package io.github.mindbound.opengpu.core.cmd;

/**
 * The in-memory op model that every version's decoder translates into, so executors and the oracle know one model
 * only (28 §4.2). {@link SegmentReader} calls one method per record, in order. Integer fields arrive as 28 §5.2 types
 * them: i16 sign-extended, u8 as 0-255, colours and handles with all 32 bits in the int.
 */
public interface OpVisitor {

    /** {@code CLEAR}: the raw colour; an executor writes {@code Colours.sv(format, colour)} (28 §5.3). */
    void clear(int colour);

    /** {@code FILL_RECT} (28 §5.4). */
    void fillRect(int x, int y, int w, int h, int colour);

    /** {@code BLIT} (28 §5.5); the flags byte as recorded. */
    void blit(int texture, int sx, int sy, int w, int h, int dx, int dy, int flags);

    /**
     * {@code TEXT} (28 §5.6): its string is {@code segment[stringOffset, stringOffset + stringLength)}, well-formed
     * UTF-8 without controls, which the visitor reads in place and must not write.
     */
    void text(int x, int y, int fg, int bg, int flags, int font, byte[] segment, int stringOffset, int stringLength);
}
