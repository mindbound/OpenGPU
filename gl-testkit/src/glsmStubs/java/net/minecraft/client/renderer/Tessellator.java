package net.minecraft.client.renderer;

import java.nio.ByteBuffer;

/**
 * OpenGPU's stand-in for Minecraft's Tessellator on the GLSM legs' runtime classpath only (the GLSM spike, roadmap M0;
 * docs/research/23-gl-testing-strategy.md §2.3): GTNHLib's DirectTessellator extends it, and GLSM reaches that class
 * while it starts, through markSplashComplete at 2.2.8 and through GLSMInitConfig.builder() from 2.2.21. It has the
 * members GTNHLib 0.11.48 and GLSM name on Tessellator, the fields public as GTNHLib's access transformer makes them in
 * game, static where Minecraft's are; it draws nothing, so nothing that draws through it works (no GLSM-leg test does).
 */
public class Tessellator {

    public static final Tessellator instance = new Tessellator();
    public static ByteBuffer byteBuffer;

    public int[] rawBuffer;
    public int rawBufferIndex;
    public int rawBufferSize;
    public int vertexCount;
    public int drawMode;
    public boolean isDrawing;
    public boolean isColorDisabled;
    public boolean hasColor;
    public boolean hasTexture;
    public boolean hasBrightness;
    public boolean hasNormals;
    public double textureU;
    public double textureV;
    public int brightness;
    public int color;
    public int normal;
    public double xOffset;
    public double yOffset;
    public double zOffset;

    public Tessellator() {}
}
