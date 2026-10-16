package io.github.mindbound.opengpu.gl;

import org.lwjgl.opengl.GL11;

/**
 * G8 (24:203; 28:593-597; 26:73-82): saves, forces and restores the six pixel-store values of one direction, so a
 * foreign alignment, row length, skip or byte swap cannot corrupt an upload or a readback. Unpack: alignment 1 (odd R8
 * rows are not 4-aligned), row length 0 (payloads are tightly packed, 28 C9), skips 0, swap bytes and LSB-first false
 * on both contexts (28 C10). Pack: the same, only in a pass that issues a readback (24:203; GLSM caches pack state only
 * from 2.2.32, 28 C11). The pixel-buffer bindings are {@link PassState}'s (S8 amendments B). Both contexts take the
 * same calls (24:187): {@code glGetInteger} (C01) and {@code glPixelStorei} (C34). GL thread; nothing is allocated
 * after construction.
 */
public final class PixelStore {

    private static final int[] UNPACK = { GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH,
        GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_UNPACK_LSB_FIRST };
    private static final int[] PACK = { GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_PIXELS,
        GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST };
    /** The forced values, in the order of UNPACK and PACK. */
    private static final int[] FORCED = { 1, 0, 0, 0, GL11.GL_FALSE, GL11.GL_FALSE };

    private final int[] savedUnpack = new int[UNPACK.length];
    private final int[] savedPack = new int[PACK.length];
    private boolean unpacking;
    private boolean packing;

    /** No GL work. */
    public PixelStore() {}

    /**
     * Saves the six unpack values and forces 1, 0, 0, 0, false, false.
     *
     * @throws IllegalStateException if the unpack bracket is open
     */
    public void beginUnpack() {
        if (unpacking) {
            throw new IllegalStateException("the unpack bracket is already open");
        }
        save(UNPACK, savedUnpack);
        force(UNPACK);
        unpacking = true;
    }

    /**
     * Restores the six unpack values, in reverse order.
     *
     * @throws IllegalStateException unless the unpack bracket is open
     */
    public void endUnpack() {
        if (!unpacking) {
            throw new IllegalStateException("the unpack bracket is not open");
        }
        unpacking = false;
        restore(UNPACK, savedUnpack);
    }

    /**
     * Saves the six pack values and forces 1, 0, 0, 0, false, false.
     *
     * @throws IllegalStateException if the pack bracket is open
     */
    public void beginPack() {
        if (packing) {
            throw new IllegalStateException("the pack bracket is already open");
        }
        save(PACK, savedPack);
        force(PACK);
        packing = true;
    }

    /**
     * Restores the six pack values, in reverse order.
     *
     * @throws IllegalStateException unless the pack bracket is open
     */
    public void endPack() {
        if (!packing) {
            throw new IllegalStateException("the pack bracket is not open");
        }
        packing = false;
        restore(PACK, savedPack);
    }

    public boolean unpacking() {
        return unpacking;
    }

    public boolean packing() {
        return packing;
    }

    private static void save(int[] names, int[] saved) {
        for (int i = 0; i < names.length; i++) {
            saved[i] = GL11.glGetInteger(names[i]);
        }
    }

    private static void force(int[] names) {
        for (int i = 0; i < names.length; i++) {
            GL11.glPixelStorei(names[i], FORCED[i]);
        }
    }

    private static void restore(int[] names, int[] saved) {
        for (int i = names.length - 1; i >= 0; i--) {
            GL11.glPixelStorei(names[i], saved[i]);
        }
    }
}
