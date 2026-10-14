package io.github.mindbound.opengpu;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.versioning.ArtifactVersion;
import cpw.mods.fml.common.versioning.DefaultArtifactVersion;

/**
 * The OC floor as FML enforces it (decision 14, docs/research/24-architecture-gl-first.md; roadmap M0, OC floor check):
 * the dependency string of {@code @Mod}, parsed by FML's own Loader.computeDependencies (Loader.java:655-726) and
 * matched as Loader.sortModList matches a loaded mod (Loader.java:246-253), against the version FMLModContainer reports
 * (FMLModContainer.java:542-548).
 */
class OcFloorRangeTest {

    private static ArtifactVersion openComputers;

    @BeforeAll
    static void parseTheDependencyStringAsFmlDoes() throws ReflectiveOperationException {
        // A class literal does not initialise OpenGPU, and @Mod has RUNTIME retention
        String dependencies = OpenGPU.class.getAnnotation(Mod.class)
            .dependencies();
        Set<ArtifactVersion> required = new HashSet<>();
        List<ArtifactVersion> after = new ArrayList<>();
        List<ArtifactVersion> before = new ArrayList<>();
        bareLoader().computeDependencies(dependencies, required, after, before);
        Assertions.assertEquals(1, required.size(), dependencies);
        Assertions.assertEquals(
            "OpenComputers",
            required.iterator()
                .next()
                .getLabel());
        Assertions.assertTrue(before.isEmpty(), dependencies);
        Assertions.assertEquals(1, after.size(), dependencies);
        openComputers = after.get(0);
        Assertions.assertEquals("OpenComputers", openComputers.getLabel());
    }

    /**
     * Loader's constructor needs a launched game (Loader.java:195-206), but computeDependencies reads only Loader's
     * static splitters, so an instance made without it serves. Unsafe is reached reflectively, outside --release 8.
     */
    private static Loader bareLoader() throws ReflectiveOperationException {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return (Loader) unsafeClass.getMethod("allocateInstance", Class.class)
            .invoke(theUnsafe.get(null), Loader.class);
    }

    private static boolean accepts(String version) {
        return openComputers.containsVersion(new DefaultArtifactVersion("OpenComputers", version));
    }

    @ParameterizedTest
    @ValueSource(strings = { "1.12.64-GTNH", "1.12.65-GTNH", "1.12.100-GTNH", "1.13.0-GTNH" })
    void acceptsTheFloorAndLaterReleases(String version) {
        Assertions.assertTrue(accepts(version), version);
    }

    // A plain 1.12.65 or 1.12.64-GTNH-pre also passes: the range is a numeric floor, not a check for -GTNH
    @ParameterizedTest
    @ValueSource(strings = { "1.12.63-GTNH", "1.12.55-GTNH", "1.11.20-GTNH", "1.12.64" })
    void rejectsEarlierReleasesAndThePlainFloor(String version) {
        Assertions.assertFalse(accepts(version), version);
    }
}
