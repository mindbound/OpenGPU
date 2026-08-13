package opengpu.v2.ocsl;

import java.util.List;

/**
 * The OCSL CPU virtual machine: a flat register machine over a PREALLOCATED primitive
 * {@code float[]} frame, with zero allocation per evaluation.
 *
 * That shape is an implementation requirement, not a preference. Op-count budgets only correspond
 * to real time under it, and a 1.7.10 client cannot afford GC hitching on the render thread — an
 * animator evaluates per node per frame. Everything the machine needs is sized once, at
 * construction, from the validated program: the frame width comes from A5's layout, the loop stack
 * from the blob's nesting depth. {@link #run} allocates nothing.
 *
 * IT INTERPRETS {@code FOR} RATHER THAN UNROLLING IT (A3). An unrolling interpreter would make the
 * frame width a function of trip count and defeat the preallocation; body registers are reused
 * each iteration and the accumulator is written once per iteration. Dynamic op counts stay
 * unrolling-invariant, so this machine and a future unrolled codegen agree about what a program
 * costs.
 *
 * ARITHMETIC LIVES IN {@link OcslMath}, never here. Every domain rule, the {@code strictfp}
 * evaluation, the {@code StrictMath}-only transcendentals and the float32 narrowing are in one
 * place so that the two executors cannot drift apart one op at a time.
 *
 * Instances are NOT thread-safe — the frame is mutable state owned by one evaluator. Build one per
 * thread that needs one; the client's animator budget assumes the render thread.
 */
public final strictfp class OcslVm {

	private final IrProgram program;
	private final IrValidator.Validated validated;
	private final float[] frame;
	private final int[] loopCounter;
	private final int[] loopTrips;
	private final int[] loopBodyStart;

	/**
	 * Scratch lanes past the end of the layout, for the one case that needs a contiguous vector an
	 * operand does not have: a CONSTANT read whole (DOT, CROSS, NORMALIZE, SELECT's arms). Sized
	 * once here so {@link #run} never allocates.
	 *
	 * THREE lanes of four, one per operand slot, because an op can take more than one constant —
	 * {@code DOT k1, k2} with a single shared buffer would have the second write clobber the
	 * first and silently compute {@code dot(k2, k2)}.
	 */
	private static final int SCRATCH_SLOTS = 3;
	private static final int SCRATCH_WIDTH = 4;

	/**
	 * The program's OUT ops, resolved ONCE.
	 *
	 * {@link #output} used to walk the whole op list on every call looking for the property, which
	 * cost an iterator allocation per call — tens of bytes, the exact figure depending on the JDK —
	 * plus an O(n) scan, on a path the design requires to be allocation-free. The test asserts
	 * ZERO rather than any measured number, so it does not depend on which. Note the shape of the
	 * miss: {@link #run} really
	 * was allocation-free, and the increment's own test measured only {@code run()}, so a
	 * per-evaluation allocation sat in the other half of the same evaluation.
	 */
	private final int[] outProperties;
	private final IrOp[] outOps;
	private final int[] outWidths;
	/** Per written property: was it written with {@code OUT_ABS} rather than {@code OUT}? */
	private final boolean[] outAbsolute;

	/**
	 * Textures bound per sampler slot. Null means unbound, which reads 0 rather than failing (S5).
	 *
	 * Sized once, like everything else here, so binding and sampling stay allocation-free.
	 */
	private final OcslTexture[] textures = new OcslTexture[SurfaceTable.MAX_SLOTS];

	public OcslVm(IrValidator.Validated validated) {
		this.validated = validated;
		this.program = validated.program();
		this.frame = new float[validated.frameWidth + SCRATCH_SLOTS * SCRATCH_WIDTH];
		int depth = maxLoopDepth(program.ops());
		this.loopCounter = new int[depth];
		this.loopTrips = new int[depth];
		this.loopBodyStart = new int[depth];

		// `timePeriod` is seeded from the frozen constant, not bound by the host, because it is not
		// host state — it is a value of the FORMAT (ANIM-5). Every other built-in describes this
		// frame or this node and only the host knows it; P is the same number in every scene, on
		// every client, for the life of the format version. Taking it from the host would create a
		// binding site that could be forgotten, and a forgotten one reads 0.0 — which a program
		// dividing by P would then turn into a silent 0 under the safe-divide rule rather than an
		// error. Seeding here means the register cannot be wrong.
		int periodSlot = validated.frameOffset(SurfaceTable.REG_TIME_PERIOD);
		if (periodSlot >= 0) {
			frame[periodSlot] = OcslTime.PERIOD_SECONDS;
		}

		List<IrOp> ops = program.ops();
		int outs = 0;
		for (int i = 0; i < ops.size(); i++) {
			if (OcslWire.isOut(ops.get(i).opcode)) {
				outs++;
			}
		}
		this.outProperties = new int[outs];
		this.outOps = new IrOp[outs];
		this.outWidths = new int[outs];
		this.outAbsolute = new boolean[outs];
		int n = 0;
		for (int i = 0; i < ops.size(); i++) {
			IrOp op = ops.get(i);
			if (OcslWire.isOut(op.opcode)) {
				int property = op.operand(0);
				OcslType t = SurfaceTable.propertyType(program.stage, property);
				outProperties[n] = property;
				outOps[n] = op;
				outAbsolute[n] = op.opcode == OcslWire.OP_OUT_ABS;
				// The property's DECLARED width, which is what the validator type-checked the
				// operand against. The caller's array length is not that number and never was.
				outWidths[n] = t == null ? 0 : t.width;
				n++;
			}
		}
	}

	private static int maxLoopDepth(List<IrOp> ops) {
		int depth = 0, max = 0;
		for (IrOp op : ops) {
			if (op.opcode == OcslWire.OP_FOR) {
				depth++;
				if (depth > max) {
					max = depth;
				}
			} else if (op.opcode == OcslWire.OP_ENDFOR) {
				depth--;
			}
		}
		return max;
	}

	/** Where a register's components live in the frame. */
	public int offsetOf(int register) {
		return validated.frameOffset(register);
	}

	/** Bind a texture to a sampler slot, or null to unbind it. */
	public void bind(int slot, OcslTexture texture) {
		if (slot < 0 || slot >= textures.length) {
			throw new IllegalArgumentException("slot " + slot + " is outside the "
					+ textures.length + " this build binds");
		}
		textures[slot] = texture;
	}

	/** Bind an input register's components before {@link #run}. */
	public void set(int register, float... components) {
		// REFUSED, because the alternative was a claim that was simply false. Seeding `timePeriod`
		// in the constructor changed its DEFAULT and nothing else: `set(REG_TIME_PERIOD, 3.0f)`
		// succeeded and stuck for the life of the VM, and a host looping over every built-in its
		// stage has -- the obvious generic binding code -- overwrote it with 0.0, which is exactly
		// the "forgotten binding reads 0.0, and safe-divide turns that into a silent 0" failure the
		// seeding was introduced to prevent. P is a value of the FORMAT, identical in every scene on
		// every client, so there is no binding a host could supply that would be more correct than
		// the constant. Refusing here is what makes "the register cannot be wrong" true.
		if (register == SurfaceTable.REG_TIME_PERIOD) {
			throw new IllegalArgumentException("timePeriod (register "
					+ SurfaceTable.REG_TIME_PERIOD + ") is a constant of the format, seeded from"
					+ " OcslTime.PERIOD_SECONDS; it is not host state and may not be bound");
		}
		int off = validated.frameOffset(register);
		if (off < 0) {
			throw new IllegalArgumentException("register " + register + " has no frame slot");
		}
		OcslType t = validated.typeOf(register);
		if (t == null || components.length != t.width) {
			throw new IllegalArgumentException("register " + register + " is "
					+ (t == null ? "unused" : t.display()) + "; got " + components.length
					+ " component(s)");
		}
		for (int i = 0; i < components.length; i++) {
			// Sanitized on the way IN as well: a non-finite arriving from a uniform or a built-in
			// would otherwise reach an op that the catch-all promised had none. Spelled through
			// OcslIngress because the rule binds EVERY stage and this signature carries no stage --
			// ANIM-9(b) lifted it out of the pixel-stage heading it was written under.
			frame[off + i] = OcslIngress.bound(components[i]);
		}
	}

	public float get(int register, int component) {
		int off = validated.frameOffset(register);
		OcslType t = validated.typeOf(register);
		if (off < 0 || t == null || component < 0 || component >= t.width) {
			// Was `frame[-1 + component]`, which threw at component 0 and quietly returned frame[0]
			// at component 1 -- a read of an unrelated register presented as an answer.
			throw new IllegalArgumentException("register " + register
					+ (t == null ? " is unused" : " is " + t.display())
					+ "; component " + component + " does not exist");
		}
		return frame[off + component];
	}

	/**
	 * Read a written property's components into {@code out}.
	 *
	 * Reads exactly the property's declared width, and refuses an array too short to hold it —
	 * symmetric with {@link #set}, which has always checked its component count. Reading
	 * {@code out.length} components instead was a silent over-read: a vec4 property into a
	 * {@code float[16]} returned four real components and twelve floats scavenged from the scratch
	 * lanes, with no error, and a constant operand threw ArrayIndexOutOfBounds from inside the
	 * constant pool. Latent only because the pixel family's one property is a vec4 — and A1's
	 * multi-property animator surface, which is the point of the design, makes narrower properties
	 * the normal case.
	 */
	public void output(int propertyId, float[] out) {
		for (int i = 0; i < outProperties.length; i++) {
			if (outProperties[i] == propertyId) {
				int width = outWidths[i];
				if (out.length < width) {
					throw new IllegalArgumentException("property " + propertyId + " is " + width
							+ " component(s) wide; got an array of " + out.length);
				}
				readValue(outOps[i], 1, width, out);
				return;
			}
		}
		throw new IllegalArgumentException("program writes no property " + propertyId);
	}

	/**
	 * Whether a written property was written ABSOLUTELY — the form its value must be composed with.
	 *
	 * The VM hands back the raw output either way; the distinction has to survive the trip or the
	 * consumer composes {@code OUT_ABS} relatively and silently applies the base twice, which is the
	 * exact defect the opcode exists to prevent. Exposed here rather than left for the consumer to
	 * re-derive from the op stream, because a consumer that has to re-derive it is a consumer that
	 * can forget to.
	 */
	public boolean isAbsolute(int propertyId) {
		for (int i = 0; i < outProperties.length; i++) {
			if (outProperties[i] == propertyId) {
				return outAbsolute[i];
			}
		}
		throw new IllegalArgumentException("program writes no property " + propertyId);
	}

	/**
	 * Evaluate the program once. Allocates nothing.
	 *
	 * Sampling reads whatever texture the host bound to the slot, by the S1-S5 rules; an unbound
	 * slot reads 0 per component rather than failing, because the validator cannot know what the
	 * host will bind.
	 */
	public void run() {
		List<IrOp> ops = program.ops();
		int depth = 0;
		for (int pc = 0; pc < ops.size(); pc++) {
			IrOp op = ops.get(pc);
			byte code = op.opcode;

			if (code == OcslWire.OP_FOR) {
				loopTrips[depth] = op.operand(0);
				loopCounter[depth] = 0;
				loopBodyStart[depth] = pc + 1;
				// The accumulator is seeded from the init operand, which charges nothing and is
				// why a pooled vector constant matters: building the seed would cost an op.
				copyValue(op, 1, validated.frameOffset(op.dst), widthOf(op.dst));
				depth++;
				continue;
			}
			if (code == OcslWire.OP_ENDFOR) {
				int d = depth - 1;
				loopCounter[d]++;
				if (loopCounter[d] < loopTrips[d]) {
					pc = loopBodyStart[d] - 1; // -1 because the loop increments
				} else {
					depth--;
				}
				continue;
			}
			if (OcslWire.isOut(code)) {
				continue; // read by output(), not evaluated
			}
			execute(op, code, depth);
		}
	}

	private int widthOf(int register) {
		OcslType t = validated.typeOf(register);
		return t == null ? 0 : t.width;
	}

	/** Frame offset of an operand, resolving constants into the read helpers below. */
	private void execute(IrOp op, byte code, int depth) {
		int dst = validated.frameOffset(op.dst);
		int w = widthOf(op.dst);

		switch (code) {
			case OcslWire.OP_ADD: case OcslWire.OP_SUB: case OcslWire.OP_MUL:
			case OcslWire.OP_DIV: case OcslWire.OP_MOD: case OcslWire.OP_MIN:
			case OcslWire.OP_MAX: case OcslWire.OP_STEP: case OcslWire.OP_POW:
			case OcslWire.OP_ATAN2:
				for (int i = 0; i < w; i++) {
					float a = component(op, 0, i), b = component(op, 1, i);
					frame[dst + i] = binary(code, a, b);
				}
				return;
			case OcslWire.OP_CLAMP: case OcslWire.OP_MIX: case OcslWire.OP_SMOOTHSTEP:
				for (int i = 0; i < w; i++) {
					float a = component(op, 0, i), b = component(op, 1, i), c = component(op, 2, i);
					frame[dst + i] = code == OcslWire.OP_CLAMP ? OcslMath.clamp(a, b, c)
							: code == OcslWire.OP_MIX ? OcslMath.mix(a, b, c)
									: OcslMath.smoothstep(a, b, c);
				}
				return;
			case OcslWire.OP_NEG: case OcslWire.OP_ABS: case OcslWire.OP_FLOOR:
			case OcslWire.OP_FRACT: case OcslWire.OP_EXP: case OcslWire.OP_LOG:
			case OcslWire.OP_SQRT: case OcslWire.OP_SIN: case OcslWire.OP_COS:
				for (int i = 0; i < w; i++) {
					frame[dst + i] = unary(code, component(op, 0, i));
				}
				return;
			case OcslWire.OP_NORMALIZE: {
				int src = scratchInto(op, 0);
				OcslMath.normalize(frame, dst, src, w);
				return;
			}
			case OcslWire.OP_DOT:
				frame[dst] = OcslMath.dot(frame, scratchInto(op, 0), scratchInto(op, 1),
						operandWidth(op, 0));
				return;
			case OcslWire.OP_DISTANCE:
				frame[dst] = OcslMath.distance(frame, scratchInto(op, 0), scratchInto(op, 1),
						operandWidth(op, 0));
				return;
			case OcslWire.OP_LENGTH:
				frame[dst] = OcslMath.length(frame, scratchInto(op, 0), operandWidth(op, 0));
				return;
			case OcslWire.OP_CROSS:
				OcslMath.cross(frame, dst, scratchInto(op, 0), scratchInto(op, 1));
				return;
			case OcslWire.OP_SWZ: {
				int mask = op.operand(1);
				int src = scratchInto(op, 0);
				// Read every component BEFORE writing any, so an in-place swizzle onto its own
				// source cannot read a value this op already overwrote.
				float c0 = frame[src + OcslWire.swizzleComponent(mask, 0)];
				float c1 = w > 1 ? frame[src + OcslWire.swizzleComponent(mask, 1)] : 0f;
				float c2 = w > 2 ? frame[src + OcslWire.swizzleComponent(mask, 2)] : 0f;
				float c3 = w > 3 ? frame[src + OcslWire.swizzleComponent(mask, 3)] : 0f;
				frame[dst] = c0;
				if (w > 1) frame[dst + 1] = c1;
				if (w > 2) frame[dst + 2] = c2;
				if (w > 3) frame[dst + 3] = c3;
				return;
			}
			case OcslWire.OP_SPLAT: {
				float v = component(op, 0, 0);
				for (int i = 0; i < w; i++) {
					frame[dst + i] = v;
				}
				return;
			}
			case OcslWire.OP_CONS2: case OcslWire.OP_CONS3: case OcslWire.OP_CONS4:
				for (int i = 0; i < w; i++) {
					frame[dst + i] = component(op, i, 0);
				}
				return;
			case OcslWire.OP_CONS3_V2F: case OcslWire.OP_CONS4_V3F: {
				int lead = w - 1;
				int src = scratchInto(op, 0);
				for (int i = 0; i < lead; i++) {
					frame[dst + i] = frame[src + i];
				}
				frame[dst + lead] = component(op, 1, 0);
				return;
			}
			case OcslWire.OP_CONS4_V2V2: {
				int a = scratchInto(op, 0), b = scratchInto(op, 1);
				float a0 = frame[a], a1 = frame[a + 1], b0 = frame[b], b1 = frame[b + 1];
				frame[dst] = a0;
				frame[dst + 1] = a1;
				frame[dst + 2] = b0;
				frame[dst + 3] = b1;
				return;
			}
			case OcslWire.OP_LT:
				frame[dst] = component(op, 0, 0) < component(op, 1, 0) ? 1f : 0f;
				return;
			case OcslWire.OP_LE:
				frame[dst] = component(op, 0, 0) <= component(op, 1, 0) ? 1f : 0f;
				return;
			case OcslWire.OP_EQ:
				frame[dst] = component(op, 0, 0) == component(op, 1, 0) ? 1f : 0f;
				return;
			case OcslWire.OP_BAND:
				frame[dst] = (component(op, 0, 0) != 0f && component(op, 1, 0) != 0f) ? 1f : 0f;
				return;
			case OcslWire.OP_BOR:
				frame[dst] = (component(op, 0, 0) != 0f || component(op, 1, 0) != 0f) ? 1f : 0f;
				return;
			case OcslWire.OP_BNOT:
				frame[dst] = 1f - component(op, 0, 0);
				return;
			case OcslWire.OP_SELECT: {
				// WHOLE-VALUE STRICT PICK, and this is where the frozen guarantee is kept: the
				// non-selected arm is never read, so it cannot contaminate the result even when
				// non-finite. That also makes select the one exemption from the catch-all -- the
				// discarded operand is discarded BEFORE any sanitizing sees it, so
				// select(true, 1.0, Inf) is 1.0 while select(true, Inf, 1.0) is 0.
				boolean cond = component(op, 0, 0) != 0f;
				int arm = cond ? 1 : 2;
				int src = scratchInto(op, arm);
				for (int i = 0; i < w; i++) {
					frame[dst + i] = frame[src + i];
				}
				return;
			}
			case OcslWire.OP_SAMPLE: {
				int slot = op.operand(0);
				OcslTexture texture = slot >= 0 && slot < textures.length ? textures[slot] : null;
				if (texture == null) {
					// S5: an UNBOUND slot reads 0, and is not an error. The validator cannot know
					// what the host will bind, so a program sampling a slot the host leaves empty
					// must render predictably rather than refuse to run.
					for (int i = 0; i < w; i++) {
						frame[dst + i] = 0f;
					}
					return;
				}
				OcslMath.sample(texture.rgba, texture.width, texture.height,
						component(op, 1, 0), component(op, 1, 1), frame, dst);
				return;
			}
			case OcslWire.OP_ITOF:
				frame[dst] = loopCounter[depth - 1 - op.operand(0)];
				return;
			default:
				throw new IllegalStateException("no VM rule for opcode " + (code & 0xFF)
						+ "; the validator should have refused this program");
		}
	}

	private static float binary(byte code, float a, float b) {
		switch (code) {
			case OcslWire.OP_ADD: return OcslMath.add(a, b);
			case OcslWire.OP_SUB: return OcslMath.sub(a, b);
			case OcslWire.OP_MUL: return OcslMath.mul(a, b);
			case OcslWire.OP_DIV: return OcslMath.div(a, b);
			case OcslWire.OP_MOD: return OcslMath.mod(a, b);
			case OcslWire.OP_MIN: return OcslMath.min(a, b);
			case OcslWire.OP_MAX: return OcslMath.max(a, b);
			case OcslWire.OP_STEP: return OcslMath.step(a, b);
			case OcslWire.OP_POW: return OcslMath.pow(a, b);
			case OcslWire.OP_ATAN2: return OcslMath.atan2(a, b);
			// Not a default that guesses. These two helpers are dispatched from a case list in
			// execute(), and a `default:` returning atan2 meant that adding one opcode to that list
			// and forgetting this one would silently compute the wrong function forever. For a
			// class whose stated purpose is that two executors cannot drift apart one op at a time,
			// a silent default IS the drift mechanism.
			default: throw new IllegalStateException("binary() has no rule for opcode "
					+ (code & 0xFF) + "; it was added to execute()'s dispatch list and not here");
		}
	}

	private static float unary(byte code, float a) {
		switch (code) {
			case OcslWire.OP_NEG: return OcslMath.neg(a);
			case OcslWire.OP_ABS: return OcslMath.abs(a);
			case OcslWire.OP_FLOOR: return OcslMath.floor(a);
			case OcslWire.OP_FRACT: return OcslMath.fract(a);
			case OcslWire.OP_EXP: return OcslMath.exp(a);
			case OcslWire.OP_LOG: return OcslMath.log(a);
			case OcslWire.OP_SQRT: return OcslMath.sqrt(a);
			case OcslWire.OP_SIN: return OcslMath.sin(a);
			case OcslWire.OP_COS: return OcslMath.cos(a);
			default: throw new IllegalStateException("unary() has no rule for opcode "
					+ (code & 0xFF) + "; it was added to execute()'s dispatch list and not here");
		}
	}

	/**
	 * One component of an operand, BROADCASTING a float source across a wider destination.
	 *
	 * This is the re-opened amendment-4 rule at runtime: a scalar operand supplies component 0 to
	 * every lane, which is exactly what makes {@code mix(vec3, vec3, float)} one instruction.
	 */
	private float component(IrOp op, int slot, int i) {
		if (op.isConstant(slot)) {
			int c = op.index(slot);
			return program.constantWidth(c) == 1 ? program.constantComponent(c, 0)
					: program.constantComponent(c, i);
		}
		int reg = op.index(slot);
		int off = validated.frameOffset(reg);
		return widthOf(reg) == 1 ? frame[off] : frame[off + i];
	}

	private int operandWidth(IrOp op, int slot) {
		return op.isConstant(slot) ? program.constantWidth(op.index(slot))
				: widthOf(op.index(slot));
	}

	/**
	 * A contiguous frame offset for an operand that must be read as a whole vector.
	 *
	 * Register operands already are contiguous, so this returns their offset directly and costs
	 * nothing. A CONSTANT has no frame slot, so its components are copied into a scratch tail that
	 * was sized once at construction — never allocated here, which is what keeps {@link #run}
	 * allocation-free.
	 */
	private int scratchInto(IrOp op, int slot) {
		if (!op.isConstant(slot)) {
			return validated.frameOffset(op.index(slot));
		}
		int c = op.index(slot);
		int width = program.constantWidth(c);
		// Per-SLOT lane, so two constant operands in one op cannot overwrite each other.
		int base = validated.frameWidth + slot * SCRATCH_WIDTH;
		for (int i = 0; i < width; i++) {
			frame[base + i] = program.constantComponent(c, i);
		}
		return base;
	}

	private void copyValue(IrOp op, int slot, int dst, int width) {
		for (int i = 0; i < width; i++) {
			frame[dst + i] = component(op, slot, i);
		}
	}

	private void readValue(IrOp op, int slot, int width, float[] out) {
		for (int i = 0; i < width; i++) {
			out[i] = component(op, slot, i);
		}
	}
}
