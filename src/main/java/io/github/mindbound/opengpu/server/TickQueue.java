package io.github.mindbound.opengpu.server;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import io.github.mindbound.opengpu.OpenGPU;

/**
 * One server session's hand-off to its server thread (D16; Q-S11-17): a FIFO of entries that any thread may add and
 * only the session's thread runs, at the start of each server tick, through {@link TickListener} [proposal: the name,
 * and entries of a type of their own, so the server package names no card type]. A card hands it its frame statuses,
 * so each is signalled on the server thread, in turn with OC's {@code computer.started} and {@code computer.stopped},
 * which OC sends to the card on that thread too (Machine.scala:240, :581, :935). Entries run in the order they were
 * added: an adder that adds one at a time, as a device delivers its statuses, never from two threads at once
 * (FrameListener.java:3-9), keeps its order across threads, since the queue is linearizable (ConcurrentLinkedQueue's
 * FIFO). A drain runs every entry it finds, those added while it runs too [proposal]. The queue has no cap
 * [proposal]: a device raises about one {@code "done"} per fold and one {@code "dropped"} per frame, with at most two
 * frames unconfirmed, so a queue drained every tick holds a few entries per device. Only a listener that never drains
 * would let it grow until the stop: jarTest checks the listener's wiring, and the session's stop logs the drain count.
 * <p>
 * The drain tests the thread first, as InputHandler's check 0 does: off the session's thread it runs nothing, counts
 * the call and logs the session's first as a warning [proposal: FML posts the tick events on the server thread,
 * MinecraftServer.java:607, :661, which another mod could change]. Each drain on the session's thread is counted,
 * those that find nothing too, so the session's stop can log that its server's ticks reached the queue on that thread
 * [proposal]. An entry that throws a RuntimeException is counted, the session's first is logged with its stack trace,
 * and the entries after it still run [proposal]; an Error passes, as in InputListener. When the session stops the
 * queue closes: the entries it holds, and any added later, are dropped, each told by {@link Entry#drop()} [proposal,
 * D16].
 */
public final class TickQueue {

    /** Work for the session's server thread. Neither method may block. */
    public interface Entry {

        /** Runs on the session's server thread, at a drain. */
        void run();

        /** The session stopped before a drain took the entry, which never runs; on whichever thread found it. */
        void drop();
    }

    private final BooleanSupplier onServerThread;
    private final Queue<Entry> entries = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong drains = new AtomicLong();
    private final AtomicLong ran = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong offThread = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    /**
     * A session's queue: {@code onServerThread} tells whether the calling thread is the session's (S11 amendments D1).
     *
     * @throws NullPointerException for a null thread test
     */
    public TickQueue(BooleanSupplier onServerThread) {
        this.onServerThread = Objects.requireNonNull(onServerThread, "onServerThread");
    }

    /**
     * Adds the entry at the tail, on any thread, without blocking. Once the queue is closed, the add drops what the
     * queue holds, the entry with it, so an add after the close, or one racing it, leaves nothing behind.
     *
     * @throws NullPointerException for a null entry
     */
    public void add(Entry entry) {
        entries.add(Objects.requireNonNull(entry, "entry"));
        if (closed.get()) {
            dropAll();
        }
    }

    /**
     * Runs the queued entries in the order they were added, those added during the drain too [proposal], on the
     * session's thread only, where it counts itself; elsewhere it runs none (see the class comment). After the close
     * the queue stays empty, since every add then drops its entry itself, and no drain comes any more: the stop clears
     * {@link ServerSession#current()} first [I].
     *
     * @return how many entries ran
     */
    public int drain() {
        if (!onServerThread.getAsBoolean()) {
            if (offThread.incrementAndGet() == 1) {
                OpenGPU.LOG.warn(
                    "OpenGPU's tick queue was drained on thread {}, not the server's, and ran nothing; later ones are "
                        + "only counted",
                    Thread.currentThread()
                        .getName());
            }
            return 0;
        }
        drains.incrementAndGet();
        int n = 0;
        for (Entry entry = entries.poll(); entry != null; entry = entries.poll()) {
            n++;
            try {
                entry.run();
            } catch (RuntimeException e) {
                if (failed.incrementAndGet() == 1) {
                    OpenGPU.LOG.warn("An entry of OpenGPU's tick queue failed; later failures are only counted", e);
                }
            }
        }
        ran.addAndGet(n);
        return n;
    }

    /** At the session's stop: drops every queued entry and, from now on, every added one. */
    void close() {
        closed.set(true);
        dropAll();
    }

    /** Whether the session has stopped and closed the queue. */
    public boolean isClosed() {
        return closed.get();
    }

    /** The entries waiting for a drain. Any thread; a count, not a snapshot. */
    public int queued() {
        return entries.size();
    }

    /** Drains on the session's thread, those that ran nothing included [proposal]. */
    public long drains() {
        return drains.get();
    }

    /** Entries run by a drain, those that threw included. */
    public long ran() {
        return ran.get();
    }

    /** Entries dropped by the close. */
    public long dropped() {
        return dropped.get();
    }

    /** Drains called off the session's thread, which ran nothing. */
    public long offThread() {
        return offThread.get();
    }

    /** Entries that threw a RuntimeException as they ran. */
    public long failed() {
        return failed.get();
    }

    /** For example "TickQueue[queued 0, drains 1200, ran 12, dropped 1, off thread 0, failed 0, closed]". */
    @Override
    public String toString() {
        return "TickQueue[queued " + queued()
            + ", drains "
            + drains.get()
            + ", ran "
            + ran.get()
            + ", dropped "
            + dropped.get()
            + ", off thread "
            + offThread.get()
            + ", failed "
            + failed.get()
            + (closed.get() ? ", closed]" : "]");
    }

    private void dropAll() {
        for (Entry entry = entries.poll(); entry != null; entry = entries.poll()) {
            drop(entry);
        }
    }

    private void drop(Entry entry) {
        dropped.incrementAndGet();
        entry.drop();
    }
}
