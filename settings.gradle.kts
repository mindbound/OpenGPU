
pluginManagement {
    repositories {
        maven {
            // RetroFuturaGradle
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
            mavenContent {
                includeGroup("com.gtnewhorizons")
                includeGroupByRegex("com\\.gtnewhorizons\\..+")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}

plugins {
    id("com.gtnewhorizons.gtnhsettingsconvention") version("2.0.33")
}

// Subprojects (24 §3.1) on buildSrc's opengpu.java8 convention; GTNHGradle configures the root only. :gl joined with
// M0 step S8 and :ocelot-harness with S10; :lang, :jit and :stream join with their steps. Keep the names flat, each with
// a build file whose plugins {} block is not empty: that request is what brings the repositories for GTNHGradle's
// per-project Spotless. :compute-spike, M0 step S14's compute spike, is test-only and ships nothing: it is no 24 §3.1
// module, and it stays until M3's :jit covers its tests.
include("core")
include("gl")
include("gl-testkit")
include("ocelot-harness")
include("compute-spike")
