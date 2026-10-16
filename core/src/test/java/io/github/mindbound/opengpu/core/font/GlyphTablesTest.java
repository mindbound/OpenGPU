package io.github.mindbound.opengpu.core.font;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mindbound.opengpu.core.cmd.Fonts;

/**
 * {@link GlyphTables}' reader on tables written here from plan §3.14's format, independently of the converter: every
 * wrong header line, a missing empty line, a CRC or count mismatch, a short stream, trailing bytes, rows
 * {@link GlyphTable} refuses, a missing glyph other than U+FFFD; {@code header}'s keys in order; and {@code font0()}'s
 * failure on a missing or malformed resource, the same on every call.
 */
class GlyphTablesTest {

    private static final String NAME = "t.glyphs";
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final int[] CODEPOINTS = { 0x41, 0x67, 0xFFFD };
    /** 'A', 'g' and U+FFFD as misc-fixed 6x13 draws them. */
    private static final int[][] ROWS = {
        { 0x00, 0x00, 0x20, 0x50, 0x88, 0x88, 0x88, 0xF8, 0x88, 0x88, 0x88, 0x00, 0x00 },
        { 0x00, 0x00, 0x00, 0x00, 0x00, 0x70, 0x88, 0x88, 0x88, 0x78, 0x08, 0x88, 0x70 },
        { 0x00, 0x00, 0x70, 0xD8, 0xA8, 0xE8, 0xD8, 0xD8, 0xF8, 0xD8, 0x70, 0x00, 0x00 } };

    @Test
    void readsAValidTable() throws IOException {
        GlyphTable t = read(valid());
        Assertions.assertEquals(6, t.cellWidth());
        Assertions.assertEquals(13, t.cellHeight());
        Assertions.assertEquals(3, t.size());
        for (int i = 0; i < 3; i++) {
            Assertions.assertEquals(CODEPOINTS[i], t.codepointAt(i));
            for (int r = 0; r < 13; r++) {
                Assertions.assertEquals(ROWS[i][r], t.row(i, r), "glyph " + i + " row " + r);
            }
        }
        for (int r = 0; r < 13; r++) {
            Assertions.assertEquals(ROWS[2][r], t.row(GlyphTable.MISSING, r));
        }
        Assertions.assertEquals(GlyphTable.MISSING, t.indexOf(0x42));
    }

    @Test
    void eachWrongHeaderLineIsRejected() {
        String[][] cases = {
            { "0", "OpenGPU glyph table 2", "line 1 is \"OpenGPU glyph table 2\", not \"OpenGPU glyph table 1\"" },
            { "0", "OpenGPU glyph table 1 ", "line 1 is" },
            { "1", "font 1", "the header says font 1, font 0's table has font 0" },
            { "1", "font  0", "the header says font  0" },
            { "1", "fonts 0", "line 2 is \"fonts 0\", where the font line belongs" },
            { "2", "cell 8x13", "the header says cell 8x13" }, { "3", "glyphs 03", "glyphs 03 is not a count" },
            { "3", "glyphs -3", "glyphs -3 is not a count" }, { "3", "glyphs 3x", "glyphs 3x is not a count" },
            { "3", "glyphs 12345678", "is not a count" },
            { "3", "glyphs 1112065", "glyphs 1112065 is not a count of 0 to 1112064" },
            { "4", "missing U+0000", "the header says missing U+0000" },
            { "5", "source", "line 6 \"source\" has no value" },
            { "5", "sources t.bdf", "where the source line belongs" },
            { "6", "source-sha256 " + SHA.toUpperCase(Locale.ROOT), "is not 64 lower-case hex digits" },
            { "6", "source-sha256 " + SHA.substring(1), "is not 64 lower-case hex digits" },
            { "7", "source-id ", "line 8 \"source-id \" has no value" },
            { "8", "licence public domain; credited in OpenGPU's NOTICE", "where the copyright line belongs" },
            { "9", "licence", "has no value" } };
        for (String[] c : cases) {
            List<String> header = header();
            header.set(Integer.parseInt(c[0]), c[1]);
            assertRejected(table(header, validBody()), c[2]);
        }
        // A tenth key-value line where the empty line belongs
        List<String> extra = header();
        extra.add("extra x");
        assertRejected(table(extra, validBody()), "line 11 is \"extra x\", not the empty line that ends the header");
    }

    @Test
    void aMissingEmptyLineIsRejected() {
        // The body's first byte, n's low byte 0x03, is then read as a header line's
        assertRejected(table(header(), false, validBody(), crc(validBody())), "header line 11 has the byte 0x03");
    }

    @Test
    void headerLinesArePrintableAsciiEndingInLf() {
        byte[] crlf = valid();
        // The first LF becomes CR LF
        byte[] withCr = new byte[crlf.length + 1];
        int lf = indexOf(crlf, (byte) '\n');
        System.arraycopy(crlf, 0, withCr, 0, lf);
        withCr[lf] = '\r';
        System.arraycopy(crlf, lf, withCr, lf + 1, crlf.length - lf);
        assertRejected(withCr, "header line 1 has the byte 0x0D");
        List<String> latin1 = header();
        latin1.set(8, "copyright caf" + (char) 0xE9);
        assertRejected(table(latin1, validBody()), "header line 9 has the byte 0xE9");
        List<String> tab = header();
        tab.set(5, "source\tt.bdf");
        assertRejected(table(tab, validBody()), "header line 6 has the byte 0x09");
        List<String> longLine = header();
        char[] filler = new char[1100];
        Arrays.fill(filler, 'x');
        longLine.set(7, "source-id " + new String(filler));
        assertRejected(table(longLine, validBody()), "header line 8 is longer than 1024 bytes");
    }

    @Test
    void aCountMismatchIsRejected() {
        List<String> two = header();
        two.set(3, "glyphs 2");
        assertRejected(table(two, validBody()), "the body holds 3 glyphs, the header 2");
        List<String> four = header();
        four.set(3, "glyphs 4");
        assertRejected(table(four, validBody()), "the body holds 3 glyphs, the header 4");
    }

    @Test
    void aCrcMismatchIsRejected() {
        byte[] body = validBody();
        int crc = crc(body);
        // One bit of a row, of a codepoint, of the missing glyph; and the stored CRC itself
        for (int at : new int[] { 4 + 12 + 2, 4, 4 + 12 + 39 + 5 }) {
            byte[] changed = body.clone();
            changed[at] ^= 0x04;
            assertRejected(table(header(), true, changed, crc), "the body's CRC32 is 0x");
        }
        assertRejected(
            table(header(), true, body, crc ^ 1),
            String.format(Locale.ROOT, "the table says 0x%08X", crc ^ 1));
    }

    @Test
    void aShortStreamIsAnEofExceptionNamingTheTable() {
        byte[] whole = valid();
        for (int length = 0; length < whole.length; length++) {
            byte[] cut = Arrays.copyOf(whole, length);
            EOFException e = Assertions.assertThrows(EOFException.class, () -> read(cut), "cut at " + length);
            Assertions.assertTrue(
                e.getMessage()
                    .startsWith(NAME + ": the stream ends in "),
                e.getMessage());
        }
    }

    @Test
    void bytesAfterTheCrcAreRejected() {
        byte[] whole = valid();
        assertRejected(Arrays.copyOf(whole, whole.length + 1), "bytes follow the CRC");
    }

    @Test
    void rowsGlyphTableRefusesAreRejectedWithTheName() {
        int[][] rows = copy(ROWS);
        rows[1][4] = 0x01;
        assertRejected(
            table(header(), body(CODEPOINTS, rows, ROWS[2])),
            NAME + ": glyph 1 row 4 sets a bit outside the cell");
        assertRejected(
            table(header(), body(new int[] { 0x67, 0x41, 0xFFFD }, ROWS, ROWS[2])),
            NAME + ": codepoints are not strictly ascending at 1");
        assertRejected(
            table(header(), body(new int[] { 0x41, 0xD800, 0xFFFD }, ROWS, ROWS[2])),
            "at 1 is no Unicode scalar value");
        int[] missing = ROWS[2].clone();
        missing[0] = 0x03;
        assertRejected(table(header(), body(CODEPOINTS, ROWS, missing)), NAME + ": missing glyph row 0 sets a bit");
    }

    @Test
    void theMissingGlyphIsTheTablesUfffd() {
        assertRejected(
            table(header(), body(CODEPOINTS, ROWS, ROWS[0])),
            "the missing glyph differs from the table's U+FFFD");
        assertRejected(
            table(header(), body(new int[] { 0x41, 0x67, 0xFFFC }, ROWS, ROWS[2])),
            "the header names U+FFFD as the missing glyph, but the table has no U+FFFD");
    }

    @Test
    void headerGivesTheKeysInOrderAndLeavesTheStreamAtTheBody() throws IOException {
        InputStream in = new ByteArrayInputStream(valid());
        Map<String, String> header = GlyphTables.header(in, NAME);
        Assertions.assertEquals(
            Arrays.asList(
                "font",
                "cell",
                "glyphs",
                "missing",
                "source",
                "source-sha256",
                "source-id",
                "copyright",
                "licence"),
            new ArrayList<String>(header.keySet()));
        Assertions.assertEquals("3", header.get("glyphs"));
        Assertions.assertEquals("$Id: t.bdf,v 1 $", header.get("source-id"));
        // The value after the key's one space, byte for byte
        Assertions.assertEquals("Public domain font.  Share and enjoy.", header.get("copyright"));
        Assertions.assertEquals("public domain; credited in OpenGPU's NOTICE", header.get("licence"));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> header.put("x", "y"));
        byte[] n = new byte[4];
        Assertions.assertEquals(4, in.read(n));
        Assertions.assertArrayEquals(new byte[] { 3, 0, 0, 0 }, n);
        // header() checks the form only: font 1 passes it, and read() rejects it
        List<String> font1 = header();
        font1.set(1, "font 1");
        Map<String, String> passes = GlyphTables.header(new ByteArrayInputStream(table(font1, validBody())), NAME);
        Assertions.assertEquals("1", passes.get("font"));
    }

    /**
     * font0() in a class loader of its own, so its holder starts fresh: a missing resource and a malformed one fail
     * with an IllegalStateException naming the resource on the first call and again on the second (no
     * NoClassDefFoundError), and a good copy loads once.
     */
    @Test
    void font0FailsTheSameWayOnEveryCallWhenItsResourceIsMissingOrMalformed(@TempDir Path dir) throws Exception {
        Path missing = isolatedClasses(dir.resolve("missing"));
        for (Throwable t : font0Twice(missing)) {
            Assertions.assertInstanceOf(IllegalStateException.class, t);
            Assertions.assertEquals(
                "font 0's glyph table " + GlyphTables.FONT0_RESOURCE + " is not on the classpath (28:631)",
                t.getMessage());
        }
        Path malformed = isolatedClasses(dir.resolve("malformed"));
        writeResource(malformed, "OpenGPU glyph table 2\n".getBytes(StandardCharsets.US_ASCII));
        for (Throwable t : font0Twice(malformed)) {
            Assertions.assertInstanceOf(IllegalStateException.class, t);
            Assertions.assertEquals(
                "font 0's glyph table " + GlyphTables.FONT0_RESOURCE
                    + ": line 1 is \"OpenGPU glyph table 2\", not \"OpenGPU glyph table 1\"",
                t.getMessage());
            Assertions.assertInstanceOf(IllegalArgumentException.class, t.getCause());
        }
        byte[] real = shippedFont0();
        Path truncated = isolatedClasses(dir.resolve("truncated"));
        writeResource(truncated, Arrays.copyOf(real, real.length - 1));
        for (Throwable t : font0Twice(truncated)) {
            Assertions.assertInstanceOf(IllegalStateException.class, t);
            Assertions.assertEquals(
                "font 0's glyph table " + GlyphTables.FONT0_RESOURCE + ": the stream ends in the CRC",
                t.getMessage());
            Assertions.assertInstanceOf(EOFException.class, t.getCause());
        }
        // A good copy: the same table object on both calls
        Path good = isolatedClasses(dir.resolve("good"));
        writeResource(good, real);
        try (URLClassLoader loader = new URLClassLoader(
            new URL[] { good.toUri()
                .toURL() },
            null)) {
            Method font0 = Class.forName(GlyphTables.class.getName(), true, loader)
                .getMethod("font0");
            Object first = font0.invoke(null);
            Assertions.assertSame(first, font0.invoke(null));
            Assertions.assertEquals(
                4121,
                first.getClass()
                    .getMethod("size")
                    .invoke(first));
        }
    }

    // The format, written here from plan §3.14 rather than by the converter

    /** The header lines of a valid three-glyph font-0 table, without line ends. */
    private static List<String> header() {
        return new ArrayList<String>(
            Arrays.asList(
                "OpenGPU glyph table 1",
                "font 0",
                "cell 6x13",
                "glyphs 3",
                "missing U+FFFD",
                "source t.bdf",
                "source-sha256 " + SHA,
                "source-id $Id: t.bdf,v 1 $",
                "copyright Public domain font.  Share and enjoy.",
                "licence public domain; credited in OpenGPU's NOTICE"));
    }

    /** u32 n, the codepoints, 13 rows per glyph and the missing glyph's 13, little-endian. */
    private static byte[] body(int[] codepoints, int[][] rows, int[] missing) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        u32(out, codepoints.length);
        for (int cp : codepoints) {
            u32(out, cp);
        }
        for (int[] glyph : rows) {
            for (int row : glyph) {
                out.write(row);
            }
        }
        for (int row : missing) {
            out.write(row);
        }
        return out.toByteArray();
    }

    private static byte[] validBody() {
        return body(CODEPOINTS, ROWS, ROWS[2]);
    }

    private static byte[] valid() {
        return table(header(), validBody());
    }

    private static byte[] table(List<String> header, byte[] body) {
        return table(header, true, body, crc(body));
    }

    /** Each header line and an LF, the empty line if asked, the body, and the given CRC, little-endian. */
    private static byte[] table(List<String> header, boolean emptyLine, byte[] body, int crc) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String line : header) {
            byte[] bytes = line.getBytes(StandardCharsets.ISO_8859_1);
            out.write(bytes, 0, bytes.length);
            out.write('\n');
        }
        if (emptyLine) {
            out.write('\n');
        }
        out.write(body, 0, body.length);
        u32(out, crc);
        return out.toByteArray();
    }

    private static int crc(byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        return (int) crc.getValue();
    }

    private static void u32(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }

    private static GlyphTable read(byte[] table) throws IOException {
        return GlyphTables.read(new ByteArrayInputStream(table), NAME);
    }

    private static void assertRejected(byte[] table, String reason) {
        IllegalArgumentException e = Assertions.assertThrows(IllegalArgumentException.class, () -> read(table), reason);
        Assertions.assertTrue(
            e.getMessage()
                .startsWith(NAME + ": "),
            e.getMessage());
        Assertions.assertTrue(
            e.getMessage()
                .contains(reason),
            "expected \"" + reason + "\" in: " + e.getMessage());
    }

    private static int[][] copy(int[][] rows) {
        int[][] out = new int[rows.length][];
        for (int i = 0; i < rows.length; i++) {
            out[i] = rows[i].clone();
        }
        return out;
    }

    private static int indexOf(byte[] data, byte b) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == b) {
                return i;
            }
        }
        return -1;
    }

    // font0() in a class loader of its own

    /**
     * A directory holding copies of GlyphTables, its holder, GlyphTable and Fonts, and no resource, for a loader whose
     * parent is the bootstrap loader, so nothing else of the test's classpath is visible.
     */
    private static Path isolatedClasses(Path dir) throws IOException {
        for (String name : new String[] { GlyphTables.class.getName(), GlyphTables.class.getName() + "$Font0",
            GlyphTable.class.getName(), Fonts.class.getName() }) {
            String path = name.replace('.', '/') + ".class";
            try (InputStream in = GlyphTablesTest.class.getClassLoader()
                .getResourceAsStream(path)) {
                Assertions.assertNotNull(in, path);
                Path target = dir.resolve(path);
                Files.createDirectories(target.getParent());
                Files.copy(in, target);
            }
        }
        return dir;
    }

    private static void writeResource(Path dir, byte[] bytes) throws IOException {
        Path target = dir.resolve(GlyphTables.FONT0_RESOURCE.substring(1));
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    /** What font0() throws on its first and second call in a fresh loader over the directory. */
    private static Throwable[] font0Twice(Path classes) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(
            new URL[] { classes.toUri()
                .toURL() },
            null)) {
            Method font0 = Class.forName(GlyphTables.class.getName(), true, loader)
                .getMethod("font0");
            Throwable[] out = new Throwable[2];
            for (int i = 0; i < 2; i++) {
                InvocationTargetException e = Assertions
                    .assertThrows(InvocationTargetException.class, () -> font0.invoke(null));
                out[i] = e.getCause();
            }
            return out;
        }
    }

    private static byte[] shippedFont0() throws IOException {
        try (InputStream in = GlyphTables.class.getResourceAsStream(GlyphTables.FONT0_RESOURCE)) {
            Assertions.assertNotNull(in, GlyphTables.FONT0_RESOURCE);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0;) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }
}
