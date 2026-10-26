package io.github.mindbound.opengpu.card;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.mindbound.opengpu.core.fixtures.device.ManualClock;
import io.github.mindbound.opengpu.core.fixtures.device.OracleRecordRenderer;
import io.github.mindbound.opengpu.core.render.FakeRenderHost;
import io.github.mindbound.opengpu.fixtures.FakeNodes;
import io.github.mindbound.opengpu.render.Installation;
import io.github.mindbound.opengpu.server.TickQueue;

/**
 * Cards of one test session (S11 amendments B1): a {@link FakeNodes} factory and network, an installation, a fold
 * executor, a {@link ManualClock}, one device-id counter for every card made, as a session has, and a
 * {@link TickQueue} that the test drains itself, in place of the start of a server tick (D16). No render host is
 * installed and no FML bus is loaded. The default is a {@link FakeRenderHost} on an {@link OracleRecordRenderer} with
 * direct folds, like {@code DeviceRig}'s, and a tick queue whose server thread is the thread that made the rig.
 */
final class CardRig {

    /** Every rig card's session id. */
    static final long SESSION = 5;

    final FakeNodes nodes = new FakeNodes();
    final ManualClock clock = new ManualClock();
    final Installation installation;
    /** The installation's host when it is a {@link FakeRenderHost}, else null. */
    final FakeRenderHost host;
    final Executor folds;
    /** The hand-off of the rig's cards' statuses, which {@link #drain()} runs. */
    final TickQueue ticks;
    private final AtomicInteger deviceIds = new AtomicInteger();

    /** A fake host on the oracle, available, with direct folds. */
    CardRig() {
        this(new FakeRenderHost(new OracleRecordRenderer()), Runnable::run);
    }

    /** {@code host}, whose reason while unavailable is "test host off", with no hostRenderer. */
    CardRig(FakeRenderHost host, Executor folds) {
        this(Installation.of(host, "test host off", null), folds);
    }

    CardRig(Installation installation, Executor folds) {
        this(installation, folds, onThisThread());
    }

    /** A rig whose cards hand their statuses to {@code ticks}, such as a test session's. */
    CardRig(Installation installation, Executor folds, TickQueue ticks) {
        this.installation = installation;
        this.host = installation.host() instanceof FakeRenderHost ? (FakeRenderHost) installation.host() : null;
        this.folds = folds;
        this.ticks = ticks;
    }

    /** A tick queue whose server thread is the calling one. */
    static TickQueue onThisThread() {
        Thread thread = Thread.currentThread();
        return new TickQueue(() -> Thread.currentThread() == thread);
    }

    /** The deps a card of this session gets. */
    CardDeps deps() {
        return new CardDeps(installation, folds, clock, deviceIds::incrementAndGet, SESSION, nodes, ticks);
    }

    /** A new card of OpenGPU's tier, as the driver makes it: not in a network yet. */
    CardEnvironment card(int tier) {
        return new CardEnvironment(tier, deps());
    }

    /**
     * A new card whose node has joined the network as OC joins a node: put in, then its own environment connected
     * twice (Network.scala:31-35, :250-260), which caches its address.
     */
    CardEnvironment joined(int tier) {
        CardEnvironment card = card(tier);
        nodes.join(card.node());
        card.onConnect(card.node());
        card.onConnect(card.node());
        return card;
    }

    /** The start of a server tick (D16): the tick queue's drain, on the calling thread. */
    int drain() {
        return ticks.drain();
    }

    @Override
    public String toString() {
        return "CardRig[" + installation + ", " + ticks + "]";
    }
}
