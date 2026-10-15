package io.github.mindbound.opengpu.core.render;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import io.github.mindbound.opengpu.core.cmd.FrameTotals;

/**
 * Kind 1, {@code FRAME} (28:163): a presented frame's id, its validated segments in submit order, and the open frame's
 * totals at present, which carry the per-class pixels for M1's energy (28 §8.3).
 */
public final class FrameRecord extends Record {

    /** At least 1; 0 is the cleared image (28:108). */
    public final long frameId;
    /** The open frame's totals at present. */
    public final FrameTotals totals;

    private final byte[][] segments;
    private final List<byte[]> view;
    /** The segments' CRC32s in dev builds, else null. */
    private final long[] crcs;

    /**
     * Clones the outer array; each segment is the submitted array itself, never copied (C14). Takes the segments'
     * CRC32s in dev builds.
     *
     * @throws NullPointerException     for null segments, a null segment or null totals
     * @throws IllegalArgumentException for a negative epoch or a frameId below 1
     */
    public FrameRecord(long epoch, long frameId, byte[][] segments, FrameTotals totals) {
        super(epoch);
        if (segments == null || totals == null) {
            throw new NullPointerException("segments and totals");
        }
        if (frameId < 1) {
            throw new IllegalArgumentException("frameId " + frameId + " is below 1");
        }
        byte[][] own = segments.clone();
        for (int i = 0; i < own.length; i++) {
            if (own[i] == null) {
                throw new NullPointerException("segment " + i);
            }
        }
        this.frameId = frameId;
        this.totals = totals;
        this.segments = own;
        this.view = Collections.unmodifiableList(Arrays.asList(own));
        if (devChecks()) {
            crcs = new long[own.length];
            for (int i = 0; i < own.length; i++) {
                crcs[i] = crc(own[i]);
            }
        } else {
            crcs = null;
        }
    }

    /** The number of segments; 0 for a present without a submit since the last one (28:108). */
    public int segmentCount() {
        return segments.length;
    }

    /** The submitted array itself; never write it. */
    public byte[] segment(int index) {
        return segments[index];
    }

    /** An unmodifiable view of the same arrays, in submit order. */
    public List<byte[]> segments() {
        return view;
    }

    /** The segments' total length. */
    public long byteCount() {
        long bytes = 0;
        for (byte[] segment : segments) {
            bytes += segment.length;
        }
        return bytes;
    }

    @Override
    public int kind() {
        return FRAME;
    }

    @Override
    public void accept(RecordVisitor visitor) {
        visitor.frame(this);
    }

    @Override
    public long queuedBytes() {
        return 0;
    }

    @Override
    public boolean intact() {
        if (crcs == null) {
            return true;
        }
        for (int i = 0; i < segments.length; i++) {
            if (crc(segments[i]) != crcs[i]) {
                return false;
            }
        }
        return true;
    }

    /** For example "FRAME e1 #3 (2 segments, 64 B)". */
    @Override
    public String toString() {
        return String.format(
            Locale.ROOT,
            "FRAME e%d #%d (%d %s, %d B)",
            epoch,
            frameId,
            segments.length,
            segments.length == 1 ? "segment" : "segments",
            byteCount());
    }
}
