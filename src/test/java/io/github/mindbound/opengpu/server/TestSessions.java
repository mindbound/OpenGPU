package io.github.mindbound.opengpu.server;

import java.util.concurrent.ExecutorService;

import io.github.mindbound.opengpu.render.Installation;

/**
 * Server sessions for tests of other packages, through {@link ServerSession}'s package-private factory and stop, on
 * the calling thread, with no MinecraftServer and no installed host (S11 amendments B1).
 */
public final class TestSessions {

    private TestSessions() {}

    /** An integrated server's session on {@code installation} and {@code pool}, started on this thread. */
    public static ServerSession start(Installation installation, ExecutorService pool) {
        return ServerSession.start(false, installation, pool);
    }

    /** Stops the session once: releases it on its host and shuts its pool down. */
    public static void stop(ServerSession session) {
        ServerSession.stop(session);
    }
}
