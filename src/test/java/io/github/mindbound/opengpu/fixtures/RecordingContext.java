package io.github.mindbound.opengpu.fixtures;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import li.cil.oc.api.machine.Context;
import li.cil.oc.api.machine.LimitReachedException;
import li.cil.oc.api.network.Node;

/**
 * OC's machine {@link Context} as a card's callbacks and statuses use it, for tests with OC not running (S11 plan
 * P15.1), in {@code :core}'s RecordingBudget's event format:
 * <ul>
 * <li>{@link #consumeCallBudget} is {@code Machine.consumeCallBudget} (Machine.scala:286-294): it records
 * {@code "consume %.9f"}; outside a synchronized call, the cost clamped to {@code max(0, cost)} throws OC's checked
 * {@link LimitReachedException} when it is strictly greater than what is left of the tick's budget, and is subtracted
 * otherwise. Java's signature declares nothing, as OC's Scala throws it anyway, so the fake throws it sneakily;</li>
 * <li>{@link #pause} records {@code "pause %.9f"} and returns true;</li>
 * <li>{@link #signal} records the signal, with its arguments as given, and returns false once the queue cap is
 * reached, as {@code Machine.signal} does at a full queue (Machine.scala:335-339); an accepted signal also records
 * {@code "signal <name> [<args>]"} in the events. A test's hook may run after each signal, outside this context's
 * lock, on the signalling thread;</li>
 * <li>{@link #limitReached} records {@code "limit"}, as OcCalls' retry does when a call threw OC's exception.</li>
 * </ul>
 * Thread-safe; budget per tick infinite unless given, and the queue unbounded unless capped.
 */
public final class RecordingContext implements Context {

    /** One signal the context was asked to queue. Immutable but for {@link #args}, the array as given. */
    public static final class Signal {

        public final String name;
        public final Object[] args;
        public final boolean accepted;
        public final String thread;

        Signal(String name, Object[] args, boolean accepted, String thread) {
            this.name = name;
            this.args = args;
            this.accepted = accepted;
            this.thread = thread;
        }

        @Override
        public String toString() {
            return name + " " + Arrays.toString(args) + (accepted ? "" : " refused") + " on " + thread;
        }
    }

    private final Node node;
    private final double perTick;
    private final List<String> events = new ArrayList<>();
    private final List<Signal> signals = new ArrayList<>();
    private double remaining;
    private boolean synchronizedCall;
    private int queueCap = Integer.MAX_VALUE;
    private int queued;
    private volatile Consumer<Signal> onSignal = s -> {};

    /** A context with no node and an unlimited budget. */
    public RecordingContext() {
        this(null, Double.POSITIVE_INFINITY);
    }

    /**
     * @param node    what {@link #node()} answers, the machine's node
     * @param perTick the call budget per tick, as {@code Machine.maxCallBudget}
     */
    public RecordingContext(Node node, double perTick) {
        this.node = node;
        this.perTick = perTick;
        this.remaining = perTick;
    }

    /** Every signal from now on is refused once {@code cap} have been accepted. */
    public synchronized RecordingContext capQueue(int cap) {
        queueCap = cap;
        return this;
    }

    /** Runs after each signal is recorded, on its thread, outside this context's lock. */
    public RecordingContext onSignal(Consumer<Signal> hook) {
        onSignal = hook;
        return this;
    }

    /** The next tick: the budget is full again. */
    public synchronized void newTick() {
        remaining = perTick;
    }

    /**
     * Whether {@link #consumeCallBudget} charges nothing, as in OC's synchronized calls.
     *
     * @return the mode before
     */
    public synchronized boolean setSynchronized(boolean inSynchronizedCall) {
        boolean was = synchronizedCall;
        synchronizedCall = inSynchronizedCall;
        return was;
    }

    /** Records {@code "limit"}: a call threw OC's LimitReachedException, which the machine retries. */
    public synchronized void limitReached() {
        events.add("limit");
    }

    /** A copy of the events so far: consume, pause, limit and accepted signals, in call order. */
    public synchronized List<String> events() {
        return new ArrayList<>(events);
    }

    /** A copy of the events but the signals, in RecordingBudget's form. */
    public synchronized List<String> charges() {
        List<String> charges = new ArrayList<>();
        for (String event : events) {
            if (!event.startsWith("signal ")) {
                charges.add(event);
            }
        }
        return charges;
    }

    /** A copy of every signal asked for, accepted or refused, in order. */
    public synchronized List<Signal> signals() {
        return new ArrayList<>(signals);
    }

    /**
     * Waits until {@code count} signals have been asked for.
     *
     * @return whether they were, within the timeout
     */
    public synchronized boolean awaitSignals(int count, long timeout, TimeUnit unit) throws InterruptedException {
        long end = System.nanoTime() + unit.toNanos(timeout);
        while (signals.size() < count) {
            long left = end - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            TimeUnit.NANOSECONDS.timedWait(this, left);
        }
        return true;
    }

    @Override
    public Node node() {
        return node;
    }

    @Override
    public boolean canInteract(String player) {
        return true;
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public boolean isPaused() {
        return false;
    }

    @Override
    public boolean start() {
        return false;
    }

    @Override
    public synchronized boolean pause(double seconds) {
        events.add(String.format(Locale.ROOT, "pause %.9f", seconds));
        return true;
    }

    @Override
    public boolean stop() {
        return false;
    }

    @Override
    public synchronized void consumeCallBudget(double callCost) {
        events.add(String.format(Locale.ROOT, "consume %.9f", callCost));
        if (!synchronizedCall) {
            double clamped = Math.max(0.0, callCost);
            if (clamped > remaining) {
                RecordingContext.<RuntimeException>sneakyThrow(new LimitReachedException());
            }
            remaining -= clamped;
        }
    }

    @Override
    public boolean signal(String name, Object... args) {
        Signal signal;
        synchronized (this) {
            boolean accepted = queued < queueCap;
            if (accepted) {
                queued++;
                events.add("signal " + name + " " + Arrays.toString(args));
            }
            signal = new Signal(
                name,
                args,
                accepted,
                Thread.currentThread()
                    .getName());
            signals.add(signal);
            notifyAll();
        }
        onSignal.accept(signal);
        return signal.accepted;
    }

    @Override
    public synchronized String toString() {
        return "RecordingContext[" + (perTick == Double.POSITIVE_INFINITY ? "unlimited" : remaining + " of " + perTick)
            + (synchronizedCall ? ", synchronized" : "")
            + ", "
            + signals.size()
            + " signals]";
    }

    /** Throws a checked exception through a signature that declares none, as Scala does. */
    @SuppressWarnings("unchecked")
    public static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
}
