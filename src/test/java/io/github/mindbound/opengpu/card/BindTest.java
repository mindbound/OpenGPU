package io.github.mindbound.opengpu.card;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.display.DisplayTile;
import io.github.mindbound.opengpu.display.Displays;
import io.github.mindbound.opengpu.fixtures.BareWorld;
import io.github.mindbound.opengpu.fixtures.FakeNodes;
import io.github.mindbound.opengpu.fixtures.OcCalls;
import io.github.mindbound.opengpu.fixtures.RecordingContext;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.network.Node;

/**
 * The card's {@code bind} and the binding's life (28:84, :95; Q-S11-6, Q-S11-7, Q-S11-16; S11 plan P12.6, P13, P15.3;
 * amendments B2, B3; D13, D15), through OC's own dispatch and argument reader (OcCalls) on the card's side and
 * FakeNodes' network: one card to one display, the last bind taking it, the host's map following every bind, unload,
 * release, cut and reconnect, and the card keeping the display's address wherever it is to bind it again, unless
 * another card bound the display in the meantime, before a full restart too, which the display's saved binder tells.
 * OC's network events are driven by hand in the order OC sends them (Network.scala:31-35, :250-260, :349-373).
 */
@Timeout(60)
class BindTest {

    private static final String INVALID = "invalid address";
    private static final String NOT_A_DISPLAY = "not a display";

    /** A display whose node is in the rig's network. */
    private static DisplayTile display(CardRig cards) {
        DisplayTile display = Displays.on(cards.nodes);
        cards.nodes.join(display.node());
        return display;
    }

    /** {@code bind(address)} as Lua calls it, the address a Lua string, which reaches Java as bytes (00:85). */
    private static List<Object> bind(CardEnvironment card, String address) throws Exception {
        return bind(card, new RecordingContext(), address);
    }

    private static List<Object> bind(CardEnvironment card, RecordingContext context, String address) throws Exception {
        return Arrays.asList(OcCalls.call(card, "bind", context, (Object) address.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<Object> ok() {
        return Collections.singletonList(Boolean.TRUE);
    }

    private static List<Object> error(String message) {
        return Arrays.asList(null, message);
    }

    /** The host's bindDisplay lines, in order. */
    private static List<String> binds(CardRig cards) {
        return cards.host.log()
            .stream()
            .filter(line -> line.startsWith("bindDisplay"))
            .collect(Collectors.toList());
    }

    private static String mapped(DisplayTile display, DeviceKey key) {
        return "bindDisplay " + display.displayId() + " " + key;
    }

    /** The card leaves the network and hears its own disconnect: removed, moved, or its case broken or unloaded. */
    private static void leave(CardRig cards, CardEnvironment card) {
        cards.nodes.leave(card.node());
        card.onDisconnect(card.node());
    }

    /** The card joins again, connected twice as OC connects a joining node's own environment. */
    private static void rejoin(CardRig cards, CardEnvironment card) {
        cards.nodes.join(card.node());
        card.onConnect(card.node());
        card.onConnect(card.node());
    }

    /** The card's node address, which a display it attaches records as its binder (D15). */
    private static String address(CardEnvironment card) {
        return card.node()
            .address();
    }

    /** The display's NBT as its chunk writes it; TileEntity.writeToNBT needs the id mapping (P15.1). */
    private static NBTTagCompound saved(DisplayTile display) {
        Displays.mapTileEntity();
        NBTTagCompound nbt = new NBTTagCompound();
        display.writeToNBT(nbt);
        return nbt;
    }

    /** The card's NBT as its case writes it: OC's node tag, with the address, and the display's address. */
    private static NBTTagCompound saved(CardEnvironment card) {
        NBTTagCompound nbt = new NBTTagCompound();
        card.save(nbt);
        return nbt;
    }

    /**
     * The display after a full restart: a new tile entity of the new session's rig, read from {@code nbt}, its node
     * under the saved address, as OC's node loads it (TileEntityEnvironment.java:130-143; Node.scala:72-80); in no
     * network yet.
     */
    private static DisplayTile reloaded(CardRig rig, NBTTagCompound nbt) {
        DisplayTile display = Displays.on(rig.nodes);
        display.readFromNBT(nbt);
        rig.nodes.readdress(
            display.node(),
            nbt.getCompoundTag("oc:node")
                .getString("address"));
        return display;
    }

    /** A card after a full restart, as a display is: OC's node tag is "node" (ManagedEnvironment.java:42-46). */
    private static CardEnvironment reloaded(CardRig rig, int tier, NBTTagCompound nbt) {
        CardEnvironment card = rig.card(tier);
        card.load(nbt);
        rig.nodes.readdress(
            card.node(),
            nbt.getCompoundTag("node")
                .getString("address"));
        return card;
    }

    /**
     * Q-S11-16's case, saved: card A (T3) bound the display, A's case unloaded, so A detached and kept the address,
     * then card B (T2) bound the display, and the server stopped, which wrote every card and tile entity: the display
     * after its unload, B after its own. With {@code heldWhenTaken}, A was still attached when B bound, so A heard the
     * takeover and forgot the address, and A's case unloaded last.
     */
    private static final class Saved {

        final NBTTagCompound a;
        final NBTTagCompound b;
        final NBTTagCompound display;
        final String addressA;
        final String addressB;
        final String id;

        Saved() throws Exception {
            this(false);
        }

        Saved(boolean heldWhenTaken) throws Exception {
            CardRig before = new CardRig();
            CardEnvironment cardA = before.joined(3);
            CardEnvironment cardB = before.joined(2);
            DisplayTile screen = display(before);
            id = screen.displayId();
            addressA = address(cardA);
            addressB = address(cardB);
            bind(cardA, id);
            if (!heldWhenTaken) {
                leave(before, cardA);
            }
            Assertions.assertEquals(ok(), bind(cardB, id));
            screen.onChunkUnload();
            leave(before, cardB);
            if (heldWhenTaken) {
                leave(before, cardA);
            }
            Assertions.assertEquals(addressB, screen.binder(), "the last bind's, kept by the unload");
            Assertions.assertEquals(heldWhenTaken ? null : id, cardA.boundAddress());
            Assertions.assertEquals(id, cardB.boundAddress());
            a = saved(cardA);
            b = saved(cardB);
            display = saved(screen);
        }
    }

    // P13.2: a display in the card's network gives true, and the host shows the card's device on it
    @Test
    void aDisplayInTheCardsNetworkBindsAndTheHostShowsTheCardsDevice() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(3);
        DisplayTile display = display(cards);
        RecordingContext context = new RecordingContext();
        Assertions.assertEquals(ok(), bind(card, context, display.displayId()));
        Assertions.assertEquals(card.deviceKey(), cards.host.boundDevice(display.displayId()));
        Assertions.assertSame(display, card.boundDisplay());
        Assertions.assertSame(card, display.boundCard());
        Assertions.assertEquals(display.displayId(), card.boundAddress());
        Assertions.assertSame(context, card.context(), "bind sets the context, as every callback does (09:65)");
        NBTTagCompound saved = new NBTTagCompound();
        card.save(saved);
        Assertions.assertEquals(display.displayId(), saved.getString(CardEnvironment.DISPLAY), "Q-S11-6");
    }

    // Q-S11-7: an address no node in the card's network has is invalid; a node that is not a display's is no display
    @Test
    void anUnknownAddressIsInvalidAndANodeOfAnotherKindIsNotADisplay() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(3);
        DisplayTile elsewhere = Displays.on(cards.nodes);
        Node machine = cards.nodes.machine();
        cards.nodes.join(machine);
        CardEnvironment other = cards.joined(2);
        Assertions.assertEquals(error(INVALID), bind(card, "opengpu_screen-404"));
        Assertions.assertEquals(error(INVALID), bind(card, elsewhere.displayId()), "a display in another network");
        Assertions.assertEquals(error(NOT_A_DISPLAY), bind(card, machine.address()));
        Assertions.assertEquals(
            error(NOT_A_DISPLAY),
            bind(
                card,
                other.node()
                    .address()));
        Assertions.assertEquals(Collections.emptyList(), binds(cards));
        Assertions.assertNull(card.boundAddress());
        // An argument of another type stays OC's argument error (28:291)
        IllegalArgumentException wrong = Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> OcCalls.call(card, "bind", new RecordingContext(), 5.0));
        Assertions.assertEquals("bad argument #1 (string expected, got number)", wrong.getMessage());
    }

    // P13.2 step 4: the display already bound gives true and changes nothing
    @Test
    void bindingTheBoundDisplayAgainChangesNothing() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(2);
        DisplayTile display = display(cards);
        Assertions.assertEquals(ok(), bind(card, display.displayId()));
        Assertions.assertEquals(ok(), bind(card, display.displayId()));
        Assertions.assertEquals(Collections.singletonList(mapped(display, card.deviceKey())), binds(cards));
    }

    // P13.1, P13.2 step 5: one card drives one display, so binding a second unmaps the first
    @Test
    void bindingASecondDisplayUnmapsTheFirst() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(3);
        DisplayTile first = display(cards);
        DisplayTile second = display(cards);
        bind(card, first.displayId());
        Assertions.assertEquals(ok(), bind(card, second.displayId()));
        Assertions.assertNull(cards.host.boundDevice(first.displayId()));
        Assertions.assertEquals(card.deviceKey(), cards.host.boundDevice(second.displayId()));
        Assertions.assertNull(first.boundCard());
        Assertions.assertSame(card, second.boundCard());
        Assertions.assertEquals(second.displayId(), card.boundAddress());
        Assertions.assertEquals(
            Arrays.asList(mapped(first, card.deviceKey()), mapped(first, null), mapped(second, card.deviceKey())),
            binds(cards));
    }

    // Q-S11-6; P13.2 step 6: the last bind takes the display, and the card it had forgets it and its address, so its
    // next connection does not take it back; the taker becomes the binder (D15)
    @Test
    void aCardThatBindsTakesTheDisplayAndTheOldCardForgetsIt() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment old = cards.joined(3);
        CardEnvironment taker = cards.joined(2);
        DisplayTile display = display(cards);
        bind(old, display.displayId());
        Assertions.assertEquals(address(old), display.binder());
        Assertions.assertEquals(ok(), bind(taker, display.displayId()));
        Assertions.assertEquals(taker.deviceKey(), cards.host.boundDevice(display.displayId()));
        Assertions.assertSame(taker, display.boundCard());
        Assertions.assertEquals(address(taker), display.binder(), "the takeover records the taker");
        Assertions.assertNull(old.boundDisplay());
        Assertions.assertNull(old.boundAddress());
        NBTTagCompound saved = new NBTTagCompound();
        old.save(saved);
        Assertions.assertFalse(saved.hasKey(CardEnvironment.DISPLAY));
        leave(cards, old);
        rejoin(cards, old);
        Assertions.assertSame(taker, display.boundCard());
        Assertions.assertEquals(taker.deviceKey(), cards.host.boundDevice(display.displayId()));
        Assertions.assertEquals(address(taker), display.binder());
    }

    // Q-S11-6; P13.2 step 6, P13.4: a card that was detached when another card bound its display, its case unloaded or
    // the card pulled, heard no takeover; when it joins again it does not take the display back, and forgets the
    // display and its address instead (M66)
    @Test
    void aReloadedCardDoesNotTakeTheDisplayFromTheLastBinder() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment old = cards.joined(3);
        CardEnvironment taker = cards.joined(2);
        DisplayTile display = display(cards);
        String id = display.displayId();
        bind(old, id);
        leave(cards, old);
        Assertions.assertEquals(id, old.boundAddress(), "detached, keeping the address");
        Assertions.assertEquals(ok(), bind(taker, id));
        int lines = binds(cards).size();
        rejoin(cards, old);
        Assertions.assertSame(taker, display.boundCard());
        Assertions.assertSame(display, taker.boundDisplay());
        Assertions.assertEquals(id, taker.boundAddress());
        Assertions.assertEquals(taker.deviceKey(), cards.host.boundDevice(id));
        Assertions.assertEquals(lines, binds(cards).size(), "the old card's join maps nothing");
        Assertions.assertNull(old.boundDisplay());
        Assertions.assertNull(old.boundAddress(), "forgotten, as a takeover makes a bound card forget it");
        NBTTagCompound saved = new NBTTagCompound();
        old.save(saved);
        Assertions.assertFalse(saved.hasKey(CardEnvironment.DISPLAY));
    }

    // Q-S11-6; B2, P13.4: a cut cable detaches the display and keeps its address; when another card binds the display
    // before the cable is mended, the display's connect does not take it back, and the card forgets it (M66)
    @Test
    void aMendedCableDoesNotTakeTheDisplayFromTheLastBinder() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment old = cards.joined(3);
        CardEnvironment taker = cards.joined(2);
        DisplayTile display = display(cards);
        String id = display.displayId();
        bind(old, id);
        old.onDisconnect(display.node());
        Assertions.assertNull(display.boundCard(), "the cut detached it");
        Assertions.assertEquals(ok(), bind(taker, id));
        old.onConnect(display.node());
        old.onConnect(display.node());
        Assertions.assertSame(taker, display.boundCard());
        Assertions.assertEquals(id, taker.boundAddress());
        Assertions.assertEquals(taker.deviceKey(), cards.host.boundDevice(id));
        Assertions.assertNull(old.boundDisplay());
        Assertions.assertNull(old.boundAddress());
    }

    // P12.6: unloaded or broken, the display unmaps itself before the prefab removes its node; the card keeps the
    // address and binds the display again when it reloads (M26)
    @Test
    void aDisplayUnloadUnmapsItAndTheCardBindsItAgainWhenItReloads() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(3);
        DisplayTile display = display(cards);
        String id = display.displayId();
        bind(card, id);
        display.onChunkUnload();
        Assertions.assertNull(cards.host.boundDevice(id));
        Assertions.assertNull(display.boundCard());
        Assertions.assertNull(card.boundDisplay());
        Assertions.assertEquals(id, card.boundAddress());
        Assertions.assertNull(
            card.node()
                .network()
                .node(id),
            "the prefab removed the node");
        // OC then reports the removed node to the card, which has nothing left to do
        card.onDisconnect(display.node());
        Assertions.assertEquals(
            Arrays.asList(mapped(display, card.deviceKey()), "bindDisplay " + id + " null"),
            binds(cards));
        // The chunk loads again: a new tile entity whose node has the saved address joins
        DisplayTile reloaded = Displays.on(cards.nodes);
        cards.nodes.readdress(reloaded.node(), id);
        cards.nodes.join(reloaded.node());
        card.onConnect(reloaded.node());
        Assertions.assertEquals(card.deviceKey(), cards.host.boundDevice(id));
        Assertions.assertSame(reloaded, card.boundDisplay());
        // Broken: invalidate unmaps it the same way
        reloaded.invalidate();
        Assertions.assertNull(cards.host.boundDevice(id));
        Assertions.assertNull(card.boundDisplay());
        Assertions.assertEquals(id, card.boundAddress());
        Assertions.assertFalse(
            card.device()
                .isReleased(),
            "the device lives on with the card (21:108)");
    }

    // P13.3; 28:151: the card's release unmaps its display and keeps the address
    @Test
    void aCardReleaseUnmapsTheDisplayAndKeepsItsAddress() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(3);
        DisplayTile display = display(cards);
        bind(card, display.displayId());
        DeviceKey key = card.deviceKey();
        leave(cards, card);
        Assertions.assertNull(cards.host.boundDevice(display.displayId()));
        Assertions.assertNull(display.boundCard());
        Assertions.assertEquals(display.displayId(), card.boundAddress());
        List<String> log = cards.host.log();
        Assertions.assertEquals(
            Arrays.asList("bindDisplay " + display.displayId() + " null", "release " + key),
            log.subList(log.size() - 2, log.size()));
    }

    // P13.3, P13.4; 28:1080: the card's next connection has a new device, and the display shows it
    @Test
    void aReconnectBindsAgainUnderTheNewKey() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(3);
        DisplayTile display = display(cards);
        bind(card, display.displayId());
        DeviceKey first = card.deviceKey();
        leave(cards, card);
        rejoin(cards, card);
        DeviceKey second = card.deviceKey();
        Assertions.assertNotEquals(first, second);
        Assertions.assertEquals(second, cards.host.boundDevice(display.displayId()));
        Assertions.assertSame(card, display.boundCard());
        Assertions.assertEquals(
            Arrays.asList(mapped(display, first), mapped(display, null), mapped(display, second)),
            binds(cards));
    }

    // B2: OC re-addresses a node by a remove and a join (Network.scala:226-229, :276-300); the card detaches and keeps
    // the old address, which no longer resolves, and the description carries the new id for Lua to bind
    @Test
    void anAddressChangeUnmapsTheDisplayAndTheDescriptionCarriesTheNewId() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(3);
        DisplayTile display = display(cards);
        String old = display.displayId();
        bind(card, old);
        cards.nodes.leave(display.node());
        card.onDisconnect(display.node());
        cards.nodes.readdress(display.node(), "opengpu_screen-new");
        cards.nodes.join(display.node());
        display.onConnect(display.node());
        card.onConnect(display.node());
        Assertions.assertNull(cards.host.boundDevice(old));
        Assertions.assertNull(cards.host.boundDevice("opengpu_screen-new"));
        Assertions.assertNull(card.boundDisplay());
        Assertions.assertEquals(old, card.boundAddress());
        Assertions.assertEquals(
            "opengpu_screen-new",
            ((S35PacketUpdateTileEntity) display.getDescriptionPacket()).func_148857_g()
                .getString("opengpu:displayId"));
        Assertions.assertEquals(ok(), bind(card, "opengpu_screen-new"));
        Assertions.assertEquals(card.deviceKey(), cards.host.boundDevice("opengpu_screen-new"));
    }

    // 28:84, :708; P13.6: bind is not direct, so OC runs it on the server thread once per tick; it charges nothing,
    // even on an empty budget, raises no signal and leaves the device as it was
    @Test
    void bindIsNotDirectAndChargesNothing() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(2);
        DisplayTile display = display(cards);
        Callback callback = OcCalls.callbacks(card)
            .get("bind");
        Assertions.assertFalse(callback.direct());
        Assertions.assertEquals(Integer.MAX_VALUE, callback.limit());
        RecordingContext empty = new RecordingContext(null, 0.0);
        long epoch = card.device()
            .epoch();
        List<Record> offered = cards.host.offered(card.deviceKey());
        Assertions.assertEquals(ok(), bind(card, empty, display.displayId()));
        Assertions.assertEquals(Collections.emptyList(), empty.events());
        Assertions.assertEquals(Collections.emptyList(), empty.signals());
        Assertions.assertEquals(
            epoch,
            card.device()
                .epoch());
        Assertions.assertEquals(offered, cards.host.offered(card.deviceKey()));
    }

    // B2 (M39): a cut cable reports only the display's disconnect to the card, which unmaps it at once; a bind to a
    // second display then leaves only that one mapped
    @Test
    void aCutThenABindToASecondDisplayLeavesOnlyTheSecondMapped() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(3);
        DisplayTile first = display(cards);
        DisplayTile second = display(cards);
        bind(card, first.displayId());
        cards.nodes.leave(first.node());
        card.onDisconnect(first.node());
        Assertions.assertNull(cards.host.boundDevice(first.displayId()), "the cut unmaps the display");
        Assertions.assertNull(first.boundCard());
        Assertions.assertEquals(first.displayId(), card.boundAddress());
        Assertions.assertEquals(ok(), bind(card, second.displayId()));
        Assertions.assertNull(cards.host.boundDevice(first.displayId()));
        Assertions.assertEquals(card.deviceKey(), cards.host.boundDevice(second.displayId()));
        Assertions.assertEquals(
            Arrays.asList(mapped(first, card.deviceKey()), mapped(first, null), mapped(second, card.deviceKey())),
            binds(cards));
    }

    // B2: when the cable is mended, the display's connect binds it again under the card's key
    @Test
    void aCutThenAReconnectBindsAgain() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(3);
        DisplayTile display = display(cards);
        bind(card, display.displayId());
        cards.nodes.leave(display.node());
        card.onDisconnect(display.node());
        Assertions.assertNull(cards.host.boundDevice(display.displayId()));
        cards.nodes.join(display.node());
        card.onConnect(display.node());
        card.onConnect(display.node());
        Assertions.assertEquals(card.deviceKey(), cards.host.boundDevice(display.displayId()));
        Assertions.assertSame(display, card.boundDisplay());
        Assertions.assertSame(card, display.boundCard());
        Assertions.assertEquals(3, binds(cards).size());
    }

    // B2: a display that is no longer the card's going away changes nothing
    @Test
    void aLateDisplayGoneFromAnotherDisplayChangesNothing() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(3);
        DisplayTile first = display(cards);
        DisplayTile second = display(cards);
        bind(card, first.displayId());
        bind(card, second.displayId());
        int lines = binds(cards).size();
        first.onChunkUnload();
        card.displayGone(first);
        card.displayTaken(first);
        Assertions.assertEquals(lines, binds(cards).size());
        Assertions.assertSame(second, card.boundDisplay());
        Assertions.assertEquals(second.displayId(), card.boundAddress());
        Assertions.assertEquals(card.deviceKey(), cards.host.boundDevice(second.displayId()));
    }

    // Q-S11-16, D15: after a full restart the older card connects first and finds the display free, but bound last by
    // B, so A forgets the address and the display stays free; then B connects and takes it
    @Test
    void afterARestartTheOlderCardConnectingFirstLeavesTheDisplayToTheLastBinder() throws Exception {
        Saved saved = new Saved();
        CardRig after = new CardRig();
        DisplayTile display = reloaded(after, saved.display);
        CardEnvironment a = reloaded(after, 3, saved.a);
        CardEnvironment b = reloaded(after, 2, saved.b);
        Assertions.assertEquals(saved.addressB, display.binder(), "read back");
        after.nodes.join(display.node());
        rejoin(after, a);
        a.onConnect(display.node());
        Assertions.assertNull(display.boundCard(), "the display stays free");
        Assertions.assertNull(a.boundDisplay());
        Assertions.assertNull(a.boundAddress(), "A forgets the address");
        Assertions.assertFalse(
            saved(a).hasKey(CardEnvironment.DISPLAY),
            "and saves none, so it never takes the display back");
        Assertions.assertEquals(Collections.emptyList(), binds(after));
        rejoin(after, b);
        Assertions.assertSame(b, display.boundCard());
        Assertions.assertSame(display, b.boundDisplay());
        Assertions.assertEquals(saved.id, b.boundAddress());
        Assertions.assertEquals(Collections.singletonList(mapped(display, b.deviceKey())), binds(after));
        Assertions.assertEquals(saved.addressB, display.binder());
    }

    // Q-S11-16, D15: after a full restart the last binder connects first and takes the display; the older card then
    // finds it held and bound last by B, and forgets the address
    @Test
    void afterARestartTheLastBinderConnectingFirstTakesTheDisplayAndTheOlderCardForgetsIt() throws Exception {
        Saved saved = new Saved();
        CardRig after = new CardRig();
        DisplayTile display = reloaded(after, saved.display);
        CardEnvironment a = reloaded(after, 3, saved.a);
        CardEnvironment b = reloaded(after, 2, saved.b);
        after.nodes.join(display.node());
        rejoin(after, b);
        Assertions.assertSame(b, display.boundCard());
        rejoin(after, a);
        a.onConnect(display.node());
        Assertions.assertSame(b, display.boundCard());
        Assertions.assertSame(display, b.boundDisplay());
        Assertions.assertEquals(b.deviceKey(), after.host.boundDevice(saved.id));
        Assertions.assertNull(a.boundDisplay());
        Assertions.assertNull(a.boundAddress());
        Assertions.assertEquals(Collections.singletonList(mapped(display, b.deviceKey())), binds(after));
        Assertions.assertEquals(saved.addressB, display.binder());
    }

    // Q-S11-16, D15: a takeover of the display while the older card still held it records the taker as the binder too,
    // so after a full restart the taker binds the display again by itself, though the older card connects first; the
    // older card forgot the address at the takeover and binds nothing
    @Test
    void afterARestartATakeoverOfAHeldDisplayLeavesItToTheTaker() throws Exception {
        Saved saved = new Saved(true);
        CardRig after = new CardRig();
        DisplayTile display = reloaded(after, saved.display);
        CardEnvironment a = reloaded(after, 3, saved.a);
        CardEnvironment b = reloaded(after, 2, saved.b);
        Assertions.assertEquals(saved.addressB, display.binder(), "read back");
        Assertions.assertFalse(saved.a.hasKey(CardEnvironment.DISPLAY), "A saved no address");
        after.nodes.join(display.node());
        rejoin(after, a);
        a.onConnect(display.node());
        Assertions.assertNull(display.boundCard());
        Assertions.assertNull(a.boundAddress());
        rejoin(after, b);
        Assertions.assertSame(b, display.boundCard());
        Assertions.assertSame(display, b.boundDisplay());
        Assertions.assertEquals(saved.id, b.boundAddress());
        Assertions.assertEquals(Collections.singletonList(mapped(display, b.deviceKey())), binds(after));
        Assertions.assertEquals(saved.addressB, display.binder());
    }

    // D15: a display whose binder never returns stays free, whichever card connects, until a Lua bind takes it and
    // records its card as the binder
    @Test
    void aDisplayWhoseBinderNeverReturnsStaysFreeUntilALuaBind() throws Exception {
        Saved saved = new Saved();
        CardRig after = new CardRig();
        DisplayTile display = reloaded(after, saved.display);
        CardEnvironment a = reloaded(after, 3, saved.a);
        after.nodes.join(display.node());
        rejoin(after, a);
        a.onConnect(display.node());
        Assertions.assertNull(display.boundCard());
        Assertions.assertNull(a.boundAddress());
        Assertions.assertEquals(saved.addressB, display.binder(), "B's, until a bind");
        Assertions.assertEquals(Collections.emptyList(), binds(after));
        Assertions.assertEquals(ok(), bind(a, saved.id));
        Assertions.assertSame(a, display.boundCard());
        Assertions.assertEquals(a.deviceKey(), after.host.boundDevice(saved.id));
        Assertions.assertEquals(saved.id, a.boundAddress());
        Assertions.assertEquals(saved.addressA, display.binder());
        Assertions.assertEquals(saved.addressA, saved(display).getString("opengpu:binder"));
    }

    // D15: a display saved before D15 has no opengpu:binder and reads none, so the first card to connect binds it, as
    // before, and becomes its binder; the other card then forgets the address
    @Test
    void aDisplaySavedBeforeTheBinderGoesToTheFirstCardToConnectWhichItRecords() throws Exception {
        Saved saved = new Saved();
        NBTTagCompound old = (NBTTagCompound) saved.display.copy();
        old.removeTag("opengpu:binder");
        CardRig after = new CardRig();
        DisplayTile display = reloaded(after, old);
        CardEnvironment a = reloaded(after, 3, saved.a);
        CardEnvironment b = reloaded(after, 2, saved.b);
        Assertions.assertNull(display.binder());
        after.nodes.join(display.node());
        rejoin(after, a);
        Assertions.assertSame(a, display.boundCard());
        Assertions.assertEquals(a.deviceKey(), after.host.boundDevice(saved.id));
        Assertions.assertEquals(saved.addressA, display.binder(), "recorded");
        rejoin(after, b);
        b.onConnect(display.node());
        Assertions.assertSame(a, display.boundCard());
        Assertions.assertNull(b.boundAddress());
        Assertions.assertEquals(saved.addressA, saved(display).getString("opengpu:binder"));
    }

    // D15: a cut cable, the card's release and the display's unload all detach the display and keep its binder, which a
    // save then writes; the mended cable and the card's return bind it again, since its binder is that card
    @Test
    void theBinderOutlivesACutAReleaseAndAnUnload() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(3);
        DisplayTile display = display(cards);
        String id = display.displayId();
        String self = address(card);
        bind(card, id);
        Assertions.assertEquals(self, display.binder());
        cards.nodes.leave(display.node());
        card.onDisconnect(display.node());
        Assertions.assertNull(display.boundCard(), "the cut detached it");
        Assertions.assertEquals(self, display.binder(), "the cut keeps it");
        Assertions.assertEquals(self, saved(display).getString("opengpu:binder"));
        cards.nodes.join(display.node());
        card.onConnect(display.node());
        Assertions.assertSame(card, display.boundCard(), "the mend binds it again");
        leave(cards, card);
        Assertions.assertNull(display.boundCard(), "the release detached it");
        Assertions.assertEquals(self, display.binder(), "the release keeps it");
        rejoin(cards, card);
        Assertions.assertSame(card, display.boundCard(), "the return binds it again");
        display.onChunkUnload();
        Assertions.assertNull(card.boundDisplay(), "the unload detached it");
        Assertions.assertEquals(self, display.binder(), "the unload keeps it");
        Assertions.assertEquals(self, saved(display).getString("opengpu:binder"));
    }

    // D15, past D13: the older card's cable is mended while the last binder is itself detached, so no card holds the
    // display; its binder is still the last binder, so the older card forgets the address, and the last binder takes
    // the display when it returns
    @Test
    void aMendedCableLeavesAFreeDisplayToItsBinderWhileTheBinderIsAway() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment old = cards.joined(3);
        CardEnvironment last = cards.joined(2);
        DisplayTile display = display(cards);
        String id = display.displayId();
        bind(old, id);
        old.onDisconnect(display.node());
        Assertions.assertEquals(ok(), bind(last, id));
        leave(cards, last);
        Assertions.assertNull(display.boundCard(), "the last binder is away");
        old.onConnect(display.node());
        old.onConnect(display.node());
        Assertions.assertNull(display.boundCard(), "free, and left to its binder");
        Assertions.assertNull(old.boundDisplay());
        Assertions.assertNull(old.boundAddress());
        Assertions.assertNull(cards.host.boundDevice(id));
        Assertions.assertEquals(address(last), display.binder());
        rejoin(cards, last);
        Assertions.assertSame(last, display.boundCard());
        Assertions.assertEquals(last.deviceKey(), cards.host.boundDevice(id));
    }

    // D15: the binder is saved as the String opengpu:binder beside the shape and precise mode, the key as written,
    // since it is the saved format; a fresh tile entity reads it back, and an empty, missing or non-String value reads
    // as none
    @Test
    void theBinderIsSavedAsAStringAndAnEmptyMissingOrOtherValueReadsAsNone() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(3);
        DisplayTile display = display(cards);
        Assertions.assertNull(display.binder(), "never bound");
        Assertions.assertFalse(saved(display).hasKey("opengpu:binder"), "none is not written");
        bind(card, display.displayId());
        NBTTagCompound nbt = saved(display);
        Assertions.assertEquals(
            new TreeSet<>(
                Arrays.asList(
                    "id",
                    "x",
                    "y",
                    "z",
                    "oc:node",
                    "opengpu:facing",
                    "opengpu:rotation",
                    "opengpu:thickness",
                    "opengpu:tiltH",
                    "opengpu:tiltV",
                    "opengpu:precise",
                    "opengpu:binder")),
            new TreeSet<>(nbt.func_150296_c()));
        Assertions.assertTrue(nbt.hasKey("opengpu:binder", 8), "a String tag");
        Assertions.assertEquals(address(card), nbt.getString("opengpu:binder"));
        FakeNodes nodes = new FakeNodes();
        DisplayTile loaded = Displays.on(nodes);
        loaded.readFromNBT(nbt);
        Assertions.assertEquals(address(card), loaded.binder());
        nbt.setString("opengpu:binder", "");
        loaded.readFromNBT(nbt);
        Assertions.assertNull(loaded.binder(), "empty");
        nbt.setInteger("opengpu:binder", 5);
        DisplayTile other = Displays.on(nodes);
        other.readFromNBT(nbt);
        Assertions.assertNull(other.binder(), "not a String");
        nbt.removeTag("opengpu:binder");
        DisplayTile missing = Displays.on(nodes);
        missing.readFromNBT(nbt);
        Assertions.assertNull(missing.binder(), "missing");
    }

    // D15 [proposal]: a change of binder marks the display's chunk changed, so the next save writes it, even when
    // nothing else in that chunk changed (Chunk.java:1111-1125); the binder binding again changes nothing and marks
    // nothing
    @Test
    void aChangeOfBinderMarksTheDisplaysChunkChanged() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment first = cards.joined(3);
        CardEnvironment second = cards.joined(2);
        DisplayTile display = display(cards);
        display.xCoord = 4;
        display.yCoord = 70;
        display.zCoord = 9;
        BareWorld world = BareWorld.allocate(false);
        world.put(display);
        String id = display.displayId();
        bind(first, id);
        Assertions.assertEquals(Collections.singletonList("4 70 9"), world.modified(), "none to the first card");
        first.onDisconnect(display.node());
        first.onConnect(display.node());
        Assertions.assertSame(first, display.boundCard());
        Assertions.assertEquals(
            1,
            world.modified()
                .size(),
            "the binder bound again");
        bind(second, id);
        Assertions.assertEquals(Arrays.asList("4 70 9", "4 70 9"), world.modified(), "the takeover");
    }
}
