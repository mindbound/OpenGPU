package opengpu.v2.scene;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import opengpu.v2.protocol.V2Wire;

/**
 * C1.3.1's selection rule and projection read: which camera the 3D layer uses, and when it
 * refuses to render at all.
 *
 * These exist because BOTH rules have a plausible wrong implementation that no other channel
 * would catch. Selection has {@code Canvas2dRenderer.isDrawn} — one identifier away, already in
 * scope, and reading as obviously correct. The projection read has "supply a sensible default",
 * which produces a believable picture instead of a visible refusal. Each test below states the
 * wrong answer as an assertion so that adopting it fails here rather than in the field.
 */
public class ActiveCameraTest {

	private static ServerScene scene() {
		return new ServerScene("cam");
	}

	private static SceneNode node(ServerScene s, int id) {
		return s.state().nodes.get(Integer.valueOf(id));
	}

	// ---------------------------------------------------------------- selection

	@Test
	public void theLowestIdVisibleCameraWins() {
		ServerScene s = scene();
		int first = s.createNode(V2Wire.NODE_CAMERA, 0);
		int second = s.createNode(V2Wire.NODE_CAMERA, 0);
		assertTrue("ids must ascend for this test to be about ORDER", second > first);
		assertSame("lowest id, not last created", node(s, first), s.state().activeCamera());
	}

	@Test
	public void hidingTheActiveCameraSilentlyPromotesTheNextLowest() {
		ServerScene s = scene();
		int first = s.createNode(V2Wire.NODE_CAMERA, 0);
		int second = s.createNode(V2Wire.NODE_CAMERA, 0);
		s.setVisible(first, false);
		assertSame("promotion must fall out of re-scanning, with no extra code",
				node(s, second), s.state().activeCamera());
		s.setVisible(first, true);
		assertSame("and un-hiding must demote it again — proving nothing was cached",
				node(s, first), s.state().activeCamera());
	}

	@Test
	public void freeingTheActiveCameraPromotesTheNextLowest() {
		ServerScene s = scene();
		int first = s.createNode(V2Wire.NODE_CAMERA, 0);
		int second = s.createNode(V2Wire.NODE_CAMERA, 0);
		s.freeNode(first);
		assertSame(node(s, second), s.state().activeCamera());
	}

	@Test
	public void hidingEveryCameraSwitchesTheThreeDLayerOff() {
		ServerScene s = scene();
		int only = s.createNode(V2Wire.NODE_CAMERA, 0);
		s.setVisible(only, false);
		assertNull("no visible camera means the 3D layer is skipped, not that a hidden one"
				+ " is used anyway", s.state().activeCamera());
	}

	@Test
	public void aSceneWithNoCameraAtAllHasNoActiveCamera() {
		ServerScene s = scene();
		s.createNode(V2Wire.NODE_GROUP, 0);
		assertNull(s.state().activeCamera());
	}

	@Test
	public void nonCameraNodesAreNeverSelectedHoweverLowTheirId() {
		ServerScene s = scene();
		int group = s.createNode(V2Wire.NODE_GROUP, 0);
		int cam = s.createNode(V2Wire.NODE_CAMERA, 0);
		assertTrue("the decoy must sort FIRST, or this tests nothing", group < cam);
		assertSame(node(s, cam), s.state().activeCamera());
	}

	/**
	 * THE falsifier for the {@code isDrawn} substitution — the one case where the two
	 * predicates disagree, and the whole reason the rule says "its OWN visible flag".
	 *
	 * A camera parented to a HIDDEN group stays eligible: selection is a flat scan with no tree
	 * walk, because camera rigs are the point (PLAN-STAGE-C.md, camera decision 2). Swapping in
	 * effective visibility passes every other test in this file and fails only this one.
	 */
	@Test
	public void aCameraParentedToAHiddenGroupIsStillSelected() {
		ServerScene s = scene();
		int rig = s.createNode(V2Wire.NODE_GROUP, 0);
		int cam = s.createNode(V2Wire.NODE_CAMERA, 0, rig);
		s.setVisible(rig, false);

		assertTrue("the camera's OWN flag must still be true, or this test proves nothing about"
				+ " which flag is consulted", node(s, cam).visible);
		assertTrue("and the parent must really be hidden", !node(s, rig).visible);
		assertSame("a camera in a hidden rig group stays eligible — effective visibility is NOT"
				+ " the rule, and adopting it breaks exactly this case",
				node(s, cam), s.state().activeCamera());
	}

	@Test
	public void aCameraHiddenInItsOwnRightIsSkippedEvenWithAVisibleParent() {
		ServerScene s = scene();
		int rig = s.createNode(V2Wire.NODE_GROUP, 0);
		int cam = s.createNode(V2Wire.NODE_CAMERA, 0, rig);
		s.setVisible(cam, false);
		assertTrue("the parent stays visible, isolating the camera's own flag as the cause",
				node(s, rig).visible);
		assertNull("the OWN flag is consulted in BOTH directions — this is the mirror of the"
				+ " hidden-rig case, and a scan that ignored visibility entirely would pass"
				+ " that one while failing this", s.state().activeCamera());
	}

	// ---------------------------------------------------------------- projection

	@Test
	public void aNeverConfiguredCameraHasNoProjectionAndGetsNoDefault() {
		ServerScene s = scene();
		int cam = s.createNode(V2Wire.NODE_CAMERA, 0);
		SceneNode camera = node(s, cam);
		assertSame("it IS the active camera — visible defaults true, so this is the state every"
				+ " camera passes through", camera, s.state().activeCamera());
		assertTrue("precondition: nothing has written __proj",
				camera.uniforms.get(ServerScene.PROJECTION_UNIFORM) == null);
		assertNull("no renderer projection defaults exist (camera decision 4): an unconfigured"
				+ " camera must SKIP the 3D layer, never render at an invented fov",
				s.state().cameraProjection(camera));
	}

	@Test
	public void aConfiguredPerspectiveCameraReturnsItsOwnValues() {
		ServerScene s = scene();
		int cam = s.createNode(V2Wire.NODE_CAMERA, 0);
		s.setProjection(cam, false, 60.0, 0.1, 100.0);
		double[] p = s.state().cameraProjection(node(s, cam));
		assertNotNull(p);
		assertEquals(ServerScene.PROJECTION_PERSPECTIVE, p[0], 0.0);
		assertEquals(60.0, p[1], 0.0);
		assertEquals(0.1, p[2], 0.0);
		assertEquals(100.0, p[3], 0.0);
	}

	@Test
	public void aConfiguredOrthoCameraReturnsItsOwnValues() {
		ServerScene s = scene();
		int cam = s.createNode(V2Wire.NODE_CAMERA, 0);
		s.setProjection(cam, true, 12.0, 0.5, 50.0);
		double[] p = s.state().cameraProjection(node(s, cam));
		assertNotNull(p);
		assertEquals(ServerScene.PROJECTION_ORTHO, p[0], 0.0);
		assertEquals(12.0, p[1], 0.0);
	}

	/**
	 * The band is re-checked HERE, not trusted from the server gate.
	 *
	 * {@link ServerScene#setProjection} refuses these, so they cannot be written through the
	 * verb — which is exactly why they are planted directly into the node's table. A mirror
	 * payload reaches the client through DeltaApplier/SnapshotCodec, which validate name
	 * legality and value COUNT but never the projection band. These are the values that would
	 * arrive if anything ever wrote __proj by another route.
	 */
	@Test
	public void anOutOfBandProjectionIsRefusedClientSideRatherThanRenderedDegenerate() {
		ServerScene s = scene();
		int cam = s.createNode(V2Wire.NODE_CAMERA, 0);
		SceneNode camera = node(s, cam);
		final double persp = ServerScene.PROJECTION_PERSPECTIVE;

		double[][] bad = {
			{ persp, 60.0, 0.1, 0.05 },                     // far < near
			{ persp, 60.0, 5.0, 5.0 },                      // far == near, frustum collapses
			{ persp, 60.0, 0.0, 100.0 },                    // near == 0
			{ persp, 60.0, -1.0, 100.0 },                   // near < 0
			{ persp, 180.0, 0.1, 100.0 },                   // fov at the exclusive bound
			{ persp, 0.0, 0.1, 100.0 },                     // fov zero
			{ ServerScene.PROJECTION_ORTHO, 0.0, 0.1, 100.0 },  // ortho half-height zero
			{ 7.0, 60.0, 0.1, 100.0 },                      // unknown mode
		};
		for (int i = 0; i < bad.length; i++) {
			camera.uniforms.put(ServerScene.PROJECTION_UNIFORM, bad[i].clone());
			assertNull("row " + i + " is outside the band the server would have enforced and"
					+ " must be refused here too, since no client-side path checks it",
					s.state().cameraProjection(camera));
		}

		// The positive control: the SAME planting route with a legal payload must succeed, or
		// every row above would pass for the trivial reason that planting does not work.
		camera.uniforms.put(ServerScene.PROJECTION_UNIFORM,
				new double[] { persp, 60.0, 0.1, 100.0 });
		assertNotNull("the planting route itself must be capable of producing an ACCEPTED"
				+ " projection, or the refusals above are vacuous",
				s.state().cameraProjection(camera));
	}

	@Test
	public void aMalformedProjectionEntryIsRefusedRatherThanIndexedOutOfBounds() {
		ServerScene s = scene();
		int cam = s.createNode(V2Wire.NODE_CAMERA, 0);
		SceneNode camera = node(s, cam);
		camera.uniforms.put(ServerScene.PROJECTION_UNIFORM, new double[] { 1.0, 60.0 });
		assertNull("a short entry must return null, not throw", s.state().cameraProjection(camera));
	}

	@Test
	public void aNullCameraHasNoProjection() {
		assertNull("the no-camera and no-projection paths converge on one skip, so the caller"
				+ " needs no null check of its own", scene().state().cameraProjection(null));
	}
}
