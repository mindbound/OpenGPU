package opengpu.v2.mc.client.render;

/**
 * THE NODE FOLD — ANIM-10. How a parent group's <i>displayed</i> values reach a child.
 *
 * <h2>Why this is its own class</h2>
 *
 * {@link Canvas2dRenderer} says of itself "NOT unit-testable: Canvas2dRenderer needs a GL context,
 * so no JVM test can load it. Verified by reading and in-game." That is true of the <b>class</b> and
 * was never true of this <b>arithmetic</b> — the affine and the fold touch no GL at all. The
 * consequence was that the parent-to-child fold, which decides where every child of every group is
 * drawn, was pinned by nothing: no unit test, no golden vector, only a play-test. This codebase has
 * already recorded what a clean play-test is worth on state-machine code.
 *
 * Extracted so ANIM-10's conformance vectors are real JVM tests today, with composed values supplied
 * by hand and no animator anywhere. That was originally because the animator surface was shut; the
 * surface opened on 2026-08-13 and the vectors still supply values by hand, which is now a CHOICE
 * rather than a constraint — the fold's arithmetic is what these pin, and feeding it from a running
 * program would test the program instead. Nothing here consumes animator output yet in any case:
 * that is the evaluation overlay, Phase 3.3.
 *
 * <h2>THE FOUR-TERM ORDER WAS NEVER FOUR FACTORS</h2>
 *
 * ANIM-10 asks for the order of "parent server, parent animator, child server, child animator" to be
 * pinned, and observes that four terms admit 24 orderings. <b>The renderer cannot build 24 of
 * them.</b> A node's server base and its animator output fuse <i>per property</i> — {@code +} for
 * translate and rotate, {@code x} for scale, per {@code OcslCompose} — <i>before</i> any matrix op is
 * applied, and the fused scalars then go through one {@code T -> R -> S}. So the structure is two
 * factors, not four:
 *
 * <pre>
 *   M_scene = [ P_srv (+/x) P_anim ] . [ C_srv (+/x) C_anim ]
 *             \_____ one T.R.S _____/   \_____ one T.R.S _____/
 * </pre>
 *
 * and the surviving order — parent fully composed, then child fully composed — is exactly ANIM-10's
 * mirror rule. It is free and it is unforgeable: {@link #foldTransform} lays the parent's whole
 * {@code T.R.S} onto the accumulator before the child's first op, and there is no representation
 * anywhere of a "parent contribution" that could be applied in another position.
 *
 * <b>A consequence worth stating because it is not obvious:</b> the child's translation — its own
 * base <i>and</i> its animator's offset alike — inherits the parent's rotation and scale.
 * {@code S} sits to the right of {@code R}, so a child offset is scaled in the parent's unrotated
 * axes and only then rotated: {@code offset_scene = R_p(S_p(cx, cy))}. A group at
 * {@code rot = pi/2, sx = 2} puts a child at {@code (10, 0)} at scene offset {@code (0, 20)}.
 *
 * <h2>Tint: the child's OWN factor, times the parent's DISPLAYED factor</h2>
 *
 * ANIM-10 pins that an animator's tint output replaces the owning node's <b>own</b> factor rather
 * than the composed result — the reading that keeps the group feature working, because the other one
 * lets a single animated child stay lit inside a faded group.
 *
 * <b>The short-circuit is gone, and that is the fix rather than a tidy.</b> This fold used to read
 * {@code if (parent != null && parent.tint != 0xFFFFFFFF)}, testing the parent's <i>raw packed
 * field</i>. Harmless while the field was the whole truth; a correctness hole the moment a parent's
 * displayed tint can differ from it, because a group whose server tint is white and whose animator
 * fades it to half alpha would take the early exit and <b>contribute nothing to any child</b> — a
 * property that converges perfectly across the wire and renders nothing, which is the exact defect
 * {@code beginNode}'s own comment was written to refuse. Removing it is bit-identical today: a white
 * parent multiplies all four channels by exactly {@code 255/255 = 1.0}.
 *
 * <h2>What this fold deliberately does NOT cover</h2>
 *
 * <ul>
 * <li><b>{@code z} and {@code visible} are carved out BY NAME.</b> ANIM-10 lists the visibility gate
 *     and the z anchor among the consumers that must read the composed value. Neither can: they are
 *     reserved-but-not-ownable in v1, so {@code SurfaceTable.propertyType} returns null and
 *     {@code OcslCompose.ruleFor} returns -1 for them — there is no composed value to read. They
 *     stay raw, and {@link NodeFoldTest} pins that {@code compose} refuses them, so the carve-out is
 *     enforced rather than described.</li>
 * <li><b>{@code tz}, {@code sz} and {@code rot3d} have no consumer at all.</b> The whole transform
 *     path is 2D and five wide — {@code SceneNode} carries {@code x, y, rot, sx, sy},
 *     {@code NodeInterpolator} is {@code FIELDS = 5}, and the affine below is 2x3 with no z. ANIM-3
 *     pinned composition equations for three properties nothing can display. ANIM-10 therefore binds
 *     the <b>six 2D properties</b>, and the gap is a stated scope rather than an oversight; a test
 *     fails the moment a 3D transform field appears on {@code SceneNode}, because at that point this
 *     fold and the interpolator both have to widen together.</li>
 * </ul>
 */
public final class NodeFold {
	private NodeFold() {}

	/** Row-major 2D affine: {@code x' = a*x + c*y + e; y' = b*x + d*y + f}. */
	public static final class Affine {
		public double a = 1, b = 0, c = 0, d = 1, e = 0, f = 0;

		public void set(Affine o) {
			a = o.a; b = o.b; c = o.c; d = o.d; e = o.e; f = o.f;
		}

		public void identity() {
			a = 1; b = 0; c = 0; d = 1; e = 0; f = 0;
		}

		/**
		 * POST-multiply, which is what makes the fold order readable as application order.
		 *
		 * The linear part already present multiplies the new offset — that one line is why a child's
		 * translation inherits its parent's rotation and scale.
		 */
		public void translate(double dx, double dy) {
			e += a * dx + c * dy;
			f += b * dx + d * dy;
		}

		public void rotate(double rad) {
			double cos = Math.cos(rad), sin = Math.sin(rad);
			double na = a * cos + c * sin;
			double nb = b * cos + d * sin;
			double nc = -a * sin + c * cos;
			double nd = -b * sin + d * cos;
			a = na; b = nb; c = nc; d = nd;
		}

		public void scale(double sx, double sy) {
			a *= sx; b *= sx; c *= sy; d *= sy;
		}

		public double tx(double x, double y) {
			return a * x + c * y + e;
		}

		public double ty(double x, double y) {
			return b * x + d * y + f;
		}
	}

	// A node's displayed transform, in the order the affine consumes it.
	public static final int TRS_X = 0;
	public static final int TRS_Y = 1;
	public static final int TRS_ROT = 2;
	public static final int TRS_SX = 3;
	public static final int TRS_SY = 4;

	/** Five, and the number is the 2D scope above rather than an array size. */
	public static final int TRS_WIDTH = 5;

	/** An untinted node. Every channel is exactly 1.0, so folding it changes nothing. */
	public static final int WHITE = 0xFFFFFFFF;

	public static final int TINT_R = 0;
	public static final int TINT_G = 1;
	public static final int TINT_B = 2;
	public static final int TINT_A = 3;

	/**
	 * Compose a child's scene matrix from its parent's displayed TRS and its own.
	 *
	 * Both arguments are <b>displayed</b> values — server base already composed with any animator
	 * output, and already interpolated. This function is the fold and nothing else; it deliberately
	 * cannot see a {@code SceneNode}, so there is no way for it to reach past its arguments to a raw
	 * field, which is precisely how the tint short-circuit went wrong.
	 *
	 * @param parentTrs the parent's displayed TRS, or null for an unparented node
	 */
	public static void foldTransform(double[] parentTrs, double[] childTrs, Affine out) {
		out.identity();
		if (parentTrs != null) {
			apply(parentTrs, out);
		}
		apply(childTrs, out);
	}

	private static void apply(double[] trs, Affine out) {
		out.translate(trs[TRS_X], trs[TRS_Y]);
		out.rotate(trs[TRS_ROT]);
		out.scale(trs[TRS_SX], trs[TRS_SY]);
	}

	/**
	 * The four colour multipliers a node draws with: its own displayed tint times its parent's.
	 *
	 * UNCONDITIONAL. Pass {@link #WHITE} for an unparented node rather than skipping the call — the
	 * skip is what broke, and a multiply by 1.0 costs nothing worth a branch that can be wrong.
	 *
	 * @param childTint the child's DISPLAYED tint; an animator's output has already replaced the
	 *        node's own factor by the time it arrives here
	 * @param parentTint the parent's DISPLAYED tint, or {@link #WHITE}
	 */
	public static void foldTint(int childTint, int parentTint, double[] out) {
		out[TINT_R] = channel(childTint, 16) * channel(parentTint, 16);
		out[TINT_G] = channel(childTint, 8) * channel(parentTint, 8);
		out[TINT_B] = channel(childTint, 0) * channel(parentTint, 0);
		out[TINT_A] = channel(childTint, 24) * channel(parentTint, 24);
	}

	private static double channel(int packed, int shift) {
		return (packed >>> shift & 0xFF) / 255.0;
	}
}
