package io.github.mindbound.opengpu.client.render;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.render.NoRenderHost;
import io.github.mindbound.opengpu.render.Installation;

/**
 * {@link HostInstaller#choose}, with no GL context and no launched game (S12 plan P7.1 with amendments I11 and F6;
 * Q-S12-5 (a), Q-S12-7 (a)): the probes are the tests' own and the host factory records its calls. A
 * {@link GlRenderHost} does no GL work when it is made, so the factory makes real ones. No test installs anything in
 * {@code RenderHosts} (S11 amendments B1).
 */
class HostInstallerTest {

    private static final String RENDERER = "Test GL Renderer";

    private final List<String> info = new ArrayList<>();
    private final List<String> warn = new ArrayList<>();
    private final List<String> made = new ArrayList<>();

    /** Records the renderer it is given and makes a production host on a still clock. */
    private final Function<String, GlRenderHost> hosts = renderer -> {
        made.add(renderer);
        return GlRenderHost.production(warn::add, info::add, renderer, () -> 0L);
    };

    private static Supplier<GlCaps> probing(boolean angelicaLoaded, String angelicaVersion, boolean gles,
        boolean gl33) {
        return () -> new GlCaps(
            Collections.singletonMap("gl33", gl33),
            angelicaLoaded,
            angelicaVersion,
            gles,
            null,
            "Test Vendor",
            RENDERER,
            "4.6 Test");
    }

    private Installation choose(Supplier<GlCaps> probe) {
        return HostInstaller.choose(probe, hosts, info::add, warn::add);
    }

    // 21:59: GL suffices, so the GlRenderHost goes in, with the reason Lua sees if it fails closed later (H63)
    @Test
    void graphicsOnInstallsTheMadeHostWithTheFailedReasonAndTheRenderer() {
        Installation installation = choose(probing(true, "2.2.8", false, true));
        Assertions.assertTrue(installation.host() instanceof GlRenderHost, installation::toString);
        Assertions.assertEquals(HostInstaller.HOST_RENDERER_FAILED, installation.unavailableReason());
        Assertions.assertEquals(RENDERER, installation.hostRenderer());
        Assertions.assertEquals(Collections.singletonList(RENDERER), made, "made once, with the probed renderer");
        Assertions.assertTrue(
            installation.host()
                .available(),
            "a new GlRenderHost is available");
        Assertions.assertEquals(1, info.size(), info::toString);
        Assertions.assertTrue(
            info.get(0)
                .startsWith("OpenGPU GlCaps: gl33=true "),
            info::toString);
        Assertions.assertEquals(Collections.emptyList(), warn);
    }

    // 24:230: no graphics, so NoRenderHost with the reason; no :gl host is made, and the renderer goes in (Q-S12-7)
    @Test
    void aReasonInstallsNoRenderHostWithItAndTheRendererAndMakesNoHost() {
        Object[][] rows = { { probing(false, null, false, false), GlCaps.BELOW_GL33 },
            { probing(true, "2.2.36", true, true), GlCaps.GLES_UNTESTED },
            { probing(true, "2.1.49", false, true), GlCaps.ANGELICA_BELOW_FLOOR } };
        for (Object[] row : rows) {
            @SuppressWarnings("unchecked")
            Installation installation = choose((Supplier<GlCaps>) row[0]);
            Assertions.assertSame(NoRenderHost.INSTANCE, installation.host(), installation::toString);
            Assertions.assertEquals(row[1], installation.unavailableReason());
            Assertions.assertEquals(RENDERER, installation.hostRenderer());
        }
        Assertions.assertEquals(Collections.emptyList(), made, "no host is made without graphics");
        Assertions.assertEquals(3, info.size(), "one caps line per probe");
        Assertions.assertEquals(Collections.emptyList(), warn);
    }

    // I11: a probe that throws still installs, with the failed reason and no renderer, logged once with its trace
    @Test
    void aProbeThatThrowsInstallsNoRenderHostWithTheFailedReasonAndNoRenderer() {
        Installation installation = choose(() -> { throw new IllegalStateException("no context in this test"); });
        Assertions.assertSame(NoRenderHost.INSTANCE, installation.host());
        Assertions.assertEquals(HostInstaller.HOST_RENDERER_FAILED, installation.unavailableReason());
        Assertions.assertNull(installation.hostRenderer());
        Assertions.assertEquals(Collections.emptyList(), made);
        Assertions.assertEquals(Collections.emptyList(), info);
        Assertions.assertEquals(1, warn.size(), warn::toString);
        Assertions.assertTrue(
            warn.get(0)
                .startsWith("OpenGPU GlCaps: the probe failed"),
            warn::toString);
        Assertions.assertTrue(
            warn.get(0)
                .contains("IllegalStateException: no context in this test"),
            warn::toString);
    }

    // A field that lwjgl3ify's shim lacks throws NoSuchFieldError at the read site (14 §2.3): an Error, caught too
    @Test
    void aProbeThatThrowsALinkageErrorInstallsTheSame() {
        Installation installation = choose(() -> { throw new NoSuchFieldError("GL_ARB_sync"); });
        Assertions.assertSame(NoRenderHost.INSTANCE, installation.host());
        Assertions.assertEquals(HostInstaller.HOST_RENDERER_FAILED, installation.unavailableReason());
        Assertions.assertNull(installation.hostRenderer());
        Assertions.assertEquals(1, warn.size(), warn::toString);
        Assertions.assertTrue(
            warn.get(0)
                .contains("NoSuchFieldError: GL_ARB_sync"),
            warn::toString);
    }

    // D11: a host factory that throws, here an Error as a :gl class failing to link would, after a probe that found
    // graphics: NoRenderHost with the failed reason and the probed renderer, the caps line logged, the throw logged
    // once with its trace, and nothing rethrown
    @Test
    void aHostFactoryThatThrowsInstallsNoRenderHostWithTheFailedReasonAndTheRenderer() {
        Installation installation = HostInstaller.choose(probing(true, "2.2.8", false, true), renderer -> {
            made.add(renderer);
            throw new NoClassDefFoundError("io/github/mindbound/opengpu/gl/GlBackend");
        }, info::add, warn::add);
        Assertions.assertSame(NoRenderHost.INSTANCE, installation.host());
        Assertions.assertEquals(HostInstaller.HOST_RENDERER_FAILED, installation.unavailableReason());
        Assertions.assertEquals(RENDERER, installation.hostRenderer());
        Assertions.assertEquals(Collections.singletonList(RENDERER), made, "the factory was called once");
        Assertions.assertEquals(1, info.size(), info::toString);
        Assertions.assertTrue(
            info.get(0)
                .startsWith("OpenGPU GlCaps: gl33=true "),
            info::toString);
        Assertions.assertEquals(1, warn.size(), warn::toString);
        Assertions.assertTrue(
            warn.get(0)
                .startsWith(
                    "OpenGPU host: the host renderer could not be made, so graphics are unavailable"
                        + " (\"host renderer failed\"): "),
            warn::toString);
        Assertions.assertTrue(
            warn.get(0)
                .contains("NoClassDefFoundError: io/github/mindbound/opengpu/gl/GlBackend"),
            warn::toString);
    }

    // F14: the reason is labelled in the session's log line, so a healthy start does not read as a failure
    @Test
    void theInstallationsLogLineLabelsTheReason() {
        Installation installation = choose(probing(false, null, false, true));
        Assertions.assertEquals(
            "Installation[GlRenderHost, graphics when unavailable: host renderer failed, hostRenderer " + RENDERER
                + "]",
            installation.toString());
        Assertions.assertEquals(
            "Installation[NoRenderHost, graphics when unavailable: dedicated server, hostRenderer null]",
            Installation.dedicated()
                .toString());
    }
}
