package io.github.mindbound.opengpu.core.colour;

/**
 * An index8 palette: 256 entries of {@code 0xFFRRGGBB}, which expand reads (28 §5.7). Immutable.
 */
public final class Palette {

    /**
     * M0's palette, until {@code setPalette} arrives in M1 (28:490): 08-C's 6×6×6 cube plus 40 greys. Entry
     * i = 36r + 6g + b for r, g, b in 0-5 has channels 51·level; grey k (0 ≤ k &lt; 40) at entry 216 + k has the value
     * (255·(k + 1) + 20) / 41 in floor division, 6 to 249, none of them a cube level. Entry 0 is black, so a cleared
     * T2 device is black (21:242).
     */
    public static final Palette DEFAULT = new Palette(defaultEntries());

    private static final int SIZE = 256;

    private final int[] argb;

    private Palette(int[] argb) {
        this.argb = argb;
    }

    /**
     * The entry as {@code 0xFFRRGGBB}.
     *
     * @throws ArrayIndexOutOfBoundsException for an index outside 0-255
     */
    public int argb(int index) {
        return argb[index];
    }

    /** 256. */
    public int size() {
        return argb.length;
    }

    private static int[] defaultEntries() {
        int[] entries = new int[SIZE];
        for (int i = 0; i < 216; i++) {
            int r = 51 * (i / 36);
            int g = 51 * (i / 6 % 6);
            int b = 51 * (i % 6);
            entries[i] = 0xFF000000 | r << 16 | g << 8 | b;
        }
        for (int k = 0; k < 40; k++) {
            int v = (255 * (k + 1) + 20) / 41;
            entries[216 + k] = 0xFF000000 | v << 16 | v << 8 | v;
        }
        return entries;
    }
}
