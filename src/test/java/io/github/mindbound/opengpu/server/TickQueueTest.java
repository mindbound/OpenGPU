package io.github.mindbound.opengpu.server;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@link TickQueue}, the hand-off of a session's frame statuses to its server thread (D16; Q-S11-17), with no FML: the
 * test calls the drain that the tick listener calls at the start of each server tick, which no test loads (S11 plan
 * P15.1), and a flag stands in for the session's thread test. The card's side is CardSignalTest's.
 */
@Timeout(60)
class TickQueueTest {

    private static final long WAIT_SECONDS = 10;

    /** An entry that records its run and its drop in {@code log}, under its name. */
    private static TickQueue.Entry entry(String name, List<String> log) {
        return new TickQueue.Entry() {

            @Override
            public void run() {
                log.add("run " + name);
            }

            @Override
            public void drop() {
                log.add("drop " + name);
            }
        };
    }

    // D16; FrameListener.java:3-9: entries run in the order they were added, from whichever thread, and one added
    // while the drain runs is run by the same drain
    @Test
    void entriesRunInTheOrderTheyWereAddedFromAnyThread() throws Exception {
        TickQueue ticks = new TickQueue(() -> true);
        List<String> log = new CopyOnWriteArrayList<>();
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            ticks.add(entry("a", log));
            other.submit(() -> ticks.add(entry("b", log)))
                .get(WAIT_SECONDS, TimeUnit.SECONDS);
            ticks.add(new TickQueue.Entry() {

                @Override
                public void run() {
                    log.add("run c");
                    ticks.add(entry("d", log));
                }

                @Override
                public void drop() {
                    log.add("drop c");
                }
            });
            Assertions.assertEquals(3, ticks.queued());
            Assertions.assertEquals(Collections.emptyList(), log, "nothing runs before the drain");
            Assertions.assertEquals(0, ticks.drains());
            Assertions.assertEquals(4, ticks.drain());
            Assertions.assertEquals(Arrays.asList("run a", "run b", "run c", "run d"), log);
            Assertions.assertEquals(0, ticks.drain());
            Assertions.assertEquals(4, ticks.ran());
            Assertions.assertEquals(2, ticks.drains(), "every drain, the empty one too [proposal]");
        } finally {
            other.shutdownNow();
        }
    }

    // D16, as InputHandler's check 0: off the session's thread a drain runs nothing and is counted, and the entries
    // wait for the session's thread
    @Test
    void offTheSessionsThreadADrainRunsNothingAndIsCounted() {
        AtomicBoolean onServerThread = new AtomicBoolean();
        TickQueue ticks = new TickQueue(onServerThread::get);
        List<String> log = new CopyOnWriteArrayList<>();
        ticks.add(entry("a", log));
        Assertions.assertEquals(0, ticks.drain());
        Assertions.assertEquals(0, ticks.drain());
        Assertions.assertEquals(2, ticks.offThread());
        Assertions.assertEquals(0, ticks.drains(), "a drain off the thread is not one of the session's");
        Assertions.assertEquals(Collections.emptyList(), log);
        Assertions.assertEquals(1, ticks.queued());
        onServerThread.set(true);
        Assertions.assertEquals(1, ticks.drain());
        Assertions.assertEquals(Collections.singletonList("run a"), log);
        Assertions.assertEquals(1, ticks.drains());
    }

    // D16 [proposal]: the session's stop closes the queue; the entries it holds are dropped in order, an entry added
    // afterwards is dropped at once, and nothing runs
    @Test
    void theCloseDropsWhatIsQueuedAndWhatComesLater() {
        TickQueue ticks = new TickQueue(() -> true);
        List<String> log = new CopyOnWriteArrayList<>();
        ticks.add(entry("a", log));
        ticks.add(entry("b", log));
        ticks.close();
        Assertions.assertTrue(ticks.isClosed());
        Assertions.assertEquals(Arrays.asList("drop a", "drop b"), log);
        ticks.add(entry("c", log));
        Assertions.assertEquals(Arrays.asList("drop a", "drop b", "drop c"), log);
        Assertions.assertEquals(0, ticks.queued());
        Assertions.assertEquals(0, ticks.drain());
        Assertions.assertEquals(3, ticks.dropped());
        Assertions.assertEquals(0, ticks.ran());
    }

    // [proposal]: an entry that throws a RuntimeException is counted and the entries after it still run, so one fault
    // neither stops the tick nor loses a status; an Error passes, as in InputListener
    @Test
    void anEntryThatThrowsIsCountedAndTheRestStillRun() {
        TickQueue ticks = new TickQueue(() -> true);
        List<String> log = new CopyOnWriteArrayList<>();
        ticks.add(new TickQueue.Entry() {

            @Override
            public void run() {
                throw new IllegalStateException("a broken entry");
            }

            @Override
            public void drop() {}
        });
        ticks.add(entry("b", log));
        Assertions.assertEquals(2, ticks.drain());
        Assertions.assertEquals(Collections.singletonList("run b"), log);
        Assertions.assertEquals(1, ticks.failed());
        ticks.add(new TickQueue.Entry() {

            @Override
            public void run() {
                throw new AssertionError("an error");
            }

            @Override
            public void drop() {}
        });
        Assertions.assertThrows(AssertionError.class, ticks::drain);
        Assertions.assertEquals(1, ticks.failed());
    }
}
