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

/**
 * One context-bound Test task on one GL profile, guarded so that it fails the build when it ran or skipped nothing. It
 * runs the gl-tagged tests of [tests] with [extraClasspath] after their runtime classpath; [configure] comes last.
 */
fun glTestTask(
    taskName: String,
    profile: String,
    tests: SourceSet = sourceSets.test.get(),
    extraClasspath: FileCollection = files(),
    configure: Test.() -> Unit = {},
): TaskProvider<Test> {
    val task = tasks.register<Test>(taskName) {
        group = "verification"
        description = "Runs the gl-tagged tests on a $profile LWJGL 2 context, in its own Java 8 JVM."
        testClassesDirs = tests.output.classesDirs
        classpath = tests.runtimeClasspath + extraClasspath
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
        configure()
    }
    VerifyTestsRan.register(project, task, false)
    return task
}

val glCompatTest = glTestTask("glCompatTest", "COMPAT")
// Core 3.3 forward-compatible, the context GLSM runs on; the GLSM legs below add GLSM on it
val glCoreTest = glTestTask("glCoreTest", "CORE_33")
glCoreTest.configure { mustRunAfter(glCompatTest) }

// The GLSM legs (roadmap M0's GLSM spike; 23 §2.3 and design implication 8): src/glsmTest on glCoreTest's context, with
// GLSM from one Angelica release's dev jar per Test task, since GLSM is a JVM singleton, and without FML. The floor's
// jar is the compile target. A plain Java 8 test JVM lacks what FML and Minecraft put next to GLSM in game: the
// libraries below, and the three Minecraft classes GLSM and GTNHLib reach, which src/glsmStubs stands in for. None of
// this reaches glCompatTest or glCoreTest.
val glsmStubs = sourceSets.create("glsmStubs")
val glsmTest = sourceSets.create("glsmTest")
// JUnit and the test fixtures, as src/test has them
configurations.named(glsmTest.implementationConfigurationName) { extendsFrom(configurations.testImplementation.get()) }
configurations.named(glsmTest.runtimeOnlyConfigurationName) { extendsFrom(configurations.testRuntimeOnly.get()) }

// Angelica and GTNHLib also publish an srg variant (reobfElements); ask for their mcp one, as the root's RFG does
val rfgObfuscation = Attribute.of("com.gtnewhorizons.retrofuturagradle.obfuscation", String::class.java)
listOf(glsmTest.compileClasspathConfigurationName, glsmTest.runtimeClasspathConfigurationName).forEach { name ->
    configurations.named(name) { attributes { attribute(rfgObfuscation, "mcp") } }
}

dependencies {
    // Each one not transitive: the modules would add UniMixins, GTNHExtLib, the Kotlin stdlib or the downgrader itself
    "glsmTestCompileOnly"("com.github.GTNewHorizons:Angelica:${OpenGpuVersions.ANGELICA_FLOOR}:dev") { isTransitive = false }
    "glsmTestImplementation"("org.ow2.asm:asm:${OpenGpuVersions.ASM}")
    "glsmTestImplementation"("org.ow2.asm:asm-tree:${OpenGpuVersions.ASM}")
    // GLSM's GTNHLib, and what GTNHLib lists only in its apiElements or loads in game
    "glsmTestRuntimeOnly"("com.github.GTNewHorizons:GTNHLib:${OpenGpuVersions.GTNHLIB}:dev") { isTransitive = false }
    "glsmTestRuntimeOnly"("xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:${OpenGpuVersions.JVMDG_API}:downgraded-8") {
        isTransitive = false
    }
    "glsmTestRuntimeOnly"("it.unimi.dsi:fastutil:${OpenGpuVersions.FASTUTIL}") { isTransitive = false }
    "glsmTestRuntimeOnly"("org.joml:joml:${OpenGpuVersions.JOML}") { isTransitive = false }
    // What Minecraft 1.7.10 ships, which GLSM and GTNHLib take from it in game
    "glsmTestRuntimeOnly"("com.google.guava:guava:${OpenGpuVersions.GUAVA_MC}") { isTransitive = false }
    "glsmTestRuntimeOnly"("org.apache.commons:commons-lang3:${OpenGpuVersions.COMMONS_LANG3_MC}") { isTransitive = false }
    "glsmTestRuntimeOnly"("org.apache.logging.log4j:log4j-api:${OpenGpuVersions.LOG4J_MC}") { isTransitive = false }
    "glsmTestRuntimeOnly"("org.apache.logging.log4j:log4j-core:${OpenGpuVersions.LOG4J_MC}") { isTransitive = false }
    "glsmTestRuntimeOnly"(glsmStubs.output)
}

// -Popengpu.glsm.enableDSA=false puts the GLSM legs on GLSM's bind path everywhere. By default they ask for DSA, as
// Angelica does in game (AngelicaConfig.enableDSA); GLSM then uses it on GL 4.5 and later, except on Intel under Windows.
val glsmEnableDsa: Provider<String> = providers.gradleProperty("opengpu.glsm.enableDSA")

/** One GLSM leg: glsmTest's classes with GLSM from Angelica [angelica]'s dev jar, in its own JVM. */
fun glsmTestTask(taskName: String, angelica: String): TaskProvider<Test> {
    val jar = configurations.dependencyScope("${taskName}Angelica")
    dependencies.add(jar.name, "com.github.GTNewHorizons:Angelica:$angelica:dev") { isTransitive = false }
    // Resolved as glsmTest's runtime classpath is, the attributes spelled out
    val jarClasspath = configurations.resolvable("${taskName}AngelicaClasspath") {
        extendsFrom(jar.get())
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 8)
            attribute(rfgObfuscation, "mcp")
        }
    }
    return glTestTask(taskName, "CORE_33", glsmTest, files(jarClasspath)) {
        description = "Runs the GLSM spike's tests on a CORE_33 context with GLSM from Angelica $angelica, in its own Java 8 JVM."
        systemProperty("opengpu.glsm.angelica", angelica)
        // GLSM reads it once, when its SystemProperties initializes; WARN is the in-game default
        systemProperty("angelica.unmappedGL", "WARN")
        // GLSM's INFO lines in the test log: backend, DSA, FFP emulation, the splash, unmapped calls
        systemProperty("org.apache.logging.log4j.level", "INFO")
        glsmEnableDsa.orNull?.let { systemProperty("opengpu.glsm.enableDSA", it) }
    }
}

val glsmFloorTest = glsmTestTask("glsmFloorTest", OpenGpuVersions.ANGELICA_FLOOR)
glsmFloorTest.configure { mustRunAfter(glCoreTest) }
val glsmPackTest = glsmTestTask("glsmPackTest", OpenGpuVersions.ANGELICA_PACK)
glsmPackTest.configure { mustRunAfter(glsmFloorTest) }
