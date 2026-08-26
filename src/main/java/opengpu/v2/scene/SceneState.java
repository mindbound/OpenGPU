package opengpu.v2.scene;

import java.util.Map;
import java.util.TreeMap;

/**
 * The shared scene data model: resources + nodes + id counters. Used verbatim on both sides
 * — the server's authoritative copy and every client mirror hold a SceneState; only the
 * wrappers differ ({@link ServerScene} stages deltas, {@link SceneMirror} applies batches).
 *
 * Id counters are part of the state and persist with it: recorded canvas command lists and
 * node refs reference these ids, so a reload must never reallocate them.
 *
 * TreeMaps keep iteration deterministic (state comparison, future NBT round-trips).
 */
public final class SceneState {
	public final TreeMap<Integer, ResourceInfo> resources = new TreeMap<Integer, ResourceInfo>();
	public final TreeMap<Integer, SceneNode> nodes = new TreeMap<Integer, SceneNode>();
	/**
	 * OCSL programs, in their OWN table rather than as a resource type — see ProgramInfo for the
	 * three reasons. Its own id space, so a program id and a texture id of the same value are
	 * different objects and neither can be passed where the other is meant.
	 */
	public final TreeMap<Integer, ProgramInfo> programs = new TreeMap<Integer, ProgramInfo>();
	public int nextResourceId = 1;
	public int nextNodeId = 1;
	public int nextProgramId = 1;

	/**
	 * The animator clock's epoch, in WORLD TIME ({@code World.getTotalWorldTime()}) — ANIM-13.
	 *
	 * World time, not the server's tick counter, and that choice is forced rather than stylistic.
	 * {@code V2ServerRuntime.tickCounter} resets to 0 at server stop, so a raw session tick stored
	 * here is meaningless one restart later. But the counter is ALSO what feeds
	 * {@code SceneBatch.serverTick} and hence the client's {@code ServerTimeline.renderNanos}, so
	 * this value cannot be differenced against {@code renderNanos} as-is either: world time is far
	 * larger than a fresh counter, and {@code OcslTime.time} would see a negative elapsed and pin
	 * {@code t = 0.0} permanently.
	 *
	 * SENT AS WORLD TIME ANYWAY, and converted by the receiver using {@link #worldTimeAnchor}.
	 * An earlier draft had the server pre-derive the session-domain age and send that; it was
	 * replaced when the same question arose for the per-node attach stamp, where a derived value
	 * would make a mirror's stored field a different KIND of number from the server's and leave
	 * {@link #contentEquals} comparing unlike quantities. One anchor converts every stamp, so all
	 * of them can stay the same quantity everywhere. See PLAN-STAGE-B 3.2.
	 *
	 * 0 means "not yet stamped": a scene restored from a pre-v7 save has no epoch on disk, and a
	 * fresh scene is stamped when its owner first ticks it.
	 */
	public long creationWorldTime = 0L;

	/**
	 * The server's world time at the instant this state was last stamped — the ANCHOR that makes
	 * every world-time value here convertible into the client's session-tick domain.
	 *
	 * A snapshot carries it as its LAST field — not in the header, though it is read together with
	 * the header's {@code serverTick}, and the two are stamped from the same tick. A receiver
	 * converts any world-time stamp with
	 * {@code sessionTick = serverTick + (stamp - worldTimeAnchor)}, then scales by TICK_NANOS to
	 * meet a nanosecond clock. That is what lets
	 * {@link #creationWorldTime} and every node's {@code attachedWorldTime} be stored as world
	 * time — identical on the server, on every mirror and on disk, so
	 * {@link #contentEquals} compares like with like — while
	 * {@code ServerTimeline.renderNanos}, which they are ultimately differenced against, is
	 * NANOSECONDS on the server's clock — derived from a smoothed offset, not a tick count — and
	 * is anchored to the session tick counter that resets at server stop. Hence both steps: convert
	 * the domain with the anchor, then scale ticks to nanos.
	 *
	 * NOT persisted as meaningful state: it is a property of the moment a payload was produced,
	 * not of the scene, so a restore reads whatever the save happened to hold and the next tick
	 * overwrites it. It rides the snapshot because that is where a receiver needs it.
	 */
	public long worldTimeAnchor = 0L;

	/** Structure only: no TEXTURE bytes cloned. For snapshots, which strip them anyway. */
	public SceneState copyStructure() {
		SceneState s = new SceneState();
		for (Map.Entry<Integer, ResourceInfo> e : resources.entrySet()) {
			ResourceInfo res = e.getValue().copyStructure();
			// Canvases ARE their content, so they must still be deep-copied.
			res.canvas = e.getValue().canvas == null ? null : e.getValue().canvas.copy();
			// Mesh blobs ride the STRUCTURE (v10) — the programs' inline reasoning below, not
			// the textures' out-of-band one: bounded, immutable, and a snapshot that dropped
			// them would restore data-less meshes on every resync and every save.
			if (res.type == opengpu.v2.protocol.V2Wire.RES_MESH && e.getValue().bytes != null)
				res.bytes = e.getValue().bytes.clone();
			s.resources.put(e.getKey(), res);
		}
		for (Map.Entry<Integer, SceneNode> e : nodes.entrySet()) {
			s.nodes.put(e.getKey(), e.getValue().copy());
		}
		// Programs ride the STRUCTURE, like canvas command lists and unlike texture bytes: they
		// are small, immutable and inline in the snapshot (DESIGN, "Program storage"), so a
		// structure copy that dropped them would produce a snapshot a mirror cannot run.
		s.programs.putAll(programs);
		s.nextResourceId = nextResourceId;
		s.nextNodeId = nextNodeId;
		s.nextProgramId = nextProgramId;
		s.creationWorldTime = creationWorldTime;
		s.worldTimeAnchor = worldTimeAnchor;
		return s;
	}

	/**
	 * The canvas that defines this scene's LOGICAL SIZE: the lowest-id canvas node's canvas.
	 *
	 * This is an INVARIANT, not an implementation detail, and it is stated here because it
	 * used to exist only as a comment on the client's private size lookup. The display
	 * canvas is created first (TileEntityGpu2.ensureImplicitCanvas) and node ids are handed
	 * out monotonically (ServerScene.createNode), so every later canvas — the offscreen
	 * canvases DESIGN-RENDERER-V2 specifies — sorts after it and cannot displace it.
	 *
	 * Anything that gives the display canvas a NEW node id breaks this silently: both sides
	 * would agree on the state, so there is no sequence gap, no apply failure and no log
	 * line — just an FBO allocated at some offscreen canvas's size. That is the constraint
	 * on how a resolution change may be implemented: keep the display node's id, or replace
	 * this rule with an explicit marker first.
	 *
	 * And a node's {@code ref} is IMMUTABLE — there is no NodeSetRef delta — so "keep the
	 * node, repoint it at a new canvas" is not available without a protocol change. The one
	 * id-preserving path the current delta set allows is recreating the canvas RESOURCE
	 * under its existing id, which leaves the node untouched. See DisplayCanvasTest.
	 *
	 * Returns null when no canvas node exists yet.
	 */
	public ResourceInfo displayCanvas() {
		for (SceneNode node : nodes.values()) {
			if (node.type == opengpu.v2.protocol.V2Wire.NODE_CANVAS) {
				ResourceInfo res = resources.get(node.ref);
				if (res != null && res.type == opengpu.v2.protocol.V2Wire.RES_CANVAS) {
					return res;
				}
			}
		}
		return null;
	}

	/**
	 * Whether any node here has an animator attached — ANIM-19's re-render term.
	 *
	 * SCANNED, NOT CACHED, and that is a deliberate trade rather than the lazy option. A cached
	 * flag would be a second source of truth for something the node table already states, and it
	 * would need invalidating on every path that changes a node — the same five-path obligation
	 * that putting {@code animator} ON the node was chosen to avoid. One stale flag freezes an
	 * animated scene or re-renders a static one forever, and nothing would detect either.
	 *
	 * The cost is bounded and lands where it does not matter. It returns on the FIRST attached
	 * node, so an animated scene is O(1); the full walk happens only for a scene with no animator
	 * at all, which is the case the caller has already established is otherwise settled, and a
	 * typical scene holds a handful of nodes against a {@code MAX_NODES} of 4096. If a scene ever
	 * carries thousands of nodes AND no animator AND renders nothing, this walk is what to measure
	 * first — but measure it before caching it, because the cache is the expensive kind of fix.
	 */
	public boolean hasAttachedAnimator() {
		for (SceneNode node : nodes.values()) {
			if (node.animator != 0) {
				return true;
			}
		}
		return false;
	}

	public SceneState copy() {
		SceneState s = new SceneState();
		for (Map.Entry<Integer, ResourceInfo> e : resources.entrySet()) {
			s.resources.put(e.getKey(), e.getValue().copy());
		}
		for (Map.Entry<Integer, SceneNode> e : nodes.entrySet()) {
			s.nodes.put(e.getKey(), e.getValue().copy());
		}
		// ProgramInfo.copy() returns this: immutable, so sharing is the deep copy.
		s.programs.putAll(programs);
		s.nextResourceId = nextResourceId;
		s.nextNodeId = nextNodeId;
		s.nextProgramId = nextProgramId;
		s.creationWorldTime = creationWorldTime;
		s.worldTimeAnchor = worldTimeAnchor;
		return s;
	}

	/**
	 * Deep content comparison of resources and nodes (counters excluded — mirrors never
	 * allocate). Used by the convergence tests: server state and mirror state must agree
	 * after any batch sequence.
	 */
	public boolean contentEquals(SceneState other) {
		if (resources.size() != other.resources.size() || nodes.size() != other.nodes.size()
				|| programs.size() != other.programs.size())
			return false;
		for (Map.Entry<Integer, ResourceInfo> e : resources.entrySet()) {
			ResourceInfo o = other.resources.get(e.getKey());
			if (o == null || !e.getValue().contentEquals(o))
				return false;
		}
		for (Map.Entry<Integer, SceneNode> e : nodes.entrySet()) {
			SceneNode o = other.nodes.get(e.getKey());
			if (o == null || !e.getValue().contentEquals(o))
				return false;
		}
		// Included because convergence is what this method certifies: a mirror missing a program
		// the server holds renders a node with no animator and reports agreement.
		for (Map.Entry<Integer, ProgramInfo> e : programs.entrySet()) {
			ProgramInfo o = other.programs.get(e.getKey());
			if (o == null || !e.getValue().contentEquals(o))
				return false;
		}
		return true;
	}
}
