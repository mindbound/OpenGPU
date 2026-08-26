package opengpu.v2.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;

import opengpu.v2.scene.CanvasCommand;

/**
 * Binary codec for {@link SceneBatch}. Wire layout:
 *
 *   [short PROTOCOL_VERSION][UTF sceneId][int epoch][int seq][long serverTick][int deltaCount]
 *   then per delta: [byte typeId][payload]
 *
 * Canvas commands encode as [byte op][double args...][UTF text if OP_DRAW_TEXT].
 * NodeProps values encode as plain doubles; VISIBLE travels as 0/1 and TINT as the exact
 * double representation of the unsigned 32-bit ARGB value (0..2^32-1 — exactly representable
 * in a double); appliers recover it with (int)(long)value.
 *
 * Decoding is strict: any unknown version/type/op/mask-bit, truncation, TRAILING DATA, or
 * count above the sanity caps throws {@link CodecException}. Sanity caps exist so a garbage
 * payload cannot force a huge allocation before the structure check fails.
 */
public final class BatchCodec {
	private BatchCodec() {}

	/**
	 * Leading short of a DEFLATE-wrapped batch: [short -2][int rawLen][deflated raw batch].
	 *
	 * A sentinel rather than a PROTOCOL_VERSION bump on purpose. The version is shared by
	 * the batch, snapshot and message codecs AND by the persisted structure, so bumping it
	 * for a batch-only change would force a save migration for nothing. Protocol versions
	 * are positive, so a negative marker can never collide, and the inflated payload still
	 * carries the real version — the strictness of the inner decode is unchanged.
	 */
	static final short COMPRESSED_MARKER = -2;

	/** Below this, framing overhead outweighs any saving; small batches ship raw. */
	static final int COMPRESS_THRESHOLD_BYTES = 256;

	/**
	 * Hard ceiling on a declared inflated size, refused BEFORE any allocation.
	 *
	 * Sized to what a batch can legitimately be, NOT to the transport ceiling. The transport
	 * ceiling exists for resource bodies (up to a 256 MiB texture), and reusing it here would
	 * let a few KB of deflate claim a quarter-gigabyte per inbound batch — reopening the
	 * amplification this constant exists to close.
	 *
	 * RAISED 4 -> 8 MiB at the v10 bump, and only a bump can move this: an old jar refuses a
	 * v10 batch either right here at the size check (before any version read) or at strict
	 * version equality below — size first, then version — so no pre-raise decoder ever
	 * services post-raise content. Why it had to move: v10's 15-bit NodeProps makes the widest
	 * producible delta 129 B, and MAX_DELTAS x 129 = 4,227,072 alone exceeds the old 4 MiB;
	 * the complete v10 worst case (header + delta product + the write, submit, program and
	 * mesh per-batch allowances) is 6,160,407 B = 73% of this ceiling — BatchSizeBoundTest
	 * derives it and pins the &lt;90% headroom rule. 6 MiB would sit at 97% and fail that pin.
	 * The trade-off deliberately re-accepted at 8 MiB: a crafted compressed inbound can claim
	 * twice the pre-validation allocation it could before — still bounded, still one batch.
	 */
	static final int MAX_INFLATED_BYTES = 8 * 1024 * 1024;

	public static byte[] encode(SceneBatch batch) {
		byte[] raw = encodeRaw(batch);
		if (raw.length < COMPRESS_THRESHOLD_BYTES) {
			return raw;
		}
		java.util.zip.Deflater deflater = new java.util.zip.Deflater(
				java.util.zip.Deflater.BEST_SPEED);
		try {
			deflater.setInput(raw);
			deflater.finish();
			// Worst case for incompressible input is slightly larger than the input; give
			// the buffer room so a single deflate() call always completes.
			byte[] buffer = new byte[raw.length + 64];
			int compressed = deflater.deflate(buffer);
			if (!deflater.finished() || compressed + 6 >= raw.length) {
				return raw; // incompressible: the wrapper would only add bytes
			}
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(compressed + 6);
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeShort(COMPRESSED_MARKER);
			out.writeInt(raw.length);
			out.write(buffer, 0, compressed);
			return bytes.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		} finally {
			deflater.end();
		}
	}

	private static byte[] encodeRaw(SceneBatch batch) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeShort(V2Wire.PROTOCOL_VERSION);
			out.writeUTF(batch.sceneId);
			out.writeInt(batch.epoch);
			out.writeInt(batch.seq);
			out.writeLong(batch.serverTick);
			out.writeInt(batch.deltas.size());
			for (Delta d : batch.deltas) {
				out.writeByte(d.typeId());
				writeDelta(out, d);
			}
			return bytes.toByteArray();
		} catch (IOException e) {
			// The only reachable IOException here would be writeUTF's 65535-byte limit, and
			// CanvasCommand's MAX_TEXT_CHARS constructor cap keeps every string far below it —
			// so this indicates a bug (an invariant bypassed), not user input.
			throw new RuntimeException(e);
		}
	}

	private static void writeDelta(DataOutputStream out, Delta d) throws IOException {
		if (d instanceof Delta.NodeCreate) {
			Delta.NodeCreate n = (Delta.NodeCreate) d;
			out.writeInt(n.nodeId);
			out.writeByte(n.nodeType);
			out.writeInt(n.ref);
			out.writeInt(n.parent);
		} else if (d instanceof Delta.NodeFree) {
			out.writeInt(((Delta.NodeFree) d).nodeId);
		} else if (d instanceof Delta.NodeProps) {
			Delta.NodeProps n = (Delta.NodeProps) d;
			out.writeInt(n.nodeId);
			out.writeInt(n.mask);
			for (double v : n.values) {
				out.writeDouble(v);
			}
		} else if (d instanceof Delta.ResourceCreate) {
			Delta.ResourceCreate r = (Delta.ResourceCreate) d;
			out.writeInt(r.resId);
			out.writeByte(r.resType);
			out.writeInt(r.width);
			out.writeInt(r.height);
			out.writeInt(r.sizeBytes);
			out.writeLong(r.hash);
			out.writeInt(r.commandCap);
		} else if (d instanceof Delta.ResourceFree) {
			out.writeInt(((Delta.ResourceFree) d).resId);
		} else if (d instanceof Delta.CanvasPublish) {
			Delta.CanvasPublish c = (Delta.CanvasPublish) d;
			out.writeInt(c.resId);
			writeCommands(out, c.commands);
		} else if (d instanceof Delta.CanvasAppend) {
			Delta.CanvasAppend c = (Delta.CanvasAppend) d;
			out.writeInt(c.resId);
			writeCommands(out, c.commands);
		} else if (d instanceof Delta.TextureWrite) {
			Delta.TextureWrite t = (Delta.TextureWrite) d;
			out.writeInt(t.resId);
			out.writeInt(t.version);
			out.writeInt(t.x);
			out.writeInt(t.y);
			out.writeInt(t.w);
			out.writeInt(t.h);
			// No length prefix: w*h*4 is the single source of truth for the payload size.
			out.write(t.pixels);
		} else if (d instanceof Delta.ProgramCreate) {
			Delta.ProgramCreate p = (Delta.ProgramCreate) d;
			out.writeInt(p.programId);
			out.writeByte(p.stage);
			out.writeInt(p.structuralOps);
			// Length-prefixed, unlike TextureWrite: a texture write's size is derivable from
			// w*h*4, a program's is not derivable from anything in the header. blobCopy(), not
			// the field: the blob is the delta's identity (equals reads it), so even the codec
			// goes through the defensive accessor — one copy per encode, bounded by
			// MAX_PROGRAM_BYTES_PER_BATCH.
			byte[] blob = p.blobCopy();
			out.writeInt(blob.length);
			out.write(blob);
		} else if (d instanceof Delta.ProgramFree) {
			out.writeInt(((Delta.ProgramFree) d).programId);
		} else if (d instanceof Delta.NodeAttach) {
			Delta.NodeAttach a = (Delta.NodeAttach) d;
			out.writeInt(a.nodeId);
			out.writeInt(a.programId);
			out.writeLong(a.attachedWorldTime);
		} else if (d instanceof Delta.MeshCreate) {
			Delta.MeshCreate m = (Delta.MeshCreate) d;
			out.writeInt(m.resId);
			// Both blobs length-prefixed (ProgramCreate's reasoning: nothing in the header
			// derives their sizes), via the defensive accessors — the arrays are the delta's
			// identity. Blob-interior bytes are little-endian; this frame stays big-endian.
			byte[] vertexBytes = m.vertexCopy();
			byte[] indexBytes = m.indexCopy();
			out.writeInt(vertexBytes.length);
			out.write(vertexBytes);
			out.writeInt(indexBytes.length);
			out.write(indexBytes);
		} else if (d instanceof Delta.UniformSet) {
			Delta.UniformSet u = (Delta.UniformSet) d;
			out.writeInt(u.nodeId);
			out.writeUTF(u.name);
			out.writeByte(u.type);
			for (double v : u.values) {
				out.writeDouble(v);
			}
			out.writeBoolean(u.immediate);
		} else if (d instanceof Delta.SceneProp) {
			Delta.SceneProp s = (Delta.SceneProp) d;
			out.writeInt(s.propId);
			out.writeInt(s.payload.length);
			out.write(s.payload);
		} else {
			throw new IllegalArgumentException("Unencodable delta " + d.getClass());
		}
	}

	static void writeCommands(DataOutputStream out, java.util.List<CanvasCommand> commands) throws IOException {
		out.writeInt(commands.size());
		for (CanvasCommand cmd : commands) {
			out.writeByte(cmd.op);
			for (double a : cmd.args) {
				out.writeDouble(a);
			}
			if (cmd.op == V2Wire.OP_DRAW_TEXT) {
				out.writeUTF(cmd.text);
			}
		}
	}

	public static SceneBatch decode(byte[] data) throws CodecException {
		if (data != null && data.length >= 2
				&& (short) (((data[0] & 0xFF) << 8) | (data[1] & 0xFF)) == COMPRESSED_MARKER) {
			return decode(inflate(data));
		}
		try {
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
			short version = in.readShort();
			if (version != V2Wire.PROTOCOL_VERSION)
				throw new CodecException("Unsupported protocol version " + version);
			String sceneId = in.readUTF();
			int epoch = in.readInt();
			if (epoch == 0)
				throw new CodecException("Epoch 0 is reserved");
			int seq = in.readInt();
			long tick = in.readLong();
			int count = in.readInt();
			if (count < 0 || count > V2Wire.MAX_DELTAS)
				throw new CodecException("Delta count out of range: " + count);
			ArrayList<Delta> deltas = new ArrayList<Delta>(Math.min(count, 4096));
				int[] writeBytes = new int[1];
				int[] programBytes = new int[1];
				int[] meshBytes = new int[1];
			for (int i = 0; i < count; i++) {
					deltas.add(readDelta(in, writeBytes, programBytes, meshBytes));
			}
			if (in.read() != -1)
				throw new CodecException("Trailing data after batch");
			return new SceneBatch(sceneId, epoch, seq, tick, deltas);
		} catch (EOFException e) {
			throw new CodecException("Truncated batch", e);
		} catch (IOException e) {
			throw new CodecException("Malformed batch", e);
		} catch (IllegalArgumentException e) {
			// Constructor validation reached from the wire: CanvasCommand (bad op/arg shape) AND
			// any delta constructor invariant a read arm does not pre-check — at v10 that
			// includes NodeProps' quat all-or-none rule, whose decode-side enforcement is THIS
			// conversion. Narrowing this catch needs a pre-check added to the arm first.
			throw new CodecException("Malformed batch: " + e.getMessage(), e);
		}
	}

	/**
	 * Inflate a marker-wrapped batch. The declared size is validated BEFORE allocating, and
	 * the inflater is bounded by that allocation, so a crafted payload claiming gigabytes is
	 * refused rather than serviced. A stream that inflates to a different size than declared
	 * is a malformed batch, not a resizable one.
	 */
	private static byte[] inflate(byte[] data) throws CodecException {
		if (data.length < 6)
			throw new CodecException("Truncated compressed batch");
		int rawLen = ((data[2] & 0xFF) << 24) | ((data[3] & 0xFF) << 16)
				| ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
		if (rawLen < 2 || rawLen > MAX_INFLATED_BYTES)
			throw new CodecException("Compressed batch declares an unusable size: " + rawLen);
		java.util.zip.Inflater inflater = new java.util.zip.Inflater();
		try {
			inflater.setInput(data, 6, data.length - 6);
			byte[] raw = new byte[rawLen];
			int produced = inflater.inflate(raw);
			if (produced != rawLen || !inflater.finished())
				throw new CodecException("Compressed batch does not inflate to its declared size");
			// The inner decode's no-trailing-data rule must survive the wrapper: an inflater
			// simply stops at the end of the deflate stream and never looks at what follows,
			// so without this check the compressed path silently accepts appended garbage
			// that the raw path rejects.
			if (inflater.getRemaining() != 0)
				throw new CodecException("Trailing data after compressed batch");
			// encode() wraps at most once and encodeRaw() always leads with the positive
			// PROTOCOL_VERSION, so a marker INSIDE an inflated payload is hostile or corrupt.
			// Refusing it pins decode's recursion at one level: without this, N nested
			// wrappers recurse N deep with every level's byte[] simultaneously live, so a
			// few hundred KB on the wire becomes hundreds of MB and a StackOverflowError —
			// which escapes the CodecException-only catch in the inbound drain and takes the
			// client down. That is the very amplification the size ceiling above exists to
			// refuse, so the ceiling must not be bypassable by nesting.
			if (raw.length >= 2
					&& (short) (((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF)) == COMPRESSED_MARKER)
				throw new CodecException("Nested compressed batch");
			return raw;
		} catch (java.util.zip.DataFormatException e) {
			throw new CodecException("Malformed compressed batch", e);
		} finally {
			inflater.end();
		}
	}

	/**
	 * @param writeBytes single-element accumulator of texture-write payload bytes seen so far
	 *                   in this batch; the per-tick aggregate cap is enforced against it so a
	 *                   batch cannot smuggle unbounded pixel data past the per-call cap.
	 */
	private static Delta readDelta(DataInputStream in, int[] writeBytes, int[] programBytes,
			int[] meshBytes) throws IOException, CodecException {
		byte type = in.readByte();
		switch (type) {
			case V2Wire.DELTA_NODE_CREATE: {
				int nodeId = in.readInt();
				byte nodeType = in.readByte();
				if (!V2Wire.isKnownNodeType(nodeType))
					throw new CodecException("Unknown node type " + nodeType);
				// No version gate on the wire: the batch path checks PROTOCOL_VERSION for strict
				// equality, so a peer that could send a 4-shaped NodeCreate is already refused
				// before reaching here. The gate belongs on the persisted path alone.
				int ref = in.readInt();
				return new Delta.NodeCreate(nodeId, nodeType, ref, in.readInt());
			}
			case V2Wire.DELTA_NODE_FREE:
				return new Delta.NodeFree(in.readInt());
			case V2Wire.DELTA_NODE_PROPS: {
				int nodeId = in.readInt();
				int mask = in.readInt();
				if ((mask & ~V2Wire.KNOWN_PROPS_MASK) != 0)
					throw new CodecException("Unknown prop mask bits in " + mask);
				int bits = Integer.bitCount(mask);
				double[] values = new double[bits];
				for (int i = 0; i < bits; i++) {
					values[i] = in.readDouble();
				}
				return new Delta.NodeProps(nodeId, mask, values);
			}
			case V2Wire.DELTA_RES_CREATE: {
				int resId = in.readInt();
				byte resType = in.readByte();
				if (!V2Wire.isKnownResType(resType))
					throw new CodecException("Unknown resource type " + resType);
				// A blob-less mesh record must not be able to come into being on any path; the
				// apply arm and the pre-v10 persisted loop carry the same refusal.
				if (resType == V2Wire.RES_MESH)
					throw new CodecException("Meshes arrive via DELTA_MESH_CREATE, not RES_CREATE");
				return new Delta.ResourceCreate(resId, resType, in.readInt(),
						in.readInt(), in.readInt(), in.readLong(), in.readInt());
			}
			case V2Wire.DELTA_RES_FREE:
				return new Delta.ResourceFree(in.readInt());
			case V2Wire.DELTA_CANVAS_PUBLISH: {
				int resId = in.readInt();
				return new Delta.CanvasPublish(resId, readCommands(in));
			}
			case V2Wire.DELTA_CANVAS_APPEND: {
				int resId = in.readInt();
				return new Delta.CanvasAppend(resId, readCommands(in));
			}
			case V2Wire.DELTA_TEX_WRITE: {
				int resId = in.readInt();
				int version = in.readInt();
				int x = in.readInt();
				int y = in.readInt();
				int w = in.readInt();
				int h = in.readInt();
				// Validate everything BEFORE allocating: a hostile header must not be able to
				// make us reserve memory it never intends to fill.
				if (w < 1 || h < 1 || w > V2Wire.MAX_TEXTURE_DIM || h > V2Wire.MAX_TEXTURE_DIM)
					throw new CodecException("Texture write region out of range: " + w + "x" + h);
				if (x < 0 || y < 0)
					throw new CodecException("Texture write origin out of range: " + x + "," + y);
				if (version < 1)
					throw new CodecException("Texture write version out of range: " + version);
				long len = (long) w * (long) h * 4L;
				if (len > V2Wire.MAX_WRITE_REGION_BYTES)
					throw new CodecException("Texture write payload too large: " + len);
				if (len > in.available())
					throw new CodecException("Texture write payload exceeds remaining data");
				writeBytes[0] += (int) len;
				// The BATCH bound, not the per-tick one. This accumulates across a whole decoded
				// batch, and a batch spans up to two tick allowances — direct callbacks run off
				// the server thread, so a write admitted between the END-phase seal and the next
				// START-phase grant is charged to one tick and staged into the following batch.
				// Checking a batch total against a per-tick number would reject legal traffic
				// wholesale at every receiver, which costs the frame and forces a resync.
				if (writeBytes[0] > V2Wire.MAX_WRITE_BYTES_PER_BATCH)
					throw new CodecException("Batch texture-write payload over the per-batch cap: "
							+ writeBytes[0]);
				byte[] pixels = new byte[(int) len];
				in.readFully(pixels);
				return new Delta.TextureWrite(resId, version, x, y, w, h, pixels);
			}
			case V2Wire.DELTA_PROGRAM_CREATE: {
				int programId = in.readInt();
				byte stage = in.readByte();
				int structuralOps = in.readInt();
				int len = in.readInt();
				// Everything validated BEFORE the allocation, the rule DELTA_TEX_WRITE states:
				// a hostile header must not reserve memory it never intends to fill. The ceiling
				// is the IR codec's own, so a length no legal program could reach dies here
				// rather than inside the parse.
				if (len < 1 || len > opengpu.v2.ocsl.OcslWire.MAX_BLOB_BYTES)
					throw new CodecException("Program blob length out of range: " + len);
				if (len > in.available())
					throw new CodecException("Program blob exceeds remaining data");
				// The charge is the SERVER's verdict and a mirror does not re-derive it, so the
				// decoder is the only thing standing between a crafted number and whatever later
				// reads it. Bound it by the CEILING, not by maxStructuralOps(stage): a decoder
				// that tightened per stage would refuse a legitimately larger program from a peer
				// whose stage cap has been raised, turning an acceptance-policy change into a
				// wire break. IrValidator's own note puts it exactly: the validator's caps are
				// raiseable, while "the codec, whose rejections are format identity", is not.
				if (structuralOps < 0 || structuralOps > opengpu.v2.ocsl.IrValidator.MAX_STRUCTURAL_OPS)
					throw new CodecException("Program structural charge out of range: " + structuralOps);
				if (!opengpu.v2.ocsl.OcslWire.isKnownStage(stage))
					throw new CodecException("Unknown program stage " + (stage & 0xFF));
				// The id is a raw int off the wire and DeltaApplier computes programId + 1 from
				// it. At Integer.MAX_VALUE that wraps to MIN_VALUE, leaving nextProgramId below
				// the table's own last key — the exact state SnapshotCodec.decode refuses a
				// snapshot for, reached through a batch that nothing else would reject.
				if (programId < 1 || programId == Integer.MAX_VALUE)
					throw new CodecException("Program id out of range: " + programId);
				// The per-batch aggregate, accumulated across the whole decode exactly as the
				// texture-write bound is. The count cap and the per-blob cap are each enforced
				// above, but their PRODUCT is what a receiver has to allocate, and 32768 deltas
				// times a 64 KiB blob is three orders of magnitude past MAX_INFLATED_BYTES.
				programBytes[0] += len;
				if (programBytes[0] > V2Wire.MAX_PROGRAM_BYTES_PER_BATCH)
					throw new CodecException("Batch program payload over the per-batch cap: "
							+ programBytes[0]);
				byte[] blob = new byte[len];
				in.readFully(blob);
				return new Delta.ProgramCreate(programId, stage, structuralOps, blob);
			}
			case V2Wire.DELTA_PROGRAM_FREE:
				return new Delta.ProgramFree(in.readInt());
			case V2Wire.DELTA_NODE_ATTACH: {
				int nodeId = in.readInt();
				int programId = in.readInt();
				long stamp = in.readLong();
				// Every shape the constructor refuses is refused HERE first, so the unchecked
				// IllegalArgumentException cannot escape a `throws CodecException` API — the
				// inbound drain catches CodecException only. The program id is otherwise
				// unconstrained on purpose: a dangling attachment is legal (ANIM-17), so "does
				// this program exist" is not a decode-time question.
				if (programId < 0)
					throw new CodecException("Attach program id out of range: " + programId);
				if (stamp < 0L)
					throw new CodecException("Attach stamp out of range: " + stamp);
				if (programId == 0 && stamp != 0L)
					throw new CodecException("A detach must carry a zero stamp, got " + stamp);
				return new Delta.NodeAttach(nodeId, programId, stamp);
			}
			case V2Wire.DELTA_MESH_CREATE: {
				int resId = in.readInt();
				// Everything validated BEFORE either allocation (DELTA_TEX_WRITE's rule). Length
				// bounds come first from the declared ints; the full structural validation runs
				// on the read bytes because index-range checking needs the actual data.
				if (resId < 1 || resId == Integer.MAX_VALUE)
					throw new CodecException("Mesh resource id out of range: " + resId);
				int vertexLen = in.readInt();
				if (vertexLen < V2Wire.MESH_VERTEX_STRIDE || vertexLen > V2Wire.MAX_MESH_VERTEX_BYTES)
					throw new CodecException("Mesh vertex blob length out of range: " + vertexLen);
				if (vertexLen > in.available())
					throw new CodecException("Mesh vertex blob exceeds remaining data");
				byte[] vertexBytes = new byte[vertexLen];
				in.readFully(vertexBytes);
				int indexLen = in.readInt();
				if (indexLen < 3 * V2Wire.MESH_INDEX_BYTES || indexLen > V2Wire.MAX_MESH_INDEX_BYTES)
					throw new CodecException("Mesh index blob length out of range: " + indexLen);
				if (indexLen > in.available())
					throw new CodecException("Mesh index blob exceeds remaining data");
				// The per-batch aggregate, the ProgramCreate reasoning: count cap x per-blob cap
				// is what a receiver must allocate, and the product dwarfs the ceiling.
				meshBytes[0] += vertexLen + indexLen;
				if (meshBytes[0] > V2Wire.MAX_MESH_BYTES_PER_BATCH)
					throw new CodecException("Batch mesh payload over the per-batch cap: "
							+ meshBytes[0]);
				byte[] indexBytes = new byte[indexLen];
				in.readFully(indexBytes);
				try {
					V2Wire.validateMeshBlobs(vertexBytes, indexBytes);
				} catch (IllegalArgumentException e) {
					throw new CodecException("Malformed mesh: " + e.getMessage(), e);
				}
				return new Delta.MeshCreate(resId, vertexBytes, indexBytes);
			}
			case V2Wire.DELTA_UNIFORM_SET: {
				int nodeId = in.readInt();
				String name = in.readUTF();
				byte utype = in.readByte();
				// Every shape the constructor refuses is refused HERE first as a CodecException
				// (the NodeAttach discipline): the inbound drain catches CodecException only.
				try {
					opengpu.v2.ocsl.IrStructure.checkName(0, name);
				} catch (opengpu.v2.ocsl.IrStructure.StructureException e) {
					throw new CodecException("Uniform name: " + e.getMessage(), e);
				}
				if (utype < Delta.UniformSet.TYPE_CLEAR || utype > Delta.UniformSet.TYPE_VEC4)
					throw new CodecException("Unknown uniform type " + utype);
				double[] values = new double[utype];
				for (int i = 0; i < values.length; i++) {
					values[i] = in.readDouble();
				}
				return new Delta.UniformSet(nodeId, name, utype, values, in.readBoolean());
			}
			case V2Wire.DELTA_SCENE_PROP: {
				int propId = in.readInt();
				int len = in.readInt();
				if (len < 0 || len > V2Wire.MAX_SCENE_PROP_PAYLOAD)
					throw new CodecException("Scene prop payload out of range: " + len);
				byte[] payload = new byte[len];
				in.readFully(payload);
				return new Delta.SceneProp(propId, payload);
			}
			default:
				throw new CodecException("Unknown delta type " + type);
		}
	}

	/**
	 * Decode a standalone packed command list — the payload Lua hands to canvasSubmit.
	 *
	 * Deliberately the SAME reader the batch codec uses, so the format a program packs is
	 * definitionally the format the wire carries; a second parser would be a second thing to
	 * keep in step. Trailing bytes are rejected for the same reason the batch decoder rejects
	 * them: a payload the sender thought meant something else is not a payload to guess at.
	 */
	public static ArrayList<CanvasCommand> decodeCommandList(byte[] data) throws CodecException {
		return decodeCommandList(data, V2Wire.MAX_COMMANDS);
	}

	/**
	 * Decode a standalone command list, refusing more than {@code maxCommands} of them.
	 *
	 * The bound matters because a payload's BYTE length does not bound its command COUNT: the
	 * zero-arity ops encode in one byte, so a 64 KiB list can declare 65,532 commands. Callers
	 * pass the count the target will actually accept (a canvas's command cap), and because the
	 * count is the first field on the wire the refusal costs one readInt and no allocation.
	 */
	public static ArrayList<CanvasCommand> decodeCommandList(byte[] data, int maxCommands)
			throws CodecException {
		if (data == null)
			throw new CodecException("Command payload required");
		try {
			DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(data));
			ArrayList<CanvasCommand> commands = readCommands(in, maxCommands);
			if (in.read() != -1)
				throw new CodecException("Trailing data after command list");
			return commands;
		} catch (java.io.EOFException e) {
			throw new CodecException("Truncated command list", e);
		} catch (IOException e) {
			throw new CodecException("Malformed command list", e);
		} catch (IllegalArgumentException e) {
			throw new CodecException("Malformed command list: " + e.getMessage(), e);
		}
	}

	static ArrayList<CanvasCommand> readCommands(DataInputStream in) throws IOException, CodecException {
		return readCommands(in, V2Wire.MAX_COMMANDS);
	}

	static ArrayList<CanvasCommand> readCommands(DataInputStream in, int maxCommands)
			throws IOException, CodecException {
		int count = in.readInt();
		if (count < 0 || count > V2Wire.MAX_COMMANDS)
			throw new CodecException("Command count out of range: " + count);
		if (count > maxCommands)
			throw new CodecException("Command count " + count + " exceeds the limit of " + maxCommands);
		ArrayList<CanvasCommand> commands = new ArrayList<CanvasCommand>(Math.min(count, 4096));
		for (int i = 0; i < count; i++) {
			byte op = in.readByte();
			int argc = V2Wire.canvasOpArgCount(op);
			if (argc < 0)
				throw new CodecException("Unknown canvas op " + op);
			double[] args = new double[argc];
			for (int a = 0; a < argc; a++) {
				args[a] = in.readDouble();
			}
			String text = op == V2Wire.OP_DRAW_TEXT ? in.readUTF() : null;
			commands.add(new CanvasCommand(op, args, text));
		}
		return commands;
	}
}
