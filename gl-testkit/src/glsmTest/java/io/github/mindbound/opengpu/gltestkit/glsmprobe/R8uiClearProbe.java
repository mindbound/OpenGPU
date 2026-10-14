package io.github.mindbound.opengpu.gltestkit.glsmprobe;

import java.nio.IntBuffer;
import java.util.concurrent.Callable;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * GlSmokeTest's R8UI case as a probe (see ProbeTarget). glClear is undefined on integer buffers, so it clears with
 * glClearBufferu, which GLSM leaves unmapped (docs/research/24-architecture-gl-first.md §3.7): under GLSM that one call
 * stays a direct driver call, and it sees the scissor state GLSM set, since GLSM passes state changes on at once. The
 * render core clears integer surfaces with a quad instead (25 §4.2, A09), so this probe exists only here.
 */
public final class R8uiClearProbe implements Callable<byte[][]> {

    public static final int OUTSIDE = 0xA5;
    public static final int INSIDE = 0x5A;

    @Override
    public byte[][] call() {
        ProbeTarget target = ProbeTarget.open(GL30.GL_R8UI, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, 1);
        try {
            GL30.glClearBufferu(GL11.GL_COLOR, 0, red(OUTSIDE));
            ProbeTarget.scissorOn();
            try {
                GL30.glClearBufferu(GL11.GL_COLOR, 0, red(INSIDE));
            } finally {
                ProbeTarget.scissorOff();
            }
            return target.read();
        } finally {
            target.close();
        }
    }

    /** The four-component value glClearBufferuiv takes, red set. */
    private static IntBuffer red(int value) {
        IntBuffer buffer = BufferUtils.createIntBuffer(4);
        buffer.put(0, value);
        return buffer;
    }
}
