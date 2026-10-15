package io.github.mindbound.opengpu.core.render;

import io.github.mindbound.opengpu.core.SurfaceDesc;

/**
 * The host of a dedicated server, or of a client without a renderer (28:204): never available, so {@code present}
 * returns {@code "no renderer"} and {@code readPixels} the confirmed image, the cleared frame 0 in M0 (24:87; RM:65).
 * Submits and texture calls validate exactly as with a GL host (24:228). Every method does nothing, for any argument.
 */
public final class NoRenderHost implements RenderHost {

    public static final NoRenderHost INSTANCE = new NoRenderHost();

    private NoRenderHost() {}

    /** False. */
    @Override
    public boolean available() {
        return false;
    }

    /** False. */
    @Override
    public boolean stalled() {
        return false;
    }

    @Override
    public void open(DeviceKey key, SurfaceDesc surface, ReadbackSink sink) {}

    @Override
    public void offer(DeviceKey key, Record record) {}

    @Override
    public void release(DeviceKey key) {}

    @Override
    public void bindDisplay(String displayId, DeviceKey key) {}

    @Override
    public void releaseSession(long session) {}

    @Override
    public String toString() {
        return "NoRenderHost";
    }
}
