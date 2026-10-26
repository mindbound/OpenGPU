package io.github.mindbound.opengpu.net;

import cpw.mods.fml.common.network.FMLEventChannel;
import cpw.mods.fml.common.network.NetworkRegistry;

/**
 * OpenGPU's one FML channel, {@value #NAME} (09:83; 13:131): an {@link FMLEventChannel}, made in OpenGPU's preInit,
 * since FML records the active mod container when a channel is made (FMLEmbeddedChannel.java:21-32). In M0 it carries
 * only the client-to-server {@link PointerPacket}, whose server listener, {@link InputListener}, CommonProxy registers
 * on it, and whose client sender is S12's; frames from the server to clients are M4's (RM:177-179; Q-S11-1 (c), S11
 * decisions D1). Common code calls no {@code send*} of {@code FMLEventChannel} or {@code SimpleNetworkWrapper} (13:9;
 * jarTest's check 8): the server sends nothing on the channel in M0, and only a send from the server thread can be
 * check 8's named exception [proposal, stricter than 13:117 and 13:139]. M4's pool send does not use the channel's
 * {@code send*}: it goes through {@code NetworkManager.scheduleOutboundPacket} (13:117; S11 decisions D12).
 */
public final class Channel {

    /**
     * The channel's name, a compile-time constant, so code that names it never loads this class, whose channel only a
     * launched game can make (S11 spike P5). Names are case-sensitive, at most 20 characters
     * (S3FPacketCustomPayload.java:42), and may not start with "MC|", "FML" or the character U+0001
     * (NetworkRegistry.java:141-146).
     */
    public static final String NAME = "OpenGPU";

    private static volatile FMLEventChannel channel;

    private Channel() {}

    /**
     * Makes the channel, once, from {@code CommonProxy.preInit}.
     *
     * @throws IllegalStateException when it exists already
     */
    public static void init() {
        if (channel != null) {
            throw new IllegalStateException("the " + NAME + " channel exists already");
        }
        channel = NetworkRegistry.INSTANCE.newEventDrivenChannel(NAME);
    }

    /**
     * The channel, for registering its handlers.
     *
     * @throws IllegalStateException before {@link #init()}
     */
    public static FMLEventChannel get() {
        FMLEventChannel made = channel;
        if (made == null) {
            throw new IllegalStateException("the " + NAME + " channel is made in preInit");
        }
        return made;
    }
}
