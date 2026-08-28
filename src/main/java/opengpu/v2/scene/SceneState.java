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
	 * THE active camera, or null when this scene has none — the 3D layer's selection rule.
	 *
	 * Lowest-id NODE_CAMERA whose OWN visible flag is true. {@code nodes} is a TreeMap, so
	 * ascending iteration makes "lowest-id" free and returning on the first match makes a scene
	 * with a camera O(1).
	 *
	 * <b>{@code node.visible} ALONE — deliberately NOT {@code Canvas2dRenderer.isDrawn}, and
	 * this is the one substitution that silently reverts a settled decision.</b> {@code isDrawn}
	 * is the EFFECTIVE-visibility predicate: it also tests the parent. The frozen rule says the
	 * opposite, in these words (PLAN-STAGE-C.md, camera decision 2): "the flag consulted is the
	 * camera's OWN, never the effective ancestor visibility (a camera in a hidden rig group
	 * stays eligible — selection is a flat scan, no tree walk); PARENTED cameras are eligible
	 * (the scan does not test parent — camera rigs are the point)". The two predicates agree on
	 * every scene EXCEPT a camera parented to a hidden group, which is precisely the case the
	 * decision exists to permit — so a reviewer "unifying" them would read as obviously right
	 * and break exactly one thing. The falsifier is a test:
	 * {@code aCameraParentedToAHiddenGroupIsStillSelected}.
	 *
	 * SCANNED PER CALL, NOT CACHED, for the reason its sibling below gives at length: a cached
	 * id would be a second source of truth needing invalidation on every path that creates,
	 * frees or hides a node. Re-scanning is also what makes silent promotion fall out — freeing
	 * or hiding the active camera promotes the next-lowest visible one with no extra code — and
	 * a cache would have to reimplement that.
	 */
	public SceneNode activeCamera() {
		for (SceneNode node : nodes.values()) {
			if (node.type == opengpu.v2.protocol.V2Wire.NODE_CAMERA && node.visible) {
				return node;
			}
		}
		return null;
	}

	/**
	 * A camera's projection, or null when it has none the renderer can use.
	 *
	 * <b>There are no renderer projection defaults</b> (camera decision 4): a null here means
	 * the 3D layer is SKIPPED, exactly as no visible camera does — a contract the renderer has
	 * honoured since C1.3.1 group F, whose 3D decision calls this before the pass opens and
	 * skips the layer on null. Returning invented values
	 * would render a believable picture from numbers nobody supplied — see CASEBOOK D11.
	 *
	 * Null covers three cases the renderer must treat identically, because the author's remedy
	 * is the same in all three (call setPerspective/setOrtho):
	 * <ol>
	 * <li>No {@code __proj} entry — the camera was created and never configured.</li>
	 * <li>A malformed entry (not 4 components).</li>
	 * <li>A well-formed entry outside the validation band.</li>
	 * </ol>
	 * The third is not paranoia and is not covered elsewhere. {@link ServerScene#setProjection}
	 * validates the band, but that is a SERVER-SIDE ADMISSION GATE; a mirror payload reaches
	 * this side through DeltaApplier/SnapshotCodec, which check name legality and value COUNT
	 * but never the band. A {@code far <= near} entry that arrived any other way makes the
	 * frustum degenerate, so the renderer re-checks rather than trusting a gate it does not run.
	 *
	 * Reads {@code __proj} DIRECTLY and must not be routed through any shared
	 * "read this node's uniforms" helper that skips {@code __} names. Reserved-ness runs in both
	 * directions on purpose: the author-facing binder must skip these names, while the renderer
	 * is a HOST consumer and this entry is addressed to it. One shared helper makes __proj
	 * unreadable here, and the symptom would not be an error — it would be every camera looking
	 * unconfigured forever, i.e. a 3D layer that silently ignores setPerspective.
	 *
	 * The returned array is the node's own storage ({@code DeltaApplier} stores a clone), so
	 * callers must read it and never mutate it in place.
	 */
	public double[] cameraProjection(SceneNode camera) {
		if (camera == null) {
			return null;
		}
		double[] p = camera.uniforms.get(ServerScene.PROJECTION_UNIFORM);
		if (p == null || p.length != 4) {
			return null;
		}
		double mode = p[0], a = p[1], near = p[2], far = p[3];
		if (near <= 0 || far <= near) {
			return null;
		}
		if (mode == ServerScene.PROJECTION_PERSPECTIVE) {
			return (a > 0 && a < 180) ? p : null;
		}
		if (mode == ServerScene.PROJECTION_ORTHO) {
			return a > 0 ? p : null;
		}
		return null;
	}

	/**
	 * Whether this node is visible AND not hidden by its parent — EFFECTIVE visibility.
	 *
	 * One lookup, never a loop, because nesting is capped at one level (see
	 * {@link Transform3d#parentOf}). Lives here rather than in the renderer because two
	 * consumers in different packages now need it and a second copy is how the two drift:
	 * {@code Canvas2dRenderer.isDrawn} delegates to this, and the 3D layer's light selection
	 * calls it directly.
	 *
	 * <b>Contrast {@link #activeCamera()}, which deliberately does NOT use this.</b> The two
	 * predicates disagree on exactly one scene — a node parented to a hidden group — and each
	 * rule is correct for its own consumer. Read both javadocs before unifying them.
	 */
	public boolean isEffectivelyVisible(SceneNode node) {
		if (node == null || !node.visible) {
			return false;
		}
		SceneNode parent = Transform3d.parentOf(node, this);
		return parent == null || parent.visible;
	}

	/**
	 * The renderer's hardware light ceiling: at most this many NON-AMBIENT lights are selected.
	 *
	 * Two, because Angelica's fixed-function emulation packs exactly two
	 * ({@code VertexKey.FFP_LIGHT_COUNT = 2}); {@code GL_LIGHT2..GL_LIGHT7} are accepted,
	 * tracked, and then DROPPED by the pack loop, warning once per index per JVM. So the
	 * ceiling is enforced HERE, where the drop is visible and deterministic, rather than being
	 * discovered as missing light in the picture.
	 *
	 * Deliberately NOT a server admission gate. The server accepts any number of light nodes —
	 * gating creation on a client-side rendering limit would make a scene un-authorable on the
	 * machine that can render it least, and the caps doctrine keeps renderer limits out of the
	 * server's ledger. What the ceiling costs is that the third light does not LIGHT, not that
	 * it cannot EXIST.
	 */
	public static final int MAX_ACTIVE_LIGHTS = 2;

	/**
	 * A light node's validated {@code __light} entry, or null when it does not have a usable one.
	 *
	 * Re-validated on the READ side for the same reason {@link #cameraProjection} is:
	 * {@link ServerScene#setLight} validates on admission, but a mirror payload arrives through
	 * {@code DeltaApplier}/{@code SnapshotCodec}, which check name legality and value COUNT and
	 * never the band. A light that reached this side any other way must not be trusted.
	 *
	 * <i>That sentence was FALSE when this method was written and is true now, which is worth a
	 * line rather than a silent edit.</i> Group A shipped the read side alone, and its first
	 * draft claimed this admission gate already existed — copied from {@code cameraProjection},
	 * where it does. A panel caught it; the correction said plainly that this was the only
	 * validation anywhere. Group C then built {@link ServerScene#setLight}, so the original
	 * sentence became accurate and is restored. The rule that survives both edits: the claim
	 * moves in the commit that moves the code.
	 *
	 * Null is returned — never a substituted default — for an absent entry, a malformed one, an
	 * unrecognised kind, or a negative/non-finite colour. A negative component is refused rather
	 * than clamped because it would SUBTRACT light, and a light that darkens is a defect that
	 * reads as an artist's choice. The whole entry is refused rather than the bad component, so
	 * that a light is either fully authored or absent; this matches {@code __proj} exactly, where
	 * a camera with a malformed projection is treated as unconfigured rather than partly usable.
	 *
	 * The returned array is the node's own storage, so callers must read it and never mutate it.
	 */
	public double[] lightParams(SceneNode light) {
		if (light == null || light.type != opengpu.v2.protocol.V2Wire.NODE_LIGHT) {
			return null;
		}
		double[] p = light.uniforms.get(ServerScene.LIGHT_UNIFORM);
		if (p == null || p.length != 4) {
			return null;
		}
		double kind = p[0];
		if (kind != ServerScene.LIGHT_DIRECTIONAL && kind != ServerScene.LIGHT_POINT
				&& kind != ServerScene.LIGHT_AMBIENT) {
			return null;
		}
		for (int i = 1; i < 4; i++) {
			if (Double.isNaN(p[i]) || Double.isInfinite(p[i]) || p[i] < 0) {
				return null;
			}
		}
		return p;
	}

	/**
	 * The lights that will actually light this frame: effectively-visible NODE_LIGHTs carrying a
	 * valid non-ambient {@code __light}, lowest id first, at most {@link #MAX_ACTIVE_LIGHTS}.
	 *
	 * <b>EFFECTIVE visibility, deliberately unlike {@link #activeCamera()}.</b> The camera rule
	 * consults the node's OWN flag so that a camera riding a hidden rig keeps working — a camera
	 * is a VIEWPOINT, not scene content. A light IS content: hiding a rig that carries a lamp
	 * must turn the lamp off, exactly as it hides the rig's canvases and meshes. C1.3.1 already
	 * paid for getting this backwards once, in the other direction — copying the camera rule to
	 * MESHES made hiding a group hide its canvas children while its meshes kept drawing, half a
	 * rig vanishing silently. Lights follow meshes, not cameras.
	 *
	 * Ambient lights are excluded here and summed by {@link #ambientLight()} instead, because
	 * they cost no hardware slot; a scene may therefore have ambient plus two directionals.
	 * Excluding them from this list is what stops an ambient light from silently consuming the
	 * budget the ceiling exists to ration.
	 *
	 * Scanned per call rather than cached, for the reasons {@link #activeCamera()} and
	 * {@link #hasAnimator()} both give: a cache would be a second source of truth needing
	 * invalidation on every path that creates, frees, hides or re-parents a node, and silent
	 * promotion (hide a light, the next one takes its slot) falls out of re-scanning for free.
	 */
	public java.util.List<SceneNode> activeLights() {
		java.util.List<SceneNode> out = new java.util.ArrayList<SceneNode>(MAX_ACTIVE_LIGHTS);
		for (SceneNode node : nodes.values()) {
			if (node.type != opengpu.v2.protocol.V2Wire.NODE_LIGHT) {
				continue;
			}
			double[] p = lightParams(node);
			if (p == null || p[0] == ServerScene.LIGHT_AMBIENT || !isEffectivelyVisible(node)) {
				continue;
			}
			out.add(node);
			if (out.size() == MAX_ACTIVE_LIGHTS) {
				break;
			}
		}
		return out;
	}

	/**
	 * The scene's ambient term: the component-wise SUM over every effectively-visible ambient
	 * light, or null when there is none.
	 *
	 * Summed rather than last-wins or first-wins because ambient light is physically additive and
	 * because any other rule makes the result depend on node id, which authors do not control
	 * and cannot see. Unclamped on purpose — the components are already unclamped as authored
	 * (that is how intensity is expressed), and clamping here would silently cap a scene that
	 * deliberately over-drives its ambient.
	 *
	 * Null, never a substituted default. Inventing a "reasonable" grey HERE is exactly the
	 * invented-default defect CASEBOOK D11 records: this method reports what the author wrote,
	 * and nothing else.
	 *
	 * <b>What the RENDERER does with that null is a separate decision, and it is NOT "leave
	 * whatever is already there".</b> This javadoc said so for one increment and it was wrong —
	 * an inherited {@code GL_LIGHT_MODEL_AMBIENT} is nondeterministic in a modded client (the
	 * GL default is 0.2, but vanilla raises it while a container GUI is open), so an identical
	 * scene would render differently depending on what the world did last frame. Read as a
	 * renderer instruction, "leave what is there" produces precisely the inherited-state defect
	 * this increment exists to close.
	 *
	 * {@code Mesh3dPass} therefore writes {@code (0,0,0,1)} on null — the literal meaning of
	 * "no ambient authored", which is a different thing from an invented value. The distinction
	 * that keeps both halves honest: the MODEL must not invent, and the RENDERER must not
	 * inherit.
	 */
	public double[] ambientLight() {
		double[] sum = null;
		for (SceneNode node : nodes.values()) {
			if (node.type != opengpu.v2.protocol.V2Wire.NODE_LIGHT) {
				continue;
			}
			double[] p = lightParams(node);
			if (p == null || p[0] != ServerScene.LIGHT_AMBIENT || !isEffectivelyVisible(node)) {
				continue;
			}
			if (sum == null) {
				sum = new double[] { 0, 0, 0 };
			}
			sum[0] += p[1];
			sum[1] += p[2];
			sum[2] += p[3];
		}
		return sum;
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
