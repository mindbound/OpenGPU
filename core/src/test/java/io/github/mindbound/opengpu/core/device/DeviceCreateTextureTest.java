package io.github.mindbound.opengpu.core.device;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.RecordText;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;

/**
 * {@code createTexture(w, h[, format])} (28:100, :343, §6.2; plan §3.4): the order of its checks when several fail
 * (size, format, count, VRAM, then the pending frees of H23 and the tail bound of H2); its effect (handles from the
 * FIFO, VRAM, a {@code TEX_CREATE} in the surface format offered at call time (O3) and folded at once into a zeroed
 * master on an empty tail (O6), or queued behind a {@code FRAME}); H2's empty-tail rule at T2; and the owner's
 * decision Q-S7-1 (amendment A, H23): frees deferred in the open frame still count against the count and VRAM caps
 * until a {@code present} or the {@code "no renderer"} discard appends them, or a reset drops them.
 */
class DeviceCreateTextureTest {

    private static final int T = 0x08000000;
    private static final byte[] NO_OP = TestEncoder.segment();

    /** Plan §4's small index8 caps: 24×16, VRAM 2 048, 6 textures of at most 32. */
    static DeviceCaps small() {
        return new DeviceCaps(
            new SurfaceDesc(2, 24, 16, Formats.INDEX8),
            new FrameLimits(65536, 512, 24, 4000),
            2048,
            6,
            32,
            256,
            2);
    }

    @Test
    void theSizeIsCheckedBeforeTheFormat() {
        DeviceRig rig = DeviceRig.tier(2);
        assertFails(rig, 0, 1, "rgb565", "invalid texture size");
        assertFails(rig, 1025, 1, "", "invalid texture size");
    }

    @Test
    void theFormatIsCheckedBeforeTheCount() {
        DeviceRig rig = DeviceRig.of(small());
        for (int i = 0; i < 6; i++) {
            rig.create(1, 1);
        }
        assertFails(rig, 1, 1, "rgb565", "unsupported format");
        assertFails(rig, 1, 1, null, "too many textures");
    }

    @Test
    void theCountIsCheckedBeforeTheVram() {
        // six live textures that also fill the 2 048 B of VRAM: 1 024 + 992 + 4 × 8
        DeviceRig rig = DeviceRig.of(small());
        rig.create(32, 32);
        rig.create(32, 31);
        for (int i = 0; i < 4; i++) {
            rig.create(8, 1);
        }
        Assertions.assertEquals(2048, rig.snapshot().vramUsed);
        assertFails(rig, 1, 1, null, "too many textures");
    }

    @Test
    void theVramIsCheckedBeforeTheTailBound() {
        // FRAME 1 in the tail and 999 424 B queued behind it: a 64 KB create fails both checks
        DeviceRig rig = DeviceRig.tier(2);
        rig.present();
        rig.create(1024, 976);
        assertFails(rig, 1024, 64, null, "not enough video memory");
        // and a create that fits the VRAM but not the tail bound is busy (H2)
        assertFails(rig, 185, 265, null, "busy");
    }

    @Test
    void theVramIsCheckedBeforeThePendingFrees() {
        // H23's check comes after check 4, so "not enough video memory" keeps its meaning
        DeviceRig rig = DeviceRig.tier(2);
        int a = rig.create(1024, 512);
        rig.create(1024, 512);
        rig.submit(NO_OP);
        rig.free(a);
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertEquals(524288, s.vramUsed);
        Assertions.assertEquals(524288, s.deferredBytes);
        assertFails(rig, 1024, 1024, null, "not enough video memory");
        assertFails(rig, 1, 1, null, "busy");
    }

    @Test
    void theCountIsCheckedBeforeThePendingFrees() {
        DeviceRig rig = DeviceRig.of(small());
        for (int i = 0; i < 6; i++) {
            rig.create(1, 1);
        }
        rig.submit(NO_OP);
        rig.free(T);
        // live 5 + pending 1 reaches the cap of 6: busy, not "too many textures"
        assertFails(rig, 1, 1, null, "busy");
    }

    @Test
    void handlesComeFromTheFifoAndVramAddsUp() {
        DeviceRig rig = DeviceRig.tier(2);
        Assertions.assertEquals(0x08000000, rig.create(16, 16));
        Assertions.assertEquals(0x08010000, rig.create(8, 4));
        Assertions.assertEquals(0x08020000, rig.create(1, 1));
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertEquals(256 + 32 + 1, s.vramUsed);
        Assertions.assertEquals(3, s.live.size());
        Assertions.assertEquals(8, s.live.get(1).width);
        Assertions.assertEquals(4, s.live.get(1).height);
        Assertions.assertEquals(Formats.INDEX8, s.live.get(1).format);
        // a freed slot goes to the queue's tail with its generation bumped (28:533)
        rig.free(0x08010000);
        Assertions.assertEquals(0x08030000, rig.create(1, 1));
        Assertions.assertEquals(256 + 1 + 1, rig.snapshot().vramUsed);
    }

    @Test
    void theRecordIsOfferedInTheSurfaceFormatAndFoldsAtOnceOnAnEmptyTail() {
        DeviceRig t2 = DeviceRig.tier(2);
        t2.create(16, 16);
        Assertions.assertEquals(
            Collections.singletonList("TEX_CREATE e=1 tex=0x08000000 size=16x16 format=1"),
            t2.offeredText());
        DeviceSnapshot s = t2.snapshot();
        Assertions.assertTrue(s.tail.isEmpty());
        Assertions.assertEquals(0, s.queuedBytes);
        Assertions.assertEquals(1, s.masters.size());
        MasterTexture m = s.masters.get(T);
        Assertions.assertEquals(16, m.width);
        Assertions.assertEquals(16, m.height);
        Assertions.assertEquals(Formats.INDEX8, m.format);
        // 28:550: all zeros
        Assertions.assertArrayEquals(new byte[256], m.texels());

        DeviceRig t3 = DeviceRig.tier(3);
        t3.call(b -> t3.device.createTexture(b, 16, 8, "rgb565"));
        Assertions.assertEquals(
            Collections.singletonList("TEX_CREATE e=1 tex=0x08000000 size=16x8 format=2"),
            t3.offeredText());
        Assertions.assertArrayEquals(
            new byte[256],
            t3.snapshot().masters.get(T)
                .texels());
        TexCreateRecord r = (TexCreateRecord) t3.host.offered(t3.key)
            .get(0);
        Assertions.assertEquals(Formats.RGB565, r.format);
        Assertions.assertEquals(1, r.epoch);
    }

    @Test
    void behindAFrameTheRecordQueuesUntilTheFold() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.present();
        rig.create(16, 16);
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertEquals(2, s.tail.size());
        Assertions.assertEquals(
            Record.TEX_CREATE,
            s.tail.get(1)
                .kind());
        Assertions.assertEquals(64 + 256, s.queuedBytes);
        Assertions.assertTrue(s.masters.isEmpty(), "the master lags until the fold (21:231)");
        // the texture is live for validation at once (O3)
        Assertions.assertEquals(1, s.live.size());
        Assertions.assertEquals(256, s.vramUsed);
        rig.step();
        s = rig.snapshot();
        Assertions.assertEquals(1, s.confirmedFrameId);
        Assertions.assertTrue(s.tail.isEmpty());
        Assertions.assertEquals(0, s.queuedBytes);
        Assertions.assertArrayEquals(
            new byte[256],
            s.masters.get(T)
                .texels());
        Assertions.assertTrue(
            rig.hostOracle()
                .hasTexture(T));
    }

    @Test
    void aWholeMegabyteFitsAnEmptyTailButNotOneBehindAFrame() {
        // H2: the queued check applies only to a record that joins the tail
        DeviceRig empty = DeviceRig.tier(2);
        Assertions.assertEquals(T, empty.create(1024, 1024));
        Assertions.assertEquals(Arrays.asList("limit", "pause 0.100000000"), empty.budget.events());
        DeviceSnapshot s = empty.snapshot();
        Assertions.assertEquals(1048576, s.vramUsed);
        Assertions.assertEquals(
            1048576,
            s.masters.get(T)
                .texels().length);
        Assertions.assertEquals(0, s.queuedBytes);

        DeviceRig behind = DeviceRig.tier(2);
        behind.present();
        behind.budget.clearEvents();
        String before = DeviceCallbackTableTest.state(behind.snapshot());
        Assertions.assertArrayEquals(
            new Object[] { null, "busy" },
            behind.call(b -> behind.device.createTexture(b, 1024, 1024, null))
                .toLua());
        Assertions.assertEquals(Collections.emptyList(), behind.budget.events());
        Assertions.assertEquals(before, DeviceCallbackTableTest.state(behind.snapshot()));
        // after the frame is confirmed the tail is empty again, and the same create succeeds
        behind.step();
        Assertions.assertEquals(T, behind.create(1024, 1024));
    }

    @Test
    void aFreeDeferredInTheOpenFrameKeepsItsVramUntilPresent() {
        // amendment A: T2, 1 MB created and freed while a frame is open
        DeviceRig rig = DeviceRig.tier(2);
        int t = rig.create(1024, 1024);
        rig.submit(NO_OP);
        rig.free(t);
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertEquals(0, s.vramUsed, "O4: credited at once");
        Assertions.assertEquals(1, s.deferredCount);
        Assertions.assertEquals(1048576, s.deferredBytes);
        Assertions.assertEquals(Collections.singletonList(t), s.deferredFrees);
        Assertions.assertTrue(s.masters.containsKey(t), "its master stays until its TEX_FREE folds");
        assertFails(rig, 1, 1, null, "busy");
        // present appends the TEX_FREE behind the FRAME; the pending bytes are gone, the queued check applies
        Assertions.assertEquals(1, rig.present());
        s = rig.snapshot();
        Assertions.assertEquals(0, s.deferredCount);
        Assertions.assertEquals(0, s.deferredBytes);
        Assertions.assertEquals(
            Arrays.asList(
                "FRAME e=1 id=1 segs=1 bytes=9 records=0 clear=0 fill=0 copy=0 set=0 crc=" + crc(NO_OP),
                "TEX_FREE e=1 tex=0x08000000"),
            rig.tailText());
        Assertions.assertEquals(0x08010000, rig.create(1, 1));
        // the fold of FRAME 1 drops t's master, then folds the 1×1 that queued behind its TEX_FREE
        rig.step();
        s = rig.snapshot();
        Assertions.assertEquals(Collections.singleton(0x08010000), s.masters.keySet());
        Assertions.assertTrue(s.tail.isEmpty());
    }

    @Test
    void theExactBoundIsAllowedAndOneByteMoreIsBusy() {
        // vramUsed + deferredBytes + size ≤ vramBytes (I12)
        DeviceRig rig = DeviceRig.tier(2);
        int a = rig.create(1024, 512);
        rig.submit(NO_OP);
        rig.free(a);
        Assertions.assertEquals(0x08010000, rig.create(1024, 512));
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertEquals(524288, s.vramUsed);
        Assertions.assertEquals(524288, s.deferredBytes);
        assertFails(rig, 1, 1, null, "busy");
    }

    @Test
    void theCountBoundAllowsLivePlusPendingBelowTheCap() {
        DeviceRig rig = DeviceRig.of(small());
        for (int i = 0; i < 5; i++) {
            rig.create(1, 1);
        }
        rig.submit(NO_OP);
        rig.free(T);
        // live 4 + pending 1 = 5 < 6
        Assertions.assertEquals(0x08050000, rig.create(1, 1));
        // live 5 + pending 1 = 6
        assertFails(rig, 1, 1, null, "busy");
        rig.free(0x08010000);
        assertFails(rig, 1, 1, null, "busy");
        Assertions.assertEquals(2, rig.snapshot().deferredCount);
    }

    @Test
    void theNoRendererDiscardEndsThePendingFrees() {
        DeviceRig rig = DeviceRig.tier(2);
        int t = rig.create(1024, 1024);
        rig.submit(NO_OP);
        rig.free(t);
        rig.host.setAvailable(false);
        Assertions.assertArrayEquals(
            new Object[] { null, "no renderer" },
            rig.call(b -> rig.device.present(b))
                .toLua());
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertEquals(0, s.deferredCount);
        Assertions.assertEquals(0, s.deferredBytes);
        // the TEX_FREE folded at once on the empty tail (O6)
        Assertions.assertTrue(s.masters.isEmpty());
        Assertions.assertEquals(
            Arrays.asList("TEX_CREATE e=1 tex=0x08000000 size=1024x1024 format=1", "TEX_FREE e=1 tex=0x08000000"),
            rig.offeredText());
        // slot 0 went to the queue's tail at the free, so the next texture is slot 1
        Assertions.assertEquals(0x08010000, rig.create(1024, 1024));
    }

    @Test
    void aResetDropsThePendingFrees() {
        DeviceRig rig = DeviceRig.of(small());
        for (int i = 0; i < 6; i++) {
            rig.create(1, 1);
        }
        rig.submit(NO_OP);
        rig.free(T);
        assertFails(rig, 1, 1, null, "busy");
        rig.device.reset();
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertEquals(0, s.deferredCount);
        Assertions.assertEquals(0, s.deferredBytes);
        Assertions.assertEquals(0, s.live.size());
        // six creates fit again
        for (int i = 0; i < 6; i++) {
            rig.create(1, 1);
        }
        assertFails(rig, 1, 1, null, "too many textures");
    }

    @Test
    void aFreeWithNoOpenFrameIsNotPending() {
        DeviceRig rig = DeviceRig.tier(2);
        int t = rig.create(1024, 1024);
        rig.free(t);
        Assertions.assertEquals(0, rig.snapshot().deferredBytes);
        Assertions.assertEquals(0x08010000, rig.create(1024, 1024));
    }

    @Test
    void theSideCapIsTheDevicesMaxTextureSize() {
        // 28:343's 1-1 024 is DeviceCaps.maxTextureSize; the small caps lower it to 32 (H20)
        DeviceRig rig = DeviceRig.of(small());
        Assertions.assertEquals(T, rig.create(32, 1));
        Assertions.assertEquals(0x08010000, rig.create(1, 32));
        assertFails(rig, 33, 1, null, "invalid texture size");
        assertFails(rig, 1, 33, null, "invalid texture size");
    }

    @Test
    void aNullBudgetIsRefused() {
        DeviceRig rig = DeviceRig.tier(2);
        Assertions.assertThrows(NullPointerException.class, () -> rig.device.createTexture(null, 1, 1, null));
        Assertions.assertTrue(
            rig.offeredText()
                .isEmpty());
    }

    /** The create fails with the message, uncharged, offering nothing and changing nothing. */
    static void assertFails(DeviceRig rig, int w, int h, String format, String message) {
        rig.budget.clearEvents();
        String before = DeviceCallbackTableTest.state(rig.snapshot());
        List<String> offered = rig.offeredText();
        CallResult r = rig.call(b -> rig.device.createTexture(b, w, h, format));
        Assertions.assertArrayEquals(new Object[] { null, message }, r.toLua(), w + "x" + h + " " + format);
        Assertions.assertEquals(Collections.emptyList(), rig.budget.events());
        Assertions.assertEquals(offered, rig.offeredText());
        Assertions.assertEquals(before, DeviceCallbackTableTest.state(rig.snapshot()));
    }

    private static String crc(byte[] bytes) {
        return String.format(Locale.ROOT, "%08X", RecordText.crc32(bytes));
    }
}
