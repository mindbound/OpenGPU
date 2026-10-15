package io.github.mindbound.opengpu.core.device;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.ErrorCode;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.cmd.LiveView;
import io.github.mindbound.opengpu.core.cmd.TextureHandles;
import io.github.mindbound.opengpu.core.cmd.Validation;
import io.github.mindbound.opengpu.core.cmd.Validator;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.ReadbackSink;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.RenderHost;
import io.github.mindbound.opengpu.core.render.ResetRecord;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexFreeRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;

/**
 * One card environment's device (28 §2.2; 24:80): the live view, the open frame, the server tail and the confirmed
 * state with the texture masters (28:119-123), the methods behind the M0 callbacks (28 §2.1, §3.6) with their checks,
 * messages and charges (28 §8.2), the fold of readbacks (O6), frame statuses (28:108-115) and the lifecycle
 * (28:146-155).
 *
 * <p>
 * Threads (24 §3.5). One private monitor guards the state, so a device's records are offered in call order (O1) and
 * validation sees a consistent table. Under it the device calls only the host's {@code available}, {@code stalled},
 * {@code open}, {@code offer} and {@code release}, and the clock; budgets, the listener and the fold executor are
 * called outside it. A callback whose charge follows its checks runs in three phases: the checks under the monitor;
 * the charge outside it; under the monitor again, the same checks and the effect, so a failed second check returns
 * its error with the charge spent. Readbacks are copied into a device-owned ring of at most three surface arrays (K's,
 * one pending, one spare) under a leaf lock, and folded on the executor, which must run every task it accepts
 * ({@link #create}). A fold, and the at-once fold of a resource record, run under the monitor: at most the queued-bytes
 * cap of copies (H25).
 */
public final class Device implements LiveView, ReadbackSink {

    /** 28:112: a frame still unconfirmed this long after its present is reported dropped. */
    public static final long DROP_AFTER_NANOS = 1_000_000_000L;

    private static final String RELEASED = "device released";

    /** A frame status waiting for the listener. */
    private static final class Notice {

        final boolean done;
        final long frameId;

        Notice(boolean done, long frameId) {
            this.done = done;
            this.frameId = frameId;
        }
    }

    private final DeviceKey key;
    private final DeviceCaps caps;
    private final SurfaceDesc surface;
    private final int bpp;
    private final RenderHost host;
    private final Executor folds;
    private final FrameListener listener;
    private final LongSupplier nanoClock;
    private final Runnable foldTask = this::fold;

    /** Guards everything below it, up to the ring. */
    private final Object monitor = new Object();
    private final TextureTable textures = new TextureTable();
    private final OpenFrame open = new OpenFrame();
    private final ServerTail tail = new ServerTail();
    private final Masters masters = new Masters();
    private final ArrayDeque<Notice> notices = new ArrayDeque<>();
    private long vramUsed;
    private long lastFrameId;
    private long confirmedFrameId;
    /** K's pixels: replaced whole by a fold, never written. */
    private byte[] confirmed;
    private volatile long epoch;
    private volatile boolean released;
    /** {@code bitblt}'s exhausted flag (28:712), shared by createTexture and updateTexture (H14). */
    private final AtomicBoolean budgetExhausted = new AtomicBoolean();

    /** Taken before the monitor, never after it: keeps the listener's calls in order. */
    private final Object deliveryLock = new Object();

    /** A leaf, taken after the monitor or alone: the readback ring. */
    private final Object readbackLock = new Object();
    private byte[] pending;
    private long pendingEpoch;
    private long pendingFrameId;
    private byte[] spare;
    private boolean foldScheduled;
    /** Folds that have taken the pending readback: tells a refused hand-off from a fold run inside execute. */
    private long foldStarts;

    private final AtomicLong staleReadbacks = new AtomicLong();
    private final AtomicLong rejectedReadbacks = new AtomicLong();

    private Device(DeviceKey key, DeviceCaps caps, RenderHost host, Executor folds, FrameListener listener,
        LongSupplier nanoClock, long initialEpoch) {
        this.key = key;
        this.caps = caps;
        this.surface = caps.surface;
        this.bpp = surface.bytesPerPixel();
        this.host = host;
        this.folds = folds;
        this.listener = listener;
        this.nanoClock = nanoClock;
        this.epoch = initialEpoch;
        this.confirmed = new byte[surface.byteSize()];
    }

    /**
     * Builds a device with epoch 1, frame ids from 1 and K = 0, the cleared image (all zero bytes; 24:129; 21:242), and
     * calls {@code host.open(key, caps.surface, device)} before returning, so no offer can precede it (28:199).
     *
     * @param folds     runs fold tasks: the root's OpenGPU pool (21:91); in tests {@code Runnable::run} or a manual
     *                  executor. A device keeps at most one fold task queued (amendment B), so every task
     *                  {@code execute} accepts must eventually run. Refusing by throwing is safe: the readback counts
     *                  rejected and the next one schedules a fold again. A pool that silently discards an accepted
     *                  task ({@code DiscardPolicy}, {@code DiscardOldestPolicy}, the tasks {@code shutdownNow}
     *                  drains) leaves the device's frames unconfirmed until it is released: no later readback folds,
     *                  and {@code present} returns {@code "busy"} once the cap of unconfirmed frames is reached, also
     *                  after a reset. {@code CallerRunsPolicy} would run the fold, under the device monitor (H25), on
     *                  the host's thread
     * @param nanoClock {@code System::nanoTime} in the root
     * @throws NullPointerException for a null argument
     */
    public static Device create(DeviceKey key, DeviceCaps caps, RenderHost host, Executor folds, FrameListener listener,
        LongSupplier nanoClock) {
        return create(key, caps, host, folds, listener, nanoClock, 1);
    }

    /**
     * The same with a first epoch, for a key opened again after a release (28:199): it must be above the released
     * device's last epoch.
     *
     * @throws NullPointerException     for a null argument
     * @throws IllegalArgumentException for an initialEpoch below 1
     */
    public static Device create(DeviceKey key, DeviceCaps caps, RenderHost host, Executor folds, FrameListener listener,
        LongSupplier nanoClock, long initialEpoch) {
        if (key == null || caps == null || host == null || folds == null || listener == null || nanoClock == null) {
            throw new NullPointerException("key, caps, host, folds, listener and nanoClock");
        }
        if (initialEpoch < 1) {
            throw new IllegalArgumentException("initial epoch " + initialEpoch + " is below 1");
        }
        Device device = new Device(key, caps, host, folds, listener, nanoClock, initialEpoch);
        synchronized (device.monitor) {
            host.open(key, device.surface, device);
        }
        return device;
    }

    /** The key the device was created with. */
    public DeviceKey key() {
        return key;
    }

    /** The caps the device was created with. */
    public DeviceCaps caps() {
        return caps;
    }

    /** The current epoch (28:173): +1 per reset and at release. */
    public long epoch() {
        return epoch;
    }

    // ---- The M0 callbacks (28 §2.1, §3.6). Each throws IllegalStateException("device released") after release(),
    // ---- before any check or charge, and passes through whatever the budget throws.

    /** {@code getCaps()}: {@link DeviceCaps#luaTable()}, uncharged (28:707); works after release too. */
    public Map<String, Object> getCaps() {
        return caps.luaTable();
    }

    /**
     * {@code submit(s)} (28:97, §3.5): charged first, then validated whole against the live view and the open frame's
     * totals; an accepted segment joins the open frame as the array itself (C14) and nothing is offered (O2).
     *
     * @return true, or {@code nil, "<offset>: <OP>: <reason>"} with the open frame unchanged
     * @throws NullPointerException for a null budget or stream
     */
    public CallResult submit(CallBudget budget, byte[] stream) {
        ensureNotReleased();
        if (budget == null || stream == null) {
            throw new NullPointerException("budget and stream");
        }
        budget.consume(Charges.submit(caps.tier, stream.length));
        synchronized (monitor) {
            ensureNotReleased();
            Validation v = Validator.validate(stream, this, open.totals());
            if (!v.isAccepted()) {
                return CallResult.rejected(v.error());
            }
            open.append(stream, v.totals());
            return CallResult.TRUE;
        }
    }

    /**
     * {@code present()} (28:98, :105-108): charged first; then {@code "no renderer"} (the open frame discarded and its
     * deferred frees appended), {@code "host render stalled"} or {@code "busy"} at the cap of unconfirmed frames (the
     * open frame kept), in that order (H3); else the open frame becomes {@code FRAME(id)}, appended and offered, then
     * its deferred frees.
     *
     * @return the new frame id, or {@code nil, status}
     * @throws NullPointerException for a null budget
     */
    public CallResult present(CallBudget budget) {
        ensureNotReleased();
        if (budget == null) {
            throw new NullPointerException("budget");
        }
        budget.consume(Charges.present(caps.tier));
        synchronized (monitor) {
            ensureNotReleased();
            if (!host.available()) {
                int[] frees = open.deferredFrees();
                open.clear();
                appendFrees(frees);
                return CallResult.error(Statuses.NO_RENDERER);
            }
            if (host.stalled()) {
                return CallResult.error(Statuses.HOST_RENDER_STALLED);
            }
            if (tail.unconfirmedFrames() >= caps.maxUnconfirmedFrames) {
                return CallResult.error(Statuses.BUSY);
            }
            assert open.segmentsIntact() : "a segment was written after its submit (C14)";
            FrameRecord frame = new FrameRecord(epoch, lastFrameId + 1, open.segments(), open.totals());
            int[] frees = open.deferredFrees();
            open.clear();
            lastFrameId = frame.frameId;
            host.offer(key, frame);
            tail.addFrame(frame, nanoClock.getAsLong());
            appendFrees(frees);
            return CallResult.frameId(frame.frameId);
        }
    }

    /**
     * {@code readPixels(x, y, w, h)} (28:99, :342): the region of the confirmed frame K in the 28 §5.7 layout, in a new
     * array; charged w·h·bpp/256 KB after both checks (H15).
     *
     * @return {@code data, K}, or {@code nil, "invalid region"} or {@code nil, "region exceeds N bytes per call"}
     * @throws NullPointerException for a null budget
     */
    public CallResult readPixels(CallBudget budget, int x, int y, int w, int h) {
        ensureNotReleased();
        if (budget == null) {
            throw new NullPointerException("budget");
        }
        int width = surface.width;
        int height = surface.height;
        if (!(0 <= x && x <= width && 1 <= w && w <= width - x && 0 <= y && y <= height && 1 <= h && h <= height - y)) {
            return CallResult.error("invalid region");
        }
        long n = (long) w * h * bpp;
        if (n > caps.maxReadbackBytes) {
            return CallResult.error("region exceeds " + caps.maxReadbackBytes + " bytes per call");
        }
        budget.consume(Charges.bytes(n));
        synchronized (monitor) {
            ensureNotReleased();
            byte[] out = new byte[(int) n];
            int row = w * bpp;
            for (int j = 0; j < h; j++) {
                System.arraycopy(confirmed, ((y + j) * width + x) * bpp, out, j * row, row);
            }
            return CallResult.pixels(out, confirmedFrameId);
        }
    }

    /**
     * {@code createTexture(w, h[, format])} (28:100, :343, §6.2). Checks in order: {@code "invalid texture size"},
     * {@code "unsupported format"}, {@code "too many textures"}, {@code "not enough video memory"}, {@code "busy"}
     * while the open frame's deferred frees would pass a cap (H23), {@code "busy"} when the record would join the tail
     * past its bound (H2); then the two-phase charge; then a zeroed texture in the surface's format and its
     * {@code TEX_CREATE}.
     *
     * @param format null (Lua nil) or the surface format's exact name
     * @return the handle, or {@code nil, msg}
     * @throws LimitReached         at the two-phase pattern's first attempt, with nothing changed
     * @throws NullPointerException for a null budget
     */
    public CallResult createTexture(CallBudget budget, int w, int h, String format) {
        ensureNotReleased();
        if (budget == null) {
            throw new NullPointerException("budget");
        }
        int size;
        synchronized (monitor) {
            ensureNotReleased();
            String error = createError(w, h, format);
            if (error != null) {
                budgetExhausted.set(false);
                return CallResult.error(error);
            }
            size = w * h * bpp;
        }
        chargeTwoPhase(budget, Charges.bytes(size));
        synchronized (monitor) {
            ensureNotReleased();
            String error = createError(w, h, format);
            if (error != null) {
                budgetExhausted.set(false);
                return CallResult.error(error);
            }
            int handle = textures.allocate(w, h, surface.format);
            vramUsed += size;
            appendResource(new TexCreateRecord(epoch, handle, w, h, surface.format));
            return CallResult.handle(handle);
        }
    }

    /**
     * {@code updateTexture(tex, x, y, w, h, data)} (28:101, :344, §6.3). Checks in order: the handle messages,
     * {@code "invalid texture region"}, {@code "data must be N bytes"}, {@code "busy"} when the record would join the
     * tail past its bound (H2); then the two-phase charge of {@code #data}/256 KB; then {@code TEX_UPDATE} with the
     * array itself (28:562).
     *
     * @return true, or {@code nil, msg}
     * @throws LimitReached         at the two-phase pattern's first attempt, with nothing changed
     * @throws NullPointerException for a null budget or data
     */
    public CallResult updateTexture(CallBudget budget, int tex, int x, int y, int w, int h, byte[] data) {
        ensureNotReleased();
        if (budget == null || data == null) {
            throw new NullPointerException("budget and data");
        }
        synchronized (monitor) {
            ensureNotReleased();
            String error = updateError(tex, x, y, w, h, data);
            if (error != null) {
                budgetExhausted.set(false);
                return CallResult.error(error);
            }
        }
        chargeTwoPhase(budget, Charges.bytes(data.length));
        synchronized (monitor) {
            ensureNotReleased();
            String error = updateError(tex, x, y, w, h, data);
            if (error != null) {
                budgetExhausted.set(false);
                return CallResult.error(error);
            }
            appendResource(new TexUpdateRecord(epoch, tex, x, y, w, h, data));
            return CallResult.TRUE;
        }
    }

    /**
     * {@code free(tex)} (28:102, :345, §6.4, O4): uncharged and never {@code "busy"}. The handle is invalid at once,
     * its VRAM credited at once and its slot queued with a new generation (H21); its {@code TEX_FREE} is appended now,
     * or deferred to the open frame's present or discard.
     *
     * @return true, or {@code nil} and a handle message
     */
    public CallResult free(int tex) {
        ensureNotReleased();
        synchronized (monitor) {
            ensureNotReleased();
            ErrorCode code = TextureHandles.check(tex, this);
            if (code != null) {
                return CallResult.error(TextureHandles.reason(code, tex));
            }
            int bytes = textures.byteSize(tex);
            vramUsed -= bytes;
            textures.free(tex);
            if (open.isOpen()) {
                open.deferFree(tex, bytes);
            } else {
                appendResource(new TexFreeRecord(epoch, tex));
            }
            return CallResult.TRUE;
        }
    }

    // ---- Lifecycle (28:146-155)

    /**
     * {@code computer.stopped} or {@code computer.started} (28:150): epoch + 1; the open frame with its deferred frees,
     * the tail (its frames without a status), the masters and every live handle (ascending slot, 28:1061) are dropped;
     * K, its pixels and the frame counter stay; then {@code RESET} with the new epoch and a copy of K's pixels is
     * offered. Nothing after {@link #release()}.
     */
    public void reset() {
        synchronized (monitor) {
            if (released) {
                return;
            }
            epoch++;
            open.clear();
            tail.clear();
            masters.clear();
            textures.freeAllAscending();
            vramUsed = 0;
            budgetExhausted.set(false);
            host.offer(key, new ResetRecord(epoch, confirmed.clone()));
        }
    }

    /**
     * The card is removed or moved, or the computer's chunk unloads (28:151): epoch + 1, the open frame, tail, masters
     * and undelivered statuses dropped, then {@code host.release(key)}; nothing is offered afterwards. The callbacks
     * then throw; {@link #snapshot()}, {@link #getCaps()} and {@link #tick()} still work. A second call does nothing.
     */
    public void release() {
        synchronized (monitor) {
            if (released) {
                return;
            }
            released = true;
            epoch++;
            open.clear();
            tail.clear();
            masters.clear();
            notices.clear();
            host.release(key);
        }
    }

    /** Whether {@link #release()} ran. */
    public boolean isReleased() {
        return released;
    }

    /**
     * Once per server tick, from the root (H12): reports {@code dropped} for each frame unconfirmed
     * {@link #DROP_AFTER_NANOS} after its present, once per frame, then delivers waiting statuses. Any thread.
     */
    public void tick() {
        synchronized (monitor) {
            if (!released) {
                for (long id : tail.dueForDrop(nanoClock.getAsLong())) {
                    notices.addLast(new Notice(false, id));
                }
            }
        }
        deliverNotices();
    }

    // ---- ReadbackSink (28:194)

    /**
     * Copies the pixels into the device's ring and schedules one fold; never blocks on the device's monitor and never
     * throws for any input. Counts as rejected a wrong key, a null array, a wrong length or a hand-off the executor
     * refuses by throwing, and as stale an arrival on a released device or one not newer (epoch, then frame id) than
     * the readback already pending. A refused hand-off clears the scheduled fold, so the next readback schedules one
     * again; a RuntimeException from {@code execute} is swallowed and an Error rethrown. With a direct executor (the
     * tests') the fold runs inside {@code execute}, and whatever it throws propagates as it is.
     */
    @Override
    public void onReadback(long session, int deviceId, long readbackEpoch, long frameId, byte[] pixels) {
        if (session != key.session || deviceId != key.deviceId
            || pixels == null
            || pixels.length != surface.byteSize()) {
            rejectedReadbacks.incrementAndGet();
            return;
        }
        if (released) {
            staleReadbacks.incrementAndGet();
            return;
        }
        boolean schedule;
        long ticket;
        synchronized (readbackLock) {
            if (pending != null
                && (readbackEpoch < pendingEpoch || readbackEpoch == pendingEpoch && frameId <= pendingFrameId)) {
                staleReadbacks.incrementAndGet();
                return;
            }
            // The spare, else the superseded pending array, else a new one: the newest readback wins (21:87)
            byte[] copy;
            if (spare == null && pending != null) {
                copy = pending;
            } else {
                copy = spare != null ? spare : new byte[pixels.length];
                spare = pending;
            }
            System.arraycopy(pixels, 0, copy, 0, pixels.length);
            pending = copy;
            pendingEpoch = readbackEpoch;
            pendingFrameId = frameId;
            schedule = !foldScheduled;
            foldScheduled = true;
            ticket = foldStarts;
        }
        if (schedule) {
            // A throw from execute before the fold started is a refusal (amendment B names RejectedExecutionException):
            // left scheduled, the flag would keep every later readback from scheduling a fold
            try {
                folds.execute(foldTask);
            } catch (RuntimeException e) {
                if (!unschedule(ticket)) {
                    throw e;
                }
            } catch (Error e) {
                unschedule(ticket);
                throw e;
            }
        }
    }

    // ---- LiveView (S6): consistent under the monitor, i.e. inside submit's validation; never allocates or boxes

    /** {@code caps().surface}. */
    @Override
    public SurfaceDesc surface() {
        return surface;
    }

    /** {@code caps().limits}. */
    @Override
    public FrameLimits limits() {
        return caps.limits;
    }

    /** The live texture's format, or 0 unless exactly this handle is live; any int. */
    @Override
    public int textureFormat(int handle) {
        return textures.format(handle);
    }

    /** The live texture's width, or 0. */
    @Override
    public int textureWidth(int handle) {
        return textures.width(handle);
    }

    /** The live texture's height, or 0. */
    @Override
    public int textureHeight(int handle) {
        return textures.height(handle);
    }

    /** A consistent copy of the state, taken under the monitor; any thread, also after release. */
    public DeviceSnapshot snapshot() {
        synchronized (monitor) {
            List<DeviceSnapshot.LiveTexture> live = new ArrayList<>();
            for (int handle : textures.liveHandles()) {
                live.add(
                    new DeviceSnapshot.LiveTexture(
                        handle,
                        textures.width(handle),
                        textures.height(handle),
                        textures.format(handle)));
            }
            List<Integer> deferred = new ArrayList<>();
            for (int handle : open.deferredFrees()) {
                deferred.add(handle);
            }
            return new DeviceSnapshot(
                key,
                epoch,
                released,
                lastFrameId,
                confirmedFrameId,
                confirmed.clone(),
                Collections.unmodifiableList(tail.records()),
                tail.unconfirmedFrames(),
                tail.queuedBytes(),
                vramUsed,
                Collections.unmodifiableList(live),
                masters.copy(),
                textures.freeSlots(),
                textures.generations(),
                Collections.unmodifiableList(Arrays.asList(open.segments())),
                open.totals(),
                Collections.unmodifiableList(deferred),
                open.deferredBytes(),
                budgetExhausted.get(),
                staleReadbacks.get(),
                rejectedReadbacks.get());
        }
    }

    @Override
    public String toString() {
        return "Device[" + key + " e" + epoch + (released ? " released" : "") + ", " + surface + "]";
    }

    // ---- Internals, under the monitor unless said otherwise

    private void ensureNotReleased() {
        if (released) {
            throw new IllegalStateException(RELEASED);
        }
    }

    /** createTexture's checks (28:343; H2, H23), or null. */
    private String createError(int w, int h, String format) {
        if (w < 1 || w > caps.maxTextureSize || h < 1 || h > caps.maxTextureSize) {
            return "invalid texture size";
        }
        if (format != null && Formats.byName(format) != surface.format) {
            return "unsupported format";
        }
        int live = textures.liveCount();
        if (live >= caps.maxTextures) {
            return "too many textures";
        }
        long size = (long) w * h * bpp;
        if (size > caps.vramBytes - vramUsed) {
            return "not enough video memory";
        }
        if (live + open.deferredCount() >= caps.maxTextures
            || vramUsed + open.deferredBytes() + size > caps.vramBytes) {
            return Statuses.BUSY;
        }
        if (!tail.isEmpty() && tail.queuedBytes() + size + Record.RESOURCE_OVERHEAD > caps.vramBytes) {
            return Statuses.BUSY;
        }
        return null;
    }

    /** updateTexture's checks (28:344; H2), or null. */
    private String updateError(int tex, int x, int y, int w, int h, byte[] data) {
        ErrorCode code = TextureHandles.check(tex, this);
        if (code != null) {
            return TextureHandles.reason(code, tex);
        }
        int tw = textures.width(tex);
        int th = textures.height(tex);
        if (!(0 <= x && x <= tw && 1 <= w && w <= tw - x && 0 <= y && y <= th && 1 <= h && h <= th - y)) {
            return "invalid texture region";
        }
        int n = w * h * bpp;
        if (data.length != n) {
            return "data must be " + n + " bytes";
        }
        if (!tail.isEmpty() && tail.queuedBytes() + data.length + Record.RESOURCE_OVERHEAD > caps.vramBytes) {
            return Statuses.BUSY;
        }
        return null;
    }

    /**
     * {@code bitblt}'s two-phase pattern ({@code GraphicsCard.scala:233-251}; 28:710-713), outside the monitor: a
     * cost above the credit throws LimitReached at the first attempt and, at the retry, pauses when far above and
     * proceeds uncharged; a cost within it is consumed plainly.
     */
    private void chargeTwoPhase(CallBudget budget, double cost) {
        if (Charges.twoPhase(caps.tier, cost)) {
            if (!budgetExhausted.getAndSet(true)) {
                throw new LimitReached();
            }
            double pause = Charges.pauseSeconds(caps.tier, cost);
            if (pause > 0) {
                budget.pause(pause);
            }
            budgetExhausted.set(false);
        } else {
            budgetExhausted.set(false);
            budget.consume(cost);
        }
    }

    /** Offers a resource record and folds it at once on an empty tail (O6, 21:229), else queues it. */
    private void appendResource(Record r) {
        host.offer(key, r);
        if (tail.isEmpty()) {
            masters.apply(r);
        } else {
            tail.addResource(r);
        }
    }

    private void appendFrees(int[] handles) {
        for (int handle : handles) {
            appendResource(new TexFreeRecord(epoch, handle));
        }
    }

    /** The fold of the pending readback (O6; 28:137, :155; H8), on the executor's thread. */
    private void fold() {
        synchronized (monitor) {
            byte[] pixels;
            long readbackEpoch;
            long n;
            synchronized (readbackLock) {
                foldScheduled = false;
                foldStarts++;
                pixels = pending;
                readbackEpoch = pendingEpoch;
                n = pendingFrameId;
                pending = null;
            }
            if (pixels == null) {
                return;
            }
            if (released || readbackEpoch != epoch || n <= confirmedFrameId) {
                staleReadbacks.incrementAndGet();
                recycle(pixels);
                return;
            }
            if (!tail.containsFrame(n)) {
                rejectedReadbacks.incrementAndGet();
                recycle(pixels);
                return;
            }
            while (true) {
                Record r = tail.removeFirst();
                if (r.kind() == Record.FRAME) {
                    if (((FrameRecord) r).frameId == n) {
                        break;
                    }
                } else {
                    masters.apply(r);
                }
            }
            recycle(confirmed);
            confirmed = pixels;
            confirmedFrameId = n;
            while (!tail.isEmpty() && tail.first()
                .isResource()) {
                masters.apply(tail.removeFirst());
            }
            notices.addLast(new Notice(true, n));
        }
        deliverNotices();
    }

    /**
     * After {@code folds.execute} threw, outside the monitor: unless a fold has started since the hand-off (one run
     * inside execute, which threw itself), clears the scheduled flag, so the next readback schedules a fold again,
     * keeps the pending array as the spare and counts the readback rejected.
     *
     * @return whether the hand-off was refused
     */
    private boolean unschedule(long ticket) {
        synchronized (readbackLock) {
            if (foldStarts != ticket) {
                return false;
            }
            foldScheduled = false;
            if (spare == null) {
                spare = pending;
            }
            pending = null;
        }
        rejectedReadbacks.incrementAndGet();
        return true;
    }

    /** Keeps the array as the ring's spare if it has none. */
    private void recycle(byte[] array) {
        synchronized (readbackLock) {
            if (spare == null) {
                spare = array;
            }
        }
    }

    /** Hands the waiting statuses to the listener in order, outside the monitor; never called under it. */
    private void deliverNotices() {
        synchronized (deliveryLock) {
            while (true) {
                Notice notice;
                synchronized (monitor) {
                    notice = notices.pollFirst();
                }
                if (notice == null) {
                    return;
                }
                if (notice.done) {
                    listener.frameDone(notice.frameId);
                } else {
                    listener.frameDropped(notice.frameId);
                }
            }
        }
    }
}
