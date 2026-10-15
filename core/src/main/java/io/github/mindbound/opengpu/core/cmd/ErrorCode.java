package io.github.mindbound.opengpu.core.cmd;

/**
 * The closed set of stream error codes, in 28 §3.6's evaluation order. Two validators that follow that order agree on
 * the code and the offset of every segment (28 §3.5).
 */
public enum ErrorCode {

    OVER_SUBMIT_BYTES(1),
    OVER_FRAME_BYTES(2),
    TRUNCATED_HEADER(3),
    BAD_MAGIC(4),
    BAD_VERSION(5),
    BAD_FLAGS(6),
    BAD_RESERVED(7),
    MISSING_END(8),
    TRAILING_BYTES(9),
    UNKNOWN_OPCODE(10),
    /** Rows 11 (the fixed part) and 13 ({@code TEXT}'s tail). */
    TRUNCATED_RECORD(11),
    TEXT_TOO_LONG(12),
    BAD_COLOUR(14),
    BAD_HANDLE(15),
    UNKNOWN_TYPE(16),
    WRONG_TYPE(17),
    STALE_HANDLE(18),
    WRONG_FORMAT(19),
    /** Row 20, before {@link #UNKNOWN_FONT}. */
    BAD_FLAGS_FIELD(20),
    UNKNOWN_FONT(20),
    /** Row 21: one scan, the first offending sequence decides between it and {@link #CONTROL_CHAR}. */
    BAD_UTF8(21),
    CONTROL_CHAR(21),
    OVER_FRAME_RECORDS(22),
    OVER_FRAME_FILL(23);

    private final int row;

    ErrorCode(int row) {
        this.row = row;
    }

    /** The code's row in 28 §3.6's table. */
    public int row() {
        return row;
    }
}
