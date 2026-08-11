package opengpu.v2.mc.client.render;

import java.nio.IntBuffer;

import net.minecraft.client.renderer.OpenGlHelper;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * The only way any OpenGPU code touches FBO binding. Captures the observable GL state it
 * will disturb BY VALUE before switching to a scene FBO, and restores those exact values
 * after — never "0", never framebufferMc-by-name, never attrib stacks (all three fail under
 * Angelica in documented scenarios; see ANGELICA-NOTES.md, rules 3/5/9).
 *
 * Every GL call goes through plain GL11/OpenGlHelper entry points, which Angelica's
 * redirector tracks. glGetInteger reads are served from GLSM's cache under Angelica and from
 * the driver on vanilla — both correct.
 *
 * Render thread only.
 */
public final class FramebufferPass {
	// GL constants not exposed by the 1.7.10 class constants we use.
	private static final int GL_FRAMEBUFFER_BINDING = 36006;
	private static final int GL_ACTIVE_TEXTURE = 34016;
	private static final int GL_BLEND_DST_ALPHA = 32970;
	private static final int GL_BLEND_SRC_ALPHA = 32971;
	private static final int TEX_UNIT0 = OpenGlHelper.defaultTexUnit;   // 33984
	private static final int TEX_UNIT1 = OpenGlHelper.lightmapTexUnit;  // 33985

	private final IntBuffer viewport = BufferUtils.createIntBuffer(16);

	private final java.nio.FloatBuffer colorBuffer = BufferUtils.createFloatBuffer(16);
	private final java.nio.ByteBuffer writeMaskBuffer = BufferUtils.createByteBuffer(16);

	private int savedFbo;
	private int savedViewportX, savedViewportY, savedViewportW, savedViewportH;
	private boolean savedBlend;
	private int savedBlendSrc, savedBlendDst;
	private int savedBlendSrcAlpha, savedBlendDstAlpha;
	private float savedLineWidth;
	private float savedColorR, savedColorG, savedColorB, savedColorA;
	private boolean savedMaskR, savedMaskG, savedMaskB, savedMaskA;
	private float savedClearR, savedClearG, savedClearB, savedClearA;
	private boolean savedAlphaTest;
	private boolean savedDepthTest;
	private boolean savedDepthMask;
	private boolean savedScissor;
	private boolean savedFog;
	private boolean savedLighting;
	private boolean savedCull;
	private int savedActiveTexture;
	private boolean savedTex2dUnit0;
	private boolean savedTex2dUnit1;
	private boolean active;

	public static boolean isSupported() {
		return OpenGlHelper.isFramebufferEnabled();
	}

	/**
	 * Save the caller's GL state and establish the canonical 2D state. Pair with {@link #end()},
	 * and call {@link #retarget} at least once in between to choose a framebuffer.
	 *
	 * SPLIT from the former {@code begin(fbo, width, height)} on 2026-08-09. Everything here is
	 * per-PASS — ~21 state reads and the enable/blend/line-width canonicalisation — and none of
	 * it depends on which framebuffer is being drawn into. It used to run once per visible SCENE
	 * because the whole thing was inside the per-scene loop, so two scenes paid for it twice and
	 * N scenes N times, for a value that cannot differ between them.
	 *
	 * The split is also structural, not just a saving: this class is deliberately not reentrant
	 * and SceneRenderer holds exactly one instance, so anything that wants to render into a
	 * SECOND target in the same frame — a static-layer FBO, ROADMAP P1 — cannot nest a pass and
	 * must retarget within one. That is the shape this enables.
	 */
	public void begin() {
		if (active) {
			throw new IllegalStateException("FramebufferPass is not reentrant");
		}
		active = true;

		savedFbo = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
		viewport.clear();
		GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
		savedViewportX = viewport.get(0);
		savedViewportY = viewport.get(1);
		savedViewportW = viewport.get(2);
		savedViewportH = viewport.get(3);
		savedBlend = GL11.glIsEnabled(GL11.GL_BLEND);
		// GL_BLEND_SRC/DST alias the *RGB* factors; vanilla sets separate alpha factors via
		// OpenGlHelper.glBlendFunc nearly every frame, so restoring with the 2-arg call
		// would silently overwrite them with the RGB pair.
		savedBlendSrc = GL11.glGetInteger(GL11.GL_BLEND_SRC);
		savedBlendDst = GL11.glGetInteger(GL11.GL_BLEND_DST);
		savedBlendSrcAlpha = GL11.glGetInteger(GL_BLEND_SRC_ALPHA);
		savedBlendDstAlpha = GL11.glGetInteger(GL_BLEND_DST_ALPHA);
		savedLineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);
		colorBuffer.clear();
		GL11.glGetFloat(GL11.GL_COLOR_CLEAR_VALUE, colorBuffer);
		savedClearR = colorBuffer.get(0);
		savedClearG = colorBuffer.get(1);
		savedClearB = colorBuffer.get(2);
		savedClearA = colorBuffer.get(3);
		writeMaskBuffer.clear();
		GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, writeMaskBuffer);
		savedMaskR = writeMaskBuffer.get(0) != 0;
		savedMaskG = writeMaskBuffer.get(1) != 0;
		savedMaskB = writeMaskBuffer.get(2) != 0;
		savedMaskA = writeMaskBuffer.get(3) != 0;
		colorBuffer.clear();
		GL11.glGetFloat(GL11.GL_CURRENT_COLOR, colorBuffer);
		savedColorR = colorBuffer.get(0);
		savedColorG = colorBuffer.get(1);
		savedColorB = colorBuffer.get(2);
		savedColorA = colorBuffer.get(3);
		savedAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
		savedDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
		savedDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
		savedScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
		savedFog = GL11.glIsEnabled(GL11.GL_FOG);
		savedLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
		savedCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
		savedActiveTexture = GL11.glGetInteger(GL_ACTIVE_TEXTURE);

		// Texture-2D enable state of units 0 and 1 — toggling these selects Iris program
		// variants, so they are part of the by-value contract (ANGELICA-NOTES rule 5).
		OpenGlHelper.setActiveTexture(TEX_UNIT1);
		savedTex2dUnit1 = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		OpenGlHelper.setActiveTexture(TEX_UNIT0);
		savedTex2dUnit0 = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
		// Canonicalize unit 0 too: the replay's texturing tracker assumes a known starting
		// value, and inheriting the HUD's (virtually always enabled) state draws untextured
		// primitives through a stale texel. end() restores the saved value either way.
		GL11.glDisable(GL11.GL_TEXTURE_2D);

		GL11.glDisable(GL11.GL_SCISSOR_TEST);
		GL11.glDisable(GL11.GL_FOG);
		GL11.glDisable(GL11.GL_LIGHTING);
		GL11.glDisable(GL11.GL_CULL_FACE);
		GL11.glDisable(GL11.GL_ALPHA_TEST);
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glDepthMask(true);
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		// Canvas outlines are 1px; the world's block-highlight pass leaves this at 2.
		GL11.glLineWidth(1.0F);

		// Matrices pushed ONCE; retarget() reloads them per framebuffer and end() pops both.
		// Pushing per retarget would grow the stack by one pair per scene and overflow it at
		// GL's 2-deep minimum guarantee for PROJECTION.
		GL11.glMatrixMode(GL11.GL_PROJECTION);
		GL11.glPushMatrix();
		GL11.glMatrixMode(GL11.GL_MODELVIEW);
		GL11.glPushMatrix();
	}

	/**
	 * Point the open pass at a framebuffer: bind it, size the viewport, clear it, and set the
	 * logical y-down ortho for its dimensions. Callable repeatedly within one {@link #begin()}.
	 *
	 * Everything here genuinely varies per target, which is why it is the part that repeats.
	 */
	public void retarget(int fbo, int width, int height) {
		if (!active) {
			throw new IllegalStateException("retarget() without begin()");
		}
		OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, fbo);
		GL11.glViewport(0, 0, width, height);

		// Clear FIRST, with every channel writable — glClear obeys the colour mask, so
		// masking alpha before this point leaves the attachment's alpha at its undefined
		// (in practice zero) initial contents and the whole scene reads as transparent.
		// The mask is re-set per target rather than once per pass because the clear depends
		// on it, and a previous retarget left it alpha-masked.
		GL11.glColorMask(true, true, true, true);
		GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
		// NOW pin alpha at the opaque value the clear just wrote: no recorded draw can
		// lower it. Blending math is unaffected (it reads the *source* alpha), so
		// translucent draws still composite correctly onto RGB. This is what lets surfaces
		// draw the scene texture without touching GL_BLEND, which is unsafe mid-world
		// under an Iris blend lock.
		GL11.glColorMask(true, true, true, false);

		// Matrices from scratch on the pair begin() pushed; end()'s pops restore the caller's.
		GL11.glMatrixMode(GL11.GL_PROJECTION);
		GL11.glLoadIdentity();
		// Logical y-down: (0,0) is the canvas top-left, like every 2D canvas API.
		GL11.glOrtho(0, width, height, 0, -1, 1);
		GL11.glMatrixMode(GL11.GL_MODELVIEW);
		GL11.glLoadIdentity();
	}

	/** Restore every captured value. */
	public void end() {
		if (!active) {
			throw new IllegalStateException("end() without begin()");
		}
		active = false;

		GL11.glMatrixMode(GL11.GL_PROJECTION);
		GL11.glPopMatrix();
		GL11.glMatrixMode(GL11.GL_MODELVIEW);
		GL11.glPopMatrix();

		OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, savedFbo);
		GL11.glViewport(savedViewportX, savedViewportY, savedViewportW, savedViewportH);

		setEnabled(GL11.GL_BLEND, savedBlend);
		// The 4-arg wrapper degrades to plain glBlendFunc internally when separate blending
		// is unavailable, so this is safe unconditionally (and redirected under Angelica).
		OpenGlHelper.glBlendFunc(savedBlendSrc, savedBlendDst, savedBlendSrcAlpha, savedBlendDstAlpha);
		GL11.glLineWidth(savedLineWidth);
		GL11.glColor4f(savedColorR, savedColorG, savedColorB, savedColorA);
		GL11.glColorMask(savedMaskR, savedMaskG, savedMaskB, savedMaskA);
		GL11.glClearColor(savedClearR, savedClearG, savedClearB, savedClearA);
		setEnabled(GL11.GL_ALPHA_TEST, savedAlphaTest);
		setEnabled(GL11.GL_DEPTH_TEST, savedDepthTest);
		GL11.glDepthMask(savedDepthMask);
		setEnabled(GL11.GL_SCISSOR_TEST, savedScissor);
		setEnabled(GL11.GL_FOG, savedFog);
		setEnabled(GL11.GL_LIGHTING, savedLighting);
		setEnabled(GL11.GL_CULL_FACE, savedCull);

		OpenGlHelper.setActiveTexture(TEX_UNIT1);
		setEnabled(GL11.GL_TEXTURE_2D, savedTex2dUnit1);
		OpenGlHelper.setActiveTexture(TEX_UNIT0);
		setEnabled(GL11.GL_TEXTURE_2D, savedTex2dUnit0);
		// Restore whichever unit was active by value (it may be neither 0 nor 1).
		OpenGlHelper.setActiveTexture(savedActiveTexture);
	}

	private static void setEnabled(int cap, boolean enabled) {
		if (enabled) {
			GL11.glEnable(cap);
		} else {
			GL11.glDisable(cap);
		}
	}

	// ------------------------------------------------------------------
	// FBO lifecycle helpers (OpenGlHelper wrappers only; see ANGELICA-NOTES rule 1)

	/**
	 * Largest scene dimension this GL context can allocate.
	 *
	 * GL_MAX_TEXTURE_SIZE is the binding limit here: the scene FBO's only attachment is a
	 * colour TEXTURE, so GL_MAX_RENDERBUFFER_SIZE — which the design names alongside it —
	 * does not apply until something attaches a renderbuffer. Queried lazily because it
	 * needs a current context, and cached because the answer cannot change within one.
	 */
	public static int maxSceneDimension() {
		if (maxDimension < 0) {
			maxDimension = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
		}
		return maxDimension;
	}

	private static int maxDimension = -1;

	private static final int TRUST_UNKNOWN = -1;
	private static final int TRUST_NO = 0;
	private static final int TRUST_YES = 1;
	private static int statusQueryTrust = TRUST_UNKNOWN;
	private static boolean statusQueryUntrustedLogged;

	/**
	 * Does {@code glCheckFramebufferStatus} on THIS context actually report incompleteness, or
	 * does it answer COMPLETE unconditionally?
	 *
	 * Not a paranoid question. Angelica 2.2.x's SDL GPU backend emulates framebuffers and its
	 * {@code checkFramebufferStatus()} returns {@code GL_FRAMEBUFFER_COMPLETE} for everything;
	 * Angelica knows this and exposes {@code framebufferCompletenessIsMeaningful()} so Iris can
	 * opt out. We cannot call that — it does not exist at 2.1.59 and is Angelica-internal — and
	 * our own status call is redirected into the same stub, so without this probe our
	 * completeness guard below is silently dead code on that backend. See ANGELICA-NOTES
	 * § 2.2.x survey, finding 1.
	 *
	 * The probe is a self-test of the QUERY, not of any real framebuffer: build an FBO with no
	 * attachments at all and ask. The GL spec makes that INCOMPLETE_MISSING_ATTACHMENT — and it
	 * stays incomplete even under ARB_framebuffer_no_attachments (GL 4.3), because the default
	 * width/height of a fresh framebuffer object are zero. So a backend that answers COMPLETE
	 * here is answering COMPLETE to everything, and has told us so with a case it cannot
	 * legitimately pass.
	 *
	 * Why not verify the ATTACHMENT instead, which is the tempting alternative: under Angelica
	 * {@code glGetTexLevelParameteri(GL_TEXTURE_2D, …)} is served from GLSM's
	 * {@code TextureInfoCache} — it replays the dimensions WE passed to {@code glTexImage2D}
	 * rather than what the driver allocated, and clamps through {@code Math.max(w >> level, 1)}
	 * so it can never return 0. Corroborating our own request against our own request would
	 * report success on every backend, honest or not. Do not re-add it.
	 */
	private static boolean statusQueryIsMeaningful() {
		if (statusQueryTrust == TRUST_UNKNOWN) {
			// Bind/restore by value, exactly as everything else in this class does. Binding an
			// incomplete FBO is legal; only drawing to or reading from one is an error, and we
			// do neither.
			int previous = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
			int probe = OpenGlHelper.func_153165_e();
			if (probe == 0) {
				// Name 0 is the DEFAULT framebuffer, which is legitimately COMPLETE. Binding it
				// and reading COMPLETE would prove nothing yet look exactly like a lying backend,
				// so the one thing we must not do is cache a verdict. Leave the question open —
				// FBO creation is rare enough that re-probing on the next scene costs nothing —
				// and behave for now exactly as this method did before the probe existed.
				return true;
			}
			OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, probe);
			int status = OpenGlHelper.func_153167_i(OpenGlHelper.field_153198_e);
			OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, previous);
			OpenGlHelper.func_153174_h(probe);
			statusQueryTrust = status != OpenGlHelper.field_153202_i ? TRUST_YES : TRUST_NO;
		}
		return statusQueryTrust == TRUST_YES;
	}

	/**
	 * Create an FBO with an RGBA8 color attachment. Returns {fbo, colorTexture}, or null when
	 * the scene cannot be allocated at that size.
	 *
	 * Two gates, and they fail in opposite directions on purpose. The dimension check is
	 * authoritative — it runs before any GL call and reads a limit no cache can distort. The
	 * completeness check is authoritative only where {@link #statusQueryIsMeaningful()} says the
	 * driver answers it honestly; where it does not, an FBO is ACCEPTED rather than rejected,
	 * because refusing every scene on a backend we merely cannot audit would blank every screen
	 * in the game to guard against a failure we have no reason to believe occurred.
	 */
	public static int[] createSceneFbo(int width, int height) {
		// Checked BEFORE any GL call. glTexImage2D past GL_MAX_TEXTURE_SIZE raises
		// GL_INVALID_VALUE and allocates nothing, which then surfaces only as an incomplete
		// framebuffer — a confusing symptom for a knowable cause — and leaves an error in the
		// queue for whichever mod calls glGetError next. Unreachable while the scene size is
		// a compile-time constant; the moment resolution becomes settable it is the first
		// thing a program can drive out of range.
		int max = maxSceneDimension();
		if (width <= 0 || height <= 0 || width > max || height > max) {
			return null;
		}
		// Resolved BEFORE we bind anything. The probe does its own bind/restore, and running it
		// midway through ours would nest two save/restore pairs around the same binding for no
		// reason. After the first call it is a field read.
		boolean statusMeaningful = statusQueryIsMeaningful();
		// Restored before returning: leaving the new color attachment bound would let a
		// later draw sample a texture attached to the active render target.
		int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		int tex = GL11.glGenTextures();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
				GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);

		int fbo = OpenGlHelper.func_153165_e();
		int previous = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
		OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, fbo);
		OpenGlHelper.func_153188_a(OpenGlHelper.field_153198_e, OpenGlHelper.field_153200_g,
				GL11.GL_TEXTURE_2D, tex, 0);
		int status = OpenGlHelper.func_153167_i(OpenGlHelper.field_153198_e);
		OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, previous);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
		if (status != OpenGlHelper.field_153202_i) {
			// Acted on regardless of trust, and deliberately so: a backend that answers COMPLETE
			// to everything can never produce this branch, so reaching it is positive evidence
			// from any backend. Only the COMPLETE answer needs the trust flag to mean anything.
			OpenGlHelper.func_153174_h(fbo);
			GL11.glDeleteTextures(tex);
			return null;
		}
		if (!statusMeaningful && !statusQueryUntrustedLogged) {
			// Session-wide fact about the backend, so once is right here — unlike a per-scene
			// allocation failure, which SceneRenderer deliberately does NOT funnel through a
			// one-shot flag for exactly that reason.
			statusQueryUntrustedLogged = true;
			opengpu.OpenGPU.logger.warn("This render backend reports GL_FRAMEBUFFER_COMPLETE even"
					+ " for a framebuffer with no attachments, so OpenGPU cannot verify its scene"
					+ " framebuffers; they are being accepted unchecked. The known cause is"
					+ " Angelica's SDL GPU backend (-Dangelica.sdlgpu.enable=true), which emulates"
					+ " framebuffer objects. If screens render blank or corrupt, rule this out"
					+ " first by running without that flag.");
		}
		return new int[] { fbo, tex };
	}

	public static void deleteSceneFbo(int fbo, int colorTexture) {
		OpenGlHelper.func_153174_h(fbo);
		GL11.glDeleteTextures(colorTexture);
	}
}
