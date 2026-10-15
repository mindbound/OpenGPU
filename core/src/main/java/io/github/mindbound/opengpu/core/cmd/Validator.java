package io.github.mindbound.opengpu.core.cmd;

import java.util.Locale;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.colour.Colours;

/**
 * The segment validator of 28 §3.5. It checks a whole segment against the live view and the open frame's totals and
 * returns its totals or exactly one error: the first that 28 §3.6's order reaches, with that table's code, offset and
 * {@code <OP>}. It is a pure function: it never writes the segment (C14), the view or the totals, and the caller
 * commits only an accepted result, so a rejected submit changes nothing. It never throws for any bytes, makes one
 * linear pass (every read is bounds-checked by rows 8, 11 and 13 first, p grows each record, and each string byte is
 * scanned once), and allocates nothing per record: only the result, and an error's reason text.
 */
public final class Validator {

    private Validator() {}

    /** {@link #validate(byte[], LiveView, FrameTotals)} for the first submit of a frame. */
    public static Validation validate(byte[] segment, LiveView view) {
        return validate(segment, view, FrameTotals.EMPTY);
    }

    /**
     * Validates the segment whole (28 §3.5): the submit and frame byte caps, the header field by field, then each
     * record's structure, its fields in layout order and its accounting against the frame caps.
     *
     * @param segment the submit's array, which is never written
     * @param view    the device's live view; its methods must not throw
     * @param open    the open frame's totals before this segment
     */
    public static Validation validate(byte[] segment, LiveView view, FrameTotals open) {
        byte[] s = segment;
        int n = s.length;
        FrameLimits limits = view.limits();

        // 28 §3.6 rows 1-2, before any header check
        if (n > limits.submitBytes) {
            return reject(
                ErrorCode.OVER_SUBMIT_BYTES,
                0,
                StreamError.NO_OPCODE,
                n,
                format("%d bytes exceed the %d-byte cap", n, limits.submitBytes));
        }
        long frameBytes = (long) open.bytes + n;
        if (frameBytes > limits.frameBytes) {
            return reject(
                ErrorCode.OVER_FRAME_BYTES,
                0,
                StreamError.NO_OPCODE,
                frameBytes,
                format("%d bytes with the open frame exceed the %d-byte frame cap", frameBytes, limits.frameBytes));
        }

        // Rows 3-7: the header, field by field (28 §3.1)
        if (n < Header.SIZE) {
            return reject(
                ErrorCode.TRUNCATED_HEADER,
                0,
                StreamError.NO_OPCODE,
                n,
                format("segment of %d bytes is shorter than the 8-byte header", n));
        }
        if (LittleEndian.u8(s, 0) != Header.MAGIC_0 || LittleEndian.u8(s, 1) != Header.MAGIC_1
            || LittleEndian.u8(s, 2) != Header.MAGIC_2
            || LittleEndian.u8(s, 3) != Header.MAGIC_3) {
            return reject(
                ErrorCode.BAD_MAGIC,
                0,
                StreamError.NO_OPCODE,
                Integer.toUnsignedLong(LittleEndian.u32(s, 0)),
                "bad magic, expected \"OGPU\"");
        }
        int version = LittleEndian.u8(s, Header.OFFSET_VERSION);
        if (!Header.isDecoded(version)) {
            return reject(
                ErrorCode.BAD_VERSION,
                Header.OFFSET_VERSION,
                StreamError.NO_OPCODE,
                version,
                format("unsupported command format %d (this mod decodes %s)", version, Header.decodedVersions()));
        }
        int flags = LittleEndian.u8(s, Header.OFFSET_FLAGS);
        if (flags != 0) {
            return reject(
                ErrorCode.BAD_FLAGS,
                Header.OFFSET_FLAGS,
                StreamError.NO_OPCODE,
                flags,
                format("flags 0x%02X must be 0", flags));
        }
        int reserved = LittleEndian.u16(s, Header.OFFSET_RESERVED);
        if (reserved != 0) {
            return reject(
                ErrorCode.BAD_RESERVED,
                Header.OFFSET_RESERVED,
                StreamError.NO_OPCODE,
                reserved,
                format("reserved 0x%04X must be 0", reserved));
        }

        // The version selects the record loop (28 §4.2); M0 decodes v1 only
        return recordsV1(s, view, open, limits);
    }

    /** Rows 8-23 from p = 8, and the accepted totals. */
    private static Validation recordsV1(byte[] s, LiveView view, FrameTotals open, FrameLimits limits) {
        int n = s.length;
        SurfaceDesc surface = view.surface();
        int format = surface.format;
        int width = surface.width;
        int height = surface.height;
        // What this segment may add under rows 22 and 23; differences of non-negative values cannot overflow, and a
        // negative budget (an open frame already over its cap) fails the first record, as the plain sums would
        int recordBudget = limits.frameRecords - open.records;
        long fillBudget = limits.frameFill - open.totalPixels();

        int p = Header.SIZE;
        int records = 0;
        long segmentFill = 0;
        long clear = 0;
        long fill = 0;
        long copy = 0;
        long set = 0;
        while (true) {
            // Rows 8-10: END handling, then the opcode lookup
            if (p == n) {
                return reject(ErrorCode.MISSING_END, p, Opcodes.END, 0, "segment ends without END");
            }
            int op = LittleEndian.u8(s, p);
            if (op == Opcodes.END) {
                if (p + 1 < n) {
                    int after = n - p - 1;
                    return reject(
                        ErrorCode.TRAILING_BYTES,
                        p,
                        Opcodes.END,
                        after,
                        format("END is not the last byte (%d after it)", after));
                }
                return Validation.ok(new FrameTotals(n, records, clear, fill, copy, set));
            }
            int size = Opcodes.fixedSize(op);
            if (size == 0) {
                return reject(ErrorCode.UNKNOWN_OPCODE, p, op, op, "unknown opcode");
            }
            // Row 11: the fixed part fits
            int left = n - p;
            if (size > left) {
                return truncated(p, op, size, left);
            }

            long pixels;
            switch (op) {
                case Opcodes.CLEAR: {
                    int c = LittleEndian.u32(s, p + Opcodes.CLEAR_COLOUR);
                    if (!Colours.isValid(format, c)) {
                        return badColour(p, op, format, "colour", c);
                    }
                    pixels = (long) width * height;
                    break;
                }
                case Opcodes.FILL_RECT: {
                    int c = LittleEndian.u32(s, p + Opcodes.FILL_COLOUR);
                    if (!Colours.isValid(format, c)) {
                        return badColour(p, op, format, "colour", c);
                    }
                    pixels = Clip.rectArea(
                        LittleEndian.i16(s, p + Opcodes.FILL_X),
                        LittleEndian.i16(s, p + Opcodes.FILL_Y),
                        LittleEndian.i16(s, p + Opcodes.FILL_W),
                        LittleEndian.i16(s, p + Opcodes.FILL_H),
                        width,
                        height);
                    break;
                }
                case Opcodes.BLIT: {
                    // Rows 15-19, then 20 (28 §5.5)
                    int handle = LittleEndian.u32(s, p + Opcodes.BLIT_TEX);
                    ErrorCode handleError = TextureHandles.check(handle, view);
                    if (handleError != null) {
                        return reject(
                            handleError,
                            p,
                            op,
                            Integer.toUnsignedLong(handle),
                            TextureHandles.reason(handleError, handle));
                    }
                    int textureFormat = view.textureFormat(handle);
                    if (textureFormat != format) {
                        return reject(
                            ErrorCode.WRONG_FORMAT,
                            p,
                            op,
                            Integer.toUnsignedLong(handle),
                            format(
                                "texture 0x%08X is %s, the surface is %s",
                                handle,
                                formatName(textureFormat),
                                formatName(format)));
                    }
                    int blitFlags = LittleEndian.u8(s, p + Opcodes.BLIT_FLAGS);
                    if ((blitFlags & ~Opcodes.BLIT_FLAGS_DEFINED) != 0) {
                        return badFlags(p, op, blitFlags);
                    }
                    pixels = Clip.blitArea(
                        LittleEndian.i16(s, p + Opcodes.BLIT_SX),
                        LittleEndian.i16(s, p + Opcodes.BLIT_SY),
                        LittleEndian.i16(s, p + Opcodes.BLIT_W),
                        LittleEndian.i16(s, p + Opcodes.BLIT_H),
                        LittleEndian.i16(s, p + Opcodes.BLIT_DX),
                        LittleEndian.i16(s, p + Opcodes.BLIT_DY),
                        view.textureWidth(handle),
                        view.textureHeight(handle),
                        width,
                        height);
                    break;
                }
                case Opcodes.TEXT: {
                    // Rows 12-13: the count's limit, then the tail fits
                    int count = LittleEndian.u16(s, p + Opcodes.TEXT_COUNT);
                    if (count > Opcodes.TEXT_MAX_BYTES) {
                        return reject(
                            ErrorCode.TEXT_TOO_LONG,
                            p,
                            op,
                            count,
                            format("string of %d bytes exceeds the %d-byte limit", count, Opcodes.TEXT_MAX_BYTES));
                    }
                    size = Opcodes.TEXT_FIXED_SIZE + count;
                    if (size > left) {
                        return truncated(p, op, size, left);
                    }
                    // fg, then bg, then the flags, then the font, then the string (28 §3.6, §5.6)
                    int fg = LittleEndian.u32(s, p + Opcodes.TEXT_FG);
                    if (!Colours.isValid(format, fg)) {
                        return badColour(p, op, format, "fg", fg);
                    }
                    int bg = LittleEndian.u32(s, p + Opcodes.TEXT_BG);
                    if (!Colours.isValid(format, bg)) {
                        return badColour(p, op, format, "bg", bg);
                    }
                    int textFlags = LittleEndian.u8(s, p + Opcodes.TEXT_FLAGS);
                    if ((textFlags & ~Opcodes.TEXT_FLAGS_DEFINED) != 0) {
                        return badFlags(p, op, textFlags);
                    }
                    int font = LittleEndian.u8(s, p + Opcodes.TEXT_FONT);
                    if (!Fonts.isDefined(font)) {
                        return reject(
                            ErrorCode.UNKNOWN_FONT,
                            p,
                            op,
                            font,
                            format("unknown font %d (this release defines font 0)", font));
                    }
                    long scan = Utf8.scan(s, p + Opcodes.TEXT_STRING, count);
                    if (scan < 0) {
                        int index = Utf8.errorIndex(scan);
                        if (Utf8.isBadUtf8(scan)) {
                            return reject(
                                ErrorCode.BAD_UTF8,
                                p,
                                op,
                                index,
                                format("invalid UTF-8 at string byte %d", index));
                        }
                        return reject(
                            ErrorCode.CONTROL_CHAR,
                            p,
                            op,
                            index,
                            format("control character U+%04X at string byte %d", Utf8.errorCodepoint(scan), index));
                    }
                    // One unclipped 6x13 cell per codepoint (28 §3.5, §7.3)
                    pixels = scan * Fonts.FONT_0_FILL_PER_CODEPOINT;
                    break;
                }
                default:
                    // fixedSize is non-zero for END and the four opcodes above only
                    return reject(ErrorCode.UNKNOWN_OPCODE, p, op, op, "unknown opcode");
            }

            // Rows 22-23: accounting, after the field checks; END is never counted, empty records are
            if (records >= recordBudget) {
                long total = (long) open.records + records + 1;
                return reject(
                    ErrorCode.OVER_FRAME_RECORDS,
                    p,
                    op,
                    total,
                    format("%d records exceed the frame's %d-record cap", total, limits.frameRecords));
            }
            if (pixels > fillBudget - segmentFill) {
                // The reported total saturates at Long.MAX_VALUE, which open totals near it under as large a cap
                // would pass; base cannot overflow, since segmentFill is within the budget
                long base = open.totalPixels() + segmentFill;
                long total = pixels > Long.MAX_VALUE - base ? Long.MAX_VALUE : base + pixels;
                return reject(
                    ErrorCode.OVER_FRAME_FILL,
                    p,
                    op,
                    total,
                    format("%d px of fill exceed the frame's %d-px cap", total, limits.frameFill));
            }
            records++;
            segmentFill += pixels;
            switch (op) {
                case Opcodes.CLEAR:
                    clear += pixels;
                    break;
                case Opcodes.FILL_RECT:
                    fill += pixels;
                    break;
                case Opcodes.BLIT:
                    copy += pixels;
                    break;
                default:
                    set += pixels;
                    break;
            }
            p += size;
        }
    }

    private static Validation truncated(int p, int op, int size, int left) {
        return reject(
            ErrorCode.TRUNCATED_RECORD,
            p,
            op,
            size,
            format("record needs %d bytes, only %d left", size, left));
    }

    /** Row 14; the reason extends 28:264's with the field and the value. */
    private static Validation badColour(int p, int op, int format, String field, int colour) {
        String reason = format == Formats.RGB565
            ? format("%s 0x%08X: alpha must be 0xFF before blending (M1)", field, colour)
            : format("%s 0x%08X is not an index 0-255", field, colour);
        return reject(ErrorCode.BAD_COLOUR, p, op, Integer.toUnsignedLong(colour), reason);
    }

    /** Row 20's first check, for {@code BLIT} and {@code TEXT}. */
    private static Validation badFlags(int p, int op, int flags) {
        return reject(ErrorCode.BAD_FLAGS_FIELD, p, op, flags, format("flags 0x%02X set undefined bits", flags));
    }

    /** A format's name for a message; never throws, since a view could report an undefined code. */
    private static String formatName(int format) {
        return Formats.isDefined(format) ? Formats.name(format) : "format " + format;
    }

    private static Validation reject(ErrorCode code, int offset, int opcode, long detail, String reason) {
        return Validation.rejected(new StreamError(code, offset, opcode, detail, reason));
    }

    private static String format(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }
}
