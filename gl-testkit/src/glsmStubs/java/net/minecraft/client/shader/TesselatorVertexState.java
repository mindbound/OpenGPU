package net.minecraft.client.shader;

/**
 * OpenGPU's stand-in for Minecraft's TesselatorVertexState on the GLSM legs' runtime classpath only (the GLSM spike,
 * roadmap M0): GTNHLib's DirectTessellator names it in getVertexState and setVertexState, so loading that class may
 * need it. Only Minecraft's constructor is here; nothing on the GLSM legs uses vertex states.
 */
public class TesselatorVertexState {

    public TesselatorVertexState(int[] rawBuffer, int rawBufferIndex, int vertexCount, boolean hasTexture,
        boolean hasBrightness, boolean hasNormals, boolean hasColor) {}
}
