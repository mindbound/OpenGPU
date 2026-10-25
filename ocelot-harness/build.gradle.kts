import java.time.Duration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

// The ocelot-brain harness (M0 step S10; RM:56, :209; 23 §4; 05 §3-§4), test-only. ocelot-brain's sources at
// e98a5b2 are fetched at build time into the Gradle user home and checked against the commit on every build
// (buildSrc's PinnedGitCheckout), compiled as the brain source set with Scala 2.13.11 on a Java 8 toolchain, brain's
// 19 Java files in the same Zinc run, and packed into a jar that only the test classpath sees. The Scala tests in
// src/test boot Ocelot in Java 8 test JVMs, one brain.conf profile per Test task (05 §3): the pack's for test and
// ocelotGlTest, the owner's instance's for ocelotInstanceTest and the LuaJ leg, and the pack's with OC-LuaJIT's native
// for the local OC-LuaJIT leg. main has no sources and no shipped project takes this one, so assemble, runClient,
// runServer and the release workflow's `assemble publish -x test` never fetch or compile brain (S10 plan P4.3, P4.7).
plugins {
    id("opengpu.java8")
    scala
}

repositories {
    // RFG adds Mojang's repository to the root only, and :gl-testkit's fixtures bring LWJGL 2.9.4-nightly-20150209,
    // which is not on Maven Central
    exclusiveContent {
        forRepository { maven("https://libraries.minecraft.net") { name = "Mojang" } }
        filter { includeGroup("org.lwjgl.lwjgl") }
    }
    // build.sbt:18-20's URL-only jars as Gradle reads them, the jar alone with no POM; verifyAsieJars checks each by
    // its whole sha256 (the owner's answer to Q-S10-1)
    exclusiveContent {
        forRepository {
            ivy("https://asie.pl/javadeps/") {
                name = "asie.pl javadeps"
                patternLayout { artifact("[module]-[revision].[ext]") }
                metadataSources { artifact() }
            }
        }
        filter { includeGroup("li.cil.repack.com.naef") }
    }
}

val onCi: Boolean = providers.environmentVariable("GITHUB_ACTIONS").orNull == "true"

/**
 * A path property's value, which must be absolute: Project.file would read a relative one against ocelot-harness/ and
 * File against the Gradle daemon's directory, neither of them the directory the build was started from (S10 review
 * B5).
 */
fun absolutePathProperty(name: String): File? = providers.gradleProperty(name).orNull?.let {
    val path = File(it)
    if (!path.isAbsolute) throw GradleException("-P$name is '$it'; give it as an absolute path")
    path
}

// -Popengpu.ocelot.brainRepositories=<url>,<url> and -Popengpu.ocelot.brainStore=<dir> serve the hand-run negative
// cases only (S10 amendments I8, plan P16 M19): the first replaces OpenGpuVersions.BRAIN_REPOSITORIES and fails the
// build on CI, the second keeps the real store out of them
val brainRepositories: Provider<String> = providers.gradleProperty("opengpu.ocelot.brainRepositories")
val brainStore: File? = absolutePathProperty("opengpu.ocelot.brainStore")
val fetchOcelotBrain = tasks.register<PinnedGitCheckout>("fetchOcelotBrain") {
    group = "build"
    description = "Fetches ocelot-brain at ${OpenGpuVersions.BRAIN_COMMIT} once, then checks it against the commit."
    label.set("ocelot-brain")
    repositories.set(
        brainRepositories.map { list -> list.split(',').map { it.trim() }.filter { it.isNotEmpty() } }
            .orElse(OpenGpuVersions.BRAIN_REPOSITORIES),
    )
    repositoriesForcedBy.set(brainRepositories.map { "-Popengpu.ocelot.brainRepositories" })
    commit.set(OpenGpuVersions.BRAIN_COMMIT)
    tree.set(OpenGpuVersions.BRAIN_TREE)
    files.set(OpenGpuVersions.BRAIN_FILES)
    offline.set(gradle.startParameter.isOffline)
    ci.set(onCi)
    // Outside the build directory, so clean keeps it and --offline builds find it; under the Gradle user home's caches,
    // which CI's setup-gradle saves and restores (S10 plan P5.2)
    val store = File(gradle.gradleUserHomeDir, "caches/opengpu/ocelot-brain/${OpenGpuVersions.BRAIN_COMMIT}")
    checkout.set(brainStore ?: store)
    pinFile.set(layout.buildDirectory.file("ocelot-brain/pin.properties"))
}

// Not main, so that no task of assemble reaches brain [proposal; S10 plan P4.3]
val brain: SourceSet = sourceSets.create("brain") {
    // Brain keeps its 19 Java files beside the Scala ones (totoro/ocelot/brain/nbt/*.java, Callback.java), so Zinc
    // compiles them together
    scala.setSrcDirs(listOf(fetchOcelotBrain.flatMap { it.checkout.dir("src/main/scala") }))
    java.setSrcDirs(emptyList<Any>())
    resources.setSrcDirs(listOf(fetchOcelotBrain.flatMap { it.checkout.dir("src/main/resources") }))
}

dependencies {
    // build.sbt:6-17
    "brainImplementation"("org.scala-lang:scala-library:${OpenGpuVersions.BRAIN_SCALA}")
    "brainImplementation"("org.apache.logging.log4j:log4j-core:${OpenGpuVersions.BRAIN_LOG4J}")
    "brainImplementation"("org.apache.logging.log4j:log4j-api:${OpenGpuVersions.BRAIN_LOG4J}")
    "brainImplementation"("com.google.guava:guava:${OpenGpuVersions.BRAIN_GUAVA}")
    "brainImplementation"("commons-codec:commons-codec:${OpenGpuVersions.BRAIN_COMMONS_CODEC}")
    "brainImplementation"("com.typesafe:config:${OpenGpuVersions.BRAIN_CONFIG}")
    "brainImplementation"("org.apache.commons:commons-lang3:${OpenGpuVersions.BRAIN_COMMONS_LANG3}")
    "brainImplementation"("org.apache.commons:commons-text:${OpenGpuVersions.BRAIN_COMMONS_TEXT}")
    "brainImplementation"("commons-io:commons-io:${OpenGpuVersions.BRAIN_COMMONS_IO}")
    "brainImplementation"("org.ow2.asm:asm:${OpenGpuVersions.ASM}")
    // build.sbt:18-20
    "brainImplementation"("li.cil.repack.com.naef:OC-LuaJ:${OpenGpuVersions.OC_LUAJ}")
    "brainImplementation"("li.cil.repack.com.naef:OC-JNLua:${OpenGpuVersions.OC_JNLUA}")
    "brainImplementation"("li.cil.repack.com.naef:OC-JNLua-Natives:${OpenGpuVersions.OC_JNLUA_NATIVES}")
}

scala {
    // scalac and Zinc pinned whatever a classpath carries; with the version set, Gradle no longer infers the Scala
    // classpath from each source set's classpath, which main, with no sources, could not give, and puts scala-library
    // 2.13.11 on main's classpaths itself, which assemble, below, no longer resolves (S10 amendments I4)
    scalaVersion.set(OpenGpuVersions.BRAIN_SCALA)
    zincVersion.set(OpenGpuVersions.ZINC)
}

// main is empty and its jar ships nowhere, so assemble builds nothing here: an offline assemble then resolves no
// scala-library and works on a Gradle home that never ran this project's tests, as before S10 (S10 review B2)
tasks.named("assemble") { setDependsOn(emptyList<Any>()) }

// Zinc and scalac on Java 8, so brain's Java half comes out major 52 against Java 8's own class library (23 §4.3).
// Without source and target 1.8, ScalaCompile would take the convention's toolchain, 17. If Zinc ever fails on Java 8,
// the fallback is a JDK 17 launcher with scalac's -release:8 and javac's compilerArgs --release 8, with no source or
// target compatibility: ScalaCompile's options.release never reaches Zinc's javac (S10 plan P4.5)
val java8 = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) }
tasks.withType<ScalaCompile>().configureEach {
    javaLauncher.set(java8)
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    scalaCompileOptions.encoding = "UTF-8"
    options.encoding = "UTF-8"
}

// The asie.pl jars, by their whole sha256: brain's runtime classpath, whose modules the test classpath takes (below),
// without the brain jar the test classpath adds, which is built from what this checks
val verifyAsieJars = tasks.register<VerifyJarSums>("verifyAsieJars") {
    group = "verification"
    description = "Checks the three asie.pl jars against their pinned sha256 sums."
    jars.from(
        configurations.named(brain.runtimeClasspathConfigurationName).map { classpath ->
            classpath.incoming.artifactView {
                componentFilter { it is ModuleComponentIdentifier && it.group == "li.cil.repack.com.naef" }
            }.files
        },
    )
    sums.put("OC-LuaJ-${OpenGpuVersions.OC_LUAJ}.jar", OpenGpuVersions.OC_LUAJ_SHA256)
    sums.put("OC-JNLua-${OpenGpuVersions.OC_JNLUA}.jar", OpenGpuVersions.OC_JNLUA_SHA256)
    sums.put("OC-JNLua-Natives-${OpenGpuVersions.OC_JNLUA_NATIVES}.jar", OpenGpuVersions.OC_JNLUA_NATIVES_SHA256)
}

tasks.named<ScalaCompile>("compileBrainScala") {
    // Brain's 50 deprecation warnings are not ours to fix, and scalac quotes brain's source lines in them; errors
    // still print
    scalaCompileOptions.additionalParameters = listOf("-nowarn")
    // Nothing reads the downloaded jars before their sums are checked
    dependsOn(verifyAsieJars)
}

tasks.named<ScalaCompile>("compileTestScala") {
    scalaCompileOptions.additionalParameters = listOf("-deprecation", "-feature")
}

// A jar, not class directories: loot's FileSystemAPI.fromClass reads the OpenOS floppy from a jar code source
// directly, while a classes directory depends on the resources directory being on java.class.path. It is no artifact
// of any configuration, so nothing outside this project's tests ever sees it (S10 plan P4.6)
val brainJar = tasks.register<Jar>("brainJar") {
    group = "build"
    description = "Packs ocelot-brain's classes and resources into the jar the harness's tests run on."
    from(brain.output)
    destinationDirectory.set(layout.buildDirectory.dir("ocelot-brain"))
    archiveFileName.set("ocelot-brain-${OpenGpuVersions.BRAIN_COMMIT.take(7)}.jar")
}

configurations.testImplementation { extendsFrom(configurations[brain.implementationConfigurationName]) }

dependencies {
    testImplementation(files(brainJar))
    // The engine core and its fixtures: the tier-E oracle, the test encoder, NoRenderHost's device rig (28 §5.7)
    testImplementation(testFixtures(project(":core")))
    // GlTestHost and the GL test extension, with :gl and LWJGL, for the gl-tagged tests (24:87)
    testImplementation(testFixtures(project(":gl-testkit")))
}

// -Popengpu.ocelot.archs=lua53 narrows a local run to the listed architectures of each task's own list; on CI it fails
// the build, so a gating leg cannot be narrowed there by accident [proposal; S10 plan P12, as S9's review TS3]
val knownArchs = listOf("lua53", "lua52", "lua54", "luaj", "luajit")
val wantedArchs: List<String>? = providers.gradleProperty("opengpu.ocelot.archs").orNull
    ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
if (wantedArchs != null) {
    if (onCi) {
        throw GradleException(
            "-Popengpu.ocelot.archs is set on CI, where every gating ocelot leg runs its whole list: remove it from " +
                "the command line, gradle.properties or ORG_GRADLE_PROJECT_*",
        )
    }
    val unknown = wantedArchs - knownArchs.toSet()
    if (unknown.isNotEmpty()) {
        throw GradleException("-Popengpu.ocelot.archs names $unknown; the architectures are $knownArchs")
    }
}

// -Popengpu.ocelot.pumpsPerTick=N: host frames per 50 ms tick, 3 by default, 23:182's 60 Hz at 20 ticks per second;
// the harness derives 23:188's readback cap N from it (S10 amendments F10). On CI it fails the build, as
// -Popengpu.ocelot.archs does, so the gating legs' cadence and readback cap cannot be changed there by accident (the
// owner, 2026-10-25, after S10's review)
val givenPumpsPerTick: String? = providers.gradleProperty("opengpu.ocelot.pumpsPerTick").orNull
if (givenPumpsPerTick != null && onCi) {
    throw GradleException(
        "-Popengpu.ocelot.pumpsPerTick is set on CI, where every gating ocelot leg runs 3 host frames per tick and " +
            "the readback cap derived from it: remove it from the command line, gradle.properties or " +
            "ORG_GRADLE_PROJECT_*",
    )
}
val pumpsPerTick: String = givenPumpsPerTick ?: "3"
if ((pumpsPerTick.toIntOrNull() ?: 0) < 1) {
    throw GradleException("-Popengpu.ocelot.pumpsPerTick is '$pumpsPerTick'; it must be a whole number of at least 1")
}

// -Popengpu.ocelot.seed=N and -Popengpu.ocelot.cases=N: the encoder corpus's seed and its number of random records,
// 28 and 2 000 by default; the seed also draws the generated scenes (S10 plan P14.3, P14.8). On CI either fails the
// build, as -Popengpu.ocelot.archs does, so the gating corpus cannot be shrunk or swapped there by accident (S10 review
// TS3)
fun wholeProperty(name: String, default: String, least: Long?): String {
    val given = providers.gradleProperty(name).orNull
    if (given != null && onCi) {
        throw GradleException(
            "-P$name is set on CI, where every gating ocelot leg runs the pinned corpus: remove it from the command " +
                "line, gradle.properties or ORG_GRADLE_PROJECT_*",
        )
    }
    val value = given ?: default
    val number = value.toLongOrNull()
    if (number == null || (least != null && number < least)) {
        throw GradleException(
            "-P$name is '$value'; it must be a whole number" + (if (least != null) " of at least $least" else ""),
        )
    }
    return value
}
val corpusSeed: String = wholeProperty("opengpu.ocelot.seed", "28", null)
val corpusCases: String = wholeProperty("opengpu.ocelot.cases", "2000", 0)

// Read by BuildersVerbatimTest, and an input of the task that runs it, so an edit to 28's builder block runs it again
// (S10 amendments I10)
val docs28: File = rootProject.file("docs/research/28-command-stream-v1.md")

/**
 * One ocelot leg: [profile]'s brain.conf in this Test task's own Java 8 JVM, one Ocelot initialisation per JVM
 * (05 §3), on [archs] (narrowed by -Popengpu.ocelot.archs), running the tests [tags] selects. [fullSet] is the part of
 * [archs] that runs the whole gating set (encoder identity, worked frames, NoRenderer, argument rows and, on a GL leg,
 * Lua bytes == FBO bytes and GL parity); the others run only the tests that ask for every arch of the task, such as
 * the charges and the queue tests (S10 decisions D4). The full set is never empty: a gating method allows zero
 * invocations only for a run narrowed by -Popengpu.ocelot.archs, since Gradle counts a method with none as one passed
 * test, which VerifyTestsRan cannot tell from a run, so the harness refuses an empty one otherwise (S10 review
 * TS1). The task's name goes to the JVM beside its profile, so ProfileTest can hold each task to its own (review TS2).
 * Its record, build/reports/opengpu-ocelot/<task>/ocelot.txt (05:116), is an output, so the build cache restores it
 * with the results. [paths] adds a leg's own paths to the JVM's arguments.
 */
fun Test.ocelotLeg(
    profile: String,
    archs: List<String>,
    tags: String,
    fullSet: List<String> = archs,
    readsDocs28: Boolean = false,
    paths: OcelotRun.() -> Unit = {},
) {
    require(fullSet.isNotEmpty()) { "$name: the full set is empty, so its gating tests would run on no arch" }
    require(archs.containsAll(fullSet)) { "$name: the full set $fullSet is not within $archs" }
    useJUnitPlatform { includeTags(tags) }
    systemProperty("opengpu.ocelot.task", name)
    systemProperty("opengpu.ocelot.profile", profile)
    val running = archs.filter { wantedArchs == null || it in wantedArchs }
    val full = fullSet.filter { wantedArchs == null || it in wantedArchs }
    systemProperty("opengpu.ocelot.archs", running.joinToString(","))
    systemProperty("opengpu.ocelot.fullSet", full.joinToString(","))
    systemProperty("opengpu.ocelot.narrowed", (wantedArchs != null).toString())
    if (wantedArchs != null) {
        val note = "[opengpu-ocelot] $name is narrowed by -Popengpu.ocelot.archs to $running, full set $full; a " +
            "gating test with no arch left passes with no invocation"
        doFirst { logger.lifecycle(note) }
    }
    systemProperty("opengpu.ocelot.pumpsPerTick", pumpsPerTick)
    systemProperty("opengpu.ocelot.seed", corpusSeed)
    systemProperty("opengpu.ocelot.cases", corpusCases)
    val reportDir = layout.buildDirectory.dir("reports/opengpu-ocelot/$name")
    outputs.dir(reportDir).withPropertyName("ocelotReport")
    jvmArgumentProviders.add(
        objects.newInstance(OcelotRun::class.java).also {
            it.runDir.set(layout.buildDirectory.dir("ocelot-run/$name"))
            it.reportDir.set(reportDir)
            it.projectDir.set(layout.projectDirectory)
            it.pinFile.set(fetchOcelotBrain.flatMap { fetch -> fetch.pinFile })
            if (readsDocs28) it.docs28.set(docs28)
            it.paths()
        },
    )
}

// The root's LWJGL 2 natives, <root>/run/natives/lwjgl2, which RFG extracts for the host system, as :gl-testkit's GL
// tasks take them (gl-testkit/build.gradle.kts:69-72)
val extractNatives2 = rootProject.tasks.named("extractNatives2")
val lwjglNatives = (extractNatives2.get().property("destinationFolder") as DirectoryProperty).get().asFile.absolutePath
// -Popengpu.gl.readbackFinish=auto|always|never and -Popengpu.gl.expectRenderer=<part of GL_RENDERER>, read as
// :gl-testkit reads them: the readbacks' glFinish for every GlBackend the fixtures make (Q-S8-3), auto when unset, and
// the renderer, llvmpipe on CI, so a runner or Mesa change fails loudly (gl-testkit/build.gradle.kts:39-43, :74-77)
val glReadbackFinish: Provider<String> = providers.gradleProperty("opengpu.gl.readbackFinish").orElse("auto")
val expectRenderer: Provider<String> = providers.gradleProperty("opengpu.gl.expectRenderer")
    .orElse(providers.environmentVariable("GITHUB_ACTIONS").filter { it == "true" }.map { "llvmpipe" })

/**
 * A GL leg's settings, duplicated from :gl-testkit's glTestTask (gl-testkit/build.gradle.kts:85-130) rather than
 * factored into buildSrc, which would touch S8's and S9's legs (S10 plan P12, P17.4): GlTestHost on a COMPAT LWJGL 2
 * context, one per JVM (GlContextExtension), with the root's natives. GlTestHost.readbackFinish() throws in a JVM
 * that lacks opengpu.gl.readbackFinish, which guards the duplication. gl-context.txt goes beside ocelot.txt. The GPU
 * and its driver are no task input, so a result is never reused.
 */
fun Test.ocelotGl() {
    dependsOn(extractNatives2)
    jvmArgs(
        "-Djava.library.path=$lwjglNatives",
        "-Dorg.lwjgl.librarypath=$lwjglNatives",
        "-Dorg.lwjgl.opengl.Display.noinput=true",
    )
    systemProperty("opengpu.gl.profile", "COMPAT")
    systemProperty(
        "opengpu.gl.reportDir",
        layout.buildDirectory.dir("reports/opengpu-ocelot/$name").get().asFile.absolutePath,
    )
    systemProperty("opengpu.gl.readbackFinish", glReadbackFinish.get())
    expectRenderer.orNull?.let { systemProperty("opengpu.gl.expectRenderer", it) }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

tasks.withType<Test>().configureEach {
    // One JVM per task, so one profile per JVM: brain's settings are a JVM's (05 §3)
    maxParallelForks = 1
    forkEvery = 0
    // @Timeout runs its test on a thread of its own, as in :core and :gl-testkit
    systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
    maxHeapSize = "1g"
    dependsOn(verifyAsieJars)
    testLogging { showStandardStreams = true }
    // A backstop for a hang no @Timeout covers
    timeout.set(Duration.ofMinutes(10))
}

// GTNH 2.9.0-RC-2's OpenComputers settings (10 item 1), NoRenderHost by default (24:87), and the unit tests, which
// need no Ocelot
tasks.test {
    ocelotLeg("pack", listOf("lua53", "lua52"), "(ocelot-pack | ocelot-unit | ocelot-every) & !gl", readsDocs28 = true)
}

// The owner's instance settings (10 item 2), with Lua 5.4, which the pack leaves off. 5.4 gates with the whole set 5.3
// has, here, since the pack profile ships with 5.4 off, both paths and Lua bytes == FBO bytes on GlTestHost included,
// so this is a GL task; 5.3 and 5.2, which the pack legs cover, run only the instance-tagged tests that take every
// arch (S10 decisions D4). GL JVMs run one at a time, after :gl-testkit's, which order themselves
// (gl-testkit/build.gradle.kts:132-265) [proposal; S10 plan P12]
val ocelotInstanceTest = tasks.register<Test>("ocelotInstanceTest") {
    group = "verification"
    description = "Runs the ocelot tests under the owner's instance settings, GL ones included, in their own Java 8 " +
        "JVM."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    ocelotLeg(
        "instance",
        listOf("lua53", "lua52", "lua54"),
        "ocelot-instance | ocelot-every",
        fullSet = listOf("lua54"),
    )
    ocelotGl()
    mustRunAfter(tasks.test, ":gl-testkit:glsmPackReleaseConfigTest")
}

// RM:65's "Ocelot on llvmpipe: Lua bytes == FBO bytes on 5.3 and 5.2 in CI" (24:248): the pack's GL tests on
// GlTestHost, 23:259's [proposal] name (S10 plan P12)
val ocelotGlTest = tasks.register<Test>("ocelotGlTest") {
    group = "verification"
    description = "Runs the gl-tagged ocelot tests under the pack's settings on a COMPAT LWJGL 2 context, in their " +
        "own Java 8 JVM."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    ocelotLeg("pack", listOf("lua53", "lua52"), "(gl & ocelot-pack) | ocelot-every")
    ocelotGl()
    mustRunAfter(tasks.test, ocelotInstanceTest)
}

// LuaJ, non-gating and for correctness only (S10 decisions D5, whose [name proposal] this task's name is): OC
// registers it when no native loads, so a player can land on it without choosing it. The instance profile, where
// brain registers it, runs the char path's encoder identity, NoRendererTest and the argument rows on it, but not the
// string.pack path, which 28 §9.3 names for 5.3 and 5.4 only. Started by name, never by check; VerifyTestsRan
// finalizes it (S10 amendments I5). A LuaJ failure is recorded, and fixed by no LuaJ-specific code
val ocelotLuaJCorrectnessTest = tasks.register<Test>("ocelotLuaJCorrectnessTest") {
    group = "verification"
    description = "Runs the char path's encoder identity, NoRendererTest and the argument rows on LuaJ under the " +
        "owner's instance settings; not in check."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    ocelotLeg("instance", listOf("luaj"), "(ocelot-luaj & !pack-path) | ocelot-every")
    mustRunAfter(tasks.test, ocelotInstanceTest, ocelotGlTest)
}

// The local OC-LuaJIT leg (RM:221; S10 plan P13 and decisions D6), RM:65's "on OC-LuaJIT on the owner's machine":
// non-gating and outside check, so it runs only when started by name, `ocelotLuajitTest
// -Popengpu.ocluajit.dir=<the owner's OC-LuaJIT checkout>`, even with the property in gradle.properties (S10 amendments
// I5), and it is SKIPPED without the property. It reads the checkout in place and writes nothing there. ocluajitInputs
// first checks every file it uses against OpenGpuVersions.OCLUAJIT_FLOOR (a98787b); the luajit source set compiles the
// adapter, test/native/OcljArch.scala, and the LuaStateLuaJIT.java it imports into this build directory; ocluajitKernel
// patches brain's kernel with the checkout's own patcher and luajit; Brain.init stages a copy of the additive native in
// the run directory (I11). -Popengpu.ocluajit.native= and -Popengpu.ocluajit.luajit= name a native or a luajit built
// elsewhere, under the same rules; these three paths must be absolute (S10 review B5). -Popengpu.ocluajit.floor=
// replaces the floor for the hand-run cases only and fails on CI (I16)
val ocluajitDir: File? = absolutePathProperty("opengpu.ocluajit.dir")
val ocluajitFloor: Provider<String> = providers.gradleProperty("opengpu.ocluajit.floor")
// The host as brain's LuaStateFactory and OC-LuaJIT's build-native.sh spell it in file names: windows-x86_64 or
// linux-x86_64, the two hosts OC-LuaJIT builds for
val windowsHost: Boolean = providers.systemProperty("os.name").get().startsWith("Windows")
val hostPlatform: String = (if (windowsHost) "windows" else providers.systemProperty("os.name").get().lowercase()) +
    "-" + providers.systemProperty("os.arch").get().let { if (it == "amd64") "x86_64" else it }
val ocluajitNativeName = "libjnluajit52-$hostPlatform" + if (windowsHost) ".dll" else ".so"
val ocluajitNative: File? = ocluajitDir?.let { dir ->
    absolutePathProperty("opengpu.ocluajit.native") ?: dir.resolve("build/native/libdir-additive/$ocluajitNativeName")
}
val ocluajitLuajit: File? = ocluajitDir?.let { dir ->
    absolutePathProperty("opengpu.ocluajit.luajit")
        ?: dir.resolve("build/native/luajit-$hostPlatform/src/" + if (windowsHost) "luajit.exe" else "luajit")
}

// LuaStateLuaJIT.java through the convention's JavaCompile (JDK 17, jabel, release 8), as OC-LuaJIT builds it, so a
// later commit's newer syntax still compiles; Zinc on Java 8 then compiles OcljArch.scala alone against it, brain and
// brain's jars (S10 amendments I14). Without the property the source set has no sources (plan P13.2)
val luajitSet: SourceSet = sourceSets.create("luajit") {
    java.setSrcDirs(listOfNotNull(ocluajitDir?.resolve("src/main/java")))
    java.include("li/cil/repack/com/naef/jnlua/LuaStateLuaJIT.java")
    scala.setSrcDirs(listOfNotNull(ocluajitDir?.resolve("test/native")))
    scala.include("OcljArch.scala")
    resources.setSrcDirs(emptyList<Any>())
}
configurations[luajitSet.implementationConfigurationName].extendsFrom(
    configurations[brain.implementationConfigurationName],
)
dependencies { "luajitImplementation"(files(brainJar)) }
tasks.named<JavaCompile>(luajitSet.compileJavaTaskName) {
    // javac's warnings would quote OC-LuaJIT's source lines, as scalac's would (S10 decisions D12); errors still print
    options.isWarnings = false
}
tasks.named<ScalaCompile>(luajitSet.getCompileTaskName("scala")) {
    scalaCompileOptions.additionalParameters = listOf("-nowarn")
    classpath = classpath.plus(
        files(tasks.named<JavaCompile>(luajitSet.compileJavaTaskName).flatMap { it.destinationDirectory }),
    )
}

val ocluajitInputs: TaskProvider<OcLuajitInputs>? = ocluajitDir?.let { dir ->
    tasks.register<OcLuajitInputs>("ocluajitInputs") {
        group = "verification"
        description = "Checks the OC-LuaJIT leg's files in $dir against OC-LuaJIT's floor commit."
        checkout.set(dir)
        floor.set(ocluajitFloor.orElse(OpenGpuVersions.OCLUAJIT_FLOOR))
        floorForcedBy.set(ocluajitFloor.map { "-Popengpu.ocluajit.floor" })
        ci.set(onCi)
        sources.put("adapter", "test/native/OcljArch.scala")
        sources.put("LuaJIT state", "src/main/java/li/cil/repack/com/naef/jnlua/LuaStateLuaJIT.java")
        sources.put("kernel patcher", "native/kernel/patch-machine-lua.lua")
        nativeLibrary.set(ocluajitNative)
        nativeName.set(ocluajitNativeName)
        luajitExecutable.set(ocluajitLuajit)
        provenance.set(layout.buildDirectory.file("ocelot-luajit/provenance.txt"))
    }
}
val ocluajitKernel: TaskProvider<OcLuajitKernel>? = ocluajitDir?.let { dir ->
    tasks.register<OcLuajitKernel>("ocluajitKernel") {
        group = "build"
        description = "Patches ocelot-brain's machine.lua with OC-LuaJIT's kernel patcher and luajit, as OC-LuaJIT's " +
            "smoke-test.sh does."
        // luajit runs only once the checkout has passed its rules
        ocluajitInputs?.let { dependsOn(it) }
        luajitExecutable.set(ocluajitLuajit)
        patcher.set(dir.resolve("native/kernel/patch-machine-lua.lua"))
        machineLua.set(
            fetchOcelotBrain.flatMap { it.checkout.file("src/main/resources/assets/opencomputers/lua/machine.lua") },
        )
        resources.set(layout.buildDirectory.dir("ocelot-luajit/resources"))
    }
}
if (ocluajitInputs != null) {
    // Nothing compiles from a checkout that fails its rules
    tasks.named(luajitSet.compileJavaTaskName) { dependsOn(ocluajitInputs) }
    tasks.named(luajitSet.getCompileTaskName("scala")) { dependsOn(ocluajitInputs) }
}

// The pack's settings with forceNativeLibPathFirst at the staged native, on OC-LuaJIT's architecture alone: the
// pack-tagged tests, GL ones included, but not 28 §9.3's string.pack path (28:907), and OcLuajitTest's check of the
// compiled adapter (plan P13.3, P14.1)
val ocelotLuajitTest = tasks.register<Test>("ocelotLuajitTest") {
    group = "verification"
    description = "Runs the pack's ocelot tests, GL ones included, on OC-LuaJIT from -Popengpu.ocluajit.dir=<checkout>, " +
        "in their own Java 8 JVM; only when started by name."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    val tags = "(ocelot-pack & !pack-path) | ocelot-every | ocelot-luajit"
    if (ocluajitInputs != null && ocluajitKernel != null) {
        // The kernel and the adapter ahead of the test classpath, as smoke-test.sh:486 and :622 put its own classes
        classpath = files(ocluajitKernel.flatMap { it.resources }) + luajitSet.output +
            sourceSets.test.get().runtimeClasspath
        ocelotLeg("luajit", listOf("luajit"), tags) {
            stageNative.set(ocluajitNative)
            ocluajitProvenance.set(ocluajitInputs.flatMap { it.provenance })
        }
    } else {
        classpath = sourceSets.test.get().runtimeClasspath
        ocelotLeg("luajit", listOf("luajit"), tags)
    }
    ocelotGl()
    mustRunAfter(tasks.test, ocelotInstanceTest, ocelotGlTest, ocelotLuaJCorrectnessTest)
    val set = ocluajitDir != null
    onlyIf("-Popengpu.ocluajit.dir is set") {
        if (!set) {
            it.logger.lifecycle(
                "ocelotLuajitTest skipped: set -Popengpu.ocluajit.dir=<OC-LuaJIT checkout> to run the local " +
                    "OC-LuaJIT leg",
            )
        }
        set
    }
}

// The first three gate in check: a leg that ran no test or skipped one fails the build, and so does a gating test with
// no arch to run on (ocelotLeg). The LuaJ leg is checked the
// same way whenever it is started by name, and so is the OC-LuaJIT leg when its property is set; unset, it is skipped
// and has no check
VerifyTestsRan.register(project, tasks.test, false)
VerifyTestsRan.register(project, ocelotInstanceTest, false)
VerifyTestsRan.register(project, ocelotGlTest, false)
VerifyTestsRan.register(project, ocelotLuaJCorrectnessTest, false, false)
if (ocluajitDir != null) VerifyTestsRan.register(project, ocelotLuajitTest, false, false)
