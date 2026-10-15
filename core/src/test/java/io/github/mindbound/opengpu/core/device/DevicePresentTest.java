package io.github.mindbound.opengpu.core.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.RecordText;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.Record;

/**
 * {@code present()} (28:98, :105-108; plan §3.4; H3, H22): frame ids 1, 2, 3 as Lua numbers from Longs, never reused;
 * an empty {@code FRAME} for a present without a submit; {@code "busy"} at the third unconfirmed frame and
 * {@code "host render stalled"}, both keeping the open frame for a retry (28:105); {@code "no renderer"} discarding it
 * and appending its deferred frees, behind frames still in flight (28:106); the precedence no renderer, stalled, busy
 * (H3); the charge on every status, taken before it is decided (28:91, :703); and the {@code FRAME}'s totals.
 */
class DevicePresentTest {

    private static final byte[] A = segment(clear(3));
    private static final byte[] B = segment(fill(0, 0, 10, 10, 9));

    @Test
    void framesAreNumberedFrom1AsLongs() {
        DeviceRig rig = DeviceRig.tier(2);
        assertPresent(rig, 1);
        assertPresent(rig, 2);
        rig.step();
        assertPresent(rig, 3);
        Assertions.assertEquals(3, rig.snapshot().lastFrameId);
        List<Record> offered = rig.host.offered(rig.key);
        for (int i = 0; i < 3; i++) {
            Assertions.assertEquals(i + 1, ((FrameRecord) offered.get(i)).frameId);
        }
    }

    @Test
    void aPresentWithoutASubmitIsAnEmptyFrame() {
        // 28:108: legal, and M1's expand-only presents rely on it
        DeviceRig rig = DeviceRig.tier(2);
        rig.present();
        FrameRecord f = (FrameRecord) rig.host.offered(rig.key)
            .get(0);
        Assertions.assertEquals(0, f.segmentCount());
        Assertions.assertEquals(FrameTotals.EMPTY, f.totals);
        Assertions.assertEquals(1, f.epoch);
        Assertions.assertEquals(
            Collections
                .singletonList("FRAME e=1 id=1 segs=0 bytes=0 records=0 clear=0 fill=0 copy=0 set=0 crc=00000000"),
            rig.tailText());
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertEquals(1, s.unconfirmedFrames);
        Assertions.assertEquals(0, s.confirmedFrameId);
    }

    @Test
    void theThirdUnconfirmedFrameIsBusyAndKeepsTheOpenFrame() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.present();
        rig.present();
        rig.submit(A);
        rig.submit(B);
        int offered = rig.host.offered(rig.key)
            .size();
        rig.budget.clearEvents();
        assertStatus(rig, "busy");
        Assertions.assertEquals(Collections.singletonList("consume 0.062500000"), rig.budget.events());
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertEquals(Arrays.asList(A, B), s.openSegments);
        Assertions.assertEquals(2, s.unconfirmedFrames);
        Assertions.assertEquals(2, s.lastFrameId);
        Assertions.assertEquals(
            offered,
            rig.host.offered(rig.key)
                .size());
        // a step confirms both; the retry presents the open frame whole as frame 3
        rig.step();
        Assertions.assertEquals(2, rig.snapshot().confirmedFrameId);
        assertPresent(rig, 3);
        FrameRecord f = lastFrame(rig);
        Assertions.assertEquals(2, f.segmentCount());
        Assertions.assertSame(A, f.segment(0));
        Assertions.assertSame(B, f.segment(1));
    }

    @Test
    void aStalledHostKeepsTheOpenFrame() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.submit(A);
        rig.host.setStalled(true);
        rig.budget.clearEvents();
        assertStatus(rig, "host render stalled");
        Assertions.assertEquals(Collections.singletonList("consume 0.062500000"), rig.budget.events());
        Assertions.assertEquals(Collections.singletonList(A), rig.snapshot().openSegments);
        Assertions.assertTrue(
            rig.host.offered(rig.key)
                .isEmpty());
        Assertions.assertEquals(0, rig.snapshot().lastFrameId);
        rig.host.setStalled(false);
        assertPresent(rig, 1);
        Assertions.assertSame(A, lastFrame(rig).segment(0));
    }

    @Test
    void noRendererDiscardsTheOpenFrameAndAppendsItsFreesBehindTheFramesInFlight() {
        // H22: a host that becomes unavailable with frames in flight
        DeviceRig rig = DeviceRig.tier(2);
        int t = rig.create(16, 16);
        rig.present();
        int u = rig.create(8, 8);
        rig.submit(A);
        rig.free(t);
        rig.free(u);
        rig.host.setAvailable(false);
        rig.budget.clearEvents();
        assertStatus(rig, "no renderer");
        Assertions.assertEquals(Collections.singletonList("consume 0.062500000"), rig.budget.events());
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertTrue(s.openSegments.isEmpty());
        Assertions.assertEquals(FrameTotals.EMPTY, s.openTotals);
        Assertions.assertTrue(s.deferredFrees.isEmpty());
        Assertions.assertEquals(1, s.lastFrameId);
        Assertions.assertEquals(
            Arrays.asList(
                "FRAME e=1 id=1 segs=0 bytes=0 records=0 clear=0 fill=0 copy=0 set=0 crc=00000000",
                "TEX_CREATE e=1 tex=0x08010000 size=8x8 format=1",
                "TEX_FREE e=1 tex=0x08000000",
                "TEX_FREE e=1 tex=0x08010000"),
            rig.tailText());
        Assertions.assertEquals(64 + 64 + 64 + 64, s.queuedBytes);
        // the frame in flight still completes; the frame id does not advance on "no renderer"
        rig.step();
        Assertions.assertTrue(rig.snapshot().masters.isEmpty());
        rig.host.setAvailable(true);
        assertPresent(rig, 2);
    }

    @Test
    void onNoRenderHostEveryPresentIsNoRenderer() {
        DeviceRig rig = DeviceRig.noRenderer(2);
        rig.submit(A);
        for (int i = 0; i < 3; i++) {
            rig.budget.clearEvents();
            assertStatus(rig, "no renderer");
            Assertions.assertEquals(Collections.singletonList("consume 0.062500000"), rig.budget.events());
        }
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertEquals(0, s.lastFrameId);
        Assertions.assertTrue(s.openSegments.isEmpty());
        Assertions.assertTrue(s.tail.isEmpty());
        DeviceRig t3 = DeviceRig.noRenderer(3);
        assertStatus(t3, "no renderer");
        Assertions.assertEquals(Collections.singletonList("consume 0.031250000"), t3.budget.events());
    }

    @Test
    void theStatusesTakePrecedenceNoRendererThenStalledThenBusy() {
        // H3, with all three conditions true at once
        DeviceRig rig = DeviceRig.tier(2);
        rig.present();
        rig.present();
        rig.submit(A);
        rig.host.setStalled(true);
        rig.host.setAvailable(false);
        rig.budget.clearEvents();
        assertStatus(rig, "no renderer");
        // the discard emptied the open frame; open another
        rig.submit(A);
        rig.host.setAvailable(true);
        assertStatus(rig, "host render stalled");
        rig.host.setStalled(false);
        assertStatus(rig, "busy");
        Assertions.assertEquals(
            Arrays.asList("consume 0.062500000", "consume 0.015638351", "consume 0.062500000", "consume 0.062500000"),
            rig.budget.events());
        Assertions.assertEquals(Collections.singletonList(A), rig.snapshot().openSegments);
    }

    @Test
    void theFramesTotalsAreTheOpenFramesAtPresent() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.submit(A);
        rig.submit(B);
        FrameTotals open = rig.snapshot().openTotals;
        Assertions.assertEquals(new FrameTotals(A.length + B.length, 2, 64000, 100, 0, 0), open);
        rig.present();
        Assertions.assertEquals(open, lastFrame(rig).totals);
        Assertions.assertEquals(
            Collections.singletonList(
                "FRAME e=1 id=1 segs=2 bytes=36 records=2 clear=64000 fill=100 copy=0 set=0 crc="
                    + String.format(Locale.ROOT, "%08X", RecordText.crc32(A, B))),
            rig.tailText());
    }

    @Test
    void theT3ChargeIsHalfT2s() {
        DeviceRig rig = DeviceRig.tier(3);
        assertPresent(rig, 1);
        Assertions.assertEquals(Collections.singletonList("consume 0.031250000"), rig.budget.events());
    }

    @Test
    void aResetKeepsTheFrameCounterAndStampsTheNewEpoch() {
        // 28:108: never reused in the device's lifetime; 28:150: K and the counter survive a reset
        DeviceRig rig = DeviceRig.tier(2);
        assertPresent(rig, 1);
        rig.device.reset();
        assertPresent(rig, 2);
        FrameRecord f = lastFrame(rig);
        Assertions.assertEquals(2, f.epoch);
        Assertions.assertEquals(2, f.frameId);
    }

    @Test
    void theBusyBoundIsTheDevicesMaxUnconfirmedFrames() {
        // 28:685's 2 is DeviceCaps.maxUnconfirmedFrames; with 1, the second unconfirmed frame is busy
        DeviceRig rig = DeviceRig.of(
            new DeviceCaps(
                new SurfaceDesc(2, 24, 16, Formats.INDEX8),
                new FrameLimits(65536, 512, 24, 4000),
                2048,
                6,
                32,
                256,
                1));
        assertPresent(rig, 1);
        assertStatus(rig, "busy");
        rig.step();
        assertPresent(rig, 2);
        Assertions.assertEquals(1, rig.snapshot().unconfirmedFrames);
    }

    @Test
    void aNullBudgetIsRefused() {
        DeviceRig rig = DeviceRig.tier(2);
        Assertions.assertThrows(NullPointerException.class, () -> rig.device.present(null));
        Assertions.assertTrue(
            rig.host.offered(rig.key)
                .isEmpty());
        Assertions.assertEquals(0, rig.snapshot().lastFrameId);
    }

    private static void assertPresent(DeviceRig rig, long id) {
        rig.budget.clearEvents();
        CallResult r = rig.call(b -> rig.device.present(b));
        Object[] lua = r.toLua();
        Assertions.assertEquals(1, lua.length);
        Assertions.assertEquals(Long.class, lua[0].getClass());
        Assertions.assertEquals(Long.valueOf(id), lua[0]);
        Assertions.assertEquals(id, r.frameId());
    }

    private static void assertStatus(DeviceRig rig, String status) {
        Assertions.assertArrayEquals(
            new Object[] { null, status },
            rig.call(b -> rig.device.present(b))
                .toLua());
    }

    private static FrameRecord lastFrame(DeviceRig rig) {
        List<Record> offered = rig.host.offered(rig.key);
        for (int i = offered.size() - 1; i >= 0; i--) {
            if (offered.get(i)
                .kind() == Record.FRAME) {
                return (FrameRecord) offered.get(i);
            }
        }
        throw new AssertionError("no FRAME offered");
    }
}
