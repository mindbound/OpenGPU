package io.github.mindbound.opengpu.core.cmd;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.SurfaceDesc;

/**
 * A smoke test of the validator and the reader on hand-written bytes (no test fixtures): 28 §9.4's worked frame, the
 * shortest rejections and 28 §3.6's six example messages. The full suites are the cmd tests beside it.
 */
class ValidatorSmokeTest {

    /** 28 §9.4: the frame on T2, 64 B; records at 8, 13, 26, 44 and END at 63. */
    private static final String T2_FRAME = "4F 47 50 55 01 00 00 00" // header
        + " 01 00 00 00 00" // CLEAR 0
        + " 30 0A 00 14 00 64 00 32 00 C4 00 00 00" // FILL_RECT 10,20 100x50 colour 196
        + " 31 00 00 00 08 00 00 00 00 10 00 10 00 96 00 28 00 01" // BLIT 0x08000000 0,0 16x16 to 150,40, KEY0
        + " 33 08 00 B4 00 FF 00 00 00 00 00 00 00 01 00 02 00 48 69" // TEXT 8,180 fg 255 bg 0 TRANSPARENT_BG "Hi"
        + " FF";

    /** 28:842: the same frame with rgb565 operands, again 64 B. */
    private static final String T3_FRAME = "4F 47 50 55 01 00 00 00" // header
        + " 01 00 00 00 FF" // CLEAR 0xFF000000
        + " 30 0A 00 14 00 64 00 32 00 00 80 FF FF" // FILL_RECT colour 0xFFFF8000
        + " 31 00 00 00 08 00 00 00 00 10 00 10 00 96 00 28 00 01" // the same BLIT
        + " 33 08 00 B4 00 FF FF FF FF 00 00 00 FF 01 00 02 00 48 69" // TEXT fg 0xFFFFFFFF bg 0xFF000000
        + " FF";

    /** A new device's first texture (28 §6.1). */
    private static final int TEXTURE = 0x08000000;

    @Test
    void theWorkedFrameIsAcceptedOnT2() {
        byte[] s = hex(T2_FRAME);
        Assertions.assertEquals(64, s.length);
        Validation v = Validator.validate(s, view(2));
        Assertions.assertTrue(v.isAccepted(), v::toString);
        Assertions.assertNull(v.error());
        Assertions.assertEquals(new FrameTotals(64, 4, 64000, 5000, 256, 156), v.totals());
        Assertions.assertEquals(
            69412,
            v.totals()
                .totalPixels());
    }

    @Test
    void theWorkedFrameIsAcceptedOnT3() {
        byte[] s = hex(T3_FRAME);
        Assertions.assertEquals(64, s.length);
        Validation v = Validator.validate(s, view(3));
        Assertions.assertTrue(v.isAccepted(), v::toString);
        Assertions.assertEquals(new FrameTotals(64, 4, 256000, 5000, 256, 156), v.totals());
    }

    @Test
    void theT2OperandsAreBadColoursOnT3() {
        assertRejected(
            Validator.validate(hex(T2_FRAME), view(3)),
            ErrorCode.BAD_COLOUR,
            8,
            "8: CLEAR: colour 0x00000000: alpha must be 0xFF before blending (M1)");
    }

    @Test
    void theHeaderAloneIsMissingEnd() {
        Validation v = Validator.validate(hex("4F 47 50 55 01 00 00 00"), view(2));
        assertRejected(v, ErrorCode.MISSING_END, 8, "8: END: segment ends without END");
        Assertions.assertEquals(Opcodes.END, v.error().opcode);
        Assertions.assertEquals(
            "END",
            v.error()
                .op());
    }

    @Test
    void theShortestSegmentIsAnAcceptedNoOp() {
        Validation v = Validator.validate(hex("4F 47 50 55 01 00 00 00 FF"), view(2));
        Assertions.assertTrue(v.isAccepted(), v::toString);
        Assertions.assertEquals(new FrameTotals(9, 0, 0, 0, 0, 0), v.totals());
    }

    @Test
    void theSixExampleMessagesOf28() {
        // 28 §3.6's examples, verbatim
        assertRejected(
            Validator.validate(new byte[70000], view(2)),
            ErrorCode.OVER_SUBMIT_BYTES,
            0,
            "0: submit: 70000 bytes exceed the 65536-byte cap");
        assertRejected(
            Validator.validate(hex("4F 47 50 55 02 00 00 00 FF"), view(2)),
            ErrorCode.BAD_VERSION,
            4,
            "4: header: unsupported command format 2 (this mod decodes 1)");
        assertRejected(
            Validator.validate(withHandle(0x08000001), view(2)),
            ErrorCode.STALE_HANDLE,
            26,
            "26: BLIT: handle 0x08000001 is not live");
        assertRejected(
            Validator.validate(withHandle(0x10000000), view(2)),
            ErrorCode.WRONG_TYPE,
            26,
            "26: BLIT: handle 0x10000000 is a buffer, expected a texture");
        // The TEXT at 44 with "Hi!" and a stray continuation byte: n = 4
        byte[] badText = hex(
            T2_FRAME.substring(0, T2_FRAME.indexOf(" 33 08"))
                + " 33 08 00 B4 00 FF 00 00 00 00 00 00 00 01 00 04 00 48 69 21 80 FF");
        Validation utf8 = Validator.validate(badText, view(2));
        assertRejected(utf8, ErrorCode.BAD_UTF8, 44, "44: TEXT: invalid UTF-8 at string byte 3");
        Assertions.assertEquals(3, utf8.error().detail);
        // The header, three FILL_RECT and two CLEAR put an unassigned 0x02 at 57
        String fill = " 30 0A 00 14 00 64 00 32 00 C4 00 00 00";
        String clear = " 01 00 00 00 00";
        byte[] unknown = hex("4F 47 50 55 01 00 00 00" + fill + fill + fill + clear + clear + " 02 FF");
        Validation op = Validator.validate(unknown, view(2));
        assertRejected(op, ErrorCode.UNKNOWN_OPCODE, 57, "57: 0x02: unknown opcode");
        Assertions.assertEquals(0x02, op.error().opcode);
    }

    @Test
    void theReaderVisitsTheWorkedFramesFields() {
        byte[] s = hex(T2_FRAME);
        byte[] copy = s.clone();
        Assertions.assertTrue(
            Validator.validate(s, view(2))
                .isAccepted());
        final List<String> ops = new ArrayList<>();
        SegmentReader.read(s, new OpVisitor() {

            @Override
            public void clear(int colour) {
                ops.add(String.format(Locale.ROOT, "CLEAR %d", colour));
            }

            @Override
            public void fillRect(int x, int y, int w, int h, int colour) {
                ops.add(String.format(Locale.ROOT, "FILL_RECT %d %d %d %d %d", x, y, w, h, colour));
            }

            @Override
            public void blit(int texture, int sx, int sy, int w, int h, int dx, int dy, int flags) {
                ops.add(
                    String
                        .format(Locale.ROOT, "BLIT 0x%08X %d %d %d %d %d %d %d", texture, sx, sy, w, h, dx, dy, flags));
            }

            @Override
            public void text(int x, int y, int fg, int bg, int flags, int font, byte[] segment, int stringOffset,
                int stringLength) {
                String string = new String(segment, stringOffset, stringLength, StandardCharsets.UTF_8);
                ops.add(String.format(Locale.ROOT, "TEXT %d %d %d %d %d %d %s", x, y, fg, bg, flags, font, string));
            }
        });
        Assertions.assertEquals(
            Arrays.asList(
                "CLEAR 0",
                "FILL_RECT 10 20 100 50 196",
                "BLIT 0x08000000 0 0 16 16 150 40 1",
                "TEXT 8 180 255 0 1 0 Hi"),
            ops);
        // Neither the validator nor the reader writes the array (C14)
        Assertions.assertArrayEquals(copy, s);
    }

    /** The T2 frame with its BLIT's handle replaced. */
    private static byte[] withHandle(int handle) {
        byte[] s = hex(T2_FRAME);
        for (int k = 0; k < 4; k++) {
            s[27 + k] = (byte) (handle >>> 8 * k);
        }
        return s;
    }

    private static void assertRejected(Validation v, ErrorCode code, int offset, String message) {
        Assertions.assertFalse(v.isAccepted(), v::toString);
        Assertions.assertEquals(code, v.error().code);
        Assertions.assertEquals(offset, v.error().offset);
        Assertions.assertEquals(
            message,
            v.error()
                .message());
        Assertions.assertThrows(IllegalStateException.class, v::totals);
    }

    /** The tier's surface and caps, with {@link #TEXTURE} live as a 16x16 texture in the surface's format. */
    private static LiveView view(int tier) {
        final SurfaceDesc surface = SurfaceDesc.forTier(tier);
        final FrameLimits limits = FrameLimits.forTier(tier);
        return new LiveView() {

            @Override
            public SurfaceDesc surface() {
                return surface;
            }

            @Override
            public FrameLimits limits() {
                return limits;
            }

            @Override
            public int textureFormat(int handle) {
                return handle == TEXTURE ? surface.format : 0;
            }

            @Override
            public int textureWidth(int handle) {
                return 16;
            }

            @Override
            public int textureHeight(int handle) {
                return 16;
            }
        };
    }

    private static byte[] hex(String spaced) {
        String[] parts = spaced.trim()
            .split("\\s+");
        byte[] bytes = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            bytes[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return bytes;
    }
}
