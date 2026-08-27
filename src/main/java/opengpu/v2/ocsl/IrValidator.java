package opengpu.v2.ocsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides whether a well-formed blob is an ACCEPTABLE program, and computes the frame layout the
 * CPU VM will preallocate against.
 *
 * The codec already established structure. This layer adds everything that needs to know what the
 * values mean: types, stage-applicable reads, output completeness, read-before-write, the semantic
 * caps, and A5's register→frame-offset mapping.
 *
 * BROADCAST IS THE SPINE OF THE TYPE RULES. A float operand stands in for a vector one wherever
 * the op is COMPONENT-WISE — arithmetic plus {@code MOD MIN MAX POW ATAN2 STEP CLAMP MIX
 * SMOOTHSTEP} — in any operand position, and the result takes the vector width. It does NOT
 * broadcast for the reducing and fixed-shape ops ({@code DOT CROSS LENGTH DISTANCE NORMALIZE}),
 * where a scalar operand has no reading; those stay strict. Two different vector widths in one op
 * are always refused: this is broadcast, not coercion. {@code SPLAT} survives for the places
 * broadcast cannot reach — constructor component slots, and building a vector from a float uniform.
 *
 * That rule replaced amendment 4's shape-uniform zoo on 2026-08-12. The amendment's own text had
 * conceded the choice was free ("GLSL 1.20 natively accepts mix(genType,genType,float), so the
 * splat is an IR-design decision, not a GLSL necessity"), and transcribing the four acceptance
 * programs found three of them using the scalar form anyway. Under the strict reading two of the
 * four committed op counts moved; under this one, only P1's — the program whose splat the record
 * already labelled an amendment-4 artifact.
 *
 * The caps here are the validator's and are RAISEABLE under the monotonicity rule: a program
 * rejected at build time never became a saved blob, and a cheaper program never fails
 * re-validation. Tightening one is a format-level break. That asymmetry is why they live here and
 * not in the codec, whose rejections are format identity.
 */
public final class IrValidator {

	/**
	 * Post-unroll structural ops — the CEILING. No stage sits at it since 2026-08-21: it is the
	 * bound the CODECS check a wire/disk {@code structuralOps} field against ({@code BatchCodec},
	 * {@code SnapshotCodec}) and the number every per-stage cap must stay at or under —
	 * {@code IrValidatorTest}'s range invariant fires if one tries to pass it, because a stage
	 * above the ceiling would have this validator accepting programs the decoders then refuse.
	 *
	 * RAISED 256 → 1024 on 2026-08-21 (user decision, against the cap-adequacy review), and
	 * RAISED AGAIN 1024 → 8192 on 2026-08-27 (increment M). DELIBERATELY PAST today's largest
	 * per-stage cap both times: the ceiling move is the format-adjacent event — after it, every
	 * per-stage adjustment up to the ceiling is free acceptance policy, so paying the event once
	 * buys the whole band. The known cost, stated rather than hidden: the moment a program is
	 * saved or sent whose charge exceeds what an OLDER build's ceiling allowed, that build's
	 * decoder refuses the whole batch or save with a generic CodecException — monotonicity
	 * covers upgrades only. There are now two such thresholds in the wild, and they are not
	 * equally live: builds before 2026-08-21 refuse >256, which a 512-charge animator program
	 * reaches today; builds between then and M refuse >1024, which nothing reachable can trip
	 * while every per-stage cap stays at or under 512 — {@code ServerScene}'s postcondition
	 * bounds the createProgram path by {@code maxStructuralOps(stage)}, not by this. That second
	 * threshold is a cost the increment which first raises a per-stage cap past 1024 inherits,
	 * not one M paid.
	 * One-way door: never tightenable once such blobs exist.
	 *
	 * THE MAGNITUDE, which the 2026-08-21 raise never recorded and M owes. The direction was
	 * always argued; the NUMBER never was. Sized against the shapes a ceiling would be asked
	 * for, measured rather than guessed — {@code CapIntuitionTest} charges ONE raymarch step at
	 * 28 structural ops and adds 132 for the normal and shading (4 further SDF evaluations plus
	 * ~20), so the ladder is 24 steps → 804, 32 → 1028, 48 → 1476, 64 → 1924; the compute
	 * sketch's all-pairs kernel estimates 456 + 276×15 = 4,596. 8192 clears raymarch-64 by 6,268
	 * and the all-pairs estimate by 3,596. 2048 would clear the first by 124.
	 *
	 * BUT PRICE THE ALL-PAIRS SHAPE IN THE FORM OCSL CAN ACTUALLY WRITE, which the paragraph
	 * below concedes and this one must not gloss: 276 pairs needs a TRIANGULAR nest that
	 * {@code OP_FOR}'s immediate trip count cannot express. The expressible square form is
	 * 24x24 = 576 pairs, so 456 + 576x15 = 9,096 — which 8192 REFUSES. The all-pairs point
	 * therefore does not separate 8192 from 2048 at all: neither admits a writable all-pairs
	 * kernel. On evidence the raymarch ladder is the whole case, and it argues for 2048.
	 *
	 * The evidence is not uniform and the record should not read as though it were: the raymarch
	 * ladder is EXACT and on its own argues for 2048. The all-pairs figure estimates a program
	 * {@code OP_FOR}'s immediate trip count cannot express at any ceiling (a triangular nest;
	 * the expressible square form is 576 pairs), at ±276 ops per ±1 op/pair. 8192-over-2048
	 * rests on amortization — paying the codec-adjacent event once — which is a values call the
	 * user made, not an evidence call. PLAN-STAGE-C's D9 is the posture that made it cheap:
	 * unpublished, single-user, one-way doors at LOW weight until publication.
	 *
	 * What M did NOT move, so nobody reads the raise as wider than it is: no per-stage cap
	 * (animator 512, everything else 256), so no program refused before M is accepted after it.
	 * The raise serves the pixel and compute stages later and nothing today.
	 */
	public static final int MAX_STRUCTURAL_OPS = 8192;
	/** Texture fetches, post-unroll, user tier. */
	public static final int MAX_FETCHES = 16;

	/**
	 * The structural-op cap AT A STAGE — the per-stage form ANIM-16/PLAN asks for.
	 *
	 * <b>THE STAGES DIVERGED THE SAME DAY THE SEAM LANDED.</b> The seam was installed
	 * behaviour-neutral (256 everywhere) on the morning of 2026-08-21 — its javadoc then noted
	 * that no test could tell it from the bare constant, which was true for the hours it lasted —
	 * and the user's cap-raise decision used it that afternoon: the animator moved to 512 on its
	 * measured cost model, the rest hold at 256 pending the GLSL-side measurement, and
	 * {@code IrValidatorTest.theStageCapsCarryTheDecidedDivergence} now pins every value, so the
	 * seam and the constant are no longer interchangeable anywhere. The cost models are why: an
	 * animator program runs on the CPU VM once per attached node per FRAME (~6–9 us per node at
	 * 5–14 ops, field-measured), while a pixel program runs on the GPU per fragment. One number
	 * priced for both is a number priced for neither.
	 *
	 * Written as a switch with only a default so the shape is obvious: a stage that diverges gets
	 * a case, HERE, and nowhere else. FOUR callers, by design, in two roles: the acceptance
	 * sites ({@code IrValidator.validate}, {@code OcslBuilder.charge}), which must consult the
	 * same budget or an author can build what validation refuses; and two readers that follow
	 * the seam deliberately — {@code ServerScene}'s postcondition assertion (it asserts the
	 * validator did what it says, so it asks the validator's question) and {@code StatsOverlay}'s
	 * denominator (it prices animator charges against the animator's budget). The codecs
	 * deliberately bound by {@link #MAX_STRUCTURAL_OPS} instead, because a decoder that tightened
	 * per stage would refuse a legitimately larger program rather than merely decline to author
	 * one. (A first draft of this paragraph said "two callers" while the same increment created
	 * four — the review panel's census caught it, and the lesson is the usual one about
	 * totalizing words.)
	 */
	public static int maxStructuralOps(byte stage) {
		switch (stage) {
			case OcslWire.STAGE_ANIMATOR:
				// 512, decided 2026-08-21 — the one stage with a measured cost model on both
				// sides: 0.46 us/op, ~6-9 us/node at 5-14 ops in the field
				// (FIELD-TEST-3.3). THE 0.46 IS NOT A MICROBENCHMARK, though this comment called
				// it one until 2026-08-21 and four documents repeated it: it traces to the note
				// in the empty-loop guard below, on a PIXEL-stage program, with no op mix,
				// warm-up or repetition count stated, and OcslWeightBench disowns absolute
				// nanoseconds outright. Two cost models fit the field data and differ 16x at
				// this cap — see FIELD-TEST-ANIM16.md, which is the measurement that separates
				// them. The cap below is not in question; what follows from it is.
				// An at-cap node is ~38-43 us, ~0.23-0.26% of a 60 Hz frame. FIELD-TEST-ANIM16
				// reports a RANGE (E + 512 x per-op), and 43 alone is its superseded run-1
				// model; the final per-op figure gives 38.0. The 0.46 us/op model above gave
				// ~236 us, 5.5x over, and that figure stood here while AnimatorBudget already
				// used the corrected one. The op-count ratio is unaffected: still 36x the
				// largest real program. Conservative on purpose: monotonicity makes every raise
				// a one-way door, and ANIM-16's client-global budget — the aggregate bound the
				// design calls "the real bound" — is AnimatorBudget.ENTER_NANOS in the client
				// render layer, 250 us of measured animator spend per client frame, shipped
				// 2026-08-22 and field-validated. Package-private there, so that is a prose
				// pointer rather than a resolvable reference. This
				// comment said "still has no number" until 2026-08-23, having been written the day
				// before the budget landed.
				return 512;
			default:
				// 256 for the pixel family (and the still-closed vertex/compute): PLAN's own
				// gate — "the pixel stages need a GLSL-side measurement nobody has taken" — is
				// unmet, and these stages execute nothing today, so a raise would spend a number
				// that should come from data while refusing nothing anyone can run. When the
				// measurement lands, each stage gets a case HERE, under the 8192 ceiling.
				return 256;
		}
	}

	/**
	 * The fetch cap AT A STAGE — ANIM-16, which asks for the animator's caps to be stated rather
	 * than inherited.
	 *
	 * The animator's is <b>0</b>, and that is a rule and not a description: no sampler exists in its
	 * register set, so a fetch there names a resource the surface has no way to bind. Inheriting 16
	 * would have let an animator sample any slot except the built-in `input` one — the slot-0 guard
	 * below is the only sampler check that is stage-aware, and it does not cover slots 1..15.
	 *
	 * Written now, while the stage is still refused, so it is live the moment the surface opens
	 * rather than being a prose obligation someone has to remember at that point.
	 */
	public static int maxFetches(byte stage) {
		return stage == OcslWire.STAGE_ANIMATOR ? 0 : MAX_FETCHES;
	}

	/**
	 * The uniform-component budget AT A STAGE — the other half of ANIM-16.
	 *
	 * 64 is the <b>GL</b> floor: GLSL 1.20 on 2006-era parts guarantees little more, and it is the
	 * number every pixel-family program is priced against. <b>It does not apply at the animator
	 * stage</b>, because no animator program is ever lowered to GLSL — it evaluates on the CPU VM to
	 * produce property values, and the frame it needs is bounded by A5's frame-width cap instead.
	 * ANIM-16 asks for one of these two answers explicitly; this is the second.
	 *
	 * And the sentence ANIM-16 says is "the difference between 12/64 and 44/64": <b>the node's
	 * property input registers do NOT charge against this budget.</b> They are built-in registers
	 * at fixed ids, not declared uniforms — the budget counts what a program DECLARES.
	 *
	 * <b>The VALUE is 64 everywhere, and only the REASON differs</b> — a first draft returned
	 * {@code MAX_FRAME_WIDTH} here and published "1024" for the animator, which was wrong twice
	 * over. It contradicted {@code maxUniforms 64} in the same artifact, making 1024 unreachable;
	 * and it could never fire, because v1 uniforms are float-typed, so components equal slots and
	 * {@link SurfaceTable#MAX_UNIFORMS} refuses first. The two caps coincide in v1 by accident of
	 * the type system. They diverge the moment typed uniforms land — and at that point the pixel
	 * family stays pinned to the GL floor while the animator does not, which is the distinction
	 * ANIM-16 asked to be stated rather than the number.
	 */
	public static int maxUniformComponentsAt(byte stage) {
		return MAX_UNIFORM_COMPONENTS;
	}
	/** Uniform COMPONENTS, against the GL 2.1 minimum of 64 fragment components. */
	public static final int MAX_UNIFORM_COMPONENTS = 64;
	/**
	 * Product of all loop trip counts. Equal to the CEILING, in lockstep since 2026-08-21 and
	 * moved with it again at M (2026-08-27) — raised with it precisely so it stays non-binding:
	 * unrollProduct <= charged ops (the empty-body rule) <= the stage's cap <= this. Left at 256
	 * while the ceiling moved to 1024, a thin FOR-512 loop with a one-op body would charge
	 * legally under the animator's 512 and still be refused at the FOR — loop-heavy shapes
	 * becoming second-class exactly where a raise aims. The chain now reads
	 * unrollProduct <= charge <= 512 <= 8192, so the slack is now 16x, up from 2x.
	 *
	 * ORDERING NOTE, new at M and worth knowing before reading the FOR arm: this cap is now
	 * ABOVE {@code OcslWire.MAX_LOOP_TRIPS} (4096), where it used to sit below it. A SINGLE loop
	 * can therefore no longer reach this cap at all — the most one FOR can contribute is 4096 —
	 * so tripping it now requires nesting depth 2 or more. {@code IrStructure} is what refuses an
	 * over-trip single loop; see the FOR arm's comment.
	 */
	public static final int MAX_UNROLL_PRODUCT = 8192;

	private IrValidator() {}

	/**
	 * A validated program: the types it inferred, and the frame layout the VM preallocates.
	 *
	 * The layout is A5 in code — registers packed BY DECLARED WIDTH IN FIRST-WRITE ORDER, computed
	 * identically by whoever builds a blob and whoever validates one. Pinning it mattered because
	 * A3 had already frozen "frame width is derivable from the blob alone" without saying HOW, and
	 * the first interpreter written would otherwise have decided it by accident.
	 */
	public static final class Validated {
		private final IrProgram program;
		private final OcslType[] registerTypes;
		private final int[] frameOffsets;
		public final int frameWidth;
		public final long structuralOps;
		public final int fetches;
		public final int uniformComponents;

		Validated(IrProgram program, OcslType[] registerTypes, int[] frameOffsets, int frameWidth,
				long structuralOps, int fetches, int uniformComponents) {
			this.program = program;
			this.registerTypes = registerTypes;
			this.frameOffsets = frameOffsets;
			this.frameWidth = frameWidth;
			this.structuralOps = structuralOps;
			this.fetches = fetches;
			this.uniformComponents = uniformComponents;
		}

		public IrProgram program() {
			return program;
		}

		/**
		 * The inferred type of a register, or null if the program never gives it one.
		 *
		 * Guarded at BOTH ends. Only the high end was checked, so a negative register threw
		 * ArrayIndexOutOfBounds from inside a method whose whole contract is to answer "no" by
		 * returning null — and {@code OcslVm.set} turned that into an AIOOBE where it means to
		 * throw IllegalArgumentException.
		 */
		public OcslType typeOf(int register) {
			return register >= 0 && register < registerTypes.length ? registerTypes[register] : null;
		}

		/** Where a register's components start in the VM's flat frame, or -1 if it has none. */
		public int frameOffset(int register) {
			return register >= 0 && register < frameOffsets.length ? frameOffsets[register] : -1;
		}
	}

	public static Validated validate(IrProgram program) throws ValidationException {
		// STRUCTURE FIRST, and shared with the encoder rather than restated here. Everything below
		// indexes the pool, the register table and the operand list by numbers this program
		// supplies; before that check existed, a constant index past the end of the pool, a
		// constant of width 0 or 5, and a negative register count each threw
		// ArrayIndexOutOfBounds / NullPointerException / NegativeArraySizeException straight out
		// of this method -- which the A6 contract names as the thing that must never happen.
		try {
			IrStructure.check(program);
		} catch (IrStructure.StructureException e) {
			throw new ValidationException(e.opIndex, e.getMessage());
		}

		byte stage = program.stage;
		if (!SurfaceTable.isOpen(stage)) {
			// Asks whether the SURFACE is open, which is no longer the same question as whether it
			// mandates an output -- see SurfaceTable.isOpen. The old form read an empty
			// requiredProperties as "reserved", and the message said "has no property table", which
			// became false the day the animator's was published: it has one, in the frozen artifact,
			// and is shut for entirely different reasons.
			// The only stage that REACHES this throw is still vertex, but the reason narrowed when
			// the animator opened on 2026-08-13: 7 alone is now refused by name in IrStructure.check
			// above, 0 and >=8 fail isKnownStage inside it, and 1-4 and 6 are open. Vertex reaches
			// it by being shut with no reservation anywhere -- it is the one stage shut by ABSENCE,
			// which is why this message must not call it reserved. A
			// first draft branched the wording on whether the stage had a property table, to
			// distinguish the animator -- a branch that could never execute, describing a message
			// the validator had never emitted for the animator either before or after the change.
			// It also claimed "no program may be built, ENCODED or run", and encode does not refuse
			// vertex: a hand-built vertex program still round-trips the wire, which is a real
			// pre-existing gap this message should not paper over.
			throw new ValidationException(-1, "stage " + (stage & 0xFF)
					+ " is not open: no program may be validated or run on this surface yet."
					+ " Whether it has a property table is a separate question -- a surface can have"
					+ " one and still be shut.");
		}
		int regCount = program.declaredRegisters;
		if (regCount > SurfaceTable.MAX_REGISTERS) {
			throw new ValidationException(-1, "declares " + regCount + " registers, over the cap of "
					+ SurfaceTable.MAX_REGISTERS);
		}
		OcslType[] types = new OcslType[regCount];

		// Built-ins and uniforms are BOUND, not written. Their types come from the surface table
		// and from the program's own uniform declarations, and they are the only registers a
		// program may read without having written them first.
		for (int r = 0; r < Math.min(regCount, SurfaceTable.BUILTIN_LIMIT); r++) {
			types[r] = SurfaceTable.builtinType(stage, r);
		}
		int uniformCount = program.names().size();
		if (uniformCount > SurfaceTable.MAX_UNIFORMS) {
			throw new ValidationException(-1, "declares " + uniformCount + " uniforms, over the "
					+ SurfaceTable.MAX_UNIFORMS + " the id block reserves");
		}
		// v1 uniforms are float-typed at declaration; the wire carries no per-uniform type yet, so
		// a widened uniform is a format change and not a validator relaxation. Stated rather than
		// assumed, because "typed uniforms" is committed design and this is the narrower thing
		// actually implemented.
		for (int i = 0; i < uniformCount; i++) {
			int reg = SurfaceTable.UNIFORM_BASE + i;
			if (reg < regCount) {
				types[reg] = OcslType.FLOAT;
			}
		}
		int uniformComponents = uniformCount;
		if (uniformComponents > maxUniformComponentsAt(stage)) {
			throw new ValidationException(-1, "uses " + uniformComponents
					+ " uniform components, over this stage's cap of "
					+ maxUniformComponentsAt(stage));
		}

		boolean[] written = new boolean[regCount];
		List<Integer> writeOrder = new ArrayList<Integer>();
		Map<Integer, Integer> outsByProperty = new LinkedHashMap<Integer, Integer>();
		// ANIM-7's read rule, collected DURING the single pass and settled after it. Both halves
		// have to be gathered before either can be judged: an OUT may follow the read it forbids,
		// so checking at the read site would enforce the rule only for programs that happen to put
		// their outputs first. A post-loop intersection is not a second traversal.
		Map<Integer, Integer> relativeOuts = SurfaceTable.composesOutputs(stage)
				? new LinkedHashMap<Integer, Integer>() : null;
		// First op that reads each of the node's own property registers, [16, 25), or -1.
		int[] ownReadAt = null;
		if (relativeOuts != null) {
			ownReadAt = new int[SurfaceTable.REG_ANIM_OWN_LIMIT - SurfaceTable.REG_ANIMATOR_BASE];
			java.util.Arrays.fill(ownReadAt, -1);
		}
		int fetches = 0;
		long unrollProduct = 1;
		long multiplier = 1;
		List<Integer> tripStack = new ArrayList<Integer>();

		// Charged ops seen so far. Recorded at FOR and compared at ENDFOR, which is what makes an
		// empty loop body detectable without a second pass; see the ENDFOR branch for why it is
		// refused.
		long chargedSeen = 0;
		List<Long> chargedAtEntry = new ArrayList<Long>();

		List<IrOp> ops = program.ops();
		for (int i = 0; i < ops.size(); i++) {
			IrOp op = ops.get(i);
			OcslWire.Shape shape = OcslWire.shapeOf(op.opcode);

			// Opcode, arity, dst presence, operand ranges, pool indices, swizzle canonical form,
			// loop balance and ITOF's depth selector are all IrStructure's, checked above.

			// Note every read of the node's own property registers as we go past. Done on the raw
			// operands rather than inside readType because readType is reached from several call
			// sites with different operand indices, and one of them is OUT's own value operand --
			// `OUT x, anim.x`, the purest form of the defect, which a check hung off the arithmetic
			// paths alone would walk straight past.
			if (ownReadAt != null) {
				for (int k = 0; k < shape.operandCount(); k++) {
					if (shape.operandKinds[k] != OcslWire.KIND_VALUE) {
						continue;
					}
					int operand = op.operand(k);
					if ((operand & OcslWire.OPERAND_CONST_FLAG) != 0) {
						continue;
					}
					int reg = operand & OcslWire.OPERAND_INDEX_MASK;
					int slot = reg - SurfaceTable.REG_ANIMATOR_BASE;
					if (slot >= 0 && slot < ownReadAt.length && ownReadAt[slot] < 0) {
						ownReadAt[slot] = i;
					}
				}
			}

			if (op.opcode == OcslWire.OP_FOR) {
				int trips = op.operand(0);
				if (trips < 1) {
					throw new ValidationException(i, "FOR declares " + trips
							+ " iterations; a loop that never runs has no meaning here and its"
							+ " accumulator would be its init value, which the program can write"
							+ " directly");
				}
				// No MAX_LOOP_TRIPS check here on purpose -- but the REASON changed at M and the
				// old one is now false, so it is written out rather than trimmed. It used to be
				// "trips <= multiplier <= unrollProduct, so the unroll cap below refuses anything
				// the 4096 wire bound would": true while that cap was 1024, since {T > 4096} was a
				// subset of {T > 1024}. At 8192 it is FALSE -- a single FOR of 5000 trips is over
				// the wire bound and UNDER the unroll cap. The conclusion survives for a different
				// reason: IrStructure.check runs FIRST (see validate()'s opening try) and refuses
				// any FOR whose immediate exceeds MAX_LOOP_TRIPS, wrapping it as a
				// ValidationException. So the trip bound is enforced, just not here -- and a
				// duplicate check here would still be protection it is not.
				multiplier *= trips;
				// The cap is on the deepest NESTING PATH, not on every loop in the program
				// multiplied together. `multiplier` is already the per-nest product and ENDFOR
				// already unwinds it; an accumulating total never unwinds, so two SEQUENTIAL
				// loops of 20 would have read as 400 and been refused — while the frozen entry
				// calls this cap "equal to the op cap, therefore NON-BINDING" on the argument
				// that every innermost iteration charges at least one op. The nesting-path
				// reading is still right, but that ARGUMENT was false until the empty-body rule
				// below made it true: an empty body charges nothing, so "every innermost
				// iteration charges at least one op" was a premise nothing enforced.
				unrollProduct = Math.max(unrollProduct, multiplier);
				if (unrollProduct > MAX_UNROLL_PRODUCT) {
					throw new ValidationException(i, "unroll product " + unrollProduct
							+ " exceeds the cap of " + MAX_UNROLL_PRODUCT);
				}
				tripStack.add(Integer.valueOf(trips));
				chargedAtEntry.add(Long.valueOf(chargedSeen));
				// The accumulator's type is its init operand's, and it counts as written from here.
				OcslType init = readType(program, types, written, op, 1, i, stage);
				assign(types, written, writeOrder, op.dst, init, i);
				continue;
			}
			if (op.opcode == OcslWire.OP_ENDFOR) {
				// Balance is IrStructure's, so tripStack cannot be empty here.
				// THE WORK BOUND. A loop whose body charges nothing costs the interpreter a
				// back-edge per iteration and costs unrolled codegen nothing at all, so the
				// structural count -- which is a post-unroll count, and correctly reads 0 here --
				// prices it at zero while the CPU VM pays in full. Measured: 2000 sequential
				// `FOR 256 / ENDFOR` pairs validate at ONE structural op and run 512,000
				// back-edges in ~3ms, against 118us for a program charged the entire 256 budget.
				//
				// Refused rather than charged, because charging FOR/ENDFOR would break the
				// unrolling-invariance the count is built on and move all four committed
				// acceptance counts. Refusing costs nothing real: a loop with no charged op
				// leaves its accumulator at the init value, which is the same thing `trips < 1`
				// is refused for and the program can write directly. With this rule every loop
				// charges at least `trips`, so total back-edges are bounded by the op cap.
				if (chargedSeen == chargedAtEntry.get(chargedAtEntry.size() - 1).longValue()) {
					throw new ValidationException(i, "the loop closing here charges no op, so it"
							+ " computes nothing and leaves its accumulator at the init value;"
							+ " an empty loop costs the interpreter a back-edge per iteration that"
							+ " the op count cannot see");
				}
				chargedAtEntry.remove(chargedAtEntry.size() - 1);
				tripStack.remove(tripStack.size() - 1);
				multiplier = 1;
				for (Integer t : tripStack) {
					multiplier *= t.intValue();
				}
				continue;
			}
			if (shape.structuralCharge > 0) {
				chargedSeen++;
			}

			OcslType result = inferAndCheck(program, types, written, op, i, stage);

			if (op.opcode == OcslWire.OP_SAMPLE) {
				int slot = op.operand(0);
				if (slot < 0 || slot >= SurfaceTable.MAX_SLOTS) {
					throw new ValidationException(i, "slot " + slot + " is outside the "
							+ SurfaceTable.MAX_SLOTS + " this build binds");
				}
				if (slot == SurfaceTable.SLOT_INPUT && !SurfaceTable.hasInputSampler(stage)) {
					throw new ValidationException(i, "slot 0 is the built-in `input` sampler, which"
							+ " only the effect and post-chain surfaces have");
				}
				fetches += multiplier;
				if (fetches > maxFetches(stage)) {
					throw new ValidationException(i, "program performs " + fetches
							+ " fetches post-unroll, over this stage's cap of "
							+ maxFetches(stage));
				}
			}

			if (OcslWire.isOut(op.opcode)) {
				int property = op.operand(0);
				// THE STAGE, not the property. "Absolute" only names a distinction where a relative
				// default exists; at the pixel family the output IS the value, so accepting OUT_ABS
				// there would publish a second spelling for identical behaviour.
				//
				// BEFORE the property lookup, and it matters for a program that is wrong in both
				// ways: OUT_ABS is not legal at this stage whatever property it names, so the form
				// is the more fundamental error and reporting the property instead would send the
				// author to fix the wrong thing. Pinned by
				// IrValidatorTest.theFormIsRefusedBeforeTheProperty_soAnAuthorLearnsTheRealError,
				// which carries the control for the mirror case as well.
				if (op.opcode == OcslWire.OP_OUT_ABS && !SurfaceTable.composesOutputs(stage)) {
					throw new ValidationException(i, "OUT_ABS writes a value that replaces a"
							+ " server-set base, and stage " + (stage & 0xFF) + " has no base to"
							+ " replace; its output is the value, so use OUT");
				}
				OcslType expected = SurfaceTable.propertyType(stage, property);
				if (expected == null) {
					// NAMED ONLY WHERE THE ID IS ACTUALLY RESERVED. SurfaceTable's javadoc promises
					// that the reserved-but-unownable ids are "refused with a documented error ...
					// so the refusal can name them", and this message used to interpolate the raw
					// int -- owning `visible` came back as "stage 6 has no property 8".
					//
					// THE FIRST FIX WAS WORSE THAN THE DEFECT, and an adversarial review caught it
					// after it shipped. It gated the name on `propertyName(...) != null`, and
					// propertyName is TOTAL: every arm falls back to "prop" + id. So the guard was a
					// tautology and EVERY unknown property at EVERY open stage was labelled
					// "reserved but not ownable in v1" -- "stage 1 has no property 5 (prop5,
					// reserved but not ownable in v1)", asserting a reservation for an unallocated
					// id and passing off a synthesized placeholder as a published spelling. The
					// previous message was merely terse; that one was false, and reachable today.
					if (SurfaceTable.isReservedUnownable(stage, property)) {
						throw new ValidationException(i, "stage " + (stage & 0xFF) + " reserves "
								+ SurfaceTable.propertyName(stage, property) + " (property "
								+ property + ") but it is not ownable in v1");
					}
					throw new ValidationException(i, "stage " + (stage & 0xFF)
							+ " has no property " + property);
				}
				OcslType actual = readType(program, types, written, op, 1, i, stage);
				if (actual != expected) {
					throw new ValidationException(i, "OUT " + SurfaceTable.propertyName(stage, property)
							+ " expects " + expected.display() + ", got " + actual.display());
				}
				if (!tripStack.isEmpty()) {
					throw new ValidationException(i, "OUT inside a loop would write its property"
							+ " once per iteration; one writer per property per frame");
				}
				if (relativeOuts != null && op.opcode == OcslWire.OP_OUT) {
					relativeOuts.put(Integer.valueOf(property), Integer.valueOf(i));
				}
				Integer previous = outsByProperty.put(Integer.valueOf(property), Integer.valueOf(i));
				if (previous != null) {
					throw new ValidationException(i, "property "
							+ SurfaceTable.propertyName(stage, property) + " already written at op "
							+ previous);
				}
				continue;
			}

			if (shape.hasDst) {
				assign(types, written, writeOrder, op.dst, result, i);
			}
		}

		// ANIM-7: THE RELATIVE FORM FORFEITS THE READ OF ITS OWN PROPERTY.
		//
		// Relative composition already supplies the base, so `OUT x, ADD(anim.x, d)` displays
		// base + base + d. The read is not merely redundant there, it is the defect -- and it
		// cannot be told from the CORRECT absolute-seek idiom by any dependency analysis, because
		// `OUT x, SUB(T, anim.x)` has the identical shape and is right. OUT_ABS is what separates
		// them: it says "this value IS the displayed one", so it may read the base freely, and this
		// rule applies only to the relative form.
		//
		// REPLACE properties are exempt because the arithmetic exempts them: `compose` returns the
		// output and the base never enters the result, so tint cannot double-apply. Forbidding that
		// read would cost the one idiom ANIM-21 needs (a tint animator that modulates the server
		// colour instead of overwriting it) and buy nothing.
		if (relativeOuts != null) {
			for (Map.Entry<Integer, Integer> entry : relativeOuts.entrySet()) {
				int property = entry.getKey().intValue();
				if (OcslCompose.ruleFor(property) == OcslCompose.RULE_REPLACE) {
					continue;
				}
				int reg = SurfaceTable.animatorReadRegister(property);
				if (reg < 0) {
					continue;
				}
				int readAt = ownReadAt[reg - SurfaceTable.REG_ANIMATOR_BASE];
				if (readAt < 0) {
					continue;
				}
				// The message names the FORM and the remedy, never "this stage has no such
				// register" -- the stage has it, this program gave it up. That distinction is the
				// false-refusal defect fixed in e5ea97c, one surface over.
				throw new ValidationException(readAt, "reads `"
						+ SurfaceTable.builtinName(reg) + "` (register " + reg + ") while writing `"
						+ SurfaceTable.propertyName(stage, property) + "` with the relative OUT at"
						+ " op " + entry.getValue() + "; that write already composes over this"
						+ " value, so building on it applies the base twice. Write the property"
						+ " with OUT_ABS if the output is an absolute value, which may read the"
						+ " base, or drop the read if it is an offset");
			}
		}

		for (int required : SurfaceTable.requiredProperties(stage)) {
			if (!outsByProperty.containsKey(Integer.valueOf(required))) {
				throw new ValidationException(-1, "program never writes "
						+ SurfaceTable.propertyName(stage, required) + "; every program on this surface"
						+ " must produce it");
			}
		}

		long structural = program.structuralCount();
		// PER-STAGE, not the bare constant: this is an ACCEPTANCE decision, and the message names
		// the stage so an author reading it knows which budget they crossed.
		int opCap = maxStructuralOps(stage);
		if (structural > opCap) {
			throw new ValidationException(-1, "program charges " + structural
					+ " structural ops, over stage " + (stage & 0xFF) + "'s cap of " + opCap);
		}

		// A5: pack the frame by declared width. Built-ins and uniforms first, at fixed positions
		// derived from the surface table, then working registers IN FIRST-WRITE ORDER.
		int[] offsets = new int[regCount];
		java.util.Arrays.fill(offsets, -1);
		int cursor = 0;
		for (int r = 0; r < regCount; r++) {
			if (r < SurfaceTable.WORKING_BASE && types[r] != null) {
				offsets[r] = cursor;
				cursor += types[r].width;
			}
		}
		for (Integer reg : writeOrder) {
			int r = reg.intValue();
			if (offsets[r] < 0 && types[r] != null) {
				offsets[r] = cursor;
				cursor += types[r].width;
			}
		}

		if (cursor > SurfaceTable.MAX_FRAME_WIDTH) {
			throw new ValidationException(-1, "lays out a frame of " + cursor
					+ " floats, over the cap of " + SurfaceTable.MAX_FRAME_WIDTH);
		}
		return new Validated(program, types, offsets, cursor, structural, fetches,
				uniformComponents);
	}

	// ---------------------------------------------------------------- type rules

	private static void assign(OcslType[] types, boolean[] written, List<Integer> writeOrder,
			int reg, OcslType type, int opIndex) throws ValidationException {
		if (reg < SurfaceTable.WORKING_BASE) {
			throw new ValidationException(opIndex, "register " + reg + " is "
					+ (reg < SurfaceTable.UNIFORM_BASE ? "a built-in input" : "a uniform")
					+ " and cannot be written; working registers start at "
					+ SurfaceTable.WORKING_BASE);
		}
		if (reg >= types.length) {
			// Only the LOW end was checked, so a write to register 5000 in a 97-register program
			// indexed past `types` and threw ArrayIndexOutOfBounds out of validate(). The decoder
			// rejects it against declaredRegisters; a program built in memory never met that.
			throw new ValidationException(opIndex, "writes register " + reg
					+ ", outside the " + types.length + " this program declares");
		}
		if (types[reg] != null && types[reg] != type) {
			// A register keeps one type for the program's life. Re-typing would make the frame
			// width depend on execution order, which A5's static layout cannot express.
			throw new ValidationException(opIndex, "register " + reg + " was " + types[reg].display()
					+ " and this writes " + type.display());
		}
		if (!written[reg]) {
			writeOrder.add(Integer.valueOf(reg));
		}
		types[reg] = type;
		written[reg] = true;
	}

	private static OcslType readType(IrProgram program, OcslType[] types, boolean[] written,
			IrOp op, int slot, int opIndex, byte stage) throws ValidationException {
		if (op.isConstant(slot)) {
			// Constants are typed by their width, so a pooled vec4 is a vec4 operand -- which is
			// what lets a fold be seeded with vec4(0) at zero op cost.
			return program.constantType(op.index(slot));
		}
		int reg = op.index(slot);
		OcslType type = reg < types.length ? types[reg] : null;
		if (type == null) {
			if (reg < SurfaceTable.BUILTIN_LIMIT) {
				throw new ValidationException(opIndex, "reads built-in `"
						+ SurfaceTable.builtinName(reg) + "` (register " + reg
						+ "), which stage " + (stage & 0xFF) + " does not have");
			}
			if (reg < SurfaceTable.WORKING_BASE) {
				throw new ValidationException(opIndex, "reads uniform register " + reg
						+ ", which this program does not declare");
			}
			throw new ValidationException(opIndex, "reads register " + reg
					+ " before anything writes it");
		}
		if (reg >= SurfaceTable.WORKING_BASE && !written[reg]) {
			throw new ValidationException(opIndex, "reads register " + reg
					+ " before anything writes it");
		}
		return type;
	}

	private static OcslType inferAndCheck(IrProgram program, OcslType[] types, boolean[] written,
			IrOp op, int i, byte stage) throws ValidationException {
		byte code = op.opcode;

		switch (code) {
			// --- COMPONENT-WISE OPS: a float operand may stand in for a vector one, broadcast.
			// This supersedes amendment 4's "IR function ops are shape-uniform; the builder
			// inserts explicit splat constructor ops for scalar broadcast" (re-opened 2026-08-12,
			// user decision). That amendment's own text conceded the point -- "GLSL 1.20 natively
			// accepts mix(genType,genType,float), SO THE SPLAT IS AN IR-DESIGN DECISION, NOT A
			// GLSL NECESSITY" -- and it chose the restrictive side. Three of the four
			// hand-translated acceptance programs then reached for the scalar form anyway, which
			// is evidence about what a builder naturally emits.
			//
			// One rule replaces a split: broadcast is legal wherever the op is component-wise,
			// which is exactly where it means something. The reducing and fixed-shape ops below
			// are excluded because a broadcast operand has no sensible reading there.
			//
			// CANONICAL-FORM OBLIGATION, and it is the price of this: the builder must NEVER emit
			// a SPLAT where broadcast is available. Both spellings compute the same thing and
			// charge differently, so allowing the builder to choose would fork the content hash --
			// which is the compile-cache key -- on a difference no program can observe.
			case OcslWire.OP_ADD: case OcslWire.OP_SUB: case OcslWire.OP_MUL:
			case OcslWire.OP_DIV: case OcslWire.OP_MOD: case OcslWire.OP_MIN:
			case OcslWire.OP_MAX: case OcslWire.OP_STEP: case OcslWire.OP_POW:
			case OcslWire.OP_ATAN2:
				return componentWise(program, types, written, op, i, stage, 2);
			case OcslWire.OP_CLAMP: case OcslWire.OP_MIX: case OcslWire.OP_SMOOTHSTEP:
				return componentWise(program, types, written, op, i, stage, 3);
			// Unary ops have no broadcast question -- one operand decides the shape.
			case OcslWire.OP_NEG: case OcslWire.OP_ABS: case OcslWire.OP_FLOOR:
			case OcslWire.OP_FRACT: case OcslWire.OP_EXP: case OcslWire.OP_LOG:
			case OcslWire.OP_SQRT: case OcslWire.OP_SIN: case OcslWire.OP_COS:
			case OcslWire.OP_NORMALIZE:
				return uniform(program, types, written, op, i, stage, 1);

			// --- shape-reducing
			case OcslWire.OP_DOT: case OcslWire.OP_DISTANCE: {
				OcslType t = uniform(program, types, written, op, i, stage, 2);
				return OcslType.FLOAT;
			}
			case OcslWire.OP_LENGTH: {
				uniform(program, types, written, op, i, stage, 1);
				return OcslType.FLOAT;
			}
			case OcslWire.OP_CROSS: {
				OcslType t = uniform(program, types, written, op, i, stage, 2);
				if (t != OcslType.VEC3) {
					throw new ValidationException(i, "cross takes vec3, got " + t.display());
				}
				return OcslType.VEC3;
			}

			// --- shape-changing
			case OcslWire.OP_SWZ: {
				OcslType src = readNumeric(program, types, written, op, 0, i, stage);
				int mask = op.operand(1);
				int len = OcslWire.swizzleLength(mask);
				for (int c = 0; c < len; c++) {
					if (OcslWire.swizzleComponent(mask, c) >= src.width) {
						throw new ValidationException(i, "swizzle reads component "
								+ "xyzw".charAt(OcslWire.swizzleComponent(mask, c)) + " of a "
								+ src.display());
					}
				}
				return OcslType.ofWidth(len);
			}
			case OcslWire.OP_SPLAT: {
				OcslType src = readNumeric(program, types, written, op, 0, i, stage);
				if (src != OcslType.FLOAT) {
					throw new ValidationException(i, "splat takes a float, got " + src.display());
				}
				OcslType out = OcslType.ofWidth(op.operand(1));
				if (out == null || out == OcslType.FLOAT) {
					throw new ValidationException(i, "splat width " + op.operand(1)
							+ " must be 2, 3 or 4");
				}
				return out;
			}
			case OcslWire.OP_CONS2: return components(program, types, written, op, i, stage, 2);
			case OcslWire.OP_CONS3: return components(program, types, written, op, i, stage, 3);
			case OcslWire.OP_CONS4: return components(program, types, written, op, i, stage, 4);
			case OcslWire.OP_CONS3_V2F:
				return composite(program, types, written, op, i, stage, OcslType.VEC2,
						OcslType.FLOAT, OcslType.VEC3);
			case OcslWire.OP_CONS4_V3F:
				return composite(program, types, written, op, i, stage, OcslType.VEC3,
						OcslType.FLOAT, OcslType.VEC4);
			case OcslWire.OP_CONS4_V2V2:
				return composite(program, types, written, op, i, stage, OcslType.VEC2,
						OcslType.VEC2, OcslType.VEC4);

			// --- bool producers and consumers. Scalar-only by the frozen decision: per-component
			// selection is written explicitly as mix(a, b, step(e, x)), which is already in the zoo.
			case OcslWire.OP_LT: case OcslWire.OP_LE: case OcslWire.OP_EQ: {
				for (int s = 0; s < 2; s++) {
					OcslType t = readNumeric(program, types, written, op, s, i, stage);
					if (t != OcslType.FLOAT) {
						throw new ValidationException(i, "comparisons are scalar-only, got "
								+ t.display());
					}
				}
				return OcslType.BOOL;
			}
			case OcslWire.OP_BAND: case OcslWire.OP_BOR: case OcslWire.OP_BNOT: {
				int n = code == OcslWire.OP_BNOT ? 1 : 2;
				for (int s = 0; s < n; s++) {
					OcslType t = readType(program, types, written, op, s, i, stage);
					if (t != OcslType.BOOL) {
						throw new ValidationException(i, "boolean ops take bool, got "
								+ t.display());
					}
				}
				return OcslType.BOOL;
			}
			case OcslWire.OP_SELECT: {
				OcslType cond = readType(program, types, written, op, 0, i, stage);
				if (cond != OcslType.BOOL) {
					throw new ValidationException(i, "select's condition is a bool scalar, got "
							+ cond.display());
				}
				OcslType a = readType(program, types, written, op, 1, i, stage);
				OcslType b = readType(program, types, written, op, 2, i, stage);
				if (a != b) {
					throw new ValidationException(i, "select's arms are " + a.display() + " and "
							+ b.display());
				}
				return a;
			}

			case OcslWire.OP_SAMPLE: {
				OcslType uv = readNumeric(program, types, written, op, 1, i, stage);
				if (uv != OcslType.VEC2) {
					throw new ValidationException(i, "sample takes a vec2 coordinate, got "
							+ uv.display());
				}
				return OcslType.VEC4;
			}
			case OcslWire.OP_ITOF:
				return OcslType.FLOAT;
			case OcslWire.OP_OUT:
			case OcslWire.OP_OUT_ABS:
				return null; // handled by the caller, which knows the property
			default:
				throw new ValidationException(i, "no type rule for opcode " + (code & 0xFF));
		}
	}

	/**
	 * Component-wise ops: every operand is either a float or THE one vector width, and floats are
	 * broadcast. The result is that vector width, or float when every operand is a float.
	 *
	 * Position-agnostic on purpose. SUB and DIV are not commutative and {@code k - v} is a real
	 * pattern, so a scalar is legal in any slot rather than only on the right. Two vector widths in
	 * one op is still nonsense and still refused — the broadcast allowance is exactly that, not a
	 * general coercion.
	 */
	private static OcslType componentWise(IrProgram program, OcslType[] types, boolean[] written,
			IrOp op, int i, byte stage, int arity) throws ValidationException {
		OcslType vector = null;
		for (int s = 0; s < arity; s++) {
			OcslType t = readNumeric(program, types, written, op, s, i, stage);
			if (t == OcslType.FLOAT) {
				continue;
			}
			if (vector != null && vector != t) {
				throw new ValidationException(i, OcslWire.shapeOf(op.opcode).name
						+ " mixes " + vector.display() + " and " + t.display()
						+ "; a float may broadcast, two vector widths may not");
			}
			vector = t;
		}
		return vector != null ? vector : OcslType.FLOAT;
	}

	private static OcslType uniform(IrProgram program, OcslType[] types, boolean[] written,
			IrOp op, int i, byte stage, int arity) throws ValidationException {
		OcslType first = readNumeric(program, types, written, op, 0, i, stage);
		for (int s = 1; s < arity; s++) {
			OcslType t = readNumeric(program, types, written, op, s, i, stage);
			if (t != first) {
				throw new ValidationException(i, OcslWire.shapeOf(op.opcode).name
						+ " is shape-uniform: operand 0 is " + first.display() + " but operand "
						+ s + " is " + t.display()
						+ ". Scalar broadcast is an explicit SPLAT, never implicit.");
			}
		}
		return first;
	}

	private static OcslType components(IrProgram program, OcslType[] types, boolean[] written,
			IrOp op, int i, byte stage, int n) throws ValidationException {
		for (int s = 0; s < n; s++) {
			OcslType t = readNumeric(program, types, written, op, s, i, stage);
			if (t != OcslType.FLOAT) {
				throw new ValidationException(i, "constructor component " + s + " is "
						+ t.display() + ", expected float");
			}
		}
		return OcslType.ofWidth(n);
	}

	private static OcslType composite(IrProgram program, OcslType[] types, boolean[] written,
			IrOp op, int i, byte stage, OcslType a, OcslType b, OcslType result)
			throws ValidationException {
		OcslType t0 = readNumeric(program, types, written, op, 0, i, stage);
		OcslType t1 = readNumeric(program, types, written, op, 1, i, stage);
		if (t0 != a || t1 != b) {
			throw new ValidationException(i, OcslWire.shapeOf(op.opcode).name + " takes ("
					+ a.display() + ", " + b.display() + "), got (" + t0.display() + ", "
					+ t1.display() + ")");
		}
		return result;
	}

	private static OcslType readNumeric(IrProgram program, OcslType[] types, boolean[] written,
			IrOp op, int slot, int i, byte stage) throws ValidationException {
		OcslType t = readType(program, types, written, op, slot, i, stage);
		if (t == OcslType.BOOL) {
			throw new ValidationException(i, "operand " + slot
					+ " is a bool; bools are conditions only and reach values through select");
		}
		return t;
	}
}
