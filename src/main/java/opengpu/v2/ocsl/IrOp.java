package opengpu.v2.ocsl;

import java.util.Arrays;

/**
 * One decoded IR instruction: an opcode, an optional destination register, and operands whose
 * meaning comes from {@link OcslWire#shapeOf} rather than from position guessing.
 *
 * Immutable, because a decoded program is re-validated by every client independently and nothing
 * downstream may mutate what the content hash was taken over.
 */
public final class IrOp {
	public final byte opcode;
	/** Destination register index, or -1 for the ops that write none (OUT, ENDFOR). */
	public final int dst;
	private final int[] operands;

	public IrOp(byte opcode, int dst, int... operands) {
		this.opcode = opcode;
		this.dst = dst;
		this.operands = operands.clone();
	}

	public int operandCount() {
		return operands.length;
	}

	public int operand(int i) {
		return operands[i];
	}

	/** True when this operand names the constant pool rather than a register. */
	public boolean isConstant(int i) {
		return (operands[i] & OcslWire.OPERAND_CONST_FLAG) != 0;
	}

	/** The register or constant-pool index this operand names, with the tag bit stripped. */
	public int index(int i) {
		return operands[i] & OcslWire.OPERAND_INDEX_MASK;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof IrOp))
			return false;
		IrOp other = (IrOp) o;
		return opcode == other.opcode && dst == other.dst && Arrays.equals(operands, other.operands);
	}

	@Override
	public int hashCode() {
		return (opcode * 31 + dst) * 31 + Arrays.hashCode(operands);
	}

	@Override
	public String toString() {
		OcslWire.Shape shape = OcslWire.shapeOf(opcode);
		StringBuilder sb = new StringBuilder(shape != null ? shape.name : ("OP" + (opcode & 0xFF)));
		if (dst >= 0) {
			sb.append(" r").append(dst);
		}
		for (int i = 0; i < operands.length; i++) {
			sb.append(i == 0 && dst < 0 ? " " : ", ");
			int kind = shape != null && i < shape.operandKinds.length ? shape.operandKinds[i]
					: OcslWire.KIND_VALUE;
			switch (kind) {
				case OcslWire.KIND_VALUE:
					sb.append(isConstant(i) ? "k" : "r").append(index(i));
					break;
				case OcslWire.KIND_PROPERTY:
					sb.append("prop").append(operands[i]);
					break;
				case OcslWire.KIND_SLOT:
					sb.append("slot").append(operands[i]);
					break;
				case OcslWire.KIND_SWIZZLE: {
					sb.append('.');
					int len = OcslWire.swizzleLength(operands[i]);
					for (int c = 0; c < len; c++) {
						sb.append("xyzw".charAt(OcslWire.swizzleComponent(operands[i], c)));
					}
					break;
				}
				default:
					sb.append(operands[i]);
			}
		}
		return sb.toString();
	}
}
