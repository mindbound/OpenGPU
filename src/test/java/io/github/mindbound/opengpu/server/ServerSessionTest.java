package io.github.mindbound.opengpu.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.render.FakeRenderHost;
import io.github.mindbound.opengpu.core.render.NoRenderHost;
import io.github.mindbound.opengpu.render.Installation;
import io.github.mindbound.opengpu.render.RenderHosts;

/**
 * {@link ServerSession} and its pool (S11 plan P5.2, P5.4, P15.3; amendments D1), with no MinecraftServer and no FML:
 * sessions start through the dedicated-flag and installation factories, on single-thread executors that stand in for
 * servers' threads, since a server posts both of its events on its own thread (21:111). No test installs a render host
 * (S11 amendments B1).
 */
@Timeout(60)
class ServerSessionTest {

    private static final long WAIT_SECONDS = 10;

    private final List<ServerSession> started = new ArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void stopWhatTheTestStarted() {
        started.forEach(ServerSession::stop);
        executors.forEach(ExecutorService::shutdownNow);
    }

    /** A stand-in for a server's own thread, on which its session starts and stops. */
    private ExecutorService serverThread(String name) {
        ExecutorService thread = Executors.newSingleThreadExecutor(task -> {
            Thread made = new Thread(task, name);
            made.setDaemon(true);
            return made;
        });
        executors.add(thread);
        return thread;
    }

    private ExecutorService executor() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executors.add(executor);
        return executor;
    }

    private ServerSession startOn(ExecutorService server, Callable<ServerSession> start) throws Exception {
        ServerSession session = server.submit(start)
            .get(WAIT_SECONDS, TimeUnit.SECONDS);
        started.add(session);
        return session;
    }

    private ServerSession startHere(boolean dedicated) {
        ServerSession session = ServerSession.start(dedicated, 1);
        started.add(session);
        return session;
    }

    /** FMLServerStoppedEvent on that server's thread; fails when stop takes longer than the wait. */
    private static void stopOn(ExecutorService server) throws Exception {
        // A lambda, since a reference to the overloaded stop fits both of submit's overloads
        server.submit(() -> ServerSession.stop())
            .get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static List<String> releases(FakeRenderHost host) {
        return host.log()
            .stream()
            .filter(line -> line.startsWith("releaseSession "))
            .collect(Collectors.toList());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
    }

    @Test
    void sessionIdsDifferAcrossSessionsAndGrow() {
        ServerSession first = startHere(true);
        ServerSession second = startHere(false);
        Assertions.assertTrue(first.id() >= 1, first::toString);
        Assertions.assertTrue(second.id() > first.id(), () -> first + " then " + second);
    }

    // 28:1080: one fresh id per Device, so no key is opened again within a session; the card's reconnect is U2's
    @Test
    void deviceIdsAreFreshFromOneWithinASessionOnAnyThread() throws Exception {
        ServerSession session = startHere(true);
        ExecutorService callers = Executors.newFixedThreadPool(4);
        executors.add(callers);
        List<Future<List<Integer>>> parts = new ArrayList<>();
        for (int caller = 0; caller < 4; caller++) {
            parts.add(callers.submit(() -> {
                List<Integer> ids = new ArrayList<>();
                for (int i = 0; i < 500; i++) {
                    ids.add(session.nextDeviceId());
                }
                return ids;
            }));
        }
        Set<Integer> ids = new HashSet<>();
        for (Future<List<Integer>> part : parts) {
            ids.addAll(part.get(WAIT_SECONDS, TimeUnit.SECONDS));
        }
        Assertions.assertEquals(2000, ids.size(), "every id is drawn once");
        Assertions.assertEquals(1, (int) Collections.min(ids));
        Assertions.assertEquals(2000, (int) Collections.max(ids));
        // The count is the session's: a later session's keys differ by their session id
        Assertions.assertEquals(1, startHere(true).nextDeviceId());
    }

    @Test
    void aDedicatedServerGetsNoRenderHostWithItsReason() {
        ServerSession session = startHere(true);
        Assertions.assertTrue(session.isDedicated());
        Assertions.assertSame(
            NoRenderHost.INSTANCE,
            session.installation()
                .host());
        Assertions.assertEquals(
            "dedicated server",
            session.installation()
                .unavailableReason());
        Assertions.assertNull(
            session.installation()
                .hostRenderer());
        Assertions.assertSame(Installation.dedicated(), RenderHosts.forServer(true));
    }

    // Until S12's ClientProxy.init installs GlRenderHost, an integrated server answers with the interim reason
    // (Q-S11-8)
    @Test
    void anIntegratedServerGetsTheInstalledHostWhichIsNoneUntilS12() {
        ServerSession session = startHere(false);
        Assertions.assertFalse(session.isDedicated());
        Assertions.assertSame(RenderHosts.installed(), session.installation());
        Assertions.assertSame(
            NoRenderHost.INSTANCE,
            session.installation()
                .host());
        Assertions.assertEquals(
            "host renderer not installed",
            session.installation()
                .unavailableReason());
    }

    @Test
    void thePoolHasAQuarterOfTheProcessorsFromOneToFourUnlessConfigured() {
        int[][] rows = { { 1, 1 }, { 4, 1 }, { 15, 3 }, { 16, 4 }, { 64, 4 } };
        for (int[] row : rows) {
            Assertions.assertEquals(row[1], Pool.threads(row[0], 0), "processors " + row[0]);
        }
        Assertions.assertEquals(2, Pool.threads(64, 2), "pool.threads 2");
        Assertions.assertEquals(7, Pool.threads(1, 7), "pool.threads 7");
        ThreadPoolExecutor pool = Pool.create(0, 3);
        try {
            Assertions.assertEquals(3, pool.getCorePoolSize());
            Assertions.assertEquals(3, pool.getMaximumPoolSize());
            Assertions.assertTrue(pool.getQueue() instanceof LinkedBlockingQueue, "an unbounded queue");
            Assertions.assertEquals(
                Integer.MAX_VALUE,
                pool.getQueue()
                    .remainingCapacity());
        } finally {
            pool.shutdown();
        }
        Assertions.assertThrows(IllegalArgumentException.class, () -> Pool.create(0, 0));
    }

    // Device.create's contract for its fold executor: refuse only by throwing, which a device counts (28:1073)
    @Test
    void thePoolRunsTasksOnDaemonThreadsAndRefusesByThrowingOnceStopped() throws Exception {
        ExecutorService server = serverThread("ServerSessionTest server");
        ServerSession session = startOn(server, () -> ServerSession.start(true, 2));
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(1);
        session.pool()
            .execute(() -> {
                ranOn.set(Thread.currentThread());
                ran.countDown();
            });
        Assertions.assertTrue(ran.await(WAIT_SECONDS, TimeUnit.SECONDS));
        Assertions.assertEquals(
            "opengpu-pool-" + session.id() + "-1",
            ranOn.get()
                .getName());
        Assertions.assertTrue(
            ranOn.get()
                .isDaemon());
        stopOn(server);
        Assertions.assertTrue(session.isStopped());
        Assertions.assertThrows(
            RejectedExecutionException.class,
            () -> session.pool()
                .execute(() -> {}));
    }

    // Quitting cannot hang on OpenGPU (21:111; RM:68): stop shuts the pool down and does not wait for it
    @Test
    void stopReturnsAtOnceWithAStuckTaskQueuedAndAcceptedTasksStillRun() throws Exception {
        ExecutorService server = serverThread("ServerSessionTest server");
        ServerSession session = startOn(server, () -> ServerSession.start(true, 1));
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch queuedRan = new CountDownLatch(1);
        try {
            session.pool()
                .execute(() -> awaitQuietly(release));
            session.pool()
                .execute(queuedRan::countDown);
            stopOn(server);
            Assertions.assertEquals(1, queuedRan.getCount(), "the queued task waits behind the stuck one");
        } finally {
            release.countDown();
        }
        Assertions.assertTrue(
            queuedRan.await(WAIT_SECONDS, TimeUnit.SECONDS),
            "a task the pool accepted before the stop still runs");
    }

    @Test
    void stopReleasesTheHostSessionOnceAndASecondStopDoesNothing() throws Exception {
        FakeRenderHost host = new FakeRenderHost();
        ExecutorService server = serverThread("ServerSessionTest server");
        ExecutorService pool = executor();
        ServerSession session = startOn(
            server,
            () -> ServerSession.start(true, Installation.of(host, "test host", null), pool));
        Assertions.assertSame(session, ServerSession.current());
        stopOn(server);
        stopOn(server);
        ServerSession.stop(session);
        Assertions.assertEquals(Collections.singletonList("releaseSession " + session.id()), releases(host));
        Assertions.assertNull(ServerSession.current());
        Assertions.assertTrue(pool.isShutdown());
    }

    // FMLServerStoppedEvent can follow a newer server's start (21:116): only the stopping thread's session goes
    @Test
    void stopReleasesTheSessionOfItsOwnThreadNotTheNewest() throws Exception {
        FakeRenderHost host = new FakeRenderHost();
        ExecutorService firstServer = serverThread("ServerSessionTest first server");
        ExecutorService secondServer = serverThread("ServerSessionTest second server");
        ExecutorService firstPool = executor();
        ExecutorService secondPool = executor();
        ServerSession first = startOn(
            firstServer,
            () -> ServerSession.start(false, Installation.of(host, "test host", null), firstPool));
        ServerSession second = startOn(
            secondServer,
            () -> ServerSession.start(false, Installation.of(host, "test host", null), secondPool));
        Assertions.assertSame(second, ServerSession.current());
        // This thread started no session
        ServerSession.stop();
        Assertions.assertEquals(Collections.emptyList(), releases(host));
        stopOn(firstServer);
        Assertions.assertSame(second, ServerSession.current());
        Assertions.assertTrue(first.isStopped());
        Assertions.assertFalse(second.isStopped());
        Assertions.assertEquals(Collections.singletonList("releaseSession " + first.id()), releases(host));
        CountDownLatch ran = new CountDownLatch(1);
        second.pool()
            .execute(ran::countDown);
        Assertions.assertTrue(ran.await(WAIT_SECONDS, TimeUnit.SECONDS), "the newer session's pool still runs");
        Assertions.assertThrows(
            RejectedExecutionException.class,
            () -> first.pool()
                .execute(() -> {}));
    }
}
