package opengpu.v2.scene;

/**
 * The 3D transform math the renderer needs: quaternion to rotation, world composition across the
 * one permitted parent level, the camera's view matrix, and the projection frustum.
 *
 * <b>GL-free on purpose.</b> Every method here is pure arithmetic over doubles, so it is unit
 * testable on the JVM — which matters more here than anywhere else in the renderer, because a
 * wrong convention does not crash or blank the screen. It renders a plausible picture from the
 * wrong viewpoint, and no compiler, suite, log line or in-game glance distinguishes that from a
 * right one. {@code Transform3dTest} is the only instrument that can, so the oracles it pins are
 * part of this class's contract rather than incidental coverage.
 *
 * <b>Conventions, inherited rather than invented.</b> {@link Look} already fixed them for the
 * server's lookAt, and this class must agree with it or a camera aimed by {@code lookAt} would
 * render down a different axis than it was aimed. From {@code Look}'s own construction: the
 * rotation's COLUMNS are the node's local axes in parent space, +X right, +Y up, and the node
 * <b>looks down its local −Z</b>. That is also the frame {@code glFrustum} expects, so nothing
 * here flips a sign to compensate.
 *
 * <b>Row-major here, column-major at the GL boundary.</b> {@code rotation()} returns
 * {@code R[row][col]}; {@link #viewMatrix} and {@link #modelMatrix} emit the 16-float
 * column-major arrays GL wants, translation at indices 12/13/14. The two conventions meeting in
 * one file is a real hazard, so the transposition happens in exactly those two methods and
 * nowhere else.
 */
public final class Transform3d {

	private Transform3d() {
	}

	/**
	 * Quaternion to rotation matrix, returned as {@code R[row][col]}.
	 *
	 * The quaternion is normalized here rather than trusted. Unit length is established only by
	 * the two server entry points that build one ({@code TileEntityGpu2}'s pose verbs); the
	 * delta applier, the snapshot codec and the raw props path all accept whatever arrives, and
	 * a non-unit quaternion silently scales the model. The guard skips the sqrt entirely when
	 * the quaternion is already unit within 1e-12 — the common case, since both server pose
	 * verbs normalize — and this method is called more than once per node per frame anyway
	 * (worldRotation and modelMatrix each call it, and a parented node calls it for its parent
	 * too). The cost is a few multiplies; what it buys is removing a whole class of "the mesh
	 * is subtly the wrong size" report.
	 *
	 * A zero-length quaternion is treated as identity: it cannot be normalized, and rendering
	 * nothing is a worse answer than rendering unrotated.
	 */
	public static double[][] rotation(double qx, double qy, double qz, double qw) {
		double len2 = qx * qx + qy * qy + qz * qz + qw * qw;
		if (len2 <= 0.0 || Double.isNaN(len2)) {
			return new double[][] { { 1, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 } };
		}
		if (Math.abs(len2 - 1.0) > 1e-12) {
			double inv = 1.0 / Math.sqrt(len2);
			qx *= inv;
			qy *= inv;
			qz *= inv;
			qw *= inv;
		}
		double xx = qx * qx, yy = qy * qy, zz = qz * qz;
		double xy = qx * qy, xz = qx * qz, yz = qy * qz;
		double wx = qw * qx, wy = qw * qy, wz = qw * qz;
		return new double[][] {
			{ 1 - 2 * (yy + zz), 2 * (xy - wz),     2 * (xz + wy) },
			{ 2 * (xy + wz),     1 - 2 * (xx + zz), 2 * (yz - wx) },
			{ 2 * (xz - wy),     2 * (yz + wx),     1 - 2 * (xx + yy) },
		};
	}

	/** This node's parent, or null. One lookup, never a loop — nesting is capped at one level. */
	public static SceneNode parentOf(SceneNode node, SceneState state) {
		if (node == null || node.parent == 0) {
			return null;
		}
		return state.nodes.get(Integer.valueOf(node.parent));
	}

	/**
	 * A node's world position, following the one permitted parent level.
	 *
	 * The child's local translation is scaled and rotated by the parent, matching the T-R-S
	 * composition the 2D fold already uses: {@code t = t_p + R_p * (S_p * t_c)}. Position
	 * inherits parent scale for cameras exactly as for meshes — a camera parented to a scaled
	 * rig moves with the rig, which is what makes rigs useful.
	 */
	public static double[] worldPosition(SceneNode node, SceneState state) {
		SceneNode p = parentOf(node, state);
		if (p == null) {
			return new double[] { node.x, node.y, node.tz };
		}
		double lx = node.x * p.sx, ly = node.y * p.sy, lz = node.tz * p.sz;
		double[][] rp = rotation(p.qx, p.qy, p.qz, p.qw);
		return new double[] {
			p.x + rp[0][0] * lx + rp[0][1] * ly + rp[0][2] * lz,
			p.y + rp[1][0] * lx + rp[1][1] * ly + rp[1][2] * lz,
			p.tz + rp[2][0] * lx + rp[2][1] * ly + rp[2][2] * lz,
		};
	}

	/** A node's world rotation: parent's rotation composed with its own. */
	public static double[][] worldRotation(SceneNode node, SceneState state) {
		double[][] rc = rotation(node.qx, node.qy, node.qz, node.qw);
		SceneNode p = parentOf(node, state);
		if (p == null) {
			return rc;
		}
		return multiply3(rotation(p.qx, p.qy, p.qz, p.qw), rc);
	}

	/**
	 * The unit vector pointing TOWARD a directional light — what GL wants in a {@code w = 0}
	 * {@code GL_POSITION}, in world space.
	 *
	 * This is column 2 of {@link #worldRotation}, which {@link Look} builds as <b>-forward</b>
	 * ("+X -&gt; right, +Y -&gt; true up, +Z -&gt; -forward"). A node looks down its local -Z, so
	 * its rays travel along -Z and the light ARRIVES from +Z. Aim a light at the ground and this
	 * returns +Y: the sun is above.
	 *
	 * <b>Lives here, and is called by the renderer rather than reimplemented there, for a
	 * specific reason.</b> The first version of the lighting tests recomputed this expression in
	 * a test helper — so they pinned a COPY, and changing the binder to read a different column
	 * would have left all five green. A sign or column error in this expression is the worst kind
	 * of graphics defect to catch late: the shader clamps with {@code max(dot(n, L), 0)}, so it
	 * renders a perfectly plausible picture lit from the wrong side, with no error and no black
	 * frame. One implementation, one place to test.
	 *
	 * Deliberately NOT routed through {@link #apply}: that is a POINT transform and adds the
	 * translation column, which would turn a direction into a position.
	 */
	public static double[] towardLight(SceneNode light, SceneState state) {
		double[][] r = worldRotation(light, state);
		return new double[] { r[0][2], r[1][2], r[2][2] };
	}

	/**
	 * The camera's view matrix, 16 floats column-major, ready for {@code glLoadMatrix}.
	 *
	 * <b>The view matrix is the INVERSE of the camera's world transform</b> — the single most
	 * likely thing to get wrong here, because loading the camera's own transform instead
	 * produces a picture that renders, moves when the camera moves, and is wrong in the
	 * direction nobody checks. {@code Transform3dTest.theViewMatrixIsTheInverseOfTheCamerasWorldTransform}
	 * pins it by asserting the SIGN of the result, not merely its magnitude.
	 *
	 * Built as a RIGID inverse (rotation transpose, negated translation), which is correct
	 * because NO scale reaches the view's ORIENTATION — neither the camera's own nor its
	 * parent's. Scaling a camera has no physical meaning: it would scale the whole world
	 * inversely, which is what {@code setPerspective} already expresses properly through the
	 * frustum. The camera's POSITION does still inherit parent scale (see
	 * {@link #worldPosition}), so a scaled rig moves its camera correctly — position takes
	 * parent scale, orientation takes none. The consequence worth knowing: under a
	 * NON-UNIFORM parent scale the rigid inverse is not the exact inverse of the full affine
	 * transform, because a non-uniform scale composed with a rotation shears. That is a
	 * deliberate choice — a sheared camera has no sensible meaning — and is recorded here
	 * rather than left for someone to rediscover as a bug.
	 */
	public static float[] viewMatrix(SceneNode camera, SceneState state) {
		double[][] r = worldRotation(camera, state);
		double[] t = worldPosition(camera, state);
		float[] m = new float[16];
		// The transpose: R's ROW i becomes the view's COLUMN i. With column-major indexing
		// m[c*4+r], the view's element (r,c) is R[c][r] — so m[c*4+r] = r[c][r] reads as
		// R transposed, which is the inverse rotation for an orthonormal R.
		for (int c = 0; c < 3; c++) {
			for (int row = 0; row < 3; row++) {
				m[c * 4 + row] = (float) r[c][row];
			}
			m[c * 4 + 3] = 0.0F;
		}
		// -(R^T * t): the camera position expressed in CAMERA axes, negated. Note the index
		// order — each line runs down a COLUMN of R, not across a row. Using rows computes
		// -(R*t) instead, which is a HALF-transposed inverse: the rotation block is right and
		// the translation is wrong, so the camera aims correctly from the wrong place. A first
		// draft did exactly that, and the identity-rotation test could not see it because
		// R equals R^T there — only the rotated-camera oracle catches it.
		m[12] = (float) -(r[0][0] * t[0] + r[1][0] * t[1] + r[2][0] * t[2]);
		m[13] = (float) -(r[0][1] * t[0] + r[1][1] * t[1] + r[2][1] * t[2]);
		m[14] = (float) -(r[0][2] * t[0] + r[1][2] * t[1] + r[2][2] * t[2]);
		m[15] = 1.0F;
		return m;
	}

	/**
	 * A mesh instance's model matrix, 16 floats column-major.
	 *
	 * Full affine including scale, unlike the camera: a scaled mesh is exactly what scale is
	 * for. Composition matches the 2D fold's T-R-S ordering.
	 */
	public static float[] modelMatrix(SceneNode node, SceneState state) {
		double[][] rc = rotation(node.qx, node.qy, node.qz, node.qw);
		double[][] a = new double[3][3];
		double[] sc = { node.sx, node.sy, node.sz };
		for (int row = 0; row < 3; row++) {
			for (int c = 0; c < 3; c++) {
				a[row][c] = rc[row][c] * sc[c];
			}
		}
		SceneNode p = parentOf(node, state);
		if (p != null) {
			double[][] rp = rotation(p.qx, p.qy, p.qz, p.qw);
			double[][] ps = new double[3][3];
			double[] psc = { p.sx, p.sy, p.sz };
			for (int row = 0; row < 3; row++) {
				for (int c = 0; c < 3; c++) {
					ps[row][c] = rp[row][c] * psc[c];
				}
			}
			a = multiply3(ps, a);
		}
		double[] t = worldPosition(node, state);
		float[] m = new float[16];
		for (int c = 0; c < 3; c++) {
			for (int row = 0; row < 3; row++) {
				m[c * 4 + row] = (float) a[row][c];
			}
			m[c * 4 + 3] = 0.0F;
		}
		m[12] = (float) t[0];
		m[13] = (float) t[1];
		m[14] = (float) t[2];
		m[15] = 1.0F;
		return m;
	}

	/**
	 * The frustum half-extents for a projection, as {@code {right, top}}.
	 *
	 * Aspect comes from the FBO's own dimensions, never the game window: the scene renders into
	 * its own framebuffer at a program-chosen resolution, and using the window would stretch
	 * every scene whenever the player resized the game.
	 *
	 * Perspective reads {@code proj[1]} as a vertical field of view in DEGREES and orthographic
	 * reads it as a half-height in scene units — the two meanings the reserved {@code __proj}
	 * entry carries, discriminated by {@code proj[0]}.
	 */
	public static double[] frustumExtents(double[] proj, int width, int height) {
		double aspect = width / (double) height;
		double top;
		if (proj[0] == ServerScene.PROJECTION_ORTHO) {
			top = proj[1];
		} else {
			top = proj[2] * Math.tan(proj[1] * 0.5 * Math.PI / 180.0);
		}
		return new double[] { top * aspect, top };
	}

	/** Row-major 3x3 multiply, {@code a * b}. */
	private static double[][] multiply3(double[][] a, double[][] b) {
		double[][] o = new double[3][3];
		for (int r = 0; r < 3; r++) {
			for (int c = 0; c < 3; c++) {
				o[r][c] = a[r][0] * b[0][c] + a[r][1] * b[1][c] + a[r][2] * b[2][c];
			}
		}
		return o;
	}

	/** Apply a column-major 4x4 to a point, for tests and for reasoning about what it does. */
	public static double[] apply(float[] m, double x, double y, double z) {
		return new double[] {
			m[0] * x + m[4] * y + m[8] * z + m[12],
			m[1] * x + m[5] * y + m[9] * z + m[13],
			m[2] * x + m[6] * y + m[10] * z + m[14],
		};
	}
}
