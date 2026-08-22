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

	/**
	 * Enter degradation the sustained way: over-threshold spend on ENTER_STREAK_FRAMES
	 * consecutive rolls. One overspend frame no longer engages — that is the hitch debounce,
	 * pinned by its own tests below — so every test whose subject is what happens AFTER entry
	 * goes through here.
	 */
	private static void engage(AnimatorBudget b, List<String> ids, long nanosEach) {
		for (int i = 0; i < AnimatorBudget.ENTER_STREAK_FRAMES; i++) {
			frame(b, ids, nanosEach);
		}
		b.beginFrame(ids);
		for (String id : ids) {
			b.charge(id, nanosEach);
		}
	}

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
	public void degradationEntersOnlyAfterSustainedOverspend() {
		AnimatorBudget b = new AnimatorBudget();
		b.beginFrame(ONE);
		assertFalse("nothing measured yet", b.degrading());
		b.charge("a", 300_000L);

		b.beginFrame(ONE);
		assertFalse("ONE overspend frame is a hitch until proven otherwise -- the debounce",
				b.degrading());
		b.charge("a", 300_000L);

		b.beginFrame(ONE);
		assertTrue("the second consecutive overspend is an overload", b.degrading());
	}

	/**
	 * THE HITCH CASE, observed in the wild 2026-08-22: one alt-tab put ~2.4 ms into the timed
	 * window of a ~20 us scene and the budget spent ~7 frames holding a healthy scene. A single
	 * spike must not engage -- and the spiked ESTIMATE must heal by itself, because the frame
	 * after the spike is not degrading, so the scene is admitted and re-measured before demand
	 * is ever consulted. Both properties in one trace, numbers from the real incident.
	 */
	@Test
	public void aSingleHitchFrameNeitherEngagesNorPoisonsTheEstimate() {
		AnimatorBudget b = new AnimatorBudget();
		frame(b, ONE, 20_000L);                  // healthy baseline, estimate = 20 us
		b.beginFrame(ONE);
		b.charge("a", 2_400_000L);               // the alt-tab, verbatim

		b.beginFrame(ONE);
		assertFalse("a lone 2.4 ms frame is a hitch, not an overload", b.degrading());
		b.charge("a", 20_000L);                  // admitted normally -> re-measured

		b.beginFrame(ONE);
		assertFalse(b.degrading());
		assertEquals("the estimate healed on the very next admitted frame; 2400000 here means"
				+ " the spike stuck and the scene would be held as oversize on any future"
				+ " engagement", 20_000L, b.lastFrameDemand());
	}

	/** An isolated spike must not accumulate: over, normal, over, normal... never engages. */
	@Test
	public void alternatingSpikesNeverEngage() {
		AnimatorBudget b = new AnimatorBudget();
		frame(b, ONE, 20_000L);
		for (int i = 0; i < 10; i++) {
			frame(b, ONE, (i % 2 == 0) ? 2_400_000L : 20_000L);
			assertFalse("cycle " + i + ": the streak must reset on every under-threshold frame",
					b.degrading());
		}
	}

	/**
	 * BACK-TO-BACK hitch-magnitude frames MUST engage -- the debounce is a streak counter, not a
	 * magnitude filter. This is the test that separates the shipped design from the rejected
	 * alternative (discarding/clamping outlier charges): a clamp suppresses these frames and
	 * never engages, which for a genuinely 3 ms scene means unbounded frame cost with a green
	 * suite. Every other hitch test uses isolated spikes a clamp would also suppress, so without
	 * this one the two implementations are indistinguishable.
	 */
	@Test
	public void twoConsecutiveHitchMagnitudeFramesEngage() {
		AnimatorBudget b = new AnimatorBudget();
		frame(b, ONE, 20_000L);
		frame(b, ONE, 2_400_000L);
		frame(b, ONE, 2_400_000L);
		b.beginFrame(ONE);
		assertTrue("sustained hitch-magnitude spend IS an overload, whatever its size",
				b.degrading());
	}

	/**
	 * After a RELEASE, the streak must start from zero: one hitch immediately after leaving
	 * degradation must not re-engage. Without an explicit reset in the degrading branch the
	 * streak would still hold its entry value, and the first post-release spike would jump it
	 * past the threshold -- turning the debounce into a one-shot that only works before the
	 * budget's first engagement.
	 */
	@Test
	public void aHitchRightAfterReleaseDoesNotReengage() {
		AnimatorBudget b = new AnimatorBudget();
		engage(b, ONE, 300_000L);
		assertTrue(b.degrading());

		// EXACTLY MAX_HOLD_FRAMES cheap frames, so the roll below is the RELEASING roll and the
		// spike lands on the release frame itself. The panel proved the first version of this
		// test useless: with two extra loop iterations the release happened inside the loop, the
		// ordinary under-threshold reset zeroed a stale streak before the spike arrived, and the
		// mutant this test exists to kill (the degrading-branch reset deleted) survived the whole
		// suite while the javadoc claimed otherwise. The defect that mutant reintroduces is a
		// hitch charged during the releasing frame re-engaging off the stale entry streak.
		for (int i = 0; i < AnimatorBudget.MAX_HOLD_FRAMES; i++) {
			frame(b, ONE, 20_000L);
		}
		b.beginFrame(ONE);
		assertFalse("this roll is the release", b.degrading());
		b.charge("a", 2_400_000L);               // the hitch lands ON the releasing frame

		b.beginFrame(ONE);
		assertFalse("a single post-release spike is still just a hitch; degrading here means the"
				+ " entry streak survived degradation and the spike re-engaged off stale state",
				b.degrading());
	}

	/** A spike while ALREADY degrading changes nothing -- entry state machinery is entry-only. */
	@Test
	public void aSpikeDuringDegradationIsIrrelevant() {
		AnimatorBudget b = new AnimatorBudget();
		engage(b, ONE, 300_000L);
		assertTrue(b.degrading());
		frame(b, ONE, 2_400_000L);
		// The extra roll is the test. frame() rolls BEFORE charging, so without this the spike
		// would sit uncharged-into-any-decision and the assertion would read state computed
		// before the spike existed -- the panel showed a mutant that exits degradation on any
		// observed spike passing the one-roll version verbatim. This roll makes the degrading
		// branch actually process a 2.4 ms spend.
		b.beginFrame(ONE);
		assertTrue("still degrading; demand governs exit, and a spike is not demand",
				b.degrading());
	}

	/**
	 * THE THRESHOLD IS STRICT, both sides. Exactly at ENTER does not enter; exactly at EXIT does
	 * not leave. Pinned because an off-by-one in either comparison is invisible to every other
	 * test here, and produces a budget that engages one frame early or refuses to disengage.
	 */
	@Test
	public void theThresholdsAreExclusiveAtTheBoundary() {
		AnimatorBudget atEnter = new AnimatorBudget();
		frame(atEnter, ONE, AnimatorBudget.ENTER_NANOS);
		frame(atEnter, ONE, AnimatorBudget.ENTER_NANOS);
		atEnter.beginFrame(ONE);
		assertFalse("exactly at ENTER must not degrade, however sustained", atEnter.degrading());

		AnimatorBudget over = new AnimatorBudget();
		frame(over, ONE, AnimatorBudget.ENTER_NANOS + 1);
		frame(over, ONE, AnimatorBudget.ENTER_NANOS + 1);
		over.beginFrame(ONE);
		assertTrue("one nanosecond over, sustained past the debounce, must", over.degrading());

		// THE EXIT SIDE, which this test's javadoc always claimed and never drove: demand of
		// exactly EXIT_NANOS stays degrading (the comparison is >=), one below releases. The
		// admitted pass re-measures the estimate, so charging an admitted frame sets demand.
		AnimatorBudget atExit = new AnimatorBudget();
		engage(atExit, ONE, 300_000L);
		assertTrue(atExit.degrading());
		frame(atExit, ONE, AnimatorBudget.EXIT_NANOS);      // admitted or forced: re-measured
		for (int i = 0; i < AnimatorBudget.MAX_HOLD_FRAMES + 2; i++) {
			frame(atExit, ONE, AnimatorBudget.EXIT_NANOS);
		}
		atExit.beginFrame(ONE);
		assertTrue("demand exactly at EXIT must not release", atExit.degrading());

		AnimatorBudget under = new AnimatorBudget();
		engage(under, ONE, 300_000L);
		for (int i = 0; i < AnimatorBudget.MAX_HOLD_FRAMES + 2; i++) {
			frame(under, ONE, AnimatorBudget.EXIT_NANOS - 1);
		}
		under.beginFrame(ONE);
		assertFalse("one nanosecond under EXIT must release", under.degrading());
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
		engage(b, ONE, 300_000L);
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
		engage(b, ONE, 300_000L);
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
		engage(b, many, 30_000L);                // 600 us total: far over, sustained
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
		engage(b, many, 30_000L);
		b.beginFrame(many);
		assertTrue("the stability property must be tested in the DEGRADING state, where answers"
				+ " differ per scene -- without engagement every answer is false and stability"
				+ " is vacuous", b.degrading());

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
		engage(b, many, 30_000L);
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

		for (int r = 0; r < AnimatorBudget.ENTER_STREAK_FRAMES; r++) {
			b.beginFrame(all);
			b.charge("real", 300_000L);
			for (int i = 0; i < 5; i++) {
				b.charge("p" + i, 0L);           // reached the renderer, did no animator work
			}
		}
		b.beginFrame(all);
		assertTrue("300 us of sustained real load engages it", b.degrading());

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
		engage(b, many, 30_000L);
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
		engage(b, many, 30_000L);
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
		engage(b, many, 30_000L);
		b.beginFrame(many);
		assertTrue(b.degrading());

		List<String> plusOne = ids(21);
		b.beginFrame(plusOne);
		assertFalse("s20 has never been measured", b.policyFor("s20").hold(1));
	}
}
