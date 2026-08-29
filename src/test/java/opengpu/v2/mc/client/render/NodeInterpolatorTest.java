package opengpu.v2.mc.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.Set;

import org.junit.Test;

import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.Look;
import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneState;
import opengpu.v2.scene.Transform3d;

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

	/**
	 * Aim a node's rot3d at {@code deg} about a unit axis, THROUGH {@code Look.normalize}.
	 *
	 * Both server pose verbs run their quaternion through that method, so this produces the
	 * {@code qw >= 0} canonical form a client actually receives rather than a convenient one. That
	 * matters for the half-turn vector below, whose entire point is that canonicalisation does NOT
	 * make consecutive keyframes close in quaternion space.
	 */
	private static void aim(SceneNode n, double ax, double ay, double az, double deg) {
		double h = Math.toRadians(deg) / 2.0;
		double s = Math.sin(h);
		double[] q = Look.normalize(ax * s, ay * s, az * s, Math.cos(h));
		n.qx = q[0];
		n.qy = q[1];
		n.qz = q[2];
		n.qw = q[3];
	}

	/**
	 * THE RULER. {@code x} runs 0 to 100 across the same keyframe interval, so a sample's own
	 * {@code x} reports the fraction the interpolator actually landed on.
	 *
	 * <b>The reason first given for this helper was wrong, and the correction is worth keeping.</b>
	 * It claimed the {@code ServerTimeline} EMA makes {@code localShowing(T0 + 0.25)} land NEAR a
	 * quarter rather than on it. Measured: arrivals in this file are paced exactly one tick apart,
	 * so every sample gives the same offset, the EMA never moves, and the fraction is EXACTLY 0.25
	 * and EXACTLY 0.5. The 1% tolerances on the older position vectors are not evidence of clock
	 * slop either — they predate this file's paced-arrival helpers.
	 *
	 * <b>AND IT CANNOT SEE {@code INTERPOLATION_DELAY_TICKS}, which is the cost of that
	 * independence.</b> {@code localShowing} ADDS the delay to the wall time, so every vector built
	 * on it stays green whatever the constant is set to — which is how a value that stopped
	 * interpolation happening at all survived a 961-test suite.
	 * {@code aNodeUpdatingEveryTickActuallyInterpolates} derives its instants from real arrival
	 * pacing instead and is the only vector here that fails if the constant regresses.
	 *
	 * The helper stays, for a reason that survives both corrections: measuring the fraction makes
	 * the quaternion assertions independent of the clock ENTIRELY, so a future change to
	 * {@code INTERPOLATION_DELAY_TICKS} or to the EMA cannot silently soften them. What it does
	 * NOT do is grade the timing — a {@code sample} computing a wrong {@code a} passes every
	 * assertion below it, and only the absolute position vectors elsewhere in this file catch
	 * that.
	 */
	private static double fractionOf(double[] out) {
		return out[NodeFold.TRS_X] / 100.0;
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

		double[] out = new double[NodeFold.TRS_WIDTH];
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

		double[] out = new double[NodeFold.TRS_WIDTH];
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

		double[] out = new double[NodeFold.TRS_WIDTH];
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

		double[] out = new double[NodeFold.TRS_WIDTH];
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

		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(jumper, localShowing(T0 + 0.5), out);
		assertEquals("the flagged node jumps", 300, out[0], 1e-9);
		interp.transformOf(slider, localShowing(T0 + 0.5), out);
		assertEquals("its neighbour is unaffected", 50, out[0], 1.0);
	}

	@Test
	public void rotationTakesTheShortestAngularPath() {
		// A plain lerp from 6.2 to 0.1 rad spins the long way round — a full reverse revolution
		// — every time a program wraps its angle past 2pi.
		//
		// THE ACCEPTANCE WINDOW USED TO CONTAIN THE DESTINATION. This asserted
		// `mid > 6.2 || mid < 0.15`, where the second arm was meant to accept a sample that had
		// crossed the seam — but 0.1 IS the destination, so `out[ROT] = t.curr[ROT]` (no
		// interpolation at all) passed through that arm. So did `... * 1.0` and `... * 0.5`. The
		// vector pinned the DIRECTION of travel and never the fraction, on a channel
		// `NodeFold.apply` actually consumes.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		n.rot = 6.2;
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		n.rot = 0.1;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		// Sampled OFF the midpoint, and graded against the fraction actually reached — the same
		// treatment tzAndSz... gets, for the same reason.
		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, localShowing(T0 + 0.25), out);
		double a = fractionOf(out);
		double mid = out[NodeFold.TRS_ROT];

		// The short way is +0.1831853 rad (2pi - 6.1), travelled at constant speed.
		double delta = 0.1 - 6.2 + 2.0 * Math.PI;
		assertEquals("rot advances by the SHORT delta, scaled by the fraction reached",
				6.2 + delta * a, mid, 1e-9);

		// The wrong answers, by number. Each of these passed the window this vector used to carry.
		assertTrue("snapping to the destination gives 0.1", Math.abs(mid - 0.1) > 0.01);
		assertTrue("holding at the source gives 6.2", Math.abs(mid - 6.2) > 0.01);
		assertTrue("ignoring the fraction gives the full delta, 6.3832",
				Math.abs(mid - (6.2 + delta)) > 0.01);
		assertTrue("a midpoint-returning implementation gives 6.2916",
				Math.abs(mid - (6.2 + delta * 0.5)) > 0.01);
		assertTrue("and a plain lerp sweeps back through pi to 3.15",
				Math.abs(mid - 3.15) > 0.5);
		assertTrue("the sample must be off the midpoint or two of those coincide",
				Math.abs(a - 0.5) > 0.1);
	}

	@Test
	public void aNodeWhoseGapMatchesTheDelayMovesAtConstantSpeed() throws Exception {
		// THE ONLY VECTOR IN THIS FILE THAT CAN SEE THE CADENCE AT ALL, and it pins the
		// RELATIONSHIP rather than the constant's value.
		//
		// Every other vector derives its frame instants from localShowing, which ADDS the delay to
		// the wall time — so it self-adjusts to any value and stays green whatever the constant
		// is. Those are sound as tests of the interpolation ARITHMETIC and structurally blind to
		// the cadence. This one derives its instants from the ARRIVALS, as a display frame does.
		//
		// WHAT IT PINS. In closed form, for a paced server, the clock spends min(D,G)/G of each
		// interval inside the window and sweeps it at (G/D)x nominal speed, for a delay D and a
		// keyframe gap G. Exact only where G == D. So: a node whose gap EQUALS the delay must move
		// by an equal amount on every frame, and a node whose gap EXCEEDS it must not — it surges
		// then freezes. Both halves hold whatever D is set to, which is what makes this vector
		// outlive the next change to the constant. (An earlier version asserted "a node updating
		// every tick interpolates", which is a claim about D == 1 specifically and would have had
		// to be deleted when the constant went back to 2.)
		java.lang.reflect.Field df =
				ServerTimeline.class.getDeclaredField("INTERPOLATION_DELAY_TICKS");
		df.setAccessible(true);
		int delay = df.getInt(null);

		double[] matched = perFrameSteps(delay);
		double[] wider = perFrameSteps(delay + 1);

		double lo = matched[0], hi = matched[0];
		for (int i = 0; i < matched.length; i++) {
			lo = Math.min(lo, matched[i]);
			hi = Math.max(hi, matched[i]);
		}
		// TOLERANCE 1e-3, AND THE RESIDUE HAS AN EXACT CAUSE rather than being slack. Frame
		// instants are spaced by integer nanoseconds: TICK/3 is 16_666_666 by integer division, so
		// three frames span 49_999_998 ns — two short of a tick. Over 100 units per 50 ms that is
		// 100 * 2/50_000_000 = 4e-6 units of position, which is the spread actually observed
		// (16.666665999999964 to 16.66667000000001). A first draft asserted 1e-6 and failed on it.
		//
		// The discriminator is unharmed: the mismatched cadence below surges by 66.67 units in one
		// frame, four orders of magnitude outside this band.
		assertTrue("gap == delay must move by an equal amount every frame; saw steps from "
				+ lo + " to " + hi, hi - lo < 1e-3);
		assertTrue("and must actually be moving", lo > 1e-6);

		double frozen = 0, biggest = 0;
		for (int i = 0; i < wider.length; i++) {
			if (wider[i] < 1e-6) {
				frozen++;
			}
			biggest = Math.max(biggest, wider[i]);
		}
		assertTrue("gap > delay must surge then freeze — that is the (G/D)x sweep running out of"
				+ " window. Frozen frames: " + frozen + "/" + wider.length, frozen > 0);
		assertTrue("and its biggest step must exceed the matched cadence's uniform one",
				biggest > hi + 1e-6);
	}

	/**
	 * Per-frame displacement for a node updating every {@code gapTicks} ticks, sampled at three
	 * display frames per tick from the ARRIVALS rather than from {@link #localShowing}.
	 */
	private static double[] perFrameSteps(int gapTicks) {
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		java.util.List<Double> xs = new java.util.ArrayList<Double>();
		for (int k = 1; k <= 8; k++) {
			n.x = k * 100.0;
			long arrival = N0 + (long) k * gapTicks * TICK;
			interp.capture(stateWith(n), T0 + (long) k * gapTicks, arrival, NONE);
			for (int f = 0; f < 3 * gapTicks; f++) {
				double[] out = new double[NodeFold.TRS_WIDTH];
				interp.transformOf(n, arrival + f * (TICK / 3), out);
				xs.add(Double.valueOf(out[NodeFold.TRS_X]));
			}
		}
		// steps from the second half only, so the clock estimate has settled
		int from = xs.size() / 2;
		double[] steps = new double[xs.size() - from - 1];
		for (int i = 0; i < steps.length; i++) {
			steps[i] = Math.abs(xs.get(from + i + 1).doubleValue() - xs.get(from + i).doubleValue());
		}
		return steps;
	}

	@Test
	public void aChangeConfinedToAGroupsLASTSlotIsSeen() {
		// equalRange's UPPER BOUND was pinned by nothing, and the cause is an idiom this file uses
		// everywhere: `x` as the fraction ruler. Slot 0 always differs, so the scan never has to
		// reach its top — `i < lo + len - 1` (and even `- 2`) survived the whole suite, in code
		// this increment introduced.
		//
		// The production shape is not exotic: ServerScene.setTransform always stages the full
		// X|Y|ROT|SX|SY mask, so a program changing only its sprite's height sends a delta where
		// SY is the single differing VALUE. Under the mutant the group reports "nothing moved",
		// `continue` fires, and the node's scale stays frozen at its first-sight value until some
		// other 2D slot happens to change.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 50, 50);
		n.sy = 1;
		interp.capture(stateWith(n), T0, N0, NONE);
		n.sy = 4;                        // the 2D group's LAST slot, and the only thing that moves
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, localShowing(T0 + 0.5), out);
		assertTrue("a change confined to SY must roll the 2D keyframe: " + out[NodeFold.TRS_SY],
				out[NodeFold.TRS_SY] > 1.05 && out[NodeFold.TRS_SY] < 3.95);
		assertTrue("a scan that stops one slot short leaves it frozen at 1.0",
				Math.abs(out[NodeFold.TRS_SY] - 1.0) > 0.05);

		// AND THE SAME AT THE TOP OF THE 3D GROUP, whose last slot is QW. Non-unit on purpose:
		// holding qx/qy/qz fixed while qw moves is the only way to make QW the sole difference,
		// and the unvalidated codec paths permit exactly that.
		NodeInterpolator other = new NodeInterpolator();
		SceneNode m = node(2, 0, 0);
		m.qx = 0.6;
		m.qy = 0;
		m.qz = 0;
		m.qw = 0.8;
		other.capture(stateWith(m), T0, N0, NONE);
		m.qw = 0.9;
		other.capture(stateWith(m), T0 + 1, arrival(1), NONE);

		double[] out3 = new double[NodeFold.TRS_WIDTH];
		other.transformOf(m, localShowing(T0 + 9), out3);   // past the window: shows curr verbatim
		assertEquals("a change confined to QW must roll the 3D keyframe", 0.9,
				out3[NodeFold.TRS_QW], 1e-12);
	}

	@Test
	public void aSampleBeforeTheWindowHoldsTheEarlierKeyframe() {
		// sampleGroup's before-window clamp could be DELETED outright. The one test that entered
		// it sampled at exactly renderNanos == t0, where a = 0 and the interpolating body returns
		// `from` anyway — so it pinned what the arm WRITES and could not see whether the arm
		// EXISTS. The only other visitor asserted non-sentinel and non-NaN, which an extrapolated
		// value satisfies happily.
		//
		// Without the clamp, `a` goes negative and the node extrapolates BACKWARD off the end of
		// its keyframe interval.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, localShowing(T0 - 0.5), out);
		assertEquals("before the interval, hold the earlier keyframe", 0.0,
				out[NodeFold.TRS_X], 1e-9);
		assertTrue("extrapolating gives -50 here, which is off the path entirely",
				out[NodeFold.TRS_X] > -1.0);
	}

	@Test
	public void slerpRefusesAQuaternionWhoseNormOverflows() {
		// The `isInfinite` half of slerp's degenerate guard was driven by nothing: both degenerate
		// vectors use a ZERO quaternion, which the `!(na > 0.0)` half already catches. A norm that
		// overflows while every COMPONENT stays finite is a different input case and it takes the
		// other half.
		//
		// Without it the division is by an infinite norm, so all four components go to zero and
		// Transform3d.rotation reads the result as the IDENTITY — the rotation silently discarded
		// rather than loudly refused.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		aim(n, 0, 1, 0, 30);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		n.qx = 1e200;
		n.qy = 1e200;
		n.qz = 0;
		n.qw = 1e200;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);
		assertTrue("every component must be finite or this tests the wrong guard",
				!Double.isInfinite(n.qx) && !Double.isInfinite(n.qw));

		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, localShowing(T0 + 0.5), out);
		assertEquals("an overflowing norm shows the destination verbatim", 1e200,
				out[NodeFold.TRS_QX], 0.0);
		assertEquals(1e200, out[NodeFold.TRS_QW], 0.0);
		assertTrue("dividing by the infinite norm collapses it toward the identity instead",
				Math.abs(out[NodeFold.TRS_QX]) > 1.0);
		// The 2D group is unaffected and keeps interpolating.
		assertTrue("x still moves", fractionOf(out) > 0.05 && fractionOf(out) < 0.95);
	}

	@Test
	public void theShortestAngleWrapsBOTHWaysAndReducesLargeDeltas() {
		// THREE HOLES IN ONE THREE-LINE METHOD, all pre-existing. The file's only rot vector goes
		// 6.2 -> 0.1, i.e. delta = -6.1, which drives the `d < -Math.PI` arm alone. So:
		//   * the mirror arm `d > Math.PI` was executed by NO test in the repository, and deleting
		//     its body left the suite green;
		//   * the `% TWO_PI` reduction never actually reduced anything, and deleting it was green.
		// A symmetric guard with one arm pinned is the one-sided-fix shape this project names.

		// (a) THE POSITIVE WRAP. 0.1 -> 6.2 is delta = +6.1, whose short path is -0.1832 rad —
		// backwards past zero, not forwards through pi.
		NodeInterpolator up = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		n.rot = 0.1;
		up.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		n.rot = 6.2;
		up.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		double[] out = new double[NodeFold.TRS_WIDTH];
		up.transformOf(n, localShowing(T0 + 0.25), out);
		double a = fractionOf(out);
		double shortWay = 6.2 - 0.1 - 2.0 * Math.PI;          // -0.1831853...
		assertEquals("the positive wrap takes the short way, backwards past zero",
				0.1 + shortWay * a, out[NodeFold.TRS_ROT], 1e-9);
		assertTrue("a full forward revolution would give 0.1 + 6.1a = 1.625 here",
				Math.abs(out[NodeFold.TRS_ROT] - (0.1 + 6.1 * a)) > 0.5);

		// (b) THE MODULO. A delta beyond 2pi needs reducing before either arm can land it inside
		// [-pi, pi] — one correction is not enough. 90 rad is the classic degrees-written-where-
		// radians-were-meant, and also just a fast spinner.
		NodeInterpolator big = new NodeInterpolator();
		SceneNode m = node(2, 0, 0);
		m.rot = 0;
		big.capture(stateWith(m), T0, N0, NONE);
		m.x = 100;
		m.rot = 90;
		big.capture(stateWith(m), T0 + 1, arrival(1), NONE);

		double[] out2 = new double[NodeFold.TRS_WIDTH];
		big.transformOf(m, localShowing(T0 + 0.25), out2);
		double b = fractionOf(out2);
		double reduced = 90.0 % (2.0 * Math.PI);              // 2.0354..., already inside [-pi, pi]
		assertTrue("the reduced delta must land inside the band or this vector proves nothing",
				Math.abs(reduced) <= Math.PI);
		assertEquals("a delta beyond 2pi is reduced before the wrap arms see it",
				reduced * b, out2[NodeFold.TRS_ROT], 1e-9);
		assertTrue("without the modulo this sweeps (90 - 2pi) * b = 20.93 rad in one tick",
				Math.abs(out2[NodeFold.TRS_ROT] - (90.0 - 2.0 * Math.PI) * b) > 1.0);
	}

	@Test
	public void scaleInterpolatesOnBothAxes() {
		// NEITHER SCALE CHANNEL WAS EVER INTERPOLATED BY ANY VECTOR. Replacing both
		// `out[SX]`/`out[SY]` lerps with the constant 1.0 left the whole suite green — the two
		// channels were reached only by the sentinel sweep, which cannot tell a written wrong
		// value from a written right one. They are two of the five NodeFold.apply consumes.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		n.sx = 1;
		n.sy = 4;
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		n.sx = 3;
		n.sy = 8;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, localShowing(T0 + 0.25), out);
		double a = fractionOf(out);
		assertEquals("sx lerps", 1 + 2 * a, out[NodeFold.TRS_SX], 1e-9);
		assertEquals("sy lerps", 4 + 4 * a, out[NodeFold.TRS_SY], 1e-9);
		// The two axes carry DIFFERENT values throughout, so a single-axis implementation that
		// wrote sx into both — or the constant 1.0 into either — fails rather than coinciding.
		assertTrue("the axes must not coincide", Math.abs(out[NodeFold.TRS_SX]
				- out[NodeFold.TRS_SY]) > 1.0);
		assertTrue("and neither may be the unit constant that survived before",
				Math.abs(out[NodeFold.TRS_SX] - 1.0) > 0.1);
		assertTrue("the sample must be off the midpoint", Math.abs(a - 0.5) > 0.1);
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

		double[] out = new double[NodeFold.TRS_WIDTH];
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
		double[] out = new double[NodeFold.TRS_WIDTH];
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

		// AT THE BOUNDARY, not merely somewhere past it. The assertion above samples FOUR ticks
		// beyond the newest keyframe, so any slack added to active()'s comparison up to three ticks
		// wide is invisible to it — `render < tickNanos(currTick + 1)` survived the whole suite
		// while doubling the FBO re-render on every gap-2 node and pinning a settled scene at full
		// rate for three extra frames.
		//
		// This is the same defect the file already closed once for MAX_GAP_TICKS
		// (aGapOfOneMoreThanTheCapSnaps: "a cap needs its bound at the boundary, not merely
		// somewhere past it"); active()'s bound never got the same treatment.
		//
		// The instants are in the KEYFRAME domain rather than derived from localShowing, so this
		// pair stays valid whatever INTERPOLATION_DELAY_TICKS becomes.
		assertTrue("in flight a hair BEFORE the newest keyframe",
				interp.active(localShowing(T0 + 1) - 1));
		assertFalse("and settled exactly AT it — the boundary is the assertion",
				interp.active(localShowing(T0 + 1)));
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
		double[] out = new double[NodeFold.TRS_WIDTH];
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

		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(still, localShowing(T0 + 0.5), out);
		assertEquals("a node nobody touched must not move", 25, out[0], 1e-9);
		assertEquals(25, out[1], 1e-9);
	}

	// ------------------------------------------------------------------
	// The 3D record (C1.3.3): tz, sz and the rot3d quaternion

	@Test
	public void theQuaternionFollowsTheGreatCircleRatherThanAComponentLerp() {
		// A 160 degree arc, sampled off-centre. BOTH choices are load-bearing: nlerp is EXACT at
		// the midpoint and at both ends, so a midpoint sample cannot tell the two apart at all,
		// and the gap between them grows with the arc.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		aim(n, 0, 1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		aim(n, 0, 1, 0, 160);
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, localShowing(T0 + 0.25), out);
		double a = fractionOf(out);
		// THE BAND IS THE ONE THE >3 DEGREE EXCLUSION BELOW ACTUALLY HOLDS ON. It was first
		// written as (0.05, 0.95), which is wider: at a = 0.06 the slerp-nlerp gap on this arc is
		// only 2.5 degrees, so a slow clock would have failed with "nlerp must give a DIFFERENT
		// number" -- a message blaming the vector for a clock problem.
		assertTrue("the sample must land where the exclusion below can discriminate: a = " + a,
				a > 0.1 && a < 0.9);

		// CONSTANT SPEED IS THE PROPERTY, and it comes from slerp's definition rather than from
		// its code: the half-angle advances linearly along the arc, so the rotation angle is
		// a * 160 degrees. The axis must survive untouched too — a quaternion that drifts off +Y
		// is a rotation into an axis neither keyframe named.
		assertEquals("the arc is traversed at constant speed", a * 160.0, angleDegrees(out), 1e-6);
		assertEquals("the axis must stay +Y", 0.0, out[NodeFold.TRS_QX], 1e-12);
		assertEquals(0.0, out[NodeFold.TRS_QZ], 1e-12);
		assertTrue("and point along it, not against it", out[NodeFold.TRS_QY] > 0.0);

		// THE WRONG ANSWER, COMPUTED HERE RATHER THAN QUOTED. Lerp the four components of the same
		// two quaternions and renormalise; at a quarter of the way this is 34.48 degrees against
		// slerp's 40. That difference IS the ease-in/ease-out a component lerp puts on every
		// server tick — a 20 Hz judder, in the class whose only job is to remove one.
		double h = Math.toRadians(160.0) / 2.0;
		double ny = a * Math.sin(h);
		double nw = (1 - a) + a * Math.cos(h);
		double nlerpDegrees = Math.toDegrees(2.0 * Math.atan2(Math.abs(ny), nw));
		assertTrue("nlerp must give a DIFFERENT number here or this vector proves nothing: "
				+ nlerpDegrees + " vs " + (a * 160.0), Math.abs(nlerpDegrees - a * 160.0) > 3.0);
		assertTrue("the interpolator produced the component lerp's answer, not the great circle's",
				Math.abs(angleDegrees(out) - nlerpDegrees) > 3.0);
	}

	@Test
	public void aRotationPassingAHalfTurnTakesTheShortWayRound() {
		// 175 to 185 degrees about +X: ten degrees of travel, straddling the half turn. Look
		// canonicalises the far end to qw >= 0, which flips ALL FOUR components -- so the two
		// keyframes have a dot product of -0.996 while describing rotations ten degrees apart.
		// This is the quaternion form of the wrapped-angle defect shortestAngle exists for, and
		// canonicalisation is what makes it reachable rather than what prevents it.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		aim(n, 1, 0, 0, 175);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		aim(n, 1, 0, 0, 185);
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		// SAMPLED OFF 0.5, and that matters twice. cos sits at its extremum at 180 degrees, so the
		// 1e-9 tolerance below resolves the angle to only 0.0026 degrees at the midpoint -- about
		// 3900x softer than at a quarter, while reading as exact. And the midpoint is where nlerp
		// and slerp agree identically, so a sample there cannot see which branch ran.
		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, localShowing(T0 + 0.3), out);
		double a = fractionOf(out);

		// Graded on where a basis vector LANDS, through Transform3d's own quaternion-to-matrix --
		// not on the quaternion's components, which q-versus-minus-q makes ambiguous by exactly
		// the sign this test is about.
		double[][] r = Transform3d.rotation(out[NodeFold.TRS_QX], out[NodeFold.TRS_QY],
				out[NodeFold.TRS_QZ], out[NodeFold.TRS_QW]);
		double expected = Math.toRadians(175.0 + 10.0 * a);
		assertEquals("the short way round: 175 + 10a degrees about +X",
				Math.cos(expected), r[1][1], 1e-9);
		assertEquals(Math.cos(expected), r[2][2], 1e-9);
		// AN OFF-DIAGONAL, so the row-major convention is load-bearing here. The three diagonal
		// entries are identical under transpose, so asserting only those leaves Transform3d free
		// to return R-transpose -- an inverted rotation -- without this vector noticing.
		assertEquals("r[1][2] is -sin(theta) for a rotation about +X, and it is where a transposed"
				+ " convention would show", -Math.sin(expected), r[1][2], 1e-9);
		assertEquals(Math.sin(expected), r[2][1], 1e-9);

		// THE LONG WAY, EXCLUDED BY NUMBER. Without the sign fold the interpolator sweeps the
		// 350-degree complement, whose midpoint is the IDENTITY -- +Y still at +Y, a node that
		// snaps through a full reverse revolution in one tick and lands back where it started.
		assertTrue("+Y must have swung to -Y; the long way leaves it at +1", r[1][1] < -0.99);
		assertTrue("and the axis must still be X", Math.abs(r[0][0] - 1.0) < 1e-9);
	}

	@Test
	public void tzAndSzInterpolateLikeTheirTwoDimensionalSiblings() {
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		n.tz = 10;
		n.sz = 1;
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		n.tz = 30;
		n.sz = 3;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		// OFF THE MIDPOINT, deliberately. Sampled at 0.5 these assertions are satisfied by an
		// implementation that ignores `a` entirely and returns the midpoint of the two keyframes:
		// 10 + 20*0.5 IS the midpoint, and so is 1 + 2*0.5. Depth would then step once per tick
		// instead of gliding, with a green suite. The great-circle vector avoids the midpoint for
		// its own reason; this one has to as well, for a different one.
		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, localShowing(T0 + 0.25), out);
		double a = fractionOf(out);
		assertEquals("tz lerps", 10 + 20 * a, out[NodeFold.TRS_TZ], 1e-9);
		assertEquals("sz lerps", 1 + 2 * a, out[NodeFold.TRS_SZ], 1e-9);
		assertTrue("and the sample is genuinely mid-flight", a > 0.05 && a < 0.95);
		assertTrue("and OFF the midpoint, where a midpoint-returning implementation would pass",
				Math.abs(a - 0.5) > 0.1);
		assertTrue("a midpoint-returning implementation gives tz = 20 here",
				Math.abs(out[NodeFold.TRS_TZ] - 20.0) > 1.0);

		// AND THE QUATERNION SURVIVES THE TRIP. This node's rot3d is identical across both
		// keyframes while tz moves, so the 3D group IS interpolating and slerp runs on a pair whose
		// dot is exactly 1.0 — the production-normal case for anything moving in depth without
		// turning. Delete slerp's near-identical guard and that becomes acos(1.0) = 0, sin = 0,
		// 0.0/0.0, and all four quaternion slots go NaN.
		//
		// NOTHING ELSE IN THIS FILE REACHES IT, which is why the mutant survived a round that was
		// written to catch exactly this. The sentinel vector sets its quaternion before BOTH
		// captures, so its 3D group never rolls a keyframe, stays permanently snapped, and takes
		// the arraycopy exit — slerp is never called for it at all. Two keyframe groups made a
		// path that used to be unavoidable into one that has to be aimed at deliberately: an
		// unchanging quaternion no longer visits slerp, so only a node that moves in tz or sz
		// WITHOUT turning can drive the equal-quaternion case.
		checkEverySlotWritten(interp, n, localShowing(T0 + 0.25),
				"3D group interpolating, equal quaternions");
	}

	@Test
	public void aToleratedGapGlidesRatherThanSnapping() {
		// MAX_GAP_TICKS WAS PINNED ONLY FROM ABOVE. The gaps anywhere in this file were 1, 2, 5, 21
		// and 400 — none of them 3 or 4 — so lowering the constant to 1 or 2, or turning `>` into
		// `>=`, survived the whole suite. Half the constant's stated purpose ("tolerates a program
		// updating slightly slower than every tick") had no test at all, and a cap needs a bound on
		// both sides or "lower it" is unconstrained.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 300;
		interp.capture(stateWith(n), T0 + 3, arrival(3), NONE);

		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, localShowing(T0 + 1.5), out);
		assertTrue("a gap of exactly MAX_GAP_TICKS must GLIDE, not snap: " + out[NodeFold.TRS_X],
				out[NodeFold.TRS_X] > 5.0 && out[NodeFold.TRS_X] < 295.0);
		assertTrue("and the node reports itself in flight",
				interp.active(localShowing(T0 + 1.5)));
	}

	@Test
	public void aForwardTickRebaseSnapsAcrossTheSeam() {
		// THE REBASE ARM WAS PINNED BY NOTHING. The existing rebase vector re-captures at a
		// BACKWARD tick, where `currTick <= prevTick` and sampleGroup's `t1 <= t0` each force the
		// same answer independently — so `|| rebased` is never the operative term and four mutants
		// deleting the rebase signal outright survived the whole suite.
		//
		// The case where it IS load-bearing is a FORWARD tick paired with an instant far off the
		// smoothed offset: the look-away resume SceneRenderer.captureFrom warns about. DESIGN:
		// "Resync always snaps."
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);
		n.x = 200;
		long farOff = arrival(2) + 5000L * MS;
		interp.capture(stateWith(n), T0 + 2, farOff, NONE);

		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, farOff, out);
		assertEquals("a rebase must settle the node on its newest value, not sweep to it",
				200.0, out[NodeFold.TRS_X], 1e-9);
		assertFalse("and nothing is mid-flight across a seam that never existed",
				interp.active(farOff));
		// NON-VACUITY: without the rebase term this reads 100.0 and active() is true — the
		// interpolator sweeping an interval the clock re-based out from under it.
		assertTrue("the pre-rebase keyframe value is what a missing rebase term would show",
				Math.abs(out[NodeFold.TRS_X] - 100.0) > 50.0);
	}

	@Test
	public void aRebaseSnapsAGroupThatDidNotChangeInTheRebasingBatch() {
		// THE OTHER HALF OF THE REBASE SIGNAL, and the production-normal half. The vector above
		// pins `|| rebased` in the snap ASSIGNMENT, which is only reached by a group that CHANGED
		// in the rebasing batch. The `if (rebased) t.snap[g] = true` block above it exists for the
		// opposite case — a group whose slice is unchanged, which hits equalRange and `continue`
		// before the assignment. That is what a look-away resume actually looks like: the batch
		// that re-bases the clock leaves most nodes' 2D five untouched, and any of them still
		// mid-glide would otherwise interpolate across a seam that never existed.
		//
		// Deleting that block survived the whole suite. DESIGN: "Resync always snaps."
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);
		// The rebasing batch changes NOTHING about this node.
		long farOff = arrival(2) + 5000L * MS;
		interp.capture(stateWith(n), T0 + 2, farOff, NONE);

		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, farOff, out);
		assertEquals("an untouched group must still snap across the seam", 100.0,
				out[NodeFold.TRS_X], 1e-9);
		assertFalse("and must not report itself mid-flight", interp.active(farOff));
		// NON-VACUITY: the re-based clock lands at or before this group's prevTick, so without the
		// snap the sampler clamps to PREV and the node jumps BACKWARD to where it started.
		assertTrue("without the snap this reads 0.0 — a 100-unit backward jump",
				Math.abs(out[NodeFold.TRS_X]) > 50.0);
	}

	@Test
	public void aRebaseSnapsTheThreeDimensionalGroupToo() {
		// THE 3D ARM OF THE REBASE PRE-SNAP. Every other rebase vector in this file uses a node
		// whose quaternion, tz and sz never change — so snap[G3D] is already true from first sight
		// and the 3D arm can never be the operative term. Narrowing `t.snap[g] = true` to
		// `t.snap[G2D] = true` therefore survived the whole suite, deleting half of the very
		// statement the code's comment is about ("BOTH groups, because the seam is in the clock
		// they share").
		//
		// This node's 3D group is genuinely mid-flight when the rebasing batch lands, and that
		// batch does not touch its 3D slice.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		aim(n, 0, 1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		aim(n, 0, 1, 0, 90);
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);
		n.x = 500;
		long farOff = arrival(2) + 5000L * MS;
		interp.capture(stateWith(n), T0 + 2, farOff, NONE);

		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, farOff, out);
		assertEquals("the 3D group must settle on its newest pose across the seam", 90.0,
				angleDegrees(out), 1e-6);
		assertFalse("and nothing may report itself mid-flight", interp.active(farOff));
		// NON-VACUITY: without the 3D arm the pose replays from its STARTING rotation, because the
		// re-based clock lands at or before that group's prevTick.
		assertTrue("a 2D-only pre-snap leaves this at 0 degrees", angleDegrees(out) > 45.0);
	}

	@Test
	public void aGapOfOneMoreThanTheCapSnaps() {
		// THE UPPER BOUND AT THE BOUNDARY. aToleratedGapGlides... pins 3 from below and
		// aWideGapInOneGroupMustNotSnapTheOther pins 5 from above, which leaves MAX_GAP_TICKS = 4
		// surviving — a real behaviour change (a 4-tick gap would glide instead of jumping) that
		// nothing saw. A cap needs its bound at the boundary, not merely somewhere past it.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 400;
		interp.capture(stateWith(n), T0 + 4, arrival(4), NONE);

		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, localShowing(T0 + 2), out);
		assertEquals("a gap of MAX_GAP_TICKS + 1 must SNAP", 400.0, out[NodeFold.TRS_X], 1e-9);
		assertFalse("and nothing is in flight", interp.active(localShowing(T0 + 2)));
	}

	@Test
	public void aBackwardTickSnapsRatherThanSweepingBackwards() {
		// `|| t.currTick[g] <= t.prevTick[g]` was the one term of the snap expression no test held.
		// transformOf is insensitive to it — sampleGroup's own `t1 <= t0` clamp produces the same
		// pixel — so the observable difference is active(), which reads t.snap[g] directly: without
		// the term a group whose currTick lands at or below its prevTick keeps snap=false and can
		// report itself mid-flight, pinning SceneRenderer's FBO re-render for up to the
		// interpolation delay on a scene that is not moving.
		// TWO BATCHES ON ONE TICK, which is the discriminating shape and took a survived mutant to
		// find. A first version used a BACKWARD tick sampled after currTick — where active()
		// answers false whether or not the term is present, because `render < tickNanos(currTick)`
		// is already false. It also risked a clock REBASE (a backward tick paired with forward wall
		// time), which would have set snap through a different term and masked the mutant a second
		// way. Equal ticks, a consistent arrival, and a render instant still BEHIND them isolate
		// this one term.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);
		n.x = 200;
		interp.capture(stateWith(n), T0 + 1, arrival(1) + MS, NONE);

		// transformOf cannot see this: t1 == t0 sends sampleGroup down its own clamp either way.
		// active() reads t.snap[g] directly, and it is the only witness.
		assertFalse("a keyframe that did not advance the tick must settle the group, not leave it"
				+ " reporting itself mid-flight and pinning the FBO re-render",
				interp.active(localShowing(T0 + 0.5)));
		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, localShowing(T0 + 0.5), out);
		assertEquals("and it shows the newest value", 200.0, out[NodeFold.TRS_X], 1e-9);
		// NON-VACUITY: the render clock really is behind currTick here, which is the condition
		// active() needs before it can answer true at all.
		assertTrue("the sample instant must sit before the keyframe or this proves nothing",
				localShowing(T0 + 0.5) < localShowing(T0 + 1));
	}

	@Test
	public void aWideGapInOneGroupMustNotSnapTheOther() {
		// The snap FLAG is per group for the same reason the stamps are, and this is the vector
		// that says so. A 3D pose that jumps after a long idle is a 3D teleport; the node's 2D
		// glide, running on its own keyframes, must not freeze because of it.
		//
		// Written after a mutant that collapsed `t.snap[g] = ...` into `t.snap[0] = t.snap[1] = ...`
		// survived the whole suite. The wide-gap vector could not see it because there BOTH groups
		// legitimately snap, so the shared assignment gives the right answer for the wrong reason.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		aim(n, 0, 1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);
		// Five ticks later, 3D only — a gap wider than MAX_GAP_TICKS, so the 3D group snaps.
		aim(n, 0, 1, 0, 90);
		interp.capture(stateWith(n), T0 + 5, arrival(5), NONE);

		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, localShowing(T0 + 0.5), out);
		assertEquals("the 2D glide must survive the 3D group's snap", 50.0,
				out[NodeFold.TRS_X], 1.0);
		assertTrue("a shared snap flag freezes it at the destination instead",
				Math.abs(out[NodeFold.TRS_X] - 100.0) > 10.0);
		// NON-VACUITY: the 3D group really did snap, or the hazard was never set up.
		assertEquals("the 3D group snapped to its destination", 90.0, angleDegrees(out), 1e-6);
	}

	@Test
	public void everySlotOfTheRecordIsWrittenSoAReusedBufferCannotLeak() {
		// The renderer hands the same array to every node in the scene. A slot the interpolator
		// forgets keeps the PREVIOUS node's value -- a defect that follows draw order and reads as
		// nondeterministic, which is the failure mode this package documents three times over.
		// Sized from the constant and checked slot by slot, so a twelfth field added to the record
		// without a line in rawTransform/sample fails here rather than in-game.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 1, 2);
		n.rot = 0.5;
		n.sx = 2;
		n.sy = 3;
		n.tz = 4;
		n.sz = 5;
		aim(n, 0, 0, 1, 30);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		// NaN IS CHECKED SEPARATELY FROM THE SENTINEL, and that is a finding rather than belt and
		// braces: `NaN != -999.0` is TRUE, so a slot written with garbage satisfies a
		// sentinel-only loop. Deleting slerp's near-identical guard makes every equal-quaternion
		// pair divide 0.0/0.0 and fills all four quaternion slots with NaN -- which is every node
		// at the default identity rotation, i.e. almost every node in production -- and the first
		// version of this test passed on it.
		// LABELLED FOR THE BRANCH IT ACTUALLY DRIVES. This node sets its quaternion before BOTH
		// captures and changes only x, so equalRange finds the 3D range unchanged, the 3D group's
		// stamps never roll, snap[G3D] stays true from first sight, and sampleGroup takes the
		// arraycopy exit for it. slerp is not called for this node on any path — the first draft
		// of this line called it "sample path, equal quaternions" and certified a kill it cannot
		// make. The equal-quaternion slerp case is driven in tzAndSzInterpolate... instead.
		checkEverySlotWritten(interp, n, localShowing(T0 + 0.5), "2D sampling, 3D group snapped");

		// The other exits. transformOf has SIX, not the two this test's first draft named (nor the
		// five a second draft said while listing six), and after the two-track split the last four
		// are per group: the unprimed early return, the no-track return, and sampleGroup's snap
		// arm, its before-window clamp, its after-window clamp and its interpolating body.
		interp.reset();
		checkEverySlotWritten(interp, n, localShowing(T0 + 0.5), "unprimed path (reset)");

		NodeInterpolator fresh = new NodeInterpolator();
		checkEverySlotWritten(fresh, n, N0, "no-track path (rawTransform)");

		NodeInterpolator windowed = new NodeInterpolator();
		SceneNode m = node(2, 0, 0);
		aim(m, 0.4, 0.5, Math.sqrt(1 - 0.16 - 0.25), 20);
		windowed.capture(stateWith(m), T0, N0, NONE);
		m.x = 100;
		aim(m, 0.4, 0.5, Math.sqrt(1 - 0.16 - 0.25), 140);
		windowed.capture(stateWith(m), T0 + 1, arrival(1), NONE);
		// An OBLIQUE axis and a 120-degree separation, so this one takes the GREAT-CIRCLE branch
		// with all four quaternion components non-zero. That combination is what the first draft
		// lacked: its single arm set the quaternion before both captures, so it only ever drove
		// the near-identical branch, and every other quaternion vector in this file rotates about
		// a single basis axis -- where a dropped component's correct value is 0 and a fresh
		// double[11] already holds 0. Deleting `out[QZ]` from the great-circle branch survived the
		// entire suite.
		checkEverySlotWritten(windowed, m, localShowing(T0 + 0.3), "great-circle branch, oblique axis");
		checkEverySlotWritten(windowed, m, localShowing(T0 - 5), "before the first keyframe");
		checkEverySlotWritten(windowed, m, localShowing(T0 + 9), "after the last keyframe");

		// A GENUINE SNAP ARM, with the timeline still primed — the reset() call above cannot reach
		// it, because reset() unprimes the clock and transformOf returns before sampleGroup runs.
		NodeInterpolator ported = new NodeInterpolator();
		SceneNode p = node(3, 0, 0);
		aim(p, 0.4, 0.5, Math.sqrt(1 - 0.16 - 0.25), 15);
		ported.capture(stateWith(p), T0, N0, NONE);
		p.x = 100;
		aim(p, 0.4, 0.5, Math.sqrt(1 - 0.16 - 0.25), 115);
		ported.capture(stateWith(p), T0 + 1, arrival(1),
				java.util.Collections.singleton(Integer.valueOf(3)));
		checkEverySlotWritten(ported, p, localShowing(T0 + 0.5), "snap arm, timeline primed");

		// THE OTHER MIXED STATE. The call above at "2D sampling, 3D group snapped" already covers
		// one of the two; this is its inverse — the 2D group snapped from first sight while the 3D
		// group interpolates, which is the production-normal case for a node holding position
		// while its pose turns.
		//
		// (A first draft of this comment claimed no call above reached ANY mixed state, restating
		// the review finding that prompted it as though the same round's other correction had not
		// already closed half of it. It also claimed nothing else checks that sampleGroup's
		// hard-coded field list and its GROUP_LO/GROUP_HI clamps cover the same slots — but every
		// both-interpolating call does exactly that, since the whole eleven-slot record must come
		// out of the two field lists. Two overstatements in one justification, written while
		// correcting an overstatement.)
		NodeInterpolator mixed = new NodeInterpolator();
		SceneNode q = node(4, 7, 8);
		aim(q, 0, 1, 0, 0);
		mixed.capture(stateWith(q), T0, N0, NONE);
		aim(q, 0, 1, 0, 60);
		mixed.capture(stateWith(q), T0 + 1, arrival(1), NONE);
		checkEverySlotWritten(mixed, q, localShowing(T0 + 0.5), "2D snapped, 3D interpolating");
	}

	/** Every slot written with a real number — not merely written, and not merely non-sentinel. */
	private static void checkEverySlotWritten(NodeInterpolator interp, SceneNode n, long at,
			String path) {
		final double SENTINEL = -999.0;
		double[] out = new double[NodeFold.TRS_WIDTH];
		java.util.Arrays.fill(out, SENTINEL);
		interp.transformOf(n, at, out);
		for (int i = 0; i < NodeFold.TRS_WIDTH; i++) {
			assertTrue("slot " + i + " was never written on the " + path, out[i] != SENTINEL);
			assertFalse("slot " + i + " was written NaN on the " + path + " -- which a sentinel"
					+ " check alone cannot see", Double.isNaN(out[i]));
		}
	}

	@Test
	public void aSnappedNodeShowsItsQuaternionExactly() {
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		aim(n, 0, 1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		// AN OBLIQUE AXIS. With `aim(n, 0, 1, 0, 90)` -- what this vector used to use -- qx and qz
		// are both 0, so two of the four assertions below compared zero against zero and
		// `dst[QX] = 0` in rawTransform stayed green. The comment claiming this test closed that
		// gap was written in the same edit that failed to close it.
		aim(n, 0.4, 0.5, Math.sqrt(1 - 0.16 - 0.25), 90);
		interp.capture(stateWith(n), T0 + 1, arrival(1),
				java.util.Collections.singleton(Integer.valueOf(1)));
		assertTrue("all four components must be non-zero or two assertions below are vacuous",
				Math.abs(n.qx) > 0.1 && Math.abs(n.qy) > 0.1 && Math.abs(n.qz) > 0.1
						&& Math.abs(n.qw) > 0.1);

		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, localShowing(T0 + 0.5), out);
		// ALL FOUR components, and now with an axis that makes all four discriminate.
		assertEquals("a teleport shows the destination, to the bit", n.qx, out[NodeFold.TRS_QX], 0.0);
		assertEquals(n.qy, out[NodeFold.TRS_QY], 0.0);
		assertEquals(n.qz, out[NodeFold.TRS_QZ], 0.0);
		assertEquals(n.qw, out[NodeFold.TRS_QW], 0.0);

		// AND THE KEYFRAME EXISTED AT ALL. This node's SECOND capture changes nothing but its
		// quaternion, so an `unchanged` that compares only the 2D five would skip it entirely and
		// leave the track showing the identity -- which the assertions above would then catch as a
		// value error rather than as what it is. Said here so the diagnosis is not left to whoever
		// reads the failure.
		assertTrue("the second capture must have been seen", Math.abs(out[NodeFold.TRS_QW] - 1.0) > 1e-9);
	}

	@Test
	public void aChangeToOnlyTheThreeDimensionalFieldsStillMakesAKeyframe() {
		// The mirror of the note above for tz and sz. Every other 3D vector here moves x as well,
		// to use it as a ruler -- which means every one of them would still see a keyframe under an
		// `unchanged` that was blind to tz and sz, and the node would simply sit at its old depth
		// forever. Nothing else in this file can fail on that.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		n.tz = 10;
		n.sz = 1;
		interp.capture(stateWith(n), T0, N0, NONE);
		n.tz = 30;
		n.sz = 3;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, localShowing(T0 + 0.5), out);
		assertTrue("tz must be mid-flight, not pinned at either keyframe: " + out[NodeFold.TRS_TZ],
				out[NodeFold.TRS_TZ] > 10.5 && out[NodeFold.TRS_TZ] < 29.5);
		assertTrue("and sz with it: " + out[NodeFold.TRS_SZ],
				out[NodeFold.TRS_SZ] > 1.05 && out[NodeFold.TRS_SZ] < 2.95);

		// `active()` is TRUE here and that is correct rather than merely current: the 3D group IS
		// mid-flight. It is worth naming what that costs today -- SceneRenderer re-renders the
		// scene FBO while any group interpolates, and until group B routes the 3D path through
		// this record those frames redraw an identical picture. The alternative would be a special
		// case that has to be deleted one increment later, so the cost is accepted and written
		// down instead. It lands only on a program churning 3D fields, which today does nothing
		// visible anyway.
		assertTrue("the 3D group is genuinely still moving", interp.active(localShowing(T0 + 0.5)));

		// AND THE 2D GROUP WAS NOT TOUCHED. This assertion is why the test exists after the panel:
		// its first version pinned only that a 3D change creates a keyframe, which was true of the
		// implementation that let a 3D change disturb 2D motion as well.
		assertEquals("a 3D-only change must not have rolled the 2D keyframe", 0.0,
				out[NodeFold.TRS_X], 0.0);
		assertEquals(0.0, out[NodeFold.TRS_Y], 0.0);

		// This node is in the MIXED state — 2D snapped from first sight, 3D in flight — so it is
		// also the place to check the record survives it whole.
		checkEverySlotWritten(interp, n, localShowing(T0 + 0.5), "2D snapped, 3D in flight");
	}

	@Test
	public void theRawReadCarriesEveryFieldOfTheModel() {
		// TOTAL, and deliberately not routed through an interpolation. rawTransform is the ONE
		// spelling of "a node's transform as an array" -- FOUR production call sites, in three
		// classes, depend on it covering the whole model (a first draft said three, conflating the
		// call sites with the three hand-written COPIES it replaced; a second said five, counting
		// this test's own call without saying so. The count is: NodeInterpolator twice,
		// AnimatorOverlay once, Canvas2dRenderer once) -- and a field it forgets
		// reads as zero, which for sz and qw is a node scaled away and a degenerate rotation
		// rather than an obvious blank.
		SceneNode n = node(7, 1.5, -2.5);
		n.rot = 0.25;
		n.sx = 3;
		n.sy = 4;
		n.tz = -6.5;
		n.sz = 7;
		aim(n, 0.4, 0.5, Math.sqrt(1 - 0.16 - 0.25), 47);

		double[] out = new double[NodeFold.TRS_WIDTH];
		java.util.Arrays.fill(out, Double.NaN);
		NodeInterpolator.rawTransform(n, out);
		assertEquals(1.5, out[NodeFold.TRS_X], 0.0);
		assertEquals(-2.5, out[NodeFold.TRS_Y], 0.0);
		assertEquals(0.25, out[NodeFold.TRS_ROT], 0.0);
		assertEquals(3.0, out[NodeFold.TRS_SX], 0.0);
		assertEquals(4.0, out[NodeFold.TRS_SY], 0.0);
		assertEquals(-6.5, out[NodeFold.TRS_TZ], 0.0);
		assertEquals(7.0, out[NodeFold.TRS_SZ], 0.0);
		assertEquals(n.qx, out[NodeFold.TRS_QX], 0.0);
		assertEquals(n.qy, out[NodeFold.TRS_QY], 0.0);
		assertEquals(n.qz, out[NodeFold.TRS_QZ], 0.0);
		assertEquals(n.qw, out[NodeFold.TRS_QW], 0.0);

		// NON-VACUITY: every value above is distinct and none is zero, so a read that crossed two
		// slots or left one at its default fails rather than coinciding. The quaternion's three
		// vector components are non-zero for the same reason -- an axis-aligned one hides a
		// dropped component behind the zero it would have had.
		assertTrue("qx must be non-zero or a dropped qx is invisible", Math.abs(n.qx) > 0.1);
		assertTrue("qy likewise", Math.abs(n.qy) > 0.1);
		assertTrue("qz likewise", Math.abs(n.qz) > 0.1);

		// AND NOTHING LEFT AT THE PREFILL. The NaN fill above catches nothing on its own -- all
		// eleven expected values are non-zero and distinct, so a zeroed array would fail the same
		// assertions -- until something asserts no slot survived it. This is what makes "TOTAL"
		// true when TRS_WIDTH next grows, rather than a claim about the eleven listed above.
		for (int i = 0; i < NodeFold.TRS_WIDTH; i++) {
			assertFalse("slot " + i + " was left at the NaN prefill: rawTransform does not cover"
					+ " the whole record", Double.isNaN(out[i]));
		}
	}

	@Test
	public void aDegenerateQuaternionShowsTheDestinationRatherThanAGuess() {
		// DEFENCE IN DEPTH, priced honestly: both server pose verbs normalise through Look, so
		// this cannot arrive from a well-behaved server. It can arrive from the delta applier, the
		// snapshot codec and the raw props path, none of which validate what they carry --
		// Transform3d says so in its own words and treats a zero-length quaternion as the
		// identity. Set by hand here BECAUSE Look.normalize refuses it, which is the point.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		n.qx = 0;
		n.qy = 0;
		n.qz = 0;
		n.qw = 0;
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		// AN OBLIQUE DESTINATION, so the branch's four writes are each pinned by a distinct
		// non-zero number. With an axis-aligned destination two of them carry 0.0 — which is what
		// a fresh double[11] already holds — and `out[QX] = to[QX]` could be deleted outright.
		aim(n, 0.4, 0.5, Math.sqrt(1 - 0.16 - 0.25), 90);
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, localShowing(T0 + 0.5), out);
		assertEquals("no arc to travel: show where it is going", n.qx, out[NodeFold.TRS_QX], 0.0);
		assertEquals(n.qy, out[NodeFold.TRS_QY], 0.0);
		assertEquals(n.qz, out[NodeFold.TRS_QZ], 0.0);
		assertEquals(n.qw, out[NodeFold.TRS_QW], 0.0);
		assertTrue("all four must be non-zero or half these assertions are vacuous",
				Math.abs(n.qx) > 0.1 && Math.abs(n.qy) > 0.1 && Math.abs(n.qz) > 0.1
						&& Math.abs(n.qw) > 0.1);
		checkEverySlotWritten(interp, n, localShowing(T0 + 0.5), "slerp degenerate branch");
		// The lerped channels still interpolate -- the degenerate quaternion must not snap the
		// whole node, only itself.
		assertTrue("x must still be mid-flight", fractionOf(out) > 0.05 && fractionOf(out) < 0.95);
	}

	@Test
	public void slerpNormalisesItsInputsRatherThanTrustingThem() {
		// DELETING BOTH NORMALISATION LINES SURVIVED THE WHOLE SUITE, because every other
		// quaternion vector feeds unit input through Look.normalize. Without them `dot` is not a
		// cosine — for two quaternions each scaled by k it is k^2*cos(theta) — so the branch
		// choice and the great-circle weights are both computed from the wrong angle.
		//
		// Reachable for the reason the degenerate vectors are: the delta applier, the snapshot
		// codec and the raw props path carry whatever arrives, and Transform3d says so in its own
		// words. A non-unit quaternion is the ordinary case of that, not the exotic one.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		aim(n, 0, 1, 0, 0);
		scale(n, 2.0);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		aim(n, 0, 1, 0, 160);
		scale(n, 2.0);
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, localShowing(T0 + 0.25), out);
		double a = fractionOf(out);
		// The same answer the unit-input vector gets: constant speed along the 160-degree arc.
		assertEquals("non-unit input must give the same arc as unit input", a * 160.0,
				angleDegrees(out), 1e-6);
		// Without normalisation dot = 4*cos(80deg) = 0.694, so theta reads 46.06 degrees instead
		// of 80 and the weights are wrong by a wide margin.
		assertTrue("un-normalised input would put the sample far off the arc",
				Math.abs(angleDegrees(out) - a * 160.0) < 1.0);
		assertTrue("and the inputs really are non-unit, or this vector is the unit one again",
				Math.abs(2.0 - Math.sqrt(n.qx * n.qx + n.qy * n.qy + n.qz * n.qz
						+ n.qw * n.qw)) < 1e-9);
	}

	/** Scale a node's stored quaternion off the unit sphere — what an unvalidated path delivers. */
	private static void scale(SceneNode n, double k) {
		n.qx *= k;
		n.qy *= k;
		n.qz *= k;
		n.qw *= k;
	}

	@Test
	public void aDegenerateDESTINATIONIsGuardedToo() {
		// THE MIRROR of the vector above, and the MORE reachable half: the three paths that can
		// deliver an unvalidated quaternion put it in `curr` first, so a node created with one has
		// it as its destination from first sight. Only the `from` side was pinned, and deleting
		// `|| !(nb > 0.0)` from the guard divides by zero, poisons the dot product and writes four
		// NaN slots -- with a green suite.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		aim(n, 0, 1, 0, 90);
		interp.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		n.qx = 0;
		n.qy = 0;
		n.qz = 0;
		n.qw = 0;
		interp.capture(stateWith(n), T0 + 1, arrival(1), NONE);

		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, localShowing(T0 + 0.5), out);
		for (int i = 0; i < NodeFold.TRS_WIDTH; i++) {
			assertFalse("slot " + i + " is NaN: a degenerate DESTINATION poisoned the record",
					Double.isNaN(out[i]));
		}
		assertEquals("and it shows the destination verbatim", 0.0, out[NodeFold.TRS_QW], 0.0);
		assertTrue("while x keeps interpolating", fractionOf(out) > 0.05 && fractionOf(out) < 0.95);
		// THE ASSERTION ABOVE CANNOT SEE AN UNWRITTEN SLOT. The destination is (0,0,0,0), so every
		// quaternion value here is 0.0 -- which is what the freshly allocated `out` already held,
		// and slerp's four degenerate-branch writes could be deleted with this test still green.
		// The sentinel sweep is what discriminates: an unwritten slot keeps -999, which 0.0 does
		// not. Its mirror vector was de-vacuified in the same round by giving it an oblique
		// destination; this one cannot use that fix, because being degenerate IS its premise.
		checkEverySlotWritten(interp, n, localShowing(T0 + 0.5), "slerp degenerate destination");
	}

	@Test
	public void theNearIdenticalThresholdIsPinnedFromBothSides() {
		// SLERP_LINEAR_ABOVE had no vector at all: no test reached the near-identical branch with
		// two DIFFERENT quaternions, so its body could be replaced by "return the identity" or
		// "freeze at `from`" and survive; and the threshold could be lowered to 0.2 -- diverting
		// every rotation under ~157 degrees to nlerp and restoring the judder this class removes --
		// because the only moderate-arc vector sampled at the midpoint, where the two agree.

		// (a) A 2 degree arc: dot = 0.99985, comfortably inside the linear branch. The angle must
		// still advance with `a`, which both surviving mutants above fail.
		NodeInterpolator small = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		aim(n, 0, 1, 0, 0);
		small.capture(stateWith(n), T0, N0, NONE);
		n.x = 100;
		aim(n, 0, 1, 0, 2);
		small.capture(stateWith(n), T0 + 1, arrival(1), NONE);
		double[] out = new double[NodeFold.TRS_WIDTH];
		small.transformOf(n, localShowing(T0 + 0.3), out);
		double a = fractionOf(out);
		assertEquals("the linear branch must still interpolate", a * 2.0, angleDegrees(out), 1e-3);
		assertTrue("frozen at the source would give 0", angleDegrees(out) > 0.2);
		assertTrue("snapped to the identity would also give 0", angleDegrees(out) > 0.2);

		// (b) A 90 degree arc (dot = 0.7071) sampled OFF the midpoint. Correct slerp gives 22.5
		// degrees at a quarter; nlerp gives 21.598. Raising the threshold above 0.7071 diverts
		// this vector to the linear branch and the 0.05 tolerance catches the 0.9-degree error.
		NodeInterpolator wide = new NodeInterpolator();
		SceneNode m = node(2, 0, 0);
		aim(m, 0, 1, 0, 0);
		wide.capture(stateWith(m), T0, N0, NONE);
		m.x = 100;
		aim(m, 0, 1, 0, 90);
		wide.capture(stateWith(m), T0 + 1, arrival(1), NONE);
		wide.transformOf(m, localShowing(T0 + 0.25), out);
		double b = fractionOf(out);
		assertEquals("a 90-degree arc must take the great circle", b * 90.0, angleDegrees(out), 1e-6);
		double nb = 2.0 * Math.toDegrees(Math.atan2(b * Math.sin(Math.toRadians(45.0)),
				(1 - b) + b * Math.cos(Math.toRadians(45.0))));
		assertTrue("nlerp here gives 21.598, which this must exclude: " + nb,
				Math.abs(angleDegrees(out) - nb) > 0.5);
	}

	// ------------------------------------------------------------------
	// The two keyframe groups (C1.3.3 group A, after the panel)

	@Test
	public void aThreeDimensionalChangeDoesNotDisturbATwoDimensionalGlide() {
		// THE DEFECT THE PANEL FOUND, pinned. `unchanged` had to widen to eleven or the 3D fields
		// would never interpolate -- but a shared pair of keyframe stamps meant a 3D-only change
		// ROLLED THE 2D KEYFRAME too. The render clock runs INTERPOLATION_DELAY_TICKS behind, so
		// that roll discarded a 2D window still being replayed: the node jumped to its destination
		// a tick early and then froze. The values were never wrong; `a` was.
		//
		// The oracle is a TWIN with an identical 2D history and no 3D churn. Two nodes that have
		// been told the same thing about x must draw at the same x, whatever else they were told.
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode spun = node(1, 0, 0);
		SceneNode quiet = node(2, 0, 0);
		aim(spun, 0, 1, 0, 0);
		aim(quiet, 0, 1, 0, 0);
		interp.capture(stateWith(spun, quiet), T0, N0, NONE);
		spun.x = 100;
		quiet.x = 100;
		interp.capture(stateWith(spun, quiet), T0 + 1, arrival(1), NONE);
		aim(spun, 0, 1, 0, 40);
		interp.capture(stateWith(spun, quiet), T0 + 2, arrival(2), NONE);

		double[] a = new double[NodeFold.TRS_WIDTH];
		double[] b = new double[NodeFold.TRS_WIDTH];
		boolean sawMidFlight = false;
		for (int step = 0; step <= 4; step++) {
			long at = localShowing(T0 + 0.25 * step);
			interp.transformOf(spun, at, a);
			interp.transformOf(quiet, at, b);
			assertEquals("a 3D-only change moved x at step " + step,
					b[NodeFold.TRS_X], a[NodeFold.TRS_X], 0.0);
			if (a[NodeFold.TRS_X] > 1.0 && a[NodeFold.TRS_X] < 99.0) {
				sawMidFlight = true;
			}
		}
		// NON-VACUITY: if the glide never happened at all, both columns would read 0 or 100
		// throughout and the equality above would hold trivially.
		assertTrue("the twin must actually be gliding, or this vector compares two frozen nodes",
				sawMidFlight);
		// And the 3D half really did move, or the setup never exercised the hazard.
		assertTrue("the spun node's rotation must have changed", angleDegrees(a) > 1.0);
	}

	@Test
	public void threeDimensionalChurnCannotDefeatTheWideGapSnapRule() {
		// The same coupling defeated MAX_GAP_TICKS: with one shared prevTick, a node whose
		// quaternion changed every tick reported a 2D gap of 1 no matter how long its position had
		// been still, so a jump after a long idle GLIDED. DESIGN, quoted in this class: "an idle
		// node that moves after 400 ticks jumps, it does not glide for 20 seconds."
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = node(1, 0, 0);
		aim(n, 0, 1, 0, 0);
		interp.capture(stateWith(n), T0, N0, NONE);
		for (int k = 1; k <= 20; k++) {
			aim(n, 0, 1, 0, k);
			interp.capture(stateWith(n), T0 + k, arrival(k), NONE);
		}
		n.x = 500;
		interp.capture(stateWith(n), T0 + 21, arrival(21), NONE);

		double[] out = new double[NodeFold.TRS_WIDTH];
		interp.transformOf(n, localShowing(T0 + 20.5), out);
		assertEquals("a 21-tick 2D gap must SNAP, not glide", 500.0, out[NodeFold.TRS_X], 1e-9);
		assertTrue("the shared-timeline reading glides and would show something under 500",
				out[NodeFold.TRS_X] >= 500.0);
	}

	/** The rotation angle a quaternion encodes, in degrees, from the vector/scalar parts. */
	private static double angleDegrees(double[] trs) {
		double vx = trs[NodeFold.TRS_QX], vy = trs[NodeFold.TRS_QY], vz = trs[NodeFold.TRS_QZ];
		double v = Math.sqrt(vx * vx + vy * vy + vz * vz);
		return Math.toDegrees(2.0 * Math.atan2(v, trs[NodeFold.TRS_QW]));
	}
}
