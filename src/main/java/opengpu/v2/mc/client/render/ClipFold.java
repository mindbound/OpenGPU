package opengpu.v2.mc.client.render;

/**
 * The arithmetic behind {@code OP_CLIP}, kept GL-free so it can be pinned by a JVM test — the
 * NodeFold pattern (ANIM-10). {@code Canvas2dRenderer} owns the GL calls and the clip state; this
 * class owns the three decisions that could be wrong in a way no in-game look would show:
 *
 * <ol>
 * <li>{@link #bounds}: a canvas-local rectangle becomes a SCENE-space axis-aligned box through
 *     the effective affine current when the op replays — the same map every following vertex
 *     goes through. Under translate/scale the box is the rectangle; under rotation or shear it
 *     is the bounding box of the four mapped corners, which never clips more than asked.</li>
 * <li>{@link #intersect}: nested clips compose by intersection, and an empty clip stays empty
 *     under intersection with anything (x1 &lt;= x0 is preserved by max/min).</li>
 * <li>{@link #scissor}: the box becomes {@code glScissor} arguments — pixel-centre rounding so
 *     that {@code clip(r)} admits exactly the pixels {@code filledRectangle(r)} paints, clamped
 *     to the framebuffer, and y-flipped because the scene is y-down under
 *     {@code glOrtho(0, w, h, 0)} while {@code glScissor} counts rows from the bottom.</li>
 * </ol>
 *
 * Rectangles are {@code double[4] = {x0, y0, x1, y1}} half-open on the right and bottom; a
 * rectangle with {@code x1 <= x0} or {@code y1 <= y0} is EMPTY and clips everything.
 */
public final class ClipFold {
	private ClipFold() {}

	/**
	 * Scene-space bounding box of the canvas-local rectangle {@code (x, y, w, h)} under
	 * {@code m}, into {@code out}. A non-positive {@code w} or {@code h} yields the empty box
	 * {@code {0, 0, 0, 0}}: a collapsed layout clips everything rather than erroring, and it
	 * does so BEFORE the corners are mapped, so a negative width cannot flip into a positive one
	 * through the min/max below.
	 */
	public static void bounds(NodeFold.Affine m, double x, double y, double w, double h,
			double[] out) {
		if (!(w > 0) || !(h > 0)) {
			out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 0;
			return;
		}
		double ax = m.tx(x, y), ay = m.ty(x, y);
		double bx = m.tx(x + w, y), by = m.ty(x + w, y);
		double cx = m.tx(x, y + h), cy = m.ty(x, y + h);
		double dx = m.tx(x + w, y + h), dy = m.ty(x + w, y + h);
		out[0] = Math.min(Math.min(ax, bx), Math.min(cx, dx));
		out[1] = Math.min(Math.min(ay, by), Math.min(cy, dy));
		out[2] = Math.max(Math.max(ax, bx), Math.max(cx, dx));
		out[3] = Math.max(Math.max(ay, by), Math.max(cy, dy));
	}

	/** {@code a = a ∩ b}, in place. Emptiness of either side is preserved. */
	public static void intersect(double[] a, double[] b) {
		a[0] = Math.max(a[0], b[0]);
		a[1] = Math.max(a[1], b[1]);
		a[2] = Math.min(a[2], b[2]);
		a[3] = Math.min(a[3], b[3]);
	}

	/**
	 * {@code glScissor} arguments {@code {x, y, width, height}} for {@code rect} on a
	 * {@code fboWidth x fboHeight} framebuffer whose scene rows run DOWNWARD, into {@code out}.
	 * An empty result — the box was empty, or lies entirely off the framebuffer — is
	 * {@code {0, 0, 0, 0}}, which GL accepts and which admits no fragment.
	 *
	 * Pixel column {@code i} is inside iff its centre is: {@code x0 <= i + 0.5 < x1}, i.e.
	 * {@code i} in {@code [ceil(x0 - 0.5), ceil(x1 - 0.5))}. That is the rasteriser's rule for
	 * an axis-aligned filled quad wherever an edge does NOT fall exactly on a pixel centre, so a
	 * clip and a fill of the same rectangle agree pixel for pixel on such edges. On a tie (an
	 * edge with fraction exactly .5) GL leaves the side implementation-defined; OpenGPU's own
	 * rule here is left/top in, right/bottom out, and the rasteriser may differ by that one
	 * row or column. Scene rows {@code [yi0, yi1)} occupy GL rows {@code [h - yi1, h - yi0)}.
	 */
	public static void scissor(double[] rect, int fboWidth, int fboHeight, int[] out) {
		int xi0 = clamp(ceilHalf(rect[0]), 0, fboWidth);
		int xi1 = clamp(ceilHalf(rect[2]), 0, fboWidth);
		int yi0 = clamp(ceilHalf(rect[1]), 0, fboHeight);
		int yi1 = clamp(ceilHalf(rect[3]), 0, fboHeight);
		if (xi1 <= xi0 || yi1 <= yi0) {
			out[0] = 0; out[1] = 0; out[2] = 0; out[3] = 0;
			return;
		}
		out[0] = xi0;
		out[1] = fboHeight - yi1;
		out[2] = xi1 - xi0;
		out[3] = yi1 - yi0;
	}

	/** {@code ceil(v - 0.5)}: the first pixel index whose centre is at or past {@code v}. */
	static int ceilHalf(double v) {
		// A double far outside int range saturates on the cast, and the clamp above takes it
		// from there; NaN cannot reach here (both doors reject non-finite arguments).
		return (int) Math.ceil(v - 0.5);
	}

	private static int clamp(int v, int lo, int hi) {
		return v < lo ? lo : v > hi ? hi : v;
	}
}
