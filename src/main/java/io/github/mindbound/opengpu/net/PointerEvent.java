package io.github.mindbound.opengpu.net;

import java.util.Objects;

/**
 * One pointer event that a client's GUI sends for a display (24:140; 15:181-187; S11 plan U5.3): its kind; the display
 * block the GUI was opened on, in the sender's world; the display's id; the pointer's position in the display's pixels,
 * 0-based and fractional; and its data byte, the mouse button for touch, drag and drop and the wheel's sign for
 * scroll, signed for every kind as OC reads it (PacketHandler.scala:234, :249, :263). An immutable value, which
 * {@link PointerPacket} encodes and decodes. Nothing here checks the values: the encoder refuses what no client may
 * send, and the server's {@link InputHandler} checks the rest.
 */
public final class PointerEvent {

    /**
     * The four pointer events of 24:140, by their wire code, each with the signal it raises under OC's names
     * (TextBuffer.scala:852-866).
     */
    public enum Kind {

        TOUCH("touch"),
        DRAG("drag"),
        DROP("drop"),
        SCROLL("scroll");

        private static final Kind[] BY_CODE = values();

        private final String signal;

        Kind(String signal) {
            this.signal = signal;
        }

        /** The kind's wire code, 0 to 3. */
        public int code() {
            return ordinal();
        }

        /** The name of the signal Lua sees, OC's. */
        public String signal() {
            return signal;
        }

        /** The kind with the wire code, or null for a code outside 0-3. */
        public static Kind of(int code) {
            return code >= 0 && code < BY_CODE.length ? BY_CODE[code] : null;
        }
    }

    private final int kind;
    private final int x;
    private final int y;
    private final int z;
    private final String displayId;
    private final float px;
    private final float py;
    private final byte data;

    /**
     * An event of {@code kind} for the display block at (x, y, z) whose id is {@code displayId}, at pixel (px, py).
     *
     * @throws NullPointerException for a null kind or id
     */
    public PointerEvent(Kind kind, int x, int y, int z, String displayId, float px, float py, byte data) {
        this(
            Objects.requireNonNull(kind, "kind")
                .code(),
            x,
            y,
            z,
            displayId,
            px,
            py,
            data);
    }

    /** An event with any kind code, also one that no client sends, for tests of the encoder's refusal. */
    PointerEvent(int kind, int x, int y, int z, String displayId, float px, float py, byte data) {
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.z = z;
        this.displayId = Objects.requireNonNull(displayId, "displayId");
        this.px = px;
        this.py = py;
        this.data = data;
    }

    /** The kind, or null when the event's code is none of the four. */
    public Kind kind() {
        return Kind.of(kind);
    }

    /** The kind's wire code. */
    public int kindCode() {
        return kind;
    }

    /** The display block's x. */
    public int x() {
        return x;
    }

    /** The display block's y. */
    public int y() {
        return y;
    }

    /** The display block's z. */
    public int z() {
        return z;
    }

    /** The display's id, its node's address (P12.5). */
    public String displayId() {
        return displayId;
    }

    /** The pointer's x in the display's pixels, 0-based and fractional (15:181). */
    public float px() {
        return px;
    }

    /** The pointer's y in the display's pixels, 0-based and fractional. */
    public float py() {
        return py;
    }

    /** The mouse button, or for scroll the wheel's sign, as a signed byte. */
    public byte data() {
        return data;
    }

    /** Equal kind, block, id, data and the same float bits in px and py, so a NaN equals itself. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PointerEvent)) {
            return false;
        }
        PointerEvent that = (PointerEvent) other;
        return kind == that.kind && x == that.x
            && y == that.y
            && z == that.z
            && displayId.equals(that.displayId)
            && Float.floatToIntBits(px) == Float.floatToIntBits(that.px)
            && Float.floatToIntBits(py) == Float.floatToIntBits(that.py)
            && data == that.data;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, x, y, z, displayId, px, py, data);
    }

    /** For example "PointerEvent[touch at 1 64 -2 on opengpu_screen-2, 5.5 3.0, data 0]". */
    @Override
    public String toString() {
        Kind known = kind();
        return "PointerEvent[" + (known != null ? known.signal() : "kind " + kind)
            + " at "
            + x
            + " "
            + y
            + " "
            + z
            + " on "
            + displayId
            + ", "
            + px
            + " "
            + py
            + ", data "
            + data
            + "]";
    }
}
