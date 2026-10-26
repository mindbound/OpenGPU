package io.github.mindbound.opengpu.server;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import net.minecraft.server.MinecraftServer;

import io.github.mindbound.opengpu.Config;
import io.github.mindbound.opengpu.OpenGPU;
import io.github.mindbound.opengpu.net.InputHandler;
import io.github.mindbound.opengpu.render.Installation;
import io.github.mindbound.opengpu.render.RenderHosts;
import li.cil.oc.api.API;

/**
 * One server's OpenGPU session, from {@code FMLServerStartingEvent} to {@code FMLServerStoppedEvent} (21:63, :111;
 * 24:171-172; S11 plan P5.2): the session id that every device key of that server carries, the render host its
 * devices capture, the OpenGPU pool that runs their folds, a fresh device id for every device, so no key is opened
 * twice within a session (28:1080), the {@link InputHandler} of its clients' pointer packets, and the
 * {@link TickQueue} that hands its cards' frame statuses to the server thread (D16); the thread test of both is the
 * session's thread (S11 plan U5.2, U5.8).
 * <p>
 * Threads. A server posts both events on its own thread (MinecraftServer.java:551; 21:111), and a card environment is
 * made on that thread too, which reads {@link #current()} once (21:63). {@code FMLServerStoppedEvent} can come after
 * the client has left for the title screen and opened another world, whose server thread has already started a newer
 * session (21:116), so {@link #stop()} releases the sessions its own thread started, never the newest one, and clears
 * {@link #current()} only while it still names the stopped session (S11 amendments D1). The tick queue runs on the
 * session's thread at the start of each tick, from {@link TickListener}, and its statuses still queued at the stop are
 * dropped (D16). Stopping never waits for the render thread or the pool, so quitting cannot hang on OpenGPU (21:111;
 * RM:68).
 * <p>
 * Settings reach the session through {@link #start(MinecraftServer)} alone, which reads {@link Config} and OC's
 * {@code API.config}; the classes it feeds take them by constructor (S11 amendments E6).
 */
public final class ServerSession {

    /**
     * OC's switch for the player's name in input signals, read from {@code API.config}, which is OC's
     * {@code opencomputers} sub-config (Proxy.scala:72; Settings.scala:345, :562), so the key has no prefix.
     */
    private static final String INPUT_USERNAME = "misc.inputUsername";

    // Session ids are drawn per JVM, so a host keying by (session, deviceId) never sees one twice (21:63)
    private static final AtomicLong SESSIONS = new AtomicLong();
    private static final AtomicReference<ServerSession> CURRENT = new AtomicReference<>();
    private static final List<ServerSession> LIVE = new CopyOnWriteArrayList<>();

    private final long id;
    private final boolean dedicated;
    private final Installation installation;
    private final ExecutorService pool;
    private final Thread thread;
    private final InputHandler input;
    private final TickQueue ticks;
    private final AtomicInteger deviceIds = new AtomicInteger();
    private final AtomicBoolean stopped = new AtomicBoolean();

    private ServerSession(long id, boolean dedicated, Installation installation, ExecutorService pool, Thread thread,
        boolean inputUsername) {
        this.id = id;
        this.dedicated = dedicated;
        this.installation = installation;
        this.pool = pool;
        this.thread = thread;
        BooleanSupplier onThread = () -> Thread.currentThread() == thread;
        this.input = new InputHandler(inputUsername, onThread);
        this.ticks = new TickQueue(onThread);
    }

    /**
     * Starts the session of {@code server} on its thread, at {@code FMLServerStartingEvent} (21:63). The worlds have
     * loaded by then ({@code handleServerAboutToStart}, {@code loadAllWorlds}, {@code handleServerStarting}:
     * IntegratedServer.java:95-98, DedicatedServer.java:259-280), and OC builds item components only from a case's
     * first tick or a description packet, so no card environment is made before it [I; S11 plan P5.2]. Captures the
     * server's {@link RenderHosts#forServer(boolean)} once (21:63), reads {@code pool.threads} from {@link Config}, and
     * reads OC's {@code misc.inputUsername} once for the session's input (S11 plan U5.5).
     */
    public static ServerSession start(MinecraftServer server) {
        return start(server.isDedicatedServer(), Config.poolThreads(), inputUsername());
    }

    /**
     * {@link #start(MinecraftServer)} on the server's dedicated flag and {@code pool.threads}, which tests call with no
     * MinecraftServer; the input carries the player's name, OC's default (application.conf's
     * {@code inputUsername: true}).
     */
    static ServerSession start(boolean dedicated, int configuredThreads) {
        return start(dedicated, configuredThreads, true);
    }

    private static ServerSession start(boolean dedicated, int configuredThreads, boolean inputUsername) {
        long id = SESSIONS.incrementAndGet();
        int threads = Pool.threads(
            Runtime.getRuntime()
                .availableProcessors(),
            configuredThreads);
        return begin(
            new ServerSession(
                id,
                dedicated,
                RenderHosts.forServer(dedicated),
                Pool.create(id, threads),
                Thread.currentThread(),
                inputUsername));
    }

    /**
     * A session on a given installation and pool, for tests, so none of them installs a host (S11 amendments B1); its
     * input carries the player's name, OC's default. The session shuts {@code pool} down when it stops.
     *
     * @throws NullPointerException for a null argument
     */
    static ServerSession start(boolean dedicated, Installation installation, ExecutorService pool) {
        Objects.requireNonNull(installation, "installation");
        Objects.requireNonNull(pool, "pool");
        return begin(
            new ServerSession(SESSIONS.incrementAndGet(), dedicated, installation, pool, Thread.currentThread(), true));
    }

    /** OC's {@code misc.inputUsername}, true when OC's config is not there, as in plain tests (S11 plan U5.5). */
    private static boolean inputUsername() {
        com.typesafe.config.Config oc = API.config;
        return oc == null || oc.getBoolean(INPUT_USERNAME);
    }

    private static ServerSession begin(ServerSession session) {
        LIVE.add(session);
        CURRENT.set(session);
        OpenGPU.LOG.info("OpenGPU session {} started: {}", session.id, session.installation);
        return session;
    }

    /** The newest running session, or null: none has started, or the newest has stopped. */
    public static ServerSession current() {
        return CURRENT.get();
    }

    /**
     * Stops the sessions the calling thread started, at {@code FMLServerStoppedEvent} on the server's thread; normally
     * there is one, and on a thread that started none this does nothing. See {@link #stop(ServerSession)}.
     */
    public static void stop() {
        Thread caller = Thread.currentThread();
        for (ServerSession session : LIVE) {
            if (session.thread == caller) {
                stop(session);
            }
        }
    }

    /**
     * Stops {@code session} once; a second call does nothing. It leaves {@link #current()} unless that is
     * {@code session}, closes its tick queue, so the statuses still queued, and any handed over later, are dropped and
     * none reaches a machine any more (D16) [proposal], calls {@code releaseSession} on its host (21:111;
     * RenderHost.java:48-49), then shuts its pool down without waiting: tasks the pool accepted still run on its daemon
     * threads, and new ones are refused by throwing, which a device counts as rejected (28:1073). The input's counts
     * above 0, and the tick queue's once it drained or did anything, are logged once at debug: on a server that ticked
     * that is every stop, so the log shows how often the server's ticks drained the queue on its thread [proposal].
     */
    static void stop(ServerSession session) {
        if (!session.stopped.compareAndSet(false, true)) {
            return;
        }
        LIVE.remove(session);
        CURRENT.compareAndSet(session, null);
        session.ticks.close();
        try {
            session.installation.host()
                .releaseSession(session.id);
        } finally {
            session.pool.shutdown();
        }
        Map<InputHandler.Outcome, Long> input = session.input.counts();
        if (!input.isEmpty()) {
            OpenGPU.LOG.debug("OpenGPU session {} input: {}", session.id, input);
        }
        TickQueue ticks = session.ticks;
        if (ticks.drains() + ticks.dropped() + ticks.offThread() > 0) {
            OpenGPU.LOG.debug("OpenGPU session {} {}", session.id, ticks);
        }
        OpenGPU.LOG.info("OpenGPU session {} stopped", session.id);
    }

    /** The session id, from 1, unique in this JVM. */
    public long id() {
        return id;
    }

    /** Whether the server is a dedicated one, as {@code isDedicatedServer()} said at the start. */
    public boolean isDedicated() {
        return dedicated;
    }

    /** The render host the session's devices use, captured at the start (21:63). */
    public Installation installation() {
        return installation;
    }

    /** The OpenGPU pool, the fold executor of the session's devices (21:91). */
    public Executor pool() {
        return pool;
    }

    /**
     * The handler of the session's pointer packets, built at the start with OC's {@code misc.inputUsername} and a
     * thread test that holds on the session's thread only (S11 plan U5.2, U5.8). Any thread may read it.
     */
    public InputHandler input() {
        return input;
    }

    /**
     * The hand-off of the session's cards' frame statuses to its server thread, drained at the start of each server
     * tick by {@link TickListener} and on the session's thread only, closed at the stop (D16; Q-S11-17). Any thread may
     * read it and add to it.
     */
    public TickQueue tickQueue() {
        return ticks;
    }

    /**
     * A fresh device id, from 1, one for every {@code Device} the session makes, also after a card's reconnect, so no
     * key is opened again within the session (28:1080). Any thread may call it.
     *
     * @throws IllegalStateException once 2^31 - 1 ids are used
     */
    public int nextDeviceId() {
        int next = deviceIds.incrementAndGet();
        if (next <= 0) {
            throw new IllegalStateException("session " + id + " has used every device id");
        }
        return next;
    }

    /** Whether the session has stopped. */
    public boolean isStopped() {
        return stopped.get();
    }

    /** For example "ServerSession[3, dedicated]". */
    @Override
    public String toString() {
        return "ServerSession[" + id
            + (dedicated ? ", dedicated" : ", integrated")
            + (isStopped() ? ", stopped]" : "]");
    }
}
