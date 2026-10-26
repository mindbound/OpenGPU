package io.github.mindbound.opengpu.server;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The OpenGPU pool of one server session (21:91; 24:172), which runs its devices' folds (21:91; 28:1073). It follows
 * {@code Device.create}'s contract for its fold executor: it runs every task it accepts and refuses one only by
 * throwing, which a device counts as rejected. So it has a fixed number of daemon threads, an unbounded queue and
 * {@link ThreadPoolExecutor.AbortPolicy}; it never runs a task on the caller's thread, which would fold on the host's
 * thread, discards nothing, and is shut down with {@code shutdown()}, never {@code shutdownNow()}, whose drained tasks
 * would leave frames unconfirmed. In M0 it runs folds only (S11 plan L1).
 */
final class Pool {

    private Pool() {}

    /**
     * The pool's thread count: {@code configured} when it is above 0 ({@code pool.threads} [proposal]), else
     * min(4, max(1, processors / 4)) (24:172).
     */
    static int threads(int processors, int configured) {
        return configured > 0 ? configured : Math.min(4, Math.max(1, processors / 4));
    }

    /**
     * A pool of {@code threads} daemon threads named "opengpu-pool-&lt;session&gt;-&lt;i&gt;", i from 1, made as tasks
     * arrive. Daemon threads let the JVM exit with tasks still queued: stopping a session never waits for them
     * (21:111).
     *
     * @throws IllegalArgumentException for fewer than 1 thread
     */
    static ThreadPoolExecutor create(long session, int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads " + threads);
        }
        AtomicInteger made = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "opengpu-pool-" + session + "-" + made.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(
            threads,
            threads,
            0L,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            factory,
            new ThreadPoolExecutor.AbortPolicy());
    }
}
