package opengpu.v2.ocsl;

/**
 * THE ONE PRODUCER of the {@code time} register, and the frozen value of the wrap period <i>P</i>
 * (ANIM-4, ANIM-5).
 *
 * <h2>Why this is a host-side function and not an IR op</h2>
 *
 * The design's original sentence was self-contradictory: the wrap is "applied identically in the CPU
 * VM and in emitted GLSL" <b>and</b> "the GPU receives the pre-wrapped value". Both cannot hold — if
 * the GPU gets it pre-wrapped, the wrap happens once, host-side, at register binding, and there is
 * nothing being applied identically in two executors. ANIM-4 resolves it in favour of one producer,
 * and that clause is struck from the design. This class is that producer. No op computes
 * {@code time}; every executor receives the same float32 and agrees by construction rather than by
 * conformance testing.
 *
 * <h2>The expression, and why each part is pinned</h2>
 *
 * <pre>
 *   time = f32( clampAtZero(renderNanos − creationTick·TICK_NANOS) mod P_NANOS ) / 1e9
 * </pre>
 *
 * <ul>
 * <li><b>SECONDS.</b> A smoothed time estimate minus a tick count had no denomination anywhere — a
 *     20x ambiguity that makes {@code speed} undocumentable and every curated preset's default
 *     meaningless. For a lattice program it is worse than a tempo error: it changes which integers
 *     get hashed, so one blob with one set of uniforms renders two unrelated animations.</li>
 * <li><b>The RENDER clock</b>, i.e. the same instant the frame's transforms are sampled at, not the
 *     raw server estimate. The client renders every node transform at the estimate minus a fixed
 *     2-tick interpolation delay; reading {@code time} off the raw estimate would mix inputs from
 *     two instants 100 ms apart in one frame — an orbit's phase live while the base it orbits sits
 *     100 ms in the past, permanently, with the animated node leading its interpolated neighbours
 *     forever.</li>
 * <li><b>CLAMPED AT ZERO, and this is a reachability fix rather than defensive coding.</b>
 *     {@code time} is negative for the first ~100 ms of EVERY scene's life — creation tick minus the
 *     2-tick delay — and the estimate is deliberately not clamped monotonic. Under floor-mod a small
 *     negative maps to the <i>top</i> of the wrap domain, so without this every animator on every
 *     newly created scene would start at {@code t ≈ P} and fire a full wrap discontinuity inside its
 *     first 100 ms, at the coarsest float32 resolution in the whole domain.</li>
 * <li><b>Floor-mod in the exact {@code long} nanosecond domain</b>, and after the clamp the operand
 *     is non-negative, so Java's {@code %} already IS floor-mod. Half-open {@code [0, P)}.</li>
 * <li><b>NARROWED TO FLOAT32 EXACTLY ONCE, at the end.</b> Same discipline as A4's numeric domain:
 *     wrap-then-narrow and narrow-then-wrap differ by up to a ulp of the PRE-WRAP magnitude, which
 *     at a day-long period is ~8 ms of phase.</li>
 * </ul>
 *
 * <h2>Two properties an implementer must not assume</h2>
 *
 * <b>{@code time} is NOT monotonic.</b> The clamp removes the negative range, not the EMA wobble in
 * the underlying estimate, so consecutive frames can go backwards slightly. Stated because a VM or
 * codegen fast path that assumed monotonicity would be wrong in a way nothing would catch.
 *
 * <b>One sample per frame per scene.</b> {@code time} is a material, effect and post-chain register
 * as well as an animator one, and an animator evaluated per node inside the render loop can
 * otherwise read a different value than a pixel program whose uniform is uploaded at draw — in the
 * same frame, beating against each other at a rate set by frame timing. The host calls this once per
 * scene per frame and hands that one value to every consumer. The function is pure so that rule is
 * about the CALL SITE and cannot be violated here.
 */
public final strictfp class OcslTime {
	private OcslTime() {}

	/** 20 ticks per second. Matches {@code ServerTimeline.TICK_NANOS}. */
	public static final long TICK_NANOS = 50L * 1000L * 1000L;

	/**
	 * <i>P</i> = 1680 s = 28 minutes = 33600 ticks = 2⁴·3·5·7 — published contract (ANIM-5).
	 *
	 * <b>"Frozen" here does NOT mean what it means for a register id, and the difference runs the
	 * opposite way to what the shared word suggests.</b> An id is encoded into every blob, so moving
	 * one invalidates every saved program and costs a format-version bump plus an NBT migration.
	 * <i>P</i> is in no blob at all — it is supplied at runtime through register 8. Changing it
	 * breaks no decode, corrupts no save and needs no format bump; the only cost is that a program
	 * which BAKED a literal cycle time and relied on it dividing <i>P</i> starts popping, while one
	 * that reads {@code timePeriod} and derives its cycle from it is unaffected by construction.
	 * Worth stating because the wrong model errs in the discouraging direction: it would have
	 * someone treat a behaviour change as a format break and decline to fix something fixable.
	 *
	 * Three constraints pull against each other and the choice satisfies all three.
	 *
	 * <b>An upper bound from float32.</b> At a day-long period the ulp of {@code time} near the seam
	 * is 7.8125 ms against a 6.944 ms frame at 144 Hz, so the clock fails to advance on <b>11.1%</b>
	 * of frames (simulated over 2M frames). The dry run said "roughly half" and that is wrong by
	 * about 5x — "roughly half" needs <i>P</i> ≈ 131072 s, which measures 55.2%. The conclusion
	 * survives the correction and the number did not, so the number is the one written down. At
	 * 1680 s the ulp is 0.122 ms: 1.8% of a 144 Hz frame, and still only 12% of a 1000 Hz one.
	 *
	 * <b>A lower bound from pop frequency.</b> A non-harmonic program pops {@code 3600/P} times an
	 * hour — about twice here.
	 *
	 * <b>And the constraint nobody had named: seam-continuity is decided by <i>P</i>.</b> Choosing
	 * <i>P</i> silently chooses which animation intervals a preset can express without popping. A
	 * power of two looks clean and is the worst available choice — 3-, 5-, 6- and 7-second cycles
	 * all pop against it. 1680 is highly composite and admits every interval an author reaches for:
	 * 3, 5, 6, 7, 10, 12, 15, 20, 30 and 60 seconds, exactly.
	 *
	 * Stated precisely, because the short form is not quite true: a cycle of length <i>T</i> is
	 * seam-continuous iff <b>{@code P/T} is a whole number</b>, so the pop-free set is
	 * <code>{P/k}</code> and not the integer divisors of <i>P</i> — {@code 1680/13 ≈ 129.23 s} is
	 * pop-free and divides nothing. The divisors are the sub-case an author writing round numbers
	 * lands in, which is why they are what the table above lists.
	 *
	 * Being an integer number of TICKS is what makes THE MODULUS exact in the nanosecond domain,
	 * which is what lets a conformance vector at the seam assert EQUALITY rather than a tolerance.
	 * Scoped to the modulus deliberately: an earlier draft said it removed seam rounding "entirely",
	 * which the narrowing 40 lines below contradicts outright.
	 */
	public static final int PERIOD_TICKS = 33_600;

	/** {@link #PERIOD_TICKS} in nanoseconds — the exact modulus. */
	public static final long PERIOD_NANOS = PERIOD_TICKS * TICK_NANOS;

	/**
	 * <i>P</i> in seconds, as float32 — the value the reserved {@code timePeriod} register carries.
	 *
	 * Exact in float32: 1680 = 2⁴·105, well inside the mantissa. A program that needs the seam can
	 * read this register instead of baking {@code 1/P} into its constant pool, which would freeze a
	 * CONTRACT CONSTANT into every saved blob — a thing the caps-monotonicity rule does not cover,
	 * because <i>P</i> is not a cap.
	 */
	public static final float PERIOD_SECONDS = PERIOD_NANOS / 1_000_000_000.0f;

	/**
	 * The frozen production. Pure, total, and the only place this arithmetic exists.
	 *
	 * @param renderNanos  the frame's render instant — {@code ServerTimeline.renderNanos}, i.e. the
	 *                     same instant the frame's transforms are sampled at, NOT the raw estimate
	 * @param creationTick the scene's creation tick. <b>Nothing supplies this yet</b> — it has no
	 *                     field, no NBT entry and no protocol entry anywhere (ANIM-13, deferred to
	 *                     Phase 3.2 so one {@code PROTOCOL_VERSION} bump covers attach and the tick
	 *                     together). This {@code @param} said "one long persisted in scene NBT"
	 *                     until 2026-08-13, contradicting the comment nine lines below it in this
	 *                     same method; every caller today passes a value it made up.
	 *                     <p>
	 *                     <b>The clock domain is an open question, not a detail.</b> The only tick
	 *                     domain in the system is {@code V2ServerRuntime.tickCounter}, which is
	 *                     SESSION-LOCAL and reset to 0 at server stop. Persisting a raw tick in that
	 *                     domain and subtracting it in a later session gives a negative elapsed,
	 *                     which the clamp below silently pins to {@code t = 0.0} — an animator clock
	 *                     frozen for as long as it takes the new session to overtake the stored
	 *                     value, with no exception and nothing to fail. Whoever wires this must
	 *                     first decide: remap at restore, store world time, or store 0.
	 */
	public static float time(long renderNanos, long creationTick) {
		// SATURATING, not merely clamped, and the difference is a real one. An earlier note claimed
		// overflow was unreachable because TICK_NANOS is 5e7 so the MULTIPLY only overflows past
		// ~1.8e11 ticks (~292 years of world time) -- true, and it covers the wrong operation. The
		// SUBTRACTION overflows independently: `time(Long.MIN_VALUE, 123457)` wrapped positive and
		// returned 1224.0048 where the answer is 0, and `time(0, Long.MIN_VALUE)` returned 0 only
		// because Long.MIN_VALUE * 50_000_000 is congruent to 0 mod 2^64. Unreachable today --
		// `creationTick` has no NBT field or protocol field anywhere yet, ANIM-13 defers it -- but
		// "unreachable" was exactly the claim being made about the multiply, and it was answering a
		// different question than the one that mattered.
		long created = saturatingMultiply(creationTick, TICK_NANOS);
		long elapsed = saturatingSubtract(renderNanos, created);
		if (elapsed < 0L) {
			elapsed = 0L;
		}
		// Non-negative by the line above, so `%` is floor-mod and the result is in [0, P).
		long wrapped = elapsed % PERIOD_NANOS;
		// ONE narrowing, at the very end. `wrapped` is at most 1.68e12, exactly representable in
		// double, so the division carries no error the float32 result can see.
		float seconds = (float) (wrapped / 1_000_000_000.0);
		// THE HALF-OPEN INTERVAL IS NOT PRESERVED BY THE NARROWING, and ANIM-4 did not notice.
		//
		// The exact domain is [0, P) in nanoseconds, but float32 near 1680 has a ulp of ~1.22e-4 s,
		// so every instant within half a ulp below the seam ROUNDS UP to exactly P — the last ~61
		// microseconds of every period. The amendment specifies "[0, P)" and the arithmetic it
		// specifies cannot deliver it; both statements were true and nobody had put them together.
		//
		// Fixed by selection, not by a second narrowing: the largest float32 strictly below P. The
		// error against the true value stays under one ulp, which the contract already admits, and
		// in exchange `t < P` holds for every reachable instant. Worth preferring because P is the
		// wrap point — a value AT P is the same instant as 0, so returning it puts a value from
		// outside the domain immediately before the wrap, and any program guarding on t < P or
		// relying on t/P < 1 breaks there once per period rather than never.
		if (seconds >= PERIOD_SECONDS) {
			seconds = Math.nextDown(PERIOD_SECONDS);
		}
		return seconds;
	}

	/** {@code a*b}, pinned to the nearest extreme instead of wrapping. Java 8 has no Math.multiplyExact-free form. */
	private static long saturatingMultiply(long a, long b) {
		long product = a * b;
		// The standard overflow test: division recovers the operand unless the product wrapped.
		// (a == Long.MIN_VALUE && b == -1) is the one case division itself overflows.
		if (a != 0 && (product / a != b || (a == Long.MIN_VALUE && b == -1))) {
			return ((a < 0) != (b < 0)) ? Long.MIN_VALUE : Long.MAX_VALUE;
		}
		return product;
	}

	/** {@code a-b}, pinned to the nearest extreme instead of wrapping. */
	private static long saturatingSubtract(long a, long b) {
		long difference = a - b;
		// Overflow iff the operands differ in sign AND the result took the subtrahend's sign.
		if (((a ^ b) & (a ^ difference)) < 0) {
			return a < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
		}
		return difference;
	}
}
