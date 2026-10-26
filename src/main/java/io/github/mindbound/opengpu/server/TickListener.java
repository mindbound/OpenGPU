package io.github.mindbound.opengpu.server;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The listener for the start of each server tick on FML's bus, which {@code CommonProxy.preInit} registers, as OC
 * registers its own tick handler there (ModOpenComputers.scala:94; EventHandler.scala:148-149) (D16; Q-S11-17). FML
 * posts the event first in {@code MinecraftServer.tick}, on the server thread, before the worlds tick their tile
 * entities (MinecraftServer.java:607, :707; FMLCommonHandler.java:258-261). It is registered once on both physical
 * sides, so an integrated server's ticks reach it too; a client of a remote server posts none.
 * <p>
 * It runs the running session's {@link TickQueue}, whose drain tests the thread itself. It reads only
 * {@link ServerSession#current()}, a volatile read, and does nothing while no session runs [proposal: no tick comes
 * then, since a server starts its session before its tick loop and stops it after the loop, MinecraftServer.java:449,
 * :551, so nothing is counted, unlike InputListener's packets]. No test loads it (S11 plan P15.1, U5.9).
 */
public final class TickListener {

    /** The one listener, registered once. */
    public static final TickListener INSTANCE = new TickListener();

    private TickListener() {}

    /** The start of a server tick: see the class comment. */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        ServerSession session = ServerSession.current();
        if (session != null) {
            session.tickQueue()
                .drain();
        }
    }
}
