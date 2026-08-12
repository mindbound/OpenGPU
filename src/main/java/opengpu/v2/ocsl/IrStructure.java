package opengpu.v2.ocsl;

import java.util.List;

/**
 * THE STRUCTURAL RULES, in one place, so that the three gates cannot enforce three different
 * subsets of them.
 *
 * This class exists because of a defect CLASS rather than a defect. `decode()` checked pool widths,
 * pool counts, operand indices, swizzle canonical form, loop balance and the name charset;
 * `validate()` and `encode()` each checked some other subset. Every gap that opened was the same
 * shape and each was found separately:
 *
 * <ul>
 * <li>{@code validate()} bounded an op's WRITE register and not its constant-pool READ index, so
 *     an operand naming constant 99 of a 2-entry pool threw ArrayIndexOutOfBoundsException out of
 *     the method whose contract is to refuse with a ValidationException.</li>
 * <li>{@code validate()} scanned the pool for non-finite values and not for WIDTH, so a 0- or
 *     5-wide entry made {@link OcslType#ofWidth} return null — dereferenced at seven sites for an
 *     NPE, and worse, silently treated as "contributes no shape" at three others, so the program
 *     was ACCEPTED and then crashed or lied inside the VM. A 5-wide `dot` returned 12.0 where the
 *     honest answer is 26.0, out of a program the gate had certified.</li>
 * <li>{@code encode()} range-checked op operands after a truncation bug was found there, and left
 *     the four header counts writing through {@code writeShort} unchecked — so the identical
 *     "one program in memory, a different one on the wire" divergence survived on
 *     declaredRegisters.</li>
 * <li>{@code encode()} refused non-finite constants after that divergence was found, and still
 *     emitted blobs that {@code decode()} rejects for eleven other reasons — a non-canonical
 *     swizzle, a uniform named "my name", a 1025-entry pool, a reserved stage. Each one is a
 *     program that runs on its author's client and is unreadable by every peer.</li>
 * </ul>
 *
 * So the rule is now structural rather than remembered: <b>every gate calls this.</b>
 * {@link IrValidator} calls it before type inference, {@link IrCodec#encode} calls it before
 * writing a byte, and {@link IrCodec#decode} keeps its own byte-level checks — those run while
 * parsing, catch a malformed blob before it is turned into objects at all, and are deliberately
 * NOT replaced by this. Decode is defence in depth; this is the contract.
 *
 * WHAT DOES NOT BELONG HERE: anything needing type inference. Register types, operand shapes,
 * broadcast rules, the op and fetch caps, the frame layout and the property table are
 * {@link IrValidator}'s, because they are facts about what a program MEANS. This class only asks
 * whether the program is a well-formed sequence of instructions at all.
 */
public final class IrStructure {
	private IrStructure() {}

	/**
	 * Thrown as itself so each caller can re-wrap it in the exception ITS contract promises —
	 * a ValidationException from the validator, a CodecException from the codec. A shared checker
	 * that threw its own type would have forced one of the two to break its contract.
	 */
	public static final class StructureException extends Exception {
		private static final long serialVersionUID = 1L;
		/** Index of the offending op, or -1 for a program-level problem. */
		public final int opIndex;

		StructureException(int opIndex, String message) {
			super(message);
			this.opIndex = opIndex;
		}
	}

	public static void check(IrProgram program) throws StructureException {
		checkStage(program.stage);
		checkPool(program);
		checkRegisterCount(program);
		checkNames(program);
		checkOps(program);
	}

	private static void checkStage(byte stage) throws StructureException {
		if (!OcslWire.isKnownStage(stage)) {
			throw new StructureException(-1, "Stage " + (stage & 0xFF) + " is not a stage this"
					+ " build knows");
		}
		// AND the reserved ones, which this class's own javadoc already listed among the things
		// encode() emitted and decode() then refused -- "a non-canonical swizzle, a uniform named
		// `my name`, a 1025-entry pool, A RESERVED STAGE". Every other item on that list was
		// implemented here; this one was not, so `encode()` happily produced a 36-byte animator
		// blob that no decoder would take back. Reserved-but-known is exactly the state the
		// animator and compute surfaces are in: their ids are frozen so nothing else claims them,
		// and no program on them may exist yet.
		if (stage == OcslWire.STAGE_ANIMATOR || stage == OcslWire.STAGE_COMPUTE) {
			throw new StructureException(-1, "Stage " + (stage & 0xFF) + " ("
					+ (stage == OcslWire.STAGE_ANIMATOR ? "animator" : "compute")
					+ ") is RESERVED: its register and property ids are frozen so nothing else"
					+ " takes them, but no program on this surface may be built, encoded or run"
					+ " yet. Opening it is a deliberate act, not a side effect");
		}
	}

	private static void checkPool(IrProgram program) throws StructureException {
		int count = program.constantCount();
		if (count > OcslWire.MAX_CONSTANTS) {
			throw new StructureException(-1, "Constant pool of " + count + " exceeds the cap of "
					+ OcslWire.MAX_CONSTANTS);
		}
		for (int i = 0; i < count; i++) {
			int width = program.constantWidth(i);
			if (width < 1 || width > 4) {
				// Width IS the type tag, so a width outside 1..4 has no type. Left unchecked it
				// became a null OcslType that some rules dereferenced and others silently read as
				// "no shape", which is how a malformed pool got a program ACCEPTED.
				throw new StructureException(-1, "Constant " + i + " has " + width
						+ " components; 1..4 only");
			}
			for (int c = 0; c < width; c++) {
				float v = program.constantComponent(i, c);
				if (!OcslIngress.accepts(v)) {
					// The pool is an INGRESS: SPLAT, SELECT, SWZ and the constructors copy a
					// constant into the frame and compute nothing that A4's catch-all could apply
					// to, so a non-finite here would reach OUT intact. Refusal rather than
					// substitution because authored content HAS an error path -- OcslIngress states
					// which ingress takes which remedy, and that it is the same at every stage.
					throw new StructureException(-1, "Constant " + i + " component " + c
							+ " is non-finite (" + v + "); the pool carries finite values only");
				}
			}
		}
	}

	private static void checkRegisterCount(IrProgram program) throws StructureException {
		int regs = program.declaredRegisters;
		if (regs < 0) {
			// One-sided bounds are this package's recurring bug. `new OcslType[regCount]` on a
			// negative count threw NegativeArraySizeException out of validate(); the wire cannot
			// express it because it reads an unsigned short, and a builder can.
			throw new StructureException(-1, "Declares " + regs + " registers");
		}
		if (regs > SurfaceTable.MAX_REGISTERS) {
			throw new StructureException(-1, "Declares " + regs + " registers, over the cap of "
					+ SurfaceTable.MAX_REGISTERS);
		}
	}

	private static void checkNames(IrProgram program) throws StructureException {
		List<String> names = program.names();
		if (names.size() > OcslWire.MAX_NAMES) {
			throw new StructureException(-1, "Name table of " + names.size()
					+ " exceeds the cap of " + OcslWire.MAX_NAMES);
		}
		for (int i = 0; i < names.size(); i++) {
			checkName(i, names.get(i));
		}
	}

	/**
	 * One uniform name against the wire's rule.
	 *
	 * Public so the BUILDER can apply it at the point of declaration. It used to be reachable only
	 * through a finished program, so an author writing {@code uniform("my name")} got no complaint
	 * until build time and then got one phrased as an internal defect. A rule the author can break
	 * should be checked where they break it.
	 */
	public static void checkName(int i, String name) throws StructureException {
		if (name == null) {
			throw new StructureException(-1, "Name " + i + " is null");
		}
		if (name.isEmpty()) {
			throw new StructureException(-1, "Name " + i + " is empty");
		}
		if (name.length() > OcslWire.MAX_NAME_LENGTH) {
			throw new StructureException(-1, "Name " + i + " is " + name.length()
					+ " characters, over the cap of " + OcslWire.MAX_NAME_LENGTH);
		}
		char first = name.charAt(0);
		if (first >= '0' && first <= '9') {
			throw new StructureException(-1, "Name " + i + " starts with a digit");
		}
		for (int c = 0; c < name.length(); c++) {
			char ch = name.charAt(c);
			boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
					|| (ch >= '0' && ch <= '9') || ch == '_';
			if (!ok) {
				throw new StructureException(-1, "Name " + i + " has a character outside"
						+ " [A-Za-z0-9_] at position " + c);
			}
		}
	}

	private static void checkOps(IrProgram program) throws StructureException {
		List<IrOp> ops = program.ops();
		if (ops.size() > OcslWire.MAX_OPS) {
			throw new StructureException(-1, "Op count " + ops.size() + " exceeds the cap of "
					+ OcslWire.MAX_OPS);
		}
		int loopDepth = 0;
		for (int i = 0; i < ops.size(); i++) {
			IrOp op = ops.get(i);
			if (op == null) {
				throw new StructureException(i, "Op is null");
			}
			OcslWire.Shape shape = OcslWire.shapeOf(op.opcode);
			if (shape == null) {
				throw new StructureException(i, "Opcode " + (op.opcode & 0xFF) + " has no shape;"
						+ " it is not an instruction this build knows");
			}
			if (op.operandCount() != shape.operandCount()) {
				throw new StructureException(i, shape.name + " takes " + shape.operandCount()
						+ " operand(s), got " + op.operandCount());
			}
			if (shape.hasDst != (op.dst >= 0)) {
				throw new StructureException(i, shape.name + (shape.hasDst
						? " writes a register but names none"
						: " writes no register but names " + op.dst));
			}
			if (shape.hasDst && op.dst >= program.declaredRegisters) {
				throw new StructureException(i, shape.name + " writes register " + op.dst
						+ ", outside the declared " + program.declaredRegisters);
			}
			checkOperands(program, op, shape, i, loopDepth);

			if (op.opcode == OcslWire.OP_FOR) {
				loopDepth++;
				if (loopDepth > OcslWire.MAX_LOOP_DEPTH) {
					throw new StructureException(i, "Loop nesting exceeds depth "
							+ OcslWire.MAX_LOOP_DEPTH);
				}
			} else if (op.opcode == OcslWire.OP_ENDFOR) {
				if (loopDepth == 0) {
					throw new StructureException(i, "ENDFOR closes no loop");
				}
				loopDepth--;
			}
		}
		if (loopDepth != 0) {
			throw new StructureException(-1, loopDepth
					+ " loop(s) left open at the end of the program");
		}
	}

	private static void checkOperands(IrProgram program, IrOp op, OcslWire.Shape shape, int i,
			int loopDepth) throws StructureException {
		for (int slot = 0; slot < shape.operandCount(); slot++) {
			int raw = op.operand(slot);
			switch (shape.operandKinds[slot]) {
				case OcslWire.KIND_VALUE:
					// NOTE the negative case: -1 has the sign bit set, so OPERAND_CONST_FLAG reads
					// as present and index() yields 32767. A raw operand is therefore checked as a
					// whole before its tag is believed.
					if (raw < 0 || raw > 0xFFFF) {
						throw new StructureException(i, shape.name + " operand " + slot + " is "
								+ raw + ", outside the unsigned 16 bits the wire gives it");
					}
					if (op.isConstant(slot)) {
						int c = op.index(slot);
						if (c >= program.constantCount()) {
							throw new StructureException(i, shape.name + " reads constant " + c
									+ ", outside the pool of " + program.constantCount());
						}
					} else {
						int reg = op.index(slot);
						if (reg >= program.declaredRegisters) {
							throw new StructureException(i, shape.name + " reads register " + reg
									+ ", outside the declared " + program.declaredRegisters);
						}
					}
					break;
				case OcslWire.KIND_SWIZZLE: {
					if (raw < 0 || raw > 0xFFFF) {
						throw new StructureException(i, "Swizzle at op " + i + " is " + raw
								+ ", outside the unsigned 16 bits the wire gives it");
					}
					// 2 bits per component low-to-high, length in bits 8-9: 0x3FF is every bit the
					// encoding uses. Spelled as the decoder spells it, deliberately.
					if ((raw & ~0x3FF) != 0) {
						throw new StructureException(i, "Swizzle mask 0x" + Integer.toHexString(raw)
								+ " at op " + i + " has bits set outside the mask");
					}
					int len = OcslWire.swizzleLength(raw);
					// Canonical form IS the content hash. Two spellings of `.x` that differ only in
					// bits nothing can observe would fork the compile-cache key for one program.
					for (int c = len; c < 4; c++) {
						if (OcslWire.swizzleComponent(raw, c) != 0) {
							throw new StructureException(i, "Swizzle at op " + i + " sets component "
									+ c + " beyond its length " + len
									+ "; canonical form requires zero");
						}
					}
					break;
				}
				case OcslWire.KIND_IMMEDIATE:
					if (raw < 0 || raw > 0xFFFF) {
						throw new StructureException(i, shape.name + " immediate is " + raw
								+ ", outside the unsigned 16 bits the wire gives it");
					}
					if (op.opcode == OcslWire.OP_FOR && raw > OcslWire.MAX_LOOP_TRIPS) {
						throw new StructureException(i, "FOR at op " + i + " declares " + raw
								+ " iterations, over the structural bound of "
								+ OcslWire.MAX_LOOP_TRIPS);
					}
					if (op.opcode == OcslWire.OP_ITOF) {
						// A3's loop-DEPTH selector. The VM indexes its counter stack with
						// `depth - 1 - selector`, so an out-of-range value is an out-of-range array
						// index on the render thread.
						if (loopDepth == 0) {
							throw new StructureException(i, "ITOF at op " + i
									+ " is outside any loop");
						}
						if (raw >= loopDepth) {
							throw new StructureException(i, "ITOF at op " + i
									+ " selects loop depth " + raw + " but only " + loopDepth
									+ " loop(s) are open");
						}
					}
					break;
				case OcslWire.KIND_PROPERTY:
				case OcslWire.KIND_SLOT:
					// Which properties and which sampler slots a STAGE has is the validator's, so
					// only the wire's own range is asserted here.
					if (raw < 0 || raw > 0xFFFF) {
						throw new StructureException(i, shape.name + " operand " + slot + " is "
								+ raw + ", outside the unsigned 16 bits the wire gives it");
					}
					break;
				default:
					throw new StructureException(i, "Unknown operand kind "
							+ shape.operandKinds[slot]);
			}
		}
	}
}
