package opengpu.v2.ocsl;

/**
 * THE RESERVED REGISTER/ID TABLE, PER SURFACE — the Stage B deliverable the design names "before
 * any blob is written".
 *
 * Two namespaces live here and the split between them is the whole point.
 *
 * REGISTER IDS. The index space an operand names is laid out in three fixed blocks:
 * <pre>
 *   0  .. 47   built-in input registers   (reserved for EVERY surface, applicability per surface)
 *     0  ..  9   the shared inputs: uv, position, tint, time, … timePeriod, normal
 *     10 .. 15   light parameters         (Stage C/D, block claimed, ids unassigned)
 *     16 .. 47   the ANIMATOR block       (ANIM-2: 16..24 own, 25..26 per-node, 27..35 parent)
 *   48 .. 111  user uniforms              (64 slots, declaration order)
 *   112 ..     working registers          (written by ops)
 * </pre>
 * <b>These numbers moved on 2026-08-12</b> and this diagram did not, for long enough to be worth
 * recording: it still read 0..31 / 32..95 / 96.. while the constants forty lines below said
 * otherwise. The generated {@code surface-tables.txt} was right the whole time — it is derived —
 * so the freeze test stayed green while the prose a human actually reads was wrong, which is a
 * neat demonstration that a generated artifact does not protect the hand-written text beside it.
 * User uniforms deliberately do NOT begin immediately after whatever built-ins a surface happens
 * to use. That was the specific trap the design called out: pack them tight and every surface has a
 * different uniform base, adding a built-in shifts every uniform id in every saved blob, and a
 * later fill-in like {@code normal} collides with a uniform. A fixed base costs a few unused
 * indices and buys ids that never move.
 *
 * Built-in ids are reserved ACROSS surfaces, not per surface — {@code uv} is id 0 everywhere it
 * exists, and a surface that has no {@code uv} simply leaves id 0 unreadable rather than reusing
 * it for something else. One id, one meaning, forever; the validator rejects a read of an id its
 * stage does not have.
 *
 * PROPERTY IDS are a separate u8 namespace per surface, naming what {@code OUT} may write. The
 * pixel family has exactly one row.
 */
public final class SurfaceTable {

	// ---------------------------------------------------------------- register blocks

	/**
	 * WIDENED 2026-08-12, while it was still free, and it will never be free again.
	 *
	 * Assigning ANIM-2's reservation set revealed that the animator block as first drawn (16..32)
	 * held it EXACTLY — 7 readable raw properties, `nodeSeed`, `sinceAttach`, and a 7-id parent read
	 * block is 16 of 16 — with no room for Stage C's 3D `tz`/`sz` reads or their parent
	 * counterparts. The block had been sized before the amendment enumerated what had to go in it.
	 *
	 * So the built-in span is now 0..48 and the animator block 16..48. This moves `UNIFORM_BASE`
	 * and `WORKING_BASE`, which renumbers every uniform and working register — a change that is
	 * costless today and a format-version bump plus an NBT migration after the first surface ships,
	 * because `FORMAT_VERSION` is 0 and {@link IrCodec} refuses format 0 from any persisted source.
	 * There is no blob anywhere to invalidate. That window is the whole reason to do it now.
	 *
	 * The uniform block keeps its 64 slots. The working span drops 416 → 400 — and the first draft
	 * of this note claimed that "cannot bind, since every op allocates at most one register", which
	 * is FALSE and was measured false. {@code FOR} allocates a register and charges ZERO
	 * ({@code OcslWire} gives it {@code hasDst=true} with {@code structuralCharge=0}, because the
	 * structural count is post-unroll and unrolled GLSL emits nothing for loop control), so a
	 * program of {@code FOR 1 / ADD / ENDFOR} triples allocates two registers per charged op. 199
	 * such triples validate at 201 structural ops using 399 working registers; 200 are refused for
	 * declaring 513. The register span therefore binds at around 200 charged ops, and this widening
	 * cost <b>16 reachable working registers</b>. Not a format break — nothing has been written —
	 * but the next person judging whether a widening is free should not inherit the wrong reason.
	 */
	public static final int BUILTIN_BASE = 0;
	public static final int BUILTIN_LIMIT = 48;
	public static final int UNIFORM_BASE = 48;
	public static final int UNIFORM_LIMIT = 112;
	public static final int WORKING_BASE = 112;

	public static final int MAX_UNIFORMS = UNIFORM_LIMIT - UNIFORM_BASE;

	// ---------------------------------------------------------------- built-in register ids
	// Assigned once, here, for every surface that will ever carry them. Ids with no
	// implementation yet are reserved and left unreadable rather than omitted: an id assigned
	// later collides with blobs already written under a guess, which is the failure the whole
	// reservation discipline exists to prevent.

	public static final int REG_UV = 0;
	public static final int REG_POSITION = 1;
	public static final int REG_TINT = 2;
	public static final int REG_TIME = 3;
	public static final int REG_INPUT_TEXEL_SIZE = 4;
	public static final int REG_RESOLUTION = 5;
	public static final int REG_NODE_SIZE = 6;
	public static final int REG_OUTPUT_RESOLUTION = 7;
	/**
	 * The `time` wrap period P. RESERVED AND UNREADABLE — the id is taken now, the type comes
	 * later, and the split is the whole point.
	 *
	 * The design assigns this reservation to this table by name, three times over, as the
	 * substitute guard for the one deferred obligation neither code gate covers: the animator
	 * tripwire does not cover material/effect/post, and they read `time` today. Without a register
	 * carrying P, a program wanting a seam-continuous expression must bake `1/P` into its constant
	 * pool — freezing a CONTRACT CONSTANT into every saved blob, which the caps-monotonicity rule
	 * does not cover because P is not a cap. Reserving the id costs nothing and is the half the
	 * animator dry run said "cannot follow"; the VALUE of P may (it is still unpublished).
	 *
	 * Left unreadable until P is published, following {@link #REG_NORMAL}'s precedent: a readable
	 * register would advertise a value nothing can supply.
	 */
	public static final int REG_TIME_PERIOD = 8;
	/** Stage C/D fill-in. Reserved now; no surface reads it in v1. */
	public static final int REG_NORMAL = 9;
	/** Stage C/D light parameters, reserved as a block so they land contiguously. */
	public static final int REG_LIGHT_BASE = 10;
	public static final int REG_LIGHT_LIMIT = 16;
	/**
	 * The animator surface's input registers — ASSIGNED 2026-08-12 (ANIM-2), still UNREADABLE.
	 *
	 * The ids are frozen here and published in {@code surface-tables.txt}; nothing reads them,
	 * because {@link #builtinType} returns null for {@code STAGE_ANIMATOR} and {@link IrCodec}
	 * still refuses the stage by name. That split is deliberate and is exactly what ANIM-2 costs:
	 * "a table; zero ops". The surface opens when the amendments that give these registers MEANING
	 * land — composition (ANIM-3), time (ANIM-4), lifecycle (ANIM-15/17) — and opening it is a
	 * behaviour change, not an id change, because the ids are settled now.
	 *
	 * Per ANIM-7 the readable value is the RAW server-set property, never the animator-composed one
	 * (self-referential for an owned property) and never the interpolated display transform (that
	 * is the composition base, renderer behaviour, carrying no purity claim). The consequence is
	 * documented rather than hidden: a program reading {@code x} reads a value up to one
	 * interpolation window out of step with the base its output lands on, so <b>{@code x} is not
	 * "where I am drawn"</b>.
	 */
	public static final int REG_ANIMATOR_BASE = 16;

	/** The node's own raw server-set TRS and tint, in the same order as the property table. */
	public static final int REG_ANIM_X = 16;
	public static final int REG_ANIM_Y = 17;
	public static final int REG_ANIM_SX = 18;
	public static final int REG_ANIM_SY = 19;
	public static final int REG_ANIM_ROT2D = 20;
	/** vec4. A quaternion at the wire/API level; a SEPARATE id from rot2d so every id has one type. */
	public static final int REG_ANIM_ROT3D = 21;
	/** vec4, RGBA, 0..1 — bit-for-bit the pixel stage's tint shape. ARGB packing never reaches IR. */
	public static final int REG_ANIM_TINT = 22;

	/**
	 * A stable bit-mix of the server-allocated node id: replicated by construction, pure, zero ops.
	 *
	 * This is what makes a curated preset de-phaseable with no authoring and no Lua call. Without
	 * it a preset attached to a field of nodes runs in exact lockstep — ANIM-2's "200 debris sprites
	 * shaking on the same frame" — and both workarounds are defective: baking a position read in
	 * makes the preset permanently position-de-phased and identical for co-located nodes, and a
	 * per-node `phase` uniform costs N set-calls against the design's own "animating N nodes must
	 * not cost N direct calls" rule.
	 */
	/** Stage C's 3D translate/scale on the z axis — contiguous with the node's own 2D TRS above. */
	public static final int REG_ANIM_TZ = 23;
	public static final int REG_ANIM_SZ = 24;
	/** One past the node's own property reads: [16, 25). */
	public static final int REG_ANIM_OWN_LIMIT = 25;

	public static final int REG_ANIM_NODE_SEED = 25;

	/**
	 * {@code min(renderClock − the replicated tick this attachment became active, CAP)} — ANIM-6.
	 *
	 * RESERVED, and the amendment's own framing is why: either answer was cheap on 2026-08-12 and
	 * only one stays available after the table is published. Host-computed from state the design
	 * already persists, so purity is untouched — exactly as external an input as {@code time}, and
	 * every client derives the same value from the same replicated stamp. SATURATING is the
	 * load-bearing word: monotone AND settling, the one property a wrapped clock structurally
	 * cannot have. Refusing it would have meant striking "easing" from the design; reserving it
	 * costs one id and no ops, and turns ease/one-shot/decay into 3–5 op programs.
	 */
	public static final int REG_ANIM_SINCE_ATTACH = 26;

	/**
	 * Parent property reads — ONE nesting level, a CONTIGUOUS fixed-size block, no traversal.
	 *
	 * Fixed-size and one level on purpose: a traversal would make a program's cost depend on scene
	 * shape, which no static op count could express.
	 *
	 * CONTIGUOUS is a correction, made while the ids were still free. The first draft put the
	 * parent's 2D properties at 25..31 and then the NODE'S OWN `tz`/`sz` at 32..33 before the
	 * parent's at 34..35 — so the parent range had the node's own registers sitting inside it. Host
	 * fill code writing the natural {@code for (i) frame[PARENT_BASE + i] = parentProps[i]} would
	 * have landed the parent's 8th and 9th values on the node's own z-translate and z-scale: a node
	 * whose own depth silently became its parent's, with nothing to catch it. The block now runs
	 * [27, 36) in the same order as the node's own [16, 25), so index i means the same property in
	 * both, and {@link #REG_ANIM_PARENT_LIMIT} exists so the loop bound is a constant rather than
	 * arithmetic on the last member.
	 */
	public static final int REG_ANIM_PARENT_BASE = 27;
	public static final int REG_ANIM_PARENT_X = 27;
	public static final int REG_ANIM_PARENT_Y = 28;
	public static final int REG_ANIM_PARENT_SX = 29;
	public static final int REG_ANIM_PARENT_SY = 30;
	public static final int REG_ANIM_PARENT_ROT2D = 31;
	public static final int REG_ANIM_PARENT_ROT3D = 32;
	public static final int REG_ANIM_PARENT_TINT = 33;
	public static final int REG_ANIM_PARENT_TZ = 34;
	public static final int REG_ANIM_PARENT_SZ = 35;
	public static final int REG_ANIM_PARENT_LIMIT = 36;

	/** 36..47 stay unassigned: headroom bought while it was free. */
	public static final int REG_ANIMATOR_LIMIT = 48;

	// ---------------------------------------------------------------- slots
	// Sampler bindings are their own namespace. Slot 0 is the built-in `input` sampler at the
	// effect and post surfaces -- the node's content or the previous chain entry's output -- and
	// is exempt from the "slots bind scene resources only" rule.

	public static final int SLOT_INPUT = 0;
	public static final int MAX_SLOTS = 16;

	/**
	 * A5's two provisional caps, and they live HERE because A5 welded them to this deliverable:
	 * of every cap in the design, these are the ones that "cannot be added later". Everything else
	 * bounds what a program may DO and can be raised under the monotonicity rule; these bound the
	 * shape of the frame a blob describes, and once blobs exist a frame wider than the runtime
	 * preallocates has no safe reading.
	 *
	 * Generous on purpose — the monotonicity rule makes raising safe and tightening a format
	 * break, so a provisional value should err high. The codec's own MAX_REGISTERS is a separate,
	 * structural bound on what a blob may DECLARE; this is the semantic one, checked against what
	 * the program actually lays out.
	 */
	public static final int MAX_REGISTERS = 512;
	public static final int MAX_FRAME_WIDTH = 1024;

	private SurfaceTable() {}

	/**
	 * The type a built-in register carries, or null when this stage does not have it.
	 *
	 * The validator rejects a read of a null, which is what "the validator rejects
	 * stage-inapplicable reads" means in code.
	 */
	public static OcslType builtinType(byte stage, int reg) {
		switch (stage) {
			case OcslWire.STAGE_PIXEL_MATERIAL:
				// material -> uv, position, tint, time, uniforms, slots, normal (Stage C/D)
				switch (reg) {
					case REG_UV: return OcslType.VEC2;
					case REG_POSITION: return OcslType.VEC2;
					case REG_TINT: return OcslType.VEC4;
					case REG_TIME: return OcslType.FLOAT;
					default: return null;
				}
			case OcslWire.STAGE_PIXEL_EFFECT:
				// node effect -> the material set + input sampler + inputTexelSize + node quad size
				switch (reg) {
					case REG_UV: return OcslType.VEC2;
					case REG_POSITION: return OcslType.VEC2;
					case REG_TINT: return OcslType.VEC4;
					case REG_TIME: return OcslType.FLOAT;
					case REG_INPUT_TEXEL_SIZE: return OcslType.VEC2;
					case REG_NODE_SIZE: return OcslType.VEC2;
					default: return null;
				}
			case OcslWire.STAGE_PIXEL_POST:
				// post-chain -> uv + input sampler + inputTexelSize + resolution + time + uniforms.
				// `uv` is here because without it no post effect was expressible at all: nothing
				// carried the fragment coordinate and sample() has no implicit current-fragment
				// form, so two-pass blur -- the thing ping-pong FBOs exist for -- could not be
				// written. Deliberately NO tint and NO position: a post pass has no node.
				switch (reg) {
					case REG_UV: return OcslType.VEC2;
					case REG_TIME: return OcslType.FLOAT;
					case REG_INPUT_TEXEL_SIZE: return OcslType.VEC2;
					case REG_RESOLUTION: return OcslType.VEC2;
					default: return null;
				}
			case OcslWire.STAGE_BAKE:
				// bake -> uv, output resolution, uniforms, slots. NO time and NO tint, because a
				// bake runs once into a canonical texture and must be re-runnable to the same
				// bytes; a clock or an ambient colour would make it neither.
				switch (reg) {
					case REG_UV: return OcslType.VEC2;
					case REG_OUTPUT_RESOLUTION: return OcslType.VEC2;
					default: return null;
				}
			default:
				return null;
		}
	}

	/** Human name for a built-in id, for error messages. Never reaches emitted shader text. */
	public static String builtinName(int reg) {
		switch (reg) {
			case REG_UV: return "uv";
			case REG_POSITION: return "position";
			case REG_TINT: return "tint";
			case REG_TIME: return "time";
			case REG_INPUT_TEXEL_SIZE: return "inputTexelSize";
			case REG_RESOLUTION: return "resolution";
			case REG_NODE_SIZE: return "nodeSize";
			case REG_OUTPUT_RESOLUTION: return "outputResolution";
			// Reserved-and-unreadable ids still need names. Without this case the validator's
			// diagnostics called register 8 `builtin8` while every design document, the roadmap and
			// the frozen table call it `timePeriod` -- the id whose whole purpose is to be referred
			// to before anything reads it.
			case REG_TIME_PERIOD: return "timePeriod";
			case REG_NORMAL: return "normal";
			// The animator block. Named while still unreadable, because the frozen table publishes
			// them and a reserved id whose name is "builtin23" is a reservation nobody can cite.
			case REG_ANIM_X: return "anim.x";
			case REG_ANIM_Y: return "anim.y";
			case REG_ANIM_SX: return "anim.sx";
			case REG_ANIM_SY: return "anim.sy";
			case REG_ANIM_ROT2D: return "anim.rot2d";
			case REG_ANIM_ROT3D: return "anim.rot3d";
			case REG_ANIM_TINT: return "anim.tint";
			case REG_ANIM_NODE_SEED: return "anim.nodeSeed";
			case REG_ANIM_SINCE_ATTACH: return "anim.sinceAttach";
			case REG_ANIM_PARENT_X: return "anim.parent.x";
			case REG_ANIM_PARENT_Y: return "anim.parent.y";
			case REG_ANIM_PARENT_SX: return "anim.parent.sx";
			case REG_ANIM_PARENT_SY: return "anim.parent.sy";
			case REG_ANIM_PARENT_ROT2D: return "anim.parent.rot2d";
			case REG_ANIM_PARENT_ROT3D: return "anim.parent.rot3d";
			case REG_ANIM_PARENT_TINT: return "anim.parent.tint";
			case REG_ANIM_TZ: return "anim.tz";
			case REG_ANIM_SZ: return "anim.sz";
			case REG_ANIM_PARENT_TZ: return "anim.parent.tz";
			case REG_ANIM_PARENT_SZ: return "anim.parent.sz";
			default: return "builtin" + reg;
		}
	}

	/** Whether this stage has the built-in {@code input} sampler at {@link #SLOT_INPUT}. */
	public static boolean hasInputSampler(byte stage) {
		return stage == OcslWire.STAGE_PIXEL_EFFECT || stage == OcslWire.STAGE_PIXEL_POST;
	}

	/**
	 * The type a property expects from its {@code OUT}, or null when this stage has no such
	 * property. A1 makes this check possible at all: every OUT names its property, so the type is
	 * decidable at {@code createProgram} rather than at attach.
	 */
	public static OcslType propertyType(byte stage, int propertyId) {
		switch (stage) {
			case OcslWire.STAGE_PIXEL_MATERIAL:
			case OcslWire.STAGE_PIXEL_EFFECT:
			case OcslWire.STAGE_PIXEL_POST:
			case OcslWire.STAGE_BAKE:
				// The pixel family's whole property table: one row.
				return propertyId == OcslWire.PROP_COLOR ? OcslType.VEC4 : null;
			case OcslWire.STAGE_ANIMATOR:
				return animatorPropertyType(propertyId);
			default:
				return null;
		}
	}

	/**
	 * ANIM-2's table, one row per animator-writable property.
	 *
	 * Published even though the stage is refused, because these ids are the half of the reserved-id
	 * deliverable that was missing: the animator is the one surface whose OUTPUTS are named, and
	 * nothing reserved ids for them, stated their encoding, or fixed their spelling. The record
	 * already showed the spelling drifting — OCSL-POSTER writes `rotation`, the shipped field and
	 * the `setTransform` callback both write `rot` — and ownership declarations persist to NBT, so
	 * a rename later is a save migration rather than a docs change.
	 *
	 * {@code z} and {@code visible} carry ids and are NOT ownable in v1: {@code z} is an int and
	 * {@code visible} a boolean, neither interpolates, and IR bool is conditions-only with no bool
	 * register or output anywhere — so {@code setAnimator(id, {"visible"})} is a legal-looking call
	 * with no possible meaning. They are refused at attach with a documented error rather than
	 * silently absent, so the refusal can name them.
	 *
	 * {@code ref} and {@code parent} get no row at all: ANIM-2 makes them explicitly not readable.
	 */
	private static OcslType animatorPropertyType(int propertyId) {
		switch (propertyId) {
			case OcslWire.PROP_ANIM_X:
			case OcslWire.PROP_ANIM_Y:
			case OcslWire.PROP_ANIM_SX:
			case OcslWire.PROP_ANIM_SY:
			case OcslWire.PROP_ANIM_ROT2D:
				return OcslType.FLOAT;
			case OcslWire.PROP_ANIM_ROT3D:
			case OcslWire.PROP_ANIM_TINT:
				return OcslType.VEC4;
			case OcslWire.PROP_ANIM_TZ:
			case OcslWire.PROP_ANIM_SZ:
				return OcslType.FLOAT;
			// PROP_ANIM_Z and PROP_ANIM_VISIBLE deliberately have NO type: their ids are reserved
			// so nothing else takes them, and a typed row would advertise an OUT that must then be
			// refused somewhere less obvious than here.
			default:
				return null;
		}
	}

	/**
	 * STAGE-AWARE, and it has to be: property ids are a per-surface namespace, so id 0 is
	 * {@code COLOR} at a pixel stage and {@code x} at the animator. The stage-free version returned
	 * "COLOR" for the animator's x — a diagnostic naming a property from a different surface.
	 */
	public static String propertyName(byte stage, int propertyId) {
		if (stage == OcslWire.STAGE_ANIMATOR) {
			switch (propertyId) {
				case OcslWire.PROP_ANIM_X: return "x";
				case OcslWire.PROP_ANIM_Y: return "y";
				case OcslWire.PROP_ANIM_SX: return "sx";
				case OcslWire.PROP_ANIM_SY: return "sy";
				case OcslWire.PROP_ANIM_ROT2D: return "rot2d";
				case OcslWire.PROP_ANIM_ROT3D: return "rot3d";
				case OcslWire.PROP_ANIM_TINT: return "tint";
				case OcslWire.PROP_ANIM_Z: return "z";
				case OcslWire.PROP_ANIM_VISIBLE: return "visible";
				case OcslWire.PROP_ANIM_TZ: return "tz";
				case OcslWire.PROP_ANIM_SZ: return "sz";
				default: return "prop" + propertyId;
			}
		}
		return propertyId == OcslWire.PROP_COLOR ? "COLOR" : ("prop" + propertyId);
	}

	/**
	 * Every property this stage requires an OUT for. The pixel family requires exactly COLOR.
	 *
	 * <b>DO NOT use an empty result as "this stage is not implemented".</b> Two call sites do
	 * ({@link IrValidator} and {@link OcslBuilder#forStage}), and it has been correct only because
	 * every implemented surface so far happens to mandate an output. The animator breaks it: ANIM-1
	 * and ANIM-2 make its OUT set variable per program and the sole ownership declaration, so
	 * {@code requiredProperties(STAGE_ANIMATOR)} must stay <b>empty forever</b> — an animator that
	 * owns only {@code x} writes only {@code x}.
	 *
	 * The trap that leaves is concrete: whoever opens the surface implements {@code builtinType}
	 * for it, finds {@code validate()} still refusing, and clears the gate by making this method
	 * return a property — forcing every animator program to own it, directly against ANIM-2. The
	 * two questions need separating at that point. They are not separated now because the animator
	 * is the only surface that distinguishes them and it is still shut; {@link IrStructure} refuses
	 * it by name, which is the gate that actually means "reserved".
	 */
	public static int[] requiredProperties(byte stage) {
		switch (stage) {
			case OcslWire.STAGE_PIXEL_MATERIAL:
			case OcslWire.STAGE_PIXEL_EFFECT:
			case OcslWire.STAGE_PIXEL_POST:
			case OcslWire.STAGE_BAKE:
				return new int[] { OcslWire.PROP_COLOR };
			default:
				return new int[0];
		}
	}
}
