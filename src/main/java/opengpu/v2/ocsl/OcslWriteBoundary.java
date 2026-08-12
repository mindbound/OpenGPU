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
 * and if the cause persists — one {@code setAnimatorUniform(node, "speed", 0/0)} is enough — the two
 * <b>disagree indefinitely, with no path to reconciliation</b>, because a resync snaps the BASE and
 * the server holds no animator output to snap to.
 *
 * The program cannot defend itself either: the only NaN test the op set can express is
 * {@code select(EQ(x,x), x, fallback)}, and {@code EQ} is separately documented as tolerance-hostile
 * with no domain-table row at a non-finite argument.
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
	 * 1e24 in a distance calculation and is still five orders below float32's 3.4e38. A limit chosen
	 * to bound the inputs alone would let a legal position and a legal scale multiply into infinity,
	 * which is the failure this clamp exists to prevent and would have been the natural mistake.
	 *
	 * Generous against real scenes by a wide margin — the frame-width cap is 1024 and canvases are
	 * pixel-scaled — so it bounds the pathological without touching anything an author would write.
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
	 */
	public static float acceptedTint(double serverBase, float animatorOutput) {
		return accepts(animatorOutput) ? animatorOutput : (float) serverBase;
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
