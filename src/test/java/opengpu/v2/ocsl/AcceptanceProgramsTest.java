package opengpu.v2.ocsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * THE ACCEPTANCE WORKLOAD. The programs hand-compiled during the 2026-08-11 GLSL-120 codegen dry
 * run, transcribed op-for-op into the real encoding and put through the real validator.
 *
 * This is what makes the skeleton more than a green suite of its own invention. The dry run
 * computed each program's op count BY HAND under the amendment-5 convention and committed the
 * numbers; reproducing them from an independent implementation is the check that the encoding, the
 * accounting rule and the type system all say what the design says. A number that came out
 * different would mean one of the three was wrong — and the codec review already found exactly that
 * once, when three of these four turned out to need a `vec4(vec3, float)` opcode that did not exist.
 *
 * Register ids here are NOT the dry run's r0..r26. That listing carries its own warning — "register
 * ids below are PROVISIONAL — regenerate against the amendment-7 reserved table" — and this is that
 * regeneration: built-ins take their reserved ids from {@link SurfaceTable}, uniforms sit in the
 * uniform block, and working registers start at {@code WORKING_BASE}. The OPS and their ORDER are
 * transcribed exactly; only the numbering moves.
 */
public class AcceptanceProgramsTest {

	private static final int W = SurfaceTable.WORKING_BASE;

	private static int k(int i) {
		return OcslWire.OPERAND_CONST_FLAG | i;
	}

	private static int u(int i) {
		return SurfaceTable.UNIFORM_BASE + i;
	}

	/**
	 * P1 "plasma", pixel/material. Committed 23, now 22 -- see the op-17 note below.
	 *
	 * Transcribed from the dry run's listing 00..22. Two lines carry the decisions this project
	 * argued hardest over: op 00 is the scalar-vector multiply (`uv * 8.0` as ONE instruction),
	 * and op 17 WAS a builder-inserted splat before MIX until the 2026-08-12 broadcast re-opening
	 * removed the need for it -- which is why this program charges 22 where the dry run committed
	 * 23. Op 21's explicit tint modulate is the frozen "codegen adds no implicit modulate" rule.
	 */
	private static IrProgram plasma() {
		// k0=8.0 k1=1.3 k2=0.5 k3=1.2 k4=0.7 k5=6.0 k6=0.4 k7=1.0
		float[] consts = { 8.0f, 1.3f, 0.5f, 1.2f, 0.7f, 6.0f, 0.4f, 1.0f };
		// colorA/colorB are vec3 UNIFORMS in the dry run. v1 uniforms are float-typed on the wire,
		// so this transcription splats each from a uniform component -- 2 ops the dry run did not
		// need. They are counted as a named prologue rather than folded into the body, so the
		// committed number stays legible. Typed uniforms are committed design the wire does not
		// yet carry; closing that gap removes this prologue.
		List<IrOp> ops = new ArrayList<IrOp>();
		int r5 = W, r6 = W + 1, r7 = W + 2, r8 = W + 3, r9 = W + 4, r10 = W + 5, r11 = W + 6;
		int r12 = W + 7, r13 = W + 8, r14 = W + 9, r15 = W + 10, r16 = W + 11, r17 = W + 12;
		int r18 = W + 13, r19 = W + 14, r20 = W + 15, r21 = W + 16, r22 = W + 17, r23 = W + 18;
		int r24 = W + 19, r25 = W + 20, r26 = W + 21;
		int colorA = W + 22, colorB = W + 23;

		ops.add(new IrOp(OcslWire.OP_SPLAT, colorA, u(0), 3));
		ops.add(new IrOp(OcslWire.OP_SPLAT, colorB, u(1), 3));
		int prologue = 2;

		ops.add(new IrOp(OcslWire.OP_MUL, r5, SurfaceTable.REG_UV, k(0)));      // 00 MUL.vs
		ops.add(new IrOp(OcslWire.OP_SWZ, r6, r5, OcslWire.packSwizzle(0)));     // 01 SWIZ.x
		ops.add(new IrOp(OcslWire.OP_ADD, r7, r6, SurfaceTable.REG_TIME));       // 02
		ops.add(new IrOp(OcslWire.OP_SIN, r8, r7));                              // 03
		ops.add(new IrOp(OcslWire.OP_SWZ, r9, r5, OcslWire.packSwizzle(1)));     // 04 SWIZ.y
		ops.add(new IrOp(OcslWire.OP_MUL, r10, SurfaceTable.REG_TIME, k(1)));    // 05
		ops.add(new IrOp(OcslWire.OP_ADD, r11, r9, r10));                        // 06
		ops.add(new IrOp(OcslWire.OP_COS, r12, r11));                            // 07
		ops.add(new IrOp(OcslWire.OP_ADD, r13, r8, r12));                        // 08
		ops.add(new IrOp(OcslWire.OP_CONS2, r14, k(3), k(4)));                   // 09 CON2
		ops.add(new IrOp(OcslWire.OP_DOT, r15, r5, r14));                        // 10
		ops.add(new IrOp(OcslWire.OP_MUL, r16, SurfaceTable.REG_TIME, k(2)));    // 11
		ops.add(new IrOp(OcslWire.OP_ADD, r17, r15, r16));                       // 12
		ops.add(new IrOp(OcslWire.OP_SIN, r18, r17));                            // 13
		ops.add(new IrOp(OcslWire.OP_ADD, r19, r13, r18));                       // 14 w
		ops.add(new IrOp(OcslWire.OP_DIV, r20, r19, k(5)));                      // 15
		ops.add(new IrOp(OcslWire.OP_ADD, r21, r20, k(2)));                      // 16 t
		// Op 17 of the committed listing was `CON3 r21` -- a splat inserted solely to satisfy
		// amendment 4's shape-uniform zoo. That amendment was RE-OPENED 2026-08-12 and MIX now
		// takes a broadcast scalar weight directly, so the splat is gone and this program charges
		// 22 rather than the committed 23. P1's own listing flagged that op as an amendment-4
		// artifact, which is why it is the one count the re-opening moves; P2, P3 and P4 all keep
		// their committed numbers.
		ops.add(new IrOp(OcslWire.OP_MIX, r23, colorA, colorB, r21));            // 18 (was 17+18)
		ops.add(new IrOp(OcslWire.OP_MIX, r24, k(6), k(7), r21));                // 19
		ops.add(new IrOp(OcslWire.OP_CONS4_V3F, r25, r23, r24));                 // 20 CON4(v3,f)
		ops.add(new IrOp(OcslWire.OP_MUL, r26, r25, SurfaceTable.REG_TINT));     // 21 tint modulate
		ops.add(new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, r26));        // 22 OUT

		assertEquals("22 body ops after the amendment-4 re-opening, plus the prologue",
				22 + prologue, ops.size());
		return new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL, consts, ops,
				Arrays.asList("colorA", "colorB"), colorB + 1);
	}

	@Test
	public void plasmaValidatesAndChargesItsCommittedCount() throws Exception {
		IrProgram p = plasma();
		IrValidator.Validated v = IrValidator.validate(p);

		// 22, not the committed 23: the re-opening removed op 17's splat. The 2-op prologue is
		// the transcription's own (v1 uniforms are float-typed while the dry run assumed vec3).
		assertEquals("plasma charges 22 plus the transcription prologue", 24L, v.structuralOps);
		assertEquals("plasma performs no fetches", 0, v.fetches);
		assertEquals(OcslType.VEC4, v.typeOf(W + 21));
	}

	@Test
	public void plasmaRoundTripsThroughTheCodec() throws Exception {
		IrProgram p = plasma();
		byte[] blob = IrCodec.encode(p);
		IrProgram back = IrCodec.decode(blob, IrCodec.Source.TRANSIENT);
		assertEquals(p.ops(), back.ops());
		assertEquals(p.structuralCount(), back.structuralCount());
		// The encoding is canonical: the same program must produce the same bytes every time, or
		// the content hash -- which is the compile-cache key -- is not a function of the program.
		assertTrue(Arrays.equals(blob, IrCodec.encode(back)));
	}

	/**
	 * P2 "blur", pixel/post-chain. Committed: 101 ops (1 + 11×9 + OUT), 9/16 fetches, unroll
	 * product 9, 2 uniform components.
	 *
	 * The densest of the four and the one that exercises the most machinery at once: FOR with its
	 * init riding the encoding at zero charge, ITOF reading the counter, the post-chain register
	 * set (`uv` — the register whose absence made two-pass blur inexpressible in committed v1),
	 * the built-in `input` sampler, the fetch cap counted POST-UNROLL, and a vec4 constant seeding
	 * the fold. Two of its multiplies are scalar-vector (`mul.v2f`, `mul.v4f`), which is the third
	 * and fourth independent confirmation that arithmetic is not shape-uniform.
	 */
	private static IrProgram blur() {
		// c0 = 4.0, c1 = -0.125, c2 = 0.2041637 (verifier-corrected), c3 = vec4(0,0,0,0)
		float[][] consts = {
			{ 4.0f }, { -0.125f }, { 0.2041637f }, { 0.0f, 0.0f, 0.0f, 0.0f }
		};
		int r0 = W, acc = W + 1, r2 = W + 2, r3 = W + 3, r4 = W + 4, r5 = W + 5, r6 = W + 6;
		int r7 = W + 7, r8 = W + 8, r9 = W + 9, r10 = W + 10, r11 = W + 11;
		int dir = SurfaceTable.UNIFORM_BASE; // vec2 per-entry uniform, carried as 2 components

		List<IrOp> ops = new ArrayList<IrOp>();
		// 00 mul.v2v2 r0, inputTexelSize, dir -- `dir` is a vec2 uniform in the dry run; v1
		// uniforms are float-typed, so this transcription splats it. See the note in the test.
		ops.add(new IrOp(OcslWire.OP_SPLAT, r2, dir, 2));
		ops.add(new IrOp(OcslWire.OP_MUL, r0, SurfaceTable.REG_INPUT_TEXEL_SIZE, r2));
		int prologue = 1; // the splat the dry run did not need

		ops.add(new IrOp(OcslWire.OP_FOR, acc, 9, k(3)));                      // 01 init=c3, 0 ops
		ops.add(new IrOp(OcslWire.OP_ITOF, r3, 0));                            // 02 counter
		ops.add(new IrOp(OcslWire.OP_SUB, r4, r3, k(0)));                      // 03
		ops.add(new IrOp(OcslWire.OP_MUL, r5, r4, r4));                        // 04
		ops.add(new IrOp(OcslWire.OP_MUL, r6, r5, k(1)));                      // 05
		ops.add(new IrOp(OcslWire.OP_EXP, r7, r6));                            // 06
		ops.add(new IrOp(OcslWire.OP_MUL, r8, r7, k(2)));                      // 07
		ops.add(new IrOp(OcslWire.OP_MUL, r9, r0, r4));                        // 08 mul.v2f
		ops.add(new IrOp(OcslWire.OP_ADD, r10, SurfaceTable.REG_UV, r9));      // 09
		ops.add(new IrOp(OcslWire.OP_SAMPLE, r11, SurfaceTable.SLOT_INPUT, r10)); // 10 fetch
		ops.add(new IrOp(OcslWire.OP_MUL, r11, r11, r8));                      // 11 mul.v4f
		ops.add(new IrOp(OcslWire.OP_ADD, acc, acc, r11));                     // 12 accumulate
		ops.add(new IrOp(OcslWire.OP_ENDFOR, -1));                             // 13
		ops.add(new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, acc));      // 14

		assertEquals("the fold body must be the committed 11 ops", 11,
				ops.size() - prologue - 4);
		return new IrProgram(OcslWire.STAGE_PIXEL_POST, consts, ops,
				Arrays.asList("dirX", "dirY"), r11 + 1);
	}

	@Test
	public void blurValidatesAndChargesItsCommittedCount() throws Exception {
		IrValidator.Validated v = IrValidator.validate(blur());
		// Committed 101 = op 00 (1) + body (11 x 9 = 99) + OUT (1). FOR/ENDFOR and the init
		// operand charge 0, which is exactly why the fold needs a POOLED vec4 rather than a
		// constructed one -- building vec4(0) would charge an op the committed count excludes.
		assertEquals("blur charges its committed 101 plus the uniform-splat prologue",
				102L, v.structuralOps);
		assertEquals("9 taps, counted post-unroll", 9, v.fetches);
	}

	@Test
	public void blurWouldBlowTheFetchCapAtSeventeenTaps() throws Exception {
		// The dry run recorded 9/16 with the note that a 17-tap variant is refused. Pinning the
		// refusal here keeps the cap honest: a cap nothing ever trips is a cap nobody has tested.
		IrProgram p = blur();
		List<IrOp> ops = new ArrayList<IrOp>(p.ops());
		for (int i = 0; i < ops.size(); i++) {
			if (ops.get(i).opcode == OcslWire.OP_FOR) {
				ops.set(i, new IrOp(OcslWire.OP_FOR, ops.get(i).dst, 17, ops.get(i).operand(1)));
				break;
			}
		}
		try {
			IrValidator.validate(new IrProgram(OcslWire.STAGE_PIXEL_POST,
					new float[][] { { 4.0f }, { -0.125f }, { 0.2041637f },
							{ 0.0f, 0.0f, 0.0f, 0.0f } },
					ops, Arrays.asList("dirX", "dirY"), p.declaredRegisters));
			org.junit.Assert.fail("17 taps must exceed the fetch cap");
		} catch (ValidationException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("fetches post-unroll"));
		}
	}

	@Test
	public void aPooledVectorConstantCostsNoOp() throws Exception {
		// The finding this transcription produced: a fold seeded with vec4(0,0,0,0) needs a TYPED
		// constant pool. With a scalar-only pool the seed must be constructed, charging an op that
		// no committed count includes -- so the pool's shape is decided by the accounting rule,
		// not by convenience.
		IrProgram p = new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL,
				new float[][] { { 0.0f, 0.0f, 0.0f, 0.0f }, { 1.0f } },
				Arrays.asList(
						new IrOp(OcslWire.OP_FOR, W, 2, k(0)),
						new IrOp(OcslWire.OP_ADD, W, W, k(0)),
						new IrOp(OcslWire.OP_ENDFOR, -1),
						new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)),
				new ArrayList<String>(), W + 1);
		IrValidator.Validated v = IrValidator.validate(p);
		assertEquals(OcslType.VEC4, v.typeOf(W));
		assertEquals("2 iterations of one ADD, plus OUT", 3L, v.structuralOps);
	}

	/**
	 * P3 "dissolve", pixel/node-effect. Committed: 21 ops, 2/16 fetches — reproduced exactly.
	 *
	 * This program is why amendment 4 was re-opened. Its {@code r8 vec3 = MIX r7, c1, r6} feeds
	 * MIX a FLOAT weight (r6 comes from {@code SUB c0, r5}, both floats), and under the
	 * shape-uniform reading it needed a splat that took the count to 22. P4 has two more of the
	 * same. With broadcast restored to the component-wise ops, all three listings are legal as
	 * written and only P1 — whose splat was explicitly flagged as an amendment-4 artifact — moves.
	 *
	 * It also carries the SELECT probe the dry run built it around: whole-value strict pick over
	 * two vec3 arms, with the bool condition assembled from LE/LT/BAND.
	 */
	private static IrProgram dissolve() {
		float[][] consts = { { 1.0f }, { 1.0f, 0.35f, 0.05f } };
		int threshold = SurfaceTable.UNIFORM_BASE, edgeWidth = SurfaceTable.UNIFORM_BASE + 1;
		int r0 = W, r1 = W + 1, r2 = W + 2, r3 = W + 3, r4 = W + 4, r5 = W + 5, r6 = W + 6;
		int r7 = W + 7, r8 = W + 9, r9 = W + 10, r10 = W + 11, r11 = W + 12;
		int r12 = W + 13, r13 = W + 14, r14 = W + 15, r15 = W + 16, r16 = W + 17;
		int r17 = W + 18, r18 = W + 19, r19 = W + 20;

		return new IrProgram(OcslWire.STAGE_PIXEL_EFFECT, consts, Arrays.asList(
				new IrOp(OcslWire.OP_SAMPLE, r0, SurfaceTable.SLOT_INPUT, SurfaceTable.REG_UV),
				new IrOp(OcslWire.OP_SAMPLE, r1, 1, SurfaceTable.REG_UV),
				new IrOp(OcslWire.OP_SWZ, r2, r1, OcslWire.packSwizzle(0)),
				new IrOp(OcslWire.OP_STEP, r3, threshold, r2),
				new IrOp(OcslWire.OP_ADD, r4, threshold, edgeWidth),
				new IrOp(OcslWire.OP_SMOOTHSTEP, r5, threshold, r4, r2),
				new IrOp(OcslWire.OP_SUB, r6, k(0), r5),
				new IrOp(OcslWire.OP_SWZ, r7, r0, OcslWire.packSwizzle(0, 1, 2)),
				// MIX takes r6 -- a float -- as its weight, broadcast. Exactly as the committed
				// listing writes it, which is what the amendment-4 re-opening restored.
				new IrOp(OcslWire.OP_MIX, r8, r7, k(1), r6),
				new IrOp(OcslWire.OP_LE, r9, threshold, r2),
				new IrOp(OcslWire.OP_LT, r10, r2, r4),
				new IrOp(OcslWire.OP_BAND, r11, r9, r10),
				new IrOp(OcslWire.OP_SELECT, r12, r11, r8, r7),
				new IrOp(OcslWire.OP_SWZ, r13, SurfaceTable.REG_TINT, OcslWire.packSwizzle(0, 1, 2)),
				new IrOp(OcslWire.OP_MUL, r14, r12, r13),
				new IrOp(OcslWire.OP_SWZ, r15, r0, OcslWire.packSwizzle(3)),
				new IrOp(OcslWire.OP_MUL, r16, r15, r3),
				new IrOp(OcslWire.OP_SWZ, r17, SurfaceTable.REG_TINT, OcslWire.packSwizzle(3)),
				new IrOp(OcslWire.OP_MUL, r18, r16, r17),
				new IrOp(OcslWire.OP_CONS4_V3F, r19, r14, r18),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, r19)),
				Arrays.asList("threshold", "edgeWidth"), r19 + 1);
	}

	@Test
	public void dissolveValidatesAndChargesItsCommittedCount() throws Exception {
		IrValidator.Validated v = IrValidator.validate(dissolve());
		assertEquals("dissolve reproduces its committed 21 exactly", 21L, v.structuralOps);
		assertEquals("two fetches: the built-in input sampler and one bound slot", 2, v.fetches);
		// The SELECT probe the dry run built this program around: whole-value strict pick over
		// two vec3 arms, with a bool condition assembled from LE/LT/BAND.
		assertEquals(OcslType.VEC3, v.typeOf(W + 13));
	}

	@Test
	public void componentWiseOpsBroadcastAFloatButNeverTwoVectorWidths() throws Exception {
		// The re-opened rule, stated as one behaviour rather than a mix special case: a float
		// stands in for a vector wherever the op is component-wise. Checked across the arities so
		// the rule is pinned as a rule, not as three coincidences.
		int[] ternary = { OcslWire.OP_MIX, OcslWire.OP_CLAMP, OcslWire.OP_SMOOTHSTEP };
		for (int code : ternary) {
			IrProgram p = new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL,
					new float[][] { { 0.5f }, { 0.0f, 0.0f, 0.0f, 0.0f } },
					Arrays.asList(
							new IrOp((byte) code, W, k(1), k(1), k(0)), // vec4, vec4, float
							new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)),
					new ArrayList<String>(), W + 1);
			assertEquals("broadcast must yield the vector width", OcslType.VEC4,
					IrValidator.validate(p).typeOf(W));
			assertEquals("and cost exactly one op", 2L, p.structuralCount());
		}

		// The allowance is broadcast, not coercion: two different vector widths stay nonsense.
		try {
			IrValidator.validate(new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL,
					new float[][] { { 0.0f, 0.0f }, { 0.0f, 0.0f, 0.0f, 0.0f } },
					Arrays.asList(
							new IrOp(OcslWire.OP_MIX, W, k(1), k(0), k(0)),
							new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)),
					new ArrayList<String>(), W + 1));
			org.junit.Assert.fail("vec4 mixed with vec2 must be refused");
		} catch (ValidationException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("two vector widths may not"));
		}
	}

	/**
	 * P4 "domains", pixel/material. Committed: 65 static ops (24 pre + 10 body + 31 post), a
	 * 4-iteration fold, post-unroll 24 + 4×10 + 31 = 95, **96 with OUT**. Fetches 4/16.
	 *
	 * THE FULL TRANSCRIPTION NOW LIVES IN {@link OcslBuilderTest}, authored through the builder and
	 * checked op-for-op against the dry run's listing, with the guard sites actually executed on
	 * the VM. That was the condition this test's earlier note set: P4 exists to probe `NORM` of a
	 * zero vector, `ATAN2(0,0)`, `LOG(x≤0)`, `POW(base<0)` and the `DIV` whose divisor reaches 0
	 * at uv.x=0.5, all of which are numeric-domain behaviour belonging to the CPU VM — which did
	 * not exist when P4 was deferred and does now.
	 *
	 * What remains here is a genuinely independent check and not a leftover: that the post-unroll
	 * accounting reproduces P4's committed SHAPE — a fold in a material program with a body of 10
	 * and a trip count of 4 — computed from ops that are representative rather than P4's own. It
	 * reaches 96 by a different route than the transcription does, so a transcription that drifted
	 * would not drag this with it.
	 */
	@Test
	public void domainsCountShapeReproducesUnderPostUnrollAccounting() throws Exception {
		List<IrOp> ops = new ArrayList<IrOp>();
		for (int i = 0; i < 24; i++) {                       // 24 pre-loop ops
			ops.add(new IrOp(OcslWire.OP_ADD, W, k(0), k(0)));
		}
		ops.add(new IrOp(OcslWire.OP_FOR, W + 1, 4, k(0)));  // charges 0
		for (int i = 0; i < 10; i++) {                       // 10 body ops, x4 iterations
			ops.add(new IrOp(OcslWire.OP_ADD, W + 1, W + 1, k(0)));
		}
		ops.add(new IrOp(OcslWire.OP_ENDFOR, -1));           // charges 0
		for (int i = 0; i < 30; i++) {                       // 31 post ops, the last being CONS4
			ops.add(new IrOp(OcslWire.OP_ADD, W, k(0), k(0)));
		}
		ops.add(new IrOp(OcslWire.OP_SPLAT, W + 2, W, 4));
		ops.add(new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 2));

		IrProgram p = new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, ops,
				new ArrayList<String>(), W + 3);
		// 65 is the dry run's COMPUTE-op count: FOR/ENDFOR are encoding structure and OUT is
		// counted separately ("95 ... 96 with OUT"), so three of the listed ops are outside it.
		assertEquals("65 static compute ops as the dry run counted them", 65, ops.size() - 3);
		assertEquals("24 + 4x10 + 31 + OUT = 96", 96L, IrValidator.validate(p).structuralOps);
	}

	@Test
	public void theScalarVectorMultiplyIsOneOpNotTwo() throws Exception {
		// The single line that decides three of the four committed counts. `uv * 8.0` is ONE
		// instruction: the IR's op scope names "scalar<->vector ops" as its own category, and the
		// shape-uniform rule governs the function zoo. Read the other way, every scale in every
		// program costs an extra splat and no committed count reproduces.
		IrProgram p = new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 8.0f, 1.0f },
				Arrays.asList(
						new IrOp(OcslWire.OP_MUL, W, SurfaceTable.REG_UV, k(0)),
						new IrOp(OcslWire.OP_CONS4_V2V2, W + 1, W, W),
						new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 1)),
				new ArrayList<String>(), W + 2);
		IrValidator.Validated v = IrValidator.validate(p);
		assertEquals(OcslType.VEC2, v.typeOf(W));
		assertEquals(3L, v.structuralOps);
	}

	@Test
	public void theReducingOpsStayStrictWhileComponentWiseOnesBroadcast() throws Exception {
		// What survives the re-opening. Broadcast is permitted exactly where it means something:
		// DOT, DISTANCE, CROSS and NORMALIZE reduce or fix their shape, so a scalar operand has no
		// reading there and is still refused. That boundary is the rule -- not "the zoo is strict".
		IrProgram dot = new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL,
				new float[][] { { 1.0f }, { 0.0f, 0.0f } },
				Arrays.asList(
						new IrOp(OcslWire.OP_DOT, W, k(1), k(0)),
						new IrOp(OcslWire.OP_SPLAT, W + 1, W, 4),
						new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W + 1)),
				new ArrayList<String>(), W + 2);
		try {
			IrValidator.validate(dot);
			org.junit.Assert.fail("dot(vec2, float) must be refused");
		} catch (ValidationException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("shape-uniform"));
		}

		// And the component-wise side, for contrast, in the same test so the boundary is visible.
		IrProgram mix = new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL,
				new float[][] { { 0.5f }, { 0.0f, 0.0f, 0.0f, 0.0f } },
				Arrays.asList(
						new IrOp(OcslWire.OP_MIX, W, k(1), k(1), k(0)),
						new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, W)),
				new ArrayList<String>(), W + 1);
		assertEquals(2L, IrValidator.validate(mix).structuralOps);
	}
}
