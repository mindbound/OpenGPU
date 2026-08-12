package opengpu.v2.ocsl;

import java.util.ArrayList;
import java.util.List;

/**
 * Measures the CPU-MEASURED column of the op weight table, on the real VM.
 *
 * A2 deferred this explicitly — "the CPU-measured weight column is a measurement deliverable at
 * Stage B exit (the VM exists then)" — and until it lands the design states outright that <b>no
 * claim that op counts correspond to CPU time is made</b>. The VM exists, so the deferral's reason
 * has expired.
 *
 * <h2>Two columns, because the table has two customers</h2>
 *
 * Amendment 13's existing numbers ({@code DIV 5}, {@code ATAN2 6}, {@code SWZ 0}) are GPU
 * LOWERED-INSTRUCTION counts. This produces a second, independent column in CPU time. They are not
 * interchangeable and the ops A2 named as owed are exactly where they diverge hardest: {@code
 * SELECT} is a strict-pick branch on the CPU and a blend on the GPU; {@code ITOF} is a counter read
 * here and vanishes entirely into unrolled GPU text; {@code SWZ} is several real stores on a flat
 * float[] frame and zero instructions after GPU register allocation. A single column would price
 * one of the two budgets with a number measured on the wrong executor.
 *
 * <h2>A MAIN, not a JUnit test, and deliberately</h2>
 *
 * A timing assertion in CI is a flaky test that eventually gets muted, and a muted test is worse
 * than none — this repo has the scar already. The output is a measurement to be read, argued with
 * and written into DESIGN by a person; the frozen artifact is the table, not the timing.
 *
 * <pre>
 *   ./gradlew ocslWeights
 * </pre>
 *
 * <h2>What the numbers mean</h2>
 *
 * Everything is normalised to {@code ADD = 1.0}, matching how amendment 13's column reads. Each op
 * is measured as a chain of {@link #CHAIN} dependent instances so the result cannot be optimised
 * away, against a baseline program of the same shape built from {@code ADD}; the difference is
 * attributed to the op. Dependent rather than independent on purpose: an independent chain measures
 * the machine's parallelism, not the op's cost, and the VM executes strictly in order anyway.
 */
public final class OcslWeightBench {
	private OcslWeightBench() {}

	/** Structural ops a measured program may use, leaving room for the splat/mul/out trailer. */
	private static final int BUDGET = 245;
	private static final int WARMUP = 300;
	private static final int RUNS = 4000;
	private static final int TRIALS = 7;

	private interface Chain {
		/** Apply the op once, returning the new value of the running float. */
		Expr step(OcslBuilder b, Expr x);
	}

	private static final class Row {
		final String name;
		final Chain chain;

		Row(String name, Chain chain) {
			this.name = name;
			this.chain = chain;
		}
	}

	public static void main(String[] args) throws Exception {
		List<Row> rows = new ArrayList<Row>();
		// The baseline. Every other row is reported relative to this.
		rows.add(new Row("ADD", new Chain() {
			public Expr step(OcslBuilder b, Expr x) { return x.add(b.f(1.0009f)); }
		}));
		rows.add(new Row("MUL", new Chain() {
			public Expr step(OcslBuilder b, Expr x) { return x.mul(b.f(1.0009f)); }
		}));
		// Existing amendment-13 rows, measured so the two columns can be compared at all.
		rows.add(new Row("DIV", new Chain() {
			public Expr step(OcslBuilder b, Expr x) { return x.div(b.f(1.0009f)); }
		}));
		rows.add(new Row("MOD", new Chain() {
			public Expr step(OcslBuilder b, Expr x) { return x.mod(b.f(7.13f)); }
		}));
		rows.add(new Row("POW", new Chain() {
			public Expr step(OcslBuilder b, Expr x) { return x.pow(b.f(1.0009f)); }
		}));
		rows.add(new Row("LOG", new Chain() {
			public Expr step(OcslBuilder b, Expr x) { return x.log().add(b.f(2.5f)); }
		}));
		rows.add(new Row("SQRT", new Chain() {
			public Expr step(OcslBuilder b, Expr x) { return x.sqrt().add(b.f(1.0009f)); }
		}));
		rows.add(new Row("ATAN2", new Chain() {
			public Expr step(OcslBuilder b, Expr x) { return x.atan2(b.f(1.7f)).add(b.f(2.0f)); }
		}));
		rows.add(new Row("SIN", new Chain() {
			public Expr step(OcslBuilder b, Expr x) { return x.sin().add(b.f(2.0f)); }
		}));
		rows.add(new Row("EXP", new Chain() {
			public Expr step(OcslBuilder b, Expr x) { return x.exp().mod(b.f(9.0f)); }
		}));
		rows.add(new Row("SMOOTHSTEP", new Chain() {
			public Expr step(OcslBuilder b, Expr x) {
				return x.smoothstep(b.f(9.0f), b.f(3.0f)).add(b.f(1.0009f));
			}
		}));
		// THE OWED ROWS. A2 named these as unpriced before any budget uses them.
		rows.add(new Row("LT", new Chain() {
			public Expr step(OcslBuilder b, Expr x) {
				return b.select(x.lt(b.f(1e9f)), x.add(b.f(1.0009f)), x);
			}
		}));
		rows.add(new Row("BAND", new Chain() {
			public Expr step(OcslBuilder b, Expr x) {
				return b.select(x.lt(b.f(1e9f)).band(x.lt(b.f(1e9f))), x.add(b.f(1.0009f)), x);
			}
		}));
		rows.add(new Row("BNOT", new Chain() {
			public Expr step(OcslBuilder b, Expr x) {
				return b.select(x.lt(b.f(1e9f)).bnot().bnot(), x.add(b.f(1.0009f)), x);
			}
		}));
		rows.add(new Row("SWZ", new Chain() {
			public Expr step(OcslBuilder b, Expr x) { return x.splat(2).x().add(b.f(1.0009f)); }
		}));

		System.out.println("OCSL op weights -- CPU-measured column, normalised to ADD = 1.0");
		System.out.println("budget=" + BUDGET + " ops runs=" + RUNS + " trials=" + TRIALS
				+ " (median of trials; each trial is the min over a warmed loop)");
		System.out.println();
		double base = -1;
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			int perStep = opsPerStep(row.chain);
			double ns = measure(row.chain, perStep);
			if (i == 0) {
				base = ns;
			}
			System.out.printf("  %-12s %8.2f ns/step  %d op(s)/step   relative %6.2f%n",
					row.name, Double.valueOf(ns), Integer.valueOf(perStep),
					Double.valueOf(ns / base));
		}
		System.out.println();
		System.out.println("NOTE: the relative column counts every op the chain emits, not just the");
		System.out.println("named one -- LT/BAND/BNOT rows carry a SELECT and an ADD each, SWZ carries");
		System.out.println("a SPLAT. Subtract the baseline shape before writing a row into DESIGN.");
	}

	/**
	 * How many structural ops one step of this chain emits.
	 *
	 * Measured rather than declared: several rows need a companion op to stay type-correct or in
	 * domain (LOG needs an ADD to keep its argument positive, the bool rows need a SELECT), and a
	 * fixed chain length silently overran the 256-op cap for those. The builder counts as it
	 * emits, so asking it is exact.
	 */
	private static int opsPerStep(Chain chain) {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		chain.step(b, b.builtin(SurfaceTable.REG_TIME));
		return (int) b.structuralCount();
	}

	/** Nanoseconds per STEP of one chained program, so rows of differing shape are comparable. */
	private static double measure(Chain chain, int perStep) throws Exception {
		final int steps = Math.max(1, BUDGET / Math.max(1, perStep));
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		Expr x = b.builtin(SurfaceTable.REG_TIME);
		for (int i = 0; i < steps; i++) {
			x = chain.step(b, x);
		}
		b.out(OcslWire.PROP_COLOR, x.splat(4).mul(b.builtin(SurfaceTable.REG_TINT)));
		OcslVm vm = new OcslVm(IrValidator.validate(b.build()));
		vm.set(SurfaceTable.REG_TIME, 1.5f);
		vm.set(SurfaceTable.REG_TINT, 1f, 1f, 1f, 1f);

		for (int i = 0; i < WARMUP; i++) {
			vm.run();
		}
		double[] trials = new double[TRIALS];
		for (int t = 0; t < TRIALS; t++) {
			long best = Long.MAX_VALUE;
			for (int rep = 0; rep < 5; rep++) {
				long start = System.nanoTime();
				for (int i = 0; i < RUNS; i++) {
					vm.run();
				}
				long elapsed = System.nanoTime() - start;
				if (elapsed < best) {
					best = elapsed;
				}
			}
			trials[t] = best / (double) RUNS / steps;
		}
		java.util.Arrays.sort(trials);
		return trials[TRIALS / 2];
	}
}
