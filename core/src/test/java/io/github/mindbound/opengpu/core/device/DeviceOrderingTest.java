package io.github.mindbound.opengpu.core.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.HandleTypes;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.ManualClock;
import io.github.mindbound.opengpu.core.fixtures.device.OracleRecordRenderer;
import io.github.mindbound.opengpu.core.fixtures.device.RecordText;
import io.github.mindbound.opengpu.core.fixtures.device.RecordingBudget;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FakeRenderHost;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;

/**
 * The ordering rules O1-O7 of 28 §2.2 (28:132-138) through {@code Device} on {@code FakeRenderHost} with the tier-E
 * oracle (28 §11.4, 28:936): the two examples of 28:143-144 and the worked frame of 28 §9.4 (28:840, :842), each
 * asserting the offered sequence and the server tail; O1 across two devices, O2's silent submits and refused presents,
 * O5's execution in offered order and O7's full-handle keys under slot reuse (H21). Every step of every scenario is
 * followed by {@link Invariants#checkAll}, I9's replay included.
 */
class DeviceOrderingTest {

    private static final int T = 0x08000000;

    /** 28:143: an update made while a frame is open, with an empty tail, folds at once and both blits show it. */
    @Test
    void example1TheUpdateFoldsAtOnceAndBothBlitsShowIt() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        // t created and folded beforehand
        Assertions.assertEquals(T, rig.create(16, 16));
        rig.step();
        check(rig, history);
        int before = offered(rig).size();
        byte[] a = segment(blit(T, 0, 0, 16, 16, 0, 0, 0));
        byte[] b = segment(blit(T, 0, 0, 16, 16, 20, 0, 0));
        byte[] p = pattern(256, 1);
        rig.submit(a);
        rig.update(T, 0, 0, 16, 16, p);
        rig.submit(b);
        rig.free(T);
        Assertions.assertEquals(1L, rig.present());
        List<Record> offered = offered(rig).subList(before, offered(rig).size());
        Assertions.assertEquals(
            Arrays.asList(
                "TEX_UPDATE e=1 tex=0x08000000 at=0,0 size=16x16 len=256 crc=" + crc(p),
                "FRAME e=1 id=1 segs=2 bytes=54 records=2 clear=0 fill=0 copy=512 set=0 crc=" + crc(a, b),
                "TEX_FREE e=1 tex=0x08000000"),
            RecordText.of(offered));
        FrameRecord frame = (FrameRecord) offered.get(1);
        Assertions.assertSame(a, frame.segment(0));
        Assertions.assertSame(b, frame.segment(1));
        Assertions.assertEquals(RecordText.of(offered.subList(1, 3)), rig.tailText());
        DeviceSnapshot s = check(rig, history);
        Assertions.assertArrayEquals(
            p,
            s.masters.get(T)
                .texels(),
            "TEX_UPDATE folded at once");
        Assertions.assertEquals(64, s.queuedBytes);
        rig.step();
        s = check(rig, history);
        TierEOracle host = rig.hostOracle();
        assertRegion(host, 0, 0, 16, 16, p);
        assertRegion(host, 20, 0, 16, 16, p);
        Assertions.assertFalse(host.hasTexture(T));
        Assertions.assertEquals(1, s.confirmedFrameId);
        Assertions.assertTrue(s.tail.isEmpty());
        Assertions.assertTrue(s.masters.isEmpty(), "t has no master once its TEX_FREE folded");
        Assertions.assertArrayEquals(host.pixels(), rig.screen());
    }

    /**
     * 28:144: with FRAME 5 in flight, an update lands between FRAME 5 and FRAME 6; readback 5 folds FRAME 5 and then
     * the
     * update at the head (21:231), and the masters hold the new contents only then.
     */
    @Test
    void example2AnUpdateBetweenFramesInFlightLandsBetweenThem() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        Assertions.assertEquals(T, rig.create(16, 16));
        byte[] old = pattern(256, 3);
        rig.update(T, 0, 0, 16, 16, old);
        for (int i = 1; i <= 4; i++) {
            rig.submit(segment(clear(i)));
            Assertions.assertEquals(i, rig.present());
            rig.step();
            check(rig, history);
        }
        int before = offered(rig).size();
        byte[] blitT = segment(blit(T, 0, 0, 16, 16, 100, 50, 0));
        rig.submit(blitT);
        Assertions.assertEquals(5L, rig.present());
        byte[] fresh = pattern(256, 4);
        rig.update(T, 0, 0, 16, 16, fresh);
        rig.submit(blitT);
        Assertions.assertEquals(6L, rig.present());
        String frame5 = "FRAME e=1 id=5 segs=1 bytes=27 records=1 clear=0 fill=0 copy=256 set=0 crc=" + crc(blitT);
        String update = "TEX_UPDATE e=1 tex=0x08000000 at=0,0 size=16x16 len=256 crc=" + crc(fresh);
        String frame6 = "FRAME e=1 id=6 segs=1 bytes=27 records=1 clear=0 fill=0 copy=256 set=0 crc=" + crc(blitT);
        List<String> expected = Arrays.asList(frame5, update, frame6);
        Assertions.assertEquals(expected, RecordText.of(offered(rig).subList(before, offered(rig).size())));
        Assertions.assertEquals(expected, rig.tailText());
        DeviceSnapshot s = check(rig, history);
        Assertions.assertArrayEquals(
            old,
            s.masters.get(T)
                .texels(),
            "the masters lag: FRAME 5 starts from them");
        Assertions.assertEquals(64 + 256, s.queuedBytes);
        // the host runs FRAME 5 only and reads it back
        Assertions.assertEquals(1, rig.host.run(rig.key, 1));
        Assertions.assertTrue(rig.host.readback(rig.key));
        s = check(rig, history);
        Assertions.assertEquals(5, s.confirmedFrameId);
        assertRead(rig, 100, 50, 16, 16, 5, old);
        Assertions.assertArrayEquals(
            fresh,
            s.masters.get(T)
                .texels(),
            "the TEX_UPDATE at the head folded");
        Assertions.assertEquals(Collections.singletonList(frame6), rig.tailText());
        Assertions.assertEquals(0, s.queuedBytes);
        rig.step();
        s = check(rig, history);
        Assertions.assertEquals(6, s.confirmedFrameId);
        assertRead(rig, 100, 50, 16, 16, 6, fresh);
        Assertions.assertEquals(Arrays.asList("done 5", "done 6"), lastTwo(rig.listener.events()));
    }

    /**
     * 28 §9.4's worked frame at T2 and T3 (28:840, :842): offered {@code TEX_CREATE}, {@code TEX_UPDATE},
     * {@code FRAME(1)}; the tail {@code FRAME(1)} only; the charges of 28 §8.2; and the readback equal to an oracle
     * that
     * ran the same records.
     */
    @ParameterizedTest
    @ValueSource(ints = { 2, 3 })
    void theWorkedFrameOffersCreateUpdateFrame(int tier) {
        DeviceRig rig = DeviceRig.tier(tier);
        Invariants.History history = Invariants.History.of(rig.device);
        byte[] sprite = pattern(tier == 2 ? 256 : 512, 9);
        byte[] s = workedSegment(tier);
        Assertions.assertEquals(64, s.length);
        Assertions.assertEquals(T, rig.create(16, 16));
        rig.update(T, 0, 0, 16, 16, sprite);
        rig.submit(s);
        Assertions.assertEquals(1L, rig.present());
        Assertions.assertEquals(
            tier == 2
                ? Arrays
                    .asList("consume 0.000976563", "consume 0.000976563", "consume 0.015686035", "consume 0.062500000")
                : Arrays
                    .asList("consume 0.001953125", "consume 0.001953125", "consume 0.007873535", "consume 0.031250000"),
            rig.budget.events());
        List<Record> offered = offered(rig);
        Assertions.assertEquals(3, offered.size());
        Assertions.assertEquals(
            Record.TEX_CREATE,
            offered.get(0)
                .kind());
        Assertions.assertEquals(
            Record.TEX_UPDATE,
            offered.get(1)
                .kind());
        Assertions.assertEquals(
            Record.FRAME,
            offered.get(2)
                .kind());
        Assertions.assertSame(sprite, ((TexUpdateRecord) offered.get(1)).payload());
        Assertions.assertEquals(
            Collections.singletonList(RecordText.of(offered.get(2))),
            rig.tailText(),
            "both resource records folded at once (O6)");
        check(rig, history);
        rig.step();
        DeviceSnapshot snap = check(rig, history);
        TierEOracle expected = new TierEOracle(rig.device.surface(), SyntheticGlyphs.TABLE);
        expected.createTexture(T, 16, 16, rig.device.surface().format);
        expected.updateTexture(T, 0, 0, 16, 16, sprite);
        expected.runFrame(s);
        Assertions.assertEquals(1, snap.confirmedFrameId);
        Assertions.assertEquals(
            1,
            rig.host.readbacks(rig.key)
                .size());
        Assertions.assertArrayEquals(
            expected.pixels(),
            rig.host.readbacks(rig.key)
                .get(0).pixels);
        Assertions.assertArrayEquals(expected.pixels(), rig.screen());
        Assertions.assertArrayEquals(expected.pixels(), snap.confirmedPixels());
    }

    /** The T2 worked segment is 28 §9.4's table byte for byte (28:833-838). */
    @Test
    void theT2WorkedSegmentIsTheTable() {
        Assertions.assertArrayEquals(
            TestEncoder.hex(
                "4F 47 50 55 01 00 00 00 | 01 00 00 00 00 | 30 0A 00 14 00 64 00 32 00 C4 00 00 00"
                    + " | 31 00 00 00 08 00 00 00 00 10 00 10 00 96 00 28 00 01"
                    + " | 33 08 00 B4 00 FF 00 00 00 00 00 00 00 01 00 02 00 48 69 | FF"),
            workedSegment(2));
    }

    /** O1 (28:132): each device's records are offered in its own call order; nothing orders them across devices. */
    @Test
    void twoInterleavedDevicesKeepTheirOwnCallOrder() {
        FakeRenderHost host = new FakeRenderHost(new OracleRecordRenderer());
        DeviceKey ka = new DeviceKey(1, 1);
        DeviceKey kb = new DeviceKey(1, 2);
        DeviceCaps caps = DeviceCaps.forTier(2);
        Device a = Device.create(ka, caps, host, Runnable::run, FrameListener.NONE, new ManualClock());
        Device b = Device.create(kb, caps, host, Runnable::run, FrameListener.NONE, new ManualClock());
        Invariants.History ha = Invariants.History.of(a);
        Invariants.History hb = Invariants.History.of(b);
        RecordingBudget budget = new RecordingBudget();
        byte[] pa = pattern(64, 5);
        byte[] pb = pattern(16, 6);
        byte[] sa = segment(blit(T, 0, 0, 8, 8, 0, 0, 0));
        byte[] sb = segment(fill(0, 0, 4, 4, 7), blit(T, 0, 0, 4, 4, 10, 10, 0));
        Assertions.assertEquals(T, ok(b.createTexture(budget, 4, 4, null)).handle());
        Assertions.assertEquals(T, ok(a.createTexture(budget, 8, 8, "index8")).handle());
        ok(b.submit(budget, sb));
        ok(a.updateTexture(budget, T, 0, 0, 8, 8, pa));
        ok(b.updateTexture(budget, T, 0, 0, 4, 4, pb));
        ok(a.submit(budget, sa));
        Assertions.assertEquals(1L, ok(b.present(budget)).frameId());
        ok(a.free(T));
        Assertions.assertEquals(1L, ok(a.present(budget)).frameId());
        Assertions.assertEquals(
            Arrays.asList(
                "TEX_CREATE e=1 tex=0x08000000 size=8x8 format=1",
                "TEX_UPDATE e=1 tex=0x08000000 at=0,0 size=8x8 len=64 crc=" + crc(pa),
                "FRAME e=1 id=1 segs=1 bytes=27 records=1 clear=0 fill=0 copy=64 set=0 crc=" + crc(sa),
                "TEX_FREE e=1 tex=0x08000000"),
            RecordText.of(host.offered(ka)));
        Assertions.assertEquals(
            Arrays.asList(
                "TEX_CREATE e=1 tex=0x08000000 size=4x4 format=1",
                "TEX_UPDATE e=1 tex=0x08000000 at=0,0 size=4x4 len=16 crc=" + crc(pb),
                "FRAME e=1 id=1 segs=1 bytes=40 records=2 clear=0 fill=16 copy=16 set=0 crc=" + crc(sb)),
            RecordText.of(host.offered(kb)));
        Assertions.assertEquals(Arrays.asList("open DeviceKey[1:1]", "open DeviceKey[1:2]"), host.log());
        Invariants.checkAll(a, host, ha);
        Invariants.checkAll(b, host, hb);
        Assertions.assertEquals(7, host.step());
        DeviceSnapshot sa1 = Invariants.checkAll(a, host, ha);
        DeviceSnapshot sb1 = Invariants.checkAll(b, host, hb);
        Assertions.assertEquals(1, sa1.confirmedFrameId);
        Assertions.assertEquals(1, sb1.confirmedFrameId);
        OracleRecordRenderer oracles = (OracleRecordRenderer) host.renderer();
        assertRegion(oracles.oracle(ka), 0, 0, 8, 8, pa);
        assertRegion(oracles.oracle(kb), 10, 10, 4, 4, pb);
        Assertions.assertEquals(
            7,
            oracles.oracle(kb)
                .pixel(0, 0));
    }

    /**
     * O2 (28:133): a submit never offers or touches the tail, and a present refused with {@code "busy"} or
     * {@code "host render stalled"} offers nothing and keeps the open frame, which a later present offers whole.
     */
    @Test
    void submitsAndRefusedPresentsOfferNothing() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        rig.present();
        rig.present();
        byte[] a = segment(clear(4));
        byte[] b = segment(fill(1, 1, 5, 5, 6));
        rig.submit(a);
        Assertions.assertEquals(2, offered(rig).size(), "a submit offers nothing");
        Assertions.assertEquals(2, rig.snapshot().tail.size(), "nor touches the tail");
        assertStatus(rig, "busy");
        rig.submit(b);
        Assertions.assertEquals(2, offered(rig).size());
        check(rig, history);
        rig.step();
        check(rig, history);
        rig.host.setStalled(true);
        assertStatus(rig, "host render stalled");
        Assertions.assertEquals(2, offered(rig).size());
        Assertions.assertEquals(Arrays.asList(a, b), rig.snapshot().openSegments);
        rig.host.setStalled(false);
        Assertions.assertEquals(3L, rig.present());
        List<Record> offered = offered(rig);
        Assertions.assertEquals(3, offered.size());
        FrameRecord f = (FrameRecord) offered.get(2);
        Assertions.assertEquals(Arrays.asList(a, b), f.segments());
        rig.step();
        Assertions.assertEquals(3, check(rig, history).confirmedFrameId);
    }

    /** O5 (28:136): the host runs a device's records strictly in offered order, every one of them. */
    @Test
    void theHostRunsTheOfferedSequenceInOrder() {
        DeviceRig rig = DeviceRig.tier(3);
        Invariants.History history = Invariants.History.of(rig.device);
        int t = rig.create(8, 8);
        rig.submit(segment(clear(0xFF102030), blit(t, 0, 0, 8, 8, 3, 3, 0)));
        rig.update(t, 0, 0, 2, 2, pattern(8, 2));
        rig.present();
        rig.update(t, 4, 4, 4, 4, pattern(32, 3));
        rig.submit(segment(text(0, 0, 0xFFFFFFFF, 0xFF000000, 0, 0, "ok")));
        rig.free(t);
        rig.present();
        rig.step();
        check(rig, history);
        List<Record> offered = offered(rig);
        List<Record> ran = rig.host.ran(rig.key);
        Assertions.assertEquals(offered.size(), ran.size());
        for (int i = 0; i < offered.size(); i++) {
            Assertions.assertSame(offered.get(i), ran.get(i), "record " + i);
        }
        Assertions.assertEquals(0, rig.host.queued(rig.key));
    }

    /**
     * O7 (28:138) and H21: with {@code FRAME 1} blitting t (slot 0, generation 0) in flight and t's {@code TEX_FREE}
     * behind it, 2 047 create/free pairs walk the other slots until slot 0 returns as {@code 0x08000001}, all queued
     * behind the frame; masters and host keep both generations apart, and after a step frame 1 shows t's texels and
     * frame 2 the new texture's.
     */
    @Test
    void aSlotReusedWhileItsOldGenerationIsInTheTailDoesNotCollide() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        Assertions.assertEquals(T, rig.create(16, 16));
        byte[] p = pattern(256, 11);
        rig.update(T, 0, 0, 16, 16, p);
        byte[] blitT = segment(blit(T, 0, 0, 16, 16, 0, 0, 0));
        rig.submit(blitT);
        Assertions.assertEquals(1L, rig.present());
        rig.free(T);
        Assertions.assertEquals(
            Arrays.asList(
                "FRAME e=1 id=1 segs=1 bytes=27 records=1 clear=0 fill=0 copy=256 set=0 crc=" + crc(blitT),
                "TEX_FREE e=1 tex=0x08000000"),
            rig.tailText());
        for (int slot = 1; slot <= Handles.MAX_SLOT; slot++) {
            int h = rig.create(1, 1);
            Assertions.assertEquals(Handles.pack(HandleTypes.TEXTURE, slot, 0), h);
            rig.free(h);
        }
        int t2 = rig.create(16, 16);
        Assertions.assertEquals(0x08000001, t2, "slot 0 back with generation 1");
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertEquals(2 + 2 * Handles.MAX_SLOT + 1, s.tail.size());
        Assertions.assertEquals(Collections.singleton(T), s.masters.keySet(), "only t has folded");
        Assertions.assertArrayEquals(
            p,
            s.masters.get(T)
                .texels());
        byte[] q = pattern(256, 12);
        rig.update(t2, 0, 0, 16, 16, q);
        rig.submit(segment(blit(t2, 0, 0, 16, 16, 20, 0, 0)));
        Assertions.assertEquals(2L, rig.present());
        s = check(rig, history);
        Assertions.assertEquals(64 + 2047 * (65 + 64) + 320 + 320, s.queuedBytes);
        rig.step();
        s = check(rig, history);
        TierEOracle host = rig.hostOracle();
        assertRegion(host, 0, 0, 16, 16, p);
        assertRegion(host, 20, 0, 16, 16, q);
        Assertions.assertFalse(host.hasTexture(T));
        Assertions.assertTrue(host.hasTexture(t2));
        Assertions.assertEquals(1, host.textureCount());
        Assertions.assertEquals(Collections.singleton(t2), s.masters.keySet());
        Assertions.assertArrayEquals(
            q,
            s.masters.get(t2)
                .texels());
        Assertions.assertTrue(s.tail.isEmpty());
        Assertions.assertEquals(2, s.confirmedFrameId);
    }

    // ---- helpers, shared with the other device tests of this unit

    /** The 64-byte worked segment of 28 §9.4 for the tier (28:823-824, :842). */
    static byte[] workedSegment(int tier) {
        if (tier == 2) {
            return segment(
                clear(0),
                fill(10, 20, 100, 50, 196),
                blit(T, 0, 0, 16, 16, 150, 40, 1),
                text(8, 180, 255, 0, 1, 0, "Hi"));
        }
        return segment(
            clear(0xFF000000),
            fill(10, 20, 100, 50, 0xFFFF8000),
            blit(T, 0, 0, 16, 16, 150, 40, 1),
            text(8, 180, 0xFFFFFFFF, 0xFF000000, 1, 0, "Hi"));
    }

    /** n bytes of a seeded, non-repeating pattern: valid texels of either format. */
    static byte[] pattern(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i * 7 + seed * 31 + (i >> 5) * 13 + 1);
        }
        return b;
    }

    /** The CRC32 of the parts as {@code RecordText} prints it. */
    static String crc(byte[]... parts) {
        return String.format(Locale.ROOT, "%08X", RecordText.crc32(parts));
    }

    static CallResult ok(CallResult r) {
        Assertions.assertTrue(r.isOk(), r::toString);
        return r;
    }

    /** The rig's host's offered records. */
    static List<Record> offered(DeviceRig rig) {
        return rig.host.offered(rig.key);
    }

    /** {@link Invariants#checkAll} on the rig. */
    static DeviceSnapshot check(DeviceRig rig, Invariants.History history) {
        return Invariants.checkAll(rig.device, rig.host, history);
    }

    /** The oracle's region, rows top first, equals the texels (28 §5.7 layout of the oracle's format). */
    static void assertRegion(TierEOracle oracle, int x, int y, int w, int h, byte[] texels) {
        SurfaceDesc surface = oracle.surface();
        int bpp = surface.bytesPerPixel();
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int k = (j * w + i) * bpp;
                int want = bpp == 1 ? texels[k] & 0xFF : (texels[k] & 0xFF) | (texels[k + 1] & 0xFF) << 8;
                Assertions.assertEquals(want, oracle.pixel(x + i, y + j), "pixel (" + (x + i) + ", " + (y + j) + ")");
            }
        }
    }

    /** readPixels of the region returns these bytes and frame id. */
    static void assertRead(DeviceRig rig, int x, int y, int w, int h, long frameId, byte[] expected) {
        CallResult r = ok(rig.device.readPixels(rig.budget, x, y, w, h));
        Assertions.assertEquals(frameId, r.frameId());
        Assertions.assertArrayEquals(expected, r.data());
    }

    private static void assertStatus(DeviceRig rig, String status) {
        Assertions.assertArrayEquals(
            new Object[] { null, status },
            rig.call(b -> rig.device.present(b))
                .toLua());
    }

    private static List<String> lastTwo(List<String> events) {
        return new ArrayList<>(events.subList(events.size() - 2, events.size()));
    }
}
