import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
     * 25 §6's CI-only jar, the owner's release during the research: only the linkage and FAIL runs (S9), gl-testkit's
     * glsmLinkageTest.
     */
    public static final String ANGELICA_CI_ONLY = "2.2.21";

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

    /**
     * Where :ocelot-harness fetches ocelot-brain's sources at build time (RM:209), tried in this order, up to three
     * attempts each: the owner's import of the repository (2026-10-24), then upstream on gitlab.com. Either serves the
     * same commit, and the check does not depend on which one did.
     */
    public static final List<String> BRAIN_REPOSITORIES = Collections.unmodifiableList(
        Arrays.asList(
            "https://github.com/mindbound/ocelot-brain.git",
            "https://gitlab.com/cc-ru/ocelot/ocelot-brain.git"));

    /** ocelot-brain's pin (RM:209), version 0.24.2, and its root tree, the files every build checks against. */
    public static final String BRAIN_COMMIT = "e98a5b2cd74a8372e23d8a953e3f4da252f71082";
    public static final String BRAIN_TREE = "5f21314ee10a3aebf81876193e4ffd0c8c973493";
    /** The blobs BRAIN_COMMIT's tree lists. */
    public static final int BRAIN_FILES = 605;

    /** ocelot-brain's build.sbt:6, the Scala that compiles it. */
    public static final String BRAIN_SCALA = "2.13.11";

    /** Gradle 9.7.1's default Zinc, written out so that a Gradle upgrade cannot move it unseen. */
    public static final String ZINC = "1.12.0";

    /** ocelot-brain's Maven Central dependencies (build.sbt:9-17); ASM is {@link #ASM}. */
    public static final String BRAIN_LOG4J = "2.26.1";
    public static final String BRAIN_GUAVA = "33.7.1-jre";
    public static final String BRAIN_COMMONS_CODEC = "1.22.1";
    public static final String BRAIN_CONFIG = "1.4.9";
    public static final String BRAIN_COMMONS_LANG3 = "3.20.0";
    public static final String BRAIN_COMMONS_TEXT = "1.15.0";
    public static final String BRAIN_COMMONS_IO = "2.22.0";

    /**
     * ocelot-brain's URL-only jars (build.sbt:18-20), group li.cil.repack.com.naef on https://asie.pl/javadeps/, each
     * downloaded at build time and checked by its whole sha256 (the owner's answer to Q-S10-1, 2026-10-24).
     */
    public static final String OC_LUAJ = "20220907.1";
    public static final String OC_LUAJ_SHA256 = "d80ce740d71953c917b168b822932c33aafef8ab737b193623f9c87254c1149f";
    public static final String OC_JNLUA = "20230530.0";
    public static final String OC_JNLUA_SHA256 = "41abce30160a8014abd34e885f0f073fb35c8d9e2f78d230b7b9e64a79ccedbb";
    public static final String OC_JNLUA_NATIVES = "20220928.1";
    public static final String OC_JNLUA_NATIVES_SHA256 = "43979d288fe06a8323fdf89e05bfec0448c64f026762b43ea1fa65bda3768a17";

    /** The OC-LuaJIT commit the local ocelot leg's adapter must match and its native must not predate (RM:221). */
    public static final String OCLUAJIT_FLOOR = "a98787b91d53f3b43374f8a8ba673f15135cd819";

    private OpenGpuVersions() {}
}
