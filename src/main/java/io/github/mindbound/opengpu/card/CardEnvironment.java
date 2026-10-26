package io.github.mindbound.opengpu.card;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import net.minecraft.nbt.NBTTagCompound;

import io.github.mindbound.opengpu.OpenGPU;
import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.device.CallBudget;
import io.github.mindbound.opengpu.core.device.CallResult;
import io.github.mindbound.opengpu.core.device.Device;
import io.github.mindbound.opengpu.core.device.DeviceCaps;
import io.github.mindbound.opengpu.core.device.FrameListener;
import io.github.mindbound.opengpu.core.device.LimitReached;
import io.github.mindbound.opengpu.core.device.Statuses;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.RenderHost;
import io.github.mindbound.opengpu.display.DisplayTile;
import io.github.mindbound.opengpu.render.Installation;
import io.github.mindbound.opengpu.server.TickQueue;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Callback;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.machine.LimitReachedException;
import li.cil.oc.api.network.Message;
import li.cil.oc.api.network.Node;
import li.cil.oc.api.network.Visibility;
import li.cil.oc.api.prefab.ManagedEnvironment;

/**
 * The card's OC environment, component {@code opengpu} (24:29; 28 §2.1; S11 plan P7-P11): the M0 callbacks over
 * :core's {@link Device}, their charges through OC's {@link Context}, and the {@code opengpu_frame} signal. The
 * harness's OpenGpuEntity is its twin on ocelot's API (28:1123-1124).
 * <p>
 * <b>Callbacks</b> (28:93-102; P8). Each sets the context first, reads its arguments only through OC's
 * {@code checkInteger}, {@code checkByteArray} and {@code optString} (28:84), calls the device, and returns
 * {@code CallResult.toLua()}. All are direct but {@code bind}, which reads OC's network (28:84). An argument error
 * stays OC's {@code IllegalArgumentException}, which Lua sees as {@code error(message, 0)} (28:291). The charged five
 * go through {@link ContextBudget}, and :core's {@code LimitReached}, the two-phase pattern's first attempt, becomes
 * OC's {@code LimitReachedException} (28:1077); OC's own, from {@code consumeCallBudget}, passes out untouched, since
 * no callback catches around a call (S11 spike P1). After a release Device throws
 * {@code IllegalStateException("device released")}, which OC hands to Lua as {@code nil, msg}; no connected card meets
 * it [I].
 * <p>
 * <b>Threads</b> (P7.5; 00:81; 12:145). Direct callbacks run on OC's worker threads, where they touch neither the node
 * network, the world nor the server session. A device delivers its statuses on a fold's thread or on the server
 * thread, and the card only hands them to the server thread (D16). Everything else runs on the server thread:
 * {@link #update()}, the messages, the signals, the connects and disconnects, NBT, and the binding, whose two
 * references are plain fields for that reason (P13.1; S11 plan L1).
 * <p>
 * <b>Lifecycle</b> (28:150-151; 21:106-108; P7.4; amendments B2, B3; OC's GPU, GraphicsCard.scala:538-606). The
 * handlers are idempotent, since OC connects a node's own environment twice when it joins (Network.scala:31-35,
 * :250-260), and a card saved before its case's node has a network joins a temporary one and leaves it again
 * (ManagedEnvironment.java:49-69):
 * <ul>
 * <li>{@code computer.started} or {@code computer.stopped} from a neighbouring node clears the context, raises the
 * floor and resets the device; another node's is ignored (12:138; 28:1123);</li>
 * <li>the card's own disconnect (card removed or moved, case broken, chunk unloaded) clears the context, detaches the
 * display, keeping its address, and releases the device; a released device's does nothing more;</li>
 * <li>the card's own connect caches the node's address (12:145), replaces a released device with a fresh one under a
 * new id (28:151, :1080), and binds the saved display again, as below;</li>
 * <li>the bound display's disconnect, such as a cut cable, which OC reports to the card alone (Network.scala:349-373),
 * detaches it and keeps its address; its connect binds it again, as below, the way OC's GPU finds its screen again
 * (GraphicsCard.scala:589-598).</li>
 * </ul>
 * One card drives at most one display and the last bind takes it (Q-S11-6), across a full restart too (Q-S11-16): the
 * display remembers its binder, the node address of the card it was last attached to, and saves it (D15). An explicit
 * {@code bind} always takes the display and makes this card its binder. Binding again, which has no context, takes the
 * display only while no other card holds it and its binder is none or this card's address (D13, D15). Otherwise
 * another card bound it while this one was detached (its case unloaded, the card pulled or the cable cut) or before a
 * restart, so this card forgets the display's address, as a takeover makes a bound card forget it (P13.2 step 6). So
 * after a restart only the last binder binds the display again by itself, whichever card connects first; a display
 * whose binder never returns stays free until a Lua {@code bind}; and a display saved before D15, with no binder, goes
 * to the first card to connect, which it then records. A known limit (D15): a card that OC re-addresses, as it does a
 * node whose address its network holds already (Network.scala:226-229, :276-300), no longer matches its binder entry
 * and forgets the display, which Lua then binds again. The card saves only the display's address, under
 * {@code opengpu:display} in its {@code oc:data}, which travels in the case's description packet, and no device state
 * (24:179; 28:151).
 * <p>
 * <b>Signals</b> (24:127; 09:65; P11). {@code opengpu_frame(card, frameId, status)} goes to the context of the last
 * callback with the cached address as a String, the frame id as a Long and the status as a String, since an Integer
 * reads back as nil after a save (Machine.scala:858-884). Every status is signalled on the server thread, at the start
 * of a server tick (Q-S11-17; D16): the device's listener only hands it, with the frame id and the listener it came to,
 * to the session's {@link TickQueue}, which keeps the device's order (FrameListener.java:3-9), and the queue's drain
 * counts it once, in this order: stale (presented before the last reset, or from a device since replaced), unsent (no
 * context since the last start, stop or disconnect, or the session stopped before the drain), refused (the machine's
 * queue is full, or it is stopping), or sent [proposal, after 12:127-133]. So these checks and {@code Context.signal}
 * run in turn with {@code computer.started} and {@code computer.stopped}, which OC sends to the card on the server
 * thread on every path (Machine.scala:240, :581, :935; D16), and a status of the old run cannot reach the new one
 * across a reboot. OC clears the old run's queue, sends {@code computer.stopped}, then starts the new run, all in one
 * machine update (Machine.scala:575-582, :208-240): a drain before it signals into the queue that is then cleared, and
 * a drain after it finds the floor raised. The cost is latency: a status reaches the machine's queue only at a tick's
 * start, so Lua sees it no sooner. A sleeping machine resumes only in its update, on the server thread, once its queue
 * holds a signal (Machine.scala:584), and a case updates its machine before its components (Computer.scala:112, :122,
 * :128-130). So for a sleeping machine a {@code "done"} whose fold ended between two ticks reaches Lua on the same
 * tick as before D16, one whose fold ended during a tick, before that case's machine update, one tick (50 ms) later,
 * and a {@code "dropped"} from {@link #update()}, which runs after the machine's update, on the next tick, as before.
 * A machine whose worker is running, or has yielded and waits to resume, took a status before D16 at the worker's next
 * resume, about OC's {@code executionDelay} (12 ms) after it yields, with no tick between (Machine.scala:966-968,
 * :1007-1017; application.conf:207). Now any status waits for the next tick's start, {@code "done"} and
 * {@code "dropped"} alike, up to one tick (50 ms, more on a lagging server) later than before. A refused signal is not
 * retried, and nothing coalesces beyond Device's one {@code "done"} per fold: a departure from 09:65 and 12:145,
 * supported by 21:91 (Q-S11-9; amendments G2).
 */
public final class CardEnvironment extends ManagedEnvironment {

    /** The component's name (24:29; Q-S11-2). */
    public static final String COMPONENT = "opengpu";
    /** The frame-status signal (24:127). */
    public static final String SIGNAL = "opengpu_frame";
    /** The card's {@code oc:data} key for the bound display's address (Q-S11-6; 09:102) [proposal]. */
    static final String DISPLAY = "opengpu:display";

    private static final String STARTED = "computer.started";
    private static final String STOPPED = "computer.stopped";

    private final int tier;
    private final CardDeps deps;
    /** The context of the last callback (09:65; 12:119), which the statuses reach the machine through (D16). */
    private final AtomicReference<Context> context = new AtomicReference<>();
    /** The current device with its listener, replaced together after a release, so a callback reads one pair. */
    private volatile Life life;
    /** The node's address, cached on the server thread at the card's connect, for the signal (12:145; 00:81). */
    private volatile String address;
    // The binding, on the server thread only (P13.1)
    private DisplayTile boundDisplay;
    private String boundAddress;

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();
    private final AtomicLong unsent = new AtomicLong();
    private final AtomicLong stale = new AtomicLong();
    private final AtomicBoolean signalFailureLogged = new AtomicBoolean();

    /**
     * A card of OpenGPU's {@code tier}, 2 or 3, on the server thread inside {@code createEnvironment}: its node from
     * {@code deps.nodes}, and its first device, which captures the session's host and pool once (21:63; 24:171;
     * 28:199).
     *
     * @throws IllegalArgumentException for a tier other than 2 and 3 (28:37)
     * @throws NullPointerException     for null deps
     */
    CardEnvironment(int tier, CardDeps deps) {
        DeviceCaps.forTier(tier);
        this.tier = tier;
        this.deps = Objects.requireNonNull(deps, "deps");
        setNode(deps.nodes.component(this, Visibility.Neighbors, COMPONENT));
        this.life = newLife();
    }

    // ---- The M0 callbacks (28:93-102; S11 plan P8). Docs in OC's form (00:31): the harness's, and F-1's for getCaps

    /**
     * {@code bind(address)}, on the server thread, since it reads OC's network (28:84; P13.2), as OC's GPU finds its
     * screen (GraphicsCard.scala:269-297): the node with the address in the card's network, else
     * {@code nil, "invalid address"}; a display's, else {@code nil, "not a display"} (Q-S11-7); then {@link #bindTo}.
     * It changes no device state, charges nothing beyond OC's one non-direct call per tick (28:708) and raises no
     * signal (P13.6).
     */
    @Callback(
        direct = false,
        doc = "function(address:string):boolean or nil, string -- maps the display with the address to the card's "
            + "device, taking it from any card bound to it before (28 §2.1)")
    public Object[] bind(Context context, Arguments args) {
        remember(context);
        String displayAddress = args.checkString(0);
        Node own = node();
        Node found = own == null || own.network() == null ? null
            : own.network()
                .node(displayAddress);
        if (found == null) {
            return new Object[] { null, "invalid address" };
        }
        if (!(found.host() instanceof DisplayTile)) {
            return new Object[] { null, "not a display" };
        }
        bindTo((DisplayTile) found.host(), displayAddress);
        return new Object[] { Boolean.TRUE };
    }

    /**
     * {@code getCaps()}: Device's 15 keys (28:675-690), then the root's (24:125; P10): {@code renderer}, "host-gl"
     * while the host is available and "none" otherwise, as the harness decides it (24:631); {@code readback}, true in
     * v1 (21:206-207; Q-S11-8); {@code graphics}, the installation's reason while the host is not available; and
     * {@code hostRenderer}, the cached {@code GL_RENDERER} when the installation has one. All of it is server state
     * (28:200). Uncharged beyond OC's 1/limit (28:707).
     */
    @Callback(direct = true, doc = "function():table -- the device's caps (28 §8.1) plus the host keys of 24:125")
    public Object[] getCaps(Context context, Arguments args) {
        remember(context);
        Map<String, Object> caps = life.device.getCaps();
        Installation installation = deps.installation;
        boolean available = installation.host()
            .available();
        caps.put("renderer", available ? "host-gl" : "none");
        caps.put("readback", Boolean.TRUE);
        if (!available) {
            caps.put("graphics", installation.unavailableReason());
        }
        if (installation.hostRenderer() != null) {
            caps.put("hostRenderer", installation.hostRenderer());
        }
        return new Object[] { caps };
    }

    /** {@code submit(stream)}: {@code checkByteArray} hands over the per-call array, which Device owns (28:84). */
    @Callback(
        direct = true,
        doc = "function(stream:string):boolean or nil, string -- validates the segment whole and "
            + "appends it to the open frame (28 §3.5)")
    public Object[] submit(Context context, Arguments args) throws LimitReachedException {
        remember(context);
        byte[] stream = args.checkByteArray(0);
        Device device = life.device;
        return call(context, budget -> device.submit(budget, stream)).toLua();
    }

    /** {@code present()}: a frame id also raises the floor's mark (P11.3). */
    @Callback(
        direct = true,
        doc = "function():number or nil, string -- seals the open frame into FRAME(frameId); "
            + "\"busy\", \"no renderer\" or \"host render stalled\" otherwise (24:126)")
    public Object[] present(Context context, Arguments args) throws LimitReachedException {
        remember(context);
        Life current = life;
        CallResult result = call(context, current.device::present);
        if (result.isOk()) {
            current.frames.presented(result.frameId());
        }
        return result.toLua();
    }

    /** {@code readPixels(x, y, w, h)}, in that argument order. */
    @Callback(
        direct = true,
        doc = "function(x:number, y:number, w:number, h:number):string, number or nil, string -- "
            + "a region of the newest confirmed frame in the surface byte layout, and its frame id (28 §5.7)")
    public Object[] readPixels(Context context, Arguments args) throws LimitReachedException {
        remember(context);
        int x = args.checkInteger(0);
        int y = args.checkInteger(1);
        int w = args.checkInteger(2);
        int h = args.checkInteger(3);
        Device device = life.device;
        return call(context, budget -> device.readPixels(budget, x, y, w, h)).toLua();
    }

    /** {@code createTexture(w, h[, format])}, the format nil when absent. */
    @Callback(
        direct = true,
        doc = "function(w:number, h:number[, format:string]):number or nil, string -- a texture handle (28 §6.2)")
    public Object[] createTexture(Context context, Arguments args) throws LimitReachedException {
        remember(context);
        int w = args.checkInteger(0);
        int h = args.checkInteger(1);
        String format = args.optString(2, null);
        Device device = life.device;
        return call(context, budget -> device.createTexture(budget, w, h, format)).toLua();
    }

    /** {@code updateTexture(tex, x, y, w, h, data)}. */
    @Callback(
        direct = true,
        doc = "function(tex:number, x:number, y:number, w:number, h:number, data:string):"
            + "boolean or nil, string -- a region of the texture's rows (28 §6.3)")
    public Object[] updateTexture(Context context, Arguments args) throws LimitReachedException {
        remember(context);
        int tex = args.checkInteger(0);
        int x = args.checkInteger(1);
        int y = args.checkInteger(2);
        int w = args.checkInteger(3);
        int h = args.checkInteger(4);
        byte[] data = args.checkByteArray(5);
        Device device = life.device;
        return call(context, budget -> device.updateTexture(budget, tex, x, y, w, h, data)).toLua();
    }

    /** {@code free(tex)}: uncharged and never "busy" (28:707). */
    @Callback(
        direct = true,
        doc = "function(tex:number):boolean or nil, string -- frees the texture, deferred while a frame is open "
            + "(28 §6.4)")
    public Object[] free(Context context, Arguments args) {
        remember(context);
        int tex = args.checkInteger(0);
        return life.device.free(tex)
            .toLua();
    }

    // ---- Lifecycle, on the server thread (28:150-151; S11 plan P7.4)

    /** True: the case calls {@link #update()} once per tick, after its machine's (Computer.scala:105-126). */
    @Override
    public boolean canUpdate() {
        return true;
    }

    /**
     * The device's tick: {@code "dropped"} 1 s after a present, then any waiting status (28:1076; H12), each handed to
     * the tick queue, whose drain at the next tick's start signals it (D16).
     */
    @Override
    public void update() {
        life.device.tick();
    }

    /** A neighbour's start or stop resets the device (28:150; 12:138); see the class comment. */
    @Override
    public void onMessage(Message message) {
        super.onMessage(message);
        String name = message.name();
        Node own = node();
        if ((STARTED.equals(name) || STOPPED.equals(name)) && own != null && own.isNeighborOf(message.source())) {
            context.set(null);
            Life current = life;
            current.frames.floor.set(current.frames.lastPresented.get());
            current.device.reset();
        }
    }

    /** The card's own connect, or the saved display's; see the class comment. */
    @Override
    public void onConnect(Node node) {
        super.onConnect(node);
        if (node == null) {
            return;
        }
        if (node == node()) {
            address = node.address();
            if (life.device.isReleased()) {
                life = newLife();
            }
            rebind(node);
        } else if (isSavedDisplay(node)) {
            rebindTo((DisplayTile) node.host());
        }
    }

    /** The card's own disconnect, or the bound display's; see the class comment. */
    @Override
    public void onDisconnect(Node node) {
        super.onDisconnect(node);
        if (node == null) {
            return;
        }
        if (node == node()) {
            context.set(null);
            detachDisplay();
            Device device = life.device;
            if (!device.isReleased()) {
                device.release();
                OpenGPU.LOG.debug(
                    "{} released; opengpu_frame sent {}, refused {}, unsent {}, stale {}",
                    device,
                    sent.get(),
                    refused.get(),
                    unsent.get(),
                    stale.get());
            }
        } else if (boundDisplay != null && node == boundDisplay.node()) {
            detachDisplay();
        }
    }

    /** OC's node tag, then the bound display's address when there is one (P7.4); no device state (24:179). */
    @Override
    public void save(NBTTagCompound nbt) {
        super.save(nbt);
        if (boundAddress != null) {
            nbt.setString(DISPLAY, boundAddress);
        }
    }

    /** OC's node tag, then the saved display's address, bound again at the card's connect. */
    @Override
    public void load(NBTTagCompound nbt) {
        super.load(nbt);
        String saved = nbt.getString(DISPLAY);
        boundAddress = saved.isEmpty() ? null : saved;
    }

    // ---- The binding, on the server thread (P13; amendments B2, B3)

    /**
     * {@code bind}'s steps 4-7 (P13.2), also binding again without a context (P13.4), after {@code rebindTo}'s check:
     * the display already bound does nothing; another one bound is detached; then the display is attached, which takes
     * it from a card bound to it before, makes this card its binder (D15) and maps it to this card's device, and this
     * card keeps it and its address.
     */
    void bindTo(DisplayTile display, String displayAddress) {
        if (display == boundDisplay) {
            return;
        }
        detachDisplay();
        display.attach(this);
        boundDisplay = display;
        boundAddress = displayAddress;
    }

    /**
     * The display was taken by another card's bind (P13.2 step 6): this card forgets it and its address, so it does
     * not take it back after a reload. Nothing unless it is this card's display (B2).
     */
    public void displayTaken(DisplayTile display) {
        if (display != null && display == boundDisplay) {
            boundDisplay = null;
            boundAddress = null;
        }
    }

    /**
     * The display went away, unloaded or broken, and has unmapped itself (P12.6): this card keeps its address. Nothing
     * unless it is this card's display (B2).
     */
    public void displayGone(DisplayTile display) {
        if (display != null && display == boundDisplay) {
            boundDisplay = null;
        }
    }

    /** The host the card's devices offer to, which maps its display ({@code RenderHost.bindDisplay}). */
    public RenderHost renderHost() {
        return deps.installation.host();
    }

    /** The current device's key, which the bound display shows. */
    public DeviceKey deviceKey() {
        return life.device.key();
    }

    /**
     * The surface the card's devices draw, which its display shows: the tier's (28 §8.1), the same for every device of
     * the card, also after a fresh one (P7.4). A display's input range and its description's size are this surface's
     * (S11 plan U5.5, U5.7).
     */
    public SurfaceDesc surface() {
        return SurfaceDesc.forTier(tier);
    }

    // ---- What tests read

    /** OpenGPU's tier, 2 or 3. */
    int tier() {
        return tier;
    }

    /** The current device. */
    Device device() {
        return life.device;
    }

    /** The context of the last callback, or null since a start, a stop or a disconnect (B8). */
    Context context() {
        return context.get();
    }

    DisplayTile boundDisplay() {
        return boundDisplay;
    }

    String boundAddress() {
        return boundAddress;
    }

    long signalsSent() {
        return sent.get();
    }

    long signalsRefused() {
        return refused.get();
    }

    long signalsUnsent() {
        return unsent.get();
    }

    long signalsStale() {
        return stale.get();
    }

    /** For example "CardEnvironment[T3, Device[DeviceKey[1:2] e1, ...], display opengpu_screen-2]". */
    @Override
    public String toString() {
        return "CardEnvironment[T" + tier + ", " + life.device + ", display " + boundAddress + "]";
    }

    // ---- Internals

    private Life newLife() {
        Frames frames = new Frames();
        Device device = Device.create(
            new DeviceKey(deps.session, deps.deviceIds.getAsInt()),
            DeviceCaps.forTier(tier),
            deps.installation.host(),
            deps.folds,
            frames,
            deps.nanoClock);
        return new Life(device, frames);
    }

    private void remember(Context c) {
        context.set(c);
    }

    /**
     * Runs one charged call on a {@link ContextBudget} (P9): :core's LimitReached, thrown with nothing changed, becomes
     * OC's LimitReachedException, so OC returns no values and machine.lua repeats the call synchronized on the next
     * tick, where Device pauses for the rest (28:710-713). OC's own passes through.
     */
    private static CallResult call(Context context, Function<CallBudget, CallResult> f) throws LimitReachedException {
        try {
            return f.apply(new ContextBudget(context));
        } catch (LimitReached e) {
            throw new LimitReachedException();
        }
    }

    /** P13.4: binds the saved display again, without a context; a failed lookup keeps the address. */
    private void rebind(Node own) {
        if (boundAddress == null || boundDisplay != null || own.network() == null) {
            return;
        }
        Node found = own.network()
            .node(boundAddress);
        if (found != null && found.host() instanceof DisplayTile) {
            rebindTo((DisplayTile) found.host());
        }
    }

    /**
     * Binds the saved display again without a context (P13.4), unless another card holds it (D13) or another card is
     * its binder (D15): that card bound it while this one was detached, or before a restart, and this one heard no
     * {@link #displayTaken}, and the last bind wins (Q-S11-6; Q-S11-16), so this card forgets the address, as
     * displayTaken would have made it (P13.2 step 6). A display with no binder, saved before D15, binds. A card that
     * OC has re-addressed (Network.scala:226-229, :276-300) fails the binder check and forgets the display too: D15's
     * known limit. D13's check stays, though D15 implies it: every attach records its card, so a card that holds the
     * display is its binder [I].
     */
    private void rebindTo(DisplayTile display) {
        CardEnvironment holder = display.boundCard();
        String binder = display.binder();
        boolean heldByAnother = holder != null && holder != this;
        boolean boundLastByAnother = binder != null && !binder.equals(node().address());
        if (heldByAnother || boundLastByAnother) {
            boundAddress = null;
            return;
        }
        bindTo(display, boundAddress);
    }

    /** Whether the node is the saved display's while none is bound, as OC's GPU finds its screen (P7.4). */
    private boolean isSavedDisplay(Node node) {
        return boundDisplay == null && boundAddress != null
            && boundAddress.equals(node.address())
            && node.host() instanceof DisplayTile;
    }

    /** Unmaps and forgets the bound display, keeping its address (P13.3; B2); nothing when none is bound. */
    private void detachDisplay() {
        DisplayTile display = boundDisplay;
        if (display != null) {
            boundDisplay = null;
            display.detach(this);
        }
    }

    /**
     * One status at the tick queue's drain, on the server thread (D16), so the checks and {@code Context.signal} run
     * in turn with {@link #onMessage}'s resets and the connects; see the class comment.
     */
    private void signal(Frames frames, long frameId, String status) {
        // Stale: from a device since replaced, or presented before the last reset (28:115), whose notice Device keeps,
        // or handed over before it
        if (frames != life.frames || frameId <= frames.floor.get()) {
            stale.incrementAndGet();
            return;
        }
        Context c = context.get();
        String a = address;
        if (c == null || a == null) {
            unsent.incrementAndGet();
            return;
        }
        boolean accepted;
        try {
            accepted = c.signal(SIGNAL, a, Long.valueOf(frameId), status);
        } catch (RuntimeException e) {
            // A tick queue's entry must not throw, as a FrameListener must not (FrameListener.java): the drain runs in
            // FML's tick event. So a failure counts as refused, and the first is logged [proposal]
            accepted = false;
            if (signalFailureLogged.compareAndSet(false, true)) {
                OpenGPU.LOG.warn("opengpu_frame failed on {}; later failures are counted as refused", this, e);
            }
        }
        (accepted ? sent : refused).incrementAndGet();
    }

    /**
     * A device and its listener: the listener drops the statuses of a device that is no longer the card's [proposal].
     */
    private static final class Life {

        final Device device;
        final Frames frames;

        Life(Device device, Frames frames) {
            this.device = device;
            this.frames = frames;
        }
    }

    /**
     * One device's statuses (P11.3): {@code lastPresented} is the newest frame id {@code present} returned, and
     * {@code floor} its value at the last reset; a fresh device starts both at 0. Each status goes to the session's
     * tick queue on the delivering thread, in the device's order, and is signalled at the drain (D16). Never blocks,
     * never throws.
     */
    private final class Frames implements FrameListener {

        final AtomicLong lastPresented = new AtomicLong();
        final AtomicLong floor = new AtomicLong();

        void presented(long frameId) {
            lastPresented.accumulateAndGet(frameId, Math::max);
        }

        @Override
        public void frameDone(long frameId) {
            deps.tickQueue.add(new Status(this, frameId, Statuses.DONE));
        }

        @Override
        public void frameDropped(long frameId) {
            deps.tickQueue.add(new Status(this, frameId, Statuses.DROPPED));
        }
    }

    /**
     * One status on its way to the server thread (D16): signalled at the tick queue's drain, or counted unsent when
     * the session stops before the drain takes it [proposal, D16].
     */
    private final class Status implements TickQueue.Entry {

        private final Frames frames;
        private final long frameId;
        private final String status;

        Status(Frames frames, long frameId, String status) {
            this.frames = frames;
            this.frameId = frameId;
            this.status = status;
        }

        @Override
        public void run() {
            signal(frames, frameId, status);
        }

        @Override
        public void drop() {
            unsent.incrementAndGet();
        }

        /** For example "Status[3 done]". */
        @Override
        public String toString() {
            return "Status[" + frameId + " " + status + "]";
        }
    }
}
