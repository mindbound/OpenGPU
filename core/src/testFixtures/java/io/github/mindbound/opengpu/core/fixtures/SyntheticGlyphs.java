package io.github.mindbound.opengpu.core.fixtures;

import io.github.mindbound.opengpu.core.font.GlyphTable;

/**
 * The synthetic glyph table G that CI, the tier-E oracle and {@code :gl-testkit} use in place of font 0's bitmaps, so
 * that no CI golden depends on a real font (28:633). It is generated here from 28:633's rule (own work, MIT) rather
 * than checked in as data, and its rows are pinned by its test:
 * <ul>
 * <li>U+0020 and U+00A0 are blank: present in the table, with 13 zero rows;</li>
 * <li>each codepoint cp in U+0021-007E and U+00A1-00FF has row r (0-12) = {@code ((7·cp + 29·r) mod 256) AND 0xFC},
 * which keeps every row inside the 6 columns of font 0's cell (28 §7.3);</li>
 * <li>every other codepoint draws the missing glyph, a box with rows 0 and 12 = 0xFC and rows 1-11 = 0x84.</li>
 * </ul>
 * It is 6×13, font 0's cell (28 §7.3), and not the older 8×16 form of {@code cmdstream-checks/rev_checks.py}.
 * Changing the rule is not a format change, but it invalidates every {@code TEXT} golden, which therefore names
 * {@link #ID} in its first directive (28:376).
 */
public final class SyntheticGlyphs {

    /** The table's name in the goldens' mandatory first directive, {@code glyphs synthetic-6x13 28:633}. */
    public static final String ID = "synthetic-6x13";

    /** Font 0's cell (28 §7.3). */
    public static final int CELL_WIDTH = 6;
    public static final int CELL_HEIGHT = 13;

    /** The table: 191 glyphs, U+0020-007E and U+00A0-00FF in ascending order, plus the missing box. */
    public static final GlyphTable TABLE = build();

    private static final int COLUMNS = 0xFC;
    private static final int BOX_EDGE = 0xFC;
    private static final int BOX_SIDES = 0x84;

    private SyntheticGlyphs() {}

    /**
     * Row r (0 = top) of the codepoint's glyph by 28:633's rule, as 0-255 with column b in bit 7 - b: the blank's 0,
     * the rule's value, or the missing box's row for a codepoint the table has no glyph for.
     *
     * @throws IndexOutOfBoundsException for r outside 0-12
     */
    public static int row(int codepoint, int r) {
        if (r < 0 || r >= CELL_HEIGHT) {
            throw new IndexOutOfBoundsException("row " + r + " of " + CELL_HEIGHT);
        }
        if (codepoint == 0x20 || codepoint == 0xA0) {
            return 0;
        }
        if (isDrawn(codepoint)) {
            return (7 * codepoint + 29 * r) % 256 & COLUMNS;
        }
        return r == 0 || r == CELL_HEIGHT - 1 ? BOX_EDGE : BOX_SIDES;
    }

    /** Whether the table holds a glyph for the codepoint, blank or drawn: U+0020-007E and U+00A0-00FF. */
    public static boolean hasGlyph(int codepoint) {
        return codepoint >= 0x20 && codepoint <= 0x7E || codepoint >= 0xA0 && codepoint <= 0xFF;
    }

    /** The 189 drawn codepoints: U+0021-007E and U+00A1-00FF. */
    private static boolean isDrawn(int codepoint) {
        return codepoint >= 0x21 && codepoint <= 0x7E || codepoint >= 0xA1 && codepoint <= 0xFF;
    }

    private static GlyphTable build() {
        int count = 0;
        int[] codepoints = new int[(0x7E - 0x20 + 1) + (0xFF - 0xA0 + 1)];
        for (int cp = 0; cp <= 0xFF; cp++) {
            if (hasGlyph(cp)) {
                codepoints[count++] = cp;
            }
        }
        byte[] rows = new byte[codepoints.length * CELL_HEIGHT];
        for (int i = 0; i < codepoints.length; i++) {
            for (int r = 0; r < CELL_HEIGHT; r++) {
                rows[i * CELL_HEIGHT + r] = (byte) row(codepoints[i], r);
            }
        }
        byte[] missing = new byte[CELL_HEIGHT];
        for (int r = 0; r < CELL_HEIGHT; r++) {
            // -1 is outside both ranges, so row() gives the box
            missing[r] = (byte) row(-1, r);
        }
        return new GlyphTable(CELL_WIDTH, CELL_HEIGHT, codepoints, rows, missing);
    }
}
