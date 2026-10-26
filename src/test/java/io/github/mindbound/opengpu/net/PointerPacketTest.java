package io.github.mindbound.opengpu.net;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.mindbound.opengpu.net.PointerEvent.Kind;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/**
 * The pointer packet's codec (S11 plan U5.3, U5.9), the channel's only packet in M0: the round trip of each kind, the
 * golden bytes of version 1, the sizes 27 to 90, the encoder's refusals, and a decoder that answers malformed, and
 * never throws, for any bytes, so no packet makes FML kick its sender (FMLProxyPacket.java:102-111). Packets are
 * decoded from {@code Unpooled.wrappedBuffer}, as FML wraps a client's payload; nothing here loads the channel.
 */
@Timeout(60)
class PointerPacketTest {

    private static final String ID = "opengpu_screen-2";

    private static PointerEvent event(Kind kind, String id) {
        return new PointerEvent(kind, 12, 70, -300, id, 5.75f, 199.5f, (byte) -1);
    }

    private static PointerPacket.Result decode(byte[] bytes) {
        return PointerPacket.decode(Unpooled.wrappedBuffer(bytes));
    }

    private static String repeat(String s, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) {
            out.append(s);
        }
        return out.toString();
    }

    private static byte[] hex(String spaced) {
        String[] parts = spaced.trim()
            .split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format(Locale.ROOT, "%02x ", b & 0xFF));
        }
        return out.toString()
            .trim();
    }

    // U5.3: every field comes back, the kind by its code, and the buffer's indices stay as they were
    @ParameterizedTest
    @EnumSource(Kind.class)
    void eachKindRoundTrips(Kind kind) {
        PointerEvent sent = new PointerEvent(kind, -1, 64, -17, ID, 319.5f, 0.25f, (byte) (kind.code() - 2));
        byte[] bytes = PointerPacket.encode(sent);
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        PointerPacket.Result decoded = PointerPacket.decode(buf);
        Assertions.assertFalse(decoded.isMalformed(), hex(bytes));
        Assertions.assertEquals(sent, decoded.event());
        Assertions.assertEquals(
            kind,
            decoded.event()
                .kind());
        Assertions.assertEquals(0, buf.readerIndex());
        Assertions.assertEquals(bytes.length, buf.readableBytes());
        // From a reader index past other bytes too, as a payload may arrive
        ByteBuf shifted = Unpooled.wrappedBuffer(new byte[] { 9, 9 }, bytes);
        shifted.skipBytes(2);
        Assertions.assertEquals(
            sent,
            PointerPacket.decode(shifted)
                .event());
    }

    // U5.3's layout, big-endian, for touch(1, 64, -2, "ab", 1.5, 2.25, button 0)
    @Test
    void aTouchPacketHasTheGoldenBytes() {
        byte[] bytes = PointerPacket.encode(new PointerEvent(Kind.TOUCH, 1, 64, -2, "ab", 1.5f, 2.25f, (byte) 0));
        Assertions.assertArrayEquals(
            hex(
                "00 01 01 00" // flags, type POINTER, version 1, kind touch
                    + " 00 00 00 01  00 00 00 40  ff ff ff fe" // x, y, z
                    + " 02 61 62" // idLength, "ab"
                    + " 3f c0 00 00  40 10 00 00" // px 1.5f, py 2.25f
                    + " 00"), // data
            bytes,
            hex(bytes));
    }

    // U5.3: 26 + idLength bytes, from 27 to 90; a UUID address, OC's form, gives 62
    @Test
    void idsOfOneAnd64BytesGivePacketsOf27And90Bytes() {
        String uuid = UUID.nameUUIDFromBytes("display".getBytes(StandardCharsets.UTF_8))
            .toString();
        String twoByteChars = repeat("é", 32);
        Assertions.assertEquals(64, twoByteChars.getBytes(StandardCharsets.UTF_8).length);
        for (String id : Arrays.asList("a", repeat("x", 64), twoByteChars, uuid)) {
            byte[] bytes = PointerPacket.encode(event(Kind.DRAG, id));
            Assertions.assertEquals(26 + id.getBytes(StandardCharsets.UTF_8).length, bytes.length, id);
            Assertions.assertEquals(event(Kind.DRAG, id), decode(bytes).event(), id);
        }
        Assertions.assertEquals(27, PointerPacket.encode(event(Kind.DRAG, "a")).length);
        Assertions.assertEquals(90, PointerPacket.encode(event(Kind.DRAG, repeat("x", 64))).length);
        Assertions.assertEquals(62, PointerPacket.encode(event(Kind.DRAG, uuid)).length);
        Assertions.assertEquals(27, PointerPacket.MIN_BYTES);
        Assertions.assertEquals(90, PointerPacket.MAX_BYTES);
    }

    // U5.3: no client builds an oversized or unknown packet; px and py pass through, non-finite ones too
    @Test
    void theEncoderRefusesAnEmptyIdA65ByteIdAndKind4() {
        for (String id : Arrays.asList("", repeat("x", 65), repeat("é", 33))) {
            Assertions
                .assertThrows(IllegalArgumentException.class, () -> PointerPacket.encode(event(Kind.TOUCH, id)), id);
        }
        for (int kind : new int[] { 4, -1, 255 }) {
            Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> PointerPacket.encode(new PointerEvent(kind, 0, 0, 0, ID, 0, 0, (byte) 0)),
                "kind " + kind);
        }
        PointerEvent forged = new PointerEvent(Kind.TOUCH, 0, 0, 0, ID, Float.NaN, Float.NEGATIVE_INFINITY, (byte) 0);
        Assertions.assertEquals(forged, decode(PointerPacket.encode(forged)).event());
    }

    static Stream<Arguments> malformed() {
        byte[] valid = PointerPacket.encode(event(Kind.TOUCH, ID));
        byte[] longest = PointerPacket.encode(event(Kind.TOUCH, repeat("x", 64)));
        List<byte[]> prefixes = new ArrayList<>();
        for (int n = 0; n < valid.length; n++) {
            prefixes.add(Arrays.copyOf(valid, n));
        }
        return Stream.of(
            Arguments.of("empty", Collections.singletonList(new byte[0])),
            Arguments.of("every truncated prefix", prefixes),
            Arguments.of("one trailing byte", Collections.singletonList(Arrays.copyOf(valid, valid.length + 1))),
            Arguments.of("flags 1", Collections.singletonList(with(valid, 0, 1))),
            Arguments.of("type 2", Collections.singletonList(with(valid, 1, 2))),
            Arguments.of("version 2", Collections.singletonList(with(valid, 2, 2))),
            Arguments.of("kind 4", Collections.singletonList(with(valid, 3, 4))),
            Arguments.of("idLength 0", Collections.singletonList(with(valid, 16, 0))),
            Arguments.of("idLength 65", Collections.singletonList(with(valid, 16, 65))),
            Arguments.of("91 bytes", Collections.singletonList(Arrays.copyOf(longest, 91))));
    }

    private static byte[] with(byte[] packet, int index, int value) {
        byte[] out = packet.clone();
        out[index] = (byte) value;
        return out;
    }

    // U5.3; U5.4 check 1: each row is malformed, and decoding it throws nothing
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformed")
    void malformedPacketsAreMalformedAndThrowNothing(String row, List<byte[]> payloads) {
        for (byte[] payload : payloads) {
            PointerPacket.Result decoded = Assertions.assertDoesNotThrow(() -> decode(payload), row);
            Assertions.assertSame(PointerPacket.Result.MALFORMED, decoded, row + ": " + hex(payload));
            Assertions.assertNull(decoded.event());
        }
    }

    // U5.2: no bytes make the decoder throw. Half the payloads start with a valid head and a quarter also with a
    // consistent idLength, so the deeper reads run too
    @Test
    void aThousandSeededRandomPayloadsThrowNothing() {
        Random random = new Random(0x5110_0005L);
        int events = 0;
        int malformed = 0;
        for (int i = 0; i < 1000; i++) {
            byte[] payload = new byte[random.nextInt(129)];
            random.nextBytes(payload);
            if (i % 2 == 1 && payload.length >= 4) {
                payload[0] = 0;
                payload[1] = 1;
                payload[2] = 1;
                payload[3] = (byte) random.nextInt(4);
            }
            if (i % 4 == 3 && payload.length >= 27 && payload.length <= 90) {
                payload[16] = (byte) (payload.length - 26);
            }
            PointerPacket.Result decoded = Assertions.assertDoesNotThrow(() -> decode(payload), hex(payload));
            if (decoded.isMalformed()) {
                malformed++;
            } else {
                events++;
            }
        }
        Assertions.assertTrue(events > 0, "some payloads decode: " + events);
        Assertions.assertTrue(malformed > 0, "some payloads are malformed: " + malformed);
    }
}
