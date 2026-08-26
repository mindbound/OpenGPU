package opengpu.v2.scene;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import opengpu.v2.protocol.V2Wire;

/**
 * The C1.2 server-surface semantics: 3D staging value order, the projection entry, and the
 * reserved-name/finiteness gates on the shared uniform admission path.
 */
public class Surface3dTest {

	private static ServerScene sceneWithNode(int[] nodeOut) {
		ServerScene scene = new ServerScene("surface3d");
		nodeOut[0] = scene.createNode(V2Wire.NODE_GROUP, 0);
		return scene;
	}

	@Test
	public void setTransform3dLandsEveryFieldWithTeleport() {
		// ELEVEN mutually-distinct values through the FULL mask WITH teleport — the only
		// channel that can see a value-order misassignment, because PROP_TELEPORT (bit 8) sits
		// BELOW the 3D bits and a "teleport goes last" copy from the 2D sibling would produce
		// convergent garbage no detector sees.
		int[] n = new int[1];
		ServerScene scene = sceneWithNode(n);
		scene.setTransform3d(n[0], 1.5, 2.5, 3.5, 0.5, -0.5, 0.25, 0.75, 4.5, 5.5, 6.5, true);
		SceneNode node = scene.state().nodes.get(Integer.valueOf(n[0]));
		assertEquals(1.5, node.x, 0.0);
		assertEquals(2.5, node.y, 0.0);
		assertEquals(3.5, node.tz, 0.0);
		assertEquals(0.5, node.qx, 0.0);
		assertEquals(-0.5, node.qy, 0.0);
		assertEquals(0.25, node.qz, 0.0);
		assertEquals(0.75, node.qw, 0.0);
		assertEquals(4.5, node.sx, 0.0);
		assertEquals(5.5, node.sy, 0.0);
		assertEquals(6.5, node.sz, 0.0);
		assertEquals("2D rot is NOT part of the 3D mask", 0.0, node.rot, 0.0);
	}

	@Test
	public void theNoTeleportArmsAreOrderCheckedToo() {
		// Both staging methods have TWO value-array literals (with and without the teleport
		// slot); the with-arm tests alone would let a desync in the no-teleport literal ship.
		int[] n = new int[1];
		ServerScene scene = sceneWithNode(n);
		scene.setTransform3d(n[0], 1.25, 2.25, 3.25, 0.5, 0.5, -0.5, 0.5, 4.25, 5.25, 6.25, false);
		SceneNode node = scene.state().nodes.get(Integer.valueOf(n[0]));
		assertEquals(1.25, node.x, 0.0);
		assertEquals(3.25, node.tz, 0.0);
		assertEquals(-0.5, node.qz, 0.0);
		assertEquals(0.5, node.qw, 0.0);
		assertEquals(6.25, node.sz, 0.0);
		scene.setPose3d(n[0], 7.25, 8.25, 9.25, 0, 0, 1, 0, false);
		assertEquals(9.25, node.tz, 0.0);
		assertEquals(1.0, node.qz, 0.0);
		assertEquals(0.0, node.qw, 0.0);
		assertEquals("scale untouched", 6.25, node.sz, 0.0);
	}

	@Test
	public void setPose3dLandsPositionAndQuatOnly() {
		int[] n = new int[1];
		ServerScene scene = sceneWithNode(n);
		scene.setTransform3d(n[0], 0, 0, 0, 0, 0, 0, 1, 9, 9, 9, false);
		scene.setPose3d(n[0], 7.5, 8.5, 9.5, 0, 1, 0, 0, true);
		SceneNode node = scene.state().nodes.get(Integer.valueOf(n[0]));
		assertEquals(7.5, node.x, 0.0);
		assertEquals(8.5, node.y, 0.0);
		assertEquals(9.5, node.tz, 0.0);
		assertEquals(1.0, node.qy, 0.0);
		assertEquals("scale untouched by a pose write", 9.0, node.sx, 0.0);
		assertEquals(9.0, node.sz, 0.0);
	}

	@Test
	public void projectionWritesOneReservedVec4() {
		ServerScene scene = new ServerScene("proj");
		int cam = scene.createNode(V2Wire.NODE_CAMERA, 0);
		scene.setProjection(cam, false, 60.0, 0.1, 100.0);
		SceneNode node = scene.state().nodes.get(Integer.valueOf(cam));
		assertArrayEquals(new double[] { ServerScene.PROJECTION_PERSPECTIVE, 60.0, 0.1, 100.0 },
				node.uniforms.get(ServerScene.PROJECTION_UNIFORM), 0.0);
		assertEquals("exactly one entry — the vec4, never four scalars", 1, node.uniforms.size());
		// Ortho REPLACES in place: still one entry, mode flips.
		scene.setProjection(cam, true, 12.0, 0.5, 50.0);
		assertArrayEquals(new double[] { ServerScene.PROJECTION_ORTHO, 12.0, 0.5, 50.0 },
				node.uniforms.get(ServerScene.PROJECTION_UNIFORM), 0.0);
		assertEquals(1, node.uniforms.size());
	}

	@Test
	public void projectionValidationRefusesEachBadShapeByName() {
		ServerScene scene = new ServerScene("proj-bad");
		int cam = scene.createNode(V2Wire.NODE_CAMERA, 0);
		try {
			scene.setProjection(cam, false, 180.0, 0.1, 100.0);
			fail("fov 180 is exclusive");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("(0, 180)"));
		}
		try {
			scene.setProjection(cam, false, 60.0, 0.0, 100.0);
			fail("near 0");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("near"));
		}
		try {
			scene.setProjection(cam, false, 60.0, 5.0, 5.0);
			fail("far == near");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("greater than near"));
		}
		int group = scene.createNode(V2Wire.NODE_GROUP, 0);
		try {
			scene.setProjection(group, false, 60.0, 0.1, 100.0);
			fail("projection on a non-camera");
		} catch (IllegalStateException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("not a camera"));
		}
		assertTrue("no refusal may leave a partial write",
				scene.state().nodes.get(Integer.valueOf(cam)).uniforms.isEmpty());
	}

	@Test
	public void aFullTableRefusesProjectionWholeAndSixtyThreeAdmitsIt() {
		// The refuted four-name layout would have TORN here (two entries staged, two refused);
		// the vec4 form must refuse WHOLE at a full table and land whole at 63.
		ServerScene scene = new ServerScene("proj-full");
		int cam = scene.createNode(V2Wire.NODE_CAMERA, 0);
		for (int i = 0; i < ServerScene.MAX_NODE_UNIFORMS; i++) {
			scene.setUniform(cam, "u" + i, new double[] { i }, false);
		}
		try {
			scene.setProjection(cam, false, 60.0, 0.1, 100.0);
			fail("the 65th name must be refused");
		} catch (IllegalStateException expected) {
			assertFalse("nothing staged, nothing partial", scene.state().nodes
					.get(Integer.valueOf(cam)).uniforms.containsKey(ServerScene.PROJECTION_UNIFORM));
		}
		ServerScene scene2 = new ServerScene("proj-63");
		int cam2 = scene2.createNode(V2Wire.NODE_CAMERA, 0);
		for (int i = 0; i < ServerScene.MAX_NODE_UNIFORMS - 1; i++) {
			scene2.setUniform(cam2, "u" + i, new double[] { i }, false);
		}
		scene2.setProjection(cam2, false, 60.0, 0.1, 100.0);
		assertEquals(ServerScene.MAX_NODE_UNIFORMS,
				scene2.state().nodes.get(Integer.valueOf(cam2)).uniforms.size());
		// And RE-projection at the now-full table is a REPLACE, never a 65th-name refusal —
		// pinned with the reserved name itself so a future setProjection-side capacity
		// precheck cannot forget the already-present case.
		scene2.setProjection(cam2, true, 12.0, 0.5, 50.0);
		assertEquals(ServerScene.MAX_NODE_UNIFORMS,
				scene2.state().nodes.get(Integer.valueOf(cam2)).uniforms.size());
		assertEquals(ServerScene.PROJECTION_ORTHO, scene2.state().nodes
				.get(Integer.valueOf(cam2)).uniforms.get(ServerScene.PROJECTION_UNIFORM)[0], 0.0);
	}

	@Test
	public void reservedNamesAreRefusedOnTheSharedGateBeforeTheClearBranch() {
		// The __ check runs FIRST, so a bare no-values call cannot CLEAR host state through a
		// values-first implementation — driven through BOTH immediate arms (the shared gate).
		ServerScene scene = new ServerScene("reserved");
		int cam = scene.createNode(V2Wire.NODE_CAMERA, 0);
		scene.setProjection(cam, false, 60.0, 0.1, 100.0);
		for (boolean immediate : new boolean[] { false, true }) {
			try {
				scene.setUniform(cam, "__proj", null, immediate);
				fail("a bare __proj call (the CLEAR arity) must be refused, immediate=" + immediate);
			} catch (IllegalArgumentException expected) {
				assertTrue(expected.getMessage(), expected.getMessage().contains("reserved"));
			}
			try {
				scene.setUniform(cam, "__fov", new double[] { 1 }, immediate);
				fail("any __ name is reserved, immediate=" + immediate);
			} catch (IllegalArgumentException expected) {
				assertTrue(expected.getMessage(), expected.getMessage().contains("reserved"));
			}
		}
		assertArrayEquals("the projection survived every refusal untouched",
				new double[] { ServerScene.PROJECTION_PERSPECTIVE, 60.0, 0.1, 100.0 },
				scene.state().nodes.get(Integer.valueOf(cam))
						.uniforms.get(ServerScene.PROJECTION_UNIFORM), 0.0);
		// A single underscore is NOT reserved: the fence is exactly two.
		scene.setUniform(cam, "_mine", new double[] { 1 }, false);
	}

	@Test
	public void nonFiniteUniformValuesAreRefusedAndThePreviousValueRetained() {
		ServerScene scene = new ServerScene("finite");
		int node = scene.createNode(V2Wire.NODE_GROUP, 0);
		scene.setUniform(node, "speed", new double[] { 2.5 }, false);
		try {
			scene.setUniform(node, "speed", new double[] { Double.NaN }, false);
			fail("NaN must be refused at the set-call — DESIGN's pinned rule");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("finite"));
		}
		try {
			scene.setUniform(node, "speed",
					new double[] { 1.0, Double.POSITIVE_INFINITY }, false);
			fail("infinity in any component");
		} catch (IllegalArgumentException expected) {
		}
		assertArrayEquals("the previous value is retained by construction",
				new double[] { 2.5 },
				scene.state().nodes.get(Integer.valueOf(node)).uniforms.get("speed"), 0.0);
	}
}
