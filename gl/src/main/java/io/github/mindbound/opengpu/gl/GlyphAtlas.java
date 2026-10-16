package io.github.mindbound.opengpu.gl;

import io.github.mindbound.opengpu.core.font.GlyphTable;

/**
 * A glyph table's atlas layout (28 §7.5; S8 plan §3.7): pure layout and texels, no GL. Cell c holds glyph index c, and
 * the cell after the last glyph, {@code glyphs.size()}, holds the missing glyph. Cells run left to right in rows of
 * {@code MAX_SIDE / cellWidth} (170 at 6×13), top row first; texel (b, r) of a cell is 255 where
 * {@link GlyphTable#bit} is set and 0 elsewhere, row 0 of the texels the top row (no flip, 28:409). The host uploads it
 * once as {@code GL_R8} (H33), built from the same {@link GlyphTable} object the oracle uses (28:634). Immutable.
 */
public final class GlyphAtlas {

    /** GL 3.3's guaranteed {@code MAX_TEXTURE_SIZE} (GL33:14799-14808), the atlas's fixed bound (H64). */
    public static final int MAX_SIDE = 1024;

    private final GlyphTable glyphs;
    private final int columns;
    private final int rows;
    private final int cells;

    /**
     * @throws IllegalArgumentException when {@code size() + 1} cells do not fit {@code MAX_SIDE x MAX_SIDE} (13 260 at
     *                                  6×13)
     */
    public GlyphAtlas(GlyphTable glyphs) {
        this.glyphs = glyphs;
        this.columns = MAX_SIDE / glyphs.cellWidth();
        int maxRows = MAX_SIDE / glyphs.cellHeight();
        long cells = (long) glyphs.size() + 1;
        if (cells > (long) columns * maxRows) {
            throw new IllegalArgumentException(
                cells + " cells of "
                    + glyphs.cellWidth()
                    + "x"
                    + glyphs.cellHeight()
                    + " do not fit a "
                    + MAX_SIDE
                    + "x"
                    + MAX_SIDE
                    + " atlas, which holds "
                    + (long) columns * maxRows);
        }
        this.cells = (int) cells;
        this.rows = (this.cells + columns - 1) / columns;
    }

    public GlyphTable glyphs() {
        return glyphs;
    }

    /** Cells per row: {@code MAX_SIDE / cellWidth}, 170 at 6×13. */
    public int columns() {
        return columns;
    }

    /** Rows of cells: {@code ceil((size + 1) / columns)}. */
    public int rows() {
        return rows;
    }

    /** {@code size() + 1}: every glyph and the missing glyph. */
    public int cells() {
        return cells;
    }

    /** {@code columns * cellWidth}: 1 020 at 6×13. */
    public int width() {
        return columns * glyphs.cellWidth();
    }

    /** {@code rows * cellHeight}: 26 for the synthetic table, 325 for font 0. */
    public int height() {
        return rows * glyphs.cellHeight();
    }

    /**
     * The cell of a glyph index: the index itself, or {@code glyphs.size()} for {@link GlyphTable#MISSING}.
     *
     * @throws IndexOutOfBoundsException for an index outside -1 to size() - 1
     */
    public int cell(int glyphIndex) {
        if (glyphIndex == GlyphTable.MISSING) {
            return glyphs.size();
        }
        if (glyphIndex < 0 || glyphIndex >= glyphs.size()) {
            throw new IndexOutOfBoundsException("glyph " + glyphIndex + " of " + glyphs.size());
        }
        return glyphIndex;
    }

    /** The cell's leftmost texel column. */
    public int cellX(int cell) {
        return cell % columns * glyphs.cellWidth();
    }

    /** The cell's top texel row. */
    public int cellY(int cell) {
        return cell / columns * glyphs.cellHeight();
    }

    /** {@code width() * height()} bytes, 0 or 255, row-major with row 0 the top row; a fresh array. */
    public byte[] texels() {
        int width = width();
        byte[] texels = new byte[width * height()];
        int cw = glyphs.cellWidth();
        int ch = glyphs.cellHeight();
        for (int c = 0; c < cells; c++) {
            int index = c == glyphs.size() ? GlyphTable.MISSING : c;
            int x0 = cellX(c);
            int y0 = cellY(c);
            for (int r = 0; r < ch; r++) {
                for (int b = 0; b < cw; b++) {
                    if (glyphs.bit(index, r, b)) {
                        texels[(y0 + r) * width + x0 + b] = (byte) 0xFF;
                    }
                }
            }
        }
        return texels;
    }
}
