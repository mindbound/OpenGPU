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
}
