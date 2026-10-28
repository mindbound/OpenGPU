package io.github.mindbound.opengpu.spike.emit;

import java.util.List;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import io.github.mindbound.opengpu.spike.ir.Cmp;
import io.github.mindbound.opengpu.spike.ir.Compare;
import io.github.mindbound.opengpu.spike.ir.Expr;
import io.github.mindbound.opengpu.spike.ir.Program;
import io.github.mindbound.opengpu.spike.ir.Stmt;
import io.github.mindbound.opengpu.spike.ir.Type;
import io.github.mindbound.opengpu.spike.ir.Validator;
import io.github.mindbound.opengpu.spike.rt.Kernel;

/**
 * IR to one {@code V1_8} class per program (22:177-180; S14 plan P4, amendments I7, D1 Q-S14-3): {@code public final
 * super}, in the generated classes' own package, implementing {@link Kernel} with a constructor and an
 * {@code ACC_STRICT} {@code run} that loops over the invocation range itself, so the one interface call per dispatch
 * leaves monomorphic code inside (22:179). It emits no {@code invokedynamic}, no allocation, no field and no call but
 * {@code Float.intBitsToFloat}, {@code Float.floatToIntBits}, {@code Math.sqrt}, {@code StrictMath.sin} and
 * {@code StrictMath.log1p}; every float store goes through {@code floatToIntBits}, so a stored NaN is always
 * 0x7fc00000; and each statement gets its kernel line under the source name {@code kernel}. The API takes and returns
 * no ASM type (P4.8): tests compile against these classes and run them from spikeJar's jar, where ASM is relocated.
 */
public final class Emitter {

    /** The generated classes' package, apart from the spike's own (S14 plan P6.1). */
    public static final String PACKAGE = "io.github.mindbound.opengpu.spike.gen";
    /** The SourceFile a fault's stack element names (22:180). */
    public static final String SOURCE = "kernel";
    /** {@link Kernel#run}'s descriptor. */
    public static final String RUN_DESCRIPTOR = "([I[I[I[I[IIIII)V";

    private static final String KERNEL = Kernel.class.getName()
        .replace('.', '/');
    private static final String FLOAT = "java/lang/Float";

    // run's slots: this 0, the buffers 1-4, the uniforms' words 5, the range 6-9; then the invocation's global id, the
    // uniforms read once at entry (22:179's hoisting) and the program's locals, one slot each
    private static final int BUFFER_0 = 1;
    private static final int UNIFORM_WORDS = 5;
    private static final int X0 = 6;
    private static final int X1 = 7;
    private static final int Y0 = 8;
    private static final int Y1 = 9;
    private static final int GX = 10;
    private static final int GY = 11;
    private static final int UNIFORM_0 = 12;

    private Emitter() {}

    /** The binary name of the class {@link #emit} makes for {@code program}. */
    public static String className(Program program) {
        return PACKAGE + "." + program.name;
    }

    /** Validates the program and returns its class file, which the loader's scan checks before it defines it. */
    public static byte[] emit(Program program) {
        Validator.check(program);
        ClassWriter cw = new NonLoadingWriter();
        cw.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
            className(program).replace('.', '/'),
            null,
            "java/lang/Object",
            new String[] { KERNEL });
        cw.visitSource(SOURCE, null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        new Run(program, cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STRICT, "run", RUN_DESCRIPTOR, null, null))
            .emit();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** One program's {@code run}. */
    private static final class Run {

        private final Program program;
        private final MethodVisitor mv;
        private final int local0;
        /** The next invocation: where the body's end and every {@code Return} go. */
        private final Label next = new Label();

        Run(Program program, MethodVisitor mv) {
            this.program = program;
            this.mv = mv;
            this.local0 = UNIFORM_0 + program.uniforms.size();
        }

        void emit() {
            mv.visitCode();
            // Zeroed at entry too, so every local has one type in every frame
            zeroLocals();
            for (int u = 0; u < program.uniforms.size(); u++) {
                mv.visitVarInsn(Opcodes.ALOAD, UNIFORM_WORDS);
                pushInt(u);
                mv.visitInsn(Opcodes.IALOAD);
                if (program.uniforms.get(u) == Type.F32) {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, FLOAT, "intBitsToFloat", "(I)F", false);
                    mv.visitVarInsn(Opcodes.FSTORE, UNIFORM_0 + u);
                } else {
                    mv.visitVarInsn(Opcodes.ISTORE, UNIFORM_0 + u);
                }
            }
            // y outer and x inner over [x0, x1) x [y0, y1); a 1D program has the x loop alone
            boolean rows = program.dimensions == 2;
            Label done = new Label();
            Label yTest = new Label();
            Label yNext = new Label();
            if (rows) {
                mv.visitVarInsn(Opcodes.ILOAD, Y0);
                mv.visitVarInsn(Opcodes.ISTORE, GY);
                mv.visitLabel(yTest);
                mv.visitVarInsn(Opcodes.ILOAD, GY);
                mv.visitVarInsn(Opcodes.ILOAD, Y1);
                mv.visitJumpInsn(Opcodes.IF_ICMPGE, done);
            }
            Label xTest = new Label();
            mv.visitVarInsn(Opcodes.ILOAD, X0);
            mv.visitVarInsn(Opcodes.ISTORE, GX);
            mv.visitLabel(xTest);
            mv.visitVarInsn(Opcodes.ILOAD, GX);
            mv.visitVarInsn(Opcodes.ILOAD, X1);
            mv.visitJumpInsn(Opcodes.IF_ICMPGE, rows ? yNext : done);
            // Each invocation starts from zeroed locals, the language's zero initialization (22:44; S14 amendments I7)
            zeroLocals();
            block(program.body);
            mv.visitLabel(next);
            mv.visitIincInsn(GX, 1);
            mv.visitJumpInsn(Opcodes.GOTO, xTest);
            if (rows) {
                mv.visitLabel(yNext);
                mv.visitIincInsn(GY, 1);
                mv.visitJumpInsn(Opcodes.GOTO, yTest);
            }
            mv.visitLabel(done);
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        private void zeroLocals() {
            for (int slot = 0; slot < program.locals.size(); slot++) {
                if (program.locals.get(slot) == Type.F32) {
                    mv.visitInsn(Opcodes.FCONST_0);
                    mv.visitVarInsn(Opcodes.FSTORE, local0 + slot);
                } else {
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitVarInsn(Opcodes.ISTORE, local0 + slot);
                }
            }
        }

        /**
         * Emits the block's statements up to the first that cannot complete normally and says whether the block can.
         * The rest would be dead code, which COMPUTE_FRAMES replaces with {@code NOP}s and an {@code ATHROW} that the
         * scan refuses, so it is never emitted.
         */
        private boolean block(List<Stmt> block) {
            for (Stmt s : block) {
                if (!statement(s)) {
                    return false;
                }
            }
            return true;
        }

        private boolean statement(Stmt s) {
            Label start = new Label();
            mv.visitLabel(start);
            mv.visitLineNumber(s.line, start);
            if (s instanceof Stmt.Assign) {
                Stmt.Assign a = (Stmt.Assign) s;
                expr(a.value);
                mv.visitVarInsn(a.value.type == Type.F32 ? Opcodes.FSTORE : Opcodes.ISTORE, local0 + a.slot);
                return true;
            }
            if (s instanceof Stmt.Store) {
                Stmt.Store st = (Stmt.Store) s;
                mv.visitVarInsn(Opcodes.ALOAD, BUFFER_0 + st.binding);
                expr(st.index);
                expr(st.value);
                if (st.value.type == Type.F32) {
                    // The NaN-canonical store (S14 decisions D1, Q-S14-3 (a))
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, FLOAT, "floatToIntBits", "(F)I", false);
                }
                mv.visitInsn(Opcodes.IASTORE);
                return true;
            }
            if (s instanceof Stmt.If) {
                Stmt.If i = (Stmt.If) s;
                Label orElse = new Label();
                Label end = new Label();
                if (i.orElse.isEmpty()) {
                    jumpUnless(i.condition, end);
                    block(i.then);
                    mv.visitLabel(end);
                    return true;
                }
                jumpUnless(i.condition, orElse);
                boolean thenCompletes = block(i.then);
                if (thenCompletes) {
                    mv.visitJumpInsn(Opcodes.GOTO, end);
                }
                mv.visitLabel(orElse);
                boolean elseCompletes = block(i.orElse);
                mv.visitLabel(end);
                return thenCompletes || elseCompletes;
            }
            if (s instanceof Stmt.For) {
                Stmt.For f = (Stmt.For) s;
                int slot = local0 + f.slot;
                Label test = new Label();
                Label end = new Label();
                pushInt(f.from);
                mv.visitVarInsn(Opcodes.ISTORE, slot);
                mv.visitLabel(test);
                mv.visitVarInsn(Opcodes.ILOAD, slot);
                pushInt(f.to);
                mv.visitJumpInsn(Opcodes.IF_ICMPGE, end);
                // A body that never completes runs at most once, and has no back edge to emit
                if (block(f.body)) {
                    mv.visitIincInsn(slot, 1);
                    mv.visitJumpInsn(Opcodes.GOTO, test);
                }
                mv.visitLabel(end);
                return true;
            }
            if (!(s instanceof Stmt.Return)) {
                throw new IllegalStateException("emitter bug: an unknown statement " + s);
            }
            mv.visitJumpInsn(Opcodes.GOTO, next);
            return false;
        }

        /**
         * Jumps to {@code target} when the condition is false. A float comparison uses {@code FCMPG} for {@code <} and
         * {@code <=} and {@code FCMPL} for the rest, so that a NaN operand makes every ordered comparison and
         * {@code ==} false and {@code !=} true, as javac compiles them.
         */
        private void jumpUnless(Compare c, Label target) {
            expr(c.left);
            expr(c.right);
            if (c.left.type == Type.I32) {
                mv.visitJumpInsn(intJump(c.op), target);
            } else {
                mv.visitInsn(c.op == Cmp.LT || c.op == Cmp.LE ? Opcodes.FCMPG : Opcodes.FCMPL);
                mv.visitJumpInsn(floatJump(c.op), target);
            }
        }

        private static int intJump(Cmp op) {
            switch (op) {
                case LT:
                    return Opcodes.IF_ICMPGE;
                case LE:
                    return Opcodes.IF_ICMPGT;
                case GT:
                    return Opcodes.IF_ICMPLE;
                case GE:
                    return Opcodes.IF_ICMPLT;
                case EQ:
                    return Opcodes.IF_ICMPNE;
                default:
                    return Opcodes.IF_ICMPEQ;
            }
        }

        private static int floatJump(Cmp op) {
            switch (op) {
                case LT:
                    return Opcodes.IFGE;
                case LE:
                    return Opcodes.IFGT;
                case GT:
                    return Opcodes.IFLE;
                case GE:
                    return Opcodes.IFLT;
                case EQ:
                    return Opcodes.IFNE;
                default:
                    return Opcodes.IFEQ;
            }
        }

        private void expr(Expr e) {
            if (e instanceof Expr.Const) {
                Expr.Const c = (Expr.Const) e;
                if (c.type == Type.I32) {
                    pushInt(c.bits);
                } else {
                    pushFloat(c.bits);
                }
            } else if (e instanceof Expr.Local) {
                Expr.Local l = (Expr.Local) e;
                mv.visitVarInsn(l.type == Type.F32 ? Opcodes.FLOAD : Opcodes.ILOAD, local0 + l.slot);
            } else if (e instanceof Expr.Uniform) {
                Expr.Uniform u = (Expr.Uniform) e;
                mv.visitVarInsn(u.type == Type.F32 ? Opcodes.FLOAD : Opcodes.ILOAD, UNIFORM_0 + u.index);
            } else if (e instanceof Expr.GlobalId) {
                mv.visitVarInsn(Opcodes.ILOAD, ((Expr.GlobalId) e).axis == 0 ? GX : GY);
            } else if (e instanceof Expr.Load) {
                Expr.Load l = (Expr.Load) e;
                mv.visitVarInsn(Opcodes.ALOAD, BUFFER_0 + l.binding);
                expr(l.index);
                mv.visitInsn(Opcodes.IALOAD);
                if (l.type == Type.F32) {
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, FLOAT, "intBitsToFloat", "(I)F", false);
                }
            } else if (e instanceof Expr.Unary) {
                Expr.Unary u = (Expr.Unary) e;
                expr(u.operand);
                unary(u);
            } else {
                Expr.Binary b = (Expr.Binary) e;
                expr(b.left);
                expr(b.right);
                binary(b);
            }
        }

        private void unary(Expr.Unary u) {
            switch (u.op) {
                case INEG:
                    mv.visitInsn(Opcodes.INEG);
                    break;
                case INOT:
                    mv.visitInsn(Opcodes.ICONST_M1);
                    mv.visitInsn(Opcodes.IXOR);
                    break;
                case FNEG:
                    mv.visitInsn(Opcodes.FNEG);
                    break;
                case FSQRT:
                    viaDouble("java/lang/Math", "sqrt");
                    break;
                case FSIN:
                    viaDouble("java/lang/StrictMath", "sin");
                    break;
                case FLOG1P:
                    viaDouble("java/lang/StrictMath", "log1p");
                    break;
                case I2F:
                    mv.visitInsn(Opcodes.I2F);
                    break;
                case F2I:
                    mv.visitInsn(Opcodes.F2I);
                    break;
                default:
                    throw new IllegalStateException("emitter bug: " + u.op + " is not unary");
            }
        }

        /** {@code (float)owner.name((double)x)} (22:142). */
        private void viaDouble(String owner, String name) {
            mv.visitInsn(Opcodes.F2D);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, "(D)D", false);
            mv.visitInsn(Opcodes.D2F);
        }

        private void binary(Expr.Binary b) {
            switch (b.op) {
                case IDIV: {
                    // x / 0 = x (22:134): with the divisor 0, drop it and keep x; INT_MIN / -1 wraps in IDIV itself
                    Label divide = new Label();
                    Label end = new Label();
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitJumpInsn(Opcodes.IFNE, divide);
                    mv.visitInsn(Opcodes.POP);
                    mv.visitJumpInsn(Opcodes.GOTO, end);
                    mv.visitLabel(divide);
                    mv.visitInsn(Opcodes.IDIV);
                    mv.visitLabel(end);
                    break;
                }
                case IREM: {
                    // x % 0 = 0 (22:134); INT_MIN % -1 is 0 in IREM itself
                    Label divide = new Label();
                    Label end = new Label();
                    mv.visitInsn(Opcodes.DUP);
                    mv.visitJumpInsn(Opcodes.IFNE, divide);
                    mv.visitInsn(Opcodes.POP2);
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitJumpInsn(Opcodes.GOTO, end);
                    mv.visitLabel(divide);
                    mv.visitInsn(Opcodes.IREM);
                    mv.visitLabel(end);
                    break;
                }
                default:
                    mv.visitInsn(binaryOpcode(b));
            }
        }

        private static int binaryOpcode(Expr.Binary b) {
            switch (b.op) {
                case IADD:
                    return Opcodes.IADD;
                case ISUB:
                    return Opcodes.ISUB;
                case IMUL:
                    return Opcodes.IMUL;
                case IAND:
                    return Opcodes.IAND;
                case IOR:
                    return Opcodes.IOR;
                case IXOR:
                    return Opcodes.IXOR;
                // The JVM takes shift counts mod 32, as 22:136 does
                case ISHL:
                    return Opcodes.ISHL;
                case ISHR:
                    return Opcodes.ISHR;
                case IUSHR:
                    return Opcodes.IUSHR;
                case FADD:
                    return Opcodes.FADD;
                case FSUB:
                    return Opcodes.FSUB;
                case FMUL:
                    return Opcodes.FMUL;
                case FDIV:
                    return Opcodes.FDIV;
                default:
                    throw new IllegalStateException("emitter bug: " + b.op + " is not binary");
            }
        }

        private void pushInt(int value) {
            if (value >= -1 && value <= 5) {
                mv.visitInsn(Opcodes.ICONST_0 + value);
            } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                mv.visitIntInsn(Opcodes.BIPUSH, value);
            } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                mv.visitIntInsn(Opcodes.SIPUSH, value);
            } else {
                mv.visitLdcInsn(value);
            }
        }

        private void pushFloat(int bits) {
            if (bits == 0) {
                mv.visitInsn(Opcodes.FCONST_0);
            } else if (bits == Float.floatToRawIntBits(1f)) {
                mv.visitInsn(Opcodes.FCONST_1);
            } else if (bits == Float.floatToRawIntBits(2f)) {
                mv.visitInsn(Opcodes.FCONST_2);
            } else {
                mv.visitLdcInsn(Float.intBitsToFloat(bits));
            }
        }
    }
}
