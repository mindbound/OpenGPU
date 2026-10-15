package io.github.mindbound.opengpu.core.cmd;

import java.util.Locale;

/**
 * {@code TEXT}'s string rules (28 §7.2): well-formed UTF-8 (shortest form, no surrogates, nothing above U+10FFFF; the
 * Unicode Standard's Table 3-7) with no C0 or C1 control (U+0000-001F, U+007F-009F), checked in one left-to-right scan
 * in which the first offending sequence decides between {@code BAD_UTF8} and {@code CONTROL_CHAR} (28 §3.6 row 21).
 * The detail is the string byte index of the offending sequence's first byte: the lead byte of an ill-formed or cut
 * sequence, or the stray byte itself. Noncharacters, private use, U+FEFF and unassigned codepoints are well-formed and
 * not controls, so they pass and draw the font's glyph, or its missing glyph where it has none.
 */
public final class Utf8 {

    /** Set in every error result. */
    private static final long ERROR = Long.MIN_VALUE;
    /** Set in a {@code CONTROL_CHAR} result; clear in a {@code BAD_UTF8} one. */
    private static final long CONTROL = 1L << 62;
    private static final int CODEPOINT_SHIFT = 32;
    private static final long CODEPOINT_MASK = 0x1FFFFF;

    private Utf8() {}

    /**
     * Scans {@code bytes[from, from + length)}. A result {@code >= 0} means well-formed with no control, and is the
     * number of codepoints; a negative result describes the first offending sequence, read with {@link #isBadUtf8},
     * {@link #isControlChar}, {@link #errorIndex} and {@link #errorCodepoint}. A sequence cut off by the end of the
     * range (not of the array) is ill-formed. Allocates nothing, visits each byte once, and never throws for a range
     * inside the array.
     */
    public static long scan(byte[] bytes, int from, int length) {
        long count = 0;
        int i = 0;
        while (i < length) {
            int b0 = bytes[from + i] & 0xFF;
            int codepoint;
            int size;
            if (b0 < 0x80) {
                codepoint = b0;
                size = 1;
            } else if (b0 < 0xC2) {
                // 80-BF never lead; C0 and C1 lead only overlong forms
                return bad(i);
            } else if (b0 < 0xE0) {
                if (length - i < 2) {
                    return bad(i);
                }
                int b1 = bytes[from + i + 1] & 0xFF;
                if (b1 < 0x80 || b1 > 0xBF) {
                    return bad(i);
                }
                codepoint = (b0 & 0x1F) << 6 | (b1 & 0x3F);
                size = 2;
            } else if (b0 < 0xF0) {
                // E0 A0-BF (no overlong), ED 80-9F (no surrogate), others 80-BF
                int low = b0 == 0xE0 ? 0xA0 : 0x80;
                int high = b0 == 0xED ? 0x9F : 0xBF;
                if (length - i < 3) {
                    return bad(i);
                }
                int b1 = bytes[from + i + 1] & 0xFF;
                int b2 = bytes[from + i + 2] & 0xFF;
                if (b1 < low || b1 > high || b2 < 0x80 || b2 > 0xBF) {
                    return bad(i);
                }
                codepoint = (b0 & 0x0F) << 12 | (b1 & 0x3F) << 6 | (b2 & 0x3F);
                size = 3;
            } else if (b0 < 0xF5) {
                // F0 90-BF (no overlong), F4 80-8F (nothing above U+10FFFF), F1-F3 80-BF
                int low = b0 == 0xF0 ? 0x90 : 0x80;
                int high = b0 == 0xF4 ? 0x8F : 0xBF;
                if (length - i < 4) {
                    return bad(i);
                }
                int b1 = bytes[from + i + 1] & 0xFF;
                int b2 = bytes[from + i + 2] & 0xFF;
                int b3 = bytes[from + i + 3] & 0xFF;
                if (b1 < low || b1 > high || b2 < 0x80 || b2 > 0xBF || b3 < 0x80 || b3 > 0xBF) {
                    return bad(i);
                }
                codepoint = (b0 & 0x07) << 18 | (b1 & 0x3F) << 12 | (b2 & 0x3F) << 6 | (b3 & 0x3F);
                size = 4;
            } else {
                // F5-FF would encode beyond U+10FFFF, or are no UTF-8 at all
                return bad(i);
            }
            if (isControl(codepoint)) {
                return ERROR | CONTROL | (long) codepoint << CODEPOINT_SHIFT | i;
            }
            count++;
            i += size;
        }
        return count;
    }

    /** Whether a {@link #scan} result is {@code BAD_UTF8}. */
    public static boolean isBadUtf8(long result) {
        return result < 0 && (result & CONTROL) == 0;
    }

    /** Whether a {@link #scan} result is {@code CONTROL_CHAR}. */
    public static boolean isControlChar(long result) {
        return result < 0 && (result & CONTROL) != 0;
    }

    /** The string byte index of the offending sequence's first byte in a negative {@link #scan} result; else -1. */
    public static int errorIndex(long result) {
        return result < 0 ? (int) result & Integer.MAX_VALUE : -1;
    }

    /** The control codepoint of a {@code CONTROL_CHAR} result, for its message; -1 for any other result. */
    public static int errorCodepoint(long result) {
        return isControlChar(result) ? (int) (result >>> CODEPOINT_SHIFT & CODEPOINT_MASK) : -1;
    }

    /** U+0000-001F and U+007F-009F: C0, DEL and C1 (28 §7.2). */
    public static boolean isControl(int codepoint) {
        return codepoint >= 0 && codepoint < 0x20 || codepoint >= 0x7F && codepoint <= 0x9F;
    }

    /**
     * The codepoint whose sequence starts at {@code bytes[index]}. For validated input only (an accepted {@code TEXT}
     * string): it neither checks the continuation bytes nor the end of the string.
     */
    public static int codepointAt(byte[] bytes, int index) {
        int b0 = bytes[index] & 0xFF;
        switch (sequenceLength(b0)) {
            case 1:
                return b0;
            case 2:
                return (b0 & 0x1F) << 6 | (bytes[index + 1] & 0x3F);
            case 3:
                return (b0 & 0x0F) << 12 | (bytes[index + 1] & 0x3F) << 6 | (bytes[index + 2] & 0x3F);
            default:
                return (b0 & 0x07) << 18 | (bytes[index + 1] & 0x3F) << 12
                    | (bytes[index + 2] & 0x3F) << 6
                    | (bytes[index + 3] & 0x3F);
        }
    }

    /**
     * The length, 1-4, of the sequence a lead byte starts (its low 8 bits are read, so a signed {@code byte} may be
     * passed). For validated input only.
     *
     * @throws IllegalArgumentException for a byte that leads no well-formed sequence: 80-C1 and F5-FF
     */
    public static int sequenceLength(int leadByte) {
        int b0 = leadByte & 0xFF;
        if (b0 < 0x80) {
            return 1;
        }
        if (b0 < 0xC2 || b0 > 0xF4) {
            throw new IllegalArgumentException(
                String.format(Locale.ROOT, "0x%02X leads no well-formed UTF-8 sequence", b0));
        }
        return b0 < 0xE0 ? 2 : b0 < 0xF0 ? 3 : 4;
    }

    private static long bad(int index) {
        return ERROR | index;
    }
}
