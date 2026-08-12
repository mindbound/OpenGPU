package opengpu.v2.ocsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The frozen numeric-domain table, asserted row by row.
 *
 * These are SEMANTICS, not defensive coding. Golden vectors will be generated from this behaviour
 * and frozen, so every row needs a test that would fail if someone "fixed" it into IEEE defaults —
 * `x/0 = Inf` is the natural instinct and the wrong answer here, chosen against so that a program
 * dividing by a player-supplied zero gets the same picture on every client and on both executors.
 */
public strictfp class OcslMathTest {

	private static void eq(String what, float expected, float actual) {
		assertEquals(what, expected, actual, 0.0f);
	}

	@Test
	public void safeDivideIsZeroNotInfinity() {
		eq("x/0", 0f, OcslMath.div(1f, 0f));
		eq("0/0", 0f, OcslMath.div(0f, 0f));
		eq("x/-0", 0f, OcslMath.div(1f, -0.0f));
		eq("ordinary division still divides", 2.5f, OcslMath.div(5f, 2f));
	}

	@Test
	public void logAndPowAndSqrtHaveDefinedEdges() {
		eq("log(0)", 0f, OcslMath.log(0f));
		eq("log(-1)", 0f, OcslMath.log(-1f));
		eq("pow(0,0)", 0f, OcslMath.pow(0f, 0f));
		eq("pow(-2,2)", 0f, OcslMath.pow(-2f, 2f));
		eq("sqrt(-1)", 0f, OcslMath.sqrt(-1f));
		eq("sqrt(4) still works", 2f, OcslMath.sqrt(4f));
	}

	@Test
	public void modByZeroIsZeroAndModIsFloorMod() {
		eq("mod(x,0)", 0f, OcslMath.mod(5f, 0f));
		// Floor-mod, matching GLSL: a negative dividend yields a POSITIVE result, unlike Java's %.
		eq("mod(-1, 3) is floor-mod", 2f, OcslMath.mod(-1f, 3f));
	}

	@Test
	public void atan2AtTheOriginIsZeroForEveryZeroSign() {
		eq("atan2(0,0)", 0f, OcslMath.atan2(0f, 0f));
		eq("atan2(-0,0)", 0f, OcslMath.atan2(-0.0f, 0f));
		eq("atan2(0,-0)", 0f, OcslMath.atan2(0f, -0.0f));
		eq("atan2(-0,-0)", 0f, OcslMath.atan2(-0.0f, -0.0f));
		// Away from the origin it is the real function, not a stub.
		assertTrue(OcslMath.atan2(1f, 0f) > 1.5f);
	}

	@Test
	public void normalizeOfZeroIsZero() {
		// The row the safe divide was made consistent with -- both give 0 rather than a NaN.
		float[] frame = new float[6];
		OcslMath.normalize(frame, 3, 0, 3);
		eq("normalize(0).x", 0f, frame[3]);
		eq("normalize(0).y", 0f, frame[4]);
		eq("normalize(0).z", 0f, frame[5]);
	}

	@Test
	public void smoothstepIsTheRampNotAStepAndDiffersFromStepAtEquality() {
		// Pinned formula: t = clamp((x-e0)/max(e1-e0, EPS), 0, 1); t*t*(3-2t).
		eq("below e0", 0f, OcslMath.smoothstep(0f, 1f, -1f));
		eq("above e1", 1f, OcslMath.smoothstep(0f, 1f, 2f));
		assertEquals("midpoint", 0.5f, OcslMath.smoothstep(0f, 1f, 0.5f), 1e-6f);

		// THE DEGENERATE CASE, and the one the design spells out: with coincident edges this is a
		// ramp of width EPS rising FROM e0, so at x == e0 it is 0 -- the OPPOSITE of step's 1.
		eq("smoothstep at equality", 0f, OcslMath.smoothstep(1f, 1f, 1f));
		eq("step at equality", 1f, OcslMath.step(1f, 1f));
		eq("smoothstep just above coincident edges", 1f, OcslMath.smoothstep(1f, 1f, 1.001f));
	}

	@Test
	public void everyOpIsTotalOnNonFiniteOperands() {
		// A4's catch-all: any value-computing op receiving a non-finite operand produces 0 for
		// that component. One branch, consistent with every other row's choice of 0, and it turns
		// an overflow into a defined value identical on every client.
		float inf = Float.POSITIVE_INFINITY, nan = Float.NaN;
		eq("add(Inf, 1)", 0f, OcslMath.add(inf, 1f));
		eq("mul(NaN, 2)", 0f, OcslMath.mul(nan, 2f));
		eq("sin(Inf)", 0f, OcslMath.sin(inf));
		eq("min(NaN, 1)", 0f, OcslMath.min(nan, 1f));
		eq("clamp(NaN,0,1)", 0f, OcslMath.clamp(nan, 0f, 1f));
	}

	@Test
	public void theCatchAllProducesZeroRatherThanSubstitutingZeroForTheOperand() {
		// THE ROW THAT WAS IMPLEMENTED WRONG, and the failure that caught it was add(Inf, 1) = 1.
		// Substituting 0 for a bad operand and then computing is amendment 1b's GUARD LOWERING,
		// which is for domain edges in codegen. A4's catch-all is that the OP produces 0. Two
		// different rules, and only some inputs tell them apart -- these are those inputs, chosen
		// per op so that substitution yields a plausible NON-zero answer and production yields 0.
		float inf = Float.POSITIVE_INFINITY, nan = Float.NaN;
		// MUTATION-CHECKED, and the first draft of this list was not. Deleting the operand guard
		// from every op but clamp left the whole suite green, because the values chosen here were
		// ones the RESULT guard f() catches anyway: min/max(NaN, x) is NaN in StrictMath, so f()
		// zeroes it either way; step(Inf, 1) is 0 by the comparison alone; smoothstep(NaN, ...)
		// carries the NaN through the formula. The rows below are picked so that removing the
		// operand guard yields a plausible FINITE number, which is the failure being guarded
		// against -- verified by mutating bad() to `return false` and watching each one fail.
		eq("min: without the guard, StrictMath.min(+Inf, -5) = -5", 0f, OcslMath.min(inf, -5f));
		eq("max: without the guard, StrictMath.max(-Inf, 5) = 5", 0f, OcslMath.max(-inf, 5f));
		eq("step: without the guard, +Inf >= 3 gives 1", 0f, OcslMath.step(3f, inf));
		eq("step: without the guard, NaN < 3 is false, also 1", 0f, OcslMath.step(3f, nan));
		eq("smoothstep: without the guard, +Inf clamps to t=1", 0f, OcslMath.smoothstep(0f, 10f, inf));
		eq("atan2: without the guard, atan2(+Inf, 1) = pi/2", 0f, OcslMath.atan2(inf, 1f));
		eq("atan2: without the guard, atan2(1, -Inf) = pi", 0f, OcslMath.atan2(1f, -inf));
		eq("atan2: without the guard, atan2(+Inf, +Inf) = pi/4", 0f, OcslMath.atan2(inf, inf));
		eq("div: without the guard, -8/+Inf = -0.0", 0f, OcslMath.div(-8f, inf));

		// cross via the same lens: without the guard the +Inf lane never multiplies into x, so x
		// comes out as an ordinary -3.
		float[] crossFrame = { inf, 1f, 2f, 3f, 4f, 5f, 0f, 0f, 0f };
		OcslMath.cross(crossFrame, 6, 0, 3);
		eq("cross.x with a non-finite lane anywhere", 0f, crossFrame[6]);
		eq("cross.y", 0f, crossFrame[7]);
		eq("cross.z", 0f, crossFrame[8]);

		eq("add: substitution would give 1", 0f, OcslMath.add(inf, 1f));
		eq("sub: substitution would give -1", 0f, OcslMath.sub(nan, 1f));
		eq("div: substitution would give 0/2", 0f, OcslMath.div(inf, 2f));
		eq("div by Inf: substitution would divide by zero, also 0", 0f, OcslMath.div(1f, inf));
		eq("max: substitution would give 5", 0f, OcslMath.max(nan, 5f));
		eq("min: substitution would give -5", 0f, OcslMath.min(nan, -5f));
		eq("step: substitution would give 1", 0f, OcslMath.step(inf, 1f));
		eq("mix: substitution would give 2", 0f, OcslMath.mix(inf, 2f, 1f));
		eq("clamp: substitution would give 1", 0f, OcslMath.clamp(inf, 1f, 3f));
		eq("smoothstep: substitution would give 1", 0f, OcslMath.smoothstep(nan, 1f, 2f));
		eq("mod: substitution would give 1", 0f, OcslMath.mod(inf, 3f));
		eq("floor: substitution would give 0 either way, pinned anyway", 0f, OcslMath.floor(inf));

		// The reductions have no per-component result to spare, so one bad lane zeroes the whole
		// answer -- substitution would have let the good lanes carry a partial sum out.
		float[] frame = { 1f, inf, 3f, 4f, 1f, 1f, 1f, 1f, 0f, 0f, 0f, 0f };
		eq("dot: substitution would give 1+0+3+4 = 8", 0f, OcslMath.dot(frame, 0, 4, 4));
		eq("length: substitution would give sqrt(26)", 0f, OcslMath.length(frame, 0, 4));
		eq("distance: substitution would give a partial distance", 0f,
				OcslMath.distance(frame, 0, 4, 4));

		// normalize writes its destination either way -- an early return would leave the register
		// holding whatever was there before, which is the one outcome worse than a wrong number.
		frame[8] = 7f;
		frame[9] = 7f;
		frame[10] = 7f;
		OcslMath.normalize(frame, 8, 0, 3);
		eq("normalize wrote lane 0", 0f, frame[8]);
		eq("normalize wrote lane 1", 0f, frame[9]);
		eq("normalize wrote lane 2", 0f, frame[10]);
	}

	@Test
	public void ingressStillSubstitutesBecauseThereIsNoOpToZero() {
		// The other half of the distinction, and why san() survives the correction. A uniform
		// arriving non-finite has no op producing it -- it is the operand itself that must be made
		// representable, so here substitution IS the rule. The VM applies this at setUniform.
		eq("a non-finite uniform lands as 0", 0f, OcslMath.san(Float.NaN));
		eq("and then behaves like an ordinary zero", 1f, OcslMath.add(OcslMath.san(Float.NaN), 1f));
		eq("a finite uniform is untouched", 2.5f, OcslMath.san(2.5f));
	}

	@Test
	public void anInDomainOverflowIsAlsoCaught() {
		// The route the design says stays open once setUniform refuses non-finites: a LEGAL,
		// in-domain evaluation that overflows float32. exp(100) is finite in double and infinite
		// in float, and letting that escape would make "each op is total" false exactly where the
		// rule was written for.
		assertTrue("exp(100) overflows float32", Float.isInfinite((float) StrictMath.exp(100.0)));
		eq("and the VM defines it as 0", 0f, OcslMath.exp(100f));
	}

	@Test
	public void transcendentalsComeFromStrictMath() {
		// Not a tautology: the pin is that these are fdlibm, which is bit-identical across
		// platforms, where Math.sin is permitted 1 ulp and intrinsifies per architecture. If
		// someone swaps StrictMath for Math, this fails on any host where they differ -- and
		// silently passes where they agree, which is why the javadoc carries the reason too.
		for (float x : new float[] { 0.5f, 1.5f, -2.25f, 3.14159f, 100.5f }) {
			eq("sin", (float) StrictMath.sin(x), OcslMath.sin(x));
			eq("cos", (float) StrictMath.cos(x), OcslMath.cos(x));
		}
		eq("exp", (float) StrictMath.exp(2.0), OcslMath.exp(2f));
		eq("log", (float) StrictMath.log(2.0), OcslMath.log(2f));
	}

	@Test
	public void epsIsTheFrozenValue() {
		eq("EPS is semantics, not a tuning knob", 1.0e-37f, OcslMath.EPS);
	}
}
