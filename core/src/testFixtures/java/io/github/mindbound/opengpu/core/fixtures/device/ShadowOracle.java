package io.github.mindbound.opengpu.core.fixtures.device;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
import io.github.mindbound.opengpu.core.font.GlyphTable;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.ReadbackSink;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.RecordVisitor;
import io.github.mindbound.opengpu.core.render.RenderHost;
import io.github.mindbound.opengpu.core.render.ResetRecord;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexFreeRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;

/**
 * The tier-E oracle per opening as a {@link RenderHost} (S8 plan §2.4 with amendment E, B5): what a test compares a
 * real host's readbacks with. Each opening has one {@link TierEOracle}, on the given glyph table, which runs every
 * record when it is offered, in offered order (28:126; O5); after each {@code FRAME} the surface is kept, keyed by the
 * frame's epoch and id. A sink from {@link #checking} compares each readback with the kept surface of its frame, then
 * drops every kept frame at or below it (the newest confirms the older, 21:87), so a long run keeps at most the frames
 * not yet read back. The checking sink only looks a frame up and compares, so it does nothing heavy on the GL thread.
 * <p>
 * It never produces readbacks and never calls a sink. {@link #available()} is true and {@link #stalled()} false. As
 * {@code FakeRenderHost}, it is stricter than {@code RenderHost}'s contract, as a test aid: an offer to a key that is
 * not open and a second open throw {@code IllegalStateException}, and the oracle throws for a record validation never
 * produces. Thread-safe: one lock guards everything; the oracles themselves are read through {@link #oracle} only
 * while nothing offers to that key.
 */
public final class ShadowOracle implements RenderHost {

    /** One opening of a key. */
    private static final class Shadow {

        final DeviceKey key;
        final SurfaceDesc surface;
        final TierEOracle oracle;
        /** Surfaces after each FRAME not yet confirmed by a checked readback: epoch, then frame id. */
        final TreeMap<Long, TreeMap<Long, byte[]>> frames = new TreeMap<>();
        boolean open = true;
        long checked;

        Shadow(DeviceKey key, SurfaceDesc surface, GlyphTable glyphs) {
            this.key = key;
            this.surface = surface;
            this.oracle = new TierEOracle(surface, glyphs);
        }

        byte[] frame(long epoch, long frameId) {
            TreeMap<Long, byte[]> ofEpoch = frames.get(epoch);
            return ofEpoch == null ? null : ofEpoch.get(frameId);
        }

        /** Drops every kept frame at or below (epoch, frameId): older epochs whole, and this epoch's up to frameId. */
        void dropUpTo(long epoch, long frameId) {
            frames.headMap(epoch, false)
                .clear();
            TreeMap<Long, byte[]> ofEpoch = frames.get(epoch);
            if (ofEpoch != null) {
                ofEpoch.headMap(frameId, true)
                    .clear();
                if (ofEpoch.isEmpty()) {
                    frames.remove(epoch);
                }
            }
        }

        int kept() {
            int n = 0;
            for (TreeMap<Long, byte[]> ofEpoch : frames.values()) {
                n += ofEpoch.size();
            }
            return n;
        }
    }

    private final GlyphTable glyphs;
    private final Object lock = new Object();
    /** The current or last opening of each key. */
    private final Map<DeviceKey, Shadow> shadows = new HashMap<>();
    private long lateReadbacks;

    /** @throws NullPointerException for a null table */
    public ShadowOracle(GlyphTable glyphs) {
        if (glyphs == null) {
            throw new NullPointerException("glyphs");
        }
        this.glyphs = glyphs;
    }

    public GlyphTable glyphs() {
        return glyphs;
    }

    /** True. */
    @Override
    public boolean available() {
        return true;
    }

    /** False. */
    @Override
    public boolean stalled() {
        return false;
    }

    /**
     * A new oracle on the surface, all zero (24:197; 21:242), replacing any earlier opening of the key; the sink is
     * never called.
     *
     * @throws NullPointerException  for a null key or surface
     * @throws IllegalStateException if the key is open already
     */
    @Override
    public void open(DeviceKey key, SurfaceDesc surface, ReadbackSink sink) {
        if (key == null || surface == null) {
            throw new NullPointerException("key and surface");
        }
        synchronized (lock) {
            Shadow old = shadows.get(key);
            if (old != null && old.open) {
                throw new IllegalStateException(key + " is open already");
            }
            shadows.put(key, new Shadow(key, surface, glyphs));
        }
    }

    /**
     * Runs the record on the key's oracle now; after a {@code FRAME} keeps the surface for (epoch, frame id).
     *
     * @throws NullPointerException  for a null argument
     * @throws IllegalStateException for a key that is not open, or from the oracle for a record validation never
     *                               produces
     */
    @Override
    public void offer(DeviceKey key, Record record) {
        if (key == null || record == null) {
            throw new NullPointerException("key and record");
        }
        synchronized (lock) {
            Shadow s = shadows.get(key);
            if (s == null || !s.open) {
                throw new IllegalStateException(key + " is not open");
            }
            record.accept(new Runner(s));
        }
    }

    /** Closes the key's opening and drops its kept frames; nothing for a key that is not open. */
    @Override
    public void release(DeviceKey key) {
        synchronized (lock) {
            Shadow s = shadows.get(key);
            if (s != null && s.open) {
                s.open = false;
                s.frames.clear();
            }
        }
    }

    /** Nothing: displays do not change a surface. */
    @Override
    public void bindDisplay(String displayId, DeviceKey key) {}

    /** Releases every open key of the session. */
    @Override
    public void releaseSession(long session) {
        synchronized (lock) {
            for (DeviceKey key : new ArrayList<>(shadows.keySet())) {
                if (key.session == session) {
                    release(key);
                }
            }
        }
    }

    /**
     * The oracle's surface right after the {@code FRAME} (epoch, frameId) of the key's current opening: a fresh copy in
     * the 28 §5.7 layout.
     *
     * @throws IllegalArgumentException for a frame never offered in the opening, or dropped after a checked readback
     *                                  of it or of a newer one
     */
    public byte[] pixelsAfter(DeviceKey key, long epoch, long frameId) {
        synchronized (lock) {
            Shadow s = shadows.get(key);
            byte[] pixels = s == null ? null : s.frame(epoch, frameId);
            if (pixels == null) {
                throw new IllegalArgumentException(
                    key + " e" + epoch + " #" + frameId + " was never offered, or a checked readback dropped it");
            }
            return pixels.clone();
        }
    }

    /**
     * The oracle's surface after every record offered to the key's current or last opening: a fresh copy.
     *
     * @throws IllegalStateException for a key never opened
     */
    public byte[] pixelsNow(DeviceKey key) {
        synchronized (lock) {
            return shadow(key).oracle.pixels();
        }
    }

    /**
     * The key's current or last oracle itself, for a test to compare textures with; read it only while nothing offers
     * to that key.
     *
     * @throws IllegalStateException for a key never opened
     */
    public TierEOracle oracle(DeviceKey key) {
        synchronized (lock) {
            return shadow(key).oracle;
        }
    }

    /** The frames kept for the key's current or last opening; 0 for a key never opened. */
    public int framesKept(DeviceKey key) {
        synchronized (lock) {
            Shadow s = shadows.get(key);
            return s == null ? 0 : s.kept();
        }
    }

    /** Readbacks the key's checking sinks compared, in its current or last opening. */
    public long readbacksChecked(DeviceKey key) {
        synchronized (lock) {
            Shadow s = shadows.get(key);
            return s == null ? 0 : s.checked;
        }
    }

    /** Readbacks that reached a checking sink after its opening was released: forwarded, not checked. */
    public long lateReadbacks() {
        synchronized (lock) {
            return lateReadbacks;
        }
    }

    /**
     * A sink for the key's current opening that compares each readback with {@link #pixelsAfter}, appends a failure
     * (the key, epoch, frame, the first differing pixel with both values and the row around it) to {@code failures}
     * under its lock, drops the kept frames at or below the readback, then forwards the call to {@code next} with no
     * lock held. A readback after the opening's release is forwarded unchecked and counted as late; one for another
     * key, of a frame not kept, or of the wrong length is a failure.
     *
     * @throws NullPointerException  for a null argument
     * @throws IllegalStateException for a key that is not open
     */
    public ReadbackSink checking(final DeviceKey key, final ReadbackSink next, final List<String> failures) {
        if (key == null || next == null || failures == null) {
            throw new NullPointerException("key, next and failures");
        }
        final Shadow s;
        synchronized (lock) {
            s = shadows.get(key);
            if (s == null || !s.open) {
                throw new IllegalStateException(key + " is not open");
            }
        }
        return new ReadbackSink() {

            @Override
            public void onReadback(long session, int deviceId, long epoch, long frameId, byte[] pixels) {
                String failure = check(s, session, deviceId, epoch, frameId, pixels);
                if (failure != null) {
                    synchronized (failures) {
                        failures.add(failure);
                    }
                }
                next.onReadback(session, deviceId, epoch, frameId, pixels);
            }

            @Override
            public String toString() {
                return "ShadowOracle.checking(" + key + ") -> " + next;
            }
        };
    }

    @Override
    public String toString() {
        synchronized (lock) {
            return "ShadowOracle[" + shadows.size() + " keys]";
        }
    }

    private Shadow shadow(DeviceKey key) {
        Shadow s = shadows.get(key);
        if (s == null) {
            throw new IllegalStateException(key + " was never opened");
        }
        return s;
    }

    /** The comparison of one readback, or null when it matches; drops the frames it confirms. */
    private String check(Shadow s, long session, int deviceId, long epoch, long frameId, byte[] pixels) {
        String where = s.key + " e" + epoch + " #" + frameId;
        synchronized (lock) {
            if (session != s.key.session || deviceId != s.key.deviceId) {
                return where + ": a readback for " + session + ":" + deviceId + " reached this key's sink";
            }
            if (!s.open) {
                lateReadbacks++;
                return null;
            }
            byte[] expected = s.frame(epoch, frameId);
            if (expected == null) {
                return where + ": no such frame was offered, or a newer readback was checked before it (21:87)";
            }
            s.checked++;
            s.dropUpTo(epoch, frameId);
            if (pixels == null || pixels.length != expected.length) {
                return where + ": "
                    + (pixels == null ? "null pixels" : pixels.length + " bytes")
                    + ", expected "
                    + expected.length;
            }
            return firstDifference(where, s.surface, expected, pixels);
        }
    }

    /**
     * The first pixel where two surfaces of the same layout differ (S8 plan §4: the pixel, both values and its
     * neighbours in the row), as "where: pixel (x, y) of fmt is 0x.., expected 0x..; row y from x x0: actual [..],
     * expected [..]"; null when they are equal. Both arrays must hold {@code surface.byteSize()} bytes.
     */
    public static String firstDifference(String where, SurfaceDesc surface, byte[] expected, byte[] actual) {
        int bpp = surface.bytesPerPixel();
        int n = surface.pixelCount();
        for (int i = 0; i < n; i++) {
            if (value(expected, i, bpp) != value(actual, i, bpp)) {
                int x = i % surface.width;
                int y = i / surface.width;
                int x0 = Math.max(0, x - 2);
                int x1 = Math.min(surface.width, x + 3);
                return String.format(
                    Locale.ROOT,
                    "%s: pixel (%d, %d) of %s is 0x%X, expected 0x%X; row %d from x %d: actual %s, expected %s",
                    where,
                    x,
                    y,
                    Formats.name(surface.format),
                    value(actual, i, bpp),
                    value(expected, i, bpp),
                    y,
                    x0,
                    row(actual, y * surface.width, x0, x1, bpp),
                    row(expected, y * surface.width, x0, x1, bpp));
            }
        }
        return null;
    }

    private static int value(byte[] pixels, int i, int bpp) {
        return bpp == 1 ? pixels[i] & 0xFF : (pixels[2 * i] & 0xFF) | (pixels[2 * i + 1] & 0xFF) << 8;
    }

    private static String row(byte[] pixels, int base, int x0, int x1, int bpp) {
        StringBuilder out = new StringBuilder("[");
        for (int x = x0; x < x1; x++) {
            if (x > x0) {
                out.append(' ');
            }
            out.append(String.format(Locale.ROOT, "%X", value(pixels, base + x, bpp)));
        }
        return out.append(']')
            .toString();
    }

    /** One record's mapping onto the oracle (as {@code OracleRecordRenderer}'s), plus the kept surface per FRAME. */
    private static final class Runner implements RecordVisitor {

        private final Shadow s;

        Runner(Shadow s) {
            this.s = s;
        }

        @Override
        public void frame(FrameRecord r) {
            for (int i = 0; i < r.segmentCount(); i++) {
                s.oracle.runSegment(r.segment(i));
            }
            TreeMap<Long, byte[]> ofEpoch = s.frames.get(r.epoch);
            if (ofEpoch == null) {
                ofEpoch = new TreeMap<>();
                s.frames.put(r.epoch, ofEpoch);
            }
            ofEpoch.put(r.frameId, s.oracle.pixels());
        }

        @Override
        public void texCreate(TexCreateRecord r) {
            s.oracle.createTexture(r.handle, r.width, r.height, r.format);
        }

        @Override
        public void texUpdate(TexUpdateRecord r) {
            s.oracle.updateTexture(r.handle, r.x, r.y, r.width, r.height, r.payload());
        }

        @Override
        public void texFree(TexFreeRecord r) {
            s.oracle.freeTexture(r.handle);
        }

        @Override
        public void reset(ResetRecord r) {
            s.oracle.reset(r.pixels());
        }
    }
}
