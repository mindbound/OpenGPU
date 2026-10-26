package io.github.mindbound.opengpu.card;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.device.CallBudget;
import io.github.mindbound.opengpu.core.fixtures.device.OracleRecordRenderer;
import io.github.mindbound.opengpu.core.render.FakeRenderHost;
import io.github.mindbound.opengpu.fixtures.FakeNodes;
import io.github.mindbound.opengpu.fixtures.OcCalls;
import io.github.mindbound.opengpu.fixtures.RecordingContext;
import io.github.mindbound.opengpu.render.Installation;
import io.github.mindbound.opengpu.server.ServerSession;
import io.github.mindbound.opengpu.server.TestSessions;
import li.cil.oc.api.network.Node;

/**
 * {@code opengpu_frame(card, frameId, status)} (24:127; 28:110-115; S11 plan P11, P15.3; amendments G2, L1; D16): the
 * statuses' path from the device to OC's {@code Context.signal}. Whichever thread a device delivers a status on, the
 * card only hands it to the session's tick queue, and the queue's drain signals it on the server thread at the start
 * of a server tick (Q-S11-17), which these tests stand in for by draining the rig's queue, or a test session's, by
 * hand. The signal carries the cached address, a Long frame id and a String status, and each status comes to one of
 * four outcomes, sent, refused, unsent and stale, counted once and never retried. "dropped" runs on the rig's manual
 * clock (amendments B1).
 */
@Timeout(60)
class CardSignalTest {

    private static final long WAIT_SECONDS = 10;

    private static final CallBudget FREE = new CallBudget() {

        @Override
        public void consume(double cost) {}

        @Override
        public void pause(double seconds) {}
    };

    /** A card joined to the network beside a neighbouring machine. */
    private static CardEnvironment joinedBesideAMachine(CardRig cards, Node machine) {
        CardEnvironment card = cards.joined(3);
        cards.nodes.join(machine);
        cards.nodes.neighbours(card.node(), machine);
        return card;
    }

    private static List<String> texts(RecordingContext context) {
        return context.signals()
            .stream()
            .map(CardEnvironmentTest::signalText)
            .collect(Collectors.toList());
    }

    private static String frame(CardEnvironment card, long frameId, String status) {
        return "[opengpu_frame, " + card.node()
            .address() + ", " + frameId + ", " + status + "]";
    }

    /** Every status the card has counted, of the four outcomes. */
    private static long counted(CardEnvironment card) {
        return card.signalsSent() + card.signalsRefused() + card.signalsUnsent() + card.signalsStale();
    }

    /** A single thread with a name, standing in for a fold's or a server's. */
    private static ExecutorService thread(String name) {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Waits until the single-thread executor has run every task it holds now, such as a fold. */
    private static void settle(ExecutorService executor) throws Exception {
        executor.submit(() -> {})
            .get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    // 19:202; Machine.scala:858-884: an Integer would read back as nil after a save, so String, Long, String
    @Test
    void doneAndDroppedReachTheMachineAsStringLongString() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(2);
        RecordingContext context = new RecordingContext();
        OcCalls.call(card, "present", context);
        cards.host.step();
        OcCalls.call(card, "present", context);
        cards.clock.advanceMillis(1000);
        card.update();
        Assertions.assertEquals(Collections.emptyList(), context.signals(), "before the drain");
        Assertions.assertEquals(2, cards.drain());
        Assertions.assertEquals(Arrays.asList(frame(card, 1, "done"), frame(card, 2, "dropped")), texts(context));
        for (RecordingContext.Signal signal : context.signals()) {
            Assertions.assertEquals(
                Arrays.asList(String.class, Long.class, String.class),
                Arrays.stream(signal.args)
                    .map(Object::getClass)
                    .collect(Collectors.toList()),
                signal::toString);
        }
        Assertions.assertEquals(2, card.signalsSent());
    }

    // 09:65; 21:91: "done" comes from the pool's fold, which only hands it over; D16: it is signalled at the drain, on
    // the drain's thread, the server's
    @Test
    void doneIsHandedOverOnTheFoldsThreadAndSignalledOnTheDrainsThread() throws Exception {
        ExecutorService folds = thread("CardSignalTest fold");
        try {
            CardRig cards = new CardRig(new FakeRenderHost(new OracleRecordRenderer()), folds);
            CardEnvironment card = cards.joined(3);
            RecordingContext context = new RecordingContext();
            OcCalls.call(card, "present", context);
            cards.host.step();
            settle(folds);
            Assertions.assertEquals(1, cards.ticks.queued(), "handed over by the fold");
            Assertions.assertEquals(Collections.emptyList(), context.signals(), "the fold signals nothing");
            Assertions.assertEquals(1, cards.drain());
            RecordingContext.Signal done = context.signals()
                .get(0);
            Assertions.assertEquals(
                Thread.currentThread()
                    .getName(),
                done.thread);
            Assertions.assertEquals(frame(card, 1, "done"), CardEnvironmentTest.signalText(done));
        } finally {
            folds.shutdownNow();
        }
    }

    // 24:632; 28:1124: a full queue refuses the signal, which is counted and lost
    @Test
    void aRefusedSignalIsCountedOnceAndNotRetried() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(2);
        RecordingContext full = new RecordingContext().capQueue(0);
        OcCalls.call(card, "present", full);
        cards.host.step();
        for (int tick = 0; tick < 3; tick++) {
            cards.clock.advanceMillis(1000);
            card.update();
            cards.drain();
        }
        Assertions.assertEquals(
            1,
            full.signals()
                .size(),
            full.signals()::toString);
        Assertions.assertFalse(
            full.signals()
                .get(0).accepted);
        Assertions.assertEquals(1, card.signalsRefused());
        Assertions.assertEquals(0, card.signalsSent());
    }

    // No callback has given the card a context: a frame presented on the device alone is unsent, counted at the drain
    @Test
    void withoutAContextTheStatusIsUnsent() {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(2);
        card.device()
            .present(FREE);
        cards.host.step();
        Assertions.assertEquals(0, counted(card), "before the drain");
        Assertions.assertEquals(1, cards.drain());
        Assertions.assertNull(card.context());
        Assertions.assertEquals(1, card.signalsUnsent());
        Assertions.assertEquals(0, card.signalsSent());
    }

    // P11.3; D16: statuses handed over before a reset are stale at the drain after it, also once a new run's callback
    // has given the card its context, while the new run's own frame passes; the frame counter survives the reset
    // (Device.java:395-399), so the floor tells them apart
    @Test
    void aStatusQueuedBeforeAResetIsStaleAfterIt() throws Exception {
        CardRig cards = new CardRig();
        Node machine = cards.nodes.machine();
        CardEnvironment card = joinedBesideAMachine(cards, machine);
        RecordingContext oldRun = new RecordingContext();
        RecordingContext newRun = new RecordingContext();
        OcCalls.call(card, "present", oldRun);
        OcCalls.call(card, "present", oldRun);
        cards.clock.advanceMillis(1000);
        card.update();
        card.onMessage(FakeNodes.message("computer.started", machine));
        OcCalls.call(card, "getCaps", newRun);
        Assertions.assertEquals(Collections.singletonList(3L), Arrays.asList(OcCalls.call(card, "present", newRun)));
        cards.clock.advanceMillis(1000);
        card.update();
        Assertions.assertEquals(3, cards.drain());
        Assertions.assertEquals(Collections.emptyList(), texts(oldRun));
        Assertions.assertEquals(
            Collections.singletonList(frame(card, 3, "dropped")),
            texts(newRun),
            "the new run hears of its own frame only");
        Assertions.assertEquals(2, card.signalsStale());
        Assertions.assertEquals(1, card.signalsSent());
    }

    // 28:115: frames a reset discards get no status at all
    @Test
    void framesAResetDiscardsGetNoStatus() throws Exception {
        CardRig cards = new CardRig();
        Node machine = cards.nodes.machine();
        CardEnvironment card = joinedBesideAMachine(cards, machine);
        RecordingContext context = new RecordingContext();
        OcCalls.call(card, "present", context);
        card.onMessage(FakeNodes.message("computer.stopped", machine));
        OcCalls.call(card, "getCaps", context);
        cards.host.step();
        cards.clock.advanceMillis(2000);
        card.update();
        Assertions.assertEquals(0, cards.drain());
        Assertions.assertEquals(Collections.emptyList(), context.signals());
        Assertions.assertEquals(0, counted(card));
    }

    // FrameListener.java: a listener never throws, nor does a tick queue's entry, since the drain runs in FML's tick
    @Test
    void aSignalThatThrowsIsCountedAsRefusedAndGoesNoFurther() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(2);
        RecordingContext broken = new RecordingContext()
            .onSignal(signal -> { throw new IllegalStateException("the queue broke"); });
        OcCalls.call(card, "present", broken);
        cards.host.step();
        cards.clock.advanceMillis(1000);
        card.update();
        Assertions.assertEquals(1, cards.drain());
        Assertions.assertEquals(
            1,
            broken.signals()
                .size());
        Assertions.assertEquals(1, card.signalsRefused());
        Assertions.assertEquals(0, cards.ticks.failed(), "the card counted it, so the queue saw no failure");
    }

    // D16: a status from a fold or from the tick is only handed over; nothing reaches the machine, and nothing is
    // counted, until the drain at the start of the next server tick, which signals each status once
    @Test
    void nothingIsSignalledOrCountedBeforeTheDrain() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(2);
        RecordingContext context = new RecordingContext();
        OcCalls.call(card, "present", context);
        cards.host.step();
        OcCalls.call(card, "present", context);
        cards.clock.advanceMillis(1000);
        card.update();
        Assertions.assertEquals(Collections.emptyList(), context.signals());
        Assertions.assertEquals(0, counted(card));
        Assertions.assertEquals(2, cards.ticks.queued());
        Assertions.assertEquals(2, cards.drain());
        Assertions.assertEquals(Arrays.asList(frame(card, 1, "done"), frame(card, 2, "dropped")), texts(context));
        Assertions.assertEquals(2, card.signalsSent());
        Assertions.assertEquals(0, cards.ticks.queued());
        Assertions.assertEquals(0, cards.drain(), "each status is signalled once");
        Assertions.assertEquals(2, counted(card));
    }

    // The threads review's T2, made deterministic by D16: a fold's "done" handed over just before its machine reboots
    // (close, computer.stopped, start, computer.started: Machine.scala:575-582, :208-240) is stale at the drain after
    // the reboot, so the new run never hears of a frame it did not present, and the old run hears nothing either
    @Test
    void aFoldsDoneHandedOverBeforeARebootIsStaleAtTheDrain() throws Exception {
        CardRig cards = new CardRig();
        Node machine = cards.nodes.machine();
        CardEnvironment card = joinedBesideAMachine(cards, machine);
        RecordingContext oldRun = new RecordingContext();
        RecordingContext newRun = new RecordingContext();
        OcCalls.call(card, "present", oldRun);
        cards.host.step();
        Assertions.assertEquals(1, cards.ticks.queued(), "the fold's done, handed over");
        card.onMessage(FakeNodes.message("computer.stopped", machine));
        card.onMessage(FakeNodes.message("computer.started", machine));
        OcCalls.call(card, "getCaps", newRun);
        Assertions.assertEquals(1, cards.drain());
        Assertions.assertEquals(Collections.emptyList(), oldRun.signals());
        Assertions.assertEquals(Collections.emptyList(), newRun.signals());
        Assertions.assertEquals(1, card.signalsStale());
        Assertions.assertEquals(1, counted(card));
    }

    // D16; FrameListener.java:3-9: the tick queue keeps the device's order across the threads it is delivered on: a
    // fold's "done", the server thread's "dropped", then a fold's "done" again
    @Test
    void theDevicesOrderIsKeptAcrossThreads() throws Exception {
        ExecutorService folds = thread("CardSignalTest fold");
        try {
            CardRig cards = new CardRig(new FakeRenderHost(new OracleRecordRenderer()), folds);
            CardEnvironment card = cards.joined(3);
            RecordingContext context = new RecordingContext();
            OcCalls.call(card, "present", context);
            cards.host.step();
            settle(folds);
            OcCalls.call(card, "present", context);
            cards.clock.advanceMillis(1000);
            card.update();
            cards.host.step();
            settle(folds);
            Assertions.assertEquals(Collections.emptyList(), context.signals(), "before the drain");
            Assertions.assertEquals(3, cards.drain());
            Assertions.assertEquals(
                Arrays.asList(frame(card, 1, "done"), frame(card, 2, "dropped"), frame(card, 2, "done")),
                texts(context));
        } finally {
            folds.shutdownNow();
        }
    }

    // D16: a session's tick queue drains on the session's thread only, as its input does (U5.2); a drain elsewhere
    // runs nothing and is counted, and the statuses wait for the session's thread
    @Test
    void aDrainOffTheSessionsThreadRunsNothing() throws Exception {
        ExecutorService server = thread("CardSignalTest server");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        FakeRenderHost host = new FakeRenderHost(new OracleRecordRenderer());
        ServerSession session = server.submit(() -> TestSessions.start(Installation.of(host, "test host", null), pool))
            .get(WAIT_SECONDS, TimeUnit.SECONDS);
        try {
            CardRig cards = new CardRig(session.installation(), Runnable::run, session.tickQueue());
            CardEnvironment card = cards.joined(2);
            RecordingContext context = new RecordingContext();
            OcCalls.call(card, "present", context);
            host.step();
            Assertions.assertEquals(0, cards.drain(), "off the session's thread");
            Assertions.assertEquals(
                1,
                session.tickQueue()
                    .offThread());
            Assertions.assertEquals(
                1,
                session.tickQueue()
                    .queued());
            Assertions.assertEquals(Collections.emptyList(), context.signals());
            Assertions.assertEquals(0, counted(card));
            Assertions.assertEquals(
                1,
                (int) server.submit(
                    () -> session.tickQueue()
                        .drain())
                    .get(WAIT_SECONDS, TimeUnit.SECONDS),
                "on the session's thread");
            Assertions.assertEquals(
                "CardSignalTest server",
                context.signals()
                    .get(0).thread);
            Assertions.assertEquals(1, card.signalsSent());
        } finally {
            TestSessions.stop(session);
            server.shutdownNow();
        }
    }

    // D16 [proposal]: statuses still queued when the session stops are dropped and counted as unsent, and one handed
    // over after the stop, by a fold the pool still runs (21:111), is dropped at once; no drain runs any more
    @Test
    void statusesQueuedAtTheSessionsStopAreCountedAsUnsent() throws Exception {
        ServerSession session = TestSessions
            .start(Installation.of(new FakeRenderHost(), "test host", null), Executors.newSingleThreadExecutor());
        // The cards' host is not the session's, so the stop's releaseSession leaves their device open, for a fold that
        // ends after the stop
        CardRig cards = new CardRig(
            Installation.of(new FakeRenderHost(new OracleRecordRenderer()), "test host", null),
            Runnable::run,
            session.tickQueue());
        CardEnvironment card = cards.joined(2);
        RecordingContext context = new RecordingContext();
        OcCalls.call(card, "present", context);
        cards.host.step();
        OcCalls.call(card, "present", context);
        cards.clock.advanceMillis(1000);
        card.update();
        TestSessions.stop(session);
        Assertions.assertEquals(2, card.signalsUnsent(), "done 1 and dropped 2, queued at the stop");
        Assertions.assertEquals(
            0,
            session.tickQueue()
                .queued());
        cards.host.step();
        Assertions.assertEquals(3, card.signalsUnsent(), "done 2, handed over after the stop");
        Assertions.assertEquals(0, cards.drain());
        Assertions.assertEquals(
            3,
            session.tickQueue()
                .dropped());
        Assertions.assertEquals(Collections.emptyList(), context.signals());
        Assertions.assertEquals(3, counted(card));
    }

    // P7.4; D16: a status of a device the card has since replaced is stale at the drain, also with a new run's context,
    // so the fresh device's frames, which count from 1 again, are not confused with the old one's; a released device's
    // status, drained before the card connects again, is unsent, since the card's disconnect cleared its context
    @Test
    void aReplacedDevicesStatusIsStaleAndAReleasedOnesUnsentAtTheDrain() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment replaced = cards.joined(2);
        CardEnvironment released = cards.joined(3);
        RecordingContext oldRun = new RecordingContext();
        OcCalls.call(replaced, "present", oldRun);
        OcCalls.call(released, "present", oldRun);
        cards.host.step();
        Assertions.assertEquals(2, cards.ticks.queued(), "each card's done 1");
        for (CardEnvironment card : Arrays.asList(replaced, released)) {
            cards.nodes.leave(card.node());
            card.onDisconnect(card.node());
        }
        cards.nodes.join(replaced.node());
        replaced.onConnect(replaced.node());
        replaced.onConnect(replaced.node());
        RecordingContext newRun = new RecordingContext();
        OcCalls.call(replaced, "getCaps", newRun);
        Assertions.assertEquals(2, cards.drain());
        Assertions.assertEquals(Collections.emptyList(), oldRun.signals());
        Assertions.assertEquals(Collections.emptyList(), newRun.signals());
        Assertions.assertEquals(1, replaced.signalsStale());
        Assertions.assertEquals(1, counted(replaced));
        Assertions.assertEquals(1, released.signalsUnsent());
        Assertions.assertEquals(1, counted(released));
    }
}
