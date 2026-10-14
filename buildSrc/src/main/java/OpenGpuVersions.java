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

    /**
     * Angelica's floor (25 §1.1): the root's compile target and -PwithAngelica default (dependencies.gradle), and the
     * first GLSM leg (gl-testkit's glsmFloorTest).
     */
    public static final String ANGELICA_FLOOR = "2.2.8";

    /** The Angelica of GTNH's pack, 2.9.0-RC-2 (25 §6): the second GLSM leg, glsmPackTest. */
    public static final String ANGELICA_PACK = "2.2.28";

    /**
     * GTNHLib for the GLSM legs: the version the root's dev runs load, from GTNHGradle 2.0.33's constraint
     * (UpdateableConstants.NEWEST_GTNH_LIB; the S3 runClient ran it with Angelica 2.2.8). Angelica declares 0.11.36.
     */
    public static final String GTNHLIB = "0.11.48";

    /**
     * The JvmDowngrader stubs GLSM calls: the version Angelica 2.2.x is downgraded with (its manifest's
     * JvmDowngrader-Version) and GTNHLib requests in game (gtnhlib_deps8.json).
     */
    public static final String JVMDG_API = "1.3.5";

    /** In GTNHLib's apiElements, which a plain test runtime does not get, and loaded by GTNHLib in game. */
    public static final String FASTUTIL = "8.5.18";
    public static final String JOML = "1.10.8";

    /**
     * What Minecraft 1.7.10 ships, which GLSM and GTNHLib take from it in game. log4j is the Maven Central 2.0-beta9
     * that Angelica's own GLSM tests use; Mojang's 2.0-beta9-fixed has no POM.
     */
    public static final String GUAVA_MC = "17.0";
    public static final String COMMONS_LANG3_MC = "3.3.2";
    public static final String LOG4J_MC = "2.0-beta9";

    private OpenGpuVersions() {}
}
