package opengpu.v2.ocsl;

/**
 * A program was well-formed but not acceptable: a type mismatch, a stage-inapplicable read, a
 * missing or duplicated output, a register read before it was written, or a cap exceeded.
 *
 * IR-INDEXED ON PURPOSE. The builder typechecks at authoring time and reports at the exact Lua
 * call site with a normal traceback — that is the surface a program author sees. Server validation
 * stays authoritative, and its errors are indexed by op so that a library author holding a blob
 * (or a client refusing one) can say precisely which instruction failed. The two are meant to
 * agree; they are exercised by the same shared vectors.
 */
public class ValidationException extends Exception {
	/** Index into the op stream, or -1 for whole-program failures (a missing OUT, a cap). */
	public final int opIndex;

	public ValidationException(int opIndex, String message) {
		super(opIndex >= 0 ? ("op " + opIndex + ": " + message) : message);
		this.opIndex = opIndex;
	}
}
