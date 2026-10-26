package io.github.mindbound.opengpu.card;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import io.github.mindbound.opengpu.OpenGPU;
import io.github.mindbound.opengpu.server.ServerSession;
import li.cil.oc.api.driver.item.HostAware;
import li.cil.oc.api.driver.item.Slot;
import li.cil.oc.api.network.EnvironmentHost;
import li.cil.oc.api.network.ManagedEnvironment;
import li.cil.oc.api.prefab.DriverItem;

/**
 * The card's OC item driver (24:29; 09:52; S11 plan P6.2), after OC's GPU driver (DriverGraphicsCard.scala:15-45),
 * registered in init once OC's registry exists and before OC locks it (Driver.java:73-83; Proxy.scala:105, :112-115).
 * Its environment provider is {@link CardProvider}, a class of its own, as OC's GPU has one, so {@code Driver.add} is
 * not ambiguous (S11 spike E1).
 * <ul>
 * <li>{@code worksWith(stack)} is the prefab's, by {@code isItemEqual}, so the damage counts: T2 and T3 cards only
 * (28:37);</li>
 * <li>{@code worksWith(stack, host)} also asks for a case or a rack server: robots, tablets, microcontrollers and
 * drones refuse the card, since their networks reach no display (09:52; RM:285);</li>
 * <li>the slot is a card slot, and the tier OC's zero-based one, T2 → 1 and T3 → 2, which is 28 §8.2's t (28:698)
 * and the slot fit of {@code Case.isItemValidForSlot} (Case.scala:123-127);</li>
 * <li>{@code createEnvironment} makes no environment on a client, where OC builds item components too
 * (ComponentInventory.scala:101-140; DriverGraphicsCard.scala:21-22; 00:47), and none before a server session
 * starts, which OC's lifecycle gives no card a reason to need [I; P5.2]: it logs a warning, and the card gets its
 * environment at its next connection. The data tag is the prefab's {@code oc:data}.</li>
 * </ul>
 */
public final class CardDriver extends DriverItem implements HostAware {

    private static final AtomicLong WITHOUT_SESSION = new AtomicLong();

    private final CardItem item;

    /** The driver of {@code item}'s T2 and T3 stacks. */
    public CardDriver(CardItem item) {
        super(new ItemStack(item, 1, CardItem.META_T2), new ItemStack(item, 1, CardItem.META_T3));
        this.item = item;
    }

    @Override
    public boolean worksWith(ItemStack stack, Class<? extends EnvironmentHost> host) {
        return worksWith(stack) && (isComputer(host) || isServer(host));
    }

    /** See the class comment; on the server thread, which OC builds a case's components on. */
    @Override
    public ManagedEnvironment createEnvironment(ItemStack stack, EnvironmentHost host) {
        return createEnvironment(stack, host, CardDriver::sessionDeps);
    }

    /**
     * {@link #createEnvironment(ItemStack, EnvironmentHost)} on the deps that {@code deps} gives, asked only for a
     * card on a server, so a client never reads the session.
     */
    ManagedEnvironment createEnvironment(ItemStack stack, EnvironmentHost host, Supplier<CardDeps> deps) {
        World world = host.world();
        if (world != null && world.isRemote) {
            return null;
        }
        if (!worksWith(stack)) {
            return null;
        }
        CardDeps given = deps.get();
        if (given == null) {
            WITHOUT_SESSION.incrementAndGet();
            OpenGPU.LOG.warn(
                "No OpenGPU server session for a card at ({}, {}, {}) yet; it gets its environment at its next "
                    + "connection",
                host.xPosition(),
                host.yPosition(),
                host.zPosition());
            return null;
        }
        return new CardEnvironment(CardItem.tierOf(stack), given);
    }

    @Override
    public String slot(ItemStack stack) {
        return Slot.Card;
    }

    /** OC's zero-based tier: T2 → 1, T3 → 2; 0, the prefab's, for a stack this driver does not work with. */
    @Override
    public int tier(ItemStack stack) {
        int tier = CardItem.tierOf(stack);
        return tier == 0 ? 0 : tier - 1;
    }

    /** The cards made without a session so far in this JVM, each with its warning. */
    static long withoutSession() {
        return WITHOUT_SESSION.get();
    }

    @Override
    public String toString() {
        return "CardDriver[" + item.getUnlocalizedName() + "]";
    }

    private static CardDeps sessionDeps() {
        ServerSession session = ServerSession.current();
        return session == null ? null : CardDeps.of(session);
    }
}
