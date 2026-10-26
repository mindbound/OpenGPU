package io.github.mindbound.opengpu.fixtures;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.Entity;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;

/**
 * A {@link World} for tests in a JVM where no world can be built (S11 amendments B6): a concrete subclass allocated
 * without a constructor, since World is abstract (World.java:73) and its constructor reaches the save handler and the
 * world provider. Its fields stay at their defaults, so only what is modelled here works: {@code isRemote} as given,
 * {@link #getTileEntity} from the tile entities a test puts, and {@link #markBlockForUpdate} and
 * {@link #markTileEntityChunkModified}, which record the block, and {@link #func_147479_m}, the render update a
 * client's description asks for, which records it too (S12 amendments I9); any other world access would meet a null.
 * Not thread-safe: the server thread's, or the client thread's for a remote world.
 */
public final class BareWorld extends World {

    // A constructor never runs, so these are set by allocate
    private Map<String, TileEntity> tiles;
    private List<String> updates;
    private List<String> modified;
    private List<String> renderUpdates;

    private BareWorld() {
        super((ISaveHandler) null, null, (WorldSettings) null, (WorldProvider) null, (Profiler) null);
    }

    /** A new bare world, remote or not. */
    public static BareWorld allocate(boolean remote) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            BareWorld world = (BareWorld) unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(theUnsafe.get(null), BareWorld.class);
            world.isRemote = remote;
            world.tiles = new HashMap<>();
            world.updates = new ArrayList<>();
            world.modified = new ArrayList<>();
            world.renderUpdates = new ArrayList<>();
            return world;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot allocate a world", e);
        }
    }

    /** Puts {@code tile} at its own position in this world, as a chunk holds it. */
    public void put(TileEntity tile) {
        tile.setWorldObj(this);
        tiles.put(key(tile.xCoord, tile.yCoord, tile.zCoord), tile);
    }

    /** The blocks {@link #markBlockForUpdate} was called for, as "x y z", in order. */
    public List<String> updates() {
        return new ArrayList<>(updates);
    }

    @Override
    public TileEntity getTileEntity(int x, int y, int z) {
        return tiles.get(key(x, y, z));
    }

    @Override
    public void markBlockForUpdate(int x, int y, int z) {
        updates.add(key(x, y, z));
    }

    /** The blocks whose chunk {@link #markTileEntityChunkModified} marked changed, as "x y z", in order. */
    public List<String> modified() {
        return new ArrayList<>(modified);
    }

    /** Records the block; World's own would reach the chunk provider, which a bare world has not. */
    @Override
    public void markTileEntityChunkModified(int x, int y, int z, TileEntity tile) {
        modified.add(key(x, y, z));
    }

    /** The blocks {@link #func_147479_m} marked for a render update, as "x y z", in order. */
    public List<String> renderUpdates() {
        return new ArrayList<>(renderUpdates);
    }

    /**
     * Records the block; World's own walks {@code worldAccesses} (World.java:1116-1122), which a bare world never set.
     */
    @Override
    public void func_147479_m(int x, int y, int z) {
        renderUpdates.add(key(x, y, z));
    }

    @Override
    protected IChunkProvider createChunkProvider() {
        return null;
    }

    @Override
    protected int func_152379_p() {
        return 0;
    }

    @Override
    public Entity getEntityByID(int id) {
        return null;
    }

    @Override
    public String toString() {
        return "BareWorld[remote " + isRemote + "]";
    }

    private static String key(int x, int y, int z) {
        return x + " " + y + " " + z;
    }
}
