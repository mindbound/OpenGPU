package opengpu.v2.ocsl;

/**
 * THE NUMERIC DOMAIN TABLE, in code. Every arithmetic decision OCSL makes lives here and nowhere
 * else, so that two executors cannot disagree by a ulp and a golden vector generated on one
 * machine still holds on another.
 *
 * {@code strictfp}, and that is not decoration: Java 8 float arithmetic is not strict by default,
 * so a 32-bit x87 host can double-round intermediate results. Every method is also evaluated in
 * DOUBLE and narrowed to float32 exactly once, at the return — the pixel dry run filed that as a
 * NOTE and A4 promoted it, because otherwise the frame's float32 width is a side effect of how the
 * interpreter happens to be typed rather than a property anyone stated.
 *
 * NEVER BARE {@code java.lang.Math} FOR A TRANSCENDENTAL. That prohibition was in the design
 * without a substitute; A4 named one. {@link StrictMath} is fdlibm by specification and therefore
 * bit-identical across platforms and JDKs, where {@code Math.sin} is permitted 1 ulp of slack and
 * intrinsifies differently per architecture. {@code sqrt} is the exception that proves the rule:
 * it is correctly rounded per IEEE 754 on either class, and is routed here only for uniformity.
 *
 * THE DOMAINS ARE SEMANTICS, NOT DEFENSIVE CODING. {@code x/0 = 0} is not "avoid a crash", it is
 * the defined answer, chosen to agree with {@code normalize(0) = 0}. A program that divides by a
 * player-supplied zero gets 0 on every client, in the CPU VM and on the GPU, today and after a
 * codegen rewrite.
 */
public final strictfp class OcslMath {
	private OcslMath() {}

	/**
	 * Frozen, and it is semantics rather than an implementation detail: it sets the width of the
	 * ramp {@code smoothstep} degenerates to when its edges coincide.
	 */
	public static final float EPS = 1.0e-37f;

	// ---------------------------------------------------------------- the catch-all
	// A4: any value-computing op receiving a non-finite operand produces 0 for that component.
	// One branch, consistent with every existing row's choice of 0, and it makes "each op is
	// total" literally true -- an overflow becomes a defined, visible, identical-everywhere value
	// instead of a NaN that propagates differently per backend.
	//
	// SELECT IS EXEMPT BY CONSTRUCTION and the exemption lives in the interpreter, not here:
	// strict-pick discards the non-selected arm before any operand reaches an op, so
	// select(true, 1.0, Inf) is 1.0 while select(true, Inf, 1.0) is 0.

	public static boolean finite(float x) {
		return !Float.isNaN(x) && !Float.isInfinite(x);
	}

	/**
	 * The catch-all predicate: is any operand of this op non-finite?
	 *
	 * CORRECTED 2026-08-12, and the distinction is the rule rather than a detail. The first draft
	 * SUBSTITUTED zero for the offending operand and then computed, which made {@code add(Inf, 1)}
	 * come out 1. Substitution is amendment 1b's GUARD LOWERING — a codegen technique for keeping a
	 * domain edge in range before a select. A4's catch-all is a different rule and says so
	 * literally: the op "produces 0 for that component". The whole result is zero, so a non-finite
	 * cannot cross an op wearing a plausible finite value.
	 */
	private static boolean bad(float a) {
		return !finite(a);
	}

	private static boolean bad(float a, float b) {
		return !finite(a) || !finite(b);
	}

	private static boolean bad(float a, float b, float c) {
		return !finite(a) || !finite(b) || !finite(c);
	}

	/**
	 * Sanitize a value ENTERING the frame from outside — a uniform, a built-in binding.
	 *
	 * Not the catch-all. An ingress value has no op to produce a result for; it is the operand
	 * itself that must be made representable before any op can see it, and this is the one place
	 * where substituting zero is the correct move.
	 */
	public static float san(float x) {
		return finite(x) ? x : 0.0f;
	}

	// ---------------------------------------------------------------- arithmetic

	public static float add(float a, float b) {
		return bad(a, b) ? 0.0f : f(a + (double) b);
	}

	public static float sub(float a, float b) {
		return bad(a, b) ? 0.0f : f(a - (double) b);
	}

	public static float mul(float a, float b) {
		return bad(a, b) ? 0.0f : f(a * (double) b);
	}

	/** Safe divide: {@code x/0 = 0} and {@code 0/0 = 0}, consistent with {@code normalize(0)}. */
	public static float div(float a, float b) {
		return bad(a, b) || b == 0.0f ? 0.0f : f(a / (double) b);
	}

	public static float neg(float a) {
		return bad(a) ? 0.0f : f(-(double) a);
	}

	// ---------------------------------------------------------------- the zoo

	public static float abs(float a) {
		return bad(a) ? 0.0f : f(StrictMath.abs((double) a));
	}

	public static float floor(float a) {
		return bad(a) ? 0.0f : f(StrictMath.floor(a));
	}

	/** Floor-fract, matching GLSL: {@code x - floor(x)}. */
	public static float fract(float a) {
		if (bad(a)) {
			return 0.0f;
		}
		double x = a;
		return f(x - StrictMath.floor(x));
	}

	/** Floor-mod, matching GLSL. {@code mod(x, 0) = 0}. */
	public static float mod(float a, float b) {
		if (bad(a, b) || b == 0.0f) {
			return 0.0f;
		}
		double x = a, y = b;
		return f(x - y * StrictMath.floor(x / y));
	}

	public static float min(float a, float b) {
		return bad(a, b) ? 0.0f : f(StrictMath.min((double) a, (double) b));
	}

	public static float max(float a, float b) {
		return bad(a, b) ? 0.0f : f(StrictMath.max((double) a, (double) b));
	}

	/** Pinned as {@code min(max(x, lo), hi)} — the composition, not an independent definition. */
	public static float clamp(float x, float lo, float hi) {
		return bad(x, lo, hi) ? 0.0f
				: f(StrictMath.min(StrictMath.max((double) x, (double) lo), (double) hi));
	}

	public static float mix(float a, float b, float t) {
		if (bad(a, b, t)) {
			return 0.0f;
		}
		double w = t;
		return f(a * (1.0 - w) + b * w);
	}

	/** At equality {@code step} yields 1 — deliberately the opposite of smoothstep's 0. */
	public static float step(float edge, float x) {
		return bad(edge, x) ? 0.0f : (x < edge ? 0.0f : 1.0f);
	}

	/**
	 * {@code t = clamp((x-e0)/max(e1-e0, EPS), 0, 1); t*t*(3-2t)}.
	 *
	 * Written from the pinned formula rather than from an informal "degenerates to a step": with
	 * coincident edges this is a ramp of width EPS rising FROM e0, so its value at {@code x <= e0}
	 * is 0 — the opposite of {@code step}'s at-equality 1. Conformance vectors are generated from
	 * this precise behaviour, and the max() is what makes the degenerate case defined at all.
	 */
	public static float smoothstep(float e0, float e1, float x) {
		if (bad(e0, e1, x)) {
			return 0.0f;
		}
		double a = e0, b = e1;
		double denom = StrictMath.max(b - a, (double) EPS);
		double t = StrictMath.min(StrictMath.max((x - a) / denom, 0.0), 1.0);
		return f(t * t * (3.0 - 2.0 * t));
	}

	/** {@code pow(x<=0, y) = 0}, including {@code pow(0,0) = 0}. */
	public static float pow(float a, float b) {
		return bad(a, b) || a <= 0.0f ? 0.0f : f(StrictMath.pow(a, b));
	}

	public static float exp(float a) {
		return bad(a) ? 0.0f : f(StrictMath.exp(a));
	}

	/** {@code log(x<=0) = 0}. */
	public static float log(float a) {
		return bad(a) || a <= 0.0f ? 0.0f : f(StrictMath.log(a));
	}

	/** {@code sqrt(x<0) = 0}. */
	public static float sqrt(float a) {
		return bad(a) || a < 0.0f ? 0.0f : f(StrictMath.sqrt(a));
	}

	public static float sin(float a) {
		return bad(a) ? 0.0f : f(StrictMath.sin(a));
	}

	public static float cos(float a) {
		return bad(a) ? 0.0f : f(StrictMath.cos(a));
	}

	/** {@code atan2(0,0) = 0} for every combination of zero signs, including negative zero. */
	public static float atan2(float y, float x) {
		if (bad(y, x) || (y == 0.0f && x == 0.0f)) {
			return 0.0f;
		}
		return f(StrictMath.atan2(y, x));
	}

	// ---------------------------------------------------------------- vector helpers
	// Written over (array, offset, width) so the VM never allocates a temporary to call them.
	//
	// These are the REDUCING ops, and the catch-all is correspondingly whole-result: their output
	// has no per-component structure to spare, so one non-finite input component zeroes the answer
	// rather than dropping out of a sum. Each therefore scans before it computes.

	public static float dot(float[] frame, int a, int b, int width) {
		for (int i = 0; i < width; i++) {
			if (bad(frame[a + i], frame[b + i])) {
				return 0.0f;
			}
		}
		double sum = 0.0;
		for (int i = 0; i < width; i++) {
			sum += (double) frame[a + i] * frame[b + i];
		}
		return f(sum);
	}

	public static float length(float[] frame, int a, int width) {
		for (int i = 0; i < width; i++) {
			if (bad(frame[a + i])) {
				return 0.0f;
			}
		}
		double sum = 0.0;
		for (int i = 0; i < width; i++) {
			double v = frame[a + i];
			sum += v * v;
		}
		return f(StrictMath.sqrt(sum));
	}

	public static float distance(float[] frame, int a, int b, int width) {
		for (int i = 0; i < width; i++) {
			if (bad(frame[a + i], frame[b + i])) {
				return 0.0f;
			}
		}
		double sum = 0.0;
		for (int i = 0; i < width; i++) {
			double d = (double) frame[a + i] - frame[b + i];
			sum += d * d;
		}
		return f(StrictMath.sqrt(sum));
	}

	/**
	 * The 3-component cross product, in place.
	 *
	 * LIVES HERE RATHER THAN IN THE INTERPRETER, and that move is the fix for a real defect. The
	 * VM composed it from {@code sub(mul(...), mul(...))}, which broke the catch-all in the one way
	 * the catch-all is written to prevent: {@code mul} overflowing float32 produces 0 by the rule,
	 * that 0 was then fed to {@code sub}, and the op returned a plausible finite number for a value
	 * that is not representable — {@code cross((0, 1e20, 1e-20), (0, 1, 1e20)).x} came out as
	 * -1e-20 where the true value is ~1e40. Composing total ops does not give a total op.
	 *
	 * It also narrowed to float32 three times per component instead of once. Accumulating in double
	 * and narrowing at the write is what the other reductions already do, and what contract A4 says
	 * every op does.
	 */
	public static void cross(float[] frame, int dst, int a, int b) {
		boolean nonFinite = false;
		for (int i = 0; i < 3; i++) {
			if (bad(frame[a + i], frame[b + i])) {
				nonFinite = true;
				break;
			}
		}
		// Read every component before writing any: dst is allowed to alias either operand.
		float x = 0.0f, y = 0.0f, z = 0.0f;
		if (!nonFinite) {
			x = f((double) frame[a + 1] * frame[b + 2] - (double) frame[a + 2] * frame[b + 1]);
			y = f((double) frame[a + 2] * frame[b] - (double) frame[a] * frame[b + 2]);
			z = f((double) frame[a] * frame[b + 1] - (double) frame[a + 1] * frame[b]);
		}
		frame[dst] = x;
		frame[dst + 1] = y;
		frame[dst + 2] = z;
	}

	/**
	 * {@code normalize(0) = 0} — the row the safe divide was made consistent with. Written in
	 * place, so a zero vector yields a zero vector rather than a vector of NaN.
	 *
	 * The catch-all is all-or-nothing here for the same reason as the reductions: the length is a
	 * function of every component, so one bad component makes the whole result meaningless. It
	 * cannot early-return, though — the destination must be written either way, or the op would
	 * silently leave whatever the register held before.
	 */
	public static void normalize(float[] frame, int dst, int src, int width) {
		boolean nonFinite = false;
		for (int i = 0; i < width; i++) {
			if (bad(frame[src + i])) {
				nonFinite = true;
				break;
			}
		}
		double sum = 0.0;
		if (!nonFinite) {
			for (int i = 0; i < width; i++) {
				double v = frame[src + i];
				sum += v * v;
			}
		}
		double len = nonFinite ? 0.0 : StrictMath.sqrt(sum);
		for (int i = 0; i < width; i++) {
			frame[dst + i] = len == 0.0 ? 0.0f : f(frame[src + i] / len);
		}
	}

	/**
	 * Bilinear sample with clamp-to-edge, writing 4 components. S1–S4.
	 *
	 * <p><b>S2, the half-texel rule, written out</b> — {@code x = u*width - 0.5}, floor, and the
	 * fraction is the weight. "Bilinear" alone does not say where texel centres sit, and an
	 * implementation that drops the {@code -0.5} is wrong by half a texel everywhere while still
	 * looking like a plausible blur. The convention is not merely GL's: {@code inputTexelSize} is
	 * {@code 1/width}, so a program stepping {@code uv + inputTexelSize*n} lands on adjacent texel
	 * centres under this rule and under no other — and Stage D emits {@code texture2D}, so the CPU
	 * is choosing whether to agree with the hardware rather than choosing in isolation. Either
	 * constraint alone would force it.
	 *
	 * <p><b>S4, the accumulation</b> — every tap is converted {@code b/255.0} in double, the four
	 * products are summed in double, and the result narrows to float32 exactly once. The term
	 * order is part of the pin — but NOT for the reason this comment gave until `67b3bf8`
	 * (2026-08-24), which DESIGN had already withdrawn by name. The sum
	 * accumulates in DOUBLE, so a reordering is visible only where the double-rounding error
	 * straddles a float32 boundary — of order 1e-7 of random uv. MEASURED, because the figure
	 * that stood here on 2026-08-23 was 1.3e-5 and was wrong by ~250x: two independently
	 * written harnesses saw 5 hits in 90 million uv on the golden 4x3 (5.6e-8, and an
	 * order-of-magnitude estimate on 5 events, not a rate to quote to two digits) and 0 in 10
	 * million on the 2x2. That is why all 23 non-identity permutations reproduced every vector
	 * in the first cut of the golden file; two uv were then searched for specifically to hit
	 * it, and between them they separate 18 of the 23, leaving 5 orderings the file still
	 * cannot see. "Float addition is not associative" was the withdrawn reason, and a pin
	 * whose stated reason does not apply is a pin nothing HAD enforced — which is precisely
	 * why those two rows were searched for. Nothing re-runs this measurement; see the
	 * standing note in OcslGolden about the missing term-order harness.
	 *
	 * <p>Clamp-to-edge is applied per axis AFTER the floor, so a tap outside the image takes the
	 * edge texel while the weights are unaffected. Index clamping happens in double before the
	 * narrowing cast, because a uv of 1e30 would otherwise saturate to {@code Integer.MAX_VALUE}
	 * and the {@code +1} tap would wrap to {@code MIN_VALUE}.
	 */
	public static void sample(byte[] rgba, int width, int height, float u, float v,
			float[] frame, int dst) {
		if (width < 1 || height < 1) {
			// Stated and enforced rather than inherited from OcslTexture's constructor. This method
			// is public and takes loose primitives, and clampIndex(0.0, 0) returns -1, so a zero
			// width reached the array as a negative index.
			throw new IllegalArgumentException("texture is " + width + "x" + height);
		}
		if (bad(u, v)) {
			// Already frozen: "sample() on a non-finite uv reads 0.0 per component, identically in
			// the CPU VM" -- a NaN uv would otherwise pick a driver-specific texel on the GPU.
			frame[dst] = 0.0f;
			frame[dst + 1] = 0.0f;
			frame[dst + 2] = 0.0f;
			frame[dst + 3] = 0.0f;
			return;
		}
		double x = (double) u * width - 0.5;
		double y = (double) v * height - 0.5;
		double fx0 = StrictMath.floor(x);
		double fy0 = StrictMath.floor(y);
		double fx = x - fx0;
		double fy = y - fy0;
		int x0 = clampIndex(fx0, width);
		int x1 = clampIndex(fx0 + 1.0, width);
		int y0 = clampIndex(fy0, height);
		int y1 = clampIndex(fy0 + 1.0, height);

		int o00 = (y0 * width + x0) * 4;
		int o10 = (y0 * width + x1) * 4;
		int o01 = (y1 * width + x0) * 4;
		int o11 = (y1 * width + x1) * 4;
		double w00 = (1.0 - fx) * (1.0 - fy);
		double w10 = fx * (1.0 - fy);
		double w01 = (1.0 - fx) * fy;
		double w11 = fx * fy;

		for (int c = 0; c < 4; c++) {
			double t00 = (rgba[o00 + c] & 0xFF) / 255.0;
			double t10 = (rgba[o10 + c] & 0xFF) / 255.0;
			double t01 = (rgba[o01 + c] & 0xFF) / 255.0;
			double t11 = (rgba[o11 + c] & 0xFF) / 255.0;
			frame[dst + c] = f(w00 * t00 + w10 * t10 + w01 * t01 + w11 * t11);
		}
	}

	private static int clampIndex(double coordinate, int size) {
		if (coordinate < 0.0) {
			return 0;
		}
		if (coordinate > size - 1) {
			return size - 1;
		}
		return (int) coordinate;
	}

	/**
	 * Narrow to float32 exactly once, at the point of the register write.
	 *
	 * A4 promoted this from a NOTE. Every method above computes in double and funnels through
	 * here, so the frame's width is a stated property rather than an accident of typing — and two
	 * implementations that both "use floats" cannot disagree about where rounding happened.
	 */
	private static float f(double v) {
		float r = (float) v;
		// The catch-all applies to results as well as operands: an in-domain evaluation can still
		// overflow (exp(100) is legal and finite in double, infinite in float32), and letting that
		// escape would make "each op is total" false at exactly the boundary it was written for.
		return finite(r) ? r : 0.0f;
	}
}
