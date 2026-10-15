// The engine core (24 §3.1): host-neutral, no Minecraft, OpenComputers or LWJGL; shaded into the mod jar unrelocated.
// Its test fixtures (28 §5.7: the tier-E oracle, the synthetic glyph table, the test encoder) ship in no jar; :gl-testkit
// and :ocelot-harness take them as testFixtures(project(":core")).
import java.time.Duration

plugins {
    id("opengpu.java8")
    `java-test-fixtures`
}

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
