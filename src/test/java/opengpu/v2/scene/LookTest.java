package opengpu.v2.scene;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * The lookAt convention, pinned against the SAME numeric oracles FIELD-TEST-API9 registers —
 * so the desk test, the field probe and C1.3's renderer all answer to one set of numbers.
 */
public class LookTest {

	private static final double EPS = 1e-9;

	@Test
	public void theIdentityOracle() {
		// Looking down -Z from the origin with +Y up IS the identity orientation.
		assertArrayEquals(new double[] { 0, 0, 0, 1 },
				Look.quat(0, 0, 0, 0, 0, -1, 0, 1, 0), EPS);
	}

	@Test
	public void theMinusNinetyAboutYOracle() {
		// Looking down +X: -90 degrees about +Y. The signs are the -Z-forward convention's —
		// a +90 result would mean the convention flipped.
		double h = Math.sqrt(0.5);
		assertArrayEquals(new double[] { 0, -h, 0, h },
				Look.quat(0, 0, 0, 1, 0, 0, 0, 1, 0), EPS);
	}

	@Test
	public void everyColdShepperdBranchHasItsOwnExactOracle() {
		// The trace>0 branch is what the two oracles above and the grid below drive; the three
		// diagonal branches only fire near 180-degree rotations, and normalize() would launder
		// a sign error in any of them into a unit quaternion the grid's assertions cannot see.
		// One exact 180-degree oracle per cold branch:
		// m00-largest: 180 about X — look BEHIND with an inverted up.
		assertArrayEquals(new double[] { 1, 0, 0, 0 },
				Look.quat(0, 0, 0, 0, 0, 1, 0, -1, 0), EPS);
		// m11-largest: 180 about Y — look behind with the normal up.
		assertArrayEquals(new double[] { 0, 1, 0, 0 },
				Look.quat(0, 0, 0, 0, 0, 1, 0, 1, 0), EPS);
		// m22-largest: 180 about Z — look FORWARD with an inverted up.
		assertArrayEquals(new double[] { 0, 0, 1, 0 },
				Look.quat(0, 0, 0, 0, 0, -1, 0, -1, 0), EPS);
	}

	@Test
	public void everyResultIsNormalizedWithNonNegativeW() {
		// A grid of directions; each quat must be unit length with qw >= 0 (the sign
		// canonicalisation that keeps oracles single-valued).
		for (int i = 0; i < 8; i++) {
			double a = i * Math.PI / 4 + 0.1;
			double[] q = Look.quat(1, 2, 3, 1 + Math.cos(a), 2 + 0.5, 3 + Math.sin(a), 0, 1, 0);
			double len = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
			assertEquals("unit length at step " + i, 1.0, len, EPS);
			assertTrue("qw >= 0 at step " + i, q[3] >= 0);
		}
	}

	@Test
	public void eyeEqualsTargetIsRefused() {
		try {
			Look.quat(5, 5, 5, 5, 5, 5, 0, 1, 0);
			fail("no direction to face");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("coincide"));
		}
	}

	@Test
	public void theStraightDownTrapIsRefusedWithTheRemedy() {
		// The first camera every top-down-map author tries: looking straight down with the
		// default up. The message must carry the remedy, not just the refusal.
		try {
			Look.quat(0, 0, 0, 0, -1, 0, 0, 1, 0);
			fail("up parallel to view direction");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("(0,0,-1)"));
		}
		// A zero up dies at the SAME check (|f x up| = 0 covers both).
		try {
			Look.quat(0, 0, 0, 0, 0, -1, 0, 0, 0);
			fail("zero up");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("up"));
		}
	}

	@Test
	public void normalizeRefusesNearZeroAndCanonicalisesSign() {
		try {
			Look.normalize(0, 0, 0, 0);
			fail("a zero quaternion is not a rotation");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("1.0E-6"));
		}
		// q and -q are the same rotation; the negative-w form flips whole.
		assertArrayEquals(new double[] { 0, 0, 0, 1 }, Look.normalize(0, 0, 0, -1), EPS);
		double h = Math.sqrt(0.5);
		assertArrayEquals(new double[] { -h, 0, 0, h }, Look.normalize(h, 0, 0, -h), EPS);
	}
}
