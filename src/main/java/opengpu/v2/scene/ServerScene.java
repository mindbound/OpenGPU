package opengpu.v2.scene;

import java.util.ArrayList;
import java.util.List;

import opengpu.v2.protocol.Delta;
import opengpu.v2.protocol.SceneBatch;
import opengpu.v2.protocol.V2Wire;

/**
 * The authoritative server-side scene. Public mutators validate, build a delta, apply it to
 * the local state through {@link DeltaApplier} (the same code path mirrors use), and stage
 * it. {@link #sealBatch()} closes the tick's batch with the next sequence number.
 *
 * Thread contract: all access under the owner's scene lock (the component layer's job);
 * this class itself is single-threaded on purpose.
 *
 * Texture bytes are server-held state; the ResourceCreate delta carries only the metadata
 * (id/type/dims/size/hash) — body transfer to clients is a separate, later concern.
 */
public final class ServerScene {
	public final String sceneId;
	/** Incarnation stamp: nonzero, minted at creation, restored with persistence. */
	private final int epoch;
	private final SceneState state;
	private int seq;
	private long currentTick;
	/** Admission allowance consumed this TICK; reset on a tick-value change. */
	private int tickWriteBytes;
	/**
	 * Texture-write payload staged into the CURRENT UNSEALED BATCH; reset at seal.
	 *
	 * Deliberately separate from {@link #tickWriteBytes}: the two coincide in the common
	 * path but diverge whenever a tick boundary passes without a seal, or a seal happens
	 * without a tick change (saveBoundary does exactly that). One counter cannot bound both,
	 * and it is the BATCH bound that the decoder enforces — so conflating them produces
	 * batches the receiver must reject.
	 */
	private int stagedWriteBytes;
	/** Canvas-submit payload charged this TICK and to the current unsealed BATCH. */
	private int tickSubmitBytes;
	private int stagedSubmitBytes;
	/**
	 * Program blob bytes staged into the CURRENT UNSEALED BATCH; reset at seal.
	 *
	 * There is deliberately no per-TICK twin. The other two payload kinds have one because their
	 * cost is per-frame and recurring; a program is created once and then referenced, so the
	 * scene ledger is the standing bound and this is only about what one batch can carry.
	 */
	private int stagedProgramBytes;
	/**
	 * Mesh blob bytes staged into the CURRENT UNSEALED BATCH; reset at seal. No per-TICK twin,
	 * for {@link #stagedProgramBytes}'s stated reason: a mesh is created once and referenced,
	 * so the scene ledger is the standing bound and this bounds only what one batch carries.
	 */
	private int stagedMeshBytes;
	private int writeBudgetBytes = V2Wire.MAX_WRITE_BYTES_PER_TICK;
	private int manifestGen;
	private final ArrayList<Delta> staged = new ArrayList<Delta>();

	public ServerScene(String sceneId) {
		this(sceneId, 0);
	}

	/** initialSeq is exposed for wraparound testing; a fresh epoch is minted. */
	public ServerScene(String sceneId, int initialSeq) {
		this(sceneId, initialSeq, mintEpoch(), new SceneState());
	}

	/** Persistence-restore constructor: same incarnation continues (epoch must be nonzero). */
	public ServerScene(String sceneId, int initialSeq, int epoch, SceneState state) {
		if (epoch == 0)
			throw new IllegalArgumentException("Epoch must be nonzero");
		this.sceneId = sceneId;
		this.epoch = epoch;
		this.state = state;
		this.seq = initialSeq;
	}

	/** Public: a divergent restore (degraded bodies) mints a fresh incarnation too. */
	public static int mintEpoch() {
		java.util.Random random = new java.util.Random();
		int epoch;
		do {
			epoch = random.nextInt();
		} while (epoch == 0); // 0 is the mirrors' "no epoch adopted yet" sentinel
		return epoch;
	}

	public int epoch() {
		return epoch;
	}

	public SceneState state() {
		return state;
	}

	public int currentSeq() {
		return seq;
	}

	/** True while mutations are staged for the current tick's (unsealed) batch. */
	public boolean hasStagedDeltas() {
		return !staged.isEmpty();
	}

	public void setCurrentTick(long tick) {
		beginTick(tick, V2Wire.MAX_WRITE_BYTES_PER_TICK);
	}

	/**
	 * Advance to a tick and grant that tick's texture-write allowance. The allowance resets
	 * only on a CHANGE of tick value: a mid-tick save boundary seals a batch but must not
	 * hand out a second allowance, or the per-tick cap becomes per-seal.
	 */
	public void beginTick(long tick, int writeBudgetBytes) {
		if (tick != currentTick) {
			currentTick = tick;
			tickWriteBytes = 0;
			tickSubmitBytes = 0;
		}
		this.writeBudgetBytes = Math.max(0, writeBudgetBytes);
	}

	/**
	 * Bumped whenever a resource's advertised hash changes without a sequence advance (a body
	 * serve or a save computes knownHash lazily). The snapshot envelope cache keys on it, so
	 * a cached snapshot cannot keep advertising a stale cache hint.
	 */
	public int manifestGen() {
		return manifestGen;
	}

	public void bumpManifestGen() {
		manifestGen++;
	}

	private void applyAndStage(Delta delta) {
		DeltaApplier.apply(state, delta);
		staged.add(delta);
	}

	public int createCanvas(int width, int height, int commandCap) {
		validateDimensions(width, height);
		if (commandCap <= 0 || commandCap > V2Wire.MAX_COMMANDS - 2)
			throw new IllegalArgumentException("Command cap out of range: " + commandCap);
		int id = allocateResourceId();
		applyAndStage(new Delta.ResourceCreate(id, V2Wire.RES_CANVAS, width, height, 0, 0, commandCap));
		return id;
	}

	/**
	 * Replace a canvas with one of new dimensions, KEEPING ITS RESOURCE ID.
	 *
	 * Reusing the id is the point, not an optimisation. Nodes reference resources by id and
	 * a node's ref is immutable (there is no NodeSetRef delta), so a canvas recreated under
	 * a fresh id would need a fresh node too — and the display canvas is identified purely
	 * by being the lowest-id canvas node ({@link SceneState#displayCanvas()}). A new node id
	 * sorts last, silently handing the display slot to some offscreen canvas, with both
	 * sides agreeing so nothing detects it. Same id, same node, rule intact.
	 *
	 * Expressed with the EXISTING delta types rather than a new resize delta, so no
	 * PROTOCOL_VERSION bump and no save migration: the applier's create rejects an id that
	 * already exists, but the free immediately before it has removed the entry, and deltas
	 * apply in order on both sides. Content is not carried over — the new canvas starts
	 * empty, which is also what a fresh {@code SceneCanvas} gives us for free.
	 *
	 * The node's ref dangles for exactly the width of the free, which the free path already
	 * documents as legal and convergence-neutral.
	 */
	public void recreateCanvas(int resId, int width, int height, int commandCap) {
		validateDimensions(width, height);
		if (commandCap <= 0 || commandCap > V2Wire.MAX_COMMANDS - 2)
			throw new IllegalArgumentException("Command cap out of range: " + commandCap);
		ResourceInfo existing = state.resources.get(resId);
		if (existing == null)
			throw new IllegalStateException("Recreating unknown resource " + resId);
		if (existing.type != V2Wire.RES_CANVAS)
			throw new IllegalStateException("Resource " + resId + " is not a canvas");
		applyAndStage(new Delta.ResourceFree(resId));
		applyAndStage(new Delta.ResourceCreate(resId, V2Wire.RES_CANVAS, width, height, 0, 0, commandCap));
	}

	public int createTexture(int width, int height, byte[] bytes) {
		validateDimensions(width, height);
		if (bytes == null)
			throw new IllegalArgumentException("Texture bytes required server-side");
		// Long arithmetic: w*h*4 in int silently wraps at 32768x32768, defeating both this
		// check and any byte-budget charge computed from the declared size.
		if (bytes.length != (long) width * height * 4L)
			throw new IllegalArgumentException("Texture byte length must be w*h*4");
		int id = allocateResourceId();
		applyAndStage(new Delta.ResourceCreate(id, V2Wire.RES_TEXTURE, width, height,
				bytes.length, V2Wire.contentHash(bytes), 0));
		// Clone: an aliased caller buffer mutated later would desync the server's own state
		// from the version the mirrors were told about.
		ResourceInfo res = state.resources.get(id);
		res.bytes = bytes.clone();
		res.version = 1; // the server always holds the latest version's content
		return id;
	}

	/**
	 * Create a mesh from packed blobs (v10) — the frozen wire layout is documented at
	 * {@link V2Wire#DELTA_MESH_CREATE}. Admission is charged before the work, in
	 * {@code createProgram}'s order: the format gate (the shared validator — its cheap length
	 * bounds run first internally), then the scene ledger, then the per-batch counter, then id
	 * space, and only then the delta is built and staged.
	 *
	 * @throws IllegalArgumentException if the blobs violate the frozen format; the message is
	 *         the shared validator's own and is meant to reach the author
	 * @throws IllegalStateException if the ledger, batch allowance or id space refuses
	 */
	public int createMesh(byte[] vertexBytes, byte[] indexBytes) {
		// The format gate first — the cheaper refusal, and it bounds what the ledger below can
		// be asked about (createProgram's ceiling-first reasoning).
		V2Wire.validateMeshBlobs(vertexBytes, indexBytes);
		long total = (long) vertexBytes.length + indexBytes.length;
		// THE ADMISSION GATE. Long arithmetic for the same wrap reason programBytes() states.
		if (meshBytes() + total > MAX_MESH_BYTES)
			throw new IllegalStateException("Scene mesh budget exhausted ("
					+ MAX_MESH_BYTES + " bytes); free a mesh first");
		// The BATCH bound, which the ledger does not imply: freeing returns ledger bytes but
		// does not un-stage the delta that carried them — the create/free-loop hole
		// V2Wire.MAX_MESH_BYTES_PER_BATCH exists to close.
		if (stagedMeshBytes + total > V2Wire.MAX_MESH_BYTES_PER_BATCH)
			throw new IllegalStateException("Batch mesh payload exhausted ("
					+ V2Wire.MAX_MESH_BYTES_PER_BATCH + " bytes); it refills next tick");
		int id = allocateResourceId();
		applyAndStage(new Delta.MeshCreate(id, vertexBytes, indexBytes));
		stagedMeshBytes += (int) total;
		return id;
	}

	/**
	 * Set one named uniform on a node's table (v10), or clear it.
	 *
	 * {@code values} carries 1..4 doubles — the count IS the wire type — or is empty/null for
	 * CLEAR. Name legality, the per-node cap and clear-of-missing are enforced on the shared
	 * apply path (both sides accept exactly the same set). THIS method is the shared admission
	 * gate both host verbs (setUniform and setUniformImmediate) relay through, and its checks
	 * run in a frozen order — the reserved-name check FIRST, so a bare
	 * {@code setUniform(node, "__proj")} cannot CLEAR host state through a values-first
	 * implementation:
	 *
	 * 1. Names starting {@code __} are RESERVED FOR THE HOST (setPerspective/setOrtho write
	 *    the projection there). Refused here — admission policy only: the wire, the applier
	 *    and every save still accept them, so replication and old worlds are untouched. The
	 *    C1.3 binder SKIPS names starting __ — reserved in BOTH directions, so a program
	 *    declaring a __ name reads zero (the ruled contract; a first draft recorded the
	 *    opposite and the panel caught it).
	 * 2. Every value must be finite — DESIGN's pinned set-call rule. Rejection by throw
	 *    retains the previous value by construction (applyAndStage never ran); the pin's
	 *    "logged once" clause is superseded by the error reaching the author directly.
	 * 3. At most 4 values.
	 */
	public void setUniform(int nodeId, String name, double[] values, boolean immediate) {
		requireNode(nodeId);
		if (name != null && name.startsWith("__"))
			throw new IllegalArgumentException("uniform names starting with __ are reserved for"
					+ " the host; projection is set via setPerspective/setOrtho on a camera");
		double[] v = values == null ? new double[0] : values;
		for (int i = 0; i < v.length; i++) {
			if (Double.isNaN(v[i]) || Double.isInfinite(v[i]))
				throw new IllegalArgumentException("uniform value " + (i + 1)
						+ " must be a finite number (the previous value is retained)");
		}
		if (v.length > 4)
			throw new IllegalArgumentException("A uniform carries 1..4 values, got " + v.length);
		applyAndStage(new Delta.UniformSet(nodeId, name, (byte) v.length, v, immediate));
	}

	/** The reserved projection entry's name: __proj = vec4(mode, fovDegOrHalfHeight, near, far). */
	public static final String PROJECTION_UNIFORM = "__proj";
	/** __proj mode component: 1 = perspective (fov in degrees), 2 = orthographic (half-height). */
	public static final double PROJECTION_PERSPECTIVE = 1;
	public static final double PROJECTION_ORTHO = 2;

	/**
	 * Write a camera's projection (v10, C1.2) — ONE reserved vec4 entry in the node's own
	 * uniform table, the one mechanism that already replicates, persists, and survives rejoin
	 * at v10. Atomic BY CONSTRUCTION: a single UniformSet delta, so even a 64-full-table
	 * refusal is a clean whole refusal with nothing staged (this is why the entry is one vec4
	 * and not four scalars — a four-delta write could tear mid-sequence at the cap). Staged
	 * {@code immediate}: a projection change is a cut, not a glide (teleport's doctrine) —
	 * without it C1.3's interpolator would slide the MODE through 1.5 for a tick.
	 *
	 * NODE_CAMERA-gated, and that gate does not conflict with the ungated-writer doctrine: no
	 * ungated verb can write reserved state (setUniform refuses __ names), so this is one
	 * state with one door. Validation: perspective fov in (0,180) exclusive; near &gt; 0,
	 * far &gt; near; ortho half-height &gt; 0. The entry costs 1 of the node's 64 uniform
	 * slots (a camera's author budget is 63), and once set it is author-unclearable short of
	 * freeNode — a documented one-way door; renderer defaults are a pre-first-call state only.
	 */
	public void setProjection(int nodeId, boolean ortho, double fovOrHalfHeight, double near,
			double far) {
		requireNode(nodeId);
		SceneNode node = state.nodes.get(nodeId);
		if (node.type != V2Wire.NODE_CAMERA)
			throw new IllegalStateException("node " + nodeId + " is not a camera");
		if (Double.isNaN(fovOrHalfHeight) || Double.isInfinite(fovOrHalfHeight)
				|| Double.isNaN(near) || Double.isInfinite(near)
				|| Double.isNaN(far) || Double.isInfinite(far))
			throw new IllegalArgumentException("projection parameters must be finite numbers");
		if (ortho) {
			if (!(fovOrHalfHeight > 0))
				throw new IllegalArgumentException("ortho half-height must be > 0");
		} else {
			if (!(fovOrHalfHeight > 0 && fovOrHalfHeight < 180))
				throw new IllegalArgumentException("fov must be in (0, 180) degrees exclusive");
		}
		if (!(near > 0))
			throw new IllegalArgumentException("near must be > 0");
		if (!(far > near))
			throw new IllegalArgumentException("far (" + far + ") must be greater than near ("
					+ near + ")");
		// Bypasses setUniform deliberately: that method's __ refusal guards AUTHORS out of the
		// reserved space; this method IS the host door it points them to.
		applyAndStage(new Delta.UniformSet(nodeId, PROJECTION_UNIFORM, (byte) 4, new double[] {
				ortho ? PROJECTION_ORTHO : PROJECTION_PERSPECTIVE, fovOrHalfHeight, near, far },
				true));
	}

	/**
	 * Write packed RGBA pixels into a texture region. The pixels always travel with the
	 * delta — there is no invalidate-and-refetch form, because that costs sizeBytes per
	 * watcher per refresh with no bound.
	 *
	 * Admission is a hard per-tick byte allowance. The caller is expected to translate
	 * refusal into back-pressure (OC's LimitReachedException → next-tick replay), never into
	 * a degraded path.
	 *
	 * @throws IllegalStateException if the per-tick allowance is exhausted
	 */
	public void writeRegion(int resId, int x, int y, int w, int h, byte[] data) {
		ResourceInfo res = state.resources.get(resId);
		if (res == null)
			throw new IllegalStateException("Unknown resource " + resId);
		if (res.type != V2Wire.RES_TEXTURE)
			throw new IllegalStateException("Resource " + resId + " is not a texture");
		if (w < 1 || h < 1)
			throw new IllegalArgumentException("Region must be at least 1x1");
		// Long arithmetic: OC saturates out-of-range Lua integers to Integer.MAX_VALUE, so
		// an int sum wraps negative and slips past this check.
		if (x < 0 || y < 0 || (long) x + w > res.width || (long) y + h > res.height)
			throw new IllegalArgumentException("Region out of bounds");
		long len = (long) w * (long) h * 4L;
		if (data == null || data.length != len)
			throw new IllegalArgumentException("Data length must be w*h*4 (" + len + ")");
		if (len > V2Wire.MAX_WRITE_REGION_BYTES)
			throw new IllegalArgumentException(
					"Region too large (max " + V2Wire.MAX_WRITE_REGION_BYTES + " bytes per call)");
		if (res.latestVersion == Integer.MAX_VALUE)
			throw new IllegalStateException("Texture version space exhausted; free and recreate it");
		if (tickWriteBytes + len > writeBudgetBytes)
			throw new IllegalStateException("Per-tick texture write allowance exhausted");
		// The batch bound is what the decoder checks, so it must hold independently of the
		// tick allowance — a batch spanning a tick boundary would otherwise be rejected by
		// every receiver, costing the whole frame and a resync.
		//
		// It gets its own constant at TWICE the tick allowance because that spanning is not an
		// edge case, it is arithmetic: a batch accumulates from one END-phase seal to the next
		// while the tick allowance resets at START, so a direct callback landing in the window
		// between them is charged to one tick and staged into the following batch. Bounding the
		// batch by one tick's number left this check tighter than the tick check it sits behind,
		// which is a refusal no caller can clear by waiting.
		if (stagedWriteBytes + len > V2Wire.MAX_WRITE_BYTES_PER_BATCH)
			throw new IllegalStateException("Batch texture write payload exhausted");
		tickWriteBytes += (int) len;
		stagedWriteBytes += (int) len;
		applyAndStage(new Delta.TextureWrite(resId, res.latestVersion + 1, x, y, w, h, data));
	}

	/** Bytes of texture-write payload still admissible this tick. */
	public int writeBudgetRemaining() {
		// The tighter of the two bounds: a caller pacing itself by this number must never be
		// refused by the other one.
		return Math.max(0, Math.min(writeBudgetBytes - tickWriteBytes,
				V2Wire.MAX_WRITE_BYTES_PER_BATCH - stagedWriteBytes));
	}

	private static void validateDimensions(int width, int height) {
		if (width <= 0 || height <= 0
				|| width > V2Wire.MAX_TEXTURE_DIM || height > V2Wire.MAX_TEXTURE_DIM)
			throw new IllegalArgumentException("Dimensions out of range: " + width + "x" + height);
	}

	private int allocateResourceId() {
		if (state.nextResourceId == Integer.MAX_VALUE)
			throw new IllegalStateException("Scene resource id space exhausted; recreate the scene");
		return state.nextResourceId++;
	}

	public void freeResource(int resId) {
		if (!state.resources.containsKey(resId))
			throw new IllegalStateException("Freeing unknown resource " + resId);
		applyAndStage(new Delta.ResourceFree(resId));
	}

	/**
	 * Most nodes one scene may hold.
	 *
	 * Enforced HERE, at the single allocation point, rather than in DeltaApplier: a mirror must
	 * apply whatever the server legitimately produced, and a decode-time cap that the two sides
	 * could ever disagree on turns a legal batch into a permanent resync loop. Restore paths go
	 * through SnapshotCodec, not here, so lowering this constant can never brick an existing
	 * save — it only refuses new allocations.
	 *
	 * The id space alone is not a bound: it permits 2^31 nodes, and every node costs server
	 * memory, snapshot bytes to every watcher, and per-frame client work.
	 */
	public static final int MAX_NODES = 4096;

	public int createNode(byte nodeType, int ref) {
		return createNode(nodeType, ref, 0);
	}

	/**
	 * Total OCSL program bytes one scene may hold, decided with the user 2026-08-17.
	 *
	 * Enforced HERE and not in DeltaApplier, for the reason {@link #MAX_NODES} states: this is
	 * admission policy, and a decode-time cap the two sides could disagree about would turn a
	 * legitimate batch into a permanent resync loop. Restores go through SnapshotCodec, so
	 * lowering it can never brick a save — it only refuses new creates.
	 *
	 * A PROVISIONAL number, and its capacity must be stated against the BLOB CEILING, not against
	 * example programs. The first sizing here quoted "a program charging the full op budget is
	 * 1810 bytes, so ~145 maximal programs" — measured, and still wrong as a bound, because the op
	 * cap does not bound bytes: the constant pool is charged by nothing on the accept path, so a
	 * program charging 2 structural ops legally encodes to ~17 KiB (measured,
	 * ProgramLedgerBoundTest), and the only real per-program byte bound is
	 * {@code OcslWire.MAX_BLOB_BYTES} (64 KiB). Honest capacity: 4 ceiling-sized programs, ~15
	 * pool-heavy ones, ~145 of the cheapest op-chain shape, ~1100 at the sizes committed programs
	 * happen to be today — and per the user (2026-08-18), today's examples must NOT be read as a
	 * bound on what real programs will weigh. REVISIT AT ANIM-16, with the per-program sizes 3.3's
	 * evaluation overlay will report from real scenes; raising this is free (admission policy,
	 * monotonic), which is why holding a data-free 256 KiB now beats guessing a bigger number.
	 *
	 * The COUNT dimension is bounded through the envelope floor (17 bytes; a program that
	 * VALIDATES measures >= 41), so this ledger admits ~15k programs at most against
	 * {@code SnapshotCodec.MAX_ENTRIES} of 65536 — the margin that stops the server producing a
	 * snapshot every client refuses, which is the silent-desync failure MAX_DELTAS was lowered to
	 * close. The per-BATCH byte dimension needs its own bound and has one:
	 * {@link V2Wire#MAX_PROGRAM_BYTES_PER_BATCH}. {@code ProgramLedgerBoundTest} pins all three
	 * relationships, so moving any constant past a crossing fails there rather than in a save.
	 *
	 * A restore does NOT re-check this ledger, deliberately: a save written under a larger value
	 * must reload after the value shrinks, same stance as MAX_NODES. An over-budget restored scene
	 * simply refuses new creates until frees bring it under.
	 */
	public static final int MAX_PROGRAM_BYTES = 256 * 1024;

	/**
	 * Total mesh bytes (vertex + index blobs) one scene may hold (v10). ADMISSION ONLY, the
	 * {@link #MAX_PROGRAM_BYTES} discipline verbatim: enforced here, never in DeltaApplier and
	 * never re-checked at restore, so lowering it can never brick a save — an over-budget
	 * restored scene simply refuses new creates until frees bring it under.
	 *
	 * 512 KiB = exactly TWO maximum-size meshes (192 KiB vertices + 64 KiB indices each) —
	 * the ProgramLedgerBoundTest usability floor (fewer than 2 maximal items is unusable
	 * rather than protective); its mesh twin pins the relationship. 2x the program ledger
	 * because meshes are bulkier per useful unit. The per-BATCH byte dimension has its own
	 * bound ({@link V2Wire#MAX_MESH_BYTES_PER_BATCH}); the format-identity per-mesh caps live
	 * in V2Wire because the DECODER enforces those.
	 */
	public static final int MAX_MESH_BYTES = 512 * 1024;

	/**
	 * Uniform entries one node's table may hold (v10). A DEDICATED constant, deliberately not
	 * bound to {@code OcslWire.MAX_NAMES} or {@code SurfaceTable.MAX_UNIFORMS} — those are
	 * calibration siblings that COINCIDE at 64 by calibration, not identity (the withheld-cap
	 * lesson: two caps equal by accident must stay separately movable, or the day they diverge
	 * one of them is a lie). Enforced on the shared apply path so server and mirror accept
	 * exactly the same set; the snapshot decoder bounds its per-node counts by this too.
	 */
	public static final int MAX_NODE_UNIFORMS = 64;

	/** Program bytes this scene currently holds; the ledger's running total. */
	public long programBytes() {
		long total = 0;
		for (ProgramInfo p : state.programs.values()) {
			total += p.sizeBytes;
		}
		return total;
	}

	/** Mesh bytes this scene currently holds; {@link #MAX_MESH_BYTES}'s running total. */
	public long meshBytes() {
		long total = 0;
		for (ResourceInfo r : state.resources.values()) {
			if (r.type == V2Wire.RES_MESH)
				total += r.sizeBytes;
		}
		return total;
	}

	/** Mesh bytes still admissible; clamped at zero for the restored-over-budget case. */
	public long meshBudgetRemaining() {
		return Math.max(0L, MAX_MESH_BYTES - meshBytes());
	}

	/** Program bytes still admissible. Clamped at zero, like every other *Remaining here: a
	 * restore of a save written under a larger ledger can legally hold more than the current cap,
	 * and a caller pacing itself must read "no room", not a negative number. */
	public long programBudgetRemaining() {
		return Math.max(0L, MAX_PROGRAM_BYTES - programBytes());
	}

	/**
	 * Validate a program blob and store it on the scene, returning its id.
	 *
	 * ADMISSION IS CHARGED BEFORE THE WORK, which is the whole shape of this method. The blob's
	 * LENGTH is known without parsing anything, and the expensive part — decode plus validate, an
	 * unroll-bounded walk over up to 4096 ops — happens only for a blob the ledger would accept.
	 * The reverse order is the admission-after-work defect {@code canvasSubmit} shipped on
	 * 2026-08-03 (decoded up to ~65,500 command objects before consulting the tick allowance,
	 * with a costless-refusal twin beside it — both documented at that callback).
	 *
	 * @throws IllegalStateException if the scene's program ledger cannot fit the blob
	 * @throws IllegalArgumentException if the blob is malformed or fails validation; the message
	 *         is the codec's or validator's own and is meant to reach the author
	 */
	public int createProgram(byte[] blob) {
		if (blob == null || blob.length == 0)
			throw new IllegalArgumentException("Program bytes required");
		// The codec ceiling first: it is the cheaper refusal and it bounds what the ledger check
		// below can even be asked about.
		if (blob.length > opengpu.v2.ocsl.OcslWire.MAX_BLOB_BYTES)
			throw new IllegalArgumentException("Program blob is " + blob.length
					+ " bytes, over the " + opengpu.v2.ocsl.OcslWire.MAX_BLOB_BYTES + " ceiling");
		// THE ADMISSION GATE. Long arithmetic: the running total is a long precisely so a scene
		// near the cap cannot wrap an int sum negative and read as having room.
		if (programBytes() + (long) blob.length > MAX_PROGRAM_BYTES)
			throw new IllegalStateException("Scene program budget exhausted ("
					+ MAX_PROGRAM_BYTES + " bytes); free a program first");
		// The BATCH bound, which the ledger above does not imply: freeProgram returns ledger bytes
		// but does not un-stage the delta that put them there, so a create/free loop within one
		// tick stages unbounded blob bytes while programBytes() stays at one program. Without
		// this the server can build a batch past the decoder's ceiling and every mirror refuses
		// it whole — see V2Wire.MAX_PROGRAM_BYTES_PER_BATCH.
		if (stagedProgramBytes + (long) blob.length > V2Wire.MAX_PROGRAM_BYTES_PER_BATCH)
			throw new IllegalStateException("Batch program payload exhausted ("
					+ V2Wire.MAX_PROGRAM_BYTES_PER_BATCH + " bytes); it refills next tick");
		if (state.nextProgramId == Integer.MAX_VALUE)
			throw new IllegalStateException("Scene program id space exhausted; recreate the scene");

		opengpu.v2.ocsl.IrProgram program;
		opengpu.v2.ocsl.IrValidator.Validated validated;
		try {
			// TRANSIENT: this blob came from a caller in memory, not off a disk. The persisted
			// path is SnapshotCodec's, and it stores bytes rather than decoding them.
			program = opengpu.v2.ocsl.IrCodec.decode(blob,
					opengpu.v2.ocsl.IrCodec.Source.TRANSIENT);
			validated = opengpu.v2.ocsl.IrValidator.validate(program);
		} catch (opengpu.v2.protocol.CodecException e) {
			throw new IllegalArgumentException(e.getMessage(), e);
		} catch (opengpu.v2.ocsl.ValidationException e) {
			throw new IllegalArgumentException(e.getMessage(), e);
		}
		// Narrowing is safe BECAUSE validate() returned: the charge is a long only so the
		// validator can detect an unroll product overflowing on the way to this cap, and anything
		// above the stage's cap threw above. Asserted rather than assumed, because the value
		// crosses into an int field and a future cap raise is planned work (ANIM-16).
		//
		// THE PER-STAGE CAP, unlike the codec bounds: this asserts that the validator did what it
		// says, so it must ask the same question the validator asked. Bounding by the ceiling
		// instead would keep passing after a stage cap was lowered, which is precisely the state
		// this line exists to catch.
		if (validated.structuralOps < 0
				|| validated.structuralOps
						> opengpu.v2.ocsl.IrValidator.maxStructuralOps(program.stage))
			throw new IllegalStateException("Validator returned an out-of-range structural charge "
					+ validated.structuralOps + "; it should have refused the program");

		int id = state.nextProgramId++;
		applyAndStage(new Delta.ProgramCreate(id, program.stage,
				(int) validated.structuralOps, blob));
		stagedProgramBytes += blob.length;
		return id;
	}

	/**
	 * Record the server's world time on the scene, once per tick.
	 *
	 * TWO FIELDS, ONE CALL, because they are stamped from the same reading and forgetting either
	 * is silent: both were declared, encoded, decoded and copied for a whole increment while
	 * NOTHING assigned them, so every snapshot shipped zeros and the conversion
	 * {@code sessionTick = serverTick + (stamp - worldTimeAnchor)} silently degenerated to
	 * {@code serverTick + stamp}. Found by review noticing the tests could only ever assert 0.
	 *
	 * {@link SceneState#worldTimeAnchor} is refreshed every tick — it describes the instant a
	 * payload was produced, so a stale one mis-converts every stamp in it.
	 * {@link SceneState#creationWorldTime} is stamped ONCE and never rewritten: it is the
	 * animator clock's epoch, and re-stamping it would restart every animator on the scene. Zero
	 * is the "not yet stamped" sentinel, which is also what a pre-v7 save restores as — so such a
	 * scene adopts the current world time on its first tick and its animators start from now,
	 * which is the only answer available when the save records no epoch at all.
	 *
	 * NOT a delta, deliberately: neither field describes a scene MUTATION, and making the anchor
	 * one would put a delta on every tick of every scene forever. Both ride the snapshot, which
	 * is where a receiver needs them.
	 */
	public void stampWorldTime(long worldTime) {
		state.worldTimeAnchor = worldTime;
		if (state.creationWorldTime == 0L) {
			state.creationWorldTime = worldTime;
		}
	}

	/**
	 * Point a node at a program, or detach with {@code programId == 0}.
	 *
	 * ANIM-17's atomic replace: attaching to an already-attached node SUCCEEDS and overwrites,
	 * rather than refusing. The stamp is rewritten every time for the same reason — a replace
	 * begins a NEW attachment, so an easing program restarts instead of inheriting the previous
	 * one's age — and a detach clears it so a later re-attach cannot read a stale one.
	 *
	 * THE PROGRAM IS NOT REQUIRED TO EXIST, and that is ANIM-17's ruling rather than laxity:
	 * free-while-attached is legal and dangling, on the criterion this codebase already uses for
	 * resources. Refusing an unknown id here would ALSO make a legal ordering illegal — freeing
	 * and re-attaching within one tick — for no gain, since the id can dangle a moment later
	 * anyway.
	 *
	 * ANIM-15(a)'s display-node refusal is NOT here. It keys on the TileEntity's
	 * {@code implicitCanvasNode} — the id the SERVER persists — which this class cannot see. The
	 * scene model can DERIVE a display node ({@code DisplayNode.displayNodeId}), but that is the
	 * client's rescan-from-state answer rather than the server's remembered one, and a refusal
	 * belongs on the same field the sibling transform guard already uses.
	 *
	 * @param attachedWorldTime world time at which the attachment becomes active. IGNORED when
	 *        {@code programId == 0}: a detach always stores 0, so callers need not zero it and
	 *        cannot leave a stale stamp behind by forgetting to
	 */
	public void setAnimator(int nodeId, int programId, long attachedWorldTime) {
		requireNode(nodeId);
		if (programId < 0)
			throw new IllegalArgumentException("Program id must be non-negative (0 detaches)");
		// THE STAGE GATE (2026-08-21). Nothing on this path checked the program's stage, and a
		// legal PIXEL program attached to a node CRASHED the client render thread: the evaluator
		// binds the animator registers, a pixel frame maps none of them, and OcslVm.set throws
		// out through the Forge render tick. Reachable from Lua as createProgram(<pixel blob>)
		// + setAnimator(node, id) -- two ordinary calls. Found by the per-stage-cap review
		// panel (as a stats-population hole) and upgraded to a crash by tracing the bind.
		//
		// RESOLVABLE PROGRAMS ONLY, deliberately: ANIM-17 rules a dangling attachment legal, and
		// ids are never reused, so an id absent here can never later resolve to a wrong-stage
		// program within this incarnation. The gate therefore refuses everything it can see and
		// nothing it cannot. The client still defends independently (AnimatorOverlay.vmFor),
		// because a save written before this gate existed can carry a wrong-stage attachment.
		if (programId != 0) {
			ProgramInfo attached = state.programs.get(Integer.valueOf(programId));
			if (attached != null && attached.stage != opengpu.v2.ocsl.OcslWire.STAGE_ANIMATOR) {
				throw new IllegalArgumentException("Program " + programId + " is stage "
						+ (attached.stage & 0xFF) + "; only animator programs (stage "
						+ (opengpu.v2.ocsl.OcslWire.STAGE_ANIMATOR & 0xFF)
						+ ") may attach to nodes");
			}
		}
		applyAndStage(new Delta.NodeAttach(nodeId, programId,
				programId == 0 ? 0L : attachedWorldTime));
	}

	/**
	 * Drop a program and release its ledger bytes.
	 *
	 * Programs OUTLIVE the Lua program that created them, exactly as canvases do — server-side
	 * scene state. Without this, an ordinary create-on-startup script exhausts the ledger after
	 * hundreds to thousands of reboots (the count depends entirely on program size, which today's
	 * examples do not bound) with no recovery short of replacing the GPU block — the leak
	 * `ingame/vramreclaim.lua` exists to mop up on the resource side. Unlike that side, the ids
	 * are also ENUMERABLE from Lua (the `programs` callback), so recovery does not need a
	 * brute-force id sweep. Ids are never reused, so a freed id stays dead rather than returning
	 * attached to different code.
	 */
	public void freeProgram(int programId) {
		if (!state.programs.containsKey(Integer.valueOf(programId)))
			throw new IllegalStateException("Freeing unknown program " + programId);
		applyAndStage(new Delta.ProgramFree(programId));
	}

	/**
	 * {@code parent} is 0 for none. It is fixed here and never afterwards — see
	 * {@link SceneNode#parent} for why re-parenting is not offered.
	 *
	 * The "parent below child" rule needs no check in this method: the id is drawn fresh from a
	 * monotonic counter, so it is above every existing node by construction. DeltaApplier checks
	 * it anyway, because it also sees ids that arrived over the wire.
	 */
	public int createNode(byte nodeType, int ref, int parent) {
		if (ref != 0 && !state.resources.containsKey(ref))
			throw new IllegalStateException("Node references unknown resource " + ref);
		if (parent != 0) {
			SceneNode p = state.nodes.get(parent);
			if (p == null)
				throw new IllegalStateException("Unknown parent node " + parent);
			if (p.parent != 0)
				throw new IllegalStateException("Groups nest one level only; node " + parent
						+ " is already a child of " + p.parent);
		}
		if (state.nodes.size() >= MAX_NODES)
			throw new IllegalStateException("Scene node limit reached (" + MAX_NODES + ")");
		if (state.nextNodeId == Integer.MAX_VALUE)
			throw new IllegalStateException("Scene node id space exhausted; recreate the scene");
		int id = state.nextNodeId++;
		applyAndStage(new Delta.NodeCreate(id, nodeType, ref, parent));
		return id;
	}

	public void freeNode(int nodeId) {
		if (!state.nodes.containsKey(nodeId))
			throw new IllegalStateException("Freeing unknown node " + nodeId);
		applyAndStage(new Delta.NodeFree(nodeId));
	}

	public void setTransform(int nodeId, double x, double y, double rot, double sx, double sy) {
		setTransform(nodeId, x, y, rot, sx, sy, false);
	}

	/**
	 * @param teleport true to make clients SNAP to the new transform instead of interpolating
	 *                 toward it — for a deliberate jump, which would otherwise slide across
	 *                 the screen over one server tick.
	 */
	public void setTransform(int nodeId, double x, double y, double rot, double sx, double sy,
			boolean teleport) {
		requireNode(nodeId);
		int mask = V2Wire.PROP_X | V2Wire.PROP_Y | V2Wire.PROP_ROT | V2Wire.PROP_SX | V2Wire.PROP_SY;
		if (!teleport) {
			applyAndStage(new Delta.NodeProps(nodeId, mask, new double[] { x, y, rot, sx, sy }));
			return;
		}
		// Values follow ASCENDING BIT ORDER, which is how DeltaApplier reads them back;
		// PROP_TELEPORT is the highest bit, so its value goes last.
		applyAndStage(new Delta.NodeProps(nodeId, mask | V2Wire.PROP_TELEPORT,
				new double[] { x, y, rot, sx, sy, 1 }));
	}

	/**
	 * Full 3D TRS write (v10, C1.2): position, quaternion rotation, per-axis scale — ONE delta,
	 * always the full mask, so the write is atomic and one interpolation transition (the 2D
	 * setTransform's atomicity reasoning). The quaternion must arrive already normalized and
	 * sign-canonicalised ({@link Look#normalize}); this method stages, it does not launder.
	 *
	 * VALUE ORDER IS ASCENDING BIT ORDER, and for THIS mask that puts teleport in the MIDDLE:
	 * PROP_TELEPORT is bit 8 and every 3D bit sits above it, so the layout is
	 * x, y, sx, sy, [teleport], tz, sz, qx, qy, qz, qw. The 2D sibling's "teleport goes last"
	 * comment is true only of the 2D mask — copying it here would produce convergent garbage
	 * (both sides misread identically, no detector fires).
	 * Surface3dTest.setTransform3dLandsEveryFieldWithTeleport reads every field back for
	 * exactly that reason.
	 */
	public void setTransform3d(int nodeId, double x, double y, double z,
			double qx, double qy, double qz, double qw,
			double sx, double sy, double sz, boolean teleport) {
		requireNode(nodeId);
		int mask = V2Wire.PROP_X | V2Wire.PROP_Y | V2Wire.PROP_SX | V2Wire.PROP_SY
				| V2Wire.PROP_TZ | V2Wire.PROP_SZ | V2Wire.QUAT_PROPS_MASK;
		if (!teleport) {
			applyAndStage(new Delta.NodeProps(nodeId, mask,
					new double[] { x, y, sx, sy, z, sz, qx, qy, qz, qw }));
			return;
		}
		applyAndStage(new Delta.NodeProps(nodeId, mask | V2Wire.PROP_TELEPORT,
				new double[] { x, y, sx, sy, 1, z, sz, qx, qy, qz, qw }));
	}

	/**
	 * Position + orientation only (lookAt's staging half): scale untouched. Same bit-order
	 * discipline as {@link #setTransform3d} — teleport sits between y and tz here.
	 */
	public void setPose3d(int nodeId, double x, double y, double z,
			double qx, double qy, double qz, double qw, boolean teleport) {
		requireNode(nodeId);
		int mask = V2Wire.PROP_X | V2Wire.PROP_Y | V2Wire.PROP_TZ | V2Wire.QUAT_PROPS_MASK;
		if (!teleport) {
			applyAndStage(new Delta.NodeProps(nodeId, mask,
					new double[] { x, y, z, qx, qy, qz, qw }));
			return;
		}
		applyAndStage(new Delta.NodeProps(nodeId, mask | V2Wire.PROP_TELEPORT,
				new double[] { x, y, 1, z, qx, qy, qz, qw }));
	}

	public void setZ(int nodeId, int z) {
		requireNode(nodeId);
		applyAndStage(new Delta.NodeProps(nodeId, V2Wire.PROP_Z, new double[] { z }));
	}

	public void setVisible(int nodeId, boolean visible) {
		requireNode(nodeId);
		applyAndStage(new Delta.NodeProps(nodeId, V2Wire.PROP_VISIBLE, new double[] { visible ? 1 : 0 }));
	}

	/**
	 * Hide one node and show another as ONE indivisible pair — the double-buffer swap.
	 *
	 * This is the atomicity primitive for a frame too large to arrive in one call. The problem it
	 * solves: a chunked publish is N independent direct callbacks, each taking and releasing
	 * {@code sceneLock} on its own, while the seal runs on the server thread at tick END. Nothing
	 * spans two chunks, so a seal can fall between them and ship a batch holding chunk 1's
	 * destructive publish alone — every watcher then renders the partial frame. Widening a byte
	 * budget cannot fix that, because batch membership is a timing property, not a size one.
	 *
	 * The construction, and why it is cheaper than the alternative: a program draws the whole
	 * frame into a HIDDEN canvas node, across as many chunks, ticks and batches as it likes, and
	 * then calls this. Content assembled where nothing is looking does not need to be atomic —
	 * only the reveal does, and the reveal is two property deltas. Compare server-side frame
	 * assembly, which was designed and rejected: it caps an atomic frame at what one batch carries,
	 * has to charge bytes before anything is staged (which wedges {@link #sealBatch}'s counters,
	 * since it returns early on an empty staged list without resetting them), and narrows what
	 * {@code append} can express because the command-cap precheck is deliberately compaction-blind.
	 * A back buffer is bounded by MAX_STANDING_COMMAND_BYTES instead — an order of magnitude more.
	 *
	 * ALL-OR-NOTHING, and provably so rather than by assertion. Both ids are validated before
	 * either delta is staged, and {@link DeltaApplier}'s NodeProps path throws ONLY on a missing
	 * node; every later step is unconditional field assignment. Since this runs single-threaded
	 * under the caller's lock, nothing can remove a node between the two applies, so the second
	 * cannot throw. {@code applyAndStage} has no rollback and nothing else here does either —
	 * which is exactly why the validation has to come first.
	 *
	 * Hide is staged BEFORE show, so a mirror replaying the pair never has both visible, even
	 * transiently. It never sees either state alone: SceneMirror.applyBatch applies every delta in
	 * a batch before setting {@code dirty} once.
	 */
	public void swapVisibility(int hideNodeId, int showNodeId) {
		if (hideNodeId == showNodeId)
			throw new IllegalArgumentException(
					"swapVisibility needs two different nodes, got " + hideNodeId + " twice");
		requireNode(hideNodeId);
		requireNode(showNodeId);
		applyAndStage(new Delta.NodeProps(hideNodeId, V2Wire.PROP_VISIBLE, new double[] { 0 }));
		applyAndStage(new Delta.NodeProps(showNodeId, V2Wire.PROP_VISIBLE, new double[] { 1 }));
	}

	public void setTint(int nodeId, int argb) {
		requireNode(nodeId);
		applyAndStage(new Delta.NodeProps(nodeId, V2Wire.PROP_TINT,
				new double[] { (double) (argb & 0xFFFFFFFFL) }));
	}

	/**
	 * Apply a submitted command list to a canvas under the per-tick byte allowance.
	 *
	 * Charged at BOTH the tick and the batch, exactly as texture writes are, and for the same
	 * reason: the two counters coincide in the common path but diverge whenever a tick
	 * boundary passes without a seal, or a seal happens without a tick change (saveBoundary
	 * does that). One counter cannot bound both, and it is the BATCH bound that keeps a tick's
	 * payload inside what the decoder will accept.
	 *
	 * The two bounds are now separately sized, and that is the fix for a confirmed defect rather
	 * than a tidy-up. Both used to be MAX_SUBMIT_BYTES — the same constant the library chunks
	 * at — so chunk 1 of any multi-chunk frame consumed the whole of both and chunk 2 could not
	 * be admitted in that tick by any op mix, with no contention required. Worse, the batch
	 * bound made it unrecoverable: canvasSubmit answers exhaustion with consumeCallBudget so OC
	 * replays the call a tick later, but at that replay tickSubmitBytes has reset (START phase)
	 * while stagedSubmitBytes has not (it resets at the END-phase seal, which has not run), so
	 * the replay is refused identically and Lua sees a false it cannot clear by waiting.
	 * MAX_SUBMIT_BYTES_PER_BATCH is twice the tick allowance because a batch genuinely spans up
	 * to two of them. See PERF-BASELINE.md for the in-game confirmation.
	 *
	 * @return false when the allowance is exhausted — the caller decides whether to retry.
	 */
	public boolean submitCanvas(int resId, List<CanvasCommand> commands, boolean publish, int bytes) {
		ResourceInfo res = state.resources.get(resId);
		if (res == null || res.type != V2Wire.RES_CANVAS)
			throw new IllegalStateException("Not a canvas: " + resId);
		if (tickSubmitBytes + bytes > V2Wire.MAX_SUBMIT_BYTES_PER_TICK)
			return false;
		if (stagedSubmitBytes + bytes > V2Wire.MAX_SUBMIT_BYTES_PER_BATCH)
			return false;
		// Charge AFTER the apply, not before: a list that overruns the canvas command cap throws
		// out of DeltaApplier with nothing applied and nothing staged, and a call that changed
		// no state must not have spent the tick's allowance either.
		if (publish) {
			canvasPublish(resId, commands);
		} else {
			canvasAppend(resId, commands);
		}
		tickSubmitBytes += bytes;
		stagedSubmitBytes += bytes;
		return true;
	}

	/**
	 * Bytes of canvas-submit payload still allowed this tick.
	 *
	 * The tighter of the two bounds, so a caller pacing itself by this number is never refused
	 * by the other one. Note it can still be smaller than MAX_SUBMIT_BYTES: a caller must chunk
	 * by the per-CALL ceiling and pace by this, and the two are different questions.
	 */
	public int submitBudgetRemaining() {
		return Math.max(0, Math.min(V2Wire.MAX_SUBMIT_BYTES_PER_TICK - tickSubmitBytes,
				V2Wire.MAX_SUBMIT_BYTES_PER_BATCH - stagedSubmitBytes));
	}

	/**
	 * Encoded command bytes this scene currently holds across every canvas.
	 *
	 * O(canvases), not O(commands): each canvas maintains its own running total, so this stays
	 * cheap enough to check on a path that runs every tick.
	 */
	public long standingCommandBytes() {
		long total = 0;
		for (ResourceInfo res : state.resources.values()) {
			if (res.type == V2Wire.RES_CANVAS && res.canvas != null) {
				total += res.canvas.encodedBytes();
			}
		}
		return total;
	}

	/**
	 * Bytes of standing command list this scene can still take.
	 *
	 * Conservative on the append path, deliberately: compaction may shrink the list after the
	 * fact, but the check has to be made before applying and has to reach the same verdict on
	 * every mirror, so it projects the worst case exactly as SceneCanvas's own cap precheck
	 * does.
	 */
	public long standingBudgetRemaining() {
		return Math.max(0, V2Wire.MAX_STANDING_COMMAND_BYTES - standingCommandBytes());
	}

	private void checkStandingBudget(int resId, List<CanvasCommand> commands, boolean publish) {
		long incoming = 0;
		for (CanvasCommand cmd : commands) {
			incoming += cmd.encodedBytes();
		}
		ResourceInfo res = state.resources.get(resId);
		// A publish REPLACES the target's list, so only an append adds to what is already there.
		long freed = publish && res != null && res.canvas != null ? res.canvas.encodedBytes() : 0;
		if (standingCommandBytes() - freed + incoming > V2Wire.MAX_STANDING_COMMAND_BYTES) {
			throw new IllegalStateException("scene canvas commands would exceed "
					+ V2Wire.MAX_STANDING_COMMAND_BYTES + " bytes; publish over a canvas or free one");
		}
	}

	public void canvasAppend(int resId, List<CanvasCommand> commands) {
		checkStandingBudget(resId, commands, false);
		applyAndStage(new Delta.CanvasAppend(resId, commands));
	}

	public void canvasPublish(int resId, List<CanvasCommand> commands) {
		checkStandingBudget(resId, commands, true);
		applyAndStage(new Delta.CanvasPublish(resId, commands));
	}

	private void requireNode(int nodeId) {
		if (!state.nodes.containsKey(nodeId))
			throw new IllegalStateException("Unknown node " + nodeId);
	}

	/** Seals and returns the tick's batch, or null when nothing was staged. */
	public SceneBatch sealBatch() {
		if (staged.isEmpty())
			return null;
		if (staged.size() > V2Wire.MAX_DELTAS)
			throw new IllegalStateException("Staged delta count exceeds wire cap: " + staged.size());
		seq++;
		SceneBatch batch = new SceneBatch(sceneId, epoch, seq, currentTick, staged);
		staged.clear();
		stagedWriteBytes = 0;
		stagedSubmitBytes = 0;
		stagedProgramBytes = 0;
		stagedMeshBytes = 0;
		return batch;
	}

	/**
	 * Sync snapshot: deep-copied state stamped with the current sequence number and tick,
	 * with texture bytes STRIPPED per the manifest-only snapshot contract (clients request
	 * bodies they lack; stripped textures arrive in the pending state). Snapshots are
	 * batch-boundary artifacts: taking one with staged-but-unsealed deltas would stamp
	 * state from batch N+1 with seq N, so it is refused. Call under the scene lock; the
	 * returned copy may be handed to any thread. Persistence DELIBERATELY rides this method
	 * (one codec, two duties — same batch-boundary and byte-stripping rules); bodies go to
	 * the ResourceStore separately.
	 */
	public SceneSnapshot snapshot() {
		if (!staged.isEmpty())
			throw new IllegalStateException("Seal the pending batch before snapshotting");
		// copyStructure, not copy: snapshots strip texture bytes anyway, so a deep copy would
		// clone every texture's megabytes purely to drop them on the next line — real waste
		// that streaming makes worse by taking snapshots more often.
		return new SceneSnapshot(sceneId, epoch, seq, currentTick, state.copyStructure());
	}
}
