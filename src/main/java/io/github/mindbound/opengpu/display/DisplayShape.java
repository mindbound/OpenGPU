package io.github.mindbound.opengpu.display;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * The display's shape (RM:34; 24:248; decision 13 at 24:19; S11 plan P12.3-P12.4), saved in the tile entity's NBT and
 * sent in its description, each value a byte under its own key:
 * <ul>
 * <li>{@value #FACING}, 0-5: the {@link ForgeDirection} the picture faces, SOUTH by default, as OC's
 * {@code Rotatable}'s pitch NORTH and yaw SOUTH (Rotatable.scala:29-32) [proposal];</li>
 * <li>{@value #ROTATION}, 0-3 on a floor or a ceiling and 0 on a wall: the placer's yaw quadrant, 0 south, 1 west, 2
 * north and 3 east, which {@link ScreenSurface} turns into the picture's up edge;</li>
 * <li>{@value #THICKNESS}, 1-16: pixels, 16 by default, the cube of decision 6 (24:248);</li>
 * <li>{@value #TILT_H} and {@value #TILT_V}, whole degrees: the two axes of decision 13's tilt (24:19; RM:110), both
 * 0, the only tilt M0 has; M1.5's settings widen the range (24:258).</li>
 * </ul>
 * M1 is the first release, so M0's keys need no migration, and M1.5 fills the fields M0 already saves (24:258).
 * Immutable.
 */
public final class DisplayShape {

    /** The facing's key. */
    public static final String FACING = "opengpu:facing";
    /** The rotation's key. */
    public static final String ROTATION = "opengpu:rotation";
    /** The thickness's key. */
    public static final String THICKNESS = "opengpu:thickness";
    /** The horizontal tilt's key. */
    public static final String TILT_H = "opengpu:tiltH";
    /** The vertical tilt's key. */
    public static final String TILT_V = "opengpu:tiltV";

    /** The thickness of the cube, the default and the most a display has (24:248). */
    public static final int MAX_THICKNESS = 16;

    /** A cube facing SOUTH, with no rotation and no tilt: what a display is until it is placed or read. */
    public static final DisplayShape DEFAULT = new DisplayShape(ForgeDirection.SOUTH, 0, MAX_THICKNESS, 0, 0);

    /** NBT's tag type of a byte. */
    private static final int BYTE = 1;

    private final ForgeDirection facing;
    private final int rotation;
    private final int thickness;
    private final int tiltH;
    private final int tiltV;

    private DisplayShape(ForgeDirection facing, int rotation, int thickness, int tiltH, int tiltV) {
        this.facing = facing;
        this.rotation = rotation;
        this.thickness = thickness;
        this.tiltH = tiltH;
        this.tiltV = tiltV;
    }

    /**
     * A shape of these values.
     *
     * @throws IllegalArgumentException for a facing that is not one of the six directions, a rotation other than 0
     *                                  on a wall or outside 0-3, a thickness outside 1-16, or a tilt other than 0
     */
    public static DisplayShape of(ForgeDirection facing, int rotation, int thickness, int tiltH, int tiltV) {
        String[] problems = { problem(FACING, facing, facingProblem(facing)),
            problem(ROTATION, rotation, rotationProblem(facing, rotation)),
            problem(THICKNESS, thickness, thicknessProblem(thickness)), problem(TILT_H, tiltH, tiltProblem(tiltH)),
            problem(TILT_V, tiltV, tiltProblem(tiltV)) };
        for (String problem : problems) {
            if (problem != null) {
                throw new IllegalArgumentException(problem);
            }
        }
        return new DisplayShape(facing, rotation, thickness, tiltH, tiltV);
    }

    /**
     * The shape a display gets when it is placed: the facing and the rotation from the placer ({@link Placement}),
     * the cube's thickness and no tilt (24:248).
     *
     * @throws IllegalArgumentException as {@link #of}
     */
    public static DisplayShape placed(ForgeDirection facing, int rotation) {
        return of(facing, rotation, MAX_THICKNESS, 0, 0);
    }

    /**
     * Reads the five keys. A value that is missing, not a byte or out of range reads as {@link #DEFAULT}'s, and a
     * rotation that does not fit the facing read reads as 0; each such value adds one line to {@code problems}
     * (S11 plan P12.4) [proposal].
     */
    public static DisplayShape read(NBTTagCompound nbt, List<String> problems) {
        Objects.requireNonNull(problems, "problems");
        ForgeDirection f = DEFAULT.facing;
        if (isByte(nbt, FACING, problems)) {
            int v = nbt.getByte(FACING);
            ForgeDirection read = v >= 0 && v < ForgeDirection.VALID_DIRECTIONS.length
                ? ForgeDirection.getOrientation(v)
                : null;
            String problem = problem(FACING, v, facingProblem(read));
            if (problem == null) {
                f = read;
            } else {
                problems.add(problem);
            }
        }
        int r = DEFAULT.rotation;
        if (isByte(nbt, ROTATION, problems)) {
            int v = nbt.getByte(ROTATION);
            String problem = problem(ROTATION, v, rotationProblem(f, v));
            if (problem == null) {
                r = v;
            } else {
                problems.add(problem);
            }
        }
        int t = DEFAULT.thickness;
        if (isByte(nbt, THICKNESS, problems)) {
            int v = nbt.getByte(THICKNESS);
            String problem = problem(THICKNESS, v, thicknessProblem(v));
            if (problem == null) {
                t = v;
            } else {
                problems.add(problem);
            }
        }
        return new DisplayShape(f, r, t, readTilt(nbt, TILT_H, problems), readTilt(nbt, TILT_V, problems));
    }

    /** Writes the five keys, each a byte. */
    public void write(NBTTagCompound nbt) {
        nbt.setByte(FACING, (byte) facing.ordinal());
        nbt.setByte(ROTATION, (byte) rotation);
        nbt.setByte(THICKNESS, (byte) thickness);
        nbt.setByte(TILT_H, (byte) tiltH);
        nbt.setByte(TILT_V, (byte) tiltV);
    }

    /** The side the picture faces, one of the six valid directions. */
    public ForgeDirection facing() {
        return facing;
    }

    /** The placer's yaw quadrant on a floor or a ceiling, 0-3; 0 on a wall. */
    public int rotation() {
        return rotation;
    }

    /** The thickness in pixels, 1-16. */
    public int thickness() {
        return thickness;
    }

    /** The horizontal tilt in whole degrees; 0 in M0. */
    public int tiltH() {
        return tiltH;
    }

    /** The vertical tilt in whole degrees; 0 in M0. */
    public int tiltV() {
        return tiltV;
    }

    /** Whether the picture faces up or down, where the rotation counts. */
    public boolean isFloorOrCeiling() {
        return isFloorOrCeiling(facing);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DisplayShape)) {
            return false;
        }
        DisplayShape that = (DisplayShape) other;
        return facing == that.facing && rotation == that.rotation
            && thickness == that.thickness
            && tiltH == that.tiltH
            && tiltV == that.tiltV;
    }

    @Override
    public int hashCode() {
        return Objects.hash(facing, rotation, thickness, tiltH, tiltV);
    }

    /** For example "DisplayShape[UP r3, 16 px, tilt 0/0]". */
    @Override
    public String toString() {
        return String
            .format(Locale.ROOT, "DisplayShape[%s r%d, %d px, tilt %d/%d]", facing, rotation, thickness, tiltH, tiltV);
    }

    private static boolean isFloorOrCeiling(ForgeDirection direction) {
        return direction == ForgeDirection.UP || direction == ForgeDirection.DOWN;
    }

    /** Whether the key holds a byte; a missing key or another tag type adds a problem. */
    private static boolean isByte(NBTTagCompound nbt, String key, List<String> problems) {
        if (nbt.hasKey(key, BYTE)) {
            return true;
        }
        problems.add(key + (nbt.hasKey(key) ? ": not a byte" : ": missing"));
        return false;
    }

    private static int readTilt(NBTTagCompound nbt, String key, List<String> problems) {
        if (isByte(nbt, key, problems)) {
            int v = nbt.getByte(key);
            String problem = problem(key, v, tiltProblem(v));
            if (problem == null) {
                return v;
            }
            problems.add(problem);
        }
        return 0;
    }

    /** "&lt;key&gt; &lt;value&gt;: &lt;problem&gt;", or null for no problem. */
    private static String problem(String key, Object value, String problem) {
        return problem == null ? null : key + " " + value + ": " + problem;
    }

    private static String facingProblem(ForgeDirection facing) {
        return facing == null || facing == ForgeDirection.UNKNOWN ? "not one of the six facings" : null;
    }

    private static String rotationProblem(ForgeDirection facing, int rotation) {
        if (isFloorOrCeiling(facing)) {
            return rotation >= 0 && rotation < 4 ? null : "a rotation is 0-3";
        }
        return rotation == 0 ? null : "a wall has rotation 0";
    }

    private static String thicknessProblem(int thickness) {
        return thickness >= 1 && thickness <= MAX_THICKNESS ? null : "a thickness is 1-" + MAX_THICKNESS;
    }

    private static String tiltProblem(int tilt) {
        // M0 has no tilt setting (24:258's settings are M1.5's), so 0 is the one tilt
        return tilt == 0 ? null : "a tilt is 0 in M0";
    }
}
