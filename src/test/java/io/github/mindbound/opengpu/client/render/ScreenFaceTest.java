package io.github.mindbound.opengpu.client.render;

import net.minecraftforge.common.util.ForgeDirection;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.display.DisplayShape;
import io.github.mindbound.opengpu.display.ScreenSurface;

/**
 * What a display's face shows (S12 plan P8.5 with amendment F15; P10; Q-S12-4 (a)) and the TESR's back-face test
 * (P8.7), both pure, with no GL context: an unbound display and a bound one with no present texture show the
 * chassis's dark front, a host without graphics the "no graphics" bitmap, and a present texture the picture.
 */
class ScreenFaceTest {

    private static final int TEXTURE = 7;

    // P8.5: unbound, the description's size 0 x 0, nothing is drawn, whatever the host and the lookup say
    @Test
    void anUnboundDisplayShowsTheDarkFront() {
        Assertions.assertEquals(ScreenFace.NONE, ScreenFace.choose(false, true, 0));
        Assertions.assertEquals(ScreenFace.NONE, ScreenFace.choose(false, true, TEXTURE));
    }

    // P8.5; 20:242; 24:230: a bound display on a client whose host is unavailable shows the "no graphics" bitmap
    @Test
    void aBoundDisplayOnAHostWithoutGraphicsShowsNoGraphics() {
        Assertions.assertEquals(ScreenFace.NO_GRAPHICS, ScreenFace.choose(true, false, 0));
        Assertions.assertEquals(ScreenFace.NO_GRAPHICS, ScreenFace.choose(true, false, TEXTURE));
    }

    // P8.5, P10; Q-S12-4 (a): bound, the host available and no present texture (no GL objects yet, or a LAN guest's or
    // a dedicated server's player's client in M0) shows the dark front, not "no graphics"
    @Test
    void aBoundDisplayWithNoPresentTextureShowsTheDarkFront() {
        Assertions.assertEquals(ScreenFace.NONE, ScreenFace.choose(true, true, 0));
    }

    // P8.5: bound, the host available and a present texture: the picture
    @Test
    void aBoundDisplayWithAPresentTextureShowsThePicture() {
        Assertions.assertEquals(ScreenFace.PRESENT, ScreenFace.choose(true, true, TEXTURE));
    }

    // P8.5: unbound on a host without graphics is still the dark front, as for any unbound display
    @Test
    void anUnboundDisplayOnAHostWithoutGraphicsShowsTheDarkFront() {
        Assertions.assertEquals(ScreenFace.NONE, ScreenFace.choose(false, false, 0));
        Assertions.assertEquals(ScreenFace.NONE, ScreenFace.choose(false, false, TEXTURE));
    }

    // P8.7: the TESR draws only for a camera in front of the picture's plane; (x, y, z) is the block's minimum corner
    // from the camera, as the dispatcher passes it
    @Test
    void theBackFaceTestDrawsOnlyForACameraInFrontOfThePicture() {
        ScreenSurface north = ScreenSurface.of(DisplayShape.placed(ForgeDirection.NORTH, 0));
        double[] corner = north.picture(320, 200)[ScreenSurface.TOP_LEFT];
        // The picture's plane lies at z -0.01 in the block; a camera 3 blocks north of the block sees it from its front
        Assertions.assertTrue(ScreenFace.seenFromFront(ForgeDirection.NORTH, corner, -0.5, -0.5, 3));
        // ... and one inside the block, or 3 blocks south of it, from behind
        Assertions.assertFalse(ScreenFace.seenFromFront(ForgeDirection.NORTH, corner, -0.5, -0.5, -0.5));
        Assertions.assertFalse(ScreenFace.seenFromFront(ForgeDirection.NORTH, corner, -0.5, -0.5, -4));
        // A camera exactly on the plane counts as behind
        Assertions.assertFalse(ScreenFace.seenFromFront(ForgeDirection.NORTH, corner, -0.5, -0.5, 0.01));
        ScreenSurface floor = ScreenSurface.of(DisplayShape.placed(ForgeDirection.UP, 1));
        double[] top = floor.picture(320, 200)[ScreenSurface.TOP_LEFT];
        Assertions.assertTrue(ScreenFace.seenFromFront(ForgeDirection.UP, top, -0.5, -2, -0.5), "camera above");
        Assertions.assertFalse(ScreenFace.seenFromFront(ForgeDirection.UP, top, -0.5, 1, -0.5), "camera below");
        ScreenSurface ceiling = ScreenSurface.of(DisplayShape.placed(ForgeDirection.DOWN, 2));
        double[] under = ceiling.picture(320, 200)[ScreenSurface.TOP_LEFT];
        Assertions.assertTrue(ScreenFace.seenFromFront(ForgeDirection.DOWN, under, -0.5, 2, -0.5), "camera below");
        Assertions.assertFalse(ScreenFace.seenFromFront(ForgeDirection.DOWN, under, -0.5, -2, -0.5), "camera above");
    }

    // P8.7; review strength-1: every facing, so each of the normal's three terms counts. The camera 4 blocks out from
    // the block's centre along the face's normal sees the picture; at the centre, 4 blocks out the other way, and on
    // the picture's plane it does not
    @Test
    void theBackFaceTestHoldsForEveryFacing() {
        for (ForgeDirection facing : ForgeDirection.VALID_DIRECTIONS) {
            ScreenSurface surface = ScreenSurface.of(DisplayShape.placed(facing, 0));
            Assertions.assertEquals(facing, surface.normal());
            double[] corner = surface.picture(320, 200)[ScreenSurface.TOP_LEFT];
            // The block's minimum corner from a camera at the block's centre plus d along the normal
            double[] front = fromCamera(facing, 4);
            double[] back = fromCamera(facing, -4);
            double[] centre = fromCamera(facing, 0);
            Assertions
                .assertTrue(ScreenFace.seenFromFront(facing, corner, front[0], front[1], front[2]), "front " + facing);
            Assertions
                .assertFalse(ScreenFace.seenFromFront(facing, corner, back[0], back[1], back[2]), "behind " + facing);
            Assertions.assertFalse(
                ScreenFace.seenFromFront(facing, corner, centre[0], centre[1], centre[2]),
                "inside " + facing);
            Assertions.assertFalse(
                ScreenFace.seenFromFront(facing, corner, -corner[0], -corner[1], -corner[2]),
                "on the plane " + facing);
        }
    }

    private static double[] fromCamera(ForgeDirection normal, double d) {
        return new double[] { -0.5 - d * normal.offsetX, -0.5 - d * normal.offsetY, -0.5 - d * normal.offsetZ };
    }
}
