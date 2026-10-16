package io.github.mindbound.opengpu.gl;

/**
 * CallSetTest's positive control (never run): a string naming LWJGL's package, which lwjgl3ify does not remap (14 §2)
 * and the root jarTest refuses in a shipped class (24:90), so the call-set check must report it.
 */
final class ControlLwjglString {

    private ControlLwjglString() {}

    static String name() {
        return "org.lwjgl.opengl.GL11";
    }
}
