package io.github.mindbound.opengpu.client.render;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link GlCaps}' decision and log line (24:125, :230; 25 §1.1; R-7, R-13; S12 plan P6 with amendments F8 and F11;
 * Q-S12-5 (a)), with no GL context and no launched game: the probe is never run here (S12 plan P14.1). The gate's
 * rows are FML's own range check, as {@code OcFloorRangeTest} runs FML's for OC's floor.
 */
class GlCapsTest {

    private static boolean supported(String version) {
        return GlCaps.decide(true, version, false, true) == null;
    }

    /** Caps as the probe would make them, with a few of the logged flags. */
    private static GlCaps caps(boolean angelicaLoaded, String angelicaVersion, boolean gles, boolean gl33) {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        flags.put("gl30", true);
        flags.put("gl33", gl33);
        flags.put("core", false);
        return new GlCaps(
            flags,
            angelicaLoaded,
            angelicaVersion,
            gles,
            null,
            "Test Vendor",
            "Test GL Renderer",
            "4.6 Test");
    }

    // 24:125's strings and the two S12 adds (Q-S12-5 (a)); the reasons are API once 24 §3.3's strings are (RM:258)
    @Test
    void theReasonsAreTheDocumentedStrings() {
        Assertions.assertEquals("host GPU below GL 3.3", GlCaps.BELOW_GL33);
        Assertions.assertEquals("GLES profile untested", GlCaps.GLES_UNTESTED);
        Assertions.assertEquals("Angelica below 2.2.8", GlCaps.ANGELICA_BELOW_FLOOR);
        Assertions.assertEquals("host renderer failed", HostInstaller.HOST_RENDERER_FAILED);
        Assertions.assertEquals("[2.2.8,)", GlCaps.ANGELICA_RANGE);
    }

    // 25:47: the vanilla leg needs a compatibility context of GL 3.3 or more
    @Test
    void withoutAngelicaGraphicsAreOnWithGl33() {
        Assertions.assertNull(GlCaps.decide(false, null, false, true));
    }

    @Test
    void withoutAngelicaAContextBelowGl33HasNoGraphics() {
        Assertions.assertEquals(GlCaps.BELOW_GL33, GlCaps.decide(false, null, false, false));
    }

    // 25 §1.1: the floor and every later release, with no ceiling, and a dirty or git-describe build of one
    @ParameterizedTest
    @ValueSource(strings = { "2.2.8", "2.2.36", "2.2.100", "2.2.8-dirty", "2.2.9-3-g6d8cbf9" })
    void angelicaAtOrAboveTheFloorIsSupported(String version) {
        Assertions.assertTrue(GlCaps.versionParses(version), version);
        Assertions.assertTrue(supported(version), version);
    }

    // Every 1.x and 2.1.x and the dropped 2.1.14-2.2.7 band (25 §1.1), and a short "2.2", which FML puts below 2.2.8
    @ParameterizedTest
    @ValueSource(strings = { "2.2.7", "2.2.7-dirty", "2.1.14", "2.2", "1.0.0-beta66b" })
    void angelicaBelowTheFloorHasNoGraphicsWithItsReason(String version) {
        Assertions.assertTrue(GlCaps.versionParses(version), version);
        Assertions.assertEquals(GlCaps.ANGELICA_BELOW_FLOOR, GlCaps.decide(true, version, false, true), version);
    }

    // 25:51: a version that does not parse counts as supported and is logged; FML's own range would put it below
    @ParameterizedTest
    @ValueSource(strings = { "@VERSION@", "", "${version}", "  " })
    void anAngelicaVersionThatDoesNotParseIsSupportedWithAWarning(String version) {
        Assertions.assertFalse(GlCaps.versionParses(version), version);
        Assertions.assertTrue(supported(version), version);
        Assertions.assertTrue(
            caps(true, version, false, true).logLine()
                .contains(" (does not parse, treated as supported) "),
            version);
    }

    @Test
    void aNullAngelicaVersionDoesNotParseAndIsSupported() {
        Assertions.assertFalse(GlCaps.versionParses(null));
        Assertions.assertNull(GlCaps.decide(true, null, false, true));
    }

    // The version is trimmed before both the shape test and FML's compare
    @Test
    void anAngelicaVersionWithSpacesAroundItIsJudgedTrimmed() {
        Assertions.assertTrue(supported(" 2.2.8 "));
        Assertions.assertEquals(GlCaps.ANGELICA_BELOW_FLOOR, GlCaps.decide(true, " 2.2.7\t", false, true));
    }

    // R-7: GLSM on a GLES context
    @Test
    void aGlesContextUnderASupportedAngelicaHasNoGraphics() {
        Assertions.assertEquals(GlCaps.GLES_UNTESTED, GlCaps.decide(true, "2.2.36", true, true));
    }

    // Q-S12-5 (a)'s order: the gate, then GLES, then GL 3.3, the first that applies
    @Test
    void anOldAngelicaOnAGlesContextBelowGl33GivesTheAngelicaReason() {
        Assertions.assertEquals(GlCaps.ANGELICA_BELOW_FLOOR, GlCaps.decide(true, "2.2.7", true, false));
    }

    @Test
    void aGlesContextBelowGl33GivesTheGlesReason() {
        Assertions.assertEquals(GlCaps.GLES_UNTESTED, GlCaps.decide(true, "2.2.8", true, false));
    }

    @Test
    void anOldAngelicaBelowGl33GivesTheAngelicaReason() {
        Assertions.assertEquals(GlCaps.ANGELICA_BELOW_FLOOR, GlCaps.decide(true, "2.1.14", false, false));
    }

    // Only GL 3.3, the gate and GLES decide (14:110): the other logged flags never do
    @Test
    void theCapsTakeTheirReasonFromGl33TheGateAndGlesOnly() {
        Assertions.assertNull(caps(false, null, false, true).reason());
        Assertions.assertEquals(GlCaps.BELOW_GL33, caps(false, null, false, false).reason());
        Assertions.assertEquals(GlCaps.ANGELICA_BELOW_FLOOR, caps(true, "2.2.7", false, true).reason());
        Assertions.assertEquals(GlCaps.GLES_UNTESTED, caps(true, "2.2.8", true, true).reason());
        Assertions.assertEquals("Test GL Renderer", caps(false, null, false, true).renderer());
    }

    // 14:110 and S12 plan P6.6: one line with every logged flag in order, the gate, the strings and graphics
    @Test
    void theLogLineNamesEveryFlagTheGateTheStringsAndGraphics() {
        Assertions.assertEquals(
            "OpenGPU GlCaps: gl30=true gl33=true core=false angelica=absent gles=false sdlGpu=unset"
                + " vendor=\"Test Vendor\" renderer=\"Test GL Renderer\" version=\"4.6 Test\" graphics=available",
            caps(false, null, false, true).logLine());
        String below = caps(true, "2.2.7", false, true).logLine();
        Assertions.assertTrue(below.contains(" angelica=2.2.7 gate=below gles=false "), below);
        Assertions.assertTrue(below.endsWith(" graphics=\"Angelica below 2.2.8\""), below);
        String floor = caps(true, "2.2.8", false, true).logLine();
        Assertions.assertTrue(floor.contains(" angelica=2.2.8 gate=supported "), floor);
        Assertions.assertTrue(floor.endsWith(" graphics=available"), floor);
        Assertions.assertTrue(
            caps(true, "@VERSION@", false, true).logLine()
                .contains(" angelica=\"@VERSION@\" (does not parse, treated as supported) gles=false "));
    }
}
