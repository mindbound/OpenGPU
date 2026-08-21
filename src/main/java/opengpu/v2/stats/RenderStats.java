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

	/** Pre-passes run — effectively client frames in which any scene was in use. */
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

	public static void onAnimatorEvaluate(long nanos) {
		animatorEvaluations++;
		animatorNanos += nanos;
	}

	public static void onAnimatorCompile(int structuralOps) {
		animatorProgramsCompiled++;
		animatorChargeTotal += structuralOps;
		if (structuralOps > animatorChargeMax) {
			animatorChargeMax = structuralOps;
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

	/** Nanoseconds per replayed canvas command — the figure that decides if replay is the cost. */
	public static double nanosPerCommand() {
		return commandsReplayed == 0 ? 0.0 : (double) renderNanos / (double) commandsReplayed;
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
	}
}
