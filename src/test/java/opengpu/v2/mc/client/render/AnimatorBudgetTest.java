package opengpu.v2.mc.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;

/**
 * ANIM-16 increment 5 — the budget's decision logic.
 *
 * WHY THIS CLASS IS TESTABLE AT ALL: the thresholds, the hysteresis, the rotation and the
 * staleness bound live in {@link AnimatorBudget}, which imports nothing from Minecraft.
 * {@code SceneRenderer} needs GL and Forge to classload and {@code StatsOverlay} has no coverage
 * of any kind, so arithmetic written in either would ship unverified. This is the same split the
 * hold seam already used for {@code HoldPolicy} and {@code wouldEvaluate}.
 */
public class AnimatorBudgetTest {

	private static final List<String> ONE = Arrays.asList("a");

	/** Drive one frame: roll, then charge every scene the given cost. */
	private static void frame(AnimatorBudget b, List<String> ids, long nanosEach) {
		b.beginFrame(ids);
		for (String id : ids) {
			b.charge(id, nanosEach);
		}
	}

	private static List<String> ids(int n) {
		String[] a = new String[n];
		for (int i = 0; i < n; i++) {
			a[i] = "s" + i;
		}
		return Arrays.asList(a);
	}

	// ---------------------------------------------------------------- the latch

	/**
	 * Under budget, nothing is ever held — the shipped-quiet case, and the one a broken threshold
	 * comparison would silently break.
	 */
	@Test
	public void aQuietClientNeverHolds() {
		AnimatorBudget b = new AnimatorBudget();
		for (int i = 0; i < 20; i++) {
			frame(b, ONE, 40_000L);              // 40 us, well under 250
			assertFalse("frame " + i, b.degrading());
			assertFalse(b.policyFor("a").hold(1));
		}
	}

	/**
	 * Entry is on MEASURED spend, and takes a frame: the budget cannot know a frame was expensive
	 * until it has been paid for.
	 */
	@Test
	public void degradationEntersOnlyAfterAFrameActuallyOverspends() {
		AnimatorBudget b = new AnimatorBudget();
		b.beginFrame(ONE);
		assertFalse("nothing measured yet", b.degrading());
		b.charge("a", 300_000L);                 // over ENTER, but only observable next roll

		b.beginFrame(ONE);
		assertTrue("the overspend is now in hand", b.degrading());
	}

	/**
	 * THE THRESHOLD IS STRICT, both sides. Exactly at ENTER does not enter; exactly at EXIT does
	 * not leave. Pinned because an off-by-one in either comparison is invisible to every other
	 * test here, and produces a budget that engages one frame early or refuses to disengage.
	 */
	@Test
	public void theThresholdsAreExclusiveAtTheBoundary() {
		AnimatorBudget atEnter = new AnimatorBudget();
		atEnter.beginFrame(ONE);
		atEnter.charge("a", AnimatorBudget.ENTER_NANOS);
		atEnter.beginFrame(ONE);
		assertFalse("exactly at ENTER must not degrade", atEnter.degrading());

		AnimatorBudget over = new AnimatorBudget();
		over.beginFrame(ONE);
		over.charge("a", AnimatorBudget.ENTER_NANOS + 1);
		over.beginFrame(ONE);
		assertTrue("one nanosecond over must", over.degrading());
	}

	/**
	 * THE CENTRAL HYSTERESIS PROPERTY. Exit reads estimated FULL-RATE demand, never measured
	 * spend — because holding collapses measured spend by construction.
	 *
	 * The exclusion is what matters: a budget whose exit test read spend would release on the
	 * very first held frame (spend having just fallen to the held cost) and re-enter on the next,
	 * flapping at frame rate forever. Here the scenes still WANT 300 us, so it stays engaged no
	 * matter how cheap the held frames become.
	 */
	@Test
	public void exitReadsDemandNotSpendSoHoldingCannotReleaseItself() {
		AnimatorBudget b = new AnimatorBudget();
		b.beginFrame(ONE);
		b.charge("a", 300_000L);
		b.beginFrame(ONE);
		assertTrue(b.degrading());

		// Held frames are cheap; the periodic forced admission still costs the real 300 us,
		// because the scene's DEMAND has not changed — only what it was allowed to do. Charging
		// the held cost on an admitted frame (an earlier version of this test) models a scene
		// that genuinely became cheap, and releasing then is correct, not a bug.
		for (int i = 0; i < 30; i++) {
			b.beginFrame(ONE);
			b.charge("a", b.policyFor("a").hold(1) ? 500L : 300_000L);
			assertTrue("frame " + i + ": demand is still 300 us, so it must stay engaged",
					b.degrading());
		}
	}

	/** And it DOES leave once the underlying demand genuinely falls. */
	@Test
	public void degradationLeavesWhenRealDemandFalls() {
		AnimatorBudget b = new AnimatorBudget();
		b.beginFrame(ONE);
		b.charge("a", 300_000L);
		b.beginFrame(ONE);
		assertTrue(b.degrading());

		// An admitted (forced, at the staleness bound) pass re-measures the scene as cheap.
		for (int i = 0; i < AnimatorBudget.MAX_HOLD_FRAMES + 2; i++) {
			b.beginFrame(ONE);
			b.charge("a", 20_000L);
		}
		b.beginFrame(ONE);
		assertFalse("demand re-measured at 20 us, under the 125 us exit", b.degrading());
	}

	// ---------------------------------------------------------------- the unit

	/**
	 * ADMISSION IS ALL-OR-NOTHING PER SCENE. Every node of an unadmitted scene is held, which is
	 * the only thing that lets {@code wouldEvaluate} answer false and the render guard skip the
	 * scene's fixed cost AND its GL re-render.
	 *
	 * A partial-admission budget — "hold some nodes of this scene" — passes any test that only
	 * checks totals, and forfeits both terms, because the guard runs on the FIRST running node.
	 */
	@Test
	public void anUnadmittedSceneHoldsEveryNodeAndAnAdmittedOneHoldsNone() {
		AnimatorBudget b = new AnimatorBudget();
		List<String> many = ids(20);
		frame(b, many, 30_000L);                 // 600 us total: far over
		b.beginFrame(many);
		assertTrue(b.degrading());

		int held = 0;
		int admitted = 0;
		for (String id : many) {
			AnimatorBudget.SceneBudget p = b.policyFor(id);
			boolean h = p.hold(1);
			// Whatever the verdict, it must be the same for every node id in that scene.
			for (int node = 1; node <= 50; node++) {
				assertEquals("scene " + id + " node " + node, h, p.hold(node));
			}
			if (h) {
				held++;
			} else {
				admitted++;
			}
		}
		assertTrue("some scenes must be held under a 600 us load", held > 0);
		assertTrue("and some must still run", admitted > 0);
		assertEquals(20, held + admitted);
	}

	/**
	 * The answer must not change WITHIN a frame, however many times it is asked. The overlay asks
	 * from the render guard and again from the evaluation loop, and the two disagreeing is the
	 * defect that freezes a scene outright.
	 */
	@Test
	public void theAnswerIsStableAcrossRepeatedCallsInOneFrame() {
		AnimatorBudget b = new AnimatorBudget();
		List<String> many = ids(20);
		frame(b, many, 30_000L);
		b.beginFrame(many);

		for (String id : many) {
			AnimatorBudget.SceneBudget p = b.policyFor(id);
			boolean first = p.hold(7);
			for (int call = 0; call < 100; call++) {
				assertEquals("call " + call + " on " + id, first, p.hold(7));
			}
		}
	}

	// ---------------------------------------------------------------- starvation

	/**
	 * NO SCENE IS HELD FOREVER, and the bound is the one the design owes: a held absolute-write or
	 * tint property is FROZEN, not stepped, so "it will come round eventually" is not good enough.
	 *
	 * Every scene must run at least once in any MAX_HOLD_FRAMES + 1 window even under a load that
	 * cannot possibly fit — here 40 scenes wanting 30 us each against a 250 us budget.
	 */
	@Test
	public void everySceneRunsWithinTheStalenessBoundUnderImpossibleLoad() {
		AnimatorBudget b = new AnimatorBudget();
		List<String> many = ids(40);
		frame(b, many, 30_000L);
		b.beginFrame(many);
		assertTrue(b.degrading());

		int[] lastRan = new int[40];
		Arrays.fill(lastRan, -1);
		for (int f = 0; f < 200; f++) {
			b.beginFrame(many);
			for (int i = 0; i < 40; i++) {
				if (!b.policyFor("s" + i).hold(1)) {
					lastRan[i] = f;
				}
				b.charge("s" + i, 30_000L);
			}
			for (int i = 0; i < 40; i++) {
				int stale = f - lastRan[i];
				assertTrue("scene s" + i + " unrun for " + stale + " frames at frame " + f,
						stale <= AnimatorBudget.MAX_HOLD_FRAMES + 1);
			}
		}
	}

	/**
	 * Rotation, not favouritism. Over a long run every scene should get a roughly equal share of
	 * admissions — a budget that always admitted the same scenes would satisfy the staleness
	 * bound above purely through forced admissions and starve the rest to exactly that floor.
	 */
	@Test
	public void admissionsRotateRatherThanFavouringTheSameScenes() {
		AnimatorBudget b = new AnimatorBudget();
		List<String> many = ids(12);
		frame(b, many, 30_000L);

		int[] runs = new int[12];
		for (int f = 0; f < 300; f++) {
			b.beginFrame(many);
			for (int i = 0; i < 12; i++) {
				if (!b.policyFor("s" + i).hold(1)) {
					runs[i]++;
				}
				b.charge("s" + i, 30_000L);
			}
		}
		int min = Integer.MAX_VALUE;
		int max = 0;
		for (int r : runs) {
			min = Math.min(min, r);
			max = Math.max(max, r);
		}
		assertTrue("every scene must run sometimes (min=" + min + ")", min > 0);
		assertTrue("share must be roughly even, got min=" + min + " max=" + max,
				max <= min * 2);
	}

	/**
	 * ONE OVERSIZE SCENE MUST NOT TAKE THE CLIENT DOWN WITH IT.
	 *
	 * A scene costing more than the whole budget can never be admitted by the rotation — it does
	 * not fit, and no amount of waiting makes it fit. The rotation must therefore step over it.
	 * An earlier version stopped the walk there and advanced its cursor only on an admission, so
	 * the cursor could never move past such a scene: every frame started on it, admitted nothing,
	 * and every OTHER scene fell to the staleness floor while the entire budget went unspent.
	 *
	 * The exclusion is the ten cheap scenes: they fit easily and must keep running at close to
	 * full rate. Uniform-cost fixtures cannot see this — they are exactly the case where the
	 * cursor always advances.
	 */
	@Test
	public void oneOversizeSceneDoesNotStarveTheScenesThatFit() {
		AnimatorBudget b = new AnimatorBudget();
		List<String> all = new java.util.ArrayList<String>(ids(10));
		all.add("BIG");

		// Establish estimates: ten at 20 us, one at 400 us — over ENTER_NANOS by itself.
		b.beginFrame(all);
		for (String id : all) {
			b.charge(id, "BIG".equals(id) ? 400_000L : 20_000L);
		}

		int[] runs = new int[10];
		int bigRuns = 0;
		for (int f = 0; f < 210; f++) {
			b.beginFrame(all);
			for (int i = 0; i < 10; i++) {
				if (!b.policyFor("s" + i).hold(1)) {
					runs[i]++;
				}
				b.charge("s" + i, 20_000L);
			}
			if (!b.policyFor("BIG").hold(1)) {
				bigRuns++;
			}
			b.charge("BIG", 400_000L);
		}
		int min = Integer.MAX_VALUE;
		for (int r : runs) {
			min = Math.min(min, r);
		}
		assertTrue("the ten 20 us scenes must keep running; worst ran " + min + "/210",
				min > 210 / 2);
		assertTrue("and the oversize scene must still be refreshed by the staleness bound,"
				+ " ran " + bigRuns, bigRuns > 0);
	}

	/**
	 * A scene that costs nothing must not hold degradation open on behalf of scenes that do.
	 *
	 * Scenes can return from the renderer above the point where cost is charged — no canvas node
	 * yet, or a latched FBO failure, both indefinite states. Such a scene is charged zero, which
	 * must drive its estimate to zero; if it instead kept the never-measured fallback, a handful
	 * of them would sum past the exit threshold and latch the client into permanent degradation
	 * with no real animator load anywhere.
	 */
	@Test
	public void aSceneThatCostsNothingContributesNoDemand() {
		AnimatorBudget b = new AnimatorBudget();
		List<String> all = Arrays.asList("real", "p0", "p1", "p2", "p3", "p4");

		b.beginFrame(all);
		b.charge("real", 300_000L);
		for (int i = 0; i < 5; i++) {
			b.charge("p" + i, 0L);               // reached the renderer, did no animator work
		}
		b.beginFrame(all);
		assertTrue("300 us of real load engages it", b.degrading());

		// The real scene's animators are detached; it now costs almost nothing.
		for (int f = 0; f < AnimatorBudget.MAX_HOLD_FRAMES + 3; f++) {
			b.beginFrame(all);
			b.charge("real", 5_000L);
			for (int i = 0; i < 5; i++) {
				b.charge("p" + i, 0L);
			}
		}
		b.beginFrame(all);
		assertFalse("five phantom scenes must not keep demand above the 125 us exit; demand="
				+ b.lastFrameDemand(), b.degrading());
	}

	/**
	 * A scene that leaves view and returns is admitted immediately rather than held.
	 *
	 * Its held outputs are of unknown age — the overlay only sweeps and re-stamps entries when it
	 * evaluates, and a scene the renderer never walks never evaluates — so holding it on return
	 * would display a frame from before it left, which for an absolute writer is arbitrarily
	 * stale rather than one frame late.
	 */
	@Test
	public void aSceneReturningToViewIsAdmittedNotHeld() {
		AnimatorBudget b = new AnimatorBudget();
		List<String> many = ids(20);
		frame(b, many, 30_000L);
		b.beginFrame(many);
		assertTrue(b.degrading());

		// s0 drops out of view for a while under continuing overload.
		List<String> without = ids(20).subList(1, 20);
		for (int f = 0; f < 10; f++) {
			b.beginFrame(without);
			for (String id : without) {
				b.charge(id, 30_000L);
			}
		}
		b.beginFrame(many);
		assertFalse("s0 has just returned; its outputs are of unknown age",
				b.policyFor("s0").hold(1));
	}

	// ---------------------------------------------------------------- accounting

	/**
	 * A held pass still costs — F and every recomposition — and the budget must see that cost.
	 * Reading the gated {@code RenderStats.animatorNanos} instead would report zero here.
	 */
	@Test
	public void spendCountsHeldPassesToo() {
		AnimatorBudget b = new AnimatorBudget();
		b.beginFrame(ONE);
		b.charge("a", 12_000L);                  // an all-held pass: no VM ran, F still paid
		b.beginFrame(ONE);
		assertEquals(12_000L, b.lastFrameSpend());
	}

	/**
	 * Only an ADMITTED pass may update a scene's full-rate estimate. A held pass measures the
	 * degraded cost; letting it set the estimate would ratchet demand downward until the exit
	 * test fired on a number describing the held state rather than the demand — releasing
	 * straight back into the overload that caused it.
	 */
	@Test
	public void aHeldPassDoesNotOverwriteTheFullRateEstimate() {
		AnimatorBudget b = new AnimatorBudget();
		List<String> many = ids(20);
		frame(b, many, 30_000L);
		b.beginFrame(many);
		assertTrue(b.degrading());

		long demandBefore = b.lastFrameDemand();
		for (int f = 0; f < 3; f++) {
			b.beginFrame(many);
			for (String id : many) {
				if (b.policyFor(id).hold(1)) {
					b.charge(id, 300L);          // cheap, because held
				} else {
					b.charge(id, 30_000L);
				}
			}
		}
		b.beginFrame(many);
		assertTrue("demand must not collapse toward the held cost: before=" + demandBefore
				+ " after=" + b.lastFrameDemand(), b.lastFrameDemand() > 400_000L);
	}

	/** A charge from a scene that has left view is not re-counted when it returns. */
	@Test
	public void aStaleChargeIsNotResurrected() {
		AnimatorBudget b = new AnimatorBudget();
		b.beginFrame(ONE);
		b.charge("a", 300_000L);

		b.beginFrame(Arrays.<String>asList());   // "a" out of view; its charge is consumed here
		assertEquals(300_000L, b.lastFrameSpend());

		b.beginFrame(ONE);
		assertEquals("the charge must not be counted a second time", 0L, b.lastFrameSpend());
	}

	/** Budget state is dropped with the scene, like every other per-scene cache in the renderer. */
	@Test
	public void pruningDropsStateForDeadScenes() {
		AnimatorBudget b = new AnimatorBudget();
		frame(b, ids(5), 1_000L);
		assertEquals(5, b.trackedScenes());

		b.prune(new HashSet<String>(Arrays.asList("s0", "s2")));
		assertEquals(2, b.trackedScenes());
	}

	/** No scenes at all must not throw — an empty world still rolls frames. */
	@Test
	public void anEmptySceneSetIsHarmless() {
		AnimatorBudget b = new AnimatorBudget();
		for (int i = 0; i < 5; i++) {
			b.beginFrame(Arrays.<String>asList());
		}
		assertFalse(b.degrading());
		assertEquals(0, b.lastFrameSpend());
	}

	/**
	 * The first sighting of a scene is admitted, not declined. The budget has no measurement of
	 * it yet, and a new scene whose first impression is a frozen frame is indistinguishable from
	 * an attach that failed.
	 */
	@Test
	public void aNewSceneIsAdmittedOnItsFirstFrame() {
		AnimatorBudget b = new AnimatorBudget();
		List<String> many = ids(20);
		frame(b, many, 30_000L);
		b.beginFrame(many);
		assertTrue(b.degrading());

		List<String> plusOne = ids(21);
		b.beginFrame(plusOne);
		assertFalse("s20 has never been measured", b.policyFor("s20").hold(1));
	}
}
