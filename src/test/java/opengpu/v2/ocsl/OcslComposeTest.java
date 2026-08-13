package opengpu.v2.ocsl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * ANIM-3's composition equations, vectored the way the amendment demands.
 *
 * <b>Every vector here sits on a NON-IDENTITY base</b> — server {@code rot = 45°}, server
 * {@code sx = 2} — because the amendment says in as many words that "an identity-base vector passes
 * under every candidate rule and therefore proves nothing; a vector suite without these cannot
 * detect a wrong implementation". That is the same failure shape as the round-number inputs that let
 * a `time` mutant survive 160 tests: a tidy base is where every candidate rule agrees.
 */
public strictfp class OcslComposeTest {

	private static final double SERVER_ROT_45 = Math.PI / 4.0;
	private static final double SERVER_SX = 2.0;

	@Test
	public void positionAddsInTheParentFrameAndIsNotRotatedByTheServerTransform() throws Exception {
		// THE PIN THAT SEPARATES THE TWO READINGS. Under per-property composition an animator
		// emitting dx = 3 moves the node 3 units along the PARENT's x, whatever the server rotation
		// is. Under matrix composition the same output would be rotated by the server's 45 degrees
		// and land at (2.121, 2.121) instead. The server rotation is deliberately non-zero here so
		// the two readings cannot coincide.
		float composed = OcslCompose.compose(OcslWire.PROP_ANIM_X, 10.0, 3.0f);
		assertEquals("x composes additively in the parent's frame", 13.0f, composed, 0f);

		// Same output, same answer, regardless of what the server rotation is -- which IS the claim.
		assertEquals(13.0f, OcslCompose.compose(OcslWire.PROP_ANIM_X, 10.0, 3.0f), 0f);
		assertEquals(-4.5f, OcslCompose.compose(OcslWire.PROP_ANIM_Y, -1.5, -3.0f), 0f);
	}

	@Test
	public void scaleMultipliesAndThatIsTheProbeThatMakesTheRuleUnarguable() throws Exception {
		// The dry run's consistency probe: an animator emitting 0.9649 against a server sx of 2.
		// Under multiply that is a 3.5% squash -> 1.9298. Under add it would be 2.9649, a 96%
		// stretch, from the same blob. Position and rotation cannot distinguish the two rules
		// because 0 is their identity under both; scale is the only property that can.
		float composed = OcslCompose.compose(OcslWire.PROP_ANIM_SX, SERVER_SX, 0.9649f);
		assertEquals("scale is multiplicative", 1.9298f, composed, 1e-6f);
		assertTrue("an additive reading would give 2.9649 and is what this rules out",
				Math.abs(composed - 2.9649f) > 1.0f);

		assertEquals(1.5f, OcslCompose.compose(OcslWire.PROP_ANIM_SY, 3.0, 0.5f), 0f);
	}

	@Test
	public void rotationAddsInRadiansOnANonIdentityBase() throws Exception {
		float composed = OcslCompose.compose(OcslWire.PROP_ANIM_ROT2D, SERVER_ROT_45, 0.25f);
		assertEquals((float) SERVER_ROT_45 + 0.25f, composed, 0f);
	}

	@Test
	public void theIdentityColumnIsZeroForAdditiveAndOneForMultiplicative() throws Exception {
		// The column exists so a preset's neutral default is documentable. Getting it wrong on a
		// scale property is not a subtle drift -- 0.0 collapses the node to nothing.
		assertEquals(0.0f, OcslCompose.identityFor(OcslWire.PROP_ANIM_X), 0f);
		assertEquals(0.0f, OcslCompose.identityFor(OcslWire.PROP_ANIM_Y), 0f);
		assertEquals(0.0f, OcslCompose.identityFor(OcslWire.PROP_ANIM_ROT2D), 0f);
		assertEquals(0.0f, OcslCompose.identityFor(OcslWire.PROP_ANIM_TZ), 0f);
		assertEquals(1.0f, OcslCompose.identityFor(OcslWire.PROP_ANIM_SX), 0f);
		assertEquals(1.0f, OcslCompose.identityFor(OcslWire.PROP_ANIM_SY), 0f);
		assertEquals(1.0f, OcslCompose.identityFor(OcslWire.PROP_ANIM_SZ), 0f);

		// And each identity actually leaves a NON-IDENTITY base alone, which is the property the
		// column claims rather than the constant it names.
		assertEquals(SERVER_SX, OcslCompose.compose(OcslWire.PROP_ANIM_SX, SERVER_SX,
				OcslCompose.identityFor(OcslWire.PROP_ANIM_SX)), 1e-9);
		assertEquals((float) SERVER_ROT_45, OcslCompose.compose(OcslWire.PROP_ANIM_ROT2D,
				SERVER_ROT_45, OcslCompose.identityFor(OcslWire.PROP_ANIM_ROT2D)), 0f);
	}

	@Test
	public void tintReplacesAndHasNoIdentityToDefaultTo() throws Exception {
		assertEquals("tint replaces its base outright", 0.25f,
				OcslCompose.compose(OcslWire.PROP_ANIM_TINT, 0.9, 0.25f), 0f);
		try {
			OcslCompose.identityFor(OcslWire.PROP_ANIM_TINT);
			fail("tint has no constant identity; naming one would name a value that blacks the node");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("no identity"));
		}
	}

	@Test
	public void theQuaternionProductAppliesTheAnimatorFirstAndNormalizes() throws Exception {
		// A 90-degree server yaw about Z, and a 90-degree animator yaw about Z. Applied in either
		// order these happen to agree (rotations about a shared axis commute), so the ORDER is
		// pinned by the mixed-axis case below; this one pins the arithmetic and the normalization.
		double h = Math.sqrt(0.5);
		double[] server = { 0.0, 0.0, h, h };
		float[] animator = { 0.0f, 0.0f, (float) h, (float) h };
		float[] out = new float[4];
		OcslCompose.composeRot3d(server, animator, out);

		assertEquals("two 90-degree yaws compose to 180: z = 1", 1.0f, out[2], 1e-6f);
		assertEquals(0.0f, out[3], 1e-6f);
		assertEquals(1.0f, length(out), 1e-6f);
	}

	@Test
	public void theQuaternionOrderIsServerTimesAnimatorAndTheAxesDoNotCommute() throws Exception {
		// MIXED AXES, where the order is observable: a server yaw about Z and an animator pitch
		// about X. q_srv*q_anim and q_anim*q_srv differ, so this is the vector that pins which one
		// the contract means. Identity-base or shared-axis vectors pass under both.
		double h = Math.sqrt(0.5);
		double[] serverYaw = { 0.0, 0.0, h, h };
		float[] animatorPitch = { (float) h, 0.0f, 0.0f, (float) h };

		float[] ours = new float[4];
		OcslCompose.composeRot3d(serverYaw, animatorPitch, ours);

		// q_srv * q_anim, by hand: (0.5, 0.5, 0.5, 0.5).
		assertEquals(0.5f, ours[0], 1e-6f);
		assertEquals(0.5f, ours[1], 1e-6f);
		assertEquals(0.5f, ours[2], 1e-6f);
		assertEquals(0.5f, ours[3], 1e-6f);

		// The reversed order gives (0.5, -0.5, 0.5, 0.5) -- a different rotation. Computed here so
		// the test states what it EXCLUDES rather than only what it expects.
		double[] pitchAsServer = { h, 0.0, 0.0, h };
		float[] yawAsAnimator = { 0.0f, 0.0f, (float) h, (float) h };
		float[] reversed = new float[4];
		OcslCompose.composeRot3d(pitchAsServer, yawAsAnimator, reversed);
		assertEquals("the reversed order must differ in y, or the test pins nothing",
				-0.5f, reversed[1], 1e-6f);
	}

	@Test
	public void aDegenerateQuaternionFallsBackToIdentityRatherThanNaN() throws Exception {
		float[] out = new float[4];
		OcslCompose.composeRot3d(new double[] { 0, 0, 0, 0 }, new float[] { 0, 0, 0, 0 }, out);
		assertEquals(0.0f, out[0], 0f);
		assertEquals(0.0f, out[1], 0f);
		assertEquals(0.0f, out[2], 0f);
		assertEquals("a zero-length product is the identity, not NaN", 1.0f, out[3], 0f);

		// Non-finite inputs too, consistent with A4's catch-all.
		OcslCompose.composeRot3d(new double[] { Double.NaN, 0, 0, 1 },
				new float[] { 0, 0, 0, 1 }, out);
		for (int i = 0; i < 4; i++) {
			assertTrue("lane " + i + " must stay finite", OcslMath.finite(out[i]));
		}
	}

	@Test
	public void theBaseIsNarrowedBeforeComposingSoTheResultIsAPureFunctionOfWhatTheVmSaw()
			throws Exception {
		// THE OPERANDS ARE CHOSEN SO THE TWO ORDERINGS DISAGREE, which took working the arithmetic
		// by hand rather than picking tidy numbers. ulp(1.0f) is 1.192e-7, so the rounding boundary
		// is at half of that, 5.96e-8:
		//
		//   narrow first  -> (float)(1.0 + 4e-8) = 1.0f, then 1.0f + 3e-8f = 1.0f       (3e-8 < half-ulp)
		//   narrow last   -> (float)(1.0 + 4e-8 + 3e-8) = (float)1.00000007 = 1.0000001f (7e-8 > half-ulp)
		//
		// A first draft used base = 1.0 + 1e-9 with an output of 1e-4, where the sub-float bits are
		// three orders below the ulp and BOTH orderings give 1.0001f. It would have passed with the
		// pin inverted -- the same round-number trap that let a `time` mutant survive 160 tests.
		double base = 1.0 + 4e-8;
		float composed = OcslCompose.compose(OcslWire.PROP_ANIM_X, base, 3e-8f);
		assertEquals("the base's sub-float32 bits must not survive into the result",
				Float.floatToIntBits(1.0f), Float.floatToIntBits(composed));

		// Two clients holding slightly different doubles for the same node agree bit-for-bit once
		// both narrow -- which is the reason the ordering is pinned at all. Under the other
		// ordering these two diverge, because they straddle the rounding boundary.
		assertEquals(Float.floatToIntBits(composed), Float.floatToIntBits(
				OcslCompose.compose(OcslWire.PROP_ANIM_X, 1.0 + 5e-8, 3e-8f)));
	}

	@Test
	public void everyOwnablePropertyHasARuleAndTheUnownableOnesDoNot() throws Exception {
		// Ties the rule table to the property table: anything the animator can OUT must compose,
		// and z/visible -- reserved but not ownable in v1 -- must not.
		int[] ownable = {
			OcslWire.PROP_ANIM_X, OcslWire.PROP_ANIM_Y, OcslWire.PROP_ANIM_SX,
			OcslWire.PROP_ANIM_SY, OcslWire.PROP_ANIM_ROT2D, OcslWire.PROP_ANIM_ROT3D,
			OcslWire.PROP_ANIM_TINT, OcslWire.PROP_ANIM_TZ, OcslWire.PROP_ANIM_SZ,
		};
		for (int i = 0; i < ownable.length; i++) {
			assertTrue("property " + ownable[i] + " is writable but has no composition rule",
					OcslCompose.ruleFor(ownable[i]) >= 0);
			assertTrue("property " + ownable[i] + " must have a type in the surface table",
					SurfaceTable.propertyType(OcslWire.STAGE_ANIMATOR, ownable[i]) != null);
		}
		assertEquals("z is not ownable in v1, so it composes with nothing",
				-1, OcslCompose.ruleFor(OcslWire.PROP_ANIM_Z));
		assertEquals(-1, OcslCompose.ruleFor(OcslWire.PROP_ANIM_VISIBLE));
	}

	/**
	 * "This surface is shut" and "this surface mandates no output" are now independent facts.
	 *
	 * They were one boolean until the animator needed to be the first surface that is OPEN-shaped in
	 * one sense and shut in the other: ANIM-1/ANIM-2 make its OUT set variable per program and the
	 * sole ownership declaration, so its required set must stay empty <b>forever</b> — an animator
	 * owning only {@code x} writes only {@code x}. While both gates inferred "reserved" from an
	 * empty required set, opening the surface meant clearing that inference, and the obvious way to
	 * clear it — return a property — would have forced every animator to own it.
	 *
	 * <b>WHAT THIS TEST CANNOT DO, stated because its first draft claimed otherwise.</b> It does NOT
	 * fail if someone re-derives {@code isOpen} from {@code requiredProperties}. Verified: replacing
	 * the body with {@code return requiredProperties(stage).length != 0} passes the entire suite.
	 * The two predicates agree on all 256 stage bytes today, so re-coupling is a source-level
	 * regression with <b>no observable consequence</b> — nothing behavioural can catch it, and the
	 * gap is recorded in the frozen artifact's {@code [not-frozen-here]} block rather than papered
	 * over here. They diverge only when the animator opens, which is precisely when the trap would
	 * bite. What this test does pin is the INVARIANT that survives that day: the animator's required
	 * set is empty and must stay so.
	 */
	@Test
	public void beingShutAndMandatingNoOutputAreIndependentFacts() throws Exception {
		// The animator holds both properties at once, which is what made the coupling a trap — and
		// since 2026-08-13 it DEMONSTRATES the separation instead of anticipating it. It is open and
		// mandates nothing, a combination the single coupled predicate could not represent at all:
		// under the old `requiredProperties(stage).length == 0` reading, this surface was
		// unopenable without being given a mandatory property, which is precisely the trap ANIM-2
		// warned about (every animator program would then be forced to own it).
		assertEquals("an animator's OUT set is per-program, so it mandates nothing, forever",
				0, SurfaceTable.requiredProperties(OcslWire.STAGE_ANIMATOR).length);
		assertTrue("and it is OPEN, which the coupled predicate made impossible to express",
				SurfaceTable.isOpen(OcslWire.STAGE_ANIMATOR));
		assertTrue("while genuinely having a published property table",
				SurfaceTable.propertyType(OcslWire.STAGE_ANIMATOR, OcslWire.PROP_ANIM_X) != null);

		// The open surfaces are exactly the pixel family, and each does mandate an output -- which
		// is the coincidence that let the two questions look like one for so long.
		//
		// Asserted through requiredProperties rather than `propertyType(stage, PROP_COLOR)`. That
		// probe was aliasing across namespaces: PROP_COLOR is 0 and so is the animator's `x`, so it
		// read "has any property at id 0" and passed for the animator under a mutation that opened
		// it -- the sibling assert caught that, not this loop. It also probed only id 0, so a future
		// surface whose table starts at id 1 would have read as having none.
		int mandating = 0;
		for (int s = 0; s <= 255; s++) {
			byte stage = (byte) s;
			if (!OcslWire.isKnownStage(stage) || !SurfaceTable.isOpen(stage)) {
				continue;
			}
			int[] required = SurfaceTable.requiredProperties(stage);
			// NO LONGER "every open surface mandates an output" — that inference died with the
			// animator opening, and it was the coincidence this test exists to name. What survives
			// is the typing rule: whatever a surface mandates must have a type at its own stage.
			if (required.length > 0) {
				mandating++;
			}
			for (int i = 0; i < required.length; i++) {
				assertTrue("every required property must have a type at its own stage",
						SurfaceTable.propertyType(stage, required[i]) != null);
			}
		}

		// The pixel family still mandates COLOR, so the two predicates genuinely differ rather than
		// the mandating set having emptied out underneath this test.
		assertTrue("some open surface must still mandate an output, or the distinction is vacuous"
				+ " from the other side", mandating >= 4);

		// And the gate asks isOpen rather than inferring from the mandate: the animator now BUILDS
		// while mandating nothing, which is the case the old coupled inference could not express in
		// either direction. A still-shut surface keeps the refusal honest.
		OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		try {
			OcslBuilder.forStage(OcslWire.STAGE_COMPUTE);
			fail("compute is still shut and the builder must say so");
		} catch (OcslBuilder.BuildException e) {
			assertTrue("the refusal should name openness, not a missing table, got: "
					+ e.getMessage(), e.getMessage().contains("not open"));
		}
	}

	// ---------------------------------------------------- ANIM-7: the absolute output form

	@Test
	public void theAbsoluteFormReplacesTheBaseWhereTheRelativeFormAddsToIt() throws Exception {
		// DESIGN's pinned example, both readings side by side. The relative reading IS the trap the
		// decision exists to name: an author who reads their own x and adds to it emits 3.5 against
		// a base of 3.0 and is drawn at 6.5.
		assertEquals("relative composes over the base", 6.5f,
				OcslCompose.compose(OcslWire.PROP_ANIM_X, 3.0, 3.5f, false), 0f);
		float absolute = OcslCompose.compose(OcslWire.PROP_ANIM_X, 3.0, 3.5f, true);
		assertEquals("absolute IS the displayed value", 3.5f, absolute, 0f);
		assertTrue("6.5 is the relative answer and must not be reachable absolutely",
				absolute != 6.5f);

		// Pass-through, the purest statement of the defect: `OUT x, anim.x` displays 2*base.
		assertEquals("relative pass-through doubles the base", 6.0f,
				OcslCompose.compose(OcslWire.PROP_ANIM_X, 3.0, 3.0f, false), 0f);
		assertEquals("absolute pass-through changes nothing", 3.0f,
				OcslCompose.compose(OcslWire.PROP_ANIM_X, 3.0, 3.0f, true), 0f);

		// The base is IRRELEVANT to an accepted absolute write, shown on a base LARGE BUT FINITE IN
		// FLOAT32. The first version of this line used 1.0e300 and claimed a base "this hostile
		// would derail any implementation that still touched it" -- backwards. 1.0e300 narrows to
		// +Infinity, so OcslIngress.base substitutes the ADD identity 0.0f and the RELATIVE form
		// returns add(0, 2.5) = 2.5 as well: at that base the two forms agree, and the assertion
		// passed under the exact rule it was written to exclude. A hostile base is the one a
		// relative implementation DISCARDS, so it is the least discriminating input available.
		assertEquals(2.5f, OcslCompose.compose(OcslWire.PROP_ANIM_X, 1.0e30, 2.5f, true), 0f);
		assertEquals("and the relative form is derailed by it, which is what makes the line bite",
				1.0e30f, OcslCompose.compose(OcslWire.PROP_ANIM_X, 1.0e30, 2.5f, false), 0f);
	}

	@Test
	public void theAbsoluteFormReplacesTheBaseWhereTheRelativeFormMultipliesByIt() throws Exception {
		// Scale is the probe (ANIM-3), and under multiply the relative error is a FACTOR: the same
		// output that means "1.65" absolutely means "+65% of a 1.5 base" relatively.
		assertEquals("relative multiplies", 2.475f,
				OcslCompose.compose(OcslWire.PROP_ANIM_SX, 1.5, 1.65f, false), 1e-6f);
		float absolute = OcslCompose.compose(OcslWire.PROP_ANIM_SX, 1.5, 1.65f, true);
		assertEquals("absolute IS the scale", 1.65f, absolute, 0f);
		assertTrue("2.475 is the relative answer", Math.abs(absolute - 2.475f) > 1e-3f);

		// sx_srv squared. The base is 1.5 and not 1.0 because the defect is INVISIBLE at the
		// multiplicative identity, exactly as the additive one is invisible at 0.
		assertEquals(2.25f, OcslCompose.compose(OcslWire.PROP_ANIM_SX, 1.5, 1.5f, false), 1e-6f);
		assertEquals(1.5f, OcslCompose.compose(OcslWire.PROP_ANIM_SX, 1.5, 1.5f, true), 0f);
	}

	@Test
	public void tintComposesIdenticallyInBothFormsBecauseItAlreadyReplaced() throws Exception {
		// The one property the double-apply defect cannot reach: REPLACE means the base never
		// enters an accepted result, so the two forms coincide. This is the arithmetic behind
		// leaving anim.tint readable to a program that owns tint.
		float relative = OcslCompose.compose(OcslWire.PROP_ANIM_TINT, 0.25, 0.75f, false);
		float absolute = OcslCompose.compose(OcslWire.PROP_ANIM_TINT, 0.25, 0.75f, true);
		assertEquals(0.75f, relative, 0f);
		assertEquals("the two forms coincide for a REPLACE property", relative, absolute, 0f);
		assertTrue("and neither is the base", absolute != 0.25f);
	}

	@Test
	public void aRejectedAbsoluteWriteFallsBackToTheBaseAndNotToTheIdentity() throws Exception {
		// The two forms AGREE on this answer, and the agreement is the design -- "falling back to
		// the base IS composing with the identity". They reach it by different routes, though, and
		// the wrong route is detectable: an absolute write that fell back through
		// OcslWriteBoundary.accepted would display the IDENTITY, teleporting the node to x=0 or
		// snapping it to unit scale on any frame with one bad output.
		float x = OcslCompose.compose(OcslWire.PROP_ANIM_X, 3.0, Float.NaN, true);
		assertEquals("a rejected absolute write displays the server base", 3.0f, x, 0f);
		assertTrue("0 is the ADD identity, not the base", x != 0.0f);
		assertEquals(3.0f, OcslCompose.compose(OcslWire.PROP_ANIM_X, 3.0, Float.NaN, false), 0f);

		float sx = OcslCompose.compose(OcslWire.PROP_ANIM_SX, 1.5, Float.POSITIVE_INFINITY, true);
		assertEquals(1.5f, sx, 0f);
		assertTrue("1 is the MULTIPLY identity, not the base", sx != 1.0f);
		assertEquals(1.5f,
				OcslCompose.compose(OcslWire.PROP_ANIM_SX, 1.5, Float.POSITIVE_INFINITY, false), 0f);

		// Both sides unusable: the base still goes through ingress, so the identity is what is left.
		// That is the ingress rule doing its job, not a second fallback invented for this path.
		assertEquals(0.0f, OcslCompose.compose(OcslWire.PROP_ANIM_X, Double.NaN, Float.NaN, true), 0f);
		assertEquals(1.0f, OcslCompose.compose(OcslWire.PROP_ANIM_SX, Double.NaN, Float.NaN, true), 0f);
	}

	@Test
	public void bothFormsRefuseTheSamePropertiesForTheSameReasons() throws Exception {
		// The mirror check. Two adjacent switches only guard each other if a missing arm FAILS
		// something -- one-sided fixes are this codebase's most-repeated defect.
		for (int i = 0; i < 2; i++) {
			boolean absolute = i == 1;
			try {
				OcslCompose.compose(OcslWire.PROP_ANIM_ROT3D, 0.0, 1.0f, absolute);
				fail("rot3d is a vec4 and must not compose as a scalar (absolute=" + absolute + ")");
			} catch (IllegalArgumentException expected) {
				assertTrue(expected.getMessage(), expected.getMessage().contains("composeRot3d"));
			}
			try {
				OcslCompose.compose(OcslWire.PROP_ANIM_Z, 0.0, 1.0f, absolute);
				fail("z is not ownable in v1 (absolute=" + absolute + ")");
			} catch (IllegalArgumentException expected) {
				assertTrue(expected.getMessage(), expected.getMessage().contains("not composable"));
			}
		}
	}

	@Test
	public void theAbsoluteQuaternionReplacesTheBaseAndIsStillNormalized() throws Exception {
		final float A = (float) (Math.sqrt(2.0) / 2.0);
		double[] server = { A, 0.0, 0.0, A };      // 90 degrees about x
		float[] animator = { 0.0f, A, 0.0f, A };   // 90 degrees about y
		float[] out = new float[4];

		OcslCompose.composeRot3d(server, animator, out, false);
		assertArrayEquals("the relative form is the Hamilton product",
				new float[] { 0.5f, 0.5f, 0.5f, 0.5f }, out, 1e-6f);

		OcslCompose.composeRot3d(server, animator, out, true);
		assertArrayEquals("the absolute form is the animator's own rotation",
				new float[] { 0.0f, A, 0.0f, A }, out, 1e-6f);
		assertTrue("(0.5, 0.5, 0.5, 0.5) is the RELATIVE answer", Math.abs(out[0] - 0.5f) > 1e-3f);

		// STILL NORMALIZED. That is a property of the runtime rather than of the Hamilton product,
		// so it has to survive a form that performs no product at all: a non-unit quaternion would
		// otherwise scale the node as a side effect of rotating it.
		OcslCompose.composeRot3d(server, new float[] { 0.0f, 2.0f, 0.0f, 0.0f }, out, true);
		assertArrayEquals(new float[] { 0.0f, 1.0f, 0.0f, 0.0f }, out, 1e-6f);
	}

	@Test
	public void aRejectedAbsoluteQuaternionFallsBackToTheServerBase() throws Exception {
		final float A = (float) (Math.sqrt(2.0) / 2.0);
		double[] server = { A, 0.0, 0.0, A };
		float[] out = new float[4];

		OcslCompose.composeRot3d(server, new float[] { 0.0f, Float.NaN, 0.0f, 1.0f }, out, true);
		assertArrayEquals("the base, not the identity",
				new float[] { A, 0.0f, 0.0f, A }, out, 1e-6f);
		assertTrue("(0,0,0,1) would silently unrotate a node the server had rotated",
				out[0] != 0.0f);

		// Both sides unusable -> the identity, the same two-step the scalar path takes.
		OcslCompose.composeRot3d(new double[] { Double.NaN, 0.0, 0.0, 1.0 },
				new float[] { 0.0f, Float.NaN, 0.0f, 1.0f }, out, true);
		assertArrayEquals(new float[] { 0.0f, 0.0f, 0.0f, 1.0f }, out, 0f);
	}

	@Test
	public void theAbsoluteBaseFallbackNarrowsToFloat32BeforeNormalizing() throws Exception {
		// THE FROZEN RULE, and the absolute path had to be made to obey it: "The base is narrowed to
		// float32 BEFORE composing, so the result is a pure function of what the VM saw and two
		// clients cannot disagree on it" ([composition], surface-tables.txt). The relative path
		// narrows each component on its way into the Hamilton product; the absolute fallback runs no
		// product, so it has to narrow explicitly or the answer depends on bits float32 cannot hold.
		//
		// THE DISCRIMINATING INPUT is a base that UNDERFLOWS float32. 1e-50 is a perfectly finite
		// double, so it passes the ingress check and reaches the normalizer -- where, as doubles, it
		// normalizes to the unit quaternion (1,0,0,0), a 180-degree rotation. Narrowed first it is
		// 0.0f, the norm is zero, and the degenerate guard returns the identity. Not a rounding
		// difference: two clients would draw the node facing opposite ways.
		assertEquals("the premise: this base underflows float32", 0.0f, (float) 1.0e-50, 0f);

		float[] rejected = { 0.0f, Float.NaN, 0.0f, 1.0f };
		float[] out = new float[4];
		OcslCompose.composeRot3d(new double[] { 1.0e-50, 0.0, 0.0, 0.0 }, rejected, out, true);
		assertArrayEquals("a base that vanishes under narrowing is degenerate, so: the identity",
				new float[] { 0.0f, 0.0f, 0.0f, 1.0f }, out, 0f);
		assertTrue("(1,0,0,0) is what normalizing the RAW doubles produces -- a 180-degree flip",
				out[0] != 1.0f);

		// And the two forms must agree on it, which is the cross-client property stated as a
		// same-process one: the relative path narrows, so it already answers this way.
		float[] relative = new float[4];
		OcslCompose.composeRot3d(new double[] { 1.0e-50, 0.0, 0.0, 0.0 },
				new float[] { 0.0f, 0.0f, 0.0f, 1.0f }, relative, false);
		assertArrayEquals("the two paths must not disagree about the same base",
				relative, out, 0f);
	}

	private static float length(float[] q) {
		double s = 0;
		for (int i = 0; i < q.length; i++) {
			s += (double) q[i] * q[i];
		}
		return (float) Math.sqrt(s);
	}
}
