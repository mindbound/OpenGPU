package io.github.mindbound.opengpu.net;

import java.util.concurrent.atomic.AtomicLong;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import io.github.mindbound.opengpu.OpenGPU;
import io.github.mindbound.opengpu.server.ServerSession;
import io.netty.buffer.ByteBuf;

/**
 * The server's listener on the {@value Channel#NAME} channel's own bus, which {@code CommonProxy.preInit} registers
 * after making the channel, as OC registers its packet handler (03:55; PacketHandler.scala:39-41; S11 plan U5.8). It
 * runs on both physical sides, so the integrated server's packets arrive too, on the server thread (S11 plan U5.2).
 * <p>
 * It hands each payload to the running session's {@link InputHandler}. Before that it reads only what is safe on any
 * thread: the packet's final field and the session, a volatile read ({@link ServerSession#current()}'s second reader,
 * beside the card environments'). The handler's check 0 tests the thread before any player or world is touched, and
 * the sender is looked up only behind a supplier that the handler calls after its payload checks.
 * <p>
 * It never kicks: FML kicks the player when a handler throws (FMLProxyPacket.java:102-111;
 * NetworkDispatcher.java:246-265), so a RuntimeException out of the handler is caught here and counted as
 * {@code FAILED}. OC's handler catches every Throwable (PacketHandler.scala:48); this one lets an Error through on
 * purpose, such as a NoClassDefFoundError from a slip of the client-server split, so FML logs it and the slip cannot
 * hide [proposal]. It never replies: {@code event.reply} would make FML send from here (FMLEventChannel.java:104-108),
 * and the server sends nothing on the channel in M0 (jarTest's check 8). Every path releases the payload, which
 * neither 1.7.10 nor FML does (09:83, "released by hand"; PacketHandler.scala:54-57).
 */
public final class InputListener {

    /** The one listener, registered once. */
    public static final InputListener INSTANCE = new InputListener();

    /** Packets that came while no session ran: the one outcome with no handler to count it [proposal]. */
    private static final AtomicLong NO_SESSION = new AtomicLong();

    private InputListener() {}

    /** A packet on the channel, at a server: see the class comment. */
    @SubscribeEvent
    public void onServerPacket(FMLNetworkEvent.ServerCustomPacketEvent event) {
        ByteBuf payload = null;
        try {
            payload = event.packet.payload();
            ServerSession session = ServerSession.current();
            if (session == null) {
                if (NO_SESSION.incrementAndGet() == 1) {
                    OpenGPU.LOG
                        .debug("OpenGPU input came with no server session and was dropped; later ones are counted");
                }
                return;
            }
            InputHandler input = session.input();
            try {
                input.receive(payload, () -> PlayerInputSource.of(event));
            } catch (RuntimeException e) {
                input.failed(e);
            }
        } finally {
            if (payload != null && payload.refCnt() > 0) {
                payload.release();
            }
        }
    }

    /** Packets that came while no session ran, in this JVM. */
    public static long noSession() {
        return NO_SESSION.get();
    }
}
