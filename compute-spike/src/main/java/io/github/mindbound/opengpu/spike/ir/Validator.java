package io.github.mindbound.opengpu.spike.ir;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The checks a program passes before the emitter takes it (S14 plan P3.4, amendments F2 and I6): every expression and
 * slot typed, each local slot with one type; a loop's variable an {@code I32} local its body never assigns, and its
 * trip count {@code max(0, (long) to - from)} at most 65 536 (22:122), so no loop runs unbounded and no int overflow
 * hides one; no load from a {@code writeonly} binding and no store to a {@code readonly} one (22:153's D1); at most
 * four
 * bindings, a 1D or 2D grid, and a name that makes a class name. The dispatch-time cost and its cap are M3's (22:216).
 */
public final class Validator {

    /**
     * Bindings per program: four, the spike's fixed {@code Kernel.run} arity, in place of 22's eight (S14 plan P4.4).
     */
    public static final int MAX_BINDINGS = 4;
    /** A loop's iterations (22:122). */
    public static final long MAX_TRIPS = 65536;

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,63}");

    private final Program program;
    /** The slots of the loops around the statement being checked. */
    private final Set<Integer> loopSlots = new HashSet<>();
    private int line;

    private Validator(Program program) {
        this.program = program;
    }

    /** Throws {@link InvalidProgramException} for the first problem found, in program order. */
    public static void check(Program program) {
        new Validator(program).program();
    }

    private void program() {
        if (!NAME.matcher(program.name)
            .matches()) {
            throw fail("the name '" + program.name + "' is not a letter and then at most 63 letters and digits");
        }
        if (program.dimensions != 1 && program.dimensions != 2) {
            throw fail("a grid has 1 or 2 dimensions, not " + program.dimensions);
        }
        if (program.bindings.size() > MAX_BINDINGS) {
            throw fail(program.bindings.size() + " bindings, over " + MAX_BINDINGS);
        }
        block(program.body);
    }

    private void block(List<Stmt> block) {
        for (Stmt s : block) {
            statement(s);
        }
    }

    private void statement(Stmt s) {
        line = s.line;
        if (s.line < 1) {
            throw fail("line " + s.line + " is not a kernel line");
        }
        if (s instanceof Stmt.Assign) {
            Stmt.Assign a = (Stmt.Assign) s;
            Type type = slot(a.slot);
            if (loopSlots.contains(a.slot)) {
                throw fail("local " + a.slot + " is a loop's variable, assigned in its body");
            }
            expect(type, a.value, "local " + a.slot + "'s value");
        } else if (s instanceof Stmt.Store) {
            Stmt.Store st = (Stmt.Store) s;
            if (binding(st.binding) == Access.READONLY) {
                throw fail("a store to binding " + st.binding + ", which is readonly");
            }
            expect(Type.I32, st.index, "a store's index");
            expr(st.value);
        } else if (s instanceof Stmt.If) {
            Stmt.If i = (Stmt.If) s;
            Type left = expr(i.condition.left);
            expect(left, i.condition.right, "a comparison's right operand");
            block(i.then);
            block(i.orElse);
        } else if (s instanceof Stmt.For) {
            Stmt.For f = (Stmt.For) s;
            if (slot(f.slot) != Type.I32) {
                throw fail("loop variable " + f.slot + " is " + program.locals.get(f.slot) + ", not I32");
            }
            if (loopSlots.contains(f.slot)) {
                throw fail("local " + f.slot + " is a loop's variable, assigned in its body by an inner loop");
            }
            long trips = Math.max(0, (long) f.to - f.from);
            if (trips > MAX_TRIPS) {
                throw fail("a loop from " + f.from + " to " + f.to + " runs " + trips + " times, over " + MAX_TRIPS);
            }
            loopSlots.add(f.slot);
            block(f.body);
            loopSlots.remove(f.slot);
        } else if (!(s instanceof Stmt.Return)) {
            throw fail(
                "an unknown statement " + s.getClass()
                    .getName());
        }
    }

    /** The expression's type, once its operands are checked. */
    private Type expr(Expr e) {
        if (e instanceof Expr.Local) {
            Expr.Local l = (Expr.Local) e;
            if (slot(l.slot) != l.type) {
                throw fail("local " + l.slot + " is " + program.locals.get(l.slot) + ", read as " + l.type);
            }
        } else if (e instanceof Expr.Uniform) {
            Expr.Uniform u = (Expr.Uniform) e;
            if (u.index < 0 || u.index >= program.uniforms.size()) {
                throw fail("uniform " + u.index + " is not one of the " + program.uniforms.size());
            }
            if (program.uniforms.get(u.index) != u.type) {
                throw fail("uniform " + u.index + " is " + program.uniforms.get(u.index) + ", read as " + u.type);
            }
        } else if (e instanceof Expr.GlobalId) {
            int axis = ((Expr.GlobalId) e).axis;
            if (axis < 0 || axis >= program.dimensions) {
                throw fail("global id axis " + axis + " in a " + program.dimensions + "D grid");
            }
        } else if (e instanceof Expr.Load) {
            Expr.Load l = (Expr.Load) e;
            if (binding(l.binding) == Access.WRITEONLY) {
                throw fail("a load from binding " + l.binding + ", which is writeonly");
            }
            expect(Type.I32, l.index, "a load's index");
        } else if (e instanceof Expr.Unary) {
            Expr.Unary u = (Expr.Unary) e;
            arity(u.op, 1);
            expect(u.op.operand, u.operand, u.op + "'s operand");
        } else if (e instanceof Expr.Binary) {
            Expr.Binary b = (Expr.Binary) e;
            arity(b.op, 2);
            expect(b.op.operand, b.left, b.op + "'s left operand");
            expect(b.op.operand, b.right, b.op + "'s right operand");
        } else if (!(e instanceof Expr.Const)) {
            throw fail(
                "an unknown expression " + e.getClass()
                    .getName());
        }
        return e.type;
    }

    private void expect(Type type, Expr e, String what) {
        Type actual = expr(e);
        if (actual != type) {
            throw fail(what + " is " + actual + ", not " + type);
        }
    }

    private void arity(Op op, int arity) {
        if (op.arity != arity) {
            throw fail(op + " takes " + op.arity + " operands, given " + arity);
        }
    }

    private Type slot(int slot) {
        if (slot < 0 || slot >= program.locals.size()) {
            throw fail("local " + slot + " is not one of the " + program.locals.size());
        }
        return program.locals.get(slot);
    }

    private Access binding(int binding) {
        if (binding < 0 || binding >= program.bindings.size()) {
            throw fail("binding " + binding + " is not one of the " + program.bindings.size());
        }
        return program.bindings.get(binding);
    }

    private InvalidProgramException fail(String reason) {
        String where = line > 0 ? " at kernel:" + line : "";
        return new InvalidProgramException(program.name + where + ": " + reason);
    }
}
