package io.github.mindbound.opengpu.client.gui;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Where the display GUI draws the picture and which surface pixel a mouse event points at (S12 plan P12.3, P12.4
 * with amendment F1; 15:185-186), pure: window pixels, an 8-pixel margin, an integer magnification or an even
 * decimation, a centred picture on whole pixels, LWJGL's upward y, the mouse pixel's centre, and the inside test's
 * floor. M0's surfaces are T2's 320 × 200 and T3's 640 × 400 (28 §8.1); 854 × 480 is Minecraft's default window.
 */
@Timeout(60)
class PointerMappingTest {

    private static final int T2_WIDTH = 320;
    private static final int T2_HEIGHT = 200;
    private static final int T3_WIDTH = 640;
    private static final int T3_HEIGHT = 400;

    private static void assertPlaced(PointerMapping mapping, int magnification, int decimation, int left, int top,
        double width, double height) {
        Assertions.assertNotNull(mapping);
        Assertions.assertEquals(magnification, mapping.magnification(), mapping::toString);
        Assertions.assertEquals(decimation, mapping.decimation(), mapping::toString);
        Assertions.assertEquals(left, mapping.left(), mapping::toString);
        Assertions.assertEquals(top, mapping.top(), mapping::toString);
        Assertions.assertEquals(width, mapping.width(), 1e-9, mapping::toString);
        Assertions.assertEquals(height, mapping.height(), 1e-9, mapping::toString);
    }

    // P12.3; amendment F1's IG4: T3 at 1:1 in the default window, centred
    @Test
    void aT3SurfaceInTheDefaultWindowIsDrawnAtOneToOne() {
        assertPlaced(PointerMapping.fit(854, 480, T3_WIDTH, T3_HEIGHT), 1, 1, 107, 40, 640, 400);
    }

    // P12.3; amendment F1's IG4: T2 at 2 in the default window, since 3 would be 960 wide
    @Test
    void aT2SurfaceInTheDefaultWindowIsDrawnAtTwo() {
        assertPlaced(PointerMapping.fit(854, 480, T2_WIDTH, T2_HEIGHT), 2, 1, 107, 40, 640, 400);
    }

    // P12.3: the largest integer that fits both ways, here limited by the height
    @Test
    void aLargerWindowGivesTheLargestWholeMagnificationThatFits() {
        assertPlaced(PointerMapping.fit(1000, 650, T2_WIDTH, T2_HEIGHT), 3, 1, 20, 25, 960, 600);
        assertPlaced(PointerMapping.fit(1920, 1017, T2_WIDTH, T2_HEIGHT), 5, 1, 160, 8, 1600, 1000);
    }

    // P12.3: a window too small for 1:1 decimates by the smallest whole factor that fits, so pixels decimate evenly
    @Test
    void aWindowTooSmallForOneToOneDecimatesByTheSmallestWholeFactor() {
        assertPlaced(PointerMapping.fit(500, 300, T3_WIDTH, T3_HEIGHT), 1, 2, 90, 50, 320, 200);
        assertPlaced(PointerMapping.fit(300, 200, T3_WIDTH, T3_HEIGHT), 1, 3, 43, 33, 640 / 3.0, 400 / 3.0);
    }

    // P12.3: the margin is 8 pixels on each side; a window of exactly W·m plus both margins fits m, one pixel less not
    @Test
    void aWindowOfExactlyTheMagnifiedSurfacePlusItsMarginsFits() {
        Assertions.assertEquals(8, PointerMapping.MARGIN);
        assertPlaced(PointerMapping.fit(656, 416, T2_WIDTH, T2_HEIGHT), 2, 1, 8, 8, 640, 400);
        assertPlaced(PointerMapping.fit(655, 416, T2_WIDTH, T2_HEIGHT), 1, 1, 167, 108, 320, 200);
        assertPlaced(PointerMapping.fit(656, 415, T2_WIDTH, T2_HEIGHT), 1, 1, 168, 107, 320, 200);
    }

    // P12.3: centred on a whole window pixel, the odd pixel of the room left over going right and down
    @Test
    void thePictureIsCentredOnAWholeWindowPixel() {
        assertPlaced(PointerMapping.fit(855, 481, T2_WIDTH, T2_HEIGHT), 2, 1, 107, 40, 640, 400);
        assertPlaced(PointerMapping.fit(856, 482, T2_WIDTH, T2_HEIGHT), 2, 1, 108, 41, 640, 400);
    }

    // P12.4: a mouse event points at its window pixel's centre, never at an edge: k + 0.5 at 1:1, divided by m above,
    // times k below
    @Test
    void aMouseEventPointsAtItsWindowPixelsCentre() {
        PointerMapping one = PointerMapping.fit(854, 480, T3_WIDTH, T3_HEIGHT);
        Assertions.assertEquals(5.5, one.surfaceX(107 + 5));
        Assertions.assertEquals(0.5, one.surfaceX(107));
        PointerMapping two = PointerMapping.fit(854, 480, T2_WIDTH, T2_HEIGHT);
        Assertions.assertEquals(2.75, two.surfaceX(107 + 5));
        Assertions.assertEquals(0.25, two.surfaceX(107));
        Assertions.assertEquals(0.75, two.surfaceX(108));
        PointerMapping half = PointerMapping.fit(500, 300, T3_WIDTH, T3_HEIGHT);
        Assertions.assertEquals(11, half.surfaceX(90 + 5));
        Assertions.assertEquals(1, half.surfaceX(90));
    }

    // P12.4: LWJGL counts y up from the window's bottom row, so the picture's top row is displayHeight - 1 - top
    @Test
    void theYAxisIsFlippedFromLwjglsBottomUpRows() {
        PointerMapping one = PointerMapping.fit(854, 480, T3_WIDTH, T3_HEIGHT);
        Assertions.assertEquals(0.5, one.surfaceY(480 - 1 - 40));
        Assertions.assertEquals(399.5, one.surfaceY(480 - 1 - 40 - 399));
        Assertions.assertEquals(-0.5, one.surfaceY(480 - 40), "the row above the picture");
        Assertions.assertEquals(439.5, one.surfaceY(0), "the window's bottom row");
        PointerMapping two = PointerMapping.fit(854, 480, T2_WIDTH, T2_HEIGHT);
        Assertions.assertEquals(0.25, two.surfaceY(480 - 1 - 40));
        Assertions.assertEquals(199.75, two.surfaceY(40));
        // Review strength-7: a 1/2 decimation, picture top 50, times 2 in y as in x
        PointerMapping half = PointerMapping.fit(500, 300, T3_WIDTH, T3_HEIGHT);
        Assertions.assertEquals(1.0, half.surfaceY(300 - 1 - 50));
        Assertions.assertEquals(399.0, half.surfaceY(300 - 50 - 200));
    }

    // P12.4; 15:186: inside floors for the test only: floor(px) = W, and px just below 0, lie outside; W - 0.5 inside
    @Test
    void theInsideTestFloorsThePositionOnlyToTestIt() {
        Assertions.assertTrue(PointerMapping.inside(0, 0, T2_WIDTH, T2_HEIGHT));
        Assertions.assertTrue(PointerMapping.inside(T2_WIDTH - 0.5, T2_HEIGHT - 0.5, T2_WIDTH, T2_HEIGHT));
        Assertions.assertTrue(PointerMapping.inside(T2_WIDTH - 1e-6, 3, T2_WIDTH, T2_HEIGHT));
        Assertions.assertFalse(PointerMapping.inside(T2_WIDTH, 3, T2_WIDTH, T2_HEIGHT), "floor(px) = W");
        Assertions.assertFalse(PointerMapping.inside(T2_WIDTH + 0.25, 3, T2_WIDTH, T2_HEIGHT));
        Assertions.assertFalse(PointerMapping.inside(3, T2_HEIGHT, T2_WIDTH, T2_HEIGHT), "floor(py) = H");
        Assertions.assertFalse(PointerMapping.inside(-1e-6, 3, T2_WIDTH, T2_HEIGHT), "px just below 0");
        Assertions.assertFalse(PointerMapping.inside(3, -1e-6, T2_WIDTH, T2_HEIGHT), "py just below 0");
        Assertions.assertFalse(PointerMapping.inside(Double.NaN, 3, T2_WIDTH, T2_HEIGHT), "no mapping");
        Assertions.assertFalse(PointerMapping.inside(0, 0, 0, 0), "an unbound surface has no pixel");
    }

    // P12.4: the picture's first and last window pixels map to the surface's corner pixels, one more outside, on both
    // axes and at both magnifications of the default window
    @Test
    void thePicturesEdgePixelsMapToTheCornerPixelsAndTheirNeighboursOutside() {
        for (int[] size : new int[][] { { T2_WIDTH, T2_HEIGHT }, { T3_WIDTH, T3_HEIGHT } }) {
            PointerMapping mapping = PointerMapping.fit(854, 480, size[0], size[1]);
            int firstColumn = mapping.left();
            int lastColumn = mapping.left() + (int) mapping.width() - 1;
            int topRow = 480 - 1 - mapping.top();
            int bottomRow = 480 - mapping.top() - (int) mapping.height();
            double x0 = mapping.surfaceX(firstColumn);
            double y0 = mapping.surfaceY(topRow);
            double x1 = mapping.surfaceX(lastColumn);
            double y1 = mapping.surfaceY(bottomRow);
            Assertions
                .assertTrue(mapping.inside(x0, y0) && Math.floor(x0) == 0 && Math.floor(y0) == 0, mapping::toString);
            Assertions.assertTrue(
                mapping.inside(x1, y1) && Math.floor(x1) == size[0] - 1 && Math.floor(y1) == size[1] - 1,
                mapping::toString);
            Assertions.assertFalse(mapping.inside(mapping.surfaceX(firstColumn - 1), y0), mapping::toString);
            Assertions.assertFalse(mapping.inside(mapping.surfaceX(lastColumn + 1), y1), mapping::toString);
            Assertions.assertFalse(mapping.inside(x0, mapping.surfaceY(topRow + 1)), mapping::toString);
            Assertions.assertFalse(mapping.inside(x1, mapping.surfaceY(bottomRow - 1)), mapping::toString);
        }
    }

    // P12.3: an unbound display (0 x 0) maps nothing, nor does a window with no room inside its margins
    @Test
    void anUnboundSurfaceOrAWindowWithNoRoomMapsNothing() {
        Assertions.assertNull(PointerMapping.fit(854, 480, 0, 0));
        Assertions.assertNull(PointerMapping.fit(854, 480, T2_WIDTH, 0));
        Assertions.assertNull(PointerMapping.fit(854, 480, 0, T2_HEIGHT));
        Assertions.assertNull(PointerMapping.fit(16, 480, T2_WIDTH, T2_HEIGHT));
        Assertions.assertNull(PointerMapping.fit(854, 16, T2_WIDTH, T2_HEIGHT));
        assertPlaced(PointerMapping.fit(17, 17, T2_WIDTH, T2_HEIGHT), 1, 320, 8, 8, 1, 200 / 320.0);
    }

    // P12.3, amendment F1: the GUI rectangle is the picture's window pixels over the scale factor, so under the GUI's
    // projection (a GUI unit is scaleFactor window pixels) it lands on the same window pixels at every GUI scale
    @Test
    void theGuiRectangleLandsOnTheSameWindowPixelsAtEveryGuiScale() {
        PointerMapping mapping = PointerMapping.fit(854, 480, T2_WIDTH, T2_HEIGHT);
        for (int scale = 1; scale <= 4; scale++) {
            double[] rect = mapping.guiRect(scale);
            Assertions.assertEquals(107, rect[0] * scale, 1e-9, "left at scale " + scale);
            Assertions.assertEquals(40, rect[1] * scale, 1e-9, "top at scale " + scale);
            Assertions.assertEquals(747, rect[2] * scale, 1e-9, "right at scale " + scale);
            Assertions.assertEquals(440, rect[3] * scale, 1e-9, "bottom at scale " + scale);
        }
        Assertions.assertThrows(IllegalArgumentException.class, () -> mapping.guiRect(0));
    }
}
