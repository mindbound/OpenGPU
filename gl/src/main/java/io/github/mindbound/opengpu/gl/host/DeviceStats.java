package io.github.mindbound.opengpu.gl.host;

import java.util.Locale;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.render.DeviceKey;

/**
 * One opening's host-side state (S8 plan §2.2, §3.10), a consistent copy taken under its lock by
 * {@link GlHostCore#stats}: what the tests read and what M0's measurements record (RM:43). Immutable.
 */
public final class DeviceStats {

    public final DeviceKey key;
    public final SurfaceDesc surface;
    public final boolean released;
    /**
     * The backend's objects exist: the opening had its first turn (S8 plan §3.2 (b)), was not refused, and neither step
     * (d), {@code close} nor a failed host's {@code backend.close()} (H63) has deleted them.
     */
    public final boolean opened;
    /** A side over the backend's {@code maxSurfaceSide()} at the first turn (H64): its records are dropped. */
    public final boolean refused;

    /** The queue: records, {@code FRAME}s among them, and {@code Record.queuedBytes()} plus each FRAME's bytes. */
    public final int queuedRecords, queuedFrames;
    public final long queuedBytes;
    /** Resource records ahead of the queue's first {@code FRAME} (I-H1): 0 between passes. */
    public final int headResources;

    /** The mirrors (Q-S8-1's option A): textures and their Σ w·h·bpp, whether or not an array backs them. */
    public final int mirrorTextures;
    public final long mirrorBytes;
    /**
     * Copy-on-write (amendment A): zero arrays allocated by a partial update, shared arrays cloned by one,
     * whole-texture payloads adopted by reference, and the bytes copied on the applying thread (rows and clones).
     */
    public final long mirrorAllocations, mirrorClones, mirrorAdoptions, mirrorCopyBytes;
    /** Resource records applied at their offer (on the offering thread) and from the queue (on the render thread). */
    public final long appliedAtOffer, appliedQueued;

    /** What the GPU lacks against the mirrors (I-H3): pending textures and a pending {@code RESET}. */
    public final int pendingTextures;
    public final boolean pendingReset;
    /** The textures the GPU holds for the opening, as the host has created and deleted them, and their bytes (G10). */
    public final int gpuTextures;
    public final long gpuTextureBytes;
    /** Texture uploads (creates and region updates; G13b's count, 28 §6.7) and their bytes; RESETs and expands run. */
    public final long uploads, uploadBytes, resetsRun, expands;

    public final int readbacksInFlight;
    /** A FRAME ran and its readback waits for a free slot (H36). */
    public final boolean readbackOwed;
    /** A turn changed the surface and the present texture is not yet expanded (H27, H68). */
    public final boolean expandOwed;
    /** Counters; fetched readbacks of one pass but the newest are never delivered (21:87). */
    public final long framesExecuted, readbacksIssued, readbacksFetched, readbacksDelivered;
    /** Records dropped: by a {@code RESET} (older epochs, H38), a release, a refusal or a failure. */
    public final long recordsDropped;
    /** The last delivered readback's epoch and frame id; -1 before the first. */
    public final long lastReadbackEpoch, lastReadbackFrameId;
    /** Passes from a readback's issue to its fetch, the largest and the sum (RM:43's query latency). */
    public final long readbackLatencyMax, readbackLatencyTotal;
    /** The host's failure reason (H63), or null. */
    public final String hostFailure;

    /** Under the device's lock. */
    DeviceStats(HostDevice d, String hostFailure) {
        this.key = d.key;
        this.surface = d.surface;
        this.released = d.released;
        this.opened = d.backend != null && !d.destroyed && !d.refused && !d.dropped;
        this.refused = d.refused;
        this.queuedRecords = d.queue.size();
        this.queuedFrames = d.queuedFrames;
        this.queuedBytes = d.queuedBytes;
        this.headResources = d.headResources();
        this.mirrorTextures = d.mirrors.size();
        this.mirrorBytes = d.mirrorBytes;
        this.mirrorAllocations = d.mirrorAllocations;
        this.mirrorClones = d.mirrorClones;
        this.mirrorAdoptions = d.mirrorAdoptions;
        this.mirrorCopyBytes = d.mirrorCopyBytes;
        this.appliedAtOffer = d.appliedAtOffer;
        this.appliedQueued = d.appliedQueued;
        this.pendingTextures = d.pending.size();
        this.pendingReset = d.pendingReset != null;
        this.gpuTextures = d.gpu.size();
        this.gpuTextureBytes = d.gpuBytes;
        this.uploads = d.uploads;
        this.uploadBytes = d.uploadBytes;
        this.resetsRun = d.resetsRun;
        this.expands = d.expands;
        this.readbacksInFlight = d.inFlight;
        this.readbackOwed = d.readbackOwed;
        this.expandOwed = d.expandOwed;
        this.framesExecuted = d.framesExecuted;
        this.readbacksIssued = d.readbacksIssued;
        this.readbacksFetched = d.readbacksFetched;
        this.readbacksDelivered = d.readbacksDelivered;
        this.recordsDropped = d.recordsDropped;
        this.lastReadbackEpoch = d.lastReadbackEpoch;
        this.lastReadbackFrameId = d.lastReadbackFrameId;
        this.readbackLatencyMax = d.latencyMax;
        this.readbackLatencyTotal = d.latencyTotal;
        this.hostFailure = hostFailure;
    }

    @Override
    public String toString() {
        return String.format(
            Locale.ROOT,
            "DeviceStats[%s %s%s%s%s, queue %d (%d frames, %d B), mirrors %d (%d B), pending %d%s, GPU %d (%d B), "
                + "in flight %d%s, frames %d, readbacks %d/%d/%d, dropped %d, last e%d #%d%s]",
            key,
            surface,
            released ? ", released" : "",
            refused ? ", refused" : "",
            opened ? "" : ", not opened",
            queuedRecords,
            queuedFrames,
            queuedBytes,
            mirrorTextures,
            mirrorBytes,
            pendingTextures,
            pendingReset ? " + reset" : "",
            gpuTextures,
            gpuTextureBytes,
            readbacksInFlight,
            readbackOwed ? " + owed" : "",
            framesExecuted,
            readbacksIssued,
            readbacksFetched,
            readbacksDelivered,
            recordsDropped,
            lastReadbackEpoch,
            lastReadbackFrameId,
            hostFailure == null ? "" : ", host failed: " + hostFailure);
    }
}
