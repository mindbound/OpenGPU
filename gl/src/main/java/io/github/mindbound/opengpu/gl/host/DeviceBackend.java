package io.github.mindbound.opengpu.gl.host;

import java.nio.ByteBuffer;

import io.github.mindbound.opengpu.core.render.FrameRecord;

/**
 * One device's GL work (S8 plan §3.5-§3.9): the GL thread only, always inside a {@link HostBackend} bracket. Every
 * buffer argument is read from position 0 to its limit, and its {@code remaining()} must equal the bytes the call
 * needs ({@code w·h·bpp}), else {@link IllegalArgumentException}: LWJGL 2 checks no size for {@code GL_RED} and
 * {@code GL_RED_INTEGER} (B9).
 */
public interface DeviceBackend {

    /** {@code glTexImage2D} of a new texture of the surface's format with these texels (zeros, or a mirror's bytes). */
    void createTexture(int handle, int width, int height, int format, ByteBuffer texels);

    /** {@code glTexSubImage2D} of a region of an existing texture. */
    void updateTexture(int handle, int x, int y, int width, int height, ByteBuffer texels);

    /** {@code glDeleteTextures}; its ledger row goes at call time (28:604). */
    void deleteTexture(int handle);

    void deleteAllTextures();

    /**
     * {@code RESET} (28 §5.7, 28:600): every texture deleted, then the surface replaced by {@code surface.byteSize()}
     * bytes.
     */
    void resetSurface(ByteBuffer pixels);

    /** Runs every segment of the frame through {@code SegmentReader}, in order (28:239), into the surface. */
    void runFrame(FrameRecord frame);

    /** Present texture := expand(surface) (H27; 28:484-488). */
    void expand();

    /** Fewer than two readbacks in flight (24:197; H36). */
    boolean readbackSlotFree();

    /**
     * Copies the surface into a free ring PBO and stamps a {@code GL_TIMESTAMP} query; {@code pass} is the host's pass
     * number.
     */
    void issueReadback(long epoch, long frameId, long pass);

    /**
     * Fetches finished readbacks in issue order, handing each to {@code out}, and returns how many. A slot is finished
     * when its query reports available (unless the backend's query policy is {@code NEVER_READY}) or when
     * {@code pass - issuedAtPass >= forceAfterPasses}; the first unfinished slot stops the fetch.
     */
    int fetchReadbacks(ReadbackOut out, long pass, int forceAfterPasses);

    int readbacksInFlight();

    /**
     * The surface now, through the production calls ({@code glGetTexImage} into a ring PBO, then
     * {@code glGetBufferSubData}, which waits). Only when {@link #readbacksInFlight()} is 0, else
     * {@link IllegalStateException}. {@code out} must have exactly the surface's byte size remaining.
     */
    void readNow(ByteBuffer out);

    /** Deletes the FBOs before their textures (R-10), then the textures, the PBOs and the queries; ledger rows gone. */
    void destroy();

    /** Receives one fetched readback; {@code pixels} is valid only during the call. */
    interface ReadbackOut {

        void accept(long epoch, long frameId, ByteBuffer pixels);
    }
}
