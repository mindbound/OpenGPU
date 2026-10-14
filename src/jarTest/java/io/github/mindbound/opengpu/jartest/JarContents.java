package io.github.mindbound.opengpu.jartest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * The jars under test, which the jarTest task passes as system properties (addon.gradle.kts), and ways to read them.
 */
final class JarContents {

    private JarContents() {}

    /** The -dev jar and the release jar reobfJar makes from it: what other mods' dev environments and players load. */
    static Stream<Path> shippedJars() {
        return Stream.of(jar("opengpu.devJar"), jar("opengpu.obfJar"));
    }

    static Path jar(String property) {
        String path = System.getProperty(property);
        if (path == null || path.isEmpty()) {
            throw new IllegalStateException(property + " is not set: run these tests through Gradle's jarTest task");
        }
        return Paths.get(path);
    }

    /** A path-list property; empty when the build shades nothing of that kind. */
    static List<Path> jars(String property) {
        List<Path> jars = new ArrayList<>();
        for (String path : System.getProperty(property, "")
            .split(File.pathSeparator)) {
            if (!path.isEmpty()) {
                jars.add(Paths.get(path));
            }
        }
        return jars;
    }

    /** The jar's file entries; directories left out. */
    static Set<String> names(Path jar) throws IOException {
        Set<String> names = new TreeSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory()) {
                    names.add(entry.getName());
                }
            }
        }
        return names;
    }

    /** Every class in the jar, read in full: code, frames and debug information. */
    static List<ClassNode> classes(Path jar) throws IOException {
        List<ClassNode> classes = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements();) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName()
                    .endsWith(".class")) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    ClassNode node = new ClassNode();
                    new ClassReader(in).accept(node, 0);
                    classes.add(node);
                }
            }
        }
        return classes;
    }

    static Manifest manifest(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            return manifest != null ? manifest : new Manifest();
        }
    }
}
