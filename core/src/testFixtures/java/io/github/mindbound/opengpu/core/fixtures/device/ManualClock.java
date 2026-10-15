package io.github.mindbound.opengpu.core.fixtures.device;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A device's nanosecond clock that moves only when a test moves it, for the 1 s {@code "dropped"} status (28:112; H12)
 * without sleeping. Starts at 0. Thread-safe.
 */
public final class ManualClock implements LongSupplier {

    private final AtomicLong nanos = new AtomicLong();

    /** The time now, in nanoseconds. */
    @Override
    public long getAsLong() {
        return nanos.get();
    }

    /** @throws IllegalArgumentException for a negative step: a device's clock never runs backwards */
    public void advanceNanos(long n) {
        if (n < 0) {
            throw new IllegalArgumentException("step " + n + " ns is negative");
        }
        nanos.addAndGet(n);
    }

    /** @throws IllegalArgumentException for a negative step */
    public void advanceMillis(long ms) {
        if (ms < 0) {
            throw new IllegalArgumentException("step " + ms + " ms is negative");
        }
        advanceNanos(ms * 1_000_000L);
    }

    @Override
    public String toString() {
        return "ManualClock[" + nanos.get() + " ns]";
    }
}
