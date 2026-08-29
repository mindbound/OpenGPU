package opengpu.v2.mc.client.render;

import java.nio.FloatBuffer;
import java.util.Map;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.ResourceInfo;
import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneState;
import opengpu.v2.scene.ServerScene;
import opengpu.v2.scene.Transform3d;

/**
 * The 3D layer: every visible mesh instance, under the active camera, into the scene FBO.
 *
 * <b>Drawn FIRST, under all 2D.</b> That is a decision, not a default (PLAN-STAGE-C: "3D renders
 * first depth-cleared; 2D composites over in z-order"). It is realised by the CALL SITE, which
 * runs this between {@code retarget} — which has just cleared colour and depth — and the 2D
 * replay, which runs with {@code GL_DEPTH_TEST} disabled so every 2D fragment passes regardless
 * of what depth this pass wrote.
 *
 * <b>Matrices are RELOADED, never pushed.</b> {@code FramebufferPass.begin()} has already spent
 * one PROJECTION level and GL guarantees only two; Angelica's own stack is four deep and
 * <b>swallows overflow silently</b>, so the failure mode is corruption rather than an exception.
 * An unbalanced push would leave {@code end()}'s pops restoring OUR projection as the caller's —
 * the world would render through OpenGPU's camera. This pass therefore ends by re-issuing
 * {@code retarget}'s own matrix block verbatim, restoring the 2D pair by construction rather
 * than by stack discipline.
 *
 * <b>No interpolation, and this one is a KNOWN LIMITATION rather than a design choice.</b> The
 * 2D replay one line later is handed {@code gl.interp}; this pass is handed raw mirror state. The
 * interpolator already tracks mesh nodes (its capture walks every node with no type filter) and
 * already smooths x and y — so the smoothed values exist and are thrown away here, and
 * {@code modelMatrix} reads {@code sx}/{@code sy} raw as well. The visible consequence: meshes
 * STEP at the 20 tps server tick while canvases in the same scene glide.
 *
 * <b>HALF DONE as of C1.3.3 group A, and the remaining half is the routing.</b> The field-set
 * widening this paragraph used to defer has landed: {@code NodeInterpolator} now carries all
 * eleven scalars and slerps the quaternion, on a keyframe timeline of its own so that 3D churn
 * cannot disturb 2D motion. What has NOT happened is this pass asking for it — the draw below
 * still calls {@code Transform3d.modelMatrix(node, state)} against raw mirror state, so the
 * smoothed 3D values are computed every frame and discarded here. Meshes still STEP at 20 tps
 * while canvases glide.
 *
 * Completing it means giving {@code draw} the interpolator and the overlay the way
 * {@code Canvas2dRenderer.renderScene} takes them, and giving {@code Transform3d} entry points
 * that take a displayed TRS record instead of a {@code SceneNode}. Do NOT solve that by copying
 * the record's index constants into {@code opengpu.v2.scene} — one numbering, one home.
 *
 * <b>Normals ARRIVE as of C1.3.2 group B</b>, completing the omission C1.3.1 recorded here
 * rather than fixing a bug. The paragraph this replaces said the wire carried them "at offset 12"
 * — a literal that is now {@link MeshGl#NORMAL_OFFSET}, derived.
 *
 * <b>The enable is the load-bearing half, not the pointer.</b> GLSM takes {@code hasNormal} — the
 * bit that selects a lit shader variant — from CLIENT state, not from whether a pointer was set.
 * {@code glNormalPointer} without {@code glEnableClientState(GL_NORMAL_ARRAY)} leaves every
 * vertex lit from the single current-normal uniform, which renders a mesh that is uniformly lit
 * and looks entirely plausible. There is no error, no warning and no readback that distinguishes
 * it. That failure is invisible on any mesh whose vertex normals are all equal, which is why the
 * field arm for this is a FANNED-normal triangle and not the existing one.
 *
 * <b>This group changes the shader variant while lighting is still off, and that is expected.</b>
 * The normal attribute now reaches a different generated program than C1.3.1 used. With no
 * lights enabled the normals go unread, so the picture must be UNCHANGED — a visible difference
 * here would mean the variant switch altered something it should not, and is worth stopping for
 * rather than explaining.
 *
 * <b>LIGHTING, added in C1.3.2 group D, lives in three blocks and the ORDER IS THE DESIGN.</b>
 * A gate computes {@code activeLights()} and {@code ambientLight()} once; when nothing is
 * authored every block is skipped and this pass is byte-identical to the PRE-GROUP-D pass
 * at 5b1830c — NOT to C1.3.1's, because group B's normal pointer and enable sit OUTSIDE all
 * three lighting blocks and are issued on the unlit path too. Then: SAVE-then-SET
 * under the identity modelview (the only matrix under which a light position can later be
 * written back), light POSES uploaded once under the VIEW matrix and hoisted out of the per-node
 * loop, and an EXIT after the 2D matrix rebuild restores everything in one legal order.
 *
 * Three constraints in that block cannot be reordered, each for its own reason:
 * <ul>
 * <li><b>The material is saved BEFORE the first {@code GL_COLOR_MATERIAL} transition</b> and
 *     restored AFTER the last one. Both the enable and the disable bake the current
 *     {@code glColor} into the tracked material, and the bake fires whether or not the enable
 *     value changed — so a save taken later stores our colour, and a restore placed earlier is
 *     destroyed by the closing transition. A restore in the wrong place looks obviously correct
 *     and silently does nothing.</li>
 * <li><b>Light positions are posed under the VIEW and restored under IDENTITY.</b> They are
 *     multiplied by the modelview at call time, so posing inside the loop rides each mesh and
 *     posing under identity welds every light to the camera; and the getter returns the
 *     already-transformed value, so only identity makes writing it back a no-op.</li>
 * <li><b>{@code GL_LIGHTING} is enabled LAST and disabled FIRST</b>, so the shader variant flips
 *     only once everything it reads is set, and the 2D replay — which runs BEFORE
 *     {@code FramebufferPass.end()} — never sees it on.</li>
 * </ul>
 *
 * <b>What group E owes, because this gate hides it.</b> An unlit scene skips the whole block, so
 * {@code ingame/mesh3d.lua} — which authors no lights — re-runs unlit and its vertex-colour arm
 * (A3) stays green whatever happens here. A3's PASS therefore stops covering LIT scenes the
 * moment this ships, without changing colour. Group E owes a vertex-coloured mesh WITH a light,
 * graded on "the corners keep DISTINCT hues and gain a ramp" — not on "is it grey", since the
 * failure shade is inherited rather than fixed. <b>That arm MUST also author an AMBIENT light</b>,
 * and the reason is this class's own doing: the binder zeroes light ambient, light specular,
 * material emission/specular/shininess and (on a null ambient) the light-model term, so a
 * light's only live contribution is {@code NdotVP * diffuse}. Without an ambient, every vertex
 * facing away from the light is EXACTLY BLACK — so correct code shows one hue, "distinct hues"
 * is unreachable, and the arm would fail against a working binder. Two further things no in-repo channel can see:
 * back faces will render at the ambient term only (cull is off and two-sided lighting is inert,
 * so this is the design, not a defect — register it as a prediction), and whether any of this
 * reaches pixels at all depends on constraint 1 below.
 *
 * <b>CONSTRAINT 1 — this pass must run with PROGRAM 0 bound, and that is inherited, not set.</b>
 * GLSM's {@code ShaderManager.preDraw} returns early when a non-zero non-Iris program is bound
 * (2.2.8 {@code ShaderManager.java:130-135}), so with one inherited from the previous frame at
 * {@code RenderTickEvent.START} every line of the lighting block silently does nothing. This pass
 * deliberately does NOT bind or clear a program — that would be the mod's first program call and
 * would oblige {@code FramebufferPass} to save one forever, to defend against a state no run has
 * observed. If the field arms come back dark, CHECK THE BOUND PROGRAM FIRST, not this code.
 *
 * <b>{@code GL_NORMALIZE}: the debt this class recorded for group D, now DISCHARGED above.</b>
 * {@link Transform3d#modelMatrix} applies PER-AXIS scale
 * ({@code sx}, {@code sy}, {@code sz}), and under a non-uniform scale the normals stop being
 * UNIT LENGTH — which the fixed-function light model assumes, since it takes a raw dot product.
 * Their DIRECTION survives: Angelica applies a normal matrix unconditionally
 * ({@code Uniforms.stageNormalMatrix} → JOML {@code Matrix4f.normal}, the inverse transpose,
 * emitted at {@code VertexShaderGenerator.java:143} at 2.2.8), and the inverse transpose is
 * precisely the construction that PRESERVES perpendicularity. Unlit, none of this is visible,
 * which is why it is not fixed here and why it is easy to miss later: the defect arrives with the
 * lighting, not with the scale.
 *
 * Angelica honours {@code GL_NORMALIZE} for real — {@code emitNormalTransform} emits
 * {@code normal = normalize(normal)} under {@code key.normalizeEnabled()} (2.2.8
 * {@code VertexShaderGenerator.java:145-146}) — so the fix is the enable plus its restore, not a
 * renormalise in the mesh data.
 *
 * <b>Do NOT reach for {@code GL_RESCALE_NORMAL} instead.</b> It is the {@code else if} arm two
 * lines below ({@code :147-148}), so the two are mutually exclusive, and it multiplies by a
 * single {@code u_NormalScale} = {@code 1/‖col2‖} ({@code Uniforms.java:238-241}) — one scalar
 * from ONE column, which corrects a uniform scale and leaves the {@code sx≠sy≠sz} case wrong.
 * That is exactly the case this note exists for.
 *
 * Written HERE, at the site that must change, because a note in a plan is not an obligation
 * anything enforces. <i>The first draft of this paragraph got both facts backwards — it credited
 * {@code u_NormalScale} to GL_NORMALIZE and claimed perpendicularity was lost — by paraphrasing
 * ANGELICA-NOTES from memory rather than re-reading the row, which states both correctly. A panel
 * caught it against the shipped 2.2.8 tree. The row is at ANGELICA-NOTES.md's GL_NORMALIZE entry;
 * read it, do not recall it.</i>
 */
final class Mesh3dPass {

	/** Reused per draw; {@code glLoadMatrix} reads 16 floats from position. */
	private final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);

	/**
	 * The hardware light slots this pass owns: {@code GL_LIGHT0} and {@code GL_LIGHT1}.
	 *
	 * DERIVED from {@link SceneState#MAX_ACTIVE_LIGHTS} rather than written as 2, so the selector
	 * and the binder cannot disagree about how many lights exist — a mismatch would either leave a
	 * selected light unbound or leave a slot carrying stale state, and neither reports itself.
	 *
	 * {@code GL_LIGHT2}..{@code GL_LIGHT7} are deliberately NOT touched, not even to disable them.
	 * Angelica's pack loop is bounded by {@code VertexKey.FFP_LIGHT_COUNT = 2}, so slots above 1
	 * are accepted, tracked, and then dropped — they cannot affect our pixels, and saving or
	 * clearing them would be state we dirty for no reason. If that ceiling ever rises, this
	 * constant and {@code MAX_ACTIVE_LIGHTS} move together and this comment is the reason why.
	 */
	private static final int LIGHT_SLOTS = SceneState.MAX_ACTIVE_LIGHTS;

	/**
	 * Scratch for every lighting get/set. FOUR floats, cleared before each use, read at ABSOLUTE
	 * index 0 — and that discipline is load-bearing rather than tidy.
	 *
	 * GLSM's getters are inconsistent about position: the vector cases write at absolute 0
	 * ({@code state.position.get(0, params)}), while the scalar cases use
	 * {@code params.put(params.position(), …)}. And {@code LightState.setPosition(FloatBuffer)}
	 * reads via {@code set(buffer)} at the buffer's CURRENT position. A scheme that advanced the
	 * buffer per light — the natural way to write it — would save one light's values twice and
	 * restore the other's from the wrong offset, with no error anywhere. {@code FramebufferPass}
	 * already uses this same clear-then-absolute-0 pattern for the same reason.
	 */
	private final FloatBuffer vec4 = BufferUtils.createFloatBuffer(4);

	/** Read a 4-float light parameter. Always absolute-0; see {@link #vec4}. */
	private float[] getLight(int light, int pname) {
		vec4.clear();
		GL11.glGetLight(light, pname, vec4);
		return new float[] { vec4.get(0), vec4.get(1), vec4.get(2), vec4.get(3) };
	}

	/**
	 * Read a 4-float FRONT material parameter.
	 *
	 * {@code GL_FRONT}, never {@code GL_FRONT_AND_BACK}: the getter throws a raw
	 * {@code RuntimeException} for any face outside {FRONT, BACK} — not a GL error, an exception,
	 * and with error checks disabled it is the only signal. The SETTER accepts the wider set; the
	 * getter does not, and the two are easy to assume symmetric.
	 */
	private float[] getFrontMaterial(int pname) {
		vec4.clear();
		GL11.glGetMaterial(GL11.GL_FRONT, pname, vec4);
		return new float[] { vec4.get(0), vec4.get(1), vec4.get(2), vec4.get(3) };
	}

	/** Write a 4-float light parameter. */
	private void setLight(int light, int pname, float a, float b, float c, float d) {
		vec4.clear();
		vec4.put(a).put(b).put(c).put(d);
		vec4.flip();
		GL11.glLight(light, pname, vec4);
	}

	private void setLight(int light, int pname, float[] v) {
		setLight(light, pname, v[0], v[1], v[2], v[3]);
	}

	/** Write a 4-float material parameter on BOTH faces (the setter's domain is wider). */
	private void setMaterial(int face, int pname, float a, float b, float c, float d) {
		vec4.clear();
		vec4.put(a).put(b).put(c).put(d);
		vec4.flip();
		GL11.glMaterial(face, pname, vec4);
	}

	private void setMaterial(int face, int pname, float[] v) {
		setMaterial(face, pname, v[0], v[1], v[2], v[3]);
	}

	/** {@code GL_LIGHT_MODEL_AMBIENT} is buffer-form only — the pname is in GLSM's multi-set. */
	private float[] getLightModelAmbient() {
		vec4.clear();
		GL11.glGetFloat(GL11.GL_LIGHT_MODEL_AMBIENT, vec4);
		return new float[] { vec4.get(0), vec4.get(1), vec4.get(2), vec4.get(3) };
	}

	private void setLightModelAmbient(float r, float g, float b, float a) {
		vec4.clear();
		vec4.put(r).put(g).put(b).put(a);
		vec4.flip();
		GL11.glLightModel(GL11.GL_LIGHT_MODEL_AMBIENT, vec4);
	}

	/** Enable or disable by value — the shape {@code FramebufferPass} uses for its restores. */
	private static void setEnabled(int cap, boolean on) {
		if (on) {
			GL11.glEnable(cap);
		} else {
			GL11.glDisable(cap);
		}
	}

	/**
	 * Draw the 3D layer. Returns the number of mesh instances drawn, which is 0 whenever the
	 * layer is skipped — and the layer is skipped far more often than it runs.
	 *
	 * The caller has already established that a camera and a usable projection exist; this
	 * method does not re-derive that, because the caller needs the same answer earlier (to
	 * decide whether depth had to be allocated before {@code retarget} chose its clear mask).
	 */
	int draw(SceneState state, SceneNode camera, double[] proj, Map<Integer, MeshGl> meshes,
			int width, int height) {
		double[] extents = Transform3d.frustumExtents(proj, width, height);
		double right = extents[0], top = extents[1];
		double near = proj[2], far = proj[3];

		// --- projection. glLoadIdentity FIRST: GLSM's frustum/ortho POST-MULTIPLY onto whatever
		// is loaded, so without it the 2D ortho retarget left behind would compose with ours and
		// the result is a plausible-looking wrong frustum rather than an error.
		GL11.glMatrixMode(GL11.GL_PROJECTION);
		GL11.glLoadIdentity();
		if (proj[0] == ServerScene.PROJECTION_ORTHO) {
			GL11.glOrtho(-right, right, -top, top, near, far);
		} else {
			GL11.glFrustum(-right, right, -top, top, near, far);
		}
		GL11.glMatrixMode(GL11.GL_MODELVIEW);
		GL11.glLoadIdentity();

		// --- depth test is the one enable this pass adds; begin() disabled it for the 2D layer.
		GL11.glEnable(GL11.GL_DEPTH_TEST);

		int savedArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
		int savedElementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);

		// Client state DISABLED FIRST, then pointed, then enabled. The order matters: GLSM's
		// pointer path early-returns when a generic pointer is already enabled for that
		// attribute index, and "enabled" is inherited default-VAO state we did not set. The
		// disable is what makes that guard unreachable.
		//
		// ACCEPTED DEVIATION, written down because this class otherwise restores by value: these
		// four enables are NOT saved, and the exit below leaves all four DISABLED rather than as
		// found. There is no honest way to save them — GLSM's glIsEnabled/glGetBoolean carry no
		// case for the client-array caps, and asking the driver is worse than useless, since they
		// are compatibility-only enums on a core profile: GL_INVALID_ENUM, destination untouched,
		// garbage saved. That is the same trap FramebufferPass's class javadoc documents for the
		// server-side enables. Disabling is the safe terminal state, because the fixed-function
		// client-array protocol is enable-what-you-use and any later drawer enables what it needs.
		// An earlier version of the exit block called itself "restore, in reverse" while restoring
		// none of this.
		//
		// GL_TEXTURE_COORD_ARRAY is not one flag under GLSM: it resolves through
		// clientActiveTextureUnit, which OpenGPU never sets and never reads, so this disable
		// clears whichever unit's bit the world left active. Disabling it is still right — a stale
		// texcoord pointer read against OUR bound VBO is undefined — but which unit it lands on is
		// inherited, and that is worth knowing before trusting a texcoord report from another mod.
		GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
		GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
		GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
		GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);

		// ================= LIGHTING: the gate ==================================================
		// Computed once. When nothing is authored the whole lighting block is SKIPPED and this
		// pass is byte-identical to the PRE-GROUP-D pass (5b1830c) — not to C1.3.1's: group B's
		// normal pointer and enable are outside these blocks and fire on the unlit path too. If
		// an unlit A-arm comes back CHANGED, suspect group B's shader-variant switch, not this
		// gate. — which is the contract the light verbs promise
		// ("it lights nothing until you give it a colour") and camera decision 4 applied again.
		//
		// The inherited GL_LIGHT0/GL_LIGHT1 enables stay harmless while unlit, because Angelica
		// reads them only inside its `if (lighting)` arm and FramebufferPass has already turned
		// GL_LIGHTING off for this pass.
		//
		// THIS GATE HIDES A REGRESSION FROM EVERY EXISTING FIELD ARM, and that is why group E
		// owes a new one. ingame/mesh3d.lua authors no lights, so it re-runs unlit and its
		// vertex-colour arm (A3) stays green whatever this block does. A3's PASS therefore stops
		// covering lit scenes the moment this ships, without changing colour. See the class
		// javadoc's group E note.
		java.util.List<SceneNode> lights = state.activeLights();
		double[] ambient = state.ambientLight();
		boolean lit = !lights.isEmpty() || ambient != null;

		boolean savedColorMaterial = false;
		int savedCmFace = 0, savedCmParam = 0;
		boolean savedNormalize = false;
		boolean[] savedLightEnabled = new boolean[LIGHT_SLOTS];
		float[][] savedLightAmbient = new float[LIGHT_SLOTS][];
		float[][] savedLightDiffuse = new float[LIGHT_SLOTS][];
		float[][] savedLightSpecular = new float[LIGHT_SLOTS][];
		float[][] savedLightPosition = new float[LIGHT_SLOTS][];
		float[] savedMatAmbient = null, savedMatDiffuse = null;
		float[] savedMatSpecular = null, savedMatEmission = null;
		float savedMatShininess = 0f;
		float[] savedLightModelAmbient = null;

		if (lit) {
			// ---- SAVE FIRST. Every read here is served from GLSM's own shadow; none reaches the
			// driver, and none may use glGetInteger for an ENABLE (that falls through to a core
			// driver as a compatibility-only enum: GL_INVALID_ENUM, destination untouched).
			savedColorMaterial = GL11.glIsEnabled(GL11.GL_COLOR_MATERIAL);
			// Modes, not enables — glGetInteger is correct here and ONLY here.
			savedCmFace = GL11.glGetInteger(GL11.GL_COLOR_MATERIAL_FACE);
			savedCmParam = GL11.glGetInteger(GL11.GL_COLOR_MATERIAL_PARAMETER);
			savedNormalize = GL11.glIsEnabled(GL11.GL_NORMALIZE);

			// THE FRONT MATERIAL, READ BEFORE ANY GL_COLOR_MATERIAL TRANSITION. Both the enable
			// and the disable bake the current glColor into the tracked material, so a save taken
			// after either one saves our own colour instead of the caller's.
			savedMatAmbient = getFrontMaterial(GL11.GL_AMBIENT);
			savedMatDiffuse = getFrontMaterial(GL11.GL_DIFFUSE);
			savedMatSpecular = getFrontMaterial(GL11.GL_SPECULAR);
			savedMatEmission = getFrontMaterial(GL11.GL_EMISSION);
			vec4.clear();
			GL11.glGetMaterial(GL11.GL_FRONT, GL11.GL_SHININESS, vec4);
			savedMatShininess = vec4.get(0);

			for (int i = 0; i < LIGHT_SLOTS; i++) {
				int id = GL11.GL_LIGHT0 + i;
				savedLightEnabled[i] = GL11.glIsEnabled(id);
				savedLightAmbient[i] = getLight(id, GL11.GL_AMBIENT);
				savedLightDiffuse[i] = getLight(id, GL11.GL_DIFFUSE);
				savedLightSpecular[i] = getLight(id, GL11.GL_SPECULAR);
				// POSITION MUST BE READ HERE, UNDER THE IDENTITY MODELVIEW loaded above. The
				// getter returns the value already multiplied by the modelview at SET time, not
				// the raw one, so a read taken under any other matrix cannot be written back.
				savedLightPosition[i] = getLight(id, GL11.GL_POSITION);
			}
			savedLightModelAmbient = getLightModelAmbient();

			// ---- THEN SET.
			// GL_NORMALIZE, not GL_RESCALE_NORMAL: see the class javadoc. Enabling this also
			// makes any inherited GL_RESCALE_NORMAL inert, since it is the else-if arm.
			GL11.glEnable(GL11.GL_NORMALIZE);

			// Restated rather than assumed: the world may have left FRONT/AMBIENT here, and an
			// unrecognised mode is mapped silently to AMBIENT_AND_DIFFUSE rather than reported.
			// This call does NOT bake.
			GL11.glColorMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_AMBIENT_AND_DIFFUSE);
			// This one DOES bake, harmlessly: the material was saved above and is overwritten
			// below. Without it, per-vertex colour is never read on the lit path and every mesh
			// renders one flat shade — the whole three-way blend gone, with no error.
			GL11.glEnable(GL11.GL_COLOR_MATERIAL);

			// Colour-material replaces AMBIENT and DIFFUSE only. Specular, emission and shininess
			// still come from the INHERITED material, so an inherited emission would add a flat
			// glow and an inherited specular a highlight nobody authored. Canonicalise all three.
			setMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_SPECULAR, 0f, 0f, 0f, 1f);
			setMaterial(GL11.GL_FRONT_AND_BACK, GL11.GL_EMISSION, 0f, 0f, 0f, 1f);
			// glMaterialf silently returns for any pname but GL_SHININESS; the vectors above must
			// use the buffer form.
			GL11.glMaterialf(GL11.GL_FRONT_AND_BACK, GL11.GL_SHININESS, 0f);

			// NEVER INHERITED. An inherited light-model ambient is nondeterministic in a modded
			// client, so an identical scene would render differently depending on what the world
			// did last frame. No ambient authored means (0,0,0,1) — the literal meaning of "no
			// ambient", not an invented grey.
			if (ambient != null) {
				setLightModelAmbient((float) ambient[0], (float) ambient[1], (float) ambient[2], 1f);
			} else {
				setLightModelAmbient(0f, 0f, 0f, 1f);
			}

			for (int i = 0; i < LIGHT_SLOTS; i++) {
				int id = GL11.GL_LIGHT0 + i;
				if (i < lights.size()) {
					double[] p = state.lightParams(lights.get(i));
					GL11.glEnable(id);
					setLight(id, GL11.GL_AMBIENT, 0f, 0f, 0f, 1f);
					setLight(id, GL11.GL_DIFFUSE, (float) p[1], (float) p[2], (float) p[3], 1f);
					setLight(id, GL11.GL_SPECULAR, 0f, 0f, 0f, 1f);
				} else {
					// DISABLED, not merely left alone. Enabling only the slots we filled leaves
					// the other one carrying the world's colour — or our own previous frame's.
					GL11.glDisable(id);
				}
			}

			// LAST in this block, so the shader variant flips only once everything it reads is
			// already set.
			GL11.glEnable(GL11.GL_LIGHTING);
		}

		float[] view = Transform3d.viewMatrix(camera, state);

		if (lit) {
			// ================= LIGHTING: light poses ===========================================
			// HOISTED OUT OF THE PER-NODE LOOP, and both halves of that matter.
			//
			// A light's GL_POSITION is multiplied by the modelview AT CALL TIME. Set it inside
			// the loop and every light would be re-posed by each mesh's model matrix, riding the
			// geometry. Set it back at the identity above and every light becomes a headlight
			// welded to the camera. It must be posed exactly once, under the VIEW alone.
			//
			// Loading the view here is required, not incidental: the modelview is still identity
			// at this point, so without this the poses would be read as eye-space.
			load(view);
			GL11.glLoadMatrix(matrix);
			for (int i = 0; i < lights.size() && i < LIGHT_SLOTS; i++) {
				SceneNode light = lights.get(i);
				double[] p = state.lightParams(light);
				int id = GL11.GL_LIGHT0 + i;
				if (p[0] == ServerScene.LIGHT_DIRECTIONAL) {
					// w = 0 is directional, and GL wants the vector TOWARD the light. The
					// derivation lives in Transform3d so this is the same expression the tests
					// exercise — see its javadoc for why a copy here would be worse than useless.
					double[] d = Transform3d.towardLight(light, state);
					setLight(id, GL11.GL_POSITION, (float) d[0], (float) d[1], (float) d[2], 0f);
				} else {
					// w = 1 is positional, at the light's own world position.
					double[] w = Transform3d.worldPosition(light, state);
					setLight(id, GL11.GL_POSITION, (float) w[0], (float) w[1], (float) w[2], 1f);
				}
			}
			// The view stays loaded; the loop re-establishes it per node anyway, and the pose
			// GLSM stored above is never recomputed, so one upload survives the loop's churn.
		}

		int drawn = 0;

		// EVERYTHING FROM HERE TO THE finally IS GUARDED, and the exit below is the finally's
		// body. Without this, a throw between the sets above and the restores below leaks GL
		// state that NOTHING puts back: V2ClientRuntime catches the Throwable and latches
		// prePassDisabled, so OpenGPU never runs again this session to repair it. What actually
		// persists is GL_NORMALIZE, the zeroed front-material specular/emission/shininess and
		// the colour-material FACE (ours FRONT_AND_BACK, vanilla's FRONT) — the world
		// re-establishes GL_LIGHTING, the light-model ambient and GL_LIGHT0/1 per batch, so this
		// is "state the world does not re-establish", not full-screen corruption.
		//
		// The guard also covers the client-state disables and buffer-binding restores, which
		// were already unprotected before lighting existed.
		//
		// No player-reachable trigger is known — lightParams returns null rather than throwing,
		// so the binder's array reads cannot NPE, and the realistic source is a driver-dependent
		// shader link failure inside Angelica. Defence in depth, priced as such.
		try {
		for (SceneNode node : state.nodes.values()) {
			// isDrawn, NOT the raw visible flag. The raw-flag rule is a carve-out scoped to
			// CAMERAS ONLY (a camera in a hidden rig stays eligible, so hiding a rig does not
			// blind it); every other consumer in this renderer asks the effective question, and
			// the 2D replay does. Copying the camera rule here made hiding a group hide its
			// canvas children while its meshes kept drawing — half a rig vanishing, silently.
			if (node.type != V2Wire.NODE_MESH_INSTANCE
					|| !Canvas2dRenderer.isDrawn(node, state)) {
				continue;
			}
			MeshGl mesh = meshes.get(Integer.valueOf(node.ref));
			if (mesh == null || !mesh.isDrawable()) {
				continue;
			}
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, mesh.vbo);
			GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, mesh.ebo);
			// Pointers are re-issued per mesh because the bound buffer changed; the offsets are
			// buffer-relative and come from the format's constants, never literals.
			GL11.glVertexPointer(MeshGl.POSITION_COMPONENTS, GL11.GL_FLOAT, MeshGl.stride(),
					MeshGl.POSITION_OFFSET);
			// glNormalPointer takes NO size argument — fixed-function normals are always three
			// components, so MeshGl.NORMAL_COMPONENTS has no call site here and none in MeshGl
			// either: the normal's own WIDTH does not affect where the normal BEGINS. It is
			// test-only, like UV_COMPONENTS, and exists so the layout can be summed against the
			// stride. Passing it here would not compile.
			GL11.glNormalPointer(GL11.GL_FLOAT, MeshGl.stride(), MeshGl.NORMAL_OFFSET);
			GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, MeshGl.stride(), MeshGl.COLOR_OFFSET);
			GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
			// The colour array must be ENABLED, not merely pointed at: a pointer without this
			// renders the whole mesh in the current flat glColor, with no error anywhere.
			GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
			// And the SAME rule for normals, with a worse failure. GLSM reads hasNormal from
			// CLIENT state, so a pointer without this enable lights every vertex from the current
			// normal uniform — a uniformly lit mesh that looks correct. See the class javadoc.
			GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);

			load(view);
			GL11.glLoadMatrix(matrix);
			load(Transform3d.modelMatrix(node, state));
			GL11.glMultMatrix(matrix);

			GL11.glDrawElements(GL11.GL_TRIANGLES, mesh.indexCount, MeshGl.indexType(), 0L);
			drawn++;
		}

		} finally {
		// --- exit. Buffer bindings and GL_DEPTH_TEST are restored BY VALUE; client state is left
		// disabled by the accepted deviation above, not restored. Disabling what we enabled is
		// required either way: leaving GL_COLOR_ARRAY enabled would make the 2D replay read a
		// stale colour pointer into a buffer we unbind two lines later.
		//
		// THREE, NOT TWO, as of group B. This block and the enables in the loop are ONE list
		// split across two places, and the split is the whole hazard: adding the normal enable
		// without adding it here leaks GL_NORMAL_ARRAY into the 2D replay, where the pointer it
		// carries aims into a VBO unbound on the next line. Disabled in REVERSE order of the
		// enables, so the two blocks read as mirrors and a missing line is visible by shape.
		GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
		GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
		GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
		GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, savedElementBuffer);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, savedArrayBuffer);
		GL11.glDisable(GL11.GL_DEPTH_TEST);

		// --- the 2D matrix pair, restored by REBUILDING it exactly as retarget does, not by
		// popping. See the class javadoc for why nothing here pushes.
		GL11.glMatrixMode(GL11.GL_PROJECTION);
		GL11.glLoadIdentity();
		GL11.glOrtho(0.0D, width, height, 0.0D, -1.0D, 1.0D);
		GL11.glMatrixMode(GL11.GL_MODELVIEW);
		GL11.glLoadIdentity();

		// ================= LIGHTING: the exit ==================================================
		// PLACED AFTER THE MATRIX REBUILD ON PURPOSE. The glLoadIdentity above is what makes the
		// light-position restore legal, and this block cannot move earlier for that reason alone.
		//
		// NOTHING IN THIS BLOCK MAY BE REORDERED. The sequence is the finding, not a style: every
		// alternative order either loses the caller's material or double-transforms a light.
		if (lit) {
			// GL_LIGHTING off FIRST, and this one is non-delegable even though FramebufferPass
			// saves the enable by value: the 2D replay runs BEFORE FramebufferPass.end(), and the
			// pass is per-FRAME, so leaving it on would replay the 2D layer lit and would light
			// every later scene in the same frame too.
			GL11.glDisable(GL11.GL_LIGHTING);

			for (int i = 0; i < LIGHT_SLOTS; i++) {
				int id = GL11.GL_LIGHT0 + i;
				setEnabled(id, savedLightEnabled[i]);
				setLight(id, GL11.GL_AMBIENT, savedLightAmbient[i]);
				setLight(id, GL11.GL_DIFFUSE, savedLightDiffuse[i]);
				setLight(id, GL11.GL_SPECULAR, savedLightSpecular[i]);
				// ONLY HERE. Writing back a position read from the getter is a no-op exactly when
				// the modelview is identity, because the value was already transformed once on
				// the way out. Under any other matrix this restore transforms it a second time,
				// and the light would drift a little further every frame.
				setLight(id, GL11.GL_POSITION, savedLightPosition[i]);
			}
			setLightModelAmbient(savedLightModelAmbient[0], savedLightModelAmbient[1],
					savedLightModelAmbient[2], savedLightModelAmbient[3]);
			setEnabled(GL11.GL_NORMALIZE, savedNormalize);

			// THE LAST THREE STEPS, IN THIS ORDER, AND THE REASON IS THE WHOLE TRAP:
			//
			// no GL_COLOR_MATERIAL transition may follow the material restore.
			//
			// This disable/restore BAKES the current colour into the tracked material — it fires
			// whether or not the enable value actually changes, because the bake sits outside the
			// short-circuit that skips redundant enables. So it must come BEFORE the material is
			// put back, or it destroys what we just restored. A restore placed above this line
			// looks obviously correct and silently does nothing.
			setEnabled(GL11.GL_COLOR_MATERIAL, savedColorMaterial);
			// Bake-free, so it is safe between the transition and the material.
			GL11.glColorMaterial(savedCmFace, savedCmParam);
			// And the front material LAST, with nothing after it that could bake again.
			setMaterial(GL11.GL_FRONT, GL11.GL_AMBIENT, savedMatAmbient);
			setMaterial(GL11.GL_FRONT, GL11.GL_DIFFUSE, savedMatDiffuse);
			setMaterial(GL11.GL_FRONT, GL11.GL_SPECULAR, savedMatSpecular);
			setMaterial(GL11.GL_FRONT, GL11.GL_EMISSION, savedMatEmission);
			GL11.glMaterialf(GL11.GL_FRONT, GL11.GL_SHININESS, savedMatShininess);

			// THE ASSERTION THIS RESTORE DEPENDS ON, checked rather than assumed: nothing between
			// here and control returning to the world toggles GL_COLOR_MATERIAL. Canvas2dRenderer
			// touches only GL_TEXTURE_2D and GL_BLEND; FramebufferPass.end() performs no
			// colour-material transition, and its glColor4f is not a bake site. Named here
			// because FramebufferPass.setEnabled(int, boolean) is a GENERIC helper — a future
			// line passing GL_COLOR_MATERIAL through it would defeat this restore from outside
			// the class that performed it, with nothing to catch it.
			//
			// BACK MATERIAL IS DELIBERATELY NOT RESTORED, and that is priced rather than missed.
			// The disable above dirties backMaterial's ambient and diffuse, but Angelica's
			// lighting stage reads only the FRONT material and no fixed-function file reads the
			// back one. Restoring it would be five more reads and five more writes per frame to
			// repair state that nothing consumes.
		}

		}

		return drawn;
	}

	/**
	 * Upload any mesh resource a DRAWABLE instance references and that is not current.
	 *
	 * Takes the mirror epoch because that is what actually keys the cache: the wire pins every
	 * mesh version at 1 forever, so an epoch change is the only signal that a cached id now means
	 * different geometry.
	 */
	void ensureUploaded(SceneState state, Map<Integer, MeshGl> meshes, int mirrorEpoch) {
		for (SceneNode node : state.nodes.values()) {
			// The SAME predicate as draw()'s, changed in the same edit: tightening one alone would
			// leave the two disagreeing about which meshes exist.
			if (node.type != V2Wire.NODE_MESH_INSTANCE
					|| !Canvas2dRenderer.isDrawn(node, state)) {
				continue;
			}
			ResourceInfo res = state.resources.get(Integer.valueOf(node.ref));
			if (res == null || res.type != V2Wire.RES_MESH || res.bytes == null) {
				continue;
			}
			MeshGl entry = meshes.get(Integer.valueOf(res.id));
			if (entry != null && entry.isCurrent(res, mirrorEpoch)) {
				continue;
			}
			if (entry == null) {
				entry = new MeshGl();
				meshes.put(Integer.valueOf(res.id), entry);
			}
			// A refused upload leaves the entry undrawable, so draw() skips it and this method
			// retries next frame. The retry is deliberate rather than an oversight: every
			// rejection returns BEFORE the first allocation and before any GL call, so it costs a
			// map lookup and three comparisons — unlike the FBO latch, whose retry would cost a
			// real glTexImage2D. A malformed body that a later delta repairs then recovers on its
			// own, which a latch would prevent.
			entry.upload(res, mirrorEpoch);
		}
	}

	private void load(float[] m) {
		matrix.clear();
		matrix.put(m);
		matrix.flip();
	}
}
