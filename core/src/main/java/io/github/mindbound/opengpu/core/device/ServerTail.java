package io.github.mindbound.opengpu.core.device;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.Record;

/**
 * The server tail (28:122): the records after the confirmed frame K, head first, which the server still needs to
 * replay. The device keeps it empty or starting with a {@code FRAME} (O6, C27). It counts the unconfirmed frames and
 * the queued resource bytes (28:140) and remembers when each frame was presented, for {@code "dropped"} (28:112). Not
 * thread-safe: the device's monitor guards it.
 */
final class ServerTail {

    /** A frame in the tail, with its present time. */
    private static final class Frame {

        final long frameId;
        final long presentedNanos;
        boolean reported;

        Frame(long frameId, long presentedNanos) {
            this.frameId = frameId;
            this.presentedNanos = presentedNanos;
        }
    }

    private final ArrayDeque<Record> records = new ArrayDeque<>();
    /** The tail's frames, head first. */
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private long queuedBytes;

    boolean isEmpty() {
        return records.isEmpty();
    }

    /** @throws java.util.NoSuchElementException if empty */
    Record first() {
        return records.getFirst();
    }

    /** Removes the head, keeping the counters in step. @throws java.util.NoSuchElementException if empty */
    Record removeFirst() {
        Record r = records.removeFirst();
        if (r.kind() == Record.FRAME) {
            frames.removeFirst();
        } else {
            queuedBytes -= r.queuedBytes();
        }
        return r;
    }

    /** Appends a presented frame. */
    void addFrame(FrameRecord frame, long presentedNanos) {
        records.addLast(frame);
        frames.addLast(new Frame(frame.frameId, presentedNanos));
    }

    /**
     * Appends a resource record behind a frame; its {@link Record#queuedBytes()} join the count.
     *
     * @throws IllegalStateException    if the tail is empty: a resource record with no frame ahead folds at once (O6)
     * @throws IllegalArgumentException for a record that is no resource record
     */
    void addResource(Record r) {
        if (!r.isResource()) {
            throw new IllegalArgumentException(r + " is no resource record");
        }
        if (records.isEmpty()) {
            throw new IllegalStateException(r + " would start the tail; it folds at once (O6)");
        }
        records.addLast(r);
        queuedBytes += r.queuedBytes();
    }

    /** The frames in the tail. */
    int unconfirmedFrames() {
        return frames.size();
    }

    /** Σ {@link Record#queuedBytes()} of the tail's resource records. */
    long queuedBytes() {
        return queuedBytes;
    }

    /** Whether FRAME frameId is in the tail. */
    boolean containsFrame(long frameId) {
        for (Frame f : frames) {
            if (f.frameId == frameId) {
                return true;
            }
        }
        return false;
    }

    /**
     * The frames not yet reported that were presented at least {@link Device#DROP_AFTER_NANOS} before now, in
     * ascending id order; marks them reported, so each is reported once (28:112).
     */
    long[] dueForDrop(long nowNanos) {
        long[] due = new long[frames.size()];
        int n = 0;
        for (Frame f : frames) {
            if (!f.reported && nowNanos - f.presentedNanos >= Device.DROP_AFTER_NANOS) {
                f.reported = true;
                due[n++] = f.frameId;
            }
        }
        return Arrays.copyOf(due, n);
    }

    /** A copy of the records, head first. */
    List<Record> records() {
        return new ArrayList<>(records);
    }

    /** Drops every record; both counters become 0. */
    void clear() {
        records.clear();
        frames.clear();
        queuedBytes = 0;
    }
}
