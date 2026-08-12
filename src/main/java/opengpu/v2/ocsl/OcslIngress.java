package opengpu.v2.ocsl;

/**
 * The INGRESS boundary — ANIM-9(b). What happens to a value on its way IN, at <b>every</b> stage.
 *
 * <h2>Why this is a class and not a paragraph</h2>
 *
 * The rule existed. It was written under the heading "<b>Non-finite values at the pixel stage</b>",
 * and its own back-reference pointed at the property-<i>write</i> rule ("mirroring the animator
 * rule"), so it could not even be read as restating something already global. Whether it bound an
 * animator uniform was genuinely ambiguous — and it is the rule that makes the whole write-boundary
 * reject path nearly unreachable, so the ambiguity was load-bearing rather than cosmetic.
 *
 * The amendment asked for the paragraph to be lifted into the shared uniform section. A paragraph
 * move enforces nothing: this project has now watched three prose obligations of exactly that form
 * get crossed silently. So the rule is lifted <b>into a signature instead</b> — every method here
 * takes no stage, and every ingress in the codebase routes through one of them. A future edit that
 * re-scopes the rule to one stage has to add a parameter that nothing would pass, which is the kind
 * of obligation that holds.
 *
 * <h2>Three ingresses, two remedies, and the difference is whether there is anything to refuse to</h2>
 *
 * <ul>
 * <li><b>The constant pool</b> — {@link #accepts}, and the program is REFUSED at load. Authored
 *     content, checked once, and refusing costs the author nothing but a build error. {@code SPLAT},
 *     {@code SELECT}, {@code SWZ} and the constructors copy a constant into the frame and compute
 *     nothing A4's catch-all could apply to, so a non-finite here would reach {@code OUT} intact.</li>
 * <li><b>A register binding</b> — {@link #bound}, and the value becomes 0. There is nothing to
 *     refuse to: the frame slot must hold something before the program runs, and a host binding a
 *     built-in has no error path at frame rate. This is the ingress the design paragraph is about.</li>
 * <li><b>The animator's server base</b> — {@link #base}, and the value becomes the property's
 *     composition identity. Neither 0 nor a refusal: see below.</li>
 * </ul>
 *
 * <h2>"Previous value retained" is refused HERE, for ANIM-9(a)'s reason</h2>
 *
 * The design's remedy at the set-call was to reject and keep the previous value. That is safe
 * <b>only at the replicated table</b> — the server holds one uniform table, so a value it never
 * accepted is a value no client ever sees, and the retained value is identical everywhere by
 * construction. It is <b>not</b> safe at the executor, which is per-client: a VM that has been
 * running for a minute has a binding history, one created on the failing frame has none, and the two
 * would display different values indefinitely with no path to reconciliation. That is precisely the
 * defect ANIM-9(a) removed from the write boundary, and it would have walked straight back in
 * through this door.
 *
 * So the boundary splits, and the split is where the state lives: <b>the set-call may retain,
 * because the table is replicated; the executor substitutes, because the frame is not.</b> The
 * set-call half is not implemented here — no host-facing {@code setUniform} exists while the surface
 * is shut — and is carried as an obligation rather than claimed.
 */
public final strictfp class OcslIngress {
	private OcslIngress() {}

	/**
	 * Whether a REFUSING ingress accepts this value. The constant pool's test.
	 *
	 * Deliberately the same predicate as the write boundary's: a value is representable or it is
	 * not, and having two spellings of "finite" would be one more place for them to drift apart.
	 */
	public static boolean accepts(float value) {
		return OcslMath.finite(value);
	}

	/**
	 * What a SUBSTITUTING ingress yields — a register binding, at any stage.
	 *
	 * Zero, not the previous value. An ingress value has no op to produce a result for; it is the
	 * operand itself that must be made representable before any op can see it, which is why A4's
	 * catch-all (whole result zero) does not cover this case and {@link OcslMath#san} exists.
	 */
	public static float bound(float value) {
		return OcslMath.san(value);
	}

	/**
	 * The animator's server base for a scalar property, narrowed and made composable.
	 *
	 * <b>Zero is wrong here, and that is the whole point of the method.</b> A non-finite base reaches
	 * an op as an operand, so A4's catch-all zeroes the entire result — {@code mul(NaN, 2.0)} is 0,
	 * not 2.0 — and for a MULTIPLICATIVE property that means the node collapses to nothing. The very
	 * failure {@link OcslCompose#identityFor} was introduced to prevent on the output side was still
	 * open on the base side, in the same file, with the same consequence: ANIM-9(a) closed one half
	 * of a symmetric rule.
	 *
	 * Substituting the composition identity restores the mirror. A rejected animator output falls
	 * back to the base; a rejected base falls back to the animator's output. The same constant serves
	 * both directions because add and multiply are commutative — {@code rot3d}'s product is not,
	 * which is why {@link #acceptsAll} is separate rather than a loop over components.
	 */
	public static float base(int propertyId, double serverBase) {
		float narrowed = (float) serverBase;
		if (accepts(narrowed)) {
			return narrowed;
		}
		switch (OcslCompose.ruleFor(propertyId)) {
			case OcslCompose.RULE_ADD:
			case OcslCompose.RULE_MULTIPLY:
				return OcslCompose.identityFor(propertyId);
			case OcslCompose.RULE_REPLACE:
				// tint. The base is unread when the animator's output is accepted, and read only as
				// the fallback -- see tintBase for why that fallback is white rather than zero.
				return tintBase(serverBase);
			case OcslCompose.RULE_QUATERNION:
				throw new IllegalArgumentException("rot3d's base is a vec4; it is checked whole by"
						+ " acceptsAll() and substituted inside composeRot3d()");
			default:
				throw new IllegalArgumentException("property " + propertyId
						+ " is not composable at the animator surface");
		}
	}

	/**
	 * The neutral tint, which is WHITE and not zero.
	 *
	 * {@code identityFor} throws for tint and is right to: tint REPLACES, so no animator OUTPUT
	 * leaves the base alone. The base side is a different question with an answer — the renderer
	 * computes a node's effective tint as its own times its parent group's, so the value that leaves
	 * a node's appearance alone is the multiplicative unit. Substituting 0 would fade the node to
	 * black, which is the same class of failure as a collapsed scale and just as hard to trace back
	 * to a NaN somebody set three frames ago.
	 */
	public static float tintBase(double serverBase) {
		float narrowed = (float) serverBase;
		return accepts(narrowed) ? narrowed : 1.0f;
	}

	/**
	 * Whether the animator's server base for {@code rot3d} survives narrowing to float32 intact.
	 *
	 * ALL FOUR COMPONENTS OR NONE, which is why this is a predicate over the whole quaternion rather
	 * than a per-component repair. A quaternion with one bad component has no meaningful partial
	 * fix — substituting 0 for that component alone yields a rotation nobody asked for, at an
	 * arbitrary angle, rather than a neutral one. The identity is the only substitution that leaves
	 * the other side of the product intact, and it works from either side despite the product being
	 * non-commutative: {@code q * 1 = q} and {@code 1 * q = q}.
	 *
	 * A predicate and not a copy-into-{@code out} because {@link OcslCompose#composeRot3d} runs once
	 * per animated node per frame and this codebase refuses render-thread allocation; the substituted
	 * components are selected into locals there.
	 */
	public static boolean acceptsAll(double[] serverQuaternion) {
		for (int i = 0; i < 4; i++) {
			if (!accepts((float) serverQuaternion[i])) {
				return false;
			}
		}
		return true;
	}
}
