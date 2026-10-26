package io.github.mindbound.opengpu.display;

import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.common.util.ForgeDirection;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The screen-surface helper (RM:34; 24:248; Q-S11-14; S11 amendments A3): the quad of each of the six facings and the
 * four floor and ceiling rotations, at thickness 16 and 1 and tilt 0, and its convention: upright for the placer, its
 * right edge the placer's right, pixel (0, 0) at the top-left corner. Block coordinates; every value here is exact in
 * binary, so the corners compare exactly.
 */
@Timeout(60)
class ScreenSurfaceTest {

    private static final ForgeDirection N = ForgeDirection.NORTH;
    private static final ForgeDirection S = ForgeDirection.SOUTH;
    private static final ForgeDirection E = ForgeDirection.EAST;
    private static final ForgeDirection W = ForgeDirection.WEST;

    private static ScreenSurface surface(ForgeDirection facing, int rotation, int thickness) {
        return ScreenSurface.of(DisplayShape.of(facing, rotation, thickness, 0, 0));
    }

    private static void assertCorners(ScreenSurface surface, double[]... corners) {
        for (int i = 0; i < 4; i++) {
            Assertions.assertArrayEquals(corners[i], surface.corner(i), surface + ", corner " + i);
        }
    }

    private static double[] at(double x, double y, double z) {
        return new double[] { x, y, z };
    }

    /** Every shape M0 has, at thickness 1 and 16. */
    private static List<ScreenSurface> everySurface() {
        List<ScreenSurface> surfaces = new ArrayList<>();
        for (int thickness : new int[] { 1, 16 }) {
            for (ForgeDirection facing : ForgeDirection.VALID_DIRECTIONS) {
                int rotations = facing == ForgeDirection.UP || facing == ForgeDirection.DOWN ? 4 : 1;
                for (int rotation = 0; rotation < rotations; rotation++) {
                    surfaces.add(surface(facing, rotation, thickness));
                }
            }
        }
        return surfaces;
    }

    // A3: a wall's picture stands upright on the block's front face, its right the right of a viewer facing it
    @Test
    void theFourWallsAtThickness16() {
        ScreenSurface south = surface(S, 0, 16);
        Assertions.assertEquals(ForgeDirection.UP, south.up());
        Assertions.assertEquals(E, south.right());
        assertCorners(south, at(0, 1, 1), at(1, 1, 1), at(1, 0, 1), at(0, 0, 1));
        ScreenSurface north = surface(N, 0, 16);
        Assertions.assertEquals(W, north.right());
        assertCorners(north, at(1, 1, 0), at(0, 1, 0), at(0, 0, 0), at(1, 0, 0));
        ScreenSurface east = surface(E, 0, 16);
        Assertions.assertEquals(N, east.right());
        assertCorners(east, at(1, 1, 1), at(1, 1, 0), at(1, 0, 0), at(1, 0, 1));
        ScreenSurface west = surface(W, 0, 16);
        Assertions.assertEquals(S, west.right());
        assertCorners(west, at(0, 1, 0), at(0, 1, 1), at(0, 0, 1), at(0, 0, 0));
        for (ScreenSurface wall : new ScreenSurface[] { south, north, east, west }) {
            Assertions.assertEquals(
                wall.shape()
                    .facing(),
                wall.normal());
            Assertions.assertEquals(ForgeDirection.DOWN, wall.down());
        }
    }

    // A3: on a floor the up edge points along the placer's look, on a ceiling against it; the right is the placer's
    @Test
    void theFourFloorAndCeilingRotationsAtThickness16() {
        ForgeDirection[] look = { S, W, N, E };
        ForgeDirection[] right = { W, N, E, S };
        for (int r = 0; r < 4; r++) {
            ScreenSurface floor = surface(ForgeDirection.UP, r, 16);
            ScreenSurface ceiling = surface(ForgeDirection.DOWN, r, 16);
            Assertions.assertEquals(look[r], floor.up(), "floor r" + r);
            Assertions.assertEquals(look[r].getOpposite(), ceiling.up(), "ceiling r" + r);
            Assertions.assertEquals(right[r], floor.right(), "floor r" + r);
            Assertions.assertEquals(right[r], ceiling.right(), "ceiling r" + r);
            for (int i = 0; i < 4; i++) {
                Assertions.assertEquals(1.0, floor.corner(i)[1], "floor r" + r);
                Assertions.assertEquals(0.0, ceiling.corner(i)[1], "ceiling r" + r);
            }
        }
        // Rotation 0, a placer facing south: the floor's top edge is the far, southern one, and its right the west
        assertCorners(surface(ForgeDirection.UP, 0, 16), at(1, 1, 1), at(0, 1, 1), at(0, 1, 0), at(1, 1, 0));
        assertCorners(surface(ForgeDirection.UP, 1, 16), at(0, 1, 1), at(0, 1, 0), at(1, 1, 0), at(1, 1, 1));
        assertCorners(surface(ForgeDirection.UP, 2, 16), at(0, 1, 0), at(1, 1, 0), at(1, 1, 1), at(0, 1, 1));
        assertCorners(surface(ForgeDirection.UP, 3, 16), at(1, 1, 0), at(1, 1, 1), at(0, 1, 1), at(0, 1, 0));
        // ... and the ceiling's top edge the near, northern one
        assertCorners(surface(ForgeDirection.DOWN, 0, 16), at(1, 0, 0), at(0, 0, 0), at(0, 0, 1), at(1, 0, 1));
    }

    // 24:19's thickness 1-16: the display keeps its back face, so the quad lies thickness / 16 in front of it
    @Test
    void thicknessMovesTheQuadFromTheFrontFaceToTheBack() {
        for (ForgeDirection facing : ForgeDirection.VALID_DIRECTIONS) {
            int axis = facing.offsetX != 0 ? 0 : facing.offsetY != 0 ? 1 : 2;
            boolean positive = facing.offsetX + facing.offsetY + facing.offsetZ > 0;
            for (int thickness : new int[] { 1, 16 }) {
                double expected = positive ? thickness / 16.0 : 1 - thickness / 16.0;
                ScreenSurface s = surface(facing, 0, thickness);
                for (int i = 0; i < 4; i++) {
                    Assertions.assertEquals(expected, s.corner(i)[axis], s + ", corner " + i);
                }
            }
        }
        Assertions.assertEquals(1.0 / 16, surface(S, 0, 1).corner(ScreenSurface.TOP_LEFT)[2]);
        Assertions.assertEquals(15.0 / 16, surface(ForgeDirection.DOWN, 2, 1).corner(ScreenSurface.TOP_LEFT)[1]);
    }

    // The convention for S12's TESR and touch: a unit square, right = up × normal, (0, 0) top-left, (1, 1)
    // bottom-right, pixel (x, y) of a W × H picture at (x / W, y / H)
    @Test
    void everyQuadIsTheWholeFaceWithPixelZeroAtTheTopLeft() {
        List<ScreenSurface> surfaces = everySurface();
        Assertions.assertEquals(24, surfaces.size());
        for (ScreenSurface s : surfaces) {
            double[] tl = s.corner(ScreenSurface.TOP_LEFT);
            double[] tr = s.corner(ScreenSurface.TOP_RIGHT);
            double[] br = s.corner(ScreenSurface.BOTTOM_RIGHT);
            double[] bl = s.corner(ScreenSurface.BOTTOM_LEFT);
            double[] centre = s.point(0.5, 0.5);
            for (int i = 0; i < 3; i++) {
                int r = i == 0 ? s.right().offsetX : i == 1 ? s.right().offsetY : s.right().offsetZ;
                int d = i == 0 ? s.down().offsetX : i == 1 ? s.down().offsetY : s.down().offsetZ;
                Assertions.assertEquals(r, tr[i] - tl[i], s.toString());
                Assertions.assertEquals(d, bl[i] - tl[i], s.toString());
                Assertions.assertEquals(r + d, br[i] - tl[i], s.toString());
                Assertions.assertEquals((tl[i] + br[i]) / 2, centre[i], s.toString());
                for (double[] corner : new double[][] { tl, tr, br, bl }) {
                    Assertions.assertTrue(corner[i] >= 0 && corner[i] <= 1, s.toString());
                }
            }
            ForgeDirection up = s.up();
            ForgeDirection n = s.normal();
            int[] cross = { up.offsetY * n.offsetZ - up.offsetZ * n.offsetY,
                up.offsetZ * n.offsetX - up.offsetX * n.offsetZ, up.offsetX * n.offsetY - up.offsetY * n.offsetX };
            Assertions.assertArrayEquals(
                new int[] { s.right().offsetX, s.right().offsetY, s.right().offsetZ },
                cross,
                "right = up x normal, " + s);
            Assertions.assertEquals(0, up.offsetX * n.offsetX + up.offsetY * n.offsetY + up.offsetZ * n.offsetZ);
            Assertions.assertArrayEquals(tl, s.point(0, 0));
            Assertions.assertArrayEquals(br, s.point(1, 1));
            Assertions.assertEquals(s, ScreenSurface.of(s.shape()));
        }
        Assertions.assertArrayEquals(at(0.5, 1, 1), surface(S, 0, 16).point(160 / 320.0, 0 / 200.0));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> surface(S, 0, 16).corner(4));
    }

    // ---- The picture on the face (S12 plan P8.3, P14.2; 15:175): the inner square 11.5/16 on a side, the picture at
    // its aspect inside it, centred, and OFFSET in front of the face; every value but 0.01 is exact in binary

    /** The inner square's side, the face less the bezel on each side: 11.5/16. */
    private static final double INNER = 11.5 / 16;
    private static final double EPS = 1e-12;

    /**
     * Where {@code p} lies against the face: {u, v, depth}, its distance from the face's top-left corner along the
     * right edge, down the left edge and along the normal.
     */
    private static double[] onFace(ScreenSurface s, double[] p) {
        double[] tl = s.corner(ScreenSurface.TOP_LEFT);
        double[] out = new double[3];
        ForgeDirection[] axes = { s.right(), s.down(), s.normal() };
        for (int a = 0; a < 3; a++) {
            ForgeDirection d = axes[a];
            out[a] = (p[0] - tl[0]) * d.offsetX + (p[1] - tl[1]) * d.offsetY + (p[2] - tl[2]) * d.offsetZ;
        }
        return out;
    }

    /** The face's point (u, v) moved OFFSET along the normal: where the picture's (u, v) must lie. */
    private static double[] inFront(ScreenSurface s, double u, double v) {
        double[] p = s.point(u, v);
        ForgeDirection n = s.normal();
        return at(
            p[0] + ScreenSurface.OFFSET * n.offsetX,
            p[1] + ScreenSurface.OFFSET * n.offsetY,
            p[2] + ScreenSurface.OFFSET * n.offsetZ);
    }

    private static void assertPicture(ScreenSurface s, double[][] picture, double u0, double v0, double u1, double v1) {
        String where = s.toString();
        Assertions.assertEquals(4, picture.length, where);
        Assertions.assertArrayEquals(inFront(s, u0, v0), picture[ScreenSurface.TOP_LEFT], EPS, where + ", top-left");
        Assertions.assertArrayEquals(inFront(s, u1, v0), picture[ScreenSurface.TOP_RIGHT], EPS, where + ", top-right");
        Assertions
            .assertArrayEquals(inFront(s, u1, v1), picture[ScreenSurface.BOTTOM_RIGHT], EPS, where + ", bottom-right");
        Assertions
            .assertArrayEquals(inFront(s, u0, v1), picture[ScreenSurface.BOTTOM_LEFT], EPS, where + ", bottom-left");
    }

    // P8.3: T2's 320 x 200 on a north wall, the letterbox's bands above and below, top-left first, every corner exact
    @Test
    void aT2PictureOnANorthWallIsLetterboxedInsideTheBezelTopLeftFirst() {
        Assertions.assertEquals(9.0 / 64, ScreenSurface.BEZEL);
        Assertions.assertEquals(0.01, ScreenSurface.OFFSET);
        ScreenSurface north = surface(N, 0, 16);
        double[][] picture = north.picture(320, 200);
        // x runs west, from 1 - 9/64 to 9/64; y from 1 - 141/512 down to 141/512; z 0.01 north of the face at z 0
        Assertions.assertArrayEquals(at(55.0 / 64, 371.0 / 512, -0.01), picture[ScreenSurface.TOP_LEFT]);
        Assertions.assertArrayEquals(at(9.0 / 64, 371.0 / 512, -0.01), picture[ScreenSurface.TOP_RIGHT]);
        Assertions.assertArrayEquals(at(9.0 / 64, 141.0 / 512, -0.01), picture[ScreenSurface.BOTTOM_RIGHT]);
        Assertions.assertArrayEquals(at(55.0 / 64, 141.0 / 512, -0.01), picture[ScreenSurface.BOTTOM_LEFT]);
        double[] tl = onFace(north, picture[ScreenSurface.TOP_LEFT]);
        double[] tr = onFace(north, picture[ScreenSurface.TOP_RIGHT]);
        double[] bl = onFace(north, picture[ScreenSurface.BOTTOM_LEFT]);
        Assertions.assertEquals(INNER, tr[0] - tl[0], "the width, the inner square's");
        Assertions.assertEquals(INNER * 10 / 16, bl[1] - tl[1], "the height, at 16:10");
        Assertions.assertEquals(ScreenSurface.BEZEL, tl[0], "the bezel on the left");
        Assertions.assertEquals(ScreenSurface.BEZEL + INNER * 3 / 16, tl[1], "the bezel and a band above");
    }

    // P8.3: T3's 640 x 400 has T2's aspect, so it lies exactly where T2's picture does
    @Test
    void aT3PictureLiesWhereAT2PictureDoes() {
        ScreenSurface north = surface(N, 0, 16);
        double[][] t2 = north.picture(320, 200);
        double[][] t3 = north.picture(640, 400);
        for (int i = 0; i < 4; i++) {
            Assertions.assertArrayEquals(t2[i], t3[i], "corner " + i);
        }
    }

    // P8.3: on every face M0 has, the picture's centre is the face's, OFFSET in front of it
    @Test
    void everyPictureIsCentredOnItsFace() {
        for (ScreenSurface s : everySurface()) {
            for (int[] size : new int[][] { { 320, 200 }, { 640, 400 }, { 200, 320 }, { 1, 1 }, { 7, 3 } }) {
                double[][] picture = s.picture(size[0], size[1]);
                double[] centre = new double[3];
                for (double[] corner : picture) {
                    for (int i = 0; i < 3; i++) {
                        centre[i] += corner[i] / 4;
                    }
                }
                Assertions.assertArrayEquals(inFront(s, 0.5, 0.5), centre, EPS, s + ", " + size[0] + " x " + size[1]);
            }
        }
    }

    // P8.3; 15:175: every corner lies inside the bezel, on both axes of the face
    @Test
    void everyPictureLiesInsideTheBezel() {
        for (ScreenSurface s : everySurface()) {
            for (int[] size : new int[][] { { 320, 200 }, { 200, 320 }, { 1, 1 }, { 1, 1000 } }) {
                for (double[] corner : s.picture(size[0], size[1])) {
                    double[] p = onFace(s, corner);
                    for (int a = 0; a < 2; a++) {
                        Assertions.assertTrue(
                            p[a] >= ScreenSurface.BEZEL - EPS && p[a] <= 1 - ScreenSurface.BEZEL + EPS,
                            s + ", " + size[0] + " x " + size[1] + ": " + p[a]);
                    }
                }
            }
        }
    }

    // A3 and P8.3: on the floor at rotations 0 and 2 and the ceiling at 1, pixel (0, 0) is at the face's top-left as
    // ScreenSurface defines it for the placer, the picture's right along the face's right, its rows down the face
    @Test
    void theFloorAtRotations0And2AndTheCeilingAt1KeepPixelZeroAtTheTopLeft() {
        double u0 = ScreenSurface.BEZEL;
        double u1 = 1 - ScreenSurface.BEZEL;
        double v0 = (1 - INNER * 10 / 16) / 2;
        double v1 = 1 - v0;
        for (ScreenSurface s : new ScreenSurface[] { surface(ForgeDirection.UP, 0, 16),
            surface(ForgeDirection.UP, 2, 16), surface(ForgeDirection.DOWN, 1, 16) }) {
            assertPicture(s, s.picture(320, 200), u0, v0, u1, v1);
            assertPicture(s, s.picture(640, 400), u0, v0, u1, v1);
        }
        // The floor at rotation 0, a placer facing south: the top edge is the southern one and the right the west
        double[][] floor = surface(ForgeDirection.UP, 0, 16).picture(320, 200);
        Assertions.assertTrue(floor[ScreenSurface.TOP_LEFT][2] > floor[ScreenSurface.BOTTOM_LEFT][2], "top is south");
        Assertions.assertTrue(floor[ScreenSurface.TOP_RIGHT][0] < floor[ScreenSurface.TOP_LEFT][0], "right is west");
    }

    // P8.3: every corner of every picture is OFFSET along the normal, in front of the face, at thickness 16 and 1
    @Test
    void everyCornerLiesOffsetInFrontOfTheFaceAlongTheNormal() {
        for (ScreenSurface s : everySurface()) {
            for (double[] corner : s.picture(320, 200)) {
                Assertions.assertEquals(ScreenSurface.OFFSET, onFace(s, corner)[2], EPS, s.toString());
            }
        }
        // The north wall at thickness 16: the face is the block's north face, z 0, and the picture lies at z -0.01
        for (double[] corner : surface(N, 0, 16).picture(320, 200)) {
            Assertions.assertEquals(-0.01, corner[2]);
        }
    }

    // P8.3 for M1: a picture higher than it is wide is as high as the inner square, at its own aspect, centred
    @Test
    void aTallerPictureFitsTheHeight() {
        ScreenSurface north = surface(N, 0, 16);
        double w = INNER * 10 / 16;
        assertPicture(
            north,
            north.picture(200, 320),
            (1 - w) / 2,
            ScreenSurface.BEZEL,
            (1 + w) / 2,
            1 - ScreenSurface.BEZEL);
    }

    // P8.3, P8.6: a square picture, the "no graphics" bitmap, fills the inner square; a picture of no pixels is none
    @Test
    void aSquarePictureFillsTheInnerSquareAndAnEmptyOneIsRefused() {
        double b = ScreenSurface.BEZEL;
        for (ScreenSurface s : everySurface()) {
            assertPicture(s, s.picture(1, 1), b, b, 1 - b, 1 - b);
            assertPicture(s, s.picture(64, 64), b, b, 1 - b, 1 - b);
        }
        ScreenSurface south = surface(S, 0, 16);
        Assertions.assertThrows(IllegalArgumentException.class, () -> south.picture(0, 200));
        Assertions.assertThrows(IllegalArgumentException.class, () -> south.picture(320, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> south.picture(-1, 1));
    }

    // Q-S11-15 with A3: whatever way it was placed, the picture is upright for its placer, and its right is theirs
    @Test
    void aPlacedPictureIsUprightForItsPlacer() {
        ForgeDirection[] look = { S, W, N, E };
        // The placer's right hand, the look crossed with UP: west for a placer facing south, looking up or down or not
        ForgeDirection[] right = { W, N, E, S };
        for (int q = 0; q < 4; q++) {
            float yaw = q * 90f;
            ForgeDirection theirRight = right[q];
            ScreenSurface wall = ScreenSurface.of(Placement.shape(0f, yaw));
            ScreenSurface floor = ScreenSurface.of(Placement.shape(80f, yaw));
            ScreenSurface ceiling = ScreenSurface.of(Placement.shape(-80f, yaw));
            Assertions.assertEquals(look[q].getOpposite(), wall.normal(), "yaw " + yaw);
            Assertions.assertEquals(ForgeDirection.UP, wall.up(), "yaw " + yaw);
            Assertions.assertEquals(look[q], floor.up(), "yaw " + yaw);
            Assertions.assertEquals(look[q].getOpposite(), ceiling.up(), "yaw " + yaw);
            for (ScreenSurface s : new ScreenSurface[] { wall, floor, ceiling }) {
                Assertions.assertEquals(theirRight, s.right(), s + ", yaw " + yaw);
            }
        }
    }
}
