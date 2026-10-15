package io.github.mindbound.opengpu.core.cmd;

/**
 * The clipped ranges of 28 §5.1 and §5.7, shared by the validator's fill accounting (28 §3.5) and S8's executor, which
 * draws exactly the ranges the oracle clips to (28 §5.1, host notes). The arithmetic is 32-bit, which is exact for
 * i16 operands and sides up to {@link io.github.mindbound.opengpu.core.SurfaceDesc#MAX_SIDE}; areas are longs.
 * Nothing here allocates.
 */
public final class Clip {

    private Clip() {}

    /** The pixels of rect (x, y, w, h) on a W×H surface: 0 when w ≤ 0, h ≤ 0 or the rect is off the surface. */
    public static long rectArea(int x, int y, int w, int h, int surfaceW, int surfaceH) {
        int x0 = Math.max(x, 0);
        int x1 = Math.min(x + w, surfaceW);
        int y0 = Math.max(y, 0);
        int y1 = Math.min(y + h, surfaceH);
        return area(x0, x1, y0, y1);
    }

    /**
     * Writes the rect's clipped pixel range {@code x0, y0, x1, y1} into {@code out[0..3]}: half-open,
     * x0 ≤ px &lt; x1 and y0 ≤ py &lt; y1, and empty when x1 ≤ x0 or y1 ≤ y0 (28 §5.7's {@code FILL_RECT}).
     */
    public static void rect(int x, int y, int w, int h, int surfaceW, int surfaceH, int[] out) {
        out[0] = Math.max(x, 0);
        out[1] = Math.max(y, 0);
        out[2] = Math.min(x + w, surfaceW);
        out[3] = Math.min(y + h, surfaceH);
    }

    /**
     * The pixels a {@code BLIT} covers on a W×H surface from a TW×TH texture, {@code KEY0}-skipped texels included
     * (28 §3.5): the size of {@link #blit}'s ranges.
     */
    public static long blitArea(int sx, int sy, int w, int h, int dx, int dy, int texW, int texH, int surfaceW,
        int surfaceH) {
        int i0 = Math.max(0, Math.max(-sx, -dx));
        int i1 = Math.min(w, Math.min(texW - sx, surfaceW - dx));
        int j0 = Math.max(0, Math.max(-sy, -dy));
        int j1 = Math.min(h, Math.min(texH - sy, surfaceH - dy));
        return area(i0, i1, j0, j1);
    }

    /**
     * Writes 28 §5.7's {@code BLIT} ranges {@code i0, j0, i1, j1} into {@code out[0..3]}: for i0 ≤ i &lt; i1 and
     * j0 ≤ j &lt; j1, texel (sx + i, sy + j) is inside the texture and pixel (dx + i, dy + j) inside the surface; empty
     * when i1 ≤ i0 or j1 ≤ j0.
     */
    public static void blit(int sx, int sy, int w, int h, int dx, int dy, int texW, int texH, int surfaceW,
        int surfaceH, int[] out) {
        out[0] = Math.max(0, Math.max(-sx, -dx));
        out[1] = Math.max(0, Math.max(-sy, -dy));
        out[2] = Math.min(w, Math.min(texW - sx, surfaceW - dx));
        out[3] = Math.min(h, Math.min(texH - sy, surfaceH - dy));
    }

    private static long area(int x0, int x1, int y0, int y1) {
        return x1 > x0 && y1 > y0 ? (long) (x1 - x0) * (y1 - y0) : 0;
    }
}
