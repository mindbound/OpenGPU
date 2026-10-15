package io.github.mindbound.opengpu.core.fixtures.device;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * OC's {@code checkInteger} as the device's callers see it (28:84; H7): NaN raises, values beyond the {@code int}
 * range saturate, fractions truncate toward zero ({@code ArgumentsImpl.scala:60-112}), and a value that is no number
 * names its Lua type ({@code ArgumentsImpl.scala:310-337}). These are the conversions behind 28:920's rows: 10^10 is
 * 2 147 483 647, -10^10 is -2 147 483 648, 16.7 is 16 and -16.7 is -16.
 */
class OcArgsTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({ "16.7, 16", "-16.7, -16", "15.9, 15", "1.9, 1", "0.9, 0", "-0.9, 0", "0.5, 0", "1e10, 2147483647",
        "-1e10, -2147483648", "1e9, 1000000000", "2147483647, 2147483647", "2147483647.9, 2147483647",
        "2147483646.9, 2147483646", "-2147483648, -2147483648", "-2147483648.9, -2147483648",
        "-2147483647.9, -2147483647", "1207959552, 1207959552", "Infinity, 2147483647", "-Infinity, -2147483648" })
    void aDoubleTruncatesTowardZeroAndSaturates(double value, int expected) {
        Assertions.assertEquals(expected, OcArgs.checkInteger(0, value));
    }

    @Test
    void nanHasNoIntegerRepresentation() {
        assertBad("bad argument #1 (number has no integer representation)", 0, Double.NaN);
        assertBad("bad argument #3 (number has no integer representation)", 2, Float.NaN);
    }

    @Test
    void aFloatConvertsLikeADouble() {
        Assertions.assertEquals(16, OcArgs.checkInteger(0, 16.7f));
        Assertions.assertEquals(-16, OcArgs.checkInteger(0, -16.7f));
        Assertions.assertEquals(Integer.MAX_VALUE, OcArgs.checkInteger(0, 1e10f));
        Assertions.assertEquals(Integer.MIN_VALUE, OcArgs.checkInteger(0, -1e10f));
        Assertions.assertEquals(Integer.MAX_VALUE, OcArgs.checkInteger(0, Float.POSITIVE_INFINITY));
        Assertions.assertEquals(Integer.MIN_VALUE, OcArgs.checkInteger(0, Float.NEGATIVE_INFINITY));
    }

    @Test
    void aLongSaturates() {
        Assertions.assertEquals(Integer.MAX_VALUE, OcArgs.checkInteger(0, Long.MAX_VALUE));
        Assertions.assertEquals(Integer.MAX_VALUE, OcArgs.checkInteger(0, 2147483648L));
        Assertions.assertEquals(Integer.MAX_VALUE, OcArgs.checkInteger(0, 2147483647L));
        Assertions.assertEquals(Integer.MIN_VALUE, OcArgs.checkInteger(0, -2147483649L));
        Assertions.assertEquals(Integer.MIN_VALUE, OcArgs.checkInteger(0, Long.MIN_VALUE));
        Assertions.assertEquals(0x48000000, OcArgs.checkInteger(0, 0x48000000L));
        Assertions.assertEquals(-5, OcArgs.checkInteger(0, -5L));
    }

    @Test
    void otherNumbersGiveTheirIntValue() {
        Assertions.assertEquals(-5, OcArgs.checkInteger(0, Integer.valueOf(-5)));
        Assertions.assertEquals(0x08000000, OcArgs.checkInteger(0, Integer.valueOf(0x08000000)));
        Assertions.assertEquals(-3, OcArgs.checkInteger(0, Short.valueOf((short) -3)));
        Assertions.assertEquals(-1, OcArgs.checkInteger(0, Byte.valueOf((byte) -1)));
        Assertions.assertEquals(7, OcArgs.checkInteger(0, BigInteger.valueOf(7)));
        Assertions.assertEquals(9, OcArgs.checkInteger(0, new AtomicInteger(9)));
        // Number.intValue, not saturation: OC's catch-all case for java.lang.Number (ArgumentsImpl.scala:109)
        Assertions.assertEquals(16, OcArgs.checkInteger(0, new BigDecimal("16.7")));
        Assertions.assertEquals(0, OcArgs.checkInteger(0, BigInteger.ONE.shiftLeft(32)));
    }

    @Test
    void aValueThatIsNoNumberNamesItsLuaType() {
        assertBad("bad argument #1 (integer expected, got nil)", 0, null);
        assertBad("bad argument #2 (integer expected, got string)", 1, "16");
        assertBad("bad argument #2 (integer expected, got string)", 1, new byte[] { '1', '6' });
        assertBad("bad argument #6 (integer expected, got boolean)", 5, Boolean.TRUE);
        assertBad("bad argument #1 (integer expected, got table)", 0, new HashMap<String, Object>());
        assertBad("bad argument #1 (integer expected, got Object)", 0, new Object());
    }

    @Test
    void typeNamesAreOcs() {
        Assertions.assertEquals("nil", OcArgs.typeName(null));
        Assertions.assertEquals("boolean", OcArgs.typeName(false));
        Assertions.assertEquals("integer", OcArgs.typeName(1));
        Assertions.assertEquals("integer", OcArgs.typeName(1L));
        Assertions.assertEquals("integer", OcArgs.typeName((short) 1));
        Assertions.assertEquals("integer", OcArgs.typeName((byte) 1));
        Assertions.assertEquals("number", OcArgs.typeName(1.5));
        Assertions.assertEquals("number", OcArgs.typeName(1.5f));
        Assertions.assertEquals("string", OcArgs.typeName("x"));
        Assertions.assertEquals("string", OcArgs.typeName(new byte[0]));
        Assertions.assertEquals("table", OcArgs.typeName(new HashMap<Object, Object>()));
        Assertions.assertEquals("StringBuilder", OcArgs.typeName(new StringBuilder()));
    }

    @Test
    void pastTheArgumentListThereIsNoValue() {
        Object[] args = { 16.7, null };
        Assertions.assertEquals(16, OcArgs.checkInteger(args, 0));
        IllegalArgumentException nil = Assertions
            .assertThrows(IllegalArgumentException.class, () -> OcArgs.checkInteger(args, 1));
        Assertions.assertEquals("bad argument #2 (integer expected, got nil)", nil.getMessage());
        // OC's checkIndex says "arguments" here (ArgumentsImpl.scala:312-313)
        IllegalArgumentException none = Assertions
            .assertThrows(IllegalArgumentException.class, () -> OcArgs.checkInteger(args, 2));
        Assertions.assertEquals("bad arguments #3 (integer expected, got no value)", none.getMessage());
    }

    @Test
    void aNegativeIndexIsOutOfBounds() {
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> OcArgs.checkInteger(-1, 1.0));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> OcArgs.checkInteger(new Object[] { 1.0 }, -1));
    }

    private static void assertBad(String message, int index, Object value) {
        IllegalArgumentException e = Assertions
            .assertThrows(IllegalArgumentException.class, () -> OcArgs.checkInteger(index, value));
        Assertions.assertEquals(message, e.getMessage());
    }
}
