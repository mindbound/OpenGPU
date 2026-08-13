package opengpu.v2.ocsl;

/**
 * How an animator's output composes over the server-set base — ANIM-3, per property, as equations.
 *
 * <h2>PER-PROPERTY, not matrix composition, and the choice is forced</h2>
 *
 * The design said only that "animator output composes over the server-set base — TRS output applies
 * relative to the server-set transform". <b>"Relative" names an intent, not an operation.</b>
 * Per-property ({@code x += dx}) and matrix composition
 * ({@code T_s·R_s·S_s ∘ T_a·R_a·S_a}) are both faithful readings of that sentence, and they disagree
 * the moment the node has a non-zero server rotation or a non-unit server scale: the orbit is either
 * in the parent's frame or in the node's own rotated, scaled frame. The dry run traced them
 * <b>17 logical units apart</b> on a node with {@code sx = 1.5}.
 *
 * Per-property wins for a reason beyond taste: <b>matrix composition makes per-property ownership
 * incoherent.</b> You cannot own {@code x} alone if the animator contributes a whole matrix, and
 * single-property ownership is the surface's entire model.
 *
 * <h2>SCALE IS THE PROBE, and it is why this could not be left to interpretation</h2>
 *
 * Position and rotation hide the disagreement at rest, because 0 is the identity under every
 * candidate reading — a momentarily-still animator looks right under all of them, which is exactly
 * how a wrong rule survives a play-test. Scale does not: the neutral output is <b>1.0 under multiply
 * and 0.0 under add</b>. The dry run's consistency probe emits 0.9649, which is a 3.5% squash under
 * one reading and a <b>96% stretch</b> under the other, from the same blob. Every curated preset
 * ships default uniform values and those defaults are meaningless until this is pinned.
 *
 * <h2>The equations</h2>
 *
 * <pre>
 *   x_disp  = x_srv  + x_anim        identity 0
 *   y_disp  = y_srv  + y_anim        identity 0
 *   z_disp  = z_srv  + z_anim        identity 0
 *   rot_disp = rot_srv + rot_anim    identity 0     (2D, radians)
 *   sx_disp = sx_srv * sx_anim       identity 1
 *   sy_disp = sy_srv * sy_anim       identity 1
 *   sz_disp = sz_srv * sz_anim       identity 1
 *   rot3d   = q_srv * q_anim         identity (0,0,0,1), animator-local applied first
 *   tint    = tint_anim              REPLACES; the server value is readable as an input
 * </pre>
 *
 * <b>{@code x} and {@code y} compose IN THE PARENT'S FRAME — they are NOT rotated by the server
 * transform.</b> Pinned in those words because the opposite reading is the one a reader supplies for
 * free: "applies relative to the server-set transform" sounds like it means the node's own rotated,
 * scaled frame, and it does not.
 *
 * <h2>The arithmetic width, which is a bit-for-bit agreement problem</h2>
 *
 * The server base is a {@code double}; the VM frame is {@code float32}. <b>The base is narrowed to
 * float32 BEFORE composing</b>, so the composed value is a pure function of what the VM actually
 * saw. Composing in double and narrowing afterwards would make the result depend on bits of the
 * base the program could never read, and two clients holding slightly different doubles for the
 * same node would then disagree on a value they both computed "correctly".
 */
public final strictfp class OcslCompose {
	private OcslCompose() {}

	/** {@code disp = srv + anim}. Identity output 0. */
	public static final int RULE_ADD = 0;
	/** {@code disp = srv * anim}. Identity output 1. */
	public static final int RULE_MULTIPLY = 1;
	/** {@code disp = anim}. No constant identity exists — any output replaces the base. */
	public static final int RULE_REPLACE = 2;
	/** {@code disp = q_srv * q_anim}, then normalized. Identity output (0,0,0,1). */
	public static final int RULE_QUATERNION = 3;

	/** The composition rule for an animator property id, or -1 if it is not composable. */
	public static int ruleFor(int propertyId) {
		switch (propertyId) {
			case OcslWire.PROP_ANIM_X:
			case OcslWire.PROP_ANIM_Y:
			case OcslWire.PROP_ANIM_ROT2D:
			case OcslWire.PROP_ANIM_TZ:
				return RULE_ADD;
			case OcslWire.PROP_ANIM_SX:
			case OcslWire.PROP_ANIM_SY:
			case OcslWire.PROP_ANIM_SZ:
				return RULE_MULTIPLY;
			case OcslWire.PROP_ANIM_ROT3D:
				return RULE_QUATERNION;
			case OcslWire.PROP_ANIM_TINT:
				return RULE_REPLACE;
			default:
				// z and visible are not ownable in v1, so they compose with nothing.
				return -1;
		}
	}

	/**
	 * The output value that leaves the base unchanged — what a preset's neutral default must be.
	 *
	 * The whole reason ANIM-3 asks for this column: an author writing a curated preset has to know
	 * what "do nothing" looks like, and it is 0 for some properties and 1 for others. Getting it
	 * wrong on a scale property is a 96% stretch, not a subtle drift.
	 *
	 * @throws IllegalArgumentException for {@code tint}, which has no constant identity — it
	 *         REPLACES, so there is no output that leaves the base alone. Throwing is the honest
	 *         answer; returning 0 would name a value that squashes the node to black.
	 */
	public static float identityFor(int propertyId) {
		switch (ruleFor(propertyId)) {
			case RULE_ADD:
				return 0.0f;
			case RULE_MULTIPLY:
				return 1.0f;
			case RULE_QUATERNION:
				throw new IllegalArgumentException("rot3d's identity is the quaternion (0,0,0,1),"
						+ " not a scalar; use identityRot3d()");
			case RULE_REPLACE:
				throw new IllegalArgumentException("tint REPLACES its base, so no output value"
						+ " leaves the base unchanged; there is no identity to default to");
			default:
				throw new IllegalArgumentException("property " + propertyId
						+ " is not composable at the animator surface");
		}
	}

	/** The identity quaternion, in the pinned (x, y, z, w) component order. */
	public static float[] identityRot3d() {
		return new float[] { 0.0f, 0.0f, 0.0f, 1.0f };
	}

	/**
	 * Compose one scalar property. {@code serverBase} is the {@code double} the server holds.
	 *
	 * The narrowing happens on the way in, and that ordering is the pin — see the class note. It is
	 * spelled {@link OcslIngress#base} rather than a cast because narrowing is only half of what has
	 * to happen to a value arriving from outside: a non-finite base reaching {@code mul} is zeroed by
	 * A4's catch-all and collapses the node, so the base gets the same identity substitution the
	 * write boundary gives a rejected output. ANIM-9(b), and the mirror of ANIM-9(a).
	 *
	 * <b>BOTH boundaries are applied HERE rather than left to the caller.</b> The first draft assumed
	 * a caller would run {@link OcslWriteBoundary#accepted} first and then compose — under which
	 * {@code compose(sx, 2.5, NaN)} was {@code mul(2.5, NaN)} = 0, an invisible node, for anyone who
	 * did not. There are no callers yet, so nobody had remembered, and an ordering obligation that
	 * has never once been met is not a contract. The boundary is idempotent on a finite value, so a
	 * caller who does apply it first gets the same answer either way.
	 */
	public static float compose(int propertyId, double serverBase, float animatorOutput) {
		switch (ruleFor(propertyId)) {
			case RULE_ADD:
				return OcslMath.add(OcslIngress.base(propertyId, serverBase),
						OcslWriteBoundary.accepted(propertyId, animatorOutput));
			case RULE_MULTIPLY:
				return OcslMath.mul(OcslIngress.base(propertyId, serverBase),
						OcslWriteBoundary.accepted(propertyId, animatorOutput));
			case RULE_REPLACE:
				return OcslWriteBoundary.acceptedTint(serverBase, animatorOutput);
			case RULE_QUATERNION:
				throw new IllegalArgumentException("rot3d is a vec4; use composeRot3d()");
			default:
				throw new IllegalArgumentException("property " + propertyId
						+ " is not composable at the animator surface");
		}
	}

	/**
	 * Compose one scalar property written in either output form — ANIM-7's double-apply decision.
	 *
	 * {@code absolute} is {@link OcslWire#OP_OUT_ABS}: {@code disp = out}, so the base appears only
	 * as the rejected write's fallback. That is the same shape {@code RULE_REPLACE} already had,
	 * which is why the absolute path reuses {@link OcslWriteBoundary#acceptedTint} for {@code tint}
	 * instead of inventing a second answer for a property that never composed in the first place.
	 *
	 * The arms MIRROR the relative overload above, deliberately and visibly: a rule that exists in
	 * one form and not the other is this codebase's most-repeated defect, and the two switches
	 * sitting adjacent is what makes a missing arm read as missing.
	 */
	public static float compose(int propertyId, double serverBase, float animatorOutput,
			boolean absolute) {
		if (!absolute) {
			return compose(propertyId, serverBase, animatorOutput);
		}
		switch (ruleFor(propertyId)) {
			case RULE_ADD:
			case RULE_MULTIPLY:
				return OcslWriteBoundary.acceptedAbsolute(propertyId, serverBase, animatorOutput);
			case RULE_REPLACE:
				return OcslWriteBoundary.acceptedTint(serverBase, animatorOutput);
			case RULE_QUATERNION:
				throw new IllegalArgumentException("rot3d is a vec4; use composeRot3d()");
			default:
				throw new IllegalArgumentException("property " + propertyId
						+ " is not composable at the animator surface");
		}
	}

	/**
	 * {@code q_disp = q_srv * q_anim}, normalized — the animator's rotation applied FIRST, in the
	 * node's local frame.
	 *
	 * Component order is <b>(x, y, z) vector part, w scalar</b>, matching the IR's vec4 swizzles and
	 * GLSL convention, because {@code rot3d} is read and written through {@code .x/.y/.z/.w} like
	 * any other vec4 and a different packing here would make those swizzles lie.
	 *
	 * <b>The runtime normalizes, and that had to be said</b>: the IR's own {@code normalize} is
	 * vec-shaped and nothing stated that the composition normalizes its result, so an animator
	 * emitting a slightly non-unit quaternion — which float32 arithmetic makes routine — would
	 * otherwise scale the node as a side effect of rotating it. A zero-length product falls back to
	 * the identity rather than producing NaN, consistent with A4's catch-all and with
	 * {@code normalize(0)}.
	 */
	public static void composeRot3d(double[] server, float[] animator, float[] out) {
		// Each side through its own boundary FIRST, and separately -- see OcslWriteBoundary.acceptsAll.
		// The norm guard below caught non-finite input only by accident, and it caught it on the wrong
		// side of the product: it discarded the server base along with the bad animator output.
		//
		// The identity is spelled inline rather than through identityRot3d(), which allocates a fresh
		// array. This runs once per animated node per frame and the render thread cannot afford it --
		// a first draft copied both sides into two float[4] and gave that back. OcslIngressTest pins
		// these eight constants against identityRot3d() so the two spellings cannot drift.
		boolean serverOk = OcslIngress.acceptsAll(server);
		boolean animOk = OcslWriteBoundary.acceptsAll(animator);
		float sx = serverOk ? (float) server[0] : 0.0f;
		float sy = serverOk ? (float) server[1] : 0.0f;
		float sz = serverOk ? (float) server[2] : 0.0f;
		float sw = serverOk ? (float) server[3] : 1.0f;
		float ax = animOk ? animator[0] : 0.0f;
		float ay = animOk ? animator[1] : 0.0f;
		float az = animOk ? animator[2] : 0.0f;
		float aw = animOk ? animator[3] : 1.0f;

		// Hamilton product, accumulated in double and narrowed once per component -- the same
		// discipline OcslMath.cross and dot use, and for the same reason.
		double x = (double) sw * ax + (double) sx * aw + (double) sy * az - (double) sz * ay;
		double y = (double) sw * ay - (double) sx * az + (double) sy * aw + (double) sz * ax;
		double z = (double) sw * az + (double) sx * ay - (double) sy * ax + (double) sz * aw;
		double w = (double) sw * aw - (double) sx * ax - (double) sy * ay - (double) sz * az;

		normalizeInto(x, y, z, w, out);
	}

	/**
	 * {@code rot3d} written in either output form — the quaternion mirror of the scalar overload.
	 *
	 * Absolute means {@code q_disp = q_anim}, still NORMALIZED: the reason the relative path
	 * normalizes is that float32 arithmetic makes a slightly non-unit quaternion routine and a
	 * non-unit quaternion scales the node as a side effect of rotating it, and that reason does not
	 * care which form produced the value. A rejected absolute write falls back to the server base
	 * (normalized in its turn), and a base that is itself unusable falls back to the identity —
	 * the same two-step the scalar path takes through {@link OcslWriteBoundary#acceptedAbsolute}
	 * and {@link OcslIngress#base}.
	 */
	public static void composeRot3d(double[] server, float[] animator, float[] out,
			boolean absolute) {
		if (!absolute) {
			composeRot3d(server, animator, out);
			return;
		}
		if (OcslWriteBoundary.acceptsAll(animator)) {
			normalizeInto(animator[0], animator[1], animator[2], animator[3], out);
			return;
		}
		if (OcslIngress.acceptsAll(server)) {
			// NARROWED TO FLOAT32 FIRST, which the relative path does one component at a time on its
			// way into the product and this path has to do explicitly. The frozen [composition] rule
			// is "the base is narrowed to float32 BEFORE composing, so the result is a pure function
			// of what the VM saw and two clients cannot disagree on it" -- normalizing the raw
			// doubles makes the answer depend on bits float32 cannot represent. It is not a rounding
			// nicety: a base that underflows float32 normalizes to a UNIT quaternion as doubles and
			// to the identity once narrowed, so a client that skipped the narrowing draws a
			// different rotation, not a slightly different one.
			normalizeInto((float) server[0], (float) server[1], (float) server[2],
					(float) server[3], out);
			return;
		}
		out[0] = 0.0f;
		out[1] = 0.0f;
		out[2] = 0.0f;
		out[3] = 1.0f;
	}

	/**
	 * Normalize a quaternion into {@code out}, falling back to the identity when it degenerates.
	 *
	 * Extracted so both output forms normalize through the SAME arithmetic rather than two spellings
	 * that agree until one of them is edited. The guard is what it always was: a degenerate result
	 * from finite inputs — non-finite input is stopped by the boundaries before it reaches here.
	 */
	private static void normalizeInto(double x, double y, double z, double w, float[] out) {
		double norm = Math.sqrt(x * x + y * y + z * z + w * w);
		if (!(norm > 0.0) || Double.isInfinite(norm)) {
			// Inline for the same allocation reason as above: this runs per animated node per frame.
			out[0] = 0.0f;
			out[1] = 0.0f;
			out[2] = 0.0f;
			out[3] = 1.0f;
			return;
		}
		out[0] = OcslMath.san((float) (x / norm));
		out[1] = OcslMath.san((float) (y / norm));
		out[2] = OcslMath.san((float) (z / norm));
		out[3] = OcslMath.san((float) (w / norm));
	}
}
