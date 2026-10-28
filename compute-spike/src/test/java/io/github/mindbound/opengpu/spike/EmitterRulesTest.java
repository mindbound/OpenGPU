package io.github.mindbound.opengpu.spike;

import static io.github.mindbound.opengpu.spike.ir.Ir.cmp;
import static io.github.mindbound.opengpu.spike.ir.Ir.gid;
import static io.github.mindbound.opengpu.spike.ir.Ir.i32;
import static io.github.mindbound.opengpu.spike.ir.Ir.load;
import static io.github.mindbound.opengpu.spike.ir.Ir.op;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.spike.emit.Emitter;
import io.github.mindbound.opengpu.spike.ir.Access;
import io.github.mindbound.opengpu.spike.ir.Cmp;
import io.github.mindbound.opengpu.spike.ir.Op;
import io.github.mindbound.opengpu.spike.ir.Program;
import io.github.mindbound.opengpu.spike.ir.ProgramBuilder;
import io.github.mindbound.opengpu.spike.ir.Type;
import io.github.mindbound.opengpu.spike.load.KernelFault;
import io.github.mindbound.opengpu.spike.load.KernelLoader;
import io.github.mindbound.opengpu.spike.load.KernelRefusedException;
import io.github.mindbound.opengpu.spike.load.KernelScan;
import io.github.mindbound.opengpu.spike.rt.Kernel;

/**
 * Four of the emitter's rules: a fault names {@code kernel:LINE} (22:180), in a report built without the exception's
 * message, so it is the same string on Java 8 and 21 (S14 amendments I8); a method of 7 500 bytes or more is refused
 * before {@code defineClass} (22:179), on {@link KernelLoader#load}'s own path too; the writer's
 * {@code getCommonSuperClass} throws instead of loading (22:177); and no code that cannot be reached is emitted: no
 * statement after a {@code Return} or after an {@code If} whose arms both return, and no back edge after a loop body
 * that returns, since {@code COMPUTE_FRAMES} would turn such code into {@code NOP}s and an {@code ATHROW} that the
 * scan refuses. Each such program runs two invocations through the loader into literal words.
 */
class EmitterRulesTest {

    /** Line 3 loads {@code in[x + 5]}, out of bounds of a 3-word buffer. */
    static Program outOfBounds() {
        ProgramBuilder k = new ProgramBuilder("Oob", 1);
        int in = k.binding(Access.READONLY);
        int out = k.binding(Access.WRITEONLY);
        int v = k.local(Type.I32);
        k.assign(v, i32(1));
        k.assign(v, op(Op.IADD, k.get(v), gid(0)));
        k.assign(v, load(in, op(Op.IADD, gid(0), i32(5)), Type.I32));
        k.store(out, gid(0), k.get(v));
        return k.build();
    }

    /** {@code statements} increments of b0's words, 8 to 12 bytes of code each: 600 fit under the cap, 700 do not. */
    static Program sized(int statements) {
        ProgramBuilder k = new ProgramBuilder("Sized" + statements, 1);
        int words = k.binding(Access.READ_WRITE);
        for (int s = 0; s < statements; s++) {
            k.store(words, i32(s), op(Op.IADD, load(words, i32(s), Type.I32), i32(1)));
        }
        return k.build();
    }

    /** A loop of 4 whose body stores {@code j + 10} and returns, then a store of 99 that never runs. */
    static Program loopReturns() {
        ProgramBuilder k = new ProgramBuilder("LoopReturns", 1);
        int out = k.binding(Access.READ_WRITE);
        int j = k.local(Type.I32);
        k.loop(j, 0, 4, () -> {
            k.store(out, gid(0), op(Op.IADD, k.get(j), i32(10)));
            k.ret();
        });
        k.store(out, gid(0), i32(99));
        return k.build();
    }

    /** An if whose arms store 1 or 2 and both return, then a store of 99 that never runs. */
    static Program bothReturn() {
        ProgramBuilder k = new ProgramBuilder("BothReturn", 1);
        int out = k.binding(Access.READ_WRITE);
        k.ifElse(cmp(Cmp.EQ, gid(0), i32(0)), () -> {
            k.store(out, gid(0), i32(1));
            k.ret();
        }, () -> {
            k.store(out, gid(0), i32(2));
            k.ret();
        });
        k.store(out, gid(0), i32(99));
        return k.build();
    }

    /** A store of 3, a return, then a store of 99 that never runs. */
    static Program afterReturn() {
        ProgramBuilder k = new ProgramBuilder("AfterReturn", 1);
        int out = k.binding(Access.READ_WRITE);
        k.store(out, gid(0), i32(3));
        k.ret();
        k.store(out, gid(0), i32(99));
        return k.build();
    }

    /** The program's words after two invocations, through {@link KernelLoader#load}'s emit, scan and define. */
    private static int[] twoInvocations(Program program) {
        int[] words = new int[2];
        KernelLoader.load(program)
            .run(words, null, null, null, null, 0, 2, 0, 1);
        return words;
    }

    @Test
    void aColdOutOfBoundsLoadIsReportedAtItsKernelLine() {
        Program program = outOfBounds();
        Kernel kernel = KernelLoader.load(program);
        ArrayIndexOutOfBoundsException fault = Assertions.assertThrows(
            ArrayIndexOutOfBoundsException.class,
            () -> kernel.run(new int[3], new int[3], null, null, null, 0, 1, 0, 1));
        Assertions.assertEquals("Oob: a buffer index out of bounds at kernel:3", KernelFault.report(program, fault));
    }

    @Test
    void aRunOf7500BytesOrMoreIsRefusedBeforeDefineClass() throws Exception {
        Program fits = sized(600);
        byte[] small = Emitter.emit(fits);
        Assertions.assertTrue(KernelScan.codeLength(small, "run") < KernelScan.CODE_LENGTH_CAP);
        int[] words = new int[600];
        KernelLoader.load(fits)
            .run(words, null, null, null, null, 0, 2, 0, 1);
        Assertions.assertEquals(2, words[599]);

        Program over = sized(700);
        byte[] large = Emitter.emit(over);
        int length = KernelScan.codeLength(large, "run");
        Assertions.assertTrue(length >= KernelScan.CODE_LENGTH_CAP, () -> "run is only " + length + " bytes");
        KernelLoader loader = new KernelLoader(Emitter.className(over));
        KernelRefusedException refused = Assertions
            .assertThrows(KernelRefusedException.class, () -> loader.define(large));
        Assertions.assertTrue(
            refused.getMessage()
                .contains("has " + length + " bytes of code, at or over the cap of 7500"),
            refused::getMessage);
        Assertions.assertThrows(ClassNotFoundException.class, () -> loader.loadClass(Emitter.className(over)));
        // And on the path every kernel takes, where load's own scan is what refuses it
        Assertions.assertThrows(KernelRefusedException.class, () -> KernelLoader.load(over));
    }

    @Test
    void aLoopWhoseBodyReturnsRunsItOnce() {
        Assertions.assertArrayEquals(new int[] { 10, 10 }, twoInvocations(loopReturns()));
    }

    @Test
    void anIfWhoseArmsBothReturnEndsTheInvocation() {
        Assertions.assertArrayEquals(new int[] { 1, 2 }, twoInvocations(bothReturn()));
    }

    @Test
    void statementsAfterAReturnNeverRun() {
        Assertions.assertArrayEquals(new int[] { 3, 3 }, twoInvocations(afterReturn()));
    }

    @Test
    void theWritersCommonSuperClassThrowsInsteadOfLoading() throws Exception {
        // Through reflection, so the API keeps no ASM type (S14 plan P4.8); ASM's own would throw
        // TypeNotPresentException for these two names, after trying to load them
        Class<?> writer = Class.forName("io.github.mindbound.opengpu.spike.emit.NonLoadingWriter");
        Constructor<?> make = writer.getDeclaredConstructor();
        make.setAccessible(true);
        Method merge = writer.getSuperclass()
            .getDeclaredMethod("getCommonSuperClass", String.class, String.class);
        merge.setAccessible(true);
        Object instance = make.newInstance();
        InvocationTargetException thrown = Assertions.assertThrows(
            InvocationTargetException.class,
            () -> merge.invoke(instance, Emitter.PACKAGE.replace('.', '/') + "/A", "java/lang/Object"));
        Assertions.assertEquals(
            IllegalStateException.class,
            thrown.getCause()
                .getClass(),
            () -> String.valueOf(thrown.getCause()));
    }
}
