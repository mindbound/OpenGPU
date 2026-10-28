package io.github.mindbound.opengpu.spike.ir;

import java.util.Objects;

/**
 * An expression of the spike's IR (S14 plan P3.3), a small subset of 22 §3's SIR: immutable, each with its type. The
 * nodes are this class's nested classes and no other, so the emitter and the validator see every kind. Nothing is
 * checked here but nulls; {@link Validator} checks the types against the program.
 */
public abstract class Expr {

    public final Type type;

    Expr(Type type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    /** A constant word: an int, or a float as its bits. */
    public static final class Const extends Expr {

        public final int bits;

        public Const(Type type, int bits) {
            super(type);
            this.bits = bits;
        }
    }

    /** A program local, by slot; {@code type} must be the slot's one type. */
    public static final class Local extends Expr {

        public final int slot;

        public Local(int slot, Type type) {
            super(type);
            this.slot = slot;
        }
    }

    /** A dispatch's uniform, by index; {@code type} must be the program's type for it. */
    public static final class Uniform extends Expr {

        public final int index;

        public Uniform(int index, Type type) {
            super(type);
            this.index = index;
        }
    }

    /** The invocation's global id on axis 0 (x) or 1 (y). */
    public static final class GlobalId extends Expr {

        public final int axis;

        public GlobalId(int axis) {
            super(Type.I32);
            this.axis = axis;
        }
    }

    /** A buffer word, as an int, or as a float through {@code Float.intBitsToFloat} (22:120). */
    public static final class Load extends Expr {

        public final int binding;
        public final Expr index;

        public Load(int binding, Expr index, Type type) {
            super(type);
            this.binding = binding;
            this.index = Objects.requireNonNull(index, "index");
        }
    }

    public static final class Unary extends Expr {

        public final Op op;
        public final Expr operand;

        public Unary(Op op, Expr operand) {
            super(Objects.requireNonNull(op, "op").result);
            this.op = op;
            this.operand = Objects.requireNonNull(operand, "operand");
        }
    }

    public static final class Binary extends Expr {

        public final Op op;
        public final Expr left;
        public final Expr right;

        public Binary(Op op, Expr left, Expr right) {
            super(Objects.requireNonNull(op, "op").result);
            this.op = op;
            this.left = Objects.requireNonNull(left, "left");
            this.right = Objects.requireNonNull(right, "right");
        }
    }
}
