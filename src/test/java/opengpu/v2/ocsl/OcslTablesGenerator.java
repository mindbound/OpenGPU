package opengpu.v2.ocsl;

/**
 * Writes the freeze-gated table file. Run deliberately; never from a test.
 *
 * <pre>
 *   ./gradlew ocslTables
 * </pre>
 *
 * Owns the file handle rather than printing for a shell redirect, for the reason
 * {@link OcslGoldenGenerator} records: FPGradle prints a version notice to stdout that {@code -q}
 * does not suppress, and it lands in the middle of any redirected artifact.
 *
 * Separate from the checker on purpose, and for the same reason: if {@link OcslTablesTest} could
 * regenerate its own expectations, the first response to a red build would be to regenerate, and the
 * suite would pin nothing.
 */
public final class OcslTablesGenerator {
	private OcslTablesGenerator() {}

	public static void main(String[] args) throws java.io.IOException {
		if (args.length != 1) {
			throw new IllegalArgumentException("usage: OcslTablesGenerator <output-file>");
		}
		// RENDERED BEFORE THE FILE IS OPENED, and the order is the whole point.
		// `new FileOutputStream(path)` truncates immediately, so rendering inside the try left any
		// throw from render() with a ZERO-BYTE contract file. Measured: adding an operand kind makes
		// `kindName` throw by design -- the loud guard doing its job -- and the frozen file went
		// 3308 bytes -> 0. The intended workflow is "read the diff and decide which side is wrong",
		// and that workflow is destroyed at exactly the moment the guard fires, because the diff
		// becomes the entire file. OcslGoldenGenerator builds its whole string first; this copied its
		// file-handle lesson and dropped its ordering.
		String text = OcslTables.render();
		java.io.Writer w = new java.io.OutputStreamWriter(
				new java.io.FileOutputStream(args[0]), "UTF-8");
		try {
			w.write(text);
		} finally {
			w.close();
		}
		System.out.println("wrote " + args[0]);
	}
}
