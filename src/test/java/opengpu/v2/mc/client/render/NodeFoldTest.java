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
		// IDENTITY FIRST, then the 2D five. A fresh array is not the identity transform once the
		// record carries the 3D six — sz and qw both want 1 and a new double[] gives 0 — and these
		// vectors would silently start describing a node scaled to nothing on the axis they do not
		// mention, the moment anything downstream reads it.
		NodeFold.identity(t);
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
	public void theTransformRecordIsExactlyAsWideAsTheModelItCarries() throws Exception {
		// THE SCOPED GAP, CLOSED AT C1.3.3 — and the tripwire outlives the closing rather than
		// being deleted along with it. The gap was that the MODEL is eleven scalars wide while the
		// DISPLAYED RECORD was five, so tz, sz and the rot3d quaternion had nowhere to travel.
		// They travel now: NodeInterpolator smooths all eleven, the quaternion by slerp.
		//
		// So the assertion that used to read "the fold consumes the 2D five ONLY" has become "the
		// record is exactly as wide as the model", which is a STRONGER coupling rather than a
		// relaxed one. A twelfth scalar on SceneNode used to owe this test an explanation; it now
		// also owes the record a slot and an index constant, and fails here until it has them. A
		// missing line in rawTransform is NOT caught here — that one belongs to
		// NodeInterpolatorTest.theRawReadCarriesEveryFieldOfTheModel, and an earlier draft of this
		// comment claimed it for this test.
		List<String> transformFields = new ArrayList<String>();
		for (Field f : SceneNode.class.getDeclaredFields()) {
			if (Modifier.isStatic(f.getModifiers())) {
				continue;
			}
			if (f.getType() == double.class || f.getType() == float.class) {
				transformFields.add(f.getName());
			}
		}
		List<String> expected = Arrays.asList("x", "y", "rot", "sx", "sy",
				"tz", "sz", "qx", "qy", "qz", "qw");
		assertEquals("SceneNode's scalar model is eleven wide (the 2D five plus v10's 3D six);"
				+ " a new scalar field here means ANIM-10's scope, NodeFold.TRS_WIDTH and"
				+ " NodeInterpolator's FIELDS must all be re-examined together. Found: "
				+ transformFields,
				expected.size(), transformFields.size());
		// THE INDEX SET, ASSERTED DIRECTLY, because the first version of this check was a PROXY for
		// it: `TRS_QW == TRS_WIDTH - 1`, under a message claiming the constants "cover the model
		// exactly". That predicate cannot see a collision — renumber TRS_QZ to equal TRS_QY and it
		// still passes, while two record slots silently become one.
		//
		// It is the SAME defect this project fixed in ProtocolVersionTest.assertIdTable on
		// 2026-08-28 (a proxy asserted while the message promised contiguity), written again
		// roughly 24 hours later by the same hand, which is why it is now spelled the same way:
		// build the set, compare the set, and check the size separately so a duplicate cannot hide
		// inside it. (The dates in this comment were wrong on first writing and were corrected in
		// CASEBOOK.md alone — this shipped mirror kept them for another round, which is the
		// one-sided fix landing inside the fix for a false claim.)
		int[] declared = { NodeFold.TRS_X, NodeFold.TRS_Y, NodeFold.TRS_ROT, NodeFold.TRS_SX,
				NodeFold.TRS_SY, NodeFold.TRS_TZ, NodeFold.TRS_SZ, NodeFold.TRS_QX,
				NodeFold.TRS_QY, NodeFold.TRS_QZ, NodeFold.TRS_QW };
		java.util.TreeSet<Integer> indices = new java.util.TreeSet<Integer>();
		for (int i = 0; i < declared.length; i++) {
			indices.add(Integer.valueOf(declared[i]));
		}
		java.util.TreeSet<Integer> slots = new java.util.TreeSet<Integer>();
		for (int i = 0; i < NodeFold.TRS_WIDTH; i++) {
			slots.add(Integer.valueOf(i));
		}
		assertEquals("the index constants must be exactly the slots 0..TRS_WIDTH-1", slots, indices);
		assertEquals("and no two may collide — a duplicate shrinks the set without changing it",
				declared.length, indices.size());
		assertEquals("one constant per scalar in the model", transformFields.size(), declared.length);

		// THE PARTITION, ASSERTED IN FULL — and the first version of this was the SAME proxy defect
		// the paragraph above is about, written in the assertion added to fix it. It read
		// `TRS_SY < TRS_TZ && TRS_TZ < TRS_QW`: two of the eleven boundary relations, leaving
		// TRS_X/Y/ROT/SX unpinned below the split and TRS_SZ/QX/QY/QZ unpinned above it. Swapping
		// TRS_ROT and TRS_SZ keeps the index set exactly {0..10} with no collisions AND keeps
		// SY < TZ < QW, while `rot` is interpolated on the 3D timeline and slot 2 is written by
		// neither group.
		//
		// This is load-bearing rather than decorative: NodeInterpolator splits the record into two
		// keyframe groups by RANGE ([0, TRS_TZ) and [TRS_TZ, TRS_WIDTH)) and its javadoc cites
		// this test as the thing that makes the split safe.
		int[] twoD = { NodeFold.TRS_X, NodeFold.TRS_Y, NodeFold.TRS_ROT, NodeFold.TRS_SX,
				NodeFold.TRS_SY };
		int[] threeD = { NodeFold.TRS_TZ, NodeFold.TRS_SZ, NodeFold.TRS_QX, NodeFold.TRS_QY,
				NodeFold.TRS_QZ, NodeFold.TRS_QW };
		for (int i = 0; i < twoD.length; i++) {
			assertTrue("2D constant at slot " + twoD[i] + " must sit below the split at "
					+ NodeFold.TRS_TZ, twoD[i] < NodeFold.TRS_TZ);
		}
		for (int i = 0; i < threeD.length; i++) {
			assertTrue("3D constant at slot " + threeD[i] + " must sit at or above the split at "
					+ NodeFold.TRS_TZ, threeD[i] >= NodeFold.TRS_TZ);
		}
		// THE UNION, COMPARED AS A SET — and this is the FOURTH spelling of this one assertion,
		// each previous one a cheaper proxy than the last. (1) `TRS_QW == TRS_WIDTH - 1` under a
		// message promising the constants "cover the model exactly". (2) `TRS_SY < TRS_TZ &&
		// TRS_TZ < TRS_QW` under a comment claiming the two groups partition the record. (3)
		// `twoD.length + threeD.length == TRS_WIDTH` under a message promising exhaustion — which
		// a duplicate entry in either array satisfies while a constant vanishes from both, its
		// position relative to the split then asserted nowhere.
		//
		// A count is not a set, an inequality is not a partition, and a single boundary is not a
		// contiguity. Each of the three was the CHEAP HALF of the property in the message above
		// it, which is what made each feel sufficient while it was being typed.
		java.util.TreeSet<Integer> union = new java.util.TreeSet<Integer>();
		for (int i = 0; i < twoD.length; i++) {
			union.add(Integer.valueOf(twoD[i]));
		}
		for (int i = 0; i < threeD.length; i++) {
			union.add(Integer.valueOf(threeD[i]));
		}
		assertEquals("the two groups must partition the record's declared constants exactly",
				indices, union);
		assertEquals("with no constant listed twice across the two groups",
				twoD.length + threeD.length, union.size());
		assertEquals("and the split must fall exactly where the 2D five end", twoD.length,
				NodeFold.TRS_TZ);
		assertTrue("and they are exactly " + expected + ", found " + transformFields,
				transformFields.containsAll(expected));
		assertEquals("the displayed-transform record must be exactly as wide as the scalar model"
				+ " it carries", transformFields.size(), NodeFold.TRS_WIDTH);
		assertEquals("and eleven is still written down as a LITERAL here, so the two cannot agree"
				+ " by both being wrong", 11, NodeFold.TRS_WIDTH);

		// THE THIRD MEMBER OF THE TRIO, now actually checked. The previous version of this test
		// NAMED NodeInterpolator's FIELDS as part of the obligation and asserted nothing about it —
		// prose standing where a check belonged. It is private, it is in this package, and
		// reflection reaches it.
		Field width = NodeInterpolator.class.getDeclaredField("FIELDS");
		width.setAccessible(true);
		assertEquals("the interpolator must write exactly as many slots as the record holds:"
				+ " fewer leaves the previous node's value in a reused buffer, more throws on the"
				+ " render thread", NodeFold.TRS_WIDTH, width.getInt(null));

		// WHAT THAT CANNOT CATCH, said rather than implied. FIELDS derives from TRS_WIDTH as of
		// C1.3.3, so the assertion above is a tautology TODAY. It is kept because an edit that
		// re-literalises FIELDS — the exact form it had before this increment — turns it back into
		// a real check, and there is no way to catch a re-literalisation that happens to be equal.
		// The same limit is recorded in MeshGlLayoutTest for the same reason.

		// Composable AND carried, which is the closed half; still refused at the animator surface,
		// which is the half C1.3.3's later group opens. Two different statements about the same
		// three properties, and running them together is how this test's own scope note went stale.
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

	/**
	 * The packed and unpacked tint folds are one rule in two forms — 3.3b added the array form
	 * because a composed tint is continuous and has no packed representation. This agreement is
	 * what ALLOWS the rule to exist twice: if it ever fails, one of the two has drifted and the
	 * animated and unanimated paths are tinting differently.
	 */
	@Test
	public void thePackedAndUnpackedTintFoldsAgree() throws Exception {
		int[] tints = { 0x80402010, 0xFF808080, 0x00FFFFFF, 0x7F123456, NodeFold.WHITE };
		double[] viaPacked = new double[4];
		double[] viaArrays = new double[4];
		double[] child = new double[4];
		double[] parent = new double[4];
		for (int c : tints) {
			for (int p : tints) {
				NodeFold.foldTint(c, p, viaPacked);
				NodeFold.unpack(c, child);
				NodeFold.unpack(p, parent);
				NodeFold.foldTint(child, parent, viaArrays);
				for (int ch = 0; ch < 4; ch++) {
					assertEquals("channel " + ch + " of " + Integer.toHexString(c) + " x "
							+ Integer.toHexString(p), viaPacked[ch], viaArrays[ch], 0.0);
				}
			}
		}
	}

	/** unpack reads ARGB into TINT_* (RGBA) order — the swap is where a wrong shift would hide. */
	@Test
	public void unpackReadsTheChannelsInTintOrder() throws Exception {
		double[] out = new double[4];
		NodeFold.unpack(0x80402010, out);
		assertEquals("R is bits 16-23", 0x40 / 255.0, out[NodeFold.TINT_R], 0.0);
		assertEquals("G is bits 8-15", 0x20 / 255.0, out[NodeFold.TINT_G], 0.0);
		assertEquals("B is bits 0-7", 0x10 / 255.0, out[NodeFold.TINT_B], 0.0);
		assertEquals("A is bits 24-31", 0x80 / 255.0, out[NodeFold.TINT_A], 0.0);
	}
}
