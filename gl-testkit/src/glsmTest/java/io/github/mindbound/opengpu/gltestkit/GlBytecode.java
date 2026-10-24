package io.github.mindbound.opengpu.gltestkit;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Assertions;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
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
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * The GLSM tests' bytecode helpers, one copy of each (M0 step S9; S9 plan P7): the steps Angelica's transformer takes
 * with a class ({@link #redirect}), the class lists, GL call lists and LWJGL class literals that GlsmRedirectionTest
 * and GlsmLinkageTest compare before and after them, every LWJGL name a class holds, and GLStateManager's public static
 * methods read from a jar. It names no Angelica type, so glsmLinkageTest's JVM, which has no Angelica jar on its
 * classpath, can load it: GlsmLoader and GlsmLinkageTest each hand it their redirector as a {@link Redirector}.
 */
final class GlBytecode {

    /** The package of LWJGL's GL classes, whose calls GLSMRedirector rewrites. */
    static final String GL_OWNER = "org/lwjgl/opengl/";
    /** What every LWJGL internal name starts with. */
    static final String LWJGL = "org/lwjgl/";
    /** D01: the class, the class literal and the method name of the one lookup GLSM must not redirect (HY-1). */
    static final String[] DRIVER_DIRECT = { "io.github.mindbound.opengpu.gl.PassState", GL_OWNER + "GL11",
        "glGetError" };
    /** D01's one reflective call, as {@link Literal#reflection} lists it (PassState.lookUpDriverGetError). */
    static final String FIND_STATIC = "java/lang/invoke/MethodHandles$Lookup.findStatic";

    private static final int PUBLIC_STATIC = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
    /** A string constant that names a GL entry point: glGetError, or LWJGL's native form nglGetError. */
    private static final Pattern GL_NAME = Pattern.compile("n?gl[A-Z][A-Za-z0-9_]*");

    private GlBytecode() {}

    /** What {@link #redirect} needs of a GLSMRedirector: the three calls Angelica's transformer makes on it. */
    interface Redirector {

        /** GLSMRedirector.getCoreExclusions(): the dotted name prefixes the transformer leaves as they are. */
        String[] coreExclusions();

        /** GLSMRedirector.shouldTransform(byte[]): its constant-pool filter. */
        boolean shouldTransform(byte[] classFile);

        /** GLSMRedirector.transformClassNode(String, ClassNode), with the dotted name: whether it changed the node. */
        boolean transformClassNode(String name, ClassNode node);
    }

    /**
     * {@code original}, the class file of {@code name}, through {@code redirector} by the steps Angelica's transformer
     * takes with every mod class in game: the core exclusions, the constant-pool filter, ClassReader.accept(node, 0),
     * transformClassNode with the dotted name and, only when that changed something, a COMPUTE_MAXS ClassWriter
     * (AngelicaRedirectorTransformer.java:22-47 at 2.2.8, glsm/.../loading/ModRedirector.java:53-74 at 2.2.28 and
     * :56-70 at 2.2.36). What the redirector throws, such as unmappedGL=FAIL's IllegalStateException, reaches the
     * caller.
     */
    static GlsmLoader.Transform redirect(Redirector redirector, String name, byte[] original) {
        boolean excluded = false;
        for (String exclusion : redirector.coreExclusions()) {
            excluded |= name.startsWith(exclusion);
        }
        boolean filtered = !excluded && redirector.shouldTransform(original);
        boolean changed = false;
        byte[] defined = original;
        if (filtered) {
            ClassNode node = new ClassNode();
            new ClassReader(original).accept(node, 0);
            changed = redirector.transformClassNode(name, node);
            if (changed) {
                ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                node.accept(writer);
                defined = writer.toByteArray();
            }
        }
        return new GlsmLoader.Transform(original, defined, excluded, filtered, changed);
    }

    /** The class names under the prefix, from the directory or jar the anchor class file is in. */
    static List<String> classes(String prefix, String anchor) throws IOException, URISyntaxException {
        URL url = GlBytecode.class.getClassLoader()
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

    /** The class file of {@code name} as {@code loader} has it. */
    static byte[] bytes(ClassLoader loader, String name) throws IOException {
        String resource = name.replace('.', '/') + ".class";
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("no " + resource);
            }
            return readAll(in);
        }
    }

    /**
     * The calls on LWJGL's GL classes and on GLStateManager ({@code glsm}), in order, as owner.name+descriptor; a
     * glEnable or glDisable gets the int constant right before it appended in brackets, where GLSMRedirector looks, or
     * " [null]".
     */
    static List<String> calls(byte[] classFile, String glsm) {
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

    /** The capability of a glEnable/glDisable entry of {@link #calls}, or null for another call or no constant. */
    static Integer capability(String member) {
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

    /**
     * An LWJGL class literal in a method, with what tells D01's lookup from another way past the redirector: the string
     * constant right after it, the name D01's form looks up; how many LWJGL class literals the method holds; the GL
     * entry points its string constants name; and its calls on MethodHandles.Lookup, Class and java.lang.reflect. Two
     * are equal when their text is.
     */
    static final class Literal {

        final String method;
        /** The literal's internal name. */
        final String type;
        /** The string constant of the next instruction, or null. */
        final String name;
        final int literals;
        final List<String> glNames;
        /** As owner.name. */
        final List<String> reflection;

        Literal(String method, String type, String name, int literals, List<String> glNames, List<String> reflection) {
            this.method = method;
            this.type = type;
            this.name = name;
            this.literals = literals;
            this.glNames = Collections.unmodifiableList(new ArrayList<>(glNames));
            this.reflection = Collections.unmodifiableList(new ArrayList<>(reflection));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Literal && toString().equals(other.toString());
        }

        @Override
        public int hashCode() {
            return toString().hashCode();
        }

        @Override
        public String toString() {
            return method + ": "
                + type
                + " before "
                + (name == null ? "no string" : "\"" + name + "\"")
                + ", in a method of "
                + literals
                + " LWJGL class literal(s), the GL names "
                + glNames
                + " and the reflective calls "
                + reflection;
        }
    }

    /** Each LWJGL class literal of {@code classFile}, in order. */
    static List<Literal> literals(byte[] classFile) {
        ClassNode node = new ClassNode();
        new ClassReader(classFile).accept(node, 0);
        List<Literal> out = new ArrayList<>();
        for (MethodNode method : node.methods) {
            List<LdcInsnNode> types = new ArrayList<>();
            List<String> glNames = new ArrayList<>();
            List<String> reflection = new ArrayList<>();
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                Object constant = insn instanceof LdcInsnNode ? ((LdcInsnNode) insn).cst : null;
                if (constant instanceof String && GL_NAME.matcher((String) constant)
                    .matches()) {
                    glNames.add((String) constant);
                } else if (constant instanceof Type && lwjglClass((Type) constant)) {
                    types.add((LdcInsnNode) insn);
                } else if (insn instanceof MethodInsnNode && reflective(((MethodInsnNode) insn).owner)) {
                    reflection.add(((MethodInsnNode) insn).owner + "." + ((MethodInsnNode) insn).name);
                }
            }
            for (LdcInsnNode type : types) {
                out.add(
                    new Literal(
                        method.name,
                        ((Type) type.cst).getInternalName(),
                        stringAfter(type),
                        types.size(),
                        glNames,
                        reflection));
            }
        }
        return out;
    }

    private static boolean lwjglClass(Type type) {
        return type.getSort() == Type.OBJECT && type.getInternalName()
            .startsWith(LWJGL);
    }

    // The APIs a class literal can look a method up with
    private static boolean reflective(String owner) {
        return owner.equals("java/lang/invoke/MethodHandles$Lookup") || owner.equals("java/lang/Class")
            || owner.startsWith("java/lang/reflect/");
    }

    // The string constant of the next instruction, past labels, line numbers and frames, or null
    private static String stringAfter(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next instanceof LdcInsnNode && ((LdcInsnNode) next).cst instanceof String
            ? (String) ((LdcInsnNode) next).cst
            : null;
    }

    /**
     * Null for D01's lookup in PassState, else why an LWJGL class literal is a problem: it can reach GL past the
     * redirector. D01's lookup takes the form of PassState.lookUpDriverGetError: GL11's literal, its method's only
     * LWJGL one, right before the string "glGetError", the only GL entry point the method names, in a method whose only
     * reflective call is one MethodHandles.Lookup.findStatic. A second lookup in that method is reported, whether it
     * reuses the literal from a local, takes its name from elsewhere or goes through a helper (S9 review F1, TS1).
     */
    static String literalProblem(String className, Literal literal) {
        boolean lookup = className.equals(DRIVER_DIRECT[0]) && literal.type.equals(DRIVER_DIRECT[1])
            && DRIVER_DIRECT[2].equals(literal.name)
            && literal.literals == 1
            && literal.glNames.equals(Collections.singletonList(DRIVER_DIRECT[2]))
            && literal.reflection.equals(Collections.singletonList(FIND_STATIC));
        return lookup ? null
            : className + ": the class literal " + literal + " is a way past the redirector; only D01's lookup may";
    }

    /** The {@link #lwjglReferences} entry of a literal: the same class literal. */
    static String reference(Literal literal) {
        return literal.method + ": the class literal " + literal.type;
    }

    /**
     * Every LWJGL name {@code classFile} holds, as "member: what it is", in order: its superclass and interfaces, its
     * fields' types, its methods' descriptors and thrown types and, in their code, the owner and descriptor of each
     * call and field access, type instructions, class literals and other type constants, method handles, each
     * invokedynamic's descriptor, bootstrap method and arguments, and caught types. Read from the ClassNode tree only,
     * never the constant pool: javac 9 and later, so the JDK 17 compiler here, leaves a CONSTANT_Class entry for each
     * class whose constants it inlines, which no instruction uses (GlFormats's GL11 and GL30 in :gl's jar, which the
     * root's reobfuscated jar drops; S9 critic A7, review F2).
     */
    static List<String> lwjglReferences(byte[] classFile) {
        ClassNode node = new ClassNode();
        new ClassReader(classFile).accept(node, 0);
        List<String> out = new ArrayList<>();
        if (names(node.superName)) {
            out.add("class: the superclass " + node.superName);
        }
        for (String type : node.interfaces) {
            if (names(type)) {
                out.add("class: the interface " + type);
            }
        }
        for (FieldNode field : node.fields) {
            if (names(field.desc)) {
                out.add(field.name + ": the field type " + field.desc);
            }
        }
        for (MethodNode method : node.methods) {
            String where = method.name + ": ";
            if (names(method.desc)) {
                out.add(where + "the descriptor " + method.desc);
            }
            for (String type : method.exceptions) {
                if (names(type)) {
                    out.add(where + "the thrown type " + type);
                }
            }
            for (TryCatchBlockNode block : method.tryCatchBlocks) {
                if (names(block.type)) {
                    out.add(where + "the caught type " + block.type);
                }
            }
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    if (names(call.owner) || names(call.desc)) {
                        out.add(where + "the call " + call.owner + "." + call.name + call.desc);
                    }
                } else if (insn instanceof FieldInsnNode) {
                    FieldInsnNode field = (FieldInsnNode) insn;
                    if (names(field.owner) || names(field.desc)) {
                        out.add(where + "the field " + field.owner + "." + field.name + ":" + field.desc);
                    }
                } else if (insn instanceof TypeInsnNode && names(((TypeInsnNode) insn).desc)) {
                    out.add(where + "the type " + ((TypeInsnNode) insn).desc);
                } else if (insn instanceof MultiANewArrayInsnNode && names(((MultiANewArrayInsnNode) insn).desc)) {
                    out.add(where + "the array type " + ((MultiANewArrayInsnNode) insn).desc);
                } else if (insn instanceof LdcInsnNode && !(((LdcInsnNode) insn).cst instanceof String)
                    && names(((LdcInsnNode) insn).cst)) {
                        Object constant = ((LdcInsnNode) insn).cst;
                        if (constant instanceof Type && ((Type) constant).getSort() == Type.OBJECT) {
                            out.add(where + "the class literal " + ((Type) constant).getInternalName());
                        } else {
                            out.add(where + "the constant " + constant);
                        }
                    } else if (insn instanceof InvokeDynamicInsnNode) {
                        InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                        if (names(indy.desc)) {
                            out.add(where + "the invokedynamic " + indy.name + indy.desc);
                        }
                        if (names(indy.bsm)) {
                            out.add(where + "the bootstrap method " + indy.bsm);
                        }
                        for (Object argument : indy.bsmArgs) {
                            if (!(argument instanceof String) && names(argument)) {
                                out.add(where + "the bootstrap argument " + argument);
                            }
                        }
                    }
            }
        }
        return out;
    }

    // A name, descriptor, Type, Handle or dynamic constant that names an LWJGL class
    private static boolean names(Object value) {
        return value != null && String.valueOf(value)
            .contains(LWJGL);
    }

    /**
     * The public static methods of {@code owner}, an internal name, as name+descriptor: those of its base entry in the
     * jar and of its superclasses there. Read from the class files, never by reflection, which would load the class and
     * resolve the types of all its methods, Minecraft's and LWJGL's among them (S9 plan P3.2).
     */
    static Set<String> publicStatics(File jar, String owner) throws IOException {
        return publicStatics(jar, owner, 8);
    }

    /**
     * The same for the copies a Java {@code release} JVM loads from a multi-release jar: each class from the highest
     * META-INF/versions/n/ with n at most {@code release} that holds it, else from its base entry.
     */
    static Set<String> publicStatics(File jar, String owner, int release) throws IOException {
        return publicStatics(jar, owner, release, new ArrayList<String>());
    }

    /**
     * The same, with the entry of each class file it reads added to {@code read}, {@code owner}'s first, so a caller
     * can check which copy was compared (S9 review TS2).
     */
    static Set<String> publicStatics(File jar, String owner, int release, List<String> read) throws IOException {
        Set<String> methods = new TreeSet<>();
        try (ZipFile zip = new ZipFile(jar)) {
            for (String name = owner; name != null && !name.equals("java/lang/Object");) {
                ZipEntry entry = entry(zip, name, release);
                read.add(entry.getName());
                ClassNode node = new ClassNode();
                try (InputStream in = zip.getInputStream(entry)) {
                    new ClassReader(readAll(in))
                        .accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
                if (name.equals(owner) && (node.access & Opcodes.ACC_PUBLIC) == 0) {
                    throw new IllegalStateException(
                        owner + " in " + jar.getName() + " is not public: no call from another package links to it");
                }
                for (MethodNode method : node.methods) {
                    if ((method.access & PUBLIC_STATIC) == PUBLIC_STATIC) {
                        methods.add(method.name + method.desc);
                    }
                }
                name = node.superName;
            }
        }
        return methods;
    }

    private static ZipEntry entry(ZipFile zip, String name, int release) throws IOException {
        ZipEntry entry = null;
        for (int n = release; n > 8 && entry == null; n--) {
            entry = zip.getEntry("META-INF/versions/" + n + "/" + name + ".class");
        }
        if (entry == null) {
            entry = zip.getEntry(name + ".class");
        }
        if (entry == null) {
            throw new IOException("no " + name + ".class in " + zip.getName());
        }
        return entry;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
            out.write(chunk, 0, n);
        }
        return out.toByteArray();
    }
}
