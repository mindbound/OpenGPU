package io.github.mindbound.opengpu.core.cmd;

/**
 * The segment header (28 §3.1): magic {@code "OGPU"}, the command-format version, flags and a reserved u16, 8 bytes in
 * all. The shortest legal segment is the header plus {@code END}. The root {@code api} package's
 * {@code CommandFormat} copies these constants (28 §4.2), hence the different name.
 */
public final class Header {

    /** magic(4) version(1) flags(1) reserved u16(2) */
    public static final int SIZE = 8;

    /** {@code "OGPU"} (28 §3.1; 09:67). */
    public static final int MAGIC_0 = 0x4F, MAGIC_1 = 0x47, MAGIC_2 = 0x50, MAGIC_3 = 0x55;

    public static final int OFFSET_VERSION = 4, OFFSET_FLAGS = 5, OFFSET_RESERVED = 6;

    /**
     * The version M0 emits (28 §4.1): {@code getCaps().commandFormat}, which the {@code api} jar's
     * {@code CommandFormat.VERSION} copies.
     */
    public static final int VERSION = 1;

    /** Header plus {@code END}: a no-op (28 §3.1). */
    public static final int MIN_SEGMENT = SIZE + Opcodes.END_SIZE;

    private Header() {}

    /** Whether this mod decodes segments of the version (28 §4.2: {@code submit} accepts all of them; M0 has {1}). */
    public static boolean isDecoded(int version) {
        return version == VERSION;
    }

    /** The versions this mod decodes, as {@code BAD_VERSION}'s reason lists them (28 §3.6): "1". */
    public static String decodedVersions() {
        return "1";
    }
}
