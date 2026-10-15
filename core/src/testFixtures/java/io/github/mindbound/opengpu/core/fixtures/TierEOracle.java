package io.github.mindbound.opengpu.core.fixtures;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.HandleTypes;
import io.github.mindbound.opengpu.core.cmd.OpVisitor;
import io.github.mindbound.opengpu.core.cmd.SegmentReader;
import io.github.mindbound.opengpu.core.cmd.Utf8;
import io.github.mindbound.opengpu.core.colour.Colours;
import io.github.mindbound.opengpu.core.colour.Palette;
import io.github.mindbound.opengpu.core.font.GlyphTable;

/**
 * The tier-E oracle (28 §5.7; 23:147-149): a test-only CPU model of one device's surface, which lives in
 * {@code :core}'s test fixtures and so ships in no jar. It is built from the device's {@link SurfaceDesc} and font 0's
 * glyph table G, and its state is the surface S, one value per pixel and zero at the start, and a map T from the full
 * handle (O7) to a texture's (w, h, format, texels). It runs the records a device offers, in offered order: the
 * {@code TEX_*} records, {@code RESET} and each {@code FRAME}'s segments through the main {@link SegmentReader}.
 * S7's {@code FakeRenderHost} plugs it in as its record renderer (28 §2.4), and S8 compares {@code GlTestHost} with it
 * byte for byte (28 §11.4).
 * <p>
 * The ops follow 28 §5.3-§5.6 and 28:494-506 literally, with this class's own loops, minima and maxima rather than
 * {@code core.cmd.Clip}, which S8's executor uses, and with its own transcription of 28:495's {@code sv} and {@code q}
 * rather than {@code Colours.sv}, so that S8 is compared with an independent reading of 28. Only the UTF-8 decoding of
 * a validated string is main's ({@link Utf8#codepointAt}, {@link Utf8#sequenceLength}).
 * <p>
 * Every surface access and every texel access goes through one bounds-checked accessor that throws
 * {@code IllegalStateException} out of range, so the fuzzer's P4, "writes in-bounds only" (28 §11.3), is observable:
 * a flat index alone would wrap a negative x into the previous row. A surface write must also hold a value the format
 * can store. The oracle trusts validation but refuses what validation never accepts (an invalid colour, an undefined
 * flag bit, a font other than 0, an absent texture or one of the other format), with an
 * {@code IllegalStateException}. Not thread-safe.
 */
public final class TierEOracle {

    /** Font 0's cell (28 §5.6, §7.3). */
    private static final int CELL_W = 6;
    private static final int CELL_H = 13;
    /** The largest texture side (28 §6.2). */
    private static final int MAX_TEXTURE_SIDE = 1024;
    /** {@code BLIT}'s flags bit 0 (28 §5.5). */
    private static final int KEY0 = 0x01;
    /** {@code TEXT}'s flags bit 0 (28 §5.6). */
    private static final int TRANSPARENT_BG = 0x01;

    private final SurfaceDesc surface;
    private final GlyphTable glyphs;
    private final int width;
    private final int height;
    private final int format;
    /** The largest value a pixel of the format holds: an index, or a 565 value. */
    private final int maxValue;
    /** S: pixel (x, y) at {@code y * width + x}; read and written only through {@link #surfaceIndex}. */
    private final int[] pixels;
    /** T, keyed by the full 31-bit handle (O7). */
    private final Map<Integer, Texture> textures = new HashMap<>();
    private final OpVisitor ops = new Ops();

    /**
     * @throws IllegalArgumentException unless the glyph table has font 0's 6×13 cell (28 §7.3)
     */
    public TierEOracle(SurfaceDesc surface, GlyphTable glyphs) {
        if (surface == null || glyphs == null) {
            throw new NullPointerException("surface and glyphs");
        }
        if (glyphs.cellWidth() != CELL_W || glyphs.cellHeight() != CELL_H) {
            throw new IllegalArgumentException(
                "font 0's cell is 6x13 (28 §7.3), not " + glyphs.cellWidth() + "x" + glyphs.cellHeight());
        }
        this.surface = surface;
        this.glyphs = glyphs;
        this.width = surface.width;
        this.height = surface.height;
        this.format = surface.format;
        this.maxValue = format == Formats.INDEX8 ? 0xFF : 0xFFFF;
        this.pixels = new int[width * height];
    }

    public SurfaceDesc surface() {
        return surface;
    }

    public GlyphTable glyphs() {
        return glyphs;
    }

    // The records, in offered order (28 §2.3)

    /**
     * {@code TEX_CREATE}: {@code T[h] = (w, h, zeros)} (28:497), zeros being index 0 or 565 black (28 §6.2).
     *
     * @throws IllegalArgumentException for a handle that is not a well-formed texture handle (28 §6.1), a side outside
     *                                  1-1 024 or an undefined format
     * @throws IllegalStateException    if the handle is already present
     */
    public void createTexture(int handle, int width, int height, int format) {
        if (!Handles.isWellFormed(handle) || Handles.type(handle) != HandleTypes.TEXTURE) {
            throw new IllegalArgumentException(handleName(handle) + " is not a texture handle (28 §6.1)");
        }
        if (width < 1 || width > MAX_TEXTURE_SIDE || height < 1 || height > MAX_TEXTURE_SIDE) {
            throw new IllegalArgumentException(
                "texture " + width + "x" + height + " has a side outside 1.." + MAX_TEXTURE_SIDE + " (28 §6.2)");
        }
        if (!Formats.isDefined(format)) {
            throw new IllegalArgumentException("texture format " + format + " is undefined (28 §6.2)");
        }
        if (textures.containsKey(handle)) {
            throw new IllegalStateException(handleName(handle) + " is already present");
        }
        textures.put(handle, new Texture(handle, width, height, format));
    }

    /**
     * {@code TEX_UPDATE}: the payload's rows copied into {@code T[h]} (28:497). The payload is the region in the
     * 28 §5.7 layout: rows top first, w·bpp bytes each with no padding, rgb565 as LE u16 (28 §6.3).
     *
     * @throws IllegalStateException    if the handle is absent
     * @throws IllegalArgumentException for a region outside the texture (0 ≤ x ≤ TW, 1 ≤ w ≤ TW − x and likewise for
     *                                  y, 28 §6.3) or a payload that is not w·h·bpp bytes
     */
    public void updateTexture(int handle, int x, int y, int width, int height, byte[] payload) {
        Texture t = texture(handle);
        if (x < 0 || x > t.width
            || width < 1
            || width > t.width - x
            || y < 0
            || y > t.height
            || height < 1
            || height > t.height - y) {
            throw new IllegalArgumentException(
                String.format(
                    Locale.ROOT,
                    "region (%d, %d) %dx%d is outside %s's %dx%d (28 §6.3)",
                    x,
                    y,
                    width,
                    height,
                    handleName(handle),
                    t.width,
                    t.height));
        }
        int bpp = Formats.bytesPerPixel(t.format);
        if (payload.length != width * height * bpp) {
            throw new IllegalArgumentException(
                "payload of " + payload.length
                    + " bytes for a "
                    + width
                    + "x"
                    + height
                    + " region, expected "
                    + width * height * bpp);
        }
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int p = (j * width + i) * bpp;
                int value = bpp == 1 ? payload[p] & 0xFF : (payload[p] & 0xFF) | (payload[p + 1] & 0xFF) << 8;
                t.texels[texelIndex(t, x + i, y + j)] = value;
            }
        }
    }

    /**
     * {@code TEX_FREE}: remove {@code T[h]} (28:497).
     *
     * @throws IllegalStateException if the handle is absent
     */
    public void freeTexture(int handle) {
        if (textures.remove(handle) == null) {
            throw new IllegalStateException(handleName(handle) + " is not present");
        }
    }

    /**
     * {@code RESET}: {@code T = {}; S = the record's pixels} (28:496), K's pixels in the 28 §5.7 layout.
     *
     * @throws IllegalArgumentException unless pixels is exactly {@code surface.byteSize()} bytes
     */
    public void reset(byte[] pixels) {
        if (pixels.length != surface.byteSize()) {
            throw new IllegalArgumentException(
                "a " + surface + " surface is " + surface.byteSize() + " bytes, not " + pixels.length);
        }
        textures.clear();
        boolean index8 = format == Formats.INDEX8;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                int value = index8 ? pixels[i] & 0xFF : (pixels[2 * i] & 0xFF) | (pixels[2 * i + 1] & 0xFF) << 8;
                write(x, y, value);
            }
        }
    }

    /** {@code FRAME}: its segments in order (28 §3.2: a segment is not a scope, and M0 has no frame state). */
    public void runFrame(byte[]... segments) {
        for (byte[] segment : segments) {
            runSegment(segment);
        }
    }

    /** One validated segment's records, in order, through the main reader. */
    public void runSegment(byte[] segment) {
        SegmentReader.read(segment, ops);
    }

    // State

    /** The surface in the 28 §5.7 layout, a fresh copy: rows top first; index8 1 B per pixel, rgb565 a LE u16. */
    public byte[] pixels() {
        boolean index8 = format == Formats.INDEX8;
        byte[] out = new byte[surface.byteSize()];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                int value = pixels[surfaceIndex(x, y, false)];
                if (index8) {
                    out[i] = (byte) value;
                } else {
                    out[2 * i] = (byte) value;
                    out[2 * i + 1] = (byte) (value >>> 8);
                }
            }
        }
        return out;
    }

    /**
     * Pixel (x, y): an index, or a 565 value.
     *
     * @throws IllegalStateException outside the surface, from the bounds-checked accessor
     */
    public int pixel(int x, int y) {
        return pixels[surfaceIndex(x, y, false)];
    }

    /**
     * Texel (x, y) of a present texture: an index, or a 565 value.
     *
     * @throws IllegalStateException if the handle is absent, or outside the texture, from the bounds-checked accessor
     */
    public int texel(int handle, int x, int y) {
        Texture t = texture(handle);
        return t.texels[texelIndex(t, x, y)];
    }

    /** The surface expanded to RGBA8 (28 §5.7): {@code Colours.expandToRgba(surface, pixels(), palette)}. */
    public byte[] expandedRgba(Palette palette) {
        return Colours.expandToRgba(surface, pixels(), palette);
    }

    public boolean hasTexture(int handle) {
        return textures.containsKey(handle);
    }

    public int textureCount() {
        return textures.size();
    }

    // 28:495, transcribed here rather than taken from Colours

    /** {@code q(v, m) = (v*m + 127) / 255} in integer division (28:495; 24:205). */
    private static int q(int v, int m) {
        return (v * m + 127) / 255;
    }

    /** {@code sv(c)}: index8 c; rgb565 {@code (q(c>>16&255,31)<<11) | (q(c>>8&255,63)<<5) | q(c&255,31)} (28:495). */
    private int sv(int c) {
        if (format == Formats.INDEX8) {
            return c;
        }
        return q(c >> 16 & 255, 31) << 11 | q(c >> 8 & 255, 63) << 5 | q(c & 255, 31);
    }

    /** 28 §3.4's accepted values, which validation has checked: index8 {@code 0x000000II}, rgb565 alpha 0xFF. */
    private void requireColour(String op, String field, int c) {
        boolean valid = format == Formats.INDEX8 ? c >= 0 && c <= 255 : c >>> 24 == 0xFF;
        if (!valid) {
            throw unvalidated(
                String.format(Locale.ROOT, "%s %s 0x%08X is not a %s colour", op, field, c, formatName()));
        }
    }

    // The bounds-checked accessors: every surface and texel access goes through these two

    private int surfaceIndex(int x, int y, boolean writing) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IllegalStateException(
                String.format(
                    Locale.ROOT,
                    "oracle %s outside the %dx%d surface at (%d, %d)",
                    writing ? "wrote" : "read",
                    width,
                    height,
                    x,
                    y));
        }
        return y * width + x;
    }

    private static int texelIndex(Texture t, int x, int y) {
        if (x < 0 || x >= t.width || y < 0 || y >= t.height) {
            throw new IllegalStateException(
                String.format(
                    Locale.ROOT,
                    "oracle accessed %s outside its %dx%d at (%d, %d)",
                    handleName(t.handle),
                    t.width,
                    t.height,
                    x,
                    y));
        }
        return y * t.width + x;
    }

    /** S[x, y] = value, through {@link #surfaceIndex}; the value must fit the format. */
    private void write(int x, int y, int value) {
        int i = surfaceIndex(x, y, true);
        if (value < 0 || value > maxValue) {
            throw new IllegalStateException(
                String.format(
                    Locale.ROOT,
                    "oracle wrote 0x%X at (%d, %d), which %s cannot hold",
                    value,
                    x,
                    y,
                    formatName()));
        }
        pixels[i] = value;
    }

    private Texture texture(int handle) {
        Texture t = textures.get(handle);
        if (t == null) {
            throw new IllegalStateException(handleName(handle) + " is not present");
        }
        return t;
    }

    private String formatName() {
        return Formats.name(format);
    }

    private static String handleName(int handle) {
        return String.format(Locale.ROOT, "texture 0x%08X", handle);
    }

    private static IllegalStateException unvalidated(String what) {
        return new IllegalStateException(what + ": validation rejects it, so the oracle never runs it");
    }

    /** A texture of T: (w, h, format, texels), texel (x, y) at {@code y * width + x} (28 §5.7). */
    private static final class Texture {

        final int handle;
        final int width;
        final int height;
        final int format;
        final int[] texels;

        Texture(int handle, int width, int height, int format) {
            this.handle = handle;
            this.width = width;
            this.height = height;
            this.format = format;
            this.texels = new int[width * height];
        }
    }

    /** The ops of 28 §5.3-§5.6 and 28:498-505. */
    private final class Ops implements OpVisitor {

        /** 28:498: for every pixel, S = sv(c). */
        @Override
        public void clear(int c) {
            requireColour("CLEAR", "colour", c);
            int v = sv(c);
            for (int py = 0; py < height; py++) {
                for (int px = 0; px < width; px++) {
                    write(px, py, v);
                }
            }
        }

        /** 28:499: for max(y,0) ≤ py &lt; min(y+h,H), max(x,0) ≤ px &lt; min(x+w,W): S[px,py] = sv(c). */
        @Override
        public void fillRect(int x, int y, int w, int h, int c) {
            requireColour("FILL_RECT", "colour", c);
            int v = sv(c);
            // x + w and y + h in 32-bit arithmetic, which i16 operands never overflow (28 §5.1)
            for (int py = Math.max(y, 0); py < Math.min(y + h, height); py++) {
                for (int px = Math.max(x, 0); px < Math.min(x + w, width); px++) {
                    write(px, py, v);
                }
            }
        }

        /**
         * 28:500-501: i0 = max(0,-sx,-dx), i1 = min(w, TW-sx, W-dx); j0 = max(0,-sy,-dy), j1 = min(h, TH-sy, H-dy);
         * for j0 ≤ j &lt; j1, i0 ≤ i &lt; i1: v = T[sx+i, sy+j]; if !(KEY0 &amp;&amp; v == 0): S[dx+i, dy+j] = v. The
         * texel is copied raw (28 §5.5).
         */
        @Override
        public void blit(int tex, int sx, int sy, int w, int h, int dx, int dy, int flags) {
            Texture t = textures.get(tex);
            if (t == null) {
                throw unvalidated("BLIT of absent " + handleName(tex));
            }
            if (t.format != format) {
                throw unvalidated(
                    "BLIT of " + Formats.name(t.format) + " " + handleName(tex) + " onto " + formatName());
            }
            if ((flags & ~KEY0) != 0) {
                throw unvalidated(String.format(Locale.ROOT, "BLIT flags 0x%02X", flags));
            }
            boolean key0 = (flags & KEY0) != 0;
            int i0 = Math.max(0, Math.max(-sx, -dx));
            int i1 = Math.min(w, Math.min(t.width - sx, width - dx));
            int j0 = Math.max(0, Math.max(-sy, -dy));
            int j1 = Math.min(h, Math.min(t.height - sy, height - dy));
            for (int j = j0; j < j1; j++) {
                for (int i = i0; i < i1; i++) {
                    int v = t.texels[texelIndex(t, sx + i, sy + j)];
                    if (!(key0 && v == 0)) {
                        write(dx + i, dy + j, v);
                    }
                }
            }
        }

        /**
         * 28:502-505: codepoint k (0-based) fills the cell [x + 6k, x + 6k + 6) × [y, y + 13) from G(cp), or G's
         * missing glyph; for r &lt; 13, b &lt; 6 with (x+6k+b, y+r) on the surface, a set bit {@code g[r] >> (7-b) & 1}
         * writes sv(fg), and a clear bit sv(bg) unless {@code TRANSPARENT_BG}. Cells are clipped and never wrap
         * (28 §5.6).
         */
        @Override
        public void text(int x, int y, int fg, int bg, int flags, int font, byte[] segment, int stringOffset,
            int stringLength) {
            // fg, then bg, valid even when bg is unused; then the flags and the font (28 §5.6)
            requireColour("TEXT", "fg", fg);
            requireColour("TEXT", "bg", bg);
            if ((flags & ~TRANSPARENT_BG) != 0) {
                throw unvalidated(String.format(Locale.ROOT, "TEXT flags 0x%02X", flags));
            }
            if (font != 0) {
                throw unvalidated("TEXT font " + font + " (M0 defines font 0 only)");
            }
            boolean transparent = (flags & TRANSPARENT_BG) != 0;
            int fgValue = sv(fg);
            int bgValue = sv(bg);
            int k = 0;
            int i = 0;
            while (i < stringLength) {
                int size = sequenceLength(segment[stringOffset + i], i);
                if (size > stringLength - i) {
                    throw unvalidated("TEXT sequence at string byte " + i + " runs past the string's end");
                }
                int cp = Utf8.codepointAt(segment, stringOffset + i);
                // indexOf gives GlyphTable.MISSING (-1) for a codepoint without a glyph, and row(-1, r) is the box
                int g = glyphs.indexOf(cp);
                for (int r = 0; r < CELL_H; r++) {
                    for (int b = 0; b < CELL_W; b++) {
                        int px = x + CELL_W * k + b;
                        int py = y + r;
                        if (px >= 0 && px < width && py >= 0 && py < height) {
                            if ((glyphs.row(g, r) >> (7 - b) & 1) != 0) {
                                write(px, py, fgValue);
                            } else if (!transparent) {
                                write(px, py, bgValue);
                            }
                        }
                    }
                }
                k = k + 1;
                i += size;
            }
        }

        private int sequenceLength(byte lead, int index) {
            try {
                return Utf8.sequenceLength(lead);
            } catch (IllegalArgumentException e) {
                throw unvalidated("TEXT string byte " + index + ": " + e.getMessage());
            }
        }
    }
}
