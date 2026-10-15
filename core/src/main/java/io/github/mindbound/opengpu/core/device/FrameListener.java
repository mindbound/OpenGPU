package io.github.mindbound.opengpu.core.device;

/**
 * Frame statuses for the root's {@code opengpu_frame} signal (24:127; 28:110-115; H12). A device delivers them outside
 * its monitor, in the order they arose, never from two threads at once, on whichever thread drains its queue: a fold's
 * thread or {@link Device#tick()}'s caller. Either status may arrive on either thread, so an implementation must be
 * thread-safe and must not assume the server thread (OC's {@code Context.signal} is thread-safe, 21:91). It must not
 * throw or block long. Frames discarded by a reset get no status (28:115).
 */
public interface FrameListener {

    /** A listener that does nothing. */
    FrameListener NONE = new FrameListener() {

        @Override
        public void frameDone(long frameId) {}

        @Override
        public void frameDropped(long frameId) {}

        @Override
        public String toString() {
            return "FrameListener.NONE";
        }
    };

    /** Frames up to frameId are confirmed: one call per fold, for its frame (21:87). */
    void frameDone(long frameId);

    /** The frame is still unconfirmed 1 s after its present; it stays queued, and a later done covers it (28:112). */
    void frameDropped(long frameId);
}
