// The render core (24:83): LWJGL 2 compile-only, no Minecraft, no OpenGlHelper (23 §2.1), on :core only in M0 (H52).
// Its context-bound tests live in :gl-testkit, which depends on it, so there is no cycle; :gl's own tests need no
// context. The root shades it unrelocated (dependencies.gradle, H51).
import java.time.Duration

plugins {
    id("opengpu.java8")
}

repositories {
    // RFG adds Mojang's repository to the root only, and 2.9.4-nightly-20150209 is not on Maven Central
    exclusiveContent {
        forRepository { maven("https://libraries.minecraft.net") { name = "Mojang" } }
        filter { includeGroup("org.lwjgl.lwjgl") }
    }
}

dependencies {
    api(project(":core"))
    // Not transitive: the POM adds natives for three systems and JInput; the game and :gl-testkit bring it at run time
    compileOnly("org.lwjgl.lwjgl:lwjgl:${OpenGpuVersions.LWJGL2}") { isTransitive = false }
    // CallSetTest loads :gl's classes and reads their bytecode; nothing calls GL in this task
    testImplementation("org.lwjgl.lwjgl:lwjgl:${OpenGpuVersions.LWJGL2}") { isTransitive = false }
    testImplementation(testFixtures(project(":core")))
    testImplementation("org.ow2.asm:asm:${OpenGpuVersions.ASM}")
    testImplementation("org.ow2.asm:asm-tree:${OpenGpuVersions.ASM}")
}

tasks.test {
    // A longer or different host model run (S8): -Popengpu.host.cases=2000 -Popengpu.host.seed=11
    providers.gradleProperty("opengpu.host.cases").orNull?.let { systemProperty("opengpu.host.cases", it) }
    providers.gradleProperty("opengpu.host.seed").orNull?.let { systemProperty("opengpu.host.seed", it) }
    // @Timeout runs its test on a thread of its own, as in :core (S7), so a blocked thread fails the test
    systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
    // The host's bound tests hold T3 payloads; no test relies on the default heap (S8 amendments E)
    maxHeapSize = "1g"
    // A backstop for a hang no @Timeout covers
    timeout.set(Duration.ofMinutes(15))
}

VerifyTestsRan.register(project, tasks.test, true)
