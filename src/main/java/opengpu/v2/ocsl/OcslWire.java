package opengpu.v2.ocsl;

/**
 * Wire-level constants for the OCSL IR, plus the opcode shape table the codec decodes against.
 *
 * The IR blob is the ONLY transport and storage form for a program — GLSL text never rides the
 * wire and never lands in a save (DESIGN-RENDERER-V2 § Programmable pipeline). That makes this
 * file the security boundary's vocabulary: every id is an explicit constant, nothing depends on
 * enum ordinals or declaration order, and the decoder rejects anything it does not recognize.
 *
 * FORMAT VERSION 1 IS FROZEN, since 2026-08-18. Version 0 meant pre-release, and while it held,
 * no blob could reach a world save — that is what made every id below free to renumber. Phase 3.1
 * opened the {@code createProgram} surface and began persisting blobs, so the window shut in the
 * same change, exactly as {@link IrCodec.Source} said it would. Every opcode id, shape row and
 * register block is now format identity: changing one is a version bump plus a migration for
 * stored blobs. Adding a NEW id at the end remains free, and so does raising a validator cap —
 * acceptance policy is not layout.
 */
public final class OcslWire {
	private OcslWire() {}

	/** 'O','C','S','L' — a blob that does not start with this is not ours, and we say so. */
	public static final int MAGIC = 0x4F43534C;

	/**
	 * FROZEN 0 -> 1 on 2026-08-18, by Phase 3.1 — the change that opened persistence, which is
	 * exactly the trigger {@link IrCodec.Source} had been carrying since the format was drawn.
	 *
	 * The pre-release window is closed. Until this bump, no blob could reach a world save (there
	 * was no surface that made one), which is what let the opcode table and
	 * {@link SurfaceTable}'s register blocks be renumbered for free — {@code SurfaceTable}'s
	 * widening note relies on that in as many words. 3.1 ships {@code createProgram} and writes
	 * program blobs into the snapshot section that {@code ScenePersistence} persists, so from
	 * this version on BOTH are format identity: renumbering a register or changing an op's shape
	 * needs a version bump and a migration, not a comment saying it is still free.
	 *
	 * Raising a VALIDATOR cap is unaffected and stays a non-event — {@code IrValidator}'s caps are
	 * monotonic acceptance policy, not layout. That distinction is the whole reason the two live
	 * in different classes, and it is what keeps ANIM-16's op-cap raise a Phase 4 decision rather
	 * than a format break.
	 */
	public static final short FORMAT_VERSION = 1;

	/** Closes the payload; a blob whose ops decode but whose tail is wrong is truncated or lying. */
	public static final short TRAILING_GUARD = 0x0C51;

	// ---------------------------------------------------------------- stages
	// Ids are reserved for every designed surface NOW, including the ones no code can produce
	// yet: an id assigned later collides with blobs already written under a guess.

	public static final byte STAGE_PIXEL_MATERIAL = 1;
	public static final byte STAGE_PIXEL_EFFECT = 2;
	public static final byte STAGE_PIXEL_POST = 3;
	public static final byte STAGE_BAKE = 4;
	public static final byte STAGE_VERTEX = 5;
	/**
	 * OPEN since 2026-08-13. Programs on this stage build, validate, encode, decode and run.
	 *
	 * It was "Reserved and REFUSED" while its dry run's amendments were pending, and this javadoc
	 * said deleting the decoder's refusal "is the act that reopens the surface, and it is the only
	 * way to reopen it". <b>That was wrong twice over</b>, and both errors are worth keeping visible
	 * because the second one is why this sentence went stale unnoticed: the decoder branch was one
	 * of FOUR gates ({@link SurfaceTable#isOpen}, {@code IrStructure.checkStage}, that branch, and
	 * {@link SurfaceTable#builtinType}), and describing the gate HERE, on the constant, put a fourth
	 * copy of the claim in a file none of the four edits had to touch. The gate moved with its test;
	 * this sentence did not, and only a review found it.
	 */
	public static final byte STAGE_ANIMATOR = 6;
	/**
	 * Reserved for the post-Stage-D Data Card.
	 *
	 * CLOSED, NOT UNKNOWN — and the refusal is the DECODER's, by name. {@link #isKnownStage}
	 * deliberately includes this id so the message can say "reserved" rather than lying about
	 * unknownness, but {@code IrCodec.decode} then refuses it explicitly, before any validator
	 * runs. An earlier version of this paragraph said a stage-7 blob DECODES and that the refusal
	 * was {@code IrValidator}'s {@code !SurfaceTable.isOpen} arm; both halves were false, and the
	 * second one matters — a DECODER rejection is format identity, so opening this stage is not
	 * the one-line validator act that opened the animator. {@code SurfaceTable} counts the gates:
	 * four for compute, and its note says "Count the sites, do not reuse this number". The
	 * distinction is load-bearing twice over. It is
	 * what keeps a reserved stage's refusal message able to say "reserved" rather than lying
	 * about unknownness (the guarantee {@code aReservedRefusalIsDistinctFromAnUnknownStage}
	 * pins), and it is what makes opening the stage later a VALIDATOR change under the
	 * monotonicity rule rather than a format change — though that too would be a four-site edit,
	 * not the one-line act this paragraph claimed until `67b3bf8` (2026-08-24). Because
	 * createProgram validates before storing, no blob can
	 * persist under this stage while it is closed, which is what protects the future compute
	 * surface's acceptance rules from being frozen by accident.
	 */
	public static final byte STAGE_COMPUTE = 7;

	// ---------------------------------------------------------------- property ids
	// Per-surface output namespace (u8). A1 of the animator decisions: every OUT names a property,
	// including the pixel family's, so one op form serves every stage and there is no implicit
	// destination anywhere.

	/** The pixel family's only output. Its surfaces accept exactly one OUT, targeting this. */
	public static final int PROP_COLOR = 0;

	// The ANIMATOR surface's output namespace (ANIM-2, assigned 2026-08-12). A SEPARATE namespace
	// from the pixel family's -- property ids are per-surface, so id 0 is COLOR there and `x` here,
	// and there is no collision to resolve. Assigned while the stage is still refused, because
	// these ids persist into ownership declarations in NBT: a rename later is a save migration.
	public static final int PROP_ANIM_X = 0;
	public static final int PROP_ANIM_Y = 1;
	public static final int PROP_ANIM_SX = 2;
	public static final int PROP_ANIM_SY = 3;
	public static final int PROP_ANIM_ROT2D = 4;
	/** vec4. Split from rot2d so every id has exactly one type and the OUT check stays decidable. */
	public static final int PROP_ANIM_ROT3D = 5;
	/** vec4, RGBA, normalized 0..1. The ARGB int packing is wire/storage only and never reaches IR. */
	public static final int PROP_ANIM_TINT = 6;
	/** Reserved, NOT ownable in v1: an int that does not interpolate. Refused at attach, by name. */
	public static final int PROP_ANIM_Z = 7;
	/** Reserved, NOT ownable in v1: IR bool is conditions-only, with no bool register or output. */
	public static final int PROP_ANIM_VISIBLE = 8;
	/** Stage C's 3D translate/scale on the z axis. */
	public static final int PROP_ANIM_TZ = 9;
	public static final int PROP_ANIM_SZ = 10;

	// ---------------------------------------------------------------- operand tagging
	// An operand is a register index or a constant-pool index, distinguished by the top bit. The
	// accounting rule makes constant-pool references FREE, which only works if a constant can BE
	// an operand — a LOADK op would have to charge.

	public static final int OPERAND_CONST_FLAG = 0x8000;
	public static final int OPERAND_INDEX_MASK = 0x7FFF;

	// ---------------------------------------------------------------- operand kinds

	/** A register index, or a constant-pool index when {@link #OPERAND_CONST_FLAG} is set. */
	public static final int KIND_VALUE = 0;
	/** A literal count that is not a register: FOR's trip count, ITOF's loop depth. */
	public static final int KIND_IMMEDIATE = 1;
	/** A property id in the stage's output namespace. */
	public static final int KIND_PROPERTY = 2;
	/** A resource slot index for sample(). */
	public static final int KIND_SLOT = 3;
	/**
	 * A swizzle mask: 2 bits per component, packed low-to-high, with {@code length - 1} in bits 8-9.
	 *
	 * LENGTH MINUS ONE, because a swizzle is 1..4 components and two bits hold 0..3. Both places
	 * that described this said "the length in bits 8-9", which is off by one and would have an
	 * independent implementer emit every swizzle a component short.
	 */
	public static final int KIND_SWIZZLE = 4;

	// ---------------------------------------------------------------- opcodes

	public static final byte OP_ADD = 1;
	public static final byte OP_SUB = 2;
	public static final byte OP_MUL = 3;
	public static final byte OP_DIV = 4;
	public static final byte OP_NEG = 5;

	public static final byte OP_ABS = 10;
	public static final byte OP_FLOOR = 11;
	public static final byte OP_FRACT = 12;
	public static final byte OP_MOD = 13;
	public static final byte OP_MIN = 14;
	public static final byte OP_MAX = 15;
	public static final byte OP_CLAMP = 16;
	public static final byte OP_MIX = 17;
	public static final byte OP_STEP = 18;
	public static final byte OP_SMOOTHSTEP = 19;
	public static final byte OP_DOT = 20;
	public static final byte OP_CROSS = 21;
	public static final byte OP_LENGTH = 22;
	public static final byte OP_NORMALIZE = 23;
	public static final byte OP_DISTANCE = 24;
	public static final byte OP_POW = 25;
	public static final byte OP_EXP = 26;
	public static final byte OP_LOG = 27;
	public static final byte OP_SQRT = 28;
	public static final byte OP_SIN = 29;
	public static final byte OP_COS = 30;
	public static final byte OP_ATAN2 = 31;

	public static final byte OP_SWZ = 40;
	public static final byte OP_SPLAT = 41;
	public static final byte OP_CONS2 = 42;
	public static final byte OP_CONS3 = 43;
	public static final byte OP_CONS4 = 44;
	/**
	 * The composite constructors, and they are not optional garnish.
	 *
	 * The frozen arity table has FOUR families — "vecN from N floats, vec(N−1)+float, vec4 from
	 * 2×vec2, splat from scalar" — and the first draft of this table encoded only two. The gap is
	 * not cosmetic: three of the four acceptance programs build their result with
	 * `vec4(vec3, float)` as ONE charged instruction, so without an opcode for it the builder must
	 * lower to three swizzles plus a CONS4 and every one of those programs charges 3 more than the
	 * committed count. The Stage B exit check reproduces 22/101/21/96 as STRUCTURAL-OP charges —
	 * not bytes. Three of the four carry a prologue on the BUILDER route (plasma 24, blur 102,
	 * domains 97 — OcslBuilderTest); two carry one in AcceptanceProgramsTest's hand-encoded
	 * IR, and this sentence said "two of the four" flatly until `67b3bf8` (2026-08-24).
	 * Plasma read 23 until the 2026-08-12 broadcast re-opening removed a
	 * builder-inserted SPLAT; AcceptanceProgramsTest records the change in four places, and
	 * OcslBuilderTest holds the builder-route counts and every execution. Without the composite
	 * constructors it would have
	 * failed on three of four, and the arity is unrecoverable from the wire (operand count is a
	 * property of the opcode, with no per-op count field), so no validator could have repaired it.
	 */
	public static final byte OP_CONS3_V2F = 45;
	public static final byte OP_CONS4_V3F = 46;
	public static final byte OP_CONS4_V2V2 = 47;

	public static final byte OP_LT = 50;
	public static final byte OP_LE = 51;
	public static final byte OP_EQ = 52;
	public static final byte OP_BAND = 53;
	public static final byte OP_BOR = 54;
	public static final byte OP_BNOT = 55;
	public static final byte OP_SELECT = 56;

	public static final byte OP_SAMPLE = 60;

	public static final byte OP_FOR = 70;
	public static final byte OP_ENDFOR = 71;
	public static final byte OP_ITOF = 72;

	public static final byte OP_OUT = 80;

	/**
	 * The ABSOLUTE output form: {@code disp = out}, whatever the property's default rule.
	 *
	 * ANIM-7's double-apply decision. Under a relative rule the only way to reach an absolute target
	 * is {@code OUT x, SUB(T, anim.x)}, which is indistinguishable — by any dependency analysis —
	 * from the double-apply bug {@code OUT x, ADD(anim.x, d)}. Giving absolute intent its own opcode
	 * is what makes the bug's spelling refusable without also refusing the idiom. Accepted only where
	 * {@link SurfaceTable#composesOutputs} holds; everywhere else "absolute" names no distinction,
	 * because the program's output IS the value.
	 *
	 * Added 2026-08-13, while {@code FORMAT_VERSION} was still 0 and no blob existed — the only
	 * window in which a new OUTPUT FORM cost a shape row rather than a migration, since it changes
	 * what an existing op means rather than appending to the table. That window closed with the
	 * 3.1 freeze; a further output form now needs a version bump.
	 */
	public static final byte OP_OUT_ABS = 81;

	private static final int MAX_OPCODE = 81;

	// ---------------------------------------------------------------- caps
	// Structural caps the DECODER enforces, so a malformed or hostile blob dies before any
	// allocation proportional to a number it supplied. Semantic caps (the per-stage op cap, the fetch
	// cap, uniform components) belong to the validator and are deliberately not here: they are
	// raiseable under the monotonicity rule, and these are not the same kind of number.

	public static final int MAX_CONSTANTS = 1024;
	public static final int MAX_OPS = 4096;
	public static final int MAX_REGISTERS = 1024;
	public static final int MAX_NAMES = 64;
	public static final int MAX_NAME_LENGTH = 32;
	/**
	 * Structural bounds on loops, so a well-formed blob cannot describe astronomically more work
	 * than its own size. A 6-op blob reading {@code FOR 65535 / FOR 65535 / ADD / ENDFOR / ENDFOR
	 * / OUT} is otherwise perfectly valid and denotes 4.29e9 executed instructions.
	 *
	 * The DEPTH bound is what keeps the describable space far above anything the validator
	 * accepts: 4096^16 is ~6.3e57 executed instructions from a 34-op blob, against an acceptance
	 * ceiling in the thousands. The TRIP bound no longer is — see the ordering note below.
	 *
	 * These are not the validator's unroll-product cap ({@code IrValidator.MAX_UNROLL_PRODUCT},
	 * deliberately not quoted here as the operative number, only recounted as history: it moved
	 * 256 -> 1024 on 2026-08-21 and 1024 -> 8192 at M on 2026-08-27, in lockstep with the ceiling
	 * both times; and this sentence still read "(256, frozen)" on 2026-08-23 — a number copied
	 * into prose drifts, and "frozen" was wrong about a cap that is pinned to a moving one).
	 * They are not that cap and must not be confused with it: this bounds what can be
	 * *described*, the validator bounds what is *accepted*, and only the latter is raiseable
	 * under the monotonicity rule.
	 *
	 * ORDERING INVERTED AT M, and it moved a refusal: {@code MAX_LOOP_TRIPS} (4096) used to sit
	 * ABOVE the unroll cap and was therefore redundant defence-in-depth — anything this bound
	 * refused, that cap refused first. At 8192 it sits BELOW, so a single FOR of 4097..8192 trips
	 * is refused HERE and nowhere else, and a single loop can no longer reach the unroll cap at
	 * all. {@code IrStructure.check} is the site that enforces it; {@code IrValidator}'s FOR arm
	 * carries the matching note.
	 */
	public static final int MAX_LOOP_TRIPS = 4096;
	public static final int MAX_LOOP_DEPTH = 16;
	/** A blob larger than this is refused before it is parsed at all. */
	public static final int MAX_BLOB_BYTES = 64 * 1024;

	// ---------------------------------------------------------------- the shape table

	/**
	 * Per-opcode shape: does it write a destination register, what its operands mean, and what it
	 * charges under the STRUCTURAL count.
	 *
	 * The structural count is the acceptance-cap currency (A2 of the animator decisions, resolving
	 * a FROZEN-vs-FROZEN conflict between the flat accounting sentence and the weighted cost
	 * table): every executed instruction charges 1, swizzles and constructors included, OUT
	 * included; FOR/ENDFOR are encoding structure and charge 0. The weighted table prices the fill
	 * budget and the bake op-pixel product and does NOT touch the cap — different question,
	 * different name. (The animator budget is a THIRD customer and prices neither: it charges
	 * measured nanoseconds, the CPU column leaving 27 of 48 opcodes unpriced.)
	 */
	public static final class Shape {
		public final String name;
		public final boolean hasDst;
		public final int[] operandKinds;
		public final int structuralCharge;

		Shape(String name, boolean hasDst, int[] operandKinds, int structuralCharge) {
			this.name = name;
			this.hasDst = hasDst;
			this.operandKinds = operandKinds;
			this.structuralCharge = structuralCharge;
		}

		public int operandCount() {
			return operandKinds.length;
		}
	}

	private static final Shape[] SHAPES = new Shape[MAX_OPCODE + 1];

	private static final int[] V0 = {};
	private static final int[] V1 = { KIND_VALUE };
	private static final int[] V2 = { KIND_VALUE, KIND_VALUE };
	private static final int[] V3 = { KIND_VALUE, KIND_VALUE, KIND_VALUE };

	private static void shape(byte op, String name, boolean hasDst, int[] kinds, int charge) {
		SHAPES[op & 0xFF] = new Shape(name, hasDst, kinds, charge);
	}

	static {
		shape(OP_ADD, "ADD", true, V2, 1);
		shape(OP_SUB, "SUB", true, V2, 1);
		shape(OP_MUL, "MUL", true, V2, 1);
		shape(OP_DIV, "DIV", true, V2, 1);
		shape(OP_NEG, "NEG", true, V1, 1);

		shape(OP_ABS, "ABS", true, V1, 1);
		shape(OP_FLOOR, "FLOOR", true, V1, 1);
		shape(OP_FRACT, "FRACT", true, V1, 1);
		shape(OP_MOD, "MOD", true, V2, 1);
		shape(OP_MIN, "MIN", true, V2, 1);
		shape(OP_MAX, "MAX", true, V2, 1);
		shape(OP_CLAMP, "CLAMP", true, V3, 1);
		shape(OP_MIX, "MIX", true, V3, 1);
		shape(OP_STEP, "STEP", true, V2, 1);
		shape(OP_SMOOTHSTEP, "SMOOTHSTEP", true, V3, 1);
		shape(OP_DOT, "DOT", true, V2, 1);
		shape(OP_CROSS, "CROSS", true, V2, 1);
		shape(OP_LENGTH, "LENGTH", true, V1, 1);
		shape(OP_NORMALIZE, "NORMALIZE", true, V1, 1);
		shape(OP_DISTANCE, "DISTANCE", true, V2, 1);
		shape(OP_POW, "POW", true, V2, 1);
		shape(OP_EXP, "EXP", true, V1, 1);
		shape(OP_LOG, "LOG", true, V1, 1);
		shape(OP_SQRT, "SQRT", true, V1, 1);
		shape(OP_SIN, "SIN", true, V1, 1);
		shape(OP_COS, "COS", true, V1, 1);
		shape(OP_ATAN2, "ATAN2", true, V2, 1);

		// A swizzle charges 1. It is a real instruction on a flat float[] frame — several stores —
		// and the flat rule is what the cap is stated against.
		shape(OP_SWZ, "SWZ", true, new int[] { KIND_VALUE, KIND_SWIZZLE }, 1);
		// SPLAT survives the 2026-08-12 broadcast re-opening for the places broadcast cannot
		// reach: a constructor's component slots, and building a vector from a float uniform.
		// Where a component-wise op WOULD accept the scalar directly, canonical form forbids
		// emitting this instead -- two spellings of one value that charge differently would fork
		// the content hash, which is the compile-cache key.
		shape(OP_SPLAT, "SPLAT", true, new int[] { KIND_VALUE, KIND_IMMEDIATE }, 1);
		shape(OP_CONS2, "CONS2", true, V2, 1);
		shape(OP_CONS3, "CONS3", true, V3, 1);
		shape(OP_CONS4, "CONS4", true, new int[] { KIND_VALUE, KIND_VALUE, KIND_VALUE, KIND_VALUE }, 1);
		// The composite forms. Each is ONE charged instruction, which is the whole point: the
		// committed op counts were computed with vec4(vec3,float) costing 1.
		shape(OP_CONS3_V2F, "CONS3_V2F", true, V2, 1);
		shape(OP_CONS4_V3F, "CONS4_V3F", true, V2, 1);
		shape(OP_CONS4_V2V2, "CONS4_V2V2", true, V2, 1);

		// Comparisons are function-form and scalar-only, producing the IR's only bool values.
		shape(OP_LT, "LT", true, V2, 1);
		shape(OP_LE, "LE", true, V2, 1);
		shape(OP_EQ, "EQ", true, V2, 1);
		shape(OP_BAND, "BAND", true, V2, 1);
		shape(OP_BOR, "BOR", true, V2, 1);
		shape(OP_BNOT, "BNOT", true, V1, 1);
		// Whole-value strict pick: the non-selected operand can never influence the result, even
		// when non-finite. Both arms are still evaluated and charged — a flat register machine has
		// no jumps and select is not a cost saving.
		shape(OP_SELECT, "SELECT", true, V3, 1);

		shape(OP_SAMPLE, "SAMPLE", true, new int[] { KIND_SLOT, KIND_VALUE }, 1);

		// FOR carries its trip count and the accumulator's init operand; both it and ENDFOR are
		// encoding structure and charge 0. The BODY's ops charge once per iteration — caps are
		// post-unroll dynamic counts, which is unrolling-invariant and so reads the same for an
		// interpreting VM and for unrolled codegen.
		shape(OP_FOR, "FOR", true, new int[] { KIND_IMMEDIATE, KIND_VALUE }, 0);
		shape(OP_ENDFOR, "ENDFOR", false, V0, 0);
		// A3: the counter is NOT a register and is never an operand. ITOF's operand is an
		// immediate loop-DEPTH selector, so the frame stays float-only as pinned and no other op
		// can reach the counter.
		shape(OP_ITOF, "ITOF", true, new int[] { KIND_IMMEDIATE }, 1);

		// A1: every output names its property. The pixel family is the degenerate case — one row
		// in its property table, PROP_COLOR — so there is no implicit destination anywhere and one
		// code path serves every stage.
		shape(OP_OUT, "OUT", false, new int[] { KIND_PROPERTY, KIND_VALUE }, 1);
		// Identical shape to OUT on purpose: the two forms differ in what the COMPOSITION does with
		// the value, not in what the op carries. A different arity would have made every existing
		// hand-built program's bytes a special case for no gain.
		shape(OP_OUT_ABS, "OUT_ABS", false, new int[] { KIND_PROPERTY, KIND_VALUE }, 1);
	}

	/**
	 * Whether an opcode is an output in either form.
	 *
	 * ONE place, deliberately. Every rule about outputs — one writer per property, the frame's
	 * output collection, "skip it during evaluation" — has to cover both forms, and this project's
	 * recurring defect is closing one side of a symmetric rule and leaving the mirror open.
	 *
	 * The test to apply at a site naming one opcode is what the site is FOR, not which constant it
	 * names: code that means "an output" and tests {@code opcode == OP_OUT} is the bug this method
	 * exists to prevent, while code that means "which form is this" — {@code OcslVm}'s per-property
	 * flag, {@code emitOut}'s stage gate, a test asserting the form survived a round trip — names
	 * the specific opcode correctly and must keep doing so.
	 */
	public static boolean isOut(byte opcode) {
		return opcode == OP_OUT || opcode == OP_OUT_ABS;
	}

	/** The shape of an opcode, or null if the opcode is not one of ours. */
	public static Shape shapeOf(byte opcode) {
		int i = opcode & 0xFF;
		return i <= MAX_OPCODE ? SHAPES[i] : null;
	}

	/** Whether a stage id is one this build knows how to decode at all. */
	public static boolean isKnownStage(byte stage) {
		return stage >= STAGE_PIXEL_MATERIAL && stage <= STAGE_COMPUTE;
	}

	/** Pack a swizzle: components low-to-high, 2 bits each, {@code length - 1} in bits 8-9. */
	public static int packSwizzle(int... components) {
		if (components.length < 1 || components.length > 4)
			throw new IllegalArgumentException("swizzle length must be 1..4");
		int mask = (components.length - 1) << 8;
		for (int i = 0; i < components.length; i++) {
			if (components[i] < 0 || components[i] > 3)
				throw new IllegalArgumentException("swizzle component must be 0..3");
			mask |= components[i] << (i * 2);
		}
		return mask;
	}

	public static int swizzleLength(int mask) {
		return ((mask >> 8) & 0x3) + 1;
	}

	public static int swizzleComponent(int mask, int i) {
		return (mask >> (i * 2)) & 0x3;
	}
}
