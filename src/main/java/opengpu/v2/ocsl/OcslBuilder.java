package opengpu.v2.ocsl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * THE AUTHORING SURFACE: build an OCSL program by writing expressions, and get an {@link IrProgram}
 * that the validator accepts and the VM runs.
 *
 * This is the Java layer the shipped Lua library sits on. The Lua surface is metatable-overloaded —
 * {@code a * b}, {@code p.x} — and every one of those metamethods lands on a method here, so the
 * shapes match deliberately: binary arithmetic on {@link Expr}, {@code x()/y()/z()/w()} for the
 * {@code __index} swizzles, and FUNCTION-FORM comparisons and boolean combination
 * ({@link Expr#lt}, {@link #select}) because Lua coerces comparison metamethods to booleans and
 * {@code and}/{@code or}/{@code not} have no metamethods at all. That is a permanent constraint of
 * the host language, not a stopgap, so the Java surface wears it too rather than offering an
 * ergonomics the Lua layer could never expose.
 *
 * <h2>Emission is EAGER, and that is the load-bearing decision</h2>
 *
 * Every operation appends exactly one op and allocates exactly one register, at the call site,
 * immediately. There is no expression graph, no common-subexpression elimination and no dead-code
 * pass. Three reasons, in the order they bind:
 *
 * <ol>
 * <li><b>The counts are the contract.</b> The four acceptance programs' op counts were computed by
 *     hand from straight-line listings. An optimizer would make the count a function of how clever
 *     it happens to be, and "reproduce the committed number" would stop meaning anything.</li>
 * <li><b>Canonical form is the content hash.</b> Any optimization is a CHOICE, and two spellings of
 *     one program that lower differently fork the compile-cache key on a difference no program can
 *     observe. Determinism is not a nice property here; it is the requirement.</li>
 * <li><b>Errors belong at the call site.</b> The design asks for type and budget errors to surface
 *     "at the exact builder call site with a normal Lua traceback". A deferred emitter can only
 *     report at {@code build()}, by which point the traceback is gone.</li>
 * </ol>
 *
 * <h2>The builder and the validator must agree, so {@link #build} makes them</h2>
 *
 * A2 requires the builder and the validator to agree on the structural count. That obligation was
 * prose, and prose obligations are not enforced — so {@link #build} runs the real validator and
 * compares its count against the one this builder accumulated while emitting. A disagreement throws
 * there and then, naming both numbers. It cannot drift into a release and be discovered at the cap
 * boundary, which is the failure A2 predicts in as many words: "what stops builder acceptance and
 * validator rejection diverging at 255 ops".
 *
 * <h2>Canonicalization</h2>
 *
 * All four components A2 names are implemented here, because only the complete rule supports the
 * claim that identical source cannot fork the hash space:
 *
 * <ul>
 * <li><b>Constant-pool dedup</b> — {@link #f} and {@link #constant} intern by value, so one literal
 *     is one pool entry however many times it is written.</li>
 * <li><b>Broadcast collapse</b> — a float operand to a component-wise op is passed through as a
 *     float. The builder NEVER inserts a splat there. Both spellings compute the same value and
 *     charge differently, so allowing the choice would fork the hash; this is the obligation the
 *     amendment-4 re-opening took on explicitly.</li>
 * <li><b>Operand ordering</b> — source order, always. The builder does not commute anything, so
 *     {@code a.mul(b)} and {@code b.mul(a)} stay distinct programs rather than being normalized by
 *     a rule the Lua author cannot see.</li>
 * <li><b>Lowering choices</b> — the zoo op, always. Under structural counting a zoo op is never
 *     worse than its expansion ({@code LENGTH} charges 1 where {@code SQRT(DOT(v,v))} charges 2),
 *     so the canonical choice is never in tension with the cheap one.</li>
 * </ul>
 */
public final class OcslBuilder {

	/** Thrown at the offending call site, which is the whole point of building eagerly. */
	public static final class BuildException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		BuildException(String message) {
			super(message);
		}
	}

	/** The body of a {@link #loop}: given the accumulator and the counter, produce the next value. */
	public interface Fold {
		Expr apply(Expr accumulator, Counter counter);
	}

	/**
	 * A loop counter. Deliberately NOT an {@link Expr}: A3 pins the counter as an immediate
	 * loop-depth selector rather than a register, so it cannot be an operand to anything. Reading
	 * it costs an {@code ITOF} — charged once per iteration, on an interpreter and on unrolled
	 * codegen alike — and {@link #value} is the only way to get one.
	 */
	public final class Counter {
		/**
		 * Identifies the LOOP, not a position in the stack.
		 *
		 * An earlier draft stored the depth, and depth is not identity: a counter captured from a
		 * closed fold and read inside a later one silently resolved to THAT loop's counter. The
		 * program built, validated and ran, and returned the wrong number with no diagnostic —
		 * two sibling folds, the second reading the first's counter, gave 0+1+2+3+4 from the
		 * second loop's own five trips. The only guard was {@code selector < 0}, which catches
		 * reading outside any loop and not reading inside an unrelated one.
		 */
		private final int loopId;

		Counter(int loopId) {
			this.loopId = loopId;
		}

		/** {@code float(i)} for this loop, as a fresh float register. */
		public Expr value() {
			int position = liveLoops.indexOf(Integer.valueOf(loopId));
			if (position < 0) {
				throw new BuildException("this counter belongs to a loop that has already closed;"
						+ " a counter is readable only inside its own fold");
			}
			// The IR operand is a depth selector where 0 is the INNERMOST open loop, so it is
			// derived from where this loop sits in the LIVE stack rather than from where it sat
			// when the counter was handed out.
			return emit(OcslWire.OP_ITOF, OcslType.FLOAT, false,
					liveLoops.size() - 1 - position);
		}
	}

	private final byte stage;
	private final List<IrOp> ops = new ArrayList<IrOp>();
	private final List<float[]> constants = new ArrayList<float[]>();
	private final Map<String, Integer> constantIndex = new HashMap<String, Integer>();
	private final List<String> uniformNames = new ArrayList<String>();
	private final Map<String, Integer> uniformIndex = new HashMap<String, Integer>();
	private final List<OcslType> registerTypes = new ArrayList<OcslType>();
	/** Per working register: does its value depend, transitively, on the tint built-in? */
	private final List<Boolean> tintDependent = new ArrayList<Boolean>();
	private final List<Integer> tripStack = new ArrayList<Integer>();
	private final List<Integer> loopFirstOp = new ArrayList<Integer>();
	/** Loop ids currently open, outermost first — what a {@link Counter} is resolved against. */
	private final List<Integer> liveLoops = new ArrayList<Integer>();
	private final List<String> warnings = new ArrayList<String>();
	private int nextLoopId = 0;
	private int fetches = 0;
	private long structural = 0;
	private long multiplier = 1;
	private boolean built = false;

	private OcslBuilder(byte stage) {
		this.stage = stage;
	}

	public static OcslBuilder forStage(byte stage) {
		if (!OcslWire.isKnownStage(stage)) {
			throw new BuildException("stage " + (stage & 0xFF) + " is not a stage this build knows");
		}
		if (!SurfaceTable.isOpen(stage)) {
			// The same refusal the validator makes, made EARLIER: letting a caller build against a
			// shut surface would mean discovering at build() that nothing they wrote could ever go
			// anywhere. Both gates now ask SurfaceTable.isOpen rather than inferring it from an
			// empty requiredProperties -- an inference that was correct only until a surface wanted
			// to mandate no output, which is exactly what the animator does.
			throw new BuildException("stage " + (stage & 0xFF) + " is not open: no program may be"
					+ " built on this surface yet");
		}
		return new OcslBuilder(stage);
	}

	// ---------------------------------------------------------------- inputs

	/** A built-in input register for this stage — {@code uv}, {@code time}, {@code tint}, … */
	public Expr builtin(int register) {
		OcslType type = SurfaceTable.builtinType(stage, register);
		if (type == null) {
			throw new BuildException("stage " + (stage & 0xFF) + " has no built-in register "
					+ register);
		}
		return new Expr(this, register, type);
	}

	/**
	 * Declare (or re-reference) a uniform by name.
	 *
	 * v1 uniforms are float-typed: the wire carries no per-uniform type, so widening one is a
	 * format change rather than a builder relaxation. A program wanting a vec3 uniform splats three
	 * of these, and pays for the splat — which is why the acceptance transcriptions carry a
	 * two-op prologue the dry run's hand listing did not.
	 */
	public Expr uniform(String name) {
		// Checked HERE against the same rule the wire enforces. Without it, `setUniform("my name")`
		// -- an entirely ordinary thing for an author to write -- was accepted, and build() then
		// reported it as a defect in the builder or the validator rather than as a name the format
		// cannot carry.
		try {
			IrStructure.checkName(0, name);
		} catch (IrStructure.StructureException e) {
			throw new BuildException("uniform name \"" + name + "\" cannot be carried: "
					+ e.getMessage().replaceFirst("^Name 0 ", ""));
		}
		Integer existing = uniformIndex.get(name);
		if (existing != null) {
			return new Expr(this, SurfaceTable.UNIFORM_BASE + existing.intValue(), OcslType.FLOAT);
		}
		if (uniformNames.size() >= SurfaceTable.MAX_UNIFORMS) {
			throw new BuildException("declares more than " + SurfaceTable.MAX_UNIFORMS
					+ " uniforms, which is what the id block reserves");
		}
		int slot = uniformNames.size();
		uniformNames.add(name);
		uniformIndex.put(name, Integer.valueOf(slot));
		return new Expr(this, SurfaceTable.UNIFORM_BASE + slot, OcslType.FLOAT);
	}

	/** A float literal. Interned, so writing 1.0 twenty times is one pool entry. */
	public Expr f(float value) {
		return constant(new float[] { value });
	}

	/**
	 * A pooled vector literal — 1..4 components, costing NO op.
	 *
	 * Distinct from {@link #vec4(Expr, Expr, Expr, Expr)} and it matters: a fold seeded with
	 * {@code vec4(0)} built by a CONSTRUCTOR charges an op that no committed count includes, which
	 * is exactly why the constant pool is typed rather than a flat array of scalars.
	 */
	public Expr constant(float... components) {
		return pool(components.clone());
	}

	private Expr pool(float[] components) {
		if (components.length < 1 || components.length > 4) {
			throw new BuildException("a constant has 1..4 components; got " + components.length);
		}
		StringBuilder key = new StringBuilder();
		for (int i = 0; i < components.length; i++) {
			if (!OcslMath.finite(components[i])) {
				// Refused at the point of authorship, where the author can see which literal it
				// was. The structural gate refuses it again later; this is the message with the
				// context.
				throw new BuildException("constant component " + i + " is " + components[i]
						+ "; the pool carries finite values only");
			}
			// floatToIntBits, not the value: it separates 0.0 from -0.0, which are different
			// constants on the wire and would otherwise dedup into one.
			key.append(Float.floatToIntBits(components[i])).append(',');
		}
		Integer existing = constantIndex.get(key.toString());
		if (existing != null) {
			return new Expr(this, OcslWire.OPERAND_CONST_FLAG | existing.intValue(),
					OcslType.ofWidth(components.length));
		}
		int index = constants.size();
		if (index >= OcslWire.MAX_CONSTANTS) {
			throw new BuildException("pools more than " + OcslWire.MAX_CONSTANTS + " constants");
		}
		constants.add(components);
		constantIndex.put(key.toString(), Integer.valueOf(index));
		return new Expr(this, OcslWire.OPERAND_CONST_FLAG | index,
				OcslType.ofWidth(components.length));
	}

	// ---------------------------------------------------------------- emission

	int loopDepth() {
		return tripStack.size();
	}

	byte stage() {
		return stage;
	}

	/**
	 * Append one op, allocate its destination, and charge it.
	 *
	 * The single point at which a program grows, so the budget, the register layout and the tint
	 * dataflow are all maintained in one place rather than at forty call sites.
	 */
	Expr emit(byte opcode, OcslType resultType, boolean tintFlows, int... operands) {
		if (built) {
			throw new BuildException("this builder has already produced a program");
		}
		OcslWire.Shape shape = OcslWire.shapeOf(opcode);
		int dst = SurfaceTable.WORKING_BASE + registerTypes.size();
		if (dst >= SurfaceTable.MAX_REGISTERS) {
			throw new BuildException("needs more than " + SurfaceTable.MAX_REGISTERS
					+ " registers; every op allocates one, so this is a program-length limit");
		}
		registerTypes.add(resultType);
		tintDependent.add(Boolean.valueOf(tintFlows));
		ops.add(new IrOp(opcode, dst, operands));
		charge(shape.structuralCharge);
		return new Expr(this, dst, resultType);
	}

	private void charge(int perExecution) {
		structural += multiplier * perExecution;
		if (structural > IrValidator.MAX_STRUCTURAL_OPS) {
			// AT THE CALL SITE. The validator would refuse the finished program with an op index,
			// which is the right surface for a wire blob and the wrong one for an author who is
			// still typing.
			throw new BuildException("program charges " + structural + " structural ops, over the"
					+ " cap of " + IrValidator.MAX_STRUCTURAL_OPS
					+ "; the op that crossed it is the one being written here");
		}
	}

	boolean tintDependent(int operand) {
		if ((operand & OcslWire.OPERAND_CONST_FLAG) != 0) {
			return false;
		}
		int reg = operand & OcslWire.OPERAND_INDEX_MASK;
		if (reg == SurfaceTable.REG_TINT) {
			return true;
		}
		int working = reg - SurfaceTable.WORKING_BASE;
		return working >= 0 && working < tintDependent.size()
				&& tintDependent.get(working).booleanValue();
	}

	// ---------------------------------------------------------------- constructors

	/** {@code vec2(x, y)} from two floats. */
	public Expr vec2(Expr x, Expr y) {
		return construct(OcslWire.OP_CONS2, OcslType.VEC2, x, y);
	}

	/** {@code vec3(x, y, z)} from three floats. */
	public Expr vec3(Expr x, Expr y, Expr z) {
		return construct(OcslWire.OP_CONS3, OcslType.VEC3, x, y, z);
	}

	/** {@code vec4(x, y, z, w)} from four floats. */
	public Expr vec4(Expr x, Expr y, Expr z, Expr w) {
		return construct(OcslWire.OP_CONS4, OcslType.VEC4, x, y, z, w);
	}

	/** {@code vec3(xy, z)} — one op, not a swizzle-and-assemble. */
	public Expr vec3(Expr xy, Expr z) {
		require(xy, OcslType.VEC2, "vec3(vec2, float)");
		require(z, OcslType.FLOAT, "vec3(vec2, float)");
		return emit(OcslWire.OP_CONS3_V2F, OcslType.VEC3,
				tintDependent(xy.operand) || tintDependent(z.operand), xy.operand, z.operand);
	}

	/**
	 * The two-part {@code vec4}: {@code vec4(vec3, float)} or {@code vec4(vec2, vec2)}.
	 *
	 * ONE method dispatching on the operand types rather than two overloads, and not only because
	 * Java erases both to {@code (Expr, Expr)}. The Lua surface is dynamically typed and will offer
	 * exactly one {@code vec4} that picks by what it is handed; a Java API that split them would
	 * have the two layers disagree about how many constructors there are.
	 */
	public Expr vec4(Expr a, Expr b) {
		if (a.owner != this || b.owner != this) {
			throw new BuildException("expression belongs to a different builder");
		}
		if (a.type == OcslType.VEC3 && b.type == OcslType.FLOAT) {
			return emit(OcslWire.OP_CONS4_V3F, OcslType.VEC4,
					tintDependent(a.operand) || tintDependent(b.operand), a.operand, b.operand);
		}
		if (a.type == OcslType.VEC2 && b.type == OcslType.VEC2) {
			return emit(OcslWire.OP_CONS4_V2V2, OcslType.VEC4,
					tintDependent(a.operand) || tintDependent(b.operand), a.operand, b.operand);
		}
		throw new BuildException("two-part vec4 takes (vec3, float) or (vec2, vec2), got ("
				+ a.type.display() + ", " + b.type.display() + ")");
	}

	private Expr construct(byte opcode, OcslType result, Expr... parts) {
		int[] operands = new int[parts.length];
		boolean tint = false;
		for (int i = 0; i < parts.length; i++) {
			require(parts[i], OcslType.FLOAT, result.display() + " from floats");
			operands[i] = parts[i].operand;
			tint |= tintDependent(parts[i].operand);
		}
		return emit(opcode, result, tint, operands);
	}

	private void require(Expr e, OcslType type, String what) {
		if (e.owner != this) {
			throw new BuildException("expression belongs to a different builder");
		}
		if (e.type != type) {
			throw new BuildException(what + " wants " + type.display() + ", got " + e.type.display());
		}
	}

	// ---------------------------------------------------------------- control

	/**
	 * The bounded fold: {@code acc = init}, then {@code trips} iterations of {@code body}.
	 *
	 * A fold rather than a loop STATEMENT, and it maps 1:1 to IR {@code FOR}. Build-time unrolling
	 * in the host language is explicitly not the mechanism — it would burn the op cap, and the
	 * whole point of an interpreted {@code for} is that the frame width stays independent of the
	 * trip count.
	 *
	 * The body returns the accumulator's next value. When that value is the result of the body's
	 * LAST op — which is the natural shape, {@code return acc.add(tap)} — the builder retargets
	 * that op to write the accumulator directly and hands its register back, so the fold costs
	 * exactly what the hand listings say. Anything else needs a real copy, and that copy is
	 * charged rather than hidden.
	 */
	public Expr loop(int trips, Expr init, Fold body) {
		if (trips < 1) {
			throw new BuildException("a loop of " + trips + " iterations never runs, so its"
					+ " accumulator would keep its init value -- write that value directly");
		}
		if (multiplier * trips > IrValidator.MAX_UNROLL_PRODUCT) {
			throw new BuildException("nesting this loop reaches an unroll product of "
					+ (multiplier * trips) + ", over the cap of " + IrValidator.MAX_UNROLL_PRODUCT);
		}
		if (loopDepth() >= OcslWire.MAX_LOOP_DEPTH) {
			throw new BuildException("loop nesting would exceed depth " + OcslWire.MAX_LOOP_DEPTH);
		}
		if (init.owner != this) {
			throw new BuildException("expression belongs to a different builder");
		}

		int acc = SurfaceTable.WORKING_BASE + registerTypes.size();
		registerTypes.add(init.type);
		tintDependent.add(Boolean.valueOf(tintDependent(init.operand)));
		ops.add(new IrOp(OcslWire.OP_FOR, acc, trips, init.operand));
		// FOR charges nothing: it is encoding structure, and the count is a post-unroll count.
		tripStack.add(Integer.valueOf(trips));
		loopFirstOp.add(Integer.valueOf(ops.size()));
		int loopId = nextLoopId++;
		liveLoops.add(Integer.valueOf(loopId));
		multiplier *= trips;

		Counter counter = new Counter(loopId);
		Expr result = body.apply(new Expr(this, acc, init.type), counter);
		if (result == null || result.owner != this) {
			throw new BuildException("the fold body must return an expression from this builder");
		}
		if (result.type != init.type) {
			throw new BuildException("the fold body returns " + result.type.display()
					+ " but the accumulator was seeded " + init.type.display()
					+ "; a fold's accumulator keeps one type");
		}

		int bodyStart = loopFirstOp.remove(loopFirstOp.size() - 1).intValue();
		if (ops.size() == bodyStart) {
			// Refused here for the same reason the validator refuses it: an empty body computes
			// nothing and still costs the interpreter a back-edge per iteration, which the op count
			// cannot see. Caught at the call site, where the author can see the empty lambda.
			throw new BuildException("the fold body emits no op, so the loop computes nothing and"
					+ " leaves the accumulator at its init value");
		}
		writeBackAccumulator(acc, result, bodyStart);

		multiplier = 1;
		tripStack.remove(tripStack.size() - 1);
		liveLoops.remove(liveLoops.size() - 1);
		for (int i = 0; i < tripStack.size(); i++) {
			multiplier *= tripStack.get(i).intValue();
		}
		ops.add(new IrOp(OcslWire.OP_ENDFOR, -1));
		return new Expr(this, acc, init.type);
	}

	private void writeBackAccumulator(int acc, Expr result, int bodyStart) {
		int last = ops.size() - 1;
		IrOp lastOp = ops.get(last);
		boolean isFreshBodyResult = last >= bodyStart
				&& lastOp.dst == result.operand
				&& result.operand == SurfaceTable.WORKING_BASE + registerTypes.size() - 1;
		if (result.operand == acc) {
			throw new BuildException("the fold body returns the accumulator unchanged");
		}
		// The accumulator inherits whatever the body's result depends on. Seeding this from `init`
		// alone made the tint advisory wrong in BOTH directions for every folding program: it fired
		// on a fold that accumulates tint, and stayed silent on one that folds tint into a value it
		// then outputs.
		tintDependent.set(acc - SurfaceTable.WORKING_BASE, Boolean.valueOf(
				tintDependent(acc) || tintDependent(result.operand)));

		if (isFreshBodyResult) {
			// Retarget rather than copy: the op that was going to write a fresh register writes the
			// accumulator instead, which is the shape every hand listing uses and costs nothing.
			//
			// THE REGISTER IS NOT RECYCLED, and that is a correctness fix rather than tidiness. An
			// earlier draft popped it so the next op would reuse the number -- and an `Expr` the
			// author still held then silently aliased whatever came next. Measured: a fold whose
			// body result was kept in a local produced `FOR r96 / ADD r96 / ENDFOR / SIN r97 /
			// MUL r98, r97 / OUT r98`, i.e. the entire fold dead and the output carrying sin(time),
			// validating and running with no diagnostic anywhere. Leaving the register allocated
			// makes that same program a READ OF AN UNWRITTEN REGISTER, which the validator refuses
			// by name. The register costs nothing: never written, it never enters the frame layout.
			int[] operands = new int[lastOp.operandCount()];
			for (int i = 0; i < operands.length; i++) {
				operands[i] = lastOp.operand(i);
			}
			ops.set(last, new IrOp(lastOp.opcode, acc, operands));
			return;
		}
		// A real copy, and CHARGED. Reached when the body returns something it did not just
		// compute -- a constant, a built-in, a register from an earlier statement, or the result of
		// a NESTED fold, whose body always ends on ENDFOR.
		ops.add(copyOp(acc, result));
		charge(1);
	}

	/**
	 * The cheapest legal one-op copy for a value of this type.
	 *
	 * A bool needs its OWN spelling. The copy was unconditionally a {@code SWZ}, which the
	 * validator refuses over a bool operand — and not for want of a guard: {@code SWZ}'s type rule
	 * yields {@code ofWidth(1)}, i.e. FLOAT, so it could not write a bool register even if the
	 * operand check let it through. That made a bool fold build or fail depending on whether its
	 * body happened to end on the returned op, and a NESTED bool fold always failed, because an
	 * inner loop ends its body on {@code ENDFOR}. {@code BOR(x, x)} is the bool identity: one op,
	 * bool in, bool out, and exact over the canonical {0,1} a bool register is allowed to hold.
	 */
	private IrOp copyOp(int dst, Expr result) {
		if (result.type == OcslType.BOOL) {
			return new IrOp(OcslWire.OP_BOR, dst, result.operand, result.operand);
		}
		int width = result.type.width;
		int[] components = new int[width];
		for (int i = 0; i < width; i++) {
			components[i] = i;
		}
		return new IrOp(OcslWire.OP_SWZ, dst, result.operand, OcslWire.packSwizzle(components));
	}

	/**
	 * {@code cond ? whenTrue : whenFalse}, whole-value STRICT PICK.
	 *
	 * Function-form because Lua's {@code and}/{@code or} have no metamethods, and strict-pick
	 * because the discarded arm must never contaminate the result — the guarantee A4's catch-all
	 * was written around and the reason {@code select} is its one exemption.
	 */
	public Expr select(Expr cond, Expr whenTrue, Expr whenFalse) {
		require(cond, OcslType.BOOL, "select's condition");
		// Both arms checked for OWNERSHIP too. Only the condition was, so an Expr from a different
		// builder was accepted and its raw operand index emitted verbatim -- and when that index
		// happened to name a written register here, the program validated, ran, and computed
		// something else entirely. Expr's own methods all check; these two entry points did not.
		requireOwned(whenTrue);
		requireOwned(whenFalse);
		if (whenTrue.type != whenFalse.type) {
			throw new BuildException("select's arms are " + whenTrue.type.display() + " and "
					+ whenFalse.type.display() + "; both arms carry one type");
		}
		// The CONDITION carries dependency too: which arm is taken is part of what the value is.
		return emit(OcslWire.OP_SELECT, whenTrue.type,
				tintDependent(cond.operand) || tintDependent(whenTrue.operand)
						|| tintDependent(whenFalse.operand),
				cond.operand, whenTrue.operand, whenFalse.operand);
	}

	void requireOwned(Expr e) {
		if (e == null || e.owner != this) {
			throw new BuildException("expression belongs to a different builder");
		}
	}

	/** Sample a texture slot at a vec2 coordinate. */
	public Expr sample(int slot, Expr uv) {
		require(uv, OcslType.VEC2, "sample's coordinate");
		if (slot < 0 || slot >= SurfaceTable.MAX_SLOTS) {
			throw new BuildException("slot " + slot + " is outside the " + SurfaceTable.MAX_SLOTS
					+ " this build binds");
		}
		if (slot == SurfaceTable.SLOT_INPUT && !SurfaceTable.hasInputSampler(stage)) {
			throw new BuildException("slot 0 is the built-in `input` sampler, which only the effect"
					+ " and post-chain surfaces have");
		}
		// THE FETCH BUDGET, counted POST-UNROLL like the validator's. Without it a 17-tap blur --
		// the natural next edit of the committed 9-tap one -- was accepted call by call and then
		// refused inside build() with a message blaming the builder for the author's program. The
		// multiplier is already maintained here; not using it was the whole bug.
		fetches += multiplier;
		// THE STAGE'S cap, not the global one. This read IrValidator.MAX_FETCHES while the
		// validator had moved to a per-stage cap, which is the builder/validator divergence this
		// file exists to prevent -- and it would have surfaced exactly where the comment above says
		// it already surfaced once: build() refusing a program the builder accepted call-by-call,
		// with a message blaming the implementation for the author's program. At the animator
		// (fetch cap 0) every single tap would have taken that path.
		if (fetches > IrValidator.maxFetches(stage)) {
			throw new BuildException("program performs " + fetches + " fetches post-unroll, over"
					+ " this stage's cap of " + IrValidator.maxFetches(stage)
					+ "; a tap inside a loop of n costs n fetches");
		}
		return emit(OcslWire.OP_SAMPLE, OcslType.VEC4, tintDependent(uv.operand),
				slot, uv.operand);
	}

	// ---------------------------------------------------------------- output

	/**
	 * Write a stage property RELATIVELY — the value composes over the server-set base by the
	 * property's own rule. At a stage that does not compose, this is the only form and "relatively"
	 * is vacuous: the output is the value.
	 */
	public void out(int propertyId, Expr value) {
		emitOut(OcslWire.OP_OUT, propertyId, value);
	}

	/**
	 * Write a stage property ABSOLUTELY — {@code disp = value}, whatever the property's rule.
	 *
	 * ANIM-7's double-apply decision: this is the spelling for "go to T", which under a relative
	 * rule would otherwise have to be written {@code SUB(T, anim.x)} and would be indistinguishable
	 * from the double-apply bug. Refused at a stage whose outputs do not compose.
	 */
	public void outAbsolute(int propertyId, Expr value) {
		emitOut(OcslWire.OP_OUT_ABS, propertyId, value);
	}

	private void emitOut(byte opcode, int propertyId, Expr value) {
		if (built) {
			throw new BuildException("this builder has already produced a program");
		}
		// Mirrors IrValidator's gate, and the builder has to carry it: build() blames a
		// validator-refused program on "a defect in one of the two rather than in this program",
		// which would be a false accusation for an author who simply used the wrong form.
		if (opcode == OcslWire.OP_OUT_ABS && !SurfaceTable.composesOutputs(stage)) {
			throw new BuildException("OUT_ABS writes a value that replaces a server-set base, and"
					+ " stage " + (stage & 0xFF) + " has no base to replace; its output is the"
					+ " value, so use out()");
		}
		requireOwned(value);
		OcslType expected = SurfaceTable.propertyType(stage, propertyId);
		if (expected == null) {
			throw new BuildException("stage " + (stage & 0xFF) + " has no property " + propertyId);
		}
		if (value.type != expected) {
			throw new BuildException("OUT " + SurfaceTable.propertyName(stage, propertyId) + " expects "
					+ expected.display() + ", got " + value.type.display());
		}
		if (!tripStack.isEmpty()) {
			throw new BuildException("OUT inside a loop would write its property once per"
					+ " iteration; one writer per property per frame");
		}
		// BOTH forms, counted together: `out(x)` then `outAbsolute(x)` is two writers for one
		// displayed property, and a check that only saw OP_OUT would let the pair through to a
		// consumer with no rule for which one wins.
		for (int i = 0; i < ops.size(); i++) {
			if (OcslWire.isOut(ops.get(i).opcode) && ops.get(i).operand(0) == propertyId) {
				throw new BuildException("property " + SurfaceTable.propertyName(stage, propertyId)
						+ " is already written");
			}
		}
		// "Does this stage have tint at all" asked of the surface table rather than of a list of
		// stage constants, so a stage added later answers for itself.
		boolean stageHasTint = SurfaceTable.builtinType(stage, SurfaceTable.REG_TINT) != null;
		if (stageHasTint && !tintDependent(value.operand)) {
			// A WARNING, not a refusal, and the design says so: ignoring tint is legal and simply
			// disables node and group tinting for that material. Silence would be the wrong
			// answer -- the effect shows up as a shipped feature that quietly stopped working.
			warnings.add("this program's output has no data dependency on `tint`, so node and"
					+ " group tinting will have no effect on it");
		}
		ops.add(new IrOp(opcode, -1, propertyId, value.operand));
		charge(OcslWire.shapeOf(opcode).structuralCharge);
	}

	/** Build-time advisories. Empty for a program with nothing to say about it. */
	public List<String> warnings() {
		return new ArrayList<String>(warnings);
	}

	/** The structural count as accumulated while emitting — the number {@link #build} checks. */
	public long structuralCount() {
		return structural;
	}

	/**
	 * A5's frame layout, computed the way the validator computes it.
	 *
	 * The builder bounded the register COUNT (512, unreachable under a 256-op cap) and not the
	 * frame WIDTH (1024, reached at 254 vec4 registers — comfortably inside every cap the builder
	 * knew about). So a legal-looking program was refused inside build() by a message blaming the
	 * implementation. Registers are counted from the OPS rather than from the allocation list,
	 * because a retargeted fold leaves an allocated-but-never-written register that the layout
	 * correctly skips.
	 */
	private void checkFrameWidth() {
		int cursor = 0;
		for (int r = 0; r < SurfaceTable.WORKING_BASE; r++) {
			OcslType t = r < SurfaceTable.UNIFORM_BASE ? SurfaceTable.builtinType(stage, r)
					: (r - SurfaceTable.UNIFORM_BASE < uniformNames.size() ? OcslType.FLOAT : null);
			if (t != null) {
				cursor += t.width;
			}
		}
		boolean[] laidOut = new boolean[registerTypes.size()];
		for (int i = 0; i < ops.size(); i++) {
			int dst = ops.get(i).dst;
			int working = dst - SurfaceTable.WORKING_BASE;
			if (working >= 0 && working < laidOut.length && !laidOut[working]) {
				laidOut[working] = true;
				cursor += registerTypes.get(working).width;
			}
		}
		if (cursor > SurfaceTable.MAX_FRAME_WIDTH) {
			throw new BuildException("lays out a frame of " + cursor + " floats, over the cap of "
					+ SurfaceTable.MAX_FRAME_WIDTH);
		}
	}

	/**
	 * The pool in first-USE order, carrying only what the ops reference.
	 *
	 * Not first-MENTION order, and the difference is a real one. A literal written and then not
	 * used — {@code local x = 5} in a Lua program, or an expression the author abandoned — emits no
	 * op, so it is invisible in the instruction stream and yet would sit in the pool changing the
	 * blob's bytes. Two sources that compile to the identical instruction sequence would then have
	 * different content hashes and miss each other in the compile cache. Ordering by first use
	 * rather than by first mention closes the same gap from the other side: where in the source a
	 * literal was NAMED stops mattering, and only where it is USED does.
	 *
	 * This is canonicalization, not optimization, and the distinction is the one the eager-emission
	 * rule turns on: no instruction is added, removed or reordered here. The pool is made a pure
	 * function of the ops, which is what it always claimed to be.
	 */
	private float[][] canonicalPool() {
		int[] remap = new int[constants.size()];
		java.util.Arrays.fill(remap, -1);
		List<float[]> used = new ArrayList<float[]>();
		for (int i = 0; i < ops.size(); i++) {
			IrOp op = ops.get(i);
			OcslWire.Shape shape = OcslWire.shapeOf(op.opcode);
			for (int slot = 0; slot < shape.operandCount(); slot++) {
				if (shape.operandKinds[slot] != OcslWire.KIND_VALUE || !op.isConstant(slot)) {
					continue;
				}
				int old = op.index(slot);
				if (remap[old] < 0) {
					remap[old] = used.size();
					used.add(constants.get(old));
				}
			}
		}
		if (used.size() == constants.size()) {
			boolean identity = true;
			for (int i = 0; i < remap.length && identity; i++) {
				identity = remap[i] == i;
			}
			if (identity) {
				return used.toArray(new float[used.size()][]);
			}
		}
		for (int i = 0; i < ops.size(); i++) {
			IrOp op = ops.get(i);
			OcslWire.Shape shape = OcslWire.shapeOf(op.opcode);
			int[] operands = new int[op.operandCount()];
			boolean changed = false;
			for (int slot = 0; slot < operands.length; slot++) {
				operands[slot] = op.operand(slot);
				if (shape.operandKinds[slot] == OcslWire.KIND_VALUE && op.isConstant(slot)) {
					int moved = remap[op.index(slot)];
					operands[slot] = OcslWire.OPERAND_CONST_FLAG | moved;
					changed |= moved != op.index(slot);
				}
			}
			if (changed) {
				ops.set(i, new IrOp(op.opcode, op.dst, operands));
			}
		}
		return used.toArray(new float[used.size()][]);
	}

	/**
	 * Finish, validate, and CHECK THE TWO COUNTS AGREE.
	 *
	 * A2 says the builder and the validator must agree on the structural count, and names the
	 * failure that follows if they do not: builder acceptance and validator rejection diverging at
	 * 255 ops. Making that a comparison here means the divergence is impossible to ship rather than
	 * merely tested for — it would have to survive a program nobody ever builds.
	 */
	public IrProgram build() {
		if (built) {
			throw new BuildException("this builder has already produced a program");
		}
		if (!tripStack.isEmpty()) {
			throw new BuildException(tripStack.size() + " loop(s) left open");
		}
		checkFrameWidth();
		built = true;
		IrProgram program = new IrProgram(stage, canonicalPool(), ops, uniformNames,
				SurfaceTable.WORKING_BASE + registerTypes.size());
		IrValidator.Validated validated;
		try {
			validated = IrValidator.validate(program);
		} catch (ValidationException e) {
			// The builder type-checks every call, so reaching here means the two disagree about
			// what a legal program IS -- which is a defect in one of them, not in the caller's
			// program, and it says so rather than blaming the author.
			throw new BuildException("the builder accepted a program the validator refuses, which"
					+ " is a defect in one of the two rather than in this program: " + e.getMessage());
		}
		if (validated.structuralOps != structural) {
			throw new BuildException("the builder charged " + structural + " structural ops and the"
					+ " validator charges " + validated.structuralOps
					+ "; these must agree or the cap means two different things");
		}
		return program;
	}
}
