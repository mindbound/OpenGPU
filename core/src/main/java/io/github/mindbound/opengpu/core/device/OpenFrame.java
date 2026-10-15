package io.github.mindbound.opengpu.core.device;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.render.Record;

/**
 * The open frame (28:121): the segments validated since the last present, their running totals, and the frees
 * deferred while it is open (O4), with the deferred textures' count and bytes, which still count against
 * {@code createTexture} until they are appended (H23). In dev builds it takes each segment's CRC32 at append (28:202).
 * Not thread-safe: the device's monitor guards it.
 */
final class OpenFrame {

    private final List<byte[]> segments = new ArrayList<>();
    private long[] crcs = new long[4];
    private FrameTotals totals = FrameTotals.EMPTY;
    private int[] deferred = new int[4];
    private int deferredCount;
    private long deferredBytes;

    /** Whether at least one segment was accepted since the last seal or discard. */
    boolean isOpen() {
        return !segments.isEmpty();
    }

    /** The running totals; {@link FrameTotals#EMPTY} when not open. */
    FrameTotals totals() {
        return totals;
    }

    /** Appends the submitted array itself (C14) and adds its totals; takes its CRC32 in dev builds. */
    void append(byte[] segment, FrameTotals segmentTotals) {
        FrameTotals sum = totals.plus(segmentTotals);
        if (Record.devChecks()) {
            int i = segments.size();
            if (i == crcs.length) {
                crcs = Arrays.copyOf(crcs, i * 2);
            }
            crcs[i] = crc(segment);
        }
        segments.add(segment);
        totals = sum;
    }

    /**
     * Defers a free until the frame is sealed or discarded; bytes is the texture's w·h·bpp, read from the table before
     * the slot was freed.
     *
     * @throws IllegalStateException unless the frame is open
     */
    void deferFree(int handle, int bytes) {
        if (!isOpen()) {
            throw new IllegalStateException("no open frame to defer a free to");
        }
        if (deferredCount == deferred.length) {
            deferred = Arrays.copyOf(deferred, deferredCount * 2);
        }
        deferred[deferredCount++] = handle;
        deferredBytes += bytes;
    }

    /** A new outer array of the submitted arrays, in submit order. */
    byte[][] segments() {
        return segments.toArray(new byte[segments.size()][]);
    }

    /** The deferred handles, in free order. */
    int[] deferredFrees() {
        return Arrays.copyOf(deferred, deferredCount);
    }

    int deferredCount() {
        return deferredCount;
    }

    /** Σ w·h·bpp of the deferred textures. */
    long deferredBytes() {
        return deferredBytes;
    }

    /** In dev builds, whether every segment still has the CRC32 taken at append; else true. */
    boolean segmentsIntact() {
        if (!Record.devChecks()) {
            return true;
        }
        for (int i = 0; i < segments.size(); i++) {
            if (crc(segments.get(i)) != crcs[i]) {
                return false;
            }
        }
        return true;
    }

    /** Drops the segments, the totals and the deferred frees. */
    void clear() {
        segments.clear();
        totals = FrameTotals.EMPTY;
        deferredCount = 0;
        deferredBytes = 0;
    }

    private static long crc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length);
        return crc.getValue();
    }
}
