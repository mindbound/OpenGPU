package opengpu.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import opengpu.v2.ocsl.Expr;
import opengpu.v2.ocsl.IrCodec;
import opengpu.v2.ocsl.IrOp;
import opengpu.v2.ocsl.IrProgram;
import opengpu.v2.ocsl.IrValidator;
import opengpu.v2.ocsl.OcslBuilder;
import opengpu.v2.ocsl.OcslWire;
import opengpu.v2.ocsl.SurfaceTable;
import opengpu.v2.protocol.BatchCodec;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.SnapshotCodec;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.ProgramInfo;
import opengpu.v2.scene.SceneMirror;
import opengpu.v2.scene.SceneSnapshot;
import opengpu.v2.scene.ServerScene;

/**
 * Phase 3.1's program table: admission, the ledger's BOTH sides, the free path, and convergence
 * through the real codec.
 *
 * The ledger tests are written against the shape this project keeps getting wrong. A cap
 * reliably gets a test that it REFUSES and reliably does not get one that legal traffic still
 * GETS THROUGH — the per-tick submit allowance shipped broken from day one behind exactly that
 * asymmetry. So every bound here is pinned from both directions, and the refusal tests carry an
 * explicit exclusion of the wrong refusal.
 */
public class ProgramStorageTest {

	private static final String SCENE = "gpu-node-address";

	// ---------------------------------------------------------------- helpers

	/**
	 * A valid program of a requested structural size. {@code charge} counts the ops it charges,
	 * so {@code ceiling()} below is this at the acceptance cap.
	 */
	private static byte[] blob(int charge) throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_POST);
		Expr acc = b.constant(0f, 0f, 0f, 1f);
		for (int i = 0; i < charge; i++) {
			acc = acc.add(b.f(1.0f));
		}
		b.out(OcslWire.PROP_COLOR, acc);
		return IrCodec.encode(b.build());
	}

	/**
	 * A LARGE program of the cheapest bytes-per-op shape (1810 B) — NOT the largest the validator
	 * accepts: the constant pool is uncharged, so the byte-maximal legal program is ~17 KiB and
	 * pool-heavy (ProgramLedgerBoundTest measures it). Big enough to exercise the ledger in a
	 * few hundred creates, which is what these tests need it for.
	 */
	private static byte[] ceiling() throws Exception {
		return blob(IrValidator.MAX_STRUCTURAL_OPS - 4);
	}

	/** The smallest thing that is still a program. */
	private static byte[] tiny() throws Exception {
		return blob(1);
	}

	private static void ship(ServerScene server, SceneMirror mirror) throws Exception {
		SceneBatch batch = server.sealBatch();
		if (batch == null) {
			return;
		}
		assertTrue("mirror rejected the batch",
				mirror.applyBatch(BatchCodec.decode(BatchCodec.encode(batch))));
	}

	// ---------------------------------------------------------------- convergence

	/**
	 * The blob survives the wire BYTE FOR BYTE, and so does the validator's verdict.
	 *
	 * The exclusion matters here more than usual: a ProgramCreate that dropped its blob would
	 * still produce a mirror with the right program COUNT and the right id, and every assertion
	 * about ids and sizes would pass. Only comparing the bytes catches it, which is why
	 * {@code Delta.ProgramCreate.equals} includes the array.
	 */
	@Test
	public void aProgramReachesAMirrorByteForByteThroughTheRealCodec() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		SceneMirror mirror = new SceneMirror(SCENE);
		byte[] source = ceiling();

		int id = server.createProgram(source);
		ship(server, mirror);

		ProgramInfo mirrored = mirror.state().programs.get(Integer.valueOf(id));
		assertNotNull("the program did not reach the mirror", mirrored);
		assertTrue("the mirror holds different bytes than the server sent",
				java.util.Arrays.equals(source, mirrored.blobCopy()));
		assertEquals("stage lost on the wire", OcslWire.STAGE_PIXEL_POST, mirrored.stage);
		assertEquals("the validator's charge lost on the wire",
				IrValidator.validate(IrCodec.decode(source, IrCodec.Source.TRANSIENT)).structuralOps,
				mirrored.structuralOps);
		assertTrue("server and mirror disagree", server.state().contentEquals(mirror.state()));
		// contentEquals compares the TABLES, not the counters, so this is the only line that can
		// see DeltaApplier's counter-advance dropped. Without it, a mirror whose nextProgramId
		// stayed at 1 passes every assertion here — and then fails SnapshotCodec's
		// counter-consistency check the first time ITS state is snapshotted.
		assertEquals("the mirror's program id counter did not track the server's",
				server.state().nextProgramId, mirror.state().nextProgramId);
	}

	@Test
	public void freeConvergesToo() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		SceneMirror mirror = new SceneMirror(SCENE);
		int a = server.createProgram(tiny());
		int b = server.createProgram(tiny());
		ship(server, mirror);
		server.freeProgram(a);
		ship(server, mirror);

		assertFalse("the freed program is still on the mirror",
				mirror.state().programs.containsKey(Integer.valueOf(a)));
		assertTrue("the other program should have survived",
				mirror.state().programs.containsKey(Integer.valueOf(b)));
		assertTrue("server and mirror disagree after a free",
				server.state().contentEquals(mirror.state()));
	}

	/**
	 * A freed id is never handed out again.
	 *
	 * This is what makes a dangling attach reference (3.2) merely stale rather than dangerous: a
	 * reused id would silently rebind a node to code it never asked for, which is the same defect
	 * class as the canvas-handle collision that reached the server as a node id.
	 */
	@Test
	public void aFreedProgramIdIsNeverReused() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		int first = server.createProgram(tiny());
		server.freeProgram(first);
		int second = server.createProgram(tiny());
		assertTrue("id " + second + " was handed out again after " + first + " was freed",
				second > first);
	}

	// ---------------------------------------------------------------- the ledger, both sides

	/** Upper bound: the create that would cross the ledger is refused. */
	@Test
	public void theLedgerRefusesTheProgramThatWouldCrossIt() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		byte[] big = ceiling();
		int admitted = 0;
		try {
			for (int i = 0; i < 10000; i++) {
				server.createProgram(big);
				admitted++;
			}
			fail("the ledger admitted " + admitted + " programs without ever refusing");
		} catch (IllegalStateException expected) {
			assertTrue("the refusal should name the budget, got: " + expected.getMessage(),
					expected.getMessage().contains("program budget"));
		}
		assertTrue("the ledger is over its own cap after refusing",
				server.programBytes() <= ServerScene.MAX_PROGRAM_BYTES);
		// EXACT, not a range: floor(cap / size) is what a correct ledger admits, and any weaker
		// assertion also passes on a ledger that stops early — a review found the first draft's
		// ">= 100" satisfiable by a cap lowered 30%, an admission gate stopping at 70%, or
		// per-program overhead silently added to the charge.
		assertEquals("the ledger admitted a different count than floor(cap / blobSize)",
				(long) (ServerScene.MAX_PROGRAM_BYTES / big.length), (long) admitted);
	}

	/**
	 * The batch dimension, which the scene ledger does NOT imply: freeProgram returns ledger
	 * bytes without un-staging the create deltas, so without its own bound a create/free loop in
	 * one tick builds a batch past the decoder's 4 MiB ceiling that every mirror refuses whole.
	 * Both sides of the cap: the refusal fires, and the batch built up to it still DECODES.
	 */
	@Test
	public void aCreateFreeLoopHitsTheBatchBoundAndTheBatchItBuiltStillDecodes() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		byte[] big = ceiling();
		int cycles = 0;
		try {
			for (int i = 0; i < 100000; i++) {
				int id = server.createProgram(big);
				server.freeProgram(id);
				cycles++;
			}
			fail("the batch program bound never fired across " + cycles + " create/free cycles;"
					+ " the ledger alone cannot bound the batch, so nothing did");
		} catch (IllegalStateException expected) {
			assertTrue("expected the BATCH refusal, got: " + expected.getMessage(),
					expected.getMessage().contains("Batch program payload"));
		}
		// The scene ledger never saw more than one program — which is exactly why it could not
		// have been the thing that refused.
		assertEquals(0L, server.programBytes());
		assertEquals("the loop should run until the staged bytes cross the batch cap",
				(long) (V2Wire.MAX_PROGRAM_BYTES_PER_BATCH / big.length), (long) cycles);
		// And the legal half: everything admitted before the refusal seals into a batch that
		// round-trips the real codec. A producer bound tighter than the decoder's would be
		// invisible here; a decoder bound tighter than the producer's is the silent desync.
		SceneBatch batch = server.sealBatch();
		assertNotNull(batch);
		SceneBatch decoded = BatchCodec.decode(BatchCodec.encode(batch));
		assertEquals(batch.deltas.size(), decoded.deltas.size());
	}

	/**
	 * LOWER bound — the half that never gets written, and the half that has broken twice here.
	 *
	 * A ledger admitting only up to its cap is worth nothing if a single legal program cannot get
	 * through it; that is precisely how the per-tick submit allowance shipped, refusing every
	 * frame larger than one call from the day it landed.
	 */
	@Test
	public void andTheLedgerAdmitsAProgramThatFits() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		assertEquals("a fresh scene should have the whole ledger free",
				ServerScene.MAX_PROGRAM_BYTES, server.programBudgetRemaining());
		byte[] big = ceiling();
		int id = server.createProgram(big);
		assertTrue("a maximal program must be admissible on an empty scene", id > 0);
		assertEquals("the ledger charged something other than the blob length",
				big.length, server.programBytes());
		assertEquals("remaining is not cap minus charged",
				ServerScene.MAX_PROGRAM_BYTES - big.length, server.programBudgetRemaining());
	}

	/** Freeing returns the bytes: a scene at the cap becomes usable again without a restart. */
	@Test
	public void freeingReturnsBytesToTheLedger() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		byte[] big = ceiling();
		int last = 0;
		try {
			for (int i = 0; i < 10000; i++) {
				last = server.createProgram(big);
			}
			fail("never filled");
		} catch (IllegalStateException expected) {
			// full
		}
		long full = server.programBytes();
		server.freeProgram(last);
		assertEquals("free did not return the blob's bytes",
				full - big.length, server.programBytes());
		// And the create that was refused a moment ago now succeeds. Without this the free path
		// could be dropping the table entry while leaking the charge and every other assertion
		// would still pass.
		assertTrue("a create still fails after freeing exactly enough for it",
				server.createProgram(big) > 0);
	}

	// ---------------------------------------------------------------- admission before work

	/**
	 * THE ADMISSION ORDER, tested by a vector that separates the two orders rather than by
	 * reading the method.
	 *
	 * The blob is BOTH over the remaining ledger AND unparseable. Check-then-parse answers with
	 * the budget; parse-then-check answers with the codec. One input, two distinguishable
	 * outcomes — which is the only way to pin an ordering from the outside.
	 */
	@Test
	public void admissionIsChargedBeforeTheBlobIsParsed() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		byte[] big = ceiling();
		try {
			for (int i = 0; i < 10000; i++) {
				server.createProgram(big);
			}
			fail("never filled");
		} catch (IllegalStateException expected) {
			// full
		}

		byte[] garbage = new byte[2048]; // no magic, no version, not a program by any reading
		try {
			server.createProgram(garbage);
			fail("a full ledger accepted 2 KiB of garbage");
		} catch (IllegalStateException budget) {
			assertTrue("expected the LEDGER's refusal, got: " + budget.getMessage(),
					budget.getMessage().contains("program budget"));
		} catch (IllegalArgumentException parsed) {
			fail("the blob was PARSED before the ledger was consulted — that is the"
					+ " admission-after-work defect, and the message proves it: "
					+ parsed.getMessage());
		}
	}

	/**
	 * The mirror image, and without it the test above passes on a createProgram that refuses
	 * everything: with room on the ledger, garbage must reach the parser and be refused BY it.
	 */
	@Test
	public void withRoomOnTheLedgerAMalformedBlobIsRefusedByTheCodec() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		try {
			server.createProgram(new byte[2048]);
			fail("garbage was accepted as a program");
		} catch (IllegalArgumentException expected) {
			assertTrue("expected the CODEC's refusal, got: " + expected.getMessage(),
					expected.getMessage().toLowerCase().contains("ocsl"));
		}
		assertEquals("a refused program charged the ledger anyway", 0L, server.programBytes());
	}

	/** A program over the codec's own ceiling dies on length, before any parse or ledger walk. */
	@Test
	public void aBlobOverTheCodecCeilingIsRefusedOnLength() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		try {
			server.createProgram(new byte[OcslWire.MAX_BLOB_BYTES + 1]);
			fail("a blob over MAX_BLOB_BYTES was accepted");
		} catch (IllegalArgumentException expected) {
			assertTrue("expected the length refusal, got: " + expected.getMessage(),
					expected.getMessage().contains("ceiling"));
		}
		// "Before any ledger walk" needs the ledger FULL to discriminate — on an empty scene a
		// ledger-first ordering gives the identical message, because 64 KiB + 1 fits 256 KiB.
		byte[] big = ceiling();
		try {
			for (int i = 0; i < 10000; i++) {
				server.createProgram(big);
			}
			fail("never filled");
		} catch (IllegalStateException full) {
			// full — and the next refusal must still be the CEILING's, not the budget's
		}
		try {
			server.createProgram(new byte[OcslWire.MAX_BLOB_BYTES + 1]);
			fail("a blob over MAX_BLOB_BYTES was accepted on a full ledger");
		} catch (IllegalArgumentException expected) {
			assertTrue("on a full ledger an over-ceiling blob must still die on LENGTH — a"
					+ " budget-first ordering answers with the budget: " + expected.getMessage(),
					expected.getMessage().contains("ceiling"));
		}
	}

	/** The trivial refusals, pinned because nothing else exercises them. */
	@Test
	public void nullAndEmptyBlobsAreRefused() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		try {
			server.createProgram(null);
			fail("null accepted");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("required"));
		}
		try {
			server.createProgram(new byte[0]);
			fail("empty accepted");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("required"));
		}
		assertEquals(0L, server.programBytes());
	}

	/**
	 * The applier's own refusals, driven directly — a duplicate create and an unknown free are
	 * resync triggers on a mirror, and neither had a test.
	 */
	@Test
	public void theApplierRefusesADuplicateCreateAndAnUnknownFree() throws Exception {
		opengpu.v2.scene.SceneState state = new opengpu.v2.scene.SceneState();
		byte[] blob = tiny();
		opengpu.v2.scene.DeltaApplier.apply(state,
				new opengpu.v2.protocol.Delta.ProgramCreate(3, OcslWire.STAGE_PIXEL_POST, 1, blob));
		try {
			opengpu.v2.scene.DeltaApplier.apply(state,
					new opengpu.v2.protocol.Delta.ProgramCreate(3, OcslWire.STAGE_PIXEL_POST, 1, blob));
			fail("a duplicate program id applied");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage().contains("already exists"));
		}
		try {
			opengpu.v2.scene.DeltaApplier.apply(state,
					new opengpu.v2.protocol.Delta.ProgramFree(99));
			fail("freeing an unknown program applied");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage().contains("unknown program"));
		}
		// The failed applies must not have corrupted the table: the original entry survives.
		assertTrue(state.programs.containsKey(Integer.valueOf(3)));
		assertEquals(4, state.nextProgramId);
	}

	/**
	 * A program the VALIDATOR refuses never reaches the table, and never charges the ledger.
	 *
	 * The first version of this test never constructed an over-cap program at all — it built an
	 * under-cap one, asserted it was ACCEPTED, and the name promised a refusal it did not
	 * contain; a createProgram that never checked the structural cap passed it. The review that
	 * caught it is why this now hand-assembles the program: OcslBuilder runs the real validator
	 * in build() and refuses, so the over-cap blob has to be made from raw IrOps and encoded
	 * directly (IrCodec.encode runs IrStructure only — layout, not acceptance policy — which is
	 * exactly the codec/validator split that makes the blob producible).
	 */
	@Test
	public void aProgramOverTheStructuralCapIsRefusedAndChargesNothing() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		// TWO SEQUENTIAL 200-trip loops, the IrValidatorTest idiom: sequential loops do not
		// multiply against the unroll-product cap (each path is 200 <= 256), but their bodies SUM
		// into the structural charge — 1 splat + 200 + 200 + 1 out = 402, well past 256. A single
		// 257-trip loop would be refused too, but by the UNROLL cap with a different message, and
		// a test that fails for an unintended reason is not evidence about the rule it names.
		final int w = SurfaceTable.WORKING_BASE;
		final int k0 = OcslWire.OPERAND_CONST_FLAG; // constant-pool index 0
		java.util.ArrayList<IrOp> ops = new java.util.ArrayList<IrOp>();
		ops.add(new IrOp(OcslWire.OP_SPLAT, w, k0, 4));
		ops.add(new IrOp(OcslWire.OP_FOR, w + 1, 200, k0));
		ops.add(new IrOp(OcslWire.OP_ADD, w + 1, w + 1, k0));
		ops.add(new IrOp(OcslWire.OP_ENDFOR, -1));
		ops.add(new IrOp(OcslWire.OP_FOR, w + 2, 200, k0));
		ops.add(new IrOp(OcslWire.OP_ADD, w + 2, w + 2, k0));
		ops.add(new IrOp(OcslWire.OP_ENDFOR, -1));
		ops.add(new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, w));
		IrProgram overCap = new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL,
				new float[] { 1.0f }, ops, new java.util.ArrayList<String>(), w + 3);

		try {
			server.createProgram(IrCodec.encode(overCap));
			fail("a program charging 402 structural ops was accepted");
		} catch (IllegalArgumentException expected) {
			assertTrue("the refusal should be the validator's own, naming the cap, got: "
					+ expected.getMessage(),
					expected.getMessage().contains("structural ops, over the cap"));
		}
		assertEquals("a refused program charged the ledger anyway", 0L, server.programBytes());
	}

	// ---------------------------------------------------------------- persistence

	/**
	 * Programs survive the snapshot the SAVE is written from, through the persisted decoder.
	 *
	 * {@code ScenePersistence} encodes {@code scene.snapshot()} and reads it back with
	 * {@code decodePersisted}, so this is that exact pair. A program section that encoded but did
	 * not decode would delete the scene's stored bodies on restore, which is why the version bump
	 * carries a guardian test at all.
	 */
	@Test
	public void programsSurviveThePersistedSnapshotRoundTrip() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		byte[] one = ceiling();
		byte[] two = tiny();
		// c and d are DISTINCT programs, deliberately: a first draft made them identical, and
		// with two indistinguishable survivors a decoder that stored one entry under every key —
		// or crossed the records' fields — round-tripped clean. Different blob, stage-same,
		// different size is what makes the per-entry assertions below discriminate.
		byte[] three = blob(40);
		int a = server.createProgram(one);
		int c = server.createProgram(three);
		server.freeProgram(a);
		int d = server.createProgram(two);
		// snapshot() refuses to run with deltas staged — a snapshot taken mid-batch would stamp
		// state from batch N+1 with seq N. ScenePersistence rides the same method, so sealing
		// here is not test scaffolding, it is the production ordering.
		server.sealBatch();

		SceneSnapshot restored = SnapshotCodec.decodePersisted(
				SnapshotCodec.encode(server.snapshot()));

		assertFalse("a freed program came back from the save",
				restored.state.programs.containsKey(Integer.valueOf(a)));
		// EACH survivor checked against ITS OWN blob — the pairing, not just membership, is what
		// a field-crossing decoder would corrupt.
		assertTrue("program " + c + "'s blob did not survive intact",
				java.util.Arrays.equals(three,
						restored.state.programs.get(Integer.valueOf(c)).blobCopy()));
		assertTrue("program " + d + "'s blob did not survive intact",
				java.util.Arrays.equals(two,
						restored.state.programs.get(Integer.valueOf(d)).blobCopy()));
		assertEquals("the id counter did not survive, so a restore could reallocate a live id",
				server.state().nextProgramId, restored.state.nextProgramId);
		assertTrue("the restored state is not content-equal to the live one",
				server.state().contentEquals(restored.state));
	}

	/**
	 * The format freeze, pinned where a future edit will trip over it.
	 *
	 * 3.1 is the change that opened persistence, and {@code IrCodec.Source}'s contract was that
	 * {@code FORMAT_VERSION} moves to 1 in exactly that change. A blob now reaches a world save
	 * through the section above, so every opcode id and register block below is format identity.
	 */
	@Test
	public void theIrFormatIsFrozenBecauseProgramsNowPersist() throws Exception {
		assertEquals("FORMAT_VERSION moved without this test moving with it — if you bumped it for"
				+ " a real format change, update this constant AND write the migration; if it went"
				+ " back to 0, persistence must have been closed again, which it has not been",
				1, OcslWire.FORMAT_VERSION);
		// And the reason, asserted rather than described: the BLOB ITSELF must come back from
		// the persisted decoder byte-for-byte. A first draft asserted only that the snapshot got
		// LONGER than one blob, which an encoder that dropped the blob body still satisfied by
		// one accidental byte of margin.
		ServerScene server = new ServerScene(SCENE);
		byte[] source = tiny();
		int id = server.createProgram(source);
		server.sealBatch(); // see the round-trip test above: snapshot() is a batch-boundary call
		SceneSnapshot restored = SnapshotCodec.decodePersisted(
				SnapshotCodec.encode(server.snapshot()));
		assertTrue("the snapshot the save is written from does not carry the program's bytes, so"
				+ " the freeze would have no basis", java.util.Arrays.equals(source,
						restored.state.programs.get(Integer.valueOf(id)).blobCopy()));
	}
}
