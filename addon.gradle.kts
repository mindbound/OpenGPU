import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.time.Duration
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
    // The root's tests name OC's API types, and compileOnly reaches no test classpath; OC's dev jar, with its Scala
    // internals, is on testRuntimeClasspath already through runtimeOnlyNonPublishable (S11 spike §3.1)
    "testCompileOnly"("com.github.GTNewHorizons:OpenComputers:1.12.64-GTNH:api") { isTransitive = false }
    // DeviceRig, the recording budget and listener, the clocks and executors, as :ocelot-harness takes them
    "testImplementation"(testFixtures(project(":core")))

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

tasks.named<Test>("test") {
    // @Timeout runs its test on a thread of its own, as in :core and :ocelot-harness
    systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
    // A backstop for a hang no @Timeout covers
    timeout.set(Duration.ofMinutes(10))
}

// Both fail the build when they ran nothing, e.g. after a source directory moved
VerifyTestsRan.register(project, tasks.named<Test>("test"), true)
VerifyTestsRan.register(project, jarTest, true)

// The local runServer audit (S11 plan P4.3, W1; decisions D3), off unless asked for.
// -Popengpu.runServer.verboseClass gives runServer's JVM -verbose:class, and -Popengpu.runServer.world=<name> gives
// MinecraftServer.main --world <name>,
// which names the world's folder before server.properties' level-name is read (MinecraftServer.java:1463-1466,
// DedicatedServer.java:217-219), so each audit runs a fresh world of its own and run/server/world stays untouched.
// RFG keeps the task's own args ahead of its calculated ones, and GradleStart and launchwrapper pass an unknown option
// through to main (W1). The world's name is checked here, at the top level, so a bad one fails before any task runs.
val onCi: Boolean = providers.environmentVariable("GITHUB_ACTIONS").orNull == "true"
val runServerVerboseClass: Boolean =
    providers.gradleProperty("opengpu.runServer.verboseClass").orNull.let { it != null && it != "false" }
val runServerWorld: String? = providers.gradleProperty("opengpu.runServer.world").orNull
if (runServerWorld != null) {
    // On CI the shared workflow's runServer step runs the world its server.properties names, as S10's test-only
    // properties fail there (ocelot-harness/build.gradle.kts:202-208)
    if (onCi) {
        throw GradleException(
            "-Popengpu.runServer.world is set on CI, whose runServer step runs the world server.properties names: " +
                "remove it from the command line, gradle.properties or ORG_GRADLE_PROJECT_*",
        )
    }
    // A plain folder name: no '.', '/' or '\' makes it a path, and NTFS ignores case, so "World" would open the
    // owner's world folder (server.properties' level-name=world) and "nul" a device. It starts with a letter or a
    // digit, so no launcher reads it as an option [proposal]
    val reserved = listOf("world", "CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
    if (!Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}").matches(runServerWorld) ||
        reserved.any { it.equals(runServerWorld, ignoreCase = true) }
    ) {
        throw GradleException(
            "-Popengpu.runServer.world is '$runServerWorld'; give a folder name of 1 to 64 ASCII letters, digits, " +
                "'-' and '_' that starts with a letter or a digit and is not 'world' or a Windows device name " +
                "(CON, PRN, AUX, NUL, COM1-9, LPT1-9) in any letter case",
        )
    }
}
if (runServerVerboseClass || runServerWorld != null) {
    tasks.named<JavaExec>("runServer") {
        if (runServerVerboseClass) {
            jvmArgs("-verbose:class")
        }
        if (runServerWorld != null) {
            args("--world", runServerWorld)
        }
    }
}
