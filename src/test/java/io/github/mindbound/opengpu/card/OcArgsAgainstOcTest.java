package io.github.mindbound.opengpu.card;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.fixtures.device.ArgRows;
import io.github.mindbound.opengpu.core.fixtures.device.ArgRows.NumberKind;
import io.github.mindbound.opengpu.core.fixtures.device.M0Replay;
import io.github.mindbound.opengpu.core.fixtures.device.OcArgs;
import io.github.mindbound.opengpu.fixtures.OcCalls;
import li.cil.oc.api.machine.Arguments;

/**
 * {@code :core}'s models of OC's argument reads against OC's own {@code ArgumentsImpl} from the dev jar (S11 plan
 * P15.4; spike §5): {@link OcArgs}' {@code checkInteger}, which the harness's replay and M0Replay use, and M0Replay's
 * {@code checkByteArray} and {@code optString}. The spike's 14 values, the Long values 5.3 and 5.4 deliver (28:1129),
 * which the spike did not try, and ArgRows' rows of both number kinds: each gives the same int or the same message.
 */
@Timeout(60)
class OcArgsAgainstOcTest {

    @Test
    void checkIntegerIsOcsOnEveryValue() throws Exception {
        List<Object> values = new ArrayList<>(
            Arrays.asList(
                // The spike's 14 (spike.md §5)
                0.0,
                16.7,
                -16.7,
                1e10,
                2147483647.5,
                Double.POSITIVE_INFINITY,
                -1e10,
                Double.NEGATIVE_INFINITY,
                -0.0,
                3,
                Double.NaN,
                "7",
                null,
                true,
                // Longs, as 5.3 and 5.4 deliver integers: 3, 2^31, -2^31 - 1 and 2^40
                3L,
                1L << 31,
                -(1L << 31) - 1,
                1L << 40));
        Assertions.assertEquals(18, values.size());
        for (ArgRows.Value row : ArgRows.integerRows()) {
            for (NumberKind kind : NumberKind.values()) {
                values.add(row.java(kind, Collections.<String, Integer>emptyMap()));
            }
        }
        for (Object v : values) {
            Assertions.assertEquals(oc(v), model(v), "checkInteger(" + show(v) + ")");
        }
    }

    // Past the end OC's message has its plural, and the string reads take a byte[] itself, a String's UTF-8 bytes,
    // and nil or no value for optString's default (ArgumentsImpl.scala:165-186, :305-321)
    @Test
    void theListFormAndTheStringReadsAreOcs() throws Exception {
        for (int given = 0; given <= 2; given++) {
            Object[] args = new Object[given];
            Arrays.fill(args, 1.0);
            for (int index = 0; index <= 2; index++) {
                Arguments oc = OcCalls.args(args);
                int i = index;
                Assertions.assertEquals(
                    outcome(() -> oc.checkInteger(i)),
                    outcome(() -> OcArgs.checkInteger(args, i)),
                    "checkInteger(" + index + ") of " + given);
            }
        }
        byte[] high = { (byte) 0x80, (byte) 0xFF, 'a' };
        List<Object> values = new ArrayList<>(
            Arrays.asList(high, "é€", "", 5L, 5.0, 5, true, null, Collections.emptyMap()));
        for (ArgRows.Value row : ArgRows.byteRows()) {
            if (!row.isMissing()) {
                values.add(row.java(NumberKind.LONG, Collections.<String, Integer>emptyMap()));
                values.add(row.java(NumberKind.DOUBLE, Collections.<String, Integer>emptyMap()));
            }
        }
        for (Object v : values) {
            Object[] one = { v };
            Arguments oc = OcCalls.args(v);
            Assertions.assertEquals(
                outcome(() -> show(oc.checkByteArray(0))),
                outcome(() -> show(M0Replay.byteArray(one, 0))),
                "checkByteArray(" + show(v) + ")");
            Assertions.assertEquals(
                outcome(() -> show(oc.optString(0, null))),
                outcome(() -> show(M0Replay.optString(one, 0))),
                "optString(" + show(v) + ")");
        }
        // OC hands over the byte[] itself (28:84), and so does the model
        Assertions.assertSame(
            high,
            OcCalls.args((Object) high)
                .checkByteArray(0));
        Assertions.assertSame(high, M0Replay.byteArray(new Object[] { high }, 0));
        Arguments none = OcCalls.args();
        Assertions.assertEquals(
            outcome(() -> show(none.checkByteArray(0))),
            outcome(() -> show(M0Replay.byteArray(new Object[0], 0))),
            "checkByteArray past the end");
        Assertions.assertEquals("nil", show(M0Replay.optString(new Object[0], 0)));
        Assertions.assertNull(none.optString(0, null));
    }

    private static String oc(Object v) throws Exception {
        Arguments args = OcCalls.args(v);
        return outcome(() -> args.checkInteger(0));
    }

    private static String model(Object v) {
        return outcome(() -> OcArgs.checkInteger(0, v));
    }

    private interface Read {

        Object get();
    }

    /** The value read, or {@code "error <message>"} for OC's argument error. */
    private static String outcome(Read read) {
        try {
            return String.valueOf(read.get());
        } catch (IllegalArgumentException e) {
            return "error " + e.getMessage();
        }
    }

    private static String show(Object v) {
        if (v == null) {
            return "nil";
        }
        if (v instanceof byte[]) {
            return "byte[] " + Arrays.toString((byte[]) v);
        }
        if (v instanceof String) {
            return "String \"" + v + "\" " + Arrays.toString(((String) v).getBytes(StandardCharsets.UTF_8));
        }
        return v.getClass()
            .getSimpleName() + " "
            + v;
    }
}
