package opengpu.v2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;

import org.junit.Test;

import opengpu.v2.ocsl.Expr;
import opengpu.v2.ocsl.IrCodec;
import opengpu.v2.ocsl.IrOp;
import opengpu.v2.ocsl.IrProgram;
import opengpu.v2.ocsl.OcslBuilder;
import opengpu.v2.ocsl.OcslWire;
import opengpu.v2.ocsl.SurfaceTable;
import opengpu.v2.protocol.BatchCodec;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.SceneMirror;
import opengpu.v2.scene.SceneState;
import opengpu.v2.scene.ServerScene;

/**
 * Phase 3.2, piece 2: the attach SURFACE.
 *
 * WHAT THIS FILE CAN AND CANNOT SEE. ANIM-15(a)'s display-node refusal lives on
 * {@code TileEntityGpu2}, because it keys on {@code implicitCanvasNode} — the id the SERVER
 * persists, and a private TE field. ({@code DisplayNode.displayNodeId} does return a display node
 * id, contrary to a claim this comment used to carry; it is the CLIENT's rescan-from-state answer,
 * which is a different mechanism from the server's remembered one.) Loading that class needs
 * Forge, so no JVM test can drive the refusal end to end.
 *
 * That is why the refusal was factored the way it was: both halves that DECIDE anything —
 * "which properties does this program own" ({@link IrProgram#outProperties}) and "is this property
 * part of the transform" ({@link SurfaceTable#isAnimatorTransformProperty}) — are pure functions in
 * testable classes, and the TE method is a thin composition of them with a field read. What remains
 * untestable here is the dispatch, not the logic. The composition itself is a candidate for the
 * in-game channel once 3.3 makes an attached animator observable.
 */
public class NodeAttachSurfaceTest {

	private static final String SCENE = "gpu-node-address";

	// ---------------------------------------------------------------- the ownership declaration

	/** An animator owning a transform property is reported as owning it. */
	@Test
	public void outPropertiesReportsWhatTheProgramWrites() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		// EMITTED HIGH-ID FIRST, deliberately. A first draft wrote x then tint, whose insertion
		// order IS ascending order — so an ArrayList or a LinkedHashSet passed and the word
		// "ascending" in the assertion was decorative. Reversing the emission is what makes the
		// ordering claim load-bearing.
		b.out(OcslWire.PROP_ANIM_TINT, b.constant(1f, 1f, 1f, 1f));
		b.out(OcslWire.PROP_ANIM_X, b.f(1.0f));
		int[] owned = b.build().outProperties();

		assertArrayEquals("both OUTs must be reported, ASCENDING regardless of emission order",
				new int[] { OcslWire.PROP_ANIM_X, OcslWire.PROP_ANIM_TINT }, owned);
	}

	/**
	 * A property may be written ONCE, and that is enforced twice over — so the ownership
	 * declaration cannot contain a duplicate in the first place.
	 *
	 * Written as an invariant rather than as "duplicates collapse", because the collapse case is
	 * unreachable for anything the attach path can see: {@code OcslBuilder} refuses the second
	 * write at build and {@code IrValidator} refuses it at validate, and every program in
	 * {@code SceneState.programs} passed the validator. The set semantics in
	 * {@code outProperties} are therefore defence-in-depth for a hand-built program, not a rule
	 * the surface relies on. Pinning the refusal is what actually protects the guard: if a
	 * property could be written twice with different ownership implications, "which properties
	 * does this program own" would stop being a well-posed question.
	 */
	@Test
	public void aPropertyMayBeWrittenOnlyOnce() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.out(OcslWire.PROP_ANIM_X, b.f(1.0f));
		try {
			b.out(OcslWire.PROP_ANIM_X, b.f(2.0f));
			fail("a second OUT to the same property was accepted");
		} catch (RuntimeException expected) {
			assertTrue("expected the already-written refusal, got: " + expected.getMessage(),
					expected.getMessage() != null
							&& expected.getMessage().contains("already written"));
		}
	}

	/** A program that writes nothing owns nothing — the empty case the guard must not trip on. */
	@Test
	public void aProgramWithNoOutOwnsNothing() {
		IrProgram none = new IrProgram(OcslWire.STAGE_ANIMATOR, new float[] { 1.0f },
				new ArrayList<IrOp>(), new ArrayList<String>(), SurfaceTable.WORKING_BASE + 1);
		assertEquals(0, none.outProperties().length);
	}

	/**
	 * OUT_ABS declares ownership exactly as OUT does.
	 *
	 * The wrong implementation this excludes is naming one opcode instead of asking
	 * {@code OcslWire.isOut} — which is the mistake that method's own javadoc exists to prevent,
	 * and here it would let an absolute-form animator take the display node's transform unnoticed.
	 */
	@Test
	public void theAbsoluteOutputFormDeclaresOwnershipToo() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.outAbsolute(OcslWire.PROP_ANIM_X, b.f(1.0f));
		assertArrayEquals("OUT_ABS owns its property as surely as OUT",
				new int[] { OcslWire.PROP_ANIM_X }, b.build().outProperties());
	}

	/** The set survives a codec round trip, which is how the attach path actually obtains it. */
	@Test
	public void outPropertiesSurvivesTheRoundTripTheAttachPathUses() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.out(OcslWire.PROP_ANIM_ROT2D, b.f(0.5f));
		byte[] blob = IrCodec.encode(b.build());
		IrProgram decoded = IrCodec.decode(blob, IrCodec.Source.TRANSIENT);
		assertArrayEquals("the attach guard reads the set off a DECODED blob, not off the builder",
				new int[] { OcslWire.PROP_ANIM_ROT2D }, decoded.outProperties());
	}

	// ---------------------------------------------------------------- the transform predicate

	/**
	 * Tint is the property ANIM-15(a) deliberately still permits on the display node, and z and
	 * visible answer false because they are not ownable at all.
	 */
	@Test
	public void transformPropertiesAreEverythingOwnableExceptTint() {
		assertTrue(SurfaceTable.isAnimatorTransformProperty(OcslWire.PROP_ANIM_X));
		assertTrue(SurfaceTable.isAnimatorTransformProperty(OcslWire.PROP_ANIM_Y));
		assertTrue(SurfaceTable.isAnimatorTransformProperty(OcslWire.PROP_ANIM_SX));
		assertTrue(SurfaceTable.isAnimatorTransformProperty(OcslWire.PROP_ANIM_SY));
		assertTrue(SurfaceTable.isAnimatorTransformProperty(OcslWire.PROP_ANIM_ROT2D));
		assertTrue(SurfaceTable.isAnimatorTransformProperty(OcslWire.PROP_ANIM_ROT3D));
		assertTrue("Stage C's tz must count, or a 3D animator takes the display node silently",
				SurfaceTable.isAnimatorTransformProperty(OcslWire.PROP_ANIM_TZ));
		assertTrue(SurfaceTable.isAnimatorTransformProperty(OcslWire.PROP_ANIM_SZ));

		// The exclusion that gives the predicate its point: a blanket "owns anything" test would
		// pass every assertion above and still be wrong, because it would refuse tint too.
		assertFalse("tint is expressly allowed on the display node",
				SurfaceTable.isAnimatorTransformProperty(OcslWire.PROP_ANIM_TINT));
		assertFalse("z is unownable, so it can never reach an attachment question",
				SurfaceTable.isAnimatorTransformProperty(OcslWire.PROP_ANIM_Z));
		assertFalse(SurfaceTable.isAnimatorTransformProperty(OcslWire.PROP_ANIM_VISIBLE));
	}

	// ---------------------------------------------------------------- the scene mutator

	private static void ship(ServerScene server, SceneMirror mirror) throws Exception {
		SceneBatch batch = server.sealBatch();
		if (batch == null) {
			return;
		}
		assertTrue("mirror rejected the batch",
				mirror.applyBatch(BatchCodec.decode(BatchCodec.encode(batch))));
	}

	@Test
	public void setAnimatorAttachesReplacesAndDetachesThroughTheWire() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		SceneMirror mirror = new SceneMirror(SCENE);
		int canvas = server.createCanvas(64, 32, 256);
		int node = server.createNode(V2Wire.NODE_CANVAS, canvas);
		ship(server, mirror);

		server.setAnimator(node, 5, 1000L);
		ship(server, mirror);
		assertEquals(5, mirror.state().nodes.get(Integer.valueOf(node)).animator);
		assertEquals(1000L, mirror.state().nodes.get(Integer.valueOf(node)).attachedWorldTime);

		// REPLACE restarts the clock: the new attachment is a new attachment, so an easing
		// program must not inherit the previous one's age.
		server.setAnimator(node, 6, 2000L);
		ship(server, mirror);
		assertEquals(6, mirror.state().nodes.get(Integer.valueOf(node)).animator);
		assertEquals("a replace must restamp, not keep the old attachment's age",
				2000L, mirror.state().nodes.get(Integer.valueOf(node)).attachedWorldTime);

		// DETACH clears both, so a later re-attach cannot read a stale stamp.
		server.setAnimator(node, 0, 3000L);
		ship(server, mirror);
		assertEquals(0, mirror.state().nodes.get(Integer.valueOf(node)).animator);
		assertEquals("a detach must clear the stamp even when one is passed",
				0L, mirror.state().nodes.get(Integer.valueOf(node)).attachedWorldTime);
		assertTrue("server and mirror disagree", server.state().contentEquals(mirror.state()));
	}

	@Test
	public void attachingToAnUnknownNodeIsRefused() {
		ServerScene server = new ServerScene(SCENE);
		try {
			server.setAnimator(99, 1, 10L);
			fail("attach to a nonexistent node was accepted");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("99"));
		}
		// Nothing may be staged by a refused call. Without this the test cannot tell ServerScene's
		// own requireNode from DeltaApplier's identically-typed throw one layer down.
		assertFalse("a refused attach must stage nothing", server.hasStagedDeltas());
	}

	@Test
	public void aNegativeProgramIdIsRefused() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		int canvas = server.createCanvas(64, 32, 256);
		int node = server.createNode(V2Wire.NODE_CANVAS, canvas);
		try {
			server.setAnimator(node, -1, 10L);
			fail("a negative program id was accepted");
		} catch (IllegalArgumentException expected) {
			// This message is byte-identical to Delta.NodeAttach's constructor refusal, so it
			// cannot distinguish the two layers on its own; the state check below is what does.
			assertTrue(expected.getMessage(), expected.getMessage().contains("non-negative"));
		}
		assertEquals("a refused attach must not have attached anything",
				0, server.state().nodes.get(Integer.valueOf(node)).animator);
	}

	/** ANIM-17: attaching a program that does not exist is legal and dangles. */
	@Test
	public void attachingAnUnknownProgramIsLegal() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		int canvas = server.createCanvas(64, 32, 256);
		int node = server.createNode(V2Wire.NODE_CANVAS, canvas);
		assertTrue("precondition: the program really does not exist",
				server.state().programs.isEmpty());
		server.setAnimator(node, 4242, 10L);
		assertEquals("a dangling attach must be accepted, not refused",
				4242, server.state().nodes.get(Integer.valueOf(node)).animator);
	}

	/**
	 * Freeing an attached program leaves the attachment dangling rather than refusing — the whole
	 * of ANIM-17's ruling, exercised through the two public calls a program would actually make.
	 */
	@Test
	public void freeingAnAttachedProgramLeavesTheAttachmentDangling() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		int canvas = server.createCanvas(64, 32, 256);
		int node = server.createNode(V2Wire.NODE_CANVAS, canvas);

		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.out(OcslWire.PROP_ANIM_TINT, b.constant(1f, 1f, 1f, 1f));
		int program = server.createProgram(IrCodec.encode(b.build()));
		server.setAnimator(node, program, 100L);

		server.freeProgram(program); // must NOT throw
		assertFalse("the program is gone",
				server.state().programs.containsKey(Integer.valueOf(program)));
		assertEquals("...but the attachment still names it, which is what 'dangling' means",
				program, server.state().nodes.get(Integer.valueOf(node)).animator);
	}

	/**
	 * A RESOLVABLE program of the wrong stage is REFUSED at attach — the stage gate, 2026-08-21.
	 *
	 * Until it existed, this exact sequence CRASHED the client: a legal pixel program validates
	 * and attaches, and the evaluator then binds animator registers into a frame that maps none
	 * of them — OcslVm.set throws on the render thread, out through the Forge tick. Two ordinary
	 * Lua calls. The refusal names both stages, because the author's mistake is a stage mix-up
	 * and the message is where they learn it.
	 *
	 * Scoped deliberately: the gate checks only programs it can RESOLVE. Attaching an absent id
	 * stays legal (ANIM-17's dangling ruling — {@code detachingADanglingAttachmentWorks} attaches
	 * id 777 and keeps passing), and ids are never reused, so an absent id can never later
	 * resolve to a wrong-stage program within an incarnation.
	 */
	@Test
	public void aNonAnimatorProgramIsRefusedAtAttach() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		int canvas = server.createCanvas(64, 32, 256);
		int node = server.createNode(V2Wire.NODE_CANVAS, canvas);

		OcslBuilder pixel = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		pixel.out(OcslWire.PROP_COLOR, pixel.constant(1f, 1f, 1f, 1f));
		int program = server.createProgram(IrCodec.encode(pixel.build()));

		try {
			server.setAnimator(node, program, 100L);
			fail("a pixel-stage program must not attach as an animator");
		} catch (IllegalArgumentException expected) {
			assertTrue("the refusal names the offending stage: " + expected.getMessage(),
					expected.getMessage().contains(
							"stage " + OcslWire.STAGE_PIXEL_MATERIAL));
			assertTrue("and the required one: " + expected.getMessage(),
					expected.getMessage().contains(
							"stage " + OcslWire.STAGE_ANIMATOR));
		}
		assertEquals("the refused attach must not have landed", 0,
				server.state().nodes.get(Integer.valueOf(node)).animator);
	}

	/** A detach after the program was freed still clears cleanly — no resolution is attempted. */
	@Test
	public void detachingADanglingAttachmentWorks() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		int canvas = server.createCanvas(64, 32, 256);
		int node = server.createNode(V2Wire.NODE_CANVAS, canvas);
		server.setAnimator(node, 777, 100L);
		// THE INTERMEDIATE STATE, asserted because without it every assertion in this test is a
		// fresh node's default: an applier arm with an empty body, or a setAnimator that staged
		// nothing at all, would pass on the precondition alone.
		assertEquals(777, server.state().nodes.get(Integer.valueOf(node)).animator);
		assertEquals(100L, server.state().nodes.get(Integer.valueOf(node)).attachedWorldTime);

		server.setAnimator(node, 0, 0L);
		assertEquals(0, server.state().nodes.get(Integer.valueOf(node)).animator);
		assertEquals(0L, server.state().nodes.get(Integer.valueOf(node)).attachedWorldTime);
	}

	/**
	 * The world-time fields are actually STAMPED, which for a whole increment they were not.
	 *
	 * Both were declared, encoded, decoded and copied while nothing assigned them, so every
	 * snapshot shipped zeros and the conversion the anchor exists for degenerated silently. Two
	 * review lenses found it independently by noticing the tests could only ever assert 0 — so
	 * these assertions use non-zero values on purpose.
	 */
	@Test
	public void theWorldTimeFieldsAreStampedAndTheEpochIsStampedOnlyOnce() {
		ServerScene server = new ServerScene(SCENE);
		assertEquals("a fresh scene is unstamped", 0L, server.state().creationWorldTime);
		assertEquals(0L, server.state().worldTimeAnchor);

		server.stampWorldTime(5000L);
		assertEquals("the epoch takes the first reading", 5000L, server.state().creationWorldTime);
		assertEquals("and so does the anchor", 5000L, server.state().worldTimeAnchor);

		server.stampWorldTime(5001L);
		assertEquals("the ANCHOR refreshes every tick — a stale one mis-converts every stamp",
				5001L, server.state().worldTimeAnchor);
		assertEquals("the EPOCH must not move, or every animator on the scene restarts",
				5000L, server.state().creationWorldTime);
	}

	/** A scene restored with an epoch already on disk keeps it rather than adopting now. */
	@Test
	public void aRestoredEpochIsNotOverwritten() {
		SceneState restored = new SceneState();
		restored.creationWorldTime = 12345L;
		ServerScene server = new ServerScene(SCENE, 0, 0x0FACE, restored);
		server.stampWorldTime(99999L);
		assertEquals("a v8 save's epoch survives its first tick", 12345L,
				server.state().creationWorldTime);
		assertEquals(99999L, server.state().worldTimeAnchor);
	}

	/**
	 * A freed NODE takes its attachment with it — the property that made a field the right home
	 * and retired 2.2's five-drop-site checklist.
	 */
	@Test
	public void freeingTheNodeTakesTheAttachmentWithIt() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		SceneMirror mirror = new SceneMirror(SCENE);
		int canvas = server.createCanvas(64, 32, 256);
		int keep = server.createNode(V2Wire.NODE_CANVAS, canvas);
		int doomed = server.createNode(V2Wire.NODE_GROUP, 0);
		server.setAnimator(doomed, 9, 500L);
		server.setAnimator(keep, 8, 400L);
		ship(server, mirror);

		server.freeNode(doomed);
		ship(server, mirror);

		assertFalse("the node is gone and so is its attachment, with no drop site anywhere",
				mirror.state().nodes.containsKey(Integer.valueOf(doomed)));
		assertEquals("the surviving node keeps its own",
				8, mirror.state().nodes.get(Integer.valueOf(keep)).animator);
		assertTrue(server.state().contentEquals(mirror.state()));
	}
}
