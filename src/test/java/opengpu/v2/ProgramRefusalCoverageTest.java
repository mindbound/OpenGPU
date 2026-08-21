package opengpu.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import opengpu.v2.ocsl.Expr;
import opengpu.v2.ocsl.IrCodec;
import opengpu.v2.ocsl.OcslBuilder;
import opengpu.v2.ocsl.OcslWire;
import opengpu.v2.protocol.Delta;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.DeltaApplier;
import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneState;
import opengpu.v2.scene.ServerScene;

/**
 * Refusals in the program/attach surface that nothing asserted on.
 *
 * FOUND MECHANICALLY, NOT BY RECALL, and the method is the point. Every throw-with-a-message
 * across the 3.1-3.5 surface was extracted and checked against the whole test tree; six had no
 * assertion anywhere, so each could have been deleted with all 656 tests still green. Two of the
 * six I would have sworn were covered — which is why the check was a script rather than a memory.
 *
 * These are cheap guards on paths a program can genuinely reach (a nil blob from Lua, a double
 * free), plus one that is effectively unreachable and tested anyway because its cost is a single
 * assertion and its failure mode is an id collision that nothing else would catch.
 */
public class ProgramRefusalCoverageTest {

	private static final String SCENE = "gpu-node-address";

	private static byte[] validBlob() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_POST);
		Expr colour = b.constant(0f, 0f, 0f, 1f);
		b.out(OcslWire.PROP_COLOR, colour);
		return IrCodec.encode(b.build());
	}

	// ---------------------------------------------------------------- createProgram's entry guards

	/**
	 * A nil blob from Lua reaches this as null, and an empty string as a zero-length array. Both
	 * must be refused BEFORE the codec sees them, or the author gets a parse error about bytes
	 * they never sent.
	 */
	@Test
	public void createProgramRefusesANullOrEmptyBlob() {
		ServerScene server = new ServerScene(SCENE);
		try {
			server.createProgram(null);
			fail("a null blob was accepted");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(),
					expected.getMessage().contains("Program bytes required"));
		}
		try {
			server.createProgram(new byte[0]);
			fail("an empty blob was accepted");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(),
					expected.getMessage().contains("Program bytes required"));
		}
		assertEquals("a refused create must charge nothing", 0L, server.programBytes());
	}

	/**
	 * The id counter refuses to wrap rather than handing out a duplicate.
	 *
	 * Effectively unreachable — it needs 2^31 creates — but tested because the failure it prevents
	 * is silent: a wrapped counter reallocates a LIVE id, and DeltaApplier's duplicate check would
	 * then reject the create on both sides while the ledger had already been charged. One
	 * assertion against a state built at the boundary is cheaper than reasoning about that.
	 */
	@Test
	public void createProgramRefusesToWrapTheIdSpace() throws Exception {
		SceneState state = new SceneState();
		state.nextProgramId = Integer.MAX_VALUE;
		ServerScene server = new ServerScene(SCENE, 0, 0x0FACE, state);
		try {
			server.createProgram(validBlob());
			fail("the id space wrapped instead of refusing");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage(),
					expected.getMessage().contains("id space exhausted"));
		}
	}

	// ---------------------------------------------------------------- freeProgram

	/**
	 * Freeing an unknown program is refused, which is what makes a DOUBLE free an error rather
	 * than a silent no-op — and a double free is the reachable case: a Lua teardown that runs
	 * twice, or a script freeing an id it already released at reboot.
	 */
	@Test
	public void freeProgramRefusesAnUnknownId() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		int id = server.createProgram(validBlob());
		server.freeProgram(id);
		try {
			server.freeProgram(id); // the second free
			fail("a double free was accepted");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage(),
					expected.getMessage().contains("Freeing unknown program"));
		}
	}

	/** The same refusal on the applier, which is the path a MIRROR runs. */
	@Test
	public void theApplierRefusesToFreeAnUnknownProgram() {
		SceneState state = new SceneState();
		try {
			DeltaApplier.apply(state, new Delta.ProgramFree(7));
			fail("the applier freed a program that does not exist");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage(),
					expected.getMessage().contains("Freeing unknown program"));
		}
	}

	// ---------------------------------------------------------------- the delta's own invariants

	/**
	 * {@code Delta.ProgramCreate}'s constructor guards, which the DECODER relies on being
	 * unreachable — it checks the same three shapes first so an unchecked IllegalArgumentException
	 * cannot escape its {@code throws CodecException} contract. If these ever stop throwing, that
	 * layering silently becomes decorative, so they are pinned here directly.
	 */
	@Test
	public void theProgramCreateDeltaRefusesAnUnusableBlob() {
		try {
			new Delta.ProgramCreate(1, OcslWire.STAGE_PIXEL_POST, 4, null);
			fail("a null blob built a delta");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("needs a blob"));
		}
		try {
			new Delta.ProgramCreate(1, OcslWire.STAGE_PIXEL_POST, 4, new byte[0]);
			fail("an empty blob built a delta");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("blob is empty"));
		}
		try {
			new Delta.ProgramCreate(1, OcslWire.STAGE_PIXEL_POST, 4,
					new byte[OcslWire.MAX_BLOB_BYTES + 1]);
			fail("an oversized blob built a delta");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(),
					expected.getMessage().contains("exceeds MAX_BLOB_BYTES"));
		}
	}

	/** And the legal shape still builds — the other side of all three refusals above. */
	@Test
	public void aWellFormedProgramCreateDeltaStillBuilds() throws Exception {
		byte[] blob = validBlob();
		Delta.ProgramCreate d = new Delta.ProgramCreate(1, OcslWire.STAGE_PIXEL_POST, 4, blob);
		assertTrue("the blob must survive construction",
				java.util.Arrays.equals(blob, d.blobCopy()));
	}

	/**
	 * A node freed while it still carries an attachment does not trip any of the above — freeing
	 * a NODE is not freeing a program, and the attachment simply goes with it. Included because
	 * the two "free" verbs sit next to each other and a guard added to the wrong one would be
	 * caught here rather than in game.
	 */
	@Test
	public void freeingAnAttachedNodeIsNotAProgramFree() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		int canvas = server.createCanvas(64, 32, 256);
		int node = server.createNode(V2Wire.NODE_CANVAS, canvas);
		// An ANIMATOR blob, unlike this file's pixel-stage validBlob(): since the stage gate
		// (2026-08-21) only animator programs may attach, and this fixture's attach was exactly
		// the wrong-stage convenience the gate exists to refuse. The gate catching a TEST
		// fixture on landing day is the newly-live-branch rule doing its job.
		OcslBuilder anim = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		anim.out(OcslWire.PROP_ANIM_TINT, anim.constant(1f, 1f, 1f, 1f));
		int program = server.createProgram(IrCodec.encode(anim.build()));
		server.setAnimator(node, program, 100L);

		server.freeNode(node); // must not throw, and must not touch the program table
		assertTrue("freeing a node must not free its program",
				server.state().programs.containsKey(Integer.valueOf(program)));
		SceneNode gone = server.state().nodes.get(Integer.valueOf(node));
		assertEquals("the node itself is gone", null, gone);
	}
}
