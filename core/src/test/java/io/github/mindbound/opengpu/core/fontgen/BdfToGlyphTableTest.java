package io.github.mindbound.opengpu.core.fontgen;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.CRC32;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mindbound.opengpu.core.font.GlyphTable;
import io.github.mindbound.opengpu.core.font.GlyphTables;

/**
 * Font 0's converter (28:631; plan §3.14, amendments F) on hand-written 6x13 fonts: placement by BBX, every check with
 * its message, the header and body layout, determinism, a round trip through {@link GlyphTables#read}, and the real
 * {@code 6x13.bdf} converted twice into the table {@code :core} ships.
 */
class BdfToGlyphTableTest {

    /** The real BDF, relative to {@code :core}'s directory, where Gradle runs this test; a property overrides it. */
    private static final String REAL_BDF = System.getProperty("opengpu.font0.bdf", "src/fonts/misc-fixed/6x13.bdf");
    private static final String REAL_SHA256 = "d00cf1cbaed055bb91d11c80e063d5d2d3b66552a7f2ead7161ee3780f9123f0";

    private static final String COPYRIGHT = "\"Public domain font.  Share and enjoy.\"";
    /** misc-fixed 6x13's U+FFFD, copied from its BITMAP (6x13.bdf:82438-82450). */
    private static final String[] FFFD = { "00", "00", "70", "D8", "A8", "E8", "D8", "D8", "F8", "D8", "70", "00",
        "00" };
    private static final int[] FFFD_CELL = { 0x00, 0x00, 0x70, 0xD8, 0xA8, 0xE8, 0xD8, 0xD8, 0xF8, 0xD8, 0x70, 0x00,
        0x00 };
    /** The first glyph's STARTCHAR line in {@link #font}, with a COPYRIGHT. */
    private static final int FIRST_GLYPH_LINE = 10;

    @Test
    void fullCellsAreCopiedAndBbxXShiftsRight() {
        byte[] body = body(
            font(
                cellGlyph("A", 0x41, "00", "00", "20", "50", "88", "88", "88", "F8", "88", "88", "88", "00", "00"),
                // 5 wide at x 1: each row moves one column right
                glyph(
                    "B",
                    0x42,
                    "6 0",
                    "5 13 1 -2",
                    "00",
                    "00",
                    "F0",
                    "88",
                    "88",
                    "88",
                    "F0",
                    "88",
                    "88",
                    "88",
                    "F0",
                    "00",
                    "00"),
                fffd()));
        assertCell(body, 0x41, 0x00, 0x00, 0x20, 0x50, 0x88, 0x88, 0x88, 0xF8, 0x88, 0x88, 0x88, 0x00, 0x00);
        assertCell(body, 0x42, 0x00, 0x00, 0x78, 0x44, 0x44, 0x44, 0x78, 0x44, 0x44, 0x44, 0x78, 0x00, 0x00);
    }

    @Test
    void shortGlyphsArePlacedByBbxY() {
        byte[] body = body(
            font(
                // y 0, two rows: cell rows 11 - 2 = 9 and 10, shifted 2
                glyph("period", 0x2E, "6 0", "2 2 2 0", "C0", "C0"),
                // y -2, one row: the bottom row 12
                glyph("underscore", 0x5F, "6 0", "6 1 0 -2", "FC"),
                // y 8, three rows: the top rows 0-2, shifted 2
                glyph("quotesingle", 0x27, "6 0", "1 3 2 8", "80", "80", "80"),
                // y 9, two rows, 4 wide at x 1: rows 0 and 1
                glyph("degree", 0xB0, "6 0", "4 2 1 9", "60", "90"),
                // An empty box: a blank cell
                glyph("space", 0x20, "6 0", "0 0 0 0"),
                fffd()));
        assertCell(body, 0x2E, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x30, 0x30, 0, 0);
        assertCell(body, 0x5F, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFC);
        assertCell(body, 0x27, 0x20, 0x20, 0x20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertCell(body, 0xB0, 0x30, 0x48, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertCell(body, 0x20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    void anAdvanceOtherThanOneCellIsRejected() {
        assertRejected(
            font(glyph("x", 0x78, "7 0", "6 13 0 -2", FFFD), fffd()),
            FIRST_GLYPH_LINE,
            "advances 7 0, not 6 0");
        assertRejected(font(glyph("x", 0x78, "5 0", "6 13 0 -2", FFFD), fffd()), FIRST_GLYPH_LINE, "advances 5 0");
        assertRejected(font(glyph("x", 0x78, "6 1", "6 13 0 -2", FFFD), fffd()), FIRST_GLYPH_LINE, "advances 6 1");
    }

    @Test
    void aGlyphPastEachCellEdgeIsRejected() {
        // left, right, bottom, top
        for (String bbx : new String[] { "6 13 -1 -2", "6 13 1 -2", "1 1 6 0", "6 13 0 -3", "6 1 0 -3", "6 13 0 -1",
            "6 2 0 10" }) {
            String[] rows = new String[Integer.parseInt(bbx.split(" ")[1])];
            Arrays.fill(rows, "00");
            assertRejected(
                font(glyph("x", 0x78, "6 0", bbx, rows), fffd()),
                FIRST_GLYPH_LINE,
                "has BBX " + bbx + ", outside the 6x13 cell");
        }
    }

    @Test
    void aBitBeyondTheBoxWidthIsRejected() {
        // 5 wide: columns 0-4 are bits 7-3 (0xF8), so 0x04 is column 5
        assertRejected(
            font(glyph("x", 0x78, "6 0", "5 1 0 0", "04"), fffd()),
            FIRST_GLYPH_LINE,
            "row 0 is 04, with a bit beyond its width 5");
        Assertions.assertDoesNotThrow(() -> convert(font(glyph("x", 0x78, "6 0", "5 1 0 0", "08"), fffd())));
        // 6 wide: 0x02 and 0x01 are outside the cell's 0xFC
        assertRejected(font(glyph("x", 0x78, "6 0", "6 2 0 0", "00", "02"), fffd()), FIRST_GLYPH_LINE, "row 1 is 02");
        assertRejected(font(glyph("x", 0x78, "6 0", "6 1 0 0", "01"), fffd()), FIRST_GLYPH_LINE, "row 0 is 01");
        // An empty-width box takes no bit at all
        assertRejected(font(glyph("x", 0x78, "6 0", "0 1 0 0", "80"), fffd()), FIRST_GLYPH_LINE, "beyond its width 0");
    }

    @Test
    void aRepeatedEncodingIsRejectedNamingBothLines() {
        byte[] bdf = font(cellGlyph("x", 0x78, FFFD), cellGlyph("x.alt", 0x78, FFFD), fffd());
        // Each 13-row glyph block is 20 lines
        assertRejected(bdf, FIRST_GLYPH_LINE + 20, "repeats the encoding of line " + FIRST_GLYPH_LINE);
    }

    @Test
    void encodingsAreUnicodeScalarValues() {
        for (int cp : new int[] { 0xD800, 0xDBFF, 0xDC00, 0xDFFF, 0x110000, 999999999 }) {
            assertRejected(font(cellGlyph("x", cp, FFFD), fffd()), FIRST_GLYPH_LINE, "is no Unicode scalar value");
        }
        // U+0000 is a scalar value and stays in the table (plan amendments F), as do U+D7FF, U+E000 and U+10FFFF
        byte[] body = body(
            font(
                cellGlyph("char0", 0, FFFD),
                cellGlyph("a", 0xD7FF, FFFD),
                cellGlyph("b", 0xE000, FFFD),
                cellGlyph("c", 0x10FFFF, FFFD),
                fffd()));
        Assertions.assertEquals(5, u32(body, 0));
        Assertions.assertEquals(0, u32(body, 4));
        Assertions.assertEquals(0x10FFFF, u32(body, 4 + 4 * 4));
    }

    @Test
    void anUnencodedGlyphFailsTheConversion() {
        // Font 0's pinned file has none, so one means a changed file (plan amendments F)
        byte[] bdf = font(glyph("x", -1, "6 0", "6 13 0 -2", FFFD), fffd());
        assertRejected(bdf, FIRST_GLYPH_LINE, "has ENCODING -1");
        byte[] twoIntegers = new String(bdf, StandardCharsets.US_ASCII).replace("ENCODING -1", "ENCODING -1 120")
            .getBytes(StandardCharsets.US_ASCII);
        assertRejected(twoIntegers, FIRST_GLYPH_LINE, "has ENCODING -1");
    }

    @Test
    void aTableWithoutUfffdIsRejected() {
        byte[] bdf = font(cellGlyph("x", 0x78, FFFD), cellGlyph("y", 0xFFFC, FFFD));
        assertRejected(bdf, -1, "no glyph for U+FFFD, the missing glyph");
    }

    @Test
    void aWrongSha256IsRejectedNamingBothDigests() {
        byte[] bdf = font(fffd());
        String actual = sha256(bdf);
        String wrong = REAL_SHA256;
        IllegalArgumentException e = Assertions
            .assertThrows(IllegalArgumentException.class, () -> BdfToGlyphTable.convert(bdf, wrong, "hand.bdf"));
        Assertions.assertEquals("hand.bdf has SHA-256 " + actual + ", expected " + wrong, e.getMessage());
        // Either case is accepted for the expected digest; a malformed one is rejected as such
        String upper = actual.toUpperCase(java.util.Locale.ROOT);
        Assertions.assertDoesNotThrow(() -> BdfToGlyphTable.convert(bdf, upper, "hand.bdf"));
        for (String bad : new String[] { actual.substring(1), actual + "0", "g" + actual.substring(1), "" }) {
            IllegalArgumentException m = Assertions
                .assertThrows(IllegalArgumentException.class, () -> BdfToGlyphTable.convert(bdf, bad, "hand.bdf"));
            Assertions.assertTrue(
                m.getMessage()
                    .contains("is not 64 hex digits"),
                m.getMessage());
        }
    }

    @Test
    void aWrongBoundingBoxAscentOrDescentIsRejected() {
        assertRejected(font("8 13 0 -2", 11, 2, COPYRIGHT, fffd()), -1, "FONTBOUNDINGBOX is 8 13 0 -2, not 6 13 0 -2");
        assertRejected(font("6 12 0 -2", 11, 2, COPYRIGHT, fffd()), -1, "FONTBOUNDINGBOX is 6 12 0 -2");
        assertRejected(font("6 13 1 -2", 11, 2, COPYRIGHT, fffd()), -1, "FONTBOUNDINGBOX is 6 13 1 -2");
        assertRejected(font("6 13 0 -3", 11, 2, COPYRIGHT, fffd()), -1, "FONTBOUNDINGBOX is 6 13 0 -3");
        assertRejected(
            font("6 13 0 -2", 10, 2, COPYRIGHT, fffd()),
            -1,
            "FONT_ASCENT 10 and FONT_DESCENT 2, not 11 and 2");
        assertRejected(font("6 13 0 -2", 11, 3, COPYRIGHT, fffd()), -1, "FONT_DESCENT 3");
        // A parse error carries the source name and the line
        byte[] broken = new String(font(fffd()), StandardCharsets.US_ASCII).replace("BBX 6 13 0 -2", "BBX 6 13 0")
            .getBytes(StandardCharsets.US_ASCII);
        assertRejected(broken, FIRST_GLYPH_LINE + 4, "BBX takes 4 integers");
    }

    @Test
    void theHeaderAndBodyHaveTheirLayout() {
        // File order x, U+0000, U+FFFD, 'A': the body is ascending
        byte[] bdf = font(
            cellGlyph("x", 0x78, "00", "00", "00", "00", "00", "88", "50", "20", "50", "88", "00", "00", "00"),
            cellGlyph("char0", 0, "00", "00", "A8", "00", "88", "00", "88", "00", "88", "00", "A8", "00", "00"),
            fffd(),
            cellGlyph("A", 0x41, "00", "00", "20", "50", "88", "88", "88", "F8", "88", "88", "88", "00", "00"));
        byte[] table = convert(bdf);
        String header = "OpenGPU glyph table 1\n" + "font 0\n"
            + "cell 6x13\n"
            + "glyphs 4\n"
            + "missing U+FFFD\n"
            + "source hand.bdf\n"
            + "source-sha256 "
            + sha256(bdf)
            + "\n"
            + "source-id $Id: hand.bdf,v 1.0 2026-10-16 $\n"
            // The property's value byte for byte: two spaces after the full stop (plan amendments F)
            + "copyright Public domain font.  Share and enjoy.\n"
            + "licence public domain; credited in OpenGPU's NOTICE\n"
            + "\n";
        byte[] headerBytes = header.getBytes(StandardCharsets.US_ASCII);
        Assertions.assertArrayEquals(headerBytes, Arrays.copyOf(table, headerBytes.length));
        byte[] body = Arrays.copyOfRange(table, headerBytes.length, table.length - 4);
        Assertions.assertEquals(4 + 4 * 4 + 13 * 4 + 13, body.length);
        Assertions.assertArrayEquals(new byte[] { 4, 0, 0, 0 }, Arrays.copyOf(body, 4));
        // u32 little-endian codepoints, ascending
        Assertions.assertArrayEquals(
            new byte[] { 0, 0, 0, 0, 0x41, 0, 0, 0, 0x78, 0, 0, 0, (byte) 0xFD, (byte) 0xFF, 0, 0 },
            Arrays.copyOfRange(body, 4, 20));
        // Glyph i's rows at 20 + 13·i, row 0 first: U+0000, 'A', 'x', U+FFFD; then the missing glyph, U+FFFD's rows
        Assertions.assertEquals(0xA8, body[20 + 2] & 0xFF);
        Assertions.assertEquals(0x20, body[20 + 13 + 2] & 0xFF);
        Assertions.assertEquals(0x88, body[20 + 26 + 5] & 0xFF);
        for (int r = 0; r < 13; r++) {
            Assertions.assertEquals(FFFD_CELL[r], body[20 + 39 + r] & 0xFF, "U+FFFD row " + r);
            Assertions.assertEquals(FFFD_CELL[r], body[20 + 52 + r] & 0xFF, "missing row " + r);
        }
        // The CRC32 of the body, little-endian, ends the file
        CRC32 crc = new CRC32();
        crc.update(body, 0, body.length);
        Assertions.assertEquals((int) crc.getValue(), u32(table, table.length - 4));
        // body() gives the same bytes
        Assertions.assertArrayEquals(body, BdfToGlyphTable.body(Bdf.parse(bdf)));
    }

    @Test
    void headerValuesMustBePresentAndPrintableAscii() {
        // No COPYRIGHT property
        assertRejected(font("6 13 0 -2", 11, 2, null, fffd()), -1, "no COPYRIGHT property for the header");
        // A Latin-1 byte in COPYRIGHT
        byte[] latin1 = new String(font(fffd()), StandardCharsets.ISO_8859_1)
            .replace("Share and enjoy.", "Share and enjoy \u00E9")
            .getBytes(StandardCharsets.ISO_8859_1);
        assertRejected(latin1, -1, "not printable ASCII");
        // No COMMENT holding $Id
        byte[] noId = new String(font(fffd()), StandardCharsets.US_ASCII).replace("$Id:", "Id:")
            .getBytes(StandardCharsets.US_ASCII);
        assertRejected(noId, -1, "no COMMENT holding $Id for the header");
        // The source name goes into the header too
        byte[] bdf = font(fffd());
        for (String name : new String[] { "", "a\nb", "caf\u00E9.bdf" }) {
            IllegalArgumentException e = Assertions
                .assertThrows(IllegalArgumentException.class, () -> BdfToGlyphTable.convert(bdf, sha256(bdf), name));
            Assertions.assertTrue(
                e.getMessage()
                    .contains("is not printable ASCII"),
                e.getMessage());
        }
    }

    @Test
    void twoConversionsAreByteIdentical() {
        byte[] bdf = font(cellGlyph("x", 0x78, FFFD), cellGlyph("A", 0x41, FFFD), fffd());
        byte[] first = convert(bdf);
        byte[] second = convert(bdf.clone());
        Assertions.assertArrayEquals(first, second);
        // CRLF changes the SHA-256 but not the glyphs, so only the header's digest differs
        byte[] crlf = new String(bdf, StandardCharsets.US_ASCII).replace("\n", "\r\n")
            .getBytes(StandardCharsets.US_ASCII);
        byte[] third = convert(crlf);
        Assertions.assertArrayEquals(bodyOf(first), bodyOf(third));
    }

    @Test
    void aTableReadsBackThroughGlyphTables() throws IOException {
        byte[] bdf = font(
            glyph("degree", 0xB0, "6 0", "4 2 1 9", "60", "90"),
            cellGlyph("A", 0x41, "00", "00", "20", "50", "88", "88", "88", "F8", "88", "88", "88", "00", "00"),
            glyph("underscore", 0x5F, "6 0", "6 1 0 -2", "FC"),
            fffd());
        byte[] table = convert(bdf);
        GlyphTable read = GlyphTables.read(new ByteArrayInputStream(table), "hand.glyphs");
        byte[] body = bodyOf(table);
        Assertions.assertEquals(6, read.cellWidth());
        Assertions.assertEquals(13, read.cellHeight());
        Assertions.assertEquals(4, read.size());
        for (int i = 0; i < read.size(); i++) {
            int cp = read.codepointAt(i);
            for (int r = 0; r < 13; r++) {
                Assertions.assertEquals(cellOf(body, cp)[r] & 0xFF, read.row(i, r), "U+" + Integer.toHexString(cp));
            }
        }
        for (int r = 0; r < 13; r++) {
            Assertions.assertEquals(FFFD_CELL[r], read.row(GlyphTable.MISSING, r));
        }
        Assertions.assertEquals(0x48, read.row(read.indexOf(0xB0), 1));
        Assertions.assertEquals(0xFC, read.row(read.indexOf(0x5F), 12));
    }

    @Test
    void runWritesTheTableOrExitsNonZero(@TempDir Path dir) throws IOException {
        byte[] bdf = font(cellGlyph("A", 0x41, FFFD), fffd());
        Path in = dir.resolve("hand.bdf");
        Files.write(in, bdf);
        Path out = dir.resolve("deep/er/hand.glyphs");
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream logStream = new PrintStream(log, true, "UTF-8");
        PrintStream errStream = new PrintStream(err, true, "UTF-8");
        // A wrong argument count: 2, and the usage
        Assertions.assertEquals(2, BdfToGlyphTable.run(new String[] { in.toString() }, logStream, errStream));
        Assertions.assertTrue(
            err.toString("UTF-8")
                .startsWith("usage: BdfToGlyphTable"));
        // A failed check: 1, the message, and no file
        err.reset();
        Assertions.assertEquals(
            1,
            BdfToGlyphTable
                .run(new String[] { in.toString(), out.toString(), REAL_SHA256, "hand.bdf" }, logStream, errStream));
        Assertions.assertTrue(
            err.toString("UTF-8")
                .contains("hand.bdf has SHA-256 "),
            err.toString("UTF-8"));
        Assertions.assertFalse(Files.exists(out));
        // Success: 0, the directories made, the table written, one log line
        Assertions.assertEquals(
            0,
            BdfToGlyphTable
                .run(new String[] { in.toString(), out.toString(), sha256(bdf), "hand.bdf" }, logStream, errStream));
        Assertions.assertArrayEquals(convert(bdf), Files.readAllBytes(out));
        String line = log.toString("UTF-8");
        Assertions.assertTrue(line.contains("hand.bdf -> hand.glyphs: 2 glyphs"), line);
        Assertions.assertTrue(line.contains("body sha256 " + sha256(bodyOf(convert(bdf)))), line);
    }

    /**
     * The real {@code 6x13.bdf}, converted twice: byte-identical, and equal to the table on the classpath, so the
     * shipped resource is what the converter makes of the pinned file.
     */
    @Test
    void theRealFontConvertsTwiceIntoTheShippedTable() throws IOException {
        Path real = Paths.get(REAL_BDF);
        Assertions.assertTrue(
            Files.isRegularFile(real),
            "no " + real.toAbsolutePath() + ": run from :core's directory or set -Dopengpu.font0.bdf");
        byte[] bdf = Files.readAllBytes(real);
        byte[] first = BdfToGlyphTable.convert(bdf, REAL_SHA256, "6x13.bdf");
        byte[] second = BdfToGlyphTable.convert(Files.readAllBytes(real), REAL_SHA256, "6x13.bdf");
        Assertions.assertArrayEquals(first, second);
        byte[] shipped;
        try (InputStream in = GlyphTables.class.getResourceAsStream(GlyphTables.FONT0_RESOURCE)) {
            Assertions.assertNotNull(in, GlyphTables.FONT0_RESOURCE);
            shipped = readAll(in);
        }
        Assertions.assertArrayEquals(first, shipped);
    }

    // A hand-written 6x13 font

    /** A font with misc-fixed's bounding box and metrics, an $Id comment, misc-fixed's COPYRIGHT and the glyphs. */
    static byte[] font(String... glyphs) {
        return font("6 13 0 -2", 11, 2, COPYRIGHT, glyphs);
    }

    /** As {@link #font(String...)}, with the box, metrics and quoted COPYRIGHT (null for none) given. */
    static byte[] font(String boundingBox, int ascent, int descent, String copyright, String... glyphs) {
        StringBuilder b = new StringBuilder();
        b.append("STARTFONT 2.1\n");
        b.append("COMMENT $Id: hand.bdf,v 1.0 2026-10-16 $\n");
        b.append("FONTBOUNDINGBOX ")
            .append(boundingBox)
            .append('\n');
        b.append("STARTPROPERTIES ")
            .append(copyright == null ? 2 : 3)
            .append('\n');
        b.append("FONT_ASCENT ")
            .append(ascent)
            .append('\n');
        b.append("FONT_DESCENT ")
            .append(descent)
            .append('\n');
        if (copyright != null) {
            b.append("COPYRIGHT ")
                .append(copyright)
                .append('\n');
        }
        b.append("ENDPROPERTIES\n");
        b.append("CHARS ")
            .append(glyphs.length)
            .append('\n');
        for (String glyph : glyphs) {
            b.append(glyph);
        }
        b.append("ENDFONT\n");
        return b.toString()
            .getBytes(StandardCharsets.ISO_8859_1);
    }

    /** One glyph block: STARTCHAR, ENCODING, SWIDTH, DWIDTH, BBX, BITMAP, the rows and ENDCHAR (7 + rows lines). */
    static String glyph(String name, int encoding, String dwidth, String bbx, String... rows) {
        StringBuilder b = new StringBuilder();
        b.append("STARTCHAR ")
            .append(name)
            .append('\n');
        b.append("ENCODING ")
            .append(encoding)
            .append('\n');
        b.append("SWIDTH 480 0\n");
        b.append("DWIDTH ")
            .append(dwidth)
            .append('\n');
        b.append("BBX ")
            .append(bbx)
            .append('\n');
        b.append("BITMAP\n");
        for (String row : rows) {
            b.append(row)
                .append('\n');
        }
        return b.append("ENDCHAR\n")
            .toString();
    }

    /** A full-cell glyph, BBX 6 13 0 -2, as every glyph of misc-fixed 6x13 is. */
    static String cellGlyph(String name, int encoding, String... rows) {
        return glyph(name, encoding, "6 0", "6 13 0 -2", rows);
    }

    static String fffd() {
        return cellGlyph("uniFFFD", 0xFFFD, FFFD);
    }

    static byte[] convert(byte[] bdf) {
        return BdfToGlyphTable.convert(bdf, sha256(bdf), "hand.bdf");
    }

    private static byte[] body(byte[] bdf) {
        byte[] body = BdfToGlyphTable.body(Bdf.parse(bdf));
        Assertions.assertArrayEquals(body, bodyOf(convert(bdf)), "body() and convert() agree");
        return body;
    }

    /** A table's body: after the header's empty line, before the CRC. */
    static byte[] bodyOf(byte[] table) {
        for (int i = 1; i < table.length; i++) {
            if (table[i] == '\n' && table[i - 1] == '\n') {
                return Arrays.copyOfRange(table, i + 1, table.length - 4);
            }
        }
        throw new AssertionError("no empty line");
    }

    /** The codepoint's 13 rows in a body, by a linear search of its codepoints. */
    private static byte[] cellOf(byte[] body, int cp) {
        int n = u32(body, 0);
        for (int i = 0; i < n; i++) {
            if (u32(body, 4 + 4 * i) == cp) {
                int at = 4 + 4 * n + 13 * i;
                return Arrays.copyOfRange(body, at, at + 13);
            }
        }
        throw new AssertionError("no U+" + Integer.toHexString(cp) + " in the body");
    }

    private static void assertCell(byte[] body, int cp, int... rows) {
        byte[] cell = cellOf(body, cp);
        for (int r = 0; r < 13; r++) {
            Assertions.assertEquals(rows[r], cell[r] & 0xFF, "U+" + Integer.toHexString(cp) + " row " + r);
        }
    }

    /** The conversion fails with the source name, the glyph's line (or none for -1), and the reason. */
    private static void assertRejected(byte[] bdf, int line, String reason) {
        IllegalArgumentException e = Assertions
            .assertThrows(IllegalArgumentException.class, () -> convert(bdf), reason);
        String prefix = line < 0 ? "hand.bdf: " : "hand.bdf line " + line + ": ";
        Assertions.assertTrue(
            e.getMessage()
                .startsWith(prefix),
            "expected " + prefix + "..., got: " + e.getMessage());
        Assertions.assertTrue(
            e.getMessage()
                .contains(reason),
            "expected \"" + reason + "\" in: " + e.getMessage());
    }

    static String sha256(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(data);
            StringBuilder out = new StringBuilder();
            for (byte b : hash) {
                out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xFF));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0;) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static int u32(byte[] in, int at) {
        return (in[at] & 0xFF) | (in[at + 1] & 0xFF) << 8 | (in[at + 2] & 0xFF) << 16 | (in[at + 3] & 0xFF) << 24;
    }
}
