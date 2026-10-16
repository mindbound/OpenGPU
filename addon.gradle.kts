import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.jvm.tasks.Jar
import org.gradle.process.CommandLineArgumentProvider

// GTNHGradle applies this script after its ShadowModule (module 8 of GTNHGradlePlugin's list; Shadow is module 6, the
// dependencies script 14). A script plugin gets no type-safe accessors, so tasks and extensions are looked up by name
// and type; OpenGpuVersions and VerifyTestsRan come from buildSrc. The Spotless part is in addon.gradle.

// Project classes ship unrelocated, external libraries relocated under io.github.mindbound.opengpu.shadow (24 §3.1,
// 05 open question 2). Shadow tries explicit relocators before its automatic ones and the first match wins (Shadow
// 9.6.1 ShadowJar.kt:568-577, internal/Relocators.kt:40-50), so this identity keeps every io.github.mindbound.opengpu
// package, the root's and the subprojects', where it is, as Angelica keeps glsm (angelica-shadow-common.gradle.kts:25).
// minimizeShadowedDependencies is off: Shadow leaves excluded project jars out of its reachability graph
// (internal/UnusedTracker.kt:75-76), so with them excluded, minimize could only drop library classes that :jit reaches.
tasks.named<ShadowJar>("shadowJar") {
    relocate("io.github.mindbound.opengpu", "io.github.mindbound.opengpu")
}

val sourceSets = the<SourceSetContainer>()
val java8 = the<JavaToolchainService>().launcherFor { languageVersion = JavaLanguageVersion.of(8) }

// Every test runs on JUnit 5 and Java 8, the owner's runtime, as in the subprojects (buildSrc's opengpu.java8).
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    javaLauncher = java8
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

// Tests of the jars that ship, 24 §3.1's bytecode reference test and 05 OQ2's -dev jar check, in their own source set:
// RFG puts ASM 5.0.3 (asm-debug-all) on the root's test classpaths, and these need ASM 9. GTNHGradle adds jabel to the
// main and test processor paths only, so src/jarTest is plain Java 8.
val jarTestSources = sourceSets.create("jarTest")

dependencies {
    "testImplementation"(platform("org.junit:junit-bom:${OpenGpuVersions.JUNIT}"))
    "testImplementation"("org.junit.jupiter:junit-jupiter")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")

    "jarTestImplementation"(platform("org.junit:junit-bom:${OpenGpuVersions.JUNIT}"))
    "jarTestImplementation"("org.junit.jupiter:junit-jupiter")
    "jarTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    "jarTestImplementation"("org.ow2.asm:asm:${OpenGpuVersions.ASM}")
    "jarTestImplementation"("org.ow2.asm:asm-tree:${OpenGpuVersions.ASM}")
    "jarTestImplementation"("org.ow2.asm:asm-commons:${OpenGpuVersions.ASM}")
}

val jarTest = tasks.register<Test>("jarTest") {
    group = "verification"
    description = "Checks the shipped jars: bytecode references (24 §3.1), -dev jar layout (05 OQ2), NOTICE, LICENSE."
    testClassesDirs = jarTestSources.output.classesDirs
    classpath = jarTestSources.runtimeClasspath
    shouldRunAfter(tasks.named("test"))

    // -dev is shadowJar, the release jar is reobfJar made from it, -dev-preshadow is the plain jar (ShadowModule)
    val devJar = tasks.named<Jar>("shadowJar").flatMap { it.archiveFile }
    val obfJar = tasks.named<Jar>("reobfJar").flatMap { it.archiveFile }
    val preshadowJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val apiJar = tasks.named<Jar>("apiJar").flatMap { it.archiveFile }
    val shaded = configurations.getByName("shadowImplementation").incoming
    val projectJars = shaded.artifactView { componentFilter { it is ProjectComponentIdentifier } }.files
    val libraryJars = shaded.artifactView { componentFilter { it !is ProjectComponentIdentifier } }.files
    inputs.files(devJar, obfJar, preshadowJar, apiJar).withPropertyName("jars").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(projectJars).withPropertyName("shadedProjectJars").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(libraryJars).withPropertyName("shadedLibraryJars").withPathSensitivity(PathSensitivity.NONE)
    // The jars pack src/main/resources' NOTICE and LICENSE, copies of the root's, the source of truth (29:179)
    val notice = layout.projectDirectory.file("NOTICE")
    val license = layout.projectDirectory.file("LICENSE")
    inputs.file(notice).withPropertyName("notice").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(license).withPropertyName("license").withPathSensitivity(PathSensitivity.NONE)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dopengpu.devJar=" + devJar.get().asFile.absolutePath,
                "-Dopengpu.obfJar=" + obfJar.get().asFile.absolutePath,
                "-Dopengpu.preshadowJar=" + preshadowJar.get().asFile.absolutePath,
                "-Dopengpu.apiJar=" + apiJar.get().asFile.absolutePath,
                "-Dopengpu.shadedProjectJars=" + projectJars.files.joinToString(File.pathSeparator),
                "-Dopengpu.shadedLibraryJars=" + libraryJars.files.joinToString(File.pathSeparator),
                "-Dopengpu.notice=" + notice.asFile.absolutePath,
                "-Dopengpu.license=" + license.asFile.absolutePath,
            )
        },
    )
}

// Both fail the build when they ran nothing, e.g. after a source directory moved
VerifyTestsRan.register(project, tasks.named<Test>("test"), true)
VerifyTestsRan.register(project, jarTest, true)
