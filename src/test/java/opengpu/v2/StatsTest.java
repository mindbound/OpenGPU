package opengpu.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import opengpu.v2.stats.RenderStats;
import opengpu.v2.stats.SceneStats;

/**
 * The measurement instruments themselves.
 *
 * Worth testing rather than trusting, because their whole job is to produce numbers that will
 * be used to argue for changing transport caps and render strategy. A counter that is quietly
 * wrong does not fail — it produces a plausible figure that justifies the wrong decision, and
 * nothing downstream can tell. The two derived ratios below are the ones most likely to be read
 * carelessly, so both are pinned against hand-computed values.
 */
public class StatsTest {

	private SceneStats stats;

	@Before
	public void setUp() {
		stats = new SceneStats();
		RenderStats.reset();
	}

	@Test
	public void encodedBytesAndSentBytesAreDifferentQuantities() {
		// SceneHost encodes ONE envelope and hands the same array to every watcher, so the
		// server pays the encoded size once while the network carries it per watcher. Conflating
		// them would understate a populated server by exactly the watcher count.
		stats.onBatch(1000, 5, 4);

		assertEquals("encoded once", 1000, stats.batchBytes);
		assertEquals("carried four times", 4000, stats.batchBytesSent);
		assertEquals(1, stats.batches);
		assertEquals(5, stats.deltas);
		assertEquals(4, stats.watchersMax);
	}

	@Test
	public void perTickAndPerBatchAreNotTheSameNumber() {
		// A scene sealing one batch every twentieth tick costs a twentieth as much per tick as
		// its batch size suggests. Reading meanBatchBytes where meanBytesPerTick was meant would
		// argue for changing a cap in the wrong direction, which is precisely the mistake the
		// instrumentation exists to prevent.
		stats.onTick();
		stats.onBatch(2000, 1, 1);
		for (int i = 0; i < 19; i++) {
			stats.onTick();
			stats.onIdleTick();
		}

		assertEquals("one batch of 2000 bytes", 2000.0, stats.meanBatchBytes(), 1e-9);
		assertEquals("spread over twenty ticks", 100.0, stats.meanBytesPerTick(), 1e-9);
	}

	@Test
	public void everyTickCountsTowardTheDivisorEvenWhenNothingIsSent() {
		// The per-tick divisor used to be derived as batches + idleTicks, which looked right and
		// dropped two real cases: a tick that seals a batch with NO watchers (nothing is sent,
		// so onBatch never fires) and a tick that emits a heartbeat. Both vanished from the
		// denominator, inflating bytes-per-tick — and an inflated figure argues for raising the
		// transport caps, which is the one decision this number exists to inform.
		stats.onTick();
		stats.onBatch(1000, 1, 1);   // a normal watched tick
		stats.onTick();              // sealed a batch, but nobody watching: no outcome call
		stats.onTick();
		stats.onHeartbeat(1);        // neither a batch nor idle
		stats.onTick();
		stats.onIdleTick();

		assertEquals("all four ticks are in the divisor", 4, stats.ticks);
		assertEquals("1000 bytes over four ticks", 250.0, stats.meanBytesPerTick(), 1e-9);

		// The old derivation would have seen batches(1) + idleTicks(1) = 2 and reported double.
		assertEquals("the discarded derivation is not what is being computed",
				500.0, (double) stats.batchBytes / (stats.batches + stats.idleTicks), 1e-9);
	}

	@Test
	public void theMaximumBatchIsTrackedSeparatelyFromTheTotal() {
		// The max is what has to fit the decoder's inflate ceiling; a mean that looks healthy
		// says nothing about whether one frame blew the limit and lost the whole batch.
		stats.onBatch(100, 1, 1);
		stats.onBatch(9000, 1, 1);
		stats.onBatch(200, 1, 1);

		assertEquals(9000, stats.batchBytesMax);
		assertEquals(9300, stats.batchBytes);
		assertEquals(3100.0, stats.meanBatchBytes(), 1e-9);
	}

	@Test
	public void emptyStatsDivideToZeroRatherThanNaN() {
		// These get formatted into a chat line the moment someone runs the command, including
		// before anything has rendered. A NaN there reads as a bug in the mod.
		assertEquals(0.0, stats.meanBatchBytes(), 1e-9);
		assertEquals(0.0, stats.meanBytesPerTick(), 1e-9);
		assertEquals(0.0, RenderStats.workFraction(), 1e-9);
		assertEquals(0.0, RenderStats.interpolationFraction(), 1e-9);
		assertEquals(0.0, RenderStats.meanRenderMicros(), 1e-9);
		assertEquals(0.0, RenderStats.nanosPerCommand(), 1e-9);
	}

	@Test
	public void snapshotsAreCountedPerRecipient() {
		// One snapshot encoding served to three watchers is three deliveries of that size — the
		// cost of clients entering range, which is what the resync budget paces.
		stats.onSnapshot(50000, 3);

		assertEquals(3, stats.snapshots);
		assertEquals(150000, stats.snapshotBytes);
		assertEquals("the encoding itself is what must fit the chunker", 50000, stats.snapshotBytesMax);
	}

	@Test
	public void theWorkFractionIsWhatMeasuresInterpolationCost() {
		// framesWithWork / prePasses is the interpolation overhead stated directly. A settled
		// scene must sit near zero; if it does not, active() is failing to go false, which is a
		// regression no unit test of the interpolator itself would catch.
		for (int i = 0; i < 100; i++) {
			RenderStats.onPrePass(i < 25);
		}
		assertEquals(0.25, RenderStats.workFraction(), 1e-9);
	}

	@Test
	public void renderTimingSeparatesInterpolationFromFreshState() {
		RenderStats.onSceneRender(1_000_000L, 500, true);
		RenderStats.onSceneRender(3_000_000L, 1500, false);

		assertEquals(2, RenderStats.sceneRenders);
		assertEquals(1, RenderStats.interpolationRenders);
		assertEquals(0.5, RenderStats.interpolationFraction(), 1e-9);
		assertEquals("mean of 1ms and 3ms", 2000.0, RenderStats.meanRenderMicros(), 1e-6);
		assertEquals("the slow frame is kept", 3_000_000L, RenderStats.renderNanosMax);
		assertEquals("4ms over 2000 commands", 2000.0, RenderStats.nanosPerCommand(), 1e-6);
	}

	@Test
	public void resetClearsEverything() {
		stats.onTick();
		stats.onBatch(10, 1, 1);
		stats.onHeartbeat(1);
		stats.onBodyServed(99);
		stats.reset();
		assertEquals(0, stats.batches);
		assertEquals(0, stats.batchBytes);
		assertEquals(0, stats.heartbeats);
		assertEquals(0, stats.bodyBytes);
		assertEquals(0, stats.watchersMax);

		RenderStats.onPrePass(true);
		RenderStats.onUpload(1234);
		RenderStats.reset();
		assertEquals(0, RenderStats.prePasses);
		assertEquals(0, RenderStats.uploadBytes);
		assertTrue(RenderStats.uploads == 0);
	}

	/**
	 * EVERY mutable counter, by reflection — not the three someone remembered.
	 *
	 * The named assertions above covered three of RenderStats' eighteen fields. {@code reset()}
	 * happens to be correct today, so this is not a bug fix; it is closing the way the NEXT one
	 * arrives, which is a field added and not added to reset(). That defect is invisible to
	 * every named assertion by construction — the test cannot mention a field that does not
	 * exist yet — and the animator budget is about to add three more. A reflection sweep is the
	 * only form that covers a field nobody has written yet.
	 *
	 * Drives every counter non-zero FIRST, so the sweep cannot pass merely because a field was
	 * never touched: a reset that clears nothing at all would still pass a sweep run against
	 * pristine zeros.
	 */
	@Test
	public void resetClearsEveryRenderStatsCounterIncludingOnesThisTestNeverNames()
			throws Exception {
		RenderStats.onPrePass(true);
		RenderStats.onSceneRender(3L * 1000L * 1000L, 7, true);   // over STALL_NANOS: stall rows
		RenderStats.onUpload(4321);
		RenderStats.onTextureDeferred();
		RenderStats.onAnimatorEvaluate(555L);
		RenderStats.onAnimatorCompile(42, 96, 24);   // charge, frame floats, registers
		RenderStats.passOpens++;
		RenderStats.passNanos += 17L;
		// Driven directly, like passOpens/passNanos: this one only increments inside
		// AnimatorOverlay's node loop, which this test does not run. Note the sweep's two halves
		// catch DIFFERENT defects — the precondition catches "a counter was added and nobody
		// drives it here", the post-reset assertion catches "a counter was added and nobody
		// reset it". Adding this field fired the precondition first, which is how the
		// distinction surfaced.
		RenderStats.animatorNodesEvaluated += 3L;
		// Same reason, one step further out: no production path increments this at all yet (the
		// only hold policy is EVALUATE_ALWAYS), so nothing but a direct write can drive it. The
		// precondition below is what makes "added a counter, forgot reset()" impossible to land
		// even for a counter that no shipped code can move.
		RenderStats.animatorNodesHeld += 5L;
		// Same again for increment 5's three. The sweep's precondition is what catches "added a
		// counter, never drove it here"; the post-reset assertion catches "added a counter, never
		// reset it". Both are needed, and neither is reachable from production in an unloaded
		// client, which is exactly why they are driven by hand.
		RenderStats.animatorScenePassesSettled += 7L;
		RenderStats.animatorBudgetFrames += 11L;
		RenderStats.animatorBudgetAdmissions += 13L;

		java.util.List<java.lang.reflect.Field> counters =
				new java.util.ArrayList<java.lang.reflect.Field>();
		for (java.lang.reflect.Field f : RenderStats.class.getDeclaredFields()) {
			int m = f.getModifiers();
			if (!java.lang.reflect.Modifier.isStatic(m)
					|| java.lang.reflect.Modifier.isFinal(m)) {
				continue;   // STALL_NANOS is final: a threshold, not a counter
			}
			if (f.getType() != long.class && f.getType() != int.class) {
				continue;
			}
			counters.add(f);
		}
		assertTrue("reflection found " + counters.size() + " counters; if this dropped, the sweep"
				+ " is covering less than it claims", counters.size() >= 15);
		for (java.lang.reflect.Field f : counters) {
			f.setAccessible(true);
			assertTrue("precondition: " + f.getName() + " must be non-zero BEFORE reset, or its"
					+ " assertion below proves nothing", f.getLong(null) != 0L);
		}

		RenderStats.reset();

		for (java.lang.reflect.Field f : counters) {
			assertEquals("RenderStats." + f.getName() + " survived reset() — a counter was added"
					+ " without adding it to reset(), which is exactly what this sweep exists to"
					+ " catch", 0L, f.getLong(null));
		}
	}
}
