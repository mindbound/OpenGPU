package io.github.mindbound.opengpu.render;

import java.util.Objects;

import net.minecraft.server.MinecraftServer;

/**
 * Which render host a server's devices get (21 §1.3, 21:51-65; 24:171-175). Common code, naming no client type, so a
 * dedicated server loads it as it is (24 §3.1 check 1). The client proxy installs its host here (S12; 21:59). A
 * dedicated server never makes the client proxy, and whatever is installed, {@link #forServer} gives it the
 * dedicated installation, so a server never renders (24:228).
 */
public final class RenderHosts {

    private static volatile Installation installed = Installation.none(Installation.NOT_INSTALLED);

    private RenderHosts() {}

    /**
     * The installed host: {@link io.github.mindbound.opengpu.core.render.NoRenderHost} with
     * {@link Installation#NOT_INSTALLED} until S12's {@code ClientProxy.init} installs another.
     */
    public static Installation installed() {
        return installed;
    }

    /**
     * Installs the client's host, from {@code ClientProxy.init} (S12; 21:59): {@code GlRenderHost} when GL suffices,
     * else {@code NoRenderHost} with one of 24:125's GL reasons. A session already started keeps the host it captured
     * (21:63). Tests never call it (S11 amendments B1).
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
     */
    public static Installation forServer(MinecraftServer server) {
        return forServer(server.isDedicatedServer());
    }

    /** {@link #forServer(MinecraftServer)} by the server's {@code isDedicatedServer()}. */
    public static Installation forServer(boolean dedicatedServer) {
        return dedicatedServer ? Installation.dedicated() : installed;
    }
}
