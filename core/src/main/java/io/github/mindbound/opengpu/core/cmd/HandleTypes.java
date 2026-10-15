package io.github.mindbound.opengpu.core.cmd;

/**
 * The resource types of {@link io.github.mindbound.opengpu.core.Handles}' type field (28 §6.1). Only the texture is
 * used in M0; 2-7 are reserved now so that M0's messages can name them and M2-M3 need not renumber, and 8-15 are
 * unassigned ({@code UNKNOWN_TYPE}, 28 §3.6 row 16).
 */
public final class HandleTypes {

    public static final int NONE = 0;
    public static final int TEXTURE = 1;
    public static final int BUFFER = 2;
    public static final int RENDER_TARGET = 3;
    public static final int PROGRAM = 4;
    public static final int PIPELINE = 5;
    public static final int KERNEL = 6;
    public static final int COMPUTE_LIST = 7;

    /** Types from here to 15 are unassigned. */
    public static final int FIRST_UNASSIGNED = 8;

    private static final String[] KINDS = { null, "texture", "buffer", "render target", "program", "pipeline", "kernel",
        "compute list" };

    private HandleTypes() {}

    /** Types 1-7. */
    public static boolean isAssigned(int type) {
        return type >= TEXTURE && type < FIRST_UNASSIGNED;
    }

    /**
     * The kind as {@code WRONG_TYPE}'s message names it (28 §3.6, "is a buffer"): "texture", "buffer", "render target",
     * "program", "pipeline", "kernel" or "compute list"; null for 0 and unassigned types.
     */
    public static String kindName(int type) {
        return isAssigned(type) ? KINDS[type] : null;
    }
}
