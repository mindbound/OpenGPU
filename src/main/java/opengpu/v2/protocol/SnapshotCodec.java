package opengpu.v2.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.SceneCanvas;
import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneSnapshot;
import opengpu.v2.scene.SceneState;

/**
 * Codec for {@link SceneSnapshot}: the manifest-only full-state resync payload. Texture
 * BYTES never ride a snapshot (ServerScene.snapshot() strips them; this codec has no field
 * for them) — recovered mirrors hold pending textures and request bodies separately.
 *
 * Canvas resources are restored via {@code new SceneCanvas + publish(commands)}, the
 * canonical restore path, so compaction replay-state is rebuilt identically to the source.
 * Decoding applies the same strictness rules as {@link BatchCodec}: unknown types, invalid
 * dimensions, out-of-range counts, truncation, and trailing data all throw.
 */
public final class SnapshotCodec {
	private SnapshotCodec() {}

	static final int MAX_ENTRIES = 1 << 16;

	public static byte[] encode(SceneSnapshot snapshot) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeShort(V2Wire.PROTOCOL_VERSION);
			out.writeUTF(snapshot.sceneId);
			out.writeInt(snapshot.epoch);
			out.writeInt(snapshot.seq);
			out.writeLong(snapshot.serverTick);
			SceneState state = snapshot.state;
			out.writeInt(state.nextResourceId);
			out.writeInt(state.nextNodeId);
			out.writeInt(state.resources.size());
			for (Map.Entry<Integer, ResourceInfo> e : state.resources.entrySet()) {
				ResourceInfo res = e.getValue();
				out.writeInt(res.id);
				out.writeByte(res.type);
				out.writeInt(res.width);
				out.writeInt(res.height);
				out.writeInt(res.sizeBytes);
				// Written unconditionally so the record shape is fixed-width up to the canvas
				// tail; canvases carry version 1 / no hash.
				out.writeInt(res.type == V2Wire.RES_CANVAS ? 1 : res.latestVersion);
				out.writeInt(res.type == V2Wire.RES_CANVAS ? 0 : res.knownHashVersion);
				out.writeLong(res.type == V2Wire.RES_CANVAS ? 0L : res.knownHash);
				if (res.type == V2Wire.RES_CANVAS) {
					out.writeInt(res.canvas.commandCap);
					BatchCodec.writeCommands(out, res.canvas.visibleCommands());
				}
			}
			out.writeInt(state.nodes.size());
			for (Map.Entry<Integer, SceneNode> e : state.nodes.entrySet()) {
				SceneNode node = e.getValue();
				out.writeInt(node.id);
				out.writeByte(node.type);
				out.writeInt(node.ref);
				out.writeDouble(node.x);
				out.writeDouble(node.y);
				out.writeDouble(node.rot);
				out.writeDouble(node.sx);
				out.writeDouble(node.sy);
				out.writeInt(node.z);
				out.writeBoolean(node.visible);
				out.writeInt(node.tint);
				// APPENDED in v5. The record went 58 -> 62 bytes; nothing before it moved, which
				// is what lets a v4 record be read at its own width by the gate in decode().
				out.writeInt(node.parent);
			}
			byte[] encoded = bytes.toByteArray();
			// "Legal to create" must imply "deliverable": a snapshot the chunker cannot carry
			// would make the scene permanently unresyncable. Budget caps (open numbers in the
			// design doc) are bounded by this ceiling.
			if (encoded.length > FrameChunker.MAX_TRANSFER_BYTES)
				throw new IllegalStateException(
						"Snapshot exceeds transport capacity: " + encoded.length + " bytes");
			return encoded;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Older persisted structure versions THIS DECODER CAN READ, beyond the current
	 * {@link V2Wire#PROTOCOL_VERSION}. Every entry carries the reason it can.
	 *
	 * The contract used to be "byte-identical to the current layout". The 4 → 5 bump ended that,
	 * and the array now means "this decoder reads it, and here is why" — which is weaker, so the
	 * reason is not optional. Exactly two reasons are admissible:
	 *
	 *   3 — BYTE-IDENTICAL. The 3 → 4 bump appended OP_SET_FONT to the op table and changed no
	 *       field, and {@link BatchCodec#readCommands} decodes commands by arity, so a v3
	 *       structure holds only ops 1..21 whose arities are untouched.
	 *   4 — READ AT ITS OWN WIDTH BY A GATE. The 4 → 5 bump APPENDED {@code parent} to the node
	 *       record, 58 bytes to 62, and moved nothing. decode() reads that field only when
	 *       version >= 5, so a v4 node record is consumed at 58 bytes and the node loop still
	 *       ends flush against the trailing-data guard. The op table did not change in this bump.
	 *
	 * IF A FUTURE BUMP MOVES, RESIZES OR REORDERS AN EXISTING FIELD, IT DOES NOT BELONG HERE, and
	 * no gate rescues it. Write a decoder for the old layout instead, as
	 * {@link LegacyStructureCodec} does for v2, and dispatch on the peeked version. The same goes
	 * for a bump that changes an existing OP'S ARITY or reuses a retired op id: the structure
	 * embeds command lists framed by arity alone, so that silently misreads every old canvas, and
	 * a forked decoder does not help either because {@code CanvasCommand} validates against the
	 * same global table. Listing a version here without checking BOTH questions is how a save gets
	 * silently MISREAD rather than cleanly rejected, which is worse than the data loss this list
	 * exists to prevent — a misread structure produces a plausible scene built from misaligned
	 * bytes.
	 *
	 * The rule for the bump itself: when you raise PROTOCOL_VERSION, decide in the same edit
	 * whether the outgoing version goes in this array. Leaving it out is a decision too, and
	 * it means every existing world loses its scenes — see ScenePersistence.restoreOrFresh.
	 *
	 * ENTRIES ARE PERMANENT, NOT TRANSITIONAL. There is no point at which "everyone has loaded
	 * by now" makes one safe to drop: a chunk nobody visits keeps its old structure
	 * indefinitely, and TileEntityGpu2.writeToNBT rewrites a pendingStructure verbatim when the
	 * TE saves before its scene is initialised. A world can therefore carry a v3 structure
	 * through any number of v4 sessions.
	 */
	private static final short[] LAYOUT_COMPATIBLE_PERSISTED_VERSIONS = { 3, 4 };

	/**
	 * Legality of a decoded parent, answered identically on both paths — and answered DIFFERENTLY
	 * when it comes back "no". The asymmetry is the same one {@link #decodePersisted} exists for.
	 *
	 *   persisted — SANITISE to "no parent". A CodecException here is
	 *               {@code ScenePersistence.restoreOrFresh} calling {@code store.deleteScene}, so
	 *               throwing would answer "one node has a bad parent id" by destroying the scene
	 *               and every texture body it owns. Dropping the grouping costs a grouping.
	 *               Silent, with precedent: {@code ref} above gets no existence check either, and
	 *               a node pointing at a freed resource is simply skipped by the renderer.
	 *   network   — THROW. A snapshot frame that says something the server could not have
	 *               produced is corruption, and refusing it costs nothing: the mirror is already
	 *               built to answer a failed apply with needsResync, and the retry fetches a
	 *               clean snapshot. Sanitising here would be strictly worse than on disk, because
	 *               it is the one place the disagreement is DETECTABLE — a silently zeroed parent
	 *               leaves the mirror rendering an ungrouped scene against a server that has the
	 *               grouping, with no seq gap and no apply failure to reveal it.
	 *
	 * Three conditions, each O(1). There is no traversal and no visited set anywhere in this
	 * codec, and the one that carries the acyclicity argument is the LAST one, not the first:
	 *
	 *   parent >= id  — self-parenting and forward references. Nodes are ENCODED in ascending id
	 *                   order ({@code SceneState.nodes} is a TreeMap), but a crafted blob need not
	 *                   be, so this is checked rather than assumed.
	 *   absent        — freed before the save, or an id crafted from nothing. Order-dependent, and
	 *                   only downward: a parent not yet inserted degrades, never accepts.
	 *   grandparent   — Stage B allows ONE nesting level, so a parent must itself be unparented.
	 *                   THIS is what makes cycles impossible, on its own and regardless of order:
	 *                   every member of a cycle must both have a parent and be one, and this
	 *                   refuses exactly that. `parent` being final is what stops a node acquiring
	 *                   one later. The other two conditions are belt and braces over it.
	 */
	private static int resolveParent(SceneState state, int id, int parent, boolean persisted)
			throws CodecException {
		boolean legal = parent == 0 || parent < id;
		if (legal && parent != 0) {
			SceneNode p = state.nodes.get(parent);
			legal = p != null && p.parent == 0;
		}
		if (legal)
			return parent;
		if (!persisted)
			throw new CodecException("Node " + id + " has an illegal parent " + parent);
		return 0;
	}

	/**
	 * Network path: strict. A PEER of another vintage is an error — it disagrees about the op
	 * table, so decoding its payload risks reading one op's argument as another's.
	 */
	public static SceneSnapshot decode(byte[] data) throws CodecException {
		return decode(data, false);
	}

	/**
	 * Persistence path: a SAVE of another vintage is not an error where the layout allows it.
	 *
	 * The asymmetry with {@link #decode} is the whole point. A peer can be told to upgrade; a
	 * save on disk cannot, and the caller answers a CodecException by DELETING the scene's
	 * stored bodies ({@link opengpu.v2.persist.ScenePersistence#restoreOrFresh}). So strictness
	 * here does not fail safe — it destroys the thing it is protecting.
	 */
	public static SceneSnapshot decodePersisted(byte[] data) throws CodecException {
		return decode(data, true);
	}

	private static boolean isLayoutCompatible(short version) {
		for (short v : LAYOUT_COMPATIBLE_PERSISTED_VERSIONS) {
			if (v == version)
				return true;
		}
		return false;
	}

	private static SceneSnapshot decode(byte[] data, boolean persisted) throws CodecException {
		try {
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
			short version = in.readShort();
			if (version != V2Wire.PROTOCOL_VERSION
					&& !(persisted && isLayoutCompatible(version)))
				throw new CodecException("Unsupported protocol version " + version);
			String sceneId = in.readUTF();
			int epoch = in.readInt();
			if (epoch == 0)
				throw new CodecException("Epoch 0 is reserved");
			int seq = in.readInt();
			long tick = in.readLong();
			SceneState state = new SceneState();
			state.nextResourceId = in.readInt();
			state.nextNodeId = in.readInt();
			int resCount = in.readInt();
			if (resCount < 0 || resCount > MAX_ENTRIES)
				throw new CodecException("Resource count out of range: " + resCount);
			for (int i = 0; i < resCount; i++) {
				int id = in.readInt();
				byte type = in.readByte();
				if (!V2Wire.isKnownResType(type))
					throw new CodecException("Unknown resource type " + type);
				int width = in.readInt();
				int height = in.readInt();
				int sizeBytes = in.readInt();
				int wireVersion = in.readInt();
				int knownHashVersion = in.readInt();
				long knownHash = in.readLong();
				if (wireVersion < 1)
					throw new CodecException("Resource " + id + " version out of range: " + wireVersion);
				if (knownHashVersion < 0 || knownHashVersion > wireVersion)
					throw new CodecException("Resource " + id + " knownHashVersion out of range");
				if (width <= 0 || height <= 0
						|| width > V2Wire.MAX_TEXTURE_DIM || height > V2Wire.MAX_TEXTURE_DIM)
					throw new CodecException("Resource " + id + " has invalid dimensions");
				if (type == V2Wire.RES_TEXTURE && sizeBytes != (long) width * height * 4L)
					throw new CodecException("Resource " + id + " size does not match dimensions");
				if (state.resources.containsKey(id))
					throw new CodecException("Duplicate resource id " + id);
				ResourceInfo res = new ResourceInfo(id, type, width, height, sizeBytes);
				// "I know of version V and hold no bytes." ScenePersistence.restore sets
				// version when it attaches bytes; SceneMirror.applySnapshot via carry-over.
				res.latestVersion = wireVersion;
				res.version = 0;
				res.knownHashVersion = knownHashVersion;
				res.knownHash = knownHash;
				if (type == V2Wire.RES_CANVAS) {
					int cap = in.readInt();
					// Construct the canvas BEFORE reading its list, then bound the read by the
					// cap. Two reasons in that order: the constructor is what validates cap, and
					// a bound is only worth having once it has been validated; and a payload's
					// BYTE length is a weak bound on its command COUNT, since the zero-arity ops
					// encode in one byte each. The unbounded overload would therefore turn about
					// a megabyte of blob into MAX_COMMANDS live objects before publish() refused
					// the list. BatchCodec.decodeCommandList(byte[], int) carries the javadoc for
					// this rule — pass "the count the target will actually accept (a canvas's
					// command cap)" — and readCommands(DataInputStream, int) is its streaming
					// form, which the two structure decoders were the last callers not to use.
					//
					// This NARROWS the hazard, it does not close it: cap is itself only bounded
					// by MAX_COMMANDS - 2, so a save forging a large cap and a matching count
					// still allocates in proportion to its own length. Bounding tighter than the
					// canvas's own cap is what would close it, and is deliberately not done —
					// any bound that can refuse a legitimate save is, on this path,
					// restoreOrFresh deleting the scene. What this does close is the realistic
					// case: a canvas with an ordinary cap can no longer be made to build a
					// million commands.
					SceneCanvas canvas = new SceneCanvas(width, height, cap);
					ArrayList<opengpu.v2.scene.CanvasCommand> commands =
							BatchCodec.readCommands(in, cap);
					canvas.publish(commands);
					res.canvas = canvas;
				}
				state.resources.put(id, res);
			}
			int nodeCount = in.readInt();
			if (nodeCount < 0 || nodeCount > MAX_ENTRIES)
				throw new CodecException("Node count out of range: " + nodeCount);
			for (int i = 0; i < nodeCount; i++) {
				int id = in.readInt();
				byte type = in.readByte();
				if (!V2Wire.isKnownNodeType(type))
					throw new CodecException("Unknown node type " + type);
				int ref = in.readInt();
				double x = in.readDouble();
				double y = in.readDouble();
				double rot = in.readDouble();
				double sx = in.readDouble();
				double sy = in.readDouble();
				int z = in.readInt();
				boolean visible = in.readBoolean();
				int tint = in.readInt();
				// THE v5 GATE. A v4 record simply ends after tint, so it is read at its own
				// 58-byte width and the node loop still finishes flush against the trailing-data
				// guard below. Note >= 5, not >= 4: off by one here would read each v4 node's
				// parent out of the NEXT node's id, and the run would die at EOF having deleted
				// nothing but every pre-upgrade world. aV4StructureDecodesThroughThePersistencePath
				// is the test that catches it.
				int parent = version >= 5 ? in.readInt() : 0;
				if (state.nodes.containsKey(id))
					throw new CodecException("Duplicate node id " + id);
				SceneNode node = new SceneNode(id, type, ref,
						resolveParent(state, id, parent, persisted));
				node.x = x;
				node.y = y;
				node.rot = rot;
				node.sx = sx;
				node.sy = sy;
				node.z = z;
				node.visible = visible;
				node.tint = tint;
				state.nodes.put(id, node);
			}
			if (in.read() != -1)
				throw new CodecException("Trailing data after snapshot");
			// Counter consistency: ids must never be reallocatable below existing entries
			// (recorded command lists reference them). A crafted snapshot must not be able to
			// plant a state that violates SceneState's no-reuse contract.
			if (state.nextResourceId < 1
					|| (!state.resources.isEmpty() && state.nextResourceId <= state.resources.lastKey()))
				throw new CodecException("Resource id counter inconsistent with contents");
			if (state.nextNodeId < 1
					|| (!state.nodes.isEmpty() && state.nextNodeId <= state.nodes.lastKey()))
				throw new CodecException("Node id counter inconsistent with contents");
			return new SceneSnapshot(sceneId, epoch, seq, tick, state);
		} catch (EOFException e) {
			throw new CodecException("Truncated snapshot", e);
		} catch (IOException e) {
			throw new CodecException("Malformed snapshot", e);
		} catch (IllegalArgumentException e) {
			throw new CodecException("Malformed snapshot: " + e.getMessage(), e);
		} catch (IllegalStateException e) {
			// SceneCanvas.publish cap breach on crafted input.
			throw new CodecException("Malformed snapshot: " + e.getMessage(), e);
		}
	}
}
