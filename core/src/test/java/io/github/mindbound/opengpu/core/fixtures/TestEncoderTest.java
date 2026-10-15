package io.github.mindbound.opengpu.core.fixtures;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * The Java test encoder against an independent {@code ByteBuffer} packing of 28 §5.2's layouts, on 20 000 seeded
 * random and edge inputs (luabuilders.py's edge sets), plus 28 §9.1-§9.2's mirror rules: i16 operands wrap modulo
 * 2^16, flags and font reduce to a byte, and n is the string's byte length.
 */
class TestEncoderTest {

    /** luabuilders.py's i16 edges: −32 768, −1, 0, 1, T2's W − 1 and W, 32 767. */
    private static final int[] I16_EDGES = { -32768, -1, 0, 1, 319, 320, 32767 };
    /** luabuilders.py's u32 edges: colours of both formats and handles. */
    private static final int[] U32_EDGES = { 0, 1, 255, 0xFF000000, 0xFFFFFFFF, 0x08000000, 0x7FFFFFFF };
    /** Flags and font values, with 0x101 and 0x1FF to show the byte reduction. */
    private static final int[] U8_EDGES = { 0, 1, 2, 0x80, 0xFF, 0x101, 0x1FF };

    @Test
    void recordsMatchAnIndependentPacking() {
        SplittableRandom r = new SplittableRandom(633);
        for (int n = 0; n < 20000; n++) {
            switch (n % 4) {
                case 0: {
                    int c = u32(r);
                    assertRecord(
                        le(5).put((byte) 0x01)
                            .putInt(c),
                        TestEncoder.clear(c));
                    break;
                }
                case 1: {
                    int x = i16(r);
                    int y = i16(r);
                    int w = i16(r);
                    int h = i16(r);
                    int c = u32(r);
                    assertRecord(
                        le(13).put((byte) 0x30)
                            .putShort((short) x)
                            .putShort((short) y)
                            .putShort((short) w)
                            .putShort((short) h)
                            .putInt(c),
                        TestEncoder.fill(x, y, w, h, c));
                    break;
                }
                case 2: {
                    int tex = u32(r);
                    int sx = i16(r);
                    int sy = i16(r);
                    int w = i16(r);
                    int h = i16(r);
                    int dx = i16(r);
                    int dy = i16(r);
                    int flags = u8(r);
                    assertRecord(
                        le(18).put((byte) 0x31)
                            .putInt(tex)
                            .putShort((short) sx)
                            .putShort((short) sy)
                            .putShort((short) w)
                            .putShort((short) h)
                            .putShort((short) dx)
                            .putShort((short) dy)
                            .put((byte) flags),
                        TestEncoder.blit(tex, sx, sy, w, h, dx, dy, flags));
                    break;
                }
                default: {
                    int x = i16(r);
                    int y = i16(r);
                    int fg = u32(r);
                    int bg = u32(r);
                    int flags = u8(r);
                    int font = u8(r);
                    byte[] s = string(r);
                    assertRecord(
                        le(17 + s.length).put((byte) 0x33)
                            .putShort((short) x)
                            .putShort((short) y)
                            .putInt(fg)
                            .putInt(bg)
                            .put((byte) flags)
                            .put((byte) font)
                            .putShort((short) s.length)
                            .put(s),
                        TestEncoder.text(x, y, fg, bg, flags, font, s));
                    break;
                }
            }
        }
    }

    @Test
    void i16OperandsWrapAndBytesReduce() {
        // 28 §9.1-§9.2: v % 65536 for an i16, so -1 is FF FF and 65 541 is 05 00; flags 0x101 is 01
        Assertions.assertEquals("FF FF", TestEncoder.toHex(TestEncoder.le16(-1)));
        Assertions.assertEquals("05 00", TestEncoder.toHex(TestEncoder.le16(65541)));
        Assertions.assertEquals(
            "30 FF FF 05 00 00 80 FF 7F 05 00 00 00",
            TestEncoder.toHex(TestEncoder.fill(-1, 65541, -32768, 32767, 5)));
        byte[] blit = TestEncoder.blit(0x08000000, 0, 0, 1, 1, 0, 0, 0x101);
        Assertions.assertEquals(0x01, blit[17]);
        byte[] text = TestEncoder.text(0, 0, 1, 2, 0x101, 0x1FF, "");
        Assertions.assertEquals(0x01, text[13]);
        Assertions.assertEquals((byte) 0xFF, text[14]);
        Assertions.assertEquals("78 56 34 12", TestEncoder.toHex(TestEncoder.le32(0x12345678)));
    }

    @Test
    void theHeaderAndEnd() {
        // 28 §3.1: "OGPU", version 1, flags 0, reserved u16 0; END is FF
        Assertions.assertEquals("4F 47 50 55 01 00 00 00", TestEncoder.toHex(TestEncoder.header()));
        Assertions.assertEquals("FF", TestEncoder.toHex(TestEncoder.end()));
        Assertions.assertEquals("4F 47 50 55 01 00 00 00 FF", TestEncoder.toHex(TestEncoder.segment()));
        Assertions.assertEquals("4F 47 50 55 02 80 00 80", TestEncoder.toHex(TestEncoder.header(2, 0x80, 0x8000)));
        Assertions.assertEquals("4F 47 50 55 01 00 01 00", TestEncoder.toHex(TestEncoder.header(0x101, 0x100, 1)));
    }

    @Test
    void theWorkedExample() {
        // 28 §9.4 (28:833-838): the T2 frame, 64 B
        byte[] s = TestEncoder.segment(
            TestEncoder.clear(0),
            TestEncoder.fill(10, 20, 100, 50, 196),
            TestEncoder.blit(0x08000000, 0, 0, 16, 16, 150, 40, 1),
            TestEncoder.text(8, 180, 255, 0, 1, 0, "Hi"));
        Assertions.assertArrayEquals(
            TestEncoder.hex(
                "4F 47 50 55 01 00 00 00 | 01 00 00 00 00 | 30 0A 00 14 00 64 00 32 00 C4 00 00 00 |"
                    + " 31 00 00 00 08 00 00 00 00 10 00 10 00 96 00 28 00 01 |"
                    + " 33 08 00 B4 00 FF 00 00 00 00 00 00 00 01 00 02 00 48 69 | FF"),
            s);
        Assertions.assertEquals(64, s.length);
    }

    @Test
    void textCountsBytes() {
        // n counts bytes, as Lua's # does (28 §7.2): "é€😀" is 2 + 3 + 4 = 9
        byte[] text = TestEncoder.text(0, 0, 0, 0, 0, 0, "é€😀");
        Assertions.assertEquals(17 + 9, text.length);
        Assertions.assertEquals("09 00", TestEncoder.toHex(new byte[] { text[15], text[16] }));
        Assertions
            .assertEquals("C3 A9 E2 82 AC F0 9F 98 80", TestEncoder.toHex(Arrays.copyOfRange(text, 17, text.length)));
        Assertions.assertArrayEquals(
            TestEncoder.text(1, 2, 3, 4, 0, 0, "Hi".getBytes(StandardCharsets.UTF_8)),
            TestEncoder.text(1, 2, 3, 4, 0, 0, "Hi"));
        // the largest u16 count fits, one more does not
        byte[] longest = TestEncoder.text(0, 0, 0, 0, 0, 0, new byte[65535]);
        Assertions.assertEquals(17 + 65535, longest.length);
        Assertions.assertEquals((byte) 0xFF, longest[15]);
        Assertions.assertEquals((byte) 0xFF, longest[16]);
        Assertions
            .assertThrows(IllegalArgumentException.class, () -> TestEncoder.text(0, 0, 0, 0, 0, 0, new byte[65536]));
    }

    @Test
    void theRawHelpers() {
        byte[] s = TestEncoder.segment(TestEncoder.clear(0));
        Assertions.assertEquals("4F 47 50 55 01 00 00 00 01 00 00 00 00 FF", TestEncoder.toHex(s));
        Assertions.assertEquals("01 FF 00", TestEncoder.toHex(TestEncoder.bytes(1, -1, 256)));
        Assertions.assertEquals(
            "01 02 03",
            TestEncoder.toHex(TestEncoder.concat(TestEncoder.bytes(1), TestEncoder.bytes(2, 3))));
        Assertions.assertEquals(
            "4F 47 50 55 01 00 00 00 01 AA BB 00 00 FF",
            TestEncoder.toHex(TestEncoder.with(s, 9, 0xAA, 0xBB)));
        Assertions.assertEquals(
            "4F 47 50 55 01 00 00 00 01 34 12 00 00 FF",
            TestEncoder.toHex(TestEncoder.withLe16(s, 9, 0x1234)));
        Assertions.assertEquals(
            "4F 47 50 55 01 00 00 00 01 78 56 34 12 FF",
            TestEncoder.toHex(TestEncoder.withLe32(s, 9, 0x12345678)));
        // the patches are copies
        Assertions.assertEquals("4F 47 50 55 01 00 00 00 01 00 00 00 00 FF", TestEncoder.toHex(s));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> TestEncoder.with(s, 13, 1, 2));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> TestEncoder.withLe32(s, -1, 0));
        Assertions.assertEquals("4F 47", TestEncoder.toHex(TestEncoder.head(s, 2)));
        Assertions.assertEquals(0, TestEncoder.head(s, 0).length);
        Assertions.assertArrayEquals(s, TestEncoder.head(s, s.length));
        Assertions.assertThrows(IllegalArgumentException.class, () -> TestEncoder.head(s, s.length + 1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> TestEncoder.head(s, -1));
        Assertions.assertArrayEquals(TestEncoder.bytes(0x4F, 0xab, 1), TestEncoder.hex("4F|ab, 01"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> TestEncoder.hex("4F 4"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> TestEncoder.hex("4 F"));
        Assertions.assertThrows(IllegalArgumentException.class, () -> TestEncoder.hex("4G"));
    }

    @Test
    void everyCallReturnsAFreshArray() {
        byte[] a = TestEncoder.header();
        a[0] = 0;
        Assertions.assertEquals(0x4F, TestEncoder.header()[0]);
        Assertions.assertNotSame(TestEncoder.end(), TestEncoder.end());
        Assertions.assertNotSame(TestEncoder.clear(0), TestEncoder.clear(0));
    }

    private static void assertRecord(ByteBuffer expected, byte[] actual) {
        Assertions.assertFalse(expected.hasRemaining(), "the packing filled its record");
        Assertions.assertEquals(TestEncoder.toHex(expected.array()), TestEncoder.toHex(actual));
    }

    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size)
            .order(ByteOrder.LITTLE_ENDIAN);
    }

    /** An edge half the time, else any 32-bit value: in range, or one that wraps. */
    private static int i16(SplittableRandom r) {
        switch (r.nextInt(4)) {
            case 0:
            case 1:
                return I16_EDGES[r.nextInt(I16_EDGES.length)];
            case 2:
                return r.nextInt(-32768, 32768);
            default:
                return r.nextInt();
        }
    }

    private static int u32(SplittableRandom r) {
        return r.nextBoolean() ? U32_EDGES[r.nextInt(U32_EDGES.length)] : r.nextInt();
    }

    private static int u8(SplittableRandom r) {
        return r.nextBoolean() ? U8_EDGES[r.nextInt(U8_EDGES.length)] : r.nextInt(512);
    }

    /** n = 0, 1 or 1 024 (28 §11.1) now and then, else 0-40 bytes of any value, malformed UTF-8 included. */
    private static byte[] string(SplittableRandom r) {
        int n;
        switch (r.nextInt(8)) {
            case 0:
                n = 0;
                break;
            case 1:
                n = 1;
                break;
            case 2:
                n = 1024;
                break;
            default:
                n = r.nextInt(41);
                break;
        }
        byte[] s = new byte[n];
        for (int i = 0; i < n; i++) {
            s[i] = (byte) r.nextInt(256);
        }
        return s;
    }
}
