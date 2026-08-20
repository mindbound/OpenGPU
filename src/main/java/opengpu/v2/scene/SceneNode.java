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

	/**
	 * The OCSL program animating this node, or 0 for none.
	 *
	 * A FIELD HERE RATHER THAN A SIDE MAP, decided 2026-08-19, and the reason is lifecycle rather
	 * than taste. Five paths remove a node — {@code ServerScene.freeNode}, {@code clearNodes}
	 * (which bypasses the callback body by calling {@code freeNode} directly),
	 * {@link SceneMirror}'s {@code applySnapshot} and {@code hardReset}, and MirrorClient's
	 * evict/clear — and only two of them run a {@code NodeFree} delta. A side map would need
	 * dropping on all five, which is the leak {@link opengpu.v2.mc.client.render.NodeInterpolator}
	 * documents in its own words ("a long-lived scene leaks one entry per freed node forever").
	 * A field dies with the node on every path, so the drop site count is zero.
	 *
	 * MUTABLE, unlike {@link #ref} and {@link #parent}: ANIM-17 makes a second {@code setAnimator}
	 * an atomic REPLACE that succeeds, and detach is the same write with 0. There is no ordering
	 * invariant to protect here — unlike {@code parent}, whose immutability is what makes the
	 * graph acyclic by construction.
	 *
	 * A DANGLING VALUE IS LEGAL. Freeing a program that a node still names leaves this pointing at
	 * an id no longer in {@code SceneState.programs}; ANIM-17 rules that legal-and-dangling on
	 * this codebase's own criterion (the resource case, not the node case — both sides dangle
	 * identically and the persisted form agrees), and the node simply renders at its server value.
	 * So nothing here may assume the id resolves.
	 */
	public int animator = 0;

	/**
	 * WORLD TIME at which this node's current attachment became active, or 0 when unattached —
	 * ANIM-6's "replicated tick at which this attachment became active on this node".
	 *
	 * The register {@code anim.sinceAttach} is ANIM-6's
	 * {@code min(renderClock - the attach instant, CAP)} — stated there against "the replicated
	 * tick", and note that this field is NOT that tick: it is world time, so the host fill converts
	 * it first (below) rather than subtracting it from a render clock directly. Saturation is the
	 * load-bearing
	 * word: it is the one monotone-and-settling quantity a wrapped clock structurally cannot
	 * provide, which is what makes ease / one-shot / decay expressible at all. Without it every
	 * animator is exactly P-periodic and there is no such thing as "play once".
	 *
	 * WORLD TIME, not a session tick, and identical on both sides — the same reasoning as
	 * {@link SceneState#creationWorldTime}, plus one more that is specific to living on a node:
	 * if the server sent a session-domain value instead, a mirror's copy of this field would hold
	 * a different KIND of number than the server's, and {@link #contentEquals} — which is what
	 * certifies convergence — would be comparing unlike quantities while reporting agreement. The
	 * client converts with the snapshot's world-time anchor when it fills the register
	 * (Phase 3.3); the CAP is applied there too, and is deliberately not a wire concern.
	 *
	 * REWRITTEN ON EVERY ATTACH, including a replace: ANIM-17 makes a second setAnimator an
	 * atomic replace, and the new attachment is a NEW attachment — an easing program must restart
	 * when it is re-attached, not inherit the previous one's age. Detach clears it to 0 so a
	 * later re-attach cannot read a stale stamp.
	 */
	public long attachedWorldTime = 0L;

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
		n.animator = animator;
		n.attachedWorldTime = attachedWorldTime;
		return n;
	}

	public boolean contentEquals(SceneNode o) {
		// `animator` is included, and it has to be: this method is what certifies server/mirror
		// convergence, so leaving it out would let a mirror miss an attach and report agreement
		// while rendering the node unanimated — the same argument NodeCreate.equals makes for
		// `parent`, on a field that changes far more often.
		return id == o.id && type == o.type && ref == o.ref && parent == o.parent
				&& x == o.x && y == o.y && rot == o.rot && sx == o.sx && sy == o.sy
				&& z == o.z && visible == o.visible && tint == o.tint
				&& animator == o.animator && attachedWorldTime == o.attachedWorldTime;
	}
}
