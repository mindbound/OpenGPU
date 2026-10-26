package io.github.mindbound.opengpu.net;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;

import cpw.mods.fml.common.network.FMLNetworkEvent;

/**
 * The game's {@link InputSource}: the {@code EntityPlayerMP} who sent a packet, and its world, a {@code WorldServer}
 * (S11 plan U5.8). It forwards the handler's arguments unchanged. It is the only class of input's server half that
 * names a player or a world, and no test loads it (U5.9): S12's in-game check runs it first (S11 plan N7).
 */
final class PlayerInputSource implements InputSource {

    private final EntityPlayerMP player;

    private PlayerInputSource(EntityPlayerMP player) {
        this.player = player;
    }

    /**
     * The sender of a server's custom packet: the player of its play handler, as OC takes it
     * (PacketHandler.scala:41). FML builds the event from a {@code NetHandlerPlayServer} on both physical sides
     * (FMLEventChannel.java:61-65).
     */
    static PlayerInputSource of(FMLNetworkEvent.ServerCustomPacketEvent event) {
        return new PlayerInputSource(((NetHandlerPlayServer) event.handler).playerEntity);
    }

    @Override
    public double distanceSq(double x, double y, double z) {
        return player.getDistanceSq(x, y, z);
    }

    @Override
    public boolean blockLoaded(int x, int y, int z) {
        return world().blockExists(x, y, z);
    }

    @Override
    public boolean watchesChunk(int chunkX, int chunkZ) {
        return world().getPlayerManager()
            .isPlayerWatchingChunk(player, chunkX, chunkZ);
    }

    @Override
    public TileEntity tileEntity(int x, int y, int z) {
        return world().getTileEntity(x, y, z);
    }

    @Override
    public Object entity() {
        return player;
    }

    @Override
    public String name() {
        return player.getCommandSenderName();
    }

    /** {@code func_143004_u}, the idle timer's reset (EntityPlayerMP.java:1154-1157). */
    @Override
    public void markActive() {
        player.func_143004_u();
    }

    private WorldServer world() {
        return player.getServerForPlayer();
    }
}
