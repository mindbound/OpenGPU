package io.github.mindbound.opengpu.core;

import java.util.Locale;

import io.github.mindbound.opengpu.core.cmd.Formats;

/**
 * A device's surface (28 §2.4): its tier, its size and its format. The codec and the tier-E oracle read the width, the
 * height and the format only, never the tier (28 §1.1: "the codec takes the surface format and size as parameters,
 * never a tier"); the tier is for the SPI and {@code getCaps} (S7), and 0 marks an untiered test surface.
 */
public final class SurfaceDesc {

    /**
     * A sanity bound on either side: it keeps W·H·bpp and any one record's fill far below 2^31, so the 32-bit clip
     * arithmetic of 28 §5.1 is exact. M0's largest surface is T3's 640×400 (28 §8.1).
     */
    public static final int MAX_SIDE = 4096;

    private static final SurfaceDesc T2 = new SurfaceDesc(2, 320, 200, Formats.INDEX8);
    private static final SurfaceDesc T3 = new SurfaceDesc(3, 640, 400, Formats.RGB565);

    /** 0 for an untiered test surface; 2 or 3 in M0 (T1 joins in M1, 28 §1.1). */
    public final int tier;
    /** 1 to {@link #MAX_SIDE}. */
    public final int width;
    /** 1 to {@link #MAX_SIDE}. */
    public final int height;
    /** {@link Formats#INDEX8} or {@link Formats#RGB565} (28 §6.2). */
    public final int format;

    /**
     * @throws IllegalArgumentException for a tier outside 0-3, a side outside 1-{@link #MAX_SIDE} or an undefined
     *                                  format
     */
    public SurfaceDesc(int tier, int width, int height, int format) {
        if (tier < 0 || tier > 3) {
            throw new IllegalArgumentException("tier " + tier + " is outside 0..3");
        }
        if (width < 1 || width > MAX_SIDE || height < 1 || height > MAX_SIDE) {
            throw new IllegalArgumentException(
                "surface " + width + "x" + height + " has a side outside 1.." + MAX_SIDE);
        }
        if (!Formats.isDefined(format)) {
            throw new IllegalArgumentException("surface format " + format + " is undefined");
        }
        this.tier = tier;
        this.width = width;
        this.height = height;
        this.format = format;
    }

    /**
     * The tier's surface (28 §8.1): 2 is 320×200 index8 and 3 is 640×400 rgb565. M1 adds 1, 160×100 index8.
     *
     * @throws IllegalArgumentException for any other tier
     */
    public static SurfaceDesc forTier(int tier) {
        switch (tier) {
            case 2:
                return T2;
            case 3:
                return T3;
            default:
                throw new IllegalArgumentException("M0 builds tiers 2 and 3 only, not " + tier);
        }
    }

    public int bytesPerPixel() {
        return Formats.bytesPerPixel(format);
    }

    public int pixelCount() {
        return width * height;
    }

    /** The length of the surface in the 28 §5.7 byte layout: W·H·bpp. */
    public int byteSize() {
        return width * height * bytesPerPixel();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SurfaceDesc)) {
            return false;
        }
        SurfaceDesc that = (SurfaceDesc) other;
        return tier == that.tier && width == that.width && height == that.height && format == that.format;
    }

    @Override
    public int hashCode() {
        return ((tier * 31 + width) * 31 + height) * 31 + format;
    }

    /** For example "T2 320x200 index8", or "320x200 index8" for tier 0. */
    @Override
    public String toString() {
        String size = String.format(Locale.ROOT, "%dx%d %s", width, height, Formats.name(format));
        return tier == 0 ? size : "T" + tier + " " + size;
    }
}
