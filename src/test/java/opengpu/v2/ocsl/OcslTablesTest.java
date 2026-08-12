package opengpu.v2.ocsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Holds the three freeze-gated tables to their frozen file.
 *
 * The roadmap names these as gating the format freeze; an audit on 2026-08-12 found two of the three
 * existed only as prose and Java, so nothing could have caught a reserved id changing its number.
 * This is the gate. It only ever READS — regeneration is {@link OcslTablesGenerator}, a separate
 * main, so a red build cannot be answered by re-recording whatever the code now does.
 *
 * When this fails, read the diff and decide which side is wrong. Usually the code. When it is
 * genuinely the file — a reservation was deliberately taken up, a cap deliberately raised — the
 * regenerated diff shows exactly which contract moved, which is the review this obligation wanted.
 */
public class OcslTablesTest {

	private static final String RESOURCE = "/ocsl/surface-tables.txt";

	@Test
	public void theFrozenTablesStillDescribeTheCode() throws Exception {
		assertEquals("the freeze-gated tables have drifted from the code that implements them;"
				+ " read the diff, then either fix the code or regenerate with ./gradlew ocslTables",
				read(), OcslTables.render());
	}

	@Test
	public void theFileIsPresentAndNotEmpty() throws Exception {
		// Absence must FAIL rather than skip. A missing-file fallback would turn the one gate these
		// tables have into a no-op the day someone deletes the resource.
		String frozen = read();
		assertTrue("the frozen table file is empty", frozen.length() > 0);
		assertTrue("it should carry the generator's warning header", frozen.contains("GENERATED"));
	}

	/**
	 * No two built-in registers may share an id — the collision the reservation discipline exists to
	 * prevent, checked against the code rather than against the file.
	 *
	 * Deliberately NOT a comparison with the frozen text: if the ids collided, the generator would
	 * happily render the collision and the frozen file would agree with it. A frozen artifact pins
	 * change; it cannot notice that what was frozen was wrong to begin with.
	 */
	@Test
	public void noReadableBuiltInLandsInsideAReservedBlock() throws Exception {
		// SCANNED, not listed. The first version of this test iterated its own private copy of the
		// id list -- the same list the renderer had -- so adding a register at id 10, colliding with
		// the light block and readable at the post stage, rendered byte-identical AND passed the
		// test whose javadoc said it checked "the collision the reservation discipline exists to
		// prevent". A coverage check that reads a hand-kept list checks the list, not the code.
		// OcslGoldenTest learned this already and uses reflection for the same reason.
		Set<Integer> readable = new HashSet<Integer>();
		for (int s = 0; s <= 255; s++) {
			byte stage = (byte) s;
			if (!OcslWire.isKnownStage(stage)) {
				continue;
			}
			for (int reg = 0; reg < SurfaceTable.UNIFORM_BASE; reg++) {
				if (SurfaceTable.builtinType(stage, reg) != null) {
					readable.add(Integer.valueOf(reg));
				}
			}
		}
		assertTrue("expected the stages to expose built-ins at all", readable.size() > 4);
		for (Integer reg : readable) {
			int r = reg.intValue();
			assertTrue("register " + r + " (" + SurfaceTable.builtinName(r) + ") is readable but"
					+ " sits inside the light block reserved at " + SurfaceTable.REG_LIGHT_BASE,
					r < SurfaceTable.REG_LIGHT_BASE || r >= SurfaceTable.REG_LIGHT_LIMIT);
			assertTrue("register " + r + " (" + SurfaceTable.builtinName(r) + ") is readable but"
					+ " sits inside the animator block reserved at "
					+ SurfaceTable.REG_ANIMATOR_BASE,
					r < SurfaceTable.REG_ANIMATOR_BASE || r >= SurfaceTable.REG_ANIMATOR_LIMIT);
			assertTrue("register " + r + " is readable but has no name, so diagnostics and the"
					+ " frozen table would disagree about what it is",
					!SurfaceTable.builtinName(r).startsWith("builtin"));
		}
	}

	/**
	 * Every readable built-in appears in the frozen table.
	 *
	 * The other half of the same lesson: the renderer walks the id space now, and this checks that
	 * the walk actually reaches everything the stages expose.
	 */
	@Test
	public void everyReadableBuiltInAppearsInTheTable() throws Exception {
		String rendered = OcslTables.render();
		for (int s = 0; s <= 255; s++) {
			byte stage = (byte) s;
			if (!OcslWire.isKnownStage(stage)) {
				continue;
			}
			for (int reg = 0; reg < SurfaceTable.UNIFORM_BASE; reg++) {
				if (SurfaceTable.builtinType(stage, reg) == null) {
					continue;
				}
				String expected = "\n" + reg + " " + SurfaceTable.builtinName(reg) + " ";
				assertTrue("register " + reg + " is readable at stage " + (s & 0xFF)
						+ " but is missing from [registers]", rendered.contains(expected));
			}
		}
	}

	/** The reserved blocks must not overlap, in the order the id space assigns them. */
	@Test
	public void theReservedBlocksTileWithoutOverlapping() throws Exception {
		assertTrue("light block is empty or inverted",
				SurfaceTable.REG_LIGHT_BASE < SurfaceTable.REG_LIGHT_LIMIT);
		assertTrue("animator block starts before light ends",
				SurfaceTable.REG_LIGHT_LIMIT <= SurfaceTable.REG_ANIMATOR_BASE);
		assertTrue("animator block is empty or inverted",
				SurfaceTable.REG_ANIMATOR_BASE < SurfaceTable.REG_ANIMATOR_LIMIT);
		assertTrue("uniform block starts before the animator block ends",
				SurfaceTable.REG_ANIMATOR_LIMIT <= SurfaceTable.UNIFORM_BASE);
		assertTrue("uniform block is empty or inverted",
				SurfaceTable.UNIFORM_BASE < SurfaceTable.UNIFORM_LIMIT);
		assertTrue("working registers start before the uniform block ends",
				SurfaceTable.UNIFORM_LIMIT <= SurfaceTable.WORKING_BASE);
	}

	/**
	 * Every opcode the wire knows appears in the rendered table.
	 *
	 * Guards the gap a hand-maintained table always develops: an op added to `OcslWire` and not to
	 * the frozen file would leave a second backend implementing an instruction whose charge nobody
	 * wrote down. The rendering walks the opcode space, so this checks the walk actually covers it.
	 */
	@Test
	public void everyKnownOpcodeAppearsInTheTable() throws Exception {
		String rendered = OcslTables.render();
		int found = 0;
		for (int i = 0; i <= 255; i++) {
			OcslWire.Shape shape = OcslWire.shapeOf((byte) i);
			if (shape == null) {
				continue;
			}
			found++;
			if (!rendered.contains("\n" + i + " " + shape.name + " ")) {
				fail("opcode " + i + " (" + shape.name + ") is missing from the [ops] table;"
						+ " an op the wire carries and the table does not is an instruction a"
						+ " second backend cannot charge correctly");
			}
		}
		assertTrue("expected the wire to define opcodes at all", found > 20);
	}

	private static String read() throws Exception {
		InputStream in = OcslTablesTest.class.getResourceAsStream(RESOURCE);
		if (in == null) {
			fail("missing " + RESOURCE + "; regenerate with ./gradlew ocslTables");
		}
		try {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			int n;
			while ((n = in.read(chunk)) > 0) {
				buffer.write(chunk, 0, n);
			}
			// Normalised so a checkout with CRLF translation does not fail the comparison on line
			// endings, which say nothing about whether a contract moved.
			return new String(buffer.toByteArray(), "UTF-8").replace("\r\n", "\n");
		} finally {
			in.close();
		}
	}
}
