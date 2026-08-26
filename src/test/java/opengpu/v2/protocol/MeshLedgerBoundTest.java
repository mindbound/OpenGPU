package opengpu.v2.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import opengpu.v2.scene.ServerScene;

/**
 * The mesh ledger's relationships, pinned the way {@link ProgramLedgerBoundTest} pins the
 * program ledger's — the same silent-desync failure is on the table (a server that can build a
 * snapshot every client refuses whole), plus one relationship the program side never had:
 * the index WIDTH is frozen wire format, so the vertex cap must keep u16 sufficient forever.
 *
 * KNOWN OPEN SIBLING, recorded rather than closed here: the shared resource-count bound is
 * SnapshotCodec.MAX_ENTRIES (65536), and 65537 one-pixel textures would cross it with 98.44%
 * of the VRAM budget free (16 MiB / 4 B per one-pixel texture = 4,194,304 permitted by bytes;
 * a first draft of this line copied 99.84% from the design ruling — the ruling's own slip).
 * That hole predates meshes and is a texture-admission question, not a mesh one; the cheap
 * closure is one ServerScene admission line bounding state.resources.size(), admission-only.
 */
public class MeshLedgerBoundTest {

	/** The smallest legal mesh: one 36-byte vertex + one 6-byte triangle. */
	private static final int SMALLEST_MESH_BYTES = V2Wire.MESH_VERTEX_STRIDE
			+ 3 * V2Wire.MESH_INDEX_BYTES;

	@Test
	public void u16IndicesAreSufficientForeverUnderTheVertexCap() {
		int maxVertices = V2Wire.MAX_MESH_VERTEX_BYTES / V2Wire.MESH_VERTEX_STRIDE;
		assertEquals("the derivation below rests on this quotient", 5461, maxVertices);
		// The frozen index width is 2 bytes; the largest addressable vertex is 65535. The cap
		// sits at a ~12x margin and the assertion below pins the 8x FLOOR (the BatchSizeBoundTest
		// headroom norm, so the failure arrives as a design question): raising
		// MAX_MESH_VERTEX_BYTES past this makes a legal mesh contain vertices its own indices
		// cannot name, which is a FORMAT break, not a cap raise.
		assertTrue("the vertex cap admits " + maxVertices + " vertices against u16's 65536 —"
				+ " a mesh could legally hold vertices its indices cannot address",
				maxVertices < 65536);
		assertTrue("and with margin: " + maxVertices + " * 8 should stay under 65536, so a cap"
				+ " raise trips here before it approaches the cliff",
				maxVertices * 8L < 65536L);
	}

	@Test
	public void theByteLedgerBoundsTheMeshCountBelowTheSnapshotEntryCap() {
		long impliedCount = (long) ServerScene.MAX_MESH_BYTES / SMALLEST_MESH_BYTES;
		System.out.println("[mesh ledger] smallest mesh = " + SMALLEST_MESH_BYTES + " B; "
				+ ServerScene.MAX_MESH_BYTES + " B admits at most " + impliedCount
				+ " meshes, against SnapshotCodec.MAX_ENTRIES = " + SnapshotCodec.MAX_ENTRIES);
		assertTrue("the ledger admits up to " + impliedCount + " meshes but the snapshot decoder"
				+ " refuses more than " + SnapshotCodec.MAX_ENTRIES + " resources — the server"
				+ " could build a snapshot every client rejects",
				impliedCount <= SnapshotCodec.MAX_ENTRIES);
		// The room, pinned, not just the crossing (ProgramLedgerBoundTest's reasoning).
		assertTrue("the two caps are within 2x of crossing (" + impliedCount + " vs "
				+ SnapshotCodec.MAX_ENTRIES + ")",
				impliedCount * 2 <= SnapshotCodec.MAX_ENTRIES);
	}

	@Test
	public void theLedgerAdmitsAtLeastTwoMaximumSizeMeshes() {
		long worstCase = (long) ServerScene.MAX_MESH_BYTES
				/ (V2Wire.MAX_MESH_VERTEX_BYTES + V2Wire.MAX_MESH_INDEX_BYTES);
		assertTrue("the ledger admits " + worstCase + " maximum-size meshes; fewer than 2 makes"
				+ " the cap unusable rather than protective (the ProgramLedgerBoundTest floor,"
				+ " which is why MAX_MESH_VERTEX_BYTES is 192 KiB and not 256)",
				worstCase >= 2);
	}

	@Test
	public void theBatchAllowanceIsTwiceTheLedgerAndUnderTheDecoderCeiling() {
		assertEquals("the 2x-ledger batch convention (a batch spans two tick allowances)",
				2L * ServerScene.MAX_MESH_BYTES, (long) V2Wire.MAX_MESH_BYTES_PER_BATCH);
		assertTrue("the largest per-batch allowance must keep the 8x margin against the decoder"
				+ " ceiling", V2Wire.MAX_MESH_BYTES_PER_BATCH * 8L <= BatchCodec.MAX_INFLATED_BYTES);
	}

	// ---- Producer behaviour: admission refuses BEFORE work, and refusals cost the caller ----

	private static byte[] vertices(int count) {
		byte[] blob = new byte[count * V2Wire.MESH_VERTEX_STRIDE];
		for (int i = 0; i < blob.length; i++) {
			blob[i] = (byte) i;
		}
		return blob;
	}

	private static byte[] oneTriangle() {
		return new byte[] { 0, 0, 1, 0, 2, 0 };
	}

	@Test
	public void aMeshSceneSurvivesTheRealEncodeDecodeRoundTrip() throws Exception {
		// THE ENCODE SIDE — the panel found every mesh-bearing persisted fixture was
		// hand-written (decode-only), so SnapshotCodec.encode's mesh tail, its bytes-present
		// guard, and SceneState.copyStructure's mesh-bytes carry had ZERO test execution, on
		// the exact path ScenePersistence saves through. Non-identity TRS values so an
		// encode-order permutation among the six doubles cannot hide behind zeros.
		ServerScene scene = new ServerScene("mesh-roundtrip");
		int mesh = scene.createMesh(vertices(3), oneTriangle());
		int node = scene.createNode(V2Wire.NODE_MESH_INSTANCE, mesh);
		opengpu.v2.scene.DeltaApplier.apply(scene.state(), new opengpu.v2.protocol.Delta.NodeProps(
				node, V2Wire.PROP_TZ | V2Wire.PROP_SZ | V2Wire.QUAT_PROPS_MASK,
				new double[] { 11.5, 12.5, 13.5, 14.5, 15.5, 16.5 }));
		scene.setUniform(node, "speed", new double[] { 2.5 }, false);
		scene.sealBatch();

		opengpu.v2.scene.SceneSnapshot decoded =
				SnapshotCodec.decode(SnapshotCodec.encode(scene.snapshot()));
		opengpu.v2.scene.ResourceInfo restored = decoded.state.resources.get(Integer.valueOf(mesh));
		assertTrue("mesh bytes must survive the structure copy and the codec",
				restored.bytes != null && restored.bytes.length
						== vertices(3).length + oneTriangle().length);
		assertTrue("and byte-for-byte", java.util.Arrays.equals(
				scene.state().resources.get(Integer.valueOf(mesh)).bytes, restored.bytes));
		opengpu.v2.scene.SceneNode n = decoded.state.nodes.get(Integer.valueOf(node));
		assertEquals(11.5, n.tz, 0.0);
		assertEquals(12.5, n.sz, 0.0);
		assertEquals(13.5, n.qx, 0.0);
		assertEquals(14.5, n.qy, 0.0);
		assertEquals(15.5, n.qz, 0.0);
		assertEquals(16.5, n.qw, 0.0);
		assertTrue("the whole state converges through the codec",
				scene.state().contentEquals(decoded.state));
	}

	@Test
	public void createMeshRefusesTheFormatBeforeTouchingTheLedger() {
		ServerScene scene = new ServerScene("mesh-fmt");
		try {
			scene.createMesh(new byte[35], oneTriangle());
			fail("a 35-byte vertex blob is below the one-vertex floor");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("smaller"));
		}
		try {
			// 37 bytes: PAST the floor, so this drives the stride check itself — a 35-byte
			// blob dies at the floor and would leave the divisibility branch untested.
			scene.createMesh(new byte[37], oneTriangle());
			fail("a 37-byte vertex blob is not a multiple of the stride");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("stride"));
		}
		try {
			scene.createMesh(vertices(2), new byte[] { 0, 0, 1, 0, 3, 0 });
			fail("index 3 is out of range for 2 vertices");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("out of range for"));
		}
		assertEquals("refusals must not consume ledger bytes", 0L, scene.meshBytes());
	}

	@Test
	public void theLedgerRefusesAndFreesReopenIt() {
		ServerScene scene = new ServerScene("mesh-ledger");
		// Two maximum-vertex meshes fit (the usability floor); a third must be refused.
		int a = scene.createMesh(vertices(V2Wire.MAX_MESH_VERTEX_BYTES / V2Wire.MESH_VERTEX_STRIDE),
				oneTriangle());
		scene.createMesh(vertices(V2Wire.MAX_MESH_VERTEX_BYTES / V2Wire.MESH_VERTEX_STRIDE),
				oneTriangle());
		try {
			scene.createMesh(vertices(V2Wire.MAX_MESH_VERTEX_BYTES / V2Wire.MESH_VERTEX_STRIDE),
					oneTriangle());
			fail("the ledger should be exhausted");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("mesh budget"));
		}
		scene.freeResource(a);
		// The LEDGER reopens on free; whether the create lands now also depends on the
		// per-batch counter, which does NOT reopen until the seal — the create/free-loop bound.
		assertTrue("freeing must return the ledger bytes",
				scene.meshBudgetRemaining() >= V2Wire.MAX_MESH_VERTEX_BYTES);
	}

	@Test
	public void theBatchCounterRefusesChurnTheLedgerCannotSee() {
		ServerScene scene = new ServerScene("mesh-batch");
		int maxVerts = V2Wire.MAX_MESH_VERTEX_BYTES / V2Wire.MESH_VERTEX_STRIDE;
		// Create/free in a loop: the ledger never exceeds one mesh, but the staged bytes climb.
		// MAX_MESH_BYTES_PER_BATCH / (192 KiB + 6 B) = 5 whole creates; the 6th must refuse.
		for (int i = 0; i < 5; i++) {
			int id = scene.createMesh(vertices(maxVerts), oneTriangle());
			scene.freeResource(id);
		}
		try {
			scene.createMesh(vertices(maxVerts), oneTriangle());
			fail("the per-batch mesh allowance should be exhausted while the ledger is empty");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("Batch mesh payload"));
		}
		assertEquals("the ledger really was empty the whole time", 0L, scene.meshBytes());
		// The refusal clears at the seal, not by waiting inside the same batch.
		scene.sealBatch();
		scene.createMesh(vertices(maxVerts), oneTriangle());
	}
}
