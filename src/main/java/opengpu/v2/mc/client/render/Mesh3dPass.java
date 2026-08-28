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
 * Not fixed here because the fix is not local. Routing the 3D path through the interpolator means
 * widening its field set to carry tz and a quaternion, which is slerp work the plan assigns to a
 * later C1.3 increment, and doing it badly (interpolating x/y but stepping z and rotation) would
 * look worse than stepping everything. Recorded so the next increment knows it is completing this
 * rather than discovering it.
 *
 * <b>No normals, deliberately.</b> The wire carries them at offset 12 and they are the right
 * bytes, but there is no lighting in this increment. Enabling {@code GL_NORMAL_ARRAY} would flip
 * a bit in Angelica's fixed-function {@code VertexKey} and select a different generated shader
 * variant for a pass with no lights — pre-spending the exact bit C1.3.2's normals arm exists to
 * test. A later increment ADDS normals; it is not fixing an omission.
 */
final class Mesh3dPass {

	/** Reused per draw; {@code glLoadMatrix} reads 16 floats from position. */
	private final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);

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

		float[] view = Transform3d.viewMatrix(camera, state);
		int drawn = 0;

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
			GL11.glVertexPointer(3, GL11.GL_FLOAT, MeshGl.stride(), MeshGl.POSITION_OFFSET);
			GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, MeshGl.stride(), MeshGl.COLOR_OFFSET);
			GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
			// The colour array must be ENABLED, not merely pointed at: a pointer without this
			// renders the whole mesh in the current flat glColor, with no error anywhere.
			GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);

			load(view);
			GL11.glLoadMatrix(matrix);
			load(Transform3d.modelMatrix(node, state));
			GL11.glMultMatrix(matrix);

			GL11.glDrawElements(GL11.GL_TRIANGLES, mesh.indexCount, MeshGl.indexType(), 0L);
			drawn++;
		}

		// --- exit. Buffer bindings and GL_DEPTH_TEST are restored BY VALUE; client state is left
		// disabled by the accepted deviation above, not restored. Disabling the two we enabled is
		// required either way: leaving GL_COLOR_ARRAY enabled would make the 2D replay read a
		// stale colour pointer into a buffer we unbind two lines later.
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
