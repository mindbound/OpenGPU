package io.github.mindbound.opengpu.core.cmd.bench;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.cmd.LiveView;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;

/**
 * The loads of the validation-throughput measurement (28 §8.1: one call's validation must fit the worker's 0.2 ms at
 * the 65 536-byte submit cap; RM:43): segments of exactly 4 096, 16 384 and 65 536 B on a T3 surface, with an
 * array-backed one-texture view that never boxes, as S7's {@code Device} must not (28 §3.5). The frame caps are
 * {@code (65536, 65536, 4096, Long.MAX_VALUE)}: T3's fill cap would stop the text-heavy loads, and what is measured
 * is the code path.
 * <ul>
 * <li>{@code records}: {@code FILL_RECT} (4x4) and {@code BLIT} (16x16) alternating, then two ASCII {@code TEXT}s
 * that bring the segment to its size; at 65 536 B that is 2 047 of each and two of n = 1 018, 4 096 records.</li>
 * <li>{@code ascii-text}: {@code TEXT}s of n = 1 024 ASCII and one shorter; at 65 536 B 62 and one of n = 968.</li>
 * <li>{@code cjk-text}: {@code TEXT}s of 341 three-byte codepoints (n = 1 023) and two ASCII ones; at 65 536 B 62,
 * and n = 507 and 506.</li>
 * <li>{@code reject-last}: {@code records} with its last record's fg invalid, the error path and its message.</li>
 * </ul>
 * Two more loads, of one opcode each, are for {@link ValidatorAllocationTest} and not timed: {@code clears}, 4 096
 * {@code CLEAR}s in 20 489 B, and {@code short-text}, 2 976 {@code TEXT}s of n = 1-8 in 65 481 B, whose codepoints
 * are 1, 2, 3 and 4 bytes long in turn. Each workload's expected totals are worked out from how it is built, not from
 * the validator.
 */
public final class ValidatorWorkloads {

    /** The one texture: a new device's first, 16x16 rgb565. */
    public static final int TEXTURE = 0x08000000;
    public static final SurfaceDesc SURFACE = SurfaceDesc.forTier(3);
    public static final FrameLimits LIMITS = new FrameLimits(65536, 65536, 4096, Long.MAX_VALUE);
    public static final int[] SIZES = { 65536, 16384, 4096 };

    private static final int COLOUR = 0xFF336699;
    private static final int BAD_FG = 0x00336699;
    private static final int PAIR = 13 + 18;
    private static final byte[] PANGRAM = "Sphinx of black quartz, judge my vow. ".getBytes(StandardCharsets.US_ASCII);

    private ValidatorWorkloads() {}

    /** One load: its name, size, bytes and what validation must give. */
    public static final class Workload {

        public final String name;
        public final byte[] segment;
        /** Null for a rejected load. */
        public final FrameTotals expected;
        /** The offset of the record a rejected load fails at, else -1. */
        public final int rejectedAt;

        Workload(String name, byte[] segment, FrameTotals expected, int rejectedAt) {
            this.name = name;
            this.segment = segment;
            this.expected = expected;
            this.rejectedAt = rejectedAt;
        }

        @Override
        public String toString() {
            return name + " " + segment.length + " B";
        }
    }

    /** {@link #TEXTURE} live as a 16x16 rgb565 texture on T3, from fields: nothing boxes or allocates. */
    public static final class OneTextureView implements LiveView {

        @Override
        public SurfaceDesc surface() {
            return SURFACE;
        }

        @Override
        public FrameLimits limits() {
            return LIMITS;
        }

        @Override
        public int textureFormat(int handle) {
            return handle == TEXTURE ? Formats.RGB565 : 0;
        }

        @Override
        public int textureWidth(int handle) {
            return 16;
        }

        @Override
        public int textureHeight(int handle) {
            return 16;
        }
    }

    /** The view's live handles, for the naive validator. */
    public static List<Integer> liveHandles() {
        return Collections.singletonList(TEXTURE);
    }

    /**
     * Every load at every size: records, ascii-text, cjk-text and reject-last, at 65 536, 16 384 and 4 096 B, the cap
     * first, so that the bench's one truly cold call is records at 65 536 B.
     */
    public static List<Workload> all() {
        List<Workload> all = new ArrayList<>();
        for (int size : SIZES) {
            all.add(records(size, false));
            all.add(asciiText(size));
            all.add(cjkText(size));
            all.add(records(size, true));
        }
        return all;
    }

    /**
     * {@code records} (or {@code reject-last}) of exactly size bytes: k FILL_RECT/BLIT pairs, with k the smallest
     * that leaves two TEXTs of at most 1 024 bytes for the rest, then those two TEXTs.
     */
    public static Workload records(int size, boolean rejectLast) {
        // 9 B of header and END; the two TEXTs take 34 + n1 + n2 <= 2 082
        int pairs = ceilDiv(size - 9 - 2082, PAIR);
        int rest = size - 9 - PAIR * pairs;
        int n1 = (rest - 34 + 1) / 2;
        int n2 = rest - 34 - n1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, TestEncoder.header());
        for (int i = 0; i < pairs; i++) {
            // Inside the surface: x <= 635, y <= 395 for the 4x4 fill, dx <= 623, dy <= 383 for the 16x16 blit
            write(out, TestEncoder.fill(i * 7 % 636, i * 13 % 396, 4, 4, COLOUR));
            write(out, TestEncoder.blit(TEXTURE, 0, 0, 16, 16, i * 11 % 624, i * 5 % 384, 1));
        }
        write(out, TestEncoder.text(0, 0, COLOUR, 0xFF000000, 0, 0, ascii(n1)));
        int last = out.size();
        write(out, TestEncoder.text(0, 13, rejectLast ? BAD_FG : COLOUR, 0xFF000000, 1, 0, ascii(n2)));
        write(out, TestEncoder.end());
        byte[] s = out.toByteArray();
        if (rejectLast) {
            return new Workload("reject-last", s, null, last);
        }
        FrameTotals expected = new FrameTotals(size, 2 * pairs + 2, 0, 16L * pairs, 256L * pairs, 78L * (n1 + n2));
        return new Workload("records", s, expected, -1);
    }

    /** {@code ascii-text} of exactly size bytes: TEXTs of n = 1 024 while they fit, then one of the rest. */
    public static Workload asciiText(int size) {
        int full = (size - 9 - 17) / 1041;
        int n = size - 9 - 1041 * full - 17;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, TestEncoder.header());
        for (int i = 0; i < full; i++) {
            write(out, TestEncoder.text(0, 13 * (i % 30), COLOUR, 0xFF000000, 0, 0, ascii(1024)));
        }
        write(out, TestEncoder.text(0, 0, COLOUR, 0xFF000000, 1, 0, ascii(n)));
        write(out, TestEncoder.end());
        long codepoints = 1024L * full + n;
        return new Workload(
            "ascii-text",
            out.toByteArray(),
            new FrameTotals(size, full + 1, 0, 0, 0, 78 * codepoints),
            -1);
    }

    /** {@code cjk-text} of exactly size bytes: TEXTs of 341 three-byte codepoints, then two ASCII TEXTs. */
    public static Workload cjkText(int size) {
        int full = (size - 9 - 34) / 1040;
        int rest = size - 9 - 1040 * full;
        int n1 = (rest - 34 + 1) / 2;
        int n2 = rest - 34 - n1;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, TestEncoder.header());
        for (int i = 0; i < full; i++) {
            write(out, TestEncoder.text(0, 13 * (i % 30), COLOUR, 0xFF000000, 0, 0, cjk(341, i)));
        }
        write(out, TestEncoder.text(0, 0, COLOUR, 0xFF000000, 1, 0, ascii(n1)));
        write(out, TestEncoder.text(0, 13, COLOUR, 0xFF000000, 1, 0, ascii(n2)));
        write(out, TestEncoder.end());
        long codepoints = 341L * full + n1 + n2;
        return new Workload(
            "cjk-text",
            out.toByteArray(),
            new FrameTotals(size, full + 2, 0, 0, 0, 78 * codepoints),
            -1);
    }

    /** {@code clears}: count {@code CLEAR}s, 8 + 5·count + 1 bytes; 4 096 of them, the record cap, are 20 489 B. */
    public static Workload clears(int count) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, TestEncoder.header());
        for (int i = 0; i < count; i++) {
            write(out, TestEncoder.clear(i % 2 == 0 ? COLOUR : 0xFF000000));
        }
        write(out, TestEncoder.end());
        byte[] s = out.toByteArray();
        return new Workload("clears", s, new FrameTotals(s.length, count, 640L * 400 * count, 0, 0, 0), -1);
    }

    /**
     * {@code short-text}: 93 rounds of 32 {@code TEXT}s, 2 976 in all. TEXT i holds 1 + (i / 4) mod (8 / w)
     * codepoints of w = 1 + i mod 4 bytes, so n is 1-8 and each round's 32 strings are 160 bytes and 80 codepoints:
     * 93 · (32 · 17 + 160) + 9 = 65 481 B.
     */
    public static Workload shortText() {
        int texts = 93 * 32;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, TestEncoder.header());
        long codepoints = 0;
        for (int i = 0; i < texts; i++) {
            int width = 1 + i % 4;
            int count = 1 + i / 4 % (8 / width);
            ByteArrayOutputStream string = new ByteArrayOutputStream();
            for (int k = 0; k < count; k++) {
                write(string, utf8(codepoint(width, i + k)));
            }
            write(out, TestEncoder.text(0, 13 * (i % 30), COLOUR, 0xFF000000, 0, 0, string.toByteArray()));
            codepoints += count;
        }
        write(out, TestEncoder.end());
        byte[] s = out.toByteArray();
        return new Workload("short-text", s, new FrameTotals(s.length, texts, 0, 0, 0, 78 * codepoints), -1);
    }

    /**
     * A printable codepoint whose UTF-8 form is width bytes: ASCII letters, U+0100-06FF, U+4E00-9FFF or
     * U+1F600-1F64F, none of them a control or a surrogate.
     */
    private static int codepoint(int width, int seed) {
        switch (width) {
            case 1:
                return 'A' + seed % 26;
            case 2:
                return 0x100 + seed * 7 % 0x600;
            case 3:
                return 0x4E00 + seed * 13 % 0x5200;
            default:
                return 0x1F600 + seed % 0x50;
        }
    }

    private static byte[] utf8(int codepoint) {
        return new String(Character.toChars(codepoint)).getBytes(StandardCharsets.UTF_8);
    }

    /** n printable ASCII bytes. */
    private static byte[] ascii(int n) {
        byte[] s = new byte[n];
        for (int i = 0; i < n; i++) {
            s[i] = PANGRAM[i % PANGRAM.length];
        }
        return s;
    }

    /** count codepoints from U+4E00 on, 3 bytes each. */
    private static byte[] cjk(int count, int seed) {
        byte[] s = new byte[3 * count];
        for (int i = 0; i < count; i++) {
            int cp = 0x4E00 + (seed * 341 + i) % 0x5200;
            s[3 * i] = (byte) (0xE0 | cp >> 12);
            s[3 * i + 1] = (byte) (0x80 | cp >> 6 & 0x3F);
            s[3 * i + 2] = (byte) (0x80 | cp & 0x3F);
        }
        return s;
    }

    private static int ceilDiv(int a, int b) {
        return a <= 0 ? 0 : (a + b - 1) / b;
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }
}
