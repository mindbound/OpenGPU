package io.github.mindbound.opengpu.spike.load;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.TypePath;

import io.github.mindbound.opengpu.spike.emit.Emitter;
import io.github.mindbound.opengpu.spike.rt.Kernel;

/**
 * The scan of a kernel's final bytes before {@code defineClass} (22:178, 24:162, 09:77; S14 plan P6.2, amendments
 * I13): a {@code ClassReader} pass over what {@code ClassWriter.toByteArray()} gave, not over the emitter's intent,
 * whose visitors refuse in every callback but {@code visit}, {@code visitSource} without a debug extension,
 * {@code visitMethod} without a signature or exceptions, and in code {@code visitCode}, {@code visitFrame},
 * {@code visitLabel}, {@code visitLineNumber}, {@code visitMaxs}, {@code visitEnd} and the instructions on the list.
 * Then a walk of the class file reads each method's {@code code_length} and refuses one of 7 500 bytes or more
 * (22:179: such a method is never JIT-compiled at 8 000), and any attribute but Code, StackMapTable, LineNumberTable
 * and SourceFile, which also catches those ASM reads without a callback, such as an unused BootstrapMethods or a
 * LocalVariableTypeTable without its LocalVariableTable. The walk names what it refuses by the class file's own
 * structures (ClassFile, method_info, Code; JVMS 4.1, 4.6, 4.7.3), in words of its own, so a test can tell it from the
 * visitors' refusals. The class itself must be version 52, {@code public final super}, extend {@code Object},
 * implement {@link Kernel} alone, and hold a public constructor and {@code run} and nothing else. The loader's exact
 * list is the second layer.
 */
public final class KernelScan {

    /** A method's code of this many bytes or more is refused (22:179). */
    public static final int CODE_LENGTH_CAP = 7500;

    private static final String KERNEL = Kernel.class.getName()
        .replace('.', '/');
    private static final String OBJECT = "java/lang/Object";

    /** Zero-operand instructions: constants, int[] access, int and float arithmetic, compares, stack ops, return. */
    private static final BitSet INSNS = opcodes(
        Opcodes.ICONST_M1,
        Opcodes.ICONST_0,
        Opcodes.ICONST_1,
        Opcodes.ICONST_2,
        Opcodes.ICONST_3,
        Opcodes.ICONST_4,
        Opcodes.ICONST_5,
        Opcodes.FCONST_0,
        Opcodes.FCONST_1,
        Opcodes.FCONST_2,
        Opcodes.IALOAD,
        Opcodes.IASTORE,
        Opcodes.ARRAYLENGTH,
        Opcodes.POP,
        Opcodes.POP2,
        Opcodes.DUP,
        Opcodes.DUP_X1,
        Opcodes.DUP_X2,
        Opcodes.DUP2,
        Opcodes.DUP2_X1,
        Opcodes.DUP2_X2,
        Opcodes.SWAP,
        Opcodes.IADD,
        Opcodes.ISUB,
        Opcodes.IMUL,
        Opcodes.IDIV,
        Opcodes.IREM,
        Opcodes.INEG,
        Opcodes.IAND,
        Opcodes.IOR,
        Opcodes.IXOR,
        Opcodes.ISHL,
        Opcodes.ISHR,
        Opcodes.IUSHR,
        Opcodes.FADD,
        Opcodes.FSUB,
        Opcodes.FMUL,
        Opcodes.FDIV,
        Opcodes.FREM,
        Opcodes.FNEG,
        Opcodes.FCMPL,
        Opcodes.FCMPG,
        Opcodes.I2F,
        Opcodes.F2I,
        Opcodes.F2D,
        Opcodes.D2F,
        Opcodes.RETURN);
    private static final BitSet VARS = opcodes(
        Opcodes.ILOAD,
        Opcodes.FLOAD,
        Opcodes.ALOAD,
        Opcodes.ISTORE,
        Opcodes.FSTORE);
    private static final BitSet JUMPS = opcodes(
        Opcodes.IFEQ,
        Opcodes.IFNE,
        Opcodes.IFLT,
        Opcodes.IFGE,
        Opcodes.IFGT,
        Opcodes.IFLE,
        Opcodes.IF_ICMPEQ,
        Opcodes.IF_ICMPNE,
        Opcodes.IF_ICMPLT,
        Opcodes.IF_ICMPGE,
        Opcodes.IF_ICMPGT,
        Opcodes.IF_ICMPLE,
        Opcodes.GOTO);
    /** The calls the emitter makes, all {@code INVOKESTATIC}, as owner.name descriptor: exactly these. */
    private static final Set<String> CALLS = Collections.unmodifiableSet(
        new HashSet<>(
            Arrays.asList(
                "java/lang/Float.intBitsToFloat(I)F",
                "java/lang/Float.floatToIntBits(F)I",
                "java/lang/Math.sqrt(D)D",
                "java/lang/StrictMath.sin(D)D",
                "java/lang/StrictMath.log1p(D)D")));
    private static final String SUPER_INIT = OBJECT + ".<init>()V";
    private static final Set<String> CODE_ATTRIBUTES = Collections
        .unmodifiableSet(new HashSet<>(Arrays.asList("StackMapTable", "LineNumberTable")));

    private KernelScan() {}

    /**
     * Throws {@link KernelRefusedException} unless {@code classFile} is a kernel class named {@code className} (a
     * binary name) that the scan accepts.
     */
    public static void check(byte[] classFile, String className) {
        String name = className.replace('.', '/');
        ClassReader reader = read(classFile, name);
        try {
            reader.accept(new ClassScan(name), 0);
        } catch (KernelRefusedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new KernelRefusedException(name, "a class file ASM cannot read: " + e);
        }
        for (Map.Entry<String, Integer> method : walk(reader, name).entrySet()) {
            if (method.getValue() >= CODE_LENGTH_CAP) {
                throw new KernelRefusedException(
                    name,
                    "method " + method.getKey()
                        + " has "
                        + method.getValue()
                        + " bytes of code, at or over the cap of "
                        + CODE_LENGTH_CAP);
            }
        }
    }

    /** The {@code code_length} of the class's method {@code name}, read from the class file, or -1 if it has none. */
    public static int codeLength(byte[] classFile, String name) {
        for (Map.Entry<String, Integer> method : walk(read(classFile, "?"), "?").entrySet()) {
            if (method.getKey()
                .startsWith(name + "(")) {
                return method.getValue();
            }
        }
        return -1;
    }

    private static ClassReader read(byte[] classFile, String name) {
        try {
            return new ClassReader(classFile);
        } catch (RuntimeException e) {
            throw new KernelRefusedException(name, "a class file ASM cannot read: " + e);
        }
    }

    /**
     * Walks the class file (JVMS 4.1, 4.6, 4.7.3) from {@code ClassReader.header}, refusing an attribute off the list,
     * and returns each method's {@code code_length} by name and descriptor.
     */
    private static Map<String, Integer> walk(ClassReader r, String name) {
        try {
            char[] chars = new char[r.getMaxStringLength()];
            // access_flags, this_class and super_class, then the interfaces
            int p = r.header + 6;
            p += 2 + 2 * r.readUnsignedShort(p);
            int fields = r.readUnsignedShort(p);
            if (fields != 0) {
                throw new KernelRefusedException(name, "a ClassFile fields_count of " + fields);
            }
            p += 2;
            int methods = r.readUnsignedShort(p);
            p += 2;
            Map<String, Integer> lengths = new LinkedHashMap<>();
            for (int m = 0; m < methods; m++) {
                String method = r.readUTF8(p + 2, chars) + r.readUTF8(p + 4, chars);
                int attributes = r.readUnsignedShort(p + 6);
                p += 8;
                for (int a = 0; a < attributes; a++) {
                    String attribute = r.readUTF8(p, chars);
                    if (!"Code".equals(attribute)) {
                        throw new KernelRefusedException(
                            name,
                            "a method_info attribute " + attribute + " on " + method);
                    }
                    // max_stack and max_locals, then code_length, the code and the exception table
                    int codeLength = r.readInt(p + 10);
                    lengths.put(method, codeLength);
                    int q = p + 14 + codeLength;
                    q += 2 + 8 * r.readUnsignedShort(q);
                    int codeAttributes = r.readUnsignedShort(q);
                    q += 2;
                    for (int c = 0; c < codeAttributes; c++) {
                        String codeAttribute = r.readUTF8(q, chars);
                        if (!CODE_ATTRIBUTES.contains(codeAttribute)) {
                            throw new KernelRefusedException(
                                name,
                                "a Code attribute " + codeAttribute + " in " + method);
                        }
                        q += 6 + r.readInt(q + 2);
                    }
                    p += 6 + r.readInt(p + 2);
                }
            }
            int attributes = r.readUnsignedShort(p);
            p += 2;
            for (int a = 0; a < attributes; a++) {
                String attribute = r.readUTF8(p, chars);
                if (!"SourceFile".equals(attribute)) {
                    throw new KernelRefusedException(name, "a ClassFile attribute " + attribute);
                }
                p += 6 + r.readInt(p + 2);
            }
            return lengths;
        } catch (KernelRefusedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new KernelRefusedException(name, "a class file the scan cannot walk: " + e);
        }
    }

    private static BitSet opcodes(int... opcodes) {
        BitSet set = new BitSet();
        for (int opcode : opcodes) {
            set.set(opcode);
        }
        return set;
    }

    private static final class ClassScan extends ClassVisitor {

        private final String name;
        private boolean init;
        private boolean run;

        ClassScan(String name) {
            super(Opcodes.ASM9);
            this.name = name;
        }

        private KernelRefusedException refuse(String reason) {
            return new KernelRefusedException(name, reason);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
            String[] interfaces) {
            if (version != Opcodes.V1_8) {
                throw refuse("class file version " + (version & 0xffff) + "." + (version >>> 16) + ", not 52.0");
            }
            if (access != (Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER)) {
                throw refuse("class access 0x" + Integer.toHexString(access) + ", not public final super");
            }
            if (!this.name.equals(name)) {
                throw refuse("the class file is " + name);
            }
            if (signature != null) {
                throw refuse("a class signature");
            }
            if (!OBJECT.equals(superName)) {
                throw refuse("superclass " + superName);
            }
            if (interfaces == null || interfaces.length != 1 || !KERNEL.equals(interfaces[0])) {
                throw refuse("interfaces " + Arrays.toString(interfaces) + ", not [" + KERNEL + "]");
            }
        }

        @Override
        public void visitSource(String source, String debug) {
            if (debug != null) {
                throw refuse("a SourceDebugExtension");
            }
        }

        @Override
        public ModuleVisitor visitModule(String name, int access, String version) {
            throw refuse("a module");
        }

        @Override
        public void visitNestHost(String nestHost) {
            throw refuse("a nest host");
        }

        @Override
        public void visitOuterClass(String owner, String name, String descriptor) {
            throw refuse("an outer class");
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            throw refuse("a class annotation");
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor,
            boolean visible) {
            throw refuse("a class type annotation");
        }

        @Override
        public void visitAttribute(Attribute attribute) {
            throw refuse("a class attribute " + attribute.type);
        }

        @Override
        public void visitNestMember(String nestMember) {
            throw refuse("a nest member");
        }

        @Override
        public void visitPermittedSubclass(String permittedSubclass) {
            throw refuse("a permitted subclass");
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            throw refuse("an inner class");
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
            throw refuse("a record component");
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            throw refuse("a field");
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
            String[] exceptions) {
            String method = name + descriptor;
            if (signature != null) {
                throw refuse("a signature on " + method);
            }
            if (exceptions != null) {
                throw refuse("exceptions on " + method);
            }
            if (!init && access == Opcodes.ACC_PUBLIC && "<init>()V".equals(method)) {
                init = true;
                return new MethodScan(method, true);
            }
            if (!run && access == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STRICT)
                && ("run" + Emitter.RUN_DESCRIPTOR).equals(method)) {
                run = true;
                return new MethodScan(method, false);
            }
            throw refuse("method " + method + " with access 0x" + Integer.toHexString(access));
        }

        @Override
        public void visitEnd() {
            if (!init || !run) {
                throw refuse("not both a public <init>()V and a public strictfp run");
            }
        }

        private final class MethodScan extends MethodVisitor {

            private final String method;
            private final boolean constructor;

            MethodScan(String method, boolean constructor) {
                super(Opcodes.ASM9);
                this.method = method;
                this.constructor = constructor;
            }

            private KernelRefusedException refuseHere(String what) {
                return refuse(what + " in " + method);
            }

            @Override
            public void visitParameter(String name, int access) {
                throw refuseHere("a MethodParameters entry");
            }

            @Override
            public AnnotationVisitor visitAnnotationDefault() {
                throw refuseHere("an annotation default");
            }

            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                throw refuseHere("an annotation");
            }

            @Override
            public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String descriptor,
                boolean visible) {
                throw refuseHere("a type annotation");
            }

            @Override
            public void visitAnnotableParameterCount(int parameterCount, boolean visible) {
                throw refuseHere("a parameter annotation count");
            }

            @Override
            public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
                throw refuseHere("a parameter annotation");
            }

            @Override
            public void visitAttribute(Attribute attribute) {
                throw refuseHere("an attribute " + attribute.type);
            }

            @Override
            public void visitCode() {}

            @Override
            public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {}

            @Override
            public void visitInsn(int opcode) {
                if (!INSNS.get(opcode)) {
                    throw refuseHere("opcode " + opcode);
                }
            }

            @Override
            public void visitIntInsn(int opcode, int operand) {
                if (opcode != Opcodes.BIPUSH && opcode != Opcodes.SIPUSH) {
                    throw refuseHere("opcode " + opcode);
                }
            }

            @Override
            public void visitVarInsn(int opcode, int varIndex) {
                if (!VARS.get(opcode)) {
                    throw refuseHere("opcode " + opcode);
                }
            }

            @Override
            public void visitTypeInsn(int opcode, String type) {
                throw refuseHere("opcode " + opcode);
            }

            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                throw refuseHere("opcode " + opcode);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                String call = owner + "." + name + descriptor;
                boolean listed = opcode == Opcodes.INVOKESTATIC && CALLS.contains(call);
                boolean superInit = constructor && opcode == Opcodes.INVOKESPECIAL && SUPER_INIT.equals(call);
                if (isInterface || !(listed || superInit)) {
                    throw refuseHere("opcode " + opcode + ", a call to " + call);
                }
            }

            @Override
            public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
                Object... bootstrapMethodArguments) {
                throw refuseHere("opcode " + Opcodes.INVOKEDYNAMIC);
            }

            @Override
            public void visitJumpInsn(int opcode, Label label) {
                if (!JUMPS.get(opcode)) {
                    throw refuseHere("opcode " + opcode);
                }
            }

            @Override
            public void visitLabel(Label label) {}

            @Override
            public void visitLdcInsn(Object value) {
                if (!(value instanceof Integer) && !(value instanceof Float)) {
                    throw refuseHere(
                        "opcode " + Opcodes.LDC
                            + ", a constant of "
                            + value.getClass()
                                .getSimpleName());
                }
            }

            @Override
            public void visitIincInsn(int varIndex, int increment) {}

            @Override
            public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
                throw refuseHere("opcode " + Opcodes.TABLESWITCH);
            }

            @Override
            public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                throw refuseHere("opcode " + Opcodes.LOOKUPSWITCH);
            }

            @Override
            public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
                throw refuseHere("opcode " + Opcodes.MULTIANEWARRAY);
            }

            @Override
            public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath, String descriptor,
                boolean visible) {
                throw refuseHere("an instruction annotation");
            }

            @Override
            public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                throw refuseHere("a try/catch block");
            }

            @Override
            public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath, String descriptor,
                boolean visible) {
                throw refuseHere("a try/catch annotation");
            }

            @Override
            public void visitLocalVariable(String name, String descriptor, String signature, Label start, Label end,
                int index) {
                throw refuseHere("a LocalVariableTable entry");
            }

            @Override
            public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath, Label[] start,
                Label[] end, int[] index, String descriptor, boolean visible) {
                throw refuseHere("a local variable annotation");
            }

            @Override
            public void visitLineNumber(int line, Label start) {}

            @Override
            public void visitMaxs(int maxStack, int maxLocals) {}

            @Override
            public void visitEnd() {}
        }
    }
}
