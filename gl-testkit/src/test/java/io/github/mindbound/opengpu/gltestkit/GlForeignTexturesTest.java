package io.github.mindbound.opengpu.gltestkit;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.gltestkit.host.RenderCoreTest;
import io.github.mindbound.opengpu.gltestkit.scenario.ForeignTexturesScenario;

/**
 * Every readback through the PBO ring is its frame while another mod deletes textures as GLSM does (S8's fix loop
 * after U6; ForeignTexturesScenario), on the compatibility leg, with the deletes' shrink done by hand; and each host
 * resolved its readbacks' {@code glFinish} as {@code -Popengpu.gl.readbackFinish} says for the leg's
 * {@code GL_VENDOR}, a line in the log per host (Q-S8-3). With {@code never}, this is a driver's re-verification run
 * (ReadbackFinish).
 */
@RenderCoreTest
class GlForeignTexturesTest {

    @Test
    @Timeout(120)
    void everyReadbackIsItsFrameWhileTexturesChangeStorage(GlThread gl) {
        List<String> failures = new ForeignTexturesScenario().apply(gl);
        Assertions.assertEquals(Collections.emptyList(), failures, failures.size() + " failure(s)");
    }
}
