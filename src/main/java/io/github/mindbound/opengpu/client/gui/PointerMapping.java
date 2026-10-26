package io.github.mindbound.opengpu.client.gui;

/**
 * Where the display GUI draws a W × H picture, and which surface pixel a mouse event points at (20:173; 15:185-186;
 * 03:51; S12 plan P12.3, P12.4 with amendment F1). Everything is in window pixels, Minecraft's {@code displayWidth} and
 * {@code displayHeight}, never {@code ScaledResolution}'s GUI units, whose integers lose the fraction precise mode
 * reports (K9): so a change of GUI scale moves neither the picture nor the cursor's mapping.
 * <ul>
 * <li><b>Magnification.</b> The largest integer {@code m ≥ 1} with {@code W·m} and {@code H·m} inside the window less
 * {@value #MARGIN} pixels on each side; when even 1 does not fit, {@code 1/k} for the smallest integer {@code k ≥ 2}
 * that fits, so pixels decimate evenly (15:186's integer magnification) [proposal].</li>
 * <li><b>Place.</b> Centred, its top-left corner on the whole window pixel {@code (left, top)}, counted from the
 * window's top-left, rounded down when the room left over is odd.</li>
 * <li><b>A mouse event</b> at LWJGL's {@code (eventX, eventY)}, whose y grows upward from the bottom row, points at
 * its window pixel's centre: {@code px = (eventX + 0.5 − left) / m} and
 * {@code py = (windowHeight − eventY − 0.5 − top) / m}. At {@code m = 1} that is {@code k + 0.5} for pixel {@code k},
 * which floors to {@code k}, and it never lies on a pixel's edge [proposal].</li>
 * <li><b>Inside</b> means {@code 0 ≤ floor(px) < W} and {@code 0 ≤ floor(py) < H}, as {@link #inside} tests it; the
 * floor is for this test and for {@link Gesture}'s drag test (S12 decisions D26) only, and the packet carries the
 * unfloored position, which the server floors in integer mode and passes on in precise mode (15:185-187; S11 plan
 * U5.5).</li>
 * </ul>
 * Pure, with no Minecraft or LWJGL type, so the root's plain test runs it. Immutable.
 */
public final class PointerMapping {

    /** The room kept free on each side of the window, in window pixels (S12 plan P12.3). */
    public static final int MARGIN = 8;

    private final int windowHeight;
    private final int surfaceWidth;
    private final int surfaceHeight;
    private final int magnification;
    private final int decimation;
    private final int left;
    private final int top;

    private PointerMapping(int windowWidth, int windowHeight, int surfaceWidth, int surfaceHeight, int magnification,
        int decimation) {
        this.windowHeight = windowHeight;
        this.surfaceWidth = surfaceWidth;
        this.surfaceHeight = surfaceHeight;
        this.magnification = magnification;
        this.decimation = decimation;
        this.left = (int) Math.floor((windowWidth - width()) / 2);
        this.top = (int) Math.floor((windowHeight - height()) / 2);
    }

    /**
     * The mapping of a {@code surfaceWidth} × {@code surfaceHeight} surface in a window of the given size in pixels, or
     * null when there is nothing to map: an unbound display, whose description gives 0 × 0, or a window with no room
     * inside its margins.
     */
    public static PointerMapping fit(int windowWidth, int windowHeight, int surfaceWidth, int surfaceHeight) {
        int roomWidth = windowWidth - 2 * MARGIN;
        int roomHeight = windowHeight - 2 * MARGIN;
        if (surfaceWidth <= 0 || surfaceHeight <= 0 || roomWidth <= 0 || roomHeight <= 0) {
            return null;
        }
        int m = Math.min(roomWidth / surfaceWidth, roomHeight / surfaceHeight);
        if (m >= 1) {
            return new PointerMapping(windowWidth, windowHeight, surfaceWidth, surfaceHeight, m, 1);
        }
        // W / k fits the room when k is at least W / room, rounded up; m = 0 makes one of them 2 or more
        int k = Math.max(ceilDiv(surfaceWidth, roomWidth), ceilDiv(surfaceHeight, roomHeight));
        return new PointerMapping(windowWidth, windowHeight, surfaceWidth, surfaceHeight, 1, k);
    }

    /**
     * Whether {@code (px, py)} lies on one of a W × H surface's pixels: {@code 0 ≤ floor(px) < W} and
     * {@code 0 ≤ floor(py) < H}. A NaN lies on none, and a surface of 0 × 0 has none.
     */
    public static boolean inside(double px, double py, int width, int height) {
        double column = Math.floor(px);
        double row = Math.floor(py);
        return column >= 0 && column < width && row >= 0 && row < height;
    }

    /** Whether {@code (px, py)} lies on one of this surface's pixels, as {@link #inside(double, double, int, int)}. */
    public boolean inside(double px, double py) {
        return inside(px, py, surfaceWidth, surfaceHeight);
    }

    /** Window pixels per surface pixel: at least 1, and 1 while the picture decimates. */
    public int magnification() {
        return magnification;
    }

    /** Surface pixels per window pixel: 1 unless the window is too small for 1:1, then at least 2. */
    public int decimation() {
        return decimation;
    }

    /** The picture's left edge, in window pixels from the window's left. */
    public int left() {
        return left;
    }

    /** The picture's top edge, in window pixels from the window's top. */
    public int top() {
        return top;
    }

    /** The picture's width in window pixels, {@code W·m}; a fraction only while it decimates. */
    public double width() {
        return (double) surfaceWidth * magnification / decimation;
    }

    /** The picture's height in window pixels, {@code H·m}. */
    public double height() {
        return (double) surfaceHeight * magnification / decimation;
    }

    /** The surface x of a mouse event at LWJGL's {@code eventX}: its window pixel's centre, unfloored. */
    public double surfaceX(int eventX) {
        return (eventX + 0.5 - left) * decimation / magnification;
    }

    /** The surface y of a mouse event at LWJGL's {@code eventY}, counted up from the window's bottom row. */
    public double surfaceY(int eventY) {
        return (windowHeight - eventY - 0.5 - top) * decimation / magnification;
    }

    /**
     * The picture as {@code {left, top, right, bottom}} in GUI units for a GUI drawn at {@code scaleFactor}: window
     * pixels divided by it. The GUI's projection maps a GUI unit to {@code scaleFactor} window pixels
     * (EntityRenderer.java:1739-1749, with {@code ScaledResolution}'s unrounded size), so each corner lands on the
     * window pixel it came from, whatever the scale.
     *
     * @throws IllegalArgumentException for a scale factor below 1
     */
    public double[] guiRect(int scaleFactor) {
        if (scaleFactor < 1) {
            throw new IllegalArgumentException("scale factor " + scaleFactor + " is below 1");
        }
        double scale = scaleFactor;
        return new double[] { left / scale, top / scale, (left + width()) / scale, (top + height()) / scale };
    }

    /** For example "PointerMapping[320 x 200 at 2/1, left 107, top 40]". */
    @Override
    public String toString() {
        return "PointerMapping[" + surfaceWidth
            + " x "
            + surfaceHeight
            + " at "
            + magnification
            + "/"
            + decimation
            + ", left "
            + left
            + ", top "
            + top
            + "]";
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
