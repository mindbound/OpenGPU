package io.github.mindbound.opengpu.net;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;

/**
 * The pointer packet, client to server on the {@value Channel#NAME} channel, version 1 (S11 plan U5.3): a versioned
 * variant of 15:185's {@code (address, float px, float py, drag, button)}, with 09:83's flags byte in front, big-endian
 * as {@code ByteBuf} and {@code DataOutput} write it (09:83, "OC-style raw DataOutput payloads") [proposal for the
 * layout]:
 *
 * <pre>
 * u8   flags      0 in M0; bit 0 is 09:83's deflate flag, reserved; any set bit drops the packet
 * u8   type       1 POINTER; 2 KEY and 3 CLIPBOARD reserved for M1 (09:131); types are numbered per direction
 * u8   version    1
 * u8   kind       0 touch, 1 drag, 2 drop, 3 scroll (24:140)
 * i32  x, y, z    the display block the GUI was opened on, in the sender's world
 * u8   idLength   1..64, then the display's id in UTF-8 (its node's address, P12.5)
 * f32  px, py     0-based pixels, fractional (15:181, 15:185)
 * i8   data       touch, drag, drop: the mouse button; scroll: the wheel's sign; signed for every kind
 * </pre>
 *
 * A packet is {@value #FIXED_BYTES} + idLength bytes, {@value #MIN_BYTES} to {@value #MAX_BYTES}: far under 24:33's
 * client-to-server bound of 32 766 B (C17PacketCustomPayload.java:37-39, :52). The block position lets the server find
 * the display in the sender's world with no address registry, and gives 24:222's "chunk loaded" check; the id guards
 * against a stale GUI on a replaced or re-addressed display (P12.5; B2); the type and version bytes let M1 add its key
 * and clipboard packets and let the server drop an old client's packets cleanly [proposal]. One codec for both ends:
 * S12's client sender encodes, and the server's {@link InputHandler} decodes. It names no Netty or FML type but
 * {@code ByteBuf}.
 */
public final class PointerPacket {

    /** The flags byte of M0: no bit set. */
    public static final int FLAGS = 0;
    /** The pointer packet's type, the first of the client-to-server types. */
    public static final int TYPE = 1;
    /** The layout's version. */
    public static final int VERSION = 1;
    /** The display id's longest UTF-8 form; a node's address, a UUID, takes 36 B. */
    public static final int MAX_ID_BYTES = 64;
    /** The bytes around the id: {@value #HEAD} up to idLength, {@value #TAIL} after the id. */
    public static final int FIXED_BYTES = 26;
    /** The shortest packet, with a 1-byte id. */
    public static final int MIN_BYTES = FIXED_BYTES + 1;
    /** The longest packet, with a 64-byte id. */
    public static final int MAX_BYTES = FIXED_BYTES + MAX_ID_BYTES;

    /** flags, type, version, kind, x, y, z and idLength. */
    private static final int HEAD = 17;
    /** px, py and data. */
    private static final int TAIL = 9;

    private PointerPacket() {}

    /**
     * The event's packet, of exactly {@value #FIXED_BYTES} + idLength bytes. It passes any px and py through,
     * non-finite ones too, so a test can build what a forged client sends; the server checks them.
     *
     * @throws IllegalArgumentException for an id of 0 or more than {@value #MAX_ID_BYTES} UTF-8 bytes, or a kind code
     *                                  outside 0-3, which no client may send
     * @throws NullPointerException     for a null event
     */
    public static byte[] encode(PointerEvent event) {
        byte[] id = event.displayId()
            .getBytes(StandardCharsets.UTF_8);
        if (id.length < 1 || id.length > MAX_ID_BYTES) {
            throw new IllegalArgumentException(
                "display id of " + id.length + " UTF-8 bytes is outside 1.." + MAX_ID_BYTES);
        }
        if (event.kind() == null) {
            throw new IllegalArgumentException("pointer kind " + event.kindCode() + " is outside 0..3");
        }
        ByteBuffer out = ByteBuffer.allocate(FIXED_BYTES + id.length);
        out.put((byte) FLAGS)
            .put((byte) TYPE)
            .put((byte) VERSION)
            .put((byte) event.kindCode())
            .putInt(event.x())
            .putInt(event.y())
            .putInt(event.z())
            .put((byte) id.length)
            .put(id)
            .putFloat(event.px())
            .putFloat(event.py())
            .put(event.data());
        return out.array();
    }

    /**
     * The event in {@code payload}'s readable bytes, or {@link Result#MALFORMED}; never throws, for any bytes, so no
     * packet makes FML kick its sender (FMLProxyPacket.java:102-111; S11 plan U5.2). It checks the readable bytes
     * first (more than {@value #MAX_BYTES}: malformed before any read), then each field's bounds before its read and
     * its value after it, then that nothing follows the data byte, so the length is exactly {@value #FIXED_BYTES} +
     * idLength. The id is decoded as UTF-8 with replacement, so a mangled id matches no display. It reads by index and
     * leaves the buffer's indices as they are.
     */
    public static Result decode(ByteBuf payload) {
        if (payload == null || payload.readableBytes() > MAX_BYTES) {
            return Result.MALFORMED;
        }
        Reader in = new Reader(payload);
        if (!in.has(HEAD)) {
            return Result.MALFORMED;
        }
        int flags = in.u8();
        int type = in.u8();
        int version = in.u8();
        PointerEvent.Kind kind = PointerEvent.Kind.of(in.u8());
        if (flags != FLAGS || type != TYPE || version != VERSION || kind == null) {
            return Result.MALFORMED;
        }
        int x = in.i32();
        int y = in.i32();
        int z = in.i32();
        int idLength = in.u8();
        if (idLength < 1 || idLength > MAX_ID_BYTES || !in.has(idLength + TAIL)) {
            return Result.MALFORMED;
        }
        String id = in.utf8(idLength);
        float px = in.f32();
        float py = in.f32();
        byte data = in.i8();
        if (in.remaining() != 0) {
            return Result.MALFORMED;
        }
        return new Result(new PointerEvent(kind, x, y, z, id, px, py, data));
    }

    /** What {@link #decode} found: an event, or {@link #MALFORMED}. */
    public static final class Result {

        /** Bytes that are no version-1 pointer packet. */
        public static final Result MALFORMED = new Result(null);

        private final PointerEvent event;

        private Result(PointerEvent event) {
            this.event = event;
        }

        /** Whether the bytes were no pointer packet. */
        public boolean isMalformed() {
            return event == null;
        }

        /** The decoded event, or null when malformed. */
        public PointerEvent event() {
            return event;
        }

        @Override
        public String toString() {
            return event == null ? "MALFORMED" : event.toString();
        }
    }

    /** Reads a buffer's readable bytes by index, from its reader index up to its writer index. */
    private static final class Reader {

        private final ByteBuf buf;
        private final int end;
        private int at;

        Reader(ByteBuf buf) {
            this.buf = buf;
            this.at = buf.readerIndex();
            this.end = buf.writerIndex();
        }

        boolean has(int bytes) {
            return end - at >= bytes;
        }

        int remaining() {
            return end - at;
        }

        int u8() {
            return buf.getUnsignedByte(at++);
        }

        byte i8() {
            return buf.getByte(at++);
        }

        int i32() {
            int value = buf.getInt(at);
            at += 4;
            return value;
        }

        float f32() {
            return Float.intBitsToFloat(i32());
        }

        String utf8(int length) {
            byte[] bytes = new byte[length];
            buf.getBytes(at, bytes);
            at += length;
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
