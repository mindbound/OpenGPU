package io.github.mindbound.opengpu.spike;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ByteVector;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypeReference;

import io.github.mindbound.opengpu.spike.emit.Emitter;
import io.github.mindbound.opengpu.spike.ir.Program;
import io.github.mindbound.opengpu.spike.load.KernelLoader;
import io.github.mindbound.opengpu.spike.load.KernelRefusedException;
import io.github.mindbound.opengpu.spike.load.KernelScan;
import io.github.mindbound.opengpu.spike.rt.Kernel;

/**
 * The scan before {@code defineClass} (22:178; S14 plan P6.2, amendments I13), one crafted class per refusal: P6.2's
 * list case by case, I13's callbacks, and the class's own shape, each the accepted default with one change and each
 * refused with its own reason, so a scan that let one through, or refused it for another, fails here. The class-file
 * walk words its refusals apart from the visitors', and two tests show it refusing alone: in {@code check}, what ASM
 * reads without a callback, and through {@code codeLength}, which runs the walk without the visitors, each attribute
 * and field off its list. Then every class the emitter makes is accepted, and a refused class is never defined.
 */
class ScanTest {

    private static final String RUN = "run" + Emitter.RUN_DESCRIPTOR;
    private static final String KERNEL = Kernel.class.getName()
        .replace('.', '/');
    private static final String DEPRECATED = "Ljava/lang/Deprecated;";
    private static final Handle BOOTSTRAP = new Handle(
        Opcodes.H_INVOKESTATIC,
        "java/lang/invoke/ConstantBootstraps",
        "nullConstant",
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;",
        false);

    private static Arguments refusal(String label, String reason, Consumer<Crafted> change) {
        return Arguments.of(label, reason, Crafted.with(change));
    }

    private static Arguments code(String label, String reason, Consumer<MethodVisitor> code) {
        return refusal(label, reason, c -> c.runCode = code);
    }

    private static String inRun(int opcode) {
        return "opcode " + opcode + " in " + RUN;
    }

    /** run's code: {@code BIPUSH} and {@code POP} when {@code length} is even, then {@code ICONST_0 POP} pairs. */
    static byte[] runOfLength(int length) {
        return Crafted.with(c -> c.runCode = mv -> {
            int left = length - 1;
            if (left % 2 != 0) {
                mv.visitIntInsn(Opcodes.BIPUSH, 1);
                mv.visitInsn(Opcodes.POP);
                left -= 3;
            }
            for (; left > 0; left -= 2) {
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitInsn(Opcodes.POP);
            }
        });
    }

    /** S14 plan P6.2's "Rejected, with a test each". */
    static Stream<Arguments> refusals() {
        return Stream.of(code("INVOKEDYNAMIC", inRun(Opcodes.INVOKEDYNAMIC), mv -> {
            mv.visitInvokeDynamicInsn("get", "()Ljava/lang/Object;", BOOTSTRAP);
            mv.visitInsn(Opcodes.POP);
        }), code("LDC of a Type", "a constant of Type", mv -> {
            mv.visitLdcInsn(Type.getType("Ljava/lang/System;"));
            mv.visitInsn(Opcodes.POP);
        }), code("LDC of a Handle", "a constant of Handle", mv -> {
            mv.visitLdcInsn(BOOTSTRAP);
            mv.visitInsn(Opcodes.POP);
        }), code("LDC of a ConstantDynamic", "a constant of ConstantDynamic", mv -> {
            mv.visitLdcInsn(new ConstantDynamic("none", "Ljava/lang/Object;", BOOTSTRAP));
            mv.visitInsn(Opcodes.POP);
        }), code("NEW", inRun(Opcodes.NEW), mv -> {
            mv.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
            mv.visitInsn(Opcodes.POP);
        }), code("NEWARRAY", inRun(Opcodes.NEWARRAY), mv -> {
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
            mv.visitInsn(Opcodes.POP);
        }), code("ANEWARRAY", inRun(Opcodes.ANEWARRAY), mv -> {
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
            mv.visitInsn(Opcodes.POP);
        }), code("MULTIANEWARRAY", inRun(Opcodes.MULTIANEWARRAY), mv -> {
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitMultiANewArrayInsn("[[I", 2);
            mv.visitInsn(Opcodes.POP);
        }), code("AALOAD", inRun(Opcodes.AALOAD), mv -> {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.AALOAD);
            mv.visitInsn(Opcodes.POP);
        }), code("AASTORE", inRun(Opcodes.AASTORE), mv -> {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitInsn(Opcodes.AASTORE);
        }), code("CHECKCAST", inRun(Opcodes.CHECKCAST), mv -> {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Object");
            mv.visitInsn(Opcodes.POP);
        }), code("INSTANCEOF", inRun(Opcodes.INSTANCEOF), mv -> {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/Object");
            mv.visitInsn(Opcodes.POP);
        }), code("MONITORENTER", inRun(Opcodes.MONITORENTER), mv -> {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitInsn(Opcodes.MONITORENTER);
        }), code("MONITOREXIT", inRun(Opcodes.MONITOREXIT), mv -> {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitInsn(Opcodes.MONITOREXIT);
        }), code("ATHROW", inRun(Opcodes.ATHROW), mv -> {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitInsn(Opcodes.ATHROW);
        }), code("field access", inRun(Opcodes.GETSTATIC), mv -> {
            mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            mv.visitInsn(Opcodes.POP);
        }), code("System.exit", "a call to java/lang/System.exit(I)V", mv -> {
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "exit", "(I)V", false);
        }), code("Object.getClass", "a call to java/lang/Object.getClass()Ljava/lang/Class;", mv -> {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
            mv.visitInsn(Opcodes.POP);
        }), code("Math.sin", "a call to java/lang/Math.sin(D)D", mv -> {
            mv.visitInsn(Opcodes.FCONST_0);
            mv.visitInsn(Opcodes.F2D);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "sin", "(D)D", false);
            mv.visitInsn(Opcodes.D2F);
            mv.visitInsn(Opcodes.POP);
        }), code("a try/catch block", "a try/catch block in " + RUN, mv -> {
            Label start = new Label();
            Label end = new Label();
            Label handler = new Label();
            Label after = new Label();
            mv.visitTryCatchBlock(start, end, handler, "java/lang/RuntimeException");
            mv.visitLabel(start);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(end);
            mv.visitJumpInsn(Opcodes.GOTO, after);
            mv.visitLabel(handler);
            mv.visitInsn(Opcodes.POP);
            mv.visitLabel(after);
        }),
            refusal("version 51", "class file version 51.0, not 52.0", c -> c.version = Opcodes.V1_7),
            refusal("version 53", "class file version 53.0, not 52.0", c -> c.version = Opcodes.V9),
            Arguments.of(
                "a run of 7 500 bytes",
                "method " + RUN + " has 7500 bytes of code, at or over the cap of 7500",
                runOfLength(7500)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void eachListedRejectionIsRefused(String label, String reason, byte[] crafted) {
        assertRefused(label, reason, crafted);
    }

    /** Every callback off the visitors' list (S14 amendments I13) refuses, each with its own reason. */
    @Test
    void everyCallbackOffTheListIsRefused() {
        String inner = Crafted.NAME.replace('.', '/') + "$Inner";
        Map<String, Consumer<Crafted>> cases = new LinkedHashMap<>();
        cases.put("a class attribute OpenGpuExtra", c -> c.members = cv -> cv.visitAttribute(new Extra()));
        cases.put("an inner class", c -> c.members = cv -> cv.visitInnerClass(inner, c.name, "Inner", 0));
        cases.put("an outer class", c -> c.members = cv -> cv.visitOuterClass("java/lang/Object", null, null));
        cases.put("a nest host", c -> c.members = cv -> cv.visitNestHost("java/lang/Object"));
        cases.put("a nest member", c -> c.members = cv -> cv.visitNestMember(inner));
        cases.put("a permitted subclass", c -> c.members = cv -> cv.visitPermittedSubclass(inner));
        // ASM's reader adds ACC_RECORD (0x10000) to the class's access for a Record attribute, so visit refuses first
        cases.put(
            "class access 0x10031, not public final super",
            c -> c.members = cv -> cv.visitRecordComponent("x", "I", null)
                .visitEnd());
        cases.put(
            "a module",
            c -> c.members = cv -> cv.visitModule("kernel", 0, null)
                .visitEnd());
        cases.put(
            "a field",
            c -> c.members = cv -> cv.visitField(Opcodes.ACC_PRIVATE, "f", "I", null, null)
                .visitEnd());
        cases.put(
            "a class annotation",
            c -> c.members = cv -> cv.visitAnnotation(DEPRECATED, true)
                .visitEnd());
        cases.put(
            "a class type annotation",
            c -> c.members = cv -> cv.visitTypeAnnotation(
                TypeReference.newSuperTypeReference(-1)
                    .getValue(),
                null,
                DEPRECATED,
                true)
                .visitEnd());
        cases.put(
            "an annotation in " + RUN,
            c -> c.runHead = mv -> mv.visitAnnotation(DEPRECATED, true)
                .visitEnd());
        cases
            .put(
                "a type annotation in " + RUN,
                c -> c.runHead = mv -> mv
                    .visitTypeAnnotation(
                        TypeReference.newTypeReference(TypeReference.METHOD_RETURN)
                            .getValue(),
                        null,
                        DEPRECATED,
                        true)
                    .visitEnd());
        // ASM 9.10.1's reader calls visitAnnotableParameterCount before any visitParameterAnnotation, so the count's
        // refusal answers; visitParameterAnnotation's own is a backstop the reader never reaches
        cases.put(
            "a parameter annotation count",
            c -> c.runHead = mv -> mv.visitParameterAnnotation(0, DEPRECATED, true)
                .visitEnd());
        cases.put("an annotation default in " + RUN, c -> c.runHead = mv -> {
            AnnotationVisitor value = mv.visitAnnotationDefault();
            value.visit(null, 1);
            value.visitEnd();
        });
        cases.put("a MethodParameters entry in " + RUN, c -> c.runHead = mv -> mv.visitParameter("b0", 0));
        cases.put("a LocalVariableTable entry in " + RUN, c -> c.runCode = mv -> {
            Label start = new Label();
            Label end = new Label();
            mv.visitLabel(start);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitVarInsn(Opcodes.ISTORE, 10);
            mv.visitLabel(end);
            mv.visitLocalVariable("v", "I", null, start, end, 10);
        });
        cases.put("an instruction annotation in " + RUN, c -> c.runCode = mv -> {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitInsn(Opcodes.POP);
            mv.visitInsnAnnotation(
                TypeReference.newTypeReference(TypeReference.INSTANCEOF)
                    .getValue(),
                null,
                DEPRECATED,
                true)
                .visitEnd();
        });
        cases.put("a SourceDebugExtension", c -> c.debug = "SMAP");
        cases.put("a signature on " + RUN, c -> c.runSignature = Emitter.RUN_DESCRIPTOR);
        cases.put("exceptions on " + RUN, c -> c.runExceptions = new String[] { "java/lang/Exception" });
        assertEachRefused(cases);
    }

    @Test
    void aClassOfAnotherShapeIsRefused() {
        String other = Emitter.PACKAGE.replace('.', '/') + "/Other";
        Map<String, Consumer<Crafted>> cases = new LinkedHashMap<>();
        cases.put("class access 0x21, not public final super", c -> c.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER);
        cases.put("the class file is " + other, c -> c.name = other);
        cases.put("a class signature", c -> c.signature = "Ljava/lang/Object;L" + KERNEL + ";");
        cases.put("superclass java/lang/Thread", c -> c.superName = "java/lang/Thread");
        cases.put("interfaces [], not", c -> c.interfaces = new String[0]);
        cases.put(
            "interfaces [" + KERNEL + ", java/lang/Runnable]",
            c -> c.interfaces = new String[] { KERNEL, "java/lang/Runnable" });
        cases.put("method <clinit>()V with access 0x8", c -> c.members = cv -> {
            MethodVisitor mv = cv.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        cases.put("method helper()V with access 0x9", c -> c.members = cv -> {
            MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "helper", "()V", null, null);
            mv.visitCode();
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        cases.put("method " + RUN + " with access 0x1", c -> c.runAccess = Opcodes.ACC_PUBLIC);
        cases.put("not both a public <init>()V and a public strictfp run", c -> c.run = false);
        cases.put("a constant of String", c -> c.runCode = mv -> {
            mv.visitLdcInsn("x");
            mv.visitInsn(Opcodes.POP);
        });
        cases.put("a constant of Long", c -> c.runCode = mv -> {
            mv.visitLdcInsn(7L);
            mv.visitInsn(Opcodes.POP2);
        });
        cases.put(inRun(Opcodes.DCONST_0), c -> c.runCode = mv -> {
            mv.visitInsn(Opcodes.DCONST_0);
            mv.visitInsn(Opcodes.POP2);
        });
        cases.put(inRun(Opcodes.NOP), c -> c.runCode = mv -> mv.visitInsn(Opcodes.NOP));
        cases.put(inRun(Opcodes.ASTORE), c -> c.runCode = mv -> {
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ASTORE, 10);
        });
        cases.put(inRun(Opcodes.IFNULL), c -> c.runCode = mv -> {
            Label next = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitJumpInsn(Opcodes.IFNULL, next);
            mv.visitLabel(next);
        });
        cases.put(inRun(Opcodes.TABLESWITCH), c -> c.runCode = mv -> {
            Label next = new Label();
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitTableSwitchInsn(0, 0, next, next);
            mv.visitLabel(next);
        });
        cases.put(inRun(Opcodes.LOOKUPSWITCH), c -> c.runCode = mv -> {
            Label next = new Label();
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitLookupSwitchInsn(next, new int[] { 5 }, new Label[] { next });
            mv.visitLabel(next);
        });
        cases.put("a call to java/lang/Object.<init>()V in " + RUN, c -> c.runCode = mv -> {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        });
        cases.put("a call to java/lang/Float.floatToRawIntBits(F)I", c -> c.runCode = mv -> {
            mv.visitInsn(Opcodes.FCONST_0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "floatToRawIntBits", "(F)I", false);
            mv.visitInsn(Opcodes.POP);
        });
        cases.put("a call to " + KERNEL + ".run", c -> c.runCode = mv -> {
            for (int slot = 0; slot <= 5; slot++) {
                mv.visitVarInsn(Opcodes.ALOAD, slot);
            }
            for (int slot = 6; slot <= 9; slot++) {
                mv.visitVarInsn(Opcodes.ILOAD, slot);
            }
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, KERNEL, "run", Emitter.RUN_DESCRIPTOR, true);
        });
        assertEachRefused(cases);
    }

    @Test
    void whatAsmReadsWithoutACallbackIsRefusedByTheWalk() {
        Map<String, Consumer<Crafted>> cases = new LinkedHashMap<>();
        // An unused BootstrapMethods table, which ASM's reader takes in without telling a visitor
        cases.put(
            "a ClassFile attribute BootstrapMethods",
            c -> c.members = cv -> ((ClassWriter) cv).newConstantDynamic("none", "Ljava/lang/Object;", BOOTSTRAP));
        // A LocalVariableTypeTable, which the reader reads only beside a LocalVariableTable
        cases.put(
            "a Code attribute LocalVariableTypeTable in " + RUN,
            c -> c.runCode = mv -> mv.visitAttribute(new Extra("LocalVariableTypeTable", true)));
        assertEachRefused(cases);
    }

    @Test
    void theWalkAloneRefusesEachAttributeAndFieldOffItsList() {
        // codeLength reads the class file through the walk alone, without the visitors that refuse these first
        Map<String, Consumer<Crafted>> cases = new LinkedHashMap<>();
        cases.put("a ClassFile attribute OpenGpuExtra", c -> c.members = cv -> cv.visitAttribute(new Extra()));
        cases.put(
            "a method_info attribute OpenGpuExtra on " + RUN,
            c -> c.runHead = mv -> mv.visitAttribute(new Extra()));
        cases.put(
            "a Code attribute OpenGpuExtra in " + RUN,
            c -> c.runCode = mv -> mv.visitAttribute(new Extra("OpenGpuExtra", true)));
        cases.put(
            "a ClassFile fields_count of 1",
            c -> c.members = cv -> cv.visitField(Opcodes.ACC_PRIVATE, "f", "I", null, null)
                .visitEnd());
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Consumer<Crafted>> c : cases.entrySet()) {
            byte[] crafted = Crafted.with(c.getValue());
            try {
                KernelScan.codeLength(crafted, "run");
                wrong.add(c.getKey() + ": walked");
            } catch (KernelRefusedException e) {
                if (!e.getMessage()
                    .contains(c.getKey())) {
                    wrong.add(c.getKey() + ": refused for another reason, " + e.getMessage());
                }
            }
        }
        Assertions.assertEquals(new ArrayList<String>(), wrong);
    }

    @Test
    void everyClassTheEmitterMakesIsAccepted() {
        List<Program> programs = Arrays.asList(
            SpikeKernels.heat(),
            SpikeKernels.mix(),
            SpikeKernels.opTable(true),
            SpikeKernels.opTable(false),
            EmitterRulesTest.sized(600),
            EmitterRulesTest.outOfBounds(),
            EmitterRulesTest.loopReturns(),
            EmitterRulesTest.bothReturn(),
            EmitterRulesTest.afterReturn());
        for (Program program : programs) {
            KernelScan.check(Emitter.emit(program), Emitter.className(program));
        }
        // And the crafted default, with run 1 byte under the cap
        KernelScan.check(Crafted.with(c -> {}), Crafted.NAME);
        KernelScan.check(runOfLength(7499), Crafted.NAME);
    }

    @Test
    void aRefusedClassIsNeverDefined() throws Exception {
        KernelLoader refused = new KernelLoader(Crafted.NAME);
        byte[] allocates = Crafted.with(c -> c.runCode = mv -> {
            mv.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
            mv.visitInsn(Opcodes.POP);
        });
        Assertions.assertThrows(KernelRefusedException.class, () -> refused.define(allocates));
        Assertions.assertThrows(ClassNotFoundException.class, () -> refused.loadClass(Crafted.NAME));
        // The control: the default class, through the same steps, is defined and runs
        KernelLoader accepted = new KernelLoader(Crafted.NAME);
        Class<?> type = accepted.define(Crafted.with(c -> {}));
        Assertions.assertSame(type, accepted.loadClass(Crafted.NAME));
        KernelLoader.instantiate(type)
            .run(null, null, null, null, null, 0, 1, 0, 1);
    }

    private static void assertEachRefused(Map<String, Consumer<Crafted>> cases) {
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Consumer<Crafted>> c : cases.entrySet()) {
            try {
                KernelScan.check(Crafted.with(c.getValue()), Crafted.NAME);
                wrong.add(c.getKey() + ": accepted");
            } catch (KernelRefusedException e) {
                if (!e.getMessage()
                    .contains(c.getKey())) {
                    wrong.add(c.getKey() + ": refused for another reason, " + e.getMessage());
                }
            }
        }
        Assertions.assertEquals(new ArrayList<String>(), wrong);
    }

    private static void assertRefused(String label, String reason, byte[] crafted) {
        KernelRefusedException e = Assertions
            .assertThrows(KernelRefusedException.class, () -> KernelScan.check(crafted, Crafted.NAME), label);
        Assertions.assertTrue(
            e.getMessage()
                .contains(reason),
            () -> label + " was refused for another reason: " + e.getMessage());
    }

    /**
     * An attribute of the class or a method, or with {@code inCode} of a Code attribute, written with an empty body but
     * for a LocalVariableTypeTable's zero length. Its default name is one ASM does not know, so its reader hands it to
     * {@code visitAttribute}.
     */
    private static final class Extra extends Attribute {

        private final boolean inCode;

        Extra() {
            this("OpenGpuExtra", false);
        }

        Extra(String type, boolean inCode) {
            super(type);
            this.inCode = inCode;
        }

        @Override
        public boolean isCodeAttribute() {
            return inCode;
        }

        @Override
        protected ByteVector write(ClassWriter classWriter, byte[] code, int codeLength, int maxStack, int maxLocals) {
            ByteVector body = new ByteVector();
            return "LocalVariableTypeTable".equals(type) ? body.putShort(0) : body;
        }
    }
}
