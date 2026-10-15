package io.github.mindbound.opengpu.core.cmd;

import java.util.Locale;

/**
 * The reader the executors and the oracle re-read validated segments with (28 §2.4): it trusts its input and asserts
 * its invariants with Java {@code assert}, which every Gradle Test task enables. A frame is its segments read in order;
 * v1 has no frame-scoped state (28 §3.2). Nothing here allocates.
 */
public final class SegmentReader {

    private SegmentReader() {}

    /**
     * Visits the records of a segment {@link Validator} accepted, in order, up to its {@code END}.
     *
     * @throws IllegalStateException at an unassigned opcode, which only an unvalidated segment holds
     */
    public static void read(byte[] segment, OpVisitor visitor) {
        assert isV1Header(segment) : "not a validated segment: its header is not a v1 header";
        int p = Header.SIZE;
        while (true) {
            int op = LittleEndian.u8(segment, p);
            switch (op) {
                case Opcodes.END:
                    assert p == segment.length - 1 : "not a validated segment: END at " + p + " is not the last byte";
                    return;
                case Opcodes.CLEAR:
                    visitor.clear(LittleEndian.u32(segment, p + Opcodes.CLEAR_COLOUR));
                    p += Opcodes.CLEAR_SIZE;
                    break;
                case Opcodes.FILL_RECT:
                    visitor.fillRect(
                        LittleEndian.i16(segment, p + Opcodes.FILL_X),
                        LittleEndian.i16(segment, p + Opcodes.FILL_Y),
                        LittleEndian.i16(segment, p + Opcodes.FILL_W),
                        LittleEndian.i16(segment, p + Opcodes.FILL_H),
                        LittleEndian.u32(segment, p + Opcodes.FILL_COLOUR));
                    p += Opcodes.FILL_RECT_SIZE;
                    break;
                case Opcodes.BLIT:
                    visitor.blit(
                        LittleEndian.u32(segment, p + Opcodes.BLIT_TEX),
                        LittleEndian.i16(segment, p + Opcodes.BLIT_SX),
                        LittleEndian.i16(segment, p + Opcodes.BLIT_SY),
                        LittleEndian.i16(segment, p + Opcodes.BLIT_W),
                        LittleEndian.i16(segment, p + Opcodes.BLIT_H),
                        LittleEndian.i16(segment, p + Opcodes.BLIT_DX),
                        LittleEndian.i16(segment, p + Opcodes.BLIT_DY),
                        LittleEndian.u8(segment, p + Opcodes.BLIT_FLAGS));
                    p += Opcodes.BLIT_SIZE;
                    break;
                case Opcodes.TEXT: {
                    int n = LittleEndian.u16(segment, p + Opcodes.TEXT_COUNT);
                    visitor.text(
                        LittleEndian.i16(segment, p + Opcodes.TEXT_X),
                        LittleEndian.i16(segment, p + Opcodes.TEXT_Y),
                        LittleEndian.u32(segment, p + Opcodes.TEXT_FG),
                        LittleEndian.u32(segment, p + Opcodes.TEXT_BG),
                        LittleEndian.u8(segment, p + Opcodes.TEXT_FLAGS),
                        LittleEndian.u8(segment, p + Opcodes.TEXT_FONT),
                        segment,
                        p + Opcodes.TEXT_STRING,
                        n);
                    p += Opcodes.TEXT_FIXED_SIZE + n;
                    break;
                }
                default:
                    throw new IllegalStateException(
                        String.format(Locale.ROOT, "opcode 0x%02X at %d in an unvalidated segment", op, p));
            }
        }
    }

    private static boolean isV1Header(byte[] s) {
        return s.length >= Header.MIN_SEGMENT && LittleEndian.u8(s, 0) == Header.MAGIC_0
            && LittleEndian.u8(s, 1) == Header.MAGIC_1
            && LittleEndian.u8(s, 2) == Header.MAGIC_2
            && LittleEndian.u8(s, 3) == Header.MAGIC_3
            && Header.isDecoded(LittleEndian.u8(s, Header.OFFSET_VERSION))
            && s[Header.OFFSET_FLAGS] == 0
            && s[Header.OFFSET_RESERVED] == 0
            && s[Header.OFFSET_RESERVED + 1] == 0;
    }
}
