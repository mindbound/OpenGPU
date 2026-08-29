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
 * <li><b>{@code tz}, {@code sz} and {@code rot3d} are CARRIED here as of C1.3.3. Nothing reads
 *     them yet.</b> The record below is eleven wide — {@code SceneNode}'s whole scalar model — and
 *     {@link NodeInterpolator} smooths every slot of it, the quaternion by slerp. No production
 *     reader touches the 3D slots: {@link #apply} below is 2x3 and takes the 2D five, and
 *     {@code AnimatorOverlay} reads TRS records in two more places
 *     ({@code bindParentProperties} and {@code baseOf}) — also the 2D five only. The 3D pass reads {@code SceneNode}'s fields raw
 *     and never sees a record at all. So the smoothed 3D slots are computed and discarded until
 *     group B routes {@code Mesh3dPass} through here. The widening gave the 3D properties a
 *     CARRIER, and a carrier is not a consumer.
 *     <br><b>The reader list is spelled out because an earlier draft said "the only production
 *     reader is {@code apply}"</b> — which is the same failure to enumerate second readers that
 *     produced this increment's headline defect, committed in the sentence correcting it.
 *     <br>The affine here will stay 2x3 regardless, because a canvas has no z to place; the
 *     mirror-image case is {@code rot}, a 2D angle the 3D path never reads. ANIM-10 therefore
 *     still binds the <b>six 2D properties</b> for THIS fold.</li>
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

	// A node's displayed transform. The 2D five come first and KEEP THEIR INDICES: this record is
	// written in one place and read in several, so renumbering it would be a silent change to every
	// reader at once.
	public static final int TRS_X = 0;
	public static final int TRS_Y = 1;
	public static final int TRS_ROT = 2;
	public static final int TRS_SX = 3;
	public static final int TRS_SY = 4;

	// v10's 3D six, carried since C1.3.3. The order is SceneNode's own field order, which is also
	// the wire's ascending PROP_Q* order — so the places that write this record can be read against
	// each other line by line instead of by cross-referencing three numberings.
	public static final int TRS_TZ = 5;
	public static final int TRS_SZ = 6;
	public static final int TRS_QX = 7;
	public static final int TRS_QY = 8;
	public static final int TRS_QZ = 9;
	public static final int TRS_QW = 10;

	/**
	 * Eleven — the 2D five plus v10's 3D six — and the number is still a SCOPE rather than an
	 * array size. It is the width of the displayed-transform record, and every array carrying one
	 * MUST be sized from here so that its writer and its readers cannot disagree about how wide it
	 * is — a rule enforced by nothing but review, which is why three literal 5-wide arrays survived
	 * in {@code AnimatorOverlayTest} through this increment's first draft. They passed only because
	 * {@code overlayTransform} writes the 2D five; the first 3D property it learns to substitute
	 * would have turned all three into an {@code ArrayIndexOutOfBoundsException}.
	 *
	 * <b>A zeroed array of this width is NOT the identity transform.</b> {@code sx}, {@code sy},
	 * {@code sz} and the quaternion's {@code w} all default to 1 on a
	 * {@link opengpu.v2.scene.SceneNode} and to 0 in a fresh {@code double[]}, so a
	 * partially-filled record scales the node away, and would hand the 3D path a degenerate
	 * quaternion once group B routes it through here.
	 * {@link #identity(double[])} writes that down once instead of leaving it to be remembered.
	 */
	public static final int TRS_WIDTH = 11;

	/**
	 * Write the identity transform into a TRS record — the value {@code new double[TRS_WIDTH]}
	 * looks like it already holds and does not.
	 *
	 * NO PRODUCTION CALLER TODAY, and that is worth stating rather than leaving to be discovered:
	 * every production site fills all eleven slots from {@code NodeInterpolator}. The callers are
	 * both test helpers — {@code NodeFoldTest.trs} and {@code AnimatorOverlayTest.trsOf}. (An
	 * earlier draft said "the only caller", and the second one was added by the same fix round
	 * that wrote the sentence. A cardinal has to be counted at the moment it is written, not
	 * remembered from ten minutes earlier.) It exists for the sites that build a record by hand —
	 * group B's tests among them — and because writing the hazard down once beats each of them
	 * rediscovering that {@code sz} and {@code qw} default to the wrong number.
	 */
	public static void identity(double[] trs) {
		trs[TRS_X] = 0;
		trs[TRS_Y] = 0;
		trs[TRS_ROT] = 0;
		trs[TRS_SX] = 1;
		trs[TRS_SY] = 1;
		trs[TRS_TZ] = 0;
		trs[TRS_SZ] = 1;
		trs[TRS_QX] = 0;
		trs[TRS_QY] = 0;
		trs[TRS_QZ] = 0;
		trs[TRS_QW] = 1;
	}

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
	 * THE PACKED FORM, kept for the conformance vectors and for any caller whose tints are both
	 * still packed ints. Production folds the unpacked form below since 3.3b: an animator's
	 * composed tint is continuous and has no packed representation, so quantizing it to 8 bits
	 * here would turn a smooth fade into 256 steps for no reason. The two forms MUST agree —
	 * {@code NodeFoldTest} pins that they do, which is what allows the rule to exist twice.
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

	/**
	 * The fold on already-unpacked factors — the form the animator path uses. Same rule as the
	 * packed form: child times parent, per channel, unconditionally.
	 */
	public static void foldTint(double[] childFactor, double[] parentFactor, double[] out) {
		out[TINT_R] = childFactor[TINT_R] * parentFactor[TINT_R];
		out[TINT_G] = childFactor[TINT_G] * parentFactor[TINT_G];
		out[TINT_B] = childFactor[TINT_B] * parentFactor[TINT_B];
		out[TINT_A] = childFactor[TINT_A] * parentFactor[TINT_A];
	}

	/** Unpack a packed ARGB tint into its four 0..1 channel factors, in TINT_* order. */
	public static void unpack(int packedTint, double[] out) {
		out[TINT_R] = channel(packedTint, 16);
		out[TINT_G] = channel(packedTint, 8);
		out[TINT_B] = channel(packedTint, 0);
		out[TINT_A] = channel(packedTint, 24);
	}

	private static double channel(int packed, int shift) {
		return (packed >>> shift & 0xFF) / 255.0;
	}
}
