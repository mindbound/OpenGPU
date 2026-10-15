package io.github.mindbound.opengpu.core.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.text;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;

/**
 * {@code readPixels(x, y, w, h)} (28:99, :342; 24:129; plan §3.4, amendment B): a region of the confirmed frame K in
 * the 28 §5.7 layout with K's id, not the newest presented frame's; rows of a sub-rectangle at T2 and T3; the zeros of
 * frame 0 on a {@code NoRenderHost} (RM:65); charged after both checks (H15); and a fresh array every call, never K's
 * own nor the host's delivered one (B12; amendment B's copy).
 */
class DeviceReadPixelsTest {

    /** A 24×16 index8 surface whose whole image fits one readback of 384 B. */
    private static DeviceCaps wholeSurfaceCaps() {
        return new DeviceCaps(
            new SurfaceDesc(2, 24, 16, Formats.INDEX8),
            new FrameLimits(65536, 512, 24, 4000),
            2048,
            6,
            32,
            384,
            2);
    }

    @Test
    void theRegionIsKsAfterTheFold() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.submit(segment(clear(5), fill(10, 20, 100, 50, 196)));
        rig.present();
        // not yet read back: K is still the cleared frame 0
        assertRead(rig, 8, 19, 4, 3, 0, new byte[12]);
        rig.step();
        assertRead(rig, 8, 19, 4, 3, 1, bytes(5, 5, 5, 5, 5, 5, 196, 196, 5, 5, 196, 196));
        // the fill's far corner: (109, 69) is filled, (110, 70) is not
        assertRead(rig, 108, 68, 3, 3, 1, bytes(196, 196, 5, 196, 196, 5, 5, 5, 5));
    }

    @Test
    void anRgb565RegionIsLittleEndianU16Rows() {
        // 28 §9.4's T3 operands: clear(0xFF000000) is 0x0000, fill colour 0xFFFF8000 is 0xFC00
        DeviceRig rig = DeviceRig.tier(3);
        rig.submit(segment(clear(0xFF000000), fill(10, 20, 100, 50, 0xFFFF8000)));
        rig.present();
        rig.step();
        assertRead(rig, 9, 20, 2, 2, 1, bytes(0x00, 0x00, 0x00, 0xFC, 0x00, 0x00, 0x00, 0xFC));
        assertRead(rig, 109, 69, 2, 1, 1, bytes(0x00, 0xFC, 0x00, 0x00));
    }

    @Test
    void theReadIsTheNewestConfirmedFrameNotTheNewestPresented() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.submit(segment(clear(5)));
        rig.present();
        rig.step();
        rig.submit(segment(clear(6)));
        rig.present();
        assertRead(rig, 0, 0, 2, 1, 1, bytes(5, 5));
        rig.step();
        assertRead(rig, 0, 0, 2, 1, 2, bytes(6, 6));
        // the last pixel of the surface
        rig.submit(segment(fill(310, 190, 20, 20, 77)));
        rig.present();
        rig.step();
        assertRead(rig, 318, 199, 2, 1, 3, bytes(77, 77));
        assertRead(rig, 309, 189, 1, 1, 3, bytes(6));
    }

    @Test
    void theWholeScreenInBandsIsTheHostsImage() {
        for (int tier = 2; tier <= 3; tier++) {
            DeviceRig rig = DeviceRig.tier(tier);
            boolean t3 = tier == 3;
            int t = rig.create(16, 16);
            byte[] sprite = new byte[t3 ? 512 : 256];
            for (int i = 0; i < sprite.length; i++) {
                sprite[i] = (byte) (i * 7 + 1);
            }
            rig.update(t, 0, 0, 16, 16, sprite);
            rig.submit(
                segment(
                    clear(t3 ? 0xFF102030 : 3),
                    fill(10, 20, 100, 50, t3 ? 0xFFFF8000 : 196),
                    blit(t, 0, 0, 16, 16, 150, 40, 1),
                    text(8, 180, t3 ? 0xFFFFFFFF : 255, t3 ? 0xFF000000 : 0, 1, 0, "Hi")));
            rig.present();
            rig.step();
            Assertions.assertArrayEquals(
                rig.hostOracle()
                    .pixels(),
                rig.screen(),
                "tier " + tier);
            Assertions.assertArrayEquals(
                rig.hostOracle()
                    .pixels(),
                rig.snapshot()
                    .confirmedPixels(),
                "tier " + tier);
        }
    }

    @Test
    void aNoRenderHostReadsTheZerosOfFrame0() {
        // RM:65; 28:204
        DeviceRig rig = DeviceRig.noRenderer(2);
        rig.create(16, 16);
        rig.submit(segment(clear(9)));
        rig.call(b -> rig.device.present(b));
        rig.step();
        assertRead(rig, 0, 0, 256, 128, 0, new byte[32768]);
        Assertions.assertArrayEquals(new byte[64000], rig.screen());
        DeviceRig t3 = DeviceRig.noRenderer(3);
        assertRead(t3, 600, 390, 40, 10, 0, new byte[800]);
    }

    @Test
    void eachReadIsAFreshArrayNeitherKsNorTheHosts() {
        // B12 and amendment B: a whole-surface read never returns K's array, and K is a copy of the delivered one
        DeviceRig rig = DeviceRig.of(wholeSurfaceCaps());
        rig.submit(segment(clear(9)));
        rig.present();
        rig.step();
        byte[] nines = new byte[384];
        Arrays.fill(nines, (byte) 9);
        rig.budget.clearEvents();
        CallResult first = rig.device.readPixels(rig.budget, 0, 0, 24, 16);
        CallResult second = rig.device.readPixels(rig.budget, 0, 0, 24, 16);
        Assertions.assertEquals(Arrays.asList("consume 0.001464844", "consume 0.001464844"), rig.budget.events());
        Assertions.assertArrayEquals(nines, first.data());
        Assertions.assertNotSame(first.data(), second.data());
        byte[] delivered = rig.host.readbacks(rig.key)
            .get(0).pixels;
        Assertions.assertNotSame(delivered, first.data());
        Assertions.assertNotSame(delivered, second.data());
        // writing into a returned array or into the host's delivered one changes nothing the device holds
        first.data()[0] = 1;
        delivered[1] = 2;
        Assertions.assertArrayEquals(nines, second.data());
        Assertions.assertArrayEquals(
            nines,
            rig.device.readPixels(rig.budget, 0, 0, 24, 16)
                .data());
        Assertions.assertArrayEquals(
            nines,
            rig.snapshot()
                .confirmedPixels());
    }

    @Test
    void theChargeFollowsBothChecks() {
        // H15: an invalid or over-cap region is uncharged; a valid one is charged w·h·bpp / 256 KB
        DeviceRig rig = DeviceRig.of(
            new DeviceCaps(
                new SurfaceDesc(3, 16, 12, Formats.RGB565),
                new FrameLimits(65536, 512, 24, 4000),
                4096,
                6,
                32,
                256,
                2));
        Assertions.assertEquals(
            "region exceeds 256 bytes per call",
            rig.device.readPixels(rig.budget, 0, 0, 16, 9)
                .message());
        Assertions.assertEquals(
            "invalid region",
            rig.device.readPixels(rig.budget, 0, 0, 17, 9)
                .message());
        Assertions.assertEquals(Collections.emptyList(), rig.budget.events());
        // 16 × 8 × 2 = 256 B, exactly the cap
        CallResult r = rig.device.readPixels(rig.budget, 0, 4, 16, 8);
        Assertions.assertEquals(256, r.data().length);
        Assertions.assertEquals(Collections.singletonList("consume 0.000976563"), rig.budget.events());
    }

    @Test
    void aReadChangesNothing() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(16, 16);
        rig.present();
        String before = DeviceCallbackTableTest.state(rig.snapshot());
        List<String> offered = rig.offeredText();
        rig.device.readPixels(rig.budget, 0, 0, 100, 100);
        rig.device.readPixels(rig.budget, 0, 0, 0, 100);
        Assertions.assertEquals(before, DeviceCallbackTableTest.state(rig.snapshot()));
        Assertions.assertEquals(offered, rig.offeredText());
    }

    @Test
    void aNullBudgetIsRefused() {
        DeviceRig rig = DeviceRig.tier(2);
        Assertions.assertThrows(NullPointerException.class, () -> rig.device.readPixels(null, 0, 0, 1, 1));
    }

    private static void assertRead(DeviceRig rig, int x, int y, int w, int h, long frame, byte[] expected) {
        CallResult r = rig.call(b -> rig.device.readPixels(b, x, y, w, h));
        Assertions.assertTrue(r.isOk(), r.toString());
        Assertions.assertEquals(frame, r.frameId());
        Assertions.assertArrayEquals(expected, r.data());
        Object[] lua = r.toLua();
        Assertions.assertSame(r.data(), lua[0]);
        Assertions.assertEquals(Long.valueOf(frame), lua[1]);
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
