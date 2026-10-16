package io.github.mindbound.opengpu.gl;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import io.github.mindbound.opengpu.gl.host.DeviceBackend;

/**
 * One device's readback ring (24:197; 20:127-138; S8 plan §3.9): three slots, each a pack PBO of W·H·bpp (C74, C75) and
 * a {@code GL_TIMESTAMP} query (C94), at most two in flight (H36). An issue copies the surface into a free slot's PBO
 * with {@code glGetTexImage(..., 0L)} (C91; never {@code glReadPixels(..., long)}, A01) and stamps the query after it
 * (C96). A fetch takes slots in issue order while the head is finished: its query reports available (C97; never {@code
 * glGetQueryObjectui}, A02), unless the policy is {@link QueryPolicy#NEVER_READY}, or it was issued {@code
 * forceAfterPasses} passes ago; {@code glGetBufferSubData} (C92) then waits for the copy, so correctness never depends
 * on the query (20:138), once {@link #create} has read each PBO, on every driver, and a {@code glFinish} has run in
 * the pass, on every driver but those {@link ReadbackFinish} lists as verified clean (Intel's driver needs both;
 * {@link #fetch}; Q-S8-3). The pack PBO is bound only during an issue or a fetch (S8 amendments B). The pass numbers
 * are the host's (B3). GL thread, inside a pass.
 */
final class ReadbackRing {

    static final int SLOTS = 3;
    static final int MAX_IN_FLIGHT = 2;

    private final GlBackend backend;
    private final GlLedger ledger;
    private final long session;
    private final int deviceId;
    private final int bytes;
    private final int pixelType;
    private final int[] pbo = new int[SLOTS];
    private final int[] query = new int[SLOTS];
    private final boolean[] inFlight = new boolean[SLOTS];
    private final long[] epoch = new long[SLOTS];
    private final long[] frameId = new long[SLOTS];
    private final long[] issuedAtPass = new long[SLOTS];
    /** In-flight slots in issue order: a FIFO of at most MAX_IN_FLIGHT. */
    private final int[] order = new int[SLOTS];
    private int head;
    private int count;
    private boolean created;
    private long fetchedAvailable;
    private long fetchedForced;

    ReadbackRing(GlBackend backend, long session, int deviceId, int bytes, int pixelType) {
        this.backend = backend;
        this.ledger = backend.ledger();
        this.session = session;
        this.deviceId = deviceId;
        this.bytes = bytes;
        this.pixelType = pixelType;
    }

    /**
     * The PBOs and queries, with their ledger rows (S8 plan §3.11), then one {@code glGetBufferSubData} of each PBO
     * (C92), its bytes dropped. That read works around Intel's Windows driver (32.0.101.8860; S8's fix loop after U5):
     * a fetch from a PBO the CPU has never read may return what its memory held before, zeros or another device's
     * readback, instead of waiting for the copy (20:138). In S8's probes that hit 0.1 to 28 % of fresh devices' first
     * readbacks, the most with many devices opened in one pass; with the read, none in over 50 000. A copy at creation
     * without the read did not help.
     */
    void create() {
        for (int i = 0; i < SLOTS; i++) {
            pbo[i] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[i]);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, bytes, GL15.GL_STREAM_READ);
            ledger.add(session, deviceId, GlLedger.PBO, "pbo" + i, bytes);
            query[i] = GL15.glGenQueries();
            ledger.add(session, deviceId, GlLedger.QUERY, "readback" + i, 0);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        created = true;
        for (int i = 0; i < SLOTS; i++) {
            readSlot(i, backend.readbackBuffer(bytes));
        }
    }

    /** Fewer than two in flight (24:197; H36). */
    boolean slotFree() {
        return count < MAX_IN_FLIGHT;
    }

    int inFlight() {
        return count;
    }

    /**
     * Copies the surface into a free slot and stamps its query.
     *
     * @throws IllegalStateException with two in flight
     */
    void issue(int surfaceTexture, long epoch, long frameId, long pass) {
        if (!slotFree()) {
            throw new IllegalStateException(MAX_IN_FLIGHT + " readbacks are in flight (H36)");
        }
        int slot = 0;
        while (inFlight[slot]) {
            slot++;
        }
        copySurface(slot, surfaceTexture);
        GL33.glQueryCounter(query[slot], GL33.GL_TIMESTAMP);
        inFlight[slot] = true;
        this.epoch[slot] = epoch;
        this.frameId[slot] = frameId;
        issuedAtPass[slot] = pass;
        order[(head + count) % SLOTS] = slot;
        count++;
        backend.readbackIssued();
    }

    /**
     * Fetches finished slots in issue order, the first unfinished one stopping the fetch, and hands each to
     * {@code out}; returns how many. A {@code glFinish} comes before the first read ({@link GlBackend#finishOnce};
     * skipped on a driver {@link ReadbackFinish} lists as verified clean, NVIDIA's so far; Q-S8-3), because Intel's
     * Windows driver (32.0.101.8860; S8's fix loop after U6) does not always make
     * {@code glGetBufferSubData} wait for a copy issued in an earlier pass (20:138) when a texture's storage was
     * re-specified in between, as GLSM's {@code glDeleteTextures} does to every texture it deletes (it shrinks it to
     * 1×1; Angelica 2.2.8's {@code GLStateManager.java:2201-2254}). The read then returns the PBO's earlier bytes, or a
     * copy only partly landed, whether or not the query
     * reports available, and waiting for {@code GL_QUERY_RESULT} does not help. With such re-specifications between
     * the passes that hit up to half of the readbacks, on the GLSM legs and, with GLSM's shrink done by hand, on the
     * compatibility leg; with the {@code glFinish}, none (GlForeignTexturesTest, GlsmForeignTexturesTest).
     */
    int fetch(DeviceBackend.ReadbackOut out, long pass, int forceAfterPasses) {
        int fetched = 0;
        while (count > 0) {
            int slot = order[head];
            boolean forced = pass - issuedAtPass[slot] >= forceAfterPasses;
            boolean available = !forced && backend.queryPolicy() == QueryPolicy.DRIVER
                && GL15.glGetQueryObjecti(query[slot], GL15.GL_QUERY_RESULT_AVAILABLE) != GL11.GL_FALSE;
            if (!forced && !available) {
                break;
            }
            backend.finishOnce();
            ByteBuffer pixels = backend.readbackBuffer(bytes);
            readSlot(slot, pixels);
            inFlight[slot] = false;
            head = (head + 1) % SLOTS;
            count--;
            if (available) {
                fetchedAvailable++;
            } else {
                fetchedForced++;
            }
            fetched++;
            pixels.clear()
                .limit(bytes);
            out.accept(epoch[slot], frameId[slot], pixels);
        }
        return fetched;
    }

    /**
     * The surface now through the production calls, in slot 0's PBO: {@code glGetTexImage} into it, then
     * {@code glGetBufferSubData}, which waits for the copy.
     *
     * @throws IllegalStateException    with a readback in flight
     * @throws IllegalArgumentException unless {@code out} is a direct buffer at position 0 with exactly W·H·bpp bytes
     */
    void readNow(int surfaceTexture, ByteBuffer out) {
        if (count != 0) {
            throw new IllegalStateException(count + " readbacks are in flight; readNow needs none");
        }
        GlTexture.checkBuffer(out, bytes, "readNow");
        copySurface(0, surfaceTexture);
        readSlot(0, out);
    }

    /** Fetches with their query available (C97) and fetches forced by the pass count, for RM:43's latency figures. */
    long fetchedAvailable() {
        return fetchedAvailable;
    }

    long fetchedForced() {
        return fetchedForced;
    }

    /** The PBOs (C78) and queries (C95) with their ledger rows; slots in flight are discarded (21:106). */
    void delete() {
        if (!created) {
            return;
        }
        created = false;
        for (int i = 0; i < SLOTS; i++) {
            GL15.glDeleteBuffers(pbo[i]);
            ledger.remove(session, deviceId, GlLedger.PBO, "pbo" + i, bytes);
            GL15.glDeleteQueries(query[i]);
            ledger.remove(session, deviceId, GlLedger.QUERY, "readback" + i, 0);
            inFlight[i] = false;
        }
        count = 0;
    }

    /**
     * {@code glGetTexImage} of the surface on unit 0 into the slot's PBO, under the forced pack store (24:203), after
     * {@link GlBackend#finishBeforeCopy}'s wait on llvmpipe.
     */
    private void copySurface(int slot, int surfaceTexture) {
        backend.beginPackOnce();
        backend.finishBeforeCopy();
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[slot]);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, surfaceTexture);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL30.GL_RED_INTEGER, pixelType, 0L);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
    }

    /** {@code glGetBufferSubData} of the slot's whole PBO into {@code pixels}, checked first (B9). */
    private void readSlot(int slot, ByteBuffer pixels) {
        GlTexture.checkBuffer(pixels, bytes, "a readback");
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo[slot]);
        GL15.glGetBufferSubData(GL21.GL_PIXEL_PACK_BUFFER, 0, pixels);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
    }
}
