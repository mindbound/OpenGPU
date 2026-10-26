package io.github.mindbound.opengpu.card;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.minecraft.nbt.NBTTagCompound;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.ResetRecord;
import io.github.mindbound.opengpu.display.DisplayTile;
import io.github.mindbound.opengpu.display.Displays;
import io.github.mindbound.opengpu.fixtures.FakeNodes;
import io.github.mindbound.opengpu.fixtures.OcCalls;
import io.github.mindbound.opengpu.fixtures.RecordingContext;
import li.cil.oc.api.network.Node;

/**
 * The card's lifecycle on the server thread (28:150-151; 21:106-108; S11 plan P7.4, P13.3-P13.5, P15.3; amendments
 * B2, B3, B7-B9): resets on a neighbour's start or stop, release on the card's own disconnect, a fresh device under a
 * new id at its next connect, the display detached and bound again, and OC's join sequences, with every handler
 * idempotent. The events are driven by hand in the order OC's network sends them (Network.scala:31-35, :250-260,
 * :349-373), on {@code FakeNodes}' network; the host's log and offered records show the effects.
 */
@Timeout(60)
class CardLifecycleTest {

    private static List<String> log(CardRig cards, String prefix) {
        return cards.host.log()
            .stream()
            .filter(line -> line.startsWith(prefix))
            .collect(Collectors.toList());
    }

    private static List<String> resets(CardRig cards, DeviceKey key) {
        return cards.host.offered(key)
            .stream()
            .filter(r -> r.kind() == Record.RESET)
            .map(r -> "RESET e" + ((ResetRecord) r).epoch)
            .collect(Collectors.toList());
    }

    /** A card with the display's address saved, as after a reload; not in a network yet. */
    private static CardEnvironment savedWith(CardRig cards, DisplayTile display, int tier) {
        CardEnvironment card = cards.card(tier);
        NBTTagCompound saved = new NBTTagCompound();
        saved.setString(CardEnvironment.DISPLAY, display.displayId());
        card.load(saved);
        return card;
    }

    /** A card bound to a display in its network: saved with its address, then joined. */
    private static CardEnvironment boundTo(CardRig cards, DisplayTile display) {
        cards.nodes.join(display.node());
        CardEnvironment card = savedWith(cards, display, 3);
        cards.nodes.join(card.node());
        card.onConnect(card.node());
        card.onConnect(card.node());
        Assertions.assertSame(display, card.boundDisplay());
        Assertions.assertSame(card, display.boundCard());
        return card;
    }

    // 28:150; 12:138: a neighbour's start or stop resets the device and clears the context, so no dead Machine stays
    // referenced (12:145; amendments B8)
    @Test
    void aNeighboursStartAndStopResetTheDeviceAndClearTheContext() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(3);
        Node machine = cards.nodes.machine();
        cards.nodes.join(machine);
        cards.nodes.neighbours(card.node(), machine);
        RecordingContext context = new RecordingContext();
        OcCalls.call(card, "getCaps", context);
        Assertions.assertSame(context, card.context());
        card.onMessage(FakeNodes.message("computer.started", machine));
        Assertions.assertNull(card.context());
        Assertions.assertEquals(
            2,
            card.device()
                .epoch());
        OcCalls.call(card, "getCaps", context);
        card.onMessage(FakeNodes.message("computer.stopped", machine));
        Assertions.assertNull(card.context());
        Assertions.assertEquals(Arrays.asList("RESET e2", "RESET e3"), resets(cards, card.deviceKey()));
    }

    // Only the card's own machine resets it (GraphicsCard.scala:538-545), and only by these two messages
    @Test
    void anotherNodesStartAndANeighboursOtherMessagesAreIgnored() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(3);
        Node neighbour = cards.nodes.machine();
        Node other = cards.nodes.machine();
        cards.nodes.join(neighbour, other);
        cards.nodes.neighbours(card.node(), neighbour);
        RecordingContext context = new RecordingContext();
        OcCalls.call(card, "getCaps", context);
        card.onMessage(FakeNodes.message("computer.started", other));
        card.onMessage(FakeNodes.message("computer.stopped", other));
        card.onMessage(FakeNodes.message("computer.signal", neighbour));
        Assertions.assertEquals(
            1,
            card.device()
                .epoch());
        Assertions.assertSame(context, card.context());
        Assertions.assertEquals(Collections.emptyList(), resets(cards, card.deviceKey()));
    }

    // 28:151; 21:106; P13.3: removed, moved or unloaded, the card unmaps its display, keeps its address and releases
    @Test
    void theCardsOwnDisconnectDetachesTheDisplayKeepsItsAddressAndReleases() throws Exception {
        CardRig cards = new CardRig();
        DisplayTile display = Displays.on(cards.nodes);
        CardEnvironment card = boundTo(cards, display);
        DeviceKey key = card.deviceKey();
        OcCalls.call(card, "getCaps", new RecordingContext());
        cards.nodes.leave(card.node());
        card.onDisconnect(card.node());
        List<String> log = cards.host.log();
        Assertions.assertEquals(
            Arrays.asList("bindDisplay " + display.displayId() + " null", "release " + key),
            log.subList(log.size() - 2, log.size()));
        Assertions.assertTrue(
            card.device()
                .isReleased());
        Assertions.assertNull(card.context());
        Assertions.assertNull(card.boundDisplay());
        Assertions.assertNull(display.boundCard());
        Assertions.assertEquals(display.displayId(), card.boundAddress());
    }

    // 28:151, :1080: the next connection gets a new device under a fresh id, which works, and binds the display again
    @Test
    void aReconnectGetsAFreshDeviceUnderANewIdThatWorksAndBindsTheDisplayAgain() throws Exception {
        CardRig cards = new CardRig();
        DisplayTile display = Displays.on(cards.nodes);
        CardEnvironment card = boundTo(cards, display);
        DeviceKey first = card.deviceKey();
        cards.nodes.leave(card.node());
        card.onDisconnect(card.node());
        cards.nodes.join(card.node());
        card.onConnect(card.node());
        card.onConnect(card.node());
        // A kept device would throw IllegalStateException("device released") here; a new one starts at frame 1
        Assertions.assertEquals(
            Collections.singletonList(1L),
            Arrays.asList(OcCalls.call(card, "present", new RecordingContext())));
        DeviceKey second = card.deviceKey();
        Assertions.assertEquals(new DeviceKey(CardRig.SESSION, first.deviceId + 1), second);
        Assertions.assertEquals(second, cards.host.boundDevice(display.displayId()));
        Assertions.assertEquals(
            Arrays.asList(
                "bindDisplay " + display.displayId() + " " + first,
                "bindDisplay " + display.displayId() + " null",
                "bindDisplay " + display.displayId() + " " + second),
            log(cards, "bindDisplay"));
        Assertions.assertEquals(Arrays.asList("open " + first, "open " + second), log(cards, "open"));
    }

    // Amendments B2: a cut cable reports only the display's disconnect to the card (Network.scala:349-373)
    @Test
    void aDisplaysDisconnectDetachesItKeepingItsAddressAndItsConnectBindsItAgain() {
        CardRig cards = new CardRig();
        DisplayTile display = Displays.on(cards.nodes);
        CardEnvironment card = boundTo(cards, display);
        DeviceKey key = card.deviceKey();
        card.onDisconnect(display.node());
        Assertions.assertNull(card.boundDisplay());
        Assertions.assertNull(display.boundCard());
        Assertions.assertNull(cards.host.boundDevice(display.displayId()));
        Assertions.assertEquals(display.displayId(), card.boundAddress());
        Assertions.assertFalse(
            card.device()
                .isReleased(),
            "the device lives on with the card (21:108)");
        card.onConnect(display.node());
        Assertions.assertSame(display, card.boundDisplay());
        Assertions.assertEquals(key, cards.host.boundDevice(display.displayId()));
    }

    // 28:1080; amendments B9 (M16): ids are the session's, so two cards never share a key
    @Test
    void twoCardsOfOneSessionGetDistinctKeys() {
        CardRig cards = new CardRig();
        CardEnvironment a = cards.joined(2);
        CardEnvironment b = cards.joined(3);
        Assertions.assertNotEquals(a.deviceKey(), b.deviceKey());
        Assertions.assertEquals(CardRig.SESSION, b.deviceKey().session);
        Assertions.assertTrue(cards.host.isOpen(a.deviceKey()));
        Assertions.assertTrue(cards.host.isOpen(b.deviceKey()));
    }

    // Amendments B3: OC connects a joining node's own environment twice, and the display's node more than once
    @Test
    void aDoubleConnectGivesOneDeviceAndOneBind() {
        CardRig cards = new CardRig();
        DisplayTile display = Displays.on(cards.nodes);
        cards.nodes.join(display.node());
        CardEnvironment card = savedWith(cards, display, 2);
        cards.nodes.join(card.node());
        card.onConnect(card.node());
        card.onConnect(card.node());
        card.onConnect(display.node());
        card.onConnect(display.node());
        Assertions.assertEquals(Collections.singletonList("open " + card.deviceKey()), log(cards, "open"));
        Assertions.assertEquals(
            Collections.singletonList("bindDisplay " + display.displayId() + " " + card.deviceKey()),
            log(cards, "bindDisplay"));
    }

    // Amendments B3: a card added where its case has no network joins a temporary one to save and leaves it
    // (ManagedEnvironment.java:49-69; ComponentInventory.scala:149-151); the real join then gets a fresh device
    @Test
    void aJoinSaveAndRemoveThenTheRealJoinGetsAFreshDevice() {
        CardRig cards = new CardRig();
        DisplayTile display = Displays.on(cards.nodes);
        CardEnvironment card = savedWith(cards, display, 3);
        DeviceKey first = card.deviceKey();
        card.onConnect(card.node());
        NBTTagCompound saved = new NBTTagCompound();
        card.save(saved);
        card.onDisconnect(card.node());
        Assertions.assertTrue(
            card.device()
                .isReleased());
        Assertions.assertEquals(display.displayId(), saved.getString(CardEnvironment.DISPLAY));
        cards.nodes.join(display.node(), card.node());
        card.onConnect(card.node());
        card.onConnect(card.node());
        Assertions.assertEquals(new DeviceKey(CardRig.SESSION, first.deviceId + 1), card.deviceKey());
        Assertions.assertSame(display, card.boundDisplay());
        Assertions.assertEquals(
            Arrays.asList("open " + first, "release " + first, "open " + card.deviceKey()),
            cards.host.log()
                .stream()
                .filter(line -> line.startsWith("open") || line.startsWith("release"))
                .collect(Collectors.toList()));
    }

    // Amendments B3: a second own disconnect, and a detach by a card that is not bound, change nothing
    @Test
    void aSecondOwnDisconnectAndAStrayDetachDoNothing() {
        CardRig cards = new CardRig();
        DisplayTile display = Displays.on(cards.nodes);
        CardEnvironment card = boundTo(cards, display);
        CardEnvironment stranger = cards.joined(2);
        display.detach(stranger);
        Assertions.assertSame(card, display.boundCard());
        card.onDisconnect(card.node());
        int lines = cards.host.log()
            .size();
        card.onDisconnect(card.node());
        display.detach(card);
        Assertions.assertEquals(
            lines,
            cards.host.log()
                .size());
    }

    // Q-S11-6; P13.2 step 6, P13.4: a card that joins with a saved address binds the display again only while no other
    // card holds it, since binding again is no bind and the last bind wins; the joining card forgets the address, as
    // the takeover it missed while detached would have made it (M66)
    @Test
    void aCardThatJoinsWithASavedAddressLeavesTheDisplayToTheCardBoundToIt() {
        CardRig cards = new CardRig();
        DisplayTile display = Displays.on(cards.nodes);
        CardEnvironment first = boundTo(cards, display);
        CardEnvironment second = savedWith(cards, display, 2);
        cards.nodes.join(second.node());
        second.onConnect(second.node());
        second.onConnect(second.node());
        Assertions.assertSame(first, display.boundCard());
        Assertions.assertSame(display, first.boundDisplay());
        Assertions.assertEquals(display.displayId(), first.boundAddress());
        Assertions.assertEquals(first.deviceKey(), cards.host.boundDevice(display.displayId()));
        Assertions.assertNull(second.boundDisplay());
        Assertions.assertNull(second.boundAddress());
    }

    // Amendments B2: displayTaken and displayGone act only on the card's own display, and gone keeps the address
    @Test
    void displayTakenAndGoneActOnTheCardsOwnDisplayOnly() {
        CardRig cards = new CardRig();
        DisplayTile display = Displays.on(cards.nodes);
        DisplayTile other = Displays.on(cards.nodes);
        CardEnvironment card = boundTo(cards, display);
        card.displayTaken(other);
        card.displayGone(other);
        Assertions.assertSame(display, card.boundDisplay());
        Assertions.assertEquals(display.displayId(), card.boundAddress());
        card.displayGone(display);
        Assertions.assertNull(card.boundDisplay());
        Assertions.assertEquals(display.displayId(), card.boundAddress());
    }

    // 28:1080: once released, every callback but getCaps throws, which OC hands to Lua as nil, msg
    @Test
    void afterAReleaseTheCallbacksThrowButGetCapsAnswers() throws Exception {
        CardRig cards = new CardRig();
        CardEnvironment card = cards.joined(2);
        card.onDisconnect(card.node());
        RecordingContext context = new RecordingContext();
        IllegalStateException released = Assertions
            .assertThrows(IllegalStateException.class, () -> OcCalls.call(card, "present", context));
        Assertions.assertEquals("device released", released.getMessage());
        Assertions.assertEquals(2, ((Map<?, ?>) OcCalls.call(card, "getCaps", context)[0]).get("tier"));
    }
}
