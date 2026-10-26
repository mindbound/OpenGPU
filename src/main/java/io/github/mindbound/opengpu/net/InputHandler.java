package io.github.mindbound.opengpu.net;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.tileentity.TileEntity;

import io.github.mindbound.opengpu.OpenGPU;
import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.display.DisplayTile;
import io.netty.buffer.ByteBuf;

/**
 * Input's server half (24:140, :222; 15:181-187; 09:87; S11 plan U5.2-U5.5): one server session's handler of the
 * pointer packets that clients send on the {@value Channel#NAME} channel. {@link InputListener} hands it each
 * packet's payload on the server thread, where FML posts the channel's events after the tick's tile-entity updates
 * (MinecraftServer.java:707, :730; FMLEventChannel.java:98-110). So there is no hand-off queue: the network's drain is
 * the hand-off [proposal].
 * <p>
 * <b>The checks</b>, in this order, read less of the game state first [proposal]. Each failure drops the packet and
 * counts one {@link Outcome} [proposal]: nothing is sent back, nobody is kicked and nothing is clamped (15:185; OC
 * drops its bad mouse packets silently too, PacketHandler.scala:235, :241). The first drop of each outcome in a session
 * is logged at debug, with the player's name once a player is known; later ones are only counted [proposal].
 * <ol start="0">
 * <li>on the session's server thread, else {@code OFF_THREAD}, logged once per session as a warning [proposal: FML
 * posts the event on that thread, U5.2, which another mod could change];</li>
 * <li>a version-1 pointer packet ({@link PointerPacket#decode}), else {@code MALFORMED} (09:87; 24:33);</li>
 * <li>px and py finite, else {@code NOT_FINITE} (24:222; PacketHandler.scala:26, :235).</li>
 * </ol>
 * Only then is the sender's {@link InputSource} asked for, so a malformed or forged packet touches no player and no
 * world. Checks 3-5 read the player's position and the server's chunk maps and change nothing; check 6 can change the
 * world, so it comes after them (OC also asks blockExists first, PacketHandler.scala:77-78):
 * <ol start="3">
 * <li>the player within 8 blocks of the block's centre, a squared distance of at most 64, else
 * {@code OUT_OF_REACH} (24:222; 15:128; OC's rule, TextBuffer.scala:408-413);</li>
 * <li>the block's chunk loaded in the sender's world, y in 0-255, else {@code NOT_LOADED} (24:222);</li>
 * <li>the sender watching that chunk, else {@code NOT_WATCHING} (09:87). Both, since they differ while a chunk is
 * still being sent;</li>
 * <li>a valid display there whose id is the packet's, else {@code NO_DISPLAY} [proposal];</li>
 * <li>a card bound to it, with its node in a network, else {@code UNBOUND} [proposal, after 15:185's range check]:
 * M0's display has no pixel space of its own, it shows its card's surface (P13.1; Q-S11-3);</li>
 * <li>{@code 0 <= px < W} and {@code 0 <= py < H} for the bound card's surface, which the server derives itself and
 * never takes from the client, else {@code OUT_OF_RANGE} (15:185; 09:87; 24:222).</li>
 * </ol>
 * The handler computes the block's centre (x + 0.5, y + 0.5, z + 0.5) and chunk (x &gt;&gt; 4, z &gt;&gt; 4) itself, as
 * OC does (Environment.scala:22; PacketHandler.scala:30-31). No rate limit is added beyond the server's drain and OC's
 * signal queue, which bound OC's own mouse packets too (03:67; Machine.scala:335-339) [proposal].
 * <p>
 * <b>The signal</b> (U5.5). An accepted packet makes the display's node send
 * {@code computer.checked_signal(player, name, x, y, data[, playerName])} to every machine that can reach it, as OC's
 * screens and keyboards do (TextBuffer.scala:890-910; Keyboard.scala:137-143); a machine that the player may use
 * signals {@code name} with the display's address in front (Machine.scala:645-656), so Lua sees
 * {@code touch(display, x, y, button, player)} and drag, drop and scroll alike (24:140). The coordinates are 0-based
 * pixels, a departure from OC's 1-based cells (24:140; 15:184; 09:65): a {@code Long}, floored, by default, and a
 * {@code Double} of the float in precise mode, with -0.0 turned into +0.0 [proposal] (15:181). The data byte goes as a
 * {@code Long} of the signed byte, OC's value in 09:65's boxing, since an Integer reads back as nil after a save
 * (Machine.scala:865-879). The player's name follows when OC's {@code misc.inputUsername} is set, as OC's own input
 * does (TextBuffer.scala:905-907) [proposal]. The player is then marked active, as OC marks it for its packets
 * (PacketHandler.scala:36-44) [proposal].
 * <p>
 * Counts are kept for every outcome but {@code NO_SESSION}, which the listener counts; any thread may read them.
 */
public final class InputHandler {

    /** The message OC's machines turn into a signal once the player may use them (Machine.scala:645-656). */
    public static final String MESSAGE = "computer.checked_signal";
    /** OC's reach for screen input, squared: 8 blocks to the block's centre (TextBuffer.scala:408-413). */
    static final double REACH_SQUARED = 64;

    /** What became of a packet. */
    public enum Outcome {
        /** No session ran: counted by {@link InputListener}, not here. */
        NO_SESSION,
        OFF_THREAD,
        MALFORMED,
        NOT_FINITE,
        OUT_OF_REACH,
        NOT_LOADED,
        NOT_WATCHING,
        NO_DISPLAY,
        UNBOUND,
        OUT_OF_RANGE,
        ACCEPTED,
        /** A RuntimeException out of {@link #receive}, which the listener caught and passed to {@link #failed}. */
        FAILED
    }

    private static final Outcome[] OUTCOMES = Outcome.values();

    private final boolean inputUsername;
    private final BooleanSupplier onServerThread;
    private final AtomicLongArray counts = new AtomicLongArray(OUTCOMES.length);

    /**
     * A session's handler: {@code inputUsername} is OC's {@code misc.inputUsername}, read once when the session
     * starts, and {@code onServerThread} tells whether the calling thread is the session's (S11 amendments D1, E6).
     *
     * @throws NullPointerException for a null thread test
     */
    public InputHandler(boolean inputUsername, BooleanSupplier onServerThread) {
        this.inputUsername = inputUsername;
        this.onServerThread = Objects.requireNonNull(onServerThread, "onServerThread");
    }

    /**
     * Checks one packet's payload in the order of the class comment and, if every check passes, signals it from the
     * display. {@code source} gives the sender, and is asked only after checks 0-2 have passed. It throws nothing of
     * its own for any bytes; a RuntimeException from the game, such as OC's from {@code sendToReachable}, passes out
     * to the listener, which hands it to {@link #failed}. It leaves the payload to its caller, who releases it.
     */
    public void receive(ByteBuf payload, Supplier<? extends InputSource> source) {
        if (!onServerThread.getAsBoolean()) {
            if (bump(Outcome.OFF_THREAD) == 1) {
                OpenGPU.LOG.warn(
                    "OpenGPU input came on thread {}, not the server's, and was dropped; later ones are only counted",
                    Thread.currentThread()
                        .getName());
            }
            return;
        }
        PointerPacket.Result decoded = PointerPacket.decode(payload);
        if (decoded.isMalformed()) {
            drop(Outcome.MALFORMED, null);
            return;
        }
        PointerEvent event = decoded.event();
        if (!Float.isFinite(event.px()) || !Float.isFinite(event.py())) {
            drop(Outcome.NOT_FINITE, null);
            return;
        }
        InputSource player = source.get();
        int x = event.x();
        int y = event.y();
        int z = event.z();
        if (player.distanceSq(x + 0.5, y + 0.5, z + 0.5) > REACH_SQUARED) {
            drop(Outcome.OUT_OF_REACH, player);
            return;
        }
        if (y < 0 || y > 255 || !player.blockLoaded(x, y, z)) {
            drop(Outcome.NOT_LOADED, player);
            return;
        }
        if (!player.watchesChunk(x >> 4, z >> 4)) {
            drop(Outcome.NOT_WATCHING, player);
            return;
        }
        TileEntity tile = player.tileEntity(x, y, z);
        if (!(tile instanceof DisplayTile) || tile.isInvalid()
            || !event.displayId()
                .equals(((DisplayTile) tile).displayId())) {
            drop(Outcome.NO_DISPLAY, player);
            return;
        }
        DisplayTile display = (DisplayTile) tile;
        SurfaceDesc surface = display.boundSurface();
        if (surface == null) {
            drop(Outcome.UNBOUND, player);
            return;
        }
        float px = event.px();
        float py = event.py();
        if (!(px >= 0 && px < surface.width && py >= 0 && py < surface.height)) {
            drop(Outcome.OUT_OF_RANGE, player);
            return;
        }
        display.sendInput(signal(event, display.isPrecise(), player));
        player.markActive();
        bump(Outcome.ACCEPTED);
    }

    /**
     * Counts a RuntimeException that {@link #receive} let out, which the listener caught so FML kicks nobody
     * (FMLProxyPacket.java:102-111); the session's first is logged as a warning with its stack trace.
     */
    public void failed(RuntimeException e) {
        if (bump(Outcome.FAILED) == 1) {
            OpenGPU.LOG.warn("OpenGPU input failed; later failures are only counted", e);
        }
    }

    /** How many packets came to the outcome so far; 0 for {@code NO_SESSION}, which the listener counts. */
    public long count(Outcome outcome) {
        return counts.get(outcome.ordinal());
    }

    /** The outcomes counted so far, those above 0, in the enum's order. */
    public Map<Outcome, Long> counts() {
        Map<Outcome, Long> out = new EnumMap<>(Outcome.class);
        for (Outcome outcome : OUTCOMES) {
            long n = counts.get(outcome.ordinal());
            if (n > 0) {
                out.put(outcome, n);
            }
        }
        return out;
    }

    /** Whether the signal carries the player's name, OC's {@code misc.inputUsername}. */
    public boolean inputUsername() {
        return inputUsername;
    }

    @Override
    public String toString() {
        return "InputHandler[inputUsername " + inputUsername + ", " + counts() + "]";
    }

    private long bump(Outcome outcome) {
        return counts.incrementAndGet(outcome.ordinal());
    }

    private void drop(Outcome outcome, InputSource player) {
        if (bump(outcome) != 1) {
            return;
        }
        if (player == null) {
            OpenGPU.LOG.debug("OpenGPU input dropped as {}; later ones are only counted", outcome);
        } else {
            OpenGPU.LOG
                .debug("OpenGPU input from {} dropped as {}; later ones are only counted", player.name(), outcome);
        }
    }

    /** {@code (player, name, x, y, data[, playerName])} for {@link #MESSAGE} (U5.5). */
    private Object[] signal(PointerEvent event, boolean precise, InputSource player) {
        List<Object> args = new ArrayList<>(6);
        args.add(player.entity());
        args.add(
            event.kind()
                .signal());
        if (precise) {
            // + 0.0f turns -0.0, which the range check lets pass, into +0.0
            args.add(Double.valueOf(event.px() + 0.0f));
            args.add(Double.valueOf(event.py() + 0.0f));
        } else {
            // px and py are at least 0 here, so the cast floors
            args.add(Long.valueOf((long) event.px()));
            args.add(Long.valueOf((long) event.py()));
        }
        args.add(Long.valueOf(event.data()));
        if (inputUsername) {
            args.add(player.name());
        }
        return args.toArray();
    }
}
