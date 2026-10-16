package io.github.mindbound.opengpu.gltestkit;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.mindbound.opengpu.gltestkit.scenario.Scenarios;

/**
 * The GL scenarios of S8 plan §4.3 on the GLSM legs (glsmFloorTest on 2.2.8, glsmPackTest on 2.2.28): each scenario
 * class is loaded through a new {@link GlsmLoader}, so it, the host fixtures and every {@code :gl} class it reaches are
 * transformed by GLSMRedirector as in game (H30), and run on the GL thread under GLSM. Only JDK and {@code :core} types
 * cross: the scenario is a {@code Function<GlThread, List<String>>}. The scenarios' hygiene check gets GLSM's cache
 * against the driver ({@link GlsmExtension#outOfSync}) after every pass, through the loader's own GlHygiene.
 */
@GlsmTest
class GlsmScenarioTest {

    private static final String HOST = "io.github.mindbound.opengpu.gltestkit.host.";
    private static final String HYGIENE = HOST + "GlHygiene";

    static List<String> scenarios() {
        return Scenarios.ALL;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    @Timeout(300)
    @SuppressWarnings("unchecked")
    void scenarioMatchesTheReferenceUnderGlsm(String name, GlThread gl) throws Exception {
        Assertions.assertTrue(GlsmLoader.owns(name), name + " is not in a package GlsmLoader owns");
        Assertions.assertTrue(GlsmLoader.owns(HYGIENE), "GlsmLoader does not own gltestkit.host");
        GlsmLoader loader = new GlsmLoader();
        Function<GlThread, List<String>> scenario = loader.instance(name, Function.class);
        Assertions.assertSame(
            loader,
            scenario.getClass()
                .getClassLoader(),
            name + " was not defined by GlsmLoader");
        Class<?> hygiene = loader.loadClass(HYGIENE);
        Assertions.assertSame(loader, hygiene.getClassLoader(), "GlHygiene was not defined by GlsmLoader");
        hygiene.getMethod("glsmCrossCheck", Callable.class)
            .invoke(null, new Callable<List<String>>() {

                @Override
                public List<String> call() {
                    return GlsmExtension.outOfSync();
                }
            });
        List<String> failures = scenario.apply(gl);
        // The classes that ran are the transformed ones: the pass's bracket and the hygiene start (risk 9)
        Assertions.assertTrue(
            loader.transform("io.github.mindbound.opengpu.gl.PassState").changed,
            "GLSMRedirector left PassState unchanged");
        Assertions.assertTrue(loader.transform(HYGIENE).changed, "GLSMRedirector left GlHygiene unchanged");
        Assertions.assertEquals(Collections.emptyList(), failures, failures.size() + " failure(s)");
    }
}
