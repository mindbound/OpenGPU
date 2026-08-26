package opengpu.v2.mc.client.render;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import opengpu.v2.ocsl.IrCodec;
import opengpu.v2.ocsl.IrValidator;
import opengpu.v2.ocsl.OcslCompose;
import opengpu.v2.ocsl.OcslTime;
import opengpu.v2.ocsl.OcslVm;
import opengpu.v2.ocsl.OcslWire;
import opengpu.v2.ocsl.OcslWriteBoundary;
import opengpu.v2.ocsl.SurfaceTable;
import opengpu.v2.scene.ProgramInfo;
import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneState;

/**
 * Phase 3.3a — the per-frame animator evaluation, and the side map holding its results.
 *
 * <h2>Why a side map and not a field on SceneNode</h2>
 *
 * The composed value must NEVER reach {@link SceneState}. That is not tidiness: SceneState is what
 * snapshots encode, what {@code contentEquals} certifies convergence over, and what persistence
 * writes to disk. An animator output landing there would be replicated as though the server had
 * set it, would make two clients rendering the same scene at different frame times read as
 * DIVERGENT, and would bake a frame's worth of animation into the save. Keeping it out by
 * construction — a map the scene model cannot see — is what makes "persistence sees the base"
 * true without anyone having to remember it. {@link NodeInterpolator} holds its tracks the same
 * way and for the same reason.
 *
 * <h2>Evaluation order is ascending node id, and that is load-bearing</h2>
 *
 * Parent registers carry the parent's COMPOSED value (decided 2026-08-20). ANIM-7's raw-only rule
 * is about a node's OWN properties, where the composed value would be self-referential — a child
 * reading its parent is not, so the rule does not reach. Composed is also the only answer that
 * makes the canonical use work: a label counter-rotating against a spinning parent needs the
 * parent's effective rotation, and raw fails exactly when the parent is animated.
 *
 * That requires parents to be evaluated first, which costs nothing here: {@code SceneNode.parent}
 * is refused unless strictly below the child's id, so ascending id order IS a topological order,
 * and {@code SceneState.nodes} is a TreeMap. No sort, no traversal, and it stays correct if Stage
 * C lifts the one-level nesting limit.
 *
 * <h2>The composition base is the INTERPOLATED display transform</h2>
 *
 * DESIGN (ANIM-7): "The interpolated display transform is the <i>composition base</i> — renderer
 * behaviour, carrying no purity claim." A relative write lands on the base as DISPLAYED, so an
 * animated node whose server base is mid-glide moves with the glide instead of stepping at 20 Hz
 * — composing over the raw field would reintroduce, for animated nodes only, the stepping
 * {@link NodeInterpolator} exists to remove. The OWN-property registers stay RAW per the same
 * ANIM-7 paragraph, which is why a program reading {@code x} sees a value up to one interpolation
 * window out of step with the base its output lands on. The parent registers carry the parent's
 * composed-over-displayed value, because "the parent's effective rotation" means the one it is
 * drawn with. A null interpolation source composes over the raw fields — the same convention as
 * {@code Canvas2dRenderer.renderScene}, and what headless tests exercise.
 *
 * <h2>The side map survives the frame boundary (ANIM-16, 2026-08-21)</h2>
 *
 * It used to be cleared at the top of every pass, which made several properties free: an entry
 * could only describe the current frame, and detach snapped because the entry simply stopped
 * being recreated. Holding needs the opposite — a node whose program is skipped has to find its
 * previous output still there — so the map now persists and each pass stamps what it touched.
 * Three things move from "free" to "enforced" as a result, and all three are the kind that fail
 * silently:
 *
 * <ul>
 *   <li><b>A node the pass abandons loses its entry immediately</b>, inside the loop — detach, a
 *       program freed out from under the attachment, an undecodable or wrong-stage blob. NOT left
 *       to the sweep, because {@link #bindParentProperties} reads the map DURING the loop and
 *       would otherwise hand a child its parent's already-abandoned output.</li>
 *   <li><b>{@link #sweepUntouched} drops what the loop never visited</b> — a node deleted from the
 *       scene outright, which the loop cannot reach to remove.</li>
 *   <li><b>The no-clock path clears explicitly</b>, because a scene that lost its anchor
 *       recomposing over a moving base would go on animating plausibly instead of falling back to
 *       its server values.</li>
 * </ul>
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * It does not decide WHICH nodes to hold. {@link HoldPolicy} is the seam, and since 2026-08-22
 * {@code AnimatorBudget} drives it: a scene the budget declines to admit holds every node.
 * {@link #EVALUATE_ALWAYS} is now the fallback for an overlay with no policy installed, not the
 * shipped behaviour.
 *
 * It does not touch GL and it does not read the renderer — the renderer reads IT, at
 * {@code Canvas2dRenderer.readTransform} (via {@link #overlayTransform}) and {@code beginNode}
 * (via {@link #tintFactor}), wired 2026-08-21 in Phase 3.3b. Kept separate so everything that
 * DECIDES anything is testable headlessly, and the Forge-bound half is pure wiring.
 */
final class AnimatorOverlay {

	/**
	 * {@code sinceAttach} saturates here — 600 s, decided 2026-08-20.
	 *
	 * DERIVED FROM WHAT IT BOUNDS, not borrowed. An earlier draft reached for
	 * {@link OcslTime#PERIOD_SECONDS}, which would have been a bound taken from a neighbouring
	 * concept: <i>P</i> is the wrap period of a different clock and its value says nothing about
	 * how long a one-shot should run. 600 s is past any ease, intro or decay anyone would author,
	 * while still settling within a session rather than "eventually".
	 *
	 * Precision is not the binding constraint at this magnitude: a float32 ulp at 600 is ~61 us —
	 * two orders below a 6.944 ms frame at 144 Hz, the figure {@code OcslTime} reasons against
	 * (an earlier draft said three, which would need a 61 ms frame, about 16 fps) — and it stays
	 * under one server tick out past 140,000 s. The
	 * constraint is semantic, and saturation is what makes ease-and-settle expressible at all —
	 * a wrapped clock is monotone nowhere and a growing one settles nowhere.
	 *
	 * NOT FREELY RAISEABLE, unlike the validator caps. Raising MAX_STRUCTURAL_OPS is
	 * behaviour-neutral; raising this changes what an existing program COMPUTES, so it is closer
	 * to format identity than to acceptance policy. No register publishes it, deliberately: a
	 * program needs <i>P</i> to build seam-continuous cycles, a real mathematical need, and has no
	 * equivalent need for this — one scaling by CAP would be expressing a fraction of an arbitrary
	 * constant.
	 */
	static final float SINCE_ATTACH_CAP_SECONDS = 600.0f;

	/**
	 * One past the highest animator property id, so {@code Composed.raw} and every loop that walks
	 * the property space are sized from ONE place rather than from whichever ids happen to be
	 * reachable today. {@code PROP_ANIM_SZ} is the current highest (10).
	 */
	private static final int PROPERTY_LIMIT = OcslWire.PROP_ANIM_SZ + 1;

	/**
	 * One node's composed, clamped output. Only the properties its program actually wrote.
	 *
	 * <h3>It carries the RAW output too, and that is what makes holding possible</h3>
	 *
	 * The composed fields are {@code compose(base, raw)} — a function of the base the frame
	 * displayed when the program ran. Reusing a composed value on a later frame therefore freezes
	 * the BASE as well as the animator, so a node whose server position is still changing would
	 * stop dead: under a relative write {@code disp = base + raw}, a stale {@code disp} discards
	 * every base update since. Keeping {@code raw} and the write form lets a held frame recompose
	 * over the CURRENT base, which freezes only the animator's own contribution — the thing the
	 * budget actually declined to compute.
	 *
	 * That distinction is not cosmetic. Degradation that freezes a node entirely is
	 * indistinguishable in-game from the evaluator being broken, which is the failure mode 3.4
	 * was sequenced before 3.3 to keep unambiguous.
	 *
	 * <h3>WHICH writes keep tracking, stated exactly</h3>
	 *
	 * Recomposition can only preserve motion for rules that USE the base, so the honest claim is
	 * narrower than "held nodes keep moving":
	 *
	 * <ul>
	 *   <li><b>Relative x/y/rot2d (ADD) and sx/sy (MULTIPLY): track.</b> The base is an operand,
	 *       so server-driven and interpolated motion continue at full rate under a hold.</li>
	 *   <li><b>Absolute writes ({@code OP_OUT_ABS}) and tint (RULE_REPLACE): do not, and cannot.</b>
	 *       Those rules discard the base by definition — {@code OcslWriteBoundary} consults it only
	 *       as the fallback for a REJECTED write — so a held frame reproduces the last output
	 *       exactly. That is not a defect of recomposition; it is what "absolute" means. It does
	 *       mean a held absolute writer is frozen for the duration, so the budget must bound how
	 *       long a hold can last rather than assuming holds are visually free.</li>
	 * </ul>
	 *
	 * <h3>The case recomposition gets WRONG, recorded rather than fixed</h3>
	 *
	 * A program whose output COMPENSATES for the base — {@code rot2d = -parent.rot2d} for a label
	 * that stays upright on a spinning dial, {@code sx = 1/parent.sx} for a constant-width outline —
	 * reads its own or its parent's registers, and those are frozen along with everything else
	 * while the transform they cancel keeps advancing. Held, the label rotates at the dial's full
	 * rate and snaps back on release. Freezing the composed value instead does not fix it either:
	 * the parent transform is applied downstream in {@code NodeFold}, so the child still swings.
	 * <b>No output-reuse scheme can hold a base-compensating program correctly</b> — the only sound
	 * answers are to not hold it, or to hold it only briefly.
	 *
	 * Deliberately NOT enforced here, because the mechanism cannot tell which programs those are
	 * cheaply: the obvious test — "does the program's frame map an own/parent register" — is
	 * useless, since {@code IrValidator} lays out a slot for every typed built-in whether the
	 * program reads it or not, so it answers yes for every animator program. Detecting it needs an
	 * operand scan that knows which opcodes carry non-register operands ({@code FOR}'s trip count,
	 * {@code OUT}'s property id, and any others), and shipping a half-audited scan on the render
	 * thread is worse than shipping a documented limit. <b>This is a required input to ANIM-16's
	 * policy, not an open bug in this class.</b> None of the four shipped demos is affected — they
	 * read only {@code time}, {@code nodeSeed} and {@code sinceAttach}.
	 */
	static final class Composed {
		int writtenMask;
		/** Bit per property: the write form the program used ({@code OP_OUT_ABS} vs relative). */
		int absoluteMask;
		double x, y, sx, sy, rot;
		/** RGBA 0..1, composed and clamped; packing to ARGB is the renderer's business. */
		final float[] tint = new float[4];

		/**
		 * The VM's own output per transform property, indexed BY PROPERTY ID — uncomposed and
		 * unclamped, exactly as {@code vm.output} produced it.
		 *
		 * Indexing by id rather than by a private ordinal is deliberate — a second numbering is a
		 * second thing to keep in step, and this one would be silently wrong rather than loudly out
		 * of range.
		 *
		 * SIZED TO THE WHOLE ANIMATOR PROPERTY SPACE, not to the five ids reachable today. Sizing
		 * it to {@code PROP_ANIM_ROT2D + 1} was correct and was a trap: {@link #applyProperty}'s
		 * Stage C filter is an explicit three-id blacklist whose comment promises it will OPEN
		 * ("When Stage C lands, this is the guard that opens"), and {@code PROP_ANIM_SZ} is 10.
		 * Opening the guard without also resizing here is an ArrayIndexOutOfBoundsException on the
		 * render thread, from an edit whose author has no reason to look at this line. Six unused
		 * floats per animated node buys that away.
		 */
		final float[] raw = new float[PROPERTY_LIMIT];
		/** The VM's own RGBA tint output, uncomposed and unclamped. */
		final float[] rawTint = new float[4];

		/**
		 * The frame this entry was last refreshed on. Entries not touched by a pass are dropped,
		 * which is how a node REMOVED from the scene stops animating; the in-loop removals handle
		 * detach and the rest.
		 */
		int frameSeq;

		/**
		 * Which program produced everything above. A held entry is only reusable while the node is
		 * still attached to THAT program.
		 *
		 * Without this, swapping a node's animator while the budget holds it replays the OLD
		 * program's frozen output for as long as the hold lasts — and {@code ServerScene.setAnimator}
		 * replaces an attachment DIRECTLY, one delta, never passing through 0, so neither the
		 * detach removal above nor the sweep sees anything happen. In-game that reads as "attaching
		 * the new animator did nothing", which is indistinguishable from the attach call failing.
		 */
		int programId;

		boolean wrote(int propertyId) {
			return (writtenMask & (1 << propertyId)) != 0;
		}

		boolean isAbsolute(int propertyId) {
			return (absoluteMask & (1 << propertyId)) != 0;
		}
	}

	private final Map<Integer, Composed> byNode = new HashMap<Integer, Composed>();

	/**
	 * Which nodes may skip their program this frame — ANIM-16's degradation seam.
	 *
	 * <b>LIVE SINCE 2026-08-22.</b> {@code AnimatorBudget} installs a per-scene policy at
	 * {@code SceneGl} creation, so this can and does answer {@code true} on an overloaded client.
	 * It was inert when the seam landed in increments 3–4 — the budget it steers needed a per-node
	 * cost constant that had not been measured yet — and that fact was written here in terms
	 * ("INERT IN PRODUCTION AS SHIPPED") which stopped being true the moment increment 5 wired the
	 * budget.
	 *
	 * <b>The contract has not changed and still binds:</b> whatever answers this must give the
	 * SAME answer for a node throughout one frame. It is asked from two places per frame — the
	 * render guard's walk and the evaluation loop — and a disagreement freezes the scene outright.
	 */
	interface HoldPolicy {
		/** True to reuse {@code nodeId}'s previous output instead of running its program. */
		boolean hold(int nodeId);
	}

	static final HoldPolicy EVALUATE_ALWAYS = new HoldPolicy() {
		public boolean hold(int nodeId) {
			return false;
		}
	};

	private HoldPolicy holdPolicy = EVALUATE_ALWAYS;

	void holdPolicy(HoldPolicy policy) {
		this.holdPolicy = policy == null ? EVALUATE_ALWAYS : policy;
	}

	/**
	 * Decoded programs, keyed by program id.
	 *
	 * SAFE TO CACHE BY ID because ids are never reused — {@code nextProgramId} only climbs, and a
	 * freed id stays dead rather than returning attached to different code. That property was
	 * chosen for the attach path's sake and pays again here: without it this cache would need
	 * invalidating whenever the program table changed, and a stale entry would run yesterday's
	 * program forever with nothing to detect it.
	 *
	 * WITHIN ONE SCENE INCARNATION ONLY. An epoch change is a new {@code ServerScene}, whose
	 * {@code nextProgramId} restarts at 1 — so the new epoch's program 1 can be different code
	 * under an id this cache already holds, and {@link #pruneCaches} cannot see it (the id is
	 * still present). {@link #reset} exists for exactly that seam and must be called wherever
	 * {@code NodeInterpolator.reset} is.
	 *
	 * One VM per PROGRAM, not per node: the frame is scratch space, and evaluation is sequential
	 * on one thread, so N nodes sharing an attachment share its VM and its zero-allocation frame.
	 */
	private final Map<Integer, Compiled> vms = new HashMap<Integer, Compiled>();

	/**
	 * A decoded program and its ownership declaration, cached together.
	 *
	 * The declaration is {@link opengpu.v2.ocsl.IrProgram#outProperties} — the SAME method the
	 * attach guard uses, so both halves of the system read ownership from one place. A first draft
	 * re-derived it every frame by calling {@code vm.isAbsolute} on each candidate property and
	 * CATCHING the IllegalArgumentException for the ones the program does not write: up to five
	 * constructed exceptions with stack traces per node per frame, on the render thread, inside a
	 * class advertising a zero-allocation frame. Review caught it. Exceptions are not a lookup
	 * mechanism, and the lookup already existed.
	 */
	private static final class Compiled {
		final OcslVm vm;
		final int[] written;
		final boolean[] absolute;

		Compiled(OcslVm vm, int[] written, boolean[] absolute) {
			this.vm = vm;
			this.written = written;
			this.absolute = absolute;
		}
	}

	/** Program ids that failed to decode or validate; skipped without retrying every frame. */
	private final Map<Integer, Boolean> broken = new HashMap<Integer, Boolean>();

	private final float[] scratch4 = new float[4];
	/** The current node's displayed base TRS — what relative writes compose over. */
	private final double[] baseTrs = new double[NodeFold.TRS_WIDTH];
	/** The parent's displayed base TRS, for the parent registers' unanimated fallback. */
	private final double[] parentTrs = new double[NodeFold.TRS_WIDTH];
	/** The displayed-base source for the frame being evaluated; null = raw fields. */
	private NodeInterpolator interp;
	private long frameNanos;

	/**
	 * Which pass is running, so {@link #sweepUntouched} can tell this frame's entries from the
	 * leftovers. WRAPPING IS HARMLESS, unusually for a sequence used in an equality test: an entry
	 * is stamped by every pass that keeps it and removed by the very next pass that does not, so
	 * no entry can survive long enough to meet its own stamp again 2^32 passes later.
	 */
	private int frameSeq;

	/** The raw-base form: composes over the raw server fields. For tests and for callers with
	 *  no interpolator — the same convention as {@code Canvas2dRenderer.renderScene}. */
	void evaluate(SceneState state, long renderInstant, long sessionTickOffset,
			boolean clockKnown) {
		evaluate(state, renderInstant, sessionTickOffset, clockKnown, null, 0L);
	}

	/**
	 * Evaluate every attached node in {@code state} for this frame.
	 *
	 * @param renderInstant     {@code NodeInterpolator.renderInstant} — the SAME instant
	 *                          interpolation is replaying against (ANIM-4: one time sample per
	 *                          frame per scene), or Long.MIN_VALUE if the timeline is unprimed
	 * @param sessionTickOffset {@code SceneMirror.sessionTickOffset}: world time + this =
	 *                          the server-tick domain {@code renderInstant} counts
	 * @param clockKnown        {@code SceneMirror.animatorClockKnown} — false until a snapshot
	 *                          carrying a stamped anchor has landed
	 * @param interp            the displayed-base source, or null to compose over raw fields.
	 *                          MUST be the same interpolator, sampled at the same
	 *                          {@code nowNanos}, that the renderer will draw with — a different
	 *                          pair would compose animator output over a base the frame does not
	 *                          display
	 * @param nowNanos          the frame's local clock sample, as passed to {@code renderScene}
	 */
	void evaluate(SceneState state, long renderInstant, long sessionTickOffset,
			boolean clockKnown, NodeInterpolator interp, long nowNanos) {
		this.interp = interp;
		this.frameNanos = nowNanos;
		// NOT cleared any more, and the replacement is the loop's frameSeq stamp plus the sweep
		// at the bottom. The map has to survive the frame boundary for a held entry to exist at
		// all, so "detach snaps without a special case" — which the blanket clear used to give
		// for free — becomes a special case, and it is the sweep. An entry no pass touched is an
		// entry whose node was detached, removed, or went dangling, and it goes.
		frameSeq++;
		// NO CLOCK, NO ANIMATION, and failing closed is the point. Without the anchor a stamp
		// cannot be placed in the render clock's domain at all, and the plausible fallbacks are
		// worse than nothing: offset 0 puts every scene's epoch at the start of the session, so
		// every animator would run from a phase that is wrong by the whole magnitude of world
		// time — moving smoothly and confidently at the wrong point in its cycle, which is far
		// harder to notice than a node that simply does not move.
		if (!clockKnown || renderInstant == Long.MIN_VALUE) {
			// Explicit now that the top of the method no longer clears. Without this the last
			// good frame's output would persist — and worse, would keep RECOMPOSING over a moving
			// base, so a scene that lost its clock would go on animating plausibly instead of
			// falling back to its server values. Failing closed has to survive the map becoming
			// persistent.
			byNode.clear();
			return;
		}

		float time = OcslTime.time(renderInstant,
				sessionTickOf(state.creationWorldTime, sessionTickOffset));

		// Ascending id: SceneState.nodes is a TreeMap and parent < child is an allocator
		// invariant, so every parent is composed before any child that reads it.
		for (Map.Entry<Integer, SceneNode> entry : state.nodes.entrySet()) {
			SceneNode node = entry.getValue();
			Integer id = entry.getKey();
			// DROPPED HERE, NOT LEFT TO THE SWEEP, and the difference is a real one-frame bug.
			// The sweep runs after the loop, but `bindParentProperties` reads `byNode` DURING it —
			// so an abandoned parent that merely went unstamped would still be sitting in the map,
			// fully populated, when its child is evaluated later in the same pass. The child would
			// compose against an animator output the pass had already decided no longer exists,
			// while the renderer (which reads the map after the sweep) draws the parent at its
			// server base: one frame of internal inconsistency, of the size of the parent's whole
			// animated offset, exactly at the moment of detach. The old blanket clear made this
			// impossible for free; making the map persistent is what put it back.
			if (node.animator == 0) {
				byNode.remove(id);
				continue;
			}
			ProgramInfo info = state.programs.get(Integer.valueOf(node.animator));
			if (info == null) {
				// A DANGLING attachment, which ANIM-17 rules legal: the program was freed while
				// still attached. The node renders at its server value, exactly as it would one
				// tick before the attach — not an error, and not worth a diagnostic every frame.
				byNode.remove(id);
				continue;
			}
			Compiled compiled = vmFor(node.animator, info);
			if (compiled == null) {
				byNode.remove(id);
				continue; // decode/validate failed; recorded once in `broken`
			}
			Composed held = byNode.get(id);
			// ADMISSION BEFORE THE WORK. The predicate is shared with the render guard rather
			// than restated here, because the two disagreeing is the defect that would hurt: a
			// guard that short-circuits a scene this loop was about to animate freezes it
			// outright, and nothing downstream could tell that from a broken program.
			if (runsThisFrame(node, held)) {
				// AFTER every skip and BEFORE the work: this counts nodes a VM actually ran for,
				// which is the denominator ANIM-16's per-node constant needs. Charged here rather
				// than at the top of the loop so dangling attachments, detached nodes and broken
				// blobs — all of which cost nothing — cannot inflate it and make the per-node mean
				// read low. Held nodes are charged to their own counter for the same reason.
				opengpu.v2.stats.RenderStats.animatorNodesEvaluated++;
				Composed out = evaluateNode(compiled, node, state, time, renderInstant,
						sessionTickOffset, held);
				if (out == null) {
					// The program wrote nothing this frame. Drop any earlier entry rather than
					// leaving it to be swept: a stale value must not survive the pass that
					// decided there is none.
					byNode.remove(id);
					continue;
				}
				out.frameSeq = frameSeq;
				out.programId = node.animator;
				byNode.put(id, out);
			} else {
				opengpu.v2.stats.RenderStats.animatorNodesHeld++;
				// The whole point of holding: the animator's own output stays where it was, and
				// the base underneath it does not.
				recompose(held, node);
				held.frameSeq = frameSeq;
			}
		}
		sweepUntouched();
		pruneCaches(state);
	}

	/**
	 * Will any node in {@code state} run its program on the next pass? The render guard's fifth
	 * conjunct asks through here.
	 *
	 * <p>SHARES {@link #runsThisFrame} WITH THE LOOP, which is the whole reason this is a method
	 * rather than a repeated condition: if the guard concluded "nothing to animate" while
	 * {@link #evaluate} still ran a program, the scene would be composed and never drawn. (The
	 * loop calls {@code runsThisFrame} directly — it already holds the node and its entry — so
	 * what the two share is the predicate, not this method.)
	 *
	 * <p><b>It is deliberately CONSERVATIVE, not exact.</b> {@code evaluate} skips three further
	 * cases this walk does not look at: a dangling attachment, an undecodable blob, and a
	 * wrong-stage program. A scene whose only attachments are of those kinds answers true here and
	 * runs no program at all. That errs toward rendering, which is the safe direction — the
	 * dangerous one is claiming no work while the loop animates — but it means the budget can
	 * never suppress the re-render of a scene whose only animator is dangling. Worth closing when
	 * the budget lands; not worth decoding blobs inside a render guard to close now.
	 *
	 * <p>Under {@link #EVALUATE_ALWAYS} this returns exactly what
	 * {@code SceneState.hasAttachedAnimator()} returns, including for those same attachments —
	 * deliberately, so that swapping the guard over to this method is a no-op until a policy says
	 * otherwise.
	 */
	boolean wouldEvaluate(SceneState state) {
		for (SceneNode node : state.nodes.values()) {
			if (node.animator == 0) {
				continue;
			}
			if (runsThisFrame(node, byNode.get(Integer.valueOf(node.id)))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A node runs its program unless a policy holds it AND there is something VALID to hold.
	 *
	 * Two guards ahead of the policy, both of them cases where the policy's answer is simply not
	 * available to act on:
	 *
	 * <ul>
	 *   <li><b>Nothing to hold.</b> A node with no previous output has nothing to recompose, so
	 *       holding it would show the server value while the budget believed it was showing a
	 *       frozen animation — a first frame that silently skips is a node that never starts
	 *       moving. Holding reuses work, and is unavailable before any work exists.</li>
	 *   <li><b>Held output belongs to a different program.</b> See {@link Composed#programId}.</li>
	 * </ul>
	 *
	 * <p><b>Contract on {@link HoldPolicy#hold}: it must answer the same for a given node
	 * throughout one frame.</b> This method is reached from two places per frame — the render
	 * guard's walk and the evaluation loop — and the guarantee that the guard cannot short-circuit
	 * a scene this loop was about to animate rests entirely on both getting the same answer. A
	 * budget implemented by decrementing a counter per CALL would break that and freeze scenes.
	 * The shape that satisfies it is to decide once per frame and answer from the decision.
	 */
	private boolean runsThisFrame(SceneNode node, Composed held) {
		return held == null || held.programId != node.animator || !holdPolicy.hold(node.id);
	}

	/** The composed values for a node, or null if it has none this frame. */
	Composed of(int nodeId) {
		return byNode.get(Integer.valueOf(nodeId));
	}

	boolean isEmpty() {
		return byNode.isEmpty();
	}

	/**
	 * Forget everything, including the compiled programs. For an epoch change: a new scene
	 * incarnation restarts the program id space, so the never-reused property the VM cache leans
	 * on holds only within one epoch — the new epoch's program 1 can be different code under an
	 * id {@link #pruneCaches} would consider live. Mirrors {@code NodeInterpolator.reset} and
	 * must be called beside it.
	 */
	void reset() {
		byNode.clear();
		vms.clear();
		broken.clear();
	}

	/**
	 * Substitute this node's composed transform properties into a displayed TRS vector, leaving
	 * properties its program did not write at the value already there. The renderer calls this
	 * from {@code readTransform}, AFTER the interpolated read — the vector holds the displayed
	 * base, and a written property's composed value replaces it wholesale because it was composed
	 * over that same base.
	 */
	void overlayTransform(int nodeId, double[] trs) {
		Composed c = byNode.get(Integer.valueOf(nodeId));
		if (c == null) {
			return;
		}
		if (c.wrote(OcslWire.PROP_ANIM_X)) {
			trs[NodeFold.TRS_X] = c.x;
		}
		if (c.wrote(OcslWire.PROP_ANIM_Y)) {
			trs[NodeFold.TRS_Y] = c.y;
		}
		if (c.wrote(OcslWire.PROP_ANIM_ROT2D)) {
			trs[NodeFold.TRS_ROT] = c.rot;
		}
		if (c.wrote(OcslWire.PROP_ANIM_SX)) {
			trs[NodeFold.TRS_SX] = c.sx;
		}
		if (c.wrote(OcslWire.PROP_ANIM_SY)) {
			trs[NodeFold.TRS_SY] = c.sy;
		}
	}

	/**
	 * The node's displayed tint factor, in {@code NodeFold}'s RGBA channel order: the animator's
	 * composed-and-clamped tint when its program wrote one, else the raw packed tint unpacked.
	 * Tint composes by REPLACE, so unlike the transforms there is no base to substitute into —
	 * this IS the child factor {@code NodeFold.foldTint} consumes.
	 */
	void tintFactor(int nodeId, int rawPackedTint, double[] out) {
		Composed c = byNode.get(Integer.valueOf(nodeId));
		if (c != null && c.wrote(OcslWire.PROP_ANIM_TINT)) {
			out[NodeFold.TINT_R] = c.tint[0];
			out[NodeFold.TINT_G] = c.tint[1];
			out[NodeFold.TINT_B] = c.tint[2];
			out[NodeFold.TINT_A] = c.tint[3];
			return;
		}
		NodeFold.unpack(rawPackedTint, out);
	}

	// ---------------------------------------------------------------- internals

	private static long sessionTickOf(long worldStamp, long offset) {
		return worldStamp + offset;
	}

	private Compiled vmFor(int programId, ProgramInfo info) {
		Integer key = Integer.valueOf(programId);
		Compiled cached = vms.get(key);
		if (cached != null) {
			return cached;
		}
		if (broken.containsKey(key)) {
			return null;
		}
		// WRONG-STAGE DEFENCE (2026-08-21). A non-animator program must never reach the bind:
		// bindClock writes the animator registers, a pixel-stage frame maps none of them, and
		// OcslVm.set THROWS -- on the render thread, out through the Forge tick. The server now
		// refuses such attaches (ServerScene.setAnimator's stage gate), but a save written
		// before that gate can legitimately carry one, and the renderer must not be the thing
		// that takes a client down over scene data. Same memoization as damaged bytes: skipped
		// forever, the node renders at its server base.
		if (info.stage != OcslWire.STAGE_ANIMATOR) {
			return markBroken(key);
		}
		try {
			// TRANSIENT: this blob arrived over the wire in a snapshot or a delta, not off a disk.
			opengpu.v2.ocsl.IrProgram program =
					IrCodec.decode(info.blobCopy(), IrCodec.Source.TRANSIENT);
			IrValidator.Validated validated = IrValidator.validate(program);
			// PLAN (op caps): "Have 3.3 report each program's structural charge at evaluation so
			// that decision meets real data." Reported at compile rather than per frame — the
			// charge is a property of the program, and per-frame would just repeat it.
			//
			// Frame width and register count ride the same call for the same reason, and because
			// the charge ALONE cannot answer the question the next cap round asks: both of those
			// caps were measured binding below the op cap they accompany, so a program's charge
			// does not tell you which limit it will reach first. `validated` already carries the
			// frame — the validator lays it out to enforce MAX_FRAME_WIDTH — so this costs a
			// field read, not a second pass.
			// THE UNIFORM GAP, said out loud once per COUNTER WINDOW -- StatsOverlay's
			// SHIFT+toggle calls RenderStats.reset(), which re-arms this. It said "once per
			// client session" when it shipped, which was false on a keypress that ships.
			// A declared uniform reads
			// 0.0 forever because nothing binds one; that is silent, and silence is the defect.
			// A refusal was considered and rejected -- uniforms are a designed surface, so
			// refusing them would reject already-persisted blobs and be undone the day the
			// client BINDING lands (the node-keyed uniform table itself landed at C1.1, its
			// set-call at C1.2; the binding is C1.3's, which is when this diagnostic retires).
			// See OcslDiagnostics.uniformsWithNothingToBindThem.
			//
			// READ BEFORE THE RECORD, deliberately, and the ordering is the whole mechanism:
			// the counter IS the latch, so there is no second "have we warned" flag to keep in
			// step with it and RenderStats.reset clears both by clearing one. Same discipline
			// as OcslDiagnostics.Reporter, whose rate check also precedes its record, and for
			// the same reason -- recording first would consume the very state being tested.
			boolean firstWithUniforms = validated.uniformComponents > 0
					&& opengpu.v2.stats.RenderStats.animatorProgramsWithUniforms == 0L;
			opengpu.v2.stats.RenderStats.onAnimatorCompile((int) validated.structuralOps,
					validated.frameWidth, program.declaredRegisters, validated.uniformComponents);
			if (firstWithUniforms) {
				opengpu.v2.ocsl.OcslDiagnostics.uniformsWithNothingToBindThem(
						programId, validated.uniformComponents);
			}
			OcslVm vm = new OcslVm(validated);
			int[] written = program.outProperties();
			boolean[] absolute = new boolean[written.length];
			for (int i = 0; i < written.length; i++) {
				absolute[i] = vm.isAbsolute(written[i]);
			}
			Compiled compiled = new Compiled(vm, written, absolute);
			vms.put(key, compiled);
			return compiled;
		} catch (opengpu.v2.protocol.CodecException e) {
			return markBroken(key);
		} catch (opengpu.v2.ocsl.ValidationException e) {
			return markBroken(key);
		}
	}

	/**
	 * Record a blob this client cannot use, so it is not re-parsed every frame forever.
	 *
	 * NARROWLY TYPED, and a mutation sweep is what forced that. This began as
	 * {@code catch (Exception)}, which swallowed everything — including the NullPointerException a
	 * missing {@code ProgramInfo} produces. The visible behaviour was identical (the node is
	 * skipped either way), so removing the dangling-attachment check entirely was invisible to the
	 * whole suite. Two things were wrong underneath that: a merely ABSENT program would be recorded
	 * as permanently BROKEN, which is a different state with a different meaning, and any ordinary
	 * bug in this class would have been silently relabelled "the blob is corrupt" — a check that
	 * cries wolf on a healthy input, which is worse than no check because the next real alarm
	 * arrives pre-ignored.
	 *
	 * The server validated this blob before storing it, so a decode or validation failure here
	 * genuinely means the bytes were damaged in transit or on disk. Since 2026-08-21 the set
	 * also holds WRONG-STAGE attachments (a pre-gate save can carry one) — not damage, but the
	 * same outcome for the same reason: unusable by this evaluator, permanently.
	 */
	private Compiled markBroken(Integer key) {
		broken.put(key, Boolean.TRUE);
		return null;
	}

	private Composed evaluateNode(Compiled compiled, SceneNode node, SceneState state, float time,
			long renderInstant, long offset, Composed reuse) {
		OcslVm vm = compiled.vm;
		bindClock(vm, time, node, offset, renderInstant);
		bindOwnProperties(vm, node);
		bindParentProperties(vm, node, state);
		vm.run();
		// The composition base, read AFTER the VM ran to keep the two reads of the node visibly
		// distinct: registers were bound from the RAW fields above (ANIM-7's purity rule), and
		// relative writes land on the DISPLAYED base here — the interpolated transform when a
		// source is present. This is the "up to one interpolation window out of step" the design
		// documents, made concrete.
		displayedBase(node, baseTrs);

		Composed out = null;
		for (int i = 0; i < compiled.written.length; i++) {
			if (out == null) {
				// REUSES last frame's object when there is one — the map is persistent now, so
				// the entry already exists and allocating a replacement every frame per node
				// would be pure garbage in a class advertising a zero-allocation frame.
				// writtenMask MUST be cleared: a node can be re-attached to a different program
				// with a different declaration, and a leftover bit would keep substituting a
				// property the current program never writes.
				//
				// absoluteMask is REDUNDANT and kept anyway. applyProperty sets or clears that
				// bit explicitly for every property it writes, and nothing reads the bit for a
				// property writtenMask does not claim — so a mutation deleting this line
				// survives, which is a statement about the line, not about the tests. It stays
				// because the two masks are one concept and leaving half of it stale is a trap
				// for whatever next sets writtenMask without going through applyProperty.
				out = reuse;
				if (out == null) {
					out = new Composed();
				} else {
					out.writtenMask = 0;
					out.absoluteMask = 0;
				}
			}
			applyProperty(vm, node, compiled.written[i], compiled.absolute[i], out);
		}
		return out;
	}

	/**
	 * Re-derive a held node's composed output over the CURRENT frame's base, without running its
	 * program. This is what a held frame costs, and the gap between it and {@link #evaluateNode}
	 * is what ANIM-16's budget buys: a base read and up to six composes, against a whole VM.
	 *
	 * Deliberately shares {@link #composeStored} with the fresh path instead of restating the
	 * composition. A composition rule living in two places is this codebase's most-repeated
	 * defect, and here the two copies would diverge invisibly — a held node and a fresh node
	 * disagreeing by a clamp is not something a frame shows you.
	 */
	private void recompose(Composed held, SceneNode node) {
		if (held.writtenMask == 0) {
			return;
		}
		displayedBase(node, baseTrs);
		// Walks the WHOLE property space, from the same constant that sizes `raw`. Bounding this
		// at PROP_ANIM_TINT would have been correct today and would silently stop recomposing any
		// property admitted later — a held node whose new property never tracks its base, with
		// nothing to indicate why.
		for (int property = 0; property < PROPERTY_LIMIT; property++) {
			if (held.wrote(property)) {
				composeStored(held, node, property);
			}
		}
	}

	/** Drop entries this pass did not touch — a detached, removed or newly-dangling node. */
	private void sweepUntouched() {
		for (Iterator<Map.Entry<Integer, Composed>> it = byNode.entrySet().iterator();
				it.hasNext();) {
			if (it.next().getValue().frameSeq != frameSeq) {
				it.remove();
			}
		}
	}

	/** The node's transform as the frame displays it — interpolated when a source is present. */
	private void displayedBase(SceneNode node, double[] out) {
		if (interp != null) {
			interp.transformOf(node, frameNanos, out);
			return;
		}
		out[NodeFold.TRS_X] = node.x;
		out[NodeFold.TRS_Y] = node.y;
		out[NodeFold.TRS_ROT] = node.rot;
		out[NodeFold.TRS_SX] = node.sx;
		out[NodeFold.TRS_SY] = node.sy;
	}

	private void bindClock(OcslVm vm, float time, SceneNode node, long offset,
			long renderInstant) {
		vm.set(SurfaceTable.REG_TIME, time);
		// timePeriod (register 8) is NOT bound here. The VM seeds it from OcslTime.PERIOD_SECONDS
		// itself and refuses a host write outright -- "a constant of the format ... not host
		// state". A first draft bound it anyway, which SurfaceTable's own javadoc had warned
		// against in the sentence above the one I took the register id from: seeding it in the VM
		// is precisely so no binding site can get it wrong.
		vm.set(SurfaceTable.REG_ANIM_NODE_SEED, nodeSeed(node.id));
		vm.set(SurfaceTable.REG_ANIM_SINCE_ATTACH,
				sinceAttach(node.attachedWorldTime, offset, renderInstant));
	}

	/**
	 * ANIM-6's saturating clock: seconds since this attachment became active, clamped to CAP.
	 *
	 * Clamped at BOTH ends. The upper clamp is the saturation the feature exists for; the lower
	 * one matters because a stamp can legitimately sit slightly ahead of the render instant —
	 * {@code renderNanos} is deliberately behind the server estimate by the interpolation delay,
	 * so a just-attached node is briefly "attached in the future". Negative seconds there would
	 * run an easing program backwards through its first two ticks.
	 */
	private static float sinceAttach(long attachedWorldTime, long offset, long renderInstant) {
		if (attachedWorldTime == 0L) {
			return 0.0f; // unattached, or a stamp from before v8 — the epoch, not a duration
		}
		long attachNanos = sessionTickOf(attachedWorldTime, offset) * OcslTime.TICK_NANOS;
		long elapsed = renderInstant - attachNanos;
		if (elapsed <= 0L) {
			return 0.0f;
		}
		float seconds = (float) (elapsed / 1_000_000_000.0);
		return seconds > SINCE_ATTACH_CAP_SECONDS ? SINCE_ATTACH_CAP_SECONDS : seconds;
	}

	/**
	 * A stable bit-mix of the node id, so a preset attached to many nodes de-phases without any
	 * authoring — ANIM-2's "200 debris sprites shaking on the same frame".
	 *
	 * Replicated by construction because it is a pure function of an id both sides already agree
	 * on. In 0..1: the register is a float and a program's natural use is as a phase offset.
	 */
	private static float nodeSeed(int nodeId) {
		int h = nodeId * 0x9E3779B9;
		h ^= h >>> 16;
		h *= 0x85EBCA6B;
		h ^= h >>> 13;
		return (h >>> 8) / (float) (1 << 24);
	}

	private void bindOwnProperties(OcslVm vm, SceneNode node) {
		vm.set(SurfaceTable.REG_ANIM_X, (float) node.x);
		vm.set(SurfaceTable.REG_ANIM_Y, (float) node.y);
		vm.set(SurfaceTable.REG_ANIM_SX, (float) node.sx);
		vm.set(SurfaceTable.REG_ANIM_SY, (float) node.sy);
		vm.set(SurfaceTable.REG_ANIM_ROT2D, (float) node.rot);
		unpackTint(node.tint, scratch4);
		vm.set(SurfaceTable.REG_ANIM_TINT, scratch4[0], scratch4[1], scratch4[2], scratch4[3]);
		// tz/sz/rot3d are Stage C: readable ids with no 2D source. NOT bound at all — they sit at
		// the frame's zero initialisation, which is not the same thing as "bound to a default",
		// since OcslIngress.bound() only runs on values a host actually sets. A 2D scene has no
		// z translate to offer, and fabricating one would be worse than a zero a program can test.
	}

	/**
	 * The parent block, carrying the parent's COMPOSED values when it has any.
	 *
	 * Falls back to the parent's DISPLAYED base when the parent has no animator — which is not a
	 * special case but the same answer: an unanimated parent's composed value IS its base, and
	 * "the parent's effective rotation" (the counter-rotation use these registers exist for)
	 * means the one the parent is drawn with, interpolation included. A raw fallback would put a
	 * child's animator up to one interpolation window behind the parent it is compensating.
	 */
	private void bindParentProperties(OcslVm vm, SceneNode node, SceneState state) {
		SceneNode parent = node.parent == 0 ? null
				: state.nodes.get(Integer.valueOf(node.parent));
		if (parent == null) {
			// Unparented, or a parent that no longer resolves. Zero is wrong for scale, so the
			// identity is bound explicitly rather than left to the frame's default.
			vm.set(SurfaceTable.REG_ANIM_PARENT_X, 0.0f);
			vm.set(SurfaceTable.REG_ANIM_PARENT_Y, 0.0f);
			vm.set(SurfaceTable.REG_ANIM_PARENT_SX, 1.0f);
			vm.set(SurfaceTable.REG_ANIM_PARENT_SY, 1.0f);
			vm.set(SurfaceTable.REG_ANIM_PARENT_ROT2D, 0.0f);
			vm.set(SurfaceTable.REG_ANIM_PARENT_TINT, 1.0f, 1.0f, 1.0f, 1.0f);
			return;
		}
		displayedBase(parent, parentTrs);
		// CURRENT-PASS BY CONSTRUCTION, and it takes two rules now, not one. Ascending id plus
		// parent < child means the parent was VISITED before this child; and every path the loop
		// can take for that parent either refreshes its entry (evaluated or recomposed) or removes
		// it on the spot. Without the second rule this read could return an entry the pass had
		// already abandoned — the sweep only runs after the loop, far too late for a child.
		Composed pc = byNode.get(Integer.valueOf(parent.id));
		vm.set(SurfaceTable.REG_ANIM_PARENT_X,
				(float) pick(pc, OcslWire.PROP_ANIM_X, parentTrs[NodeFold.TRS_X]));
		vm.set(SurfaceTable.REG_ANIM_PARENT_Y,
				(float) pick(pc, OcslWire.PROP_ANIM_Y, parentTrs[NodeFold.TRS_Y]));
		vm.set(SurfaceTable.REG_ANIM_PARENT_SX,
				(float) pick(pc, OcslWire.PROP_ANIM_SX, parentTrs[NodeFold.TRS_SX]));
		vm.set(SurfaceTable.REG_ANIM_PARENT_SY,
				(float) pick(pc, OcslWire.PROP_ANIM_SY, parentTrs[NodeFold.TRS_SY]));
		vm.set(SurfaceTable.REG_ANIM_PARENT_ROT2D,
				(float) pick(pc, OcslWire.PROP_ANIM_ROT2D, parentTrs[NodeFold.TRS_ROT]));
		if (pc != null && pc.wrote(OcslWire.PROP_ANIM_TINT)) {
			vm.set(SurfaceTable.REG_ANIM_PARENT_TINT,
					pc.tint[0], pc.tint[1], pc.tint[2], pc.tint[3]);
		} else {
			unpackTint(parent.tint, scratch4);
			vm.set(SurfaceTable.REG_ANIM_PARENT_TINT,
					scratch4[0], scratch4[1], scratch4[2], scratch4[3]);
		}
	}

	private static double pick(Composed pc, int propertyId, double raw) {
		if (pc == null || !pc.wrote(propertyId)) {
			return raw;
		}
		switch (propertyId) {
			case OcslWire.PROP_ANIM_X: return pc.x;
			case OcslWire.PROP_ANIM_Y: return pc.y;
			case OcslWire.PROP_ANIM_SX: return pc.sx;
			case OcslWire.PROP_ANIM_SY: return pc.sy;
			case OcslWire.PROP_ANIM_ROT2D: return pc.rot;
			default: return raw;
		}
	}

	/**
	 * Compose one written property over the node's DISPLAYED base, then CLAMP. ("Server base"
	 * until 3.3b — the class header's composition-base section is where the change is argued.)
	 *
	 * The clamp is {@code OcslWriteBoundary.clampForWrite}, which had zero callers until this
	 * method — it is the consumer the ledger named. Composition can leave the finite range even
	 * when both operands were inside it (two legal scales multiply, two legal positions add), so
	 * clamping the animator's raw output alone would not bound what reaches the transform math.
	 *
	 * TINT IS A DIFFERENT CASE, and an earlier draft of this paragraph ran the two together.
	 * Tint composes by RULE_REPLACE, so composition contributes no overshoot at all — an
	 * out-of-range tint is simply the animator's own output. The clamp still belongs here because
	 * this is where the value becomes final, but it is not the last line of defence:
	 * {@code quantizeColorChannel} clamps again before it multiplies, deliberately, "because this
	 * is the function that feeds the shift". Two clamps, one of them the guard this method owes
	 * the ledger, the other belt-and-braces at the packer.
	 */
	private void applyProperty(OcslVm vm, SceneNode node, int property, boolean absolute,
			Composed out) {
		// SKIPPED BEFORE COMPOSING, not after. `written` now comes from the program's own
		// declaration rather than a hardcoded 2D list, which made Stage C's ids genuinely
		// reachable here for the first time — and `rot3d` composes by RULE_QUATERNION, for which
		// OcslCompose.compose THROWS by design ("rot3d is a vec4; use composeRot3d()"). A legal
		// program owning it — the validator accepts one today — would have taken the render frame
		// down. A first draft filtered these in the switch at the bottom, which is after the
		// throw. Classic newly-reachable-branch: the guard was fine while the list was hardcoded
		// and became load-bearing the moment the list became honest.
		//
		// tz and sz would compose harmlessly (ADD and MULTIPLY) but have nowhere to be stored:
		// `Composed` is 2D and PLAN records all three as "ownable-but-unconsumed, kept
		// deliberately". When Stage C lands, this is the guard that opens.
		if (property == OcslWire.PROP_ANIM_TZ || property == OcslWire.PROP_ANIM_SZ
				|| property == OcslWire.PROP_ANIM_ROT3D) {
			return;
		}
		// CAPTURE, then compose. The raw output is stored before anything is done to it so that a
		// later held frame has the animator's own answer to recompose, rather than a value with
		// this frame's base already folded in.
		vm.output(property, scratch4);
		if (absolute) {
			out.absoluteMask |= 1 << property;
		} else {
			out.absoluteMask &= ~(1 << property);
		}
		if (property == OcslWire.PROP_ANIM_TINT) {
			out.rawTint[0] = scratch4[0];
			out.rawTint[1] = scratch4[1];
			out.rawTint[2] = scratch4[2];
			out.rawTint[3] = scratch4[3];
		} else {
			out.raw[property] = scratch4[0];
		}
		// Only on a property that actually landed somewhere. The switch's unreachable arm answers
		// false, so a property id added later without a slot in Composed stays UNWRITTEN rather
		// than being advertised as written and read back as a stale zero — the silent
		// fall-through the arm was kept to prevent.
		if (composeStored(out, node, property)) {
			out.writtenMask |= 1 << property;
		}
	}

	/**
	 * Compose one stored raw output over the base and clamp — the ONE place composition happens.
	 *
	 * Reads {@code baseTrs}, which both callers fill from {@link #displayedBase} beforehand, and
	 * {@code node.tint}, which is raw by design: tint has no interpolation track, so its displayed
	 * value is its server value.
	 *
	 * @param property must already be recorded in {@code out.absoluteMask} and present in
	 *                 {@code out.raw}/{@code out.rawTint}
	 * @return true if the composed value landed in a field of {@code out}
	 */
	private boolean composeStored(Composed out, SceneNode node, int property) {
		boolean absolute = out.isAbsolute(property);
		if (property == OcslWire.PROP_ANIM_TINT) {
			unpackTint(node.tint, out.tint);
			for (int c = 0; c < 4; c++) {
				float composed = OcslCompose.compose(OcslWire.PROP_ANIM_TINT, out.tint[c],
						out.rawTint[c], absolute);
				out.tint[c] = OcslWriteBoundary.clampForWrite(OcslWire.PROP_ANIM_TINT, composed);
			}
			return true;
		}
		double base = baseOf(baseTrs, property);
		float composed = OcslCompose.compose(property, base, out.raw[property], absolute);
		float clamped = OcslWriteBoundary.clampForWrite(property, composed);
		switch (property) {
			case OcslWire.PROP_ANIM_X: out.x = clamped; break;
			case OcslWire.PROP_ANIM_Y: out.y = clamped; break;
			case OcslWire.PROP_ANIM_SX: out.sx = clamped; break;
			case OcslWire.PROP_ANIM_SY: out.sy = clamped; break;
			case OcslWire.PROP_ANIM_ROT2D: out.rot = clamped; break;
			default:
				// Unreachable: the Stage C ids are turned away at the top of applyProperty, and
				// `written` cannot contain anything else — the validator refuses an OUT to a
				// property the stage has no row for. Kept as a total switch rather than deleted,
				// because the alternative is a silent fall-through if a property id is ever added.
				return false;
		}
		return true;
	}

	private static double baseOf(double[] displayedTrs, int property) {
		switch (property) {
			case OcslWire.PROP_ANIM_X: return displayedTrs[NodeFold.TRS_X];
			case OcslWire.PROP_ANIM_Y: return displayedTrs[NodeFold.TRS_Y];
			case OcslWire.PROP_ANIM_SX: return displayedTrs[NodeFold.TRS_SX];
			case OcslWire.PROP_ANIM_SY: return displayedTrs[NodeFold.TRS_SY];
			case OcslWire.PROP_ANIM_ROT2D: return displayedTrs[NodeFold.TRS_ROT];
			default: return 0.0;
		}
	}

	/** ARGB int to RGBA floats in 0..1 — the shape the IR speaks. */
	private static void unpackTint(int argb, float[] out) {
		out[0] = ((argb >>> 16) & 0xFF) / 255.0f;
		out[1] = ((argb >>> 8) & 0xFF) / 255.0f;
		out[2] = (argb & 0xFF) / 255.0f;
		out[3] = ((argb >>> 24) & 0xFF) / 255.0f;
	}

	/**
	 * Drop cached VMs for programs the scene no longer holds.
	 *
	 * Without this a long-lived client leaks one decoded program per freed id — the leak
	 * {@link NodeInterpolator} documents for its own tracks, arriving on a second map. Ids are
	 * never reused, so an absent id is gone for good and dropping it cannot strand a live entry.
	 */
	private void pruneCaches(SceneState state) {
		for (Iterator<Integer> it = vms.keySet().iterator(); it.hasNext();) {
			if (!state.programs.containsKey(it.next())) {
				it.remove();
			}
		}
		for (Iterator<Integer> it = broken.keySet().iterator(); it.hasNext();) {
			if (!state.programs.containsKey(it.next())) {
				it.remove();
			}
		}
	}
}
