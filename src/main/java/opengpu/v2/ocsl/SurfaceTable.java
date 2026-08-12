package opengpu.v2.ocsl;

/**
 * THE RESERVED REGISTER/ID TABLE, PER SURFACE — the Stage B deliverable the design names "before
 * any blob is written".
 *
 * Two namespaces live here and the split between them is the whole point.
 *
 * REGISTER IDS. The index space an operand names is laid out in three fixed blocks:
 * <pre>
 *   0  .. 31   built-in input registers   (reserved for EVERY surface, applicability per surface)
 *   32 .. 95   user uniforms              (64 slots, declaration order)
 *   96 ..      working registers          (written by ops)
 * </pre>
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

	public static final int BUILTIN_BASE = 0;
	public static final int BUILTIN_LIMIT = 32;
	public static final int UNIFORM_BASE = 32;
	public static final int UNIFORM_LIMIT = 96;
	public static final int WORKING_BASE = 96;

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
	 * Reserved for the deferred animator surface. Its property table is one of the 18 pending
	 * amendments, so nothing here is assigned yet — but the BLOCK is, so that when the surface
	 * is taken up its ids do not have to be carved out of space something else has taken.
	 */
	public static final int REG_ANIMATOR_BASE = 16;
	public static final int REG_ANIMATOR_LIMIT = 32;

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
			default:
				return null;
		}
	}

	public static String propertyName(int propertyId) {
		return propertyId == OcslWire.PROP_COLOR ? "COLOR" : ("prop" + propertyId);
	}

	/** Every property this stage requires an OUT for. The pixel family requires exactly COLOR. */
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
