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
import opengpu.v2.scene.ProgramInfo;
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
				// The v10 mesh tail: BOTH blobs inline (the programs' reasoning, not the
				// textures' — copyStructure carries mesh bytes for exactly this line). The
				// fixed-width header above already holds the frozen type-3 convention (width =
				// vertexCount, height = 1), which is what keeps the dims check in decode()
				// untouched. The split point is derivable (width * stride), and both halves are
				// length-prefixed so decode re-derives and cross-checks the counts.
				if (res.type == V2Wire.RES_MESH) {
					if (res.bytes == null)
						throw new IllegalStateException("Mesh " + res.id
								+ " has no bytes at encode; copyStructure must carry them");
					int vertexLen = res.width * V2Wire.MESH_VERTEX_STRIDE;
					out.writeInt(vertexLen);
					out.write(res.bytes, 0, vertexLen);
					out.writeInt(res.bytes.length - vertexLen);
					out.write(res.bytes, vertexLen, res.bytes.length - vertexLen);
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
				// APPENDED in v7, same shape again: 62 -> 66 bytes, nothing before it moved. A
				// FIXED-WIDTH int is what makes this legal under this codec's policy — the
				// attachment is one program id, so it needs no variable-length tail and no forked
				// decoder. 0 means unattached, which is what every pre-v7 node record restores as.
				out.writeInt(node.animator);
				// APPENDED in v8, same shape a third time: 66 -> 74 bytes, nothing before it
				// moved. ANIM-6's per-attachment stamp, in world time so that this record means
				// the same thing on the server, on every mirror, and on disk.
				out.writeLong(node.attachedWorldTime);
				// APPENDED in v10, same shape a fourth time: 74 -> 122 bytes, nothing before it
				// moved. The 3D TRS in pinned order tz, sz, qx, qy, qz, qw. The decode gate's
				// pre-v10 defaults are the IDENTITY transform (sz = 1, qw = 1), NOT the usual
				// zero pattern — a zeroed sz would collapse every pre-upgrade node's scale.
				out.writeDouble(node.tz);
				out.writeDouble(node.sz);
				out.writeDouble(node.qx);
				out.writeDouble(node.qy);
				out.writeDouble(node.qz);
				out.writeDouble(node.qw);
			}
			// APPENDED in v6, the same shape the v5 `parent` append used: strictly after every
			// earlier field, so a v5 payload is consumed at its own width by the gate in decode()
			// and needs no separate decoder. Programs ride the structure INLINE (own table, own
			// section — DESIGN, "Program storage"): they are immutable, bounded per blob by
			// OcslWire.MAX_BLOB_BYTES and per scene by ServerScene's 256 KiB ledger (a fraction
			// of the 2 MiB of canvas commands this snapshot already carries), and the client
			// needs them to run an animator at all, so the out-of-band body protocol built for
			// textures would buy nothing.
			out.writeInt(state.nextProgramId);
			out.writeInt(state.programs.size());
			for (Map.Entry<Integer, ProgramInfo> e : state.programs.entrySet()) {
				ProgramInfo prog = e.getValue();
				out.writeInt(prog.id);
				out.writeByte(prog.stage);
				out.writeInt(prog.structuralOps);
				out.writeInt(prog.blobLength());
				out.write(prog.blobCopy());
			}
			// APPENDED in v7, and it must sit AFTER the whole v6 section, not before it. A first
			// draft put this long ahead of `nextProgramId`, which reads like a detail and is the
			// forbidden case: it MOVES v6's fields, so a v6 payload and a v7 payload would need
			// two different field orders and this codec would owe a forked decoder. Appending
			// keeps the v6 gate below reading a v6 payload at its own width.
			out.writeLong(state.creationWorldTime);
			// APPENDED in v8: THE ANCHOR. Every stamp in this payload — the scene epoch above and
			// each node's attach stamp — is WORLD time, while the client's render clock
			// (ServerTimeline.renderNanos) lives in the SESSION-tick domain the header's
			// serverTick counts. One pair of (serverTick, worldTime) taken at the same instant is
			// all a receiver needs to convert any of them:
			//     sessionTick = serverTick + (stamp - worldTimeAnchor)
			// The alternative — having the server pre-derive each stamp — was rejected because it
			// makes a mirror's stored field a DIFFERENT KIND of number from the server's, so
			// SceneState.contentEquals would compare unlike quantities while reporting agreement.
			// One field here keeps every stored stamp identical everywhere and leaves 3.3's host
			// fill needing no further protocol change.
			out.writeLong(state.worldTimeAnchor);
			// APPENDED in v10, strictly after the v8 anchor: the per-attachment uniform table,
			// NESTED — [int nodeCountWithUniforms][per node: int nodeId, int entryCount,
			// entries...] — because the FLAT alternative was refuted by arithmetic: 4096 nodes x
			// 64 entries = 262,144 legal standing entries, 4x MAX_ENTRIES, so a flat count bound
			// would let a legally-full server encode a snapshot every decoder refuses (network:
			// permanently unresyncable; persisted: restoreOrFresh DELETES the scene). Nested,
			// each bound is producibly satisfiable: outer <= MAX_NODES < MAX_ENTRIES, inner <=
			// MAX_NODE_UNIFORMS. Entry = (name, count-as-type byte, doubles); the wire delta's
			// `immediate` flag is deliberately absent — it qualifies transitions, not state.
			// WRITTEN EVEN WHEN EMPTY: the count int is load-bearing for the trailing-data guard.
			int nodesWithUniforms = 0;
			for (SceneNode node : state.nodes.values()) {
				if (!node.uniforms.isEmpty())
					nodesWithUniforms++;
			}
			out.writeInt(nodesWithUniforms);
			for (Map.Entry<Integer, SceneNode> e : state.nodes.entrySet()) {
				SceneNode node = e.getValue();
				if (node.uniforms.isEmpty())
					continue;
				out.writeInt(node.id);
				out.writeInt(node.uniforms.size());
				for (Map.Entry<String, double[]> u : node.uniforms.entrySet()) {
					out.writeUTF(u.getKey());
					double[] values = u.getValue();
					out.writeByte(values.length);
					for (double v : values) {
						out.writeDouble(v);
					}
				}
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
	 *   5 — READ AT ITS OWN WIDTH BY A GATE, the same shape one bump later. The 5 → 6 bump
	 *       APPENDED a whole SECTION after the node loop (nextProgramId, then a program count,
	 *       then per-program id / stage / structuralOps / length / blob) and moved no existing
	 *       field. decode() reads
	 *       that section only when version >= 6, so a v5 payload ends where it always did and
	 *       lands flush on the trailing-data guard; a v5 world simply restores with an empty
	 *       program table, which is what it had. The op table did not change in this bump.
	 *   6 — READ AT ITS OWN WIDTH BY TWO GATES. The 6 → 7 bump did both permitted things at once:
	 *       APPENDED a fixed-width {@code animator} int to the node record (62 → 66 bytes, nothing
	 *       before it moved), and APPENDED the scene's {@code creationWorldTime} long AFTER the
	 *       whole v6 program section. decode() reads each only at version >= 7, so a v6 payload is
	 *       consumed at its own widths and still lands flush on the trailing-data guard; a v6
	 *       world restores with every node unattached and epoch 0, which is what it had. The op
	 *       table did not change in this bump. Note the ordering constraint the second field
	 *       nearly broke: appending it BEFORE the v6 section would have moved v6's fields, which
	 *       is the forked-decoder case below, not this one.
	 *
	 *   7 — READ AT ITS OWN WIDTH BY TWO GATES, the same shape once more. The 7 → 8 bump APPENDED
	 *       ANIM-6's fixed-width {@code attachedWorldTime} long to the node record (66 → 74 bytes,
	 *       nothing before it moved) and APPENDED the world-time anchor after the v7 epoch, which
	 *       is itself after the v6 program section. decode() reads each only at version >= 8, so a
	 *       v7 payload is consumed at its own widths and lands flush on the trailing-data guard; a
	 *       v7 world restores with every attachment unstamped and no anchor, which is what it had.
	 *       The op table did not change in this bump.
	 *   8 — BYTE-IDENTICAL. The 8 → 9 bump (ANIM-13(b)) put the server tick on the HEARTBEAT, a
	 *       transient message no save contains; it changed no persisted field and no op arity, so
	 *       a v8 structure is a v9 structure. (This paragraph was missing when the entry landed —
	 *       the file's own every-entry-carries-its-reason rule, restored at the v10 bump's audit.)
	 *   9 — READ AT ITS OWN WIDTH BY GATES. The 9 → 10 bump APPENDED the six 3D TRS doubles to
	 *       the node record (74 → 122 bytes, nothing before it moved), the type-3 mesh tail in
	 *       the resource record's type-conditional slot (v9 cannot contain a type-3 record — the
	 *       decoder refuses one below version 10 outright), and the nested uniform section after
	 *       the v8 anchor. decode() reads each only at version >= 10, so a v9 payload is consumed
	 *       at its own widths and lands flush on the trailing-data guard; a v9 world restores
	 *       with identity 3D transforms (sz = 1, qw = 1 — the gate arms restore the IDENTITY, not
	 *       zeros), no meshes and empty uniform tables, which is what it had. The op table did
	 *       not change in this bump.
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
	private static final short[] LAYOUT_COMPATIBLE_PERSISTED_VERSIONS = { 3, 4, 5, 6, 7, 8, 9 };

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
				// REFUSE, never skip: no v8/v9 encoder could write a type-3 record, so one in a
				// pre-v10 payload is corruption; skipping would fabricate a data-less mesh from
				// bytes that mean something else — the misread this file ranks worse than loss.
				if (type == V2Wire.RES_MESH && version < 10)
					throw new CodecException("Resource " + id + " claims type MESH in a v"
							+ version + " payload; meshes exist only from v10");
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
				// Type-3 records pass UNTOUCHED by construction: width = vertexCount (1..5461)
				// sits inside the 1..8192 bound, height = 1. That is the frozen convention's
				// whole point — the one wrong natural choice (height = indexCount) would refuse
				// any legal mesh with more than 8192 indices.
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
				// (Meshes override below: their bytes are IN this payload.)
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
				if (type == V2Wire.RES_MESH) {
					// The v10 mesh tail. Length bounds BEFORE either allocation (the batch
					// decoder's rule), then the shared validator on the read bytes, then the
					// header cross-checks — a record whose fixed-width header disagrees with its
					// own tail was written by no encoder and is refused on BOTH paths (the
					// throwing dims-check precedent governs impossible records here too).
					int vertexLen = in.readInt();
					if (vertexLen < V2Wire.MESH_VERTEX_STRIDE
							|| vertexLen > V2Wire.MAX_MESH_VERTEX_BYTES)
						throw new CodecException("Mesh " + id + " vertex blob length out of range: "
								+ vertexLen);
					byte[] vertexBytes = new byte[vertexLen];
					in.readFully(vertexBytes);
					int indexLen = in.readInt();
					if (indexLen < 3 * V2Wire.MESH_INDEX_BYTES
							|| indexLen > V2Wire.MAX_MESH_INDEX_BYTES)
						throw new CodecException("Mesh " + id + " index blob length out of range: "
								+ indexLen);
					byte[] indexBytes = new byte[indexLen];
					in.readFully(indexBytes);
					try {
						V2Wire.validateMeshBlobs(vertexBytes, indexBytes);
					} catch (IllegalArgumentException ex) {
						throw new CodecException("Mesh " + id + " malformed: " + ex.getMessage(), ex);
					}
					if (width != V2Wire.meshVertexCount(vertexBytes) || height != 1)
						throw new CodecException("Mesh " + id + " header disagrees with its tail: "
								+ width + "x" + height + " vs " + V2Wire.meshVertexCount(vertexBytes)
								+ " vertices");
					if (sizeBytes != vertexLen + indexLen)
						throw new CodecException("Mesh " + id + " sizeBytes disagrees with its blobs");
					if (wireVersion != 1 || knownHashVersion != 1)
						throw new CodecException("Mesh " + id + " carries versions no encoder writes");
					byte[] combined = new byte[vertexLen + indexLen];
					System.arraycopy(vertexBytes, 0, combined, 0, vertexLen);
					System.arraycopy(indexBytes, 0, combined, vertexLen, indexLen);
					// The hash and the bytes travelled in the SAME payload, so a mismatch is
					// self-inconsistency, not a torn side-channel — corrupt on any path.
					if (knownHash != V2Wire.contentHash(combined))
						throw new CodecException("Mesh " + id + " bytes do not match their hash");
					res.bytes = combined;
					res.version = 1;
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
				// THE v7 FIELD, and >= 7 for the identical reason >= 5 is not >= 4: reading it a
				// version early would take each v6 node's animator out of the NEXT node's id.
				// NOT sanitised against the program table the way `parent` is against the node
				// table — a dangling attachment is legal by ANIM-17, and the program section has
				// not been read yet at this point in the stream anyway, so there is nothing here
				// to resolve against even in principle.
				int animator = version >= 7 ? in.readInt() : 0;
				// v8's stamp, gated on its own version for the same reason each earlier field is.
				long attachedWorldTime = version >= 8 ? in.readLong() : 0L;
				// v10's 3D TRS, gated alike — and the defaults are the IDENTITY transform, not
				// the file's usual zero pattern: a pre-v10 node restoring with sz = 0 or qw = 0
				// would collapse its scale / rotation the moment C1.3 starts consuming these.
				double tz = version >= 10 ? in.readDouble() : 0.0;
				double szScale = version >= 10 ? in.readDouble() : 1.0;
				double qxr = version >= 10 ? in.readDouble() : 0.0;
				double qyr = version >= 10 ? in.readDouble() : 0.0;
				double qzr = version >= 10 ? in.readDouble() : 0.0;
				double qwr = version >= 10 ? in.readDouble() : 1.0;
				if (animator < 0)
					throw new CodecException("Node " + id + " has a negative animator id " + animator);
				if (attachedWorldTime < 0L)
					throw new CodecException("Node " + id + " has a negative attach stamp "
							+ attachedWorldTime);
				// The two fields are meaningless apart, so a record carrying one without the other
				// is corruption rather than a state to absorb. Checked HERE and not in
				// DeltaApplier because the delta constructor already enforces the same pairing —
				// this is the persisted path's equivalent guard, on bytes nothing else validated.
				if (animator == 0 && attachedWorldTime != 0L)
					throw new CodecException("Node " + id + " is unattached but carries a stamp of "
							+ attachedWorldTime);
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
				node.tz = tz;
				node.sz = szScale;
				node.qx = qxr;
				node.qy = qyr;
				node.qz = qzr;
				node.qw = qwr;
				node.animator = animator;
				node.attachedWorldTime = attachedWorldTime;
				state.nodes.put(id, node);
			}
			// THE v6 GATE, same rule as the v5 one above: >= 6, not >= 5. A v5 payload ends
			// after the node loop, so it reads at its own width and still lands flush on the
			// trailing-data guard below; reading the section unconditionally would consume that
			// guard's bytes and turn every pre-upgrade scene into "Truncated snapshot".
			if (version >= 6) {
				state.nextProgramId = in.readInt();
				int programCount = in.readInt();
				// Bounded before the loop allocates, using the same MAX_ENTRIES the resource and
				// node counts use: a crafted count must not spin this into an OOM before the
				// per-entry reads fail. Generous for programs, which the scene ledger caps far
				// lower — this guards the decoder, not the policy.
				if (programCount < 0 || programCount > MAX_ENTRIES)
					throw new CodecException("Program count " + programCount + " out of range");
				for (int i = 0; i < programCount; i++) {
					int id = in.readInt();
					byte stage = in.readByte();
					int structuralOps = in.readInt();
					int length = in.readInt();
					// THE SAME BOUNDS THE BATCH DECODER ENFORCES, deliberately: no OpenGPU
					// encoder can write a record these refuse, so a payload carrying one is
					// corrupt, and on this path throwing is the designed answer (restoreOrFresh
					// treats a codec failure as an unusable save). A first draft checked only
					// the blob length here, which left the PERSISTED path accepting three shapes
					// the wire path refuses — an asymmetry with no reason, found by review.
					if (id < 1 || id == Integer.MAX_VALUE)
						throw new CodecException("Program id " + id + " out of range");
					if (!opengpu.v2.ocsl.OcslWire.isKnownStage(stage))
						throw new CodecException("Unknown program stage " + (stage & 0xFF));
					// The CEILING, deliberately, not maxStructuralOps(stage) -- see BatchCodec's
					// note at the same check. A persisted blob outlives the cap that admitted it,
					// so bounding a SAVE by today's per-stage policy would make lowering a cap
					// retroactively delete scenes.
					if (structuralOps < 0
							|| structuralOps > opengpu.v2.ocsl.IrValidator.MAX_STRUCTURAL_OPS)
						throw new CodecException("Program structural charge out of range: "
								+ structuralOps);
					// The blob ceiling is the codec's own, so a crafted length cannot allocate
					// past what a legal program could ever be.
					if (length < 1 || length > opengpu.v2.ocsl.OcslWire.MAX_BLOB_BYTES)
						throw new CodecException("Program blob length " + length + " out of range");
					byte[] blob = new byte[length];
					in.readFully(blob);
					if (state.programs.containsKey(Integer.valueOf(id)))
						throw new CodecException("Duplicate program id " + id);
					state.programs.put(Integer.valueOf(id),
							new ProgramInfo(id, stage, blob, structuralOps));
				}
			}
			// THE v7 GATE, sitting after the v6 section because that is where encode() writes it.
			// A v6 payload ends with the program section and lands flush on the trailing-data
			// guard below; left ungated, this read would consume that guard's bytes and every
			// v6 world would restore as "Truncated snapshot" — which restoreOrFresh answers by
			// deleting the scene's stored bodies.
			if (version >= 7) {
				state.creationWorldTime = in.readLong();
			}
			if (version >= 8) {
				state.worldTimeAnchor = in.readLong();
			}
			// THE v10 GATE on the nested uniform section — see encode() for the section's shape
			// and the arithmetic that forced nesting. Every bound here is one a legal encoder
			// can actually satisfy: outer count <= MAX_ENTRIES (producibly <= MAX_NODES), inner
			// count 1..MAX_NODE_UNIFORMS. Unknown nodeId, duplicate nodeId (a node whose table
			// is already non-empty), duplicate name, bad name, or a type byte outside 1..4
			// (CLEAR is never persisted — it is a transition) all throw: no encoder writes them.
			if (version >= 10) {
				int nodesWithUniforms = in.readInt();
				if (nodesWithUniforms < 0 || nodesWithUniforms > MAX_ENTRIES)
					throw new CodecException("Uniform node count out of range: " + nodesWithUniforms);
				for (int i = 0; i < nodesWithUniforms; i++) {
					int nodeId = in.readInt();
					SceneNode node = state.nodes.get(Integer.valueOf(nodeId));
					if (node == null)
						throw new CodecException("Uniform section references unknown node " + nodeId);
					if (!node.uniforms.isEmpty())
						throw new CodecException("Duplicate uniform group for node " + nodeId);
					int entryCount = in.readInt();
					if (entryCount < 1 || entryCount > opengpu.v2.scene.ServerScene.MAX_NODE_UNIFORMS)
						throw new CodecException("Uniform entry count out of range for node "
								+ nodeId + ": " + entryCount);
					for (int u = 0; u < entryCount; u++) {
						String name = in.readUTF();
						try {
							opengpu.v2.ocsl.IrStructure.checkName(0, name);
						} catch (opengpu.v2.ocsl.IrStructure.StructureException ex) {
							throw new CodecException("Uniform name on node " + nodeId + ": "
									+ ex.getMessage(), ex);
						}
						int valueCount = in.readByte();
						if (valueCount < 1 || valueCount > 4)
							throw new CodecException("Uniform '" + name + "' on node " + nodeId
									+ " has a value count of " + valueCount);
						double[] values = new double[valueCount];
						for (int v = 0; v < valueCount; v++) {
							values[v] = in.readDouble();
						}
						if (node.uniforms.put(name, values) != null)
							throw new CodecException("Duplicate uniform '" + name + "' on node "
									+ nodeId);
					}
				}
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
			// Same contract for programs: an attach delta references a program by id, so a
			// counter that could reallocate a live id is the same hazard as for resources.
			if (state.nextProgramId < 1
					|| (!state.programs.isEmpty() && state.nextProgramId <= state.programs.lastKey()))
				throw new CodecException("Program id counter inconsistent with contents");
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
