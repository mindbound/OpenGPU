package io.github.mindbound.opengpu.display;

import net.minecraft.tileentity.TileEntity;

import io.github.mindbound.opengpu.card.NodeFactory;

/**
 * Display tile entities for tests of other packages, through {@link DisplayTile}'s package-private
 * {@code DisplayTile(NodeFactory)} (S11 amendments B4): the node comes from a test's factory, such as FakeNodes, and
 * no world is needed for the binding. Also the tile entity's id mapping, which a test needs before a display writes its
 * NBT (S11 plan P15.1).
 */
public final class Displays {

    /** The tile entity's id, as Content registers it. */
    public static final String TILE_ID = "opengpu:screen";

    private static boolean mapped;

    private Displays() {}

    /** A display whose node {@code nodes} makes. */
    public static DisplayTile on(NodeFactory nodes) {
        return new DisplayTile(nodes);
    }

    /** A display with no node, as a client's tile entity is (OC's builders make none there). */
    public static DisplayTile withoutNode() {
        return new DisplayTile((host, reachability, name) -> null);
    }

    /**
     * Maps {@link DisplayTile} to {@value #TILE_ID}, once per JVM, as {@code GameRegistry.registerTileEntity} does in
     * game: {@code TileEntity.writeToNBT} throws for a class with no mapping, and {@code addMapping} throws for an id
     * mapped twice (TileEntity.java:46-57, :96).
     */
    public static synchronized void mapTileEntity() {
        if (!mapped) {
            TileEntity.addMapping(DisplayTile.class, TILE_ID);
            mapped = true;
        }
    }
}
