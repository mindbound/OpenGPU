package io.github.mindbound.opengpu.display;

import net.minecraftforge.common.util.ForgeDirection;

/**
 * Where a placed display faces, from the placer's pitch and yaw, by OC's {@code Rotatable} rule (Q-S11-15; S11 plan
 * P12.3, amendments C1), so OpenGPU's display places as OC's screens beside it do. The placer's look has a vertical
 * part, {@code round(pitch / 90) + 1} into (UP, none, DOWN), and a horizontal part, {@code round(yaw / 90) & 3} into
 * (SOUTH, WEST, NORTH, EAST), with OC's float arithmetic and {@link Math#round(float)}, which rounds halves up
 * (Rotatable.scala:20-22, :54-57). The picture faces against the look: against the vertical part when there is one,
 * else against the horizontal part (OC's {@code invertRotation}, Item.scala:94-99; Rotatable.scala:67-76). So a display
 * placed while looking down by 45° or more faces up, and one placed while looking up by more than 45° faces down.
 * <p>
 * The rotation is the horizontal part's quadrant on a floor or a ceiling, 0 on a wall (P12.3); {@link ScreenSurface}
 * turns it into the picture's up edge, upright for the placer. Pure functions, so tests need no world.
 */
public final class Placement {

    /** OC's {@code pitch2Direction}: the look's vertical part, NORTH meaning none (Rotatable.scala:20). */
    private static final ForgeDirection[] PITCH = { ForgeDirection.UP, ForgeDirection.NORTH, ForgeDirection.DOWN };
    /** OC's {@code yaw2Direction}: the look's horizontal part, by quadrant (Rotatable.scala:22). */
    private static final ForgeDirection[] YAW = { ForgeDirection.SOUTH, ForgeDirection.WEST, ForgeDirection.NORTH,
        ForgeDirection.EAST };

    private Placement() {}

    /** The shape of a display placed by a placer looking at {@code pitch} and {@code yaw}, in degrees. */
    public static DisplayShape shape(float pitch, float yaw) {
        ForgeDirection facing = facing(pitch, yaw);
        return DisplayShape.placed(facing, rotation(facing, yaw));
    }

    /** The side the picture faces: against the placer's look. */
    public static ForgeDirection facing(float pitch, float yaw) {
        // Minecraft keeps the pitch in -90..90; the clamp only keeps any other float in the table
        int vertical = Math.max(0, Math.min(2, Math.round(pitch / 90f) + 1));
        ForgeDirection look = PITCH[vertical] == ForgeDirection.NORTH ? lookAlong(yaw) : PITCH[vertical];
        return look.getOpposite();
    }

    /** The yaw's quadrant on a floor or a ceiling, 0-3; 0 on a wall. */
    public static int rotation(ForgeDirection facing, float yaw) {
        return facing == ForgeDirection.UP || facing == ForgeDirection.DOWN ? quadrant(yaw) : 0;
    }

    /** The yaw's quadrant: 0 south, 1 west, 2 north, 3 east, Minecraft's yaw order, for any yaw. */
    public static int quadrant(float yaw) {
        return Math.round(yaw / 360f * 4f) & 3;
    }

    /** The horizontal direction of quadrant {@code q}, 0-3. */
    static ForgeDirection quadrantDirection(int q) {
        return YAW[q & 3];
    }

    private static ForgeDirection lookAlong(float yaw) {
        return YAW[quadrant(yaw)];
    }
}
