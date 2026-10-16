package io.github.mindbound.opengpu.gltestkit;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Every readback through the PBO ring is its frame while another mod deletes textures through GLSM (S8's fix loop
 * after U6; ForeignTexturesScenario), on the GLSM legs: the scenario, the host fixtures and {@code :gl} loaded through
 * a new {@link GlsmLoader} and transformed by GLSMRedirector (H30), as GlsmScenarioTest loads the plan's rows, with
 * GLSM's cache checked against the driver after every pump; and each host resolved its readbacks' {@code glFinish}
 * from {@code GL_VENDOR}, read through GLSM, as {@code -Popengpu.gl.readbackFinish} says for the leg's, a line in the
 * log per host (Q-S8-3). With {@code never}, this is a driver's re-verification run (ReadbackFinish).
 */
@GlsmTest
class GlsmForeignTexturesTest {

    private static final String NAME = "io.github.mindbound.opengpu.gltestkit.scenario.ForeignTexturesScenario";
    private static final String HYGIENE = "io.github.mindbound.opengpu.gltestkit.host.GlHygiene";

    @Test
    @Timeout(120)
    @SuppressWarnings("unchecked")
    void everyReadbackIsItsFrameWhileGlsmDeletesTextures(GlThread gl) throws Exception {
        GlsmLoader loader = new GlsmLoader();
        Function<GlThread, List<String>> scenario = loader.instance(NAME, Function.class);
        Assertions.assertSame(
            loader,
            scenario.getClass()
                .getClassLoader(),
            NAME + " was not defined by GlsmLoader");
        loader.loadClass(HYGIENE)
            .getMethod("glsmCrossCheck", Callable.class)
            .invoke(null, new Callable<List<String>>() {

                @Override
                public List<String> call() {
                    return GlsmExtension.outOfSync();
                }
            });
        List<String> failures = scenario.apply(gl);
        Assertions.assertEquals(Collections.emptyList(), failures, failures.size() + " failure(s)");
    }
}
