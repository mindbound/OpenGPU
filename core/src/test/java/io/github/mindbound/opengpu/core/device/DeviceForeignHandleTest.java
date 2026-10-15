package io.github.mindbound.opengpu.core.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.cmd.ErrorCode;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
import io.github.mindbound.opengpu.core.fixtures.device.ManualClock;
import io.github.mindbound.opengpu.core.fixtures.device.OracleRecordRenderer;
import io.github.mindbound.opengpu.core.fixtures.device.RecordingBudget;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FakeRenderHost;
import io.github.mindbound.opengpu.core.render.FrameRecord;

/**
 * Handles are device-local (28:539; 28 §11.2, 28:917; S6 G12; H19). Every device's first texture is
 * {@code 0x08000000}, so a handle passed from one card to another is accepted there exactly when that card happens to
 * have the same handle live, and then it names that card's own texture. This is documented, not detected: telling the
 * two apart would need per-device generation seeds, which 28 does not propose. Two devices, (1, 1) and (1, 2), on one
 * host.
 */
class DeviceForeignHandleTest {

    private static final int T = 0x08000000;

    private final FakeRenderHost host = new FakeRenderHost(new OracleRecordRenderer());
    private final DeviceKey ka = new DeviceKey(1, 1);
    private final DeviceKey kb = new DeviceKey(1, 2);
    private final Device a = Device
        .create(ka, DeviceCaps.forTier(2), host, Runnable::run, FrameListener.NONE, new ManualClock());
    private final Device b = Device
        .create(kb, DeviceCaps.forTier(2), host, Runnable::run, FrameListener.NONE, new ManualClock());
    private final RecordingBudget budget = new RecordingBudget();
    private final byte[] blitT = segment(blit(T, 0, 0, 4, 4, 0, 0, 0));

    @Test
    void aHandleLiveOnlyOnAnotherDeviceIsNotLiveHere() {
        Assertions.assertEquals(
            T,
            DeviceOrderingTest.ok(a.createTexture(budget, 4, 4, null))
                .handle());
        CallResult r = b.submit(budget, blitT);
        Assertions.assertEquals("8: BLIT: handle 0x08000000 is not live", r.message());
        Assertions.assertEquals(ErrorCode.STALE_HANDLE, r.streamError().code);
        Assertions.assertEquals(8, r.streamError().offset);
        Assertions.assertTrue(b.snapshot().openSegments.isEmpty());
        // on its own device the same segment is accepted
        Assertions.assertEquals(CallResult.TRUE, a.submit(budget, blitT));
    }

    @Test
    void theSameHandleLiveOnBothNamesEachDevicesOwnTexture() {
        byte[] pa = DeviceOrderingTest.pattern(16, 1);
        byte[] pb = DeviceOrderingTest.pattern(16, 2);
        Assertions.assertEquals(
            T,
            DeviceOrderingTest.ok(a.createTexture(budget, 4, 4, null))
                .handle());
        DeviceOrderingTest.ok(a.updateTexture(budget, T, 0, 0, 4, 4, pa));
        Assertions.assertEquals(
            T,
            DeviceOrderingTest.ok(b.createTexture(budget, 4, 4, null))
                .handle());
        DeviceOrderingTest.ok(b.updateTexture(budget, T, 0, 0, 4, 4, pb));
        // b accepts the segment a program wrote for a, and it draws b's texels: nothing can tell (28:539)
        Assertions.assertEquals(CallResult.TRUE, b.submit(budget, blitT));
        Assertions.assertEquals(
            1L,
            DeviceOrderingTest.ok(b.present(budget))
                .frameId());
        host.step();
        OracleRecordRenderer oracles = (OracleRecordRenderer) host.renderer();
        TierEOracle onB = oracles.oracle(kb);
        DeviceOrderingTest.assertRegion(onB, 0, 0, 4, 4, pb);
        Assertions.assertArrayEquals(
            onB.pixels(),
            b.snapshot()
                .confirmedPixels());
        Assertions.assertArrayEquals(
            new byte[320 * 200],
            oracles.oracle(ka)
                .pixels(),
            "a drew nothing");
        // and on a, the same array draws a's
        Assertions.assertEquals(CallResult.TRUE, a.submit(budget, blitT));
        Assertions.assertEquals(
            1L,
            DeviceOrderingTest.ok(a.present(budget))
                .frameId());
        host.step();
        DeviceOrderingTest.assertRegion(oracles.oracle(ka), 0, 0, 4, 4, pa);
        Assertions.assertSame(
            blitT,
            ((FrameRecord) host.offered(ka)
                .get(2)).segment(0));
        Assertions.assertSame(
            blitT,
            ((FrameRecord) host.offered(kb)
                .get(2)).segment(0));
    }

    @Test
    void theHandlesDivergeOnlyAsEachDevicesQueueDoes() {
        Assertions.assertEquals(
            T,
            DeviceOrderingTest.ok(a.createTexture(budget, 4, 4, null))
                .handle());
        Assertions.assertEquals(
            T,
            DeviceOrderingTest.ok(b.createTexture(budget, 4, 4, null))
                .handle());
        DeviceOrderingTest.ok(a.free(T));
        Assertions.assertEquals(
            0x08010000,
            DeviceOrderingTest.ok(a.createTexture(budget, 4, 4, null))
                .handle());
        byte[] blitNext = segment(blit(0x08010000, 0, 0, 4, 4, 0, 0, 0));
        Assertions.assertEquals(CallResult.TRUE, a.submit(budget, blitNext));
        Assertions.assertEquals(
            "8: BLIT: handle 0x08010000 is not live",
            b.submit(budget, blitNext)
                .message());
        Assertions.assertEquals(CallResult.TRUE, b.submit(budget, blitT));
        Assertions.assertEquals(
            "8: BLIT: handle 0x08000000 is not live",
            a.submit(budget, blitT)
                .message());
    }
}
