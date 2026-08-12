package opengpu.v2.ocsl;

import java.util.ArrayList;
import java.util.List;

/**
 * THE GOLDEN VECTOR CASE LIST, and the one evaluator both the generator and the checker use.
 *
 * A4 pins the CPU VM's arithmetic as machine-independent precisely so that a vector suite can
 * assert VALUES that survive a CI runner on another architecture — {@code strictfp}, StrictMath-only
 * transcendentals, float32 narrowing at every register write. This is that suite's input.
 *
 * <h2>The split that makes it a golden suite rather than a tautology</h2>
 *
 * The CASE LIST lives here, in code. The EXPECTED VALUES live in
 * {@code src/test/resources/ocsl/golden-vectors.txt}, checked in, and nothing in the test path
 * writes that file. {@link OcslGoldenTest} reads it and compares; it cannot regenerate it. So a
 * change in arithmetic surfaces as a DATA DIFF in review — a reviewer sees `sin(0.5)` move by a
 * ulp — rather than as an edited assertion inside a test, which is the form the same change would
 * take if the expectations lived in Java and which is far easier to wave through.
 *
 * Regenerating is a deliberate act: run {@link OcslGoldenGenerator}, look at the diff, and justify
 * every line that moved. There is no flag that makes the test rewrite its own expectations.
 *
 * <h2>Values are hex bit patterns</h2>
 *
 * {@code 0x3f800000}, not {@code 1.0}. Decimal text round-trips through a parser and loses the
 * distinction between 0.0 and -0.0 unless everyone is careful; bit patterns are exact, and a
 * one-ulp drift is a visible character change rather than a digit hiding at the end of a decimal
 * expansion. Each line carries a decimal rendering after a `;` for the reader, which the parser
 * ignores.
 */
final class OcslGolden {
	private OcslGolden() {}

	static final String RESOURCE = "/ocsl/golden-vectors.txt";

	static final class Case {
		final String op;
		final float[] args;

		Case(String op, float... args) {
			this.op = op;
			this.args = args;
		}
	}

	private static final float INF = Float.POSITIVE_INFINITY;
	private static final float NAN = Float.NaN;

	/**
	 * Every case, in a fixed order.
	 *
	 * Order is part of the artifact: the file is compared line by line, so reordering this list
	 * rewrites the file and shows up as a diff. That is deliberate — it makes an accidental
	 * reshuffle visible instead of silent.
	 */
	static List<Case> cases() {
		List<Case> c = new ArrayList<Case>();

		// ---- ordinary in-range arithmetic. Without these a "fix" that breaks only the normal
		// path while preserving every edge case would pass a suite made purely of edge cases.
		for (String op : new String[] { "add", "sub", "mul", "div", "mod", "min", "max", "pow",
				"atan2", "step" }) {
			c.add(new Case(op, 5.0f, 2.0f));
			c.add(new Case(op, -3.25f, 1.5f));
			c.add(new Case(op, 0.1f, 0.3f));
		}
		for (String op : new String[] { "neg", "abs", "floor", "fract", "exp", "log", "sqrt",
				"sin", "cos" }) {
			c.add(new Case(op, 0.5f));
			c.add(new Case(op, 2.25f));
			c.add(new Case(op, -1.75f));
			c.add(new Case(op, 100.5f));
		}
		for (String op : new String[] { "clamp", "mix", "smoothstep" }) {
			c.add(new Case(op, 0.25f, 0.75f, 0.5f));
			c.add(new Case(op, -2.0f, 3.0f, 1.25f));
		}
		// CLAMP NEEDS ITS OWN TRIPLES. The two above were chosen for mix(a,b,t) and
		// smoothstep(e0,e1,x), where they are ordinary; read as clamp(x,lo,hi) BOTH have lo > hi
		// and both answer `hi`. Mutating clamp to `return hi` passed all 126 vectors and the whole
		// 132-test package -- clamp was never once tested clamping, and it is the op P4 uses twice.
		c.add(new Case("clamp", 0.5f, 0.0f, 1.0f));    // inside the interval: returns x
		c.add(new Case("clamp", -3.0f, 0.0f, 1.0f));   // below: returns lo
		c.add(new Case("clamp", 7.5f, 0.0f, 1.0f));    // above: returns hi

		// ---- the frozen domain table, row by row. Each of these is a SEMANTIC choice, not a
		// crash guard, and each was chosen against the IEEE default.
		c.add(new Case("div", 1.0f, 0.0f));          // x/0 = 0, not Inf
        c.add(new Case("div", 0.0f, 0.0f));
		c.add(new Case("div", 1.0f, -0.0f));
		c.add(new Case("mod", 5.0f, 0.0f));          // mod(x,0) = 0
		c.add(new Case("mod", -1.0f, 3.0f));         // floor-mod: positive, unlike Java's %
		c.add(new Case("log", 0.0f));                // log(x<=0) = 0
		c.add(new Case("log", -1.0f));
		c.add(new Case("pow", 0.0f, 0.0f));          // pow(x<=0, y) = 0, including 0^0
		c.add(new Case("pow", -2.0f, 2.0f));
		c.add(new Case("sqrt", -1.0f));              // sqrt(x<0) = 0
		c.add(new Case("atan2", 0.0f, 0.0f));        // the pole, all four zero signs
		c.add(new Case("atan2", -0.0f, 0.0f));
		c.add(new Case("atan2", 0.0f, -0.0f));
		c.add(new Case("atan2", -0.0f, -0.0f));
		c.add(new Case("step", 1.0f, 1.0f));         // at equality step is 1 ...
		c.add(new Case("smoothstep", 1.0f, 1.0f, 1.0f));   // ... and smoothstep is 0
		c.add(new Case("smoothstep", 1.0f, 1.0f, 1.001f));
		c.add(new Case("norm3", 0.0f, 0.0f, 0.0f));  // normalize(0) = 0
		c.add(new Case("exp", 100.0f));              // in-domain overflow of float32 -> 0

		// ---- A4's catch-all, at the inputs where PRODUCING zero differs from SUBSTITUTING it.
		// The first draft substituted, and add(Inf,1) came out 1.
		c.add(new Case("add", INF, 1.0f));
		c.add(new Case("sub", NAN, 1.0f));
		c.add(new Case("mul", NAN, 2.0f));
		c.add(new Case("div", INF, 2.0f));
		c.add(new Case("div", -8.0f, INF));
		c.add(new Case("min", INF, -5.0f));
		c.add(new Case("max", -INF, 5.0f));
		c.add(new Case("step", 3.0f, INF));
		c.add(new Case("step", 3.0f, NAN));
		c.add(new Case("mod", INF, 3.0f));
		c.add(new Case("pow", INF, 2.0f));
		c.add(new Case("atan2", INF, 1.0f));
		c.add(new Case("atan2", 1.0f, -INF));
		c.add(new Case("atan2", INF, INF));
		c.add(new Case("sin", INF));
		c.add(new Case("cos", NAN));
		c.add(new Case("floor", INF));
		c.add(new Case("clamp", NAN, 0.0f, 1.0f));
		c.add(new Case("clamp", INF, 1.0f, 3.0f));
		c.add(new Case("mix", INF, 2.0f, 1.0f));
		c.add(new Case("smoothstep", NAN, 1.0f, 2.0f));

		// ---- the reducing ops. Whole-result catch-all: one bad lane zeroes the answer rather
		// than dropping out of the sum.
		// ACCUMULATION WIDTH. Every reducing row below used exactly-representable inputs, so a
		// float32 accumulator reproduced all of them bit for bit and A8's "accumulate in double,
		// narrow once" was frozen by nothing. These inputs are not representable, so the two
		// accumulators disagree in the last bit and the file can finally see which one is running.
		c.add(new Case("dot3", 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f));
		c.add(new Case("len3", 0.1f, 0.2f, 0.3f));
		c.add(new Case("dist3", 0.1f, 0.2f, 0.3f, 0.7f, 1.1f, 1.3f));

		// WIDTHS OTHER THAN 3. The VM calls these with the operand's own width, and the dry run
		// emits ocsl_normalize2 for vec2, so hardcoding 3 inside length/normalize would have gone
		// unnoticed here.
		c.add(new Case("len2", 3.0f, 4.0f));
		c.add(new Case("len4", 1.0f, 1.0f, 1.0f, 1.0f));
		c.add(new Case("norm2", 3.0f, 4.0f));
		c.add(new Case("norm4", 0.0f, 0.0f, 0.0f, 2.0f));
		c.add(new Case("dist2", 1.0f, 2.0f, 4.0f, 6.0f));

		// SIGNED ZERO AS A RESULT. The file's own header justifies bit patterns by "so 0.0 and
		// -0.0 stay distinct", and no frozen RESULT was -0.0 -- so a backend that flushed every
		// zero result to +0.0, which is exactly what a GPU lowering might do, passed the suite.
		c.add(new Case("neg", 0.0f));
		c.add(new Case("min", 0.0f, -0.0f));
		c.add(new Case("max", -0.0f, 0.0f));
		c.add(new Case("floor", -0.0f));
		c.add(new Case("mul", -1.0f, 0.0f));

		// INGRESS. `san` is the one place substitution is still correct -- a uniform arriving
		// non-finite has no op to produce a result for -- and it had no vector at all, which the
		// hand-written coverage list could not notice. It is also the exact rule whose
		// substitute-vs-produce confusion was the corrected defect of 2026-08-12.
		c.add(new Case("san", Float.NaN));
		c.add(new Case("san", INF));
		c.add(new Case("san", 2.5f));
		c.add(new Case("san", -0.0f));

		// mod with a NEGATIVE divisor, where floor-mod and Java's % differ in the other direction.
		// The single mod(-1,3)=2 row only covered the positive-divisor half.
		c.add(new Case("mod", 1.0f, -3.0f));
		c.add(new Case("mod", -1.0f, -3.0f));

		// THE SUB-EPS BAND, and smoothstep's max(). EPS is frozen as semantics -- it sets the width
		// of the ramp smoothstep degenerates to -- and nothing constrained it: EPS could be raised
		// by 33 orders of magnitude, or the max() deleted outright, with the file none the wiser
		// because the two coincident-edge rows sit far outside the band they are meant to pin.
		c.add(new Case("smoothstep", 0.0f, 0.0f, 5.0e-38f));
		c.add(new Case("smoothstep", 0.0f, 1.0e-37f, 5.0e-38f));
		c.add(new Case("smoothstep", 0.0f, 1.0e-30f, 5.0e-31f));
		c.add(new Case("log", 1.0e-38f));
		c.add(new Case("norm2", 1.0e-38f, 0.0f));

		c.add(new Case("dot2", 1.0f, 2.0f, 3.0f, 4.0f));
		c.add(new Case("dot3", 1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f));
		c.add(new Case("dot4", 0.5f, -1.5f, 2.0f, 3.0f, 1.0f, 1.0f, 1.0f, 1.0f));
		c.add(new Case("dot3", 1.0f, INF, 3.0f, 1.0f, 1.0f, 1.0f));
		c.add(new Case("len3", 3.0f, 4.0f, 12.0f));
		c.add(new Case("len3", 1.0f, INF, 3.0f));
		c.add(new Case("dist3", 1.0f, 2.0f, 3.0f, 4.0f, 6.0f, 15.0f));
		c.add(new Case("norm3", 3.0f, 4.0f, 0.0f));
		c.add(new Case("norm3", 1.0f, INF, 3.0f));

		// ---- cross. THE VALUES THAT MOVED when it stopped being sub(mul,mul) in the interpreter
		// and became a primitive accumulating in double: about 29.5% of ordinary finite inputs
		// changed, and nothing pinned the new ones until here.
		c.add(new Case("cross3", 1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f));
		c.add(new Case("cross3", 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f));
		c.add(new Case("cross3", 1.7f, -2.3f, 0.9f, 3.1f, 0.45f, -1.2f));
		// The laundering case: mul overflows float32, the catch-all zeroes it, and composing would
		// have fed that zero to sub and returned a plausible -1e-20 for a value near 1e40.
		c.add(new Case("cross3", 0.0f, 1e20f, 1e-20f, 0.0f, 1.0f, 1e20f));
		c.add(new Case("cross3", INF, 1.0f, 2.0f, 3.0f, 4.0f, 5.0f));

		return c;
	}

	/** How many floats this op's result carries. */
	static int resultWidth(String op) {
		if (op.equals("cross3")) {
			return 3;
		}
		return op.startsWith("norm") ? Integer.parseInt(op.substring(4)) : 1;
	}

	/** The {@link OcslMath} method a vector's op name exercises — width suffixes stripped. */
	static String methodFor(String op) {
		if (op.startsWith("norm")) {
			return "normalize";
		}
		if (op.startsWith("len")) {
			return "length";
		}
		if (op.startsWith("dist")) {
			return "distance";
		}
		if (op.startsWith("dot")) {
			return "dot";
		}
		if (op.startsWith("cross")) {
			return "cross";
		}
		return op;
	}

	/**
	 * How many floats this op CONSUMES. Checked in both the generator and the checker, because
	 * {@link #evaluate} used to take whatever it was handed: a {@code dot3} row given four
	 * arguments was silently evaluated as a {@code dot2}, and a {@code norm3} row given five had
	 * two of them ignored, so a malformed file produced a self-consistent wrong answer.
	 */
	static int argWidth(String op) {
		if (op.startsWith("dot") || op.startsWith("dist")) {
			return 2 * Integer.parseInt(op.substring(op.startsWith("dot") ? 3 : 4));
		}
		if (op.startsWith("norm") || op.startsWith("len")) {
			return Integer.parseInt(op.substring(op.startsWith("norm") ? 4 : 3));
		}
		if (op.equals("cross3")) {
			return 6;
		}
		if (op.equals("clamp") || op.equals("mix") || op.equals("smoothstep")) {
			return 3;
		}
		if (op.equals("san") || op.equals("neg") || op.equals("abs") || op.equals("floor") || op.equals("fract")
				|| op.equals("exp") || op.equals("log") || op.equals("sqrt") || op.equals("sin")
				|| op.equals("cos")) {
			return 1;
		}
		return 2;
	}

	/**
	 * The ONE evaluator, shared by the generator and the checker.
	 *
	 * Shared deliberately: if the generator and the test each had their own dispatch, the file
	 * would pin the agreement of two copies of a switch statement rather than the behaviour of
	 * {@link OcslMath}.
	 */
	static float[] evaluate(String op, float[] a) {
		if (a.length != argWidth(op)) {
			throw new IllegalArgumentException(op + " consumes " + argWidth(op)
					+ " floats, got " + a.length);
		}
		float[] frame;
		if (op.equals("san")) return one(OcslMath.san(a[0]));
		if (op.equals("add")) return one(OcslMath.add(a[0], a[1]));
		if (op.equals("sub")) return one(OcslMath.sub(a[0], a[1]));
		if (op.equals("mul")) return one(OcslMath.mul(a[0], a[1]));
		if (op.equals("div")) return one(OcslMath.div(a[0], a[1]));
		if (op.equals("mod")) return one(OcslMath.mod(a[0], a[1]));
		if (op.equals("min")) return one(OcslMath.min(a[0], a[1]));
		if (op.equals("max")) return one(OcslMath.max(a[0], a[1]));
		if (op.equals("pow")) return one(OcslMath.pow(a[0], a[1]));
		if (op.equals("atan2")) return one(OcslMath.atan2(a[0], a[1]));
		if (op.equals("step")) return one(OcslMath.step(a[0], a[1]));
		if (op.equals("neg")) return one(OcslMath.neg(a[0]));
		if (op.equals("abs")) return one(OcslMath.abs(a[0]));
		if (op.equals("floor")) return one(OcslMath.floor(a[0]));
		if (op.equals("fract")) return one(OcslMath.fract(a[0]));
		if (op.equals("exp")) return one(OcslMath.exp(a[0]));
		if (op.equals("log")) return one(OcslMath.log(a[0]));
		if (op.equals("sqrt")) return one(OcslMath.sqrt(a[0]));
		if (op.equals("sin")) return one(OcslMath.sin(a[0]));
		if (op.equals("cos")) return one(OcslMath.cos(a[0]));
		if (op.equals("clamp")) return one(OcslMath.clamp(a[0], a[1], a[2]));
		if (op.equals("mix")) return one(OcslMath.mix(a[0], a[1], a[2]));
		if (op.equals("smoothstep")) return one(OcslMath.smoothstep(a[0], a[1], a[2]));
		if (op.startsWith("dot")) {
			int w = a.length / 2;
			frame = a.clone();
			return one(OcslMath.dot(frame, 0, w, w));
		}
		if (op.startsWith("len")) {
			frame = a.clone();
			return one(OcslMath.length(frame, 0, a.length));
		}
		if (op.startsWith("dist")) {
			int w = a.length / 2;
			frame = a.clone();
			return one(OcslMath.distance(frame, 0, w, w));
		}
		if (op.startsWith("norm")) {
			int w = a.length;
			frame = new float[2 * w];
			System.arraycopy(a, 0, frame, 0, w);
			OcslMath.normalize(frame, w, 0, w);
			float[] out = new float[w];
			System.arraycopy(frame, w, out, 0, w);
			return out;
		}
		if (op.equals("cross3")) {
			frame = new float[9];
			System.arraycopy(a, 0, frame, 0, 6);
			OcslMath.cross(frame, 6, 0, 3);
			return new float[] { frame[6], frame[7], frame[8] };
		}
		throw new IllegalArgumentException("no evaluator for op " + op);
	}

	private static float[] one(float v) {
		return new float[] { v };
	}

	/** One line of the file: op, hex args, hex results, and a decimal rendering for the reader. */
	static String line(Case c, float[] result) {
		StringBuilder sb = new StringBuilder();
		sb.append(c.op);
		for (int i = 0; i < c.args.length; i++) {
			sb.append(' ').append(hex(c.args[i]));
		}
		sb.append(" ->");
		for (int i = 0; i < result.length; i++) {
			sb.append(' ').append(hex(result[i]));
		}
		sb.append("   ; ").append(c.op).append('(');
		for (int i = 0; i < c.args.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(c.args[i]);
		}
		sb.append(") = ");
		for (int i = 0; i < result.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(result[i]);
		}
		return sb.toString();
	}

	static String hex(float f) {
		return String.format("%08x", Integer.valueOf(Float.floatToRawIntBits(f)));
	}

	static float unhex(String s) {
		return Float.intBitsToFloat((int) Long.parseLong(s, 16));
	}
}
