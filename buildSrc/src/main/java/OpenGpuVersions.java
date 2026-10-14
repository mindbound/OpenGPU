/**
 * Library pins shared by the root's scripts (addon.gradle.kts) and the subprojects, so each version is written once.
 */
public final class OpenGpuVersions {

    /** The newest JUnit 5 (2026-04-26); Jupiter 5.x runs on Java 8, JUnit 6 needs Java 17. */
    public static final String JUNIT = "5.14.4";

    /** One ASM for every artifact (05 §1): the version lwjgl3ify and ocelot-brain use. */
    public static final String ASM = "9.10.1";

    /** What Minecraft 1.7.10 ships; on Mojang's repository, not on Maven Central. */
    public static final String LWJGL2 = "2.9.4-nightly-20150209";

    /**
     * GTNHGradle 2.0.33's jabel setup (ToolchainModule), mirrored by opengpu.java8; keep these equal to GTNHGradle's.
     */
    public static final String JABEL = "1.0.2-GTNH";
    public static final String JNA_PLATFORM = "5.19.1";

    private OpenGpuVersions() {}
}
