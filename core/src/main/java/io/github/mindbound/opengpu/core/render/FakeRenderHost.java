package io.github.mindbound.opengpu.core.render;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.mindbound.opengpu.core.SurfaceDesc;

/**
 * The test host (28:205; 23:165, :182). It queues offers per device and runs them only when a test asks, through
 * {@link #step()} or the finer {@link #run} and {@link #readback}, like {@code GlTestHost}'s {@code pumpFrame()}. A
 * pluggable {@link RecordRenderer} runs the records: zeros by default, the tier-E oracle in tests. Every method takes
 * one private lock; {@link #run} calls the renderer under it, and {@link #readback} and {@link #step} call
 * {@link ReadbackSink#onReadback} after releasing it, so a sink may call back into this host from any thread.
 */
public final class FakeRenderHost implements RenderHost {

    /** One delivered readback. Immutable; {@link #pixels} is the delivered array itself. */
    public static final class Readback {

        public final long epoch;
        public final long frameId;
        public final byte[] pixels;

        Readback(long epoch, long frameId, byte[] pixels) {
            this.epoch = epoch;
            this.frameId = frameId;
            this.pixels = pixels;
        }

        @Override
        public String toString() {
            return "Readback[e" + epoch + " #" + frameId + ", " + pixels.length + " B]";
        }
    }

    /** A key's current or last opening. */
    private static final class Opening {

        final SurfaceDesc surface;
        final ReadbackSink sink;
        final ArrayDeque<Record> queue = new ArrayDeque<>();
        final List<Record> offered = new ArrayList<>();
        final List<Record> ran = new ArrayList<>();
        final List<Readback> readbacks = new ArrayList<>();
        boolean open = true;
        /** The newest epoch offered, for {@link #setSkipStaleEpochs} (28:155). */
        long newestEpoch = Long.MIN_VALUE;
        /** The newest FRAME run since the last readback, if any. */
        boolean frameRan;
        long frameEpoch;
        long frameId;

        Opening(SurfaceDesc surface, ReadbackSink sink) {
            this.surface = surface;
            this.sink = sink;
        }
    }

    private final Object lock = new Object();
    private final RecordRenderer renderer;
    /** In open order; a key opened again moves to the end. */
    private final Map<DeviceKey, Opening> openings = new LinkedHashMap<>();
    private final Map<String, DeviceKey> displays = new HashMap<>();
    private final List<String> log = new ArrayList<>();
    private volatile boolean available = true;
    private volatile boolean stalled;
    private boolean skipStaleEpochs;
    private boolean keepHistory = true;

    /** A host on {@link RecordRenderer#zeros()}. */
    public FakeRenderHost() {
        this(RecordRenderer.zeros());
    }

    /** @throws NullPointerException for a null renderer */
    public FakeRenderHost(RecordRenderer renderer) {
        if (renderer == null) {
            throw new NullPointerException("renderer");
        }
        this.renderer = renderer;
    }

    /** The renderer this host runs records on. */
    public RecordRenderer renderer() {
        return renderer;
    }

    /** What {@link #available()} answers; default true, false models M1's disabled host (H22). */
    public void setAvailable(boolean available) {
        this.available = available;
    }

    /** What {@link #stalled()} answers; default false. */
    public void setStalled(boolean stalled) {
        this.stalled = stalled;
    }

    /**
     * Default false. True: {@link #run} takes, but does not hand to the renderer, a record whose epoch is below the
     * newest epoch offered for its key (28:155's optional skip).
     */
    public void setSkipStaleEpochs(boolean skip) {
        synchronized (lock) {
            this.skipStaleEpochs = skip;
        }
    }

    /**
     * Default true. False: from now on {@link #offered}, {@link #ran} and {@link #readbacks} record nothing, so a long
     * run retains no payloads (the concurrency test).
     */
    public void setKeepHistory(boolean keep) {
        synchronized (lock) {
            this.keepHistory = keep;
        }
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public boolean stalled() {
        return stalled;
    }

    /**
     * Opens the key and calls {@code renderer.open}; a released key may be opened again, with a fresh queue and fresh
     * logs. Logs "open DeviceKey[s:d]".
     *
     * @throws NullPointerException  for a null argument
     * @throws IllegalStateException if the key is open already
     */
    @Override
    public void open(DeviceKey key, SurfaceDesc surface, ReadbackSink sink) {
        if (key == null || surface == null || sink == null) {
            throw new NullPointerException("key, surface and sink");
        }
        synchronized (lock) {
            Opening old = openings.get(key);
            if (old != null && old.open) {
                throw new IllegalStateException(key + " is open already");
            }
            openings.remove(key);
            openings.put(key, new Opening(surface, sink));
            log.add("open " + key);
            renderer.open(key, surface);
        }
    }

    /**
     * Queues the record.
     *
     * @throws NullPointerException  for a null argument
     * @throws IllegalStateException unless the key is open: a test aid, stricter than {@link RenderHost#offer}'s
     *                               contract, that catches a device offering before {@code open} or after
     *                               {@code release}
     */
    @Override
    public void offer(DeviceKey key, Record record) {
        if (key == null || record == null) {
            throw new NullPointerException("key and record");
        }
        synchronized (lock) {
            Opening o = openings.get(key);
            if (o == null || !o.open) {
                throw new IllegalStateException(key + " is not open");
            }
            o.queue.addLast(record);
            if (keepHistory) {
                o.offered.add(record);
            }
            o.newestEpoch = Math.max(o.newestEpoch, record.epoch);
        }
    }

    /**
     * Drops the key's queue (21:106) and calls {@code renderer.release}; logs "release DeviceKey[s:d]". Nothing for a
     * key that is not open.
     */
    @Override
    public void release(DeviceKey key) {
        synchronized (lock) {
            releaseLocked(key);
        }
    }

    /**
     * Binds the display to the key, or unbinds it for a null key; logs "bindDisplay &lt;id&gt; &lt;key or null&gt;".
     */
    @Override
    public void bindDisplay(String displayId, DeviceKey key) {
        if (displayId == null) {
            throw new NullPointerException("displayId");
        }
        synchronized (lock) {
            if (key == null) {
                displays.remove(displayId);
            } else {
                displays.put(displayId, key);
            }
            log.add("bindDisplay " + displayId + " " + key);
        }
    }

    /** Logs "releaseSession &lt;session&gt;", then releases every open key of the session in open order. */
    @Override
    public void releaseSession(long session) {
        synchronized (lock) {
            log.add("releaseSession " + session);
            for (DeviceKey key : new ArrayList<>(openings.keySet())) {
                if (key.session == session) {
                    releaseLocked(key);
                }
            }
        }
    }

    /**
     * Every device open now, in open order: {@code run(key, Integer.MAX_VALUE)}, then {@code readback(key)}.
     *
     * @return the records taken
     */
    public int step() {
        List<DeviceKey> keys;
        synchronized (lock) {
            keys = new ArrayList<>();
            for (Map.Entry<DeviceKey, Opening> e : openings.entrySet()) {
                if (e.getValue().open) {
                    keys.add(e.getKey());
                }
            }
        }
        int taken = 0;
        for (DeviceKey key : keys) {
            taken += step(key);
        }
        return taken;
    }

    /**
     * {@code run(key, Integer.MAX_VALUE)}, then {@code readback(key)}.
     *
     * @return the records taken
     * @throws IllegalStateException for a key never opened
     */
    public int step(DeviceKey key) {
        int taken = run(key, Integer.MAX_VALUE);
        readback(key);
        return taken;
    }

    /**
     * Takes up to max queued records in offered order and hands each to the renderer after
     * {@code assert record.intact()}, or skips it under {@link #setSkipStaleEpochs}. No readback.
     *
     * @return the records taken, skipped ones included; 0 for a released key
     * @throws IllegalArgumentException for a negative max
     * @throws IllegalStateException    for a key never opened
     */
    public int run(DeviceKey key, int max) {
        if (max < 0) {
            throw new IllegalArgumentException("max " + max + " is negative");
        }
        synchronized (lock) {
            Opening o = opening(key);
            if (!o.open) {
                return 0;
            }
            int taken = 0;
            while (taken < max && !o.queue.isEmpty()) {
                Record r = o.queue.removeFirst();
                taken++;
                if (skipStaleEpochs && r.epoch < o.newestEpoch) {
                    continue;
                }
                assert r.intact() : r + " changed after it was offered";
                renderer.accept(key, r);
                if (keepHistory) {
                    o.ran.add(r);
                }
                if (r.kind() == Record.FRAME) {
                    o.frameRan = true;
                    o.frameEpoch = r.epoch;
                    o.frameId = ((FrameRecord) r).frameId;
                }
            }
            return taken;
        }
    }

    /**
     * If a {@code FRAME} ran since the last readback: takes {@code renderer.pixels(key)} and the newest such frame's
     * epoch and id under the lock, then calls {@code sink.onReadback(session, deviceId, epoch, frameId, pixels)} after
     * releasing it.
     *
     * @return whether it delivered a readback; false for a released key
     * @throws IllegalStateException for a key never opened
     */
    public boolean readback(DeviceKey key) {
        ReadbackSink sink;
        Readback readback;
        synchronized (lock) {
            Opening o = opening(key);
            if (!o.open || !o.frameRan) {
                return false;
            }
            o.frameRan = false;
            readback = new Readback(o.frameEpoch, o.frameId, renderer.pixels(key));
            if (keepHistory) {
                o.readbacks.add(readback);
            }
            sink = o.sink;
        }
        sink.onReadback(key.session, key.deviceId, readback.epoch, readback.frameId, readback.pixels);
        return true;
    }

    /** Whether the key is open now. */
    public boolean isOpen(DeviceKey key) {
        synchronized (lock) {
            Opening o = openings.get(key);
            return o != null && o.open;
        }
    }

    /** The surface of the key's current or last opening, or null for a key never opened. */
    public SurfaceDesc surface(DeviceKey key) {
        synchronized (lock) {
            Opening o = openings.get(key);
            return o == null ? null : o.surface;
        }
    }

    /** A copy of the records offered in the key's current or last opening, in order (28:126); empty if never opened. */
    public List<Record> offered(DeviceKey key) {
        synchronized (lock) {
            Opening o = openings.get(key);
            return o == null ? new ArrayList<Record>() : new ArrayList<>(o.offered);
        }
    }

    /** A copy of the records handed to the renderer in that opening, in order; empty if never opened. */
    public List<Record> ran(DeviceKey key) {
        synchronized (lock) {
            Opening o = openings.get(key);
            return o == null ? new ArrayList<Record>() : new ArrayList<>(o.ran);
        }
    }

    /** The records queued for the key and not yet taken; 0 if never opened or released. */
    public int queued(DeviceKey key) {
        synchronized (lock) {
            Opening o = openings.get(key);
            return o == null ? 0 : o.queue.size();
        }
    }

    /** A copy of the readbacks delivered in that opening, in order; empty if never opened. */
    public List<Readback> readbacks(DeviceKey key) {
        synchronized (lock) {
            Opening o = openings.get(key);
            return o == null ? new ArrayList<Readback>() : new ArrayList<>(o.readbacks);
        }
    }

    /** The key the display is bound to, or null. */
    public DeviceKey boundDevice(String displayId) {
        synchronized (lock) {
            return displays.get(displayId);
        }
    }

    /** A copy of the lifecycle log, in call order: "open …", "release …", "releaseSession …", "bindDisplay …". */
    public List<String> log() {
        synchronized (lock) {
            return new ArrayList<>(log);
        }
    }

    @Override
    public String toString() {
        return "FakeRenderHost[" + renderer + "]";
    }

    private Opening opening(DeviceKey key) {
        Opening o = openings.get(key);
        if (o == null) {
            throw new IllegalStateException(key + " was never opened");
        }
        return o;
    }

    private void releaseLocked(DeviceKey key) {
        Opening o = openings.get(key);
        if (o == null || !o.open) {
            return;
        }
        o.open = false;
        o.queue.clear();
        o.frameRan = false;
        log.add("release " + key);
        renderer.release(key);
    }
}
