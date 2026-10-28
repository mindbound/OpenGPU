package io.github.mindbound.opengpu.spike.load;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import io.github.mindbound.opengpu.spike.SpikeKernels;
import io.github.mindbound.opengpu.spike.emit.Emitter;
import io.github.mindbound.opengpu.spike.rt.Kernel;

/**
 * The loader's own layer (22:178; S14 plan P6.1, amendments I4): what the JVM asks a kernel's loader for stays within
 * its exact list, and a class that names {@code java.lang.System}, defined here past the scan, fails with
 * {@code NoClassDefFoundError} when its call runs, since linking is lazy. This test sits in the loader's package for
 * {@code defineUnscanned}, which nothing else calls.
 */
class LoaderTest {

    @Test
    void aKernelsLoaderIsAskedOnlyForTheNamesOnItsList() {
        int[] field = SpikeKernels.heatField();
        int[] words = new int[SpikeKernels.INVOCATIONS];
        Kernel heat = KernelLoader.load(SpikeKernels.heat());
        heat.run(field, words, null, null, SpikeKernels.heatUniforms(), 0, 4, 0, 4);
        Kernel mix = KernelLoader.load(SpikeKernels.mix());
        mix.run(field, words, null, null, SpikeKernels.mixUniforms(), 0, 4, 0, 1);
        Kernel opTable = KernelLoader.load(SpikeKernels.opTable(true));
        int[][] pairs = SpikeKernels.opTablePairs();
        opTable.run(
            pairs[0],
            pairs[1],
            new int[4 * SpikeKernels.columns(true)
                .size()],
            null,
            null,
            0,
            4,
            0,
            1);
        List<String> outside = new ArrayList<>();
        for (Kernel kernel : Arrays.asList(heat, mix, opTable)) {
            Class<?> type = kernel.getClass();
            KernelLoader loader = (KernelLoader) type.getClassLoader();
            Assertions.assertSame(KernelLoader.class.getClassLoader(), loader.getParent(), type.getName());
            for (String name : loader.asked()) {
                if (!KernelLoader.PARENT_NAMES.contains(name) && !name.equals(type.getName())) {
                    outside.add(type.getName() + " asked for " + name);
                }
            }
        }
        Assertions.assertEquals(new ArrayList<String>(), outside);
        // K3 calls all three of Float, Math and StrictMath, so its loader was asked for the whole list
        KernelLoader opTables = (KernelLoader) opTable.getClass()
            .getClassLoader();
        Assertions.assertTrue(
            opTables.asked()
                .containsAll(KernelLoader.PARENT_NAMES),
            () -> String.valueOf(opTables.asked()));
    }

    @Test
    void aSystemCallFailsWithNoClassDefFoundErrorWhenItRuns() {
        String name = Emitter.PACKAGE + ".CallsSystem";
        byte[] bytes = callsSystem(name);
        // The scan refuses it for the call itself, and the loader's layer, shown alone below, stops the same call
        KernelRefusedException refused = Assertions
            .assertThrows(KernelRefusedException.class, () -> KernelScan.check(bytes, name));
        Assertions.assertTrue(
            refused.getMessage()
                .contains("a call to java/lang/System.currentTimeMillis()J"),
            refused::getMessage);
        KernelLoader loader = new KernelLoader(name);
        Kernel kernel = KernelLoader.instantiate(loader.defineUnscanned(bytes));
        // Defined and instantiated: nothing has resolved java.lang.System yet
        Assertions.assertFalse(
            loader.asked()
                .contains("java.lang.System"),
            () -> String.valueOf(loader.asked()));
        NoClassDefFoundError error = Assertions
            .assertThrows(NoClassDefFoundError.class, () -> kernel.run(null, null, null, null, null, 0, 1, 0, 1));
        Assertions.assertTrue(
            error.getMessage()
                .contains("java/lang/System"),
            error::getMessage);
        Assertions.assertTrue(
            loader.asked()
                .contains("java.lang.System"),
            () -> String.valueOf(loader.asked()));
    }

    /**
     * A kernel class whose run calls {@code System.currentTimeMillis()}, which no kernel may name; it is otherwise the
     * shape the scan accepts, so the call is what the scan refuses.
     */
    private static byte[] callsSystem(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
            name.replace('.', '/'),
            null,
            "java/lang/Object",
            new String[] { Kernel.class.getName()
                .replace('.', '/') });
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor run = cw
            .visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STRICT, "run", Emitter.RUN_DESCRIPTOR, null, null);
        run.visitCode();
        run.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "currentTimeMillis", "()J", false);
        run.visitInsn(Opcodes.POP2);
        run.visitInsn(Opcodes.RETURN);
        run.visitMaxs(0, 0);
        run.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
