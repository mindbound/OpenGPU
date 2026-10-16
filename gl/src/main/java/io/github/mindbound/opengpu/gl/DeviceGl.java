package io.github.mindbound.opengpu.gl;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.SegmentReader;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.gl.host.DeviceBackend;

/**
 * One device's GL objects and its op executor (S8 plan §3.5-§3.9): the surface texture ({@code GL_R8UI} or {@code
 * GL_R16UI}) on its FBO, the RGBA8 present texture on its FBO, the device's textures by full handle (O7) and the
 * readback ring, each with its {@code GlLedger} rows (§3.11). Frames run through {@link SegmentReader} into {@link
 * OpQuads}, whose quads the host's {@link QuadBatch} hands back here to draw. {@link GlBackend#open} makes it and hands
 * it to {@code GlHostCore} as a {@link DeviceBackend}. GL thread, inside a pass.
 */
final class DeviceGl implements DeviceBackend, OpQuads.TextureSizes, QuadBatch.Drawer {

    /** The texture side cap (28 §6.2), inside GL 3.3's guaranteed {@code MAX_TEXTURE_SIZE}. */
    static final int MAX_TEXTURE_SIDE = 1024;

    private final GlBackend backend;
    private final GlLedger ledger;
    private final DeviceKey key;
    private final SurfaceDesc surface;
    private final int width;
    private final int height;
    private final float scaleX;
    private final float scaleY;
    private final OpQuads ops;
    private final ReadbackRing ring;
    private final Map<Integer, GlTexture> textures = new LinkedHashMap<>();
    private GlTexture surfaceTexture;
    private GlTexture presentTexture;
    private int surfaceFbo;
    private int presentFbo;
    private boolean destroyed;

    DeviceGl(GlBackend backend, DeviceKey key, SurfaceDesc surface) {
        this.backend = backend;
        this.ledger = backend.ledger();
        this.key = key;
        this.surface = surface;
        this.width = surface.width;
        this.height = surface.height;
        this.scaleX = 2f / width;
        this.scaleY = 2f / height;
        this.ops = new OpQuads(surface, backend.atlas(), this, backend.batch());
        this.ring = new ReadbackRing(
            backend,
            key.session,
            key.deviceId,
            surface.byteSize(),
            GlFormats.pixelType(surface.format));
    }

    /**
     * The surface and its FBO cleared to 0 by a quad (28:199; 24:197; 20:158), the present texture and its FBO
     * expanded once, and the ring. On a failure, what was made is deleted again.
     *
     * @throws IllegalStateException for an FBO that is not complete (20:240)
     */
    void create() {
        try {
            surfaceTexture = GlTexture.create(
                ledger,
                key.session,
                key.deviceId,
                GlLedger.SURFACE,
                GlLedger.SURFACE,
                GlFormats.surfaceInternal(surface.format),
                width,
                height,
                GlFormats.surfacePixelFormat(surface.format),
                GlFormats.pixelType(surface.format),
                surface.bytesPerPixel(),
                null);
            surfaceFbo = framebuffer(surfaceTexture.name, GlLedger.SURFACE);
            presentTexture = GlTexture.create(
                ledger,
                key.session,
                key.deviceId,
                GlLedger.PRESENT,
                GlLedger.PRESENT,
                GL11.GL_RGBA8,
                width,
                height,
                GL11.GL_RGBA,
                GL11.GL_UNSIGNED_BYTE,
                4,
                null);
            presentFbo = framebuffer(presentTexture.name, GlLedger.PRESENT);
            ring.create();
            target(surfaceFbo);
            backend.surfaceDrawn();
            QuadBatch batch = backend.batch();
            batch.begin(this);
            try {
                batch.quad(Shaders.FILL, OpQuads.NO_TEXTURE, 0, 0, width, height, 0, 0, 0, 0, 0, 0);
                batch.finish();
            } finally {
                batch.abandon();
            }
            expand();
        } catch (RuntimeException e) {
            release();
            throw e;
        }
    }

    DeviceKey key() {
        return key;
    }

    SurfaceDesc surface() {
        return surface;
    }

    int surfaceTextureName() {
        return surfaceTexture.name;
    }

    int presentTextureName() {
        return presentTexture.name;
    }

    long fetchedAvailable() {
        return ring.fetchedAvailable();
    }

    long fetchedForced() {
        return ring.fetchedForced();
    }

    // DeviceBackend

    /**
     * {@code glTexImage2D} of a new {@code GL_R8} or {@code GL_R16UI} texture (28:583-584) with the given texels.
     *
     * @throws IllegalArgumentException for a format other than the surface's (28:551) or texels of the wrong size
     * @throws IllegalStateException    for a handle the device holds
     */
    @Override
    public void createTexture(int handle, int width, int height, int format, ByteBuffer texels) {
        checkLive();
        if (format != surface.format) {
            throw new IllegalArgumentException(
                "a " + Formats.name(format) + " texture on a " + surface + " device (28:551)");
        }
        if (width < 1 || height < 1 || width > MAX_TEXTURE_SIDE || height > MAX_TEXTURE_SIDE) {
            throw new IllegalArgumentException(
                "a " + width + "x" + height + " texture has a side outside 1.." + MAX_TEXTURE_SIDE + " (28 §6.2)");
        }
        if (textures.containsKey(handle)) {
            throw new IllegalStateException(GlLedger.textureLabel(handle) + " is already on the GPU");
        }
        long stage = backend.stageNanos(texels);
        long start = System.nanoTime();
        GlTexture texture = GlTexture.create(
            ledger,
            key.session,
            key.deviceId,
            GlLedger.TEXTURE,
            GlLedger.textureLabel(handle),
            GlFormats.textureInternal(format),
            width,
            height,
            GlFormats.texturePixelFormat(format),
            GlFormats.pixelType(format),
            Formats.bytesPerPixel(format),
            texels);
        long call = System.nanoTime() - start;
        textures.put(handle, texture);
        backend.probe()
            .upload(key.session, key.deviceId, handle, (int) texture.bytes(), stage, call);
    }

    /**
     * {@code glTexSubImage2D} of a region (C32).
     *
     * @throws IllegalArgumentException for a region outside the texture or texels of the wrong size
     * @throws IllegalStateException    for a handle the device does not hold
     */
    @Override
    public void updateTexture(int handle, int x, int y, int width, int height, ByteBuffer texels) {
        checkLive();
        GlTexture texture = texture(handle);
        long stage = backend.stageNanos(texels);
        long start = System.nanoTime();
        texture.upload(x, y, width, height, texels);
        long call = System.nanoTime() - start;
        backend.probe()
            .upload(key.session, key.deviceId, handle, width * height * texture.bytesPerTexel, stage, call);
    }

    /**
     * {@code glDeleteTextures} (C33); the ledger row goes now (28:604).
     *
     * @throws IllegalStateException for a handle the device does not hold
     */
    @Override
    public void deleteTexture(int handle) {
        checkLive();
        texture(handle).delete(ledger);
        textures.remove(handle);
    }

    @Override
    public void deleteAllTextures() {
        checkLive();
        for (Iterator<GlTexture> it = textures.values()
            .iterator(); it.hasNext();) {
            it.next()
                .delete(ledger);
            it.remove();
        }
    }

    /**
     * {@code RESET} (28:600): every texture deleted, then the surface replaced in place; the surface objects stay
     * (28:150; 21:107).
     *
     * @throws IllegalArgumentException unless pixels holds exactly {@code surface.byteSize()} bytes at position 0
     */
    @Override
    public void resetSurface(ByteBuffer pixels) {
        checkLive();
        GlTexture.checkBuffer(pixels, surface.byteSize(), "RESET's pixels");
        deleteAllTextures();
        surfaceTexture.upload(0, 0, width, height, pixels);
    }

    /** Every segment in order (28:239) through the surface FBO with a (0, 0, W, H) viewport (C19). */
    @Override
    public void runFrame(FrameRecord frame) {
        checkLive();
        assert frame.intact() : frame + " changed after it was offered (28:1082)";
        target(surfaceFbo);
        backend.surfaceDrawn();
        QuadBatch batch = backend.batch();
        batch.begin(this);
        try {
            for (int i = 0; i < frame.segmentCount(); i++) {
                SegmentReader.read(frame.segment(i), ops);
            }
            batch.finish();
        } finally {
            batch.abandon();
        }
    }

    /**
     * Present := expand(surface) (H27; 28:484-488): one quad into the present FBO, the surface on unit 0 and, for
     * index8, the palette on unit 1 (H53).
     */
    @Override
    public void expand() {
        checkLive();
        target(presentFbo);
        int program;
        if (surface.format == Formats.INDEX8) {
            program = Shaders.EXPAND_INDEX8;
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, backend.paletteTextureName());
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        } else {
            program = Shaders.EXPAND_RGB565;
        }
        QuadBatch batch = backend.batch();
        batch.begin(this);
        try {
            batch.quad(program, OpQuads.SURFACE, 0, 0, width, height, 0, 0, 0, 0, 0, 0);
            batch.finish();
        } finally {
            batch.abandon();
        }
    }

    @Override
    public boolean readbackSlotFree() {
        return ring.slotFree();
    }

    @Override
    public void issueReadback(long epoch, long frameId, long pass) {
        checkLive();
        ring.issue(surfaceTexture.name, epoch, frameId, pass);
    }

    @Override
    public int fetchReadbacks(ReadbackOut out, long pass, int forceAfterPasses) {
        checkLive();
        return ring.fetch(out, pass, forceAfterPasses);
    }

    @Override
    public int readbacksInFlight() {
        return ring.inFlight();
    }

    @Override
    public void readNow(ByteBuffer out) {
        checkLive();
        ring.readNow(surfaceTexture.name, out);
    }

    /**
     * Both FBOs (C38) before any texture (R-10), then the surface, present and device textures, the PBOs and the
     * queries; every ledger row of the opening goes.
     *
     * @throws IllegalStateException if it was destroyed
     */
    @Override
    public void destroy() {
        checkLive();
        release();
        backend.destroyed(this);
    }

    // OpQuads.TextureSizes

    @Override
    public int textureWidth(int handle) {
        return texture(handle).width;
    }

    @Override
    public int textureHeight(int handle) {
        return texture(handle).height;
    }

    // QuadBatch.Drawer

    /**
     * One batch into the bound target: the program with its scale (C06, C59), the texture on unit 0 (C11), the mode's
     * VAO, the whole stream VBO's data store (C76; A12) and one draw (C83 or C85).
     */
    @Override
    public void draw(int program, int texture, ByteBuffer data, int quads) {
        if (data.position() != 0 || data.remaining() != QuadBatch.BUFFER_BYTES) {
            throw new IllegalArgumentException("the stream VBO takes the whole 64 KiB buffer (A12)");
        }
        Program p = backend.program(program);
        GL20.glUseProgram(p.id());
        GL20.glUniform2f(p.uniform(Shaders.U_SCALE), scaleX, scaleY);
        int name = textureName(texture);
        if (name != 0) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
        }
        GL30.glBindVertexArray(backend.vertexArray());
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, backend.streamBuffer());
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STREAM_DRAW);
        if (backend.mode() == QuadMode.EXPANDED) {
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, QuadBatch.VERTICES_PER_QUAD * quads);
        } else {
            GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, QuadBatch.VERTICES_PER_QUAD, quads);
        }
    }

    // Internals

    /** Binds an FBO to both targets with a (0, 0, W, H) viewport (C07, C19). */
    private void target(int framebuffer) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        GL11.glViewport(0, 0, width, height);
    }

    private int textureName(int texture) {
        if (texture == OpQuads.NO_TEXTURE) {
            return 0;
        }
        if (texture == OpQuads.ATLAS) {
            return backend.atlasTextureName();
        }
        if (texture == OpQuads.SURFACE) {
            return surfaceTexture.name;
        }
        return texture(texture).name;
    }

    private GlTexture texture(int handle) {
        GlTexture texture = textures.get(handle);
        if (texture == null) {
            throw new IllegalStateException(GlLedger.textureLabel(handle) + " is not on the GPU");
        }
        return texture;
    }

    /** {@code glGenFramebuffers} (C35) with the texture on colour attachment 0 (C36), checked complete (C37). */
    private int framebuffer(int texture, String label) {
        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glDeleteFramebuffers(fbo);
            throw new IllegalStateException(
                key + "'s " + label + " framebuffer is not complete: 0x" + Integer.toHexString(status) + " (20:240)");
        }
        ledger.add(key.session, key.deviceId, GlLedger.FBO, label, 0);
        return fbo;
    }

    /** Deletes whatever exists, FBOs first (R-10); safe on a half-made device. */
    private void release() {
        destroyed = true;
        if (surfaceFbo != 0) {
            GL30.glDeleteFramebuffers(surfaceFbo);
            ledger.remove(key.session, key.deviceId, GlLedger.FBO, GlLedger.SURFACE, 0);
            surfaceFbo = 0;
        }
        if (presentFbo != 0) {
            GL30.glDeleteFramebuffers(presentFbo);
            ledger.remove(key.session, key.deviceId, GlLedger.FBO, GlLedger.PRESENT, 0);
            presentFbo = 0;
        }
        if (surfaceTexture != null) {
            surfaceTexture.delete(ledger);
            surfaceTexture = null;
        }
        if (presentTexture != null) {
            presentTexture.delete(ledger);
            presentTexture = null;
        }
        for (GlTexture texture : textures.values()) {
            texture.delete(ledger);
        }
        textures.clear();
        ring.delete();
    }

    /** Live and inside a bracket: nothing changes GL state before enter or after exit (S8 amendments B). */
    private void checkLive() {
        if (destroyed) {
            throw new IllegalStateException(key + "'s GL objects were destroyed");
        }
        if (!backend.inBracket()) {
            throw new IllegalStateException(key + ": GL work outside a pass; call beginPass first");
        }
    }

    @Override
    public String toString() {
        return "DeviceGl[" + key + ", " + surface + "]";
    }
}
