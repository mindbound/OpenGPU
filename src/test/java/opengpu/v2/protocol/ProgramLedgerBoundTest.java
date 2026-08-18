package opengpu.v2.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;

import org.junit.Test;

import opengpu.v2.ocsl.Expr;
import opengpu.v2.ocsl.IrCodec;
import opengpu.v2.ocsl.IrOp;
import opengpu.v2.ocsl.IrProgram;
import opengpu.v2.ocsl.IrValidator;
import opengpu.v2.ocsl.OcslBuilder;
import opengpu.v2.ocsl.OcslWire;
import opengpu.v2.ocsl.SurfaceTable;
import opengpu.v2.scene.ServerScene;

/**
 * The program ledger's OTHER dimension, and the decoder bounds that back it.
 *
 * A byte cap is not a count cap. {@code MAX_SUBMIT_BYTES} bounded payload bytes while the zero-arity
 * ops encode at one byte each, so a legal 64 KiB payload declared 65,532 commands — 16x any
 * canvas's cap. The same question has to be asked of the program ledger: 256 KiB of programs is how
 * MANY programs, and does that number stay under what the snapshot decoder will accept?
 *
 * If it does not, the server can build a snapshot that every client refuses whole — a silent
 * desync, invisible to convergence checking because no mirror ever applies it. That is the exact
 * failure {@code MAX_DELTAS} was lowered to close, and this test is what keeps the program side
 * from reintroducing it when someone raises a constant.
 *
 * This test lives in {@code opengpu.v2.protocol} so it can read {@code SnapshotCodec.MAX_ENTRIES},
 * which is package-private on purpose.
 */
public class ProgramLedgerBoundTest {

	/** The smallest thing the builder will produce that is still a valid program. */
	private static byte[] smallestRealProgram() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_POST);
		Expr colour = b.constant(0f, 0f, 0f, 1f);
		b.out(OcslWire.PROP_COLOR, colour);
		return IrCodec.encode(b.build());
	}

	/**
	 * The count implied by the byte ledger stays under the snapshot decoder's entry cap, with
	 * margin, DERIVED from a measured smallest program rather than assumed.
	 */
	@Test
	public void theByteLedgerBoundsTheProgramCountBelowTheSnapshotEntryCap() throws Exception {
		int smallest = smallestRealProgram().length;
		// The fixed envelope is exactly 17 bytes: magic 4 + version 2 + stage 1 + reserved 1 +
		// constCount 2 + declaredRegisters 2 + opCount 2 + nameCount 1 + trailing guard 2. (A
		// first draft guarded ">= 12" and called it "the envelope alone", which no envelope
		// regression could ever have tripped.)
		assertTrue("a program encoded to " + smallest + " bytes, below the 17-byte envelope —"
				+ " the derivation below would be resting on a wrong number", smallest >= 17);

		long impliedCount = ServerScene.MAX_PROGRAM_BYTES / smallest;
		System.out.println("[ledger] smallest program = " + smallest + " B; "
				+ ServerScene.MAX_PROGRAM_BYTES + " B admits at most " + impliedCount
				+ " programs, against SnapshotCodec.MAX_ENTRIES = " + SnapshotCodec.MAX_ENTRIES);

		assertTrue("the ledger admits up to " + impliedCount + " programs but the snapshot decoder"
				+ " refuses more than " + SnapshotCodec.MAX_ENTRIES + " — the server could build a"
				+ " snapshot every client rejects. Lower MAX_PROGRAM_BYTES or raise MAX_ENTRIES,"
				+ " and read the MAX_DELTAS note before choosing which",
				impliedCount <= SnapshotCodec.MAX_ENTRIES);
		// The wrong answer written in: asserting merely "<=" would pass at exactly the crossing
		// point, where any future shrink of the envelope reopens the gap. The decision was taken
		// with room, so pin the room.
		assertTrue("the two caps are within 2x of crossing (" + impliedCount + " vs "
				+ SnapshotCodec.MAX_ENTRIES + "); that is not the margin the sizing assumed",
				impliedCount * 2 <= SnapshotCodec.MAX_ENTRIES);
	}

	/**
	 * THE OP CAP DOES NOT BOUND BLOB BYTES, and the ledger's original sizing assumed it did.
	 *
	 * `MAX_STRUCTURAL_OPS` charges OPS. The CONSTANT POOL is charged by nothing on the accept
	 * path: {@code IrStructure.checkPool} caps it at {@code MAX_CONSTANTS} (1024) entries and
	 * checks width and finiteness, {@code IrValidator} does not mention pool size anywhere, and
	 * an UNREFERENCED constant is legal. Each vec4 entry encodes to 1 + 4*4 = 17 bytes, so a
	 * program charging a handful of structural ops can be ~17 KiB — an order of magnitude past
	 * the "1810 bytes at the acceptance ceiling" the ledger was sized from.
	 *
	 * That figure came from a straight-line chain of two-operand ADDs, which is the CHEAPEST op
	 * shape there is, and it was read as a maximum. This test measures the other axis so the
	 * sizing rests on a bound rather than on the shape of one example, and so that raising
	 * MAX_CONSTANTS later trips here.
	 *
	 * The OC surface takes raw bytes, so a program of this shape is authorable from Lua directly
	 * — it does not need the builder, which would never emit an unreferenced pool.
	 */
	@Test
	public void theOpCapDoesNotBoundBlobBytesAndTheLedgerMustBeSizedForThat() throws Exception {
		// A minimal two-op program carrying a FULL constant pool of vec4s, all but two unused.
		float[][] pool = new float[OcslWire.MAX_CONSTANTS][];
		for (int i = 0; i < pool.length; i++) {
			pool[i] = new float[] { 0.5f, 0.5f, 0.5f, 1.0f };
		}
		// The destination must be a WORKING register: registers below SurfaceTable.WORKING_BASE
		// are built-in inputs and the validator refuses a write to them. (IrCodecTest's fixtures
		// write to register 0 and pass, because the CODEC does not check register semantics —
		// only the validator does, and those tests never validate.)
		final int dst = SurfaceTable.WORKING_BASE;
		ArrayList<IrOp> ops = new ArrayList<IrOp>();
		ops.add(new IrOp(OcslWire.OP_MUL, dst,
				OcslWire.OPERAND_CONST_FLAG, OcslWire.OPERAND_CONST_FLAG | 1));
		ops.add(new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, dst));
		// declaredRegisters is an ABSOLUTE index bound, not a count of working slots: the
		// validator refuses "writes register 112, outside the declared 4". So it must cover the
		// built-in and uniform blocks plus the one vec4 this program actually uses.
		IrProgram fat = new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL, pool, ops,
				new ArrayList<String>(), SurfaceTable.WORKING_BASE + 4);

		long charge = IrValidator.validate(fat).structuralOps;
		byte[] encoded = IrCodec.encode(fat);
		System.out.println("[ledger] pool-heavy program: charge = " + charge + " structural ops, "
				+ encoded.length + " B; the ledger holds "
				+ (ServerScene.MAX_PROGRAM_BYTES / encoded.length) + " of them");

		assertTrue("this program must VALIDATE — if it does not, the pool is bounded after all"
				+ " and this whole finding dissolves", charge <= IrValidator.MAX_STRUCTURAL_OPS);
		// The exclusion, and the entire point: a tiny structural charge with a huge blob.
		assertTrue("charge " + charge + " is not small; pick a cheaper program", charge <= 8);
		assertTrue("a program charging " + charge + " ops encoded to only " + encoded.length
				+ " bytes — if the pool were charged or refused, the ledger's op-based sizing"
				+ " would be sound and this test should be deleted along with the byte cap",
				encoded.length > 16000);
	}

	/**
	 * The ledger's real capacity, stated against the largest blob {@code createProgram} accepts
	 * rather than against the cheapest op shape.
	 */
	@Test
	public void theLedgerCapacityIsStatedAgainstTheBlobCeilingNotTheOpCeiling() throws Exception {
		// createProgram's only byte bound is the codec ceiling, so this is the honest worst case.
		long worstCase = ServerScene.MAX_PROGRAM_BYTES / OcslWire.MAX_BLOB_BYTES;
		System.out.println("[ledger] at the blob ceiling (" + OcslWire.MAX_BLOB_BYTES
				+ " B) the ledger holds " + worstCase + " programs");
		assertTrue("the ledger admits fewer than 2 maximum-size programs, which makes the cap"
				+ " unusable rather than protective", worstCase >= 2);
		// And the op-heavy figure, kept because the DESIGN argument cites it — but labelled as
		// what it is: the cost of ONE SHAPE, not a maximum. A vec4 ADD chain is the cheapest
		// bytes-per-charged-op program there is, which is exactly why quoting its size as the
		// ceiling understated the real worst case by more than an order of magnitude.
		int opHeavy = IrCodec.encode(opHeavyShape()).length;
		System.out.println("[ledger] op-heavy vec4 chain = " + opHeavy + " B; the ledger holds "
				+ (ServerScene.MAX_PROGRAM_BYTES / opHeavy) + " of them");
		assertTrue("the op-heavy shape is no longer far cheaper than the blob ceiling, so the"
				+ " 'typical vs maximum' distinction this test draws has collapsed and the"
				+ " ledger's sizing argument needs rewriting, not just its numbers",
				opHeavy * 4 < OcslWire.MAX_BLOB_BYTES);
	}

	/** The largest vec4 ADD chain that validates — frame-bound at 253 charged ops, not 256. */
	private static IrProgram opHeavyShape() {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_POST);
		Expr acc = b.constant(0f, 0f, 0f, 1f);
		for (int i = 0; i < IrValidator.MAX_STRUCTURAL_OPS - 4; i++) {
			acc = acc.add(b.f(1.0f));
		}
		b.out(OcslWire.PROP_COLOR, acc);
		return b.build();
	}

	// ---------------------------------------------------------------- decoder bounds

	private static byte[] batchWithRawProgramDelta(int programId, byte stage, int structuralOps,
			int declaredLen, byte[] blobBytes) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeShort(V2Wire.PROTOCOL_VERSION);
		out.writeUTF("s");
		out.writeInt(1); // epoch
		out.writeInt(1); // seq
		out.writeLong(1L);
		out.writeInt(1); // one delta
		out.writeByte(V2Wire.DELTA_PROGRAM_CREATE);
		out.writeInt(programId);
		out.writeByte(stage);
		out.writeInt(structuralOps);
		out.writeInt(declaredLen);
		out.write(blobBytes);
		out.flush();
		return bytes.toByteArray();
	}

	/**
	 * A crafted structural charge is refused by the DECODER.
	 *
	 * The charge is the server's verdict and no mirror re-derives it, so the decoder is the only
	 * thing between a hostile number and whatever later reads it — the per-stage cap review
	 * (ANIM-16) is meant to read exactly this field off real programs.
	 */
	@Test
	public void aCraftedStructuralChargeIsRefused() throws Exception {
		byte[] blob = smallestRealProgram();
		byte[] batch = batchWithRawProgramDelta(1, OcslWire.STAGE_PIXEL_POST,
				IrValidator.MAX_STRUCTURAL_OPS + 1, blob.length, blob);
		try {
			BatchCodec.decode(batch);
			fail("a structural charge above the acceptance cap decoded anyway");
		} catch (CodecException expected) {
			assertTrue("expected the charge refusal, got: " + expected.getMessage(),
					expected.getMessage().contains("structural charge"));
		}
	}

	/** A blob length past the codec ceiling dies before the allocation it would have forced. */
	@Test
	public void aCraftedBlobLengthIsRefusedBeforeAllocating() throws Exception {
		byte[] batch = batchWithRawProgramDelta(1, OcslWire.STAGE_PIXEL_POST, 1,
				OcslWire.MAX_BLOB_BYTES + 1, new byte[] { 1, 2, 3, 4 });
		try {
			BatchCodec.decode(batch);
			fail("a blob length over MAX_BLOB_BYTES decoded anyway");
		} catch (CodecException expected) {
			assertTrue("expected the length refusal, got: " + expected.getMessage(),
					expected.getMessage().contains("blob length"));
		}
	}

	/** A declared length longer than the bytes present is truncation, not a resizable payload. */
	@Test
	public void aDeclaredLengthPastTheEndOfTheDataIsRefused() throws Exception {
		byte[] batch = batchWithRawProgramDelta(1, OcslWire.STAGE_PIXEL_POST, 1, 4096,
				new byte[] { 1, 2, 3, 4 });
		try {
			BatchCodec.decode(batch);
			fail("a program delta claiming more bytes than it carries decoded anyway");
		} catch (CodecException expected) {
			// The SPECIFIC refusal — a first draft asserted only that a message existed, which
			// any CodecException at all satisfies, including "Unknown delta type 12" with the
			// whole case deleted from the switch.
			assertTrue("expected the availability refusal, got: " + expected.getMessage(),
					expected.getMessage().contains("remaining data"));
		}
	}

	/** A zero-length blob is refused by the decoder, not left to blow up in the Delta ctor. */
	@Test
	public void aZeroLengthBlobIsRefusedAsACodecError() throws Exception {
		byte[] batch = batchWithRawProgramDelta(1, OcslWire.STAGE_PIXEL_POST, 1, 0, new byte[0]);
		try {
			BatchCodec.decode(batch);
			fail("a zero-length program blob decoded anyway");
		} catch (CodecException expected) {
			// Without the len < 1 clause this surfaces as ProgramCreate's
			// IllegalArgumentException — an unchecked exception escaping a `throws
			// CodecException` API, which the inbound drain's catch would not contain.
			assertTrue("expected the length refusal, got: " + expected.getMessage(),
					expected.getMessage().contains("blob length"));
		}
	}

	/**
	 * The decoder refuses the two program ids the applier's counter-advance cannot digest: an id
	 * below 1, and Integer.MAX_VALUE, whose {@code + 1} would wrap {@code nextProgramId} to
	 * MIN_VALUE — strictly below the table's own last key, the exact state
	 * {@code SnapshotCodec.decode} refuses a snapshot for, planted through a batch nothing else
	 * would reject.
	 */
	@Test
	public void programIdsTheCounterCannotDigestAreRefused() throws Exception {
		byte[] blob = smallestRealProgram();
		for (int badId : new int[] { 0, -5, Integer.MAX_VALUE }) {
			byte[] batch = batchWithRawProgramDelta(badId, OcslWire.STAGE_PIXEL_POST, 1,
					blob.length, blob);
			try {
				BatchCodec.decode(batch);
				fail("program id " + badId + " decoded anyway");
			} catch (CodecException expected) {
				assertTrue("expected the id refusal for " + badId + ", got: "
						+ expected.getMessage(),
						expected.getMessage().contains("Program id out of range"));
			}
		}
		// And the largest DIGESTIBLE id still decodes — the boundary's legal side.
		byte[] batch = batchWithRawProgramDelta(Integer.MAX_VALUE - 1, OcslWire.STAGE_PIXEL_POST,
				1, blob.length, blob);
		Delta.ProgramCreate d = (Delta.ProgramCreate) BatchCodec.decode(batch).deltas.get(0);
		assertEquals(Integer.MAX_VALUE - 1, d.programId);
	}

	/**
	 * The per-batch blob aggregate: deltas that are each individually legal must not compose into
	 * a batch past {@code MAX_PROGRAM_BYTES_PER_BATCH} — the count cap and the per-blob cap are
	 * each enforced, but their PRODUCT is what a receiver allocates, and 32768 deltas times 64 KiB
	 * is three orders of magnitude past the inflate ceiling.
	 */
	@Test
	public void theBatchBlobAggregateIsBoundedAtTheDecoder() throws Exception {
		// 9 x 64 KiB = 576 KiB > 512 KiB. Fabricated blobs: BatchCodec does not parse blob
		// contents (the IR codec is the client's re-validation step), so filler is legal here.
		int count = V2Wire.MAX_PROGRAM_BYTES_PER_BATCH / OcslWire.MAX_BLOB_BYTES + 1;
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeShort(V2Wire.PROTOCOL_VERSION);
		out.writeUTF("s");
		out.writeInt(1);
		out.writeInt(1);
		out.writeLong(1L);
		out.writeInt(count);
		byte[] filler = new byte[OcslWire.MAX_BLOB_BYTES];
		for (int i = 0; i < count; i++) {
			out.writeByte(V2Wire.DELTA_PROGRAM_CREATE);
			out.writeInt(1 + i);
			out.writeByte(OcslWire.STAGE_PIXEL_POST);
			out.writeInt(1);
			out.writeInt(filler.length);
			out.write(filler);
		}
		out.flush();
		try {
			BatchCodec.decode(bytes.toByteArray());
			fail(count + " ceiling-sized program deltas decoded in one batch");
		} catch (CodecException expected) {
			assertTrue("expected the per-batch refusal, got: " + expected.getMessage(),
					expected.getMessage().contains("per-batch cap"));
		}
	}

	/**
	 * The producer and decoder constants must agree, and the producer's must reference the
	 * ledger: the batch cap is defined as 2x MAX_PROGRAM_BYTES (the tick-spanning argument
	 * MAX_WRITE_BYTES_PER_BATCH documents), and this is the only place the cross-package
	 * relationship can be asserted.
	 */
	@Test
	public void theBatchCapIsTwiceTheLedger() {
		assertEquals("V2Wire.MAX_PROGRAM_BYTES_PER_BATCH must track 2x ServerScene's ledger —"
				+ " they live in different packages, so only this test ties them",
				2L * ServerScene.MAX_PROGRAM_BYTES, (long) V2Wire.MAX_PROGRAM_BYTES_PER_BATCH);
	}

	@Test
	public void anUnknownStageIsRefused() throws Exception {
		byte[] blob = smallestRealProgram();
		byte[] batch = batchWithRawProgramDelta(1, (byte) 99, 1, blob.length, blob);
		try {
			BatchCodec.decode(batch);
			fail("an unknown program stage decoded anyway");
		} catch (CodecException expected) {
			assertTrue("expected the stage refusal, got: " + expected.getMessage(),
					expected.getMessage().contains("Unknown program stage"));
		}
	}

	/**
	 * The legal shape still gets through — the other side of every refusal above — exercised AT
	 * THE BOUNDARIES, not in the comfortable middle: a charge of exactly MAX_STRUCTURAL_OPS and
	 * a declared length of exactly MAX_BLOB_BYTES must both decode, or a silent {@code >} vs
	 * {@code >=} slip in either bound turns the largest legal program into a refused one and no
	 * refusal test notices.
	 */
	@Test
	public void aWellFormedProgramDeltaStillDecodesAtTheBounds() throws Exception {
		byte[] blob = smallestRealProgram();
		byte[] batch = batchWithRawProgramDelta(7, OcslWire.STAGE_PIXEL_POST,
				IrValidator.MAX_STRUCTURAL_OPS, blob.length, blob);
		SceneBatch decoded = BatchCodec.decode(batch);
		assertEquals(1, decoded.deltas.size());
		Delta.ProgramCreate d = (Delta.ProgramCreate) decoded.deltas.get(0);
		assertEquals(7, d.programId);
		assertEquals(OcslWire.STAGE_PIXEL_POST, d.stage);
		assertEquals(IrValidator.MAX_STRUCTURAL_OPS, d.structuralOps);
		assertTrue("the blob did not survive", java.util.Arrays.equals(blob, d.blobCopy()));

		// A blob of exactly MAX_BLOB_BYTES. Fabricated filler: the batch codec does not parse
		// blob contents, so only the LENGTH boundary is under test here.
		byte[] atCeiling = new byte[OcslWire.MAX_BLOB_BYTES];
		Delta.ProgramCreate big = (Delta.ProgramCreate) BatchCodec.decode(
				batchWithRawProgramDelta(8, OcslWire.STAGE_PIXEL_POST, 1,
						atCeiling.length, atCeiling)).deltas.get(0);
		assertEquals(OcslWire.MAX_BLOB_BYTES, big.blobLength());
	}

	/** Both new delta types survive the encoder's own round trip, blob included. */
	@Test
	public void bothProgramDeltasRoundTripThroughTheEncoder() throws Exception {
		byte[] blob = smallestRealProgram();
		ArrayList<Delta> deltas = new ArrayList<Delta>();
		deltas.add(new Delta.ProgramCreate(3, OcslWire.STAGE_PIXEL_POST, 4, blob));
		deltas.add(new Delta.ProgramFree(3));
		SceneBatch back = BatchCodec.decode(
				BatchCodec.encode(new SceneBatch("s", 1, 1, 1L, deltas)));
		assertEquals(2, back.deltas.size());
		assertEquals("ProgramCreate did not round trip; if equals() omits the blob this passes"
				+ " with the bytes dropped", deltas.get(0), back.deltas.get(0));
		assertEquals(deltas.get(1), back.deltas.get(1));
	}
}
