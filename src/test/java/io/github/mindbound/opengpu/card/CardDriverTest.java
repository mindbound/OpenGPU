package io.github.mindbound.opengpu.card;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.fixtures.device.ManualClock;
import io.github.mindbound.opengpu.core.render.FakeRenderHost;
import io.github.mindbound.opengpu.fixtures.BareWorld;
import io.github.mindbound.opengpu.fixtures.FakeNodes;
import io.github.mindbound.opengpu.render.Installation;
import io.github.mindbound.opengpu.server.ServerSession;
import io.github.mindbound.opengpu.server.TestSessions;
import li.cil.oc.api.driver.item.Slot;
import li.cil.oc.api.internal.Case;
import li.cil.oc.api.internal.Drone;
import li.cil.oc.api.internal.Microcontroller;
import li.cil.oc.api.internal.Robot;
import li.cil.oc.api.internal.Server;
import li.cil.oc.api.internal.Tablet;
import li.cil.oc.api.network.EnvironmentHost;
import li.cil.oc.api.network.ManagedEnvironment;

/**
 * {@link CardDriver} and {@link CardProvider} (S11 plan P6.2, P6.3, P15.3), with no game: the item and stacks are made
 * directly, hosts are OC's API types and proxies, and a remote world is a bare {@link World} subclass allocated
 * without a constructor (amendments B6). Only the last test starts a server session, on this thread, and stops it.
 */
@Timeout(60)
class CardDriverTest {

    private static final CardItem ITEM = new CardItem();
    private static final CardDriver DRIVER = new CardDriver(ITEM);

    private static ItemStack card(int meta) {
        return new ItemStack(ITEM, 1, meta);
    }

    /** A host in {@code world}, at the origin. */
    private static EnvironmentHost host(World world) {
        return (EnvironmentHost) Proxy.newProxyInstance(
            CardDriverTest.class.getClassLoader(),
            new Class<?>[] { EnvironmentHost.class },
            (self, method, args) -> {
                switch (method.getName()) {
                    case "world":
                        return world;
                    case "xPosition":
                    case "yPosition":
                    case "zPosition":
                        return 0.0;
                    case "markChanged":
                        return null;
                    case "toString":
                        return "host in " + world;
                    case "hashCode":
                        return System.identityHashCode(self);
                    case "equals":
                        return self == args[0];
                    default:
                        throw new UnsupportedOperationException(method.getName());
                }
            });
    }

    /** Deps on a fake host, counting how often they are asked for. */
    private static Supplier<CardDeps> deps(AtomicInteger asked) {
        AtomicInteger ids = new AtomicInteger();
        return () -> {
            asked.incrementAndGet();
            return new CardDeps(
                Installation.of(new FakeRenderHost(), "test host", null),
                Runnable::run,
                new ManualClock(),
                ids::incrementAndGet,
                7,
                new FakeNodes(),
                CardRig.onThisThread());
        };
    }

    @Test
    void worksWithTheT2AndT3DamagesOfTheCardOnly() {
        Assertions.assertTrue(DRIVER.worksWith(card(2)));
        Assertions.assertTrue(DRIVER.worksWith(card(3)));
        for (int meta : new int[] { 0, 1, 4 }) {
            Assertions.assertFalse(DRIVER.worksWith(card(meta)), "damage " + meta);
        }
        Assertions.assertFalse(DRIVER.worksWith(new ItemStack(new Item(), 1, 2)), "another item");
        Assertions.assertFalse(DRIVER.worksWith(null));
    }

    // 09:52: cases and rack servers; robots, tablets, microcontrollers and drones reach no display (RM:285)
    @Test
    void worksInCasesAndServersOnly() {
        Assertions.assertTrue(DRIVER.worksWith(card(2), Case.class));
        Assertions.assertTrue(DRIVER.worksWith(card(3), Server.class));
        for (Class<? extends EnvironmentHost> host : Arrays
            .asList(Robot.class, Tablet.class, Drone.class, Microcontroller.class)) {
            Assertions.assertFalse(DRIVER.worksWith(card(3), host), host.getSimpleName());
        }
        Assertions.assertFalse(DRIVER.worksWith(card(1), Case.class), "a stack the driver does not take");
    }

    // OC's zero-based tier is 28 §8.2's t and the slot fit of Case.isItemValidForSlot (Case.scala:123-127)
    @Test
    void theSlotIsACardSlotAndTheTierIsOcsZeroBasedOne() {
        Assertions.assertEquals(Slot.Card, DRIVER.slot(card(2)));
        Assertions.assertEquals("card", DRIVER.slot(card(3)));
        Assertions.assertEquals(1, DRIVER.tier(card(2)));
        Assertions.assertEquals(2, DRIVER.tier(card(3)));
    }

    // OC builds item components on clients too (ComponentInventory.scala:101-140): no environment, no session asked
    @Test
    void aRemoteWorldGetsNoEnvironmentAndNoSessionIsAskedFor() throws Exception {
        World remote = BareWorld.allocate(true);
        AtomicInteger asked = new AtomicInteger();
        Assertions.assertNull(DRIVER.createEnvironment(card(3), host(remote), deps(asked)));
        Assertions.assertEquals(0, asked.get());
    }

    @Test
    void aServerWorldOrNoWorldGetsACardOfTheStacksTier() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        World server = BareWorld.allocate(false);
        ManagedEnvironment t2 = DRIVER.createEnvironment(card(2), host(server), deps(asked));
        ManagedEnvironment t3 = DRIVER.createEnvironment(card(3), host(null), deps(asked));
        Assertions.assertEquals(2, ((CardEnvironment) t2).tier());
        Assertions.assertEquals(3, ((CardEnvironment) t3).tier());
        Assertions.assertEquals(
            3,
            ((CardEnvironment) t3).device()
                .caps().tier);
        Assertions.assertEquals(2, asked.get());
        Assertions.assertNull(DRIVER.createEnvironment(card(1), host(server), deps(asked)), "not a card stack");
    }

    // S11 plan P5.2: no session before FMLServerStartingEvent, which OC gives no card a reason to meet
    @Test
    void withoutASessionTheCardGetsNoEnvironmentAndAWarning() {
        long before = CardDriver.withoutSession();
        Assertions.assertNull(DRIVER.createEnvironment(card(2), host(null), () -> null));
        Assertions.assertEquals(before + 1, CardDriver.withoutSession());
    }

    @Test
    void theProviderReportsTheEnvironmentClassForCardStacksOnly() {
        Assertions.assertSame(CardEnvironment.class, CardProvider.INSTANCE.getEnvironment(card(2)));
        Assertions.assertSame(CardEnvironment.class, CardProvider.INSTANCE.getEnvironment(card(3)));
        Assertions.assertNull(CardProvider.INSTANCE.getEnvironment(card(0)));
        Assertions.assertNull(CardProvider.INSTANCE.getEnvironment(new ItemStack(new Item(), 1, 3)));
        Assertions.assertNull(CardProvider.INSTANCE.getEnvironment(null));
    }

    // The deps OC's call gets come from the running session: its host, its pool, its ids (21:63; 28:1080)
    @Test
    void theRunningSessionGivesTheCardItsHostAndAFreshKey() {
        FakeRenderHost host = new FakeRenderHost();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ServerSession session = TestSessions.start(Installation.of(host, "test host", null), pool);
        try {
            CardEnvironment first = (CardEnvironment) DRIVER.createEnvironment(card(2), host(null));
            CardEnvironment second = (CardEnvironment) DRIVER.createEnvironment(card(3), host(null));
            Assertions.assertEquals(session.id(), first.deviceKey().session);
            Assertions.assertEquals(1, first.deviceKey().deviceId);
            Assertions.assertEquals(2, second.deviceKey().deviceId);
            Assertions.assertSame(host, first.renderHost());
            Assertions.assertTrue(host.isOpen(first.deviceKey()));
            // OC's network API is not set here, so OC's node builder made no node (amendments B4)
            Assertions.assertNull(first.node());
        } finally {
            TestSessions.stop(session);
        }
    }

    // D16: the deps the driver takes from the running session (CardDeps.of) hand the card's statuses to that session's
    // own tick queue, the one its server thread drains, beside its installation, pool, device ids and id (21:63;
    // 28:1080)
    @Test
    void theSessionsDepsCarryItsOwnTickQueue() {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ServerSession session = TestSessions.start(Installation.of(new FakeRenderHost(), "test host", null), pool);
        try {
            CardDeps deps = CardDeps.of(session);
            Assertions.assertSame(session.tickQueue(), deps.tickQueue);
            Assertions.assertSame(session.installation(), deps.installation);
            Assertions.assertSame(pool, deps.folds);
            Assertions.assertEquals(session.id(), deps.session);
            Assertions.assertSame(NodeFactory.OC, deps.nodes);
            Assertions.assertEquals(1, deps.deviceIds.getAsInt());
            Assertions.assertEquals(2, session.nextDeviceId(), "the session's own counter");
            long before = System.nanoTime();
            long now = deps.nanoClock.getAsLong();
            Assertions.assertTrue(before <= now && now <= System.nanoTime(), "System::nanoTime (Device.java:138)");
        } finally {
            TestSessions.stop(session);
        }
    }
}
