package opengpu.v2.ocsl;

/**
 * A value in a program being built: a register, a built-in, a uniform or a constant-pool entry,
 * carrying the type the builder inferred for it.
 *
 * Immutable and cheap. An {@code Expr} names something that already exists — every operation
 * emitted its op at the moment it was called — so holding one costs nothing and using it twice
 * costs nothing either. That is what makes {@code Expr} safe to pass around a Lua program without
 * the author needing a model of when work happens: the work already happened.
 *
 * <h2>Broadcast, and the splat the builder must not insert</h2>
 *
 * Every component-wise operation here accepts a float where a vector is expected and passes it
 * through as a float operand. It never inserts a {@code SPLAT}. Both spellings compute the same
 * value and charge differently — one op against two — so allowing the choice would fork the content
 * hash, which is the compile-cache key, on a difference no program can observe. This is the
 * obligation the 2026-08-12 amendment-4 re-opening took on in exchange for making
 * {@code mix(vec3, vec3, float)} a single instruction, and it is why P1 charges 22 rather than the
 * 23 originally committed.
 *
 * The REDUCING and fixed-shape operations — {@link #dot}, {@link #cross}, {@link #length},
 * {@link #distance}, {@link #normalize} — are strict, and deliberately so: broadcasting a scalar
 * into {@code dot} would silently answer a different question than the author asked.
 */
public final class Expr {

	final OcslBuilder owner;
	/** Encoded operand: a register index, or {@code OPERAND_CONST_FLAG | poolIndex}. */
	final int operand;
	public final OcslType type;

	Expr(OcslBuilder owner, int operand, OcslType type) {
		this.owner = owner;
		this.operand = operand;
		this.type = type;
	}

	// ---------------------------------------------------------------- component-wise

	public Expr add(Expr other) {
		return componentWise(OcslWire.OP_ADD, other);
	}

	public Expr sub(Expr other) {
		return componentWise(OcslWire.OP_SUB, other);
	}

	public Expr mul(Expr other) {
		return componentWise(OcslWire.OP_MUL, other);
	}

	public Expr div(Expr other) {
		return componentWise(OcslWire.OP_DIV, other);
	}

	public Expr mod(Expr other) {
		return componentWise(OcslWire.OP_MOD, other);
	}

	public Expr min(Expr other) {
		return componentWise(OcslWire.OP_MIN, other);
	}

	public Expr max(Expr other) {
		return componentWise(OcslWire.OP_MAX, other);
	}

	public Expr pow(Expr other) {
		return componentWise(OcslWire.OP_POW, other);
	}

	public Expr atan2(Expr x) {
		return componentWise(OcslWire.OP_ATAN2, x);
	}

	/** {@code step(edge, x)} — this expression is the EDGE, matching GLSL's argument order. */
	public Expr step(Expr x) {
		return componentWise(OcslWire.OP_STEP, x);
	}

	public Expr clamp(Expr lo, Expr hi) {
		return componentWise3(OcslWire.OP_CLAMP, lo, hi);
	}

	public Expr mix(Expr other, Expr t) {
		return componentWise3(OcslWire.OP_MIX, other, t);
	}

	/** {@code smoothstep(e0, e1, x)} — this expression is {@code e0}. */
	public Expr smoothstep(Expr e1, Expr x) {
		return componentWise3(OcslWire.OP_SMOOTHSTEP, e1, x);
	}

	// ---------------------------------------------------------------- unary

	public Expr neg() {
		return unary(OcslWire.OP_NEG);
	}

	public Expr abs() {
		return unary(OcslWire.OP_ABS);
	}

	public Expr floor() {
		return unary(OcslWire.OP_FLOOR);
	}

	public Expr fract() {
		return unary(OcslWire.OP_FRACT);
	}

	public Expr exp() {
		return unary(OcslWire.OP_EXP);
	}

	public Expr log() {
		return unary(OcslWire.OP_LOG);
	}

	public Expr sqrt() {
		return unary(OcslWire.OP_SQRT);
	}

	public Expr sin() {
		return unary(OcslWire.OP_SIN);
	}

	public Expr cos() {
		return unary(OcslWire.OP_COS);
	}

	// ---------------------------------------------------------------- reducing, strict

	public Expr dot(Expr other) {
		return reducing(OcslWire.OP_DOT, other, OcslType.FLOAT);
	}

	public Expr distance(Expr other) {
		return reducing(OcslWire.OP_DISTANCE, other, OcslType.FLOAT);
	}

	public Expr cross(Expr other) {
		if (type != OcslType.VEC3) {
			throw new OcslBuilder.BuildException("cross takes vec3, got " + type.display());
		}
		return reducing(OcslWire.OP_CROSS, other, OcslType.VEC3);
	}

	public Expr length() {
		numeric("length");
		return owner.emit(OcslWire.OP_LENGTH, OcslType.FLOAT, owner.tintDependent(operand), operand);
	}

	public Expr normalize() {
		numeric("normalize");
		if (type == OcslType.FLOAT) {
			throw new OcslBuilder.BuildException("normalize takes a vector, got float");
		}
		return owner.emit(OcslWire.OP_NORMALIZE, type, owner.tintDependent(operand), operand);
	}

	// ---------------------------------------------------------------- comparison and boolean
	// FUNCTION-FORM, permanently: Lua coerces comparison metamethods to booleans, so `a < b` in the
	// shipped library could never return an expression object. The Java surface matches rather than
	// offering operators the Lua layer cannot mirror.

	public Expr lt(Expr other) {
		return compare(OcslWire.OP_LT, other);
	}

	public Expr le(Expr other) {
		return compare(OcslWire.OP_LE, other);
	}

	public Expr eq(Expr other) {
		return compare(OcslWire.OP_EQ, other);
	}

	public Expr band(Expr other) {
		return bool(OcslWire.OP_BAND, other);
	}

	public Expr bor(Expr other) {
		return bool(OcslWire.OP_BOR, other);
	}

	public Expr bnot() {
		if (type != OcslType.BOOL) {
			throw new OcslBuilder.BuildException("bnot takes a bool, got " + type.display());
		}
		return owner.emit(OcslWire.OP_BNOT, OcslType.BOOL, owner.tintDependent(operand), operand);
	}

	// ---------------------------------------------------------------- shape

	public Expr x() {
		return swizzle(0);
	}

	public Expr y() {
		return swizzle(1);
	}

	public Expr z() {
		return swizzle(2);
	}

	public Expr w() {
		return swizzle(3);
	}

	/** {@code .yx}, {@code .xyz}, {@code .wwww} — 1..4 components drawn from {@code xyzw}. */
	public Expr swz(String components) {
		int[] indices = new int[components.length()];
		for (int i = 0; i < components.length(); i++) {
			int c = "xyzw".indexOf(components.charAt(i));
			if (c < 0) {
				throw new OcslBuilder.BuildException("swizzle component '" + components.charAt(i)
						+ "' is not one of x, y, z, w");
			}
			indices[i] = c;
		}
		return swizzle(indices);
	}

	/**
	 * Widen a float to a vector.
	 *
	 * The explicit splat, and the ONLY one the builder emits. Component-wise arithmetic broadcasts
	 * instead, so reaching for this means the author genuinely wants a vector value — a vec3
	 * assembled from a float uniform, say — rather than a wider operand to an op that would have
	 * taken the float as it stood.
	 */
	public Expr splat(int width) {
		if (type != OcslType.FLOAT) {
			throw new OcslBuilder.BuildException("splat widens a float, got " + type.display());
		}
		if (width < 2 || width > 4) {
			throw new OcslBuilder.BuildException("splat produces a vec2..vec4, got width " + width);
		}
		return owner.emit(OcslWire.OP_SPLAT, OcslType.ofWidth(width), owner.tintDependent(operand),
				operand, width);
	}

	// ---------------------------------------------------------------- internals

	private Expr swizzle(int... indices) {
		numeric("swizzle");
		if (indices.length < 1 || indices.length > 4) {
			throw new OcslBuilder.BuildException("a swizzle selects 1..4 components, got "
					+ indices.length);
		}
		for (int i = 0; i < indices.length; i++) {
			if (indices[i] >= type.width) {
				throw new OcslBuilder.BuildException("component " + "xyzw".charAt(indices[i])
						+ " does not exist on " + type.display());
			}
		}
		return owner.emit(OcslWire.OP_SWZ, OcslType.ofWidth(indices.length),
				owner.tintDependent(operand), operand, OcslWire.packSwizzle(indices));
	}

	/**
	 * The broadcast rule: a float operand keeps its width, and the result takes the vector's.
	 *
	 * Two vectors of DIFFERENT widths are a type error rather than a broadcast — there is no
	 * defensible answer for {@code vec2 + vec3}, and guessing one would make a typo into a program.
	 */
	private Expr componentWise(byte opcode, Expr other) {
		check(other);
		numeric("this operation");
		other.numeric("this operation");
		OcslType result = combine(type, other.type);
		return owner.emit(opcode, result,
				owner.tintDependent(operand) || owner.tintDependent(other.operand),
				operand, other.operand);
	}

	private Expr componentWise3(byte opcode, Expr b, Expr c) {
		check(b);
		check(c);
		numeric("this operation");
		b.numeric("this operation");
		c.numeric("this operation");
		OcslType result = combine(combine(type, b.type), c.type);
		return owner.emit(opcode, result,
				owner.tintDependent(operand) || owner.tintDependent(b.operand)
						|| owner.tintDependent(c.operand),
				operand, b.operand, c.operand);
	}

	private static OcslType combine(OcslType a, OcslType b) {
		if (a == b) {
			return a;
		}
		if (a == OcslType.FLOAT) {
			return b;
		}
		if (b == OcslType.FLOAT) {
			return a;
		}
		throw new OcslBuilder.BuildException("component-wise operands are " + a.display() + " and "
				+ b.display() + "; a float broadcasts, two different vector widths do not");
	}

	private Expr unary(byte opcode) {
		numeric("this operation");
		return owner.emit(opcode, type, owner.tintDependent(operand), operand);
	}

	private Expr reducing(byte opcode, Expr other, OcslType result) {
		check(other);
		numeric("this operation");
		if (type != other.type) {
			// No broadcast here, and the strictness is the point: these ops reduce over components,
			// so a silently widened operand answers a different question.
			throw new OcslBuilder.BuildException("this operation takes two operands of the same"
					+ " type, got " + type.display() + " and " + other.type.display()
					+ "; it reduces over components, so a float does not broadcast into it");
		}
		return owner.emit(opcode, result,
				owner.tintDependent(operand) || owner.tintDependent(other.operand),
				operand, other.operand);
	}

	private Expr compare(byte opcode, Expr other) {
		check(other);
		if (type != OcslType.FLOAT || other.type != OcslType.FLOAT) {
			throw new OcslBuilder.BuildException("comparisons take floats, got " + type.display()
					+ " and " + other.type.display());
		}
		// Dependency flows through a COMPARISON. It was hardcoded false here, which made the tint
		// advisory fire on programs whose output demonstrably moves with tint through a select.
		return owner.emit(opcode, OcslType.BOOL,
				owner.tintDependent(operand) || owner.tintDependent(other.operand),
				operand, other.operand);
	}

	private Expr bool(byte opcode, Expr other) {
		check(other);
		if (type != OcslType.BOOL || other.type != OcslType.BOOL) {
			throw new OcslBuilder.BuildException("boolean combination takes bools, got "
					+ type.display() + " and " + other.type.display());
		}
		return owner.emit(opcode, OcslType.BOOL,
				owner.tintDependent(operand) || owner.tintDependent(other.operand),
				operand, other.operand);
	}

	private void numeric(String what) {
		if (type == OcslType.BOOL) {
			// A bool is one float slot holding exactly 0.0 or 1.0, produced only by the six bool
			// ops. Letting arithmetic touch one would put a non-canonical value in a bool slot,
			// which is precisely what the type system exists to prevent.
			throw new OcslBuilder.BuildException(what + " takes a numeric value; a bool reaches"
					+ " arithmetic only through select");
		}
	}

	private void check(Expr other) {
		if (other == null || other.owner != owner) {
			throw new OcslBuilder.BuildException("expression belongs to a different builder");
		}
	}
}
