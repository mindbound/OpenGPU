package io.github.mindbound.opengpu.gl.host;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
import io.github.mindbound.opengpu.core.font.GlyphTable;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FrameRecord;

/**
 * A {@link HostBackend} without GL for {@link GlHostCore}'s CPU tests (S8 plan §4.1, amendment C): each opening's
 * {@link DeviceBackend} keeps a {@link TierEOracle} as its "GPU" (textures from the bytes it is given,
 * {@code runFrame} through the oracle, {@code expand} recorded), and a readback ring of three slots, at most two in
 * flight, as plan §3.9:
 * a readback copies the oracle's surface at its issue and is finished {@link #latency} passes after it (or a latency of
 * its own, {@link #latencyPerIssue}), or never under {@link #neverReady} (the {@code NEVER_READY} query policy), or
 * when {@code pass - issuedAt >= forceAfterPasses}; a fetch takes finished ones oldest first and stops at the first
 * one that is not, as GL's ring does.
 * <p>
 * It checks what {@code GlHostCore} owes the seam: every call but {@code maxSurfaceSide} and {@code staging} inside a
 * bracket, one thread for everything, no third readback while two are in flight, no {@code readNow} with readbacks in
 * flight, buffers at position 0 with exactly the bytes the call needs (B9), and no call on a destroyed device or after
 * {@code close}; a breach throws {@link IllegalStateException} or {@link IllegalArgumentException}, which the host
 * treats as a backend failure (H63), so the tests see it. {@code staging} fills its buffer with 0xA5 at every call, so
 * code that relies on stale staging contents shows, and it records whether each upload's buffer is the one
 * {@code staging} last handed out ({@link #uploads()}: GlBackend measures G13b's {@code stageNanos} only then, 28
 * §6.7). Its fixtures: {@link #failBeginPassAt} for the failure path,
 * {@link #maxSurfaceSide(int)} for H64, a call log for "an idle host makes no backend call", and an optional hook run
 * inside every device call (the lock-held-across-a-GL-call tests). Thread-safe by one lock; the hook runs outside it.
 */
final class OracleBackend implements HostBackend {

    /** One opening's GL objects. */
    final class Device implements DeviceBackend {

        final DeviceKey key;
        final SurfaceDesc surface;
        final TierEOracle oracle;
        final int bpp;
        /** The "GPU" textures: handle to (w, h); the oracle holds their texels. */
        final Map<Integer, int[]> textures = new HashMap<>();
        final long[] slotEpoch = new long[3];
        final long[] slotFrame = new long[3];
        final long[] slotIssuedAt = new long[3];
        /** Under {@link #latencyPerIssue}: the slot's own latency, drawn at its issue. */
        final int[] slotLatency = new int[3];
        final long[] slotOrder = new long[3];
        final byte[][] slotPixels = new byte[3][];
        final boolean[] slotBusy = new boolean[3];
        long issueCount;
        boolean destroyed;
        int frames, expands, creates, updates, deletes, resets, issued, fetched, readNows;
        long uploadBytes;
        /** The newest frame that ran, or -1. */
        long lastFrameEpoch = -1, lastFrameId = -1;

        Device(DeviceKey key, SurfaceDesc surface) {
            this.key = key;
            this.surface = surface;
            this.oracle = new TierEOracle(surface, glyphs);
            this.bpp = surface.bytesPerPixel();
        }

        @Override
        public void createTexture(int handle, int width, int height, int format, ByteBuffer texels) {
            hook("createTexture");
            synchronized (OracleBackend.this) {
                live("createTexture");
                if (format != surface.format) {
                    throw new IllegalArgumentException("format " + format + " on a " + surface + " surface (28:551)");
                }
                byte[] bytes = take(texels, width * height * Formats.bytesPerPixel(format), "createTexture");
                upload("create", texels);
                if (textures.containsKey(handle)) {
                    throw new IllegalStateException(hex(handle) + " exists on the GPU already");
                }
                oracle.createTexture(handle, width, height, format);
                oracle.updateTexture(handle, 0, 0, width, height, bytes);
                textures.put(handle, new int[] { width, height });
                creates++;
                uploadBytes += bytes.length;
                log("create " + key + " " + hex(handle));
            }
        }

        @Override
        public void updateTexture(int handle, int x, int y, int width, int height, ByteBuffer texels) {
            hook("updateTexture");
            synchronized (OracleBackend.this) {
                live("updateTexture");
                byte[] bytes = take(texels, width * height * bpp, "updateTexture");
                upload("update", texels);
                oracle.updateTexture(handle, x, y, width, height, bytes);
                updates++;
                uploadBytes += bytes.length;
                log(String.format(Locale.ROOT, "update %s %s %d,%d %dx%d", key, hex(handle), x, y, width, height));
            }
        }

        @Override
        public void deleteTexture(int handle) {
            hook("deleteTexture");
            synchronized (OracleBackend.this) {
                live("deleteTexture");
                oracle.freeTexture(handle);
                textures.remove(handle);
                deletes++;
                log("delete " + key + " " + hex(handle));
            }
        }

        @Override
        public void deleteAllTextures() {
            hook("deleteAllTextures");
            synchronized (OracleBackend.this) {
                live("deleteAllTextures");
                for (int handle : textures.keySet()) {
                    oracle.freeTexture(handle);
                }
                deletes += textures.size();
                textures.clear();
                log("deleteAll " + key);
            }
        }

        @Override
        public void resetSurface(ByteBuffer pixels) {
            hook("resetSurface");
            synchronized (OracleBackend.this) {
                live("resetSurface");
                byte[] bytes = take(pixels, surface.byteSize(), "resetSurface");
                upload("reset", pixels);
                // 28 §5.7, 28:600: every texture deleted, then the surface replaced
                deletes += textures.size();
                textures.clear();
                oracle.reset(bytes);
                resets++;
                log("reset " + key);
            }
        }

        @Override
        public void runFrame(FrameRecord frame) {
            hook("runFrame");
            synchronized (OracleBackend.this) {
                live("runFrame");
                for (int i = 0; i < frame.segmentCount(); i++) {
                    oracle.runSegment(frame.segment(i));
                }
                frames++;
                lastFrameEpoch = frame.epoch;
                lastFrameId = frame.frameId;
                log("frame " + key + " e" + frame.epoch + " #" + frame.frameId);
            }
        }

        @Override
        public void expand() {
            hook("expand");
            synchronized (OracleBackend.this) {
                live("expand");
                expands++;
                log("expand " + key);
            }
        }

        @Override
        public boolean readbackSlotFree() {
            synchronized (OracleBackend.this) {
                live("readbackSlotFree");
                return inFlight() < 2;
            }
        }

        @Override
        public void issueReadback(long epoch, long frameId, long pass) {
            hook("issueReadback");
            synchronized (OracleBackend.this) {
                live("issueReadback");
                if (inFlight() >= 2) {
                    throw new IllegalStateException(key + ": a third readback issued while two are in flight (H36)");
                }
                int slot = 0;
                while (slotBusy[slot]) {
                    slot++;
                }
                slotBusy[slot] = true;
                slotEpoch[slot] = epoch;
                slotFrame[slot] = frameId;
                slotIssuedAt[slot] = pass;
                slotLatency[slot] = perIssue ? issueLatency(latencySeed, issueCount) : 0;
                slotOrder[slot] = issueCount++;
                slotPixels[slot] = oracle.pixels();
                issued++;
                log("issue " + key + " e" + epoch + " #" + frameId + " at " + pass);
            }
        }

        @Override
        public int fetchReadbacks(ReadbackOut out, long pass, int forceAfterPasses) {
            hook("fetchReadbacks");
            List<Integer> ready = new ArrayList<>();
            synchronized (OracleBackend.this) {
                live("fetchReadbacks");
                for (int slot : issueOrder()) {
                    // A fixed latency is read now (a test may lower it while readbacks are in flight)
                    int after = perIssue ? slotLatency[slot] : latency;
                    boolean done = !neverReady && pass - slotIssuedAt[slot] >= after
                        || pass - slotIssuedAt[slot] >= forceAfterPasses;
                    if (!done) {
                        // Oldest first: a newer one that is finished waits behind it
                        break;
                    }
                    ready.add(slot);
                }
                if (ready.size() >= 2) {
                    fetchedTogether++;
                }
            }
            // The callback runs outside this lock, on the reused direct buffer, which is scribbled after each call
            for (int slot : ready) {
                byte[] pixels;
                long epoch;
                long frameId;
                synchronized (OracleBackend.this) {
                    pixels = slotPixels[slot];
                    epoch = slotEpoch[slot];
                    frameId = slotFrame[slot];
                    slotBusy[slot] = false;
                    slotPixels[slot] = null;
                    fetched++;
                    log("fetch " + key + " e" + epoch + " #" + frameId + " at " + pass);
                }
                ByteBuffer b = readBuffer(pixels.length);
                b.put(pixels);
                b.flip();
                out.accept(epoch, frameId, b);
                b.clear();
                b.limit(pixels.length);
                fill(b, (byte) 0x5A);
            }
            return ready.size();
        }

        @Override
        public int readbacksInFlight() {
            synchronized (OracleBackend.this) {
                live("readbacksInFlight");
                return inFlight();
            }
        }

        @Override
        public void readNow(ByteBuffer out) {
            hook("readNow");
            synchronized (OracleBackend.this) {
                live("readNow");
                if (inFlight() != 0) {
                    throw new IllegalStateException(key + ": readNow with " + inFlight() + " readbacks in flight");
                }
                if (out.position() != 0 || out.remaining() != surface.byteSize()) {
                    throw new IllegalArgumentException(
                        "readNow into position " + out.position() + ", " + out.remaining() + " bytes remaining");
                }
                out.put(oracle.pixels());
                readNows++;
                log("readNow " + key);
            }
        }

        @Override
        public void destroy() {
            hook("destroy");
            synchronized (OracleBackend.this) {
                live("destroy");
                destroyed = true;
                Arrays.fill(slotBusy, false);
                Arrays.fill(slotPixels, null);
                textures.clear();
                log("destroy " + key);
            }
        }

        /** Under the lock: readbacks issued and not fetched. */
        int inFlight() {
            int n = 0;
            for (boolean busy : slotBusy) {
                n += busy ? 1 : 0;
            }
            return n;
        }

        /** The busy slots, oldest issue first. */
        private List<Integer> issueOrder() {
            List<Integer> slots = new ArrayList<>();
            for (int s = 0; s < 3; s++) {
                if (slotBusy[s]) {
                    slots.add(s);
                }
            }
            slots.sort((a, b) -> Long.compare(slotOrder[a], slotOrder[b]));
            return slots;
        }

        /** Under the lock: a call on this device, which must be live, inside a bracket, on the backend's thread. */
        private void live(String call) {
            inBracket(call);
            if (destroyed) {
                throw new IllegalStateException(call + " on " + key + "'s destroyed objects");
            }
        }

        /** The handles the "GPU" holds (tests). */
        TreeSet<Integer> handles() {
            synchronized (OracleBackend.this) {
                return new TreeSet<>(textures.keySet());
            }
        }

        /** The surface now (tests, between passes). */
        byte[] pixels() {
            synchronized (OracleBackend.this) {
                return oracle.pixels();
            }
        }

        @Override
        public String toString() {
            return "OracleBackend.Device[" + key + ", " + surface + "]";
        }
    }

    final GlyphTable glyphs;
    /** Passes after its issue at which a readback's query reports available: 1 = at the next pass's fetch. */
    volatile int latency = 1;
    /** The NEVER_READY query policy (amendment C): only the forced fetch completes. */
    volatile boolean neverReady;
    /**
     * Under {@link #latencyPerIssue}: each readback's latency comes from {@link #issueLatency}, not {@link #latency}.
     */
    private volatile boolean perIssue;
    private volatile long latencySeed;

    private int maxSide = 16384;
    private int failBeginPassAt;
    private int failEndPassAt;
    private Consumer<String> hook;
    private Consumer<Integer> stagingProbe;
    private final List<String> calls = new ArrayList<>();
    private final List<String> uploads = new ArrayList<>();
    private final Map<DeviceKey, List<Device>> devices = new HashMap<>();
    private Thread thread;
    private boolean inPass;
    private boolean begun;
    private boolean closed;
    private int beginPasses;
    private int endPasses;
    private int closes;
    private int fetchedTogether;
    private ByteBuffer staging;
    private ByteBuffer readBuffer;

    /** On the synthetic glyph table (28:633). */
    OracleBackend() {
        this(SyntheticGlyphs.TABLE);
    }

    OracleBackend(GlyphTable glyphs) {
        this.glyphs = glyphs;
    }

    @Override
    public synchronized void beginPass() {
        onThread("beginPass");
        if (closed) {
            throw new IllegalStateException("beginPass after close");
        }
        if (inPass) {
            throw new IllegalStateException("beginPass inside a bracket");
        }
        beginPasses++;
        log("beginPass");
        if (beginPasses == failBeginPassAt) {
            throw new IllegalStateException("a program failed to link (OracleBackend, beginPass " + beginPasses + ")");
        }
        inPass = true;
        begun = true;
    }

    @Override
    public synchronized void endPass() {
        onThread("endPass");
        if (!inPass) {
            throw new IllegalStateException("endPass outside a bracket");
        }
        inPass = false;
        endPasses++;
        log("endPass");
        if (endPasses == failEndPassAt) {
            throw new IllegalStateException(
                "glGetError: GL_INVALID_OPERATION (OracleBackend, endPass " + endPasses + ")");
        }
    }

    @Override
    public synchronized int maxSurfaceSide() {
        return begun ? maxSide : 0;
    }

    @Override
    public DeviceBackend open(DeviceKey key, SurfaceDesc surface) {
        hook("open");
        synchronized (this) {
            inBracket("open");
            if (surface.width > maxSide || surface.height > maxSide) {
                throw new IllegalStateException(surface + " exceeds the side limit " + maxSide + " (H64)");
            }
            Device d = new Device(key, surface);
            List<Device> of = devices.get(key);
            if (of == null) {
                of = new ArrayList<>();
                devices.put(key, of);
            }
            for (Device old : of) {
                if (!old.destroyed) {
                    throw new IllegalStateException(key + " opened while an older opening's objects live (H46)");
                }
            }
            of.add(d);
            log("open " + key);
            return d;
        }
    }

    @Override
    public ByteBuffer staging(int bytes) {
        Consumer<Integer> probe;
        synchronized (this) {
            probe = stagingProbe;
        }
        // On the caller's thread, with whatever locks it holds: the copy-on-write tests ask which
        if (probe != null) {
            probe.accept(bytes);
        }
        return stagingBuffer(bytes);
    }

    private synchronized ByteBuffer stagingBuffer(int bytes) {
        if (staging == null || staging.capacity() < bytes) {
            staging = ByteBuffer.allocateDirect(Math.max(bytes, 4096));
        }
        staging.clear();
        staging.limit(bytes);
        fill(staging, (byte) 0xA5);
        staging.clear();
        staging.limit(bytes);
        return staging;
    }

    @Override
    public synchronized void close() {
        onThread("close");
        if (inPass) {
            throw new IllegalStateException("close inside a bracket");
        }
        closes++;
        closed = true;
        log("close");
    }

    // ---- Settings and inspection

    /** The side limit maxSurfaceSide answers after the first beginPass (H64); 16 384 by default. */
    synchronized OracleBackend maxSurfaceSide(int side) {
        this.maxSide = side;
        return this;
    }

    /**
     * From now on each readback gets a latency of its own, 1-4 passes, {@link #issueLatency} of the seed and the
     * device's issue index: a newer readback may then finish before an older one and wait behind it, so one fetch can
     * take two (21:87). Two backends given one seed draw the same latencies for the same issues.
     */
    synchronized OracleBackend latencyPerIssue(long seed) {
        this.latencySeed = seed;
        this.perIssue = true;
        return this;
    }

    /** The latency, 1-4 passes, of a device's issue-th readback (0-based) under {@link #latencyPerIssue}. */
    static int issueLatency(long seed, long issue) {
        // SplitMix64's finaliser (as SplittableRandom's), so neighbouring issues get unrelated latencies
        long z = seed + (issue + 1) * 0x9E3779B97F4A7C15L;
        z = (z ^ z >>> 30) * 0xBF58476D1CE4E5B9L;
        z = (z ^ z >>> 27) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return 1 + (int) (z >>> 62);
    }

    /** The n-th beginPass (1-based) throws IllegalStateException, as a program that fails to link (H63); 0: never. */
    synchronized OracleBackend failBeginPassAt(int n) {
        this.failBeginPassAt = n;
        return this;
    }

    /** The n-th endPass throws, as PassState.exit's glGetError check (H63); 0: never. */
    synchronized OracleBackend failEndPassAt(int n) {
        this.failEndPassAt = n;
        return this;
    }

    /** Run with the call's name at the start of every device call and open, outside this backend's lock; null: none. */
    synchronized OracleBackend hook(Consumer<String> hook) {
        this.hook = hook;
        return this;
    }

    /** Called with the size at every staging call, before the buffer is handed out; null: none. */
    synchronized OracleBackend stagingProbe(Consumer<Integer> probe) {
        this.stagingProbe = probe;
        return this;
    }

    /** The newest opening of the key, or null. */
    synchronized Device device(DeviceKey key) {
        List<Device> of = devices.get(key);
        return of == null || of.isEmpty() ? null : of.get(of.size() - 1);
    }

    /** Every opening of the key, oldest first. */
    synchronized List<Device> devices(DeviceKey key) {
        List<Device> of = devices.get(key);
        return of == null ? new ArrayList<Device>() : new ArrayList<>(of);
    }

    /** A copy of the call log: "beginPass", "open DeviceKey[1:1]", "frame DeviceKey[1:1] e1 #2", ... */
    synchronized List<String> calls() {
        return new ArrayList<>(calls);
    }

    synchronized void clearCalls() {
        calls.clear();
    }

    /**
     * One entry per upload, of any device, in call order: "create", "update" or "reset", then "staged" when its
     * buffer was the instance {@code staging} last handed out, else "own buffer" (G13b's stageNanos).
     */
    synchronized List<String> uploads() {
        return new ArrayList<>(uploads);
    }

    synchronized int beginPasses() {
        return beginPasses;
    }

    synchronized int closes() {
        return closes;
    }

    /** Fetches, of any device, that returned two readbacks at once. */
    synchronized int fetchedTogether() {
        return fetchedTogether;
    }

    synchronized boolean inPass() {
        return inPass;
    }

    @Override
    public String toString() {
        return "OracleBackend[latency " + latency + (neverReady ? ", never ready" : "") + "]";
    }

    // ---- Internals

    private void hook(String call) {
        Consumer<String> h;
        synchronized (this) {
            h = hook;
        }
        if (h != null) {
            h.accept(call);
        }
    }

    private void log(String call) {
        calls.add(call);
    }

    /** Under the lock: whether an upload's buffer is the one staging last handed out (G13b, 28 §6.7). */
    private void upload(String call, ByteBuffer texels) {
        uploads.add(call + (texels == staging ? " staged" : " own buffer"));
    }

    private void onThread(String call) {
        Thread current = Thread.currentThread();
        if (thread == null) {
            thread = current;
        } else if (thread != current) {
            throw new IllegalStateException(call + " on " + current.getName() + ", not on " + thread.getName());
        }
    }

    private void inBracket(String call) {
        onThread(call);
        if (!inPass) {
            throw new IllegalStateException(call + " outside a beginPass/endPass bracket");
        }
    }

    private ByteBuffer readBuffer(int bytes) {
        synchronized (this) {
            if (readBuffer == null || readBuffer.capacity() < bytes) {
                readBuffer = ByteBuffer.allocateDirect(bytes);
            }
            readBuffer.clear();
            readBuffer.limit(bytes);
            return readBuffer;
        }
    }

    /** Fills the buffer from its position to its limit. */
    private static void fill(ByteBuffer b, byte value) {
        byte[] chunk = new byte[Math.min(8192, Math.max(1, b.remaining()))];
        Arrays.fill(chunk, value);
        while (b.hasRemaining()) {
            b.put(chunk, 0, Math.min(chunk.length, b.remaining()));
        }
    }

    /** B9: position 0 and exactly the bytes the call needs; the buffer is left as it was. */
    private static byte[] take(ByteBuffer b, int bytes, String call) {
        if (!b.isDirect()) {
            throw new IllegalArgumentException(call + ": LWJGL takes direct buffers only");
        }
        if (b.position() != 0 || b.remaining() != bytes) {
            throw new IllegalArgumentException(
                call + ": position "
                    + b.position()
                    + ", "
                    + b.remaining()
                    + " bytes remaining, expected 0 and "
                    + bytes);
        }
        byte[] out = new byte[bytes];
        b.duplicate()
            .get(out);
        return out;
    }

    private static String hex(int handle) {
        return String.format(Locale.ROOT, "0x%08X", handle);
    }
}
