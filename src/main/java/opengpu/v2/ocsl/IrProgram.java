package opengpu.v2.ocsl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A decoded OCSL program: stage, constant pool, op stream, and the CPU-side name table.
 *
 * NO IR-CARRIED STRING EVER REACHES EMITTED SHADER TEXT. The names here exist so a Lua program can
 * say {@code setUniform("strength", …)}; codegen uses positional names and keeps the name→index
 * mapping host-side. That is what closes GLSL-text injection through the codegen back door, and it
 * is why the decoder caps this table's charset, length and count.
 */
public final class IrProgram {
	public final byte stage;
	/**
	 * The constant pool, one entry per constant, each 1..4 components wide.
	 *
	 * TYPED, not a flat float array. Two of the four acceptance programs need a vector constant
	 * that costs no op — the blur's fold is seeded with `vec4(0,0,0,0)` riding the FOR encoding,
	 * and the dissolve carries a `vec3` tint triple — and a scalar-only pool forces both to be
	 * built with a constructor, which charges an op neither committed count includes. Width is the
	 * type: there is no vec1, so a 1-wide entry is a float.
	 */
	private final float[][] constants;
	private final List<IrOp> ops;
	private final List<String> names;
	/** Register count the blob declares; the validator checks every write against it. */
	public final int declaredRegisters;

	/** Convenience for the common all-scalar pool. */
	public IrProgram(byte stage, float[] scalarConstants, List<IrOp> ops, List<String> names,
			int declaredRegisters) {
		this(stage, widen(scalarConstants), ops, names, declaredRegisters);
	}

	public IrProgram(byte stage, float[][] constants, List<IrOp> ops, List<String> names,
			int declaredRegisters) {
		this.stage = stage;
		this.constants = new float[constants.length][];
		for (int i = 0; i < constants.length; i++) {
			this.constants[i] = constants[i].clone();
		}
		this.ops = Collections.unmodifiableList(new ArrayList<IrOp>(ops));
		this.names = Collections.unmodifiableList(new ArrayList<String>(names));
		this.declaredRegisters = declaredRegisters;
	}

	private static float[][] widen(float[] scalars) {
		float[][] out = new float[scalars.length][];
		for (int i = 0; i < scalars.length; i++) {
			out[i] = new float[] { scalars[i] };
		}
		return out;
	}

	public int constantCount() {
		return constants.length;
	}

	/** Components in constant {@code i} — 1 for a float, 2..4 for a vector. */
	public int constantWidth(int i) {
		return constants[i].length;
	}

	public float constantComponent(int i, int component) {
		return constants[i][component];
	}

	/** The type a constant-pool reference contributes to inference. */
	public OcslType constantType(int i) {
		return OcslType.ofWidth(constants[i].length);
	}

	/**
	 * The property ids this program writes — its OWNERSHIP DECLARATION, ascending and distinct.
	 *
	 * Derived from the ops every time rather than stored anywhere, and that is the decision
	 * (3.2, 2026-08-19) rather than an oversight. DESIGN struck {@code ownedProps} because "the
	 * IR's {@code OUT} set is the sole ownership declaration, because a second list is two sources
	 * of truth"; a cached mask on {@code ProgramInfo} would be exactly such a second copy, and one
	 * no decoder could check without decoding the blob anyway. The only caller that does not
	 * already hold a decoded program is the attach path, which needs this once per attach to the
	 * display node — a setup-time call, not a per-frame one. Every client that RUNS a program
	 * decodes it for {@link OcslVm} regardless, so nothing on the render path pays for this.
	 *
	 * Both output forms count: {@code OUT} and {@code OUT_ABS} declare the same ownership and
	 * differ only in how the value composes, which is why this asks {@link OcslWire#isOut} rather
	 * than naming one opcode — the mistake that method's own javadoc exists to prevent.
	 */
	public int[] outProperties() {
		// A set, though a VALIDATED program can never contain a duplicate: both OcslBuilder and
		// IrValidator refuse a second OUT to one property ("already written"). This is
		// defence-in-depth for a hand-built program, not a rule any caller leans on.
		java.util.TreeSet<Integer> found = new java.util.TreeSet<Integer>();
		for (IrOp op : ops) {
			if (OcslWire.isOut(op.opcode)) {
				// The property is operand 0 for both forms — the same read IrValidator makes when
				// it type-checks the write.
				found.add(Integer.valueOf(op.operand(0)));
			}
		}
		int[] out = new int[found.size()];
		int i = 0;
		for (Integer p : found) {
			out[i++] = p.intValue();
		}
		return out;
	}

	public List<IrOp> ops() {
		return ops;
	}

	public List<String> names() {
		return names;
	}

	/**
	 * The STRUCTURAL count — the currency the per-stage acceptance caps are stated against
	 * (256 for the pixel family, 512 for the animator since 2026-08-21; IrValidator's
	 * maxStructuralOps is the record).
	 *
	 * Every executed instruction charges 1, swizzles and constructors included, OUT included;
	 * FOR/ENDFOR are encoding structure and charge 0. Body ops charge once per iteration, because
	 * the caps are post-unroll dynamic counts — which is unrolling-INVARIANT, so an interpreting
	 * CPU VM and unrolled GLSL codegen compute the same number for the same blob. That invariance
	 * is the whole reason the cap can be checked once, at validation, and believed by both.
	 *
	 * This is NOT the weighted cost. The weight table prices the fill budget and the bake
	 * op-pixel product — and NOT the animator budget, which prices measured nanoseconds because
	 * the CPU column leaves 27 of 48 opcodes unpriced. "Swizzles charging 0" described the GPU
	 * column only; the measured CPU column puts SWZ at 0.62, a real store on a flat float[]
	 * frame. Two quantities,
	 * two names, one of them the cap — resolving a conflict where both claimed it and gave
	 * different answers.
	 *
	 * NEVER FAILS OPEN. Overflow and malformed loop structure both return {@link Long#MAX_VALUE},
	 * not a sentinel, because the only consumer of this number is a cap comparison and every
	 * plausible spelling of that is {@code count > cap}. An earlier draft returned -1 for both,
	 * which made {@code -1 > 256} false and would have ACCEPTED a program whose real post-unroll
	 * count was billions — the one place the cap is decided, failing open. Saturating means an
	 * uncountable program is rejected by any cap; the validator still reports *which* structural
	 * error it was, in its own words.
	 */
	public long structuralCount() {
		long total = 0;
		// One multiplier per open loop, so nesting multiplies.
		List<Integer> tripStack = new ArrayList<Integer>();
		long multiplier = 1;
		for (IrOp op : ops) {
			OcslWire.Shape shape = OcslWire.shapeOf(op.opcode);
			if (shape == null) {
				return Long.MAX_VALUE;
			}
			if (op.opcode == OcslWire.OP_FOR) {
				int trips = op.operand(0);
				if (trips < 0) {
					return Long.MAX_VALUE;
				}
				tripStack.add(Integer.valueOf(trips));
				multiplier *= trips;
				if (multiplier > Integer.MAX_VALUE) {
					return Long.MAX_VALUE;
				}
			} else if (op.opcode == OcslWire.OP_ENDFOR) {
				if (tripStack.isEmpty()) {
					return Long.MAX_VALUE;
				}
				// Recomputed rather than divided back out: a zero-trip loop makes the multiplier 0
				// and division would not restore it (and would divide by zero).
				tripStack.remove(tripStack.size() - 1);
				multiplier = recomputeMultiplier(tripStack);
			} else {
				total += multiplier * shape.structuralCharge;
				if (total < 0) {
					return Long.MAX_VALUE;
				}
			}
		}
		return tripStack.isEmpty() ? total : Long.MAX_VALUE;
	}

	private static long recomputeMultiplier(List<Integer> tripStack) {
		long m = 1;
		for (Integer t : tripStack) {
			m *= t.intValue();
		}
		return m;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("stage=").append(stage & 0xFF)
				.append(" regs=").append(declaredRegisters)
				.append(" consts=").append(constants.length)
				.append(" structural=").append(structuralCount()).append('\n');
		for (IrOp op : ops) {
			sb.append("  ").append(op).append('\n');
		}
		return sb.toString();
	}
}
