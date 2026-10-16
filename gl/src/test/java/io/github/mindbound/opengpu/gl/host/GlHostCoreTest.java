package io.github.mindbound.opengpu.gl.host;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.device.CallResult;
import io.github.mindbound.opengpu.core.device.Device;
import io.github.mindbound.opengpu.core.device.DeviceCaps;
import io.github.mindbound.opengpu.core.device.DeviceSnapshot;
import io.github.mindbound.opengpu.core.device.FrameListener;
import io.github.mindbound.opengpu.core.device.MasterTexture;
import io.github.mindbound.opengpu.core.device.Statuses;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.ManualClock;
import io.github.mindbound.opengpu.core.fixtures.device.OracleRecordRenderer;
import io.github.mindbound.opengpu.core.fixtures.device.RecordingBudget;
import io.github.mindbound.opengpu.core.fixtures.device.ShadowOracle;
import io.github.mindbound.opengpu.core.fixtures.device.TeeRenderHost;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.ReadbackSink;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.ResetRecord;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexFreeRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;

/**
 * {@link GlHostCore} on {@link OracleBackend} (S8 plan §4.1 with amendments B, C and D): the contract counts (H47),
 * {@code RESET}'s drop (H38), release and reopen (H46), sessions and displays, the heartbeat (H32), round robin
 * (24:173), the readback ring's rules (H36, H37, 21:87), delivery outside every lock (28:1072), I-H1, the idle early
 * exit (A4), the failure path (H63), the side limit (H64), the pending rules of plan §3.10, the GL thread (H57),
 * {@code close}, mirrors off, {@code readNow} and the device on the host. Expected surfaces come from a fresh oracle
 * running the offered records in order (28:126), never from the backend under test.
 */
class GlHostCoreTest {

    private static final SurfaceDesc INDEX8 = new SurfaceDesc(0, 8, 6, Formats.INDEX8);
    private static final SurfaceDesc RGB565 = new SurfaceDesc(0, 5, 4, Formats.RGB565);
    private static final DeviceKey A = new DeviceKey(1, 1);
    private static final DeviceKey B = new DeviceKey(1, 2);
    private static final DeviceKey C = new DeviceKey(1, 3);
    private static final DeviceKey OTHER = new DeviceKey(2, 1);
    /** Texture handles: type 1, slots 0-2, generation 0 (28 §6.1). */
    private static final int TEX = 0x08000000, TEX2 = 0x08010000, TEX3 = 0x08020000;

    /** A sink that records "e<epoch> #<frame>" and a copy of each readback's pixels. Thread-safe. */
    static final class Sink implements ReadbackSink {

        final List<String> frames = Collections.synchronizedList(new ArrayList<String>());
        final List<byte[]> pixels = Collections.synchronizedList(new ArrayList<byte[]>());

        @Override
        public void onReadback(long session, int deviceId, long epoch, long frameId, byte[] p) {
            frames.add("e" + epoch + " #" + frameId);
            pixels.add(p.clone());
        }

        byte[] last() {
            return pixels.get(pixels.size() - 1);
        }
    }

    private final List<String> logs = Collections.synchronizedList(new ArrayList<String>());

    private HostOptions options() {
        return HostOptions.defaults()
            .expand(HostOptions.Expand.ALWAYS)
            .stallAfterNanos(Long.MAX_VALUE)
            .log(logs::add);
    }

    private GlHostCore host(OracleBackend backend) {
        return new GlHostCore(backend, options());
    }

    // ---- H47: what breaks the contract is counted, and nothing throws

    @Test
    void offersThatBreakTheContractAreCountedAndNeverThrow() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = host(backend);
        Sink sink = new Sink();
        core.offer(A, frame(1, 1));
        Assertions.assertEquals(1, core.contractViolations(), "an offer to a key never opened");
        core.offer(null, frame(1, 1));
        core.offer(A, null);
        core.open(null, INDEX8, sink);
        core.open(A, null, sink);
        core.open(A, INDEX8, null);
        Assertions.assertEquals(6, core.contractViolations(), "null arguments");
        Assertions.assertNull(core.stats(A), "no opening was made");
        core.open(A, INDEX8, sink);
        core.offer(A, frame(1, 1));
        Assertions.assertEquals(6, core.contractViolations());
        // Records that break the device contract (mirrors on, applied at once on the empty queue): counted, dropped
        core.offer(A, update(1, TEX, 0, 0, 1, 1, new byte[1]));
        core.offer(A, new TexFreeRecord(1, TEX));
        core.offer(A, new TexCreateRecord(1, TEX, 2, 2, Formats.RGB565));
        Assertions.assertEquals(6, core.contractViolations(), "they queue behind the FRAME, unapplied yet");
        pumpUntilIdle(core, A);
        Assertions.assertEquals(9, core.contractViolations(), "applied behind the frame: update, free, wrong format");
        core.offer(A, create(1, TEX, 2, 2));
        core.offer(A, create(1, TEX, 2, 2));
        core.offer(A, update(1, TEX, 1, 1, 2, 2, new byte[4]));
        core.offer(A, new ResetRecord(2, new byte[3]));
        Assertions.assertEquals(12, core.contractViolations(), "a second create, a region outside, a short RESET");
        core.release(A);
        core.offer(A, frame(1, 2));
        Assertions.assertEquals(13, core.contractViolations(), "an offer after release");
        Assertions.assertDoesNotThrow(() -> {
            core.release(null);
            core.release(B);
            core.bindDisplay(null, A);
            core.bindDisplay("d", null);
            core.releaseSession(99);
        });
        Assertions.assertEquals(13, core.contractViolations());
        Assertions.assertNull(core.failure());
    }

    @Test
    void aSecondOpenIsCountedAndTheFirstOpeningStays() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = host(backend);
        Sink first = new Sink();
        Sink second = new Sink();
        core.open(A, INDEX8, first);
        core.open(A, RGB565, second);
        Assertions.assertEquals(1, core.contractViolations());
        Assertions.assertEquals(INDEX8, core.stats(A).surface);
        core.offer(A, frame(1, 1, segment(fill(0, 0, 2, 2, 7))));
        pumpUntilIdle(core, A);
        Assertions.assertEquals(Arrays.asList("e1 #1"), first.frames);
        Assertions.assertTrue(second.frames.isEmpty());
    }

    // ---- H38: RESET drops what is queued; the result is the reference's

    @Test
    void resetDropsTheQueuedOlderRecordsAndTheResultEqualsTheReference() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = host(backend);
        Sink sink = new Sink();
        core.open(A, INDEX8, sink);
        // TEX reaches the GPU first, so the RESET must delete it there (28:600)
        core.offer(A, create(1, TEX, 2, 2));
        core.offer(A, update(1, TEX, 0, 0, 2, 2, bytes(1, 2, 3, 4)));
        pumpUntilIdle(core, A);
        Assertions.assertEquals(
            Collections.singleton(TEX),
            backend.device(A)
                .handles());
        // Behind no pass: four records queue (frames and the update behind them), then the RESET drops them all
        core.offer(A, frame(1, 1, segment(fill(0, 0, 8, 6, 9))));
        core.offer(A, frame(1, 2, segment(blit(TEX, 0, 0, 2, 2, 1, 1, 0))));
        core.offer(A, update(1, TEX, 0, 0, 1, 1, bytes(5)));
        core.offer(A, frame(1, 3, segment(fill(2, 2, 2, 2, 4))));
        Assertions.assertEquals(4, core.stats(A).queuedRecords);
        byte[] k = pattern(INDEX8, 3);
        core.offer(A, new ResetRecord(2, k));
        DeviceStats s = core.stats(A);
        Assertions.assertEquals(4, s.recordsDropped);
        Assertions.assertEquals(0, s.queuedRecords);
        Assertions.assertEquals(0, s.queuedBytes);
        Assertions.assertEquals(0, s.mirrorTextures);
        Assertions.assertEquals(0, s.pendingTextures);
        Assertions.assertTrue(s.pendingReset);
        // Epoch 2 draws on K's pixels with every older texture gone
        List<Record> epoch2 = Arrays.<Record>asList(
            create(2, TEX2, 3, 1),
            update(2, TEX2, 0, 0, 3, 1, bytes(0, 6, 7)),
            frame(2, 4, segment(blit(TEX2, 0, 0, 3, 1, 4, 5, 1), fill(0, 0, 1, 6, 8))));
        for (Record r : epoch2) {
            core.offer(A, r);
        }
        pumpUntilIdle(core, A);
        List<Record> expected = new ArrayList<>();
        expected.add(new ResetRecord(2, k));
        expected.addAll(epoch2);
        Assertions.assertEquals(Arrays.asList("e2 #4"), sink.frames, "no frame of epoch 1 ran or was read back");
        assertPixels(INDEX8, replay(INDEX8, expected), sink.last());
        Assertions.assertEquals(1, backend.device(A).resets);
        Assertions.assertEquals(1, backend.device(A).frames, "only frame 4 ran");
        Assertions.assertEquals(
            Collections.singleton(TEX2),
            backend.device(A)
                .handles());
        Assertions.assertEquals(0, core.contractViolations());
    }

    // ---- 21:106; H46: release, and a reopen before a pass

    @Test
    void releaseDropsTheQueueAndDiscardsReadbacksInFlight() {
        OracleBackend backend = new OracleBackend();
        backend.latency = 3;
        GlHostCore core = host(backend);
        Sink sink = new Sink();
        core.open(A, INDEX8, sink);
        core.offer(A, frame(1, 1, segment(fill(0, 0, 1, 1, 1))));
        core.pass(PassBudget.unlimited());
        Assertions.assertEquals(1, core.stats(A).readbacksInFlight);
        core.offer(A, frame(1, 2));
        core.release(A);
        DeviceStats s = core.stats(A);
        Assertions.assertTrue(s.released);
        Assertions.assertEquals(1, s.recordsDropped);
        Assertions.assertEquals(0, s.queuedRecords);
        Assertions.assertFalse(core.idle(A), "its objects await step (d)");
        for (int i = 0; i < 6; i++) {
            core.pass(PassBudget.unlimited());
        }
        Assertions.assertTrue(core.idle(A));
        Assertions.assertTrue(backend.device(A).destroyed);
        Assertions.assertTrue(sink.frames.isEmpty(), "a released device's readbacks are discarded");
        for (String call : backend.calls()) {
            Assertions.assertFalse(call.startsWith("fetch"), call);
        }
    }

    @Test
    void openReleaseAndOpenAgainBeforeAPassDestroysTheOldOpeningFirstAndTheNewStartsCleared() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = host(backend);
        Sink old = new Sink();
        core.open(A, INDEX8, old);
        core.offer(A, frame(1, 1, segment(clear(5))));
        pumpUntilIdle(core, A);
        Assertions.assertEquals(Arrays.asList("e1 #1"), old.frames);
        backend.clearCalls();
        core.release(A);
        Sink fresh = new Sink();
        core.open(A, INDEX8, fresh);
        Record f = frame(3, 1, segment(fill(1, 1, 2, 2, 6)));
        core.offer(A, f);
        pumpUntilIdle(core, A);
        List<String> calls = backend.calls();
        int destroy = calls.indexOf("destroy " + A);
        int open = calls.indexOf("open " + A);
        Assertions.assertTrue(destroy >= 0 && open > destroy, "destroy before open in one pass: " + calls);
        Assertions.assertEquals(Arrays.asList("e3 #1"), fresh.frames);
        assertPixels(INDEX8, replay(INDEX8, Collections.singletonList(f)), fresh.last());
        Assertions.assertEquals(1, old.frames.size());
        Assertions.assertEquals(
            2,
            backend.devices(A)
                .size());
        Assertions.assertEquals(0, core.contractViolations());
    }

    @Test
    void releaseSessionReleasesEveryKeyOfTheSessionAndUnbindsItsDisplays() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = host(backend);
        core.open(A, INDEX8, new Sink());
        core.open(B, RGB565, new Sink());
        Sink other = new Sink();
        core.open(OTHER, INDEX8, other);
        core.bindDisplay("a", A);
        core.bindDisplay("b", B);
        core.bindDisplay("o", OTHER);
        pumpUntilIdle(core, A, B, OTHER);
        core.releaseSession(1);
        Assertions.assertNull(core.boundDevice("a"));
        Assertions.assertNull(core.boundDevice("b"));
        Assertions.assertEquals(OTHER, core.boundDevice("o"));
        Assertions.assertNull(core.stats(A), "a released session's stats go with it");
        Assertions.assertNull(core.stats(B));
        core.pass(PassBudget.unlimited());
        Assertions.assertTrue(backend.device(A).destroyed);
        Assertions.assertTrue(backend.device(B).destroyed);
        Assertions.assertFalse(backend.device(OTHER).destroyed);
        core.offer(A, frame(1, 1));
        Assertions.assertEquals(1, core.contractViolations());
        core.offer(OTHER, frame(1, 1, segment(clear(2))));
        pumpUntilIdle(core, OTHER);
        Assertions.assertEquals(Arrays.asList("e1 #1"), other.frames);
    }

    // ---- 28:151, 28:1080: a released opening keeps its stats, never its device

    /**
     * M0's root opens a fresh key for every new device, a chunk reload or a card move (28:1080), and the host keeps
     * each released opening for {@link GlHostCore#stats} until its session is released: what it keeps must not hold
     * the device, the opening's sink, with K and the readback ring's arrays. Sixty devices are opened, confirmed,
     * released and passed; none may stay reachable while every key's stats still answer.
     */
    @Test
    @Timeout(60)
    void aReleasedDeviceIsNotKeptByTheHostWhileItsStatsStillAnswer() throws Exception {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = host(backend);
        DeviceCaps caps = caps(new SurfaceDesc(0, 160, 100, Formats.RGB565));
        List<DeviceKey> keys = new ArrayList<>();
        List<WeakReference<Device>> devices = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            DeviceKey key = new DeviceKey(5, i + 1);
            keys.add(key);
            devices.add(new WeakReference<>(openConfirmAndRelease(core, backend, key, caps)));
        }
        int reachable = awaitCleared(devices);
        Assertions.assertEquals(0, reachable, reachable + " of 60 released devices are still reachable");
        for (DeviceKey key : keys) {
            DeviceStats s = core.stats(key);
            Assertions.assertNotNull(s, key + "'s stats are gone before its session");
            Assertions.assertTrue(s.released, s.toString());
            Assertions.assertFalse(s.opened, s.toString());
            Assertions.assertEquals(1, s.readbacksDelivered, s.toString());
        }
        Assertions.assertEquals(0, core.contractViolations());
        core.releaseSession(5);
        Assertions.assertNull(core.stats(keys.get(0)));
    }

    /**
     * What a released opening keeps once step (d) has run is a shell of counters: no sink, no backend device, and not
     * the queue and maps whose arrays a thousand queued frames and its textures grew.
     */
    @Test
    @Timeout(60)
    void aReleasedOpeningKeepsNoSinkBackendOrGrownCollection() throws Exception {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = host(backend);
        Sink sink = new Sink();
        WeakReference<Sink> sinkRef = new WeakReference<>(sink);
        core.open(A, INDEX8, sink);
        core.offer(A, create(1, TEX, 2, 2));
        core.offer(A, update(1, TEX, 0, 0, 2, 2, bytes(1, 2, 3, 4)));
        core.offer(A, create(1, TEX2, 1, 1));
        core.offer(A, frame(1, 1, segment(clear(1))));
        pumpUntilIdle(core, A);
        Assertions.assertEquals(Arrays.asList("e1 #1"), sink.frames);
        sink = null;
        for (long id = 2; id <= 1001; id++) {
            core.offer(A, frame(1, id));
        }
        HostDevice hd = core.device(A);
        List<WeakReference<Object>> grown = new ArrayList<>();
        synchronized (hd) {
            Assertions.assertEquals(1000, hd.queue.size());
            Assertions.assertEquals(2, hd.gpu.size());
            grown.add(new WeakReference<Object>(hd.queue));
            grown.add(new WeakReference<Object>(hd.mirrors));
            grown.add(new WeakReference<Object>(hd.pending));
            grown.add(new WeakReference<Object>(hd.gpu));
        }
        core.release(A);
        core.pass(PassBudget.unlimited());
        Assertions.assertTrue(backend.device(A).destroyed, "step (d) ran");
        int reachable = awaitCleared(grown);
        Assertions
            .assertEquals(0, reachable, reachable + " of the queue, mirrors, pending and GPU maps are still kept");
        Assertions.assertNull(sinkRef.get(), "the sink is still kept");
        synchronized (hd) {
            Assertions.assertNull(hd.backend, "the backend device is still kept");
        }
        DeviceStats s = core.stats(A);
        Assertions.assertTrue(s.released);
        Assertions.assertEquals(1000, s.recordsDropped);
        Assertions.assertEquals(0, s.queuedRecords);
        Assertions.assertEquals(0, s.gpuTextures);
        Assertions.assertEquals(1, s.readbacksDelivered);
    }

    /**
     * A pass keeps no unit it ran: a {@code RESET}'s pixels taken as the last unit a budget allows are not held until
     * the next pass with work, which on an idle host may never come.
     */
    @Test
    @Timeout(60)
    void aPassKeepsNoRecordItRan() throws Exception {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = host(backend);
        core.open(A, INDEX8, new Sink());
        pumpUntilIdle(core, A);
        ResetRecord reset = new ResetRecord(2, pattern(INDEX8, 5));
        List<WeakReference<Object>> ran = new ArrayList<>();
        ran.add(new WeakReference<Object>(reset));
        ran.add(new WeakReference<Object>(reset.pixels()));
        core.offer(A, reset);
        reset = null;
        core.pass(PassBudget.units(1));
        Assertions.assertEquals(1, backend.device(A).resets, "the RESET ran as the pass's one unit");
        int reachable = awaitCleared(ran);
        Assertions.assertEquals(0, reachable, "the RESET or its pixels are still kept after its pass");
    }

    /**
     * The sink is taken with the readback under the opening's lock, so a release racing the delivery, which lets the
     * sink go, never makes the call fail: no sink "throws", no violation is counted, and the host never fails. A thread
     * releases each key after a random delay that spans the pass delivering its readback.
     */
    @Test
    @Timeout(60)
    void aReleaseRacingTheDeliveryOfItsReadbackNeverBreaksTheCall() throws Exception {
        final OracleBackend backend = new OracleBackend();
        final GlHostCore core = host(backend);
        final AtomicReference<DeviceKey> toRelease = new AtomicReference<>();
        final AtomicInteger released = new AtomicInteger();
        final AtomicInteger delivered = new AtomicInteger();
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        ReadbackSink counting = (session, deviceId, epoch, frameId, pixels) -> delivered.incrementAndGet();
        Thread releaser = new Thread(() -> {
            try {
                SplittableRandom r = new SplittableRandom(23);
                while (!stop.get()) {
                    DeviceKey key = toRelease.getAndSet(null);
                    if (key == null) {
                        continue;
                    }
                    long until = System.nanoTime() + r.nextInt(30_000);
                    while (System.nanoTime() < until && !stop.get()) {
                        // a random delay of up to 30 µs, across the pass that delivers the readback
                    }
                    core.release(key);
                    released.incrementAndGet();
                }
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, "releaser");
        releaser.setDaemon(true);
        releaser.start();
        int rounds = 3000;
        try {
            for (int i = 0; i < rounds; i++) {
                DeviceKey key = new DeviceKey(6, i + 1);
                core.open(key, INDEX8, counting);
                core.offer(key, frame(1, 1, segment(clear(i & 0xFF))));
                // Its objects, its frame and its readback's issue; the next pass fetches and delivers it
                core.pass(PassBudget.unlimited());
                toRelease.set(key);
                core.pass(PassBudget.unlimited());
                for (int wait = 0; wait < 10_000_000 && released.get() <= i && thrown.get() == null; wait++) {
                    Thread.yield();
                }
                Assertions.assertNull(thrown.get(), String.valueOf(thrown.get()));
                Assertions.assertEquals(i + 1, released.get(), "the releaser did not release round " + i);
                // Step (d)
                core.pass(PassBudget.unlimited());
            }
        } finally {
            stop.set(true);
            releaser.join(10_000);
        }
        Assertions.assertFalse(releaser.isAlive(), "the releaser still runs 10 s after the stop");
        Assertions.assertNull(core.failure());
        Assertions.assertEquals(0, core.contractViolations(), logs.toString());
        for (String line : logs) {
            Assertions.assertFalse(line.contains("sink threw"), line);
        }
        System.out.println(
            "GlHostCoreTest release race: " + delivered.get()
                + " of "
                + rounds
                + " readbacks delivered, the rest dropped");
    }

    // ---- Displays, presentDevice and expand (H27, H68)

    @Test
    void bindDisplayAndPresentDeviceFollowTheBindingAndBoundOnlyExpandWaitsForADisplay() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = new GlHostCore(backend, options().expand(HostOptions.Expand.BOUND_ONLY));
        core.open(A, INDEX8, new Sink());
        core.open(B, INDEX8, new Sink());
        Assertions.assertNull(core.presentDevice("x"), "no display bound");
        core.bindDisplay("x", A);
        Assertions.assertNull(core.presentDevice("x"), "A has no GL objects before its first turn");
        pumpUntilIdle(core, A, B);
        Assertions.assertSame(backend.device(A), core.presentDevice("x"));
        Assertions.assertSame(backend.device(A), core.deviceBackend(A));
        core.bindDisplay("x", B);
        Assertions.assertSame(backend.device(B), core.presentDevice("x"));
        Assertions.assertEquals(B, core.boundDevice("x"));
        core.bindDisplay("x", null);
        Assertions.assertNull(core.presentDevice("x"));
        Assertions.assertNull(core.boundDevice("x"));
        // BOUND_ONLY: A's frame owes an expand that waits, without work, until a display shows A
        core.offer(A, frame(1, 1, segment(clear(1))));
        pumpUntilIdle(core, A);
        Assertions.assertEquals(0, backend.device(A).expands);
        Assertions.assertTrue(core.stats(A).expandOwed);
        backend.clearCalls();
        core.pass(PassBudget.unlimited());
        Assertions.assertTrue(
            backend.calls()
                .isEmpty(),
            "an expand nobody shows is no work");
        core.bindDisplay("y", A);
        Assertions.assertFalse(core.idle(A));
        core.pass(PassBudget.unlimited());
        Assertions.assertEquals(1, backend.device(A).expands);
        Assertions.assertFalse(core.stats(A).expandOwed);
        Assertions.assertTrue(core.idle(A));
    }

    @Test
    void expandAlwaysFollowsEveryTurnThatChangedTheSurfaceAndNeverDoesNot() {
        OracleBackend always = new OracleBackend();
        GlHostCore core = host(always);
        core.open(A, INDEX8, new Sink());
        pumpUntilIdle(core, A);
        Assertions.assertEquals(0, always.device(A).expands, "the backend's open expands once itself");
        core.offer(A, frame(1, 1));
        core.offer(A, frame(1, 2));
        pumpUntilIdle(core, A);
        Assertions.assertEquals(1, always.device(A).expands, "one expand for the turn, after both frames");
        core.offer(A, new ResetRecord(2, new byte[INDEX8.byteSize()]));
        pumpUntilIdle(core, A);
        Assertions.assertEquals(2, always.device(A).expands, "a RESET changes the surface too");

        OracleBackend never = new OracleBackend();
        GlHostCore none = new GlHostCore(never, options().expand(HostOptions.Expand.NEVER));
        none.open(A, INDEX8, new Sink());
        none.bindDisplay("x", A);
        none.offer(A, frame(1, 1));
        pumpUntilIdle(none, A);
        Assertions.assertEquals(0, never.device(A).expands);
    }

    // ---- H32: the heartbeat

    @Test
    void stalledFollowsTheHeartbeatOnAManualClock() {
        ManualClock clock = new ManualClock();
        OracleBackend backend = new OracleBackend();
        GlHostCore core = new GlHostCore(
            backend,
            HostOptions.defaults()
                .clock(clock)
                .log(logs::add));
        Assertions.assertEquals(
            GlHostCore.STALL_AFTER_NANOS,
            core.options()
                .stallAfterNanos());
        Assertions.assertFalse(core.stalled(), "the heartbeat starts at construction");
        clock.advanceMillis(1999);
        Assertions.assertFalse(core.stalled(), "1 999 ms");
        clock.advanceMillis(1);
        Assertions.assertFalse(core.stalled(), "2 000 ms is not more than 2 s");
        clock.advanceMillis(1);
        Assertions.assertTrue(core.stalled(), "2 001 ms");
        core.pass(PassBudget.unlimited());
        Assertions.assertFalse(core.stalled(), "a pass, even an idle one, moves the heartbeat");
        Assertions.assertTrue(
            backend.calls()
                .isEmpty());
        clock.advanceMillis(2001);
        Assertions.assertTrue(core.stalled());
        GlHostCore never = new GlHostCore(
            backend,
            HostOptions.defaults()
                .clock(clock)
                .stallAfterNanos(Long.MAX_VALUE));
        clock.advanceMillis(1_000_000_000L);
        Assertions.assertFalse(never.stalled());
    }

    @Test
    void setAvailableIsWhatAvailableAnswersAndADeviceThenHasNoRenderer() {
        GlHostCore core = host(new OracleBackend());
        Assertions.assertTrue(core.available());
        core.setAvailable(false);
        Assertions.assertFalse(core.available());
        DeviceRig rig = DeviceRig.on(caps(INDEX8), core, () -> core.pass(PassBudget.unlimited()));
        CallResult r = rig.call(b -> rig.device.present(b));
        Assertions.assertEquals("no renderer", r.message());
        core.setAvailable(true);
        Assertions.assertTrue(core.available());
        Assertions.assertTrue(
            rig.call(b -> rig.device.present(b))
                .isOk());
    }

    // ---- 24:173: round robin

    @Test
    void unitsOfOneAlternateBetweenThreeDevices() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = host(backend);
        for (DeviceKey key : Arrays.asList(A, B, C)) {
            core.open(key, INDEX8, new Sink());
        }
        pumpUntilIdle(core, A, B, C);
        for (DeviceKey key : Arrays.asList(A, B, C)) {
            for (long id = 1; id <= 3; id++) {
                core.offer(key, frame(1, id));
            }
        }
        backend.clearCalls();
        for (int i = 0; i < 9; i++) {
            core.pass(PassBudget.units(1));
        }
        List<String> frames = new ArrayList<>();
        for (String call : backend.calls()) {
            if (call.startsWith("frame")) {
                frames.add(call);
            }
        }
        Assertions.assertEquals(
            Arrays.asList(
                "frame " + A + " e1 #1",
                "frame " + B + " e1 #1",
                "frame " + C + " e1 #1",
                "frame " + A + " e1 #2",
                "frame " + B + " e1 #2",
                "frame " + C + " e1 #2",
                "frame " + A + " e1 #3",
                "frame " + B + " e1 #3",
                "frame " + C + " e1 #3"),
            frames);
        // units(0) runs no unit, but the pass still fetches, issues and delivers
        core.offer(A, frame(1, 4));
        core.pass(PassBudget.units(0));
        Assertions.assertEquals(1, core.stats(A).queuedRecords);
    }

    // ---- 24:197, H36, 21:87: the ring

    @Test
    void aReadbackOwedWhileTwoAreInFlightIsIssuedAfterAFetchAndANewerFrameReplacesIt() {
        OracleBackend backend = new OracleBackend();
        backend.neverReady = true;
        GlHostCore core = new GlHostCore(backend, options().forceFetchAfterPasses(4));
        Sink sink = new Sink();
        core.open(A, INDEX8, sink);
        core.pass(PassBudget.unlimited());
        long p0 = core.passes();
        for (long id = 1; id <= 4; id++) {
            core.offer(A, frame(1, id, segment(fill((int) id, 0, 1, 1, (int) id))));
            core.pass(PassBudget.unlimited());
        }
        // Frames 1 and 2 are in flight from passes p0+1 and p0+2; 3 was owed at p0+3 and 4 replaced it at p0+4
        DeviceStats s = core.stats(A);
        Assertions.assertEquals(2, s.readbacksInFlight);
        Assertions.assertTrue(s.readbackOwed);
        Assertions.assertEquals(4, s.framesExecuted);
        Assertions.assertEquals(2, s.readbacksIssued);
        Assertions.assertTrue(sink.frames.isEmpty(), "NEVER_READY: nothing before the forced fetch");
        core.pass(PassBudget.unlimited());
        Assertions.assertEquals(Arrays.asList("e1 #1"), sink.frames, "forced at p0+5, 4 passes after its issue");
        Assertions.assertEquals(p0 + 5, core.passes());
        Assertions.assertTrue(
            backend.calls()
                .contains("issue " + A + " e1 #4 at " + (p0 + 5)),
            "the owed readback took the freed slot");
        pumpUntilIdle(core, A);
        Assertions.assertEquals(Arrays.asList("e1 #1", "e1 #2", "e1 #4"), sink.frames);
        for (String call : backend.calls()) {
            Assertions
                .assertFalse(call.startsWith("issue " + A + " e1 #3"), "frame 3's readback was replaced: " + call);
        }
        // The forced fetch came exactly forceAfterPasses after the issue
        Assertions.assertEquals(4, core.stats(A).readbackLatencyMax);
        Assertions.assertNull(core.failure(), "no third readback was issued while two were in flight");
    }

    @Test
    void aResetAfterAFrameInOneTurnIssuesNoReadback() {
        OracleBackend backend = new OracleBackend();
        final GlHostCore core = host(backend);
        Sink sink = new Sink();
        core.open(A, INDEX8, sink);
        pumpUntilIdle(core, A);
        final byte[] k = pattern(INDEX8, 11);
        // The RESET arrives while the frame runs (the hook runs at the start of runFrame, inside the bracket and
        // outside every host lock), so it runs after the frame in the same turn (H37)
        final boolean[] once = { false };
        backend.hook(call -> {
            if (!once[0] && call.equals("runFrame")) {
                once[0] = true;
                core.offer(A, new ResetRecord(2, k));
            }
        });
        core.offer(A, frame(1, 1, segment(clear(3))));
        core.pass(PassBudget.unlimited());
        backend.hook(null);
        Assertions.assertTrue(once[0]);
        DeviceStats s = core.stats(A);
        Assertions.assertEquals(1, s.framesExecuted);
        Assertions.assertEquals(1, s.resetsRun);
        Assertions.assertFalse(s.readbackOwed, "the RESET cancelled frame 1's readback");
        Assertions.assertEquals(0, s.readbacksIssued);
        Assertions.assertArrayEquals(
            k,
            backend.device(A)
                .pixels());
        pumpUntilIdle(core, A);
        Assertions.assertTrue(sink.frames.isEmpty());
    }

    @Test
    void twoReadbacksFetchedInOnePassDeliverOnlyTheNewer() {
        OracleBackend backend = new OracleBackend();
        backend.latency = 100;
        GlHostCore core = new GlHostCore(backend, options().forceFetchAfterPasses(16));
        Sink sink = new Sink();
        core.open(A, INDEX8, sink);
        core.pass(PassBudget.unlimited());
        core.offer(A, frame(1, 1, segment(fill(0, 0, 1, 1, 1))));
        core.pass(PassBudget.unlimited());
        Record f2 = frame(1, 2, segment(fill(1, 0, 1, 1, 2)));
        core.offer(A, f2);
        core.pass(PassBudget.unlimited());
        Assertions.assertEquals(2, core.stats(A).readbacksInFlight);
        backend.latency = 1;
        core.pass(PassBudget.unlimited());
        DeviceStats s = core.stats(A);
        Assertions.assertEquals(2, s.readbacksFetched);
        Assertions.assertEquals(1, s.readbacksDelivered);
        Assertions.assertEquals(Arrays.asList("e1 #2"), sink.frames, "the newest confirms the older (21:87)");
        Assertions.assertEquals(2, s.lastReadbackFrameId);
        assertPixels(INDEX8, replay(INDEX8, Arrays.asList(frame(1, 1, segment(fill(0, 0, 1, 1, 1))), f2)), sink.last());
    }

    @Test
    void aReadbackFetchedInThePassOfItsReleaseIsDropped() {
        final OracleBackend backend = new OracleBackend();
        final GlHostCore core = host(backend);
        Sink a = new Sink();
        Sink b = new Sink();
        core.open(A, INDEX8, a);
        core.open(B, INDEX8, b);
        core.offer(A, frame(1, 1, segment(clear(1))));
        core.pass(PassBudget.unlimited());
        Assertions.assertEquals(1, core.stats(A).readbacksInFlight);
        // A's readback is fetched in step (a); then, while B's frame runs, A is released (21:106: late readbacks drop)
        backend.hook(call -> {
            if (call.equals("runFrame")) {
                core.release(A);
            }
        });
        core.offer(B, frame(1, 1, segment(clear(2))));
        core.pass(PassBudget.unlimited());
        backend.hook(null);
        DeviceStats s = core.stats(A);
        Assertions.assertTrue(s.released);
        Assertions.assertEquals(1, s.readbacksFetched);
        Assertions.assertEquals(0, s.readbacksDelivered);
        Assertions.assertTrue(a.frames.isEmpty(), "a released device gets no readback");
        pumpUntilIdle(core, A, B);
        Assertions.assertEquals(Arrays.asList("e1 #1"), b.frames);
        Assertions.assertTrue(a.frames.isEmpty());
    }

    // ---- 28:1072: delivery outside the bracket and every lock

    @Test
    @Timeout(20)
    void deliveryRunsOutsideTheBracketWithNoHostLockHeld() throws Exception {
        final OracleBackend backend = new OracleBackend();
        final GlHostCore core = host(backend);
        final AtomicReference<String> problem = new AtomicReference<>();
        final List<String> done = Collections.synchronizedList(new ArrayList<String>());
        ReadbackSink joining = new ReadbackSink() {

            @Override
            public void onReadback(long session, int deviceId, long epoch, long frameId, byte[] pixels) {
                if (backend.inPass()) {
                    problem.set("delivered inside the bracket");
                }
                Thread t = new Thread(() -> {
                    core.stats(A);
                    core.stats(B);
                    core.idle(A);
                    core.offer(B, frame(1, 1, segment(clear(4))));
                    core.open(C, INDEX8, new Sink());
                    done.add("e" + epoch + " #" + frameId);
                }, "second thread");
                t.setDaemon(true);
                t.start();
                try {
                    t.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread()
                        .interrupt();
                }
                if (t.isAlive()) {
                    problem.set("the second thread did not finish within 5 s: a host lock is held during delivery");
                }
            }
        };
        core.open(A, INDEX8, joining);
        Sink b = new Sink();
        core.open(B, INDEX8, b);
        core.offer(A, frame(1, 1, segment(clear(1))));
        pumpUntilIdle(core, A, B, C);
        Assertions.assertNull(problem.get(), problem.get());
        Assertions.assertEquals(Arrays.asList("e1 #1"), done);
        Assertions.assertEquals(Arrays.asList("e1 #1"), b.frames, "the second thread's offer ran");
        Assertions.assertNotNull(core.stats(C));
    }

    @Test
    @Timeout(30)
    void noHostLockIsHeldAcrossABackendCall() throws Exception {
        final OracleBackend backend = new OracleBackend();
        final GlHostCore core = new GlHostCore(backend, options());
        final AtomicReference<String> problem = new AtomicReference<>();
        final int[] probes = { 0 };
        backend.hook(call -> {
            probes[0]++;
            Thread t = new Thread(() -> {
                // Each takes a lock the render thread might hold: A's, B's, or the host's
                core.stats(A);
                core.idle(B);
                core.stats(B);
                core.release(new DeviceKey(9, 9));
                core.releaseSession(99);
                core.bindDisplay("probe", A);
            }, "probe");
            t.setDaemon(true);
            t.start();
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            }
            if (t.isAlive()) {
                problem.compareAndSet(null, "a lock was held across a backend call");
            }
        });
        Sink sink = new Sink();
        core.open(A, INDEX8, sink);
        core.open(B, INDEX8, new Sink());
        // Owned and shared mirrors, a delete, a create, an update, a RESET, frames and readbacks
        core.offer(B, create(1, TEX3, 1, 1));
        core.offer(A, create(1, TEX, 4, 4));
        core.offer(A, update(1, TEX, 0, 0, 4, 4, pattern(new SurfaceDesc(0, 4, 4, Formats.INDEX8), 1)));
        core.offer(A, create(1, TEX2, 3, 3));
        core.offer(A, update(1, TEX2, 1, 1, 2, 2, bytes(1, 2, 3, 4)));
        core.offer(A, frame(1, 1, segment(blit(TEX, 0, 0, 4, 4, 0, 0, 0), blit(TEX2, 0, 0, 3, 3, 4, 2, 0))));
        pumpUntilIdle(core, A, B);
        core.offer(A, new TexFreeRecord(1, TEX));
        core.offer(A, new ResetRecord(2, pattern(INDEX8, 2)));
        core.offer(A, frame(2, 2, segment(fill(0, 0, 2, 2, 9))));
        pumpUntilIdle(core, A, B);
        backend.hook(null);
        Assertions.assertNull(problem.get(), problem.get());
        Assertions.assertTrue(probes[0] > 10, "probes " + probes[0]);
        Assertions.assertEquals(Arrays.asList("e1 #1", "e2 #2"), sink.frames);
        Assertions.assertNull(core.failure());
    }

    // ---- I-H1 (amendment D): one resource record per lock hold, none ahead of a FRAME when a pass ends

    @Test
    void resourceRecordsBehindATakenFrameAreAppliedBeforeThePassEnds() {
        final OracleBackend backend = new OracleBackend();
        final GlHostCore core = host(backend);
        Sink sink = new Sink();
        core.open(A, INDEX8, sink);
        core.offer(A, create(1, TEX, 2, 1));
        core.offer(A, update(1, TEX, 0, 0, 2, 1, bytes(1, 2)));
        pumpUntilIdle(core, A);
        // 28:144: frame 5 shows the old contents, frame 6 the new ones
        List<Record> offered = new ArrayList<>();
        offered.add(create(1, TEX, 2, 1));
        offered.add(update(1, TEX, 0, 0, 2, 1, bytes(1, 2)));
        offered.add(frame(1, 5, segment(blit(TEX, 0, 0, 2, 1, 0, 0, 0))));
        offered.add(update(1, TEX, 0, 0, 2, 1, bytes(7, 8)));
        offered.add(frame(1, 6, segment(blit(TEX, 0, 0, 2, 1, 0, 1, 0))));
        for (Record r : offered.subList(2, 5)) {
            core.offer(A, r);
            assertIH1(core, A);
        }
        Assertions.assertEquals(3, core.stats(A).queuedRecords, "the update waits behind frame 5");
        final int[] seen = { -1 };
        backend.hook(call -> {
            HostDevice hd = core.device(A);
            if (seen[0] < 0 && call.equals("runFrame")) {
                // Frame 5 is taken and starts; the update behind it is at the head (I-H1 as amended)
                seen[0] = hd.headResources() > 0 && hd.trailing() ? 1 : 0;
            }
        });
        core.pass(PassBudget.units(1));
        assertIH1(core, A);
        DeviceStats s = core.stats(A);
        Assertions.assertEquals(0, s.headResources, "no resource record ahead of a FRAME when the pass ends");
        Assertions.assertEquals(1, s.queuedRecords, "frame 6");
        Assertions.assertEquals(1, s.appliedQueued);
        Assertions.assertEquals(1, s.pendingTextures, "the update is pending, frame 6 waits for it");
        pumpUntilIdle(core, A);
        backend.hook(null);
        Assertions.assertEquals(1, seen[0], "the trailing flag held while the update was at the head");
        Assertions.assertEquals(Arrays.asList("e1 #5", "e1 #6"), sink.frames);
        assertPixels(INDEX8, replay(INDEX8, offered.subList(0, 3)), sink.pixels.get(0));
        assertPixels(INDEX8, replay(INDEX8, offered), sink.pixels.get(1));
    }

    // ---- A4: an idle host makes no backend call

    @Test
    void anIdleHostMakesNoBackendCall() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = new GlHostCore(backend, options().expand(HostOptions.Expand.BOUND_ONLY));
        for (int i = 0; i < 3; i++) {
            core.pass(PassBudget.unlimited());
        }
        Assertions.assertTrue(
            backend.calls()
                .isEmpty(),
            backend.calls()
                .toString());
        Assertions.assertEquals(3, core.passes());
        core.open(A, INDEX8, new Sink());
        core.pass(PassBudget.unlimited());
        Assertions.assertEquals(Arrays.asList("beginPass", "open " + A, "endPass"), backend.calls());
        backend.clearCalls();
        core.pass(PassBudget.unlimited());
        Assertions.assertTrue(
            backend.calls()
                .isEmpty(),
            "opened and nothing owed: " + backend.calls());
        core.offer(A, frame(1, 1));
        pumpUntilIdle(core, A);
        backend.clearCalls();
        for (int i = 0; i < 3; i++) {
            core.pass(PassBudget.unlimited());
        }
        Assertions.assertTrue(
            backend.calls()
                .isEmpty(),
            "delivered, and the expand waits for a display: " + backend.calls());
        // A key opened and released before its first turn has no objects: still no work
        core.open(B, INDEX8, new Sink());
        core.release(B);
        core.pass(PassBudget.unlimited());
        Assertions.assertTrue(
            backend.calls()
                .isEmpty(),
            backend.calls()
                .toString());
    }

    // ---- H63: the failure path

    @Test
    void aBackendThatThrowsInBeginPassFailsTheHostForGood() {
        OracleBackend backend = new OracleBackend().failBeginPassAt(3);
        GlHostCore core = host(backend);
        Sink sink = new Sink();
        // The rig's device is DeviceRig.KEY, 1:1, which is A: the raw records go to B
        DeviceRig rig = DeviceRig.on(caps(INDEX8), core, () -> core.pass(PassBudget.unlimited()));
        core.open(B, INDEX8, sink);
        core.pass(PassBudget.unlimited());
        core.offer(B, frame(1, 1, segment(clear(1))));
        core.pass(PassBudget.unlimited());
        Assertions.assertNull(core.failure());
        core.bindDisplay("d", B);
        Assertions.assertSame(backend.device(B), core.presentDevice("d"));
        Assertions.assertTrue(core.stats(B).opened);
        core.offer(B, frame(1, 2));
        core.pass(PassBudget.unlimited());
        Assertions.assertNotNull(core.failure());
        // backend.close() deleted every device's objects: the TESR gets null, the "no graphics" bitmap (20:242)
        Assertions.assertNull(core.deviceBackend(B));
        Assertions.assertNull(core.presentDevice("d"));
        Assertions.assertEquals(B, core.boundDevice("d"), "the binding stays");
        Assertions.assertFalse(core.stats(B).opened);
        Assertions.assertTrue(
            core.failure()
                .contains("failed to link"),
            core.failure());
        Assertions.assertFalse(core.available());
        Assertions.assertEquals(1, logs.size(), "logged once: " + logs);
        Assertions.assertTrue(
            logs.get(0)
                .contains("failed to link"));
        Assertions.assertEquals(1, backend.closes());
        Assertions.assertFalse(backend.inPass(), "the failed beginPass left no bracket open");
        DeviceStats s = core.stats(B);
        Assertions.assertEquals(core.failure(), s.hostFailure);
        Assertions.assertEquals(0, s.queuedRecords);
        // Offers are dropped uncounted; passes make no backend call; a device answers "no renderer"
        long violations = core.contractViolations();
        core.offer(B, frame(1, 3));
        core.offer(new DeviceKey(7, 7), frame(1, 1));
        Assertions.assertEquals(violations, core.contractViolations());
        int begins = backend.beginPasses();
        for (int i = 0; i < 3; i++) {
            core.pass(PassBudget.unlimited());
        }
        Assertions.assertEquals(begins, backend.beginPasses());
        Assertions.assertTrue(sink.frames.isEmpty(), "frame 1's readback was never fetched");
        Assertions.assertEquals(
            "no renderer",
            rig.call(b -> rig.device.present(b))
                .message());
        Assertions.assertTrue(core.idle(B));
        Assertions.assertEquals(1, logs.size());
    }

    @Test
    void aFailureInsideThePassClosesTheBracketAndFailsTheHost() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = host(backend);
        core.open(A, INDEX8, new Sink());
        pumpUntilIdle(core, A);
        core.bindDisplay("d", A);
        Assertions.assertSame(backend.device(A), core.deviceBackend(A));
        Assertions.assertSame(backend.device(A), core.presentDevice("d"));
        backend.hook(call -> { throw new IllegalStateException("FBO incomplete: GL_FRAMEBUFFER_UNSUPPORTED"); });
        core.offer(A, frame(1, 1));
        core.pass(PassBudget.unlimited());
        Assertions.assertTrue(
            core.failure()
                .contains("FBO incomplete"),
            core.failure());
        Assertions.assertFalse(backend.inPass(), "endPass closed the bracket");
        Assertions.assertEquals(1, backend.closes());
        // The failed host's devices have no GL objects: no backend device for the TESR, and none opened (H63)
        Assertions.assertNull(core.deviceBackend(A));
        Assertions.assertNull(core.presentDevice("d"));
        Assertions.assertFalse(core.stats(A).opened);

        // endPass's own glGetError check (PassState.exit) fails the host too
        OracleBackend second = new OracleBackend().failEndPassAt(2);
        GlHostCore core2 = host(second);
        core2.open(A, INDEX8, new Sink());
        core2.pass(PassBudget.unlimited());
        Assertions.assertNull(core2.failure());
        core2.bindDisplay("d", A);
        Assertions.assertSame(second.device(A), core2.presentDevice("d"));
        core2.offer(A, frame(1, 1));
        core2.pass(PassBudget.unlimited());
        Assertions.assertTrue(
            core2.failure()
                .contains("GL_INVALID_OPERATION"),
            core2.failure());
        Assertions.assertFalse(second.inPass());
        Assertions.assertEquals(1, second.closes());
        Assertions.assertNull(core2.deviceBackend(A));
        Assertions.assertNull(core2.presentDevice("d"));
        Assertions.assertFalse(core2.stats(A).opened);
    }

    // ---- 28:202, A11: dev builds check a record again when the render thread takes it

    @Test
    void aRecordWrittenAfterItsOfferFailsTheDevBuildChecksWhereTheRenderThreadTakesIt() {
        Assertions.assertTrue(Record.devChecks(), "dev checks run (-ea)");
        // Mirrors on: a TEX_UPDATE queued behind a FRAME, checked when it is applied
        OracleBackend backend = new OracleBackend();
        GlHostCore core = host(backend);
        core.open(A, INDEX8, new Sink());
        core.offer(A, create(1, TEX, 2, 1));
        core.offer(A, frame(1, 1));
        byte[] payload = bytes(1, 2);
        core.offer(A, update(1, TEX, 0, 0, 2, 1, payload));
        payload[0] = 9;
        AssertionError applied = Assertions.assertThrows(AssertionError.class, () -> core.pass(PassBudget.unlimited()));
        Assertions.assertTrue(
            applied.getMessage()
                .contains("changed after it was offered"),
            applied.getMessage());
        Assertions.assertFalse(backend.inPass(), "the bracket closed");
        // A whole update is adopted, so the SYNC's check (SP-2) sees it too; a partial one is copied into the mirror,
        // and only the check where it is applied sees the change
        OracleBackend partial = new OracleBackend();
        GlHostCore copied = host(partial);
        copied.open(A, INDEX8, new Sink());
        copied.offer(A, create(1, TEX, 3, 1));
        copied.offer(A, frame(1, 1));
        byte[] part = bytes(1, 2);
        copied.offer(A, update(1, TEX, 1, 0, 2, 1, part));
        part[0] = 9;
        AssertionError copiedApply = Assertions
            .assertThrows(AssertionError.class, () -> copied.pass(PassBudget.unlimited()));
        Assertions.assertTrue(
            copiedApply.getMessage()
                .contains("changed after it was offered"),
            copiedApply.getMessage());
        Assertions.assertFalse(partial.inPass(), "the bracket closed");
        // Mirrors off: the payload is checked before its upload
        OracleBackend off = new OracleBackend();
        GlHostCore raw = new GlHostCore(off, options().mirrors(false));
        raw.open(A, INDEX8, new Sink());
        raw.offer(A, create(1, TEX, 2, 1));
        byte[] queued = bytes(3, 4);
        raw.offer(A, update(1, TEX, 0, 0, 2, 1, queued));
        queued[1] = 0;
        Assertions.assertThrows(AssertionError.class, () -> raw.pass(PassBudget.unlimited()));
        Assertions.assertFalse(off.inPass());
        // A FRAME's segment is checked at execution
        OracleBackend third = new OracleBackend();
        GlHostCore frames = host(third);
        frames.open(A, INDEX8, new Sink());
        byte[] segment = segment(fill(0, 0, 1, 1, 5));
        frames.offer(A, frame(1, 1, segment));
        segment[segment.length - 2] = 6;
        Assertions.assertThrows(AssertionError.class, () -> frames.pass(PassBudget.unlimited()));
        Assertions.assertFalse(third.inPass());
    }

    /**
     * 28:202 and 21:128 at upload: a whole-texture update offered on an empty queue is applied at its offer and its
     * payload adopted by the mirror (amendment A), so no queued record is taken later; the mirror's record is checked
     * before the SYNC hands the array out for its upload and before a partial update clones it, and a {@code RESET}'s
     * pixels before theirs.
     */
    @Test
    void anAdoptedPayloadOrResetWrittenAfterItsOfferFailsTheDevBuildCheckBeforeItIsUploadedOrCloned() {
        Assertions.assertTrue(Record.devChecks(), "dev checks run (-ea)");
        OracleBackend backend = new OracleBackend();
        GlHostCore core = host(backend);
        core.open(A, INDEX8, new Sink());
        pumpUntilIdle(core, A);
        core.offer(A, create(1, TEX, 2, 1));
        byte[] whole = bytes(1, 2);
        core.offer(A, update(1, TEX, 0, 0, 2, 1, whole));
        Assertions.assertTrue(
            core.device(A)
                .mirror(TEX)
                .shared(),
            "adopted at its offer");
        Assertions.assertEquals(0, core.stats(A).queuedRecords, "nothing queued: no APPLY will check it");
        whole[1] = 9;
        AssertionError upload = Assertions.assertThrows(AssertionError.class, () -> core.pass(PassBudget.unlimited()));
        Assertions.assertTrue(
            upload.getMessage()
                .contains("changed after it was offered"),
            upload.getMessage());
        Assertions.assertFalse(backend.inPass(), "the bracket closed");
        Assertions.assertTrue(
            backend.device(A)
                .handles()
                .isEmpty(),
            "the changed bytes never reached the GPU");

        // A partial update clones the adopted array: checked before the clone, on the offering thread
        OracleBackend second = new OracleBackend();
        GlHostCore cloning = host(second);
        cloning.open(A, INDEX8, new Sink());
        cloning.offer(A, create(1, TEX, 2, 1));
        byte[] adopted = bytes(3, 4);
        cloning.offer(A, update(1, TEX, 0, 0, 2, 1, adopted));
        adopted[0] = 7;
        AssertionError clone = Assertions
            .assertThrows(AssertionError.class, () -> cloning.offer(A, update(1, TEX, 1, 0, 1, 1, bytes(5))));
        Assertions.assertTrue(
            clone.getMessage()
                .contains("changed after it was offered"),
            clone.getMessage());

        // RESET's pixels (K's copy, 28:150) are checked before they are uploaded
        OracleBackend third = new OracleBackend();
        GlHostCore resets = host(third);
        resets.open(A, INDEX8, new Sink());
        pumpUntilIdle(resets, A);
        byte[] k = pattern(INDEX8, 4);
        resets.offer(A, new ResetRecord(2, k));
        k[0] ^= 1;
        AssertionError reset = Assertions.assertThrows(AssertionError.class, () -> resets.pass(PassBudget.unlimited()));
        Assertions.assertTrue(
            reset.getMessage()
                .contains("changed after it was offered"),
            reset.getMessage());
        Assertions.assertFalse(third.inPass());
        Assertions.assertEquals(0, third.device(A).resets, "the changed pixels never reached the GPU");
    }

    // ---- H64: a side over the backend's limit

    @Test
    void aSurfaceOverTheBackendsSideLimitIsAContractViolationAndItsRecordsAreDropped() {
        OracleBackend backend = new OracleBackend().maxSurfaceSide(16);
        GlHostCore core = host(backend);
        Sink wide = new Sink();
        Sink ok = new Sink();
        core.open(A, new SurfaceDesc(0, 17, 4, Formats.INDEX8), wide);
        core.open(B, new SurfaceDesc(0, 16, 16, Formats.RGB565), ok);
        core.offer(A, frame(1, 1));
        core.offer(B, frame(1, 1));
        pumpUntilIdle(core, A, B);
        Assertions.assertEquals(1, core.contractViolations());
        DeviceStats a = core.stats(A);
        Assertions.assertTrue(a.refused);
        Assertions.assertFalse(a.opened);
        Assertions.assertEquals(1, a.recordsDropped);
        Assertions.assertNull(backend.device(A));
        Assertions.assertEquals(Arrays.asList("e1 #1"), ok.frames);
        core.offer(A, frame(1, 2));
        Assertions.assertEquals(1, core.contractViolations(), "counted once, for the opening");
        Assertions.assertEquals(2, core.stats(A).recordsDropped);
        Assertions.assertTrue(wide.frames.isEmpty());
        Assertions.assertTrue(core.idle(A));
        Assertions.assertTrue(
            logs.get(0)
                .contains("H64"),
            logs.toString());
    }

    // ---- Plan §3.10's pending rules

    @Test
    void aFreeOfATextureThatNeverReachedTheGpuIssuesNoGlCall() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = host(backend);
        core.open(A, INDEX8, new Sink());
        pumpUntilIdle(core, A);
        backend.clearCalls();
        core.offer(A, create(1, TEX, 2, 2));
        core.offer(A, update(1, TEX, 0, 0, 1, 1, bytes(3)));
        core.offer(A, new TexFreeRecord(1, TEX));
        DeviceStats s = core.stats(A);
        Assertions.assertEquals(0, s.pendingTextures);
        Assertions.assertEquals(0, s.mirrorTextures);
        Assertions.assertFalse(core.stats(A).pendingReset);
        Assertions.assertTrue(core.idle(A));
        core.pass(PassBudget.unlimited());
        Assertions.assertTrue(
            backend.calls()
                .isEmpty(),
            backend.calls()
                .toString());
        // On the GPU: an update then a free is one delete and no upload
        core.offer(A, create(1, TEX2, 2, 2));
        pumpUntilIdle(core, A);
        backend.clearCalls();
        core.offer(A, update(1, TEX2, 0, 0, 2, 2, bytes(1, 2, 3, 4)));
        core.offer(A, new TexFreeRecord(1, TEX2));
        pumpUntilIdle(core, A);
        Assertions.assertEquals(Arrays.asList("beginPass", "delete " + A + " 0x08010000", "endPass"), backend.calls());
        Assertions.assertTrue(
            backend.device(A)
                .handles()
                .isEmpty());
    }

    @Test
    void aHandleCreatedAgainWhileItsDeleteIsPendingIsDeletedThenCreated() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = host(backend);
        Sink sink = new Sink();
        core.open(A, INDEX8, sink);
        core.offer(A, create(1, TEX, 2, 2));
        core.offer(A, update(1, TEX, 0, 0, 2, 2, bytes(1, 1, 1, 1)));
        pumpUntilIdle(core, A);
        backend.clearCalls();
        // After a generation wrap the same full handle comes back (O7): delete the old texture, then create the new
        core.offer(A, new TexFreeRecord(1, TEX));
        core.offer(A, create(1, TEX, 3, 1));
        core.offer(A, update(1, TEX, 1, 0, 1, 1, bytes(9)));
        List<Pending> pending = core.device(A)
            .pendingCopy();
        Assertions.assertEquals(1, pending.size());
        Assertions.assertTrue(
            pending.get(0).deleteGpu && pending.get(0).create
                && !pending.get(0)
                    .dirty(),
            pending.toString());
        Record f = frame(1, 1, segment(blit(TEX, 0, 0, 3, 1, 0, 0, 0)));
        core.offer(A, f);
        pumpUntilIdle(core, A);
        List<String> calls = backend.calls();
        Assertions.assertEquals("delete " + A + " 0x08000000", calls.get(1), calls.toString());
        Assertions.assertEquals("create " + A + " 0x08000000", calls.get(2), calls.toString());
        assertPixels(
            INDEX8,
            replay(INDEX8, Arrays.asList(create(1, TEX, 3, 1), update(1, TEX, 1, 0, 1, 1, bytes(9)), f)),
            sink.last());
    }

    @Test
    void aSecondUpdateWidensTheDirtyBoxIntoOneUpload() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = host(backend);
        core.open(A, INDEX8, new Sink());
        SurfaceDesc tex = new SurfaceDesc(0, 8, 8, Formats.INDEX8);
        core.offer(A, create(1, TEX, 8, 8));
        core.offer(A, update(1, TEX, 0, 0, 8, 8, pattern(tex, 1)));
        pumpUntilIdle(core, A);
        backend.clearCalls();
        core.offer(A, update(1, TEX, 1, 1, 2, 2, bytes(10, 11, 12, 13)));
        core.offer(A, update(1, TEX, 5, 4, 2, 3, bytes(20, 21, 22, 23, 24, 25)));
        Pending p = core.device(A)
            .pendingCopy()
            .get(0);
        Assertions.assertEquals(Arrays.asList(1, 1, 7, 7), Arrays.asList(p.x0, p.y0, p.x1, p.y1));
        pumpUntilIdle(core, A);
        Assertions.assertEquals(
            Arrays.asList("beginPass", "update " + A + " 0x08000000 1,1 6x6", "endPass"),
            backend.calls());
        assertGpuTexture(core, backend, A, TEX);
    }

    @Test
    void aFrameRunsOnlyAfterTheTexturesItReadsAreSynced() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = host(backend);
        Sink sink = new Sink();
        core.open(A, INDEX8, sink);
        core.pass(PassBudget.unlimited());
        List<Record> offered = Arrays.<Record>asList(
            create(1, TEX, 2, 2),
            update(1, TEX, 0, 0, 2, 2, bytes(4, 5, 6, 7)),
            frame(1, 1, segment(blit(TEX, 0, 0, 2, 2, 3, 3, 0))));
        for (Record r : offered) {
            core.offer(A, r);
        }
        backend.clearCalls();
        core.pass(PassBudget.units(1));
        Assertions.assertEquals(Arrays.asList("beginPass", "create " + A + " 0x08000000", "endPass"), backend.calls());
        core.pass(PassBudget.units(1));
        Assertions.assertTrue(
            backend.calls()
                .contains("frame " + A + " e1 #1"));
        pumpUntilIdle(core, A);
        assertPixels(INDEX8, replay(INDEX8, offered), sink.last());
    }

    // ---- H57: one GL thread

    @Test
    @Timeout(20)
    void theFirstThreadThatRunsAPassWithWorkIsTheGlThread() throws Exception {
        OracleBackend backend = new OracleBackend();
        final GlHostCore core = host(backend);
        // An idle pass fixes nothing: it makes no GL call
        Assertions.assertNull(onThread(() -> core.pass(PassBudget.unlimited())));
        core.open(A, INDEX8, new Sink());
        core.pass(PassBudget.unlimited());
        Throwable pass = onThread(() -> core.pass(PassBudget.unlimited()));
        Assertions.assertTrue(pass instanceof IllegalStateException, String.valueOf(pass));
        Assertions.assertTrue(
            pass.getMessage()
                .contains("H57"),
            pass.getMessage());
        Assertions.assertTrue(onThread(() -> core.close()) instanceof IllegalStateException);
        Assertions.assertTrue(onThread(() -> core.presentDevice("x")) instanceof IllegalStateException);
        Assertions.assertTrue(onThread(() -> core.readNow(A)) instanceof IllegalStateException);
        // Offers and inspection run on any thread
        Assertions.assertNull(onThread(() -> {
            core.offer(A, frame(1, 1));
            core.stats(A);
            core.idle(A);
        }));
        pumpUntilIdle(core, A);
        Assertions.assertNull(core.failure());
    }

    // ---- close

    @Test
    void closeDestroysEveryOpeningInOneBracketThenClosesTheBackend() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = host(backend);
        core.open(A, INDEX8, new Sink());
        core.open(B, RGB565, new Sink());
        core.offer(A, frame(1, 1));
        pumpUntilIdle(core, A, B);
        core.open(C, INDEX8, new Sink());
        core.bindDisplay("x", A);
        backend.clearCalls();
        core.close();
        Assertions.assertEquals(
            Arrays.asList("beginPass", "destroy " + A, "destroy " + B, "endPass", "close"),
            backend.calls());
        Assertions.assertFalse(core.available());
        Assertions.assertNull(core.boundDevice("x"));
        Assertions.assertNull(core.stats(A), "close drops every opening, its stats with it");
        Assertions.assertNull(core.stats(C));
        long violations = core.contractViolations();
        core.offer(A, frame(1, 2));
        core.open(A, INDEX8, new Sink());
        Assertions.assertNull(core.stats(A), "an open after close makes no opening");
        core.pass(PassBudget.unlimited());
        core.close();
        Assertions.assertEquals(violations, core.contractViolations());
        Assertions.assertEquals(1, backend.closes());

        OracleBackend unused = new OracleBackend();
        GlHostCore never = host(unused);
        never.close();
        Assertions.assertEquals(Arrays.asList("close"), unused.calls());
    }

    /**
     * An open that found the host open just before {@code close} or a failure took the host's lock adds nothing once
     * the lock is its: no opening, no stats, no backend device, and the device it was handed is not kept (28:151;
     * H63). The test holds the host's lock (reflection: the lock is private, and main code has no seam for this) while
     * the opening thread blocks on it, closes or fails the host on its own thread, whose hold of the monitor is
     * reentrant, and only then lets the open go.
     */
    @Test
    @Timeout(60)
    void anOpenRacingCloseOrAFailureAddsNothingOnceTheyHaveRun() throws Exception {
        for (String end : new String[] { "close", "a failure" }) {
            final OracleBackend backend = new OracleBackend();
            final GlHostCore core = host(backend);
            core.open(A, INDEX8, new Sink());
            boolean close = end.equals("close");
            // H63: the next pass's beginPass throws, and the host fails for good
            backend.failBeginPassAt(close ? 0 : 1);
            final DeviceKey key = new DeviceKey(7, 1);
            final List<WeakReference<Device>> handed = Collections.synchronizedList(new ArrayList<>());
            final AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread opener = new Thread(() -> {
                try {
                    // Device.create opens its key on the host before it returns (28:199)
                    Device device = Device
                        .create(key, caps(INDEX8), core, Runnable::run, FrameListener.NONE, System::nanoTime);
                    handed.add(new WeakReference<>(device));
                } catch (Throwable e) {
                    thrown.set(e);
                }
            }, "opener");
            opener.setDaemon(true);
            Object lock = hostLock(core);
            synchronized (lock) {
                opener.start();
                awaitBlockedOn(opener, lock);
                if (close) {
                    core.close();
                } else {
                    core.pass(PassBudget.unlimited());
                }
            }
            opener.join(5_000);
            Assertions.assertFalse(opener.isAlive(), end + ": the open did not return within 5 s");
            Assertions.assertNull(thrown.get(), String.valueOf(thrown.get()));
            Assertions.assertFalse(core.available(), end);
            Assertions.assertEquals(close, core.failure() == null, end + ": " + core.failure());
            Assertions.assertNull(core.device(key), end + ": an opening was added after it");
            Assertions.assertNull(core.stats(key), end + ": stats were added after it");
            core.pass(PassBudget.unlimited());
            Assertions.assertNull(backend.device(key), end + ": a backend device was made after it");
            Assertions.assertEquals(1, handed.size());
            Assertions
                .assertEquals(0, awaitCleared(handed), end + ": the host keeps the device it was handed after it");
            Assertions.assertEquals(0, core.contractViolations(), end);
        }
    }

    // ---- Mirrors off (option B)

    @Test
    void mirrorsOffQueuesEveryRecordAndRunsThemInOfferedOrder() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = new GlHostCore(backend, options().mirrors(false));
        Sink sink = new Sink();
        core.open(A, RGB565, sink);
        core.pass(PassBudget.unlimited());
        List<Record> offered = Arrays.<Record>asList(
            new TexCreateRecord(1, TEX, 2, 1, Formats.RGB565),
            update(1, TEX, 0, 0, 2, 1, bytes(0x34, 0x12, 0xFF, 0xFF)),
            frame(1, 1, segment(blit(TEX, 0, 0, 2, 1, 1, 1, 0))),
            new TexFreeRecord(1, TEX));
        for (Record r : offered) {
            core.offer(A, r);
        }
        DeviceStats s = core.stats(A);
        Assertions.assertEquals(4, s.queuedRecords);
        Assertions.assertEquals(0, s.mirrorTextures);
        backend.clearCalls();
        core.pass(PassBudget.units(2));
        Assertions.assertEquals(
            Arrays.asList("beginPass", "create " + A + " 0x08000000", "update " + A + " 0x08000000 0,0 2x1", "endPass"),
            backend.calls());
        pumpUntilIdle(core, A);
        assertPixels(RGB565, replay(RGB565, offered.subList(0, 3)), sink.last());
        Assertions.assertTrue(
            backend.device(A)
                .handles()
                .isEmpty());
        // A RESET drops what is queued with mirrors off too
        core.offer(A, frame(1, 2));
        core.offer(A, new ResetRecord(2, new byte[RGB565.byteSize()]));
        Assertions.assertEquals(1, core.stats(A).recordsDropped);
    }

    // ---- G13b: every upload's texels are the staging buffer the backend handed out

    /**
     * GlBackend measures an upload's {@code stageNanos} only when its texels are the buffer {@code staging} handed out
     * (28 §6.7; G13b), so every upload the host makes must come from it, with mirrors on and off: a create's zeros, a
     * created texture's adopted or owned mirror, an update's dirty box from either, a payload with mirrors off, and a
     * {@code RESET}'s pixels.
     */
    @Test
    void everyUploadIsFromTheStagingBufferTheBackendHandedOut() {
        for (boolean mirrors : new boolean[] { true, false }) {
            OracleBackend backend = new OracleBackend();
            GlHostCore core = new GlHostCore(backend, options().mirrors(mirrors));
            core.open(A, INDEX8, new Sink());
            pumpUntilIdle(core, A);
            // Zeros; an adopted whole payload; an owned array a partial update made
            core.offer(A, create(1, TEX, 2, 2));
            core.offer(A, create(1, TEX2, 3, 2));
            core.offer(A, update(1, TEX2, 0, 0, 3, 2, bytes(1, 2, 3, 4, 5, 6)));
            core.offer(A, create(1, TEX3, 3, 2));
            core.offer(A, update(1, TEX3, 1, 0, 2, 1, bytes(7, 8)));
            pumpUntilIdle(core, A);
            // The dirty boxes of an owned array, of an adopted one and of zeros made an array
            core.offer(A, update(1, TEX3, 0, 1, 1, 1, bytes(9)));
            core.offer(A, update(1, TEX2, 0, 0, 3, 2, bytes(10, 11, 12, 13, 14, 15)));
            core.offer(A, update(1, TEX, 1, 1, 1, 1, bytes(16)));
            pumpUntilIdle(core, A);
            core.offer(A, new ResetRecord(2, pattern(INDEX8, 4)));
            pumpUntilIdle(core, A);
            String where = mirrors ? "mirrors on" : "mirrors off";
            // Mirrors on, a created texture's first upload carries its updates; mirrors off, each record runs
            List<String> expected = mirrors
                ? Arrays.asList("create", "create", "create", "update", "update", "update", "reset")
                : Arrays
                    .asList("create", "create", "update", "create", "update", "update", "update", "update", "reset");
            List<String> staged = new ArrayList<>();
            for (String call : expected) {
                staged.add(call + " staged");
            }
            Assertions.assertEquals(staged, backend.uploads(), where);
            Assertions.assertEquals(0, core.contractViolations(), where);
        }
    }

    // ---- readNow (amendment C)

    @Test
    void readNowReadsTheSurfaceInABracketOnlyWithNoReadbackInFlight() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = host(backend);
        core.open(A, INDEX8, new Sink());
        Assertions.assertThrows(IllegalStateException.class, () -> core.readNow(A), "no GL objects yet");
        Assertions.assertThrows(IllegalStateException.class, () -> core.readNow(B), "not open");
        Record f = frame(1, 1, segment(fill(2, 1, 3, 3, 42)));
        core.offer(A, f);
        core.pass(PassBudget.unlimited());
        Assertions.assertThrows(IllegalStateException.class, () -> core.readNow(A), "a readback is in flight");
        pumpUntilIdle(core, A);
        backend.clearCalls();
        assertPixels(INDEX8, replay(INDEX8, Collections.singletonList(f)), core.readNow(A));
        Assertions.assertEquals(Arrays.asList("beginPass", "readNow " + A, "endPass"), backend.calls());
        Assertions.assertNull(core.failure());
    }

    // ---- A device on the host, checked by the shadow oracle

    @Test
    void aDeviceOnTheHostMatchesTheShadowOracle() {
        OracleBackend backend = new OracleBackend();
        final GlHostCore core = host(backend);
        List<String> failures = Collections.synchronizedList(new ArrayList<String>());
        ShadowOracle shadow = new ShadowOracle(SyntheticGlyphs.TABLE);
        TeeRenderHost tee = new TeeRenderHost(core, shadow, failures);
        DeviceRig rig = DeviceRig.on(caps(INDEX8), tee, () -> pumpUntilIdle(core, DeviceRig.KEY));
        int t = rig.create(3, 2);
        rig.update(t, 0, 0, 3, 2, bytes(1, 2, 3, 4, 5, 6));
        rig.submit(segment(clear(9), blit(t, 0, 0, 3, 2, 2, 2, 1)));
        long first = rig.present();
        rig.update(t, 1, 1, 1, 1, bytes(77));
        rig.submit(segment(blit(t, 0, 0, 3, 2, 4, 3, 0)));
        rig.free(t);
        long second = rig.present();
        rig.step();
        Assertions.assertTrue(failures.isEmpty(), failures.toString());
        Assertions.assertEquals(second, rig.snapshot().confirmedFrameId);
        Assertions.assertEquals(Arrays.asList("done " + second), rig.listener.events());
        Assertions.assertArrayEquals(shadow.pixelsNow(DeviceRig.KEY), rig.screen());
        Assertions.assertTrue(first < second);
        Assertions.assertEquals(0, core.contractViolations());
        Assertions.assertEquals(0, core.stats(DeviceRig.KEY).gpuTextures);
    }

    // ---- Threads (S8 plan §3.12): workers, the render thread, a server thread and a fold pool at once

    /** The race's callbacks per worker, a reset of each device per this many of them, and a pace's bound. */
    private static final int RACE_CALLBACKS = 20_000, RACE_RESET_EVERY = 250;
    private static final long RACE_PACE_NANOS = TimeUnit.MILLISECONDS.toNanos(20);
    /** The server's ticks of each device per turn, before it parks (S8's fix loop after F4). */
    private static final int RACE_TICKS = 8;
    /** The race's floors per device. */
    private static final int RACE_MIN_FRAMES = 400, RACE_MIN_READBACKS = 400, RACE_MIN_RESETS = 20;

    /** One device of the race, its worker's counts and what the server and the fold pool did to it. */
    private static final class RaceLane {

        final DeviceKey key;
        final DeviceCaps caps;
        final long seed;
        /** Frames the folds confirmed (frameDone, 21:87). */
        final AtomicLong confirmed = new AtomicLong();
        /** The worker's callbacks so far, which the server's resets follow. */
        final AtomicInteger callbacks = new AtomicInteger();
        /** Resets the server started while the worker ran, each at one of its marks. */
        final AtomicInteger resets = new AtomicInteger();
        /** The worker's waits ({@code pace}, {@code awaitReset}) that ran out of time. */
        final AtomicInteger waitsRanOut = new AtomicInteger();
        volatile boolean working = true;
        volatile Thread worker;
        Device device;

        RaceLane(DeviceKey key, DeviceCaps caps, long seed) {
            this.key = key;
            this.caps = caps;
            this.seed = seed;
        }

        @Override
        public String toString() {
            return String.format(
                Locale.ROOT,
                "%s: %d frames confirmed, %d resets raced, %d waits ran out",
                key,
                confirmed.get(),
                resets.get(),
                waitsRanOut.get());
        }
    }

    /**
     * The CPU twin of scenario 17: two devices of both formats, each driven by its own worker (20 000 random callbacks
     * with OC's retry), a render thread passing with random budgets, a server thread ticking each device
     * {@link #RACE_TICKS} times a turn and resetting each one at every {@link #RACE_RESET_EVERY}th callback of its
     * worker, and a two-thread fold pool (21:91). Every readback is checked by the shadow oracle as it arrives. On two
     * cores (S8's pinned runs, stricter than CI's runner) a worker would run its callbacks out, mostly "busy", before
     * the fold pool or the server got a turn; so a worker parks after a busy present until its device moves
     * ({@link #pace}) and at each reset mark until the server has started the reset ({@link #awaitReset}), the server
     * parks between its turns, the render thread yields after each pass, and the race must confirm at least
     * {@link #RACE_MIN_FRAMES} frames, check {@link #RACE_MIN_READBACKS} readbacks and start {@link #RACE_MIN_RESETS}
     * resets while each worker runs. Then the workers end, the server stops, the render thread quiesces on itself (H57)
     * and the pool drains, each within a bounded wait: no thread threw, the host never failed and saw no contract
     * violation, every readback matched, and each device's tail is empty with K's pixels and masters equal to the
     * oracle's and the GPU's textures its live and deferred ones (G10).
     */
    @Test
    @Timeout(120)
    void workersTheRenderThreadAServerThreadAndAFoldPoolRaceWithoutChangingResults() throws Exception {
        final OracleBackend backend = new OracleBackend();
        backend.latency = 2;
        final GlHostCore core = new GlHostCore(backend, options().forceFetchAfterPasses(3));
        final List<String> failures = Collections.synchronizedList(new ArrayList<String>());
        final ShadowOracle shadow = new ShadowOracle(SyntheticGlyphs.TABLE);
        final TeeRenderHost tee = new TeeRenderHost(core, shadow, failures);
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            2,
            2,
            0,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>(),
            r -> {
                Thread t = new Thread(r, "opengpu-fold");
                t.setDaemon(true);
                return t;
            });
        final ConcurrentLinkedQueue<Throwable> thrown = new ConcurrentLinkedQueue<>();
        // A fold that throws is recorded, not lost on a pool thread with its device's folds stopped
        final Executor folds = task -> pool.execute(() -> {
            try {
                task.run();
            } catch (Throwable e) {
                thrown.add(e);
            }
        });
        final List<RaceLane> lanes = Arrays.asList(
            new RaceLane(A, caps(new SurfaceDesc(0, 24, 16, Formats.INDEX8)), 0xC0FFEEL),
            new RaceLane(B, caps(new SurfaceDesc(0, 16, 12, Formats.RGB565)), 0xC0FFEFL));
        for (final RaceLane lane : lanes) {
            FrameListener counting = new FrameListener() {

                @Override
                public void frameDone(long frameId) {
                    lane.confirmed.incrementAndGet();
                    LockSupport.unpark(lane.worker);
                }

                @Override
                public void frameDropped(long frameId) {}
            };
            lane.device = Device.create(lane.key, lane.caps, tee, folds, counting, System::nanoTime);
        }
        final CountDownLatch workersDone = new CountDownLatch(2);
        final CountDownLatch renderDone = new CountDownLatch(1);
        final AtomicBoolean stopServer = new AtomicBoolean();
        final AtomicBoolean stopRender = new AtomicBoolean();
        final AtomicLong ticks = new AtomicLong();
        long began = System.nanoTime();
        final Thread server = new Thread(() -> {
            try {
                int[] nextReset = { RACE_RESET_EVERY, RACE_RESET_EVERY };
                for (int i = 0; i < 50_000_000 && !stopServer.get(); i++) {
                    for (int d = 0; d < 2; d++) {
                        RaceLane lane = lanes.get(d);
                        int done = lane.callbacks.get();
                        if (lane.working && done >= nextReset[d]) {
                            nextReset[d] = done + RACE_RESET_EVERY;
                            // The worker waits at its mark until the reset starts, then races it
                            lane.resets.incrementAndGet();
                            LockSupport.unpark(lane.worker);
                            lane.device.reset();
                            LockSupport.unpark(lane.worker);
                        }
                    }
                    // Then a burst of ticks, each device's in turn, which take its monitor and deliver its notices
                    // against the callbacks and the folds; a yield between them lets them interleave on this core too
                    for (int t = 0; t < 2 * RACE_TICKS; t++) {
                        lanes.get(t & 1).device.tick();
                        Thread.yield();
                    }
                    ticks.addAndGet(2 * RACE_TICKS);
                    // Woken by a worker at each of its reset marks, else about every millisecond
                    LockSupport.parkNanos(1_000_000);
                }
            } catch (Throwable e) {
                thrown.add(e);
            }
        }, "server");
        List<Thread> threads = new ArrayList<>();
        for (final RaceLane lane : lanes) {
            threads.add(new Thread(() -> {
                try {
                    work(lane, RACE_CALLBACKS, server, thrown);
                } catch (Throwable e) {
                    thrown.add(e);
                } finally {
                    lane.working = false;
                    workersDone.countDown();
                }
            }, "worker " + lane.key));
        }
        threads.add(server);
        threads.add(new Thread(() -> {
            try {
                SplittableRandom r = new SplittableRandom(17);
                for (int i = 0; i < 50_000_000 && !stopRender.get(); i++) {
                    int k = r.nextInt(4);
                    core.pass(k == 0 ? PassBudget.unlimited() : PassBudget.units(k));
                    // Where a render loop draws its frame: a yield gives way on this thread's own core only, so on
                    // two cores the loop still takes most of one (S8's fix loop after F4)
                    Thread.yield();
                }
                // The quiesce runs on the GL thread itself (H57), once the server has stopped resetting
                for (int i = 0; i < 200 && !(core.idle(A) && core.idle(B)); i++) {
                    core.pass(PassBudget.unlimited());
                }
            } catch (Throwable e) {
                thrown.add(e);
            } finally {
                renderDone.countDown();
            }
        }, "render"));
        for (Thread t : threads) {
            t.setDaemon(true);
            t.start();
        }
        try {
            Assertions.assertTrue(workersDone.await(60, TimeUnit.SECONDS), "the workers did not finish within 60 s");
        } finally {
            stopServer.set(true);
            server.join(10_000);
            stopRender.set(true);
        }
        Assertions.assertFalse(server.isAlive(), "the server thread still runs 10 s after the stop");
        Assertions.assertTrue(renderDone.await(30, TimeUnit.SECONDS), "the render thread did not quiesce within 30 s");
        for (Thread t : threads) {
            t.join(10_000);
            Assertions.assertFalse(t.isAlive(), t.getName() + " still runs 10 s after the stop");
        }
        pool.shutdown();
        Assertions.assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "the fold pool did not drain within 10 s");
        String counts = String.format(
            Locale.ROOT,
            "%d ms, %d passes, %d server ticks; %s, %d readbacks checked; %s, %d readbacks checked",
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began),
            core.passes(),
            ticks.get(),
            lanes.get(0),
            shadow.readbacksChecked(A),
            lanes.get(1),
            shadow.readbacksChecked(B));
        System.out.println("GlHostCoreTest race: " + counts);
        if (!thrown.isEmpty()) {
            throw new AssertionError("a thread threw", thrown.peek());
        }
        Assertions.assertEquals(Collections.emptyList(), failures);
        Assertions.assertNull(core.failure());
        Assertions.assertEquals(0, core.contractViolations());
        for (RaceLane lane : lanes) {
            DeviceKey key = lane.key;
            Assertions.assertTrue(core.idle(key));
            DeviceSnapshot snap = lane.device.snapshot();
            Assertions.assertTrue(snap.tail.isEmpty(), snap.toString());
            // Not vacuous on two cores either: frames, readbacks and resets went through each device as it raced
            Assertions
                .assertTrue(lane.confirmed.get() >= RACE_MIN_FRAMES, key + ": too few frames confirmed; " + counts);
            Assertions.assertTrue(
                shadow.readbacksChecked(key) >= RACE_MIN_READBACKS,
                key + ": too few readbacks checked; " + counts);
            Assertions.assertTrue(lane.resets.get() >= RACE_MIN_RESETS, key + ": too few resets raced; " + counts);
            assertPixels(lane.caps.surface, shadow.pixelsNow(key), snap.confirmedPixels());
            TierEOracle oracle = shadow.oracle(key);
            Assertions.assertEquals(oracle.textureCount(), snap.masters.size());
            for (MasterTexture m : snap.masters.values()) {
                byte[] texels = m.texels();
                int bpp = Formats.bytesPerPixel(m.format);
                for (int i = 0; i < m.width * m.height; i++) {
                    int value = bpp == 1 ? texels[i] & 0xFF : (texels[2 * i] & 0xFF) | (texels[2 * i + 1] & 0xFF) << 8;
                    if (oracle.texel(m.handle, i % m.width, i / m.width) != value) {
                        Assertions.fail(m + " differs from the oracle at texel " + i);
                    }
                }
            }
            DeviceStats s = core.stats(key);
            Assertions.assertEquals(snap.vramUsed + snap.deferredBytes, s.gpuTextureBytes, "G10: " + s);
            Assertions.assertEquals(snap.live.size() + snap.deferredCount, s.gpuTextures, "G10: " + s);
        }
        System.out.println("GlHostCoreTest race: " + core.stats(A) + ", " + core.stats(B));
    }

    /**
     * A worker: ops random callbacks through OC's retry, on the textures it was told of, each present paced, waking the
     * server at each of its reset marks; it stops early once another thread threw, which its waits would otherwise
     * stretch to the workers' bound.
     */
    private static void work(RaceLane lane, int ops, Thread server, Collection<Throwable> thrown) {
        lane.worker = Thread.currentThread();
        Device device = lane.device;
        SplittableRandom r = new SplittableRandom(lane.seed);
        RecordingBudget budget = new RecordingBudget();
        SurfaceDesc s = lane.caps.surface;
        int bpp = s.bytesPerPixel();
        Map<Integer, int[]> live = new LinkedHashMap<>();
        for (int i = 0; i < ops && thrown.isEmpty(); i++) {
            int resets = lane.resets.get();
            int roll = r.nextInt(100);
            if (roll < 15) {
                final int w = r.nextInt(1, 7);
                final int h = r.nextInt(1, 7);
                CallResult c = budget.call(() -> device.createTexture(budget, w, h, null));
                if (c.isOk()) {
                    live.put(c.handle(), new int[] { w, h });
                }
            } else if (roll < 40 && !live.isEmpty()) {
                List<Integer> handles = new ArrayList<>(live.keySet());
                final int tex = handles.get(r.nextInt(handles.size()));
                int[] size = live.get(tex);
                final int w = r.nextBoolean() ? size[0] : r.nextInt(1, size[0] + 1);
                final int h = r.nextBoolean() ? size[1] : r.nextInt(1, size[1] + 1);
                final int x = r.nextInt(size[0] - w + 1);
                final int y = r.nextInt(size[1] - h + 1);
                final byte[] data = new byte[w * h * bpp];
                for (int b = 0; b < data.length; b++) {
                    data[b] = (byte) r.nextInt(256);
                }
                CallResult c = budget.call(() -> device.updateTexture(budget, tex, x, y, w, h, data));
                if (!c.isOk() && c.message()
                    .contains("not live")) {
                    // a reset by the server thread freed it
                    live.remove(tex);
                }
            } else if (roll < 50 && !live.isEmpty()) {
                List<Integer> handles = new ArrayList<>(live.keySet());
                int tex = handles.get(r.nextInt(handles.size()));
                device.free(tex);
                live.remove(tex);
            } else if (roll < 75) {
                int colour = s.format == Formats.INDEX8 ? r.nextInt(256) : 0xFF000000 | r.nextInt(1 << 24);
                byte[] record;
                if (!live.isEmpty() && r.nextBoolean()) {
                    int tex = new ArrayList<>(live.keySet()).get(r.nextInt(live.size()));
                    record = blit(tex, 0, 0, 6, 6, r.nextInt(-3, s.width), r.nextInt(-3, s.height), r.nextInt(2));
                } else if (r.nextInt(10) == 0) {
                    record = clear(colour);
                } else {
                    record = fill(r.nextInt(-3, s.width), r.nextInt(-3, s.height), r.nextInt(8), r.nextInt(8), colour);
                }
                final byte[] stream = segment(record);
                budget.call(() -> device.submit(budget, stream));
            } else if (roll < 95) {
                long confirmed = lane.confirmed.get();
                long epoch = device.epoch();
                CallResult c = budget.call(() -> device.present(budget));
                if (!pace(lane, c, confirmed, epoch)) {
                    lane.waitsRanOut.incrementAndGet();
                }
            } else {
                budget.call(() -> device.readPixels(budget, 0, 0, s.width, 1));
            }
            if (lane.callbacks.incrementAndGet() % RACE_RESET_EVERY == 0 && !awaitReset(lane, server, resets)) {
                lane.waitsRanOut.incrementAndGet();
            }
        }
    }

    /**
     * At each of its reset marks the worker wakes the server and parks, for at most {@link #RACE_PACE_NANOS}, until the
     * server has started that reset, which then races the worker's next callbacks. Only woken, a server on two cores
     * can wait a long while for one, and the resets would then miss the worker.
     *
     * @return false when the bound ran out first
     */
    private static boolean awaitReset(RaceLane lane, Thread server, int resets) {
        LockSupport.unpark(server);
        long until = System.nanoTime() + RACE_PACE_NANOS;
        while (lane.resets.get() == resets) {
            long left = until - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            LockSupport.parkNanos(left);
        }
        return true;
    }

    /**
     * After a {@code "busy"} present (the cap of unconfirmed frames, 28:105-108) the worker waits for its device, as an
     * OC program that sleeps to the next tick, for at most {@link #RACE_PACE_NANOS}: until a fold confirms another
     * frame or a reset empties the tail, each of which unparks it. It parks rather than yields: on two cores a thread
     * that yields gives way on its own core only, and the fold pool and the server need the time.
     *
     * @return false when the bound ran out first
     */
    private static boolean pace(RaceLane lane, CallResult c, long confirmed, long epoch) {
        if (c.isOk() || !Statuses.BUSY.equals(c.message())) {
            return true;
        }
        long until = System.nanoTime() + RACE_PACE_NANOS;
        while (lane.confirmed.get() == confirmed && lane.device.epoch() == epoch) {
            long left = until - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            LockSupport.parkNanos(left);
        }
        return true;
    }

    // ---- Helpers

    static FrameRecord frame(long epoch, long id, byte[]... segments) {
        return new FrameRecord(epoch, id, segments, FrameTotals.EMPTY);
    }

    static TexCreateRecord create(long epoch, int handle, int w, int h) {
        return new TexCreateRecord(epoch, handle, w, h, Formats.INDEX8);
    }

    static TexUpdateRecord update(long epoch, int handle, int x, int y, int w, int h, byte[] payload) {
        return new TexUpdateRecord(epoch, handle, x, y, w, h, payload);
    }

    static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    /** A deterministic non-zero surface of the given layout. */
    static byte[] pattern(SurfaceDesc s, int seed) {
        byte[] out = new byte[s.byteSize()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (seed * 31 + i * 7 + 1);
        }
        return out;
    }

    /** Caps with the given surface, for a device in these tests. */
    static DeviceCaps caps(SurfaceDesc s) {
        return new DeviceCaps(
            new SurfaceDesc(s.format == Formats.INDEX8 ? 2 : 3, s.width, s.height, s.format),
            new FrameLimits(65536, 4096, 64, 100000),
            4096,
            8,
            32,
            4096,
            2);
    }

    /** The reference: the records run in offered order on a fresh oracle (28:126; O5). */
    static byte[] replay(SurfaceDesc s, List<? extends Record> records) {
        OracleRecordRenderer oracle = new OracleRecordRenderer();
        DeviceKey key = new DeviceKey(0, 0);
        oracle.open(key, s);
        for (Record r : records) {
            oracle.accept(key, r);
        }
        return oracle.pixels(key);
    }

    static void assertPixels(SurfaceDesc s, byte[] expected, byte[] actual) {
        String difference = ShadowOracle.firstDifference("readback", s, expected, actual);
        Assertions.assertNull(difference, difference);
    }

    /** Passes with an unlimited budget until the keys are idle, at most 32. */
    static int pumpUntilIdle(GlHostCore core, DeviceKey... keys) {
        for (int pumps = 1; pumps <= 32; pumps++) {
            core.pass(PassBudget.unlimited());
            boolean idle = true;
            for (DeviceKey key : keys) {
                idle &= core.idle(key);
            }
            if (idle) {
                return pumps;
            }
        }
        throw new AssertionError("not idle after 32 pumps: " + Arrays.toString(keys));
    }

    /** I-H1 as amendment D states it: resource records at the head only behind a FRAME the render thread took. */
    static void assertIH1(GlHostCore core, DeviceKey key) {
        HostDevice hd = core.device(key);
        Assertions.assertTrue(hd.headResources() == 0 || hd.trailing(), "I-H1: " + hd.queued());
    }

    /** The GPU texture's texels equal the mirror's. */
    static void assertGpuTexture(GlHostCore core, OracleBackend backend, DeviceKey key, int handle) {
        Mirror m = core.device(key)
            .mirror(handle);
        byte[] mirror = m.texels();
        OracleBackend.Device d = backend.device(key);
        for (int y = 0; y < m.height; y++) {
            for (int x = 0; x < m.width; x++) {
                int i = (y * m.width + x) * m.bpp;
                int value = m.bpp == 1 ? mirror[i] & 0xFF : (mirror[i] & 0xFF) | (mirror[i + 1] & 0xFF) << 8;
                Assertions.assertEquals(value, d.oracle.texel(handle, x, y), "texel (" + x + ", " + y + ")");
            }
        }
    }

    /**
     * A device on the key, one frame presented and confirmed through the host, then released and a pass run, which
     * deletes its objects (step (d)); the device is returned and held nowhere in the test.
     */
    private static Device openConfirmAndRelease(GlHostCore core, OracleBackend backend, DeviceKey key,
        DeviceCaps caps) {
        Device device = Device.create(key, caps, core, Runnable::run, FrameListener.NONE, System::nanoTime);
        RecordingBudget budget = new RecordingBudget();
        byte[] stream = segment(fill(1, 1, 4, 4, 0xFF123456));
        Assertions.assertTrue(
            budget.call(() -> device.submit(budget, stream))
                .isOk());
        CallResult presented = budget.call(() -> device.present(budget));
        Assertions.assertTrue(presented.isOk(), presented.toString());
        pumpUntilIdle(core, key);
        Assertions.assertEquals(
            presented.frameId(),
            device.snapshot().confirmedFrameId,
            key + ": the frame was not confirmed");
        device.release();
        core.pass(PassBudget.unlimited());
        Assertions.assertTrue(backend.device(key).destroyed, key + ": step (d) did not run");
        return device;
    }

    /** Collects garbage, at most 50 times 20 ms, until every reference is cleared; how many are not. */
    private static int awaitCleared(List<? extends WeakReference<?>> refs) throws InterruptedException {
        int reachable = refs.size();
        for (int i = 0; i < 50 && reachable > 0; i++) {
            System.gc();
            Thread.sleep(20);
            reachable = 0;
            for (WeakReference<?> ref : refs) {
                reachable += ref.get() == null ? 0 : 1;
            }
        }
        return reachable;
    }

    /** The host's lock, which open, release, close and a failure take (reflection: the field is private). */
    private static Object hostLock(GlHostCore core) throws ReflectiveOperationException {
        Field field = GlHostCore.class.getDeclaredField("hostLock");
        field.setAccessible(true);
        return field.get(core);
    }

    /** Waits, at most 5 s, until the thread is BLOCKED entering the monitor. */
    private static void awaitBlockedOn(Thread thread, Object monitor) throws InterruptedException {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            ThreadInfo info = threads.getThreadInfo(thread.getId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED
                && info.getLockInfo() != null
                && info.getLockInfo()
                    .getIdentityHashCode() == System.identityHashCode(monitor)) {
                return;
            }
            Thread.sleep(1);
        }
        throw new AssertionError(thread.getName() + " did not block on the monitor within 5 s");
    }

    /** Runs the action on a new thread and joins it within 5 s; what it threw, or null. */
    static Throwable onThread(Runnable action) throws InterruptedException {
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable e) {
                thrown.set(e);
            } finally {
                done.countDown();
            }
        }, "other thread");
        t.setDaemon(true);
        t.start();
        Assertions.assertTrue(done.await(5, TimeUnit.SECONDS), "the other thread did not finish within 5 s");
        return thrown.get();
    }
}
