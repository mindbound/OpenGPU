package io.github.mindbound.opengpu.core;

/**
 * Resource handles as Lua sees them (docs/research/26-lessons-from-general-engines.md §1.2, G1; 24 §2.1):
 * {@code type(4) | slot(11) | generation(16)} in a positive int, below 2^31 because OpenComputers marshals it as an
 * Integer. Type 0 is reserved, so 0 is never a handle, and handles of different types never alias. The resource types
 * are {@link io.github.mindbound.opengpu.core.cmd.HandleTypes}'s (28 §6.1).
 */
public final class Handles {

    public static final int TYPE_BITS = 4;
    public static final int SLOT_BITS = 11;
    public static final int GENERATION_BITS = 16;

    public static final int MAX_TYPE = (1 << TYPE_BITS) - 1;
    public static final int MAX_SLOT = (1 << SLOT_BITS) - 1;
    public static final int MAX_GENERATION = (1 << GENERATION_BITS) - 1;

    /** Not a handle: its type is 0. */
    public static final int NONE = 0;

    private static final int SLOT_SHIFT = GENERATION_BITS;
    private static final int TYPE_SHIFT = SLOT_BITS + GENERATION_BITS;

    private Handles() {}

    public static int pack(int type, int slot, int generation) {
        check("type", type, 1, MAX_TYPE);
        check("slot", slot, 0, MAX_SLOT);
        check("generation", generation, 0, MAX_GENERATION);
        return (type << TYPE_SHIFT) | (slot << SLOT_SHIFT) | generation;
    }

    public static int type(int handle) {
        return (handle >>> TYPE_SHIFT) & MAX_TYPE;
    }

    public static int slot(int handle) {
        return (handle >>> SLOT_SHIFT) & MAX_SLOT;
    }

    public static int generation(int handle) {
        return handle & MAX_GENERATION;
    }

    /**
     * Whether the value has a handle's shape: positive, with a non-zero type. It says nothing of whether it is live.
     */
    public static boolean isWellFormed(int handle) {
        return handle > 0 && type(handle) != 0;
    }

    private static void check(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("handle " + field + " " + value + " is outside " + min + ".." + max);
        }
    }
}
