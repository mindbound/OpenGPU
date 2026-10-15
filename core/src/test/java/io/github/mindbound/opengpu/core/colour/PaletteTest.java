package io.github.mindbound.opengpu.core.colour;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * M0's default palette (28 §5.7, 28:490): the 6×6×6 cube with channels 51·level, then 40 greys of
 * (255·(k + 1) + 20) / 41. The anchors and the grey list are plan §1.6's, computed with rev_checks.py [M].
 */
class PaletteTest {

    /** Plan §1.6's 40 grey levels, entries 216-255. */
    private static final int[] GREYS = { 6, 12, 19, 25, 31, 37, 44, 50, 56, 62, 68, 75, 81, 87, 93, 100, 106, 112, 118,
        124, 131, 137, 143, 149, 155, 162, 168, 174, 180, 187, 193, 199, 205, 211, 218, 224, 230, 236, 243, 249 };

    @Test
    void theAnchors() {
        int[][] anchors = { { 0, 0x000000 }, { 1, 0x000033 }, { 5, 0x0000FF }, { 6, 0x003300 }, { 36, 0x330000 },
            { 196, 0xFF66CC }, { 215, 0xFFFFFF }, { 216, 0x060606 }, { 217, 0x0C0C0C }, { 235, 0x7C7C7C },
            { 255, 0xF9F9F9 } };
        for (int[] a : anchors) {
            Assertions.assertEquals(0xFF000000 | a[1], Palette.DEFAULT.argb(a[0]), "entry " + a[0]);
        }
    }

    @Test
    void entryZeroIsBlack() {
        // a cleared T2 device is black (21:242), not OC's 0x0F0F0F
        Assertions.assertEquals(0xFF000000, Palette.DEFAULT.argb(0));
    }

    @Test
    void theCubeIsFiftyOneTimesItsLevels() {
        for (int r = 0; r < 6; r++) {
            for (int g = 0; g < 6; g++) {
                for (int b = 0; b < 6; b++) {
                    int i = 36 * r + 6 * g + b;
                    Assertions.assertEquals(
                        0xFF000000 | 51 * r << 16 | 51 * g << 8 | 51 * b,
                        Palette.DEFAULT.argb(i),
                        "entry " + i);
                }
            }
        }
    }

    @Test
    void theGreys() {
        for (int k = 0; k < 40; k++) {
            int v = GREYS[k];
            Assertions.assertEquals((255 * (k + 1) + 20) / 41, v, "the list itself, k = " + k);
            Assertions.assertEquals(0xFF000000 | v << 16 | v << 8 | v, Palette.DEFAULT.argb(216 + k), "grey " + k);
            if (k > 0) {
                Assertions.assertTrue(v > GREYS[k - 1], "strictly increasing at " + k);
            }
            Assertions.assertNotEquals(0, v % 51, "grey " + k + " repeats a cube level");
        }
    }

    @Test
    void every256EntriesAreOpaqueAndDistinct() {
        Assertions.assertEquals(256, Palette.DEFAULT.size());
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 256; i++) {
            int argb = Palette.DEFAULT.argb(i);
            Assertions.assertEquals(0xFF, argb >>> 24, "alpha of " + i);
            Assertions.assertTrue(seen.add(argb), "entry " + i + " repeats");
        }
        Assertions.assertThrows(ArrayIndexOutOfBoundsException.class, () -> Palette.DEFAULT.argb(-1));
        Assertions.assertThrows(ArrayIndexOutOfBoundsException.class, () -> Palette.DEFAULT.argb(256));
    }
}
