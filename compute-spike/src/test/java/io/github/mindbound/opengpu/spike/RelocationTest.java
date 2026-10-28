package io.github.mindbound.opengpu.spike;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.spike.emit.Emitter;
import io.github.mindbound.opengpu.spike.load.KernelLoader;
import io.github.mindbound.opengpu.spike.rt.Kernel;

/**
 * ASM relocated as the mod jar would relocate it (05:58; S14 plan P5.3, amendments I1), on the leg that runs it. The
 * leg's classpath is the test classes, then spikeJar's jar, then testRuntimeClasspath, without main's classes, so the
 * spike runs its relocated ASM from the jar while the unrelocated ASM 9.10.1 of testRuntimeClasspath, which this test
 * itself and CheckClassAdapter use, sits beside it in the same JVM. Each check reads the jar the build passed as
 * {@code opengpu.spike.jar}, which must be spikeJar's and not the plain jar task's.
 */
class RelocationTest {

    private static final String ASM = "org/objectweb/asm/";
    /** The prefix the root's auto-relocation gives a shaded library (DevJarLayoutTest.java:141). */
    private static final String RELOCATED = "io/github/mindbound/opengpu/shadow/" + ASM;
    private static final String SPIKE = "io/github/mindbound/opengpu/spike/";

    @Test
    void theLegGotSpikeJarsJar() {
        Path jar = spikeJar();
        Path plain = Paths.get(property("opengpu.spike.plainJar"));
        Assertions.assertTrue(
            jar.getFileName()
                .toString()
                .endsWith("-spike.jar"),
            () -> jar + " is not spikeJar's");
        Assertions.assertNotEquals(plain.toAbsolutePath(), jar.toAbsolutePath(), "the leg got the plain jar");
        Assertions.assertTrue(Files.isRegularFile(jar), () -> jar + " does not exist");
    }

    @Test
    void classesReferToRelocatedAsmOnly() throws IOException {
        // Every class in the jar, the spike's and ASM's own, so a relocation that missed a name in either fails
        TreeMap<String, List<String>> unrelocated = new TreeMap<>();
        int spikeClasses = 0;
        int spikeReferences = 0;
        try (JarFile jar = new JarFile(spikeJar().toFile())) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                String name = entry.getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                boolean spike = name.startsWith(SPIKE);
                if (spike) {
                    spikeClasses++;
                }
                for (String utf8 : utf8Constants(read(jar, entry))) {
                    String rest = utf8.replace(RELOCATED, "")
                        .replace(RELOCATED.replace('/', '.'), "");
                    if (spike && !rest.equals(utf8)) {
                        spikeReferences++;
                    }
                    if (rest.contains(ASM) || rest.contains(ASM.replace('/', '.'))) {
                        unrelocated.computeIfAbsent(name, k -> new ArrayList<>())
                            .add(utf8);
                    }
                }
            }
        }
        Assertions.assertTrue(spikeClasses > 0, "spikeJar's jar holds no spike class");
        // The emitter and the scan name ClassWriter, ClassReader, the visitors, Label and Opcodes
        Assertions.assertTrue(spikeReferences > 0, "no spike class names the relocated ASM, so this sees nothing");
        Assertions.assertEquals(new TreeMap<>(), unrelocated, "classes naming unrelocated ASM");
    }

    @Test
    void theJarHoldsNoModuleInfoAndAsmOnlyRelocated() throws IOException {
        List<String> wrong = new ArrayList<>();
        boolean writer = false;
        try (JarFile jar = new JarFile(spikeJar().toFile())) {
            for (JarEntry entry : Collections.list(jar.entries())) {
                String name = entry.getName();
                if (name.endsWith("module-info.class") || name.startsWith(ASM)) {
                    wrong.add(name);
                }
                writer |= name.equals(RELOCATED + "ClassWriter.class");
            }
        }
        Assertions.assertEquals(Collections.emptyList(), wrong);
        Assertions.assertTrue(writer, "spikeJar's jar lacks the relocated ClassWriter");
    }

    @Test
    void theLegRunsTheRelocatedClasses() throws Exception {
        Path jar = spikeJar();
        // Main's classes are on the classpath once, from the jar: never from build/classes/java/main
        Assertions.assertEquals(
            Collections.singletonList(jar),
            locations(
                Kernel.class.getName()
                    .replace('.', '/') + ".class"));
        Assertions.assertEquals(
            Collections.singletonList(jar),
            locations(
                Emitter.class.getName()
                    .replace('.', '/') + ".class"));
        // The emitter's writer extends the ClassWriter Shadow relocated, which comes from the jar too
        Class<?> writer = Class.forName("io.github.mindbound.opengpu.spike.emit.NonLoadingWriter")
            .getSuperclass();
        Assertions.assertEquals(RELOCATED.replace('/', '.') + "ClassWriter", writer.getName());
        Assertions.assertEquals(jar, codeSource(writer));
        // The unrelocated copy, testRuntimeClasspath's, is another class from another jar
        Class<?> plain = org.objectweb.asm.ClassWriter.class;
        Assertions.assertNotEquals(jar, codeSource(plain));
        Assertions.assertEquals(
            "asm-9.10.1.jar",
            codeSource(plain).getFileName()
                .toString());
    }

    @Test
    void anEmittedKernelRunsInAChildOfTheJarsLoader() {
        Kernel kernel = KernelLoader.load(SpikeKernels.mix());
        Assertions.assertEquals(
            Emitter.PACKAGE + ".Mix",
            kernel.getClass()
                .getName());
        ClassLoader loader = kernel.getClass()
            .getClassLoader();
        Assertions.assertSame(KernelLoader.class, loader.getClass());
        Assertions.assertSame(KernelLoader.class.getClassLoader(), loader.getParent());
        int[] in = SpikeKernels.mixInput();
        int[] out = new int[in.length];
        int[] expected = new int[in.length];
        kernel.run(in, out, null, null, SpikeKernels.mixUniforms(), 0, in.length, 0, 1);
        JavaKernels.MIX.run(in, expected, null, null, SpikeKernels.mixUniforms(), 0, in.length, 0, 1);
        Assertions.assertArrayEquals(expected, out);
    }

    /** Every classpath copy of a resource: the jar that holds it, or its own file in a classes directory. */
    private static List<Path> locations(String resource) throws IOException, URISyntaxException {
        List<Path> paths = new ArrayList<>();
        Enumeration<URL> urls = RelocationTest.class.getClassLoader()
            .getResources(resource);
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            String spec = url.toString();
            if (spec.startsWith("jar:")) {
                paths.add(Paths.get(new URL(spec.substring(4, spec.indexOf("!/"))).toURI()));
            } else {
                paths.add(Paths.get(url.toURI()));
            }
        }
        return paths;
    }

    private static Path codeSource(Class<?> type) throws URISyntaxException {
        return Paths.get(
            type.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    }

    private static Path spikeJar() {
        return Paths.get(property("opengpu.spike.jar"));
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        Assertions.assertNotNull(value, () -> name + " is not set: run the spike's tests through Gradle");
        return value;
    }

    private static byte[] read(JarFile jar, JarEntry entry) throws IOException {
        try (InputStream in = jar.getInputStream(entry)) {
            byte[] bytes = new byte[(int) entry.getSize()];
            new DataInputStream(in).readFully(bytes);
            return bytes;
        }
    }

    /**
     * A class file's {@code CONSTANT_Utf8} entries (JVMS 4.4), which hold every class, member, descriptor and string
     * the class names; read with no ASM, so the check does not rest on the library it checks.
     */
    private static List<String> utf8Constants(byte[] classFile) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile));
        if (in.readInt() != 0xcafebabe) {
            throw new IOException("not a class file");
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        List<String> utf8 = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1:
                    utf8.add(in.readUTF());
                    break;
                case 3: // Integer
                case 4: // Float
                case 9: // Fieldref
                case 10: // Methodref
                case 11: // InterfaceMethodref
                case 12: // NameAndType
                case 17: // Dynamic
                case 18: // InvokeDynamic
                    in.readInt();
                    break;
                case 5: // Long
                case 6: // Double, which take two entries
                    in.readLong();
                    i++;
                    break;
                case 7: // Class
                case 8: // String
                case 16: // MethodType
                case 19: // Module
                case 20: // Package
                    in.readUnsignedShort();
                    break;
                case 15: // MethodHandle
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                    break;
                default:
                    throw new IOException("constant pool tag " + tag + " at entry " + i);
            }
        }
        return utf8;
    }
}
