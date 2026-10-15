package io.github.mindbound.opengpu.core.cmd;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** The byte, record and per-class pixel totals of 28 §3.5, and the commit's {@code open.plus(segment)}. */
class FrameTotalsTest {

    @Test
    void emptyIsAllZero() {
        Assertions.assertEquals(new FrameTotals(0, 0, 0, 0, 0, 0), FrameTotals.EMPTY);
        Assertions.assertEquals(0, FrameTotals.EMPTY.totalPixels());
    }

    @Test
    void thePixelsArePerEnergyClass() {
        FrameTotals t = new FrameTotals(64, 4, 64000, 5000, 256, 156);
        Assertions.assertEquals(64000, t.pixels(EnergyClass.CLEAR));
        Assertions.assertEquals(5000, t.pixels(EnergyClass.FILL));
        Assertions.assertEquals(256, t.pixels(EnergyClass.COPY));
        Assertions.assertEquals(156, t.pixels(EnergyClass.SET));
        // Row 23 compares the sum over the four classes with the one fill cap
        Assertions.assertEquals(69412, t.totalPixels());
    }

    @Test
    void plusAddsEveryField() {
        FrameTotals a = new FrameTotals(64, 4, 64000, 5000, 256, 156);
        FrameTotals b = new FrameTotals(9, 0, 0, 0, 0, 0);
        FrameTotals c = new FrameTotals(30, 2, 1, 2, 3, 4);
        Assertions.assertEquals(new FrameTotals(73, 4, 64000, 5000, 256, 156), a.plus(b));
        Assertions.assertEquals(new FrameTotals(94, 6, 64001, 5002, 259, 160), a.plus(c));
        Assertions.assertEquals(a.plus(c), c.plus(a));
        Assertions.assertEquals(a, a.plus(FrameTotals.EMPTY));
        // Immutable: plus makes a new value
        Assertions.assertEquals(new FrameTotals(64, 4, 64000, 5000, 256, 156), a);
    }

    @Test
    void plusRefusesAnOverflow() {
        FrameTotals big = new FrameTotals(Integer.MAX_VALUE, 0, 0, 0, 0, 0);
        Assertions.assertThrows(ArithmeticException.class, () -> big.plus(new FrameTotals(1, 0, 0, 0, 0, 0)));
        FrameTotals many = new FrameTotals(0, Integer.MAX_VALUE, 0, 0, 0, 0);
        Assertions.assertThrows(ArithmeticException.class, () -> many.plus(new FrameTotals(0, 1, 0, 0, 0, 0)));
        FrameTotals full = new FrameTotals(0, 0, Long.MAX_VALUE, 0, 0, 0);
        Assertions.assertThrows(ArithmeticException.class, () -> full.plus(new FrameTotals(0, 0, 1, 0, 0, 0)));
        // Each class fits but the four together do not: still an ArithmeticException, not the constructor's
        // IllegalArgumentException
        Assertions.assertThrows(ArithmeticException.class, () -> full.plus(new FrameTotals(0, 0, 0, 1, 0, 0)));
        Assertions.assertThrows(
            ArithmeticException.class,
            () -> new FrameTotals(0, 0, 1L << 62, (1L << 62) - 1, 0, 0).plus(new FrameTotals(0, 0, 0, 0, 1, 0)));
        Assertions.assertEquals(
            Long.MAX_VALUE,
            new FrameTotals(0, 0, 1L << 62, (1L << 62) - 2, 0, 0).plus(new FrameTotals(0, 0, 0, 0, 1, 0))
                .totalPixels());
    }

    @Test
    void negativeOrOverflowingTotalsAreRefused() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new FrameTotals(-1, 0, 0, 0, 0, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new FrameTotals(0, -1, 0, 0, 0, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new FrameTotals(0, 0, -1, 0, 0, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new FrameTotals(0, 0, 0, -1, 0, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new FrameTotals(0, 0, 0, 0, -1, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new FrameTotals(0, 0, 0, 0, 0, -1));
        // The sum must fit a long, so totalPixels() cannot wrap
        Assertions.assertThrows(IllegalArgumentException.class, () -> new FrameTotals(0, 0, Long.MAX_VALUE, 1, 0, 0));
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new FrameTotals(0, 0, 0, 0, Long.MAX_VALUE, Long.MAX_VALUE));
        Assertions.assertEquals(Long.MAX_VALUE, new FrameTotals(0, 0, Long.MAX_VALUE, 0, 0, 0).totalPixels());
    }

    @Test
    void equalityIsByValue() {
        FrameTotals a = new FrameTotals(64, 4, 64000, 5000, 256, 156);
        Assertions.assertEquals(a, new FrameTotals(64, 4, 64000, 5000, 256, 156));
        Assertions.assertEquals(a.hashCode(), new FrameTotals(64, 4, 64000, 5000, 256, 156).hashCode());
        Assertions.assertNotEquals(a, new FrameTotals(65, 4, 64000, 5000, 256, 156));
        Assertions.assertNotEquals(a, new FrameTotals(64, 5, 64000, 5000, 256, 156));
        Assertions.assertNotEquals(a, new FrameTotals(64, 4, 64001, 5000, 256, 156));
        Assertions.assertNotEquals(a, new FrameTotals(64, 4, 64000, 5001, 256, 156));
        Assertions.assertNotEquals(a, new FrameTotals(64, 4, 64000, 5000, 257, 156));
        Assertions.assertNotEquals(a, new FrameTotals(64, 4, 64000, 5000, 256, 157));
        // The same sum in other classes is another value
        Assertions.assertNotEquals(a, new FrameTotals(64, 4, 5000, 64000, 256, 156));
        Assertions
            .assertEquals("FrameTotals[64 B, 4 records, clear 64000, fill 5000, copy 256, set 156 px]", a.toString());
    }
}
