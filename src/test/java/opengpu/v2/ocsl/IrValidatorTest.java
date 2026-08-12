package opengpu.v2.ocsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Validator tests: type inference, stage-applicable reads, the A1 output check, the caps, and
 * A5's frame layout.
 *
 * The shape-uniformity tests are the load-bearing ones. GLSL 1.20 would accept
 * {@code mix(vec3, vec3, float)} happily, so nothing about the eventual codegen would catch an
 * implicit broadcast — but the committed program counts assume the splat costs its own op, so a
 * permissive validator would silently disagree with the builder about every program's size.
 */
public class IrValidatorTest {

	private static final int W = SurfaceTable.WORKING_BASE;

	private static IrProgram prog(byte stage, float[] consts, int registers, IrOp... ops) {
		return new IrProgram(stage, consts, Arrays.asList(ops), new ArrayList<String>(), registers);
	}

	private static IrProgram prog(byte stage, float[] consts, List<String> names, int registers,
			IrOp... ops) {
		return new IrProgram(stage, consts, Arrays.asList(ops), names, registers);
	}

	private static int k(int i) {
		return OcslWire.OPERAND_CONST_FLAG | i;
	}

	/** A minimal valid material: splat a constant to vec4 and write it. */
	private static IrProgram validMaterial() {
		return prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W));
	}

	private static void expectReject(IrProgram p, String fragment) {
		try {
			IrValidator.validate(p);
			fail("expected rejection mentioning \"" + fragment + "\"");
		} catch (ValidationException e) {
			assertTrue("message was: " + e.getMessage(),
					e.getMessage().toLowerCase().contains(fragment.toLowerCase()));
		}
	}

	// ---------------------------------------------------------------- happy path

	@Test
	public void acceptsAMinimalMaterial() throws Exception {
		IrValidator.Validated v = IrValidator.validate(validMaterial());
		assertEquals(OcslType.VEC4, v.typeOf(W));
		assertEquals(2L, v.structuralOps);
		assertEquals(0, v.fetches);
	}

	@Test
	public void infersThroughTheZooAndTheShapeChangingOps() throws Exception {
		IrProgram p = prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 0.5f }, W + 5,
				new IrOp(OcslWire.OP_SWZ, W, SurfaceTable.REG_UV, OcslWire.packSwizzle(0)),
				new IrOp(OcslWire.OP_SIN, W + 1, W),
				new IrOp(OcslWire.OP_SPLAT, W + 2, W + 1, 3),
				new IrOp(OcslWire.OP_CONS4_V3F, W + 3, W + 2, k(0)),
				new IrOp(OcslWire.OP_MUL, W + 4, W + 3, SurfaceTable.REG_TINT),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 4));
		IrValidator.Validated v = IrValidator.validate(p);
		assertEquals("swizzling one component of a vec2 yields a float", OcslType.FLOAT, v.typeOf(W));
		assertEquals(OcslType.VEC3, v.typeOf(W + 2));
		assertEquals("vec4(vec3, float) is one op", OcslType.VEC4, v.typeOf(W + 3));
		assertEquals(OcslType.VEC4, v.typeOf(W + 4));
	}

	// ---------------------------------------------------------------- shape uniformity

	@Test
	public void acceptsScalarVectorArithmeticOnEitherSide() throws Exception {
		// CORRECTED 2026-08-12. This test first asserted the opposite -- that vec3 * float is
		// rejected -- because I read the frozen "IR function ops are shape-uniform" rule as
		// covering arithmetic. It does not: the IR's op scope names "component-wise arithmetic,
		// scalar<->vector ops" as TWO categories, and the committed plasma program opens with
		// `MUL.vs uv, 8.0` as one charged instruction. Under the strict reading every scale in
		// every program costs an extra splat and not one committed count reproduces. The zoo stays
		// strict -- see refusesMixWithAScalarWeight, which is the same decision's other half.
		IrProgram right = prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 2.0f }, W + 3,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 3),
				new IrOp(OcslWire.OP_MUL, W + 1, W, k(0)),
				new IrOp(OcslWire.OP_SPLAT, W + 2, k(0), 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 2));
		assertEquals(OcslType.VEC3, IrValidator.validate(right).typeOf(W + 1));

		// Scalar on the left too: SUB and DIV are not commutative and `k - v` is a real pattern.
		IrProgram left = prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 2.0f }, W + 3,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 3),
				new IrOp(OcslWire.OP_SUB, W + 1, k(0), W),
				new IrOp(OcslWire.OP_SPLAT, W + 2, k(0), 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 2));
		assertEquals(OcslType.VEC3, IrValidator.validate(left).typeOf(W + 1));
	}

	@Test
	public void refusesMismatchedVectorWidthsInArithmetic() {
		// The scalar-vector allowance is exactly that. vec2 + vec3 is still nonsense.
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 4,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 2),
				new IrOp(OcslWire.OP_SPLAT, W + 1, k(0), 3),
				new IrOp(OcslWire.OP_ADD, W + 2, W, W + 1),
				new IrOp(OcslWire.OP_SPLAT, W + 3, k(0), 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 3)),
				"two vector widths may not");
	}

	@Test
	public void acceptsMixWithAScalarWeight() throws Exception {
		// REVERSED 2026-08-12 with the amendment-4 re-opening. This asserted a rejection until
		// three of the four acceptance programs turned out to use the form the design itself
		// called "an IR-design decision, not a GLSL necessity". Component-wise ops now broadcast.
		IrProgram p = prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 0.5f }, W + 4,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_SPLAT, W + 1, k(0), 4),
				new IrOp(OcslWire.OP_MIX, W + 2, W, W + 1, k(0)),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 2));
		assertEquals(OcslType.VEC4, IrValidator.validate(p).typeOf(W + 2));
	}

	// ---------------------------------------------------------------- stage-applicable reads

	@Test
	public void refusesAReadOfABuiltinTheStageDoesNotHave() {
		// A post-chain pass has no node, so no tint. The register id still EXISTS -- ids are
		// reserved across surfaces so one id never means two things -- it is simply unreadable.
		expectReject(prog(OcslWire.STAGE_PIXEL_POST, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_MUL, W, SurfaceTable.REG_TINT, SurfaceTable.REG_TINT),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), "does not have");
	}

	@Test
	public void bakeHasNoTimeBecauseItMustBeReRunnable() {
		expectReject(prog(OcslWire.STAGE_BAKE, new float[] { 1.0f }, W + 2,
				new IrOp(OcslWire.OP_SIN, W, SurfaceTable.REG_TIME),
				new IrOp(OcslWire.OP_SPLAT, W + 1, W, 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 1)), "does not have");
	}

	@Test
	public void theInputSamplerBelongsOnlyToEffectAndPost() throws Exception {
		IrProgram post = prog(OcslWire.STAGE_PIXEL_POST, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_SAMPLE, W, SurfaceTable.SLOT_INPUT, SurfaceTable.REG_UV),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W));
		assertEquals(1, IrValidator.validate(post).fetches);

		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_SAMPLE, W, SurfaceTable.SLOT_INPUT, SurfaceTable.REG_UV),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), "built-in `input`");
	}

	// ---------------------------------------------------------------- A1: the output check

	@Test
	public void refusesAnOutputOfTheWrongType() {
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 3),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), "expects vec4, got vec3");
	}

	@Test
	public void refusesAProgramThatNeverWritesItsRequiredProperty() {
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4)), "never writes COLOR");
	}

	@Test
	public void refusesADuplicateOutForOneProperty() {
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), "already written");
	}

	@Test
	public void refusesAnOutInsideALoop() {
		// The accumulator gets its OWN register. An earlier draft of this test reused the vec4 for
		// FOR's accumulator, which the validator rejected as a retype -- correctly, but for the
		// wrong reason, so the rule under test was never reached. A test that fails for an
		// unintended reason is not evidence about the rule it names.
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 2,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_FOR, W + 1, 2, k(0)),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W),
				new IrOp(OcslWire.OP_ENDFOR, -1)), "one writer per property");
	}

	// ---------------------------------------------------------------- register discipline

	@Test
	public void refusesAReadBeforeAWrite() {
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 2,
				new IrOp(OcslWire.OP_ABS, W, W + 1),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), "before anything writes it");
	}

	@Test
	public void refusesAWriteToABuiltinOrAUniform() {
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_SPLAT, SurfaceTable.REG_TINT, k(0), 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, SurfaceTable.REG_TINT)),
				"built-in input");
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f },
				Arrays.asList("strength"), W + 1,
				new IrOp(OcslWire.OP_SPLAT, SurfaceTable.UNIFORM_BASE, k(0), 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, SurfaceTable.UNIFORM_BASE)),
				"a uniform");
	}

	@Test
	public void refusesRetypingARegister() {
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_ABS, W, k(0)),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), "was vec4");
	}

	@Test
	public void readsAUniformItDeclares() throws Exception {
		IrProgram p = prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f },
				Arrays.asList("strength"), W + 1,
				new IrOp(OcslWire.OP_SPLAT, W, SurfaceTable.UNIFORM_BASE, 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W));
		assertEquals(1, IrValidator.validate(p).uniformComponents);
	}

	@Test
	public void refusesAReadOfAnUndeclaredUniform() {
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_SPLAT, W, SurfaceTable.UNIFORM_BASE + 3, 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), "does not declare");
	}

	// ---------------------------------------------------------------- bools are conditions only

	@Test
	public void refusesABoolReachingArithmetic() {
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 2,
				new IrOp(OcslWire.OP_LT, W, k(0), k(0)),
				new IrOp(OcslWire.OP_ABS, W + 1, W),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 1)), "conditions only");
	}

	@Test
	public void selectRequiresABoolConditionAndMatchingArms() throws Exception {
		IrProgram ok = prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f, 2.0f }, W + 3,
				new IrOp(OcslWire.OP_LT, W, k(0), k(1)),
				new IrOp(OcslWire.OP_SPLAT, W + 1, k(0), 4),
				new IrOp(OcslWire.OP_SELECT, W + 2, W, W + 1, W + 1),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 2));
		assertEquals(OcslType.VEC4, IrValidator.validate(ok).typeOf(W + 2));

		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 3,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_SPLAT, W + 1, k(0), 3),
				new IrOp(OcslWire.OP_LT, W + 2, k(0), k(0)),
				new IrOp(OcslWire.OP_SELECT, W, W + 2, W, W + 1),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), "select's arms");
	}

	@Test
	public void comparisonsAreScalarOnly() {
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 3,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 2),
				new IrOp(OcslWire.OP_LT, W + 1, W, W),
				new IrOp(OcslWire.OP_SPLAT, W + 2, k(0), 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 2)), "scalar-only");
	}

	// ---------------------------------------------------------------- caps

	@Test
	public void enforcesTheStructuralOpCap() {
		List<IrOp> ops = new ArrayList<IrOp>();
		ops.add(new IrOp(OcslWire.OP_SPLAT, W, k(0), 4));
		for (int i = 0; i < IrValidator.MAX_STRUCTURAL_OPS; i++) {
			ops.add(new IrOp(OcslWire.OP_ABS, W, W));
		}
		ops.add(new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W));
		expectReject(new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, ops,
				new ArrayList<String>(), W + 1), "over the cap");
	}

	@Test
	public void enforcesTheFetchCapPostUnroll() {
		// 9 taps in a loop of 2 is 18 fetches, over the cap of 16 -- the count is post-unroll,
		// so a loop cannot hide fetches behind a small op listing.
		List<IrOp> ops = new ArrayList<IrOp>();
		ops.add(new IrOp(OcslWire.OP_SPLAT, W, k(0), 4));
		ops.add(new IrOp(OcslWire.OP_FOR, W + 1, 2, k(0)));
		for (int i = 0; i < 9; i++) {
			ops.add(new IrOp(OcslWire.OP_SAMPLE, W + 2, SurfaceTable.SLOT_INPUT, SurfaceTable.REG_UV));
		}
		ops.add(new IrOp(OcslWire.OP_ENDFOR, -1));
		ops.add(new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W));
		expectReject(new IrProgram(OcslWire.STAGE_PIXEL_POST, new float[] { 1.0f }, ops,
				new ArrayList<String>(), W + 3), "fetches post-unroll");
	}

	@Test
	public void refusesAZeroTripLoop() {
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 2,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_FOR, W + 1, 0, k(0)),
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), "never runs");
	}

	// ---------------------------------------------------------------- review fallout, pinned

	@Test
	public void sequentialLoopsDoNotMultiplyAgainstTheUnrollCap() throws Exception {
		// The cap is on the deepest NESTING PATH. An accumulating product never unwinds, so two
		// sequential loops of 20 read as 400 and were refused -- while the frozen entry calls this
		// cap "equal to the op cap, therefore NON-BINDING" on an argument that is about nesting.
		// 2 x 20 iterations of one op = 40 structural, nowhere near the 256 op cap.
		IrProgram p = prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 3,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_FOR, W + 1, 20, k(0)),
				new IrOp(OcslWire.OP_ADD, W + 1, W + 1, k(0)),
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_FOR, W + 2, 20, k(0)),
				new IrOp(OcslWire.OP_ADD, W + 2, W + 2, k(0)),
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W));
		assertEquals("40 body ops + splat + OUT", 42L, IrValidator.validate(p).structuralOps);

		// Nesting still multiplies, which is the half that must keep working.
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 3,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_FOR, W + 1, 20, k(0)),
				new IrOp(OcslWire.OP_FOR, W + 2, 20, k(0)),
				new IrOp(OcslWire.OP_ADD, W + 2, W + 2, k(0)),
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), "unroll product 400");
	}

	@Test
	public void reservesTheTimePeriodIdWithoutMakingItReadable() {
		// The design assigns this reservation to the surface table three times over, as the
		// substitute guard for an obligation neither code gate covers: without a register carrying
		// P, a program must bake 1/P into its constant pool -- a CONTRACT CONSTANT frozen into
		// every saved blob, which the caps-monotonicity rule does not cover because P is not a cap.
		// The id is taken now; the type follows when P is published (REG_NORMAL's precedent).
		assertTrue("timePeriod must have a distinct built-in id",
				SurfaceTable.REG_TIME_PERIOD != SurfaceTable.REG_TIME
						&& SurfaceTable.REG_TIME_PERIOD < SurfaceTable.BUILTIN_LIMIT);
		for (byte stage : new byte[] { OcslWire.STAGE_PIXEL_MATERIAL, OcslWire.STAGE_PIXEL_EFFECT,
				OcslWire.STAGE_PIXEL_POST, OcslWire.STAGE_BAKE }) {
			org.junit.Assert.assertNull("timePeriod is reserved, not yet readable",
					SurfaceTable.builtinType(stage, SurfaceTable.REG_TIME_PERIOD));
		}
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 2,
				new IrOp(OcslWire.OP_MUL, W, SurfaceTable.REG_TIME, SurfaceTable.REG_TIME_PERIOD),
				new IrOp(OcslWire.OP_SPLAT, W + 1, W, 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 1)), "does not have");
	}

	@Test
	public void refusesAStageWithNoPropertyTable() {
		// The vertex stage is reserved for Stage C and refused by no codec tripwire. Without this,
		// a vertex program with no OUT at all validated as ACCEPTABLE -- "it validated" meaning
		// nothing, because that stage cannot produce an output.
		expectReject(prog(OcslWire.STAGE_VERTEX, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4)), "no property table");
	}

	@Test
	public void enforcesA5sProvisionalFrameCaps() {
		// Of every cap in the design these are the two that "cannot be added later": they bound
		// the shape of the frame a blob describes, and once blobs exist a frame wider than the
		// runtime preallocates has no safe reading.
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f },
				SurfaceTable.MAX_REGISTERS + 1,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), "over the cap of");

		// The frame cap has to be tripped INSIDE the op cap or the op cap answers first, and the
		// two sit close: at 256 ops the widest reachable frame is 255 vec4 registers plus the
		// material built-ins, about 1029 floats against the 1024 cap. 255 splats + OUT is exactly
		// 256 ops and 9 + 1020 = 1029 floats -- the narrow window where the frame cap is the one
		// that speaks. That narrowness is itself worth knowing: raising the op cap later (which
		// monotonicity permits) makes this cap bind much sooner.
		List<IrOp> wide = new ArrayList<IrOp>();
		int n = 255;
		for (int i = 0; i < n; i++) {
			wide.add(new IrOp(OcslWire.OP_SPLAT, W + i, k(0), 4)); // vec4: 4 floats each
		}
		wide.add(new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W));
		expectReject(new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, wide,
				new ArrayList<String>(), W + n), "frame of");
	}

	// ---------------------------------------------------------------- A5: the frame layout

	@Test
	public void packsTheFrameByDeclaredWidthInFirstWriteOrder() throws Exception {
		IrProgram p = prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 3,
				new IrOp(OcslWire.OP_SPLAT, W + 2, k(0), 3),   // written FIRST despite the id
				new IrOp(OcslWire.OP_ABS, W, k(0)),            // float, second
				new IrOp(OcslWire.OP_CONS4_V3F, W + 1, W + 2, W),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 1));
		IrValidator.Validated v = IrValidator.validate(p);

		// Built-ins the stage HAS come first, at fixed positions, so a program that reads none
		// still agrees with one that reads all of them about where working registers begin.
		int builtinWidth = OcslType.VEC2.width + OcslType.VEC2.width + OcslType.VEC4.width
				+ OcslType.FLOAT.width; // uv, position, tint, time
		assertEquals("W+2 was written first", builtinWidth, v.frameOffset(W + 2));
		assertEquals("W came second", builtinWidth + 3, v.frameOffset(W));
		assertEquals("W+1 came third", builtinWidth + 4, v.frameOffset(W + 1));
		assertEquals(builtinWidth + 3 + 1 + 4, v.frameWidth);
	}

	@Test
	public void frameLayoutIsAPureFunctionOfTheBlob() throws Exception {
		// The same program validated twice must lay out identically -- that is what lets the
		// builder and the validator agree without exchanging anything but bytes.
		IrValidator.Validated a = IrValidator.validate(validMaterial());
		IrValidator.Validated b = IrValidator.validate(validMaterial());
		assertEquals(a.frameWidth, b.frameWidth);
		assertEquals(a.frameOffset(W), b.frameOffset(W));
	}
}
