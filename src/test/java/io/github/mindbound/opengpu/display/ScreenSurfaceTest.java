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
