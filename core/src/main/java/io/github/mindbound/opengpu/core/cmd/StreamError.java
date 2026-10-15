package io.github.mindbound.opengpu.core.cmd;

/**
 * A rejected submit's one error (28 §3.6): 28:334's {@code StreamError(code, offset, opcode, detail)} plus the reason
 * text, so that {@link #message()} is what {@code submit} returns as {@code nil, msg}. The message is documentation,
 * stable within a release and asserted exactly by S7's and S10's tests (28:347). Immutable.
 */
public final class StreamError {

    /** The opcode of a whole-segment or header error, which no record has. */
    public static final int NO_OPCODE = -1;

    public final ErrorCode code;
    /** 28 §3.6's offset: the failing record's opcode byte, the header field's first byte, or 0 for the caps. */
    public final int offset;
    /**
     * The failing record's opcode byte 0-255; {@link Opcodes#END} for {@code MISSING_END} and {@code TRAILING_BYTES};
     * {@link #NO_OPCODE} for the submit and header codes (rows 1-7).
     */
    public final int opcode;
    /**
     * Per code: the segment length (rows 1, 3), the frame's bytes with it (2), bytes 0-3 as an LE u32 (4), the byte or
     * LE u16 found (5-7), 0 (8), the bytes after {@code END} (9), the opcode (10), the record size needed (11, 13), the
     * count (12), the colour or handle as an unsigned u32 (14-19), the flags or font byte (20), the string byte index
     * (21), and the frame's records or fill with this record (22, 23).
     */
    public final long detail;
    /** The {@code <reason>} of the message. */
    public final String reason;

    /**
     * @throws NullPointerException     for a null code or reason
     * @throws IllegalArgumentException for a negative offset, or an opcode that does not fit the code (see
     *                                  {@link #opcode})
     */
    public StreamError(ErrorCode code, int offset, int opcode, long detail, String reason) {
        if (code == null || reason == null) {
            throw new NullPointerException("code and reason must not be null");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset " + offset + " is negative");
        }
        int row = code.row();
        boolean fits;
        if (row <= ErrorCode.BAD_RESERVED.row()) {
            fits = opcode == NO_OPCODE;
        } else if (code == ErrorCode.MISSING_END || code == ErrorCode.TRAILING_BYTES) {
            fits = opcode == Opcodes.END;
        } else {
            fits = opcode >= 0 && opcode <= 0xFF;
        }
        if (!fits) {
            throw new IllegalArgumentException("opcode " + opcode + " does not fit " + code);
        }
        this.code = code;
        this.offset = offset;
        this.opcode = opcode;
        this.detail = detail;
        this.reason = reason;
    }

    /**
     * The message's {@code <OP>} (28 §3.6): "submit" for the caps of rows 1-2, "header" for rows 3-7, "END" for rows
     * 8-9, and otherwise the opcode's name, or "0xNN" (upper case) for an unassigned one.
     */
    public String op() {
        switch (code) {
            case OVER_SUBMIT_BYTES:
            case OVER_FRAME_BYTES:
                return "submit";
            case TRUNCATED_HEADER:
            case BAD_MAGIC:
            case BAD_VERSION:
            case BAD_FLAGS:
            case BAD_RESERVED:
                return "header";
            case MISSING_END:
            case TRAILING_BYTES:
                return "END";
            default:
                return Opcodes.token(opcode);
        }
    }

    /** {@code "<offset>: <OP>: <reason>"} (28 §3.6), e.g. "26: BLIT: handle 0x08000001 is not live". */
    public String message() {
        return offset + ": " + op() + ": " + reason;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StreamError)) {
            return false;
        }
        StreamError that = (StreamError) other;
        return code == that.code && offset == that.offset
            && opcode == that.opcode
            && detail == that.detail
            && reason.equals(that.reason);
    }

    @Override
    public int hashCode() {
        int hash = code.hashCode();
        hash = hash * 31 + offset;
        hash = hash * 31 + opcode;
        hash = hash * 31 + (int) (detail ^ (detail >>> 32));
        return hash * 31 + reason.hashCode();
    }

    /** {@link #message()}. */
    @Override
    public String toString() {
        return message();
    }
}
