package io.github.mindbound.opengpu.gl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@link Shaders}' sources as text (20:93-99; S8 plan §3.6, amendments E): {@code #version 330 core} first, which
 * GLSM's {@code fixupVersion} leaves alone; no identifier GLSM's {@code renameReservedWords} renames ({@code sample},
 * {@code new}, {@code sampler}, {@code texture}; 20:96-97, B13); no GLSL 1.x construct; a location on every vertex
 * input and fragment output (varyings cannot carry one in GLSL 3.30 without ARB_separate_shader_objects), and every
 * fragment input a flat output of the vertex shader; every uniform {@code Program} looks up declared, and every
 * declared one looked up, so no sampler is left without its unit.
 */
class ShaderSourceTest {

    private static final Pattern WORD = Pattern.compile("\\b(sample|new|sampler|texture)\\b");
    private static final String[] BANNED = { "gl_FragColor", "texture2D", "attribute", "varying", "gl_FragData",
        "#version 1", "precision " };
    private static final Pattern DECLARATION = Pattern.compile(
        "^\\s*(layout\\s*\\(location\\s*=\\s*(\\d+)\\)\\s*)?(flat\\s+)?(in|out|uniform)\\s+(\\w+)\\s+(\\w+)\\s*;");

    @Test
    void everySourceStartsWithVersion330Core() {
        for (Map.Entry<String, String> source : sources().entrySet()) {
            Assertions.assertTrue(
                source.getValue()
                    .startsWith("#version 330 core\n"),
                source.getKey());
            Assertions
                .assertEquals(1, count(source.getValue(), "#version"), source.getKey() + " has one #version line");
        }
    }

    @Test
    void noIdentifierGlsmRenamesAndNoGlsl1Construct() {
        for (Map.Entry<String, String> source : sources().entrySet()) {
            Matcher matcher = WORD.matcher(source.getValue());
            Assertions.assertFalse(matcher.find(), () -> source.getKey() + " uses the word " + matcher.group());
            for (String banned : BANNED) {
                Assertions.assertFalse(
                    source.getValue()
                        .contains(banned),
                    source.getKey() + " contains " + banned);
            }
        }
        // The pattern is not blind
        Assertions.assertTrue(
            WORD.matcher("vec4 c = texture(u_tex, uv);")
                .find());
        Assertions.assertTrue(
            WORD.matcher("uniform sampler s;")
                .find());
        Assertions.assertFalse(
            WORD.matcher("uniform usampler2D u_texture; uint u_sample;")
                .find());
    }

    @Test
    void everyVertexInputAndFragmentOutputHasALocation() {
        List<String[]> inputs = declarations(Shaders.QUAD_VS, "in");
        Assertions.assertEquals(3, inputs.size());
        for (String[] in : inputs) {
            Assertions.assertNotNull(in[0], "vertex input " + in[3] + " has no location");
        }
        Assertions
            .assertEquals(Arrays.asList("0 ivec4 a_rect", "1 ivec2 a_offset", "2 uvec4 a_value"), describe(inputs));
        for (int code = 0; code < Shaders.COUNT; code++) {
            List<String[]> outputs = declarations(Shaders.FRAGMENT[code], "out");
            Assertions.assertEquals(1, outputs.size(), Shaders.NAMES[code]);
            String[] out = outputs.get(0);
            Assertions.assertEquals("0", out[0], Shaders.NAMES[code] + "'s output location");
            boolean expand = code == Shaders.EXPAND_INDEX8 || code == Shaders.EXPAND_RGB565;
            // Integer surfaces take uint (R8UI, R16UI); the present texture takes vec4 (RGBA8)
            Assertions.assertEquals(expand ? "vec4" : "uint", out[2], Shaders.NAMES[code] + "'s output type");
        }
    }

    @Test
    void everyFragmentInputIsAFlatVertexOutput() {
        Set<String> vertexOutputs = new HashSet<>();
        for (String[] out : declarations(Shaders.QUAD_VS, "out")) {
            Assertions.assertEquals("flat", out[1], out[3] + " is not flat");
            vertexOutputs.add(out[2] + " " + out[3]);
        }
        Assertions.assertEquals(new HashSet<>(Arrays.asList("ivec2 v_offset", "uvec4 v_value")), vertexOutputs);
        for (int code = 0; code < Shaders.COUNT; code++) {
            for (String[] in : declarations(Shaders.FRAGMENT[code], "in")) {
                Assertions.assertEquals("flat", in[1], Shaders.NAMES[code] + "'s " + in[3] + " is not flat");
                Assertions.assertTrue(
                    vertexOutputs.contains(in[2] + " " + in[3]),
                    Shaders.NAMES[code] + "'s " + in[3] + " matches no vertex output");
            }
        }
    }

    @Test
    void everyUniformLookedUpIsDeclaredAndEveryDeclaredOneLookedUp() {
        Assertions.assertEquals(Shaders.COUNT, Shaders.NAMES.length);
        Assertions.assertEquals(Shaders.COUNT, Shaders.FRAGMENT.length);
        Assertions.assertEquals(Shaders.COUNT, Shaders.UNIFORMS.length);
        Assertions.assertEquals(Shaders.COUNT, Shaders.SAMPLER_UNITS.length);
        for (int code = 0; code < Shaders.COUNT; code++) {
            Set<String> declared = new HashSet<>();
            for (String[] uniform : declarations(Shaders.QUAD_VS, "uniform")) {
                declared.add(uniform[3]);
            }
            List<String> samplers = new ArrayList<>();
            for (String[] uniform : declarations(Shaders.FRAGMENT[code], "uniform")) {
                declared.add(uniform[3]);
                samplers.add(uniform[3]);
            }
            String name = Shaders.NAMES[code];
            Assertions.assertEquals(declared, new HashSet<>(Arrays.asList(Shaders.UNIFORMS[code])), name);
            Assertions.assertEquals(Shaders.U_SCALE, Shaders.UNIFORMS[code][0], name + " looks u_scale up first");
            Assertions.assertEquals(Shaders.UNIFORMS[code].length, Shaders.SAMPLER_UNITS[code].length, name);
            // The fragment shader's uniforms are its samplers, in UNIFORMS' order after u_scale
            Assertions.assertEquals(
                samplers,
                Arrays.asList(Shaders.UNIFORMS[code])
                    .subList(1, Shaders.UNIFORMS[code].length),
                name);
            for (int u = 1; u < Shaders.UNIFORMS[code].length; u++) {
                int unit = Shaders.SAMPLER_UNITS[code][u];
                // Unit 1 is the palette's alone (H53)
                Assertions.assertEquals(Shaders.UNIFORMS[code][u].equals("u_palette") ? 1 : 0, unit, name);
            }
        }
    }

    @Test
    void programsAndShadersAgree() {
        Assertions.assertEquals(
            Arrays.asList("fill", "blit-index8", "blit-rgb565", "text", "expand-index8", "expand-rgb565"),
            Arrays.asList(Shaders.NAMES));
        Assertions.assertSame(Shaders.FILL_FS, Shaders.FRAGMENT[Shaders.FILL]);
        Assertions.assertSame(Shaders.BLIT_INDEX8_FS, Shaders.FRAGMENT[Shaders.BLIT_INDEX8]);
        Assertions.assertSame(Shaders.BLIT_RGB565_FS, Shaders.FRAGMENT[Shaders.BLIT_RGB565]);
        Assertions.assertSame(Shaders.TEXT_FS, Shaders.FRAGMENT[Shaders.TEXT]);
        Assertions.assertSame(Shaders.EXPAND_INDEX8_FS, Shaders.FRAGMENT[Shaders.EXPAND_INDEX8]);
        Assertions.assertSame(Shaders.EXPAND_RGB565_FS, Shaders.FRAGMENT[Shaders.EXPAND_RGB565]);
        // GL_R8 textures sample as float, GL_R16UI and the integer surfaces as unsigned (28:583-584)
        Assertions.assertTrue(Shaders.BLIT_INDEX8_FS.contains("uniform sampler2D u_tex;"));
        Assertions.assertTrue(Shaders.BLIT_INDEX8_FS.contains("* 255.0 + 0.5"));
        Assertions.assertTrue(Shaders.BLIT_RGB565_FS.contains("uniform usampler2D u_tex;"));
        Assertions.assertTrue(Shaders.EXPAND_INDEX8_FS.contains("uniform usampler2D u_surface;"));
        Assertions.assertTrue(Shaders.EXPAND_RGB565_FS.contains("uniform usampler2D u_surface;"));
        Assertions.assertTrue(Shaders.TEXT_FS.contains("uniform sampler2D u_atlas;"));
    }

    /** Every shader by name. */
    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("QUAD_VS", Shaders.QUAD_VS);
        for (int code = 0; code < Shaders.COUNT; code++) {
            sources.put(Shaders.NAMES[code], Shaders.FRAGMENT[code]);
        }
        return sources;
    }

    /** The source's declarations of one storage qualifier: {location or null, "flat" or null, type, name}. */
    private static List<String[]> declarations(String source, String qualifier) {
        List<String[]> out = new ArrayList<>();
        for (String line : source.split("\n")) {
            Matcher m = DECLARATION.matcher(line);
            if (m.find() && m.group(4)
                .equals(qualifier)) {
                out.add(new String[] { m.group(2), m.group(3) == null ? null : "flat", m.group(5), m.group(6) });
            }
        }
        return out;
    }

    private static List<String> describe(List<String[]> declarations) {
        List<String> out = new ArrayList<>();
        for (String[] d : declarations) {
            out.add(d[0] + " " + d[2] + " " + d[3]);
        }
        return out;
    }

    private static int count(String s, String part) {
        int n = 0;
        for (int i = s.indexOf(part); i >= 0; i = s.indexOf(part, i + 1)) {
            n++;
        }
        return n;
    }
}
