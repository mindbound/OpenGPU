package io.github.mindbound.opengpu.gltestkit;

import java.lang.reflect.InvocationTargetException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.lwjgl.opengl.GL11;

import com.gtnewhorizons.angelica.glsm.GLStateManager;

import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.font.GlyphTable;

/**
 * H63's error check (20:240) and R-3's drain under the configuration a GTNH release client runs (S8 review HY-1):
 * GLSM's error checks off, as Angelica's {@code disableErrorChecks} sets them in an obfuscated client
 * ({@code -Dopengpu.glsm.noErrorChecks=true}), and a release build of OpenGPU, without Java assertions, so PassState
 * checks after the first pass only. Only glsmReleaseConfigTest and glsmPackReleaseConfigTest run it, on the floor's
 * GLSM (2.2.8, where the redirector maps no {@code Util} call) and the pack's (2.2.28, where it maps
 * {@code Util.checkGLError} to GLSM's own check); GLSM is a JVM singleton, so the other legs, which keep GLSM's checks
 * on, exclude its tag. PassState reads the driver's error state through its run-time handle on {@code GL11.glGetError}
 * (D01). The GlBackend is loaded through a new
 * {@link GlsmLoader}, so its GL calls reach GLSM as in game; this class's own GL11 calls are not transformed and reach
 * the driver, which is how it provokes an error GLSM does not know of: {@code glTexParameteri} with a pname that is
 * none ({@code GL_INVALID_ENUM}).
 */
@GlsmTest
@Tag("glsm-release")
class GlsmReleaseConfigTest {

    private static final String GL = "io.github.mindbound.opengpu.gl.";
    /** GL_INVALID_ENUM as PassState's message names it. */
    private static final String INVALID_ENUM = "0x0500";

    @Test
    @Timeout(60)
    void glsmsGlGetErrorAnswersZeroForTheDriversError(GlThread gl) throws Exception {
        int[] seen = gl.call(() -> {
            GlContextExtension.drainErrors();
            provoke();
            return new int[] { GLStateManager.glGetError(), GL11.glGetError() };
        });
        Assertions.assertEquals(
            GL11.GL_NO_ERROR,
            seen[0],
            "GLStateManager.glGetError reported the error: GLSM's error checks are on, not the release config");
        Assertions.assertEquals(GL11.GL_INVALID_ENUM, seen[1], "the driver's error after the provoking call");
    }

    @Test
    @Timeout(60)
    void theFirstPassFailsOnADriverErrorOfItsOwn(GlThread gl) throws Exception {
        final Backend backend = new Backend();
        Assertions.assertFalse(backend.dev(), "this task runs without assertions, as a release build does");
        try {
            String message = gl.call(() -> {
                GlContextExtension.drainErrors();
                backend.call("beginPass");
                provoke();
                try {
                    backend.call("endPass");
                    return null;
                } catch (IllegalStateException e) {
                    return e.getMessage();
                }
            });
            Assertions.assertNotNull(message, "endPass did not report the pass's GL error (H63; 20:240)");
            Assertions.assertTrue(
                message.contains("GL error " + INVALID_ENUM) && message.contains("after OpenGPU's pass (20:240)"),
                message);
        } finally {
            backend.close(gl);
        }
    }

    @Test
    @Timeout(60)
    void theFirstPassDrainsAndChecksAndALaterOneDoesNeither(GlThread gl) throws Exception {
        final Backend backend = new Backend();
        Assertions.assertFalse(backend.dev(), "this task runs without assertions, as a release build does");
        try {
            int[] seen = gl.call(() -> {
                GlContextExtension.drainErrors();
                // The first pass checks: another mod's error is drained at its start, not reported as its own
                provoke();
                backend.call("beginPass");
                int first = backend.drained();
                backend.call("endPass");
                // A later pass neither drains, since the driver's glGetError can be a sync point, nor checks
                provoke();
                backend.call("beginPass");
                int later = backend.drained();
                provoke();
                backend.call("endPass");
                int left = GL11.glGetError();
                GlContextExtension.drainErrors();
                return new int[] { first, later, left };
            });
            Assertions.assertEquals(1, seen[0], "errors the first pass drained from the driver (R-3)");
            Assertions.assertEquals(0, seen[1], "errors a later pass drained: only a pass that checks drains");
            Assertions.assertEquals(GL11.GL_INVALID_ENUM, seen[2], "the error a later pass left in the driver");
        } finally {
            backend.close(gl);
        }
    }

    /** GL_INVALID_ENUM in the driver: GL_TEXTURE_2D is no texture parameter. Untransformed, so GLSM never sees it. */
    private static void provoke() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_2D, 0);
    }

    /** A GlBackend of :gl's classes as GlsmLoader transforms them, driven by reflection across the loaders. */
    private static final class Backend {

        private final GlsmLoader loader = new GlsmLoader();
        private final Object backend;
        private final Object ledger;

        Backend() throws ReflectiveOperationException {
            Class<?> type = loader.loadClass(GL + "GlBackend");
            Class<?> mode = loader.loadClass(GL + "QuadMode");
            Class<?> ledgerType = loader.loadClass(GL + "GlLedger");
            Class<?> probe = loader.loadClass(GL + "UploadProbe");
            Assertions.assertSame(loader, type.getClassLoader(), "GlBackend was not defined by GlsmLoader");
            ledger = ledgerType.getConstructor()
                .newInstance();
            backend = type.getConstructor(GlyphTable.class, mode, ledgerType, probe)
                .newInstance(
                    SyntheticGlyphs.TABLE,
                    mode.getField("EXPANDED")
                        .get(null),
                    ledger,
                    probe.getField("NONE")
                        .get(null));
        }

        /** Whether PassState, as GlsmLoader defined it, runs its dev-build checks. */
        boolean dev() throws ClassNotFoundException {
            return loader.loadClass(GL + "PassState")
                .desiredAssertionStatus();
        }

        int drained() throws Exception {
            return (Integer) invoke(invoke(backend, "passState"), "errorsDrainedAtEnter");
        }

        Object call(String method) throws Exception {
            return invoke(backend, method);
        }

        /** close() on the GL thread; the ledger is then empty (24:218). */
        void close(GlThread gl) throws Exception {
            gl.call(() -> invoke(backend, "close"));
            Assertions.assertEquals(Boolean.TRUE, invoke(ledger, "isEmpty"), "the ledger after close");
        }

        /** A public no-argument method, its own exception rethrown as it is. */
        private static Object invoke(Object target, String method) throws Exception {
            try {
                return target.getClass()
                    .getMethod(method)
                    .invoke(target);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                throw e;
            }
        }
    }
}
