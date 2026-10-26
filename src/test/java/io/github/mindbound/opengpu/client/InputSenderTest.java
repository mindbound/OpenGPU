package io.github.mindbound.opengpu.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.net.PointerEvent;
import io.github.mindbound.opengpu.net.PointerEvent.Kind;
import io.github.mindbound.opengpu.net.PointerPacket;
import io.netty.buffer.Unpooled;

/**
 * Input's client sender (13:139; S12 plan P12.6 with amendment I11 (b)) through its test seam: the bytes go to a
 * list, and the thread test is the tests' own, so nothing here loads the channel or touches {@code Minecraft}
 * (S11 plan P15.1). Packets are decoded from {@code Unpooled.wrappedBuffer}, as the server's listener gets them.
 */
@Timeout(60)
class InputSenderTest {

    private static final String ID = "opengpu_screen-2";

    private final List<byte[]> out = new ArrayList<>();

    private static PointerEvent decode(byte[] bytes) {
        PointerPacket.Result result = PointerPacket.decode(Unpooled.wrappedBuffer(bytes));
        Assertions.assertFalse(result.isMalformed(), result::toString);
        return result.event();
    }

    // P12.6: each send is PointerPacket.encode of the event, which decodes back to it, for every kind
    @Test
    void theBytesArePointerPacketsEncodingOfTheEventAndDecodeBackToIt() {
        InputSender sender = new InputSender(12, 70, -300, out::add, () -> true);
        for (Kind kind : Kind.values()) {
            sender.send(kind, ID, 5.75f, 199.5f, (byte) -1);
        }
        Assertions.assertEquals(Kind.values().length, out.size());
        for (int i = 0; i < out.size(); i++) {
            PointerEvent expected = new PointerEvent(Kind.values()[i], 12, 70, -300, ID, 5.75f, 199.5f, (byte) -1);
            Assertions.assertArrayEquals(PointerPacket.encode(expected), out.get(i), expected::toString);
            Assertions.assertEquals(expected, decode(out.get(i)));
        }
        Assertions.assertEquals(0, sender.dropped());
    }

    // P12.6: the block is the GUI's, the one its sender was made for, and the id the one its gesture read
    @Test
    void theBlockIsTheGuisAndTheIdTheOneItReadAtTheSend() {
        InputSender sender = new InputSender(-7, 3, 2_000_000, out::add, () -> true);
        sender.send(Kind.TOUCH, ID, 0.5f, 0.25f, (byte) 1);
        sender.send(Kind.DRAG, "opengpu_screen-3", 1.5f, 0.25f, (byte) 1);
        PointerEvent touch = decode(out.get(0));
        PointerEvent drag = decode(out.get(1));
        Assertions.assertArrayEquals(
            new int[] { -7, 3, 2_000_000 },
            new int[] { touch.x(), touch.y(), touch.z() },
            touch::toString);
        Assertions.assertArrayEquals(
            new int[] { -7, 3, 2_000_000 },
            new int[] { drag.x(), drag.y(), drag.z() },
            drag::toString);
        Assertions.assertEquals(ID, touch.displayId());
        Assertions.assertEquals("opengpu_screen-3", drag.displayId());
        Assertions.assertEquals(0.5f, touch.px());
        Assertions.assertEquals(0.25f, touch.py());
        Assertions.assertEquals(1, touch.data());
    }

    // P12.6; amendment I11 (b): a call from another thread than the main one is dropped, and counted; the main
    // thread's calls go on
    @Test
    void aCallOffTheMainThreadIsDropped() throws InterruptedException {
        Thread main = Thread.currentThread();
        InputSender sender = new InputSender(1, 64, -2, out::add, () -> Thread.currentThread() == main);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                sender.send(Kind.TOUCH, ID, 1.5f, 2.5f, (byte) 0);
                sender.send(Kind.SCROLL, ID, 1.5f, 2.5f, (byte) 1);
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "not-the-client-main-thread");
        other.start();
        other.join();
        Assertions.assertNull(failure.get(), "a dropped call does not throw");
        Assertions.assertEquals(Collections.emptyList(), out);
        Assertions.assertEquals(2, sender.dropped());
        sender.send(Kind.TOUCH, ID, 1.5f, 2.5f, (byte) 0);
        Assertions.assertEquals(1, out.size());
        Assertions.assertEquals(2, sender.dropped());
    }

    // P12.5, P12.6: a null id is never encoded, and an id the codec refuses, which only a server's description could
    // give, is dropped rather than thrown in the GUI
    @Test
    void aNullIdIsNeverEncodedAndAnIdTheCodecRefusesIsDropped() {
        InputSender sender = new InputSender(1, 64, -2, out::add, () -> true);
        sender.send(Kind.TOUCH, null, 1.5f, 2.5f, (byte) 0);
        Assertions.assertEquals(0, sender.dropped());
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i <= PointerPacket.MAX_ID_BYTES; i++) {
            tooLong.append('a');
        }
        sender.send(Kind.TOUCH, "", 1.5f, 2.5f, (byte) 0);
        sender.send(Kind.TOUCH, tooLong.toString(), 1.5f, 2.5f, (byte) 0);
        Assertions.assertEquals(Collections.emptyList(), out);
        Assertions.assertEquals(2, sender.dropped());
        Assertions.assertThrows(NullPointerException.class, () -> sender.send(null, ID, 1.5f, 2.5f, (byte) 0));
    }
}
