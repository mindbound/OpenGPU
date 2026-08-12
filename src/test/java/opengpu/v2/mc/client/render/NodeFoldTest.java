package opengpu.v2.mc.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import opengpu.v2.ocsl.OcslCompose;
import opengpu.v2.ocsl.OcslWire;
import opengpu.v2.scene.SceneNode;

/**
 * ANIM-10's conformance vectors — the parent-to-child fold.
 *
 * This fold decides where every child of every group is drawn and it was pinned by NOTHING until
 * now: {@code Canvas2dRenderer} declares itself untestable for want of a GL context, which was true
 * of the class and never of the arithmetic.
 *
 * Every vector names the wrong answers it excludes, as NUMBERS. The amendment's own wording ("an
 * animated group with a non-unit server scale and a child at a non-zero offset") describes a setup,
 * not a discriminator; the numbers are what make it one. Note in particular that every candidate
 * reading coincides on an unrotated, unit-scaled group — which is exactly why an in-game look at a
 * plain group proves nothing here.
 */
public strictfp class NodeFoldTest {

	private static double[] trs(double x, double y, double rot, double sx, double sy) {
		double[] t = new double[NodeFold.TRS_WIDTH];
		t[NodeFold.TRS_X] = x;
		t[NodeFold.TRS_Y] = y;
		t[NodeFold.TRS_ROT] = rot;
		t[NodeFold.TRS_SX] = sx;
		t[NodeFold.TRS_SY] = sy;
		return t;
	}

	/** Where a child's local origin lands in scene space. */
	private static double[] origin(double[] parent, double[] child) {
		NodeFold.Affine m = new NodeFold.Affine();
		NodeFold.foldTransform(parent, child, m);
		return new double[] { m.tx(0, 0), m.ty(0, 0) };
	}

	// ------------------------------------------------------------------ V1: inherited transform

	@Test
	public void anAnimatedGroupsRotationCarriesItsChildThroughTheGroupsScale() throws Exception {
		// V1. Group server sx=2, sy=1 (NON-UNIFORM on purpose); group animator adds rot2d = pi/2.
		// Child at (10, 0), no child animator. Composed group rot = 0 + pi/2 (RULE_ADD).
		double groupRot = OcslCompose.compose(OcslWire.PROP_ANIM_ROT2D, 0.0, (float) (Math.PI / 2));
		double[] group = trs(0, 0, groupRot, 2, 1);
		double[] child = trs(10, 0, 0, 1, 1);

		double[] at = origin(group, child);
		// Correct: R(pi/2) . S(2,1) . (10,0) = R(pi/2) . (20,0) = (0, 20).
		//
		// THE TOLERANCE IS 1e-5 AND THAT IS A FINDING, not slack. The animator's output is float32,
		// so the quarter turn that reaches this double-precision matrix is 1.5707964f, which differs
		// from pi/2 by ~4.4e-8; cos of it is -4.4e-8 rather than 0, and at the 20-unit radius the
		// group's scale produces that lands x at -8.74e-7 instead of 0. A first draft asserted 1e-9
		// and failed. Real: an animator-driven rotation cannot be more accurate than float32, and
		// any future golden vector over animator angles has to be written to that width.
		assertEquals("child x", 0.0, at[0], 1e-5);
		assertEquals("child y", 20.0, at[1], 1e-5);
		assertTrue("and the float32 residue is far below the 10-vs-20 discriminator below",
				Math.abs(at[0]) < 1e-4);

		// WRONG 1 -- the animator applied as a separate matrix AFTER the parent's server TRS,
		// i.e. S(2,1) . R(pi/2) . (10,0) = (0, 10). A factor of two on y.
		assertTrue("appending the animator as its own matrix gives y = 10", Math.abs(at[1] - 10.0) > 1e-6);

		// WRONG 2 -- children inherit the parent's SERVER BASE rather than its display, which is the
		// reading ANIM-10 clause (2) exists to kill: R(0) . S(2,1) . (10,0) = (20, 0). Wrong axis.
		double[] baseOnly = origin(trs(0, 0, 0, 2, 1), child);
		assertEquals("the server-base-only reading really does give (20, 0)", 20.0, baseOnly[0], 1e-9);
		assertTrue("and that is not what the fold produces", Math.abs(at[0] - 20.0) > 1e-6);

		// The non-uniform scale is load-bearing: with sx == sy the scale commutes with the rotation
		// and WRONG 1 coincides with the correct answer, so the vector would prove nothing.
		double[] uniform = origin(trs(0, 0, Math.PI / 2, 2, 2), child);
		double[] uniformWrong = new double[] { -0.0, 20.0 };
		assertEquals("under a uniform scale the two readings agree, which is why V1 uses 2x1",
				uniformWrong[1], uniform[1], 1e-9);
	}

	// ------------------------------------------------------------------ V3: the mirror rule

	@Test
	public void aChildComposesOntoItsOwnBaseBeforeInheritingTheParentsScale() throws Exception {
		// V3. Group server sx=2; group animator emits sx=1.5 -> composed 3.0 (RULE_MULTIPLY).
		// Child server x=10; child animator emits x=+4 -> composed 14 (RULE_ADD).
		double groupSx = OcslCompose.compose(OcslWire.PROP_ANIM_SX, 2.0, 1.5f);
		double childX = OcslCompose.compose(OcslWire.PROP_ANIM_X, 10.0, 4.0f);
		assertEquals("scale composes multiplicatively", 3.0, groupSx, 1e-9);
		assertEquals("position composes additively", 14.0, childX, 1e-9);

		double[] at = origin(trs(0, 0, 0, groupSx, 1), trs(childX, 0, 0, 1, 1));
		// Correct: S(3,1) . (14, 0) = 42.
		assertEquals("child origin x", 42.0, at[0], 1e-9);

		// WRONG 1 -- the child's animator delta applied in SCENE space, outside the parent, i.e.
		// composing after inheriting: S(3,1) . (10,0) + (4,0) = 34. The exact inversion clause (3)
		// forbids.
		assertTrue("applying the child's delta outside the parent gives 34", Math.abs(at[0] - 34.0) > 1e-6);

		// WRONG 2 -- the child inherits the parent's SERVER scale only: S(2,1) . (14,0) = 28.
		double[] serverScaleOnly = origin(trs(0, 0, 0, 2, 1), trs(childX, 0, 0, 1, 1));
		assertEquals("the server-scale-only reading really does give 28", 28.0, serverScaleOnly[0], 1e-9);
		assertTrue("and that is not what the fold produces", Math.abs(at[0] - 28.0) > 1e-6);

		// 42 / 34 / 28 are three distinct integers -- no tolerance argument decides between them.
		// A reading that MIXED the two composition rules (add where multiply was meant) also falls
		// out here rather than needing a fourth vector: 2*1.5 = 3 but 2+1.5 = 3.5 gives 49.
		assertTrue("a rule-mixing reading gives 49", Math.abs(at[0] - 49.0) > 1e-6);
	}

	// ------------------------------------------------------------------ V2: inherited tint

	@Test
	public void anAnimatorsTintReplacesTheNodesOwnFactorAndTheGroupStillMultiplies() throws Exception {
		// V2a. Group server tint 0x80FFFFFF (alpha 128/255); no group animator. Child server tint
		// white; child animator emits alpha 0.5, which REPLACES the child's own factor.
		double[] out = new double[4];
		int childDisplayed = 0x80FFFFFF; // the animator's 0.5 alpha, packed as the child's own tint
		NodeFold.foldTint(childDisplayed, 0x80FFFFFF, out);

		double half = 128 / 255.0;
		assertEquals("alpha is the product of the two factors", half * half, out[NodeFold.TINT_A], 1e-12);
		assertEquals("which is 0.25196...", 0.2519646289888504, out[NodeFold.TINT_A], 1e-12);

		// WRONG -- the animator replaces the COMPOSED result instead of the node's own factor: the
		// group fade disappears and the alpha is 0.5019..., twice as bright. That node would be the
		// one thing still lit inside a faded group.
		assertTrue("replacing the composed result gives 0.5019", Math.abs(out[NodeFold.TINT_A] - half) > 1e-6);

		// AN OPEN QUESTION, SURFACED BY THE ARITHMETIC RATHER THAN ASSUMED AWAY. ANIM-10's own text
		// says an animated child under a half-alpha group must render at "pulse * 0.5" -- and 0.5 is
		// NOT REPRESENTABLE as an 8-bit tint channel. The neighbours are 127/255 = 0.498039 and
		// 128/255 = 0.501961. So the amendment's number is reachable only if a composed tint reaches
		// the renderer as four floats; through the packed int it is 0.2519646, not 0.2509804. The
		// difference is 0.4%, which is a step in the 8-bit destination.
		assertTrue("the packed path cannot produce the amendment's 0.2509804",
				Math.abs(out[NodeFold.TINT_A] - 0.25098039215686274) > 1e-6);
		assertTrue("because no byte divided by 255 is 0.5",
				Math.abs(127 / 255.0 - 0.5) > 1e-6 && Math.abs(128 / 255.0 - 0.5) > 1e-6);
	}

	@Test
	public void theTintFoldCannotSeeARawFieldToShortCircuitOn() throws Exception {
		// V2b, RESTATED -- and the restatement is the finding.
		//
		// It was first written as a second NUMERIC vector: group server tint white, group animator
		// fades it, child carries the 0x80. But the fold takes DISPLAYED values, so "which node is
		// animated" is invisible to it and that setup is the identical call to V2a --
		// foldTint(0x80FFFFFF, 0x80FFFFFF). Its assertion that "the short-circuit reading gives
		// 0.5019" could never fail, because no short-circuit is expressible here. A vector that
		// cannot fail is not a vector.
		//
		// The defect was real and it lived at the CALL SITE: Canvas2dRenderer guarded the fold with
		// `parent.tint != 0xFFFFFFFF`, testing the parent's raw packed field while folding what was
		// meant to be its displayed one -- so a group animated away from white skipped the fold and
		// reached no child. Canvas2dRenderer needs a GL context and no JVM test can load it.
		//
		// So it is closed STRUCTURALLY instead, the same move ANIM-9(b) made with the stage: the
		// fold takes ints, not nodes, and therefore has nothing to reach past its arguments to.
		for (java.lang.reflect.Method m : NodeFold.class.getDeclaredMethods()) {
			for (Class<?> t : m.getParameterTypes()) {
				assertTrue(m.getName() + " takes a " + t.getSimpleName() + "; the fold must take"
						+ " VALUES, or a guard on a raw field becomes expressible again",
						t != SceneNode.class);
			}
		}

		// Unconditional: every parent tint folds, including white. The guard was an optimisation
		// whose removal is bit-identical, which is what made deleting it safe rather than a
		// behaviour change -- 255/255 is exactly 1.0 and multiplying by it is exact.
		double half = 128 / 255.0;
		double[] whiteParent = new double[4];
		NodeFold.foldTint(0x80FFFFFF, NodeFold.WHITE, whiteParent);
		assertEquals("a white parent multiplies by exactly one, to the bit", half,
				whiteParent[NodeFold.TINT_A], 0.0);
		assertEquals(1.0, whiteParent[NodeFold.TINT_R], 0.0);

		// And a non-white parent does change the answer, or the line above would pass against a
		// fold that ignored its parent argument entirely.
		double[] tintedParent = new double[4];
		NodeFold.foldTint(0x80FFFFFF, 0x80FFFFFF, tintedParent);
		assertTrue("ignoring the parent would give 0.5019 here",
				Math.abs(tintedParent[NodeFold.TINT_A] - half) > 1e-6);
		assertEquals(half * half, tintedParent[NodeFold.TINT_A], 1e-12);
	}

	// ------------------------------------------------------------------ the carve-outs, enforced

	@Test
	public void zAndVisibleHaveNoComposedValueToRead() throws Exception {
		// ANIM-10 names the visibility gate and the z anchor among the consumers that must read the
		// composed value. Neither can. Enforced rather than described: if either is ever made
		// ownable, compose() stops throwing and this test fails, which is the moment the fold and
		// the amendment both have to say what a composed z means.
		int[] notOwnable = { OcslWire.PROP_ANIM_Z, OcslWire.PROP_ANIM_VISIBLE };
		for (int i = 0; i < notOwnable.length; i++) {
			assertEquals("property " + notOwnable[i] + " must have no composition rule",
					-1, OcslCompose.ruleFor(notOwnable[i]));
			try {
				OcslCompose.compose(notOwnable[i], 1.0, 1.0f);
				fail("compose() accepted property " + notOwnable[i] + ", which ANIM-10 carves out"
						+ " by name; it is now ownable and the fold must say what it means");
			} catch (IllegalArgumentException expected) {
				assertTrue("and it says so by name", expected.getMessage().contains("not composable"));
			}
		}

		// Non-vacuity: an OWNABLE property must not throw, or the loop above would pass against a
		// compose() that refused everything.
		assertEquals(3.0, OcslCompose.compose(OcslWire.PROP_ANIM_X, 1.0, 2.0f), 0.0);
	}

	@Test
	public void theFoldIsTwoDimensionalAndSaysSoWhenAThirdAppears() throws Exception {
		// THE SCOPED GAP. ANIM-3 pinned composition equations for tz, sz and rot3d; the renderer has
		// no field, no interpolator slot and no matrix term for any of them, so ANIM-10 binds the
		// six 2D properties only. This is a stated scope, and this test is what stops it becoming a
		// forgotten one: the moment a 3D transform field lands on SceneNode, the fold and the
		// interpolator have to widen together and this fails to say so.
		List<String> transformFields = new ArrayList<String>();
		for (Field f : SceneNode.class.getDeclaredFields()) {
			if (Modifier.isStatic(f.getModifiers())) {
				continue;
			}
			if (f.getType() == double.class || f.getType() == float.class) {
				transformFields.add(f.getName());
			}
		}
		List<String> expected = Arrays.asList("x", "y", "rot", "sx", "sy");
		assertEquals("SceneNode's transform is 2D and five wide; a new scalar field here means"
				+ " ANIM-10's scope, NodeFold.TRS_WIDTH and NodeInterpolator's FIELDS must all"
				+ " widen together. Found: " + transformFields,
				expected.size(), transformFields.size());
		assertTrue("and they are exactly " + expected + ", found " + transformFields,
				transformFields.containsAll(expected));
		assertEquals("the fold's width tracks that set", expected.size(), NodeFold.TRS_WIDTH);

		// The three that have no consumer are nonetheless composable, which is the gap stated as an
		// assertion rather than as a sentence: OcslCompose answers for them and nothing can draw it.
		assertEquals(6.0, OcslCompose.compose(OcslWire.PROP_ANIM_SZ, 2.0, 3.0f), 0.0);
		assertEquals(5.0, OcslCompose.compose(OcslWire.PROP_ANIM_TZ, 2.0, 3.0f), 0.0);
	}

	// ------------------------------------------------------------------ the fold itself

	@Test
	public void anUnparentedNodeFoldsToItsOwnTransformExactly() throws Exception {
		double[] child = trs(3, 4, 0, 2, 5);
		NodeFold.Affine withNull = new NodeFold.Affine();
		NodeFold.foldTransform(null, child, withNull);

		NodeFold.Affine withIdentityParent = new NodeFold.Affine();
		NodeFold.foldTransform(trs(0, 0, 0, 1, 1), child, withIdentityParent);

		assertEquals("a null parent and an identity parent agree", withIdentityParent.a, withNull.a, 0.0);
		assertEquals(withIdentityParent.d, withNull.d, 0.0);
		assertEquals(withIdentityParent.e, withNull.e, 0.0);
		assertEquals(withIdentityParent.f, withNull.f, 0.0);
		assertEquals("and the scale is the child's own", 2.0, withNull.a, 0.0);
		assertEquals(5.0, withNull.d, 0.0);
	}

	@Test
	public void foldTransformResetsSoAReusedAccumulatorCannotCarryTheLastNodeOver() throws Exception {
		// The renderer reuses ONE Affine for every node in the scene. If the fold ever stopped
		// starting from identity, node N would draw through node N-1's matrix -- a defect that
		// follows z-order and reads as nondeterministic, which is the failure mode this file's
		// neighbours document twice.
		NodeFold.Affine m = new NodeFold.Affine();
		NodeFold.foldTransform(null, trs(100, 100, 1.0, 7, 7), m);
		NodeFold.foldTransform(null, trs(1, 2, 0, 1, 1), m);
		assertEquals("second fold must not see the first", 1.0, m.e, 1e-12);
		assertEquals(2.0, m.f, 1e-12);
		assertEquals(1.0, m.a, 1e-12);
		assertTrue("carrying the previous node over would give e = 100 + something", m.e < 50.0);
	}
}
