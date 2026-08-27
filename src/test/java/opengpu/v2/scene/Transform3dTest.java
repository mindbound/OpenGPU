package opengpu.v2.scene;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import opengpu.v2.protocol.V2Wire;

/**
 * The only instrument that can see a wrong 3D convention.
 *
 * A transposed rotation, a view matrix that is the camera's transform rather than its inverse,
 * or a flipped forward axis all produce a picture that renders cleanly from the wrong place.
 * The compiler cannot see it, the suite cannot see it, and in-game it looks like "the camera is
 * a bit off" rather than like a defect. So each test below states the WRONG value it excludes,
 * not merely the right one — a bare equality assertion passes with several of these bugs when
 * the case happens to be symmetric.
 *
 * Oracles are pinned against {@link Look}, which fixed these conventions for the server's
 * lookAt; if the two ever disagree, a camera aimed by lookAt renders down a different axis than
 * it was aimed, so T4 ties the new code to that existing pin rather than to my arithmetic.
 */
public class Transform3dTest {

	private static final double EPS = 1e-9;
	private static final double R2 = Math.sqrt(0.5);

	private static ServerScene scene() {
		return new ServerScene("t3d");
	}

	private static SceneNode node(ServerScene s, int id) {
		return s.state().nodes.get(Integer.valueOf(id));
	}

	private static void assertVec(String why, double[] want, double[] got, double eps) {
		for (int i = 0; i < 3; i++) {
			assertEquals(why + " [" + i + "]", want[i], got[i], eps);
		}
	}

	// ------------------------------------------------------------------ rotation

	@Test
	public void identityQuaternionGivesIdentityRotation() {
		double[][] r = Transform3d.rotation(0, 0, 0, 1);
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				assertEquals(i == j ? 1.0 : 0.0, r[i][j], EPS);
			}
		}
	}

	/**
	 * THE transpose falsifier. This rotation is ASYMMETRIC, so it is the case that distinguishes
	 * R from R-transpose — the 180-degree cases below cannot, because they are diagonal.
	 */
	@Test
	public void anAsymmetricRotationPinsTheHandednessAndCatchesATransposedFormula() {
		double[][] r = Transform3d.rotation(0, -R2, 0, R2);
		assertEquals("R02 must be -1", -1.0, r[0][2], EPS);
		assertEquals("R20 must be +1", 1.0, r[2][0], EPS);
		assertTrue("R02 and R20 must differ in sign, or the matrix is symmetric and this test"
				+ " cannot see a transpose at all", r[0][2] * r[2][0] < 0);
		assertEquals(0.0, r[0][0], EPS);
		assertEquals(1.0, r[1][1], EPS);
		assertEquals(0.0, r[2][2], EPS);
	}

	@Test
	public void theThreeHalfTurnsPinTheDiagonalSigns() {
		double[][] x = Transform3d.rotation(1, 0, 0, 0);
		assertVec("180 about X", new double[] { 1, -1, -1 },
				new double[] { x[0][0], x[1][1], x[2][2] }, EPS);
		double[][] y = Transform3d.rotation(0, 1, 0, 0);
		assertVec("180 about Y", new double[] { -1, 1, -1 },
				new double[] { y[0][0], y[1][1], y[2][2] }, EPS);
		double[][] z = Transform3d.rotation(0, 0, 1, 0);
		assertVec("180 about Z", new double[] { -1, -1, 1 },
				new double[] { z[0][0], z[1][1], z[2][2] }, EPS);
		// These three are symmetric matrices. Stated explicitly because a reader could otherwise
		// take this test as covering the transpose bug, which it structurally cannot.
		assertEquals("this case is symmetric and so blind to a transpose", x[0][2], x[2][0], 0.0);
	}

	/**
	 * Ties the new math to the convention LookTest already pins: a node looks down its
	 * local -Z. NOTE this is agreement WITH Look, not independent verification of it — if
	 * Look.quat were itself wrong, this test would confirm the error rather than catch it.
	 * What it does guarantee is that lookAt and the renderer cannot drift apart.
	 * Sixteen directions, so a sign flip cannot hide in a symmetry.
	 */
	@Test
	public void everyLookAtQuaternionAimsTheLocalMinusZAtTheTarget() {
		double[][] dirs = {
			{ 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 },
			{ 1, 1, 0 }, { -1, 1, 0 }, { 1, -1, 0 }, { -1, -1, 0 },
			{ 1, 0, 1 }, { -1, 0, 1 }, { 1, 0, -1 }, { -1, 0, -1 },
			{ 2, 3, 4 }, { -2, 3, -4 }, { 0.1, -0.2, 0.3 }, { -5, -1, 2 },
		};
		for (int i = 0; i < dirs.length; i++) {
			double[] d = dirs[i];
			double len = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
			double[] q = Look.quat(0, 0, 0, d[0], d[1], d[2], 0, 1, 0);
			double[][] r = Transform3d.rotation(q[0], q[1], q[2], q[3]);
			// local -Z through R
			double fx = -r[0][2], fy = -r[1][2], fz = -r[2][2];
			assertVec("direction " + i + ": local -Z must point at the target, or the forward"
					+ " convention has flipped to +Z",
					new double[] { d[0] / len, d[1] / len, d[2] / len },
					new double[] { fx, fy, fz }, 1e-12);
		}
	}

	@Test
	public void everyRotationIsOrthonormalAndRightHanded() {
		double[][] qs = {
			{ 0, 0, 0, 1 }, { 0, -R2, 0, R2 }, { 1, 0, 0, 0 },
			{ 0.3, 0.4, 0.5, 0.7 }, { -0.1, 0.9, 0.2, 0.3 },
		};
		for (int i = 0; i < qs.length; i++) {
			double[][] r = Transform3d.rotation(qs[i][0], qs[i][1], qs[i][2], qs[i][3]);
			for (int a = 0; a < 3; a++) {
				for (int b = 0; b < 3; b++) {
					double dot = r[0][a] * r[0][b] + r[1][a] * r[1][b] + r[2][a] * r[2][b];
					assertEquals("case " + i + ": columns must be orthonormal (a dropped factor"
							+ " of 2 breaks this)", a == b ? 1.0 : 0.0, dot, 1e-12);
				}
			}
			double det = r[0][0] * (r[1][1] * r[2][2] - r[1][2] * r[2][1])
					- r[0][1] * (r[1][0] * r[2][2] - r[1][2] * r[2][0])
					+ r[0][2] * (r[1][0] * r[2][1] - r[1][1] * r[2][0]);
			assertEquals("case " + i + ": det must be +1 — a det of -1 is a LEFT-handed frame,"
					+ " which renders a mirrored world that looks merely 'wrong somehow'",
					1.0, det, 1e-12);
		}
	}

	@Test
	public void aNonUnitQuaternionIsNormalisedRatherThanScalingTheModel() {
		double[][] unit = Transform3d.rotation(0, -R2, 0, R2);
		double[][] scaled = Transform3d.rotation(0, -R2 * 7, 0, R2 * 7);
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				assertEquals("a 7x-length quaternion must give the SAME rotation, not a 49x"
						+ " scale — nothing on the delta or snapshot path enforces unit length",
						unit[i][j], scaled[i][j], EPS);
			}
		}
	}

	// ------------------------------------------------------------------ view matrix

	@Test
	public void theViewMatrixNegatesTheCameraPositionRatherThanCopyingIt() {
		ServerScene s = scene();
		int cam = s.createNode(V2Wire.NODE_CAMERA, 0);
		s.setPose3d(cam, 0, 0, 5, 0, 0, 0, 1, true);
		float[] v = Transform3d.viewMatrix(node(s, cam), s.state());
		double[] eye = Transform3d.apply(v, 0, 0, 0);
		assertVec("world origin seen from a camera at z=+5 must be 5 in FRONT of it, i.e. -5",
				new double[] { 0, 0, -5 }, eye, EPS);
		assertTrue("z must be negative: +5 here means the translation was copied, not negated",
				eye[2] < 0);
		// BLIND SPOT, stated so this test is not mistaken for covering it: the rotation here
		// is identity, so R equals its own transpose and a translation built from R's rows
		// instead of its columns gives the same answer. A real half-transposed inverse shipped
		// past this assertion and was caught only by the rotated-camera case below.
	}

	/**
	 * THE inverse falsifier. With a rotated camera, loading the camera's own transform instead
	 * of its inverse gives z = +5 rather than -5 — same magnitude, opposite sign, and a picture
	 * that renders.
	 */
	@Test
	public void theViewMatrixIsTheInverseOfTheCamerasWorldTransform() {
		ServerScene s = scene();
		int cam = s.createNode(V2Wire.NODE_CAMERA, 0);
		s.setPose3d(cam, -3, 0, 0, 0, -R2, 0, R2, true);
		float[] v = Transform3d.viewMatrix(node(s, cam), s.state());
		double[] eye = Transform3d.apply(v, 2, 1, 0);
		assertVec("camera at (-3,0,0) looking down +X; the point (2,1,0) is 5 ahead and 1 up",
				new double[] { 0, 1, -5 }, eye, 1e-6);
		assertTrue("z must be NEGATIVE (in front). +5 is the signature of loading M instead of"
				+ " M-inverse — it renders, and it renders backwards", eye[2] < 0);
		assertTrue("and must not be +5 specifically", Math.abs(eye[2] - 5.0) > 1.0);
	}

	@Test
	public void aCameraParentedToAScaledRotatedRigStillSeesTheWorldCorrectly() {
		ServerScene s = scene();
		int rig = s.createNode(V2Wire.NODE_GROUP, 0);
		s.setPose3d(rig, 10, 0, 0, 0, R2, 0, R2, true);
		// Set on the node directly because Transform3d reads the raw fields. NOTE: scale
		// DOES have a server verb — setNodeTransform3d's 11-argument form has carried
		// sx/sy/sz since C1.2. An earlier version of this comment claimed otherwise, and
		// that false belief is why no test gave a node a non-unit OWN scale until a panel
		// found the gap.
		node(s, rig).sx = 2;
		node(s, rig).sy = 2;
		node(s, rig).sz = 2;
		int cam = s.createNode(V2Wire.NODE_CAMERA, 0, rig);
		s.setPose3d(cam, 0, 0, 5, 0, 0, 0, 1, true);
		float[] v = Transform3d.viewMatrix(node(s, cam), s.state());
		double[] eye = Transform3d.apply(v, 0, 0, 0);
		// Parent rotates +Z onto +X and scales by 2, so local (0,0,5) lands at world (20,0,0).
		assertEquals("the camera must be 20 away, proving position inherits BOTH the parent's"
				+ " rotation and its scale", 20.0, Math.sqrt(
						eye[0] * eye[0] + eye[1] * eye[1] + eye[2] * eye[2]), 1e-6);
		assertTrue("a parent-scale-ignoring implementation puts it at 15",
				Math.abs(Math.sqrt(eye[0] * eye[0] + eye[1] * eye[1] + eye[2] * eye[2]) - 15.0)
						> 1.0);
	}

	@Test
	public void theCamerasOwnScaleDoesNotDistortTheView() {
		ServerScene s = scene();
		int cam = s.createNode(V2Wire.NODE_CAMERA, 0);
		s.setPose3d(cam, 0, 0, 5, 0, 0, 0, 1, true);
		float[] before = Transform3d.viewMatrix(node(s, cam), s.state());
		node(s, cam).sx = 3;
		node(s, cam).sy = 3;
		node(s, cam).sz = 3;
		float[] after = Transform3d.viewMatrix(node(s, cam), s.state());
		for (int i = 0; i < 16; i++) {
			assertEquals("scaling a CAMERA must not change what it sees — the frustum expresses"
					+ " zoom, and applying scale here would silently zoom instead [" + i + "]",
					before[i], after[i], 0.0F);
		}
	}

	// ------------------------------------------------------------------ model matrix

	@Test
	public void theModelMatrixPlacesTranslationAtTheColumnMajorIndices() {
		ServerScene s = scene();
		int n = s.createNode(V2Wire.NODE_GROUP, 0);
		s.setPose3d(n, 7, 8, 9, 0, 0, 0, 1, true);
		float[] m = Transform3d.modelMatrix(node(s, n), s.state());
		assertEquals("translation x belongs at index 12 (column-major), not 3 (row-major)",
				7.0F, m[12], 1e-6F);
		assertEquals(8.0F, m[13], 1e-6F);
		assertEquals(9.0F, m[14], 1e-6F);
		assertEquals("index 3 must be 0 — a value here means the matrix is row-major and GL will"
				+ " read it transposed", 0.0F, m[3], 0.0F);
		assertEquals(1.0F, m[15], 0.0F);
	}

	@Test
	public void aMeshInheritsItsParentsRotationAndScale() {
		ServerScene s = scene();
		int rig = s.createNode(V2Wire.NODE_GROUP, 0);
		s.setPose3d(rig, 10, 0, 0, 0, R2, 0, R2, true);
		node(s, rig).sx = 2;
		node(s, rig).sy = 2;
		node(s, rig).sz = 2;
		int mesh = s.createNode(V2Wire.NODE_GROUP, 0, rig);
		s.setPose3d(mesh, 1, 0, 0, 0, 0, 0, 1, true);
		float[] m = Transform3d.modelMatrix(node(s, mesh), s.state());
		double[] world = Transform3d.apply(m, 1, 0, 0);
		// Parent maps +X onto -Z and doubles; mesh local origin is at world (10,0,-2), and its
		// own local (1,0,0) adds another (0,0,-2).
		assertVec("a mesh takes its PARENT's scale (its own is covered by"
				+ " aNodesOwnScaleReachesItsOwnGeometry, which this case cannot see)",
				new double[] { 10, 0, -4 },
				world, 1e-6);
	}

	// ------------------------------------------------------------------ frustum

	@Test
	public void thePerspectiveFrustumMatchesItsWorkedOracle() {
		double[] e = Transform3d.frustumExtents(
				new double[] { ServerScene.PROJECTION_PERSPECTIVE, 60.0, 0.1, 100.0 }, 512, 288);
		assertEquals("top = near * tan(fov/2)", 0.057735026918962574, e[1], 1e-15);
		assertEquals("right = top * aspect", 0.10264004785593346, e[0], 1e-15);
		assertTrue("right must EXCEED top for a wide FBO; if they are equal the aspect was"
				+ " dropped, and every scene renders vertically stretched", e[0] > e[1]);
	}

	@Test
	public void theAspectComesFromTheFboDimensionsSoTheSameFovWidensWithTheFbo() {
		double[] proj = { ServerScene.PROJECTION_PERSPECTIVE, 60.0, 0.1, 100.0 };
		double[] wide = Transform3d.frustumExtents(proj, 512, 288);
		double[] narrow = Transform3d.frustumExtents(proj, 800, 600);
		assertEquals("vertical extent is fov-driven and must NOT move with the aspect",
				wide[1], narrow[1], 1e-15);
		assertEquals(0.076980035891950099, narrow[0], 1e-15);
		assertTrue("a 16:9 FBO must be wider than a 4:3 one at the same fov", wide[0] > narrow[0]);
	}

	@Test
	public void orthoReadsTheSecondComponentAsAHalfHeightNotAnAngle() {
		double[] e = Transform3d.frustumExtents(
				new double[] { ServerScene.PROJECTION_ORTHO, 1.0, 0.1, 100.0 }, 512, 288);
		assertEquals("ortho top IS the half-height, with no tangent applied", 1.0, e[1], 1e-15);
		assertEquals(1.7777777777777777, e[0], 1e-15);
		assertTrue("if a tan() leaked into the ortho path top would be ~0.0087, not 1.0",
				e[1] > 0.5);
	}

	// ------------------------------------------------- panel-found blind spots (2026-08-27)

	/**
	 * A node's OWN scale reaching its own geometry. Added after a panel showed that deleting the
	 * own-scale factor entirely left all fifteen preceding tests green, BIT-identically: every
	 * node they build has the default scale of 1, and multiplying by exactly 1.0 is IEEE-exact.
	 * Non-uniform on purpose, so a per-axis mix-up cannot hide behind a uniform factor.
	 */
	@Test
	public void aNodesOwnScaleReachesItsOwnGeometry() {
		ServerScene s = scene();
		int n = s.createNode(V2Wire.NODE_GROUP, 0);
		s.setPose3d(n, 0, 0, 0, 0, 0, 0, 1, true);
		node(s, n).sx = 2;
		node(s, n).sy = 3;
		node(s, n).sz = 4;
		float[] m = Transform3d.modelMatrix(node(s, n), s.state());
		double[] world = Transform3d.apply(m, 1, 1, 1);
		assertVec("own scale must reach the node's own vertices", new double[] { 2, 3, 4 },
				world, 1e-9);
		assertTrue("(1,1,1) here means the own-scale factor was dropped — the exact mutation that"
				+ " survived every earlier test", Math.abs(world[0] - 1.0) > 0.5);
	}

	/**
	 * Scale-then-rotate ordering. Distinguishable ONLY with a non-uniform scale under an
	 * asymmetric rotation: with a uniform scale R*S == S*R, which is why no earlier test could
	 * see it.
	 */
	@Test
	public void ownScaleIsAppliedBeforeRotationNotAfter() {
		ServerScene s = scene();
		int n = s.createNode(V2Wire.NODE_GROUP, 0);
		s.setPose3d(n, 0, 0, 0, R2, 0, 0, R2, true);   // 90 degrees about X
		node(s, n).sx = 2;
		node(s, n).sy = 3;
		node(s, n).sz = 4;
		float[] m = Transform3d.modelMatrix(node(s, n), s.state());
		double[] world = Transform3d.apply(m, 1, 1, 1);
		assertVec("R*S: scale in the node's own axes, then rotate",
				new double[] { 2, -4, 3 }, world, 1e-9);
		assertTrue("(2,-3,4) is S*R — rotating first and scaling in WORLD axes, which shears a"
				+ " rotated non-uniformly scaled mesh", Math.abs(world[1] + 3.0) > 0.5);
	}

	/**
	 * THE composition-order falsifier. Both earlier parented tests give the CHILD an identity
	 * rotation, and R_parent * I == I * R_parent — so an implementation composing the product
	 * backwards passed every one of them. Here both rotations are asymmetric and do not commute.
	 */
	@Test
	public void parentRotationComposesOntoTheChildInThatOrder() {
		ServerScene s = scene();
		int rig = s.createNode(V2Wire.NODE_GROUP, 0);
		s.setPose3d(rig, 0, 0, 0, 0, R2, 0, R2, true);    // 90 about Y
		int child = s.createNode(V2Wire.NODE_GROUP, 0, rig);
		s.setPose3d(child, 0, 0, 0, R2, 0, 0, R2, true);  // 90 about X
		double[][] w = Transform3d.worldRotation(node(s, child), s.state());
		double[] got = {
			w[0][0] * 0 + w[0][1] * 1 + w[0][2] * 0,
			w[1][0] * 0 + w[1][1] * 1 + w[1][2] * 0,
			w[2][0] * 0 + w[2][1] * 1 + w[2][2] * 0,
		};
		assertVec("R_parent * R_child applied to (0,1,0)", new double[] { 1, 0, 0 }, got, 1e-9);
		assertTrue("(0,0,1) is the reversed product R_child * R_parent — a different orientation"
				+ " entirely, and invisible whenever either rotation is identity",
				Math.abs(got[2]) < 0.5);
	}

	/** Per-axis parent scale on a child's position — uniform scale cannot see a mis-index. */
	@Test
	public void aChildsPositionTakesTheParentsScalePerAxis() {
		ServerScene s = scene();
		int rig = s.createNode(V2Wire.NODE_GROUP, 0);
		s.setPose3d(rig, 0, 0, 0, 0, 0, 0, 1, true);
		node(s, rig).sx = 2;
		node(s, rig).sy = 3;
		node(s, rig).sz = 4;
		int child = s.createNode(V2Wire.NODE_GROUP, 0, rig);
		s.setPose3d(child, 1, 1, 1, 0, 0, 0, 1, true);
		double[] w = Transform3d.worldPosition(node(s, child), s.state());
		assertVec("each axis takes ITS OWN parent scale factor", new double[] { 2, 3, 4 }, w,
				1e-9);
	}

	@Test
	public void bothMatricesCarryAProperHomogeneousRow() {
		ServerScene s = scene();
		int cam = s.createNode(V2Wire.NODE_CAMERA, 0);
		s.setPose3d(cam, 1, 2, 3, 0, -R2, 0, R2, true);
		float[] v = Transform3d.viewMatrix(node(s, cam), s.state());
		assertEquals("m[15] must be 1 — a 0 here makes every transformed point collapse when a"
				+ " later stage divides by w", 1.0F, v[15], 0.0F);
		for (int c = 0; c < 3; c++) {
			assertEquals("m[" + (c * 4 + 3) + "] must be 0", 0.0F, v[c * 4 + 3], 0.0F);
		}
	}

	@Test
	public void aDegenerateQuaternionFallsBackToIdentityRatherThanNaN() {
		double[][] zero = Transform3d.rotation(0, 0, 0, 0);
		double[][] nan = Transform3d.rotation(Double.NaN, 0, 0, 1);
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				assertEquals("zero quaternion -> identity", i == j ? 1.0 : 0.0, zero[i][j], EPS);
				assertEquals("NaN quaternion -> identity, never NaN (a NaN matrix propagates to"
						+ " every vertex and the mesh vanishes with no error)",
						i == j ? 1.0 : 0.0, nan[i][j], EPS);
			}
		}
	}
}
