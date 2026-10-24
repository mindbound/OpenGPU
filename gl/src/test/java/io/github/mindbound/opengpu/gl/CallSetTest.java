package io.github.mindbound.opengpu.gl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * 25 §4's linkage test, steps 1-2, over :gl's own classes (S8 plan §3.13; H44; 25:249-259), from their bytecode with
 * ASM: every call into LWJGL is a plain {@code invokestatic} on {@code GL11}-{@code GL33} that matches a row of
 * {@code callset.txt} (25 §4.1, with C99 {@code glPolygonMode}, H41, and C100 {@code glFinish}, S8's fix loop after
 * U6), none matches the deny-list (25 §4.2) or a driver-direct row, and each
 * {@code glEnable}/{@code glDisable} takes its capability as an int constant right before the call, where
 * GLSMRedirector folds it (25:239), and matches its folded or unfolded row. Also, in every class: no
 * {@code invokedynamic} whose bootstrap names LWJGL (R-5); no lambda or method reference in a class that calls GL
 * (plan §0); no GL call in a constructor or static initializer (plan §0: the GLSM legs instantiate off the GL thread);
 * no string constant naming {@code org.lwjgl.} or {@code org/lwjgl/} (24:90; the root jarTest's check); no
 * {@code glVertexAttrib*} constant setter (V1), no {@code glClearBuffer*} (A09), no {@code ShortBuffer} overload (A11)
 * and no {@code IntBuffer} one except R01's scissor-box query; and no class literal of LWJGL's, the way past
 * GLSMRedirector, but a driver-direct row's run-time lookup in D01's form: its class literal right before its method's
 * name, in a method with no other LWJGL literal or GL name and one findStatic as its only reflective call (D01
 * {@code GL11.glGetError}, PassState's, S8 review HY-1; S9 review F1). Three test-source classes are reported, so the
 * check is not blind. Steps 3-4, the per-jar linkage, are gl-testkit's GlsmLinkageTest (glsmLinkageTest, S9).
 */
class CallSetTest {

    private static final String OPENGL = "org/lwjgl/opengl/";
    private static final Set<String> OWNERS = new HashSet<>(
        Arrays.asList("GL11", "GL12", "GL13", "GL14", "GL15", "GL20", "GL21", "GL30", "GL31", "GL32", "GL33"));
    /** The capabilities GLSMRedirector's glCapRedirects folds at 2.2.8 (javap of the floor's dev jar). */
    private static final Set<Integer> FOLDED = new HashSet<>(
        Arrays.asList(3008, 3042, 2929, 2884, 2896, 3553, 2912, 32826, 3089));
    private static final String R01 = OPENGL + "GL11.glGetInteger(ILjava/nio/IntBuffer;)V";
    private static final String D01 = OPENGL + "GL11.glGetError()I";
    /** D01's one reflective call, as owner.name (PassState.lookUpDriverGetError). */
    private static final String FIND_STATIC = "java/lang/invoke/MethodHandles$Lookup.findStatic";
    /** A string constant that names a GL entry point: glGetError, or LWJGL's native form nglGetError. */
    private static final Pattern GL_NAME = Pattern.compile("n?gl[A-Z][A-Za-z0-9_]*");

    private static final CallSet CALL_SET = CallSet.load();

    @Test
    void theCallSetIsWellFormedAndEveryRowIsAnLwjglMethod() throws Exception {
        // Plan §3.13's rows, C15 for the rasterizer-discard restore (S8 amendments B), C99 (H41) and C100, the fetch's
        // glFinish (S8's fix loop after U6), C02 for GL_VENDOR (Q-S8-3) and no C03 (HY-1); D01, the driver's error
        // state (HY-1); 25 §4.2's 13
        Assertions.assertEquals(66, CALL_SET.allowed.size());
        Assertions.assertEquals(5, CALL_SET.folded.size());
        Assertions.assertEquals(13, CALL_SET.denied.size());
        Assertions.assertEquals(Collections.singletonMap(D01, "D01"), CALL_SET.direct);
        Assertions.assertFalse(
            CALL_SET.allowed.containsKey(D01),
            "a GL11.glGetError call is GLSM's, which answers 0 in a release client (HY-1): D01 looks it up instead");
        for (Map.Entry<String, String> row : CALL_SET.allRows.entrySet()) {
            String call = row.getValue();
            String owner = call.substring(0, call.indexOf('.'))
                .replace('/', '.');
            String name = call.substring(call.indexOf('.') + 1, call.indexOf('('));
            String descriptor = call.substring(call.indexOf('('));
            Class<?> type = Class.forName(owner, false, CallSetTest.class.getClassLoader());
            Assertions.assertTrue(declares(type, name, descriptor), row.getKey() + " " + call + " is no LWJGL method");
        }
        for (String allowed : CALL_SET.allowed.keySet()) {
            Assertions.assertFalse(CALL_SET.denied.containsKey(allowed), allowed + " is allowed and denied");
        }
        for (String direct : CALL_SET.direct.keySet()) {
            Assertions.assertFalse(CALL_SET.allowed.containsKey(direct), direct + " is allowed as a call and direct");
        }
    }

    @Test
    void glsMainClassesStayInTheCallSet() throws IOException {
        List<ClassNode> classes = mainClasses();
        Set<String> names = new TreeSet<>();
        for (ClassNode node : classes) {
            names.add(node.name.substring(node.name.lastIndexOf('/') + 1));
        }
        for (String expected : new String[] { "PassState", "PixelStore", "GlBackend", "DeviceGl", "ReadbackRing",
            "Program", "GlTexture", "GpuTimer", "QuadBatch", "OpQuads", "GlLedger" }) {
            Assertions.assertTrue(names.contains(expected), expected + " is not among :gl's classes: " + names);
        }
        List<String> problems = new ArrayList<>();
        Set<String> used = new TreeSet<>();
        int sites = 0;
        for (ClassNode node : classes) {
            Report report = check(node);
            problems.addAll(report.problems);
            used.addAll(report.ids);
            sites += report.glCalls;
        }
        Assertions.assertEquals(Collections.emptyList(), problems, problems.size() + " problem(s)");
        Assertions.assertTrue(sites > 150, "only " + sites + " GL call sites found");
        // The bracket's calls, each by its row, its driver-direct error read (D01), the fetch's glFinish (C100) and
        // GL_VENDOR (C02)
        for (String id : new String[] { "D01", "C02", "C04", "C05", "C12", "C14", "C15", "C16", "C91", "C92", "C96",
            "C97", "F01", "F03", "F04", "F06", "F08", "R01", "R02", "C99", "C100" }) {
            Assertions.assertTrue(used.contains(id), id + " is not called: " + used);
        }
    }

    @Test
    void theGlCallingClassesAreTheExpectedOnes() throws IOException {
        Set<String> calling = new TreeSet<>();
        for (ClassNode node : mainClasses()) {
            if (check(node).glCalls > 0) {
                calling.add(node.name.substring(node.name.lastIndexOf('/') + 1));
            }
        }
        // Everything else, gl.host included, is CPU-only (H29: GlHostCore makes no GL call)
        Assertions.assertEquals(
            new TreeSet<>(
                Arrays.asList(
                    "DeviceGl",
                    "GlBackend",
                    "GlTexture",
                    "GpuTimer",
                    "PassState",
                    "PixelStore",
                    "Program",
                    "ReadbackRing")),
            calling);
    }

    @Test
    void theDenyListedCallIsReported() throws IOException {
        Report report = check(node(ControlReadPixels.class));
        Assertions.assertEquals(2, report.problems.size(), report.problems.toString());
        Assertions.assertTrue(
            report.problems.get(0)
                .contains("A01"),
            report.problems.toString());
        Assertions.assertTrue(
            report.problems.get(1)
                .contains("not in the call set"),
            report.problems.toString());
    }

    @Test
    void theMethodReferenceIsReported() throws IOException {
        Report report = check(node(ControlMethodReference.class));
        Assertions.assertEquals(1, report.problems.size(), report.problems.toString());
        Assertions.assertTrue(
            report.problems.get(0)
                .contains("invokedynamic"),
            report.problems.toString());
        Assertions.assertTrue(
            report.problems.get(0)
                .contains("GL11.glFlush"),
            report.problems.toString());
    }

    @Test
    void theLwjglStringIsReported() throws IOException {
        Report report = check(node(ControlLwjglString.class));
        Assertions.assertEquals(
            Collections.singletonList(
                "io/github/mindbound/opengpu/gl/ControlLwjglString: the string \"org.lwjgl.opengl.GL11\" names LWJGL"),
            report.problems);
    }

    @Test
    void theRulesCatchWhatTheyDescribe() {
        // Synthetic method bodies, one rule each: a variable capability, an unfolded GL_BLEND enable, a constant
        // attribute, a ShortBuffer and an IntBuffer overload, a GL call in a constructor and a lambda in a GL class
        Assertions.assertTrue(
            problemsOf(call(Opcodes.INVOKESTATIC, OPENGL + "GL11", "glDisable", "(I)V", null), "m", false).get(0)
                .contains("constant"));
        Assertions.assertTrue(
            problemsOf(call(Opcodes.INVOKESTATIC, OPENGL + "GL11", "glEnable", "(I)V", 3042), "m", false).get(0)
                .contains("not in the call set"));
        Assertions.assertTrue(
            problemsOf(call(Opcodes.INVOKESTATIC, OPENGL + "GL20", "glVertexAttrib4f", "(IFFFF)V", null), "m", false)
                .toString()
                .contains("V1"));
        Assertions.assertTrue(
            problemsOf(
                call(
                    Opcodes.INVOKESTATIC,
                    OPENGL + "GL11",
                    "glTexSubImage2D",
                    "(IIIIIIIILjava/nio/ShortBuffer;)V",
                    null),
                "m",
                false).toString()
                    .contains("ShortBuffer"));
        Assertions.assertTrue(
            problemsOf(
                call(Opcodes.INVOKESTATIC, OPENGL + "GL30", "glClearBufferu", "(IILjava/nio/IntBuffer;)V", null),
                "m",
                false).toString()
                    .contains("A09"));
        Assertions.assertTrue(
            problemsOf(call(Opcodes.INVOKESTATIC, OPENGL + "GL11", "glFlush", "()V", null), "<init>", false).toString()
                .contains("constructor"));
        Assertions.assertTrue(
            problemsOf(call(Opcodes.INVOKESTATIC, OPENGL + "GL11", "glFlush", "()V", null), "m", true).toString()
                .contains("lambda"));
        Assertions.assertTrue(
            problemsOf(call(Opcodes.INVOKEVIRTUAL, OPENGL + "ContextCapabilities", "x", "()V", null), "m", false)
                .toString()
                .contains("invokestatic"));
        Assertions.assertTrue(
            problemsOf(
                call(Opcodes.INVOKESTATIC, OPENGL + "ARBTimerQuery", "glQueryCounter", "(II)V", null),
                "m",
                false).toString()
                    .contains("GL11-GL33"));
        Assertions.assertEquals(
            Collections.emptyList(),
            problemsOf(call(Opcodes.INVOKESTATIC, OPENGL + "GL11", "glDisable", "(I)V", 2960), "m", false));
        Assertions.assertEquals(
            Collections.emptyList(),
            problemsOf(call(Opcodes.INVOKESTATIC, OPENGL + "GL11", "glDisable", "(I)V", 3042), "m", false));
        // HY-1: D01 as a call is GLSM's and reported, as is Util.checkGLError (redirected from 2.2.16); only its
        // run-time lookup passes, and an LWJGL class literal without it does not
        Assertions.assertTrue(
            problemsOf(call(Opcodes.INVOKESTATIC, OPENGL + "GL11", "glGetError", "()I", null), "m", false).toString()
                .contains("driver-direct"));
        List<String> util = problemsOf(
            call(Opcodes.INVOKESTATIC, OPENGL + "Util", "checkGLError", "()V", null),
            "m",
            false);
        Assertions.assertTrue(
            util.toString()
                .contains("only GL11-GL33"),
            util.toString());
        Report lookup = check(lookup(OPENGL + "GL11", false, "glGetError"));
        Assertions.assertEquals(Collections.emptyList(), lookup.problems);
        Assertions.assertEquals(Collections.singleton("D01"), lookup.ids);
        Assertions.assertTrue(
            check(lookup(OPENGL + "GL11", false, "glFlush")).problems.toString()
                .contains("class literal"));
        Assertions.assertTrue(
            check(lookup(OPENGL + "GL11", false)).problems.toString()
                .contains("class literal"));
        Assertions.assertTrue(
            check(lookup("org/lwjgl/opengl/Util", false, "checkGLError")).problems.toString()
                .contains("class literal"));
        // S9 review F1, TS1: a second lookup in D01's method, with a second literal or with D01's kept in a local
        Assertions.assertTrue(
            check(lookup(OPENGL + "GL11", false, "glGetError", "glFinish")).problems.toString()
                .contains("class literal"));
        Assertions.assertTrue(
            check(lookup(OPENGL + "GL11", true, "glFinish", "glGetError")).problems.toString()
                .contains("class literal"));
    }

    // The check

    /** What one class does that 25 §4 and plan §0 forbid, and which rows it uses. */
    static final class Report {

        final List<String> problems = new ArrayList<>();
        final Set<String> ids = new TreeSet<>();
        int glCalls;
    }

    static Report check(ClassNode node) {
        Report report = new Report();
        boolean lambdas = false;
        for (MethodNode method : node.methods) {
            List<LdcInsnNode> literals = new ArrayList<>();
            List<String> glNames = new ArrayList<>();
            List<String> reflection = new ArrayList<>();
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof InvokeDynamicInsnNode) {
                    lambdas = true;
                    checkIndy(node, (InvokeDynamicInsnNode) insn, report);
                } else if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    checkCall(node, method, call, report);
                    if (call.owner.equals("java/lang/invoke/MethodHandles$Lookup")
                        || call.owner.equals("java/lang/Class")
                        || call.owner.startsWith("java/lang/reflect/")) {
                        reflection.add(call.owner + "." + call.name);
                    }
                } else if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof String) {
                    String string = (String) ((LdcInsnNode) insn).cst;
                    if (GL_NAME.matcher(string)
                        .matches()) {
                        glNames.add(string);
                    }
                    checkString(node, string, report);
                } else if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof Type
                    && ((Type) ((LdcInsnNode) insn).cst).getSort() == Type.OBJECT
                    && ((Type) ((LdcInsnNode) insn).cst).getInternalName()
                        .startsWith("org/lwjgl/")) {
                            literals.add((LdcInsnNode) insn);
                        }
            }
            for (LdcInsnNode literal : literals) {
                checkLookup(node, method, literal, literals.size(), glNames, reflection, report);
            }
        }
        for (FieldNode field : node.fields) {
            if (field.value instanceof String) {
                checkString(node, (String) field.value, report);
            }
        }
        if (lambdas && report.glCalls > 0) {
            report.problems.add(node.name + " calls GL and holds a lambda or method reference (25 R-5; plan §0)");
        }
        return report;
    }

    private static void checkCall(ClassNode node, MethodNode method, MethodInsnNode call, Report report) {
        if (!call.owner.startsWith("org/lwjgl/")) {
            return;
        }
        String text = call.owner + "." + call.name + call.desc;
        String where = node.name + "." + method.name + ": " + text;
        if (call.getOpcode() != Opcodes.INVOKESTATIC || !call.owner.startsWith(OPENGL)) {
            report.problems.add(where + " is no invokestatic on " + OPENGL + " (25 §4 step 1)");
            return;
        }
        report.glCalls++;
        if (!OWNERS.contains(call.owner.substring(OPENGL.length()))) {
            report.problems.add(where + ": only GL11-GL33 (plan §0)");
        }
        String direct = CALL_SET.direct.get(text);
        if (direct != null) {
            report.problems.add(
                where + " is driver-direct ("
                    + direct
                    + "): look it up at run time, since GLSMRedirector rewrites the call (HY-1)");
            return;
        }
        if (method.name.equals("<init>") || method.name.equals("<clinit>")) {
            report.problems.add(where + ": GL work in a constructor or static initializer (plan §0)");
        }
        if (call.name.startsWith("glVertexAttrib") && !call.name.equals("glVertexAttribIPointer")
            && !call.name.equals("glVertexAttribPointer")
            && !call.name.equals("glVertexAttribDivisor")) {
            report.problems.add(where + ": a constant generic attribute (V1, 24:203)");
        }
        if (call.name.startsWith("glClearBuffer")) {
            report.problems.add(where + ": glClearBuffer* is unmapped in GLSM (A09)");
        }
        if (call.desc.contains("Ljava/nio/ShortBuffer;")) {
            report.problems.add(where + ": a ShortBuffer overload (A11)");
        }
        if (call.desc.contains("Ljava/nio/IntBuffer;") && !text.equals(R01)) {
            report.problems.add(where + ": an IntBuffer overload other than R01 (A04-A07)");
        }
        String denied = CALL_SET.denied.get(text);
        if (denied != null) {
            report.problems.add(where + " is " + denied + " of 25 §4.2's deny-list");
        }
        String id;
        if (call.name.equals("glEnable") || call.name.equals("glDisable")) {
            Integer constant = constantBefore(call);
            if (constant == null) {
                report.problems.add(where + " takes no int constant right before the call (25:239)");
                return;
            }
            id = FOLDED.contains(constant) ? CALL_SET.folded.get(text + " " + constant) : CALL_SET.allowed.get(text);
            if (id == null) {
                report.problems.add(where + " with " + constant + " is not in the call set");
                return;
            }
        } else {
            id = CALL_SET.allowed.get(text);
            if (id == null) {
                report.problems.add(where + " is not in the call set (25 §4.1)");
                return;
            }
        }
        report.ids.add(id);
    }

    /**
     * An LWJGL class literal in a method: allowed only as a driver-direct row's lookup in D01's form, that of
     * PassState.lookUpDriverGetError: the row's owner as the method's only LWJGL class literal, right before the row's
     * method name as a string constant, the only GL entry point the method names, in a method whose only reflective
     * call is one MethodHandles.Lookup.findStatic. A second lookup in that method is reported, whether it reuses the
     * literal from a local, takes its name from elsewhere or goes through a helper (S9 review F1, TS1). It counts as a
     * GL call.
     */
    private static void checkLookup(ClassNode node, MethodNode method, LdcInsnNode literal, int literals,
        List<String> glNames, List<String> reflection, Report report) {
        report.glCalls++;
        String type = ((Type) literal.cst).getInternalName();
        AbstractInsnNode next = literal.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        Object looked = next instanceof LdcInsnNode ? ((LdcInsnNode) next).cst : null;
        for (Map.Entry<String, String> row : CALL_SET.direct.entrySet()) {
            String call = row.getKey();
            String owner = call.substring(0, call.indexOf('.'));
            String name = call.substring(call.indexOf('.') + 1, call.indexOf('('));
            if (owner.equals(type) && name.equals(looked)
                && literals == 1
                && glNames.equals(Collections.singletonList(name))
                && reflection.equals(Collections.singletonList(FIND_STATIC))) {
                report.ids.add(row.getValue());
                return;
            }
        }
        report.problems.add(
            node.name + "."
                + method.name
                + ": the class literal "
                + type
                + " outside a driver-direct row's lookup in D01's form, a way past GLSMRedirector (HY-1)");
    }

    private static void checkIndy(ClassNode node, InvokeDynamicInsnNode indy, Report report) {
        List<Object> handles = new ArrayList<>(Arrays.asList(indy.bsmArgs));
        handles.add(indy.bsm);
        for (Object argument : handles) {
            if (argument instanceof Handle && ((Handle) argument).getOwner()
                .startsWith("org/lwjgl/")) {
                Handle handle = (Handle) argument;
                report.problems.add(
                    node.name + ": an invokedynamic handle to "
                        + handle.getOwner()
                        + "."
                        + handle.getName()
                        + " (R-5)");
            } else if (argument instanceof String) {
                checkString(node, (String) argument, report);
            }
        }
    }

    private static void checkString(ClassNode node, String value, Report report) {
        if (value.contains("org.lwjgl.") || value.contains("org/lwjgl/")) {
            report.problems.add(node.name + ": the string \"" + value + "\" names LWJGL");
        }
    }

    /** The int constant pushed right before the call, as GLSMRedirector reads it, or null. */
    private static Integer constantBefore(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn.getPrevious();
        if (previous instanceof IntInsnNode && previous.getOpcode() != Opcodes.NEWARRAY) {
            return ((IntInsnNode) previous).operand;
        }
        if (previous instanceof LdcInsnNode && ((LdcInsnNode) previous).cst instanceof Integer) {
            return (Integer) ((LdcInsnNode) previous).cst;
        }
        if (previous != null && previous.getOpcode() >= Opcodes.ICONST_M1 && previous.getOpcode() <= Opcodes.ICONST_5) {
            return previous.getOpcode() - Opcodes.ICONST_0;
        }
        return null;
    }

    // Classes

    /** Every class of :gl's main output, gl.host included, from the directory the test classpath has it in. */
    private static List<ClassNode> mainClasses() throws IOException {
        Path root;
        try {
            root = Paths.get(
                GlLedger.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
        Assertions.assertTrue(Files.isDirectory(root), ":gl's main classes are not a directory: " + root);
        Path gl = root.resolve("io/github/mindbound/opengpu/gl");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(gl)) {
            files = walk.filter(
                p -> p.toString()
                    .endsWith(".class"))
                .sorted()
                .collect(Collectors.toList());
        }
        List<ClassNode> classes = new ArrayList<>();
        for (Path file : files) {
            ClassNode node = new ClassNode();
            new ClassReader(Files.readAllBytes(file)).accept(node, 0);
            classes.add(node);
        }
        Assertions.assertTrue(classes.size() >= 15, "only " + classes.size() + " classes under " + gl);
        return classes;
    }

    private static ClassNode node(Class<?> type) throws IOException {
        String resource = "/" + type.getName()
            .replace('.', '/') + ".class";
        try (InputStream in = CallSetTest.class.getResourceAsStream(resource)) {
            Assertions.assertNotNull(in, resource);
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }

    private static boolean declares(Class<?> type, String name, String descriptor) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName()
                .equals(name) && Modifier.isStatic(method.getModifiers())
                && Modifier.isPublic(method.getModifiers())
                && Type.getMethodDescriptor(method)
                    .equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    // Synthetic classes for theRulesCatchWhatTheyDescribe

    /** One call, to be preceded by an int constant when {@code constant} is set, else by a local variable load. */
    private static Synthetic call(int opcode, String owner, String name, String descriptor, Integer constant) {
        return new Synthetic(new MethodInsnNode(opcode, owner, name, descriptor, false), constant);
    }

    /**
     * A class whose one method looks each name up on owner in D01's form,
     * MethodHandles.publicLookup().findStatic(Owner.class, name, MethodType.methodType(int.class)): with a class
     * literal each, or with {@code shared} one literal kept in a local; with no name, the class literal alone.
     */
    private static ClassNode lookup(String owner, boolean shared, String... names) {
        ClassNode node = new ClassNode();
        node.name = "synthetic/Lookup";
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "m", "()V", null, null);
        if (shared || names.length == 0) {
            method.instructions.add(new LdcInsnNode(Type.getObjectType(owner)));
        }
        if (shared) {
            method.instructions.add(new VarInsnNode(Opcodes.ASTORE, 0));
        }
        for (String name : names) {
            method.instructions.add(
                new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/invoke/MethodHandles",
                    "publicLookup",
                    "()Ljava/lang/invoke/MethodHandles$Lookup;",
                    false));
            method.instructions
                .add(shared ? new VarInsnNode(Opcodes.ALOAD, 0) : new LdcInsnNode(Type.getObjectType(owner)));
            method.instructions.add(new LdcInsnNode(name));
            method.instructions
                .add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/Integer", "TYPE", "Ljava/lang/Class;"));
            method.instructions.add(
                new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/invoke/MethodType",
                    "methodType",
                    "(Ljava/lang/Class;)Ljava/lang/invoke/MethodType;",
                    false));
            method.instructions.add(
                new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/invoke/MethodHandles$Lookup",
                    "findStatic",
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;",
                    false));
        }
        node.methods.add(method);
        return node;
    }

    /** The problems {@link #check} finds in a class with one method holding the call. */
    private static List<String> problemsOf(Synthetic synthetic, String methodName, boolean withLambda) {
        ClassNode node = new ClassNode();
        node.name = "synthetic/Probe";
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, methodName, "()V", null, null);
        if (synthetic.constant != null) {
            method.instructions.add(new IntInsnNode(Opcodes.SIPUSH, synthetic.constant));
        } else {
            method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        }
        method.instructions.add(synthetic.call);
        if (withLambda) {
            method.instructions.add(
                new InvokeDynamicInsnNode(
                    "run",
                    "()Ljava/lang/Runnable;",
                    new Handle(
                        Opcodes.H_INVOKESTATIC,
                        "java/lang/invoke/LambdaMetafactory",
                        "metafactory",
                        "()V",
                        false)));
        }
        node.methods.add(method);
        return check(node).problems;
    }

    private static final class Synthetic {

        final MethodInsnNode call;
        final Integer constant;

        Synthetic(MethodInsnNode call, Integer constant) {
            this.call = call;
            this.constant = constant;
        }
    }

    /** callset.txt: allowed rows by call, folded rows by "call constant", denied and driver-direct rows by call. */
    static final class CallSet {

        final Map<String, String> allowed = new HashMap<>();
        final Map<String, String> folded = new HashMap<>();
        final Map<String, String> denied = new HashMap<>();
        final Map<String, String> direct = new HashMap<>();
        final Map<String, String> allRows = new LinkedHashMap<>();

        static CallSet load() {
            CallSet set = new CallSet();
            Set<String> ids = new HashSet<>();
            try (InputStream in = CallSetTest.class.getResourceAsStream("callset.txt")) {
                Assertions.assertNotNull(in, "callset.txt");
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] fields = line.split(" ");
                    Assertions.assertTrue(fields.length == 2 || fields.length == 3, line);
                    String id = fields[0];
                    Assertions.assertTrue(ids.add(id), "duplicate id " + id);
                    Assertions.assertTrue(fields[1].startsWith(OPENGL), line);
                    set.allRows.put(id, fields[1]);
                    if (id.startsWith("A")) {
                        set.denied.put(fields[1], id);
                    } else if (id.startsWith("D")) {
                        Assertions.assertEquals(2, fields.length, line);
                        set.direct.put(fields[1], id);
                    } else if (fields.length == 3) {
                        Assertions.assertTrue(id.startsWith("F"), line);
                        set.folded.put(fields[1] + " " + Integer.parseInt(fields[2]), id);
                    } else {
                        set.allowed.put(fields[1], id);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return set;
        }
    }
}
