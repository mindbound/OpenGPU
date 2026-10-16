package io.github.mindbound.opengpu.gltestkit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.lwjgl.opengl.GL11;

import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.gl.GlBackend;
import io.github.mindbound.opengpu.gl.GlLedger;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.UploadProbe;
import io.github.mindbound.opengpu.gltestkit.host.RenderCoreTest;

/**
 * H63's error check (20:240) and R-3's drain in a dev build, on the compatibility leg: with assertions on, every pass
 * drains what other code left in the driver at its start and reports an error of its own at its end, through its
 * run-time handle on {@code GL11.glGetError} (D01; S8 review HY-1). glsmReleaseConfigTest and
 * glsmPackReleaseConfigTest cover a release build under GLSM's release config. The errors are provoked with
 * {@code glTexParameteri} and a pname that is none ({@code GL_INVALID_ENUM}).
 */
@RenderCoreTest
class GlPassErrorCheckTest {

    @Test
    @Timeout(60)
    void everyDevPassDrainsOthersErrorsAndReportsItsOwn(GlThread gl) throws Exception {
        final GlLedger ledger = new GlLedger();
        final GlBackend backend = new GlBackend(SyntheticGlyphs.TABLE, QuadMode.EXPANDED, ledger, UploadProbe.NONE);
        Assertions.assertTrue(
            GlBackend.class.getClassLoader()
                .loadClass("io.github.mindbound.opengpu.gl.PassState")
                .desiredAssertionStatus(),
            "the GL legs run with assertions, as a dev build");
        try {
            String[] seen = gl.call(() -> {
                String[] out = new String[6];
                for (int pass = 0; pass < 3; pass++) {
                    // Another mod's error before the pass: drained, not the pass's
                    provoke();
                    backend.beginPass();
                    out[2 * pass] = String.valueOf(
                        backend.passState()
                            .errorsDrainedAtEnter());
                    if (pass > 0) {
                        provoke();
                    }
                    try {
                        backend.endPass();
                        out[2 * pass + 1] = "no error";
                    } catch (IllegalStateException e) {
                        out[2 * pass + 1] = e.getMessage();
                    }
                }
                return out;
            });
            String reported = "GL error 0x0500 after OpenGPU's pass (20:240)";
            Assertions.assertEquals(0x0500, GL11.GL_INVALID_ENUM);
            Assertions.assertArrayEquals(
                new String[] { "1", "no error", "1", reported, "1", reported },
                seen,
                "errors drained at each pass's start and what its end reported");
        } finally {
            gl.call(() -> {
                backend.close();
                return null;
            });
        }
        Assertions.assertTrue(ledger.isEmpty(), "the ledger after close: " + ledger.rows());
    }

    /** GL_INVALID_ENUM: GL_TEXTURE_2D is no texture parameter. */
    private static void provoke() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_2D, 0);
    }
}
