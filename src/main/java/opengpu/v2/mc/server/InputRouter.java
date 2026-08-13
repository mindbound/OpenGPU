package opengpu.v2.mc.server;

import java.util.HashMap;
import java.util.Map;

import li.cil.oc.api.network.Node;
import net.minecraft.entity.player.EntityPlayer;
import opengpu.v2.protocol.MessageCodec;

/**
 * Validates player input against a scene and turns it into OC signals.
 *
 * Everything a client sends is treated as hostile until proven otherwise (legacy X-01): the
 * surface is resolved from the server's OWN binding rather than named by the client, the
 * coordinates are range-checked against the scene's logical size, and only a fixed set of
 * signal names can ever be emitted. Identity is an opaque per-press id, never a player name.
 *
 * Signals are sent from the SCREEN's node, because OC's Machine prepends the sending node's
 * address to every signal — which is exactly the {@code (surfaceAddress, ...)} shape the
 * design specifies.
 *
 * TWO CHANNELS, deliberately. Live input a player is producing right now goes out as
 * {@code computer.checked_signal}, so OC applies its canInteract rules exactly as it does
 * for its own {@code touch}. A server-originated release of a STRANDED gesture goes out as
 * plain {@code computer.signal} with no player attached — OC's own convention for
 * server-observed state transitions ({@code walk} in Screen.scala, {@code motion},
 * {@code redstone_changed}), and the reason the whole strand-handling design collapses to
 * one delivery path: an unchecked release needs no live EntityPlayer, so it can be
 * delivered whenever the machine is running instead of whenever the holder is online.
 * See docs/dev/INPUT-GESTURE-PERSISTENCE.md for the full argument.
 *
 * Server thread only, under the owning TE's scene lock.
 */
public final class InputRouter {
	/** Max input events accepted from one watcher per tick; excess is dropped silently. */
	public static final int MAX_EVENTS_PER_WATCHER_PER_TICK = 20;

	/**
	 * A gesture in flight: everything needed to END it without the client's help.
	 *
	 * The id alone is not enough, and that is the whole point. A release has to be emitted
	 * FROM THE NODE THE PRESS WAS EMITTED FROM — after a rebind, sending through the GPU's
	 * current screen would deliver {@code monitor_up(newAddress)} for a
	 * {@code monitor_down(oldAddress)}: a phantom release on a surface the player never
	 * touched, while the real gesture stays stuck. The coordinates ride along so a
	 * server-originated release lands where the gesture actually was, rather than at a
	 * position a disqualified client got to choose.
	 */
	static final class Pointer {
		final int id;
		final int button;
		/** The surface the DOWN was emitted from, by ADDRESS — never by Node reference. */
		final String address;
		int x;
		int y;

		/**
		 * A move waiting for the tick flush — TRANSIENT, deliberately not persisted.
		 *
		 * Moves are COALESCED per gesture per tick rather than emitted per packet, because the
		 * consumer measurably cannot keep up with per-packet emission. An uncoalesced drag emits
		 * ~60 moves/s. The consumer's ceiling measured ~53 events/s: `event.pull` costs ~11 ms
		 * per call on the OC side (it yields the machine even at a zero timeout), which would
		 * allow ~90/s if pulling were all the program did, and pulls took ~60% of wall time in
		 * the instrumented loop — 0.60 / 11 ms ≈ 53/s, which is also the loop rate that run
		 * measured. Six in-game runs, see ingame/uidemo.lua; keep these figures in step with
		 * that file, which is where they were measured. So the queue GROWS for the length of
		 * every gesture — lag proportional to drag duration — and a backlog is unrecoverable
		 * from Lua, because skipping a signal costs the same pull as handling it.
		 *
		 * THE ~11 ms IS A LOADED-REGIME FIGURE, NOT A PROPERTY OF event.pull, and the original
		 * wording of this paragraph — "it yields the machine even at a zero timeout", offered as
		 * the mechanism — was simply wrong. uidemo run 8 timed the three populations separately
		 * instead of averaging them together:
		 *
		 *   zero-timeout pull that RETURNED an event   1.4 ms  (11.1 ms under backlog, run 6)
		 *   zero-timeout pull that returned NOTHING    0.3 ms
		 *   event.pull(0.02), the idle wait           50.2 ms
		 *
		 * So the yield is cheap; what is expensive is a TIMED wait, which rounds up to a whole
		 * Minecraft tick — ask for 20 ms and you wait 50. That, not the pull, is where an idle
		 * Lua event loop spends its life: run 8 accounted for 28.9 s of 29.0 s elapsed, and
		 * 572 idle waits x 50.2 ms is 28.7 s of it.
		 *
		 * The ~11 ms is real but CONDITIONAL: identical calls returning identical events cost
		 * 11.1 ms with a deep queue and 1.4 ms with a shallow one, an 8x swing that is not a
		 * sampling artifact (run 6's pulls were 97.6% hits, so its mean was essentially the hit
		 * cost). WHY depth makes a pull dear is still unestablished, and it does not need to be
		 * here: the ceiling that bounds the emitter is the loaded one, ~53/s, measured directly
		 * in the regime the bound must survive. Do not carry the 11 ms into an idle-path
		 * argument, and do not carry the yield explanation anywhere at all.
		 *
		 * OC's signal queue is 256 slots, and the two ways to fill it want separate arithmetic.
		 * A program not listening for input at all drains none of it and overflows in 256/60 ≈
		 * 4 s. That is the robust number and the nastier case, because the victim is a program
		 * that never asked for a pointer: overflow drops KEY events for everything on the
		 * machine. A program that IS draining overflows far more slowly — the net is 60 − 53 ≈
		 * 7/s, so ~35 s — but treat that one as an order of magnitude rather than a figure: it
		 * is the small difference of two measured rates that are themselves ±15%, and no
		 * instrumented run actually observed the queue reach 256 while draining.
		 *
		 * So a move updates {@code x}/{@code y} (which route() already did) and parks the
		 * emission references here; {@link InputRouter#flushCoalescedMoves} emits at most ONE
		 * monitor_move per gesture per tick, carrying the NEWEST position.
		 *
		 * COST, stated honestly: this is not a delay, it is a DISCARD. Intermediate positions
		 * between two flushes are overwritten and never emitted — at ~60 moves/s in and 20
		 * ticks/s out, roughly two of every three moves never reach Lua at all. A program that
		 * draws a stroke by joining consecutive monitor_move points therefore samples it at
		 * 20 Hz, and a fast drag renders as a polygon with its corners cut. What is preserved is
		 * the newest POSITION, which is what a drag, a hover, or a slider needs; what is lost is
		 * the PATH between samples. Freehand drawing is the case that notices.
		 *
		 * BOUGHT: emission bounded at 20/s per gesture, comfortably under the consumer's
		 * ceiling. Note the bound is per GESTURE, not per machine, and nothing bounds concurrent
		 * gestures — activePointer is keyed per watcher AND per button, and several GPUs can
		 * feed one machine's queue. Three simultaneous drags put 60/s back on the queue and the
		 * original defect returns; two stay within budget. In practice that means several
		 * PLAYERS dragging at once, not one player with several buttons: MC's GuiScreen reports
		 * a single dragged button, so one player produces one move stream.
		 *
		 * The node and player references are held for AT MOST ONE TICK, only so the flush can
		 * emit exactly what route() would have (same checked signal, same permission check).
		 * They are dropped, not persisted: a pending move is meaningless across a save. MOST
		 * stranding paths also remove this record from {@code activePointer} and take the
		 * pending move with them — that is what orders a move against its own UP, see the UP
		 * case. The exception is the screen's chunk unloading, which notifies the GPU of
		 * nothing (flushScreen's javadoc documents the same hole); there the record survives
		 * with its move pending, and what drops it is the dead-node guard in the flush rather
		 * than any removal.
		 */
		// `moveNode != null` IS the pending-move flag. There was a separate boolean until a
		// mutation sweep proved it equivalent: the flush nulls this reference after reading, so
		// the flag tracked the nullness exactly, and a state that two fields must agree on is a
		// desync waiting for the edit that updates one of them.
		Node moveNode;
		EntityPlayer movePlayer;

		Pointer(int id, int button, String address, int x, int y) {
			this.id = id;
			this.button = button;
			this.address = address;
			this.x = x;
			this.y = y;
		}
	}

	/**
	 * A key currently held down, as the server understands it.
	 *
	 * The server tracked NOTHING for keys until 2026-08-08 — route() forwarded the two signals
	 * and kept no state — so every path that stranded a pointer stranded a key identically, and
	 * there was nothing to release from. The client tracks its own held keys and releases them
	 * when the GUI closes, which covers the ordinary case and hides how much it does not cover:
	 * a crash or Alt+F4, a proximity unsubscribe at 96 blocks, a screen removed, a world save,
	 * a chunk unload, or a KEY_UP dropped by the rate limit.
	 *
	 * Identity is the KEYCODE, not the char: shift changes the char while the physical key is
	 * the same, so matching a release to its press by char would fail exactly when a program
	 * holds a modifier. The char is carried along only to be replayed on release.
	 */
	private static final class HeldKey {
		final int keycode;
		final int ch;
		/** The surface the DOWN was emitted from, by ADDRESS — same rule as Pointer. */
		final String address;

		HeldKey(int keycode, int ch, String address) {
			this.keycode = keycode;
			this.ch = ch;
			this.address = address;
		}
	}

	// Package-private for InputRouterTest, on OcslDiagnostics.slot()'s precedent: the flush
	// semantics are unit-testable (Pointer + the Node INTERFACE), while route() takes
	// TileEntityScreen2 and a live EntityPlayer — MC concretes no unit test can construct —
	// so the pend side stays field-verified like the rest of this class.
	final Map<String, Pointer> activePointer = new HashMap<String, Pointer>();
	private final Map<String, HeldKey> heldKeys = new HashMap<String, HeldKey>();
	private final Map<String, Integer> eventsThisTick = new HashMap<String, Integer>();
	private long currentTick = Long.MIN_VALUE;
	private int nextPointerId = 1;

	/**
	 * Called at the start of each pump so the per-tick allowance resets exactly once — and,
	 * since 2026-08-13, so coalesced moves flush exactly once per tick.
	 *
	 * This is deliberately NOT where the pending-RELEASE path emits, and the difference is worth
	 * stating because the two look interchangeable. A release emits from serverBeginTick, at
	 * {@code TickEvent.Phase.START}, because it must not be lost: a signal queued as the world
	 * saves dies in OC's resume. A pending move is the opposite kind of object — transient,
	 * superseded by the next move, and losable without harm (see {@link Pointer#moveNode} for
	 * which paths drop one, and the single path that does not) — so it is placed for LATENCY
	 * instead, and Phase.END is where the latency is. V2ServerRuntime runs
	 * {@code drainInbound()} immediately before the serverPump loop in that same phase, so the
	 * moves this flush emits were pended a few lines earlier in the SAME tick rather than
	 * waiting for the next one.
	 */
	public void beginTick(long tick, int sceneWidth, int sceneHeight) {
		if (tick != currentTick) {
			currentTick = tick;
			eventsThisTick.clear();
			flushCoalescedMoves(sceneWidth, sceneHeight);
		}
	}

	/**
	 * Emit at most one monitor_move per live gesture, carrying its newest position.
	 *
	 * See {@link Pointer#moveNode} for why moves coalesce at all. Two drop rules, both
	 * deliberate:
	 * <ul>
	 * <li>A gesture whose record left {@code activePointer} takes its pending move with it.
	 *     For an UP that is the ORDERING guarantee — the release already emitted with the
	 *     final coordinates, and a stale move emitted after it would run the gesture
	 *     backwards. For a stranded gesture the synthesized release owns the ending the same
	 *     way.</li>
	 * <li>A node whose network is gone is dropped silently, because a signal into a dead node
	 *     reaches nobody. Note what this does NOT promise: for a screen whose chunk unloaded,
	 *     no release is synthesized in-session — that gesture is folded into NBT at the next
	 *     save and released on reload. The break path is different again, and never reaches
	 *     this branch at all: BlockScreen2 notifies before super.breakBlock, so flushScreen has
	 *     already removed the Pointer by the time this runs.</li>
	 * <li>A move that is out of bounds against the LIVE scene size is dropped, see below.</li>
	 * </ul>
	 *
	 * @param sceneWidth  the scene's size AS OF NOW, not as of when the packet arrived. Callers
	 *                    with no scene pass 0, 0 — a size nothing can be inside, so any move
	 *                    pending across a teardown is dropped rather than measured against a
	 *                    default size no scene has
	 * @param sceneHeight see above
	 */
	void flushCoalescedMoves(int sceneWidth, int sceneHeight) {
		for (Pointer p : activePointer.values()) {
			if (p.moveNode == null) {
				continue;
			}
			Node node = p.moveNode;
			EntityPlayer player = p.movePlayer;
			p.moveNode = null;
			p.movePlayer = null;
			if (node.network() == null) {
				continue;
			}
			// Re-checked against the size that is live NOW, and deferring the emission is
			// precisely what makes the two sizes able to differ: route() already documents that
			// the server applies Lua's setResolution the instant it is called, from the machine
			// thread, so a resize can land between the pend and this flush. route() DROPS an
			// out-of-bounds move rather than clamping it (clamping is for releases, which must
			// not be lost), so this drops one too — otherwise coalescing would emit the one
			// thing route() guarantees it never emits, a monitor_move outside the current
			// resolution. Reachable by a program that resizes while a player is mid-drag.
			if (!inBounds(p.x, p.y, sceneWidth, sceneHeight)) {
				continue;
			}
			// Guarded for the reason emitRelease states about its own identical send: this fans
			// out through every third-party onMessage on the network, and an escaping throw here
			// would reach V2ServerRuntime's Phase.END pump loop, which wraps te.serverPump in
			// nothing — crashing the tick and skipping every GPU after this one. Per pointer, so
			// one hostile consumer costs its own gesture's move and not the rest of the flush.
			//
			// NOT a claim that this file is now uniformly guarded: route()'s three emit sites
			// are still bare, and onInput does not wrap route() either, so a monitor_down can
			// still take the tick down. That is pre-existing and unchanged by this commit —
			// recorded here rather than silently half-fixed.
			try {
				node.sendToReachable("computer.checked_signal", player, "monitor_move",
						Integer.valueOf(p.x), Integer.valueOf(p.y),
						Integer.valueOf(p.button), Integer.valueOf(p.id));
			} catch (RuntimeException e) {
				opengpu.OpenGPU.logger.warn("v2: coalesced monitor_move failed for gesture "
						+ p.id, e);
			}
		}
	}

	private static String pointerSlot(String watcherKey, int button) {
		return watcherKey + '#' + button;
	}

	/**
	 * Keyed per KEYCODE, for the same reason pointers are keyed per button: holding several
	 * keys at once is ordinary, and one slot per watcher would make the second press overwrite
	 * the first, stranding it with nothing left pointing at it.
	 *
	 * A DIFFERENT separator from pointerSlot's, so the two namespaces cannot collide: both are
	 * "watcher + separator + small int", and watcherOf() has to recover the watcher from either.
	 */
	private static String keySlot(String watcherKey, int keycode) {
		return watcherKey + '@' + keycode;
	}

	/**
	 * Recover the watcher from either namespace's slot key.
	 *
	 * Must strip whichever separator appears LAST, not '#' specifically: a watcher key is a
	 * player UUID string, which contains neither character, but taking the later of the two
	 * positions is what keeps this correct without depending on that. Returning the whole slot
	 * when neither is present is the pre-existing behaviour for a bare watcher key.
	 */
	private static String watcherOf(String slot) {
		int cut = Math.max(slot.lastIndexOf('#'), slot.lastIndexOf('@'));
		return cut < 0 ? slot : slot.substring(0, cut);
	}

	/**
	 * End every gesture held on one surface, by EMITTING its release.
	 *
	 * This exists because the defect was never that {@code activePointer} leaked — a map entry
	 * costs nothing and dies with the chunk. The defect is that a {@code monitor_down} reached
	 * an OC machine and no {@code monitor_up} followed, and that belief lives in persisted Lua
	 * state. Only a signal ends it. The old {@code evictWatcher} cleared the map and emitted
	 * nothing, so it left every machine holding the button exactly as before; forgetting the
	 * slot is not a lesser fix, it is not a fix at all.
	 *
	 * Called at the transitions where the server knows a gesture cannot be completed against
	 * this surface: unbind, rebind, screen removal, the wall-follow when a bound screen is
	 * absorbed and demoted, and the driver-takeover drop after a network partition heals. Those
	 * all run on the server thread while the OLD screen is still live and its node still
	 * attached, which is what makes the release deliverable — {@code BlockScreen2.breakBlock}
	 * deliberately notifies before {@code super.breakBlock} for this reason.
	 *
	 * That list has been wrong twice by omission, so treat it as a summary and not an index:
	 * the authoritative set is whatever calls this method and {@code flushBoundScreenLocked}.
	 *
	 * Not reachable at all when the screen's own chunk unloads: {@code invalidate()} and
	 * {@code onChunkUnload()} both call {@code node.remove()} first, so there is no network
	 * left to send on. Even that case is now covered, because a flush no longer sends
	 * anything: writeToNBT persists live gestures as pending records regardless, and the
	 * delivery loop emits them when the surface resolves again.
	 *
	 * INFALLIBLE, and that is the design. Ending a gesture used to mean emitting a signal,
	 * which could fail, which forced a remove-only-on-success rule, which was got wrong at
	 * three different call sites across four review rounds. Ending a gesture now means
	 * MOVING it to {@code pendingReleases}; the per-tick delivery loop owns the emission and
	 * all of the retrying. A map move cannot fail, so there is no rule to get wrong.
	 *
	 * @return how many gestures were moved to pending
	 */
	public int flushScreen(String screenAddress) {
		if (screenAddress == null) {
			return 0;
		}
		int moved = 0;
		java.util.Iterator<Map.Entry<String, Pointer>> it = activePointer.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, Pointer> entry = it.next();
			Pointer p = entry.getValue();
			if (!screenAddress.equals(p.address)) {
				continue;
			}
			it.remove();
			moveToPending(entry.getKey(), p);
			moved++;
		}
		// Keys held on the same surface, by the same rule. Every sweep in this file has to do
		// both halves; a sweep that moved pointers only would leave the key stranded in exactly
		// the situation the sweep exists for.
		java.util.Iterator<Map.Entry<String, HeldKey>> keys = heldKeys.entrySet().iterator();
		while (keys.hasNext()) {
			Map.Entry<String, HeldKey> entry = keys.next();
			HeldKey k = entry.getValue();
			if (!screenAddress.equals(k.address)) {
				continue;
			}
			keys.remove();
			moveToPending(entry.getKey(), k);
			moved++;
		}
		return moved;
	}

	/**
	 * Every gesture held by anyone, whatever surface it is on. For teardown of this GPU.
	 *
	 * Same move-to-pending as every other flush, but the two teardowns then diverge, and the
	 * CALLER owns the difference. On a chunk unload the records must NOT be delivered now:
	 * onChunkUnload runs before the unload save, so they ride the following writeToNBT to
	 * disk and are delivered when the chunk returns — emitting as well would deliver once
	 * now and once from the restored record. On a block destruction there is no later save
	 * and no restore — the router and its records die with the TE — so the caller must
	 * attempt synchronous delivery immediately after this returns, while the nodes are
	 * still in the network.
	 */
	public int flushAll() {
		int moved = 0;
		java.util.Iterator<Map.Entry<String, Pointer>> it = activePointer.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, Pointer> entry = it.next();
			Pointer p = entry.getValue();
			it.remove();
			moveToPending(entry.getKey(), p);
			moved++;
		}
		java.util.Iterator<Map.Entry<String, HeldKey>> keys = heldKeys.entrySet().iterator();
		while (keys.hasNext()) {
			Map.Entry<String, HeldKey> entry = keys.next();
			HeldKey k = entry.getValue();
			keys.remove();
			moveToPending(entry.getKey(), k);
			moved++;
		}
		return moved;
	}

	/**
	 * A gesture that outlived the session it was pressed in.
	 *
	 * Deliberately a separate type from {@link Pointer}, and kept in a separate collection.
	 * A restored record is NOT a live gesture: the client that pressed it is gone, nothing
	 * can move it, and no release will ever arrive for it. Putting these back into
	 * {@code activePointer} would make {@code route()} treat a stale slot as an incumbent
	 * and release it on the player's next real press — turning one stuck gesture into a
	 * phantom release plus a lost press.
	 */
	public static final class PendingRelease {
		/** A stranded pointer press: emits monitor_up. Value 0 so a record written before keys
		 * were tracked reads back as a pointer with no migration code. */
		public static final int KIND_POINTER = 0;
		/** A stranded key press: emits monitor_key_up. */
		public static final int KIND_KEY = 1;

		/**
		 * ONE record type and ONE pending list for both, deliberately.
		 *
		 * Everything downstream of the strand is identical for a key and a pointer: park rather
		 * than emit, deliver at tick START, resolve the surface by address, require a listening
		 * unpaused machine, retry every tick, expire, cap, and ride writeToNBT across a save.
		 * Every one of those was got wrong at least once while the pointer path was being built
		 * — three designs over four review rounds — and a parallel set of key structures would
		 * be a second copy of all of them, free to drift. This codebase's most repeated defect
		 * is a rule applied at some of the places it is needed.
		 *
		 * The cost is a record where some fields do not apply: button/id/x/y are meaningless
		 * for a key, keycode/ch are meaningless for a pointer. Construct through the factories
		 * below so a caller never has to decide what to put in the unused ones.
		 */
		public final int kind;
		/** Who held it. Not needed to deliver — the signal names no player — kept for NBT
		 * debuggability and any future per-player accounting. */
		public final String watcher;
		/** POINTER only. */
		public final int button;
		/** POINTER only: the server-minted gesture id. */
		public final int id;
		public final String address;
		/** POINTER only. */
		public final int x;
		public final int y;
		/** KEY only: the LWJGL keycode, which is what identifies the key across down and up. */
		public final int keycode;
		/** KEY only: the character recorded at PRESS time — see keyRelease below for why. */
		public final int ch;
		/** World time when the press was recorded, for expiry. */
		public final long recordedAt;

		private PendingRelease(int kind, String watcher, int button, int id, String address,
				int x, int y, int keycode, int ch, long recordedAt) {
			this.kind = kind;
			this.watcher = watcher;
			this.button = button;
			this.id = id;
			this.address = address;
			this.x = x;
			this.y = y;
			this.keycode = keycode;
			this.ch = ch;
			this.recordedAt = recordedAt;
		}

		public static PendingRelease pointer(String watcher, int button, int id, String address,
				int x, int y, long recordedAt) {
			return new PendingRelease(KIND_POINTER, watcher, button, id, address, x, y,
					0, 0, recordedAt);
		}

		/**
		 * A stranded key.
		 *
		 * The char is the one recorded at PRESS time, not zero, because that is exactly what a
		 * real release carries: GuiScene keeps a keycode-to-char map and replays the press char
		 * on key-up (falling back to 0 only when it never saw the press). A synthesized release
		 * that sent 0 would be distinguishable from a real one, and a program matching presses
		 * to releases by char would not close the gesture it opened.
		 */
		public static PendingRelease key(String watcher, String address, int keycode, int ch,
				long recordedAt) {
			return new PendingRelease(KIND_KEY, watcher, 0, 0, address, 0, 0,
					keycode, ch, recordedAt);
		}
	}

	/**
	 * Hard cap on persisted pending releases. Three buttons times a plausible number of
	 * watchers around one GPU is far below this; anything approaching it is a bug or an
	 * attempt to grow our NBT. Enforced on READ as well as write — a hand-edited save must
	 * not be able to hand us an unbounded list.
	 */
	public static final int MAX_PENDING_RELEASES = 32;

	/**
	 * Seven in-game days. A record is only useful while the program still believes the
	 * button is down; past that the holder has almost certainly rebooted the computer, and
	 * an un-expiring record is a leak in the GPU's NBT that nothing would ever clear.
	 */
	public static final long PENDING_EXPIRY_TICKS = 24000L * 7L;

	private final java.util.List<PendingRelease> pendingReleases =
			new java.util.ArrayList<PendingRelease>();

	/**
	 * Set by every structural change to {@code activePointer} or {@code pendingReleases}.
	 *
	 * Both are now PERSISTED state, and a TileEntity whose chunk is never marked modified is
	 * never written — so without this a gesture could fail to reach disk, and the removal of
	 * a delivered record could fail to reach disk too, replaying that release on every later
	 * load. Kept here rather than at the call sites deliberately: there are seven places that
	 * mutate this state, from five callers, and "the guard applied at one of the several
	 * places it is needed" is this codebase's most repeated defect.
	 */
	private boolean persistenceDirty;

	/** Read-and-clear, folded into the tile entity's existing once-per-tick markDirty. */
	public boolean consumePersistenceDirty() {
		boolean was = persistenceDirty;
		persistenceDirty = false;
		return was;
	}

	/**
	 * The single transition out of {@code activePointer}: a gesture the client can no longer
	 * complete becomes a pending release, and the delivery loop owns everything after that.
	 *
	 * Nothing is emitted here, deliberately. `sendToReachable` queues a signal that the
	 * machine consumes on its next timeslice; a signal emitted in the final tick before a
	 * world save reaches disk still queued and is destroyed by OC's resume — measured, not
	 * theorised (see INPUT-GESTURE-PERSISTENCE.md). A record, by contrast, either gets
	 * delivered at the START of the next tick — before that tick's machine timeslice, so it
	 * is consumed the same tick — or reaches disk in writeToNBT and is delivered on load.
	 * Either way exactly one path is live at a time.
	 */
	private void moveToPending(String watcherKey, Pointer p) {
		addPending(PendingRelease.pointer(watcherOf(watcherKey), p.button, p.id,
				p.address, p.x, p.y, worldTimeForRecords));
	}

	/** The key half of the same single transition. See {@link #moveToPending(String, Pointer)}. */
	private void moveToPending(String watcherKey, HeldKey k) {
		addPending(PendingRelease.key(watcherOf(watcherKey), k.address, k.keycode, k.ch,
				worldTimeForRecords));
	}

	private void addPending(PendingRelease record) {
		// Capped in MEMORY, not just at the NBT boundary. Parking on an unreachable machine
		// made unbounded growth reachable: a client spamming presses while no machine can
		// hear the releases would otherwise grow this list by its full event allowance
		// every tick until expiry, hours later. Oldest dropped first, same policy as the
		// save path: the newest presses are the ones a program still waits on.
		//
		// Keys share the cap rather than getting their own, deliberately: the bound exists to
		// keep this GPU's NBT small, and that is a property of the total. Two caps of 32 would
		// be a 64-record list wearing a smaller number.
		while (pendingReleases.size() >= MAX_PENDING_RELEASES) {
			pendingReleases.remove(0);
		}
		pendingReleases.add(record);
		persistenceDirty = true;
	}

	/**
	 * World time as of the last tick, so {@code moveToPending} can stamp a record without
	 * every caller having to thread a clock down to it.
	 */
	private long worldTimeForRecords;

	public void setWorldTime(long worldTime) {
		worldTimeForRecords = worldTime;
	}

	/**
	 * Everything that must survive a reload: the live gestures plus any pending ones not
	 * yet delivered. Both go into the same list because on the far side of a save they are
	 * the same thing — a press with no release and no client left to send one.
	 */
	public java.util.List<PendingRelease> snapshotForSave(long worldTime) {
		java.util.List<PendingRelease> out =
				new java.util.ArrayList<PendingRelease>(pendingReleases);
		for (Map.Entry<String, Pointer> e : activePointer.entrySet()) {
			Pointer p = e.getValue();
			out.add(PendingRelease.pointer(watcherOf(e.getKey()), p.button, p.id, p.address,
					p.x, p.y, worldTime));
		}
		// Live keys ride the save exactly as live pointers do: on the far side of it they are
		// the same thing — a press with no release and no client left to send one.
		for (Map.Entry<String, HeldKey> e : heldKeys.entrySet()) {
			HeldKey k = e.getValue();
			out.add(PendingRelease.key(watcherOf(e.getKey()), k.address, k.keycode, k.ch,
					worldTime));
		}
		if (out.size() > MAX_PENDING_RELEASES) {
			// Oldest first: the newest presses are the ones a program is most likely to
			// still be waiting on.
			out = out.subList(out.size() - MAX_PENDING_RELEASES, out.size());
		}
		return out;
	}

	/** Restore from NBT. Capped here too, deliberately — see MAX_PENDING_RELEASES. */
	public void restorePending(java.util.List<PendingRelease> records) {
		pendingReleases.clear();
		int from = Math.max(0, records.size() - MAX_PENDING_RELEASES);
		pendingReleases.addAll(records.subList(from, records.size()));
		// Dirty only when memory now disagrees with disk. A truncated restore must persist
		// the truncation, and a non-empty list is about to mutate on delivery anyway; but
		// the common case — a chunk that loaded with no held gestures — must NOT be marked
		// modified just for having been read.
		persistenceDirty = from > 0 || !pendingReleases.isEmpty();
	}

	public int pendingReleaseCount() {
		return pendingReleases.size();
	}

	/**
	 * The id counter is persisted alongside the records because ids only mean anything
	 * relative to it. It restarts at 1 per router instance, so without this a fresh press
	 * after a reload could mint the very id a pending record still carries — two different
	 * gestures, same id, and a program keying on ids cannot tell the release apart from a
	 * duplicate. Clamped on restore: ids are positive, and a corrupt value must not park
	 * the counter at the wrap boundary.
	 */
	public int pointerIdCounter() {
		return nextPointerId;
	}

	public void restorePointerIdCounter(int value) {
		nextPointerId = value > 0 ? value : 1;
	}

	/**
	 * THE delivery loop — the only place a stranded gesture's release is ever emitted, apart
	 * from the two node-death call sites documented at their callers.
	 *
	 * Called at the START phase of every server tick, which is the timing the whole design
	 * rests on: a signal emitted at START is consumed by the machine's timeslice later in
	 * the SAME tick, before any point at which that tick can save the world. Emitting at the
	 * END phase — or synchronously at a transition, which is END-adjacent — is how the two
	 * previous designs both lost the quit-to-title case: the signal sat queued across the
	 * save and OC's resume destroyed it. Deliver-at-START means a record is only removed
	 * once its signal is inside the same tick as the machine run that consumes it; if the
	 * server never reaches that tick, the record is still here for writeToNBT.
	 *
	 * Kept on a failed send: a failed send emitted nothing to duplicate, so the retry next
	 * tick is a first delivery. The usual failure is a surface whose chunk is not loaded;
	 * the record waits for it, bounded by the expiry below.
	 *
	 * @return how many releases were emitted
	 */
	public int flushPending(Node fromNetwork, int sceneWidth, int sceneHeight, long worldTime) {
		if (pendingReleases.isEmpty()) {
			return 0;
		}
		int sent = 0;
		java.util.Iterator<PendingRelease> it = pendingReleases.iterator();
		while (it.hasNext()) {
			PendingRelease r = it.next();
			// A record from the FUTURE is dropped unconditionally — a restored backup moved
			// the clock backwards, and keeping it risks a record that never expires.
			if (worldTime < r.recordedAt) {
				it.remove();
				persistenceDirty = true;
				continue;
			}
			// Delivery is attempted BEFORE expiry. The expiry clock is total world time,
			// which on a busy server accrues regardless of whether the holder's machine
			// chunk ever loads — seven in-game days is only ~2.3 real hours of uptime, and
			// expiring a record that would have delivered on this very attempt drops a
			// release for being patient. A stale-but-deliverable release lands as an
			// unmatched monitor_up, which the caveats already class as harmless; a dropped
			// one is the stuck button this whole design exists to end. Expiry now only
			// bounds the genuinely undeliverable.
			if (emitRelease(r, fromNetwork, sceneWidth, sceneHeight)) {
				it.remove();
				persistenceDirty = true;
				sent++;
			} else if (worldTime - r.recordedAt > PENDING_EXPIRY_TICKS) {
				it.remove();
				persistenceDirty = true;
			}
		}
		return sent;
	}

	/**
	 * Deliver only the records addressed to ONE surface, now — for the screen-removal path,
	 * where that address dies with the block and parking would mean waiting on a node that
	 * can never resolve again. Scoped so the rest of the pending list keeps its START-phase
	 * delivery guarantee: force-delivering unrelated records mid-tick would hand them the
	 * save race the START timing exists to avoid.
	 */
	public int flushPendingFor(String address, Node fromNetwork, int sceneWidth,
			int sceneHeight) {
		if (address == null || pendingReleases.isEmpty()) {
			return 0;
		}
		int sent = 0;
		java.util.Iterator<PendingRelease> it = pendingReleases.iterator();
		while (it.hasNext()) {
			PendingRelease r = it.next();
			if (!address.equals(r.address)) {
				continue;
			}
			if (emitRelease(r, fromNetwork, sceneWidth, sceneHeight, true)) {
				it.remove();
				persistenceDirty = true;
				sent++;
			}
		}
		return sent;
	}

	/** Every pending record, urgently — for GPU destruction, where they die with this router. */
	public int flushPendingLastChance(Node fromNetwork, int sceneWidth, int sceneHeight) {
		int sent = 0;
		java.util.Iterator<PendingRelease> it = pendingReleases.iterator();
		while (it.hasNext()) {
			PendingRelease r = it.next();
			if (emitRelease(r, fromNetwork, sceneWidth, sceneHeight, true)) {
				it.remove();
				persistenceDirty = true;
				sent++;
			}
		}
		return sent;
	}

	public int flushWatcher(String watcherKey) {
		int moved = 0;
		for (int button = 0; button <= 2; button++) {
			moved += flushPointer(watcherKey, button);
		}
		// Keys cannot be swept by iterating their space the way buttons are — a keycode is 0..255
		// and only a handful are ever held — so this walks the map and matches the watcher. Uses
		// watcherOf rather than a prefix test on purpose: a startsWith would also match a watcher
		// whose UUID string happens to be a prefix of another's.
		java.util.Iterator<Map.Entry<String, HeldKey>> keys = heldKeys.entrySet().iterator();
		while (keys.hasNext()) {
			Map.Entry<String, HeldKey> entry = keys.next();
			if (!watcherKey.equals(watcherOf(entry.getKey()))) {
				continue;
			}
			HeldKey k = entry.getValue();
			keys.remove();
			moveToPending(entry.getKey(), k);
			moved++;
		}
		// DELIBERATELY does not touch eventsThisTick. It used to, back when the only caller was
		// disconnect eviction and clearing a departing player's counter looked tidy. It is now
		// called from onInput's rejecting gates, and one of those gates turns on `input.epoch`
		// — a field read straight off the wire. Resetting the allowance there would let a
		// client restore its own rate limit on demand by sending a deliberately stale epoch
		// between bursts, which defeats the one bound on how many signals a player can force
		// into every machine on the network.
		//
		// It was never load-bearing anyway: beginTick() clears the whole map once per tick.
		return moved;
	}

	/**
	 * End ONE button's gesture, PER BUTTON deliberately: slots are keyed per button because
	 * two-button drags are ordinary (see route()); a gate rejecting button 1 says nothing
	 * about button 0, and ending all three would kill a live drag the player is holding.
	 *
	 * A previous design emitted here and had to reason about failed sends — the slot
	 * survived them, so the next gated event could retry. That entire class of reasoning is
	 * gone: moving to pending cannot fail, and the delivery loop retries every tick, which
	 * is strictly more often than "whenever the client happens to send another event".
	 *
	 * @return 1 if a gesture was moved to pending, 0 otherwise
	 */
	public int flushPointer(String watcherKey, int button) {
		String slot = pointerSlot(watcherKey, button);
		Pointer p = activePointer.remove(slot);
		if (p == null) {
			return 0;
		}
		moveToPending(slot, p);
		return 1;
	}

	/**
	 * End ONE key, the exact mirror of {@link #flushPointer}.
	 *
	 * Per keycode for the same reason that one is per button: the gate that calls this fires per
	 * EVENT, so ending every key a watcher holds would kill the others the instant a player
	 * crossed the reach boundary — jitter at exactly 8 blocks, a shove, one step back. A release
	 * is the one event meaning that key is over.
	 *
	 * This is the FIFTH flush site, and it was missing from the first draft of the key work: the
	 * reach gate released pointers and let keys fall through with their slot still set, so a
	 * player displaced past 8 blocks mid-press — a minecart, knockback, water, a piston, a
	 * teleport — left the key held in Lua until something else swept it. Nothing about the key
	 * path made that visible; the comment above the gate asserted keys had no state to release
	 * from, which had just stopped being true.
	 *
	 * @return 1 if a key was moved to pending, 0 otherwise
	 */
	public int flushKey(String watcherKey, int keycode) {
		String slot = keySlot(watcherKey, keycode);
		HeldKey k = heldKeys.remove(slot);
		if (k == null) {
			return 0;
		}
		moveToPending(slot, k);
		return 1;
	}

	/**
	 * Send one server-originated {@code monitor_up}, as UNCHECKED {@code computer.signal}.
	 *
	 * Unchecked is a decision, not an omission, and this method is its whole blast radius —
	 * it emits this one signal name with server-owned arguments and must never be
	 * generalised into a way to send anything else. The reasoning: {@code checked_signal}
	 * needs a live EntityPlayer, and that requirement is what forced every previous design
	 * to either emit at transitions (lost across saves — measured) or wait for the holder to
	 * log back in (id races, login hooks, Netty-thread hazards, and on a server a program
	 * stuck for days). The check itself bought nothing here: the matching press was already
	 * admitted through canInteract, the coordinates are the server's own press-time record
	 * clamped to the live scene, the id is server-minted, and the signal can only ever CLOSE
	 * a gesture. OC draws the same line for its own signals — {@code touch} is checked,
	 * {@code walk}/{@code motion}/{@code redstone_changed} are not — and a stranded-gesture
	 * release is a state transition, not live input.
	 *
	 * Deliberately NOT rate-limited and not reach-checked. Neither guard applies: this is
	 * not client traffic, and the bounded quantity is the number of presses the server
	 * itself admitted through both guards.
	 */
	private boolean emitRelease(Pointer p, Node fromNetwork, int sceneWidth, int sceneHeight) {
		return emitRelease(PendingRelease.pointer(null, p.button, p.id, p.address, p.x, p.y, 0L),
				fromNetwork, sceneWidth, sceneHeight, false);
	}

	private boolean emitRelease(PendingRelease r, Node fromNetwork, int sceneWidth,
			int sceneHeight) {
		return emitRelease(r, fromNetwork, sceneWidth, sceneHeight, false);
	}

	/**
	 * @param lastChance true at the node-death sites, where parking means waiting on an
	 *                   address that will never resolve again: there the queued-signal risk
	 *                   of sending into a paused machine beats the certain strand of not
	 *                   sending at all. A machine must still be reachable either way — with
	 *                   nobody listening there is nothing to send into.
	 */
	private boolean emitRelease(PendingRelease r, Node fromNetwork, int sceneWidth,
			int sceneHeight, boolean lastChance) {
		if (fromNetwork == null || fromNetwork.network() == null) {
			return false;
		}
		// Resolved BY ADDRESS at send time, never from a Node captured at the press. Holding a
		// Node would pin the screen's TileEntity — and through it a chunk — for as long as a
		// gesture sat unreleased, and would keep sending through an object that may since have
		// been removed and replaced. Every other cross-object reference in this codebase is an
		// address for the same reason. A null here is the surface having genuinely gone, which
		// is the one case that stays undeliverable.
		Node target = fromNetwork.network().node(r.address);
		if (target == null || target.address() == null) {
			return false;
		}
		// A resolved SCREEN proves nothing about the MACHINE, and the machine is the only
		// consumer this signal has. An unloaded computer chunk removes its node from the
		// network, so sendToReachable would deliver to nobody — and an unloaded machine is
		// saved RUNNING (the unload save runs before the TileEntity hook that would stop
		// it), so its persisted Lua state still holds this very gesture. Counting that send
		// as success deleted the record and reproduced the original defect via an ordinary
		// walk out of view distance. Park until a machine can hear it.
		//
		// Paused machines park too: after a world load every machine sits in OC's startup
		// delay, and a signal queued into a paused machine can be captured by a save while
		// still queued — the exact resume-destruction this design exists to avoid. The
		// delivery loop retries every tick; pauses are ticks long, the expiry is days.
		boolean machineListening = false;
		for (Node n : target.reachableNodes()) {
			if (n.host() instanceof li.cil.oc.api.machine.Machine) {
				if (!lastChance && ((li.cil.oc.api.machine.Machine) n.host()).isPaused()) {
					return false;
				}
				machineListening = true;
			}
		}
		if (!machineListening) {
			return false;
		}
		// Clamped to the LIVE scene size, not the size at press time. setResolution can shrink
		// the scene while a gesture is held, and a release outside the canvas is exactly what
		// route()'s own bounds check refuses to let a client send.
		//
		// Keys carry no coordinates, so nothing to clamp — monitor_key_up is (char, keycode),
		// the same shape the client sends. Everything ABOVE this point is deliberately shared:
		// resolving the surface by address, requiring a listening unpaused machine, and the
		// last-chance override are properties of "a release the client can no longer send",
		// not of what kind of release it is.
		int x = sceneWidth > 0 ? Math.max(0, Math.min(sceneWidth - 1, r.x)) : r.x;
		int y = sceneHeight > 0 ? Math.max(0, Math.min(sceneHeight - 1, r.y)) : r.y;
		// Guarded because this fans out through every reachable node's onMessage, including
		// third-party components, and it now runs from the tick-START loop: an escaping
		// throw there is a crash, and a crash with the record persisted is a crash on every
		// subsequent load of the world. Treated as SENT on a throw, deliberately — the
		// foreach may have already delivered to the machine before the throwing host, and
		// retrying a throwing handler every tick would just crash-spam; a possibly-lost
		// release on a network with a broken third-party component is the lesser harm.
		try {
			if (r.kind == PendingRelease.KIND_KEY) {
				// Argument order matches the client's own key events: (char, keycode).
				target.sendToReachable("computer.signal", "monitor_key_up",
						Integer.valueOf(r.ch), Integer.valueOf(r.keycode));
			} else {
				target.sendToReachable("computer.signal", "monitor_up",
						Integer.valueOf(x), Integer.valueOf(y),
						Integer.valueOf(r.button), Integer.valueOf(r.id));
			}
		} catch (RuntimeException e) {
			opengpu.OpenGPU.logger.warn("v2: a component on screen " + r.address
					+ "'s network threw while receiving a release", e);
		}
		return true;
	}

	/**
	 * @param screen  the surface the input landed on, whose node sends the signal
	 * @param player  the originating player, for OC's permission check
	 * @return true when the input was ACCEPTED — for most kinds that means a signal was emitted
	 *         now; for a pointer MOVE it means the position was recorded and the tick flush will
	 *         emit at most one coalesced monitor_move for the gesture (see Pointer#moveNode)
	 */
	public boolean route(MessageCodec.Input input, String watcherKey, EntityPlayer player,
			TileEntityScreen2 screen, int sceneWidth, int sceneHeight) {
		if (screen == null || player == null) {
			return false;
		}
		Node node = screen.node();
		if (node == null || node.address() == null) {
			return false;
		}
		// Rate limit before any work: input is the one inbound path a client can drive at
		// will, and a signal flood would stall every machine on the network.
		//
		// A RELEASE is exempt. Dropping one leaves activePointer set forever: Lua sees a
		// press that never ends, and the next press reuses the stale slot. Releases are
		// self-limiting anyway — there can only be as many as there were presses, and those
		// ARE capped.
		// INPUT_KEY_UP is exempt for exactly the same reason, and was not until 2026-08-08:
		// dropping a key release leaves heldKeys set forever, so Lua sees a key that never comes
		// up and the next press of that key finds a stale slot. Key releases are self-limiting
		// too — one per press, and presses ARE capped.
		boolean isRelease = input.kind == MessageCodec.INPUT_POINTER_UP
				|| input.kind == MessageCodec.INPUT_KEY_UP;
		if (!isRelease) {
			Integer used = eventsThisTick.get(watcherKey);
			int count = used == null ? 0 : used;
			if (count >= MAX_EVENTS_PER_WATCHER_PER_TICK) {
				return false;
			}
			eventsThisTick.put(watcherKey, count + 1);
		}

		switch (input.kind) {
			case MessageCodec.INPUT_POINTER_DOWN:
			case MessageCodec.INPUT_POINTER_MOVE:
			case MessageCodec.INPUT_POINTER_UP: {
				int px = input.a, py = input.b;
				if (!inBounds(px, py, sceneWidth, sceneHeight)) {
					// A RELEASE is CLAMPED, never dropped — the same reasoning as the
					// rate-limit exemption above, which this check silently undid: returning
					// here skips the activePointer.remove() below, so Lua sees a press that
					// never ends and the next press reuses the stale slot.
					//
					// Out-of-bounds became reachable when the scene size stopped being a
					// constant. The server applies setResolution the instant Lua calls it (a
					// direct callback), while the client keeps mapping clicks through the old
					// size until the batch is sealed, shipped, and rendered — a window of a
					// tick plus latency plus a frame, easily long enough to release in.
					if (!isRelease) {
						return false;
					}
					px = Math.max(0, Math.min(sceneWidth - 1, px));
					py = Math.max(0, Math.min(sceneHeight - 1, py));
				}
				if (input.c < 0 || input.c > 2)
					return false; // left/right/middle only
				// Keyed per BUTTON, not just per watcher: a second button pressed during a
				// drag would otherwise overwrite the first one's id, so the first press
				// could never be released and its move/up events would carry the wrong
				// gesture. Two-button drags are ordinary in a paint program.
				String slot = pointerSlot(watcherKey, input.c);
				int pointerId;
				if (input.kind == MessageCodec.INPUT_POINTER_DOWN) {
					// Drain deliverable pending releases BEFORE minting a new id — at the
					// one chokepoint both entry points (onInput, onSurfaceClick) share, so
					// the up-before-down ordering cannot be lost at "one of the several
					// places it is needed". The START-phase loop normally empties this
					// list ticks earlier; the window this covers is a gesture stranded by
					// a transition in THIS tick (a Lua unbind between drains) followed by
					// a press in the same tick, where delivering the old up after the new
					// down would read as an id mismatch on a held slot.
					if (!pendingReleases.isEmpty()) {
						flushPending(node, sceneWidth, sceneHeight, worldTimeForRecords);
					}
					// An occupied slot means a press whose release never arrived — a lost
					// packet, or one of the gated paths. Overwriting it silently orphaned the
					// incumbent: Lua kept the old id held forever while a new id started on
					// the same button. END it before reusing the slot.
					//
					// The one flush that still tries a SYNCHRONOUS emit first, for ordering:
					// the incumbent's up should reach Lua before the new press's down, and
					// this method is about to emit that down. Pending delivery would arrive a
					// tick later — after the down — and read as an id mismatch on a slot the
					// program is actively holding. Only if the incumbent's surface cannot be
					// resolved right now does it fall back to pending, where the mismatch is
					// the price of not losing the release entirely.
					Pointer incumbent = activePointer.remove(slot);
					persistenceDirty = true;
					if (incumbent != null && !emitRelease(incumbent, node, sceneWidth, sceneHeight)) {
						moveToPending(slot, incumbent);
					}
					pointerId = nextPointerId++;
					if (nextPointerId <= 0) {
						nextPointerId = 1; // wrap: ids are opaque and short-lived
					}
					// The ADDRESS is captured here, at the press, because that is the surface
					// the gesture belongs to. Everything that later ends this gesture must
					// release against it and not against whatever the GPU is bound to by then.
					activePointer.put(slot, new Pointer(pointerId, input.c, node.address(), px, py));
					persistenceDirty = true;
				} else {
					Pointer active = activePointer.get(slot);
					// A move or release with no press behind it is either a lost packet or a
					// forged one; either way there is no gesture to attribute it to.
					if (active == null)
						return false;
					// THE SAME RULE THIS FILE STATES FOR THE FLUSH PATH, ENFORCED HERE TOO — and
					// this is the path that carries almost every real release. The binding can
					// move under a live gesture without any surface being destroyed: when a
					// bound screen is absorbed into a larger wall it is demoted to a satellite
					// and TileEntityGpu2.resolveScreenLocked deliberately FOLLOWS the new
					// origin. Emitting through whatever screen is bound now would hand Lua a
					// monitor_up for a surface it never received a monitor_down from, while the
					// real gesture stayed held forever. Release the surface it actually belongs
					// to and refuse to fabricate one on the new surface.
					if (!active.address.equals(node.address())) {
						// Stranded on its old surface: move it to pending, where the delivery
						// loop retries every tick — strictly more often than the previous
						// design's "whenever the client sends another event on this button",
						// which could be never. The event being routed is refused either way;
						// this check exists precisely to not fabricate it on the new surface.
						activePointer.remove(slot);
						moveToPending(slot, active);
						return false;
					}
					pointerId = active.id;
					// Track where the gesture is, so a server-originated release lands at the
					// last position the player actually reached rather than at the origin.
					active.x = px;
					active.y = py;
					if (input.kind == MessageCodec.INPUT_POINTER_MOVE) {
						// PENDED, not emitted — the tick flush sends at most one move per
						// gesture, with whatever position is newest by then. See
						// Pointer.moveNode for the measurements this rests on. The packet
						// still charged the per-tick allowance above: coalescing bounds what
						// LUA receives, and must not widen what a client may SEND.
						active.moveNode = node;
						active.movePlayer = player;
						return true;
					}
					// UP: removing the record also discards any pending move, and that is
					// the ordering guarantee — this release carries the final coordinates
					// itself, and a stale move flushed after it would run the gesture
					// backwards.
					activePointer.remove(slot);
					persistenceDirty = true;
				}
				String name = input.kind == MessageCodec.INPUT_POINTER_DOWN ? "monitor_down"
						: "monitor_up";
				node.sendToReachable("computer.checked_signal", player, name,
						Integer.valueOf(px), Integer.valueOf(py),
						Integer.valueOf(input.c), Integer.valueOf(pointerId));
				return true;
			}
			case MessageCodec.INPUT_SCROLL: {
				if (!inBounds(input.a, input.b, sceneWidth, sceneHeight))
					return false;
				if (input.c != 1 && input.c != -1)
					return false; // one notch at a time; a client cannot amplify a scroll
				// Address-checked like the move/release path: reporting a gesture's id on a
				// scroll over a different surface would tell a program a scroll belonged to
				// a drag on a screen it is not looking at. Zero, meaning "no gesture", is
				// the honest answer.
				Pointer active = activePointer.get(pointerSlot(watcherKey, 0));
				int scrollGesture = active != null && active.address.equals(node.address())
						? active.id : 0;
				node.sendToReachable("computer.checked_signal", player, "monitor_scroll",
						Integer.valueOf(input.a), Integer.valueOf(input.b),
						Integer.valueOf(input.c), Integer.valueOf(scrollGesture));
				return true;
			}
			case MessageCodec.INPUT_KEY_DOWN:
			case MessageCodec.INPUT_KEY_UP: {
				// Deliberately NOT named key_down/key_up: OpenOS binds those for real
				// keyboards, and the legacy mod's collision with them (with a different
				// argument shape) was a documented interop hazard.
				if (input.a < 0 || input.a > 0xFFFF || input.b < 0 || input.b > 0xFF)
					return false;
				boolean down = input.kind == MessageCodec.INPUT_KEY_DOWN;
				String slot = keySlot(watcherKey, input.b);
				if (down) {
					// AUTO-REPEAT IS THE COMMON CASE, not a defensive edge. Minecraft enables
					// keyboard repeat while a GUI is open (so text fields work), so a held key
					// re-enters keyTyped many times a second: a 6-second hold of W was measured
					// in game as 42 repeats, all arriving as KEY_DOWN for keycode 17.
					//
					// Overwrite, therefore, and never create a second record: ONE PHYSICAL KEY
					// OWES EXACTLY ONE RELEASE. Same keycode, same surface, and the newest char
					// is the one a release should replay. Treating a repeat as a fresh press
					// would put 42 records in the pending list for one key and deliver 42
					// releases for one press.
					//
					// (An earlier version of this comment asserted repeats could not happen,
					// reasoning from the absence of an enableRepeatEvents call in our own client
					// code. That call is Minecraft's to make, not ours; the in-game measurement
					// is what settled it.)
					heldKeys.put(slot, new HeldKey(input.b, input.a, node.address()));
					persistenceDirty = true;
				} else {
					HeldKey held = heldKeys.remove(slot);
					// A RELEASE WITH NO PRESS BEHIND IT IS REFUSED, exactly as the pointer path
					// refuses one, and this is what makes the rate-limit exemption above SAFE
					// rather than a hole. INPUT_KEY_UP is exempt from
					// MAX_EVENTS_PER_WATCHER_PER_TICK — which is the only inbound throttle in the
					// stack — so without this a client could emit unlimited monitor_key_up
					// signals into every machine on the network by never sending a press. The
					// exemption's justification is "one per press, and presses ARE capped"; this
					// line is what makes that true instead of merely stated.
					//
					// It also removes the DOUBLE RELEASE. Once a sweep has moved the key to a
					// pending record, the slot is gone, so the player's real key-up on the way
					// back finds nothing and stays quiet — rather than delivering a second
					// monitor_key_up, through whatever surface is bound by then.
					if (held == null) {
						return false;
					}
					persistenceDirty = true;
					// Address-checked like the pointer release path, and for the same reason: a
					// binding can move under a held key without any surface being destroyed, and
					// emitting through the CURRENT screen would deliver a release naming a
					// surface the player never pressed on, while the real one stayed stuck. Park
					// it instead — the delivery loop will send it from the right node.
					if (!held.address.equals(node.address())) {
						moveToPending(slot, held);
						return false;
					}
				}
				node.sendToReachable("computer.checked_signal", player,
						down ? "monitor_key_down" : "monitor_key_up",
						Integer.valueOf(input.a), Integer.valueOf(input.b));
				return true;
			}
			default:
				return false; // decodeInput already rejects unknown kinds
		}
	}

	private static boolean inBounds(int x, int y, int width, int height) {
		return x >= 0 && y >= 0 && x < width && y < height;
	}
}
