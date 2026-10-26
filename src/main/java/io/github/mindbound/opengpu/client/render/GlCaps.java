package io.github.mindbound.opengpu.client.render;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import net.minecraft.client.renderer.OpenGlHelper;

import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.versioning.DefaultArtifactVersion;
import cpw.mods.fml.common.versioning.VersionParser;
import cpw.mods.fml.common.versioning.VersionRange;

/**
 * The client's GL probe and the Angelica version gate (14 §3 as 24:67 narrows it; 20 §7; 24:125, :230; 25 §1.1; R-7,
 * R-13; S12 plan P6 with amendments F8 and F11). {@link #probe()} reads once, on the client main thread in
 * {@code ClientProxy.init}, after {@code OpenGlHelper.initializeTextures} (Minecraft.java:497; 25:59): 14 §3's
 * full flag set, Angelica's loaded version (20:113; 25:55), GLSM's GLES flag, the core-profile bit (C01) and
 * {@code GL_VENDOR}, {@code GL_RENDERER} and {@code GL_VERSION} (C02). It keeps primitives and strings, never the
 * {@code ContextCapabilities} object (14:84's rule 3), and reads only fields of 14:56's row of names present in all
 * three LWJGL APIs (14:83's rule 2). Under Forge's splash the main thread holds a context that shares objects with the
 * game's (SplashProgress.java:192-194), which these reads accept and which makes no GL object.
 * <p>
 * Only GL 3.3, the gate and GLES decide anything, in {@link #decide}; everything else is logged once and never
 * branched on (14:110; 14 §2.8). The gate is a runtime check, not an {@code @Mod} range, which FML would turn into a
 * refusal to start (Loader.java:246-260; 25:58-62), and {@code @Mod} has no {@code after:angelica} (S12 plan P6.3).
 * <p>
 * Like every class under {@code client/}, it carries no {@code @Lwjgl3Aware} and names no LWJGL class in a string
 * (14:82; {@code jarTest} checks 3 and 4).
 */
public final class GlCaps {

    /** 24:125's reason for a context below GL 3.3 compatibility: the vanilla leg's floor (25:47; 20:230). */
    public static final String BELOW_GL33 = "host GPU below GL 3.3";
    /** 24:125's reason for GLSM on a GLES context (R-7; 25:453-455). */
    public static final String GLES_UNTESTED = "GLES profile untested";
    /**
     * The reason for an Angelica below the floor (24:230; 25 §1.1's "with that reason" names no string): Q-S12-5 (a).
     * The version goes to the log line, not to the string.
     */
    public static final String ANGELICA_BELOW_FLOOR = "Angelica below 2.2.8";
    /** 25 §1.1, R-13: one range is the whole gate, with no ceiling (25:546-547). */
    public static final String ANGELICA_RANGE = "[2.2.8,)";
    /** Angelica's mod id, as FML indexes it. */
    public static final String ANGELICA = "angelica";
    /** Angelica's switch for its SDL-GPU backend (02:102), logged as it is; no class is looked up for it. */
    public static final String SDL_GPU_PROPERTY = "angelica.sdlgpu.enable";

    /** R-7's reflective target, an Angelica class, which {@code jarTest} check 3 allows in a string. */
    private static final String RENDER_SYSTEM = "com.gtnewhorizons.angelica.glsm.RenderSystem";
    private static final VersionRange FLOOR = VersionParser.parseRange(ANGELICA_RANGE);
    /**
     * FML's ComparableVersion parses every string and puts a non-numeric one below the floor (S12 spike §6 item 4), so
     * "parses" is the plan's own rule: the version, trimmed, starts with digits and dots (S12 plan P6.3; Q-S12-5 (a)).
     */
    private static final Pattern PARSES = Pattern.compile("\\d+(\\.\\d+)*");

    private final Map<String, Boolean> flags;
    private final boolean gl33;
    private final boolean angelicaLoaded;
    private final String angelicaVersion;
    private final boolean gles;
    private final String sdlGpu;
    private final String vendor;
    private final String renderer;
    private final String version;
    private final String reason;

    /**
     * The probe's values: {@code flags} the logged GL flags in their log order, with "gl33" among them, which decides;
     * the Angelica version is read only when Angelica is loaded, and GLES only with Angelica.
     *
     * @throws NullPointerException for null flags
     */
    GlCaps(Map<String, Boolean> flags, boolean angelicaLoaded, String angelicaVersion, boolean gles, String sdlGpu,
        String vendor, String renderer, String version) {
        this.flags = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(flags, "flags")));
        this.gl33 = Boolean.TRUE.equals(flags.get("gl33"));
        this.angelicaLoaded = angelicaLoaded;
        this.angelicaVersion = angelicaVersion;
        this.gles = gles;
        this.sdlGpu = sdlGpu;
        this.vendor = vendor;
        this.renderer = renderer;
        this.version = version;
        this.reason = decide(angelicaLoaded, angelicaVersion, gles, gl33);
    }

    /**
     * Reads the current context, Angelica's version and GLSM's GLES flag. Client main thread, once, in
     * {@code ClientProxy.init}; it needs a current context and a launched game ({@code Loader}), so no test runs it.
     */
    public static GlCaps probe() {
        boolean angelicaLoaded = Loader.isModLoaded(ANGELICA);
        String angelicaVersion = angelicaLoaded ? angelicaVersion() : null;
        boolean gles = angelicaLoaded && isGles();
        ContextCapabilities caps = GLContext.getCapabilities();
        // C01 only from GL 3.2, where GL_CONTEXT_PROFILE_MASK exists (20:113; 03 §5 rule 8)
        boolean core = caps.OpenGL32
            && (GL11.glGetInteger(GL32.GL_CONTEXT_PROFILE_MASK) & GL32.GL_CONTEXT_CORE_PROFILE_BIT) != 0;
        // 14 §3's set (14:94-107), read now and kept as booleans
        Map<String, Boolean> flags = new LinkedHashMap<>();
        flags.put("gl14", caps.OpenGL14);
        flags.put("gl15", caps.OpenGL15);
        flags.put("gl20", caps.OpenGL20);
        flags.put("gl21", caps.OpenGL21);
        flags.put("gl30", caps.OpenGL30);
        flags.put("gl32", caps.OpenGL32);
        flags.put("gl33", caps.OpenGL33);
        flags.put("core", core);
        flags.put("fbo", caps.OpenGL30 || caps.GL_ARB_framebuffer_object || caps.GL_EXT_framebuffer_object);
        flags.put("shaders", OpenGlHelper.shadersSupported);
        flags.put("bgraUpload", caps.OpenGL12);
        flags.put("pbo", caps.GL_ARB_pixel_buffer_object);
        flags.put("mapBufferRange", caps.GL_ARB_map_buffer_range);
        flags.put("bufferStorage", caps.GL_ARB_buffer_storage);
        flags.put("sync", caps.GL_ARB_sync);
        return new GlCaps(
            flags,
            angelicaLoaded,
            angelicaVersion,
            gles,
            System.getProperty(SDL_GPU_PROPERTY),
            GL11.glGetString(GL11.GL_VENDOR),
            GL11.glGetString(GL11.GL_RENDERER),
            GL11.glGetString(GL11.GL_VERSION));
    }

    /**
     * {@code graphics}' reason, or null when graphics are on: the gate, then GLES, then GL 3.3, the first that applies
     * (Q-S12-5 (a)). The gate needs no GL state, and an Angelica below the floor is the actionable cause when two
     * apply. A version that does not parse ({@link #versionParses}) counts as supported (25:51). Pure: the root's plain
     * tests run it.
     */
    static String decide(boolean angelicaLoaded, String angelicaVersion, boolean gles, boolean gl33) {
        if (angelicaLoaded && versionParses(angelicaVersion)
            && !FLOOR.containsVersion(new DefaultArtifactVersion(ANGELICA, angelicaVersion.trim()))) {
            return ANGELICA_BELOW_FLOOR;
        }
        if (gles) {
            return GLES_UNTESTED;
        }
        if (!gl33) {
            return BELOW_GL33;
        }
        return null;
    }

    /** Whether the version, trimmed, starts with digits and dots, so that FML's range can judge it (S12 plan P6.3). */
    static boolean versionParses(String version) {
        return version != null && PARSES.matcher(version.trim())
            .lookingAt();
    }

    /** Angelica's version as FML indexed it (Loader.java:728; 25:55), or null. */
    private static String angelicaVersion() {
        ModContainer angelica = Loader.instance()
            .getIndexedModList()
            .get(ANGELICA);
        return angelica == null ? null : angelica.getVersion();
    }

    /** R-7: {@code RenderSystem.isGLES()}, read reflectively; a missing class or method means not GLES (25:453-455). */
    private static boolean isGles() {
        try {
            Object answer = Class.forName(RENDER_SYSTEM)
                .getMethod("isGLES")
                .invoke(null);
            return Boolean.TRUE.equals(answer);
        } catch (ReflectiveOperationException | LinkageError | SecurityException e) {
            return false;
        }
    }

    /** {@code graphics}' reason (24:125), or null when graphics are on. */
    public String reason() {
        return reason;
    }

    /** {@code GL_RENDERER} (C02), for {@code Installation.hostRenderer} (24:125; RM:43) and the log; may be null. */
    public String renderer() {
        return renderer;
    }

    /**
     * The one log line (14:110; S12 plan P6.6 with amendment F8), for example "OpenGPU GlCaps: gl14=true ... sync=true
     * angelica=absent gles=false sdlGpu=unset vendor="Intel" renderer="Intel(R) UHD Graphics 770" version="4.6.0 -
     * Build 32.0.101.6913" graphics=available", or with "angelica=2.2.7 gate=below ... graphics="Angelica below
     * 2.2.8"", or with "angelica="@VERSION@" (does not parse, treated as supported)".
     */
    public String logLine() {
        StringBuilder line = new StringBuilder("OpenGPU GlCaps:");
        for (Map.Entry<String, Boolean> flag : flags.entrySet()) {
            line.append(' ')
                .append(flag.getKey())
                .append('=')
                .append(flag.getValue());
        }
        line.append(" angelica=");
        if (!angelicaLoaded) {
            line.append("absent");
        } else if (!versionParses(angelicaVersion)) {
            line.append(quoted(angelicaVersion))
                .append(" (does not parse, treated as supported)");
        } else {
            line.append(angelicaVersion.trim())
                .append(" gate=")
                .append(ANGELICA_BELOW_FLOOR.equals(reason) ? "below" : "supported");
        }
        line.append(" gles=")
            .append(gles)
            .append(" sdlGpu=")
            .append(sdlGpu == null ? "unset" : quoted(sdlGpu))
            .append(" vendor=")
            .append(quoted(vendor))
            .append(" renderer=")
            .append(quoted(renderer))
            .append(" version=")
            .append(quoted(version))
            .append(" graphics=")
            .append(reason == null ? "available" : quoted(reason));
        return line.toString();
    }

    private static String quoted(String text) {
        return text == null ? "null" : "\"" + text + "\"";
    }

    @Override
    public String toString() {
        return logLine();
    }
}
