package io.github.mindbound.opengpu.core.font;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The glyph table G (28 §7.1): one byte per row from the top, column b in bit 7 − b (MSB leftmost, 28 §5.6), the
 * constructor's checks, the lookup, the missing glyph at index −1, and copies of every array.
 */
class GlyphTableTest {

    /** A 3×2 table of 'A', 'B' and U+1F600, and a box as the missing glyph. */
    private static GlyphTable small() {
        return new GlyphTable(
            3,
            2,
            new int[] { 0x41, 0x42, 0x1F600 },
            bytes(0x80, 0x40, 0x20, 0xE0, 0xA0, 0x00),
            bytes(0xE0, 0xA0));
    }

    @Test
    void accessorsAndLookup() {
        GlyphTable t = small();
        Assertions.assertEquals(3, t.cellWidth());
        Assertions.assertEquals(2, t.cellHeight());
        Assertions.assertEquals(3, t.size());
        Assertions.assertEquals(0x42, t.codepointAt(1));
        Assertions.assertEquals(0, t.indexOf(0x41));
        Assertions.assertEquals(1, t.indexOf(0x42));
        Assertions.assertEquals(2, t.indexOf(0x1F600));
        // misses below, between and above the entries
        for (int cp : new int[] { 0, 0x40, 0x43, 0x1F5FF, 0x1F601, 0x10FFFF, -1 }) {
            Assertions.assertEquals(GlyphTable.MISSING, t.indexOf(cp), "codepoint " + cp);
        }
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> t.codepointAt(-1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> t.codepointAt(3));
    }

    @Test
    void rowsAndBitsAreMsbLeftmost() {
        GlyphTable t = small();
        Assertions.assertEquals(0x80, t.row(0, 0));
        Assertions.assertEquals(0x40, t.row(0, 1));
        Assertions.assertEquals(0xE0, t.row(1, 1));
        Assertions.assertEquals(0x00, t.row(2, 1));
        // 'A' row 0 = 0x80: column 0 only; row 1 = 0x40: column 1 only
        Assertions.assertTrue(t.bit(0, 0, 0));
        Assertions.assertFalse(t.bit(0, 0, 1));
        Assertions.assertFalse(t.bit(0, 0, 2));
        Assertions.assertFalse(t.bit(0, 1, 0));
        Assertions.assertTrue(t.bit(0, 1, 1));
        // U+1F600 row 0 = 0xA0: columns 0 and 2
        Assertions.assertTrue(t.bit(2, 0, 0));
        Assertions.assertFalse(t.bit(2, 0, 1));
        Assertions.assertTrue(t.bit(2, 0, 2));
        for (int index = -1; index < t.size(); index++) {
            for (int r = 0; r < 2; r++) {
                for (int b = 0; b < 3; b++) {
                    Assertions.assertEquals((t.row(index, r) >> 7 - b & 1) != 0, t.bit(index, r, b));
                }
            }
        }
    }

    @Test
    void indexMinusOneIsTheMissingGlyph() {
        GlyphTable t = small();
        Assertions.assertEquals(-1, GlyphTable.MISSING);
        Assertions.assertEquals(0xE0, t.row(GlyphTable.MISSING, 0));
        Assertions.assertEquals(0xA0, t.row(GlyphTable.MISSING, 1));
        Assertions.assertTrue(t.bit(GlyphTable.MISSING, 1, 2));
        Assertions.assertFalse(t.bit(GlyphTable.MISSING, 1, 1));
        // what a lookup miss gives is the row the missing glyph has
        Assertions.assertEquals(0xE0, t.row(t.indexOf(0x20AC), 0));
    }

    @Test
    void outOfRangeAccessThrows() {
        GlyphTable t = small();
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> t.row(-2, 0));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> t.row(3, 0));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> t.row(0, -1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> t.row(0, 2));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> t.row(GlyphTable.MISSING, 2));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> t.bit(0, 0, -1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> t.bit(0, 0, 3));
    }

    @Test
    void theCellIsOneToEightWideAndOneToSixtyFourHigh() {
        Assertions.assertDoesNotThrow(() -> new GlyphTable(1, 1, new int[0], new byte[0], bytes(0x80)));
        Assertions.assertDoesNotThrow(() -> new GlyphTable(8, 64, new int[0], new byte[0], new byte[64]));
        Assertions.assertDoesNotThrow(() -> new GlyphTable(8, 1, new int[] { 0 }, bytes(0xFF), bytes(0xFF)));
        assertRejected(0, 1, new int[0], new byte[0], new byte[1]);
        assertRejected(9, 1, new int[0], new byte[0], new byte[1]);
        assertRejected(6, 0, new int[0], new byte[0], new byte[0]);
        assertRejected(6, 65, new int[0], new byte[0], new byte[65]);
    }

    @Test
    void codepointsAreStrictlyAscendingScalarValues() {
        // unsorted and duplicate
        assertRejected(1, 1, new int[] { 0x42, 0x41 }, new byte[2], new byte[1]);
        assertRejected(1, 1, new int[] { 0x41, 0x41 }, new byte[2], new byte[1]);
        // surrogates, negative, above U+10FFFF
        for (int cp : new int[] { 0xD800, 0xDBFF, 0xDC00, 0xDFFF, -1, 0x110000 }) {
            assertRejected(1, 1, new int[] { cp }, new byte[1], new byte[1]);
        }
        Assertions.assertDoesNotThrow(
            () -> new GlyphTable(1, 1, new int[] { 0, 0xD7FF, 0xE000, 0x10FFFF }, new byte[4], new byte[1]));
    }

    @Test
    void theArraysHaveTheirLengths() {
        assertRejected(3, 2, new int[] { 0x41 }, new byte[1], new byte[2]);
        assertRejected(3, 2, new int[] { 0x41 }, new byte[3], new byte[2]);
        assertRejected(3, 2, new int[] { 0x41 }, new byte[2], new byte[1]);
        assertRejected(3, 2, new int[] { 0x41 }, new byte[2], new byte[3]);
    }

    @Test
    void noRowSetsABitOutsideTheCell() {
        // a 6-wide cell keeps bits 7-2 (mask 0xFC), as font 0's 6×13 does (28 §7.1)
        Assertions.assertDoesNotThrow(() -> new GlyphTable(6, 1, new int[] { 0x41 }, bytes(0xFC), bytes(0xFC)));
        assertRejected(6, 1, new int[] { 0x41 }, bytes(0x02), bytes(0x00));
        assertRejected(6, 1, new int[] { 0x41 }, bytes(0x01), bytes(0x00));
        assertRejected(6, 1, new int[] { 0x41 }, bytes(0x00), bytes(0x02));
        assertRejected(1, 1, new int[] { 0x41 }, bytes(0x40), bytes(0x80));
        // the second glyph's row is checked too
        assertRejected(6, 1, new int[] { 0x41, 0x42 }, bytes(0xFC, 0xFE), bytes(0x00));
    }

    @Test
    void theArraysAreCopied() {
        int[] codepoints = { 0x41, 0x42 };
        byte[] rows = bytes(0x80, 0x40);
        byte[] missing = bytes(0xC0);
        GlyphTable t = new GlyphTable(2, 1, codepoints, rows, missing);
        codepoints[0] = 0x30;
        rows[0] = 0;
        missing[0] = 0;
        Assertions.assertEquals(0x41, t.codepointAt(0));
        Assertions.assertEquals(0, t.indexOf(0x41));
        Assertions.assertEquals(GlyphTable.MISSING, t.indexOf(0x30));
        Assertions.assertEquals(0x80, t.row(0, 0));
        Assertions.assertEquals(0xC0, t.row(GlyphTable.MISSING, 0));
    }

    private static void assertRejected(int w, int h, int[] codepoints, byte[] rows, byte[] missing) {
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new GlyphTable(w, h, codepoints, rows, missing),
            w + "x" + h);
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
