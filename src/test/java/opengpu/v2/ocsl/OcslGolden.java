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

		broadcastCases(c);
		sampleCases(c);
		return c;
	}

	/** How many floats this op's result carries. */
	static int resultWidth(String op) {
		if (op.startsWith("sample:")) {
			return 4;
		}
		if (op.startsWith("bc:")) {
			int max = 1;
			int[] w = widths(op);
			for (int i = 0; i < w.length; i++) {
				max = Math.max(max, w[i]);
			}
			return max;
		}
		if (op.equals("cross3")) {
			return 3;
		}
		return op.startsWith("norm") ? Integer.parseInt(op.substring(4)) : 1;
	}

	/**
	 * Broadcast cases, named {@code bc:<op>:<operand widths>:<k|r>}.
	 *
	 * THE COST AMENDMENT 4 TOOK ON AND HAD NOT PAID. Re-opening the broadcast half bought
	 * {@code mix(vec3, vec3, float)} as ONE instruction, in exchange for "conformance vectors must
	 * now cover the broadcast operand positions". The scalar rows above cannot: broadcast does not
	 * live in {@link OcslMath} at all — it lives in {@code OcslVm.component()}, which decides
	 * per-operand whether to read lane {@code i} or lane 0. So these cases build a real program and
	 * run it on the real VM.
	 *
	 * The widths string gives each operand's width in order ({@code 31} is {@code (vec3, float)},
	 * {@code 313} is {@code (vec3, float, vec3)}), and the last field is where the operands come
	 * from. That last distinction is not decoration: {@code component()} takes a DIFFERENT BRANCH
	 * for a constant-pool reference than for a register, and each branch has its own width test, so
	 * a broadcast that worked from constants could still be wrong from registers.
	 */
	private static void broadcastCases(List<Case> c) {
		String[] binary = { "add", "sub", "mul", "div", "mod", "min", "max", "pow", "atan2",
				"step" };
		// Positive and non-round: pow's base must stay in domain, and round numbers hide lane
		// mix-ups because several lanes would agree by accident.
		float[] v3 = { 0.25f, 1.5f, 2.75f };
		float s = 0.6f;
		for (int i = 0; i < binary.length; i++) {
			for (int src = 0; src < 2; src++) {
				String tag = src == 0 ? "k" : "r";
				c.add(new Case("bc:" + binary[i] + ":31:" + tag, v3[0], v3[1], v3[2], s));
				c.add(new Case("bc:" + binary[i] + ":13:" + tag, s, v3[0], v3[1], v3[2]));
			}
		}
		// Every position a float can occupy in a three-operand op, which is what "the broadcast
		// operand POSITIONS" means and what a rule applied to only the last operand would fail.
		String[] ternary = { "clamp", "mix", "smoothstep" };
		float[] lo = { 0.1f, 0.2f, 0.3f };
		float[] hi = { 0.9f, 1.4f, 3.1f };
		for (int i = 0; i < ternary.length; i++) {
			String op = ternary[i];
			c.add(new Case("bc:" + op + ":331:k", lo[0], lo[1], lo[2], hi[0], hi[1], hi[2], s));
			c.add(new Case("bc:" + op + ":313:k", lo[0], lo[1], lo[2], s, hi[0], hi[1], hi[2]));
			c.add(new Case("bc:" + op + ":133:k", s, lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]));
			c.add(new Case("bc:" + op + ":311:k", lo[0], lo[1], lo[2], s, hi[0]));
			c.add(new Case("bc:" + op + ":131:k", s, lo[0], lo[1], lo[2], hi[0]));
			c.add(new Case("bc:" + op + ":113:k", s, hi[0], lo[0], lo[1], lo[2]));
			c.add(new Case("bc:" + op + ":331:r", lo[0], lo[1], lo[2], hi[0], hi[1], hi[2], s));
			c.add(new Case("bc:" + op + ":313:r", lo[0], lo[1], lo[2], s, hi[0], hi[1], hi[2]));
			c.add(new Case("bc:" + op + ":133:r", s, lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]));
		}
		// Widths other than 3, so a broadcast that hardcoded a lane count is visible.
		c.add(new Case("bc:mul:21:k", 0.25f, 1.5f, s));
		c.add(new Case("bc:mul:41:k", 0.25f, 1.5f, 2.75f, -0.5f, s));
		c.add(new Case("bc:mul:14:r", s, 0.25f, 1.5f, 2.75f, -0.5f));
		c.add(new Case("bc:mix:441:r", 0.1f, 0.2f, 0.3f, 0.4f, 0.9f, 1.4f, 3.1f, -1.0f, s));
		// A float in BOTH positions of a component-wise op stays a float -- the result takes a
		// vector width only when an operand has one.
		c.add(new Case("bc:add:11:k", 1.25f, s));
		c.add(new Case("bc:add:11:r", 1.25f, s));
	}

	/**
	 * Sampling cases, named {@code sample:WxH}, with the uv as the arguments.
	 *
	 * The texture CONTENT is not in the file: it is generated from the dimensions by
	 * {@link #testTexture}, so a vector file of a few hundred lines does not have to carry kilobytes of pixels to
	 * pin a filtering rule. The generator is deliberately non-separable in x and y and coprime in
	 * its strides, so a transposed lookup or a swapped tap changes the answer.
	 */
	private static void sampleCases(List<Case> c) {
		// Texel centres of a 2x2: each returns its own texel exactly under the half-texel rule and
		// a blend under endpoint-stretch, which is what distinguishes S2 from its alternative.
		c.add(new Case("sample:2x2", 0.25f, 0.25f));
		c.add(new Case("sample:2x2", 0.75f, 0.25f));
		c.add(new Case("sample:2x2", 0.25f, 0.75f));
		c.add(new Case("sample:2x2", 0.75f, 0.75f));
		// Midpoints: the even blend, and the four-way centre.
		c.add(new Case("sample:2x2", 0.5f, 0.25f));
		c.add(new Case("sample:2x2", 0.5f, 0.5f));
		// The flat shoulder at each edge, and past it -- clamp-to-edge, not wrap.
		c.add(new Case("sample:2x2", 0.0f, 0.0f));
		c.add(new Case("sample:2x2", 1.0f, 1.0f));
		c.add(new Case("sample:2x2", -3.5f, 0.5f));
		c.add(new Case("sample:2x2", 42.0f, 0.5f));
		// Wildly out of range, where casting before clamping would wrap the +1 tap to MIN_VALUE.
		c.add(new Case("sample:2x2", 1.0e30f, 0.5f));
		c.add(new Case("sample:2x2", -1.0e30f, 0.5f));
		// NON-SQUARE, so width and height cannot be transposed without changing the answer.
		c.add(new Case("sample:4x3", 0.3f, 0.7f));
		c.add(new Case("sample:4x3", 0.125f, 0.16666667f));
		c.add(new Case("sample:4x3", 0.9f, 0.1f));
		c.add(new Case("sample:3x4", 0.3f, 0.7f));
		// Fractional weights that are not representable, so the double accumulation is visible.
		c.add(new Case("sample:4x3", 0.1f, 0.2f));
		c.add(new Case("sample:4x3", 0.37f, 0.61f));
		// 1x1: every tap clamps to the same texel and the weights must still sum to 1.
		c.add(new Case("sample:1x1", 0.5f, 0.5f));
		c.add(new Case("sample:1x1", -2.0f, 9.0f));
		// TERM ORDER. S4 pins the order of the four summed products, and until this line NOTHING
		// constrained it: all 23 non-identity permutations reproduced every other vector here. The
		// round uv values are why -- the sum is accumulated in DOUBLE, so order is visible only
		// where the double-rounding error straddles a float32 boundary, which round numbers never
		// do. These two uv were searched for precisely because they do: between them they separate
		// 18 of the 23 permutations.
		c.add(new Case("sample:4x3", 0.85571521520614624f, 0.44612684845924377f));
		c.add(new Case("sample:4x3", 0.81845015f, 0.42062655f));

		// Non-finite uv. NOT reachable from a CPU program -- constants are refused non-finite in
		// three places, set() sanitizes, and every op funnels through f() -- so this freezes the
		// defence-in-depth branch and, more usefully, the rule Stage D must implement explicitly,
		// because GLSL has no catch-all and a NaN uv there picks a driver-specific texel.
		c.add(new Case("sample:2x2", NAN, 0.5f));
		c.add(new Case("sample:2x2", 0.5f, INF));
	}

	/**
	 * A deterministic texture for the sampling vectors:
	 * {@code (x*37 + y*61 + x*y*13 + c*17) & 0xFF}.
	 *
	 * The {@code x*y} term is what makes this NON-SEPARABLE, and the first version did not have it:
	 * {@code x*37 + y*61} is a sum of a function of x and a function of y, which is the definition
	 * of separable, so the javadoc claiming otherwise was false. The mutations that claim exists to
	 * justify -- a transposed lookup, a diagonal tap swap -- happened to be caught anyway by the
	 * {@code & 0xFF} wrap, so it was a wrong rationale rather than a live gap. Fixed so the reason
	 * and the behaviour agree.
	 */
	static byte[] testTexture(int width, int height) {
		byte[] px = new byte[width * height * 4];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				for (int ch = 0; ch < 4; ch++) {
					px[(y * width + x) * 4 + ch] = (byte) ((x * 37 + y * 61 + ch * 17) & 0xFF);
				}
			}
		}
		return px;
	}

	/** The {@link OcslMath} method a vector's op name exercises — width suffixes stripped. */
	static String methodFor(String op) {
		if (op.startsWith("sample:")) {
			return "sample";
		}
		if (op.startsWith("bc:")) {
			// A broadcast case exercises the VM's operand reader, not an OcslMath method; it still
			// names the underlying op so the coverage floor counts it.
			return op.split(":")[1];
		}
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
		if (op.startsWith("sample:")) {
			return 2;
		}
		if (op.startsWith("bc:")) {
			int total = 0;
			int[] w = widths(op);
			for (int i = 0; i < w.length; i++) {
				total += w[i];
			}
			return total;
		}
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
		if (op.startsWith("sample:")) {
			String[] wh = op.substring("sample:".length()).split("x");
			int width = Integer.parseInt(wh[0]);
			int height = Integer.parseInt(wh[1]);
			float[] out = new float[4];
			OcslMath.sample(testTexture(width, height), width, height, a[0], a[1], out, 0);
			return out;
		}
		if (op.startsWith("bc:")) {
			return broadcast(op, a);
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

	/** Widths of each operand, from the {@code bc:op:WIDTHS:src} name. */
	private static int[] widths(String op) {
		String digits = op.split(":")[2];
		int[] w = new int[digits.length()];
		for (int i = 0; i < w.length; i++) {
			w[i] = digits.charAt(i) - '0';
		}
		return w;
	}

	/**
	 * Build a program that performs one broadcasting op, run it on the real VM, and read the
	 * result back.
	 *
	 * Register operands are made by taking {@code abs} of the constant — identity for these values,
	 * one op, and no semantic interference — because that is the cheapest way to get a value into a
	 * REGISTER, which is the branch of {@code component()} a constant reference never takes.
	 */
	private static float[] broadcast(String op, float[] a) {
		String[] parts = op.split(":");
		String name = parts[1];
		int[] w = widths(op);
		boolean registers = parts[3].equals("r");

		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		Expr[] operands = new Expr[w.length];
		int at = 0;
		for (int i = 0; i < w.length; i++) {
			float[] comps = new float[w[i]];
			System.arraycopy(a, at, comps, 0, w[i]);
			at += w[i];
			Expr e = b.constant(comps);
			operands[i] = registers ? e.abs() : e;
		}
		Expr result = apply(b, name, operands);

		// OUT wants a vec4, so widen without disturbing the lanes under test: they stay in 0..n-1
		// whichever widening is used.
		int rw = result.type.width;
		Expr out;
		if (rw == 4) {
			out = result;
		} else if (rw == 3) {
			out = b.vec4(result, b.f(1.0f));
		} else if (rw == 2) {
			out = b.vec4(result, result);
		} else {
			out = result.splat(4);
		}
		b.out(OcslWire.PROP_COLOR, out);

		float[] pixel = new float[4];
		try {
			OcslVm vm = new OcslVm(IrValidator.validate(b.build()));
			vm.run();
			vm.output(OcslWire.PROP_COLOR, pixel);
		} catch (ValidationException e) {
			throw new IllegalStateException("broadcast case " + op + " built an invalid program", e);
		}
		float[] r = new float[rw];
		System.arraycopy(pixel, 0, r, 0, rw);
		return r;
	}

	private static Expr apply(OcslBuilder b, String name, Expr[] o) {
		if (name.equals("add")) return o[0].add(o[1]);
		if (name.equals("sub")) return o[0].sub(o[1]);
		if (name.equals("mul")) return o[0].mul(o[1]);
		if (name.equals("div")) return o[0].div(o[1]);
		if (name.equals("mod")) return o[0].mod(o[1]);
		if (name.equals("min")) return o[0].min(o[1]);
		if (name.equals("max")) return o[0].max(o[1]);
		if (name.equals("pow")) return o[0].pow(o[1]);
		if (name.equals("atan2")) return o[0].atan2(o[1]);
		if (name.equals("step")) return o[0].step(o[1]);
		if (name.equals("clamp")) return o[0].clamp(o[1], o[2]);
		if (name.equals("mix")) return o[0].mix(o[1], o[2]);
		if (name.equals("smoothstep")) return o[0].smoothstep(o[1], o[2]);
		throw new IllegalArgumentException("no broadcast rule for " + name);
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
