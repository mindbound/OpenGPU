package io.github.mindbound.opengpu.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.tileentity.TileEntity;

/**
 * A sender of pointer packets and its world for {@link InputHandler}'s tests, with no player and no world (S11 plan
 * U5.9): blocks with a loaded flag and a tile entity, a set of watched chunks, and a player position from which it
 * computes the squared distance as {@code Entity.getDistanceSq} does. It logs every world query with its arguments, in
 * order, so a test sees which checks asked what; {@link #markActive()} is counted. Not thread-safe: the server
 * thread's.
 */
final class FakeInputSource implements InputSource {

    private final Set<String> loaded = new HashSet<>();
    private final Map<String, TileEntity> tiles = new HashMap<>();
    private final Set<String> watched = new HashSet<>();
    private final List<String> queries = new ArrayList<>();
    /** The player's entity, a marker that the signal must carry first. */
    private final Object entity = new Object() {

        @Override
        public String toString() {
            return "FakePlayer";
        }
    };
    private double x;
    private double y;
    private double z;
    private int active;

    /**
     * A sender two blocks east of the tile entity's block centre, whose block is loaded, whose chunk it watches, and
     * where the tile entity is.
     */
    static FakeInputSource beside(TileEntity tile) {
        return new FakeInputSource().at(tile.xCoord + 2.5, tile.yCoord + 0.5, tile.zCoord + 0.5)
            .loaded(tile.xCoord, tile.yCoord, tile.zCoord)
            .watching(tile.xCoord >> 4, tile.zCoord >> 4)
            .tile(tile);
    }

    /** Puts the player at the point. */
    FakeInputSource at(double px, double py, double pz) {
        x = px;
        y = py;
        z = pz;
        return this;
    }

    FakeInputSource loaded(int bx, int by, int bz) {
        loaded.add(key(bx, by, bz));
        return this;
    }

    FakeInputSource unloaded(int bx, int by, int bz) {
        loaded.remove(key(bx, by, bz));
        return this;
    }

    FakeInputSource watching(int chunkX, int chunkZ) {
        watched.add(chunkX + " " + chunkZ);
        return this;
    }

    FakeInputSource notWatching(int chunkX, int chunkZ) {
        watched.remove(chunkX + " " + chunkZ);
        return this;
    }

    /** Puts the tile entity at its own block. */
    FakeInputSource tile(TileEntity tile) {
        return tile(tile.xCoord, tile.yCoord, tile.zCoord, tile);
    }

    /** Puts the tile entity, or none for null, at the block. */
    FakeInputSource tile(int bx, int by, int bz, TileEntity tile) {
        if (tile == null) {
            tiles.remove(key(bx, by, bz));
        } else {
            tiles.put(key(bx, by, bz), tile);
        }
        return this;
    }

    /** The world queries so far, such as "distanceSq -0.5 64.5 -16.5", in order. */
    List<String> queries() {
        return new ArrayList<>(queries);
    }

    /** Whether a query of that method was made. */
    boolean asked(String method) {
        return queries.stream()
            .anyMatch(q -> q.startsWith(method + " "));
    }

    /** How often the player was marked active. */
    int markedActive() {
        return active;
    }

    @Override
    public double distanceSq(double cx, double cy, double cz) {
        queries.add("distanceSq " + cx + " " + cy + " " + cz);
        double dx = x - cx;
        double dy = y - cy;
        double dz = z - cz;
        return dx * dx + dy * dy + dz * dz;
    }

    @Override
    public boolean blockLoaded(int bx, int by, int bz) {
        queries.add("blockLoaded " + key(bx, by, bz));
        return loaded.contains(key(bx, by, bz));
    }

    @Override
    public boolean watchesChunk(int chunkX, int chunkZ) {
        queries.add("watchesChunk " + chunkX + " " + chunkZ);
        return watched.contains(chunkX + " " + chunkZ);
    }

    @Override
    public TileEntity tileEntity(int bx, int by, int bz) {
        queries.add("tileEntity " + key(bx, by, bz));
        return tiles.get(key(bx, by, bz));
    }

    @Override
    public Object entity() {
        return entity;
    }

    @Override
    public String name() {
        return "Alex";
    }

    @Override
    public void markActive() {
        active++;
    }

    @Override
    public String toString() {
        return "FakeInputSource[at " + x + " " + y + " " + z + "]";
    }

    private static String key(int bx, int by, int bz) {
        return bx + " " + by + " " + bz;
    }
}
