package io.github.mindbound.opengpu.core.fixtures.device;

import java.util.List;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.ReadbackSink;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.RenderHost;

/**
 * A host under test with a {@link ShadowOracle} beside it (S8 plan §2.4 with amendment E): a device on this tee has
 * every readback the primary delivers checked against the oracle before it reaches the device. Each call goes to the
 * shadow first and then to the primary, so a frame's surface is kept before the primary can deliver it from another
 * thread; {@link #release} goes to the primary first, so no readback of a live opening reaches a released shadow.
 * {@link #available()} and {@link #stalled()} are the primary's. {@link #open} gives the primary the device's sink
 * wrapped by {@code shadow.checking(key, sink, failures)}, and the shadow a sink that is never called. It adds no lock
 * of its own, so it is as thread-safe as the two hosts.
 */
public final class TeeRenderHost implements RenderHost {

    private static final ReadbackSink UNUSED = new ReadbackSink() {

        @Override
        public void onReadback(long session, int deviceId, long epoch, long frameId, byte[] pixels) {}

        @Override
        public String toString() {
            return "TeeRenderHost's unused sink";
        }
    };

    private final RenderHost primary;
    private final ShadowOracle shadow;
    private final List<String> failures;

    /**
     * @param failures where the checking sinks append their failures, under the list's own lock
     * @throws NullPointerException for a null argument
     */
    public TeeRenderHost(RenderHost primary, ShadowOracle shadow, List<String> failures) {
        if (primary == null || shadow == null || failures == null) {
            throw new NullPointerException("primary, shadow and failures");
        }
        this.primary = primary;
        this.shadow = shadow;
        this.failures = failures;
    }

    public RenderHost primary() {
        return primary;
    }

    public ShadowOracle shadow() {
        return shadow;
    }

    /** The list the checking sinks append to. */
    public List<String> failures() {
        return failures;
    }

    @Override
    public boolean available() {
        return primary.available();
    }

    @Override
    public boolean stalled() {
        return primary.stalled();
    }

    @Override
    public void open(DeviceKey key, SurfaceDesc surface, ReadbackSink sink) {
        shadow.open(key, surface, UNUSED);
        primary.open(key, surface, shadow.checking(key, sink, failures));
    }

    @Override
    public void offer(DeviceKey key, Record record) {
        shadow.offer(key, record);
        primary.offer(key, record);
    }

    @Override
    public void release(DeviceKey key) {
        primary.release(key);
        shadow.release(key);
    }

    @Override
    public void bindDisplay(String displayId, DeviceKey key) {
        shadow.bindDisplay(displayId, key);
        primary.bindDisplay(displayId, key);
    }

    @Override
    public void releaseSession(long session) {
        primary.releaseSession(session);
        shadow.releaseSession(session);
    }

    @Override
    public String toString() {
        return "TeeRenderHost[" + primary + " | " + shadow + "]";
    }
}
