package opengpu.v2.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import opengpu.v2.scene.SceneNode;
import opengpu.v2.scene.SceneSnapshot;
import opengpu.v2.scene.ServerScene;

/**
 * The uniform table's producer-max &lt;= decoder-bound relationship, pinned because the FLAT
 * alternative was refuted by exactly this arithmetic at the C1.1 design panel: 4096 nodes x 64
 * entries = 262,144 legal standing entries = 4x {@code SnapshotCodec.MAX_ENTRIES}, so a flat
 * count bound would let a legally-full server encode a snapshot every decoder refuses — network:
 * permanently unresyncable; persisted: restoreOrFresh answers the CodecException by DELETING the
 * scene. The section is therefore NESTED, and each of its two bounds must stay producibly
 * satisfiable. This test is what keeps that arithmetic from rotting when a cap moves.
 */
public class UniformTableBoundTest {

	@Test
	public void everyNestedBoundIsProduciblySatisfiable() {
		// Outer count: at most one group per node, and MAX_NODES is under MAX_ENTRIES.
		assertTrue("the outer group count (max " + ServerScene.MAX_NODES + ", one per node) must"
				+ " stay under the decoder's MAX_ENTRIES bound of " + SnapshotCodec.MAX_ENTRIES,
				ServerScene.MAX_NODES <= SnapshotCodec.MAX_ENTRIES);
		// The refuted flat product, kept as the reason the section is nested at all.
		long standing = (long) ServerScene.MAX_NODES * ServerScene.MAX_NODE_UNIFORMS;
		assertTrue("the legal standing total (" + standing + ") exceeds MAX_ENTRIES ("
				+ SnapshotCodec.MAX_ENTRIES + ") — if this ever stops being true, the nested"
				+ " section is defending against nothing and could be flattened",
				standing > SnapshotCodec.MAX_ENTRIES);
		// Worst-case decode allocation, stated: bounded by the two nested caps, and small.
		// Per entry at most: 34 B name (32 chars, charset makes chars == bytes) + 1 + 32.
		long worstBytes = standing * (34 + 1 + 32);
		assertTrue("the worst-case section (" + worstBytes + " B) must stay far under the"
				+ " transfer ceiling", worstBytes * 4 < FrameChunker.MAX_TRANSFER_BYTES);
	}

	@Test
	public void aFullTableSurvivesTheSnapshotRoundTrip() throws Exception {
		ServerScene scene = new ServerScene("uniform-full");
		int node = scene.createNode(V2Wire.NODE_GROUP, 0);
		for (int i = 0; i < ServerScene.MAX_NODE_UNIFORMS; i++) {
			scene.setUniform(node, "u" + i, new double[] { i, i + 0.5 }, false);
		}
		scene.sealBatch();
		byte[] encoded = SnapshotCodec.encode(scene.snapshot());
		SceneSnapshot decoded = SnapshotCodec.decode(encoded);
		SceneNode restored = decoded.state.nodes.get(Integer.valueOf(node));
		assertEquals("a maximally-full table round-trips whole",
				ServerScene.MAX_NODE_UNIFORMS, restored.uniforms.size());
		org.junit.Assert.assertArrayEquals(new double[] { 7, 7.5 },
				restored.uniforms.get("u7"), 0.0);
	}

	// ---- Producer behaviour on the shared apply path ----

	@Test
	public void theSixtyFifthNameIsRefusedButReplacingIsNot() {
		ServerScene scene = new ServerScene("uniform-cap");
		int node = scene.createNode(V2Wire.NODE_GROUP, 0);
		for (int i = 0; i < ServerScene.MAX_NODE_UNIFORMS; i++) {
			scene.setUniform(node, "u" + i, new double[] { i }, false);
		}
		try {
			scene.setUniform(node, "one_too_many", new double[] { 1 }, false);
			fail("the per-node cap should refuse a new NAME");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("uniforms"));
		}
		// Replacing an existing name never grows the table, so the cap must not bind.
		scene.setUniform(node, "u3", new double[] { 9, 9, 9, 9 }, true);
	}

	@Test
	public void clearRemovesAndClearingTheMissingThrows() {
		ServerScene scene = new ServerScene("uniform-clear");
		int node = scene.createNode(V2Wire.NODE_GROUP, 0);
		scene.setUniform(node, "speed", new double[] { 2.5 }, false);
		// CLEAR is values-absent (the count IS the type; 0 values = type 0 = CLEAR).
		scene.setUniform(node, "speed", null, false);
		try {
			scene.setUniform(node, "speed", new double[0], false);
			fail("clearing a missing entry must throw — the frees' precedent");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("unknown uniform"));
		}
	}

	@Test
	public void anIllegalNameIsRefusedAtTheDeltaNotDiscoveredDownstream() {
		ServerScene scene = new ServerScene("uniform-name");
		int node = scene.createNode(V2Wire.NODE_GROUP, 0);
		try {
			scene.setUniform(node, "9starts_with_digit", new double[] { 1 }, false);
			fail("a name IrStructure.checkName refuses must not become a delta");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("digit"));
		}
		try {
			scene.setUniform(node, "has space", new double[] { 1 }, false);
			fail("charset violation");
		} catch (IllegalArgumentException expected) {
			// chars == bytes rests on this charset; BatchSizeBoundTest leans on it.
		}
	}
}
