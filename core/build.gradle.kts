// The engine core (24 §3.1): host-neutral, no Minecraft, OpenComputers or LWJGL; shaded into the mod jar unrelocated.
// Its test fixtures (28 §5.7: the tier-E oracle, the synthetic glyph table, the test encoder) ship in no jar; :gl-testkit
// and :ocelot-harness take them as testFixtures(project(":core")).
plugins {
    id("opengpu.java8")
    `java-test-fixtures`
}

tasks.test {
    // The validator's throughput measurement (28 §8.1) runs in benchTest, never in check
    useJUnitPlatform { excludeTags("bench") }
    // A longer or different fuzz run: -Popengpu.fuzz.cases=1000000 -Popengpu.fuzz.seed=7
    providers.gradleProperty("opengpu.fuzz.cases").orNull?.let { systemProperty("opengpu.fuzz.cases", it) }
    providers.gradleProperty("opengpu.fuzz.seed").orNull?.let { systemProperty("opengpu.fuzz.seed", it) }
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
