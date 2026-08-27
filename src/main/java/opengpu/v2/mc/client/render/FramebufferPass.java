package opengpu.v2.mc.client.render;

import java.nio.IntBuffer;

import net.minecraft.client.renderer.OpenGlHelper;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/**
 * The only way any OpenGPU code touches FBO binding. Captures the observable GL state it
 * will disturb BY VALUE before switching to a scene FBO, and restores those exact values
 * after — never "0", never framebufferMc-by-name, never attrib stacks (all three fail under
 * Angelica in documented scenarios; see ANGELICA-NOTES.md, rules 3/5/9).
 *
 * Every GL call goes through plain GL11/OpenGlHelper entry points, which Angelica's
 * redirector tracks (ANGELICA-NOTES rule 1).
 *
 * The pname lists below name what THIS class reads and are not a census of GLSM's switches;
 * consult the source for anything else, and note that "no enable cap is carried" is a claim
 * about the caps this class saves, not about every cap in the switch.
 *
 * READING STATE BACK IS NOT UNIFORM, and this paragraph used to say it was — "glGetInteger
 * reads are served from GLSM's cache, both correct" was a general licence, and C1.3.0 (2026-08-27)
 * established it is false in general. GLSM's {@code glGetInteger} switch carries the pnames this
 * class asks it for — viewport, the four blend factors, active texture, depth func, cull-face
 * mode, front face — but it carries NO ENABLE CAP. {@code GL_DEPTH_TEST}, {@code GL_CULL_FACE},
 * {@code GL_ALPHA_TEST}, {@code GL_LIGHTING} fall through to a 3.3 core driver, where they are
 * compatibility-only enums: {@code GL_INVALID_ENUM}, destination untouched, garbage saved.
 * <b>Read every enable with {@code glIsEnabled} or {@code glGetBoolean}, never
 * {@code glGetInteger}</b> — as the saves below do. Two further pnames have their own rule.
 * {@code GL_DEPTH_RANGE} is BUFFER-FORM ONLY: neither scalar getter has a case for it, so a
 * scalar read reaches the driver and returns one component of two. And the depth VALUES — the
 * clear value and the two range bounds — are stored by GLSM as doubles, so <b>read them with
 * {@code glGetDouble}, not {@code glGetFloat}</b>: the float forms apply a narrowing cast while
 * the double forms serve them uncast. {@code glGetDouble} IS redirected — {@code GLSMRedirector}'s
 * gl11 map carries {@code glGetDouble} and {@code glGetDoublev} beside {@code glGetFloat} — so
 * the exact read costs nothing in tracking.
 *
 * <b>Every claim in this paragraph was checked against Angelica tag 2.2.8 with
 * {@code git show 2.2.8:<path>}.</b> The reference clone's working tree sat on tag 2.1.59 until
 * 2026-08-27 while the pack ran 2.2.8, and an earlier draft of this very paragraph asserted the
 * OPPOSITE about {@code glGetDouble} — and changed the code to match — because it was greped
 * from that tree. The clone has since been moved to 2.2.8, but name the ref in the command
 * anyway: a grep's output never states which ref produced it. See CASEBOOK D12.
 *
 * Render thread only.
 */
public final class FramebufferPass {
	// GL constants not exposed by the 1.7.10 class constants we use.
	/**
	 * 36006. Named {@code GL_FRAMEBUFFER_BINDING} by EXT_framebuffer_object and
	 * {@code GL_DRAW_FRAMEBUFFER_BINDING} by ARB_framebuffer_object / GL 3.0 — the SAME number,
	 * with a different meaning depending on which extension the driver exposes. On the ARB path it
	 * is the DRAW binding specifically, which is the whole content of the note on
	 * {@link #savedFbo}.
	 */
	private static final int GL_FRAMEBUFFER_BINDING = 36006;
	private static final int GL_ACTIVE_TEXTURE = 34016;
	private static final int GL_BLEND_DST_ALPHA = 32970;
	private static final int GL_BLEND_SRC_ALPHA = 32971;
	private static final int TEX_UNIT0 = OpenGlHelper.defaultTexUnit;   // 33984
	private static final int TEX_UNIT1 = OpenGlHelper.lightmapTexUnit;  // 33985

	private final IntBuffer viewport = BufferUtils.createIntBuffer(16);

	private final java.nio.FloatBuffer colorBuffer = BufferUtils.createFloatBuffer(16);
	/** Depth range only — it is buffer-form, and the DOUBLE form is the exact one. */
	private final java.nio.DoubleBuffer depthRangeBuffer = BufferUtils.createDoubleBuffer(16);
	private final java.nio.ByteBuffer writeMaskBuffer = BufferUtils.createByteBuffer(16);

	/**
	 * The caller's DRAW framebuffer. Restored through {@code GL_FRAMEBUFFER}, which writes BOTH
	 * halves — so a split read/draw binding does not survive begin()/end().
	 *
	 * ASYMMETRY, INVESTIGATED 2026-08-12 AND LEFT AS IS, with the conditions that would make it a
	 * bug written here rather than in a roadmap nobody reads at the point of change. It is real and
	 * mechanical, not theoretical:
	 *
	 * <ul>
	 * <li>Under Angelica the {@code glGetInteger} above is served from GLSM's cache, and GLSM tracks
	 *     {@code drawFramebuffer} and {@code readFramebuffer} as SEPARATE fields, dispatching 36006
	 *     to the draw one. Its {@code glBindFramebuffer(GL_FRAMEBUFFER, …)} assigns both. So on this
	 *     stack the split is representable, and {@link #end()} would clobber a caller's read
	 *     binding.</li>
	 * <li>Iris (Angelica's shader pipeline) genuinely uses split bindings — {@code GlFramebuffer}
	 *     has {@code bindAsReadBuffer}/{@code bindAsDrawBuffer}, and Iris has its own scar comment
	 *     about confusing the two.</li>
	 * </ul>
	 *
	 * NOT FIXED, because no reachable producer was found. {@code SceneRenderer} runs at
	 * {@code RenderTickEvent.START}, i.e. before world rendering, so the state it inherits is
	 * whatever survived the PREVIOUS frame's render and swap. Iris's split binds live inside its
	 * composite/deferred passes; the one {@code GL_READ_FRAMEBUFFER → 0} reset found in
	 * {@code FinalPassRenderer} is in setup, not the per-frame path. Vanilla 1.7.10 cannot produce a
	 * split at all — every vanilla bind goes through {@code GL_FRAMEBUFFER}.
	 *
	 * TWO REASONS THE OBVIOUS FIX IS NOT OBVIOUS, and they are why this is a note and not a patch:
	 *
	 * <ul>
	 * <li><b>EXT-only drivers have no split to preserve.</b> EXT_framebuffer_object defines a single
	 *     binding; {@code GL_READ_FRAMEBUFFER_BINDING} does not exist there and querying it is a GL
	 *     error. Any save-both/restore-both fix must be gated on the ARB/GL3 path — and the 2006-era
	 *     floor hardware this project still has open as a decision is exactly the EXT case.</li>
	 * <li><b>We never READ from a framebuffer</b> — no {@code glReadPixels}, no
	 *     {@code glBlitFramebuffer}, no {@code glCopyTex*} anywhere in this mod. So we can only ever
	 *     CAUSE this for someone downstream; we can never suffer it. That bounds the blast radius to
	 *     "a mod that leaves read ≠ draw across the frame boundary AND depends on the read half
	 *     surviving our pass".</li>
	 * </ul>
	 *
	 * WHAT WOULD MAKE THIS A REAL BUG, so the next person can check in minutes rather than
	 * re-deriving it: a mod leaving read ≠ draw at {@code RenderTickEvent.START}. Log
	 * {@code GL_READ_FRAMEBUFFER_BINDING} and {@code GL_DRAW_FRAMEBUFFER_BINDING} at the top of
	 * {@code SceneRenderer.onRenderTick} for one session with shaders on; if they ever differ, this
	 * becomes a patch and the gate above is the design constraint on it.
	 */
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
	// The five C1.3.1 additions: modes and values, never enables. See begin()'s reads.
	private int savedDepthFunc;
	private int savedCullFaceMode;
	private int savedFrontFace;
	private double savedClearDepth;
	private double savedDepthRangeNear;
	private double savedDepthRangeFar;
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
	 * per-PASS — ~26 state reads and the enable/blend/line-width canonicalisation — and none of
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
		// Depth range travels with the viewport, and is BUFFER-FORM ONLY: neither scalar getter
		// has a case for it, so a scalar read falls through to the driver and hands back one
		// component of two. The DoubleBuffer form is carried AND serves both bounds uncast from
		// viewportState, which stores doubles — the float buffer form narrows them. GLSM writes
		// at params.position() without advancing it, hence the clear(): it is what makes the
		// absolute get(0)/get(1) correct, as the sibling reads all do.
		depthRangeBuffer.clear();
		GL11.glGetDouble(GL11.GL_DEPTH_RANGE, depthRangeBuffer);
		savedDepthRangeNear = depthRangeBuffer.get(0);
		savedDepthRangeFar = depthRangeBuffer.get(1);
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
		// The five C1.3.1 additions. Every one is a MODE or a VALUE, never an enable, so
		// glGetInteger is correct for the three that GLSM's switch carries; see the class
		// javadoc for why that distinction is not decorative.
		savedDepthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
		savedCullFaceMode = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
		savedFrontFace = GL11.glGetInteger(GL11.GL_FRONT_FACE);
		// glGetDouble, which IS redirected (GLSMRedirector's gl11 map carries glGetDouble and
		// glGetDoublev beside glGetFloat) and serves this pname UNCAST from depthState, where
		// the float form applies a narrowing (float) cast. Exact, and on the same tracked path
		// as every other read here.
		savedClearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
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
		// Canonicalise what the five new saves cover. A save without a matching SET buys
		// nothing: the state would still be whatever the world left, and the save would
		// merely put it back. The depth RANGE is the sharpest of these — a leftover
		// non-default range from a held-item or outline pass compresses the whole 3D layer
		// into a z slab, and the symptom reads as a projection bug rather than a state leak.
		// GL_CULL_FACE itself stays DISABLED above; only its mode is pinned, so that a 3D
		// pass enabling the cap locally does not inherit the world's winding.
		GL11.glDepthFunc(GL11.GL_LEQUAL);
		GL11.glDepthRange(0.0D, 1.0D);
		GL11.glClearDepth(1.0D);
		GL11.glFrontFace(GL11.GL_CCW);
		GL11.glCullFace(GL11.GL_BACK);
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
	public void retarget(int fbo, int width, int height, boolean hasDepth) {
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
		// The depth mirror of that same argument, and it must be exact. glClear obeys the
		// DEPTH write mask exactly as it obeys the colour mask. GLSM DOES redirect glClear
		// (GLSMRedirector's gl11 map carries it), but its handler only records for display
		// lists and then forwards to the backend without touching either mask — so real GL
		// semantics reach the driver. An earlier draft claimed glClear was not intercepted at
		// all, which was false; the conclusion is unchanged, the reason was not. begin() sets glDepthMask(true) once, but this
		// class is explicitly built for repeated retargets inside one begin(): the moment a 3D
		// draw leaves the mask false, the NEXT scene's depth clear would silently do nothing and
		// that scene would depth-test against its predecessor's buffer. That is the alpha bug's
		// twin, and it only appears with two or more visible scenes — which a single-screen
		// field test never reaches.
		GL11.glColorMask(true, true, true, true);
		GL11.glDepthMask(true);
		GL11.glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
		GL11.glClearDepth(1.0D);
		// One clear of both buffers, not two calls: the ordering argument above is written
		// about a single masked clear.
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT
				| (hasDepth ? GL11.GL_DEPTH_BUFFER_BIT : 0));
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
		// The five C1.3.1 restores, mirroring begin()'s reads one for one. The saves hold
		// DOUBLES (widened when the reads moved to glGetDouble), so nothing narrows on the way
		// back and no round trip through float occurs — an earlier draft of this comment said
		// the opposite and pointed at a class javadoc that now says so too. All five setters
		// write through to the driver as well as to GLSM's cache, so the restore is real.
		GL11.glDepthFunc(savedDepthFunc);
		GL11.glClearDepth(savedClearDepth);
		GL11.glDepthRange(savedDepthRangeNear, savedDepthRangeFar);
		GL11.glCullFace(savedCullFaceMode);
		GL11.glFrontFace(savedFrontFace);
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
	 * Largest scene dimension the COLOUR attachment can take: {@code GL_MAX_TEXTURE_SIZE}.
	 *
	 * There are now TWO limits and they bound different attachments. This one is the colour
	 * texture's and remains the authority for every 2D scene. The premise this javadoc used to
	 * carry — "GL_MAX_RENDERBUFFER_SIZE does not apply until something attaches a
	 * renderbuffer" — EXPIRED at C1.3.1, which attaches one for depth; see
	 * {@link #maxRenderbufferDimension()}. Deliberately not merged into a single min(): a 2D
	 * scene must not be refused a size its own attachment can hold because of a limit that
	 * does not apply to it, and merging the two would flip a previously-constant condition for
	 * every existing scene.
	 *
	 * Queried lazily because it needs a current context, and cached because the answer cannot
	 * change within one.
	 */
	public static int maxSceneDimension() {
		if (maxDimension < 0) {
			maxDimension = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
		}
		return maxDimension;
	}

	/**
	 * Largest dimension a DEPTH renderbuffer can take: {@code GL_MAX_RENDERBUFFER_SIZE}.
	 *
	 * The depth path's bound, and only the depth path's. Callers wanting a scene that carries
	 * depth take {@code min(maxSceneDimension(), maxRenderbufferDimension())}; callers
	 * allocating colour alone must not.
	 *
	 * The two are equal on the machine C1.0 measured (both 16384, FIELD-TEST-C10), which is
	 * exactly why a field observation there cannot tell the two gates apart and the
	 * discrimination has to be a unit-level test instead. Same lazy-and-cached reasoning as
	 * its sibling. {@code GL_MAX_RENDERBUFFER_SIZE} is a GL30 CONSTANT read through a GL11
	 * entry point, which is not a raw GL30 call and so does not touch ANGELICA-NOTES rule 1;
	 * the C1.0 spike read it the same way and the field run returned a real number.
	 */
	public static int maxRenderbufferDimension() {
		if (maxRenderbufferSize < 0) {
			maxRenderbufferSize = GL11.glGetInteger(GL30.GL_MAX_RENDERBUFFER_SIZE);
		}
		return maxRenderbufferSize;
	}

	private static int maxDimension = -1;
	private static int maxRenderbufferSize = -1;

	private static final int TRUST_UNKNOWN = -1;
	private static final int TRUST_NO = 0;
	private static final int TRUST_YES = 1;
	private static int statusQueryTrust = TRUST_UNKNOWN;
	private static boolean statusQueryUntrustedLogged;

	/**
	 * Does {@code glCheckFramebufferStatus} on THIS context actually report incompleteness, or
	 * does it answer COMPLETE unconditionally?
	 *
	 * Not a paranoid question. Angelica 2.2.x's SDL GPU backend (opt-in via the
	 * {@code angelica.sdlgpu.enable} JVM property; verified still opt-in at 2.2.8, the
	 * runtime's actual version) emulates framebuffers and its {@code checkFramebufferStatus()}
	 * returns {@code GL_FRAMEBUFFER_COMPLETE} for everything; Angelica knows this and exposes
	 * {@code framebufferCompletenessIsMeaningful()} so Iris can opt out. We cannot call that —
	 * it is Angelica-internal (and did not exist before 2.2.x) — and
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

	/**
	 * Attach a {@code GL_DEPTH_COMPONENT24} renderbuffer to an existing scene FBO. Returns the
	 * renderbuffer name, or -1 if it could not be allocated.
	 *
	 * <b>NO CALLER YET — the 3D pass that invokes this arrives in C1.3.1 group F.</b> Every
	 * path below is therefore unexercised by the suite and by any in-game run so far; the
	 * argument order, the D24 constant and the attach target are checkable only by reading
	 * them against vanilla's own sequence, which is why that provenance is cited below.
	 *
	 * <b>To be called LAZILY, on a scene's first 3D frame — not from createSceneFbo.</b> That is the
	 * settled lifetime decision (PLAN-STAGE-C.md, depth lifetime: "allocate lazily on first
	 * need, then keep"), and this is the only shape that honours it without cost elsewhere:
	 * allocating inside the create window would force the camera scan to run every frame for
	 * every mirrored scene INCLUDING settled ones, and would make the create-before-delete body
	 * reachable on frames where nothing resized. A 2D-only scene never calls this and never
	 * spends the memory (576 KiB at 512x288, 16 MiB at 2048x2048).
	 *
	 * The price of lazy is paid here and is deliberate: this owes its own completeness query,
	 * because it changes the FBO's attachments after createSceneFbo's one check has already
	 * passed. Attaching without re-checking would leave the incomplete case to surface as a
	 * scene that composites 2D correctly and renders 3D garbage.
	 *
	 * Gated on {@link #maxRenderbufferDimension()} and NOT on {@link #maxSceneDimension()} —
	 * they are different limits on different attachments, and folding them into one min() would
	 * refuse a 2D scene a size its own attachment can hold.
	 *
	 * The call sequence is vanilla {@code Framebuffer.createFramebuffer}'s own stencil-free
	 * branch, verbatim, and was proven in the field by the C1.0 spike (FIELD-TEST-C10).
	 */
	public static int attachSceneDepth(int fbo, int width, int height) {
		int max = maxRenderbufferDimension();
		if (width <= 0 || height <= 0 || width > max || height > max) {
			return -1;
		}
		int previous = GL11.glGetInteger(GL_FRAMEBUFFER_BINDING);
		OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, fbo);
		int depthRb = OpenGlHelper.func_153185_f();
		OpenGlHelper.func_153176_h(OpenGlHelper.field_153199_f, depthRb);
		OpenGlHelper.func_153186_a(OpenGlHelper.field_153199_f, GL14.GL_DEPTH_COMPONENT24,
				width, height);
		OpenGlHelper.func_153190_b(OpenGlHelper.field_153198_e, OpenGlHelper.field_153201_h,
				OpenGlHelper.field_153199_f, depthRb);
		int status = OpenGlHelper.func_153167_i(OpenGlHelper.field_153198_e);
		boolean complete = status == OpenGlHelper.field_153202_i;
		// FAILURE CLEANUP RUNS WHILE fbo IS STILL BOUND, and that order is load-bearing.
		// glDeleteRenderbuffers detaches the name only from the CURRENTLY BOUND framebuffer, so
		// deleting after the rebind below would leave this FBO carrying a dead
		// GL_DEPTH_ATTACHMENT — permanently incomplete, which then fails every later 2D replay
		// into the same scene with GL_INVALID_FRAMEBUFFER_OPERATION. That is precisely the
		// "a 3D failure blanks the 2D layer" outcome depthUnavailable exists to prevent,
		// arriving one level BELOW the latch where it cannot help. createSceneFbo's twin branch
		// can afford to delete late only because it destroys the whole FBO; this one keeps it,
		// so the explicit detach is what replaces that guarantee.
		if (!complete) {
			OpenGlHelper.func_153190_b(OpenGlHelper.field_153198_e, OpenGlHelper.field_153201_h,
					OpenGlHelper.field_153199_f, 0);
			OpenGlHelper.func_153184_g(depthRb);
		}
		// ACCEPTED RESTORE EXCEPTION, written down because this class's contract is "restore by
		// value, completely" and this is not that. The renderbuffer binding is restored to 0 by
		// convention rather than to its previous value, because there IS no previous value to
		// read: GLSM caches no GL_RENDERBUFFER_BINDING (verified zero occurrences in the 2.2.8
		// source), so a by-value save would be an uncached driver query — the one thing this
		// class avoids. 0 is GL's own initial binding, and nothing in MC or Angelica holds a
		// renderbuffer bound across calls. NOTE this differs from the C1.0 spike's reasoning,
		// which leaned on "deleting the bound RB reverts the binding per spec" — the spike
		// deleted its renderbuffer immediately and we KEEP ours, so that justification does not
		// transfer and this one replaces it.
		OpenGlHelper.func_153176_h(OpenGlHelper.field_153199_f, 0);
		OpenGlHelper.func_153171_g(OpenGlHelper.field_153198_e, previous);
		// Same trust reasoning as createSceneFbo's twin branch: a backend that answers COMPLETE
		// to everything cannot reach the incomplete case, so reaching it is positive evidence.
		return complete ? depthRb : -1;
	}

	/**
	 * Free a scene FBO and its attachments. {@code depthRb} may be -1 for a scene that never
	 * needed depth, which is the common case — every 2D-only scene.
	 */
	public static void deleteSceneFbo(int fbo, int colorTexture, int depthRb) {
		OpenGlHelper.func_153174_h(fbo);
		GL11.glDeleteTextures(colorTexture);
		// -1, never 0: 0 is a legal-looking GL name and glDeleteRenderbuffers(0) is a silent
		// no-op, so a 0 sentinel would hide a leak rather than trip on it.
		if (depthRb != -1) {
			OpenGlHelper.func_153184_g(depthRb);
		}
	}
}
