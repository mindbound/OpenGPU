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

import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.fixtures.MapLiveView;

/**
 * The messages {@code submit} returns as {@code nil, msg}, {@code "<offset>: <OP>: <reason>"} (28 §3.6): 28's six
 * examples verbatim, and one exact message per other code, as S7's and S10's tests will assert them (28:347). The
 * reasons that 28 does not fix are the S6 plan's (its section 2.2, with amendment C's TRAILING_BYTES,
 * TRUNCATED_RECORD and BAD_COLOUR texts; BAD_COLOUR extends 28:264 with the field and the value).
 */
class StreamErrorMessageTest {

    /** 28 §9.4's T2 frame: records at 8, 13, 26 and 44, END at 63. */
    private static byte[] workedFrame(int handle, byte[] string) {
        return segment(
            clear(0),
            fill(10, 20, 100, 50, 196),
            blit(handle, 0, 0, 16, 16, 150, 40, 1),
            text(8, 180, 255, 0, 1, 0, string));
    }

    private static final byte[] HI = { 'H', 'i' };

    @Test
    void the28Examples() {
        MapLiveView t2 = Expectations.t2();
        assertMessage(
            new byte[70000],
            t2,
            FrameTotals.EMPTY,
            ErrorCode.OVER_SUBMIT_BYTES,
            "0: submit: 70000 bytes exceed the 65536-byte cap");
        assertMessage(
            concat(header(2, 0, 0), bytes(0xFF)),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.BAD_VERSION,
            "4: header: unsupported command format 2 (this mod decodes 1)");
        assertMessage(
            workedFrame(0x08000001, HI),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.STALE_HANDLE,
            "26: BLIT: handle 0x08000001 is not live");
        assertMessage(
            workedFrame(0x10000000, HI),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.WRONG_TYPE,
            "26: BLIT: handle 0x10000000 is a buffer, expected a texture");
        // "Hi!" and a stray continuation byte: n = 4, the bad byte at string index 3
        StreamError utf8 = assertMessage(
            workedFrame(Expectations.TEXTURE, bytes('H', 'i', '!', 0x80)),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.BAD_UTF8,
            "44: TEXT: invalid UTF-8 at string byte 3");
        Assertions.assertEquals(3, utf8.detail);
        // The header, three FILL_RECT and two CLEAR put 0x02 at 8 + 39 + 10 = 57
        byte[] f = fill(10, 20, 100, 50, 196);
        StreamError unknown = assertMessage(
            concat(header(), f, f, f, clear(0), clear(0), bytes(0x02, 0xFF)),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.UNKNOWN_OPCODE,
            "57: 0x02: unknown opcode");
        Assertions.assertEquals(0x02, unknown.opcode);
        Assertions.assertEquals(0x02, unknown.detail);
    }

    @Test
    void theWholeSegmentAndHeaderMessages() {
        MapLiveView t2 = Expectations.t2();
        StreamError frame = assertMessage(
            segment(),
            t2,
            new FrameTotals(131064, 0, 0, 0, 0, 0),
            ErrorCode.OVER_FRAME_BYTES,
            "0: submit: 131073 bytes with the open frame exceed the 131072-byte frame cap");
        Assertions.assertEquals(131073, frame.detail);
        Assertions.assertEquals(StreamError.NO_OPCODE, frame.opcode);
        StreamError shortHeader = assertMessage(
            bytes(0x4F, 0x47, 0x50, 0x55, 0x01),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.TRUNCATED_HEADER,
            "0: header: segment of 5 bytes is shorter than the 8-byte header");
        Assertions.assertEquals(5, shortHeader.detail);
        StreamError magic = assertMessage(
            concat(bytes('O', 'G', 'P', 'V', 1, 0, 0, 0), bytes(0xFF)),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.BAD_MAGIC,
            "0: header: bad magic, expected \"OGPU\"");
        Assertions.assertEquals(0x5650474FL, magic.detail);
        StreamError flags = assertMessage(
            concat(header(1, 0x80, 0), bytes(0xFF)),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.BAD_FLAGS,
            "5: header: flags 0x80 must be 0");
        Assertions.assertEquals(0x80, flags.detail);
        StreamError reserved = assertMessage(
            concat(header(1, 0, 0x8000), bytes(0xFF)),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.BAD_RESERVED,
            "6: header: reserved 0x8000 must be 0");
        Assertions.assertEquals(0x8000, reserved.detail);
    }

    @Test
    void theFramingMessages() {
        MapLiveView t2 = Expectations.t2();
        StreamError missing = assertMessage(
            header(),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.MISSING_END,
            "8: END: segment ends without END");
        Assertions.assertEquals(Opcodes.END, missing.opcode);
        Assertions.assertEquals(0, missing.detail);
        StreamError trailing = assertMessage(
            concat(header(), bytes(0xFF, 0x00)),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.TRAILING_BYTES,
            "8: END: END is not the last byte (1 after it)");
        Assertions.assertEquals(Opcodes.END, trailing.opcode);
        Assertions.assertEquals(1, trailing.detail);
        assertMessage(
            concat(header(), clear(0), bytes(0xFF, 0xFF, 0xFF)),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.TRAILING_BYTES,
            "13: END: END is not the last byte (2 after it)");
        // A BLIT opcode alone: 18 bytes needed, 1 left
        StreamError cut = assertMessage(
            concat(header(), bytes(0x31)),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.TRUNCATED_RECORD,
            "8: BLIT: record needs 18 bytes, only 1 left");
        Assertions.assertEquals(18, cut.detail);
        Assertions.assertEquals(Opcodes.BLIT, cut.opcode);
        StreamError tooLong = assertMessage(
            segment(text(0, 0, 1, 0, 0, 0, new byte[1025])),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.TEXT_TOO_LONG,
            "8: TEXT: string of 1025 bytes exceeds the 1024-byte limit");
        Assertions.assertEquals(1025, tooLong.detail);
        // TEXT with n = 2 and one string byte: 19 bytes needed, 18 left
        StreamError tail = assertMessage(
            head(concat(header(), text(0, 0, 1, 0, 0, 0, "ab")), 26),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.TRUNCATED_RECORD,
            "8: TEXT: record needs 19 bytes, only 18 left");
        Assertions.assertEquals(19, tail.detail);
    }

    @Test
    void theFieldMessages() {
        MapLiveView t2 = Expectations.t2();
        MapLiveView t3 = Expectations.t3();
        StreamError index = assertMessage(
            segment(clear(0x100)),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.BAD_COLOUR,
            "8: CLEAR: colour 0x00000100 is not an index 0-255");
        Assertions.assertEquals(0x100, index.detail);
        StreamError alpha = assertMessage(
            segment(fill(0, 0, 1, 1, 0x00FF8000)),
            t3,
            FrameTotals.EMPTY,
            ErrorCode.BAD_COLOUR,
            "8: FILL_RECT: colour 0x00FF8000: alpha must be 0xFF before blending (M1)");
        Assertions.assertEquals(0x00FF8000L, alpha.detail);
        assertMessage(
            segment(text(0, 0, 0xFF000000, 0xFEFFFFFF, 0, 0, "a")),
            t3,
            FrameTotals.EMPTY,
            ErrorCode.BAD_COLOUR,
            "8: TEXT: bg 0xFEFFFFFF: alpha must be 0xFF before blending (M1)");
        StreamError fg = assertMessage(
            segment(text(0, 0, 0xFF000000, 0, 0, 0, "a")),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.BAD_COLOUR,
            "8: TEXT: fg 0xFF000000 is not an index 0-255");
        // The detail is the colour as an unsigned u32
        Assertions.assertEquals(0xFF000000L, fg.detail);
        StreamError bad = assertMessage(
            segment(blit(0x80000000, 0, 0, 1, 1, 0, 0, 0)),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.BAD_HANDLE,
            "8: BLIT: invalid handle 0x80000000");
        Assertions.assertEquals(0x80000000L, bad.detail);
        assertMessage(
            segment(blit(0x48000000, 0, 0, 1, 1, 0, 0, 0)),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.UNKNOWN_TYPE,
            "8: BLIT: handle 0x48000000 has an unknown type");
        assertMessage(
            segment(blit(0x38000000, 0, 0, 1, 1, 0, 0, 0)),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.WRONG_TYPE,
            "8: BLIT: handle 0x38000000 is a compute list, expected a texture");
        MapLiveView mixed = Expectations.t2();
        mixed.put(0x08010000, 4, 4, Formats.RGB565);
        StreamError format = assertMessage(
            segment(blit(0x08010000, 0, 0, 1, 1, 0, 0, 0)),
            mixed,
            FrameTotals.EMPTY,
            ErrorCode.WRONG_FORMAT,
            "8: BLIT: texture 0x08010000 is rgb565, the surface is index8");
        Assertions.assertEquals(0x08010000L, format.detail);
        StreamError flags = assertMessage(
            segment(blit(Expectations.TEXTURE, 0, 0, 1, 1, 0, 0, 0x02)),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.BAD_FLAGS_FIELD,
            "8: BLIT: flags 0x02 set undefined bits");
        Assertions.assertEquals(2, flags.detail);
        assertMessage(
            segment(text(0, 0, 1, 0, 0x81, 0, "a")),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.BAD_FLAGS_FIELD,
            "8: TEXT: flags 0x81 set undefined bits");
        StreamError font = assertMessage(
            segment(text(0, 0, 1, 0, 0, 1, "a")),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.UNKNOWN_FONT,
            "8: TEXT: unknown font 1 (this release defines font 0)");
        Assertions.assertEquals(1, font.detail);
        StreamError control = assertMessage(
            segment(text(0, 0, 1, 0, 0, 0, "a\nb")),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.CONTROL_CHAR,
            "8: TEXT: control character U+000A at string byte 1");
        Assertions.assertEquals(1, control.detail);
        assertMessage(
            segment(text(0, 0, 1, 0, 0, 0, bytes('a', 'b', 0xC2, 0x9F))),
            t2,
            FrameTotals.EMPTY,
            ErrorCode.CONTROL_CHAR,
            "8: TEXT: control character U+009F at string byte 2");
    }

    @Test
    void theAccountingMessages() {
        MapLiveView t2 = Expectations.t2();
        StreamError records = assertMessage(
            segment(clear(0), clear(0)),
            t2,
            new FrameTotals(0, 4095, 0, 0, 0, 0),
            ErrorCode.OVER_FRAME_RECORDS,
            "13: CLEAR: 4097 records exceed the frame's 4096-record cap");
        Assertions.assertEquals(4097, records.detail);
        StreamError pixels = assertMessage(
            segment(clear(0)),
            t2,
            new FrameTotals(0, 0, 0, 960001, 0, 0),
            ErrorCode.OVER_FRAME_FILL,
            "8: CLEAR: 1024001 px of fill exceed the frame's 1024000-px cap");
        Assertions.assertEquals(1024001, pixels.detail);
    }

    @Test
    void streamErrorChecksItsFields() {
        StreamError e = new StreamError(ErrorCode.UNKNOWN_OPCODE, 57, 0x02, 0x02, "unknown opcode");
        Assertions.assertEquals("57: 0x02: unknown opcode", e.message());
        Assertions.assertEquals(e.message(), e.toString());
        Assertions.assertEquals(e, new StreamError(ErrorCode.UNKNOWN_OPCODE, 57, 0x02, 0x02, "unknown opcode"));
        Assertions.assertEquals(
            e.hashCode(),
            new StreamError(ErrorCode.UNKNOWN_OPCODE, 57, 0x02, 0x02, "unknown opcode").hashCode());
        Assertions.assertNotEquals(e, new StreamError(ErrorCode.UNKNOWN_OPCODE, 58, 0x02, 0x02, "unknown opcode"));
        Assertions.assertNotEquals(e, new StreamError(ErrorCode.UNKNOWN_OPCODE, 57, 0x03, 0x02, "unknown opcode"));
        Assertions.assertNotEquals(e, new StreamError(ErrorCode.UNKNOWN_OPCODE, 57, 0x02, 0x03, "unknown opcode"));
        Assertions.assertNotEquals(e, new StreamError(ErrorCode.UNKNOWN_OPCODE, 57, 0x02, 0x02, "other"));
        // <OP> by code (28 §3.6): submit, header, END, the name or 0xNN
        Assertions.assertEquals("submit", new StreamError(ErrorCode.OVER_SUBMIT_BYTES, 0, -1, 0, "r").op());
        Assertions.assertEquals("submit", new StreamError(ErrorCode.OVER_FRAME_BYTES, 0, -1, 0, "r").op());
        for (ErrorCode code : new ErrorCode[] { ErrorCode.TRUNCATED_HEADER, ErrorCode.BAD_MAGIC, ErrorCode.BAD_VERSION,
            ErrorCode.BAD_FLAGS, ErrorCode.BAD_RESERVED }) {
            Assertions.assertEquals("header", new StreamError(code, 0, -1, 0, "r").op());
        }
        Assertions.assertEquals("END", new StreamError(ErrorCode.MISSING_END, 8, 0xFF, 0, "r").op());
        Assertions.assertEquals("END", new StreamError(ErrorCode.TRAILING_BYTES, 8, 0xFF, 0, "r").op());
        Assertions.assertEquals("TEXT", new StreamError(ErrorCode.BAD_UTF8, 8, 0x33, 0, "r").op());
        Assertions.assertEquals("0xFE", new StreamError(ErrorCode.UNKNOWN_OPCODE, 8, 0xFE, 0xFE, "r").op());
        // The opcode must fit the code
        Assertions
            .assertThrows(IllegalArgumentException.class, () -> new StreamError(ErrorCode.BAD_MAGIC, 0, 0x01, 0, "r"));
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new StreamError(ErrorCode.MISSING_END, 8, 0x01, 0, "r"));
        Assertions
            .assertThrows(IllegalArgumentException.class, () -> new StreamError(ErrorCode.BAD_COLOUR, 8, -1, 0, "r"));
        Assertions
            .assertThrows(IllegalArgumentException.class, () -> new StreamError(ErrorCode.BAD_COLOUR, 8, 256, 0, "r"));
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new StreamError(ErrorCode.BAD_COLOUR, -1, 0x01, 0, "r"));
        Assertions.assertThrows(NullPointerException.class, () -> new StreamError(null, 0, -1, 0, "r"));
        Assertions.assertThrows(NullPointerException.class, () -> new StreamError(ErrorCode.BAD_MAGIC, 0, -1, 0, null));
    }

    @Test
    void textureHandlesNamesEveryKind() {
        // 28 §3.6's handle messages, shared with S7's updateTexture and free
        String[] kinds = { "buffer", "render target", "program", "pipeline", "kernel", "compute list" };
        for (int type = 2; type <= 7; type++) {
            int handle = type << 27;
            Assertions.assertEquals(
                String.format(Locale.ROOT, "handle 0x%08X is a %s, expected a texture", handle, kinds[type - 2]),
                TextureHandles.reason(ErrorCode.WRONG_TYPE, handle));
        }
        Assertions.assertEquals("invalid handle 0x00000000", TextureHandles.reason(ErrorCode.BAD_HANDLE, 0));
        Assertions.assertEquals(
            "handle 0x7FFFFFFF has an unknown type",
            TextureHandles.reason(ErrorCode.UNKNOWN_TYPE, 0x7FFFFFFF));
        Assertions
            .assertEquals("handle 0x08000001 is not live", TextureHandles.reason(ErrorCode.STALE_HANDLE, 0x08000001));
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> TextureHandles.reason(ErrorCode.BAD_COLOUR, 0x08000000));
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> TextureHandles.reason(ErrorCode.WRONG_TYPE, 0x08000000));
        MapLiveView t2 = Expectations.t2();
        Assertions.assertNull(TextureHandles.check(Expectations.TEXTURE, t2));
        Assertions.assertEquals(ErrorCode.BAD_HANDLE, TextureHandles.check(0, t2));
        Assertions.assertEquals(ErrorCode.BAD_HANDLE, TextureHandles.check(0x80000000, t2));
        Assertions.assertEquals(ErrorCode.UNKNOWN_TYPE, TextureHandles.check(0x40000000, t2));
        Assertions.assertEquals(ErrorCode.WRONG_TYPE, TextureHandles.check(0x10000000, t2));
        Assertions.assertEquals(ErrorCode.STALE_HANDLE, TextureHandles.check(0x08000001, t2));
    }

    /** Both validators reject s with the code at the message's offset and OP; the main one's message is exact. */
    private static StreamError assertMessage(byte[] s, MapLiveView view, FrameTotals open, ErrorCode code,
        String message) {
        int colon = message.indexOf(": ");
        int offset = Integer.parseInt(message.substring(0, colon));
        String op = message.substring(colon + 2, message.indexOf(": ", colon + 2));
        StreamError e = Expectations.assertRejected(s, view, open, code, offset, op);
        Assertions.assertEquals(message, e.message());
        return e;
    }
}
