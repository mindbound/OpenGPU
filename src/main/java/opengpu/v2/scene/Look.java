package opengpu.v2.scene;

/**
 * The server-side 3D orientation math (v10, C1.2): lookAt and quaternion hygiene. DESIGN's
 * commitment is "the server does the math" — both sides then replicate the RESULT, so no
 * client ever re-derives a rotation and the two sides cannot disagree about trigonometry.
 *
 * <h2>The frozen convention (FIELD-TEST-API9's oracles pin these numbers)</h2>
 * A node looks down its LOCAL -Z (the GL convention). Coordinates are PARENT-space — world
 * space for an unparented node. Wire component order qx, qy, qz, qw (ascending PROP_Q* bits).
 * Identity example: eye (0,0,0), target (0,0,-1), up (0,1,0) yields (0, 0, 0, 1). Second
 * oracle: target (1,0,0) with up (0,1,0) yields (0, -0.7071068, 0, +0.7071068) — -90 degrees
 * about +Y. Quaternions leave here normalized with qw &gt;= 0 (the sign canonicalisation that
 * keeps field-test oracles single-valued; q and -q are the same rotation).
 */
public final class Look {
	private Look() {}

	/** Below this magnitude a quaternion is refused as degenerate rather than normalized. */
	public static final double QUAT_EPSILON = 1e-6;

	/**
	 * Normalize and sign-canonicalise a caller-supplied quaternion. Refuses a near-zero one:
	 * normalizing garbage would manufacture a confident rotation from noise.
	 */
	public static double[] normalize(double qx, double qy, double qz, double qw) {
		double len = Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
		if (!(len > QUAT_EPSILON))
			throw new IllegalArgumentException("quaternion magnitude " + len
					+ " is below " + QUAT_EPSILON + "; pass a non-degenerate rotation"
					+ " (identity is 0,0,0,1)");
		double s = 1.0 / len;
		double x = qx * s, y = qy * s, z = qz * s, w = qw * s;
		if (w < 0) {
			x = -x;
			y = -y;
			z = -z;
			w = -w;
		}
		return new double[] { x, y, z, w };
	}

	/**
	 * The lookAt quaternion: orient a node at {@code eye} so its local -Z faces {@code target}
	 * with {@code up} steadying the roll. Returns {qx, qy, qz, qw}, normalized, qw &gt;= 0.
	 *
	 * Degenerate inputs are REFUSED, and the second refusal deliberately catches two cases in
	 * one check — up parallel to the view direction AND a zero up — because |forward x up|
	 * vanishes for both. The message carries the remedy for the case every top-down-map author
	 * hits first (looking straight down with the default up).
	 */
	public static double[] quat(double ex, double ey, double ez,
			double tx, double ty, double tz, double ux, double uy, double uz) {
		double fx = tx - ex, fy = ty - ey, fz = tz - ez;
		double flen = Math.sqrt(fx * fx + fy * fy + fz * fz);
		if (!(flen > QUAT_EPSILON))
			throw new IllegalArgumentException("eye and target coincide; there is no direction"
					+ " to face");
		fx /= flen;
		fy /= flen;
		fz /= flen;
		// right = forward x up
		double sx = fy * uz - fz * uy;
		double sy = fz * ux - fx * uz;
		double sz = fx * uy - fy * ux;
		double slen = Math.sqrt(sx * sx + sy * sy + sz * sz);
		if (!(slen > QUAT_EPSILON))
			throw new IllegalArgumentException("looking parallel to up (or up is zero); pass a"
					+ " different up, e.g. (0,0,-1) for a top-down view");
		sx /= slen;
		sy /= slen;
		sz /= slen;
		// true up = right x forward (orthonormal by construction)
		double upx = sy * fz - sz * fy;
		double upy = sz * fx - sx * fz;
		double upz = sx * fy - sy * fx;
		// Rotation matrix columns are the node's local axes in parent space:
		// +X -> right, +Y -> true up, +Z -> -forward (looks down -Z).
		double m00 = sx, m01 = upx, m02 = -fx;
		double m10 = sy, m11 = upy, m12 = -fy;
		double m20 = sz, m21 = upz, m22 = -fz;
		// Shepperd's method: branch on the largest diagonal term for numerical stability.
		double qx, qy, qz, qw;
		double trace = m00 + m11 + m22;
		if (trace > 0) {
			double s = Math.sqrt(trace + 1.0) * 2.0;
			qw = 0.25 * s;
			qx = (m21 - m12) / s;
			qy = (m02 - m20) / s;
			qz = (m10 - m01) / s;
		} else if (m00 > m11 && m00 > m22) {
			double s = Math.sqrt(1.0 + m00 - m11 - m22) * 2.0;
			qw = (m21 - m12) / s;
			qx = 0.25 * s;
			qy = (m01 + m10) / s;
			qz = (m02 + m20) / s;
		} else if (m11 > m22) {
			double s = Math.sqrt(1.0 + m11 - m00 - m22) * 2.0;
			qw = (m02 - m20) / s;
			qx = (m01 + m10) / s;
			qy = 0.25 * s;
			qz = (m12 + m21) / s;
		} else {
			double s = Math.sqrt(1.0 + m22 - m00 - m11) * 2.0;
			qw = (m10 - m01) / s;
			qx = (m02 + m20) / s;
			qy = (m12 + m21) / s;
			qz = 0.25 * s;
		}
		return normalize(qx, qy, qz, qw);
	}
}
