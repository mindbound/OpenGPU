package opengpu.v2.ocsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * ANIM-9(b) — the ingress boundary, and that it binds every stage.
 *
 * Every vector carries a second assertion naming the WRONG answer it excludes, which here is usually
 * a specific number: the superseded rule's "previous value retained", or the zero A4's catch-all
 * produces from a non-finite operand. A vector that only asserts the right answer would pass against
 * both implementations on most inputs, and this file exists precisely because two rules that agree
 * at rest disagree in the field.
 */
public strictfp class OcslIngressTest {

	private static final int U0 = SurfaceTable.UNIFORM_BASE;
	private static final int W = SurfaceTable.WORKING_BASE;

	/** One uniform, splatted to vec4, written to COLOR. Every open stage requires COLOR vec4. */
	private static OcslVm oneUniformProgram(byte stage) throws Exception {
		List<String> names = new ArrayList<String>();
		names.add("u");
		IrProgram p = new IrProgram(stage, new float[0][], Arrays.asList(
				new IrOp(OcslWire.OP_SPLAT, W, U0, 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), names, W + 1);
		return new OcslVm(IrValidator.validate(p));
	}

	private static float readColor(OcslVm vm) throws Exception {
		vm.run();
		float[] out = new float[4];
		vm.output(OcslWire.PROP_COLOR, out);
		return out[0];
	}

	private static byte[] openStages() {
		int n = 0;
		for (int s = 0; s <= 255; s++) {
			if (OcslWire.isKnownStage((byte) s) && SurfaceTable.isOpen((byte) s)) {
				n++;
			}
		}
		byte[] open = new byte[n];
		int at = 0;
		for (int s = 0; s <= 255 && at < n; s++) {
			if (OcslWire.isKnownStage((byte) s) && SurfaceTable.isOpen((byte) s)) {
				open[at++] = (byte) s;
			}
		}
		return open;
	}

	// ------------------------------------------------------------------ the rule carries no stage

	@Test
	public void theIngressRuleCarriesNoStageInAnySignature() throws Exception {
		// This IS the amendment, in the one form that holds. "Lift the paragraph into the shared
		// uniform section" is unenforceable -- three obligations of exactly that shape have been
		// crossed silently in this project. A signature that cannot name a stage can only be
		// re-scoped by adding a parameter nothing would pass, and that shows up in a diff.
		int publicMethods = 0;
		for (Method m : OcslIngress.class.getDeclaredMethods()) {
			if (!Modifier.isPublic(m.getModifiers())) {
				continue;
			}
			publicMethods++;
			for (Class<?> t : m.getParameterTypes()) {
				assertTrue(m.getName() + " takes a byte, and byte is how every stage in this"
						+ " codebase is spelled; the ingress rule binds all of them",
						t != byte.class);
			}
		}
		// Without this the test passes on an empty set -- a rename or a deletion would make the
		// whole assertion vacuous while still reporting green.
		assertTrue("expected the four ingress spellings; found " + publicMethods,
				publicMethods >= 4);
	}

	@Test
	public void refusingAndSubstitutingAreDifferentRemediesOnPurpose() throws Exception {
		// Authored content HAS an error path, so the pool refuses; a frame slot must hold something
		// before the program runs, so a binding substitutes. Pinned together because a future tidy
		// that "unifies" them would silently make one of the two wrong.
		assertTrue("the pool refuses a non-finite constant", !OcslIngress.accepts(Float.NaN));
		assertEquals("a binding substitutes rather than refusing", 0.0f,
				OcslIngress.bound(Float.NaN), 0f);
		assertEquals(0.0f, OcslIngress.bound(Float.POSITIVE_INFINITY), 0f);
		assertEquals("and a finite value passes untouched", 7.5f, OcslIngress.bound(7.5f), 0f);
		assertTrue("a shared remedy would make one of these two answers wrong",
				OcslIngress.accepts(7.5f));
	}

	// ------------------------------------------------------------------ every open stage, the same

	@Test
	public void aNonFiniteBindingReadsZeroAtEveryOpenStageNotOnlyThePixelOnes() throws Exception {
		byte[] open = openStages();
		// The rule was written under a PIXEL-STAGE heading. Sweeping one stage would reproduce that
		// scope exactly while looking like a conformance vector.
		assertTrue("fewer than three open stages makes 'every stage' vacuous", open.length >= 3);

		for (int i = 0; i < open.length; i++) {
			OcslVm vm = oneUniformProgram(open[i]);
			vm.set(U0, Float.NaN);
			assertEquals("stage " + open[i] + ": a NaN binding reads 0", 0.0f, readColor(vm), 0f);

			vm.set(U0, Float.POSITIVE_INFINITY);
			assertEquals("stage " + open[i] + ": an Inf binding reads 0", 0.0f, readColor(vm), 0f);

			vm.set(U0, 7.5f);
			assertEquals("stage " + open[i] + ": a finite binding is untouched", 7.5f,
					readColor(vm), 0f);
		}
	}

	@Test
	public void aRejectedBindingDoesNotRetainThePreviousValue() throws Exception {
		// THE SUPERSEDED RULE, given a number. "Previous value retained" is safe at the replicated
		// uniform table and unsafe at the executor: a VM with a binding history and one created on
		// the failing frame would display different values indefinitely, which is exactly the
		// cross-frame state ANIM-9(a) removed from the write boundary.
		OcslVm vm = oneUniformProgram(OcslWire.STAGE_PIXEL_MATERIAL);
		vm.set(U0, 5.0f);
		assertEquals(5.0f, readColor(vm), 0f);

		vm.set(U0, Float.NaN);
		float after = readColor(vm);
		assertEquals("the executor substitutes zero", 0.0f, after, 0f);
		assertTrue("retaining the previous value gives 5.0, and that is what this excludes",
				after != 5.0f);
	}

	// ------------------------------------------------------------------ the animator's server base

	@Test
	public void aNonFiniteServerBaseFallsBackToTheIdentityNotToZero() throws Exception {
		// The mirror ANIM-9(a) left open. A non-finite base reaches an op as an OPERAND, so A4's
		// catch-all zeroes the whole result -- and for a multiplicative property that collapses the
		// node to nothing, the very failure identityFor was introduced to prevent on the other side.
		float scale = OcslCompose.compose(OcslWire.PROP_ANIM_SX, Double.NaN, 2.0f);
		assertEquals("a bad base leaves the animator's scale standing", 2.0f, scale, 0f);
		assertTrue("mul(NaN, 2) is 0 under the catch-all, and an invisible node is what this"
				+ " excludes", scale != 0.0f);

		float x = OcslCompose.compose(OcslWire.PROP_ANIM_X, Double.POSITIVE_INFINITY, 3.0f);
		assertEquals("and an additive property keeps its offset", 3.0f, x, 0f);
		assertTrue("add(Inf, 3) is 0 under the catch-all", x != 0.0f);

		// A finite base is untouched, so the substitution is not just always-identity.
		assertEquals(12.5f, OcslCompose.compose(OcslWire.PROP_ANIM_X, 12.5, 0.0f), 0f);
		assertEquals(5.0f, OcslCompose.compose(OcslWire.PROP_ANIM_SX, 2.5, 2.0f), 0f);
	}

	@Test
	public void theBaseIsNarrowedBeforeItIsTestedNotAfter() throws Exception {
		// 1e300 is a PERFECTLY FINITE double and an infinity in float32. Testing the double and then
		// narrowing accepts it, hands Inf to mul, and the catch-all collapses the node -- the same
		// visible failure as a NaN base, reached through a value that passes a finiteness test.
		// This is the one input where the two orderings disagree, so it is the whole vector.
		float scale = OcslCompose.compose(OcslWire.PROP_ANIM_SX, 1.0e300, 2.0f);
		assertEquals("narrow first: 1e300 is not representable, so the identity stands in",
				2.0f, scale, 0f);
		assertTrue("test-then-narrow gives mul(Inf, 2) = 0, and that is what this excludes",
				scale != 0.0f);
		assertTrue("1e300 is finite as a double, which is why the ordering is the pin",
				!Double.isInfinite(1.0e300) && Float.isInfinite((float) 1.0e300));
	}

	@Test
	public void aNonFiniteTintBaseIsWhiteRatherThanBlack() throws Exception {
		// identityFor throws for tint and is right to -- tint REPLACES, so no OUTPUT leaves the base
		// alone. The BASE side is a different question with an answer: the renderer multiplies a
		// node's tint by its parent group's, so the value that leaves appearance alone is 1.
		float tint = OcslWriteBoundary.acceptedTint(Double.NaN, Float.NaN);
		assertEquals("a rejected write onto a bad base shows white", 1.0f, tint, 0f);
		assertTrue("a bare (float) cast passes the NaN straight through", OcslMath.finite(tint));
		assertTrue("substituting 0 fades the node to black, the same class of failure as a"
				+ " collapsed scale", tint != 0.0f);

		// A good base is still what a rejected write falls back to.
		assertEquals(0.25f, OcslWriteBoundary.acceptedTint(0.25, Float.NaN), 0f);
		assertEquals("and an accepted write wins over the base", 0.75f,
				OcslWriteBoundary.acceptedTint(0.25, 0.75f), 0f);
	}

	@Test
	public void composeAloneHonoursTheWriteBoundaryWithoutAPreApplyingCaller() throws Exception {
		// compose() used to require its caller to run OcslWriteBoundary.accepted() first, and handed
		// mul(2.5, NaN) = 0 -- an invisible node -- to anyone who did not. There are no callers yet,
		// so nobody had ever met that obligation. Idempotent on a finite value, so a caller who does
		// pre-apply is unaffected.
		float scale = OcslCompose.compose(OcslWire.PROP_ANIM_SX, 2.5, Float.NaN);
		assertEquals("a rejected scale displays the server base", 2.5f, scale, 0f);
		assertTrue("the caller-must-remember version gives 0 and collapses the node", scale != 0.0f);

		float x = OcslCompose.compose(OcslWire.PROP_ANIM_X, 4.0, Float.POSITIVE_INFINITY);
		assertEquals("and so does a rejected offset", 4.0f, x, 0f);
		assertTrue("add(4, Inf) is 0 under the catch-all", x != 0.0f);

		float tint = OcslCompose.compose(OcslWire.PROP_ANIM_TINT, 0.25, Float.NaN);
		assertEquals("a rejected tint displays the base, not black", 0.25f, tint, 0f);
		assertTrue("san(NaN) = 0 is black, and that is what this excludes", tint != 0.0f);

		// Idempotence, stated as a vector rather than as a claim.
		assertEquals(2.5f, OcslCompose.compose(OcslWire.PROP_ANIM_SX, 2.5,
				OcslWriteBoundary.accepted(OcslWire.PROP_ANIM_SX, Float.NaN)), 0f);
		assertEquals(6.0f, OcslCompose.compose(OcslWire.PROP_ANIM_SX, 2.0,
				OcslWriteBoundary.accepted(OcslWire.PROP_ANIM_SX, 3.0f)), 0f);
	}

	// ------------------------------------------------------------------ rot3d, both sides

	/** sin/cos of 45 degrees — a quarter turn about Z, as a unit quaternion. */
	private static final float H = 0.70710678f;

	@Test
	public void aNonFiniteAnimatorQuaternionKeepsTheServerRotation() throws Exception {
		// ANIM-9(a) SAID "falls back to the server base" AND ROT3D DID NOT. The norm guard caught the
		// NaN and fell out to the identity ROTATION, discarding q_srv as well -- so a node with a
		// server rotation snapped upright the moment its animator overflowed.
		double[] server = { 0.0, 0.0, H, H };
		float[] animator = { Float.NaN, 0.0f, 0.0f, 1.0f };
		float[] out = new float[4];
		OcslCompose.composeRot3d(server, animator, out);

		assertEquals("the server's quarter turn survives", H, out[2], 1.0e-6f);
		assertEquals(H, out[3], 1.0e-6f);
		assertTrue("the identity quaternion has z = 0, and that is what this excludes",
				Math.abs(out[2]) > 0.5f);
	}

	@Test
	public void aNonFiniteServerQuaternionKeepsTheAnimatorRotation() throws Exception {
		// The mirror. Substituting the identity works from either side despite the product being
		// non-commutative, which is why one constant covers both.
		double[] server = { 0.0, 0.0, Double.NaN, 1.0 };
		float[] animator = { 0.0f, 0.0f, H, H };
		float[] out = new float[4];
		OcslCompose.composeRot3d(server, animator, out);

		assertEquals("the animator's quarter turn survives", H, out[2], 1.0e-6f);
		assertEquals(H, out[3], 1.0e-6f);
		assertTrue("falling out to the identity gives z = 0", Math.abs(out[2]) > 0.5f);
	}

	@Test
	public void aQuaternionIsSubstitutedWholeOrNotAtAll() throws Exception {
		// Repairing the bad component alone would leave (0, 0, NaN->0, 1) * q -- a rotation nobody
		// asked for, at an arbitrary angle, rather than a neutral one. Here the server's OTHER
		// components are non-zero, so a per-component repair is observably different from an
		// identity substitution.
		double[] server = { H, 0.0, Double.NaN, H };
		float[] animator = { 0.0f, 0.0f, 0.0f, 1.0f };
		float[] out = new float[4];
		OcslCompose.composeRot3d(server, animator, out);

		assertEquals("all four components go, so the result is the identity", 0.0f, out[0], 0f);
		assertEquals(1.0f, out[3], 1.0e-6f);
		assertTrue("a per-component repair would keep x = 0.707, and that is what this excludes",
				Math.abs(out[0]) < 0.5f);

		// And a fully finite server quaternion is untouched by any of this.
		double[] good = { H, 0.0, 0.0, H };
		OcslCompose.composeRot3d(good, animator, out);
		assertEquals(H, out[0], 1.0e-6f);
	}

	@Test
	public void theInlineIdentityInComposeRot3dIsTheStatedOne() throws Exception {
		// composeRot3d spells (0,0,0,1) inline in three places to stay allocation-free on the render
		// thread -- it runs once per animated node per frame. That duplicates a pinned constant, so it
		// gets a test rather than a comment.
		//
		// THE FIRST DRAFT OF THIS TEST DID NOT WORK AND THE MUTATION SWEEP SAID SO. It fed a bad
		// quaternion to BOTH sides, which under a drifted constant makes the product degenerate --
		// and the zero-length norm guard then returns the identity, the right answer for the wrong
		// reason. It passed against the mutant while a differently-named test caught it.
		//
		// The form that works compares the two SPELLINGS on inputs where the guard cannot fire: run
		// the product once with identityRot3d() passed explicitly and once with a value that forces
		// the inline substitution, and require them to agree. Done for each side separately, because
		// the two inline copies can drift independently.
		float[] stated = OcslCompose.identityRot3d();
		float[] viaStated = new float[4];
		float[] viaSubstitution = new float[4];

		// Animator side. The server carries a real quarter turn, so the product is non-degenerate.
		double[] server = { 0.0, 0.0, H, H };
		OcslCompose.composeRot3d(server, stated, viaStated);
		OcslCompose.composeRot3d(server, new float[] { Float.NaN, 0.0f, 0.0f, 1.0f }, viaSubstitution);
		for (int i = 0; i < 4; i++) {
			assertEquals("animator-side component " + i + ": the inline identity must equal"
					+ " identityRot3d()", viaStated[i], viaSubstitution[i], 1.0e-7f);
		}
		assertEquals("and the shared answer is the server's own rotation, not the identity",
				H, viaStated[2], 1.0e-6f);

		// Server side, same shape.
		double[] statedAsBase = { stated[0], stated[1], stated[2], stated[3] };
		float[] animator = { 0.0f, 0.0f, H, H };
		OcslCompose.composeRot3d(statedAsBase, animator, viaStated);
		OcslCompose.composeRot3d(new double[] { Double.NaN, 0.0, 0.0, 1.0 }, animator, viaSubstitution);
		for (int i = 0; i < 4; i++) {
			assertEquals("server-side component " + i + ": the inline identity must equal"
					+ " identityRot3d()", viaStated[i], viaSubstitution[i], 1.0e-7f);
		}
		assertEquals("and the shared answer is the animator's rotation", H, viaStated[2], 1.0e-6f);
		assertTrue("a degenerate product would give z = 0 and this vector would prove nothing",
				Math.abs(viaSubstitution[2]) > 0.5f);
	}

	// ------------------------------------------------------------------ the obligation, enforced

	@Test
	public void openingTheAnimatorSurfaceOwesARunningIngressVector() throws Exception {
		// The amendment asks for "the conformance vector at the animator surface". The vectors above
		// run a PROGRAM at every open stage, and the animator is shut -- no builder, no validator, no
		// decode -- so its ingress is pinned here only through compose(), which is the part of that
		// surface that exists. This assertion fails the moment the surface opens, which is the only
		// form of "come back and finish this" this project has found to hold.
		assertTrue("The animator surface is now OPEN. Add STAGE_ANIMATOR to the sweep in"
				+ " aNonFiniteBindingReadsZeroAtEveryOpenStageNotOnlyThePixelOnes (it derives its"
				+ " stage list from isOpen, so it will pick the animator up by itself -- but its"
				+ " program writes COLOR, which the animator has no property for) and then delete"
				+ " this test.", !SurfaceTable.isOpen(OcslWire.STAGE_ANIMATOR));
	}
}
