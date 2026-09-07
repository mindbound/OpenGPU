package opengpu.v2.mc.client.render;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Conformance vectors for the clip arithmetic behind OP_CLIP — the NodeFold pattern: the
 * renderer cannot load in a JVM test, so the decisions that would be wrong INVISIBLY are pulled
 * out and pinned here. Every vector names the wrong answer it excludes, as numbers, because
 * every candidate reading coincides on an unrotated, unit-scaled, integer-aligned rectangle —
 * which is exactly what an in-game look at a plain clipped panel shows.
 *
 * Two readings are excluded throughout: the y-flip forgotten (scissor rows counted from the top)
 * and rounding by truncation or by outward expansion rather than at pixel centres. A third — the
 * rect mapped through the NODE transform only (FILL's map) rather than the effective one — is NOT
 * excludable here: bounds() takes its affine as a parameter, so every vector passes whichever the
 * renderer hands it. That choice is pinned only by reading Canvas2dRenderer's OP_CLIP case
 * ({@code ClipFold.bounds(effective, ...)}) and by cliptest panel C in the field.
 */
public strictfp class ClipFoldTest {

	private static double[] bounds(NodeFold.Affine m, double x, double y, double w, double h) {
		double[] out = new double[4];
		ClipFold.bounds(m, x, y, w, h, out);
		return out;
	}

	private static int[] scissor(double[] rect, int w, int h) {
		int[] out = new int[4];
		ClipFold.scissor(rect, w, h, out);
		return out;
	}

	private static void assertBox(String what, double x0, double y0, double x1, double y1,
			double[] got) {
		assertEquals(what + " x0", x0, got[0], 1e-9);
		assertEquals(what + " y0", y0, got[1], 1e-9);
		assertEquals(what + " x1", x1, got[2], 1e-9);
		assertEquals(what + " y1", y1, got[3], 1e-9);
	}

	@Test
	public void identityRectIsItselfAndTheScissorFlipsY() {
		double[] box = bounds(new NodeFold.Affine(), 10, 20, 30, 40);
		assertBox("identity", 10, 20, 40, 60, box);
		// Scene rows 20..60 of a 100-row framebuffer are GL rows 40..80: y = 100 - 60.
		// The wrong answer excluded: {10, 20, 30, 40} — rows counted from the top.
		assertArrayEquals(new int[] { 10, 40, 30, 40 }, scissor(box, 100, 100));
	}

	@Test
	public void translateAndScaleAreExact() {
		NodeFold.Affine m = new NodeFold.Affine();
		m.translate(5, 7);
		m.scale(2, 3);
		// x' = 2x + 5, y' = 3y + 7: (1,1)-(5,3) maps to (7,10)-(15,16).
		double[] box = bounds(m, 1, 1, 4, 2);
		assertBox("translate+scale", 7, 10, 15, 16, box);
		// Excluded: {1, 1, 5, 3} (rect not mapped at all) and {12, 24, 20, 30} (the ops folded
		// in the other order — post-multiplication means the offset here is NOT scaled).
		assertArrayEquals(new int[] { 7, 84, 8, 6 }, scissor(box, 100, 100));
	}

	@Test
	public void aNegativeScaleStillProducesAnOrderedBox() {
		NodeFold.Affine m = new NodeFold.Affine();
		m.translate(100, 0);
		m.scale(-1, 1);
		// x' = 100 - x: the rect 10..15 lands at 85..90, and x0 < x1 must still hold.
		double[] box = bounds(m, 10, 0, 5, 5);
		assertBox("mirrored", 85, 0, 90, 5, box);
		assertArrayEquals(new int[] { 85, 95, 5, 5 }, scissor(box, 100, 100));
	}

	@Test
	public void rotationClipsToTheBoundingBoxOfTheRotatedRect() {
		NodeFold.Affine m = new NodeFold.Affine();
		m.rotate(Math.PI / 4);
		// A 10x10 square at the origin rotated 45 degrees: corners at (0,0), (7.07,7.07),
		// (-7.07,7.07) and (0,14.14). The box is a superset — never clips MORE than asked.
		double s = 10 / Math.sqrt(2);
		double[] box = bounds(m, 0, 0, 10, 10);
		assertBox("rotated 45", -s, 0, s, 2 * s, box);
		// Excluded: {0, 0, 10, 10} — the unrotated rect, i.e. rotation ignored.
		assertTrue("the box is wider than the rect", box[2] - box[0] > 10);
	}

	@Test
	public void nestedClipsIntersect() {
		double[] a = { 10, 10, 50, 50 };
		ClipFold.intersect(a, new double[] { 30, 0, 80, 40 });
		assertBox("intersection", 30, 10, 50, 40, a);
	}

	@Test
	public void anEmptyClipStaysEmptyUnderIntersection() {
		// The empty box {0,0,0,0} intersected with a box that CONTAINS the origin: max/min
		// keeps x1 <= x0, so it stays empty rather than becoming the containing box.
		double[] a = bounds(new NodeFold.Affine(), 10, 10, 0, 10);
		assertBox("empty from w = 0", 0, 0, 0, 0, a);
		ClipFold.intersect(a, new double[] { -10, -10, 100, 100 });
		assertArrayEquals("nothing is admitted", new int[] { 0, 0, 0, 0 }, scissor(a, 100, 100));
		// And the other order: a live clip intersected with an empty one is empty.
		double[] b = { 10, 10, 50, 50 };
		ClipFold.intersect(b, a);
		assertArrayEquals(new int[] { 0, 0, 0, 0 }, scissor(b, 100, 100));
	}

	@Test
	public void aNonPositiveSizeClipsEverythingRatherThanFlipping() {
		// Excluded: {5, 0, 10, 5} — a negative width read as "5 wide, leftward", which is what
		// min/max over the corners would produce if the size were not checked first.
		assertBox("w = -5", 0, 0, 0, 0, bounds(new NodeFold.Affine(), 10, 0, -5, 5));
		assertBox("h = 0", 0, 0, 0, 0, bounds(new NodeFold.Affine(), 10, 0, 5, 0));
		assertArrayEquals(new int[] { 0, 0, 0, 0 },
				scissor(bounds(new NodeFold.Affine(), 10, 0, -5, 5), 100, 100));
	}

	@Test
	public void offFramebufferIsClampedAndEntirelyOffIsEmpty() {
		// Partly off the top-left: the clamp keeps the visible 30x30 at the corner.
		assertArrayEquals(new int[] { 0, 70, 30, 30 },
				scissor(new double[] { -20, -20, 30, 30 }, 100, 100));
		// Partly off the bottom-right.
		assertArrayEquals(new int[] { 90, 0, 10, 10 },
				scissor(new double[] { 90, 90, 130, 130 }, 100, 100));
		// Entirely off: both edges clamp to the same value, so nothing is admitted.
		assertArrayEquals(new int[] { 0, 0, 0, 0 },
				scissor(new double[] { 200, 200, 300, 300 }, 100, 100));
		// Values a double can hold but an int cannot saturate on the cast and then clamp,
		// rather than wrapping into a plausible box.
		assertArrayEquals(new int[] { 0, 0, 100, 100 },
				scissor(new double[] { -1e300, -1e300, 1e300, 1e300 }, 100, 100));
	}

	@Test
	public void edgesRoundAtPixelCentresSoAClipAndAFillAgree() {
		// Pixel i is in iff x0 <= i + 0.5 < x1. A rect from 10.4 to 20.6 covers centres 10.5
		// through 20.5: pixels 10..20, eleven of them — the same eleven a filled quad of that
		// rect rasterises. Excluded: {10, 21} by outward expansion (floor/ceil) would ALSO give
		// eleven here, so the tie cases below are what separate the rules.
		assertArrayEquals(new int[] { 10, 99, 11, 1 },
				scissor(new double[] { 10.4, 0, 20.6, 1 }, 100, 100));
		// 10.6 excludes pixel 10 (centre 10.5 < 10.6): floor would have admitted it.
		assertArrayEquals(new int[] { 11, 99, 9, 1 },
				scissor(new double[] { 10.6, 0, 20.4, 1 }, 100, 100));
		// Ties: a centre exactly on the left edge is IN, on the right edge OUT — the half-open
		// interval applied to centres. 10.5..20.5 admits pixels 10..19, ten of them.
		assertArrayEquals(new int[] { 10, 99, 10, 1 },
				scissor(new double[] { 10.5, 0, 20.5, 1 }, 100, 100));
		assertEquals(10, ClipFold.ceilHalf(10.5));
		assertEquals(10, ClipFold.ceilHalf(10.4));
		assertEquals(11, ClipFold.ceilHalf(10.6));
		assertEquals(-2, ClipFold.ceilHalf(-2.4));
	}

	@Test
	public void sceneRowsMapToGlRowsFromTheBottom() {
		// The top scene row of a 100-row framebuffer is GL row 99; the bottom one is GL row 0.
		assertArrayEquals(new int[] { 0, 99, 1, 1 }, scissor(new double[] { 0, 0, 1, 1 }, 1, 100));
		assertArrayEquals(new int[] { 0, 0, 1, 1 }, scissor(new double[] { 0, 99, 1, 100 }, 1, 100));
	}

	@Test
	public void theRectIsMappedThroughTheAffineGivenNotAnImplicitOne() {
		// The renderer must hand bounds() the EFFECTIVE affine (node followed by local), not the
		// node's alone as FILL does. This pins that bounds() applies whatever it is given in
		// full: a local translate composed after a node scale moves the box by the SCALED offset.
		NodeFold.Affine effective = new NodeFold.Affine();
		effective.scale(2, 2);      // node
		effective.translate(3, 4);  // local, post-multiplied: offset becomes (6, 8)
		double[] box = bounds(effective, 0, 0, 5, 5);
		// Excluded: {0, 0, 10, 10} (node only) and {3, 4, 13, 14} (translate not scaled).
		assertBox("node then local", 6, 8, 16, 18, box);
	}
}
