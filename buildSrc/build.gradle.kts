// The build's own Gradle code: the subprojects' opengpu.java8 convention (src/main/groovy), and the shared pins and the
// tests-ran guard (src/main/java). Not kotlin-dsl: it compiles with the Kotlin Gradle plugin, and the build already
// holds GTNHGradle's KGP 2.2.21, whose compiler cannot read Gradle 9.7.1's Kotlin 2.4.0 classes.
plugins {
    `groovy-gradle-plugin`
}
