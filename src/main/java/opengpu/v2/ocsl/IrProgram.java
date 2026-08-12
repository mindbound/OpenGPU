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
	private final float[] constants;
	private final List<IrOp> ops;
	private final List<String> names;
	/** Register count the blob declares; the validator checks every write against it. */
	public final int declaredRegisters;

	public IrProgram(byte stage, float[] constants, List<IrOp> ops, List<String> names,
			int declaredRegisters) {
		this.stage = stage;
		this.constants = constants.clone();
		this.ops = Collections.unmodifiableList(new ArrayList<IrOp>(ops));
		this.names = Collections.unmodifiableList(new ArrayList<String>(names));
		this.declaredRegisters = declaredRegisters;
	}

	public int constantCount() {
		return constants.length;
	}

	public float constant(int i) {
		return constants[i];
	}

	public List<IrOp> ops() {
		return ops;
	}

	public List<String> names() {
		return names;
	}

	/**
	 * The STRUCTURAL count — the currency the ~256 acceptance cap is stated against.
	 *
	 * Every executed instruction charges 1, swizzles and constructors included, OUT included;
	 * FOR/ENDFOR are encoding structure and charge 0. Body ops charge once per iteration, because
	 * the caps are post-unroll dynamic counts — which is unrolling-INVARIANT, so an interpreting
	 * CPU VM and unrolled GLSL codegen compute the same number for the same blob. That invariance
	 * is the whole reason the cap can be checked once, at validation, and believed by both.
	 *
	 * This is NOT the weighted cost. The weight table (guarded ops charging their lowered cost,
	 * swizzles charging 0) prices the fill budget and the bake op-pixel product. Two quantities,
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
