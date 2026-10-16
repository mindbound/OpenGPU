package io.github.mindbound.opengpu.gltestkit;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Every readback through the PBO ring is its frame when the copy comes while llvmpipe may still be drawing the frame
 * before it (S8's fix loop on llvmpipe; CopyWaitScenario), on the GLSM legs: the scenario, the host fixtures and
 * {@code :gl} loaded through a new {@link GlsmLoader} and transformed by GLSMRedirector (H30), as GlsmScenarioTest
 * loads the plan's rows, with GLSM's cache checked against the driver after every pump; and the backend resolved the
 * copy's {@code glFinish} from {@code GL_RENDERER}, read through GLSM: on, and run, on llvmpipe, also by each copy in
 * a pass that draws nothing after one that drew, off elsewhere.
 */
@GlsmTest
class GlsmCopyWaitTest {

    private static final String NAME = "io.github.mindbound.opengpu.gltestkit.scenario.CopyWaitScenario";
    private static final String HYGIENE = "io.github.mindbound.opengpu.gltestkit.host.GlHygiene";

    @Test
    @Timeout(60)
    @SuppressWarnings("unchecked")
    void everyReadbackIsItsFrameWhileTheFrameBeforeItIsStillDrawnUnderGlsm(GlThread gl) throws Exception {
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
