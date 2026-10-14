package io.github.mindbound.opengpu.gltestkit;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMInitConfig;

/**
 * Angelica's GLSM brought up outside FML (the GLSM spike, roadmap M0; docs/research/23-gl-testing-strategy.md §2.3) on
 * {@link GlContextExtension}'s CORE_33 context, the only kind GLSM takes: RenderSystem.initRenderer throws on a
 * compatibility context (RenderSystem.java:182 at 2.2.8, :184 at 2.2.28). Once per test JVM, on the GL thread, in the
 * order Angelica's MixinInitGLStateManager uses in game (:38-52 at 2.2.8, :39-49 at 2.2.28), then the end of the
 * splash, so GLSM is as it is when the render core runs. GLSM is a JVM singleton with no teardown, so each Angelica
 * release is its own Test task. Before each test the state the probes touch goes back to the context's defaults, the
 * driver first and then GLSM, so a GLSM call skipped as matching its cache still leaves both equal; after each test it
 * fails when GLSM's cache and the driver disagree on that state. OpenGPU's own code, as GlContextExtension is (24 §8,
 * risk 16).
 */
public final class GlsmExtension
    implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static GlsmInfo info;
    private static Throwable startFailure;

    /** This JVM's GLSM, brought up on first use. */
    static synchronized GlsmInfo glsm(ExtensionContext context) throws Exception {
        if (startFailure != null) {
            throw new IllegalStateException("GLSM failed to start earlier in this JVM", startFailure);
        }
        if (info == null) {
            GlThread gl = GlContextExtension.gl(context);
            try {
                info = gl.call(GlsmExtension::start);
                report(info);
            } catch (Exception | Error e) {
                startFailure = e;
                throw e;
            }
        }
        return info;
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        glsm(context);
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        glsm(context);
        List<String> drift = GlContextExtension.gl(context)
            .call(() -> {
                baseline();
                return outOfSync();
            });
        if (!drift.isEmpty()) {
            throw new IllegalStateException("GLSM's cache and the driver disagree after the reset: " + drift);
        }
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        if (info == null) {
            return; // GLSM never started; beforeEach has said why
        }
        List<String> drift = GlContextExtension.gl(context)
            .call(GlsmExtension::outOfSync);
        if (!drift.isEmpty()) {
            throw new AssertionError("GLSM's cache and the driver disagree after the test: " + drift);
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext extension) {
        return parameter.getParameter()
            .getType() == GlsmInfo.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext extension) {
        try {
            return glsm(extension);
        } catch (Exception e) {
            throw new ParameterResolutionException("no GLSM", e);
        }
    }

    private static GlsmInfo start() throws Exception {
        long started = System.nanoTime();
        // The first use of GLStateManager. Its static initializer queries GL, so it needs this thread's context, and
        // takes the thread that runs it as GLSM's main thread for good (GLStateManager.java:205 and :221 at 2.2.8, :306
        // and :329 at 2.2.28); Angelica's own fixtures overwrite that field with Unsafe instead.
        GLStateManager.setDrawableGL(Display.getDrawable());
        if (GLStateManager.getMainThread() != Thread.currentThread()) {
            throw new IllegalStateException(
                "GLSM's main thread is " + GLStateManager.getMainThread()
                    .getName() + ", not the GL thread: GLStateManager was used on another thread first");
        }
        GLStateManager.initialize(
            GLSMInitConfig.builder()
                // Seeds GLSM's viewport, and from 2.2.21 its scissor box, as the window's size
                .displaySize(GlThread.WIDTH, GlThread.HEIGHT)
                // The defaults differ, false at 2.2.8 and true from 2.2.21; true is Angelica's in-game default
                .enableDSA(Boolean.parseBoolean(System.getProperty("opengpu.glsm.enableDSA", "true")))
                // A release pack turns GLSM's glGetError off (MixinInitGLStateManager.java:47 at 2.2.8); not here
                .noErrorChecks(false)
                .build());
        GLStateManager.setRunningSplash(false);
        GLStateManager.markSplashComplete("opengpu-gltestkit");
        if (!RenderSystem.isCoreProfile()) {
            throw new IllegalStateException("GLSM did not see a core profile");
        }
        if (!GLStateManager.isSplashComplete() || !GLStateManager.isCachingEnabled()) {
            throw new IllegalStateException("GLSM's splash did not complete, or its cache is off");
        }
        return GlsmInfo.query(System.nanoTime() - started);
    }

    /** Puts the state the probes touch back to the context's defaults: the driver first, then GLSM. */
    static void baseline() {
        GL11.glViewport(0, 0, GlThread.WIDTH, GlThread.HEIGHT);
        GL11.glScissor(0, 0, GlThread.WIDTH, GlThread.HEIGHT);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glEnable(GL11.GL_DITHER);
        GL11.glClearColor(0f, 0f, 0f, 0f);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
        GLStateManager.glViewport(0, 0, GlThread.WIDTH, GlThread.HEIGHT);
        GLStateManager.glScissor(0, 0, GlThread.WIDTH, GlThread.HEIGHT);
        GLStateManager.glDisable(GL11.GL_SCISSOR_TEST);
        GLStateManager.glEnable(GL11.GL_DITHER);
        GLStateManager.glClearColor(0f, 0f, 0f, 0f);
        GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GLStateManager.glActiveTexture(GL13.GL_TEXTURE0);
        GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        GLStateManager.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
    }

    /**
     * The probe-touched state on which GLSM's answer and the driver's differ, as "name: GLSM x, driver y". GLSM answers
     * from its cache, except for the scissor box before 2.2.21, which it asks the driver for (25 R-1).
     */
    static List<String> outOfSync() {
        List<String> drift = new ArrayList<>();
        compare(drift, "viewport", glsmInts(GL11.GL_VIEWPORT), driverInts(GL11.GL_VIEWPORT));
        compare(drift, "scissor box", glsmInts(GL11.GL_SCISSOR_BOX), driverInts(GL11.GL_SCISSOR_BOX));
        compare(
            drift,
            "scissor test",
            GLStateManager.glIsEnabled(GL11.GL_SCISSOR_TEST),
            GL11.glIsEnabled(GL11.GL_SCISSOR_TEST));
        compare(drift, "dither", GLStateManager.glIsEnabled(GL11.GL_DITHER), GL11.glIsEnabled(GL11.GL_DITHER));
        compare(drift, "clear colour", glsmFloats(GL11.GL_COLOR_CLEAR_VALUE), driverFloats(GL11.GL_COLOR_CLEAR_VALUE));
        compare(
            drift,
            "draw framebuffer",
            GLStateManager.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
            GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
        compare(
            drift,
            "read framebuffer",
            GLStateManager.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),
            GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING));
        compare(
            drift,
            "active texture",
            GLStateManager.glGetInteger(GL13.GL_ACTIVE_TEXTURE),
            GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE));
        compare(
            drift,
            "texture 2D binding",
            GLStateManager.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),
            GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
        return drift;
    }

    // Four values through GLSM and through the driver; LWJGL's checks want 16-element buffers (R-2: absolute reads)

    static int[] glsmInts(int pname) {
        IntBuffer buffer = BufferUtils.createIntBuffer(16);
        GLStateManager.glGetInteger(pname, buffer);
        return new int[] { buffer.get(0), buffer.get(1), buffer.get(2), buffer.get(3) };
    }

    static int[] driverInts(int pname) {
        IntBuffer buffer = BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(pname, buffer);
        return new int[] { buffer.get(0), buffer.get(1), buffer.get(2), buffer.get(3) };
    }

    static float[] glsmFloats(int pname) {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        GLStateManager.glGetFloat(pname, buffer);
        return new float[] { buffer.get(0), buffer.get(1), buffer.get(2), buffer.get(3) };
    }

    static float[] driverFloats(int pname) {
        FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(pname, buffer);
        return new float[] { buffer.get(0), buffer.get(1), buffer.get(2), buffer.get(3) };
    }

    private static void compare(List<String> drift, String name, int[] glsm, int[] driver) {
        compare(drift, name, Arrays.toString(glsm), Arrays.toString(driver));
    }

    private static void compare(List<String> drift, String name, float[] glsm, float[] driver) {
        compare(drift, name, Arrays.toString(glsm), Arrays.toString(driver));
    }

    private static void compare(List<String> drift, String name, Object glsm, Object driver) {
        if (!glsm.equals(driver)) {
            drift.add(name + ": GLSM " + glsm + ", driver " + driver);
        }
    }

    // One line in the test log, and glsm.txt next to the task's gl-context.txt
    private static void report(GlsmInfo glsm) throws IOException {
        StringBuilder line = new StringBuilder("[opengpu-glsm]");
        for (Map.Entry<String, String> entry : glsm.asMap()
            .entrySet()) {
            line.append(' ')
                .append(entry.getKey())
                .append('=')
                .append(entry.getValue());
        }
        System.out.println(line);
        String dir = System.getProperty("opengpu.gl.reportDir");
        if (dir != null) {
            File file = new File(dir, "glsm.txt");
            file.getParentFile()
                .mkdirs();
            try (Writer out = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String> entry : glsm.asMap()
                    .entrySet()) {
                    out.write(entry.getKey() + "=" + entry.getValue() + "\n");
                }
            }
        }
    }
}
