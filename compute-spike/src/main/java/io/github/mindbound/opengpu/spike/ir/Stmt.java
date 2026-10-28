package io.github.mindbound.opengpu.spike.ir;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A statement of the spike's IR (S14 plan P3.3), each with its kernel line, which the emitter writes to the class's
 * LineNumberTable so a fault names {@code kernel:LINE} (22:180). Control flow is structured: {@code If}, a counted
 * {@code For} with constant bounds (S14 amendments F2), and {@code Return}, which ends the invocation.
 */
public abstract class Stmt {

    public final int line;

    Stmt(int line) {
        this.line = line;
    }

    static List<Stmt> copy(List<Stmt> block, String what) {
        List<Stmt> copy = new ArrayList<>(Objects.requireNonNull(block, what));
        for (Stmt s : copy) {
            Objects.requireNonNull(s, what);
        }
        return Collections.unmodifiableList(copy);
    }

    /** {@code local[slot] = value}. */
    public static final class Assign extends Stmt {

        public final int slot;
        public final Expr value;

        public Assign(int line, int slot, Expr value) {
            super(line);
            this.slot = slot;
            this.value = Objects.requireNonNull(value, "value");
        }
    }

    /** {@code binding[index] = value}: a float through {@code Float.floatToIntBits}, so every NaN is 0x7fc00000. */
    public static final class Store extends Stmt {

        public final int binding;
        public final Expr index;
        public final Expr value;

        public Store(int line, int binding, Expr index, Expr value) {
            super(line);
            this.binding = binding;
            this.index = Objects.requireNonNull(index, "index");
            this.value = Objects.requireNonNull(value, "value");
        }
    }

    public static final class If extends Stmt {

        public final Compare condition;
        public final List<Stmt> then;
        public final List<Stmt> orElse;

        public If(int line, Compare condition, List<Stmt> then, List<Stmt> orElse) {
            super(line);
            this.condition = Objects.requireNonNull(condition, "condition");
            this.then = copy(then, "then");
            this.orElse = copy(orElse, "orElse");
        }
    }

    /** {@code for (local[slot] = from; local[slot] < to; local[slot]++) body}, bounds constant (S14 amendments F2). */
    public static final class For extends Stmt {

        public final int slot;
        public final int from;
        public final int to;
        public final List<Stmt> body;

        public For(int line, int slot, int from, int to, List<Stmt> body) {
            super(line);
            this.slot = slot;
            this.from = from;
            this.to = to;
            this.body = copy(body, "body");
        }
    }

    /** Ends this invocation; the dispatch goes on with the next. */
    public static final class Return extends Stmt {

        public Return(int line) {
            super(line);
        }
    }
}
