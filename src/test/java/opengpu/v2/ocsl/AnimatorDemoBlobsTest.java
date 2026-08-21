package opengpu.v2.ocsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The field-test demo programs — built here, carried by {@code ingame/animtest.lua} as hex.
 *
 * <h2>Why a JVM test builds blobs for a Lua script</h2>
 *
 * There is no Lua-side OCSL encoder yet (that is the miniscript front-end, blocked on Stage B),
 * so the in-game animator test has to hand {@code createProgram} pre-encoded bytes. Hand-rolling
 * IR in Lua would make the field test indict the hand-rolled encoder instead of the feature. So
 * the SAME builder the whole suite trusts produces them, this test re-derives them on every run,
 * and {@link #theLuaScriptCarriesExactlyTheseBlobs} fails the moment the script's copies drift —
 * the mutual-check that turns "keep animtest.lua in sync" from prose into an enforced fact.
 *
 * A fresh hex dump lands in {@code build/reports/animator-demo-blobs.txt} on every run, so
 * re-syncing after a deliberate change is copy-paste rather than archaeology.
 *
 * <h2>Seam continuity is part of the design of each program</h2>
 *
 * {@code time} wraps at P = 1680 s. Every period used below divides P (1.5, 2, 4, 6), so each
 * cycle lands exactly on the seam and the wrap is invisible — the property {@code OcslTime}'s
 * divisor table exists to make available. A program with a non-divisor period would visibly skip
 * once every 28 minutes, and a field test is exactly where that would be misread as a bug.
 */
public class AnimatorDemoBlobsTest {

	private static final double TWO_PI = Math.PI * 2.0;

	/**
	 * ORBIT: x/y circle around the server base, radius 22 px, period 6 s, phase-offset by
	 * nodeSeed so several nodes sharing this one program de-phase — ANIM-2's fix, visible.
	 *
	 * Also breathes SCALE: sx = sy = 1 +/- 0.25 on the same phase. Scale composes by MULTIPLY,
	 * so the output is the factor, not a delta — and this is deliberately the only field channel
	 * the SX/SY substitution branches have: the panel found that without it, a deleted or
	 * transposed scale branch in overlayTransform was invisible to every channel at once.
	 */
	private static byte[] orbit() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		Expr phase = b.builtin(SurfaceTable.REG_TIME).mul(b.f((float) (TWO_PI / 6.0)))
				.add(b.builtin(SurfaceTable.REG_ANIM_NODE_SEED).mul(b.f((float) TWO_PI)));
		b.out(OcslWire.PROP_ANIM_X, phase.cos().mul(b.f(22.0f)));
		b.out(OcslWire.PROP_ANIM_Y, phase.sin().mul(b.f(22.0f)));
		Expr breathe = phase.sin().mul(b.f(0.25f)).add(b.f(1.0f));
		b.out(OcslWire.PROP_ANIM_SX, breathe);
		b.out(OcslWire.PROP_ANIM_SY, breathe);
		return IrCodec.encode(b.build());
	}

	/**
	 * BOUNCE: y offsets by -35 px on a rectified 3 s sine — the node HOPS 35 px UP off its base
	 * every 1.5 s, like a ball bouncing on a floor. Two prose corrections are baked into that
	 * sentence: rectification halves the visible cadence (a first draft said "period 3 s", the
	 * un-rectified wave's number), and the scene is y-DOWN, so negative y is up — the same
	 * draft called this a "dip", which is the sign read in the wrong coordinate convention.
	 */
	private static byte[] bounce() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.out(OcslWire.PROP_ANIM_Y, b.builtin(SurfaceTable.REG_TIME)
				.mul(b.f((float) (Math.PI / 1.5))).sin().abs().mul(b.f(-35.0f)));
		return IrCodec.encode(b.build());
	}

	/**
	 * SPINUP: rotation from {@code sinceAttach} alone, with a TRAPEZOIDAL velocity profile —
	 * angle = w * u * (sa - 1.25 * u) where u = min(sa/2.5, 1), w = one turn per 4 s. Below
	 * 2.5 s that is w*sa^2/5 (velocity climbs LINEARLY 0 → w); above, w*(sa - 1.25) (constant
	 * w). Both the angle and the velocity are continuous at the seam. Re-attaching restamps and
	 * REPLAYS the ramp, and ANIM-6's saturation is on screen: at the 600 s CAP the angle stops
	 * growing, so a spinner left attached ten minutes visibly FREEZES — the field test names
	 * that as expected, not a bug.
	 *
	 * TWO earlier drafts' mistakes, on record because each survived one review round:
	 * {@code angle = t * w * min(sa/2.5, 1)} throttled the ANGLE against the scene clock, which
	 * sweeps the whole accumulated {@code t*w} — potentially hundreds of turns — inside the
	 * ramp: a blur, not an ease. The second, {@code angle = w * sa * min(sa/2.5, 1)}, moved to
	 * the attach clock but still shaped the ANGLE: its velocity is 2w*sa/2.5, which peaks at 2w
	 * and HALVES discontinuously at the seam — "ramps 0 to w" was simply false of it, which
	 * the panel's arithmetic and claims lenses both derived independently. Ease the VELOCITY
	 * and integrate; easing the angle is how both wrong drafts happened.
	 */
	private static byte[] spinup() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		Expr sa = b.builtin(SurfaceTable.REG_ANIM_SINCE_ATTACH);
		Expr u = sa.mul(b.f(1.0f / 2.5f)).min(b.f(1.0f));
		b.out(OcslWire.PROP_ANIM_ROT2D,
				u.mul(sa.sub(u.mul(b.f(1.25f)))).mul(b.f((float) (TWO_PI / 4.0))));
		return IrCodec.encode(b.build());
	}

	/**
	 * PULSE: tint with TWO channels moving at DISTINCT phases (green on sin, alpha on cos,
	 * period 2 s) — a lane swap between the evaluator's RGBA and the renderer's channel order
	 * shows up in-game as the wrong colour breathing, where a uniform pulse would hide it.
	 */
	private static byte[] pulse() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		Expr t = b.builtin(SurfaceTable.REG_TIME).mul(b.f((float) Math.PI));
		b.out(OcslWire.PROP_ANIM_TINT, b.vec4(
				b.f(1.0f),
				t.sin().mul(b.f(0.5f)).add(b.f(0.5f)),
				b.f(1.0f),
				t.cos().mul(b.f(0.25f)).add(b.f(0.75f))));
		return IrCodec.encode(b.build());
	}

	/**
	 * HEAVY: a near-cap program for the ANIM-16 cost measurement's arm B, and nothing else.
	 *
	 * Deliberately NOT a visual demo — it computes a long dependent scalar chain and writes a
	 * tiny bounded offset, so it is visually near-identical to a still node while costing ~35x
	 * what ORBIT does. That is the point: arm B holds node count at 1 and varies PROGRAM SIZE,
	 * so the reading must not be confounded by the node also moving further or drawing more.
	 *
	 * A DEPENDENT chain (each op consumes the last) rather than independent ops, because
	 * OcslWeightBench measures dependent chains and an independent chain would let the JIT
	 * pipeline them — a different quantity from the one the weight column priced.
	 *
	 * The `sin` keeps the accumulator bounded without a second op: values stay in [-1, 1] no
	 * matter how long the chain runs, so the chain cannot drift to infinity and turn the
	 * measurement into a NaN-handling benchmark.
	 */
	private static byte[] heavy() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		Expr acc = b.builtin(SurfaceTable.REG_TIME).mul(b.f(0.5f));
		for (int i = 0; i < 240; i++) {
			acc = acc.add(b.f(0.01f)).sin();     // 2 charged ops per iteration
		}
		b.out(OcslWire.PROP_ANIM_X, acc.mul(b.f(3.0f)));
		return IrCodec.encode(b.build());
	}

	/** The four VISUAL demos, carried by {@code ingame/animtest.lua} — Phase 3.3's protocol. */
	private static Map<String, byte[]> demos() throws Exception {
		Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
		m.put("ORBIT", orbit());
		m.put("BOUNCE", bounce());
		m.put("SPINUP", spinup());
		m.put("PULSE", pulse());
		return m;
	}

	/**
	 * The MEASUREMENT programs, carried by {@code ingame/animbench.lua} — ANIM-16's cost run.
	 *
	 * Kept out of {@code demos()} deliberately: animtest.lua is Phase 3.3's visual protocol with
	 * ten registered predictions about what a viewer SEES, and a 483-op invisible program has no
	 * business in it. Two scripts, two purposes, each pinned against its own blob set.
	 */
	/**
	 * TINY: the smallest program that still evaluates — arm A's fixed program.
	 *
	 * Arm A varies node count to isolate the per-node PROLOGUE, so its program must contribute
	 * as close to nothing as the format allows: any per-op cost left in it is charged to the
	 * prologue constant the arm exists to measure. One multiply and the OUT is the floor.
	 */
	private static byte[] tiny() throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_ANIMATOR);
		b.out(OcslWire.PROP_ANIM_X, b.builtin(SurfaceTable.REG_TIME).mul(b.f(0.0f)));
		return IrCodec.encode(b.build());
	}

	private static Map<String, byte[]> benchPrograms() throws Exception {
		Map<String, byte[]> m = new LinkedHashMap<String, byte[]>();
		m.put("TINY", tiny());
		m.put("HEAVY", heavy());
		return m;
	}

	private static Map<String, byte[]> allPrograms() throws Exception {
		Map<String, byte[]> m = new LinkedHashMap<String, byte[]>(demos());
		m.putAll(benchPrograms());
		return m;
	}

	private static String hex(byte[] blob) {
		StringBuilder sb = new StringBuilder(blob.length * 2);
		for (byte value : blob) {
			sb.append(String.format("%02x", value));
		}
		return sb.toString();
	}

	/** Every demo decodes, validates, and sits WELL inside the caps — a field test must not be
	 *  the first place a cap trips. */
	@Test
	public void everyDemoValidatesComfortablyUnderTheCaps() throws Exception {
		StringBuilder report = new StringBuilder();
		report.append("Animator field-test demo blobs — regenerate by running this test.\n");
		report.append("Paste each hex into ingame/animtest.lua; the pinning test checks they match.\n\n");
		for (Map.Entry<String, byte[]> demo : allPrograms().entrySet()) {
			IrProgram program = IrCodec.decode(demo.getValue(), IrCodec.Source.TRANSIENT);
			IrValidator.Validated validated = IrValidator.validate(program);
			// The ANIMATOR's cap, not the ceiling — the panel found this was the one
			// animator-denominated headroom check left dividing MAX_STRUCTURAL_OPS after the
			// per-stage split. NOT equal since the same-day raise (animator 512 vs ceiling 1024):
			// dividing the ceiling here would already loosen this bound 2x past the budget these
			// programs are actually priced against.
			int animCap = IrValidator.maxStructuralOps(OcslWire.STAGE_ANIMATOR);
			if ("HEAVY".equals(demo.getKey())) {
				// THE ONE DELIBERATE EXEMPTION, and it is exempt from the HEADROOM rule only —
				// never from the cap. HEAVY exists to be near-cap: ANIM-16's arm B varies program
				// size against a fixed node count, and the two candidate cost models are only
				// separable at the large end (they predict ~8 us vs ~236 us for one node there).
				// A "comfortably under half" version of this program could not tell them apart,
				// which would make the measurement unable to see the effect it is for.
				assertTrue("HEAVY must still VALIDATE — a measurement program that trips the cap"
						+ " measures the refusal path, not the cost: " + validated.structuralOps,
						validated.structuralOps <= animCap);
				assertTrue("HEAVY must actually be large enough to separate the models — under"
						+ " half the cap it is just another demo: " + validated.structuralOps,
						validated.structuralOps > animCap / 2);
			} else {
				assertTrue(demo.getKey() + " must sit under half the animator's op cap, not squeak"
						+ " past it — " + validated.structuralOps + " ops",
						validated.structuralOps <= animCap / 2);
			}
			report.append(demo.getKey()).append(" (").append(validated.structuralOps)
					.append(" ops, ").append(demo.getValue().length).append(" bytes)\n")
					.append(hex(demo.getValue())).append("\n\n");
		}
		File dir = new File("build/reports");
		dir.mkdirs();
		FileOutputStream out = new FileOutputStream(new File(dir, "animator-demo-blobs.txt"));
		try {
			out.write(report.toString().getBytes(StandardCharsets.UTF_8));
		} finally {
			out.close();
		}
	}

	/**
	 * The mutual check: the hex literals inside {@code ingame/animtest.lua} must be EXACTLY the
	 * bytes the builder produces today. "Keep the demo script in sync" as an enforced fact — if
	 * the builder, the format, or a demo program changes, this points at the script instead of
	 * letting the field test silently run yesterday's programs.
	 *
	 * ASSUMED, not asserted, on the file's existence: {@code ingame/} is deliberately gitignored
	 * (the scripts live beside the working copy, not in it), so a clean clone — CI included —
	 * has no script to pin and must SKIP rather than fail. On the machine where the field test
	 * actually runs, the file exists and the check binds.
	 */
	@Test
	public void theLuaScriptCarriesExactlyTheseBlobs() throws Exception {
		pinAgainst("ingame/animtest.lua", demos());
	}

	/** The bench script's own pin — same mutual check, different script and blob set. */
	@Test
	public void theBenchScriptCarriesExactlyTheseBlobs() throws Exception {
		pinAgainst("ingame/animbench.lua", benchPrograms());
	}

	private static void pinAgainst(String path, Map<String, byte[]> programs) throws Exception {
		File script = new File(path);
		org.junit.Assume.assumeTrue(path + " absent (clean clone) — nothing to pin",
				script.exists());
		String lua = new String(Files.readAllBytes(script.toPath()), StandardCharsets.UTF_8);
		for (Map.Entry<String, byte[]> demo : programs.entrySet()) {
			Matcher m = Pattern.compile(
					"BLOB_" + demo.getKey() + "\\s*=\\s*\"([0-9a-f]+)\"").matcher(lua);
			assertTrue(path + " must define BLOB_" + demo.getKey(), m.find());
			assertEquals("BLOB_" + demo.getKey() + " in " + path + " has drifted from the"
					+ " builder's output — regenerate from build/reports/animator-demo-blobs.txt",
					hex(demo.getValue()), m.group(1));
		}
	}
}
