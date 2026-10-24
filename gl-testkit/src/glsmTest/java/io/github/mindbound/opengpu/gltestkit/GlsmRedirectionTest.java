package io.github.mindbound.opengpu.gltestkit;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.redirect.GLSMRedirector;

/**
 * S8 plan §4.5 (H30, H44; risk 9): every class of the three packages GlsmLoader owns ({@code :gl} with
 * {@code gl.host}, {@code gltestkit.host}, {@code gltestkit.scenario}) loads through it, and GLSMRedirector, run as in
 * game, leaves no call on LWJGL's GL classes in any of them: each becomes the {@code GLStateManager} method of the same
 * name and descriptor, which must exist (the spike's check, widened), except that {@code glEnable}/{@code glDisable}
 * of a capability in the redirector's own fold table become its no-argument method (25:239; F01-F10). Every
 * {@code glEnable}/{@code glDisable} takes its capability as a constant right before the call (plan §0). Classes that
 * call no GL are defined by the loader but left as they are. No class holds a method handle owned by LWJGL (R-5) or a
 * string constant naming LWJGL's packages (plan §0; the root jarTest's check (3)). And no class holds a class literal
 * of LWJGL's, the way past the redirector, but for the one deliberate call GLSM must not see: PassState's run-time
 * lookup of {@code GL11.glGetError} ({@link GlBytecode#DRIVER_DIRECT}, callset.txt's D01), which reads the driver's
 * error state for R-3's drain and H63's check (20:240), because GLSM's own {@code glGetError} answers 0 under
 * Angelica's release default and the redirector maps {@code Util.checkGLError} to it from 2.2.16 (S8 review HY-1). The
 * transform must leave that literal as it is. This is the WARN legs' check, of what the render-core legs run, test
 * fixtures included; 25 §4's gating one is GlsmLinkageTest (glsmLinkageTest, S9), over :gl's shipped classes under
 * unmappedGL=FAIL on four jars, whose link check overlaps this one for :gl on the floor's and the pack's (S9 plan P12).
 */
@GlsmTest
class GlsmRedirectionTest {

    private static final String[][] PACKAGES = {
        { "io/github/mindbound/opengpu/gl/", "io/github/mindbound/opengpu/gl/GlBackend.class" },
        { "io/github/mindbound/opengpu/gltestkit/host/", "io/github/mindbound/opengpu/gltestkit/host/GlHygiene.class" },
        { "io/github/mindbound/opengpu/gltestkit/scenario/",
            "io/github/mindbound/opengpu/gltestkit/scenario/Scenarios.class" } };
    private static final String GL_OWNER = GlBytecode.GL_OWNER;
    /** Built from pieces, so this class holds no such constant either. */
    private static final String LWJGL_DOTTED = "org" + ".lwjgl.";
    private static final String LWJGL_SLASHED = "org" + "/lwjgl/";

    @Test
    void everyOwnedClassLoadsThroughGlsmLoader() throws Exception {
        GlsmLoader loader = new GlsmLoader();
        for (String[] p : PACKAGES) {
            List<String> names = GlBytecode.classes(p[0], p[1]);
            Assertions.assertFalse(names.isEmpty(), "no class found under " + p[0]);
            for (String name : names) {
                Assertions.assertTrue(GlsmLoader.owns(name), name + " is not owned");
                Class<?> type = Class.forName(name, false, loader);
                Assertions.assertSame(loader, type.getClassLoader(), name);
            }
        }
    }

    @Test
    void noGlCallIsLeftAndEveryTargetLinks() throws Exception {
        GlsmLoader loader = new GlsmLoader();
        String glsm = GLSMRedirector.getTargetClassName();
        Map<Integer, String> folds = folds();
        List<String> problems = new ArrayList<>();
        int glClasses = 0;
        int plainClasses = 0;
        int folded = 0;
        int direct = 0;
        for (String[] p : PACKAGES) {
            for (String name : GlBytecode.classes(p[0], p[1])) {
                GlsmLoader.Transform t = loader.transform(name);
                List<GlBytecode.Literal> literals = GlBytecode.literals(t.defined);
                if (!literals.equals(GlBytecode.literals(t.original))) {
                    problems.add(name + ": the transform changed its LWJGL class literals to " + literals);
                }
                for (GlBytecode.Literal literal : literals) {
                    String problem = GlBytecode.literalProblem(name, literal);
                    if (problem != null) {
                        problems.add(problem);
                    } else {
                        direct++;
                    }
                }
                List<String> before = GlBytecode.calls(t.original, glsm);
                if (before.isEmpty()) {
                    plainClasses++;
                    if (t.changed || !Arrays.equals(t.original, t.defined)) {
                        problems.add(name + ": calls no GL, but the redirector changed it");
                    }
                    continue;
                }
                glClasses++;
                if (t.excluded || !t.filtered || !t.changed) {
                    problems.add(
                        name + ": calls GL, but excluded "
                            + t.excluded
                            + ", filtered "
                            + t.filtered
                            + ", changed "
                            + t.changed);
                    continue;
                }
                List<String> expected = new ArrayList<>();
                for (String call : before) {
                    String member = call.substring(call.indexOf('.') + 1);
                    Integer cap = GlBytecode.capability(member);
                    if (cap != null && folds.containsKey(cap)) {
                        expected.add(
                            glsm + "."
                                + (member.startsWith("glEnable") ? "enable" : "disable")
                                + folds.get(cap)
                                + "()V");
                        folded++;
                    } else {
                        expected.add(glsm + "." + member);
                    }
                    if (member.startsWith("glEnable(") || member.startsWith("glDisable(")) {
                        if (cap == null) {
                            problems.add(name + ": " + member + " without a constant capability (25:239)");
                        }
                    }
                }
                List<String> after = GlBytecode.calls(t.defined, glsm);
                if (!expected.equals(after)) {
                    problems.add(name + ": expected " + expected + "\n  got " + after);
                }
                for (String call : after) {
                    if (call.startsWith(GL_OWNER)) {
                        problems.add(name + ": " + call + " is left on LWJGL");
                    } else {
                        String link = links(call, glsm);
                        if (link != null) {
                            problems.add(name + ": " + link);
                        }
                    }
                }
            }
        }
        Assertions.assertEquals(new ArrayList<String>(), problems, problems.size() + " problem(s)");
        Assertions.assertTrue(glClasses >= 10 && plainClasses >= 10, glClasses + " GL classes, " + plainClasses);
        Assertions.assertTrue(folded > 0, "no glEnable or glDisable was folded");
        Assertions.assertEquals(
            1,
            direct,
            "lookups of " + Arrays.toString(GlBytecode.DRIVER_DIRECT) + " left by the redirector");
        System.out.println(
            "[opengpu-glsm] redirection: " + glClasses
                + " GL-calling classes redirected, "
                + plainClasses
                + " left unchanged, "
                + folded
                + " enable/disable calls folded, "
                + direct
                + " driver-direct lookup left as it is");
    }

    /** D01's form, and each way out of it (S9 review F1, TS1). */
    @Test
    void onlyTheDriverDirectLookupMayHoldAnLwjglClassLiteral() {
        String passState = GlBytecode.DRIVER_DIRECT[0];
        String gl11 = GlBytecode.DRIVER_DIRECT[1];
        String d01 = GlBytecode.DRIVER_DIRECT[2];
        List<String> one = Collections.singletonList(GlBytecode.FIND_STATIC);
        List<String> d01Name = Collections.singletonList(d01);
        Assertions.assertNull(GlBytecode.literalProblem(passState, literal(gl11, d01, 1, d01Name, one)));
        // No name right after the literal, another name, another owner, another class
        Assertions.assertNotNull(
            GlBytecode.literalProblem(passState, literal(gl11, null, 1, Collections.<String>emptyList(), one)));
        Assertions.assertNotNull(
            GlBytecode
                .literalProblem(passState, literal(gl11, "glFlush", 1, Collections.singletonList("glFlush"), one)));
        Assertions.assertNotNull(
            GlBytecode.literalProblem(
                passState,
                literal(GL_OWNER + "Util", "checkGLError", 1, Collections.<String>emptyList(), one)));
        Assertions.assertNotNull(
            GlBytecode.literalProblem("io.github.mindbound.opengpu.gl.GlBackend", literal(gl11, d01, 1, d01Name, one)));
        // A second GL name, a second lookup, a lookup through a helper, a second literal in D01's method
        Assertions.assertNotNull(
            GlBytecode.literalProblem(passState, literal(gl11, d01, 1, Arrays.asList(d01, "glFinish"), one)));
        Assertions.assertNotNull(
            GlBytecode.literalProblem(
                passState,
                literal(gl11, d01, 1, d01Name, Arrays.asList(GlBytecode.FIND_STATIC, GlBytecode.FIND_STATIC))));
        Assertions.assertNotNull(
            GlBytecode.literalProblem(passState, literal(gl11, d01, 1, d01Name, Collections.<String>emptyList())));
        Assertions.assertNotNull(GlBytecode.literalProblem(passState, literal(gl11, d01, 2, d01Name, one)));
    }

    private static GlBytecode.Literal literal(String type, String name, int literals, List<String> glNames,
        List<String> reflection) {
        return new GlBytecode.Literal("m", type, name, literals, glNames, reflection);
    }

    @Test
    void noLwjglHandleOrStringConstant() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String[] p : PACKAGES) {
            for (String name : GlBytecode.classes(p[0], p[1])) {
                ClassNode node = new ClassNode();
                new ClassReader(GlBytecode.bytes(GlsmRedirectionTest.class.getClassLoader(), name)).accept(node, 0);
                for (FieldNode field : node.fields) {
                    if (field.value instanceof String && (((String) field.value).contains(LWJGL_DOTTED)
                        || ((String) field.value).contains(LWJGL_SLASHED))) {
                        problems.add(name + "." + field.name + ": the string constant \"" + field.value + "\"");
                    }
                }
                for (MethodNode method : node.methods) {
                    for (AbstractInsnNode insn : method.instructions.toArray()) {
                        if (insn instanceof InvokeDynamicInsnNode) {
                            InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                            for (Object arg : indy.bsmArgs) {
                                if (arg instanceof Handle && ((Handle) arg).getOwner()
                                    .startsWith(LWJGL_SLASHED)) {
                                    problems.add(name + "." + method.name + ": a method handle on " + arg + " (R-5)");
                                }
                            }
                        }
                        if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof String) {
                            String s = (String) ((LdcInsnNode) insn).cst;
                            if (s.contains(LWJGL_DOTTED) || s.contains(LWJGL_SLASHED)) {
                                problems.add(name + "." + method.name + ": the string constant \"" + s + "\"");
                            }
                        }
                    }
                }
            }
        }
        Assertions.assertEquals(new ArrayList<String>(), problems, problems.size() + " problem(s)");
    }

    /**
     * GlBytecode's reader against this JVM (S9 plan P12): the public static methods it reads from the leg's own jar,
     * the one GLStateManager came from, are those the JVM gives GLStateManager. getMethods resolves the public methods'
     * types only, as links() relies on; getDeclaredMethods would also resolve the private ones', which this JVM may
     * lack (S9 critic A8).
     */
    @Test
    void theBytecodeMethodListMatchesTheJvm() throws Exception {
        File jar = new File(
            GLStateManager.class.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Set<String> jvm = new TreeSet<>();
        for (Method method : GLStateManager.class.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getDeclaringClass() == GLStateManager.class) {
                jvm.add(method.getName() + Type.getMethodDescriptor(method));
            }
        }
        Set<String> bytecode = GlBytecode.publicStatics(jar, GLSMRedirector.getTargetClassName());
        List<String> differences = new ArrayList<>();
        for (String method : jvm) {
            if (!bytecode.contains(method)) {
                differences.add("only the JVM has " + method);
            }
        }
        for (String method : bytecode) {
            if (!jvm.contains(method)) {
                differences.add("only the bytecode has " + method);
            }
        }
        Assertions.assertFalse(jvm.isEmpty(), "GLStateManager has no public static method");
        Assertions.assertEquals(
            new ArrayList<String>(),
            differences,
            jar.getName() + ": " + differences.size() + " difference(s)");
        System.out.println(
            "[opengpu-glsm] redirection: " + jvm.size()
                + " public static GLStateManager methods in "
                + jar.getName()
                + ", the same from its bytecode and from the JVM");
    }

    /** The redirector's fold table: capability to the suffix of GLStateManager's enable/disable methods. */
    @SuppressWarnings("unchecked")
    private static Map<Integer, String> folds() throws ReflectiveOperationException {
        Field field = GLSMRedirector.class.getDeclaredField("glCapRedirects");
        field.setAccessible(true);
        return (Map<Integer, String>) field.get(null);
    }

    /** Null when the GLStateManager call links: a public static method of that name and descriptor (25 §4 step 3). */
    private static String links(String call, String glsm) throws ClassNotFoundException {
        String member = call.substring(glsm.length() + 1)
            .replaceAll(" \\[.*]$", "");
        String name = member.substring(0, member.indexOf('('));
        String descriptor = member.substring(member.indexOf('('));
        Type[] arguments = Type.getArgumentTypes(descriptor);
        Class<?>[] parameters = new Class<?>[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            parameters[i] = classOf(arguments[i]);
        }
        Method method;
        try {
            method = GLStateManager.class.getMethod(name, parameters);
        } catch (NoSuchMethodException e) {
            return call + " has no GLStateManager method to link to";
        }
        if (!Modifier.isStatic(method.getModifiers())
            || method.getReturnType() != classOf(Type.getReturnType(descriptor))) {
            return call + " links to " + method;
        }
        return null;
    }

    private static Class<?> classOf(Type type) throws ClassNotFoundException {
        switch (type.getSort()) {
            case Type.VOID:
                return void.class;
            case Type.BOOLEAN:
                return boolean.class;
            case Type.BYTE:
                return byte.class;
            case Type.CHAR:
                return char.class;
            case Type.SHORT:
                return short.class;
            case Type.INT:
                return int.class;
            case Type.FLOAT:
                return float.class;
            case Type.LONG:
                return long.class;
            case Type.DOUBLE:
                return double.class;
            case Type.ARRAY:
                return Class.forName(
                    type.getDescriptor()
                        .replace('/', '.'),
                    false,
                    GlsmRedirectionTest.class.getClassLoader());
            default:
                return Class.forName(type.getClassName(), false, GlsmRedirectionTest.class.getClassLoader());
        }
    }
}
