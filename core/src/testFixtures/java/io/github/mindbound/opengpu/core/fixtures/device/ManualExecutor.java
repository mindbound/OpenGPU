package io.github.mindbound.opengpu.core.fixtures.device;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * A fold executor (21:91) that queues its tasks and runs them only when a test asks, on the test's thread, so that a
 * test decides when, and in which order, a device's folds run: {@link #runNext} in arrival order, {@link #runLast}
 * newest first. {@link #setRejecting} makes {@link #execute} throw {@link RejectedExecutionException}, as a pool that
 * shuts down does. Thread-safe; tasks run outside its lock.
 */
public final class ManualExecutor implements Executor {

    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private boolean rejecting;

    /**
     * Queues the task.
     *
     * @throws NullPointerException       for a null task
     * @throws RejectedExecutionException while {@link #setRejecting} is on, queueing nothing
     */
    @Override
    public synchronized void execute(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        if (rejecting) {
            throw new RejectedExecutionException("ManualExecutor rejects tasks");
        }
        tasks.addLast(task);
    }

    /** Whether {@link #execute} throws from now on; default false. Queued tasks stay queued. */
    public synchronized void setRejecting(boolean rejecting) {
        this.rejecting = rejecting;
    }

    /** The tasks queued and not yet run. */
    public synchronized int pending() {
        return tasks.size();
    }

    /**
     * Runs queued tasks in arrival order until none is left, also those queued while it runs.
     *
     * @return the tasks run
     */
    public int runAll() {
        int run = 0;
        Runnable task;
        while ((task = poll(true)) != null) {
            task.run();
            run++;
        }
        return run;
    }

    /**
     * Runs the oldest queued task.
     *
     * @throws IllegalStateException if none is queued
     */
    public void runNext() {
        take(true).run();
    }

    /**
     * Runs the newest queued task, ahead of older ones: out-of-order folds.
     *
     * @throws IllegalStateException if none is queued
     */
    public void runLast() {
        take(false).run();
    }

    @Override
    public synchronized String toString() {
        return "ManualExecutor[" + tasks.size() + " pending" + (rejecting ? ", rejecting" : "") + "]";
    }

    private Runnable take(boolean oldest) {
        Runnable task = poll(oldest);
        if (task == null) {
            throw new IllegalStateException("no task is queued");
        }
        return task;
    }

    private synchronized Runnable poll(boolean oldest) {
        return oldest ? tasks.pollFirst() : tasks.pollLast();
    }
}
