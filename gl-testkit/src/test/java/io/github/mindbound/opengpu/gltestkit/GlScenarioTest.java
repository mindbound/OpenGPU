package io.github.mindbound.opengpu.gltestkit;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import io.github.mindbound.opengpu.gltestkit.host.RenderCoreTest;
import io.github.mindbound.opengpu.gltestkit.scenario.Scenarios;

/**
 * The GL scenarios of S8 plan §4.3 on the compatibility leg (glCompatTest), each run directly, as GlsmScenarioTest runs
 * the same classes through GlsmLoader on the GLSM legs. Around each scenario, the generic attribute values of
 * locations 1-7 (V1, 26:87) are set to values of their own and must come back unchanged: a check of test code on this
 * leg only, since {@code glVertexAttrib*} bypasses GLSM's cache (H66), and CallSetTest proves :gl never calls it.
 * Location 0 aliases the vertex position on a compatibility context, where its current value cannot be asked for
 * (Mesa raises GL_INVALID_OPERATION), so it is left out.
 */
@RenderCoreTest
class GlScenarioTest {

    private static final int ATTRIBUTES = 8;

    static List<String> scenarios() {
        return Scenarios.ALL;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    @Timeout(300)
    @SuppressWarnings("unchecked")
    void scenarioMatchesTheReference(String name, GlThread gl) throws Exception {
        Function<GlThread, List<String>> scenario = (Function<GlThread, List<String>>) Class.forName(name)
            .getConstructor()
            .newInstance();
        gl.run(GlScenarioTest::setAttributes);
        List<String> failures = new ArrayList<>();
        try {
            failures.addAll(scenario.apply(gl));
        } finally {
            failures.addAll(gl.call(GlScenarioTest::checkAttributes));
        }
        Assertions.assertEquals(Collections.emptyList(), failures, failures.size() + " failure(s)");
    }

    private static float value(int index, int component) {
        return 0.25f * index + 0.0625f * component + 1f;
    }

    private static void setAttributes() {
        for (int i = 1; i < ATTRIBUTES; i++) {
            GL20.glVertexAttrib4f(i, value(i, 0), value(i, 1), value(i, 2), value(i, 3));
        }
    }

    /** Each location's current value against what was set; then (0, 0, 0, 1) again, the context's default. */
    private static List<String> checkAttributes() {
        List<String> out = new ArrayList<>();
        FloatBuffer buffer = ByteBuffer.allocateDirect(64)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer();
        for (int i = 1; i < ATTRIBUTES; i++) {
            buffer.clear();
            GL20.glGetVertexAttrib(i, GL20.GL_CURRENT_VERTEX_ATTRIB, buffer);
            for (int c = 0; c < 4; c++) {
                if (buffer.get(c) != value(i, c)) {
                    out.add(
                        String.format(
                            Locale.ROOT,
                            "generic attribute %d component %d is %s, was set to %s (V1)",
                            i,
                            c,
                            buffer.get(c),
                            value(i, c)));
                }
            }
            GL20.glVertexAttrib4f(i, 0f, 0f, 0f, 1f);
        }
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            out.add(String.format(Locale.ROOT, "GL error 0x%04X reading the generic attributes", error));
        }
        return out;
    }
}
