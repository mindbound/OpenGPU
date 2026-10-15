package io.github.mindbound.opengpu.core.cmd;

/**
 * A submit's outcome (28 §3.5): accepted with the segment's own totals, or rejected with exactly one error. The caller
 * appends the segment and commits {@code open.plus(totals())} only when it is accepted, so a rejected submit changes
 * nothing. Immutable.
 */
public final class Validation {

    private final FrameTotals totals;
    private final StreamError error;

    private Validation(FrameTotals totals, StreamError error) {
        this.totals = totals;
        this.error = error;
    }

    /** @throws NullPointerException for null totals */
    public static Validation ok(FrameTotals segment) {
        if (segment == null) {
            throw new NullPointerException("segment totals");
        }
        return new Validation(segment, null);
    }

    /** @throws NullPointerException for a null error */
    public static Validation rejected(StreamError error) {
        if (error == null) {
            throw new NullPointerException("error");
        }
        return new Validation(null, error);
    }

    public boolean isAccepted() {
        return error == null;
    }

    /**
     * The segment's own totals: its byte count ({@code #s}), its records and its per-class fill.
     *
     * @throws IllegalStateException if the submit was rejected
     */
    public FrameTotals totals() {
        if (totals == null) {
            throw new IllegalStateException("rejected: " + error.message());
        }
        return totals;
    }

    /** The error, or null if accepted. */
    public StreamError error() {
        return error;
    }

    @Override
    public String toString() {
        return error == null ? "accepted " + totals : "rejected " + error.message();
    }
}
