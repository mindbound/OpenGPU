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
 * <h2>What this class deliberately does not do</h2>
 *
 * It does not touch GL and it does not read the renderer. Substituting these values at
 * {@code Canvas2dRenderer.readTransform}/{@code foldTint} is 3.3b — kept separate so everything
 * that DECIDES anything is testable headlessly, and the Forge-bound half is pure wiring.
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

	/** One node's composed, clamped output. Only the properties its program actually wrote. */
	static final class Composed {
		int writtenMask;
		double x, y, sx, sy, rot;
		/** RGBA 0..1, composed and clamped; packing to ARGB is the renderer's business. */
		final float[] tint = new float[4];

		boolean wrote(int propertyId) {
			return (writtenMask & (1 << propertyId)) != 0;
		}
	}

	private final Map<Integer, Composed> byNode = new HashMap<Integer, Composed>();

	/**
	 * Decoded programs, keyed by program id.
	 *
	 * SAFE TO CACHE BY ID because ids are never reused — {@code nextProgramId} only climbs, and a
	 * freed id stays dead rather than returning attached to different code. That property was
	 * chosen for the attach path's sake and pays again here: without it this cache would need
	 * invalidating whenever the program table changed, and a stale entry would run yesterday's
	 * program forever with nothing to detect it.
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

	/**
	 * Evaluate every attached node in {@code state} for this frame.
	 *
	 * @param renderInstant     {@code NodeInterpolator.renderInstant} — the SAME instant
	 *                          interpolation is replaying against (ANIM-4: one time sample per
	 *                          frame per scene), or Long.MIN_VALUE if the timeline is unprimed
	 * @param sessionTickOffset {@code SceneMirror.sessionTickOf}'s offset: world time + this =
	 *                          the server-tick domain {@code renderInstant} counts
	 * @param clockKnown        {@code SceneMirror.animatorClockKnown} — false until a snapshot
	 *                          carrying a stamped anchor has landed
	 */
	void evaluate(SceneState state, long renderInstant, long sessionTickOffset,
			boolean clockKnown) {
		byNode.clear();
		// NO CLOCK, NO ANIMATION, and failing closed is the point. Without the anchor a stamp
		// cannot be placed in the render clock's domain at all, and the plausible fallbacks are
		// worse than nothing: offset 0 puts every scene's epoch at the start of the session, so
		// every animator would run from a phase that is wrong by the whole magnitude of world
		// time — moving smoothly and confidently at the wrong point in its cycle, which is far
		// harder to notice than a node that simply does not move.
		if (!clockKnown || renderInstant == Long.MIN_VALUE) {
			return;
		}

		float time = OcslTime.time(renderInstant,
				sessionTickOf(state.creationWorldTime, sessionTickOffset));

		// Ascending id: SceneState.nodes is a TreeMap and parent < child is an allocator
		// invariant, so every parent is composed before any child that reads it.
		for (Map.Entry<Integer, SceneNode> entry : state.nodes.entrySet()) {
			SceneNode node = entry.getValue();
			if (node.animator == 0) {
				continue;
			}
			ProgramInfo info = state.programs.get(Integer.valueOf(node.animator));
			if (info == null) {
				// A DANGLING attachment, which ANIM-17 rules legal: the program was freed while
				// still attached. The node renders at its server value, exactly as it would one
				// tick before the attach — not an error, and not worth a diagnostic every frame.
				continue;
			}
			Compiled compiled = vmFor(node.animator, info);
			if (compiled == null) {
				continue; // decode/validate failed; recorded once in `broken`
			}
			Composed out = evaluateNode(compiled, node, state, time, renderInstant,
					sessionTickOffset);
			if (out != null) {
				byNode.put(Integer.valueOf(node.id), out);
			}
		}
		pruneCaches(state);
	}

	/** The composed values for a node, or null if it has none this frame. */
	Composed of(int nodeId) {
		return byNode.get(Integer.valueOf(nodeId));
	}

	boolean isEmpty() {
		return byNode.isEmpty();
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
		try {
			// TRANSIENT: this blob arrived over the wire in a snapshot or a delta, not off a disk.
			opengpu.v2.ocsl.IrProgram program =
					IrCodec.decode(info.blobCopy(), IrCodec.Source.TRANSIENT);
			IrValidator.Validated validated = IrValidator.validate(program);
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
	 * genuinely means the bytes were damaged in transit or on disk.
	 */
	private Compiled markBroken(Integer key) {
		broken.put(key, Boolean.TRUE);
		return null;
	}

	private Composed evaluateNode(Compiled compiled, SceneNode node, SceneState state, float time,
			long renderInstant, long offset) {
		OcslVm vm = compiled.vm;
		bindClock(vm, time, node, offset, renderInstant);
		bindOwnProperties(vm, node);
		bindParentProperties(vm, node, state);
		vm.run();

		Composed out = null;
		for (int i = 0; i < compiled.written.length; i++) {
			if (out == null) {
				out = new Composed();
			}
			applyProperty(vm, node, compiled.written[i], compiled.absolute[i], out);
		}
		return out;
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
	 * Falls back to the parent's raw server values when the parent has no animator — which is not
	 * a special case but the same answer: an unanimated parent's composed value IS its raw value,
	 * so the two branches agree by construction rather than by coincidence.
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
		Composed pc = byNode.get(Integer.valueOf(parent.id)); // evaluated already: parent < child
		vm.set(SurfaceTable.REG_ANIM_PARENT_X, (float) pick(pc, OcslWire.PROP_ANIM_X, parent.x));
		vm.set(SurfaceTable.REG_ANIM_PARENT_Y, (float) pick(pc, OcslWire.PROP_ANIM_Y, parent.y));
		vm.set(SurfaceTable.REG_ANIM_PARENT_SX, (float) pick(pc, OcslWire.PROP_ANIM_SX, parent.sx));
		vm.set(SurfaceTable.REG_ANIM_PARENT_SY, (float) pick(pc, OcslWire.PROP_ANIM_SY, parent.sy));
		vm.set(SurfaceTable.REG_ANIM_PARENT_ROT2D,
				(float) pick(pc, OcslWire.PROP_ANIM_ROT2D, parent.rot));
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
	 * Compose one written property over the node's server base, then CLAMP.
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
		if (property == OcslWire.PROP_ANIM_TINT) {
			vm.output(property, scratch4);
			unpackTint(node.tint, out.tint);
			for (int c = 0; c < 4; c++) {
				float composed = OcslCompose.compose(OcslWire.PROP_ANIM_TINT, out.tint[c],
						scratch4[c], absolute);
				out.tint[c] = OcslWriteBoundary.clampForWrite(OcslWire.PROP_ANIM_TINT, composed);
			}
			out.writtenMask |= 1 << property;
			return;
		}
		vm.output(property, scratch4);
		double base = baseOf(node, property);
		float composed = OcslCompose.compose(property, base, scratch4[0], absolute);
		float clamped = OcslWriteBoundary.clampForWrite(property, composed);
		switch (property) {
			case OcslWire.PROP_ANIM_X: out.x = clamped; break;
			case OcslWire.PROP_ANIM_Y: out.y = clamped; break;
			case OcslWire.PROP_ANIM_SX: out.sx = clamped; break;
			case OcslWire.PROP_ANIM_SY: out.sy = clamped; break;
			case OcslWire.PROP_ANIM_ROT2D: out.rot = clamped; break;
			default:
				// Unreachable: the Stage C ids are turned away at the top of this method, and
				// `written` cannot contain anything else — the validator refuses an OUT to a
				// property the stage has no row for. Kept as a total switch rather than deleted,
				// because the alternative is a silent fall-through if a property id is ever added.
				return;
		}
		out.writtenMask |= 1 << property;
	}

	private static double baseOf(SceneNode node, int property) {
		switch (property) {
			case OcslWire.PROP_ANIM_X: return node.x;
			case OcslWire.PROP_ANIM_Y: return node.y;
			case OcslWire.PROP_ANIM_SX: return node.sx;
			case OcslWire.PROP_ANIM_SY: return node.sy;
			case OcslWire.PROP_ANIM_ROT2D: return node.rot;
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
