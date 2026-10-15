package io.github.mindbound.opengpu.core.device;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.junit.jupiter.api.Assertions;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.cmd.HandleTypes;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
import io.github.mindbound.opengpu.core.fixtures.device.OracleRecordRenderer;
import io.github.mindbound.opengpu.core.fixtures.device.RecordText;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FakeRenderHost;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.ResetRecord;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexFreeRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;

/**
 * The device invariants of S7 plan §3.1 as assertions, with amendment A's I10-I12 (H23), shared by the device tests and
 * the model test in {@code core.device.model} (amendment B7). It reads only {@link DeviceSnapshot}, the public records,
 * {@link FakeRenderHost}'s inspection methods and the test fixtures; every method throws JUnit's assertion error with
 * the invariant's name and the snapshot.
 * <ul>
 * <li><b>I1</b> the server tail is empty or starts with a {@code FRAME} (28:122; O6, 28:137);</li>
 * <li><b>I2</b> the unconfirmed frames are the tail's {@code FRAME}s, at most {@code maxUnconfirmedFrames}
 * (28:140);</li>
 * <li><b>I3</b> the queued bytes are the tail's resource records' {@link Record#queuedBytes()} (28:140);</li>
 * <li><b>I4</b> the VRAM use is Σ w·h·bpp of the live textures, within {@code vramBytes}; at most
 * {@code maxTextures} are live (28 §6.2);</li>
 * <li><b>I5</b> live handles are type 1 with their slot's current generation, and the live and the free slots
 * partition 0-2 047 (28 §6.1);</li>
 * <li><b>I6</b> the tail's frames have consecutive ids ending at the last presented one, all above K (28:108);</li>
 * <li><b>I7</b> the open frame has segments iff it is open, and frees are deferred only while it is (28:121, O4);</li>
 * <li><b>I8</b> every record offered in an epoch carries it, a {@code RESET} the next one, frame ids grow by 1, and
 * nothing is offered after {@code release} (28:150-151, :173);</li>
 * <li><b>I9</b> the replay: a fresh tier-E oracle that runs {@code RESET(K's pixels)}, one {@code TEX_CREATE} and one
 * whole {@code TEX_UPDATE} per master, then the tail, ends with the surface and textures of an oracle that ran every
 * record offered since the device was created (21:231; 28:137, :144's "on replay"). It holds when the readbacks came
 * from {@link OracleRecordRenderer}, so a test that delivers readbacks of its own makes does not check it;</li>
 * <li><b>I10</b> {@code queuedBytes ≤ vramBytes + 64 · maxTextures} (amendment A, replacing H11);</li>
 * <li><b>I11</b> at most {@code maxTextures} masters, of at most {@code vramBytes} bytes together (28:577, restored by
 * H23);</li>
 * <li><b>I12</b> {@code live + deferredCount ≤ maxTextures} and {@code vramUsed + deferredBytes ≤ vramBytes}
 * (H23).</li>
 * </ul>
 * The full-history oracle of I9 is a {@link History}, fed only the records offered since its last feed (amendment B8),
 * never rebuilt.
 */
public final class Invariants {

    private static final DeviceKey REPLAY = new DeviceKey(-1, -1);

    private Invariants() {}

    /**
     * I1-I7 and I10-I12 on one snapshot of a device with these caps.
     *
     * @return the snapshot
     */
    public static DeviceSnapshot check(DeviceSnapshot s, DeviceCaps caps) {
        String where = " in " + s;
        // I1
        if (!s.tail.isEmpty()) {
            Assertions.assertEquals(
                Record.FRAME,
                s.tail.get(0)
                    .kind(),
                "I1: the tail starts with " + s.tail.get(0) + where);
        }
        // I2, I3, I6, and every tail record carries the epoch (I8)
        int frames = 0;
        long queued = 0;
        long previousId = -1;
        for (Record r : s.tail) {
            Assertions.assertEquals(s.epoch, r.epoch, "I8: tail record " + r + " of another epoch" + where);
            if (r.kind() == Record.FRAME) {
                long id = ((FrameRecord) r).frameId;
                if (frames == 0) {
                    Assertions.assertTrue(id > s.confirmedFrameId, "I6: tail frame " + id + " is not above K" + where);
                } else {
                    Assertions.assertEquals(previousId + 1, id, "I6: tail frames are not consecutive" + where);
                }
                previousId = id;
                frames++;
            } else {
                Assertions.assertTrue(r.isResource(), "I1: " + r + " in the tail is no resource record" + where);
                queued += r.queuedBytes();
            }
        }
        Assertions.assertEquals(frames, s.unconfirmedFrames, "I2: unconfirmedFrames" + where);
        Assertions.assertTrue(frames <= caps.maxUnconfirmedFrames, "I2: " + frames + " unconfirmed frames" + where);
        Assertions.assertEquals(queued, s.queuedBytes, "I3: queuedBytes" + where);
        if (frames > 0) {
            Assertions.assertEquals(s.lastFrameId, previousId, "I6: the tail's last frame" + where);
        }
        Assertions.assertTrue(s.confirmedFrameId <= s.lastFrameId, "I6: K above the last frame" + where);
        Assertions.assertTrue(s.confirmedFrameId >= 0, "I6: K below 0" + where);
        // I4, I5
        int bpp = caps.surface.bytesPerPixel();
        int[] generations = s.generations();
        int[] free = s.freeSlots();
        Assertions.assertEquals(Handles.MAX_SLOT + 1, generations.length, "I5: generations" + where);
        boolean[] seen = new boolean[Handles.MAX_SLOT + 1];
        long vram = 0;
        int previousSlot = -1;
        // The loops below test first and build their messages only on a failure: the model test calls this after
        // every op, and building ~6 000 messages per call made it the test's main cost.
        for (DeviceSnapshot.LiveTexture t : s.live) {
            int slot = Handles.slot(t.handle);
            boolean ok = Handles.isWellFormed(t.handle) && Handles.type(t.handle) == HandleTypes.TEXTURE
                && slot > previousSlot
                && generations[slot] == Handles.generation(t.handle)
                && caps.surface.format == t.format
                && t.width >= 1
                && t.width <= caps.maxTextureSize
                && t.height >= 1
                && t.height <= caps.maxTextureSize;
            if (!ok) {
                String h = hex(t.handle);
                Assertions.assertTrue(Handles.isWellFormed(t.handle), "I5: live " + h + " is malformed" + where);
                Assertions
                    .assertEquals(HandleTypes.TEXTURE, Handles.type(t.handle), "I5: live " + h + "'s type" + where);
                Assertions.assertTrue(slot > previousSlot, "I5: live handles not in ascending slot order" + where);
                Assertions.assertEquals(
                    generations[slot],
                    Handles.generation(t.handle),
                    "I5: live " + h + " has not its slot's generation" + where);
                Assertions.assertEquals(caps.surface.format, t.format, "I5: live " + h + "'s format" + where);
                Assertions.fail("I4: live " + h + " is " + t.width + "x" + t.height + where);
            }
            previousSlot = slot;
            seen[slot] = true;
            vram += (long) t.width * t.height * bpp;
        }
        for (int slot : free) {
            if (slot < 0 || slot > Handles.MAX_SLOT) {
                Assertions.fail("I5: free slot " + slot + where);
            }
            if (seen[slot]) {
                Assertions.fail("I5: slot " + slot + " is both live and free, or queued twice" + where);
            }
            seen[slot] = true;
        }
        for (int slot = 0; slot <= Handles.MAX_SLOT; slot++) {
            if (!seen[slot]) {
                Assertions.fail("I5: slot " + slot + " is neither live nor free" + where);
            }
        }
        Assertions.assertEquals(vram, s.vramUsed, "I4: vramUsed" + where);
        Assertions.assertTrue(s.vramUsed <= caps.vramBytes, "I4: vramUsed above the cap" + where);
        Assertions.assertTrue(s.live.size() <= caps.maxTextures, "I4: " + s.live.size() + " live textures" + where);
        // I7
        if (s.openSegments.isEmpty()) {
            Assertions.assertEquals(FrameTotals.EMPTY, s.openTotals, "I7: totals without an open frame" + where);
            Assertions.assertTrue(s.deferredFrees.isEmpty(), "I7: frees deferred without an open frame" + where);
            Assertions.assertEquals(0, s.deferredBytes, "I7: deferred bytes without an open frame" + where);
        }
        Assertions.assertEquals(s.deferredFrees.size(), s.deferredCount, "I7: deferredCount" + where);
        for (int h : s.deferredFrees) {
            for (DeviceSnapshot.LiveTexture t : s.live) {
                if (t.handle == h) {
                    Assertions.fail("O4: deferred free " + hex(h) + " is still live" + where);
                }
            }
        }
        // I10
        Assertions.assertTrue(
            s.queuedBytes <= (long) caps.vramBytes + (long) Record.RESOURCE_OVERHEAD * caps.maxTextures,
            "I10: queuedBytes " + s.queuedBytes + " above vramBytes + 64 · maxTextures" + where);
        // I11
        Assertions.assertTrue(s.masters.size() <= caps.maxTextures, "I11: " + s.masters.size() + " masters" + where);
        long masterBytes = 0;
        for (Map.Entry<Integer, MasterTexture> e : s.masters.entrySet()) {
            MasterTexture m = e.getValue();
            if (e.getKey() != m.handle) {
                Assertions.fail("O7: a master under another key" + where);
            }
            long length = (long) m.width * m.height * Formats.bytesPerPixel(m.format);
            if (length != m.texels().length) {
                Assertions
                    .assertEquals(length, m.texels().length, "I11: master " + hex(m.handle) + "'s length" + where);
            }
            masterBytes += m.texels().length;
        }
        Assertions.assertTrue(masterBytes <= caps.vramBytes, "I11: " + masterBytes + " B of masters" + where);
        // I12
        Assertions.assertTrue(
            s.live.size() + s.deferredCount <= caps.maxTextures,
            "I12: live + deferred above maxTextures" + where);
        Assertions.assertTrue(
            s.vramUsed + s.deferredBytes <= caps.vramBytes,
            "I12: vramUsed + deferredBytes above vramBytes" + where);
        return s;
    }

    /**
     * I8 for a quiescent device: its epoch is the history's (one above it after {@code release}, which offers
     * nothing), and the history saw every frame presented.
     */
    public static void checkEpochs(DeviceSnapshot s, History history) {
        Assertions.assertEquals(
            s.released ? history.epoch() + 1 : history.epoch(),
            s.epoch,
            "I8: the device's epoch against the offered RESETs in " + s);
        Assertions.assertEquals(history.lastFrameId(), s.lastFrameId, "I8: frames presented but not offered in " + s);
    }

    /** I9 on a snapshot of an unreleased device whose confirmed pixels came from the tier-E oracle. */
    public static void checkReplay(DeviceSnapshot s, History history) {
        Assertions.assertFalse(s.released, "I9 is for a device that is not released");
        OracleRecordRenderer replay = new OracleRecordRenderer(history.renderer.glyphs());
        replay.open(REPLAY, history.surface);
        Textures expected = new Textures();
        replay.accept(REPLAY, new ResetRecord(0, s.confirmedPixels()));
        for (MasterTexture m : s.masters.values()) {
            replay.accept(REPLAY, new TexCreateRecord(0, m.handle, m.width, m.height, m.format));
            replay.accept(REPLAY, new TexUpdateRecord(0, m.handle, 0, 0, m.width, m.height, m.texels()));
            expected.put(
                m.handle,
                m.width,
                m.height,
                m.format,
                m.texels()
                    .clone());
        }
        for (Record r : s.tail) {
            replay.accept(REPLAY, r);
            expected.apply(r);
        }
        TierEOracle oracle = replay.oracle(REPLAY);
        String where = " after " + history.fed() + " offered records, in " + s;
        if (!Arrays.equals(history.pixels(), oracle.pixels())) {
            Assertions.fail("I9: the replay's surface differs from the full history's" + where);
        }
        history.textures.assertSame(expected, "I9: the replay's textures (masters + tail)" + where);
        history.textures.assertOn(oracle, "I9: the replay oracle's textures" + where);
    }

    /**
     * Every invariant of a quiescent device on a {@link FakeRenderHost} that keeps its history: I1-I7 and I10-I12 on
     * a snapshot; the newly offered records fed to the history (I8); I9 unless the device is released, when the host
     * must have the key closed; and, once the host has run every record it was offered, its oracle equals the
     * history's (O5). I9 and the host comparison need an {@link OracleRecordRenderer}; on another renderer they are
     * skipped.
     *
     * @return the snapshot
     */
    public static DeviceSnapshot checkAll(Device device, FakeRenderHost host, History history) {
        DeviceSnapshot s = check(device.snapshot(), device.caps());
        DeviceKey key = device.key();
        history.feedNew(host.offered(key));
        checkEpochs(s, history);
        if (s.released) {
            Assertions.assertFalse(host.isOpen(key), "I8: the host still has the released key open");
            return s;
        }
        if (host.renderer() instanceof OracleRecordRenderer) {
            checkReplay(s, history);
            if (host.queued(key) == 0) {
                TierEOracle ran = ((OracleRecordRenderer) host.renderer()).oracle(key);
                if (!Arrays.equals(history.pixels(), ran.pixels())) {
                    Assertions.fail("O5: the drained host's surface differs from the full history's in " + s);
                }
                history.textures.assertOn(ran, "O5: the drained host's textures in " + s);
            }
        }
        return s;
    }

    /**
     * A stable one-line description of everything a snapshot holds, for "nothing changed" assertions: equal for two
     * snapshots of an unchanged device.
     */
    public static String describe(DeviceSnapshot s) {
        StringBuilder b = new StringBuilder();
        b.append(
            String.format(
                Locale.ROOT,
                "e%d%s last %d K %d pixels %08X",
                s.epoch,
                s.released ? " released" : "",
                s.lastFrameId,
                s.confirmedFrameId,
                RecordText.crc32(s.confirmedPixels())));
        b.append(" tail ")
            .append(RecordText.of(s.tail));
        b.append(
            String.format(
                Locale.ROOT,
                " frames %d queued %d vram %d live %s",
                s.unconfirmedFrames,
                s.queuedBytes,
                s.vramUsed,
                s.live));
        b.append(" masters [");
        for (MasterTexture m : s.masters.values()) {
            b.append(String.format(Locale.ROOT, "%s %08X; ", m, RecordText.crc32(m.texels())));
        }
        b.append(
            String.format(
                Locale.ROOT,
                "] free %d/%08X generations %08X",
                s.freeSlots().length,
                Arrays.hashCode(s.freeSlots()),
                Arrays.hashCode(s.generations())));
        b.append(" open [");
        for (byte[] segment : s.openSegments) {
            b.append(String.format(Locale.ROOT, "%d B %08X; ", segment.length, RecordText.crc32(segment)));
        }
        b.append("] ")
            .append(s.openTotals);
        b.append(" deferred [");
        for (int h : s.deferredFrees) {
            b.append(hex(h))
                .append(' ');
        }
        b.append(
            String.format(
                Locale.ROOT,
                "] %d B exhausted %b stale %d rejected %d",
                s.deferredBytes,
                s.budgetExhausted,
                s.staleReadbacks,
                s.rejectedReadbacks));
        return b.toString();
    }

    /** "0x%08X". */
    public static String hex(int handle) {
        return String.format(Locale.ROOT, "0x%08X", handle);
    }

    /**
     * I8 and I9's full history: a tier-E oracle that runs every record offered for one device, in offered order, with
     * the textures' contents also kept as byte arrays, and the epoch and frame id the records carry. Feed it with
     * {@link #feedNew} from {@code host.offered(key)}, which hands it only the records offered since the last call.
     * Not thread-safe.
     */
    public static final class History {

        private final SurfaceDesc surface;
        private final OracleRecordRenderer renderer = new OracleRecordRenderer();
        private final Textures textures = new Textures();
        private long epoch;
        private long lastFrameId;
        private int fed;

        /** A history of a device on the surface whose first epoch is initialEpoch, before its first offer. */
        public History(SurfaceDesc surface, long initialEpoch) {
            this.surface = surface;
            this.epoch = initialEpoch;
            renderer.open(REPLAY, surface);
        }

        /** {@code new History(device.surface(), device.epoch())}: call it before the device offers anything. */
        public static History of(Device device) {
            return new History(device.surface(), device.epoch());
        }

        /**
         * Feeds {@code offered.subList(fed(), offered.size())}: offered is the device's whole offered sequence so far,
         * and every earlier record came through this method.
         */
        public void feedNew(List<? extends Record> offered) {
            Assertions.assertTrue(
                offered.size() >= fed,
                "the offered sequence shrank from " + fed + " to " + offered.size() + " records");
            for (int i = fed; i < offered.size(); i++) {
                feed(offered.get(i));
            }
        }

        private void feed(Record r) {
            String what = "I8: offered record " + fed + ", " + r;
            if (r.kind() == Record.RESET) {
                Assertions.assertEquals(epoch + 1, r.epoch, what + ", does not carry the next epoch");
                epoch = r.epoch;
            } else {
                Assertions.assertEquals(epoch, r.epoch, what + ", is not of the current epoch");
            }
            if (r.kind() == Record.FRAME) {
                long id = ((FrameRecord) r).frameId;
                Assertions.assertEquals(lastFrameId + 1, id, what + ", is not the next frame");
                lastFrameId = id;
            }
            Assertions.assertTrue(r.intact(), what + ", changed after it was offered");
            renderer.accept(REPLAY, r);
            textures.apply(r);
            fed++;
        }

        /** The records fed so far. */
        public int fed() {
            return fed;
        }

        /** The epoch of the newest record fed (the first epoch before any {@code RESET}). */
        public long epoch() {
            return epoch;
        }

        /** The newest {@code FRAME}'s id, 0 before the first. */
        public long lastFrameId() {
            return lastFrameId;
        }

        /** The full history's surface now, 28 §5.7 layout, a fresh array. */
        public byte[] pixels() {
            return renderer.pixels(REPLAY);
        }

        /** The full history's oracle. */
        public TierEOracle oracle() {
            return renderer.oracle(REPLAY);
        }

        /** The full history's textures: a copy of each one's texels, by full handle. */
        public SortedMap<Integer, byte[]> textures() {
            return textures.copy();
        }
    }

    /** Textures as byte arrays in the 28 §5.7 layout, by full handle (O7): the records' effect without an oracle. */
    private static final class Textures {

        private final TreeMap<Integer, int[]> sizes = new TreeMap<>();
        private final TreeMap<Integer, byte[]> texels = new TreeMap<>();

        void put(int handle, int w, int h, int format, byte[] bytes) {
            sizes.put(handle, new int[] { w, h, format });
            texels.put(handle, bytes);
        }

        void apply(Record r) {
            switch (r.kind()) {
                case Record.TEX_CREATE: {
                    TexCreateRecord c = (TexCreateRecord) r;
                    Assertions.assertFalse(sizes.containsKey(c.handle), r + ": the handle exists already (O7)");
                    put(c.handle, c.width, c.height, c.format, new byte[c.byteSize()]);
                    return;
                }
                case Record.TEX_UPDATE: {
                    TexUpdateRecord u = (TexUpdateRecord) r;
                    int[] size = sizes.get(u.handle);
                    Assertions.assertNotNull(size, r + ": no such texture");
                    int bpp = Formats.bytesPerPixel(size[2]);
                    byte[] t = texels.get(u.handle);
                    int row = u.width * bpp;
                    for (int j = 0; j < u.height; j++) {
                        System.arraycopy(u.payload(), j * row, t, ((u.y + j) * size[0] + u.x) * bpp, row);
                    }
                    return;
                }
                case Record.TEX_FREE: {
                    int handle = ((TexFreeRecord) r).handle;
                    Assertions.assertNotNull(sizes.remove(handle), r + ": no such texture");
                    texels.remove(handle);
                    return;
                }
                case Record.RESET:
                    sizes.clear();
                    texels.clear();
                    return;
                default:
                    // FRAME: draws on the surface only
            }
        }

        void assertSame(Textures other, String what) {
            Assertions.assertEquals(handles(), other.handles(), what + ": the handles");
            for (Map.Entry<Integer, int[]> e : sizes.entrySet()) {
                int handle = e.getKey();
                Assertions
                    .assertArrayEquals(e.getValue(), other.sizes.get(handle), what + ": " + hex(handle) + "'s size");
                if (!Arrays.equals(texels.get(handle), other.texels.get(handle))) {
                    Assertions.fail(what + ": " + hex(handle) + "'s texels differ");
                }
            }
        }

        /** The oracle has exactly these textures, with these texels where they are small enough to read one by one. */
        void assertOn(TierEOracle oracle, String what) {
            Assertions.assertEquals(sizes.size(), oracle.textureCount(), what + ": the texture count");
            for (Map.Entry<Integer, int[]> e : sizes.entrySet()) {
                int handle = e.getKey();
                Assertions.assertTrue(oracle.hasTexture(handle), what + ": " + hex(handle) + " is missing");
                int[] size = e.getValue();
                if (size[0] * size[1] > 4096) {
                    continue;
                }
                byte[] t = texels.get(handle);
                boolean index8 = size[2] == Formats.INDEX8;
                for (int y = 0; y < size[1]; y++) {
                    for (int x = 0; x < size[0]; x++) {
                        int i = y * size[0] + x;
                        int want = index8 ? t[i] & 0xFF : (t[2 * i] & 0xFF) | (t[2 * i + 1] & 0xFF) << 8;
                        if (oracle.texel(handle, x, y) != want) {
                            Assertions.fail(what + ": " + hex(handle) + " differs at (" + x + ", " + y + ")");
                        }
                    }
                }
            }
        }

        List<Integer> handles() {
            return new ArrayList<>(sizes.keySet());
        }

        SortedMap<Integer, byte[]> copy() {
            TreeMap<Integer, byte[]> copy = new TreeMap<>();
            for (Map.Entry<Integer, byte[]> e : texels.entrySet()) {
                copy.put(
                    e.getKey(),
                    e.getValue()
                        .clone());
            }
            return copy;
        }
    }
}
