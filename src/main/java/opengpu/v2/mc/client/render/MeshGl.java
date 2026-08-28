package opengpu.v2.mc.client.render;

import java.nio.ByteBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

import opengpu.v2.protocol.V2Wire;
import opengpu.v2.scene.ResourceInfo;

/**
 * One mesh resource's GL buffers: an interleaved vertex VBO and a u16 index EBO.
 *
 * <b>Real buffer objects, not client arrays, and this is forced rather than chosen.</b> Angelica
 * overwrites Forge's {@code createDisplay()} to build a <b>core, forward-compatible</b> context,
 * where client-side vertex and index pointers do not exist. (3.3 is the FLOOR it asks for, not
 * the result: the overwrite probes down from the platform maximum and stops at the first version
 * that succeeds, so the live context is normally 4.6 core. Nothing here depends on the version —
 * only on core + forward-compatible.) GLSM rescues client ATTRIBUTE pointers by re-uploading them
 * per draw, and its {@code glDrawElements(int,int,int,ByteBuffer)} special-cases only
 * {@code GL_QUADS} into the quad converter — a {@code GL_TRIANGLES} draw with a client index
 * buffer goes straight to the backend, on a context that cannot serve it. So the "simpler"
 * client-array route for a first flat mesh does not merely cost a per-draw re-interleave:
 * <b>it does not draw at all</b>, and would have surfaced in-game as a mesh that silently
 * renders nothing.
 *
 * That also matches the shape the wire format was designed for (V2Wire: "upload ONCE into the
 * client's own VBO"), so this is the intended path arriving on schedule rather than an
 * optimisation.
 *
 * <b>Uploaded once per (resource, epoch, version), then reused</b> — the same key
 * {@code TexEntry} uses, and for its reason: an epoch change reuses resource ids for different
 * content, so a version match alone would wrongly suppress the re-upload. For meshes the epoch
 * does ALL the work, because the wire pins every mesh version at 1 forever.
 */
final class MeshGl {

	/** {@code GL_ARRAY_BUFFER} holding the interleaved vertex records. */
	int vbo = -1;
	/** {@code GL_ELEMENT_ARRAY_BUFFER} holding u16 indices. */
	int ebo = -1;
	/** Indices to draw; 0 means "nothing to draw", which is legal and renders nothing. */
	int indexCount;
	/** Resource version this was built from. */
	int uploadedVersion = -1;
	/**
	 * Mirror epoch this was built under — and for meshes, the component that does all the work.
	 *
	 * A mesh's {@code version} is pinned at 1 forever by the wire's frozen convention, so a
	 * version-only guard reduces to "has this ever uploaded" and can never fire a re-upload. An
	 * epoch change restarts the id space, so the new epoch's mesh 1 can be entirely different
	 * geometry under a cached id — the hazard {@code TexEntry} keys on epoch to avoid.
	 */
	int uploadedEpoch = -1;

	/** Bytes of colour at the tail of a vertex record: u8 RGBA. */
	static final int COLOR_BYTES = 4;

	/**
	 * Byte offset of the colour attribute inside a vertex record.
	 *
	 * DERIVED from {@link V2Wire#MESH_VERTEX_STRIDE}, because colour is the record's last field.
	 * A renderer that writes the literal 32 keeps compiling and starts reading garbage the day
	 * the stride moves, with the codecs still round-tripping and only the picture wrong. An
	 * earlier version of this constant WAS that literal (spelled {@code 8 * 4}) beneath a javadoc
	 * claiming it was derived — the claim and the code disagreeing in the exact words of the
	 * failure they described.
	 *
	 * The remaining assumption is that colour stays LAST, which is the format's own statement:
	 * "pos f32 x3, normal f32 x3, uv f32 x2, color u8 x4 RGBA. Frozen."
	 */
	static final int COLOR_OFFSET = V2Wire.MESH_VERTEX_STRIDE - COLOR_BYTES;

	/** Bytes of position data at the head of a record: 3 floats. */
	static final int POSITION_OFFSET = 0;

	/**
	 * The record's float-attribute widths, naming the frozen format field by field:
	 * "pos f32 x3 @0, normal f32 x3 @12, uv f32 x2 @24, color u8 x4 @32".
	 *
	 * <b>TWO of these feed a derivation; TWO are test-only.</b> {@link #NORMAL_OFFSET} is built
	 * from {@code POSITION_COMPONENTS * FLOAT_BYTES} alone — an attribute's offset depends on
	 * what precedes it, never on its own width — so {@code NORMAL_COMPONENTS} and
	 * {@code UV_COMPONENTS} have no main-code call site at all.
	 *
	 * That is their job rather than an oversight: without them the record cannot be SUMMED, and a
	 * derivation nothing checks is decoration. MeshGlLayoutTest adds the four widths and asserts
	 * they account for the whole {@link V2Wire#MESH_VERTEX_STRIDE}, so a stride change these
	 * constants do not follow fails there instead of in the picture.
	 *
	 * Deriving {@code NORMAL_OFFSET} rather than writing the literal 12 avoids repeating, in this
	 * file, the exact defect {@link #COLOR_OFFSET}'s javadoc memorialises three declarations
	 * above — a hardcoded offset under a comment claiming derivation, which keeps compiling and
	 * starts reading garbage the day the layout moves.
	 */
	static final int FLOAT_BYTES = 4;
	static final int POSITION_COMPONENTS = 3;
	static final int NORMAL_COMPONENTS = 3;
	static final int UV_COMPONENTS = 2;

	/**
	 * Byte offset of the normal attribute: immediately after position.
	 *
	 * DERIVED, for {@link #COLOR_OFFSET}'s reason and by the opposite route — colour is derived
	 * backwards from the stride because it is LAST, and normal forwards from position because it
	 * is SECOND. Both assumptions are the format's own statement, and both are checked by the
	 * reconciliation test rather than trusted.
	 */
	static final int NORMAL_OFFSET = POSITION_OFFSET + POSITION_COMPONENTS * FLOAT_BYTES;

	/**
	 * Upload (or re-upload) a mesh resource's blobs. Returns false if the resource cannot be
	 * drawn, leaving this entry unusable rather than half-built.
	 *
	 * <b>Byte order: the blobs are copied VERBATIM, and that is the whole story.</b> The wire
	 * format is little-endian, the copy below is a bulk byte copy, and {@code glBufferData}
	 * uploads those bytes unaltered — so GL reads them in the HOST's order. On a big-endian host
	 * every float and index would decode wrongly. That is ACCEPTED, not solved: MC 1.7.10 on a
	 * big-endian JVM is outside this project's target, and the honest fix would be a byte-swapping
	 * decode, not a buffer flag. An earlier version of this paragraph claimed
	 * {@code ByteBuffer.order()} prevented it — {@code order()} affects only the multi-byte
	 * accessors, which this method never calls, so those calls were inert.
	 */
	boolean upload(ResourceInfo res, int mirrorEpoch) {
		if (res == null || res.bytes == null || res.type != V2Wire.RES_MESH) {
			return false;
		}
		int vertexCount = res.width;
		int vertexBytes = vertexCount * V2Wire.MESH_VERTEX_STRIDE;
		if (vertexCount <= 0 || vertexBytes > res.bytes.length) {
			return false;
		}
		int indexBytes = res.bytes.length - vertexBytes;
		if (indexBytes <= 0 || indexBytes % V2Wire.MESH_INDEX_BYTES != 0) {
			return false;
		}

		ByteBuffer vertices = BufferUtils.createByteBuffer(vertexBytes);
		vertices.put(res.bytes, 0, vertexBytes);
		vertices.flip();

		ByteBuffer indices = BufferUtils.createByteBuffer(indexBytes);
		indices.put(res.bytes, vertexBytes, indexBytes);
		indices.flip();

		if (vbo == -1) {
			vbo = GL15.glGenBuffers();
		}
		if (ebo == -1) {
			ebo = GL15.glGenBuffers();
		}
		// THIS METHOD RESTORES ITS OWN BINDINGS, and must. It runs OUTSIDE FramebufferPass's
		// window — SceneRenderer calls it before openPass() — and FramebufferPass saves no buffer
		// binding in any case: there is no GL_ARRAY_BUFFER_BINDING among its ~26 reads. An earlier
		// version of this comment asserted a "pass-wide save/restore" that does not exist, and so
		// left both targets bound to OpenGPU's buffers for the whole world render.
		//
		// That is worse than untidy. GL_ELEMENT_ARRAY_BUFFER is per-VAO state on a core profile,
		// so GLSM records our EBO into whichever VAO the world left bound; deleting it later
		// zeroes that VAO's binding. The fault then lands in someone else's renderer, on
		// mesh-churn frames only, which is the hardest possible shape to attribute.
		int prevArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
		int prevElement = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
		GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW);
		GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
		GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, indices, GL15.GL_STATIC_DRAW);
		GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, prevElement);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArray);

		indexCount = indexBytes / V2Wire.MESH_INDEX_BYTES;
		uploadedVersion = res.version;
		uploadedEpoch = mirrorEpoch;
		return true;
	}

	/** True when this entry holds buffers a draw can use. */
	boolean isDrawable() {
		return vbo != -1 && ebo != -1 && indexCount > 0;
	}

	/** True when this entry is current for the given resource under the given epoch. */
	boolean isCurrent(ResourceInfo res, int mirrorEpoch) {
		return uploadedEpoch == mirrorEpoch && uploadedVersion == res.version && isDrawable();
	}

	/**
	 * Free both buffers.
	 *
	 * Called from the scene's prune arm AND from dispose. Both are required: prune handles a mesh
	 * freed while its scene lives, dispose handles the scene going away with meshes still in it.
	 * Missing either leaks for the lifetime of whatever survives — and unlike a texture leak,
	 * nothing on screen changes, so only a VRAM graph would ever show it.
	 */
	void free() {
		if (vbo != -1) {
			GL15.glDeleteBuffers(vbo);
			vbo = -1;
		}
		if (ebo != -1) {
			GL15.glDeleteBuffers(ebo);
			ebo = -1;
		}
		indexCount = 0;
		uploadedVersion = -1;
		uploadedEpoch = -1;
	}

	/** Vertex stride, exposed so the draw site never writes the literal. */
	static int stride() {
		return V2Wire.MESH_VERTEX_STRIDE;
	}

	/** The index element type: u16, per the frozen format. */
	static int indexType() {
		return GL11.GL_UNSIGNED_SHORT;
	}
}
