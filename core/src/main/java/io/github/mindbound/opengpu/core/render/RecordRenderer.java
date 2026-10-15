package io.github.mindbound.opengpu.core.render;

import java.util.HashMap;
import java.util.Map;

import io.github.mindbound.opengpu.core.SurfaceDesc;

/**
 * {@link FakeRenderHost}'s pluggable renderer (28:205): zeros by default, the tier-E oracle from {@code :core}'s test
 * fixtures in tests, which keeps the oracle out of the main jar. Called by one thread at a time, under the host's
 * lock, so it never calls a {@code Device} or a {@link ReadbackSink}.
 */
public interface RecordRenderer {

    /** A device's fresh surface, all zero bytes (24:197; 21:242), replacing any state an earlier opening left. */
    void open(DeviceKey key, SurfaceDesc surface);

    /** Runs one record of an open device, in offered order (O5). */
    void accept(DeviceKey key, Record record);

    /**
     * The surface now: a fresh array of {@code surface.byteSize()} bytes in the 28 §5.7 layout, which the caller owns.
     */
    byte[] pixels(DeviceKey key);

    /** The device's state is dropped; a later {@link #open} of the key starts afresh (an addition to 28:205, S7). */
    default void release(DeviceKey key) {}

    /**
     * A new renderer that keeps every surface zeroed (23:165; 28:205). Its {@link #pixels} throws
     * IllegalStateException for a key that is not open.
     */
    static RecordRenderer zeros() {
        return new RecordRenderer() {

            private final Map<DeviceKey, SurfaceDesc> surfaces = new HashMap<>();

            @Override
            public void open(DeviceKey key, SurfaceDesc surface) {
                surfaces.put(key, surface);
            }

            @Override
            public void accept(DeviceKey key, Record record) {}

            @Override
            public byte[] pixels(DeviceKey key) {
                SurfaceDesc surface = surfaces.get(key);
                if (surface == null) {
                    throw new IllegalStateException(key + " is not open");
                }
                return new byte[surface.byteSize()];
            }

            @Override
            public void release(DeviceKey key) {
                surfaces.remove(key);
            }

            @Override
            public String toString() {
                return "RecordRenderer.zeros()";
            }
        };
    }
}
