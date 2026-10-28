package io.github.mindbound.opengpu.spike.ir;

/**
 * The spike's operations (S14 plan P3.3), each with its arity, its operands' type and its result's, under 22 §2.3's
 * defined arithmetic: int ops wrap, shift counts are taken mod 32, {@code x / 0 = x} and {@code x % 0 = 0}; float ops
 * are IEEE with subnormals kept; {@code F2I} truncates toward zero, saturates and maps NaN to 0.
 */
public enum Op {

    IADD(2, Type.I32, Type.I32),
    ISUB(2, Type.I32, Type.I32),
    IMUL(2, Type.I32, Type.I32),
    IDIV(2, Type.I32, Type.I32),
    IREM(2, Type.I32, Type.I32),
    IAND(2, Type.I32, Type.I32),
    IOR(2, Type.I32, Type.I32),
    IXOR(2, Type.I32, Type.I32),
    ISHL(2, Type.I32, Type.I32),
    ISHR(2, Type.I32, Type.I32),
    IUSHR(2, Type.I32, Type.I32),
    INEG(1, Type.I32, Type.I32),
    INOT(1, Type.I32, Type.I32),
    FADD(2, Type.F32, Type.F32),
    FSUB(2, Type.F32, Type.F32),
    FMUL(2, Type.F32, Type.F32),
    FDIV(2, Type.F32, Type.F32),
    FNEG(1, Type.F32, Type.F32),
    /** {@code (float)Math.sqrt((double)x)}, correctly rounded by its specification (S14 plan P4.5). */
    FSQRT(1, Type.F32, Type.F32),
    /** {@code (float)StrictMath.sin((double)x)} (22:142). */
    FSIN(1, Type.F32, Type.F32),
    /** {@code (float)StrictMath.log1p((double)x)} (22:142), whose NaN's sign differs between JDK 8/17 and 21+. */
    FLOG1P(1, Type.F32, Type.F32),
    I2F(1, Type.I32, Type.F32),
    F2I(1, Type.F32, Type.I32);

    /** 1 or 2. */
    public final int arity;
    /** The type of each operand. */
    public final Type operand;
    public final Type result;

    Op(int arity, Type operand, Type result) {
        this.arity = arity;
        this.operand = operand;
        this.result = result;
    }
}
