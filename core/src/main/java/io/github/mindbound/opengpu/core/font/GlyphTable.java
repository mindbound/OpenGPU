package io.github.mindbound.opengpu.core.font;

/**
 * A font's glyph table G (28 §7.1): codepoints mapped to cell bitmaps, plus a missing glyph. The tier-E oracle and S8's
 * atlas take the same object, so they cannot diverge. A glyph is one byte per row from the top, with column b
 * (0 = left) in bit 7 - b, MSB leftmost as BDF stores a row (28 §5.6), so the cell is at most 8 wide; M1's wider faces
 * get a wider constructor, and {@link #bit} is the accessor that stays. Font 0 is 6×13 (28 §7.3); CI and the oracle use
 * a synthetic table (28:633); the real font 0 comes from {@link GlyphTables#font0()}. Immutable.
 */
public final class GlyphTable {

    /** The index {@link #row} and {@link #bit} take for the missing glyph. */
    public static final int MISSING = -1;

    private static final int MAX_CELL_WIDTH = 8;
    private static final int MAX_CELL_HEIGHT = 64;

    private final int cellWidth;
    private final int cellHeight;
    private final int[] codepoints;
    /** Glyph i's row r at {@code i * cellHeight + r}. */
    private final byte[] rows;
    private final byte[] missing;

    /**
     * @param codepoints strictly ascending, each in 0-0x10FFFF and no surrogate
     * @param rows       glyph i's row r at {@code i * cellHeight + r}, so {@code codepoints.length * cellHeight} bytes
     * @param missing    the missing glyph's {@code cellHeight} rows
     * @throws IllegalArgumentException unless 1 ≤ cellWidth ≤ 8 and 1 ≤ cellHeight ≤ 64, the codepoints and the lengths
     *                                  are as described, and no row, the missing glyph's included, sets a bit outside
     *                                  its top cellWidth bits
     */
    public GlyphTable(int cellWidth, int cellHeight, int[] codepoints, byte[] rows, byte[] missing) {
        if (cellWidth < 1 || cellWidth > MAX_CELL_WIDTH) {
            throw new IllegalArgumentException("cell width " + cellWidth + " is outside 1.." + MAX_CELL_WIDTH);
        }
        if (cellHeight < 1 || cellHeight > MAX_CELL_HEIGHT) {
            throw new IllegalArgumentException("cell height " + cellHeight + " is outside 1.." + MAX_CELL_HEIGHT);
        }
        for (int i = 0; i < codepoints.length; i++) {
            int cp = codepoints[i];
            if (cp < 0 || cp > 0x10FFFF || cp >= 0xD800 && cp <= 0xDFFF) {
                throw new IllegalArgumentException(
                    "codepoint " + Integer.toHexString(cp) + " at " + i + " is no Unicode scalar value");
            }
            if (i > 0 && cp <= codepoints[i - 1]) {
                throw new IllegalArgumentException("codepoints are not strictly ascending at " + i);
            }
        }
        if (rows.length != (long) codepoints.length * cellHeight) {
            throw new IllegalArgumentException(
                rows.length + " row bytes for " + codepoints.length + " glyphs of " + cellHeight + " rows");
        }
        if (missing.length != cellHeight) {
            throw new IllegalArgumentException("the missing glyph has " + missing.length + " rows, not " + cellHeight);
        }
        int outside = 0xFF >>> cellWidth;
        for (int i = 0; i < rows.length; i++) {
            if ((rows[i] & outside) != 0) {
                throw new IllegalArgumentException(
                    "glyph " + i / cellHeight + " row " + i % cellHeight + " sets a bit outside the cell");
            }
        }
        for (int r = 0; r < missing.length; r++) {
            if ((missing[r] & outside) != 0) {
                throw new IllegalArgumentException("missing glyph row " + r + " sets a bit outside the cell");
            }
        }
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;
        this.codepoints = codepoints.clone();
        this.rows = rows.clone();
        this.missing = missing.clone();
    }

    public int cellWidth() {
        return cellWidth;
    }

    public int cellHeight() {
        return cellHeight;
    }

    /** The number of glyphs, the missing glyph not counted. */
    public int size() {
        return codepoints.length;
    }

    /**
     * Glyph index's codepoint; ascending in the index.
     *
     * @throws IndexOutOfBoundsException for an index outside 0 to size() - 1
     */
    public int codepointAt(int index) {
        if (index < 0 || index >= codepoints.length) {
            throw new IndexOutOfBoundsException("glyph " + index + " of " + codepoints.length);
        }
        return codepoints[index];
    }

    /** The glyph index of the codepoint, by binary search; {@link #MISSING} if the table has none for it. */
    public int indexOf(int codepoint) {
        int low = 0;
        int high = codepoints.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int value = codepoints[mid];
            if (value < codepoint) {
                low = mid + 1;
            } else if (value > codepoint) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return MISSING;
    }

    /**
     * Row r (0 = top) of glyph index, or of the missing glyph for {@link #MISSING}, as 0-255: column b is bit 7 - b.
     *
     * @throws IndexOutOfBoundsException for an index outside -1 to size() - 1 or r outside 0 to cellHeight() - 1
     */
    public int row(int index, int r) {
        if (r < 0 || r >= cellHeight) {
            throw new IndexOutOfBoundsException("row " + r + " of " + cellHeight);
        }
        if (index == MISSING) {
            return missing[r] & 0xFF;
        }
        if (index < 0 || index >= codepoints.length) {
            throw new IndexOutOfBoundsException("glyph " + index + " of " + codepoints.length);
        }
        return rows[index * cellHeight + r] & 0xFF;
    }

    /**
     * Whether pixel (b, r) of glyph index, or of the missing glyph for {@link #MISSING}, is set: column b (0 = left) of
     * row r (0 = top). S8's atlas reads glyphs through this.
     *
     * @throws IndexOutOfBoundsException for an index outside -1 to size() - 1, r outside 0 to cellHeight() - 1 or b
     *                                   outside 0 to cellWidth() - 1
     */
    public boolean bit(int index, int r, int b) {
        if (b < 0 || b >= cellWidth) {
            throw new IndexOutOfBoundsException("column " + b + " of " + cellWidth);
        }
        return (row(index, r) >>> 7 - b & 1) != 0;
    }
}
