package io.github.mindbound.opengpu.core.font;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

import io.github.mindbound.opengpu.core.cmd.Fonts;

/**
 * Glyph tables shipped in {@code :core}'s jar (28:631; 29:179): read through the classpath, never through Minecraft's
 * resource manager, so a resource pack cannot change G. Font 0's table is written at build time by
 * {@code core.fontgen}'s converter from misc-fixed 6x13 (28:630), which documents the format; this class reads it
 * independently of that code:
 * <ol>
 * <li>an ASCII header, one LF-terminated line each: {@value #MAGIC}, then the key-value lines {@code font 0},
 * {@code cell 6x13}, {@code glyphs <n>}, {@code missing U+FFFD}, {@code source}, {@code source-sha256} (64 lower-case
 * hex), {@code source-id}, {@code copyright} and {@code licence}, in this order, each key and its non-empty value
 * separated by one space; then an empty line;</li>
 * <li>the body, little-endian: u32 n; n u32 codepoints, ascending; n × 13 row bytes, glyph i's at 13·i, row 0 first;
 * the missing glyph's 13 row bytes;</li>
 * <li>a u32 CRC32 of the body, little-endian, and nothing after it.</li>
 * </ol>
 * M0 reads font 0 only (28 §7.1); M1's further faces (29 §5.3) generalize the cell and the font id.
 */
public final class GlyphTables {

    /** Font 0's table, a resource of {@code :core}'s jar that {@code :core}'s {@code generateFont0} writes. */
    public static final String FONT0_RESOURCE = "/io/github/mindbound/opengpu/core/font/font0-6x13.glyphs";

    /** The header's first line. */
    public static final String MAGIC = "OpenGPU glyph table 1";

    /** The header's keys after {@link #MAGIC}, in their order. */
    private static final String[] KEYS = { "font", "cell", "glyphs", "missing", "source", "source-sha256", "source-id",
        "copyright", "licence" };

    /** Font 0's fixed header values (28 §7.1, §7.3; 28:632). */
    private static final String FONT0_ID = "0";
    private static final String FONT0_CELL = Fonts.FONT_0_CELL_WIDTH + "x" + Fonts.FONT_0_CELL_HEIGHT;
    private static final int FONT0_MISSING = 0xFFFD;
    private static final String FONT0_MISSING_VALUE = "U+FFFD";

    /** A header line's longest form, LF excluded; a longer one is malformed, and nothing reads without bound. */
    private static final int MAX_LINE = 1024;
    /** The Unicode scalar values: a table holds at most one glyph for each. */
    private static final int MAX_GLYPHS = 0x110000 - 0x800;

    private GlyphTables() {}

    /**
     * Font 0, misc-fixed 6x13 (28:630), read from {@link #FONT0_RESOURCE} once, on the first call (the holder idiom).
     *
     * @throws IllegalStateException naming the resource, on this and every later call, when it is missing or malformed
     */
    public static GlyphTable font0() {
        GlyphTable table = Font0.TABLE;
        if (table == null) {
            throw new IllegalStateException(Font0.FAILURE.getMessage(), Font0.FAILURE.getCause());
        }
        return table;
    }

    /** Loads font 0 when font0() first needs it; a failure is kept, so that every call reports it the same way. */
    private static final class Font0 {

        static final GlyphTable TABLE;
        static final IllegalStateException FAILURE;

        static {
            GlyphTable table = null;
            IllegalStateException failure = null;
            try {
                table = load();
            } catch (IllegalStateException e) {
                failure = e;
            }
            TABLE = table;
            FAILURE = failure;
        }

        private static GlyphTable load() {
            String name = "font 0's glyph table " + FONT0_RESOURCE;
            InputStream in = GlyphTables.class.getResourceAsStream(FONT0_RESOURCE);
            if (in == null) {
                throw new IllegalStateException(name + " is not on the classpath (28:631)");
            }
            try (InputStream stream = new BufferedInputStream(in)) {
                return read(stream, name);
            } catch (IOException | IllegalArgumentException e) {
                // read's messages start with the name; an I/O error's may not
                String reason = String.valueOf(e.getMessage());
                throw new IllegalStateException(reason.startsWith(name) ? reason : name + ": " + reason, e);
            }
        }
    }

    /**
     * Reads a font-0 table from the stream, which it leaves open: the header with font 0's values, the body, the CRC,
     * and the end of the stream. The missing glyph must equal the table's U+FFFD, as the header's {@code missing} says.
     *
     * @param name the table's name for messages
     * @throws IOException              for a stream that ends early, an {@link EOFException} naming the table
     * @throws IllegalArgumentException with the name and the reason, for a wrong header line, a missing empty line, a
     *                                  count or CRC mismatch, bytes after the CRC, or rows {@link GlyphTable} refuses
     */
    public static GlyphTable read(InputStream in, String name) throws IOException {
        Map<String, String> header = header(in, name);
        expectValue(name, header, "font", FONT0_ID);
        expectValue(name, header, "cell", FONT0_CELL);
        expectValue(name, header, "missing", FONT0_MISSING_VALUE);
        int glyphs = glyphCount(name, header.get("glyphs"));
        String sha = header.get("source-sha256");
        if (sha.length() != 64 || !isLowerHex(sha)) {
            throw malformed(name, "source-sha256 " + sha + " is not 64 lower-case hex digits");
        }
        int height = Fonts.FONT_0_CELL_HEIGHT;
        DataInputStream data = new DataInputStream(in);
        CRC32 crc = new CRC32();
        byte[] count = readBody(data, crc, 4, name, "the glyph count");
        int n = u32(count, 0);
        if (n != glyphs) {
            throw malformed(name, "the body holds " + Integer.toUnsignedString(n) + " glyphs, the header " + glyphs);
        }
        byte[] codepointBytes = readBody(data, crc, 4 * n, name, "the codepoints");
        byte[] rows = readBody(data, crc, height * n, name, "the glyph rows");
        byte[] missing = readBody(data, crc, height, name, "the missing glyph");
        byte[] stored = new byte[4];
        readFully(data, stored, name, "the CRC");
        if (u32(stored, 0) != (int) crc.getValue()) {
            throw malformed(
                name,
                String.format(
                    Locale.ROOT,
                    "the body's CRC32 is 0x%08X, the table says 0x%08X",
                    crc.getValue(),
                    u32(stored, 0)));
        }
        if (in.read() != -1) {
            throw malformed(name, "bytes follow the CRC");
        }
        int[] codepoints = new int[n];
        for (int i = 0; i < n; i++) {
            codepoints[i] = u32(codepointBytes, 4 * i);
        }
        GlyphTable table;
        try {
            table = new GlyphTable(Fonts.FONT_0_CELL_WIDTH, height, codepoints, rows, missing);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + ": " + e.getMessage(), e);
        }
        int fffd = table.indexOf(FONT0_MISSING);
        if (fffd == GlyphTable.MISSING) {
            throw malformed(name, "the header names U+FFFD as the missing glyph, but the table has no U+FFFD");
        }
        int at = fffd * height;
        if (!Arrays.equals(Arrays.copyOfRange(rows, at, at + height), missing)) {
            throw malformed(name, "the missing glyph differs from the table's U+FFFD");
        }
        return table;
    }

    /**
     * Reads a table's header, up to and including its empty line, and leaves the stream at the body: the key-value
     * lines after {@link #MAGIC}, in order. It checks the form (the first line, the keys and their order, non-empty
     * printable-ASCII values, the empty line), not the values, which {@link #read} checks for font 0.
     *
     * @throws IOException              for a stream that ends in the header, an {@link EOFException} naming the table
     * @throws IllegalArgumentException with the name and the reason, for a wrong line or a missing empty line
     */
    public static Map<String, String> header(InputStream in, String name) throws IOException {
        String first = line(in, name, 1);
        if (!MAGIC.equals(first)) {
            throw malformed(name, "line 1 is " + quote(first) + ", not " + quote(MAGIC));
        }
        Map<String, String> header = new LinkedHashMap<String, String>();
        for (int i = 0; i < KEYS.length; i++) {
            int lineNo = i + 2;
            String line = line(in, name, lineNo);
            int space = line.indexOf(' ');
            String key = space < 0 ? line : line.substring(0, space);
            if (!KEYS[i].equals(key)) {
                throw malformed(
                    name,
                    "line " + lineNo + " is " + quote(line) + ", where the " + KEYS[i] + " line belongs");
            }
            if (space < 0 || space == line.length() - 1) {
                throw malformed(name, "line " + lineNo + " " + quote(line) + " has no value");
            }
            header.put(key, line.substring(space + 1));
        }
        String end = line(in, name, KEYS.length + 2);
        if (!end.isEmpty()) {
            throw malformed(
                name,
                "line " + (KEYS.length + 2) + " is " + quote(end) + ", not the empty line that ends" + " the header");
        }
        return Collections.unmodifiableMap(header);
    }

    /** One header line without its LF: printable ASCII, at most MAX_LINE bytes. */
    private static String line(InputStream in, String name, int lineNo) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(80);
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException(name + ": the stream ends in header line " + lineNo);
            }
            if (b == '\n') {
                break;
            }
            if (b < 0x20 || b > 0x7E) {
                throw malformed(
                    name,
                    String.format(
                        Locale.ROOT,
                        "header line %d has the byte 0x%02X, which is not printable ASCII",
                        lineNo,
                        b));
            }
            if (bytes.size() == MAX_LINE) {
                throw malformed(name, "header line " + lineNo + " is longer than " + MAX_LINE + " bytes");
            }
            bytes.write(b);
        }
        byte[] ascii = bytes.toByteArray();
        char[] chars = new char[ascii.length];
        for (int i = 0; i < ascii.length; i++) {
            chars[i] = (char) ascii[i];
        }
        return new String(chars);
    }

    private static void expectValue(String name, Map<String, String> header, String key, String expected) {
        String value = header.get(key);
        if (!expected.equals(value)) {
            throw malformed(
                name,
                "the header says " + key + " " + value + ", font 0's table has " + key + " " + expected);
        }
    }

    /** The glyphs value: 1-7 decimal digits without a leading zero, at most the number of scalar values. */
    private static int glyphCount(String name, String value) {
        boolean digits = value.length() <= 7 && (value.length() == 1 || value.charAt(0) != '0');
        for (int i = 0; i < value.length() && digits; i++) {
            digits = value.charAt(i) >= '0' && value.charAt(i) <= '9';
        }
        if (!digits || Integer.parseInt(value) > MAX_GLYPHS) {
            throw malformed(name, "glyphs " + value + " is not a count of 0 to " + MAX_GLYPHS);
        }
        return Integer.parseInt(value);
    }

    private static byte[] readBody(DataInputStream in, CRC32 crc, int length, String name, String what)
        throws IOException {
        byte[] out = new byte[length];
        readFully(in, out, name, what);
        crc.update(out, 0, length);
        return out;
    }

    private static void readFully(DataInputStream in, byte[] out, String name, String what) throws IOException {
        try {
            in.readFully(out);
        } catch (EOFException e) {
            EOFException named = new EOFException(name + ": the stream ends in " + what);
            named.initCause(e);
            throw named;
        }
    }

    private static boolean isLowerHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }

    private static int u32(byte[] in, int at) {
        return (in[at] & 0xFF) | (in[at + 1] & 0xFF) << 8 | (in[at + 2] & 0xFF) << 16 | (in[at + 3] & 0xFF) << 24;
    }

    private static IllegalArgumentException malformed(String name, String reason) {
        return new IllegalArgumentException(name + ": " + reason);
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }
}
