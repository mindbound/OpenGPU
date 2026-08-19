package opengpu.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;

import org.junit.Test;

import opengpu.v2.protocol.BatchCodec;
import opengpu.v2.protocol.CodecException;
import opengpu.v2.protocol.Delta;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.SnapshotCodec;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.DeltaApplier;
import opengpu.v2.scene.SceneMirror;
import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneSnapshot;
import opengpu.v2.scene.SceneState;
import opengpu.v2.scene.ServerScene;

/**
 * Phase 3.2, piece 1: the attachment RECORD and its wire/persistence half.
 *
 * No attach surface exists yet — {@code ServerScene} has no setAnimator and there is no OC
 * callback — so everything here drives {@link DeltaApplier} and the codecs directly. That
 * separation is the point of splitting the increment: the record can be proven to replicate and
 * persist before any caller-reachable path exists to create one, so the refusals that guard that
 * path (ANIM-15(a)) cannot be shipped late.
 */
public class NodeAttachRecordTest {

	private static final String SCENE = "gpu-node-address";

	private static SceneState stateWithNode(int nodeId) {
		SceneState state = new SceneState();
		state.nodes.put(Integer.valueOf(nodeId),
				new SceneNode(nodeId, V2Wire.NODE_GROUP, 0, 0));
		state.nextNodeId = nodeId + 1;
		return state;
	}

	// ---------------------------------------------------------------- applier semantics

	/**
	 * ANIM-17's atomic replace: a second attach SUCCEEDS and overwrites. The wrong implementation
	 * this excludes is an "already attached" refusal, which is what the audit explicitly struck.
	 */
	@Test
	public void asecondAttachReplacesRatherThanRefusing() {
		SceneState state = stateWithNode(1);
		DeltaApplier.apply(state, new Delta.NodeAttach(1, 7));
		assertEquals(7, state.nodes.get(Integer.valueOf(1)).animator);
		DeltaApplier.apply(state, new Delta.NodeAttach(1, 9));
		assertEquals("the second attach must replace, not refuse",
				9, state.nodes.get(Integer.valueOf(1)).animator);
	}

	/** Detach is the same write with 0, not a second delta type. */
	@Test
	public void detachIsAttachWithZero() {
		SceneState state = stateWithNode(1);
		DeltaApplier.apply(state, new Delta.NodeAttach(1, 7));
		DeltaApplier.apply(state, new Delta.NodeAttach(1, 0));
		assertEquals(0, state.nodes.get(Integer.valueOf(1)).animator);
	}

	/**
	 * A dangling attachment is LEGAL — ANIM-17's ruling, and the applier must not resolve the id.
	 *
	 * The wrong implementation excluded here is the tempting one: checking
	 * {@code state.programs.containsKey} and throwing. On a mirror that throw is a resync trigger,
	 * so it would answer a freed program by refetching the same state forever; and it would make
	 * delta ORDER significant, since a batch may legally free a program and attach later in the
	 * same tick.
	 */
	@Test
	public void attachingAProgramThatDoesNotExistIsLegal() {
		SceneState state = stateWithNode(1);
		assertTrue("precondition: no programs at all", state.programs.isEmpty());
		DeltaApplier.apply(state, new Delta.NodeAttach(1, 4242));
		assertEquals("a dangling attachment must be stored, not refused",
				4242, state.nodes.get(Integer.valueOf(1)).animator);
	}

	/** The node, unlike the program, must exist — it is the thing being written. */
	@Test
	public void attachingToAnUnknownNodeThrows() {
		SceneState state = stateWithNode(1);
		try {
			DeltaApplier.apply(state, new Delta.NodeAttach(99, 1));
			fail("attach to a nonexistent node applied");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("unknown node"));
		}
	}

	/** Negative ids are refused at construction, so no such delta can exist to encode. */
	@Test
	public void aNegativeProgramIdIsRefusedAtConstruction() {
		try {
			new Delta.NodeAttach(1, -1);
			fail("a negative program id was accepted");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("non-negative"));
		}
	}

	// ---------------------------------------------------------------- the wire

	@Test
	public void attachAndDetachRoundTripThroughTheEncoder() throws Exception {
		ArrayList<Delta> deltas = new ArrayList<Delta>();
		deltas.add(new Delta.NodeAttach(3, 11));
		deltas.add(new Delta.NodeAttach(3, 0));
		SceneBatch back = BatchCodec.decode(
				BatchCodec.encode(new SceneBatch("s", 1, 1, 1L, deltas)));
		assertEquals(2, back.deltas.size());
		assertEquals(deltas.get(0), back.deltas.get(0));
		assertEquals(deltas.get(1), back.deltas.get(1));
	}

	/** A negative id on the WIRE dies as a CodecException, not as an unchecked constructor throw. */
	@Test
	public void aNegativeProgramIdOnTheWireIsACodecError() throws Exception {
		java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
		java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
		out.writeShort(V2Wire.PROTOCOL_VERSION);
		out.writeUTF("s");
		out.writeInt(1);
		out.writeInt(1);
		out.writeLong(1L);
		out.writeInt(1);
		out.writeByte(V2Wire.DELTA_NODE_ATTACH);
		out.writeInt(3);
		out.writeInt(-5);
		out.flush();
		try {
			BatchCodec.decode(bytes.toByteArray());
			fail("a negative attach id decoded anyway");
		} catch (CodecException expected) {
			// The exclusion: without the decoder's check this surfaces as the constructor's
			// IllegalArgumentException, which escapes a `throws CodecException` API and would not
			// be contained by the inbound drain's catch.
			assertTrue("expected the decoder's refusal, got: " + expected.getMessage(),
					expected.getMessage().contains("Attach program id out of range"));
		}
	}

	/**
	 * Convergence through the real codec, and the assertion that makes it bite is
	 * {@code contentEquals} — which now compares `animator`, so a mirror that dropped the attach
	 * can no longer report agreement while rendering the node unanimated.
	 */
	@Test
	public void anAttachConvergesOnAMirror() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		SceneMirror mirror = new SceneMirror(SCENE);
		int canvas = server.createCanvas(64, 32, 256);
		int node = server.createNode(V2Wire.NODE_CANVAS, canvas);
		ship(server, mirror);

		// No ServerScene surface in this piece, so the delta is staged through the applier the
		// same way the server would: apply to server state, then ship the identical delta.
		Delta attach = new Delta.NodeAttach(node, 12);
		DeltaApplier.apply(server.state(), attach);
		ArrayList<Delta> deltas = new ArrayList<Delta>();
		deltas.add(attach);
		assertTrue(mirror.applyBatch(BatchCodec.decode(BatchCodec.encode(
				new SceneBatch(SCENE, server.epoch(), server.currentSeq() + 1, 1L, deltas)))));

		assertEquals("the attach did not reach the mirror",
				12, mirror.state().nodes.get(Integer.valueOf(node)).animator);
		assertTrue("server and mirror disagree", server.state().contentEquals(mirror.state()));
	}

	/** contentEquals must SEE a divergent attachment — otherwise the test above proves nothing. */
	@Test
	public void contentEqualsDetectsADivergentAttachment() {
		SceneState a = stateWithNode(1);
		SceneState b = stateWithNode(1);
		assertTrue("identical states must compare equal", a.contentEquals(b));
		DeltaApplier.apply(a, new Delta.NodeAttach(1, 5));
		assertTrue("a state whose node is attached must NOT equal one whose node is not",
				!a.contentEquals(b));
	}

	private static void ship(ServerScene server, SceneMirror mirror) throws Exception {
		SceneBatch batch = server.sealBatch();
		if (batch == null) {
			return;
		}
		assertTrue("mirror rejected the batch",
				mirror.applyBatch(BatchCodec.decode(BatchCodec.encode(batch))));
	}

	// ---------------------------------------------------------------- persistence

	/**
	 * The attachment and the scene epoch both survive the snapshot the SAVE is written from.
	 *
	 * Two fields added in one bump, in two different places (the node record and the scene tail),
	 * so both are asserted here — and the epoch is given a NON-ZERO value on purpose: 0 is the
	 * default a decoder that never read the field would also produce.
	 */
	@Test
	public void attachmentAndEpochSurviveThePersistedRoundTrip() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		int canvas = server.createCanvas(64, 32, 256);
		int attached = server.createNode(V2Wire.NODE_CANVAS, canvas);
		int bare = server.createNode(V2Wire.NODE_GROUP, 0);
		DeltaApplier.apply(server.state(), new Delta.NodeAttach(attached, 31));
		server.state().creationWorldTime = 123456789L;
		server.sealBatch();

		SceneSnapshot restored = SnapshotCodec.decodePersisted(
				SnapshotCodec.encode(server.snapshot()));

		assertEquals("the attachment did not survive the save",
				31, restored.state.nodes.get(Integer.valueOf(attached)).animator);
		assertEquals("an unattached node must not acquire one",
				0, restored.state.nodes.get(Integer.valueOf(bare)).animator);
		assertEquals("the animator epoch did not survive the save",
				123456789L, restored.state.creationWorldTime);
		assertTrue("the restored state is not content-equal to the live one",
				server.state().contentEquals(restored.state));
	}

	/**
	 * A dangling attachment persists as-is. This is the case ANIM-17's "legal and dangling" ruling
	 * turns on: the persisted form must agree with live state, or a running scene and its own
	 * reload would disagree permanently — the very reason a freed PARENT node is refused instead.
	 */
	@Test
	public void aDanglingAttachmentPersistsRatherThanBeingSanitised() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		int canvas = server.createCanvas(64, 32, 256);
		int node = server.createNode(V2Wire.NODE_CANVAS, canvas);
		DeltaApplier.apply(server.state(), new Delta.NodeAttach(node, 777)); // no such program
		server.sealBatch();

		SceneSnapshot restored = SnapshotCodec.decodePersisted(
				SnapshotCodec.encode(server.snapshot()));
		assertTrue("precondition: the program really does not exist",
				restored.state.programs.isEmpty());
		assertEquals("a dangling attachment must round-trip unchanged, or live state and its own"
				+ " reload disagree — which is exactly what parent sanitisation exists to prevent"
				+ " for a field where dangling is NOT legal",
				777, restored.state.nodes.get(Integer.valueOf(node)).animator);
	}

	/** A negative animator id in a persisted record is corruption, not a value to absorb. */
	@Test
	public void aNegativeAnimatorInAPersistedRecordIsRefused() throws Exception {
		ServerScene server = new ServerScene(SCENE);
		int canvas = server.createCanvas(64, 32, 256);
		int node = server.createNode(V2Wire.NODE_CANVAS, canvas);
		// A SENTINEL, located by searching rather than by a hand-computed offset. The first
		// version of this test counted back from the end and landed in the program section — the
		// node record is not last, the program section and the epoch follow it. An offset derived
		// from the layout has to be re-derived at every bump; a distinctive value does not.
		final int sentinel = 0x7EEEEEEE;
		DeltaApplier.apply(server.state(), new Delta.NodeAttach(node, sentinel));
		server.sealBatch();
		byte[] good = SnapshotCodec.encode(server.snapshot());
		assertNotNull(SnapshotCodec.decodePersisted(good));

		int at = -1;
		for (int i = 0; i + 3 < good.length; i++) {
			if ((good[i] & 0xFF) == 0x7E && (good[i + 1] & 0xFF) == 0xEE
					&& (good[i + 2] & 0xFF) == 0xEE && (good[i + 3] & 0xFF) == 0xEE) {
				assertEquals("the sentinel appears twice; pick a rarer one", -1, at);
				at = i;
			}
		}
		assertTrue("the sentinel animator id is not in the encoded snapshot at all, so this test"
				+ " is not patching the field it names", at >= 0);

		byte[] bad = good.clone();
		bad[at] = (byte) 0xFF;
		bad[at + 1] = (byte) 0xFF;
		bad[at + 2] = (byte) 0xFF;
		bad[at + 3] = (byte) 0xFF;
		try {
			SnapshotCodec.decodePersisted(bad);
			fail("a negative animator id decoded anyway (node " + node + ")");
		} catch (CodecException expected) {
			assertTrue("expected the animator refusal, got: " + expected.getMessage(),
					expected.getMessage().contains("negative animator"));
		}
	}
}
