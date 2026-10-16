package io.github.mindbound.opengpu.gltestkit.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import io.github.mindbound.opengpu.core.render.ReadbackSink;

/**
 * A sink that keeps every readback it is handed (28 §2.4): the labels, a copy of the pixels, the array itself (so a
 * scenario can see the host reuse it, 28:1073) and, with a pass counter, the pass it came in. Thread-safe.
 */
public final class Deliveries implements ReadbackSink {

    /** One readback as delivered. */
    public static final class Delivery {

        public final long session;
        public final int deviceId;
        public final long epoch;
        public final long frameId;
        /** A copy taken in the call. */
        public final byte[] pixels;
        /** The array the host passed, for identity checks only: the host may write it again later. */
        public final byte[] array;
        /** The pass counter's value in the call, or -1 without one. */
        public final long pass;

        Delivery(long session, int deviceId, long epoch, long frameId, byte[] array, long pass) {
            this.session = session;
            this.deviceId = deviceId;
            this.epoch = epoch;
            this.frameId = frameId;
            this.pixels = array.clone();
            this.array = array;
            this.pass = pass;
        }

        @Override
        public String toString() {
            return "e" + epoch + " #" + frameId + (pass >= 0 ? " at pass " + pass : "");
        }
    }

    private final LongSupplier pass;
    private final List<Delivery> all = new ArrayList<>();

    public Deliveries() {
        this(null);
    }

    /** With a pass counter, such as {@code host.core()::passes}. */
    public Deliveries(LongSupplier pass) {
        this.pass = pass;
    }

    @Override
    public synchronized void onReadback(long session, int deviceId, long epoch, long frameId, byte[] pixels) {
        all.add(new Delivery(session, deviceId, epoch, frameId, pixels, pass == null ? -1 : pass.getAsLong()));
    }

    public synchronized int count() {
        return all.size();
    }

    /** The newest, or null before the first. */
    public synchronized Delivery last() {
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    public synchronized Delivery get(int index) {
        return all.get(index);
    }

    /** A copy, in delivery order. */
    public synchronized List<Delivery> all() {
        return new ArrayList<>(all);
    }

    @Override
    public synchronized String toString() {
        return "Deliveries" + all;
    }
}
