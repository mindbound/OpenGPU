package opengpu.v2.mc.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import li.cil.oc.api.network.Network;
import li.cil.oc.api.network.Node;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * The first test this class has ever had, and it covers exactly the half a unit test CAN cover.
 *
 * {@code route()} takes {@code TileEntityScreen2} and a live {@code EntityPlayer} — Minecraft
 * concretes that cannot be constructed without bootstrapping the game — which is why InputRouter
 * had no unit test until now. The FLUSH side touches only {@link InputRouter.Pointer} and the OC
 * {@code Node} INTERFACE, so it is tested here, through the package-private seam, on
 * OcslDiagnostics.slot()'s precedent.
 *
 * The PEND side is not covered here and is NOT yet covered in game either, which is worth saying
 * precisely because it would be easy to assume otherwise: ingame/uidemo.lua is cited throughout
 * this change, but all six of its runs measured the UNCOALESCED emitter. They are what motivated
 * coalescing, not evidence that coalescing works. Confirming the drag lag is actually gone needs a
 * fresh in-game run against a build containing this commit.
 *
 * What must hold: at most one monitor_move per gesture per tick, carrying the NEWEST position and
 * the gesture's OWN button and id; a flush is once per tick even if beginTick is called twice; a
 * gesture that left the map takes its pending move with it; a dead node drops its move without
 * retrying forever; a move that the live scene size no longer contains is dropped rather than
 * clamped.
 *
 * What is NOT covered here, stated so it is not mistaken for covered:
 * <ul>
 * <li>The whole PEND side — that a MOVE stops emitting inline, returns true, and still charges the
 *     rate limit. All of it is inside route(), behind the MC concretes.</li>
 * <li>The ORDERING of a coalesced move against its own monitor_up. What this file pins is the
 *     mechanism removal relies on (a record out of the map emits nothing); that route()'s UP case
 *     really does remove before emitting is a property of route(), and unverifiable here.</li>
 * </ul>
 */
public class InputRouterTest {

	/**
	 * A scene size big enough that no test's coordinates fall outside it by accident — and
	 * deliberately NOT square, so a width/height transposition in the bounds check cannot hide.
	 */
	private static final int W = 200;
	private static final int H = 150;

	/** One recorded sendToReachable invocation. */
	private static final class Sent {
		final String signal;
		final Object[] args;

		Sent(String signal, Object[] args) {
			this.signal = signal;
			this.args = args;
		}
	}

	/**
	 * A recording {@code Node} via dynamic proxy, because hand-implementing the OC interfaces
	 * means ~15 stub methods each for the two of them. The proxy records sendToReachable,
	 * answers network() with a proxied Network (or null, for the dead-node case), and returns
	 * neutral values for everything else.
	 */
	private static Node mockNode(final List<Sent> sink, final boolean alive, final String address) {
		final Network network = alive
				? (Network) Proxy.newProxyInstance(Network.class.getClassLoader(),
						new Class<?>[] { Network.class }, new InvocationHandler() {
							public Object invoke(Object proxy, Method m, Object[] args) {
								return neutral(m.getReturnType());
							}
						})
				: null;
		return (Node) Proxy.newProxyInstance(Node.class.getClassLoader(),
				new Class<?>[] { Node.class }, new InvocationHandler() {
					public Object invoke(Object proxy, Method m, Object[] args) {
						if ("sendToReachable".equals(m.getName())) {
							Object[] rest = (Object[]) args[1];
							sink.add(new Sent((String) args[0], rest));
							return null;
						}
						if ("network".equals(m.getName())) {
							return network;
						}
						if ("address".equals(m.getName())) {
							return address;
						}
						return neutral(m.getReturnType());
					}
				});
	}

	static Object neutral(Class<?> type) {
		if (type == boolean.class) return Boolean.FALSE;
		if (type == int.class) return Integer.valueOf(0);
		if (type == double.class) return Double.valueOf(0);
		return null;
	}

	/**
	 * An EntityPlayer-typed identity token, obtained without running a constructor.
	 *
	 * The flush forwards this reference into checked_signal's player slot and never calls a
	 * method on it, so an uninitialized instance is enough — and it is the only kind obtainable
	 * here. EntityPlayer is abstract; EntityPlayerMP's constructor wants a live MinecraftServer,
	 * WorldServer and GameProfile; the dynamic-proxy trick used above works on interfaces only;
	 * and no mocking library is on the test classpath.
	 *
	 * Worth the ugliness because the alternative is a hole with teeth. OC matches
	 * checked_signal's first element with a Scala typed pattern, which excludes null, and an
	 * unmatched signal is dropped SILENTLY — so a mutation that nulls this argument would break
	 * every drag in game (down arrives, moves vanish, up arrives) while leaving a suite that
	 * never inspects that argument entirely green.
	 */
	private static EntityPlayer sentinelPlayer() throws Exception {
		Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		f.setAccessible(true);
		sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
		return (EntityPlayer) unsafe.allocateInstance(EntityPlayerMP.class);
	}

	/** Plant a live gesture with a pending move, the state route()'s MOVE case leaves behind. */
	private static InputRouter.Pointer plant(InputRouter router, String slot, int id, int button,
			Node node, EntityPlayer player, int x, int y) {
		InputRouter.Pointer p = new InputRouter.Pointer(id, button, "screen-addr", x, y);
		p.moveNode = node;
		p.movePlayer = player;
		router.activePointer.put(slot, p);
		return p;
	}

	@Test
	public void theFlushEmitsOneMoveWithTheNewestPositionAndOnlyOnce() throws Exception {
		InputRouter router = new InputRouter();
		List<Sent> sent = new ArrayList<Sent>();
		Node node = mockNode(sent, true, "screen-addr");
		EntityPlayer player = sentinelPlayer();
		InputRouter.Pointer p = plant(router, "watcher#0", 7, 0, node, player, 10, 12);

		// Several coordinate updates between flushes — what a burst of move packets does. Only
		// the record's CURRENT position may reach the wire.
		p.x = 40;
		p.y = 44;

		router.flushCoalescedMoves(W, H);
		assertEquals("one gesture, one move", 1, sent.size());
		Sent s = sent.get(0);
		assertEquals("computer.checked_signal", s.signal);
		// The player slot. OC drops a checked_signal whose first element is not an EntityPlayer,
		// so this argument is the difference between a working drag and a silent one.
		assertNotNull("the player argument is forwarded, not nulled", s.args[0]);
		// `==` inside assertTrue rather than assertSame, deliberately. assertSame's failure path
		// string-concatenates both objects, which calls Entity.toString() -> getCommandSenderName()
		// -> the GameProfile that an allocateInstance'd sentinel does not have: a genuine
		// mismatch would surface as an NPE with no message instead of the assertion. Same reason
		// assertEquals is avoided on these — Entity.equals is identity anyway.
		assertTrue("and it is the gesture's OWN player", player == s.args[0]);
		assertEquals("monitor_move", s.args[1]);
		assertEquals("the NEWEST x", Integer.valueOf(40), s.args[2]);
		assertEquals("the NEWEST y", Integer.valueOf(44), s.args[3]);
		assertEquals("the gesture's button", Integer.valueOf(0), s.args[4]);
		assertEquals("the gesture's id", Integer.valueOf(7), s.args[5]);

		// Clean now: a second flush emits nothing. A flush that re-emitted would double the
		// rate this mechanism exists to bound.
		router.flushCoalescedMoves(W, H);
		assertEquals("a clean gesture emits nothing", 1, sent.size());
		assertTrue("and the pending move is consumed", p.moveNode == null);
	}

	@Test
	public void twoGesturesFlushIndependently() throws Exception {
		InputRouter router = new InputRouter();
		List<Sent> sent = new ArrayList<Sent>();
		Node node = mockNode(sent, true, "screen-addr");
		EntityPlayer p1 = sentinelPlayer();
		EntityPlayer p2 = sentinelPlayer();
		plant(router, "watcher#0", 1, 0, node, p1, 5, 6);
		plant(router, "watcher#1", 2, 1, node, p2, 50, 60);

		router.flushCoalescedMoves(W, H);
		assertEquals("both gestures emitted", 2, sent.size());
		// Order over a HashMap is unspecified; identify by gesture id. EVERY field is checked
		// per gesture, not just the id: the two gestures differ in x, y, button AND player
		// precisely so that a field read from the wrong map entry — or hardcoded, which is what
		// a button asserted only where it happens to be 0 permits — cannot pass.
		boolean saw1 = false, saw2 = false;
		for (Sent s : sent) {
			int id = ((Integer) s.args[5]).intValue();
			if (id == 1) {
				saw1 = true;
				assertTrue("gesture 1's player", p1 == s.args[0]);
				assertEquals("gesture 1's x", Integer.valueOf(5), s.args[2]);
				assertEquals("gesture 1's y", Integer.valueOf(6), s.args[3]);
				assertEquals("gesture 1's button", Integer.valueOf(0), s.args[4]);
			} else if (id == 2) {
				saw2 = true;
				assertTrue("gesture 2's player", p2 == s.args[0]);
				assertEquals("gesture 2's x", Integer.valueOf(50), s.args[2]);
				assertEquals("gesture 2's y", Integer.valueOf(60), s.args[3]);
				// The discriminating one: button 1, so a hardcoded 0 cannot survive. A
				// right-button drag reporting button 0 on every move would give Lua an
				// internally inconsistent gesture — down(1), move(0)…, up(1).
				assertEquals("gesture 2's button", Integer.valueOf(1), s.args[4]);
			}
		}
		assertTrue("gesture 1 flushed", saw1);
		assertTrue("gesture 2 flushed", saw2);
	}

	@Test
	public void aGestureThatLeftTheMapTakesItsPendingMoveWithIt() throws Exception {
		// The mechanism the UP-ordering guarantee rests on. route()'s UP case removes the record
		// and emits the release immediately with the final coordinates; a pending move flushed
		// AFTER that release would run the gesture backwards. This pins the half that lives on
		// this side of the seam — that a removed record emits nothing. That route() really does
		// remove before it emits is route()'s property, and not verifiable here.
		InputRouter router = new InputRouter();
		List<Sent> sent = new ArrayList<Sent>();
		Node node = mockNode(sent, true, "screen-addr");
		plant(router, "watcher#0", 3, 0, node, sentinelPlayer(), 20, 20);

		router.activePointer.remove("watcher#0");
		router.flushCoalescedMoves(W, H);
		assertEquals("no orphan move after the gesture ended", 0, sent.size());
	}

	@Test
	public void aDeadNodeDropsItsMoveWithoutRetrying() throws Exception {
		// The surface died between the pend and the flush. The move is dropped, and dropped
		// ONCE — the pending reference is consumed even though nothing was sent, or a dead
		// surface would queue a retry every tick forever.
		InputRouter router = new InputRouter();
		List<Sent> sent = new ArrayList<Sent>();
		Node dead = mockNode(sent, false, "screen-addr");
		InputRouter.Pointer p = plant(router, "watcher#0", 4, 0, dead, sentinelPlayer(), 8, 9);

		router.flushCoalescedMoves(W, H);
		assertEquals("nothing reaches a dead node", 0, sent.size());
		assertTrue("and the move does not retry forever", p.moveNode == null);

		router.flushCoalescedMoves(W, H);
		assertEquals(0, sent.size());
	}

	@Test
	public void aMoveTheLiveSceneNoLongerContainsIsDroppedNotClamped() throws Exception {
		// Deferring the emission is what makes this reachable at all: Lua's setResolution
		// applies the instant it is called, from the machine thread, so the scene can shrink
		// between route() pending this move and the flush emitting it. route() DROPS an
		// out-of-bounds move (clamping is for releases, which must not be lost), so the flush
		// has to drop it too — otherwise coalescing emits the one thing route() promises never
		// to emit, a monitor_move outside the current resolution.
		InputRouter router = new InputRouter();
		List<Sent> sent = new ArrayList<Sent>();
		Node node = mockNode(sent, true, "screen-addr");
		InputRouter.Pointer p = plant(router, "watcher#0", 5, 0, node, sentinelPlayer(), 40, 44);

		router.flushCoalescedMoves(30, 20);
		// Asserting the ABSENCE of a signal, not a coordinate range: a clamping implementation
		// would emit (29, 19), which any range assertion would happily admit.
		assertEquals("dropped, not clamped to the new edge", 0, sent.size());
		assertTrue("and consumed, so it cannot retry next tick", p.moveNode == null);

		// A NON-SQUARE scene with asymmetric coordinates, so the check cannot pass while
		// transposed. (25, 5) is inside 30x20; it is outside a width/height swap (25 >= 20) and
		// outside an x/y swap (25 >= 20 as the y term). Asserting it IS emitted therefore fails
		// under either transposition — which a square scene could never detect.
		List<Sent> asym = new ArrayList<Sent>();
		Node nAsym = mockNode(asym, true, "screen-addr");
		InputRouter routerAsym = new InputRouter();
		plant(routerAsym, "watcher#0", 11, 0, nAsym, sentinelPlayer(), 25, 5);
		routerAsym.flushCoalescedMoves(30, 20);
		assertEquals("a point inside a wide scene is emitted", 1, asym.size());
		assertEquals("x survives untransposed", Integer.valueOf(25), asym.get(0).args[2]);
		assertEquals("y survives untransposed", Integer.valueOf(5), asym.get(0).args[3]);

		// The boundary, both sides of it, because inBounds is x < width and off-by-one here
		// would either drop a legal edge pixel or emit an illegal one.
		List<Sent> edge = new ArrayList<Sent>();
		Node n2 = mockNode(edge, true, "screen-addr");
		InputRouter router2 = new InputRouter();
		plant(router2, "watcher#0", 6, 0, n2, sentinelPlayer(), 29, 19);
		plant(router2, "watcher#1", 7, 0, n2, sentinelPlayer(), 30, 19);
		router2.flushCoalescedMoves(30, 20);
		assertEquals("the last legal pixel is emitted, the first illegal one is not",
				1, edge.size());
		assertEquals("and it is the in-bounds gesture", Integer.valueOf(6), edge.get(0).args[5]);

		// The same on the y axis, which the x cases above cannot speak for.
		List<Sent> edgeY = new ArrayList<Sent>();
		Node n4 = mockNode(edgeY, true, "screen-addr");
		InputRouter router4 = new InputRouter();
		plant(router4, "watcher#0", 9, 0, n4, sentinelPlayer(), 5, 19);
		plant(router4, "watcher#1", 10, 0, n4, sentinelPlayer(), 5, 20);
		router4.flushCoalescedMoves(30, 20);
		assertEquals("the last legal row is emitted, the first illegal one is not",
				1, edgeY.size());
		assertEquals("and it is the in-bounds gesture", Integer.valueOf(9), edgeY.get(0).args[5]);

		// A size nothing can be inside drops everything: TileEntityGpu2 passes 0, 0 when a
		// teardown left it with no scene.
		List<Sent> none = new ArrayList<Sent>();
		Node n3 = mockNode(none, true, "screen-addr");
		InputRouter router3 = new InputRouter();
		plant(router3, "watcher#0", 8, 0, n3, sentinelPlayer(), 0, 0);
		router3.flushCoalescedMoves(0, 0);
		assertEquals("no scene, no moves", 0, none.size());
	}

	@Test
	public void theCapIsTwoAndTrippingItDefersRatherThanDrops() throws Exception {
		// The VALUE is pinned deliberately (caps have two sides): 2 x 20 ticks/s = 40 moves/s
		// per GPU, under the ~53/s measured consumer ceiling with room for downs/ups/keys.
		// Raising it past the ceiling reintroduces the queue-overflow defect; a change here
		// must re-justify against ingame/uidemo.lua's measurements, not just edit a constant.
		assertEquals(2, InputRouter.MAX_MOVE_EMISSIONS_PER_FLUSH);

		InputRouter router = new InputRouter();
		List<Sent> sent = new ArrayList<Sent>();
		Node node = mockNode(sent, true, "screen-addr");
		InputRouter.Pointer a = plant(router, "watcher#0", 1, 0, node, sentinelPlayer(), 1, 1);
		InputRouter.Pointer b = plant(router, "watcher#1", 2, 0, node, sentinelPlayer(), 2, 2);
		InputRouter.Pointer c = plant(router, "watcher#2", 3, 0, node, sentinelPlayer(), 3, 3);

		router.flushCoalescedMoves(W, H);
		assertEquals("the cap holds: two of three emitted", 2, sent.size());
		// DEFERRED, not dropped. Under oldest-pend-first with equal stamps the tie breaks in
		// press order, so the deferred one is deterministically c — but this test pins only
		// the count, leaving the ordering contract to the contention test below.
		int pending = (a.moveNode != null ? 1 : 0) + (b.moveNode != null ? 1 : 0)
				+ (c.moveNode != null ? 1 : 0);
		assertEquals("exactly one deferred, none dropped", 1, pending);

		// A dropping implementation would emit nothing here; the deferred move must land.
		router.flushCoalescedMoves(W, H);
		assertEquals("the deferred gesture emits next flush", 3, sent.size());
		assertTrue("and nothing is pending after that",
				a.moveNode == null && b.moveNode == null && c.moveNode == null);

		// All three ids reached the wire exactly once — deferral must not duplicate either.
		boolean saw1 = false, saw2 = false, saw3 = false;
		for (Sent s : sent) {
			int id = ((Integer) s.args[5]).intValue();
			if (id == 1) saw1 = true;
			else if (id == 2) saw2 = true;
			else if (id == 3) saw3 = true;
		}
		assertTrue("every gesture's move arrived", saw1 && saw2 && saw3);
	}

	@Test
	public void continuousContentionServesOldestFirstInsteadOfStarving() throws Exception {
		// Three gestures ALL re-pending after every flush — three players dragging without
		// pause. Under a fixed iteration order the same two would win every tick and the third
		// would never emit. Fairness is AGE-based (oldest pend tick first): a deferred gesture
		// keeps its stamp while freshly emitted ones re-pend with a NEWER one, so the deferred
		// gesture must win the very next flush. The re-pend below mimics route()'s stamping
		// rule exactly — stamp only on the clean->pending transition — because restamping a
		// still-pending gesture would reset its age and invert the rule.
		InputRouter router = new InputRouter();
		List<Sent> sent = new ArrayList<Sent>();
		Node node = mockNode(sent, true, "screen-addr");
		EntityPlayer p1 = sentinelPlayer(), p2 = sentinelPlayer(), p3 = sentinelPlayer();
		InputRouter.Pointer a = plant(router, "watcher#0", 1, 0, node, p1, 1, 1);
		InputRouter.Pointer b = plant(router, "watcher#1", 2, 0, node, p2, 2, 2);
		InputRouter.Pointer c = plant(router, "watcher#2", 3, 0, node, p3, 3, 3);
		InputRouter.Pointer[] all = { a, b, c };
		EntityPlayer[] players = { p1, p2, p3 };

		java.util.Set<Integer> seen = new java.util.HashSet<Integer>();
		for (long tick = 1; tick <= 6; tick++) {
			router.flushCoalescedMoves(W, H);
			for (Sent s : sent) {
				seen.add((Integer) s.args[5]);
			}
			for (int i = 0; i < all.length; i++) {
				if (all[i].moveNode == null) {
					all[i].movePendTick = tick;
					all[i].moveNode = node;
					all[i].movePlayer = players[i];
				}
			}
		}
		assertEquals("no gesture starves under continuous contention", 3, seen.size());

		// Sharper than no-starvation: after flush 1 emitted a and b (tie broken by press
		// order), c is the oldest pending, so flush 2's winners MUST include it. Bounded
		// delay of ceil(3/2) = 2 flushes is the contract the constant's javadoc states.
		java.util.Set<Integer> firstTwoFlushes = new java.util.HashSet<Integer>();
		for (int i = 0; i < Math.min(4, sent.size()); i++) {
			firstTwoFlushes.add((Integer) sent.get(i).args[5]);
		}
		assertTrue("the deferred gesture emitted within its delay bound",
				firstTwoFlushes.contains(Integer.valueOf(3)));
	}

	@Test
	public void aDeferredGestureOutranksNewcomersEvenWhenTheCompositionChanges() throws Exception {
		// THE SCENARIO THAT BROKE THE FIRST FAIRNESS DESIGN, pinned so no positional scheme
		// can come back. A rotation cursor indexes a list rebuilt each flush; when the list's
		// composition changes between flushes (a gesture ends, another presses), the cursor
		// points at a POSITION, and the review's concrete failure was a gesture served TWICE
		// before an already-deferred one was served once. Age cannot alias that way: the
		// deferred gesture's stamp is older than every newcomer's, so it must win flush 2
		// regardless of what joined or left the map in between.
		InputRouter router = new InputRouter();
		List<Sent> sent = new ArrayList<Sent>();
		Node node = mockNode(sent, true, "screen-addr");
		EntityPlayer p1 = sentinelPlayer(), p2 = sentinelPlayer();
		InputRouter.Pointer a = plant(router, "watcher#0", 1, 0, node, p1, 1, 1);
		InputRouter.Pointer b = plant(router, "watcher#1", 2, 0, node, p2, 2, 2);
		InputRouter.Pointer c = plant(router, "watcher#2", 3, 0, node, sentinelPlayer(), 3, 3);

		router.flushCoalescedMoves(W, H);
		assertEquals("flush 1: a and b (press order on tied age)", 2, sent.size());
		assertTrue("c is the deferred one", c.moveNode != null);

		// Composition change: b's gesture ENDS (release removes the record), a re-pends with
		// a fresh stamp, and a brand-new gesture d presses and pends — both NEWER than c.
		router.activePointer.remove("watcher#1");
		a.movePendTick = 5;
		a.moveNode = node;
		a.movePlayer = p1;
		InputRouter.Pointer d = plant(router, "watcher#3", 4, 0, node, sentinelPlayer(), 4, 4);
		d.movePendTick = 5;

		sent.clear();
		router.flushCoalescedMoves(W, H);
		assertEquals("flush 2 emits two", 2, sent.size());
		// The deferred gesture MUST be among them — under the old cursor it demonstrably was
		// not, in exactly this shape.
		boolean sawC = false;
		for (Sent s : sent) {
			if (((Integer) s.args[5]).intValue() == 3) {
				sawC = true;
			}
		}
		assertTrue("the gesture deferred before the composition change emitted first", sawC);
	}

	@Test
	public void droppedMovesDoNotChargeTheCap() throws Exception {
		// The cap bounds what reaches the QUEUE. A dead-node or out-of-bounds move puts
		// nothing there, so it must not consume an emission slot — otherwise two dead
		// gestures could silence a router that had live moves to deliver.
		InputRouter router = new InputRouter();
		List<Sent> sent = new ArrayList<Sent>();
		Node live = mockNode(sent, true, "screen-addr");
		Node dead = mockNode(sent, false, "screen-addr");
		plant(router, "watcher#0", 1, 0, dead, sentinelPlayer(), 1, 1);
		plant(router, "watcher#1", 2, 0, live, sentinelPlayer(), 500, 500); // outside W x H
		plant(router, "watcher#2", 3, 0, live, sentinelPlayer(), 3, 3);
		plant(router, "watcher#3", 4, 0, live, sentinelPlayer(), 4, 4);

		router.flushCoalescedMoves(W, H);
		assertEquals("both LIVE moves emitted; the two drops charged nothing", 2, sent.size());
		boolean saw3 = false, saw4 = false;
		for (Sent s : sent) {
			int id = ((Integer) s.args[5]).intValue();
			if (id == 3) saw3 = true;
			else if (id == 4) saw4 = true;
		}
		assertTrue("and they are the live gestures", saw3 && saw4);
	}

	@Test
	public void beginTickFlushesOncePerTickNotOncePerCall() throws Exception {
		InputRouter router = new InputRouter();
		List<Sent> sent = new ArrayList<Sent>();
		Node node = mockNode(sent, true, "screen-addr");

		router.beginTick(5L, W, H);
		InputRouter.Pointer p = plant(router, "watcher#0", 9, 0, node, sentinelPlayer(), 1, 2);

		// The same tick again: the guard must skip the flush, or a second pump in one tick
		// would double the emission rate the coalescing exists to bound.
		router.beginTick(5L, W, H);
		assertEquals("same tick, no flush", 0, sent.size());
		assertTrue("the move is still pending", p.moveNode != null);

		router.beginTick(6L, W, H);
		assertEquals("next tick flushes it", 1, sent.size());
		assertEquals(Integer.valueOf(9), sent.get(0).args[5]);
	}
}
