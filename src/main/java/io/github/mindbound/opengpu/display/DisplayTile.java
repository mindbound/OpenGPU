package io.github.mindbound.opengpu.display;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.common.util.ForgeDirection;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import io.github.mindbound.opengpu.OpenGPU;
import io.github.mindbound.opengpu.card.CardEnvironment;
import io.github.mindbound.opengpu.card.NodeFactory;
import io.github.mindbound.opengpu.core.SurfaceDesc;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.Node;
import li.cil.oc.api.network.SidedEnvironment;
import li.cil.oc.api.network.Visibility;
import li.cil.oc.api.prefab.TileEntityEnvironment;

/**
 * The cube display's tile entity, {@code opengpu:screen} (RM:34; 24:248; 09:52), the display's OC environment (S11
 * plan P12, P13; amendments B2-B4, C1).
 * <p>
 * <b>Shape.</b> Its {@link DisplayShape}, set from the placer when the block is placed ({@link Placement}), saved in
 * NBT beside the prefab's {@code oc:node}, and read back with a missing or out-of-range value as its default and one
 * warning per tile entity (P12.4) [proposal]. Of the binding it saves only its binder (below), where P13.5 had it save
 * nothing (D15), and no device state: the card holds the binding (P13.5), and the host owns the framebuffer.
 * <p>
 * <b>Node.</b> A {@link Visibility#Network} component, {@value #COMPONENT} (09:52; 24:222; Q-S11-2), with no connector
 * before M1's energy [proposal], made by a {@link NodeFactory}: OC's builder, which makes none on a client, as the
 * prefab's own node is null there, or a test's fake (B4). The prefab joins it to OC's network on the first server tick
 * and removes it on unload and invalidate (TileEntityEnvironment.java:99-125). It is offered on the five faces but the
 * front, which decision 11 keeps from keyboards too (24:222; 24:66; 15:97-111).
 * <p>
 * <b>Id and description.</b> The display's id is its node's address, the string Lua passes to {@code bind} and the key
 * the host maps (P12.5) [proposal], as OC's screens send their buffer's address to clients (Screen.scala:300-311). The
 * description packet, vanilla's tile-entity sync, carries the id once there is one, the shape, and the bound card's
 * surface size, 0 by 0 while unbound (09:102 without tier and {@code hasKeyboard}, 24:66; S11 plan U5.7), and a
 * client's tile entity reads it in {@link #onDataPacket}: S12's GUI maps the cursor to pixels with that size on every
 * client, while the server derives the size again for every packet and never takes the client's. When its node joins
 * under an id no description has carried yet, the display has the description sent again (P12.5; B2): after a
 * placement, whose description went out before the node had an address; after a re-address (Network.scala:226-229,
 * :276-300); and after a load whose first join comes before any description, which costs one more S35 [proposal]. It
 * is sent again on every attach and detach too, for the size (U5.7).
 * <p>
 * <b>Binding</b> (P13; B2, B3; Q-S11-6). A card binds the display through {@link #attach} and {@link #detach}, which
 * map and unmap it on that card's host under the id it had when it was mapped. One card shows on it at a time, and the
 * last card to bind takes it. Unloaded or broken, the display unmaps itself and tells its card through
 * {@code displayGone} before the prefab removes its node, and the card keeps its address to bind it again when it
 * reloads; the device lives on with the card (P12.6; 28:153; 21:108; 12:145).
 * <p>
 * <b>Binder</b> (D15; Q-S11-16). The display remembers the node address of the card it was last attached to, its
 * binder, so that the last bind wins across a full restart too: every attach sets it, an explicit {@code bind} and a
 * binding again alike, and detach, {@code displayGone}, a cut cable and an unload keep it. It is server state only:
 * saved as the String {@value #BINDER} when set, read back with an empty, missing or non-String value as none
 * [proposal: the type check], and never in the description. A card that binds again without a context, at its own
 * connect or the display's, takes the display only while its binder is none or that card (CardEnvironment's
 * {@code rebindTo}); any other card forgets the address. So after a restart only the last binder binds it again by
 * itself, a display whose binder never returns stays free until a Lua {@code bind}, and a display saved before D15,
 * with no binder, goes to the first card to connect, which it then records. A change of binder marks the display's
 * chunk changed, as OC's environments mark theirs (Environment.scala:28, :44), so a save keeps it
 * (ChunkProviderServer.java:338-340; Chunk.java:1111-1125) [proposal]. One window stays open: an unloading chunk is
 * saved at once, and the display's own {@code onChunkUnload} runs later in that tick, after the tile entities' updates
 * (ChunkProviderServer.java:387-389; Chunk.java:1015-1024; World.java:2196-2203). So a Lua {@code bind} in that tick,
 * from a case whose chunk stays, still attaches the display, whose node is in the network until then, and records a
 * binder that no save keeps, since the chunk is gone (World.java:3590-3596). After the reload that bind's card finds
 * the older binder and forgets the address, and the display goes to the older binder's card only if that card kept the
 * address, else it stays free until a Lua {@code bind}.
 * <p>
 * <b>Input</b> (24:140, :222; 15:184-187; S11 plan U5.5, U5.6). The display takes input only through the session's
 * {@code InputHandler}, on the server thread, which finds it by its block and id, ranges the pointer against
 * {@link #boundSurface()}, and has it signal {@code computer.checked_signal} from its node ({@link #sendInput}). Its
 * component's callbacks in M0 are exactly {@code isPrecise} and {@code setPrecise}, OC's pair
 * (TextBuffer.scala:208-221), on every display, since M0's display has no tier (Q-S11-3; 15:187): in precise mode the
 * signals carry fractional pixel coordinates. The flag is the display's own state, saved as
 * {@value #PRECISE} beside the shape, as OC saves {@code oc:precise} (TextBuffer.scala:460, :496) [proposal], and not
 * in the description: a client always sends fractions, and the server converts.
 * <p>
 * <b>The client's half</b> (S12 plan P8.7, P8.8 with amendment I7). {@link #getRenderBoundingBox} gives one box per
 * tile entity from its first call, the extent of M1.5's largest default wall (R-9); the render distance stays
 * vanilla's 64 blocks, a per-class constant as Celeritas needs (03:102). A description that turns the display to
 * another facing or rotation has the client rebuild the block's chunk mesh, since the chassis's front icon follows the
 * facing. An ISBRH on a meshing thread reads the shape through Celeritas' world slice, so the shape is volatile; it is
 * written on the server thread and on the client thread only.
 * <p>
 * <b>What it never does</b> (P12.7; S11 plan L1, L4). It names no client type outside {@code @SideOnly(CLIENT)} members
 * (24:90): {@link #canConnect}, {@link #onDataPacket} and {@link #getRenderBoundingBox}. It holds no framebuffer,
 * texture or device, renders nothing, reads no pixels, and reaches the host only through {@code bindDisplay}. It sends
 * no packet of its own: its description is vanilla's sync. It calls no {@code getEffectiveSide()}, saves no device
 * state, and of the binding saves only its binder (D15), which it never describes. It signals only
 * {@code computer.checked_signal}, for accepted input. Everything but the client's three members and
 * {@code isPrecise}, which OC's workers call, runs on the server thread; on a client, Celeritas' meshing threads also
 * read the shape and ask for the render box.
 */
public class DisplayTile extends TileEntityEnvironment implements SidedEnvironment {

    /** The display component's name (09:52; Q-S11-2). */
    public static final String COMPONENT = "opengpu_screen";

    /** The description's key for the display's id [proposal]. */
    static final String DISPLAY_ID = "opengpu:displayId";
    /** The description's keys for the bound surface's size, shorts, 0 while unbound (S11 plan U5.7). */
    static final String WIDTH = "opengpu:width";
    static final String HEIGHT = "opengpu:height";
    /** The NBT key of precise mode, a boolean, false when missing (S11 plan U5.6) [proposal]. */
    static final String PRECISE = "opengpu:precise";
    /** The NBT key of the binder, a String, written only when there is one, never described (D15; Q-S11-16). */
    static final String BINDER = "opengpu:binder";

    /**
     * How far the render box reaches past the block on every side, in blocks: M1.5's largest default wall is 8 × 6
     * (RM:108), so any of its blocks lies at most 7 from the far edge, whichever way it faces (S12 plan P8.7;
     * Q-S12-6 (a)). M1.5 sizes it from its configured maximum.
     */
    static final int RENDER_REACH = 7;

    /** The message a display's node sends for accepted input (24:222; Machine.scala:645-656). */
    private static final String CHECKED_SIGNAL = "computer.checked_signal";
    /** NBT's tag type of a string. */
    private static final int STRING = 8;

    /**
     * Written on the server thread and on the client thread; volatile, since an ISBRH on a Celeritas meshing thread
     * reads it through the world slice (WorldSlice.java:382-391; S12 plan P8.8).
     */
    private volatile DisplayShape shape = DisplayShape.DEFAULT;
    /**
     * On a client: the render box, made once under {@link #renderBoxLock} and then never changed (S12 amendments I7).
     */
    private volatile AxisAlignedBB renderBox;
    private final Object renderBoxLock = new Object();
    /** Warnings logged for unreadable shape values: at most one per tile entity. */
    private int shapeWarnings;
    /** Precise mode: set on the server thread by setPrecise, read there by input and on OC's workers by isPrecise. */
    private volatile boolean precise;
    /** The card bound to it, and the id the card's host maps it under; on the server thread only (P13.1). */
    private CardEnvironment boundCard;
    private String mappedId;
    /**
     * The binder: the node address of the card last attached, or null for none (D15). On the server thread only; set by
     * attach alone, so it outlives the attachment, and saved.
     */
    private String binder;
    /** On the server: the id the next or last description carries, so a join under another id sends it again. */
    private String describedId;
    /** On a client, which has no node: the id and the surface size the last description carried. */
    private String receivedId;
    private int receivedWidth;
    private int receivedHeight;

    /** The tile entity Minecraft makes, through its no-arg constructor, with OC's node builder. */
    public DisplayTile() {
        this(NodeFactory.OC);
    }

    /** A display whose node comes from {@code nodes}, for tests (S11 amendments B4). */
    DisplayTile(NodeFactory nodes) {
        node = nodes.component(this, Visibility.Network, COMPONENT);
    }

    /**
     * The display's id: its node's address, null before the node joins a network; on a client, the id of the last
     * description, or null.
     */
    public String displayId() {
        return node != null ? node.address() : receivedId;
    }

    /** The display's shape. */
    public DisplayShape shape() {
        return shape;
    }

    /** The side the picture faces, one of the six valid directions. */
    public ForgeDirection facing() {
        return shape.facing();
    }

    /**
     * Takes the shape a placer gives it (P12.1), on both sides, right after the block is placed. Placing the block
     * marked its chunk changed (Chunk.java:727), so the shape is saved with it, and the server's reaches clients in the
     * description that goes out with the placed block (PlayerManager.java:572-574).
     *
     * @throws NullPointerException for a null shape
     */
    public void place(DisplayShape placed) {
        shape = Objects.requireNonNull(placed, "placed");
    }

    /** The card bound to the display, or null. */
    public CardEnvironment boundCard() {
        return boundCard;
    }

    /**
     * The binder: the node address of the card the display was last attached to, kept while it is unbound, unloaded
     * and saved; null for none (D15). On the server thread.
     */
    public String binder() {
        return binder;
    }

    /**
     * The bound card's surface, which input is ranged against (S11 plan U5.5): null while no card is bound, or while
     * the display's node is in no network, where it could signal no machine. On the server thread.
     */
    public SurfaceDesc boundSurface() {
        CardEnvironment card = boundCard;
        if (card == null || node == null || node.network() == null) {
            return null;
        }
        return card.surface();
    }

    /**
     * The width of the picture in pixels: on a server, the bound card's surface's, 0 while unbound; on a client, the
     * last description's (S11 plan U5.7).
     */
    public int surfaceWidth() {
        if (node == null) {
            return receivedWidth;
        }
        return boundCard != null ? boundCard.surface().width : 0;
    }

    /** The height of the picture in pixels, as {@link #surfaceWidth()}. */
    public int surfaceHeight() {
        if (node == null) {
            return receivedHeight;
        }
        return boundCard != null ? boundCard.surface().height : 0;
    }

    /** Whether input signals carry fractional pixel coordinates (S11 plan U5.5, U5.6). Any thread. */
    public boolean isPrecise() {
        return precise;
    }

    /**
     * Sends {@code computer.checked_signal} with {@code args} from the display's node to every machine that can reach
     * it, as OC's screens send their input (TextBuffer.scala:890-910): {@code (player, name, x, y, data[, playerName])}
     * from the session's {@code InputHandler}, which machines the player may use turn into the signal {@code name} with
     * the display's address in front (Machine.scala:645-656). A node in no network sends nothing (Node.scala:102-103).
     * On the server thread.
     */
    public void sendInput(Object... args) {
        if (node != null) {
            node.sendToReachable(CHECKED_SIGNAL, args);
        }
    }

    /**
     * Binds the display to {@code card}, from the card's {@code bind} or its binding again, which takes no display that
     * another card holds or that another card bound last (P13.2 step 6; P13.4; D15): a card bound before loses it
     * through {@link CardEnvironment#displayTaken}, the card's node address becomes the binder, and the card's host
     * maps the display's id to the card's device. The description goes out again with the card's surface size (U5.7).
     * Nothing when {@code card} is bound already.
     *
     * @throws NullPointerException for a null card
     */
    public void attach(CardEnvironment card) {
        Objects.requireNonNull(card, "card");
        CardEnvironment previous = boundCard;
        if (previous == card) {
            return;
        }
        boundCard = card;
        mappedId = displayId();
        Node cardNode = card.node();
        String address = cardNode != null ? cardNode.address() : null;
        if (!Objects.equals(binder, address)) {
            binder = address;
            markChunkChanged();
        }
        if (previous != null) {
            previous.displayTaken(this);
        }
        card.renderHost()
            .bindDisplay(mappedId, card.deviceKey());
        describeAgain();
    }

    /**
     * Unbinds the display from {@code card}, whose host unmaps it (P13.3; RenderHost.java:45-46), and sends the
     * description again, with a size of 0 by 0 (U5.7). Nothing unless {@code card} is the one bound (S11 amendments
     * B3).
     */
    public void detach(CardEnvironment card) {
        if (card == null || boundCard != card) {
            return;
        }
        unmap();
        describeAgain();
    }

    // ---- The component's callbacks (S11 plan U5.6; OC's pair, TextBuffer.scala:208-221), with U5.6's doc texts
    // [proposal]

    /** {@code isPrecise()}: direct, since it reads only the volatile flag. */
    @Callback(
        direct = true,
        doc = "function():boolean -- Whether touch, drag, drop and scroll carry fractional pixel coordinates.")
    public Object[] isPrecise(Context context, Arguments args) {
        return new Object[] { precise };
    }

    /**
     * {@code setPrecise(enabled)}: not direct, since a change marks the tile entity dirty, which touches the world
     * (P7.5's rule), and only a change does [proposal]; on every display, with no tier gate (15:187; Q-S11-3). Returns
     * the old setting.
     */
    @Callback(
        direct = false,
        doc = "function(enabled:boolean):boolean -- Sets whether touch, drag, drop and scroll carry fractional pixel "
            + "coordinates; returns the old setting.")
    public Object[] setPrecise(Context context, Arguments args) {
        boolean enabled = args.checkBoolean(0);
        boolean old = precise;
        precise = enabled;
        if (old != enabled) {
            markDirty();
        }
        return new Object[] { old };
    }

    // ---- OC's network, on the server thread

    /** The node on the five faces but the front, none on the front (24:222; 15:97-111). */
    @Override
    public Node sidedNode(ForgeDirection side) {
        return side == shape.facing() ? null : node;
    }

    /**
     * Whether a cable connects on {@code side}, for its rendering: the faces {@link #sidedNode} offers (15:109-111).
     */
    @SideOnly(Side.CLIENT)
    @Override
    public boolean canConnect(ForgeDirection side) {
        return side != shape.facing();
    }

    /** The display's own node joined: under a new id, the description is sent again (P12.5). */
    @Override
    public void onConnect(Node node) {
        super.onConnect(node);
        if (node == null || node != this.node) {
            return;
        }
        String id = node.address();
        if (id != null && !id.equals(describedId)) {
            // OC connects a joining node's own environment twice (Network.scala:31-35, :250-260): ask once
            describedId = id;
            describeAgain();
        }
    }

    /** P12.6: unbound before the prefab removes the node; the card keeps the address. */
    @Override
    public void onChunkUnload() {
        gone();
        super.onChunkUnload();
    }

    /** P12.6: unbound before the prefab removes the node; the card keeps the address. */
    @Override
    public void invalidate() {
        gone();
        super.invalidate();
    }

    // ---- NBT and the description

    /** The prefab's node, the shape, precise mode and the binder, which a save from before D15 lacks: none. */
    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        shape = readShape(nbt);
        precise = nbt.getBoolean(PRECISE);
        String saved = nbt.hasKey(BINDER, STRING) ? nbt.getString(BINDER) : "";
        binder = saved.isEmpty() ? null : saved;
    }

    /** The prefab's node, the shape, precise mode and the binder when there is one (D15). */
    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        shape.write(nbt);
        nbt.setBoolean(PRECISE, precise);
        if (binder != null) {
            nbt.setString(BINDER, binder);
        }
    }

    /**
     * The id, once there is one, the shape and the bound surface's size, in an S35 of action 0, which clients hand to
     * onDataPacket; never precise mode or the binder, which are the server's (D15).
     */
    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound tag = new NBTTagCompound();
        String id = displayId();
        if (id != null) {
            tag.setString(DISPLAY_ID, id);
        }
        shape.write(tag);
        tag.setShort(WIDTH, (short) surfaceWidth());
        tag.setShort(HEIGHT, (short) surfaceHeight());
        describedId = id;
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, tag);
    }

    /**
     * A client reads the server's description; its getter is client-only (S35PacketUpdateTileEntity.java:96-97). When
     * the facing or the rotation changed and the tile entity is in a world, the world marks the block for a render
     * update, so the chunk mesh, whose chassis shows its front icon on the facing, is built again
     * ({@code func_147479_m}, World.java:1116-1122; S12 plan P8.8) [proposal]. Thickness and tilt cannot change in M0
     * (RM:110-111).
     */
    @SideOnly(Side.CLIENT)
    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity packet) {
        NBTTagCompound tag = packet.func_148857_g();
        receivedId = tag.hasKey(DISPLAY_ID, STRING) ? tag.getString(DISPLAY_ID) : null;
        DisplayShape old = shape;
        shape = readShape(tag);
        receivedWidth = tag.getShort(WIDTH);
        receivedHeight = tag.getShort(HEIGHT);
        boolean turned = old.facing() != shape.facing() || old.rotation() != shape.rotation();
        if (turned && worldObj != null) {
            worldObj.func_147479_m(xCoord, yCoord, zCoord);
        }
    }

    /**
     * The render box (25 R-9, 25:461-467; S12 plan P8.7 with amendment I7): one box per tile entity, made on the first
     * call and the same instance on every later one, so Angelica's per-class classification and per-instance cache
     * never see it change (TileEntityRenderBoundsRegistry.java:37-54). It is the block grown by
     * {@value #RENDER_REACH} on every side, from {@code (x - 7, y - 7, z - 7)} to {@code (x + 8, y + 8, z + 8)}: the
     * extent of M1.5's largest default wall around any of its blocks, so the rule has its final form in M0 [proposal;
     * Q-S12-6 (a)]. Celeritas' meshing threads ask for it as well as the client thread
     * (AngelicaChunkBuilderMeshingTask.java:166-182), so it is made once under a private lock and published through a
     * volatile field. A box 15 blocks wide passes its 16-block chunk section unless the block sits at section-local 7
     * or 8 on all three axes, by Celeritas' strict test (AngelicaChunkBuilderMeshingTask.java:181-183), so Celeritas
     * lists nearly every display as a global block entity, and the 8 others stay in their section, whose bounds hold
     * the whole box: harmless either way (S12 amendments I7, as S12's review spec-1 corrects it). The box is the tile
     * entity's own: callers only read it, as vanilla's frustum test does (Frustrum.java:34-37).
     */
    @SideOnly(Side.CLIENT)
    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        AxisAlignedBB box = renderBox;
        if (box == null) {
            synchronized (renderBoxLock) {
                box = renderBox;
                if (box == null) {
                    box = AxisAlignedBB.getBoundingBox(
                        xCoord - RENDER_REACH,
                        yCoord - RENDER_REACH,
                        zCoord - RENDER_REACH,
                        xCoord + 1 + RENDER_REACH,
                        yCoord + 1 + RENDER_REACH,
                        zCoord + 1 + RENDER_REACH);
                    renderBox = box;
                }
            }
        }
        return box;
    }

    // ---- What tests read

    /** Warnings logged for unreadable shape values, 0 or 1. */
    int shapeWarnings() {
        return shapeWarnings;
    }

    /**
     * For example "DisplayTile[opengpu_screen-2 at 1 64 2, DisplayShape[...], card CardEnvironment[...], binder
     * opengpu-1]".
     */
    @Override
    public String toString() {
        return "DisplayTile[" + displayId()
            + " at "
            + xCoord
            + " "
            + yCoord
            + " "
            + zCoord
            + ", "
            + shape
            + ", card "
            + boundCard
            + ", binder "
            + binder
            + "]";
    }

    // ---- Internals

    private DisplayShape readShape(NBTTagCompound nbt) {
        List<String> problems = new ArrayList<>();
        DisplayShape read = DisplayShape.read(nbt, problems);
        if (!problems.isEmpty() && shapeWarnings == 0) {
            shapeWarnings++;
            OpenGPU.LOG.warn(
                "Display at {} {} {}: {} read as defaults; later problems with its shape are not logged",
                xCoord,
                yCoord,
                zCoord,
                problems);
        }
        return read;
    }

    /** Unloaded or broken: unmapped, and the card told, which keeps the address (P12.6). */
    private void gone() {
        CardEnvironment card = boundCard;
        if (card != null) {
            unmap();
            card.displayGone(this);
        }
    }

    private void unmap() {
        CardEnvironment card = boundCard;
        String id = mappedId;
        boundCard = null;
        mappedId = null;
        card.renderHost()
            .bindDisplay(id, null);
    }

    /** Has the description sent again, on a server's world only (P12.5; U5.7). */
    private void describeAgain() {
        if (worldObj != null && !worldObj.isRemote) {
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    /**
     * Marks the chunk changed, so the next save writes the binder (World.java:3590-3596), on a server's world only, as
     * OC's environments mark theirs (Environment.scala:28, :44) [proposal]. Not {@link #markDirty()}, which also reads
     * the block and tells comparators (TileEntity.java:167-179), which a binder change does not concern.
     */
    private void markChunkChanged() {
        if (worldObj != null && !worldObj.isRemote) {
            worldObj.markTileEntityChunkModified(xCoord, yCoord, zCoord, this);
        }
    }
}
