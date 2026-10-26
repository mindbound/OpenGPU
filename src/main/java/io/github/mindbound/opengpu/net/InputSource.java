package io.github.mindbound.opengpu.net;

import net.minecraft.tileentity.TileEntity;

/**
 * The player who sent a pointer packet, and that player's world, as {@link InputHandler}'s checks ask them (S11 plan
 * U5.4, U5.8): the seam between the checks and the game, so tests need no player and no world.
 * {@link PlayerInputSource} is the game's, over the {@code EntityPlayerMP}. The handler computes every argument
 * itself, the block's centre and its chunk, and a source only forwards them. Called on the server thread only, after
 * the handler's checks of the thread and the payload have passed.
 */
public interface InputSource {

    /**
     * The squared distance from the player to the point: {@code Entity.getDistanceSq}, arithmetic on the player's
     * position (Entity.java:1308-1314).
     */
    double distanceSq(double x, double y, double z);

    /**
     * Whether the block's chunk is loaded in the player's world: {@code World.blockExists}, which asks the chunk
     * provider and loads nothing (World.java:417-420).
     */
    boolean blockLoaded(int x, int y, int z);

    /**
     * Whether the player watches the chunk and its client has the chunk's data:
     * {@code PlayerManager.isPlayerWatchingChunk}, which creates no watcher (PlayerManager.java:315-319).
     */
    boolean watchesChunk(int chunkX, int chunkZ);

    /**
     * The tile entity at the block in the player's world, or null. This can change the world: it loads a missing chunk
     * and makes a tile entity a block should have and lacks (World.java:2804, :480-483; Chunk.java:912-939), so the
     * handler asks it after every check that changes nothing.
     */
    TileEntity tileEntity(int x, int y, int z);

    /** The player's entity, which OC's machine checks before it signals (Machine.scala:645-656). */
    Object entity();

    /** The player's name, which the signal carries when OC's {@code misc.inputUsername} is set. */
    String name();

    /** Marks the player active, as OC marks a player for its packets (PacketHandler.scala:36-44). */
    void markActive();
}
