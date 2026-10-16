package io.github.mindbound.opengpu.gltestkit;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
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
 * lookup of {@code GL11.glGetError} ({@link #DRIVER_DIRECT}, callset.txt's D01), which reads the driver's error state
 * for R-3's drain and H63's check (20:240), because GLSM's own {@code glGetError} answers 0 under Angelica's release
 * default and the redirector maps {@code Util.checkGLError} to it from 2.2.16 (S8 review HY-1). The transform must
 * leave that literal as it is.
 */
@GlsmTest
class GlsmRedirectionTest {

    private static final String[][] PACKAGES = {
        { "io/github/mindbound/opengpu/gl/", "io/github/mindbound/opengpu/gl/GlBackend.class" },
        { "io/github/mindbound/opengpu/gltestkit/host/", "io/github/mindbound/opengpu/gltestkit/host/GlHygiene.class" },
        { "io/github/mindbound/opengpu/gltestkit/scenario/",
            "io/github/mindbound/opengpu/gltestkit/scenario/Scenarios.class" } };
    private static final String GL_OWNER = "org/lwjgl/opengl/";
    /** Built from pieces, so this class holds no such constant either. */
    private static final String LWJGL_DOTTED = "org" + ".lwjgl.";
    private static final String LWJGL_SLASHED = "org" + "/lwjgl/";
    /** D01: the class, the class literal and the method name of the one lookup GLSM must not redirect (HY-1). */
    static final String[] DRIVER_DIRECT = { "io.github.mindbound.opengpu.gl.PassState", GL_OWNER + "GL11",
        "glGetError" };

    @Test
    void everyOwnedClassLoadsThroughGlsmLoader() throws Exception {
        GlsmLoader loader = new GlsmLoader();
        for (String[] p : PACKAGES) {
            List<String> names = classes(p[0], p[1]);
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
            for (String name : classes(p[0], p[1])) {
                GlsmLoader.Transform t = loader.transform(name);
                List<String> literals = literals(t.defined);
                if (!literals.equals(literals(t.original))) {
                    problems.add(name + ": the transform changed its LWJGL class literals to " + literals);
                }
                for (String literal : literals) {
                    String problem = literalProblem(name, literal);
                    if (problem != null) {
                        problems.add(problem);
                    } else {
                        direct++;
                    }
                }
                List<String> before = calls(t.original, glsm);
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
                    Integer cap = capability(member);
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
                List<String> after = calls(t.defined, glsm);
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
        Assertions.assertEquals(1, direct, "lookups of " + Arrays.toString(DRIVER_DIRECT) + " left by the redirector");
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

    @Test
    void onlyTheDriverDirectLookupMayHoldAnLwjglClassLiteral() {
        String passState = DRIVER_DIRECT[0];
        Assertions.assertNull(literalProblem(passState, "m: " + DRIVER_DIRECT[1] + " [glGetError]"));
        Assertions.assertNotNull(literalProblem(passState, "m: " + DRIVER_DIRECT[1] + " []"));
        Assertions.assertNotNull(literalProblem(passState, "m: " + DRIVER_DIRECT[1] + " [glFlush]"));
        Assertions.assertNotNull(literalProblem(passState, "m: " + GL_OWNER + "Util [checkGLError]"));
        Assertions.assertNotNull(
            literalProblem("io.github.mindbound.opengpu.gl.GlBackend", "m: " + DRIVER_DIRECT[1] + " [glGetError]"));
    }

    /**
     * Null for D01's lookup in PassState, else why an LWJGL class literal is a problem: it can reach GL past the
     * redirector.
     */
    static String literalProblem(String className, String literal) {
        String strings = literal.substring(literal.indexOf('[') + 1, literal.lastIndexOf(']'));
        String type = literal.substring(literal.indexOf(": ") + 2, literal.indexOf(" ["));
        boolean lookup = className.equals(DRIVER_DIRECT[0]) && type.equals(DRIVER_DIRECT[1])
            && Arrays.asList(strings.split(", "))
                .contains(DRIVER_DIRECT[2]);
        return lookup ? null
            : className + ": the class literal " + literal + " is a way past the redirector; only D01's lookup may";
    }

    /** Each LWJGL class literal as "method: internal name [the method's string constants]", in order. */
    private static List<String> literals(byte[] classFile) {
        ClassNode node = new ClassNode();
        new ClassReader(classFile).accept(node, 0);
        List<String> out = new ArrayList<>();
        for (MethodNode method : node.methods) {
            List<String> strings = new ArrayList<>();
            List<String> types = new ArrayList<>();
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof String) {
                    strings.add((String) ((LdcInsnNode) insn).cst);
                } else if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof Type
                    && ((Type) ((LdcInsnNode) insn).cst).getSort() == Type.OBJECT
                    && ((Type) ((LdcInsnNode) insn).cst).getInternalName()
                        .startsWith(LWJGL_SLASHED)) {
                            types.add(((Type) ((LdcInsnNode) insn).cst).getInternalName());
                        }
            }
            for (String type : types) {
                out.add(method.name + ": " + type + " " + strings);
            }
        }
        return out;
    }

    @Test
    void noLwjglHandleOrStringConstant() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String[] p : PACKAGES) {
            for (String name : classes(p[0], p[1])) {
                ClassNode node = new ClassNode();
                new ClassReader(GlsmLoader.bytes(GlsmRedirectionTest.class.getClassLoader(), name)).accept(node, 0);
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

    /** The redirector's fold table: capability to the suffix of GLStateManager's enable/disable methods. */
    @SuppressWarnings("unchecked")
    private static Map<Integer, String> folds() throws ReflectiveOperationException {
        Field field = GLSMRedirector.class.getDeclaredField("glCapRedirects");
        field.setAccessible(true);
        return (Map<Integer, String>) field.get(null);
    }

    /** The class names under the prefix, from the directory or jar the anchor class file is in. */
    static List<String> classes(String prefix, String anchor) throws IOException, URISyntaxException {
        URL url = GlsmRedirectionTest.class.getClassLoader()
            .getResource(anchor);
        Assertions.assertNotNull(url, anchor);
        TreeSet<String> names = new TreeSet<>();
        if ("jar".equals(url.getProtocol())) {
            JarURLConnection connection = (JarURLConnection) url.openConnection();
            connection.setUseCaches(false);
            try (JarFile jar = connection.getJarFile()) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String entry = entries.nextElement()
                        .getName();
                    if (entry.startsWith(prefix) && entry.endsWith(".class")) {
                        names.add(className(entry));
                    }
                }
            }
        } else {
            File file = new File(url.toURI());
            File root = file;
            for (int i = 0; i < anchor.split("/").length; i++) {
                root = root.getParentFile();
            }
            walk(new File(root, prefix), prefix, names);
        }
        return new ArrayList<>(names);
    }

    private static void walk(File dir, String path, TreeSet<String> names) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                walk(f, path + f.getName() + "/", names);
            } else if (f.getName()
                .endsWith(".class")) {
                    names.add(className(path + f.getName()));
                }
        }
    }

    private static String className(String entry) {
        return entry.substring(0, entry.length() - ".class".length())
            .replace('/', '.');
    }

    /** The calls on LWJGL's GL classes and on GLStateManager, in order, as owner.name+descriptor. */
    private static List<String> calls(byte[] classFile, String glsm) {
        ClassNode node = new ClassNode();
        new ClassReader(classFile).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!call.owner.startsWith(GL_OWNER) && !call.owner.equals(glsm)) {
                    continue;
                }
                String text = call.owner + "." + call.name + call.desc;
                if (call.name.equals("glEnable") || call.name.equals("glDisable")) {
                    Integer constant = constantBefore(call);
                    text += " [" + constant + "]";
                }
                calls.add(text);
            }
        }
        return calls;
    }

    /** The capability of a {@code glEnable}/{@code glDisable} entry, or null for another call or no constant. */
    private static Integer capability(String member) {
        if (!member.startsWith("glEnable(I)V [") && !member.startsWith("glDisable(I)V [")) {
            return null;
        }
        String value = member.substring(member.indexOf('[') + 1, member.indexOf(']'));
        return "null".equals(value) ? null : Integer.valueOf(value);
    }

    private static Integer constantBefore(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn.getPrevious();
        if (previous instanceof IntInsnNode) {
            return ((IntInsnNode) previous).operand;
        }
        if (previous instanceof LdcInsnNode && ((LdcInsnNode) previous).cst instanceof Integer) {
            return (Integer) ((LdcInsnNode) previous).cst;
        }
        return null;
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
