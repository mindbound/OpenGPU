package io.github.mindbound.opengpu.display;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.card.CardEnvironment;
import io.github.mindbound.opengpu.card.Cards;
import io.github.mindbound.opengpu.fixtures.BareWorld;
import io.github.mindbound.opengpu.fixtures.FakeNodes;

/**
 * The display's description (09:102 without tier and {@code hasKeyboard}; S11 plan P12.5, P15.3, U5.7; amendments
 * B2): an S35 of action 0 whose tag holds the display's id, once it has one, its shape and the bound card's surface
 * size, which a client's tile entity, with no node, reads back, and never the binder, which is the server's (D15); and
 * the description sent again when the node joins under an id the last one did not carry, and on every attach and
 * detach. The S35's getters are client-only in game and plain methods on this classpath.
 */
@Timeout(60)
class DescriptionPacketTest {

    /**
     * The tag's keys as written, as P12.4, P12.5 and U5.7 name them, not the constants, which a rename would carry
     * along: the id, the five shape keys and the size, and never {@code opengpu:binder} (D15).
     */
    private static final Set<String> DESCRIBED = new TreeSet<>(
        Arrays.asList(
            "opengpu:displayId",
            "opengpu:facing",
            "opengpu:rotation",
            "opengpu:thickness",
            "opengpu:tiltH",
            "opengpu:tiltV",
            "opengpu:width",
            "opengpu:height"));

    private static DisplayTile at(DisplayTile display, int x, int y, int z) {
        display.xCoord = x;
        display.yCoord = y;
        display.zCoord = z;
        return display;
    }

    // P12.5: the id, the five shape keys and U5.7's size, nothing else; a client's tile entity reads them back
    @Test
    void theTagCarriesTheIdAndTheShapeAndAClientReadsThem() {
        FakeNodes nodes = new FakeNodes();
        DisplayTile server = at(Displays.on(nodes), 7, 70, -3);
        nodes.join(server.node());
        DisplayShape shape = DisplayShape.placed(ForgeDirection.UP, 3);
        server.place(shape);
        S35PacketUpdateTileEntity packet = (S35PacketUpdateTileEntity) server.getDescriptionPacket();
        Assertions.assertEquals(
            Arrays.asList(7, 70, -3, 0),
            Arrays.asList(
                packet.func_148856_c(),
                packet.func_148855_d(),
                packet.func_148854_e(),
                packet.func_148853_f()));
        NBTTagCompound tag = packet.func_148857_g();
        Assertions.assertEquals(DESCRIBED, keys(packet));
        Assertions.assertEquals(server.displayId(), tag.getString(DisplayTile.DISPLAY_ID));
        DisplayTile client = Displays.withoutNode();
        client.onDataPacket(null, packet);
        Assertions.assertEquals(server.displayId(), client.displayId());
        Assertions.assertEquals(shape, client.shape());
        Assertions.assertEquals(0, client.shapeWarnings());
    }

    // P12.5: before the node has an address the tag has no id, and a client then has none
    @Test
    void withoutAnIdTheTagCarriesTheShapeOnly() {
        DisplayTile server = Displays.withoutNode();
        server.place(DisplayShape.placed(ForgeDirection.WEST, 0));
        S35PacketUpdateTileEntity packet = (S35PacketUpdateTileEntity) server.getDescriptionPacket();
        Assertions.assertFalse(
            packet.func_148857_g()
                .hasKey(DisplayTile.DISPLAY_ID));
        DisplayTile client = Displays.withoutNode();
        client.onDataPacket(null, packet);
        Assertions.assertNull(client.displayId());
        Assertions.assertEquals(ForgeDirection.WEST, client.facing());
    }

    // P12.5; B2: the first join after a placement, and a join under a new address, send the description again, once
    @Test
    void aJoinUnderANewIdSendsTheDescriptionAgainOnce() {
        FakeNodes nodes = new FakeNodes();
        BareWorld world = BareWorld.allocate(false);
        DisplayTile display = at(Displays.on(nodes), 1, 64, 2);
        world.put(display);
        nodes.join(display.node());
        display.onConnect(display.node());
        display.onConnect(display.node());
        Assertions.assertEquals(Collections.singletonList("1 64 2"), world.updates());
        String first = display.displayId();
        Assertions.assertEquals(
            first,
            ((S35PacketUpdateTileEntity) display.getDescriptionPacket()).func_148857_g()
                .getString(DisplayTile.DISPLAY_ID));
        // Another node's join, and the display's own under its id, ask for nothing
        display.onConnect(nodes.machine());
        nodes.leave(display.node());
        nodes.join(display.node());
        display.onConnect(display.node());
        Assertions.assertEquals(
            1,
            world.updates()
                .size());
        // OC re-addresses a node that joins where its address is taken (Network.scala:226-229, :276-300)
        nodes.leave(display.node());
        nodes.readdress(display.node(), "opengpu_screen-new");
        nodes.join(display.node());
        display.onConnect(display.node());
        display.onConnect(display.node());
        Assertions.assertEquals(Arrays.asList("1 64 2", "1 64 2"), world.updates());
        Assertions.assertEquals(
            "opengpu_screen-new",
            ((S35PacketUpdateTileEntity) display.getDescriptionPacket()).func_148857_g()
                .getString(DisplayTile.DISPLAY_ID));
        // A client never asks: its world is remote, and its tile entity has no node
        BareWorld remote = BareWorld.allocate(true);
        DisplayTile client = at(Displays.on(new FakeNodes()), 1, 64, 2);
        remote.put(client);
        client.onConnect(client.node());
        Assertions.assertEquals(Collections.emptyList(), remote.updates());
    }

    /** A display at (4, 70, 9) in a bare server world, its node in the cards' network. */
    private static DisplayTile placed(Cards cards, BareWorld world) {
        DisplayTile display = at(Displays.on(cards.nodes()), 4, 70, 9);
        world.put(display);
        cards.nodes()
            .join(display.node());
        return display;
    }

    /** The description's size as [width, height]; each must be a short tag (NBT type 2). */
    private static List<Integer> size(S35PacketUpdateTileEntity packet) {
        NBTTagCompound tag = packet.func_148857_g();
        Assertions.assertTrue(tag.hasKey(DisplayTile.WIDTH, 2), tag.toString());
        Assertions.assertTrue(tag.hasKey(DisplayTile.HEIGHT, 2), tag.toString());
        return Arrays.asList((int) tag.getShort(DisplayTile.WIDTH), (int) tag.getShort(DisplayTile.HEIGHT));
    }

    /** The size a client's tile entity, with no node, reads from the packet. */
    private static List<Integer> read(S35PacketUpdateTileEntity packet) {
        DisplayTile client = Displays.withoutNode();
        client.onDataPacket(null, packet);
        return Arrays.asList(client.surfaceWidth(), client.surfaceHeight());
    }

    private static S35PacketUpdateTileEntity describe(DisplayTile display) {
        return (S35PacketUpdateTileEntity) display.getDescriptionPacket();
    }

    private static Set<String> keys(S35PacketUpdateTileEntity packet) {
        return new TreeSet<>(
            packet.func_148857_g()
                .func_150296_c());
    }

    // U5.7: a bound card's surface size goes out as two shorts, sent again on the attach, and a client reads it; D15:
    // the attach made the card the binder, which the tag does not carry
    @Test
    void aBoundT3CardGives640By400() throws Exception {
        Cards cards = new Cards();
        BareWorld world = BareWorld.allocate(false);
        DisplayTile display = placed(cards, world);
        Cards.bind(cards.joined(3), display);
        Assertions.assertEquals(Collections.singletonList("4 70 9"), world.updates(), "the attach sends it again");
        Assertions.assertEquals(Arrays.asList(640, 400), size(describe(display)));
        Assertions.assertEquals(Arrays.asList(640, 400), read(describe(display)));
        Assertions.assertNotNull(display.binder());
        Assertions.assertEquals(DESCRIBED, keys(describe(display)), "no opengpu:binder");
    }

    // U5.7: unbound, and after a detach, the size is 0 by 0, and the detach sends the description again; D15: the
    // detached display keeps its binder, which the tag does not carry
    @Test
    void unboundAndAfterADetachTheSizeIs0By0() throws Exception {
        Cards cards = new Cards();
        BareWorld world = BareWorld.allocate(false);
        DisplayTile display = placed(cards, world);
        Assertions.assertEquals(Arrays.asList(0, 0), size(describe(display)), "never bound");
        CardEnvironment card = cards.joined(2);
        Cards.bind(card, display);
        Assertions.assertEquals(Arrays.asList(320, 200), size(describe(display)));
        cards.release(card);
        Assertions.assertNull(display.boundCard());
        Assertions.assertEquals(Arrays.asList("4 70 9", "4 70 9"), world.updates(), "the attach, then the detach");
        Assertions.assertEquals(Arrays.asList(0, 0), size(describe(display)), "after the detach");
        Assertions.assertEquals(Arrays.asList(0, 0), read(describe(display)));
        Assertions.assertEquals(
            card.node()
                .address(),
            display.binder());
        Assertions.assertEquals(DESCRIBED, keys(describe(display)), "no opengpu:binder");
    }
}
