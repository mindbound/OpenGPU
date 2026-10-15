package io.github.mindbound.opengpu.core.naive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Self-test of the naive validator against note 28's own examples (§3.6, §9.4, §11.2), with its
 * own little-endian byte builders, so that the differential fuzzer's reference is checked before
 * it is trusted.
 */
final class NaiveValidatorSelfTest {

    // ------------------------------------------------------------------ tiny LE builders

    private static final class Le {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Le u8(int v) {
            out.write(v & 0xFF);
            return this;
        }

        Le i16(int v) {
            return u8(v).u8(v >> 8);
        }

        Le u32(long v) {
            return u8((int) v).u8((int) (v >>> 8))
                .u8((int) (v >>> 16))
                .u8((int) (v >>> 24));
        }

        Le raw(byte[] b) {
            out.write(b, 0, b.length);
            return this;
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    private static final long TEX = 0x08000000L;

    private static byte[] header() {
        return new Le().u8('O')
            .u8('G')
            .u8('P')
            .u8('U')
            .u8(1)
            .u8(0)
            .i16(0)
            .bytes();
    }

    private static final byte[] END = { (byte) 0xFF };

    private static byte[] clear(long c) {
        return new Le().u8(0x01)
            .u32(c)
            .bytes();
    }

    private static byte[] fill(int x, int y, int w, int h, long c) {
        return new Le().u8(0x30)
            .i16(x)
            .i16(y)
            .i16(w)
            .i16(h)
            .u32(c)
            .bytes();
    }

    private static byte[] blit(long t, int sx, int sy, int w, int h, int dx, int dy, int flags) {
        return new Le().u8(0x31)
            .u32(t)
            .i16(sx)
            .i16(sy)
            .i16(w)
            .i16(h)
            .i16(dx)
            .i16(dy)
            .u8(flags)
            .bytes();
    }

    private static byte[] text(int x, int y, long fg, long bg, int flags, int font, byte[] str) {
        return new Le().u8(0x33)
            .i16(x)
            .i16(y)
            .u32(fg)
            .u32(bg)
            .u8(flags)
            .u8(font)
            .i16(str.length)
            .raw(str)
            .bytes();
    }

    private static byte[] text(byte[] str) {
        return text(0, 0, 1, 0, 0, 0, str);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] b(int... v) {
        byte[] out = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (byte) v[i];
        }
        return out;
    }

    private static byte[] cat(byte[]... parts) {
        Le le = new Le();
        for (byte[] part : parts) {
            le.raw(part);
        }
        return le.bytes();
    }

    private static byte[] seg(byte[]... records) {
        Le le = new Le().raw(header());
        for (byte[] r : records) {
            le.raw(r);
        }
        return le.raw(END)
            .bytes();
    }

    private static byte[] cut(byte[] a, int drop) {
        return Arrays.copyOf(a, a.length - drop);
    }

    private static byte[] hex(String s) {
        String[] parts = s.trim()
            .split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return out;
    }

    // ------------------------------------------------------------------ devices (28 §8.1)

    private static NaiveValidator.Config t2() {
        NaiveValidator.Config c = new NaiveValidator.Config();
        c.format = 1;
        c.width = 320;
        c.height = 200;
        c.submitCap = 65536;
        c.frameByteCap = 131072;
        c.frameRecordCap = 4096;
        c.frameFillCap = 1024000;
        c.textures.put(TEX, new int[] { 16, 16, 1 });
        return c;
    }

    private static NaiveValidator.Config t3() {
        NaiveValidator.Config c = new NaiveValidator.Config();
        c.format = 2;
        c.width = 640;
        c.height = 400;
        c.submitCap = 65536;
        c.frameByteCap = 262144;
        c.frameRecordCap = 4096;
        c.frameFillCap = 4096000;
        c.textures.put(TEX, new int[] { 16, 16, 2 });
        return c;
    }

    private static NaiveValidator.Result run(byte[] s, NaiveValidator.Config c) {
        return NaiveValidator.validate(s, c);
    }

    private static void error(NaiveValidator.Result r, String code, int offset, String op) {
        String what = r.toString();
        assertFalse(r.ok, what);
        assertEquals(code, r.code, what);
        assertEquals(offset, r.offset, what);
        assertEquals(op, r.op, what);
        assertEquals(0, r.records, what);
        assertArrayEquals(new long[4], r.fill, what);
    }

    private static void error(NaiveValidator.Result r, String code, int offset, String op, long detail) {
        error(r, code, offset, op);
        assertEquals(detail, r.detail, r.toString());
    }

    private static void accepted(NaiveValidator.Result r, int records, long clear, long fill, long copy, long set) {
        String what = r.toString();
        assertTrue(r.ok, what);
        assertEquals("OK", r.code, what);
        assertEquals("END", r.op, what);
        assertEquals(-1, r.detail, what);
        assertEquals(records, r.records, what);
        assertArrayEquals(new long[] { clear, fill, copy, set }, r.fill, what);
    }

    // ------------------------------------------------------------------ 28 §9.4

    private static final String T2_EXAMPLE_HEX = "4F 47 50 55 01 00 00 00 " + "01 00 00 00 00 "
        + "30 0A 00 14 00 64 00 32 00 C4 00 00 00 "
        + "31 00 00 00 08 00 00 00 00 10 00 10 00 96 00 28 00 01 "
        + "33 08 00 B4 00 FF 00 00 00 00 00 00 00 01 00 02 00 48 69 "
        + "FF";

    @Test
    void workedExampleT2() {
        byte[] built = seg(
            clear(0),
            fill(10, 20, 100, 50, 196),
            blit(TEX, 0, 0, 16, 16, 150, 40, 1),
            text(8, 180, 255, 0, 1, 0, ascii("Hi")));
        byte[] fromNote = hex(T2_EXAMPLE_HEX);
        assertEquals(64, fromNote.length);
        assertArrayEquals(fromNote, built);
        NaiveValidator.Result r = run(fromNote, t2());
        accepted(r, 4, 64000, 5000, 256, 156);
        assertEquals(63, r.offset);
    }

    @Test
    void workedExampleT3AndIndexOperandsOnT3() {
        byte[] s = seg(
            clear(0xFF000000L),
            fill(10, 20, 100, 50, 0xFFFF8000L),
            blit(TEX, 0, 0, 16, 16, 150, 40, 1),
            text(8, 180, 0xFFFFFFFFL, 0xFF000000L, 1, 0, ascii("Hi")));
        assertEquals(64, s.length);
        accepted(run(s, t3()), 4, 256000, 5000, 256, 156);
        error(run(hex(T2_EXAMPLE_HEX), t3()), "BAD_COLOUR", 8, "CLEAR");
    }

    // ------------------------------------------------------------------ whole-segment caps and header

    @Test
    void frameByteCapBeforeHeader() {
        NaiveValidator.Config c = t2();
        c.openBytes = 131071;
        error(run(b('O', 'G'), c), "OVER_FRAME_BYTES", 0, "submit");
        c.openBytes = 131070;
        error(run(b('O', 'G'), c), "TRUNCATED_HEADER", 0, "header");
        c.openBytes = 131072 - 9;
        accepted(run(seg(), c), 0, 0, 0, 0, 0);
        c.openBytes = 131072 - 8;
        error(run(seg(), c), "OVER_FRAME_BYTES", 0, "submit");
    }

    @Test
    void submitCap() {
        NaiveValidator.Config c = t2();
        c.openBytes = 131071;
        error(run(new byte[65537], c), "OVER_SUBMIT_BYTES", 0, "submit");
        c.openBytes = 0;
        error(run(new byte[65536], c), "BAD_MAGIC", 0, "header");
    }

    @Test
    void headerFields() {
        for (int len = 0; len < 8; len++) {
            error(run(Arrays.copyOf(header(), len), t2()), "TRUNCATED_HEADER", 0, "header");
        }
        error(run(header(), t2()), "MISSING_END", 8, "END");
        for (int i = 0; i < 4; i++) {
            byte[] s = seg();
            s[i] ^= 0x20;
            error(run(s, t2()), "BAD_MAGIC", 0, "header");
        }
        for (int v : new int[] { 0, 2, 255 }) {
            byte[] s = seg();
            s[4] = (byte) v;
            error(run(s, t2()), "BAD_VERSION", 4, "header");
        }
        for (int bit = 0; bit < 8; bit++) {
            byte[] s = seg();
            s[5] = (byte) (1 << bit);
            error(run(s, t2()), "BAD_FLAGS", 5, "header");
        }
        for (int v : new int[] { 1, 0x8000 }) {
            byte[] s = seg();
            s[6] = (byte) v;
            s[7] = (byte) (v >> 8);
            error(run(s, t2()), "BAD_RESERVED", 6, "header");
        }
    }

    // ------------------------------------------------------------------ framing

    @Test
    void trailingBytesAndUnknownOpcodes() {
        error(run(cat(header(), END, b(0x00)), t2()), "TRAILING_BYTES", 8, "END");
        error(run(cat(header(), END, END), t2()), "TRAILING_BYTES", 8, "END");
        int[] unknown = { 0x00, 0x02, 0x32, 0x40, 0xAB, 0xFE };
        String[] names = { "0x00", "0x02", "0x32", "0x40", "0xAB", "0xFE" };
        for (int i = 0; i < unknown.length; i++) {
            error(run(seg(b(unknown[i])), t2()), "UNKNOWN_OPCODE", 8, names[i]);
        }
        error(run(seg(clear(0), b(0x32)), t2()), "UNKNOWN_OPCODE", 13, "0x32");
    }

    @Test
    void cutRecordsSwallowingEnd() {
        byte[] f8 = fill(0, 0, 1, 1, 5);
        error(run(cat(header(), cut(f8, 1), END), t2()), "BAD_COLOUR", 8, "FILL_RECT");
        byte[] f5 = fill(0, 0, 1, 1, 0xFF000005L);
        error(run(cat(header(), cut(f5, 1), END), t3()), "MISSING_END", 21, "END");
        error(run(cat(header(), cut(f8, 2), END), t2()), "TRUNCATED_RECORD", 8, "FILL_RECT");
    }

    @Test
    void everyRecordCutAtEveryByteWithoutEnd() {
        byte[][] records = { clear(0), fill(1, 2, 3, 4, 5), blit(TEX, 0, 0, 1, 1, 0, 0, 0), text(ascii("ab")) };
        String[] names = { "CLEAR", "FILL_RECT", "BLIT", "TEXT" };
        for (int k = 0; k < records.length; k++) {
            for (int keep = 1; keep < records[k].length; keep++) {
                byte[] s = cat(header(), Arrays.copyOf(records[k], keep));
                error(run(s, t2()), "TRUNCATED_RECORD", 8, names[k]);
            }
            error(run(cat(header(), records[k]), t2()), "MISSING_END", 8 + records[k].length, "END");
        }
    }

    @Test
    void textStructureBeforeFields() {
        byte[] t = text(0, 0, 0x100, 0, 0, 0, ascii("ab"));
        error(run(cat(header(), cut(t, 1)), t2()), "TRUNCATED_RECORD", 8, "TEXT");
        byte[] a1025 = new byte[1025];
        Arrays.fill(a1025, (byte) 'a');
        error(run(seg(text(a1025)), t2()), "TEXT_TOO_LONG", 8, "TEXT");
        byte[] a1024 = Arrays.copyOf(a1025, 1024);
        accepted(run(seg(text(a1024)), t2()), 1, 0, 0, 0, 78L * 1024);
        accepted(run(seg(text(new byte[0])), t2()), 1, 0, 0, 0, 0);
    }

    // ------------------------------------------------------------------ colours

    @Test
    void colours() {
        error(run(seg(clear(0x100)), t2()), "BAD_COLOUR", 8, "CLEAR");
        error(run(seg(clear(0xFF000000L)), t2()), "BAD_COLOUR", 8, "CLEAR");
        accepted(run(seg(clear(255)), t2()), 1, 64000, 0, 0, 0);
        error(run(seg(clear(0x00FFFFFFL)), t3()), "BAD_COLOUR", 8, "CLEAR");
        error(run(seg(clear(0xFEFFFFFFL)), t3()), "BAD_COLOUR", 8, "CLEAR");
        error(run(seg(fill(0, 0, 0, 0, 0x100)), t2()), "BAD_COLOUR", 8, "FILL_RECT");
        error(run(seg(text(0, 0, 1, 0x100, 0, 0, ascii("a"))), t2()), "BAD_COLOUR", 8, "TEXT");
        error(run(seg(text(0, 0, 0x100, 0, 2, 1, b(0xC0))), t2()), "BAD_COLOUR", 8, "TEXT");
        error(run(seg(clear(0), clear(0x100)), t2()), "BAD_COLOUR", 13, "CLEAR");
    }

    // ------------------------------------------------------------------ handles and flags

    @Test
    void blitHandles() {
        long[] bad = { 0L, 0x80000000L, 0x00000005L, 0x07FFFFFFL, 0xFFFFFFFFL };
        for (long h : bad) {
            error(run(seg(blit(h, 0, 0, 1, 1, 0, 0, 0)), t2()), "BAD_HANDLE", 8, "BLIT");
        }
        for (long h : new long[] { 0x48000000L, 0x40000000L, 0x7FFFFFFFL }) {
            error(run(seg(blit(h, 0, 0, 1, 1, 0, 0, 0)), t2()), "UNKNOWN_TYPE", 8, "BLIT");
        }
        for (long h : new long[] { 0x10000000L, 0x38000000L }) {
            error(run(seg(blit(h, 0, 0, 1, 1, 0, 0, 0)), t2()), "WRONG_TYPE", 8, "BLIT");
        }
        for (long h : new long[] { 0x08000001L, 0x08010000L }) {
            error(run(seg(blit(h, 0, 0, 1, 1, 0, 0, 0)), t2()), "STALE_HANDLE", 8, "BLIT");
        }
        error(run(seg(blit(0x08000001L, 0, 0, 1, 1, 0, 0, 2)), t2()), "STALE_HANDLE", 8, "BLIT");
        accepted(run(seg(blit(TEX, 0, 0, 1, 1, 0, 0, 1)), t2()), 1, 0, 0, 1, 0);
    }

    @Test
    void wrongFormatBeforeFlags() {
        NaiveValidator.Config c = t2();
        c.textures.put(TEX, new int[] { 16, 16, 2 });
        error(run(seg(blit(TEX, 0, 0, 1, 1, 0, 0, 2)), c), "WRONG_FORMAT", 8, "BLIT");
    }

    @Test
    void flagsAndFonts() {
        for (int f : new int[] { 0x02, 0x80 }) {
            error(run(seg(blit(TEX, 0, 0, 1, 1, 0, 0, f)), t2()), "BAD_FLAGS_FIELD", 8, "BLIT");
            error(run(seg(text(0, 0, 1, 0, f, 0, ascii("a"))), t2()), "BAD_FLAGS_FIELD", 8, "TEXT");
        }
        for (int font : new int[] { 1, 255 }) {
            error(run(seg(text(0, 0, 1, 0, 0, font, ascii("a"))), t2()), "UNKNOWN_FONT", 8, "TEXT");
        }
        error(run(seg(text(0, 0, 1, 0, 2, 1, ascii("a"))), t2()), "BAD_FLAGS_FIELD", 8, "TEXT");
        error(run(seg(text(0, 0, 1, 0, 0, 1, b(0xC0, 0x80))), t2()), "UNKNOWN_FONT", 8, "TEXT");
    }

    // ------------------------------------------------------------------ UTF-8 (28 §7.2)

    @Test
    void textStringScan() {
        error(run(seg(text(ascii("a\nb"))), t2()), "CONTROL_CHAR", 8, "TEXT", 1);
        error(run(seg(text(b(0xC0, 0x80))), t2()), "BAD_UTF8", 8, "TEXT", 0);
        int[][] bad = { { 0xC1, 0xBF }, { 0xE0, 0x80, 0x80 }, { 0xE0, 0x9F, 0xBF }, { 0xED, 0xA0, 0x80 },
            { 0xED, 0xBF, 0xBF }, { 0xF0, 0x80, 0x80, 0x80 }, { 0xF0, 0x8F, 0xBF, 0xBF }, { 0xF4, 0x90, 0x80, 0x80 },
            { 0xF5, 0x80, 0x80, 0x80 }, { 0xFE }, { 0xFF }, { 0x80 }, { 0xC2 }, { 0xE2, 0x82 }, { 0xF0, 0x9F, 0x98 },
            { 0xE2, 0x82, 0x41 }, { 0xC0, 0x01 }, };
        for (int[] s : bad) {
            error(run(seg(text(b(s))), t2()), "BAD_UTF8", 8, "TEXT", 0);
            error(run(seg(text(cat(ascii("xy"), b(s)))), t2()), "BAD_UTF8", 8, "TEXT", 2);
        }
        int[][] controls = { { 0x00 }, { 0x0A }, { 0x1F }, { 0x7F }, { 0xC2, 0x80 }, { 0xC2, 0x85 }, { 0xC2, 0x9F },
            { 0x01, 0xC0 } };
        for (int[] s : controls) {
            error(run(seg(text(b(s))), t2()), "CONTROL_CHAR", 8, "TEXT", 0);
            error(run(seg(text(cat(ascii("xyz"), b(s)))), t2()), "CONTROL_CHAR", 8, "TEXT", 3);
        }
        int[][] good = { { 0x20 }, { 0x7E }, { 0xC2, 0xA0 }, { 0xCD, 0xB8 }, { 0xDF, 0xBF }, { 0xE0, 0xA0, 0x80 },
            { 0xED, 0x9F, 0xBF }, { 0xEE, 0x80, 0x80 }, { 0xEF, 0xBB, 0xBF }, { 0xEF, 0xBF, 0xBE },
            { 0xEF, 0xBF, 0xBF }, { 0xF0, 0x90, 0x80, 0x80 }, { 0xF3, 0xB0, 0x80, 0x80 }, { 0xF4, 0x8F, 0xBF, 0xBF }, };
        for (int[] s : good) {
            accepted(run(seg(text(b(s))), t2()), 1, 0, 0, 0, 78);
            accepted(run(seg(text(cat(ascii("ab"), b(s), ascii("c")))), t2()), 1, 0, 0, 0, 78 * 4);
        }
    }

    @Test
    void sequenceCutByTheStringEndNeverReadsPastN() {
        error(run(cat(header(), text(b(0xE2, 0x82))), t2()), "BAD_UTF8", 8, "TEXT", 0);
        error(run(cat(header(), text(b(0x61, 0xF0, 0x9F, 0x98))), t2()), "BAD_UTF8", 8, "TEXT", 1);
        error(run(cat(header(), text(b(0xC2)), b(0x80), END), t2()), "BAD_UTF8", 8, "TEXT", 0);
        error(run(cat(header(), text(b(0xC2)), b(0xA0), END), t2()), "BAD_UTF8", 8, "TEXT", 0);
    }

    // ------------------------------------------------------------------ fill and the frame caps

    @Test
    void fillClipping() {
        accepted(run(seg(fill(-5, -5, 10, 10, 1)), t2()), 1, 0, 25, 0, 0);
        accepted(run(seg(fill(315, 195, 100, 100, 1)), t2()), 1, 0, 25, 0, 0);
        accepted(run(seg(fill(0, 0, -1, 10, 1)), t2()), 1, 0, 0, 0, 0);
        accepted(run(seg(fill(32767, 32767, 32767, 32767, 1)), t2()), 1, 0, 0, 0, 0);
        accepted(run(seg(fill(-32768, -32768, 32767, 32767, 1)), t2()), 1, 0, 0, 0, 0);
        accepted(run(seg(fill(0, 0, 32767, 32767, 1)), t2()), 1, 0, 64000, 0, 0);
        accepted(run(seg(blit(TEX, 2, 0, 16, 16, -4, 195, 0)), t2()), 1, 0, 0, 50, 0);
        accepted(run(seg(blit(TEX, -3, -3, 16, 16, 0, 0, 1)), t2()), 1, 0, 0, 169, 0);
        accepted(run(seg(blit(TEX, 16, 0, 16, 16, 0, 0, 0)), t2()), 1, 0, 0, 0, 0);
        accepted(run(seg(blit(TEX, 0, 0, 16, 16, 320, 0, 0)), t2()), 1, 0, 0, 0, 0);
        accepted(run(seg(text(-1000, -1000, 1, 0, 0, 0, ascii("abc"))), t2()), 1, 0, 0, 0, 234);
    }

    @Test
    void recordCap() {
        NaiveValidator.Config c = t2();
        c.openRecords = 4096;
        accepted(run(seg(), c), 0, 0, 0, 0, 0);
        error(run(seg(fill(0, 0, 0, 0, 1)), c), "OVER_FRAME_RECORDS", 8, "FILL_RECT");
        error(run(seg(clear(0x100)), c), "BAD_COLOUR", 8, "CLEAR");
        c.openFill = c.frameFillCap;
        error(run(seg(clear(0)), c), "OVER_FRAME_RECORDS", 8, "CLEAR");
        c = t2();
        c.openRecords = 4094;
        accepted(run(seg(fill(0, 0, 0, 0, 1), fill(0, 0, 0, 0, 1)), c), 2, 0, 0, 0, 0);
        error(run(seg(fill(0, 0, 0, 0, 1), fill(0, 0, 0, 0, 1), clear(0)), c), "OVER_FRAME_RECORDS", 34, "CLEAR");
    }

    @Test
    void fillCap() {
        NaiveValidator.Config c = t2();
        c.openFill = 1024000 - 64000;
        accepted(run(seg(clear(0)), c), 1, 64000, 0, 0, 0);
        c.openFill = 1024000 - 63999;
        error(run(seg(clear(0)), c), "OVER_FRAME_FILL", 8, "CLEAR");
        c = t2();
        c.frameFillCap = 69000;
        accepted(run(seg(clear(0), fill(10, 20, 100, 50, 1)), c), 2, 64000, 5000, 0, 0);
        c.frameFillCap = 68999;
        error(run(seg(clear(0), fill(10, 20, 100, 50, 1)), c), "OVER_FRAME_FILL", 13, "FILL_RECT");
        c = t2();
        c.frameFillCap = 0;
        accepted(
            run(seg(fill(0, 0, 0, 5, 1), text(new byte[0]), blit(TEX, 0, 0, 16, 16, 320, 0, 0)), c),
            3,
            0,
            0,
            0,
            0);
        error(run(seg(text(ascii("a"))), c), "OVER_FRAME_FILL", 8, "TEXT");
    }

    // ------------------------------------------------------------------ independence (28 §11.3 P2)

    @Test
    void importsNothingFromTheProject() throws IOException {
        String needle = String.join(".", "io", "github", "mindbound", "opengpu");
        String dir = needle.replace('.', '/') + "/core/naive/";
        Path root = sourceRoot(dir + "NaiveValidator.java");
        for (String file : new String[] { "NaiveValidator.java", "NaiveValidatorSelfTest.java" }) {
            Path path = root.resolve(dir + file);
            assertTrue(Files.isRegularFile(path), "missing " + path.toAbsolutePath());
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int packages = 0;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith("package ")) {
                    assertEquals("package " + needle + ".core.naive;", line, file + ":" + (i + 1));
                    packages++;
                    continue;
                }
                assertFalse(line.contains(needle), file + ":" + (i + 1) + ": " + line);
            }
            assertEquals(1, packages, file);
        }
    }

    /**
     * The test source root: the naive.srcRoot property if set, else src/test/java (Gradle runs the
     * tests in core/) or core/src/test/java (the repository root), relative to user.dir. Fails if
     * neither holds the file, so the check never passes without reading the sources.
     */
    private static Path sourceRoot(String file) {
        String property = System.getProperty("naive.srcRoot");
        if (property != null) {
            return Paths.get(property);
        }
        Path base = Paths.get(System.getProperty("user.dir"));
        List<Path> tried = new ArrayList<>();
        for (String candidate : new String[] { "src/test/java", "core/src/test/java" }) {
            Path root = base.resolve(candidate);
            if (Files.isRegularFile(root.resolve(file))) {
                return root;
            }
            tried.add(root.toAbsolutePath());
        }
        return fail("no test source root holds " + file + ": tried " + tried + "; set -Dnaive.srcRoot");
    }
}
