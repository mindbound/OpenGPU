package opengpu.v2.ocsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Replays the frozen golden vectors against the live {@link OcslMath}.
 *
 * THIS TEST CANNOT WRITE THE FILE. It reads {@code /ocsl/golden-vectors.txt} from the classpath,
 * compares bit pattern against bit pattern, and fails if the file is missing rather than falling
 * back to generating one. That asymmetry is the whole design: an "update expectations" mode would
 * make the first response to a red build a regeneration, and the suite would pin nothing at all.
 *
 * What it is FOR, beyond catching a regression: these values are the contract a second
 * implementation is held to. Stage D's GLSL backend and any future bytecode tier are conformant
 * exactly insofar as they reproduce this file, and A4 pinned {@code strictfp} + StrictMath-only
 * transcendentals + narrow-once precisely so the file is a property of OCSL rather than of the
 * machine that generated it. The cross-architecture claim is checked here too — see
 * {@link #everyValueIsReproducibleOffThisMachine}.
 */
public class OcslGoldenTest {

	private static final class Row {
		final int line;
		final String op;
		final float[] args;
		final float[] expected;

		Row(int line, String op, float[] args, float[] expected) {
			this.line = line;
			this.op = op;
			this.args = args;
			this.expected = expected;
		}
	}

	private static List<Row> load() throws Exception {
		InputStream in = OcslGoldenTest.class.getResourceAsStream(OcslGolden.RESOURCE);
		assertNotNull("golden vector file " + OcslGolden.RESOURCE + " is missing from the test"
				+ " resources; it is checked in, and this test does not generate it -- run"
				+ " OcslGoldenGenerator and review the diff", in);
		List<Row> rows = new ArrayList<Row>();
		BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
		try {
			String text;
			int lineNumber = 0;
			while ((text = r.readLine()) != null) {
				lineNumber++;
				int comment = text.indexOf(';');
				if (comment >= 0) {
					text = text.substring(0, comment);
				}
				text = text.trim();
				if (text.isEmpty() || text.startsWith("#")) {
					continue;
				}
				String[] parts = text.split("\\s+");
				int arrow = -1;
				for (int i = 0; i < parts.length; i++) {
					if ("->".equals(parts[i])) {
						arrow = i;
					}
				}
				assertTrue("line " + lineNumber + " has no '->'", arrow > 0);
				float[] args = new float[arrow - 1];
				for (int i = 0; i < args.length; i++) {
					args[i] = OcslGolden.unhex(parts[i + 1]);
				}
				float[] expected = new float[parts.length - arrow - 1];
				for (int i = 0; i < expected.length; i++) {
					expected[i] = OcslGolden.unhex(parts[arrow + 1 + i]);
				}
				rows.add(new Row(lineNumber, parts[0], args, expected));
			}
		} finally {
			r.close();
		}
		return rows;
	}

	@Test
	public void everyFrozenVectorStillHolds() throws Exception {
		List<Row> rows = load();
		List<OcslGolden.Case> cases = OcslGolden.cases();
		assertEquals("the file must carry every case the list defines; a case added to"
				+ " OcslGolden.cases() without regenerating leaves it unpinned",
				cases.size(), rows.size());

		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			OcslGolden.Case expectedCase = cases.get(i);
			// THE INPUTS COME FROM THE CASE LIST, the expected outputs from the file. An earlier
			// version re-evaluated using the args parsed from the FILE, which made the file merely
			// self-consistent: any row could be swapped for another of the same op, or its
			// arguments gutted to zeros with a matching result, and every assertion still passed.
			// 126 vectors could have been collapsed to 29. The split this suite claims -- case
			// list in code, values in data -- only exists if the code supplies the inputs.
			assertEquals("line " + row.line + ": " + row.op + " argument count",
					expectedCase.args.length, row.args.length);
			for (int k = 0; k < expectedCase.args.length; k++) {
				assertEquals("line " + row.line + ": " + row.op + " argument " + k
						+ " does not match the case list; the file's inputs are not free to drift",
						OcslGolden.hex(expectedCase.args[k]), OcslGolden.hex(row.args[k]));
			}
			float[] actual = OcslGolden.evaluate(expectedCase.op, expectedCase.args);
			assertEquals("line " + row.line + ": " + row.op + " result width",
					row.expected.length, actual.length);
			for (int c = 0; c < actual.length; c++) {
				// Compared as BIT PATTERNS, not as floats. `assertEquals(float, float, 0f)` treats
				// every NaN as equal to every other and 0.0 as equal to -0.0, so it would pass on
				// exactly the distinctions the domain table was written to make.
				assertEquals("line " + row.line + ": " + row.op + " component " + c
						+ " -- expected " + row.expected[c] + " (" + OcslGolden.hex(row.expected[c])
						+ "), got " + actual[c] + " (" + OcslGolden.hex(actual[c]) + ")",
						OcslGolden.hex(row.expected[c]), OcslGolden.hex(actual[c]));
			}
		}
	}

	@Test
	public void theFileOrderMatchesTheCaseListOrder() throws Exception {
		// The file is compared line by line, so a reordered case list would silently pair case N
		// with vector N of a different case and still be the right LENGTH. Pinning the op names in
		// order makes that mismatch a failure instead of a wrong comparison that happens to pass.
		List<Row> rows = load();
		List<OcslGolden.Case> cases = OcslGolden.cases();
		for (int i = 0; i < rows.size(); i++) {
			assertEquals("vector " + i + " is a different op than case " + i,
					cases.get(i).op, rows.get(i).op);
		}
	}

	@Test
	public void everyValueIsReproducibleOffThisMachine() throws Exception {
		// A4's claim is that these values are a property of OCSL, not of the host: strictfp
		// evaluation, StrictMath-only transcendentals, one narrowing at the write. This asserts the
		// half that a CI runner on another architecture would otherwise be the first to discover.
		//
		// Not a tautology: StrictMath is fdlibm by specification and bit-identical everywhere,
		// while java.lang.Math is permitted a ulp of slack and intrinsifies per architecture. If
		// someone swaps one for the other, this fails on any host where they differ -- and on a
		// host where they agree the golden file itself would silently encode a machine-specific
		// value, which is the failure this pins against.
		for (float x : new float[] { 0.5f, 2.25f, -1.75f, 100.5f, 3.14159f }) {
			assertEquals("sin", OcslGolden.hex((float) StrictMath.sin(x)),
					OcslGolden.hex(OcslMath.sin(x)));
			assertEquals("cos", OcslGolden.hex((float) StrictMath.cos(x)),
					OcslGolden.hex(OcslMath.cos(x)));
		}
		assertEquals("exp", OcslGolden.hex((float) StrictMath.exp(2.0)),
				OcslGolden.hex(OcslMath.exp(2f)));
		assertEquals("log", OcslGolden.hex((float) StrictMath.log(2.0)),
				OcslGolden.hex(OcslMath.log(2f)));
		// ACC_STRICT IS PER METHOD, not per class -- javac puts it on each method and leaves the
		// class flags alone, so asking OcslMath.class was asking the wrong object. Verified
		// against the real bytecode: `javap -v` on the built class shows major version 52 with
		// ACC_STRICT on the methods.
		//
		// The check matters precisely because of what this project SHIPS. Tests run on JDK 21,
		// where all floating point is strict and the flag would be moot; the game runs Java 8,
		// where strictness is opt-in and a 32-bit x87 host double-rounds without it. So the thing
		// worth asserting is a property of the emitted bytecode, and it is checkable from any JVM.
		int checked = 0;
		java.lang.reflect.Method[] methods = OcslMath.class.getDeclaredMethods();
		for (int i = 0; i < methods.length; i++) {
			int mods = methods[i].getModifiers();
			if (!java.lang.reflect.Modifier.isStatic(mods)) {
				continue;
			}
			assertTrue("OcslMath." + methods[i].getName() + " is not strictfp; on the Java 8 JVM"
					+ " the game runs, that lets an x87 host double-round an intermediate and the"
					+ " golden vectors stop being a property of OCSL",
					java.lang.reflect.Modifier.isStrict(mods));
			checked++;
		}
		assertTrue("found no methods to check, so this assertion proved nothing", checked > 20);
	}

	@Test
	public void everyArithmeticMethodOnOcslMathHasAVector() throws Exception {
		// DERIVED FROM OcslMath BY REFLECTION, not from a hand-kept list. The list version could
		// not do what its own comment claimed: adding a method with no vector was caught by
		// nothing, because the list was a String[] literal that a new method never touches. One
		// had already slipped through -- `san`, the ingress rule whose substitute-vs-produce
		// distinction was the corrected defect of 2026-08-12.
		List<Row> rows = load();
		java.lang.reflect.Method[] methods = OcslMath.class.getDeclaredMethods();
		StringBuilder missing = new StringBuilder();
		for (int i = 0; i < methods.length; i++) {
			java.lang.reflect.Method m = methods[i];
			int mods = m.getModifiers();
			if (!java.lang.reflect.Modifier.isPublic(mods)
					|| !java.lang.reflect.Modifier.isStatic(mods)) {
				continue;
			}
			// `finite` is a predicate, not an arithmetic op; every other public method computes a
			// value the vectors are supposed to freeze.
			if (m.getName().equals("finite")) {
				continue;
			}
			boolean covered = false;
			for (int j = 0; j < rows.size() && !covered; j++) {
				covered = OcslGolden.methodFor(rows.get(j).op).equals(m.getName());
			}
			if (!covered) {
				missing.append(missing.length() == 0 ? "" : ", ").append(m.getName());
			}
		}
		assertEquals("public arithmetic on OcslMath with no golden vector", "",
				missing.toString());
	}
}
