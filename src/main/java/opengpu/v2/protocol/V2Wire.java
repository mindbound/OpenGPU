package opengpu.v2.protocol;

/**
 * Wire-level constants for protocol v2. Every id here is an explicit constant — nothing on
 * the wire may ever depend on enum ordinals or registration order (the legacy protocol's
 * central fragility). Changing PROTOCOL_VERSION is a hard compatibility break by design:
 * decoders reject anything else.
 */
public final class V2Wire {
	private V2Wire() {}

	// v3: mutable texture content. Region writes (DELTA_TEX_WRITE) ride the batch stream, a
	// monotone per-resource version replaces the content hash as the sync identity, and the
	// snapshot manifest / resource messages carry (version, knownHash).
	// v2 was: scene incarnation epoch in batch/snapshot/heartbeat headers.
	// Discipline: any layout change bumps this in the same change.
	/**
	 * Bumped 3 -> 4 on 2026-08-08 when OP_SET_FONT was added.
	 *
	 * Bumped 4 -> 5 on 2026-08-11 for transform parenting: SceneNode gained `parent`, which
	 * widened both the NodeCreate delta and the persisted node record. This is the FIRST bump
	 * that moved the persisted record rather than the op table, and it is the one the extension
	 * policy in DESIGN-RENDERER-V2 (§ Persistence & legacy migration) was written for. Version 4
	 * is therefore readable, not byte-identical: see SnapshotCodec's version gate and the
	 * justification attached to its entry in LAYOUT_COMPATIBLE_PERSISTED_VERSIONS. The op table
	 * did NOT change in this bump, which is the other half of the question that policy makes
	 * every bump answer.
	 *
	 * THE BUMP AND THE OP TABLE MUST MOVE TOGETHER, and that is the only thing protecting
	 * against silent corruption here. The decoder tests this for strict equality and rejects
	 * unknown ops outright, so a client of the wrong vintage discards the whole batch before
	 * it can misread anything — which is why the renderer can assert that unknown ops cannot
	 * arrive. Add an op without bumping this and that guarantee inverts: two builds share a
	 * version number, disagree about the op table, and an old client decodes the new op's
	 * argument as some other op's payload. See ProtocolVersionTest.
	 */
	/*
	 * Bumped 8 -> 9 on 2026-08-22 for ANIM-13(b): the heartbeat carries the server tick.
	 *
	 * THE CHEAPEST SHAPE A BUMP CAN HAVE, and worth naming as the counter-example to 5 and 8
	 * above. It touches neither the op table nor any PERSISTED record: the heartbeat is a
	 * transient message that no save ever contains. So v8 joins
	 * LAYOUT_COMPATIBLE_PERSISTED_VERSIONS unchanged — its layout IS v9's layout — and this bump
	 * differs from 7 -> 8, which moved the node record.
	 *
	 * IT STILL OWED A GOLDEN FIXTURE, and the first draft of this comment claimed it did not.
	 * The scoping reasoned that an unchanged layout has nothing to pin; the migration guardian
	 * refused the bump and was right. What a v8 fixture proves is not that the layout survived
	 * -- it did not move -- but that the v9 READER still ACCEPTS an 8, which is the entire risk
	 * of a bump like this one: forget to whitelist the outgoing version and every existing world
	 * answers by losing its scenes on first chunk load. See
	 * PersistedVersionMigrationTest.aV8StructureStillDecodesAfterTheHeartbeatGainedATick.
	 *
	 * Why it was spent at all: an animator scene is network-silent by design, and until now the
	 * only message such a scene received carried seq alone. ServerTimeline is fed only from an
	 * applied batch, so a silent scene's clock estimate froze at its last batch and free-ran on
	 * wall time; when a batch finally arrived past the 500 ms re-base threshold the timeline
	 * snapped and `time` stepped BACKWARD, which for a pure function of time means every
	 * animator on that scene pops with nothing to interpolate. One long on a message already
	 * being sent removes the free-run outright.
	 */
	/*
	 * Bumped 9 -> 10 on 2026-08-26 for Stage C's C1.1 — one bump carries everything 3D needs
	 * at the wire/persistence level, so no later Stage C increment pays another:
	 *
	 *  - Meshes: RES_MESH = 3 claimed; DELTA_MESH_CREATE (15) carries both blobs INLINE (the
	 *    program precedent, not the texture body protocol); the persisted resource record grows
	 *    a type-3 conditional tail. The vertex layout frozen here (stride 36: pos f32x3,
	 *    normal f32x3, uv f32x2, color u8x4 RGBA; u16 LE indices; triangle list) is FORMAT.
	 *  - 3D TRS: six new NodeProps bits (TZ, SZ, QX..QW), and the persisted node record appends
	 *    tz, sz, qx, qy, qz, qw after attachedWorldTime.
	 *  - The per-attachment uniform table: DELTA_UNIFORM_SET (16) and a NESTED snapshot section
	 *    after worldTimeAnchor.
	 *
	 * The op table did NOT move (no new canvas op).
	 *
	 * BatchCodec.MAX_INFLATED_BYTES moved 4 -> 8 MiB IN THIS BUMP, because the widest
	 * producible delta grew to a 15-bit NodeProps (129 B) and 32768 x 129 alone exceeds 4 MiB —
	 * see BatchSizeBoundTest for the full re-derivation. Raising the decoder ceiling is safe
	 * ONLY at a bump: an old jar refuses a v10 batch either at the inflate size check (before
	 * any version read) or at strict version equality — size first, then version — so no
	 * pre-raise decoder ever sees post-raise content.
	 *
	 * Persisted-format answer: v9 joins LAYOUT_COMPATIBLE_PERSISTED_VERSIONS (its layout is
	 * v8's; every v10 change is an append behind version >= 10 gates), and the v9 golden
	 * fixture + frozen v10 writers land in PersistedVersionMigrationTest in this same change.
	 */
	public static final short PROTOCOL_VERSION = 10;

	// Delta type ids
	public static final byte DELTA_NODE_CREATE = 1;
	public static final byte DELTA_NODE_FREE = 2;
	public static final byte DELTA_NODE_PROPS = 3;
	public static final byte DELTA_RES_CREATE = 4;
	public static final byte DELTA_RES_FREE = 5;
	public static final byte DELTA_CANVAS_PUBLISH = 6;
	public static final byte DELTA_CANVAS_APPEND = 7;
	/** Reserved for scene-level state (post-chain order, scene uniforms) — unused until Stage D. */
	public static final byte DELTA_SCENE_PROP = 8;
	/**
	 * Reserved ids for surface bind/unbind. NOT a pending payload — the prediction expired.
	 *
	 * This said the payload would "settle with the Stage A surface work". That work shipped, and
	 * settled it the other way: binding a scene to a screen is an OC callback pair on the GPU
	 * (bind/unbind) whose result is synced by the screen tile entity, not a scene delta. Nothing
	 * constructs either id, no decoder case reads one, and BatchCodec answers an arriving 9 or 10
	 * with "Unknown delta type".
	 *
	 * They stay reserved because the wire is append-only by id: handing 9 and 10 to something
	 * else would make a future build silently misread any batch an old one had produced. Corrected
	 * 2026-08-09 — the comment had been describing an obligation that no longer existed.
	 */
	public static final byte DELTA_BIND = 9;
	public static final byte DELTA_UNBIND = 10;
	/** Packed-RGBA region write into a texture (v3). Always carries its pixels. */
	public static final byte DELTA_TEX_WRITE = 11;
	/**
	 * OCSL program create/free (v6). The blob travels INLINE, like a canvas publish and unlike a
	 * texture body: a program is at most {@link opengpu.v2.ocsl.OcslWire#MAX_BLOB_BYTES} and a
	 * mirror cannot run an attached animator without it, so the out-of-band body protocol built
	 * for textures would add a pending state to the render path and buy nothing.
	 *
	 * CREATE carries the validator's verdict (stage, structural charge) alongside the bytes, so
	 * that the scene TABLES agree on both sides without either re-deriving them at apply time —
	 * a disagreement there could only diverge the tables. This does not weaken the rule that a
	 * client re-validates before it EXECUTES anything (IrCodec's class javadoc); the two happen at
	 * different moments and answer different questions.
	 */
	public static final byte DELTA_PROGRAM_CREATE = 12;
	public static final byte DELTA_PROGRAM_FREE = 13;
	/**
	 * Attach an OCSL program to a node, or detach with programId 0 (v7).
	 *
	 * ONE delta for both directions, and no separate detach id, because ANIM-17 makes a second
	 * attach an atomic REPLACE that succeeds — so every case is "this node's animator is now X",
	 * with 0 as a legal X. A detach delta would be a second spelling of that write and would need
	 * its own ordering rules against the replace.
	 *
	 * The referenced program need not exist: ANIM-17 rules free-while-attached legal and dangling
	 * (the resource case), so the applier does not resolve the id.
	 */
	public static final byte DELTA_NODE_ATTACH = 14;
	/**
	 * Mesh create (v10). BOTH blobs travel INLINE in this one delta — the program precedent
	 * ({@link #DELTA_PROGRAM_CREATE}), not the texture body protocol: a mesh is bounded by
	 * {@link #MAX_MESH_VERTEX_BYTES}+{@link #MAX_MESH_INDEX_BYTES}, and the out-of-band pending
	 * state would land on the C1.3 render path for nothing. There is deliberately NO mesh arm
	 * of DELTA_RES_CREATE, and a blob-less mesh record cannot come into being on any path: the
	 * decode arm and the apply arm both refuse {@link #RES_MESH} outright ("meshes arrive via
	 * DELTA_MESH_CREATE"), and the persisted resource loop refuses a type-3 record below
	 * version 10 ("meshes exist only from v10" — at v10+ it ACCEPTS one, reading the inline
	 * tail). The registry entry this delta creates is still an ordinary resource (type 3), so
	 * ResourceFree and resource enumeration are untouched.
	 *
	 * The layout below freezes the WIRE; how a client uploads is its own business — the
	 * expected shape is upload ONCE into the client's own VBO (meshes are static by design and
	 * VRAM-charged at declare), because Angelica's GLSM re-uploads client-array attributes per
	 * draw, which would pay the interleave four times per frame for nothing.
	 *
	 * The blobs' frozen layout — the WIRE format, independent of how any client uploads it:
	 * vertex record stride 36, little-endian: pos f32 x3 @0, normal f32 x3 @12, uv f32 x2 @24,
	 * color u8 x4 RGBA @32. Indices u16 little-endian, triangle list. Blob-interior integers
	 * are LITTLE-endian while every framed wire integer stays DataOutputStream big-endian —
	 * the two conventions meet at {@link #validateMeshBlobs}, which must read indices LE.
	 * (Lua 5.3 packs a vertex with string.pack("&lt;ffffffffBBBB", ...) — eight f's; the shipped
	 * big-endian '&gt;d' fallback in opengpu.lua is NOT this packer, and C1.4 owes a 5.2
	 * little-endian one. DESIGN:103's "pos, uv, color, normal" listing and PLAN-STAGE-C's
	 * "position, normal, uv, color" both predate this freeze and neither declared a wire
	 * order; THIS order is the normative one.)
	 */
	public static final byte DELTA_MESH_CREATE = 15;
	/**
	 * Per-attachment uniform table write (v10): nodeId, name, type byte, values, immediate.
	 *
	 * Keyed by node + NAME, not register: register binding is a client/program concern (C1.3),
	 * and name-keying survives ANIM-17's atomic program replace — entries outlive detach and
	 * re-attach; only NodeFree, scene reset and CLEAR remove them. Type byte: 0 = CLEAR
	 * (removes the entry, carries no values; clearing a missing entry throws, the frees'
	 * precedent), 1 = float, 2 = vec2, 3 = vec3, 4 = vec4 — the value count is the type.
	 * The {@code immediate} flag qualifies THIS TRANSITION, not the node (PROP_TELEPORT's
	 * doctrine): consumed at apply, never stored, never persisted.
	 */
	public static final byte DELTA_UNIFORM_SET = 16;

	// Node types
	public static final byte NODE_CANVAS = 1;
	public static final byte NODE_SPRITE = 2;
	public static final byte NODE_GROUP = 3;
	// Stage C (v10): both are ordinary nodes in the transform tree. A camera is a NODE so that
	// lookAt math, parenting and interpolation all reuse the one machine (DESIGN's decision).
	public static final byte NODE_MESH_INSTANCE = 4;
	public static final byte NODE_CAMERA = 5;

	// Resource types
	public static final byte RES_TEXTURE = 1;
	public static final byte RES_CANVAS = 2;
	/**
	 * Mesh (v10). Created ONLY by {@link #DELTA_MESH_CREATE} — see the refusal note there.
	 * Persisted fixed-width header convention, frozen: width = vertexCount (1..MAX by
	 * construction inside the existing 1..MAX_TEXTURE_DIM decode bound), height = 1,
	 * sizeBytes = vertexBytes + indexBytes, version = latestVersion = 1 on both sides (the
	 * blob travels inline; a mesh is never held at an older version), knownHashVersion = 1,
	 * knownHash = contentHash(vertexBlob || indexBlob). Counts are re-derived from the two
	 * length-prefixed tail blobs at decode and cross-checked against this header.
	 */
	public static final byte RES_MESH = 3;
	// Reserved: 4 = FONT.
	// 5 WAS "PROGRAM" and is now reserved-but-unclaimed: 3.1 decided against a program resource
	// type (SceneState.programs is its own table — ProgramInfo's javadoc gives the three code-level
	// reasons). The id stays burned rather than reused, because the wire is append-only by id.

	// Producer- AND consumer-side sanity caps. Enforced at seal/construction time as well as
	// decode time so an over-cap payload is impossible to produce, not a decode-time surprise.
	/**
	 * Deltas per batch.
	 *
	 * LOWERED 1&lt;&lt;16 -&gt; 1&lt;&lt;15 on 2026-08-09, because the claim directly above was false in the
	 * BYTE dimension. The count cap and the payload caps were each enforced, but nothing bounded
	 * their product against the decoder's ceiling: 65536 deltas x 81 B (a full-mask NodeProps,
	 * the widest delta a server can produce) is 5.3 MB, and with the write and submit
	 * per-batch allowances on top the worst case reached 5.6 MB against a
	 * BatchCodec.MAX_INFLATED_BYTES of 4 MiB. The server would have produced a batch that EVERY
	 * client refuses whole — a silent desync, not a crash, and invisible to convergence
	 * checking because no mirror ever applies it.
	 *
	 * NOT fixed by adding a byte check at seal, because ServerScene.sealBatch ALREADY has a
	 * seal-time check on this cap and it throws. A second one on a byte predicate would only
	 * reach the same throw by a different route; by seal the batch is assembled and the tick is
	 * spent, so the choices are crash the pump or send something known to be undecodable.
	 *
	 * What lowering the cap actually buys is the removal of a failure MODE. Sizing the two caps
	 * against each other leaves the loud failure (the existing IllegalStateException) as the only
	 * one, and deletes the silent one, where a batch ships and every mirror refuses it.
	 *
	 * The cost is a band that changes behaviour. Above 47,331 deltas a batch was undecodable, so
	 * (47331, 65536] silently desynced and is now caught; but (32768, 47331] previously WORKED
	 * and now throws. That band is unreachable in practice: MAX_NODES is 4096 and a node
	 * contributes at most a couple of deltas per tick, putting a pathological full-scene rewrite
	 * near 8192 — a 4x margin — against 143 B/tick and a 24,662 B largest batch in the heaviest
	 * bench2 run. BatchSizeBoundTest pins BOTH sides, so neither raising this into the
	 * undecodable range nor lowering it into the reachable one passes silently.
	 *
	 * Those two bounds left the window [32768, 47331] at v9's 4 MiB ceiling, with 1&lt;&lt;15 the
	 * only power of two in it — 1&lt;&lt;14 puts the pump's throw back in reach. RE-DERIVED at the
	 * 9 -&gt; 10 bump: the widest producible delta grew to a 15-bit NodeProps (129 B), which made
	 * the window at 4 MiB EMPTY (upper bound 17,526 &lt; the busy-tick lower bound 32,768) — that
	 * is why BatchCodec.MAX_INFLATED_BYTES moved to 8 MiB in the same change, where the window
	 * is [32768, 50040] and 1&lt;&lt;15 remains the only power of two in it. The value is derived
	 * rather than picked, which is why the tests assert a window and not a number.
	 */
	public static final int MAX_DELTAS = 1 << 15;
	public static final int MAX_COMMANDS = 1 << 20;
	public static final int MAX_SCENE_PROP_PAYLOAD = 1 << 20;
	/** Modified-UTF-8 keeps 3 bytes/char worst case: 8192 chars stays far under writeUTF's 65535-byte limit. */
	public static final int MAX_TEXT_CHARS = 8192;
	public static final int MAX_TEXTURE_DIM = 8192;

	/** One writeRegion payload: 64x64 RGBA. Producer cap AND decoder cap. */
	public static final int MAX_WRITE_REGION_BYTES = 16384;
	/**
	 * Aggregate texture-write payload admitted per scene per TICK. Equal to the per-call cap
	 * deliberately: one full-size write per tick is expressible and is the documented
	 * contract. Raising this is gated on batch compression landing — every number here is a
	 * function of whether that exists.
	 */
	public static final int MAX_WRITE_BYTES_PER_TICK = 16384;

	/**
	 * Texture-write payload one BATCH may carry. Twice the per-tick allowance, and the factor
	 * of two is structural rather than a safety margin.
	 *
	 * A batch can legitimately carry TWO ticks' worth of admitted payload. OC callbacks are
	 * {@code direct=true}, so they run on a machine executor thread, not the server thread — a
	 * write admitted in the window between the END-phase seal and the next START-phase grant is
	 * charged to tick T's allowance but staged into the batch that seals at the END of T+1.
	 * {@code stagedWriteBytes} resets at seal; {@code tickWriteBytes} resets at the tick change;
	 * they are deliberately different counters and the batch one therefore spans strictly more.
	 *
	 * Bounding a batch by ONE tick's number is short by construction, and the failure is not a
	 * throttle but a refusal the caller cannot clear by waiting: the retry lands in the same
	 * unsealed batch and is refused identically. The identical mistake on the submit path is
	 * what made a frame over 64 KiB undeliverable — see MAX_SUBMIT_BYTES_PER_BATCH.
	 *
	 * MUST move together with the decoder check in BatchCodec, which bounds a decoded batch by
	 * this same quantity. A producer admitting more than the decoder accepts loses the whole
	 * batch at every receiver rather than throttling anyone.
	 */
	public static final int MAX_WRITE_BYTES_PER_BATCH = 2 * MAX_WRITE_BYTES_PER_TICK;

	/**
	 * Packed command bytes ONE canvasSubmit call may carry. A per-CALL ceiling and nothing else.
	 *
	 * Its own bound, NOT the texture-write budget: these pace different things (command lists
	 * versus pixel payloads) and reusing a neighbouring concept's number is how this codebase
	 * has repeatedly produced bounds that were wrong for what they were bounding.
	 *
	 * It bounds one {@code checkByteArray} copy and one {@code decodeCommandList} allocation.
	 * It is also bracketed from below by the widest single command — an OP_DRAW_TEXT at
	 * MAX_TEXT_CHARS is ~24.6 KB, so the ceiling must clear several times that to be usable —
	 * and from above by the caller's own memory: the payload is a Lua string built with
	 * table.concat, needing the parts table and the result live at once, against 192 KiB of
	 * Lua RAM on a tier-1 machine.
	 *
	 * This constant used to serve as the per-tick and per-batch allowance as well. That is what
	 * made any frame larger than one submit undeliverable: the library chunks at this ceiling,
	 * so chunk 1 consumed the entire tick AND batch allowance and left less than one command's
	 * worth for chunk 2 — provably, for any op mix, with no contention. See PERF-BASELINE.md.
	 */
	public static final int MAX_SUBMIT_BYTES = 65536;

	/**
	 * Packed command bytes admitted per scene per TICK. The rate limiter.
	 *
	 * Sized to the MEASURED case rather than the theoretical maximum: the largest frame anyone
	 * has actually published is ~99 KB (3001 commands, PERF-BASELINE.md), which chunks to two
	 * submits. Two ceilings admits that in a single tick, so it lands in ONE batch — and
	 * SceneMirror.applyBatch applies a whole batch before setting {@code dirty}, so a frame that
	 * lands in one batch is never rendered half-drawn.
	 *
	 * Deliberately NOT sized to a full 4096-command canvas (~233 KB, four chunks). That frame
	 * still completes, over two ticks, via the admission retry the batch bound now makes work;
	 * it may show torn for one tick, which is the seal race that server-side frame assembly
	 * (DESIGN-RENDERER-V2 § scene.begin/commit) exists to close properly. Buying tear-freedom
	 * for a frame size nobody has published, by doubling the sustained bandwidth a single Lua
	 * program can drive, is the trade this project keeps getting wrong in the generous
	 * direction. Raise it when a measurement asks for it, which is cheap; lowering a shipped
	 * allowance is not.
	 *
	 * At 20 ticks/s this permits 2.6 MB/s per watcher per scene pre-DEFLATE.
	 */
	public static final int MAX_SUBMIT_BYTES_PER_TICK = 2 * MAX_SUBMIT_BYTES;

	/**
	 * Packed command bytes one BATCH may carry. Twice the per-tick allowance, for exactly the
	 * reason given on MAX_WRITE_BYTES_PER_BATCH: a batch spans up to two tick allowances because
	 * direct callbacks run off the server thread and can land in the inter-tick window.
	 *
	 * This is the constant whose absence produced the confirmed defect. With the batch bounded
	 * by one tick's worth, canvasSubmit's {@code consumeCallBudget} retry — which exists to make
	 * a refusal transparent to Lua — could not clear: at the next tick's synchronized replay
	 * {@code tickSubmitBytes} had reset at START but {@code stagedSubmitBytes} had not, because
	 * it only resets at the END-phase seal, which had not run yet. Verified in-game 2026-08-04.
	 */
	public static final int MAX_SUBMIT_BYTES_PER_BATCH = 2 * MAX_SUBMIT_BYTES_PER_TICK;

	/**
	 * Encoded command bytes a scene may HOLD across all its canvases at once.
	 *
	 * MAX_SUBMIT_BYTES paces the rate commands arrive; this bounds the standing total, and the
	 * two are genuinely different quantities. Nothing bounded the total before canvasSubmit
	 * existed, but only because it was vacuous: no code path could put a command into a
	 * non-display canvas, so every offscreen canvas was provably empty and the display
	 * canvas's own commandCap was the whole story.
	 *
	 * The standing total is what a RESYNC SNAPSHOT carries, in full, to every client entering
	 * range — and what the save writes to disk on every autosave. The VRAM budget does bound
	 * it, but only at ~26 MB, because CANVAS_SLOT_COST prices a slot at 32 bytes as a proxy
	 * for the client's FBO while a filled slot costs up to 57 bytes on the wire (and an
	 * OP_DRAW_TEXT slot far more). Pricing VRAM by wire cost would be the same borrowed-bound
	 * mistake in the other direction, so the wire total gets its own explicit budget.
	 *
	 * 2 MiB: comfortably over any plausible real scene (a full 4096-command canvas of the
	 * widest op is ~233 KB). (A first draft said the snapshot "must also satisfy
	 * MAX_INFLATED_BYTES" — false: nothing routes a snapshot through BatchCodec; SnapshotCodec
	 * bounds its encode by FrameChunker.MAX_TRANSFER_BYTES, its own ceiling.)
	 */
	public static final int MAX_STANDING_COMMAND_BYTES = 2 * 1024 * 1024;

	/**
	 * Program blob bytes one BATCH may carry, producer- and decoder-side.
	 *
	 * The scene's 256 KiB ledger does NOT stand in for this, and assuming it did was the hole this
	 * constant closes: {@code freeProgram} releases ledger bytes, so a create/free loop inside one
	 * tick stages unbounded ProgramCreate deltas while the live total never exceeds one blob. Both
	 * other byte-carrying deltas already keep a per-batch counter for exactly this reason
	 * ({@link #MAX_WRITE_BYTES_PER_BATCH}, {@link #MAX_SUBMIT_BYTES_PER_BATCH}); the program delta
	 * shipped without one.
	 *
	 * What that cost, precisely: {@code MAX_DELTAS} (32768) and the ledger both stay far from their
	 * caps while the batch grows past {@code BatchCodec.MAX_INFLATED_BYTES} (4 MiB), so the server
	 * builds a batch EVERY client refuses whole — a silent desync no convergence check can see,
	 * because no mirror ever applies it. That is the same failure MAX_DELTAS was lowered from
	 * 1&lt;&lt;16 to close, arriving on a new axis.
	 *
	 * Sized at 2x the ledger, the same "a batch spans up to two tick allowances" reasoning
	 * MAX_WRITE_BYTES_PER_BATCH is built on: the most a legitimate caller needs in one batch is to
	 * populate the whole program table, and churn beyond that is not traffic worth carrying. The
	 * batch caps keep an 8x margin between the LARGEST allowance and the decoder ceiling: at v10
	 * that pair is MAX_MESH_BYTES_PER_BATCH (1 MiB) against 8 MiB; this 512 KiB sits at 16x.
	 */
	public static final int MAX_PROGRAM_BYTES_PER_BATCH = 2 * 256 * 1024;

	// ---- Mesh format caps (v10). FORMAT IDENTITY, decoder-enforced: these move only with a
	// PROTOCOL_VERSION bump, unlike ServerScene.MAX_MESH_BYTES (admission-only ledger). ----
	/** Vertex record stride: pos f32 x3, normal f32 x3, uv f32 x2, color u8 x4 RGBA. Frozen. */
	public static final int MESH_VERTEX_STRIDE = 36;
	/** Index width: u16 little-endian. Frozen; see the u16-sufficiency pin in MeshBoundTest. */
	public static final int MESH_INDEX_BYTES = 2;
	/**
	 * Per-mesh vertex blob cap: 192 KiB = 5461 vertices.
	 *
	 * 192 rather than 256 KiB so the scene ledger fits TWO maximum-size meshes — the
	 * discriminating arithmetic divides the ledger by a FULL mesh (vertex + index caps):
	 * 524288 / (196608 + 65536) = 2, where 256 KiB gives 524288 / (262144 + 65536) = 1 —
	 * failing ProgramLedgerBoundTest's usability floor: a ledger that admits fewer than 2
	 * maximum-size items is unusable rather than protective. 5461 &lt; 65536 is also what makes
	 * u16 indices sufficient FOREVER under this cap.
	 */
	public static final int MAX_MESH_VERTEX_BYTES = 192 * 1024;
	/**
	 * Per-mesh index blob cap: 64 KiB = 32768 u16 slots; the largest LEGAL list is 32766
	 * indices = 10922 triangles (the validator's %3 rule makes the last two slots unreachable).
	 */
	public static final int MAX_MESH_INDEX_BYTES = 64 * 1024;
	/**
	 * Mesh blob bytes one BATCH may carry — 2x the ServerScene.MAX_MESH_BYTES ledger, the
	 * create/free-loop reasoning of {@link #MAX_PROGRAM_BYTES_PER_BATCH} verbatim: freeing
	 * releases ledger bytes, so churn inside one batch is bounded here, not by the ledger.
	 */
	public static final int MAX_MESH_BYTES_PER_BATCH = 2 * 512 * 1024;

	/**
	 * The one shared mesh-blob validator — called from all four sites (BatchCodec decode,
	 * SnapshotCodec's type-3 tail, DeltaApplier's backstop, createMesh admission) so the
	 * format has exactly one spelling. Throws IllegalArgumentException; callers wrap.
	 *
	 * Floors are explicit (one vertex, one triangle): the divisibility checks alone would admit
	 * the empty mesh vacuously, and every sibling resource path refuses zero extent. Indices
	 * are read LITTLE-endian — blob-interior convention, not the frame's big-endian one.
	 */
	public static void validateMeshBlobs(byte[] vertexBytes, byte[] indexBytes) {
		if (vertexBytes == null || indexBytes == null)
			throw new IllegalArgumentException("Mesh blobs must be present.");
		if (vertexBytes.length > MAX_MESH_VERTEX_BYTES)
			throw new IllegalArgumentException("Vertex blob " + vertexBytes.length + " B exceeds "
					+ MAX_MESH_VERTEX_BYTES + ".");
		if (indexBytes.length > MAX_MESH_INDEX_BYTES)
			throw new IllegalArgumentException("Index blob " + indexBytes.length + " B exceeds "
					+ MAX_MESH_INDEX_BYTES + ".");
		if (vertexBytes.length < MESH_VERTEX_STRIDE)
			throw new IllegalArgumentException("Vertex blob smaller than one vertex.");
		if (indexBytes.length < 3 * MESH_INDEX_BYTES)
			throw new IllegalArgumentException("Index blob smaller than one triangle.");
		if (vertexBytes.length % MESH_VERTEX_STRIDE != 0)
			throw new IllegalArgumentException("Vertex blob " + vertexBytes.length
					+ " B is not a multiple of the " + MESH_VERTEX_STRIDE + "-byte stride.");
		if (indexBytes.length % MESH_INDEX_BYTES != 0)
			throw new IllegalArgumentException("Index blob " + indexBytes.length
					+ " B is not a multiple of " + MESH_INDEX_BYTES + ".");
		int indexCount = indexBytes.length / MESH_INDEX_BYTES;
		if (indexCount % 3 != 0)
			throw new IllegalArgumentException("Index count " + indexCount
					+ " is not a multiple of 3 (triangle list).");
		int vertexCount = vertexBytes.length / MESH_VERTEX_STRIDE;
		for (int i = 0; i < indexBytes.length; i += 2) {
			int index = (indexBytes[i] & 0xFF) | ((indexBytes[i + 1] & 0xFF) << 8);
			if (index >= vertexCount)
				throw new IllegalArgumentException("Index " + index + " at position " + (i / 2)
						+ " out of range for " + vertexCount + " vertices.");
		}
	}

	/** Vertex count implied by a validated vertex blob. */
	public static int meshVertexCount(byte[] vertexBytes) {
		return vertexBytes.length / MESH_VERTEX_STRIDE;
	}

	/** Self-describing persisted body blob: 'OGPB'. */
	public static final int PERSIST_BODY_MAGIC = 0x4F475042;
	public static final short PERSIST_BODY_FORMAT = 1;

	public static boolean isKnownNodeType(byte type) {
		return type == NODE_CANVAS || type == NODE_SPRITE || type == NODE_GROUP
				|| type == NODE_MESH_INSTANCE || type == NODE_CAMERA;
	}

	public static boolean isKnownResType(byte type) {
		return type == RES_TEXTURE || type == RES_CANVAS || type == RES_MESH;
	}

	// Node property mask bits (NodeProps delta)
	public static final int PROP_X = 1;
	public static final int PROP_Y = 1 << 1;
	public static final int PROP_ROT = 1 << 2;
	public static final int PROP_SX = 1 << 3;
	public static final int PROP_SY = 1 << 4;
	public static final int PROP_Z = 1 << 5;
	public static final int PROP_VISIBLE = 1 << 6;
	public static final int PROP_TINT = 1 << 7;
	/**
	 * "This transform change is a JUMP — do not interpolate it."
	 *
	 * Carries a 0/1 value like PROP_VISIBLE rather than being a valueless flag, because the
	 * codec derives the value count from Integer.bitCount(mask); a bit without a value would
	 * desynchronise every field after it.
	 *
	 * Transient by nature: it describes a transition, not state, so nothing stores it on
	 * SceneNode and it never reaches a save — snapshots encode node VALUES, not deltas. That
	 * is why widening the mask needs no PROTOCOL_VERSION bump and no save migration, the same
	 * reasoning as the compression sentinel in BatchCodec.
	 */
	public static final int PROP_TELEPORT = 1 << 8;
	// ---- 3D TRS bits (v10). One double per bit, ascending bit order — the invariant every
	// bit here inherits from the codec's Integer.bitCount derivation. ----
	public static final int PROP_TZ = 1 << 9;
	public static final int PROP_SZ = 1 << 10;
	/**
	 * The rot3d quaternion rides FOUR bits (one double per bit is load-bearing, see
	 * PROP_TELEPORT), and they are ALL-OR-NONE: a mask carrying some but not all of QX..QW is
	 * rejected at construction AND at decode — a partial quaternion is not a rotation.
	 * Component order when present: ascending bit order, so qx, qy, qz, qw.
	 */
	public static final int PROP_QX = 1 << 11;
	public static final int PROP_QY = 1 << 12;
	public static final int PROP_QZ = 1 << 13;
	public static final int PROP_QW = 1 << 14;
	/** The four quaternion bits together; masks must carry all or none of this. */
	public static final int QUAT_PROPS_MASK = PROP_QX | PROP_QY | PROP_QZ | PROP_QW;
	/** Every defined property bit; masks carrying anything else are rejected outright. */
	public static final int KNOWN_PROPS_MASK = 0x7FFF;

	// Canvas op ids (v2 replaces CommandEnum; the Transelate typo dies here)
	public static final byte OP_FILL = 1;
	public static final byte OP_PLOT = 2;
	public static final byte OP_LINE = 3;
	public static final byte OP_RECT = 4;
	public static final byte OP_FILL_RECT = 5;
	public static final byte OP_TRIANGLE = 6;
	public static final byte OP_FILL_TRIANGLE = 7;
	public static final byte OP_OVAL = 8;
	public static final byte OP_FILL_OVAL = 9;
	public static final byte OP_CLEAR_RECT = 10;
	public static final byte OP_DRAW_TEXT = 11;
	public static final byte OP_DRAW_TEXTURE = 12;
	public static final byte OP_DRAW_TEXTURE_SUB = 13;
	public static final byte OP_SET_COLOR = 14;
	public static final byte OP_TRANSLATE = 15;
	public static final byte OP_ROTATE = 16;
	public static final byte OP_ROTATE_AROUND = 17;
	public static final byte OP_SCALE = 18;
	public static final byte OP_PUSH = 19;
	public static final byte OP_POP = 20;
	public static final byte OP_ORIGIN = 21;
	/**
	 * Select the font subsequent OP_DRAW_TEXT commands use. Ambient state with exactly
	 * OP_SET_COLOR's lifecycle: unscoped by PUSH/POP, and reset to {@link #FONT_DEFAULT} at
	 * the start of every canvas replay. The reset is load-bearing — without it a canvas that
	 * selected a font would leak it into whichever canvas replayed next, so the symptom would
	 * follow node draw order and read as nondeterministic.
	 */
	public static final byte OP_SET_FONT = 22;

	/**
	 * GNU Unifont, read at runtime from OpenComputers. 8x16 cells, ~75,000 glyphs covering
	 * the Basic Multilingual Plane and beyond. The default because it is the one that can
	 * render an arbitrary string.
	 */
	public static final byte FONT_DEFAULT = 0;
	/**
	 * unscii-8, bundled. 8x8 cells, ~3,190 glyphs: complete Box Drawing, Block Elements,
	 * Geometric Shapes and Braille, and NO CJK, kana or Hangul whatsoever. Half the cell
	 * height, and drawn for an 8px box rather than scaled down from 16.
	 */
	public static final byte FONT_UNSCII8 = 1;
	/** Ids are dense from 0; anything at or above this is rejected rather than clamped. */
	public static final int FONT_COUNT = 2;

	public static boolean isValidFont(int fontId) {
		return fontId >= 0 && fontId < FONT_COUNT;
	}

	/**
	 * Numeric argument count per canvas op. DRAW_TEXT additionally carries a UTF string.
	 * Index = op id; -1 marks an invalid op.
	 */
	private static final int[] CANVAS_OP_ARGS = new int[] {
		-1, // 0 unused
		0,  // FILL
		2,  // PLOT x,y
		4,  // LINE
		4,  // RECT
		4,  // FILL_RECT
		6,  // TRIANGLE
		6,  // FILL_TRIANGLE
		4,  // OVAL
		4,  // FILL_OVAL
		4,  // CLEAR_RECT
		2,  // DRAW_TEXT x,y (+ UTF)
		3,  // DRAW_TEXTURE id,x,y
		7,  // DRAW_TEXTURE_SUB id,x,y,tx,ty,w,h
		4,  // SET_COLOR r,g,b,a
		2,  // TRANSLATE
		1,  // ROTATE
		3,  // ROTATE_AROUND
		2,  // SCALE
		0,  // PUSH
		0,  // POP
		0,  // ORIGIN
		1,  // SET_FONT fontId
	};

	public static int canvasOpArgCount(int op) {
		if (op <= 0 || op >= CANVAS_OP_ARGS.length)
			return -1;
		return CANVAS_OP_ARGS[op];
	}

	public static boolean isTransformOp(int op) {
		return op == OP_TRANSLATE || op == OP_ROTATE || op == OP_ROTATE_AROUND
				|| op == OP_SCALE || op == OP_PUSH || op == OP_POP || op == OP_ORIGIN;
	}

	/**
	 * Wraparound-safe sequence comparison (RFC 1982 style): positive when a is newer than b.
	 */
	public static int seqDelta(int a, int b) {
		return a - b;
	}

	/** FNV-1a 64-bit content hash for resource bytes. */
	public static long contentHash(byte[] data) {
		long hash = 0xcbf29ce484222325L;
		for (int i = 0; i < data.length; i++) {
			hash ^= (data[i] & 0xff);
			hash *= 0x100000001b3L;
		}
		return hash;
	}
}
