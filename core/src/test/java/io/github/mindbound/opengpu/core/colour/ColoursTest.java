package io.github.mindbound.opengpu.core.colour;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;

/**
 * 28 §3.4 and §5.7's colour functions, exhaustively where 28 §11.4 asks: {@code q} against floor(c·m/255 + ½) for
 * every c, {@code q(expand(v)) = v} for all 65 536 values, and expand as integer bit replication, which differs from
 * rounding at exactly 14 levels ({@code cmdstream-checks/q565.py}). The expected values are computed here from 28's
 * formulas in their own form, not with {@link Colours}.
 */
class ColoursTest {

    @Test
    void qRoundsToNearestForEveryChannelValue() {
        // floor(c·m/255 + 1/2) = floor((2cm + 255) / 510), exact in integers; m is odd, so 2cm + 255 is odd and
        // never a multiple of 510: there are no ties
        for (int m : new int[] { 31, 63 }) {
            for (int c = 0; c <= 255; c++) {
                Assertions.assertEquals((2 * c * m + 255) / 510, Colours.q(c, m), "q(" + c + ", " + m + ")");
                Assertions.assertNotEquals(0, (2 * c * m + 255) % 510, "a tie at c = " + c + ", m = " + m);
            }
            Assertions.assertEquals(0, Colours.q(0, m));
            Assertions.assertEquals(m, Colours.q(255, m));
        }
    }

    @Test
    void quantizeIsQPerChannelForEveryRgb() {
        // 2^24 colours, each channel by the rounding formula; bits 24-31 are ignored
        for (int rgb = 0; rgb <= 0xFFFFFF; rgb++) {
            int r = rgb >>> 16;
            int g = rgb >>> 8 & 0xFF;
            int b = rgb & 0xFF;
            int expected = (2 * r * 31 + 255) / 510 << 11 | (2 * g * 63 + 255) / 510 << 5 | (2 * b * 31 + 255) / 510;
            int got = Colours.quantize565(rgb);
            if (got != expected || Colours.quantize565(0xFF000000 | rgb) != expected) {
                Assertions.fail(
                    String.format(Locale.ROOT, "quantize565(0x%06X) = 0x%04X, expected 0x%04X", rgb, got, expected));
            }
        }
    }

    @Test
    void expandRoundTripsEvery565Value() {
        for (int v = 0; v <= 0xFFFF; v++) {
            int argb = Colours.expand565(v);
            int r5 = v >>> 11;
            int g6 = v >>> 5 & 0x3F;
            int b5 = v & 0x1F;
            // 28 §5.7: integer bit replication, alpha 255 (24:201)
            int expected = 0xFF000000 | (r5 << 3 | r5 >>> 2) << 16 | (g6 << 2 | g6 >>> 4) << 8 | (b5 << 3 | b5 >>> 2);
            if (argb != expected) {
                Assertions
                    .fail(String.format(Locale.ROOT, "expand565(0x%04X) = 0x%08X, expected 0x%08X", v, argb, expected));
            }
            // 28 §3.4: q(expand(v)) = v, so any 565 value can be drawn exactly by passing 0xFF and expand(v)
            if (Colours.quantize565(argb) != v || Colours.sv(Formats.RGB565, argb) != v) {
                Assertions.fail(String.format(Locale.ROOT, "q(expand(0x%04X)) = 0x%04X", v, Colours.quantize565(argb)));
            }
            // bits 16-31 of the value are ignored
            Assertions.assertEquals(argb, Colours.expand565(v | 0xABCD0000));
        }
    }

    @Test
    void bitReplicationDiffersFromRoundingAtFourteenLevels() {
        // round(v·255/m) = floor((510v + m) / 2m), exact: 510v is even and m odd, so there are no ties
        List<Integer> five = new ArrayList<>();
        for (int v = 0; v < 32; v++) {
            int replicated = Colours.expand565(v << 11) >>> 16 & 0xFF;
            Assertions.assertEquals(v << 3 | v >>> 2, replicated);
            if (replicated != (510 * v + 31) / 62) {
                five.add(v);
            }
        }
        List<Integer> six = new ArrayList<>();
        for (int v = 0; v < 64; v++) {
            int replicated = Colours.expand565(v << 5) >>> 8 & 0xFF;
            Assertions.assertEquals(v << 2 | v >>> 4, replicated);
            if (replicated != (510 * v + 63) / 126) {
                six.add(v);
            }
        }
        Assertions.assertEquals(Arrays.asList(3, 7, 24, 28), five);
        Assertions.assertEquals(Arrays.asList(11, 12, 13, 14, 15, 48, 49, 50, 51, 52), six);
        // the blue channel replicates as red does
        for (int v = 0; v < 32; v++) {
            Assertions.assertEquals(v << 3 | v >>> 2, Colours.expand565(v) & 0xFF);
        }
    }

    @Test
    void validityFollowsTheFormat() {
        // 28 §3.4: index8 0x000000II; rgb565 0xFFRRGGBB until M1's blending
        for (int i = 0; i <= 255; i++) {
            Assertions.assertTrue(Colours.isValid(Formats.INDEX8, i), "index " + i);
        }
        for (int c : new int[] { 256, 0x100, 0xFF000000, 0x80000000, 0x0000FF00, -1 }) {
            Assertions.assertFalse(Colours.isValid(Formats.INDEX8, c), String.format(Locale.ROOT, "index8 0x%08X", c));
        }
        for (int c : new int[] { 0xFF000000, 0xFFFFFFFF, 0xFF336699 }) {
            Assertions.assertTrue(Colours.isValid(Formats.RGB565, c), String.format(Locale.ROOT, "rgb565 0x%08X", c));
        }
        for (int c : new int[] { 0x00FFFFFF, 0, 5, 0xFEFFFFFF, 0x7FFFFFFF, 0x01000000 }) {
            Assertions.assertFalse(Colours.isValid(Formats.RGB565, c), String.format(Locale.ROOT, "rgb565 0x%08X", c));
        }
        for (int format : new int[] { Formats.INVALID, 3, -1 }) {
            Assertions.assertFalse(Colours.isValid(format, 0), "format " + format);
            Assertions.assertFalse(Colours.isValid(format, 0xFF000000), "format " + format);
        }
    }

    @Test
    void surfaceValuesOf28sExamples() {
        // 28 §3.4 [M] and plan §3.2 (example.py, rev_checks.py)
        Assertions.assertEquals(0x0000, Colours.sv(Formats.RGB565, 0xFF000000));
        Assertions.assertEquals(0xFC00, Colours.sv(Formats.RGB565, 0xFFFF8000));
        Assertions.assertEquals(0xFFFF, Colours.sv(Formats.RGB565, 0xFFFFFFFF));
        Assertions.assertEquals(0x3333, Colours.sv(Formats.RGB565, 0xFF336699));
        for (int i = 0; i <= 255; i++) {
            Assertions.assertEquals(i, Colours.sv(Formats.INDEX8, i));
        }
        Assertions.assertThrows(IllegalArgumentException.class, () -> Colours.sv(Formats.INVALID, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Colours.sv(3, 0));
        Assertions.assertEquals(0xFFFF8200, Colours.expand565(0xFC00));
    }

    @Test
    void expandReadsThePaletteOnIndex8Only() {
        Palette p = Palette.DEFAULT;
        Assertions.assertEquals(0xFFFF66CC, Colours.expand(Formats.INDEX8, 196, p));
        Assertions.assertEquals(0xFF000000, Colours.expand(Formats.INDEX8, 0, p));
        Assertions.assertEquals(0xFFFF8200, Colours.expand(Formats.RGB565, 0xFC00, p));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Colours.expand(Formats.INVALID, 0, p));
    }

    @Test
    void expandToRgbaOnIndex8() {
        // a 2x2 index8 surface, rows top first (28 §5.7): 0 black, 5 blue 0000FF, 196 FF66CC, 255 grey F9
        SurfaceDesc s = new SurfaceDesc(0, 2, 2, Formats.INDEX8);
        byte[] rgba = Colours.expandToRgba(s, new byte[] { 0, 5, (byte) 196, (byte) 255 }, Palette.DEFAULT);
        Assertions
            .assertArrayEquals(bytes(0, 0, 0, 255, 0, 0, 255, 255, 255, 0x66, 0xCC, 255, 0xF9, 0xF9, 0xF9, 255), rgba);
    }

    @Test
    void expandToRgbaOnRgb565() {
        // LE u16 per pixel with r5 in bits 15-11: F800 red, 07E0 green, 001F blue, FC00 -> FF8200
        SurfaceDesc s = new SurfaceDesc(0, 2, 2, Formats.RGB565);
        byte[] pixels = bytes(0x00, 0xF8, 0xE0, 0x07, 0x1F, 0x00, 0x00, 0xFC);
        Assertions.assertArrayEquals(
            bytes(255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 255, 0x82, 0, 255),
            Colours.expandToRgba(s, pixels, Palette.DEFAULT));
        Assertions
            .assertThrows(IllegalArgumentException.class, () -> Colours.expandToRgba(s, new byte[4], Palette.DEFAULT));
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
