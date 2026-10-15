package io.github.mindbound.opengpu.core.cmd;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.end;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.header;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.text;

import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;

/**
 * 28 §9.4's worked frame: its bytes on T2 (28:833-838) and on T3 (28:842), its record offsets, its validation with
 * each tier's caps, the T2 operands' rejection on T3, and the frame run on the tier-E oracle with a defined sprite,
 * against a surface worked out here from 28 §5.3-§5.6 and 28:633's glyph rule.
 */
class WorkedExampleTest {

    private static final int TEX = 0x08000000;

    /** 28:833-838, verbatim. */
    private static final String T2_BYTES = "4F 47 50 55 01 00 00 00 | 01 00 00 00 00 | "
        + "30 0A 00 14 00 64 00 32 00 C4 00 00 00 | 31 00 00 00 08 00 00 00 00 10 00 10 00 96 00 28 00 01 | "
        + "33 08 00 B4 00 FF 00 00 00 00 00 00 00 01 00 02 00 48 69 | FF";

    /** 28:842's operands in 28 §5.2's layouts. */
    private static final String T3_BYTES = "4F 47 50 55 01 00 00 00 | 01 00 00 00 FF | "
        + "30 0A 00 14 00 64 00 32 00 00 80 FF FF | 31 00 00 00 08 00 00 00 00 10 00 10 00 96 00 28 00 01 | "
        + "33 08 00 B4 00 FF FF FF FF 00 00 00 FF 01 00 02 00 48 69 | FF";

    private static byte[] t2Frame() {
        return segment(
            clear(0),
            fill(10, 20, 100, 50, 196),
            blit(TEX, 0, 0, 16, 16, 150, 40, 1),
            text(8, 180, 255, 0, 1, 0, "Hi"));
    }

    private static byte[] t3Frame() {
        return segment(
            clear(0xFF000000),
            fill(10, 20, 100, 50, 0xFFFF8000),
            blit(TEX, 0, 0, 16, 16, 150, 40, 1),
            text(8, 180, 0xFFFFFFFF, 0xFF000000, 1, 0, "Hi"));
    }

    @Test
    void theBuildersGive28sBytes() {
        Assertions.assertArrayEquals(TestEncoder.hex(T2_BYTES), t2Frame());
        Assertions.assertArrayEquals(TestEncoder.hex(T3_BYTES), t3Frame());
        Assertions.assertEquals(64, t2Frame().length);
        Assertions.assertEquals(64, t3Frame().length);
    }

    @Test
    void theRecordsAreAt8_13_26_44AndEndAt63() {
        int[] sizes = { header().length, clear(0).length, fill(10, 20, 100, 50, 196).length,
            blit(TEX, 0, 0, 16, 16, 150, 40, 1).length, text(8, 180, 255, 0, 1, 0, "Hi").length, end().length };
        int[] offsets = { 0, 8, 13, 26, 44, 63 };
        int at = 0;
        for (int i = 0; i < sizes.length; i++) {
            Assertions.assertEquals(offsets[i], at);
            at += sizes[i];
        }
        Assertions.assertEquals(64, at);
        byte[] s = t2Frame();
        Assertions.assertEquals(Opcodes.CLEAR, s[8]);
        Assertions.assertEquals(Opcodes.FILL_RECT, s[13]);
        Assertions.assertEquals(Opcodes.BLIT, s[26]);
        Assertions.assertEquals(Opcodes.TEXT, s[44]);
        Assertions.assertEquals(Opcodes.END, s[63] & 0xFF);
    }

    @Test
    void theFrameIsAcceptedOnEachTier() {
        // T2: CLEAR 64 000 px, FILL_RECT 100 x 50, BLIT 16 x 16 with the KEY0 texels counted, TEXT 2 x 78
        Expectations.assertAccepted(t2Frame(), Expectations.t2(), new FrameTotals(64, 4, 64000, 5000, 256, 156));
        Expectations.assertAccepted(t3Frame(), Expectations.t3(), new FrameTotals(64, 4, 256000, 5000, 256, 156));
    }

    @Test
    void theT2OperandsAreBadColoursOnT3() {
        // 28:842: 0, 196 and 255 have alpha 0, so CLEAR at 8 fails first
        StreamError e = Expectations.assertRejected(t2Frame(), Expectations.t3(), ErrorCode.BAD_COLOUR, 8, "CLEAR");
        Assertions.assertEquals("8: CLEAR: colour 0x00000000: alpha must be 0xFF before blending (M1)", e.message());
        // And the T3 operands on T2: 0xFF000000 is no index
        Expectations.assertRejected(t3Frame(), Expectations.t2(), ErrorCode.BAD_COLOUR, 8, "CLEAR");
    }

    @Test
    void theOracleDrawsTheFrameOnT2() {
        TierEOracle oracle = new TierEOracle(SurfaceDesc.forTier(2), SyntheticGlyphs.TABLE);
        oracle.createTexture(TEX, 16, 16, Formats.INDEX8);
        oracle.updateTexture(TEX, 0, 0, 16, 16, sprite(1));
        // The oracle runs validated segments only
        Expectations.assertAccepted(t2Frame(), Expectations.t2(), new FrameTotals(64, 4, 64000, 5000, 256, 156));
        oracle.runFrame(t2Frame());
        // What 28 §5.3-§5.6 make of it: index 0 everywhere, the fill rect 196, the sprite where its texel is not 0
        // (KEY0), and 255 on the set bits of 'H' and 'i' (TRANSPARENT_BG)
        int[][] expected = new int[200][320];
        for (int y = 20; y < 70; y++) {
            for (int x = 10; x < 110; x++) {
                expected[y][x] = 196;
            }
        }
        drawSprite(expected, 1);
        drawText(expected, 255);
        assertSurface(expected, oracle, 1);
        // Spot checks of 28's prose
        Assertions.assertEquals(0, oracle.pixel(0, 0));
        Assertions.assertEquals(196, oracle.pixel(10, 20));
        Assertions.assertEquals(196, oracle.pixel(109, 69));
        Assertions.assertEquals(0, oracle.pixel(110, 20));
        Assertions.assertEquals(0, oracle.pixel(10, 70));
        // Texel (0, 0) is 0 and keyed out; texel (1, 0) is 1
        Assertions.assertEquals(0, oracle.pixel(150, 40));
        Assertions.assertEquals(1, oracle.pixel(151, 40));
        Assertions.assertEquals(3, oracle.pixel(150, 41));
    }

    @Test
    void theOracleDrawsTheFrameOnT3() {
        TierEOracle oracle = new TierEOracle(SurfaceDesc.forTier(3), SyntheticGlyphs.TABLE);
        oracle.createTexture(TEX, 16, 16, Formats.RGB565);
        // 565 texels 0x0841 times (i + 3j) % 5: a fifth are 0, and none is a quantized operand
        oracle.updateTexture(TEX, 0, 0, 16, 16, sprite(2));
        Expectations.assertAccepted(t3Frame(), Expectations.t3(), new FrameTotals(64, 4, 256000, 5000, 256, 156));
        oracle.runFrame(t3Frame());
        // sv(0xFF000000) = 0x0000, sv(0xFFFF8000) = 0xFC00, sv(0xFFFFFFFF) = 0xFFFF (28:265)
        int[][] expected = new int[400][640];
        for (int y = 20; y < 70; y++) {
            for (int x = 10; x < 110; x++) {
                expected[y][x] = 0xFC00;
            }
        }
        drawSprite(expected, 2);
        drawText(expected, 0xFFFF);
        assertSurface(expected, oracle, 2);
    }

    /** Texel (i, j) = (i + 3j) % 5, times 0x0841 on rgb565, rows top first in the 28 §5.7 layout. */
    private static byte[] sprite(int bpp) {
        byte[] payload = new byte[16 * 16 * bpp];
        for (int j = 0; j < 16; j++) {
            for (int i = 0; i < 16; i++) {
                int v = texel(i, j, bpp);
                payload[(j * 16 + i) * bpp] = (byte) v;
                if (bpp == 2) {
                    payload[(j * 16 + i) * 2 + 1] = (byte) (v >>> 8);
                }
            }
        }
        return payload;
    }

    private static int texel(int i, int j, int bpp) {
        int v = (i + 3 * j) % 5;
        return bpp == 1 ? v : v * 0x0841;
    }

    /** The BLIT 0,0 16x16 to 150,40 with KEY0: texel 0 leaves the pixel. */
    private static void drawSprite(int[][] s, int bpp) {
        for (int j = 0; j < 16; j++) {
            for (int i = 0; i < 16; i++) {
                int v = texel(i, j, bpp);
                if (v != 0) {
                    s[40 + j][150 + i] = v;
                }
            }
        }
    }

    /** "Hi" at (8, 180) with TRANSPARENT_BG: 28:633's rule, row r = ((7·cp + 29·r) mod 256) AND 0xFC, MSB left. */
    private static void drawText(int[][] s, int fg) {
        int[] codepoints = { 'H', 'i' };
        for (int k = 0; k < codepoints.length; k++) {
            for (int r = 0; r < 13; r++) {
                int row = (7 * codepoints[k] + 29 * r) % 256 & 0xFC;
                for (int b = 0; b < 6; b++) {
                    if ((row >> 7 - b & 1) != 0) {
                        s[180 + r][8 + 6 * k + b] = fg;
                    }
                }
            }
        }
    }

    private static void assertSurface(int[][] expected, TierEOracle oracle, int bpp) {
        byte[] pixels = oracle.pixels();
        int width = expected[0].length;
        Assertions.assertEquals(expected.length * width * bpp, pixels.length);
        for (int y = 0; y < expected.length; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * bpp;
                int actual = bpp == 1 ? pixels[i] & 0xFF : (pixels[i] & 0xFF) | (pixels[i + 1] & 0xFF) << 8;
                if (actual != expected[y][x]) {
                    Assertions.fail(
                        String
                            .format(Locale.ROOT, "(%d, %d): expected 0x%X, oracle 0x%X", x, y, expected[y][x], actual));
                }
            }
        }
    }
}
