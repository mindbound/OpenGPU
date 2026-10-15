package io.github.mindbound.opengpu.core.cmd;

/**
 * The OpenGPU format codes of surfaces and textures (28 §6.2), never GL enums (24:238). 0 is invalid and codes from 3
 * are reserved for M2's texture formats.
 */
public final class Formats {

    public static final int INVALID = 0;
    /** 1 byte per pixel, a palette index. */
    public static final int INDEX8 = 1;
    /** 2 bytes per pixel, a little-endian u16 with r5 in bits 15-11, g6 in 10-5 and b5 in 4-0 (28 §5.7). */
    public static final int RGB565 = 2;

    private Formats() {}

    public static boolean isDefined(int format) {
        return format == INDEX8 || format == RGB565;
    }

    /** @throws IllegalArgumentException for an undefined format */
    public static int bytesPerPixel(int format) {
        switch (format) {
            case INDEX8:
                return 1;
            case RGB565:
                return 2;
            default:
                throw undefined(format);
        }
    }

    /**
     * "index8" or "rgb565", the names that {@code getCaps().format} and {@code createTexture}'s format argument use
     * (28 §8.1, §6.2).
     *
     * @throws IllegalArgumentException for an undefined format
     */
    public static String name(int format) {
        switch (format) {
            case INDEX8:
                return "index8";
            case RGB565:
                return "rgb565";
            default:
                throw undefined(format);
        }
    }

    /** The code of a format name, or {@link #INVALID} for null or any other string. */
    public static int byName(String name) {
        if ("index8".equals(name)) {
            return INDEX8;
        }
        if ("rgb565".equals(name)) {
            return RGB565;
        }
        return INVALID;
    }

    private static IllegalArgumentException undefined(int format) {
        return new IllegalArgumentException("format " + format + " is undefined");
    }
}
