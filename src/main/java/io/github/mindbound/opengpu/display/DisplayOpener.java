package io.github.mindbound.opengpu.display;

import java.util.Objects;

/**
 * The hook through which a display's right-click opens its GUI on a client (24:17 decision 11, 24:222; 20:173; S12
 * plan P12.1 with amendment F16). Common code, naming no client type and no client package string (24 §3.1 check 1),
 * as {@code RenderHosts.install} is: {@link DisplayBlock#onBlockActivated} calls {@link #open} on a client's world
 * only, and opens nothing until {@code ClientProxy.init} installs the client's opener, which shows the display's
 * screen. A dedicated server never installs one.
 * <p>
 * It replaces S11's U5.13 "proxy hook that opens the GUI on the client" (S11 plan U5.13): a {@code CommonProxy} method
 * would have the block reach the proxy, so a test that builds the block and right-clicks it would load
 * {@code CommonProxy}, which no root test may (S11 plan P15.1). Not an {@code IGuiHandler} either: there is no
 * container, and a handler needs {@code @Mod.Instance}, a registration in common code and a client half for no gain
 * (S12 plan P12.1). A test that installs its own opener puts {@link #NONE} back in {@code finally}.
 */
public final class DisplayOpener {

    /** Opens a display's GUI, on the client main thread. */
    public interface Opener {

        /** Opens the GUI of {@code tile}, a client's display tile entity. */
        void open(DisplayTile tile);
    }

    /** Opens nothing: the hook until a client installs its opener, and always on a dedicated server. */
    public static final Opener NONE = tile -> {};

    /** Written once on the client main thread in FML init, read there; volatile as {@code RenderHosts}' field is. */
    private static volatile Opener opener = NONE;

    private DisplayOpener() {}

    /**
     * Installs the client's opener, from {@code ClientProxy.init}, or {@link #NONE} again, from a test.
     *
     * @throws NullPointerException for a null opener
     */
    public static void install(Opener installed) {
        opener = Objects.requireNonNull(installed, "installed");
    }

    /**
     * Opens the GUI of {@code tile} through the installed opener; nothing before a client installs one.
     *
     * @throws NullPointerException for a null tile
     */
    public static void open(DisplayTile tile) {
        opener.open(Objects.requireNonNull(tile, "tile"));
    }
}
