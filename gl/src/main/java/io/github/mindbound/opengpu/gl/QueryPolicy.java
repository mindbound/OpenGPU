package io.github.mindbound.opengpu.gl;

/**
 * When a readback slot counts as finished (S8 amendments C, B4). A slot is always finished once it was issued
 * {@code forceAfterPasses} passes ago (24:197), and the fetch waits for the copy then (20:138; on Intel's Windows
 * driver only with {@code ReadbackRing.create}'s read of each new PBO and {@code GlBackend.finishOnce}'s
 * {@code glFinish} before the pass's first read, which {@link ReadbackFinish} keeps on for every driver not verified
 * clean), so correctness never depends on the query.
 */
public enum QueryPolicy {
    /** Also when its {@code GL_TIMESTAMP} query reports available (C97): production. */
    DRIVER,
    /**
     * Only when forced, through the real PBO path: tests, so that two readbacks in flight and the forced fetch happen
     * on drivers that finish every copy within a pass.
     */
    NEVER_READY
}
