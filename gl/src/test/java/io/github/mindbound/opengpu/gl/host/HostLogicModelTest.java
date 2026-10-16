package io.github.mindbound.opengpu.gl.host;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.head;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.function.Function;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.device.CallBudget;
import io.github.mindbound.opengpu.core.device.CallResult;
import io.github.mindbound.opengpu.core.device.Device;
import io.github.mindbound.opengpu.core.device.DeviceCaps;
import io.github.mindbound.opengpu.core.device.DeviceSnapshot;
import io.github.mindbound.opengpu.core.device.MasterTexture;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
import io.github.mindbound.opengpu.core.fixtures.device.ManualClock;
import io.github.mindbound.opengpu.core.fixtures.device.OracleRecordRenderer;
import io.github.mindbound.opengpu.core.fixtures.device.RecordingBudget;
import io.github.mindbound.opengpu.core.fixtures.device.RecordingListener;
import io.github.mindbound.opengpu.core.fixtures.device.ShadowOracle;
import io.github.mindbound.opengpu.core.fixtures.device.TeeRenderHost;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FakeRenderHost;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.ReadbackSink;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.RecordVisitor;
import io.github.mindbound.opengpu.core.render.RenderHost;
import io.github.mindbound.opengpu.core.render.ResetRecord;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexFreeRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;

/**
 * The host's property test (S8 plan §4.1 with amendment E, A2): seeded random sequences of the M0 callbacks, host
 * passes, stalls, ticks, resets and a release with a reopen under a higher epoch drive {@code Device}s on
 * {@link GlHostCore} over {@link OracleBackend}, with mirrors on and off, each behind a {@link TeeRenderHost} whose
 * {@link ShadowOracle} checks every readback. These properties hold:
 * <ol>
 * <li>every readback a device on the host receives equals {@code ShadowOracle.pixelsAfter} of its frame;</li>
 * <li>after a quiesce (passes with an unlimited budget until {@code idle}) the device's tail is empty, its K is its
 * last presented frame when no reset followed it, and its confirmed pixels, {@code readPixels} and masters equal the
 * oracle's replay of every offered record;</li>
 * <li>in a lockstep sequence (every host step two unlimited passes, readbacks finished at the next pass) mirrors on and
 * off give identical delivery traces, {@code CallResult}s and listener events;</li>
 * <li>in a lockstep sequence a device on {@code FakeRenderHost(OracleRecordRenderer)}, stepped at the same operation
 * indices, gives the same {@code CallResult}s, listener events and device-visible state after every operation; only
 * the stale-readback counters may differ (H37).</li>
 * </ol>
 * The other half of the sequences are budgeted: each step is one pass of {@code units(k)} or unlimited, each readback
 * finishes 1-4 passes after its issue, a latency of its own from the seed and the device's issue index (so a newer one
 * may wait behind an older one and one pass fetch both, 21:87), or only when forced ({@code NEVER_READY}), and the
 * expand option varies; there properties 1 and 2 hold for each host apart. After every operation: I-H1 (no resource
 * record ahead of a FRAME between
 * passes), the bounds of plan §3.10 (mirrors within the caps, equal to {@code vramUsed + deferredBytes} with the queue
 * empty, queued bytes within the tail bound, queued frames within the unconfirmed cap, pending within twice the texture
 * cap) and G10 once idle (28:1083); and, every operation on the small caps and every 16th on T2, I-H2 (the mirrors
 * equal a replay of the offered records ahead of the queue) and I-H3 (the GPU's textures plus what is pending give
 * the mirrors), and once idle the GPU's textures equal the oracle's.
 * <p>
 * The default run is {@value #DEFAULT_CASES} sequences of {@value #OPS} operations on plan §4's small caps, half index8
 * and half their rgb565 twin, plus a tenth as many on T2. {@code -Dopengpu.host.cases=N} (Gradle:
 * {@code -Popengpu.host.cases}) scales it, {@code -Dopengpu.host.seed=S} (default 8) changes the stream. Each sequence
 * is a dynamic test named by its seed; a failure names the op index, the op and the ops before it. Each part's coverage
 * test then checks that the run reached the outcomes the rules are about, when the run has the default size, and the
 * times are printed.
 */
class HostLogicModelTest {

    static final int DEFAULT_CASES = 200;
    static final int OPS = 150;
    static final long SEED = Long.getLong("opengpu.host.seed", 8L);
    static final int CASES = Integer.getInteger("opengpu.host.cases", DEFAULT_CASES);
    static final DeviceKey KEY = new DeviceKey(1, 1);
    /** The callbacks' names, for the coverage outcomes. */
    private static final Map<Kind, String> NAMES = new HashMap<>();

    static {
        NAMES.put(Kind.CREATE, "createTexture");
        NAMES.put(Kind.UPDATE, "updateTexture");
        NAMES.put(Kind.FREE, "free");
        NAMES.put(Kind.SUBMIT, "submit");
        NAMES.put(Kind.PRESENT, "present");
        NAMES.put(Kind.READ_PIXELS, "readPixels");
    }

    /** Plan §4's small caps (S7's): 24x16 index8, 2 KB of VRAM, 6 textures of at most 32 px a side, 256 B per read. */
    static final DeviceCaps SMALL_INDEX8 = new DeviceCaps(
        new SurfaceDesc(2, 24, 16, Formats.INDEX8),
        new FrameLimits(65536, 512, 24, 4000),
        2048,
        6,
        32,
        256,
        2);
    /** Their rgb565 twin: 16x12, 4 KB of VRAM, 512 B per read. */
    static final DeviceCaps SMALL_RGB565 = new DeviceCaps(
        new SurfaceDesc(3, 16, 12, Formats.RGB565),
        new FrameLimits(65536, 512, 24, 4000),
        4096,
        6,
        32,
        512,
        2);

    private static final List<String> COMMON = Arrays.asList(
        "createTexture: ok",
        "updateTexture: ok",
        "free: ok",
        "submit: ok",
        "submit: rejected",
        "present: ok",
        "present: busy",
        "readPixels: ok",
        "listener: done",
        "listener: dropped",
        "host: RESET dropped queued records",
        "host: applied behind a taken FRAME",
        "host: mirror adopted a payload",
        "host: mirror cloned",
        "host: mirror allocated",
        "host: readback owed with two in flight",
        "host: two fetched in one pass",
        "host: release and reopen",
        "device: frees deferred",
        "device: stale readback",
        "sequence: lockstep",
        "sequence: budgeted");
    private static final List<String> SMALL_ONLY = Arrays
        .asList("createTexture: busy", "updateTexture: busy", "host: forced fetch delivered");

    private static final List<Part> PARTS = new ArrayList<>();

    /** One part of the run. */
    static final class Part {

        final String name;
        final int ordinal;
        final DeviceCaps caps;
        final int sequences;
        final int deepEvery;
        final List<String> required;
        final Set<String> coverage = new TreeSet<>();
        long nanos;
        int ran;

        Part(String name, int ordinal, DeviceCaps caps, int sequences, int deepEvery, List<String> required) {
            this.name = name;
            this.ordinal = ordinal;
            this.caps = caps;
            this.sequences = sequences;
            this.deepEvery = deepEvery;
            this.required = required;
        }
    }

    @TestFactory
    List<DynamicTest> theHostKeepsEveryResultUnchanged() {
        Assertions.assertTrue(Record.devChecks(), "dev checks run (-ea), as in :gl:test");
        PARTS.clear();
        List<String> small = new ArrayList<>(COMMON);
        small.addAll(SMALL_ONLY);
        int index8 = CASES / 2;
        PARTS.add(new Part("small index8", 0, SMALL_INDEX8, index8, 1, small));
        PARTS.add(new Part("small rgb565", 1, SMALL_RGB565, CASES - index8, 1, small));
        PARTS.add(new Part("T2", 2, DeviceCaps.forTier(2), Math.max(2, CASES / 10), 16, COMMON));
        List<DynamicTest> tests = new ArrayList<>();
        for (final Part part : PARTS) {
            for (int i = 0; i < part.sequences; i++) {
                final long seed = SEED * 1_000_003L + part.ordinal * 100_003L + i;
                final boolean lockstep = i % 2 == 0;
                tests.add(
                    DynamicTest.dynamicTest(
                        String.format(
                            Locale.ROOT,
                            "%s #%d %s, seed %d",
                            part.name,
                            i,
                            lockstep ? "lockstep" : "budgeted",
                            seed),
                        () -> run(part, seed, lockstep)));
            }
            tests.add(DynamicTest.dynamicTest(part.name + ": coverage", () -> coverage(part)));
        }
        tests.add(DynamicTest.dynamicTest("times", HostLogicModelTest::times));
        return tests;
    }

    private static void run(Part part, long seed, boolean lockstep) {
        long started = System.nanoTime();
        try {
            new Sequence(part, seed, lockstep).run();
        } finally {
            synchronized (part) {
                part.nanos += System.nanoTime() - started;
                part.ran++;
            }
        }
    }

    private static void coverage(Part part) {
        if (CASES < DEFAULT_CASES) {
            System.out.println(part.name + ": coverage not checked below the default size: " + part.coverage);
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String outcome : part.required) {
            if (!part.coverage.contains(outcome)) {
                missing.add(outcome);
            }
        }
        Assertions
            .assertTrue(missing.isEmpty(), part.name + " never reached " + missing + "; reached " + part.coverage);
    }

    private static void times() {
        long total = 0;
        StringBuilder line = new StringBuilder("HostLogicModelTest:");
        for (Part part : PARTS) {
            total += part.nanos;
            line.append(
                String.format(Locale.ROOT, " %s %d sequences %d ms;", part.name, part.ran, part.nanos / 1_000_000));
        }
        line.append(String.format(Locale.ROOT, " total %d ms (%d cases, seed %d)", total / 1_000_000, CASES, SEED));
        System.out.println(line);
    }

    // ---- The ops

    enum Kind {
        CREATE,
        UPDATE,
        FREE,
        SUBMIT,
        PRESENT,
        READ_PIXELS,
        STEP,
        STALL,
        TICK,
        RESET,
        REOPEN,
        CHURN
    }

    /** One op; its arrays are never written after generation. */
    static final class Op {

        final Kind kind;
        int tex, x, y, w, h, count;
        String format;
        byte[] data;
        int[] pairs;
        String note;

        Op(Kind kind) {
            this.kind = kind;
        }

        @Override
        public String toString() {
            switch (kind) {
                case CREATE:
                    return String.format(
                        Locale.ROOT,
                        "createTexture(%d, %d, %s)",
                        w,
                        h,
                        format == null ? "nil" : "\"" + format + "\"");
                case UPDATE:
                    return String.format(
                        Locale.ROOT,
                        "updateTexture(0x%08X, %d, %d, %d, %d, %d B)",
                        tex,
                        x,
                        y,
                        w,
                        h,
                        data.length);
                case FREE:
                    return String.format(Locale.ROOT, "free(0x%08X)", tex);
                case SUBMIT:
                    return "submit(" + data.length + " B: " + note + ")";
                case READ_PIXELS:
                    return String.format(Locale.ROOT, "readPixels(%d, %d, %d, %d)", x, y, w, h);
                case STEP:
                    return "step(" + (count == 0 ? "unlimited" : "units(" + count + ")") + ")";
                case STALL:
                    return "stall(" + count + " ops)";
                case TICK:
                    return "tick(+" + count + " ms)";
                case CHURN:
                    return "churn(" + pairs.length / 2 + " pairs)";
                default:
                    return kind.name()
                        .toLowerCase(Locale.ROOT) + "()";
            }
        }
    }

    // ---- One device on one host

    /** A device and its host: GlHostCore over OracleBackend behind a tee, or FakeRenderHost with the oracle. */
    static final class Lane {

        final String name;
        final DeviceCaps caps;
        final ManualClock clock;
        final RecordingListener listener = new RecordingListener();
        final RecordingBudget budget = new RecordingBudget();
        final List<String> failures = Collections.synchronizedList(new ArrayList<String>());
        /** The delivery trace: "e<epoch> #<frame>" per readback the host delivered. */
        final List<String> deliveries = new ArrayList<>();
        final OracleBackend backend;
        final GlHostCore core;
        final ShadowOracle shadow;
        final FakeRenderHost fake;
        final OracleRecordRenderer renderer;
        final RenderHost host;
        final boolean mirrors;
        Device device;
        boolean presentedAfterReset;
        /** I-H2's replay of the offered records ahead of the host's queue (mirrors on). */
        TierEOracle prefix;
        final ArrayDeque<Record> unreplayed = new ArrayDeque<>();

        /** A lane on GlHostCore. */
        Lane(String name, DeviceCaps caps, ManualClock clock, boolean mirrors, int latency, boolean neverReady,
            int force, HostOptions.Expand expand) {
            this.name = name;
            this.caps = caps;
            this.clock = clock;
            this.mirrors = mirrors;
            this.backend = new OracleBackend();
            backend.latency = latency;
            backend.neverReady = neverReady;
            this.core = new GlHostCore(
                backend,
                HostOptions.defaults()
                    .mirrors(mirrors)
                    .stallAfterNanos(Long.MAX_VALUE)
                    .forceFetchAfterPasses(force)
                    .expand(expand)
                    .log(failures::add));
            this.shadow = new ShadowOracle(SyntheticGlyphs.TABLE);
            this.fake = null;
            this.renderer = null;
            this.host = new TeeRenderHost(new Recorder(), shadow, failures);
        }

        /** A lane on FakeRenderHost(OracleRecordRenderer) (property 4). */
        Lane(String name, DeviceCaps caps, ManualClock clock) {
            this.name = name;
            this.caps = caps;
            this.clock = clock;
            this.mirrors = false;
            this.backend = null;
            this.core = null;
            this.shadow = null;
            this.renderer = new OracleRecordRenderer();
            this.fake = new FakeRenderHost(renderer);
            fake.setKeepHistory(false);
            this.host = fake;
        }

        boolean gl() {
            return core != null;
        }

        void open(long epoch) {
            device = Device.create(KEY, caps, host, Runnable::run, listener, clock, epoch);
            presentedAfterReset = false;
        }

        CallResult call(Function<CallBudget, CallResult> call) {
            return budget.call(() -> call.apply(budget));
        }

        /** A host step: two unlimited passes in lockstep (a FakeRenderHost step), else one pass of the budget. */
        void step(boolean lockstep, int units) {
            if (!gl()) {
                fake.step();
            } else if (lockstep) {
                core.pass(PassBudget.unlimited());
                core.pass(PassBudget.unlimited());
            } else {
                core.pass(units == 0 ? PassBudget.unlimited() : PassBudget.units(units));
            }
        }

        DeviceStats stats() {
            return core.stats(KEY);
        }

        /** Folds the offered records that left the host's queue into the I-H2 replay. */
        void advancePrefix() {
            int queued = stats().queuedRecords;
            while (unreplayed.size() > queued) {
                unreplayed.pollFirst()
                    .accept(new RecordVisitor() {

                        @Override
                        public void frame(FrameRecord r) {}

                        @Override
                        public void texCreate(TexCreateRecord r) {
                            prefix.createTexture(r.handle, r.width, r.height, r.format);
                        }

                        @Override
                        public void texUpdate(TexUpdateRecord r) {
                            prefix.updateTexture(r.handle, r.x, r.y, r.width, r.height, r.payload());
                        }

                        @Override
                        public void texFree(TexFreeRecord r) {
                            prefix.freeTexture(r.handle);
                        }

                        @Override
                        public void reset(ResetRecord r) {
                            prefix.reset(r.pixels());
                        }
                    });
            }
        }

        /** GlHostCore behind the tee, recording the offered records (I-H2) and the delivery trace (property 3). */
        final class Recorder implements RenderHost {

            @Override
            public boolean available() {
                return core.available();
            }

            @Override
            public boolean stalled() {
                return core.stalled();
            }

            @Override
            public void open(DeviceKey key, SurfaceDesc surface, final ReadbackSink sink) {
                prefix = new TierEOracle(surface, SyntheticGlyphs.TABLE);
                unreplayed.clear();
                core.open(key, surface, (session, deviceId, epoch, frameId, pixels) -> {
                    deliveries.add("e" + epoch + " #" + frameId);
                    sink.onReadback(session, deviceId, epoch, frameId, pixels);
                });
            }

            @Override
            public void offer(DeviceKey key, Record record) {
                if (mirrors) {
                    unreplayed.addLast(record);
                }
                core.offer(key, record);
            }

            @Override
            public void release(DeviceKey key) {
                core.release(key);
            }

            @Override
            public void bindDisplay(String displayId, DeviceKey key) {
                core.bindDisplay(displayId, key);
            }

            @Override
            public void releaseSession(long session) {
                core.releaseSession(session);
            }
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // ---- One sequence

    static final class Sequence {

        final Part part;
        final DeviceCaps caps;
        final SurfaceDesc surface;
        final int bpp;
        final long seed;
        final boolean lockstep;
        final SplittableRandom r;
        final ManualClock clock = new ManualClock();
        final Lane on;
        final Lane off;
        final Lane fake;
        final List<Lane> lanes = new ArrayList<>();
        final List<Lane> gl = new ArrayList<>();
        final ArrayDeque<String> recent = new ArrayDeque<>();
        final List<Integer> freed = new ArrayList<>();
        /** The mirrors-on device's live textures, handle to {w, h}, from its results (no snapshot per op). */
        final Map<Integer, int[]> live = new LinkedHashMap<>();
        final Set<String> coverage = new TreeSet<>();
        int index;
        int stallUntil = -1;

        Sequence(Part part, long seed, boolean lockstep) {
            this.part = part;
            this.caps = part.caps;
            this.surface = caps.surface;
            this.bpp = surface.bytesPerPixel();
            this.seed = seed;
            this.lockstep = lockstep;
            this.r = new SplittableRandom(seed);
            boolean neverReady = !lockstep && r.nextInt(4) == 0;
            int force = lockstep ? HostOptions.FORCE_FETCH_AFTER_PASSES : r.nextInt(1, neverReady ? 9 : 5);
            HostOptions.Expand expand = HostOptions.Expand.values()[r.nextInt(3)];
            // Lockstep: every readback finished at the next pass. Budgeted: each readback its own latency of 1-4
            // passes, from the seed and the device's issue index, not from r, so both lanes draw the same for the
            // same issue; a newer one may then finish first and wait, and one pass fetch two (21:87)
            on = new Lane("mirrors on", caps, clock, true, 1, neverReady, force, expand);
            off = new Lane("mirrors off", caps, clock, false, 1, neverReady, force, expand);
            if (!lockstep) {
                on.backend.latencyPerIssue(seed);
                off.backend.latencyPerIssue(seed);
            }
            fake = lockstep ? new Lane("FakeRenderHost", caps, clock) : null;
            lanes.add(on);
            lanes.add(off);
            gl.add(on);
            gl.add(off);
            if (fake != null) {
                lanes.add(fake);
            }
            for (Lane lane : lanes) {
                lane.open(1);
            }
            coverage.add(lockstep ? "sequence: lockstep" : "sequence: budgeted");
            if (neverReady) {
                coverage.add("sequence: never ready");
            }
        }

        void run() {
            for (index = 0; index < OPS; index++) {
                Op op = next();
                remember(op.toString());
                Map<Lane, Object> results = new HashMap<>();
                long droppedBefore = on.stats().recordsDropped;
                if (op.kind == Kind.TICK) {
                    clock.advanceMillis(op.count);
                }
                if (op.kind == Kind.STALL) {
                    stallUntil = index + op.count;
                }
                for (Lane lane : lanes) {
                    results.put(lane, apply(lane, op));
                }
                note(op, results.get(on));
                track(op, results.get(on));
                if (op.kind == Kind.RESET && on.stats().recordsDropped > droppedBefore) {
                    coverage.add("host: RESET dropped queued records");
                }
                check(op, results);
            }
            quiesce();
            synchronized (part) {
                part.coverage.addAll(coverage);
            }
        }

        // -- Applying an op to every lane

        private Object apply(Lane lane, Op op) {
            final Device d = lane.device;
            switch (op.kind) {
                case CREATE:
                    return lane.call(b -> d.createTexture(b, op.w, op.h, op.format));
                case UPDATE:
                    return lane.call(b -> d.updateTexture(b, op.tex, op.x, op.y, op.w, op.h, op.data));
                case FREE:
                    return d.free(op.tex);
                case SUBMIT:
                    return lane.call(b -> d.submit(b, op.data));
                case PRESENT: {
                    CallResult result = lane.call(b -> d.present(b));
                    if (result.isOk()) {
                        lane.presentedAfterReset = true;
                    }
                    return result;
                }
                case READ_PIXELS:
                    return lane.call(b -> d.readPixels(b, op.x, op.y, op.w, op.h));
                case STEP:
                    if (index < stallUntil) {
                        return "stalled";
                    }
                    lane.step(lockstep, op.count);
                    return "stepped";
                case STALL:
                    return "stall";
                case TICK:
                    d.tick();
                    return "tick";
                case RESET:
                    d.reset();
                    lane.presentedAfterReset = false;
                    return "reset";
                case REOPEN: {
                    long epoch = d.epoch();
                    d.release();
                    lane.open(epoch + 1);
                    return "reopen";
                }
                case CHURN: {
                    List<Object> out = new ArrayList<>();
                    out.add(lane.call(b -> d.submit(b, op.data)));
                    for (int i = 0; i < op.pairs.length; i += 2) {
                        final int w = op.pairs[i];
                        final int h = op.pairs[i + 1];
                        CallResult created = lane.call(b -> d.createTexture(b, w, h, null));
                        out.add(created);
                        if (created.isOk()) {
                            out.add(d.free(created.handle()));
                        }
                    }
                    return out;
                }
                default:
                    throw new IllegalStateException(op.toString());
            }
        }

        // -- The checks after every op

        private void check(Op op, Map<Lane, Object> results) {
            for (Lane lane : gl) {
                // Property 1: every readback equals the oracle's surface after its frame
                if (!lane.failures.isEmpty()) {
                    fail(lane + ": " + lane.failures);
                }
                Assertions.assertNull(lane.core.failure(), lane.core.failure());
                if (lane.core.contractViolations() != 0) {
                    fail(lane + ": " + lane.core.contractViolations() + " contract violations");
                }
            }
            if (lockstep) {
                // Properties 3 and 4: the same results and events, and the same delivery trace with mirrors on and off
                Object expected = results.get(on);
                for (Lane lane : lanes) {
                    if (!Objects.equals(expected, results.get(lane))) {
                        fail("results differ: " + on + " " + expected + ", " + lane + " " + results.get(lane));
                    }
                    if (!on.listener.events()
                        .equals(lane.listener.events())) {
                        fail(
                            "events differ: " + on
                                + " "
                                + on.listener.events()
                                + ", "
                                + lane
                                + " "
                                + lane.listener.events());
                    }
                }
                if (!on.deliveries.equals(off.deliveries)) {
                    fail("delivery traces differ: on " + on.deliveries + ", off " + off.deliveries);
                }
            }
            boolean deep = (index + 1) % part.deepEvery == 0;
            for (Lane lane : gl) {
                invariants(lane, deep);
            }
            if (lockstep && deep) {
                DeviceSnapshot a = on.device.snapshot();
                for (Lane lane : lanes) {
                    if (lane != on) {
                        sameDeviceState(a, lane);
                    }
                }
            }
        }

        /** I-H1-I-H3, plan §3.10's bounds and G10 for one GlHostCore lane; the texel-level ones when deep. */
        private void invariants(Lane lane, boolean deep) {
            DeviceStats s = lane.stats();
            if (s.queuedFrames > caps.maxUnconfirmedFrames) {
                fail(who(lane, s) + ": more queued frames than the device can have unconfirmed");
            }
            if (s.readbackOwed && s.readbacksInFlight == 2) {
                coverage.add("host: readback owed with two in flight");
            }
            if (s.appliedQueued > 0) {
                coverage.add("host: applied behind a taken FRAME");
            }
            if (s.mirrorAdoptions > 0) {
                coverage.add("host: mirror adopted a payload");
            }
            if (s.mirrorClones > 0) {
                coverage.add("host: mirror cloned");
            }
            if (s.mirrorAllocations > 0) {
                coverage.add("host: mirror allocated");
            }
            if (lane.backend.neverReady && s.readbacksDelivered > 0) {
                coverage.add("host: forced fetch delivered");
            }
            if (lane.backend.fetchedTogether() > 0) {
                // 21:87: only the newer of the two is delivered, and property 1 checks it against its own frame
                coverage.add("host: two fetched in one pass");
            }
            DeviceSnapshot snap = needsSnapshot(lane, s, deep) ? lane.device.snapshot() : null;
            if (snap != null && snap.deferredCount > 0) {
                coverage.add("device: frees deferred");
            }
            if (snap != null && snap.staleReadbacks > 0) {
                coverage.add("device: stale readback");
            }
            if (lane.mirrors) {
                // I-H1 between passes: the drain left no resource record ahead of a FRAME
                if (s.headResources != 0) {
                    fail(who(lane, s) + ": I-H1: resource records at the head between passes");
                }
                if (s.mirrorTextures > caps.maxTextures || s.mirrorBytes > caps.vramBytes) {
                    fail(who(lane, s) + ": mirrors over the caps");
                }
                if (s.pendingTextures > 2 * caps.maxTextures) {
                    fail(who(lane, s) + ": pending over twice the texture cap");
                }
                long bound = caps.vramBytes + (long) Record.RESOURCE_OVERHEAD * caps.maxTextures
                    + (long) caps.maxUnconfirmedFrames * caps.limits.frameBytes;
                if (s.queuedBytes > bound) {
                    fail(who(lane, s) + ": queued bytes over the tail bound " + bound);
                }
                if (snap != null && s.queuedRecords == 0 && !snap.released) {
                    if (s.mirrorBytes != snap.vramUsed + snap.deferredBytes
                        || s.mirrorTextures != snap.live.size() + snap.deferredCount) {
                        fail(
                            who(lane, s) + ": with the queue empty the mirrors are not the device's textures: " + snap);
                    }
                }
                lane.advancePrefix();
                if (deep) {
                    mirrorsEqualThePrefix(who(lane, s), lane);
                    gpuPlusPendingIsTheMirrors(who(lane, s), lane);
                }
            }
            if (lane.core.idle(KEY) && s.opened && snap != null) {
                // G10 (28:1083): once idle the GPU holds the device's live textures and its deferred frees
                if (s.gpuTextureBytes != snap.vramUsed + snap.deferredBytes
                    || s.gpuTextures != snap.live.size() + snap.deferredCount) {
                    fail(who(lane, s) + ": G10: idle, but the GPU's textures are not the device's: " + snap);
                }
                if (deep) {
                    gpuEqualsTheOracle(who(lane, s), lane);
                }
            }
        }

        /** Every op on the small caps; on T2, whose masters reach 1 MiB, with the deep checks only. */
        private String who(Lane lane, DeviceStats s) {
            return lane + ": " + s;
        }

        private boolean needsSnapshot(Lane lane, DeviceStats s, boolean deep) {
            return deep || caps.vramBytes <= 4096;
        }

        /** I-H2: every mirror equals the replay of the offered records ahead of the queue, and no other is there. */
        private void mirrorsEqualThePrefix(String who, Lane lane) {
            HostDevice hd = lane.core.device(KEY);
            if (hd == null) {
                return;
            }
            Map<Integer, byte[]> mirrors = hd.mirrorTexels();
            if (mirrors.size() != lane.prefix.textureCount()) {
                fail(who + ": I-H2: " + mirrors.size() + " mirrors, the replay has " + lane.prefix.textureCount());
            }
            for (Map.Entry<Integer, byte[]> e : mirrors.entrySet()) {
                Mirror m = hd.mirror(e.getKey());
                compareTexels(who + ", I-H2 mirror " + m, lane.prefix, m, e.getValue(), null);
                expectOutside(who + ", I-H2 size of " + m, lane.prefix, m);
            }
        }

        /** I-H3: the GPU's textures with what is pending applied give the mirrors. */
        private void gpuPlusPendingIsTheMirrors(String who, Lane lane) {
            HostDevice hd = lane.core.device(KEY);
            DeviceStats s = lane.stats();
            // The backend's newest device is this opening's only once it had its first turn (after a reopen the old
            // one lives until step (d))
            if (hd == null || !s.opened || s.pendingReset) {
                return;
            }
            OracleBackend.Device gpu = lane.backend.device(KEY);
            Map<Integer, Pending> pending = new HashMap<>();
            for (Pending p : hd.pendingCopy()) {
                pending.put(p.handle, p);
            }
            Set<Integer> onGpu = gpu.handles();
            Map<Integer, byte[]> mirrors = hd.mirrorTexels();
            for (Map.Entry<Integer, byte[]> e : mirrors.entrySet()) {
                int handle = e.getKey();
                Mirror m = hd.mirror(handle);
                Pending p = pending.get(handle);
                if (p != null && p.create) {
                    if (onGpu.contains(handle) && !p.deleteGpu) {
                        fail(who + ": I-H3: " + p + " but the GPU holds the texture and no delete is pending");
                    }
                    continue;
                }
                if (!onGpu.contains(handle)) {
                    fail(who + ": I-H3: " + m + " is on no GPU and no create is pending");
                }
                compareTexels(who + ", I-H3 " + m, gpu.oracle, m, e.getValue(), p);
            }
            for (int handle : onGpu) {
                Pending p = pending.get(handle);
                if (!mirrors.containsKey(handle) && (p == null || !p.deleteGpu)) {
                    fail(who + String.format(Locale.ROOT, ": I-H3: the GPU holds 0x%08X, which no mirror has", handle));
                }
            }
            for (Pending p : pending.values()) {
                if (p.deleteGpu && !onGpu.contains(p.handle)) {
                    fail(who + ": I-H3: " + p + " but the GPU does not hold it");
                }
            }
        }

        /** Once idle: the GPU's textures are the oracle's, texel by texel. */
        private void gpuEqualsTheOracle(String who, Lane lane) {
            if (!lane.stats().opened) {
                return;
            }
            OracleBackend.Device gpu = lane.backend.device(KEY);
            TierEOracle oracle = lane.shadow.oracle(KEY);
            if (gpu.handles()
                .size() != oracle.textureCount()) {
                fail(
                    who + ": idle: the GPU holds "
                        + gpu.handles()
                        + ", the oracle "
                        + oracle.textureCount()
                        + " textures");
            }
            for (int handle : gpu.handles()) {
                int[] size = gpu.textures.get(handle);
                for (int y = 0; y < size[1]; y++) {
                    for (int x = 0; x < size[0]; x++) {
                        if (!oracle.hasTexture(handle)
                            || gpu.oracle.texel(handle, x, y) != oracle.texel(handle, x, y)) {
                            fail(
                                who + String.format(
                                    Locale.ROOT,
                                    ": idle: GPU texture 0x%08X differs at (%d, %d)",
                                    handle,
                                    x,
                                    y));
                        }
                    }
                }
            }
        }

        /** Texel by texel, skipping the dirty box of a pending entry. */
        private void compareTexels(String who, TierEOracle oracle, Mirror m, byte[] texels, Pending dirty) {
            for (int y = 0; y < m.height; y++) {
                for (int x = 0; x < m.width; x++) {
                    if (dirty != null && dirty.dirty()
                        && x >= dirty.x0
                        && x < dirty.x1
                        && y >= dirty.y0
                        && y < dirty.y1) {
                        continue;
                    }
                    int i = (y * m.width + x) * m.bpp;
                    int value = m.bpp == 1 ? texels[i] & 0xFF : (texels[i] & 0xFF) | (texels[i + 1] & 0xFF) << 8;
                    int expected;
                    try {
                        expected = oracle.texel(m.handle, x, y);
                    } catch (IllegalStateException e) {
                        fail(who + ": the reference lacks texel (" + x + ", " + y + "): " + e.getMessage());
                        return;
                    }
                    if (value != expected) {
                        fail(
                            who + String
                                .format(Locale.ROOT, ": texel (%d, %d) is 0x%X, expected 0x%X", x, y, value, expected));
                    }
                }
            }
        }

        /** The reference texture has the mirror's size: (w, 0) and (0, h) lie outside it. */
        private void expectOutside(String who, TierEOracle oracle, Mirror m) {
            for (int[] p : new int[][] { { m.width, 0 }, { 0, m.height } }) {
                try {
                    oracle.texel(m.handle, p[0], p[1]);
                    fail(who + ": the reference texture is larger than the mirror");
                } catch (IllegalStateException expected) {
                    // outside, as it must be
                }
            }
        }

        /** Property 4's device-visible state: everything but the stale-readback counters (H37). */
        private void sameDeviceState(DeviceSnapshot a, Lane lane) {
            DeviceSnapshot b = lane.device.snapshot();
            String who = on + " against " + lane;
            if (a.epoch != b.epoch || a.lastFrameId != b.lastFrameId
                || a.confirmedFrameId != b.confirmedFrameId
                || a.unconfirmedFrames != b.unconfirmedFrames
                || a.queuedBytes != b.queuedBytes
                || a.vramUsed != b.vramUsed
                || !a.deferredFrees.equals(b.deferredFrees)
                || !a.live.toString()
                    .equals(b.live.toString())
                || a.rejectedReadbacks != b.rejectedReadbacks) {
                fail(who + ": device states differ: " + a + " against " + b);
            }
            if (!Arrays.equals(a.confirmedPixels(), b.confirmedPixels())) {
                fail(who + ": confirmed pixels differ");
            }
        }

        // -- The end: a quiesce, then property 2

        private void quiesce() {
            for (Lane lane : lanes) {
                if (lane.gl()) {
                    int passes = 0;
                    while (!lane.core.idle(KEY) && passes < 64) {
                        lane.core.pass(PassBudget.unlimited());
                        passes++;
                    }
                    Assertions.assertTrue(lane.core.idle(KEY), lane + " not idle after 64 passes: " + lane.stats());
                    Assertions.assertTrue(lane.failures.isEmpty(), lane + ": " + lane.failures);
                    Assertions.assertEquals(0, lane.core.contractViolations(), lane + "'s contract violations");
                } else {
                    lane.fake.step();
                }
                DeviceSnapshot s = lane.device.snapshot();
                String who = "seed " + seed + ", " + lane + " after the quiesce: " + s;
                Assertions.assertTrue(s.tail.isEmpty(), who);
                Assertions.assertEquals(0, s.unconfirmedFrames, who);
                if (lane.presentedAfterReset) {
                    Assertions.assertEquals(s.lastFrameId, s.confirmedFrameId, who + ": K is the last presented frame");
                }
                TierEOracle replay = lane.gl() ? lane.shadow.oracle(KEY) : lane.renderer.oracle(KEY);
                byte[] expected = replay.pixels();
                String difference = ShadowOracle.firstDifference(who + ", K", surface, expected, s.confirmedPixels());
                Assertions.assertNull(difference, difference);
                difference = ShadowOracle.firstDifference(who + ", readPixels", surface, expected, screen(lane));
                Assertions.assertNull(difference, difference);
                Assertions.assertEquals(replay.textureCount(), s.masters.size(), who + ": masters");
                for (MasterTexture m : s.masters.values()) {
                    byte[] texels = m.texels();
                    int mbpp = Formats.bytesPerPixel(m.format);
                    for (int y = 0; y < m.height; y++) {
                        for (int x = 0; x < m.width; x++) {
                            int i = (y * m.width + x) * mbpp;
                            int value = mbpp == 1 ? texels[i] & 0xFF : (texels[i] & 0xFF) | (texels[i + 1] & 0xFF) << 8;
                            if (replay.texel(m.handle, x, y) != value) {
                                fail(who + ": master " + m + " differs at (" + x + ", " + y + ")");
                            }
                        }
                    }
                }
                if (lane.gl()) {
                    DeviceStats stats = lane.stats();
                    Assertions.assertEquals(s.vramUsed + s.deferredBytes, stats.gpuTextureBytes, who + ": G10");
                    gpuEqualsTheOracle(who, lane);
                }
            }
        }

        /** The confirmed image through {@code readPixels}, in bands of whole rows within the readback cap. */
        private byte[] screen(Lane lane) {
            int row = surface.width * bpp;
            int rows = Math.max(1, caps.maxReadbackBytes / row);
            byte[] out = new byte[surface.byteSize()];
            for (int y = 0; y < surface.height; y += rows) {
                final int y0 = y;
                final int h = Math.min(rows, surface.height - y);
                CallResult band = lane.call(b -> lane.device.readPixels(b, 0, y0, surface.width, h));
                Assertions.assertTrue(band.isOk(), String.valueOf(band));
                System.arraycopy(band.data(), 0, out, y * row, band.data().length);
            }
            return out;
        }

        // -- Generation, from the mirrors-on lane's device

        private Op next() {
            int roll = r.nextInt(100);
            if (roll < 13) {
                return create();
            }
            if (roll < 27) {
                return update();
            }
            if (roll < 35) {
                return free();
            }
            if (roll < 53) {
                return submit();
            }
            if (roll < 65) {
                return new Op(Kind.PRESENT);
            }
            if (roll < 68) {
                return readPixels();
            }
            if (roll < 86) {
                Op op = new Op(Kind.STEP);
                op.count = r.nextInt(10) < 7 ? r.nextInt(1, 4) : 0;
                return op;
            }
            if (roll < 89) {
                Op op = new Op(Kind.STALL);
                op.count = r.nextInt(1, 41);
                return op;
            }
            if (roll < 94) {
                Op op = new Op(Kind.TICK);
                op.count = r.nextInt(701);
                return op;
            }
            if (roll < 96) {
                return new Op(Kind.RESET);
            }
            if (roll < 97) {
                coverage.add("host: release and reopen");
                return new Op(Kind.REOPEN);
            }
            return churn();
        }

        private Op create() {
            Op op = new Op(Kind.CREATE);
            int roll = r.nextInt(100);
            int max = caps.maxTextureSize;
            if (roll < 70) {
                op.w = r.nextInt(1, 9);
                op.h = r.nextInt(1, 9);
            } else if (roll < 80) {
                long left = Math.max(1, (caps.vramBytes - liveBytes()) / bpp);
                switch (r.nextInt(4)) {
                    case 0:
                        op.w = max;
                        op.h = r.nextInt(1, 3);
                        break;
                    case 1:
                        op.w = r.nextInt(1, 3);
                        op.h = max;
                        break;
                    case 2:
                        // what is left of the VRAM, in rows of up to the side cap
                        op.w = (int) Math.min(max, left);
                        op.h = (int) Math.max(1, Math.min(max, left / op.w));
                        break;
                    default:
                        op.w = (int) Math.min(max, left);
                        op.h = (int) Math.max(1, Math.min(max, left / op.w)) + 1;
                }
            } else if (roll < 82) {
                op.w = max;
                op.h = max;
            } else if (roll < 92) {
                int[] bad = { 0, -1, max + 1, Integer.MIN_VALUE };
                op.w = r.nextBoolean() ? bad[r.nextInt(bad.length)] : r.nextInt(1, 5);
                op.h = bad[r.nextInt(bad.length)];
            } else {
                op.w = r.nextInt(1, 5);
                op.h = r.nextInt(1, 5);
                String[] formats = { "index8", "rgb565", "bogus", Formats.name(surface.format) };
                op.format = formats[r.nextInt(formats.length)];
            }
            return op;
        }

        private Op update() {
            Op op = new Op(Kind.UPDATE);
            if (live.isEmpty() || r.nextInt(10) == 0) {
                op.tex = garbageHandle();
                op.x = 0;
                op.y = 0;
                op.w = r.nextInt(1, 4);
                op.h = r.nextInt(1, 4);
                op.data = randomBytes(op.w * op.h * bpp);
                return op;
            }
            int[] t = pickLive();
            int tw = t[1];
            int th = t[2];
            op.tex = t[0];
            int roll = r.nextInt(100);
            if (roll < 35) {
                op.w = tw;
                op.h = th;
            } else if (roll < 85) {
                op.w = r.nextInt(1, tw + 1);
                op.h = r.nextInt(1, th + 1);
                switch (r.nextInt(5)) {
                    case 0:
                        break;
                    case 1:
                        op.x = tw - op.w;
                        break;
                    case 2:
                        op.y = th - op.h;
                        break;
                    case 3:
                        op.x = tw - op.w;
                        op.y = th - op.h;
                        break;
                    default:
                        op.x = r.nextInt(tw - op.w + 1);
                        op.y = r.nextInt(th - op.h + 1);
                }
            } else {
                // outside the texture, or empty
                op.w = r.nextInt(0, tw + 2);
                op.h = r.nextInt(1, th + 1);
                op.x = r.nextInt(0, tw + 1);
                op.y = r.nextInt(0, 2);
            }
            int length = Math.max(0, op.w * op.h * bpp);
            if (r.nextInt(20) == 0) {
                length += r.nextBoolean() ? 1 : -1;
            }
            op.data = randomBytes(Math.max(0, length));
            return op;
        }

        private Op free() {
            Op op = new Op(Kind.FREE);
            if (live.isEmpty() || r.nextInt(8) == 0) {
                op.tex = garbageHandle();
            } else {
                op.tex = pickLive()[0];
                freed.add(op.tex);
            }
            return op;
        }

        private int garbageHandle() {
            if (!freed.isEmpty() && r.nextBoolean()) {
                return freed.get(r.nextInt(freed.size()));
            }
            int[] bad = { 0, 0x7FFFFFFF, 0x08000000 | r.nextInt(0x10000), 0x10000000, -1 };
            return bad[r.nextInt(bad.length)];
        }

        private Op submit() {
            List<byte[]> records = new ArrayList<>();
            StringBuilder note = new StringBuilder();
            int n = r.nextInt(1, 7);
            int width = surface.width;
            int height = surface.height;
            for (int k = 0; k < n; k++) {
                int pick = r.nextInt(100);
                if (pick < (caps.vramBytes > 4096 ? 3 : 10)) {
                    records.add(clear(colour()));
                    note.append("CLEAR ");
                } else if (pick < 45) {
                    records.add(
                        fill(
                            r.nextInt(-4, width + 2),
                            r.nextInt(-4, height + 2),
                            r.nextInt(0, width / 2 + 4),
                            r.nextInt(0, height / 2 + 4),
                            colour()));
                    note.append("FILL_RECT ");
                } else if (pick < 80 && !live.isEmpty()) {
                    int[] t = pickLive();
                    int tw = Math.min(t[1], 64);
                    int th = Math.min(t[2], 64);
                    records.add(
                        blit(
                            t[0],
                            r.nextInt(-2, tw + 2),
                            r.nextInt(-2, th + 2),
                            r.nextInt(0, tw + 4),
                            r.nextInt(0, th + 4),
                            r.nextInt(-4, width + 1),
                            r.nextInt(-4, height + 1),
                            r.nextInt(2)));
                    note.append("BLIT ");
                } else {
                    String[] pieces = { "A", "b", "Z", "0", " ", "~", "é", "€", "中" };
                    StringBuilder text = new StringBuilder();
                    int chars = r.nextInt(0, 6);
                    for (int c = 0; c < chars; c++) {
                        text.append(pieces[r.nextInt(pieces.length)]);
                    }
                    records.add(
                        text(
                            r.nextInt(-6, width + 1),
                            r.nextInt(-12, height + 1),
                            colour(),
                            colour(),
                            r.nextInt(2),
                            0,
                            text.toString()));
                    note.append("TEXT ");
                }
            }
            byte[] segment = segment(records.toArray(new byte[0][]));
            if (r.nextInt(20) == 0) {
                segment = head(segment, segment.length - 1);
                note.append("(cut)");
            }
            Op op = new Op(Kind.SUBMIT);
            op.data = segment;
            op.note = note.toString()
                .trim();
            return op;
        }

        private Op readPixels() {
            Op op = new Op(Kind.READ_PIXELS);
            int width = surface.width;
            int height = surface.height;
            int roll = r.nextInt(10);
            if (roll < 7) {
                op.w = r.nextInt(1, Math.min(width, 8) + 1);
                op.h = r.nextInt(1, Math.min(height, 4) + 1);
                op.x = r.nextInt(width - op.w + 1);
                op.y = r.nextInt(height - op.h + 1);
            } else if (roll < 8) {
                op.w = width;
                op.h = height;
            } else {
                op.x = r.nextInt(-1, width + 1);
                op.y = r.nextInt(-1, height + 1);
                op.w = r.nextInt(0, width + 2);
                op.h = r.nextInt(0, 3);
            }
            return op;
        }

        private Op churn() {
            Op op = new Op(Kind.CHURN);
            op.data = segment(fill(0, 0, 1, 1, colour()));
            int pairs = r.nextInt(1, 21);
            op.pairs = new int[2 * pairs];
            for (int i = 0; i < op.pairs.length; i++) {
                op.pairs[i] = r.nextInt(1, 3);
            }
            return op;
        }

        private int colour() {
            return surface.format == Formats.INDEX8 ? r.nextInt(256) : 0xFF000000 | r.nextInt(1 << 24);
        }

        private byte[] randomBytes(int n) {
            byte[] out = new byte[n];
            long bits = 0;
            for (int i = 0; i < n; i++) {
                if ((i & 7) == 0) {
                    bits = r.nextLong();
                }
                out[i] = (byte) bits;
                bits >>>= 8;
            }
            return out;
        }

        // -- Bookkeeping

        /** The live textures after the op, from the mirrors-on lane's results. */
        private void track(Op op, Object result) {
            switch (op.kind) {
                case CREATE:
                    if (((CallResult) result).isOk()) {
                        live.put(((CallResult) result).handle(), new int[] { op.w, op.h });
                    }
                    return;
                case FREE:
                    if (((CallResult) result).isOk()) {
                        live.remove(op.tex);
                    }
                    return;
                case RESET:
                case REOPEN:
                    live.clear();
                    return;
                default:
                    return;
            }
        }

        private int liveBytes() {
            int bytes = 0;
            for (int[] size : live.values()) {
                bytes += size[0] * size[1] * bpp;
            }
            return bytes;
        }

        /** A live texture of the mirrors-on device: {handle, w, h}. */
        private int[] pickLive() {
            List<Integer> handles = new ArrayList<>(live.keySet());
            int handle = handles.get(r.nextInt(handles.size()));
            int[] size = live.get(handle);
            return new int[] { handle, size[0], size[1] };
        }

        private void note(Op op, Object result) {
            if (result instanceof CallResult) {
                CallResult c = (CallResult) result;
                String name = NAMES.get(op.kind);
                String outcome = c.isOk() ? "ok" : c.streamError() != null ? "rejected" : c.message();
                if (outcome.startsWith("busy") || outcome.equals("ok") || outcome.equals("rejected")) {
                    coverage.add(name + ": " + outcome);
                }
            }
            for (String event : on.listener.events()) {
                coverage.add("listener: " + event.substring(0, event.indexOf(' ')));
            }
        }

        private void remember(String op) {
            recent.addLast(index + ": " + op);
            while (recent.size() > 12) {
                recent.pollFirst();
            }
        }

        private void fail(String what) {
            throw new AssertionError(
                "seed " + seed
                    + (lockstep ? " lockstep" : " budgeted")
                    + ", "
                    + part.name
                    + ", op "
                    + recent.peekLast()
                    + ": "
                    + what
                    + "\n  recent ops: "
                    + recent);
        }
    }
}
