package opengpu.v2.mc.server;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import opengpu.v2.protocol.V2Wire;

/**
 * All six cells of the ref-type redirect matrix, driven directly — the static is pure and the
 * class LOADS in a JVM test (only instantiation throws), so player-facing strings are checked
 * as strings, not by eye. The binary predecessor answered every mismatch with its complement:
 * the moment RES_MESH joined the id space, createSprite(meshId) would have claimed the mesh
 * "is a canvas" — two false claims in one message. Every cell here is a TRUE claim naming
 * what the resource is and the verb that takes it.
 */
public class RefTypeMatrixTest {

	@Test
	public void everyCellNamesTheActualTypeAndItsVerb() {
		assertEquals("resource 7 is a canvas, not a texture; use createCanvasNode",
				TileEntityGpu2.refTypeMismatch(7, V2Wire.RES_CANVAS, V2Wire.RES_TEXTURE));
		assertEquals("resource 7 is a mesh, not a texture; use createMeshNode",
				TileEntityGpu2.refTypeMismatch(7, V2Wire.RES_MESH, V2Wire.RES_TEXTURE));
		assertEquals("resource 7 is a texture, not a canvas; use createSprite",
				TileEntityGpu2.refTypeMismatch(7, V2Wire.RES_TEXTURE, V2Wire.RES_CANVAS));
		assertEquals("resource 7 is a mesh, not a canvas; use createMeshNode",
				TileEntityGpu2.refTypeMismatch(7, V2Wire.RES_MESH, V2Wire.RES_CANVAS));
		assertEquals("resource 7 is a texture, not a mesh; use createSprite",
				TileEntityGpu2.refTypeMismatch(7, V2Wire.RES_TEXTURE, V2Wire.RES_MESH));
		assertEquals("resource 7 is a canvas, not a mesh; use createCanvasNode",
				TileEntityGpu2.refTypeMismatch(7, V2Wire.RES_CANVAS, V2Wire.RES_MESH));
	}
}
