package io.github.mindbound.opengpu.gltestkit.host;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/**
 * 23:59's state-hygiene assertion with 26's additions (G8's pixel store; 26:73-81) and R-1's scissor box: a start
 * state, recorded, and {@link #diff()}, which reads every item again and lists each that changed. Every call goes
 * through the loader the caller runs under, so on the GLSM legs GLSM's cache sees the start and answers the reads
 * (H30); there {@link #glsmCrossCheck} adds GLSM's cache against the driver to every diff. The generic attribute
 * values of V1 (26:87) are compared by the compatibility leg's test code only (H66). GL thread only; GL-calling code
 * in plan §0's form: GL11-GL33 static calls that GLSM maps, no lambdas, no GL work in a constructor or at class load.
 */
public final class GlHygiene {

    /** S8 plan §3.3's viewport and scissor box: neither the window's nor any surface's. */
    public static final int[] VIEWPORT = { 3, 5, 17, 11 };
    public static final int[] SCISSOR = { 1, 2, 7, 9 };
    /** The hostile pixel store, for pack and unpack alike (26:81): alignment 8, row length 7, skips, swap, LSB. */
    static final int[] STORE_VALUES = { 8, 7, 2, 1, GL11.GL_TRUE, GL11.GL_TRUE };
    private static final int[] DEFAULT_STORE = { 4, 0, 0, 0, GL11.GL_FALSE, GL11.GL_FALSE };
    private static final int[] UNPACK = { GL11.GL_UNPACK_ALIGNMENT, GL11.GL_UNPACK_ROW_LENGTH,
        GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_UNPACK_SKIP_ROWS, GL11.GL_UNPACK_SWAP_BYTES, GL11.GL_UNPACK_LSB_FIRST };
    private static final int[] PACK = { GL11.GL_PACK_ALIGNMENT, GL11.GL_PACK_ROW_LENGTH, GL11.GL_PACK_SKIP_PIXELS,
        GL11.GL_PACK_SKIP_ROWS, GL11.GL_PACK_SWAP_BYTES, GL11.GL_PACK_LSB_FIRST };
    private static final String[] STORE_NAMES = { "alignment", "row length", "skip pixels", "skip rows", "swap bytes",
        "LSB first" };
    /** The capabilities diff reads; the start turns all on but dither. */
    private static final int[] CAPABILITIES = { GL11.GL_BLEND, GL11.GL_SCISSOR_TEST, GL11.GL_CULL_FACE,
        GL11.GL_DEPTH_TEST, GL11.GL_STENCIL_TEST, GL11.GL_POLYGON_OFFSET_FILL, GL11.GL_COLOR_LOGIC_OP, GL11.GL_DITHER,
        GL11.GL_ALPHA_TEST, GL30.GL_CLIP_DISTANCE0, GL30.GL_RASTERIZER_DISCARD };
    private static final String[] CAPABILITY_NAMES = { "GL_BLEND", "GL_SCISSOR_TEST", "GL_CULL_FACE", "GL_DEPTH_TEST",
        "GL_STENCIL_TEST", "GL_POLYGON_OFFSET_FILL", "GL_COLOR_LOGIC_OP", "GL_DITHER", "GL_ALPHA_TEST",
        "GL_CLIP_DISTANCE0", "GL_RASTERIZER_DISCARD" };
    /** Units 0-3's textures (H45; 24:197) and units 0-1's samplers (H53). */
    private static final int UNITS = 4;
    private static final int SAMPLER_UNITS = 2;
    /** The clear probe's colour in 1/255 steps, which convert exactly; its target's side. */
    private static final int[] CLEAR_RGBA = { 51, 102, 153, 204 };
    private static final int PROBE_SIDE = 8;

    /** The GLSM legs' check of GLSM's cache against the driver (GlsmExtension's), set by GlsmScenarioTest. */
    private static volatile Callable<List<String>> crossCheck;

    private final boolean hostile;
    private final Map<String, String> before;
    private final int[] objects;
    /** The viewport and scissor box before the start, which undo puts back: the window's, whatever its size. */
    private final int[] viewport;
    private final int[] scissor;
    private boolean undone;

    private GlHygiene(boolean hostile, Map<String, String> before, int[] objects, int[] viewport, int[] scissor) {
        this.hostile = hostile;
        this.before = before;
        this.objects = objects;
        this.viewport = viewport;
        this.scissor = scissor;
    }

    /**
     * The GLSM legs only, through reflection on the class GlsmLoader defined: GLSM's cache against the driver, which
     * {@link #diff()} then adds to its own items (S8 plan §4.5), and the GLSM labels (GL_CLIP_DISTANCE0's, which
     * GLSM caches and sends to the driver too, S8 review HY-2). Null turns it off.
     */
    public static void glsmCrossCheck(Callable<List<String>> check) {
        crossCheck = check;
    }

    /** Whether {@link #glsmCrossCheck} is set: this class runs under GLSM. */
    public static boolean onGlsm() {
        return crossCheck != null;
    }

    /**
     * S8 plan §3.3's MC-like start with S8 amendments E's values that bite, recorded: a framebuffer of its own on
     * {@code GL_FRAMEBUFFER} (as {@code framebufferMc} at START, 20:50), a program in use, a VAO of its own with an
     * array buffer, a pack and an unpack PBO bound, textures of its own on units 0-3 with {@code GL_LINEAR} samplers on
     * units 0-1 and unit 2 active, viewport (3, 5, 17, 11), the scissor test on with box (1, 2, 7, 9) (R-1), blend
     * (ZERO, ONE), logic op INVERT, cull FRONT_AND_BACK, colour mask (false, true, true, false), depth test GREATER
     * with the mask off, stencil, polygon offset fill, alpha test NEVER, {@code GL_CLIP_DISTANCE0} (on the GLSM legs
     * GLSM's clip plane 0, which GLSM caches and also sends to the driver), polygon mode LINE,
     * {@code GL_RASTERIZER_DISCARD} (H70) and both pixel stores at
     * alignment 8, row length 7, skip pixels 2, skip rows 1, swap bytes and LSB first (26:81). GL thread.
     *
     * @throws IllegalStateException naming the items that did not take, so a start that does not bite fails
     */
    public static GlHygiene mcLikeStart() {
        int[] viewport = fourInts(GL11.GL_VIEWPORT);
        int[] scissor = fourInts(GL11.GL_SCISSOR_BOX);
        int[] objects = new int[7];
        // Objects first: a null glTexImage2D with an unpack PBO bound would take the PBO's offset path
        int texture = GL11.glGenTextures();
        objects[0] = texture;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            GL11.GL_RGBA8,
            4,
            4,
            0,
            GL11.GL_RGBA,
            GL11.GL_UNSIGNED_BYTE,
            (ByteBuffer) null);
        int framebuffer = GL30.glGenFramebuffers();
        objects[1] = framebuffer;
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        int program = trivialProgram();
        objects[2] = program;
        GL20.glUseProgram(program);
        int vertexArray = GL30.glGenVertexArrays();
        objects[3] = vertexArray;
        GL30.glBindVertexArray(vertexArray);
        int arrayBuffer = GL15.glGenBuffers();
        objects[4] = arrayBuffer;
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 64L, GL15.GL_STREAM_DRAW);
        // Samplers that leave integer textures incomplete for texelFetch on strict drivers
        int sampler = GL33.glGenSamplers();
        objects[5] = sampler;
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        for (int unit = 0; unit < UNITS; unit++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            if (unit < SAMPLER_UNITS) {
                GL33.glBindSampler(unit, sampler);
            }
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        GL11.glViewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(SCISSOR[0], SCISSOR[1], SCISSOR[2], SCISSOR[3]);
        // Render state that bites when a pass does not force it (S8 amendments E, B6)
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_ZERO, GL11.GL_ONE);
        GL11.glEnable(GL11.GL_COLOR_LOGIC_OP);
        GL11.glLogicOp(GL11.GL_INVERT);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_FRONT_AND_BACK);
        GL11.glColorMask(false, true, true, false);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_GREATER);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glEnable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_NEVER, 1f);
        GL11.glEnable(GL30.GL_CLIP_DISTANCE0);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);
        GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
        // The pixel buffers and stores last (B1; 26:81)
        int unpackBuffer = GL15.glGenBuffers();
        objects[6] = unpackBuffer;
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpackBuffer);
        GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, 64L, GL15.GL_STREAM_DRAW);
        int packBuffer = GL15.glGenBuffers();
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
        GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, 64L, GL15.GL_STREAM_READ);
        int[] all = new int[objects.length + 1];
        System.arraycopy(objects, 0, all, 0, objects.length);
        all[objects.length] = packBuffer;
        for (int i = 0; i < UNPACK.length; i++) {
            GL11.glPixelStorei(UNPACK[i], STORE_VALUES[i]);
            GL11.glPixelStorei(PACK[i], STORE_VALUES[i]);
        }
        GlHygiene start = new GlHygiene(true, read(), all, viewport, scissor);
        List<String> missed = start.missedHostile();
        if (!missed.isEmpty()) {
            start.undo();
            throw new IllegalStateException("the MC-like start did not take: " + missed);
        }
        return start;
    }

    /**
     * The current state recorded and left as it is: S8 amendments E's "GLSM defaults" start on the GLSM legs
     * (program 0, GLSM's default VAO, nothing else changed) and the context's defaults on the compatibility leg. GL
     * thread.
     */
    public static GlHygiene current() {
        return new GlHygiene(false, read(), new int[0], null, null);
    }

    /** Whether this is {@link #mcLikeStart()}'s start. */
    public boolean hostile() {
        return hostile;
    }

    /** The recorded start: item name to value, in read order. */
    public Map<String, String> before() {
        return Collections.unmodifiableMap(before);
    }

    /**
     * Every item read again: "name: before x, now y" for each that changed, a GL error if one is pending, and on the
     * GLSM legs each item where GLSM's cache and the driver disagree. Empty when clean. GL thread.
     */
    public List<String> diff() {
        List<String> out = new ArrayList<>();
        Map<String, String> now = read();
        for (Map.Entry<String, String> item : before.entrySet()) {
            String value = now.get(item.getKey());
            if (!item.getValue()
                .equals(value)) {
                out.add(item.getKey() + ": before " + item.getValue() + ", now " + value);
            }
        }
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            out.add(String.format(Locale.ROOT, "GL error 0x%04X pending", error));
        }
        Callable<List<String>> check = crossCheck;
        if (check != null) {
            try {
                for (String drift : check.call()) {
                    out.add("GLSM's cache against the driver: " + drift);
                }
            } catch (Exception e) {
                out.add("GLSM's cache check failed: " + e);
            }
        }
        return out;
    }

    /** Each item of the recorded start against the state now, as {@link #diff()} lists them; for an undo's check. */
    public static List<String> compare(Map<String, String> expected) {
        List<String> out = new ArrayList<>();
        Map<String, String> now = read();
        for (Map.Entry<String, String> item : expected.entrySet()) {
            String value = now.get(item.getKey());
            if (!item.getValue()
                .equals(value)) {
                out.add(item.getKey() + ": expected " + item.getValue() + ", now " + value);
            }
        }
        return out;
    }

    /** The state now, item name to value: what {@link #diff()} compares. GL thread. */
    public static Map<String, String> snapshot() {
        return read();
    }

    /**
     * {@link #mcLikeStart()}'s state back to the context's defaults, every object it made deleted; nothing for
     * {@link #current()}. Idempotent. GL thread.
     */
    public void undo() {
        if (undone || !hostile) {
            undone = true;
            return;
        }
        undone = true;
        for (int i = 0; i < UNPACK.length; i++) {
            GL11.glPixelStorei(UNPACK[i], DEFAULT_STORE[i]);
            GL11.glPixelStorei(PACK[i], DEFAULT_STORE[i]);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
        GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        GL11.glDisable(GL30.GL_CLIP_DISTANCE0);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_ALWAYS, 0f);
        GL11.glDisable(GL11.GL_POLYGON_OFFSET_FILL);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDepthMask(true);
        GL11.glDepthFunc(GL11.GL_LESS);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glColorMask(true, true, true, true);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glLogicOp(GL11.GL_COPY);
        GL11.glDisable(GL11.GL_COLOR_LOGIC_OP);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ZERO);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(scissor[0], scissor[1], scissor[2], scissor[3]);
        GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        for (int unit = UNITS - 1; unit >= 0; unit--) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            if (unit < SAMPLER_UNITS) {
                GL33.glBindSampler(unit, 0);
            }
        }
        GL33.glDeleteSamplers(objects[5]);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        GL15.glDeleteBuffers(objects[4]);
        GL30.glDeleteVertexArrays(objects[3]);
        GL20.glUseProgram(0);
        GL20.glDeleteProgram(objects[2]);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        GL30.glDeleteFramebuffers(objects[1]);
        GL11.glDeleteTextures(objects[0]);
        GL15.glDeleteBuffers(objects[6]);
        GL15.glDeleteBuffers(objects[7]);
    }

    /**
     * S8 amendments E's check after a pass from GLSM's defaults: one {@code glClear} through the loader's GL (GLSM on
     * its legs) into an RGBA8 framebuffer of its own, with a set clear colour, read back whole; with {@code quad}, then
     * one fixed-function quad through the client-array path ({@code glEnableClientState}, {@code glVertexPointer} of
     * a {@code FloatBuffer}, {@code glDrawArrays}; GLSM 2.2.8 emulates it on a core context) over the target's left
     * half, read back again. The context's defaults are put back after. Returns the differences, empty when both hold.
     * GL thread.
     */
    public static List<String> clearProbe(boolean quad) {
        List<String> out = new ArrayList<>();
        int[] viewport = fourInts(GL11.GL_VIEWPORT);
        int texture = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            GL11.GL_RGBA8,
            PROBE_SIDE,
            PROBE_SIDE,
            0,
            GL11.GL_RGBA,
            GL11.GL_UNSIGNED_BYTE,
            (ByteBuffer) null);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        int framebuffer = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        try {
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                out.add(String.format(Locale.ROOT, "the clear probe's framebuffer is incomplete: 0x%04X", status));
                return out;
            }
            GL11.glViewport(0, 0, PROBE_SIDE, PROBE_SIDE);
            GL11.glClearColor(CLEAR_RGBA[0] / 255f, CLEAR_RGBA[1] / 255f, CLEAR_RGBA[2] / 255f, CLEAR_RGBA[3] / 255f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            byte[] cleared = GlReads.texture(texture, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 4 * PROBE_SIDE * PROBE_SIDE);
            expectImage(out, "glClear", cleared, PROBE_SIDE, CLEAR_RGBA);
            if (quad) {
                FloatBuffer vertices = ByteBuffer.allocateDirect(12 * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
                // Two triangles over x in [-1, 0]: columns 0-3 of 8, every row
                float[] xy = { -1f, -1f, 0f, -1f, -1f, 1f, -1f, 1f, 0f, -1f, 0f, 1f };
                for (int i = 0; i < xy.length; i++) {
                    vertices.put(i, xy[i]);
                }
                vertices.clear();
                // The current colour is the caller's: on NVIDIA's compatibility profile it aliases generic attribute 3,
                // which GlScenarioTest's V1 check (26:87) holds fixed around every scenario, so it is put back below
                FloatBuffer colour = ByteBuffer.allocateDirect(64)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
                GL11.glGetFloat(GL11.GL_CURRENT_COLOR, colour);
                GL11.glColor4f(1f, 0f, 0f, 1f);
                GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
                GL11.glVertexPointer(2, 0, vertices);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
                GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
                GL11.glColor4f(colour.get(0), colour.get(1), colour.get(2), colour.get(3));
                byte[] drawn = GlReads
                    .texture(texture, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 4 * PROBE_SIDE * PROBE_SIDE);
                byte[] left = new byte[drawn.length];
                for (int y = 0; y < PROBE_SIDE; y++) {
                    for (int x = 0; x < PROBE_SIDE; x++) {
                        int i = 4 * (y * PROBE_SIDE + x);
                        int[] want = x < PROBE_SIDE / 2 ? new int[] { 255, 0, 0, 255 } : CLEAR_RGBA;
                        for (int c = 0; c < 4; c++) {
                            left[i + c] = (byte) want[c];
                        }
                    }
                }
                int at = firstDifference(left, drawn);
                if (at >= 0) {
                    out.add(
                        String.format(
                            Locale.ROOT,
                            "the fixed-function quad: pixel (%d, %d) byte %d is %d, expected %d",
                            at / 4 % PROBE_SIDE,
                            at / 4 / PROBE_SIDE,
                            at % 4,
                            drawn[at] & 0xFF,
                            left[at] & 0xFF));
                }
            }
        } finally {
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL30.glDeleteFramebuffers(framebuffer);
            GL11.glDeleteTextures(texture);
        }
        int error = GL11.glGetError();
        if (error != GL11.GL_NO_ERROR) {
            out.add(String.format(Locale.ROOT, "GL error 0x%04X after the clear probe", error));
        }
        return out;
    }

    // The items

    /** Every compared item, read through the caller's loader; the active unit is put back after the per-unit reads. */
    private static Map<String, String> read() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("draw framebuffer", String.valueOf(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)));
        s.put("read framebuffer", String.valueOf(GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)));
        s.put("program", String.valueOf(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)));
        s.put("vertex array", String.valueOf(GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)));
        s.put("array buffer", String.valueOf(GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)));
        s.put("pack buffer", String.valueOf(GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING)));
        s.put("unpack buffer", String.valueOf(GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING)));
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        s.put("active texture", "GL_TEXTURE" + (active - GL13.GL_TEXTURE0));
        for (int unit = 0; unit < UNITS; unit++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            s.put("unit " + unit + " texture", String.valueOf(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)));
            if (unit < SAMPLER_UNITS) {
                s.put("unit " + unit + " sampler", String.valueOf(GL11.glGetInteger(GL33.GL_SAMPLER_BINDING)));
            }
        }
        GL13.glActiveTexture(active);
        s.put("viewport", ints(GL11.GL_VIEWPORT, 4));
        s.put("scissor box", ints(GL11.GL_SCISSOR_BOX, 4));
        boolean glsm = onGlsm();
        for (int i = 0; i < CAPABILITIES.length; i++) {
            String name = CAPABILITY_NAMES[i];
            if (glsm && CAPABILITIES[i] == GL30.GL_CLIP_DISTANCE0) {
                name += " (GLSM's clip plane, also sent to the driver)";
            }
            s.put(name, String.valueOf(GL11.glIsEnabled(CAPABILITIES[i])));
        }
        s.put(
            "blend func",
            GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB) + ", "
                + GL11.glGetInteger(GL14.GL_BLEND_DST_RGB)
                + ", "
                + GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA)
                + ", "
                + GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA));
        s.put("logic op", String.valueOf(GL11.glGetInteger(GL11.GL_LOGIC_OP_MODE)));
        s.put("cull face", String.valueOf(GL11.glGetInteger(GL11.GL_CULL_FACE_MODE)));
        s.put("depth func", String.valueOf(GL11.glGetInteger(GL11.GL_DEPTH_FUNC)));
        s.put("depth mask", booleans(GL11.GL_DEPTH_WRITEMASK, 1));
        s.put("colour mask", booleans(GL11.GL_COLOR_WRITEMASK, 4));
        // The first value only: a core context or GLSM's cache may give one value where a compatibility one gives two;
        // glPolygonMode with GL_FRONT_AND_BACK sets both alike
        s.put("polygon mode", ints(GL11.GL_POLYGON_MODE, 1));
        for (int i = 0; i < UNPACK.length; i++) {
            s.put("unpack " + STORE_NAMES[i], String.valueOf(GL11.glGetInteger(UNPACK[i])));
            s.put("pack " + STORE_NAMES[i], String.valueOf(GL11.glGetInteger(PACK[i])));
        }
        return s;
    }

    /** The items of {@link #mcLikeStart()} that do not hold its values. */
    private List<String> missedHostile() {
        List<String> missed = new ArrayList<>();
        expect(missed, "active texture", "GL_TEXTURE2");
        expect(missed, "viewport", VIEWPORT[0] + ", " + VIEWPORT[1] + ", " + VIEWPORT[2] + ", " + VIEWPORT[3]);
        expect(missed, "scissor box", SCISSOR[0] + ", " + SCISSOR[1] + ", " + SCISSOR[2] + ", " + SCISSOR[3]);
        for (Map.Entry<String, String> item : before.entrySet()) {
            if (item.getKey()
                .startsWith("GL_")
                && !item.getKey()
                    .startsWith("GL_DITHER")) {
                expect(missed, item.getKey(), "true");
            }
        }
        expect(missed, "colour mask", "false, true, true, false");
        expect(missed, "depth mask", "false");
        expect(missed, "polygon mode", String.valueOf(GL11.GL_LINE));
        for (int i = 0; i < STORE_NAMES.length; i++) {
            expect(missed, "unpack " + STORE_NAMES[i], String.valueOf(STORE_VALUES[i]));
            expect(missed, "pack " + STORE_NAMES[i], String.valueOf(STORE_VALUES[i]));
        }
        for (String name : new String[] { "draw framebuffer", "program", "vertex array", "array buffer", "pack buffer",
            "unpack buffer", "unit 0 texture", "unit 3 texture", "unit 0 sampler", "unit 1 sampler" }) {
            if ("0".equals(before.get(name))) {
                missed.add(name + " is 0");
            }
        }
        return missed;
    }

    private void expect(List<String> missed, String name, String value) {
        if (!value.equals(before.get(name))) {
            missed.add(name + " is " + before.get(name) + ", not " + value);
        }
    }

    /** n values of an integer query, read with absolute gets from a cleared 16-element buffer (R-2). */
    private static int[] fourInts(int pname) {
        IntBuffer buffer = ByteBuffer.allocateDirect(64)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer();
        GL11.glGetInteger(pname, buffer);
        return new int[] { buffer.get(0), buffer.get(1), buffer.get(2), buffer.get(3) };
    }

    private static String ints(int pname, int n) {
        IntBuffer buffer = ByteBuffer.allocateDirect(64)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer();
        GL11.glGetInteger(pname, buffer);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < n; i++) {
            text.append(i == 0 ? "" : ", ")
                .append(buffer.get(i));
        }
        return text.toString();
    }

    private static String booleans(int pname, int n) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(16)
            .order(ByteOrder.nativeOrder());
        GL11.glGetBoolean(pname, buffer);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < n; i++) {
            text.append(i == 0 ? "" : ", ")
                .append(buffer.get(i) != 0);
        }
        return text.toString();
    }

    private static void expectImage(List<String> out, String what, byte[] image, int side, int[] rgba) {
        for (int i = 0; i < side * side * 4; i++) {
            if ((image[i] & 0xFF) != rgba[i % 4]) {
                out.add(
                    String.format(
                        Locale.ROOT,
                        "%s: pixel (%d, %d) byte %d is %d, expected %d",
                        what,
                        i / 4 % side,
                        i / 4 / side,
                        i % 4,
                        image[i] & 0xFF,
                        rgba[i % 4]));
                return;
            }
        }
    }

    private static int firstDifference(byte[] expected, byte[] actual) {
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                return i;
            }
        }
        return -1;
    }

    /** A program that links on both contexts; it never draws (the start's own state only). */
    private static int trivialProgram() {
        int vs = shader(GL20.GL_VERTEX_SHADER, "#version 330 core\nvoid main() { gl_Position = vec4(0.0); }\n");
        int fs = shader(
            GL20.GL_FRAGMENT_SHADER,
            "#version 330 core\nout vec4 o_colour;\nvoid main() { o_colour = vec4(1.0); }\n");
        int id = GL20.glCreateProgram();
        GL20.glAttachShader(id, vs);
        GL20.glAttachShader(id, fs);
        GL20.glLinkProgram(id);
        GL20.glDetachShader(id, vs);
        GL20.glDetachShader(id, fs);
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        if (GL20.glGetProgrami(id, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
            GL20.glDeleteProgram(id);
            throw new IllegalStateException("the MC-like start's program failed to link");
        }
        return id;
    }

    private static int shader(int type, String source) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, source);
        GL20.glCompileShader(id);
        return id;
    }

    @Override
    public String toString() {
        return (hostile ? "GlHygiene.mcLikeStart" : "GlHygiene.current") + before;
    }
}
