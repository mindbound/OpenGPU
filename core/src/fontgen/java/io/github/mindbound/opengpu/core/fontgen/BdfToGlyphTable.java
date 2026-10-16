package io.github.mindbound.opengpu.core.fontgen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Font 0's build-time converter (28:631; 29:302): misc-fixed 6x13's BDF (28:630) into the glyph table that
 * {@code :core}'s jar ships and {@code GlyphTables} reads through the classpath. {@code :core}'s {@code generateFont0}
 * runs it; it lives in the {@code fontgen} source set, which no jar ships, and uses the JDK alone (plan amendments F).
 * Deterministic: the output is a function of the input bytes and the source name alone, and reads no clock, locale or
 * environment, so two conversions are byte-identical.
 * <p>
 * Checks, each failing the conversion with a message: the file's SHA-256 (29:381); {@code FONTBOUNDINGBOX 6 13 0 -2},
 * {@code FONT_ASCENT 11} and {@code FONT_DESCENT 2} (29 §7); no {@code ENCODING -1} glyph, since the pinned file has
 * none (plan amendments F); for every glyph {@code DWIDTH 6 0}, a box inside the cell (0 ≤ x, x + w ≤ 6, −2 ≤ y,
 * y + h ≤ 11), no bit beyond the box's width, and a unique encoding that is a Unicode scalar value; U+FFFD present
 * (28:632); a {@code COMMENT} holding {@code $Id} and a {@code COPYRIGHT} property, both printable ASCII.
 * <p>
 * Placement: bitmap row i (top first, MSB leftmost) of a box (w, h, x, y) is cell row 11 − (y + h) + i, shifted right
 * by x, so column b of the cell is bit 7 − b (28:469, :628).
 * <p>
 * The table, every glyph by ascending codepoint, U+0000 and U+FFFD included (plan amendments F), with U+FFFD's rows
 * also as the missing glyph (28:632):
 * <ol>
 * <li>a readable ASCII header (29:179), one LF-terminated line each, in this order: {@value #MAGIC}, {@code font 0},
 * {@code cell 6x13}, {@code glyphs <n>}, {@code missing U+FFFD}, {@code source <name>}, {@code source-sha256 <64 hex>},
 * {@code source-id <the first COMMENT holding $Id, verbatim>}, {@code copyright <COPYRIGHT, unquoted, byte for byte>},
 * {@code licence public domain; credited in OpenGPU's NOTICE}; then an empty line;</li>
 * <li>the body, little-endian: u32 n; n u32 codepoints, ascending; n × 13 row bytes, glyph i's at 13·i, row 0 first;
 * the missing glyph's 13 row bytes;</li>
 * <li>a u32 {@link CRC32} of the body, little-endian.</li>
 * </ol>
 */
public final class BdfToGlyphTable {

    /** The header's first line. */
    public static final String MAGIC = "OpenGPU glyph table 1";
    /** The header's last line's value (29 §5.4). */
    public static final String LICENCE = "public domain; credited in OpenGPU's NOTICE";

    /** Font 0's cell (28 §7.3) and the 6x13 BDF's metrics (29 §7). */
    static final int CELL_WIDTH = 6;
    static final int CELL_HEIGHT = 13;
    static final int ASCENT = 11;
    static final int DESCENT = 2;
    /** Font 0's missing glyph is its own U+FFFD (28:632). */
    static final int MISSING_CODEPOINT = 0xFFFD;

    private static final String USAGE = "usage: BdfToGlyphTable <bdf> <out> <expected sha256 hex> <source name>";

    private BdfToGlyphTable() {}

    /**
     * Converts {@code args[0]} into {@code args[1]}, creating its directory; args[2] is the expected SHA-256 and
     * args[3] the source name for the header. Exits with 1 and a message on any check, with 2 on a wrong argument
     * count.
     */
    public static void main(String[] args) throws IOException {
        int status = run(args, System.out, System.err);
        if (status != 0) {
            System.exit(status);
        }
    }

    /** {@link #main}'s work, returning its exit status rather than exiting; it writes only when every check passes. */
    static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
        if (args.length != 4) {
            err.println(USAGE);
            return 2;
        }
        byte[] bdf = Files.readAllBytes(Paths.get(args[0]));
        byte[] table;
        try {
            table = convert(bdf, args[2], args[3]);
        } catch (IllegalArgumentException e) {
            err.println("BdfToGlyphTable: " + e.getMessage());
            return 1;
        }
        Path target = Paths.get(args[1])
            .toAbsolutePath();
        Files.createDirectories(target.getParent());
        Files.write(target, table);
        int bodyStart = headerLength(table);
        int n = u32(table, bodyStart);
        out.println(
            String.format(
                Locale.ROOT,
                "BdfToGlyphTable: %s -> %s: %d glyphs, %d bytes, body sha256 %s",
                args[3],
                target.getFileName(),
                n,
                table.length,
                sha256Hex(table, bodyStart, table.length - 4 - bodyStart)));
        return 0;
    }

    /**
     * The whole table file for a BDF.
     *
     * @param expectedSha256 the BDF's SHA-256 as 64 hex digits, either case
     * @param sourceName     the header's {@code source} value, printable ASCII
     * @throws IllegalArgumentException naming the source, and the line where there is one, for any failed check
     */
    public static byte[] convert(byte[] bdf, String expectedSha256, String sourceName) {
        if (sourceName == null || sourceName.isEmpty() || !isPrintableAscii(sourceName)) {
            throw new IllegalArgumentException("the source name " + quote(sourceName) + " is not printable ASCII");
        }
        if (expectedSha256 == null || !isHex(expectedSha256, 64)) {
            throw new IllegalArgumentException(
                "the expected SHA-256 " + quote(expectedSha256) + " is not 64 hex digits");
        }
        String actual = sha256Hex(bdf, 0, bdf.length);
        String expected = expectedSha256.toLowerCase(Locale.ROOT);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(sourceName + " has SHA-256 " + actual + ", expected " + expected);
        }
        Bdf font;
        byte[] body;
        try {
            font = Bdf.parse(bdf);
            body = body(font);
        } catch (IllegalArgumentException e) {
            String reason = e.getMessage();
            throw new IllegalArgumentException(sourceName + (reason.startsWith("line ") ? " " : ": ") + reason, e);
        }
        String sourceId = null;
        for (String comment : font.comments()) {
            if (comment.contains("$Id")) {
                sourceId = comment;
                break;
            }
        }
        String copyright = font.copyright();
        List<String> header = new ArrayList<String>();
        header.add(MAGIC);
        header.add("font 0");
        header.add("cell " + CELL_WIDTH + "x" + CELL_HEIGHT);
        header.add("glyphs " + u32(body, 0));
        header.add(String.format(Locale.ROOT, "missing U+%04X", MISSING_CODEPOINT));
        header.add("source " + sourceName);
        header.add("source-sha256 " + actual);
        header.add("source-id " + headerValue(sourceName, "COMMENT holding $Id", sourceId));
        header.add("copyright " + headerValue(sourceName, "COPYRIGHT property", copyright));
        header.add("licence " + LICENCE);
        ByteArrayOutputStream out = new ByteArrayOutputStream(512 + body.length);
        for (String line : header) {
            byte[] ascii = line.getBytes(StandardCharsets.US_ASCII);
            out.write(ascii, 0, ascii.length);
            out.write('\n');
        }
        out.write('\n');
        out.write(body, 0, body.length);
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        byte[] crcBytes = new byte[4];
        putU32(crcBytes, 0, (int) crc.getValue());
        out.write(crcBytes, 0, 4);
        return out.toByteArray();
    }

    /**
     * The table's binary body alone (n, the codepoints, the rows, the missing glyph), after the font's checks other
     * than the SHA-256 and the header's: what {@code Font0Test} and {@code font0_table.py} hash.
     *
     * @throws IllegalArgumentException for any failed check, starting with {@code "line <n>: "} for a glyph's
     */
    public static byte[] body(Bdf font) {
        int[] box = font.fontBoundingBox();
        if (box[0] != CELL_WIDTH || box[1] != CELL_HEIGHT || box[2] != 0 || box[3] != -DESCENT) {
            throw new IllegalArgumentException(
                String.format(
                    Locale.ROOT,
                    "FONTBOUNDINGBOX is %d %d %d %d, not %d %d 0 %d",
                    box[0],
                    box[1],
                    box[2],
                    box[3],
                    CELL_WIDTH,
                    CELL_HEIGHT,
                    -DESCENT));
        }
        if (font.ascent() != ASCENT || font.descent() != DESCENT) {
            throw new IllegalArgumentException(
                "FONT_ASCENT " + font
                    .ascent() + " and FONT_DESCENT " + font.descent() + ", not " + ASCENT + " and " + DESCENT);
        }
        List<Bdf.Glyph> glyphs = new ArrayList<Bdf.Glyph>(font.glyphs());
        for (Bdf.Glyph g : glyphs) {
            checkGlyph(g);
        }
        // Ascending codepoints; the sort is stable, so of two glyphs with one encoding the earlier line comes first
        Collections.sort(glyphs, new Comparator<Bdf.Glyph>() {

            @Override
            public int compare(Bdf.Glyph a, Bdf.Glyph b) {
                return Integer.compare(a.encoding, b.encoding);
            }
        });
        int n = glyphs.size();
        int missingIndex = -1;
        for (int i = 0; i < n; i++) {
            Bdf.Glyph g = glyphs.get(i);
            if (i > 0 && glyphs.get(i - 1).encoding == g.encoding) {
                throw new IllegalArgumentException(
                    "line " + g.line
                        + ": glyph "
                        + describe(g)
                        + " repeats the encoding of line "
                        + glyphs.get(i - 1).line);
            }
            if (g.encoding == MISSING_CODEPOINT) {
                missingIndex = i;
            }
        }
        if (missingIndex < 0) {
            throw new IllegalArgumentException(
                String.format(Locale.ROOT, "no glyph for U+%04X, the missing glyph (28:632)", MISSING_CODEPOINT));
        }
        byte[] body = new byte[4 + 4 * n + CELL_HEIGHT * n + CELL_HEIGHT];
        putU32(body, 0, n);
        int rowsAt = 4 + 4 * n;
        for (int i = 0; i < n; i++) {
            Bdf.Glyph g = glyphs.get(i);
            putU32(body, 4 + 4 * i, g.encoding);
            place(g, body, rowsAt + CELL_HEIGHT * i);
        }
        System.arraycopy(body, rowsAt + CELL_HEIGHT * missingIndex, body, rowsAt + CELL_HEIGHT * n, CELL_HEIGHT);
        return body;
    }

    /** Every check of one glyph, so that its 13 cell rows can be placed without a bound check. */
    private static void checkGlyph(Bdf.Glyph g) {
        String at = "line " + g.line + ": glyph " + describe(g);
        if (g.encoding < 0) {
            throw new IllegalArgumentException(
                at + " has ENCODING -1; font 0's pinned file has no unencoded glyph (plan amendments F)");
        }
        if (g.encoding > 0x10FFFF || g.encoding >= 0xD800 && g.encoding <= 0xDFFF) {
            throw new IllegalArgumentException(at + " is no Unicode scalar value");
        }
        if (g.dwidthX != CELL_WIDTH || g.dwidthY != 0) {
            throw new IllegalArgumentException(
                at + " advances " + g.dwidthX + " " + g.dwidthY + ", not " + CELL_WIDTH + " 0 (one cell, 28:650)");
        }
        if (g.bbxX < 0 || g.bbxX + g.bbxW > CELL_WIDTH || g.bbxY < -DESCENT || g.bbxY + g.bbxH > ASCENT) {
            throw new IllegalArgumentException(
                String.format(
                    Locale.ROOT,
                    "%s has BBX %d %d %d %d, outside the %dx%d cell (x 0..%d, y %d..%d)",
                    at,
                    g.bbxW,
                    g.bbxH,
                    g.bbxX,
                    g.bbxY,
                    CELL_WIDTH,
                    CELL_HEIGHT,
                    CELL_WIDTH,
                    -DESCENT,
                    ASCENT));
        }
        // bbxW <= 6 here, so a row is one byte, MSB leftmost; the box's columns are its top bbxW bits
        int inBox = (0xFF << (8 - g.bbxW)) & 0xFF;
        for (int i = 0; i < g.rows.length; i++) {
            if ((g.rows[i] & ~inBox) != 0) {
                throw new IllegalArgumentException(
                    String.format(
                        Locale.ROOT,
                        "%s row %d is %02X, with a bit beyond its width %d",
                        at,
                        i,
                        g.rows[i],
                        g.bbxW));
            }
        }
    }

    /** Writes the glyph's 13 cell rows at offset; the box's rows land at 11 − (y + h) + i, shifted right by x. */
    private static void place(Bdf.Glyph g, byte[] out, int offset) {
        int top = ASCENT - (g.bbxY + g.bbxH);
        for (int i = 0; i < g.bbxH; i++) {
            out[offset + top + i] = (byte) (g.rows[i] >>> g.bbxX);
        }
    }

    private static String headerValue(String sourceName, String what, String value) {
        if (value == null) {
            throw new IllegalArgumentException(sourceName + ": no " + what + " for the header (29:179)");
        }
        if (value.isEmpty() || !isPrintableAscii(value)) {
            throw new IllegalArgumentException(
                sourceName + ": the "
                    + what
                    + " "
                    + quote(value)
                    + " is empty or not printable ASCII, so it cannot stand in the header");
        }
        return value;
    }

    private static String describe(Bdf.Glyph g) {
        return g.encoding < 0 ? String.format(Locale.ROOT, "\"%s\" (ENCODING %d)", g.name, g.encoding)
            : String.format(Locale.ROOT, "\"%s\" (ENCODING %d, U+%04X)", g.name, g.encoding, g.encoding);
    }

    /** The header's length: the offset just past the empty line that ends it. */
    private static int headerLength(byte[] table) {
        for (int i = 1; i < table.length; i++) {
            if (table[i] == '\n' && table[i - 1] == '\n') {
                return i + 1;
            }
        }
        throw new IllegalStateException("no empty line in the table");
    }

    static boolean isPrintableAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(String s, int length) {
        if (s.length() != length) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            // ASCII only: Character.digit would also take other scripts' digits
            char c = s.charAt(i);
            if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F')) {
                return false;
            }
        }
        return true;
    }

    /** Lower-case hex of the SHA-256 of length bytes at offset. */
    static String sha256Hex(byte[] data, int offset, int length) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java 8 platform has SHA-256 (MessageDigest's Javadoc)
            throw new IllegalStateException(e);
        }
        digest.update(data, offset, length);
        byte[] hash = digest.digest();
        char[] out = new char[hash.length * 2];
        for (int i = 0; i < hash.length; i++) {
            out[2 * i] = Character.forDigit(hash[i] >>> 4 & 0xF, 16);
            out[2 * i + 1] = Character.forDigit(hash[i] & 0xF, 16);
        }
        return new String(out);
    }

    private static void putU32(byte[] out, int at, int value) {
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >>> 8);
        out[at + 2] = (byte) (value >>> 16);
        out[at + 3] = (byte) (value >>> 24);
    }

    private static int u32(byte[] in, int at) {
        return (in[at] & 0xFF) | (in[at + 1] & 0xFF) << 8 | (in[at + 2] & 0xFF) << 16 | (in[at + 3] & 0xFF) << 24;
    }

    private static String quote(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }
}
