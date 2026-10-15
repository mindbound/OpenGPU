package io.github.mindbound.opengpu.core.cmd;

import java.util.Collection;

import org.junit.jupiter.api.Assertions;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.fixtures.MapLiveView;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.naive.NaiveValidator;

/**
 * Every hand-written expectation of the cmd tests goes through here, so each one is asserted against the main
 * validator and the naive one (28 §11.2: "every expected result comes from §3.6's tables, through the naive
 * validator of §11.3"). The main validator must also leave the segment unwritten (C14) and give a message of the
 * form {@code "<offset>: <OP>: <reason>"} (28 §3.6).
 */
public final class Expectations {

    /** A new device's first texture (28 §6.1), which {@link #t2()} and {@link #t3()} hold as 16x16. */
    public static final int TEXTURE = 0x08000000;

    private Expectations() {}

    /** T2's surface and caps (28 §8.1) with {@link #TEXTURE} live, 16x16 index8. */
    static MapLiveView t2() {
        return tier(2);
    }

    /** T3's surface and caps (28 §8.1) with {@link #TEXTURE} live, 16x16 rgb565. */
    static MapLiveView t3() {
        return tier(3);
    }

    static MapLiveView tier(int tier) {
        MapLiveView view = new MapLiveView(SurfaceDesc.forTier(tier), FrameLimits.forTier(tier));
        int handle = view.create(16, 16);
        Assertions.assertEquals(TEXTURE, handle);
        return view;
    }

    /** The view's surface with other caps, and no texture. */
    static MapLiveView withLimits(SurfaceDesc surface, FrameLimits limits) {
        return new MapLiveView(surface, limits);
    }

    // Rejections

    public static StreamError assertRejected(byte[] s, MapLiveView view, ErrorCode code, int offset, String op) {
        return assertRejected(s, view, view.handles(), FrameTotals.EMPTY, code, offset, op);
    }

    public static StreamError assertRejected(byte[] s, MapLiveView view, FrameTotals open, ErrorCode code, int offset,
        String op) {
        return assertRejected(s, view, view.handles(), open, code, offset, op);
    }

    /**
     * Asserts that both validators reject s with this code, offset and {@code <OP>}, and that they agree on the
     * detail of row 21; returns the main validator's error for further checks of its detail and message.
     */
    public static StreamError assertRejected(byte[] s, LiveView view, Collection<Integer> live, FrameTotals open,
        ErrorCode code, int offset, String op) {
        Validation main = validate(s, view, open);
        String where = " for " + TestEncoder.toHex(s);
        Assertions.assertFalse(main.isAccepted(), "main accepted" + where);
        StreamError e = main.error();
        Assertions.assertEquals(code, e.code, "main " + e + where);
        Assertions.assertEquals(offset, e.offset, "main " + e + where);
        Assertions.assertEquals(op, e.op(), "main " + e + where);
        Assertions.assertEquals(e.offset + ": " + e.op() + ": " + e.reason, e.message());
        Assertions.assertThrows(IllegalStateException.class, main::totals);
        NaiveValidator.Result naive = NaiveValidator.validate(s, NaiveBridge.config(view, live, open));
        Assertions.assertFalse(naive.ok, "naive accepted" + where);
        Assertions.assertEquals(code.name(), naive.code, "naive " + naive + where);
        Assertions.assertEquals(offset, naive.offset, "naive " + naive + where);
        Assertions.assertEquals(op, naive.op, "naive " + naive + where);
        if (code == ErrorCode.BAD_UTF8 || code == ErrorCode.CONTROL_CHAR) {
            Assertions.assertEquals(e.detail, naive.detail, "naive " + naive + where);
        }
        return e;
    }

    // Acceptances

    public static FrameTotals assertAccepted(byte[] s, MapLiveView view, FrameTotals expected) {
        return assertAccepted(s, view, view.handles(), FrameTotals.EMPTY, expected);
    }

    public static FrameTotals assertAccepted(byte[] s, MapLiveView view, FrameTotals open, FrameTotals expected) {
        return assertAccepted(s, view, view.handles(), open, expected);
    }

    /** Asserts that both validators accept s with the expected segment totals, whose bytes must be {@code #s}. */
    public static FrameTotals assertAccepted(byte[] s, LiveView view, Collection<Integer> live, FrameTotals open,
        FrameTotals expected) {
        Assertions.assertEquals(s.length, expected.bytes, "the expected totals' bytes are #s");
        FrameTotals totals = acceptedByBoth(s, view, live, open);
        Assertions.assertEquals(expected, totals, "main for " + TestEncoder.toHex(s));
        return totals;
    }

    /**
     * Asserts that both validators accept s with the same records and class totals, where the test states no totals
     * of its own (the round trips' edge values); returns the main validator's.
     */
    static FrameTotals assertAcceptedByBoth(byte[] s, MapLiveView view) {
        return acceptedByBoth(s, view, view.handles(), FrameTotals.EMPTY);
    }

    /** Asserts only that the two validators agree on s, for cases whose outcome the naive validator defines. */
    static Validation assertAgree(byte[] s, MapLiveView view) {
        Validation main = validate(s, view, FrameTotals.EMPTY);
        NaiveValidator.Result naive = NaiveBridge.naive(s, view, FrameTotals.EMPTY);
        String disagreement = NaiveBridge.disagreement(main, naive);
        Assertions.assertNull(disagreement, () -> disagreement + " for " + TestEncoder.toHex(s));
        return main;
    }

    private static FrameTotals acceptedByBoth(byte[] s, LiveView view, Collection<Integer> live, FrameTotals open) {
        Validation main = validate(s, view, open);
        String where = " for " + TestEncoder.toHex(s);
        Assertions.assertTrue(main.isAccepted(), () -> "main " + main + where);
        Assertions.assertNull(main.error());
        FrameTotals totals = main.totals();
        Assertions.assertEquals(s.length, totals.bytes, "main's bytes" + where);
        NaiveValidator.Result naive = NaiveValidator.validate(s, NaiveBridge.config(view, live, open));
        Assertions.assertTrue(naive.ok, () -> "naive " + naive + where);
        Assertions.assertEquals(s.length - 1, naive.offset, "naive's END" + where);
        Assertions.assertNull(NaiveBridge.disagreement(main, naive), () -> NaiveBridge.disagreement(main, naive));
        return totals;
    }

    /** The main validator, checked not to write the segment (C14). */
    private static Validation validate(byte[] s, LiveView view, FrameTotals open) {
        byte[] copy = s.clone();
        Validation v = Validator.validate(s, view, open);
        Assertions.assertArrayEquals(copy, s, "the validator wrote the segment");
        return v;
    }
}
