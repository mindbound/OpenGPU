package opengpu.v2.scene;

import opengpu.v2.protocol.V2Wire;

/**
 * Is the remembered display canvas still the display canvas? — the predicate behind
 * {@code ensureImplicitCanvas}, extracted so a JVM test can reach it.
 *
 * <h2>The defect this exists to close</h2>
 *
 * {@code ensureImplicitCanvas} validated the remembered RESOURCE properly — it exists and its type
 * is {@code RES_CANVAS} — and validated the remembered NODE with {@code nodes.containsKey(id)}, i.e.
 * <b>existence and nothing else</b>. Those two ids are persisted separately in TileEntity NBT, so a
 * restore can hand back a node id that exists and is <b>not the display node</b>: a sprite, a group,
 * or a canvas node pointing at some other canvas. The check passes, and the three guards keyed on
 * that id — {@code clearNodes}'s skip, {@code freeNode}'s refusal, {@code setNodeTransform}'s
 * refusal — then protect the wrong node while the real display node is freely transformable and
 * freeable. The one runtime cross-check compares RESOURCE ids, so it cannot see a node-level
 * mismatch.
 *
 * <b>Nothing downstream can detect it.</b> That is the same argument {@code setNodeTransform}'s own
 * refusal makes about the display node: the server and every mirror agree perfectly on the wrong
 * state, so no divergence check fires. It is exactly the failure class this codebase refuses by
 * name, and it was reachable through a save/restore with no animator anywhere near it.
 *
 * <h2>Why a class and not three more conditions inline</h2>
 *
 * {@code TileEntityGpu2} needs Minecraft to load, so no JVM test can construct one — the same
 * position {@code Canvas2dRenderer} was in before {@code NodeFold}. The predicate is a pure function
 * of {@link SceneState} plus two ints, so it moves here and gets vectors; the TileEntity keeps the
 * side effect (allocating a replacement) and hands this the decision.
 *
 * <b>Found while auditing ANIM-15(a)</b>, which wants an attach-time refusal keyed on the display
 * node and therefore had to establish what "the display node" IS. The answer is a private TileEntity
 * field, not a scene concept — {@code SceneState.displayCanvas()} returns a {@code ResourceInfo} and
 * there is no function anywhere that returns the display NODE.
 */
public final class DisplayNode {
	private DisplayNode() {}

	/**
	 * Whether the remembered (resource, node) pair still describes the display canvas.
	 *
	 * ALL THREE CONDITIONS, and the third is the one that was missing: the node must exist, be a
	 * canvas node, and point at <b>that</b> resource. Checking existence alone accepts any node that
	 * happens to hold the id.
	 *
	 * @param resourceId the remembered implicit canvas resource id, or 0 if none is remembered
	 * @param nodeId the remembered implicit canvas node id
	 */
	public static boolean stillValid(SceneState state, int resourceId, int nodeId) {
		if (state == null || resourceId == 0) {
			return false;
		}
		ResourceInfo res = state.resources.get(Integer.valueOf(resourceId));
		if (res == null || res.type != V2Wire.RES_CANVAS) {
			return false;
		}
		SceneNode node = state.nodes.get(Integer.valueOf(nodeId));
		return node != null && node.type == V2Wire.NODE_CANVAS && node.ref == resourceId;
	}

	/**
	 * Whether {@code nodeId} is the node {@link SceneState#displayCanvas} would pick — the CLIENT's
	 * idea of the display, which is a different mechanism from the server's remembered id.
	 *
	 * Stated so the divergence is nameable rather than latent. The client scans for the lowest-id
	 * node whose type is {@code NODE_CANVAS} and whose ref resolves to a canvas; the server compares
	 * against a field it persists. They agree by construction and nothing enforces it, and
	 * {@link #stillValid} is what keeps the server's half honest across a restore.
	 *
	 * Deterministic on every client: {@code SceneState.nodes} is a TreeMap, so the scan is ascending
	 * by id — a total order, not a hash order.
	 */
	public static int displayNodeId(SceneState state) {
		if (state == null) {
			return 0;
		}
		for (java.util.Map.Entry<Integer, SceneNode> entry : state.nodes.entrySet()) {
			SceneNode node = entry.getValue();
			if (node.type == V2Wire.NODE_CANVAS) {
				ResourceInfo res = state.resources.get(Integer.valueOf(node.ref));
				if (res != null && res.type == V2Wire.RES_CANVAS) {
					return entry.getKey().intValue();
				}
			}
		}
		return 0;
	}
}
