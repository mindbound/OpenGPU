package io.github.mindbound.opengpu.core.fixtures.device;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import io.github.mindbound.opengpu.core.fixtures.TestEncoder;

/**
 * The argument rows both sides of callback parity run (S11 plan P15.4; amendments F-2, F-3), each as Lua source for
 * {@code :ocelot-harness}'s calls.lua and as the Java value OC delivers for that source, which the root's tests hand
 * to OC's {@code ArgumentsImpl}:
 * <ul>
 * <li>{@link #integerRows()}: S10's seven rows of 28:920 (ArgumentRowsTest.scala:63), in every integer argument of
 * {@code readPixels}, {@code createTexture}, {@code updateTexture} and {@code free} ({@link #integerSites()});</li>
 * <li>{@link #byteRows()}: a byte string with bytes of 0x80 and above, a {@code java.lang.String}, a number, nil and a
 * missing argument, in {@code submit}'s stream, {@code updateTexture}'s data and {@code createTexture}'s format
 * ({@link #byteSites()}).</li>
 * </ul>
 * A Lua number reaches Java as a {@code Long} when it is an integer, on 5.3 and 5.4, and as a {@code Double}
 * otherwise and on 5.2 and LuaJ (28:1129), and on OC-LuaJIT (00:85): {@link NumberKind}. A Lua string reaches Java as
 * a {@code byte[]} (00:85; ExtendedLuaState.scala:108), so the {@code String} row is the root's only: Lua cannot make
 * it, and the harness skips it (F-2). The other arguments of each site are valid, on a T2 device after
 * {@link #SETUP}, whose handle the rows name {@link #TEX}.
 */
public final class ArgRows {

    /** The name {@link #SETUP}'s handle is kept under, which {@code updateTexture}'s rows use. */
    public static final String TEX = "tex";

    /** How a Lua number reaches Java (28:1129). */
    public enum NumberKind {

        /** 5.3 and 5.4: an integer as a {@code Long}, a float as a {@code Double}. */
        LONG,
        /** 5.2, LuaJ and OC-LuaJIT: every number as a {@code Double} (00:85). */
        DOUBLE;

        /** The integer {@code n} as this kind delivers it. */
        public Object number(long n) {
            return this == LONG ? (Object) Long.valueOf(n) : (Object) Double.valueOf(n);
        }
    }

    /** One argument: Lua source and the Java value OC delivers for it. Immutable. */
    public static final class Value {

        /** For messages: the Lua source, or what the value is. */
        public final String label;
        private final String lua;
        private final Object asLong;
        private final Object asDouble;
        private final String handle;
        private final boolean missing;

        private Value(String label, String lua, Object asLong, Object asDouble, String handle, boolean missing) {
            this.label = label;
            this.lua = lua;
            this.asLong = asLong;
            this.asDouble = asDouble;
            this.handle = handle;
            this.missing = missing;
        }

        /** A value Lua source gives as the same Java value on every kind, such as 1e10 or a string. */
        static Value same(String lua, Object java) {
            return new Value(lua, lua, java, java, null, false);
        }

        /** The integer {@code n}: a {@code Long} or a {@code Double} by kind. */
        static Value integer(long n) {
            return new Value(Long.toString(n), Long.toString(n), Long.valueOf(n), Double.valueOf(n), null, false);
        }

        /** The handle kept under {@code name}, which Lua holds in {@code V.<name>}: an integer by kind. */
        static Value handle(String name) {
            return new Value("V." + name, "V." + name, null, null, name, false);
        }

        /** A byte string, as a Lua string literal. */
        static Value bytes(String label, byte[] bytes) {
            return new Value(label, ArgRows.lua(bytes), bytes, bytes, null, false);
        }

        /** A value only Java can deliver. */
        static Value rootOnly(String label, Object java) {
            return new Value(label, null, java, java, null, false);
        }

        /** No argument at all. */
        static Value missing() {
            return new Value("missing", null, null, null, null, true);
        }

        /** Whether the value is no argument at all: the call ends before it. */
        public boolean isMissing() {
            return missing;
        }

        /** Whether only the root can deliver it: there is no Lua source for it (F-2). */
        public boolean isRootOnly() {
            return lua == null && !missing;
        }

        /**
         * The Lua source.
         *
         * @throws IllegalStateException for a missing or root-only value
         */
        public String lua() {
            if (lua == null) {
                throw new IllegalStateException(label + " has no Lua source");
            }
            return lua;
        }

        /**
         * The Java value OC delivers for it on {@code kind}: a fresh array for a byte string, since the callee owns
         * what
         * {@code checkByteArray} hands over (28:84); for a handle, the one kept under its name.
         *
         * @throws IllegalStateException for a missing value, or a handle not kept yet
         */
        public Object java(NumberKind kind, Map<String, Integer> handles) {
            if (missing) {
                throw new IllegalStateException("a missing argument has no value");
            }
            if (handle != null) {
                Integer h = handles.get(handle);
                if (h == null) {
                    throw new IllegalStateException("no handle kept as " + handle + " in " + handles);
                }
                return kind.number(h);
            }
            Object v = kind == NumberKind.LONG ? asLong : asDouble;
            return v instanceof byte[] ? ((byte[]) v).clone() : v;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** One call: the callback and its arguments, the first result kept under {@link #keep} unless null. Immutable. */
    public static final class Call {

        public final String callback;
        public final List<Value> args;
        public final String keep;

        Call(String callback, List<Value> args, String keep) {
            this.callback = callback;
            this.args = Collections.unmodifiableList(new ArrayList<>(args));
            this.keep = keep;
        }

        /** The arguments' Lua sources, for calls.lua. */
        public List<String> lua() {
            List<String> out = new ArrayList<>();
            for (Value v : args) {
                out.add(v.lua());
            }
            return out;
        }

        /** The arguments as OC delivers them on {@code kind}. */
        public Object[] java(NumberKind kind, Map<String, Integer> handles) {
            Object[] out = new Object[args.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = args.get(i)
                    .java(kind, handles);
            }
            return out;
        }

        /** Whether Lua can make the call: no argument is root-only. */
        public boolean luaCanMake() {
            for (Value v : args) {
                if (v.isRootOnly()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return callback + args;
        }
    }

    /** Where a row goes: one argument of a callback, the others valid. Immutable. */
    public static final class Site {

        public final String callback;
        /** The valid arguments; a row replaces the one at {@link #position}, or follows the last. */
        public final List<Value> base;
        public final int position;

        Site(String callback, int position, Value... base) {
            if (position < 0 || position > base.length) {
                throw new IllegalArgumentException(callback + ": position " + position + " of " + base.length);
            }
            this.callback = callback;
            this.base = Collections.unmodifiableList(Arrays.asList(base));
            this.position = position;
        }

        /**
         * The call with {@code row} at {@link #position}; a missing row ends the arguments there.
         *
         * @throws IllegalArgumentException for a missing row at a position before the last
         */
        public Call with(Value row) {
            List<Value> args = new ArrayList<>(base);
            if (row.isMissing()) {
                if (position < base.size() - 1) {
                    throw new IllegalArgumentException(this + ": a missing argument before the last");
                }
                return new Call(callback, args.subList(0, position), null);
            }
            if (position < args.size()) {
                args.set(position, row);
            } else {
                args.add(row);
            }
            return new Call(callback, args, null);
        }

        @Override
        public String toString() {
            return callback + " argument #" + (position + 1);
        }
    }

    /** {@code createTexture(16, 16)} on the fresh device, kept as {@link #TEX}. */
    public static final Call SETUP = new Call(
        "createTexture",
        Arrays.asList(Value.integer(16), Value.integer(16)),
        TEX);

    private static final List<Value> INTEGER_ROWS = Collections.unmodifiableList(
        Arrays.asList(
            Value.same("1e10", 1e10),
            Value.same("-1e10", -1e10),
            new Value("10000000000", "10000000000", 10_000_000_000L, 1e10, null, false),
            Value.same("16.7", 16.7),
            Value.same("-16.7", -16.7),
            Value.same("0/0", Double.NaN),
            Value.same("\"x\"", new byte[] { 'x' })));

    private static final List<Value> BYTE_ROWS = Collections.unmodifiableList(
        Arrays.asList(
            // A whole segment, CLEAR(0x80) on T2: valid as bytes, but 0x80 and END's 0xFF are no UTF-8, so a String
            // made from it and encoded back is another stream
            Value.bytes("a byte string with bytes of 0x80 and above", TestEncoder.segment(TestEncoder.clear(0x80))),
            Value.rootOnly("a java.lang.String", "\u00e9\u20ac"),
            Value.integer(5),
            Value.same("nil", null),
            Value.missing()));

    private static final List<Site> INTEGER_SITES;
    private static final List<Site> BYTE_SITES;

    static {
        Value zero = Value.integer(0);
        Value one = Value.integer(1);
        Value pixel = Value.bytes("\"\\000\"", new byte[1]);
        List<Site> integers = new ArrayList<>();
        Value[] read = { zero, zero, one, one };
        for (int i = 0; i < read.length; i++) {
            integers.add(new Site("readPixels", i, read));
        }
        Value[] create = { one, one };
        for (int i = 0; i < create.length; i++) {
            integers.add(new Site("createTexture", i, create));
        }
        Value[] update = { Value.handle(TEX), zero, zero, one, one, pixel };
        for (int i = 0; i < 5; i++) {
            integers.add(new Site("updateTexture", i, update));
        }
        integers.add(new Site("free", 0, zero));
        INTEGER_SITES = Collections.unmodifiableList(integers);
        BYTE_SITES = Collections.unmodifiableList(
            Arrays.asList(
                new Site("submit", 0),
                new Site("updateTexture", 5, Value.handle(TEX), zero, zero, one, one),
                new Site("createTexture", 2, one, one)));
    }

    private ArgRows() {}

    /** S10's seven rows: ±10^10 as floats, 10000000000 as written, ±16.7, 0/0 and "x". */
    public static List<Value> integerRows() {
        return INTEGER_ROWS;
    }

    /** A byte string of bytes of 0x80 and above, a String (the root's only), the integer 5, nil, and missing. */
    public static List<Value> byteRows() {
        return BYTE_ROWS;
    }

    /** Every integer argument of the four callbacks that take one, the others valid: 12 sites (28:920). */
    public static List<Site> integerSites() {
        return INTEGER_SITES;
    }

    /** {@code submit}'s stream, {@code updateTexture}'s data and {@code createTexture}'s format, each the last. */
    public static List<Site> byteSites() {
        return BYTE_SITES;
    }

    /**
     * Whether {@code delivered} is {@code expected} as OC delivers values: the same class and value, a
     * {@code byte[]} by content, and NaN equal to NaN, as {@code Double.equals} has it.
     */
    public static boolean same(Object expected, Object delivered) {
        if (expected instanceof byte[] && delivered instanceof byte[]) {
            return Arrays.equals((byte[]) expected, (byte[]) delivered);
        }
        return Objects.equals(expected, delivered);
    }

    /**
     * A Lua string literal of the bytes, by the harness's rule (Records.scala's {@code Lua.str}): letters, digits
     * and space as they are, every other byte as a three-digit decimal escape, which every runtime reads alike.
     */
    public static String lua(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 4 + 2).append('"');
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == ' ') {
                out.append((char) c);
            } else {
                out.append('\\')
                    .append(String.format(Locale.ROOT, "%03d", c));
            }
        }
        return out.append('"')
            .toString();
    }
}
