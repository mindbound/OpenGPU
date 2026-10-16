package io.github.mindbound.opengpu.gl.host;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.ReadbackSink;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.ResetRecord;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexFreeRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;

/**
 * One opening of a key in {@link GlHostCore} (S8 plan §3.10 with amendments A and D): its queue, its texture mirrors,
 * what the GPU lacks against them, its readback state and its counters. Its lock is the object itself, a leaf: no other
 * lock is taken under it, and it is never held across a GL call or a sink call (H56).
 * <ul>
 * <li><b>I-H1</b> (amendment D): the queue's records ahead of its first {@code FRAME} are resource records that arrived
 * behind a {@code FRAME} the render thread has already taken. A resource record offered while the queue is empty
 * applies at once; behind anything queued it waits, and the render thread applies it, one record per lock hold, before
 * the pass ends ({@link #applyHead}). With mirrors on no {@code RESET} is ever queued.</li>
 * <li><b>I-H2</b> (mirrors on): the mirrors are every texture as it stands just before the queue's head in the offered
 * sequence (28:126).</li>
 * <li><b>I-H3</b>: {@code pending} and {@code pendingReset} are what the GPU lacks against the mirrors.</li>
 * </ul>
 * A {@code FRAME} runs only once nothing ahead of it is queued or pending, so the textures it reads equal the mirrors,
 * which equal the textures at its place in the offered sequence (O5): results are unchanged by the mirrors. With
 * mirrors off (option B) the render thread runs every record from the queue in offered order instead.
 */
final class HostDevice {

    /** One unit of work that {@link #nextUnit} hands the render thread, which runs it outside every lock. */
    static final class Unit {

        /** No work; a queued resource record applied (CPU only, not a budget unit; amendment D). */
        static final int NONE = 0, APPLY = 1;
        /** A {@code RESET}, one pending texture, one {@code FRAME}, one record with mirrors off (S8 plan §3.2). */
        static final int RESET = 2, SYNC = 3, FRAME = 4, RAW = 5;

        int kind;
        /** FRAME's, RAW's or RESET's record. */
        Record record;
        /** SYNC: the handle, what it owes, and the box: the whole texture for a create, else the dirty box. */
        int handle;
        boolean deleteGpu;
        boolean create;
        boolean dirty;
        int x, y, w, h;
        int format;
        /** SYNC: the mirror's width, the source's row stride in texels. */
        int textureWidth;
        /** RESET's pixels, or a null or shared mirror's array (null: zeros): immutable, copied outside the lock. */
        byte[] source;
        /** An owned mirror's box, copied into the backend's staging buffer under the lock (amendment D). */
        ByteBuffer staged;

        void clear() {
            kind = NONE;
            record = null;
            handle = 0;
            deleteGpu = create = dirty = false;
            x = y = w = h = format = textureWidth = 0;
            source = null;
            staged = null;
        }
    }

    /** One delivery that {@link #takeDelivery} hands the GL thread, which calls the sink outside every lock. */
    static final class Delivery {

        /**
         * The sink as it stood under the lock: a concurrent {@link HostDevice#release()} cannot null it under the call.
         */
        ReadbackSink sink;
        long epoch;
        long frameId;
        byte[] pixels;

        void clear() {
            sink = null;
            epoch = frameId = 0;
            pixels = null;
        }
    }

    final DeviceKey key;
    final SurfaceDesc surface;
    final boolean mirrorsOn;
    final int bpp;
    private final AtomicLong violations;

    /** Set under the host's lock when the opening leaves it (S8 plan §3.10), so the render thread sees it at once. */
    volatile boolean released;

    // Guarded by this
    /**
     * The root's device (28:194), null once the opening is released, dropped or destroyed: {@link GlHostCore} keeps a
     * released opening for its stats until {@code releaseSession}, and that shell must not keep the device with its
     * surface arrays (28:151; 21:106).
     */
    private ReadbackSink sink;
    /**
     * Replaced, not cleared, when the opening leaves (as {@code mirrors} and {@code pending}): no grown array stays.
     */
    ArrayDeque<Record> queue = new ArrayDeque<>();
    int queuedFrames;
    long queuedBytes;
    Map<Integer, Mirror> mirrors = new HashMap<>();
    long mirrorBytes;
    LinkedHashMap<Integer, Pending> pending = new LinkedHashMap<>();
    /** The pending {@code RESET}, its record kept for the dev-build check before its upload (28:202). */
    ResetRecord pendingReset;
    /** I-H1: the resource records at the queue's head came behind a FRAME the render thread took. */
    boolean trailing;
    /** A side over the backend's maxSurfaceSide at the first turn (H64). */
    boolean refused;
    /** The host failed (H63): everything dropped, nothing runs. */
    boolean dropped;
    /**
     * Set at the first turn by the render thread and nulled when its objects are deleted ({@link #destroyed()}); the GL
     * thread reads it without the lock (it alone writes it).
     */
    DeviceBackend backend;
    boolean destroyed;

    // The GPU side, written by the render thread under this lock so that stats see a consistent copy
    boolean readbackOwed;
    long owedEpoch;
    long owedFrameId;
    boolean expandOwed;
    int inFlight;
    private ArrayDeque<Long> issuePasses = new ArrayDeque<>();
    Map<Integer, Integer> gpu = new HashMap<>();
    long gpuBytes;
    boolean deliver;
    long deliverEpoch;
    long deliverFrameId;

    // Counters (DeviceStats)
    long mirrorAllocations, mirrorClones, mirrorAdoptions, mirrorCopyBytes;
    long appliedAtOffer, appliedQueued;
    long uploads, uploadBytes, resetsRun, expands;
    long framesExecuted, readbacksIssued, readbacksFetched, readbacksDelivered, recordsDropped;
    long lastReadbackEpoch = -1, lastReadbackFrameId = -1;
    long latencyMax, latencyTotal;

    /** The render thread's: the newest fetched readback of the pass, delivered after it (S8 plan §3.9). */
    private byte[] readbackArray;
    private final DeviceBackend.ReadbackOut out = new DeviceBackend.ReadbackOut() {

        @Override
        public void accept(long epoch, long frameId, ByteBuffer pixels) {
            fetchedOne(epoch, frameId, pixels);
        }
    };

    HostDevice(DeviceKey key, SurfaceDesc surface, ReadbackSink sink, boolean mirrorsOn, AtomicLong violations) {
        this.key = key;
        this.surface = surface;
        this.sink = sink;
        this.mirrorsOn = mirrorsOn;
        this.bpp = surface.bytesPerPixel();
        this.violations = violations;
    }

    // ---- The offering side: any thread, usually under the Device's monitor (S8 plan §3.12)

    /**
     * 28:126's offer, under this lock for one queue add or one apply (amendment D): {@code RESET} drops what is queued,
     * the mirrors and the pending state and becomes the pending reset (H38; 28:155); a {@code FRAME} queues; a resource
     * record applies at once on an empty queue with mirrors on, else queues. Never throws: what breaks the device
     * contract is counted and dropped.
     */
    synchronized void offer(Record r) {
        if (released || refused || dropped) {
            // released: raced with the root's release; refused and dropped: their records are dropped (H63, H64)
            recordsDropped++;
            return;
        }
        switch (r.kind()) {
            case Record.RESET: {
                ResetRecord reset = (ResetRecord) r;
                if (reset.pixels().length != surface.byteSize()) {
                    violations.incrementAndGet();
                    return;
                }
                recordsDropped += queue.size();
                clearQueue();
                clearMirrors();
                pendingReset = reset;
                return;
            }
            case Record.FRAME:
                enqueue(r);
                return;
            case Record.TEX_CREATE:
            case Record.TEX_UPDATE:
            case Record.TEX_FREE:
                if (mirrorsOn && queue.isEmpty()) {
                    if (apply(r)) {
                        appliedAtOffer++;
                    }
                } else {
                    enqueue(r);
                }
                return;
            default:
                violations.incrementAndGet();
        }
    }

    /**
     * The opening leaves its host: queue, mirrors and pending state dropped, and the sink let go (28:151; 21:106). A
     * readback the GL thread took before this delivers to the sink it took, which counts it stale.
     */
    synchronized void release() {
        released = true;
        sink = null;
        dropState();
    }

    /** The host failed (H63): everything dropped, the sink let go, and nothing runs from now on. */
    synchronized void drop() {
        dropped = true;
        sink = null;
        dropState();
    }

    /** A side over the backend's limit at the first turn (H64): its records are dropped from now on. */
    synchronized void refuse() {
        refused = true;
        dropState();
    }

    // ---- The render thread

    /**
     * Fills the unit with the opening's next work, in this order: the pending {@code RESET}; a queued resource record
     * at the head, applied here (APPLY: CPU only, one record per lock hold, amendment D); one pending texture (SYNC,
     * with an owned mirror's box copied into {@code host.staging} under the lock and any other source left to copy
     * outside it); the {@code FRAME} at the head, once nothing is pending; with mirrors off, the record at the head.
     * Applying queued records before syncing lets a texture's updates coalesce into one upload.
     *
     * @return the unit's kind
     */
    synchronized int nextUnit(Unit u, HostBackend host) {
        u.clear();
        if (released || refused || dropped || backend == null) {
            return Unit.NONE;
        }
        if (pendingReset != null) {
            u.kind = Unit.RESET;
            u.record = pendingReset;
            u.source = pendingReset.pixels();
            pendingReset = null;
            return u.kind;
        }
        if (applyHeadLocked()) {
            u.kind = Unit.APPLY;
            return u.kind;
        }
        if (!pending.isEmpty()) {
            Iterator<Pending> it = pending.values()
                .iterator();
            Pending p = it.next();
            it.remove();
            syncUnit(p, u, host);
            return u.kind;
        }
        Record head = queue.peekFirst();
        if (head == null) {
            return Unit.NONE;
        }
        poll();
        u.record = head;
        if (head.kind() == Record.FRAME) {
            trailing = mirrorsOn && headIsResource();
            u.kind = Unit.FRAME;
        } else {
            // Mirrors off (option B): the record itself, in offered order
            u.kind = Unit.RAW;
        }
        return u.kind;
    }

    /**
     * Applies the resource record at the queue's head, if there is one (mirrors on): the drain that leaves no resource
     * record ahead of a FRAME when the pass ends, so offers made while the server tail is empty never queue behind
     * one and the queue stays within the tail's bound (S8 plan §3.10).
     *
     * @return whether it applied one
     */
    synchronized boolean applyHead() {
        if (released || refused || dropped) {
            return false;
        }
        return applyHeadLocked();
    }

    /** Whether it has work: the first turn, a unit, a readback owed, in flight or to deliver, or an allowed expand. */
    synchronized boolean hasWork(boolean expandAllowed) {
        if (released || refused || dropped) {
            return false;
        }
        return backend == null || pendingReset != null
            || !queue.isEmpty()
            || !pending.isEmpty()
            || readbackOwed
            || inFlight > 0
            || deliver
            || expandOwed && expandAllowed;
    }

    /** {@link GlHostCore#idle}'s answer for this opening: nothing owed, or nothing that will ever run. */
    synchronized boolean idle(boolean expandAllowed) {
        if (refused || dropped || released) {
            return true;
        }
        return !hasWork(expandAllowed);
    }

    synchronized void opened(DeviceBackend db) {
        backend = db;
    }

    /**
     * Step (d) or {@code close} deleted its objects: from now on it is the small shell {@link GlHostCore#stats} keeps
     * until {@code releaseSession}, with no backend, sink, readback array or grown collection.
     */
    synchronized void destroyed() {
        destroyed = true;
        backend = null;
        sink = null;
        inFlight = 0;
        issuePasses = new ArrayDeque<>();
        deliver = false;
        readbackOwed = false;
        gpu = new HashMap<>();
        gpuBytes = 0;
        readbackArray = null;
    }

    synchronized void resetRan() {
        gpu.clear();
        gpuBytes = 0;
        // H37: a RESET after a FRAME in one turn cancels the frame's readback; the device would count it stale
        readbackOwed = false;
        expandOwed = true;
        resetsRun++;
    }

    synchronized void gpuDeleted(int handle) {
        Integer bytes = gpu.remove(handle);
        if (bytes != null) {
            gpuBytes -= bytes;
        }
    }

    synchronized void gpuCreated(int handle, int bytes) {
        Integer old = gpu.put(handle, bytes);
        gpuBytes += bytes - (old == null ? 0 : old);
        uploads++;
        uploadBytes += bytes;
    }

    synchronized void uploaded(int bytes) {
        uploads++;
        uploadBytes += bytes;
    }

    synchronized void frameRan(FrameRecord f) {
        readbackOwed = true;
        owedEpoch = f.epoch;
        owedFrameId = f.frameId;
        expandOwed = true;
        framesExecuted++;
    }

    synchronized void expanded() {
        expandOwed = false;
        expands++;
    }

    synchronized boolean readbackOwed() {
        return readbackOwed;
    }

    synchronized long owedEpoch() {
        return owedEpoch;
    }

    synchronized long owedFrameId() {
        return owedFrameId;
    }

    synchronized boolean expandOwed() {
        return expandOwed;
    }

    synchronized int inFlight() {
        return inFlight;
    }

    synchronized void issued(long pass) {
        readbackOwed = false;
        inFlight++;
        issuePasses.addLast(pass);
        readbacksIssued++;
    }

    /** {@code fetchReadbacks} returned n: the oldest n in flight are done; their latency in passes is kept (RM:43). */
    synchronized void fetched(int n, long pass) {
        for (int i = 0; i < n && !issuePasses.isEmpty(); i++) {
            long latency = pass - issuePasses.pollFirst();
            latencyMax = Math.max(latencyMax, latency);
            latencyTotal += latency;
        }
        inFlight = Math.max(0, inFlight - n);
    }

    DeviceBackend.ReadbackOut out() {
        return out;
    }

    /**
     * After the pass, outside the bracket and every lock (28:1072; H67): the newest readback fetched in the pass, if
     * any and the opening is still live, with its array, which the sink copies before it returns (28:1073), and the
     * sink, all taken under the lock.
     *
     * @return whether {@code out} holds a delivery
     */
    synchronized boolean takeDelivery(Delivery out) {
        if (!deliver) {
            return false;
        }
        deliver = false;
        if (released || dropped || sink == null || readbackArray == null) {
            return false;
        }
        out.sink = sink;
        out.epoch = deliverEpoch;
        out.frameId = deliverFrameId;
        out.pixels = readbackArray;
        return true;
    }

    synchronized void delivered(long epoch, long frameId) {
        readbacksDelivered++;
        lastReadbackEpoch = epoch;
        lastReadbackFrameId = frameId;
    }

    // ---- Inspection (tests, stats): under the lock

    synchronized DeviceStats stats(String hostFailure) {
        return new DeviceStats(this, hostFailure);
    }

    /** Resource records ahead of the queue's first FRAME (I-H1). */
    synchronized int headResources() {
        int n = 0;
        for (Record r : queue) {
            if (!r.isResource()) {
                break;
            }
            n++;
        }
        return n;
    }

    synchronized boolean trailing() {
        return trailing;
    }

    synchronized List<Record> queued() {
        return new ArrayList<>(queue);
    }

    /** Copies of the mirrors' texels by handle, ascending (tests: I-H2). */
    synchronized TreeMap<Integer, byte[]> mirrorTexels() {
        TreeMap<Integer, byte[]> copy = new TreeMap<>();
        for (Map.Entry<Integer, Mirror> e : mirrors.entrySet()) {
            copy.put(
                e.getKey(),
                e.getValue()
                    .texels());
        }
        return copy;
    }

    /** The mirror itself, or null (tests). */
    synchronized Mirror mirror(int handle) {
        return mirrors.get(handle);
    }

    /** Copies of the pending entries, in order (tests: I-H3). */
    synchronized List<Pending> pendingCopy() {
        List<Pending> copy = new ArrayList<>();
        for (Pending p : pending.values()) {
            Pending c = new Pending(p.handle);
            c.deleteGpu = p.deleteGpu;
            c.create = p.create;
            c.x0 = p.x0;
            c.y0 = p.y0;
            c.x1 = p.x1;
            c.y1 = p.y1;
            copy.add(c);
        }
        return copy;
    }

    @Override
    public String toString() {
        return "HostDevice[" + key + ", " + surface + (released ? ", released" : "") + "]";
    }

    // ---- Internals, under the lock

    private void enqueue(Record r) {
        queue.addLast(r);
        if (r.kind() == Record.FRAME) {
            queuedFrames++;
            queuedBytes += ((FrameRecord) r).byteCount();
        } else {
            queuedBytes += r.queuedBytes();
        }
    }

    private Record poll() {
        Record r = queue.pollFirst();
        if (r.kind() == Record.FRAME) {
            queuedFrames--;
            queuedBytes -= ((FrameRecord) r).byteCount();
        } else {
            queuedBytes -= r.queuedBytes();
        }
        return r;
    }

    private void clearQueue() {
        queue.clear();
        queuedFrames = 0;
        queuedBytes = 0;
        trailing = false;
    }

    private void clearMirrors() {
        mirrors.clear();
        mirrorBytes = 0;
        pending.clear();
    }

    /** For good (28:151): fresh collections, so the arrays that a long queue or many textures grew go too. */
    private void dropState() {
        recordsDropped += queue.size();
        clearQueue();
        clearMirrors();
        queue = new ArrayDeque<>();
        mirrors = new HashMap<>();
        pending = new LinkedHashMap<>();
        pendingReset = null;
        readbackOwed = false;
        expandOwed = false;
    }

    private boolean headIsResource() {
        Record head = queue.peekFirst();
        return head != null && head.isResource();
    }

    private boolean applyHeadLocked() {
        if (!mirrorsOn || !headIsResource()) {
            return false;
        }
        Record r = poll();
        // 28:202, A11: a queued record is checked again when it is taken
        assert r.intact() : r + " changed after it was offered";
        if (apply(r)) {
            appliedQueued++;
        }
        if (!headIsResource()) {
            trailing = false;
        }
        return true;
    }

    /**
     * Folds a resource record into the mirrors and the pending state (S8 plan §3.10): create, a zero mirror and a
     * pending create; update, the mirror's rows and the dirty box unless a create is pending; free, the mirror gone and
     * a GPU delete pending unless the texture never reached the GPU. A record that breaks the device contract (a
     * create of a mirror that exists or in another format, an update or free of none, a region outside) is counted
     * and dropped.
     *
     * @return whether it applied
     */
    private boolean apply(Record r) {
        switch (r.kind()) {
            case Record.TEX_CREATE: {
                TexCreateRecord c = (TexCreateRecord) r;
                if (c.format != surface.format || mirrors.containsKey(c.handle)) {
                    violations.incrementAndGet();
                    return false;
                }
                Mirror m = new Mirror(c.handle, c.width, c.height, c.format);
                mirrors.put(c.handle, m);
                mirrorBytes += m.byteSize();
                // After a generation wrap the old texture may still await its delete: delete, then create (O7). A
                // pending delete has no dirty box (TEX_FREE cleaned it), and a create uploads the whole mirror
                pending(c.handle).create = true;
                return true;
            }
            case Record.TEX_UPDATE: {
                TexUpdateRecord u = (TexUpdateRecord) r;
                Mirror m = mirrors.get(u.handle);
                if (m == null || !m.fits(u)) {
                    violations.incrementAndGet();
                    return false;
                }
                switch (m.update(u)) {
                    case Mirror.ADOPTED:
                        mirrorAdoptions++;
                        break;
                    case Mirror.ALLOCATED:
                        mirrorAllocations++;
                        mirrorCopyBytes += u.payload().length;
                        break;
                    case Mirror.CLONED:
                        mirrorClones++;
                        mirrorCopyBytes += m.byteSize() + u.payload().length;
                        break;
                    default:
                        mirrorCopyBytes += u.payload().length;
                }
                Pending p = pending(u.handle);
                if (!p.create) {
                    p.addDirty(u.x, u.y, u.width, u.height);
                }
                return true;
            }
            case Record.TEX_FREE: {
                int handle = ((TexFreeRecord) r).handle;
                Mirror m = mirrors.remove(handle);
                if (m == null) {
                    violations.incrementAndGet();
                    return false;
                }
                mirrorBytes -= m.byteSize();
                Pending p = pending(handle);
                if (p.create && !p.deleteGpu) {
                    // It never reached the GPU: nothing to delete
                    pending.remove(handle);
                } else {
                    p.create = false;
                    p.clean();
                    p.deleteGpu = true;
                }
                return true;
            }
            default:
                violations.incrementAndGet();
                return false;
        }
    }

    private Pending pending(int handle) {
        Pending p = pending.get(handle);
        if (p == null) {
            p = new Pending(handle);
            pending.put(handle, p);
        }
        return p;
    }

    private void syncUnit(Pending p, Unit u, HostBackend host) {
        u.kind = Unit.SYNC;
        u.handle = p.handle;
        u.deleteGpu = p.deleteGpu;
        Mirror m = p.create || p.dirty() ? mirrors.get(p.handle) : null;
        assert m != null || !p.create && !p.dirty() : p + " has no mirror";
        if (m == null) {
            // A delete only (I-H3: a create or a dirty box always has its mirror)
            return;
        }
        u.create = p.create;
        u.dirty = !p.create;
        u.format = m.format;
        u.textureWidth = m.width;
        if (p.create) {
            u.x = 0;
            u.y = 0;
            u.w = m.width;
            u.h = m.height;
        } else {
            u.x = p.x0;
            u.y = p.y0;
            u.w = p.x1 - p.x0;
            u.h = p.y1 - p.y0;
        }
        if (m.owned()) {
            // The owned array may change once the lock is released: copy the box now (amendment D: ≤ 2 MiB)
            ByteBuffer b = host.staging(u.w * u.h * m.bpp);
            Mirror.copyBox(m.array(), m.width, m.bpp, u.x, u.y, u.w, u.h, b);
            b.flip();
            u.staged = b;
        } else {
            // 28:202, A11: an adopted payload is checked again before it leaves the lock for its upload
            assert m.intact() : m + "'s adopted payload changed after it was offered";
            u.source = m.array();
        }
    }

    /** The render thread's copy of one fetched readback; the newest of a pass wins (21:87). */
    private void fetchedOne(long epoch, long frameId, ByteBuffer pixels) {
        int n = surface.byteSize();
        if (pixels == null || pixels.remaining() != n) {
            violations.incrementAndGet();
            return;
        }
        if (readbackArray == null) {
            readbackArray = new byte[n];
        }
        pixels.duplicate()
            .get(readbackArray, 0, n);
        synchronized (this) {
            readbacksFetched++;
            deliver = true;
            deliverEpoch = epoch;
            deliverFrameId = frameId;
        }
    }
}
