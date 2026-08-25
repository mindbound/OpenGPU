package opengpu.v2.mc.client.render;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import net.minecraft.client.renderer.OpenGlHelper;

/**
 * THROWAWAY — Stage C increment C1.0, the disposable GL spike. Delete after
 * FIELD-TEST-C10.md is graded. Nothing may come to depend on this class.
 *
 * <h2>What it answers, and why before anything is frozen</h2>
 *
 * Stage C's 3D layer stands on a depth renderbuffer attached to a scene FBO under
 * Angelica/GLSM. PLAN-STAGE-C's decision 1 is fail-fast on exactly that — so this runs BEFORE
 * C1.1 freezes the mesh vertex layout or pays the protocol bump. Three questions, one
 * standing obligation:
 *
 * <ol>
 * <li><b>Does a {@code GL_DEPTH_COMPONENT24} renderbuffer attach and complete</b> on this
 *     stack, at the default scene size and at the 2048 MAX_CANVAS_DIM shape? Completeness can
 *     hold while depth testing is broken, so a functional check follows: two overlapping quads
 *     drawn NEAR-first — only a working depth test keeps the near one on top.</li>
 * <li><b>What is {@code GL_MAX_RENDERBUFFER_SIZE}?</b> {@code FramebufferPass} deliberately
 *     consults only {@code GL_MAX_TEXTURE_SIZE}, "because the scene FBO's only attachment is a
 *     texture" — a premise that expires the moment a renderbuffer attaches. This reads the
 *     limit that will bound Stage C.</li>
 * <li><b>Which FBO cascade is live</b> — GL30 core / ARB / EXT? {@code OpenGlHelper} decides
 *     this at init and keeps the answer private; the spike re-derives it from
 *     {@link ContextCapabilities} with the same cascade, because the split-binding query below
 *     is a GL ERROR on the EXT-only path and must be gated.</li>
 * </ol>
 *
 * The standing obligation: {@code FramebufferPass}'s savedFbo javadoc (investigated 2026-08-12,
 * left as a note) asks for exactly one observation before its asymmetry becomes a patch —
 * <i>"Log GL_READ_FRAMEBUFFER_BINDING and GL_DRAW_FRAMEBUFFER_BINDING at the top of
 * SceneRenderer's pre-pass for one session with shaders on; if they ever differ, this becomes
 * a patch."</i> {@link #watchSplitBinding()} is that log line, latched so a divergence prints
 * once rather than per frame.
 *
 * <h2>Costs, stated</h2>
 *
 * While this spike is installed, the pre-pass's "a settled frame touches no GL state at all"
 * guarantee is suspended: the split watch reads two (GLSM-tracked, cached) integers per
 * pre-pass on the core/ARB path. The one-shot itself allocates and frees a 512x288 and a
 * 2048x2048 depth renderbuffer (~16 MB transient) once. It also performs this mod's only
 * {@code glReadPixels} — ANGELICA-NOTES records "we never READ from a framebuffer" as a
 * blast-radius bound; the read here is 1 pixel from the spike's own FBO, inside the pre-pass
 * window, and dies with the class.
 *
 * All entry points are GL11/OpenGlHelper, per the house rule (Angelica's GLSM tracks these) —
 * with ONE owned exception: {@code glReadPixels} is not in GLSM's redirect table at the pinned
 * Angelica 2.1.59 (ANGELICA-NOTES' rejected-alternatives entry records this as a rule-1
 * violation), so the readback is a raw driver call that skips GLSM's pack-buffer suspension.
 * Accepted here only because the class dies with the field test; GLSM's glEnd draws
 * synchronously (directDrawer), so there is no deferred-draw race behind the read.
 * The depth-renderbuffer sequence is vanilla {@code Framebuffer.createFramebuffer}'s own,
 * verbatim — its Forge {@code getStencilBits()==0} branch: gen (func_153185_f), bind
 * (func_153176_h), storage 33190 = GL_DEPTH_COMPONENT24 (func_153186_a), attach
 * (func_153190_b). A pack that reserves stencil bits routes vanilla down the D24S8 branch
 * instead — FIELD-TEST-C10's G2 triage note carries that caveat.
 * Accepted restore exception: the renderbuffer binding is left at 0 after teardown (deleting
 * the bound RB reverts it per spec; nothing in 1.7.10 relies on ambient RB binding).
 */
final class DepthSpike {

	/** Same raw enum FramebufferPass uses; = GL_DRAW_FRAMEBUFFER_BINDING on split-capable GL. */
	private static final int GL_FRAMEBUFFER_BINDING = 36006;
	private static final int GL_ACTIVE_TEXTURE = 34016;
	private static final int TEX_UNIT0 = OpenGlHelper.defaultTexUnit;   // 33984
	private static final int TEX_UNIT1 = OpenGlHelper.lightmapTexUnit;  // 33985

	private static boolean ranOnce;
	private static boolean splitCapKnown;
	private static boolean splitQueryable;
	private static boolean splitDivergenceLatched;

	private DepthSpike() {}

	/** Call at the top of every pre-pass. Cheap after the first frame. */
	static void onPrePass() {
		if (!FramebufferPass.isSupported()) {
			return;
		}
		watchSplitBinding();
		if (!ranOnce) {
			ranOnce = true;
			try {
				runOnce();
			} catch (Throwable t) {
				// A spike must never take the render thread down: report and stand down.
				opengpu.OpenGPU.logger.error("[spike C1.0] ABORTED: " + t, t);
			}
		}
	}

	/**
	 * The FramebufferPass javadoc's requested observation. Gated: on the EXT-only path the
	 * read-binding enum does not exist and querying it is a GL error — the same reason that
	 * javadoc gives for not "fixing" the asymmetry unconditionally.
	 */
	private static void watchSplitBinding() {
		if (!splitCapKnown) {
			splitCapKnown = true;
			ContextCapabilities cc = GLContext.getCapabilities();
			splitQueryable = cc.OpenGL30 || cc.GL_ARB_framebuffer_object;
			// Print the arming decision plus both first-frame values: a quiet session must be
			// distinguishable from a watch that never ran, and the FramebufferPass javadoc's
			// literal ask is to LOG both bindings.
			if (splitQueryable) {
				log("split watch ARMED (core/ARB): draw="
						+ GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) + " read="
						+ GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
						+ " — logs again only on divergence");
			} else {
				log("split watch DISARMED (EXT-only path: the read-binding enum does not exist"
						+ " there) — grade G7 VOID this session");
			}
		}
		if (!splitQueryable || splitDivergenceLatched) {
			return;
		}
		int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		if (draw != read) {
			splitDivergenceLatched = true;
			opengpu.OpenGPU.logger.warn("[spike C1.0] SPLIT BINDING AT PRE-PASS: draw=" + draw
					+ " read=" + read + " — FramebufferPass's savedFbo note says this makes its"
					+ " restore asymmetry A PATCH, not a note. Record this line in"
					+ " FIELD-TEST-C10.md.");
		}
	}

	private static void log(String s) {
		opengpu.OpenGPU.logger.info("[spike C1.0] " + s);
	}

	private static String glErr(String stage) {
		int e = GL11.glGetError();
		return e == 0 ? (stage + ": no GL error") : (stage + ": GL ERROR 0x"
				+ Integer.toHexString(e).toUpperCase(java.util.Locale.ROOT));
	}

	private static void runOnce() {
		ContextCapabilities cc = GLContext.getCapabilities();
		// Drain any stale error FLAGS (plural — the spec allows several queued) so nothing
		// below is blamed for someone else's glGetError; bounded against broken drivers.
		for (int i = 0; i < 16 && GL11.glGetError() != 0; i++) { }

		log("vendor='" + GL11.glGetString(GL11.GL_VENDOR) + "' renderer='"
				+ GL11.glGetString(GL11.GL_RENDERER) + "' version='"
				+ GL11.glGetString(GL11.GL_VERSION) + "'");
		// The same cascade OpenGlHelper.initializeTextures runs, re-derived because its result
		// (field_153212_w) is private. Order matters and mirrors vanilla exactly.
		String cascade = cc.OpenGL30 ? "GL30-core" : cc.GL_ARB_framebuffer_object ? "ARB"
				: cc.GL_EXT_framebuffer_object ? "EXT" : "NONE";
		log("FBO cascade: " + cascade + "  (OpenGL30=" + cc.OpenGL30 + " ARB_fbo="
				+ cc.GL_ARB_framebuffer_object + " EXT_fbo=" + cc.GL_EXT_framebuffer_object + ")");
		log("GL_MAX_TEXTURE_SIZE=" + GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE)
				+ "  GL_MAX_RENDERBUFFER_SIZE=" + GL11.glGetInteger(GL30.GL_MAX_RENDERBUFFER_SIZE));
		log(glErr("setup checkpoint (strings+limits)"));

		// ---- save the state this spike touches, BY VALUE (FramebufferPass's discipline) ----
		int savedFbo = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
		java.nio.IntBuffer vp = BufferUtils.createIntBuffer(16);
		GL11.glGetInteger(GL11.GL_VIEWPORT, vp);
		boolean savedDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
		boolean savedDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
		int savedDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
		boolean savedBlend = GL11.glIsEnabled(GL11.GL_BLEND);
		boolean savedAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
		boolean savedScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
		boolean savedFog = GL11.glIsEnabled(GL11.GL_FOG);
		boolean savedLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
		boolean savedCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
		float savedClearDepth = GL11.glGetFloat(GL11.GL_DEPTH_CLEAR_VALUE);
		java.nio.FloatBuffer fb = BufferUtils.createFloatBuffer(16);
		GL11.glGetFloat(GL11.GL_COLOR_CLEAR_VALUE, fb);
		float savedClearR = fb.get(0), savedClearG = fb.get(1), savedClearB = fb.get(2),
				savedClearA = fb.get(3);
		fb.clear();
		GL11.glGetFloat(GL11.GL_CURRENT_COLOR, fb);
		float savedColorR = fb.get(0), savedColorG = fb.get(1), savedColorB = fb.get(2),
				savedColorA = fb.get(3);
		java.nio.ByteBuffer wm = BufferUtils.createByteBuffer(16);
		GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, wm);
		boolean savedMaskR = wm.get(0) != 0, savedMaskG = wm.get(1) != 0,
				savedMaskB = wm.get(2) != 0, savedMaskA = wm.get(3) != 0;
		// Texture units 0/1 by value, FramebufferPass's exact idiom — the unit-1 lightmap
		// multiplies fragments even with lighting off (ANGELICA-NOTES rule-5 correction).
		int savedActiveTexture = GL11.glGetInteger(GL_ACTIVE_TEXTURE);
		OpenGlHelper.setActiveTexture(TEX_UNIT1);
		boolean savedTex2dUnit1 = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		OpenGlHelper.setActiveTexture(TEX_UNIT0);
		boolean savedTex2dUnit0 = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
		int savedTexBinding0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		GL11.glMatrixMode(GL11.GL_PROJECTION);
		GL11.glPushMatrix();
		GL11.glMatrixMode(GL11.GL_MODELVIEW);
		GL11.glPushMatrix();

		int fbo = 0, colorTex = 0, depthRb = 0, bigRb = 0;
		try {
			final int W = 512, H = 288;   // the default scene resolution

			// ---- color: a texture attachment, the shape the real scene FBO already has ----
			colorTex = GL11.glGenTextures();
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, W, H, 0, GL11.GL_RGBA,
					GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

			fbo = OpenGlHelper.func_153165_e();                                  // glGenFramebuffers
			OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, fbo);        // bind FBO
			OpenGlHelper.func_153188_a(OpenGlHelper.field_153198_e,              // attach color
					OpenGlHelper.field_153200_g, GL11.GL_TEXTURE_2D, colorTex, 0);
			log(glErr("after color-texture attach"));

			// ---- depth: vanilla Framebuffer.createFramebuffer's sequence, verbatim ----
			depthRb = OpenGlHelper.func_153185_f();                              // glGenRenderbuffers
			OpenGlHelper.func_153176_h(OpenGlHelper.field_153199_f, depthRb);    // bind RB
			OpenGlHelper.func_153186_a(OpenGlHelper.field_153199_f,              // storage D24
					GL14.GL_DEPTH_COMPONENT24, W, H);
			OpenGlHelper.func_153190_b(OpenGlHelper.field_153198_e,              // attach depth
					OpenGlHelper.field_153201_h, OpenGlHelper.field_153199_f, depthRb);
			log(glErr("after 512x288 D24 attach"));

			int status = OpenGlHelper.func_153167_i(OpenGlHelper.field_153198_e);
			log("completeness at 512x288 color-tex + D24-renderbuffer: "
					+ (status == OpenGlHelper.field_153202_i ? "COMPLETE"
							: ("INCOMPLETE 0x" + Integer.toHexString(status).toUpperCase(
									java.util.Locale.ROOT))));

			if (status == OpenGlHelper.field_153202_i) {
				functionalDepthTest(W, H);
			} else {
				log("functional depth test SKIPPED — incomplete FBO");
			}

			// ---- the MAX_CANVAS_DIM-shaped probe: can a 2048x2048 D24 allocate + complete? ----
			bigRb = OpenGlHelper.func_153185_f();
			OpenGlHelper.func_153176_h(OpenGlHelper.field_153199_f, bigRb);
			OpenGlHelper.func_153186_a(OpenGlHelper.field_153199_f,
					GL14.GL_DEPTH_COMPONENT24, 2048, 2048);
			log(glErr("after 2048x2048 D24 storage (~16 MB, freed below)"));
			OpenGlHelper.func_153190_b(OpenGlHelper.field_153198_e,
					OpenGlHelper.field_153201_h, OpenGlHelper.field_153199_f, bigRb);
			// The color attachment is still 512x288: mismatched sizes are legal under
			// core/ARB (the FBO renders at the intersection) but EXT requires equal sizes and
			// may report INCOMPLETE_DIMENSIONS — which is itself an answer worth logging.
			int bigStatus = OpenGlHelper.func_153167_i(OpenGlHelper.field_153198_e);
			log("completeness with 2048x2048 D24 (color still 512x288 — EXT may refuse the"
					+ " mismatch by design): "
					+ (bigStatus == OpenGlHelper.field_153202_i ? "COMPLETE"
							: ("INCOMPLETE 0x" + Integer.toHexString(bigStatus).toUpperCase(
									java.util.Locale.ROOT))));
		} finally {
			// ---- tear down and restore, by value, in reverse ----
			if (bigRb != 0) OpenGlHelper.func_153184_g(bigRb);
			if (depthRb != 0) OpenGlHelper.func_153184_g(depthRb);
			if (fbo != 0) OpenGlHelper.func_153174_h(fbo);
			if (colorTex != 0) GL11.glDeleteTextures(colorTex);

			GL11.glMatrixMode(GL11.GL_MODELVIEW);
			GL11.glPopMatrix();
			GL11.glMatrixMode(GL11.GL_PROJECTION);
			GL11.glPopMatrix();
			GL11.glMatrixMode(GL11.GL_MODELVIEW);
			OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, savedFbo);
			GL11.glViewport(vp.get(0), vp.get(1), vp.get(2), vp.get(3));
			setEnabled(GL11.GL_DEPTH_TEST, savedDepthTest);
			GL11.glDepthMask(savedDepthMask);
			GL11.glDepthFunc(savedDepthFunc);
			setEnabled(GL11.GL_BLEND, savedBlend);
			setEnabled(GL11.GL_ALPHA_TEST, savedAlphaTest);
			setEnabled(GL11.GL_SCISSOR_TEST, savedScissor);
			setEnabled(GL11.GL_FOG, savedFog);
			setEnabled(GL11.GL_LIGHTING, savedLighting);
			setEnabled(GL11.GL_CULL_FACE, savedCull);
			GL11.glClearDepth(savedClearDepth);
			GL11.glClearColor(savedClearR, savedClearG, savedClearB, savedClearA);
			GL11.glColor4f(savedColorR, savedColorG, savedColorB, savedColorA);
			GL11.glColorMask(savedMaskR, savedMaskG, savedMaskB, savedMaskA);
			OpenGlHelper.setActiveTexture(TEX_UNIT1);
			setEnabled(GL11.GL_TEXTURE_2D, savedTex2dUnit1);
			OpenGlHelper.setActiveTexture(TEX_UNIT0);
			setEnabled(GL11.GL_TEXTURE_2D, savedTex2dUnit0);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, savedTexBinding0);
			OpenGlHelper.setActiveTexture(savedActiveTexture);

			int after = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
			log("restore check: FBO binding before=" + savedFbo + " after=" + after
					+ (after == savedFbo ? " (restored)" : " *** NOT RESTORED ***"));
			log("done. This class is C1.0 throwaway — delete it once the field test is graded.");
		}
	}

	/**
	 * Completeness can pass while depth testing is broken, so: draw the NEAR quad first, the
	 * FAR quad second. A working depth test keeps the near colour; painter's order would
	 * overwrite it. The far-first control run separates "readback works at all" from "the
	 * depth test discriminates" — with identity matrices, vertex z passes through to clip z.
	 */
	private static void functionalDepthTest(int w, int h) {
		GL11.glViewport(0, 0, w, h);
		GL11.glMatrixMode(GL11.GL_PROJECTION);
		GL11.glLoadIdentity();
		GL11.glMatrixMode(GL11.GL_MODELVIEW);
		GL11.glLoadIdentity();
		// Canonicalize everything that could pollute an untextured colour readback —
		// FramebufferPass.begin()'s set (unit-1 texturing is already off from the save block).
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glDisable(GL11.GL_BLEND);
		GL11.glDisable(GL11.GL_ALPHA_TEST);
		GL11.glDisable(GL11.GL_SCISSOR_TEST);
		GL11.glDisable(GL11.GL_FOG);
		GL11.glDisable(GL11.GL_LIGHTING);
		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glColorMask(true, true, true, true);
		GL11.glEnable(GL11.GL_DEPTH_TEST);
		GL11.glDepthFunc(GL11.GL_LESS);
		GL11.glDepthMask(true);
		GL11.glClearDepth(1.0);

		// Control: far red then near green — green under BOTH depth testing and painter's
		// order, so it validates only the draw+readback machinery.
		log("control (far-first):  " + drawPairAndRead(false));
		// Discriminator: near green then far red — green ONLY if the depth test rejects the far
		// fragments.
		log("discriminator (near-first): " + drawPairAndRead(true));
	}

	private static String drawPairAndRead(boolean nearFirst) {
		GL11.glClearColor(0, 0, 1, 1);   // blue ground: visible if neither quad lands
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
		if (nearFirst) {
			quad(0f, 1f, 0f, -0.5f);     // near, GREEN
			quad(1f, 0f, 0f, 0.5f);      // far, RED
		} else {
			quad(1f, 0f, 0f, 0.5f);
			quad(0f, 1f, 0f, -0.5f);
		}
		java.nio.ByteBuffer px = BufferUtils.createByteBuffer(4);
		GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
		// The quads span the whole viewport, so ANY pixel answers; (0,0) avoids assuming the
		// y-flip convention.
		int r = px.get(0) & 0xFF, g = px.get(1) & 0xFF, b = px.get(2) & 0xFF;
		String colour = g > 200 && r < 50 ? "GREEN" : r > 200 && g < 50 ? "RED"
				: b > 200 ? "BLUE(nothing drew?)" : "MIXED";
		return "read " + r + "," + g + "," + b + " => " + colour + "   " + glErr("readback");
	}

	private static void setEnabled(int cap, boolean enabled) {
		if (enabled) {
			GL11.glEnable(cap);
		} else {
			GL11.glDisable(cap);
		}
	}

	private static void quad(float r, float g, float b, float z) {
		GL11.glColor4f(r, g, b, 1f);
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glVertex3f(-1f, -1f, z);
		GL11.glVertex3f(1f, -1f, z);
		GL11.glVertex3f(1f, 1f, z);
		GL11.glVertex3f(-1f, 1f, z);
		GL11.glEnd();
	}
}
