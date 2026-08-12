package opengpu.v2.ocsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ANIM-9's write boundary.
 *
 * Every vector here carries a second assertion naming the WRONG answer it excludes. That is not
 * decoration: the same session produced a test whose entire stated purpose was catching a
 * re-coupling and which caught nothing, because nobody computed what the wrong implementation
 * returns on the chosen inputs. Writing the exclusion into the test is what forces that arithmetic
 * to happen at all.
 */
public strictfp class OcslWriteBoundaryTest {

	@Test
	public void theQuantizerClampsBeforeItScalesSoAnOvershootCannotReachTheShift() throws Exception {
		// THE DEFECT, in the dry run's own arithmetic. The tint destination is a packed
		// 8-bit-per-channel int and an overshoot does NOT saturate: (int)(1.5 * 255) = 382 = 0x17E,
		// and shifted into the alpha byte that lands as 126 -- a dark flash where the author asked
		// for full brightness.
		int packed = OcslWriteBoundary.quantizeColorChannel(1.5f);
		assertEquals("an overshoot must clamp to full, not wrap", 255, packed);
		assertTrue("the unclamped path gives 382, which is what this excludes", packed != 382);
		assertEquals("and 382 & 0xFF is the 126 that produced the dark flash", 126, 382 & 0xFF);
		assertTrue("so the packed value must fit a byte", packed >= 0 && packed <= 255);

		// The whole overshoot range collapses to full rather than wrapping somewhere plausible.
		assertEquals(255, OcslWriteBoundary.quantizeColorChannel(1.0f));
		assertEquals(255, OcslWriteBoundary.quantizeColorChannel(2.0f));
		assertEquals(255, OcslWriteBoundary.quantizeColorChannel(Float.MAX_VALUE));
		assertEquals(0, OcslWriteBoundary.quantizeColorChannel(-0.5f));
		assertEquals(0, OcslWriteBoundary.quantizeColorChannel(Float.NEGATIVE_INFINITY));
	}

	@Test
	public void theRoundingModeIsHalfUpAndIsPinnedAtAnExactMidpoint() throws Exception {
		// Chosen so half-up and half-even DISAGREE, which is the only place a rounding mode is
		// observable. 0.5/255 lands exactly on x.5 after scaling: 127.5.
		float exactMidpoint = 127.5f / 255.0f;
		int packed = OcslWriteBoundary.quantizeColorChannel(exactMidpoint);
		assertEquals("half-up takes 127.5 to 128", 128, packed);
		assertTrue("half-even would give 128 here too, so the NEXT midpoint is the discriminator",
				packed == 128);

		// 126.5 -> half-up 127, half-even 126. This is the vector that actually separates them.
		int lower = OcslWriteBoundary.quantizeColorChannel(126.5f / 255.0f);
		assertEquals("half-up takes 126.5 to 127", 127, lower);
		assertTrue("half-even would give 126, and that is what this excludes", lower != 126);

		// Truncation would give 126 as well, and is excluded by the same vector.
		assertTrue("truncation gives 126 too", lower != 126);
	}

	@Test
	public void aRejectedWriteFallsBackToTheServerBaseWithNoRetainedState() throws Exception {
		// The stateless fail-soft. Composing the identity IS falling back to the base, which is why
		// this can be expressed without any per-client history.
		double base = 12.5;
		float rejected = OcslWriteBoundary.accepted(OcslWire.PROP_ANIM_X, Float.NaN);
		assertEquals("a rejected additive write substitutes 0", 0.0f, rejected, 0f);
		assertEquals("so the displayed value is the base, exactly",
				(float) base, OcslCompose.compose(OcslWire.PROP_ANIM_X, base, rejected), 0f);

		// And for a MULTIPLICATIVE property the identity is 1, not 0 -- substituting 0 would
		// collapse the node to nothing, which is the failure the identity column exists to prevent.
		float rejectedScale = OcslWriteBoundary.accepted(OcslWire.PROP_ANIM_SX, Float.NaN);
		assertEquals("a rejected multiplicative write substitutes 1", 1.0f, rejectedScale, 0f);
		assertTrue("substituting 0 would collapse the node and is what this excludes",
				rejectedScale != 0.0f);
		assertEquals(2.5f, OcslCompose.compose(OcslWire.PROP_ANIM_SX, 2.5, rejectedScale), 0f);

		// STATELESS means the answer does not depend on history: the same rejection on the first
		// frame a client ever sees gives the same value as on the thousandth. A client that joined
		// on the failing frame and one that watched for a minute agree -- which is the property the
		// "previous value retained" rule could not have, and the reason it defeated purity.
		for (int frame = 0; frame < 3; frame++) {
			assertEquals(0.0f, OcslWriteBoundary.accepted(OcslWire.PROP_ANIM_X, Float.NaN), 0f);
		}

		// A finite value passes through untouched -- rejection is for non-finite only.
		assertEquals(3.25f, OcslWriteBoundary.accepted(OcslWire.PROP_ANIM_X, 3.25f), 0f);
		assertTrue(OcslWriteBoundary.accepts(0.0f));
		assertTrue(!OcslWriteBoundary.accepts(Float.POSITIVE_INFINITY));
		assertTrue(!OcslWriteBoundary.accepts(Float.NaN));
	}

	@Test
	public void aRejectedTintWriteUsesTheBaseDirectlyBecauseTintHasNoIdentity() throws Exception {
		assertEquals("tint replaces, so the fallback is the base itself",
				0.75f, OcslWriteBoundary.acceptedTint(0.75, Float.NaN), 0f);
		assertEquals(0.25f, OcslWriteBoundary.acceptedTint(0.75, 0.25f), 0f);
	}

	@Test
	public void theTrsClampBoundsTheProductAndNotJustTheOperands() throws Exception {
		// The clamp exists so "a 1e38 position never reaches the transform math". The limit is
		// chosen against the PRODUCT: position x scale must stay far inside float32, because two
		// separately-legal values multiplying to infinity is the failure being prevented.
		float clamped = OcslWriteBoundary.clampForWrite(OcslWire.PROP_ANIM_X, 1.0e38f);
		assertEquals(OcslWriteBoundary.TRS_LIMIT, clamped, 0f);
		assertTrue("an unclamped 1e38 is what this excludes", clamped != 1.0e38f);

		float clampedScale = OcslWriteBoundary.clampForWrite(OcslWire.PROP_ANIM_SX, 1.0e38f);
		double worstProduct = (double) clamped * clampedScale;
		assertTrue("the worst legal product must stay finite in float32, got " + worstProduct,
				OcslMath.finite((float) worstProduct));
		assertTrue("and its square must too, for distance math",
				OcslMath.finite((float) (worstProduct * worstProduct)));
		// Without the clamp this product is 1e76, which is infinity in float32 -- the concrete
		// reason a limit chosen to bound the operands alone would not have been enough.
		assertTrue("1e38 squared overflows float32, which is the case being excluded",
				Float.isInfinite(1.0e38f * 1.0e38f));

		assertEquals(-OcslWriteBoundary.TRS_LIMIT,
				OcslWriteBoundary.clampForWrite(OcslWire.PROP_ANIM_Y, -1.0e38f), 0f);
		assertEquals("values inside the limit pass through untouched",
				42.5f, OcslWriteBoundary.clampForWrite(OcslWire.PROP_ANIM_X, 42.5f), 0f);
	}

	@Test
	public void theBoundaryClampsTheCOMPOSEDValueNotOnlyTheAnimatorOutput() throws Exception {
		// Two separately-legal operands can compose out of range: a base at the limit and a scale of
		// 2 multiply past it. Clamping the animator's output alone would let that reach the
		// transform math, which is why the boundary is applied after composition.
		float composed = OcslCompose.compose(OcslWire.PROP_ANIM_SX,
				OcslWriteBoundary.TRS_LIMIT, 2.0f);
		assertTrue("composition alone leaves the range", composed > OcslWriteBoundary.TRS_LIMIT);
		assertEquals("and the boundary brings it back", OcslWriteBoundary.TRS_LIMIT,
				OcslWriteBoundary.clampForWrite(OcslWire.PROP_ANIM_SX, composed), 0f);
	}

	@Test
	public void everyOwnablePropertyHasABoundedWriteBoundary() throws Exception {
		// Ties the boundary to the property table the same way the composition rules are tied: a
		// property that can be written must have a rule bounding what it writes.
		int[] scalars = {
			OcslWire.PROP_ANIM_X, OcslWire.PROP_ANIM_Y, OcslWire.PROP_ANIM_SX,
			OcslWire.PROP_ANIM_SY, OcslWire.PROP_ANIM_ROT2D, OcslWire.PROP_ANIM_TINT,
			OcslWire.PROP_ANIM_TZ, OcslWire.PROP_ANIM_SZ,
		};
		float[] hostile = {
			Float.MAX_VALUE, -Float.MAX_VALUE, 1.0e38f, Float.POSITIVE_INFINITY,
			Float.NEGATIVE_INFINITY, Float.NaN,
		};
		for (int i = 0; i < scalars.length; i++) {
			for (int h = 0; h < hostile.length; h++) {
				float out = OcslWriteBoundary.clampForWrite(scalars[i], hostile[h]);
				assertTrue("property " + scalars[i] + " let " + hostile[h] + " through as " + out,
						OcslMath.finite(out));
				assertTrue("and it must be bounded, got " + out,
						Math.abs(out) <= OcslWriteBoundary.TRS_LIMIT);
			}
		}
	}
}
