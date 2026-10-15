package io.github.mindbound.opengpu.core.render;

import io.github.mindbound.opengpu.core.SurfaceDesc;

/**
 * The render SPI (28 §2.4; 21 §1.3): what a {@link io.github.mindbound.opengpu.core.device.Device} hands its records
 * to. A device calls {@link #available()}, {@link #stalled()}, {@link #open}, {@link #offer} and {@link #release} under
 * its monitor, so they never block, never call back into a device or a {@link ReadbackSink}, and are never called
 * while the host holds a lock that it also holds when it calls {@link ReadbackSink#onReadback}. For calls that follow
 * this contract they never throw either, since a device does not undo the state it changed before such a call (see
 * {@link #offer}). Every method may be called from any thread.
 */
public interface RenderHost {

    /** Whether a renderer runs the records; false makes {@code present} return {@code "no renderer"} (28:204). */
    boolean available();

    /**
     * Whether the host's render loop has been silent for more than 2 s, which makes {@code present} return
     * {@code "host render stalled"} (24:126; 20:192-194). An addition to 28:185-192 (S7); false by default.
     */
    default boolean stalled() {
        return false;
    }

    /**
     * Opens the device's surface, cleared to 0 (24:197; 21:242): once per device, before any {@link #offer} (28:199).
     * A key released earlier may be opened again, with a higher epoch.
     */
    void open(DeviceKey key, SurfaceDesc surface, ReadbackSink sink);

    /**
     * Queues one record of an open device, at append and in call order (28:126; O1, O5). Never blocks (21:80). For an
     * open key it accepts every record: it never throws and never drops one. The device has already changed its state
     * when it offers (28:89's order: live-view changes, then records appended and offered), so a host that threw or
     * dropped would leave the device and the host apart. A host bounds what it holds (H24, amendment C) only without
     * changing results: for example by applying the resource records offered ahead of the next {@code FRAME} to its
     * own copy of the textures, or by reporting {@link #stalled()}.
     */
    void offer(DeviceKey key, Record record);

    /** Drops the device: its queued records, its objects and its late readbacks (28:151; 21:106). */
    void release(DeviceKey key);

    /** Maps a display to a device, or unmaps it for a null key (the display block's, 21:63); devices never call it. */
    void bindDisplay(String displayId, DeviceKey key);

    /** Releases every device of a session at server stop (21:111); devices never call it. */
    void releaseSession(long session);
}
