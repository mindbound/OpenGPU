package io.github.mindbound.opengpu.jartest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

/**
 * The bytecode reference test of docs/research/24-architecture-gl-first.md §3.1, on the jars that ship (Shadow rewrites
 * type references and string constants while relocating, so only the built jars show what ships). Common code,
 * everything outside OpenGPU's client package and :gl's package, must not name LWJGL, the client packages of
 * Minecraft, FML or Forge, or OpenGPU's own client code: the dedicated server has none of them, and the dev runServer
 * classpath has LWJGL, which would hide a leak (21 §1.3). The scan sees what a dedicated server loads: FML's
 * SideTransformer strips members marked {@code @SideOnly(Side.CLIENT)} there and refuses classes so marked
 * (SideTransformer.java:44-78), so the usual client overrides in common blocks and items pass. Decision 9's two
 * lwjgl3ify footguns are checked in every class.
 */
class BytecodeReferenceTest {

    private static final String OPENGPU = "io/github/mindbound/opengpu/";
    private static final String[] CLIENT_CODE = { OPENGPU + "client/", OPENGPU + "gl/" };
    private static final String[] CLIENT_ONLY = { "org/lwjgl/", "net/minecraft/client/", "cpw/mods/fml/client/",
        "net/minecraftforge/client/", OPENGPU + "client/", OPENGPU + "gl/" };
    private static final String[] CLIENT_CODE_BY_NAME = { "io.github.mindbound.opengpu.client.",
        "io.github.mindbound.opengpu.gl.", OPENGPU + "client/", OPENGPU + "gl/" };
    private static final String SIDE_ONLY = "Lcpw/mods/fml/relauncher/SideOnly;";
    private static final String SIDED_PROXY = "Lcpw/mods/fml/common/SidedProxy;";

    static Stream<Path> shippedJars() {
        return JarContents.shippedJars();
    }

    @ParameterizedTest
    @MethodSource("shippedJars")
    void commonCodeNamesNoClientClass(Path jar) throws IOException {
        List<String> problems = new ArrayList<>();
        for (ClassNode node : JarContents.classes(jar)) {
            if (startsWithAny(node.name, CLIENT_CODE)) {
                continue;
            }
            if (isClientOnly(node.visibleAnnotations)) {
                problems
                    .add(node.name + " is @SideOnly(CLIENT) outside the client packages; a server refuses to load it");
                continue;
            }
            serverView(node);
            References references = References.of(node);
            for (String type : references.types) {
                if (startsWithAny(type, CLIENT_ONLY)) {
                    problems.add(node.name + " references " + type);
                }
            }
            for (String string : references.strings) {
                if (containsAny(string, CLIENT_CODE_BY_NAME)) {
                    problems.add(node.name + " names OpenGPU's client code in \"" + string + "\"");
                }
            }
        }
        assertNone(problems);
    }

    @ParameterizedTest
    @MethodSource("shippedJars")
    void sidedProxiesNameAClassOnEachSide(Path jar) throws IOException {
        List<ClassNode> classes = JarContents.classes(jar);
        Set<String> names = new HashSet<>();
        for (ClassNode node : classes) {
            names.add(node.name);
        }
        List<String> problems = new ArrayList<>();
        int proxies = 0;
        for (ClassNode node : classes) {
            for (FieldNode field : node.fields) {
                AnnotationNode proxy = find(field.visibleAnnotations, SIDED_PROXY);
                if (proxy == null) {
                    continue;
                }
                proxies++;
                // FML loads both by name, the client one only on a client (ProxyInjector.java:58-59)
                String where = node.name + "." + field.name;
                String client = value(proxy, "clientSide").replace('.', '/');
                String server = value(proxy, "serverSide").replace('.', '/');
                if (!client.startsWith(OPENGPU + "client/") || !names.contains(client)) {
                    problems.add(where + ": clientSide " + client + " is no class under " + OPENGPU + "client/");
                }
                if (startsWithAny(server, CLIENT_CODE) || !names.contains(server)) {
                    problems.add(where + ": serverSide " + server + " is no common class in the jar");
                }
            }
        }
        Assertions.assertNotEquals(0, proxies, "no @SidedProxy field found; OpenGPU.proxy is one");
        assertNone(problems);
    }

    @ParameterizedTest
    @MethodSource("shippedJars")
    void noClassNamesAnLwjglClassInAString(Path jar) throws IOException {
        // Decision 9's second footgun: lwjgl3ify remaps LWJGL type references, not strings (14 §2)
        List<String> problems = new ArrayList<>();
        for (ClassNode node : JarContents.classes(jar)) {
            for (String string : References.of(node).strings) {
                if (string.contains("org.lwjgl.") || string.contains("org/lwjgl/")) {
                    problems.add(node.name + ": \"" + string + "\"");
                }
            }
        }
        assertNone(problems);
    }

    @ParameterizedTest
    @MethodSource("shippedJars")
    void nothingOptsOutOfLwjgl3ify(Path jar) throws IOException {
        // Decision 9's first footgun: lwjgl3ify skips a class marked @Lwjgl3Aware and every class of a jar whose
        // manifest says Lwjgl3ify-Aware (14 §2)
        List<String> problems = new ArrayList<>();
        if (JarContents.manifest(jar)
            .getMainAttributes()
            .getValue("Lwjgl3ify-Aware") != null) {
            problems.add("the manifest sets Lwjgl3ify-Aware");
        }
        for (ClassNode node : JarContents.classes(jar)) {
            for (String type : References.of(node).types) {
                if (type.startsWith("me/eigenraven/lwjgl3ify/")) {
                    problems.add(node.name + " references " + type);
                }
            }
        }
        assertNone(problems);
    }

    @ParameterizedTest
    @MethodSource("shippedJars")
    void classFilesAreJava8(Path jar) throws IOException {
        // The one Java 8 convention, on the artifact: OpenGPU's own classes are major 52, shaded libraries at most 52
        List<String> problems = new ArrayList<>();
        for (String name : JarContents.names(jar)) {
            if (name.startsWith("META-INF/versions/")) {
                problems.add(name + ": the jar is not to be multi-release");
            }
        }
        for (ClassNode node : JarContents.classes(jar)) {
            int major = node.version & 0xFFFF;
            boolean own = node.name.startsWith(OPENGPU) && !node.name.startsWith(OPENGPU + "shadow/");
            if (own ? major != 52 : major > 52) {
                problems.add(node.name + " is major " + major);
            }
        }
        assertNone(problems);
    }

    @ParameterizedTest
    @MethodSource("shippedJars")
    void ownReferencesResolveInTheJar(Path jar) throws IOException {
        // A class lost to relocation or minimizing would fail only where it is first loaded, in game
        List<ClassNode> classes = JarContents.classes(jar);
        Set<String> names = new HashSet<>();
        for (ClassNode node : classes) {
            names.add(node.name);
        }
        List<String> problems = new ArrayList<>();
        for (ClassNode node : classes) {
            for (String type : References.of(node).types) {
                if (type.startsWith(OPENGPU) && !names.contains(type)) {
                    problems.add(node.name + " references " + type + ", which the jar lacks");
                }
            }
        }
        assertNone(problems);
    }

    /**
     * What a dedicated server loads (SideTransformer.java:53-78), less what sidedProxiesNameAClassOnEachSide checks.
     */
    private static void serverView(ClassNode node) {
        node.fields.removeIf(field -> isClientOnly(field.visibleAnnotations));
        node.methods.removeIf(method -> isClientOnly(method.visibleAnnotations));
        for (FieldNode field : node.fields) {
            if (field.visibleAnnotations != null) {
                field.visibleAnnotations.removeIf(annotation -> SIDED_PROXY.equals(annotation.desc));
            }
        }
        // InnerClasses entries name nested types without loading them, so a stripped member's types can linger there
        node.innerClasses.clear();
    }

    /** Whether the annotations hold {@code @SideOnly(Side.CLIENT)}, read as SideTransformer reads it (:91-110). */
    private static boolean isClientOnly(List<AnnotationNode> annotations) {
        AnnotationNode sideOnly = find(annotations, SIDE_ONLY);
        if (sideOnly == null || sideOnly.values == null) {
            return false;
        }
        for (int i = 0; i + 1 < sideOnly.values.size(); i += 2) {
            Object value = sideOnly.values.get(i + 1);
            if ("value".equals(sideOnly.values.get(i)) && value instanceof String[]
                && "CLIENT".equals(((String[]) value)[1])) {
                return true;
            }
        }
        return false;
    }

    private static AnnotationNode find(List<AnnotationNode> annotations, String descriptor) {
        if (annotations != null) {
            for (AnnotationNode annotation : annotations) {
                if (descriptor.equals(annotation.desc)) {
                    return annotation;
                }
            }
        }
        return null;
    }

    private static String value(AnnotationNode annotation, String name) {
        if (annotation.values != null) {
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if (name.equals(annotation.values.get(i))) {
                    return String.valueOf(annotation.values.get(i + 1));
                }
            }
        }
        return "";
    }

    private static boolean startsWithAny(String s, String[] prefixes) {
        for (String prefix : prefixes) {
            if (s.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String s, String[] parts) {
        for (String part : parts) {
            if (s.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static void assertNone(List<String> problems) {
        Assertions
            .assertTrue(problems.isEmpty(), () -> problems.size() + " problem(s):\n" + String.join("\n", problems));
    }
}
