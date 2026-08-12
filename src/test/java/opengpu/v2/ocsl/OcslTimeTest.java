package opengpu.v2.ocsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ANIM-4's frozen {@code time} production, and ANIM-5's <i>P</i>.
 *
 * The amendment names its own conformance vectors — "pre-wrap −1 tick, 0, +1 tick" — and they are
 * the first three tests here. Every assertion that touches the seam uses EQUALITY rather than a
 * tolerance, which is only legitimate because <i>P</i> is an integer number of ticks: the modulus is
 * then exact in the nanosecond domain and there is no seam rounding to absorb.
 */
public class OcslTimeTest {

	/** A creation tick with no special structure, so nothing passes by landing on a round number. */
	private static final long CREATED = 123_457L;

	private static long atTick(long tick) {
		return tick * OcslTime.TICK_NANOS;
	}

	@Test
	public void theWrapPeriodIsAnIntegerNumberOfTicksAndHighlyComposite() throws Exception {
		assertEquals("P must be a whole number of ticks or the modulus is not exact",
				0L, OcslTime.PERIOD_NANOS % OcslTime.TICK_NANOS);
		assertEquals(1680.0f, OcslTime.PERIOD_SECONDS, 0f);
		assertEquals(33_600, OcslTime.PERIOD_TICKS);

		// The property that actually matters to an author: the pop-free cycle times are the DIVISORS
		// of P. A power of two would admit only powers of two; these are the intervals a preset can
		// plausibly want. Asserted rather than described, because it is the reason for the value.
		int[] humanCycles = { 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 15, 20, 24, 28, 30, 60, 120 };
		for (int i = 0; i < humanCycles.length; i++) {
			assertEquals(humanCycles[i] + "s must divide P exactly or that cycle pops at the seam",
					0, OcslTime.PERIOD_TICKS % (humanCycles[i] * 20));
		}

		// And P is exactly representable, so reading it back through a float32 register loses
		// nothing -- the register exists precisely so a program need not bake 1/P into its pool.
		assertEquals(OcslTime.PERIOD_NANOS / 1e9, OcslTime.PERIOD_SECONDS, 0.0);
	}

	@Test
	public void oneTickBeforeCreationClampsToZeroRatherThanWrappingToTheTopOfTheDomain() throws Exception {
		// THE REACHABILITY FIX, and the case that is live on every scene ever created: `time` is
		// negative for the first ~100 ms of a scene's life, because the render clock trails the
		// estimate by 2 ticks. Under floor-mod without the clamp this lands at P - 50ms, so every
		// animator on every new scene would open at t ~ P and fire a full wrap discontinuity inside
		// its first 100 ms -- at the coarsest float32 resolution in the whole domain.
		float t = OcslTime.time(atTick(CREATED - 1), CREATED);
		assertEquals("a pre-creation instant must clamp to 0, not wrap to P", 0.0f, t, 0f);

		// Not just the one-tick case: the whole negative range collapses to the same value, so there
		// is no sliver of it that behaves differently.
		assertEquals(0.0f, OcslTime.time(atTick(CREATED - 2), CREATED), 0f);
		assertEquals(0.0f, OcslTime.time(atTick(CREATED - 100_000), CREATED), 0f);
		assertEquals(0.0f, OcslTime.time(Long.MIN_VALUE / 2, CREATED), 0f);
	}

	@Test
	public void theCreationInstantIsExactlyZeroAndOneTickLaterIsExactlyFiftyMilliseconds()
			throws Exception {
		assertEquals(0.0f, OcslTime.time(atTick(CREATED), CREATED), 0f);
		assertEquals("one tick is 50 ms, exactly, in seconds",
				0.05f, OcslTime.time(atTick(CREATED + 1), CREATED), 0f);
		assertEquals(1.0f, OcslTime.time(atTick(CREATED + 20), CREATED), 0f);
	}

	@Test
	public void theSeamIsExactAndHalfOpen() throws Exception {
		// [0, P): the instant at exactly P is 0 again, not P. Equality, not tolerance -- this is the
		// assertion the integer-tick choice was made to make legitimate.
		assertEquals("t = P must re-enter the domain at 0",
				0.0f, OcslTime.time(atTick(CREATED + OcslTime.PERIOD_TICKS), CREATED), 0f);
		assertEquals(0.0f, OcslTime.time(atTick(CREATED + 3L * OcslTime.PERIOD_TICKS), CREATED), 0f);

		// One tick short of the seam is P - 50ms, exactly.
		assertEquals(OcslTime.PERIOD_SECONDS - 0.05f,
				OcslTime.time(atTick(CREATED + OcslTime.PERIOD_TICKS - 1), CREATED), 0f);

		// And the value never reaches P from below, at the finest granularity the clock has.
		//
		// PINNED TO EXACT BITS, not merely to "< P". A mutation harness found that asserting only
		// the inequality let `seconds = PERIOD_SECONDS * 0.5f` pass the entire suite: for the final
		// 61035 ns of every period `time` would return 840.0 instead of 1679.9999 -- a half-period
		// teleport, roughly once per 127 hours of continuous rendering per scene, with a green
		// build. An assertion that admits every value in [0, P) pins nothing about which one.
		float justUnder = OcslTime.time(
				atTick(CREATED + OcslTime.PERIOD_TICKS) - 1L, CREATED);
		assertEquals("the instant 1ns before the seam must be the largest float32 below P",
				Float.floatToIntBits(Math.nextDown(OcslTime.PERIOD_SECONDS)),
				Float.floatToIntBits(justUnder));

		// THE CLAMPED VALUES MERGE WITH THE BUCKET BELOW, which is the one visible cost of the fix
		// and is measured rather than described. The ulp here is 122070.3 ns, so the 61035 ns that
		// used to round UP to P now join the 61035 ns that already rounded DOWN to nextDown(P):
		// the top quantization step is 1.5 ulp wide, 183105 ns, and `time` holds still for ~183 µs
		// immediately before every wrap. Everywhere else the step is 1 ulp.
		long seam = atTick(CREATED + OcslTime.PERIOD_TICKS);
		assertEquals("61035 ns before the seam is inside the clamp window",
				Float.floatToIntBits(justUnder),
				Float.floatToIntBits(OcslTime.time(seam - 61_035L, CREATED)));
		assertEquals("61036 ns before it rounds down to the same float -- the buckets merge",
				Float.floatToIntBits(justUnder),
				Float.floatToIntBits(OcslTime.time(seam - 61_036L, CREATED)));
		assertEquals("and 183105 ns before, still the same float: the step is 1.5 ulp wide",
				Float.floatToIntBits(justUnder),
				Float.floatToIntBits(OcslTime.time(seam - 183_105L, CREATED)));
		assertTrue("one nanosecond further back must finally step down",
				OcslTime.time(seam - 183_106L, CREATED) < justUnder);

		// Monotonic across the seam approach despite the widened step -- the merge cannot introduce
		// a value that goes backwards, which is the thing a quantization change most easily breaks.
		// Stops at 1 ns before the seam, not at the seam: `back == 0` IS the wrap, where the value
		// drops to 0 by design. Including it asserted that the discontinuity this whole register is
		// built around does not exist.
		float previous = -1f;
		for (long back = 400_000L; back >= 1L; back--) {
			float t = OcslTime.time(seam - back, CREATED);
			assertTrue("time went backwards " + back + " ns before the seam", t >= previous);
			previous = t;
		}
		assertEquals("and the seam itself is the wrap, back to 0",
				0.0f, OcslTime.time(seam, CREATED), 0f);
	}

	/**
	 * The narrowing happens ONCE, at the end — pinned at an instant with no round structure.
	 *
	 * Every other vector in this class sits at 0, 50 ms, 1 s or a whole number of periods, and at
	 * all of those the two possible orderings agree, so a mutation narrowing the DIVIDEND first
	 * passed the whole suite. It is not an equivalent mutation: it differs on 25.6% of the period
	 * with a worst-case phase error of 122 µs. One unstructured instant separates them.
	 */
	@Test
	public void theNarrowingHappensOnceAtTheEndAndNotOnTheDividend() throws Exception {
		float t = OcslTime.time(1_234_567_890_123L, 0L);
		assertEquals("narrowing the nanoseconds before dividing gives 1234.568 here",
				Float.floatToIntBits(1234.5679f), Float.floatToIntBits(t));
	}

	@Test
	public void wrappingHappensBeforeTheNarrowingSoPhaseDoesNotDriftWithScenAge() throws Exception {
		// The order that matters: wrap in the exact long domain, narrow once at the end. If the
		// narrowing came first, a scene hours old would carry a pre-wrap magnitude whose ulp is
		// milliseconds, and the SAME position in the cycle would land on different floats depending
		// on how many periods had already elapsed. Here it is bit-identical across periods.
		for (int period = 0; period < 5; period++) {
			long tick = CREATED + (long) period * OcslTime.PERIOD_TICKS + 4321L;
			assertEquals("period " + period + " must be bit-identical to period 0",
					Float.floatToIntBits(OcslTime.time(atTick(CREATED + 4321L), CREATED)),
					Float.floatToIntBits(OcslTime.time(atTick(tick), CREATED)));
		}
	}

	@Test
	public void everyReachableInstantStaysInsideTheDomainAndFinite() throws Exception {
		long[] probes = {
			Long.MIN_VALUE, Long.MIN_VALUE / 2, -1L, 0L, 1L,
			atTick(CREATED), atTick(CREATED) + 1L, Long.MAX_VALUE / 4, Long.MAX_VALUE,
		};
		for (int i = 0; i < probes.length; i++) {
			float t = OcslTime.time(probes[i], CREATED);
			assertTrue("non-finite at " + probes[i], OcslMath.finite(t));
			assertTrue("below 0 at " + probes[i] + ": " + t, t >= 0.0f);
			assertTrue("at or above P at " + probes[i] + ": " + t, t < OcslTime.PERIOD_SECONDS);
		}
	}

	/**
	 * The extremes SATURATE rather than wrapping, with the answer pinned rather than just bounded.
	 *
	 * This list used to sit in the probe above under the name "every reachable instant", asserting
	 * only range and finiteness — so it passed on the wrong answer. Before the saturating
	 * arithmetic, {@code time(Long.MIN_VALUE, CREATED)} returned <b>1224.0048</b>: the subtraction
	 * wrapped positive and produced a plausible mid-domain instant for a time infinitely before the
	 * scene existed. In range, finite, and wrong.
	 *
	 * Not reachable today — {@code creationTick} has no NBT or protocol field anywhere yet, ANIM-13
	 * defers it — and pinned anyway, because "unreachable" was the exact claim the previous overflow
	 * note made, and it was answering a question about the multiply while the subtraction was the
	 * one that overflowed.
	 */
	@Test
	public void theArithmeticSaturatesInsteadOfWrappingAtTheExtremes() throws Exception {
		// BEFORE creation saturates to the clamp, which is 0.
		assertEquals("an instant infinitely before creation is 0, not a mid-domain value",
				0.0f, OcslTime.time(Long.MIN_VALUE, CREATED), 0f);
		assertEquals(0.0f, OcslTime.time(Long.MIN_VALUE, -1L), 0f);
		assertEquals(0.0f, OcslTime.time(Long.MIN_VALUE, Long.MAX_VALUE), 0f);

		// AFTER creation by an unrepresentable amount saturates the other way and WRAPS, which is
		// the correct answer rather than 0: `creationTick = Long.MIN_VALUE` is a scene created
		// infinitely far in the past, so elapsed time is enormous and lands somewhere in [0, P).
		// Asserted as an exact value because "somewhere in the domain" is what the old probe said,
		// and that is what let it pass on the pre-saturation answer of 1224.0048.
		assertEquals("saturating to Long.MAX_VALUE then wrapping lands at 676.8548 s",
				Float.floatToIntBits(676.8548f),
				Float.floatToIntBits(OcslTime.time(0L, Long.MIN_VALUE)));

		// The far future saturates too, and lands somewhere legal rather than wrapping negative.
		for (long created : new long[] { -1L, 0L, Long.MIN_VALUE, Long.MAX_VALUE }) {
			float t = OcslTime.time(Long.MAX_VALUE, created);
			assertTrue("far-future must stay in [0, P), got " + t,
					t >= 0.0f && t < OcslTime.PERIOD_SECONDS);
		}
	}

	@Test
	public void timePeriodIsNotHostStateAndTheVmRefusesToBindIt() throws Exception {
		// The seeding changed the DEFAULT and nothing else until this refusal existed: a host could
		// set the register to 3.0 and have it stick for the VM's life, and generic binding code
		// looping over every built-in its stage has would set it to 0.0 -- the precise failure the
		// seeding was introduced to prevent.
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		Expr period = b.builtin(SurfaceTable.REG_TIME_PERIOD);
		b.out(OcslWire.PROP_COLOR, period.splat(4).mul(b.builtin(SurfaceTable.REG_TINT)));
		OcslVm vm = new OcslVm(IrValidator.validate(b.build()));

		try {
			vm.set(SurfaceTable.REG_TIME_PERIOD, 3.0f);
			org.junit.Assert.fail("binding timePeriod must be refused; it is not host state");
		} catch (IllegalArgumentException e) {
			assertTrue("the refusal should say why, got: " + e.getMessage(),
					e.getMessage().contains("constant of the format"));
		}

		vm.set(SurfaceTable.REG_TINT, 1f, 1f, 1f, 1f);
		vm.run();
		float[] out = new float[4];
		vm.output(OcslWire.PROP_COLOR, out);
		assertEquals("and the constant survives the attempt",
				OcslTime.PERIOD_SECONDS, out[0], 0f);
	}

	@Test
	public void aStageWithoutTimePeriodStillConstructs() throws Exception {
		// Bake has no clock and so no period, meaning frameOffset(REG_TIME_PERIOD) is -1 there. The
		// constructor's guard for that was never exercised by any test; removing it survived the
		// whole suite while leaving the bake stage throwing ArrayIndexOutOfBounds at -1.
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_BAKE);
		b.out(OcslWire.PROP_COLOR, b.constant(0.25f, 0.5f, 0.75f, 1.0f));
		OcslVm vm = new OcslVm(IrValidator.validate(b.build()));
		vm.run();

		float[] out = new float[4];
		vm.output(OcslWire.PROP_COLOR, out);
		assertEquals(0.25f, out[0], 0f);
	}

	@Test
	public void theClockResolvesFarFinerThanAFrameAtTheWorstPointInTheDomain() throws Exception {
		// The float32 upper bound that ruled out a day-long period: near the seam the ulp must stay
		// well under a frame, or the clock fails to advance on some frames and motion stutters.
		float nearSeam = OcslTime.PERIOD_SECONDS - 0.05f;
		float ulp = Math.ulp(nearSeam);
		assertTrue("ulp near the seam is " + ulp + " s, which is not far below a 144Hz frame",
				ulp < 0.001f);
	}

	@Test
	public void theVmSeedsTimePeriodFromTheConstantRatherThanFromTheHost() throws Exception {
		// The register is readable now, and its value is a property of the FORMAT. Nothing binds it:
		// a program reads P without the host supplying anything, so a forgotten binding cannot make
		// it 0 -- which the safe-divide rule would otherwise turn into a silent 0 rather than a fault.
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		Expr period = b.builtin(SurfaceTable.REG_TIME_PERIOD);
		b.out(OcslWire.PROP_COLOR, period.splat(4).mul(b.builtin(SurfaceTable.REG_TINT)));

		OcslVm vm = new OcslVm(IrValidator.validate(b.build()));
		vm.set(SurfaceTable.REG_TINT, 1f, 1f, 1f, 1f);
		vm.run();

		float[] out = new float[4];
		vm.output(OcslWire.PROP_COLOR, out);
		assertEquals("the VM must seed timePeriod without any host binding",
				OcslTime.PERIOD_SECONDS, out[0], 0f);
	}

	@Test
	public void timePeriodIsReadableExactlyWhereTimeIs() throws Exception {
		// ANIM-5 asks for the register "at every surface where `time` exists" -- the period is
		// meaningless where there is no clock, and a register readable at one and not the other
		// would be a trap in either direction.
		for (int s = 0; s <= 255; s++) {
			byte stage = (byte) s;
			if (!OcslWire.isKnownStage(stage)) {
				continue;
			}
			boolean hasTime = SurfaceTable.builtinType(stage, SurfaceTable.REG_TIME) != null;
			boolean hasPeriod =
					SurfaceTable.builtinType(stage, SurfaceTable.REG_TIME_PERIOD) != null;
			assertEquals("stage " + s + " must carry time and timePeriod together",
					hasTime, hasPeriod);
		}
	}
}
