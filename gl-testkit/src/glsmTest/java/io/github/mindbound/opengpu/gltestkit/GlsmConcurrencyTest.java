package io.github.mindbound.opengpu.gltestkit;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Scenario 17 on the GLSM legs under its own {@code @Timeout(60)} (S8 plan §4.3, §0: every threaded test), loaded
 * through a new {@link GlsmLoader} as GlsmScenarioTest loads every scenario under its rows' wider timeout: the
 * scenario, the host fixtures and {@code :gl} transformed by GLSMRedirector (H30), with GLSM's cache checked against
 * the driver after every pump. Only the GL thread calls GL; the workers, the server thread and the fold pool reach
 * {@code Device} and {@code GlHostCore}'s offers.
 */
@GlsmTest
class GlsmConcurrencyTest {

    private static final String NAME = "io.github.mindbound.opengpu.gltestkit.scenario.ConcurrencyScenario";
    private static final String HYGIENE = "io.github.mindbound.opengpu.gltestkit.host.GlHygiene";

    @Test
    @Timeout(60)
    @SuppressWarnings("unchecked")
    void theThreadsOfTheGameAgreeWithTheOracleUnderGlsm(GlThread gl) throws Exception {
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
