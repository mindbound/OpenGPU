package io.github.mindbound.opengpu.core.fontgen;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The BDF 2.1 reader of font 0's converter (28:631; plan §3.14): properties, {@code COMMENT} lines, glyphs with both
 * {@code ENCODING} forms, CRLF, and each malformation reported with its line number.
 */
class BdfTest {

    /**
     * A hand-written font: three glyphs in file order 'B', an unencoded one and 'A', a quote inside COPYRIGHT, a
     * COMMENT between the properties and between the glyphs. Line numbers are noted where the tests use them.
     */
    private static List<String> font() {
        return new ArrayList<String>(
            Arrays.asList(
                "STARTFONT 2.1", // 1
                "COMMENT $Id: test.bdf,v 1.1 2026-10-16 $", // 2
                "COMMENT  two blanks, one kept", // 3
                "FONT -Test-Fixed-Medium-R-Normal--13-120-75-75-C-60-ISO10646-1", // 4
                "SIZE 12 75 75", // 5
                "FONTBOUNDINGBOX 6 13 0 -2", // 6
                "STARTPROPERTIES 4", // 7
                "FONT_ASCENT 11", // 8
                "COMMENT inside the properties", // 9
                "FONT_DESCENT 2", // 10
                "COPYRIGHT \"Public domain font.  Say \"\"hi\"\".\"", // 11
                "DEFAULT_CHAR 0", // 12
                "ENDPROPERTIES", // 13
                "CHARS 3", // 14
                "STARTCHAR B", // 15
                "ENCODING 66", // 16
                "SWIDTH 480 0", // 17
                "DWIDTH 6 0", // 18
                "BBX 5 3 1 0", // 19
                "BITMAP", // 20
                "F0", // 21
                "88", // 22
                "f0", // 23
                "ENDCHAR", // 24
                "COMMENT between the glyphs", // 25
                "STARTCHAR unencoded", // 26
                "ENCODING -1 200", // 27
                "DWIDTH 6 0", // 28
                "BBX 12 1 0 -2", // 29
                "BITMAP", // 30
                "ABC0", // 31
                "ENDCHAR", // 32
                "STARTCHAR A", // 33
                "ENCODING 65", // 34
                "DWIDTH 6 0", // 35
                "BBX 0 0 0 0", // 36
                "BITMAP", // 37
                "ENDCHAR", // 38
                "ENDFONT")); // 39
    }

    @Test
    void readsTheGlobalsPropertiesAndComments() {
        Bdf f = Bdf.parse(bytes(font(), "\n"));
        Assertions.assertArrayEquals(new int[] { 6, 13, 0, -2 }, f.fontBoundingBox());
        Assertions.assertEquals(11, f.ascent());
        Assertions.assertEquals(2, f.descent());
        // Unquoted, the doubled quote read as one, both spaces kept
        Assertions.assertEquals("Public domain font.  Say \"hi\".", f.copyright());
        Assertions.assertEquals("0", f.property("DEFAULT_CHAR"));
        Assertions.assertNull(f.property("FOUNDRY"));
        // In file order, each the text after COMMENT and one blank
        Assertions.assertEquals(
            Arrays.asList(
                "$Id: test.bdf,v 1.1 2026-10-16 $",
                " two blanks, one kept",
                "inside the properties",
                "between the glyphs"),
            f.comments());
        // fontBoundingBox() is a copy
        f.fontBoundingBox()[0] = 99;
        Assertions.assertEquals(6, f.fontBoundingBox()[0]);
        Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> f.comments()
                .add("x"));
        Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> f.glyphs()
                .clear());
    }

    @Test
    void readsTheGlyphsInFileOrderWithBothEncodingForms() {
        List<Bdf.Glyph> glyphs = Bdf.parse(bytes(font(), "\n"))
            .glyphs();
        Assertions.assertEquals(3, glyphs.size());
        Bdf.Glyph b = glyphs.get(0);
        Assertions.assertEquals("B", b.name);
        Assertions.assertEquals(15, b.line);
        Assertions.assertEquals(66, b.encoding);
        Assertions.assertEquals(6, b.dwidthX);
        Assertions.assertEquals(0, b.dwidthY);
        Assertions.assertEquals(5, b.bbxW);
        Assertions.assertEquals(3, b.bbxH);
        Assertions.assertEquals(1, b.bbxX);
        Assertions.assertEquals(0, b.bbxY);
        Assertions.assertEquals(1, b.rowBytes());
        // Lower-case hex reads as upper-case does
        Assertions.assertArrayEquals(new int[] { 0xF0, 0x88, 0xF0 }, b.rows);
        // ENCODING -1 200: unencoded, the non-standard index not kept; a 12-wide row is two bytes
        Bdf.Glyph unencoded = glyphs.get(1);
        Assertions.assertEquals(-1, unencoded.encoding);
        Assertions.assertEquals(26, unencoded.line);
        Assertions.assertEquals(2, unencoded.rowBytes());
        Assertions.assertArrayEquals(new int[] { 0xABC0 }, unencoded.rows);
        // An empty box has no rows
        Bdf.Glyph a = glyphs.get(2);
        Assertions.assertEquals(65, a.encoding);
        Assertions.assertEquals(0, a.rows.length);
    }

    @Test
    void theOneIntegerUnencodedFormAndTheTwoIntegerRule() {
        List<String> lines = font();
        lines.set(26, "ENCODING -1");
        Assertions.assertEquals(
            -1,
            Bdf.parse(bytes(lines, "\n"))
                .glyphs()
                .get(1).encoding);
        // A second integer follows -1 only (BDF 2.1), and nothing is below -1
        lines.set(26, "ENCODING 65 7");
        assertRejected(lines, 27, "follows -1 only");
        lines.set(26, "ENCODING -2");
        assertRejected(lines, 27, "below -1");
        lines.set(26, "ENCODING -1 2 3");
        assertRejected(lines, 27, "1 or 2 integers");
        lines.set(26, "ENCODING x");
        assertRejected(lines, 27, "not an integer");
    }

    @Test
    void crlfLineEndsReadAsLf() {
        Bdf lf = Bdf.parse(bytes(font(), "\n"));
        Bdf crlf = Bdf.parse(bytes(font(), "\r\n"));
        Assertions.assertEquals(lf.comments(), crlf.comments());
        Assertions.assertEquals(lf.copyright(), crlf.copyright());
        Assertions.assertEquals(
            lf.glyphs()
                .size(),
            crlf.glyphs()
                .size());
        for (int i = 0; i < lf.glyphs()
            .size(); i++) {
            Bdf.Glyph x = lf.glyphs()
                .get(i);
            Bdf.Glyph y = crlf.glyphs()
                .get(i);
            Assertions.assertEquals(x.name, y.name);
            Assertions.assertEquals(x.line, y.line);
            Assertions.assertEquals(x.encoding, y.encoding);
            Assertions.assertArrayEquals(x.rows, y.rows);
        }
        // A last line without its line end, with or without a CR
        String text = new String(bytes(font(), "\r\n"), StandardCharsets.ISO_8859_1);
        Assertions.assertEquals(
            3,
            Bdf.parse(trimEnd(text, 2))
                .glyphs()
                .size());
        Assertions.assertEquals(
            3,
            Bdf.parse(trimEnd(text, 1))
                .glyphs()
                .size());
    }

    @Test
    void aMissingEndcharIsReportedWhereTheNextKeywordStands() {
        List<String> lines = font();
        lines.remove(23); // ENDCHAR of B, line 24; the COMMENT moves up to 24
        assertRejected(lines, 24, "more than the 3 rows");
        lines.remove(23); // the COMMENT too: STARTCHAR unencoded now stands at 24
        assertRejected(lines, 24, "no ENDCHAR for glyph \"B\" (line 15) before STARTCHAR");
        List<String> last = font();
        last.remove(37); // ENDCHAR of A: ENDFONT at 38
        assertRejected(last, 38, "no ENDCHAR for glyph \"A\" (line 33) before ENDFONT");
    }

    @Test
    void aShortBitmapIsReported() {
        List<String> lines = font();
        lines.remove(22); // B's third row: ENDCHAR now at 23, after two of BBX's three rows
        assertRejected(lines, 23, "BITMAP has 2 rows, BBX says 3");
        // The file ends inside a BITMAP
        List<String> cut = new ArrayList<String>(font().subList(0, 21));
        assertRejected(cut, 22, "ends in glyph \"B\"'s BITMAP");
    }

    @Test
    void badHexAndWrongDigitCountsAreReported() {
        for (String row : new String[] { "G0", "0x", " F", "F ", "\u00B2F" }) {
            List<String> lines = font();
            lines.set(21, row);
            assertRejected(lines, 22, "not hex");
        }
        // A 5-wide row is two hex digits, a 12-wide one four, and an empty line is no row
        for (String row : new String[] { "F", "F00", "", "F000" }) {
            List<String> lines = font();
            lines.set(21, row);
            assertRejected(lines, 22, "hex digits, not 2");
        }
        List<String> wide = font();
        wide.set(30, "ABC");
        assertRejected(wide, 31, "hex digits, not 4");
    }

    @Test
    void theStructureIsChecked() {
        List<String> lines = font();
        lines.set(0, "STARTFONT 2.2");
        assertRejected(lines, 1, "expected STARTFONT 2.1");
        lines = font();
        lines.set(13, "CHARS 4"); // three glyphs follow; reported at ENDFONT
        assertRejected(lines, 39, "CHARS 4 at line 14, but 3 glyphs");
        lines = font();
        lines.set(6, "STARTPROPERTIES 5"); // four properties follow
        assertRejected(lines, 13, "STARTPROPERTIES 5, but ENDPROPERTIES after 4");
        lines = font();
        lines.set(6, "STARTPROPERTIES 3");
        assertRejected(lines, 12, "where ENDPROPERTIES belongs");
        lines = font();
        lines.remove(38); // no ENDFONT: the file ends at line 39
        assertRejected(lines, 39, "ends before ENDFONT");
        lines = font();
        lines.add("STARTCHAR late");
        assertRejected(lines, 40, "after ENDFONT");
        lines = font();
        lines.set(19, "BITMAP 3");
        assertRejected(lines, 20, "unexpected \"BITMAP 3\"");
        lines = font();
        lines.set(16, "WIDTH 480 0");
        assertRejected(lines, 17, "unexpected \"WIDTH 480 0\" in glyph \"B\" (line 15)");
        lines = font();
        lines.set(16, "ENCODING 67");
        assertRejected(lines, 17, "a second ENCODING");
        lines = font();
        lines.remove(17); // B without DWIDTH: BITMAP at 19
        assertRejected(lines, 19, "lacks DWIDTH before BITMAP");
        lines = font();
        lines.set(4, "GLYPHS 3");
        assertRejected(lines, 5, "unexpected \"GLYPHS\" before CHARS");
    }

    @Test
    void numbersAndBoxesAreChecked() {
        List<String> lines = font();
        lines.set(18, "BBX 5 3 1");
        assertRejected(lines, 19, "BBX takes 4 integers");
        lines.set(18, "BBX 5 3 1 0 0");
        assertRejected(lines, 19, "BBX takes 4 integers");
        lines.set(18, "BBX 5 -3 1 0");
        assertRejected(lines, 19, "outside 0..32 wide");
        lines.set(18, "BBX 33 3 1 0");
        assertRejected(lines, 19, "outside 0..32 wide");
        lines.set(18, "BBX 5 3 1 1234567890");
        assertRejected(lines, 19, "not an integer");
        lines = font();
        lines.set(17, "DWIDTH 6");
        assertRejected(lines, 18, "DWIDTH takes 2 integers");
        lines = font();
        lines.set(5, "FONTBOUNDINGBOX 6 13 0 +2");
        assertRejected(lines, 6, "not an integer");
        lines = font();
        lines.set(13, "CHARS -3");
        assertRejected(lines, 14, "CHARS -3 is negative");
    }

    @Test
    void theRequiredGlobalsAreChecked() {
        List<String> lines = font();
        lines.remove(5); // no FONTBOUNDINGBOX: CHARS at 13
        assertRejected(lines, 13, "no FONTBOUNDINGBOX before CHARS");
        lines = font();
        lines.set(7, "PIXEL_SIZE 13"); // FONT_ASCENT replaced
        assertRejected(lines, 14, "no FONT_ASCENT property before CHARS");
        lines = font();
        lines.set(9, "FONT_DESCENT two");
        assertRejected(lines, 10, "FONT_DESCENT is \"two\", not an integer");
        lines = font();
        lines.set(10, "COPYRIGHT \"unclosed");
        assertRejected(lines, 11, "no closing quote");
        lines.set(10, "COPYRIGHT \"a \" b\"");
        assertRejected(lines, 11, "lone quote");
        lines.set(10, "COPYRIGHT");
        assertRejected(lines, 11, "COPYRIGHT has no value");
        lines.set(10, "DEFAULT_CHAR 1");
        assertRejected(lines, 12, "a second property DEFAULT_CHAR");
    }

    @Test
    void anAbsentCopyrightIsNullAndABareCommentIsEmpty() {
        List<String> lines = font();
        lines.set(10, "POINT_SIZE 120");
        lines.set(2, "COMMENT");
        Bdf f = Bdf.parse(bytes(lines, "\n"));
        Assertions.assertNull(f.copyright());
        Assertions.assertEquals(
            "",
            f.comments()
                .get(1));
        // Blank lines outside a BITMAP are skipped, and the line numbers still count them
        lines.add(14, "");
        Bdf blank = Bdf.parse(bytes(lines, "\n"));
        Assertions.assertEquals(
            16,
            blank.glyphs()
                .get(0).line);
    }

    @Test
    void bytesOutsideAsciiReadAsLatin1() {
        List<String> lines = font();
        lines.set(1, "COMMENT caf\u00E9");
        Bdf f = Bdf.parse(bytes(lines, "\n"));
        Assertions.assertEquals(
            "caf\u00E9",
            f.comments()
                .get(0));
    }

    private static void assertRejected(List<String> lines, int line, String reason) {
        IllegalArgumentException e = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> Bdf.parse(bytes(lines, "\n")),
            "line " + line + ": " + reason);
        Assertions.assertTrue(
            e.getMessage()
                .startsWith("line " + line + ": "),
            "expected line " + line + ", got: " + e.getMessage());
        Assertions.assertTrue(
            e.getMessage()
                .contains(reason),
            "expected \"" + reason + "\" in: " + e.getMessage());
    }

    /** The lines joined, each followed by the line end, as ISO-8859-1. */
    static byte[] bytes(List<String> lines, String end) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(line)
                .append(end);
        }
        return out.toString()
            .getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] trimEnd(String text, int chars) {
        return text.substring(0, text.length() - chars)
            .getBytes(StandardCharsets.ISO_8859_1);
    }
}
