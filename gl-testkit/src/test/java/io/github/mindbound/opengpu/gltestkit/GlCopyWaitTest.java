package io.github.mindbound.opengpu.gltestkit;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.gltestkit.host.RenderCoreTest;
import io.github.mindbound.opengpu.gltestkit.scenario.CopyWaitScenario;

/**
 * Every readback through the PBO ring is its frame when the copy comes while llvmpipe may still be drawing the frame
 * before it (S8's fix loop on llvmpipe; CopyWaitScenario), on the compatibility leg; and the backend resolved the
 * copy's {@code glFinish} from the leg's {@code GL_RENDERER}: on, and run, on llvmpipe, also by each copy in a pass
 * that draws nothing after one that drew, off elsewhere. Without that wait, llvmpipe hands nearly half of the first
 * device's readbacks the frame before theirs.
 */
@RenderCoreTest
class GlCopyWaitTest {

    @Test
    @Timeout(60)
    void everyReadbackIsItsFrameWhileTheFrameBeforeItIsStillDrawn(GlThread gl) {
        List<String> failures = new CopyWaitScenario().apply(gl);
        Assertions.assertEquals(Collections.emptyList(), failures, failures.size() + " failure(s)");
    }
}
