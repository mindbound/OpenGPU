package io.github.mindbound.opengpu.core.fixtures;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.font.GlyphTable;

/**
 * The synthetic G of 28:633 against the rule's own checks (q1_font0.py's: 189 drawn glyphs, pairwise distinct, none the
 * box or the blank, every column used, nothing outside 0xFC) and plan §4.2's rows, which the goldens' TEXT grids are
 * drawn from.
 */
class SyntheticGlyphsTest {

    private static final GlyphTable G = SyntheticGlyphs.TABLE;

    /** Plan §4.2's rows 0-12, computed from 28:633's rule. */
    private static final String[][] PINNED = { { "21", "E4 04 20 3C 58 78 94 B0 CC EC 08 24 40" },
        { "41", "C4 E4 00 1C 38 58 74 90 AC CC E8 04 20" }, { "48", "F8 14 30 4C 6C 88 A4 C0 E0 FC 18 34 54" },
        { "69", "DC FC 18 34 50 70 8C A8 C4 E4 00 1C 38" }, { "7E", "70 8C AC C8 E4 00 20 3C 58 74 94 B0 CC" },
        { "A1", "64 84 A0 BC D8 F8 14 30 4C 6C 88 A4 C0" }, { "E9", "5C 7C 98 B4 D0 F0 0C 28 44 64 80 9C B8" },
        { "FF", "F8 14 30 50 6C 88 A4 C4 E0 FC 18 38 54" } };
    private static final String BOX = "FC 84 84 84 84 84 84 84 84 84 84 84 FC";

    @Test
    void theTableIsSixByThirteenWith191Glyphs() {
        Assertions.assertEquals("synthetic-6x13", SyntheticGlyphs.ID);
        // font 0's cell (28 §7.3)
        Assertions.assertEquals(6, SyntheticGlyphs.CELL_WIDTH);
        Assertions.assertEquals(13, SyntheticGlyphs.CELL_HEIGHT);
        Assertions.assertEquals(6, G.cellWidth());
        Assertions.assertEquals(13, G.cellHeight());
        Assertions.assertEquals(191, G.size());
        List<Integer> expected = new ArrayList<>();
        for (int cp = 0x20; cp <= 0x7E; cp++) {
            expected.add(cp);
        }
        for (int cp = 0xA0; cp <= 0xFF; cp++) {
            expected.add(cp);
        }
        List<Integer> got = new ArrayList<>();
        for (int i = 0; i < G.size(); i++) {
            got.add(G.codepointAt(i));
        }
        Assertions.assertEquals(expected, got);
    }

    @Test
    void theRowsFollowTheRule() {
        // 28:633: row r of a drawn codepoint is ((7·cp + 29·r) mod 256) AND 0xFC
        for (int i = 0; i < G.size(); i++) {
            int cp = G.codepointAt(i);
            for (int r = 0; r < 13; r++) {
                int expected = cp == 0x20 || cp == 0xA0 ? 0 : (7 * cp + 29 * r & 0xFF) & 0xFC;
                Assertions.assertEquals(expected, G.row(i, r), String.format(Locale.ROOT, "U+%04X row %d", cp, r));
                Assertions.assertEquals(expected, SyntheticGlyphs.row(cp, r));
            }
        }
    }

    @Test
    void thePinnedRows() {
        for (String[] pin : PINNED) {
            int cp = Integer.parseInt(pin[0], 16);
            Assertions.assertEquals(pin[1], rows(G.indexOf(cp)), "U+00" + pin[0]);
        }
        Assertions.assertEquals(BOX, rows(GlyphTable.MISSING));
    }

    @Test
    void theDrawnGlyphsAreDistinctAndUseEveryColumn() {
        Set<String> drawn = new HashSet<>();
        int columns = 0;
        int outside = 0;
        for (int i = 0; i < G.size(); i++) {
            int cp = G.codepointAt(i);
            if (cp == 0x20 || cp == 0xA0) {
                continue;
            }
            Assertions.assertTrue(drawn.add(rows(i)), String.format(Locale.ROOT, "U+%04X repeats a glyph", cp));
            for (int r = 0; r < 13; r++) {
                columns |= G.row(i, r);
            }
        }
        for (int i = -1; i < G.size(); i++) {
            for (int r = 0; r < 13; r++) {
                outside |= G.row(i, r) & 0x03;
            }
        }
        Assertions.assertEquals(189, drawn.size());
        Assertions.assertFalse(drawn.contains(BOX), "a drawn glyph is the box");
        Assertions.assertFalse(drawn.contains("00 00 00 00 00 00 00 00 00 00 00 00 00"), "a drawn glyph is blank");
        // all six columns of font 0's cell (bits 7-2) are set somewhere, and bits 1-0 nowhere
        Assertions.assertEquals(0xFC, columns);
        Assertions.assertEquals(0, outside);
    }

    @Test
    void theBlanksArePresentAndBlank() {
        for (int cp : new int[] { 0x20, 0xA0 }) {
            int index = G.indexOf(cp);
            Assertions.assertNotEquals(GlyphTable.MISSING, index, "U+" + Integer.toHexString(cp) + " is missing");
            Assertions.assertTrue(SyntheticGlyphs.hasGlyph(cp));
            Assertions.assertEquals("00 00 00 00 00 00 00 00 00 00 00 00 00", rows(index));
        }
    }

    @Test
    void everyOtherCodepointIsMissing() {
        // U+20AC (3 bytes) and U+1F600 (4 bytes) are the goldens' missing glyphs; the C0 and C1 ranges, DEL and
        // U+0100 lie just outside the table
        for (int cp : new int[] { 0x00, 0x1F, 0x7F, 0x80, 0x9F, 0x100, 0x20AC, 0xFFFD, 0x1F600, 0x10FFFF }) {
            Assertions.assertEquals(GlyphTable.MISSING, G.indexOf(cp), String.format(Locale.ROOT, "U+%04X", cp));
            Assertions.assertFalse(SyntheticGlyphs.hasGlyph(cp));
            for (int r = 0; r < 13; r++) {
                Assertions.assertEquals(G.row(GlyphTable.MISSING, r), SyntheticGlyphs.row(cp, r));
            }
        }
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> SyntheticGlyphs.row(0x41, 13));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> SyntheticGlyphs.row(0x41, -1));
    }

    private static String rows(int index) {
        StringBuilder out = new StringBuilder();
        for (int r = 0; r < 13; r++) {
            if (r > 0) {
                out.append(' ');
            }
            out.append(String.format(Locale.ROOT, "%02X", G.row(index, r)));
        }
        return out.toString();
    }
}
