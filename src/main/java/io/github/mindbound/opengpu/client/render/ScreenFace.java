package io.github.mindbound.opengpu.client.render;

import net.minecraftforge.common.util.ForgeDirection;

/**
 * What a display's face shows on this client (S12 plan P8.5 with amendment F15; P10; Q-S12-4 (a)), chosen by the pure
 * {@link #choose}, which the TESR uses and the GUI's picture follows (P12.3). The rule departs from
 * {@code GlHostCore.presentDevice}'s Javadoc, which maps every display with no present device to "no graphics"
 * (GlHostCore.java:458-462): "no graphics" is kept for this client's host having none or having failed (20:242;
 * 24:230; 28:1114's HO-2), while an unbound display, and in M0 every display a LAN guest or a dedicated server's
 * player sees, shows the chassis's dark front, since that client's graphics work and no content reaches it
 * (Q-S11-1 (c)). Also the TESR's back-face test, {@link #seenFromFront}. No GL and no client type, so the root's plain
 * test runs it.
 */
public enum ScreenFace {

    /** Nothing is drawn: the chassis's dark front shows, as for an unbound display. */
    NONE,
    /** The fixed "no graphics" bitmap over the inner square (20:242; 24:230; S12 plan P8.6). */
    NO_GRAPHICS,
    /** The present texture over the picture's corners. */
    PRESENT;

    /**
     * The face of a display:
     * <ul>
     * <li>unbound, its description's size 0 × 0: {@link #NONE};</li>
     * <li>bound, and this client's installed host unavailable ({@code NoRenderHost}, or a {@code GlRenderHost} failed
     * for good, H63): {@link #NO_GRAPHICS};</li>
     * <li>bound, the host available, and no present texture, {@code 0}: {@link #NONE}. On the integrated server's
     * client this lasts until the device's GL objects exist; on a LAN guest or a dedicated server's player it is every
     * display in M0 (P10);</li>
     * <li>otherwise {@link #PRESENT}. A bound device that has presented nothing shows the palette's entry 0, which its
     * surface is cleared to (DeviceGl.java:70).</li>
     * </ul>
     *
     * @param bound         whether the display's description gives a bound surface, a size above 0 × 0
     * @param hostAvailable whether this client's installed host is available ({@code RenderHost.available()})
     * @param texture       the present texture of the device bound to the display, or 0 for none
     */
    public static ScreenFace choose(boolean bound, boolean hostAvailable, int texture) {
        if (!bound) {
            return NONE;
        }
        if (!hostAvailable) {
            return NO_GRAPHICS;
        }
        return texture != 0 ? PRESENT : NONE;
    }

    /**
     * Whether the camera lies in front of the plane through {@code point} with this normal, the TESR's early return
     * for a viewer behind the face (S12 plan P8.7; OC's back-face test, ScreenRenderer.scala:55-58) [proposal]. The
     * camera is at the origin of the TESR's camera-relative coordinates, and the block's minimum corner at
     * {@code (x, y, z)} from it, so the plane's point lies at {@code (x, y, z) + point}. On the plane counts as behind.
     *
     * @param normal the face's normal, one of the six valid directions
     * @param point  a point of the plane in block coordinates, such as a corner of {@code ScreenSurface.picture}
     */
    public static boolean seenFromFront(ForgeDirection normal, double[] point, double x, double y, double z) {
        double along = normal.offsetX * (x + point[0]) + normal.offsetY * (y + point[1])
            + normal.offsetZ * (z + point[2]);
        // The camera, at the origin, is in front when the plane's point lies behind it along the normal
        return along < 0;
    }
}
