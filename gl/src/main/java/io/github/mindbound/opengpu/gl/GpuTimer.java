package io.github.mindbound.opengpu.gl;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/**
 * A {@code GL_TIMESTAMP} pair (C94-C98; 20 §3) around GPU work, for G13b's upload bench and the readback figures of
 * RM:43 (28 §6.7; H43): {@code glQueryCounter} at {@link #begin} and {@link #end}, availability polled without
 * blocking, both 64-bit results read with {@code glGetQueryObjectui64} (C98; never A03). The two queries are made at
 * the first {@link #begin}, with two {@code GlLedger} rows of kind {@code query} under the given label. GL thread,
 * inside a pass.
 */
public final class GpuTimer {

    private final GlLedger ledger;
    private final long session;
    private final int deviceId;
    private final String label;
    private int beginQuery;
    private int endQuery;
    private boolean ended;

    /** No GL work. */
    public GpuTimer(GlLedger ledger, long session, int deviceId, String label) {
        if (ledger == null || label == null) {
            throw new NullPointerException("ledger and label");
        }
        this.ledger = ledger;
        this.session = session;
        this.deviceId = deviceId;
        this.label = label;
    }

    /** Stamps the start; makes the queries on first use. */
    public void begin() {
        if (beginQuery == 0) {
            beginQuery = GL15.glGenQueries();
            ledger.add(session, deviceId, GlLedger.QUERY, label, 0);
            endQuery = GL15.glGenQueries();
            ledger.add(session, deviceId, GlLedger.QUERY, label, 0);
        }
        ended = false;
        GL33.glQueryCounter(beginQuery, GL33.GL_TIMESTAMP);
    }

    /**
     * Stamps the end.
     *
     * @throws IllegalStateException before {@link #begin}
     */
    public void end() {
        if (beginQuery == 0) {
            throw new IllegalStateException("end before begin");
        }
        GL33.glQueryCounter(endQuery, GL33.GL_TIMESTAMP);
        ended = true;
    }

    /** Whether the end stamp's result is available (C97), without waiting. */
    public boolean available() {
        return ended && GL15.glGetQueryObjecti(endQuery, GL15.GL_QUERY_RESULT_AVAILABLE) != GL11.GL_FALSE;
    }

    /**
     * The GPU time between the stamps; waits for the results.
     *
     * @throws IllegalStateException before {@link #end}
     */
    public long elapsedNanos() {
        if (!ended) {
            throw new IllegalStateException("no begin and end pair");
        }
        long end = GL33.glGetQueryObjectui64(endQuery, GL15.GL_QUERY_RESULT);
        long start = GL33.glGetQueryObjectui64(beginQuery, GL15.GL_QUERY_RESULT);
        return end - start;
    }

    /** Deletes both queries (C95) and their ledger rows; a no-op before the first {@link #begin}. */
    public void delete() {
        if (beginQuery != 0) {
            GL15.glDeleteQueries(beginQuery);
            ledger.remove(session, deviceId, GlLedger.QUERY, label, 0);
            GL15.glDeleteQueries(endQuery);
            ledger.remove(session, deviceId, GlLedger.QUERY, label, 0);
            beginQuery = 0;
            endQuery = 0;
            ended = false;
        }
    }
}
