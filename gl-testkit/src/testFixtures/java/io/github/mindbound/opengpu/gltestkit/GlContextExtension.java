package io.github.mindbound.opengpu.gltestkit;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

/**
 * OpenGPU's GL test extension (docs/research/23-gl-testing-strategy.md §2.2), its own code rather than Angelica's LGPL
 * fixtures (24 §8, risk 16). One context per test JVM, of the profile its Test task names in opengpu.gl.profile:
 * created on a {@link GlThread} for the first {@link GlTest} class, logged, held to GL 3.3 (24 §7, answer 1), to the
 * profile asked for and, when opengpu.gl.expectRenderer is set, to the renderer, and destroyed when the run ends. GL
 * errors are drained before each test and fail it after. Nothing is skipped: without a display or a context, every GL
 * test fails with the cause.
 */
public final class GlContextExtension
    implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace
        .create(GlContextExtension.class);

    private static Throwable startFailure;

    /** This JVM's GL thread, started on first use. */
    static synchronized GlThread gl(ExtensionContext context) throws Exception {
        if (startFailure != null) {
            throw new IllegalStateException("GL context creation failed earlier in this JVM", startFailure);
        }
        ExtensionContext.Store store = context.getRoot()
            .getStore(NAMESPACE);
        GlThread gl = store.get(GlThread.class, GlThread.class);
        if (gl != null) {
            return gl;
        }
        try {
            requireDisplay();
            gl = GlThread.start(GlThread.Profile.valueOf(System.getProperty("opengpu.gl.profile", "COMPAT")));
            // JUnit 5.13+ closes AutoCloseable store values when their context closes: the root's, at the end of the
            // run
            store.put(GlThread.class, gl);
            report(gl.info());
            verify(gl.info());
            return gl;
        } catch (Exception | Error e) {
            startFailure = e;
            throw e;
        }
    }

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        gl(context);
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        gl(context).run(() -> {
            Display.processMessages(); // keeps the window responsive on Windows
            drainErrors(); // what setup or an earlier test left is not this test's
        });
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        int[] errors = gl(context).call(GlContextExtension::drainErrors);
        if (errors.length > 0) {
            StringBuilder codes = new StringBuilder();
            for (int error : errors) {
                codes.append(codes.length() == 0 ? "" : ", ")
                    .append("0x")
                    .append(Integer.toHexString(error));
            }
            throw new AssertionError("GL errors after the test: " + codes);
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext extension) {
        Class<?> type = parameter.getParameter()
            .getType();
        return type == GlThread.class || type == GlInfo.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext extension) {
        try {
            GlThread gl = gl(extension);
            return parameter.getParameter()
                .getType() == GlThread.class ? gl : gl.info();
        } catch (Exception e) {
            throw new ParameterResolutionException("no GL context", e);
        }
    }

    /** Reads and clears GL's error flags, at most 16, since a lost context can report errors forever. */
    static int[] drainErrors() {
        int[] seen = new int[16];
        int count = 0;
        for (int error = GL11.glGetError(); error != GL11.GL_NO_ERROR
            && count < seen.length; error = GL11.glGetError()) {
            seen[count++] = error;
        }
        return Arrays.copyOf(seen, count);
    }

    private static void requireDisplay() {
        // Without this, LWJGL's Display reports a missing X server as an ExceptionInInitializerError
        if (System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .startsWith("linux")) {
            String display = System.getenv("DISPLAY");
            if (display == null || display.isEmpty()) {
                throw new IllegalStateException(
                    "DISPLAY is not set: GL tests need an X server. CI runs the build under xvfb-run; locally use"
                        + " xvfb-run -s '-screen 0 1366x768x24' ./gradlew :gl-testkit:glCompatTest");
            }
        }
    }

    // One line in the test log, and gl-context.txt next to the task's reports
    private static void report(GlInfo info) throws IOException {
        StringBuilder line = new StringBuilder("[opengpu-gl]");
        for (Map.Entry<String, String> entry : info.asMap()
            .entrySet()) {
            line.append(' ')
                .append(entry.getKey())
                .append('=')
                .append(entry.getValue());
        }
        System.out.println(line);
        String dir = System.getProperty("opengpu.gl.reportDir");
        if (dir != null) {
            File file = new File(dir, "gl-context.txt");
            file.getParentFile()
                .mkdirs();
            try (Writer out = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                for (Map.Entry<String, String> entry : info.asMap()
                    .entrySet()) {
                    out.write(entry.getKey() + "=" + entry.getValue() + "\n");
                }
            }
        }
    }

    private static void verify(GlInfo info) {
        if (!info.gl33) {
            throw new IllegalStateException("GL 3.3 is OpenGPU's floor; this context is " + info.version);
        }
        // LWJGL drops ContextAttribs without a word when GLX_ARB_create_context is missing; this check catches that
        if (info.isCoreProfile() != (info.profile == GlThread.Profile.CORE_33)) {
            throw new IllegalStateException(
                "asked for " + info.profile + ", got profile mask 0x" + Integer.toHexString(info.profileMask));
        }
        String expected = System.getProperty("opengpu.gl.expectRenderer");
        String renderer = String.valueOf(info.renderer);
        if (expected != null && !renderer.toLowerCase(Locale.ROOT)
            .contains(expected.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                "expected a renderer containing '" + expected + "', got '" + renderer + "'");
        }
    }
}
