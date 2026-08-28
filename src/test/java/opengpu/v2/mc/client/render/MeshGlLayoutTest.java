package opengpu.v2.mc.client.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import opengpu.v2.protocol.V2Wire;

/**
 * The vertex record's layout: that {@link MeshGl}'s attribute offsets describe the FROZEN wire
 * format, and keep describing it.
 *
 * <b>Why this exists at all: nothing checked the offsets before.</b> {@code COLOR_OFFSET} has
 * carried a javadoc claiming it is derived since C1.3.1 — and recording that an earlier version
 * of it was the literal {@code 8 * 4} beneath that same claim. The derivation was fixed; the
 * absence of any test was not, so the file's own cautionary tale was one edit from repeating with
 * nothing to catch it. Group B added a second derived offset, which made the gap worth closing.
 *
 * <b>The two halves do different jobs, and both are needed.</b>
 * <ul>
 * <li>{@link MeshGl} DERIVES its offsets from component widths, so the code follows the format
 *     automatically — position forwards from zero, colour backwards from the stride.</li>
 * <li>This test PINS THE LITERALS the format froze: "pos f32 x3 @0, normal f32 x3 @12, uv f32 x2
 *     @24, color u8 x4 @32", V2Wire's own words. A derivation alone cannot catch a change to the
 *     FORMAT, because the derived values move with it silently and every assertion phrased in
 *     terms of them stays true. Pinning the literals here is what makes a format change fail
 *     rather than propagate.</li>
 * </ul>
 * That pairing is the file's whole point: derive in main so the code cannot drift from the
 * constants, pin literals in test so the constants cannot drift from the format.
 *
 * <b>WHAT THIS FILE CANNOT CATCH, measured rather than assumed.</b> Replacing a derivation in
 * {@link MeshGl} with the literal it currently equals — putting {@code COLOR_OFFSET = 8 * 4}
 * back, the exact regression that constant's javadoc memorialises — leaves every test here GREEN.
 * That was injected on 2026-08-28 and confirmed to survive; the two mutants that break the
 * derivation's ARITHMETIC (normal offset computed with the wrong width; a component width that
 * no longer sums to the stride) were killed, at two assertions each.
 *
 * The survivor is not a hole to patch, because no assertion can close it. A literal and a
 * derivation that agree are indistinguishable by any expression over their values; they diverge
 * only when {@link V2Wire#MESH_VERTEX_STRIDE} moves, and the stride is a compile-time constant
 * this test cannot vary. So the guarantee is narrower than "the offsets are derived": it is
 * <b>"if the stride ever moves, something here fails loudly"</b> — which is the property that
 * actually matters, since the literal is harmless right up until that moment and catastrophic
 * after it. Stated because a test whose limits are unwritten gets trusted past them.
 */
public class MeshGlLayoutTest {

	/**
	 * The frozen format, transcribed from V2Wire's statement rather than read back from the
	 * constants under test. Reading them back would make every assertion below a tautology —
	 * the defect this file exists to prevent, one level up.
	 */
	private static final int FROZEN_STRIDE = 36;
	private static final int FROZEN_POSITION_OFFSET = 0;
	private static final int FROZEN_NORMAL_OFFSET = 12;
	private static final int FROZEN_UV_OFFSET = 24;
	private static final int FROZEN_COLOR_OFFSET = 32;

	@Test
	public void theStrideIsTheOneTheFormatFroze() {
		assertEquals("V2Wire.MESH_VERTEX_STRIDE is FORMAT — a change here breaks every mesh in"
				+ " every existing save, and needs a protocol decision, not an edit",
				FROZEN_STRIDE, V2Wire.MESH_VERTEX_STRIDE);
	}

	@Test
	public void everyAttributeSitsWhereTheFormatSaysItDoes() {
		assertEquals("position @0", FROZEN_POSITION_OFFSET, MeshGl.POSITION_OFFSET);
		assertEquals("normal @12", FROZEN_NORMAL_OFFSET, MeshGl.NORMAL_OFFSET);
		assertEquals("colour @32", FROZEN_COLOR_OFFSET, MeshGl.COLOR_OFFSET);
	}

	@Test
	public void theAttributeWidthsAccountForTheWHOLEStride() {
		int accounted = MeshGl.POSITION_COMPONENTS * MeshGl.FLOAT_BYTES
				+ MeshGl.NORMAL_COMPONENTS * MeshGl.FLOAT_BYTES
				+ MeshGl.UV_COMPONENTS * MeshGl.FLOAT_BYTES
				+ MeshGl.COLOR_BYTES;
		// THE RECONCILIATION. Without it the component constants are decoration: a stride raised
		// to make room for a fifth attribute would leave every derived offset still "correct" by
		// its own arithmetic, COLOR_OFFSET would silently slide to the new tail, and the only
		// symptom would be a wrong picture. This is the assertion that makes the derivations mean
		// something, and it is why UV_COMPONENTS exists despite no drawing code reading it.
		assertEquals("the four attribute widths must account for the entire vertex record, with"
				+ " no unexplained padding — if you added an attribute, this is where it declares"
				+ " itself", V2Wire.MESH_VERTEX_STRIDE, accounted);
	}

	@Test
	public void theAttributesAreOrderedAndDoNotOverlap() {
		// Derived from the widths rather than pinned, because this asserts INTERNAL consistency
		// (each attribute begins where the previous one ends), which is a different claim from
		// the literal pins above and fails on a different mistake — a reordering that keeps the
		// stride intact.
		assertEquals("normal begins exactly where position ends",
				MeshGl.POSITION_OFFSET + MeshGl.POSITION_COMPONENTS * MeshGl.FLOAT_BYTES,
				MeshGl.NORMAL_OFFSET);
		int uvOffset = MeshGl.NORMAL_OFFSET + MeshGl.NORMAL_COMPONENTS * MeshGl.FLOAT_BYTES;
		assertEquals("and uv where normal ends", FROZEN_UV_OFFSET, uvOffset);
		assertEquals("and colour where uv ends",
				uvOffset + MeshGl.UV_COMPONENTS * MeshGl.FLOAT_BYTES, MeshGl.COLOR_OFFSET);
		assertTrue("colour must end exactly at the stride, leaving no trailing padding",
				MeshGl.COLOR_OFFSET + MeshGl.COLOR_BYTES == V2Wire.MESH_VERTEX_STRIDE);
	}

	@Test
	public void aVertexRecordHoldsAWholeNumberOfAttributes() {
		// The wrong answer written in: a stride that is not a multiple of the float width would
		// misalign every attribute after the first on a strict-alignment driver, and the offsets
		// above would all still reconcile.
		assertEquals("the stride must be a whole number of floats",
				0, V2Wire.MESH_VERTEX_STRIDE % MeshGl.FLOAT_BYTES);
	}
}
