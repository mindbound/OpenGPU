// The engine core (24 §3.1): host-neutral, no Minecraft, OpenComputers or LWJGL; shaded into the mod jar unrelocated.
// Its test fixtures (28 §5.7: the tier-E oracle, the synthetic glyph table, the test encoder) ship in no jar; :gl-testkit
// and :ocelot-harness take them as testFixtures(project(":core")).
import java.time.Duration

plugins {
    id("opengpu.java8")
    `java-test-fixtures`
}

// Font 0's table (28:631; 29:302): the build-time converter (own code, JDK only) in src/fontgen, run on misc-fixed
// 6x13's BDF; the table ships as a classpath resource of :core, the converter in no jar.
val fontgen = sourceSets.create("fontgen")
val generateFont0 = tasks.register<JavaExec>("generateFont0") {
    group = "build"
    description = "Converts misc-fixed 6x13.bdf into font 0's glyph table (28 §7.1)."
    // Everything the task keeps is computed here as plain values: an argument provider reading a script-level val
    // would capture the script object, which the configuration cache cannot store (S8 amendments F)
    val bdf = layout.projectDirectory.file("src/fonts/misc-fixed/6x13.bdf")
    val outDir = layout.buildDirectory.dir("generated/font0")
    classpath = fontgen.runtimeClasspath
    mainClass.set("io.github.mindbound.opengpu.core.fontgen.BdfToGlyphTable")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(8)) })
    inputs.file(bdf).withPathSensitivity(PathSensitivity.NONE).withPropertyName("bdf")
    outputs.dir(outDir).withPropertyName("font0Dir")
    args(
        bdf.asFile.absolutePath,
        outDir.get().file("io/github/mindbound/opengpu/core/font/font0-6x13.glyphs").asFile.absolutePath,
        "d00cf1cbaed055bb91d11c80e063d5d2d3b66552a7f2ead7161ee3780f9123f0", // 29:381
        "6x13.bdf",
    )
    // JavaExec is not cacheable by default; this one is a function of the BDF, the arguments and the converter's
    // classes, and reads no clock or environment
    outputs.cacheIf { true }
}
// The task as a resource directory: processResources depends on it
sourceSets.main { resources.srcDir(generateFont0) }
// BdfTest and BdfToGlyphTableTest test the converter; the table reaches every test through main's resources
dependencies { testImplementation(fontgen.output) }

tasks.withType<Test>().configureEach {
    // @Timeout runs its test on a thread of its own, so that it can fail a test whose thread is BLOCKED on a monitor
    // (a lock-order cycle, plan §3.10); the same-thread default only interrupts, which frees no BLOCKED thread
    systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
    // A backstop for a hang no @Timeout covers
    timeout.set(Duration.ofMinutes(15))
}

tasks.test {
    // The validator's throughput measurement (28 §8.1) runs in benchTest, never in check
    useJUnitPlatform { excludeTags("bench") }
    // A longer or different fuzz run: -Popengpu.fuzz.cases=1000000 -Popengpu.fuzz.seed=7
    providers.gradleProperty("opengpu.fuzz.cases").orNull?.let { systemProperty("opengpu.fuzz.cases", it) }
    providers.gradleProperty("opengpu.fuzz.seed").orNull?.let { systemProperty("opengpu.fuzz.seed", it) }
    // A longer or different model run (S7): -Popengpu.model.cases=2000 -Popengpu.model.seed=11
    providers.gradleProperty("opengpu.model.cases").orNull?.let { systemProperty("opengpu.model.cases", it) }
    providers.gradleProperty("opengpu.model.seed").orNull?.let { systemProperty("opengpu.model.seed", it) }
}

tasks.register<Test>("benchTest") {
    group = "verification"
    description = "Measures the validator at the 65536-byte submit cap (28 §8.1); not part of check."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("bench") }
    // The report's absolute path, so that it lands in core's build directory whatever the working directory
    systemProperty(
        "opengpu.bench.report",
        layout.buildDirectory.file("reports/opengpu-bench/validator.txt").get().asFile.absolutePath,
    )
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

VerifyTestsRan.register(project, tasks.test, true)
