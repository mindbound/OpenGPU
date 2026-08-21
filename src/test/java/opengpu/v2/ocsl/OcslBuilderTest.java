package opengpu.v2.ocsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * The builder, checked against the thing it exists to reproduce.
 *
 * A2 asks for a Stage B **verification** of the four committed counts "rather than a regeneration",
 * and this is the second of the three independent paths to them: the dry run computed each by hand,
 * {@link AcceptanceProgramsTest} transcribes the hand listings op-for-op, and these tests AUTHOR the
 * same programs through the builder's expression surface. Three routes, one number each. A builder
 * that inserted an op the listing does not have — a splat before a broadcast MIX, a copy to close a
 * fold — would show up here as an arithmetic disagreement and nowhere else.
 *
 * Note what is deliberately NOT asserted: identical register ids or identical bytes against the
 * hand transcriptions. Those number their registers in LISTING order (plasma writes `colorA` first
 * and calls it W+22), while the builder allocates in emission order, which is A5's first-write rule.
 * The counts are the contract; the numbering is not, and pretending otherwise would pin an accident
 * of how the transcription was typed.
 */
public class OcslBuilderTest {

	// ---------------------------------------------------------------- P1 plasma

	private static OcslBuilder plasma() {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		// v1 uniforms are float-typed on the wire, so a vec3 uniform is splatted from a float. The
		// two ops this costs are the transcription's prologue, not the dry run's.
		Expr colorA = b.uniform("colorA").splat(3);
		Expr colorB = b.uniform("colorB").splat(3);

		Expr uv = b.builtin(SurfaceTable.REG_UV);
		Expr time = b.builtin(SurfaceTable.REG_TIME);

		Expr p = uv.mul(b.f(8.0f));                       // 00 -- vec2 * float, ONE op
		Expr wave = p.x().add(time).sin();                // 01 02 03
		Expr wave2 = p.y().add(time.mul(b.f(1.3f))).cos(); // 04 05 06 07
		Expr sum = wave.add(wave2);                       // 08
		Expr diag = p.dot(b.vec2(b.f(1.2f), b.f(0.7f)));  // 09 10
		Expr third = diag.add(time.mul(b.f(0.5f))).sin(); // 11 12 13
		Expr t = sum.add(third).div(b.f(6.0f)).add(b.f(0.5f)); // 14 15 16

		// 18/19: MIX takes the float `t` as its weight directly. Under the pre-re-opening
		// shape-uniform reading this needed a splat first, and that splat is the entire difference
		// between the committed 23 and the 22 this program now charges.
		Expr rgb = colorA.mix(colorB, t);                 // 18
		Expr alpha = b.f(0.4f).mix(b.f(1.0f), t);         // 19
		Expr rgba = b.vec4(rgb, alpha);                   // 20
		b.out(OcslWire.PROP_COLOR,
				rgba.mul(b.builtin(SurfaceTable.REG_TINT))); // 21 22
		return b;
	}

	@Test
	public void theBuilderRefusesTheAbsoluteOutputFormWhereOutputsDoNotCompose() throws Exception {
		// The builder has to carry IrValidator's gate rather than lean on it: build() attributes a
		// validator refusal to "a defect in one of the two rather than in this program", which is a
		// false accusation for an author who simply reached for the wrong output form.
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		Expr colour = b.constant(0.5f, 0.5f, 0.5f, 1.0f);
		try {
			b.outAbsolute(OcslWire.PROP_COLOR, colour);
			fail("a material's output IS its colour; there is no base to replace");
		} catch (OcslBuilder.BuildException e) {
			assertTrue("the refusal should name the form, got: " + e.getMessage(),
					e.getMessage().contains("OUT_ABS"));
		}

		// THE CONTROL, doing double duty. The same property and the same value go through in the
		// relative form, so the refusal was about the form -- and if the refused call had appended
		// its op before throwing, the duplicate-writer scan would refuse this one as "already
		// written". A refusal that mutates the builder is the shape CanvasSubmitTest pins for
		// back-pressure and the same argument applies here.
		b.out(OcslWire.PROP_COLOR, colour);
		IrProgram p = b.build();
		int outs = 0;
		for (int i = 0; i < p.ops().size(); i++) {
			if (OcslWire.isOut(p.ops().get(i).opcode)) {
				outs++;
				assertEquals("the surviving write is the relative one",
						OcslWire.OP_OUT, p.ops().get(i).opcode);
			}
		}
		assertEquals("exactly one write reached the program", 1, outs);
	}

	@Test
	public void theBuilderRefusesARelativeWriteThatReadsItsOwnBase() throws Exception {
		// ANIM-7, mirrored from IrValidator. It has to be mirrored rather than delegated: build()
		// attributes a validator refusal to "a defect in one of the two rather than in this
		// program", which would tell an author with an ordinary mistake that the toolchain is
		// broken and send them to read the compiler instead of their program.
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.out(OcslWire.PROP_ANIM_X, b.builtin(SurfaceTable.REG_ANIM_X).add(b.f(5.0f)));
		try {
			b.build();
			fail("a relative write that reads its own base applies the base twice");
		} catch (OcslBuilder.BuildException e) {
			assertTrue("the builder must own this refusal, not attribute it to the validator: "
					+ e.getMessage(), !e.getMessage().contains("defect in one of the two"));
			assertTrue("and must name the remedy: " + e.getMessage(),
					e.getMessage().contains("outAbsolute"));
		}
	}

	@Test
	public void theBuilderAcceptsTheAbsoluteFormReadingTheSameBase() throws Exception {
		// The other side, and the reason the rule is about the FORM: this is snap-to-grid /
		// ease-to-target, the idiom OUT_ABS exists to make expressible. A builder that refused it
		// would leave absolute positioning with no spelling at all.
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.outAbsolute(OcslWire.PROP_ANIM_X, b.builtin(SurfaceTable.REG_ANIM_X).add(b.f(5.0f)));
		IrProgram p = b.build();
		assertEquals(OcslWire.OP_OUT_ABS, p.ops().get(p.ops().size() - 1).opcode);

		// And reading a property it does not write stays legal in the relative form too.
		OcslBuilder c = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		c.out(OcslWire.PROP_ANIM_ROT2D, c.builtin(SurfaceTable.REG_ANIM_X).mul(c.f(0.01f)));
		c.build();

		// TINT, THE REPLACE EXEMPTION — and it needs its own vector on THIS side, not only in the
		// validator's copy. A review deleted the builder's REPLACE arm and the whole suite stayed
		// green: every tint test lives in the validator, compose and ingress classes, and none of
		// them goes through the builder. Since the builder is the only authoring path, losing that
		// arm would refuse every tint animator ever written with a message telling the author to
		// use outAbsolute() — which does not mean the same thing, because absolute would discard
		// the server colour that this idiom exists to modulate (ANIM-21).
		OcslBuilder t = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		t.out(OcslWire.PROP_ANIM_TINT, t.builtin(SurfaceTable.REG_ANIM_TINT).mul(t.f(0.5f)));
		t.build();
	}

	@Test
	public void plasmaAuthoredThroughTheBuilderChargesItsCommittedCount() throws Exception {
		OcslBuilder b = plasma();
		IrProgram p = b.build();
		assertEquals("22 body ops after the amendment-4 re-opening, plus the 2-op uniform prologue",
				24L, b.structuralCount());
		assertEquals(24L, IrValidator.validate(p).structuralOps);
		assertEquals("plasma performs no fetches", 0, IrValidator.validate(p).fetches);
		assertTrue("its output modulates by tint, so no advisory", b.warnings().isEmpty());
	}

	// ---------------------------------------------------------------- P2 blur

	private static OcslBuilder blur() {
		final OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_POST);
		final Expr texel = b.builtin(SurfaceTable.REG_INPUT_TEXEL_SIZE);
		final Expr uv = b.builtin(SurfaceTable.REG_UV);
		final Expr step = texel.mul(b.uniform("dir").splat(2));  // prologue splat + 00

		Expr sum = b.loop(9, b.constant(0f, 0f, 0f, 0f), new OcslBuilder.Fold() {
			public Expr apply(Expr acc, OcslBuilder.Counter i) {
				Expr offset = i.value().sub(b.f(4.0f));       // 02 03
				Expr weight = offset.mul(offset)              // 04
						.mul(b.f(-0.125f))                    // 05
						.exp()                                // 06
						.mul(b.f(0.2041637f));                // 07
				Expr tap = b.sample(SurfaceTable.SLOT_INPUT,
						uv.add(step.mul(offset)));            // 08 09 10
				return acc.add(tap.mul(weight));              // 11 12
			}
		});
		b.out(OcslWire.PROP_COLOR, sum);
		return b;
	}

	@Test
	public void blurAuthoredThroughTheBuilderChargesItsCommittedCount() throws Exception {
		OcslBuilder b = blur();
		IrProgram p = b.build();
		IrValidator.Validated v = IrValidator.validate(p);
		// 1 + 11x9 + OUT = 101, plus the one splat the float-typed uniform costs.
		assertEquals("blur charges its committed 101 plus the uniform-splat prologue",
				102L, b.structuralCount());
		assertEquals(102L, v.structuralOps);
		assertEquals("9 taps, counted post-unroll", 9, v.fetches);
	}

	// ---------------------------------------------------------------- P3 dissolve

	private static OcslBuilder dissolve() {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_EFFECT);
		Expr uv = b.builtin(SurfaceTable.REG_UV);
		Expr tint = b.builtin(SurfaceTable.REG_TINT);
		Expr threshold = b.uniform("threshold");
		Expr edgeWidth = b.uniform("edgeWidth");

		Expr base = b.sample(SurfaceTable.SLOT_INPUT, uv);
		Expr noise = b.sample(1, uv).x();
		Expr hard = threshold.step(noise);
		Expr upper = threshold.add(edgeWidth);
		Expr ramp = threshold.smoothstep(upper, noise);
		Expr inv = b.f(1.0f).sub(ramp);
		Expr rgb = base.swz("xyz");
		// The float weight `inv` broadcast into a vec3 MIX -- the exact line that re-opened
		// amendment 4, legal as the committed listing writes it.
		Expr edged = rgb.mix(b.constant(1.0f, 0.35f, 0.05f), inv);
		Expr inBand = threshold.le(noise).band(noise.lt(upper));
		Expr picked = b.select(inBand, edged, rgb);
		Expr tinted = picked.mul(tint.swz("xyz"));
		Expr alpha = base.w().mul(hard).mul(tint.w());
		b.out(OcslWire.PROP_COLOR, b.vec4(tinted, alpha));
		return b;
	}

	@Test
	public void dissolveAuthoredThroughTheBuilderChargesItsCommittedCount() throws Exception {
		OcslBuilder b = dissolve();
		IrProgram p = b.build();
		IrValidator.Validated v = IrValidator.validate(p);
		assertEquals("dissolve reproduces its committed 21 exactly", 21L, b.structuralCount());
		assertEquals(21L, v.structuralOps);
		assertEquals("two fetches: the built-in input sampler and one bound slot", 2, v.fetches);
	}

	// ------------------------------------------------- P1/P2/P3 actually EXECUTED

	/**
	 * The acceptance programs RUN, which until now only P4 did.
	 *
	 * Stage B's exit criterion is the four dry-run programs "authored in the builder, validated, and
	 * executed on the VM". P1, P2 and P3 had the first two and not the third — they were built,
	 * counted op-for-op and round-tripped through the codec, and then never evaluated. A program can
	 * satisfy every count in this file and still read an unwritten register, index a frame slot that
	 * does not exist, or produce garbage: the counts constrain the SHAPE and say nothing about what
	 * running it does. P4 was executed because it was written to probe the numeric domain; the other
	 * three were not, because nothing about their op counts suggested they needed to be.
	 *
	 * These assert what a whole-program run can honestly assert without a second backend to compare
	 * against — that every lane is finite, that alpha is what the program constructs, and that the
	 * bound sampler actually reaches the output. Per the GLSL dry run § 5.4, cross-backend numeric
	 * conformance is a **Stage D gate**, not this; Stage B's share of that obligation is the CPU VM
	 * against the golden vectors, which `OcslGoldenTest` discharges.
	 */
	@Test
	public void plasmaRunsAndEveryLaneIsFinite() throws Exception {
		OcslVm vm = new OcslVm(IrValidator.validate(plasma().build()));
		vm.set(SurfaceTable.REG_UV, 0.37f, 0.61f);
		vm.set(SurfaceTable.REG_TIME, 2.5f);
		vm.set(SurfaceTable.REG_TINT, 1f, 1f, 1f, 1f);
		vm.set(SurfaceTable.UNIFORM_BASE, 0.2f);      // colorA
		vm.set(SurfaceTable.UNIFORM_BASE + 1, 0.9f);  // colorB
		vm.run();

		float[] out = new float[4];
		vm.output(OcslWire.PROP_COLOR, out);
		for (int i = 0; i < 4; i++) {
			assertTrue("lane " + i + " is " + out[i], OcslMath.finite(out[i]));
		}
		// The rgb lanes are a MIX of the two uniform splats, so they are bracketed by them whatever
		// the plasma field does -- a real constraint on the arithmetic rather than a finiteness check
		// that a program returning all zeroes would also pass.
		for (int i = 0; i < 3; i++) {
			assertTrue("lane " + i + " = " + out[i] + " outside mix(0.2, 0.9)",
					out[i] >= 0.2f - 1e-6f && out[i] <= 0.9f + 1e-6f);
		}
		// Alpha is mix(0.4, 1.0, t) times tint.w, so it cannot be below 0.4.
		assertTrue("alpha " + out[3], out[3] >= 0.4f - 1e-6f && out[3] <= 1.0f + 1e-6f);
	}

	@Test
	public void blurRunsAndItsNineTapsSumToTheKernelWeight() throws Exception {
		OcslVm vm = new OcslVm(IrValidator.validate(blur().build()));
		// A FLAT texture, so the nine-tap sum is a direct read of the kernel's total weight and does
		// not depend on where the taps land. That makes this a check on the FOLD -- nine iterations,
		// each charging its weight and accumulating -- rather than on the sampler.
		byte[] flat = new byte[8 * 8 * 4];
		java.util.Arrays.fill(flat, (byte) 0xFF);
		vm.bind(SurfaceTable.SLOT_INPUT, new OcslTexture(8, 8, flat));
		vm.set(SurfaceTable.REG_UV, 0.5f, 0.5f);
		vm.set(SurfaceTable.REG_INPUT_TEXEL_SIZE, 1f / 8f, 1f / 8f);
		vm.set(SurfaceTable.UNIFORM_BASE, 1.0f); // dir
		vm.run();

		float[] out = new float[4];
		vm.output(OcslWire.PROP_COLOR, out);
		// exp(-0.125 * (i-4)^2) * 0.2041637 summed over i=0..8, against a texture of 1.0.
		double expected = 0;
		for (int i = 0; i < 9; i++) {
			double offset = i - 4.0;
			expected += Math.exp(-0.125 * offset * offset) * 0.2041637;
		}
		for (int i = 0; i < 4; i++) {
			assertTrue("lane " + i + " is " + out[i], OcslMath.finite(out[i]));
			assertEquals("nine taps of a flat 1.0 texture sum to the kernel weight",
					expected, out[i], 1e-3);
		}
	}

	@Test
	public void dissolveRunsAndItsSelectPicksTheHardEdgeArm() throws Exception {
		OcslVm vm = new OcslVm(IrValidator.validate(dissolve().build()));
		byte[] white = new byte[4 * 4 * 4];
		java.util.Arrays.fill(white, (byte) 0xFF);
		vm.bind(SurfaceTable.SLOT_INPUT, new OcslTexture(4, 4, white));
		// Noise slot: 0 everywhere, so `noise` is 0.0 and the band test is decidable by hand.
		vm.bind(1, new OcslTexture(4, 4, new byte[4 * 4 * 4]));
		vm.set(SurfaceTable.REG_UV, 0.5f, 0.5f);
		vm.set(SurfaceTable.REG_TINT, 1f, 1f, 1f, 1f);
		vm.set(SurfaceTable.UNIFORM_BASE, 0.5f);      // threshold
		vm.set(SurfaceTable.UNIFORM_BASE + 1, 0.25f); // edgeWidth
		vm.run();

		float[] out = new float[4];
		vm.output(OcslWire.PROP_COLOR, out);
		for (int i = 0; i < 4; i++) {
			assertTrue("lane " + i + " is " + out[i], OcslMath.finite(out[i]));
		}
		// noise = 0, threshold = 0.5, so `threshold.le(noise)` is FALSE and the band is not entered:
		// SELECT must take the untouched `rgb` arm, which is the white texture, not the ember colour
		// (1.0, 0.35, 0.05) the edged arm would blend toward. Distinguishing the two arms is the
		// point -- P3 is the dry run's select probe, and a SELECT that always took one arm would
		// reproduce every count in this file.
		assertEquals("green lane proves the un-edged arm was taken", 1.0f, out[1], 1e-6f);
		assertEquals("blue lane likewise", 1.0f, out[2], 1e-6f);
		// step(0.5, 0) = 0, so alpha collapses regardless of the base texture's opaque alpha.
		assertEquals("hard mask is 0 below the threshold", 0.0f, out[3], 1e-6f);
	}

	// ---------------------------------------------------------------- P4 domains

	/**
	 * P4 "numeric-domain torture", pixel/material. Committed: 65 static compute ops
	 * (24 pre + 10 body + 31 post), a 4-iteration fold, post-unroll 24 + 4×10 + 31 = 95,
	 * **96 with OUT**. Fetches 4/16, unroll product 4.
	 *
	 * TRANSCRIBED NOW, AND THE DEFERRAL'S OWN REASON IS WHY. P4 was deliberately left untranscribed
	 * with the argument recorded in as many words: it "exists to probe the GUARD sites — NORM of a
	 * zero vector, ATAN2(0,0), LOG(x≤0), POW(base&lt;0), and a DIV whose divisor reaches 0 at
	 * uv.x=0.5 — [which] belong to the CPU VM and to codegen, neither of which exists yet", so
	 * transcribing it would have exercised nothing the other three cover while risking a slip that
	 * reads as a finding. The VM exists now. That is the whole of what changed, and it turns P4
	 * from redundant into the only program that drives OcslMath's domain table end to end.
	 *
	 * Two adaptations, both the same ones P1 and P2 carry, both counted as a named prologue rather
	 * than folded into the body so the committed number stays legible:
	 * <ul>
	 * <li>{@code u_bias} is a vec2 uniform in the dry run and v1 uniforms are float-typed, so it is
	 *     splatted — ONE prologue op, exactly as P2's {@code dir} is.</li>
	 * <li>The listing's {@code slot(0)} becomes slot 1 here. Slot 0 is reserved as the built-in
	 *     {@code input} sampler and only the effect and post-chain surfaces have it; a material
	 *     program binding its own texture starts at 1. The dry run's listing carries its own
	 *     warning that its ids are provisional, and this is that regeneration.</li>
	 * </ul>
	 */
	private static OcslBuilder domains() {
		final OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		final Expr uv = b.builtin(SurfaceTable.REG_UV);
		final Expr time = b.builtin(SurfaceTable.REG_TIME);
		final Expr tint = b.builtin(SurfaceTable.REG_TINT);
		Expr swirl = b.uniform("swirl");
		Expr bias = b.uniform("bias").splat(2);   // prologue: v1 uniforms are float-typed

		final Expr half = b.f(0.5f);
		final Expr quarter = b.f(0.25f);
		final Expr zero = b.f(0.0f);
		final Expr one = b.f(1.0f);

		Expr p = uv.sub(b.constant(0.5f, 0.5f));           // 000 crosses the zero vector
		Expr dir = p.normalize();                          // 001 EDGE: normalize(0) = 0
		Expr r = p.length();                               // 002
		Expr ang = p.y().atan2(p.x());                     // 003 004 005 EDGE: the pole
		Expr ux = uv.x();                                  // 006
		// `bias.x()` is hoisted rather than written inline in the .add() below, because Java
		// evaluates the receiver before the argument and the listing swizzles the bias BEFORE the
		// subtract. Inline it and the count is identical while the order is not -- which is exactly
		// the kind of slip a total cannot see, and why the opcode sequence is asserted too.
		Expr bx = bias.x();                                // 007
		Expr lg = ux.sub(quarter).add(bx).log();           // 008 009 010 EDGE: arg <= 0
		Expr pw = uv.y().sub(half)                         // 011 012
				.pow(b.f(2.5f).add(swirl));                // 013 014 EDGE: base < 0
		Expr fm = ux.mul(b.f(4.0f)).sub(b.f(2.0f))         // 015 016
				.mod(b.f(1.5f));                           // 017 negative lhs, floor-mod
		Expr sq = quarter.sub(p.dot(p)).sqrt();            // 018 019 020 EDGE: arg < 0
		Expr dv = time.sin().div(ux.sub(half));            // 021 022 023 THE GO-BLOCKER SITE

		final Expr acc = b.loop(4, b.constant(0f, 0f, 0f), new OcslBuilder.Fold() {
			public Expr apply(Expr a, OcslBuilder.Counter i) {
				// The counter is deliberately unread -- that gap is the dry run's amendment 6.
				Expr coord = uv.mul(b.f(1.7f))                      // 025
						.add(a.swz("xy").mul(b.f(0.13f)))           // 026 027 028
						.fract();                                   // 029
				Expr tex = b.sample(1, coord).swz("xyz");           // 030 031
				return a.mul(half).add(tex.mul(half));              // 032 033 034
			}
		});

		Expr c1 = b.vec3(fm, sq, pw.clamp(zero, one));     // 036 037
		Expr c2 = b.vec3(
				dir.x().mul(half).add(half),               // 038 039 040
				dir.y().mul(half).add(half),               // 041 042 043
				ang.mul(b.f(0.15915494f)).add(half));      // 044 045 046
		Expr c3 = b.vec3(
				lg.mul(quarter).fract(),                   // 047 048
				dv.mul(b.f(0.1f)).add(half).clamp(zero, one), // 049 050 051
				r.mul(b.f(2.0f)).fract());                 // 052 053 054
		Expr blend = c1.mix(                               // 058
				c2.mix(c3, time.mul(b.f(0.1f)).fract()),   // 055 056 057
				half);
		Expr rgb = blend.mul(acc.mul(half).add(half));     // 059 060 061
		Expr mask = b.select(ux.lt(half), one, b.f(0.75f)); // 062 063
		b.out(OcslWire.PROP_COLOR,
				b.vec4(rgb.mul(mask), one).mul(tint));     // 064 065 066 067
		return b;
	}

	@Test
	public void domainsAuthoredThroughTheBuilderChargesItsCommittedCount() throws Exception {
		OcslBuilder b = domains();
		IrProgram p = b.build();
		IrValidator.Validated v = IrValidator.validate(p);
		// The last of the four committed counts to get a check of its own. 24 + 4x10 + 31 + OUT,
		// plus the one splat the float-typed uniform costs.
		assertEquals("domains reproduces its committed 96 plus the uniform-splat prologue",
				97L, b.structuralCount());
		assertEquals(97L, v.structuralOps);
		assertEquals("4 taps, counted post-unroll", 4, v.fetches);
	}

	@Test
	public void domainsIsTranscribedOpForOpAndNotMerelyToTheSameTotal() throws Exception {
		// THE CHECK THE COUNT CANNOT MAKE. 96 is one number and the listing is 68 lines, so a slip
		// that swaps two ops of the same kind -- or emits a swizzle a step late -- reproduces the
		// total exactly. One such slip was in the first draft of this transcription: `bias.x()`
		// written inline as an argument emitted its SWZ AFTER the subtract, because Java evaluates
		// the receiver first, and the count was right either way.
		//
		// The dry run's own listing is the expected value, read straight down.
		byte[] expected = {
			OcslWire.OP_SPLAT,                                                 // prologue
			OcslWire.OP_SUB, OcslWire.OP_NORMALIZE, OcslWire.OP_LENGTH,        // 000 001 002
			OcslWire.OP_SWZ, OcslWire.OP_SWZ, OcslWire.OP_ATAN2,               // 003 004 005
			OcslWire.OP_SWZ, OcslWire.OP_SWZ, OcslWire.OP_SUB,                 // 006 007 008
			OcslWire.OP_ADD, OcslWire.OP_LOG,                                  // 009 010
			OcslWire.OP_SWZ, OcslWire.OP_SUB, OcslWire.OP_ADD, OcslWire.OP_POW,// 011 012 013 014
			OcslWire.OP_MUL, OcslWire.OP_SUB, OcslWire.OP_MOD,                 // 015 016 017
			OcslWire.OP_DOT, OcslWire.OP_SUB, OcslWire.OP_SQRT,                // 018 019 020
			OcslWire.OP_SIN, OcslWire.OP_SUB, OcslWire.OP_DIV,                 // 021 022 023
			OcslWire.OP_FOR,                                                   // 024
			OcslWire.OP_MUL, OcslWire.OP_SWZ, OcslWire.OP_MUL, OcslWire.OP_ADD,// 025 026 027 028
			OcslWire.OP_FRACT, OcslWire.OP_SAMPLE, OcslWire.OP_SWZ,            // 029 030 031
			OcslWire.OP_MUL, OcslWire.OP_MUL, OcslWire.OP_ADD,                 // 032 033 034
			OcslWire.OP_ENDFOR,                                                // 035
			OcslWire.OP_CLAMP, OcslWire.OP_CONS3,                              // 036 037
			OcslWire.OP_SWZ, OcslWire.OP_MUL, OcslWire.OP_ADD,                 // 038 039 040
			OcslWire.OP_SWZ, OcslWire.OP_MUL, OcslWire.OP_ADD,                 // 041 042 043
			OcslWire.OP_MUL, OcslWire.OP_ADD, OcslWire.OP_CONS3,               // 044 045 046
			OcslWire.OP_MUL, OcslWire.OP_FRACT,                                // 047 048
			OcslWire.OP_MUL, OcslWire.OP_ADD, OcslWire.OP_CLAMP,               // 049 050 051
			OcslWire.OP_MUL, OcslWire.OP_FRACT, OcslWire.OP_CONS3,             // 052 053 054
			OcslWire.OP_MUL, OcslWire.OP_FRACT,                                // 055 056
			OcslWire.OP_MIX, OcslWire.OP_MIX,                                  // 057 058
			OcslWire.OP_MUL, OcslWire.OP_ADD, OcslWire.OP_MUL,                 // 059 060 061
			OcslWire.OP_LT, OcslWire.OP_SELECT, OcslWire.OP_MUL,               // 062 063 064
			OcslWire.OP_CONS4_V3F, OcslWire.OP_MUL, OcslWire.OP_OUT,           // 065 066 067
		};
		IrProgram p = domains().build();
		assertEquals("op count: 65 compute + FOR + ENDFOR + OUT, plus the prologue splat",
				expected.length, p.ops().size());
		for (int i = 0; i < expected.length; i++) {
			assertEquals("op " + i + " (" + OcslWire.shapeOf(expected[i]).name + " expected, got "
					+ OcslWire.shapeOf(p.ops().get(i).opcode).name + ")",
					expected[i], p.ops().get(i).opcode);
		}
		assertEquals("65 static compute ops as the dry run counted them",
				65, p.ops().size() - 1 - 3);
	}

	@Test
	public void domainsDrivesEveryGuardSiteAtOnceAndStaysFinite() throws Exception {
		// THE PROGRAM'S ACTUAL PURPOSE, runnable for the first time. At the exact centre of the
		// quad four guard sites fire together: p is the zero vector, so normalize(0) and
		// atan2(0,0) both hit their poles; uv.y - 0.5 is 0, so pow's base is not positive; and
		// uv.x - 0.5 is 0, which is the divide the dry run flagged as its GO-BLOCKER.
		//
		// Every one of those is a row of the frozen domain table, and the property being checked is
		// the one A4 exists for: the output is finite and defined, not a NaN that would propagate
		// differently on every backend.
		OcslVm vm = new OcslVm(IrValidator.validate(domains().build()));
		vm.set(SurfaceTable.REG_UV, 0.5f, 0.5f);
		vm.set(SurfaceTable.REG_TIME, 1.25f);
		vm.set(SurfaceTable.REG_TINT, 1f, 1f, 1f, 1f);
		vm.set(SurfaceTable.UNIFORM_BASE, 0.0f);       // swirl
		vm.set(SurfaceTable.UNIFORM_BASE + 1, -0.25f); // bias: drives log's argument to exactly 0
		vm.run();

		float[] out = new float[4];
		vm.output(OcslWire.PROP_COLOR, out);
		for (int i = 0; i < 4; i++) {
			assertTrue("component " + i + " is " + out[i], OcslMath.finite(out[i]));
		}
		assertEquals("alpha is the constructed 1.0 times tint", 1.0f, out[3], 0f);

		// And the same program away from every pole still runs, so the guards are not a stub that
		// happens to return zero everywhere.
		OcslVm off = new OcslVm(IrValidator.validate(domains().build()));
		off.set(SurfaceTable.REG_UV, 0.8f, 0.7f);
		off.set(SurfaceTable.REG_TIME, 0.3f);
		off.set(SurfaceTable.REG_TINT, 1f, 1f, 1f, 1f);
		off.set(SurfaceTable.UNIFORM_BASE, 0.5f);
		off.set(SurfaceTable.UNIFORM_BASE + 1, 0.5f);
		off.run();
		float[] awayFromPoles = new float[4];
		off.output(OcslWire.PROP_COLOR, awayFromPoles);
		for (int i = 0; i < 4; i++) {
			assertTrue("component " + i, OcslMath.finite(awayFromPoles[i]));
		}
	}

	// ---------------------------------------------------------------- canonical form

	@Test
	public void aFloatOperandBroadcastsAndTheBuilderNeverSplatsAroundIt() throws Exception {
		// The obligation the amendment-4 re-opening took on, as a test rather than a sentence:
		// both spellings compute the same value and charge differently, so if the builder could
		// choose, one program would have two content hashes -- and the hash is the compile-cache
		// key. It cannot choose: there is no path through the arithmetic that emits a splat.
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		Expr rgb = b.builtin(SurfaceTable.REG_TINT).swz("xyz");
		b.out(OcslWire.PROP_COLOR, b.vec4(rgb.mul(b.f(0.5f)), b.f(1.0f)));
		IrProgram p = b.build();

		for (int i = 0; i < p.ops().size(); i++) {
			assertFalse("the builder emitted a SPLAT where a float operand would broadcast",
					p.ops().get(i).opcode == OcslWire.OP_SPLAT);
		}
		assertEquals("swizzle, multiply, construct, out", 4L, b.structuralCount());
	}

	@Test
	public void aRepeatedLiteralIsOnePoolEntry() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		Expr uv = b.builtin(SurfaceTable.REG_UV);
		Expr a = uv.mul(b.f(2.0f)).x();
		Expr c = uv.mul(b.f(2.0f)).y();
		b.out(OcslWire.PROP_COLOR, b.vec4(a, c, b.f(2.0f), b.f(1.0f)));
		IrProgram p = b.build();
		assertEquals("2.0 written three times is one entry; 1.0 is the other", 2,
				p.constantCount());

		// Dedup is by BIT PATTERN, because 0.0 and -0.0 are different constants on the wire and
		// folding them together would change what a program computes at the atan2 and divide rows.
		OcslBuilder z = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		Expr t = z.builtin(SurfaceTable.REG_TINT);
		z.out(OcslWire.PROP_COLOR, t.add(z.f(0.0f).splat(4)).sub(z.f(-0.0f).splat(4)));
		assertEquals("0.0 and -0.0 stay distinct", 2, z.build().constantCount());
	}

	@Test
	public void aLiteralNeverUsedDoesNotReachThePool() throws Exception {
		// Canonical form from the other side. A mentioned-but-unused literal emits no op, so it is
		// invisible in the instruction stream -- and would still have sat in the pool changing the
		// blob's bytes, so two sources compiling to the SAME instructions would have had different
		// content hashes and missed each other in the compile cache.
		OcslBuilder used = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		used.out(OcslWire.PROP_COLOR, used.builtin(SurfaceTable.REG_TINT).mul(used.f(0.5f)));

		OcslBuilder withJunk = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		withJunk.f(99.0f);                       // abandoned
		withJunk.constant(1f, 2f, 3f, 4f);       // abandoned
		withJunk.out(OcslWire.PROP_COLOR,
				withJunk.builtin(SurfaceTable.REG_TINT).mul(withJunk.f(0.5f)));

		assertTrue("identical instructions must produce identical bytes",
				java.util.Arrays.equals(IrCodec.encode(used.build()),
						IrCodec.encode(withJunk.build())));
	}

	@Test
	public void thePoolIsOrderedByFirstUseNotFirstMention() throws Exception {
		// Where a literal was NAMED must not reach the wire; only where it is USED. Both programs
		// multiply by 2 and then by 3, and differ only in the order the two literals were spoken.
		OcslBuilder a = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		Expr twoA = a.f(2.0f);
		Expr threeA = a.f(3.0f);
		a.out(OcslWire.PROP_COLOR, a.builtin(SurfaceTable.REG_TINT).mul(twoA).mul(threeA));

		OcslBuilder c = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		Expr threeC = c.f(3.0f);                 // mentioned first, used second
		Expr twoC = c.f(2.0f);
		c.out(OcslWire.PROP_COLOR, c.builtin(SurfaceTable.REG_TINT).mul(twoC).mul(threeC));

		assertTrue("mention order must not fork the content hash",
				java.util.Arrays.equals(IrCodec.encode(a.build()), IrCodec.encode(c.build())));
	}

	// ---------------------------------------------------------------- the fold

	@Test
	public void aFoldsFrameWidthDoesNotDependOnItsTripCount() throws Exception {
		// A3's DISCRIMINATING test, and the design says why it has to be a width rather than an I/O
		// vector: interpreted and unrolled execution produce identical outputs by construction, so
		// no input/output pair can tell them apart. The frame width can. An unrolling builder would
		// allocate body registers per iteration and this number would move with the trip count.
		int narrow = foldFrameWidth(3);
		int wide = foldFrameWidth(12);
		assertEquals("the frame is a function of the blob, not of how many times it runs",
				narrow, wide);
	}

	private static int foldFrameWidth(int trips) throws Exception {
		final OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		Expr sum = b.loop(trips, b.constant(0f, 0f, 0f, 0f), new OcslBuilder.Fold() {
			public Expr apply(Expr acc, OcslBuilder.Counter i) {
				return acc.add(i.value().sin().splat(4));
			}
		});
		b.out(OcslWire.PROP_COLOR, sum.mul(b.builtin(SurfaceTable.REG_TINT)));
		return IrValidator.validate(b.build()).frameWidth;
	}

	@Test
	public void aFoldClosingOnSomethingItDidNotJustComputeChargesTheCopy() throws Exception {
		// The write-back is a retarget when the body's last op produced the value -- the natural
		// `return acc.add(x)` shape, costing nothing extra. When it did not, a real copy is needed,
		// and it is CHARGED rather than slipped in: a hidden op would put the builder's count and
		// the validator's on different footings, which build() would then reject as a defect in
		// one of them.
		// The retargeted shape first, for contrast: the body's last op IS the returned value.
		final OcslBuilder plain = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		Expr retargeted = plain.loop(4, plain.constant(0f, 0f, 0f, 0f), new OcslBuilder.Fold() {
			public Expr apply(Expr acc, OcslBuilder.Counter i) {
				return acc.add(i.value().splat(4));
			}
		});
		plain.out(OcslWire.PROP_COLOR, retargeted.mul(plain.builtin(SurfaceTable.REG_TINT)));
		plain.build();
		assertEquals("ITOF, SPLAT, ADD per iteration, retargeted at no cost, then MUL and OUT",
				3 * 4 + 2L, plain.structuralCount());

		// Now one that genuinely needs the copy: the returned value was computed BEFORE the body's
		// last op, so the register it lives in is not the one the retarget could claim.
		final OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		Expr sum = b.loop(4, b.constant(0f, 0f, 0f, 0f), new OcslBuilder.Fold() {
			public Expr apply(Expr acc, OcslBuilder.Counter i) {
				Expr next = acc.add(i.value().splat(4));
				next.mul(b.f(2.0f)); // emitted after it, and discarded
				return next;
			}
		});
		b.out(OcslWire.PROP_COLOR, sum.mul(b.builtin(SurfaceTable.REG_TINT)));
		IrProgram p = b.build();
		// ITOF, SPLAT, ADD, the discarded MUL, and the SWZ copy = 5 per iteration. The copy is
		// visible in the count rather than hidden, which is what lets build()'s builder-vs-validator
		// comparison mean anything.
		assertEquals(5 * 4 + 2L, b.structuralCount());
		assertEquals(b.structuralCount(), IrValidator.validate(p).structuralOps);
	}

	@Test
	public void anEmptyFoldBodyIsRefusedAtTheCallSite() {
		final OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		try {
			b.loop(256, b.constant(0f, 0f, 0f, 0f), new OcslBuilder.Fold() {
				public Expr apply(Expr acc, OcslBuilder.Counter i) {
					return acc;
				}
			});
			fail("an empty fold body must be refused");
		} catch (OcslBuilder.BuildException e) {
			// A7, caught where the author can see the empty lambda rather than as an op index in a
			// validator message about a blob.
			assertTrue(e.getMessage(), e.getMessage().contains("emits no op")
					|| e.getMessage().contains("unchanged"));
		}
	}

	// ---------------------------------------------------------------- errors at the call site

	@Test
	public void aTypeErrorNamesTheOperationRatherThanAnOpIndex() {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		try {
			b.builtin(SurfaceTable.REG_UV).add(b.builtin(SurfaceTable.REG_TINT));
			fail("vec2 + vec4 has no defensible answer");
		} catch (OcslBuilder.BuildException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("two different vector widths"));
		}
		try {
			b.builtin(SurfaceTable.REG_UV).dot(b.f(1.0f));
			fail("dot reduces over components, so a float must not broadcast into it");
		} catch (OcslBuilder.BuildException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("does not broadcast"));
		}
	}

	@Test
	public void theBudgetIsRefusedWhereItIsCrossedNotAtBuild() {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		Expr x = b.builtin(SurfaceTable.REG_TIME);
		try {
			for (int i = 0; i < IrValidator.MAX_STRUCTURAL_OPS + 10; i++) {
				x = x.sin();
			}
			fail("the op cap must stop this");
		} catch (OcslBuilder.BuildException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("structural ops"));
			assertTrue("and it must say the failure is here, not in some finished blob",
					e.getMessage().contains("being written here"));
			// The BUILDER's message names the stage too — the panel found the commit draft
			// claiming "two assertions pin" the stage-naming while both went through the
			// VALIDATOR; nothing asserted the builder's own message until this line.
			assertTrue("the builder's refusal must name the stage whose budget was crossed",
					e.getMessage().contains(
							"over stage " + OcslWire.STAGE_PIXEL_MATERIAL + "'s cap"));
		}
	}

	@Test
	public void aProgramIgnoringTintIsWarnedAboutRatherThanRefused() throws Exception {
		// Legal, and it silently disables node and group tinting for that material -- which is a
		// shipped feature appearing to break. Refusing would be wrong (the design says ignoring
		// tint is a choice); saying nothing would be worse.
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		b.out(OcslWire.PROP_COLOR, b.constant(1f, 0f, 0f, 1f).mul(b.f(0.5f)));
		b.build();
		assertEquals(1, b.warnings().size());
		assertTrue(b.warnings().get(0), b.warnings().get(0).contains("tint"));
	}

	@Test
	public void aShutStageIsRefusedAtTheFirstCallAndAnOpenOneIsNot() {
		// THE ANIMATOR SIDE INVERTED 2026-08-13: it opened, so `forStage` must hand back a builder.
		// This test's subject was never the animator specifically — it is that the gate fires at the
		// FIRST call rather than at build(), so an author does not write a whole program before
		// learning the surface will not take it. Compute carries that now.
		OcslBuilder animator = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		assertTrue("an open surface hands back a usable builder", animator != null);

		try {
			OcslBuilder.forStage(OcslWire.STAGE_COMPUTE);
			fail("compute is deferred behind a tripwire");
		} catch (OcslBuilder.BuildException e) {
			// Was `contains("reserved")`, which came from a message reading "has no property
			// table... it is reserved". That message became FALSE the day the animator's property
			// table was published, and the gate now asks SurfaceTable.isOpen instead of inferring
			// reservation from an empty required-properties set.
			assertTrue(e.getMessage(), e.getMessage().contains("not open"));
		}
	}

	// ---------------------------------------------------------------- found by the review
	//
	// Six defects, all confirmed by execution against the first draft. The two that matter most
	// were SILENT: a fold whose result register got recycled under a live handle, and a stashed
	// counter that read a different loop. Both produced programs that built, validated, encoded and
	// ran, and returned the wrong number with no diagnostic at any of the three gates.

	@Test
	public void aFoldsResultRegisterIsNeverRecycledUnderALiveHandle() throws Exception {
		// THE SILENT MISCOMPILE. The retarget used to pop the register it freed, so the next op
		// reused the number and an Expr the author still held aliased it. Measured on the first
		// draft: `FOR r96 / ADD r96 / ENDFOR / SIN r97 / MUL r98, r97 / OUT r98` -- the whole fold
		// dead and the output carrying sin(time), validating and running with nothing said. The
		// register stays allocated now, so the same program is a read of something never written
		// and the validator names it.
		final OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		final Expr[] leaked = new Expr[1];
		b.loop(2, b.constant(0f, 0f, 0f, 0f), new OcslBuilder.Fold() {
			public Expr apply(Expr acc, OcslBuilder.Counter i) {
				Expr next = acc.add(b.constant(1f, 1f, 1f, 1f));
				leaked[0] = next;   // kept past the fold, where it no longer names anything written
				return next;
			}
		});
		b.out(OcslWire.PROP_COLOR, leaked[0].mul(b.builtin(SurfaceTable.REG_TINT)));
		try {
			b.build();
			fail("a handle on the retargeted register must not silently alias a later op");
		} catch (OcslBuilder.BuildException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("before anything writes it"));
		}
	}

	@Test
	public void aCounterIsReadableOnlyInsideItsOwnFold() throws Exception {
		// Depth is not identity. A counter captured from a closed fold used to resolve against
		// whatever loop now sat at that depth: two sibling folds, the second reading the first's
		// counter, ran and returned 0+1+2+3+4 from the SECOND loop's own five trips.
		final OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		final OcslBuilder.Counter[] stashed = new OcslBuilder.Counter[1];
		b.loop(2, b.f(0f), new OcslBuilder.Fold() {
			public Expr apply(Expr acc, OcslBuilder.Counter i) {
				stashed[0] = i;
				return acc.add(i.value());
			}
		});
		try {
			b.loop(5, b.f(0f), new OcslBuilder.Fold() {
				public Expr apply(Expr acc, OcslBuilder.Counter i) {
					return acc.add(stashed[0].value());   // a counter from the CLOSED fold
				}
			});
			fail("a closed fold's counter must not resolve against a later loop");
		} catch (OcslBuilder.BuildException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("already closed"));
		}
	}

	@Test
	public void aBoolAccumulatorFoldsOnBothWriteBackPaths() throws Exception {
		// The copy was unconditionally a SWZ, which the validator refuses over a bool -- and could
		// not have worked anyway, since SWZ's type rule yields a float. So a bool fold built or
		// failed depending on whether its body happened to end on the returned op, and a NESTED
		// bool fold ALWAYS failed, because an inner loop ends its body on ENDFOR.
		final OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		final Expr time = b.builtin(SurfaceTable.REG_TIME);
		Expr any = b.loop(3, time.lt(b.f(0.5f)), new OcslBuilder.Fold() {
			public Expr apply(Expr acc, final OcslBuilder.Counter outer) {
				// A nested fold as the body's last statement: the copy path, unavoidably.
				return b.loop(2, acc, new OcslBuilder.Fold() {
					public Expr apply(Expr inner, OcslBuilder.Counter i) {
						return inner.bor(i.value().lt(outer.value()));
					}
				});
			}
		});
		b.out(OcslWire.PROP_COLOR,
				b.select(any, b.builtin(SurfaceTable.REG_TINT), b.constant(0f, 0f, 0f, 1f)));
		IrProgram p = b.build();
		assertEquals(b.structuralCount(), IrValidator.validate(p).structuralOps);
	}

	@Test
	public void theFetchBudgetIsRefusedAtTheCallSite() {
		// A 17-tap blur is the natural next edit of the committed 9-tap one. It used to be accepted
		// tap by tap and refused inside build() by a message blaming the implementation.
		final OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_POST);
		final Expr uv = b.builtin(SurfaceTable.REG_UV);
		try {
			b.loop(17, b.constant(0f, 0f, 0f, 0f), new OcslBuilder.Fold() {
				public Expr apply(Expr acc, OcslBuilder.Counter i) {
					return acc.add(b.sample(SurfaceTable.SLOT_INPUT, uv));
				}
			});
			fail("17 taps is over the fetch cap");
		} catch (OcslBuilder.BuildException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("fetches post-unroll"));
		}
	}

	@Test
	public void aUniformNameTheWireCannotCarryIsRefusedWhereItIsWritten() {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		for (String bad : new String[] { "my name", "", "2fast", "colour-a" }) {
			try {
				b.uniform(bad);
				fail("\"" + bad + "\" is not a name the format carries");
			} catch (OcslBuilder.BuildException e) {
				assertTrue(e.getMessage(), e.getMessage().contains(bad)
						|| e.getMessage().contains("empty"));
			}
		}
	}

	@Test
	public void anExpressionFromAnotherBuilderIsRefusedEverywhere() {
		// select() checked only its condition and out() checked nothing, so a foreign Expr's raw
		// operand index was emitted verbatim -- and when it happened to name a written register
		// here, the program validated, ran, and computed something else.
		OcslBuilder a = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		OcslBuilder other = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		Expr foreign = other.builtin(SurfaceTable.REG_TINT).mul(other.f(9f));
		Expr cond = a.builtin(SurfaceTable.REG_TIME).lt(a.f(1f));
		try {
			a.select(cond, foreign, a.builtin(SurfaceTable.REG_TINT));
			fail("select must refuse an arm from another builder");
		} catch (OcslBuilder.BuildException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("different builder"));
		}
		try {
			a.out(OcslWire.PROP_COLOR, foreign);
			fail("out must refuse a value from another builder");
		} catch (OcslBuilder.BuildException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("different builder"));
		}
	}

	@Test
	public void theTintAdvisorySeesDependenciesThroughFoldsAndConditions() throws Exception {
		// A warning that fires on correct programs is the mechanism by which the real warning stops
		// being read. Three shapes were false positives: dependency through a fold accumulator,
		// through a comparison feeding select, and through a sampled coordinate.
		final OcslBuilder folded = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		final Expr tint = folded.builtin(SurfaceTable.REG_TINT);
		Expr sum = folded.loop(4, folded.constant(0f, 0f, 0f, 0f), new OcslBuilder.Fold() {
			public Expr apply(Expr acc, OcslBuilder.Counter i) {
				return acc.add(tint.mul(folded.f(0.25f)));
			}
		});
		folded.out(OcslWire.PROP_COLOR, sum);
		folded.build();
		assertTrue("its colour is literally a sum of tint", folded.warnings().isEmpty());

		OcslBuilder picked = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		Expr bright = picked.builtin(SurfaceTable.REG_TINT).x().lt(picked.f(0.5f));
		picked.out(OcslWire.PROP_COLOR, picked.select(bright,
				picked.constant(0f, 0f, 0f, 1f), picked.constant(1f, 1f, 1f, 1f)));
		picked.build();
		assertTrue("which arm is taken is part of what the value is",
				picked.warnings().isEmpty());

		// And it still fires where it should.
		OcslBuilder ignores = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		ignores.out(OcslWire.PROP_COLOR, ignores.constant(1f, 0f, 0f, 1f).mul(ignores.f(0.5f)));
		ignores.build();
		assertEquals(1, ignores.warnings().size());
	}

	@Test
	public void theBuilderAndTheValidatorAgreeOnEveryProgramHere() throws Exception {
		// A2's obligation is that these two numbers agree, and the failure it predicts is builder
		// acceptance and validator rejection diverging AT the cap. build() compares them on every
		// program ever built, so the divergence cannot reach a release -- it would have to survive
		// a program nobody builds. This test states the property; build() enforces it.
		OcslBuilder b = plasma();
		IrProgram p = b.build();
		assertEquals(b.structuralCount(), IrValidator.validate(p).structuralOps);
		assertEquals(b.structuralCount(), p.structuralCount());
	}

	@Test
	public void aBuiltProgramRunsOnTheVm() throws Exception {
		// The last link: authored here, validated by the real validator, executed by the real VM.
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		Expr half = b.f(0.5f);
		b.out(OcslWire.PROP_COLOR,
				b.builtin(SurfaceTable.REG_TINT).mul(half));
		OcslVm vm = new OcslVm(IrValidator.validate(b.build()));
		vm.set(SurfaceTable.REG_TINT, 1f, 1f, 1f, 1f);
		vm.run();
		float[] out = new float[4];
		vm.output(OcslWire.PROP_COLOR, out);
		for (int i = 0; i < 4; i++) {
			assertEquals("lane " + i, 0.5f, out[i], 0f);
		}
	}

	/**
	 * THE SIZING ARITHMETIC BEHIND 3.1's STORAGE DECISION, measured at the ACCEPTANCE CEILING.
	 *
	 * Programs are stored INLINE in the scene snapshot under a per-scene byte ledger. Two caps
	 * are easy to confuse and the first draft of this test confused them:
	 *
	 *   OcslWire.MAX_OPS = 4096          the CODEC's structural wire bound
	 *   IrValidator.MAX_STRUCTURAL_OPS = 256   what a program may actually CHARGE
	 *
	 * The validator's is the one that binds acceptance (its caps are policy, raiseable under
	 * monotonicity; the codec's are format identity — IrCodec's class contrast), so sizing the
	 * ledger off 4096 overstated this shape ~16x. NOTE what this test does NOT establish: the
	 * op cap does not bound BYTES — the constant pool is uncharged, so the byte-maximal program
	 * is pool-heavy, not op-heavy; ProgramLedgerBoundTest measures that axis. An earlier draft
	 * here also misquoted IrValidator ("refuses anything the 4096 wire bound would") as being
	 * about this pair — that sentence is OP_FOR's, about MAX_LOOP_TRIPS vs MAX_UNROLL_PRODUCT,
	 * which merely share the numbers.
	 *
	 * This builds the largest program of THE CHEAPEST BYTES-PER-OP SHAPE and measures its blob.
	 * If MAX_STRUCTURAL_OPS or MAX_FRAME_WIDTH is ever raised (ANIM-16), this number moves and
	 * the ledger's arithmetic must be re-derived, not assumed.
	 */
	@Test
	public void aProgramAtTheAcceptanceCeilingEncodesWithinTheLedgersArithmetic() throws Exception {
		IrProgram program = largestVec4ChainProgram();
		byte[] encoded = IrCodec.encode(program);
		long charge = IrValidator.validate(program).structuralOps;
		System.out.println("[program size] at acceptance ceiling = " + encoded.length + " bytes ("
				+ program.ops().size() + " static ops, " + charge + " structural)");

		// THE ASSERTION THIS TEST EXISTED WITHOUT. The charge was only PRINTED, so nothing pinned
		// what this program actually costs, and three documents went on to call 1810 bytes "the
		// acceptance ceiling".
		//
		// RE-DERIVED for the 2026-08-21 raise, and the binding cap FLIPPED, which is exactly why
		// this test's javadoc ordered re-derivation rather than assumption. Before: the FRAME
		// (1024 floats) bound this shape at 255 charged ops, one short of the old 256 op cap
		// (254 adds x 4 + the 8-float pixel-post prologue = exactly 1024, and the check is
		// strictly greater-than; a first re-derivation wrote 253 here, which was the old
		// fixture's MAX-4 margin masquerading as the bind -- the panel recomputed it).
		// After: the frame is 2048 (admitting ~500 vec4 registers) while the pixel stage's op
		// cap stays 256 — so the OP cap binds now, and the shape's true maximum is exactly the
		// stage cap. The frame-bound story did not vanish; it moved to the ANIMATOR (512 op
		// cap), where theAnimatorsVec4ChainIsFrameBoundBelowItsOpCap pins it.
		assertEquals("the pixel vec4 chain is now OP-bound at the stage cap under the 2048 frame"
				+ " — if this moved, a cap or the per-op register width changed and every size"
				+ " derived from this program needs recomputing",
				(long) IrValidator.maxStructuralOps(OcslWire.STAGE_PIXEL_POST), charge);

		// Two-sided, because a one-sided ceiling admits an encoder that dropped the op records.
		// The floor is derived from the CHARGE, not the ceiling constant: 1024 op records were
		// never in this program.
		assertTrue("a cap-sized program encodes to only " + encoded.length + " bytes, which is"
				+ " below what " + charge + " op records can occupy — the encoder is dropping"
				+ " something", encoded.length > 5 * charge);
		assertTrue("a cap-sized program encodes to " + encoded.length
				+ " bytes; the ledger's arithmetic assumed a few KiB", encoded.length < 8192);
	}

	/**
	 * THE FRAME-BOUND STORY, relocated to the animator by the 2026-08-21 raise.
	 *
	 * The animator's op cap is 512, but each vec4 ADD lays out a vec4 working register, and the
	 * 2048-float frame admits only ~500 of them plus the stage prologue — so the FRAME binds
	 * this shape below the op cap, deliberately adjacent to it. Pinned with a bracketing pair
	 * rather than one exact count because the exact bind depends on the stage prologue's width:
	 * 470 adds must BUILD (safely inside both caps), 510 must be REFUSED by the frame (2040
	 * floats of working registers + any nonzero prologue exceeds 2048, while its charge of 511
	 * sits under the 512 op cap — so a refusal here that named the OP cap would mean the frame
	 * check is dead).
	 */
	@Test
	public void theAnimatorsVec4ChainIsFrameBoundBelowItsOpCap() throws Exception {
		OcslBuilder ok = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		Expr accOk = ok.constant(0f, 0f, 0f, 1f);
		for (int i = 0; i < 470; i++) {
			accOk = accOk.add(ok.f(1.0f));
		}
		ok.out(OcslWire.PROP_ANIM_TINT, accOk);
		long charge = IrValidator.validate(ok.build()).structuralOps;
		assertEquals("470 adds + OUT — past the old 255 frame bind, proof the raise delivered",
				471L, charge);

		try {
			OcslBuilder over = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
			Expr acc = over.constant(0f, 0f, 0f, 1f);
			for (int i = 0; i < 510; i++) {
				acc = acc.add(over.f(1.0f));
			}
			over.out(OcslWire.PROP_ANIM_TINT, acc);
			IrValidator.validate(over.build());
			fail("510 vec4 registers cannot fit a 2048-float frame");
		} catch (OcslBuilder.BuildException e) {
			assertTrue("the refusal must be the FRAME's — an op-cap message here means the frame"
					+ " check is dead (charge 511 is under the 512 cap): " + e.getMessage(),
					e.getMessage().contains("frame"));
		} catch (opengpu.v2.ocsl.ValidationException e) {
			assertTrue("the refusal must be the FRAME's: " + e.getMessage(),
					e.getMessage().contains("frame"));
		}
	}

	/**
	 * THE FRAME CAP'S EQUALITY BOUNDARY — a program laying out EXACTLY MAX_FRAME_WIDTH floats
	 * must VALIDATE, and one float more must not.
	 *
	 * The panel found the {@code cursor > MAX_FRAME_WIDTH} check's boundary untested: mutating
	 * {@code >} to {@code >=} survived the whole suite, because every frame fixture sat clear of
	 * the line (the animator bracket passes at 1914 and rejects at 2074). This is also the exact
	 * boundary the pre-raise "253" misreading got wrong — 254 adds laid out exactly 1024 and
	 * PASSED, which is what made the true bind 255, not 253.
	 *
	 * Construction: the animator prologue is 34 floats (time 1 + timePeriod 1 + own block 15 +
	 * nodeSeed/sinceAttach 2 + parent block 15), so 503 vec4 working registers (2012) + 2 scalar
	 * working registers + 34 = exactly 2048. The pooled vec4 constant takes NO frame slot — a
	 * first version of this fixture counted it as one and sat at 2044/2045, so its reject arm
	 * never reached the line it claimed to test; the suite failure is what corrected the count.
	 * If the prologue ever changes width, this test fails loudly and gets re-derived —
	 * deliberately, since the prologue's width is part of what the frame cap means.
	 */
	@Test
	public void aFrameOfExactlyTheCapValidatesAndOneFloatMoreDoesNot() throws Exception {
		OcslBuilder exact = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		Expr acc = exact.constant(0f, 0f, 0f, 1f);
		for (int i = 0; i < 503; i++) {          // 503 adds = 503 vec4 working registers
			acc = acc.add(exact.f(1.0f));
		}
		Expr s1 = exact.f(1.0f).add(exact.f(2.0f));   // scalar working reg 1
		Expr s2 = s1.add(exact.f(3.0f));              // scalar working reg 2
		exact.out(OcslWire.PROP_ANIM_TINT, acc);
		exact.out(OcslWire.PROP_ANIM_X, s2);
		long charge = IrValidator.validate(exact.build()).structuralOps;
		assertEquals("503 vec4 adds + 2 scalar adds + 2 OUTs, at a frame of exactly 2048 — a"
				+ " frame check of >= instead of > refuses this legal program",
				507L, charge);

		OcslBuilder over = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		Expr acc2 = over.constant(0f, 0f, 0f, 1f);
		for (int i = 0; i < 503; i++) {
			acc2 = acc2.add(over.f(1.0f));
		}
		Expr t1 = over.f(1.0f).add(over.f(2.0f));
		Expr t2 = t1.add(over.f(3.0f));
		Expr t3 = t2.add(over.f(4.0f));               // one scalar register too many: 2049
		try {
			over.out(OcslWire.PROP_ANIM_TINT, acc2);
			over.out(OcslWire.PROP_ANIM_X, t3);
			IrValidator.validate(over.build());
			fail("a 2049-float frame must be refused");
		} catch (OcslBuilder.BuildException e) {
			assertTrue("the refusal is the frame's: " + e.getMessage(),
					e.getMessage().contains("frame"));
		} catch (opengpu.v2.ocsl.ValidationException e) {
			assertTrue("the refusal is the frame's: " + e.getMessage(),
					e.getMessage().contains("frame"));
		}
	}

	/**
	 * The largest vec4 ADD chain the PIXEL-POST stage accepts: cap-1 adds + one OUT = exactly
	 * the stage's op cap in charge.
	 *
	 * OP-bound since the 2026-08-21 raise: the 2048-float frame admits ~500 vec4 registers, so
	 * the pixel stage's 256 op cap is what stops this shape now (it was the 1024 frame at 255,
	 * pre-raise). Named for the shape rather than for "the ceiling", because it is not the
	 * largest program the validator accepts in BYTES — see ProgramLedgerBoundTest, where a
	 * two-op program with a full constant pool is an order of magnitude larger.
	 */
	static IrProgram largestVec4ChainProgram() {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_POST);
		Expr acc = b.constant(0f, 0f, 0f, 1f);
		for (int i = 0; i < IrValidator.maxStructuralOps(OcslWire.STAGE_PIXEL_POST) - 1; i++) {
			acc = acc.add(b.f(1.0f));
		}
		b.out(OcslWire.PROP_COLOR, acc);
		return b.build();
	}
}
