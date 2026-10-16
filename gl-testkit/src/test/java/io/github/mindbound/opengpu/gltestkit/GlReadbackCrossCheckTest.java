package io.github.mindbound.opengpu.gltestkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.device.ShadowOracle;
import io.github.mindbound.opengpu.core.fixtures.golden.GoldenCases;
import io.github.mindbound.opengpu.core.fixtures.golden.GoldenRunner;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gltestkit.host.GlHygiene;
import io.github.mindbound.opengpu.gltestkit.host.GlRecordRenderer;
import io.github.mindbound.opengpu.gltestkit.host.RenderCoreTest;
import io.github.mindbound.opengpu.gltestkit.scenario.GlGoldenTarget;

/**
 * S8 plan §4.4: for each golden's last frame, on both formats, the production readback (glGetTexImage into a ring
 * PBO, then glGetBufferSubData) gives the same bytes as A08's direct glGetTexImage of the same surface texture
 * (GlReads, test code only), and both equal the oracle. From the MC-like start, so both paths run under the hostile
 * pack store, which each must force (24:203). Compatibility leg only.
 */
@RenderCoreTest
class GlReadbackCrossCheckTest {

    @Test
    @Timeout(120)
    void pboPathEqualsTheDirectRead(GlThread gl) throws Exception {
        GlHygiene start = gl.call(GlHygiene::mcLikeStart);
        List<String> failures = new ArrayList<>();
        GlRecordRenderer renderer = new GlRecordRenderer(gl, SyntheticGlyphs.TABLE, QuadMode.EXPANDED)
            .checkHygiene(start, failures, "GlReadbackCrossCheckTest");
        int index8 = 0;
        int rgb565 = 0;
        try {
            int device = 0;
            for (GoldenCases.Case c : GoldenCases.load()
                .cases()) {
                SurfaceDesc surface = c.surface();
                GlGoldenTarget target = GlGoldenTarget.open(renderer, new DeviceKey(3, ++device), surface);
                GoldenRunner.Result result = GoldenRunner.run(c, target);
                if (!result.passed()) {
                    failures.add(result.report());
                }
                byte[] viaPbo = renderer.pixels(target.key());
                byte[] direct = renderer.surfaceDirect(target.key());
                byte[] oracle = target.oracle()
                    .pixels();
                add(failures, ShadowOracle.firstDifference(c.name() + ": PBO against A08", surface, direct, viaPbo));
                add(
                    failures,
                    ShadowOracle.firstDifference(c.name() + ": A08 against the oracle", surface, oracle, direct));
                target.release();
                if (surface.format == Formats.INDEX8) {
                    index8++;
                } else {
                    rgb565++;
                }
            }
        } finally {
            renderer.close();
            failures.addAll(gl.call(start::diff));
            gl.run(start::undo);
        }
        Assertions.assertTrue(index8 > 0 && rgb565 > 0, "goldens of both formats: " + index8 + " and " + rgb565);
        Assertions.assertEquals(Collections.emptyList(), failures, failures.size() + " failure(s)");
    }

    private static void add(List<String> failures, String difference) {
        if (difference != null) {
            failures.add(difference);
        }
    }
}
