package io.github.mindbound.opengpu.spike.ir;

/** Short factories for expressions and conditions, for {@link ProgramBuilder}'s users, since S14 has no front end. */
public final class Ir {

    private Ir() {}

    public static Expr i32(int value) {
        return new Expr.Const(Type.I32, value);
    }

    /** A float constant, as its raw bits, so a NaN keeps the payload it was written with. */
    public static Expr f32(float value) {
        return f32Bits(Float.floatToRawIntBits(value));
    }

    public static Expr f32Bits(int bits) {
        return new Expr.Const(Type.F32, bits);
    }

    public static Expr gid(int axis) {
        return new Expr.GlobalId(axis);
    }

    public static Expr load(int binding, Expr index, Type type) {
        return new Expr.Load(binding, index, type);
    }

    public static Expr op(Op op, Expr operand) {
        return new Expr.Unary(op, operand);
    }

    public static Expr op(Op op, Expr left, Expr right) {
        return new Expr.Binary(op, left, right);
    }

    public static Compare cmp(Cmp op, Expr left, Expr right) {
        return new Compare(op, left, right);
    }
}
