package io.github.mindbound.opengpu.gltestkit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.lwjgl.Sys;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GLContext;

/**
 * The context a GL test ran on, recorded with every run: renderer, GL version and, on Mesa, the Mesa version inside it
 * (docs/research/23-gl-testing-strategy.md §3.5).
 */
public final class GlInfo {

    public final GlThread.Profile profile;
    public final String vendor;
    public final String renderer;
    public final String version;
    public final String glslVersion;
    public final String lwjglVersion;
    /** Whether the context offers GL 3.3, OpenGPU's floor. */
    public final boolean gl33;
    /** GL_CONTEXT_PROFILE_MASK; 0 below GL 3.2, which has none. */
    public final int profileMask;

    private GlInfo(GlThread.Profile profile, String vendor, String renderer, String version, String glslVersion,
        String lwjglVersion, boolean gl33, int profileMask) {
        this.profile = profile;
        this.vendor = vendor;
        this.renderer = renderer;
        this.version = version;
        this.glslVersion = glslVersion;
        this.lwjglVersion = lwjglVersion;
        this.gl33 = gl33;
        this.profileMask = profileMask;
    }

    /** Reads the current context; call it on the GL thread. */
    static GlInfo query(GlThread.Profile profile) {
        ContextCapabilities caps = GLContext.getCapabilities();
        return new GlInfo(
            profile,
            GL11.glGetString(GL11.GL_VENDOR),
            GL11.glGetString(GL11.GL_RENDERER),
            GL11.glGetString(GL11.GL_VERSION),
            GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION),
            Sys.getVersion(),
            caps.OpenGL33,
            caps.OpenGL32 ? GL11.glGetInteger(GL32.GL_CONTEXT_PROFILE_MASK) : 0);
    }

    public boolean isCoreProfile() {
        return (profileMask & GL32.GL_CONTEXT_CORE_PROFILE_BIT) != 0;
    }

    /** The record as ordered key=value pairs: the log line, gl-context.txt and the test report's entries. */
    public Map<String, String> asMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("gl.profile", profile.name());
        map.put("gl.vendor", orDash(vendor));
        map.put("gl.renderer", orDash(renderer));
        map.put("gl.version", orDash(version));
        map.put("gl.glsl", orDash(glslVersion));
        map.put("gl.profileMask", "0x" + Integer.toHexString(profileMask));
        map.put("lwjgl.version", orDash(lwjglVersion));
        map.put("java.version", orDash(System.getProperty("java.version")));
        map.put("java.vendor", orDash(System.getProperty("java.vendor")));
        map.put("java.home", orDash(System.getProperty("java.home")));
        map.put("os", orDash(System.getProperty("os.name") + " " + System.getProperty("os.arch")));
        map.put("display", orDash(System.getenv("DISPLAY")));
        return Collections.unmodifiableMap(map);
    }

    // JUnit's report entries refuse blank values
    private static String orDash(String value) {
        return value == null || value.trim()
            .isEmpty() ? "-" : value;
    }
}
