package io.github.mindbound.opengpu.gltestkit.glsmprobe;

import java.util.concurrent.Callable;

import org.lwjgl.opengl.GL11;

/**
 * GlSmokeTest's RGBA8 case as a probe (see ProbeTarget): a full clear in CLEAR_R..CLEAR_A, then a red one in the
 * scissor box, read back both ways. Every call it makes is one GLSM maps.
 */
public final class Rgba8ClearProbe implements Callable<byte[][]> {

    @Override
    public byte[][] call() {
        ProbeTarget target = ProbeTarget.open(GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 4);
        try {
            GL11.glClearColor(
                ProbeTarget.CLEAR_R / 255f,
                ProbeTarget.CLEAR_G / 255f,
                ProbeTarget.CLEAR_B / 255f,
                ProbeTarget.CLEAR_A / 255f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            ProbeTarget.scissorOn();
            try {
                GL11.glClearColor(1f, 0f, 0f, 1f);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            } finally {
                ProbeTarget.scissorOff();
            }
            return target.read();
        } finally {
            target.close();
        }
    }
}
