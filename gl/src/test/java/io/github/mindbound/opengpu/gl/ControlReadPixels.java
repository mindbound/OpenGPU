package io.github.mindbound.opengpu.gl;

import org.lwjgl.opengl.GL11;

/**
 * CallSetTest's positive control (never run): a call on 25 §4.2's deny-list, A01 {@code glReadPixels(IIIIIIJ)V}, which
 * GLSM 2.2.8 to 2.2.21 lacks, so the call-set check must report it.
 */
final class ControlReadPixels {

    private ControlReadPixels() {}

    static void read() {
        GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, 0L);
    }
}
