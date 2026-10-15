package io.github.mindbound.opengpu.core.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.cmd.ErrorCode;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.RecordText;

/**
 * {@code free(tex)} (28:102, :345, §6.4) and O4 (28:135) in each state of the device: the handle invalid and its VRAM
 * credited at once, its slot queued with a new generation (H21); its {@code TEX_FREE} appended now with no open frame
 * (folding at once on an empty tail, O6), right after the open frame's {@code FRAME} at present, at the open frame's
 * discard on {@code "no renderer"}, and dropped by a reset (28:150); uncharged (28:707) and never {@code "busy"}
 * (28:140), even with the queued bytes at the cap; and, under the owner's H23 (amendment A), a free deferred in the
 * open
 * frame still making the next large create {@code "busy"} although its VRAM was credited.
 */
class DeviceFreeTest {

    private static final int T = 0x08000000;
    private static final byte[] NO_OP = segment();

    @Test
    void withNoOpenFrameTheFreeIsAppendedAndFoldsAtOnce() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(16, 16);
        Assertions.assertEquals(CallResult.TRUE, rig.device.free(T));
        Assertions.assertEquals(
            Arrays.asList("TEX_CREATE e=1 tex=0x08000000 size=16x16 format=1", "TEX_FREE e=1 tex=0x08000000"),
            rig.offeredText());
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertTrue(s.tail.isEmpty());
        Assertions.assertTrue(s.masters.isEmpty());
        Assertions.assertTrue(s.live.isEmpty());
        Assertions.assertEquals(0, s.vramUsed);
        Assertions.assertTrue(s.deferredFrees.isEmpty());
        rig.step();
        Assertions.assertFalse(
            rig.hostOracle()
                .hasTexture(T));
    }

    @Test
    void withFramesInFlightTheFreeQueuesBehindThem() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(16, 16);
        rig.present();
        rig.free(T);
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertEquals(2, s.tail.size());
        Assertions.assertEquals("TEX_FREE e=1 tex=0x08000000", RecordText.of(s.tail.get(1)));
        Assertions.assertEquals(64, s.queuedBytes);
        Assertions.assertTrue(s.masters.containsKey(T), "the master lags until FRAME 1 folds");
        Assertions.assertEquals(0, s.vramUsed);
        Assertions.assertTrue(s.live.isEmpty());
        rig.step();
        s = rig.snapshot();
        Assertions.assertTrue(s.masters.isEmpty());
        Assertions.assertTrue(s.tail.isEmpty());
        Assertions.assertEquals(0, s.queuedBytes);
    }

    @Test
    void withAnOpenFrameTheFreeFollowsItsFrame() {
        // O4: a validated frame never references a freed resource
        DeviceRig rig = DeviceRig.tier(2);
        int a = rig.create(16, 16);
        int b = rig.create(8, 8);
        byte[] blitA = segment(blit(a, 0, 0, 16, 16, 0, 0, 0));
        rig.submit(blitA);
        rig.free(b);
        rig.free(a);
        // nothing offered yet; both frees pending in free order
        Assertions.assertEquals(
            2,
            rig.offeredText()
                .size());
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertEquals(Arrays.asList(b, a), s.deferredFrees);
        Assertions.assertEquals(2, s.deferredCount);
        Assertions.assertEquals(256 + 64, s.deferredBytes);
        Assertions.assertEquals(0, s.vramUsed);
        Assertions.assertTrue(s.masters.containsKey(a));
        Assertions.assertTrue(s.masters.containsKey(b));
        Assertions.assertEquals(1, rig.present());
        List<String> offered = rig.offeredText();
        Assertions.assertEquals(5, offered.size());
        Assertions.assertTrue(
            offered.get(2)
                .startsWith("FRAME e=1 id=1 segs=1 bytes=27 "),
            offered.get(2));
        Assertions.assertEquals("TEX_FREE e=1 tex=0x08010000", offered.get(3));
        Assertions.assertEquals("TEX_FREE e=1 tex=0x08000000", offered.get(4));
        Assertions.assertEquals(offered.subList(2, 5), rig.tailText());
        Assertions.assertEquals(128, rig.snapshot().queuedBytes);
        Assertions.assertTrue(rig.snapshot().deferredFrees.isEmpty());
        // the host draws the frame with a, then deletes both
        rig.step();
        Assertions.assertFalse(
            rig.hostOracle()
                .hasTexture(a));
        Assertions.assertTrue(rig.snapshot().masters.isEmpty());
    }

    @Test
    void theNoRendererDiscardAppendsThePendingFrees() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(16, 16);
        rig.submit(NO_OP);
        rig.free(T);
        rig.host.setAvailable(false);
        Assertions.assertArrayEquals(
            new Object[] { null, "no renderer" },
            rig.call(b -> rig.device.present(b))
                .toLua());
        Assertions.assertEquals(
            Arrays.asList("TEX_CREATE e=1 tex=0x08000000 size=16x16 format=1", "TEX_FREE e=1 tex=0x08000000"),
            rig.offeredText());
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertTrue(s.openSegments.isEmpty());
        Assertions.assertTrue(s.deferredFrees.isEmpty());
        Assertions.assertTrue(s.masters.isEmpty());
        Assertions.assertEquals(0, s.lastFrameId);
    }

    @Test
    void onNoRenderHostTheDiscardFoldsThePendingFreesAtOnce() {
        DeviceRig rig = DeviceRig.noRenderer(2);
        rig.create(16, 16);
        rig.submit(NO_OP);
        rig.free(T);
        Assertions.assertEquals(1, rig.snapshot().masters.size());
        Assertions.assertArrayEquals(
            new Object[] { null, "no renderer" },
            rig.call(b -> rig.device.present(b))
                .toLua());
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertTrue(s.masters.isEmpty());
        Assertions.assertTrue(s.deferredFrees.isEmpty());
        Assertions.assertTrue(s.tail.isEmpty());
    }

    @Test
    void aResetDropsThePendingFreesUnoffered() {
        // 28:150: the open frame with its deferred frees is discarded
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(16, 16);
        rig.submit(NO_OP);
        rig.free(T);
        rig.device.reset();
        List<String> offered = rig.offeredText();
        Assertions.assertEquals(2, offered.size());
        Assertions.assertTrue(
            offered.get(1)
                .startsWith("RESET e=2 "),
            offered.get(1));
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertTrue(s.deferredFrees.isEmpty());
        Assertions.assertEquals(0, s.deferredBytes);
        Assertions.assertTrue(s.masters.isEmpty());
    }

    @Test
    void theVramIsCreditedAtOnceButTheNextLargeCreateIsBusy() {
        // O4 and H23 together (amendment A)
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(1024, 1024);
        rig.submit(NO_OP);
        rig.budget.clearEvents();
        Assertions.assertEquals(CallResult.TRUE, rig.device.free(T));
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertEquals(0, s.vramUsed);
        Assertions.assertEquals(1048576, s.deferredBytes);
        Assertions.assertTrue(s.live.isEmpty());
        // the free itself was never busy; the create that would pass the cap with it is
        DeviceCreateTextureTest.assertFails(rig, 1024, 1024, null, "busy");
        DeviceCreateTextureTest.assertFails(rig, 1, 1, null, "busy");
        Assertions.assertEquals(Collections.emptyList(), rig.budget.events());
        rig.present();
        Assertions.assertEquals(0x08010000, rig.create(1, 1));
    }

    @Test
    void theHandleIsInvalidForValidationAtOnce() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(16, 16);
        rig.free(T);
        CallResult r = rig.call(b -> rig.device.submit(b, segment(blit(T, 0, 0, 16, 16, 0, 0, 0))));
        Assertions.assertEquals("8: BLIT: handle 0x08000000 is not live", r.message());
        Assertions.assertEquals(ErrorCode.STALE_HANDLE, r.streamError().code);
        // the same with a frame open: the frame already holds a's blit, the next segment may not
        DeviceRig open = DeviceRig.tier(2);
        open.create(16, 16);
        open.submit(segment(blit(T, 0, 0, 16, 16, 0, 0, 0)));
        open.free(T);
        Assertions.assertEquals(
            "8: BLIT: handle 0x08000000 is not live",
            open.call(b -> open.device.submit(b, segment(blit(T, 0, 0, 1, 1, 0, 0, 0))))
                .message());
        Assertions.assertEquals(1, open.snapshot().openSegments.size());
    }

    @Test
    void theSlotIsQueuedAtTheTailWithItsNextGeneration() {
        // H21: reusable at once, after the 2 047 slots ahead of it
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(1, 1);
        rig.submit(NO_OP);
        rig.free(T);
        DeviceSnapshot s = rig.snapshot();
        int[] free = s.freeSlots();
        Assertions.assertEquals(2048, free.length);
        Assertions.assertEquals(1, free[0]);
        Assertions.assertEquals(0, free[2047]);
        Assertions.assertEquals(1, s.generations()[0]);
        Assertions.assertEquals(0x08010000, rig.create(1, 1));
    }

    @Test
    void freeIsNeverBusyEvenWithTheQueuedBytesAtTheCap() {
        // 28:140: every TEX_FREE needs a create that was counted; with H23 the excess is at most 64 · maxTextures
        DeviceRig rig = DeviceRig.tier(2);
        rig.present();
        int a = rig.create(1024, 976);
        int b = rig.create(128, 383);
        Assertions.assertEquals(1048576, rig.snapshot().queuedBytes);
        DeviceCreateTextureTest.assertFails(rig, 1, 1, null, "busy");
        rig.budget.clearEvents();
        Assertions.assertEquals(CallResult.TRUE, rig.device.free(a));
        Assertions.assertEquals(CallResult.TRUE, rig.device.free(b));
        Assertions.assertEquals(Collections.emptyList(), rig.budget.events());
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertEquals(1048576 + 128, s.queuedBytes);
        Assertions.assertTrue(s.queuedBytes <= 1048576 + 64 * 256);
        Assertions.assertEquals(0, s.vramUsed);
        rig.step();
        Assertions.assertEquals(0, rig.snapshot().queuedBytes);
    }

    @Test
    void theHandleMessagesAreThoseOfUpdateTexture() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(16, 16);
        String before = DeviceCallbackTableTest.state(rig.snapshot());
        List<String> offered = rig.offeredText();
        Assertions.assertEquals(
            "invalid handle 0x00000000",
            rig.device.free(0)
                .message());
        Assertions.assertEquals(
            "invalid handle 0x80000000",
            rig.device.free(Integer.MIN_VALUE)
                .message());
        Assertions.assertEquals(
            "handle 0x7FFFFFFF has an unknown type",
            rig.device.free(Integer.MAX_VALUE)
                .message());
        Assertions.assertEquals(
            "handle 0x30000000 is a kernel, expected a texture",
            rig.device.free(0x30000000)
                .message());
        Assertions.assertEquals(
            "handle 0x08000001 is not live",
            rig.device.free(0x08000001)
                .message());
        Assertions.assertEquals(before, DeviceCallbackTableTest.state(rig.snapshot()));
        Assertions.assertEquals(offered, rig.offeredText());
        rig.free(T);
        Assertions.assertEquals(
            "handle 0x08000000 is not live",
            rig.device.free(T)
                .message());
    }

    @Test
    void aFrameOpenedAfterTheFreeDoesNotDeferIt() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(16, 16);
        rig.free(T);
        rig.submit(NO_OP);
        Assertions.assertTrue(rig.snapshot().deferredFrees.isEmpty());
        Assertions.assertEquals(1, rig.present());
        Assertions.assertEquals(1, rig.snapshot().tail.size());
    }
}
