package io.github.mindbound.opengpu.core.render;

/**
 * Where a host delivers a device's readbacks (28:194-196): one per device, implemented by S7's
 * {@link io.github.mindbound.opengpu.core.device.Device}. Non-blocking (21:56); any thread may call it.
 */
public interface ReadbackSink {

    /**
     * Frame {@code frameId} of {@code epoch}'s records ran, and {@code pixels} is the surface after it in the 28 §5.7
     * layout. The sink copies the pixels before it returns, so the caller keeps its array and may reuse it (21:85's
     * two-array ring) as soon as this returns. Never blocks and never throws for any input.
     */
    void onReadback(long session, int deviceId, long epoch, long frameId, byte[] pixels);
}
