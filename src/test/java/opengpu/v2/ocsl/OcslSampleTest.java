package opengpu.v2.ocsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The pinned sampling rules S1–S5, each asserted at the input that distinguishes it from the
 * plausible alternative.
 *
 * A "looks like a blur" test would pass under every convention considered. These do not: each one
 * is placed where the half-texel offset, the term order, or the 255 divisor changes the answer.
 */
public strictfp class OcslSampleTest {

	/** A 2x2 texture with distinct opaque channel values, so a lane or tap mix-up is visible. */
	private static OcslTexture checker() {
		byte[] px = new byte[2 * 2 * 4];
		int[] reds = { 0, 255, 51, 204 };   // 0.0, 1.0, 0.2, 0.8 exactly under /255
		for (int i = 0; i < 4; i++) {
			px[i * 4] = (byte) reds[i];
			px[i * 4 + 1] = (byte) (i * 10);
			px[i * 4 + 2] = (byte) (i * 20);
			px[i * 4 + 3] = (byte) 255;
		}
		return new OcslTexture(2, 2, px);
	}

	private static float[] at(OcslTexture t, float u, float v) {
		float[] frame = new float[4];
		OcslMath.sample(t.rgba, t.width, t.height, u, v, frame, 0);
		return frame;
	}

	@Test
	public void texelCentresSitAtTheHalfTexelOffset() {
		// S2's load-bearing consequence. Under `u*width - 0.5` the centre of texel i is at
		// u = (i+0.5)/width, and sampling there returns that texel EXACTLY -- no neighbour bleed.
		// Under the endpoint-stretch alternative (u*(width-1)) these same coordinates would each
		// return a blend, which is how the two conventions are told apart.
		OcslTexture t = checker();
		assertEquals("texel (0,0) at its centre", 0.0f, at(t, 0.25f, 0.25f)[0], 0f);
		assertEquals("texel (1,0) at its centre", 1.0f, at(t, 0.75f, 0.25f)[0], 0f);
		assertEquals("texel (0,1) at its centre", 0.2f, at(t, 0.25f, 0.75f)[0], 1e-7f);
		assertEquals("texel (1,1) at its centre", 0.8f, at(t, 0.75f, 0.75f)[0], 1e-7f);
	}

	@Test
	public void theOuterHalfTexelIsFlatUnderClampToEdge() {
		// The visible difference from endpoint-stretch: this convention has a flat shoulder of half
		// a texel at each edge, because both taps clamp to the same texel there. Endpoint-stretch
		// has no flat region and would ramp across the whole [0,1].
		OcslTexture t = checker();
		assertEquals("u=0 and u=0.25 are both inside texel 0's shoulder",
				at(t, 0.0f, 0.25f)[0], at(t, 0.25f, 0.25f)[0], 0f);
		assertEquals("and the far edge likewise",
				at(t, 1.0f, 0.25f)[0], at(t, 0.75f, 0.25f)[0], 0f);
	}

	@Test
	public void theMidpointBlendsTwoTexelsEvenly() {
		OcslTexture t = checker();
		// Halfway between the two centres of row 0: exactly (0.0 + 1.0)/2.
		assertEquals(0.5f, at(t, 0.5f, 0.25f)[0], 1e-7f);
		// And the four-way centre of the whole image: (0 + 1 + 0.2 + 0.8)/4.
		assertEquals(0.5f, at(t, 0.5f, 0.5f)[0], 1e-7f);
	}

	@Test
	public void bytesConvertByTwoFiftyFiveSoWhiteIsReachable() {
		// S3. Dividing by 256 -- the other plausible choice, and the one that makes the arithmetic
		// a shift -- would make 0xFF land on 0.99609375 and white unreachable.
		OcslTexture t = checker();
		assertEquals("0xFF is exactly 1.0", 1.0f, at(t, 0.75f, 0.25f)[0], 0f);
		assertEquals("0x00 is exactly 0.0", 0.0f, at(t, 0.25f, 0.25f)[0], 0f);
		assertEquals("alpha is 0xFF throughout", 1.0f, at(t, 0.5f, 0.5f)[3], 0f);
	}

	@Test
	public void aNonFiniteUvReadsZeroPerComponent() {
		// NOT reachable from a CPU program, and saying so is the honest version: constants are
		// refused non-finite in three places, set() sanitizes, and every op funnels through f(),
		// so no OCSL program can deliver a non-finite uv to the VM. This is defence in depth here
		// -- and the rule Stage D must implement EXPLICITLY, because GLSL has no catch-all and a
		// NaN uv there picks a driver-specific texel.
		OcslTexture t = checker();
		for (float bad : new float[] { Float.NaN, Float.POSITIVE_INFINITY,
				Float.NEGATIVE_INFINITY }) {
			float[] r = at(t, bad, 0.5f);
			for (int c = 0; c < 4; c++) {
				assertEquals("uv.x = " + bad + ", component " + c, 0.0f, r[c], 0f);
			}
			float[] r2 = at(t, 0.5f, bad);
			for (int c = 0; c < 4; c++) {
				assertEquals("uv.y = " + bad + ", component " + c, 0.0f, r2[c], 0f);
			}
		}
	}

	@Test
	public void aWildlyOutOfRangeUvClampsInsteadOfWrappingItsIndex() {
		// The index clamp happens in DOUBLE before the narrowing cast. Casting first would saturate
		// at Integer.MAX_VALUE and the +1 tap would wrap to MIN_VALUE -- an ArrayIndexOutOfBounds
		// from a uv a program can reach by ordinary arithmetic.
		OcslTexture t = checker();
		for (float u : new float[] { 1e30f, -1e30f, 1e9f, -7.5f, 42.0f }) {
			float[] r = at(t, u, 0.25f);
			assertTrue("uv.x = " + u + " must clamp to an edge texel",
					r[0] == 0.0f || r[0] == 1.0f);
		}
		assertEquals("far right clamps to texel (1,0)", 1.0f, at(t, 1e30f, 0.25f)[0], 0f);
		assertEquals("far left clamps to texel (0,0)", 0.0f, at(t, -1e30f, 0.25f)[0], 0f);
	}

	@Test
	public void anUnboundSlotReadsZeroRatherThanFailing() throws Exception {
		// S5. The validator cannot know what the host will bind, so this must render predictably.
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_EFFECT);
		b.out(OcslWire.PROP_COLOR, b.sample(1, b.builtin(SurfaceTable.REG_UV)));
		OcslVm vm = new OcslVm(IrValidator.validate(b.build()));
		vm.set(SurfaceTable.REG_UV, 0.5f, 0.5f);
		vm.run();
		float[] out = new float[4];
		vm.output(OcslWire.PROP_COLOR, out);
		for (int c = 0; c < 4; c++) {
			assertEquals("component " + c, 0.0f, out[c], 0f);
		}
	}

	@Test
	public void aBoundTextureFlowsThroughTheVmAndTheBuilder() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_EFFECT);
		b.out(OcslWire.PROP_COLOR, b.sample(1, b.builtin(SurfaceTable.REG_UV)));
		OcslVm vm = new OcslVm(IrValidator.validate(b.build()));
		vm.bind(1, checker());
		vm.set(SurfaceTable.REG_UV, 0.75f, 0.25f);
		vm.run();
		float[] out = new float[4];
		vm.output(OcslWire.PROP_COLOR, out);
		assertEquals("texel (1,0)'s red, end to end", 1.0f, out[0], 0f);

		// Unbinding restores the S5 behaviour rather than keeping a stale texture.
		vm.bind(1, null);
		vm.run();
		vm.output(OcslWire.PROP_COLOR, out);
		assertEquals(0.0f, out[0], 0f);
	}

	@Test
	public void samplingAllocatesNothing() throws Exception {
		// Sampling is the per-pixel path of every effect and post program, so it is held to the
		// same zero-allocation requirement as the rest of run().
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_EFFECT);
		b.out(OcslWire.PROP_COLOR, b.sample(1, b.builtin(SurfaceTable.REG_UV)));
		OcslVm vm = new OcslVm(IrValidator.validate(b.build()));
		vm.bind(1, checker());
		vm.set(SurfaceTable.REG_UV, 0.3f, 0.7f);
		float[] out = new float[4];
		for (int i = 0; i < 2000; i++) {
			vm.run();
			vm.output(OcslWire.PROP_COLOR, out);
		}
		final int runs = 100_000;
		long before = allocatedBytes();
		for (int i = 0; i < runs; i++) {
			vm.run();
			vm.output(OcslWire.PROP_COLOR, out);
		}
		long perEvaluation = (allocatedBytes() - before) / runs;
		assertTrue("allocated " + perEvaluation + " bytes per sampled evaluation",
				perEvaluation < 1L);
	}

	private static long allocatedBytes() {
		java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory
				.getThreadMXBean();
		try {
			return (Long) Class.forName("com.sun.management.ThreadMXBean")
					.getMethod("getThreadAllocatedBytes", long.class)
					.invoke(bean, Long.valueOf(Thread.currentThread().getId()));
		} catch (Exception e) {
			throw new IllegalStateException("no thread allocation counter on this JVM", e);
		}
	}
}
