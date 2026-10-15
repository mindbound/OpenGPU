package io.github.mindbound.opengpu.core.cmd;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.bytes;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.concat;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.head;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.header;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.with;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.mindbound.opengpu.core.fixtures.MapLiveView;

/**
 * 28 §11.2's lengths and header fields (28 §3.1; §3.6 rows 3-8): every case through both validators, on T2 and T3,
 * since the header does not depend on the surface.
 */
class ValidatorHeaderTest {

    private static final byte[] END = bytes(0xFF);

    @ParameterizedTest(name = "T{0}")
    @ValueSource(ints = { 2, 3 })
    void theEmptyStringAndEveryShortPrefixAreATruncatedHeader(int tier) {
        MapLiveView view = Expectations.tier(tier);
        Expectations.assertRejected(new byte[0], view, ErrorCode.TRUNCATED_HEADER, 0, "header");
        for (int length = 1; length < 8; length++) {
            Expectations.assertRejected(head(header(), length), view, ErrorCode.TRUNCATED_HEADER, 0, "header");
            // Row 3 comes before the magic: seven bytes of anything are a truncated header
            Expectations.assertRejected(new byte[length], view, ErrorCode.TRUNCATED_HEADER, 0, "header");
        }
    }

    @ParameterizedTest(name = "T{0}")
    @ValueSource(ints = { 2, 3 })
    void exactlyTheHeaderIsMissingEndAt8(int tier) {
        Expectations.assertRejected(header(), Expectations.tier(tier), ErrorCode.MISSING_END, 8, "END");
    }

    @ParameterizedTest(name = "T{0}")
    @ValueSource(ints = { 2, 3 })
    void theHeaderAndEndAreANoOp(int tier) {
        Expectations.assertAccepted(concat(header(), END), Expectations.tier(tier), new FrameTotals(9, 0, 0, 0, 0, 0));
    }

    @Test
    void eachWrongMagicByteIsBadMagicAt0() {
        MapLiveView view = Expectations.t2();
        byte[] s = concat(header(), END);
        for (int i = 0; i < 4; i++) {
            for (int value : new int[] { 0x00, 0xFF, s[i] ^ 0x20, s[i] + 1, s[i] - 1 }) {
                Expectations.assertRejected(with(s, i, value), view, ErrorCode.BAD_MAGIC, 0, "header");
            }
        }
        // Lower case and reversed
        Expectations
            .assertRejected(concat(bytes('o', 'g', 'p', 'u', 1, 0, 0, 0), END), view, ErrorCode.BAD_MAGIC, 0, "header");
        Expectations
            .assertRejected(concat(bytes('U', 'P', 'G', 'O', 1, 0, 0, 0), END), view, ErrorCode.BAD_MAGIC, 0, "header");
    }

    @ParameterizedTest(name = "version {0}")
    @ValueSource(ints = { 0, 2, 3, 127, 128, 255 })
    void anUndecodedVersionIsBadVersionAt4(int version) {
        Expectations
            .assertRejected(concat(header(version, 0, 0), END), Expectations.t2(), ErrorCode.BAD_VERSION, 4, "header");
    }

    @ParameterizedTest(name = "flags bit {0}")
    @ValueSource(ints = { 0, 1, 2, 3, 4, 5, 6, 7 })
    void eachFlagsBitIsBadFlagsAt5(int bit) {
        // Every header flag bit is reserved in v1 (28 §3.1)
        Expectations
            .assertRejected(concat(header(1, 1 << bit, 0), END), Expectations.t2(), ErrorCode.BAD_FLAGS, 5, "header");
    }

    @ParameterizedTest(name = "reserved {0}")
    @ValueSource(ints = { 0x0001, 0x8000, 0x0100, 0xFFFF })
    void aNonZeroReservedFieldIsBadReservedAt6(int reserved) {
        // 01 00, 00 80, 00 01 and FF FF: offset 6 even when only byte 7 is set
        Expectations.assertRejected(
            concat(header(1, 0, reserved), END),
            Expectations.t2(),
            ErrorCode.BAD_RESERVED,
            6,
            "header");
    }

    @Test
    void theHeaderIsCheckedFieldByFieldInOrder() {
        MapLiveView view = Expectations.t2();
        // Bad magic and bad version: the magic decides
        Expectations
            .assertRejected(concat(bytes('O', 'G', 'P', 'X', 2, 0, 0, 0), END), view, ErrorCode.BAD_MAGIC, 0, "header");
        // Bad version and bad flags: the version
        Expectations.assertRejected(concat(header(2, 1, 0), END), view, ErrorCode.BAD_VERSION, 4, "header");
        // Bad flags and bad reserved: the flags
        Expectations.assertRejected(concat(header(1, 1, 1), END), view, ErrorCode.BAD_FLAGS, 5, "header");
        // Everything bad
        Expectations
            .assertRejected(bytes(0, 0, 0, 0, 0, 0xFF, 0xFF, 0xFF, 0xFF), view, ErrorCode.BAD_MAGIC, 0, "header");
        // A valid header with no record loop yet decides before the records do
        Expectations.assertRejected(concat(header(1, 0, 1), bytes(0x02)), view, ErrorCode.BAD_RESERVED, 6, "header");
        // A zero-filled buffer: the magic
        Expectations.assertRejected(new byte[64], view, ErrorCode.BAD_MAGIC, 0, "header");
    }

    @Test
    void aHeaderAfterTheHeaderIsAnUnknownOpcode() {
        // "OGPU" twice: 0x4F is unassigned, so the second header is no record
        Expectations
            .assertRejected(concat(header(), header(), END), Expectations.t2(), ErrorCode.UNKNOWN_OPCODE, 8, "0x4F");
    }
}
