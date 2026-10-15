package io.github.mindbound.opengpu.core.fixtures;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * The Java test encoder of 28 §11.1: one builder per opcode, mirroring the Lua reference builders of 28 §9.2, which
 * S10 compares byte for byte with the Lua encoder's output. Each builder returns one record; {@link #segment} adds the
 * header and {@code END}. It writes 28 §5.2's layouts with its own literal opcodes and offsets, not
 * {@code core.cmd.Opcodes}, so the round-trip tests compare the main reader with an independent encoding.
 * <p>
 * The mirror rules, for in-range integers (28 §9.1-§9.2): an i16 operand is written as {@code v & 0xFFFF}, Lua's
 * {@code floor(v) % 65536}, so it wraps modulo 2^16; a u32 operand (a colour or a handle) is the int's 32 bits; flags
 * and font are reduced to {@code & 0xFF}, as the Lua builders floor and reduce them; {@code n} is the string's byte
 * length. A string over 65 535 bytes is an {@code IllegalArgumentException}, where Lua's {@code string.char} raises.
 * <p>
 * The raw helpers below the builders make the malformed segments of the negative tests (28 §11.2). Every method
 * returns a fresh array.
 */
public final class TestEncoder {

    /** The largest count a u16 holds. */
    private static final int MAX_COUNT = 0xFFFF;

    private TestEncoder() {}

    /** The v1 header (28 §3.1): {@code 4F 47 50 55 01 00 00 00}, 28 §9.2's {@code HEADER}. */
    public static byte[] header() {
        return header(1, 0, 0);
    }

    /** {@code END}: {@code FF} (28 §3.2). */
    public static byte[] end() {
        return bytes(0xFF);
    }

    /** {@code CLEAR} (28 §5.2): 5 B, {@code 01} then the colour. 28 §9.2's {@code clear(c)}. */
    public static byte[] clear(int c) {
        return concat(bytes(0x01), le32(c));
    }

    /** {@code FILL_RECT} (28 §5.2): 13 B, {@code 30}, x, y, w, h as i16, then the colour. 28 §9.2's {@code fill}. */
    public static byte[] fill(int x, int y, int w, int h, int c) {
        return concat(bytes(0x30), le16(x), le16(y), le16(w), le16(h), le32(c));
    }

    /**
     * {@code BLIT} (28 §5.2): 18 B, {@code 31}, the handle, sx, sy, w, h, dx, dy as i16, then the flags byte. 28 §9.2's
     * {@code blit}.
     */
    public static byte[] blit(int tex, int sx, int sy, int w, int h, int dx, int dy, int flags) {
        return concat(bytes(0x31), le32(tex), le16(sx), le16(sy), le16(w), le16(h), le16(dx), le16(dy), bytes(flags));
    }

    /**
     * {@code TEXT} (28 §5.2): 17 + n B, {@code 33}, x, y as i16, fg, bg, the flags and font bytes, the u16 count n,
     * then the n string bytes as given, so a test may pass malformed UTF-8. 28 §9.2's {@code text}.
     *
     * @throws IllegalArgumentException if the string is longer than 65 535 bytes
     */
    public static byte[] text(int x, int y, int fg, int bg, int flags, int font, byte[] utf8) {
        if (utf8.length > MAX_COUNT) {
            throw new IllegalArgumentException(
                "a string of " + utf8.length + " bytes does not fit TEXT's u16 count (28 §5.2)");
        }
        return concat(bytes(0x33), le16(x), le16(y), le32(fg), le32(bg), bytes(flags, font), le16(utf8.length), utf8);
    }

    /**
     * {@link #text(int, int, int, int, int, int, byte[])} with the string's UTF-8 bytes. Java replaces an unpaired
     * surrogate with '?', so malformed strings go through the byte form.
     */
    public static byte[] text(int x, int y, int fg, int bg, int flags, int font, String s) {
        return text(x, y, fg, bg, flags, font, s.getBytes(StandardCharsets.UTF_8));
    }

    /** A whole segment: {@link #header()}, the records in order, {@link #end()} (28 §3.1). */
    public static byte[] segment(byte[]... records) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, header());
        for (byte[] record : records) {
            write(out, record);
        }
        write(out, end());
        return out.toByteArray();
    }

    /** The parts joined in order. */
    public static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            write(out, part);
        }
        return out.toByteArray();
    }

    // Raw helpers for the negative tests (28 §11.2)

    /**
     * A header with the given version and flags bytes and the reserved field as a LE u16 (each reduced to its size).
     */
    public static byte[] header(int version, int flags, int reserved) {
        return concat(bytes(0x4F, 0x47, 0x50, 0x55, version, flags), le16(reserved));
    }

    /** One byte per value, each {@code & 0xFF}. */
    public static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    /** The low 16 bits, little-endian (28 §3.3): an i16 or a u16 operand. */
    public static byte[] le16(int v) {
        return bytes(v, v >>> 8);
    }

    /** All 32 bits, little-endian (28 §3.3): a u32, handle or colour operand. */
    public static byte[] le32(int v) {
        return bytes(v, v >>> 8, v >>> 16, v >>> 24);
    }

    /**
     * A copy of s with bytes patched from offset on, each value {@code & 0xFF}.
     *
     * @throws IndexOutOfBoundsException if the patch does not fit inside s
     */
    public static byte[] with(byte[] s, int offset, int... values) {
        return patch(s, offset, bytes(values));
    }

    /** A copy of s with a LE u16 written at offset. */
    public static byte[] withLe16(byte[] s, int offset, int v) {
        return patch(s, offset, le16(v));
    }

    /** A copy of s with a LE u32 written at offset. */
    public static byte[] withLe32(byte[] s, int offset, int v) {
        return patch(s, offset, le32(v));
    }

    /**
     * The first length bytes of s: a truncation.
     *
     * @throws IllegalArgumentException for a length outside 0 to s.length
     */
    public static byte[] head(byte[] s, int length) {
        if (length < 0 || length > s.length) {
            throw new IllegalArgumentException("prefix of " + length + " bytes of a " + s.length + "-byte array");
        }
        return Arrays.copyOf(s, length);
    }

    /**
     * Bytes from hex: pairs of hex digits, optionally separated by whitespace, '|' or ','; for writing 28's tables
     * (28:833-838) as they read.
     *
     * @throws IllegalArgumentException for any other character or an odd digit count
     */
    public static byte[] hex(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int high = -1;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch) || ch == '|' || ch == ',') {
                if (high >= 0) {
                    throw new IllegalArgumentException("odd hex digit at " + (i - 1) + " in \"" + text + "\"");
                }
                continue;
            }
            int digit = Character.digit(ch, 16);
            if (digit < 0) {
                throw new IllegalArgumentException("'" + ch + "' at " + i + " is no hex digit in \"" + text + "\"");
            }
            if (high < 0) {
                high = digit;
            } else {
                out.write(high << 4 | digit);
                high = -1;
            }
        }
        if (high >= 0) {
            throw new IllegalArgumentException("odd number of hex digits in \"" + text + "\"");
        }
        return out.toByteArray();
    }

    /** The bytes as upper-case hex pairs separated by spaces, e.g. {@code "4F 47 50 55"}: for failure messages. */
    public static String toHex(byte[] s) {
        StringBuilder out = new StringBuilder(s.length * 3);
        for (int i = 0; i < s.length; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(String.format(Locale.ROOT, "%02X", s[i] & 0xFF));
        }
        return out.toString();
    }

    private static byte[] patch(byte[] s, int offset, byte[] values) {
        if (offset < 0 || offset > s.length - values.length) {
            throw new IndexOutOfBoundsException(
                values.length + " bytes at " + offset + " do not fit a " + s.length + "-byte array");
        }
        byte[] out = s.clone();
        System.arraycopy(values, 0, out, offset, values.length);
        return out;
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }
}
