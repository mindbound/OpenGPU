package io.github.mindbound.opengpu.core.device.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SplittableRandom;
import java.util.function.Function;
import java.util.function.Supplier;

import org.opentest4j.AssertionFailedError;

import io.github.mindbound.opengpu.core.device.CallBudget;
import io.github.mindbound.opengpu.core.device.CallResult;
import io.github.mindbound.opengpu.core.device.DeviceCaps;
import io.github.mindbound.opengpu.core.device.DeviceSnapshot;
import io.github.mindbound.opengpu.core.device.Invariants;
import io.github.mindbound.opengpu.core.device.MasterTexture;
import io.github.mindbound.opengpu.core.device.model.OpGenerator.Op;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.RecordText;
import io.github.mindbound.opengpu.core.naive.NaiveDevice;
import io.github.mindbound.opengpu.core.render.FakeRenderHost;
import io.github.mindbound.opengpu.core.render.Record;

/**
 * One model-test sequence's two sides (S7 plan §4.5): {@code Device} on a {@link DeviceRig} ({@code FakeRenderHost}
 * with the tier-E oracle, a direct fold executor, a {@code ManualClock}, a {@code RecordingListener} and an unlimited
 * {@code RecordingBudget}) and the reference model {@link NaiveDevice}, written separately from 28 (plan §2.4, §6.3),
 * on the same clock. Each op runs on both; a {@code LimitReached} is OC's retry on either side (28:710-713: the
 * device's through {@code RecordingBudget.call}, the model's by calling again), and readbacks the host delivers are
 * handed to the model with their epoch, frame id and a copy of the pixels.
 * <p>
 * After every op it compares the results ({@code Arrays.deepEquals} of the Lua shapes), the budget events, the records
 * offered by the op ({@link RecordText}), the listener's events and the snapshot's scalars: epoch, the last frame id,
 * K, the unconfirmed frames, the queued bytes, the VRAM use, the live handles and the server tail. {@link #deepCheck}
 * adds the masters' bytes, K's pixels and {@link Invariants#checkAll} (I1-I12, I8, and I9's replay against a
 * full-history oracle fed only the newly offered records, amendment B8). A mismatch throws an assertion error naming
 * the op index, the op and both sides' values; {@link #recent} gives the ops before it.
 */
final class ModelBridge {

    private static final int RECENT = 12;

    private final DeviceCaps caps;
    private final DeviceRig rig;
    private final FakeRenderHost host;
    private final NaiveDevice model;
    private final Invariants.History history;
    private final OpGenerator generator;
    private final Set<String> coverage;
    /** Every offered record's descriptor, computed once (the tail repeats them, and their CRCs cost). */
    private final Map<Record, String> descriptors = new IdentityHashMap<>();
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private int deviceOffered;
    private int modelOffered;
    private int modelBudget;
    private int modelListener;
    private int readbacks;
    private int index = -1;
    private Op op;
    /** The device after the previous op. */
    private DeviceSnapshot before;

    /**
     * Two fresh sides on these caps, their getCaps compared.
     *
     * @param coverage collects a tag per distinct outcome (normalized messages, statuses, events), for the test's
     *                 coverage check
     */
    ModelBridge(DeviceCaps caps, long seed, Set<String> coverage) {
        this.caps = caps;
        this.rig = DeviceRig.of(caps);
        this.host = rig.host;
        this.model = new NaiveDevice(
            caps.surface,
            caps.limits,
            caps.vramBytes,
            caps.maxTextures,
            caps.maxTextureSize,
            caps.maxReadbackBytes,
            caps.maxUnconfirmedFrames,
            rig.clock);
        this.history = Invariants.History.of(rig.device);
        this.generator = new OpGenerator(caps, new SplittableRandom(seed));
        this.coverage = coverage;
        List<Map.Entry<String, Object>> device = new ArrayList<>(
            rig.device.getCaps()
                .entrySet());
        List<Map.Entry<String, Object>> reference = new ArrayList<>(
            model.getCaps()
                .entrySet());
        if (!device.equals(reference)) {
            throw new AssertionFailedError("getCaps differs (28:675-690)", reference, device);
        }
        this.before = rig.device.snapshot();
        compareState();
    }

    /** Draws the next op from the model's state, runs it on both sides and compares them. */
    void step() {
        index++;
        op = generator.next(model.liveHandles(), model.vramUsed());
        recent.addLast(index + ": " + op);
        if (recent.size() > RECENT) {
            recent.removeFirst();
        }
        apply(op);
        compareState();
    }

    /** The ops up to and including the current one, oldest first. */
    List<String> recent() {
        return new ArrayList<>(recent);
    }

    /** The masters' bytes and K's pixels against the model's, then every invariant with I9's replay. */
    void deepCheck() {
        DeviceSnapshot s = rig.device.snapshot();
        SortedMap<Integer, byte[]> reference = model.masters();
        if (!s.masters.keySet()
            .equals(reference.keySet())) {
            mismatch(
                "the masters' handles (28:123, O6)",
                hexes(new ArrayList<>(s.masters.keySet())),
                hexes(new ArrayList<>(reference.keySet())));
        }
        for (Map.Entry<Integer, MasterTexture> e : s.masters.entrySet()) {
            if (!Arrays.equals(
                e.getValue()
                    .texels(),
                reference.get(e.getKey()))) {
                mismatch(
                    "master " + Invariants.hex(e.getKey()) + "'s texels (O6)",
                    RecordText.crc32(
                        e.getValue()
                            .texels()),
                    RecordText.crc32(reference.get(e.getKey())));
            }
        }
        if (!Arrays.equals(s.confirmedPixels(), model.confirmedPixels())) {
            mismatch(
                "K's pixels (28:137)",
                RecordText.crc32(s.confirmedPixels()),
                RecordText.crc32(model.confirmedPixels()));
        }
        Invariants.checkAll(rig.device, host, history);
    }

    private void apply(Op op) {
        switch (op.kind) {
            case CREATE:
                create(op.w, op.h, op.format);
                return;
            case UPDATE:
                compare(
                    "updateTexture",
                    device(b -> rig.device.updateTexture(b, op.tex, op.x, op.y, op.w, op.h, op.data)),
                    model(() -> model.updateTexture(op.tex, op.x, op.y, op.w, op.h, op.data)));
                return;
            case FREE:
                compare(
                    "free",
                    rig.device.free(op.tex)
                        .toLua(),
                    model.free(op.tex));
                return;
            case SUBMIT:
                compare("submit", device(b -> rig.device.submit(b, op.data)), model(() -> model.submit(op.data)));
                return;
            case PRESENT:
                compare("present", device(b -> rig.device.present(b)), model(() -> model.present()));
                return;
            case READ_PIXELS:
                compare(
                    "readPixels",
                    device(b -> rig.device.readPixels(b, op.x, op.y, op.w, op.h)),
                    model(() -> model.readPixels(op.x, op.y, op.w, op.h)));
                return;
            case CHURN:
                compare(
                    "churn's submit",
                    device(b -> rig.device.submit(b, op.data)),
                    model(() -> model.submit(op.data)));
                for (int i = 0; i < op.pairCount(); i++) {
                    Object[] r = create(op.pairWidth(i), op.pairHeight(i), null);
                    if (r.length == 1 && r[0] instanceof Integer) {
                        int handle = (Integer) r[0];
                        compare(
                            "churn's free",
                            rig.device.free(handle)
                                .toLua(),
                            model.free(handle));
                    }
                }
                return;
            case RUN:
                host.run(rig.key, op.count);
                return;
            case READBACK:
                host.readback(rig.key);
                feedReadbacks();
                return;
            case STEP:
                host.step();
                feedReadbacks();
                return;
            case TICK:
                rig.clock.advanceMillis(op.count);
                rig.device.tick();
                model.tick();
                return;
            case RESET:
                rig.device.reset();
                model.reset();
                return;
            case SET_AVAILABLE:
                host.setAvailable(op.flag);
                model.setHostAvailable(op.flag);
                return;
            case SET_STALLED:
                host.setStalled(op.flag);
                model.setHostStalled(op.flag);
                return;
            default:
                throw new AssertionError(op.kind);
        }
    }

    /** createTexture on both, compared; a handle is reported to the generator. */
    private Object[] create(int w, int h, String format) {
        Object[] d = device(b -> rig.device.createTexture(b, w, h, format));
        Object[] m = model(() -> model.createTexture(w, h, format));
        compare("createTexture", d, m);
        if (d.length == 1 && d[0] instanceof Integer) {
            generator.created((Integer) d[0], w, h);
        } else if ("busy".equals(d[1])) {
            // H23's pending frees or H2's queued bytes, told apart on the state, which a refused create leaves as it
            // was (28 §2.1's order: no side effect before the charge)
            DeviceSnapshot s = rig.device.snapshot();
            long size = (long) w * h * caps.surface.bytesPerPixel();
            boolean pending = s.live.size() + s.deferredCount >= caps.maxTextures
                || s.vramUsed + s.deferredBytes + size > caps.vramBytes;
            coverage.add(pending ? "createTexture: busy (H23)" : "createTexture: busy (H2)");
        }
        return d;
    }

    /** The device's side of a callback, through OC's direct call and its retry. */
    private Object[] device(Function<CallBudget, CallResult> call) {
        return rig.budget.call(() -> call.apply(rig.budget))
            .toLua();
    }

    /** The model's side: a LimitReached is OC's retry, the same call once more. */
    private static Object[] model(Supplier<Object[]> call) {
        try {
            return call.get();
        } catch (NaiveDevice.LimitReached first) {
            return call.get();
        }
    }

    private void feedReadbacks() {
        List<FakeRenderHost.Readback> delivered = host.readbacks(rig.key);
        for (; readbacks < delivered.size(); readbacks++) {
            FakeRenderHost.Readback r = delivered.get(readbacks);
            model.onReadback(r.epoch, r.frameId, r.pixels.clone());
        }
    }

    private void compare(String call, Object[] device, Object[] reference) {
        if (!Arrays.deepEquals(device, reference)) {
            mismatch(call + "'s result", lua(device), lua(reference));
        }
        if (device.length == 2 && device[0] == null) {
            coverage.add(call + ": " + normalize((String) device[1]));
        } else {
            coverage.add(call + ": ok");
        }
    }

    /** Everything an op can change, against the model; the snapshot becomes {@link #before} for the next op. */
    private void compareState() {
        List<String> events = rig.budget.events();
        rig.budget.clearEvents();
        List<String> all = model.budgetEvents();
        List<String> reference = all.subList(modelBudget, all.size());
        modelBudget = all.size();
        if (!events.equals(reference)) {
            mismatch("budget events (28 §8.2)", events, reference);
        }
        for (String e : events) {
            coverage.add("budget: " + e.split(" ")[0]);
        }

        List<Record> offered = host.offered(rig.key);
        List<String> text = new ArrayList<>();
        for (; deviceOffered < offered.size(); deviceOffered++) {
            text.add(descriptor(offered.get(deviceOffered)));
        }
        all = model.offered();
        reference = all.subList(modelOffered, all.size());
        modelOffered = all.size();
        if (!text.equals(reference)) {
            mismatch("records offered (28:126, O1-O4)", text, reference);
        }

        events = rig.listener.events();
        rig.listener.clear();
        all = model.listenerEvents();
        reference = all.subList(modelListener, all.size());
        modelListener = all.size();
        if (!events.equals(reference)) {
            mismatch("frame statuses (28:110-115)", events, reference);
        }
        for (String e : events) {
            coverage.add("listener: " + e.split(" ")[0]);
        }

        DeviceSnapshot s = rig.device.snapshot();
        scalar("epoch (28:173)", s.epoch, model.epoch());
        scalar("the last frame id (28:108)", s.lastFrameId, model.lastFrameId());
        scalar("K (28:137)", s.confirmedFrameId, model.confirmedFrameId());
        scalar("unconfirmed frames (28:140)", s.unconfirmedFrames, model.unconfirmedFrames());
        scalar("queued bytes (28:140)", s.queuedBytes, model.queuedBytes());
        scalar("VRAM in use (28 §6.2)", s.vramUsed, model.vramUsed());
        List<Integer> live = new ArrayList<>();
        for (DeviceSnapshot.LiveTexture t : s.live) {
            live.add(t.handle);
        }
        if (!live.equals(model.liveHandles())) {
            mismatch("live handles (28 §6.1)", hexes(live), hexes(model.liveHandles()));
        }
        List<String> tail = new ArrayList<>();
        for (Record r : s.tail) {
            tail.add(descriptor(r));
        }
        if (!tail.equals(model.tail())) {
            mismatch("the server tail (28:122)", tail, model.tail());
        }
        if (s.staleReadbacks > before.staleReadbacks) {
            coverage.add("readback: stale");
        }
        // The model ignores what it cannot fold; a faithful host's readbacks are stale at worst, never rejected (H8)
        scalar("readbacks rejected", s.rejectedReadbacks, 0);
        if (s.deferredCount > 0 && s.unconfirmedFrames > 0) {
            coverage.add("state: frees deferred with frames in flight");
        }
        before = s;
    }

    private String descriptor(Record r) {
        String d = descriptors.get(r);
        if (d == null) {
            d = RecordText.of(r);
            descriptors.put(r, d);
        }
        return d;
    }

    private void scalar(String what, long device, long reference) {
        if (device != reference) {
            mismatch(what, device, reference);
        }
    }

    private void mismatch(String what, Object device, Object reference) {
        throw new AssertionFailedError(
            String.format(Locale.ROOT, "op %d, %s: %s: device %s, model %s", index, op, what, device, reference),
            reference,
            device);
    }

    /** A Lua result for a message: {@code [true]}, {@code [0x08000000]}, {@code [3]}, {@code [N B crc C, K]}. */
    static String lua(Object[] values) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                b.append(", ");
            }
            Object v = values[i];
            if (v == null) {
                b.append("nil");
            } else if (v instanceof Integer) {
                b.append(String.format(Locale.ROOT, "0x%08X", v));
            } else if (v instanceof byte[]) {
                byte[] data = (byte[]) v;
                b.append(String.format(Locale.ROOT, "%d B crc %08X", data.length, RecordText.crc32(data)));
            } else if (v instanceof String) {
                b.append('"')
                    .append(v)
                    .append('"');
            } else {
                b.append(v)
                    .append(" (")
                    .append(
                        v.getClass()
                            .getSimpleName())
                    .append(')');
            }
        }
        return b.append(']')
            .toString();
    }

    /** A message with its numbers taken out, as a coverage tag. */
    static String normalize(String message) {
        return message.replaceAll("0x[0-9A-F]{8}", "<handle>")
            .replaceAll("[0-9]+", "#")
            .replace("<handle>", "0x#");
    }

    private static List<String> hexes(List<Integer> handles) {
        List<String> out = new ArrayList<>();
        for (int h : handles) {
            out.add(Invariants.hex(h));
        }
        return out;
    }
}
