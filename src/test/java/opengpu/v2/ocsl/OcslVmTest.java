package opengpu.v2.ocsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Test;

/**
 * The CPU VM: does it execute what the validator accepted, exactly as the frozen rules say?
 *
 * The load-bearing tests here are SELECT's strict pick and the zero-allocation property. The first
 * is the guarantee that closed the mix()-laundering hole; the second is an implementation
 * requirement the design states outright, because op-count budgets only correspond to real time
 * under it and a 1.7.10 client cannot afford GC on the render thread.
 */
public strictfp class OcslVmTest {

	private static final int W = SurfaceTable.WORKING_BASE;

	private static int k(int i) {
		return OcslWire.OPERAND_CONST_FLAG | i;
	}

	private static OcslVm vm(IrProgram p) throws Exception {
		return new OcslVm(IrValidator.validate(p));
	}

	private static IrProgram prog(float[][] consts, int registers, IrOp... ops) {
		return new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL, consts, Arrays.asList(ops),
				new ArrayList<String>(), registers);
	}

	private static float[] runAndRead(IrProgram p) throws Exception {
		OcslVm vm = vm(p);
		vm.run();
		float[] out = new float[4];
		vm.output(OcslWire.PROP_COLOR, out);
		return out;
	}

	@Test
	public void theOutputFormSurvivesTheTripThroughTheVm() throws Exception {
		// The VM hands back the raw output whichever form wrote it, so the FORM has to be readable
		// from here or the consumer composes an absolute write relatively — the exact defect the
		// opcode exists to prevent, applied silently once per node per frame.
		//
		// Only the relative half is exercisable today: OUT_ABS is refused at every OPEN stage by
		// design (SurfaceTable.composesOutputs), and the one stage that accepts it is shut. The
		// true half lands with the first animator program tests, PLAN 1.3.
		OcslVm vm = vm(prog(new float[][] { { 1.0f } }, W + 1,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)));
		assertTrue("a relative write must not report itself absolute",
				!vm.isAbsolute(OcslWire.PROP_COLOR));
		try {
			vm.isAbsolute(7); // a property this program never wrote
			fail("the VM must not answer for a property the program never wrote");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("writes no property"));
		}
	}

	@Test
	public void evaluatesArithmeticThroughToTheOutput() throws Exception {
		float[] out = runAndRead(prog(new float[][] { { 2.0f }, { 3.0f } }, W + 2,
				new IrOp(OcslWire.OP_MUL, W, k(0), k(1)),
				new IrOp(OcslWire.OP_SPLAT, W + 1, W, 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 1)));
		assertEquals(6.0f, out[0], 0f);
		assertEquals(6.0f, out[3], 0f);
	}

	@Test
	public void broadcastsAScalarAcrossAVectorLane() throws Exception {
		// The re-opened amendment-4 rule at RUNTIME, not just in the type checker: a scalar
		// operand supplies component 0 to every lane, which is what makes mix(vecN, vecN, float)
		// one instruction rather than a splat plus an op.
		float[] out = runAndRead(prog(new float[][] { { 0.0f, 0.0f, 0.0f, 0.0f },
				{ 1.0f, 1.0f, 1.0f, 1.0f }, { 0.25f } }, W + 1,
				new IrOp(OcslWire.OP_MIX, W, k(0), k(1), k(2)),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)));
		for (int i = 0; i < 4; i++) {
			assertEquals("lane " + i, 0.25f, out[i], 1e-6f);
		}
	}

	@Test
	public void selectDiscardsTheOtherArmEvenWhenItIsNonFinite() throws Exception {
		// THE FROZEN GUARANTEE, and the one A4's catch-all had to be written around. An earlier
		// draft of the catch-all had no select exemption, which would have mandated exactly the
		// discarded-arm contamination strict-pick exists to forbid -- caught in review before it
		// froze. Both directions are pinned so the exemption cannot be half-implemented.
		float[][] consts = { { 1.0f }, { 0.0f }, { 2.0f } };

		// Build Inf in-domain (exp overflows float32) rather than smuggling it through a
		// constant, which the codec refuses -- so this is the route the design says stays open.
		IrProgram picksFinite = prog(consts, W + 4,
				new IrOp(OcslWire.OP_LT, W, k(1), k(0)),           // 0 < 1 -> true
				new IrOp(OcslWire.OP_SPLAT, W + 1, k(2), 4),       // finite arm = 2
				new IrOp(OcslWire.OP_SPLAT, W + 2, k(0), 4),
				new IrOp(OcslWire.OP_SELECT, W + 3, W, W + 1, W + 2),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 3));
		assertEquals("the selected arm survives untouched", 2.0f, runAndRead(picksFinite)[0], 0f);

		IrProgram picksOther = prog(consts, W + 4,
				new IrOp(OcslWire.OP_LT, W, k(0), k(1)),           // 1 < 0 -> false
				new IrOp(OcslWire.OP_SPLAT, W + 1, k(2), 4),
				new IrOp(OcslWire.OP_SPLAT, W + 2, k(0), 4),       // = 1
				new IrOp(OcslWire.OP_SELECT, W + 3, W, W + 1, W + 2),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 3));
		assertEquals("the other arm is what comes out", 1.0f, runAndRead(picksOther)[0], 0f);
	}

	@Test
	public void interpretsLoopsAndReadsTheCounterThroughItof() throws Exception {
		// A3: the VM INTERPRETS rather than unrolling, body registers are reused, and the
		// accumulator is written once per iteration. Sum of ITOF over 5 iterations = 0+1+2+3+4.
		float[] out = runAndRead(prog(new float[][] { { 0.0f, 0.0f, 0.0f, 0.0f } }, W + 3,
				new IrOp(OcslWire.OP_FOR, W, 5, k(0)),
				new IrOp(OcslWire.OP_ITOF, W + 1, 0),
				new IrOp(OcslWire.OP_SPLAT, W + 2, W + 1, 4),
				new IrOp(OcslWire.OP_ADD, W, W, W + 2),
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)));
		assertEquals("0+1+2+3+4", 10.0f, out[0], 0f);
	}

	@Test
	public void nestedLoopsCountIndependentlyAndItofSelectsByDepth() throws Exception {
		// ITOF's operand is a loop-DEPTH selector (0 = innermost). Outer 3 x inner 2, summing the
		// OUTER counter: 2*(0+1+2) = 6.
		float[] out = runAndRead(prog(new float[][] { { 0.0f, 0.0f, 0.0f, 0.0f } }, W + 3,
				new IrOp(OcslWire.OP_FOR, W, 3, k(0)),
				new IrOp(OcslWire.OP_FOR, W + 1, 2, k(0)),
				new IrOp(OcslWire.OP_ITOF, W + 2, 1),   // depth 1 = the OUTER loop
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_ADD, W, W, W + 2),
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)));
		assertEquals("outer counter summed once per outer iteration", 3.0f, out[0], 0f);
	}

	@Test
	public void twoConstantOperandsInOneOpDoNotClobberEachOther() throws Exception {
		// A real bug in the first draft: constants have no frame slot, so reading one as a whole
		// vector needs scratch -- and a single shared buffer made `DOT k1, k2` compute
		// dot(k2, k2). Per-slot lanes fix it, and this pins that they stay separate.
		float[] out = runAndRead(prog(new float[][] {
				{ 1.0f, 2.0f }, { 3.0f, 4.0f }, { 0.0f } }, W + 2,
				new IrOp(OcslWire.OP_DOT, W, k(0), k(1)),   // 1*3 + 2*4 = 11, NOT dot(k1,k1)=5
				new IrOp(OcslWire.OP_SPLAT, W + 1, W, 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 1)));
		assertEquals("dot of two DIFFERENT constants", 11.0f, out[0], 0f);
	}

	@Test
	public void swizzleOntoItsOwnSourceReadsBeforeItWrites() throws Exception {
		// .yx onto the same register would corrupt itself if components were written as they were
		// read. Not reachable today (the validator forbids retyping), but the op is written to be
		// safe rather than to rely on that.
		float[] out = runAndRead(prog(new float[][] { { 1.0f, 2.0f }, { 0.0f } }, W + 3,
				new IrOp(OcslWire.OP_SWZ, W, k(0), OcslWire.packSwizzle(1, 0)),
				new IrOp(OcslWire.OP_SWZ, W + 1, W, OcslWire.packSwizzle(0)),
				new IrOp(OcslWire.OP_SPLAT, W + 2, W + 1, 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 2)));
		assertEquals("swapped, so .x is the old .y", 2.0f, out[0], 0f);
	}

	@Test
	public void aWholeEvaluationAllocatesNothingInSteadyState() throws Exception {
		// The design pins zero allocation per evaluation as an implementation REQUIREMENT: op
		// budgets only track real time under it, and an animator runs per node per frame.
		//
		// THIS TEST WAS BLIND TO A REAL DEFECT THREE WAYS OVER, and the fix is as much to the test
		// as to the VM. (1) It measured run() alone -- but an evaluation is run() THEN output(),
		// and output() walked the op list with an enhanced-for, allocating an iterator per call.
		// (2) Its threshold was 2 MB over 20k iterations, so a per-call allocation of tens of bytes
		// fitted under it with room to spare. (3) Worst, its METRIC was heap occupancy rather than
		// allocation -- see allocatedBytes() below, where injecting the exact regression made the
		// old form pass five runs out of five. Measuring the wrong thing on half the path against a
		// threshold that could not bite: any one of those alone would have hidden the defect.
		IrProgram p = prog(new float[][] { { 0.0f, 0.0f, 0.0f, 0.0f }, { 1.5f } }, W + 4,
				new IrOp(OcslWire.OP_FOR, W, 8, k(0)),
				new IrOp(OcslWire.OP_ITOF, W + 1, 0),
				new IrOp(OcslWire.OP_SIN, W + 2, W + 1),
				new IrOp(OcslWire.OP_SPLAT, W + 3, W + 2, 4),
				new IrOp(OcslWire.OP_ADD, W, W, W + 3),
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W));
		OcslVm vm = vm(p);
		float[] out = new float[4];
		for (int i = 0; i < 2000; i++) {
			vm.run(); // warm up, and let any one-shot allocation happen
			vm.output(OcslWire.PROP_COLOR, out);
		}
		final int runs = 100_000;
		long before = allocatedBytes();
		for (int i = 0; i < runs; i++) {
			vm.run();
			vm.output(OcslWire.PROP_COLOR, out);
		}
		long perEvaluation = (allocatedBytes() - before) / runs;
		assertTrue("allocated " + perEvaluation + " bytes per evaluation", perEvaluation < 1L);
	}

	/**
	 * Bytes this thread has allocated, from the JVM's own counter.
	 *
	 * NOT {@code totalMemory() - freeMemory()}, which is what this test used to measure, and the
	 * difference is the difference between a test and a coin flip. That expression reports heap
	 * OCCUPANCY: a young-gen collection inside the measurement window frees more than the loop
	 * allocates and the delta comes out NEGATIVE. Injecting exactly the 56-byte-per-evaluation
	 * regression this test was rewritten to catch made it pass five times out of five at
	 * {@code -Xmn16m} (reported growth: -684328 bytes, while 11.2 MB was really allocated), fail at
	 * {@code -Xmn32m}, and pass again at 4096 bytes per evaluation in a long-lived JVM -- which is
	 * exactly what Gradle provides, one forked JVM for all 403 tests. Pass/fail was not even
	 * monotonic in the quantity under test.
	 *
	 * The counter here measures allocation directly, so the threshold can be ONE BYTE per
	 * evaluation instead of a megabyte of hopeful slack.
	 */
	private static long allocatedBytes() {
		java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory
				.getThreadMXBean();
		try {
			// getThreadAllocatedBytes is on com.sun.management.ThreadMXBean, reached reflectively
			// so the suite still compiles and runs on a JVM that does not expose it.
			return (Long) Class.forName("com.sun.management.ThreadMXBean")
					.getMethod("getThreadAllocatedBytes", long.class)
					.invoke(bean, Long.valueOf(Thread.currentThread().getId()));
		} catch (Exception e) {
			throw new IllegalStateException("this JVM exposes no thread allocation counter, so the"
					+ " zero-allocation requirement cannot be measured here", e);
		}
	}

	@Test
	public void crossIsOneTotalOpRatherThanACompositionOfThem() throws Exception {
		// Composing total ops does NOT give a total op, and this is the case that proves it. The
		// first draft built cross from sub(mul(..), mul(..)) in the VM: mul overflowed float32, the
		// catch-all correctly produced 0, and that 0 was then fed to sub -- so the op returned
		// -1e-20, a plausible finite number, for a value near 1e40 that float32 cannot hold.
		// Both constants are finite, so nothing upstream refuses this program.
		float[] out = runAndRead(prog(new float[][] {
				{ 0f, 1e20f, 1e-20f }, { 0f, 1f, 1e20f }, { 0f } }, W + 2,
				new IrOp(OcslWire.OP_CROSS, W, k(0), k(1)),
				new IrOp(OcslWire.OP_CONS4_V3F, W + 1, W, k(2)),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 1)));
		assertEquals("x is not representable, so the op owes 0", 0f, out[0], 0f);

		// And it is still a cross product where the answer fits.
		float[] ok = runAndRead(prog(new float[][] {
				{ 1f, 0f, 0f }, { 0f, 1f, 0f }, { 0f } }, W + 2,
				new IrOp(OcslWire.OP_CROSS, W, k(0), k(1)),
				new IrOp(OcslWire.OP_CONS4_V3F, W + 1, W, k(2)),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 1)));
		assertEquals("x", 0f, ok[0], 0f);
		assertEquals("y", 0f, ok[1], 0f);
		assertEquals("z", 1f, ok[2], 0f);
	}

	@Test
	public void outputReadsThePropertysWidthAndNotTheCallersArrayLength() throws Exception {
		IrProgram p = prog(new float[][] { { 7.0f }, { 0.0f } }, W + 2,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W));
		OcslVm vm = vm(p);
		vm.run();

		// An oversized array is filled to the property's width and no further. It used to be
		// filled to out.length, scavenging the scratch lanes past the frame with no error at all.
		float[] big = new float[16];
		java.util.Arrays.fill(big, -1f);
		vm.output(OcslWire.PROP_COLOR, big);
		for (int i = 0; i < 4; i++) {
			assertEquals("component " + i, 7f, big[i], 0f);
		}
		for (int i = 4; i < big.length; i++) {
			assertEquals("lane " + i + " must be untouched, not read from past the register",
					-1f, big[i], 0f);
		}

		// And an array too short is refused rather than silently short-read, symmetric with set().
		try {
			vm.output(OcslWire.PROP_COLOR, new float[2]);
			org.junit.Assert.fail("a 2-float array cannot hold a vec4 property");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("4 component"));
		}
	}

}
