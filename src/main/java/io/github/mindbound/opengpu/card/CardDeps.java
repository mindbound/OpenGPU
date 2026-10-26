package io.github.mindbound.opengpu.card;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import io.github.mindbound.opengpu.render.Installation;
import io.github.mindbound.opengpu.server.ServerSession;
import io.github.mindbound.opengpu.server.TickQueue;

/**
 * What a card environment takes from its server session, in one value (S11 amendments B1): the render host its
 * devices offer to, with {@code getCaps}' host keys; the OpenGPU pool that runs their folds (21:91); the clock of the
 * 1 s {@code "dropped"} status (28:112); a fresh device id for every device (28:1080); the session id of their keys;
 * the node factory; and the session's tick queue, which hands their frame statuses to the server thread (D16).
 * {@link CardDriver} fills it from the running session on the server thread, once per card (21:63; 24:171), and tests
 * fill it with a fake host, a direct or manual executor, a manual clock and a tick queue they drain themselves, so no
 * test installs a render host or loads FML's bus. Immutable.
 */
final class CardDeps {

    final Installation installation;
    final Executor folds;
    final LongSupplier nanoClock;
    final IntSupplier deviceIds;
    final long session;
    final NodeFactory nodes;
    final TickQueue tickQueue;

    /** @throws NullPointerException for a null argument */
    CardDeps(Installation installation, Executor folds, LongSupplier nanoClock, IntSupplier deviceIds, long session,
        NodeFactory nodes, TickQueue tickQueue) {
        this.installation = Objects.requireNonNull(installation, "installation");
        this.folds = Objects.requireNonNull(folds, "folds");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.deviceIds = Objects.requireNonNull(deviceIds, "deviceIds");
        this.session = session;
        this.nodes = Objects.requireNonNull(nodes, "nodes");
        this.tickQueue = Objects.requireNonNull(tickQueue, "tickQueue");
    }

    /**
     * The session's: its installation, its pool, {@code System::nanoTime} (Device.java:138), its device ids and its id,
     * OC's node builder, and its tick queue.
     */
    static CardDeps of(ServerSession session) {
        return new CardDeps(
            session.installation(),
            session.pool(),
            System::nanoTime,
            session::nextDeviceId,
            session.id(),
            NodeFactory.OC,
            session.tickQueue());
    }

    @Override
    public String toString() {
        return "CardDeps[session " + session + ", " + installation + ", " + nodes + "]";
    }
}
