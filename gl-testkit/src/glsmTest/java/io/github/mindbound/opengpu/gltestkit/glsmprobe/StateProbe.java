package io.github.mindbound.opengpu.gltestkit.glsmprobe;

import org.lwjgl.opengl.GL11;

/**
 * Sets viewport, clear colour, scissor test and scissor box to values of its own and leaves them set, for the check
 * that GLSM's cache follows the redirected calls (see ProbeTarget). The clear colour is Rgba8ClearProbe's full-clear
 * colour.
 */
public final class StateProbe implements Runnable {

    public static final int VX = 1;
    public static final int VY = 2;
    public static final int VW = 4;
    public static final int VH = 3;

    @Override
    public void run() {
        GL11.glViewport(VX, VY, VW, VH);
        GL11.glClearColor(
            ProbeTarget.CLEAR_R / 255f,
            ProbeTarget.CLEAR_G / 255f,
            ProbeTarget.CLEAR_B / 255f,
            ProbeTarget.CLEAR_A / 255f);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(ProbeTarget.SX, ProbeTarget.SY, ProbeTarget.SW, ProbeTarget.SH);
    }
}
