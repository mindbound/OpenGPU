package opengpu.v2.mc.client.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * ANIM-16's client-global animator budget — the policy half, deliberately free of Minecraft.
 *
 * <h2>What it decides, and in what unit</h2>
 *
 * Once per client frame it chooses which scenes evaluate their animators at full rate. An admitted
 * scene holds nothing; an unadmitted scene holds every node. There is no partial admission, and
 * that is the central design decision rather than a simplification — see below.
 *
 * <h2>Why the unit is the SCENE and not the node</h2>
 *
 * The measured cost of one scene's animator pass is {@code F + W*walked + sum(E_node)}, with
 * F ~= 11-13 us of fixed per-evaluation cost, W ~= 37 ns per walked node, and E ~= 0.57 us for a
 * 2-op program (2-4 us for real content, 38-43 us at the op cap) — FIELD-TEST-ANIM16.md, runs 1
 * and 2. Holding a node declines only its own E term. F, the node walk, and the scene's GL
 * re-render — <i>"the larger half"</i>, per {@code SceneRenderer}'s own guard comment — are
 * declined only when the whole scene short-circuits at the render guard, which needs
 * {@code wouldEvaluate} to answer false, which needs EVERY node held.
 *
 * <p>{@code AnimatorOverlay.wouldEvaluate} returns true at the FIRST node that runs. So one
 * exempted node costs the scene its F and its GL render in full. A budget that guaranteed "one
 * node per scene evaluates every frame" would therefore guarantee that no scene ever
 * short-circuits, pinning an uncontrollable floor of {@code settledScenes * F} — about 12 us
 * each, crossing {@link #EXIT_NANOS} at roughly ten animated scenes. Past that it would enter
 * degradation and never leave, having declined only the cheapest term it has. An earlier draft of
 * this increment proposed exactly that; it is recorded here because it is the intuitive design and
 * it is self-defeating.
 *
 * <p>Scene granularity is also what DESIGN means by <i>"animators drop to reduced evaluation rate
 * (30/20 Hz)"</i>: a scene admitted every k-th frame IS that sentence. A per-node counter never
 * was.
 *
 * <h2>Decide once, answer from the decision</h2>
 *
 * {@link SceneBudget#hold} is a field read. It must be, because {@code AnimatorOverlay} asks it
 * from two places per frame — the render guard's walk and the evaluation loop — and requires the
 * same answer from both: a guard that short-circuits a scene the loop was about to animate freezes
 * it, and a guard that admits a scene the loop then declines pays full GL cost for no animation.
 * A budget that decremented capacity per call would break that, and the resulting freeze destroys
 * the very measurement that would end it — {@code evaluate} never runs, so the scene reports no
 * spend, so the budget never releases. Self-reinforcing, not self-correcting.
 *
 * <h2>It measures; it does not predict</h2>
 *
 * Each scene's cost estimate is its own last measured full-rate pass ({@link #charge}), not a
 * model. The overlay cannot be asked which nodes are holdable — {@code runsThisFrame} overrides a
 * hold silently when a node has no previous output or its program was swapped — so any prediction
 * of "I will save X" is wrong in ways nothing can detect. {@link #MODEL_SCENE_NANOS} exists only
 * as the first-sighting fallback, before a scene has ever been measured.
 */
final class AnimatorBudget {

	/**
	 * Enter degradation when the previous frame's measured animator spend exceeded this.
	 *
	 * 250 us is ~3% of a 120 Hz frame and ~1.5% of a 60 Hz one. In measured units it buys five
	 * 3.3-style demo scenes (45-50 us each), or about six at-cap nodes, before anything is held —
	 * so a legitimate build should not reach it. What it bounds is the multiplicative case:
	 * {@code MAX_NODES} 4096 at the op cap is ~170 ms of animator work in a single frame.
	 *
	 * <p>FREELY REVISABLE, unlike the validator caps. This is a client-side constant with no
	 * persistence, no wire format and no downgrade hazard — the same standing as
	 * {@code SceneRenderer.UPLOAD_BUDGET_PER_FRAME}. If a real build trips it, raising it is one
	 * line plus the field data that justified it.
	 */
	static final long ENTER_NANOS = 250_000L;

	/**
	 * Leave degradation when estimated FULL-RATE demand falls below this.
	 *
	 * Half of {@link #ENTER_NANOS}, and the asymmetry is the whole point. The exit test cannot
	 * read measured spend: the moment holding starts, measured spend falls by construction, so a
	 * measured-spend exit would release immediately and re-enter next frame, flapping at frame
	 * rate. It reads what the scenes WOULD cost if all were admitted, which holding does not
	 * change.
	 */
	static final long EXIT_NANOS = 125_000L;

	/**
	 * Consecutive over-threshold frames required to enter degradation.
	 *
	 * Two, because the two failure shapes separate perfectly at that number: a genuine overload
	 * is over on EVERY frame (field-measured: 8 scenes engaged the budget on 100% of frames
	 * across every window), while a wall-clock hitch — alt-tab, GC — is a single frame. One
	 * frame of delayed entry under a real overload costs one frame of unthrottled spend, which
	 * is the overload's own cost, once; a spurious engagement cost ~7 frames of holding a healthy
	 * scene every time the window lost focus (observed 2026-08-22).
	 *
	 * Higher values buy nothing: back-to-back hitches are rare, self-heal through the staleness
	 * bound even when they do slip through, and each unit here delays response to real overload.
	 */
	static final int ENTER_STREAK_FRAMES = 2;

	/**
	 * A scene may be held for at most this many consecutive frames before it is admitted whatever
	 * the budget says.
	 *
	 * NOT a fairness nicety — a correctness bound. A held node's animator output is recomposed
	 * over the current base, so relative writes keep tracking; but absolute writes and tint
	 * compose by rules that DISCARD the base, so those properties are frozen outright for the
	 * duration of the hold, not merely stepped. Six frames is 100 ms at 60 fps, i.e. a floor of
	 * 10 Hz on the refresh of a frozen absolute writer.
	 *
	 * <p>The forced admission is charged like any other, so the budget goes negative and the rest
	 * of the frame is disarmed — the same shape as the upload budget's admitted-anyway item.
	 */
	static final int MAX_HOLD_FRAMES = 6;

	/**
	 * What a never-yet-measured scene is assumed to cost, so its first admission is priced rather
	 * than free. Deliberately generous: F alone is 11-13 us measured, and a scene worth animating
	 * has nodes on top of that. Replaced by a real measurement after one admitted frame.
	 */
	static final long MODEL_SCENE_NANOS = 30_000L;

	/** One scene's budget state, and the policy object its overlay holds. */
	static final class SceneBudget implements AnimatorOverlay.HoldPolicy {

		final String sceneId;

		/**
		 * This frame's decision, written before the scene loop and only read during it.
		 *
		 * Starts true so a scene animates normally from its first frame: the budget has no
		 * measurement of it yet, and declining work before knowing its cost would make a new
		 * scene's first impression a frozen one.
		 */
		boolean admitted = true;

		/** Consecutive frames this scene has been held. Bounded by {@link #MAX_HOLD_FRAMES}. */
		int framesHeld;

		/** Last measured cost of an ADMITTED pass, or -1 before the first one. */
		long lastFullRateNanos = -1L;

		/** Accumulated during the current frame by {@link AnimatorBudget#charge}. */
		long chargedThisFrame;

		/** Whether the previous frame's scene loop visited this scene at all. */
		boolean seenLastFrame;

		SceneBudget(String sceneId) {
			this.sceneId = sceneId;
		}

		/**
		 * The whole point of the class: a field read, identical for every node of the scene and
		 * for every call within the frame.
		 */
		public boolean hold(int nodeId) {
			return !admitted;
		}

		long estimate() {
			return lastFullRateNanos >= 0 ? lastFullRateNanos : MODEL_SCENE_NANOS;
		}
	}

	private final Map<String, SceneBudget> scenes = new HashMap<String, SceneBudget>();

	/** Reused across frames so the frame roll allocates nothing. */
	private final List<SceneBudget> present = new ArrayList<SceneBudget>();

	private boolean degrading;
	/** Consecutive frames of over-threshold spend while NOT degrading; see the entry debounce. */
	private int overStreak;
	private long lastFrameSpend;
	private long lastFrameDemand;
	private int admittedLastFrame;

	/**
	 * The rotation cursor, as a scene id rather than an index.
	 *
	 * KEYED BY ID BECAUSE THE ITERATION ORDER IS A HASH ORDER. The renderer walks a
	 * {@code HashSet} of scene ids rebuilt every frame; its order is arbitrary and can change when
	 * the set's contents or capacity change. A cursor holding a position would either favour one
	 * hash-determined scene indefinitely or silently skip a scene when the set shifted. Holding an
	 * id costs a scan to re-find it and is correct under both.
	 */
	private String cursorSceneId;

	/** The policy for {@code sceneId}, created on first sight. */
	SceneBudget policyFor(String sceneId) {
		SceneBudget b = scenes.get(sceneId);
		if (b == null) {
			b = new SceneBudget(sceneId);
			scenes.put(sceneId, b);
		}
		return b;
	}

	/** Drop state for scenes that no longer exist, mirroring the renderer's own pruning. */
	void prune(java.util.Set<String> liveSceneIds) {
		for (Iterator<Map.Entry<String, SceneBudget>> it = scenes.entrySet().iterator();
				it.hasNext();) {
			if (!liveSceneIds.contains(it.next().getKey())) {
				it.remove();
			}
		}
	}

	/**
	 * Roll the frame and decide who runs — the single point at which the policy changes.
	 *
	 * @param sceneIds every scene the renderer is about to walk, in whatever order it will walk
	 *                 them; the decision does not depend on that order
	 */
	void beginFrame(Iterable<String> sceneIds) {
		present.clear();
		long demand = 0L;
		for (String id : sceneIds) {
			SceneBudget b = policyFor(id);
			demand += b.estimate();
			present.add(b);
		}
		// SPEND IS SUMMED OVER EVERY TRACKED SCENE, not just the ones present this frame. A scene
		// charged last frame and gone this frame still cost what it cost, and — the reason this is
		// a correctness point rather than an accounting one — leaving its counter unzeroed would
		// let a stale charge be re-counted whenever it came back into view.
		long spend = 0L;
		for (Map.Entry<String, SceneBudget> e : scenes.entrySet()) {
			spend += e.getValue().chargedThisFrame;
			e.getValue().chargedThisFrame = 0L;
		}
		lastFrameSpend = spend;
		lastFrameDemand = demand;

		// HYSTERESIS, ON TWO DIFFERENT QUANTITIES. Entry reads what the last frame actually cost,
		// because that is ground truth for "we are over". Exit reads what the scenes WOULD cost
		// at full rate, because measured spend collapses the moment holding starts and would
		// release us instantly into the same overload.
		//
		// ENTRY IS DEBOUNCED: the spend must exceed the threshold on ENTER_STREAK_FRAMES
		// CONSECUTIVE frames. A genuine overload sustains -- the field test's 8-scene arms were
		// over threshold on every frame of every window -- while a wall-clock hitch is isolated
		// by nature. Without this, one alt-tab or GC pause inside the timed animator window both
		// tripped entry AND poisoned the hitched scene's full-rate estimate, and the budget spent
		// the next ~7 frames holding a 20 us scene it believed cost 2.4 ms (observed in the wild,
		// 2026-08-22; the staleness bound recovered it, but the engagement was spurious).
		//
		// THE DEBOUNCE ALSO HEALS THE ESTIMATE in the observed failure shape: on the frame after
		// an isolated spike the budget is not yet degrading, so the scene is admitted normally,
		// re-measured at its real cost, and the poisoned estimate is overwritten before demand is
		// ever consulted -- demand only matters while degrading. That trace assumes the scene is
		// still visible and the budget disengaged; the two paths outside it are real but bounded
		// by the forced-admission machinery, not by the debounce: a spike charged to an ADMITTED
		// scene while already degrading inflates demand until rotation or the staleness bound
		// re-admits and re-measures it (at most MAX_HOLD_FRAMES frames of delayed exit), and a
		// spike on a scene's LAST VISIBLE frame survives in its estimate until the scene returns
		// to view, where the return is itself a forced admission -- one frame of inflated demand.
		// Clamping estimates instead would cover those two paths too, but it makes the recorded
		// numbers lie and interacts with the rotation's cost-vs-remaining test; frames-bounded
		// staleness was judged the cheaper defect.
		if (!degrading) {
			overStreak = spend > ENTER_NANOS ? overStreak + 1 : 0;
			if (overStreak >= ENTER_STREAK_FRAMES) {
				degrading = true;
			}
		} else {
			degrading = demand >= EXIT_NANOS;
			overStreak = 0;
		}

		if (!degrading) {
			for (int i = 0; i < present.size(); i++) {
				admit(present.get(i));
			}
			admittedLastFrame = present.size();
			markSeen();
			return;
		}
		admittedLastFrame = select();
		markSeen();
		// Reported only while engaged, so both counters stay at zero in a client that never
		// overloads and a non-zero reading always means the budget actually did something.
		opengpu.v2.stats.RenderStats.animatorBudgetFrames++;
		opengpu.v2.stats.RenderStats.animatorBudgetAdmissions += admittedLastFrame;
	}

	/**
	 * Choose scenes to admit, staleness first and then rotation, until the budget is spent.
	 *
	 * @return how many were admitted
	 */
	private int select() {
		for (int i = 0; i < present.size(); i++) {
			present.get(i).admitted = false;
		}
		long remaining = ENTER_NANOS;
		int admitted = 0;

		// PASS 1 — the correctness floor. A scene at the hold limit runs whatever the budget
		// says, and is charged anyway so the overspend is visible rather than absorbed. Same for
		// a scene the previous frame did not see: its held outputs are of unknown age, and a
		// scene re-entering view must not show a frozen frame from before it left.
		for (int i = 0; i < present.size(); i++) {
			SceneBudget b = present.get(i);
			if (b.framesHeld >= MAX_HOLD_FRAMES || !b.seenLastFrame) {
				admit(b);
				remaining -= b.estimate();
				admitted++;
			}
		}

		// PASS 2 — rotation.
		//
		// THE CURSOR ADVANCES BY EXACTLY ONE SCENE PER FRAME, unconditionally, and is set BEFORE
		// any admission decision. An earlier version advanced it only when a scene was admitted
		// and stopped the walk at the first scene that did not fit, which deadlocked: a scene
		// estimated above ENTER_NANOS can never be admitted here, so it could never become the
		// cursor, so once the cursor reached the scene before it every frame started on it, broke
		// immediately, and admitted nothing. Every scene in the client then fell to the
		// MAX_HOLD_FRAMES floor while the entire budget went unspent, and the state was
		// self-perpetuating because only an admission could have moved the cursor. One oversize
		// scene -- seven at-cap nodes, which is what ENTER_NANOS is sized to catch -- took
		// everything else down with it.
		//
		// A SCENE THAT DOES NOT FIT IS SKIPPED, not stopped on. The earlier comment argued that
		// skipping starves expensive scenes and left MAX_HOLD_FRAMES doing all the work; it had
		// the direction backwards. An oversize scene is not saved by stopping the walk -- it
		// still does not fit -- so stopping only denies the budget to scenes that DO. Bounding
		// the expensive scene's staleness is pass 1's job, and pass 1 already does exactly that.
		int start = rotationStart();
		if (!present.isEmpty()) {
			cursorSceneId = present.get(start).sceneId;
		}
		for (int n = 0; n < present.size(); n++) {
			SceneBudget b = present.get((start + n) % present.size());
			if (b.admitted) {
				continue;
			}
			long cost = b.estimate();
			if (cost > remaining) {
				continue;
			}
			admit(b);
			remaining -= cost;
			admitted++;
		}
		return admitted;
	}

	private int rotationStart() {
		if (cursorSceneId != null) {
			for (int i = 0; i < present.size(); i++) {
				if (cursorSceneId.equals(present.get(i).sceneId)) {
					return (i + 1) % present.size();
				}
			}
		}
		return 0;
	}

	private void admit(SceneBudget b) {
		b.admitted = true;
		b.framesHeld = 0;
	}

	private void markSeen() {
		for (int i = 0; i < present.size(); i++) {
			SceneBudget b = present.get(i);
			if (!b.admitted) {
				b.framesHeld++;
			}
			b.seenLastFrame = true;
		}
		// A scene the renderer did not walk this frame is invisible, not held: its overlay never
		// ran, so nothing about it changed and its held outputs are simply old. Clearing the flag
		// is what makes pass 1 admit it on return.
		for (Map.Entry<String, SceneBudget> e : scenes.entrySet()) {
			if (!present.contains(e.getValue())) {
				e.getValue().seenLastFrame = false;
				e.getValue().framesHeld = 0;
			}
		}
	}

	/**
	 * Record what a scene's animator pass actually cost.
	 *
	 * <p>CALLED UNCONDITIONALLY, and that is not the same as {@code RenderStats.onAnimatorEvaluate},
	 * which fires only when at least one node ran a VM. That gate is right for the per-node
	 * instrument and wrong here: an all-held pass still pays F and every recomposition, so a
	 * budget reading the gated counter would see spend collapse to zero the instant it started
	 * holding, release, spike, and re-enter — oscillating at frame rate while blind to the cost it
	 * was actually incurring.
	 */
	void charge(String sceneId, long nanos) {
		SceneBudget b = policyFor(sceneId);
		b.chargedThisFrame += nanos;
		if (b.admitted) {
			// Only an admitted pass measures full-rate cost; a held pass measures the degraded
			// cost and would ratchet the estimate down until the exit test fired on a number that
			// describes the degraded state rather than the demand.
			b.lastFullRateNanos = nanos;
		}
	}

	boolean degrading() {
		return degrading;
	}

	long lastFrameSpend() {
		return lastFrameSpend;
	}

	long lastFrameDemand() {
		return lastFrameDemand;
	}

	int admittedLastFrame() {
		return admittedLastFrame;
	}

	int trackedScenes() {
		return scenes.size();
	}
}
