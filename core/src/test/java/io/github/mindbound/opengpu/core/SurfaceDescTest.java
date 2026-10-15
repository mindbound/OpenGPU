package io.github.mindbound.opengpu.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.cmd.Formats;

/** 28 §2.4's {@code SurfaceDesc}: M0's two tiers (28 §8.1), the constructor's bounds and the 28 §5.7 sizes. */
class SurfaceDescTest {

    @Test
    void theTiersAreT2AndT3() {
        SurfaceDesc t2 = SurfaceDesc.forTier(2);
        Assertions.assertEquals(2, t2.tier);
        Assertions.assertEquals(320, t2.width);
        Assertions.assertEquals(200, t2.height);
        Assertions.assertEquals(Formats.INDEX8, t2.format);
        SurfaceDesc t3 = SurfaceDesc.forTier(3);
        Assertions.assertEquals(3, t3.tier);
        Assertions.assertEquals(640, t3.width);
        Assertions.assertEquals(400, t3.height);
        Assertions.assertEquals(Formats.RGB565, t3.format);
        // T1 joins in M1; the codec never takes a tier (28 §1.1)
        for (int tier : new int[] { -1, 0, 1, 4 }) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> SurfaceDesc.forTier(tier), "tier " + tier);
        }
    }

    @Test
    void theSizesFollowTheByteLayout() {
        // 28 §5.7: index8 1 B per pixel, rgb565 2 B
        SurfaceDesc t2 = SurfaceDesc.forTier(2);
        Assertions.assertEquals(1, t2.bytesPerPixel());
        Assertions.assertEquals(64000, t2.pixelCount());
        Assertions.assertEquals(64000, t2.byteSize());
        SurfaceDesc t3 = SurfaceDesc.forTier(3);
        Assertions.assertEquals(2, t3.bytesPerPixel());
        Assertions.assertEquals(256000, t3.pixelCount());
        Assertions.assertEquals(512000, t3.byteSize());
        SurfaceDesc largest = new SurfaceDesc(0, SurfaceDesc.MAX_SIDE, SurfaceDesc.MAX_SIDE, Formats.RGB565);
        Assertions.assertEquals(2L * SurfaceDesc.MAX_SIDE * SurfaceDesc.MAX_SIDE, largest.byteSize());
    }

    @Test
    void theConstructorChecksEveryField() {
        Assertions.assertDoesNotThrow(() -> new SurfaceDesc(0, 1, 1, Formats.INDEX8));
        Assertions.assertDoesNotThrow(() -> new SurfaceDesc(3, 1, SurfaceDesc.MAX_SIDE, Formats.RGB565));
        int[][] bad = { { -1, 4, 4, Formats.INDEX8 }, { 4, 4, 4, Formats.INDEX8 }, { 0, 0, 4, Formats.INDEX8 },
            { 0, 4, 0, Formats.INDEX8 }, { 0, SurfaceDesc.MAX_SIDE + 1, 4, Formats.INDEX8 },
            { 0, 4, SurfaceDesc.MAX_SIDE + 1, Formats.INDEX8 }, { 0, -4, 4, Formats.INDEX8 },
            { 0, 4, 4, Formats.INVALID }, { 0, 4, 4, 3 }, { 0, 4, 4, -1 } };
        for (int[] b : bad) {
            Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SurfaceDesc(b[0], b[1], b[2], b[3]),
                b[0] + " " + b[1] + "x" + b[2] + " format " + b[3]);
        }
    }

    @Test
    void equalityCoversAllFourFields() {
        SurfaceDesc a = new SurfaceDesc(2, 320, 200, Formats.INDEX8);
        Assertions.assertEquals(SurfaceDesc.forTier(2), a);
        Assertions.assertEquals(
            SurfaceDesc.forTier(2)
                .hashCode(),
            a.hashCode());
        // a tier-0 test surface of T2's size is not T2
        Assertions.assertNotEquals(a, new SurfaceDesc(0, 320, 200, Formats.INDEX8));
        Assertions.assertNotEquals(a, new SurfaceDesc(2, 321, 200, Formats.INDEX8));
        Assertions.assertNotEquals(a, new SurfaceDesc(2, 320, 201, Formats.INDEX8));
        Assertions.assertNotEquals(a, new SurfaceDesc(2, 320, 200, Formats.RGB565));
        Assertions.assertNotEquals(a, null);
        Assertions.assertNotEquals(a, "T2 320x200 index8");
    }

    @Test
    void toStringNamesTheTierWhenThereIsOne() {
        Assertions.assertEquals(
            "T2 320x200 index8",
            SurfaceDesc.forTier(2)
                .toString());
        Assertions.assertEquals(
            "T3 640x400 rgb565",
            SurfaceDesc.forTier(3)
                .toString());
        Assertions.assertEquals("4x3 rgb565", new SurfaceDesc(0, 4, 3, Formats.RGB565).toString());
    }
}
