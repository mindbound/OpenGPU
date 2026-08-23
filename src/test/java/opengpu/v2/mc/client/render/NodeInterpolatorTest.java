package opengpu.v2.mc.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Set;

import org.junit.Test;

import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneState;

/**
 * Node interpolation is what makes 20 Hz server updates look like 60 fps motion. It is pure
 * arithmetic over the shared scene model — no GL, no Forge — so it is testable here, which
 * matters because most of its failure modes are invisible in a screenshot: a wrong lerp reads
 * as "slightly off", and a wrong angular path reads as a rare backspin nobody catches.
 *
 * These were rewritten when interpolation moved from a fixed window measured at BATCH ARRIVAL
 * to keyframes replayed against the SERVER clock. Worth stating what the old tests could not
 * see, because it explains how the first implementation passed ten green tests while being the
 * exact model the design warns against: every one of them fed batches at perfectly even
 * intervals, where arrival time and server time coincide up to a constant. Jitter is the only
 * condition that tells the two models apart, so it now has tests of its own.
 */
public class NodeInterpolatorTest {

	private static final long MS = 1000L * 1000L;
	private static final long TICK = ServerTimeline.TICK_NANOS;
	private static final long DELAY = ServerTimeline.INTERPOLATION_DELAY_TICKS * TICK;

	/** Arrivals are paced exactly one tick apart, so the clock offset is a constant. */
	private static final long T0 = 1000L;      // first server tick
	private static final long N0 = 7_000_000L; // arbitrary local nanos at that arrival
	/** ServerTimeline.RESET_THRESHOLD_NANOS in ms; private there, mirrored for readability. */
	private static final long RESET_THRESHOLD_MS = 500L;

	private static final Set<Integer> NONE = Collections.<Integer>emptySet();

	/** Local time at which the render clock is showing {@code serverTick}. */
	private static long localShowing(double serverTick) {
		// renderNanos(N) = N + offset - DELAY, with offset = T0*TICK - N0 for paced arrivals.
		return (long) (serverTick * TICK) - (T0 * TICK - N0) + DELAY;
	}

	/** Local arrival time of the k-th paced batch. */
	private static long arrival(long k) {
		return N0 + k * TICK;
	}

	private static SceneState stateWith(SceneNode... nodes) {
		SceneState s = new SceneState();
		for (SceneNode n : nodes) {
			s.nodes.put(n.id, n);
		}
		return s;
	}

	private static SceneNode node(int id, double x, double y) {
		SceneNode n = new SceneNode(id, V2Wire.NODE_SPRITE, 1);
		n.x = x;
		n.y = y;
		return n;
	}

	// ------------------------------------------------------------------
	// The animator's instant (Phase 3.5)

	/**
	 * ADDED AFTER A MISATTRIBUTION. A mutation stripping renderInstant's unprimed guard survived
	 * the whole suite, and I recorded that in 3.5's commit message as "Forge-bound, unreachable
	 * from JVM tests". That was wrong: this class imports only java.util and opengpu.v2.scene,
	 * and this very file has been testing it all along. The mutation survived because nothing
	 * covered the method — an ordinary coverage gap dressed up as a structural limit.
	 *
	 * The sentinel is Long.MIN_VALUE rather than 0 because 0 is a legitimate instant, so a caller
	 * that forgot to check would silently place the animator clock at the epoch.
	 */
	@Test
	public void renderInstantIsUnprimedBeforeTheFirstCapture() {
		assertEquals("an unprimed timeline must say so, not answer 0",
				Long.MIN_VALUE, new NodeInterpolator().renderInstant(N0));
	}

	/**
	 * ANIM-4's "one time sample per frame per scene", made checkable: the instant the animator
	 * reads must be the instant interpolation is replaying against, not merely a similar one.
	 *
	 * Asserted through {@code localShowing}, the helper every interpolation test in this file is
	 * written against — so this passes only while both features read the same clock. A second
	 * ServerTimeline would agree here to within its own EMA and then drift apart across a rebase,
	 * which is exactly what the shared accessor exists to prevent.
	 */
	@Test
	public void renderInstantIsTheClockInterpolationReplaysAgainst() {
		NodeInterpolator interp = new NodeInterpolator();
		interp.capture(stateWith(node(1, 0, 0)), T0, N0, NONE);
		assertEquals("at the local time the clock shows tick T0+2, the animator's instant must BE"
				+ " tick T0+2 — the same moment, not an approximation of it",
				ServerTimeline.tickNanos(T0 + 2), interp.renderInstant(localShowing(T0 + 2)));
	}

	/** And it advances with local time one-for-one, which is what makes it a clock. */
	@Test
	public void renderInstantAdvancesWithLocalTime() {
		NodeInterpolator interp = new NodeInterpolator();
		interp.capture(stateWith(node(1, 0, 0)), T0, N0, NONE);
		long at = interp.renderInstant(arrival(3));
		assertEquals("100 ms of local time is 100 ms of render time",
				at + 100 * MS, interp.renderInstant(arrival(3) + 100 * MS));
	}

	// ------------------------------------------------------------------
	// The clock

	@Test
	public void theRenderClockLagsLiveByExactlyTheInterpolationDelay() {
		// The lag IS the jitter buffer: to interpolate TOWARD a keyframe we must already hold
		// it, so the rendered moment has to sit far enough in the past that the next one has
		// normally arrived. Driving this to zero would look right and stall on every batch.
		ServerTimeline clock = new ServerTimeline();
		assertFalse(clock.primed());
		clock.onBatch(T0, N0);
		assertTrue(clock.primed());

		assertEquals(clock.serverNanos(N0) - DELAY, clock.renderNanos(N0));
		assertEquals("at the moment a tick lands we render DELAY behind it",
				ServerTimeline.tickNanos(T0) - DELAY, clock.renderNanos(N0));
	}

	@Test
	public void aLateBatchMovesTheClockByAFractionOfItsLateness() {
		// The property the whole rewrite exists for. Under the old model a batch 20 ms late
		// compressed that node's motion into 30 ms and the next stretched it, so motion surged
		// and stalled. Smoothing means one late arrival barely moves the clock.
		ServerTimeline clock = new ServerTimeline();
		clock.onBatch(T0, N0);
		long before = clock.serverNanos(N0);

		long late = 20 * MS;
		clock.onBatch(T0 + 1, arrival(1) + late);
		long after = clock.serverNanos(N0);

		long shift = Math.abs(after - before);
		assertTrue("a 20 ms late batch must not drag the clock 20 ms: " + shift, shift < late / 2);
		assertTrue("but it must move it somewhat, or the clock never tracks", shift > 0);
	}

	@Test
	public void pacedArrivalsHoldTheClockSteady() {
		ServerTimeline clock = new ServerTimeline();
		clock.onBatch(T0, N0);
		long offsetProbe = clock.serverNanos(N0) - N0;
		for (long k = 1; k < 40; k++) {
			assertFalse("paced traffic must never look like a re-base",
					clock.onBatch(T0 + k, arrival(k)));
		}
		assertEquals("a stable stream must not let the estimate drift",
				offsetProbe, clock.serverNanos(N0) - N0);
	}

	/**
	 * ANIM-13(b), THE REGRESSION -- and an honest statement of what the fix does and does not buy.
	 *
	 * A scene that sends no batches must still have its clock corrected. The drift is real
	 * arithmetic: the estimate tracks {@code serverTick * TICK - nowNanos}, so a server below
	 * 20 tps makes that quantity fall at {@code (1 - tps/20)} of wall time. An animator scene is
	 * silent BY DESIGN -- that is the whole point of animators -- so this is the ordinary case.
	 *
	 * PACED AT THE PRODUCTION CADENCE, and that matters more than it looks. A first version of
	 * this test fed one heartbeat per wall second and asserted the fed arm "must never re-base".
	 * It passed, and it was wrong twice over: production sends a heartbeat every
	 * {@code V2ServerRuntime.HEARTBEAT_INTERVAL_TICKS} = 40 idle ticks (~2.1 s at 19 tps), and at
	 * that spacing the fix does NOT eliminate re-basing. The steady-state lag of an EMA tracking
	 * a ramp is {@code d * (1 - ALPHA) / ALPHA}; with ALPHA = 0.125 that is SEVEN times the
	 * per-sample drift, and ~105 ms of drift per heartbeat gives ~737 ms -- past the 500 ms
	 * threshold. A panel caught the substituted constant; the property below is the one that
	 * survives at the real cadence.
	 *
	 * WHAT THE FIX ACTUALLY BUYS: the error is BOUNDED instead of unbounded. Un-fed, the estimate
	 * is wrong by the whole accumulated deficit and the eventual correction is that entire jump --
	 * it grows without limit with the length of the silence. Fed, the error cannot exceed the
	 * re-base threshold before a correction lands, so the worst jump is bounded by a constant no
	 * matter how long the scene stays quiet. That is the difference between an animator stepping
	 * back three seconds and one stepping back half of one.
	 */
	@Test
	public void aSilentSceneBoundsItsClockErrorInsteadOfLettingItGrow() {
		final long heartbeatTicks = 40L;             // V2ServerRuntime.HEARTBEAT_INTERVAL_TICKS
		final long silenceSeconds = 60L;
		final long wall = silenceSeconds * 1000L * MS;
		final long ticksElapsed = silenceSeconds * 19L;               // 19 tps
		final long wallPerHeartbeat = heartbeatTicks * 1000L * MS / 19L;

		// THE CONTROL -- the old behaviour. Nothing corrects the estimate for a minute, so the
		// batch that ends the silence carries the entire accumulated deficit in one step.
		ServerTimeline starved = new ServerTimeline();
		starved.onBatch(T0, N0);
		long starvedBefore = starved.serverNanos(N0);
		assertTrue("a minute of silence at 19 tps must re-base",
				starved.onBatch(T0 + ticksElapsed, N0 + wall));
		long starvedJump = Math.abs(starved.serverNanos(N0) - starvedBefore);

		// THE FIX -- heartbeats at the production spacing across the same minute.
		ServerTimeline fed = new ServerTimeline();
		fed.onBatch(T0, N0);
		long beats = wall / wallPerHeartbeat;
		for (long b = 1; b <= beats; b++) {
			fed.onBatch(T0 + b * heartbeatTicks, N0 + b * wallPerHeartbeat);
		}
		long fedBefore = fed.serverNanos(N0);
		fed.onBatch(T0 + ticksElapsed, N0 + wall);
		long fedJump = Math.abs(fed.serverNanos(N0) - fedBefore);

		assertTrue("the un-fed estimate must be wrong by the whole minute of deficit -- 60 s at"
				+ " 19 tps loses 3 s of tick-time: " + starvedJump / MS + " ms",
				starvedJump > 2500L * MS);
		assertTrue("the fed estimate worst correction must stay bounded by the re-base threshold"
				+ " rather than growing with the silence: " + fedJump / MS + " ms",
				fedJump < RESET_THRESHOLD_MS * MS);
		assertTrue("and it must be dramatically smaller than the un-fed one, or feeding bought"
				+ " nothing: fed=" + fedJump / MS + " ms starved=" + starvedJump / MS + " ms",
				fedJump * 4 < starvedJump);
	}

	/**
	 * The honest other half of the arithmetic above, asserted rather than left in prose: at the
	 * PRODUCTION cadence a sustained tps deficit still re-bases, because the steady-state lag of
	 * the EMA (~737 ms at 19 tps) is larger than the 500 ms threshold.
	 *
	 * Recorded as a test so nobody re-derives the claim that heartbeats eliminate re-basing. If
	 * that is ever wanted, the knobs are ALPHA and HEARTBEAT_INTERVAL_TICKS, and this test is
	 * where such a change would announce itself.
	 */
	@Test
	public void atProductionCadenceASustainedTpsDeficitStillRebases() {
		final long heartbeatTicks = 40L;
		final long wallPerHeartbeat = heartbeatTicks * 1000L * MS / 19L;

		ServerTimeline clock = new ServerTimeline();
		clock.onBatch(T0, N0);
		boolean rebasedAtLeastOnce = false;
		for (long b = 1; b <= 30; b++) {
			rebasedAtLeastOnce |= clock.onBatch(T0 + b * heartbeatTicks, N0 + b * wallPerHeartbeat);
		}
		assertTrue("a persistent 19 tps deficit out-runs an ALPHA=0.125 EMA sampled every ~2.1 s,"
				+ " so feeding bounds the error without removing the re-base",
				rebasedAtLeastOnce);
	}

	/**
	 * WHY THE RENDERER FEEDS ON CHANGE ONLY -- the trap the naive version of this fix falls into.
	 *
	 * The mirror holds its newest tick as a LEVEL, so a renderer that fed it every frame would
	 * push the SAME tick against an ever-later nowNanos, and each sample would read as the
	 * server having fallen further behind. This drives the estimate down without bound: the fix
	 * would manufacture a worse drift than the free-run it removes, silently, because nothing
	 * throws and the picture merely slides.
	 *
	 * Pinned here rather than at the renderer because SceneRenderer allocates through LWJGL
	 * BufferUtils in its constructor, which is not on the test runtime classpath (the limit
	 * ScenePopulationTest records) -- so this documents the hazard the de-duplicator in
	 * SceneRenderer.SceneGl.fedTick exists to prevent, and fails if anyone decides the same tick
	 * is harmless to re-feed.
	 */
	@Test
	public void refeedingOneTickWouldDragTheEstimateDownwards() {
		ServerTimeline clock = new ServerTimeline();
		clock.onBatch(T0, N0);
		long offsetProbe = clock.serverNanos(N0) - N0;

		for (long f = 1; f <= 60; f++) {
			clock.onBatch(T0, N0 + f * 16L * MS);        // the same tick, one frame later each time
		}

		assertTrue("re-feeding one tick must be shown to move the estimate, or the de-duplicator"
				+ " in SceneRenderer is guarding nothing",
				clock.serverNanos(N0) - N0 < offsetProbe);
	}

	/**
	 * THE WIRING ITSELF: {@code observeTick} must actually reach the clock.
	 *
	 * The panel found both new client-side methods covered only by prose -- every timeline test
	 * drove {@code ServerTimeline} directly, so emptying {@code observeTick} body left the whole
	 * correction channel gone with a green suite. This drives the real {@code NodeInterpolator},
	 * which needs no GL context.
	 */
	@Test
	public void observeTickFeedsTheClockRatherThanBeingDeadWiring() {
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode node = new SceneNode(1, V2Wire.NODE_SPRITE, 0, 0);
		interp.capture(stateWith(node), T0, N0, NONE);
		long before = interp.renderInstant(N0);

		// A tick 40 ahead arriving 40 ticks of wall time later is a clean sample; the estimate
		// must move, which it can only do if observeTick reaches the timeline at all.
		interp.observeTick(T0 + 40L, N0 + 40L * TICK + 200L * MS);

		assertTrue("observeTick must move the render clock, or the ANIM-13(b) channel is dead wiring",
				interp.renderInstant(N0) != before);
	}

	/**
	 * THE PAIRING IS TIME-INVARIANT, AND THAT IS THE WHOLE POINT OF STAMPING ARRIVAL.
	 *
	 * The estimate is {@code tick * TICK - arrival}, an estimate of (server time − wall time).
	 * That quantity does not decay, so a correctly-paired sample read five seconds late must give
	 * the SAME offset as one read immediately. This test asserts exactly that, and its control arm
	 * asserts the converse: pairing the same tick with the READER's clock instead is wrong by the
	 * whole delay and trips a re-base.
	 *
	 * The defect this pins was reachable by looking away from a screen and back. The renderer's
	 * feed is gated on the scene being WALKED, while inbound frames are drained every tick
	 * regardless — so the mirror held a fresh tick that the renderer read up to a heartbeat
	 * interval later (~2 s), and paired it with `now`. Worse on the batch arm: `dirty` survives
	 * until a render clears it, so a batch that landed while the scene was unwatched could be
	 * paired with a clock reading an entire look-away later.
	 */
	@Test
	public void aSampleReadLateIsStillCorrectWhenPairedWithItsArrival() {
		final long arrival = N0 + 10 * TICK;
		final long tick = T0 + 10;
		final long lateRead = arrival + 5000L * MS;      // read five seconds after it landed

		ServerTimeline prompt = new ServerTimeline();
		prompt.onBatch(T0, N0);
		prompt.onBatch(tick, arrival);

		ServerTimeline late = new ServerTimeline();
		late.onBatch(T0, N0);
		assertFalse("a correctly paired sample must not re-base merely for being read late",
				late.onBatch(tick, arrival));       // same PAIR, consumed at lateRead

		assertEquals("arrival-paired samples are time-invariant: reading late must not move the"
				+ " estimate at all", prompt.serverNanos(N0), late.serverNanos(N0));
		// And the estimate is still right when queried at the late instant.
		assertEquals(prompt.serverNanos(lateRead), late.serverNanos(lateRead));

		// THE CONTROL — the old behaviour. Same tick, but paired with the reader's clock.
		ServerTimeline mispaired = new ServerTimeline();
		mispaired.onBatch(T0, N0);
		assertTrue("pairing a 5 s old tick with `now` must re-base — this is the defect, and if it"
				+ " ever stops re-basing this test has lost its subject",
				mispaired.onBatch(tick, lateRead));
		assertTrue("and the mispaired estimate must land far from the correct one",
				Math.abs(mispaired.serverNanos(N0) - prompt.serverNanos(N0)) > 4000L * MS);
	}

	@Test
	public void aBackwardTickOrAHugeJumpRebases() {
		ServerTimeline clock = new ServerTimeline();
		clock.onBatch(T0, N0);
		assertTrue("a tick going backwards is a different incarnation, not jitter",
				clock.onBatch(T0 - 5, arrival(1)));

		ServerTimeline other = new ServerTimeline();
		other.onBatch(T0, N0);
		assertTrue("a jump past any plausible jitter must re-base, not be averaged toward",
				other.onBatch(T0 + 1, arrival(1) + 5_000L * MS));
	}

	// ------------------------------------------------------------------
	// Keyframes

	@Test
	public void firstSightSnapsInsteadOfFlyingInFromTheOrigin() {
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 100, 200);
		interp.capture(stateWith(n), T0, N0, NONE);

		double[] out = new double[5];
		interp.transformOf(n, N0, out);
		assertEquals("a new node must appear where it is, not lerp in", 100, out[0], 1e-9);
		assertEquals(200, out[1], 1e-9);
		assertFalse("a snapped node is not mid-flight", interp.active(N0));
	}

	/** x at the server moment halfway between the keyframes, for a given arrival lateness. */
	private static double positionAtHalfTick(long lateness) {
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		interp.capture(stateWith(n), T0 + 1, arrival(1) + lateness, NONE);

		double[] out = new double[5];
		interp.transformOf(n, localShowing(T0 + 0.5), out);
		return out[0];
	}

	@Test
	public void latenessIsDampedRatherThanPassedStraightThrough() {
		// THE regression test for the rewrite, and worth stating its arithmetic because the
		// obvious assertion — "the two are identical" — is WRONG and this test was written that
		// way first. The clock deliberately tracks arrivals, so lateness is not eliminated, it
		// is damped by ALPHA.
		//
		// Over a 50 ms keyframe interval carrying 100 units of travel, an 18 ms late batch:
		//   this model     -> clock absorbs ALPHA * 18 ms = 2.25 ms  ->  ~4.5 units of shift
		//   lerp-from-arrival -> the full 18 ms lands on the motion  ->  ~36 units of shift
		// The gap between those two numbers is the entire benefit, so assert against it rather
		// than against equality.
		double onTime = positionAtHalfTick(0);
		double late = positionAtHalfTick(18 * MS);
		double shift = Math.abs(onTime - late);

		assertTrue("lateness must be damped, not passed through: " + shift, shift < 8.0);
		assertTrue("an undamped model would shift ~36 units here", shift < 36.0 / 2);
		assertTrue("but the clock must still track arrivals at all", shift > 0.0);
	}

	@Test
	public void aNodeTraversesItsKeyframeIntervalExactlyOnce() {
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		n.y = 40;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		double[] out = new double[5];
		interp.transformOf(n, localShowing(T0), out);
		assertEquals("at the first keyframe it is at the start", 0, out[0], 1e-6);

		interp.transformOf(n, localShowing(T0 + 0.5), out);
		assertEquals(50, out[0], 1.0);
		assertEquals(20, out[1], 1.0);

		interp.transformOf(n, localShowing(T0 + 1), out);
		assertEquals("at the second keyframe it has arrived", 100, out[0], 1e-6);

		interp.transformOf(n, localShowing(T0 + 9), out);
		assertEquals("and it stays there rather than overshooting", 100, out[0], 1e-6);
	}

	@Test
	public void aLongIdleThenAMoveJumpsRatherThanCrawling() {
		// DESIGN: "an idle node that moves after 400 ticks jumps, it does not glide for 20
		// seconds". The two keyframes really ARE that far apart, so without the gap rule the
		// lerp would sweep the node across the whole idle span.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 500;
		interp.capture(stateWith(n), T0 + 400, arrival(400), NONE);

		double[] out = new double[5];
		interp.transformOf(n, localShowing(T0 + 200), out);
		assertEquals("a 400-tick gap must snap, not glide", 500, out[0], 1e-9);
		assertFalse(interp.active(arrival(400)));
	}

	@Test
	public void aTeleportSnapsWhileItsNeighbourKeepsSliding() {
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode jumper = node(1, 0, 0);
		SceneNode slider = node(2, 0, 0);
		interp.capture(stateWith(jumper, slider), T0, N0, NONE);

		jumper.x = 300;
		slider.x = 100;
		interp.capture(stateWith(jumper, slider), T0 + 1, arrival(1),
				Collections.singleton(Integer.valueOf(1)));

		double[] out = new double[5];
		interp.transformOf(jumper, localShowing(T0 + 0.5), out);
		assertEquals("the flagged node jumps", 300, out[0], 1e-9);
		interp.transformOf(slider, localShowing(T0 + 0.5), out);
		assertEquals("its neighbour is unaffected", 50, out[0], 1.0);
	}

	@Test
	public void rotationTakesTheShortestAngularPath() {
		// A plain lerp from 6.2 to 0.1 rad spins the long way round — a full reverse revolution
		// — every time a program wraps its angle past 2pi.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		n.rot = 6.2;
		interp.capture(stateWith(n), T0, N0, NONE);
		n.rot = 0.1;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		double[] out = new double[5];
		interp.transformOf(n, localShowing(T0 + 0.5), out);
		double mid = out[2];
		assertTrue("must cross the 2pi seam, not sweep back through pi: " + mid,
				mid > 6.2 || mid < 0.15);
	}

	@Test
	public void aRebaseSettlesEveryNodeInsteadOfSweepingAcrossTheSeam() {
		// Tick stamps from a dead incarnation describe nothing in the new one, so interpolating
		// across the seam would sweep every node along an interval that never existed.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		n.x = 700;
		interp.capture(stateWith(n), 5L, arrival(2), NONE); // fresh incarnation, ticks restart

		double[] out = new double[5];
		interp.transformOf(n, arrival(2), out);
		assertEquals("a re-based node shows its current value outright", 700, out[0], 1e-9);
	}

	@Test
	public void resetSettlesEverything() {
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);
		assertTrue(interp.active(arrival(1)));

		interp.reset();
		double[] out = new double[5];
		interp.transformOf(n, arrival(1), out);
		assertEquals(100, out[0], 1e-9);
		assertFalse("nothing is mid-flight after a reset", interp.active(arrival(1)));
	}

	@Test
	public void aSettledSceneStopsForcingRerenders() {
		// active() is what pins the scene at full frame rate; if it never went false a static
		// display would re-render forever, which is a cost interpolation should pay only while
		// something is actually moving.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		assertTrue("still catching up right after the batch", interp.active(arrival(1)));
		assertFalse("settled once the render clock passes the newest keyframe",
				interp.active(localShowing(T0 + 5)));
	}

	@Test
	public void freedNodesDoNotLeakTracks() {
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode a = node(1, 0, 0);
		SceneNode b = node(2, 0, 0);
		interp.capture(stateWith(a, b), T0, N0, NONE);
		interp.capture(stateWith(a), T0 + 1, arrival(1), NONE);

		// b is gone: it must not keep a track alive, and asking about it falls back to its own
		// values rather than a stale keyframe.
		b.x = 999;
		double[] out = new double[5];
		interp.transformOf(b, arrival(1), out);
		assertEquals(999, out[0], 1e-9);
	}

	@Test
	public void anUnchangedNodeKeepsItsOwnTimeline() {
		// A scene where one sprite moves must not restart everything else's interpolation.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode mover = node(1, 0, 0);
		SceneNode still = node(2, 25, 25);
		interp.capture(stateWith(mover, still), T0, N0, NONE);

		mover.x = 100;
		interp.capture(stateWith(mover, still), T0 + 1, arrival(1), NONE);

		double[] out = new double[5];
		interp.transformOf(still, localShowing(T0 + 0.5), out);
		assertEquals("a node nobody touched must not move", 25, out[0], 1e-9);
		assertEquals(25, out[1], 1e-9);
	}
}
