package opengpu.v2.ocsl;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import opengpu.v2.protocol.CodecException;

/**
 * The canonical OCSL IR codec: bytes in, {@link IrProgram} out, and back.
 *
 * This is the security boundary in code. Clients independently re-validate every blob they
 * receive, so this decoder assumes its input is hostile: it never allocates proportional to a
 * count it has not first bounded, never loops on attacker-chosen structure without a bound, and
 * rejects with {@link CodecException} rather than returning something partial.
 *
 * STRUCTURE ONLY. This layer checks that a blob is well-formed — magic, version, known stage,
 * known opcodes, correct arity, in-range indices, balanced loops, exact length. It does NOT check
 * types, caps, register liveness, or stage-applicable registers; those are the validator's, and
 * keeping them apart is what lets the validator's caps be raised under the monotonicity rule
 * while this layer's rejections stay format identity.
 */
public final class IrCodec {
	private IrCodec() {}

	private static final Charset UTF8 = Charset.forName("UTF-8");

	/**
	 * Where a blob came from, because the answer changes what is legal.
	 *
	 * THIS IS THE FORMAT-0 GATE. Stage B builds the language skeleton with no player-reachable
	 * surface, so the decision of record is that no program blob reaches a world save yet — and a
	 * decision that is only written down is one this repo has crossed silently three times. Any
	 * path that reads a persisted program must say {@link #PERSISTED}, and a pre-release blob
	 * arriving that way is refused. When the first surface ships, {@link OcslWire#FORMAT_VERSION}
	 * moves to 1 in the same change that opens persistence, and this gate stops firing on its own.
	 */
	public enum Source {
		/** Built in memory or received on the wire. Pre-release blobs are fine here. */
		TRANSIENT,
		/** Read back from a world save. Pre-release blobs are refused. */
		PERSISTED
	}

	// ---------------------------------------------------------------- encode

	/** Every op field on the wire is one unsigned short; anything else must be refused, not folded. */
	private static void checkFits(String opName, String what, int value) throws CodecException {
		if (value < 0 || value > 0xFFFF) {
			throw new CodecException(opName + " " + what + " is " + value
					+ ", outside the unsigned 16 bits the wire gives it");
		}
	}

	public static byte[] encode(IrProgram program) throws CodecException {
		// THE SAME STRUCTURAL RULES THE DECODER ENFORCES, checked before a byte is written and
		// shared with the validator rather than restated here. Encode used to accept thirteen
		// shapes decode refuses -- a 1025-entry pool, a non-canonical swizzle, a uniform named
		// "my name", a reserved stage -- each producing a blob that runs on its author's client
		// and is unreadable by every peer. It also wrote the header counts through writeShort
		// unchecked, so a program with 65633 declared registers encoded to a valid blob declaring
		// 97: one encode call, two different programs.
		try {
			IrStructure.check(program);
		} catch (IrStructure.StructureException e) {
			throw new CodecException(e.opIndex < 0 ? e.getMessage()
					: "op " + e.opIndex + ": " + e.getMessage());
		}

		ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
		DataOutputStream out = new DataOutputStream(bytes);
		try {
			out.writeInt(OcslWire.MAGIC);
			out.writeShort(OcslWire.FORMAT_VERSION);
			out.writeByte(program.stage);
			out.writeByte(0); // reserved; a decoder that finds this non-zero is reading a newer blob

			out.writeShort(program.constantCount());
			for (int i = 0; i < program.constantCount(); i++) {
				int width = program.constantWidth(i);
				if (width < 1 || width > 4) {
					throw new CodecException("Constant " + i + " has " + width
							+ " components; 1..4 only");
				}
				// Width IS the type tag. There is no vec1, so 1 means float and no separate type
				// byte can disagree with the payload length.
				out.writeByte(width);
				for (int c = 0; c < width; c++) {
					float v = program.constantComponent(i, c);
					// Symmetric with decode, which has always refused these. Without it, a program
					// built in memory with an Inf constant encoded happily into a blob its own
					// decoder rejects -- the author sees a working local run and a peer sees a
					// corrupt program.
					if (Float.isNaN(v) || Float.isInfinite(v)) {
						// Worded to match the decoder's refusal deliberately: two checks on the
						// same rule that disagree about what to call it read as two rules.
						throw new CodecException("Constant " + i + " component " + c
								+ " is non-finite (" + v + "); the pool carries finite values only");
					}
					out.writeFloat(v);
				}
			}

			out.writeShort(program.declaredRegisters);

			List<IrOp> ops = program.ops();
			out.writeShort(ops.size());
			for (IrOp op : ops) {
				OcslWire.Shape shape = OcslWire.shapeOf(op.opcode);
				if (shape == null) {
					throw new CodecException("Cannot encode unknown opcode " + (op.opcode & 0xFF));
				}
				if (op.operandCount() != shape.operandCount()) {
					throw new CodecException(shape.name + " takes " + shape.operandCount()
							+ " operands, got " + op.operandCount());
				}
				if (shape.hasDst != (op.dst >= 0)) {
					throw new CodecException(shape.name + (shape.hasDst
							? " needs a destination register" : " writes no destination"));
				}
				out.writeByte(op.opcode);
				// RANGE-CHECKED, NOT TRUNCATED. writeShort silently keeps the low 16 bits, and the
				// consequence was worse than a bad blob: `ITOF …, 65536` validated, crashed the VM
				// with an out-of-range loop depth, and then encoded to a blob whose immediate was 0
				// and which decoded and ran cleanly. One validate() call certified two different
				// programs -- the one that ran and the one that shipped.
				checkFits(shape.name, "destination", shape.hasDst ? op.dst : 0);
				out.writeShort(shape.hasDst ? op.dst : 0);
				for (int i = 0; i < op.operandCount(); i++) {
					checkFits(shape.name, "operand " + i, op.operand(i));
					out.writeShort(op.operand(i));
				}
			}

			List<String> names = program.names();
			out.writeByte(names.size());
			for (String name : names) {
				byte[] raw = name.getBytes(UTF8);
				if (raw.length > OcslWire.MAX_NAME_LENGTH) {
					throw new CodecException("Name too long: " + name);
				}
				out.writeByte(raw.length);
				out.write(raw);
			}

			out.writeShort(OcslWire.TRAILING_GUARD);
			out.flush();
		} catch (IOException e) {
			throw new CodecException("Encoding failed", e);
		}
		byte[] result = bytes.toByteArray();
		if (result.length > OcslWire.MAX_BLOB_BYTES) {
			throw new CodecException("Encoded program is " + result.length
					+ " bytes, over the " + OcslWire.MAX_BLOB_BYTES + " ceiling");
		}
		return result;
	}

	// ---------------------------------------------------------------- decode

	public static IrProgram decode(byte[] blob, Source source) throws CodecException {
		if (blob == null) {
			throw new CodecException("No program bytes");
		}
		// Bounded before anything is read, so an oversized blob costs a length check and not a
		// parse. The ceiling is deliberately far above any admissible program.
		if (blob.length > OcslWire.MAX_BLOB_BYTES) {
			throw new CodecException("Program blob is " + blob.length + " bytes, over the "
					+ OcslWire.MAX_BLOB_BYTES + " ceiling");
		}
		DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(blob));
		try {
			int magic = in.readInt();
			if (magic != OcslWire.MAGIC) {
				throw new CodecException("Not an OCSL program (magic 0x"
						+ Integer.toHexString(magic) + ")");
			}
			short version = in.readShort();
			if (version != OcslWire.FORMAT_VERSION) {
				// Both directions are refused, and the message says which. Reading a blob from
				// the FUTURE is the case that has to fail loudly rather than be guessed at.
				throw new CodecException("Program declares IR format v" + version
						+ "; this build reads v" + OcslWire.FORMAT_VERSION);
			}
			if (source == Source.PERSISTED && version == 0) {
				throw new CodecException("Refusing a pre-release (format 0) program blob read from"
						+ " a world save. Stage B ships no program surface, so nothing should have"
						+ " persisted one; see docs/dev/DESIGN-RENDERER-V2.md, the animator dry"
						+ " run's scope decision. The format version moves to 1 in the same change"
						+ " that opens persistence.");
			}

			byte stage = in.readByte();
			if (!OcslWire.isKnownStage(stage)) {
				throw new CodecException("Unknown program stage " + (stage & 0xFF));
			}
			if (stage == OcslWire.STAGE_ANIMATOR) {
				// THE TRIPWIRE. Deliberately distinct from the unknown-stage rejection above: the
				// animator stage id IS known and IS reserved, and its 18 pending amendments are
				// the reason it cannot be accepted. Deleting this branch is the act that reopens
				// the surface, which is why the pointer lives here rather than in a roadmap.
				throw new CodecException("The OCSL animator stage is reserved and not yet"
						+ " implemented: its dry run found 0 of 9 programs encodable and left 18"
						+ " amendments pending, including the property table this decoder would"
						+ " need. See docs/dev/OCSL-ANIMATOR-DRYRUN.md before enabling it.");
			}
			if (stage == OcslWire.STAGE_COMPUTE) {
				throw new CodecException("The OCSL compute stage is reserved for after Stage D and"
						+ " is not implemented; see docs/dev/DESIGN-RENDERER-V2.md § Future stage.");
			}
			int reserved = in.readUnsignedByte();
			if (reserved != 0) {
				throw new CodecException("Reserved header byte is " + reserved + ", not 0");
			}

			int constantCount = in.readUnsignedShort();
			if (constantCount > OcslWire.MAX_CONSTANTS) {
				throw new CodecException("Constant pool of " + constantCount + " exceeds the cap of "
						+ OcslWire.MAX_CONSTANTS);
			}
			float[][] constants = new float[constantCount][];
			for (int i = 0; i < constantCount; i++) {
				int width = in.readUnsignedByte();
				if (width < 1 || width > 4) {
					throw new CodecException("Constant " + i + " declares " + width
							+ " components; 1..4 only");
				}
				constants[i] = new float[width];
				for (int c = 0; c < width; c++) {
					constants[i][c] = in.readFloat();
					// A non-finite CONSTANT is refused at the boundary rather than left to the
					// runtime's non-finite rules: those exist for values a program COMPUTES, and a
					// literal Inf in the pool is a malformed blob, not an arithmetic outcome.
					if (Float.isNaN(constants[i][c]) || Float.isInfinite(constants[i][c])) {
						throw new CodecException("Constant " + i + " component " + c
								+ " is non-finite");
					}
				}
			}

			int declaredRegisters = in.readUnsignedShort();
			if (declaredRegisters > OcslWire.MAX_REGISTERS) {
				throw new CodecException("Declared register count " + declaredRegisters
						+ " exceeds the cap of " + OcslWire.MAX_REGISTERS);
			}

			int opCount = in.readUnsignedShort();
			if (opCount > OcslWire.MAX_OPS) {
				throw new CodecException("Op count " + opCount + " exceeds the cap of "
						+ OcslWire.MAX_OPS);
			}
			List<IrOp> ops = new ArrayList<IrOp>(opCount);
			int loopDepth = 0;
			for (int i = 0; i < opCount; i++) {
				byte opcode = in.readByte();
				OcslWire.Shape shape = OcslWire.shapeOf(opcode);
				if (shape == null) {
					throw new CodecException("Unknown opcode " + (opcode & 0xFF) + " at op " + i);
				}
				int dstRaw = in.readUnsignedShort();
				int dst = shape.hasDst ? dstRaw : -1;
				if (shape.hasDst) {
					if (dst >= declaredRegisters) {
						throw new CodecException(shape.name + " at op " + i + " writes register "
								+ dst + ", outside the declared " + declaredRegisters);
					}
				} else if (dstRaw != 0) {
					throw new CodecException(shape.name + " at op " + i
							+ " writes no destination but its slot is " + dstRaw + ", not 0");
				}
				int[] operands = new int[shape.operandCount()];
				for (int k = 0; k < operands.length; k++) {
					operands[k] = in.readUnsignedShort();
					checkOperand(shape, i, k, operands[k], declaredRegisters, constantCount,
							loopDepth);
				}
				if (opcode == OcslWire.OP_FOR) {
					// Bounded HERE, not left to the validator, because these two numbers decide how
					// much work a blob describes and the count that prices it is computed after
					// decode. An unbounded trip count in a structurally valid 6-op program is the
					// difference between 6 instructions and 4.29e9.
					int trips = operands[0];
					if (trips > OcslWire.MAX_LOOP_TRIPS) {
						throw new CodecException("FOR at op " + i + " declares " + trips
								+ " iterations, over the structural bound of "
								+ OcslWire.MAX_LOOP_TRIPS);
					}
					loopDepth++;
					if (loopDepth > OcslWire.MAX_LOOP_DEPTH) {
						throw new CodecException("Loop nesting at op " + i + " exceeds depth "
								+ OcslWire.MAX_LOOP_DEPTH);
					}
				} else if (opcode == OcslWire.OP_ENDFOR) {
					if (loopDepth == 0) {
						throw new CodecException("ENDFOR at op " + i + " closes no loop");
					}
					loopDepth--;
				}
				ops.add(new IrOp(opcode, dst, operands));
			}
			if (loopDepth != 0) {
				throw new CodecException(loopDepth + " loop(s) left open at the end of the program");
			}

			int nameCount = in.readUnsignedByte();
			if (nameCount > OcslWire.MAX_NAMES) {
				throw new CodecException("Name table of " + nameCount + " exceeds the cap of "
						+ OcslWire.MAX_NAMES);
			}
			List<String> names = new ArrayList<String>(nameCount);
			for (int i = 0; i < nameCount; i++) {
				int len = in.readUnsignedByte();
				if (len > OcslWire.MAX_NAME_LENGTH) {
					throw new CodecException("Name " + i + " is " + len + " bytes, over the cap of "
							+ OcslWire.MAX_NAME_LENGTH);
				}
				byte[] raw = new byte[len];
				in.readFully(raw);
				String name = new String(raw, UTF8);
				checkNameCharset(i, name);
				names.add(name);
			}

			short guard = in.readShort();
			if (guard != OcslWire.TRAILING_GUARD) {
				throw new CodecException("Trailing guard is 0x" + Integer.toHexString(guard & 0xFFFF)
						+ ", not 0x" + Integer.toHexString(OcslWire.TRAILING_GUARD));
			}
			if (in.read() != -1) {
				// Trailing data is refused rather than ignored: a blob that decodes AND has bytes
				// left over is two different programs to two different readers.
				throw new CodecException("Trailing data after the program");
			}

			return new IrProgram(stage, constants, ops, names, declaredRegisters);
		} catch (EOFException e) {
			throw new CodecException("Program blob is truncated", e);
		} catch (IOException e) {
			throw new CodecException("Program blob could not be read", e);
		}
	}

	private static void checkOperand(OcslWire.Shape shape, int opIndex, int slot, int raw,
			int declaredRegisters, int constantCount, int loopDepth) throws CodecException {
		int kind = shape.operandKinds[slot];
		switch (kind) {
			case OcslWire.KIND_VALUE: {
				boolean isConst = (raw & OcslWire.OPERAND_CONST_FLAG) != 0;
				int index = raw & OcslWire.OPERAND_INDEX_MASK;
				int limit = isConst ? constantCount : declaredRegisters;
				if (index >= limit) {
					throw new CodecException(shape.name + " at op " + opIndex + " reads "
							+ (isConst ? "constant " : "register ") + index + ", outside the "
							+ (isConst ? "pool of " : "declared ") + limit);
				}
				break;
			}
			case OcslWire.KIND_PROPERTY:
				// The per-surface property table is the validator's business (it knows the stage's
				// rows); structurally the id just has to fit the u8 namespace A1 pinned.
				if (raw > 0xFF) {
					throw new CodecException("Property id " + raw + " at op " + opIndex
							+ " is outside the u8 namespace");
				}
				break;
			case OcslWire.KIND_SLOT:
				if (raw > 0xFF) {
					throw new CodecException("Slot " + raw + " at op " + opIndex + " is out of range");
				}
				break;
			case OcslWire.KIND_SWIZZLE: {
				int len = OcslWire.swizzleLength(raw);
				if ((raw & ~0x3FF) != 0) {
					throw new CodecException("Swizzle mask 0x" + Integer.toHexString(raw) + " at op "
							+ opIndex + " has bits set outside the mask");
				}
				// Components beyond the declared length must be zero, or two encodings of one
				// swizzle exist and the content hash forks on a difference nothing can observe.
				for (int c = len; c < 4; c++) {
					if (OcslWire.swizzleComponent(raw, c) != 0) {
						throw new CodecException("Swizzle at op " + opIndex + " sets component "
								+ c + " beyond its length " + len + "; canonical form requires zero");
					}
				}
				break;
			}
			case OcslWire.KIND_IMMEDIATE:
				if (shape.name.equals("ITOF")) {
					// A3: the operand is a loop-DEPTH selector, so it must name a loop that is
					// actually open here. Depth 0 is the innermost enclosing loop.
					if (loopDepth == 0) {
						throw new CodecException("ITOF at op " + opIndex + " is outside any loop");
					}
					if (raw >= loopDepth) {
						throw new CodecException("ITOF at op " + opIndex + " selects loop depth "
								+ raw + " but only " + loopDepth + " loop(s) are open");
					}
				}
				break;
			default:
				throw new CodecException("Unknown operand kind " + kind + " at op " + opIndex);
		}
	}

	/**
	 * The name table's charset cap. Names never reach emitted shader text — codegen uses
	 * positional names — but they DO reach a persisted namespace and Lua-facing errors, so the
	 * charset is pinned rather than trusted.
	 */
	private static void checkNameCharset(int i, String name) throws CodecException {
		if (name.isEmpty()) {
			throw new CodecException("Name " + i + " is empty");
		}
		for (int c = 0; c < name.length(); c++) {
			char ch = name.charAt(c);
			boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
					|| (ch >= '0' && ch <= '9') || ch == '_';
			if (!ok) {
				throw new CodecException("Name " + i + " (\"" + name + "\") has a character outside"
						+ " [A-Za-z0-9_] at position " + c);
			}
		}
		char first = name.charAt(0);
		if (first >= '0' && first <= '9') {
			throw new CodecException("Name " + i + " (\"" + name + "\") starts with a digit");
		}
	}
}
