package io.github.mindbound.opengpu.core.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;

/**
 * {@code updateTexture(tex, x, y, w, h, data)} (28:101, :344, §6.3; plan §3.4): the check order (handle, region,
 * length, then H2's tail bound); a {@code TEX_UPDATE} that carries the argument array itself (28:562; C14), offered
 * at call time (O3) and folded at once on an empty tail or queued behind a {@code FRAME} (O6); its rows land at their
 * offsets in the master (28:561), on T3 as LE u16 unchanged; amendment B12's full-then-partial pair, folded and run by
 * the host with the first payload untouched; and H23 leaving updates alone.
 */
class DeviceUpdateTextureTest {

    private static final int T = 0x08000000;

    @Test
    void theRecordCarriesTheArgumentArrayItself() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(16, 16);
        byte[] data = ramp(256, 1);
        rig.update(T, 0, 0, 16, 16, data);
        List<Record> offered = rig.host.offered(rig.key);
        Assertions.assertEquals(2, offered.size());
        TexUpdateRecord u = (TexUpdateRecord) offered.get(1);
        Assertions.assertSame(data, u.payload());
        Assertions.assertEquals(T, u.handle);
        Assertions.assertEquals(0, u.x);
        Assertions.assertEquals(0, u.y);
        Assertions.assertEquals(16, u.width);
        Assertions.assertEquals(16, u.height);
        Assertions.assertEquals(1, u.epoch);
        // folded at once on the empty tail (O6)
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertTrue(s.tail.isEmpty());
        Assertions.assertArrayEquals(
            data,
            s.masters.get(T)
                .texels());
        Assertions.assertNotSame(
            data,
            s.masters.get(T)
                .texels());
    }

    @Test
    void behindAFrameTheUpdateQueuesAndTheMasterLags() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(16, 16);
        rig.present();
        byte[] data = ramp(256, 3);
        rig.update(T, 0, 0, 16, 16, data);
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertEquals(2, s.tail.size());
        Assertions.assertSame(data, ((TexUpdateRecord) s.tail.get(1)).payload());
        Assertions.assertEquals(64 + 256, s.queuedBytes);
        // the master holds what FRAME 1 starts from (21:231)
        Assertions.assertArrayEquals(
            new byte[256],
            s.masters.get(T)
                .texels());
        rig.step();
        s = rig.snapshot();
        Assertions.assertTrue(s.tail.isEmpty());
        Assertions.assertEquals(0, s.queuedBytes);
        Assertions.assertArrayEquals(
            data,
            s.masters.get(T)
                .texels());
    }

    @Test
    void aRegionLandsAtItsOffsetInTheMasterAndOnTheHost() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(16, 16);
        byte[] data = { 1, 2, 3, 4, 5, 6, 7, 8 };
        rig.update(T, 3, 5, 4, 2, data);
        byte[] expected = new byte[256];
        System.arraycopy(data, 0, expected, 5 * 16 + 3, 4);
        System.arraycopy(data, 4, expected, 6 * 16 + 3, 4);
        Assertions.assertArrayEquals(
            expected,
            rig.snapshot().masters.get(T)
                .texels());
        rig.step();
        TierEOracle oracle = rig.hostOracle();
        Assertions.assertEquals(1, oracle.texel(T, 3, 5));
        Assertions.assertEquals(4, oracle.texel(T, 6, 5));
        Assertions.assertEquals(8, oracle.texel(T, 6, 6));
        Assertions.assertEquals(0, oracle.texel(T, 7, 6));
        Assertions.assertEquals(0, oracle.texel(T, 2, 5));
    }

    @Test
    void anRgb565PayloadLandsUnchangedAsLittleEndianU16() {
        // 28:561: rows top first, w·2 bytes each, LE u16
        DeviceRig rig = DeviceRig.tier(3);
        rig.create(4, 2);
        byte[] data = { (byte) 0x00, (byte) 0xF8, (byte) 0xE0, (byte) 0x07, (byte) 0x1F, (byte) 0x00, (byte) 0xFF,
            (byte) 0xFF, (byte) 0x34, (byte) 0x12, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x80, (byte) 0xAA,
            (byte) 0x55 };
        rig.update(T, 0, 0, 4, 2, data);
        Assertions.assertArrayEquals(
            data,
            rig.snapshot().masters.get(T)
                .texels());
        rig.step();
        TierEOracle oracle = rig.hostOracle();
        Assertions.assertEquals(0xF800, oracle.texel(T, 0, 0));
        Assertions.assertEquals(0x07E0, oracle.texel(T, 1, 0));
        Assertions.assertEquals(0x001F, oracle.texel(T, 2, 0));
        Assertions.assertEquals(0xFFFF, oracle.texel(T, 3, 0));
        Assertions.assertEquals(0x1234, oracle.texel(T, 0, 1));
        Assertions.assertEquals(0x55AA, oracle.texel(T, 3, 1));
        // a sub-region of 2 × 1 at (1, 1)
        rig.update(T, 1, 1, 2, 1, new byte[] { 0x11, 0x22, 0x33, 0x44 });
        byte[] texels = rig.snapshot().masters.get(T)
            .texels();
        Assertions.assertArrayEquals(
            new byte[] { (byte) 0x34, (byte) 0x12, 0x11, 0x22, 0x33, 0x44, (byte) 0xAA, (byte) 0x55 },
            Arrays.copyOfRange(texels, 8, 16));
    }

    @Test
    void aFullUpdateThenAPartialOneLeaveTheFirstPayloadUntouched() {
        // B12, folded at once: the master copies rows, it never adopts P
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(16, 16);
        byte[] p = ramp(256, 7);
        byte[] pCopy = p.clone();
        rig.update(T, 0, 0, 16, 16, p);
        rig.update(T, 4, 4, 2, 2, new byte[] { 9, 9, 9, 9 });
        Assertions.assertArrayEquals(pCopy, p);
        List<Record> offered = rig.host.offered(rig.key);
        Assertions.assertTrue(
            offered.get(1)
                .intact());
        Assertions.assertTrue(
            offered.get(2)
                .intact());
        byte[] expected = pCopy.clone();
        expected[4 * 16 + 4] = expected[4 * 16 + 5] = expected[5 * 16 + 4] = expected[5 * 16 + 5] = 9;
        Assertions.assertArrayEquals(
            expected,
            rig.snapshot().masters.get(T)
                .texels());
        // the host runs both, asserting each record intact, and ends with the same texture
        rig.step();
        Assertions.assertEquals(
            9,
            rig.hostOracle()
                .texel(T, 5, 5));
        Assertions.assertEquals(
            pCopy[3 * 16 + 4] & 0xFF,
            rig.hostOracle()
                .texel(T, 4, 3));
    }

    @Test
    void aQueuedFullAndPartialPairFoldsBeforeTheHostRunsThem() {
        // B12, queued: both records wait behind FRAME 1; the fold applies them to the master first, then the host
        // runs them, so a master that adopted P would already have written P before the host reads it
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(16, 16);
        rig.present();
        byte[] p = ramp(256, 11);
        byte[] pCopy = p.clone();
        rig.update(T, 0, 0, 16, 16, p);
        rig.update(T, 0, 15, 16, 1, ramp(16, 200));
        Assertions.assertEquals(3, rig.snapshot().tail.size());
        // the host runs TEX_CREATE and FRAME 1 only and reads back: the fold takes FRAME 1, then both updates at
        // the tail's head
        Assertions.assertEquals(2, rig.host.run(rig.key, 2));
        Assertions.assertTrue(rig.host.readback(rig.key));
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertEquals(1, s.confirmedFrameId);
        Assertions.assertTrue(s.tail.isEmpty());
        Assertions.assertArrayEquals(pCopy, p);
        List<Record> offered = rig.host.offered(rig.key);
        Assertions.assertTrue(
            offered.get(2)
                .intact());
        Assertions.assertTrue(
            offered.get(3)
                .intact());
        // now the host runs the two updates (FakeRenderHost asserts intact() on each)
        Assertions.assertEquals(2, rig.host.run(rig.key, Integer.MAX_VALUE));
        byte[] expected = pCopy.clone();
        System.arraycopy(ramp(16, 200), 0, expected, 15 * 16, 16);
        Assertions.assertArrayEquals(
            expected,
            s.masters.get(T)
                .texels());
        TierEOracle oracle = rig.hostOracle();
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                Assertions.assertEquals(expected[y * 16 + x] & 0xFF, oracle.texel(T, x, y), x + "," + y);
            }
        }
    }

    @Test
    void theNextFrameBlitsTheUpdatedTexels() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(4, 4);
        rig.update(T, 0, 0, 4, 4, ramp(16, 1));
        rig.submit(segment(blit(T, 0, 0, 4, 4, 10, 20, 0)));
        Assertions.assertEquals(1, rig.present());
        rig.step();
        CallResult r = rig.device.readPixels(rig.budget, 10, 20, 4, 4);
        Assertions.assertEquals(1, r.frameId());
        Assertions.assertArrayEquals(ramp(16, 1), r.data());
    }

    @Test
    void theHandleIsCheckedBeforeTheRegionAndTheRegionBeforeTheLength() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(16, 16);
        // a stale handle with a bad region and a bad length
        assertFails(rig, 0x08000001, 20, 20, 0, 0, new byte[3], "handle 0x08000001 is not live");
        assertFails(rig, 0x10000000, -1, 0, 1, 1, new byte[3], "handle 0x10000000 is a buffer, expected a texture");
        // a bad region with a bad length
        assertFails(rig, T, 0, 0, 17, 1, new byte[3], "invalid texture region");
        // the length after a good region, formed from it
        assertFails(rig, T, 2, 3, 5, 7, new byte[34], "data must be 35 bytes");
    }

    @Test
    void theTailBoundAppliesOnlyBehindAFrame() {
        // H2: a whole 1 024 × 1 024 update on an empty tail folds at once and is never queued
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(1024, 1024);
        rig.budget.clearEvents();
        byte[] whole = new byte[1048576];
        rig.update(T, 0, 0, 1024, 1024, whole);
        Assertions.assertEquals(Arrays.asList("limit", "pause 0.100000000"), rig.budget.events());
        Assertions.assertEquals(0, rig.snapshot().queuedBytes);
        // behind FRAME 1: 1 048 576 + 64 exceeds the cap; 1 024 × 1 023 fits
        rig.present();
        assertFails(rig, T, 0, 0, 1024, 1024, whole, "busy");
        rig.update(T, 0, 0, 1024, 1023, new byte[1047552]);
        Assertions.assertEquals(64 + 1047552, rig.snapshot().queuedBytes);
    }

    @Test
    void pendingFreesDoNotCountAgainstUpdates() {
        // amendment A: H23 is createTexture's alone; updateTexture creates nothing
        DeviceRig rig = DeviceRig.tier(2);
        int a = rig.create(1024, 512);
        int b = rig.create(1024, 512);
        rig.submit(TestEncoder.segment());
        rig.free(a);
        Assertions.assertEquals(524288, rig.snapshot().deferredBytes);
        rig.update(b, 0, 0, 1024, 512, new byte[524288]);
        Assertions.assertEquals(
            Record.TEX_UPDATE,
            rig.host.offered(rig.key)
                .get(2)
                .kind());
    }

    @Test
    void aNullBudgetOrDataIsRefused() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(1, 1);
        Assertions
            .assertThrows(NullPointerException.class, () -> rig.device.updateTexture(null, T, 0, 0, 1, 1, new byte[1]));
        Assertions
            .assertThrows(NullPointerException.class, () -> rig.device.updateTexture(rig.budget, T, 0, 0, 1, 1, null));
        Assertions.assertEquals(
            1,
            rig.host.offered(rig.key)
                .size());
    }

    /** The update fails with the message, uncharged, offering nothing and changing nothing. */
    private static void assertFails(DeviceRig rig, int tex, int x, int y, int w, int h, byte[] data, String message) {
        rig.budget.clearEvents();
        String before = DeviceCallbackTableTest.state(rig.snapshot());
        List<String> offered = rig.offeredText();
        CallResult r = rig.call(b -> rig.device.updateTexture(b, tex, x, y, w, h, data));
        Assertions.assertArrayEquals(new Object[] { null, message }, r.toLua());
        Assertions.assertEquals(Collections.emptyList(), rig.budget.events());
        Assertions.assertEquals(offered, rig.offeredText());
        Assertions.assertEquals(before, DeviceCallbackTableTest.state(rig.snapshot()));
    }

    /** n bytes start, start + 1, ... (mod 256): distinct enough to show where each byte lands. */
    private static byte[] ramp(int n, int start) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (start + i);
        }
        return b;
    }
}
