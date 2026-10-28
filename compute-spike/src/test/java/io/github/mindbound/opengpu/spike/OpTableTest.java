package io.github.mindbound.opengpu.spike;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.spike.SpikeKernels.Column;
import io.github.mindbound.opengpu.spike.ir.Type;
import io.github.mindbound.opengpu.spike.load.KernelLoader;
import io.github.mindbound.opengpu.spike.rt.Kernel;

/**
 * 22 §2.3's rows read from K3's table (S14 plan P7.3, amendments F14), the oracle that catches a mistake the emitter
 * and a Java reference would share: each row's literal values on the special pairs, with every divisor and shift count
 * loaded from a buffer, so the emitted guards are what answers; then every column of the 576 special pairs against its
 * rule written in Java; and the first dispatch of a fresh K3, mostly in the JVM's interpreter, against the 30th, by
 * then compiled (P8.2 item 2). The table is that first dispatch's, and its digest is the leg's {@code k3}.
 */
class OpTableTest {

    private static final List<Column> COLUMNS = SpikeKernels.columns(true);
    private static final int CANONICAL_NAN = 0x7fc00000;
    private static final int[] NANS = { 0x7fc00000, 0xffc00000, 0x7f800001, 0xffffffff, 0x7fffffff };
    private static final int MIN = Integer.MIN_VALUE;
    private static final int MAX = Integer.MAX_VALUE;
    private static final int PLUS_INF = 0x7f800000;
    private static final int MINUS_INF = 0xff800000;
    private static final int MINUS_ZERO = 0x80000000;

    private static Kernel opTable;
    private static int[] table;

    @BeforeAll
    static void runAFreshK3Once() {
        opTable = KernelLoader.load(SpikeKernels.opTable(true));
        table = SpikeKernels.opTableRun(opTable, true);
        Digests.record("k3", Digests.sha256(table));
    }

    @Test
    void integerArithmeticFollowsTheTable() {
        for (int a : SpikeKernels.SPECIALS) {
            // x / 0 = x and x % 0 = 0 (22:134)
            Assertions.assertEquals(a, out(Column.IDIV, a, 0), () -> hex(a) + " / 0");
            Assertions.assertEquals(0, out(Column.IREM, a, 0), () -> hex(a) + " % 0");
        }
        // INT_MIN / -1 = INT_MIN and INT_MIN % -1 = 0 (22:135)
        Assertions.assertEquals(MIN, out(Column.IDIV, MIN, -1));
        Assertions.assertEquals(0, out(Column.IREM, MIN, -1));
        // % truncates, with the dividend's sign (22:137)
        Assertions.assertEquals(-7, out(Column.IREM, -7, 31));
        Assertions.assertEquals(0, out(Column.IDIV, -7, 31));
        Assertions.assertEquals(5, out(Column.IREM, 33, -7));
        Assertions.assertEquals(-4, out(Column.IDIV, 33, -7));
        // add, sub and mul wrap (22:133)
        Assertions.assertEquals(MIN, out(Column.IADD, MAX, 1));
        Assertions.assertEquals(MAX, out(Column.ISUB, MIN, 1));
        Assertions.assertEquals(MIN, out(Column.IMUL, MIN, -1));
        Assertions.assertEquals(MIN, out(Column.INEG, MIN, 0));
        Assertions.assertEquals(6, out(Column.INOT, -7, 0));
    }

    @Test
    void shiftCountsAreTakenMod32() {
        // 22:136
        Assertions.assertEquals(7, out(Column.ISHL, 7, 32));
        Assertions.assertEquals(14, out(Column.ISHL, 7, 33));
        Assertions.assertEquals(MIN, out(Column.ISHL, 7, -1));
        Assertions.assertEquals(MIN, out(Column.ISHL, 1, 31));
        Assertions.assertEquals(-7, out(Column.ISHR, -7, 32));
        Assertions.assertEquals(-4, out(Column.ISHR, -7, 33));
        Assertions.assertEquals(-1, out(Column.ISHR, -7, -1));
        Assertions.assertEquals(-7, out(Column.IUSHR, -7, 32));
        Assertions.assertEquals(0x7ffffffc, out(Column.IUSHR, -7, 33));
        Assertions.assertEquals(1, out(Column.IUSHR, -7, -1));
    }

    @Test
    void floatToIntTruncatesSaturatesAndMapsNanToZero() {
        // 22:139
        for (int nan : NANS) {
            Assertions.assertEquals(0, out(Column.F2I, nan, 0), () -> "F2I of " + hex(nan));
        }
        Assertions.assertEquals(MAX, out(Column.F2I, Float.floatToRawIntBits(1e10f), 0));
        Assertions.assertEquals(MIN, out(Column.F2I, Float.floatToRawIntBits(-1e10f), 0));
        Assertions.assertEquals(MAX, out(Column.F2I, PLUS_INF, 0));
        Assertions.assertEquals(MIN, out(Column.F2I, MINUS_INF, 0));
        Assertions.assertEquals(MAX, out(Column.F2I, 0x7f7fffff, 0));
        Assertions.assertEquals(-2, out(Column.F2I, Float.floatToRawIntBits(-2f), 0));
        Assertions.assertEquals(0, out(Column.F2I, 0x007fffff, 0));
        Assertions.assertEquals(0, out(Column.F2I, MINUS_ZERO, 0));
    }

    @Test
    void everyStoredNanIs7fc00000() {
        // The NaN-canonical store (S14 decisions D1, Q-S14-3 (a)), whatever the operands' payloads and signs
        int one = Float.floatToRawIntBits(1f);
        for (int nan : NANS) {
            Assertions.assertEquals(CANONICAL_NAN, out(Column.FADD, nan, one), () -> hex(nan) + " + 1");
            Assertions.assertEquals(CANONICAL_NAN, out(Column.FMUL, one, nan), () -> "1 * " + hex(nan));
            Assertions.assertEquals(CANONICAL_NAN, out(Column.FNEG, nan, 0), () -> "-" + hex(nan));
            Assertions.assertEquals(CANONICAL_NAN, out(Column.FSQRT, nan, 0), () -> "sqrt " + hex(nan));
        }
        // NaNs made by the operation, which x86 makes negative (0xffc00000)
        Assertions.assertEquals(CANONICAL_NAN, out(Column.FSUB, PLUS_INF, PLUS_INF));
        Assertions.assertEquals(CANONICAL_NAN, out(Column.FADD, PLUS_INF, MINUS_INF));
        Assertions.assertEquals(CANONICAL_NAN, out(Column.FMUL, 0, PLUS_INF));
        Assertions.assertEquals(CANONICAL_NAN, out(Column.FDIV, 0, 0));
        Assertions.assertEquals(CANONICAL_NAN, out(Column.FSQRT, Float.floatToRawIntBits(-1f), 0));
        Assertions.assertEquals(CANONICAL_NAN, out(Column.FSIN, PLUS_INF, 0));
        // log1p's NaN is negative on JDK 8 and 17 and positive on 21+ (S14 amendments F5)
        Assertions.assertEquals(CANONICAL_NAN, out(Column.FLOG1P, Float.floatToRawIntBits(-2f), 0));
        // And every NaN anywhere in the table, the random pairs' too
        for (int p = 0; p < SpikeKernels.INVOCATIONS; p++) {
            for (int c = 0; c < COLUMNS.size(); c++) {
                int word = table[p * COLUMNS.size() + c];
                if (COLUMNS.get(c).op != null && COLUMNS.get(c).op.result == Type.F32 && isNaN(word)) {
                    Assertions.assertEquals(CANONICAL_NAN, word, "pair " + p + ", " + COLUMNS.get(c));
                }
            }
        }
    }

    @Test
    void negativeZeroAndSubnormalsAreKept() {
        // 22:141: IEEE, subnormals kept
        int one = Float.floatToRawIntBits(1f);
        int minusOne = Float.floatToRawIntBits(-1f);
        Assertions.assertEquals(MINUS_ZERO, out(Column.FNEG, 0, 0));
        Assertions.assertEquals(MINUS_ZERO, out(Column.FMUL, MINUS_ZERO, one));
        Assertions.assertEquals(MINUS_ZERO, out(Column.FADD, MINUS_ZERO, MINUS_ZERO));
        Assertions.assertEquals(0, out(Column.FADD, MINUS_ZERO, 0));
        Assertions.assertEquals(MINUS_ZERO, out(Column.FSQRT, MINUS_ZERO, 0));
        Assertions.assertEquals(MINUS_ZERO, out(Column.FSIN, MINUS_ZERO, 0));
        Assertions.assertEquals(MINUS_ZERO, out(Column.FLOG1P, MINUS_ZERO, 0));
        Assertions.assertEquals(0x00000002, out(Column.FADD, 0x00000001, 0x00000001));
        Assertions.assertEquals(0x007fffff, out(Column.FMUL, 0x007fffff, one));
        Assertions.assertEquals(0x007fffff, out(Column.FSUB, 0x00800000, 0x00000001));
        Assertions.assertEquals(0x80000001, out(Column.FDIV, 0x00000001, minusOne));
        Assertions.assertEquals(0x80000001, out(Column.FNEG, 0x00000001, 0));
        Assertions.assertEquals(Float.floatToRawIntBits(-2147483648f), out(Column.I2F, MIN, 0));
    }

    @Test
    void orderedComparisonsWithNanAreFalseAndNotEqualIsTrue() {
        Column[] ordered = { Column.FLT, Column.FLE, Column.FGT, Column.FGE, Column.FEQ };
        for (int nan : NANS) {
            for (int other : SpikeKernels.SPECIALS) {
                for (Column c : ordered) {
                    Assertions.assertEquals(0, out(c, nan, other), () -> hex(nan) + " " + c + " " + hex(other));
                    Assertions.assertEquals(0, out(c, other, nan), () -> hex(other) + " " + c + " " + hex(nan));
                }
                Assertions.assertEquals(1, out(Column.FNE, nan, other), () -> hex(nan) + " FNE " + hex(other));
                Assertions.assertEquals(1, out(Column.FNE, other, nan), () -> hex(other) + " FNE " + hex(nan));
            }
        }
        Assertions.assertEquals(1, out(Column.FEQ, 0, MINUS_ZERO));
        Assertions.assertEquals(0, out(Column.FNE, 0, MINUS_ZERO));
        Assertions.assertEquals(1, out(Column.FLT, MINUS_INF, PLUS_INF));
        Assertions.assertEquals(1, out(Column.FLE, 0x00000001, 0x00000001));
        // int comparisons are signed
        Assertions.assertEquals(1, out(Column.ILT, MIN, -1));
        Assertions.assertEquals(1, out(Column.IGT, 0x7fc00000, 0xffc00000));
        Assertions.assertEquals(1, out(Column.IGE, MAX, MAX));
        Assertions.assertEquals(0, out(Column.IEQ, 0, MIN));
    }

    @Test
    void everySpecialPairMatchesItsRuleInJava() {
        int specials = SpikeKernels.SPECIALS.length;
        for (int p = 0; p < specials * specials; p++) {
            int a = SpikeKernels.SPECIALS[p / specials];
            int b = SpikeKernels.SPECIALS[p % specials];
            for (Column c : COLUMNS) {
                int pair = p;
                Assertions.assertEquals(
                    rule(c, a, b),
                    table[pair * COLUMNS.size() + COLUMNS.indexOf(c)],
                    () -> "pair " + pair + ": " + hex(a) + " " + c + " " + hex(b));
            }
        }
    }

    @Test
    void theFirstDispatchEqualsThe30th() {
        int[] warm = null;
        for (int i = 0; i < 30; i++) {
            warm = SpikeKernels.opTableRun(opTable, true);
        }
        Assertions.assertArrayEquals(table, warm);
    }

    /** Column c of the special pair (a, b) in the first dispatch's table. */
    private static int out(Column c, int a, int b) {
        int pair = indexOf(a) * SpikeKernels.SPECIALS.length + indexOf(b);
        return table[pair * COLUMNS.size() + COLUMNS.indexOf(c)];
    }

    private static int indexOf(int special) {
        for (int i = 0; i < SpikeKernels.SPECIALS.length; i++) {
            if (SpikeKernels.SPECIALS[i] == special) {
                return i;
            }
        }
        throw new IllegalArgumentException(hex(special) + " is not one of K3's specials");
    }

    /** The column's rule in Java, with javac's lowering of each operator and 22:134's guards. */
    private static int rule(Column c, int a, int b) {
        float x = Float.intBitsToFloat(a);
        float y = Float.intBitsToFloat(b);
        switch (c) {
            case IADD:
                return a + b;
            case ISUB:
                return a - b;
            case IMUL:
                return a * b;
            case IDIV:
                return JavaKernels.idiv(a, b);
            case IREM:
                return JavaKernels.irem(a, b);
            case IAND:
                return a & b;
            case IOR:
                return a | b;
            case IXOR:
                return a ^ b;
            case ISHL:
                return a << b;
            case ISHR:
                return a >> b;
            case IUSHR:
                return a >>> b;
            case INEG:
                return -a;
            case INOT:
                return ~a;
            case FADD:
                return Float.floatToIntBits(x + y);
            case FSUB:
                return Float.floatToIntBits(x - y);
            case FMUL:
                return Float.floatToIntBits(x * y);
            case FDIV:
                return Float.floatToIntBits(x / y);
            case FNEG:
                return Float.floatToIntBits(-x);
            case FSQRT:
                return Float.floatToIntBits((float) Math.sqrt(x));
            case FSIN:
                return Float.floatToIntBits((float) StrictMath.sin(x));
            case FLOG1P:
                return Float.floatToIntBits((float) StrictMath.log1p(x));
            case I2F:
                return Float.floatToIntBits((float) a);
            case F2I:
                return (int) x;
            case ILT:
                return a < b ? 1 : 0;
            case ILE:
                return a <= b ? 1 : 0;
            case IGT:
                return a > b ? 1 : 0;
            case IGE:
                return a >= b ? 1 : 0;
            case IEQ:
                return a == b ? 1 : 0;
            case INE:
                return a != b ? 1 : 0;
            case FLT:
                return x < y ? 1 : 0;
            case FLE:
                return x <= y ? 1 : 0;
            case FGT:
                return x > y ? 1 : 0;
            case FGE:
                return x >= y ? 1 : 0;
            case FEQ:
                return x == y ? 1 : 0;
            default:
                return x != y ? 1 : 0;
        }
    }

    private static boolean isNaN(int bits) {
        return (bits & 0x7f800000) == 0x7f800000 && (bits & 0x007fffff) != 0;
    }

    private static String hex(int word) {
        return String.format("0x%08x", word);
    }
}
