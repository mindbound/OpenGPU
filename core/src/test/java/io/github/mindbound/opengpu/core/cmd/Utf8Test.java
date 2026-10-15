package io.github.mindbound.opengpu.core.cmd;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.fixtures.TestEncoder;

/**
 * {@code Utf8.scan}, the one left-to-right scan of 28 §7.2, cross-checked exhaustively against the JDK's own UTF-8
 * decoder with malformed input reported: Java 8's decoder implements the Unicode Standard's Table 3-7 and reports a
 * malformed sequence at its first byte, the index rule of 28 §3.6 row 21 as the S6 plan resolves it (its gap G3). The
 * expected result of a string is the JDK's: the first control codepoint (U+0000-001F, U+007F-009F) of the decoded
 * prefix decides if one comes before the malformed position, otherwise {@code BAD_UTF8} at that position, otherwise
 * the codepoint count. The agreement holds on Java 8, the test runtime; recheck it if the tests ever run on a newer
 * JDK.
 */
class Utf8Test {

    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    private final CharBuffer chars = CharBuffer.allocate(128);
    private final List<String> failures = new ArrayList<>();

    @Test
    void everyOneAndTwoByteString() {
        byte[] b = new byte[2];
        for (int b0 = 0; b0 < 256; b0++) {
            b[0] = (byte) b0;
            check(b, 1);
            for (int b1 = 0; b1 < 256; b1++) {
                b[1] = (byte) b1;
                check(b, 2);
            }
        }
        assertNoFailures(256 + 65536);
    }

    @Test
    void everyThreeByteStringWithALeadInE0ToF4() {
        // F0-F4 included: a 4-byte lead cut at the array's end, where a read past the range would throw
        byte[] b = new byte[3];
        for (int b0 = 0xE0; b0 <= 0xF4; b0++) {
            b[0] = (byte) b0;
            for (int b1 = 0; b1 < 256; b1++) {
                b[1] = (byte) b1;
                for (int b2 = 0; b2 < 256; b2++) {
                    b[2] = (byte) b2;
                    check(b, 3);
                }
            }
        }
        assertNoFailures(21 * 65536);
    }

    @Test
    void fourByteStringsWithALeadInF0ToF4() {
        // Every second byte, and third and fourth bytes at the edges of the continuation range
        int[] edges = { 0x7F, 0x80, 0xBF, 0xC0 };
        byte[] b = new byte[4];
        int count = 0;
        for (int b0 = 0xF0; b0 <= 0xF4; b0++) {
            b[0] = (byte) b0;
            for (int b1 = 0; b1 < 256; b1++) {
                b[1] = (byte) b1;
                for (int b2 : edges) {
                    b[2] = (byte) b2;
                    for (int b3 : edges) {
                        b[3] = (byte) b3;
                        check(b, 4);
                        count++;
                    }
                }
            }
        }
        Assertions.assertEquals(20480, count);
        assertNoFailures(count);
    }

    @Test
    void longerStringsMixingEveryClass() {
        // Seeded: well-formed pieces of every length with a control or an ill-formed piece now and then
        SplittableRandom r = new SplittableRandom(28);
        String[] pieces = { "41", "7E", "20", "C2 A0", "DF BF", "E0 A0 80", "E2 82 AC", "ED 9F BF", "EF BF BF",
            "F0 90 80 80", "F4 8F BF BF", "0A", "C2 85", "7F", "80", "C0 80", "ED A0 80", "F4 90 80 80", "E2 82",
            "FF" };
        byte[][] bytes = new byte[pieces.length][];
        for (int i = 0; i < pieces.length; i++) {
            bytes[i] = TestEncoder.hex(pieces[i]);
        }
        byte[] buffer = new byte[64];
        for (int n = 0; n < 20000; n++) {
            int length = 0;
            int count = r.nextInt(1, 12);
            for (int k = 0; k < count; k++) {
                // Mostly the eleven well-formed, non-control pieces
                byte[] piece = bytes[r.nextInt(10) < 8 ? r.nextInt(11) : r.nextInt(bytes.length)];
                System.arraycopy(piece, 0, buffer, length, piece.length);
                length += piece.length;
            }
            check(buffer, length);
        }
        assertNoFailures(20000);
    }

    @Test
    void theRangeIsCutByItsLengthNotByTheArray() {
        byte[] euro = TestEncoder.hex("E2 82 AC");
        assertBad(Utf8.scan(euro, 0, 2), 0);
        assertBad(Utf8.scan(euro, 0, 1), 0);
        Assertions.assertEquals(1, Utf8.scan(euro, 0, 3));
        Assertions.assertEquals(0, Utf8.scan(euro, 1, 0));
        // An offset: indexes are relative to from
        byte[] s = TestEncoder.hex("FF FF 41 42 C0 80 FF");
        assertBad(Utf8.scan(s, 2, 4), 2);
        Assertions.assertEquals(2, Utf8.scan(s, 2, 2));
        assertBad(Utf8.scan(s, 0, 7), 0);
        assertBad(Utf8.scan(s, 6, 1), 0);
    }

    @Test
    void theHelpersDecodeEveryResult() {
        // S6 plan amendment C (finding 9): C2 9F at string index 1 022, the last a 2-byte sequence can start at
        byte[] s = new byte[1024];
        Arrays.fill(s, (byte) 'A');
        s[1022] = (byte) 0xC2;
        s[1023] = (byte) 0x9F;
        long control = Utf8.scan(s, 0, 1024);
        Assertions.assertTrue(control < 0);
        Assertions.assertTrue(Utf8.isControlChar(control));
        Assertions.assertFalse(Utf8.isBadUtf8(control));
        Assertions.assertEquals(1022, Utf8.errorIndex(control));
        Assertions.assertEquals(0x9F, Utf8.errorCodepoint(control));
        // U+0000 at 1 023
        s[1022] = 'A';
        s[1023] = 0;
        control = Utf8.scan(s, 0, 1024);
        Assertions.assertTrue(Utf8.isControlChar(control));
        Assertions.assertEquals(1023, Utf8.errorIndex(control));
        Assertions.assertEquals(0, Utf8.errorCodepoint(control));
        // A cut sequence at 1 023
        s[1023] = (byte) 0xC2;
        long bad = Utf8.scan(s, 0, 1024);
        Assertions.assertTrue(Utf8.isBadUtf8(bad));
        Assertions.assertFalse(Utf8.isControlChar(bad));
        Assertions.assertEquals(1023, Utf8.errorIndex(bad));
        Assertions.assertEquals(-1, Utf8.errorCodepoint(bad));
        // A count is no error
        s[1023] = 'A';
        long count = Utf8.scan(s, 0, 1024);
        Assertions.assertEquals(1024, count);
        Assertions.assertFalse(Utf8.isBadUtf8(count));
        Assertions.assertFalse(Utf8.isControlChar(count));
        Assertions.assertEquals(-1, Utf8.errorIndex(count));
        Assertions.assertEquals(-1, Utf8.errorCodepoint(count));
        // 1 024 bytes of 4-byte sequences are 256 codepoints
        byte[] emoji = new byte[1024];
        for (int i = 0; i < 1024; i += 4) {
            System.arraycopy(TestEncoder.hex("F0 9F 98 80"), 0, emoji, i, 4);
        }
        Assertions.assertEquals(256, Utf8.scan(emoji, 0, 1024));
        Assertions.assertEquals(0, Utf8.scan(new byte[0], 0, 0));
    }

    @Test
    void isControlIsC0DelAndC1() {
        for (int cp = -1; cp <= 0x10FFFF; cp++) {
            boolean expected = cp >= 0 && cp <= 0x1F || cp >= 0x7F && cp <= 0x9F;
            if (Utf8.isControl(cp) != expected) {
                Assertions.fail(String.format(Locale.ROOT, "isControl(U+%04X)", cp));
            }
        }
    }

    @Test
    void codepointAtAndSequenceLengthDecodeEveryTable37Row() {
        // Table 3-7's rows at their ends: (first codepoint, last codepoint) and their sequences
        String[][] rows = { { "00", "7F" }, { "C2 80", "DF BF" }, { "E0 A0 80", "E0 BF BF" },
            { "E1 80 80", "EC BF BF" }, { "ED 80 80", "ED 9F BF" }, { "EE 80 80", "EF BF BF" },
            { "F0 90 80 80", "F0 BF BF BF" }, { "F1 80 80 80", "F3 BF BF BF" }, { "F4 80 80 80", "F4 8F BF BF" } };
        int[][] codepoints = { { 0x0, 0x7F }, { 0x80, 0x7FF }, { 0x800, 0xFFF }, { 0x1000, 0xCFFF }, { 0xD000, 0xD7FF },
            { 0xE000, 0xFFFF }, { 0x10000, 0x3FFFF }, { 0x40000, 0xFFFFF }, { 0x100000, 0x10FFFF } };
        for (int i = 0; i < rows.length; i++) {
            for (int end = 0; end < 2; end++) {
                byte[] seq = TestEncoder.hex(rows[i][end]);
                byte[] padded = new byte[seq.length + 2];
                System.arraycopy(seq, 0, padded, 1, seq.length);
                Assertions.assertEquals(codepoints[i][end], Utf8.codepointAt(padded, 1), rows[i][end]);
                Assertions.assertEquals(seq.length, Utf8.sequenceLength(seq[0]), rows[i][end]);
                Assertions.assertEquals(seq.length, Utf8.sequenceLength(seq[0] & 0xFF), rows[i][end]);
            }
        }
        // Every scalar value: its JDK encoding decodes to it
        for (int cp = 0; cp <= 0x10FFFF; cp += cp < 0x800 ? 1 : 7) {
            if (cp >= 0xD800 && cp <= 0xDFFF) {
                continue;
            }
            byte[] seq = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            Assertions.assertEquals(cp, Utf8.codepointAt(seq, 0));
            Assertions.assertEquals(seq.length, Utf8.sequenceLength(seq[0]));
        }
        // No well-formed sequence starts with 80-C1 or F5-FF
        for (int b = 0; b < 256; b++) {
            boolean leads = b < 0x80 || b >= 0xC2 && b <= 0xF4;
            if (leads) {
                Utf8.sequenceLength(b);
            } else {
                int lead = b;
                Assertions.assertThrows(IllegalArgumentException.class, () -> Utf8.sequenceLength(lead), "" + b);
            }
        }
    }

    /** Compares Utf8.scan of b[0, length) with the JDK's verdict; mismatches are collected. */
    private void check(byte[] b, int length) {
        long actual = Utf8.scan(b, 0, length);
        long[] expected = jdk(b, length);
        boolean ok;
        if (expected[0] == 0) {
            ok = actual == expected[1];
        } else if (expected[0] == 1) {
            ok = Utf8.isBadUtf8(actual) && Utf8.errorIndex(actual) == expected[1];
        } else {
            ok = Utf8.isControlChar(actual) && Utf8.errorIndex(actual) == expected[1]
                && Utf8.errorCodepoint(actual) == expected[2];
        }
        if (!ok && failures.size() < 20) {
            failures.add(
                String.format(
                    Locale.ROOT,
                    "%s: expected %s, scan %d (bad %b, control %b, index %d, codepoint %d)",
                    TestEncoder.toHex(Arrays.copyOf(b, length)),
                    Arrays.toString(expected),
                    actual,
                    Utf8.isBadUtf8(actual),
                    Utf8.isControlChar(actual),
                    Utf8.errorIndex(actual),
                    Utf8.errorCodepoint(actual)));
        }
    }

    /**
     * The JDK's verdict on b[0, length): {0, count}, {1, malformed index} or {2, control index, codepoint}, a control
     * of the well-formed prefix deciding when it comes first.
     */
    private long[] jdk(byte[] b, int length) {
        decoder.reset();
        chars.clear();
        ByteBuffer in = ByteBuffer.wrap(b, 0, length);
        CoderResult result = decoder.decode(in, chars, true);
        int malformed = -1;
        if (result.isError()) {
            malformed = in.position();
        } else {
            decoder.flush(chars);
        }
        chars.flip();
        int index = 0;
        long count = 0;
        while (chars.hasRemaining()) {
            char c = chars.get();
            int cp = Character.isHighSurrogate(c) ? Character.toCodePoint(c, chars.get()) : c;
            if (cp <= 0x1F || cp >= 0x7F && cp <= 0x9F) {
                return new long[] { 2, index, cp };
            }
            index += cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            count++;
        }
        return malformed >= 0 ? new long[] { 1, malformed } : new long[] { 0, count };
    }

    private void assertNoFailures(int checked) {
        Assertions.assertTrue(failures.isEmpty(), () -> checked + " strings, mismatches: " + failures);
    }

    private static void assertBad(long result, int index) {
        Assertions.assertTrue(Utf8.isBadUtf8(result), "result " + result);
        Assertions.assertEquals(index, Utf8.errorIndex(result));
    }
}
