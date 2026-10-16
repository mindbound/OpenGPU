package io.github.mindbound.opengpu.gl.host;

import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * A pass's budget (24:173: {@code client.renderBudgetMs}, 4 ms by default): {@link GlHostCore#pass} asks
 * {@link #more()} before each unit of GL work, a {@code RESET}, one pending texture or one {@code FRAME} (S8 plan
 * §3.2). Applying a queued resource record to the mirrors is CPU work under the device's lock and is not counted
 * (amendment D). A budget serves one pass: the root makes {@code millis(4)} at each {@code RenderTickEvent(START)},
 * {@code GlTestHost} {@code unlimited()}, the tests {@code units(n)} for a deterministic round robin.
 */
public interface PassBudget {

    /** Whether the pass may run one more unit; it is asked once per unit, and a false answer ends the round robin. */
    boolean more();

    /** Always more: {@code GlTestHost}'s pump. */
    static PassBudget unlimited() {
        return new PassBudget() {

            @Override
            public boolean more() {
                return true;
            }

            @Override
            public String toString() {
                return "PassBudget.unlimited()";
            }
        };
    }

    /** {@link #millis(double, LongSupplier)} on {@code System::nanoTime}: the root's 4 ms (24:173). */
    static PassBudget millis(double ms) {
        return millis(ms, System::nanoTime);
    }

    /**
     * True at the first call whatever the clock says, so a pass runs at least one unit and every device progresses
     * however slow a unit is (scenario 11), then while less than {@code ms} has passed since that first call.
     *
     * @throws IllegalArgumentException unless ms is finite and positive
     * @throws NullPointerException     for a null clock
     */
    static PassBudget millis(final double ms, final LongSupplier nanoClock) {
        if (!(ms > 0) || Double.isInfinite(ms)) {
            throw new IllegalArgumentException("budget of " + ms + " ms is not finite and positive");
        }
        if (nanoClock == null) {
            throw new NullPointerException("nanoClock");
        }
        final long nanos = (long) Math.ceil(ms * 1_000_000.0);
        return new PassBudget() {

            private boolean started;
            private long start;

            @Override
            public boolean more() {
                long now = nanoClock.getAsLong();
                if (!started) {
                    started = true;
                    start = now;
                    return true;
                }
                return now - start < nanos;
            }

            @Override
            public String toString() {
                return String.format(Locale.ROOT, "PassBudget.millis(%s)", ms);
            }
        };
    }

    /**
     * True for the first n calls: n units of GL work per pass, for the tests' deterministic round robin (24:173).
     *
     * @throws IllegalArgumentException for a negative n
     */
    static PassBudget units(final int n) {
        if (n < 0) {
            throw new IllegalArgumentException("units " + n + " is negative");
        }
        return new PassBudget() {

            private int left = n;

            @Override
            public boolean more() {
                if (left == 0) {
                    return false;
                }
                left--;
                return true;
            }

            @Override
            public String toString() {
                return "PassBudget.units(" + n + ")";
            }
        };
    }
}
