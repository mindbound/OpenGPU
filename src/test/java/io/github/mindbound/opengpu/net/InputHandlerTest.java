package io.github.mindbound.opengpu.net;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.minecraft.tileentity.TileEntity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.mindbound.opengpu.card.CardEnvironment;
import io.github.mindbound.opengpu.card.Cards;
import io.github.mindbound.opengpu.display.DisplayTile;
import io.github.mindbound.opengpu.display.Displays;
import io.github.mindbound.opengpu.fixtures.FakeNodes;
import io.github.mindbound.opengpu.fixtures.OcCalls;
import io.github.mindbound.opengpu.fixtures.RecordingContext;
import io.github.mindbound.opengpu.net.InputHandler.Outcome;
import io.github.mindbound.opengpu.net.PointerEvent.Kind;
import io.github.mindbound.opengpu.render.Installation;
import io.github.mindbound.opengpu.server.ServerSession;
import io.github.mindbound.opengpu.server.TestSessions;
import io.netty.buffer.Unpooled;

/**
 * Input's server half (24:140, :222; 15:181-187; 09:65, :87; S11 plan U5.4, U5.5, U5.9): the handler's checks in
 * their order, each failure's outcome and what it asked of the world, and the accepted packet's
 * {@code computer.checked_signal} with every argument and its type. A real display (B4) is bound through OC's dispatch
 * of {@code bind} to a card on a FakeRenderHost, at T2 or T3; FakeNodes records what its node sends, and a
 * FakeInputSource stands in for the sender and its world. No test loads the channel, the listener or the game's
 * source (U5.9).
 */
@Timeout(60)
class InputHandlerTest {

    /** The display's block, whose centre is (-0.5, 64.5, -16.5) and whose chunk is (-1, -2). */
    private static final int X = -1;
    private static final int Y = 64;
    private static final int Z = -17;

    /** A display at (X, Y, Z) in a card's network, bound or not, a sender beside it and a handler. */
    private static final class Rig {

        final Cards cards = new Cards();
        final CardEnvironment card;
        final DisplayTile display;
        final FakeInputSource player;
        final InputHandler handler;
        int supplied;

        Rig(int tier) throws Exception {
            this(tier, true, true);
        }

        Rig(int tier, boolean bound, boolean inputUsername) throws Exception {
            card = cards.joined(tier);
            display = Displays.on(cards.nodes());
            display.xCoord = X;
            display.yCoord = Y;
            display.zCoord = Z;
            cards.nodes()
                .join(display.node());
            if (bound) {
                Cards.bind(card, display);
            }
            player = FakeInputSource.beside(display);
            handler = new InputHandler(inputUsername, () -> true);
        }

        PointerEvent event(Kind kind, float px, float py, int data) {
            return new PointerEvent(kind, X, Y, Z, display.displayId(), px, py, (byte) data);
        }

        void send(PointerEvent event) {
            send(PointerPacket.encode(event));
        }

        void send(byte[] packet) {
            handler.receive(Unpooled.wrappedBuffer(packet), source());
        }

        void touch(float px, float py) {
            send(event(Kind.TOUCH, px, py, 0));
        }

        /** The sender, counting how often the handler asks for it. */
        Supplier<InputSource> source() {
            return () -> {
                supplied++;
                return player;
            };
        }

        List<FakeNodes.Sent> signals() {
            return cards.nodes()
                .sent();
        }

        /** The only signal's values. */
        List<Object> signal() {
            List<FakeNodes.Sent> sent = signals();
            Assertions.assertEquals(1, sent.size(), sent.toString());
            return sent.get(0).data;
        }

        /** Precise mode through OC's dispatch of the display's setPrecise, as Lua sets it. */
        void precise() throws Exception {
            OcCalls.call(display, "setPrecise", new RecordingContext(), Boolean.TRUE);
        }
    }

    private static Map<Outcome, Long> only(Outcome outcome) {
        return Collections.singletonMap(outcome, 1L);
    }

    private static List<Class<?>> types(List<Object> values) {
        return values.stream()
            .map(Object::getClass)
            .collect(Collectors.toList());
    }

    // U5.5: the display's node sends computer.checked_signal(player, "touch", x, y, button, name), the coordinates
    // floored to 0-based pixels and every number a Long; the checks asked the world in U5.4's order
    @Test
    void anAcceptedTouchAtT2SignalsEveryArgumentWithItsType() throws Exception {
        Rig rig = new Rig(2);
        rig.send(rig.event(Kind.TOUCH, 5.75f, 3.25f, 0));
        Assertions.assertEquals(only(Outcome.ACCEPTED), rig.handler.counts());
        FakeNodes.Sent sent = rig.signals()
            .get(0);
        Assertions.assertSame(rig.display.node(), sent.source);
        Assertions.assertEquals("computer.checked_signal", sent.name);
        Assertions.assertEquals(InputHandler.MESSAGE, sent.name);
        Assertions.assertEquals(Arrays.asList(rig.player.entity(), "touch", 5L, 3L, 0L, "Alex"), sent.data);
        Assertions.assertSame(rig.player.entity(), sent.data.get(0));
        Assertions.assertEquals(
            Arrays.asList(String.class, Long.class, Long.class, Long.class, String.class),
            types(sent.data.subList(1, 6)));
        Assertions.assertEquals(
            Arrays.asList(
                "distanceSq -0.5 64.5 -16.5",
                "blockLoaded -1 64 -17",
                "watchesChunk -1 -2",
                "tileEntity -1 64 -17"),
            rig.player.queries());
        Assertions.assertEquals(1, rig.supplied);
    }

    // 24:140: OC's names, with the data byte after the coordinates for every kind
    @ParameterizedTest
    @EnumSource(Kind.class)
    void theFourKindsGiveTheirFourNames(Kind kind) throws Exception {
        Rig rig = new Rig(3);
        rig.send(rig.event(kind, 10, 20, 1));
        Assertions.assertEquals(Arrays.asList(rig.player.entity(), kind.signal(), 10L, 20L, 1L, "Alex"), rig.signal());
        Assertions.assertEquals(
            Arrays.asList("touch", "drag", "drop", "scroll"),
            Arrays.stream(Kind.values())
                .map(Kind::signal)
                .collect(Collectors.toList()));
    }

    // 24:140; 09:65: scroll's delta is the wheel's sign, a Long
    @ParameterizedTest
    @ValueSource(ints = { -1, 1 })
    void scrollsDeltaArrivesAsALong(int delta) throws Exception {
        Rig rig = new Rig(2);
        rig.send(rig.event(Kind.SCROLL, 1, 1, delta));
        Object value = rig.signal()
            .get(4);
        Assertions.assertEquals(Long.class, value.getClass());
        Assertions.assertEquals((long) delta, value);
    }

    // U5.3: the data byte is signed for every kind, as OC reads it (PacketHandler.scala:234), so 0xFF is -1
    @Test
    void theDataByte0xFFArrivesAsMinusOne() throws Exception {
        Rig rig = new Rig(2);
        rig.send(rig.event(Kind.TOUCH, 1, 1, 0xFF));
        Assertions.assertEquals(
            -1L,
            rig.signal()
                .get(4));
    }

    // 15:184: integer mode floors the fraction
    @Test
    void integerModeFloors() throws Exception {
        Rig rig = new Rig(2);
        rig.touch(5.75f, 199.99f);
        Assertions.assertEquals(
            Arrays.asList(5L, 199L),
            rig.signal()
                .subList(2, 4));
    }

    // 15:181; 09:65: precise mode passes the float widened to a Double
    @Test
    void preciseModePassesTheFraction() throws Exception {
        Rig rig = new Rig(2);
        rig.precise();
        rig.touch(5.75f, 0.5f);
        List<Object> values = rig.signal();
        Assertions.assertEquals(Arrays.asList(5.75, 0.5), values.subList(2, 4));
        Assertions.assertEquals(Arrays.asList(Double.class, Double.class), types(values.subList(2, 4)));
        Assertions.assertEquals(
            Long.class,
            values.get(4)
                .getClass(),
            "the button stays a Long");
    }

    // U5.5: -0.0 passes the range check (-0.0f >= 0), and precise mode hands Lua +0.0, not -0.0
    @Test
    void preciseModeTurnsMinusZeroIntoPlusZero() throws Exception {
        Rig rig = new Rig(2);
        rig.precise();
        rig.touch(-0.0f, -0.0f);
        List<Object> values = rig.signal();
        Assertions.assertEquals(0L, Double.doubleToRawLongBits((Double) values.get(2)));
        Assertions.assertEquals(0L, Double.doubleToRawLongBits((Double) values.get(3)));
    }

    // U5.5: the name follows only when OC's misc.inputUsername is set
    @Test
    void withoutInputUsernameTheSignalHasNoName() throws Exception {
        Rig rig = new Rig(2, true, false);
        rig.touch(1, 2);
        Assertions.assertEquals(Arrays.asList(rig.player.entity(), "touch", 1L, 2L, 0L), rig.signal());
        Assertions.assertFalse(rig.handler.inputUsername());
    }

    // U5.4 check 8: 0 <= px < W and 0 <= py < H for the bound card's surface; nothing clamped (15:185)
    @ParameterizedTest(name = "T{0} ({1}, {2}) accepted {3}")
    @CsvSource({ "2, 0, 0, true", "2, 319.5, 199.5, true", "2, 320, 0, false", "2, 0, 200, false", "2, -0.5, 0, false",
        "3, 639.5, 399.5, true", "3, 640, 0, false" })
    void theRangeIsTheBoundCardsSurface(int tier, float px, float py, boolean accepted) throws Exception {
        Rig rig = new Rig(tier);
        rig.touch(px, py);
        Assertions.assertEquals(only(accepted ? Outcome.ACCEPTED : Outcome.OUT_OF_RANGE), rig.handler.counts());
        Assertions.assertEquals(
            accepted ? 1 : 0,
            rig.signals()
                .size());
    }

    static Stream<Arguments> nonFinite() {
        return Stream.of(
            Arguments.of(Float.NaN, 1f),
            Arguments.of(Float.POSITIVE_INFINITY, 1f),
            Arguments.of(Float.NEGATIVE_INFINITY, 1f),
            Arguments.of(1f, Float.NaN),
            Arguments.of(1f, Float.POSITIVE_INFINITY),
            Arguments.of(1f, Float.NEGATIVE_INFINITY));
    }

    // U5.4 check 2: NaN and the infinities are dropped before the sender is asked for (24:222)
    @ParameterizedTest(name = "({0}, {1})")
    @MethodSource("nonFinite")
    void nonFiniteCoordinatesAreDroppedBeforeTheSenderIsAskedFor(float px, float py) throws Exception {
        Rig rig = new Rig(2);
        rig.touch(px, py);
        Assertions.assertEquals(only(Outcome.NOT_FINITE), rig.handler.counts());
        Assertions.assertEquals(0, rig.supplied);
        Assertions.assertEquals(Collections.emptyList(), rig.signals());
    }

    // U5.4 check 3: a squared distance of 64 to the block's centre is within reach
    @Test
    void aSquaredDistanceOf64IsInReach() throws Exception {
        Rig rig = new Rig(2);
        rig.player.at(X + 0.5 + 8, Y + 0.5, Z + 0.5);
        rig.touch(1, 1);
        Assertions.assertEquals(only(Outcome.ACCEPTED), rig.handler.counts());
    }

    // U5.4 check 3: beyond it the packet is dropped before any chunk or tile-entity query
    @Test
    void beyond64ThePacketIsDroppedBeforeAnyOtherQuery() throws Exception {
        Rig rig = new Rig(2);
        rig.player.at(X + 0.5 + 8, Y + 0.51, Z + 0.5);
        rig.touch(1, 1);
        Assertions.assertEquals(only(Outcome.OUT_OF_REACH), rig.handler.counts());
        Assertions.assertEquals(Collections.singletonList("distanceSq -0.5 64.5 -16.5"), rig.player.queries());
        Assertions.assertFalse(rig.player.asked("blockLoaded"));
        Assertions.assertFalse(rig.player.asked("watchesChunk"));
        Assertions.assertFalse(rig.player.asked("tileEntity"));
        Assertions.assertEquals(Collections.emptyList(), rig.signals());
    }

    // U5.4: the reach is measured to the block's centre, as OC's (Environment.scala:22)
    @Test
    void theReachQueryIsTheBlocksCentre() throws Exception {
        Rig rig = new Rig(2);
        rig.touch(1, 1);
        Assertions.assertEquals(
            "distanceSq -0.5 64.5 -16.5",
            rig.player.queries()
                .get(0));
    }

    // U5.4: the chunk is the block's shifted by 4, floor towards minus infinity (PacketHandler.scala:30-31)
    @Test
    void theChunkQueryShifts() throws Exception {
        Rig rig = new Rig(2);
        rig.touch(1, 1);
        Assertions.assertTrue(
            rig.player.queries()
                .contains("watchesChunk -1 -2"),
            rig.player.queries()
                .toString());
    }

    // U5.4 check 4: an unloaded chunk, or a y outside 0-255, is dropped before the watching and tile-entity queries
    @Test
    void anUnloadedBlockIsDroppedBeforeTheWatchingAndTileEntityQueries() throws Exception {
        Rig rig = new Rig(2);
        rig.player.unloaded(X, Y, Z);
        rig.touch(1, 1);
        Assertions.assertEquals(only(Outcome.NOT_LOADED), rig.handler.counts());
        Assertions
            .assertEquals(Arrays.asList("distanceSq -0.5 64.5 -16.5", "blockLoaded -1 64 -17"), rig.player.queries());
        // y 256, with the player beside it: no world query after the reach
        Rig high = new Rig(2);
        high.player.at(X + 0.5, 256.5, Z + 1.5);
        high.send(new PointerEvent(Kind.TOUCH, X, 256, Z, high.display.displayId(), 1, 1, (byte) 0));
        Assertions.assertEquals(only(Outcome.NOT_LOADED), high.handler.counts());
        Assertions.assertEquals(Collections.singletonList("distanceSq -0.5 256.5 -16.5"), high.player.queries());
        Assertions.assertEquals(Collections.emptyList(), rig.signals());
        Assertions.assertEquals(Collections.emptyList(), high.signals());
    }

    // U5.4 check 5: a chunk the sender does not watch is dropped before the tile-entity query (09:87)
    @Test
    void anUnwatchedChunkIsDroppedBeforeTheTileEntityQuery() throws Exception {
        Rig rig = new Rig(2);
        rig.player.notWatching(-1, -2);
        rig.touch(1, 1);
        Assertions.assertEquals(only(Outcome.NOT_WATCHING), rig.handler.counts());
        Assertions.assertFalse(rig.player.asked("tileEntity"));
        Assertions.assertEquals(Collections.emptyList(), rig.signals());
    }

    // U5.4 check 6: no tile entity there
    @Test
    void noTileEntityIsNoDisplay() throws Exception {
        Rig rig = new Rig(2);
        rig.player.tile(X, Y, Z, null);
        rig.touch(1, 1);
        Assertions.assertEquals(only(Outcome.NO_DISPLAY), rig.handler.counts());
        Assertions.assertTrue(rig.player.asked("tileEntity"));
    }

    // U5.4 check 6: another tile entity there
    @Test
    void anotherTileEntityIsNoDisplay() throws Exception {
        Rig rig = new Rig(2);
        TileEntity other = new TileEntity();
        rig.player.tile(X, Y, Z, other);
        rig.touch(1, 1);
        Assertions.assertEquals(only(Outcome.NO_DISPLAY), rig.handler.counts());
        Assertions.assertEquals(Collections.emptyList(), rig.signals());
    }

    // U5.4 check 6: an invalid display, still bound and in its network, as a removed tile entity is marked
    @Test
    void anInvalidDisplayIsNoDisplay() throws Exception {
        Rig rig = new Rig(2);
        Field invalid = TileEntity.class.getDeclaredField("tileEntityInvalid");
        invalid.setAccessible(true);
        invalid.setBoolean(rig.display, true);
        Assertions.assertTrue(rig.display.isInvalid());
        Assertions.assertNotNull(rig.display.boundSurface(), "bound, so only the validity decides");
        rig.touch(1, 1);
        Assertions.assertEquals(only(Outcome.NO_DISPLAY), rig.handler.counts());
        Assertions.assertEquals(Collections.emptyList(), rig.signals());
    }

    // U5.4 check 6: a stale GUI's id, of a replaced or re-addressed display (P12.5; B2)
    @Test
    void anotherIdIsNoDisplay() throws Exception {
        Rig rig = new Rig(2);
        rig.send(new PointerEvent(Kind.TOUCH, X, Y, Z, "opengpu_screen-gone", 1, 1, (byte) 0));
        Assertions.assertEquals(only(Outcome.NO_DISPLAY), rig.handler.counts());
        Assertions.assertEquals(Collections.emptyList(), rig.signals());
    }

    // U5.5: a display no card was bound to has no pixel space of its own
    @Test
    void anUnboundDisplayDropsTheInput() throws Exception {
        Rig rig = new Rig(3, false, true);
        Assertions.assertNull(rig.display.boundSurface());
        rig.touch(1, 1);
        Assertions.assertEquals(only(Outcome.UNBOUND), rig.handler.counts());
        Assertions.assertEquals(Collections.emptyList(), rig.signals());
    }

    // U5.5; P7.4; B2: the card's release detaches the display, so later input is unbound too
    @Test
    void afterTheCardsReleaseTheDisplayIsUnbound() throws Exception {
        Rig rig = new Rig(3);
        rig.touch(1, 1);
        rig.cards.release(rig.card);
        rig.touch(1, 1);
        Map<Outcome, Long> expected = new EnumMap<>(Outcome.class);
        expected.put(Outcome.UNBOUND, 1L);
        expected.put(Outcome.ACCEPTED, 1L);
        Assertions.assertEquals(expected, rig.handler.counts());
        Assertions.assertEquals(
            1,
            rig.signals()
                .size());
    }

    // U5.4 check 7's second half: a bound display whose node is in no network could signal no machine
    // (Node.scala:102-103), so its input is unbound too, and the player is not marked active
    @Test
    void aBoundDisplayOutsideTheNetworkIsUnbound() throws Exception {
        Rig rig = new Rig(2);
        rig.cards.nodes()
            .leave(rig.display.node());
        Assertions.assertSame(rig.card, rig.display.boundCard(), "nothing told the card");
        rig.touch(1, 1);
        Assertions.assertEquals(only(Outcome.UNBOUND), rig.handler.counts());
        Assertions.assertEquals(Collections.emptyList(), rig.signals());
        Assertions.assertEquals(0, rig.player.markedActive());
    }

    // U5.2 check 0: a session's handler takes packets on the session's thread only, and off it asks for no sender
    @Test
    void offTheServerThreadThePacketIsDroppedAndNoSenderIsAskedFor() throws Exception {
        Rig rig = new Rig(2);
        ExecutorService server = Executors.newSingleThreadExecutor();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ServerSession session = server
            .submit(() -> TestSessions.start(Installation.of(rig.cards.host(), "test host", null), pool))
            .get(10, TimeUnit.SECONDS);
        try {
            InputHandler input = session.input();
            byte[] packet = PointerPacket.encode(rig.event(Kind.TOUCH, 1, 1, 0));
            input.receive(Unpooled.wrappedBuffer(packet), rig.source());
            Assertions.assertEquals(only(Outcome.OFF_THREAD), input.counts());
            Assertions.assertEquals(0, rig.supplied);
            Assertions.assertEquals(Collections.emptyList(), rig.player.queries());
            server.submit(() -> input.receive(Unpooled.wrappedBuffer(packet), rig.source()))
                .get(10, TimeUnit.SECONDS);
            Assertions.assertEquals(1, input.count(Outcome.ACCEPTED), "on the session's thread");
            Assertions.assertTrue(input.inputUsername(), "OC's default without its config");
        } finally {
            TestSessions.stop(session);
            server.shutdownNow();
        }
    }

    // U5.4: the first failing check decides, and checks 0-2 never ask for the sender
    @Test
    void theFirstFailingCheckDecides() throws Exception {
        Rig rig = new Rig(2);
        byte[] packet = PointerPacket.encode(rig.event(Kind.TOUCH, 1, 1, 0));
        packet[0] = 1;
        rig.send(packet);
        Assertions.assertEquals(only(Outcome.MALFORMED), rig.handler.counts());
        // NaN, far away, in an unloaded chunk: NOT_FINITE, the first of its failures
        rig.send(new PointerEvent(Kind.TOUCH, 100000, 300, 100000, "nowhere", Float.NaN, 1, (byte) 0));
        Assertions.assertEquals(1, rig.handler.count(Outcome.NOT_FINITE));
        Assertions.assertEquals(
            2,
            rig.handler.counts()
                .size());
        Assertions.assertEquals(0, rig.supplied);
        Assertions.assertEquals(Collections.emptyList(), rig.player.queries());
    }

    // U5.8: what receive lets out passes to the listener, which hands it to failed, counted as FAILED
    @Test
    void failedCountsFailed() throws Exception {
        Rig rig = new Rig(2);
        byte[] packet = PointerPacket.encode(rig.event(Kind.TOUCH, 1, 1, 0));
        IllegalStateException thrown = Assertions.assertThrows(
            IllegalStateException.class,
            () -> rig.handler
                .receive(Unpooled.wrappedBuffer(packet), () -> { throw new IllegalStateException("no player"); }));
        Assertions.assertEquals(Collections.emptyMap(), rig.handler.counts(), "receive counts nothing it lets out");
        rig.handler.failed(thrown);
        Assertions.assertEquals(only(Outcome.FAILED), rig.handler.counts());
        Assertions.assertEquals(0, rig.handler.count(Outcome.NO_SESSION), "the listener's to count");
    }

    // U5.5: the player is marked active for accepted input only (PacketHandler.scala:36-44)
    @Test
    void thePlayerIsMarkedActiveOnlyWhenTheInputIsAccepted() throws Exception {
        Rig rig = new Rig(2);
        rig.touch(320, 0);
        rig.send(new byte[] { 0, 1, 1 });
        rig.send(new PointerEvent(Kind.TOUCH, X, Y, Z, "opengpu_screen-gone", 1, 1, (byte) 0));
        Assertions.assertEquals(0, rig.player.markedActive());
        rig.touch(1, 1);
        Assertions.assertEquals(1, rig.player.markedActive());
        Assertions.assertEquals(1, rig.handler.count(Outcome.ACCEPTED));
    }
}
