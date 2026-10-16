package io.github.mindbound.opengpu.gl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The quads of one (program, texture) pair as 20-byte vertex records (S8 plan §3.6), in one 64 KiB client buffer
 * [proposal] that the stream VBO always takes whole (A12; H69). A change of program or texture, or a full buffer, hands
 * the batch to the {@link Drawer} and starts a new one; GL draws the primitives of one draw and of successive draws in
 * order, every op overwrites or discards and none reads the surface, so batching cannot change a pixel [I: 23 §3.1]. No
 * GL call: the drawer makes them. Not thread-safe; one per host, used on the GL thread.
 * <p>
 * The record, in native byte order as the GPU reads vertex data: at 0 four i16 (x0, y0, x1, y1), at 8 two i16 (ox, oy),
 * at 12 four u16 (v0-v3). EXPANDED writes it six times, one per vertex; INSTANCED once (28:415; G16).
 */
final class QuadBatch implements OpQuads.QuadSink {

    /** The stream VBO's data store: every flush uploads exactly this many bytes (A12). */
    static final int BUFFER_BYTES = 64 * 1024;
    static final int RECORD_BYTES = 20;
    static final int VERTICES_PER_QUAD = 6;

    /** Draws one batch into the bound target. */
    interface Drawer {

        /**
         * @param data  the whole buffer, position 0 and limit {@link #BUFFER_BYTES}, the batch at its start
         * @param quads at least 1
         */
        void draw(int program, int texture, ByteBuffer data, int quads);
    }

    private final QuadMode mode;
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_BYTES)
        .order(ByteOrder.nativeOrder());
    private final int bytesPerQuad;
    private final int capacity;
    private Drawer drawer;
    private int program = -1;
    private int texture;
    private int quads;
    private long draws;

    QuadBatch(QuadMode mode) {
        this.mode = mode;
        this.bytesPerQuad = mode == QuadMode.EXPANDED ? RECORD_BYTES * VERTICES_PER_QUAD : RECORD_BYTES;
        this.capacity = BUFFER_BYTES / bytesPerQuad;
    }

    QuadMode mode() {
        return mode;
    }

    /** 120 (EXPANDED) or 20 (INSTANCED). */
    int bytesPerQuad() {
        return bytesPerQuad;
    }

    /** Quads per buffer: 546 (EXPANDED) or 3 276 (INSTANCED). */
    int capacity() {
        return capacity;
    }

    /** Quads in the open batch. */
    int quads() {
        return quads;
    }

    /** Batches drawn so far. */
    long draws() {
        return draws;
    }

    /**
     * Starts a run of quads into one target, drawn through {@code drawer}.
     *
     * @throws IllegalStateException if a run is open
     */
    void begin(Drawer drawer) {
        if (this.drawer != null) {
            throw new IllegalStateException("a quad run is already open");
        }
        this.drawer = drawer;
    }

    /** Draws what is left and ends the run; a no-op without an open run. */
    void finish() {
        if (drawer != null) {
            try {
                flush();
            } finally {
                drawer = null;
                quads = 0;
                program = -1;
            }
        }
    }

    /** Ends the run without drawing: after a failure. */
    void abandon() {
        drawer = null;
        quads = 0;
        program = -1;
    }

    /**
     * Adds a quad, flushing first at a program or texture change or a full buffer.
     *
     * @throws IllegalStateException without an open run
     */
    @Override
    public void quad(int program, int texture, int x0, int y0, int x1, int y1, int ox, int oy, int v0, int v1, int v2,
        int v3) {
        if (drawer == null) {
            throw new IllegalStateException("no quad run is open");
        }
        assert x0 < x1 && y0 < y1 : "an empty quad";
        assert isI16(x0) && isI16(y0) && isI16(x1) && isI16(y1) : "a rect outside i16";
        assert isI16(ox) && isI16(oy) : "an offset outside i16: " + ox + ", " + oy;
        assert isU16(v0) && isU16(v1) && isU16(v2) && isU16(v3) : "a value outside u16";
        if (quads > 0 && (program != this.program || texture != this.texture || quads == capacity)) {
            flush();
        }
        this.program = program;
        this.texture = texture;
        int copies = mode == QuadMode.EXPANDED ? VERTICES_PER_QUAD : 1;
        int p = quads * bytesPerQuad;
        for (int copy = 0; copy < copies; copy++) {
            buffer.putShort(p, (short) x0);
            buffer.putShort(p + 2, (short) y0);
            buffer.putShort(p + 4, (short) x1);
            buffer.putShort(p + 6, (short) y1);
            buffer.putShort(p + 8, (short) ox);
            buffer.putShort(p + 10, (short) oy);
            buffer.putShort(p + 12, (short) v0);
            buffer.putShort(p + 14, (short) v1);
            buffer.putShort(p + 16, (short) v2);
            buffer.putShort(p + 18, (short) v3);
            p += RECORD_BYTES;
        }
        quads++;
    }

    /** Hands the open batch, if any, to the drawer. */
    void flush() {
        if (quads == 0) {
            return;
        }
        buffer.clear();
        int n = quads;
        quads = 0;
        draws++;
        drawer.draw(program, texture, buffer, n);
    }

    private static boolean isI16(int v) {
        return v >= Short.MIN_VALUE && v <= Short.MAX_VALUE;
    }

    private static boolean isU16(int v) {
        return v >= 0 && v <= 0xFFFF;
    }
}
