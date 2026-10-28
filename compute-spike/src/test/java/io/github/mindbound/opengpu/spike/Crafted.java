package io.github.mindbound.opengpu.spike;

import java.util.function.Consumer;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import io.github.mindbound.opengpu.spike.emit.Emitter;
import io.github.mindbound.opengpu.spike.rt.Kernel;

/**
 * A hand-made kernel class for the scan's and the loader's tests: by default one the scan accepts (version 52,
 * {@code public final super}, {@link Kernel} its one interface, a constructor and a strictfp {@code run} that returns),
 * with one field changed for each case. It is written by the unrelocated ASM of testRuntimeClasspath with
 * {@code COMPUTE_MAXS} and no frames, so a case's code need not verify: a refused class is never defined.
 */
final class Crafted {

    /** The binary name every crafted class has. */
    static final String NAME = Emitter.PACKAGE + ".Crafted";

    int version = Opcodes.V1_8;
    int access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
    String name = NAME.replace('.', '/');
    String signature;
    String superName = "java/lang/Object";
    String[] interfaces = { Kernel.class.getName()
        .replace('.', '/') };
    String debug;
    /** False for a class with a constructor alone. */
    boolean run = true;
    int runAccess = Opcodes.ACC_PUBLIC | Opcodes.ACC_STRICT;
    String runSignature;
    String[] runExceptions;
    /** Class-level additions, after visitSource. */
    Consumer<ClassVisitor> members = cv -> {};
    /** Before run's visitCode: parameters and annotations. */
    Consumer<MethodVisitor> runHead = mv -> {};
    /** run's code, before its RETURN. */
    Consumer<MethodVisitor> runCode = mv -> {};

    /** The default class with {@code change} applied. */
    static byte[] with(Consumer<Crafted> change) {
        Crafted c = new Crafted();
        change.accept(c);
        return c.bytes();
    }

    byte[] bytes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(version, access, name, signature, superName, interfaces);
        cw.visitSource(Emitter.SOURCE, debug);
        members.accept(cw);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        if (run) {
            MethodVisitor mv = cw.visitMethod(runAccess, "run", Emitter.RUN_DESCRIPTOR, runSignature, runExceptions);
            runHead.accept(mv);
            mv.visitCode();
            runCode.accept(mv);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
