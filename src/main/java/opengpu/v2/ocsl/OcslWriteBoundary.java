package opengpu.v2.ocsl;

/**
 * The animator's property-write boundary — ANIM-9. What happens to a value on its way OUT.
 *
 * <h2>The fail-soft is STATELESS, and that is the fix rather than the tidying</h2>
 *
 * The design said a non-finite animator output is rejected with the <b>previous value retained</b>.
 * That sentence is the only cross-frame state anywhere in the animator design, and it is per-client
 * — which makes it the one place the purity guarantee is defeated. A client watching for a minute
 * holds the last good angle; a client that joined on the failing frame has no previous value at all;
 * and if the cause persisted the two would <b>disagree indefinitely, with no path to
 * reconciliation</b>, because a resync snaps the BASE and the server holds no animator output to
 * snap to. So a rejected write is stateless instead — see below.
 *
 * <h2>THE REJECT ARM IS UNREACHABLE FROM THE CPU VM, and the argument above no longer names a live
 * hazard</h2>
 *
 * This javadoc cited {@code setAnimatorUniform(node, "speed", 0/0)} as "enough" to cause that
 * indefinite disagreement. <b>It is not, and has not been since ANIM-9(b) landed.</b> Two rules
 * written after this one close it, and neither is this boundary:
 * <ul>
 * <li><b>Ingress substitutes zero for a non-finite binding</b> ({@code registerBinding
 *     substitute-zero}), so {@code 0/0} never enters the frame — swept at every open stage by
 *     {@code OcslIngressTest}.</li>
 * <li><b>A4's catch-all sanitizes op RESULTS, not only operands</b> ({@link OcslMath}), so an
 *     in-domain evaluation that overflows float32 yields 0 rather than infinity.</li>
 * </ul>
 * Constants are refused non-finite at the pool, so every value in an animator frame is finite and
 * {@link #accepts} never answers false for a VM output. {@code AnimatorProgramTest} demonstrates it
 * end to end: {@code 1e30 * 1e30} composes to the base because the offset is <b>zero</b>, not
 * because a write was rejected — and those two look identical downstream, which is why that test
 * asserts the raw value rather than the composed one.
 *
 * <b>That induction was stated here as "every op result" and audited only afterwards, which is the
 * wrong order and turned up a real gap.</b> Of the VM's register-writes, the computing ones all
 * reach {@code OcslMath} and the rest are literals or copies of already-sanitized values — except
 * {@code OP_BNOT}, which computed {@code 1f - x} and so was total only CONDITIONALLY, on the
 * validator having typed its operand BOOL. It now emits a literal like its five siblings, so the
 * property holds inside the VM instead of leaning on another class. {@code OP_SELECT} remains a
 * deliberate exemption and says so: it copies the chosen arm and never reads the other.
 *
 * <b>The boundary stays regardless.</b> {@link OcslCompose#compose} takes a float from a CALLER and
 * applies ingress and this accept/reject arm itself, because "an ordering obligation that has never
 * once been met is not a contract" — the guarantee is about what compose accepts, not about who
 * happens to call it today. The reject arm is redundancy that became total, which is a fine state
 * for a guard. What would be wrong is citing VM totality as grounds to weaken it.
 *
 * <b>The CLAMP arm HAS A CALLER as of Phase 3.3a (2026-08-20).</b> This paragraph used to say
 * {@link #clampForWrite} had no caller in {@code src/main}, and that was true for as long as
 * nothing consumed composed values. Its consumer now exists:
 * {@code AnimatorOverlay.applyProperty} composes and then clamps, per property, before the value
 * is stored — which is where the ordering obligation this class warns about came due, exactly as
 * predicted.
 *
 * NOT THE SAME AS "on the render path", and the distinction is the one this paragraph got wrong
 * once already. A first version of this correction said the arm was "now LIVE"; that is a claim
 * about reachability, and it overshot — {@code AnimatorOverlay} is itself called only from tests
 * until 3.3b substitutes it at {@code Canvas2dRenderer}. Having a caller and being reached from a
 * production entry point are two claims, and swapping the weaker note for a stronger one is how a
 * ledger item gets marked done early.
 *
 * {@code compose} still does not clamp, and must not: composition can leave the finite range even
 * when both operands were inside it, so the clamp belongs at the consumer that knows the value is
 * final, not inside the operation that produced it.
 *
 * The program could not defend itself either, when this mattered: the only NaN test the op set can
 * express is {@code select(EQ(x,x), x, fallback)}, and {@code EQ} is separately documented as
 * tolerance-hostile with no domain-table row at a non-finite argument.
 *
 * So a rejected write <b>falls back to the server base for that property, on that frame</b>. Identical
 * on every client regardless of history, needs no retained state, and is exactly what detach and
 * resync already do. It has a pleasing form given ANIM-3: falling back to the base IS composing with
 * the property's identity, which is why {@link OcslCompose#identityFor} exists.
 *
 * <h2>Range, because non-finite was never the whole problem</h2>
 *
 * Nothing handled finite out-of-range, and the animator boundary is the one of the three colour
 * boundaries writing into a <b>packed 8-bit-per-channel int, where an overshoot does not
 * saturate</b>. The dry run's arithmetic: {@code (int)(1.5 * 255) = 382 = 0x17E}, and shifted into
 * the alpha byte that lands as <b>126</b> — so a rounding overshoot produces a DARK FLASH rather
 * than a clamp. The bake clamp existed and the pixel rule extended it to final colour; both were
 * scoped by name to their own stages, and this boundary had no range rule at all.
 *
 * Clamp first, then quantize. In that order the overshoot cannot reach the shift.
 */
public final strictfp class OcslWriteBoundary {
	private OcslWriteBoundary() {}

	/**
	 * The stated finite magnitude for unbounded TRS properties.
	 *
	 * ANIM-9 asks that "a 1e38 position never reaches the transform math", and names no value. 1e6
	 * is chosen so the PRODUCTS stay far inside float32 rather than because positions are expected
	 * near it: transform math multiplies position by scale, and 1e6 x 1e6 = 1e12, which squares to
	 * 1e24 in a distance calculation — <b>fourteen</b> orders below float32's 3.4e38. A limit chosen
	 * to bound the inputs alone would let a legal position and a legal scale multiply into infinity,
	 * which is the failure this clamp exists to prevent and would have been the natural mistake.
	 *
	 * <b>Both sentences here were wrong when first written, and an adversarial review caught them
	 * where the author's own mutation sweep did not.</b> The margin was stated as "five orders"
	 * (it is fourteen), and the claim that it is "generous against real scenes — the frame-width cap
	 * is 1024" cited {@code SurfaceTable.MAX_FRAME_WIDTH}, which bounds an OCSL program's REGISTER
	 * FRAME and has nothing whatever to do with scene coordinates. The constant survives both
	 * corrections; the arguments for it did not. No scene-coordinate bound is cited in its place
	 * because this file has not established one, and inventing a second number to replace a wrong
	 * one is how the first went in.
	 */
	public static final float TRS_LIMIT = 1.0e6f;

	/** Colour channels quantize to this many steps: 8 bits per channel, the packed destination. */
	public static final int COLOR_STEPS = 255;

	/**
	 * Whether an animator output may be written at all.
	 *
	 * Non-finite is the whole test — finite out-of-range is CLAMPED rather than rejected, because a
	 * value that merely overshoots still carries the author's intent and snapping it to the base
	 * would be a bigger lie than clamping it.
	 */
	public static boolean accepts(float animatorOutput) {
		return OcslMath.finite(animatorOutput);
	}

	/**
	 * The animator output to actually compose with, substituting the identity when the write is
	 * rejected — which is what "falls back to the server base" means, expressed once.
	 *
	 * Tint has no identity (it REPLACES), so a rejected tint write cannot be expressed as an
	 * identity output; {@link #acceptedTint} handles that case by name instead.
	 */
	public static float accepted(int propertyId, float animatorOutput) {
		return accepts(animatorOutput) ? animatorOutput : OcslCompose.identityFor(propertyId);
	}

	/**
	 * A rejected tint write falls back to the server base directly, because tint replaces and so has
	 * no identity output that would leave the base alone.
	 *
	 * The base goes through {@link OcslIngress#tintBase} rather than being narrowed inline: the
	 * fallback is only worth having if the thing it falls back TO is representable, and a bare
	 * {@code (float) serverBase} let a non-finite base through the one path built to catch one.
	 */
	public static float acceptedTint(double serverBase, float animatorOutput) {
		return accepts(animatorOutput) ? animatorOutput : OcslIngress.tintBase(serverBase);
	}

	/**
	 * An {@code OUT_ABS} write's value: the output, or the SERVER BASE when the output is rejected.
	 *
	 * The absolute form's fallback reaches the base DIRECTLY, where the relative form reaches it by
	 * substituting the composition identity — {@link #accepted}. Same destination, and it has to be
	 * spelled twice because the routes differ: an absolute write composes with nothing, so an
	 * identity output would not leave the base alone, it would BE the displayed value (0 for
	 * {@code x}, 1 for {@code sx}) and would teleport the node on any frame with a bad output.
	 * {@link #acceptedTint} is this same shape for the one property that already replaced.
	 */
	public static float acceptedAbsolute(int propertyId, double serverBase, float animatorOutput) {
		return accepts(animatorOutput) ? animatorOutput
				: OcslIngress.base(propertyId, serverBase);
	}

	/**
	 * {@code rot3d}'s spelling of {@link #accepted} — the animator's quaternion, or the identity.
	 *
	 * ANIM-9(a) SAID "a rejected write falls back to the server base" AND ROT3D DID NOT DO THAT.
	 * {@code identityFor} throws for the quaternion by design, so the scalar path could not express
	 * it, and {@code composeRot3d}'s zero-length guard was doing double duty: a non-finite animator
	 * quaternion made the norm NaN and fell out to the IDENTITY ROTATION — which discards the server
	 * base as well as the bad output, so a node with a server rotation snapped upright the moment its
	 * animator overflowed. The same one-sided-fix shape as the base side, from the same increment.
	 *
	 * Substituting the identity quaternion for the animator's half leaves {@code q_srv} intact
	 * through the product, which is what the sentence promised.
	 *
	 * A predicate rather than a copy, for the allocation reason {@link OcslIngress#acceptsAll} gives.
	 */
	public static boolean acceptsAll(float[] animatorQuaternion) {
		for (int i = 0; i < 4; i++) {
			if (!accepts(animatorQuaternion[i])) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Clamp a composed value for its destination. Applied AFTER composition, to the displayed value.
	 *
	 * Composition itself can leave the finite range even when both operands were inside it — two
	 * legal scales multiply, two legal positions add — so clamping the animator's output alone would
	 * not bound what reaches the transform math.
	 */
	public static float clampForWrite(int propertyId, float composed) {
		switch (propertyId) {
			case OcslWire.PROP_ANIM_TINT:
				// Colour clamps to 0..1 BEFORE quantization; see quantizeColorChannel.
				return OcslMath.clamp(composed, 0.0f, 1.0f);
			case OcslWire.PROP_ANIM_X:
			case OcslWire.PROP_ANIM_Y:
			case OcslWire.PROP_ANIM_TZ:
			case OcslWire.PROP_ANIM_SX:
			case OcslWire.PROP_ANIM_SY:
			case OcslWire.PROP_ANIM_SZ:
				return OcslMath.clamp(composed, -TRS_LIMIT, TRS_LIMIT);
			case OcslWire.PROP_ANIM_ROT2D:
				// Angles are periodic, so a magnitude clamp would be meaningless -- but an unbounded
				// one still has to stay finite for the transform math, and the same limit does it.
				return OcslMath.clamp(composed, -TRS_LIMIT, TRS_LIMIT);
			default:
				return OcslMath.san(composed);
		}
	}

	/**
	 * A colour channel in 0..1 to its 8-bit packed value, ROUND-HALF-UP — the stated rounding mode.
	 *
	 * The clamp happens here too rather than being assumed, because this is the function that feeds
	 * the shift and the whole defect was an unclamped value reaching it. {@code (int)(1.5 * 255)} is
	 * 382, which is {@code 0x17E}; shifted into the alpha byte it lands as 126 and the node flashes
	 * dark. Clamping first makes 255 the maximum by construction.
	 *
	 * Half-up rather than half-even because the destination is a display value where the mode is
	 * unobservable except at exact midpoints, and half-up is what every hand-written
	 * {@code (int)(v * 255 + 0.5f)} in the wild already does — stating the one already in use costs
	 * nothing and stating a different one silently shifts every existing colour by a step.
	 */
	public static int quantizeColorChannel(float value) {
		float clamped = OcslMath.clamp(value, 0.0f, 1.0f);
		return (int) (clamped * COLOR_STEPS + 0.5f);
	}
}
