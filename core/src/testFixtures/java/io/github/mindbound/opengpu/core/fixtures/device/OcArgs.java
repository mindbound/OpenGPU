package io.github.mindbound.opengpu.core.fixtures.device;

import java.util.Map;

/**
 * OC's argument conversion, so that tests can write 28:920's rows in Lua numbers (10^10, 16.7, NaN) although
 * {@code :core}'s device takes {@code int}s (28:84; H7). A transcription of {@code ArgumentsImpl.checkInteger} and its
 * messages ({@code OCT/scala/li/cil/oc/server/machine/ArgumentsImpl.scala:60-112}, {@code :310-337}), which
 * ocelot-brain repeats ({@code src/main/scala/totoro/ocelot/brain/entity/machine/Arguments.scala:84-120},
 * {@code :554-580}). The index is 0-based, as OC's; the messages count from 1.
 * <ul>
 * <li>a {@code Double} or {@code Float}: NaN raises "bad argument #n (number has no integer representation)"; above
 * {@code Integer.MAX_VALUE} gives MAX_VALUE, below {@code MIN_VALUE} gives MIN_VALUE (infinities included); otherwise
 * the value truncated toward zero, so 16.7 is 16 and -16.7 is -16;</li>
 * <li>a {@code Long}: saturated the same way;</li>
 * <li>any other {@code Number} ({@code Integer}, {@code Short}, {@code Byte}, ...): its {@code intValue()};</li>
 * <li>anything else: "bad argument #n (integer expected, got T)", T being OC's type name: nil, boolean, string (a
 * {@code String} or a {@code byte[]}), table (a {@code Map}), or the class's simple name.</li>
 * </ul>
 */
public final class OcArgs {

    private OcArgs() {}

    /**
     * {@code args.checkInteger(index)} for an argument present at that index (nil included).
     *
     * @throws IllegalArgumentException  with OC's message for NaN or a value that is no number
     * @throws IndexOutOfBoundsException for a negative index, as OC's {@code checkIndex}
     */
    public static int checkInteger(int index, Object value) {
        if (index < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (value instanceof Double) {
            double v = (Double) value;
            if (Double.isNaN(v)) {
                throw intError(index, value);
            } else if (v > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            } else if (v < Integer.MIN_VALUE) {
                return Integer.MIN_VALUE;
            } else {
                return (int) v;
            }
        }
        if (value instanceof Float) {
            float v = (Float) value;
            if (Float.isNaN(v)) {
                throw intError(index, value);
            } else if (v > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            } else if (v < Integer.MIN_VALUE) {
                return Integer.MIN_VALUE;
            } else {
                return (int) v;
            }
        }
        if (value instanceof Long) {
            long v = (Long) value;
            if (v > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            } else if (v < Integer.MIN_VALUE) {
                return Integer.MIN_VALUE;
            } else {
                return (int) v;
            }
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        throw new IllegalArgumentException(
            "bad argument #" + (index + 1) + " (integer expected, got " + typeName(value) + ")");
    }

    /**
     * {@code args.checkInteger(index)} on a whole argument list: past its end OC raises
     * "bad arguments #n (integer expected, got no value)", with its plural ({@code ArgumentsImpl.scala:310-313}).
     *
     * @throws IllegalArgumentException  as {@link #checkInteger(int, Object)}, or past the list's end
     * @throws IndexOutOfBoundsException for a negative index
     */
    public static int checkInteger(Object[] args, int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (index >= args.length) {
            throw new IllegalArgumentException("bad arguments #" + (index + 1) + " (integer expected, got no value)");
        }
        return checkInteger(index, args[index]);
    }

    /** OC's type name for a message ({@code ArgumentsImpl.scala:323-337}). */
    public static String typeName(Object value) {
        if (value == null) {
            return "nil";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return "integer";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof String || value instanceof byte[]) {
            return "string";
        }
        if (value instanceof Map) {
            return "table";
        }
        return value.getClass()
            .getSimpleName();
    }

    private static IllegalArgumentException intError(int index, Object value) {
        return new IllegalArgumentException(
            "bad argument #" + (index + 1) + " (" + typeName(value) + " has no integer representation)");
    }
}
