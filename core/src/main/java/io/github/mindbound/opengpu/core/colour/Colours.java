package io.github.mindbound.opengpu.core.colour;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;

/**
 * The colour operand and the surface values (28 §3.4, §5.7; 24:205). A colour is one u32 whose meaning follows the
 * surface format: an index 0-255 on index8, {@code 0xFFRRGGBB} on rgb565, quantized on the CPU by the round-to-nearest
 * {@link #q}. Expand is integer bit replication, never GL's 5_6_5 unorm conversion, so {@code q(expand(v)) = v} for
 * every 565 value. It lives in main because M4's guests expand on the CPU with the host's function (28:488).
 */
public final class Colours {

    private Colours() {}

    /**
     * {@code (c·m + 127) / 255} in integer division: round to nearest, with no exact ties for 0 ≤ c ≤ 255 and m 31 or
     * 63 (24:205). Unchecked, for the hot path.
     */
    public static int q(int c, int m) {
        return (c * m + 127) / 255;
    }

    /**
     * Whether the colour is valid on the format (28 §3.4): index8 takes 0x00000000-0x000000FF, rgb565 takes
     * 0xFF000000-0xFFFFFFFF (alpha 0xFF until M1's blending); false for every other format.
     */
    public static boolean isValid(int format, int colour) {
        switch (format) {
            case Formats.INDEX8:
                return (colour & 0xFFFFFF00) == 0;
            case Formats.RGB565:
                return colour >>> 24 == 0xFF;
            default:
                return false;
        }
    }

    /**
     * The surface value {@code sv(c)} an op writes (28 §3.4, 28:495): the index on index8, {@link #quantize565} on
     * rgb565. The colour must be {@link #isValid valid}; it is not checked.
     *
     * @throws IllegalArgumentException for an undefined format
     */
    public static int sv(int format, int colour) {
        switch (format) {
            case Formats.INDEX8:
                return colour;
            case Formats.RGB565:
                return quantize565(colour);
            default:
                throw new IllegalArgumentException("format " + format + " is undefined");
        }
    }

    /** {@code (q(RR,31) << 11) | (q(GG,63) << 5) | q(BB,31)} of {@code 0x..RRGGBB}; bits 24-31 are ignored. */
    public static int quantize565(int rgb) {
        return q(rgb >>> 16 & 0xFF, 31) << 11 | q(rgb >>> 8 & 0xFF, 63) << 5 | q(rgb & 0xFF, 31);
    }

    /**
     * A 565 value as {@code 0xFFRRGGBB} by bit replication (28 §5.7): {@code r5<<3 | r5>>2}, {@code g6<<2 | g6>>4},
     * {@code b5<<3 | b5>>2}, alpha 255 (24:201). Bits 16-31 of v are ignored.
     */
    public static int expand565(int v) {
        int r5 = v >>> 11 & 0x1F;
        int g6 = v >>> 5 & 0x3F;
        int b5 = v & 0x1F;
        int r = r5 << 3 | r5 >>> 2;
        int g = g6 << 2 | g6 >>> 4;
        int b = b5 << 3 | b5 >>> 2;
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /**
     * A surface value as {@code 0xFFRRGGBB}: {@code palette.argb(value)} on index8, {@link #expand565} on rgb565.
     *
     * @throws IllegalArgumentException for an undefined format
     */
    public static int expand(int format, int value, Palette palette) {
        switch (format) {
            case Formats.INDEX8:
                return palette.argb(value);
            case Formats.RGB565:
                return expand565(value);
            default:
                throw new IllegalArgumentException("format " + format + " is undefined");
        }
    }

    /**
     * A whole surface in the 28 §5.7 byte layout as RGBA8 bytes: R, G, B and 255 per pixel, rows top first, which S8's
     * present-texture check and M4's guests compare. The palette is read on index8 only.
     *
     * @throws IllegalArgumentException if pixels is not {@code surface.byteSize()} bytes long
     */
    public static byte[] expandToRgba(SurfaceDesc surface, byte[] pixels, Palette palette) {
        if (pixels.length != surface.byteSize()) {
            throw new IllegalArgumentException(
                "a " + surface + " surface is " + surface.byteSize() + " bytes, not " + pixels.length);
        }
        int count = surface.pixelCount();
        byte[] rgba = new byte[count * 4];
        boolean index8 = surface.format == Formats.INDEX8;
        for (int i = 0; i < count; i++) {
            int argb = index8 ? palette.argb(pixels[i] & 0xFF)
                : expand565((pixels[2 * i] & 0xFF) | (pixels[2 * i + 1] & 0xFF) << 8);
            rgba[4 * i] = (byte) (argb >>> 16);
            rgba[4 * i + 1] = (byte) (argb >>> 8);
            rgba[4 * i + 2] = (byte) argb;
            rgba[4 * i + 3] = (byte) 0xFF;
        }
        return rgba;
    }
}
