package io.github.mindbound.opengpu.core.cmd;

import java.util.Locale;

/**
 * Byte, record and per-class pixel totals (28 §3.5): one accepted segment's, or the open frame's running totals, which
 * the caller replaces with {@code open.plus(segment)} only when a submit is accepted. {@code FRAME} carries a frame's
 * per-class totals for M1's energy (28 §8.3). Immutable.
 */
public final class FrameTotals {

    /** An empty frame: no segment yet. */
    public static final FrameTotals EMPTY = new FrameTotals(0, 0, 0, 0, 0, 0);

    public final int bytes;
    /** Records, {@code END} not counted and empty ops counted (28 §3.5). */
    public final int records;
    public final long clearPixels;
    public final long fillPixels;
    public final long copyPixels;
    public final long setPixels;

    /** @throws IllegalArgumentException if any value is negative or the four pixel totals overflow a long */
    public FrameTotals(int bytes, int records, long clearPixels, long fillPixels, long copyPixels, long setPixels) {
        if (bytes < 0 || records < 0 || clearPixels < 0 || fillPixels < 0 || copyPixels < 0 || setPixels < 0) {
            throw new IllegalArgumentException(
                "negative totals: " + describe(bytes, records, clearPixels, fillPixels, copyPixels, setPixels));
        }
        if (clearPixels + fillPixels < 0 || clearPixels + fillPixels + copyPixels < 0
            || clearPixels + fillPixels + copyPixels + setPixels < 0) {
            throw new IllegalArgumentException(
                "pixel totals overflow: " + describe(bytes, records, clearPixels, fillPixels, copyPixels, setPixels));
        }
        this.bytes = bytes;
        this.records = records;
        this.clearPixels = clearPixels;
        this.fillPixels = fillPixels;
        this.copyPixels = copyPixels;
        this.setPixels = setPixels;
    }

    /** One energy class's pixels. */
    public long pixels(EnergyClass energyClass) {
        switch (energyClass) {
            case CLEAR:
                return clearPixels;
            case FILL:
                return fillPixels;
            case COPY:
                return copyPixels;
            case SET:
                return setPixels;
            default:
                throw new IllegalArgumentException(String.valueOf(energyClass));
        }
    }

    /** The sum over the four classes: what {@code OVER_FRAME_FILL} compares with the fill cap (28 §3.6 row 23). */
    public long totalPixels() {
        return clearPixels + fillPixels + copyPixels + setPixels;
    }

    /**
     * Both totals added: what a commit does, {@code open = open.plus(segment)}.
     *
     * @throws ArithmeticException if a field overflows or the four pixel totals together overflow a long, and no
     *                             other exception: the sum is checked here, before the constructor's own check
     */
    public FrameTotals plus(FrameTotals other) {
        long clear = Math.addExact(clearPixels, other.clearPixels);
        long fill = Math.addExact(fillPixels, other.fillPixels);
        long copy = Math.addExact(copyPixels, other.copyPixels);
        long set = Math.addExact(setPixels, other.setPixels);
        // totalPixels() of the result, which the constructor would refuse with an IllegalArgumentException
        Math.addExact(Math.addExact(Math.addExact(clear, fill), copy), set);
        return new FrameTotals(
            Math.addExact(bytes, other.bytes),
            Math.addExact(records, other.records),
            clear,
            fill,
            copy,
            set);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FrameTotals)) {
            return false;
        }
        FrameTotals that = (FrameTotals) other;
        return bytes == that.bytes && records == that.records
            && clearPixels == that.clearPixels
            && fillPixels == that.fillPixels
            && copyPixels == that.copyPixels
            && setPixels == that.setPixels;
    }

    @Override
    public int hashCode() {
        int hash = bytes;
        hash = hash * 31 + records;
        hash = hash * 31 + Long.hashCode(clearPixels);
        hash = hash * 31 + Long.hashCode(fillPixels);
        hash = hash * 31 + Long.hashCode(copyPixels);
        return hash * 31 + Long.hashCode(setPixels);
    }

    @Override
    public String toString() {
        return "FrameTotals[" + describe(bytes, records, clearPixels, fillPixels, copyPixels, setPixels) + "]";
    }

    private static String describe(int bytes, int records, long clear, long fill, long copy, long set) {
        return String.format(
            Locale.ROOT,
            "%d B, %d records, clear %d, fill %d, copy %d, set %d px",
            bytes,
            records,
            clear,
            fill,
            copy,
            set);
    }
}
