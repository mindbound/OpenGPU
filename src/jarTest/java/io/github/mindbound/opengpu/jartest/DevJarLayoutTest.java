package io.github.mindbound.opengpu.jartest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The -dev jar check of docs/research/05-dev-stack-and-testing.md, open question 2, on every build: the root's and
 * every shaded subproject's classes sit at their own paths, external libraries sit relocated under
 * io/github/mindbound/opengpu/shadow/, and the release jar keeps every entry. GTNHGradle's defaults would have moved
 * the subprojects under io/github/mindbound/opengpu/shadow/io/github/mindbound/opengpu/ (24 §3.1).
 */
class DevJarLayoutTest {

    private static final String OPENGPU = "io/github/mindbound/opengpu/";
    private static final String SHADOW = OPENGPU + "shadow/";
    private static final String MANIFEST = "META-INF/MANIFEST.MF";
    // RFG adds the generated Tags class to the plain jar only; its one constant is inlined wherever it is used
    private static final String TAGS = OPENGPU + "Tags.class";
    private static final String CORE_FIXTURES = OPENGPU + "core/fixtures/";

    static Stream<Path> shippedJars() {
        return JarContents.shippedJars();
    }

    @ParameterizedTest
    @MethodSource("shippedJars")
    void everyClassIsInOpenGpusNamespace(Path jar) throws IOException {
        // An unrelocated library could clash with another mod's copy, and org.objectweb.asm would lose to the
        // launcher's ASM, which LaunchClassLoader loads parent-first (05 §1)
        List<String> problems = new ArrayList<>();
        for (String name : JarContents.names(jar)) {
            if (name.endsWith(".class") && !name.startsWith(OPENGPU)) {
                problems.add(name);
            }
        }
        assertNone(problems);
    }

    @ParameterizedTest
    @MethodSource("shippedJars")
    void projectClassesAreNotRelocated(Path jar) throws IOException {
        List<String> problems = new ArrayList<>();
        for (String name : JarContents.names(jar)) {
            if (name.startsWith(SHADOW + "io/")) {
                problems.add(name);
            }
        }
        assertNone(problems);
    }

    @ParameterizedTest
    @MethodSource("shippedJars")
    void noTestFixtureClassShips(Path jar) throws IOException {
        // :core's test fixtures (the tier-E oracle, the synthetic glyphs, the test encoder) are test-only and ship
        // in no jar (docs/research/28-command-stream-v1.md §5.7)
        List<String> problems = new ArrayList<>();
        for (String name : JarContents.names(jar)) {
            if (name.startsWith(CORE_FIXTURES)) {
                problems.add(name);
            }
        }
        assertNone(problems);
    }

    @Test
    void devJarHoldsEveryProjectEntryAtItsOwnPath() throws IOException {
        List<Path> projects = JarContents.jars("opengpu.shadedProjectJars");
        Assertions.assertFalse(
            projects.isEmpty(),
            "no shaded subproject; dependencies.gradle lists them as shadowImplementation(project(...))");
        Set<String> dev = JarContents.names(JarContents.jar("opengpu.devJar"));
        List<String> problems = new ArrayList<>();
        List<Path> sources = new ArrayList<>(projects);
        sources.add(JarContents.jar("opengpu.preshadowJar"));
        for (Path source : sources) {
            boolean hasClass = false;
            for (String name : JarContents.names(source)) {
                hasClass |= name.endsWith(".class");
                if (!name.equals(MANIFEST) && !name.equals(TAGS) && !dev.contains(name)) {
                    problems.add(source.getFileName() + "!/" + name + " is missing from the -dev jar");
                }
            }
            // a jar without a class would pass the check above without being checked
            if (!hasClass) {
                problems.add(source.getFileName() + " holds no class");
            }
        }
        assertNone(problems);
    }

    @Test
    void devJarHoldsEveryShadedLibraryRelocated() throws IOException {
        // None yet: ASM comes with :jit, at io/github/mindbound/opengpu/shadow/org/objectweb/asm/ (05 OQ2). A library
        // minimized on purpose gets an explicit allowlist here, never a looser check.
        Set<String> dev = JarContents.names(JarContents.jar("opengpu.devJar"));
        List<String> problems = new ArrayList<>();
        for (Path library : JarContents.jars("opengpu.shadedLibraryJars")) {
            for (String name : JarContents.names(library)) {
                if (name.endsWith(".class") && !name.startsWith("META-INF/")
                    && !name.endsWith("module-info.class")
                    && !dev.contains(SHADOW + name)) {
                    problems.add(library.getFileName() + "!/" + name + " is not at " + SHADOW + name);
                }
            }
        }
        assertNone(problems);
    }

    @Test
    void devJarHoldsTheApiJarsClasses() throws IOException {
        // The -api jar is cut from the root's own classes, so a root package relocated in the -dev jar would stop
        // matching it (24, amendments of 2026-10-14)
        Set<String> dev = JarContents.names(JarContents.jar("opengpu.devJar"));
        List<String> problems = new ArrayList<>();
        for (String name : JarContents.names(JarContents.jar("opengpu.apiJar"))) {
            if (name.endsWith(".class") && !dev.contains(name)) {
                problems.add(name);
            }
        }
        assertNone(problems);
    }

    @Test
    void releaseJarHasTheDevJarsEntries() throws IOException {
        // reobfJar, built from shadowJar (ShadowModule), renames members, not entries
        Assertions.assertEquals(
            JarContents.names(JarContents.jar("opengpu.devJar")),
            JarContents.names(JarContents.jar("opengpu.obfJar")));
    }

    private static void assertNone(List<String> problems) {
        Assertions
            .assertTrue(problems.isEmpty(), () -> problems.size() + " problem(s):\n" + String.join("\n", problems));
    }
}
