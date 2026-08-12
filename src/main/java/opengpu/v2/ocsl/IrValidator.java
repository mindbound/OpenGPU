package opengpu.v2.ocsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides whether a well-formed blob is an ACCEPTABLE program, and computes the frame layout the
 * CPU VM will preallocate against.
 *
 * The codec already established structure. This layer adds everything that needs to know what the
 * values mean: types, stage-applicable reads, output completeness, read-before-write, the semantic
 * caps, and A5's register→frame-offset mapping.
 *
 * BROADCAST IS THE SPINE OF THE TYPE RULES. A float operand stands in for a vector one wherever
 * the op is COMPONENT-WISE — arithmetic plus {@code MOD MIN MAX POW ATAN2 STEP CLAMP MIX
 * SMOOTHSTEP} — in any operand position, and the result takes the vector width. It does NOT
 * broadcast for the reducing and fixed-shape ops ({@code DOT CROSS LENGTH DISTANCE NORMALIZE}),
 * where a scalar operand has no reading; those stay strict. Two different vector widths in one op
 * are always refused: this is broadcast, not coercion. {@code SPLAT} survives for the places
 * broadcast cannot reach — constructor component slots, and building a vector from a float uniform.
 *
 * That rule replaced amendment 4's shape-uniform zoo on 2026-08-12. The amendment's own text had
 * conceded the choice was free ("GLSL 1.20 natively accepts mix(genType,genType,float), so the
 * splat is an IR-design decision, not a GLSL necessity"), and transcribing the four acceptance
 * programs found three of them using the scalar form anyway. Under the strict reading two of the
 * four committed op counts moved; under this one, only P1's — the program whose splat the record
 * already labelled an amendment-4 artifact.
 *
 * The caps here are the validator's and are RAISEABLE under the monotonicity rule: a program
 * rejected at build time never became a saved blob, and a cheaper program never fails
 * re-validation. Tightening one is a format-level break. That asymmetry is why they live here and
 * not in the codec, whose rejections are format identity.
 */
public final class IrValidator {

	/** Post-unroll structural ops. Conservative on purpose: raiseable, never tightenable. */
	public static final int MAX_STRUCTURAL_OPS = 256;
	/** Texture fetches, post-unroll, user tier. */
	public static final int MAX_FETCHES = 16;
	/** Uniform COMPONENTS, against the GL 2.1 minimum of 64 fragment components. */
	public static final int MAX_UNIFORM_COMPONENTS = 64;
	/** Product of all loop trip counts. Equal to the op cap and therefore non-binding today. */
	public static final int MAX_UNROLL_PRODUCT = 256;

	private IrValidator() {}

	/**
	 * A validated program: the types it inferred, and the frame layout the VM preallocates.
	 *
	 * The layout is A5 in code — registers packed BY DECLARED WIDTH IN FIRST-WRITE ORDER, computed
	 * identically by whoever builds a blob and whoever validates one. Pinning it mattered because
	 * A3 had already frozen "frame width is derivable from the blob alone" without saying HOW, and
	 * the first interpreter written would otherwise have decided it by accident.
	 */
	public static final class Validated {
		private final IrProgram program;
		private final OcslType[] registerTypes;
		private final int[] frameOffsets;
		public final int frameWidth;
		public final long structuralOps;
		public final int fetches;
		public final int uniformComponents;

		Validated(IrProgram program, OcslType[] registerTypes, int[] frameOffsets, int frameWidth,
				long structuralOps, int fetches, int uniformComponents) {
			this.program = program;
			this.registerTypes = registerTypes;
			this.frameOffsets = frameOffsets;
			this.frameWidth = frameWidth;
			this.structuralOps = structuralOps;
			this.fetches = fetches;
			this.uniformComponents = uniformComponents;
		}

		public IrProgram program() {
			return program;
		}

		/** The inferred type of a register, or null if the program never gives it one. */
		public OcslType typeOf(int register) {
			return register < registerTypes.length ? registerTypes[register] : null;
		}

		/** Where a register's components start in the VM's flat frame, or -1 if it has none. */
		public int frameOffset(int register) {
			return register < frameOffsets.length ? frameOffsets[register] : -1;
		}
	}

	public static Validated validate(IrProgram program) throws ValidationException {
		byte stage = program.stage;
		if (SurfaceTable.requiredProperties(stage).length == 0) {
			// A stage with no property table cannot produce an output, so "it validated" would
			// mean nothing. The vertex stage is the live case: reserved for Stage C, refused by no
			// tripwire, and it would otherwise pass as an ACCEPTABLE program that can never write
			// anything.
			throw new ValidationException(-1, "stage " + (stage & 0xFF)
					+ " has no property table, so no program on it can produce an output;"
					+ " it is reserved and not yet implemented");
		}
		int regCount = program.declaredRegisters;
		if (regCount > SurfaceTable.MAX_REGISTERS) {
			throw new ValidationException(-1, "declares " + regCount + " registers, over the cap of "
					+ SurfaceTable.MAX_REGISTERS);
		}
		OcslType[] types = new OcslType[regCount];

		// Built-ins and uniforms are BOUND, not written. Their types come from the surface table
		// and from the program's own uniform declarations, and they are the only registers a
		// program may read without having written them first.
		for (int r = 0; r < Math.min(regCount, SurfaceTable.BUILTIN_LIMIT); r++) {
			types[r] = SurfaceTable.builtinType(stage, r);
		}
		int uniformCount = program.names().size();
		if (uniformCount > SurfaceTable.MAX_UNIFORMS) {
			throw new ValidationException(-1, "declares " + uniformCount + " uniforms, over the "
					+ SurfaceTable.MAX_UNIFORMS + " the id block reserves");
		}
		// v1 uniforms are float-typed at declaration; the wire carries no per-uniform type yet, so
		// a widened uniform is a format change and not a validator relaxation. Stated rather than
		// assumed, because "typed uniforms" is committed design and this is the narrower thing
		// actually implemented.
		for (int i = 0; i < uniformCount; i++) {
			int reg = SurfaceTable.UNIFORM_BASE + i;
			if (reg < regCount) {
				types[reg] = OcslType.FLOAT;
			}
		}
		int uniformComponents = uniformCount;
		if (uniformComponents > MAX_UNIFORM_COMPONENTS) {
			throw new ValidationException(-1, "uses " + uniformComponents
					+ " uniform components, over the cap of " + MAX_UNIFORM_COMPONENTS);
		}

		boolean[] written = new boolean[regCount];
		List<Integer> writeOrder = new ArrayList<Integer>();
		Map<Integer, Integer> outsByProperty = new LinkedHashMap<Integer, Integer>();
		int fetches = 0;
		long unrollProduct = 1;
		long multiplier = 1;
		List<Integer> tripStack = new ArrayList<Integer>();

		List<IrOp> ops = program.ops();
		for (int i = 0; i < ops.size(); i++) {
			IrOp op = ops.get(i);
			OcslWire.Shape shape = OcslWire.shapeOf(op.opcode);

			if (op.opcode == OcslWire.OP_FOR) {
				int trips = op.operand(0);
				if (trips < 1) {
					throw new ValidationException(i, "FOR declares " + trips
							+ " iterations; a loop that never runs has no meaning here and its"
							+ " accumulator would be its init value, which the program can write"
							+ " directly");
				}
				multiplier *= trips;
				// The cap is on the deepest NESTING PATH, not on every loop in the program
				// multiplied together. `multiplier` is already the per-nest product and ENDFOR
				// already unwinds it; an accumulating total never unwinds, so two SEQUENTIAL
				// loops of 20 would have read as 400 and been refused — while the frozen entry
				// calls this cap "equal to the op cap, therefore NON-BINDING" on the argument
				// that every innermost iteration charges at least one op. That argument is about
				// a nesting path; a running total makes the cap bind where the design says it
				// cannot.
				unrollProduct = Math.max(unrollProduct, multiplier);
				if (unrollProduct > MAX_UNROLL_PRODUCT) {
					throw new ValidationException(i, "unroll product " + unrollProduct
							+ " exceeds the cap of " + MAX_UNROLL_PRODUCT);
				}
				tripStack.add(Integer.valueOf(trips));
				// The accumulator's type is its init operand's, and it counts as written from here.
				OcslType init = readType(program, types, written, op, 1, i, stage);
				assign(types, written, writeOrder, op.dst, init, i);
				continue;
			}
			if (op.opcode == OcslWire.OP_ENDFOR) {
				tripStack.remove(tripStack.size() - 1);
				multiplier = 1;
				for (Integer t : tripStack) {
					multiplier *= t.intValue();
				}
				continue;
			}

			OcslType result = inferAndCheck(program, types, written, op, i, stage);

			if (op.opcode == OcslWire.OP_SAMPLE) {
				int slot = op.operand(0);
				if (slot >= SurfaceTable.MAX_SLOTS) {
					throw new ValidationException(i, "slot " + slot + " is outside the "
							+ SurfaceTable.MAX_SLOTS + " this build binds");
				}
				if (slot == SurfaceTable.SLOT_INPUT && !SurfaceTable.hasInputSampler(stage)) {
					throw new ValidationException(i, "slot 0 is the built-in `input` sampler, which"
							+ " only the effect and post-chain surfaces have");
				}
				fetches += multiplier;
				if (fetches > MAX_FETCHES) {
					throw new ValidationException(i, "program performs " + fetches
							+ " fetches post-unroll, over the cap of " + MAX_FETCHES);
				}
			}

			if (op.opcode == OcslWire.OP_OUT) {
				int property = op.operand(0);
				OcslType expected = SurfaceTable.propertyType(stage, property);
				if (expected == null) {
					throw new ValidationException(i, "stage " + (stage & 0xFF)
							+ " has no property " + property);
				}
				OcslType actual = readType(program, types, written, op, 1, i, stage);
				if (actual != expected) {
					throw new ValidationException(i, "OUT " + SurfaceTable.propertyName(property)
							+ " expects " + expected.display() + ", got " + actual.display());
				}
				if (!tripStack.isEmpty()) {
					throw new ValidationException(i, "OUT inside a loop would write its property"
							+ " once per iteration; one writer per property per frame");
				}
				Integer previous = outsByProperty.put(Integer.valueOf(property), Integer.valueOf(i));
				if (previous != null) {
					throw new ValidationException(i, "property "
							+ SurfaceTable.propertyName(property) + " already written at op "
							+ previous);
				}
				continue;
			}

			if (shape.hasDst) {
				assign(types, written, writeOrder, op.dst, result, i);
			}
		}

		for (int required : SurfaceTable.requiredProperties(stage)) {
			if (!outsByProperty.containsKey(Integer.valueOf(required))) {
				throw new ValidationException(-1, "program never writes "
						+ SurfaceTable.propertyName(required) + "; every program on this surface"
						+ " must produce it");
			}
		}

		long structural = program.structuralCount();
		if (structural > MAX_STRUCTURAL_OPS) {
			throw new ValidationException(-1, "program charges " + structural
					+ " structural ops, over the cap of " + MAX_STRUCTURAL_OPS);
		}

		// A5: pack the frame by declared width. Built-ins and uniforms first, at fixed positions
		// derived from the surface table, then working registers IN FIRST-WRITE ORDER.
		int[] offsets = new int[regCount];
		java.util.Arrays.fill(offsets, -1);
		int cursor = 0;
		for (int r = 0; r < regCount; r++) {
			if (r < SurfaceTable.WORKING_BASE && types[r] != null) {
				offsets[r] = cursor;
				cursor += types[r].width;
			}
		}
		for (Integer reg : writeOrder) {
			int r = reg.intValue();
			if (offsets[r] < 0 && types[r] != null) {
				offsets[r] = cursor;
				cursor += types[r].width;
			}
		}

		if (cursor > SurfaceTable.MAX_FRAME_WIDTH) {
			throw new ValidationException(-1, "lays out a frame of " + cursor
					+ " floats, over the cap of " + SurfaceTable.MAX_FRAME_WIDTH);
		}
		return new Validated(program, types, offsets, cursor, structural, fetches,
				uniformComponents);
	}

	// ---------------------------------------------------------------- type rules

	private static void assign(OcslType[] types, boolean[] written, List<Integer> writeOrder,
			int reg, OcslType type, int opIndex) throws ValidationException {
		if (reg < SurfaceTable.WORKING_BASE) {
			throw new ValidationException(opIndex, "register " + reg + " is "
					+ (reg < SurfaceTable.UNIFORM_BASE ? "a built-in input" : "a uniform")
					+ " and cannot be written; working registers start at "
					+ SurfaceTable.WORKING_BASE);
		}
		if (types[reg] != null && types[reg] != type) {
			// A register keeps one type for the program's life. Re-typing would make the frame
			// width depend on execution order, which A5's static layout cannot express.
			throw new ValidationException(opIndex, "register " + reg + " was " + types[reg].display()
					+ " and this writes " + type.display());
		}
		if (!written[reg]) {
			writeOrder.add(Integer.valueOf(reg));
		}
		types[reg] = type;
		written[reg] = true;
	}

	private static OcslType readType(IrProgram program, OcslType[] types, boolean[] written,
			IrOp op, int slot, int opIndex, byte stage) throws ValidationException {
		if (op.isConstant(slot)) {
			// Constants are typed by their width, so a pooled vec4 is a vec4 operand -- which is
			// what lets a fold be seeded with vec4(0) at zero op cost.
			return program.constantType(op.index(slot));
		}
		int reg = op.index(slot);
		OcslType type = reg < types.length ? types[reg] : null;
		if (type == null) {
			if (reg < SurfaceTable.BUILTIN_LIMIT) {
				throw new ValidationException(opIndex, "reads built-in `"
						+ SurfaceTable.builtinName(reg) + "` (register " + reg
						+ "), which stage " + (stage & 0xFF) + " does not have");
			}
			if (reg < SurfaceTable.WORKING_BASE) {
				throw new ValidationException(opIndex, "reads uniform register " + reg
						+ ", which this program does not declare");
			}
			throw new ValidationException(opIndex, "reads register " + reg
					+ " before anything writes it");
		}
		if (reg >= SurfaceTable.WORKING_BASE && !written[reg]) {
			throw new ValidationException(opIndex, "reads register " + reg
					+ " before anything writes it");
		}
		return type;
	}

	private static OcslType inferAndCheck(IrProgram program, OcslType[] types, boolean[] written,
			IrOp op, int i, byte stage) throws ValidationException {
		byte code = op.opcode;

		switch (code) {
			// --- COMPONENT-WISE OPS: a float operand may stand in for a vector one, broadcast.
			// This supersedes amendment 4's "IR function ops are shape-uniform; the builder
			// inserts explicit splat constructor ops for scalar broadcast" (re-opened 2026-08-12,
			// user decision). That amendment's own text conceded the point -- "GLSL 1.20 natively
			// accepts mix(genType,genType,float), SO THE SPLAT IS AN IR-DESIGN DECISION, NOT A
			// GLSL NECESSITY" -- and it chose the restrictive side. Three of the four
			// hand-translated acceptance programs then reached for the scalar form anyway, which
			// is evidence about what a builder naturally emits.
			//
			// One rule replaces a split: broadcast is legal wherever the op is component-wise,
			// which is exactly where it means something. The reducing and fixed-shape ops below
			// are excluded because a broadcast operand has no sensible reading there.
			//
			// CANONICAL-FORM OBLIGATION, and it is the price of this: the builder must NEVER emit
			// a SPLAT where broadcast is available. Both spellings compute the same thing and
			// charge differently, so allowing the builder to choose would fork the content hash --
			// which is the compile-cache key -- on a difference no program can observe.
			case OcslWire.OP_ADD: case OcslWire.OP_SUB: case OcslWire.OP_MUL:
			case OcslWire.OP_DIV: case OcslWire.OP_MOD: case OcslWire.OP_MIN:
			case OcslWire.OP_MAX: case OcslWire.OP_STEP: case OcslWire.OP_POW:
			case OcslWire.OP_ATAN2:
				return componentWise(program, types, written, op, i, stage, 2);
			case OcslWire.OP_CLAMP: case OcslWire.OP_MIX: case OcslWire.OP_SMOOTHSTEP:
				return componentWise(program, types, written, op, i, stage, 3);
			// Unary ops have no broadcast question -- one operand decides the shape.
			case OcslWire.OP_NEG: case OcslWire.OP_ABS: case OcslWire.OP_FLOOR:
			case OcslWire.OP_FRACT: case OcslWire.OP_EXP: case OcslWire.OP_LOG:
			case OcslWire.OP_SQRT: case OcslWire.OP_SIN: case OcslWire.OP_COS:
			case OcslWire.OP_NORMALIZE:
				return uniform(program, types, written, op, i, stage, 1);

			// --- shape-reducing
			case OcslWire.OP_DOT: case OcslWire.OP_DISTANCE: {
				OcslType t = uniform(program, types, written, op, i, stage, 2);
				return OcslType.FLOAT;
			}
			case OcslWire.OP_LENGTH: {
				uniform(program, types, written, op, i, stage, 1);
				return OcslType.FLOAT;
			}
			case OcslWire.OP_CROSS: {
				OcslType t = uniform(program, types, written, op, i, stage, 2);
				if (t != OcslType.VEC3) {
					throw new ValidationException(i, "cross takes vec3, got " + t.display());
				}
				return OcslType.VEC3;
			}

			// --- shape-changing
			case OcslWire.OP_SWZ: {
				OcslType src = readNumeric(program, types, written, op, 0, i, stage);
				int mask = op.operand(1);
				int len = OcslWire.swizzleLength(mask);
				for (int c = 0; c < len; c++) {
					if (OcslWire.swizzleComponent(mask, c) >= src.width) {
						throw new ValidationException(i, "swizzle reads component "
								+ "xyzw".charAt(OcslWire.swizzleComponent(mask, c)) + " of a "
								+ src.display());
					}
				}
				return OcslType.ofWidth(len);
			}
			case OcslWire.OP_SPLAT: {
				OcslType src = readNumeric(program, types, written, op, 0, i, stage);
				if (src != OcslType.FLOAT) {
					throw new ValidationException(i, "splat takes a float, got " + src.display());
				}
				OcslType out = OcslType.ofWidth(op.operand(1));
				if (out == null || out == OcslType.FLOAT) {
					throw new ValidationException(i, "splat width " + op.operand(1)
							+ " must be 2, 3 or 4");
				}
				return out;
			}
			case OcslWire.OP_CONS2: return components(program, types, written, op, i, stage, 2);
			case OcslWire.OP_CONS3: return components(program, types, written, op, i, stage, 3);
			case OcslWire.OP_CONS4: return components(program, types, written, op, i, stage, 4);
			case OcslWire.OP_CONS3_V2F:
				return composite(program, types, written, op, i, stage, OcslType.VEC2,
						OcslType.FLOAT, OcslType.VEC3);
			case OcslWire.OP_CONS4_V3F:
				return composite(program, types, written, op, i, stage, OcslType.VEC3,
						OcslType.FLOAT, OcslType.VEC4);
			case OcslWire.OP_CONS4_V2V2:
				return composite(program, types, written, op, i, stage, OcslType.VEC2,
						OcslType.VEC2, OcslType.VEC4);

			// --- bool producers and consumers. Scalar-only by the frozen decision: per-component
			// selection is written explicitly as mix(a, b, step(e, x)), which is already in the zoo.
			case OcslWire.OP_LT: case OcslWire.OP_LE: case OcslWire.OP_EQ: {
				for (int s = 0; s < 2; s++) {
					OcslType t = readNumeric(program, types, written, op, s, i, stage);
					if (t != OcslType.FLOAT) {
						throw new ValidationException(i, "comparisons are scalar-only, got "
								+ t.display());
					}
				}
				return OcslType.BOOL;
			}
			case OcslWire.OP_BAND: case OcslWire.OP_BOR: case OcslWire.OP_BNOT: {
				int n = code == OcslWire.OP_BNOT ? 1 : 2;
				for (int s = 0; s < n; s++) {
					OcslType t = readType(program, types, written, op, s, i, stage);
					if (t != OcslType.BOOL) {
						throw new ValidationException(i, "boolean ops take bool, got "
								+ t.display());
					}
				}
				return OcslType.BOOL;
			}
			case OcslWire.OP_SELECT: {
				OcslType cond = readType(program, types, written, op, 0, i, stage);
				if (cond != OcslType.BOOL) {
					throw new ValidationException(i, "select's condition is a bool scalar, got "
							+ cond.display());
				}
				OcslType a = readType(program, types, written, op, 1, i, stage);
				OcslType b = readType(program, types, written, op, 2, i, stage);
				if (a != b) {
					throw new ValidationException(i, "select's arms are " + a.display() + " and "
							+ b.display());
				}
				return a;
			}

			case OcslWire.OP_SAMPLE: {
				OcslType uv = readNumeric(program, types, written, op, 1, i, stage);
				if (uv != OcslType.VEC2) {
					throw new ValidationException(i, "sample takes a vec2 coordinate, got "
							+ uv.display());
				}
				return OcslType.VEC4;
			}
			case OcslWire.OP_ITOF:
				return OcslType.FLOAT;
			case OcslWire.OP_OUT:
				return null; // handled by the caller, which knows the property
			default:
				throw new ValidationException(i, "no type rule for opcode " + (code & 0xFF));
		}
	}

	/**
	 * Component-wise ops: every operand is either a float or THE one vector width, and floats are
	 * broadcast. The result is that vector width, or float when every operand is a float.
	 *
	 * Position-agnostic on purpose. SUB and DIV are not commutative and {@code k - v} is a real
	 * pattern, so a scalar is legal in any slot rather than only on the right. Two vector widths in
	 * one op is still nonsense and still refused — the broadcast allowance is exactly that, not a
	 * general coercion.
	 */
	private static OcslType componentWise(IrProgram program, OcslType[] types, boolean[] written,
			IrOp op, int i, byte stage, int arity) throws ValidationException {
		OcslType vector = null;
		for (int s = 0; s < arity; s++) {
			OcslType t = readNumeric(program, types, written, op, s, i, stage);
			if (t == OcslType.FLOAT) {
				continue;
			}
			if (vector != null && vector != t) {
				throw new ValidationException(i, OcslWire.shapeOf(op.opcode).name
						+ " mixes " + vector.display() + " and " + t.display()
						+ "; a float may broadcast, two vector widths may not");
			}
			vector = t;
		}
		return vector != null ? vector : OcslType.FLOAT;
	}

	private static OcslType uniform(IrProgram program, OcslType[] types, boolean[] written,
			IrOp op, int i, byte stage, int arity) throws ValidationException {
		OcslType first = readNumeric(program, types, written, op, 0, i, stage);
		for (int s = 1; s < arity; s++) {
			OcslType t = readNumeric(program, types, written, op, s, i, stage);
			if (t != first) {
				throw new ValidationException(i, OcslWire.shapeOf(op.opcode).name
						+ " is shape-uniform: operand 0 is " + first.display() + " but operand "
						+ s + " is " + t.display()
						+ ". Scalar broadcast is an explicit SPLAT, never implicit.");
			}
		}
		return first;
	}

	private static OcslType components(IrProgram program, OcslType[] types, boolean[] written,
			IrOp op, int i, byte stage, int n) throws ValidationException {
		for (int s = 0; s < n; s++) {
			OcslType t = readNumeric(program, types, written, op, s, i, stage);
			if (t != OcslType.FLOAT) {
				throw new ValidationException(i, "constructor component " + s + " is "
						+ t.display() + ", expected float");
			}
		}
		return OcslType.ofWidth(n);
	}

	private static OcslType composite(IrProgram program, OcslType[] types, boolean[] written,
			IrOp op, int i, byte stage, OcslType a, OcslType b, OcslType result)
			throws ValidationException {
		OcslType t0 = readNumeric(program, types, written, op, 0, i, stage);
		OcslType t1 = readNumeric(program, types, written, op, 1, i, stage);
		if (t0 != a || t1 != b) {
			throw new ValidationException(i, OcslWire.shapeOf(op.opcode).name + " takes ("
					+ a.display() + ", " + b.display() + "), got (" + t0.display() + ", "
					+ t1.display() + ")");
		}
		return result;
	}

	private static OcslType readNumeric(IrProgram program, OcslType[] types, boolean[] written,
			IrOp op, int slot, int i, byte stage) throws ValidationException {
		OcslType t = readType(program, types, written, op, slot, i, stage);
		if (t == OcslType.BOOL) {
			throw new ValidationException(i, "operand " + slot
					+ " is a bool; bools are conditions only and reach values through select");
		}
		return t;
	}
}
