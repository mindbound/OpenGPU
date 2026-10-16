package io.github.mindbound.opengpu.core.font;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Font 0 as {@code :core}'s jar ships it (28 §7.1; plan §3.14, amendments F), read through {@link GlyphTables#font0()}:
 * the whole face of misc-fixed 6x13 (28:632; 29 §7), its coverage, its own U+FFFD as the missing glyph, the header's
 * provenance (29:179), the body's pinned SHA-256, and five glyphs whose rows were copied by hand from
 * {@code 6x13.bdf}'s {@code BITMAP} lines, not from the converter.
 */
class Font0Test {

    /**
     * The table body's SHA-256 (n, the codepoints, the rows, the missing glyph; plan §3.14), as U2's converter
     * {@code BdfToGlyphTable} computes it from {@code 6x13.bdf}. The merge checks it against the digest that
     * {@code font0_table.py} prints for the same file (U3, written independently of the converter); the two must agree.
     */
    static final String BODY_SHA256 = "665917a7d7972c825d76b467641f5cab4f8ed9731be420eeae93ef96dd87afeb";

    /** {@code 6x13.bdf}'s SHA-256 (29:381). */
    private static final String SOURCE_SHA256 = "d00cf1cbaed055bb91d11c80e063d5d2d3b66552a7f2ead7161ee3780f9123f0";

    private static final int GLYPHS = 4121;

    @Test
    void theWholeFaceIsLoaded() {
        GlyphTable g = GlyphTables.font0();
        Assertions.assertEquals(6, g.cellWidth());
        Assertions.assertEquals(13, g.cellHeight());
        Assertions.assertEquals(GLYPHS, g.size());
        // U+0000 (STARTCHAR char0, DEFAULT_CHAR 0) stays: it is a scalar value (plan amendments F, H71)
        Assertions.assertEquals(0x0000, g.codepointAt(0));
        Assertions.assertEquals(0xFFFD, g.codepointAt(GLYPHS - 1));
        // The only control character in G; TEXT rejects it as CONTROL_CHAR (28:647)
        int controls = 0;
        for (int i = 0; i < g.size(); i++) {
            int cp = g.codepointAt(i);
            if (cp < 0x20 || cp >= 0x7F && cp <= 0x9F) {
                controls++;
            }
        }
        Assertions.assertEquals(1, controls);
        // Loaded once: the same object on every call
        Assertions.assertSame(g, GlyphTables.font0());
    }

    @Test
    void ufffdIsPresentAndIsTheMissingGlyph() {
        GlyphTable g = GlyphTables.font0();
        int fffd = g.indexOf(0xFFFD);
        Assertions.assertNotEquals(GlyphTable.MISSING, fffd);
        for (int r = 0; r < 13; r++) {
            Assertions.assertEquals(g.row(fffd, r), g.row(GlyphTable.MISSING, r), "row " + r);
        }
        // Codepoints the face lacks: CJK, an emoji, a noncharacter, the top of the range
        for (int cp : new int[] { 0x4E00, 0x1F600, 0xFFFE, 0x10FFFF, 0xE02C }) {
            Assertions.assertEquals(GlyphTable.MISSING, g.indexOf(cp), "U+" + Integer.toHexString(cp));
        }
    }

    @Test
    void noRowSetsABitOutsideTheCell() {
        GlyphTable g = GlyphTables.font0();
        for (int i = GlyphTable.MISSING; i < g.size(); i++) {
            for (int r = 0; r < 13; r++) {
                Assertions.assertEquals(0, g.row(i, r) & ~0xFC, "glyph " + i + " row " + r);
            }
        }
    }

    /**
     * 28:632's coverage, counted as {@code q1_font0.py:83-87} counts it: present and inked, the blanks U+0020 and
     * U+00A0 counted as inked.
     */
    @Test
    void theSubsetGreekAndCyrillicAreInked() {
        GlyphTable g = GlyphTables.font0();
        Assertions.assertEquals(351, inked(g, true, 0x20, 0x7E, 0xA0, 0xFF, 0x2500, 0x259F));
        // Inside the subset: box drawing 128/128 and blocks 32/32 (29 §7)
        Assertions.assertEquals(128, inked(g, false, 0x2500, 0x257F));
        Assertions.assertEquals(32, inked(g, false, 0x2580, 0x259F));
        // Greek letters 49/49: U+0391-03A1, U+03A3-03A9 (U+03A2 is unassigned), U+03B1-03C9
        Assertions.assertEquals(49, inked(g, false, 0x391, 0x3A1, 0x3A3, 0x3A9, 0x3B1, 0x3C9));
        Assertions.assertEquals(96, inked(g, false, 0x400, 0x45F));
    }

    @Test
    void privateUseGlyphsStartAtE000() {
        // 107 from U+E000 (28:1053), up to U+E06E, with U+E02C-E02F absent
        GlyphTable g = GlyphTables.font0();
        int count = 0;
        int first = -1;
        int last = -1;
        for (int i = 0; i < g.size(); i++) {
            int cp = g.codepointAt(i);
            if (cp >= 0xE000 && cp <= 0xF8FF) {
                count++;
                first = first < 0 ? cp : first;
                last = cp;
            }
        }
        Assertions.assertEquals(107, count);
        Assertions.assertEquals(0xE000, first);
        Assertions.assertEquals(0xE06E, last);
    }

    @Test
    void theBodyHasThePinnedSha256() throws IOException {
        byte[] table = shipped();
        int bodyStart = -1;
        for (int i = 1; i < table.length && bodyStart < 0; i++) {
            if (table[i] == '\n' && table[i - 1] == '\n') {
                bodyStart = i + 1;
            }
        }
        Assertions.assertTrue(bodyStart > 0, "no empty line");
        byte[] body = Arrays.copyOfRange(table, bodyStart, table.length - 4);
        // u32 n, n u32 codepoints, n × 13 rows, the missing glyph's 13 (plan §3.14)
        Assertions.assertEquals(4 + 4 * GLYPHS + 13 * GLYPHS + 13, body.length);
        Assertions.assertEquals(BODY_SHA256, sha256(body));
    }

    @Test
    void theHeaderNamesTheSourceAndItsTerms() throws IOException {
        Map<String, String> header;
        try (InputStream in = GlyphTables.class.getResourceAsStream(GlyphTables.FONT0_RESOURCE)) {
            Assertions.assertNotNull(in, GlyphTables.FONT0_RESOURCE);
            header = GlyphTables.header(in, GlyphTables.FONT0_RESOURCE);
        }
        Assertions.assertEquals("0", header.get("font"));
        Assertions.assertEquals("6x13", header.get("cell"));
        Assertions.assertEquals("4121", header.get("glyphs"));
        Assertions.assertEquals("U+FFFD", header.get("missing"));
        Assertions.assertEquals("6x13.bdf", header.get("source"));
        Assertions.assertEquals(SOURCE_SHA256, header.get("source-sha256"));
        // 6x13.bdf:2, verbatim (28:631: $Id v1.115, 2009-04-06)
        Assertions.assertEquals("$Id: 6x13.bdf,v 1.115 2009-04-06 18:50:15+01 mgk25 Rel $", header.get("source-id"));
        // 6x13.bdf:26's COPYRIGHT, byte for byte: two spaces after the full stop (plan amendments F)
        Assertions.assertEquals("Public domain font.  Share and enjoy.", header.get("copyright"));
        Assertions.assertEquals("public domain; credited in OpenGPU's NOTICE", header.get("licence"));
    }

    /**
     * Rows copied by hand from {@code 6x13.bdf}'s {@code BITMAP} lines. Each of these glyphs has {@code BBX 6 13 0 -2},
     * so bitmap row i is cell row 11 - (-2 + 13) + i = i, unshifted (plan §3.14), and each line's two hex digits are
     * the row byte as written.
     */
    @Test
    void handCopiedGlyphsMatch() {
        GlyphTable g = GlyphTables.font0();
        // STARTCHAR A, ENCODING 65: 6x13.bdf:712-731
        assertGlyph(g, 0x41, 0x00, 0x00, 0x20, 0x50, 0x88, 0x88, 0x88, 0xF8, 0x88, 0x88, 0x88, 0x00, 0x00);
        // STARTCHAR g, ENCODING 103: 6x13.bdf:1472-1491; the descender fills rows 11 and 12
        assertGlyph(g, 0x67, 0x00, 0x00, 0x00, 0x00, 0x00, 0x70, 0x88, 0x88, 0x88, 0x78, 0x08, 0x88, 0x70);
        // STARTCHAR block, ENCODING 9608 (U+2588 FULL BLOCK): 6x13.bdf:62612-62631, every row 0xFC
        assertGlyph(g, 0x2588, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC, 0xFC);
        // STARTCHAR afii10024, ENCODING 1046 (U+0416 CYRILLIC CAPITAL LETTER ZHE): 6x13.bdf:17812-17831
        assertGlyph(g, 0x0416, 0x00, 0x00, 0xA8, 0xA8, 0xA8, 0x70, 0x20, 0x70, 0xA8, 0xA8, 0xA8, 0x00, 0x00);
        // STARTCHAR uniFFFD, ENCODING 65533: 6x13.bdf:82432-82451
        assertGlyph(g, 0xFFFD, 0x00, 0x00, 0x70, 0xD8, 0xA8, 0xE8, 0xD8, 0xD8, 0xF8, 0xD8, 0x70, 0x00, 0x00);
        // STARTCHAR char0, ENCODING 0: 6x13.bdf:32-51, the dotted box of DEFAULT_CHAR
        assertGlyph(g, 0x0000, 0x00, 0x00, 0xA8, 0x00, 0x88, 0x00, 0x88, 0x00, 0x88, 0x00, 0xA8, 0x00, 0x00);
        // And bit by bit for 'A' row 7 (0xF8): columns 0-4 set, column 5 clear
        int a = g.indexOf(0x41);
        for (int b = 0; b < 6; b++) {
            Assertions.assertEquals(b < 5, g.bit(a, 7, b), "column " + b);
        }
    }

    /** The number of codepoints in the inclusive ranges that have a glyph with a set bit, or are the two blanks. */
    private static int inked(GlyphTable g, boolean blanksCount, int... ranges) {
        int count = 0;
        for (int k = 0; k < ranges.length; k += 2) {
            for (int cp = ranges[k]; cp <= ranges[k + 1]; cp++) {
                int index = g.indexOf(cp);
                if (index == GlyphTable.MISSING) {
                    continue;
                }
                boolean ink = blanksCount && (cp == 0x20 || cp == 0xA0);
                for (int r = 0; r < 13 && !ink; r++) {
                    ink = g.row(index, r) != 0;
                }
                if (ink) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void assertGlyph(GlyphTable g, int cp, int... rows) {
        int index = g.indexOf(cp);
        Assertions.assertNotEquals(GlyphTable.MISSING, index, "U+" + Integer.toHexString(cp));
        for (int r = 0; r < 13; r++) {
            Assertions.assertEquals(rows[r], g.row(index, r), String.format(Locale.ROOT, "U+%04X row %d", cp, r));
        }
    }

    private static byte[] shipped() throws IOException {
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

    private static String sha256(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(data);
            StringBuilder out = new StringBuilder();
            for (byte b : hash) {
                out.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
