package io.github.mindbound.opengpu.spike;

import static io.github.mindbound.opengpu.spike.ir.Ir.cmp;
import static io.github.mindbound.opengpu.spike.ir.Ir.f32;
import static io.github.mindbound.opengpu.spike.ir.Ir.gid;
import static io.github.mindbound.opengpu.spike.ir.Ir.i32;
import static io.github.mindbound.opengpu.spike.ir.Ir.load;
import static io.github.mindbound.opengpu.spike.ir.Ir.op;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.spike.ir.Access;
import io.github.mindbound.opengpu.spike.ir.Cmp;
import io.github.mindbound.opengpu.spike.ir.Expr;
import io.github.mindbound.opengpu.spike.ir.InvalidProgramException;
import io.github.mindbound.opengpu.spike.ir.Op;
import io.github.mindbound.opengpu.spike.ir.Program;
import io.github.mindbound.opengpu.spike.ir.ProgramBuilder;
import io.github.mindbound.opengpu.spike.ir.Stmt;
import io.github.mindbound.opengpu.spike.ir.Type;
import io.github.mindbound.opengpu.spike.ir.Validator;

/**
 * The validator (S14 plan P3.4, amendments F2 and I6): types and one type per slot, a loop's variable its body never
 * assigns, a trip count computed in {@code long} and capped at 65 536 (22:122), and 22:153's D1 on every load and
 * store. Each refused program differs from an accepted one by the one change named, and the message names the line.
 */
class ValidatorTest {

    /** A 1D program with an I32 local 0, an F32 local 1, a readonly, a writeonly and a read-write binding. */
    private static Program program(Consumer<ProgramBuilder> body) {
        ProgramBuilder k = new ProgramBuilder("Checked", 1);
        k.local(Type.I32);
        k.local(Type.F32);
        k.binding(Access.READONLY);
        k.binding(Access.WRITEONLY);
        k.binding(Access.READ_WRITE);
        k.uniform(Type.F32);
        body.accept(k);
        return k.build();
    }

    private static void assertRefused(String fragment, Program program) {
        InvalidProgramException e = Assertions
            .assertThrows(InvalidProgramException.class, () -> Validator.check(program), "accepted: " + fragment);
        Assertions.assertTrue(
            e.getMessage()
                .contains(fragment),
            () -> "'" + e.getMessage() + "' does not say '" + fragment + "'");
    }

    @Test
    void everyOperandAndSlotHasItsType() {
        Map<String, Consumer<ProgramBuilder>> refused = new LinkedHashMap<>();
        refused.put("IADD's right operand is F32, not I32", k -> k.assign(0, op(Op.IADD, i32(1), f32(1f))));
        refused.put("FSQRT's operand is I32, not F32", k -> k.assign(1, op(Op.FSQRT, i32(4))));
        refused.put("INEG takes 1 operands, given 2", k -> k.assign(0, op(Op.INEG, i32(1), i32(2))));
        refused.put("IADD takes 2 operands, given 1", k -> k.assign(0, op(Op.IADD, i32(1))));
        refused.put("local 0's value is F32, not I32", k -> k.assign(0, f32(1f)));
        refused.put("local 0 is I32, read as F32", k -> k.assign(1, new Expr.Local(0, Type.F32)));
        refused.put("local 2 is not one of the 2", k -> k.assign(2, i32(0)));
        refused.put("a store's index is F32, not I32", k -> k.store(1, f32(0f), i32(0)));
        refused.put("a load's index is F32, not I32", k -> k.assign(0, load(0, f32(0f), Type.I32)));
        refused
            .put("a comparison's right operand is F32, not I32", k -> k.ifThen(cmp(Cmp.LT, i32(0), f32(0f)), k::ret));
        refused.put("uniform 0 is F32, read as I32", k -> k.assign(0, new Expr.Uniform(0, Type.I32)));
        refused.put("uniform 1 is not one of the 1", k -> k.assign(1, new Expr.Uniform(1, Type.F32)));
        refused.put("global id axis 1 in a 1D grid", k -> k.assign(0, gid(1)));
        refused.put("binding 3 is not one of the 3", k -> k.store(3, i32(0), i32(0)));
        for (Map.Entry<String, Consumer<ProgramBuilder>> c : refused.entrySet()) {
            assertRefused(c.getKey(), program(c.getValue()));
        }
        // The same shapes, typed right
        Validator.check(program(k -> {
            k.assign(0, op(Op.IADD, i32(1), gid(0)));
            k.assign(1, op(Op.FSQRT, new Expr.Uniform(0, Type.F32)));
            k.ifThen(cmp(Cmp.LT, k.get(1), f32(0f)), () -> k.store(1, i32(0), load(2, k.get(0), Type.F32)));
        }));
    }

    @Test
    void theProgramsShapeIsBounded() {
        assertRefused("is not a letter", new ProgramBuilder("9lives", 1).build());
        assertRefused("is not a letter", new ProgramBuilder("a.b", 1).build());
        assertRefused("a grid has 1 or 2 dimensions, not 3", new ProgramBuilder("Grid", 3).build());
        ProgramBuilder five = new ProgramBuilder("Five", 1);
        for (int b = 0; b < 5; b++) {
            five.binding(Access.READ_WRITE);
        }
        assertRefused("5 bindings, over 4", five.build());
        Program line0 = new Program(
            "Line0",
            Collections.<Type>emptyList(),
            Collections.<Access>emptyList(),
            Collections.<Type>emptyList(),
            1,
            Collections.<Stmt>singletonList(new Stmt.Return(0)));
        assertRefused("line 0 is not a kernel line", line0);
    }

    @Test
    void aLoopsVariableIsNeverAssignedInItsBody() {
        assertRefused(
            "Checked at kernel:2: local 0 is a loop's variable, assigned in its body",
            program(k -> k.loop(0, 0, 4, () -> k.assign(0, i32(1)))));
        assertRefused(
            "kernel:3: local 0 is a loop's variable, assigned in its body",
            program(k -> k.loop(0, 0, 4, () -> k.ifThen(cmp(Cmp.EQ, k.get(0), i32(2)), () -> k.assign(0, i32(9))))));
        assertRefused(
            "local 0 is a loop's variable, assigned in its body by an inner loop",
            program(k -> k.loop(0, 0, 4, () -> k.loop(0, 0, 2, k::ret))));
        assertRefused("loop variable 1 is F32, not I32", program(k -> k.loop(1, 0, 4, k::ret)));
        // Reading it, and reusing the slot after the loop, are fine
        Validator.check(program(k -> {
            k.loop(0, 0, 4, () -> k.store(1, k.get(0), k.get(0)));
            k.assign(0, i32(7));
            k.loop(0, 0, 2, k::ret);
        }));
    }

    @Test
    void aTripCountIsComputedInLongAndCappedAt65536() {
        // int arithmetic would make MIN_VALUE..MAX_VALUE -1 trips, so 0, and accept it (S14 amendments I6)
        assertRefused(
            "runs 4294967295 times, over 65536",
            program(k -> k.loop(0, Integer.MIN_VALUE, Integer.MAX_VALUE, k::ret)));
        assertRefused("runs 65537 times", program(k -> k.loop(0, 0, 65537, k::ret)));
        assertRefused("runs 65537 times", program(k -> k.loop(0, -65536, 1, k::ret)));
        Validator.check(program(k -> k.loop(0, 0, 65536, k::ret)));
        Validator.check(program(k -> k.loop(0, Integer.MIN_VALUE, Integer.MIN_VALUE + 65536, k::ret)));
        Validator.check(program(k -> k.loop(0, Integer.MAX_VALUE - 65536, Integer.MAX_VALUE, k::ret)));
        // A loop whose bound is below its start runs no times
        Validator.check(program(k -> k.loop(0, Integer.MAX_VALUE, Integer.MIN_VALUE, k::ret)));
    }

    @Test
    void writeonlyIsNeverLoadedAndReadonlyNeverStored() {
        // 22:153's D1; bindings 0 readonly, 1 writeonly, 2 read-write
        assertRefused(
            "a load from binding 1, which is writeonly",
            program(k -> k.assign(0, load(1, i32(0), Type.I32))));
        assertRefused("a store to binding 0, which is readonly", program(k -> k.store(0, i32(0), i32(0))));
        Validator.check(program(k -> {
            k.assign(0, load(0, i32(0), Type.I32));
            k.store(1, i32(0), k.get(0));
            k.store(2, i32(0), op(Op.IADD, load(2, i32(0), Type.I32), i32(1)));
        }));
    }
}
