package io.github.mindbound.opengpu.core.cmd;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;

/**
 * The constants of 28 §3.1, §4.4, §5.2, §6.1, §6.2 and §7.3 as {@code core.cmd} holds them: the opcode table and its
 * layouts, the header, the format and handle-type codes, font 0's cell, and the closed set of error codes with their
 * rows of 28 §3.6.
 */
class OpcodesTest {

    @Test
    void theFiveOpcodesHaveTheirSizesNamesAndClasses() {
        // 28 §5.2: CLEAR 5, FILL_RECT 13, BLIT 18, TEXT 17 + n, END 1
        assertOp(0x01, "CLEAR", 5, EnergyClass.CLEAR);
        assertOp(0x30, "FILL_RECT", 13, EnergyClass.FILL);
        assertOp(0x31, "BLIT", 18, EnergyClass.COPY);
        assertOp(0x33, "TEXT", 17, EnergyClass.SET);
        assertOp(0xFF, "END", 1, null);
        Assertions.assertEquals(0x01, Opcodes.CLEAR);
        Assertions.assertEquals(0x30, Opcodes.FILL_RECT);
        Assertions.assertEquals(0x31, Opcodes.BLIT);
        Assertions.assertEquals(0x33, Opcodes.TEXT);
        Assertions.assertEquals(0xFF, Opcodes.END);
    }

    @Test
    void everyOtherValueIsUnassigned() {
        List<Integer> assigned = Arrays.asList(0x01, 0x30, 0x31, 0x33, 0xFF);
        int unassigned = 0;
        for (int op = 0; op < 256; op++) {
            if (assigned.contains(op)) {
                continue;
            }
            unassigned++;
            Assertions.assertEquals(0, Opcodes.fixedSize(op), "0x" + Integer.toHexString(op));
            Assertions.assertNull(Opcodes.name(op));
            Assertions.assertNull(Opcodes.op(op));
            Assertions.assertNull(Opcodes.energyClass(op));
            // 28 §3.6's <OP> for an unassigned opcode, upper-case hex
            Assertions.assertEquals(String.format(Locale.ROOT, "0x%02X", op), Opcodes.token(op));
        }
        Assertions.assertEquals(251, unassigned);
        // 0x00 is never assigned (28 §3.2, C2)
        Assertions.assertEquals("0x00", Opcodes.token(0x00));
        Assertions.assertEquals("0xFE", Opcodes.token(0xFE));
        // Values outside a byte name nothing
        Assertions.assertEquals(0, Opcodes.fixedSize(-1));
        Assertions.assertEquals(0, Opcodes.fixedSize(256));
        Assertions.assertEquals(0, Opcodes.fixedSize(0x101));
        Assertions.assertNull(Opcodes.op(-1));
        Assertions.assertNull(Opcodes.name(0x133));
    }

    @Test
    void allListsTheOpcodesInOrder() {
        List<Opcodes.Op> all = Opcodes.all();
        Assertions.assertEquals(5, all.size());
        int[] codes = { 0x01, 0x30, 0x31, 0x33, 0xFF };
        for (int i = 0; i < codes.length; i++) {
            Assertions.assertEquals(codes[i], all.get(i).code);
            Assertions.assertSame(all.get(i), Opcodes.op(codes[i]));
        }
        Assertions.assertThrows(UnsupportedOperationException.class, () -> all.add(all.get(0)));
        Assertions
            .assertThrows(UnsupportedOperationException.class, () -> all.get(0).fields.add(all.get(1).fields.get(0)));
    }

    @Test
    void theLayoutsAre28sTable() {
        // 28 §5.2, field by field: name, offset, type
        assertFields(Opcodes.CLEAR, "c COLOUR@1");
        assertFields(Opcodes.FILL_RECT, "x I16@1", "y I16@3", "w I16@5", "h I16@7", "c COLOUR@9");
        assertFields(
            Opcodes.BLIT,
            "tex HANDLE@1",
            "sx I16@5",
            "sy I16@7",
            "w I16@9",
            "h I16@11",
            "dx I16@13",
            "dy I16@15",
            "flags U8@17");
        assertFields(
            Opcodes.TEXT,
            "x I16@1",
            "y I16@3",
            "fg COLOUR@5",
            "bg COLOUR@9",
            "flags U8@13",
            "font U8@14",
            "n U16@15",
            "s BYTES@17");
        assertFields(Opcodes.END);
        Assertions.assertEquals(15, Opcodes.op(Opcodes.TEXT).countOffset);
        for (int code : new int[] { Opcodes.CLEAR, Opcodes.FILL_RECT, Opcodes.BLIT, Opcodes.END }) {
            Assertions.assertEquals(-1, Opcodes.op(code).countOffset);
        }
    }

    @Test
    void eachLayoutIsContiguousFromOffset1AndSumsToItsSize() {
        for (Opcodes.Op op : Opcodes.all()) {
            int next = 1;
            for (Opcodes.Field field : op.fields) {
                Assertions.assertEquals(next, field.offset, op.name + "." + field.name);
                next += field.type.size;
            }
            Assertions.assertEquals(op.fixedSize, next, op.name);
        }
    }

    @Test
    void theOffsetConstantsMatchTheTable() {
        Assertions.assertEquals(1, Opcodes.CLEAR_COLOUR);
        int[] fill = { Opcodes.FILL_X, Opcodes.FILL_Y, Opcodes.FILL_W, Opcodes.FILL_H, Opcodes.FILL_COLOUR };
        int[] blit = { Opcodes.BLIT_TEX, Opcodes.BLIT_SX, Opcodes.BLIT_SY, Opcodes.BLIT_W, Opcodes.BLIT_H,
            Opcodes.BLIT_DX, Opcodes.BLIT_DY, Opcodes.BLIT_FLAGS };
        int[] text = { Opcodes.TEXT_X, Opcodes.TEXT_Y, Opcodes.TEXT_FG, Opcodes.TEXT_BG, Opcodes.TEXT_FLAGS,
            Opcodes.TEXT_FONT, Opcodes.TEXT_COUNT, Opcodes.TEXT_STRING };
        assertOffsets(Opcodes.FILL_RECT, fill);
        assertOffsets(Opcodes.BLIT, blit);
        assertOffsets(Opcodes.TEXT, text);
        Assertions.assertEquals(5, Opcodes.CLEAR_SIZE);
        Assertions.assertEquals(13, Opcodes.FILL_RECT_SIZE);
        Assertions.assertEquals(18, Opcodes.BLIT_SIZE);
        Assertions.assertEquals(17, Opcodes.TEXT_FIXED_SIZE);
        Assertions.assertEquals(1, Opcodes.END_SIZE);
        Assertions.assertEquals(1024, Opcodes.TEXT_MAX_BYTES);
    }

    @Test
    void theFlagBitsAreBit0Only() {
        // 28 §5.5, §5.6: KEY0 and TRANSPARENT_BG; bits 1-2 are M1's scale and stay undefined in v1
        Assertions.assertEquals(0x01, Opcodes.BLIT_KEY0);
        Assertions.assertEquals(0x01, Opcodes.BLIT_FLAGS_DEFINED);
        Assertions.assertEquals(0x01, Opcodes.TEXT_TRANSPARENT_BG);
        Assertions.assertEquals(0x01, Opcodes.TEXT_FLAGS_DEFINED);
        Assertions.assertEquals(0xFE, ~Opcodes.BLIT_FLAGS_DEFINED & 0xFF);
    }

    @Test
    void theTestEncodersRecordsHaveTheTablesSizes() {
        Assertions.assertEquals(Opcodes.fixedSize(Opcodes.CLEAR), TestEncoder.clear(0).length);
        Assertions.assertEquals(Opcodes.fixedSize(Opcodes.FILL_RECT), TestEncoder.fill(0, 0, 0, 0, 0).length);
        Assertions.assertEquals(Opcodes.fixedSize(Opcodes.BLIT), TestEncoder.blit(0, 0, 0, 0, 0, 0, 0, 0).length);
        Assertions
            .assertEquals(Opcodes.fixedSize(Opcodes.TEXT), TestEncoder.text(0, 0, 0, 0, 0, 0, new byte[0]).length);
        Assertions
            .assertEquals(Opcodes.fixedSize(Opcodes.TEXT) + 5, TestEncoder.text(0, 0, 0, 0, 0, 0, "Hello").length);
        Assertions.assertEquals(Opcodes.fixedSize(Opcodes.END), TestEncoder.end().length);
        Assertions.assertEquals(Header.SIZE, TestEncoder.header().length);
        Assertions.assertEquals(Header.MIN_SEGMENT, TestEncoder.segment().length);
        // Each builder's first byte is its opcode
        Assertions.assertEquals(Opcodes.CLEAR, TestEncoder.clear(0)[0]);
        Assertions.assertEquals(Opcodes.FILL_RECT, TestEncoder.fill(0, 0, 0, 0, 0)[0]);
        Assertions.assertEquals(Opcodes.BLIT, TestEncoder.blit(0, 0, 0, 0, 0, 0, 0, 0)[0]);
        Assertions.assertEquals(Opcodes.TEXT, TestEncoder.text(0, 0, 0, 0, 0, 0, "")[0]);
        Assertions.assertEquals(Opcodes.END, TestEncoder.end()[0] & 0xFF);
    }

    @Test
    void theHeaderConstants() {
        // 28 §3.1: "OGPU", version 1, flags 0, reserved u16 0; 9 bytes is the shortest segment
        Assertions.assertEquals(8, Header.SIZE);
        Assertions.assertArrayEquals(
            new int[] { 'O', 'G', 'P', 'U' },
            new int[] { Header.MAGIC_0, Header.MAGIC_1, Header.MAGIC_2, Header.MAGIC_3 });
        Assertions.assertEquals(4, Header.OFFSET_VERSION);
        Assertions.assertEquals(5, Header.OFFSET_FLAGS);
        Assertions.assertEquals(6, Header.OFFSET_RESERVED);
        Assertions.assertEquals(1, Header.VERSION);
        Assertions.assertEquals(9, Header.MIN_SEGMENT);
        Assertions.assertEquals("1", Header.decodedVersions());
        for (int version = 0; version < 256; version++) {
            Assertions.assertEquals(version == 1, Header.isDecoded(version), "version " + version);
        }
        Assertions.assertArrayEquals(TestEncoder.hex("4F 47 50 55 01 00 00 00"), TestEncoder.header());
    }

    @Test
    void theFormatCodes() {
        // 28 §6.2: 1 index8 (1 B), 2 rgb565 (2 B), 0 invalid, 3 and up reserved for M2
        Assertions.assertEquals(0, Formats.INVALID);
        Assertions.assertEquals(1, Formats.INDEX8);
        Assertions.assertEquals(2, Formats.RGB565);
        Assertions.assertTrue(Formats.isDefined(1));
        Assertions.assertTrue(Formats.isDefined(2));
        for (int format : new int[] { -1, 0, 3, 4, 255 }) {
            Assertions.assertFalse(Formats.isDefined(format));
            Assertions.assertThrows(IllegalArgumentException.class, () -> Formats.bytesPerPixel(format));
            Assertions.assertThrows(IllegalArgumentException.class, () -> Formats.name(format));
        }
        Assertions.assertEquals(1, Formats.bytesPerPixel(Formats.INDEX8));
        Assertions.assertEquals(2, Formats.bytesPerPixel(Formats.RGB565));
        Assertions.assertEquals("index8", Formats.name(Formats.INDEX8));
        Assertions.assertEquals("rgb565", Formats.name(Formats.RGB565));
        Assertions.assertEquals(Formats.INDEX8, Formats.byName("index8"));
        Assertions.assertEquals(Formats.RGB565, Formats.byName("rgb565"));
        Assertions.assertEquals(Formats.INVALID, Formats.byName(null));
        Assertions.assertEquals(Formats.INVALID, Formats.byName("RGB565"));
        Assertions.assertEquals(Formats.INVALID, Formats.byName("rgba8"));
    }

    @Test
    void theHandleTypes() {
        // 28 §6.1's table, and the kinds WRONG_TYPE names
        String[] kinds = { null, "texture", "buffer", "render target", "program", "pipeline", "kernel",
            "compute list" };
        for (int type = 0; type < 16; type++) {
            Assertions.assertEquals(type >= 1 && type <= 7, HandleTypes.isAssigned(type), "type " + type);
            Assertions.assertEquals(type < kinds.length ? kinds[type] : null, HandleTypes.kindName(type));
        }
        Assertions.assertEquals(0, HandleTypes.NONE);
        Assertions.assertEquals(1, HandleTypes.TEXTURE);
        Assertions.assertEquals(2, HandleTypes.BUFFER);
        Assertions.assertEquals(3, HandleTypes.RENDER_TARGET);
        Assertions.assertEquals(4, HandleTypes.PROGRAM);
        Assertions.assertEquals(5, HandleTypes.PIPELINE);
        Assertions.assertEquals(6, HandleTypes.KERNEL);
        Assertions.assertEquals(7, HandleTypes.COMPUTE_LIST);
        Assertions.assertEquals(8, HandleTypes.FIRST_UNASSIGNED);
        Assertions.assertNull(HandleTypes.kindName(-1));
        // A new device's first texture is 0x08000000 (28 §6.1)
        Assertions.assertEquals(0x08000000, Handles.pack(HandleTypes.TEXTURE, 0, 0));
        Assertions.assertEquals(0x10000000, Handles.pack(HandleTypes.BUFFER, 0, 0));
    }

    @Test
    void font0sCell() {
        // 28 §7.3: 6x13, 78 px of fill per codepoint; M0 defines font 0 only
        Assertions.assertEquals(0, Fonts.FONT_0);
        Assertions.assertEquals(6, Fonts.FONT_0_CELL_WIDTH);
        Assertions.assertEquals(13, Fonts.FONT_0_CELL_HEIGHT);
        Assertions.assertEquals(78, Fonts.FONT_0_FILL_PER_CODEPOINT);
        Assertions.assertEquals(6 * 13, Fonts.FONT_0_FILL_PER_CODEPOINT);
        for (int font = 0; font < 256; font++) {
            Assertions.assertEquals(font == 0, Fonts.isDefined(font));
        }
    }

    @Test
    void theErrorCodesAre28sClosedSetInOrder() {
        // 28 §3.6: 24 codes in evaluation order, with their rows
        String[] names = { "OVER_SUBMIT_BYTES", "OVER_FRAME_BYTES", "TRUNCATED_HEADER", "BAD_MAGIC", "BAD_VERSION",
            "BAD_FLAGS", "BAD_RESERVED", "MISSING_END", "TRAILING_BYTES", "UNKNOWN_OPCODE", "TRUNCATED_RECORD",
            "TEXT_TOO_LONG", "BAD_COLOUR", "BAD_HANDLE", "UNKNOWN_TYPE", "WRONG_TYPE", "STALE_HANDLE", "WRONG_FORMAT",
            "BAD_FLAGS_FIELD", "UNKNOWN_FONT", "BAD_UTF8", "CONTROL_CHAR", "OVER_FRAME_RECORDS", "OVER_FRAME_FILL" };
        int[] rows = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 14, 15, 16, 17, 18, 19, 20, 20, 21, 21, 22, 23 };
        ErrorCode[] codes = ErrorCode.values();
        Assertions.assertEquals(24, codes.length);
        for (int i = 0; i < codes.length; i++) {
            Assertions.assertEquals(names[i], codes[i].name());
            Assertions.assertEquals(rows[i], codes[i].row(), names[i]);
        }
    }

    private static void assertOp(int code, String name, int size, EnergyClass energyClass) {
        Assertions.assertEquals(size, Opcodes.fixedSize(code), name);
        Assertions.assertEquals(name, Opcodes.name(code));
        Assertions.assertEquals(name, Opcodes.token(code));
        Assertions.assertEquals(energyClass, Opcodes.energyClass(code));
        Opcodes.Op op = Opcodes.op(code);
        Assertions.assertEquals(code, op.code);
        Assertions.assertEquals(name, op.name);
        Assertions.assertEquals(size, op.fixedSize);
        Assertions.assertEquals(energyClass, op.energyClass);
    }

    private static void assertFields(int code, String... expected) {
        List<Opcodes.Field> fields = Opcodes.op(code).fields;
        String[] actual = new String[fields.size()];
        for (int i = 0; i < actual.length; i++) {
            Opcodes.Field f = fields.get(i);
            actual[i] = f.name + " " + f.type + "@" + f.offset;
        }
        Assertions.assertArrayEquals(expected, actual, Opcodes.name(code));
    }

    private static void assertOffsets(int code, int[] offsets) {
        List<Opcodes.Field> fields = Opcodes.op(code).fields;
        Assertions.assertEquals(fields.size(), offsets.length);
        for (int i = 0; i < offsets.length; i++) {
            Assertions.assertEquals(fields.get(i).offset, offsets[i], Opcodes.name(code) + "." + fields.get(i).name);
        }
    }
}
