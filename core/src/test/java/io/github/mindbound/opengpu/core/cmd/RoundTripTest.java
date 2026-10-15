package io.github.mindbound.opengpu.core.cmd;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.text;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.mindbound.opengpu.core.fixtures.MapLiveView;
import io.github.mindbound.opengpu.core.fixtures.ReEncoder;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;

/**
 * 28 §11.1's round trips: the test encoder's records read back through the main {@link SegmentReader} with their
 * fields intact, i16 edge values on every axis, colour edges of both formats, {@code TEXT} lengths and UTF-8 widths,
 * and the canonical form of 28 §3.2: every accepted segment decodes to one record list, and re-encoding it gives the
 * same bytes.
 */
class RoundTripTest {

    @Test
    void encodedFieldsDecode() {
        // 1 000 seeded random records per opcode, ten to a segment; the reader trusts its input, so any field value
        // and any string bytes go
        SplittableRandom r = new SplittableRandom(28);
        for (int op = 0; op < 4; op++) {
            for (int batch = 0; batch < 100; batch++) {
                ByteArrayOutputStream records = new ByteArrayOutputStream();
                List<String> expected = new ArrayList<>();
                for (int k = 0; k < 10; k++) {
                    byte[] record = randomRecord(op, r, expected);
                    records.write(record, 0, record.length);
                }
                byte[] s = segment(records.toByteArray());
                Assertions.assertEquals(expected, Recorder.read(s), TestEncoder.toHex(s));
            }
        }
    }

    static Stream<Arguments> i16Edges() {
        // 28 §11.1: -32 768, -1, 0, 1, W - 1, W, 32 767 for both surfaces' W, and their H for the y-axis fields
        int[] edges = { -32768, -1, 0, 1, 319, 320, 639, 640, 32767 };
        int[] yEdges = { 199, 200, 399, 400 };
        String[] fields = { "FILL_RECT.x", "FILL_RECT.y", "FILL_RECT.w", "FILL_RECT.h", "BLIT.sx", "BLIT.sy", "BLIT.w",
            "BLIT.h", "BLIT.dx", "BLIT.dy", "TEXT.x", "TEXT.y" };
        List<Arguments> cases = new ArrayList<>();
        for (String field : fields) {
            for (int v : edges) {
                cases.add(Arguments.of(field, v));
            }
            if (isYAxis(field)) {
                for (int v : yEdges) {
                    cases.add(Arguments.of(field, v));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("i16Edges")
    void i16EdgesDecodeExactlyAndAreValid(String field, int value) {
        // i16 values are never invalid (28 §5.1): accepted on T2 and T3 with valid colours and a live texture
        for (int tier = 2; tier <= 3; tier++) {
            MapLiveView view = Expectations.tier(tier);
            int colour = tier == 2 ? 200 : 0xFF00FF00;
            int[] f = defaults(field.substring(0, field.indexOf('.')));
            f[fieldIndex(field)] = value;
            byte[] record;
            String expected;
            if (field.startsWith("FILL_RECT")) {
                record = fill(f[0], f[1], f[2], f[3], colour);
                expected = Recorder.fillLine(f[0], f[1], f[2], f[3], colour);
            } else if (field.startsWith("BLIT")) {
                record = blit(Expectations.TEXTURE, f[0], f[1], f[2], f[3], f[4], f[5], 1);
                expected = Recorder.blitLine(Expectations.TEXTURE, f[0], f[1], f[2], f[3], f[4], f[5], 1);
            } else {
                record = text(f[0], f[1], colour, colour, 1, 0, "Ab");
                expected = Recorder.textLine(f[0], f[1], colour, colour, 1, 0, new byte[] { 'A', 'b' });
            }
            byte[] s = segment(record);
            Assertions.assertEquals(Arrays.asList(expected), Recorder.read(s));
            Expectations.assertAcceptedByBoth(s, view);
            assertCanonical(s);
        }
    }

    @Test
    void colourEdgesRoundTripAndAreAccepted() {
        int[] index8 = { 0, 1, 254, 255 };
        int[] rgb565 = { 0xFF000000, 0xFF0000FF, 0xFF00FF00, 0xFFFF0000, 0xFFFFFFFF, 0xFF7F7F7F };
        for (int tier = 2; tier <= 3; tier++) {
            MapLiveView view = Expectations.tier(tier);
            int other = tier == 2 ? 9 : 0xFF000000;
            for (int c : tier == 2 ? index8 : rgb565) {
                byte[] s = segment(
                    clear(c),
                    fill(1, 2, 3, 4, c),
                    text(0, 0, c, other, 0, 0, "x"),
                    text(0, 0, other, c, 0, 0, "y"));
                Assertions.assertEquals(
                    Arrays.asList(
                        Recorder.clearLine(c),
                        Recorder.fillLine(1, 2, 3, 4, c),
                        Recorder.textLine(0, 0, c, other, 0, 0, new byte[] { 'x' }),
                        Recorder.textLine(0, 0, other, c, 0, 0, new byte[] { 'y' })),
                    Recorder.read(s));
                long clearPx = tier == 2 ? 64000 : 256000;
                Expectations.assertAccepted(s, view, new FrameTotals(s.length, 4, clearPx, 12, 0, 156));
                assertCanonical(s);
            }
        }
    }

    @Test
    void textLengthsAndUtf8Widths() {
        MapLiveView t2 = Expectations.t2();
        byte[] ascii1024 = new byte[1024];
        Arrays.fill(ascii1024, (byte) '~');
        byte[] emoji1024 = new byte[1024];
        for (int i = 0; i < 1024; i += 4) {
            System.arraycopy(TestEncoder.hex("F0 9F 98 80"), 0, emoji1024, i, 4);
        }
        Object[][] cases = { { new byte[0], 0 }, { new byte[] { 'A' }, 1 }, { ascii1024, 1024 }, { emoji1024, 256 },
            { TestEncoder.hex("41"), 1 }, { TestEncoder.hex("C3 A9"), 1 }, { TestEncoder.hex("E2 82 AC"), 1 },
            { TestEncoder.hex("F0 9F 98 80"), 1 }, { TestEncoder.hex("41 C3 A9 E2 82 AC F0 9F 98 80"), 4 } };
        for (Object[] c : cases) {
            byte[] string = (byte[]) c[0];
            int codepoints = (Integer) c[1];
            byte[] s = segment(text(-3, 7, 15, 0, 1, 0, string));
            Assertions.assertEquals(8 + 17 + string.length + 1, s.length);
            Assertions.assertEquals(Arrays.asList(Recorder.textLine(-3, 7, 15, 0, 1, 0, string)), Recorder.read(s));
            Expectations.assertAccepted(s, t2, new FrameTotals(s.length, 1, 0, 0, 0, 78L * codepoints));
            assertCanonical(s);
        }
    }

    @Test
    void acceptedSegmentsAreCanonical() {
        // 28 §9.4's frames, on T2 and T3, and a mixed segment
        byte[] t2 = TestEncoder.hex(
            "4F 47 50 55 01 00 00 00 | 01 00 00 00 00 | 30 0A 00 14 00 64 00 32 00 C4 00 00 00 | "
                + "31 00 00 00 08 00 00 00 00 10 00 10 00 96 00 28 00 01 | "
                + "33 08 00 B4 00 FF 00 00 00 00 00 00 00 01 00 02 00 48 69 | FF");
        byte[] t3 = TestEncoder.hex(
            "4F 47 50 55 01 00 00 00 | 01 00 00 00 FF | 30 0A 00 14 00 64 00 32 00 00 80 FF FF | "
                + "31 00 00 00 08 00 00 00 00 10 00 10 00 96 00 28 00 01 | "
                + "33 08 00 B4 00 FF FF FF FF 00 00 00 FF 01 00 02 00 48 69 | FF");
        Expectations.assertAcceptedByBoth(t2, Expectations.t2());
        Expectations.assertAcceptedByBoth(t3, Expectations.t3());
        assertCanonical(t2);
        assertCanonical(t3);
        byte[] mixed = segment(
            fill(-32768, 32767, -1, 0, 255),
            blit(Expectations.TEXTURE, -1, -1, 32767, 32767, -32768, -32768, 0),
            text(32767, -32768, 0, 255, 0, 0, "ÿĀ�"),
            clear(128));
        Expectations.assertAcceptedByBoth(mixed, Expectations.t2());
        assertCanonical(mixed);
        assertCanonical(segment());
    }

    /** ReEncoder.reencode(s) equals s (28 §3.2, §11.1). */
    private static void assertCanonical(byte[] s) {
        Assertions.assertArrayEquals(s, ReEncoder.reencode(s), () -> "not canonical: " + TestEncoder.toHex(s));
    }

    private static boolean isYAxis(String field) {
        return field.endsWith(".y") || field.endsWith(".h") || field.endsWith(".sy") || field.endsWith(".dy");
    }

    /** Neutral values of an opcode's i16 fields, in layout order. */
    private static int[] defaults(String op) {
        if (op.equals("FILL_RECT")) {
            return new int[] { 1, 2, 3, 4 };
        }
        if (op.equals("BLIT")) {
            return new int[] { 0, 0, 4, 4, 5, 6 };
        }
        return new int[] { 7, 8 };
    }

    private static int fieldIndex(String field) {
        String name = field.substring(field.indexOf('.') + 1);
        List<String> order = field.startsWith("FILL_RECT") ? Arrays.asList("x", "y", "w", "h")
            : field.startsWith("BLIT") ? Arrays.asList("sx", "sy", "w", "h", "dx", "dy") : Arrays.asList("x", "y");
        return order.indexOf(name);
    }

    /** A random record of opcode index op (CLEAR, FILL_RECT, BLIT, TEXT), with its expected decoding appended. */
    private static byte[] randomRecord(int op, SplittableRandom r, List<String> expected) {
        switch (op) {
            case 0: {
                int c = r.nextInt();
                expected.add(Recorder.clearLine(c));
                return clear(c);
            }
            case 1: {
                int x = i16(r);
                int y = i16(r);
                int w = i16(r);
                int h = i16(r);
                int c = r.nextInt();
                expected.add(Recorder.fillLine(x, y, w, h, c));
                return fill(x, y, w, h, c);
            }
            case 2: {
                int tex = r.nextInt();
                int sx = i16(r);
                int sy = i16(r);
                int w = i16(r);
                int h = i16(r);
                int dx = i16(r);
                int dy = i16(r);
                int flags = r.nextInt(256);
                expected.add(Recorder.blitLine(tex, sx, sy, w, h, dx, dy, flags));
                return blit(tex, sx, sy, w, h, dx, dy, flags);
            }
            default: {
                int x = i16(r);
                int y = i16(r);
                int fg = r.nextInt();
                int bg = r.nextInt();
                int flags = r.nextInt(256);
                int font = r.nextInt(256);
                byte[] string = new byte[r.nextInt(10) == 0 ? 1024 : r.nextInt(40)];
                for (int i = 0; i < string.length; i++) {
                    string[i] = (byte) r.nextInt(256);
                }
                expected.add(Recorder.textLine(x, y, fg, bg, flags, font, string));
                return text(x, y, fg, bg, flags, font, string);
            }
        }
    }

    /** Edge-heavy i16 values. */
    private static int i16(SplittableRandom r) {
        if (r.nextInt(4) == 0) {
            int[] edges = { -32768, -32767, -1, 0, 1, 32766, 32767 };
            return edges[r.nextInt(edges.length)];
        }
        return r.nextInt(-32768, 32768);
    }

    /** Records what {@link SegmentReader} visits, one line per record. */
    private static final class Recorder implements OpVisitor {

        private final List<String> ops = new ArrayList<>();

        static List<String> read(byte[] s) {
            Recorder recorder = new Recorder();
            SegmentReader.read(s, recorder);
            return recorder.ops;
        }

        static String clearLine(int c) {
            return String.format(Locale.ROOT, "CLEAR %08X", c);
        }

        static String fillLine(int x, int y, int w, int h, int c) {
            return String.format(Locale.ROOT, "FILL_RECT %d %d %d %d %08X", x, y, w, h, c);
        }

        static String blitLine(int tex, int sx, int sy, int w, int h, int dx, int dy, int flags) {
            return String.format(Locale.ROOT, "BLIT %08X %d %d %d %d %d %d %d", tex, sx, sy, w, h, dx, dy, flags);
        }

        static String textLine(int x, int y, int fg, int bg, int flags, int font, byte[] string) {
            return String.format(
                Locale.ROOT,
                "TEXT %d %d %08X %08X %d %d [%s]",
                x,
                y,
                fg,
                bg,
                flags,
                font,
                TestEncoder.toHex(string));
        }

        @Override
        public void clear(int colour) {
            ops.add(clearLine(colour));
        }

        @Override
        public void fillRect(int x, int y, int w, int h, int colour) {
            ops.add(fillLine(x, y, w, h, colour));
        }

        @Override
        public void blit(int texture, int sx, int sy, int w, int h, int dx, int dy, int flags) {
            ops.add(blitLine(texture, sx, sy, w, h, dx, dy, flags));
        }

        @Override
        public void text(int x, int y, int fg, int bg, int flags, int font, byte[] segment, int stringOffset,
            int stringLength) {
            ops.add(
                textLine(
                    x,
                    y,
                    fg,
                    bg,
                    flags,
                    font,
                    Arrays.copyOfRange(segment, stringOffset, stringOffset + stringLength)));
        }
    }
}
