package io.github.mindbound.opengpu.display;

import java.util.Locale;
import java.util.Objects;

import net.minecraftforge.common.util.ForgeDirection;

/**
 * The screen-surface helper (RM:34; 24:248; Q-S11-14; S11 amendments A3): the display's screen quad in block
 * coordinates, from its {@link DisplayShape}, for S12's TESR and for touch. Pure geometry with no client type, so a
 * server and a plain test can use it. Block coordinates put the display's block at 0-1 on each axis, its minimum
 * corner at the origin; world coordinates add the block's position.
 * <p>
 * <b>The convention</b>, which S12's TESR uses [proposal]:
 * <ul>
 * <li>the quad is the display's whole front face. Its normal is the facing. The display keeps its back face, as a
 * panel on the block behind it, so the quad lies {@code thickness / 16} in front of the back face: on the block's
 * front face at 16, the cube of decision 6 (24:248), and 1/16 in front of the back face at 1 (24:19's 1-16 px);</li>
 * <li>the picture is upright for its placer. On a wall its up edge points UP. On a floor its up edge points along the
 * rotation's quadrant, the placer's horizontal look ({@link Placement}), and on a ceiling against it, since a placer
 * looking down sees the far edge at the top and one looking up sees the near edge there;</li>
 * <li>its right edge is the up edge's direction crossed with the normal, the viewer's right;</li>
 * <li>pixel (0, 0) is the top-left corner, x grows to the right and y down (0-based pixels, 15:184; 24:140), so
 * {@link #point} at (x / W, y / H) is where pixel (x, y) of a W × H picture starts.</li>
 * </ul>
 * The bezel and the letterbox of 15 §4.2 are inside this quad and S12's. Tilt is 0 in M0, the only tilt
 * {@link DisplayShape} has; M1.5's tilted plane (RM:110-111) extends this class. Immutable.
 */
public final class ScreenSurface {

    /**
     * Corner indices, in the order top-left, top-right, bottom-right, bottom-left, as the picture's viewer sees them.
     */
    public static final int TOP_LEFT = 0;
    /** The top-right corner's index. */
    public static final int TOP_RIGHT = 1;
    /** The bottom-right corner's index. */
    public static final int BOTTOM_RIGHT = 2;
    /** The bottom-left corner's index. */
    public static final int BOTTOM_LEFT = 3;

    private final DisplayShape shape;
    private final ForgeDirection up;
    private final ForgeDirection right;
    /** The top-left corner, {x, y, z}; the quad spans one block from it along {@link #right} and one down. */
    private final double[] origin;

    private ScreenSurface(DisplayShape shape, ForgeDirection up, ForgeDirection right, double[] origin) {
        this.shape = shape;
        this.up = up;
        this.right = right;
        this.origin = origin;
    }

    /** The surface of a display of this shape. */
    public static ScreenSurface of(DisplayShape shape) {
        ForgeDirection normal = shape.facing();
        ForgeDirection up;
        if (normal == ForgeDirection.UP) {
            up = Placement.quadrantDirection(shape.rotation());
        } else if (normal == ForgeDirection.DOWN) {
            up = Placement.quadrantDirection(shape.rotation())
                .getOpposite();
        } else {
            up = ForgeDirection.UP;
        }
        ForgeDirection right = cross(up, normal);
        // The face's centre: the block's centre moved along the normal to thickness / 16 in front of the back face
        double depth = shape.thickness() / (double) DisplayShape.MAX_THICKNESS - 0.5;
        double[] origin = new double[3];
        for (int i = 0; i < 3; i++) {
            origin[i] = 0.5 + depth * offset(normal, i) + 0.5 * offset(up, i) - 0.5 * offset(right, i);
        }
        return new ScreenSurface(shape, up, right, origin);
    }

    /** The shape this surface is of. */
    public DisplayShape shape() {
        return shape;
    }

    /** The side the picture faces, the quad's normal. */
    public ForgeDirection normal() {
        return shape.facing();
    }

    /** The direction of the picture's up edge. */
    public ForgeDirection up() {
        return up;
    }

    /** The direction pixel y grows in: against {@link #up()}. */
    public ForgeDirection down() {
        return up.getOpposite();
    }

    /** The direction of the picture's right edge, in which pixel x grows. */
    public ForgeDirection right() {
        return right;
    }

    /**
     * The point {@code u} of the way along the top edge and {@code v} of the way down the left edge, as a new {x, y,
     * z}: (0, 0) is the top-left corner and (1, 1) the bottom-right.
     */
    public double[] point(double u, double v) {
        ForgeDirection down = down();
        double[] p = new double[3];
        for (int i = 0; i < 3; i++) {
            p[i] = origin[i] + u * offset(right, i) + v * offset(down, i);
        }
        return p;
    }

    /**
     * Corner {@code index}, {@link #TOP_LEFT} to {@link #BOTTOM_LEFT}, as a new {x, y, z}.
     *
     * @throws IndexOutOfBoundsException for another index
     */
    public double[] corner(int index) {
        switch (index) {
            case TOP_LEFT:
                return point(0, 0);
            case TOP_RIGHT:
                return point(1, 0);
            case BOTTOM_RIGHT:
                return point(1, 1);
            case BOTTOM_LEFT:
                return point(0, 1);
            default:
                throw new IndexOutOfBoundsException("corner " + index);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ScreenSurface && ((ScreenSurface) other).shape.equals(shape);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ScreenSurface.class, shape);
    }

    /** For example "ScreenSurface[normal UP, up SOUTH, right WEST, top-left (1.0, 1.0, 1.0)]". */
    @Override
    public String toString() {
        return String.format(
            Locale.ROOT,
            "ScreenSurface[normal %s, up %s, right %s, top-left (%s, %s, %s)]",
            normal(),
            up,
            right,
            origin[0],
            origin[1],
            origin[2]);
    }

    /** Axis {@code i}, 0 for x, 1 for y and 2 for z, of the direction's unit offset. */
    private static int offset(ForgeDirection d, int i) {
        return i == 0 ? d.offsetX : i == 1 ? d.offsetY : d.offsetZ;
    }

    /** The direction of a × b, for two perpendicular directions. */
    private static ForgeDirection cross(ForgeDirection a, ForgeDirection b) {
        int x = a.offsetY * b.offsetZ - a.offsetZ * b.offsetY;
        int y = a.offsetZ * b.offsetX - a.offsetX * b.offsetZ;
        int z = a.offsetX * b.offsetY - a.offsetY * b.offsetX;
        for (ForgeDirection d : ForgeDirection.VALID_DIRECTIONS) {
            if (d.offsetX == x && d.offsetY == y && d.offsetZ == z) {
                return d;
            }
        }
        throw new IllegalArgumentException(a + " and " + b + " are not perpendicular");
    }
}
