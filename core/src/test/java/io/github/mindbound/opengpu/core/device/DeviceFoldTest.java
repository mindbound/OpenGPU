package io.github.mindbound.opengpu.core.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.ManualClock;
import io.github.mindbound.opengpu.core.fixtures.device.ManualExecutor;
import io.github.mindbound.opengpu.core.fixtures.device.OracleRecordRenderer;
import io.github.mindbound.opengpu.core.fixtures.device.RecordingListener;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FakeRenderHost;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.ReadbackSink;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.RecordRenderer;
import io.github.mindbound.opengpu.core.render.RenderHost;

/**
 * Readbacks and the fold (O6, 28:137; 28:155; 21:85-91, :229-231; H8; amendment B): the fold for readback n applies
 * the tail's records up to and including {@code FRAME n} to the masters, sets K = n with the readback's pixels, then
 * folds the resource records now at the tail's head, with one {@code done n} for every frame it confirms (21:87). The
 * device copies the delivered pixels before {@code onReadback} returns, into its own ring of at most three arrays, and
 * keeps at most one fold task queued: a newer arrival replaces the pending one, an arrival not newer than it is stale.
 * Readbacks of another epoch, of a frame at or before K, or of a released device are stale; of another key, of a
 * wrong length, of a null array, of a frame never presented, or refused by the executor (any throw from
 * {@code execute}), rejected (both counters are atomic, B10). {@code onReadback} never throws for these, beyond
 * rethrowing an executor's Error, and never waits for the device's monitor. Since the ring recycles K's old array,
 * readPixels and snapshot copy K's pixels under the monitor: neither a returned region nor a snapshot changes when
 * later readbacks fold, and a whole-surface read racing the folds is never torn.
 */
class DeviceFoldTest {

    private static final int T = 0x08000000;
    private static final int SURFACE = 320 * 200;
    /** The ring race's bounds: at least this many frames and reads, whichever ends later. */
    private static final int RACE_FRAMES = 2_000;
    private static final int RACE_READS = 20_000;
    /** Safety bounds behind them. */
    private static final int MAX_FRAMES = 500_000;
    private static final int MAX_READS = 1_000_000;

    @Test
    void theFoldRunsUpToFrameNAndThenTheHeadsResourceRecords() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        Assertions.assertEquals(T, rig.create(16, 16));
        byte[] old = DeviceOrderingTest.pattern(256, 1);
        rig.update(T, 0, 0, 16, 16, old);
        // the host runs the two resource records; FRAME 1 is the next one it takes
        Assertions.assertEquals(2, rig.host.run(rig.key, Integer.MAX_VALUE));
        rig.submit(segment(blit(T, 0, 0, 16, 16, 0, 0, 0)));
        Assertions.assertEquals(1L, rig.present());
        byte[] fresh = DeviceOrderingTest.pattern(256, 2);
        rig.update(T, 0, 0, 16, 16, fresh);
        rig.submit(segment(blit(T, 0, 0, 16, 16, 30, 0, 0)));
        Assertions.assertEquals(2L, rig.present());
        rig.free(T);
        DeviceSnapshot s = DeviceOrderingTest.check(rig, history);
        Assertions
            .assertEquals(Arrays.asList(Record.FRAME, Record.TEX_UPDATE, Record.FRAME, Record.TEX_FREE), kinds(s.tail));
        Assertions.assertEquals(64 + 256 + 64, s.queuedBytes);
        Assertions.assertArrayEquals(
            old,
            s.masters.get(T)
                .texels(),
            "the masters lag behind the tail");
        // readback 1: FRAME 1 folds, then the TEX_UPDATE now at the head
        Assertions.assertEquals(1, rig.host.run(rig.key, 1));
        Assertions.assertTrue(rig.host.readback(rig.key));
        s = DeviceOrderingTest.check(rig, history);
        Assertions.assertEquals(1, s.confirmedFrameId);
        Assertions.assertEquals(Arrays.asList(Record.FRAME, Record.TEX_FREE), kinds(s.tail));
        Assertions.assertEquals(64, s.queuedBytes);
        Assertions.assertEquals(1, s.unconfirmedFrames);
        Assertions.assertArrayEquals(
            fresh,
            s.masters.get(T)
                .texels());
        Assertions.assertArrayEquals(
            rig.host.readbacks(rig.key)
                .get(0).pixels,
            s.confirmedPixels());
        Assertions.assertEquals(Collections.singletonList("done 1"), rig.listener.events());
        // readback 2: FRAME 2, then the TEX_FREE
        rig.step();
        s = DeviceOrderingTest.check(rig, history);
        Assertions.assertEquals(2, s.confirmedFrameId);
        Assertions.assertTrue(s.tail.isEmpty());
        Assertions.assertEquals(0, s.queuedBytes);
        Assertions.assertTrue(s.masters.isEmpty());
        Assertions.assertEquals(Arrays.asList("done 1", "done 2"), rig.listener.events());
        Assertions.assertEquals(0, s.staleReadbacks);
        Assertions.assertEquals(0, s.rejectedReadbacks);
    }

    @Test
    void oneFoldConfirmsEveryFrameUpToItWithOneDone() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        rig.submit(segment(clear(1)));
        rig.present();
        rig.submit(segment(clear(2)));
        rig.present();
        rig.step();
        DeviceSnapshot s = DeviceOrderingTest.check(rig, history);
        Assertions.assertEquals(2, s.confirmedFrameId);
        Assertions.assertEquals(Collections.singletonList("done 2"), rig.listener.events(), "21:87: coalesced");
        Assertions.assertEquals(
            1,
            rig.host.readbacks(rig.key)
                .size());
        Assertions.assertEquals(2, rig.screen()[0]);
    }

    /** Amendment B: the host keeps its array and may reuse it as soon as onReadback returns. */
    @Test
    void theDeliveredArrayIsCopiedBeforeOnReadbackReturns() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.present();
        byte[] px = DeviceOrderingTest.pattern(SURFACE, 5);
        byte[] copy = px.clone();
        rig.device.onReadback(1, 1, 1, 1, px);
        Arrays.fill(px, (byte) 0x55);
        DeviceSnapshot s = Invariants.check(rig.snapshot(), rig.device.caps());
        Assertions.assertEquals(1, s.confirmedFrameId);
        Assertions.assertArrayEquals(copy, s.confirmedPixels());
        Assertions.assertArrayEquals(copy, rig.screen());
        // the ring reuses the device's own arrays: two more readbacks into the same host array stay distinct
        rig.present();
        byte[] p2 = DeviceOrderingTest.pattern(SURFACE, 6);
        System.arraycopy(p2, 0, px, 0, SURFACE);
        rig.device.onReadback(1, 1, 1, 2, px);
        rig.present();
        byte[] p3 = DeviceOrderingTest.pattern(SURFACE, 7);
        System.arraycopy(p3, 0, px, 0, SURFACE);
        CallResult before = rig.device.readPixels(rig.budget, 0, 0, 320, 100);
        rig.device.onReadback(1, 1, 1, 3, px);
        Arrays.fill(px, (byte) 0);
        Assertions.assertArrayEquals(p3, rig.screen());
        Assertions.assertArrayEquals(Arrays.copyOf(p2, 32000), before.data(), "a returned region is never written");
        Assertions.assertEquals(2, before.frameId());
    }

    /**
     * B12 with amendment B's ring: a whole-surface readPixels returns a fresh array, never K's, which the ring recycles
     * and refills with the readbacks after the next fold, so the result keeps frame 1's pixels while frames 2 and 3
     * fold.
     */
    @Test
    void aWholeSurfaceReadIsNoneOfTheRingsArrays() {
        FakeRenderHost host = new FakeRenderHost(new OracleRecordRenderer());
        DeviceRig rig = DeviceRig.on(wholeSurfaceReads(), host, host::step);
        byte[] p1 = DeviceOrderingTest.pattern(SURFACE, 11);
        rig.present();
        rig.device.onReadback(1, 1, 1, 1, p1);
        CallResult first = rig.device.readPixels(rig.budget, 0, 0, 320, 200);
        CallResult again = rig.device.readPixels(rig.budget, 0, 0, 320, 200);
        Assertions.assertArrayEquals(p1, first.data());
        Assertions.assertNotSame(first.data(), again.data());
        Assertions.assertNotSame(p1, first.data());
        for (int n = 2; n <= 4; n++) {
            rig.present();
            rig.device.onReadback(1, 1, 1, n, DeviceOrderingTest.pattern(SURFACE, 11 + n));
            Assertions.assertEquals(n, rig.snapshot().confirmedFrameId);
        }
        Assertions.assertArrayEquals(p1, first.data(), "a later fold wrote into a returned array");
        Assertions.assertArrayEquals(p1, again.data());
        Assertions.assertArrayEquals(DeviceOrderingTest.pattern(SURFACE, 15), rig.screen());
    }

    /**
     * A snapshot copies K's pixels (24:181: M1's save writes it later): after the fold of readback 2 recycles K = 1's
     * array as the spare and readback 3 is copied into it, the snapshot taken at K = 1 still holds frame 1's pixels.
     */
    @Test
    void aSnapshotKeepsKsPixelsWhileLaterReadbacksFold() {
        DeviceRig rig = DeviceRig.tier(2);
        byte[] p1 = DeviceOrderingTest.pattern(SURFACE, 1);
        rig.present();
        rig.device.onReadback(1, 1, 1, 1, p1);
        DeviceSnapshot first = rig.snapshot();
        Assertions.assertEquals(1, first.confirmedFrameId);
        Assertions.assertArrayEquals(p1, first.confirmedPixels());
        for (int n = 2; n <= 3; n++) {
            rig.present();
            rig.device.onReadback(1, 1, 1, n, DeviceOrderingTest.pattern(SURFACE, n));
            Assertions.assertEquals(n, rig.snapshot().confirmedFrameId);
        }
        Assertions.assertArrayEquals(
            DeviceOrderingTest.pattern(SURFACE, 3),
            rig.snapshot()
                .confirmedPixels());
        Assertions.assertEquals(1, first.confirmedFrameId);
        Assertions.assertArrayEquals(p1, first.confirmedPixels(), "a later readback was copied into the snapshot");
    }

    /**
     * Amendment B: readPixels copies K's rows under the monitor, since a fold recycles K's old array as the spare and
     * the next readback is copied into it. A presenter clears each frame to its id &amp; 0xFF, presents and steps the
     * host, whose readbacks fold on a two-thread pool (21:91), while a reader reads the whole surface (maxReadbackBytes
     * is its size): every read is uniform and equals its K &amp; 0xFF, 0 for K = 0. On {@code "busy"} the presenter
     * waits for the next {@code done} rather than spinning, which on a two-core runner would starve the folds. Bounded
     * by counts, not time: 2 000 frames and 20 000 reads, whichever ends later, so the reads race the folds throughout.
     */
    @Test
    @Timeout(120)
    void wholeSurfaceReadsRacingTheFoldsAreNeverTorn() throws Exception {
        long started = System.nanoTime();
        FakeRenderHost host = new FakeRenderHost(new FrameIdRenderer());
        host.setKeepHistory(false);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        // a fold that throws is recorded, not lost on a pool thread
        Executor folds = task -> pool.execute(() -> {
            try {
                task.run();
            } catch (Throwable e) {
                failures.add(e);
            }
        });
        Semaphore dones = new Semaphore(0);
        FrameListener listener = new FrameListener() {

            @Override
            public void frameDone(long frameId) {
                dones.release();
            }

            @Override
            public void frameDropped(long frameId) {}
        };
        DeviceCaps caps = wholeSurfaceReads();
        Device device = Device.create(DeviceRig.KEY, caps, host, folds, listener, new ManualClock());
        AtomicLong presented = new AtomicLong();
        AtomicLong reads = new AtomicLong();
        AtomicLong moves = new AtomicLong();
        AtomicLong bad = new AtomicLong();
        AtomicReference<String> firstBad = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean();
        Thread presenter = new Thread(() -> {
            try {
                boolean open = false;
                // each pass presents a frame or takes a done, of which there is at most one per frame
                while (failures.isEmpty() && presented.get() < MAX_FRAMES
                    && (presented.get() < RACE_FRAMES || reads.get() < RACE_READS)) {
                    long next = presented.get() + 1;
                    if (!open) {
                        ok(device.submit(FREE, segment(clear((int) (next & 0xFF)))));
                        open = true;
                    }
                    CallResult r = device.present(FREE);
                    host.step();
                    if (r.isOk()) {
                        Assertions.assertEquals(next, r.frameId());
                        presented.set(next);
                        open = false;
                    } else {
                        // two frames unconfirmed, now both run and the newer read back: its done is due
                        Assertions.assertEquals(Statuses.BUSY, r.message());
                        Assertions.assertTrue(dones.tryAcquire(10, TimeUnit.SECONDS), "no fold within 10 s");
                    }
                }
            } catch (Throwable e) {
                failures.add(e);
            }
        }, "presenter");
        Thread reader = new Thread(() -> {
            try {
                long last = 0;
                for (int i = 0; i < MAX_READS && !stop.get() && failures.isEmpty(); i++) {
                    CallResult r = ok(device.readPixels(FREE, 0, 0, 320, 200));
                    byte[] px = r.data();
                    byte want = (byte) r.frameId();
                    for (int j = 0; j < px.length; j++) {
                        if (px[j] != want) {
                            if (bad.incrementAndGet() == 1) {
                                firstBad.set(
                                    String.format(
                                        Locale.ROOT,
                                        "K = %d, byte %d is %d, the first %d and the last %d",
                                        r.frameId(),
                                        j,
                                        px[j] & 0xFF,
                                        px[0] & 0xFF,
                                        px[px.length - 1] & 0xFF));
                            }
                            break;
                        }
                    }
                    if (r.frameId() != last) {
                        moves.incrementAndGet();
                        last = r.frameId();
                    }
                    reads.incrementAndGet();
                }
            } catch (Throwable e) {
                failures.add(e);
            }
        }, "reader");
        boolean drained;
        try {
            presenter.start();
            reader.start();
            presenter.join(90_000);
            Assertions.assertFalse(presenter.isAlive(), "the presenter did not finish");
        } finally {
            stop.set(true);
            presenter.join(10_000);
            reader.join(10_000);
            pool.shutdown();
            drained = pool.awaitTermination(10, TimeUnit.SECONDS);
        }
        Assertions.assertFalse(reader.isAlive(), "the reader did not stop");
        Assertions.assertTrue(drained, "the fold pool drained");
        Throwable first = failures.peek();
        if (first != null) {
            throw new AssertionError(failures.size() + " thread(s) threw; the first: " + first, first);
        }
        System.out.println(
            String.format(
                Locale.ROOT,
                "DeviceFoldTest: %.2f s, %d frames, %d whole-surface reads, K moved %d times under the reader",
                (System.nanoTime() - started) / 1e9,
                presented.get(),
                reads.get(),
                moves.get()));
        Assertions
            .assertEquals(0, bad.get(), bad.get() + " of " + reads.get() + " reads torn; the first: " + firstBad.get());
        Assertions.assertTrue(presented.get() >= RACE_FRAMES, presented.get() + " frames presented");
        Assertions.assertTrue(reads.get() >= RACE_READS, reads.get() + " reads");
        Invariants.check(device.snapshot(), caps);
    }

    @Test
    void aReadbackAtOrBeforeKIsStale() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.present();
        rig.present();
        rig.step();
        Assertions.assertEquals(2, rig.snapshot().confirmedFrameId);
        String before = stateWithoutCounters(rig.snapshot());
        rig.device.onReadback(1, 1, 1, 2, new byte[SURFACE]);
        rig.device.onReadback(1, 1, 1, 1, new byte[SURFACE]);
        rig.device.onReadback(1, 1, 1, 0, new byte[SURFACE]);
        DeviceSnapshot s = Invariants.check(rig.snapshot(), rig.device.caps());
        Assertions.assertEquals(3, s.staleReadbacks);
        Assertions.assertEquals(0, s.rejectedReadbacks);
        Assertions.assertEquals(before, stateWithoutCounters(s));
        Assertions.assertEquals(Collections.singletonList("done 2"), rig.listener.events());
    }

    @Test
    void aReadbackOfAFrameNeverPresentedIsRejected() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.present();
        rig.present();
        String before = stateWithoutCounters(rig.snapshot());
        rig.device.onReadback(1, 1, 1, 3, new byte[SURFACE]);
        rig.device.onReadback(1, 1, 1, Long.MAX_VALUE, new byte[SURFACE]);
        DeviceSnapshot s = Invariants.check(rig.snapshot(), rig.device.caps());
        Assertions.assertEquals(2, s.rejectedReadbacks);
        Assertions.assertEquals(0, s.staleReadbacks);
        Assertions.assertEquals(before, stateWithoutCounters(s));
        Assertions.assertTrue(
            rig.listener.events()
                .isEmpty());
        rig.step();
        Assertions.assertEquals(2, rig.snapshot().confirmedFrameId);
    }

    @Test
    void aReadbackOfAnotherKeyOrLengthOrNoArrayIsRejectedWithoutAFold() {
        ManualExecutor folds = new ManualExecutor();
        DeviceRig rig = manualRig(folds);
        rig.present();
        String before = stateWithoutCounters(rig.snapshot());
        rig.device.onReadback(2, 1, 1, 1, new byte[SURFACE]);
        rig.device.onReadback(1, 2, 1, 1, new byte[SURFACE]);
        rig.device.onReadback(1, 1, 1, 1, new byte[SURFACE - 1]);
        rig.device.onReadback(1, 1, 1, 1, new byte[SURFACE + 1]);
        rig.device.onReadback(1, 1, 1, 1, new byte[0]);
        rig.device.onReadback(1, 1, 1, 1, null);
        Assertions.assertEquals(0, folds.pending(), "nothing scheduled");
        DeviceSnapshot s = Invariants.check(rig.snapshot(), rig.device.caps());
        Assertions.assertEquals(6, s.rejectedReadbacks);
        Assertions.assertEquals(0, s.staleReadbacks);
        Assertions.assertEquals(before, stateWithoutCounters(s));
        // the right readback still folds
        rig.device.onReadback(1, 1, 1, 1, new byte[SURFACE]);
        Assertions.assertEquals(1, folds.runAll());
        Assertions.assertEquals(1, rig.snapshot().confirmedFrameId);
    }

    @Test
    void aReadbackOfAnOlderEpochIsStale() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        rig.present();
        rig.device.reset();
        // the host ran FRAME 1 (epoch 1) and then the RESET: its readback is of epoch 1
        rig.step();
        DeviceSnapshot s = DeviceOrderingTest.check(rig, history);
        Assertions.assertEquals(
            1,
            rig.host.readbacks(rig.key)
                .get(0).epoch);
        Assertions.assertEquals(1, s.staleReadbacks);
        Assertions.assertEquals(0, s.confirmedFrameId);
        Assertions.assertTrue(
            rig.listener.events()
                .isEmpty());
        // nor does a direct one of the old epoch confirm frame 2 of the new one
        rig.present();
        Assertions.assertEquals(2, rig.snapshot().lastFrameId);
        rig.device.onReadback(1, 1, 1, 2, new byte[SURFACE]);
        s = Invariants.check(rig.snapshot(), rig.device.caps());
        Assertions.assertEquals(2, s.staleReadbacks);
        Assertions.assertEquals(0, s.confirmedFrameId);
        rig.step();
        Assertions.assertEquals(2, DeviceOrderingTest.check(rig, history).confirmedFrameId);
    }

    /** Amendment B: two readbacks before the fold runs give one queued task, one fold for the newer, one done. */
    @Test
    void aNewerReadbackReplacesThePendingOne() {
        ManualExecutor folds = new ManualExecutor();
        DeviceRig rig = manualRig(folds);
        Invariants.History history = Invariants.History.of(rig.device);
        upToFrame5(rig, folds);
        rig.submit(segment(clear(6)));
        Assertions.assertEquals(6L, rig.present());
        rig.submit(segment(clear(7)));
        Assertions.assertEquals(7L, rig.present());
        Assertions.assertEquals(1, rig.host.run(rig.key, 1));
        Assertions.assertTrue(rig.host.readback(rig.key));
        Assertions.assertEquals(1, folds.pending());
        rig.step();
        Assertions.assertEquals(1, folds.pending(), "at most one fold task queued per device");
        Assertions.assertEquals(5, rig.snapshot().confirmedFrameId);
        rig.listener.clear();
        Assertions.assertEquals(1, folds.runAll());
        DeviceSnapshot s = DeviceOrderingTest.check(rig, history);
        Assertions.assertEquals(7, s.confirmedFrameId);
        Assertions.assertTrue(s.tail.isEmpty());
        Assertions.assertEquals(Collections.singletonList("done 7"), rig.listener.events());
        Assertions.assertEquals(7, rig.screen()[0]);
        Assertions.assertEquals(0, s.rejectedReadbacks);
    }

    /** Plan §4.3's out-of-order folds under amendment B: readback 7, then readback 6, before the fold runs. */
    @Test
    void anArrivalOlderThanThePendingOneIsStale() {
        ManualExecutor folds = new ManualExecutor();
        DeviceRig rig = manualRig(folds);
        upToFrame5(rig, folds);
        rig.present();
        rig.present();
        byte[] p7 = DeviceOrderingTest.pattern(SURFACE, 7);
        rig.device.onReadback(1, 1, 1, 7, p7);
        rig.device.onReadback(1, 1, 1, 6, new byte[SURFACE]);
        rig.device.onReadback(1, 1, 1, 7, new byte[SURFACE]);
        Assertions.assertEquals(1, folds.pending());
        Assertions.assertEquals(2, rig.snapshot().staleReadbacks, "6 and the second 7 are not newer than 7");
        rig.listener.clear();
        folds.runAll();
        DeviceSnapshot s = Invariants.check(rig.snapshot(), rig.device.caps());
        Assertions.assertEquals(7, s.confirmedFrameId);
        Assertions.assertArrayEquals(p7, s.confirmedPixels());
        Assertions.assertEquals(Collections.singletonList("done 7"), rig.listener.events());
        // and after the fold an older one is stale by K
        rig.device.onReadback(1, 1, 1, 6, new byte[SURFACE]);
        folds.runAll();
        Assertions.assertEquals(3, rig.snapshot().staleReadbacks);
        Assertions.assertEquals(7, rig.snapshot().confirmedFrameId);
    }

    /** B10: a refused hand-off is counted, onReadback does not throw, and the next readback is scheduled again. */
    @Test
    void aRefusedHandOffIsRejectedAndTheNextOneFolds() {
        ManualExecutor folds = new ManualExecutor();
        DeviceRig rig = manualRig(folds);
        rig.present();
        folds.setRejecting(true);
        rig.device.onReadback(1, 1, 1, 1, new byte[SURFACE]);
        Assertions.assertEquals(0, folds.pending());
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertEquals(1, s.rejectedReadbacks);
        Assertions.assertEquals(0, s.confirmedFrameId);
        folds.setRejecting(false);
        byte[] px = DeviceOrderingTest.pattern(SURFACE, 3);
        rig.device.onReadback(1, 1, 1, 1, px);
        Assertions.assertEquals(1, folds.pending());
        folds.runAll();
        s = Invariants.check(rig.snapshot(), rig.device.caps());
        Assertions.assertEquals(1, s.confirmedFrameId);
        Assertions.assertArrayEquals(px, s.confirmedPixels());
        Assertions.assertEquals(1, s.rejectedReadbacks);
    }

    /**
     * Any other throw from execute refuses the hand-off too: onReadback swallows a RuntimeException, counts the
     * readback rejected, and the next readback schedules a fold again instead of finding one still scheduled.
     */
    @Test
    void aHandOffThatThrowsIsRejectedAndTheNextOneFolds() {
        FailingOnce folds = new FailingOnce(new IllegalStateException("the pool is broken"));
        DeviceRig rig = manualRig(folds);
        rig.present();
        Assertions.assertDoesNotThrow(() -> rig.device.onReadback(1, 1, 1, 1, new byte[SURFACE]));
        Assertions.assertTrue(folds.threw);
        Assertions.assertEquals(0, folds.queue.pending());
        Assertions.assertEquals(1, rig.snapshot().rejectedReadbacks);
        theNextReadbackFolds(rig, folds);
    }

    /** An Error from execute is undone and counted the same way, then rethrown to the host's thread. */
    @Test
    void aHandOffThatThrowsAnErrorIsRejectedAndRethrown() {
        PoolError error = new PoolError();
        FailingOnce folds = new FailingOnce(error);
        DeviceRig rig = manualRig(folds);
        rig.present();
        PoolError thrown = Assertions
            .assertThrows(PoolError.class, () -> rig.device.onReadback(1, 1, 1, 1, new byte[SURFACE]));
        Assertions.assertSame(error, thrown);
        Assertions.assertEquals(0, folds.queue.pending());
        Assertions.assertEquals(1, rig.snapshot().rejectedReadbacks);
        theNextReadbackFolds(rig, folds);
    }

    /**
     * With a direct executor the fold runs inside execute: what the fold throws (here from a listener that breaks
     * FrameListener's contract) propagates from onReadback and is no refused hand-off, so nothing counts rejected, the
     * fold's work stands and the next readback schedules a fold again.
     */
    @Test
    void aFoldThatThrowsInsideExecuteIsNoRefusal() {
        IllegalStateException broken = new IllegalStateException("the listener is broken");
        FrameListener listener = new FrameListener() {

            @Override
            public void frameDone(long frameId) {
                throw broken;
            }

            @Override
            public void frameDropped(long frameId) {}
        };
        Device device = Device.create(
            new DeviceKey(1, 1),
            DeviceCaps.forTier(2),
            new FakeRenderHost(new OracleRecordRenderer()),
            Runnable::run,
            listener,
            new ManualClock());
        for (long n = 1; n <= 2; n++) {
            Assertions.assertEquals(n, ok(device.present(FREE)).frameId());
            long frame = n;
            IllegalStateException thrown = Assertions
                .assertThrows(IllegalStateException.class, () -> device.onReadback(1, 1, 1, frame, new byte[SURFACE]));
            Assertions.assertSame(broken, thrown);
            DeviceSnapshot s = Invariants.check(device.snapshot(), device.caps());
            Assertions.assertEquals(n, s.confirmedFrameId);
            Assertions.assertEquals(0, s.rejectedReadbacks);
        }
    }

    @Test
    void aReleasedDeviceCountsReadbacksStale() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.present();
        rig.device.release();
        rig.device.onReadback(1, 1, 2, 1, new byte[SURFACE]);
        rig.device.onReadback(1, 1, 1, 1, new byte[SURFACE]);
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertEquals(2, s.staleReadbacks);
        Assertions.assertEquals(0, s.confirmedFrameId);
        Assertions.assertTrue(
            rig.listener.events()
                .isEmpty());
    }

    /**
     * 28:194, plan §3.10: onReadback takes no lock the device holds while it calls its host. A host whose offer blocks
     * holds a createTexture inside the device's monitor (shown by a snapshot that blocks on it); onReadback, with a
     * manual fold executor, still returns.
     */
    @Test
    @Timeout(30)
    void onReadbackDoesNotWaitForTheMonitor() throws Exception {
        BlockingHost host = new BlockingHost();
        ManualExecutor folds = new ManualExecutor();
        DeviceKey key = new DeviceKey(1, 1);
        Device device = Device
            .create(key, DeviceCaps.forTier(2), host, folds, new RecordingListener(), new ManualClock());
        Assertions.assertEquals(1L, ok(device.present(FREE)).frameId());
        AtomicReference<CallResult> created = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread creator = new Thread(() -> {
            try {
                created.set(device.createTexture(FREE, 1, 1, null));
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "creator");
        Thread snapshotter = new Thread(device::snapshot, "snapshotter");
        Thread reader = new Thread(() -> device.onReadback(1, 1, 1, 1, new byte[SURFACE]), "reader");
        host.armed = true;
        try {
            creator.start();
            Assertions.assertTrue(host.entered.await(10, TimeUnit.SECONDS), "the create reached the host's offer");
            snapshotter.start();
            while (snapshotter.getState() != Thread.State.BLOCKED) {
                Assertions.assertTrue(snapshotter.isAlive(), "the snapshot did not wait: the monitor is not held");
                Thread.yield();
            }
            reader.start();
            reader.join(5000);
            Assertions.assertFalse(reader.isAlive(), "onReadback waited for the device's monitor");
            Assertions.assertEquals(1, folds.pending());
        } finally {
            host.release.countDown();
            creator.join(10000);
            snapshotter.join(10000);
            reader.join(10000);
        }
        Assertions.assertNull(failure.get());
        Assertions.assertEquals(
            T,
            created.get()
                .handle());
        folds.runAll();
        Assertions.assertEquals(1, device.snapshot().confirmedFrameId);
    }

    // ---- helpers

    static final CallBudget FREE = new CallBudget() {

        @Override
        public void consume(double cost) {}

        @Override
        public void pause(double seconds) {}
    };

    /** T2 with maxReadbackBytes the surface's size, so that one readPixels reads it whole. */
    private static DeviceCaps wholeSurfaceReads() {
        return new DeviceCaps(SurfaceDesc.forTier(2), FrameLimits.forTier(2), 1048576, 256, 1024, SURFACE, 2);
    }

    private static DeviceRig manualRig(Executor folds) {
        FakeRenderHost host = new FakeRenderHost(new OracleRecordRenderer());
        return DeviceRig.on(DeviceCaps.forTier(2), host, host::step, folds);
    }

    /** Frames 1-5 presented, stepped and folded: K = 5, an empty tail. */
    private static void upToFrame5(DeviceRig rig, ManualExecutor folds) {
        for (int i = 1; i <= 5; i++) {
            rig.submit(segment(clear(i)));
            Assertions.assertEquals(i, rig.present());
            rig.step();
            folds.runAll();
        }
        Assertions.assertEquals(5, rig.snapshot().confirmedFrameId);
    }

    /** After a refused readback of frame 1 (K = 0): a second one schedules a fold, which confirms frame 1. */
    private static void theNextReadbackFolds(DeviceRig rig, FailingOnce folds) {
        Assertions.assertEquals(0, rig.snapshot().confirmedFrameId);
        byte[] px = DeviceOrderingTest.pattern(SURFACE, 4);
        rig.device.onReadback(1, 1, 1, 1, px);
        Assertions.assertEquals(1, folds.queue.pending(), "the refused hand-off left no fold scheduled");
        Assertions.assertEquals(1, folds.queue.runAll());
        DeviceSnapshot s = Invariants.check(rig.snapshot(), rig.device.caps());
        Assertions.assertEquals(1, s.confirmedFrameId);
        Assertions.assertArrayEquals(px, s.confirmedPixels());
        Assertions.assertEquals(1, s.rejectedReadbacks);
        Assertions.assertEquals(0, s.staleReadbacks);
        Assertions.assertEquals(Collections.singletonList("done 1"), rig.listener.events());
    }

    private static CallResult ok(CallResult r) {
        return DeviceOrderingTest.ok(r);
    }

    private static List<Integer> kinds(List<Record> records) {
        List<Integer> kinds = new ArrayList<>();
        for (Record r : records) {
            kinds.add(r.kind());
        }
        return kinds;
    }

    /** {@link Invariants#describe} without the two readback counters, which the tests assert on their own. */
    private static String stateWithoutCounters(DeviceSnapshot s) {
        String d = Invariants.describe(s);
        return d.substring(0, d.lastIndexOf(" stale "));
    }

    /**
     * What the oracle draws for the ring race's frames, the surface cleared to the frame's id &amp; 0xFF, without
     * running the clear pixel by pixel: a frame then costs about what a whole-surface read does, so that a read copying
     * outside the monitor would overlap a fold and the next readback often (a two-core run of that mutant tears
     * hundreds of reads with this renderer and a few dozen at most with the oracle).
     */
    private static final class FrameIdRenderer implements RecordRenderer {

        private int size;
        private byte value;

        @Override
        public void open(DeviceKey key, SurfaceDesc surface) {
            size = surface.byteSize();
            value = 0;
        }

        @Override
        public void accept(DeviceKey key, Record record) {
            if (record.kind() == Record.FRAME) {
                value = (byte) ((FrameRecord) record).frameId;
            }
        }

        @Override
        public byte[] pixels(DeviceKey key) {
            byte[] px = new byte[size];
            Arrays.fill(px, value);
            return px;
        }
    }

    /** A fold executor whose first execute throws the given exception; later ones queue on a {@link ManualExecutor}. */
    private static final class FailingOnce implements Executor {

        final ManualExecutor queue = new ManualExecutor();
        private final Throwable failure;
        boolean threw;

        FailingOnce(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void execute(Runnable task) {
            if (!threw) {
                threw = true;
                if (failure instanceof Error) {
                    throw (Error) failure;
                }
                throw (RuntimeException) failure;
            }
            queue.execute(task);
        }
    }

    /** An Error a pool might throw, such as when it cannot start a thread. */
    private static final class PoolError extends Error {

        private static final long serialVersionUID = 1L;

        PoolError() {
            super("unable to start a fold thread");
        }
    }

    /** A host whose offer, once armed, signals and then waits for the test; everything else does nothing. */
    private static final class BlockingHost implements RenderHost {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean armed;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public void open(DeviceKey key, SurfaceDesc surface, ReadbackSink sink) {}

        @Override
        public void offer(DeviceKey key, Record record) {
            if (armed) {
                entered.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread()
                        .interrupt();
                }
            }
        }

        @Override
        public void release(DeviceKey key) {}

        @Override
        public void bindDisplay(String displayId, DeviceKey key) {}

        @Override
        public void releaseSession(long session) {}
    }
}
