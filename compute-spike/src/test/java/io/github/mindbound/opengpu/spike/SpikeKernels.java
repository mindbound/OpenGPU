package io.github.mindbound.opengpu.spike;

import static io.github.mindbound.opengpu.spike.ir.Ir.cmp;
import static io.github.mindbound.opengpu.spike.ir.Ir.gid;
import static io.github.mindbound.opengpu.spike.ir.Ir.i32;
import static io.github.mindbound.opengpu.spike.ir.Ir.load;
import static io.github.mindbound.opengpu.spike.ir.Ir.op;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import io.github.mindbound.opengpu.spike.ir.Access;
import io.github.mindbound.opengpu.spike.ir.Cmp;
import io.github.mindbound.opengpu.spike.ir.Compare;
import io.github.mindbound.opengpu.spike.ir.Expr;
import io.github.mindbound.opengpu.spike.ir.Op;
import io.github.mindbound.opengpu.spike.ir.Program;
import io.github.mindbound.opengpu.spike.ir.ProgramBuilder;
import io.github.mindbound.opengpu.spike.ir.Type;
import io.github.mindbound.opengpu.spike.rt.Kernel;

/**
 * The spike's kernels and their inputs (S14 plan P9.1, amendments F1, F2, I3, I14), each 65 536 invocations:
 * <ul>
 * <li>K1, {@link #heat()}: 22 §2.1's heat stencil on a 256 x 256 grid, with its bounds guard, four guarded neighbours
 * and the float update, run 16 steps ping-pong between two buffers; the gated kernel.</li>
 * <li>K2, {@link #mix()}: an integer mix over a constant loop of 4, with guarded {@code idiv} and {@code irem} on
 * divisors that are sometimes 0, and shifts by counts up to 34.</li>
 * <li>K3, {@link #opTable(boolean)}: every op of S14 plan P3.3 and every comparison on (a, b) pairs, the first 576
 * the pairs of {@link #SPECIALS}' 24 words and the rest from {@code java.util.Random} with a fixed seed, whose
 * algorithm its Javadoc specifies; K3' is K3 without {@code FSIN}, which allocates on JDK 21+ (amendments I3).</li>
 * </ul>
 * Every input is fixed, so a kernel's output words are the same on every JVM that runs them right.
 */
public final class SpikeKernels {

    /** K1's grid side. */
    public static final int SIZE = 256;
    public static final int INVOCATIONS = SIZE * SIZE;
    public static final int HEAT_STEPS = 16;
    public static final float HEAT_RATE = 0.25f;
    public static final int MIX_SEED = 0x51ce5eed;

    /**
     * 24 words that are special as ints, as floats or as shift counts: +0 and -0 (INT_MIN), the infinities, NaNs with
     * several payloads and both signs (-1 and INT_MAX among them), subnormals, the least normal, shift counts 31 to 33,
     * plus and minus 7, 1, -1, -2, 1e10 and -1e10, and the greatest float.
     */
    public static final int[] SPECIALS = { 0x00000000, 0x80000000, 0x7f800000, 0xff800000, 0x7fc00000, 0xffc00000,
        0x7f800001, 0xffffffff, 0x7fffffff, 0x00000001, 0x007fffff, 0x80000001, 0x00800000, 31, 32, 33, 7, -7,
        Float.floatToRawIntBits(1f), Float.floatToRawIntBits(-1f), Float.floatToRawIntBits(-2f),
        Float.floatToRawIntBits(1e10f), Float.floatToRawIntBits(-1e10f), 0x7f7fffff };

    private SpikeKernels() {}

    /** K1: {@code b1[i] = c + k * (s / float(n) - c)} over b0's cell and its in-grid neighbours (22:100-115). */
    public static Program heat() {
        ProgramBuilder k = new ProgramBuilder("Heat", 2);
        int src = k.binding(Access.READONLY);
        int dst = k.binding(Access.WRITEONLY);
        Expr w = k.uniform(Type.I32);
        Expr h = k.uniform(Type.I32);
        Expr rate = k.uniform(Type.F32);
        int gx = k.local(Type.I32);
        int gy = k.local(Type.I32);
        int i = k.local(Type.I32);
        int c = k.local(Type.F32);
        int s = k.local(Type.F32);
        int n = k.local(Type.I32);
        k.assign(gx, gid(0));
        k.assign(gy, gid(1));
        k.ifThen(cmp(Cmp.GE, k.get(gx), w), k::ret);
        k.ifThen(cmp(Cmp.GE, k.get(gy), h), k::ret);
        k.assign(i, op(Op.IADD, op(Op.IMUL, k.get(gy), w), k.get(gx)));
        k.assign(c, load(src, k.get(i), Type.F32));
        k.assign(s, k.get(c));
        k.assign(n, i32(1));
        neighbour(k, cmp(Cmp.GT, k.get(gx), i32(0)), src, op(Op.ISUB, k.get(i), i32(1)), s, n);
        neighbour(k, cmp(Cmp.LT, op(Op.IADD, k.get(gx), i32(1)), w), src, op(Op.IADD, k.get(i), i32(1)), s, n);
        neighbour(k, cmp(Cmp.GT, k.get(gy), i32(0)), src, op(Op.ISUB, k.get(i), w), s, n);
        neighbour(k, cmp(Cmp.LT, op(Op.IADD, k.get(gy), i32(1)), h), src, op(Op.IADD, k.get(i), w), s, n);
        Expr mean = op(Op.FDIV, k.get(s), op(Op.I2F, k.get(n)));
        k.store(dst, k.get(i), op(Op.FADD, k.get(c), op(Op.FMUL, rate, op(Op.FSUB, mean, k.get(c)))));
        return k.build();
    }

    private static void neighbour(ProgramBuilder k, Compare inGrid, int src, Expr index, int s, int n) {
        k.ifThen(inGrid, () -> {
            k.assign(s, op(Op.FADD, k.get(s), load(src, index, Type.F32)));
            k.assign(n, op(Op.IADD, k.get(n), i32(1)));
        });
    }

    /** K1's uniforms: W, H and the rate's bits. */
    public static int[] heatUniforms() {
        return new int[] { SIZE, SIZE, Float.floatToIntBits(HEAT_RATE) };
    }

    /** K1's start: floats in [0, 100), finite and normal, so that the bench times no NaN or subnormal arithmetic. */
    public static int[] heatField() {
        Random random = new Random(0x4ea7);
        int[] field = new int[INVOCATIONS];
        for (int i = 0; i < field.length; i++) {
            field[i] = Float.floatToIntBits(random.nextFloat() * 100f);
        }
        return field;
    }

    /** K1's 16 steps from {@link #heatField()}, ping-pong over the exact grid; each step's output, in order. */
    public static int[][] heatSteps(Kernel heat) {
        int[] a = heatField();
        int[] b = new int[INVOCATIONS];
        int[] uniforms = heatUniforms();
        int[][] steps = new int[HEAT_STEPS][];
        for (int step = 0; step < HEAT_STEPS; step++) {
            int[] src = step % 2 == 0 ? a : b;
            int[] dst = step % 2 == 0 ? b : a;
            heat.run(src, dst, null, null, uniforms, 0, SIZE, 0, SIZE);
            steps[step] = dst.clone();
        }
        return steps;
    }

    /**
     * K2: per invocation, {@code h = b0[x]} and {@code acc = x ^ seed}, then 4 rounds of {@code d = (h >>> 8j) & 15},
     * {@code acc = acc * 31 + h / d}, {@code acc ^= h % (d - 7)}, a rotate of h, {@code h += (acc >> 3) +
     * (acc << (11j + 1))} and {@code h = ~h ^ -acc}; then {@code b1[x] = acc ^ h}.
     */
    public static Program mix() {
        ProgramBuilder k = new ProgramBuilder("Mix", 1);
        int in = k.binding(Access.READONLY);
        int out = k.binding(Access.WRITEONLY);
        Expr seed = k.uniform(Type.I32);
        int h = k.local(Type.I32);
        int acc = k.local(Type.I32);
        int j = k.local(Type.I32);
        int d = k.local(Type.I32);
        int e = k.local(Type.I32);
        k.assign(h, load(in, gid(0), Type.I32));
        k.assign(acc, op(Op.IXOR, gid(0), seed));
        k.loop(j, 0, 4, () -> {
            k.assign(d, op(Op.IAND, op(Op.IUSHR, k.get(h), op(Op.IMUL, k.get(j), i32(8))), i32(15)));
            k.assign(acc, op(Op.IADD, op(Op.IMUL, k.get(acc), i32(31)), op(Op.IDIV, k.get(h), k.get(d))));
            k.assign(e, op(Op.ISUB, k.get(d), i32(7)));
            k.assign(acc, op(Op.IXOR, k.get(acc), op(Op.IREM, k.get(h), k.get(e))));
            k.assign(h, op(Op.IOR, op(Op.ISHL, k.get(h), i32(5)), op(Op.IUSHR, k.get(h), i32(27))));
            Expr shift = op(Op.IADD, op(Op.IMUL, k.get(j), i32(11)), i32(1));
            Expr mixed = op(
                Op.IADD,
                op(Op.IADD, k.get(h), op(Op.ISHR, k.get(acc), i32(3))),
                op(Op.ISHL, k.get(acc), shift));
            k.assign(h, mixed);
            k.assign(h, op(Op.IXOR, op(Op.INOT, k.get(h)), op(Op.INEG, k.get(acc))));
        });
        k.store(out, gid(0), op(Op.IXOR, k.get(acc), k.get(h)));
        return k.build();
    }

    /** K2's input: INT_MIN, -1, 0 and INT_MAX, then random words. */
    public static int[] mixInput() {
        Random random = new Random(0x313);
        int[] input = new int[INVOCATIONS];
        for (int i = 0; i < input.length; i++) {
            input[i] = random.nextInt();
        }
        input[0] = Integer.MIN_VALUE;
        input[1] = -1;
        input[2] = 0;
        input[3] = Integer.MAX_VALUE;
        return input;
    }

    public static int[] mixUniforms() {
        return new int[] { MIX_SEED };
    }

    /** One of K3's output words per pair: an op's result, or a comparison's as 1 or 0. */
    public enum Column {

        IADD(Op.IADD),
        ISUB(Op.ISUB),
        IMUL(Op.IMUL),
        IDIV(Op.IDIV),
        IREM(Op.IREM),
        IAND(Op.IAND),
        IOR(Op.IOR),
        IXOR(Op.IXOR),
        ISHL(Op.ISHL),
        ISHR(Op.ISHR),
        IUSHR(Op.IUSHR),
        INEG(Op.INEG),
        INOT(Op.INOT),
        FADD(Op.FADD),
        FSUB(Op.FSUB),
        FMUL(Op.FMUL),
        FDIV(Op.FDIV),
        FNEG(Op.FNEG),
        FSQRT(Op.FSQRT),
        FSIN(Op.FSIN),
        FLOG1P(Op.FLOG1P),
        I2F(Op.I2F),
        F2I(Op.F2I),
        ILT(Cmp.LT, Type.I32),
        ILE(Cmp.LE, Type.I32),
        IGT(Cmp.GT, Type.I32),
        IGE(Cmp.GE, Type.I32),
        IEQ(Cmp.EQ, Type.I32),
        INE(Cmp.NE, Type.I32),
        FLT(Cmp.LT, Type.F32),
        FLE(Cmp.LE, Type.F32),
        FGT(Cmp.GT, Type.F32),
        FGE(Cmp.GE, Type.F32),
        FEQ(Cmp.EQ, Type.F32),
        FNE(Cmp.NE, Type.F32);

        /** The op, or null for a comparison. */
        public final Op op;
        /** The comparison, or null for an op. */
        public final Cmp cmp;
        /** The operands' type. */
        public final Type operands;

        Column(Op op) {
            this.op = op;
            this.cmp = null;
            this.operands = op.operand;
        }

        Column(Cmp cmp, Type operands) {
            this.op = null;
            this.cmp = cmp;
            this.operands = operands;
        }
    }

    /** K3's columns, in output order: all of them, or all but {@code FSIN} for K3'. */
    public static List<Column> columns(boolean sine) {
        List<Column> columns = new ArrayList<>(Arrays.asList(Column.values()));
        if (!sine) {
            columns.remove(Column.FSIN);
        }
        return Collections.unmodifiableList(columns);
    }

    /** K3 ({@code sine}) or K3': {@code b2[x * columns + c]} = column c on {@code (b0[x], b1[x])}. */
    public static Program opTable(boolean sine) {
        List<Column> columns = columns(sine);
        ProgramBuilder k = new ProgramBuilder(sine ? "OpTable" : "OpTableNoSin", 1);
        int a = k.binding(Access.READONLY);
        int b = k.binding(Access.READONLY);
        int out = k.binding(Access.WRITEONLY);
        int ai = k.local(Type.I32);
        int bi = k.local(Type.I32);
        int af = k.local(Type.F32);
        int bf = k.local(Type.F32);
        int base = k.local(Type.I32);
        k.assign(ai, load(a, gid(0), Type.I32));
        k.assign(bi, load(b, gid(0), Type.I32));
        // Every divisor and shift count comes from a buffer, so the runtime guards are what K3 shows (amendments F14)
        k.assign(af, load(a, gid(0), Type.F32));
        k.assign(bf, load(b, gid(0), Type.F32));
        k.assign(base, op(Op.IMUL, gid(0), i32(columns.size())));
        for (int c = 0; c < columns.size(); c++) {
            Column column = columns.get(c);
            Expr index = op(Op.IADD, k.get(base), i32(c));
            Expr x = k.get(column.operands == Type.F32 ? af : ai);
            Expr y = k.get(column.operands == Type.F32 ? bf : bi);
            if (column.op == null) {
                k.ifElse(cmp(column.cmp, x, y), () -> k.store(out, index, i32(1)), () -> k.store(out, index, i32(0)));
            } else {
                k.store(out, index, column.op.arity == 1 ? op(column.op, x) : op(column.op, x, y));
            }
        }
        return k.build();
    }

    /** K3's inputs a and b: {@link #SPECIALS} x {@link #SPECIALS}, a outer, then random pairs. */
    public static int[][] opTablePairs() {
        int[] a = new int[INVOCATIONS];
        int[] b = new int[INVOCATIONS];
        int specials = SPECIALS.length;
        for (int p = 0; p < specials * specials; p++) {
            a[p] = SPECIALS[p / specials];
            b[p] = SPECIALS[p % specials];
        }
        Random random = new Random(0x0b7ab1e);
        for (int p = specials * specials; p < INVOCATIONS; p++) {
            a[p] = random.nextInt();
            b[p] = random.nextInt();
        }
        return new int[][] { a, b };
    }

    /** One dispatch of K3 or K3' over every pair, into a new buffer. */
    public static int[] opTableRun(Kernel opTable, boolean sine) {
        int[][] pairs = opTablePairs();
        int[] out = new int[INVOCATIONS * columns(sine).size()];
        opTable.run(pairs[0], pairs[1], out, null, null, 0, INVOCATIONS, 0, 1);
        return out;
    }
}
