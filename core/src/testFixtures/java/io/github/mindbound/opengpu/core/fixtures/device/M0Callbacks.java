package io.github.mindbound.opengpu.core.fixtures.device;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The M0 callbacks' spec (28:93-102; S11 plan P15.4; amendments F-1), which the root's card and the harness's
 * OpenGpuEntity are both held to, since neither can load the other (RM:544): per callback, its Lua name,
 * {@code direct}, {@code limit}, {@code doc}, its argument reads in order and whether only the card has it. The doc
 * strings are part of parity (Q-S11-11): each side's {@code @Callback} carries these texts, in OC's form
 * {@code function(arg:type):result -- text} (00:31). Both annotations have OC's six elements with OC's defaults
 * (S11 api.md §6.5), so {@code value} is blank, the Lua name being the method's, and {@code getter} and
 * {@code setter} are false for every callback.
 * <p>
 * {@code getCaps}' doc is generic, so that it is true of both sides, and its parity is Device's 15 keys (28:675-690)
 * plus {@link #RENDERER}, "host-gl" while the host is available and "none" otherwise, the rule both sides use
 * (24:631); the root's {@code readback}, {@code graphics} and {@code hostRenderer} are its own (F-1). {@code bind} is
 * the card's alone: its messages belong to the display (28:95), and the harness has no display.
 */
public final class M0Callbacks {

    /** {@code getCaps}' key for the host's renderer (24:125). */
    public static final String RENDERER = "renderer";
    /** {@link #RENDERER} while the host is available, so also on a FakeRenderHost, which has no GL (24:631). */
    public static final String HOST_GL = "host-gl";
    /** {@link #RENDERER} while it is not. */
    public static final String NONE = "none";

    /** How a callback reads one argument: through which of OC's {@code Arguments} methods (28:84). */
    public enum Read {

        /** {@code checkInteger}: saturating and truncating (OcArgs). */
        INTEGER("integer", false),
        /** {@code checkByteArray}: a Lua string's bytes, the per-call array itself. */
        BYTES("string", false),
        /** {@code checkString}. */
        STRING("string", false),
        /** {@code optString(i, null)}: null when absent or nil. */
        OPT_STRING("string", true);

        /** The type OC's argument errors name, as in "bad argument #1 (integer expected, got string)". */
        public final String expected;
        /** Whether a missing or nil argument is no error. */
        public final boolean optional;

        Read(String expected, boolean optional) {
            this.expected = expected;
            this.optional = optional;
        }
    }

    /** One callback. Immutable. */
    public static final class Spec {

        /** The Lua name, which is the method's. */
        public final String name;
        public final boolean direct;
        /** OC's calls per tick: the default, {@code Integer.MAX_VALUE}, so 1/limit is charged per direct call. */
        public final int limit;
        public final String doc;
        /** The argument reads, in the order the callback makes them. */
        public final List<Read> reads;
        /** Whether only the root's card has it: {@code bind}. */
        public final boolean cardOnly;

        Spec(String name, boolean direct, String doc, boolean cardOnly, Read... reads) {
            this.name = name;
            this.direct = direct;
            this.limit = Integer.MAX_VALUE;
            this.doc = doc;
            this.reads = Collections.unmodifiableList(Arrays.asList(reads));
            this.cardOnly = cardOnly;
        }

        @Override
        public String toString() {
            return name + (direct ? " direct" : " non-direct") + " " + reads + (cardOnly ? " card only" : "");
        }
    }

    private static final List<Spec> ALL = Collections.unmodifiableList(
        Arrays.asList(
            new Spec(
                "bind",
                false,
                "function(address:string):boolean or nil, string -- maps the display with the address to the card's "
                    + "device, taking it from any card bound to it before (28 §2.1)",
                true,
                Read.STRING),
            new Spec(
                "getCaps",
                true,
                "function():table -- the device's caps (28 §8.1) plus the host keys of 24:125",
                false),
            new Spec(
                "submit",
                true,
                "function(stream:string):boolean or nil, string -- validates the segment whole and appends it to "
                    + "the open frame (28 §3.5)",
                false,
                Read.BYTES),
            new Spec(
                "present",
                true,
                "function():number or nil, string -- seals the open frame into FRAME(frameId); \"busy\", "
                    + "\"no renderer\" or \"host render stalled\" otherwise (24:126)",
                false),
            new Spec(
                "readPixels",
                true,
                "function(x:number, y:number, w:number, h:number):string, number or nil, string -- a region of the "
                    + "newest confirmed frame in the surface byte layout, and its frame id (28 §5.7)",
                false,
                Read.INTEGER,
                Read.INTEGER,
                Read.INTEGER,
                Read.INTEGER),
            new Spec(
                "createTexture",
                true,
                "function(w:number, h:number[, format:string]):number or nil, string -- a texture handle (28 §6.2)",
                false,
                Read.INTEGER,
                Read.INTEGER,
                Read.OPT_STRING),
            new Spec(
                "updateTexture",
                true,
                "function(tex:number, x:number, y:number, w:number, h:number, data:string):boolean or nil, string "
                    + "-- a region of the texture's rows (28 §6.3)",
                false,
                Read.INTEGER,
                Read.INTEGER,
                Read.INTEGER,
                Read.INTEGER,
                Read.INTEGER,
                Read.BYTES),
            new Spec(
                "free",
                true,
                "function(tex:number):boolean or nil, string -- frees the texture, deferred while a frame is open "
                    + "(28 §6.4)",
                false,
                Read.INTEGER)));

    private M0Callbacks() {}

    /** All eight, in 28:93-102's order: {@code bind} first. */
    public static List<Spec> all() {
        return ALL;
    }

    /** The seven both sides have: all but {@code bind}. */
    public static List<Spec> shared() {
        List<Spec> out = new ArrayList<>();
        for (Spec s : ALL) {
            if (!s.cardOnly) {
                out.add(s);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * The callback with this Lua name.
     *
     * @throws IllegalArgumentException for a name that is not an M0 callback
     */
    public static Spec get(String name) {
        for (Spec s : ALL) {
            if (s.name.equals(name)) {
                return s;
            }
        }
        throw new IllegalArgumentException(name + " is no M0 callback (28:93-102)");
    }
}
