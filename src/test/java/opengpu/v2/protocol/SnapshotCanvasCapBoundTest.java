package opengpu.v2.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.junit.Test;

import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.SceneSnapshot;

/**
 * The canvas command list inside a snapshot must be bounded by the cap the decoder just read.
 *
 * A payload's BYTE length is a weak bound on its command COUNT — the zero-arity ops encode in
 * one byte each — which is why the bounded reads exist. The rule is written down on
 * {@link BatchCodec#decodeCommandList(byte[], int)}, which tells callers to pass "the count the
 * target will actually accept (a canvas's command cap)";
 * {@link BatchCodec#readCommands(java.io.DataInputStream, int)} is the streaming form of the
 * same bound and carries no javadoc of its own. SnapshotCodec read the cap and then called the
 * UNBOUNDED overload, so a structure could declare up to {@link V2Wire#MAX_COMMANDS} commands
 * and have every one of them built before {@code SceneCanvas.publish} refused the list.
 *
 * The outcome was never wrong — an over-cap list was always rejected — so the two tests below
 * pin the two things that ARE observable and that a careless bound would break:
 *
 *   - the refusal quotes the COUNT against the cap, which only the pre-allocation check can
 *     produce (publish's refusal reads "canvas command list full"). That is the evidence the
 *     list was refused at the count rather than after being built;
 *   - a list of exactly cap commands still decodes. This is the one that matters: the bound is
 *     "greater than", and an off-by-one here would refuse a legitimately saved canvas — which
 *     on the persistence path is ScenePersistence.restoreOrFresh answering a CodecException
 *     with store.deleteScene. The invariant that makes cap a SAFE bound is that commandCap is
 *     final and both append() and publish() enforce size &lt;= cap, so no validly encoded canvas
 *     can carry more commands than its own cap.
 */
public class SnapshotCanvasCapBoundTest {

	private static final int CANVAS_ID = 1;
	private static final int DIM = 64;
	private static final int EPOCH = 0xC0FFEE;

	/**
	 * A minimal one-canvas, zero-node structure whose canvas declares {@code cap} and then
	 * writes {@code commandCount} zero-arity FILL commands. Hand-written rather than produced
	 * by the encoder, because the encoder cannot express the disagreement under test.
	 */
	private static byte[] structure(int cap, int commandCount) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(bytes);
		out.writeShort(V2Wire.PROTOCOL_VERSION);
		out.writeUTF("gpu-addr");
		out.writeInt(EPOCH);
		out.writeInt(1);    // seq
		out.writeLong(0L);  // serverTick
		out.writeInt(2);    // nextResourceId, must exceed the highest resource id
		out.writeInt(1);    // nextNodeId
		out.writeInt(1);    // one resource
		out.writeInt(CANVAS_ID);
		out.writeByte(V2Wire.RES_CANVAS);
		out.writeInt(DIM);
		out.writeInt(DIM);
		out.writeInt(0);    // sizeBytes: only textures are size-checked
		out.writeInt(1);    // canvases ride as version 1
		out.writeInt(0);    // no hash version
		out.writeLong(0L);  // no hash
		out.writeInt(cap);
		out.writeInt(commandCount);
		for (int i = 0; i < commandCount; i++) {
			out.writeByte(V2Wire.OP_FILL); // zero arity: one byte per command
		}
		out.writeInt(0);    // no nodes
		// v6 program section: this fixture is written at PROTOCOL_VERSION, so it must carry
		// every field that version defines. Empty, since the cap under test is the canvas's.
		out.writeInt(1);    // nextProgramId
		out.writeInt(0);    // no programs
		out.flush();
		return bytes.toByteArray();
	}

	@Test
	public void aCommandCountAboveTheCanvasCapIsRefusedAtTheCount() throws Exception {
		try {
			SnapshotCodec.decode(structure(4, 5));
			fail("a canvas declaring 5 commands under a cap of 4 decoded cleanly");
		} catch (CodecException expected) {
			assertEquals("the refusal must come from the bounded read, before the list is"
					+ " built; \"canvas command list full\" means publish caught it afterwards",
					"Command count 5 exceeds the limit of 4", expected.getMessage());
		}
	}

	@Test
	public void aCommandCountExactlyAtTheCanvasCapStillDecodes() throws Exception {
		SceneSnapshot snap = SnapshotCodec.decode(structure(4, 4));

		ResourceInfo canvas = snap.state.resources.get(CANVAS_ID);
		assertTrue("the canvas must survive a full-to-cap command list", canvas != null
				&& canvas.canvas != null);
		assertEquals("every command must be kept", 4, canvas.canvas.visibleCommands().size());
		assertEquals("and the cap itself must round-trip", 4, canvas.canvas.commandCap);
	}
}
