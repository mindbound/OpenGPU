package opengpu.v2.ocsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import opengpu.v2.protocol.CodecException;

/**
 * Structural codec tests for the OCSL IR.
 *
 * Two of these pin GATES rather than behaviour — the animator tripwire and the format-0
 * persistence refusal — and both are written so they cannot pass vacuously. That matters more than
 * usual here: a gate that fires for the wrong reason looks identical to one that works, and this
 * repo has already shipped one guard that could not fail.
 */
public class IrCodecTest {

	private static IrProgram program(byte stage, float[] constants, int registers, IrOp... ops) {
		return new IrProgram(stage, constants, Arrays.asList(ops), new ArrayList<String>(), registers);
	}

	/** k0 * k1 -> r0, OUT COLOR r0. The smallest legal pixel program. */
	private static IrProgram trivial() {
		return program(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 2.0f, 3.0f }, 1,
				new IrOp(OcslWire.OP_MUL, 0, OcslWire.OPERAND_CONST_FLAG, OcslWire.OPERAND_CONST_FLAG | 1),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, 0));
	}

	@Test
	public void theAbsoluteOutputFormRoundTripsAndCarriesOutsShape() throws Exception {
		// ANIM-7's opcode, added 2026-08-13 while FORMAT_VERSION was still 0 and no blob existed —
		// that window closed with the 3.1 freeze, so a further output form now needs a version
		// bump and a migration rather than just a shape row. IrCodec has no
		// OP_OUT reference at all and dispatches through shapeOf, so "does it round-trip" is really
		// "is the shape row registered" -- and a missing shape() call is the failure that would
		// otherwise surface much later as a decoder refusing its own encoder's output.
		OcslWire.Shape out = OcslWire.shapeOf(OcslWire.OP_OUT);
		OcslWire.Shape abs = OcslWire.shapeOf(OcslWire.OP_OUT_ABS);
		assertNotNull("OP_OUT_ABS without a shape decodes as an unknown opcode", abs);
		assertEquals("OUT_ABS", abs.name);
		assertEquals("the forms carry identical operands", out.operandCount(), abs.operandCount());
		assertTrue("neither form writes a register", out.hasDst == abs.hasDst);
		assertEquals("and they cost the same", out.structuralCharge, abs.structuralCharge);

		IrProgram p = program(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 2.0f, 3.0f }, 1,
				new IrOp(OcslWire.OP_MUL, 0, OcslWire.OPERAND_CONST_FLAG,
						OcslWire.OPERAND_CONST_FLAG | 1),
				new IrOp(OcslWire.OP_OUT_ABS, -1, OcslWire.PROP_COLOR, 0));
		IrProgram back = dec(enc(p));
		// The FORM has to survive, not merely the program: a codec that collapsed the distinction
		// would hand back OP_OUT and every absolute program would then compose relatively --
		// silently, and exactly once per frame per node.
		assertEquals(OcslWire.OP_OUT_ABS, back.ops().get(1).opcode);
		assertTrue("the form must not decay to OP_OUT", back.ops().get(1).opcode != OcslWire.OP_OUT);
		assertEquals(OcslWire.PROP_COLOR, back.ops().get(1).operand(0));
	}

	private static byte[] enc(IrProgram p) throws Exception {
		return IrCodec.encode(p);
	}

	private static IrProgram dec(byte[] b) throws Exception {
		return IrCodec.decode(b, IrCodec.Source.TRANSIENT);
	}

	/**
	 * Where the op stream starts, DERIVED from the program rather than hardcoded.
	 *
	 * Two tests poke individual bytes and both broke when the constant pool became typed — a magic
	 * offset is a second, silent copy of the layout that only announces itself as an unrelated
	 * assertion failure ("op count 31490 exceeds the cap"). Deriving it means the next layout
	 * change moves one function.
	 */
	private static int opStreamOffset(IrProgram p) {
		int off = 4 + 2 + 1 + 1 + 2; // magic, format version, stage, reserved, constant count
		for (int i = 0; i < p.constantCount(); i++) {
			off += 1 + 4 * p.constantWidth(i); // width tag + f32 components
		}
		return off + 2 + 2; // declared registers, op count
	}

	/** Encoded size of one op: opcode byte, dst short, and one short per operand. */
	private static int opSize(IrOp op) {
		return 1 + 2 + 2 * op.operandCount();
	}

	private static void expectReject(byte[] blob, String messageFragment) {
		try {
			IrCodec.decode(blob, IrCodec.Source.TRANSIENT);
			fail("expected rejection mentioning \"" + messageFragment + "\"");
		} catch (CodecException e) {
			assertTrue("message was: " + e.getMessage(),
					e.getMessage().toLowerCase().contains(messageFragment.toLowerCase()));
		}
	}

	// ---------------------------------------------------------------- round trip

	@Test
	public void roundTripsATrivialProgram() throws Exception {
		IrProgram in = trivial();
		IrProgram out = dec(enc(in));
		assertEquals(in.stage, out.stage);
		assertEquals(in.declaredRegisters, out.declaredRegisters);
		assertEquals(in.constantCount(), out.constantCount());
		assertEquals(2.0f, out.constantComponent(0, 0), 0.0f);
		assertEquals(in.ops(), out.ops());
	}

	@Test
	public void roundTripsEveryOpcodeShape() throws Exception {
		// Any opcode the shape table knows must survive encode/decode with its declared arity.
		// This is what stops a new op being added to the table with an arity the codec cannot
		// carry -- the failure would otherwise surface only when someone first emitted one.
		//
		// SCANNED, not bounded by a literal. This read `i <= 80`, the ceiling on the day it was
		// written, so OP_OUT_ABS at 81 was exempt from the one general round-trip guard the codec
		// has -- and the guard below could not notice, because it compared against a hardcoded 40
		// while 48 shapes are registered, leaving room for eight opcodes to vanish from the walk
		// under a message that says "the whole table". Proven by mutation: collapsing OP_OUT_ABS to
		// OP_OUT inside IrCodec.decode left this test GREEN and was caught only by the one test
		// written for that opcode specifically.
		int registered = 0;
		for (int i = 0; i <= 255; i++) {
			if (OcslWire.shapeOf((byte) i) != null) {
				registered++;
			}
		}
		int checked = 0;
		for (int i = 0; i <= 255; i++) {
			OcslWire.Shape shape = OcslWire.shapeOf((byte) i);
			if (shape == null) {
				continue;
			}
			int[] operands = new int[shape.operandCount()];
			for (int k = 0; k < operands.length; k++) {
				switch (shape.operandKinds[k]) {
					case OcslWire.KIND_VALUE: operands[k] = 0; break;
					case OcslWire.KIND_IMMEDIATE: operands[k] = 0; break;
					case OcslWire.KIND_PROPERTY: operands[k] = OcslWire.PROP_COLOR; break;
					case OcslWire.KIND_SLOT: operands[k] = 0; break;
					case OcslWire.KIND_SWIZZLE: operands[k] = OcslWire.packSwizzle(0); break;
					default: fail("unhandled operand kind");
				}
			}
			List<IrOp> ops = new ArrayList<IrOp>();
			boolean needsLoop = i == OcslWire.OP_ITOF || i == OcslWire.OP_ENDFOR;
			if (needsLoop) {
				ops.add(new IrOp(OcslWire.OP_FOR, 0, 2, OcslWire.OPERAND_CONST_FLAG));
			}
			ops.add(new IrOp((byte) i, shape.hasDst ? 0 : -1, operands));
			if (i == OcslWire.OP_FOR) {
				ops.add(new IrOp(OcslWire.OP_ENDFOR, -1));
			} else if (i == OcslWire.OP_ITOF) {
				ops.add(new IrOp(OcslWire.OP_ENDFOR, -1));
			}
			IrProgram p = new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f },
					ops, new ArrayList<String>(), 1);
			IrProgram back = dec(enc(p));
			assertEquals(shape.name + " did not round-trip", p.ops(), back.ops());
			checked++;
		}
		// EVERY registered shape, not "most of them". A floor admits skips; equality against the
		// table's own size fires if a later edit adds a `continue` for an awkward op.
		assertTrue("expected the wire to register shapes at all, saw " + registered, registered > 20);
		assertEquals("every registered shape must round-trip, not merely most", registered, checked);
	}

	@Test
	public void roundTripsATypedConstantPool() throws Exception {
		// Width IS the type tag -- there is no vec1, so a 1-wide entry is a float and no separate
		// type byte can disagree with the payload length. Two acceptance programs need this: a
		// fold seeded with a pooled vec4 costs no op, whereas constructing the seed would charge
		// one that no committed count includes.
		IrProgram p = new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL,
				new float[][] { { 1.5f }, { 0.0f, 0.0f, 0.0f, 0.0f }, { 1.0f, 0.35f, 0.05f } },
				Arrays.asList(new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, k(1))),
				new ArrayList<String>(), 1);
		IrProgram back = dec(enc(p));
		assertEquals(3, back.constantCount());
		assertEquals(OcslType.FLOAT, back.constantType(0));
		assertEquals(OcslType.VEC4, back.constantType(1));
		assertEquals(OcslType.VEC3, back.constantType(2));
		assertEquals(0.35f, back.constantComponent(2, 1), 0.0f);
		assertTrue(Arrays.equals(enc(p), enc(back)));
	}

	@Test
	public void rejectsAConstantOfIllegalWidth() throws Exception {
		byte[] blob = enc(trivial());
		// The width byte sits right after the constant count (magic 4 + version 2 + stage 1
		// + reserved 1 + count 2).
		blob[10] = 5;
		expectReject(blob, "components; 1..4 only");
	}

	private static int k(int i) {
		return OcslWire.OPERAND_CONST_FLAG | i;
	}

	@Test
	public void carriesTheNameTable() throws Exception {
		IrProgram p = new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f },
				Arrays.asList(new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR,
						OcslWire.OPERAND_CONST_FLAG)),
				Arrays.asList("strength", "dir2"), 1);
		assertEquals(Arrays.asList("strength", "dir2"), dec(enc(p)).names());
	}

	// ---------------------------------------------------------------- the two gates

	@Test
	public void theAnimatorStageDecodesAndComputeStillDoesNot() throws Exception {
		// INVERTED 2026-08-13. This test used to patch an animator stage byte into a blob and assert
		// the decoder refused it with a pointer at the dry run; deleting that branch is the act that
		// opened the surface. What the test is FOR survives unchanged: the decoder distinguishes a
		// reserved stage from an open one and from an unknown one, and it now has to get all three
		// right rather than two.
		byte[] animator = enc(program(OcslWire.STAGE_ANIMATOR, new float[] { 1.0f }, 1,
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_ANIM_X, OcslWire.OPERAND_CONST_FLAG)));
		IrProgram back = dec(animator);
		assertEquals("an open surface's blob round-trips", OcslWire.STAGE_ANIMATOR, back.stage);

		// THE STAGE BYTE IS PATCHED for compute, because `encode()` refuses it -- IrStructure has
		// the reserved-stage check its javadoc always claimed, closing the case where encode()
		// emitted bytes decode() rejects. Patching is also the more faithful test: the decoder is
		// the security boundary, so what it must refuse is bytes from an encoder we do not control
		// -- a hostile one, or simply an older build from before the tripwire.
		byte[] blob = enc(program(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, 1,
				new IrOp(OcslWire.OP_OUT, -1, 0, OcslWire.OPERAND_CONST_FLAG)));
		blob[6] = OcslWire.STAGE_COMPUTE; // 4-byte magic, 2-byte version, then the stage
		try {
			IrCodec.decode(blob, IrCodec.Source.TRANSIENT);
			fail("compute must be refused while it is reserved");
		} catch (CodecException e) {
			// Non-vacuous in the way that matters: a generic "unknown stage" rejection would
			// satisfy a laxer assertion while the tripwire and its pointer never existed.
			assertTrue("the refusal must carry the pointer, got: " + e.getMessage(),
					e.getMessage().contains("DESIGN-RENDERER-V2"));
		}
	}

	/**
	 * The encoder refuses the reserved surfaces too, which is the half that was missing.
	 *
	 * {@code IrStructure}'s javadoc listed "a reserved stage" among the things {@code encode()}
	 * emitted and {@code decode()} then refused, and implemented every item on that list except
	 * that one — so a hand-built animator program encoded to a valid 36-byte blob no decoder would
	 * take back.
	 *
	 * The animator left this list when its surface opened (2026-08-13), so compute is the only
	 * reserved stage remaining. A one-element sweep would be a weak test on its own, so the open
	 * side is asserted alongside it: the refusal has to be about RESERVATION, not about "any stage
	 * that is not the pixel family", and with one entry left nothing else distinguishes those.
	 */
	@Test
	public void encodeRefusesTheReservedSurfacesRatherThanEmittingUndecodableBytes() throws Exception {
		enc(program(OcslWire.STAGE_ANIMATOR, new float[] { 1.0f }, 1,
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_ANIM_X, OcslWire.OPERAND_CONST_FLAG)));

		byte[] stages = { OcslWire.STAGE_COMPUTE };
		for (int i = 0; i < stages.length; i++) {
			try {
				enc(program(stages[i], new float[] { 1.0f }, 1,
						new IrOp(OcslWire.OP_OUT, -1, 0, OcslWire.OPERAND_CONST_FLAG)));
				fail("encode() must refuse reserved stage " + (stages[i] & 0xFF)
						+ " rather than emit bytes that no decoder accepts");
			} catch (CodecException e) {
				assertTrue("the refusal should say the surface is reserved, got: " + e.getMessage(),
						e.getMessage().contains("RESERVED"));
			}
		}
	}

	@Test
	public void aReservedRefusalIsDistinctFromAnUnknownStage() throws Exception {
		// The other half of the same guarantee. If a genuinely unknown id also produced the
		// pointer, the test above would pass with no tripwire in the code at all.
		//
		// RE-AIMED AT COMPUTE 2026-08-13. It asserted that an unknown stage does not claim to be the
		// ANIMATOR, and the animator's pointer no longer exists — so the assertion had become true
		// of every possible message and pinned nothing. It did not fail when the surface opened,
		// which is exactly why a passing test is not evidence that it still checks something.
		byte[] blob = enc(trivial());
		blob[6] = (byte) 99;
		try {
			IrCodec.decode(blob, IrCodec.Source.TRANSIENT);
			fail("an unknown stage must be refused");
		} catch (CodecException e) {
			assertTrue("unknown stages must NOT claim to be a reserved one: " + e.getMessage(),
					!e.getMessage().contains("DESIGN-RENDERER-V2"));
			assertTrue(e.getMessage().toLowerCase().contains("unknown program stage"));
		}
	}

	/**
	 * RE-AIMED 2026-08-18, when Phase 3.1 opened persistence and FORMAT_VERSION froze at 1.
	 *
	 * The asymmetry this used to assert is gone by design: a format-0 blob is now refused from
	 * EVERY source, because 0 is a past version rather than the current one. Left as written, the
	 * test would have failed on its first line — a current blob is now legal from a save, which is
	 * the entire point of the freeze.
	 *
	 * What survives is worth keeping and is what this now pins: a pre-release blob found in a save
	 * gets the SPECIFIC diagnosis, not the generic version mismatch. That depends on the two checks
	 * being in the right ORDER in the decoder, and nothing else would notice if they were swapped —
	 * both orders refuse, only the message differs.
	 */
	@Test
	public void aPreReleaseBlobFromASaveGetsTheSpecificDiagnosisNotTheGenericOne() throws Exception {
		byte[] current = enc(trivial());
		assertNotNull("a CURRENT blob must be readable from a save now that programs persist",
				IrCodec.decode(current, IrCodec.Source.PERSISTED));

		// Hand-craft the pre-release envelope: same bytes, version short forced back to 0.
		byte[] preRelease = enc(trivial());
		preRelease[4] = 0;
		preRelease[5] = 0;

		try {
			IrCodec.decode(preRelease, IrCodec.Source.PERSISTED);
			fail("a format-0 blob must not be accepted from a world save");
		} catch (CodecException e) {
			assertTrue("expected the pre-release explanation, got: " + e.getMessage(),
					e.getMessage().contains("pre-release"));
			// The exclusion: the generic refusal also fires on these bytes and also contains the
			// word "format", so asserting on that would pass with the checks in either order.
			assertTrue("this is the GENERIC message; the specific check must run first: "
					+ e.getMessage(), !e.getMessage().contains("this build reads"));
		}
		// And off the wire the same bytes get the ordinary version refusal, since there is no
		// save to explain.
		try {
			IrCodec.decode(preRelease, IrCodec.Source.TRANSIENT);
			fail("a format-0 blob is not this build's format from any source");
		} catch (CodecException e) {
			assertTrue("expected the version refusal, got: " + e.getMessage(),
					e.getMessage().contains("this build reads"));
		}
	}

	// ---------------------------------------------------------------- malformed input

	@Test
	public void rejectsBadMagic() throws Exception {
		byte[] blob = enc(trivial());
		blob[0] = 'X';
		expectReject(blob, "not an ocsl program");
	}

	@Test
	public void rejectsAFutureFormatVersion() throws Exception {
		byte[] blob = enc(trivial());
		blob[4] = 0;
		blob[5] = 7;
		expectReject(blob, "format v7");
	}

	@Test
	public void rejectsTruncation() throws Exception {
		byte[] full = enc(trivial());
		for (int cut = 1; cut < full.length; cut++) {
			byte[] shortened = Arrays.copyOf(full, cut);
			try {
				IrCodec.decode(shortened, IrCodec.Source.TRANSIENT);
				fail("truncation at " + cut + " of " + full.length + " decoded anyway");
			} catch (CodecException expected) {
				// every prefix must be refused, never partially accepted
			}
		}
	}

	@Test
	public void rejectsTrailingData() throws Exception {
		byte[] full = enc(trivial());
		byte[] padded = Arrays.copyOf(full, full.length + 1);
		expectReject(padded, "trailing data");
	}

	@Test
	public void rejectsAnUnknownOpcode() throws Exception {
		IrProgram p = trivial();
		byte[] blob = enc(p);
		blob[opStreamOffset(p)] = (byte) 123;
		expectReject(blob, "unknown opcode");
	}

	@Test
	public void rejectsAWriteOutsideTheDeclaredRegisters() throws Exception {
		expectRejectOnEncodeOrDecode(program(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, 1,
				new IrOp(OcslWire.OP_ABS, 5, OcslWire.OPERAND_CONST_FLAG)), "outside the declared");
	}

	@Test
	public void rejectsAReadOutsideTheConstantPool() throws Exception {
		expectRejectOnEncodeOrDecode(program(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, 1,
				new IrOp(OcslWire.OP_ABS, 0, OcslWire.OPERAND_CONST_FLAG | 9)), "outside the pool");
	}

	@Test
	public void rejectsANonFiniteConstant() throws Exception {
		expectRejectOnEncodeOrDecode(program(OcslWire.STAGE_PIXEL_MATERIAL,
				new float[] { Float.POSITIVE_INFINITY }, 1,
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, OcslWire.OPERAND_CONST_FLAG)),
				"non-finite");
	}

	@Test
	public void rejectsUnbalancedLoops() throws Exception {
		expectRejectOnEncodeOrDecode(program(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, 1,
				new IrOp(OcslWire.OP_FOR, 0, 3, OcslWire.OPERAND_CONST_FLAG)), "left open");
		expectRejectOnEncodeOrDecode(program(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, 1,
				new IrOp(OcslWire.OP_ENDFOR, -1)), "closes no loop");
	}

	@Test
	public void rejectsItofOutsideALoopAndBeyondTheOpenDepth() throws Exception {
		// A3 pins ITOF's operand as a loop-DEPTH selector rather than a frame index, which only
		// means anything if the depth is checked against the loops actually open here.
		expectRejectOnEncodeOrDecode(program(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, 1,
				new IrOp(OcslWire.OP_ITOF, 0, 0)), "outside any loop");
		expectRejectOnEncodeOrDecode(program(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, 1,
				new IrOp(OcslWire.OP_FOR, 0, 3, OcslWire.OPERAND_CONST_FLAG),
				new IrOp(OcslWire.OP_ITOF, 0, 4),
				new IrOp(OcslWire.OP_ENDFOR, -1)), "selects loop depth");
	}

	@Test
	public void rejectsANonCanonicalSwizzle() throws Exception {
		// Components past the declared length must be zero. Two encodings of one swizzle would
		// fork the content hash on a difference no program can observe.
		int sloppy = OcslWire.packSwizzle(1) | (2 << 2);
		expectRejectOnEncodeOrDecode(program(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, 1,
				new IrOp(OcslWire.OP_SWZ, 0, OcslWire.OPERAND_CONST_FLAG, sloppy)), "canonical");
	}

	@Test
	public void rejectsNamesOutsideTheCharset() throws Exception {
		IrProgram p = new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f },
				Arrays.asList(new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR,
						OcslWire.OPERAND_CONST_FLAG)),
				Arrays.asList("x; } void main"), 1);
		expectRejectOnEncodeOrDecode(p, "outside [a-za-z0-9_]");
	}

	private static void expectRejectOnEncodeOrDecode(IrProgram p, String fragment) {
		try {
			byte[] blob = IrCodec.encode(p);
			expectReject(blob, fragment);
		} catch (CodecException e) {
			assertTrue("encode rejected with the wrong message: " + e.getMessage(),
					e.getMessage().toLowerCase().contains(fragment.toLowerCase()));
		}
	}

	// ---------------------------------------------------------------- canonical-form guards
	// Both guards existed from the first draft and neither was pinned, so a later "simplification"
	// could have deleted either and left a green suite. Canonical form IS the content hash.

	@Test
	public void rejectsANonZeroReservedHeaderByte() throws Exception {
		byte[] blob = enc(trivial());
		blob[7] = 1;
		expectReject(blob, "reserved header byte");
	}

	@Test
	public void rejectsANonZeroSlotOnAnOpThatWritesNoDestination() throws Exception {
		// OUT writes no register, so its dst slot must be 0 -- otherwise one program has two byte
		// encodings and the cache key forks on a difference nothing can observe.
		IrProgram p = trivial();
		byte[] blob = enc(p);
		int outStart = opStreamOffset(p) + opSize(p.ops().get(0)); // past the MUL
		blob[outStart + 1] = 0;
		blob[outStart + 2] = 3;
		expectReject(blob, "writes no destination but its slot is 3");
	}

	// ---------------------------------------------------------------- the composite constructors

	@Test
	public void encodesEveryFrozenConstructorFamilyAsOneChargedOp() throws Exception {
		// The frozen arity table names four families: vecN from N floats, vec(N-1)+float,
		// vec4 from 2xvec2, and splat. Three of the four acceptance programs build their result
		// with vec4(vec3,float) as ONE instruction; without an opcode for it the builder must
		// lower to 3 swizzles + CONS4 and those programs charge 3 more than their committed count.
		byte[] composites = { OcslWire.OP_CONS3_V2F, OcslWire.OP_CONS4_V3F, OcslWire.OP_CONS4_V2V2 };
		for (byte op : composites) {
			OcslWire.Shape shape = OcslWire.shapeOf(op);
			assertNotNull("composite constructor " + op + " has no shape", shape);
			assertEquals(shape.name + " must take two operands", 2, shape.operandCount());
			assertEquals(shape.name + " must charge exactly 1", 1, shape.structuralCharge);
			IrProgram p = program(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, 2,
					new IrOp(op, 0, OcslWire.OPERAND_CONST_FLAG, OcslWire.OPERAND_CONST_FLAG),
					new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, 0));
			assertEquals(shape.name + " did not round-trip", p.ops(), dec(enc(p)).ops());
			assertEquals(shape.name + " program must charge 2", 2L, p.structuralCount());
		}
	}

	// ---------------------------------------------------------------- structural count (A2)

	@Test
	public void structuralCountChargesEveryInstructionAndNotLoopStructure() {
		// MUL + OUT = 2. FOR/ENDFOR charge 0, so a bare loop adds nothing.
		assertEquals(2L, trivial().structuralCount());
	}

	@Test
	public void structuralCountIsPostUnrollAndMultipliesWithNesting() {
		// 3 iterations x (ADD) + OUT = 4. The count is what the ~256 cap is stated against, and it
		// must be unrolling-INVARIANT so an interpreting VM and unrolled codegen agree.
		IrProgram single = program(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, 1,
				new IrOp(OcslWire.OP_FOR, 0, 3, OcslWire.OPERAND_CONST_FLAG),
				new IrOp(OcslWire.OP_ADD, 0, 0, OcslWire.OPERAND_CONST_FLAG),
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, 0));
		assertEquals(4L, single.structuralCount());

		// Nested: 3 x 4 x ADD = 12, plus OUT.
		IrProgram nested = program(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, 1,
				new IrOp(OcslWire.OP_FOR, 0, 3, OcslWire.OPERAND_CONST_FLAG),
				new IrOp(OcslWire.OP_FOR, 0, 4, OcslWire.OPERAND_CONST_FLAG),
				new IrOp(OcslWire.OP_ADD, 0, 0, OcslWire.OPERAND_CONST_FLAG),
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, 0));
		assertEquals(13L, nested.structuralCount());
	}

	@Test
	public void structuralCountNeverFailsOpenOnAnUncountableProgram() throws Exception {
		// THE POINT: the only consumer of this number is `count > cap`. An earlier draft returned
		// -1 here, and -1 > 256 is false -- so a 6-op blob denoting 4.29e9 instructions would have
		// been ACCEPTED by the very check that exists to bound it. Saturating rejects it instead.
		IrProgram huge = program(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, 1,
				new IrOp(OcslWire.OP_FOR, 0, 4096, OcslWire.OPERAND_CONST_FLAG),
				new IrOp(OcslWire.OP_FOR, 0, 4096, OcslWire.OPERAND_CONST_FLAG),
				new IrOp(OcslWire.OP_ADD, 0, 0, OcslWire.OPERAND_CONST_FLAG),
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, 0));
		assertTrue("an uncountable program must fail a cap check, not pass it",
				huge.structuralCount() > 256L);

		// Malformed structure must fail the same comparison, for the same reason.
		IrProgram unbalanced = program(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, 1,
				new IrOp(OcslWire.OP_ENDFOR, -1));
		assertTrue(unbalanced.structuralCount() > 256L);
	}

	@Test
	public void refusesLoopsThatDescribeMoreWorkThanTheBlobCouldJustify() throws Exception {
		// The structural bound is not the validator's unroll-product cap; it stops a well-formed
		// blob DESCRIBING astronomically more work than its own size.
		expectRejectOnEncodeOrDecode(program(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, 1,
				new IrOp(OcslWire.OP_FOR, 0, 65535, OcslWire.OPERAND_CONST_FLAG),
				new IrOp(OcslWire.OP_ENDFOR, -1)), "over the structural bound");

		List<IrOp> deep = new ArrayList<IrOp>();
		for (int i = 0; i <= OcslWire.MAX_LOOP_DEPTH; i++) {
			deep.add(new IrOp(OcslWire.OP_FOR, 0, 2, OcslWire.OPERAND_CONST_FLAG));
		}
		for (int i = 0; i <= OcslWire.MAX_LOOP_DEPTH; i++) {
			deep.add(new IrOp(OcslWire.OP_ENDFOR, -1));
		}
		expectRejectOnEncodeOrDecode(new IrProgram(OcslWire.STAGE_PIXEL_MATERIAL,
				new float[] { 1.0f }, deep, new ArrayList<String>(), 1), "exceeds depth");
	}

	@Test
	public void structuralCountSurvivesAZeroTripLoopWithoutDividingByZero() {
		// A zero-trip loop is the validator's to reject, but the COUNT must not blow up on it --
		// the multiplier is restored by recomputation, not by dividing back out.
		IrProgram p = program(OcslWire.STAGE_PIXEL_MATERIAL, new float[] { 1.0f }, 1,
				new IrOp(OcslWire.OP_FOR, 0, 0, OcslWire.OPERAND_CONST_FLAG),
				new IrOp(OcslWire.OP_ADD, 0, 0, OcslWire.OPERAND_CONST_FLAG),
				new IrOp(OcslWire.OP_ENDFOR, -1),
				new IrOp(OcslWire.OP_OUT, -1, OcslWire.PROP_COLOR, 0));
		assertEquals("the OUT still charges 1", 1L, p.structuralCount());
	}
}
