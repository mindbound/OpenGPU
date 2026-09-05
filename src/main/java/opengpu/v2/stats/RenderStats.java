package opengpu.v2.stats;

/**
 * Client-side render counters — the half of the picture the server cannot see.
 *
 * The immediate reason this exists: node interpolation (landed 2026-08-04) changed the render
 * cost model and nobody measured it. A scene used to re-render when a batch arrived, at most
 * 20 times a second. It now re-renders on every frame while any node is mid-flight — 60 to 200
 * times a second — replaying the whole command list through immediate-mode GL, on a runtime
 * where immediate mode is emulated. Stage B's animators would multiply exactly this, so the
 * number is wanted before anything is built on top of it.
 *
 * <h2>What is timed, and what deliberately is not</h2>
 * One {@link System#nanoTime()} pair around a whole scene render. At 200 Hz that is 400 calls a
 * second, comfortably below noise. Timing individual primitives would cost more than the
 * primitives — the thing being measured is a {@code glBegin}/{@code glEnd} pair — so the
 * per-command figure is derived by dividing, not sampled.
 *
 * <h2>Why frames and renders are counted separately</h2>
 * {@link #framesWithWork} over {@link #prePasses} is the interpolation cost directly: the
 * fraction of frames that had to redraw at all. A settled scene should sit near zero, and if it
 * does not, {@code active()} is failing to go false — which is the specific regression this
 * class would catch and no test can.
 *
 * Static because there is one client and one render thread; every mutation below happens on it.
 */
public final class RenderStats {

	private RenderStats() {}

	/**
	 * Pre-passes run — EVERY in-world client frame with FBO support, NOT only frames with a scene
	 * in use: {@code V2ClientRuntime} calls {@code prePass} with no emptiness guard and the
	 * increment below is unconditional. The javadoc claimed "frames in which any scene was in
	 * use" until 2026-08-21, and it matters because this is the denominator of every rate
	 * reported against it.
	 */
	public static long prePasses;
	/** Pre-passes where at least one scene actually re-rendered. */
	public static long framesWithWork;

	/** Scene FBO re-renders. Can exceed framesWithWork when several scenes are visible. */
	public static long sceneRenders;
	/** Re-renders caused by interpolation rather than by an arriving batch. */
	public static long interpolationRenders;

	public static long renderNanos;
	public static long renderNanosMax;

	/**
	 * A render this slow is a hitch, not a cost — an order of magnitude above the ~500 us a
	 * heavily-loaded scene takes here, so nothing a benchmark is trying to measure can reach it.
	 */
	public static final long STALL_NANOS = 2L * 1000L * 1000L;
	/**
	 * Renders over {@link #STALL_NANOS}, and the time they took.
	 *
	 * {@link #renderNanos} is a SUM, so a benchmark scored by differencing it absorbs a rare
	 * multi-millisecond hitch undiluted and silently: at ~124 fps one 33 ms stall shifts a 20 s
	 * run-scoped mean by 13 us, which is the same size as the effects being measured. The text
	 * measurement hit exactly that — two runs of one configuration landed 13.3 us apart while
	 * another pair landed 0.2 us apart, and nothing in the instrument could say whether the
	 * difference was noise or one stall. These two counters make that separable: subtract
	 * stallNanos from the numerator and stallRenders from the denominator for a hitch-free mean,
	 * and compare against the raw one.
	 */
	public static long stallRenders;
	public static long stallNanos;

	/**
	 * The FramebufferPass save/restore: how many times it was opened, and what that cost.
	 *
	 * Counted because hoisting the pass out of the per-scene loop (2026-08-09) otherwise DELETES
	 * this quantity from the instrument. {@link #renderNanos} used to include one save/restore
	 * per scene render; it now includes none, so the reported per-scene mean drops by that amount
	 * whether or not any real work was saved. With a single visible scene the true saving is
	 * exactly zero and the apparent improvement is entirely that artifact.
	 *
	 * It also restores what {@code ingame/scenetest.lua} was written to look for. That harness
	 * asks whether per-render cost multiplies with the number of scenes, and names its own target
	 * as "a SHARED cost that does not multiply". The hoist created exactly such a term — and
	 * removing it from the numerator the harness reads would have made the harness report perfect
	 * additivity at every N, i.e. it could no longer fail. A test that cannot fail is not a test.
	 */
	public static long passOpens;
	public static long passNanos;

	/** Canvas commands replayed, so cost per command can be derived. */
	public static long commandsReplayed;

	/** Texture bytes uploaded and the uploads that carried them. */
	public static long uploadBytes;
	public static long uploads;
	/**
	 * TEXTURES deferred because the per-frame upload budget ran out — not frames.
	 *
	 * Several textures can be deferred in one frame, so this counts higher than the number of
	 * frames affected. Named and documented for what it is because the previous wording said
	 * "frames", and a reader comparing it against the frame count would have concluded the
	 * budget was exhausted more often than it is.
	 */
	public static long texturesDeferred;

	/**
	 * Animator cost, counted only over renders of scenes that HAD an attached animator, so the
	 * mean means "what animators cost" rather than being diluted by scenes that have none.
	 */
	public static long animatorEvaluations;
	public static long animatorNanos;

	/**
	 * Structural-op charges of animator programs compiled this session — the real-usage data
	 * PLAN's op-cap section says the cap decision (ANIM-16 / Phase 4) must meet. A program is
	 * charged once, at compile, because the charge is a property of the program.
	 */
	public static long animatorProgramsCompiled;
	public static long animatorChargeTotal;
	public static int animatorChargeMax;

	/**
	 * The two dimensions the op charge does NOT bound — and the data the 2026-08-21 cap round
	 * did not have.
	 *
	 * That round raised four numbers together (op ceiling 256→1024, animator stage 256→512,
	 * registers 512→1024, frame 1024→2048) because measurement showed the register and frame
	 * caps binding BELOW the op cap they accompany: {@code SurfaceTable}'s own note records a
	 * vec4-chain shape that ran out of FRAME at 255 charged ops — one short of the then-256 op
	 * cap — and SSA's register-per-op layout binding straight-line code near ~400 registers. So
	 * "how many ops did real programs charge" cannot answer "which cap will a real program hit
	 * first", and only the op number was ever instrumented. These two are the missing columns.
	 *
	 * Both are recorded at COMPILE beside the charge, for the same reason: they are properties
	 * of the program, not of a frame. Frame width is in FLOATS (a vec4 register occupies four),
	 * which is why it is a different quantity from the register count rather than a multiple of
	 * it — a program of many scalars and one of few vec4s can carry the same charge and sit at
	 * opposite ends of both caps.
	 *
	 * READ THE FRAME COLUMN AGAINST ITS FLOOR, NOT AGAINST ZERO. The validator types the
	 * builtins from the surface table and lays out every TYPED builtin whether the program reads
	 * it or not, so the animator's readable builtin block is in the frame of even a one-op
	 * program: measured 2026-08-22, a trivial {@code out(x, <pooled constant>)} program lays out
	 * <b>34 floats</b> before computing anything. The program's own contribution is the excess
	 * over that floor. This is exactly why the charge cannot stand in for the frame — they do
	 * not even share an origin — and it is pinned by
	 * {@code everyAnimatorProgramPaysTheBuiltinBlockBeforeItComputesAnything}.
	 *
	 * The floor is a floor for every program the BUILDER produces, not a universal law: the
	 * typing loop runs to {@code min(declaredRegisters, BUILTIN_LIMIT)}, so a hand-encoded blob
	 * declaring fewer registers than the builtin block is wide types — and therefore lays out —
	 * only that many. Builder output always declares {@code WORKING_BASE} (112) or more, so the
	 * distinction cannot arise from anything this client compiles today; it is stated because a
	 * blob arrives over the WIRE, and "every program" would be the wrong thing to believe when
	 * reading a number a peer's encoder produced.
	 */
	public static long animatorFrameWidthTotal;
	public static int animatorFrameWidthMax;
	public static long animatorRegistersTotal;
	public static int animatorRegistersMax;

	/**
	 * Animator program COMPILES that DECLARED at least one uniform — every such uniform reads
	 * 0.0. COMPILES, on the sibling counters' convention: {@code onAnimatorCompile} runs on a
	 * {@code vms} cache miss, and an epoch change or a world rejoin recompiles, so five portal
	 * hops read 5 in a world holding one program.
	 *
	 * Not a performance counter. The other columns here answer "which cap will a real program
	 * hit first"; this one answers "is anybody walking into the uniform gap", and it exists
	 * because that gap is otherwise SILENT. A program may declare a uniform, validate, encode,
	 * persist and attach, and then evaluate against a zero-initialised frame forever: nothing in
	 * {@code src/main} binds a register at or above {@code SurfaceTable.UNIFORM_BASE} — the
	 * uniform table and its set-call exist (C1.1/C1.2), but the client BINDING is C1.3's, so
	 * until it lands there is no failure to observe: the frame is simply zero.
	 *
	 * DECLARES, not READS, and the distinction is deliberate rather than sloppy. The number this
	 * counts is {@code IrValidator.Validated.uniformComponents}, which the validator takes from
	 * the blob's DECLARATION; it does not track which uniforms an op actually reads. A program
	 * that declares a uniform and never reads it is counted here and suffers nothing. Reporting
	 * "reads" would be the stronger claim and this counter cannot support it.
	 *
	 * It also doubles as the "have we said this yet" latch for the one log line
	 * ({@code AnimatorOverlay} warns on the 0 → 1 transition), which is why there is no second
	 * piece of state to keep in step: {@link #reset()} clears the count and the latch together,
	 * because they are the same field.
	 */
	public static long animatorProgramsWithUniforms;

	/**
	 * NODES for which a VM actually ran — not scenes, not frames, and not attachments.
	 *
	 * The unit is named out loud because this class has already paid for the alternative once
	 * ({@link #texturesDeferred}, whose predecessor said "frames" and made readers conclude the
	 * budget was exhausted more often than it is). Until this counter existed the only animator
	 * instrument was per-SCENE ({@link #animatorEvaluations}), so every per-node figure in
	 * circulation — including the ~6-9 us/node the field test reports — was a DIVISION of a
	 * scene total by a node count read off a Lua script, not a measurement. ANIM-16's budget is
	 * calibrated against a per-node constant, so it needs the denominator measured.
	 *
	 * EXCLUDES dangling attachments deliberately: a node whose program was freed is skipped
	 * before any VM work (ANIM-17 rules that legal), so counting it would inflate the
	 * denominator with nodes that cost nothing and make the per-node mean read LOW.
	 */
	public static long animatorNodesEvaluated;

	/**
	 * NODES whose program was SKIPPED in favour of their previous output — the other half of
	 * {@link #animatorNodesEvaluated}, and the two never count the same node twice in one pass.
	 *
	 * "Skipped", not "recomposed": an entry that claims no properties (a program declaring only
	 * Stage C ids, which the evaluator stores nothing for) is counted here and recomposes nothing,
	 * because what this measures is work DECLINED, not work done.
	 *
	 * LIVE SINCE 2026-08-22. It read zero in every build up to increment 5, when
	 * {@code AnimatorBudget} became the wired hold policy; before that the only policy anywhere in
	 * production was {@code AnimatorOverlay.EVALUATE_ALWAYS} and nothing could ever be held. That
	 * sentence stood in this javadoc after the budget landed, which would have told anyone reading
	 * the instrument that a zero here is expected — on the very counter ANIM-16's field test has
	 * to read.
	 *
	 * A zero now means the budget never engaged, which is the normal state for a client that is
	 * not overloaded and a FINDING for one that is. It exists so the amount of declined work is
	 * visible on the same footing as the work done: a budget whose effect can only be inferred
	 * from a frame-time change is a budget nobody can tell apart from a stutter.
	 *
	 * <b>It is also the correction term for the per-node instrument.</b>
	 * {@code animatorNanos / animatorNodesEvaluated} charges a frame's whole overlay cost to the
	 * nodes that ran a VM, so on a mixed frame the recomposition of held nodes lands in the
	 * numerator with no denominator of its own. That bias is upward and small — recomposition is
	 * a base read and at most six composes — but it is real, and this counter is what makes it
	 * quantifiable rather than invisible.
	 */
	public static long animatorNodesHeld;

	/**
	 * Animator scene-passes that ran on an otherwise-SETTLED scene — not dirty, not uploading,
	 * already drawn once, not interpolating.
	 *
	 * Against {@link #animatorEvaluations} this is the fraction of animator work that occurred on
	 * a scene the budget could have skipped ENTIRELY, fixed cost and GL re-render included, had
	 * every one of its nodes been held. On any other frame, holding declines only the VM runs,
	 * which for typical content is a fraction of a microsecond per node against ~12 us of fixed
	 * cost — so this ratio is the ceiling on how much ANIM-16's budget can actually save, and it
	 * was never measured before increment 5. If it reads low, the budget's main lever does not
	 * exist in this workload and the honest response is to say so rather than tune thresholds.
	 *
	 * A LOWER BOUND once degradation is active: a settled scene whose nodes are all held
	 * short-circuits before this increments, and is absent from the denominator too.
	 */
	public static long animatorScenePassesSettled;

	/**
	 * Frames the client-global animator budget spent in degradation, and scene-admissions it
	 * granted while there.
	 *
	 * Both read zero in an unloaded client by design — degradation is the exceptional state, and
	 * a budget whose engagement can only be inferred from a frame-time change is one nobody can
	 * tell apart from a stutter. {@code animatorBudgetAdmissions} over
	 * {@code animatorBudgetFrames} is the mean number of scenes running at full rate while
	 * engaged, which is what the rotation period actually is.
	 */
	public static long animatorBudgetFrames;
	public static long animatorBudgetAdmissions;

	public static void onAnimatorEvaluate(long nanos) {
		animatorEvaluations++;
		animatorNanos += nanos;
	}

	/**
	 * @param structuralOps    the post-unroll charge, the number the stage cap is enforced on
	 * @param frameWidth       the laid-out frame in FLOATS, against {@code MAX_FRAME_WIDTH}
	 * @param declaredRegisters the blob's register count, against {@code MAX_REGISTERS}
	 * @param uniformComponents the blob's DECLARED uniform count — see
	 *        {@link #animatorProgramsWithUniforms}, which this only increments when it is > 0
	 */
	public static void onAnimatorCompile(int structuralOps, int frameWidth, int declaredRegisters,
			int uniformComponents) {
		animatorProgramsCompiled++;
		animatorChargeTotal += structuralOps;
		if (structuralOps > animatorChargeMax) {
			animatorChargeMax = structuralOps;
		}
		animatorFrameWidthTotal += frameWidth;
		if (frameWidth > animatorFrameWidthMax) {
			animatorFrameWidthMax = frameWidth;
		}
		animatorRegistersTotal += declaredRegisters;
		if (declaredRegisters > animatorRegistersMax) {
			animatorRegistersMax = declaredRegisters;
		}
		if (uniformComponents > 0) {
			animatorProgramsWithUniforms++;
		}
	}

	public static double meanAnimatorMicros() {
		return animatorEvaluations == 0 ? 0.0
				: animatorNanos / (double) animatorEvaluations / 1000.0;
	}

	public static void onPrePass(boolean didWork) {
		prePasses++;
		if (didWork) {
			framesWithWork++;
		}
	}

	/**
	 * 3D-layer time, accumulated separately and SUBTRACTED from the per-command figure.
	 *
	 * The 3D pass runs inside the render window but contributes ZERO commands, so folding it
	 * into {@code renderNanos} would inflate {@link #nanosPerCommand()} by a term that grows
	 * with mesh count and shrinks with command count — against a figure PERF-BASELINE compares
	 * to and {@code StatsOverlay} prints on screen. Declaring the number "2D-only" in a document
	 * would not have helped: the player still reads it, and the document is not where they read
	 * it.
	 *
	 * Kept rather than discarded, because it is the only measurement of what the 3D layer costs
	 * and because a hidden subtraction is worse than a visible one.
	 */
	private static long threeDNanos;
	private static long threeDDraws;

	public static void onThreeDLayer(long nanos, int meshesDrawn) {
		threeDNanos += nanos;
		threeDDraws += meshesDrawn;
	}

	/** Total nanoseconds spent in the 3D layer. */
	public static long threeDNanos() {
		return threeDNanos;
	}

	/** Mesh instances drawn across all 3D passes. */
	public static long threeDDraws() {
		return threeDDraws;
	}

	public static void onSceneRender(long nanos, int commands, boolean drivenByInterpolation) {
		sceneRenders++;
		renderNanos += nanos;
		commandsReplayed += commands;
		if (nanos > renderNanosMax) {
			renderNanosMax = nanos;
		}
		if (nanos > STALL_NANOS) {
			stallRenders++;
			stallNanos += nanos;
		}
		if (drivenByInterpolation) {
			interpolationRenders++;
		}
	}

	public static void onUpload(int bytes) {
		uploads++;
		uploadBytes += bytes;
	}

	public static void onTextureDeferred() {
		texturesDeferred++;
	}

	/** Fraction of pre-passes that had to redraw. Near zero for a settled scene. */
	public static double workFraction() {
		return prePasses == 0 ? 0.0 : (double) framesWithWork / (double) prePasses;
	}

	/** Fraction of re-renders that interpolation caused rather than fresh server state. */
	public static double interpolationFraction() {
		return sceneRenders == 0 ? 0.0 : (double) interpolationRenders / (double) sceneRenders;
	}

	public static double meanRenderMicros() {
		return sceneRenders == 0 ? 0.0 : renderNanos / (double) sceneRenders / 1000.0;
	}

	/**
	 * Nanoseconds per replayed 2D command since load — the figure that decides if replay is the
	 * cost — with the 3D layer's time REMOVED.
	 *
	 * {@code renderNanos} covers the whole render window, which since C1.3.1 group F includes the
	 * 3D pass: work that adds no commands. Subtracting it keeps this a measure of 2D replay,
	 * which is what PERF-BASELINE measured.
	 *
	 * <b>This is the SINCE-LOAD figure and is not what the overlay prints.</b> {@code StatsOverlay}
	 * computes its own rolling-window value and subtracts there too; the two are separate
	 * arithmetic over the same counters, and both must carry the subtraction. An earlier version
	 * of this javadoc claimed the overlay printed THIS method's result — it never has.
	 *
	 * Clamped at zero defensively, not for a known mechanism: with one clock, strictly nested
	 * windows and long accumulators, {@code renderNanos - threeDNanos} is positive by the cost of
	 * two {@code nanoTime} calls. The clamp exists so that a future caller who accumulates the two
	 * on different paths cannot turn a bookkeeping slip into a negative rate on screen.
	 */
	public static double nanosPerCommand() {
		if (commandsReplayed == 0) {
			return 0.0;
		}
		long twoD = renderNanos - threeDNanos;
		return twoD <= 0 ? 0.0 : (double) twoD / (double) commandsReplayed;
	}

	public static void reset() {
		prePasses = 0;
		framesWithWork = 0;
		sceneRenders = 0;
		interpolationRenders = 0;
		renderNanos = 0;
		renderNanosMax = 0;
		stallRenders = 0;
		stallNanos = 0;
		passOpens = 0;
		passNanos = 0;
		commandsReplayed = 0;
		uploadBytes = 0;
		uploads = 0;
		texturesDeferred = 0;
		animatorEvaluations = 0;
		animatorNanos = 0;
		animatorProgramsCompiled = 0;
		animatorChargeTotal = 0;
		animatorChargeMax = 0;
		animatorFrameWidthTotal = 0;
		animatorFrameWidthMax = 0;
		animatorRegistersTotal = 0;
		animatorRegistersMax = 0;
		animatorProgramsWithUniforms = 0;
		animatorNodesEvaluated = 0;
		animatorNodesHeld = 0;
		animatorScenePassesSettled = 0;
		animatorBudgetFrames = 0;
		animatorBudgetAdmissions = 0;
		threeDNanos = 0;
		threeDDraws = 0;
		java.util.Arrays.fill(keyframeGaps, 0L);
		keyframeGapsBackward = 0;
	}

	// ---- keyframe cadence -------------------------------------------------------------------

	/**
	 * How far apart, in server ticks, consecutive keyframes of one node's group arrive.
	 *
	 * <b>This is the input to every result in {@code INTERPOLATION-DELAY-MATH.md} and it has never
	 * been measured on a real program.</b> The analysis proves what a delay policy can and cannot
	 * do given a cadence; it says nothing about which cadences OpenComputers programs actually
	 * emit, and the same {@code executionDelay} figure is currently used in three places to argue
	 * three incompatible things. The buckets are cut at the two boundaries that decide behaviour:
	 *
	 * <pre>
	 *   [0] gap 1     the delay is 2, so this cadence interpolates on ZERO frames -- it steps
	 *   [1] gap 2     D == G: exact, the only perfectly served cadence
	 *   [2] gap 3     glides 2/3 of the interval
	 *   [3] gap 4     glide and jump a dead heat -- the CANDIDATE ceiling at budget 1/2
	 *   [4] gap 5     == GLIDE_MAX_GAP_TICKS: glides 2/5, the SHIPPED ceiling at budget 2/5
	 *   [5] gap 6-10  over the cliff: snap is set before any delay is read; 0% interpolation
	 *   [6] gap 11+   an idle node waking, which the snap rule exists to serve correctly
	 * </pre>
	 *
	 * <b>GAPS 4 AND 5 SHARED BUCKET [3] UNTIL 2026-08-30, WHICH MADE THE CEILING QUESTION
	 * UNANSWERABLE.</b> The one arm that motivates the whole item — {@code os.sleep(0.25)} — reported
	 * "4-5 = 56" and nothing finer, so no reading could say whether the population it measured wanted
	 * a ceiling of 4 or of 5. Both candidates now land on a bucket EDGE, so this cut does not have to
	 * move again when the budget is settled. Changing these edges means changing
	 * {@code ingame/cadence.lua}'s mirror in the same edit — it carries the cut in exactly TWO
	 * places, {@code bucketOf} and {@code BUCKET_NAMES}, and everything else there sizes itself from
	 * {@code #BUCKET_NAMES}. <b>Nothing automated checks that mirror</b>: the file is gitignored,
	 * loaded by no test, and invisible in the {@code git diff} a reviewer reads — the only check is
	 * the S1a step of the field test, comparing the two printed histograms by eye. A silent
	 * disagreement costs the second instrument that caught client-side batch coalescing.
	 *
	 * <b>The first roll of each group is deliberately NOT counted</b>, because it measures the
	 * interval from a node's first appearance to its first movement, not a cadence. A scene that
	 * creates fifty nodes and then starts animating them would otherwise contribute fifty samples
	 * of "however long setup took" into the top bucket, and read as "programs emit enormous gaps".
	 *
	 * <b>A FINAL FIELD HOLDING MUTABLE STATE, which the scalar half of {@code StatsTest}'s reset
	 * sweep cannot see.</b> That sweep skips {@code final} fields — correctly, since
	 * {@link #STALL_NANOS} is a threshold — so this array needed a second sweep written for it. If
	 * another bucket array is added here, it is covered automatically; if the sweep is ever
	 * simplified back to one loop, these seven counters go unguarded again.
	 */
	public static final long[] keyframeGaps = new long[7];

	/**
	 * Rolls where the new tick was not after the old one.
	 *
	 * Its own counter rather than a bucket, because it is not a cadence at all — it is the
	 * {@code currTick <= prevTick} pathology, a duplicate or backward stamp. Folding it into
	 * "gap 0" would let a clock defect read as a very fast program.
	 */
	public static long keyframeGapsBackward;

	/**
	 * Printable label per bucket, parallel to {@link #keyframeGaps}.
	 *
	 * Here rather than in the overlay because the overlay's row used a fixed-arity format string
	 * with seven hand-placed indices, which is {@link #gapBucket}'s edge list written a second time
	 * in a second file — and the 2026-08-30 re-cut had to edit both. The overlay now loops this
	 * array, so the next re-cut touches {@code gapBucket} and this line and nothing downstream.
	 */
	public static final String[] GAP_BUCKET_NAMES = { "1", "2", "3", "4", "5", "6-10", "11+" };

	/**
	 * The first bucket whose gaps are past the glide ceiling — the start of the "snaps" population.
	 *
	 * <b>THIS CONSTANT OWES AN EQUALITY IT CANNOT STATE HERE.</b> It must equal
	 * {@code gapBucket(NodeInterpolator.GLIDE_MAX_GAP_TICKS + 1)}, but {@code NodeInterpolator} is
	 * package-private in another package and this class cannot name it. The equality is therefore
	 * asserted in {@code NodeInterpolatorTest}, which sits in that package and can see both.
	 *
	 * It exists because {@code StatsOverlay} cannot see the ceiling either, and until 2026-08-30 it
	 * hard-coded {@code g[5] + g[6]} and a literal {@code "snaps (gap6+)"} label under a comment
	 * claiming a test kept them in step with the constant. No test reaches {@code StatsOverlay} at
	 * all — {@code AnimatorBudgetTest}'s header says so outright — so the claim was false and the
	 * label was one budget change away from naming the wrong population to a player.
	 */
	public static final int FIRST_SNAPPING_BUCKET = 5;

	/** Bucket index for a gap in ticks. Public so the test can pin the edges against the code. */
	public static int gapBucket(long gap) {
		if (gap <= 1) return 0;
		if (gap == 2) return 1;
		if (gap == 3) return 2;
		if (gap == 4) return 3;   // SPLIT FROM 5 on 2026-08-30: the two candidate ceilings must be
		if (gap == 5) return 4;   // separately countable, or the budget cannot be settled at all
		if (gap <= 10) return 5;
		return 6;
	}

	public static void onKeyframeGap(long gap) {
		if (gap <= 0) {
			keyframeGapsBackward++;
			return;
		}
		keyframeGaps[gapBucket(gap)]++;
	}

	/**
	 * Total CADENCE samples — the buckets, and deliberately NOT {@link #keyframeGapsBackward}.
	 *
	 * <b>This is the denominator the overlay's percentages divide by and the total it prints, so it
	 * must equal the sum of the buckets exactly.</b> The first version added the backward counter in,
	 * which broke that identity the moment the pathology fired: the printed total exceeded the six
	 * buckets, and `steps`/`exact`/`snaps` were computed over a population containing samples that
	 * are not cadences at all.
	 *
	 * That is worse than a wrong percentage. The transcription audit in `FIELD-TEST-CADENCE.md`
	 * depends on each printed row being self-checking — total equals the buckets, percentages
	 * recompute from them — so a mistyped reading contradicts its own line. Mixing a non-bucket
	 * count into the total destroys exactly that redundancy, and destroys it **precisely when the
	 * backward-stamp pathology fires**, which is the case the protocol registers as stop-and-
	 * re-derive. An instrument must not lose its self-check in the situation it exists to report.
	 */
	public static long keyframeGapSamples() {
		long n = 0;
		for (int i = 0; i < keyframeGaps.length; i++) n += keyframeGaps[i];
		return n;
	}
}
