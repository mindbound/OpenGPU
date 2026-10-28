// M0 step S14's compute spike (RM:42, RM:69; 24:248; 09:130), test-only: a small IR to V1_8 bytecode through ASM in a
// child loader, the same output on Java 8 and Java 21. It ships nothing and is no 24 §3.1 module (S14 decisions D1,
// Q-S14-1 (a)): no shipped project depends on it, assemble builds nothing here, and it stays until M3's :jit covers its
// tests. spikeJar packs main with ASM relocated as the mod jar would relocate it, and both gating legs run from that
// jar, test on the convention's Java 8 launcher and test21 on a Java 21 one; verifySpikeSameOutput, in check, holds
// their digests equal (Q-S14-2 (a); S14 plan P5.3, P8.2-P8.3). spikeBench8 and spikeBench21 time it, by name only.
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.time.Duration
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("opengpu.java8")
}

dependencies {
    // The emitter needs ClassWriter and its visitors alone; CheckClassAdapter, with asm-tree and asm-analysis, is the
    // tests' (S14 plan P4.1)
    implementation("org.ow2.asm:asm:${OpenGpuVersions.ASM}")
    testImplementation("org.ow2.asm:asm-util:${OpenGpuVersions.ASM}")
}

// main's jar ships nowhere, so assemble builds nothing here, and assemble's dry run gains only this task (guard 3;
// ocelot-harness/build.gradle.kts:112-114)
tasks.named("assemble") { setDependsOn(emptyList<Any>()) }

// main with ASM relocated under the prefix the root's auto-relocation gives a shaded library
// (DevJarLayoutTest.java:141). Shadow 9.6.1 sets the classifier, the configurations and the module-info.class
// exclusions only on the plugin's own shadowJar (ShadowJar$Companion), and no Shadow plugin is applied here, so a
// registered ShadowJar sets them itself (S14 amendments I1). No shipped jar and no configuration takes it: the two legs
// and the two bench tasks alone read it
val spikeRuntime = configurations.runtimeClasspath
val spikeJar = tasks.register<ShadowJar>("spikeJar") {
    group = "build"
    description = "Packs the spike's classes with ASM ${OpenGpuVersions.ASM} relocated, for the gating legs."
    from(sourceSets.main.map { it.output })
    configurations.set(listOf(spikeRuntime.get()))
    relocate("org.objectweb.asm", "io.github.mindbound.opengpu.shadow.org.objectweb.asm")
    archiveClassifier.set("spike")
    exclude("module-info.class", "META-INF/versions/**/module-info.class")
}

tasks.withType<Test>().configureEach {
    // @Timeout runs its test on a thread of its own, as in :core and :gl-testkit, so a blocked thread fails the test
    systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
    // No test relies on the default heap
    maxHeapSize = "1g"
    // A backstop for a hang no @Timeout covers
    timeout.set(Duration.ofMinutes(10))
}

/**
 * The test classes, then spikeJar's jar, then testRuntimeClasspath, without main's classes, so the spike runs its
 * relocated ASM from the jar beside the tests' unrelocated one.
 */
fun Test.fromSpikeJar() {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = files(
        sourceSets.test.get().output,
        spikeJar.flatMap { it.archiveFile },
        configurations.testRuntimeClasspath,
    )
}

/**
 * A gating leg (S14 plan P5.3, P8.2-P8.3), from spikeJar's jar. The leg gets its expected java.specification.version,
 * which LegTest holds its JVM to, so a launcher that lost to the convention's Java 8 fails instead of passing unseen,
 * and spikeJar's path with the plain jar's, which RelocationTest tells apart. Its digests.txt,
 * build/reports/compute-spike/<task>/, is an output, so the build cache restores it with the results, and it is deleted
 * before each run, so a run that records nothing leaves no stale file to compare.
 */
fun Test.spikeLeg(expectJava: String) {
    val jar = spikeJar.flatMap { it.archiveFile }
    fromSpikeJar()
    useJUnitPlatform { excludeTags("bench") }
    systemProperty("opengpu.spike.expectJava", expectJava)
    val reportDir = layout.buildDirectory.dir("reports/compute-spike/$name")
    outputs.dir(reportDir).withPropertyName("spikeDigests")
    val digests = reportDir.get().file("digests.txt").asFile
    val plainJar = tasks.jar.get().archiveFile.get().asFile.absolutePath
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dopengpu.spike.jar=" + jar.get().asFile.absolutePath,
                "-Dopengpu.spike.plainJar=$plainJar",
                "-Dopengpu.spike.digests=" + digests.absolutePath,
            )
        },
    )
    doFirst { digests.delete() }
}

tasks.test {
    spikeLeg("1.8")
}

// The build's one gating Test task on a launcher other than Java 8 (S14 plan P15 item 4; spikeBench21, run by name
// only, is the other one): locally Gradle's provisioned Zulu 21.0.12.1, which its toolchain detection finds offline,
// and on CI whatever 21 Gradle finds there
val test21 = tasks.register<Test>("test21") {
    group = "verification"
    description = "Runs the spike's tests from spikeJar's jar in a Java 21 JVM, as test does in a Java 8 one."
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    spikeLeg("21")
}

// Both gate in check, and a leg that ran no test or skipped one fails the build
VerifyTestsRan.register(project, tasks.test, false)
VerifyTestsRan.register(project, test21, false)

// RM:69's "same output on Java 8 and 21" (Q-S14-2 (a)), in verifyClientGlTestClasses' style
// (addon.gradle.kts:173-212): it fails unless both legs ran on 1.8 and 21, each recorded exactly P8.2's digests, and
// the two legs' digests are equal. The names are pinned, so a digest both legs stop recording fails here instead of
// leaving the rest to compare (S14 review tests-1): k3 alone holds StrictMath's sin and log1p, whose code differs
// between JDK 8 and 21, and a new kernel's digest joins the list on purpose. Its one line names both JVMs, so CI's log
// shows which JDK 21 CI used and gives the cross-OS record (S14 plan P8.3)
val verifySpikeSameOutput = tasks.register("verifySpikeSameOutput") {
    group = "verification"
    description = "Fails unless test and test21 ran on Java 8 and Java 21 and wrote the same digests."
    dependsOn(tasks.test, test21)
    val expected = linkedMapOf("test" to "1.8", "test21" to "21")
    val names = sortedSetOf("digest.k1", "digest.k2", "digest.k3")
    val files = expected.mapValues { (leg, _) ->
        layout.buildDirectory.file("reports/compute-spike/$leg/digests.txt").get().asFile
    }
    doLast {
        val problems = mutableListOf<String>()
        val legs = files.mapValues { (leg, file) ->
            if (!file.isFile) {
                problems += "$leg wrote no $file"
                emptyMap()
            } else {
                val values = file.readLines().filter { it.isNotBlank() }
                    .associate { it.substringBefore('=') to it.substringAfter('=') }
                val java = values["java.specification.version"]
                if (java != expected.getValue(leg)) {
                    problems += "$leg ran on Java $java, not ${expected.getValue(leg)}"
                }
                values
            }
        }
        val digests = legs.mapValues { (_, values) -> values.filterKeys { it.startsWith("digest.") } }
        digests.filterValues { it.keys != names }.forEach { (leg, values) ->
            problems += "$leg recorded ${values.keys.sorted()}, not $names"
        }
        if (digests.values.all { it.keys == names } && digests.values.distinct().size != 1) {
            problems += "the legs' digests differ: $digests"
        }
        if (problems.isNotEmpty()) {
            throw GradleException(problems.joinToString("; "))
        }
        val jvms = legs.entries.joinToString("; ") { (leg, values) ->
            "$leg java.version=${values["java.version"]}, java.vendor=${values["java.vendor"]}, " +
                "java.home=${values["java.home"]}"
        }
        val same = digests.getValue("test").entries.joinToString(", ") { (key, value) -> "$key=$value" }
        logger.lifecycle("[opengpu-spike] same output on Java 8 and 21: $same; $jvms")
    }
}
tasks.named("check") { dependsOn(verifySpikeSameOutput) }

// The spike's speed (S14 plan P9.1-P9.3; RM:69's 10 ns/element for K1), by name only, as :core:benchTest
// (core/build.gradle.kts:60-74): never in check and never verified as run, never up to date or cached, with assertions
// off. Each run is one fresh JVM from spikeJar's jar; the main session judges the gate on the owner's CPU from five
// runs per JDK. K3, sin's cost and sqrt's are recorded after K1 and K2 (P9.1, P4.5). The report is
// build/reports/compute-spike/<task>/bench.txt
fun Test.spikeBench(java: Int) {
    group = "verification"
    description = "Times K1 and K2 on Java $java, then K3, sin and sqrt (S14 plan P9.1-P9.2); not part of check."
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(java)) })
    fromSpikeJar()
    useJUnitPlatform { includeTags("bench") }
    enableAssertions = false
    systemProperty(
        "opengpu.bench.report",
        layout.buildDirectory.file("reports/compute-spike/$name/bench.txt").get().asFile.absolutePath,
    )
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

tasks.register<Test>("spikeBench8") { spikeBench(8) }
tasks.register<Test>("spikeBench21") { spikeBench(21) }
