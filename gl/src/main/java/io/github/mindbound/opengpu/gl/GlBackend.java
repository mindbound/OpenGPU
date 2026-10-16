package io.github.mindbound.opengpu.gl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Fonts;
import io.github.mindbound.opengpu.core.colour.Palette;
import io.github.mindbound.opengpu.core.font.GlyphTable;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.gl.host.DeviceBackend;
import io.github.mindbound.opengpu.gl.host.HostBackend;

/**
 * {@code GlHostCore}'s GL backend (S8 plan §2.1, §3; H29), one per GL context: the six programs, the two VAOs and the
 * stream VBO (§3.6), the glyph atlas (§3.7), the palette texture (§3.8), the staging and readback buffers (§3.5) and
 * the pass bracket. Constructing it does no GL work; it allocates the staging buffer at its largest texture's size, so
 * {@link #staging} never allocates under a device's lock (S8 review HO-4). The first {@link #beginPass} creates the
 * shared objects on the thread that then owns every pass (H57), and reads {@code GL_VENDOR} (C02) once to resolve
 * {@link ReadbackFinish}, and {@code GL_RENDERER} (C02) for {@link #finishesBeforeCopy}. Every GL call it and its
 * devices make runs inside {@link #beginPass}/{@link #endPass}, in S8 amendments B's order: {@link PassState#enter},
 * the unpack store forced, first-time creation, the caller's work (the pack store forced once, before the first
 * readback; a {@code glFinish} once, before the first fetch reads a PBO, unless {@link ReadbackFinish} leaves it off
 * for this driver; on llvmpipe a {@code glFinish} before a readback's copy when a surface was drawn into since the last
 * one), then the pack and unpack stores restored, {@code glFlush} after an issue (C93) and {@link PassState#exit}. GL
 * thread only, but for {@link #staging}.
 */
public final class GlBackend implements HostBackend {

    /** The palette texture's bytes: 256 × 1 RGBA8 (28:490; S8 plan §3.8). */
    static final int PALETTE_BYTES = 256 * 4;
    /** The atlas's ledger label (S8 plan §3.11). */
    static final String ATLAS_LABEL = "font0";
    /**
     * The staging buffer's size from construction: the largest texture of the widest format, 1 024² rgb565 (28 §6.2,
     * :598), and so every box a device's lock copies into it (amendment D). RESET's pixels and readNow's surface, which
     * are staged outside every lock, can be larger on an untiered test surface; only they can grow it.
     */
    static final int STAGING_BYTES = DeviceGl.MAX_TEXTURE_SIDE * DeviceGl.MAX_TEXTURE_SIDE * 2;

    private final GlyphTable glyphs;
    private final QuadMode mode;
    private final GlLedger ledger;
    private final UploadProbe probe;
    private final QueryPolicy queryPolicy;
    private final ReadbackFinish readbackFinish;
    private final GlyphAtlas atlas;
    private final QuadBatch batch;
    private final PassState passState = new PassState();
    private final PixelStore pixelStore = new PixelStore();
    private final List<DeviceGl> devices = new ArrayList<>();
    private final Program[] programs = new Program[Shaders.COUNT];
    private int vaoExpanded;
    private int vaoInstanced;
    private int streamBuffer;
    private GlTexture atlasTexture;
    private GlTexture paletteTexture;
    private int maxTextureSize;
    private ByteBuffer staging;
    private long stagingHandedOutAt;
    private ByteBuffer readback;
    private boolean created;
    private boolean inPass;
    private boolean packing;
    private boolean issued;
    /** Whether this pass ran {@link #finishOnce}'s {@code glFinish} after its last issue. */
    private boolean finished;
    /** {@link #readbackFinish} resolved for the driver by the first pass. */
    private boolean finishBeforeRead;
    private String vendor;
    private long readbackFinishes;
    /** {@link #finishesBeforeCopy} of {@code GL_RENDERER}, resolved by the first pass. */
    private boolean finishBeforeCopy;
    /** Whether a device drew into its surface since this backend's last {@code glFinish}. */
    private boolean surfaceDrawn;
    private String renderer;
    private long copyFinishes;
    private boolean closed;
    /** Whether {@link #close} runs its own bracket. */
    private boolean closing;

    /** {@link QueryPolicy#DRIVER}: production. No GL work. */
    public GlBackend(GlyphTable glyphs, QuadMode mode, GlLedger ledger, UploadProbe probe) {
        this(glyphs, mode, ledger, probe, QueryPolicy.DRIVER);
    }

    /** {@link ReadbackFinish#AUTO}: production. No GL work. */
    public GlBackend(GlyphTable glyphs, QuadMode mode, GlLedger ledger, UploadProbe probe, QueryPolicy queryPolicy) {
        this(glyphs, mode, ledger, probe, queryPolicy, ReadbackFinish.AUTO);
    }

    /**
     * No GL work; the atlas's layout and texels are computed and the staging buffer allocated here.
     *
     * @throws IllegalArgumentException unless the glyphs have font 0's 6×13 cell (28 §7.3) and fit the atlas
     */
    public GlBackend(GlyphTable glyphs, QuadMode mode, GlLedger ledger, UploadProbe probe, QueryPolicy queryPolicy,
        ReadbackFinish readbackFinish) {
        if (glyphs == null || mode == null
            || ledger == null
            || probe == null
            || queryPolicy == null
            || readbackFinish == null) {
            throw new NullPointerException("glyphs, mode, ledger, probe, queryPolicy and readbackFinish");
        }
        if (glyphs.cellWidth() != Fonts.FONT_0_CELL_WIDTH || glyphs.cellHeight() != Fonts.FONT_0_CELL_HEIGHT) {
            throw new IllegalArgumentException(
                "font 0's cell is 6x13 (28 §7.3), not " + glyphs.cellWidth() + "x" + glyphs.cellHeight());
        }
        this.glyphs = glyphs;
        this.mode = mode;
        this.ledger = ledger;
        this.probe = probe;
        this.queryPolicy = queryPolicy;
        this.readbackFinish = readbackFinish;
        this.atlas = new GlyphAtlas(glyphs);
        this.batch = new QuadBatch(mode);
        this.staging = ByteBuffer.allocateDirect(STAGING_BYTES)
            .order(ByteOrder.nativeOrder());
    }

    public GlyphTable glyphs() {
        return glyphs;
    }

    public QuadMode mode() {
        return mode;
    }

    public GlLedger ledger() {
        return ledger;
    }

    public GlyphAtlas atlas() {
        return atlas;
    }

    public QueryPolicy queryPolicy() {
        return queryPolicy;
    }

    /** The policy it was made with; {@link #readbackFinish()} is what the first pass resolved it to. */
    public ReadbackFinish readbackFinishPolicy() {
        return readbackFinish;
    }

    /**
     * Whether a pass runs {@code glFinish} before its first PBO read, as the first pass resolved
     * {@link #readbackFinishPolicy()} from {@code GL_VENDOR}; false before it. For tests and logs.
     */
    public boolean readbackFinish() {
        return finishBeforeRead;
    }

    /** {@code GL_VENDOR} as the first pass read it (C02); null before it. */
    public String vendor() {
        return vendor;
    }

    /** The {@code glFinish} calls the readbacks' fetches ran (C100), for tests and RM:43's figures. */
    public long readbackFinishes() {
        return readbackFinishes;
    }

    /**
     * Whether a readback's copy waits for the surfaces drawn into before it ({@link #finishBeforeCopy}), as the first
     * pass resolved it from {@code GL_RENDERER}; false before it. For tests and logs.
     */
    public boolean copyFinish() {
        return finishBeforeCopy;
    }

    /** {@code GL_RENDERER} as the first pass read it (C02); null before it. */
    public String renderer() {
        return renderer;
    }

    /** The {@code glFinish} calls the readbacks' copies ran (C100), for tests and RM:43's figures. */
    public long copyFinishes() {
        return copyFinishes;
    }

    public UploadProbe probe() {
        return probe;
    }

    /** The pass bracket, for the hygiene tests ({@link PassState#errorsDrainedAtEnter}). */
    public PassState passState() {
        return passState;
    }

    /** Whether a {@link #beginPass} is open. */
    public boolean inPass() {
        return inPass;
    }

    // HostBackend

    /**
     * Opens the bracket: {@link PassState#enter}, the unpack store forced (G8), and on the first call the shared
     * objects and {@code GL_MAX_TEXTURE_SIZE}. A failure closes the bracket again before it propagates, and deletes
     * the shared objects made so far.
     *
     * @throws IllegalStateException if a pass is open or the backend is closed, or for a program that fails to link
     *                               (20:240)
     */
    @Override
    public void beginPass() {
        if (closed) {
            throw new IllegalStateException("the backend is closed");
        }
        if (inPass) {
            throw new IllegalStateException("a pass is already open");
        }
        passState.enter();
        try {
            pixelStore.beginUnpack();
            if (!created) {
                createShared();
            }
        } catch (RuntimeException | Error e) {
            if (pixelStore.unpacking()) {
                pixelStore.endUnpack();
            }
            passState.abort();
            throw e;
        }
        inPass = true;
        packing = false;
        issued = false;
        finished = false;
    }

    /**
     * Closes the bracket in reverse: the pack store if a readback forced it, the unpack store, {@code glFlush} if a
     * readback was issued (C93; Angelica can drop the end-of-frame flush, 20 §3), then {@link PassState#exit}.
     *
     * @throws IllegalStateException without an open pass, or naming a GL error the exit check found (H63)
     */
    @Override
    public void endPass() {
        if (!inPass) {
            throw new IllegalStateException("no pass is open");
        }
        inPass = false;
        RuntimeException failure = null;
        try {
            batch.abandon();
            if (packing) {
                packing = false;
                pixelStore.endPack();
            }
            pixelStore.endUnpack();
            if (issued) {
                GL11.glFlush();
            }
        } catch (RuntimeException e) {
            failure = e;
        }
        if (failure != null) {
            passState.abort();
            throw failure;
        }
        passState.exit();
    }

    /** {@code GL_MAX_TEXTURE_SIZE} as the first pass read it; 0 before it. */
    @Override
    public int maxSurfaceSide() {
        return maxTextureSize;
    }

    /**
     * @throws IllegalStateException    without an open pass
     * @throws IllegalArgumentException for a side over {@link #maxSurfaceSide()} (H64)
     */
    @Override
    public DeviceBackend open(DeviceKey key, SurfaceDesc surface) {
        requirePass();
        if (surface.width > maxTextureSize || surface.height > maxTextureSize) {
            throw new IllegalArgumentException(
                surface + " has a side over GL_MAX_TEXTURE_SIZE " + maxTextureSize + " (H64)");
        }
        DeviceGl device = new DeviceGl(this, key, surface);
        device.create();
        devices.add(device);
        return device;
    }

    /**
     * One direct buffer per host, at position 0 with limit {@code bytes}: the buffer allocated at construction, at
     * {@link #STAGING_BYTES}, for every size up to it, so a call under a device's lock only reslices it (amendment D;
     * S8 review HO-4). Only a larger RESET or readNow of an untiered test surface, staged outside every lock, grows it.
     * No GL work, so it may be called outside a pass.
     */
    @Override
    public ByteBuffer staging(int bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("negative staging size " + bytes);
        }
        if (staging.capacity() < bytes) {
            staging = ByteBuffer.allocateDirect(roundUp(bytes))
                .order(ByteOrder.nativeOrder());
        }
        staging.clear()
            .limit(bytes);
        stagingHandedOutAt = System.nanoTime();
        return staging;
    }

    /**
     * Destroys the devices still open and deletes every shared object, inside its own bracket unless a pass is open;
     * the ledger is then empty. Idempotent; the backend takes no pass afterwards.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (!created) {
            return;
        }
        boolean bracket = !inPass;
        if (bracket) {
            passState.enter();
            pixelStore.beginUnpack();
            closing = true;
        }
        RuntimeException failure = null;
        try {
            while (!devices.isEmpty()) {
                devices.get(devices.size() - 1)
                    .destroy();
            }
            deleteShared();
        } catch (RuntimeException e) {
            failure = e;
        }
        if (bracket) {
            closing = false;
            pixelStore.endUnpack();
            if (failure != null) {
                passState.abort();
            } else {
                passState.exit();
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    // For the root's TESR (later) and tests

    /** The device's present texture: RGBA8, row 0 the surface's top row (the TESR flips it, 28:409). */
    public int presentTexture(DeviceBackend device) {
        return own(device).presentTextureName();
    }

    /** The device's surface texture, {@code GL_R8UI} or {@code GL_R16UI}, for tests' direct reads. */
    public int surfaceTexture(DeviceBackend device) {
        return own(device).surfaceTextureName();
    }

    /** Readbacks of the device fetched with their query available, for RM:43's query-latency figures. */
    public long fetchedWithQueryAvailable(DeviceBackend device) {
        return own(device).fetchedAvailable();
    }

    /** Readbacks of the device fetched because {@code forceAfterPasses} had passed. */
    public long fetchedForced(DeviceBackend device) {
        return own(device).fetchedForced();
    }

    // For DeviceGl and ReadbackRing

    /** Whether GL work may run now: inside a pass, or inside {@link #close}'s own bracket (S8 amendments B). */
    boolean inBracket() {
        return inPass || closing;
    }

    QuadBatch batch() {
        return batch;
    }

    Program program(int code) {
        return programs[code];
    }

    /** The VAO of this backend's mode. */
    int vertexArray() {
        return mode == QuadMode.EXPANDED ? vaoExpanded : vaoInstanced;
    }

    int streamBuffer() {
        return streamBuffer;
    }

    int atlasTextureName() {
        return atlasTexture.name;
    }

    int paletteTextureName() {
        return paletteTexture.name;
    }

    /** The staging copy's time when {@code texels} is the staging buffer, else 0 (G13b, 28 §6.7). */
    long stageNanos(ByteBuffer texels) {
        return texels == staging ? System.nanoTime() - stagingHandedOutAt : 0;
    }

    /** The buffer readbacks land in, grown to the largest surface: position 0, limit {@code bytes}. */
    ByteBuffer readbackBuffer(int bytes) {
        if (readback == null || readback.capacity() < bytes) {
            readback = ByteBuffer.allocateDirect(roundUp(bytes))
                .order(ByteOrder.nativeOrder());
        }
        readback.clear()
            .limit(bytes);
        return readback;
    }

    /** Forces the pack store once per pass, before the first {@code glGetTexImage} (24:203). */
    void beginPackOnce() {
        requirePass();
        if (!packing) {
            pixelStore.beginPack();
            packing = true;
        }
    }

    /**
     * {@code glFinish} (C100) before a fetch reads a PBO, once per pass and again only after a later issue: one wait
     * covers every copy issued before it ({@link ReadbackRing#fetch}; S8's fix loop after U6). It waits for all the GL
     * work queued so far, the game's included, so a pass that fetches gives up the CPU's lead over the GPU; on a driver
     * {@link ReadbackFinish} lists as verified clean it is skipped (Q-S8-3).
     */
    void finishOnce() {
        requirePass();
        if (!finished) {
            if (finishBeforeRead) {
                GL11.glFinish();
                readbackFinishes++;
                surfaceDrawn = false;
            }
            finished = true;
        }
    }

    /** A device drew into its surface (DeviceGl's clear at creation and each frame): see {@link #finishBeforeCopy}. */
    void surfaceDrawn() {
        surfaceDrawn = true;
    }

    /**
     * {@code glFinish} (C100) before a readback's {@code glGetTexImage} of a surface into a PBO ({@link ReadbackRing}'s
     * issue and readNow), on a renderer {@link #finishesBeforeCopy} names, when a device drew into its surface since
     * this backend's last {@code glFinish}. llvmpipe copies into a PBO on the CPU, through a map of the surface for
     * reading that waits only for a scene it finds writing the surface, and its {@code lp_setup_is_resource_referenced}
     * ({@code lp_setup.c}) returns the first scene, in slot order, that names the surface at all: a scene that only
     * samples it, or merely has it bound to a sampler unit, in a lower slot hides the scene still drawing the frame, so
     * the copy takes the surface as it was before the frame (S8's fix loop on llvmpipe: ConcurrencyScenario in 4 of 6
     * runs, a readback equal to the previous frame's; CopyWaitScenario). Mesa 26.0.8, CI's 25.2.8 and main have that
     * first-match return alike. After the wait no scene draws a surface, so one call covers every copy until a device
     * draws again; the expands between the copies only sample. On every other renderer the copy stays asynchronous.
     * The fetch's {@code glFinish} ({@link #finishOnce}; {@link ReadbackFinish}, the owner's Q-S8-3) is a separate
     * wait, which this one leaves as it was. Nothing turns this one off: on a later Mesa, the standalone repro that
     * S8's docs archive, {@code docs/research/driver-checks/lp_stale_gettex.c}, re-verifies the driver before
     * {@link #finishesBeforeCopy} may leave it out.
     */
    void finishBeforeCopy() {
        requirePass();
        if (finishBeforeCopy && surfaceDrawn) {
            GL11.glFinish();
            copyFinishes++;
            surfaceDrawn = false;
        }
    }

    /**
     * Whether readbacks' copies need {@link #finishBeforeCopy}'s wait on the driver of this {@code GL_RENDERER} string:
     * true for one naming llvmpipe, its own ("llvmpipe (LLVM 21.1.8, 256 bits)"), Mesa's before 2017 ("Gallium 0.4 on
     * llvmpipe (...)") and zink's on lavapipe ("zink Vulkan 1.3(llvmpipe (...))"), which runs on llvmpipe's
     * rasterizer and is not verified either way, so it waits too, a {@code glFinish} being all it costs; false for
     * every other, an empty one and none. Public for the tests that expect it of a leg's renderer.
     */
    public static boolean finishesBeforeCopy(String renderer) {
        return renderer != null && renderer.contains("llvmpipe");
    }

    void readbackIssued() {
        issued = true;
        finished = false;
    }

    void destroyed(DeviceGl device) {
        devices.remove(device);
    }

    // First-time creation and close

    private void createShared() {
        try {
            maxTextureSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
            // Q-S8-3: the readbacks' glFinish from the driver's vendor, once per backend
            vendor = GL11.glGetString(GL11.GL_VENDOR);
            finishBeforeRead = readbackFinish.finishes(vendor);
            System.err.println(
                "[opengpu-gl] readback glFinish " + (finishBeforeRead ? "on" : "off")
                    + " ("
                    + readbackFinish
                    + ", GL_VENDOR "
                    + vendor
                    + ")");
            // llvmpipe's copy into a PBO can miss the scene still drawing the surface (finishBeforeCopy)
            renderer = GL11.glGetString(GL11.GL_RENDERER);
            finishBeforeCopy = finishesBeforeCopy(renderer);
            if (finishBeforeCopy) {
                System.err.println("[opengpu-gl] readback copy glFinish on (GL_RENDERER " + renderer + ")");
            }
            for (int code = 0; code < Shaders.COUNT; code++) {
                programs[code] = Program
                    .link(Shaders.NAMES[code], Shaders.QUAD_VS, Shaders.FRAGMENT[code], Shaders.UNIFORMS[code], ledger);
                // Sampler units once, after link (C57; 20 §2)
                GL20.glUseProgram(programs[code].id());
                for (int u = 1; u < Shaders.UNIFORMS[code].length; u++) {
                    GL20.glUniform1i(programs[code].uniform(Shaders.UNIFORMS[code][u]), Shaders.SAMPLER_UNITS[code][u]);
                }
            }
            streamBuffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, streamBuffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) QuadBatch.BUFFER_BYTES, GL15.GL_STREAM_DRAW);
            ledger.add(GlLedger.HOST_SESSION, GlLedger.HOST_DEVICE, GlLedger.VBO, "quads", QuadBatch.BUFFER_BYTES);
            vaoExpanded = vertexArray(0, "expanded");
            vaoInstanced = vertexArray(1, "instanced");
            atlasTexture = GlTexture.create(
                ledger,
                GlLedger.HOST_SESSION,
                GlLedger.HOST_DEVICE,
                GlLedger.GLYPH_ATLAS,
                ATLAS_LABEL,
                GL30.GL_R8,
                atlas.width(),
                atlas.height(),
                GL11.GL_RED,
                GL11.GL_UNSIGNED_BYTE,
                1,
                direct(atlas.texels()));
            paletteTexture = GlTexture.create(
                ledger,
                GlLedger.HOST_SESSION,
                GlLedger.HOST_DEVICE,
                GlLedger.PALETTE,
                "default",
                GL11.GL_RGBA8,
                256,
                1,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                4,
                direct(paletteBytes(Palette.DEFAULT)));
            created = true;
        } catch (RuntimeException | Error e) {
            deleteShared();
            throw e;
        }
    }

    /**
     * A VAO reading the stream VBO's 20-byte records (S8 plan §3.6): attribute 0 {@code ivec4 a_rect} at 0, 1
     * {@code ivec2 a_offset} at 8, 2 {@code uvec4 a_value} at 12, all integer (C82) and enabled (C79; attribute 0 is
     * an enabled array, 20:104), with divisor 1 for the instanced form (C87).
     */
    private int vertexArray(int divisor, String label) {
        int vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, streamBuffer);
        GL30.glVertexAttribIPointer(0, 4, GL11.GL_SHORT, QuadBatch.RECORD_BYTES, 0L);
        GL30.glVertexAttribIPointer(1, 2, GL11.GL_SHORT, QuadBatch.RECORD_BYTES, 8L);
        GL30.glVertexAttribIPointer(2, 4, GL11.GL_UNSIGNED_SHORT, QuadBatch.RECORD_BYTES, 12L);
        for (int index = 0; index < 3; index++) {
            GL20.glEnableVertexAttribArray(index);
            if (divisor != 0) {
                GL33.glVertexAttribDivisor(index, divisor);
            }
        }
        ledger.add(GlLedger.HOST_SESSION, GlLedger.HOST_DEVICE, GlLedger.VAO, label, 0);
        return vao;
    }

    /** Deletes whatever shared object exists, with its ledger row. */
    private void deleteShared() {
        created = false;
        for (int code = 0; code < Shaders.COUNT; code++) {
            if (programs[code] != null) {
                programs[code].delete(ledger);
                programs[code] = null;
            }
        }
        if (vaoExpanded != 0) {
            GL30.glDeleteVertexArrays(vaoExpanded);
            ledger.remove(GlLedger.HOST_SESSION, GlLedger.HOST_DEVICE, GlLedger.VAO, "expanded", 0);
            vaoExpanded = 0;
        }
        if (vaoInstanced != 0) {
            GL30.glDeleteVertexArrays(vaoInstanced);
            ledger.remove(GlLedger.HOST_SESSION, GlLedger.HOST_DEVICE, GlLedger.VAO, "instanced", 0);
            vaoInstanced = 0;
        }
        if (streamBuffer != 0) {
            GL15.glDeleteBuffers(streamBuffer);
            ledger.remove(GlLedger.HOST_SESSION, GlLedger.HOST_DEVICE, GlLedger.VBO, "quads", QuadBatch.BUFFER_BYTES);
            streamBuffer = 0;
        }
        if (atlasTexture != null) {
            atlasTexture.delete(ledger);
            atlasTexture = null;
        }
        if (paletteTexture != null) {
            paletteTexture.delete(ledger);
            paletteTexture = null;
        }
    }

    private DeviceGl own(DeviceBackend device) {
        if (!(device instanceof DeviceGl) || !devices.contains(device)) {
            throw new IllegalArgumentException(device + " is no open device of this backend");
        }
        return (DeviceGl) device;
    }

    private void requirePass() {
        if (!inPass) {
            throw new IllegalStateException("GL work outside a pass: call beginPass first (S8 amendments B)");
        }
    }

    /** The palette as RGBA8 bytes: R, G, B and 255 per entry (28:490; 24:201). */
    static byte[] paletteBytes(Palette palette) {
        byte[] bytes = new byte[PALETTE_BYTES];
        for (int i = 0; i < 256; i++) {
            int argb = palette.argb(i);
            bytes[4 * i] = (byte) (argb >>> 16);
            bytes[4 * i + 1] = (byte) (argb >>> 8);
            bytes[4 * i + 2] = (byte) argb;
            bytes[4 * i + 3] = (byte) 0xFF;
        }
        return bytes;
    }

    private static ByteBuffer direct(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length)
            .order(ByteOrder.nativeOrder());
        buffer.put(bytes)
            .flip();
        return buffer;
    }

    /** To a whole 64 KiB, so a slowly growing size does not reallocate each time. */
    private static int roundUp(int bytes) {
        int unit = 64 * 1024;
        return Math.max(unit, (bytes + unit - 1) / unit * unit);
    }
}
