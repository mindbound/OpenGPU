package io.github.mindbound.opengpu.core.cmd;

import java.util.Locale;

/**
 * The admission caps the validator checks (28 §8.1): bytes per submit, and the open frame's bytes, records and fill.
 * They are the device's tier caps, not host limits (28 §10.5), and provisional until M0's measurements (Q5). Immutable.
 */
public final class FrameLimits {

    /** 65 536 B per submit at every tier ({@code maxSubmitBytes}). */
    public static final int SUBMIT_BYTES = 65536;

    private static final int FRAME_RECORDS = 4096;
    private static final FrameLimits T2 = new FrameLimits(SUBMIT_BYTES, 131072, FRAME_RECORDS, 1024000L);
    private static final FrameLimits T3 = new FrameLimits(SUBMIT_BYTES, 262144, FRAME_RECORDS, 4096000L);

    /** {@code maxSubmitBytes}: row 1 of 28 §3.6. */
    public final int submitBytes;
    /** {@code maxFrameBytes}: the open frame's bytes with this submit, row 2. */
    public final int frameBytes;
    /** {@code maxFrameRecords}: the open frame's records, row 22. */
    public final int frameRecords;
    /** {@code maxFrameFill}: the open frame's fill over all four energy classes, row 23. */
    public final long frameFill;

    /**
     * @throws IllegalArgumentException if submitBytes, frameBytes or frameRecords is below 1 or frameFill below 0 (a
     *                                  fill cap of 0 admits only empty ops)
     */
    public FrameLimits(int submitBytes, int frameBytes, int frameRecords, long frameFill) {
        if (submitBytes < 1 || frameBytes < 1 || frameRecords < 1 || frameFill < 0) {
            throw new IllegalArgumentException(
                "invalid limits: " + describe(submitBytes, frameBytes, frameRecords, frameFill));
        }
        this.submitBytes = submitBytes;
        this.frameBytes = frameBytes;
        this.frameRecords = frameRecords;
        this.frameFill = frameFill;
    }

    /**
     * The tier's caps (28 §8.1): T2 65 536, 131 072, 4 096 and 1 024 000 px; T3 65 536, 262 144, 4 096 and
     * 4 096 000 px. M1 adds T1's 65 536, 65 536, 4 096 and 256 000 px.
     *
     * @throws IllegalArgumentException for any tier but 2 and 3
     */
    public static FrameLimits forTier(int tier) {
        switch (tier) {
            case 2:
                return T2;
            case 3:
                return T3;
            default:
                throw new IllegalArgumentException("M0 builds tiers 2 and 3 only, not " + tier);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FrameLimits)) {
            return false;
        }
        FrameLimits that = (FrameLimits) other;
        return submitBytes == that.submitBytes && frameBytes == that.frameBytes
            && frameRecords == that.frameRecords
            && frameFill == that.frameFill;
    }

    @Override
    public int hashCode() {
        int hash = submitBytes;
        hash = hash * 31 + frameBytes;
        hash = hash * 31 + frameRecords;
        return hash * 31 + (int) (frameFill ^ (frameFill >>> 32));
    }

    @Override
    public String toString() {
        return "FrameLimits[" + describe(submitBytes, frameBytes, frameRecords, frameFill) + "]";
    }

    private static String describe(int submitBytes, int frameBytes, int frameRecords, long frameFill) {
        return String.format(
            Locale.ROOT,
            "submit %d B, frame %d B, %d records, %d px",
            submitBytes,
            frameBytes,
            frameRecords,
            frameFill);
    }
}
