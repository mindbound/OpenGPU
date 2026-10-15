package io.github.mindbound.opengpu.core.cmd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The v1 opcode table as data (28 §4.4, §5.2): numbers, sizes, field layouts, flag bits and energy classes. The
 * validator and the reader take their sizes and offsets from here, the root {@code api} package's
 * {@code CommandFormat} copies the constants (28 §4.2), and from M1 the same table generates {@code api-dump.json}'s
 * opcode and record-layout entries. Field offsets are relative to the opcode byte, and a record's total includes it.
 */
public final class Opcodes {

    public static final int CLEAR = 0x01, FILL_RECT = 0x30, BLIT = 0x31, TEXT = 0x33, END = 0xFF;

    /** {@code TEXT}'s fixed part; the record is 17 + n bytes. */
    public static final int CLEAR_SIZE = 5, FILL_RECT_SIZE = 13, BLIT_SIZE = 18, TEXT_FIXED_SIZE = 17, END_SIZE = 1;

    public static final int CLEAR_COLOUR = 1;
    public static final int FILL_X = 1, FILL_Y = 3, FILL_W = 5, FILL_H = 7, FILL_COLOUR = 9;
    public static final int BLIT_TEX = 1, BLIT_SX = 5, BLIT_SY = 7, BLIT_W = 9, BLIT_H = 11, BLIT_DX = 13, BLIT_DY = 15,
        BLIT_FLAGS = 17;
    public static final int TEXT_X = 1, TEXT_Y = 3, TEXT_FG = 5, TEXT_BG = 9, TEXT_FLAGS = 13, TEXT_FONT = 14,
        TEXT_COUNT = 15, TEXT_STRING = 17;

    /**
     * The longest {@code TEXT} string in bytes (28 §3.6 row 12; {@code getCaps().maxTextBytes}). A format constant, not
     * a frame cap: raising it later is a relaxed check (28 §4.3).
     */
    public static final int TEXT_MAX_BYTES = 1024;

    /** {@code BLIT}'s flags bit 0: source texels of value 0 are not written (28 §5.5). */
    public static final int BLIT_KEY0 = 0x01;
    /** Bits 1-2 are M1's integer scale (28 §5.5) and stay {@code BAD_FLAGS_FIELD} in v1, as do bits 3-7. */
    public static final int BLIT_FLAGS_DEFINED = BLIT_KEY0;
    /** {@code TEXT}'s flags bit 0: clear glyph bits keep the pixel instead of writing bg (28 §5.6). */
    public static final int TEXT_TRANSPARENT_BG = 0x01;
    /** Bits 1-2 are M1's integer scale (28 §5.6) and stay {@code BAD_FLAGS_FIELD} in v1, as do bits 3-7. */
    public static final int TEXT_FLAGS_DEFINED = TEXT_TRANSPARENT_BG;

    /** An operand type of 28 §3.3, with its size in bytes; {@link #BYTES} is the counted tail, sized by its count. */
    public enum FieldType {

        U8(1),
        I16(2),
        U16(2),
        U32(4),
        HANDLE(4),
        COLOUR(4),
        BYTES(0);

        public final int size;

        FieldType(int size) {
            this.size = size;
        }
    }

    /** One field of a record's layout (28 §5.2), named as 28 names it. */
    public static final class Field {

        public final String name;
        /** Relative to the opcode byte. */
        public final int offset;
        public final FieldType type;

        private Field(String name, int offset, FieldType type) {
            this.name = name;
            this.offset = offset;
            this.type = type;
        }

        @Override
        public String toString() {
            return name + " " + type + "@" + offset;
        }
    }

    /** One opcode's descriptor: what every version's decoder and M1's API dump read (28 §4.2). Immutable. */
    public static final class Op {

        public final int code;
        public final String name;
        /** The fixed part, opcode included; for {@code TEXT} the record is this plus its count. */
        public final int fixedSize;
        /** Null for {@code END}. */
        public final EnergyClass energyClass;
        /** The offset of the u16 that counts the tail ({@code TEXT}'s 15), or -1 for none. */
        public final int countOffset;
        /** In layout order, without the opcode byte; unmodifiable. */
        public final List<Field> fields;

        private Op(int code, String name, int fixedSize, EnergyClass energyClass, int countOffset, Field... fields) {
            this.code = code;
            this.name = name;
            this.fixedSize = fixedSize;
            this.energyClass = energyClass;
            this.countOffset = countOffset;
            this.fields = Collections.unmodifiableList(Arrays.asList(fields));
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "0x%02X %s %d B %s", code, name, fixedSize, fields);
        }
    }

    private static final List<Op> ALL;
    private static final Op[] BY_CODE = new Op[256];
    /** 28 §3.2's 256-entry size table; 0 means unassigned. */
    private static final int[] FIXED_SIZE = new int[256];

    static {
        List<Op> all = new ArrayList<>();
        all.add(
            new Op(CLEAR, "CLEAR", CLEAR_SIZE, EnergyClass.CLEAR, -1, new Field("c", CLEAR_COLOUR, FieldType.COLOUR)));
        all.add(
            new Op(
                FILL_RECT,
                "FILL_RECT",
                FILL_RECT_SIZE,
                EnergyClass.FILL,
                -1,
                new Field("x", FILL_X, FieldType.I16),
                new Field("y", FILL_Y, FieldType.I16),
                new Field("w", FILL_W, FieldType.I16),
                new Field("h", FILL_H, FieldType.I16),
                new Field("c", FILL_COLOUR, FieldType.COLOUR)));
        all.add(
            new Op(
                BLIT,
                "BLIT",
                BLIT_SIZE,
                EnergyClass.COPY,
                -1,
                new Field("tex", BLIT_TEX, FieldType.HANDLE),
                new Field("sx", BLIT_SX, FieldType.I16),
                new Field("sy", BLIT_SY, FieldType.I16),
                new Field("w", BLIT_W, FieldType.I16),
                new Field("h", BLIT_H, FieldType.I16),
                new Field("dx", BLIT_DX, FieldType.I16),
                new Field("dy", BLIT_DY, FieldType.I16),
                new Field("flags", BLIT_FLAGS, FieldType.U8)));
        all.add(
            new Op(
                TEXT,
                "TEXT",
                TEXT_FIXED_SIZE,
                EnergyClass.SET,
                TEXT_COUNT,
                new Field("x", TEXT_X, FieldType.I16),
                new Field("y", TEXT_Y, FieldType.I16),
                new Field("fg", TEXT_FG, FieldType.COLOUR),
                new Field("bg", TEXT_BG, FieldType.COLOUR),
                new Field("flags", TEXT_FLAGS, FieldType.U8),
                new Field("font", TEXT_FONT, FieldType.U8),
                new Field("n", TEXT_COUNT, FieldType.U16),
                new Field("s", TEXT_STRING, FieldType.BYTES)));
        all.add(new Op(END, "END", END_SIZE, null, -1));
        ALL = Collections.unmodifiableList(all);
        for (Op op : ALL) {
            BY_CODE[op.code] = op;
            FIXED_SIZE[op.code] = op.fixedSize;
        }
    }

    private Opcodes() {}

    /** The fixed size of an opcode 0-255, opcode byte included; 0 for an unassigned one or a value outside 0-255. */
    public static int fixedSize(int opcode) {
        return (opcode & ~0xFF) == 0 ? FIXED_SIZE[opcode] : 0;
    }

    /** "CLEAR", "FILL_RECT", "BLIT", "TEXT" or "END"; null for an unassigned opcode. */
    public static String name(int opcode) {
        Op op = op(opcode);
        return op == null ? null : op.name;
    }

    /** The {@code <OP>} of 28 §3.6's messages: {@link #name}, or "0x%02X" in upper case for an unassigned opcode. */
    public static String token(int opcode) {
        String name = name(opcode);
        return name != null ? name : String.format(Locale.ROOT, "0x%02X", opcode);
    }

    /** Null for {@code END} and unassigned opcodes. */
    public static EnergyClass energyClass(int opcode) {
        Op op = op(opcode);
        return op == null ? null : op.energyClass;
    }

    /** The descriptor, or null for an unassigned opcode. */
    public static Op op(int opcode) {
        return (opcode & ~0xFF) == 0 ? BY_CODE[opcode] : null;
    }

    /** Every v1 opcode in opcode order: {@code CLEAR}, {@code FILL_RECT}, {@code BLIT}, {@code TEXT}, {@code END}. */
    public static List<Op> all() {
        return ALL;
    }
}
