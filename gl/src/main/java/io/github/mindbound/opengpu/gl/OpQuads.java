package io.github.mindbound.opengpu.gl;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Clip;
import io.github.mindbound.opengpu.core.cmd.Fonts;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.OpVisitor;
import io.github.mindbound.opengpu.core.cmd.Utf8;
import io.github.mindbound.opengpu.core.colour.Colours;
import io.github.mindbound.opengpu.core.font.GlyphTable;

/**
 * S8 plan §3.6's op-to-quad rules as an {@link OpVisitor} with no GL: each op becomes integer-aligned quads (24:197)
 * covering exactly the ranges the oracle clips to, computed on the CPU with {@link Clip} (28:412-413), so no vertex
 * leaves the surface and no {@code texelFetch} leaves its texture (GL33:4324-4349). A quad is its pixel rect
 * [x0, x1) × [y0, y1), the offset from a window pixel to the texel it fetches, and four values; the sink receives it
 * with the program and the texture it needs. Values are the precomputed {@code sv(c)} (28:414). Not thread-safe.
 */
final class OpQuads implements OpVisitor {

    /** No texture: {@code CLEAR} and {@code FILL_RECT}. */
    static final int NO_TEXTURE = 0;
    /** The host's glyph atlas: {@code TEXT}. */
    static final int ATLAS = -1;
    /** The device's surface: expand. Texture handles are positive (28 §6.1), so these never clash with one. */
    static final int SURFACE = -2;

    /** {@code BLIT}'s and {@code TEXT}'s flags bit 0: {@code KEY0}, {@code TRANSPARENT_BG} (28 §5.5-§5.6). */
    private static final int FLAG_BIT0 = 1;

    /** Receives each quad. */
    interface QuadSink {

        void quad(int program, int texture, int x0, int y0, int x1, int y1, int ox, int oy, int v0, int v1, int v2,
            int v3);
    }

    /** The sides of the device's textures, by full handle (O7). */
    interface TextureSizes {

        /** @throws IllegalStateException for a handle the device does not hold */
        int textureWidth(int handle);

        /** @throws IllegalStateException for a handle the device does not hold */
        int textureHeight(int handle);
    }

    private final int width;
    private final int height;
    private final int format;
    private final GlyphAtlas atlas;
    private final GlyphTable glyphs;
    private final TextureSizes textures;
    private final QuadSink sink;
    private final int blitProgram;
    private final int[] range = new int[4];

    /** @throws IllegalArgumentException unless the atlas's glyphs have font 0's 6×13 cell (28 §7.3) */
    OpQuads(SurfaceDesc surface, GlyphAtlas atlas, TextureSizes textures, QuadSink sink) {
        if (atlas.glyphs()
            .cellWidth() != Fonts.FONT_0_CELL_WIDTH
            || atlas.glyphs()
                .cellHeight() != Fonts.FONT_0_CELL_HEIGHT) {
            throw new IllegalArgumentException("font 0's cell is 6x13 (28 §7.3)");
        }
        this.width = surface.width;
        this.height = surface.height;
        this.format = surface.format;
        this.atlas = atlas;
        this.glyphs = atlas.glyphs();
        this.textures = textures;
        this.sink = sink;
        this.blitProgram = surface.format == Formats.INDEX8 ? Shaders.BLIT_INDEX8 : Shaders.BLIT_RGB565;
    }

    /** 28 §5.3: the whole surface. */
    @Override
    public void clear(int colour) {
        sink.quad(Shaders.FILL, NO_TEXTURE, 0, 0, width, height, 0, 0, Colours.sv(format, colour), 0, 0, 0);
    }

    /** 28 §5.4: the clipped rect, skipped when empty. */
    @Override
    public void fillRect(int x, int y, int w, int h, int colour) {
        Clip.rect(x, y, w, h, width, height, range);
        if (range[2] > range[0] && range[3] > range[1]) {
            sink.quad(
                Shaders.FILL,
                NO_TEXTURE,
                range[0],
                range[1],
                range[2],
                range[3],
                0,
                0,
                Colours.sv(format, colour),
                0,
                0,
                0);
        }
    }

    /**
     * 28 §5.5: {@code Clip.blit}'s ranges (28:500-501), the quad (dx + i0, dy + j0, dx + i1, dy + j1) and the offset
     * (sx − dx, sy − dy), so window pixel (dx + i, dy + j) fetches texel (sx + i, sy + j); skipped when empty.
     */
    @Override
    public void blit(int texture, int sx, int sy, int w, int h, int dx, int dy, int flags) {
        Clip.blit(
            sx,
            sy,
            w,
            h,
            dx,
            dy,
            textures.textureWidth(texture),
            textures.textureHeight(texture),
            width,
            height,
            range);
        if (range[2] > range[0] && range[3] > range[1]) {
            sink.quad(
                blitProgram,
                texture,
                dx + range[0],
                dy + range[1],
                dx + range[2],
                dy + range[3],
                sx - dx,
                sy - dy,
                flags & FLAG_BIT0,
                0,
                0,
                0);
        }
    }

    /**
     * 28 §5.6: codepoint k's cell [x + 6k, x + 6k + 6) × [y, y + 13), clipped, with the offset from the cell's origin
     * to its atlas cell, so pixel (x0 + b, y + r) fetches glyph row r, column b. A record whose rows are all off the
     * surface is skipped whole, and the scan stops at the first cell at or past the right edge, since cells never wrap.
     */
    @Override
    public void text(int x, int y, int fg, int bg, int flags, int font, byte[] segment, int stringOffset,
        int stringLength) {
        assert font == Fonts.FONT_0 : "TEXT font " + font + " passed validation";
        int cw = Fonts.FONT_0_CELL_WIDTH;
        int ch = Fonts.FONT_0_CELL_HEIGHT;
        if (y >= height || y + ch <= 0) {
            return;
        }
        int y0 = Math.max(y, 0);
        int y1 = Math.min(y + ch, height);
        int fgValue = Colours.sv(format, fg);
        int bgValue = Colours.sv(format, bg);
        int transparent = flags & FLAG_BIT0;
        int k = 0;
        int i = 0;
        while (i < stringLength) {
            int x0 = x + cw * k;
            if (x0 >= width) {
                return;
            }
            int lead = segment[stringOffset + i];
            if (x0 + cw > 0) {
                int cell = atlas.cell(glyphs.indexOf(Utf8.codepointAt(segment, stringOffset + i)));
                sink.quad(
                    Shaders.TEXT,
                    ATLAS,
                    Math.max(x0, 0),
                    y0,
                    Math.min(x0 + cw, width),
                    y1,
                    atlas.cellX(cell) - x0,
                    atlas.cellY(cell) - y,
                    fgValue,
                    bgValue,
                    transparent,
                    0);
            }
            k++;
            i += Utf8.sequenceLength(lead);
        }
    }
}
