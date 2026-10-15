package io.github.mindbound.opengpu.core.cmd;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.bytes;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.concat;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.head;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.header;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.text;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.withLe16;

import java.util.Arrays;
import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.mindbound.opengpu.core.fixtures.MapLiveView;

/**
 * 28 §11.2's framing cases (28 §3.2; §3.6 rows 8-13): {@code END} and what follows it, unassigned opcodes, every M0
 * record cut at every byte, and {@code TEXT}'s count, through both validators.
 */
class ValidatorFramingTest {

    private static final byte[] END = bytes(0xFF);

    @Test
    void anyByteAfterEndIsTrailingBytesAtTheEnd() {
        MapLiveView view = Expectations.t2();
        for (int value = 0; value < 256; value++) {
            StreamError e = Expectations
                .assertRejected(concat(header(), END, bytes(value)), view, ErrorCode.TRAILING_BYTES, 8, "END");
            Assertions.assertEquals(1, e.detail);
        }
        // After a record, the END's offset
        Expectations.assertRejected(concat(header(), clear(7), END, END), view, ErrorCode.TRAILING_BYTES, 13, "END");
        // A second whole segment after the first
        Expectations
            .assertRejected(concat(segment(clear(7)), segment(clear(7))), view, ErrorCode.TRAILING_BYTES, 13, "END");
    }

    @ParameterizedTest(name = "0x{0}")
    @ValueSource(strings = { "00", "02", "32", "40", "FE" })
    void the28UnassignedOpcodesAreUnknown(String hex) {
        int op = Integer.parseInt(hex, 16);
        Expectations.assertRejected(
            concat(header(), bytes(op), END),
            Expectations.t2(),
            ErrorCode.UNKNOWN_OPCODE,
            8,
            "0x" + hex);
    }

    @Test
    void every251UnassignedOpcodeIsUnknownAndNamedInUpperCaseHex() {
        MapLiveView view = Expectations.t2();
        int count = 0;
        for (int op = 0; op < 256; op++) {
            if (Arrays.asList(0x01, 0x30, 0x31, 0x33, 0xFF)
                .contains(op)) {
                continue;
            }
            count++;
            String token = String.format(Locale.ROOT, "0x%02X", op);
            StreamError e = Expectations
                .assertRejected(concat(header(), bytes(op), END), view, ErrorCode.UNKNOWN_OPCODE, 8, token);
            Assertions.assertEquals(op, e.opcode);
            // After a valid record: the unknown one's offset
            Expectations
                .assertRejected(concat(header(), clear(1), bytes(op), END), view, ErrorCode.UNKNOWN_OPCODE, 13, token);
        }
        Assertions.assertEquals(251, count);
    }

    @Test
    void aZeroFilledBufferFailsAtItsFirstRecord() {
        // 0x00 is never assigned (28 §3.2, C2)
        byte[] s = concat(header(), new byte[100]);
        Expectations.assertRejected(s, Expectations.t2(), ErrorCode.UNKNOWN_OPCODE, 8, "0x00");
    }

    @Test
    void everyRecordCutAtEveryByteAgreesWithTheNaiveValidator() {
        // 28 §11.2: every M0 record cut after c bytes, with and without a following END, on both formats
        for (int tier = 2; tier <= 3; tier++) {
            MapLiveView view = Expectations.tier(tier);
            int colour = tier == 2 ? 5 : 0xFF000005;
            byte[][] records = { clear(colour), fill(1, 2, 3, 4, colour),
                blit(Expectations.TEXTURE, 0, 0, 4, 4, 1, 1, 1), text(1, 2, colour, colour, 0, 0, "ab") };
            for (byte[] record : records) {
                for (int c = 0; c <= record.length; c++) {
                    byte[] cut = head(record, c);
                    Validation bare = Expectations.assertAgree(concat(header(), cut), view);
                    Validation ended = Expectations.assertAgree(concat(header(), cut, END), view);
                    Assertions.assertFalse(bare.isAccepted(), bare::toString);
                    if (c == 0 || c == record.length) {
                        // No record, or a whole one: END is where it belongs
                        Assertions.assertTrue(ended.isAccepted(), ended::toString);
                        Assertions.assertEquals(ErrorCode.MISSING_END, bare.error().code);
                    } else {
                        // A cut record takes the END in or does not fit
                        Assertions.assertFalse(ended.isAccepted(), ended::toString);
                    }
                }
            }
        }
    }

    @Test
    void aCutRecordCanSwallowTheEnd() {
        // 28 §11.2 [M]: FILL_RECT one byte short followed by END reads 0xFF as its colour's top byte. With colour 5
        // (index8) or 0xFF000005 (rgb565) that is 0xFF000005: BAD_COLOUR on index8, then MISSING_END on rgb565
        byte[] index8 = concat(header(), head(fill(1, 2, 3, 4, 5), 12), END);
        byte[] rgb565 = concat(header(), head(fill(1, 2, 3, 4, 0xFF000005), 12), END);
        Assertions.assertEquals(21, index8.length);
        StreamError e = Expectations.assertRejected(index8, Expectations.t2(), ErrorCode.BAD_COLOUR, 8, "FILL_RECT");
        Assertions.assertEquals(0xFF000005L, e.detail);
        Expectations.assertRejected(rgb565, Expectations.t3(), ErrorCode.MISSING_END, 21, "END");
        // Two bytes short: the fixed part does not fit
        byte[] shorter = concat(header(), head(fill(1, 2, 3, 4, 5), 11), END);
        StreamError cut = Expectations
            .assertRejected(shorter, Expectations.t2(), ErrorCode.TRUNCATED_RECORD, 8, "FILL_RECT");
        Assertions.assertEquals(13, cut.detail);
    }

    @Test
    void eachFixedPartMustFit() {
        MapLiveView view = Expectations.t2();
        Expectations.assertRejected(concat(header(), bytes(0x01), END), view, ErrorCode.TRUNCATED_RECORD, 8, "CLEAR");
        Expectations.assertRejected(concat(header(), bytes(0x30)), view, ErrorCode.TRUNCATED_RECORD, 8, "FILL_RECT");
        Expectations.assertRejected(
            concat(header(), head(blit(Expectations.TEXTURE, 0, 0, 1, 1, 0, 0, 0), 17)),
            view,
            ErrorCode.TRUNCATED_RECORD,
            8,
            "BLIT");
        // TEXT's fixed part cut before its count: the count is never read
        Expectations.assertRejected(
            concat(header(), head(text(0, 0, 1, 0, 0, 0, ""), 16)),
            view,
            ErrorCode.TRUNCATED_RECORD,
            8,
            "TEXT");
        // A later record decides at its own offset
        Expectations.assertRejected(
            concat(header(), clear(1), fill(0, 0, 1, 1, 1), bytes(0x31, 0, 0)),
            view,
            ErrorCode.TRUNCATED_RECORD,
            26,
            "BLIT");
    }

    @Test
    void textsCountIsCheckedBeforeItsTailAndItsTailBeforeItsFields() {
        MapLiveView view = Expectations.t2();
        // n past the end: TRUNCATED_RECORD, even with a bad fg (rows 13 before 14)
        byte[] badFgShort = concat(header(), withLe16(text(0, 0, 0x100, 0, 0, 0, "abc"), 15, 40), END);
        StreamError cut = Expectations.assertRejected(badFgShort, view, ErrorCode.TRUNCATED_RECORD, 8, "TEXT");
        Assertions.assertEquals(57, cut.detail);
        // n = 1 025: TEXT_TOO_LONG, with the tail present or cut (row 12 before 13)
        Expectations
            .assertRejected(segment(text(0, 0, 1, 0, 0, 0, new byte[1025])), view, ErrorCode.TEXT_TOO_LONG, 8, "TEXT");
        Expectations.assertRejected(
            concat(header(), withLe16(text(0, 0, 1, 0, 0, 0, "ab"), 15, 1025), END),
            view,
            ErrorCode.TEXT_TOO_LONG,
            8,
            "TEXT");
        Expectations.assertRejected(
            concat(header(), withLe16(text(0, 0, 0x100, 0, 0x80, 7, "ab"), 15, 65535), END),
            view,
            ErrorCode.TEXT_TOO_LONG,
            8,
            "TEXT");
        // n = 1 024 is accepted: 1 024 ASCII codepoints, 78 px each
        byte[] longest = new byte[1024];
        Arrays.fill(longest, (byte) 'A');
        Expectations.assertAccepted(
            segment(text(0, 0, 1, 0, 0, 0, longest)),
            view,
            new FrameTotals(8 + 17 + 1024 + 1, 1, 0, 0, 0, 1024 * 78));
        // The tail exactly to the end, no room for END
        Expectations
            .assertRejected(concat(header(), text(0, 0, 1, 0, 0, 0, "ab")), view, ErrorCode.MISSING_END, 27, "END");
        // A count one short makes the string's last byte the next opcode ('b' = 0x62, unassigned)
        Expectations.assertRejected(
            concat(header(), withLe16(text(0, 0, 1, 0, 0, 0, "ab"), 15, 1), END),
            view,
            ErrorCode.UNKNOWN_OPCODE,
            26,
            "0x62");
        // A count one long swallows the END
        Expectations.assertRejected(
            concat(header(), withLe16(text(0, 0, 1, 0, 0, 0, "ab"), 15, 3), END),
            view,
            ErrorCode.BAD_UTF8,
            8,
            "TEXT");
    }
}
