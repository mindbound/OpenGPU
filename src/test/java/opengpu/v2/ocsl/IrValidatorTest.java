package opengpu.v2.ocsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
	public void reservesTheTimePeriodIdAndNowPublishesItsValue() throws Exception {
		// The design assigns this reservation to the surface table three times over, as the
		// substitute guard for an obligation neither code gate covers: without a register carrying
		// P, a program must bake 1/P into its constant pool -- a CONTRACT CONSTANT frozen into
		// every saved blob, which the caps-monotonicity rule does not cover because P is not a cap.
		// The id is taken now; the type follows when P is published (REG_NORMAL's precedent).
		// THE DEFERRAL EXPIRED, 2026-08-12. This test used to assert timePeriod was reserved and
		// UNREADABLE, and its own comment recorded why: "the type follows when P is published". P is
		// published now (ANIM-5, OcslTime.PERIOD_SECONDS = 1680s), so the condition the deferral
		// named is met and the register is readable. Kept as the same test rather than deleted,
		// because the reservation is still the thing under test -- only its state moved.
		assertTrue("timePeriod must have a distinct built-in id",
				SurfaceTable.REG_TIME_PERIOD != SurfaceTable.REG_TIME
						&& SurfaceTable.REG_TIME_PERIOD < SurfaceTable.BUILTIN_LIMIT);
		for (byte stage : new byte[] { OcslWire.STAGE_PIXEL_MATERIAL, OcslWire.STAGE_PIXEL_EFFECT,
				OcslWire.STAGE_PIXEL_POST }) {
			assertEquals("timePeriod is readable wherever time is", OcslType.FLOAT,
					SurfaceTable.builtinType(stage, SurfaceTable.REG_TIME_PERIOD));
		}
		// Bake has no clock, so it has no period either -- a register readable at one and not the
		// other would be a trap in whichever direction it pointed.
		org.junit.Assert.assertNull("bake has no time, so it must have no timePeriod",
				SurfaceTable.builtinType(OcslWire.STAGE_BAKE, SurfaceTable.REG_TIME_PERIOD));

		// The exact program this test used to REJECT now validates: reading P instead of baking
		// 1/P into the constant pool is the whole point of the reservation.
		IrValidator.validate(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 2,
				new IrOp(OcslWire.OP_MUL, W, SurfaceTable.REG_TIME, SurfaceTable.REG_TIME_PERIOD),
				new IrOp(OcslWire.OP_SPLAT, W + 1, W, 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 1)));

		// Bake still refuses it, so the stage-applicability rule did not go slack.
		expectReject(prog(OcslWire.STAGE_BAKE, new float[] { 1.0f }, W + 2,
				new IrOp(OcslWire.OP_MUL, W, SurfaceTable.REG_TIME_PERIOD,
						SurfaceTable.REG_TIME_PERIOD),
				new IrOp(OcslWire.OP_SPLAT, W + 1, W, 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 1)), "does not have");
	}

	@Test
	public void refusesAStageThatIsNotOpen() {
		// The vertex stage is reserved for Stage C and refused by no codec tripwire. Without this,
		// a vertex program with no OUT at all validated as ACCEPTABLE -- "it validated" meaning
		// nothing, because nothing may run on that surface yet.
		//
		// ASSERTS ON OPENNESS, not on "no property table", and the rename is the point. The old
		// wording passed only because the message happened to end with that phrase, and it would
		// have turned RED on the exact Stage C workflow this gate exists to make safe: publish a
		// vertex property table while leaving the surface shut, and the refusal stops mentioning a
		// missing table while remaining entirely correct. A test named
		// `refusesAStageWithNoPropertyTable` going red at that moment reads as "don't publish the
		// table" or "open the stage" -- which is the trap that was just removed, relocated into the
		// suite.
		expectReject(prog(OcslWire.STAGE_VERTEX, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4)), "is not open");
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
		//
		// DERIVED by summing the stage's built-ins rather than listing them. The listed version
		// (uv + position + tint + time) broke the day `timePeriod` became readable -- which is a
		// test failing for a reason that has nothing to do with what it tests. What this pins is
		// the ORDER and the packing, not how many built-ins the material stage happens to have.
		int builtinWidth = 0;
		for (int reg = 0; reg < SurfaceTable.BUILTIN_LIMIT; reg++) {
			OcslType t = SurfaceTable.builtinType(OcslWire.STAGE_PIXEL_MATERIAL, reg);
			if (t != null) {
				builtinWidth += t.width;
			}
		}
		assertEquals("W+2 was written first", builtinWidth, v.frameOffset(W + 2));
		assertEquals("W came second", builtinWidth + 3, v.frameOffset(W));
		assertEquals("W+1 came third", builtinWidth + 4, v.frameOffset(W + 1));
		assertEquals(builtinWidth + 3 + 1 + 4, v.frameWidth);
	}

	// ---------------------------------------------------------------- the gate is self-sufficient
	//
	// EVERY TEST BELOW WAS A HOLE THE CODEC HAPPENED TO COVER. The design names validate() as the
	// boundary the VM trusts, but the loop invariants the VM's memory safety depends on were
	// enforced only in IrCodec.decode(). That was fine while every program arrived over the wire,
	// and stops being fine with the builder, which constructs an IrProgram and calls validate()
	// directly -- never passing through the decoder at all. Each of these accepted a program that
	// then crashed the VM, or crashed validate() itself with the wrong exception type.

	@Test
	public void refusesAnEndforThatClosesNoLoop() {
		// Threw ArrayIndexOutOfBoundsException: -1 from remove(size()-1) INSIDE validate(), so a
		// caller catching ValidationException saw it escape. IrProgram.structuralCount() guards
		// this exact case and saturates -- eighty lines further on, behind the crash.
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), "closes no loop");
	}

	@Test
	public void refusesALoopLeftOpen() {
		// Was caught only by structuralCount() saturating, which surfaced as "charges
		// 9223372036854775807 structural ops" -- true, and useless to whoever forgot the ENDFOR.
		// The OUT comes FIRST: with no ENDFOR everything after the FOR is inside the loop, and
		// OUT-inside-a-loop is refused for its own reason, which would have tested the wrong rule.
		expectReject(new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL,
				new float[][] { { 1.0f }, { 0f, 0f, 0f, 0f } },
				Arrays.asList(
						new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
						new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W),
						new IrOp(OcslWire.OP_FOR, W + 1, 2, k(1)),
						new IrOp(OcslWire.OP_ADD, W + 1, W + 1, W + 1)),
				new ArrayList<String>(), W + 2), "left open");
	}

	@Test
	public void refusesAnItofThatNamesALoopDepthNotOpenHere() {
		// ACCEPTED before, and then OcslVm computed loopCounter[depth - 1 - selector] and threw
		// ArrayIndexOutOfBoundsException: -5 on the render thread. The type rule was the whole
		// rule: `case OP_ITOF: return OcslType.FLOAT;`, with no look at the operand.
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 2,
				new IrOp(OcslWire.OP_ITOF, W + 1, 0),
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), "outside any loop");

		expectReject(new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL,
				new float[][] { { 1.0f }, { 0f, 0f, 0f, 0f } },
				Arrays.asList(
						new IrOp(OcslWire.OP_FOR, W + 1, 3, k(1)),
						new IrOp(OcslWire.OP_ITOF, W + 2, 5),
						new IrOp(OcslWire.OP_ENDFOR, -1),
						new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
						new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)),
				new ArrayList<String>(), W + 3), "selects loop depth 5");
	}

	@Test
	public void refusesALoopWhoseBodyChargesNothing() throws Exception {
		// THE WORK BOUND, and the reason it cannot be a cap on the op count. FOR/ENDFOR charge 0,
		// and that is CORRECT: the count is a post-unroll count and unrolled codegen emits nothing
		// for an empty body. Only the interpreter pays, one back-edge per iteration, so the one
		// number pricing the program is blind to the work by construction.
		expectReject(new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL,
				new float[][] { { 1.0f }, { 0f, 0f, 0f, 0f } },
				Arrays.asList(
						new IrOp(OcslWire.OP_FOR, W + 1, 256, k(1)),
						new IrOp(OcslWire.OP_ENDFOR, -1),
						new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
						new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)),
				new ArrayList<String>(), W + 2), "charges no op");

		// The measured attack: 2000 sequential pairs validated at ONE structural op against a cap
		// of 256, and ran 512,000 back-edges -- about 3 ms per evaluation, where a program charged
		// the entire 256-op budget runs in 118 us. Neither existing cap could see it:
		// MAX_UNROLL_PRODUCT is per-nesting-path by design, so sequential loops never accumulate.
		List<IrOp> many = new ArrayList<IrOp>();
		for (int i = 0; i < 2000; i++) {
			many.add(new IrOp(OcslWire.OP_FOR, W + 1, 256, k(1)));
			many.add(new IrOp(OcslWire.OP_ENDFOR, -1));
		}
		many.add(new IrOp(OcslWire.OP_SPLAT, W, k(0), 4));
		many.add(new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W));
		expectReject(new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL,
				new float[][] { { 1.0f }, { 0f, 0f, 0f, 0f } }, many, new ArrayList<String>(),
				W + 2), "charges no op");

		// A loop that computes something is untouched, including one whose only charged op is
		// nested deeper -- the rule is about the body, not about the immediate level.
		IrValidator.validate(new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL,
				new float[][] { { 1.0f }, { 0f, 0f, 0f, 0f } },
				Arrays.asList(
						new IrOp(OcslWire.OP_FOR, W + 1, 4, k(1)),
						new IrOp(OcslWire.OP_FOR, W + 2, 4, k(1)),
						new IrOp(OcslWire.OP_ADD, W + 1, W + 1, W + 2),
						new IrOp(OcslWire.OP_ENDFOR, -1),
						new IrOp(OcslWire.OP_ENDFOR, -1),
						new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
						new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)),
				new ArrayList<String>(), W + 3));
	}

	@Test
	public void refusesAWriteBeyondTheDeclaredRegisterCount() {
		// assign() checked only the LOW end, so dst=5000 in a 97-register program indexed past
		// `types` and threw AIOOBE out of validate().
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_SPLAT, 5000, k(0), 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), "outside the");
	}

	@Test
	public void refusesAnOpCarryingTheWrongOperandCount() {
		// One check that retires a whole class: every type rule below it indexes operands by the
		// arity it expects, so a short op reached IrOp.operand() and threw AIOOBE out of validate().
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_ADD, W),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), "operand");
	}

	@Test
	public void refusesANonFiniteConstant() {
		// The pool is an ingress. Every op is total and the VM sanitizes uniforms, but SPLAT,
		// SELECT, SWZ and the constructors COPY a constant into the frame and compute nothing the
		// catch-all could apply to -- so an Inf constant reached OUT as Inf. decode() refused such
		// a pool; encode() did not, so the program ran locally and produced a blob no peer accepts.
		expectReject(new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL,
				new float[][] { { Float.POSITIVE_INFINITY } },
				Arrays.asList(
						new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
						new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)),
				new ArrayList<String>(), W + 1), "non-finite");
	}

	@Test
	public void refusesLoopNestingDeeperThanTheWireCarries() {
		// validate() accepted 40-deep nesting, the VM ran it, encode() produced a blob -- and
		// decode() then refused it. A program this method certified that no peer can read.
		List<IrOp> ops = new ArrayList<IrOp>();
		int depth = OcslWire.MAX_LOOP_DEPTH + 1;
		for (int i = 0; i < depth; i++) {
			ops.add(new IrOp(OcslWire.OP_FOR, W + 1 + i, 1, k(1)));
		}
		ops.add(new IrOp(OcslWire.OP_ADD, W + 1, W + 1, W + 1));
		for (int i = 0; i < depth; i++) {
			ops.add(new IrOp(OcslWire.OP_ENDFOR, -1));
		}
		ops.add(new IrOp(OcslWire.OP_SPLAT, W, k(0), 4));
		ops.add(new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W));
		expectReject(new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL,
				new float[][] { { 1.0f }, { 0f, 0f, 0f, 0f } }, ops, new ArrayList<String>(),
				W + 2 + depth), "depth");
	}

	@Test
	public void refusesANegativeSamplerSlot() {
		// Only the high end was bounded. Harmless in the CPU VM, which ignores the slot; not
		// harmless for codegen, which binds by it.
		expectReject(prog(OcslWire.STAGE_PIXEL_EFFECT, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_SAMPLE, W, -1, SurfaceTable.REG_UV),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), "outside the");
	}

	// ---------------------------------------------------------------- the second sweep
	//
	// Everything below was found by ADVERSARIALLY VERIFYING THE FIRST ROUND OF FIXES rather than by
	// reviewing the code again, and the pattern is worth naming: each first-round fix closed one
	// SIDE of a symmetric rule and left the other open. The write index was bounded and the
	// constant-pool read index was not; constants were checked for finiteness and not for width;
	// op operands were range-checked on encode and the four header counts were not. The rules now
	// live once, in IrStructure, called by validate() and encode() alike -- so the remaining risk
	// is a missing RULE rather than a rule enforced in only one of three places.

	@Test
	public void refusesAConstantOperandOutsideThePool() {
		// The mirror image of the register fix, and it threw ArrayIndexOutOfBoundsException out of
		// validate() from IrProgram.constantType. Note the second case: a raw operand of -1 has the
		// sign bit set, so OPERAND_CONST_FLAG reads as present and index() yields 32767 -- which is
		// why IrStructure checks the raw operand before believing its tag.
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_SPLAT, W, k(9), 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), "outside the pool");
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_ADD, W, -1, k(0)),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), "outside the unsigned 16");
	}

	@Test
	public void refusesAConstantWhoseWidthIsNotAType() {
		// Width IS the type tag, so 0 or 5 has no type -- OcslType.ofWidth returns null. That null
		// was dereferenced at seven sites for an NPE out of validate(), and, worse, read as
		// "contributes no shape" at three others, so the program was ACCEPTED and then either threw
		// inside the VM or returned a wrong number: a 5-wide DOT reported 12.0 where the honest
		// answer is 26.0, because scratchInto wrote five floats into a four-float lane.
		expectReject(new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL,
				new float[][] { new float[0], { 1f, 1f, 1f, 1f } },
				Arrays.asList(
						new IrOp(OcslWire.OP_ADD, W, k(0), k(1)),
						new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)),
				new ArrayList<String>(), W + 1), "1..4 only");
		expectReject(new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL,
				new float[][] { { 1f, 1f, 1f, 1f, 9f }, { 2f, 2f, 2f, 2f, 2f } },
				Arrays.asList(
						new IrOp(OcslWire.OP_DOT, W, k(0), k(1)),
						new IrOp(OcslWire.OP_SPLAT, W + 1, W, 4),
						new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 1)),
				new ArrayList<String>(), W + 2), "1..4 only");
	}

	@Test
	public void refusesANegativeRegisterCount() {
		// `new OcslType[regCount]` threw NegativeArraySizeException, because the cap check was
		// one-sided. That one-sidedness is this package's recurring bug: the same shape was fixed
		// in assign(), typeOf(), frameOffset() and the sampler slot in the first round, and this
		// instance survived all four.
		expectReject(new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL,
				new float[][] { { 1f, 1f, 1f, 1f } },
				Arrays.asList(new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, k(0))),
				new ArrayList<String>(), -3), "declares -3 registers");
	}

	@Test
	public void refusesWhatTheDecoderWouldRefuse() {
		// A6's corollary, tested as ONE property rather than as a list of instances. Each of these
		// validated, ran on the VM and encoded cleanly, and was then refused by decode() -- so the
		// author saw a working program and every peer saw a corrupt one. That is exactly the
		// failure the constant-finiteness fix was written for, surviving in four other fields.
		float[] one = { 1.0f };

		// A pool larger than the wire's cap.
		float[] big = new float[OcslWire.MAX_CONSTANTS + 1];
		Arrays.fill(big, 1.0f);
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, big, W + 1,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), "exceeds the cap");

		// A non-canonical swizzle: length 1, but a component set beyond it. Canonical form IS the
		// content hash, so two spellings of `.x` would fork the compile-cache key for one program.
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, one, W + 2,
				new IrOp(OcslWire.OP_SWZ, W, k(0), 1 << 2),
				new IrOp(OcslWire.OP_SPLAT, W + 1, W, 4),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 1)), "canonical form");

		// Uniform names the decoder's charset refuses. setUniform("my name", ...) would otherwise
		// build a program that works on its author's client and nowhere else.
		for (String bad : new String[] { "", "1abc", "a-b", "my name" }) {
			expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, one, Arrays.asList(bad), W + 1,
					new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
					new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)), "name 0");
		}

		// More ops than the wire carries.
		List<IrOp> many = new ArrayList<IrOp>();
		for (int i = 0; i < OcslWire.MAX_OPS + 1; i++) {
			many.add(new IrOp(OcslWire.OP_SPLAT, W, k(0), 4));
		}
		many.add(new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W));
		expectReject(new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL, new float[][] { { 1f } }, many,
				new ArrayList<String>(), W + 1), "op count");
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

	@Test
	public void theReservedAnimatorPropertiesHaveNamesForTheRefusalToUse() throws Exception {
		// ANIM-15(c). SurfaceTable's javadoc promises that z and visible "are refused at attach with
		// a documented error ... so the refusal can name them", and the OUT refusal interpolated the
		// raw int -- owning `visible` came back as "stage 6 has no property 8".
		//
		// THE MESSAGE ITSELF CANNOT BE VECTORED YET, and a mutation sweep proved it rather than my
		// assuming it: validate() refuses any stage that is not open BEFORE it reaches the OUT check,
		// so the name-bearing branch is unreachable while the animator is shut. Removing the name
		// from the message fails nothing. What is pinnable now is the DATA the message will use, and
		// that these two ids are typeless while being named -- which is the whole reason the raw int
		// was uninformative.
		assertEquals("visible", SurfaceTable.propertyName(OcslWire.STAGE_ANIMATOR,
				OcslWire.PROP_ANIM_VISIBLE));
		assertEquals("z", SurfaceTable.propertyName(OcslWire.STAGE_ANIMATOR, OcslWire.PROP_ANIM_Z));
		assertNull("and they are typeless, which is what makes them unownable",
				SurfaceTable.propertyType(OcslWire.STAGE_ANIMATOR, OcslWire.PROP_ANIM_VISIBLE));
		assertNull(SurfaceTable.propertyType(OcslWire.STAGE_ANIMATOR, OcslWire.PROP_ANIM_Z));

		// Non-vacuity: an OWNABLE animator property is both named and typed, so the two assertions
		// above are not just "everything is null".
		assertEquals("x", SurfaceTable.propertyName(OcslWire.STAGE_ANIMATOR, OcslWire.PROP_ANIM_X));
		assertNotNull(SurfaceTable.propertyType(OcslWire.STAGE_ANIMATOR, OcslWire.PROP_ANIM_X));

		// THE OBLIGATION, enforced. When the animator surface opens, the refusal message becomes
		// reachable and must get a vector asserting it contains "visible" rather than "8".
		assertFalse("The animator surface is now OPEN. IrValidator's OUT refusal is reachable at that"
				+ " stage: write the message vector asserting the property is NAMED, and delete this"
				+ " assertion.", SurfaceTable.isOpen(OcslWire.STAGE_ANIMATOR));
	}
}
