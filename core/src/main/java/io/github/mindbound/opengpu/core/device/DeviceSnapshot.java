package io.github.mindbound.opengpu.core.device;

import java.util.List;
import java.util.Locale;
import java.util.SortedMap;

import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.Record;

/**
 * A consistent copy of a device's state (28:119-123), taken under its monitor by {@link Device#snapshot()}: what the
 * tests read, and what M1's save will write (24:181). Immutable: the lists and the map are unmodifiable, and the array
 * accessors return fresh copies. Records and segments are the device's own immutable values.
 */
public final class DeviceSnapshot {

    /** One live texture of the handle table. */
    public static final class LiveTexture {

        public final int handle;
        public final int width;
        public final int height;
        public final int format;

        LiveTexture(int handle, int width, int height, int format) {
            this.handle = handle;
            this.width = width;
            this.height = height;
            this.format = format;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "0x%08X %dx%d", handle, width, height);
        }
    }

    public final DeviceKey key;
    public final long epoch;
    public final boolean released;
    /** The newest presented frame id; 0 before the first present. */
    public final long lastFrameId;
    /** K, the newest confirmed frame id; 0 is the cleared image. */
    public final long confirmedFrameId;
    /** The server tail, head first (unmodifiable). */
    public final List<Record> tail;
    /** The {@code FRAME}s in the tail. */
    public final int unconfirmedFrames;
    /** Σ queued bytes of the tail's resource records (28:140). */
    public final long queuedBytes;
    /** Σ w·h·bpp of the live textures. */
    public final long vramUsed;
    /** The live textures, ascending slot (unmodifiable). */
    public final List<LiveTexture> live;
    /** Copies of the texture masters, by full handle (unmodifiable). */
    public final SortedMap<Integer, MasterTexture> masters;
    /** The open frame's segments, the submitted arrays (unmodifiable). */
    public final List<byte[]> openSegments;
    public final FrameTotals openTotals;
    /** The open frame's deferred frees, in free order (unmodifiable). */
    public final List<Integer> deferredFrees;
    /** Their count, which still counts against createTexture (H23). */
    public final int deferredCount;
    /** Their Σ w·h·bpp, which still counts against createTexture (H23). */
    public final long deferredBytes;
    /** The two-phase pattern's exhausted flag (28:712). */
    public final boolean budgetExhausted;
    /** Readbacks dropped for another epoch, a frame at or before K, a released device or a newer pending one (H8). */
    public final long staleReadbacks;
    /** Readbacks dropped for a wrong key or length, a null array, a frame never presented, or a rejected hand-off. */
    public final long rejectedReadbacks;

    private final byte[] confirmedPixels;
    private final int[] freeSlots;
    private final int[] generations;

    DeviceSnapshot(DeviceKey key, long epoch, boolean released, long lastFrameId, long confirmedFrameId,
        byte[] confirmedPixels, List<Record> tail, int unconfirmedFrames, long queuedBytes, long vramUsed,
        List<LiveTexture> live, SortedMap<Integer, MasterTexture> masters, int[] freeSlots, int[] generations,
        List<byte[]> openSegments, FrameTotals openTotals, List<Integer> deferredFrees, long deferredBytes,
        boolean budgetExhausted, long staleReadbacks, long rejectedReadbacks) {
        this.key = key;
        this.epoch = epoch;
        this.released = released;
        this.lastFrameId = lastFrameId;
        this.confirmedFrameId = confirmedFrameId;
        this.confirmedPixels = confirmedPixels;
        this.tail = tail;
        this.unconfirmedFrames = unconfirmedFrames;
        this.queuedBytes = queuedBytes;
        this.vramUsed = vramUsed;
        this.live = live;
        this.masters = masters;
        this.freeSlots = freeSlots;
        this.generations = generations;
        this.openSegments = openSegments;
        this.openTotals = openTotals;
        this.deferredFrees = deferredFrees;
        this.deferredCount = deferredFrees.size();
        this.deferredBytes = deferredBytes;
        this.budgetExhausted = budgetExhausted;
        this.staleReadbacks = staleReadbacks;
        this.rejectedReadbacks = rejectedReadbacks;
    }

    /** A fresh copy of K's pixels, 28 §5.7 layout. */
    public byte[] confirmedPixels() {
        return confirmedPixels.clone();
    }

    /** A fresh copy of the texture type's free queue, head first. */
    public int[] freeSlots() {
        return freeSlots.clone();
    }

    /** A fresh copy of the generations, 2 048 entries by slot. */
    public int[] generations() {
        return generations.clone();
    }

    @Override
    public String toString() {
        return String.format(
            Locale.ROOT,
            "DeviceSnapshot[%s e%d%s, last %d, K %d, tail %d records (%d frames, %d B), VRAM %d B, %d live, "
                + "%d masters]",
            key,
            epoch,
            released ? " released" : "",
            lastFrameId,
            confirmedFrameId,
            tail.size(),
            unconfirmedFrames,
            queuedBytes,
            vramUsed,
            live.size(),
            masters.size());
    }
}
