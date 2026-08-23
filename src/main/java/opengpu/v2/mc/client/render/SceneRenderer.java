package opengpu.v2.mc.client.render;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import opengpu.OpenGPU;
import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.SceneMirror;
import opengpu.v2.scene.SceneState;
import opengpu.v2.scene.SceneNode;
import opengpu.v2.stats.RenderStats;
import opengpu.v2.sync.MirrorClient;

/**
 * The central per-scene GL cache and pre-pass driver (never TE-owned, per the design).
 * Runs exclusively on the render thread, in RenderTickEvent.START.
 *
 * Responsibilities: FBO-per-scene lifecycle, resource-texture uploads under a per-frame
 * byte budget, pruning GL objects whose resources/mirrors are gone, and re-rendering dirty
 * scenes through {@link FramebufferPass} + {@link Canvas2dRenderer}.
 */
public final class SceneRenderer {
	/** Per-frame texture upload budget: one 1024x512 RGBA texture's worth of bytes. */
	public static final long UPLOAD_BUDGET_PER_FRAME = 2L * 1024 * 1024;

	private static final class TexEntry {
		int glId;
		// Version, NOT array identity: writeRegion mutates the array in place, so an
		// identity check would report "already uploaded" forever while the pixels change.
		int uploadedEpoch;
		int uploadedVersion;
		/** Dimensions of the allocated GL texture; a sub-upload is only valid against these. */
		int glWidth;
		int glHeight;
	}

	private static final class SceneGl {
		int fbo = -1;
		int colorTex = -1;
		int width, height;
		boolean everRendered;
		/**
		 * Set whenever the GL texture set changes. A body that arrives after the batch that
		 * referenced it (budget-deferred, or simply delivered later) does not dirty the
		 * mirror, so without this the scene keeps showing the placeholder until some
		 * unrelated batch happens to arrive.
		 */
		boolean uploadDirty;
		/**
		 * Logical size whose FBO creation already failed. One attempt per distinct size:
		 * retrying costs a real glTexImage2D plus teardown on EVERY pre-pass frame and will
		 * keep failing for the same reason. A later resize clears this and retries naturally.
		 */
		int failedWidth = -1;
		int failedHeight = -1;
		/** Smooths node transforms across the 20 tps channel; see NodeInterpolator. */
		final NodeInterpolator interp = new NodeInterpolator();
		/** Per-frame animator output, held beside the interpolator it composes over. */
		final AnimatorOverlay overlay = new AnimatorOverlay();
		/** Last mirror epoch this cache saw; a change means a new timeline, so snap. */
		int knownEpoch;
		/**
		 * The last server tick handed to the interpolator's clock — ANIM-13(b)'s de-duplicator.
		 *
		 * Load-bearing, not bookkeeping. The mirror holds its newest tick as a LEVEL, so feeding
		 * it every frame would push the same tick in against an ever-later {@code nowNanos},
		 * and each sample would read as the server falling further behind — the EMA would drag
		 * the offset down continuously. That is a worse drift than the free-run this change
		 * removes, and it is invisible unless you look for it: nothing throws, the picture just
		 * slides. Feeding strictly on CHANGE is what makes the fix a fix.
		 */
		long fedTick;
		boolean fedTickKnown;
		final Map<Integer, TexEntry> textures = new HashMap<Integer, TexEntry>();
	}

	private final Map<String, SceneGl> scenes = new HashMap<String, SceneGl>();
	/**
	 * ANIM-16's client-global animator budget. One instance for the whole client, which is what
	 * "client-global" means: the scenes compete with each other for one frame's worth of animator
	 * time, so a per-scene budget would bound nothing.
	 */
	private final AnimatorBudget animatorBudget = new AnimatorBudget();
	/**
	 * The scenes this frame will actually walk: {@code usedScenes} minus those with no mirror.
	 *
	 * ONE POPULATION, FED TO BOTH THE BUDGET AND THE LOOP. They used to differ — {@code prune}
	 * was given the {@code SceneGl} keys while {@code beginFrame} was given raw {@code
	 * usedScenes} — and the gap between them was a permanent-degradation bug. A scene marked used
	 * with no mirror is skipped before it can acquire a {@code SceneGl}, so {@code prune} deleted
	 * its budget every frame, {@code beginFrame} recreated it at the never-measured fallback, and
	 * being forever unseen it was force-admitted and charged ~30 us in pass 1 on EVERY frame.
	 * Five of those exceed the exit threshold on their own: degradation that can never end, on
	 * behalf of scenes that never render.
	 *
	 * Charging them zero would not have helped — {@code prune} destroys the entry before the next
	 * frame could read it. Only agreeing on the population fixes it, which is the same move
	 * {@code wouldEvaluate} makes for the render guard: remove the possibility of disagreement
	 * rather than test for its absence.
	 *
	 * A field, not a local, so the per-frame roll allocates nothing.
	 */
	private final List<String> walk = new ArrayList<String>();
	private final FramebufferPass pass = new FramebufferPass();
	/**
	 * Whether {@link #pass} is open this pre-pass. Not a duplicate of the pass's own {@code
	 * active} flag — that one is private and exists to reject misuse; this one is the caller's
	 * record of having opened it, so the lazy open is idempotent and the close is unconditional.
	 */
	private boolean passOpen;
	private final Canvas2dRenderer canvasRenderer = new Canvas2dRenderer();
	/**
	 * One-shot for "this machine has no FBO support at all", which is a session-wide fact.
	 * Per-scene allocation failures are NOT logged through this — they used to be, so the
	 * second failure anywhere in a session was silent forever.
	 */
	private boolean fboUnsupportedLogged;
	private boolean fboUnsupportedTold;

	/**
	 * Which half of {@code framebufferSupported && gameSettings.fboEnable} actually failed.
	 *
	 * Worth distinguishing every time it is reported: one is a video option two clicks away,
	 * the other is a machine that cannot run this mod. Telling a player "unsupported" when
	 * they merely switched FBOs off is the misdiagnosis this whole path exists to avoid.
	 */
	public static String fboDiagnosis() {
		if (!OpenGlHelper.framebufferSupported) {
			return "This GPU/driver reports no framebuffer-object support.";
		}
		return "Framebuffer objects are switched OFF in Video Settings.";
	}

	/** The player will never read a log line; say it once where they are actually looking. */
	private void notifyPlayerOnce() {
		if (fboUnsupportedTold) {
			return;
		}
		Minecraft mc = Minecraft.getMinecraft();
		if (mc == null || mc.thePlayer == null) {
			return; // not in a world yet — try again next frame, still only once
		}
		fboUnsupportedTold = true;
		mc.thePlayer.addChatMessage(new ChatComponentText(
				EnumChatFormatting.RED + "[OpenGPU] " + EnumChatFormatting.RESET
						+ fboDiagnosis() + " Screens will stay blank until "
						+ (OpenGlHelper.framebufferSupported
								? "you re-enable them (Options → Video Settings)." : "run on hardware that supports them.")));
	}

	/** The scene's rendered texture for surfaces to draw, or -1 if not yet rendered. */
	public int colorTextureFor(String sceneId) {
		SceneGl gl = scenes.get(sceneId);
		return gl != null && gl.everRendered ? gl.colorTex : -1;
	}

	/** Logical size of the rendered scene texture, or null. */
	public int[] sizeFor(String sceneId) {
		SceneGl gl = scenes.get(sceneId);
		return gl != null && gl.everRendered ? new int[] { gl.width, gl.height } : null;
	}

	/**
	 * The pre-pass: prune dead GL state, upload pending texture bytes under the frame
	 * budget, then render every used-and-dirty (or never-rendered) scene.
	 */
	/**
	 * The frame's pre-pass: everything OpenGPU draws happens under here.
	 *
	 * SPLIT IN TWO AT THE FBO GATE, 2026-08-22, and the reason is that the split is the whole
	 * difference between this file being testable and not. The gate below is a CALL-time check
	 * ({@code OpenGlHelper.isFramebufferEnabled()}), not a classloading one: this class has no
	 * static initialiser, and the suite already constructs Minecraft-typed objects headlessly
	 * ({@code InputRouterTest} allocates an {@code EntityPlayerMP} to route against). So the only
	 * thing that stopped a JVM test driving the per-frame orchestration was this one early return,
	 * which fires headlessly and skips the entire body.
	 *
	 * The commit that landed ANIM-16's budget said the wiring "needs GL and Forge to classload"
	 * and therefore could not be covered. That was wrong, and it cost an unnoticed bug: the
	 * budget was fed a different population than the loop walked. {@link #prePassSupported} is
	 * package-private so a test can drive it directly with no framebuffer.
	 */
	public void prePass(MirrorClient mirrors, Set<String> usedScenes) {
		if (!FramebufferPass.isSupported()) {
			if (!fboUnsupportedLogged) {
				fboUnsupportedLogged = true;
				// No fallback renderer is coming, and the old message promising one sent
				// anyone who read it looking for a bug instead of a setting. OpenGPU requires
				// framebuffer objects, full stop -- but note WHICH half of the gate failed:
				// OpenGlHelper.isFramebufferEnabled() is `framebufferSupported &&
				// gameSettings.fboEnable`, so the overwhelmingly likely cause is the video
				// option, which the player can simply turn back on.
				OpenGPU.logger.warn(fboDiagnosis()
						+ " OpenGPU screens require framebuffer objects and will stay blank.");
			}
			notifyPlayerOnce();
			return;
		}
		prePassSupported(mirrors, usedScenes);
	}

	/**
	 * The pre-pass proper, with framebuffer support already established.
	 *
	 * Package-private and free of any GL call on the paths a scene with no canvas node takes, so
	 * a headless test can drive the per-frame orchestration: the budget's roll, the population it
	 * is given, per-scene state creation and pruning.
	 */
	/**
	 * The scenes a frame will actually walk: those marked used that still have a mirror.
	 *
	 * STATIC AND PACKAGE-PRIVATE SO IT CAN BE TESTED, because this is where a real bug lived and
	 * the enclosing class cannot be instantiated in a JVM test — {@code new SceneRenderer()}
	 * constructs a {@link FramebufferPass}, whose buffers come from LWJGL's {@code BufferUtils},
	 * which is not on the test runtime classpath. (The class itself loads fine; it has no static
	 * initialiser. It is the CONSTRUCTOR that cannot run, which is a narrower obstacle than the
	 * "needs GL and Forge to classload" this file used to claim, and a different one.)
	 *
	 * The bug: the budget was rolled over raw {@code usedScenes} while the loop walked only the
	 * mirrored subset, so a scene marked used with no mirror was charged to the budget forever
	 * without ever being able to discharge. Both now read this one answer.
	 */
	static void resolveWalk(MirrorClient mirrors, Set<String> usedScenes, List<String> out) {
		out.clear();
		for (String sceneId : usedScenes) {
			if (mirrors.hasMirror(sceneId)) {
				out.add(sceneId);
			}
		}
	}

	void prePassSupported(MirrorClient mirrors, Set<String> usedScenes) {
		pruneDeadScenes(mirrors);
		// THE ANIMATOR BUDGET ROLLS HERE, and this is the only point in the client that is both
		// once-per-frame and aware of every scene -- prePass has exactly one caller, behind
		// Phase.START. Rolling AFTER pruneDeadScenes so the decision never plans for a scene that
		// no longer exists, and BEFORE the loop because the whole design rests on the answer being
		// fixed before anything asks: AnimatorOverlay consults the policy twice per scene (the
		// render guard's walk and the evaluation loop) and a disagreement between the two freezes
		// the scene outright.
		// THE POPULATION, resolved once, before either consumer sees it. See the `walk` field.
		resolveWalk(mirrors, usedScenes, walk);
		animatorBudget.prune(scenes.keySet());
		animatorBudget.beginFrame(walk);
		long budget = UPLOAD_BUDGET_PER_FRAME;
		long rendersBefore = RenderStats.sceneRenders;
		// ONE FramebufferPass around every scene, opened lazily by renderIfNeeded and closed
		// here. The save/restore is per-frame work that does not depend on the target, so paying
		// it once per visible scene was pure duplication.
		//
		// LAZY, and that is not an optimisation detail — it is what preserves the documented
		// guarantee that a settled scene costs nothing. renderIfNeeded short-circuits before it
		// asks for the pass, so a frame in which nothing re-renders still touches no GL state at
		// all. Opening eagerly here would charge every idle frame ~21 state reads to keep a
		// framebuffer bound that nothing draws into.
		try {
			// `walk` is already mirror-filtered, so the guard that used to stand here is gone
			// rather than duplicated — leaving it would let the two populations drift apart again
			// the next time one of them changed.
			for (String sceneId : walk) {
				SceneMirror mirror = mirrors.mirror(sceneId);
				SceneGl gl = scenes.get(sceneId);
				if (gl == null) {
					gl = new SceneGl();
					scenes.put(sceneId, gl);
					// INSTALLED ONCE, at creation, not per frame. The policy object carries the
					// scene id -- HoldPolicy.hold takes a bare node id, and node ids restart at 1
					// in every scene, so a policy shared across scenes would hold node 1
					// everywhere at once. Allocating one per frame here would also contradict
					// AnimatorOverlay's zero-allocation-frame claim.
					gl.overlay.holdPolicy(animatorBudget.policyFor(sceneId));
				}
				budget = uploadTextures(gl, mirror, budget);
				renderIfNeeded(gl, mirror);
			}
		} finally {
			// Must close on any path. A pass left open would leave the scene FBO bound and the
			// world's GL state canonicalised to OpenGPU's 2D regime for the rest of the frame.
			closePass();
		}
		// "Did this frame have to draw anything" is the interpolation overhead stated directly,
		// and it is measured HERE rather than inside renderIfNeeded because several scenes may
		// be visible and the question is about the frame, not about any one of them.
		RenderStats.onPrePass(RenderStats.sceneRenders > rendersBefore);
	}

	private void pruneDeadScenes(MirrorClient mirrors) {
		Iterator<Map.Entry<String, SceneGl>> iter = scenes.entrySet().iterator();
		while (iter.hasNext()) {
			Map.Entry<String, SceneGl> entry = iter.next();
			if (!mirrors.hasMirror(entry.getKey())) {
				dispose(entry.getValue());
				iter.remove();
			}
		}
	}

	/** Upload delivered texture bytes; free GL textures whose resources are gone. */
	private long uploadTextures(SceneGl gl, SceneMirror mirror, long budget) {
		Map<Integer, ResourceInfo> resources = mirror.state().resources;
		// Part of the upload key: an epoch change reuses resource ids for different content,
		// so a matching version alone would wrongly suppress the re-upload.
		final int mirrorEpoch = mirror.knownEpoch();
		// Prune first: freed resources release their GL objects immediately.
		Iterator<Map.Entry<Integer, TexEntry>> iter = gl.textures.entrySet().iterator();
		while (iter.hasNext()) {
			Map.Entry<Integer, TexEntry> entry = iter.next();
			ResourceInfo res = resources.get(entry.getKey());
			if (res == null || res.type != V2Wire.RES_TEXTURE) {
				GL11.glDeleteTextures(entry.getValue().glId);
				iter.remove();
				gl.uploadDirty = true;
			}
		}
		for (ResourceInfo res : resources.values()) {
			if (res.type != V2Wire.RES_TEXTURE || res.bytes == null) {
				continue; // pending: renders as the defined transparent placeholder
			}
			TexEntry entry = gl.textures.get(res.id);
			if (entry != null && entry.uploadedEpoch == mirrorEpoch
					&& entry.uploadedVersion == res.version) {
				continue;
			}
			// A sub-upload is legal whenever the GL texture holds SOME EARLIER version of
			// this resource at matching dimensions and epoch.
			//
			// The bound is "earlier", not "exactly one behind": the dirty rect accumulates
			// every write since the last upload (clearDirty runs only when we upload, and
			// unionDirtyRect on every applied blit), so it describes the delta from
			// uploadedVersion however many versions have passed. Requiring version - 1 would
			// disable the whole optimisation for any tick containing more than one write —
			// i.e. exactly the streaming workload it exists for — and for any texture that
			// was skipped for budget. A body install and a snapshot carry-over both call
			// markFullDirty, so those arrive as a full-rect sub-upload rather than a stale
			// partial one.
			boolean canSubUpload = entry != null
					&& entry.uploadedEpoch == mirrorEpoch
					&& entry.uploadedVersion > 0
					&& entry.uploadedVersion < res.version
					&& entry.glWidth == res.width && entry.glHeight == res.height
					&& res.dirtyW > 0
					&& res.dirtyX >= 0 && res.dirtyY >= 0
					&& res.dirtyX + res.dirtyW <= res.width
					&& res.dirtyY + res.dirtyH <= res.height;
			long size = canSubUpload
					? (long) res.dirtyW * res.dirtyH * 4L : res.bytes.length;
			// Always admit the head of the queue: a texture bigger than one frame's budget
			// would otherwise be skipped forever (the budget resets to the same value every
			// frame), leaving a legally-created texture permanently invisible. Admitting it
			// against an untouched budget costs one hitchy frame instead.
			if (size > budget && budget < UPLOAD_BUDGET_PER_FRAME) {
				// Counted because a budget that is regularly exhausted means textures are
				// arriving faster than they can be uploaded, which shows up to a player as a
				// picture that lags behind the program rather than as anything measurable.
				RenderStats.onTextureDeferred();
				continue; // over budget this frame; a later pre-pass picks it up
			}
			budget -= size;
			RenderStats.onUpload((int) Math.min(size, Integer.MAX_VALUE));
			if (canSubUpload) {
				uploadSubRgba(entry.glId, res.width, res.bytes,
						res.dirtyX, res.dirtyY, res.dirtyW, res.dirtyH);
				entry.uploadedVersion = res.version;
				res.clearDirty();
				gl.uploadDirty = true;
				continue;
			}
			if (entry == null) {
				entry = new TexEntry();
				entry.glId = GL11.glGenTextures();
				gl.textures.put(res.id, entry);
			}
			uploadRgba(entry.glId, res.width, res.height, res.bytes);
			entry.uploadedEpoch = mirrorEpoch;
			entry.uploadedVersion = res.version;
			entry.glWidth = res.width;
			entry.glHeight = res.height;
			res.clearDirty();
			gl.uploadDirty = true;
		}
		return budget;
	}

	/** Full (re)allocation of the GL texture: first upload, or after a resize. */
	private static void uploadRgba(int glId, int width, int height, byte[] rgba) {
		ByteBuffer buffer = BufferUtils.createByteBuffer(rgba.length);
		buffer.put(rgba);
		buffer.flip();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
				GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
	}

	/**
	 * Upload only the rectangle that changed.
	 *
	 * A streaming program rewrites a small region per tick; re-uploading the whole image
	 * makes the cost proportional to the texture size rather than the edit size — a 4-byte
	 * write to a 1 MB texture moving a megabyte per frame. The rows are contiguous in the
	 * source only when the rect spans the full width, so a partial-width rect is packed row
	 * by row into a staging buffer.
	 */
	private static void uploadSubRgba(int glId, int texWidth, byte[] rgba,
			int x, int y, int w, int h) {
		ByteBuffer buffer = BufferUtils.createByteBuffer(w * h * 4);
		if (w == texWidth) {
			// Full-width rect: the rows are already contiguous, so one bulk copy suffices.
			buffer.put(rgba, y * texWidth * 4, w * h * 4);
		} else {
			for (int row = 0; row < h; row++) {
				buffer.put(rgba, ((y + row) * texWidth + x) * 4, w * 4);
			}
		}
		buffer.flip();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, glId);
		GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, w, h,
				GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
	}

	private void renderIfNeeded(SceneGl gl, SceneMirror mirror) {
		// CHARGED ZERO UP FRONT, then charged again for real if the animator pass happens.
		//
		// Every return below this line leaves the scene having done NO animator work, and the
		// budget has to be told so. Without this, a scene that always returns early — no canvas
		// node yet, or a latched FBO failure, both of which are indefinite states — is never
		// charged at all, so its estimate stays at the never-measured fallback of 30 us FOREVER
		// while it still occupies admission budget. Five such phantoms sum to 150 us of demand
		// that nothing pays and nothing can reduce, which is above EXIT_NANOS: the client latches
		// into permanent degradation on behalf of scenes with no animators at all.
		//
		// Safe on the paths that DO evaluate: charge() accumulates within the frame, so the real
		// measurement adds to this zero rather than replacing it, and a zero full-rate reading is
		// only recorded for a scene that genuinely did nothing.
		animatorBudget.charge(mirror.sceneId, 0L);
		int[] size = sceneLogicalSize(mirror);
		if (size == null) {
			return; // no canvas node yet — nothing to render
		}
		boolean resized = gl.fbo != -1 && (gl.width != size[0] || gl.height != size[1]);
		if (gl.fbo == -1 || resized) {
			if (size[0] == gl.failedWidth && size[1] == gl.failedHeight) {
				return; // already tried this size; keep showing whatever is on screen
			}
			// Create BEFORE deleting. The old FBO is a WORKING display: tearing it down first
			// meant a failed resize turned a visible screen black, and then re-attempted the
			// same doomed allocation every single frame. sizeFor() reports the FBO's real
			// dimensions, so keeping the old one also keeps the surface letterbox coherent
			// with what is actually in the texture.
			int[] created = FramebufferPass.createSceneFbo(size[0], size[1]);
			if (created == null) {
				gl.failedWidth = size[0];
				gl.failedHeight = size[1];
				int max = FramebufferPass.maxSceneDimension();
				OpenGPU.logger.warn("Scene FBO creation failed (" + size[0] + "x" + size[1]
						+ "; this context allows up to " + max + "x" + max
						+ "); keeping the previous surface");
				return;
			}
			if (gl.fbo != -1) {
				FramebufferPass.deleteSceneFbo(gl.fbo, gl.colorTex);
			}
			gl.fbo = created[0];
			gl.colorTex = created[1];
			gl.width = size[0];
			gl.height = size[1];
			gl.everRendered = false;
			gl.failedWidth = -1;
			gl.failedHeight = -1;
		}
		long now = System.nanoTime();
		// Fold a freshly applied batch into the interpolator BEFORE deciding whether to draw:
		// capture() is what starts a node moving, so skipping it would drop the motion
		// entirely on the frame the batch lands.
		if (gl.knownEpoch != mirror.knownEpoch()) {
			// A new incarnation is a different timeline: tick stamps from the old one describe
			// nothing in the new one, so the clock estimate and every keyframe must be dropped
			// rather than interpolated across. DESIGN: "Resync always snaps."
			gl.knownEpoch = mirror.knownEpoch();
			gl.interp.reset();
			// The de-duplicator belongs to the timeline it guards: a tick from the old
			// incarnation must not suppress the first feed of the new one, which is how the
			// re-primed estimate would keep a stale offset.
			gl.fedTickKnown = false;
			// The overlay's VM cache leans on "program ids are never reused", which holds only
			// within one incarnation -- a new epoch restarts the id space, so the new epoch's
			// program 1 can be different code under a cached id. See AnimatorOverlay.reset.
			gl.overlay.reset();
		}
		if (mirror.isDirty()) {
			gl.interp.capture(mirror.state(), mirror.lastServerTick(), now, mirror.teleportedNodes());
			// capture() fed the clock with this tick; record it so the arm below does not feed
			// the same one again from the mirror's level.
			gl.fedTick = mirror.lastServerTick();
			gl.fedTickKnown = true;
		} else if (mirror.hasObservedTick()
				&& (!gl.fedTickKnown || mirror.lastObservedTick() != gl.fedTick)) {
			// ANIM-13(b): a tick that arrived on a heartbeat rather than a batch. Only reachable
			// on a scene with no state changes, which is exactly the scene class animators exist
			// for and the one whose clock previously had no correction channel at all.
			gl.interp.observeTick(mirror.lastObservedTick(), now);
			gl.fedTick = mirror.lastObservedTick();
			gl.fedTickKnown = true;
		}
		// Re-render while anything is still mid-flight, not only when a batch arrived — that
		// is the whole point, since batches land at 20 Hz and we draw at 60+. A settled scene
		// still short-circuits here, so a static display costs nothing per frame.
		//
		// ANIM-19's TERM, added 2026-08-20 (Phase 3.4) where its obligation had been written. An
		// attached animator is WORK: it recomputes node transforms every frame from `time` while
		// producing no batch, no upload and no interpolation. Without this term all four other
		// conjuncts are satisfied on a settled scene, the method returns early, and an animated
		// node FREEZES the moment the scene stops changing — while a scene with any other activity
		// keeps it moving, which is what would make the bug look intermittent rather than total.
		//
		// FIFTH conjunct, not the third. The plan and DESIGN both say "the third term", inherited
		// from a paragraph written before `uploadDirty` and `everRendered` existed; the four here
		// today are a fresh batch, a pending texture upload, the never-drawn bootstrap, and
		// interpolation.
		//
		// LAST in the condition, and that placement is load-bearing rather than stylistic: `&&`
		// short-circuits, so the node walk runs ONLY when the other four have already agreed the
		// scene is settled. An active scene never pays for it at all.
		//
		// NOT done by making the mirror permanently dirty, which the obligation named as the wrong
		// fix: that re-renders every scene every frame, animated or not, and the short-circuit
		// above is exactly what keeps a static display free.
		//
		// THE IN-GAME DISCRIMINATOR, worth stating because it survives this code being wrong:
		// frozen-when-settled indicts THIS LINE; moving-but-wrong indicts the evaluator. That is
		// why 3.4 lands before 3.3 — afterwards, a frozen frame has only one candidate cause.
		boolean interpolating = gl.interp.active(now);
		// INLINE, not hoisted to a local: a local would be evaluated eagerly every frame and the
		// short-circuit described above would be decorative. (`interpolating` is a local because
		// it is read again below; this one is not.)
		// WILL ANY PROGRAM RUN, not "is any program attached" (2026-08-21, ANIM-16). Under the
		// budget a scene can have animators and still owe this frame no new animator work, and
		// then the other four conjuncts already say the picture is identical to the one on
		// screen: nothing is dirty, nothing is uploading, it has been drawn, and nothing is
		// interpolating, so the base a held node recomposes over has not moved either. Skipping
		// is correct, and it is where most of the saving is -- declining the VM while still
		// re-rendering the scene every frame would forfeit the GL cost, which is the larger half.
		//
		// NO LONGER IDENTICAL, as of the budget landing on 2026-08-22. This used to say the term
		// answered exactly what `hasAttachedAnimator()` answered, which was true only while
		// EVALUATE_ALWAYS was the sole policy. A scene the budget declines to admit now holds
		// every node, so `wouldEvaluate` answers FALSE for a scene that plainly has animators
		// attached -- which is the entire point, because that is what lets this conjunct skip the
		// scene's fixed cost and its GL re-render together.
		//
		// STILL INLINE, for the reason stated two comments up: hoisting it to a local would
		// evaluate the walk eagerly on every frame and make the short-circuit decorative. It also
		// reads `byNode` as the PREVIOUS pass left it, which is the state it must judge -- whether
		// a node can be held depends on whether it has an output to hold, and this pass has not
		// produced one yet.
		if (!mirror.isDirty() && !gl.uploadDirty && gl.everRendered && !interpolating) {
			if (!gl.overlay.wouldEvaluate(mirror.state())) {
				return;
			}
			// THE SETTLED-FRAME MEASUREMENT (ANIM-16), and it is FREE here: reaching this line
			// means the four cheap conjuncts held AND wouldEvaluate answered true, so there is
			// animator work on an otherwise-settled scene -- no extra walk, no extra branch on
			// any other path.
			//
			// WHY IT IS WORTH COUNTING. Holding a node declines that node's own evaluation on
			// EVERY frame -- that part needs no help from this counter. What needs the short
			// circuit above is the fixed per-evaluation cost and the scene's GL re-render, and
			// that needs every node held AND these four conjuncts. Nobody knows what fraction of
			// a real animated scene's frames satisfy them; one taking 20 Hz server batches may be
			// dirty or interpolating for most of its life. Against RenderStats'
			// animatorEvaluations this gives that fraction directly.
			//
			// A LOW READING IS NOT A VERDICT ON THE BUDGET, and an earlier version of this
			// comment implied it was ("the budget's headline lever does not exist"). The
			// per-node term is ~74% of a real scene's animator cost (7 nodes, 77 ops, ~34 of
			// ~46 us) and is reclaimed whatever this ratio says. What a low reading means is
			// narrower: the GL re-render stays. The design is only in question when this is near
			// zero AND the scenes run cheap programs, because only then is there nothing worth
			// declining at all.
			//
			// UNDERCOUNTS ONCE DEGRADATION IS ACTIVE, deliberately and in the safe direction: a
			// settled scene with every node held returns at the line above and is counted in
			// neither this nor animatorEvaluations. Those frames are the budget's wins, so the
			// ratio reads as a LOWER bound on how often the lever was available.
			RenderStats.animatorScenePassesSettled++;
		}
		// ANIMATOR EVALUATION -- once per frame per scene, before any GL work, and against the
		// SAME interpolator and `now` the render below draws with (ANIM-4: one time sample per
		// frame per scene; the overlay composes over the exact base the frame displays). On a
		// scene with no attached animator this walks a loop of continues and then sweeps last
		// frame's entries away -- cheap, and it is what makes detach snap.
		//
		// It used to say "without a special case", because the pass began by clearing the map
		// outright. The map is persistent as of the hold seam (a held node needs an output to
		// still be there), so dropping stale entries IS the special case now, and it is
		// AnimatorOverlay.sweepUntouched.
		//
		// Timed only when an animator is attached, so animatorNanos means "what animators cost"
		// rather than "what this call costs on scenes that have none" -- ANIM-16 wants the first
		// number.
		// TIMED WHEN A PROGRAM ACTUALLY RAN, decided AFTERWARDS from the counter rather than
		// predicted beforehand. A first cut asked `wouldEvaluate` again here, which was wrong
		// twice over: that predicate is conservative (it answers true for dangling, undecodable
		// and wrong-stage attachments, none of which run a VM), so `animatorNanos` would grow
		// against a frozen `animatorNodesEvaluated` and quietly corrupt the per-node ratio
		// FIELD-TEST-ANIM16 exists to measure -- and it was a SECOND full node walk on every
		// frame that reached this line, including the active scenes the guard's inline
		// short-circuit is written to keep free. Reading the counter costs nothing and is exact.
		long nodesBefore = RenderStats.animatorNodesEvaluated;
		long animStart = System.nanoTime();
		gl.overlay.evaluate(mirror.state(), gl.interp.renderInstant(now),
				mirror.sessionTickOffset(), mirror.animatorClockKnown(), gl.interp, now);
		long animNanos = System.nanoTime() - animStart;
		// THE BUDGET IS CHARGED UNCONDITIONALLY, outside the gate below, and the difference is
		// the whole reason it keeps its own accumulator instead of reading RenderStats. An
		// all-held pass runs no VM, so the gate is false and `animatorNanos` does not move -- yet
		// that pass genuinely paid the fixed per-evaluation cost and every recomposition. A
		// budget reading the gated counter would watch spend collapse to zero the instant it
		// began holding, release, spike, and re-enter: blind exactly when it is engaged.
		animatorBudget.charge(mirror.sceneId, animNanos);
		if (RenderStats.animatorNodesEvaluated != nodesBefore) {
			// A MIXED pass still charges its held nodes' recomposition into this window with no
			// denominator of its own -- a small upward bias on the per-node figure, and
			// RenderStats.animatorNodesHeld is what makes it quantifiable rather than invisible.
			// The SAME reading the budget was charged, deliberately: a second nanoTime here would
			// fold the charge call into the per-node instrument FIELD-TEST-ANIM16 calibrates
			// against, changing what a measured constant means for no reason.
			RenderStats.onAnimatorEvaluate(animNanos);
		}
		Map<Integer, Integer> glMap = new HashMap<Integer, Integer>();
		for (Map.Entry<Integer, TexEntry> entry : gl.textures.entrySet()) {
			glMap.put(entry.getKey(), entry.getValue().glId);
		}
		// The render is attributed to interpolation when nothing else asked for it. That is the
		// number the whole measurement exists for: a re-render caused by fresh server state is
		// work that always had to happen, while one caused purely by a node still gliding is
		// the cost interpolation added — and at 60-200 Hz against a former ceiling of 20.
		boolean interpolationDriven = interpolating && !mirror.isDirty() && !gl.uploadDirty
				&& gl.everRendered;
		int commandCount = countCommands(mirror);
		// The pass opens here, on the first scene of the frame that actually renders, and stays
		// open until prePass closes it.
		openPass();
		// Timed from retarget, NOT from the pass save/restore, which now happens once per frame
		// outside any one scene. So renderNanos is per-SCENE work and no longer silently charges
		// the first scene of the frame for the whole frame's state capture. That makes the
		// figure smaller than it used to be by roughly the save/restore cost — numbers taken
		// before this change and after it are not directly comparable, and PERF-BASELINE's
		// fixed term was measured the old way.
		long renderStart = System.nanoTime();
		pass.retarget(gl.fbo, gl.width, gl.height);
		canvasRenderer.renderScene(mirror.state(), gl.width, gl.height, glMap, gl.interp, now,
				gl.overlay);
		RenderStats.onSceneRender(System.nanoTime() - renderStart, commandCount, interpolationDriven);
		gl.everRendered = true;
		gl.uploadDirty = false;
		mirror.clearDirty();
	}

	/**
	 * Open the shared pass if this frame has not already. Idempotent.
	 *
	 * {@code passOpen} is set BEFORE begin(), which looks wrong and is not. FramebufferPass.begin
	 * sets its own {@code active} flag on its first statement, before ~21 GL calls; if any of them
	 * throws, the pass is active while this class believes it never opened one. prePass's finally
	 * would then skip end(), {@code active} would stay true with no path left to clear it, and
	 * EVERY subsequent frame would throw "FramebufferPass is not reentrant" out of the Forge
	 * render tick — for the rest of the session. Claiming ownership first makes closePass()
	 * reach end() on exactly the path where it matters, and end() is safe to call there precisely
	 * because begin() sets active first. Mirrors the same defensive ordering in closePass().
	 */
	private void openPass() {
		if (!passOpen) {
			passOpen = true;
			long start = System.nanoTime();
			pass.begin();
			RenderStats.passOpens++;
			RenderStats.passNanos += System.nanoTime() - start;
		}
	}

	/**
	 * Close the shared pass if it was opened. Idempotent, and called from a finally, so a scene
	 * that throws mid-replay still hands the world its GL state back.
	 */
	private void closePass() {
		if (passOpen) {
			passOpen = false;
			long start = System.nanoTime();
			pass.end();
			RenderStats.passNanos += System.nanoTime() - start;
		}
	}

	/**
	 * Commands about to be replayed — walked over NODES, mirroring what the renderer draws.
	 *
	 * Counted rather than timed per command: the unit of work is a glBegin/glEnd pair, so a
	 * nanoTime around each would cost more than the thing it measures. Dividing the whole
	 * render by this count gives the per-command figure without perturbing it.
	 *
	 * The first version summed every RES_CANVAS in the scene, which is NOT what gets replayed:
	 * Canvas2dRenderer draws a canvas only when a VISIBLE canvas node references it, and
	 * offscreen canvases outlive the nodes that showed them (clearNodes frees nodes, not
	 * canvases). In practice that meant every canvas a previous program had allocated kept
	 * contributing to the denominator. It showed up immediately in the first real measurement:
	 * render-time divided by ns/command came out at a constant ~2400 commands across every
	 * sample, for a scene whose one visible canvas held five — the rest was accumulated test
	 * history. A denominator that does not move with the numerator makes the ratio meaningless,
	 * which would have silently invalidated the command-count scaling experiment.
	 */
	private static int countCommands(SceneMirror mirror) {
		SceneState state = mirror.state();
		int total = 0;
		for (SceneNode node : state.nodes.values()) {
			// isDrawn, not node.visible: a canvas node under a HIDDEN GROUP draws nothing, and
			// counting its commands would understate ns/command by the whole ratio. This is the
			// same failure the javadoc above describes, reintroduced by transform parenting —
			// the numerator stopped including these commands and the denominator had not noticed.
			if (!Canvas2dRenderer.isDrawn(node, state) || node.type != V2Wire.NODE_CANVAS) {
				continue;
			}
			ResourceInfo res = state.resources.get(node.ref);
			if (res != null && res.type == V2Wire.RES_CANVAS && res.canvas != null) {
				total += res.canvas.visibleCommands().size();
			}
		}
		return total;
	}

	/**
	 * Scene logical size, from the one place that defines which canvas is the display.
	 * The rule and its fragility live on {@link opengpu.v2.scene.SceneState#displayCanvas()}
	 * rather than here, so the server side and this pre-pass cannot drift on it.
	 */
	private static int[] sceneLogicalSize(SceneMirror mirror) {
		ResourceInfo res = mirror.state().displayCanvas();
		return res == null ? null : new int[] { res.width, res.height };
	}

	/** Full GL teardown (world unload / disconnect), render thread. */
	public void disposeAll() {
		for (SceneGl gl : scenes.values()) {
			dispose(gl);
		}
		scenes.clear();
	}

	private static void dispose(SceneGl gl) {
		if (gl.fbo != -1) {
			FramebufferPass.deleteSceneFbo(gl.fbo, gl.colorTex);
		}
		for (TexEntry entry : gl.textures.values()) {
			GL11.glDeleteTextures(entry.glId);
		}
		gl.textures.clear();
	}
}
