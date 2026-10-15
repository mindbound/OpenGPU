package io.github.mindbound.opengpu.core.naive;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * A deliberately naive validator for command stream v1, written straight from note 28's tables
 * (§3.1 header, §3.6 error rows 1-23, §5.2 layouts, §5.7 BLIT ranges, §6.1 handle types,
 * §7.2-7.3 UTF-8 and cells) for the differential fuzzer (28 §11.3, property P2).
 *
 * <p>
 * It shares no code with the main validator and is meant to be slow and obvious: absolute
 * little-endian reads through a {@link ByteBuffer}, its own opcode table parsed from text, UTF-8
 * matched against Unicode Table 3-7 held as data, fill counted column by column and row by row,
 * and one {@code if} per error row in the table's order. It allocates freely.
 */
public final class NaiveValidator {

    /** The surface, the frame caps, the open frame's running totals and the live textures. */
    public static final class Config {

        /** 1 = index8, 2 = rgb565. */
        public int format;
        public int width, height;
        public int submitCap, frameByteCap, frameRecordCap;
        public long frameFillCap;
        public int openBytes, openRecords;
        public long openFill;
        /** Live textures: handle as an unsigned u32 -> {w, h, format}. */
        public Map<Long, int[]> textures = new HashMap<>();
    }

    /** One outcome: accepted, or exactly one error. */
    public static final class Result {

        public boolean ok;
        /** "OK" or the 28 §3.6 code name. */
        public String code;
        /** The failing record's opcode offset or the header field's offset; for "OK", the END's offset. */
        public int offset;
        /** "submit", "header", "END", an opcode name, or "0xNN" for an unassigned opcode. */
        public String op;
        /** The string byte index for BAD_UTF8 and CONTROL_CHAR, else -1. */
        public long detail = -1;
        /** Records in this segment, END excluded; accepted only (0 on an error). */
        public int records;
        /** Fill per class: clear, fill, copy, set; accepted only (all 0 on an error). */
        public long[] fill = new long[4];

        @Override
        public String toString() {
            return ok ? "OK records=" + records + " fill=" + fill[0] + "/" + fill[1] + "/" + fill[2] + "/" + fill[3]
                : code + " at " + offset + " (" + op + ")" + (detail >= 0 ? " detail " + detail : "");
        }
    }

    // ------------------------------------------------------------------ the opcode table (28 §5.2)

    /** One opcode: its byte, its name, its fixed size including the opcode, its fill class (§5.2). */
    private static final class Op {

        final int code;
        final String name;
        final int fixedSize;
        final int fillClass;

        Op(int code, String name, int fixedSize, int fillClass) {
            this.code = code;
            this.name = name;
            this.fixedSize = fixedSize;
            this.fillClass = fillClass;
        }
    }

    /** 28 §5.2 as text: hex opcode, name, total fixed bytes, fill class (0 clear, 1 fill, 2 copy, 3 set). */
    private static final String OPCODE_TABLE = "01 CLEAR      5 0\n" + "30 FILL_RECT 13 1\n"
        + "31 BLIT      18 2\n"
        + "33 TEXT      17 3\n";

    private static final Map<String, Op> OPS_BY_HEX = new HashMap<>();

    static {
        for (String line : OPCODE_TABLE.split("\n")) {
            String[] f = line.trim()
                .split("\\s+");
            Op op = new Op(Integer.parseInt(f[0], 16), f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]));
            OPS_BY_HEX.put(f[0], op);
        }
    }

    private static Op lookup(int opcode) {
        return OPS_BY_HEX.get(String.format("%02X", opcode));
    }

    private static final int END_OPCODE = 0xFF;
    private static final int FORMAT_INDEX8 = 1;
    private static final int FORMAT_RGB565 = 2;
    /** 28 §8.1 maxTextBytes: a constant, not a config cap. */
    private static final int TEXT_MAX_BYTES = 1024;
    /** 28 §7.3: one 6x13 cell per codepoint. */
    private static final int CELL_W = 6, CELL_H = 13;

    // ------------------------------------------------------------------ Unicode Table 3-7

    /**
     * The Unicode Standard's Table 3-7, "Well-Formed UTF-8 Byte Sequences": each row lists, for each
     * byte of the sequence, the inclusive range it must fall in. Anything not matched is ill-formed;
     * a sequence cut by the end of the string is ill-formed too. Noncharacters, private use, U+FEFF
     * and unassigned codepoints are well-formed and accepted.
     */
    private static final int[][][] TABLE_3_7 = { { { 0x00, 0x7F } }, { { 0xC2, 0xDF }, { 0x80, 0xBF } },
        { { 0xE0, 0xE0 }, { 0xA0, 0xBF }, { 0x80, 0xBF } }, { { 0xE1, 0xEC }, { 0x80, 0xBF }, { 0x80, 0xBF } },
        { { 0xED, 0xED }, { 0x80, 0x9F }, { 0x80, 0xBF } }, { { 0xEE, 0xEF }, { 0x80, 0xBF }, { 0x80, 0xBF } },
        { { 0xF0, 0xF0 }, { 0x90, 0xBF }, { 0x80, 0xBF }, { 0x80, 0xBF } },
        { { 0xF1, 0xF3 }, { 0x80, 0xBF }, { 0x80, 0xBF }, { 0x80, 0xBF } },
        { { 0xF4, 0xF4 }, { 0x80, 0x8F }, { 0x80, 0xBF }, { 0x80, 0xBF } }, };

    /** Payload bits of the lead byte, by sequence length 1..4. */
    private static final int[] LEAD_MASK = { 0, 0x7F, 0x1F, 0x0F, 0x07 };

    private static boolean isControl(int cp) {
        return (cp >= 0x0000 && cp <= 0x001F) || (cp >= 0x007F && cp <= 0x009F);
    }

    /**
     * One left-to-right scan of {@code n} string bytes starting at {@code start}.
     * Returns {0, codepoints}, {1, index} for BAD_UTF8 or {2, index} for CONTROL_CHAR, where index is
     * the string byte index of the offending sequence's first byte.
     */
    private static long[] scanText(ByteBuffer bb, int start, int n) {
        long codepoints = 0;
        int k = 0;
        while (k < n) {
            int lead = u8(bb, start + k);
            int[][] row = null;
            for (int[][] candidate : TABLE_3_7) {
                if (lead >= candidate[0][0] && lead <= candidate[0][1]) {
                    row = candidate;
                    break;
                }
            }
            if (row == null) {
                return new long[] { 1, k };
            }
            int cp = lead & LEAD_MASK[row.length];
            for (int j = 1; j < row.length; j++) {
                if (k + j >= n) {
                    return new long[] { 1, k };
                }
                int b = u8(bb, start + k + j);
                if (b < row[j][0] || b > row[j][1]) {
                    return new long[] { 1, k };
                }
                cp = (cp << 6) | (b & 0x3F);
            }
            if (isControl(cp)) {
                return new long[] { 2, k };
            }
            codepoints++;
            k += row.length;
        }
        return new long[] { 0, codepoints };
    }

    // ------------------------------------------------------------------ operand reads (28 §3.3)

    private static int u8(ByteBuffer bb, int at) {
        return bb.get(at) & 0xFF;
    }

    private static int u16(ByteBuffer bb, int at) {
        return bb.getShort(at) & 0xFFFF;
    }

    private static int i16(ByteBuffer bb, int at) {
        return bb.getShort(at);
    }

    private static long u32(ByteBuffer bb, int at) {
        return bb.getInt(at) & 0xFFFFFFFFL;
    }

    // ------------------------------------------------------------------ colours (28 §3.4)

    private static boolean colourValid(long c, int format) {
        if (format == FORMAT_INDEX8) {
            return c >= 0 && c <= 0xFF;
        }
        if (format == FORMAT_RGB565) {
            return ((c >>> 24) & 0xFF) == 0xFF;
        }
        return false;
    }

    // ------------------------------------------------------------------ fill (28 §3.5, §5.1, §5.7)

    /** Surface columns (or rows) 0 <= q < size with start <= q < start + len, counted one by one. */
    private static long coveredRect(int start, int len, int size) {
        long count = 0;
        int end = start + len; // i16 + i16 cannot overflow int (§5.1)
        for (int q = 0; q < size; q++) {
            if (q >= start && q < end) {
                count++;
            }
        }
        return count;
    }

    /**
     * BLIT's i (or j) range of §5.7 counted over the surface: dest q = d + i is covered when
     * 0 <= i < len, 0 <= s + i < texSize and 0 <= q < surfaceSize.
     */
    private static long coveredBlit(int src, int dst, int len, int texSize, int surfaceSize) {
        long count = 0;
        for (int q = 0; q < surfaceSize; q++) {
            int i = q - dst;
            if (i >= 0 && i < len && src + i >= 0 && src + i < texSize) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ the validator (28 §3.5-3.6)

    private static Result fail(String code, int offset, String op) {
        Result r = new Result();
        r.ok = false;
        r.code = code;
        r.offset = offset;
        r.op = op;
        r.detail = -1;
        return r;
    }

    private static Result fail(String code, int offset, String op, long detail) {
        Result r = fail(code, offset, op);
        r.detail = detail;
        return r;
    }

    public static Result validate(byte[] s, Config c) {
        ByteBuffer bb = ByteBuffer.wrap(s)
            .order(ByteOrder.LITTLE_ENDIAN);
        long len = s.length;

        // Row 1: OVER_SUBMIT_BYTES
        if (len > c.submitCap) {
            return fail("OVER_SUBMIT_BYTES", 0, "submit");
        }
        // Row 2: OVER_FRAME_BYTES
        if ((long) c.openBytes + len > c.frameByteCap) {
            return fail("OVER_FRAME_BYTES", 0, "submit");
        }
        // Row 3: TRUNCATED_HEADER
        if (len < 8) {
            return fail("TRUNCATED_HEADER", 0, "header");
        }
        // Row 4: BAD_MAGIC ("OGPU")
        if (u8(bb, 0) != 0x4F || u8(bb, 1) != 0x47 || u8(bb, 2) != 0x50 || u8(bb, 3) != 0x55) {
            return fail("BAD_MAGIC", 0, "header");
        }
        // Row 5: BAD_VERSION (this mod decodes 1 only)
        if (u8(bb, 4) != 1) {
            return fail("BAD_VERSION", 4, "header");
        }
        // Row 6: BAD_FLAGS
        if (u8(bb, 5) != 0) {
            return fail("BAD_FLAGS", 5, "header");
        }
        // Row 7: BAD_RESERVED
        if (u16(bb, 6) != 0) {
            return fail("BAD_RESERVED", 6, "header");
        }

        int segmentRecords = 0;
        long[] segmentFill = new long[4];
        int p = 8;
        while (true) {
            // Row 8: MISSING_END
            if (p == len) {
                return fail("MISSING_END", p, "END");
            }
            int opcode = u8(bb, p);
            // Row 9: TRAILING_BYTES
            if (opcode == END_OPCODE && p + 1 < len) {
                return fail("TRAILING_BYTES", p, "END");
            }
            if (opcode == END_OPCODE) {
                Result r = new Result();
                r.ok = true;
                r.code = "OK";
                r.offset = p;
                r.op = "END";
                r.detail = -1;
                r.records = segmentRecords;
                r.fill = segmentFill;
                return r;
            }
            Op op = lookup(opcode);
            // Row 10: UNKNOWN_OPCODE
            if (op == null) {
                return fail("UNKNOWN_OPCODE", p, String.format("0x%02X", opcode));
            }
            String name = op.name;
            boolean isClear = "CLEAR".equals(name);
            boolean isFillRect = "FILL_RECT".equals(name);
            boolean isBlit = "BLIT".equals(name);
            boolean isText = "TEXT".equals(name);
            // Row 11: TRUNCATED_RECORD (the fixed part)
            if ((long) p + op.fixedSize > len) {
                return fail("TRUNCATED_RECORD", p, name);
            }
            int n = isText ? u16(bb, p + 15) : 0;
            // Row 12: TEXT_TOO_LONG
            if (isText && n > TEXT_MAX_BYTES) {
                return fail("TEXT_TOO_LONG", p, name);
            }
            // Row 13: TRUNCATED_RECORD (TEXT's tail)
            if (isText && (long) p + 17 + n > len) {
                return fail("TRUNCATED_RECORD", p, name);
            }
            int recordSize = op.fixedSize + n;

            // Row 14: BAD_COLOUR, in layout order (CLEAR c; FILL_RECT c; TEXT fg then bg)
            if (isClear && !colourValid(u32(bb, p + 1), c.format)) {
                return fail("BAD_COLOUR", p, name);
            }
            if (isFillRect && !colourValid(u32(bb, p + 9), c.format)) {
                return fail("BAD_COLOUR", p, name);
            }
            if (isText && !colourValid(u32(bb, p + 5), c.format)) {
                return fail("BAD_COLOUR", p, name);
            }
            if (isText && !colourValid(u32(bb, p + 9), c.format)) {
                return fail("BAD_COLOUR", p, name);
            }
            // Rows 15-19: BLIT's handle; type is bits 27-30 of handle = type(4) | slot(11) | generation(16)
            long handle = isBlit ? u32(bb, p + 1) : 0;
            long type = (handle >>> 27) & 0xF;
            // Row 15: BAD_HANDLE (0, >= 2^31, or type 0)
            if (isBlit && (handle == 0 || handle >= 0x80000000L || type == 0)) {
                return fail("BAD_HANDLE", p, name);
            }
            // Row 16: UNKNOWN_TYPE (8-15)
            if (isBlit && type >= 8 && type <= 15) {
                return fail("UNKNOWN_TYPE", p, name);
            }
            // Row 17: WRONG_TYPE (2-7: buffer, render target, program, pipeline, kernel, compute list)
            if (isBlit && type >= 2 && type <= 7) {
                return fail("WRONG_TYPE", p, name);
            }
            int[] texture = isBlit ? c.textures.get(Long.valueOf(handle)) : null;
            // Row 18: STALE_HANDLE (type 1, but no live texture with exactly this handle)
            if (isBlit && type == 1 && texture == null) {
                return fail("STALE_HANDLE", p, name);
            }
            // Row 19: WRONG_FORMAT
            if (isBlit && texture[2] != c.format) {
                return fail("WRONG_FORMAT", p, name);
            }
            // Row 20: BAD_FLAGS_FIELD (BLIT flags at 17; TEXT flags at 13), then UNKNOWN_FONT (TEXT font at 14)
            if (isBlit && (u8(bb, p + 17) & 0xFE) != 0) {
                return fail("BAD_FLAGS_FIELD", p, name);
            }
            if (isText && (u8(bb, p + 13) & 0xFE) != 0) {
                return fail("BAD_FLAGS_FIELD", p, name);
            }
            if (isText && u8(bb, p + 14) != 0) {
                return fail("UNKNOWN_FONT", p, name);
            }
            // Row 21: BAD_UTF8, CONTROL_CHAR (one scan; the first offending sequence decides)
            long[] scan = isText ? scanText(bb, p + 17, n) : new long[] { 0, 0 };
            if (isText && scan[0] == 1) {
                return fail("BAD_UTF8", p, name, scan[1]);
            }
            if (isText && scan[0] == 2) {
                return fail("CONTROL_CHAR", p, name, scan[1]);
            }

            // Row 22: OVER_FRAME_RECORDS (END is not a record; empty records are)
            if ((long) c.openRecords + segmentRecords + 1 > c.frameRecordCap) {
                return fail("OVER_FRAME_RECORDS", p, name);
            }
            // Row 23: OVER_FRAME_FILL (the cap compares the sum over the four classes)
            long recordFill = 0;
            if (isClear) {
                recordFill = (long) c.width * c.height;
            }
            if (isFillRect) {
                recordFill = coveredRect(i16(bb, p + 1), i16(bb, p + 5), c.width)
                    * coveredRect(i16(bb, p + 3), i16(bb, p + 7), c.height);
            }
            if (isBlit) {
                int tw = texture[0], th = texture[1];
                int sx = i16(bb, p + 5), sy = i16(bb, p + 7), w = i16(bb, p + 9), h = i16(bb, p + 11);
                int dx = i16(bb, p + 13), dy = i16(bb, p + 15);
                recordFill = coveredBlit(sx, dx, w, tw, c.width) * coveredBlit(sy, dy, h, th, c.height);
            }
            if (isText) {
                recordFill = (long) CELL_W * CELL_H * scan[1];
            }
            long segmentFillSum = segmentFill[0] + segmentFill[1] + segmentFill[2] + segmentFill[3];
            boolean overFill;
            try {
                overFill = Math.addExact(Math.addExact(c.openFill, segmentFillSum), recordFill) > c.frameFillCap;
            } catch (ArithmeticException e) {
                overFill = true; // the sum passes Long.MAX_VALUE, so it is over any cap
            }
            if (overFill) {
                return fail("OVER_FRAME_FILL", p, name);
            }

            segmentRecords++;
            segmentFill[op.fillClass] += recordFill;
            p += recordSize;
        }
    }

    private NaiveValidator() {}
}
