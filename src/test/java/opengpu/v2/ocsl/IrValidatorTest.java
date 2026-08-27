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

	// ---------------------------------------------------------------- ANIM-7: OUT_ABS's gate

	@Test
	public void theAbsoluteOutputFormIsRefusedWhereOutputsDoNotComposeOverABase() throws Exception {
		// THE PAIR IS THE POINT. Each stage validates the SAME program written with OP_OUT first,
		// so the refusal that follows is about the FORM and nothing else. Without that control half
		// this test passes for any reason at all -- an unrecognised opcode would satisfy it just as
		// well as the gate, and the gate is what is being claimed.
		int exercised = 0;
		for (int s = 0; s <= 255; s++) {
			byte stage = (byte) s;
			if (!OcslWire.isKnownStage(stage) || SurfaceTable.composesOutputs(stage)
					|| !SurfaceTable.isOpen(stage)) {
				continue;
			}
			IrProgram relative = prog(stage, new float[] { 1.0f }, W + 1,
					new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
					new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W));
			IrValidator.validate(relative);

			IrProgram absolute = prog(stage, new float[] { 1.0f }, W + 1,
					new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
					new IrOp(OcslWire.OP_OUT_ABS, -1, OcslWire.PROP_COLOR, W));
			expectReject(absolute, "OUT_ABS");
			exercised++;
		}
		// The vacuity guard: a loop that skipped every stage would otherwise pass silently.
		assertTrue("expected the open non-composing stages to be exercised, got " + exercised,
				exercised >= 4);
	}

	@Test
	public void theFormIsRefusedBeforeTheProperty_soAnAuthorLearnsTheRealError() throws Exception {
		// THE DISCRIMINATING INPUT, and the first attempt at this test did not have it. That version
		// re-validated a program naming PROP_COLOR and asserted the message did not say "has no
		// property" -- but PROP_COLOR is valid at every stage the sweep reaches, so that branch was
		// unreachable for the input and the assertion excluded nothing under EITHER ordering.
		//
		// A program that is wrong in BOTH ways separates them. IrValidator checks the form before
		// the property lookup, so this must name the form; moving the gate below the lookup makes it
		// say "stage 1 has no property 99" and lead the author to fix the wrong thing -- OUT_ABS is
		// not legal here whatever property it names.
		IrProgram bothWrong = prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_OUT_ABS, -1, 99, W));
		// PREMISES, asserted rather than assumed. When this test failed on a message it should not
		// have been able to produce, these were what separated "the gate moved" from "the input is
		// not what I think it is" -- and reading the source had already failed to settle it.
		assertTrue("premise: the material stage does not compose",
				!SurfaceTable.composesOutputs(OcslWire.STAGE_PIXEL_MATERIAL));
		assertEquals("premise: the op carries the absolute form",
				OcslWire.OP_OUT_ABS, bothWrong.ops().get(1).opcode);
		assertTrue("premise: property 99 is absent at this stage",
				SurfaceTable.propertyType(OcslWire.STAGE_PIXEL_MATERIAL, 99) == null);
		expectReject(bothWrong, "OUT_ABS");
		try {
			IrValidator.validate(bothWrong);
			fail("unreachable");
		} catch (ValidationException e) {
			assertTrue("the form is the more fundamental error and must be reported, got: "
					+ e.getMessage(), !e.getMessage().contains("has no property"));
		}

		// The control that keeps the pair honest: with the FORM right and only the property wrong,
		// the property error is exactly what must come back. Without this, "always say OUT_ABS"
		// would pass the assertion above.
		IrProgram onlyPropertyWrong = prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_OUT, -1, 99, W));
		expectReject(onlyPropertyWrong, "has no property 99");
	}

	// ------------------------------------------------- ANIM-7: the relative form forfeits the read

	/** OUT x, ADD(anim.x, k) — the defect itself. */
	private static IrProgram relativeSelfRead(int property, int reg) {
		return prog(OcslWire.STAGE_ANIMATOR, new float[] { 0.5f }, W + 1,
				new IrOp(OcslWire.OP_ADD, W, reg, k(0)),
				new IrOp(OcslWire.OP_OUT, -1, property, W));
	}

	@Test
	public void aRelativeWriteMayNotReadItsOwnBase() throws Exception {
		expectReject(relativeSelfRead(OcslWire.PROP_ANIM_X, SurfaceTable.REG_ANIM_X), "twice");
		// Every relatively-composed property, not just the one that is easy to think about. sx is
		// the probe ANIM-3 uses for the same reason: its identity is 1, not 0.
		expectReject(relativeSelfRead(OcslWire.PROP_ANIM_SX, SurfaceTable.REG_ANIM_SX), "twice");
		expectReject(relativeSelfRead(OcslWire.PROP_ANIM_ROT2D, SurfaceTable.REG_ANIM_ROT2D),
				"twice");

		// tz is property 9 at register 23 -- the pair the naive `base + id` map gets wrong.
		expectReject(relativeSelfRead(OcslWire.PROP_ANIM_TZ, SurfaceTable.REG_ANIM_TZ), "twice");

		// And the message names the form and the remedy rather than claiming the stage lacks the
		// register, which it has. e5ea97c is the precedent for a refusal that asserted something
		// false about the table.
		try {
			IrValidator.validate(relativeSelfRead(OcslWire.PROP_ANIM_X, SurfaceTable.REG_ANIM_X));
			fail("unreachable");
		} catch (ValidationException e) {
			String m = e.getMessage();
			assertTrue("names the remedy: " + m, m.contains("OUT_ABS"));
			assertTrue("must not claim the surface lacks the register: " + m,
					!m.contains("does not have") && !m.contains("has no built-in"));
		}
	}

	@Test
	public void theReadRuleIsAboutTheFormAndTheProperty_notAboutReadingAtAll() throws Exception {
		// THE FOUR EXEMPTIONS, each of which a blunter rule would break.

		// 1. OUT_ABS may read its own base -- this is the idiom the opcode exists for. Snap-to-grid,
		//    clamp-to-bounds and ease-to-target all have this shape and are all correct.
		IrValidator.validate(prog(OcslWire.STAGE_ANIMATOR, new float[] { 0.5f }, W + 1,
				new IrOp(OcslWire.OP_ADD, W, SurfaceTable.REG_ANIM_X, k(0)),
				new IrOp(OcslWire.OP_OUT_ABS, -1, OcslWire.PROP_ANIM_X, W)));

		// 2. Reading a property this program does NOT write is nothing to do with the rule: tilt in
		//    proportion to how far right I am.
		IrValidator.validate(prog(OcslWire.STAGE_ANIMATOR, new float[] { 0.01f }, W + 1,
				new IrOp(OcslWire.OP_MUL, W, SurfaceTable.REG_ANIM_X, k(0)),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_ANIM_ROT2D, W)));

		// 3. tint REPLACES, so the base never enters the result and doubling is impossible. This is
		//    the read ANIM-21's lint requires a tint animator to perform.
		IrValidator.validate(prog(OcslWire.STAGE_ANIMATOR, new float[] { 0.5f }, W + 1,
				new IrOp(OcslWire.OP_MUL, W, SurfaceTable.REG_ANIM_TINT, k(0)),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_ANIM_TINT, W)));

		// 4. The PARENT's copy of the same property is a different register and a different node;
		//    no OUT can name a parent property, so the rule structurally cannot apply to it.
		//
		//    THIS ONE IS A BOUNDARY PIN, NOT AN EXEMPTION, and saying so is the honest version: no
		//    production line exempts the parent block. It falls outside the window the validator
		//    collects reads over, [REG_ANIMATOR_BASE, REG_ANIM_OWN_LIMIT) = [16, 25), so the rule
		//    cannot reach it however it is written. A review pointed out that asserting this alone
		//    is a tautology — nothing could make it fail — so it is paired below with the register
		//    on the other side of the same edge.
		IrValidator.validate(prog(OcslWire.STAGE_ANIMATOR, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_ADD, W, SurfaceTable.REG_ANIM_PARENT_X, k(0)),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_ANIM_X, W)));
	}

	@Test
	public void theOwnReadRegisterMapIsNotArithmetic() throws Exception {
		// THE MAP IS THE BOUNDARY, so it gets pinned directly rather than only through the programs
		// that consume it — and pinned with the WRONG answer asserted, because the wrong answer here
		// is not a crash. `REG_ANIMATOR_BASE + propertyId` resolves for all nine properties and is
		// silently wrong for two of them.
		int[] props = { OcslWire.PROP_ANIM_X, OcslWire.PROP_ANIM_Y, OcslWire.PROP_ANIM_SX,
				OcslWire.PROP_ANIM_SY, OcslWire.PROP_ANIM_ROT2D, OcslWire.PROP_ANIM_ROT3D,
				OcslWire.PROP_ANIM_TINT, OcslWire.PROP_ANIM_TZ, OcslWire.PROP_ANIM_SZ };
		int[] regs = { SurfaceTable.REG_ANIM_X, SurfaceTable.REG_ANIM_Y, SurfaceTable.REG_ANIM_SX,
				SurfaceTable.REG_ANIM_SY, SurfaceTable.REG_ANIM_ROT2D, SurfaceTable.REG_ANIM_ROT3D,
				SurfaceTable.REG_ANIM_TINT, SurfaceTable.REG_ANIM_TZ, SurfaceTable.REG_ANIM_SZ };
		int divergences = 0;
		for (int i = 0; i < props.length; i++) {
			assertEquals("property " + props[i] + " reads its own value from register " + regs[i],
					regs[i], SurfaceTable.animatorReadRegister(props[i]));
			if (SurfaceTable.REG_ANIMATOR_BASE + props[i] != regs[i]) {
				divergences++;
			}
		}
		// tz (property 9 -> register 23) and sz (10 -> 24): z and visible hold ids with no
		// registers, so everything above them is offset by two. If this ever reads 0 the arithmetic
		// version has become correct and the assertions above stopped excluding anything.
		assertEquals("exactly two properties diverge from base+id, and they are tz and sz",
				2, divergences);
		assertEquals(23, SurfaceTable.animatorReadRegister(OcslWire.PROP_ANIM_TZ));
		assertEquals(24, SurfaceTable.animatorReadRegister(OcslWire.PROP_ANIM_SZ));
		assertTrue("base+id would land tz on nodeSeed",
				SurfaceTable.REG_ANIMATOR_BASE + OcslWire.PROP_ANIM_TZ
						== SurfaceTable.REG_ANIM_NODE_SEED);
		assertTrue("base+id would land sz on sinceAttach",
				SurfaceTable.REG_ANIMATOR_BASE + OcslWire.PROP_ANIM_SZ
						== SurfaceTable.REG_ANIM_SINCE_ATTACH);

		// And the domain: everything unownable or unallocated has no read register at all.
		assertEquals(-1, SurfaceTable.animatorReadRegister(OcslWire.PROP_ANIM_Z));
		assertEquals(-1, SurfaceTable.animatorReadRegister(OcslWire.PROP_ANIM_VISIBLE));
		assertEquals(-1, SurfaceTable.animatorReadRegister(60));
		assertEquals(-1, SurfaceTable.animatorReadRegister(-1));
	}

	@Test
	public void theReadWindowEndsExactlyAtTheNodesOwnBlock() throws Exception {
		// The edge itself, from both sides, so widening or narrowing the window fails something.
		// 24 (sz) is the last of the node's own property reads and 25 (nodeSeed) is the first
		// register past them; the window is [16, 25).
		assertEquals("the window's exclusive end", 25, SurfaceTable.REG_ANIM_OWN_LIMIT);

		// INSIDE: sz is the highest own register, and owning sz relatively forfeits it.
		expectReject(relativeSelfRead(OcslWire.PROP_ANIM_SZ, SurfaceTable.REG_ANIM_SZ), "twice");

		// OUTSIDE, one register up: nodeSeed is not a property read at all, and no OUT can name it,
		// so a program owning anything may read it.
		//
		// WHAT ACTUALLY PINS THIS EDGE is SurfaceTable.animatorReadRegister, NOT the width of the
		// validator's collection window — a mutation sweep widening that window to the whole
		// animator block SURVIVED, and an earlier version of this comment claimed it would fail.
		// It cannot: the rule only ever looks the window up at a property's OWN register, which the
		// map keeps inside [16, 25), so the extra slots are written and never read. The window is a
		// locality choice; the map is the boundary, and theOwnReadRegisterMapIsNotArithmetic pins
		// it directly.
		IrValidator.validate(prog(OcslWire.STAGE_ANIMATOR, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_MUL, W, SurfaceTable.REG_ANIM_NODE_SEED, k(0)),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_ANIM_SZ, W)));
		IrValidator.validate(prog(OcslWire.STAGE_ANIMATOR, new float[] { 1.0f }, W + 1,
				new IrOp(OcslWire.OP_MUL, W, SurfaceTable.REG_ANIM_SINCE_ATTACH, k(0)),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_ANIM_X, W)));
	}

	@Test
	public void theReadIsFoundWhereverItSitsRelativeToTheWrite() throws Exception {
		// The reason this is a post-loop check and not a check at the read site. Validate() makes
		// ONE pass, so when a read is type-checked the OUT that forbids it may not have been seen.
		// Both orders must refuse, or the rule holds only for programs that write last.
		expectReject(prog(OcslWire.STAGE_ANIMATOR, new float[] { 0.5f }, W + 2,
				new IrOp(OcslWire.OP_ADD, W, SurfaceTable.REG_ANIM_X, k(0)),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_ANIM_X, W)), "twice");

		// Read AFTER the write: the OUT's value comes from a constant, and the read feeds a second,
		// later property. The x-write is still relative and the x-read still happens.
		expectReject(prog(OcslWire.STAGE_ANIMATOR, new float[] { 0.5f }, W + 2,
				new IrOp(OcslWire.OP_ADD, W, k(0), k(0)),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_ANIM_X, W),
				new IrOp(OcslWire.OP_MUL, W + 1, SurfaceTable.REG_ANIM_X, k(0)),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_ANIM_Y, W + 1)), "twice");

		// THE OUT'S OWN OPERAND is a read too, and it is the purest form of the defect: a
		// pass-through `OUT x, anim.x` displays 2*x_srv. A rule hung off the arithmetic ops alone
		// would walk past it, since there is no arithmetic here at all.
		expectReject(prog(OcslWire.STAGE_ANIMATOR, new float[] { 0.5f }, W + 1,
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_ANIM_X, SurfaceTable.REG_ANIM_X)),
				"twice");
	}

	@Test
	public void exactlyOneKnownStageComposesItsOutputsOverAServerBase() throws Exception {
		// composesOutputs is what OUT_ABS is gated on, so it needs its own pin: if it ever answered
		// true for a pixel stage, the gate above would still pass (that stage would simply stop
		// being exercised) while the surface quietly gained a second spelling for one behaviour.
		int composing = 0;
		for (int s = 0; s <= 255; s++) {
			byte stage = (byte) s;
			if (!OcslWire.isKnownStage(stage)) {
				assertTrue("an unknown stage cannot compose", !SurfaceTable.composesOutputs(stage));
				continue;
			}
			if (SurfaceTable.composesOutputs(stage)) {
				assertEquals("the animator is the only composing surface",
						OcslWire.STAGE_ANIMATOR, stage);
				composing++;
			}
		}
		assertEquals(1, composing);
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
		for (int i = 0; i < IrValidator.maxStructuralOps(OcslWire.STAGE_PIXEL_MATERIAL); i++) {
			ops.add(new IrOp(OcslWire.OP_ABS, W, W));
		}
		ops.add(new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W));
		// The message must name the STAGE whose budget was crossed, and the stage id is derived
		// rather than written as "1" so this keeps discriminating if the constant ever moves.
		expectReject(new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, ops,
				new ArrayList<String>(), W + 1),
				"over stage " + OcslWire.STAGE_PIXEL_MATERIAL + "'s cap");
	}

	/**
	 * The SAME refusal at a SECOND stage — the panel found that with only the stage-1 assertion,
	 * a validator emitting a hardcoded "stage 1" for every refusal passed the whole suite. Two
	 * stages with distinct ids are the minimum that pins the message's stage as DERIVED.
	 */
	@Test
	public void theOpCapRefusalNamesTheStageThatWasActuallyCrossed() {
		List<IrOp> ops = new ArrayList<IrOp>();
		ops.add(new IrOp(OcslWire.OP_SPLAT, W, k(0), 4));
		for (int i = 0; i < IrValidator.maxStructuralOps(OcslWire.STAGE_PIXEL_POST); i++) {
			ops.add(new IrOp(OcslWire.OP_ABS, W, W));
		}
		ops.add(new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W));
		expectReject(new IrProgram(OcslWire.STAGE_PIXEL_POST, new float[] { 1.0f }, ops,
				new ArrayList<String>(), W + 1),
				"over stage " + OcslWire.STAGE_PIXEL_POST + "'s cap");
	}

	/**
	 * THE RANGE INVARIANT for the per-stage op caps — a tripwire, and deliberately one that
	 * cannot fire today.
	 *
	 * Both bounds exist for a reason the caps' own history supplies. The CEILING: the codecs bound
	 * a wire/disk {@code structuralOps} field by {@code MAX_STRUCTURAL_OPS}, so a stage cap raised
	 * above it would have the validator accept programs the decoders then refuse — an acceptance
	 * change leaking into format identity. The FLOOR: "raiseable, never tightenable" is only
	 * meaningful if something says how far down is too far, and the answer is the conformance
	 * suite — the four acceptance programs charge up to 102 (P2, 101 + the prologue), so a cap
	 * below that refuses a program the suite requires to pass. Without this, "lower it" is
	 * unbounded and the first sign would be acceptance tests failing for a reason nothing names.
	 *
	 * NOTE, increment M (2026-08-27): the ceiling arm's admissible band widened deliberately
	 * from [102, 1024] to [102, 8192] when the ceiling moved. That is 8x looser, and 16x the
	 * largest real cap (512) — so this test alone would no longer notice a per-stage cap
	 * mistyped anywhere in (1024, 8192]. What still catches that is the exact per-stage
	 * assertEquals set in theStageCapsCarryTheDecidedDivergence, and what bounds the CEILING
	 * itself from beneath is theCeilingStaysAboveTheShapesItsMagnitudeWasChosenFor. The three
	 * are one guard in three parts; do not read any of them alone.
	 */
	@Test
	public void everyStagesOpCapSitsBetweenTheConformanceFloorAndTheCodecCeiling() {
		final int conformanceFloor = 102;   // P2, the largest acceptance program, + its prologue
		for (byte stage = OcslWire.STAGE_PIXEL_MATERIAL; stage <= OcslWire.STAGE_COMPUTE; stage++) {
			int cap = IrValidator.maxStructuralOps(stage);
			assertTrue("stage " + stage + "'s op cap " + cap + " exceeds MAX_STRUCTURAL_OPS ("
					+ IrValidator.MAX_STRUCTURAL_OPS + "), which the codecs bound by — the"
					+ " validator would accept what BatchCodec and SnapshotCodec then reject",
					cap <= IrValidator.MAX_STRUCTURAL_OPS);
			assertTrue("stage " + stage + "'s op cap " + cap + " is below the conformance floor of "
					+ conformanceFloor + "; the acceptance programs no longer fit",
					cap >= conformanceFloor);
		}
	}

	/**
	 * THE CEILING'S FLOOR — the half of the guard that increment M's raise did not widen.
	 *
	 * The project's cap rule is "write the lower bound as a test, or 'lower it' is unbounded".
	 * Before M the ceiling's only lower bound was the exact assertEquals in
	 * theStageCapsCarryTheDecidedDivergence, which pins the VALUE but moves with it by that
	 * test's own instructions — no bound with a REASON stood beneath it. Arm (b) is that reason.
	 *
	 * Arm (a) deliberately RESTATES the range invariant's ceiling arm (max(caps) <= X is the same
	 * predicate as the per-stage loop above it); it fires only below 512 and is the loosest guard
	 * in the set, kept because it reads as one statement rather than a loop. Arm (b) is the floor
	 * the magnitude was chosen against, written as visible arithmetic so the ladder is readable
	 * here rather than as an opaque 1924: CapIntuitionTest MEASURES one raymarch step at 28
	 * structural ops and adds 4 further SDF evaluations plus ~20 for shading. If either moves,
	 * PLAN-STAGE-C D7's derivation moved with it and needs re-deriving rather than patching.
	 */
	@Test
	public void theCeilingStaysAboveTheShapesItsMagnitudeWasChosenFor() {
		int widest = 0;
		for (byte stage = OcslWire.STAGE_PIXEL_MATERIAL; stage <= OcslWire.STAGE_COMPUTE; stage++) {
			widest = Math.max(widest, IrValidator.maxStructuralOps(stage));
		}
		assertTrue("the ceiling (" + IrValidator.MAX_STRUCTURAL_OPS + ") must stay at or above"
				+ " every per-stage cap (widest " + widest + "), or the validator accepts what"
				+ " BatchCodec and SnapshotCodec then refuse",
				widest <= IrValidator.MAX_STRUCTURAL_OPS);

		final int raymarch64 = 64 * 28 + 4 * 28 + 20;   // = 1924, CapIntuitionTest's ladder
		assertTrue("the ceiling was sized to clear a 64-step raymarch (" + raymarch64 + " ops,"
				+ " PLAN-STAGE-C D7); below this NO per-stage cap could ever be raised far enough"
				+ " to admit that shape, since the range invariant forbids a stage cap above the"
				+ " ceiling — it fits at no stage TODAY (pixel 256, animator 512), which is the"
				+ " point: this is headroom for a future stage cap, not a claim about now",
				IrValidator.MAX_STRUCTURAL_OPS >= raymarch64);
	}

	/**
	 * THE ARM INCREMENT M MADE DEAD — a single over-trip FOR, now refused only by IrStructure.
	 *
	 * Before M the unroll cap (1024) sat BELOW the wire's trip bound (4096), so a single loop of
	 * 1025..4096 trips died at the unroll cap and IrStructure's check was redundant
	 * defence-in-depth. At 8192 the ordering inverts: one FOR contributes at most 4096, which no
	 * longer reaches the cap, so tripping it needs nesting depth >= 2 and the single-loop case
	 * passes to IrStructure alone. That is a previously-constant condition going live, and the
	 * project's vacuous-bounds rule says the newly-taken branch is audited as new code.
	 *
	 * The EXCLUSION arm is what makes this non-vacuous: a FOR of exactly MAX_LOOP_TRIPS must NOT
	 * be refused for its trip count. It walks past IrStructure, past the unroll cap, and dies at
	 * the per-stage op cap instead — so a mutation of IrStructure's `>` to `>=` fails here.
	 *
	 * The fragment it matches on is the FULL "over stage N's cap of", not the bare "cap of" this
	 * arm shipped with for one panel round: expectReject is a lowercased substring match, and
	 * "cap of" also matches the unroll cap's own "exceeds the cap of" — the single outcome this
	 * arm exists to exclude. A rejection fragment has to be unique to its throw site or it is a
	 * coin flip wearing a test's name.
	 */
	@Test
	public void aSingleOverTripLoopIsRefusedByTheStructuralBoundNotTheUnrollCap() throws Exception {
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 2,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_FOR, W + 1, OcslWire.MAX_LOOP_TRIPS + 1, k(0)),
				new IrOp(OcslWire.OP_ADD, W + 1, W + 1, k(0)),
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)),
				"over the structural bound of");

		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 2,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_FOR, W + 1, OcslWire.MAX_LOOP_TRIPS, k(0)),
				new IrOp(OcslWire.OP_ADD, W + 1, W + 1, k(0)),
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)),
				"over stage " + OcslWire.STAGE_PIXEL_MATERIAL + "'s cap of");
	}

	/**
	 * THE DECIDED DIVERGENCE — 2026-08-21, the user's cap-raise decision, pinned stage by stage.
	 *
	 * This test's predecessor pinned "every stage at the ceiling" and carried instructions to be
	 * updated deliberately when a stage diverged; this is that update. The expectations are the
	 * decision record: the ANIMATOR at 512 because it is the one stage with a measured cost
	 * model (0.46 us/op; ~6-9 us/node in the field); everything else at 256 because PLAN's gate
	 * — the pixel stages need a GLSL-side measurement nobody has taken — is unmet; and the
	 * CEILING at 8192 so that every future per-stage move up to it is free acceptance policy,
	 * the format-adjacent codec event having been paid once. A stage moving off these numbers
	 * must move this test with it, with the reason.
	 *
	 * The ceiling moved 1024 → 8192 at increment M (2026-08-27), and its reason is the MAGNITUDE
	 * derivation the 2026-08-21 raise never recorded: sized against the shapes a ceiling gets
	 * asked for, at CapIntuitionTest's MEASURED 28 ops per raymarch step plus 132 for normal and
	 * shading — 64 steps = 1924, cleared by 6,268 — and the compute sketch's all-pairs estimate
	 * of 4,596, cleared by 3,596. Stated with its own caveat: the raymarch half is exact and
	 * argues for 2048; 8192-over-2048 rests on amortization. No per-stage cap moved with it, so
	 * nothing refused before M is accepted after it.
	 */
	@Test
	public void theStageCapsCarryTheDecidedDivergence() {
		assertEquals("the ceiling — the codec bound, paid once; 1024 → 8192 at M (2026-08-27)"
				+ " sized on the raymarch ladder (64 steps = 1924) and the all-pairs estimate"
				+ " (4,596), both cleared", 8192,
				IrValidator.MAX_STRUCTURAL_OPS);
		assertEquals("the animator's measured raise", 512,
				IrValidator.maxStructuralOps(OcslWire.STAGE_ANIMATOR));
		for (byte stage = OcslWire.STAGE_PIXEL_MATERIAL; stage <= OcslWire.STAGE_COMPUTE; stage++) {
			if (stage == OcslWire.STAGE_ANIMATOR) {
				continue;
			}
			assertEquals("stage " + stage + " awaits the GLSL-side measurement; a diverging"
					+ " value here needs that data and this test updated with it",
					256, IrValidator.maxStructuralOps(stage));
		}
		assertEquals("unroll stays lockstep with the ceiling, or thin loops become second-class",
				IrValidator.MAX_STRUCTURAL_OPS, IrValidator.MAX_UNROLL_PRODUCT);
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
		// BOTH arms derive from the constant. The sequential arm's stated target is the
		// accumulating-product bug, which it can only see if trips*trips EXCEEDS the cap -- and
		// the old hand-typed 20+20 stopped doing that at the 2026-08-21 raise, so this arm had
		// been dead for two raises while staying green. It was still hand-typed when the NESTED
		// arm below was re-derived; a fixture whose siblings disagree about where their numbers
		// come from is the shape that hides this.
		final int trips = (int) Math.ceil(Math.sqrt(IrValidator.MAX_UNROLL_PRODUCT + 1.0));
		assertTrue("a square nest of " + trips + " must exceed the unroll cap, or NEITHER arm of"
				+ " this test can see an accumulating product",
				(long) trips * trips > IrValidator.MAX_UNROLL_PRODUCT);
		assertTrue("and must stay inside the wire's trip bound, or IrStructure refuses it first"
				+ " and this test stops testing the unroll cap",
				trips <= OcslWire.MAX_LOOP_TRIPS);
		final long expected = 2L * trips + 2;   // splat + trips + trips + OUT
		assertTrue("the SEQUENTIAL pair must still fit under the pixel op cap, or the wrong rule"
				+ " refuses it and this arm tests nothing",
				expected <= IrValidator.maxStructuralOps(OcslWire.STAGE_PIXEL_MATERIAL));

		IrProgram p = prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 3,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_FOR, W + 1, trips, k(0)),
				new IrOp(OcslWire.OP_ADD, W + 1, W + 1, k(0)),
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_FOR, W + 2, trips, k(0)),
				new IrOp(OcslWire.OP_ADD, W + 2, W + 2, k(0)),
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W));
		assertEquals("2 x " + trips + " body ops + splat + OUT", expected,
				IrValidator.validate(p).structuralOps);

		// Nesting still multiplies, which is the half that must keep working. This fixture has
		// now been re-derived TWICE for a ceiling raise -- 20x20=400 tripped the 256 cap; 40x40
		// =1600 was needed at 1024; neither trips 8192 -- so it is derived FROM the constant
		// rather than hand-typed, and the next raise moves it for free. Each raise made the
		// previous fixture walk clean and die at the pixel op cap instead, which is the WRONG
		// refusal for this test's claim, and the failure is behavioural rather than a stale
		// literal: expectReject matches on the message.
		//
		// Same `trips`, hoisted above: the two arms must agree about where their numbers come
		// from, which is the defect this pair shipped with.
		expectReject(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 3,
				new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
				new IrOp(OcslWire.OP_FOR, W + 1, trips, k(0)),
				new IrOp(OcslWire.OP_FOR, W + 2, trips, k(0)),
				new IrOp(OcslWire.OP_ADD, W + 2, W + 2, k(0)),
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)),
				"unroll product " + ((long) trips * trips));
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
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)),
				// "registers, over the cap of", not the bare "over the cap of" this shipped with:
				// expectReject is a substring match and that fragment also names the constant pool
				// and the FRAME-WIDTH cap -- which is what the second half of this very test
				// targets, so the two halves could have swapped answers undetected. Same defect,
				// same file, as the one-sided-fixes rule predicts.
				"registers, over the cap of");

		// The frame cap has to be tripped INSIDE some stage's op cap or the op cap answers
		// first. RE-DERIVED TWICE for the 2026-08-21 raise — the first re-derivation claimed
		// "the pixel stages can no longer reach the frame cap at all", reasoning only from
		// SPLAT chains (255 vec4 + the 10-float material prologue = 1030, inside 2048), and the
		// review panel refuted it with the shape SurfaceTable's own widening note documents:
		// FOR allocates a register and charges ZERO, so FOR(1, vec4-const)/ADD/ENDFOR triples
		// lay out 8 frame floats per charged op — 255 triples + OUT charge exactly 256 and lay
		// out 10 + 510*4 = 2050 > 2048. The pixel stages CAN still reach the frame cap; only
		// the straight-line splat shape cannot. What DID move: the cheapest fixture for this
		// refusal now lives at the ANIMATOR (512 ops), where 510 vec4 splats lay out 2040
		// working floats + a 34-float prologue = 2074 > 2048 at a charge of 511 — inside the op
		// cap, so the frame is the cap that speaks.
		List<IrOp> wide = new ArrayList<IrOp>();
		int n = 510;
		for (int i = 0; i < n; i++) {
			wide.add(new IrOp(OcslWire.OP_SPLAT, W + i, k(0), 4)); // vec4: 4 floats each
		}
		wide.add(new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_ANIM_TINT, W));
		expectReject(new IrProgram(OcslWire.STAGE_ANIMATOR, new float[] { 1.0f }, wide,
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
		// "THE MESSAGE CANNOT BE VECTORED YET" WAS FALSE, and it was stated here, in the commit
		// message, and to the project owner. A mutation survived and I read that as proof the branch
		// was dead; it proved only that no test covered it. The OUT check is reachable at every OPEN
		// stage -- nothing upstream filters an unknown property id (IrStructure range-checks the
		// wire's 16 bits, IrCodec the u8 namespace, both deferring the table to here) -- so a
		// material-stage OUT to property 5 reaches it today. See theRefusalOnlyClaimsAReservation.
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

		// THE OBLIGATION, DISCHARGED 2026-08-13 — the surface opened, so the RESERVED branch is
		// reachable and gets the vector the tripwire here demanded, below.
	}

	@Test
	public void owningZOrVisibleIsRefusedByNameNowThatTheSurfaceIsOpen() throws Exception {
		// What the deleted tripwire asked for. ANIM-15(c) and SurfaceTable's javadoc promise these
		// two are "refused ... with a documented error ... so the refusal can name them", and until
		// the surface opened no program could reach the branch to prove it.
		int[] reserved = { OcslWire.PROP_ANIM_Z, OcslWire.PROP_ANIM_VISIBLE };
		String[] names = { "z", "visible" };
		for (int i = 0; i < reserved.length; i++) {
			IrProgram p = prog(OcslWire.STAGE_ANIMATOR, new float[] { 1.0f }, W + 1,
					new IrOp(OcslWire.OP_OUT, -1, reserved[i], k(0)));
			try {
				IrValidator.validate(p);
				fail(names[i] + " is not ownable in v1 and must be refused");
			} catch (ValidationException e) {
				String m = e.getMessage();
				assertTrue("the refusal must NAME it, not interpolate the raw id: " + m,
						m.contains(names[i]));
				assertTrue("and must say why -- a reservation, not an absence: " + m,
						m.contains("not ownable"));
				// The discriminating half: an id with no allocation at all must NOT be dressed up
				// as a reservation. This is the e5ea97c defect, at the stage that now reaches it.
				assertTrue("it must not read as 'has no property', which is a different claim: " + m,
						!m.contains("has no property"));
			}
		}

		// The mirror: an unallocated animator property id gets the plain absence message, so the
		// two branches are told apart by the code and not only by this test's expectations.
		try {
			IrValidator.validate(prog(OcslWire.STAGE_ANIMATOR, new float[] { 1.0f }, W + 1,
					new IrOp(OcslWire.OP_OUT, -1, 60, k(0))));
			fail("property 60 has no row at the animator stage");
		} catch (ValidationException e) {
			assertTrue("an unallocated id is absent, not reserved: " + e.getMessage(),
					e.getMessage().contains("has no property 60"));
			assertTrue("and must not claim a reservation nobody made: " + e.getMessage(),
					!e.getMessage().contains("not ownable"));
		}
	}

	@Test
	public void theRefusalOnlyClaimsAReservationWhereOneExists() throws Exception {
		// THE REGRESSION, given a vector. The first version of the naming fix gated on
		// `propertyName(...) != null` -- and propertyName is TOTAL, every arm falling back to
		// "prop" + id. So the guard was a tautology and every unknown property at every OPEN stage
		// was told it was "reserved but not ownable in v1", with a synthesized spelling presented as
		// if this table published it. Reachable today; it shipped; a review found it.
		try {
			IrValidator.validate(prog(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, W + 1,
					new IrOp(OcslWire.OP_SPLAT, W, k(0), 4),
					new IrOp(OcslWire.OP_OUT, -1, 5, W)));
			fail("property 5 has no row at the material stage and must be refused");
		} catch (ValidationException expected) {
			String m = expected.getMessage();
			assertTrue("it says the property does not exist: " + m, m.contains("has no property 5"));
			assertTrue("and does NOT claim a reservation for an unallocated id: " + m,
					!m.contains("reserved"));
			assertTrue("nor invent a spelling the table does not publish: " + m,
					!m.contains("prop5"));
		}

		// Non-vacuity, and it is the whole point: propertyName really does answer for that id, so a
		// null-guard could never have suppressed the text. This is the assertion whose absence let
		// the regression through.
		assertEquals("prop5", SurfaceTable.propertyName(OcslWire.STAGE_PIXEL_MATERIAL, 5));
		assertTrue("and the predicate that replaced the null-guard says no",
				!SurfaceTable.isReservedUnownable(OcslWire.STAGE_PIXEL_MATERIAL, 5));
		assertTrue("while saying yes for the two ids that are genuinely reserved",
				SurfaceTable.isReservedUnownable(OcslWire.STAGE_ANIMATOR, OcslWire.PROP_ANIM_Z)
						&& SurfaceTable.isReservedUnownable(OcslWire.STAGE_ANIMATOR,
								OcslWire.PROP_ANIM_VISIBLE));
		assertTrue("and no for an ownable animator property",
				!SurfaceTable.isReservedUnownable(OcslWire.STAGE_ANIMATOR, OcslWire.PROP_ANIM_X));
	}
}
