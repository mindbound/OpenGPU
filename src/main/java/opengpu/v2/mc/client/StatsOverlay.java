package opengpu.v2.mc.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

import opengpu.v2.stats.RenderStats;

/**
 * F3-style debug overlay for the render counters, toggled with a key.
 *
 * Exists because OpenGPU had NO performance instrumentation at all — the roadmap's "no
 * performance baselines exist" has been true since before the renderer rework, and the caps and
 * strategies since built on top of it were argued rather than measured. The immediate question
 * it answers: node interpolation turned scene re-rendering from "when a batch arrives" (≤20 Hz)
 * into "every frame while anything moves" (60–200 Hz), and nobody has ever seen what that costs.
 *
 * <h2>Rates, not totals</h2>
 * Cumulative counters answer the wrong question for a live overlay: a total that only grows
 * tells you nothing about what the scene is doing right now, which is the entire point of
 * watching it while a program animates. Everything below is sampled over a rolling window and
 * shown per second, with the raw totals available only on the second page.
 *
 * <h2>Resetting</h2>
 * SHIFT + the toggle key, while the overlay is VISIBLE, zeroes every CLIENT counter and
 * restarts the sampling window. Added 2026-08-21: run-scoped benchmarking needs a clean zero,
 * and until then the only reset was restarting the client — {@code RenderStats.reset()} existed
 * with no production caller. "Since load" throughout this class therefore means "since load or
 * the last reset". Visible-only because shift is also the default sneak key, so a hidden reset
 * would make sneak+toggle a silent benchmark wipe. CLIENT counters only: the server per-scene
 * rows on the detailed page are {@code SceneStats}, untouched here — those zero when a Lua
 * program calls {@code getStats}, which is the server side's own window mechanism.
 *
 * <h2>Why it costs nothing when hidden</h2>
 * The counters themselves are always live — they are long increments and one nanoTime pair per
 * scene render — but this class does no work at all until toggled on, and the sampling only
 * runs while it is visible. An overlay that perturbed the numbers it displays would be worse
 * than no overlay.
 */
public final class StatsOverlay {

	/** Sampling window. Long enough to be steady, short enough to react to a program starting. */
	private static final long WINDOW_NANOS = 500L * 1000L * 1000L;

	private static StatsOverlay instance;

	private final KeyBinding toggle;
	private boolean visible;
	private boolean detailed;

	// Rolling-window sample state.
	private long windowStart;
	private long lastPrePasses, lastFramesWithWork, lastRenders, lastInterpRenders;
	private long lastNanos, lastCommands;
	private long lastUploadBytes, lastUploads;

	// Latest computed rates, held so the overlay draws steady numbers between samples rather
	// than flickering with whatever the instantaneous delta happened to be.
	private double fps, rendersPerSec, interpPct, meanMicros, workPct;
	private double uploadKbPerSec, nanosPerCommand;
	private long maxMicros;

	private StatsOverlay(KeyBinding toggle) {
		this.toggle = toggle;
		this.windowStart = System.nanoTime();
	}

	public static void init(KeyBinding toggle) {
		if (instance == null) {
			instance = new StatsOverlay(toggle);
			cpw.mods.fml.common.FMLCommonHandler.instance().bus().register(instance);
			net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(instance);
		}
	}

	@SubscribeEvent
	public void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END || toggle == null) {
			return;
		}
		// isPressed() consumes the press, so this must be polled exactly once per tick and
		// nowhere else, or the toggle silently misses every other keypress.
		while (toggle.isPressed()) {
			// SHIFT+key RESETS the client counters instead of cycling the view. Run-scoped
			// benchmarking needs a clean zero, and until this existed the only reset was
			// restarting the client — RenderStats.reset() had no production caller at all.
			//
			// ONLY WHILE VISIBLE, and that is a safety decision, not a limitation: shift is
			// also the default sneak key, so a hidden reset would let sneak+toggle silently
			// wipe a benchmark with no feedback at all. Visible, the page zeroing in front of
			// you IS the feedback; hidden, shift+key just opens the overlay like any press.
			if (visible
					&& (org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LSHIFT)
							|| org.lwjgl.input.Keyboard.isKeyDown(
									org.lwjgl.input.Keyboard.KEY_RSHIFT))) {
				RenderStats.reset();
				resetWindow();
				continue;
			}
			if (!visible) {
				visible = true;
				detailed = false;
				resetWindow();
			} else if (!detailed) {
				detailed = true;
			} else {
				visible = false;
			}
		}
	}

	private void resetWindow() {
		windowStart = System.nanoTime();
		lastPrePasses = RenderStats.prePasses;
		lastFramesWithWork = RenderStats.framesWithWork;
		lastRenders = RenderStats.sceneRenders;
		lastInterpRenders = RenderStats.interpolationRenders;
		lastNanos = RenderStats.renderNanos;
		lastCommands = RenderStats.commandsReplayed;
		lastUploadBytes = RenderStats.uploadBytes;
		lastUploads = RenderStats.uploads;
	}

	private void sample() {
		long now = System.nanoTime();
		long elapsed = now - windowStart;
		if (elapsed < WINDOW_NANOS) {
			return;
		}
		double seconds = elapsed / 1.0e9;
		long prePasses = RenderStats.prePasses - lastPrePasses;
		long framesWithWork = RenderStats.framesWithWork - lastFramesWithWork;
		long renders = RenderStats.sceneRenders - lastRenders;
		long interp = RenderStats.interpolationRenders - lastInterpRenders;
		long nanos = RenderStats.renderNanos - lastNanos;
		long commands = RenderStats.commandsReplayed - lastCommands;
		long uploadBytes = RenderStats.uploadBytes - lastUploadBytes;

		fps = prePasses / seconds;
		rendersPerSec = renders / seconds;
		interpPct = renders == 0 ? 0.0 : 100.0 * interp / renders;
		workPct = prePasses == 0 ? 0.0 : 100.0 * framesWithWork / prePasses;
		meanMicros = renders == 0 ? 0.0 : nanos / (double) renders / 1000.0;
		nanosPerCommand = commands == 0 ? 0.0 : nanos / (double) commands;
		uploadKbPerSec = uploadBytes / 1024.0 / seconds;
		maxMicros = RenderStats.renderNanosMax / 1000L;   // high-water since reset; labelled below

		resetWindow();
	}

	@SubscribeEvent
	public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
		if (!visible || event.type != RenderGameOverlayEvent.ElementType.TEXT) {
			return;
		}
		sample();
		Minecraft mc = Minecraft.getMinecraft();
		if (mc == null || mc.fontRenderer == null) {
			return;
		}

		List<String> lines = new ArrayList<String>();
		lines.add("§b[OpenGPU]§r render");
		lines.add(String.format("  pre-pass %.0f/s   scene renders %.0f/s", fps, rendersPerSec));
		// The headline number: what fraction of re-renders exist only because a node is still
		// gliding. High here with nothing moving means active() is not going false.
		lines.add(String.format("  interpolation-driven %.0f%%   frames doing work %.0f%%",
				interpPct, workPct));
		// Microseconds, not milliseconds: a scene render under 1 ms truncated to "0 ms", which
		// is every healthy frame. And the worst figure is a high-water mark since load or the
		// last shift-reset rather than a windowed one, so it is labelled instead of sitting
		// next to a per-second number as though it were one.
		lines.add(String.format("  render %.0f us mean (worst since reset %d us)", meanMicros,
				maxMicros));
		lines.add(String.format("  %.0f ns/command   upload %.1f KiB/s", nanosPerCommand,
				uploadKbPerSec));

		// Shown only once an animator has actually run, so the overlay costs no line on the
		// scenes that predate the feature. "us/eval": the mean is per EVALUATION of a scene
		// that had an attached animator, not per frame — several scenes evaluate per frame and
		// frames without animated scenes are not in the denominator. The charge stats are the
		// compile-time data ANIM-16's cap decision is waiting for (PLAN, op-caps section);
		// "programs" counts COMPILES SINCE CLIENT LOAD, so a world rejoin legitimately doubles
		// it -- climbing while you watch is the recompile-per-frame signature, a stable value
		// is not.
		if (RenderStats.animatorEvaluations > 0) {
			long avg = RenderStats.animatorProgramsCompiled == 0 ? 0
					: RenderStats.animatorChargeTotal / RenderStats.animatorProgramsCompiled;
			// The ANIMATOR's cap, not the bare ceiling: every charge on this line came from an
			// animator program, so the denominator has to be the budget those programs were
			// actually priced against. NOT equal since the same-day raise (animator 512, ceiling 1024);
			// quoting the ceiling here would overstate the animator's budget by 2x the moment
			// diverge, and then this line would silently quote the wrong one.
			lines.add(String.format(
					"  animator %.0f us/eval   programs %d (charge avg %d, max %d/%d)",
					RenderStats.meanAnimatorMicros(), RenderStats.animatorProgramsCompiled,
					avg, RenderStats.animatorChargeMax,
					opengpu.v2.ocsl.IrValidator.maxStructuralOps(
							opengpu.v2.ocsl.OcslWire.STAGE_ANIMATOR)));
			// THE OTHER TWO CAPS, on their own line because the charge above cannot stand in for
			// them: the 2026-08-21 raise moved all three together precisely BECAUSE frame and
			// registers were binding first (a vec4 chain ran out of frame at 255 charged ops
			// against a 256 op cap), and only the charge was ever instrumented. Reading all three
			// side by side is what tells the next cap round which number to spend.
			//
			// Both denominators are the ACCEPTANCE caps in SurfaceTable, deliberately, and the
			// register one is a live trap: `OcslWire.MAX_REGISTERS` is a SEPARATE constant of the
			// same name and the same value today, but it is the DECODER's bound — what a blob may
			// describe — while SurfaceTable's is what the validator ACCEPTS. These programs were
			// priced against acceptance, so acceptance is the honest denominator, and quoting the
			// codec's would silently misreport the moment the two diverge. Same reasoning as the
			// animator-vs-ceiling choice on the charge line above, one layer down.
			if (RenderStats.animatorProgramsCompiled > 0) {
				lines.add(String.format(
						"    frame avg %d, max %d/%d floats   regs avg %d, max %d/%d",
						RenderStats.animatorFrameWidthTotal / RenderStats.animatorProgramsCompiled,
						RenderStats.animatorFrameWidthMax,
						opengpu.v2.ocsl.SurfaceTable.MAX_FRAME_WIDTH,
						RenderStats.animatorRegistersTotal / RenderStats.animatorProgramsCompiled,
						RenderStats.animatorRegistersMax,
						opengpu.v2.ocsl.SurfaceTable.MAX_REGISTERS));
			}
			// THE PER-NODE LINE — the measurement ANIM-16's budget calibrates against, and the
			// reason it is here rather than derived on paper: until this shipped, every per-node
			// figure was a scene total divided by a node count read off a Lua script. Two cost
			// models fit the field data and differ 16x at the op cap, so the constant has to be
			// measured, not inferred. us/node is the slope's intercept; nodes/eval is what
			// separates "one big scene" from "many small ones" in the same reading.
			if (RenderStats.animatorNodesEvaluated > 0) {
				lines.add(String.format(
						"    %.2f us/node over %d node-evals (%.1f nodes/eval)",
						RenderStats.animatorNanos / 1000.0
								/ RenderStats.animatorNodesEvaluated,
						RenderStats.animatorNodesEvaluated,
						RenderStats.animatorNodesEvaluated
								/ (double) Math.max(1L, RenderStats.animatorEvaluations)));
				// THE CEILING ON WHAT THE BUDGET CAN SAVE. Holding nodes declines only their own
				// evaluation; the fixed per-pass cost and the scene's GL re-render go only when
				// the whole scene short-circuits, which needs the scene otherwise settled. This
				// is the fraction of animator passes where that was available. A low reading
				// means ANIM-16's budget has little to work with in this workload -- which is a
				// finding about the workload, not a number to tune.
				lines.add(String.format(
						"    %.0f%% of passes on a settled scene (the budget's reachable ceiling)",
						100.0 * RenderStats.animatorScenePassesSettled
								/ Math.max(1L, RenderStats.animatorEvaluations)));
			}
			// ONLY WHEN ENGAGED. Absent in a client that never overloads, so the line appearing
			// at all is the signal -- degradation is not a state that should be inferred from a
			// frame-time wobble.
			if (RenderStats.animatorBudgetFrames > 0) {
				lines.add(String.format(
						"    budget ENGAGED %d frames, %.1f scenes/frame at full rate",
						RenderStats.animatorBudgetFrames,
						RenderStats.animatorBudgetAdmissions
								/ (double) RenderStats.animatorBudgetFrames));
			}
		}

		if (detailed) {
			lines.add("§8  totals since load/reset (shift+key zeroes the client rows):§r");
			lines.add(String.format("    pre-passes %d, renders %d (%d interp)",
					RenderStats.prePasses, RenderStats.sceneRenders,
					RenderStats.interpolationRenders));
			lines.add(String.format("    commands replayed %d, uploads %d (%.1f MiB)",
					RenderStats.commandsReplayed, RenderStats.uploads,
					RenderStats.uploadBytes / 1048576.0));
			// Cumulative render time, so a benchmark can be scored over a WHOLE run instead of
			// off the windowed mean above. The windowed figure is a rolling 500 ms sample, which
			// means an operator reading it has to pick which windows to write down — and picking
			// is selection. Two readings of this pair, before and after a run, give an exact
			// run-scoped mean (delta us / delta renders) that no hand-picking can bias, and it
			// costs nothing to compute because both counters are already being kept.
			lines.add(String.format("    render total %.1f ms over %d renders",
					RenderStats.renderNanos / 1.0e6, RenderStats.sceneRenders));
			// The stall share of that total, so a run-scoped mean can be taken with and without
			// hitches. Without it a benchmark cannot tell a real effect from one 33 ms stall,
			// which at this frame rate is worth 13 us on a 20 s run -- the size of the effects
			// being measured. Subtract both figures from the pair above for a hitch-free mean.
			lines.add(String.format("    of which stalls (>%d ms): %d renders, %.1f ms",
					RenderStats.STALL_NANOS / 1000000L, RenderStats.stallRenders,
					RenderStats.stallNanos / 1.0e6));
			// The FBO save/restore, which the render total above deliberately EXCLUDES. It is
			// once per frame rather than once per scene, so this is the shared cost that does
			// not multiply with scene count — the quantity scenetest.lua exists to look for,
			// and the one the per-scene mean would otherwise hide entirely.
			lines.add(String.format("    pass save/restore: %d opens, %.1f ms",
					RenderStats.passOpens, RenderStats.passNanos / 1.0e6));
			lines.add(String.format("    textures deferred for budget %dx",
					RenderStats.texturesDeferred));
			appendServerLines(lines);
		}

		int y = 2 + 10 * 12;   // below vanilla's F3 block and Forge's own HUD text list
		for (String line : lines) {
			mc.fontRenderer.drawStringWithShadow(line, 2, y, 0xFFFFFF);
			y += 10;
		}
	}

	/**
	 * Server-side wire counters, but ONLY in single-player.
	 *
	 * The two halves of the measurement live in different processes and normally cannot be read
	 * together — bytes/tick is produced on the server, render cost on the client. An integrated
	 * server shares the JVM, which is the configuration a baseline is actually taken in, so the
	 * numbers can be shown side by side there. On a dedicated server this section is absent
	 * rather than wrong, and the Lua callback is the channel instead.
	 *
	 * These are plain long reads off the server thread. A torn read shows one stale figure for
	 * half a second in a debug overlay, which is an acceptable trade for not taking a lock on
	 * the render thread.
	 */
	private static void appendServerLines(List<String> lines) {
		Minecraft mc = Minecraft.getMinecraft();
		if (mc == null || !mc.isSingleplayer()) {
			lines.add("§8  (server counters: single-player only; use gpu.getStats() otherwise)§r");
			return;
		}
		opengpu.v2.mc.server.V2ServerRuntime runtime = opengpu.v2.mc.server.V2ServerRuntime.get();
		if (runtime == null) {
			return;
		}
		lines.add("§8  server, per scene:§r");
		int shown = 0;
		for (String sceneId : runtime.sceneIds()) {
			opengpu.v2.stats.SceneStats s = runtime.statsFor(sceneId);
			if (s == null) {
				continue;
			}
			lines.add(String.format("    %s  %.0f B/tick, %d batches, max %d B, sent %.1f KiB",
					sceneId.length() > 8 ? sceneId.substring(0, 8) : sceneId,
					s.meanBytesPerTick(), s.batches, s.batchBytesMax,
					s.batchBytesSent / 1024.0));
			if (++shown >= 4) {
				lines.add("    ...");
				break;
			}
		}
	}
}
