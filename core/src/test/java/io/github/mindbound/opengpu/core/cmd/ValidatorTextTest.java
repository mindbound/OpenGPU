package io.github.mindbound.opengpu.core.cmd;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.bytes;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.concat;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.header;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.text;

import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.mindbound.opengpu.core.fixtures.MapLiveView;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;

/**
 * 28 §11.2's {@code TEXT} strings (28 §7.2; §3.6 row 21): ill-formed UTF-8 by Table 3-7 is {@code BAD_UTF8} and the
 * C0 and C1 controls are {@code CONTROL_CHAR}, both from one left-to-right scan in which the first offending sequence
 * decides, with the string byte index of its first byte as the detail. Through both validators, the TEXT at 8.
 */
class ValidatorTextTest {

    static Stream<Arguments> illFormed() {
        return Stream.of(
            // Overlong forms
            Arguments.of("C0 80", 0),
            Arguments.of("C1 BF", 0),
            Arguments.of("E0 80 80", 0),
            Arguments.of("E0 9F BF", 0),
            Arguments.of("F0 8F BF BF", 0),
            Arguments.of("F0 80 80 80", 0),
            // Surrogates
            Arguments.of("ED A0 80", 0),
            Arguments.of("ED BF BF", 0),
            Arguments.of("ED B0 80", 0),
            // Beyond U+10FFFF
            Arguments.of("F4 90 80 80", 0),
            Arguments.of("F5 80 80 80", 0),
            Arguments.of("F7 BF BF BF", 0),
            Arguments.of("F8 88 80 80 80", 0),
            Arguments.of("FF", 0),
            Arguments.of("FE", 0),
            // Cut off by the string's end
            Arguments.of("C2", 0),
            Arguments.of("E2 82", 0),
            Arguments.of("F0 9F 98", 0),
            Arguments.of("41 42 E2 82", 2),
            // Stray continuation bytes
            Arguments.of("80", 0),
            Arguments.of("BF", 0),
            Arguments.of("41 80", 1),
            Arguments.of("E2 82 AC 80", 3),
            // A continuation byte out of its range
            Arguments.of("E2 28 A1", 0),
            Arguments.of("C2 41", 0),
            Arguments.of("C2 C2 80", 0),
            Arguments.of("F0 9F 98 41", 0),
            Arguments.of("48 69 21 E0 A0 C0", 3),
            // The first offending sequence after well-formed ones of every length
            Arguments.of("41 C3 A9 E2 82 AC F0 9F 98 80 C0 80", 10));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("illFormed")
    void illFormedUtf8IsBadUtf8AtItsFirstByte(String hex, int index) {
        for (int tier = 2; tier <= 3; tier++) {
            MapLiveView view = Expectations.tier(tier);
            StreamError e = Expectations.assertRejected(textSegment(tier, hex), view, ErrorCode.BAD_UTF8, 8, "TEXT");
            Assertions.assertEquals(index, e.detail, hex);
            Assertions.assertEquals("invalid UTF-8 at string byte " + index, e.reason);
        }
    }

    static Stream<Arguments> controls() {
        return Stream.of(
            Arguments.of("00", 0, 0x00),
            Arguments.of("0A", 0, 0x0A),
            Arguments.of("09", 0, 0x09),
            Arguments.of("1F", 0, 0x1F),
            Arguments.of("7F", 0, 0x7F),
            Arguments.of("C2 80", 0, 0x80),
            Arguments.of("C2 85", 0, 0x85),
            Arguments.of("C2 9F", 0, 0x9F),
            Arguments.of("61 0A 62", 1, 0x0A),
            Arguments.of("E2 82 AC 0D", 3, 0x0D));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("controls")
    void c0AndC1ControlsAreControlChar(String hex, int index, int codepoint) {
        MapLiveView view = Expectations.t2();
        StreamError e = Expectations.assertRejected(textSegment(2, hex), view, ErrorCode.CONTROL_CHAR, 8, "TEXT");
        Assertions.assertEquals(index, e.detail);
        Assertions.assertEquals(
            String.format(Locale.ROOT, "control character U+%04X at string byte %d", codepoint, index),
            e.reason);
    }

    @Test
    void theFirstOffendingSequenceDecides() {
        MapLiveView view = Expectations.t2();
        // A control first, then an overlong
        StreamError control = Expectations
            .assertRejected(textSegment(2, "0A C0 80"), view, ErrorCode.CONTROL_CHAR, 8, "TEXT");
        Assertions.assertEquals(0, control.detail);
        // An overlong first, then a control
        StreamError bad = Expectations.assertRejected(textSegment(2, "C0 80 0A"), view, ErrorCode.BAD_UTF8, 8, "TEXT");
        Assertions.assertEquals(0, bad.detail);
        // A truncated sequence whose next byte is a control: the sequence is ill-formed at its lead
        StreamError cut = Expectations.assertRejected(textSegment(2, "41 E2 0A"), view, ErrorCode.BAD_UTF8, 8, "TEXT");
        Assertions.assertEquals(1, cut.detail);
    }

    @Test
    void aSequenceIsCutByTheStringsEndNotTheSegments() {
        // n = 1, "E2", then 82 AC in the segment: the string is ill-formed before the next record is read
        byte[] s = concat(header(), text(0, 0, 1, 0, 0, 0, bytes(0xE2)), bytes(0x82, 0xAC, 0xFF));
        StreamError e = Expectations.assertRejected(s, Expectations.t2(), ErrorCode.BAD_UTF8, 8, "TEXT");
        Assertions.assertEquals(0, e.detail);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = { "F0 9F 98", "F4 8F BF", "C2", "E2 82" })
    void aSequenceCutByTheSegmentsLastByteIsBadUtf8(String hex) {
        // No END: the string's last byte is the segment's, so a scan that read one byte past the string would leave
        // the array; the sequence is ill-formed at its lead before MISSING_END is reached
        for (int tier = 2; tier <= 3; tier++) {
            int fg = tier == 2 ? 15 : 0xFFFFFFFF;
            int bg = tier == 2 ? 0 : 0xFF000000;
            byte[] s = concat(header(), text(3, 4, fg, bg, 0, 0, TestEncoder.hex(hex)));
            StreamError e = Expectations.assertRejected(s, Expectations.tier(tier), ErrorCode.BAD_UTF8, 8, "TEXT");
            Assertions.assertEquals(0, e.detail, hex);
        }
    }

    static Stream<Arguments> accepted() {
        return Stream.of(
            Arguments.of("", 0),
            Arguments.of("20", 1),
            Arguments.of("C2 A0", 1),
            Arguments.of("7E", 1),
            Arguments.of("C2 A1", 1),
            Arguments.of("DF BF", 1),
            Arguments.of("E0 A0 80", 1),
            Arguments.of("ED 9F BF", 1),
            Arguments.of("EE 80 80", 1),
            // Noncharacters and U+FEFF are well-formed and not controls
            Arguments.of("EF BF BE", 1),
            Arguments.of("EF BF BF", 1),
            Arguments.of("EF B7 90", 1),
            Arguments.of("EF BB BF", 1),
            Arguments.of("F0 90 80 80", 1),
            Arguments.of("F4 8F BF BF", 1),
            Arguments.of("F3 BF BF BF", 1),
            Arguments.of("48 69 C3 A9 E2 82 AC F0 9F 98 80", 5));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("accepted")
    void wellFormedStringsAreAcceptedAt78PxPerCodepoint(String hex, int codepoints) {
        for (int tier = 2; tier <= 3; tier++) {
            byte[] s = textSegment(tier, hex);
            Expectations
                .assertAccepted(s, Expectations.tier(tier), new FrameTotals(s.length, 1, 0, 0, 0, 78L * codepoints));
        }
    }

    @Test
    void theStringIsCheckedLastOfTextsFields() {
        MapLiveView view = Expectations.t2();
        // A bad font before bad UTF-8, bad flags before a bad font, a bad colour before bad flags (28 §3.6:324)
        Expectations.assertRejected(
            segment(text(0, 0, 1, 0, 0, 1, bytes(0xC0, 0x80))),
            view,
            ErrorCode.UNKNOWN_FONT,
            8,
            "TEXT");
        Expectations.assertRejected(
            segment(text(0, 0, 1, 0, 0x02, 1, bytes(0xC0, 0x80))),
            view,
            ErrorCode.BAD_FLAGS_FIELD,
            8,
            "TEXT");
        Expectations
            .assertRejected(segment(text(0, 0, 1, 0x100, 0x02, 1, bytes(0x0A))), view, ErrorCode.BAD_COLOUR, 8, "TEXT");
    }

    /** One TEXT at 8 with the string's bytes, colours valid for the tier's format. */
    private static byte[] textSegment(int tier, String hex) {
        int fg = tier == 2 ? 15 : 0xFFFFFFFF;
        int bg = tier == 2 ? 0 : 0xFF000000;
        return segment(text(3, 4, fg, bg, 0, 0, TestEncoder.hex(hex)));
    }
}
