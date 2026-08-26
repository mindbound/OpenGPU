package opengpu.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Test;

import opengpu.v2.protocol.BatchCodec;
import opengpu.v2.protocol.CodecException;
import opengpu.v2.protocol.Delta;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.CanvasCommand;

public class BatchCodecTest {

	private static SceneBatch sampleBatch() {
		ArrayList<Delta> deltas = new ArrayList<Delta>();
		deltas.add(new Delta.ResourceCreate(1, V2Wire.RES_CANVAS, 512, 288, 0, 0, 4096));
		deltas.add(new Delta.ResourceCreate(2, V2Wire.RES_TEXTURE, 16, 16, 1024, 0x123456789abcdefL, 0));
		deltas.add(new Delta.NodeCreate(1, V2Wire.NODE_CANVAS, 1));
		deltas.add(new Delta.NodeCreate(2, V2Wire.NODE_SPRITE, 2));
		deltas.add(new Delta.NodeProps(2, V2Wire.PROP_X | V2Wire.PROP_Y | V2Wire.PROP_TINT,
				new double[] { 12.5, -3.25, (double) (0xFF00FF00L) }));
		ArrayList<CanvasCommand> cmds = new ArrayList<CanvasCommand>();
		cmds.add(CanvasCommand.of(V2Wire.OP_SET_COLOR, 255, 128, 0, 255));
		cmds.add(CanvasCommand.of(V2Wire.OP_FILL));
		cmds.add(CanvasCommand.of(V2Wire.OP_LINE, 0, 0, 100, 50));
		cmds.add(CanvasCommand.text(4, 8, "héllo wörld"));
		cmds.add(CanvasCommand.of(V2Wire.OP_DRAW_TEXTURE_SUB, 2, 0, 0, 4, 4, 8, 8));
		cmds.add(CanvasCommand.of(V2Wire.OP_PUSH));
		cmds.add(CanvasCommand.of(V2Wire.OP_ROTATE, 0.5));
		cmds.add(CanvasCommand.of(V2Wire.OP_POP));
		deltas.add(new Delta.CanvasAppend(1, cmds));
		deltas.add(new Delta.CanvasPublish(1, cmds));
		deltas.add(new Delta.NodeFree(2));
		deltas.add(new Delta.ResourceFree(2));
		// v10: a mesh (both blobs inline), a 3D props update (full quat — all-or-none), and the
		// three uniform shapes (set float, set vec4 with immediate, CLEAR).
		deltas.add(new Delta.MeshCreate(3, meshVertices(3), meshTriangle()));
		deltas.add(new Delta.NodeProps(1,
				V2Wire.PROP_TZ | V2Wire.PROP_SZ | V2Wire.QUAT_PROPS_MASK,
				new double[] { 1.5, 2.0, 0.5, -0.5, 0.5, 0.5 }));
		deltas.add(new Delta.UniformSet(1, "speed", Delta.UniformSet.TYPE_FLOAT,
				new double[] { 2.5 }, false));
		deltas.add(new Delta.UniformSet(1, "tint4", Delta.UniformSet.TYPE_VEC4,
				new double[] { 0.1, 0.2, 0.3, 0.4 }, true));
		deltas.add(new Delta.UniformSet(1, "gone", Delta.UniformSet.TYPE_CLEAR,
				new double[0], false));
		deltas.add(new Delta.SceneProp(7, new byte[] { 1, 2, 3 }));
		return new SceneBatch("aaaa-bbbb-cccc-dddd", 5, 41, 123456789L, deltas);
	}

	static byte[] meshVertices(int count) {
		byte[] blob = new byte[count * V2Wire.MESH_VERTEX_STRIDE];
		for (int i = 0; i < blob.length; i++) {
			blob[i] = (byte) (i * 7);
		}
		return blob;
	}

	/** One triangle 0,1,2 as u16 LITTLE-endian — the blob-interior convention. */
	static byte[] meshTriangle() {
		return new byte[] { 0, 0, 1, 0, 2, 0 };
	}

	@Test
	public void roundTripPreservesEverything() throws Exception {
		SceneBatch batch = sampleBatch();
		SceneBatch decoded = BatchCodec.decode(BatchCodec.encode(batch));
		assertEquals(batch, decoded);
		assertEquals(5, decoded.epoch);
		assertEquals(41, decoded.seq);
		assertEquals(123456789L, decoded.serverTick);
	}

	@Test
	public void aMeshViaResourceCreateIsRefusedOnTheWire() throws Exception {
		// The delta constructor validates nothing, so a blob-less type-3 RES_CREATE can be
		// BUILT — the wire arm is one of the three sites that refuse it (decode here, the
		// apply arm, and the pre-v10 persisted loop).
		ArrayList<Delta> deltas = new ArrayList<Delta>();
		deltas.add(new Delta.ResourceCreate(9, V2Wire.RES_MESH, 3, 1, 114, 0L, 0));
		byte[] encoded = BatchCodec.encode(
				new SceneBatch("scene", 5, 1, 1L, deltas));
		try {
			BatchCodec.decode(encoded);
			fail("meshes must arrive via DELTA_MESH_CREATE, never RES_CREATE");
		} catch (CodecException expected) {
			assertTrue(expected.getMessage(),
					expected.getMessage().contains("DELTA_MESH_CREATE"));
		}
	}

	@Test
	public void aPartialQuaternionCannotExistOrDecode() {
		// Constructor side: all-or-none.
		try {
			new Delta.NodeProps(1, V2Wire.PROP_QX | V2Wire.PROP_QY, new double[] { 1, 2 });
			fail("a partial quaternion is not a rotation");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("all-or-none"));
		}
		// TZ/SZ alone are fine — they are ordinary scalar props.
		new Delta.NodeProps(1, V2Wire.PROP_TZ | V2Wire.PROP_SZ, new double[] { 1, 2 });
	}

	@Test
	public void aMalformedMeshBlobIsRefusedAtDecodeNotDeliveredToApply() throws Exception {
		// Hand-encode a DELTA_MESH_CREATE whose index points past its vertices; the delta
		// cannot be built (constructor validates), so the bytes are laid down by hand.
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
		out.writeShort(V2Wire.PROTOCOL_VERSION);
		out.writeUTF("scene");
		out.writeInt(5);   // epoch
		out.writeInt(1);   // seq
		out.writeLong(1L); // tick
		out.writeInt(1);   // one delta
		out.writeByte(V2Wire.DELTA_MESH_CREATE);
		out.writeInt(3);   // resId
		byte[] verts = meshVertices(2);
		out.writeInt(verts.length);
		out.write(verts);
		out.writeInt(6);
		// Index 2 with EXACTLY 2 vertices — the boundary, deliberately: the largest legal index
		// is vertexCount - 1, and a >= check weakened to > admits precisely this blob. An index
		// far past the count would pass under both spellings and pin nothing.
		out.write(new byte[] { 0, 0, 1, 0, 2, 0 });
		try {
			BatchCodec.decode(bytes.toByteArray());
			fail("an index equal to vertexCount must die in the codec");
		} catch (CodecException expected) {
			// "out of range FOR" — the index-range message specifically. The looser "out of
			// range" is also printed by the LENGTH checks, and a both-sides blob-order swap
			// would trip those instead (indexLen 72 passes, vertexLen 6 fails) — green on the
			// wrong check, which is exactly what this match must not allow.
			assertTrue(expected.getMessage(), expected.getMessage().contains("out of range for"));
		}
	}

	@Test
	public void aPartialQuaternionIsRefusedByTheDecoderItself() throws Exception {
		// The decode leg of the all-or-none rule, on hand-laid bytes: mask = PROP_QX alone is
		// INSIDE KNOWN_PROPS_MASK, so the mask check passes and the refusal must come from the
		// constructor invariant surfacing as a CodecException (BatchCodec's IAE conversion is
		// that rule's decode-side enforcement — its comment says so now).
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
		out.writeShort(V2Wire.PROTOCOL_VERSION);
		out.writeUTF("scene");
		out.writeInt(5);
		out.writeInt(1);
		out.writeLong(1L);
		out.writeInt(1);
		out.writeByte(V2Wire.DELTA_NODE_PROPS);
		out.writeInt(1);                 // nodeId
		out.writeInt(V2Wire.PROP_QX);    // a lone quaternion component
		out.writeDouble(0.5);
		try {
			BatchCodec.decode(bytes.toByteArray());
			fail("a lone quat component must not decode");
		} catch (CodecException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("all-or-none"));
		}
	}

	@Test
	public void handLaidV10DeltaBytesDecodeFieldForField() throws Exception {
		// The aHandWrittenV6 discipline for the two new deltas: the codec's own round trip is
		// encoder-against-its-own-decoder, so a symmetric field-order mistake round-trips
		// clean there and only independently laid bytes can catch it.
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
		out.writeShort(V2Wire.PROTOCOL_VERSION);
		out.writeUTF("scene");
		out.writeInt(5);
		out.writeInt(1);
		out.writeLong(1L);
		out.writeInt(2);
		out.writeByte(V2Wire.DELTA_MESH_CREATE);
		out.writeInt(7);                          // resId
		byte[] verts = meshVertices(3);
		out.writeInt(verts.length);               // vertex blob FIRST, length-prefixed
		out.write(verts);
		byte[] tri = meshTriangle();
		out.writeInt(tri.length);                 // then the index blob
		out.write(tri);
		out.writeByte(V2Wire.DELTA_UNIFORM_SET);
		out.writeInt(4);                          // nodeId
		out.writeUTF("speed");                    // name
		out.writeByte(2);                         // type = vec2
		out.writeDouble(1.25);
		out.writeDouble(-2.5);
		out.writeBoolean(true);                   // immediate LAST
		SceneBatch decoded = BatchCodec.decode(bytes.toByteArray());
		Delta.MeshCreate mesh = (Delta.MeshCreate) decoded.deltas.get(0);
		assertEquals(7, mesh.resId);
		assertEquals(3, mesh.vertexCount());
		assertTrue(Arrays.equals(verts, mesh.vertexCopy()));
		assertTrue(Arrays.equals(tri, mesh.indexCopy()));
		Delta.UniformSet u = (Delta.UniformSet) decoded.deltas.get(1);
		assertEquals(4, u.nodeId);
		assertEquals("speed", u.name);
		assertEquals(Delta.UniformSet.TYPE_VEC2, u.type);
		assertTrue(Arrays.equals(new double[] { 1.25, -2.5 }, u.values));
		assertTrue(u.immediate);
	}

	@Test
	public void the3dPropsLandOnTheirOwnNodeFields() {
		// Six DISTINCT values so a value-index desync or a swapped assignment cannot fake all
		// six landing where they belong (the swap-proof doctrine, applied to the apply path).
		opengpu.v2.scene.SceneState state = new opengpu.v2.scene.SceneState();
		opengpu.v2.scene.DeltaApplier.apply(state, new Delta.NodeCreate(1, V2Wire.NODE_GROUP, 0));
		opengpu.v2.scene.DeltaApplier.apply(state, new Delta.NodeProps(1,
				V2Wire.PROP_TZ | V2Wire.PROP_SZ | V2Wire.QUAT_PROPS_MASK,
				new double[] { 1.5, 2.0, 0.5, -0.5, 0.25, 0.75 }));
		opengpu.v2.scene.SceneNode n = state.nodes.get(Integer.valueOf(1));
		assertEquals(1.5, n.tz, 0.0);
		assertEquals(2.0, n.sz, 0.0);
		assertEquals(0.5, n.qx, 0.0);
		assertEquals(-0.5, n.qy, 0.0);
		assertEquals(0.25, n.qz, 0.0);
		assertEquals(0.75, n.qw, 0.0);
		// And mixed with a 2D bit + teleport, the ascending-bit-order contract holds across
		// the old/new boundary: x consumes first, teleport is consumed-not-stored, tz after.
		opengpu.v2.scene.DeltaApplier.apply(state, new Delta.NodeProps(1,
				V2Wire.PROP_X | V2Wire.PROP_TELEPORT | V2Wire.PROP_TZ,
				new double[] { 9.0, 1.0, 3.25 }));
		assertEquals(9.0, n.x, 0.0);
		assertEquals(3.25, n.tz, 0.0);
	}

	@Test
	public void aBatchOverTheMeshAllowanceIsRefusedAtDecode() throws Exception {
		// Six maximal meshes = ~1.13 MiB of blob against the 1 MiB per-batch allowance. The
		// SERVER-side counter is tested in MeshLedgerBoundTest; this is the DECODER's own
		// accumulator, which must hold even against a producer that skipped admission.
		ArrayList<Delta> deltas = new ArrayList<Delta>();
		byte[] verts = meshVertices(V2Wire.MAX_MESH_VERTEX_BYTES / V2Wire.MESH_VERTEX_STRIDE);
		for (int i = 0; i < 6; i++) {
			deltas.add(new Delta.MeshCreate(i + 1, verts, meshTriangle()));
		}
		byte[] encoded = BatchCodec.encode(new SceneBatch("scene", 5, 1, 1L, deltas));
		try {
			BatchCodec.decode(encoded);
			fail("the decoder's per-batch mesh accumulator never fired");
		} catch (CodecException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("per-batch cap"));
		}
	}

	@Test
	public void aMeshViaResourceCreateIsRefusedAtApplyToo() {
		// The decode-arm refusal has a server-side mirror: Delta.ResourceCreate's constructor
		// validates nothing, so a blob-less type-3 record can be BUILT in-process and the
		// shared apply path is the only guard on that route.
		opengpu.v2.scene.SceneState state = new opengpu.v2.scene.SceneState();
		try {
			opengpu.v2.scene.DeltaApplier.apply(state,
					new Delta.ResourceCreate(9, V2Wire.RES_MESH, 3, 1, 114, 0L, 0));
			fail("the apply arm must refuse a mesh ResourceCreate");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage(),
					expected.getMessage().contains("DELTA_MESH_CREATE"));
		}
		assertTrue("and nothing may be registered", state.resources.isEmpty());
	}

	@Test
	public void rejectsWrongProtocolVersion() {
		byte[] data = BatchCodec.encode(sampleBatch());
		data[1] = (byte) (data[1] + 1); // bump the version short's low byte
		try {
			BatchCodec.decode(data);
			fail("expected CodecException");
		} catch (CodecException e) {
			assertTrue(e.getMessage().contains("version"));
		}
	}

	@Test
	public void rejectsTruncation() {
		byte[] data = BatchCodec.encode(sampleBatch());
		for (int cut = 1; cut < data.length; cut += 7) {
			try {
				BatchCodec.decode(Arrays.copyOf(data, cut));
				fail("expected CodecException at cut " + cut);
			} catch (CodecException expected) {
				// every truncation point must fail cleanly
			}
		}
	}

	@Test
	public void rejectsGarbageWithoutHugeAllocation() {
		byte[] data = BatchCodec.encode(sampleBatch());
		// Corrupt the delta count to a huge value; decode must throw, not OOM.
		// Header: short version + UTF(2+19) + int epoch + int seq + long tick = 39 bytes in.
		int countOffset = 2 + 2 + 19 + 4 + 4 + 8;
		data[countOffset] = (byte) 0x7F;
		try {
			BatchCodec.decode(data);
			fail("expected CodecException");
		} catch (CodecException expected) {
		}
	}

	@Test
	public void rejectsUnknownDeltaType() throws Exception {
		ArrayList<Delta> deltas = new ArrayList<Delta>();
		deltas.add(new Delta.NodeFree(1));
		byte[] data = BatchCodec.encode(new SceneBatch("s", 3, 1, 0L, deltas));
		// Delta type byte: short + UTF("s": 2+1) + int epoch + int seq + long tick + int count.
		int typeOffset = 2 + 3 + 4 + 4 + 8 + 4;
		data[typeOffset] = 99;
		try {
			BatchCodec.decode(data);
			fail("expected CodecException");
		} catch (CodecException e) {
			assertTrue(e.getMessage().contains("delta type"));
		}
	}

	// Header for sceneId "s": short(2) + UTF(3) + epoch(4) + seq(4) + tick(8) + count(4) = 25.
	private static final int FIRST_DELTA_OFFSET = 25;

	private static byte[] singleDelta(Delta delta) {
		ArrayList<Delta> deltas = new ArrayList<Delta>();
		deltas.add(delta);
		return BatchCodec.encode(new SceneBatch("s", 3, 1, 0L, deltas));
	}

	@Test
	public void rejectsTrailingGarbage() {
		byte[] data = BatchCodec.encode(sampleBatch());
		byte[] extended = Arrays.copyOf(data, data.length + 100);
		Arrays.fill(extended, data.length, extended.length, (byte) 0xEE);
		try {
			BatchCodec.decode(extended);
			fail("expected CodecException");
		} catch (CodecException e) {
			assertTrue(e.getMessage().contains("Trailing"));
		}
	}

	@Test
	public void rejectsUnknownNodeType() {
		byte[] data = singleDelta(new Delta.NodeCreate(1, V2Wire.NODE_GROUP, 0));
		data[FIRST_DELTA_OFFSET + 1 + 4] = 99; // [type byte][int nodeId][byte nodeType]
		try {
			BatchCodec.decode(data);
			fail("expected CodecException");
		} catch (CodecException e) {
			assertTrue(e.getMessage().contains("node type"));
		}
	}

	@Test
	public void rejectsUnknownResourceType() {
		byte[] data = singleDelta(new Delta.ResourceCreate(1, V2Wire.RES_TEXTURE, 4, 4, 64, 1L, 0));
		data[FIRST_DELTA_OFFSET + 1 + 4] = 77; // [type byte][int resId][byte resType]
		try {
			BatchCodec.decode(data);
			fail("expected CodecException");
		} catch (CodecException e) {
			assertTrue(e.getMessage().contains("resource type"));
		}
	}

	@Test
	public void rejectsUnknownPropMaskBits() {
		byte[] data = singleDelta(new Delta.NodeProps(1, V2Wire.PROP_X, new double[] { 1 }));
		// [type byte][int nodeId][int mask]: set a bit above KNOWN_PROPS_MASK in the mask.
		// Byte index 2 of the big-endian mask covers bits 15..8, so 0x80 sets bit 15 — the
		// lowest bit still unknown now that v10's TZ/SZ/quat bits widened KNOWN_PROPS_MASK to
		// 0x7FFF. THIRD time this line has chased the mask: it previously set bit 9, and before
		// that bit 8, and each widening turned it into a truncation failure rather than the
		// mask rejection it is testing — which is exactly why the assertion below checks the
		// MESSAGE and not merely that something threw.
		data[FIRST_DELTA_OFFSET + 1 + 4 + 2] = (byte) 0x80; // mask becomes 0x00008001
		try {
			BatchCodec.decode(data);
			fail("expected CodecException");
		} catch (CodecException e) {
			assertTrue(e.getMessage().contains("mask"));
		}
	}

	@Test
	public void rejectsNegativeDeltaCount() {
		byte[] data = BatchCodec.encode(sampleBatch());
		int countOffset = 2 + 2 + 19 + 4 + 4 + 8;
		data[countOffset] = (byte) 0x80;
		try {
			BatchCodec.decode(data);
			fail("expected CodecException");
		} catch (CodecException expected) {
		}
	}

	@Test
	public void epochZeroIsRejected() {
		byte[] data = singleDelta(new Delta.NodeFree(1));
		// Epoch int sits right after [short version][UTF "s"]: offsets 5..8.
		for (int i = 5; i <= 8; i++) {
			data[i] = 0;
		}
		try {
			BatchCodec.decode(data);
			fail("expected CodecException");
		} catch (CodecException e) {
			assertTrue(e.getMessage().contains("Epoch"));
		}
	}

	@Test
	public void emptyBatchRoundTrips() throws Exception {
		// The codec allows zero deltas; the MIRROR rejects empty in-order batches — that rule
		// lives in SceneMirror, pinned by MirrorOrderingTest.
		SceneBatch empty = new SceneBatch("s", 9, 7, 3L, new ArrayList<Delta>());
		assertEquals(empty, BatchCodec.decode(BatchCodec.encode(empty)));
	}

	@Test
	public void tintSignEdgeValuesRoundTripExactly() throws Exception {
		long[] edges = { 0x80000000L, 0xFF000000L, 0xFFFFFFFFL, 0x00000001L, 0x7FFFFFFFL };
		for (long tint : edges) {
			SceneBatch batch = new SceneBatch("s", 2, 1, 0L, java.util.Collections.<Delta>singletonList(
					new Delta.NodeProps(1, V2Wire.PROP_TINT, new double[] { (double) tint })));
			SceneBatch decoded = BatchCodec.decode(BatchCodec.encode(batch));
			double value = ((Delta.NodeProps) decoded.deltas.get(0)).values[0];
			assertEquals((int) tint, (int) (long) value);
		}
	}
}
