package opengpu.v2.scene;

import opengpu.v2.protocol.Delta;
import opengpu.v2.protocol.V2Wire;

/**
 * The single delta-application code path. ServerScene applies its own staged deltas through
 * this class and mirrors apply decoded deltas through it — convergence by construction:
 * there is no second implementation to drift.
 *
 * Throws IllegalStateException on references to unknown ids or type mismatches; mirrors
 * treat any throw as a resync trigger, the server treats it as a validation bug (its public
 * API validates before staging).
 */
public final class DeltaApplier {
	private DeltaApplier() {}

	public static void apply(SceneState state, Delta delta) {
		if (delta instanceof Delta.NodeCreate) {
			Delta.NodeCreate d = (Delta.NodeCreate) delta;
			if (!V2Wire.isKnownNodeType(d.nodeType))
				throw new IllegalStateException("Unknown node type " + d.nodeType);
			if (state.nodes.containsKey(d.nodeId))
				throw new IllegalStateException("Node " + d.nodeId + " already exists");
			if (d.ref != 0 && !state.resources.containsKey(d.ref))
				throw new IllegalStateException("Node " + d.nodeId + " references unknown resource " + d.ref);
			// Parenting rules, enforced HERE because both sides run this — the server through
			// applyAndStage and the mirror through applyBatch — so server and mirror accept
			// exactly the same set. Throwing is right on this path: a bad delta flags needsResync
			// and a snapshot repairs it. (The PERSISTED path cannot throw; see
			// SnapshotCodec.sanitizeParent for why it sanitises the same three conditions.)
			if (d.parent != 0) {
				if (d.parent >= d.nodeId)
					throw new IllegalStateException("Node " + d.nodeId + " parent " + d.parent
							+ " must be a lower id; ids are monotonic and that is what keeps the graph acyclic");
				SceneNode p = state.nodes.get(d.parent);
				if (p == null)
					throw new IllegalStateException("Node " + d.nodeId + " parented to unknown node " + d.parent);
				if (p.parent != 0)
					throw new IllegalStateException("Node " + d.nodeId + " would nest two levels deep;"
							+ " node " + d.parent + " is already a child of " + p.parent);
			}
			state.nodes.put(d.nodeId, new SceneNode(d.nodeId, d.nodeType, d.ref, d.parent));
		} else if (delta instanceof Delta.NodeFree) {
			Delta.NodeFree d = (Delta.NodeFree) delta;
			// A freed parent would leave its children holding an id that no longer resolves, and
			// that is not merely untidy: SnapshotCodec.sanitizeParent resets an unresolvable
			// parent to 0, so live state would say "child of 7" while everything restored from a
			// save said "unparented" — a permanent, silent divergence between a running scene and
			// its own reload. Refusing keeps `parent` resolvable for as long as it exists.
			//
			// Refusal rather than a cascading free is the conservative half of the choice: adding
			// a cascade later only accepts calls that error today, while removing one would break
			// programs that had come to rely on it.
			if (!state.nodes.containsKey(d.nodeId))
				throw new IllegalStateException("Freeing unknown node " + d.nodeId);
			// Only the TAIL can hold children: a parent id is refused unless strictly lower than
			// its child's, so nothing at or below this id can be parented to it. That keeps the
			// common case cheap — freeing in descending order, as clearNodes does, leaves an
			// empty tail and costs O(1) rather than a full scan per free, which on a 4096-node
			// clear was the difference between one pass and ~8M comparisons under the scene lock.
			for (SceneNode child : state.nodes.tailMap(Integer.valueOf(d.nodeId), false).values()) {
				if (child.parent == d.nodeId)
					throw new IllegalStateException("Node " + d.nodeId + " still has child "
							+ child.id + "; free the children first, or free in descending id order");
			}
			state.nodes.remove(d.nodeId);
		} else if (delta instanceof Delta.NodeProps) {
			Delta.NodeProps d = (Delta.NodeProps) delta;
			SceneNode node = state.nodes.get(d.nodeId);
			if (node == null)
				throw new IllegalStateException("Props for unknown node " + d.nodeId);
			int vi = 0;
			if ((d.mask & V2Wire.PROP_X) != 0)
				node.x = d.values[vi++];
			if ((d.mask & V2Wire.PROP_Y) != 0)
				node.y = d.values[vi++];
			if ((d.mask & V2Wire.PROP_ROT) != 0)
				node.rot = d.values[vi++];
			if ((d.mask & V2Wire.PROP_SX) != 0)
				node.sx = d.values[vi++];
			if ((d.mask & V2Wire.PROP_SY) != 0)
				node.sy = d.values[vi++];
			if ((d.mask & V2Wire.PROP_Z) != 0)
				node.z = (int) d.values[vi++];
			if ((d.mask & V2Wire.PROP_VISIBLE) != 0)
				node.visible = d.values[vi++] != 0;
			if ((d.mask & V2Wire.PROP_TINT) != 0)
				node.tint = (int) (long) (double) d.values[vi++];
			// PROP_TELEPORT is consumed but NOT stored: it qualifies this transition, not the
			// node. Reading it keeps vi aligned; SceneMirror is what routes it to the
			// client's interpolator, and the server simply ignores it.
			if ((d.mask & V2Wire.PROP_TELEPORT) != 0)
				vi++;
		} else if (delta instanceof Delta.ResourceCreate) {
			Delta.ResourceCreate d = (Delta.ResourceCreate) delta;
			if (!V2Wire.isKnownResType(d.resType))
				throw new IllegalStateException("Unknown resource type " + d.resType);
			if (d.width <= 0 || d.height <= 0
					|| d.width > V2Wire.MAX_TEXTURE_DIM || d.height > V2Wire.MAX_TEXTURE_DIM)
				throw new IllegalStateException("Resource " + d.resId + " has invalid dimensions "
						+ d.width + "x" + d.height);
			if (d.resType == V2Wire.RES_TEXTURE
					&& d.sizeBytes != (long) d.width * d.height * 4L)
				throw new IllegalStateException("Resource " + d.resId + " size does not match dimensions");
			if (state.resources.containsKey(d.resId))
				throw new IllegalStateException("Resource " + d.resId + " already exists");
			ResourceInfo res = new ResourceInfo(d.resId, d.resType, d.width, d.height, d.sizeBytes);
			// A create is always version 1; the wire's hash field is that version's content
			// hash, which doubles as the client's content-addressed cache key.
			res.version = 0; // no bytes held yet on a mirror; the server sets 1 when it attaches
			res.latestVersion = 1;
			res.knownHash = d.hash;
			res.knownHashVersion = 1;
			if (d.resType == V2Wire.RES_CANVAS) {
				res.canvas = new SceneCanvas(d.width, d.height, d.commandCap);
			}
			state.resources.put(d.resId, res);
		} else if (delta instanceof Delta.TextureWrite) {
			Delta.TextureWrite d = (Delta.TextureWrite) delta;
			ResourceInfo res = state.resources.get(d.resId);
			if (res == null)
				throw new IllegalStateException("Texture write references unknown resource " + d.resId);
			if (res.type != V2Wire.RES_TEXTURE)
				throw new IllegalStateException("Texture write targets non-texture resource " + d.resId);
			// The rect must lie wholly inside the texture. The decoder cannot check this (it
			// holds no scene state), so this is the only place it can be enforced — and a
			// violation on a mirror is a divergence signal, i.e. a resync trigger.
			// LONG arithmetic: x and w are independently bounded but their sum is not, and
			// OC's checkInteger SATURATES out-of-range Lua numbers to Integer.MAX_VALUE
			// rather than rejecting them — so an int sum here wraps negative, passes the
			// check, and either throws mid-blit or writes 16 KiB at an address the caller
			// never named (which every mirror reproduces identically, so no detector fires).
			if ((long) d.x + d.w > res.width || (long) d.y + d.h > res.height)
				throw new IllegalStateException("Texture write region out of bounds for resource " + d.resId);
			// Independent divergence detector: versions advance by exactly one per write, so
			// a missed write is caught even if the sequence numbers looked continuous.
			if (d.version != res.latestVersion + 1)
				throw new IllegalStateException("Texture write version gap on resource " + d.resId
						+ ": expected " + (res.latestVersion + 1) + ", got " + d.version);
			// NO MUTATION ABOVE THIS LINE. Advancing latestVersion before the blit is what
			// turns any blit failure into a permanent freeze: version != latestVersion
			// forever, so every later write is silently skipped and no body is ever
			// acceptable again.
			// ASYMMETRY, deliberate: a mirror that does not hold this texture's bytes still
			// tracks latestVersion (so it knows it is behind and can fetch), but has nothing
			// to blit into. Only a side holding the previous version's bytes can apply the
			// pixels — and then all-or-nothing, since every bound is validated above.
			if (res.bytes != null && res.version == d.version - 1) {
				for (int row = 0; row < d.h; row++) {
					int srcOff = row * d.w * 4;
					int dstOff = ((d.y + row) * res.width + d.x) * 4;
					System.arraycopy(d.pixels, srcOff, res.bytes, dstOff, d.w * 4);
				}
				res.version = d.version;
				res.unionDirtyRect(d.x, d.y, d.w, d.h);
			}
			res.latestVersion = d.version;
		} else if (delta instanceof Delta.ResourceFree) {
			// Freeing a resource that nodes or recorded commands still reference is legal;
			// dangling references render the pending-placeholder (same as an untransferred
			// texture body). Convergence is unaffected — both sides dangle identically.
			Delta.ResourceFree d = (Delta.ResourceFree) delta;
			if (state.resources.remove(d.resId) == null)
				throw new IllegalStateException("Freeing unknown resource " + d.resId);
		} else if (delta instanceof Delta.CanvasPublish) {
			Delta.CanvasPublish d = (Delta.CanvasPublish) delta;
			validateEmbeddedRefs(state, d.commands);
			canvasOf(state, d.resId).publish(d.commands);
		} else if (delta instanceof Delta.CanvasAppend) {
			Delta.CanvasAppend d = (Delta.CanvasAppend) delta;
			validateEmbeddedRefs(state, d.commands);
			canvasOf(state, d.resId).append(d.commands);
		} else if (delta instanceof Delta.ProgramCreate) {
			Delta.ProgramCreate d = (Delta.ProgramCreate) delta;
			if (state.programs.containsKey(Integer.valueOf(d.programId)))
				throw new IllegalStateException("Program " + d.programId + " already exists");
			// Keeps `nextProgramId > lastKey` true of any state reachable through this method,
			// which is the invariant SnapshotCodec.decode refuses a snapshot for violating. The
			// `+ 1` cannot overflow because both producers bound the id first: the server's
			// counter refuses at Integer.MAX_VALUE, and BatchCodec refuses a wire id of
			// MAX_VALUE (or < 1) precisely so a crafted delta cannot wrap this into the state
			// the snapshot check rejects. It is redundant on the server (createProgram allocates
			// from the counter) and mirrors do not allocate at all — so this is not fixing a
			// live collision. What it buys is that the invariant belongs to the class rather
			// than to one caller's discipline, which is the difference between a rule and a
			// habit.
			if (d.programId >= state.nextProgramId)
				state.nextProgramId = d.programId + 1;
			// NOT re-validated AT APPLY TIME, which is a narrower claim than it looks: IrCodec's
			// security model has clients independently re-validate every blob they receive, and
			// that still holds — it happens where the blob is DECODED TO RUN (3.3), not here.
			// Validating in this method would be the wrong place for it twice over. Both sides run
			// this code, so a disagreement could only diverge the tables; and a throw here is a
			// resync trigger, which would answer a bad program by refetching the same bad program
			// forever. Storing bytes is not executing them. Structural bounds on the way in live
			// in BatchCodec, before these bytes are ever a Delta.
			state.programs.put(Integer.valueOf(d.programId),
					new ProgramInfo(d.programId, d.stage, d.blobCopy(), d.structuralOps));
		} else if (delta instanceof Delta.ProgramFree) {
			Delta.ProgramFree d = (Delta.ProgramFree) delta;
			// No child-reference scan, unlike NodeFree, and that is SETTLED rather than pending:
			// DESIGN's ANIM-15/17 audit ("Free while attached is a Lua error is the wrong half of
			// our own precedent") rules free-while-attached LEGAL AND DANGLING, on this
			// codebase's own stated criterion — freeing a resource nodes reference dangles
			// because both sides dangle identically, while freeing a parent node is refused
			// because the persisted form would disagree with live state. A freed program leaves
			// an attachment that renders as no-animation, identically everywhere and in the save
			// too: the resource case. So when 3.2 adds the attachment record this arm does not
			// change. What 3.2 still chooses is the record's HOME, which decides where the
			// ATTACHMENT is dropped — five paths, enumerated in that same DESIGN section.
			if (state.programs.remove(Integer.valueOf(d.programId)) == null)
				throw new IllegalStateException("Freeing unknown program " + d.programId);
		} else if (delta instanceof Delta.NodeAttach) {
			Delta.NodeAttach d = (Delta.NodeAttach) delta;
			SceneNode node = state.nodes.get(Integer.valueOf(d.nodeId));
			// The NODE must exist — it is the thing being written, and a write to a missing node
			// is the same class of error as props for a missing node, which throws two arms up.
			if (node == null)
				throw new IllegalStateException("Attach for unknown node " + d.nodeId);
			// The PROGRAM deliberately is not checked. ANIM-17 rules a dangling attachment legal
			// (the resource case: both sides dangle identically and the persisted form agrees),
			// and requiring resolution here would additionally make delta ORDER significant in a
			// way nothing guarantees — a batch that frees a program and re-attaches later in the
			// same tick is legal traffic. An unresolvable id renders as no animation.
			//
			// Replace and detach are the same write, per ANIM-17's atomic-replace ruling: there is
			// no "already attached" refusal, and 0 is simply the value that means none.
			node.animator = d.programId;
			// Both fields move together, always. A replace restarts the clock — the new
			// attachment is a NEW attachment, and an easing program must not inherit the previous
			// one's age — and a detach clears the stamp so a later re-attach cannot read a stale
			// one. Writing the id without the stamp is the defect this pairing exists to prevent.
			node.attachedWorldTime = d.attachedWorldTime;
		} else if (delta instanceof Delta.SceneProp) {
			// Reserved (Stage D); carrying it is legal, applying it is a no-op for now.
		} else {
			throw new IllegalStateException("Unknown delta " + delta.getClass());
		}
	}

	/**
	 * The doc's "any unknown-id reference" rule extends to resource ids embedded in draw
	 * commands: a DRAW_TEXTURE(_SUB) recorded against a never-created id is rejected at
	 * apply time on both sides (server: Lua error; mirror: resync trigger). Ids valid at
	 * record time may later dangle via ResourceFree — see the free note above.
	 */
	private static void validateEmbeddedRefs(SceneState state, java.util.List<CanvasCommand> commands) {
		for (CanvasCommand cmd : commands) {
			if (cmd.op == V2Wire.OP_DRAW_TEXTURE || cmd.op == V2Wire.OP_DRAW_TEXTURE_SUB) {
				int ref = (int) cmd.args[0];
				if (!state.resources.containsKey(ref))
					throw new IllegalStateException("Draw command references unknown resource " + ref);
			}
		}
	}

	private static SceneCanvas canvasOf(SceneState state, int resId) {
		ResourceInfo res = state.resources.get(resId);
		if (res == null)
			throw new IllegalStateException("Canvas op on unknown resource " + resId);
		if (res.canvas == null)
			throw new IllegalStateException("Canvas op on non-canvas resource " + resId);
		return res.canvas;
	}
}
