package io.github.mindbound.opengpu.core.fixtures;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.text;

import java.util.Arrays;
import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.colour.Palette;
import io.github.mindbound.opengpu.core.font.GlyphTable;

/**
 * The tier-E oracle's records and state (28 §5.7, 28:494-506): textures created zeroed, updated row-wise, freed;
 * RESET; the 28 §5.7 byte layout of {@code pixels()}; the bounds-checked accessor that makes the fuzzer's P4
 * observable; canvas carry-over across frames (28 §11.4). The expected values are worked out by hand in the comments,
 * never read from the oracle.
 */
class TierEOracleTest {

    private static final int TEX = 0x08000000;
    private static final int TEX2 = 0x08010000;

    @Test
    void theOracleTakesFontZerosCell() {
        SurfaceDesc s = index8(4, 3);
        TierEOracle oracle = new TierEOracle(s, SyntheticGlyphs.TABLE);
        Assertions.assertSame(s, oracle.surface());
        Assertions.assertSame(SyntheticGlyphs.TABLE, oracle.glyphs());
        // 28 §7.3: font 0 is 6×13
        Assertions.assertThrows(IllegalArgumentException.class, () -> new TierEOracle(s, table(5, 13)));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new TierEOracle(s, table(6, 12)));
        Assertions.assertThrows(NullPointerException.class, () -> new TierEOracle(null, SyntheticGlyphs.TABLE));
        Assertions.assertThrows(NullPointerException.class, () -> new TierEOracle(s, null));
    }

    @Test
    void aNewSurfaceIsZero() {
        // 28 §5.7: zero at start, index 0 or 565 black
        Assertions.assertArrayEquals(new byte[12], oracle(index8(4, 3)).pixels());
        Assertions.assertArrayEquals(new byte[24], oracle(rgb565(4, 3)).pixels());
        Assertions.assertEquals(0, oracle(index8(4, 3)).textureCount());
    }

    @Test
    void aCreatedTextureIsZeros() {
        TierEOracle oracle = oracle(index8(4, 3));
        oracle.createTexture(TEX, 3, 2, Formats.INDEX8);
        Assertions.assertTrue(oracle.hasTexture(TEX));
        Assertions.assertFalse(oracle.hasTexture(TEX2));
        Assertions.assertEquals(1, oracle.textureCount());
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                Assertions.assertEquals(0, oracle.texel(TEX, x, y));
            }
        }
        // the handle is present already; the full handle is the key (O7), so another generation is another texture
        Assertions.assertThrows(IllegalStateException.class, () -> oracle.createTexture(TEX, 3, 2, Formats.INDEX8));
        oracle.createTexture(TEX | 1, 1, 1, Formats.INDEX8);
        Assertions.assertEquals(2, oracle.textureCount());
    }

    @Test
    void createTextureChecksItsArguments() {
        TierEOracle oracle = oracle(index8(4, 3));
        int[][] sizes = { { 0, 1 }, { 1, 0 }, { 1025, 1 }, { 1, 1025 }, { -1, 1 } };
        for (int[] size : sizes) {
            Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> oracle.createTexture(TEX, size[0], size[1], Formats.INDEX8),
                size[0] + "x" + size[1]);
        }
        Assertions.assertDoesNotThrow(() -> oracle.createTexture(TEX, 1024, 1, Formats.INDEX8));
        for (int format : new int[] { Formats.INVALID, 3 }) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> oracle.createTexture(TEX2, 1, 1, format));
        }
        // 28 §6.1: type 1 only, positive, non-zero type
        for (int handle : new int[] { 0, 0x10000000, 0x88000000, 0x00000005 }) {
            Assertions
                .assertThrows(IllegalArgumentException.class, () -> oracle.createTexture(handle, 1, 1, Formats.INDEX8));
        }
    }

    @Test
    void anUpdateCopiesRowsTopFirst() {
        // 28 §6.3: the 2x2 region at (1,0) of a 3x2 texture, rows top first, w bytes each
        TierEOracle oracle = oracle(index8(4, 3));
        oracle.createTexture(TEX, 3, 2, Formats.INDEX8);
        oracle.updateTexture(TEX, 1, 0, 2, 2, new byte[] { 1, 2, 3, 4 });
        int[][] expected = { { 0, 1, 2 }, { 0, 3, 4 } };
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                Assertions.assertEquals(expected[y][x], oracle.texel(TEX, x, y), "(" + x + ", " + y + ")");
            }
        }
    }

    @Test
    void anRgb565UpdateReadsLeU16() {
        // 28 §5.7: a LE u16 per texel, so bytes 34 12 are the value 0x1234
        TierEOracle oracle = oracle(rgb565(4, 3));
        oracle.createTexture(TEX, 2, 1, Formats.RGB565);
        oracle.updateTexture(TEX, 0, 0, 2, 1, new byte[] { 0x34, 0x12, (byte) 0xCD, (byte) 0xAB });
        Assertions.assertEquals(0x1234, oracle.texel(TEX, 0, 0));
        Assertions.assertEquals(0xABCD, oracle.texel(TEX, 1, 0));
        // a blit copies the raw value (28 §5.5): pixels (0,0), (1,0) become 1234 and ABCD, bytes 34 12 CD AB
        oracle.runSegment(segment(blit(TEX, 0, 0, 2, 1, 0, 0, 0)));
        byte[] pixels = oracle.pixels();
        Assertions.assertEquals(0x34, pixels[0] & 0xFF);
        Assertions.assertEquals(0x12, pixels[1] & 0xFF);
        Assertions.assertEquals(0xCD, pixels[2] & 0xFF);
        Assertions.assertEquals(0xAB, pixels[3] & 0xFF);
    }

    @Test
    void key0SkipsOnlyTheRgb565ValueZero() {
        // 28 §5.5: KEY0 skips a texel whose 565 value is 0x0000; 0xF800 and 0x0100, whose low bytes are 0, are
        // written. The clear's 0xFF336699 is 565 value 0x3333: q(0x33, 31) = 6, q(0x66, 63) = 25, q(0x99, 31) = 19
        TierEOracle oracle = oracle(rgb565(4, 1));
        oracle.createTexture(TEX, 3, 1, Formats.RGB565);
        oracle.updateTexture(TEX, 0, 0, 3, 1, new byte[] { 0x00, (byte) 0xF8, 0x00, 0x01, 0x00, 0x00 });
        oracle.runSegment(segment(clear(0xFF336699), blit(TEX, 0, 0, 3, 1, 0, 0, 1)));
        Assertions.assertEquals(0xF800, oracle.pixel(0, 0));
        Assertions.assertEquals(0x0100, oracle.pixel(1, 0));
        Assertions.assertEquals(0x3333, oracle.pixel(2, 0));
        Assertions.assertEquals(0x3333, oracle.pixel(3, 0));
        Assertions
            .assertArrayEquals(new byte[] { 0x00, (byte) 0xF8, 0x00, 0x01, 0x33, 0x33, 0x33, 0x33 }, oracle.pixels());
        // without KEY0, at (1,0), the 0x0000 texel is written too
        oracle.runSegment(segment(blit(TEX, 0, 0, 3, 1, 1, 0, 0)));
        Assertions.assertArrayEquals(
            new byte[] { 0x00, (byte) 0xF8, 0x00, (byte) 0xF8, 0x00, 0x01, 0x00, 0x00 },
            oracle.pixels());
    }

    @Test
    void anUpdateChecksItsRegionAndPayload() {
        TierEOracle oracle = oracle(index8(4, 3));
        oracle.createTexture(TEX, 3, 2, Formats.INDEX8);
        // 28 §6.3: 0 <= x <= TW, 1 <= w <= TW - x, and likewise for y
        int[][] regions = { { -1, 0, 1, 1 }, { 3, 0, 1, 1 }, { 4, 0, 1, 1 }, { 0, 0, 0, 1 }, { 0, 0, 4, 1 },
            { 2, 0, 2, 1 }, { 0, -1, 1, 1 }, { 0, 2, 1, 1 }, { 0, 0, 1, 0 }, { 0, 1, 1, 2 } };
        for (int[] r : regions) {
            Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> oracle.updateTexture(TEX, r[0], r[1], r[2], r[3], new byte[Math.max(0, r[2] * r[3])]),
                "(" + r[0] + ", " + r[1] + ") " + r[2] + "x" + r[3]);
        }
        // the payload is exactly w·h·bpp
        Assertions
            .assertThrows(IllegalArgumentException.class, () -> oracle.updateTexture(TEX, 0, 0, 2, 2, new byte[3]));
        Assertions
            .assertThrows(IllegalArgumentException.class, () -> oracle.updateTexture(TEX, 0, 0, 2, 2, new byte[5]));
        // nothing was written by a refused update
        Assertions.assertEquals(0, oracle.texel(TEX, 0, 0));
        Assertions.assertThrows(IllegalStateException.class, () -> oracle.updateTexture(TEX2, 0, 0, 1, 1, new byte[1]));
        TierEOracle rgb = oracle(rgb565(4, 3));
        rgb.createTexture(TEX, 2, 2, Formats.RGB565);
        Assertions.assertThrows(IllegalArgumentException.class, () -> rgb.updateTexture(TEX, 0, 0, 2, 2, new byte[4]));
        Assertions.assertDoesNotThrow(() -> rgb.updateTexture(TEX, 0, 0, 2, 2, new byte[8]));
    }

    @Test
    void freeRemovesTheTexture() {
        TierEOracle oracle = oracle(index8(4, 3));
        oracle.createTexture(TEX, 1, 1, Formats.INDEX8);
        oracle.freeTexture(TEX);
        Assertions.assertFalse(oracle.hasTexture(TEX));
        Assertions.assertEquals(0, oracle.textureCount());
        Assertions.assertThrows(IllegalStateException.class, () -> oracle.freeTexture(TEX));
        Assertions.assertThrows(IllegalStateException.class, () -> oracle.texel(TEX, 0, 0));
        // validation never accepts a BLIT of a freed texture (28 §2.2 O4), so the oracle refuses it
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> oracle.runSegment(segment(blit(TEX, 0, 0, 1, 1, 0, 0, 0))));
    }

    @Test
    void resetDropsTexturesAndSetsThePixels() {
        // 28:496: T = {}; S = the record's pixels
        TierEOracle oracle = oracle(index8(3, 2));
        oracle.createTexture(TEX, 1, 1, Formats.INDEX8);
        oracle.runSegment(segment(clear(9)));
        byte[] pixels = { 1, 2, 3, 4, 5, 6 };
        oracle.reset(pixels);
        Assertions.assertEquals(0, oracle.textureCount());
        Assertions.assertArrayEquals(pixels, oracle.pixels());
        Assertions.assertEquals(6, oracle.pixel(2, 1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> oracle.reset(new byte[5]));
        Assertions.assertThrows(IllegalArgumentException.class, () -> oracle.reset(new byte[7]));
        // rgb565: LE u16 per pixel, so 00 F8 is (0,0) = 0xF800
        TierEOracle rgb = oracle(rgb565(2, 1));
        byte[] rgbPixels = { 0x00, (byte) 0xF8, 0x1F, 0x00 };
        rgb.reset(rgbPixels);
        Assertions.assertEquals(0xF800, rgb.pixel(0, 0));
        Assertions.assertEquals(0x001F, rgb.pixel(1, 0));
        Assertions.assertArrayEquals(rgbPixels, rgb.pixels());
        Assertions.assertThrows(IllegalArgumentException.class, () -> rgb.reset(new byte[2]));
        // bytes of 0x80 and more are unsigned: index8 80 and FF are 0x80 and 0xFF; rgb565 80 00 is 0x0080 and FF FF
        // is 0xFFFF
        TierEOracle high = oracle(index8(2, 1));
        byte[] highPixels = { (byte) 0x80, (byte) 0xFF };
        high.reset(highPixels);
        Assertions.assertEquals(0x80, high.pixel(0, 0));
        Assertions.assertEquals(0xFF, high.pixel(1, 0));
        Assertions.assertArrayEquals(highPixels, high.pixels());
        TierEOracle highRgb = oracle(rgb565(2, 1));
        byte[] highRgbPixels = { (byte) 0x80, 0x00, (byte) 0xFF, (byte) 0xFF };
        highRgb.reset(highRgbPixels);
        Assertions.assertEquals(0x0080, highRgb.pixel(0, 0));
        Assertions.assertEquals(0xFFFF, highRgb.pixel(1, 0));
        Assertions.assertArrayEquals(highRgbPixels, highRgb.pixels());
    }

    @Test
    void pixelsAreInTheByteLayout() {
        // index8: 1 B per pixel, rows top first; FILL_RECT (2,1) 1x1 with index 7 is byte 1·3 + 2 = 5
        TierEOracle index8 = oracle(index8(3, 2));
        index8.runSegment(segment(fill(2, 1, 1, 1, 7)));
        Assertions.assertArrayEquals(new byte[] { 0, 0, 0, 0, 0, 7 }, index8.pixels());
        // rgb565: a LE u16 with r5 in bits 15-11, g6 in 10-5, b5 in 4-0 (28 §5.7): red F800, green 07E0, blue 001F
        TierEOracle rgb = oracle(rgb565(3, 1));
        rgb.runSegment(
            segment(fill(0, 0, 1, 1, 0xFFFF0000), fill(1, 0, 1, 1, 0xFF00FF00), fill(2, 0, 1, 1, 0xFF0000FF)));
        Assertions.assertArrayEquals(new byte[] { 0x00, (byte) 0xF8, (byte) 0xE0, 0x07, 0x1F, 0x00 }, rgb.pixels());
        Assertions.assertEquals(31, rgb.pixel(0, 0) >>> 11);
        Assertions.assertEquals(63, rgb.pixel(1, 0) >>> 5);
        Assertions.assertEquals(31, rgb.pixel(2, 0));
        // a fresh copy each time
        byte[] copy = rgb.pixels();
        copy[0] = 1;
        Assertions.assertEquals(0xF800, rgb.pixel(0, 0));
        // expanded to RGBA8 by bit replication (28 §5.7): r5 31 -> 31 << 3 | 31 >> 2 = FF, and alpha FF
        Assertions.assertArrayEquals(
            new byte[] { (byte) 0xFF, 0x00, 0x00, (byte) 0xFF, 0x00, (byte) 0xFF, 0x00, (byte) 0xFF, 0x00, 0x00,
                (byte) 0xFF, (byte) 0xFF },
            rgb.expandedRgba(Palette.DEFAULT));
    }

    @Test
    void everyAccessIsBoundsChecked() {
        // a flat index would read (-1, 1) as (2, 0); the accessor throws instead (28 §11.3's P4)
        TierEOracle oracle = oracle(index8(3, 2));
        oracle.runSegment(segment(fill(2, 0, 1, 1, 5)));
        Assertions.assertEquals(5, oracle.pixel(2, 0));
        int[][] outside = { { -1, 1 }, { 3, 0 }, { 0, -1 }, { 0, 2 }, { -1, 0 } };
        for (int[] p : outside) {
            Assertions.assertThrows(IllegalStateException.class, () -> oracle.pixel(p[0], p[1]), p[0] + ", " + p[1]);
        }
        oracle.createTexture(TEX, 2, 2, Formats.INDEX8);
        for (int[] p : new int[][] { { 2, 0 }, { 0, 2 }, { -1, 1 }, { 1, -1 } }) {
            Assertions
                .assertThrows(IllegalStateException.class, () -> oracle.texel(TEX, p[0], p[1]), p[0] + ", " + p[1]);
        }
        // i16 extremes that reach no pixel run without an out-of-bounds access, the sums being 32-bit (28 §5.1): the
        // first fill ends at x + w = -1, the second has w < 0, the blit's i0 = 32 768 is past its i1, and the text is
        // at y = 32 767, below the surface; the image is unchanged
        oracle.runSegment(
            segment(
                fill(-32768, -32768, 32767, 32767, 1),
                fill(32767, 32767, -32768, -32768, 2),
                blit(TEX, -32768, 32767, 32767, 32767, -32768, -32768, 0),
                text(-32768, 32767, 3, 4, 0, 0, "AB")));
        Assertions.assertArrayEquals(new byte[] { 0, 0, 5, 0, 0, 0 }, oracle.pixels());
        // one that reaches: from (-1, -1) with w = h = 32 767, x + w = 32 766, so the whole 3x2 surface
        oracle.runSegment(segment(fill(-1, -1, 32767, 32767, 6)));
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                Assertions.assertEquals(6, oracle.pixel(x, y), "(" + x + ", " + y + ")");
            }
        }
    }

    @Test
    void itRefusesWhatValidationRejects() {
        TierEOracle oracle = oracle(index8(3, 2));
        oracle.createTexture(TEX, 1, 1, Formats.INDEX8);
        oracle.createTexture(TEX2, 1, 1, Formats.RGB565);
        byte[][] refused = { segment(clear(0x100)), segment(fill(0, 0, 1, 1, 0xFF000000)),
            segment(blit(TEX, 0, 0, 1, 1, 0, 0, 2)), segment(blit(TEX2, 0, 0, 1, 1, 0, 0, 0)),
            segment(text(0, 0, 1, 0x100, 1, 0, "A")), segment(text(0, 0, 1, 2, 2, 0, "A")),
            segment(text(0, 0, 1, 2, 0, 1, "A")) };
        for (byte[] s : refused) {
            Assertions.assertThrows(IllegalStateException.class, () -> oracle.runSegment(s), TestEncoder.toHex(s));
        }
        // every refusal comes before the record's first write
        Assertions.assertArrayEquals(new byte[6], oracle.pixels());
        TierEOracle rgb = oracle(rgb565(1, 1));
        Assertions.assertThrows(IllegalStateException.class, () -> rgb.runSegment(segment(clear(0xFEFFFFFF))));
    }

    @Test
    void canvasCarriesOver() {
        // frame 1: clear 01, fill rows 2-3 with 05
        TierEOracle oracle = oracle(index8(8, 4));
        oracle.runFrame(segment(clear(1), fill(0, 2, 8, 2, 5)));
        assertGrid(
            oracle,
            "01 01 01 01 01 01 01 01",
            "01 01 01 01 01 01 01 01",
            "05 05 05 05 05 05 05 05",
            "05 05 05 05 05 05 05 05");
        // frame 2 starts from that image. A KEY0 blit of [00 07; 07 00] to (3,1) writes only (4,1) and (3,2); a
        // transparent blank (U+0020 sets no bit) changes nothing; a fill at x 6 w 4 clips to columns 6-7
        oracle.createTexture(TEX, 2, 2, Formats.INDEX8);
        oracle.updateTexture(TEX, 0, 0, 2, 2, new byte[] { 0, 7, 7, 0 });
        oracle.runFrame(segment(blit(TEX, 0, 0, 2, 2, 3, 1, 1), text(0, 0, 9, 2, 1, 0, " "), fill(6, 0, 4, 4, 9)));
        assertGrid(
            oracle,
            "01 01 01 01 01 01 09 09",
            "01 01 01 01 07 01 09 09",
            "05 05 05 07 05 05 09 09",
            "05 05 05 05 05 05 09 09");
    }

    @Test
    void aFrameIsItsSegmentsInOrder() {
        // 28 §3.2: a segment is not a scope; the second segment's fill lands on the first one's clear
        TierEOracle oracle = oracle(index8(3, 1));
        oracle.runFrame(segment(clear(2)), segment(), segment(fill(1, 0, 5, 5, 3)));
        assertGrid(oracle, "02 03 03");
    }

    @Test
    void textDrawsTheInjectedTable() {
        // the oracle takes G as an object (28 §7.1): 'A' set everywhere, the missing glyph empty, so "AB" opaque on
        // a 12x13 surface is the fg cell at x 0-5 and, U+0042 having no glyph, the bg cell at x 6-11
        byte[] full = new byte[13];
        Arrays.fill(full, (byte) 0xFC);
        GlyphTable g = new GlyphTable(6, 13, new int[] { 0x41 }, full, new byte[13]);
        TierEOracle oracle = new TierEOracle(index8(13, 14), g);
        oracle.runSegment(segment(clear(1), text(0, 0, 9, 2, 0, 0, "AB")));
        for (int y = 0; y < 14; y++) {
            for (int x = 0; x < 13; x++) {
                int expected = y == 13 || x == 12 ? 1 : x < 6 ? 9 : 2;
                Assertions.assertEquals(expected, oracle.pixel(x, y), "(" + x + ", " + y + ")");
            }
        }
    }

    @Test
    void textWritesPlanRowsOfTheSyntheticTable() {
        // 'H' rows F8 14 30 4C 6C 88 A4 C0 E0 FC 18 34 54 (plan §4.2): column b is bit 7 - b, set = fg 9, clear =
        // bg 2; row 0 = 11111000 -> 9 9 9 9 9 2, row 1 = 00010100 -> 2 2 2 9 2 9
        int[] rows = { 0xF8, 0x14, 0x30, 0x4C, 0x6C, 0x88, 0xA4, 0xC0, 0xE0, 0xFC, 0x18, 0x34, 0x54 };
        TierEOracle oracle = oracle(index8(6, 13));
        oracle.runSegment(segment(text(0, 0, 9, 2, 0, 0, "H")));
        for (int r = 0; r < 13; r++) {
            for (int b = 0; b < 6; b++) {
                Assertions
                    .assertEquals((rows[r] >> 7 - b & 1) != 0 ? 9 : 2, oracle.pixel(b, r), "(" + b + ", " + r + ")");
            }
        }
        Assertions.assertEquals(9, oracle.pixel(4, 0));
        Assertions.assertEquals(2, oracle.pixel(5, 0));
        Assertions.assertEquals(9, oracle.pixel(3, 1));
        Assertions.assertEquals(2, oracle.pixel(4, 1));
    }

    private static void assertGrid(TierEOracle oracle, String... rows) {
        SurfaceDesc s = oracle.surface();
        StringBuilder got = new StringBuilder();
        StringBuilder want = new StringBuilder();
        for (int y = 0; y < s.height; y++) {
            for (int x = 0; x < s.width; x++) {
                got.append(x > 0 ? " " : "")
                    .append(String.format(Locale.ROOT, "%02X", oracle.pixel(x, y)));
            }
            got.append('\n');
            want.append(rows[y])
                .append('\n');
        }
        Assertions.assertEquals(want.toString(), got.toString());
    }

    private static TierEOracle oracle(SurfaceDesc surface) {
        return new TierEOracle(surface, SyntheticGlyphs.TABLE);
    }

    private static SurfaceDesc index8(int w, int h) {
        return new SurfaceDesc(0, w, h, Formats.INDEX8);
    }

    private static SurfaceDesc rgb565(int w, int h) {
        return new SurfaceDesc(0, w, h, Formats.RGB565);
    }

    private static GlyphTable table(int w, int h) {
        return new GlyphTable(w, h, new int[0], new byte[0], new byte[h]);
    }
}
