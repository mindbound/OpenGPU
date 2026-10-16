package io.github.mindbound.opengpu.gl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.font.GlyphTable;
import io.github.mindbound.opengpu.core.font.GlyphTables;

/**
 * {@link GlyphAtlas}'s layout (28 §7.5; S8 plan §3.7) on the synthetic table and on generated tables: cells left to
 * right in rows of 170, the missing glyph in the cell after the last glyph, 13 259 glyphs fitting a 1 024² atlas and
 * 13 260 not, and every texel 255 exactly where {@link GlyphTable#bit} is set, row 0 the top; and on font 0
 * ({@link GlyphTables#font0()}: 4 122 cells, 1 020 × 325, 331 500 B in the ledger).
 */
class GlyphAtlasLayoutTest {

    @Test
    void theSyntheticTableTakes192CellsIn170By2() {
        GlyphAtlas atlas = new GlyphAtlas(SyntheticGlyphs.TABLE);
        Assertions.assertEquals(191, SyntheticGlyphs.TABLE.size());
        Assertions.assertEquals(192, atlas.cells());
        Assertions.assertEquals(170, atlas.columns());
        Assertions.assertEquals(2, atlas.rows());
        Assertions.assertEquals(1020, atlas.width());
        Assertions.assertEquals(26, atlas.height());
        Assertions.assertEquals(1020 * 26, atlas.texels().length);
    }

    @Test
    void fontZeroTakes4122CellsIn170By25() {
        // S8 plan §3.14: 4 121 glyphs and the missing glyph; 28:632's 321 438 B is the glyphs' own area, 4 121 · 78
        GlyphAtlas atlas = new GlyphAtlas(GlyphTables.font0());
        Assertions.assertEquals(
            4121,
            atlas.glyphs()
                .size());
        Assertions.assertEquals(4122, atlas.cells());
        Assertions.assertEquals(170, atlas.columns());
        Assertions.assertEquals(25, atlas.rows());
        Assertions.assertEquals(1020, atlas.width());
        Assertions.assertEquals(325, atlas.height());
        Assertions.assertEquals(331_500, atlas.texels().length);
        Assertions.assertEquals(4121, atlas.cell(GlyphTable.MISSING));
        assertTexels(atlas);
    }

    @Test
    void cellsRunLeftToRightTopRowFirst() {
        GlyphAtlas atlas = new GlyphAtlas(SyntheticGlyphs.TABLE);
        Assertions.assertEquals(0, atlas.cellX(0));
        Assertions.assertEquals(0, atlas.cellY(0));
        Assertions.assertEquals(6, atlas.cellX(1));
        Assertions.assertEquals(1014, atlas.cellX(169));
        Assertions.assertEquals(0, atlas.cellY(169));
        Assertions.assertEquals(0, atlas.cellX(170));
        Assertions.assertEquals(13, atlas.cellY(170));
        Assertions.assertEquals(126, atlas.cellX(191));
        Assertions.assertEquals(13, atlas.cellY(191));
    }

    @Test
    void theMissingGlyphIsTheLastCell() {
        GlyphAtlas atlas = new GlyphAtlas(SyntheticGlyphs.TABLE);
        Assertions.assertEquals(191, atlas.cell(GlyphTable.MISSING));
        Assertions.assertEquals(0, atlas.cell(0));
        Assertions.assertEquals(190, atlas.cell(190));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> atlas.cell(191));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> atlas.cell(-2));
        // A codepoint without a glyph draws the box (28:633), from the last cell
        int cell = atlas.cell(SyntheticGlyphs.TABLE.indexOf(0x2500));
        byte[] texels = atlas.texels();
        for (int r = 0; r < 13; r++) {
            for (int b = 0; b < 6; b++) {
                boolean set = texels[(atlas.cellY(cell) + r) * atlas.width() + atlas.cellX(cell) + b] != 0;
                Assertions.assertEquals(SyntheticGlyphs.row(0x2500, r) >>> 7 - b & 1, set ? 1 : 0, "row " + r);
            }
        }
    }

    @Test
    void everyTexelIsTheGlyphsBitRowZeroTheTop() {
        assertTexels(new GlyphAtlas(SyntheticGlyphs.TABLE));
        // An asymmetric glyph: only row 0's leftmost pixel, so a flip in either axis shows
        GlyphTable corner = table(3, new byte[] { (byte) 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
        GlyphAtlas atlas = new GlyphAtlas(corner);
        byte[] texels = atlas.texels();
        for (int c = 0; c < 3; c++) {
            Assertions.assertEquals((byte) 0xFF, texels[atlas.cellX(c)], "cell " + c + "'s top-left texel");
            Assertions.assertEquals(0, texels[12 * atlas.width() + atlas.cellX(c)], "cell " + c + "'s bottom-left");
            Assertions.assertEquals(0, texels[atlas.cellX(c) + 5], "cell " + c + "'s top-right");
        }
        assertTexels(atlas);
    }

    @Test
    void thirteenThousandTwoHundredFiftyNineGlyphsFitAndOneMoreDoesNot() {
        GlyphAtlas full = new GlyphAtlas(table(13_259, new byte[13]));
        Assertions.assertEquals(13_260, full.cells());
        Assertions.assertEquals(78, full.rows());
        Assertions.assertEquals(1014, full.height());
        Assertions.assertEquals(1020, full.width());
        Assertions.assertTrue(full.height() <= GlyphAtlas.MAX_SIDE);
        Assertions.assertEquals(13_259, full.cell(GlyphTable.MISSING));
        Assertions.assertEquals(1014, full.cellX(13_259));
        Assertions.assertEquals(1001, full.cellY(13_259));
        IllegalArgumentException e = Assertions
            .assertThrows(IllegalArgumentException.class, () -> new GlyphAtlas(table(13_260, new byte[13])));
        Assertions.assertTrue(
            e.getMessage()
                .contains("13261 cells"),
            e.getMessage());
    }

    @Test
    void anEmptyTableHoldsTheMissingGlyphAlone() {
        GlyphAtlas atlas = new GlyphAtlas(table(0, new byte[13]));
        Assertions.assertEquals(1, atlas.cells());
        Assertions.assertEquals(1, atlas.rows());
        Assertions.assertEquals(13, atlas.height());
        Assertions.assertEquals(0, atlas.cell(GlyphTable.MISSING));
        assertTexels(atlas);
    }

    @Test
    void unusedCellsAreZero() {
        GlyphAtlas atlas = new GlyphAtlas(SyntheticGlyphs.TABLE);
        byte[] texels = atlas.texels();
        for (int y = 13; y < 26; y++) {
            for (int x = atlas.cellX(192); x < atlas.width(); x++) {
                Assertions.assertEquals(0, texels[y * atlas.width() + x], "texel (" + x + ", " + y + ")");
            }
        }
    }

    @Test
    void textCellsLandWhereOpQuadsFetchThem() {
        // OpQuads' offset (cellX - x0, cellY - y) maps pixel (x0 + b, y + r) to texel (cellX + b, cellY + r)
        GlyphAtlas atlas = new GlyphAtlas(SyntheticGlyphs.TABLE);
        byte[] texels = atlas.texels();
        int index = SyntheticGlyphs.TABLE.indexOf('A');
        int cell = atlas.cell(index);
        int x0 = -2;
        int y = 5;
        int ox = atlas.cellX(cell) - x0;
        int oy = atlas.cellY(cell) - y;
        for (int r = 0; r < 13; r++) {
            for (int b = 0; b < 6; b++) {
                int tx = x0 + b + ox;
                int ty = y + r + oy;
                Assertions.assertEquals(
                    SyntheticGlyphs.TABLE.bit(index, r, b),
                    texels[ty * atlas.width() + tx] != 0,
                    "row " + r + " column " + b);
            }
        }
    }

    /** Every texel of every used cell against {@link GlyphTable#bit}, and 0 or 255 only. */
    private static void assertTexels(GlyphAtlas atlas) {
        GlyphTable glyphs = atlas.glyphs();
        byte[] texels = atlas.texels();
        Assertions.assertEquals(atlas.width() * atlas.height(), texels.length);
        for (byte texel : texels) {
            Assertions.assertTrue(texel == 0 || texel == (byte) 0xFF, "a texel of " + (texel & 0xFF));
        }
        for (int c = 0; c < atlas.cells(); c++) {
            int index = c == glyphs.size() ? GlyphTable.MISSING : c;
            for (int r = 0; r < glyphs.cellHeight(); r++) {
                for (int b = 0; b < glyphs.cellWidth(); b++) {
                    boolean set = texels[(atlas.cellY(c) + r) * atlas.width() + atlas.cellX(c) + b] != 0;
                    if (set != glyphs.bit(index, r, b)) {
                        Assertions.fail("cell " + c + " row " + r + " column " + b + " is " + set);
                    }
                }
            }
        }
    }

    /** A 6×13 table of n glyphs (codepoints 0x100 up), each with these rows; the missing glyph is a full box. */
    private static GlyphTable table(int n, byte[] rows) {
        int[] codepoints = new int[n];
        byte[] all = new byte[n * 13];
        for (int i = 0; i < n; i++) {
            codepoints[i] = 0x100 + i;
            System.arraycopy(rows, 0, all, i * 13, 13);
        }
        byte[] missing = new byte[13];
        for (int r = 0; r < 13; r++) {
            missing[r] = (byte) 0xFC;
        }
        return new GlyphTable(6, 13, codepoints, all, missing);
    }
}
