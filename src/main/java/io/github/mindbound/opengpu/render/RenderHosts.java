package io.github.mindbound.opengpu.render;

import java.util.Objects;

import net.minecraft.server.MinecraftServer;

/**
 * Which render host a server's devices get (21 §1.3, 21:51-65; 24:171-175). Common code, naming no client type, so a
 * dedicated server loads it as it is (24 §3.1 check 1). The client proxy installs its host here (21:59; S12 plan
 * P7.1). A dedicated server never makes the client proxy, and whatever is installed, {@link #forServer} gives it the
 * dedicated installation, so a server never renders (24:228).
 * <p>
 * Nothing is installed until the client proxy's init runs, and an integrated server that finds nothing refuses to
 * start (Q-S11-8; Q-S12-5 (a)). In game that cannot happen: {@code FMLServerStartingEvent} comes after client init and
 * after the title screen, so the install is always in place before any integrated server captures it (S12 plan P2.3,
 * K3), and the client proxy installs {@code NoRenderHost} with a reason when its GL probe throws (S12 amendments I11).
 * Only a JVM with no client proxy, such as the root's tests, holds nothing here.
 */
public final class RenderHosts {

    private static volatile Installation installed;

    private RenderHosts() {}

    /**
     * Installs the client's host, from {@code ClientProxy.init} (21:59; S12 plan P7.1): {@code GlRenderHost} when GL
     * suffices, else {@code NoRenderHost} with one of 24:125's GL reasons or S12's. A session already started keeps
     * the host it captured (21:63). Tests never call it (S11 amendments B1).
     *
     * @throws NullPointerException for a null installation
     */
    public static void install(Installation installation) {
        installed = Objects.requireNonNull(installation, "installation");
    }

    /**
     * The host for a server that is starting: {@link Installation#dedicated()} on a dedicated server and the installed
     * one on an integrated server (21:51-65). The server thread asks once per session, at
     * {@code FMLServerStartingEvent}, and the only side test is {@code isDedicatedServer()} (24:175).
     *
     * @throws IllegalStateException for an integrated server when nothing is installed
     */
    public static Installation forServer(MinecraftServer server) {
        return forServer(server.isDedicatedServer());
    }

    /**
     * {@link #forServer(MinecraftServer)} by the server's {@code isDedicatedServer()}.
     *
     * @throws IllegalStateException for an integrated server when nothing is installed
     */
    public static Installation forServer(boolean dedicatedServer) {
        if (dedicatedServer) {
            return Installation.dedicated();
        }
        Installation current = installed;
        if (current == null) {
            throw new IllegalStateException(
                "an integrated server is starting with no render host installed: the client proxy's init installs one"
                    + " before any integrated server starts (S12 plan P2.3, K3), so this JVM has no client proxy or"
                    + " its init did not run");
        }
        return current;
    }
}
