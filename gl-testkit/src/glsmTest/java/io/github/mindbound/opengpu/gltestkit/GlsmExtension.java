package io.github.mindbound.opengpu.gltestkit;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.ByteBuffer;
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
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.gtnewhorizons.angelica.glsm.RenderSystem;
import com.gtnewhorizons.angelica.glsm.hooks.GLSMInitConfig;

/**
 * Angelica's GLSM brought up outside FML (the GLSM spike, roadmap M0; docs/research/23-gl-testing-strategy.md §2.3) on
 * {@link GlContextExtension}'s CORE_33 context, the only kind GLSM takes: RenderSystem.initRenderer throws on a
 * compatibility context (RenderSystem.java:182 at 2.2.8, :184 at 2.2.28). Once per test JVM, on the GL thread, in the
 * order Angelica's MixinInitGLStateManager uses in game (:38-52 at 2.2.8, :39-49 at 2.2.28), then the end of the
 * splash, so GLSM is as it is when the render core runs in a dev client: with DSA as Angelica asks for it by default
 * ({@code -Dopengpu.glsm.enableDSA}) and GLSM's error checks on. A release client turns them off by default
 * (Angelica's {@code disableErrorChecks} in an obfuscated client), so that {@code GLStateManager.glGetError} answers 0;
 * {@code -Dopengpu.glsm.noErrorChecks=true} does the same, which the release-config legs set (S8 review HY-1). GLSM
 * is a JVM singleton with no teardown, so each Angelica release and each such setting is its own Test task. Before
 * each test the compared state goes back to the context's defaults, the driver first and then GLSM, so a GLSM call
 * skipped as matching its cache still leaves both equal; after each test it fails
 * when GLSM's cache and the driver disagree on that state: the probes' items and the START pass's (S8 plan §4.5:
 * program, VAO, array and pixel buffer bindings, units 0-1's textures and samplers, both pixel stores, polygon mode,
 * colour and depth masks), so a call that bypasses GLSM's cache shows. GlsmScenarioTest also hands
 * {@link #outOfSync} to the scenarios' hygiene check, which runs it after every pass. OpenGPU's own code, as
 * GlContextExtension is (24 §8, risk 16).
 */
public final class GlsmExtension
    implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, ParameterResolver {

    /** The units the pass binds (H53): 0 and 1. */
    private static final int UNITS = 2;
    private static final int[] PACK = { GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_PIXELS,
        GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST };
    private static final int[] UNPACK = { GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH,
        GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_UNPACK_LSB_FIRST };
    private static final int[] STORE_DEFAULTS = { 4, 0, 0, 0, GL11.GL_FALSE, GL11.GL_FALSE };
    private static final String[] STORE_NAMES = { "alignment", "row length", "skip pixels", "skip rows", "swap bytes",
        "LSB first" };

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
                // In game true in an obfuscated client by default (AngelicaConfig.disableErrorChecks;
                // MixinInitGLStateManager.java:47 at 2.2.8, :46 at 2.2.28), and then GLStateManager.glGetError answers
                // 0; false in a dev client and here, but for the release-config legs (S8 review HY-1)
                .noErrorChecks(Boolean.parseBoolean(System.getProperty("opengpu.glsm.noErrorChecks", "false")))
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

    /**
     * Puts every compared item back to the context's defaults: the driver first, then GLSM, so a GLSM call skipped as
     * matching its cache still leaves both equal (S8 amendments E, B12). The VAO goes through GLSM only, whose
     * {@code glBindVertexArray(0)} binds its default VAO (25 amendments J); {@link #outOfSync} compares GLSM's answer
     * with the driver's binding. Units 1 and 0 are switched in both.
     */
    static void baseline() {
        GL11.glViewport(0, 0, GlThread.WIDTH, GlThread.HEIGHT);
        GL11.glScissor(0, 0, GlThread.WIDTH, GlThread.HEIGHT);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glEnable(GL11.GL_DITHER);
        GL11.glClearColor(0f, 0f, 0f, 0f);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL20.glUseProgram(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        for (int unit = UNITS - 1; unit >= 0; unit--) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GL33.glBindSampler(unit, 0);
        }
        for (int i = 0; i < PACK.length; i++) {
            GL11.glPixelStorei(PACK[i], STORE_DEFAULTS[i]);
            GL11.glPixelStorei(UNPACK[i], STORE_DEFAULTS[i]);
        }
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        GL11.glColorMask(true, true, true, true);
        GL11.glDepthMask(true);
        GLStateManager.glViewport(0, 0, GlThread.WIDTH, GlThread.HEIGHT);
        GLStateManager.glScissor(0, 0, GlThread.WIDTH, GlThread.HEIGHT);
        GLStateManager.glDisable(GL11.GL_SCISSOR_TEST);
        GLStateManager.glEnable(GL11.GL_DITHER);
        GLStateManager.glClearColor(0f, 0f, 0f, 0f);
        GLStateManager.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GLStateManager.glUseProgram(0);
        GLStateManager.glBindVertexArray(0);
        GLStateManager.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GLStateManager.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        GLStateManager.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        for (int unit = UNITS - 1; unit >= 0; unit--) {
            GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GLStateManager.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GLStateManager.glBindSampler(unit, 0);
        }
        for (int i = 0; i < PACK.length; i++) {
            GLStateManager.glPixelStorei(PACK[i], STORE_DEFAULTS[i]);
            GLStateManager.glPixelStorei(UNPACK[i], STORE_DEFAULTS[i]);
        }
        GLStateManager.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        GLStateManager.glColorMask(true, true, true, true);
        GLStateManager.glDepthMask(true);
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
        // S8 plan §4.5: the pass's items. GLSM's program 0 is its fixed-function emulation, whose program it binds in
        // the driver itself (glUseProgram(0) calls ShaderManager.activate(); 25 amendments J), so only a program of
        // the caller's is compared
        int glsmProgram = GLStateManager.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (glsmProgram != 0) {
            compare(drift, "program", glsmProgram, GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
        }
        int[] bindings = { GL30.GL_VERTEX_ARRAY_BINDING, GL15.GL_ARRAY_BUFFER_BINDING,
            GL21.GL_PIXEL_PACK_BUFFER_BINDING, GL21.GL_PIXEL_UNPACK_BUFFER_BINDING };
        String[] names = { "vertex array", "array buffer", "pack buffer", "unpack buffer" };
        for (int i = 0; i < bindings.length; i++) {
            compare(drift, names[i], GLStateManager.glGetInteger(bindings[i]), GL11.glGetInteger(bindings[i]));
        }
        // Units 0-1's textures and samplers, the unit switched in both and put back in both after
        int glsmActive = GLStateManager.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int driverActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        for (int unit = 0; unit < UNITS; unit++) {
            GLStateManager.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            compare(
                drift,
                "unit " + unit + " texture",
                GLStateManager.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),
                GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
            compare(
                drift,
                "unit " + unit + " sampler",
                GLStateManager.glGetInteger(GL33.GL_SAMPLER_BINDING),
                GL11.glGetInteger(GL33.GL_SAMPLER_BINDING));
        }
        GL13.glActiveTexture(driverActive);
        GLStateManager.glActiveTexture(glsmActive);
        for (int i = 0; i < PACK.length; i++) {
            compare(drift, "pack " + STORE_NAMES[i], GLStateManager.glGetInteger(PACK[i]), GL11.glGetInteger(PACK[i]));
            compare(
                drift,
                "unpack " + STORE_NAMES[i],
                GLStateManager.glGetInteger(UNPACK[i]),
                GL11.glGetInteger(UNPACK[i]));
        }
        // The first value: a core driver and GLSM's cache need not fill the second
        compare(drift, "polygon mode", glsmInts(GL11.GL_POLYGON_MODE)[0], driverInts(GL11.GL_POLYGON_MODE)[0]);
        compare(drift, "colour mask", glsmBooleans(GL11.GL_COLOR_WRITEMASK), driverBooleans(GL11.GL_COLOR_WRITEMASK));
        compare(drift, "depth mask", glsmBooleans(GL11.GL_DEPTH_WRITEMASK), driverBooleans(GL11.GL_DEPTH_WRITEMASK));
        return drift;
    }

    static String glsmBooleans(int pname) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(16);
        GLStateManager.glGetBoolean(pname, buffer);
        return buffer.get(0) + "," + buffer.get(1) + "," + buffer.get(2) + "," + buffer.get(3);
    }

    static String driverBooleans(int pname) {
        ByteBuffer buffer = BufferUtils.createByteBuffer(16);
        GL11.glGetBoolean(pname, buffer);
        return buffer.get(0) + "," + buffer.get(1) + "," + buffer.get(2) + "," + buffer.get(3);
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
