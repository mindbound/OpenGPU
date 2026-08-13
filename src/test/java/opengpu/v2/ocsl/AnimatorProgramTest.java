package opengpu.v2.ocsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * The animator surface END TO END — PLAN 1.3, the short-term goal.
 *
 * Everything above this file tests one component. This one runs the whole chain on one program:
 * authored in {@link OcslBuilder}, validated, encoded, DECODED, executed on {@link OcslVm}, and its
 * outputs composed over a server base through {@link OcslCompose} — which is the first time those
 * six pieces have been asked to agree with each other about anything.
 *
 * <h2>Every vector here runs the DECODED program</h2>
 *
 * Not the one the builder returned. The builder and the decoder are the two halves the format
 * exists to keep in agreement, and a test that executes the pre-encode object would pass with a
 * codec that silently dropped an op — which is precisely the failure the round-trip is for.
 *
 * <h2>Non-identity bases, and why the numbers look the way they do</h2>
 *
 * The server bases are {@code x = 3.0} and {@code sx = 1.5}, never 0 and never 1, because ANIM-3
 * says an identity-base vector passes under every candidate composition rule and therefore proves
 * nothing — and ANIM-7's double-apply defect is invisible at the identity for the same reason. The
 * inputs are chosen to be EXACT in float32 while not being round: {@code 1.7f} is not
 * representable, but {@code 0.25 * 1.7f} is, because dividing by four only moves the exponent. So
 * the assertions can be exact rather than toleranced, and still not sit on tidy values where a
 * wrong rule coincides with a right one.
 */
public strictfp class AnimatorProgramTest {

	private static final int U0 = SurfaceTable.UNIFORM_BASE;

	/** The server-set base this suite composes over. Deliberately not the composition identity. */
	private static final double X_BASE = 3.0;
	private static final double SX_BASE = 1.5;

	private static OcslVm roundTrip(IrProgram built) throws Exception {
		byte[] blob = IrCodec.encode(built);
		IrProgram decoded = IrCodec.decode(blob, IrCodec.Source.TRANSIENT);
		assertEquals("the stage must survive the wire", OcslWire.STAGE_ANIMATOR, decoded.stage);
		return new OcslVm(IrValidator.validate(decoded));
	}

	private static float out(OcslVm vm, int property) throws Exception {
		float[] components = new float[4];
		vm.output(property, components);
		return components[0];
	}

	@Test
	public void aProgramDrivenByTimeAndNodeSeedComposesOverItsServerBase() throws Exception {
		// x moves by an offset (ADD) and sx scales (MULTIPLY): two properties with DIFFERENT
		// composition rules in one program, which is what makes the composed values able to
		// disagree about which rule ran. Neither reads its own base — under the relative form that
		// is refused, and neither needs to, because relative composition supplies the base.
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		Expr time = b.builtin(SurfaceTable.REG_TIME);
		Expr seed = b.builtin(SurfaceTable.REG_ANIM_NODE_SEED);
		b.out(OcslWire.PROP_ANIM_X, time.mul(b.uniform("speed")));
		b.out(OcslWire.PROP_ANIM_SX, b.f(1.0f).add(seed.mul(b.f(0.5f))));
		IrProgram program = b.build();

		OcslVm vm = roundTrip(program);
		vm.set(SurfaceTable.REG_TIME, 0.25f);
		vm.set(SurfaceTable.REG_ANIM_NODE_SEED, 0.5f);
		vm.set(U0, 1.7f);
		vm.run();

		// The VM's raw outputs, exact: 0.25 * 1.7f lands on a representable float, and 1 + 0.5*0.5
		// is exact outright.
		float outX = out(vm, OcslWire.PROP_ANIM_X);
		float outSx = out(vm, OcslWire.PROP_ANIM_SX);
		assertEquals(0.25f * 1.7f, outX, 0f);
		assertEquals(1.25f, outSx, 0f);

		// COMPOSED, which is the number a viewer would see.
		float dispX = OcslCompose.compose(OcslWire.PROP_ANIM_X, X_BASE, outX);
		float dispSx = OcslCompose.compose(OcslWire.PROP_ANIM_SX, SX_BASE, outSx);
		assertEquals("x composes additively", 3.0f + 0.25f * 1.7f, dispX, 0f);
		assertEquals("sx composes multiplicatively", 1.875f, dispSx, 0f);

		// THE EXCLUSION ARITHMETIC. Each property is asserted against what the OTHER rule would
		// have produced from the same output, because "3.425" alone is satisfied by any
		// implementation that happens to add — the point is that multiply would have given 1.275,
		// and that add on sx would have given 2.75.
		assertTrue("multiplying x would give " + (3.0f * outX),
				Math.abs(dispX - 3.0f * outX) > 1.0f);
		assertTrue("adding sx would give " + (1.5f + outSx),
				Math.abs(dispSx - (1.5f + outSx)) > 0.5f);
	}

	@Test
	public void theSameOutputDisplaysDifferentlyUnderTheTwoOutputForms() throws Exception {
		// ANIM-7's discriminating vector, run end to end rather than against compose() alone. One
		// value, two spellings, two displayed numbers — which is the whole content of the decision.
		OcslBuilder relative = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		relative.out(OcslWire.PROP_ANIM_X, relative.f(0.75f));
		OcslVm relativeVm = roundTrip(relative.build());
		relativeVm.run();

		OcslBuilder absolute = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		absolute.outAbsolute(OcslWire.PROP_ANIM_X, absolute.f(0.75f));
		OcslVm absoluteVm = roundTrip(absolute.build());
		absoluteVm.run();

		float raw = out(relativeVm, OcslWire.PROP_ANIM_X);
		assertEquals("the same raw output either way", raw, out(absoluteVm, OcslWire.PROP_ANIM_X), 0f);

		// The VM reports which form wrote it, and that is what the consumer composes on.
		assertTrue("the relative program reports relative",
				!relativeVm.isAbsolute(OcslWire.PROP_ANIM_X));
		assertTrue("the absolute program reports absolute",
				absoluteVm.isAbsolute(OcslWire.PROP_ANIM_X));

		float dispRelative = OcslCompose.compose(OcslWire.PROP_ANIM_X, X_BASE, raw, false);
		float dispAbsolute = OcslCompose.compose(OcslWire.PROP_ANIM_X, X_BASE, raw, true);
		assertEquals("relative offsets the base", 3.75f, dispRelative, 0f);
		assertEquals("absolute replaces it", 0.75f, dispAbsolute, 0f);
		assertTrue("and the two forms must not coincide", dispRelative != dispAbsolute);
	}

	@Test
	public void aRelativeWriteThatReadsItsOwnBaseIsRefusedBeforeItCanBeBuilt() throws Exception {
		// The rule, from the authoring end. `out.x = anim.x + delta` is the double-apply defect and
		// never reaches a blob.
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.out(OcslWire.PROP_ANIM_X, b.builtin(SurfaceTable.REG_ANIM_X).add(b.f(0.5f)));
		try {
			b.build();
			fail("a relative write reading its own base applies the base twice");
		} catch (OcslBuilder.BuildException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("outAbsolute"));
		}

		// And the absolute spelling of the same intent goes all the way through, composing to the
		// value the author asked for rather than to base + value. This is the idiom the refusal
		// above exists to preserve, so the pair has to be tested together.
		OcslBuilder ok = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		ok.outAbsolute(OcslWire.PROP_ANIM_X, ok.builtin(SurfaceTable.REG_ANIM_X).add(ok.f(0.5f)));
		OcslVm vm = roundTrip(ok.build());
		vm.set(SurfaceTable.REG_ANIM_X, (float) X_BASE);
		vm.run();
		float raw = out(vm, OcslWire.PROP_ANIM_X);
		assertEquals("it read the base and added to it", 3.5f, raw, 0f);
		assertEquals("and absolute means that IS the displayed value", 3.5f,
				OcslCompose.compose(OcslWire.PROP_ANIM_X, X_BASE, raw, true), 0f);
		assertTrue("the relative reading of the same blob would double the base",
				OcslCompose.compose(OcslWire.PROP_ANIM_X, X_BASE, raw, false) == 6.5f);
	}

	@Test
	public void theVmCannotHandTheWriteBoundaryANonFiniteOutput() throws Exception {
		// THE REJECT PATH IS UNREACHABLE FROM A PROGRAM, and this pins that rather than pretending
		// otherwise. PLAN 1.3 asked for "the write-boundary reject path (NaN output → server base
		// displayed)" end to end; it cannot fire, because three rules make a VM output total:
		// the constant pool refuses non-finite entries, a non-finite BINDING is substituted with
		// zero on the way in, and OcslMath.f sanitizes every op RESULT — an overflow included.
		//
		// So a program that overflows float32 produces 0, not Infinity, and the write boundary sees
		// a finite value. The boundary is not dead code: it guards compose() against producers that
		// do not exist yet (a host-bound value, a second backend). But its reject arm is exercised
		// by OcslComposeTest calling compose directly, and NOT by anything a program can do — which
		// is worth stating, because a test that ran a program and asserted "base displayed" would
		// pass for the wrong reason: the base is displayed because the offset is ZERO.
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		Expr huge = b.uniform("huge");
		b.out(OcslWire.PROP_ANIM_X, huge.mul(huge));
		OcslVm vm = roundTrip(b.build());
		vm.set(U0, 1.0e30f);
		vm.run();

		float raw = out(vm, OcslWire.PROP_ANIM_X);
		assertEquals("1e30 * 1e30 overflows float32 and the op zeroes it", 0.0f, raw, 0f);
		assertTrue("so the boundary never sees a non-finite value from this path",
				OcslWriteBoundary.accepts(raw));
		assertEquals("and the composed result is the base, because the OFFSET is zero",
				3.0f, OcslCompose.compose(OcslWire.PROP_ANIM_X, X_BASE, raw), 0f);

		// The discriminator for the sentence above: the same composed answer arises from a rejected
		// write, so "displays the base" does not by itself tell the two apart. Only the raw value
		// does, and it is 0 rather than NaN.
		assertEquals(3.0f, OcslCompose.compose(OcslWire.PROP_ANIM_X, X_BASE, Float.NaN), 0f);
	}

	@Test
	public void timePeriodIsReadableAndSeededRatherThanBound() throws Exception {
		// ANIM-5's rule, reaching the animator for the first time: P is a constant of the FORMAT, so
		// the register carries it and no host binding can be wrong about it.
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.out(OcslWire.PROP_ANIM_X, b.builtin(SurfaceTable.REG_TIME_PERIOD));
		OcslVm vm = roundTrip(b.build());
		try {
			vm.set(SurfaceTable.REG_TIME_PERIOD, 3.0f);
			fail("timePeriod is not host state");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("not host state"));
		}
		vm.run();
		assertEquals("it reads P without anything binding it", OcslTime.PERIOD_SECONDS,
				out(vm, OcslWire.PROP_ANIM_X), 0f);
	}

	@Test
	public void theWholeAnimatorRegisterSetIsBindableAndReaches() throws Exception {
		// The frame layout, end to end. Every animator register the surface publishes must have a
		// slot the host can bind and a value the program can read back — a register that is
		// readable in the table but has no frame slot would fail only when someone tried to use it,
		// which for sinceAttach and the parent block is Phase 3.3, long after this would be cheap
		// to find.
		int[] regs = { SurfaceTable.REG_ANIM_X, SurfaceTable.REG_ANIM_SX,
				SurfaceTable.REG_ANIM_NODE_SEED, SurfaceTable.REG_ANIM_SINCE_ATTACH,
				SurfaceTable.REG_ANIM_PARENT_X, SurfaceTable.REG_ANIM_PARENT_SZ };
		for (int i = 0; i < regs.length; i++) {
			OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
			// OUT_ABS so the read is legal even when the register is the property's own base.
			b.outAbsolute(OcslWire.PROP_ANIM_Y, b.builtin(regs[i]));
			OcslVm vm = roundTrip(b.build());
			float probe = 0.5f + i;
			vm.set(regs[i], probe);
			vm.run();
			assertEquals("register " + regs[i] + " (" + SurfaceTable.builtinName(regs[i])
					+ ") must bind and read back", probe, out(vm, OcslWire.PROP_ANIM_Y), 0f);
		}
	}
}
