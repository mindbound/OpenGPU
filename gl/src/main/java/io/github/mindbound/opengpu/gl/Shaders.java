package io.github.mindbound.opengpu.gl;

/**
 * The internal shaders (20:93-99; S8 plan §3.6), {@code #version 330 core} first, which compiles on a compatibility
 * context of 3.3 or later and passes GLSM's {@code fixupVersion} unchanged (20 §2). No identifier is {@code sample},
 * {@code new}, {@code sampler} or {@code texture}, which GLSM's {@code renameReservedWords} renames (20:96-97; S8
 * amendments E), and none uses OpenGPU's user prefixes (24:144). Every op writes the precomputed {@code sv(c)} or a
 * texel (28:414; H58) into an integer target, where blending does not apply (GL33:10235-10236), and overwrites or
 * discards (28:410). Constants only, no GL call.
 * <p>
 * One vertex shader serves every program and both quad modes: the corner comes from {@code gl_VertexID % 6}, so
 * EXPANDED (each record six times) and INSTANCED (each record once, divisor 1) draw the same triangles (H39). The
 * record's integer attributes carry the values (C82; H40), never {@code glVertexAttrib*} (V1) or {@code glUniform1ui}
 * (A16). Surface pixel (x, y) is window pixel (x, y) of the bound target, with no flip (28:409), so the fragment's
 * {@code ivec2(gl_FragCoord.xy)} plus the record's offset is the texel it fetches.
 */
final class Shaders {

    // Program codes, the index of NAMES, FRAGMENT and UNIFORMS (OpQuads names the surface ones)
    static final int FILL = 0;
    static final int BLIT_INDEX8 = 1;
    static final int BLIT_RGB565 = 2;
    static final int TEXT = 3;
    static final int EXPAND_INDEX8 = 4;
    static final int EXPAND_RGB565 = 5;
    static final int COUNT = 6;

    /** The programs' names, which are their {@code GlLedger} labels (S8 plan §3.11). */
    static final String[] NAMES = { "fill", "blit-index8", "blit-rgb565", "text", "expand-index8", "expand-rgb565" };

    static final String VERSION = "#version 330 core\n";

    /** Every program's vertex shader: the quad's corner from {@code gl_VertexID}, its rect in pixels to NDC. */
    static final String QUAD_VS = VERSION + "layout(location = 0) in ivec4 a_rect;\n"
        + "layout(location = 1) in ivec2 a_offset;\n"
        + "layout(location = 2) in uvec4 a_value;\n"
        // (2/W, 2/H) of the bound target (C59)
        + "uniform vec2 u_scale;\n"
        + "flat out ivec2 v_offset;\n"
        + "flat out uvec4 v_value;\n"
        + "const ivec2 CORNER[6] = ivec2[6](ivec2(0, 0), ivec2(1, 0), ivec2(0, 1), ivec2(0, 1), ivec2(1, 0),"
        + " ivec2(1, 1));\n"
        + "void main() {\n"
        + "    ivec2 c = CORNER[gl_VertexID % 6];\n"
        + "    ivec2 p = ivec2(c.x == 0 ? a_rect.x : a_rect.z, c.y == 0 ? a_rect.y : a_rect.w);\n"
        + "    gl_Position = vec4(vec2(p) * u_scale - 1.0, 0.0, 1.0);\n"
        + "    v_offset = a_offset;\n"
        + "    v_value = a_value;\n"
        + "}\n";

    /** {@code CLEAR} and {@code FILL_RECT} (28 §5.3-§5.4): sv(c) in value 0. */
    static final String FILL_FS = VERSION + "flat in uvec4 v_value;\n"
        + "layout(location = 0) out uint o_value;\n"
        + "void main() {\n"
        + "    o_value = v_value.x;\n"
        + "}\n";

    /**
     * {@code BLIT} from a {@code GL_R8} texture (28 §5.5): the index recovered as {@code floor(r * 255.0 + 0.5)}
     * (28:583; 24:205); {@code KEY0} (value 0, bit 0) discards a texel of 0 (28:448).
     */
    static final String BLIT_INDEX8_FS = VERSION + "uniform sampler2D u_tex;\n"
        + "flat in ivec2 v_offset;\n"
        + "flat in uvec4 v_value;\n"
        + "layout(location = 0) out uint o_value;\n"
        + "void main() {\n"
        + "    ivec2 t = ivec2(gl_FragCoord.xy) + v_offset;\n"
        + "    uint v = uint(floor(texelFetch(u_tex, t, 0).r * 255.0 + 0.5));\n"
        + "    if ((v_value.x & 1u) != 0u && v == 0u) discard;\n"
        + "    o_value = v;\n"
        + "}\n";

    /** {@code BLIT} from a {@code GL_R16UI} texture (28:584): the 565 value raw, the same {@code KEY0} test. */
    static final String BLIT_RGB565_FS = VERSION + "uniform usampler2D u_tex;\n"
        + "flat in ivec2 v_offset;\n"
        + "flat in uvec4 v_value;\n"
        + "layout(location = 0) out uint o_value;\n"
        + "void main() {\n"
        + "    ivec2 t = ivec2(gl_FragCoord.xy) + v_offset;\n"
        + "    uint v = texelFetch(u_tex, t, 0).r;\n"
        + "    if ((v_value.x & 1u) != 0u && v == 0u) discard;\n"
        + "    o_value = v;\n"
        + "}\n";

    /**
     * {@code TEXT} (28 §5.6): the atlas's bit (0 or 255, 28 §7.5) picks sv(fg) in value 0 or sv(bg) in value 1, and
     * {@code TRANSPARENT_BG} in value 2 discards a clear bit.
     */
    static final String TEXT_FS = VERSION + "uniform sampler2D u_atlas;\n"
        + "flat in ivec2 v_offset;\n"
        + "flat in uvec4 v_value;\n"
        + "layout(location = 0) out uint o_value;\n"
        + "void main() {\n"
        + "    ivec2 t = ivec2(gl_FragCoord.xy) + v_offset;\n"
        + "    bool lit = texelFetch(u_atlas, t, 0).r > 0.5;\n"
        + "    if (lit) {\n"
        + "        o_value = v_value.x;\n"
        + "    } else if ((v_value.z & 1u) != 0u) {\n"
        + "        discard;\n"
        + "    } else {\n"
        + "        o_value = v_value.y;\n"
        + "    }\n"
        + "}\n";

    /** Expand of an index8 surface (28:485): {@code palette[i]} from the 256x1 RGBA8 palette on unit 1. */
    static final String EXPAND_INDEX8_FS = VERSION + "uniform usampler2D u_surface;\n"
        + "uniform sampler2D u_palette;\n"
        + "layout(location = 0) out vec4 o_colour;\n"
        + "void main() {\n"
        + "    uint i = texelFetch(u_surface, ivec2(gl_FragCoord.xy), 0).r;\n"
        + "    o_colour = texelFetch(u_palette, ivec2(int(i), 0), 0);\n"
        + "}\n";

    /**
     * Expand of an rgb565 surface (28:486): integer bit replication, never GL's 5_6_5 unorm conversion, and alpha 255
     * (24:201). k/255 converts back to k exactly on round-to-nearest drivers (H42).
     */
    static final String EXPAND_RGB565_FS = VERSION + "uniform usampler2D u_surface;\n"
        + "layout(location = 0) out vec4 o_colour;\n"
        + "void main() {\n"
        + "    uint v = texelFetch(u_surface, ivec2(gl_FragCoord.xy), 0).r;\n"
        + "    uint r5 = v >> 11;\n"
        + "    uint g6 = (v >> 5) & 63u;\n"
        + "    uint b5 = v & 31u;\n"
        + "    uvec3 c = uvec3(r5 << 3 | r5 >> 2, g6 << 2 | g6 >> 4, b5 << 3 | b5 >> 2);\n"
        + "    o_colour = vec4(vec3(c) / 255.0, 1.0);\n"
        + "}\n";

    /** The fragment shaders, by program code. */
    static final String[] FRAGMENT = { FILL_FS, BLIT_INDEX8_FS, BLIT_RGB565_FS, TEXT_FS, EXPAND_INDEX8_FS,
        EXPAND_RGB565_FS };

    /** The scale uniform every program has. */
    static final String U_SCALE = "u_scale";

    /**
     * The uniforms each program looks up after link, {@link #U_SCALE} first; the samplers after it are set once to
     * their units with {@code glUniform1i} (C57): {@link #SAMPLER_UNITS}.
     */
    static final String[][] UNIFORMS = { { U_SCALE }, { U_SCALE, "u_tex" }, { U_SCALE, "u_tex" },
        { U_SCALE, "u_atlas" }, { U_SCALE, "u_surface", "u_palette" }, { U_SCALE, "u_surface" } };

    /** The unit of each sampler uniform in {@link #UNIFORMS} (index 0 unused): unit 1 only for the palette (H53). */
    static final int[][] SAMPLER_UNITS = { { -1 }, { -1, 0 }, { -1, 0 }, { -1, 0 }, { -1, 0, 1 }, { -1, 0 } };

    private Shaders() {}
}
