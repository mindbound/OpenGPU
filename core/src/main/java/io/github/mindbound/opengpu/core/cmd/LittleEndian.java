package io.github.mindbound.opengpu.core.cmd;

/**
 * Unaligned little-endian reads from a segment (28 §3.2, §3.3). The caller has checked the bounds: these are the
 * validator's and the reader's, which read only fields their structure checks put inside the array.
 */
final class LittleEndian {

    private LittleEndian() {}

    static int u8(byte[] s, int i) {
        return s[i] & 0xFF;
    }

    static int u16(byte[] s, int i) {
        return (s[i] & 0xFF) | (s[i + 1] & 0xFF) << 8;
    }

    /** Sign-extended. */
    static int i16(byte[] s, int i) {
        return (short) ((s[i] & 0xFF) | (s[i + 1] & 0xFF) << 8);
    }

    /** All 32 bits kept in the int; {@link Integer#toUnsignedLong} gives the unsigned value. */
    static int u32(byte[] s, int i) {
        return (s[i] & 0xFF) | (s[i + 1] & 0xFF) << 8 | (s[i + 2] & 0xFF) << 16 | (s[i + 3] & 0xFF) << 24;
    }
}
