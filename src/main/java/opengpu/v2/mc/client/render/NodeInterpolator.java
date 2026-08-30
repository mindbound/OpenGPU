package opengpu.v2.mc.client.render;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneState;
import opengpu.v2.stats.RenderStats;

/**
 * Smooths retained-node transforms across the 20 tps server channel.
 *
 * This is the reason retained nodes exist at all. Node properties land at most once per server
 * tick while the client draws at 60+ fps, so without this a sprite animated from Lua steps
 * visibly — and the only way a program could hide that was to busy-loop without sleeping,
 * burning its whole call budget to raise an update rate it cannot actually raise (the batch
 * seals once per tick regardless). Interpolating here makes 20 Hz updates look like 60 fps
 * motion and costs the program nothing.
 *
 * <h2>Keyframes on the server clock, not a window from arrival</h2>
 * Each node keeps the last two states it was seen in, each stamped with the SERVER TICK that
 * carried it, and rendering samples them at {@link ServerTimeline#renderNanos}. The previous
 * implementation instead lerped over a fixed 50 ms window starting when the batch arrived
 * locally — the naive lerp-from-arrival DESIGN-RENDERER-V2 explicitly warns against. That
 * version is indistinguishable from this one on a LAN and wrong under jitter: a batch 20 ms
 * late compressed a node's motion into 30 ms and the next stretched it, so motion surged and
 * stalled. Replaying against server time means a late batch changes when we learn of a
 * movement, never how fast it appears to happen.
 *
 * CLIENT-ONLY, deliberately. Previous-transform tracking is a presentation concern: putting it
 * on {@link SceneNode} would push it into the shared model, into snapshots via
 * {@code copyStructure()}, and into {@code contentEquals} — where a mirror mid-interpolation
 * would read as diverged from the server.
 */
final class NodeInterpolator {
	// DERIVED FROM NodeFold, not re-numbered. These used to be five independent literals that
	// happened to equal the TRS_* they index, which is one numbering written twice — and the copy
	// that drifts is invisible, because both sides still compile and the fold simply reads the
	// wrong slot. FIELDS derives for a sharper reason still: transformOf writes FIELDS entries into
	// arrays its callers size at TRS_WIDTH, so a disagreement is either an out-of-bounds throw on
	// the render thread or a slot left holding the previous node's value.
	private static final int X = NodeFold.TRS_X, Y = NodeFold.TRS_Y, ROT = NodeFold.TRS_ROT,
			SX = NodeFold.TRS_SX, SY = NodeFold.TRS_SY,
			TZ = NodeFold.TRS_TZ, SZ = NodeFold.TRS_SZ,
			QX = NodeFold.TRS_QX, QY = NodeFold.TRS_QY, QZ = NodeFold.TRS_QZ, QW = NodeFold.TRS_QW;
	private static final int FIELDS = NodeFold.TRS_WIDTH;

	/**
	 * <b>TWO KEYFRAME GROUPS, NOT ONE — and this is a correctness fix, not a tidy.</b>
	 *
	 * A track's keyframe stamps decide the interval every sample divides by. When one pair of
	 * stamps served all eleven scalars, a change to a 3D field ROLLED THE KEYFRAME for the 2D five
	 * as well — and the render clock deliberately runs {@code INTERPOLATION_DELAY_TICKS} behind, so
	 * that roll DISCARDED a 2D window still being replayed. A sprite whose quaternion changed while
	 * it was mid-glide jumped to its destination a tick early and then froze. The values were never
	 * wrong; {@code a} was.
	 *
	 * It defeated the gap rule too. {@link #MAX_GAP_TICKS} measures {@code currTick - prevTick}, so
	 * a node whose {@code tz} churned every tick reported a gap of 1 no matter how long its 2D
	 * position had been still — and a 500-unit jump after a 20-tick idle GLIDED, against DESIGN's
	 * "an idle node that moves after 400 ticks jumps, it does not glide for 20 seconds" quoted
	 * verbatim above.
	 *
	 * The two groups are the two CONSUMERS the record is FOR: the 2D five feed {@code NodeFold}'s
	 * affine today, and the 3D six will feed the model matrix once group B routes
	 * {@code Mesh3dPass} through this record — until then nothing reads them at all, which
	 * {@code NodeFold}'s own reader enumeration states and this sentence asserted the opposite of
	 * in its first draft. The split is still right on the strength of the 2D half alone: fields
	 * consumed by different passes have no reason to share a timeline, and sharing one lets an
	 * invisible change move a visible thing.
	 *
	 * <b>Contiguous by construction.</b> The 2D five are {@code [0, SPLIT)} and the 3D six are
	 * {@code [SPLIT, FIELDS)}, so a group is an {@code arraycopy} range rather than a field list —
	 * which is what keeps {@code rawTransform} the single spelling of the raw read.
	 * {@code NodeFoldTest} pins the PARTITION, not merely the index set — every 2D constant below
	 * {@code SPLIT} and every 3D constant at or above it, compared as a SET against the record's
	 * declared constants. That distinction is load-bearing and was got wrong twice before it was
	 * got right: the index set alone permits swapping {@code TRS_ROT} and {@code TRS_SZ}, which
	 * keeps the set exactly {0..10} with no collisions while leaving slot 2 written by neither
	 * group on the snap and clamp arms — those copy by RANGE — even though the interpolating body,
	 * whose field list is hard-coded, would still lerp {@code rot} with the 2D group's fraction.
	 * The two spellings would then disagree about which slots a group owns, which is the hazard,
	 * and it is worse than a clean reassignment because it depends on which mixed state the node
	 * is in.
	 */
	private static final int G2D = 0, G3D = 1, GROUPS = 2;
	private static final int SPLIT = NodeFold.TRS_TZ;
	private static final int[] GROUP_LO = { 0, SPLIT };
	private static final int[] GROUP_HI = { SPLIT, FIELDS };

	/**
	 * Above this dot product the two keyframes are close enough that the great-circle formula
	 * loses its meaning — {@code sin(theta)} is the divisor and it is heading for zero — so the
	 * component lerp takes over.
	 *
	 * <b>0.9995 is a 3.62 degree separation BETWEEN THE ROTATIONS.</b> The 1.81 degrees is the
	 * QUATERNION half-angle, and the two are worth keeping apart because every other figure in
	 * this class is a rotation angle: a reader re-deriving the constant from "1.81 degrees apart"
	 * lands on 0.99988, not 0.9995. The in-body comment at the {@code acos} says 1.81 correctly,
	 * because it names {@code theta}.
	 *
	 * What the threshold COSTS is 5.8e-5 degrees — the worst rotation-angle disagreement between
	 * the two formulas anywhere on the admitted band. <b>An earlier version of this sentence
	 * priced that against "what a float32 quaternion can express", which was wrong twice.</b> The
	 * gap is 8.5x a float32 ulp at the largest component and 1089x at the component that actually
	 * encodes the angle — and there is no float32 quaternion in this path at all: the wire carries
	 * one double per component and {@link SceneNode} stores doubles. The nearest float32 is
	 * {@code Transform3d.modelMatrix}'s output array, against whose ulp the gap is still 17x too
	 * large. The constant is right and 5.8e-5 degrees is invisible; the reason first given for it
	 * was borrowed from the animator's float32 world, where it would have been true.
	 */
	private static final double SLERP_LINEAR_ABOVE = 0.9995;

	/**
	 * Interpolate only across states this close together in server ticks.
	 *
	 * DESIGN: "Lerp only between states from consecutive server ticks (or within a small
	 * threshold); otherwise snap — an idle node that moves after 400 ticks jumps, it does not
	 * glide for 20 seconds." Without this rule a node that sat still for a minute and then
	 * moved would be lerped across the entire idle span, since its two keyframes really are
	 * that far apart. Three ticks tolerates a program updating slightly slower than every tick
	 * without turning a genuine teleport into a crawl.
	 */
	private static final long MAX_GAP_TICKS = 3;

	private static final class Track {
		final double[] prev = new double[FIELDS];
		final double[] curr = new double[FIELDS];
		/** Stamps PER GROUP — see the GROUPS javadoc for why they are not shared. */
		final long[] prevTick = new long[GROUPS];
		final long[] currTick = new long[GROUPS];
		/** This transition is a jump: a teleport, a resync seam, or too wide a tick gap. */
		final boolean[] snap = new boolean[GROUPS];
		/**
		 * Has this group rolled a keyframe since the node was first seen?
		 *
		 * Diagnostic only — nothing in the interpolation arithmetic reads it. It exists so the
		 * cadence histogram can DROP the first roll, whose gap is the interval from the node
		 * appearing to it first moving rather than a cadence. See {@code RenderStats.keyframeGaps}.
		 */
		final boolean[] rolled = new boolean[GROUPS];
	}

	private final Map<Integer, Track> tracks = new HashMap<Integer, Track>();
	private final ServerTimeline timeline = new ServerTimeline();
	/**
	 * One node's raw transform, read once per node per capture and compared group by group.
	 *
	 * An INSTANCE field rather than a local: {@code capture} runs over every node in the scene on
	 * the render thread, and this class advertises a zero-allocation frame. Single-threaded by the
	 * same rule as every other field here.
	 */
	private final double[] scratch = new double[FIELDS];

	/**
	 * Fold a freshly applied batch in. Call when the mirror reports dirty, BEFORE rendering.
	 *
	 * @param serverTick the tick the batch was sealed on — the x-axis everything below uses.
	 * @param teleported nodes whose change carried PROP_TELEPORT; they snap. Without this a
	 *                   deliberate jump across the screen crawls, which is worse than the
	 *                   stepping this class exists to fix.
	 */
	void capture(SceneState state, long serverTick, long nowNanos, java.util.Set<Integer> teleported) {
		boolean rebased = timeline.onBatch(serverTick, nowNanos);
		for (SceneNode node : state.nodes.values()) {
			Track t = tracks.get(node.id);
			rawTransform(node, scratch);
			if (t == null) {
				// First sight: settle immediately. Lerping a new node from a zeroed transform
				// would fling it in from the origin at scale 0.
				t = new Track();
				System.arraycopy(scratch, 0, t.curr, 0, FIELDS);
				System.arraycopy(scratch, 0, t.prev, 0, FIELDS);
				for (int g = 0; g < GROUPS; g++) {
					t.prevTick[g] = serverTick;
					t.currTick[g] = serverTick;
					t.snap[g] = true;
				}
				tracks.put(node.id, t);
				continue;
			}
			boolean teleport = teleported.contains(Integer.valueOf(node.id));
			for (int g = 0; g < GROUPS; g++) {
				if (rebased) {
					// The clock re-based under us, so the stamps on this node's keyframes no
					// longer describe the same timeline. Interpolating across that seam would
					// sweep the node along an interval that never existed. DESIGN: "Resync
					// always snaps." BOTH groups, because the seam is in the clock they share.
					t.snap[g] = true;
				}
				int lo = GROUP_LO[g], len = GROUP_HI[g] - lo;
				if (equalRange(t.curr, scratch, lo, len)) {
					// Nothing in THIS group moved. Its stamps stay put — which is the whole
					// point: a sibling group rolling its keyframe must not restart this one.
					continue;
				}
				System.arraycopy(t.curr, lo, t.prev, lo, len);
				t.prevTick[g] = t.currTick[g];
				System.arraycopy(scratch, lo, t.curr, lo, len);
				t.currTick[g] = serverTick;
				// The cadence, sampled where it is already in hand. Skipping the first roll is
				// the whole reason `rolled` exists: that gap is appearance-to-first-motion, not a
				// cadence, and counting it would put one bogus sample per node into the top
				// bucket. Diagnostic only -- nothing below reads the histogram.
				if (t.rolled[g]) {
					RenderStats.onKeyframeGap(t.currTick[g] - t.prevTick[g]);
				}
				t.rolled[g] = true;
				t.snap[g] = teleport
						|| t.currTick[g] - t.prevTick[g] > MAX_GAP_TICKS
						|| t.currTick[g] <= t.prevTick[g]
						|| rebased;
			}
		}
		// Drop tracks for nodes that are gone, or a long-lived scene leaks one entry per freed
		// node forever.
		for (Iterator<Map.Entry<Integer, Track>> it = tracks.entrySet().iterator(); it.hasNext();) {
			if (!state.nodes.containsKey(it.next().getKey())) {
				it.remove();
			}
		}
	}

	/**
	 * Feed the clock estimate a tick that arrived WITHOUT a batch — ANIM-13(b).
	 *
	 * The sample {@code ServerTimeline} wants is "the server said tick T, we saw it at wall
	 * instant N", and a heartbeat supplies exactly that; nothing about the estimate cares which
	 * message type carried it. What it must NOT do is touch keyframes: there is no new state, so
	 * {@code capture}'s track walk would re-stamp every node against a tick no batch delivered
	 * and freeze the interpolation it exists to drive.
	 *
	 * Why this exists at all: the timeline was fed only from {@code capture}, so a scene that
	 * sends no batches — an animator scene, by design — never corrected its estimate, free-ran
	 * on wall time, and hard re-based when a batch finally landed, stepping `time` backward
	 * under every animator on it.
	 */
	void observeTick(long serverTick, long nowNanos) {
		timeline.onBatch(serverTick, nowNanos);
	}

	/** Discard the clock estimate and settle every node. For an epoch change or hard resync. */
	void reset() {
		timeline.reset();
		for (Track t : tracks.values()) {
			for (int g = 0; g < GROUPS; g++) {
				t.snap[g] = true;
			}
		}
	}

	/**
	 * The instant this frame is rendering, or {@link Long#MIN_VALUE} while the timeline is unprimed.
	 *
	 * EXISTS SO THE ANIMATOR READS THIS TIMELINE RATHER THAN ITS OWN — ANIM-4's "one {@code time}
	 * sample per frame per scene" is exactly a rule against a second estimator. Two timelines
	 * would each smooth their own EMA from the same batches, agree to within a fraction of a
	 * millisecond, and drift apart across a rebase — so an animated node would sit at an instant
	 * the interpolated transforms it composes over do not share. The whole point of ANIM-4 is that
	 * a program's {@code time} and the base it lands on describe the same moment.
	 *
	 * PACKAGE-PRIVATE, and no wider. PLAN 3.5 asks to "widen ServerTimeline.renderNanos access",
	 * which overstates what is needed: {@code ServerTimeline}, {@code Canvas2dRenderer} and
	 * {@code NodeFold} all live in this package, and 3.3's evaluator must live here too because
	 * that is where its injection points are. Publishing a render clock on a public API would
	 * invite a second caller outside the frame loop, which is the failure this accessor exists to
	 * prevent.
	 *
	 * The unprimed sentinel is MIN_VALUE rather than 0: 0 is a legitimate instant, and a caller
	 * that forgot to check would place the animator clock at the epoch instead of visibly failing.
	 */
	long renderInstant(long nowNanos) {
		return timeline.primed() ? timeline.renderNanos(nowNanos) : Long.MIN_VALUE;
	}

	/**
	 * Is any node still mid-flight? The pre-pass re-renders the scene FBO while this holds, which
	 * is the cost interpolation buys its smoothness with — and why it must go false for a settled
	 * scene rather than pinning every scene at full frame rate forever.
	 *
	 * <b>This comment spent an increment orphaned</b>, stacked above {@code renderInstant}'s
	 * javadoc with no declaration between them, so javac discarded it and {@code active} documented
	 * nothing. Prose can be orphaned as silently as a test can be vacuous, and neither reports it.
	 *
	 * Per group, since C1.3.3: a node counts as in flight if EITHER group is, which is why a
	 * 3D-only change keeps the scene re-rendering. Correct — the 3D group really is interpolating —
	 * and currently redundant, since nothing reads those slots until group B.
	 */
	boolean active(long nowNanos) {
		if (!timeline.primed()) {
			return false;
		}
		long render = timeline.renderNanos(nowNanos);
		for (Track t : tracks.values()) {
			for (int g = 0; g < GROUPS; g++) {
				if (!t.snap[g] && render < ServerTimeline.tickNanos(t.currTick[g])) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * The node's transform as it should appear now, written into {@code out} —
	 * {@link NodeFold#TRS_WIDTH} fields, so an array sized from that constant is exactly right.
	 */
	void transformOf(SceneNode node, long nowNanos, double[] out) {
		Track t = tracks.get(node.id);
		if (t == null) {
			rawTransform(node, out);
			return;
		}
		if (!timeline.primed()) {
			System.arraycopy(t.curr, 0, out, 0, FIELDS);
			return;
		}
		long render = timeline.renderNanos(nowNanos);
		for (int g = 0; g < GROUPS; g++) {
			sampleGroup(t, g, render, out);
		}
	}

	/**
	 * One group's slots, sampled against ITS OWN keyframe stamps.
	 *
	 * The snap test lives here rather than in {@code transformOf} because snapping is per group:
	 * a teleport of the 3D pose must not freeze a 2D glide that is still running.
	 */
	private static void sampleGroup(Track t, int g, long renderNanos, double[] out) {
		int lo = GROUP_LO[g], len = GROUP_HI[g] - lo;
		long t0 = ServerTimeline.tickNanos(t.prevTick[g]);
		long t1 = ServerTimeline.tickNanos(t.currTick[g]);
		if (t.snap[g] || renderNanos >= t1 || t1 <= t0) {
			System.arraycopy(t.curr, lo, out, lo, len);
			return;
		}
		if (renderNanos <= t0) {
			System.arraycopy(t.prev, lo, out, lo, len);
			return;
		}
		double a = (double) (renderNanos - t0) / (double) (t1 - t0);
		if (g == G2D) {
			out[X] = lerp(t.prev[X], t.curr[X], a);
			out[Y] = lerp(t.prev[Y], t.curr[Y], a);
			out[SX] = lerp(t.prev[SX], t.curr[SX], a);
			out[SY] = lerp(t.prev[SY], t.curr[SY], a);
			// Rotation takes the SHORTEST angular path. A plain lerp from 6.2 to 0.1 rad spins
			// the long way round — a full reverse revolution — every time a program wraps its
			// angle.
			out[ROT] = t.prev[ROT] + shortestAngle(t.curr[ROT] - t.prev[ROT]) * a;
		} else {
			out[TZ] = lerp(t.prev[TZ], t.curr[TZ], a);
			out[SZ] = lerp(t.prev[SZ], t.curr[SZ], a);
			slerp(t.prev, t.curr, a, out);
		}
	}

	/**
	 * The rot3d quaternion along the great circle between the keyframes — {@link #shortestAngle}
	 * one dimension up, and it exists for the same reason.
	 *
	 * <b>Why the great circle rather than a component lerp.</b> Lerping the four components and
	 * renormalising (nlerp) traces the same PATH at a different SPEED: it is exact at both ends
	 * AND at the midpoint, and wrong in between — LAGGING on the first half and LEADING by the
	 * mirror amount on the second. A quarter of the way along a 160 degree arc it gives 34.48
	 * degrees where constant speed is 40; three quarters along it gives 125.52 where constant
	 * speed is 120. That is an ease in and out of every server tick — a 20 Hz judder, the exact
	 * artefact this class exists to remove, reintroduced by its own fix.
	 *
	 * {@code NodeInterpolatorTest} does not pin those two literals. It MEASURES the fraction the
	 * sample actually landed on from a linearly-interpolated channel, asserts the angle equals
	 * that fraction times the arc, and recomputes nlerp at the SAME fraction to exclude it; 40 and
	 * 34.48 are simply what those come to at a quarter. It samples off the midpoint and over a
	 * wide arc for the two reasons above — at the midpoint the formulas agree exactly, and on a
	 * narrow arc they agree to within the tolerance.
	 *
	 * <b>THE SIGN FOLD IS THE LOAD-BEARING HALF, and canonicalisation does not make it
	 * redundant.</b> {@code q} and {@code -q} are the same rotation. The TWO SERVER POSE VERBS run
	 * their quaternion through {@code Look.normalize}, which forces {@code qw >= 0}; the delta
	 * applier, the snapshot codec and the raw props path do NOT — {@code Transform3d} says so in
	 * its own words, and none of the three validates what it carries. So a client node can hold
	 * either sign to begin with.
	 *
	 * And even where canonicalisation does apply it does not make consecutive keyframes CLOSE. Two
	 * rotations ten degrees apart either side of a half turn (175 and 185 degrees about +X)
	 * canonicalise to quaternions whose dot product is -0.9962, and interpolating those without
	 * folding sweeps the 350-degree complement. A node spinning slowly past a half turn WOULD snap
	 * into a full reverse revolution for one tick — would, because until the 3D path consumes this
	 * record these slots reach no pixel; the defect is real and its blast radius is currently
	 * zero. It is the pair {@code NodeInterpolatorTest} uses, quoted here so the two can be checked
	 * against each other without recomputing either.
	 *
	 * <b>A degenerate quaternion shows the destination rather than a guess.</b> There is no
	 * direction to interpolate along, so this writes {@code curr} through VERBATIM — and because
	 * it is verbatim, the interpolated record agrees with the frame raw rendering would have drawn
	 * whatever that frame turns out to be. ({@code Transform3d.rotation} happens to read a
	 * zero-length quaternion as the identity, so the answer is also a sensible one. But the
	 * agreement comes from copying the value, not from that — an earlier draft joined the two with
	 * a "so" that does not carry.)
	 */
	private static void slerp(double[] from, double[] to, double a, double[] out) {
		double ax = from[QX], ay = from[QY], az = from[QZ], aw = from[QW];
		double bx = to[QX], by = to[QY], bz = to[QZ], bw = to[QW];
		double na = Math.sqrt(ax * ax + ay * ay + az * az + aw * aw);
		double nb = Math.sqrt(bx * bx + by * by + bz * bz + bw * bw);
		if (!(na > 0.0) || !(nb > 0.0) || Double.isInfinite(na) || Double.isInfinite(nb)) {
			out[QX] = to[QX];
			out[QY] = to[QY];
			out[QZ] = to[QZ];
			out[QW] = to[QW];
			return;
		}
		ax /= na; ay /= na; az /= na; aw /= na;
		bx /= nb; by /= nb; bz /= nb; bw /= nb;
		double dot = ax * bx + ay * by + az * bz + aw * bw;
		if (dot < 0.0) {
			bx = -bx;
			by = -by;
			bz = -bz;
			bw = -bw;
			dot = -dot;
		}
		if (dot > SLERP_LINEAR_ABOVE) {
			writeNormalized(lerp(ax, bx, a), lerp(ay, by, a), lerp(az, bz, a), lerp(aw, bw, a), out);
			return;
		}
		// dot is now in [0, SLERP_LINEAR_ABOVE], so theta is at least 1.81 degrees and sin(theta)
		// is at least 0.0316 — the branch above IS the domain guard, which is why there is no
		// second clamp here pretending to be one.
		double theta = Math.acos(dot);
		double sin = Math.sin(theta);
		double wa = Math.sin((1.0 - a) * theta) / sin;
		double wb = Math.sin(a * theta) / sin;
		out[QX] = ax * wa + bx * wb;
		out[QY] = ay * wa + by * wb;
		out[QZ] = az * wa + bz * wb;
		out[QW] = aw * wa + bw * wb;
	}

	/**
	 * Normalize into the quaternion slots, falling back to the identity on a degenerate norm.
	 *
	 * <b>THE FALLBACK CANNOT FIRE FROM THE ONLY CALL SITE, and saying so is the point.</b> The
	 * branch above admits this method only when the two unit quaternions have a dot product above
	 * {@link #SLERP_LINEAR_ABOVE}, and the shortest vector on that segment is the midpoint, whose
	 * squared norm is {@code (1 + dot) / 2} — above 0.9997, so the norm never approaches zero. The
	 * guard stays anyway, priced as defence in depth rather than described as a live path: it is
	 * two lines, and a second caller added later WITHOUT that bound is precisely how a division by
	 * a vanishing norm arrives on the render thread. No test drives it THROUGH THIS CALL SITE, and
	 * none can while this is the only one — reflection can of course reach the method directly,
	 * which is a different thing from exercising the path.
	 */
	private static void writeNormalized(double x, double y, double z, double w, double[] out) {
		double norm = Math.sqrt(x * x + y * y + z * z + w * w);
		if (!(norm > 0.0) || Double.isInfinite(norm)) {
			out[QX] = 0;
			out[QY] = 0;
			out[QZ] = 0;
			out[QW] = 1;
			return;
		}
		out[QX] = x / norm;
		out[QY] = y / norm;
		out[QZ] = z / norm;
		out[QW] = w / norm;
	}

	private static double shortestAngle(double delta) {
		final double TWO_PI = Math.PI * 2.0;
		double d = delta % TWO_PI;
		if (d > Math.PI) {
			d -= TWO_PI;
		} else if (d < -Math.PI) {
			d += TWO_PI;
		}
		return d;
	}

	private static double lerp(double from, double to, double a) {
		return from + (to - from) * a;
	}

	/**
	 * A node's RAW transform, written into a TRS record — what a node displays when there is no
	 * interpolation source, and the seed a track settles on at first sight.
	 *
	 * <b>PACKAGE-VISIBLE AND STATIC because this spelling had three copies.</b>
	 * {@code Canvas2dRenderer.readTransform} and {@code AnimatorOverlay.displayedBase} each wrote
	 * it out again for their no-interpolation branch, and each wrote only the 2D five. That was
	 * harmless while the record WAS the 2D five; the moment it grew six more it became a
	 * stale-slot read — the previous node's {@code sz} and quaternion, left in a reused buffer.
	 * One spelling means a field added to {@link SceneNode} has exactly one place to be added here.
	 */
	static void rawTransform(SceneNode node, double[] dst) {
		dst[X] = node.x;
		dst[Y] = node.y;
		dst[ROT] = node.rot;
		dst[SX] = node.sx;
		dst[SY] = node.sy;
		dst[TZ] = node.tz;
		dst[SZ] = node.sz;
		dst[QX] = node.qx;
		dst[QY] = node.qy;
		dst[QZ] = node.qz;
		dst[QW] = node.qw;
	}

	/**
	 * Does one group's slice of two records agree, slot for slot?
	 *
	 * A RANGE rather than a field list, and that is what makes the group split cheap: the record's
	 * index constants are contiguous and the 3D six sit above the 2D five, so "did this group
	 * change" is a bounded scan instead of an eleven-term predicate that would have to be split in
	 * two and kept in step. Exact equality, deliberately — the question is whether the server sent
	 * a new value, not whether the new value is close to the old one.
	 */
	private static boolean equalRange(double[] a, double[] b, int lo, int len) {
		for (int i = lo; i < lo + len; i++) {
			if (a[i] != b[i]) {
				return false;
			}
		}
		return true;
	}
}
