package io.github.mindbound.opengpu.gl.host;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.ReadbackSink;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.RenderHost;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexFreeRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;

/**
 * The host side of 28 §2.4 for GL (H29): per-device queues, the START pass of 21:85-88 within a budget (24:173), round
 * robin, the readback of each device's newest frame (21:87), {@code release} and {@code releaseSession} (21:106,
 * :111), the heartbeat behind {@link #stalled()} (24:126; 20:192-194) and H24's bound through host-side texture mirrors
 * (S8 plan §3.10; Q-S8-1, the owner's option A with amendment A's copy-on-write). {@code GlTestHost} and the root's
 * {@code GlRenderHost} wrap it. It makes no GL call itself: it drives a {@link HostBackend}, so its logic is tested
 * without a context against the tier-E oracle.
 * <p>
 * Threads (S8 plan §3.12). The {@link RenderHost} methods run on any thread, usually under a {@code Device}'s monitor:
 * they never throw for any input, never call a device or a sink, and block at most for one of the render thread's lock
 * holds on the device (amendment D): one record's apply, which with copy-on-write is at most one texture's clone and
 * one payload's rows (under 4 MiB at T3, amendment A), or one copy of an owned mirror's dirty box (≤ 2 MiB). A
 * contract breach, an offer to a key that is not open or a second open, is counted ({@link #contractViolations()},
 * H47) and dropped. {@link #pass}, {@link #close} and the GL-thread accessors run on one thread, the first that ran a
 * pass with
 * work (H57; GLSM's main thread under Angelica). Sinks are called after the pass, outside the backend's bracket and
 * with no host lock held (28:1072; H67). The openings live in a concurrent map, the round-robin order is a
 * copy-on-write list under the host's lock, and each opening's own lock is a leaf; neither lock is held while the
 * other is taken.
 * <p>
 * Failure (20:240-242; H63). An exception from the backend inside a pass fails the host for good: the reason is logged
 * once, every opening's state is dropped, {@code backend.close()} is tried, {@link #available()} answers false from
 * then on (so {@code present} answers {@code "no renderer"}, which discards the open frame), and offers are dropped
 * uncounted.
 */
public final class GlHostCore implements RenderHost {

    /** 20:194; 24:126: a render loop silent this long is stalled. */
    public static final long STALL_AFTER_NANOS = 2_000_000_000L;

    /**
     * A safety bound on the queued resource records one opening applies in a row. The device's tail bound keeps the
     * real count far below it (at most 65 536 records at T3, 28:140), so it is reached only by a host that offers
     * outside the device contract.
     */
    static final int APPLY_CAP = 1 << 20;

    private final HostBackend backend;
    private final HostOptions options;
    private final LongSupplier clock;
    private final AtomicLong violations = new AtomicLong();

    /** Guards {@link #order}, {@link #dying} and every change of {@link #openings}; no other lock is taken in it. */
    private final Object hostLock = new Object();
    /** The live opening of each key. */
    private final ConcurrentHashMap<DeviceKey, HostDevice> openings = new ConcurrentHashMap<>();
    /**
     * The current or last opening of each key, for {@link #stats}; a session's go with {@link #releaseSession}, all
     * with {@link #close}. M0's root opens a fresh key for every new device (28:1080), so a released opening stays here
     * until its session ends: it lets go of its sink at once and of its backend at step (d), which leaves a shell of
     * counters.
     */
    private final ConcurrentHashMap<DeviceKey, HostDevice> lastOpenings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DeviceKey> displays = new ConcurrentHashMap<>();
    /** The live openings in open order, the round robin's; replaced whole under the host lock. */
    private List<HostDevice> order = Collections.emptyList();
    /** Released openings whose GL objects step (d) deletes at the next pass (H46). */
    private List<HostDevice> dying = new ArrayList<>();

    private volatile long heartbeat;
    private volatile long passes;
    private volatile boolean available = true;
    private volatile String failure;
    private volatile boolean closed;
    private volatile Thread glThread;

    // The GL thread's
    private int cursor;
    private final HostDevice.Unit unit = new HostDevice.Unit();
    private final HostDevice.Delivery delivery = new HostDevice.Delivery();

    /**
     * No GL work; the heartbeat starts now (H32), so a host that has not yet run a pass is stalled only once the
     * threshold has passed.
     *
     * @throws NullPointerException for a null argument
     */
    public GlHostCore(HostBackend backend, HostOptions options) {
        if (backend == null || options == null) {
            throw new NullPointerException("backend and options");
        }
        this.backend = backend;
        this.options = options;
        this.clock = options.clock();
        this.heartbeat = clock.getAsLong();
    }

    // ---- RenderHost: any thread; never throws for any input

    /** {@link #setAvailable}'s value, true by default; false once the host failed (H63) or closed. */
    @Override
    public boolean available() {
        return available && failure == null && !closed;
    }

    /** Whether no pass has started for more than the options' threshold (20:192-194; H32): {@code present} reads it. */
    @Override
    public boolean stalled() {
        return clock.getAsLong() - heartbeat > options.stallAfterNanos();
    }

    /**
     * A new opening of the key (28:199), which joins the round robin; its GL objects come at its first turn. A null
     * argument or a key that is open already counts as a contract violation (H47), and the old opening stays. Nothing
     * on a failed or closed host, checked under the host's lock, which {@link #close} and a failure hold while they set
     * their flag and take the openings they close or drop: an open racing either adds no opening, stats or sink after
     * them (H63).
     */
    @Override
    public void open(DeviceKey key, SurfaceDesc surface, ReadbackSink sink) {
        if (key == null || surface == null || sink == null) {
            violations.incrementAndGet();
            return;
        }
        synchronized (hostLock) {
            if (failure != null || closed) {
                return;
            }
            if (openings.containsKey(key)) {
                violations.incrementAndGet();
                return;
            }
            HostDevice hd = new HostDevice(key, surface, sink, options.mirrors(), violations);
            openings.put(key, hd);
            lastOpenings.put(key, hd);
            List<HostDevice> next = new ArrayList<>(order);
            next.add(hd);
            order = Collections.unmodifiableList(next);
        }
    }

    /**
     * Queues one record of an open key or applies it to the mirrors (S8 plan §3.10; 28:126), under the opening's lock
     * for one queue add or one apply. A null argument or a key that is not open is counted (H47) and dropped; a failed
     * or closed host drops every record uncounted (H63). In dev builds the record's arrays are checked first (28:202,
     * :1082), the one throw that is meant.
     */
    @Override
    public void offer(DeviceKey key, Record record) {
        if (key == null || record == null) {
            violations.incrementAndGet();
            return;
        }
        if (failure != null || closed) {
            return;
        }
        HostDevice hd = openings.get(key);
        if (hd == null) {
            violations.incrementAndGet();
            return;
        }
        assert record.intact() : record + " changed after it was offered";
        hd.offer(record);
    }

    /**
     * The live opening of the key leaves the host and drops its queue, mirrors and pending state; step (d) of the next
     * pass deletes its GL objects and discards its readbacks in flight (21:106; 28:151; H46). A later {@link #open}
     * makes a new opening. Nothing for a key that is not open.
     */
    @Override
    public void release(DeviceKey key) {
        if (key == null) {
            return;
        }
        HostDevice hd;
        synchronized (hostLock) {
            hd = openings.remove(key);
            if (hd == null) {
                return;
            }
            hd.released = true;
            List<HostDevice> next = new ArrayList<>(order);
            next.remove(hd);
            order = Collections.unmodifiableList(next);
            if (failure == null && !closed) {
                dying.add(hd);
            }
        }
        hd.release();
    }

    /** Binds the display to the key, or unbinds it for a null key (21:63); a null display id is ignored. */
    @Override
    public void bindDisplay(String displayId, DeviceKey key) {
        if (displayId == null) {
            return;
        }
        if (key == null) {
            displays.remove(displayId);
        } else {
            displays.put(displayId, key);
        }
    }

    /** Releases every open key of the session and unbinds their displays (21:111); their stats go too. */
    @Override
    public void releaseSession(long session) {
        List<DeviceKey> keys = new ArrayList<>();
        for (DeviceKey key : openings.keySet()) {
            if (key.session == session) {
                keys.add(key);
            }
        }
        for (DeviceKey key : keys) {
            release(key);
        }
        displays.values()
            .removeIf(k -> k.session == session);
        lastOpenings.keySet()
            .removeIf(k -> k.session == session);
    }

    /** M1's disable path (H22): what {@link #available()} answers while the host has not failed. */
    public void setAvailable(boolean available) {
        this.available = available;
    }

    // ---- The GL thread

    /**
     * One START pass (21:85-88; 24:173; S8 plan §3.2 with amendments B and C). It moves the heartbeat (H32) and returns
     * before the bracket when nothing is owed: no new or released opening, no unit, no readback owed or in flight and
     * no allowed expand (24:193, A4). Otherwise, inside one {@link HostBackend#beginPass()}/
     * {@link HostBackend#endPass()} bracket, in this order:
     * <ol>
     * <li>(a) finished readbacks are fetched for each live opening, the newest of the pass winning (21:87);</li>
     * <li>(d) the GL objects of openings released since the last pass are deleted, before any opening of the same key
     * is made (H46);</li>
     * <li>(b) each new opening gets its objects (a cleared surface, 28:199), unless a side exceeds
     * {@link HostBackend#maxSurfaceSide()}, a contract violation that drops its records (H64);</li>
     * <li>the round robin from the cursor, while {@code budget.more()}: each opening's next unit (S8 plan §3.10), run
     * outside every lock; queued resource records are applied on the way, uncounted (amendment D), and those still
     * queued behind a taken {@code FRAME} are applied before the pass ends;</li>
     * <li>(c) an owed expand of each opening (H27, as the options allow), and its owed readback when a slot is free
     * (at most two in flight, H36; a newer frame replaces an owed one).</li>
     * </ol>
     * Then, outside the bracket and every host lock, each opening's newest fetched readback goes to its sink (28:1072;
     * H67). An exception from the backend fails the host (H63) and is not rethrown.
     *
     * @throws NullPointerException  for a null budget
     * @throws IllegalStateException on a thread other than the first that ran a pass with work (H57)
     */
    public void pass(PassBudget budget) {
        if (budget == null) {
            throw new NullPointerException("budget");
        }
        checkThread();
        heartbeat = clock.getAsLong();
        passes = passes + 1;
        if (failure != null || closed) {
            return;
        }
        List<HostDevice> live;
        synchronized (hostLock) {
            live = order;
        }
        if (!hasWork(live)) {
            return;
        }
        glThread = Thread.currentThread();
        long pass = passes;
        RuntimeException error = null;
        boolean began = false;
        try {
            backend.beginPass();
            began = true;
            int maxSide = backend.maxSurfaceSide();
            fetchReadbacks(live, pass);
            destroyReleased();
            openNew(live, maxSide);
            runUnits(live, budget);
            drainApplies(live);
            expandAndIssue(live, pass);
        } catch (RuntimeException e) {
            error = e;
        } finally {
            if (began) {
                try {
                    backend.endPass();
                } catch (RuntimeException e) {
                    if (error == null) {
                        error = e;
                    } else {
                        log("OpenGPU: closing the failed pass's bracket failed too: " + e);
                    }
                }
            }
        }
        if (error != null) {
            fail(error);
            return;
        }
        deliver(live);
    }

    /**
     * Releases every opening and unbinds every display, deletes the GL objects of every opening that has them inside
     * one bracket, then calls {@code backend.close()}; the ledger is then empty (24:218). The host is then unavailable
     * and ignores every call, and {@link #stats} answers null for every key. A second call does nothing; after a
     * failure only the bookkeeping runs.
     *
     * @throws IllegalStateException on a thread other than the GL thread (H57), or wrapping the backend's failure
     */
    public void close() {
        checkThread();
        List<HostDevice> toDestroy;
        synchronized (hostLock) {
            if (closed) {
                return;
            }
            closed = true;
            // Released ones first, then the live ones in open order
            toDestroy = dying;
            dying = new ArrayList<>();
            for (HostDevice hd : order) {
                hd.released = true;
                toDestroy.add(hd);
            }
            openings.clear();
            lastOpenings.clear();
            order = Collections.emptyList();
        }
        displays.clear();
        for (HostDevice hd : toDestroy) {
            hd.release();
        }
        if (failure != null) {
            return;
        }
        RuntimeException error = null;
        boolean any = false;
        for (HostDevice hd : toDestroy) {
            any |= hd.backend != null && !hd.destroyed;
        }
        if (any) {
            glThread = Thread.currentThread();
            boolean began = false;
            try {
                backend.beginPass();
                began = true;
                for (HostDevice hd : toDestroy) {
                    if (hd.backend != null && !hd.destroyed) {
                        hd.backend.destroy();
                    }
                    hd.destroyed();
                }
            } catch (RuntimeException e) {
                error = e;
            } finally {
                if (began) {
                    try {
                        backend.endPass();
                    } catch (RuntimeException e) {
                        if (error == null) {
                            error = e;
                        }
                    }
                }
            }
        }
        try {
            backend.close();
        } catch (RuntimeException e) {
            if (error == null) {
                error = e;
            }
        }
        if (error != null) {
            failure = describe(error);
            log("OpenGPU: closing the GL host failed: " + failure);
            throw new IllegalStateException("closing the GL host failed: " + error, error);
        }
    }

    /**
     * The surface of an open key now, through the backend's production calls ({@link DeviceBackend#readNow}, inside
     * its own bracket; amendment C): {@code GlTestHost.surfacePixels} after pumping until {@link #idle}. A fresh array
     * in the 28 §5.7 layout. A backend failure fails the host (H63) and is rethrown.
     *
     * @throws IllegalStateException on another thread than the GL thread, on a failed or closed host, for a key that is
     *                               not open or has no GL objects yet, or while its readbacks are in flight
     */
    public byte[] readNow(DeviceKey key) {
        checkThread();
        if (failure != null || closed) {
            throw new IllegalStateException(closed ? "the host is closed" : "the host failed: " + failure);
        }
        HostDevice hd = key == null ? null : openings.get(key);
        if (hd == null) {
            throw new IllegalStateException(key + " is not open");
        }
        DeviceBackend db = hd.backend;
        if (db == null || hd.refused) {
            throw new IllegalStateException(
                key + " has no GL objects: no pass has run since its open, or it was refused");
        }
        if (hd.inFlight() != 0) {
            throw new IllegalStateException(key + " has readbacks in flight: pump until idle first");
        }
        glThread = Thread.currentThread();
        int n = hd.surface.byteSize();
        byte[] out = new byte[n];
        RuntimeException error = null;
        boolean began = false;
        try {
            backend.beginPass();
            began = true;
            ByteBuffer b = backend.staging(n);
            db.readNow(b);
            ByteBuffer d = b.duplicate();
            d.clear();
            d.limit(n);
            d.get(out);
        } catch (RuntimeException e) {
            error = e;
        } finally {
            if (began) {
                try {
                    backend.endPass();
                } catch (RuntimeException e) {
                    if (error == null) {
                        error = e;
                    }
                }
            }
        }
        if (error != null) {
            fail(error);
            throw error;
        }
        return out;
    }

    /**
     * The {@link DeviceBackend} of the device bound to the display, or null when none is bound or it has no GL objects,
     * among them every device of a failed or closed host: the root's TESR asks {@code GlBackend.presentTexture} of it
     * (S8 plan §2.1) and shows the fixed "no graphics" bitmap for null (20:242). GL thread.
     *
     * @throws IllegalStateException on another thread than the GL thread (H57)
     */
    public DeviceBackend presentDevice(String displayId) {
        checkThread();
        DeviceKey key = displayId == null ? null : displays.get(displayId);
        return key == null ? null : deviceBackend(key);
    }

    /**
     * The live opening's {@link DeviceBackend}, or null for a key that is not open or has no GL objects: none yet,
     * refused (H64), or gone with a failed host's {@code backend.close()} (H63) or with {@link #close}. The test host's
     * direct reads. GL thread.
     *
     * @throws IllegalStateException on another thread than the GL thread (H57)
     */
    public DeviceBackend deviceBackend(DeviceKey key) {
        checkThread();
        if (key == null || failure != null || closed) {
            return null;
        }
        HostDevice hd = openings.get(key);
        if (hd == null || hd.refused || hd.dropped || hd.destroyed) {
            return null;
        }
        return hd.backend;
    }

    // ---- Inspection: any thread

    /**
     * The current or last opening's state, or null for a key never opened, released with its session, or of a closed
     * host. A released opening answers until its session is released or the key is opened again.
     */
    public DeviceStats stats(DeviceKey key) {
        HostDevice hd = key == null ? null : lastOpenings.get(key);
        return hd == null ? null : hd.stats(failure);
    }

    /**
     * Whether the host owes the key nothing: its live opening has its GL objects and nothing queued, pending, owed, in
     * flight or to deliver, and no released opening of the key awaits step (d). True for a key that is not open and
     * has nothing awaiting, and on a failed or closed host, where nothing will run.
     * <p>
     * Meaningful between passes only: on the GL thread, or on another thread once the pass has returned. A unit that a
     * running pass has taken (a {@code FRAME}, a pending texture's upload, a {@code RESET}) is no longer queued or
     * pending and is not counted until it has run, so a call from another thread during a pass may answer true while
     * that unit still owes a readback or an expand.
     */
    public boolean idle(DeviceKey key) {
        if (key == null || failure != null || closed) {
            return true;
        }
        HostDevice hd = openings.get(key);
        if (hd != null && !hd.idle(expandAllowed(hd))) {
            return false;
        }
        List<HostDevice> awaiting;
        synchronized (hostLock) {
            awaiting = new ArrayList<>(dying);
        }
        for (HostDevice d : awaiting) {
            if (d.key.equals(key)) {
                synchronized (d) {
                    if (d.backend != null && !d.destroyed) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** The key bound to the display, or null. */
    public DeviceKey boundDevice(String displayId) {
        return displayId == null ? null : displays.get(displayId);
    }

    /** Offers to keys that are not open, second opens, null arguments and records breaking the contract (H47, H64). */
    public long contractViolations() {
        return violations.get();
    }

    /** Calls of {@link #pass}, idle ones included; the pass number the backend sees (amendment C). */
    public long passes() {
        return passes;
    }

    /** The failure's reason (H63), or null. */
    public String failure() {
        return failure;
    }

    public HostBackend backend() {
        return backend;
    }

    public HostOptions options() {
        return options;
    }

    @Override
    public String toString() {
        return "GlHostCore[" + openings.size()
            + " open, "
            + passes
            + " passes, "
            + options
            + (failure == null ? "" : ", failed: " + failure)
            + (closed ? ", closed" : "")
            + "]";
    }

    /** The live opening (tests in this package). */
    HostDevice device(DeviceKey key) {
        return openings.get(key);
    }

    // ---- The pass's steps, on the GL thread inside the bracket

    private boolean hasWork(List<HostDevice> live) {
        synchronized (hostLock) {
            // Openings released before their first turn have no GL objects: nothing for step (d) to do
            for (int i = dying.size() - 1; i >= 0; i--) {
                if (dying.get(i).backend == null) {
                    dying.remove(i);
                }
            }
            if (!dying.isEmpty()) {
                return true;
            }
        }
        for (HostDevice hd : live) {
            if (hd.hasWork(expandAllowed(hd))) {
                return true;
            }
        }
        return false;
    }

    /** (a): the backend hands each finished readback to the opening, which keeps the newest (S8 plan §3.9). */
    private void fetchReadbacks(List<HostDevice> live, long pass) {
        for (HostDevice hd : live) {
            DeviceBackend db = hd.backend;
            if (db == null || hd.released || hd.inFlight() == 0) {
                continue;
            }
            int n = db.fetchReadbacks(hd.out(), pass, options.forceFetchAfterPasses());
            hd.fetched(n, pass);
            assert db.readbacksInFlight() == hd.inFlight() : hd + ": the backend has "
                + db.readbacksInFlight()
                + " readbacks in flight, the host counts "
                + hd.inFlight();
        }
    }

    /** (d): FBOs before textures inside {@code destroy} (R-10); readbacks in flight are discarded (21:106). */
    private void destroyReleased() {
        List<HostDevice> toDestroy;
        synchronized (hostLock) {
            if (dying.isEmpty()) {
                return;
            }
            toDestroy = dying;
            dying = new ArrayList<>();
        }
        for (HostDevice hd : toDestroy) {
            if (hd.backend != null && !hd.destroyed) {
                hd.backend.destroy();
            }
            hd.destroyed();
        }
    }

    /** (b): the first turn of each new opening, with H64's check against the backend's side limit. */
    private void openNew(List<HostDevice> live, int maxSide) {
        for (HostDevice hd : live) {
            if (hd.released || hd.backend != null || hd.refused) {
                continue;
            }
            if (hd.surface.width > maxSide || hd.surface.height > maxSide) {
                violations.incrementAndGet();
                hd.refuse();
                log(
                    "OpenGPU: " + hd.key
                        + "'s "
                        + hd.surface
                        + " surface exceeds the context's texture side limit of "
                        + maxSide
                        + "; its records are dropped (H64)");
                continue;
            }
            hd.opened(backend.open(hd.key, hd.surface));
        }
    }

    /** The round robin: one unit of the next opening that has one, per {@code budget.more()} (S8 plan §3.2). */
    private void runUnits(List<HostDevice> live, PassBudget budget) {
        int n = live.size();
        if (n == 0) {
            return;
        }
        while (budget.more()) {
            int start = cursor % n;
            HostDevice served = null;
            for (int k = 0; k < n; k++) {
                int i = (start + k) % n;
                HostDevice hd = live.get(i);
                if (takeUnit(hd)) {
                    served = hd;
                    cursor = i + 1;
                    break;
                }
            }
            if (served == null) {
                return;
            }
            run(served);
            // The unit's record and arrays are not kept until the next pass with work
            unit.clear();
        }
    }

    /** The opening's next unit into {@link #unit}, applying queued resource records on the way (amendment D). */
    private boolean takeUnit(HostDevice hd) {
        if (hd.backend == null || hd.released) {
            return false;
        }
        for (int a = 0; a < APPLY_CAP; a++) {
            int kind = hd.nextUnit(unit, backend);
            if (kind == HostDevice.Unit.NONE) {
                return false;
            }
            if (kind != HostDevice.Unit.APPLY) {
                return true;
            }
        }
        return false;
    }

    /** Runs {@link #unit} on the opening's backend, outside every lock, then records its effect under the lock. */
    private void run(HostDevice hd) {
        DeviceBackend db = hd.backend;
        HostDevice.Unit u = unit;
        switch (u.kind) {
            case HostDevice.Unit.RESET: {
                // 28 §5.7, 28:600: every texture deleted, the surface replaced, its objects kept
                // 28:202, A11: K's pixels are checked again before they are uploaded
                assert u.record.intact() : u.record + " changed after it was offered";
                SurfaceDesc s = hd.surface;
                db.resetSurface(stage(u.source, s.width, hd.bpp, 0, 0, s.width, s.height));
                hd.resetRan();
                return;
            }
            case HostDevice.Unit.SYNC: {
                if (u.deleteGpu) {
                    db.deleteTexture(u.handle);
                    hd.gpuDeleted(u.handle);
                }
                if (u.create || u.dirty) {
                    int bpp = Formats.bytesPerPixel(u.format);
                    ByteBuffer texels = u.staged != null ? u.staged
                        : stage(u.source, u.textureWidth, bpp, u.x, u.y, u.w, u.h);
                    int bytes = u.w * u.h * bpp;
                    if (u.create) {
                        db.createTexture(u.handle, u.w, u.h, u.format, texels);
                        hd.gpuCreated(u.handle, bytes);
                    } else {
                        db.updateTexture(u.handle, u.x, u.y, u.w, u.h, texels);
                        hd.uploaded(bytes);
                    }
                }
                return;
            }
            case HostDevice.Unit.FRAME: {
                FrameRecord f = (FrameRecord) u.record;
                // 28:202: a segment is checked again at execution
                assert f.intact() : f + " changed after it was offered";
                db.runFrame(f);
                hd.frameRan(f);
                return;
            }
            case HostDevice.Unit.RAW:
                runRecord(hd, db, u.record);
                return;
            default:
                throw new IllegalStateException("unit kind " + u.kind);
        }
    }

    /** Mirrors off (option B): one resource record from its own fields and payload, through staging. */
    private void runRecord(HostDevice hd, DeviceBackend db, Record r) {
        switch (r.kind()) {
            case Record.TEX_CREATE: {
                TexCreateRecord c = (TexCreateRecord) r;
                int bytes = c.byteSize();
                // 28:550, :587: a new texture is zeros
                db.createTexture(c.handle, c.width, c.height, c.format, zeros(bytes));
                hd.gpuCreated(c.handle, bytes);
                return;
            }
            case Record.TEX_UPDATE: {
                TexUpdateRecord u = (TexUpdateRecord) r;
                // 28:202, A11: the payload is checked again before it is uploaded
                assert u.intact() : u + " changed after it was offered";
                byte[] payload = u.payload();
                ByteBuffer b = backend.staging(payload.length);
                b.put(payload);
                b.flip();
                db.updateTexture(u.handle, u.x, u.y, u.width, u.height, b);
                hd.uploaded(payload.length);
                return;
            }
            case Record.TEX_FREE: {
                int handle = ((TexFreeRecord) r).handle;
                db.deleteTexture(handle);
                hd.gpuDeleted(handle);
                return;
            }
            default:
                throw new IllegalStateException(r + " cannot run as a record of its own");
        }
    }

    /** The resource records still queued behind a taken {@code FRAME}, applied before the pass ends (I-H1). */
    private void drainApplies(List<HostDevice> live) {
        for (HostDevice hd : live) {
            if (hd.backend == null || hd.released) {
                continue;
            }
            for (int a = 0; a < APPLY_CAP && hd.applyHead(); a++) {
                // one record per lock hold (amendment D)
            }
        }
    }

    /** (c): expands as the options allow (H27, H68), and each owed readback into a free slot (24:197; H36). */
    private void expandAndIssue(List<HostDevice> live, long pass) {
        for (HostDevice hd : live) {
            DeviceBackend db = hd.backend;
            if (db == null || hd.released || hd.refused) {
                continue;
            }
            if (hd.expandOwed() && expandAllowed(hd)) {
                db.expand();
                hd.expanded();
            }
            if (hd.readbackOwed() && db.readbackSlotFree()) {
                long epoch = hd.owedEpoch();
                long frameId = hd.owedFrameId();
                db.issueReadback(epoch, frameId, pass);
                hd.issued(pass);
            }
        }
    }

    /**
     * After the pass, with no host lock held: the newest fetched readback of each live opening (21:87; 28:1072), to
     * the sink taken with it under the opening's lock, so a release racing the delivery leaves the call intact (the
     * released device counts it stale, 28:194).
     */
    private void deliver(List<HostDevice> live) {
        HostDevice.Delivery d = delivery;
        for (HostDevice hd : live) {
            if (!hd.takeDelivery(d)) {
                continue;
            }
            ReadbackSink sink = d.sink;
            byte[] pixels = d.pixels;
            long epoch = d.epoch;
            long frameId = d.frameId;
            // Nothing of the delivery stays here once it is made: the host keeps no sink of a released device
            d.clear();
            try {
                sink.onReadback(hd.key.session, hd.key.deviceId, epoch, frameId, pixels);
                hd.delivered(epoch, frameId);
            } catch (RuntimeException e) {
                // ReadbackSink never throws (28:194); one that does must not stop the other devices' deliveries
                violations.incrementAndGet();
                log("OpenGPU: " + hd.key + "'s readback sink threw: " + describe(e));
            }
        }
    }

    // ---- Helpers

    /** A staging buffer holding the box of a texture backed by source (null: zeros), position 0, limit its size. */
    private ByteBuffer stage(byte[] source, int textureWidth, int bpp, int x, int y, int w, int h) {
        ByteBuffer b = backend.staging(w * h * bpp);
        Mirror.copyBox(source, textureWidth, bpp, x, y, w, h, b);
        b.flip();
        return b;
    }

    /** n zero bytes in staging. */
    private ByteBuffer zeros(int n) {
        ByteBuffer b = backend.staging(n);
        Mirror.zeros(b, n);
        b.flip();
        return b;
    }

    private boolean expandAllowed(HostDevice hd) {
        switch (options.expand()) {
            case ALWAYS:
                return true;
            case BOUND_ONLY:
                return displays.containsValue(hd.key);
            default:
                return false;
        }
    }

    private void checkThread() {
        Thread owner = glThread;
        if (owner != null && owner != Thread.currentThread()) {
            throw new IllegalStateException(
                "the GL host runs on " + owner.getName()
                    + ", the thread of its first pass with work (H57), not on "
                    + Thread.currentThread()
                        .getName());
        }
    }

    /** H63: once; every opening's state dropped, the backend closed (its own failure logged and swallowed). */
    private void fail(RuntimeException error) {
        List<HostDevice> toDrop;
        synchronized (hostLock) {
            if (failure != null) {
                return;
            }
            failure = describe(error);
            toDrop = new ArrayList<>(openings.values());
            toDrop.addAll(dying);
            dying = new ArrayList<>();
        }
        log(
            "OpenGPU: the GL host failed and is unavailable from now on (20:242); devices answer \"no renderer\": "
                + stackTrace(error));
        for (HostDevice hd : toDrop) {
            hd.drop();
        }
        try {
            backend.close();
        } catch (RuntimeException e) {
            log("OpenGPU: closing the failed GL backend failed too: " + describe(e));
        }
    }

    private void log(String message) {
        try {
            options.log()
                .accept(message);
        } catch (RuntimeException e) {
            // a log must not break the host
        }
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return e.getClass()
            .getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static String stackTrace(Throwable e) {
        StringWriter text = new StringWriter();
        e.printStackTrace(new PrintWriter(text));
        return text.toString();
    }
}
