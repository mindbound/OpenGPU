package io.github.mindbound.opengpu.spike.ir;

import java.util.Objects;

/** An {@code If}'s condition (S14 plan P3.3): two operands of one type, compared as {@link Cmp} says. */
public final class Compare {

    public final Cmp op;
    public final Expr left;
    public final Expr right;

    public Compare(Cmp op, Expr left, Expr right) {
        this.op = Objects.requireNonNull(op, "op");
        this.left = Objects.requireNonNull(left, "left");
        this.right = Objects.requireNonNull(right, "right");
    }
}
