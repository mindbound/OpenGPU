package opengpu.v2.mc.client.render;

/**
 * The client's smoothed estimate of server time for one scene, and the render clock derived
 * from it.
 *
 * DESIGN-RENDERER-V2 § Animation: "the client keeps a monotonic estimate of server time smoothed
 * from batch arrivals (jitter buffer) and renders the scene at that estimate minus a fixed
 * interpolation delay of 1-2 ticks. Naive lerp-from-arrival stutters under jitter; MC's own
 * entity sync exists for this reason."
 *
 * The first implementation of node interpolation ignored this and lerped over a fixed 50 ms
 * window measured from LOCAL ARRIVAL — precisely the version the design warns against. It looks
 * correct on a LAN, where arrivals are evenly spaced, and degrades exactly where it matters: a
 * batch 20 ms late compresses that node's motion into 30 ms and the next one stretches it, so
 * the sprite surges and stalls. No test can see it, because the defect IS the timing.
 *
 * What this buys, concretely: node motion is replayed against SERVER time, so a late batch
 * changes when we learn about a movement but not how fast it appears to happen.
 *
 * <h2>Why an offset rather than a rate estimate</h2>
 * Server ticks and client nanoseconds both advance at real-time rates, so there is no clock
 * SKEW to estimate — only a fixed unknown offset plus transit jitter. Estimating a rate would
 * fit noise. The offset is smoothed with an EMA and hard-reset when it moves further than any
 * jitter could explain (server restart, a long freeze, a scene epoch change).
 *
 * The offset absorbs one-way transit latency as a constant bias, which is harmless: the render
 * clock is deliberately behind live anyway, and a consistent bias shifts everything equally.
 * What would NOT be harmless is a bias that varies per batch — which is what the arrival-time
 * implementation had.
 */
final class ServerTimeline {
	/**
	 * One server tick at 20 tps — DELEGATED, not duplicated.
	 *
	 * This used to spell {@code 50L * 1000L * 1000L} while {@link opengpu.v2.ocsl.OcslTime} spelled
	 * the same literal and asserted in prose that the two matched. Nothing could check that: this
	 * class is package-private, so no test in the OCSL package can reference it, and the renderer's
	 * own test derives its tick FROM this constant and would move in lockstep with any change. So
	 * retuning the tick here would have left {@code time} on 50 ms ticks, drifting every scene's
	 * clock against the transforms ANIM-4 exists to make it share an instant with — with the whole
	 * suite green. One constant, one definition, and the coupling is now a compile-time fact.
	 */
	static final long TICK_NANOS = opengpu.v2.ocsl.OcslTime.TICK_NANOS;

	/**
	 * How far behind the live estimate we render, in ticks.
	 *
	 * This is the jitter buffer, and it is the whole reason the class exists: to interpolate
	 * toward a keyframe we must already HOLD it, so we must render far enough in the past that
	 * the next keyframe has normally arrived.
	 *
	 * <b>THE VALUE IS ONLY CORRECT WHERE IT EQUALS THE KEYFRAME GAP, and that is the real finding
	 * here — neither 1 nor 2 is right in general.</b> Measured 2026-08-29, then measured again
	 * after a change to 1 was nearly shipped.
	 *
	 * In closed form, for a paced server and a CONSTANT cadence: the render clock is
	 * {@code t1 - D*TICK + d} with {@code d} the wall time since the keyframe arrived, {@code d} in
	 * {@code [0, G*TICK)} for a keyframe gap of {@code G} ticks, and the window
	 * {@code sampleGroup} divides by is {@code (t1 - G*TICK, t1)}. That window has width
	 * {@code G*TICK} and the delay only TRANSLATES it, so the clock spends
	 *
	 * <pre>   max(0, G - |D - G|) / G</pre>
	 *
	 * of each interval inside it, <b>sweeping at exactly 1x nominal speed throughout</b>. The
	 * penalty is symmetric in {@code |D - G|} and total at {@code D == 2G}: two ticks of delay
	 * against a one-tick cadence interpolates NOTHING. Exact only for {@code G == D}; every other
	 * cadence spends one discontinuity per keyframe of
	 *
	 * <pre>   min(|1 - D/G|, 1)</pre>
	 *
	 * of a keyframe span, and the visible artefact is carried entirely by that jump — never by a
	 * fast or slow sweep. <b>The jump always lands on the FIRST frame after a keyframe boundary;
	 * what moves with the sign of {@code D - G} is where the freeze sits:</b>
	 *
	 * <pre>
	 *   D &lt; G     alpha starts at 1-D/G in (0,1)   jump, then sweep, then freeze at the TAIL
	 *   D &gt; G     alpha starts below 0             jump, then freeze at the HEAD, then sweep
	 *   D &gt;= 2G   alpha never reaches (0,1)        jump, then frozen for the WHOLE interval
	 * </pre>
	 *
	 * The observed first STEP is the jump plus up to one frame's travel, since the frame grid does
	 * not generally land on the boundary instant.
	 *
	 * <b>An earlier revision of this paragraph said {@code min(D,G)/G} and {@code (G/D)x}, and both
	 * halves were false.</b> {@code min(D,G)/G} claims 100% interpolation at {@code G=1, D=2}, which
	 * the measured table below contradicts in this same javadoc; {@code (G/D)x} is wrong for
	 * every {@code D != G}, since the true rate is 1x always. Driven to correct it, 2026-08-29:
	 * measured sweep rate is {@code 1.000000x} in every one of the five {@code (G,D)} cells of
	 * {@code {1,2,3} x {1,2}} where a sweep exists at all, against {@code (G/D)x} predictions
	 * spanning 0.5x to 3x. The sixth, {@code (1,2)}, has no interpolating frame to measure — which
	 * is itself the closed form's own prediction there, and the reason this correction matters.
	 *
	 * <b>THE JUMP FORMULA TOOK THREE ATTEMPTS AND THE FIRST TWO FAILED ON THE SAME BRANCH AS THE
	 * THING THEY WERE FIXING.</b> Recorded because the pattern is the point, not the arithmetic.
	 * Draft 1 wrote {@code (1 - D/G)}: right for {@code D < G}, negative and meaningless for
	 * {@code D > G}. Draft 2 wrote {@code |1 - D/G|}: right for {@code G <= D < 2G}, but it grows
	 * without bound while the real jump SATURATES at one span — at {@code G=1, D=3} it claims two
	 * spans where the class moves one. Both drafts were reasoned; the third was driven, over
	 * {@code (G,D)} in {@code {1,2,3,4} x {1,..,8}}, and {@code min(|1 - D/G|, 1)} is the only one
	 * of the three that matches in all 32 cells. If this sentence is edited again, drive it.
	 *
	 * What the value costs at each cadence, driven against this class at exactly 20 tps with paced
	 * arrivals, no jitter, and three display frames per tick:
	 *
	 * <pre>
	 *   gap 1 (a program updating every tick, which needs a busy loop):
	 *     D=2  0 of 360 frames interpolate -- the clock never enters the window at all.
	 *          Steps 100.00, 0, 0 repeating: the node STEPS at 20 Hz and never glides
	 *     D=1  33.33 every frame, continuous
	 *   gap 2 (os.sleep(0.1), or any loop costing more than a tick):
	 *     D=2  16.67 units per frame, every frame, continuous
	 *     D=1  50.00, 16.67, 16.67, 16.67, 0, 0 repeating -- a 3x jump to the interval's
	 *          MIDPOINT, then a 50 ms FREEZE, ten times a second
	 *   gap 3:
	 *     D=2  moves on 6 frames of every 9; 33.33 entry jump (3x), then 50 ms frozen
	 *     D=1  moves on 3 of every 9; 100 ms frozen out of every 150; 66.67 entry jump (6x)
	 * </pre>
	 *
	 * The fractions above are the CONTINUOUS measure — wall time on which the node does not move.
	 * <b>A frame counter reads AT OR BELOW it, never above</b>, because a run of N still frames
	 * yields only N-1 zero STEPS: each freeze episode can lose up to one frame to the motion
	 * bordering it. Over the eight cadences {@code tools/interp-cadence} publishes, the two agree
	 * exactly in most cells and differ by one frame per episode in the rest. Quote the continuous
	 * column; the tool prints both.
	 *
	 * <i>(An earlier revision of this paragraph claimed the frame column reads ABOVE the continuous
	 * one about as often as below, on the strength of cells printing 33.5% against a continuous
	 * 33.3%. That excess was the harness dividing a zero-step count by the number of STEPS rather
	 * than the number of FRAMES — n steps span n+1 frames. Fixed in the tool; the inequality above
	 * is exact.)</i>
	 *
	 * <b>So 2 stays.</b> The surge-and-freeze at D=1 is the exact artefact this class's own header
	 * names as the thing it exists to remove ("motion surged and stalled"), and it lands on the
	 * COMMON cadence: OpenComputers charges ~13 ms of {@code executionDelay} per component call, so
	 * a loop with a few calls costs more than a tick and gap 2 or 3 is ordinary. Gap 1 is the case
	 * this class's header claims to serve and simultaneously says a program cannot sustain without
	 * "burning its whole call budget" — the header contradicts itself, and the 20 Hz stepping it
	 * suffers is at least REGULAR, where D=1's surge is not.
	 *
	 * The honest fix is neither constant: <b>the delay wants to track the observed keyframe gap.</b>
	 * Scoped in {@code PLAN-STAGE-C}, together with the TPS-deficit drift, which is the same
	 * "a delay counted in ticks is the wrong unit" problem seen from another side.
	 *
	 * The cost is honest and worth stating: node motion appears 100 ms behind the server. For a
	 * program that moves a sprite in response to a click, that is added to the click's own round
	 * trip. Canvas CONTENT is unaffected — it never interpolates and is drawn as soon as
	 * it applies. DESIGN records 1-2 ticks with the exact value to be tuned in Stage B.
	 *
	 * <b>MEASURED 2026-08-29, AND THE VALUE 2 DEFEATS THE FEATURE AT THE COMMONEST CADENCE.</b>
	 * With one keyframe per tick, {@code renderNanos} runs in {@code [(T-2)*TICK, (T-1)*TICK)}
	 * while a node changing every tick has {@code prevTick = T-1} — so {@code renderNanos <= t0}
	 * on every frame and {@code NodeInterpolator.sampleGroup} takes the clamp-to-prev exit. The
	 * node STEPS at 20 Hz, two ticks stale. Smoothing is live only for programs updating slower
	 * than once per tick (a gap of 2 or 3; 4 and above snaps at {@code MAX_GAP_TICKS}).
	 *
	 * <b>{@code NodeInterpolatorTest.localShowing} CANNOT SEE THIS CONSTANT and that is why a
	 * separate vector exists.</b> The helper derives its frame instants by ADDING this delay to the
	 * wall time, so it self-adjusts to any value and every vector built on it stays green whatever
	 * this is set to. That makes those vectors sound as tests of the interpolation ARITHMETIC and
	 * blind to the cadence. {@code aNodeWhoseGapMatchesTheDelayMovesAtConstantSpeed} derives its
	 * instants from real arrival pacing instead, and pins the {@code D == G} relationship rather
	 * than the constant's value — so it stays meaningful whatever this becomes.
	 *
	 * <b>What this does NOT fix: a sustained TPS deficit.</b> When ticks take longer than
	 * {@code TICK_NANOS} of wall time, server time advances more slowly than the render clock, so
	 * the clock drifts UP through the keyframe window and out of it — measured at 15 tps, the
	 * interpolating fraction decays from 48% over 14 ticks to 6% over 120, with the same 20
	 * absolute frames at the start of each run. Both 1 and 2 are broken there, in the same
	 * direction and for the same reason, so this is a separate defect rather than an argument for
	 * either value. Ledgered in {@code PLAN-STAGE-C}.
	 */
	static final int INTERPOLATION_DELAY_TICKS = 2;

	private static final long DELAY_NANOS = INTERPOLATION_DELAY_TICKS * TICK_NANOS;

	/** EMA weight for a new offset sample. Low: we are smoothing jitter, not tracking drift. */
	private static final double ALPHA = 0.125;

	/**
	 * Beyond this the estimate is not jitter and must not be averaged toward over many batches.
	 * Half a second is far outside any plausible tick-to-tick network variation while being
	 * well inside the gap a server pause or a resync produces.
	 */
	private static final long RESET_THRESHOLD_NANOS = 500L * 1000L * 1000L;

	private long offsetNanos;
	private boolean primed;
	private long lastTick = Long.MIN_VALUE;

	/**
	 * Fold in a batch arrival.
	 *
	 * @return true if the timeline discontinuously re-based, meaning callers holding
	 *         server-time-stamped state must SNAP rather than interpolate across the seam.
	 */
	/**
	 * NAME IS NARROWER THAN THE CONTRACT as of ANIM-13(b), 2026-08-22. This observes a
	 * {@code (serverTick, arrival instant)} sample; a batch is now only one of the two messages
	 * that carry one — {@code NodeInterpolator.observeTick} feeds it from heartbeats so that a
	 * network-silent scene's estimate cannot free-run. Kept as {@code onBatch} because renaming
	 * churns eight test call sites inside a protocol increment; the honest name would be
	 * {@code observeServerTick} and a later cleanup should take it.
	 */
	boolean onBatch(long serverTick, long nowNanos) {
		long sample = serverTick * TICK_NANOS - nowNanos;
		if (!primed) {
			offsetNanos = sample;
			primed = true;
			lastTick = serverTick;
			return true;
		}
		// A tick going BACKWARDS is not jitter — it is a different incarnation of the scene, or
		// a restored server. Averaging toward it would slide every node across the seam.
		boolean rebase = serverTick < lastTick
				|| Math.abs(sample - offsetNanos) > RESET_THRESHOLD_NANOS;
		if (rebase) {
			offsetNanos = sample;
		} else {
			offsetNanos += (long) ((sample - offsetNanos) * ALPHA);
		}
		lastTick = serverTick;
		return rebase;
	}

	/** Discard the estimate entirely; the next batch re-primes it. Use on epoch change/resync. */
	void reset() {
		primed = false;
		lastTick = Long.MIN_VALUE;
	}

	boolean primed() {
		return primed;
	}

	/** Estimated server time now, in the same nanosecond units the tick stamps use. */
	long serverNanos(long nowNanos) {
		return nowNanos + offsetNanos;
	}

	/**
	 * The moment being rendered: the estimate minus the interpolation delay.
	 *
	 * Deliberately NOT clamped monotonic. The EMA can nudge this backwards by a fraction of a
	 * millisecond, which merely re-shows a position a hair earlier and is invisible; clamping
	 * would instead freeze the clock whenever the estimate corrected downward, turning a
	 * sub-millisecond wobble into a visible stall.
	 */
	long renderNanos(long nowNanos) {
		return serverNanos(nowNanos) - DELAY_NANOS;
	}

	/** Server-time stamp of a tick, for comparing against {@link #renderNanos}. */
	static long tickNanos(long serverTick) {
		return serverTick * TICK_NANOS;
	}
}
