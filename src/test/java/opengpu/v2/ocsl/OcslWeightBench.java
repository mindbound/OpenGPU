package opengpu.v2.ocsl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <h2>Every row subtracts its own baseline, and that is the load-bearing change</h2>
 *
 * The first version of this bench reported ns per STEP and left the reader to subtract the
 * companion ops a step happened to need — {@code LOG} needs an {@code ADD} to keep its argument
 * positive, the bool rows need a {@code SELECT} to consume the bool, {@code SQRT} needs an
 * {@code ADD} to stay in domain. That footnote is exactly the kind of prose obligation that gets
 * skipped when a number is copied into a budget, and it left {@code SELECT} and {@code LT} welded
 * together in the published table. So the arithmetic is done HERE instead:
 *
 * <ul>
 * <li><b>Each row declares the ops one step emits</b>, by wire name — and the declaration is
 *     CHECKED against what the builder actually emitted, by diffing the opcode histograms of a
 *     two-step and a one-step program. A row that quietly grew a companion op fails loudly rather
 *     than silently attributing that op's cost to the op being priced.</li>
 * <li><b>Each row measures its own zero-step floor</b> — same prologue, same trailer, no steps —
 *     and subtracts it. That removes the per-{@code run()} fixed cost, and with it a real bias in
 *     the first version: rows differ in ops/step, so they differ in step COUNT, so a shared floor
 *     was amortized ~5x more thinly over a 49-step row than over a 245-step one.</li>
 * <li><b>Companions are subtracted using the isolated costs already solved</b>, in declaration
 *     order, each row introducing exactly one unknown. Not a fit — forward substitution, and a row
 *     naming a companion nobody has priced yet throws instead of guessing.</li>
 * </ul>
 *
 * The published column is the ISOLATED one. The raw ns/step is printed beside it so the numbers
 * already in DESIGN remain comparable to these.
 *
 * <h2>What the numbers mean</h2>
 *
 * Everything is normalised to an isolated {@code ADD = 1.0}, matching how amendment 13's column
 * reads. Each op is measured as a chain of dependent instances so the result cannot be optimised
 * away. Dependent rather than independent on purpose: an independent chain measures the machine's
 * parallelism, not the op's cost, and the VM executes strictly in order anyway.
 *
 * <h2>What this method still cannot do, stated at full size</h2>
 *
 * An earlier version of this list called every caveat "second-order against the differences the
 * table is used to argue about". One of them is not, and an adversarial review measured it:
 *
 * <ul>
 * <li><b>OP COSTS ARE NOT ADDITIVE, by about 28%.</b> The isolated column's whole premise is that a
 *     step costs the sum of its ops. Tested on {@code ADD} and {@code MUL} — which share a
 *     {@code case} group in {@link OcslVm#execute} and are therefore the friendliest possible pair —
 *     a mixed step measured 124.26 ns against 96.91 predicted by summing their pure-chain costs.
 *     Thirteen of the rows subtract a companion measured in a PURE single-opcode chain from a raw
 *     step measured in a MIXED one, so each carries a bias of that order, and rows mixing more
 *     dissimilar code should be worse, not better. The differences this table is used to argue about
 *     are factors of 2. <b>This is a floor on the accuracy of every companion-bearing row and it is
 *     not fixable by more careful subtraction.</b></li>
 * <li><b>Deep subtractions amplify.</b> {@code BAND} expands to
 *     {@code raw(BAND) - 2*raw(LT) + raw(SELECT)} — a ~1-op result extracted from ~13 ops of raw
 *     with alternating signs, so a 1% error in {@code raw(LT)} alone moves it ~8%. It, not
 *     {@code SAMPLE}, is the widest row here; {@code SAMPLE}'s expansion cancels cleanly.</li>
 * <li><b>The transcendental rows converge to a FIXED POINT and then measure one argument.</b> A
 *     dependent 1-D chain must: {@code SQRT} and {@code LOG} reach theirs by step 14 of 122,
 *     {@code ATAN2} by step 9, {@code SMOOTHSTEP} by step 8. Since {@code StrictMath}'s fdlibm
 *     routines are argument-range dependent, these are point measurements at a converged argument
 *     rather than averages over a domain. Reported as costs anyway, because a single number is what
 *     a weight table wants — but a program working a different part of the domain may not match.</li>
 * <li><b>Second-order, genuinely:</b> a long program's frame and op list are larger than a short
 *     one's, so the floor is marginally more cache-friendly than the row; and {@code steps} is
 *     {@code BUDGET/opsPerStep} rounded down, so rows run between 49 and 245 ops.</li>
 * </ul>
 */
public final class OcslWeightBench {
	private OcslWeightBench() {}

	/** Structural ops a measured program may use, leaving room for the prologue/trailer. */
	private static final int BUDGET = 245;
	/**
	 * Runs per program before anything is measured — sized to REACH the steady state, not to touch it.
	 *
	 * This was 300 while the measured phase does {@code TRIALS * 5 * RUNS} = 140,000 runs per
	 * measurement, a 466x gap. So the interpreter's one irreversible deoptimization landed reliably
	 * INSIDE the measured phase, and the bench watched its own baseline move 26.57 -> 64.53 -> 48.57
	 * ns across a single run while flagging 40-53% drift on the rows unlucky enough to straddle it.
	 * Warming past the transition is what makes a row's two bracketing baselines agree; the bracket
	 * cancels a steady offset, not a step change happening between its two halves.
	 */
	private static final int WARMUP = 20000;
	private static final int RUNS = 2000;
	private static final int TRIALS = 5;
	/** Ratio samples per trial. TRIALS*REPS samples feed the median and its interquartile spread. */
	private static final int REPS = 5;
	/** The slot the SAMPLE row binds. Not slot 0 — that is `input`, which material has no access to. */
	private static final int SAMPLER_SLOT = 1;

	/** Kept live so nothing in the timed loop can be proven dead. */
	private static double sink;
	/** Reused so reading the output back costs no allocation inside the measurement. */
	private static final float[] SINK_BUFFER = new float[4];

	/** One step of a measured chain. {@code ctx} is whatever the row's prologue produced, or null. */
	private interface Chain {
		Expr step(OcslBuilder b, Expr x, Expr ctx);
	}

	/** Emitted once, before the chain, and present in the row's floor program too so it cancels. */
	private interface Prologue {
		Expr emit(OcslBuilder b);
	}

	private static final class Row {
		/** The op this row prices — the single unknown. Must appear in {@link #emits}. */
		final String op;
		/** Every op one step emits, by {@link OcslWire.Shape#name}. Checked against the builder. */
		final String[] emits;
		final Prologue prologue;
		final Chain chain;
		/** A ceiling on steps for rows the caps bound more tightly than BUDGET does. */
		final int maxSteps;

		Row(String op, String[] emits, Prologue prologue, Chain chain, int maxSteps) {
			this.op = op;
			this.emits = emits;
			this.prologue = prologue;
			this.chain = chain;
			this.maxSteps = maxSteps;
		}

		Row(String op, String[] emits, Chain chain) {
			this(op, emits, null, chain, Integer.MAX_VALUE);
		}
	}

	// ---------------------------------------------------------------- the rows

	private static List<Row> rows() {
		List<Row> rows = new ArrayList<Row>();

		// The baseline, and the only row that must come first: everything is reported against it.
		rows.add(new Row("ADD", new String[] { "ADD" }, new Chain() {
			public Expr step(OcslBuilder b, Expr x, Expr ctx) { return x.add(b.f(1.0009f)); }
		}));
		rows.add(new Row("MUL", new String[] { "MUL" }, new Chain() {
			public Expr step(OcslBuilder b, Expr x, Expr ctx) { return x.mul(b.f(1.0009f)); }
		}));

		// SWZ measured PURE. A swizzle of a float to a float is a legal one-component swizzle, so
		// the row that amendment 13 prices at zero needs no companion at all — where the first
		// version measured it only through a SPLAT and published the pair.
		rows.add(new Row("SWZ", new String[] { "SWZ" }, new Chain() {
			public Expr step(OcslBuilder b, Expr x, Expr ctx) { return x.x(); }
		}));
		rows.add(new Row("SPLAT", new String[] { "SPLAT", "SWZ" }, new Chain() {
			public Expr step(OcslBuilder b, Expr x, Expr ctx) { return x.splat(2).x(); }
		}));
		// Priced for its own sake and because the SAMPLE row needs a vec2 coordinate.
		rows.add(new Row("CONS2", new String[] { "CONS2", "SWZ" }, new Chain() {
			public Expr step(OcslBuilder b, Expr x, Expr ctx) { return b.vec2(x, x).x(); }
		}));

		// Existing amendment-13 rows, measured so the two columns can be compared at all.
		rows.add(new Row("DIV", new String[] { "DIV" }, new Chain() {
			public Expr step(OcslBuilder b, Expr x, Expr ctx) { return x.div(b.f(1.0009f)); }
		}));
		// `x.mod(7.13)` from a seed of 1.5 is a FIXED POINT -- 1.5 mod 7.13 is 1.5 -- so the row
		// measured the same operands 245 times over. The ADD makes the value walk and wrap, at the
		// cost of a companion, which is the honest trade.
		rows.add(new Row("MOD", new String[] { "MOD", "ADD" }, new Chain() {
			public Expr step(OcslBuilder b, Expr x, Expr ctx) {
				return x.add(b.f(3.3f)).mod(b.f(7.13f));
			}
		}));
		rows.add(new Row("POW", new String[] { "POW" }, new Chain() {
			public Expr step(OcslBuilder b, Expr x, Expr ctx) { return x.pow(b.f(1.0009f)); }
		}));
		rows.add(new Row("LOG", new String[] { "LOG", "ADD" }, new Chain() {
			public Expr step(OcslBuilder b, Expr x, Expr ctx) { return x.log().add(b.f(2.5f)); }
		}));
		rows.add(new Row("SQRT", new String[] { "SQRT", "ADD" }, new Chain() {
			public Expr step(OcslBuilder b, Expr x, Expr ctx) { return x.sqrt().add(b.f(1.0009f)); }
		}));
		rows.add(new Row("ATAN2", new String[] { "ATAN2", "ADD" }, new Chain() {
			public Expr step(OcslBuilder b, Expr x, Expr ctx) {
				return x.atan2(b.f(1.7f)).add(b.f(2.0f));
			}
		}));
		rows.add(new Row("SIN", new String[] { "SIN", "ADD" }, new Chain() {
			public Expr step(OcslBuilder b, Expr x, Expr ctx) { return x.sin().add(b.f(2.0f)); }
		}));
		rows.add(new Row("EXP", new String[] { "EXP", "MOD" }, new Chain() {
			public Expr step(OcslBuilder b, Expr x, Expr ctx) { return x.exp().mod(b.f(9.0f)); }
		}));
		rows.add(new Row("SMOOTHSTEP", new String[] { "SMOOTHSTEP", "ADD" }, new Chain() {
			public Expr step(OcslBuilder b, Expr x, Expr ctx) {
				return x.smoothstep(b.f(9.0f), b.f(3.0f)).add(b.f(1.0009f));
			}
		}));

		// THE OWED ROWS. A2 named LT/LE/EQ, BAND/BOR/BNOT and SELECT as unpriced before any budget
		// uses them.
		//
		// SELECT FIRST, and separably, which the first version could not do. A bool's only consumer
		// is SELECT, so a per-step comparison welds the two costs together at a fixed 1:1 ratio and
		// no amount of measuring changes that. The lever is A9: an `Expr` handle names a register
		// that already exists, so REUSING the condition costs no op. Hoisting one LT out of the
		// chain gives a step of {ADD, SELECT} with no comparison in it, and the hoisted op sits in
		// this row's floor program too, so it cancels rather than being amortized away.
		Prologue hoistedCondition = new Prologue() {
			public Expr emit(OcslBuilder b) {
				return b.builtin(SurfaceTable.REG_TIME).lt(b.f(1e9f));
			}
		};
		rows.add(new Row("SELECT", new String[] { "SELECT", "ADD" }, hoistedCondition,
				new Chain() {
					public Expr step(OcslBuilder b, Expr x, Expr ctx) {
						return b.select(ctx, x.add(b.f(1.0009f)), x);
					}
				}, Integer.MAX_VALUE));
		rows.add(new Row("LT", new String[] { "LT", "SELECT", "ADD" }, new Chain() {
			public Expr step(OcslBuilder b, Expr x, Expr ctx) {
				return b.select(x.lt(b.f(1e9f)), x.add(b.f(1.0009f)), x);
			}
		}));
		rows.add(new Row("BAND", new String[] { "BAND", "LT", "LT", "SELECT", "ADD" }, new Chain() {
			public Expr step(OcslBuilder b, Expr x, Expr ctx) {
				return b.select(x.lt(b.f(1e9f)).band(x.lt(b.f(1e9f))), x.add(b.f(1.0009f)), x);
			}
		}));
		// BNOT over a FRESH comparison, not the hoisted one. Hoisting made this row cheaper to
		// isolate -- two BNOTs against SELECT and ADD alone -- but it also made it the one row in the
		// table whose chain was NOT dependent across steps: every step's BNOTs read the same
		// loop-invariant condition register, so consecutive steps had no data dependency between
		// them and the machine was free to overlap them. That contradicts the one property this
		// bench relies on everywhere else. Paying for the LT buys the dependency back.
		rows.add(new Row("BNOT", new String[] { "BNOT", "BNOT", "LT", "SELECT", "ADD" }, new Chain() {
			public Expr step(OcslBuilder b, Expr x, Expr ctx) {
				return b.select(x.lt(b.f(1e9f)).bnot().bnot(), x.add(b.f(1.0009f)), x);
			}
		}));

		// SAMPLE, bounded by the FETCH cap rather than the op budget: 16 taps post-unroll is the
		// whole allowance, so this row runs 16 steps where the others run 49-245, and its floor
		// subtraction carries correspondingly more weight. Its EXPANSION is clean, though -- the SWZ
		// terms cancel exactly -- so BAND, not this, is the widest row in the table.
		//
		// Step 1 samples uv = (1.5, 1.5), which clamps to edge so all four taps land on one texel;
		// 1 of the 16 steps therefore runs a different memory path from the rest.
		rows.add(new Row("SAMPLE", new String[] { "SAMPLE", "CONS2", "SWZ" }, null, new Chain() {
			public Expr step(OcslBuilder b, Expr x, Expr ctx) {
				return b.sample(SAMPLER_SLOT, b.vec2(x, x)).x();
			}
		}, IrValidator.MAX_FETCHES));

		return rows;
	}

	// ---------------------------------------------------------------- main

	public static void main(String[] args) throws Exception {
		System.out.println("OCSL op weights -- CPU-measured column, normalised to ADD = 1.0");
		System.out.println("jvm: " + System.getProperty("java.vm.name") + " "
				+ System.getProperty("java.version") + " (" + System.getProperty("os.arch") + ")");
		System.out.println("budget=" + BUDGET + " ops runs=" + RUNS + " trials=" + TRIALS
				+ " (median of trials; each trial is the min over a warmed loop)");
		System.out.println("every row subtracts its own zero-step floor and its priced companions");
		System.out.println();
		Map<String, Double> isolated = new LinkedHashMap<String, Double>();
		List<Row> rows = rows();
		Row addRow = rows.get(0);
		if (!"ADD".equals(addRow.op)) {
			// Enforced, not assumed. Every row divides by an ADD measured beside it, so ADD being
			// first is load-bearing rather than stylistic; reordering rows() used to NPE here.
			throw new IllegalStateException("ADD must be the first row -- every other row is"
					+ " normalised against an ADD measured adjacent to it; got " + addRow.op);
		}
		warmEveryRow(rows);

		// THE FIRST FULL PASS IS MEASURED AND THROWN AWAY.
		//
		// The interleaved ratio fixed the regime problem; it did not fix the JVM still SETTLING while
		// the early rows are being measured. The sample spread decayed monotonically down the table
		// — 15.7, 14.6, 14.0, 12.9, 15.6, 13.5, then 5.7, 2.4, 1.2, 2.1 — which is a settling curve,
		// not a property of the ops. `warmEveryRow` cannot reach that state because settling is
		// driven by the measurement workload itself, which is ~400x larger than any warm-up worth
		// running. So the whole row list is measured twice and only the second is reported: by then
		// every program has been through the real workload, and the early rows stop being penalised
		// for their position.
		for (int pass = 0; pass < 2; pass++) {
			boolean report = pass == 1;
			isolated = new LinkedHashMap<String, Double>();
			if (report) {
				System.out.printf("  %-12s %8s %6s %10s %10s%n",
						"op", "raw/ADD", "ops/st", "isolated", "spread");
			}
			measurePass(rows, addRow, isolated, report);
		}

		System.out.println();
		measureLoopRows(addRow);
		System.out.println();
		measureOutRow(addRow);
		System.out.println();
		System.out.println("sink=" + sink + " (kept live so the timed loop cannot be elided)");
	}

	/** One full sweep of the row list. Called twice; only the second is reported. */
	private static void measurePass(List<Row> rows, Row addRow, Map<String, Double> isolated,
			boolean report) throws Exception {
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			checkDeclaredComposition(row);

			// THE BASELINE IS RE-MEASURED BESIDE EVERY ROW, and the row is reported in units of it.
			//
			// This is the correction that makes the table mean anything. The straight-line programs
			// undergo a single irreversible ~1.9x deoptimization partway through a measurement
			// session -- `warmEveryRow` only SEEDS the profile, it does not prevent the transition --
			// and each row was measured exactly once, in declaration order, so a row's number was
			// decided by which side of that transition it happened to land on. Permuting only the
			// companion-free rows moved the ADD baseline by +82% and carried DIV from 1.89 to 1.06
			// on identical row content.
			//
			// Dividing by an ADD measured immediately before and after cancels the drift, PROVIDED
			// it is a multiplicative scale on the whole interpreter. That premise is testable and
			// the table tests it two ways: `drift` reports how far the two bracketing ADDs disagree,
			// and ADD's own row must come out at 1.00 -- it is measured like any other row rather
			// than being assigned 1.0, so a value away from 1.00 means the regime moved WITHIN a
			// single row and the whole run is suspect.
			Ratio measured = ratioAgainstAdd(row, addRow);
			double relRaw = measured.median;
			double drift = measured.spread;

			double companions = 0;
			int count = 0;
			for (int e = 0; e < row.emits.length; e++) {
				if (row.emits[e].equals(row.op)) {
					count++;
					continue;
				}
				Double priced = isolated.get(row.emits[e]);
				if (priced == null) {
					// Not a warning. A missing companion price means the number this row is about
					// to publish is this op's cost PLUS an unpriced one, which is precisely the
					// defect the isolated column exists to remove.
					throw new IllegalStateException("row " + row.op + " emits " + row.emits[e]
							+ ", which no earlier row has priced; order the rows so every companion"
							+ " is solved before the row that leans on it");
				}
				companions += priced.doubleValue();
			}
			if (count == 0) {
				throw new IllegalStateException("row " + row.op + " does not emit " + row.op);
			}
			if (isolated.containsKey(row.op)) {
				throw new IllegalStateException(row.op + " is priced by two rows; the second would"
						+ " silently win and the first would never be reported");
			}
			double each = (relRaw - companions) / count;
			isolated.put(row.op, Double.valueOf(each));

			if (report) {
				System.out.printf("  %-12s %8.2f %6d %10.2f %9.1f%%%s%n", row.op,
						Double.valueOf(relRaw), Integer.valueOf(row.emits.length),
						Double.valueOf(each), Double.valueOf(drift * 100),
						suspect(row.op, each, drift));
			}
		}
	}

	private static int stepsOf(Row row) {
		return Math.max(1, Math.min(row.maxSteps, BUDGET / row.emits.length));
	}

	/** One row's cost per step, with its own zero-step floor subtracted. */
	private static double nsPerStep(Row row) throws Exception {
		double floor = nsPerRun(vmFor(row, 0));
		double full = nsPerRun(vmFor(row, stepsOf(row)));
		return (full - floor) / stepsOf(row);
	}

	/** A row's cost in ADD-equivalents, with the spread across samples that produced it. */
	private static final class Ratio {
		final double median;
		/** Interquartile spread as a fraction of the median — this row's own error bar. */
		final double spread;

		Ratio(double median, double spread) {
			this.median = median;
			this.spread = spread;
		}
	}

	/**
	 * A row's cost in units of an {@code ADD} measured INTERLEAVED with it, rep by rep.
	 *
	 * The third and final attempt at making these numbers mean something, and the first that holds.
	 * The problem was never the arithmetic — it was that the interpreter's speed is not stable for
	 * the duration of a measurement. It moves between a ~26 ns/ADD regime and a ~48-53 ns one, and
	 * <b>it moves in both directions, repeatedly, within one run</b>, which is why the previous fix
	 * (warm past the transition) failed: there is no single transition to get past.
	 *
	 * Measuring {@code ADD} fully, then the row, then {@code ADD} again does not survive that — the
	 * bracket cancels a steady offset, not a step change that happens between its halves. Interleaved
	 * per rep, every sample has its numerator and denominator drawn from the same regime, so the
	 * RATIO is preserved even while the absolute ns are not. A rep that straddles a change produces
	 * an outlier ratio and the median rejects it; the interquartile spread of the samples is then
	 * this row's honest error bar, reported rather than asserted.
	 *
	 * This is also why the table is published in ADD-equivalents and not in nanoseconds. The
	 * nanoseconds are not a property of the op on this machine; the ratio is.
	 */
	private static Ratio ratioAgainstAdd(Row row, Row addRow) throws Exception {
		OcslVm rowFull = vmFor(row, stepsOf(row));
		OcslVm rowFloor = vmFor(row, 0);
		OcslVm addFull = vmFor(addRow, stepsOf(addRow));
		OcslVm addFloor = vmFor(addRow, 0);
		for (int i = 0; i < WARMUP; i++) {
			rowFull.run();
			rowFloor.run();
			addFull.run();
			addFloor.run();
		}
		double[] samples = new double[TRIALS * REPS];
		int n = 0;
		for (int t = 0; t < TRIALS; t++) {
			for (int rep = 0; rep < REPS; rep++) {
				// ADD, row, ADD -- so the row sits between two baselines taken seconds apart at
				// most, and a drift across the triple shows up as a bad sample rather than as a
				// silently wrong ratio.
				double a1 = (timeLoop(addFull) - timeLoop(addFloor)) / (double) stepsOf(addRow);
				double r = (timeLoop(rowFull) - timeLoop(rowFloor)) / (double) stepsOf(row);
				double a2 = (timeLoop(addFull) - timeLoop(addFloor)) / (double) stepsOf(addRow);
				double add = (a1 + a2) / 2.0;
				samples[n++] = add > 0 ? r / add : Double.NaN;
			}
		}
		return summarise(samples, n);
	}

	/** Median of the samples, with their interquartile spread as a fraction of it. */
	private static Ratio summarise(double[] samples, int n) {
		double[] used = Arrays.copyOf(samples, n);
		Arrays.sort(used);
		double median = used[n / 2];
		double q1 = used[n / 4];
		double q3 = used[(3 * n) / 4];
		return new Ratio(median, median == 0 ? Double.NaN : Math.abs(q3 - q1) / Math.abs(median));
	}

	/** {@link #timeLoop} with the result collected too, for pricing {@code output()}. */
	private static double timeLoopCollecting(OcslVm vm) {
		long start = System.nanoTime();
		for (int i = 0; i < RUNS; i++) {
			vm.run();
			vm.output(OcslWire.PROP_COLOR, SINK_BUFFER);
		}
		long elapsed = System.nanoTime() - start;
		sink += SINK_BUFFER[0];
		return elapsed / (double) RUNS;
	}

	/** One timed pass of {@link #RUNS} evaluations, in nanoseconds per evaluation. */
	private static double timeLoop(OcslVm vm) {
		long start = System.nanoTime();
		for (int i = 0; i < RUNS; i++) {
			vm.run();
		}
		long elapsed = System.nanoTime() - start;
		vm.output(OcslWire.PROP_COLOR, SINK_BUFFER);
		sink += SINK_BUFFER[0];
		return elapsed / (double) RUNS;
	}

	/**
	 * Flag a printed figure the reader must not copy into a budget.
	 *
	 * A NEGATIVE isolated cost is not noise, it is proof the subtraction broke — a row cannot cost
	 * less than nothing, so either the companions are mispriced or the regime moved mid-row. It was
	 * previously printed unflagged and would have propagated into every later row that names this op
	 * as a companion. The same applies to ADD's own row landing away from 1.00, and to the two
	 * bracketing baselines disagreeing.
	 */
	private static String suspect(String op, double isolatedValue, double drift) {
		if (isolatedValue < 0) {
			return "   <-- NEGATIVE: subtraction broke, do not use";
		}
		if ("ADD".equals(op) && Math.abs(isolatedValue - 1.0) > 0.05) {
			return "   <-- ADD must self-measure at 1.00; the regime moved WITHIN a row";
		}
		if (drift > 0.10) {
			return "   <-- samples spread >10%; treat as an order of magnitude";
		}
		return "";
	}

	/**
	 * Run every program the bench will measure, once, before measuring any of them.
	 *
	 * MEASUREMENT ORDER WAS CHANGING THE ANSWER, and by a lot. {@link OcslVm#execute} is one large
	 * opcode switch, so the profile-guided compilation of that switch depends on which opcodes have
	 * flowed through it — and a row therefore measures its op against whatever profile the rows
	 * DECLARED BEFORE IT happened to leave behind. Caught by an outlier: {@code DIV} measured 28.04
	 * and 28.76 ns in two runs of an earlier version where it was the third row, and 47.26 in the
	 * first run of this one, where inserting the swizzle rows had moved it to sixth. Identical
	 * program, identical chain, identical step count.
	 *
	 * <b>This does NOT by itself make the numbers order-independent, and an earlier version of this
	 * comment claimed it did.</b> {@link #WARMUP} runs only SEED the profile; the transition happens
	 * during the MEASURED phase, once, irreversibly, and each row is measured exactly once — so a
	 * row's number was still decided by which side of the transition it landed on. Permuting only
	 * the companion-free rows moved the {@code ADD} baseline by +82%. What actually fixes it is
	 * re-measuring {@code ADD} beside every row and reporting in units of it; see the measurement
	 * loop in {@link #main}. This method is kept because a seeded profile is still the right
	 * starting state, and because it is the more representative one — a real program is a mixture of
	 * opcodes, not 245 copies of one.
	 */
	private static void warmEveryRow(List<Row> rows) throws Exception {
		for (int i = 0; i < rows.size(); i++) {
			Row row = rows.get(i);
			int steps = Math.max(1, Math.min(row.maxSteps, BUDGET / row.emits.length));
			OcslVm floor = vmFor(row, 0);
			OcslVm full = vmFor(row, steps);
			for (int n = 0; n < WARMUP; n++) {
				floor.run();
				full.run();
			}
			full.output(OcslWire.PROP_COLOR, SINK_BUFFER);
			sink += SINK_BUFFER[0];
		}
		OcslVm[] loops = { loopVm(16, 2, false), loopVm(60, 2, false), loopVm(16, 4, false),
				loopVm(60, 4, false), loopVm(60, 2, true) };
		for (int n = 0; n < WARMUP; n++) {
			for (int i = 0; i < loops.length; i++) {
				loops[i].run();
			}
		}
	}

	// ---------------------------------------------------------------- ITOF and the back-edge

	/**
	 * The two rows a straight-line chain structurally cannot reach.
	 *
	 * {@code ITOF} reads a loop counter, and {@link OcslBuilder.Counter#value} is legal only inside
	 * a live fold — so it cannot appear in a straight-line program at all, which is why the first
	 * version left it owed. Measured instead as a DIFFERENCE between two folds of identical trip
	 * count and identical body length in ADDs, one of which also reads the counter: the FOR setup,
	 * the ENDFOR back-edges and the accumulator write-back are identical in both and cancel exactly.
	 *
	 * And then the back-edge itself, which is worth having for its own sake. A7 refuses a loop whose
	 * body charges no op, on the stated grounds that "an empty body computes nothing and still costs
	 * the interpreter a back-edge per iteration, which the op count cannot see". That is an
	 * assertion about a cost nobody had measured. Two folds of the same body and DIFFERENT trip
	 * counts isolate it: the one-time FOR cancels, and what is left divides by the iteration
	 * difference.
	 */
	private static void measureLoopRows(Row addRow) throws Exception {
		final int lowTrips = 16;
		// 60, and NOT more: at body 4 this charges 240 against a 256 cap with a 3-op trailer, so 63
		// is the arithmetic maximum and 64 throws BuildException. Documented because the number
		// looks arbitrary and is one increment from breaking.
		final int highTrips = 60;
		final int shortBody = 2;
		final int longBody = 4;
		final double iterations = highTrips - lowTrips;

		// INTERLEAVED with the straight-line ADD, exactly like the table rows, and for the same
		// reason. Bracketing these around a whole block of measurements is what produced this
		// bench's withdrawn "an op costs 1.84x more inside a fold" claim -- the straight-line rows
		// ran early and the loop programs last, on opposite sides of a regime change, and the entire
		// 1.84x was that gap. Every sample below draws its numerator and denominator from one rep.
		OcslVm shortLo = loopVm(lowTrips, shortBody, false);
		OcslVm shortHi = loopVm(highTrips, shortBody, false);
		OcslVm longLo = loopVm(lowTrips, longBody, false);
		OcslVm longHi = loopVm(highTrips, longBody, false);
		OcslVm counter = loopVm(highTrips, shortBody, true);
		OcslVm addFull = vmFor(addRow, stepsOf(addRow));
		OcslVm addFloor = vmFor(addRow, 0);
		for (int i = 0; i < WARMUP; i++) {
			shortLo.run();
			shortHi.run();
			longLo.run();
			longHi.run();
			counter.run();
			addFull.run();
			addFloor.run();
		}

		double[] itofSamples = new double[TRIALS * REPS];
		double[] inLoopSamples = new double[TRIALS * REPS];
		double[] backEdgeSamples = new double[TRIALS * REPS];
		int n = 0;
		for (int t = 0; t < TRIALS * REPS; t++) {
			double add = (timeLoop(addFull) - timeLoop(addFloor)) / (double) stepsOf(addRow);

			// Per-iteration cost at two body lengths. Each is a difference across TRIP COUNT between
			// two programs with the identical op list -- only `trips` differs -- so the frame, the op
			// list and the one-time FOR are bit-identical and cancel exactly.
			double perIterationShort = (timeLoop(shortHi) - timeLoop(shortLo)) / iterations;
			double perIterationLong = (timeLoop(longHi) - timeLoop(longLo)) / iterations;
			double addInLoop = (perIterationLong - perIterationShort) / (longBody - shortBody);
			double backEdge = perIterationShort - shortBody * addInLoop;

			// ITOF against the SAME trip count and the same body length in ADDs, one of which also
			// reads the counter. FOR, the back-edges and the write-back cancel. NOT everything
			// cancels, and the javadoc used to claim it did: the plain body's ADDs take
			// (register, CONSTANT) operands and the counting body's take (register, REGISTER), so
			// this is ITOF plus the difference between those operand kinds -- ~2% of an ITOF.
			double itof = (timeLoop(counter) - timeLoop(shortHi))
					/ (highTrips * (double) shortBody);

			if (add > 0) {
				itofSamples[n] = itof / add;
				inLoopSamples[n] = addInLoop / add;
				backEdgeSamples[n] = backEdge / add;
				n++;
			}
		}
		Ratio itofRatio = summarise(itofSamples, n);
		Ratio inLoopRatio = summarise(inLoopSamples, n);
		Ratio backEdgeRatio = summarise(backEdgeSamples, n);

		printDerived("ITOF", itofRatio, "");
		printDerived("(ADD in loop)", inLoopRatio, "");
		printDerived("(back-edge)", backEdgeRatio,
				backEdgeRatio.median < 0 ? "   <-- NEGATIVE: cannot cost less than nothing" : "");
		System.out.println("  All three in units of the straight-line ADD interleaved with them, so");
		System.out.println("  they are directly comparable to the table above. (ADD in loop) near 1.00");
		System.out.println("  means a fold costs no more PER OP than a straight run; the 1.84x this");
		System.out.println("  bench once reported was the straight-line rows and the loop rows being");
		System.out.println("  measured on opposite sides of a regime change, and is WITHDRAWN.");
		System.out.println("  The back-edge is a difference of four measurements with alternating");
		System.out.println("  signs, so it is the widest figure here and has gone negative on a bad");
		System.out.println("  run. It charges NOTHING structurally -- the cost A7 refuses an");
		System.out.println("  empty-bodied loop to avoid. ITOF cannot be measured outside a fold at");
		System.out.println("  all: Counter.value() is legal only inside a live one.");
	}

	private static void printDerived(String name, Ratio ratio, String flag) {
		System.out.printf("  %-12s %8.2f %6s %10.2f %9.1f%%%s%n", name,
				Double.valueOf(ratio.median), "-", Double.valueOf(ratio.median),
				Double.valueOf(ratio.spread * 100), flag);
	}

	/** A fold of {@code trips} iterations whose body is {@code body} adds, optionally of the counter. */
	private static OcslVm loopVm(int trips, final int body, final boolean readCounter)
			throws Exception {
		final OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		// Seeded from `time` rather than a literal so these programs read the same built-ins the
		// straight-line rows do, and `prepare` has the same registers to bind.
		Expr folded = b.loop(trips, b.builtin(SurfaceTable.REG_TIME), new OcslBuilder.Fold() {
			public Expr apply(Expr accumulator, OcslBuilder.Counter counter) {
				Expr a = accumulator;
				for (int i = 0; i < body; i++) {
					// The counter read is an ITOF and the add is an ADD, so the counter body is
					// exactly the plain body plus `body` ITOFs per iteration -- which is what makes
					// the difference of the two attributable to ITOF alone.
					a = a.add(readCounter ? counter.value() : b.f(1.0009f));
				}
				return a;
			}
		});
		return finish(b, folded);
	}

	// ---------------------------------------------------------------- OUT

	/**
	 * {@code OUT} — and the answer is that it is not an interpreter cost at all.
	 *
	 * {@link OcslVm#run} SKIPS {@code OP_OUT}: the property is read lazily by
	 * {@link OcslVm#output}, so a program's OUT contributes nothing to the loop that evaluates it.
	 * What it costs is one property lookup and one value read, paid by whoever collects the result,
	 * once per evaluation — measured here as the difference between {@code run()} and
	 * {@code run(); output()}.
	 *
	 * The second half of the row is structural rather than measured, and it is the more useful
	 * half: EVERY legal program has exactly one OUT. The validator requires each of the stage's
	 * required properties to be written, the builder refuses a second writer for a property, and
	 * every stage in this build has a property table of exactly {@code {COLOR}}. So OUT is a
	 * constant per program, not a per-op weight — it cannot change the ORDER of two programs under
	 * any budget, which is the only thing a weight table is used for.
	 */
	private static void measureOutRow(Row addRow) throws Exception {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		OcslVm vm = finish(b, b.builtin(SurfaceTable.REG_TIME));
		final float[] out = new float[4];

		// BOTH paths warmed BEFORE either is measured, which this row alone used to get wrong: it
		// timed run() at a point where output() had never been called anywhere in the process, then
		// warmed the combined path afterwards. output() reaches component(), the same method
		// execute() uses, so the new call site can reprofile it -- measured at +0.70 ns, i.e. small,
		// but it is the one program warmEveryRow does not cover and the one place the bench broke
		// its own rule. And then interleaved against ADD like everything else.
		OcslVm addFull = vmFor(addRow, stepsOf(addRow));
		OcslVm addFloor = vmFor(addRow, 0);
		for (int i = 0; i < WARMUP; i++) {
			vm.run();
			vm.output(OcslWire.PROP_COLOR, out);
			addFull.run();
			addFloor.run();
		}
		double[] samples = new double[TRIALS * REPS];
		int n = 0;
		for (int t = 0; t < TRIALS * REPS; t++) {
			double add = (timeLoop(addFull) - timeLoop(addFloor)) / (double) stepsOf(addRow);
			double runOnly = timeLoop(vm);
			double withOutput = timeLoopCollecting(vm);
			if (add > 0) {
				samples[n++] = (withOutput - runOnly) / add;
			}
		}
		Ratio outRatio = summarise(samples, n);
		printDerived("OUT", outRatio, "");
		System.out.println("  OUT is SKIPPED by run() -- output() reads the property lazily, so this");
		System.out.println("  is the cost of collecting a result, not of executing an op. And every");
		System.out.println("  legal program has exactly one: it is a per-program constant, and a");
		System.out.println("  constant cannot change the ORDER of two programs under any budget.");
	}

	// ---------------------------------------------------------------- program construction

	/** Build this row's program with {@code steps} steps; {@code steps == 0} is the row's floor. */
	private static IrProgram build(Row row, int steps) {
		OcslBuilder b = OcslBuilder.forStage(OcslWire.STAGE_PIXEL_MATERIAL);
		Expr ctx = row.prologue == null ? null : row.prologue.emit(b);
		Expr x = b.builtin(SurfaceTable.REG_TIME);
		for (int i = 0; i < steps; i++) {
			x = row.chain.step(b, x, ctx);
		}
		b.out(OcslWire.PROP_COLOR, x.splat(4).mul(b.builtin(SurfaceTable.REG_TINT)));
		return b.build();
	}

	private static OcslVm vmFor(Row row, int steps) throws Exception {
		return prepare(new OcslVm(IrValidator.validate(build(row, steps))));
	}

	/** Close a hand-built program off with the standard trailer and hand back a ready VM. */
	private static OcslVm finish(OcslBuilder b, Expr value) throws Exception {
		b.out(OcslWire.PROP_COLOR, value.splat(4).mul(b.builtin(SurfaceTable.REG_TINT)));
		return prepare(new OcslVm(IrValidator.validate(b.build())));
	}

	private static OcslVm prepare(OcslVm vm) {
		vm.set(SurfaceTable.REG_TIME, 1.5f);
		vm.set(SurfaceTable.REG_TINT, 1f, 1f, 1f, 1f);
		// Bound on every VM, not only the sampling one: an unbound slot reads 0 per component (S5),
		// so a SAMPLE row measured against an unbound slot would be timing the early-out and not the
		// filtering. Binding costs nothing on a program that never samples.
		vm.bind(SAMPLER_SLOT, benchTexture(64, 64));
		return vm;
	}

	/**
	 * A texture whose taps are all distinct and none of them degenerate.
	 *
	 * 64x64 so the whole thing is ~16 KB and stays resident; a 1x1 texture would make every tap
	 * take the same four bytes and measure a cache-hot best case that no real program sees.
	 */
	private static OcslTexture benchTexture(int width, int height) {
		byte[] rgba = new byte[width * height * 4];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int base = (y * width + x) * 4;
				for (int c = 0; c < 4; c++) {
					rgba[base + c] = (byte) ((x * 37 + y * 61 + x * y * 13 + c * 17) & 0xFF);
				}
			}
		}
		return new OcslTexture(width, height, rgba);
	}

	// ---------------------------------------------------------------- declaration check

	/**
	 * Verify a row emits exactly what it says it emits.
	 *
	 * The composition is DERIVED, not trusted: the opcode histogram of a two-step program minus
	 * that of a one-step program is precisely one step's ops, with the prologue and trailer
	 * cancelling. A row whose chain grew a companion — a swizzle to get back to a float, a splat to
	 * widen an operand — then fails here by name, instead of quietly folding that companion's cost
	 * into the op being priced. That is the exact defect this bench's first version shipped as a
	 * footnote.
	 */
	private static void checkDeclaredComposition(Row row) {
		Map<String, Integer> observed = histogram(build(row, 2));
		Map<String, Integer> once = histogram(build(row, 1));
		for (Map.Entry<String, Integer> e : once.entrySet()) {
			Integer had = observed.get(e.getKey());
			int left = (had == null ? 0 : had.intValue()) - e.getValue().intValue();
			if (left <= 0) {
				observed.remove(e.getKey());
			} else {
				observed.put(e.getKey(), Integer.valueOf(left));
			}
		}
		Map<String, Integer> declared = new LinkedHashMap<String, Integer>();
		for (int i = 0; i < row.emits.length; i++) {
			Integer n = declared.get(row.emits[i]);
			declared.put(row.emits[i], Integer.valueOf(n == null ? 1 : n.intValue() + 1));
		}
		if (!render(declared).equals(render(observed))) {
			throw new IllegalStateException("row " + row.op + " declares it emits "
					+ render(declared) + " per step, but the builder emitted " + render(observed)
					+ "; the isolated column subtracts the DECLARED companions, so a mismatch"
					+ " misprices this row and every row that leans on it");
		}
	}

	private static Map<String, Integer> histogram(IrProgram program) {
		Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
		List<IrOp> ops = program.ops();
		for (int i = 0; i < ops.size(); i++) {
			String name = OcslWire.shapeOf(ops.get(i).opcode).name;
			Integer n = counts.get(name);
			counts.put(name, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
		}
		return counts;
	}

	private static String render(Map<String, Integer> counts) {
		List<String> parts = new ArrayList<String>();
		for (Map.Entry<String, Integer> e : counts.entrySet()) {
			parts.add(e.getKey() + "x" + e.getValue());
		}
		java.util.Collections.sort(parts);
		return parts.toString();
	}

	// ---------------------------------------------------------------- timing

	/** Nanoseconds per {@link OcslVm#run}, median of trials, each trial the min over a warmed loop. */
	private static double nsPerRun(OcslVm vm) {
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
			trials[t] = best / (double) RUNS;
		}
		Arrays.sort(trials);
		// The program's OUTPUT, not `time`. This read `vm.get(REG_TIME, 0)`, which is an INPUT
		// register no program writes -- it returned the 1.5 that `prepare` put there, identically for
		// every VM, and observed nothing the timed loop computed. The comment claiming it kept the
		// loop from being proven dead was simply false. (HotSpot almost certainly never exploited
		// it: the frame array escapes into a field. Fixed because the claim has to be true, not
		// because a number moved.)
		vm.output(OcslWire.PROP_COLOR, SINK_BUFFER);
		sink += SINK_BUFFER[0];
		return trials[TRIALS / 2];
	}
}
