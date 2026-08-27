package opengpu.v2.ocsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * NOT A CONTRACT TEST — an INTUITION PUMP for the MAX_STRUCTURAL_OPS question, kept because the
 * answer to "is 256 enough?" should be two programs a reader can look at, not an opinion.
 *
 * Both are things a player might plausibly write. One is a rich animated material that fits with
 * room to spare; the other is the single most common "creative shader" shape there is, and it
 * does not fit at any useful quality. The numbers are MEASURED (the builder charges as it
 * emits), because every estimate made about op counts in this session was wrong — twice by more
 * than an order of magnitude.
 */
public class CapIntuitionTest {

	/**
	 * EXAMPLE 1 — "aurora": a layered, animated, domain-warped plasma with a colour ramp.
	 * Six octaves of sine-field noise, each rotated and time-advanced, warped by a cheap
	 * second field, then mapped through a three-stop gradient. This is a genuinely decorative
	 * material — the kind of thing someone builds a sign or a wall panel out of.
	 */
	@Test
	public void example1_aRichAnimatedMaterialFitsComfortably() throws Exception {
		final OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		Expr uv = b.builtin(SurfaceTable.REG_UV);
		Expr t = b.uniform("time");

		// Domain warp: a cheap field that displaces the sampling position.
		Expr warp = uv.x().mul(b.f(3.1f)).add(t.mul(b.f(0.7f))).sin()
				.mul(uv.y().mul(b.f(2.3f)).sub(t.mul(b.f(0.4f))).cos())
				.mul(b.f(0.15f));
		Expr p = uv.add(warp.splat(2));

		// Six octaves, each rotated by a fixed angle and advanced at its own rate.
		Expr acc = b.f(0.0f);
		float freq = 1.0f;
		float amp = 0.5f;
		for (int o = 0; o < 6; o++) {
			float c = (float) Math.cos(0.7f * o);
			float s = (float) Math.sin(0.7f * o);
			Expr rx = p.x().mul(b.f(c)).sub(p.y().mul(b.f(s)));
			Expr ry = p.x().mul(b.f(s)).add(p.y().mul(b.f(c)));
			Expr wave = rx.mul(b.f(freq)).add(t.mul(b.f(0.3f + 0.11f * o))).sin()
					.add(ry.mul(b.f(freq * 1.3f)).sub(t.mul(b.f(0.2f))).cos());
			acc = acc.add(wave.mul(b.f(amp)));
			freq *= 1.9f;
			amp *= 0.62f;
		}

		// Three-stop colour ramp over the accumulated field.
		Expr k = acc.mul(b.f(0.5f)).add(b.f(0.5f)).clamp(b.f(0f), b.f(1f));
		Expr cold = b.constant(0.05f, 0.20f, 0.45f, 1f);
		Expr mid = b.constant(0.10f, 0.75f, 0.65f, 1f);
		Expr hot = b.constant(0.95f, 0.85f, 0.35f, 1f);
		Expr lower = cold.mix(mid, k.mul(b.f(2f)).clamp(b.f(0f), b.f(1f)));
		Expr upper = mid.mix(hot, k.sub(b.f(0.5f)).mul(b.f(2f)).clamp(b.f(0f), b.f(1f)));
		Expr colour = lower.mix(upper, k.step(b.f(0.5f)));

		b.out(OcslWire.PROP_COLOR, colour);
		long charge = b.structuralCount();
		// THE STAGE'S CAP, not the ceiling. MAX_STRUCTURAL_OPS became a CEILING when the caps
		// went per-stage on 2026-08-21 (1024 then, 8192 since increment M), while this
		// pixel-stage example is bounded by 256 — so printing and asserting against the ceiling
		// reported a denominator four times the real one then and THIRTY-TWO times now, and the
		// assertion stopped being able to fail for any program under the ceiling. The
		// number PLAN quotes from this line ("153/256") is the per-stage one.
		int cap = IrValidator.maxStructuralOps(OcslWire.STAGE_PIXEL_MATERIAL);
		System.out.println("[cap] example 1 'aurora' (6 octaves + warp + ramp) = " + charge
				+ " structural ops of " + cap);
		IrProgram p2 = b.build();
		assertTrue("example 1 must fit, or it is not an example of fitting",
				IrValidator.validate(p2).structuralOps <= cap);
	}

	/**
	 * EXAMPLE 2 — a raymarched SDF scene, the canonical "I want to make something cool" shader.
	 *
	 * Cannot be built whole: the builder refuses at the op that crosses 256, which is itself the
	 * finding. So this measures ONE march step (position advance + a two-primitive smooth-union
	 * SDF, with no scaffolding ops -- an earlier draft inflated this by a type-wrangling
	 * line that did no SDF work) and reports what a usable march would charge. The arithmetic is over a MEASURED unit,
	 * not an estimated one.
	 */
	@Test
	public void example2_aRaymarchedSceneDoesNotFitAtAnyUsefulQuality() throws Exception {
		final OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		Expr uv = b.builtin(SurfaceTable.REG_UV);
		Expr t = b.uniform("time");
		Expr ro = b.constant(0f, 0f, -3f);
		Expr rd = b.constant(0f, 0f, 1f).add(
				b.constant(1f, 0f, 0f).mul(uv.x()).add(b.constant(0f, 1f, 0f).mul(uv.y())))
				.normalize();

		long before = b.structuralCount();

		// ---- ONE march step ----
		Expr dist = b.f(0.0f);
		Expr pos = ro.add(rd.mul(dist));
		// sphere
		Expr d1 = pos.length().sub(b.f(1.0f));
		// torus, displaced and animated
		Expr q = pos.add(b.constant(0.6f, 0f, 0f).mul(t.sin()));
		// torus: length(vec2(length(p.xz) - R, p.y)) - r, written scalar-wise.
		Expr ring = q.swz("xz").length().sub(b.f(0.7f));
		Expr d2b = q.y().mul(q.y()).add(ring.mul(ring)).sqrt().sub(b.f(0.25f));
		// smooth union
		Expr h = b.f(0.5f).add(b.f(0.5f).mul(d2b.sub(d1)).div(b.f(0.3f)))
				.clamp(b.f(0f), b.f(1f));
		Expr sdf = d2b.mix(d1, h).sub(h.mul(b.f(1f).sub(h)).mul(b.f(0.3f)));
		dist = dist.add(sdf);

		long perStep = b.structuralCount() - before;
		System.out.println("[cap] example 2 raymarch: ONE step = " + perStep + " structural ops");
		for (int steps : new int[] { 24, 32, 48, 64 }) {
			// A march also needs a normal (4 more SDF evaluations) and shading; counted as
			// 4 * (per-step SDF portion) + ~20, which is conservative.
			long total = (long) steps * perStep + 4L * perStep + 20L;
			System.out.println("[cap]   " + steps + " steps + normal + shading ~= " + total
					+ " structural ops (" + String.format("%.1f", total / (double) IrValidator.maxStructuralOps(OcslWire.STAGE_PIXEL_MATERIAL)) + "x the cap)");
		}
		// Pinned, not merely printed, since increment M: PLAN-STAGE-C D7's magnitude derivation
		// for the 8192 ceiling -- and the javadoc now at IrValidator.MAX_STRUCTURAL_OPS -- is
		// built on this measured unit. It was a println and an assertion that passes for any
		// value in [5, infinity), so an edit to the SDF body above would have moved the whole
		// rationale ladder silently. assertEquals subsumes the old bound.
		assertEquals("the per-step charge is the unit PLAN-STAGE-C D7's ceiling derivation rests"
				+ " on (the 24/32/48/64-step ladder = 804/1028/1476/1924 at this value, plus 132"
				+ " overhead); if this moved, the 8192 ceiling's magnitude argument moved with it"
				+ " and D7 needs re-deriving rather than this literal patching", 28L, perStep);
	}
}
