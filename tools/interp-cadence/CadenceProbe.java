package opengpu.v2.mc.client.render;

import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneState;
import opengpu.v2.protocol.V2Wire;

import java.util.Collections;
import java.util.Set;

/**
 * DOES A GAP-TRACKING DELAY SURVIVE AN ALTERNATING CADENCE? The panel's blocker, measured.
 *
 * Four delay policies over a range of cadences, scored on the artefacts that matter: frames where
 * the node does not move at all (freeze), the largest single-frame step relative to nominal
 * (surge), steps that go BACKWARD (which no policy should ever produce), and the spread of step
 * sizes (a smoothness proxy — a perfectly interpolated node has spread zero).
 *
 * <b>THE MODEL IS VALIDATED AGAINST THE REAL CLASS BEFORE IT IS TRUSTED.</b> Policy FIXED2 is what
 * ships, so the model's output for FIXED2 must equal {@link NodeInterpolator}'s frame for frame.
 * If it does not, every other column is fiction and the run aborts. This is the step the last two
 * harnesses in this project skipped.
 */
public final class CadenceProbe {

	private static final long TICK = ServerTimeline.TICK_NANOS;
	private static final Set<Integer> NONE = Collections.<Integer>emptySet();
	/**
	 * READ FROM THE CLASS, not copied. This was `= 3` by hand until 2026-08-29, which made the
	 * README's "compiles against src/main/java directly, so the numbers move if those classes
	 * move" false for this one constant — and it is the constant PLAN-STAGE-C names as a live
	 * option to raise. Shadowing MAX_GAP_TICKS = 5 made `cont` print 100% frozen for cadence [4]
	 * where the real class interpolates, with the validation gate silent because its cadence list
	 * stops at 3. Both halves are fixed: this reads the field, and validation() derives its
	 * cadences from it.
	 */
	private static final long MAX_GAP = maxGapTicks();

	/**
	 * Wall-clock offset of display frame {@code f} from a tick boundary.
	 *
	 * ONE SPELLING, CALLED BY BOTH ARMS AND BY THE GUARD. The model and the real-class driver used
	 * to write this expression out separately, which is precisely how they came to share a
	 * truncation the validation gate could not see: {@code f * (TICK / FRAMES_PER_TICK)} loses 2 ns
	 * per frame, so the frame that should land exactly on {@code alpha == 1} lands inside the
	 * window and reports motion. With one helper, the guard in {@code main} tests the same code the
	 * measurements run on.
	 */
	private static long frameNanos(long f) {
		return f * TICK / FRAMES_PER_TICK;
	}

	private static long maxGapTicks() {
		try {
			java.lang.reflect.Field f =
					NodeInterpolator.class.getDeclaredField("GLIDE_MAX_GAP_TICKS");
			f.setAccessible(true);
			return f.getLong(null);
		} catch (Exception e) {
			throw new IllegalStateException("NodeInterpolator.GLIDE_MAX_GAP_TICKS is gone or"
					+ " renamed; every frozen number this tool prints depends on it."
					+ " (It was MAX_GAP_TICKS until 2026-08-30.)", e);
		}
	}
	private static final int FRAMES_PER_TICK = 3;

	// ---- delay policies ------------------------------------------------------------------------
	private static final int FIXED2 = 0;   // what ships
	private static final int PERSIST = 1;  // D = the last observed gap  (proposals 1/3/4)
	private static final int MAX2 = 2;     // D = max of the last two gaps  (the hedge)
	private static final int SLEW = 3;     // D moves toward the last gap by at most one tick
	private static final String[] POLICY = { "FIXED2 (ships)", "PERSIST", "MAX2 hedge", "SLEW 1/kf" };

	/** Effective delay in ticks for a policy, given the gap history. */
	private static long delayFor(int policy, long gap, long prevGap, long current) {
		switch (policy) {
			case FIXED2:  return 2;
			case PERSIST: return Math.max(1, gap);
			case MAX2:    return Math.max(1, Math.max(gap, prevGap));
			case SLEW:
				long want = Math.max(1, gap);
				if (want > current) return current + 1;
				if (want < current) return current - 1;
				return current;
			default: throw new IllegalStateException();
		}
	}

	/** One node's displayed x across a run, under a policy. Mirrors sampleGroup's arithmetic. */
	private static double[] model(int policy, int[] gaps, int repeats) {
		java.util.List<Double> xs = new java.util.ArrayList<Double>();
		long prevTick = 0, currTick = 0;
		double prevX = 0, currX = 0;
		long gap = 1, prevGap = 1, d = 2;
		long tick = 0;
		double x = 0;
		for (int r = 0; r < repeats; r++) {
			for (int gi = 0; gi < gaps.length; gi++) {
				long g = gaps[gi];
				tick += g;
				x += 100.0;
				prevTick = currTick; prevX = currX;
				currTick = tick;     currX = x;
				prevGap = gap; gap = g;
				d = delayFor(policy, gap, prevGap, d);
				// FRAMES RUN UNTIL THE **NEXT** KEYFRAME, not for this interval's own width. The
				// first version used g here, which coincides only on a constant cadence -- on an
				// alternating one it truncated the interval before the freeze at its tail, and so
				// reported PERSIST as freeze-free on exactly the cadences that were in question.
				long nextGap = gaps[(gi + 1) % gaps.length];
				// wall time advances one TICK per server tick (20 tps, paced, no jitter), and with
				// a settled clock serverNanos(w) == w in this frame of reference.
				for (int f = 0; f < FRAMES_PER_TICK * nextGap; f++) {
					// f * TICK / FRAMES_PER_TICK, NOT f * (TICK / FRAMES_PER_TICK). The second form
					// truncates once per frame -- TICK/3 is 16_666_666, two nanoseconds short -- and
					// the error accumulates until the frame that should land exactly on alpha == 1
					// lands 4ns inside the window instead. That frame then reports a step of 4e-6
					// rather than 0, so the frozen counter MISSES THE FIRST FRAME OF EVERY EPISODE.
					// Six of the eight cadences below moved when this was fixed, two of them
					// reversing the policy ranking. See the guard in main().
					long serverNow = currTick * TICK + frameNanos(f);
					long sample = serverNow - d * TICK;
					long t0 = prevTick * TICK, t1 = currTick * TICK;
					double val;
					if (currTick - prevTick > MAX_GAP || t1 <= t0 || sample >= t1) {
						val = currX;
					} else if (sample <= t0) {
						val = prevX;
					} else {
						double a = (double) (sample - t0) / (double) (t1 - t0);
						val = prevX + (currX - prevX) * a;
					}
					xs.add(Double.valueOf(val));
				}
			}
		}
		double[] out = new double[xs.size()];
		for (int i = 0; i < out.length; i++) out[i] = xs.get(i).doubleValue();
		return out;
	}

	/** The same run through the REAL NodeInterpolator, for validation of the FIXED2 column. */
	private static double[] real(int[] gaps, int repeats) {
		NodeInterpolator interp = new NodeInterpolator();
		SceneNode n = new SceneNode(1, V2Wire.NODE_SPRITE, 1);
		long t0 = 1000L, n0 = 7_000_000_000L;
		interp.capture(stateWith(n), t0, n0, NONE);
		java.util.List<Double> xs = new java.util.ArrayList<Double>();
		long tick = 0;
		double x = 0;
		for (int r = 0; r < repeats; r++) {
			for (int gi = 0; gi < gaps.length; gi++) {
				long g = gaps[gi];
				tick += g; x += 100.0;
				n.x = x;
				long arrival = n0 + tick * TICK;
				interp.capture(stateWith(n), t0 + tick, arrival, NONE);
				// Same correction as the model: frames run until the NEXT keyframe.
				long nextGap = gaps[(gi + 1) % gaps.length];
				for (int f = 0; f < FRAMES_PER_TICK * nextGap; f++) {
					double[] out = new double[NodeFold.TRS_WIDTH];
					// Exact thirds -- see the note in model(). This arm shares the expression with
					// the model, which is exactly why the validation gate below could not see the
					// truncation: both sides were wrong in the same way.
					interp.transformOf(n, arrival + frameNanos(f), out);
					xs.add(Double.valueOf(out[NodeFold.TRS_X]));
				}
			}
		}
		double[] out = new double[xs.size()];
		for (int i = 0; i < out.length; i++) out[i] = xs.get(i).doubleValue();
		return out;
	}

	private static SceneState stateWith(SceneNode n) {
		SceneState s = new SceneState();
		s.nodes.put(n.id, n);
		return s;
	}

	private static String score(double[] xs, int[] gaps) {
		// The constant-speed step: a node covers 100 units per keyframe, and keyframes arrive every
		// avg(gaps) ticks, i.e. every FRAMES_PER_TICK * avg(gaps) frames. A perfectly interpolated
		// node moves exactly this much on every frame, so surge == 1.00x and sd == 0.
		double nominal = 100.0 / (FRAMES_PER_TICK * avg(gaps));
		int half = xs.length / 2;                          // second half only: settled
		int frozen = 0, backward = 0, n = 0;
		double max = 0, sum = 0, sum2 = 0;
		for (int i = half; i + 1 < xs.length; i++) {
			double step = xs[i + 1] - xs[i];
			if (step < -1e-9) backward++;
			double a = Math.abs(step);
			if (a < 1e-9) frozen++;
			if (a > max) max = a;
			sum += a; sum2 += a * a; n++;
		}
		double mean = sum / n;
		double sd = Math.sqrt(Math.max(0, sum2 / n - mean * mean));
		// DENOMINATOR IS FRAMES, NOT STEPS. n steps span n+1 frames, and dividing the zero-step
		// count by n rather than n+1 inflated every cell by about half a point -- enough to print
		// 33.5% beside a continuous 33.3% and 50.2% beside 50.0%, which read as the frame column
		// exceeding a bound it cannot exceed. It cannot: a run of N still frames yields N-1 zero
		// steps, so this number is <= the continuous measure in every cell, and the apparent
		// counterexamples were the denominator.
		return String.format("frozen %5.1f%%  surge %6.2fx  backward %3d  sd %6.2f",
				100.0 * frozen / (n + 1), max / nominal, backward, sd);
	}

	private static double avg(int[] a) {
		double s = 0;
		for (int i = 0; i < a.length; i++) s += a[i];
		return s / a.length;
	}

	/**
	 * The delay in force during each interval of one period, after the policy has settled.
	 *
	 * SLEW carries state, so it is run around the cycle until its limit cycle repeats rather than
	 * evaluated once. The others are memoryless in the gap history and settle immediately.
	 */
	private static long[] settledDelays(int policy, int[] gaps) {
		int n = gaps.length;
		long d = 2;
		long[] seq = new long[n];
		for (int r = 0; r < 64; r++) {
			for (int i = 0; i < n; i++) {
				d = delayFor(policy, gaps[i], gaps[(i - 1 + n) % n], d);
				seq[i] = d;
			}
		}
		return seq;
	}

	/**
	 * The CONTINUOUS frozen fraction -- the measure of wall time on which the node does not move,
	 * with no frame grid involved at all.
	 *
	 * <b>This is the column to quote, and it exists because the frame column cannot be trusted on
	 * its own.</b> During the interval after keyframe i the held pair is (i-1, i), so alpha is
	 * interior exactly on the window ((k_{i-1}+D_i)*T, (k_{i-1}+D_i+G_i)*T) -- width G_i*T, and the
	 * delay only TRANSLATES it. The interval itself is [k_i*T, k_{i+1}*T), width G_{i+1}*T. Moving
	 * time is the overlap; everything else is clamped.
	 *
	 * Two consequences worth knowing before reading the table. The overlap can never exceed
	 * min(G_i, G_{i+1})*T, so frozen time is at least (G_{i+1} - G_i)+ *T for EVERY policy,
	 * including one allowed to see the future -- when a cadence accelerates, freezing is forced and
	 * no delay rule avoids it. And the overlap attains that bound exactly when D_i lies in
	 * [min(G_i,G_{i+1}), max(G_i,G_{i+1})], which D_i = G_i always does: PERSIST sits on the floor
	 * on every cadence. The frame column reads LOWER than this one, by roughly one frame per freeze
	 * episode, because a run of N still frames yields only N-1 zero steps.
	 */
	private static double continuousFrozen(int policy, int[] gaps) {
		int n = gaps.length;
		long[] d = settledDelays(policy, gaps);
		double frozen = 0, total = 0;
		for (int i = 0; i < n; i++) {
			double gi = gaps[i], gn = gaps[(i + 1) % n];
			double moving;
			if (gi > MAX_GAP) {
				moving = 0;                       // the snap rule: held at curr for the whole interval
			} else {
				moving = Math.max(0.0,
						Math.min(d[i] + gi, gi + gn) - Math.max((double) d[i], gi));
			}
			frozen += gn - moving;
			total += gn;
		}
		return frozen / total;
	}

	/** The forced floor: sum of the cadence's accelerations over its total length. */
	private static double forcedFloor(int[] gaps) {
		int n = gaps.length;
		double rise = 0, total = 0;
		for (int i = 0; i < n; i++) {
			rise += Math.max(0, gaps[(i + 1) % n] - gaps[i]);
			total += gaps[i];
		}
		return rise / total;
	}

	public static void main(String[] args) {
		// ---------- THE GUARD THE VALIDATION GATE CANNOT BE ----------
		// The gate below compares the model against the real class and is blind to any error the
		// two share. It shared one: frame instants computed as f * (TICK/FRAMES_PER_TICK), which
		// truncates 2ns per frame.
		//
		// IT CHECKS THE EXPRESSION THE LOOPS ACTUALLY USE, via the same helper they call. A first
		// version of this guard compared FRAMES_PER_TICK * (TICK/FRAMES_PER_TICK) against TICK --
		// two compile-time constants, so it was TRUE for the shipped values and printed its notice
		// unconditionally, carrying no information about the code. A panel re-injected the defect
		// and diffed: 17 rows of output changed and the guard's lines were byte-identical. A guard
		// that cannot distinguish the defect from its absence is decoration.
		for (int k = 1; k <= 4; k++) {
			if (frameNanos(FRAMES_PER_TICK * k) != k * TICK) {
				System.out.printf("ABORT: %d frames must span exactly %d ticks; frameNanos(%d)"
						+ " gives %d, not %d.%n", FRAMES_PER_TICK * k, k, FRAMES_PER_TICK * k,
						frameNanos(FRAMES_PER_TICK * k), k * TICK);
				System.out.println("  Truncation in the frame clock makes the frame that should");
				System.out.println("  land on alpha == 1 land just inside the window, so the frozen");
				System.out.println("  counter misses the first frame of every episode. Every number");
				System.out.println("  below would be wrong in the same direction. Fix frameNanos.");
				return;
			}
		}

		// ---------- VALIDATE THE MODEL, or everything below is fiction ----------
		// The cadence list is DERIVED from MAX_GAP rather than written out, so that raising
		// NodeInterpolator.GLIDE_MAX_GAP_TICKS puts the newly-interpolated cadences under the gate
		// instead of leaving them unvalidated. With the list hardcoded at {1},{2},{3},... a raise
		// to 5 left the model snapping cadence [4] while the real class interpolated it, and the
		// gate stayed green because it never looked above 3.
		int[][] validation = new int[(int) MAX_GAP * 2][];
		for (int g = 1; g <= (int) MAX_GAP; g++) {
			validation[g - 1] = new int[] { g };
			validation[(int) MAX_GAP + g - 1] = new int[] { g, 1 + g % (int) MAX_GAP };
		}
		for (int v = 0; v < validation.length; v++) {
			double[] m = model(FIXED2, validation[v], 8);
			double[] r = real(validation[v], 8);
			if (m.length != r.length) {
				System.out.printf("MODEL ABORT: length %d vs real %d for %s%n",
						m.length, r.length, java.util.Arrays.toString(validation[v]));
				return;
			}
			double worst = 0;
			for (int i = 0; i < m.length; i++) worst = Math.max(worst, Math.abs(m[i] - r[i]));
			if (worst > 1e-6) {
				System.out.printf("MODEL ABORT: diverges by %.6f from the real class on gaps %s%n",
						worst, java.util.Arrays.toString(validation[v]));
				System.out.println("  Every comparison below would be fiction. Fix the model first.");
				return;
			}
		}
		// COUNT DERIVED, NOT TYPED. This read "on 6 cadences" until 2026-08-30, which was true when
		// MAX_GAP was 3 and the loop above built 2*3 entries. Raising the ceiling to 5 made the loop
		// build 10 while the banner still claimed 6 -- the tool asserting a coverage it had already
		// exceeded. A printed count beside a derived loop is a D6 waiting to happen.
		System.out.println("MODEL VALIDATED against the real NodeInterpolator on "
				+ validation.length + " cadences,");
		System.out.println("worst frame-by-frame divergence < 1e-6 for the shipped policy.");
		System.out.println();
		System.out.println("cont  = CONTINUOUS frozen fraction (closed form, no frame grid) <- quote this");
		System.out.println("frozen= frames with a zero step, 3 frames/tick. NEVER above cont: a run of");
		System.out.println("        N still frames yields only N-1 zero steps.");
		System.out.println("floor = forced frozen fraction: NO policy can go below it, not even a");
		System.out.println("        clairvoyant one. A policy at 'cont == floor' cannot be improved on.");
		System.out.println("surge = largest single-frame step / constant-speed step (1.00 is ideal)");
		System.out.println("sd    = spread of step sizes (0.00 is perfectly smooth)");
		System.out.println();

		int[][] cadences = {
			{1}, {2}, {3},
			{1, 2}, {1, 3}, {2, 3}, {1, 1, 3}, {2, 1, 2, 3},
		};
		boolean floorBroken = false;
		for (int c = 0; c < cadences.length; c++) {
			double floor = forcedFloor(cadences[c]);
			System.out.printf("  cadence %-11s floor %5.1f%%%n",
					java.util.Arrays.toString(cadences[c]), 100.0 * floor);
			for (int p = 0; p < POLICY.length; p++) {
				double cont = continuousFrozen(p, cadences[c]);
				// A policy below the floor is impossible; if one prints, the closed form is wrong.
				if (cont < floor - 1e-9) floorBroken = true;
				System.out.printf("    %-16s cont %5.1f%%%s  %s%n", POLICY[p], 100.0 * cont,
						cont <= floor + 1e-9 ? " ON FLOOR" : "         ",
						score(model(p, cadences[c], 40), cadences[c]));
			}
		}
		if (floorBroken) {
			System.out.println();
			System.out.println("ABORT: a policy printed BELOW the forced floor. That is impossible,");
			System.out.println("  so continuousFrozen or forcedFloor is wrong. Do not use this table.");
		}
	}
}
