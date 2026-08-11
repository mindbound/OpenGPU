package opengpu.v2.scene;

/**
 * One retained scene node. TRS + z-order + visibility + tint, plus a resource reference
 * (the canvas a Canvas node displays, the texture a Sprite samples; 0 = none).
 * Interpolation metadata (previous-state tracking) is a mirror/renderer concern layered on
 * later — this class is the shared, GL-free data model.
 */
public final class SceneNode {
	public final int id;
	public final byte type;
	public int ref;

	public double x = 0, y = 0;
	public double rot = 0;
	public double sx = 1, sy = 1;
	public int z = 0;
	public boolean visible = true;
	/** ARGB. */
	public int tint = 0xFFFFFFFF;

	/**
	 * Parent node for transform composition, or 0 for none. IMMUTABLE, like {@link #ref} and for
	 * a sharper reason: there is no PROP_PARENT and no re-parent delta, and there should not be.
	 *
	 * Ids are handed out monotonically ({@code ServerScene.createNode}), so refusing any parent
	 * whose id is not strictly BELOW the child's makes {@code parent < id} an invariant of the
	 * allocator. That is a total order, so the graph is acyclic BY CONSTRUCTION — no traversal,
	 * no visited-set, no cycle check anywhere. Allowing re-parenting would give that up and put
	 * a cycle check on the snapshot decode path, where a throw is
	 * {@code ScenePersistence.restoreOrFresh} deleting the scene. The invariant is worth more
	 * than the feature.
	 *
	 * Stage B is ONE nesting level (DESIGN-RENDERER-V2: "one nesting level in Stage B, documented
	 * as a known limit with a planned lift — turret-on-vehicle needs two"), so a parent must
	 * itself be unparented. That is what makes a two-cycle refusable by looking at one field of
	 * one node, rather than by walking a chain.
	 */
	public final int parent;

	public SceneNode(int id, byte type, int ref) {
		this(id, type, ref, 0);
	}

	public SceneNode(int id, byte type, int ref, int parent) {
		this.id = id;
		this.type = type;
		this.ref = ref;
		this.parent = parent;
	}

	public SceneNode copy() {
		SceneNode n = new SceneNode(id, type, ref, parent);
		n.x = x;
		n.y = y;
		n.rot = rot;
		n.sx = sx;
		n.sy = sy;
		n.z = z;
		n.visible = visible;
		n.tint = tint;
		return n;
	}

	public boolean contentEquals(SceneNode o) {
		return id == o.id && type == o.type && ref == o.ref && parent == o.parent
				&& x == o.x && y == o.y && rot == o.rot && sx == o.sx && sy == o.sy
				&& z == o.z && visible == o.visible && tint == o.tint;
	}
}
