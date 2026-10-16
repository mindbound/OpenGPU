package io.github.mindbound.opengpu.gl;

/**
 * Whether a pass runs {@link GlBackend}'s {@code glFinish} before its first PBO read ({@code finishOnce}, C100;
 * {@link ReadbackRing#fetch}): the wait that makes {@code glGetBufferSubData} return the copy on Intel's Windows driver
 * (32.0.101.8860) when a texture's storage was re-specified between a readback's copy and its fetch, as GLSM's
 * {@code glDeleteTextures} does (S8's fix loop after U6; 20:138). It waits for all the GL work queued so far, the
 * game's included, so Q-S8-3 (2026-10-16) keeps it on every driver except those verified clean.
 * {@code ReadbackRing.create}'s read of each new PBO stays on for every driver whatever this says.
 * <p>
 * Verified clean, so {@link #AUTO} leaves it off: NVIDIA (RTX 5060, driver 617.42: no stale readback in 2 759 with
 * the {@code glFinish} removed). On Intel 32.0.101.8860 the foreign-texture tests fail without it. To verify a new
 * driver, run, on it,
 * {@code ./gradlew :gl-testkit:glCompatTest --tests *ForeignTextures* --tests *FirstReadback* :gl-testkit:glsmFloorTest
 * --tests *ForeignTextures* -Popengpu.gl.readbackFinish=never}; with no failure, add its {@code GL_VENDOR} to
 * {@link #verifiedClean}.
 */
public enum ReadbackFinish {

    /** From {@code GL_VENDOR}, read once at the backend's first pass ({@link #verifiedClean}): production. */
    AUTO,
    /** On every driver: tests, and a user's override. */
    ALWAYS,
    /** On no driver: the re-verification run above. */
    NEVER;

    /**
     * Whether the driver of this {@code GL_VENDOR} string was verified to need no {@code glFinish} before a PBO read:
     * true for a vendor starting with "NVIDIA" ("NVIDIA Corporation"), false for every other (Intel, AMD's "ATI
     * Technologies Inc." and "AMD", Mesa with llvmpipe or another driver, Microsoft's, VMware's), an empty one and
     * none.
     */
    public static boolean verifiedClean(String vendor) {
        return vendor != null && vendor.startsWith("NVIDIA");
    }

    /** Whether a pass waits before its first PBO read on the driver of this {@code GL_VENDOR}. */
    public boolean finishes(String vendor) {
        switch (this) {
            case ALWAYS:
                return true;
            case NEVER:
                return false;
            default:
                return !verifiedClean(vendor);
        }
    }
}
