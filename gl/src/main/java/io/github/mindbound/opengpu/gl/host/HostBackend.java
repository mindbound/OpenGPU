package io.github.mindbound.opengpu.gl.host;

import java.nio.ByteBuffer;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.render.DeviceKey;

/**
 * The GL backend as {@code GlHostCore} sees it (S8, H29): every method runs on the thread that runs
 * {@code GlHostCore.pass}, the GL thread (H57). Every call but {@link #maxSurfaceSide()}, {@link #staging(int)} and
 * {@link #close()} (which opens its own bracket) runs between {@link #beginPass()} and {@link #endPass()}, which
 * bracket all GL state OpenGPU changes (20 §1; 24:197).
 */
public interface HostBackend {

    /**
     * Opens the bracket, in this order: {@code PassState.enter} (R-3's error drain, the saves, {@code glPushAttrib},
     * the forces, both pixel-buffer bindings unbound), the unpack pixel store forced (G8, 24:203), then, on the first
     * call only, the shared objects created (programs, VAOs, the stream VBO, the glyph atlas, the palette) and
     * {@code GL_MAX_TEXTURE_SIZE} read. Throws {@link IllegalStateException} for a program that fails to compile or
     * link (20:240).
     */
    void beginPass();

    /**
     * Closes the bracket in reverse: the pack store restored if this pass forced it (an issue or a readNow), the unpack
     * store restored, {@code glFlush} (C93) if a readback was issued, then {@code PassState.exit}, which checks
     * {@code glGetError} after the first pass with work (every pass in dev builds) and throws
     * {@link IllegalStateException} naming the error.
     */
    void endPass();

    /** {@code GL_MAX_TEXTURE_SIZE} as read at the first {@link #beginPass()} (at least 1 024, GL 3.3); 0 before it. */
    int maxSurfaceSide();

    /**
     * Creates one device's GL objects: the surface texture and its FBO, cleared to 0 by a quad (28:199; 24:197), the
     * RGBA8 present texture and its FBO, expanded once, and the readback ring; their {@code GlLedger} rows. The caller
     * has checked both sides against {@link #maxSurfaceSide()} (H64). Throws {@link IllegalStateException} for an FBO
     * that is not complete (20:240).
     */
    DeviceBackend open(DeviceKey key, SurfaceDesc surface);

    /** A direct buffer of at least {@code bytes}, position 0, limit {@code bytes}; valid until the next call. */
    ByteBuffer staging(int bytes);

    /** Deletes every shared object (inside its own bracket); the ledger's host rows are then gone. */
    void close();
}
