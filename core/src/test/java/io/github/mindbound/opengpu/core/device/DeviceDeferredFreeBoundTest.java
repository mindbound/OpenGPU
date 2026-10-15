package io.github.mindbound.opengpu.core.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.cmd.HandleTypes;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.RecordText;
import io.github.mindbound.opengpu.core.render.Record;

/**
 * The owner's decision Q-S7-1, "Count pending frees" (2026-10-15; amendment A, H23; critiques A1 and B1): while a
 * frame is open, the frees deferred to it (O4, 28:135) still count against {@code createTexture}'s caps until they are
 * appended, so a create/free loop inside one open frame cannot grow the masters, the host's textures or the deferred
 * {@code TEX_FREE}s without bound. Its {@code "busy"} follows {@code "not enough video memory"}, is uncharged and
 * offers nothing; {@code free} itself is never refused (28:140) and its VRAM is still credited at once. The bound is
 * lifted when the frees are appended, behind the {@code FRAME} at {@code present} or at the {@code "no renderer"}
 * discard, and by a reset. The masters then never pass {@code maxTextures} entries or {@code vramBytes} bytes (I11,
 * restoring 28:577), and the queued bytes stay within vramBytes + 64 · maxTextures (I10).
 */
class DeviceDeferredFreeBoundTest {

    private static final byte[] NO_OP = segment();

    /** B1: T2, one open frame, 300 × (create 1×1, free): creates 1-256 succeed and create 257 is "busy". */
    @Test
    void createFreePairsInOneOpenFrameStopAtMaxTextures() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        rig.submit(NO_OP);
        for (int i = 1; i <= 300; i++) {
            if (i <= 256) {
                int h = rig.create(1, 1);
                Assertions.assertEquals(Handles.pack(HandleTypes.TEXTURE, i - 1, 0), h, "create " + i);
                rig.free(h);
            } else {
                DeviceTailBoundTest.assertCreateFails(rig, 1, 1, "busy");
            }
            if (i % 64 == 0) {
                DeviceOrderingTest.check(rig, history);
            }
        }
        DeviceSnapshot s = DeviceOrderingTest.check(rig, history);
        Assertions.assertEquals(256, s.deferredCount);
        Assertions.assertEquals(256, s.deferredBytes);
        Assertions.assertTrue(s.live.isEmpty());
        Assertions.assertEquals(0, s.vramUsed, "free credited the VRAM at once (O4)");
        Assertions.assertEquals(256, s.masters.size(), "each create folded at once; none of the frees has");
        // the host holds them all too, but no more: every TEX_CREATE was offered, no TEX_FREE yet
        rig.step();
        Assertions.assertEquals(
            256,
            rig.hostOracle()
                .textureCount());
        // "too many textures" keeps its meaning, the live count, which is 0: a create of any size is "busy"
        DeviceTailBoundTest.assertCreateFails(rig, 16, 16, "busy");
        Assertions.assertEquals(1L, rig.present());
        s = DeviceOrderingTest.check(rig, history);
        Assertions.assertEquals(1 + 256, s.tail.size());
        Assertions.assertEquals(
            Record.FRAME,
            s.tail.get(0)
                .kind());
        for (int i = 1; i <= 256; i++) {
            Assertions.assertEquals(
                String.format(Locale.ROOT, "TEX_FREE e=1 tex=0x%08X", Handles.pack(1, i - 1, 0)),
                RecordText.of(s.tail.get(i)));
        }
        Assertions.assertEquals(256 * 64, s.queuedBytes);
        Assertions.assertTrue(s.queuedBytes <= 1048576 + 16384);
        Assertions.assertEquals(0, s.deferredCount);
        Assertions.assertEquals(0, s.deferredBytes);
        // appended, the frees no longer count against createTexture; the new record queues behind them
        int next = rig.create(1, 1);
        Assertions.assertEquals(Handles.pack(1, 256, 0), next);
        rig.step();
        s = DeviceOrderingTest.check(rig, history);
        Assertions.assertTrue(s.tail.isEmpty());
        Assertions.assertEquals(Collections.singleton(next), s.masters.keySet());
        Assertions.assertEquals(
            1,
            rig.hostOracle()
                .textureCount());
    }

    /** B1's 1 024×1 024 variant: create, free, create is "busy" by the bytes, and the masters never pass 1 MB. */
    @Test
    void aWholeVramTextureFreedInAnOpenFrameStillCountsItsBytes() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        rig.submit(NO_OP);
        int t = rig.create(1024, 1024);
        rig.free(t);
        DeviceSnapshot s = DeviceOrderingTest.check(rig, history);
        Assertions.assertEquals(0, s.vramUsed);
        Assertions.assertEquals(1048576, s.deferredBytes);
        DeviceTailBoundTest.assertCreateFails(rig, 1024, 1024, "busy");
        DeviceTailBoundTest.assertCreateFails(rig, 1, 1, "busy");
        Assertions.assertEquals(1048576, masterBytes(rig.snapshot()));
        // present appends the free behind FRAME 1; the 1 MB create then waits for the tail (H2), the small one queues
        Assertions.assertEquals(1L, rig.present());
        DeviceTailBoundTest.assertCreateFails(rig, 1024, 1024, "busy");
        int small = rig.create(1, 1);
        Assertions.assertEquals(1048576, masterBytes(DeviceOrderingTest.check(rig, history)));
        rig.free(small);
        rig.step();
        Assertions.assertEquals(0, masterBytes(DeviceOrderingTest.check(rig, history)));
        // a step later the bound is lifted entirely
        int again = rig.create(1024, 1024);
        Assertions.assertEquals(Handles.pack(1, 2, 0), again);
        s = DeviceOrderingTest.check(rig, history);
        Assertions.assertEquals(1048576, masterBytes(s));
        Assertions.assertEquals(1048576, s.vramUsed);
    }

    /** The bytes count while the count has room: a texture freed in the open frame keeps its 512 KB pending. */
    @Test
    void theDeferredBytesCountWhileTheCountHasRoom() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        int keep = rig.create(1024, 512);
        rig.submit(NO_OP);
        int t = rig.create(1024, 512);
        Assertions.assertArrayEquals(
            new Object[] { null, "not enough video memory" },
            rig.call(b -> rig.device.createTexture(b, 1, 1, null))
                .toLua(),
            "VRAM full: the VRAM message comes first");
        rig.free(t);
        // VRAM has 512 KB free again, but they are pending
        DeviceTailBoundTest.assertCreateFails(rig, 1, 1, "busy");
        rig.free(keep);
        // and once both are pending nothing fits
        DeviceTailBoundTest.assertCreateFails(rig, 1, 1, "busy");
        DeviceSnapshot s = DeviceOrderingTest.check(rig, history);
        Assertions.assertEquals(2, s.deferredCount);
        Assertions.assertEquals(1048576, s.deferredBytes);
        Assertions.assertEquals(0, s.vramUsed);
        Assertions.assertEquals(1048576, masterBytes(s));
        rig.present();
        rig.step();
        Assertions.assertEquals(0, masterBytes(DeviceOrderingTest.check(rig, history)));
        rig.create(1024, 1024);
    }

    /** On NoRenderHost the "no renderer" discard appends the pending frees, which fold at once (28:106, O6). */
    @Test
    void theNoRendererDiscardLiftsTheBound() {
        DeviceRig rig = DeviceRig.noRenderer(2);
        rig.submit(NO_OP);
        for (int i = 0; i < 256; i++) {
            rig.free(rig.create(1, 1));
        }
        DeviceTailBoundTest.assertCreateFails(rig, 1, 1, "busy");
        DeviceSnapshot s = Invariants.check(rig.snapshot(), rig.device.caps());
        Assertions.assertEquals(256, s.masters.size());
        Assertions.assertArrayEquals(
            new Object[] { null, "no renderer" },
            rig.call(b -> rig.device.present(b))
                .toLua());
        s = Invariants.check(rig.snapshot(), rig.device.caps());
        Assertions.assertTrue(s.masters.isEmpty());
        Assertions.assertTrue(s.tail.isEmpty());
        Assertions.assertEquals(0, s.deferredCount);
        Assertions.assertTrue(s.openSegments.isEmpty());
        Assertions.assertEquals(Handles.pack(1, 256, 0), rig.create(1, 1));
    }

    /** On a fake host turned unavailable the discard appends the frees too, here behind a frame in flight (H22). */
    @Test
    void theNoRendererDiscardOnAFakeHostAppendsTheFrees() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        rig.present();
        rig.submit(NO_OP);
        int t = rig.create(8, 8);
        rig.free(t);
        rig.host.setAvailable(false);
        Assertions.assertArrayEquals(
            new Object[] { null, "no renderer" },
            rig.call(b -> rig.device.present(b))
                .toLua());
        DeviceSnapshot s = DeviceOrderingTest.check(rig, history);
        List<String> tail = rig.tailText();
        Assertions.assertEquals(3, tail.size(), tail::toString);
        Assertions.assertEquals("TEX_CREATE e=1 tex=0x08000000 size=8x8 format=1", tail.get(1));
        Assertions.assertEquals("TEX_FREE e=1 tex=0x08000000", tail.get(2));
        Assertions.assertEquals(0, s.deferredCount);
        rig.host.setAvailable(true);
        rig.step();
        Assertions.assertTrue(DeviceOrderingTest.check(rig, history).masters.isEmpty());
    }

    /** A reset drops the pending frees with the open frame, and the count with them (28:150). */
    @Test
    void aResetLiftsTheBound() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        rig.submit(NO_OP);
        for (int i = 0; i < 256; i++) {
            rig.free(rig.create(1, 1));
        }
        DeviceTailBoundTest.assertCreateFails(rig, 1, 1, "busy");
        rig.device.reset();
        DeviceSnapshot s = DeviceOrderingTest.check(rig, history);
        Assertions.assertEquals(0, s.deferredCount);
        Assertions.assertEquals(0, s.deferredBytes);
        Assertions.assertTrue(s.masters.isEmpty());
        rig.create(1024, 1024);
        rig.step();
        DeviceOrderingTest.check(rig, history);
        Assertions.assertEquals(
            1,
            rig.hostOracle()
                .textureCount());
    }

    private static long masterBytes(DeviceSnapshot s) {
        long bytes = 0;
        for (MasterTexture m : s.masters.values()) {
            bytes += m.texels().length;
        }
        return bytes;
    }
}
