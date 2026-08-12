package opengpu.v2.scene;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import opengpu.v2.protocol.V2Wire;

/**
 * The display-node validity predicate — the defect found while auditing ANIM-15(a).
 *
 * Each vector names the wrong answer it excludes, which here is always the SAME wrong answer: the
 * superseded check was {@code nodes.containsKey(id)}, so every vector below is a state where a node
 * with that id exists and is not the display node. A test that only fed it the good state would pass
 * against the bug.
 */
public strictfp class DisplayNodeTest {

	private static final int RES = 7;
	private static final int NODE = 3;

	private static SceneState sceneWith(byte nodeType, int nodeRef, byte resType) {
		SceneState state = new SceneState();
		state.resources.put(Integer.valueOf(RES), new ResourceInfo(RES, resType, 8, 8, 0));

		SceneNode node = new SceneNode(NODE, nodeType, nodeRef, 0);
		state.nodes.put(Integer.valueOf(NODE), node);
		return state;
	}

	@Test
	public void theGoodStateIsValid() throws Exception {
		SceneState state = sceneWith(V2Wire.NODE_CANVAS, RES, V2Wire.RES_CANVAS);
		assertTrue("a canvas node pointing at the canvas resource is the display node",
				DisplayNode.stillValid(state, RES, NODE));
	}

	@Test
	public void aNodeOfTheWrongKindHoldingTheIdIsRefused() throws Exception {
		// THE DEFECT. A restore hands back a node id that exists and is a SPRITE. containsKey said
		// yes, the guards then protected that sprite, and the real display node became freely
		// transformable and freeable -- with server and every mirror agreeing on it, so no
		// divergence check could fire.
		SceneState sprite = sceneWith(V2Wire.NODE_SPRITE, RES, V2Wire.RES_CANVAS);
		assertFalse("a sprite is not the display node", DisplayNode.stillValid(sprite, RES, NODE));
		assertTrue("but it DOES exist, which is all the superseded check asked",
				sprite.nodes.containsKey(Integer.valueOf(NODE)));

		SceneState group = sceneWith(V2Wire.NODE_GROUP, RES, V2Wire.RES_CANVAS);
		assertFalse("nor is a group", DisplayNode.stillValid(group, RES, NODE));
		assertTrue(group.nodes.containsKey(Integer.valueOf(NODE)));
	}

	@Test
	public void aCanvasNodePointingAtADifferentCanvasIsRefused() throws Exception {
		// The subtler half, and the one a type-only fix would still miss: right kind, wrong canvas.
		// The node is a perfectly good canvas node -- for somebody else's canvas.
		SceneState state = sceneWith(V2Wire.NODE_CANVAS, RES + 1, V2Wire.RES_CANVAS);
		state.resources.put(Integer.valueOf(RES + 1),
				new ResourceInfo(RES + 1, V2Wire.RES_CANVAS, 8, 8, 0));

		assertFalse("the ref must name the remembered resource",
				DisplayNode.stillValid(state, RES, NODE));
		assertTrue("and a type-only check would have accepted it",
				state.nodes.get(Integer.valueOf(NODE)).type == V2Wire.NODE_CANVAS);
	}

	@Test
	public void aMissingOrWrongTypedResourceIsRefused() throws Exception {
		SceneState wrongRes = sceneWith(V2Wire.NODE_CANVAS, RES, V2Wire.RES_TEXTURE);
		assertFalse("the resource must still be a canvas", DisplayNode.stillValid(wrongRes, RES, NODE));

		SceneState good = sceneWith(V2Wire.NODE_CANVAS, RES, V2Wire.RES_CANVAS);
		assertFalse("an unknown resource id is refused", DisplayNode.stillValid(good, 999, NODE));
		assertFalse("and a zero id means nothing is remembered",
				DisplayNode.stillValid(good, 0, NODE));
	}

	@Test
	public void aMissingNodeIsRefused() throws Exception {
		SceneState state = sceneWith(V2Wire.NODE_CANVAS, RES, V2Wire.RES_CANVAS);
		assertFalse("a node id that no longer exists is refused",
				DisplayNode.stillValid(state, RES, 4242));
		assertFalse("and a null state is not a crash", DisplayNode.stillValid(null, RES, NODE));
	}

	// ------------------------------------------------------------------ the client's mechanism

	@Test
	public void theClientPicksTheLowestIdCanvasNodeAndTheOrderIsTotal() throws Exception {
		// The server remembers an id; the client scans. They agree by construction and nothing
		// enforces it, which is why the divergence is worth naming. The scan is over a TreeMap, so
		// it is ascending by id -- a total order, identical on every client, not a hash order.
		SceneState state = new SceneState();
		state.resources.put(Integer.valueOf(RES), new ResourceInfo(RES, V2Wire.RES_CANVAS, 8, 8, 0));

		// Inserted HIGH id first, so a passing result cannot be insertion order.
		state.nodes.put(Integer.valueOf(9), new SceneNode(9, V2Wire.NODE_CANVAS, RES, 0));
		state.nodes.put(Integer.valueOf(2), new SceneNode(2, V2Wire.NODE_CANVAS, RES, 0));
		state.nodes.put(Integer.valueOf(5), new SceneNode(5, V2Wire.NODE_CANVAS, RES, 0));

		assertEquals("the lowest id wins", 2, DisplayNode.displayNodeId(state));
		assertTrue("insertion order would have given 9", DisplayNode.displayNodeId(state) != 9);

		// A sprite at a lower id does not steal the slot -- only canvas nodes are candidates.
		state.nodes.put(Integer.valueOf(1), new SceneNode(1, V2Wire.NODE_SPRITE, RES, 0));
		assertEquals("a sprite at id 1 is not a candidate", 2, DisplayNode.displayNodeId(state));

		// And a canvas node whose ref does not resolve is skipped, not returned.
		state.nodes.put(Integer.valueOf(0), new SceneNode(0, V2Wire.NODE_CANVAS, 555, 0));
		assertEquals("an unresolvable ref is skipped", 2, DisplayNode.displayNodeId(state));
	}

	@Test
	public void theTwoMechanismsCanDisagreeAndThatIsTheStatedRisk() throws Exception {
		// Not a defect being fixed here -- a fact being pinned. The server's remembered node and the
		// client's scan are different mechanisms, so a state exists where stillValid says yes and
		// the client would draw a different node as the display.
		SceneState state = new SceneState();
		state.resources.put(Integer.valueOf(RES), new ResourceInfo(RES, V2Wire.RES_CANVAS, 8, 8, 0));
		state.nodes.put(Integer.valueOf(2), new SceneNode(2, V2Wire.NODE_CANVAS, RES, 0));
		state.nodes.put(Integer.valueOf(8), new SceneNode(8, V2Wire.NODE_CANVAS, RES, 0));

		assertTrue("the server's remembered node 8 passes every validity condition",
				DisplayNode.stillValid(state, RES, 8));
		assertEquals("while the client would call node 2 the display", 2,
				DisplayNode.displayNodeId(state));
		assertTrue("so validity does NOT imply the two agree -- that is the residual risk",
				DisplayNode.displayNodeId(state) != 8);
	}
}
