package io.github.mindbound.opengpu.core.fixtures;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.fixtures.golden.GoldenCases;
import io.github.mindbound.opengpu.core.fixtures.golden.GoldenRunner;

/**
 * 28 §11.4's hand-computed vectors: every case of {@code oracle-goldens.txt} on the tier-E oracle, one dynamic test
 * per case, each segment through the main validator and the expected grids from the file, which
 * {@code docs/research/cmdstream-checks/oracle_goldens.py} re-derives from 28 alone. A failing case prints its
 * expected and actual grids with the differing pixels marked; it is re-derived with that script, and an
 * {@code expect} block is never edited to match the oracle (23:148). The other tests pin the grammar where
 * {@link GoldenCases} must read the file as the script does, and show that the runner does report a mismatch.
 */
class OracleGoldenTest {

    private static final GoldenCases GOLDENS = GoldenCases.load();

    private static final String GLYPHS = "glyphs synthetic-6x13 28:633\n";

    @TestFactory
    List<DynamicTest> everyGoldenCase() {
        List<DynamicTest> tests = new ArrayList<>();
        for (final GoldenCases.Case c : GOLDENS.cases()) {
            tests.add(DynamicTest.dynamicTest(c.name(), () -> {
                GoldenRunner.Result result = GoldenRunner.run(c);
                if (!result.passed()) {
                    System.out.println(result.report());
                    Assertions.fail(result.report(), result.cause());
                }
            }));
        }
        return tests;
    }

    @Test
    void theFileNamesTheSyntheticTable() {
        // amendment C (finding 17): a change to 28:633's rule must not silently invalidate the TEXT goldens
        Assertions.assertEquals(SyntheticGlyphs.ID, GOLDENS.glyphs());
        for (GoldenCases.Case c : GOLDENS.cases()) {
            Assertions.assertEquals(SyntheticGlyphs.ID, c.glyphs(), c.name());
        }
    }

    @Test
    void everyCaseIsReadAndSaysWhy() {
        // oracle_goldens.py prints "ALL PASS (46 cases)" for this file: a case the Java parser merged or dropped
        // would change the count. Add new cases to both counts.
        Assertions.assertEquals(
            46,
            GOLDENS.cases()
                .size());
        for (GoldenCases.Case c : GOLDENS.cases()) {
            Assertions
                .assertTrue(c.comments() > 0, c.name() + " has no '#' line saying why its grids are what they are");
        }
    }

    @Test
    void theFirstDirectiveIsMandatory() {
        assertFormatError(1, "case a\nsurface index8 1x1\nframe\n  segment\nexpect\n  00\nend\n");
        assertFormatError(2, "# comments may come first\nglyphs synthetic-8x16 28:633\n");
        assertFormatError(1, "glyphs synthetic-6x13\n");
        // and nothing but comments is no file
        assertFormatError(1, "# only a comment\n");
    }

    @Test
    void rowsAreReadBeforeComments() {
        // a chars row may begin with '#': inside a row block it is a row, outside one a comment
        GoldenCases.Case c = single(
            "case hash-rows\n# why\nsurface index8 2x2\nchars #=09 .=00\nframe\n  segment\n    fill 0 0 1 2 9\n"
                + "expect\n  #.\n# also a row, of two characters\n");
        Assertions.assertNull(c, "the second row must have failed");
    }

    @Test
    void hashRowsMatchTheirCharacters() {
        GoldenCases.Case c = GoldenCases
            .parse(
                GLYPHS + "case hash-rows\n# why\nsurface index8 2x2\nchars #=09 .=00\nframe\n  segment\n"
                    + "    fill 0 0 1 2 9\nexpect\n  #.\n  #.\nend\n")
            .byName("hash-rows");
        Assertions.assertEquals(1, c.comments());
        Assertions.assertArrayEquals(
            new int[] { 9, 0, 9, 0 },
            ((GoldenCases.Expect) c.steps()
                .get(1)).values());
        Assertions.assertTrue(
            GoldenRunner.run(c)
                .passed(),
            GoldenRunner.run(c)::report);
    }

    @Test
    void stringEscapesAreCodepoints() {
        // \" \\ \\uXXXX \\U00XXXXXX, literal UTF-8, and an escaped surrogate encoded alone (Python's surrogatepass)
        byte[] s = textString("\"\\u00FF\\U0001F600\\\"\\\\é\\uD800\"");
        Assertions.assertEquals("C3 BF F0 9F 98 80 22 5C C3 A9 ED A0 80", TestEncoder.toHex(s));
        assertFormatError(6, caseWithOp("text 0 0 9 2 0 0 \"\\x41\""));
        assertFormatError(6, caseWithOp("text 0 0 9 2 0 0 \"\\U00110000\""));
        assertFormatError(6, caseWithOp("text 0 0 9 2 0 0 \"\\u12\""));
        assertFormatError(6, caseWithOp("text 0 0 9 2 0 0 \"open"));
        // nothing may follow the string, a comment included
        assertFormatError(6, caseWithOp("text 0 0 9 2 0 0 \"A\" # why"));
    }

    @Test
    void anEscapedSurrogateIsRejectedByTheValidator() {
        GoldenCases.Case c = GoldenCases.parse(caseWithOp("text 0 0 9 2 0 0 \"\\uD800\"") + "expect\n  00\nend\n")
            .byName("op");
        GoldenRunner.Result result = GoldenRunner.run(c);
        Assertions.assertFalse(result.passed());
        Assertions.assertTrue(
            result.failures()
                .get(0)
                .contains("BAD_UTF8"),
            result::report);
    }

    @Test
    void numbersAreDecimalOrHex() {
        byte[] record = op("fill 0x1 -0x2 3 -4 0x05");
        Assertions.assertArrayEquals(TestEncoder.segment(TestEncoder.fill(1, -2, 3, -4, 5)), record);
        Assertions.assertArrayEquals(
            TestEncoder.segment(TestEncoder.blit(0x08000000, -32768, 32767, 0, 0, 0, 0, 0xFF)),
            op("blit 0x08000000 -32768 0x7FFF 0 0 0 0 255"));
        Assertions.assertArrayEquals(
            TestEncoder.segment(TestEncoder.bytes(0x01, 0x05, 0x00, 0x00, 0x00)),
            op("hex 01 0x05 00 0X0 00"));
        assertFormatError(6, caseWithOp("fill 0 0 1 1 +5"));
        assertFormatError(6, caseWithOp("fill 0 0 1 32768 5"));
        assertFormatError(6, caseWithOp("clear 0x100000000"));
        assertFormatError(6, caseWithOp("hex 1"));
    }

    @Test
    void thereAreNoTrailingComments() {
        assertFormatError(6, caseWithOp("clear 3 # three"));
    }

    @Test
    void misplacedDirectivesAreErrors() {
        assertFormatError(4, GLYPHS + "case a\nsurface index8 1x1\nclear 3\n");
        assertFormatError(4, GLYPHS + "case a\nsurface index8 1x1\nsegment\n");
        // chars after a frame directive: the frame is a step already
        assertFormatError(5, GLYPHS + "case a\nsurface index8 1x1\nframe\nchars .=00\n");
        assertFormatError(4, GLYPHS + "case a\nsurface index8 1x1\nsurface index8 1x1\n");
        assertFormatError(7, GLYPHS + "case a\nsurface index8 1x1\nexpect\n  00\nend\ncase a\n");
        assertFormatError(2, GLYPHS + "case A\n");
        assertFormatError(3, GLYPHS + "case a\nsurface index4 1x1\n");
        assertFormatError(4, GLYPHS + "case a\nsurface index8 1x1\nexpect\n");
        assertFormatError(2, GLYPHS + "case a\nsurface index8 1x1\n");
    }

    @Test
    void lineBreaksAreThoseOfSplitlines() {
        String unix = GLYPHS + "case a\n# why\nsurface rgb565 2x1\nframe\n  segment\n    clear 0xFFFF8000\n"
            + "totals records=1 clear=2 fill=0 copy=0 set=0\nexpect\n  FC00 FC00\nend\n";
        GoldenCases.Case c = GoldenCases.read(
            new ByteArrayInputStream(
                unix.replace("\n", "\r\n")
                    .getBytes(StandardCharsets.UTF_8)))
            .byName("a");
        Assertions.assertEquals(Formats.RGB565, c.format());
        Assertions.assertEquals(
            5,
            c.steps()
                .get(0)
                .line());
        Assertions.assertTrue(
            GoldenRunner.run(c)
                .passed(),
            GoldenRunner.run(c)::report);
        Assertions.assertThrows(
            GoldenCases.FormatException.class,
            () -> GoldenCases.read(new ByteArrayInputStream(new byte[] { 'g', (byte) 0xC0, (byte) 0x80 })));
    }

    @Test
    void aWrongGridIsReportedWithItsPixelsMarked() {
        GoldenCases.Case c = GoldenCases.parse(
            GLYPHS + "case wrong\n# (1,0) is deliberately wrong\nsurface index8 3x2\nframe\n  segment\n    clear 4\n"
                + "totals records=1 clear=6 fill=0 copy=0 set=0\nexpect\n  04 05 04\n  04 04 04\nend\n")
            .byName("wrong");
        GoldenRunner.Result result = GoldenRunner.run(c);
        Assertions.assertFalse(result.passed());
        String report = result.report();
        Assertions.assertTrue(report.contains("expect differs at 1 pixel(s)"), report);
        Assertions.assertTrue(report.contains("04 05 04"), report);
        Assertions.assertTrue(report.contains(".X."), report);
        Assertions.assertTrue(report.contains("(1,0): expected 05, actual 04"), report);
    }

    @Test
    void aWrongPixelInTheLastRowAndColumnIsReported() {
        GoldenCases.Case c = GoldenCases.parse(
            GLYPHS + "case corner\n# (2,1) is deliberately wrong\nsurface index8 3x2\nframe\n  segment\n    clear 4\n"
                + "totals records=1 clear=6 fill=0 copy=0 set=0\nexpect\n  04 04 04\n  04 04 05\nend\n")
            .byName("corner");
        GoldenRunner.Result result = GoldenRunner.run(c);
        Assertions.assertFalse(result.passed());
        String report = result.report();
        Assertions.assertTrue(report.contains("expect differs at 1 pixel(s)"), report);
        Assertions.assertTrue(report.contains("..X"), report);
        Assertions.assertTrue(report.contains("(2,1): expected 05, actual 04"), report);
    }

    @Test
    void eachWrongTotalIsReported() {
        // every class non-zero on a 3x2 surface: CLEAR 6 px, a 1x1 fill, a 1x1 blit, and a transparent blank (no bit
        // set, 28:633) of one cell, 78 px; the grid is the clear's 04 with the fill's 05 at (0,0) and the texel 07
        // at (1,1)
        String totals = "totals records=4 clear=6 fill=1 copy=1 set=78\n";
        String good = totalsCase(totals);
        Assertions.assertTrue(
            GoldenRunner.run(
                GoldenCases.parse(good)
                    .byName("totals"))
                .passed(),
            () -> GoldenRunner.run(
                GoldenCases.parse(good)
                    .byName("totals"))
                .report());
        String[] wrong = { "records=5", "clear=7", "fill=2", "copy=2", "set=79" };
        String[] right = { "records=4", "clear=6", "fill=1", "copy=1", "set=78" };
        for (int k = 0; k < wrong.length; k++) {
            GoldenCases.Case c = GoldenCases.parse(totalsCase(totals.replace(right[k], wrong[k])))
                .byName("totals");
            GoldenRunner.Result result = GoldenRunner.run(c);
            Assertions.assertEquals(
                1,
                result.failures()
                    .size(),
                wrong[k] + ": " + result.report());
            String failure = result.failures()
                .get(0);
            Assertions.assertTrue(failure.contains("totals differ"), wrong[k] + ": " + failure);
            Assertions.assertTrue(failure.contains(wrong[k]), wrong[k] + ": " + failure);
            Assertions.assertTrue(
                failure.contains("validator: totals records=4 clear=6 fill=1 copy=1 set=78"),
                wrong[k] + ": " + failure);
        }
    }

    @Test
    void wrongTotalsAndAMissingExpectAreReported() {
        GoldenCases.Case c = GoldenCases
            .parse(
                GLYPHS + "case totals\n# fill counts 4, not 5\nsurface index8 3x2\nframe\n  segment\n"
                    + "    fill 0 0 2 2 1\ntotals records=1 clear=0 fill=5 copy=0 set=0\nend\n")
            .byName("totals");
        GoldenRunner.Result result = GoldenRunner.run(c);
        Assertions.assertEquals(
            2,
            result.failures()
                .size(),
            result::report);
        Assertions.assertTrue(
            result.failures()
                .get(0)
                .contains("validator: totals records=1 clear=0 fill=4 copy=0 set=0"),
            result::report);
        Assertions.assertEquals(
            "the case has no expect block",
            result.failures()
                .get(1));
    }

    @Test
    void handlesFollowTheAllocator() {
        // 28 §6.1: a new device's first texture is 0x08000000, so the file may not name another
        GoldenCases.Case c = GoldenCases
            .parse(
                GLYPHS + "case handle\n# slot 1 is not next\nsurface index8 1x1\ntexture 0x08010000 1x1 zeros\n"
                    + "expect\n  00\nend\n")
            .byName("handle");
        GoldenRunner.Result result = GoldenRunner.run(c);
        Assertions.assertFalse(result.passed());
        Assertions.assertTrue(
            result.report()
                .contains("the allocator gives 0x08000000"),
            result::report);
        // and a freed handle is not live for an update (28 §6.3)
        GoldenCases.Case freed = GoldenCases
            .parse(
                GLYPHS + "case freed\n# why\nsurface index8 1x1\ntexture 0x08000000 1x1 zeros\nfree 0x08000000\n"
                    + "update 0x08000000 0 0 1x1\n  01\nexpect\n  00\nend\n")
            .byName("freed");
        Assertions.assertTrue(
            GoldenRunner.run(freed)
                .report()
                .contains("handle 0x08000000 is not live (STALE_HANDLE)"),
            GoldenRunner.run(freed)::report);
    }

    @Test
    void aRejectedSegmentFailsTheCaseWithItsMessage() {
        GoldenCases.Case c = GoldenCases.parse(caseWithOp("clear 0x100") + "expect\n  00\nend\n")
            .byName("op");
        GoldenRunner.Result result = GoldenRunner.run(c);
        Assertions.assertTrue(
            result.report()
                .contains("8: CLEAR: colour 0x00000100 is not an index 0-255 (BAD_COLOUR)"),
            result::report);
    }

    /** {@link #eachWrongTotalIsReported}'s case with the given totals line. */
    private static String totalsCase(String totals) {
        return GLYPHS + "case totals\n# why\nsurface index8 3x2\ntexture 0x08000000 1x1\n  07\nframe\n  segment\n"
            + "    clear 4\n    fill 0 0 1 1 5\n    blit 0x08000000 0 0 1 1 1 1 0\n    text 0 0 9 2 1 0 \" \"\n"
            + totals
            + "expect\n  05 04 04\n  04 07 04\nend\n";
    }

    /** A case whose line 6 is the given op line inside a segment, left open for the caller's expect. */
    private static String caseWithOp(String op) {
        return GLYPHS + "case op\nsurface index8 1x1\nframe\n  segment\n    " + op + "\n";
    }

    /** The segment one op line encodes to. */
    private static byte[] op(String op) {
        GoldenCases.Case c = GoldenCases.parse(caseWithOp(op) + "expect\n  00\nend\n")
            .byName("op");
        return ((GoldenCases.Frame) c.steps()
            .get(0)).segments()
                .get(0)
                .bytes();
    }

    /** A text op's string bytes, from the quoted form. */
    private static byte[] textString(String quoted) {
        byte[] s = op("text 0 0 9 2 0 0 " + quoted);
        int n = (s[8 + 15] & 0xFF) | (s[8 + 16] & 0xFF) << 8;
        byte[] string = new byte[n];
        System.arraycopy(s, 8 + 17, string, 0, n);
        return string;
    }

    /** The case parsed from text after the first directive, or null if parsing it failed. */
    private static GoldenCases.Case single(String text) {
        try {
            return GoldenCases.parse(GLYPHS + text + "end\n")
                .cases()
                .get(0);
        } catch (GoldenCases.FormatException e) {
            return null;
        }
    }

    private static void assertFormatError(int line, String text) {
        GoldenCases.FormatException e = Assertions
            .assertThrows(GoldenCases.FormatException.class, () -> GoldenCases.parse(text), text);
        Assertions.assertEquals(line, e.line(), e::getMessage);
    }
}
