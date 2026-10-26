package io.github.mindbound.opengpu.core.fixtures.device;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.device.ArgRows.Call;
import io.github.mindbound.opengpu.core.fixtures.device.ArgRows.Value;

/**
 * Callback mapping rows (S11 plan P15.4; amendments F-2): for each M0 callback but {@code bind}, a valid call and a
 * call with a content error, a {@code nil, msg}, in one script on a T2 device on a {@code FakeRenderHost} running the
 * tier-E oracle ({@code DeviceRig.tier(2)}), with handles named as {@link ArgRows} names them and integers as 5.3
 * delivers them ({@link ArgRows.NumberKind#LONG}). {@code getCaps} has no content error. A row may then step the host,
 * which runs the offered records and reads the frame back, so the device folds it.
 * <p>
 * The rows that see {@code readPixels}' argument order (F-2): NoRendererTest's asymmetric rows on T2
 * (NoRendererTest.scala:73-80), (0, 200, 1, 1) invalid and (200, 0, 1, 1) and (319, 199, 1, 1) valid, and a region
 * of a frame that is not uniform, a 3 x 2 corner filled with 9, read at (1, 0, 4, 3), whose bytes swapped arguments
 * change. Likewise for {@code createTexture} and {@code updateTexture}, whose square textures and regions hid their
 * order (S11 review) [proposal]: a 16 x 4 texture, written at (10, 0, 6, 1) and whole, which swapped arguments refuse,
 * and at (0, 10, 1, 6), "invalid texture region", which they accept.
 */
public final class MappingRows {

    /** One call, what it must give, and whether the host then steps. Immutable. */
    public static final class Row {

        public final Call call;
        /** Null when the call is valid (its first result is not nil), else the {@code msg} of its {@code nil, msg}. */
        public final String message;
        /** Whether the host runs and reads back after the call. */
        public final boolean step;

        Row(Call call, String message, boolean step) {
            this.call = call;
            this.message = message;
            this.step = step;
        }

        @Override
        public String toString() {
            return call + (call.keep == null ? "" : " as " + call.keep)
                + (message == null ? " valid" : " gives \"" + message + "\"")
                + (step ? ", then a step" : "");
        }
    }

    private static final String TEX = ArgRows.TEX;
    /** The 16 x 4 texture's name. */
    private static final String WIDE = "wide";
    private static final List<Row> ROWS;

    static {
        List<Row> rows = new ArrayList<>();
        // createTexture (28 §6.2)
        rows.add(row("createTexture", TEX, null, n(16), n(16)));
        rows.add(row("createTexture", null, "invalid texture size", n(0), n(1)));
        // updateTexture (28 §6.3)
        rows.add(
            row(
                "updateTexture",
                null,
                null,
                Value.handle(TEX),
                n(0),
                n(0),
                n(2),
                n(2),
                bytes(new byte[] { 1, 2, 3, 4 })));
        rows.add(
            row(
                "updateTexture",
                null,
                "data must be 4 bytes",
                Value.handle(TEX),
                n(0),
                n(0),
                n(2),
                n(2),
                bytes(new byte[3])));
        // A 16 x 4 texture, written at (10, 0, 6, 1), at (0, 10, 1, 6), which lies outside it, and whole: swapped
        // arguments of createTexture or updateTexture give other answers
        rows.add(row("createTexture", WIDE, null, n(16), n(4)));
        rows.add(row("updateTexture", null, null, Value.handle(WIDE), n(10), n(0), n(6), n(1), bytes(new byte[6])));
        rows.add(
            row(
                "updateTexture",
                null,
                "invalid texture region",
                Value.handle(WIDE),
                n(0),
                n(10),
                n(1),
                n(6),
                bytes(new byte[6])));
        rows.add(row("updateTexture", null, null, Value.handle(WIDE), n(0), n(0), n(16), n(4), bytes(new byte[64])));
        // submit (28 §3.5): a corner filled, so frame 1 is not uniform
        rows.add(row("submit", null, null, bytes(TestEncoder.segment(TestEncoder.fill(0, 0, 3, 2, 9)))));
        rows.add(
            row(
                "submit",
                null,
                "0: header: segment of 3 bytes is shorter than the 8-byte header",
                bytes(new byte[] { 1, 2, 3 })));
        // present (24:126), then the frame is confirmed
        rows.add(new Row(new Call("present", Collections.<Value>emptyList(), null), null, true));
        // readPixels (28 §5.7): the corner, NoRendererTest's asymmetric rows, and the per-call cap (28:342)
        rows.add(row("readPixels", null, null, n(1), n(0), n(4), n(3)));
        rows.add(row("readPixels", null, "invalid region", n(0), n(200), n(1), n(1)));
        rows.add(row("readPixels", null, null, n(200), n(0), n(1), n(1)));
        rows.add(row("readPixels", null, null, n(319), n(199), n(1), n(1)));
        rows.add(row("readPixels", null, "region exceeds 32768 bytes per call", n(0), n(0), n(320), n(103)));
        // Two frames unconfirmed, the cap, so the third present is busy (28:98); the step confirms them
        rows.add(row("present", null, null));
        rows.add(row("present", null, null));
        rows.add(new Row(new Call("present", Collections.<Value>emptyList(), null), "busy", true));
        // free (28 §6.4)
        rows.add(row("free", null, null, Value.handle(TEX)));
        rows.add(row("free", null, "handle 0x08000000 is not live", Value.handle(TEX)));
        // getCaps (28 §8.1; F-1)
        rows.add(row("getCaps", null, null));
        ROWS = Collections.unmodifiableList(rows);
    }

    private MappingRows() {}

    /** The script, in order, from a fresh device. */
    public static List<Row> rows() {
        return ROWS;
    }

    private static Row row(String callback, String keep, String message, Value... args) {
        return new Row(new Call(callback, Arrays.asList(args), keep), message, false);
    }

    private static Value n(long v) {
        return Value.integer(v);
    }

    private static Value bytes(byte[] b) {
        return Value.bytes(b.length + " B", b);
    }
}
