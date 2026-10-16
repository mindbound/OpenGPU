package io.github.mindbound.opengpu.gl.host;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.fixtures.device.ManualClock;

/**
 * The pass budgets of S8 plan §2.2 (24:173): {@code units(n)} allows exactly n units, {@code millis} always allows the
 * first unit and then measures from it on the given clock, {@code unlimited} never ends a pass.
 */
class PassBudgetTest {

    @Test
    void unitsAllowsExactlyNUnits() {
        for (int n = 0; n <= 5; n++) {
            PassBudget budget = PassBudget.units(n);
            for (int i = 0; i < n; i++) {
                Assertions.assertTrue(budget.more(), "unit " + i + " of " + n);
            }
            Assertions.assertFalse(budget.more(), "unit " + n + " of " + n);
            Assertions.assertFalse(budget.more(), "it stays spent");
        }
        Assertions.assertThrows(IllegalArgumentException.class, () -> PassBudget.units(-1));
    }

    @Test
    void millisAllowsTheFirstUnitThenMeasuresFromItOnTheClock() {
        ManualClock clock = new ManualClock();
        clock.advanceMillis(100);
        PassBudget budget = PassBudget.millis(4, clock);
        // Created long before the pass: the clock starts at the first unit, which always runs (scenario 11)
        clock.advanceMillis(50);
        Assertions.assertTrue(budget.more(), "the first unit");
        clock.advanceNanos(3_999_999);
        Assertions.assertTrue(budget.more(), "3.999999 ms after the first unit");
        clock.advanceNanos(1);
        Assertions.assertFalse(budget.more(), "4 ms after it");
        clock.advanceMillis(100);
        Assertions.assertFalse(budget.more());

        // A first unit slower than the whole budget still runs, and is the only one
        PassBudget slow = PassBudget.millis(0.5, clock);
        Assertions.assertTrue(slow.more());
        clock.advanceMillis(10);
        Assertions.assertFalse(slow.more());

        // A budget rounds up to whole nanoseconds: 1.5 ns is 2 ns
        PassBudget fine = PassBudget.millis(0.0000015, clock);
        Assertions.assertTrue(fine.more());
        clock.advanceNanos(1);
        Assertions.assertTrue(fine.more(), "1 ns of 2");
        clock.advanceNanos(1);
        Assertions.assertFalse(fine.more(), "2 ns of 2");
    }

    @Test
    void millisRejectsNonPositiveAndNonFiniteBudgetsAndANullClock() {
        ManualClock clock = new ManualClock();
        for (double ms : new double[] { 0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY }) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> PassBudget.millis(ms, clock), "ms " + ms);
        }
        Assertions.assertThrows(NullPointerException.class, () -> PassBudget.millis(4, null));
        // The root's form on System::nanoTime
        Assertions.assertTrue(
            PassBudget.millis(4)
                .more());
    }

    @Test
    void unlimitedNeverEnds() {
        PassBudget budget = PassBudget.unlimited();
        for (int i = 0; i < 100_000; i++) {
            Assertions.assertTrue(budget.more());
        }
    }
}
