import java.time.Duration

// OpenGPU's GL test kit (24 §3.1, 23 §2.2): its own JUnit 5 extension on a real LWJGL 2 context, for :gl's render core
// (S8), the GLSM leg and the ocelot GL host. It uses LWJGL directly until :gl exists. Context-bound tests carry the gl
// tag and run in their own Test tasks, one JVM per GL profile, on Java 8, wired into check, so the shared workflow runs
// them under its xvfb-run on llvmpipe (23 §1.1, design implication 1).
plugins {
    id("opengpu.java8")
    `java-test-fixtures`
}

repositories {
    // RFG adds Mojang's repository to the root only, and 2.9.4-nightly-20150209 is not on Maven Central
    exclusiveContent {
        forRepository { maven("https://libraries.minecraft.net") { name = "Mojang" } }
        filter { includeGroup("org.lwjgl.lwjgl") }
    }
}

dependencies {
    // Not transitive: the POM adds natives for three systems and JInput; the natives come from the root's extractNatives2
    testFixturesApi("org.lwjgl.lwjgl:lwjgl:${OpenGpuVersions.LWJGL2}") { isTransitive = false }
    testFixturesApi(platform("org.junit:junit-bom:${OpenGpuVersions.JUNIT}"))
    testFixturesApi("org.junit.jupiter:junit-jupiter-api")
}

val glTag = "gl" // GlTest's @Tag

tasks.test {
    useJUnitPlatform { excludeTags(glTag) }
    // Every test here needs a context so far; plain tests (ImageCompare, M1) will run in this task
    failOnNoDiscoveredTests = false
}

// The root's LWJGL 2 natives, <root>/run/natives/lwjgl2, which RFG extracts for the host system (Angelica's form,
// glsm/build.gradle.kts:252-254)
val extractNatives2 = rootProject.tasks.named("extractNatives2")
val lwjglNatives = (extractNatives2.get().property("destinationFolder") as DirectoryProperty).get().asFile.absolutePath

// On CI the renderer must be llvmpipe, so a runner or Mesa change fails loudly; locally,
// -Popengpu.gl.expectRenderer=<part of GL_RENDERER> checks which GPU ran (dgpu-run)
val expectRenderer: Provider<String> = providers.gradleProperty("opengpu.gl.expectRenderer")
    .orElse(providers.environmentVariable("GITHUB_ACTIONS").filter { it == "true" }.map { "llvmpipe" })

/** One context-bound Test task on one GL profile, guarded so that it fails the build when it ran or skipped nothing. */
fun glTestTask(taskName: String, profile: String): TaskProvider<Test> {
    val task = tasks.register<Test>(taskName) {
        group = "verification"
        description = "Runs the gl-tagged tests on a $profile LWJGL 2 context, in its own Java 8 JVM."
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform { includeTags(glTag) }
        dependsOn(extractNatives2)
        jvmArgs(
            "-Djava.library.path=$lwjglNatives",
            "-Dorg.lwjgl.librarypath=$lwjglNatives",
            "-Dorg.lwjgl.opengl.Display.noinput=true",
        )
        systemProperty("opengpu.gl.profile", profile)
        systemProperty("opengpu.gl.reportDir", layout.buildDirectory.dir("reports/opengpu-gl/$taskName").get().asFile.absolutePath)
        expectRenderer.orNull?.let { systemProperty("opengpu.gl.expectRenderer", it) }
        testLogging { showStandardStreams = true }
        timeout = Duration.ofMinutes(10)
        mustRunAfter(tasks.test)
        // The GPU and its driver are no task input: never reuse a result (CI's --build-cache; dgpu-run locally)
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
    VerifyTestsRan.register(project, task, false)
    return task
}

val glCompatTest = glTestTask("glCompatTest", "COMPAT")
// Core 3.3 forward-compatible, the context GLSM runs on; the GLSM spike adds glsmTest on it (roadmap M0, after S4)
val glCoreTest = glTestTask("glCoreTest", "CORE_33")
glCoreTest.configure { mustRunAfter(glCompatTest) }
