package io.github.mindbound.opengpu.core.fixtures.device;

import java.util.ArrayList;
import java.util.List;

import io.github.mindbound.opengpu.core.device.FrameListener;

/**
 * A {@link FrameListener} that records the frame statuses (28:110-115; H12) as {@code "done %d"} and
 * {@code "dropped %d"}, in call order, the reference model's format. Thread-safe.
 */
public final class RecordingListener implements FrameListener {

    private final List<String> events = new ArrayList<>();

    @Override
    public synchronized void frameDone(long frameId) {
        events.add("done " + frameId);
    }

    @Override
    public synchronized void frameDropped(long frameId) {
        events.add("dropped " + frameId);
    }

    /** A copy of the events so far, in call order. */
    public synchronized List<String> events() {
        return new ArrayList<>(events);
    }

    public synchronized void clear() {
        events.clear();
    }

    @Override
    public synchronized String toString() {
        return "RecordingListener" + events;
    }
}
