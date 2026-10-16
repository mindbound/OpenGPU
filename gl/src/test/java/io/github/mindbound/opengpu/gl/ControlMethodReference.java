package io.github.mindbound.opengpu.gl;

import org.lwjgl.opengl.GL11;

/**
 * CallSetTest's positive control (never run): a method reference to {@code GL11::glFlush}, an invokedynamic handle that
 * GLSM's redirector does not rewrite before 2.1.13 and the call-set check cannot see as a call (25 R-5), so the check
 * must report it.
 */
final class ControlMethodReference {

    private ControlMethodReference() {}

    static Runnable flush() {
        return GL11::glFlush;
    }
}
