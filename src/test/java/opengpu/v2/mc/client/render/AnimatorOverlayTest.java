package opengpu.v2.mc.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import opengpu.v2.ocsl.Expr;
import opengpu.v2.ocsl.IrCodec;
import opengpu.v2.ocsl.IrValidator;
import opengpu.v2.ocsl.OcslBuilder;
import opengpu.v2.ocsl.OcslTime;
import opengpu.v2.ocsl.OcslWire;
import opengpu.v2.ocsl.OcslWriteBoundary;
import opengpu.v2.ocsl.SurfaceTable;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.ProgramInfo;
import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneState;

/**
 * Phase 3.3a — the evaluator, tested headlessly because everything that DECIDES anything lives
 * here. 3.3b's renderer substitution is Forge-bound wiring; this is where the arithmetic is.
 *
 * The fixtures build real programs through {@code OcslBuilder}, encode them, and store them as
 * {@code ProgramInfo} exactly as {@code createProgram} would — so these run against the same
 * decode-and-validate path a client does, not against a hand-made VM.
 */
public class AnimatorOverlayTest {

	private static final long OFFSET = 0L;          // world time == session ticks, for legibility
	private static final long TICK = OcslTime.TICK_NANOS;

	// ---------------------------------------------------------------- fixtures

	private static byte[] program(int propertyId, float constant) throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		// tint is a VEC4 property and the builder type-checks the OUT, so a scalar here is a build
		// error rather than a silent broadcast -- which is the behaviour we want and is why this
		// helper branches instead of taking a float everywhere.
		b.out(propertyId, propertyId == OcslWire.PROP_ANIM_TINT
				? b.constant(constant, constant, constant, constant)
				: b.f(constant));
		return IrCodec.encode(b.build());
	}

	/** Writes `x = <constant>` in the ABSOLUTE form: replaces the base rather than adding to it. */
	private static byte[] absoluteProgram(int propertyId, float constant) throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.outAbsolute(propertyId, b.f(constant));
		return IrCodec.encode(b.build());
	}

	private static ProgramInfo info(int id, byte[] blob) throws Exception {
		long charge = IrValidator.validate(
				IrCodec.decode(blob, IrCodec.Source.TRANSIENT)).structuralOps;
		return new ProgramInfo(id, OcslWire.STAGE_ANIMATOR, blob, (int) charge);
	}

	private static SceneState sceneWith(ProgramInfo... programs) {
		SceneState s = new SceneState();
		for (ProgramInfo p : programs) {
			s.programs.put(Integer.valueOf(p.id), p);
			s.nextProgramId = Math.max(s.nextProgramId, p.id + 1);
		}
		s.creationWorldTime = 100L;
		s.worldTimeAnchor = 100L;
		return s;
	}

	private static SceneNode node(SceneState s, int id, int parent) {
		SceneNode n = new SceneNode(id, V2Wire.NODE_SPRITE, 0, parent);
		s.nodes.put(Integer.valueOf(id), n);
		s.nextNodeId = Math.max(s.nextNodeId, id + 1);
		return n;
	}

	/** A render instant far enough past the epoch that `time` is well inside its period. */
	private static long instant(long sessionTick) {
		return sessionTick * TICK;
	}

	// ---------------------------------------------------------------- composition

	/**
	 * The RELATIVE form composes over the server base — the animator adds, it does not replace.
	 *
	 * The exclusion is the whole point: an implementation that returned the animator's raw output
	 * would give 5.0 here, and one that ignored the animator would give 7.0. Only composition
	 * gives 12.0, so this one number rules out both.
	 */
	@Test
	public void aRelativeWriteComposesOverTheServerBase() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		SceneNode n = node(s, 1, 0);
		n.x = 7.0;
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);

		AnimatorOverlay.Composed c = overlay.of(1);
		assertNotNull("an attached node must produce an entry", c);
		assertTrue(c.wrote(OcslWire.PROP_ANIM_X));
		assertEquals("base 7 + animator 5", 12.0, c.x, 1e-6);
		assertFalse("a property the program never wrote must not be claimed",
				c.wrote(OcslWire.PROP_ANIM_Y));
	}

	/** The ABSOLUTE form replaces the base — ANIM-7's double-apply mitigation, end to end. */
	@Test
	public void anAbsoluteWriteReplacesTheServerBase() throws Exception {
		SceneState s = sceneWith(info(1, absoluteProgram(OcslWire.PROP_ANIM_X, 5.0f)));
		SceneNode n = node(s, 1, 0);
		n.x = 7.0;
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);

		assertEquals("absolute means 5, not 12 — composing it relatively is the double-apply bug"
				+ " the opcode exists to prevent", 5.0, overlay.of(1).x, 1e-6);
	}

	/** Scale composes MULTIPLICATIVELY, which is a different rule from position's. */
	@Test
	public void scaleComposesMultiplicatively() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_SX, 3.0f)));
		SceneNode n = node(s, 1, 0);
		n.sx = 2.0;
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertEquals("2 * 3, not 2 + 3 — an additive reading would give 5.0",
				6.0, overlay.of(1).sx, 1e-6);
	}

	// ---------------------------------------------------------------- the clamp

	/**
	 * THE LEDGER ITEM: clampForWrite runs, and tint is why it matters.
	 *
	 * A tint above 1.0 reaching the packer is {@code (int)(1.5 * 255) = 382}, which lands in the
	 * byte as 126 — the node flashes DARK exactly where the author asked for full brightness.
	 *
	 * Tint composes by REPLACE, so the 1.5 here is the animator's own output rather than something
	 * composition produced; an earlier draft of this paragraph called it "a multiply-rule animator"
	 * and said "the base is already 1.0", both left over from a first version that used a white
	 * base and was replaced eight lines below.
	 */
	@Test
	public void tintIsClampedBeforeItCanReachThePacker() throws Exception {
		// A NON-WHITE base, and that is the whole vector. A first draft used 0xFFFFFFFF, whose
		// channels are 1.0 — the exact value asserted — so the test passed for an evaluator that
		// never composed at all, and its own message ("not to zero or to the base") was false
		// because the base WAS 1.0. 0x80 is 0.502, which no wrong answer here coincides with.
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_TINT, 1.5f)));
		SceneNode n = node(s, 5, 0);
		// 0x80 in ALL FOUR channels including alpha. A first fix used 0xFF808080, whose alpha
		// unpacks to 1.0 — the very value asserted — so channel 3 still passed for an evaluator
		// that never composed, leaving the hole in one of four lanes.
		n.tint = 0x80808080;
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);

		AnimatorOverlay.Composed c = overlay.of(5);
		assertTrue(c.wrote(OcslWire.PROP_ANIM_TINT));
		for (int channel = 0; channel < 4; channel++) {
			assertEquals("channel " + channel + ": unclamped this is 1.5, which reaches the packer"
					+ " as (int)(1.5*255) = 382 and lands in the byte as 126 — the node flashing"
					+ " DARK where full brightness was asked for. Base-passthrough would give"
					+ " 0.502 and multiply would give 0.753, so 1.0 excludes both — in every"
					+ " channel, alpha included.",
					1.0f, c.tint[channel], 1e-6f);
		}
	}

	/**
	 * Tint REPLACES rather than multiplying — the rule the clamp test above cannot see, because
	 * every candidate rule exceeds 1.0 there and clamps to the same answer.
	 *
	 * An animator output BELOW the base separates them: replace gives 0.5, multiply gives 0.251,
	 * add gives 1.0 (clamped), and ignoring the animator gives 0.502.
	 */
	@Test
	public void tintReplacesTheBaseRatherThanScalingIt() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_TINT, 0.5f)));
		SceneNode n = node(s, 5, 0);
		n.tint = 0x80808080; // 0.502 in every channel, alpha included
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertEquals("tint is RULE_REPLACE: the animator's value wins outright",
				0.5f, overlay.of(5).tint[0], 1e-6f);
	}

	/** TRS clamps at the finite limit, so composition cannot hand the transform math an infinity. */
	@Test
	public void aRunawayPositionIsClampedToTheFiniteLimit() throws Exception {
		// BOTH OPERANDS LEGAL, which is the stated rationale and what a first draft failed to
		// exercise: it used 1e30 for both, so the BASE alone already clamped to the answer and the
		// test passed for an evaluator that never composed. 9e5 + 9e5 = 1.8e6 is over the 1e6
		// limit while each half is comfortably inside it.
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 9.0e5f)));
		SceneNode n = node(s, 5, 0);
		n.x = 9.0e5;
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertEquals("composition left the range although both operands were inside it — clamping"
				+ " the animator's output alone would give 9e5 and never bound this",
				OcslWriteBoundary.TRS_LIMIT, overlay.of(5).x, 1.0);
	}

	// ---------------------------------------------------------------- parent registers

	/**
	 * A child reads its parent's COMPOSED value, not its raw one — decided 2026-08-20.
	 *
	 * The parent is animated to x = 10 + 5 = 15; the child copies its parent's x register. Raw
	 * would give 10, composed gives 15, so the number discriminates. This is the counter-rotation
	 * case in its simplest form, and the reason ANIM-7's raw-only rule does not reach a parent:
	 * reading your parent is not self-referential.
	 */
	@Test
	public void aChildReadsItsParentsComposedValue() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.outAbsolute(OcslWire.PROP_ANIM_X, b.builtin(SurfaceTable.REG_ANIM_PARENT_X));
		byte[] copyParentX = IrCodec.encode(b.build());

		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)),
				info(2, copyParentX));
		SceneNode parent = node(s, 1, 0);
		parent.x = 10.0;
		parent.animator = 1;
		parent.attachedWorldTime = 100L;
		SceneNode child = node(s, 2, 1);   // id 2 > 1, so it evaluates after its parent
		// A NON-ZERO base: 0.0 is the ADD identity, so a child that composed its absolute write
		// relatively by mistake would also land on 15 and this test would not see it.
		child.x = 3.0;
		child.animator = 2;
		child.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);

		assertEquals("the parent composed to 15", 15.0, overlay.of(1).x, 1e-6);
		assertEquals("and the child must see 15, not the raw 10 — raw is what fails exactly when"
				+ " the parent is animated, which is the only case anyone writes this for."
				+ " 15 also excludes a relative composition of the child's own base, which"
				+ " would give 18",
				15.0, overlay.of(2).x, 1e-6);
	}

	/** An UNANIMATED parent's composed value is its raw value, so both branches agree. */
	@Test
	public void anUnanimatedParentReadsAsItsRawValue() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.outAbsolute(OcslWire.PROP_ANIM_X, b.builtin(SurfaceTable.REG_ANIM_PARENT_X));
		SceneState s = sceneWith(info(2, IrCodec.encode(b.build())));
		SceneNode parent = node(s, 1, 0);
		parent.x = 10.0;                    // no animator
		SceneNode child = node(s, 2, 1);
		child.animator = 2;
		child.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertEquals(10.0, overlay.of(2).x, 1e-6);
	}

	/** An unparented node reads the IDENTITY, not zero — zero scale would collapse the node. */
	@Test
	public void anUnparentedNodeReadsTheIdentityForScale() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.outAbsolute(OcslWire.PROP_ANIM_SX, b.builtin(SurfaceTable.REG_ANIM_PARENT_SX));
		SceneState s = sceneWith(info(1, IrCodec.encode(b.build())));
		SceneNode n = node(s, 5, 0);
		// sx away from its 1.0 default, which is the identity being asserted: leaving it at the
		// default let this pass for an evaluator binding the node's OWN sx into the parent slot.
		n.sx = 4.0;
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertEquals("a missing parent's scale must read 1.0; 0.0 would collapse every node that"
				+ " reads it, and the node's own 4.0 would be the wrong register entirely",
				1.0, overlay.of(5).sx, 1e-6);
	}

	// ---------------------------------------------------------------- sinceAttach

	/** The saturating clock: seconds since attach, clamped at the CAP. */
	@Test
	public void sinceAttachMeasuresFromTheAttachStampAndSaturates() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.outAbsolute(OcslWire.PROP_ANIM_X, b.builtin(SurfaceTable.REG_ANIM_SINCE_ATTACH));
		byte[] readsSinceAttach = IrCodec.encode(b.build());

		// EVERY STAMP DISTINCT, and a non-zero offset. A first draft had creationWorldTime,
		// worldTimeAnchor and attachedWorldTime all equal to 100 with OFFSET 0, so "measures from
		// the ATTACH stamp" was not what it proved — measuring from the scene epoch, or dropping
		// the offset entirely, passed identically.
		SceneState s = sceneWith(info(1, readsSinceAttach));
		s.creationWorldTime = 100L;
		SceneNode n = node(s, 5, 0);
		n.animator = 1;
		n.attachedWorldTime = 120L;       // 20 ticks AFTER the scene epoch
		final long offset = 7L;           // world 120 -> session tick 127

		AnimatorOverlay overlay = new AnimatorOverlay();
		// 50 ticks after the attach == 2.5 s. The half-second matters: an integer-truncating
		// conversion renders it as 2.0, and a whole number would have hidden that.
		overlay.evaluate(s, instant(127 + 50), offset, true);
		assertEquals("2.5 s after the ATTACH stamp — measuring from the scene epoch would give 3.5",
				2.5, overlay.of(5).x, 1e-3);

		// Far past the cap: it must SETTLE, which is the property a wrapped clock cannot have.
		overlay.evaluate(s, instant(127 + 20L * 100_000L), offset, true);
		assertEquals("saturated at the cap", AnimatorOverlay.SINCE_ATTACH_CAP_SECONDS,
				overlay.of(5).x, 1e-3);
	}

	/**
	 * A stamp slightly AHEAD of the render instant reads 0, never negative.
	 *
	 * Reachable rather than theoretical: renderNanos deliberately trails the server estimate by
	 * the interpolation delay, so a just-attached node is briefly "attached in the future".
	 * Negative seconds would run an easing program backwards through its first frames.
	 */
	@Test
	public void aStampAheadOfTheRenderInstantReadsZero() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.outAbsolute(OcslWire.PROP_ANIM_X, b.builtin(SurfaceTable.REG_ANIM_SINCE_ATTACH));
		SceneState s = sceneWith(info(1, IrCodec.encode(b.build())));
		SceneNode n = node(s, 1, 0);
		n.animator = 1;
		n.attachedWorldTime = 200L;       // stamped later than the instant below

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(190), OFFSET, true);
		assertEquals(0.0, overlay.of(1).x, 1e-6);
	}

	// ---------------------------------------------------------------- the frame bindings
	// Everything below was UNASSERTED until a review lens pointed it out: no test read `time`,
	// `nodeSeed`, or any own-property register, so `float time = 0.0f` and a deleted
	// bindOwnProperties both passed the whole suite. The composition tests could not see them
	// because the base they compose against comes from the node, not from the frame.

	/**
	 * `time` is the scene's age in the wrap period, and it reaches the program.
	 *
	 * The scene is stamped at world 100 and the frame renders at session tick 200 with offset 0,
	 * so the epoch is tick 100 and the elapsed time is 100 ticks = 5.000 s — well inside P, so no
	 * wrap is involved and the number is exact. `time = 0` (the mutant this exists to kill) and
	 * measuring from the attach stamp instead of the scene epoch both give something else.
	 */
	@Test
	public void theSceneClockReachesTheProgram() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.outAbsolute(OcslWire.PROP_ANIM_X, b.builtin(SurfaceTable.REG_TIME));
		SceneState s = sceneWith(info(1, IrCodec.encode(b.build())));
		s.creationWorldTime = 100L;
		SceneNode n = node(s, 5, 0);
		n.animator = 1;
		n.attachedWorldTime = 180L;   // distinct from the epoch, so the two cannot be confused

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertEquals("5 s since the scene epoch; measuring from the ATTACH stamp would give 1.0",
				5.0, overlay.of(5).x, 1e-3);
	}

	/** And it advances with the render instant, which is what makes it a clock rather than a stamp. */
	@Test
	public void theSceneClockAdvancesWithTheFrame() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.outAbsolute(OcslWire.PROP_ANIM_X, b.builtin(SurfaceTable.REG_TIME));
		SceneState s = sceneWith(info(1, IrCodec.encode(b.build())));
		s.creationWorldTime = 100L;
		SceneNode n = node(s, 5, 0);
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		double first = overlay.of(5).x;
		overlay.evaluate(s, instant(220), OFFSET, true);
		assertEquals("20 ticks later is one second later", first + 1.0, overlay.of(5).x, 1e-3);
	}

	/**
	 * ANIM-2's de-phasing: two nodes running the SAME program get different seeds.
	 *
	 * Asserted as a difference rather than against specific hash values, so the mix can be
	 * retuned without rewriting the test — what matters is that co-attached nodes do not run in
	 * lockstep, which is the "200 debris sprites shaking on the same frame" failure. A
	 * {@code return 0.0f} seed passes every other test in this file.
	 */
	@Test
	public void nodeSeedDePhasesNodesSharingOneProgram() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.outAbsolute(OcslWire.PROP_ANIM_X, b.builtin(SurfaceTable.REG_ANIM_NODE_SEED));
		SceneState s = sceneWith(info(1, IrCodec.encode(b.build())));
		SceneNode a = node(s, 5, 0);
		a.animator = 1;
		a.attachedWorldTime = 100L;
		SceneNode c = node(s, 6, 0);
		c.animator = 1;              // the same program
		c.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);

		double sa = overlay.of(5).x;
		double sc = overlay.of(6).x;
		assertTrue("seeds must differ, or a preset on N nodes runs in lockstep", sa != sc);
		assertTrue("and stay in 0..1, which is what makes them usable as a phase: " + sa,
				sa >= 0.0 && sa < 1.0 && sc >= 0.0 && sc < 1.0);
		// Deterministic: the same node id must seed identically on every client and every frame,
		// or two clients would de-phase the same node differently.
		overlay.evaluate(s, instant(260), OFFSET, true);
		assertEquals("the seed is a pure function of the node id", sa, overlay.of(5).x, 0.0);
	}

	/**
	 * The node's OWN properties are bound into the frame, in the right registers.
	 *
	 * Reads y and writes x, so a transposed binding shows up as the wrong number rather than as
	 * nothing. Deleting bindOwnProperties entirely leaves the register at 0 and also fails —
	 * both were invisible before, because composition takes its base from the node directly.
	 */
	@Test
	public void theNodesOwnPropertiesAreBoundIntoTheFrame() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.outAbsolute(OcslWire.PROP_ANIM_X, b.builtin(SurfaceTable.REG_ANIM_Y));
		SceneState s = sceneWith(info(1, IrCodec.encode(b.build())));
		SceneNode n = node(s, 5, 0);
		n.x = 99.0;   // distinct from y, so reading the wrong register is visible
		n.y = 3.0;
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertEquals("the program read y (3.0); 99.0 would mean x was bound into y's register,"
				+ " and 0.0 would mean nothing was bound at all",
				3.0, overlay.of(5).x, 1e-6);
	}

	/**
	 * The side map is keyed by NODE id, not by program id.
	 *
	 * Every fixture in a first draft used node id == animator id, so a map keyed by the program
	 * would have passed all eighteen. Here one program animates two nodes: keying by program id
	 * collapses them into one entry and loses a node.
	 */
	@Test
	public void resultsAreKeyedByNodeNotByProgram() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		SceneNode a = node(s, 5, 0);
		a.x = 1.0;
		a.animator = 1;
		a.attachedWorldTime = 100L;
		SceneNode c = node(s, 6, 0);
		c.x = 2.0;
		c.animator = 1;              // the SAME program on a second node
		c.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);

		assertNotNull("node 5 must have its own entry", overlay.of(5));
		assertNotNull("and node 6 must too", overlay.of(6));
		assertEquals("each composes over its OWN base", 6.0, overlay.of(5).x, 1e-6);
		assertEquals(7.0, overlay.of(6).x, 1e-6);
		assertNull("nothing may be filed under the program id", overlay.of(1));
	}

	/**
	 * A program owning a STAGE C property does not take the frame down.
	 *
	 * `rot3d` composes by RULE_QUATERNION, and {@code OcslCompose.compose} throws for it by design
	 * — so once `written` began coming from the program's own declaration instead of a hardcoded
	 * 2D list, a legal rot3d-owning program became a crash. The validator accepts such a program
	 * today, so this is reachable from the OC surface, not hypothetical.
	 *
	 * The node still renders: its 2D properties are untouched and the z write is simply dropped,
	 * which is what "ownable-but-unconsumed" means until Stage C consumes it.
	 */
	@Test
	public void aProgramOwningAStageCPropertyIsSkippedNotThrown() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.out(OcslWire.PROP_ANIM_ROT3D, b.constant(0f, 0f, 0f, 1f));
		b.out(OcslWire.PROP_ANIM_X, b.f(5.0f));   // and a 2D property alongside it
		SceneState s = sceneWith(info(1, IrCodec.encode(b.build())));
		SceneNode n = node(s, 5, 0);
		n.x = 7.0;
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);   // must not throw

		assertEquals("the 2D property alongside it must still compose", 12.0,
				overlay.of(5).x, 1e-6);
		assertFalse("and the Stage C write is dropped rather than stored",
				overlay.of(5).wrote(OcslWire.PROP_ANIM_ROT3D));
	}

	/** tz and sz compose cleanly but have nowhere to go; they must also be dropped silently. */
	@Test
	public void aProgramOwningZTranslateIsSkippedNotThrown() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.out(OcslWire.PROP_ANIM_TZ, b.f(3.0f));
		SceneState s = sceneWith(info(1, IrCodec.encode(b.build())));
		SceneNode n = node(s, 5, 0);
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		AnimatorOverlay.Composed c = overlay.of(5);
		assertTrue("an entry is still produced; it simply carries no 2D property",
				c == null || !c.wrote(OcslWire.PROP_ANIM_X));
	}

	// ---------------------------------------------------------------- failing closed

	/** No clock, no animation — and no plausible-looking wrong phase either. */
	@Test
	public void anUnknownClockEvaluatesNothing() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		SceneNode n = node(s, 1, 0);
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, false);
		assertTrue("without the anchor every stamp is unplaceable; running anyway would animate"
				+ " confidently at a phase wrong by the whole magnitude of world time",
				overlay.isEmpty());
	}

	/** An unprimed timeline is the same refusal, by the sentinel renderInstant returns. */
	@Test
	public void anUnprimedTimelineEvaluatesNothing() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		SceneNode n = node(s, 1, 0);
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, Long.MIN_VALUE, OFFSET, true);
		assertTrue(overlay.isEmpty());
	}

	/** A DANGLING attachment renders at the server value — ANIM-17's ruling, at the evaluator. */
	@Test
	public void aDanglingAttachmentEvaluatesNothingAndDoesNotThrow() {
		SceneState s = sceneWith(); // no programs at all
		SceneNode n = node(s, 1, 0);
		n.animator = 4242;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertTrue("a freed program must leave the node at its base, not crash the frame",
				overlay.isEmpty());
	}

	/** An unattached node produces no entry, so the renderer reads the base for it. */
	@Test
	public void anUnattachedNodeProducesNothing() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		node(s, 1, 0); // animator stays 0

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertNull(overlay.of(1));
	}

	/**
	 * A corrupt blob is skipped rather than taking the frame down, on this frame and the next.
	 *
	 * The class ALSO records the failure so it is not re-parsed every frame forever, and this test
	 * deliberately does not claim to prove that — `broken` has no observable, and asserting
	 * `isEmpty()` twice would look like evidence for it while being satisfied by an implementation
	 * that re-decodes and re-throws every frame. Stated rather than implied, because a docstring
	 * claiming a property no assertion holds is how a gap survives review.
	 */
	@Test
	public void aCorruptProgramIsSkipped() {
		SceneState s = new SceneState();
		s.creationWorldTime = 100L;
		s.worldTimeAnchor = 100L;
		byte[] garbage = new byte[64];
		s.programs.put(Integer.valueOf(1), new ProgramInfo(1, OcslWire.STAGE_ANIMATOR, garbage, 4));
		s.nextProgramId = 2;
		SceneNode n = node(s, 1, 0);
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertTrue("damaged bytes must not take the frame down", overlay.isEmpty());
		overlay.evaluate(s, instant(201), OFFSET, true); // and again, still fine
		assertTrue(overlay.isEmpty());
	}

	// ---------------------------------------------------------------- state hygiene

	/**
	 * THE OVERLAY NEVER WRITES TO SceneState — the invariant that keeps persistence and
	 * convergence honest. Asserted by comparing the node's fields against a copy taken before
	 * evaluation: an animator that mutated the base would replicate as though the server had set
	 * it, and would make two clients at different frame times read as divergent.
	 */
	@Test
	public void evaluationLeavesTheSceneStateUntouched() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)),
				info(2, program(OcslWire.PROP_ANIM_TINT, 0.5f)));
		SceneNode n = node(s, 1, 0);
		n.x = 7.0;
		n.tint = 0xFF804020;
		n.animator = 1;
		n.attachedWorldTime = 100L;
		SceneState before = s.copy();

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);

		assertNotNull("precondition: the evaluator must actually have run, or this test passes"
				+ " trivially on a no-op", overlay.of(1));
		assertEquals("the base position must be untouched", 7.0,
				s.nodes.get(Integer.valueOf(1)).x, 0.0);
		assertEquals("and the base tint", 0xFF804020, s.nodes.get(Integer.valueOf(1)).tint);
		assertTrue("nothing in the scene may differ after a frame of animation",
				before.contentEquals(s));
		// contentEquals compares resources, nodes and programs — NOT these two longs, so an
		// evaluator that cached a converted offset back into the scene would slip past it.
		// Asserted directly rather than trusted to the comparison.
		assertEquals("the scene epoch is read, never written", 100L, s.creationWorldTime);
		assertEquals("and so is the anchor", 100L, s.worldTimeAnchor);
	}

	/** Re-evaluating replaces the previous frame's results rather than accumulating them. */
	@Test
	public void eachFrameStartsFromTheBaseNotFromLastFrame() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		SceneNode n = node(s, 1, 0);
		n.x = 7.0;
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertEquals(12.0, overlay.of(1).x, 1e-6);
		overlay.evaluate(s, instant(201), OFFSET, true);
		assertEquals("a second frame must give 12 again, not 17 — the animator is stateless and"
				+ " composing over last frame's output would be the accumulation purity forbids",
				12.0, overlay.of(1).x, 1e-6);
	}

	/** Detaching clears the entry, so the node snaps back to its server value. */
	@Test
	public void detachingClearsTheEntry() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		SceneNode n = node(s, 1, 0);
		n.x = 7.0;
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertNotNull(overlay.of(1));

		n.animator = 0;
		n.attachedWorldTime = 0L;
		overlay.evaluate(s, instant(201), OFFSET, true);
		assertNull("a detached node must produce no entry, which is what snaps it to the base",
				overlay.of(1));
	}

	// ------------------------------------------------- 3.3b: the interpolated composition base

	/** First server tick and its local arrival, for the paced-arrival fixtures below. */
	private static final long T0 = 1000L;
	private static final long N0 = 7_000_000L;
	private static final java.util.Set<Integer> NONE = java.util.Collections.<Integer>emptySet();

	/** Local time at which the render clock is showing {@code serverTick} — the same helper
	 *  every NodeInterpolatorTest fixture derives its instants from. */
	private static long localShowing(double serverTick) {
		return (long) (serverTick * TICK) - (T0 * TICK - N0)
				+ ServerTimeline.INTERPOLATION_DELAY_TICKS * TICK;
	}

	/** An interpolator holding one mid-flight track for node {@code id}: x glides 0 -> 10
	 *  between ticks T0 and T0+1. Mutates the node's x to 10 (the current server value). */
	private static NodeInterpolator midFlightX(SceneState s, SceneNode n) {
		NodeInterpolator interp = new NodeInterpolator();
		n.x = 0.0;
		interp.capture(s, T0, N0, NONE);
		n.x = 10.0;
		interp.capture(s, T0 + 1, N0 + TICK, NONE);
		return interp;
	}

	/**
	 * THE COMPOSITION BASE IS THE DISPLAYED TRANSFORM — DESIGN (ANIM-7): "The interpolated
	 * display transform is the composition base". A relative write lands on the base as drawn,
	 * so an animated node whose server base is mid-glide moves with the glide.
	 *
	 * Halfway through the keyframe pair the displayed base is 5.0, so +100 composes to 105.
	 * The exclusion is what matters: composing over the RAW field gives 110 — and that failure
	 * is invisible on every fixture whose base is settled, which is all of the ones above.
	 */
	@Test
	public void aRelativeWriteComposesOverTheInterpolatedBase() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 100.0f)));
		SceneNode n = node(s, 1, 0);
		n.animator = 1;
		n.attachedWorldTime = 100L;
		NodeInterpolator interp = midFlightX(s, n);

		long now = localShowing(T0 + 0.5);
		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, interp.renderInstant(now), OFFSET, true, interp, now);

		assertEquals("base is DISPLAYED 5.0 mid-glide, so +100 gives 105 — 110 means the raw"
				+ " field was composed over, re-stepping animated nodes at 20 Hz",
				105.0, overlay.of(1).x, 1e-4);
	}

	/**
	 * ANIM-7's split, both halves in one frame: the program's OWN register reads the RAW
	 * server-set value even while the composition base is interpolated. "x is not where I am
	 * drawn" — this is that sentence as arithmetic.
	 */
	@Test
	public void ownRegistersStayRawWhileTheBaseIsInterpolated() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.outAbsolute(OcslWire.PROP_ANIM_X, b.builtin(SurfaceTable.REG_ANIM_X));
		SceneState s = sceneWith(info(1, IrCodec.encode(b.build())));
		SceneNode n = node(s, 1, 0);
		n.animator = 1;
		n.attachedWorldTime = 100L;
		NodeInterpolator interp = midFlightX(s, n);

		long now = localShowing(T0 + 0.5);
		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, interp.renderInstant(now), OFFSET, true, interp, now);

		assertEquals("the register carries the RAW 10.0; 5.0 means the displayed base leaked"
				+ " into the purity-ruled registers, and 0.0 means nothing was bound",
				10.0, overlay.of(1).x, 1e-4);
	}

	/**
	 * The parent registers' unanimated fallback is the parent's DISPLAYED base, not its raw
	 * field — "the parent's effective rotation" means the one it is drawn with, and a raw
	 * fallback would put a counter-rotating child an interpolation window behind its parent.
	 */
	@Test
	public void theParentRegistersCarryTheParentsDisplayedBase() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.outAbsolute(OcslWire.PROP_ANIM_X, b.builtin(SurfaceTable.REG_ANIM_PARENT_X));
		SceneState s = sceneWith(info(1, IrCodec.encode(b.build())));
		SceneNode parent = node(s, 1, 0);
		SceneNode child = node(s, 2, 1);
		child.animator = 1;
		child.attachedWorldTime = 100L;
		NodeInterpolator interp = midFlightX(s, parent);

		long now = localShowing(T0 + 0.5);
		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, interp.renderInstant(now), OFFSET, true, interp, now);

		assertEquals("the unanimated parent's register value is its displayed 5.0; 10.0 means"
				+ " the raw fallback survived", 5.0, overlay.of(2).x, 1e-4);
	}

	// ------------------------------------------------- 3.3b: the renderer-facing surface

	/**
	 * reset() forgets COMPILED programs, not just results. An epoch change restarts the program
	 * id space, so the same id can arrive carrying different code — the one case pruneCaches
	 * structurally cannot see, because the id is still present.
	 */
	@Test
	public void resetForgetsCompiledProgramsForANewEpoch() throws Exception {
		AnimatorOverlay overlay = new AnimatorOverlay();
		SceneState s1 = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		SceneNode n1 = node(s1, 1, 0);
		n1.x = 1.0;
		n1.animator = 1;
		n1.attachedWorldTime = 100L;
		overlay.evaluate(s1, instant(200), OFFSET, true);
		assertEquals(6.0, overlay.of(1).x, 1e-6);

		// A new incarnation: the SAME program id now holds different code.
		SceneState s2 = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 7.0f)));
		SceneNode n2 = node(s2, 1, 0);
		n2.x = 1.0;
		n2.animator = 1;
		n2.attachedWorldTime = 100L;
		overlay.reset();
		assertNull("reset forgets RESULTS too, not only programs — a caller reading between"
				+ " reset and the next evaluate must see nothing, not the dead epoch's frame",
				overlay.of(1));
		overlay.evaluate(s2, instant(200), OFFSET, true);
		assertEquals("8, not 6 — 6 means the stale epoch's compiled program survived reset and"
				+ " the client is running yesterday's code under today's id",
				8.0, overlay.of(1).x, 1e-6);
	}

	/**
	 * The renderer-facing substitution touches ONLY the properties the program wrote — the
	 * others keep whatever displayed base the caller already read into the vector.
	 */
	@Test
	public void overlayTransformSubstitutesOnlyWrittenProperties() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		SceneNode n = node(s, 1, 0);
		n.x = 7.0;
		n.animator = 1;
		n.attachedWorldTime = 100L;
		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);

		double[] trs = { 1.0, 2.0, 3.0, 4.0, 5.0 };
		overlay.overlayTransform(1, trs);
		assertEquals("the written property is substituted", 12.0, trs[NodeFold.TRS_X], 1e-6);
		assertEquals("unwritten properties keep the caller's base", 2.0, trs[NodeFold.TRS_Y], 1e-6);
		assertEquals(3.0, trs[NodeFold.TRS_ROT], 1e-6);
		assertEquals(4.0, trs[NodeFold.TRS_SX], 1e-6);
		assertEquals(5.0, trs[NodeFold.TRS_SY], 1e-6);

		double[] untouched = { 1.0, 2.0, 3.0, 4.0, 5.0 };
		overlay.overlayTransform(99, untouched);
		assertEquals("a node with no entry substitutes nothing", 1.0, untouched[NodeFold.TRS_X],
				1e-6);
	}

	/**
	 * tintFactor hands the renderer the composed tint when written, the raw unpack when not.
	 *
	 * DISTINCT VALUES IN ALL FOUR CHANNELS, learned the hard way in 3.3a: a channel-uniform
	 * fixture (0.25 everywhere) cannot see a lane swap between {@code Composed.tint}'s RGBA and
	 * {@code NodeFold}'s TINT_* order — every wrong permutation reads the same.
	 */
	@Test
	public void tintFactorCarriesTheComposedTintAndFallsBackToThePacked() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.out(OcslWire.PROP_ANIM_TINT, b.constant(0.1f, 0.2f, 0.3f, 0.4f));
		SceneState s = sceneWith(info(1, IrCodec.encode(b.build())));
		SceneNode animated = node(s, 1, 0);
		animated.tint = 0x80808080;
		animated.animator = 1;
		animated.attachedWorldTime = 100L;
		SceneNode plain = node(s, 2, 0);
		plain.tint = 0x80402010;
		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);

		double[] out = new double[4];
		overlay.tintFactor(1, animated.tint, out);
		assertEquals("R: tint REPLACES, so the animator's value is the factor — 0.502 means the"
				+ " raw field won over a written tint", 0.1, out[NodeFold.TINT_R], 1e-6);
		assertEquals("G", 0.2, out[NodeFold.TINT_G], 1e-6);
		assertEquals("B", 0.3, out[NodeFold.TINT_B], 1e-6);
		assertEquals("A", 0.4, out[NodeFold.TINT_A], 1e-6);

		overlay.tintFactor(2, plain.tint, out);
		assertEquals("an unanimated node unpacks its raw tint", 0x40 / 255.0,
				out[NodeFold.TINT_R], 1e-9);
		assertEquals(0x20 / 255.0, out[NodeFold.TINT_G], 1e-9);
		assertEquals(0x10 / 255.0, out[NodeFold.TINT_B], 1e-9);
		assertEquals(0x80 / 255.0, out[NodeFold.TINT_A], 1e-9);
	}

	/**
	 * The structural-charge report: once per PROGRAM at compile — not per frame and not per
	 * node. PLAN's op-cap section wants real charge data for the ANIM-16 cap decision; a
	 * per-frame count would be frame count in a charge costume, and a per-node count would
	 * double every shared preset. TWO nodes share the one program here so the per-node reading
	 * is visible, and the TOTAL is asserted alongside max — the panel found it accumulated but
	 * pinned by nothing, so deleting the accumulation survived every channel at once.
	 */
	@Test
	public void theChargeIsReportedOncePerProgramNotPerFrame() throws Exception {
		opengpu.v2.stats.RenderStats.reset();
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		SceneNode n = node(s, 1, 0);
		n.animator = 1;
		n.attachedWorldTime = 100L;
		SceneNode m = node(s, 2, 0);
		m.animator = 1;              // the SAME program on a second node
		m.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertEquals("one compile, one report — 2 would mean per-NODE reporting, doubling"
				+ " every shared preset", 1L,
				opengpu.v2.stats.RenderStats.animatorProgramsCompiled);
		assertTrue("a real program has a non-zero charge",
				opengpu.v2.stats.RenderStats.animatorChargeMax > 0);
		assertEquals("with one program the total IS the max; a dropped accumulation reads 0",
				opengpu.v2.stats.RenderStats.animatorChargeMax,
				(int) opengpu.v2.stats.RenderStats.animatorChargeTotal);

		overlay.evaluate(s, instant(201), OFFSET, true);
		assertEquals("a second frame reuses the compiled program — 2 means the report is"
				+ " per-frame and the charge data is frame count in disguise", 1L,
				opengpu.v2.stats.RenderStats.animatorProgramsCompiled);
		assertEquals("and the total holds with it",
				opengpu.v2.stats.RenderStats.animatorChargeMax,
				(int) opengpu.v2.stats.RenderStats.animatorChargeTotal);
	}

	/**
	 * The frame and register columns must report the PROGRAM's own numbers, not the charge
	 * wearing their names.
	 *
	 * Three values now cross one call in the same order they are declared, which is the shape
	 * that makes an argument swap invisible to every "> 0" assertion — so these are pinned by
	 * EQUALITY against the validator's own answer for the same blob, not by non-zeroness. If the
	 * overlay passed the charge for the frame, or the frame for the registers, this fails and
	 * names which one moved.
	 */
	@Test
	public void theFrameAndRegisterColumnsReportTheProgramNotTheCharge() throws Exception {
		opengpu.v2.stats.RenderStats.reset();
		byte[] blob = program(OcslWire.PROP_ANIM_X, 5.0f);
		IrValidator.Validated expected =
				IrValidator.validate(IrCodec.decode(blob, IrCodec.Source.TRANSIENT));

		SceneState s = sceneWith(info(1, blob));
		SceneNode n = node(s, 1, 0);
		n.animator = 1;
		n.attachedWorldTime = 100L;
		new AnimatorOverlay().evaluate(s, instant(200), OFFSET, true);

		assertEquals("the frame column must be the validator's laid-out frame, in floats",
				expected.frameWidth, opengpu.v2.stats.RenderStats.animatorFrameWidthMax);
		assertEquals("the register column must be the blob's declared register count",
				expected.program().declaredRegisters,
				opengpu.v2.stats.RenderStats.animatorRegistersMax);
		assertEquals("one program: each total IS its max, so a dropped accumulation reads 0",
				opengpu.v2.stats.RenderStats.animatorFrameWidthMax,
				(int) opengpu.v2.stats.RenderStats.animatorFrameWidthTotal);
		assertEquals(opengpu.v2.stats.RenderStats.animatorRegistersMax,
				(int) opengpu.v2.stats.RenderStats.animatorRegistersTotal);
	}

	/**
	 * THE FRAME HAS A FLOOR THE CHARGE CANNOT SEE — which is the whole reason this column was
	 * added rather than inferred from the charge.
	 *
	 * The validator types every builtin from the surface table and lays out EVERY typed builtin,
	 * read or not ({@code IrValidator}'s frame loop runs over {@code r < WORKING_BASE} on
	 * {@code types[r] != null}, and the types come from {@code builtinType} unconditionally). So
	 * the animator's readable builtin block is charged to the frame of the most trivial program
	 * in existence: the one-op program below charges a handful of ops and still occupies dozens
	 * of frame floats before it computes anything.
	 *
	 * A first version of this test predicted the opposite and asserted a vec4 program would lay
	 * out a wider frame than a scalar one; both read 34, because both take their value from the
	 * constant POOL and neither allocates a working register. The measurement is kept here as
	 * the assertion rather than the hypothesis: the floor is real, it is what a reader of the
	 * overlay line has to know to interpret "34/2048", and a future change that makes builtin
	 * layout lazy would show up here as a drop rather than passing silently.
	 */
	@Test
	public void everyAnimatorProgramPaysTheBuiltinBlockBeforeItComputesAnything() throws Exception {
		opengpu.v2.stats.RenderStats.reset();
		byte[] blob = program(OcslWire.PROP_ANIM_X, 5.0f);
		IrValidator.Validated v =
				IrValidator.validate(IrCodec.decode(blob, IrCodec.Source.TRANSIENT));

		SceneState s = sceneWith(info(1, blob));
		SceneNode n = node(s, 1, 0);
		n.animator = 1;
		n.attachedWorldTime = 100L;
		new AnimatorOverlay().evaluate(s, instant(200), OFFSET, true);

		assertTrue("a trivial program's charge is small: " + v.structuralOps,
				v.structuralOps < 10L);
		assertTrue("yet its frame already carries the builtin block, which is what makes the"
				+ " charge useless as a predictor of the frame cap: frame="
				+ opengpu.v2.stats.RenderStats.animatorFrameWidthMax,
				opengpu.v2.stats.RenderStats.animatorFrameWidthMax > 10 * v.structuralOps);
	}

	/**
	 * FRAME COUNTS FLOATS, REGISTERS COUNT REGISTERS — asserted as exact deltas, because that is
	 * the only form that separates the two columns.
	 *
	 * Both programs write the same vec4 property. One takes its value from the constant pool and
	 * allocates NO working register; the other builds it with a CONS4, which under the frozen
	 * eager rule ("one call, one op, one register") allocates exactly one register of width four.
	 * So the register column must move by 1 and the frame column by 4 — the same event measured
	 * in its own unit. Any wiring that reports one column in the other's unit fails one half.
	 */
	@Test
	public void oneExtraVec4RegisterIsPlusOneRegisterAndPlusFourFrameFloats() throws Exception {
		opengpu.v2.stats.RenderStats.reset();
		SceneState pooled = sceneWith(info(1, program(OcslWire.PROP_ANIM_TINT, 0.5f)));
		SceneNode pn = node(pooled, 1, 0);
		pn.animator = 1;
		pn.attachedWorldTime = 100L;
		new AnimatorOverlay().evaluate(pooled, instant(200), OFFSET, true);
		int pooledFrame = opengpu.v2.stats.RenderStats.animatorFrameWidthMax;
		int pooledRegs = opengpu.v2.stats.RenderStats.animatorRegistersMax;

		opengpu.v2.stats.RenderStats.reset();
		SceneState composed = sceneWith(info(1, composedTintProgram()));
		SceneNode cn = node(composed, 1, 0);
		cn.animator = 1;
		cn.attachedWorldTime = 100L;
		new AnimatorOverlay().evaluate(composed, instant(200), OFFSET, true);
		int composedFrame = opengpu.v2.stats.RenderStats.animatorFrameWidthMax;
		int composedRegs = opengpu.v2.stats.RenderStats.animatorRegistersMax;

		assertEquals("one CONS4 is ONE more declared register", 1,
				composedRegs - pooledRegs);
		assertEquals("and FOUR more frame floats — the same register measured in the frame's"
				+ " unit, which is the distinction the two columns exist to keep visible", 4,
				composedFrame - pooledFrame);
	}

	/** A tint program built with CONS4 — one working vec4 register, unlike the pooled helper. */
	private static byte[] composedTintProgram() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.out(OcslWire.PROP_ANIM_TINT, b.vec4(b.f(0.1f), b.f(0.2f), b.f(0.3f), b.f(0.4f)));
		return IrCodec.encode(b.build());
	}

	/**
	 * Across DIFFERENT programs the maxima take the larger and the totals take the sum.
	 *
	 * THE FIXTURES MUST DIFFER, AND THIS TEST PROVES THEY DO BEFORE RELYING ON IT. The first
	 * version of this test paired {@code program(PROP_ANIM_X, …)} with
	 * {@code program(PROP_ANIM_TINT, …)} and asserted the max equalled {@code Math.max} of the
	 * two — but BOTH take their value from the constant pool, so both lay out 34 frame floats
	 * and declare 112 registers, and {@code Math.max(34, 34)} is satisfied by last-wins,
	 * first-wins and a true maximum alike. A panel found it: the mutation "replace the
	 * if-greater with a plain assignment" survived the entire suite, on the one test whose
	 * message claimed to be watching for exactly that.
	 *
	 * So the pair is now pooled-vs-CONS4, which differ in both columns, the difference is
	 * ASSERTED rather than assumed, and the WIDER program is compiled FIRST — so a last-wins
	 * assignment leaves the smaller value behind and fails. Compiling in two separate passes
	 * rather than one scene keeps that order deterministic instead of dependent on node walk
	 * order.
	 */
	@Test
	public void theColumnsAccumulateAcrossProgramsAndTheMaximaTakeTheLarger() throws Exception {
		byte[] wide = composedTintProgram();
		byte[] narrow = program(OcslWire.PROP_ANIM_TINT, 0.5f);
		IrValidator.Validated wideV =
				IrValidator.validate(IrCodec.decode(wide, IrCodec.Source.TRANSIENT));
		IrValidator.Validated narrowV =
				IrValidator.validate(IrCodec.decode(narrow, IrCodec.Source.TRANSIENT));
		assertTrue("the fixtures must differ in frame or this test cannot see a max at all:"
				+ " wide=" + wideV.frameWidth + " narrow=" + narrowV.frameWidth,
				wideV.frameWidth > narrowV.frameWidth);
		assertTrue("and in registers, for the same reason",
				wideV.program().declaredRegisters > narrowV.program().declaredRegisters);

		opengpu.v2.stats.RenderStats.reset();

		SceneState first = sceneWith(info(1, wide));         // the WIDER one compiles first
		SceneNode a = node(first, 1, 0);
		a.animator = 1;
		a.attachedWorldTime = 100L;
		new AnimatorOverlay().evaluate(first, instant(200), OFFSET, true);

		SceneState second = sceneWith(info(1, narrow));      // then the narrower, no reset
		SceneNode b = node(second, 1, 0);
		b.animator = 1;
		b.attachedWorldTime = 100L;
		new AnimatorOverlay().evaluate(second, instant(200), OFFSET, true);

		assertEquals("two distinct programs, two compiles", 2L,
				opengpu.v2.stats.RenderStats.animatorProgramsCompiled);
		assertEquals("the frame total is the SUM, which is what the avg column divides",
				wideV.frameWidth + narrowV.frameWidth,
				(int) opengpu.v2.stats.RenderStats.animatorFrameWidthTotal);
		assertEquals("the frame max must survive a NARROWER program compiled after it — reading"
				+ " the narrow value here means the max is last-wins, not a maximum",
				wideV.frameWidth, opengpu.v2.stats.RenderStats.animatorFrameWidthMax);
		assertEquals(wideV.program().declaredRegisters + narrowV.program().declaredRegisters,
				(int) opengpu.v2.stats.RenderStats.animatorRegistersTotal);
		assertEquals("and the same for registers",
				wideV.program().declaredRegisters,
				opengpu.v2.stats.RenderStats.animatorRegistersMax);
	}

	// ---------------------------------------------- panel round 2: the states nothing produced

	/**
	 * tintFactor's THIRD state: an entry exists (the program wrote a transform) but tint was
	 * never written. The guard's two conjuncts are distinguishable only here — relaxing
	 * {@code c != null && c.wrote(TINT)} to {@code c != null} hands the renderer the Composed's
	 * zero-initialized float[4], and every transform-only animated node renders invisible.
	 * The panel proved that mutant survived all 703 tests: the two existing fixtures cover
	 * entry-with-tint and no-entry, and no test produced this state at a tintFactor call.
	 */
	@Test
	public void tintFactorFallsBackToRawWhenTheProgramWroteOnlyTransforms() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		SceneNode n = node(s, 1, 0);
		n.tint = 0x80402010;
		n.animator = 1;
		n.attachedWorldTime = 100L;
		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertNotNull("the transform write must have produced an entry", overlay.of(1));

		double[] out = new double[4];
		overlay.tintFactor(1, n.tint, out);
		assertEquals("R: the raw tint, NOT the entry's zero-initialized tint array — 0.0 here"
				+ " is every transform-only animated node rendering invisible",
				0x40 / 255.0, out[NodeFold.TINT_R], 1e-9);
		assertEquals(0x20 / 255.0, out[NodeFold.TINT_G], 1e-9);
		assertEquals(0x10 / 255.0, out[NodeFold.TINT_B], 1e-9);
		assertEquals(0x80 / 255.0, out[NodeFold.TINT_A], 1e-9);
	}

	/**
	 * Every transform property routes through its OWN base slot, its OWN Composed field and its
	 * OWN TRS slot, with all five distinct — so any crossed wire (baseOf case swap, applyProperty
	 * case swap, displayedBase assignment swap, overlayTransform transposition, a deleted scale
	 * branch) lands on a value no other property produces. The panel found Y, SY and ROT2D were
	 * never composed by any test: three of five routes were pinned by nothing.
	 */
	@Test
	public void everyTransformPropertyRoutesThroughItsOwnSlot() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.out(OcslWire.PROP_ANIM_X, b.f(10.0f));
		b.out(OcslWire.PROP_ANIM_Y, b.f(20.0f));
		b.out(OcslWire.PROP_ANIM_ROT2D, b.f(0.5f));
		b.out(OcslWire.PROP_ANIM_SX, b.f(3.0f));
		b.out(OcslWire.PROP_ANIM_SY, b.f(5.0f));
		SceneState s = sceneWith(info(1, IrCodec.encode(b.build())));
		SceneNode n = node(s, 1, 0);
		n.x = 1.0;
		n.y = 2.0;
		n.rot = 0.25;
		n.sx = 2.0;
		n.sy = 4.0;
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		AnimatorOverlay.Composed c = overlay.of(1);
		assertEquals("x: 1 + 10", 11.0, c.x, 1e-6);
		assertEquals("y: 2 + 20", 22.0, c.y, 1e-6);
		assertEquals("rot: 0.25 + 0.5", 0.75, c.rot, 1e-6);
		assertEquals("sx: 2 * 3 (multiply, not add)", 6.0, c.sx, 1e-6);
		assertEquals("sy: 4 * 5", 20.0, c.sy, 1e-6);

		double[] trs = { -1.0, -1.0, -1.0, -1.0, -1.0 };
		overlay.overlayTransform(1, trs);
		assertEquals(11.0, trs[NodeFold.TRS_X], 1e-6);
		assertEquals(22.0, trs[NodeFold.TRS_Y], 1e-6);
		assertEquals(0.75, trs[NodeFold.TRS_ROT], 1e-6);
		assertEquals("a transposed or deleted scale branch cannot produce 6 here",
				6.0, trs[NodeFold.TRS_SX], 1e-6);
		assertEquals(20.0, trs[NodeFold.TRS_SY], 1e-6);
	}

	/**
	 * An ANIMATED parent under interpolation: the child's parent register carries the parent's
	 * value composed over the parent's DISPLAYED base — the combination (both animated, base
	 * mid-glide) that neither the parent-fallback test nor the 3.3a composed-parent test
	 * reaches.
	 */
	@Test
	public void anAnimatedParentsRegistersComposeOverItsDisplayedBase() throws Exception {
		OcslBuilder child = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		child.outAbsolute(OcslWire.PROP_ANIM_X, child.builtin(SurfaceTable.REG_ANIM_PARENT_X));
		SceneState s = sceneWith(
				info(1, program(OcslWire.PROP_ANIM_X, 100.0f)),
				info(2, IrCodec.encode(child.build())));
		SceneNode parent = node(s, 1, 0);
		parent.animator = 1;
		parent.attachedWorldTime = 100L;
		SceneNode rider = node(s, 2, 1);
		rider.animator = 2;
		rider.attachedWorldTime = 100L;
		NodeInterpolator interp = midFlightX(s, parent);

		long now = localShowing(T0 + 0.5);
		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, interp.renderInstant(now), OFFSET, true, interp, now);

		assertEquals("displayed 5 + animator 100 = 105; 110 means the parent composed over its"
				+ " raw base, and 5 means the parent's animator never reached the register",
				105.0, overlay.of(2).x, 1e-4);
	}

	/**
	 * The parent TINT register, both branches per lane — the one tint surface the panel found
	 * read by no test anywhere: an animated parent's composed tint, and an unanimated parent's
	 * raw unpack, each with FOUR DISTINCT channel values so a lane swap in
	 * bindParentProperties cannot hide.
	 */
	@Test
	public void theParentTintRegisterCarriesBothBranchesPerLane() throws Exception {
		OcslBuilder tintB = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		tintB.out(OcslWire.PROP_ANIM_TINT, tintB.constant(0.1f, 0.2f, 0.3f, 0.4f));
		OcslBuilder readB = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		readB.outAbsolute(OcslWire.PROP_ANIM_TINT,
				readB.builtin(SurfaceTable.REG_ANIM_PARENT_TINT));
		SceneState s = sceneWith(
				info(1, IrCodec.encode(tintB.build())),
				info(2, IrCodec.encode(readB.build())));
		SceneNode litParent = node(s, 1, 0);
		litParent.tint = 0x80808080;
		litParent.animator = 1;
		litParent.attachedWorldTime = 100L;
		SceneNode litChild = node(s, 2, 1);
		litChild.animator = 2;
		litChild.attachedWorldTime = 100L;
		SceneNode plainParent = node(s, 3, 0);
		plainParent.tint = 0x80402010;
		SceneNode plainChild = node(s, 4, 3);
		plainChild.animator = 2;
		plainChild.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);

		float[] viaAnimated = overlay.of(2).tint;
		assertEquals("R from the animated parent's COMPOSED tint", 0.1f, viaAnimated[0], 1e-6f);
		assertEquals(0.2f, viaAnimated[1], 1e-6f);
		assertEquals(0.3f, viaAnimated[2], 1e-6f);
		assertEquals(0.4f, viaAnimated[3], 1e-6f);

		float[] viaRaw = overlay.of(4).tint;
		assertEquals("R from the unanimated parent's raw unpack", 0x40 / 255.0f, viaRaw[0], 1e-6f);
		assertEquals(0x20 / 255.0f, viaRaw[1], 1e-6f);
		assertEquals(0x10 / 255.0f, viaRaw[2], 1e-6f);
		assertEquals(0x80 / 255.0f, viaRaw[3], 1e-6f);
	}

	/**
	 * The per-NODE counter, on a fixture where per-node and per-scene disagree.
	 *
	 * ANIM-16's budget calibrates against a per-node constant, and until this counter existed
	 * the only animator instrument was per-SCENE — so every per-node figure in circulation was a
	 * scene total divided by a node count read off a Lua script. A single-node fixture would be
	 * the vacuous version of this test: 1 node in 1 scene makes per-node and per-scene counting
	 * indistinguishable. Three evaluated nodes make them differ by 3x.
	 *
	 * The population is deliberately mixed, and the exclusions are the assertion: 3 attached and
	 * evaluable, 2 unattached, 1 DANGLING (attached to a freed program). The dangling node is
	 * skipped before any VM runs, so counting it would inflate the denominator with a node that
	 * costs nothing and make the per-node mean read LOW — the direction that would make a budget
	 * too generous.
	 */
	@Test
	public void theNodeCounterCountsEvaluatedNodesNotScenesAndNotDanglingAttachments()
			throws Exception {
		opengpu.v2.stats.RenderStats.reset();
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		for (int id = 1; id <= 3; id++) {           // three evaluable nodes, one program
			SceneNode n = node(s, id, 0);
			n.animator = 1;
			n.attachedWorldTime = 100L;
		}
		node(s, 4, 0);                              // unattached
		node(s, 5, 0);                              // unattached
		SceneNode dangling = node(s, 6, 0);
		dangling.animator = 999;                    // no such program — ANIM-17's legal dangle
		dangling.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);

		assertEquals("three nodes ran a VM — 1 would mean the counter is per-SCENE, and 4 would"
				+ " mean the dangling attachment was counted despite costing nothing",
				3L, opengpu.v2.stats.RenderStats.animatorNodesEvaluated);

		overlay.evaluate(s, instant(201), OFFSET, true);
		assertEquals("it accumulates across frames — a second frame adds three more",
				6L, opengpu.v2.stats.RenderStats.animatorNodesEvaluated);
	}

	/**
	 * THE CRASH REGRESSION (2026-08-21): a wrong-stage attachment is skipped, not evaluated.
	 *
	 * This state is constructed directly because that is exactly what it is in the wild — a
	 * save written before ServerScene's stage gate existed, where nothing refused attaching a
	 * legal pixel program to a node. Before the vmFor defence, evaluate() THREW here:
	 * bindClock's vm.set on the animator registers has no frame slot in a pixel-stage program,
	 * and the IllegalArgumentException went out through the render tick. The renderer must not
	 * be the thing that takes a client down over scene data.
	 */
	@Test
	public void aWrongStageAttachmentIsSkippedNotEvaluated() throws Exception {
		OcslBuilder pixel = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		pixel.out(OcslWire.PROP_COLOR, pixel.constant(1f, 1f, 1f, 1f));
		byte[] blob = IrCodec.encode(pixel.build());
		long charge = IrValidator.validate(
				IrCodec.decode(blob, IrCodec.Source.TRANSIENT)).structuralOps;
		SceneState s = new SceneState();
		s.programs.put(Integer.valueOf(1),
				new ProgramInfo(1, OcslWire.STAGE_PIXEL_MATERIAL, blob, (int) charge));
		s.nextProgramId = 2;
		s.creationWorldTime = 100L;
		s.worldTimeAnchor = 100L;
		SceneNode n = node(s, 1, 0);
		n.x = 7.0;
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);   // must NOT throw
		assertNull("a wrong-stage attachment produces no entry — the node renders at its base",
				overlay.of(1));
	}

	/**
	 * reset() forgets BROKEN verdicts too. If the broken set survived an epoch change, the new
	 * epoch's perfectly valid program would be skipped forever under its recycled id — vmFor
	 * consults broken before compiling, so a stale verdict is a permanent veto.
	 */
	@Test
	public void resetForgetsBrokenVerdictsForANewEpoch() throws Exception {
		AnimatorOverlay overlay = new AnimatorOverlay();
		SceneState s1 = new SceneState();
		s1.programs.put(Integer.valueOf(1), new ProgramInfo(
				1, OcslWire.STAGE_ANIMATOR, new byte[] { 1, 2, 3 }, 1));
		s1.nextProgramId = 2;
		s1.creationWorldTime = 100L;
		s1.worldTimeAnchor = 100L;
		SceneNode n1 = node(s1, 1, 0);
		n1.animator = 1;
		n1.attachedWorldTime = 100L;
		overlay.evaluate(s1, instant(200), OFFSET, true);
		assertNull("the corrupt blob must have produced nothing", overlay.of(1));

		SceneState s2 = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 7.0f)));
		SceneNode n2 = node(s2, 1, 0);
		n2.x = 1.0;
		n2.animator = 1;
		n2.attachedWorldTime = 100L;
		overlay.reset();
		overlay.evaluate(s2, instant(200), OFFSET, true);
		assertNotNull("a surviving broken verdict permanently vetoes the new epoch's valid"
				+ " program under the recycled id", overlay.of(1));
		assertEquals(8.0, overlay.of(1).x, 1e-6);
	}

	/**
	 * The raw-base overload CLEARS the interpolation source. Every other test constructs a
	 * fresh overlay, so a mutant that kept the previous frame's interpolator on the 4-arg path
	 * survived the whole suite — latent today (the one production caller always passes its
	 * interpolator), and exactly the kind of latent that detonates when a second caller
	 * arrives trusting the overload's own javadoc.
	 */
	@Test
	public void theRawBaseOverloadClearsTheInterpolationSource() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 100.0f)));
		SceneNode n = node(s, 1, 0);
		n.animator = 1;
		n.attachedWorldTime = 100L;
		NodeInterpolator interp = midFlightX(s, n);
		long now = localShowing(T0 + 0.5);

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, interp.renderInstant(now), OFFSET, true, interp, now);
		assertEquals(105.0, overlay.of(1).x, 1e-4);

		overlay.evaluate(s, instant(200), OFFSET, true);
		assertEquals("the SAME overlay on the 4-arg path composes over raw 10 — 105 means the"
				+ " previous frame's interpolator stuck", 110.0, overlay.of(1).x, 1e-4);
	}

	// ---------------------------------------------------------------- the hold seam (ANIM-16)

	/** Holds every node it is asked about — the budget's answer, without the budget. */
	private static final AnimatorOverlay.HoldPolicy HOLD_ALL = new AnimatorOverlay.HoldPolicy() {
		public boolean hold(int nodeId) {
			return true;
		}
	};

	/** `x += time`: a RELATIVE write whose raw output differs on every frame. */
	private static byte[] relativeClockProgram() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.out(OcslWire.PROP_ANIM_X, b.builtin(SurfaceTable.REG_TIME));
		return IrCodec.encode(b.build());
	}

	/**
	 * THE TEST THE WHOLE INCREMENT EXISTS FOR: a held node freezes its ANIMATOR and not its BASE.
	 *
	 * Four outcomes are separated by one number, which is why the program is {@code x += time}
	 * rather than a constant — against a constant, holding and evaluating give the same answer and
	 * the test would pass with the mechanism entirely absent:
	 *
	 * <ul>
	 *   <li><b>105</b> — correct: the base moved to 100, the animator's own 5.0 is frozen.</li>
	 *   <li><b>106</b> — the node was evaluated; the policy was ignored.</li>
	 *   <li><b>12</b> — the COMPOSED value was reused. This is the defect the increment removes,
	 *       and in-game it looks exactly like a broken evaluator: the node stops dead.</li>
	 *   <li><b>100</b> — the entry was dropped and the animator vanished.</li>
	 * </ul>
	 */
	@Test
	public void aHeldNodeFreezesItsAnimatorAndNotItsBase() throws Exception {
		SceneState s = sceneWith(info(1, relativeClockProgram()));
		SceneNode n = node(s, 1, 0);
		n.x = 7.0;
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertEquals("frame one: base 7 + time 5", 12.0, overlay.of(1).x, 1e-6);

		// The server moves the node, and the frame that follows declines to run its program.
		n.x = 100.0;
		overlay.holdPolicy(HOLD_ALL);
		overlay.evaluate(s, instant(220), OFFSET, true);

		assertEquals("a held node must recompose its FROZEN output (5.0) over the CURRENT base"
				+ " (100): 106 means it evaluated anyway, 12 means the composed value was reused"
				+ " and server motion was discarded, 100 means the animator was dropped",
				105.0, overlay.of(1).x, 1e-6);
	}

	/**
	 * A held node keeps tracking a base that is still MOVING, frame after frame.
	 *
	 * The single-step test above cannot see a recomposition that runs once and then latches, which
	 * is a plausible implementation of "recompose on the frame the hold begins".
	 */
	@Test
	public void aHeldNodeKeepsTrackingItsBaseAcrossManyHeldFrames() throws Exception {
		SceneState s = sceneWith(info(1, relativeClockProgram()));
		SceneNode n = node(s, 1, 0);
		n.x = 0.0;
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);   // raw = 5.0, base 0 -> 5
		overlay.holdPolicy(HOLD_ALL);
		for (int frame = 1; frame <= 4; frame++) {
			n.x = frame * 10.0;
			overlay.evaluate(s, instant(200 + 20L * frame), OFFSET, true);
			assertEquals("held frame " + frame + ": base moved to " + (frame * 10.0)
					+ ", the frozen 5.0 rides on top of it",
					frame * 10.0 + 5.0, overlay.of(1).x, 1e-6);
		}
	}

	/**
	 * ABSOLUTE writes hold too, and their held value must NOT drift with the base.
	 *
	 * The mirror of the test above, and it exists because the relative case is the one that makes
	 * recomposition look necessary: an implementation that "fixed" holding by always adding the
	 * frozen output to the current base would pass every relative test here and silently convert
	 * absolute writes into relative ones — the double-apply bug OP_OUT_ABS exists to prevent,
	 * arriving through the back door of the degradation path.
	 */
	@Test
	public void aHeldAbsoluteWriteStaysAbsolute() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.outAbsolute(OcslWire.PROP_ANIM_X, b.builtin(SurfaceTable.REG_TIME));
		SceneState s = sceneWith(info(1, IrCodec.encode(b.build())));
		SceneNode n = node(s, 1, 0);
		n.x = 7.0;
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertEquals("absolute: the base is replaced", 5.0, overlay.of(1).x, 1e-6);

		n.x = 100.0;
		overlay.holdPolicy(HOLD_ALL);
		overlay.evaluate(s, instant(220), OFFSET, true);
		assertEquals("still 5.0 — 105 would mean the held value was composed relatively, which is"
				+ " the double-apply bug reintroduced by the hold path", 5.0,
				overlay.of(1).x, 1e-6);
	}

	/**
	 * Holding is reusing work, so it cannot happen before any work exists.
	 *
	 * Without this rule a node attached while the budget is already saturated would be skipped on
	 * its first frame, hold nothing, and show its server value — a node that never starts moving
	 * at all, which reads in-game as the attach having failed.
	 */
	@Test
	public void aNodeWithNothingToHoldEvaluatesAnyway() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		SceneNode n = node(s, 1, 0);
		n.x = 7.0;
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.holdPolicy(HOLD_ALL);            // holding from the very first frame
		overlay.evaluate(s, instant(200), OFFSET, true);

		assertNotNull("the first frame has no previous output to reuse", overlay.of(1));
		assertEquals(12.0, overlay.of(1).x, 1e-6);
	}

	/**
	 * The guard's predicate and the evaluator's must be the SAME question, so this asserts they
	 * are the same method's answer against the same map — and that it flips only once there is
	 * something to hold.
	 */
	@Test
	public void wouldEvaluateFollowsTheHoldPolicyOnlyOnceThereIsAnOutputToHold() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		SceneNode n = node(s, 1, 0);
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.holdPolicy(HOLD_ALL);
		assertTrue("nothing evaluated yet: the guard must not let the scene short-circuit",
				overlay.wouldEvaluate(s));

		overlay.evaluate(s, instant(200), OFFSET, true);
		assertFalse("now there is an output to hold, so the scene owes no animator work",
				overlay.wouldEvaluate(s));

		overlay.holdPolicy(AnimatorOverlay.EVALUATE_ALWAYS);
		assertTrue("and the default policy always owes work", overlay.wouldEvaluate(s));
	}

	/**
	 * {@code wouldEvaluate} must look the node's entry up by NODE id, not by program id.
	 *
	 * Every other test that reaches it uses node 1 attached to program 1, where the two keys are
	 * indistinguishable — so keying by {@code node.animator} passed the whole suite while making
	 * the guard and the loop answer different questions, which is the single failure the shared
	 * predicate exists to prevent. Node id and program id are deliberately far apart here.
	 */
	@Test
	public void wouldEvaluateKeysTheMapByNodeIdNotProgramId() throws Exception {
		SceneState s = sceneWith(info(7, program(OcslWire.PROP_ANIM_X, 5.0f)));
		SceneNode n = node(s, 3, 0);
		n.animator = 7;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.holdPolicy(HOLD_ALL);
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertNotNull("node 3 has an entry", overlay.of(3));

		assertFalse("node 3 is held, so the scene owes no animator work — true here means the"
				+ " lookup used the program id (7), found nothing, and disagreed with the loop",
				overlay.wouldEvaluate(s));
	}

	/**
	 * THE NO-BEHAVIOUR-CHANGE CLAIM, asserted rather than argued.
	 *
	 * The guard swapped {@code hasAttachedAnimator()} for {@code wouldEvaluate()}. Under the
	 * shipped policy those must agree on every scene shape, INCLUDING the ones neither predicate
	 * looks past — a dangling attachment and a node whose blob will not decode both still count as
	 * work today, and quietly making them stop counting would be a behaviour change smuggled in
	 * under a refactor.
	 */
	@Test
	public void underTheShippedPolicyWouldEvaluateMatchesHasAttachedAnimator() throws Exception {
		SceneState empty = sceneWith();
		SceneState nodesOnly = sceneWith();
		node(nodesOnly, 1, 0);
		node(nodesOnly, 2, 0);

		SceneState attached = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		node(attached, 1, 0);
		SceneNode last = node(attached, 2, 0);
		last.animator = 1;
		last.attachedWorldTime = 100L;

		SceneState dangling = sceneWith();          // attachment whose program was freed
		SceneNode orphan = node(dangling, 1, 0);
		orphan.animator = 42;
		orphan.attachedWorldTime = 100L;

		SceneState broken = sceneWith(new ProgramInfo(1, OcslWire.STAGE_ANIMATOR,
				new byte[] { 9, 9, 9, 9 }, 1));
		SceneNode bad = node(broken, 1, 0);
		bad.animator = 1;
		bad.attachedWorldTime = 100L;

		SceneState[] shapes = { empty, nodesOnly, attached, dangling, broken };
		String[] names = { "empty", "nodes but no animator", "attached", "dangling", "bad blob" };
		for (int i = 0; i < shapes.length; i++) {
			// ONE OVERLAY PER SHAPE. These fixtures reuse program id 1 for different code, which
			// is precisely the case the VM cache is documented not to survive (ids are unique
			// within a scene incarnation, not across them) — sharing an overlay would compile the
			// first shape's program and quietly run it for the others, so the "bad blob" row
			// would never reach the decode it exists to cover.
			AnimatorOverlay overlay = new AnimatorOverlay();
			// Evaluated first so each shape's entries exist. NOTE what this does and does not
			// show: under EVALUATE_ALWAYS `runsThisFrame` short-circuits before it reads the
			// entry, so the map is not what makes these rows agree. The rows that carry weight
			// are `dangling` and `bad blob` — a wouldEvaluate that consulted state.programs or
			// tried to decode would disagree there. That the policy is consulted at all is
			// pinned by wouldEvaluateFollowsTheHoldPolicy..., and the key it uses by
			// wouldEvaluateKeysTheMapByNodeIdNotProgramId.
			overlay.evaluate(shapes[i], instant(200), OFFSET, true);
			assertEquals(names[i], shapes[i].hasAttachedAnimator(),
					overlay.wouldEvaluate(shapes[i]));
		}
	}

	/**
	 * Detach still snaps. This used to be free — {@code evaluate} cleared the map every frame —
	 * and the map is persistent now, so the property has to be re-established by the sweep.
	 *
	 * HOLD_ALL is installed deliberately: it is the configuration in which a surviving entry would
	 * go on being recomposed forever, so a missing sweep shows up here as a node that keeps its
	 * animation after detach rather than merely one stale frame.
	 */
	@Test
	public void detachDropsAHeldEntry() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		SceneNode n = node(s, 1, 0);
		n.x = 7.0;
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.holdPolicy(HOLD_ALL);
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertNotNull(overlay.of(1));

		n.animator = 0;
		overlay.evaluate(s, instant(220), OFFSET, true);
		assertNull("a detached node must lose its entry on the very next pass", overlay.of(1));
		assertTrue(overlay.isEmpty());
	}

	/** Same property for a node that is REMOVED outright rather than detached. */
	@Test
	public void removingANodeDropsAHeldEntry() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		SceneNode n = node(s, 1, 0);
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.holdPolicy(HOLD_ALL);
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertNotNull(overlay.of(1));

		s.nodes.remove(Integer.valueOf(1));
		overlay.evaluate(s, instant(220), OFFSET, true);
		assertTrue("a removed node's entry must not outlive it", overlay.isEmpty());
	}

	/**
	 * Losing the clock must still fail CLOSED, which the blanket clear used to guarantee.
	 *
	 * The failure this closes is worse than a stale frame: a persistent map plus recomposition
	 * means a clockless scene would go on animating smoothly over a moving base, which looks
	 * entirely healthy and is running on an epoch nobody can place.
	 */
	@Test
	public void losingTheClockDropsHeldEntries() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		SceneNode n = node(s, 1, 0);
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.holdPolicy(HOLD_ALL);
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertNotNull(overlay.of(1));

		overlay.evaluate(s, instant(220), OFFSET, false);
		assertTrue("no clock, no animation — including no held animation", overlay.isEmpty());
	}

	/**
	 * A held PARENT still feeds its child a moving value.
	 *
	 * The parent registers read {@code byNode}, so a held parent that stopped tracking its base
	 * would drag every child with it — the one place a single held node's error multiplies.
	 */
	@Test
	public void aChildReadsAHeldParentsRecomposedValue() throws Exception {
		OcslBuilder child = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		child.outAbsolute(OcslWire.PROP_ANIM_X, child.builtin(SurfaceTable.REG_ANIM_PARENT_X));
		SceneState s = sceneWith(info(1, relativeClockProgram()),
				info(2, IrCodec.encode(child.build())));

		SceneNode parent = node(s, 1, 0);
		parent.x = 0.0;
		parent.animator = 1;
		parent.attachedWorldTime = 100L;
		SceneNode kid = node(s, 2, 1);
		kid.animator = 2;
		kid.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertEquals("parent: base 0 + time 5", 5.0, overlay.of(1).x, 1e-6);
		assertEquals("child mirrors the parent", 5.0, overlay.of(2).x, 1e-6);

		// The parent holds; the child keeps evaluating and must see the parent MOVE.
		parent.x = 30.0;
		overlay.holdPolicy(new AnimatorOverlay.HoldPolicy() {
			public boolean hold(int nodeId) {
				return nodeId == 1;
			}
		});
		overlay.evaluate(s, instant(220), OFFSET, true);
		assertEquals("held parent: frozen 5.0 over the new base 30", 35.0, overlay.of(1).x, 1e-6);
		assertEquals("the child must read 35, not the 5 the parent had when it last ran",
				35.0, overlay.of(2).x, 1e-6);
	}

	/**
	 * The two node counters partition the attached nodes: every node is charged to exactly one of
	 * them, and a held node never touches the evaluated count that the per-node instrument divides
	 * by. Getting that wrong makes the measured per-node cost read LOW by exactly the hold rate,
	 * which is the number the budget is calibrated against.
	 */
	@Test
	public void heldNodesAreCountedSeparatelyFromEvaluatedOnes() throws Exception {
		// A MIXED POPULATION, matching what the evaluated-counter's own test builds. With three
		// identical evaluable nodes, "charged nowhere else" was untested: charging the dangling
		// and unattached paths to animatorNodesHeld would have read exactly the same. The sibling
		// counter learned this lesson already; this is the mirror it was missing.
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		for (int id = 1; id <= 3; id++) {
			SceneNode n = node(s, id, 0);
			n.animator = 1;
			n.attachedWorldTime = 100L;
		}
		node(s, 4, 0);                                   // unattached: costs nothing, counts nothing
		SceneNode dangling = node(s, 5, 0);
		dangling.animator = 99;                          // program never existed
		dangling.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		try {
			opengpu.v2.stats.RenderStats.reset();
			overlay.evaluate(s, instant(200), OFFSET, true);
			assertEquals("first pass: nothing to hold, and only the three evaluable nodes count",
					3L, opengpu.v2.stats.RenderStats.animatorNodesEvaluated);
			assertEquals(0L, opengpu.v2.stats.RenderStats.animatorNodesHeld);

			overlay.holdPolicy(new AnimatorOverlay.HoldPolicy() {
				public boolean hold(int nodeId) {
					return nodeId != 2;
				}
			});
			overlay.evaluate(s, instant(220), OFFSET, true);
			assertEquals("only node 2 ran a VM on the second pass", 4L,
					opengpu.v2.stats.RenderStats.animatorNodesEvaluated);
			assertEquals("nodes 1 and 3 were held. The unattached node 4 and the dangling node 5"
					+ " are charged to NEITHER counter — they cost nothing, and inflating the"
					+ " denominator with them is how the per-node mean reads low",
					2L, opengpu.v2.stats.RenderStats.animatorNodesHeld);
		} finally {
			// These are process-wide statics; a failure above must not hand dirty counters to
			// whatever runs next.
			opengpu.v2.stats.RenderStats.reset();
		}
	}

	/**
	 * EVERY tracking property, against a base that MOVES — not just x.
	 *
	 * This replaces a differential test that compared a held overlay against a fresh one over
	 * constant programs and unchanged bases. That comparison could not fail: the two paths share
	 * {@code composeStored}, so a broken composition rule moved both sides together, and with the
	 * base standing still the held answer equalled the fresh one whatever recomposition did — an
	 * empty {@code recompose} body passed it.
	 *
	 * The gap it left was bigger than itself. Every other hold test moves only x, so narrowing
	 * {@code recompose}'s loop to x alone left y, sx, sy and rot2d silently frozen — four of the
	 * five transform routes, each one the "node stops dead" failure this class is written to
	 * avoid. Absolute values, one per property, over four moved bases.
	 */
	@Test
	public void everyTrackingPropertyFollowsAMovingBaseUnderHold() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		Expr t = b.builtin(SurfaceTable.REG_TIME);
		b.out(OcslWire.PROP_ANIM_Y, t);
		b.out(OcslWire.PROP_ANIM_SX, t);
		b.out(OcslWire.PROP_ANIM_SY, t);
		b.out(OcslWire.PROP_ANIM_ROT2D, t);
		SceneState s = sceneWith(info(1, IrCodec.encode(b.build())));
		SceneNode n = node(s, 1, 0);
		n.y = 1.0;
		n.sx = 2.0;
		n.sy = 3.0;
		n.rot = 0.5;
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);   // time = 5.0
		AnimatorOverlay.Composed first = overlay.of(1);
		assertEquals("y adds", 6.0, first.y, 1e-6);
		assertEquals("sx multiplies", 10.0, first.sx, 1e-6);
		assertEquals("sy multiplies", 15.0, first.sy, 1e-6);
		assertEquals("rot adds", 5.5, first.rot, 1e-6);

		n.y = 100.0;
		n.sx = 4.0;
		n.sy = 5.0;
		n.rot = 2.0;
		overlay.holdPolicy(HOLD_ALL);
		overlay.evaluate(s, instant(220), OFFSET, true);   // time would be 6.0; frozen at 5.0

		AnimatorOverlay.Composed held = overlay.of(1);
		assertEquals("held y: new base 100 + frozen 5", 105.0, held.y, 1e-6);
		assertEquals("held sx: new base 4 * frozen 5", 20.0, held.sx, 1e-6);
		assertEquals("held sy: new base 5 * frozen 5", 25.0, held.sy, 1e-6);
		assertEquals("held rot: new base 2 + frozen 5", 7.0, held.rot, 1e-6);
	}

	/**
	 * A held node recomposes over the INTERPOLATED base, not the raw server field.
	 *
	 * The fresh path has had this pinned since 3.3b ({@code aRelativeWriteComposesOverTheInterpolatedBase}),
	 * and the hold path had nothing: every other hold test calls the 4-arg overload, so
	 * {@code interp} is null in all of them and {@code displayedBase}'s interpolating branch never
	 * runs on a held frame. Replacing {@code displayedBase} inside {@code recompose} with a raw
	 * field read passed the entire suite while reintroducing, for held nodes only, the 20 Hz
	 * stepping the interpolator exists to remove.
	 *
	 * A constant program is correct HERE — the question is which base the recomposition reads, and
	 * the three candidate answers are distinct: 107.5 interpolated, 110.0 raw, 102.5 latched at the
	 * first frame's base.
	 */
	@Test
	public void aHeldNodeRecomposesOverTheInterpolatedBase() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 100.0f)));
		SceneNode n = node(s, 1, 0);
		n.animator = 1;
		n.attachedWorldTime = 100L;
		NodeInterpolator interp = midFlightX(s, n);

		AnimatorOverlay overlay = new AnimatorOverlay();
		long quarter = localShowing(T0 + 0.25);
		overlay.evaluate(s, interp.renderInstant(quarter), OFFSET, true, interp, quarter);
		assertEquals("a quarter through the glide the displayed base is 2.5",
				102.5, overlay.of(1).x, 1e-4);

		overlay.holdPolicy(HOLD_ALL);
		long threeQuarters = localShowing(T0 + 0.75);
		overlay.evaluate(s, interp.renderInstant(threeQuarters), OFFSET, true, interp,
				threeQuarters);
		assertEquals("held, but the glide continues: base 7.5 + 100. 110 means recomposition read"
				+ " the raw field, 102.5 means it never re-read the base at all",
				107.5, overlay.of(1).x, 1e-4);
	}

	/**
	 * THE MID-LOOP REGRESSION. A child must never compose against a parent entry the SAME pass has
	 * already abandoned.
	 *
	 * The parent registers read {@code byNode} during the loop, while the sweep runs after it. When
	 * the map became persistent, a parent that hit a {@code continue} — detached here — left its
	 * fully populated entry sitting in the map for its child to find, so the child was drawn from
	 * an animator output the same pass had just discarded, while the renderer drew the parent at
	 * its server base. One frame of internal inconsistency of the size of the parent's whole
	 * animated offset, at the exact moment of detach, on the canonical parent/child use.
	 *
	 * Needs NO hold policy: this is live under the shipped one. The pre-existing detach tests all
	 * assert {@code of(parent) == null} AFTER the pass returns, which is the half that never broke.
	 */
	@Test
	public void detachingAParentDoesNotLeaveItsChildComposingAgainstTheOldOutput()
			throws Exception {
		OcslBuilder child = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		child.outAbsolute(OcslWire.PROP_ANIM_X, child.builtin(SurfaceTable.REG_ANIM_PARENT_X));
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)),
				info(2, IrCodec.encode(child.build())));

		SceneNode parent = node(s, 1, 0);
		parent.x = 10.0;
		parent.animator = 1;
		parent.attachedWorldTime = 100L;
		SceneNode kid = node(s, 2, 1);
		kid.animator = 2;
		kid.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertEquals("parent composes 10 + 5", 15.0, overlay.of(1).x, 1e-6);
		assertEquals("child mirrors the parent's composed value", 15.0, overlay.of(2).x, 1e-6);

		parent.animator = 0;                       // detach, mid-session
		overlay.evaluate(s, instant(220), OFFSET, true);

		assertNull("the parent's entry is gone by the end of the pass", overlay.of(1));
		assertEquals("and the child, evaluated LATER IN THAT SAME PASS, must already see the"
				+ " parent's base: 15 means it read the entry the pass had abandoned",
				10.0, overlay.of(2).x, 1e-6);
	}

	/** The same mid-loop rule for a parent whose PROGRAM was freed rather than detached. */
	@Test
	public void freeingAParentsProgramDoesNotLeaveItsChildComposingAgainstTheOldOutput()
			throws Exception {
		OcslBuilder child = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		child.outAbsolute(OcslWire.PROP_ANIM_X, child.builtin(SurfaceTable.REG_ANIM_PARENT_X));
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)),
				info(2, IrCodec.encode(child.build())));

		SceneNode parent = node(s, 1, 0);
		parent.x = 10.0;
		parent.animator = 1;
		parent.attachedWorldTime = 100L;
		SceneNode kid = node(s, 2, 1);
		kid.animator = 2;
		kid.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertEquals(15.0, overlay.of(2).x, 1e-6);

		// ANIM-17 rules this legal: the attachment stays, the program does not.
		s.programs.remove(Integer.valueOf(1));
		overlay.evaluate(s, instant(220), OFFSET, true);

		assertNull(overlay.of(1));
		assertEquals("a dangling parent reads as its base to its child, immediately",
				10.0, overlay.of(2).x, 1e-6);
	}

	/**
	 * THE THIRD MIRROR. Same mid-loop rule for a parent whose program is UNDECODABLE.
	 *
	 * The loop abandons a node on three conditions — detached, dangling, undecodable — and the
	 * removal was added to all three while only two got a test. A mutation deleting the third
	 * survived the suite, which is the reliable signature of a one-sided fix: the rule was applied
	 * symmetrically and asserted asymmetrically.
	 *
	 * REACHING IT TAKES A SWAP, and working that out is what makes the case real rather than
	 * hypothetical. `vmFor` caches compiled programs by id, so a node that has already evaluated
	 * cannot reach the undecodable branch by having ITS program go bad — the cache answers first.
	 * It reaches it by being re-attached to a DIFFERENT program whose blob does not decode, which
	 * leaves a populated entry from the old program sitting in the map at the moment the loop
	 * gives up on the node.
	 */
	@Test
	public void aParentSwappedOntoAnUndecodableProgramDoesNotLeaveItsChildComposingAgainstIt()
			throws Exception {
		OcslBuilder child = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		child.outAbsolute(OcslWire.PROP_ANIM_X, child.builtin(SurfaceTable.REG_ANIM_PARENT_X));
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)),
				info(2, IrCodec.encode(child.build())));
		// Damaged in transit or on disk — the server validated it before storing, so this is the
		// state the evaluator must survive rather than one it can refuse.
		s.programs.put(Integer.valueOf(3), new ProgramInfo(3, OcslWire.STAGE_ANIMATOR,
				new byte[] { 9, 9, 9, 9 }, 1));

		SceneNode parent = node(s, 1, 0);
		parent.x = 10.0;
		parent.animator = 1;
		parent.attachedWorldTime = 100L;
		SceneNode kid = node(s, 2, 1);
		kid.animator = 2;
		kid.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertEquals(15.0, overlay.of(2).x, 1e-6);

		parent.animator = 3;                     // swapped onto the damaged blob
		overlay.evaluate(s, instant(220), OFFSET, true);

		assertNull("an undecodable program leaves no entry behind", overlay.of(1));
		assertEquals("and the child must see the parent's base in that same pass",
				10.0, overlay.of(2).x, 1e-6);
	}

	/**
	 * A HELD node whose program was freed loses its output — the third case the class javadoc
	 * names, and the one the detach and node-removal tests do not reach.
	 */
	@Test
	public void freeingAHeldNodesProgramDropsItsEntry() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		SceneNode n = node(s, 1, 0);
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.holdPolicy(HOLD_ALL);
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertNotNull(overlay.of(1));

		s.programs.remove(Integer.valueOf(1));
		overlay.evaluate(s, instant(220), OFFSET, true);
		assertTrue("a freed program must not go on being replayed from a held entry",
				overlay.isEmpty());
	}

	/**
	 * SWAPPING a held node's animator must run the NEW program, not replay the old one.
	 *
	 * {@code ServerScene.setAnimator} replaces an attachment directly — one delta, never passing
	 * through 0 — so nothing detaches, nothing dangles, and the entry is neither removed in the
	 * loop nor swept. Without the program id on the entry, the old program's frozen output is
	 * replayed for as long as the hold lasts, which in-game reads as the attach having failed.
	 */
	@Test
	public void swappingAHeldNodesProgramRunsTheNewOne() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)),
				info(2, program(OcslWire.PROP_ANIM_Y, 9.0f)));
		SceneNode n = node(s, 1, 0);
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertTrue(overlay.of(1).wrote(OcslWire.PROP_ANIM_X));

		n.animator = 2;                  // a direct swap, exactly as setAnimator performs it
		overlay.holdPolicy(HOLD_ALL);
		overlay.evaluate(s, instant(220), OFFSET, true);

		assertTrue("the new program must run even under a hold — there is nothing valid to hold",
				overlay.of(1).wrote(OcslWire.PROP_ANIM_Y));
		assertFalse("and the old program's claim must not survive the swap",
				overlay.of(1).wrote(OcslWire.PROP_ANIM_X));
		assertEquals(9.0, overlay.of(1).y, 1e-6);
	}

	/**
	 * PINS A KNOWN LIMITATION rather than a guarantee: a program that reads its OWN registers goes
	 * stale under a hold, because those inputs are frozen while the base they describe moves.
	 *
	 * Recorded as a test so it is a fact about the system rather than a paragraph. It is the
	 * mechanism's honest boundary — the same shape as a base-compensating program
	 * ({@code rot2d = -parent.rot2d}), which no output-reuse scheme can hold correctly, and which
	 * is therefore a required input to ANIM-16's policy rather than a defect here.
	 *
	 * If a future change makes held base-readers track correctly, this test SHOULD fail; it is
	 * describing today's behaviour, not defending it.
	 */
	@Test
	public void aHeldProgramThatReadsItsOwnRegistersGoesStale() throws Exception {
		// WRITES y, READS x. It cannot write and read the same property: ANIM-7's double-apply
		// rule makes `out x = anim.x` a build error ("the base would be applied twice"), which is
		// the builder refusing the very shape a first draft of this test reached for. Reads of a
		// property the program does not write stay legal, and are the real base-dependence case.
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.out(OcslWire.PROP_ANIM_Y, b.builtin(SurfaceTable.REG_ANIM_X));
		SceneState s = sceneWith(info(1, IrCodec.encode(b.build())));
		SceneNode n = node(s, 1, 0);
		n.x = 10.0;
		n.y = 1.0;
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertEquals("y base 1 + the x it read, 10", 11.0, overlay.of(1).y, 1e-6);

		n.x = 100.0;                 // the register the program reads
		n.y = 50.0;                  // the base it composes over
		overlay.holdPolicy(HOLD_ALL);
		overlay.evaluate(s, instant(220), OFFSET, true);
		assertEquals("the BASE tracks (50) and the frozen read does NOT (10). 150 would mean it"
				+ " re-ran, 11 would mean the base stopped too — 60 is the documented limit:"
				+ " a held program's view of the world is as old as its last evaluation",
				60.0, overlay.of(1).y, 1e-6);
	}

	/** A null policy is the shipped one, not a NullPointerException on the render thread. */
	@Test
	public void aNullHoldPolicyFallsBackToEvaluatingAlways() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)));
		SceneNode n = node(s, 1, 0);
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.holdPolicy(HOLD_ALL);
		overlay.evaluate(s, instant(200), OFFSET, true);
		overlay.holdPolicy(null);
		assertTrue("null must read as EVALUATE_ALWAYS", overlay.wouldEvaluate(s));
		overlay.evaluate(s, instant(220), OFFSET, true);
		assertNotNull(overlay.of(1));
	}

	/**
	 * Re-attaching a node to a DIFFERENT program must not leave the old program's properties
	 * claimed. The entry object is reused across frames now, so its masks are state that outlives
	 * the program that set them — and a leftover bit would keep substituting a value the current
	 * program never writes, over a base that has since moved.
	 *
	 * The FRESH path only — {@code swappingAHeldNodesProgramRunsTheNewOne} is the held mirror, and
	 * it is a different mechanism (the entry's program id), not the same one retested.
	 */
	@Test
	public void reattachingToAnotherProgramClearsTheOldPropertyClaims() throws Exception {
		SceneState s = sceneWith(info(1, program(OcslWire.PROP_ANIM_X, 5.0f)),
				info(2, program(OcslWire.PROP_ANIM_Y, 9.0f)));
		SceneNode n = node(s, 1, 0);
		n.animator = 1;
		n.attachedWorldTime = 100L;

		AnimatorOverlay overlay = new AnimatorOverlay();
		overlay.evaluate(s, instant(200), OFFSET, true);
		assertTrue(overlay.of(1).wrote(OcslWire.PROP_ANIM_X));

		n.animator = 2;
		overlay.evaluate(s, instant(220), OFFSET, true);
		assertTrue("the new program owns y", overlay.of(1).wrote(OcslWire.PROP_ANIM_Y));
		assertFalse("and the old program's claim on x must not survive the reused entry",
				overlay.of(1).wrote(OcslWire.PROP_ANIM_X));
	}
}
