package io.github.mindbound.opengpu.core.fixtures.device;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import io.github.mindbound.opengpu.core.device.CallBudget;
import io.github.mindbound.opengpu.core.device.CallResult;
import io.github.mindbound.opengpu.core.device.LimitReached;

/**
 * A {@link CallBudget} that behaves like OC's {@code Machine} and records every call (28 §8.2; H4):
 * <ul>
 * <li>{@link #consume} is {@code Machine.consumeCallBudget}
 * ({@code OCT/scala/li/cil/oc/server/machine/Machine.scala:286-294}): outside a synchronized call, the cost clamped
 * to {@code max(0, cost)} throws {@link LimitReached} (OC's {@code LimitReachedException}) when it exceeds what is
 * left of the tick's budget, and is subtracted otherwise; in a synchronized call it does nothing;</li>
 * <li>{@link #newTick} refills the budget, as {@code Machine.scala:532}'s {@code callBudget = maxCallBudget} does;</li>
 * <li>{@link #call} is OC's direct call with its retry ({@code NativeLuaArchitecture.scala:72-77}): a
 * {@code LimitReached} makes the call return nothing, and the machine repeats it as a synchronized call on the server
 * thread ({@code Machine.scala:599-604}).</li>
 * </ul>
 * Events, in call order: {@code "consume %.9f"} for every consume (with the cost as given, also one that throws and a
 * synchronized no-op), {@code "pause %.9f"} for every pause, and {@code "limit"} each time {@link #call} sees a
 * {@code LimitReached}. Thread-safe; {@link #call} holds no lock while the call runs.
 */
public final class RecordingBudget implements CallBudget {

    private final double perTick;
    private final List<String> events = new ArrayList<>();
    private double remaining;
    private boolean inSynchronizedCall;

    /** A budget without a per-tick limit: consume never throws. */
    public RecordingBudget() {
        this(Double.POSITIVE_INFINITY);
    }

    /**
     * A budget of perTick per tick, full at the start (OC's {@code maxCallBudget}, 1.0 for a machine without
     * call-budget components, {@code Machine.scala:76}, :126).
     *
     * @throws IllegalArgumentException for a negative or NaN perTick
     */
    public RecordingBudget(double perTick) {
        if (!(perTick >= 0)) {
            throw new IllegalArgumentException("budget per tick " + perTick + " is negative or NaN");
        }
        this.perTick = perTick;
        this.remaining = perTick;
    }

    /** Whether consume does nothing, as in OC's synchronized calls. */
    public synchronized void setSynchronized(boolean inSynchronizedCall) {
        this.inSynchronizedCall = inSynchronizedCall;
    }

    public synchronized boolean isSynchronized() {
        return inSynchronizedCall;
    }

    /** The next tick: the budget is full again. */
    public synchronized void newTick() {
        remaining = perTick;
    }

    /** What is left of this tick's budget; infinite for the unlimited budget. */
    public synchronized double remaining() {
        return remaining;
    }

    /**
     * Records {@code "consume %.9f"}; then, outside a synchronized call, throws {@link LimitReached} if
     * {@code max(0, cost)} exceeds the remaining budget, else subtracts it.
     */
    @Override
    public synchronized void consume(double cost) {
        events.add(String.format(Locale.ROOT, "consume %.9f", cost));
        if (!inSynchronizedCall) {
            double clamped = Math.max(0.0, cost);
            if (clamped > remaining) {
                throw new LimitReached();
            }
            remaining -= clamped;
        }
    }

    /** Records {@code "pause %.9f"}; OC's {@code Context.pause} would sleep the machine that long. */
    @Override
    public synchronized void pause(double seconds) {
        events.add(String.format(Locale.ROOT, "pause %.9f", seconds));
    }

    /** A copy of the events so far, in call order. */
    public synchronized List<String> events() {
        return new ArrayList<>(events);
    }

    public synchronized void clearEvents() {
        events.clear();
    }

    /**
     * OC's direct call: runs the call; on {@link LimitReached} records {@code "limit"} and runs it once more as a
     * synchronized call (consume off), then restores the mode it had before. A {@code LimitReached} from the retry is
     * recorded as {@code "limit"} too and propagates (OC never retries twice; only a test that resets the device
     * between the attempts reaches it).
     */
    public CallResult call(Supplier<CallResult> call) {
        try {
            return call.get();
        } catch (LimitReached first) {
            boolean was;
            synchronized (this) {
                events.add("limit");
                was = inSynchronizedCall;
                inSynchronizedCall = true;
            }
            try {
                return call.get();
            } catch (LimitReached again) {
                synchronized (this) {
                    events.add("limit");
                }
                throw again;
            } finally {
                setSynchronized(was);
            }
        }
    }

    @Override
    public synchronized String toString() {
        return "RecordingBudget[" + (perTick == Double.POSITIVE_INFINITY ? "unlimited" : remaining + " of " + perTick)
            + (inSynchronizedCall ? ", synchronized" : "")
            + "]";
    }
}
