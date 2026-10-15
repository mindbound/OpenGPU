package io.github.mindbound.opengpu.core.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;

/**
 * The test host of 28:205 (23:165, :182; plan §2.1, H17; amendments B11, B15): offers queued per device and run only
 * when a test steps, in offered order (O5), through a pluggable renderer; one readback per device and step for the
 * newest {@code FRAME} run, with that frame's epoch; the finer {@code run}/{@code readback} split; the optional skip of
 * stale epochs (28:155); release and a later open of the same key (28:199); the root's {@code bindDisplay} and
 * {@code releaseSession}; and {@code onReadback} called outside the host's lock.
 */
class FakeRenderHostTest {

    private static final SurfaceDesc SURFACE = new SurfaceDesc(2, 4, 2, Formats.INDEX8);
    private static final DeviceKey A = new DeviceKey(1, 1);
    private static final DeviceKey B = new DeviceKey(1, 2);

    @Test
    void stepRunsEachOpenKeyInOfferedOrderThenReadsItBack() {
        LogRenderer renderer = new LogRenderer();
        FakeRenderHost host = new FakeRenderHost(renderer);
        LogSink sink = new LogSink();
        host.open(A, SURFACE, sink);
        host.open(B, SURFACE, sink);
        Record c = new TexCreateRecord(1, 0x08000000, 1, 1, Formats.INDEX8);
        Record a1 = frame(1, 1);
        Record b1 = frame(1, 1);
        Record free = new TexFreeRecord(1, 0x08000000);
        Record a2 = frame(1, 2);
        host.offer(A, c);
        host.offer(A, a1);
        host.offer(B, b1);
        host.offer(A, free);
        host.offer(A, a2);
        Assertions.assertEquals(4, host.queued(A));
        Assertions.assertEquals(1, host.queued(B));
        Assertions.assertEquals(Arrays.asList(c, a1, free, a2), host.offered(A));
        Assertions.assertTrue(
            host.ran(A)
                .isEmpty());
        // nothing runs until a step
        Assertions.assertEquals(Arrays.asList("open DeviceKey[1:1]", "open DeviceKey[1:2]"), renderer.log());

        Assertions.assertEquals(5, host.step());
        Assertions.assertEquals(
            Arrays.asList(
                "open DeviceKey[1:1]",
                "open DeviceKey[1:2]",
                "accept DeviceKey[1:1] TEX_CREATE e1",
                "accept DeviceKey[1:1] FRAME e1 #1",
                "accept DeviceKey[1:1] TEX_FREE e1",
                "accept DeviceKey[1:1] FRAME e1 #2",
                "pixels DeviceKey[1:1]",
                "accept DeviceKey[1:2] FRAME e1 #1",
                "pixels DeviceKey[1:2]"),
            renderer.log());
        Assertions.assertEquals(Arrays.asList("1:1 e1 #2 4", "1:2 e1 #1 1"), sink.log());
        Assertions.assertEquals(host.offered(A), host.ran(A));
        Assertions.assertEquals(Collections.singletonList(b1), host.ran(B));
        Assertions.assertEquals(0, host.queued(A));
        Assertions.assertEquals(0, host.queued(B));
    }

    @Test
    void theReadbackIsTheNewestFrameRunWithItsEpochOncePerStep() {
        FakeRenderHost host = new FakeRenderHost(new LogRenderer());
        LogSink sink = new LogSink();
        host.open(A, SURFACE, sink);
        host.offer(A, frame(1, 1));
        host.offer(A, frame(1, 2));
        Assertions.assertEquals(2, host.step(A));
        Assertions.assertEquals(Collections.singletonList("1:1 e1 #2 2"), sink.log());
        // nothing new ran: no readback
        Assertions.assertEquals(0, host.step(A));
        Assertions.assertFalse(host.readback(A));
        Assertions.assertEquals(
            1,
            sink.log()
                .size());
        // a RESET and a frame of the next epoch
        host.offer(A, new ResetRecord(2, new byte[SURFACE.byteSize()]));
        host.offer(A, frame(2, 3));
        Assertions.assertEquals(2, host.step());
        Assertions.assertEquals(Arrays.asList("1:1 e1 #2 2", "1:1 e2 #3 4"), sink.log());
        List<FakeRenderHost.Readback> readbacks = host.readbacks(A);
        Assertions.assertEquals(2, readbacks.size());
        Assertions.assertEquals(1, readbacks.get(0).epoch);
        Assertions.assertEquals(2, readbacks.get(0).frameId);
        Assertions.assertEquals(2, readbacks.get(1).epoch);
        Assertions.assertEquals(3, readbacks.get(1).frameId);
        // the delivered array itself, which the sink copies (amendment B)
        Assertions.assertSame(sink.arrays.get(0), readbacks.get(0).pixels);
        Assertions.assertSame(sink.arrays.get(1), readbacks.get(1).pixels);
        Assertions.assertEquals(
            "Readback[e2 #3, 8 B]",
            readbacks.get(1)
                .toString());
    }

    @Test
    void thereIsNoReadbackWithoutAFrame() {
        FakeRenderHost host = new FakeRenderHost(new LogRenderer());
        LogSink sink = new LogSink();
        host.open(A, SURFACE, sink);
        Assertions.assertFalse(host.readback(A));
        host.offer(A, new TexCreateRecord(1, 0x08000000, 1, 1, Formats.INDEX8));
        host.offer(A, new TexFreeRecord(1, 0x08000000));
        Assertions.assertEquals(2, host.step());
        Assertions.assertTrue(
            sink.log()
                .isEmpty());
        Assertions.assertTrue(
            host.readbacks(A)
                .isEmpty());
    }

    @Test
    void theDefaultRendererKeepsEverySurfaceZeroed() {
        FakeRenderHost host = new FakeRenderHost();
        Assertions.assertEquals(
            "RecordRenderer.zeros()",
            host.renderer()
                .toString());
        LogSink sink = new LogSink();
        SurfaceDesc t3 = new SurfaceDesc(3, 4, 2, Formats.RGB565);
        host.open(A, t3, sink);
        host.offer(A, new TexCreateRecord(1, 0x08000000, 1, 1, Formats.RGB565));
        host.offer(A, new TexUpdateRecord(1, 0x08000000, 0, 0, 1, 1, new byte[] { 1, 2 }));
        host.offer(A, frame(1, 1, new byte[] { 1, 2, 3 }));
        host.step();
        Assertions.assertArrayEquals(new byte[16], sink.arrays.get(0));
        Assertions.assertEquals(Collections.singletonList("1:1 e1 #1 0"), sink.log());
        Assertions.assertEquals(16, sink.arrays.get(0).length);
    }

    @Test
    void theZerosRendererGivesFreshArraysForOpenKeysOnly() {
        RecordRenderer zeros = RecordRenderer.zeros();
        Assertions.assertThrows(IllegalStateException.class, () -> zeros.pixels(A));
        zeros.open(A, SURFACE);
        byte[] first = zeros.pixels(A);
        Assertions.assertArrayEquals(new byte[8], first);
        first[0] = 5;
        Assertions.assertNotSame(first, zeros.pixels(A));
        Assertions.assertArrayEquals(new byte[8], zeros.pixels(A));
        zeros.accept(A, frame(1, 1));
        Assertions.assertArrayEquals(new byte[8], zeros.pixels(A));
        zeros.release(A);
        Assertions.assertThrows(IllegalStateException.class, () -> zeros.pixels(A));
        // a later open starts afresh (H17)
        zeros.open(A, new SurfaceDesc(3, 2, 2, Formats.RGB565));
        Assertions.assertArrayEquals(new byte[8], zeros.pixels(A));
        // two zeros() are independent
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> RecordRenderer.zeros()
                .pixels(A));
    }

    @Test
    void runAndReadbackSplitAStep() {
        FakeRenderHost host = new FakeRenderHost(new LogRenderer());
        LogSink sink = new LogSink();
        host.open(A, SURFACE, sink);
        Record f1 = frame(1, 1);
        Record c = new TexCreateRecord(1, 0x08000000, 1, 1, Formats.INDEX8);
        Record f2 = frame(1, 2);
        host.offer(A, f1);
        host.offer(A, c);
        host.offer(A, f2);
        Assertions.assertEquals(1, host.run(A, 1));
        Assertions.assertEquals(2, host.queued(A));
        Assertions.assertEquals(Collections.singletonList(f1), host.ran(A));
        Assertions.assertTrue(
            sink.log()
                .isEmpty());
        Assertions.assertTrue(host.readback(A));
        Assertions.assertEquals(Collections.singletonList("1:1 e1 #1 1"), sink.log());
        Assertions.assertFalse(host.readback(A));
        Assertions.assertEquals(0, host.run(A, 0));
        Assertions.assertEquals(2, host.run(A, 5));
        Assertions.assertEquals(0, host.run(A, Integer.MAX_VALUE));
        Assertions.assertTrue(host.readback(A));
        Assertions.assertEquals(Arrays.asList("1:1 e1 #1 1", "1:1 e1 #2 3"), sink.log());
        Assertions.assertThrows(IllegalArgumentException.class, () -> host.run(A, -1));
    }

    @Test
    void skippingStaleEpochsTakesButDoesNotRunOlderRecords() {
        // 28:155: a host may skip records whose epoch is below the newest offered for the device
        Record f1 = frame(1, 1);
        Record c = new TexCreateRecord(1, 0x08000000, 1, 1, Formats.INDEX8);
        Record reset = new ResetRecord(2, new byte[SURFACE.byteSize()]);
        Record f2 = frame(2, 2);
        LogRenderer renderer = new LogRenderer();
        FakeRenderHost host = new FakeRenderHost(renderer);
        LogSink sink = new LogSink();
        host.setSkipStaleEpochs(true);
        host.open(A, SURFACE, sink);
        for (Record r : new Record[] { f1, c, reset, f2 }) {
            host.offer(A, r);
        }
        Assertions.assertEquals(4, host.run(A, Integer.MAX_VALUE));
        Assertions.assertEquals(Arrays.asList(f1, c, reset, f2), host.offered(A));
        Assertions.assertEquals(Arrays.asList(reset, f2), host.ran(A));
        Assertions.assertTrue(host.readback(A));
        Assertions.assertEquals(Collections.singletonList("1:1 e2 #2 2"), sink.log());

        // without the skip every record runs
        FakeRenderHost plain = new FakeRenderHost(new LogRenderer());
        plain.open(A, SURFACE, new LogSink());
        for (Record r : new Record[] { f1, c, reset, f2 }) {
            plain.offer(A, r);
        }
        Assertions.assertEquals(4, plain.step());
        Assertions.assertEquals(Arrays.asList(f1, c, reset, f2), plain.ran(A));

        // the skip compares with what has been offered when the record is taken
        FakeRenderHost early = new FakeRenderHost(new LogRenderer());
        early.setSkipStaleEpochs(true);
        early.open(A, SURFACE, new LogSink());
        early.offer(A, f1);
        Assertions.assertEquals(1, early.run(A, 1));
        early.offer(A, reset);
        Assertions.assertEquals(1, early.run(A, 1));
        Assertions.assertEquals(Arrays.asList(f1, reset), early.ran(A));
    }

    @Test
    void releaseDropsTheQueueAndTheRenderersState() {
        LogRenderer renderer = new LogRenderer();
        FakeRenderHost host = new FakeRenderHost(renderer);
        LogSink sink = new LogSink();
        host.open(A, SURFACE, sink);
        Record f1 = frame(1, 1);
        Record f2 = frame(1, 2);
        host.offer(A, f1);
        Assertions.assertEquals(1, host.run(A, 1));
        host.offer(A, f2);
        host.release(A);
        Assertions.assertFalse(host.isOpen(A));
        Assertions.assertEquals(0, host.queued(A));
        Assertions.assertTrue(
            renderer.log()
                .contains("release DeviceKey[1:1]"));
        Assertions.assertEquals(Arrays.asList("open DeviceKey[1:1]", "release DeviceKey[1:1]"), host.log());
        // the last opening stays inspectable
        Assertions.assertEquals(Arrays.asList(f1, f2), host.offered(A));
        Assertions.assertEquals(Collections.singletonList(f1), host.ran(A));
        Assertions.assertEquals(SURFACE, host.surface(A));
        // a released key runs and reads back nothing, even for a frame that ran before the release
        Assertions.assertEquals(0, host.run(A, 5));
        Assertions.assertFalse(host.readback(A));
        Assertions.assertEquals(0, host.step(A));
        Assertions.assertEquals(0, host.step());
        Assertions.assertTrue(
            sink.log()
                .isEmpty());
        // nothing may be offered after release (I8), and a second release does nothing
        Assertions.assertThrows(IllegalStateException.class, () -> host.offer(A, frame(1, 3)));
        host.release(A);
        Assertions.assertEquals(
            2,
            host.log()
                .size());
        Assertions.assertEquals(1, Collections.frequency(renderer.log(), "release DeviceKey[1:1]"));
        // releasing a key never opened does nothing either
        host.release(B);
        Assertions.assertEquals(
            2,
            host.log()
                .size());
    }

    @Test
    void aReleasedKeyOpensAgainAfresh() {
        // 28:199: a key may be opened again after its release
        LogRenderer renderer = new LogRenderer();
        FakeRenderHost host = new FakeRenderHost(renderer);
        LogSink oldSink = new LogSink();
        host.open(A, SURFACE, oldSink);
        host.offer(A, frame(1, 1));
        host.step();
        host.offer(A, frame(1, 2));
        host.release(A);
        SurfaceDesc other = new SurfaceDesc(3, 2, 2, Formats.RGB565);
        LogSink newSink = new LogSink();
        host.open(A, other, newSink);
        Assertions.assertTrue(host.isOpen(A));
        Assertions.assertEquals(other, host.surface(A));
        Assertions.assertTrue(
            host.offered(A)
                .isEmpty());
        Assertions.assertTrue(
            host.ran(A)
                .isEmpty());
        Assertions.assertTrue(
            host.readbacks(A)
                .isEmpty());
        Assertions.assertEquals(0, host.queued(A));
        Assertions.assertEquals(
            Arrays.asList("open DeviceKey[1:1]", "release DeviceKey[1:1]", "open DeviceKey[1:1]"),
            host.log());
        Assertions.assertEquals(2, Collections.frequency(renderer.log(), "open DeviceKey[1:1]"));
        host.offer(A, frame(3, 1));
        host.step();
        Assertions.assertEquals(Collections.singletonList("1:1 e1 #1 1"), oldSink.log());
        Assertions.assertEquals(Collections.singletonList("1:1 e3 #1 1"), newSink.log());
    }

    @Test
    void openingAnOpenKeyThrowsAndKeepsTheOpening() {
        FakeRenderHost host = new FakeRenderHost(new LogRenderer());
        LogSink sink = new LogSink();
        host.open(A, SURFACE, sink);
        host.offer(A, frame(1, 1));
        Assertions.assertThrows(IllegalStateException.class, () -> host.open(A, SURFACE, new LogSink()));
        Assertions.assertEquals(1, host.queued(A));
        Assertions.assertEquals(Collections.singletonList("open DeviceKey[1:1]"), host.log());
        host.step();
        Assertions.assertEquals(
            1,
            sink.log()
                .size());
    }

    @Test
    void offersNeedAnOpenKeyAndNullsAreRefused() {
        FakeRenderHost host = new FakeRenderHost(new LogRenderer());
        // a device that offers before open (28:199)
        Assertions.assertThrows(IllegalStateException.class, () -> host.offer(A, frame(1, 1)));
        host.open(A, SURFACE, new LogSink());
        Assertions.assertThrows(IllegalStateException.class, () -> host.offer(B, frame(1, 1)));
        Assertions.assertThrows(NullPointerException.class, () -> host.offer(A, null));
        Assertions.assertThrows(NullPointerException.class, () -> host.offer(null, frame(1, 1)));
        Assertions.assertThrows(NullPointerException.class, () -> host.open(null, SURFACE, new LogSink()));
        Assertions.assertThrows(NullPointerException.class, () -> host.open(B, null, new LogSink()));
        Assertions.assertThrows(NullPointerException.class, () -> host.open(B, SURFACE, null));
        Assertions.assertThrows(NullPointerException.class, () -> new FakeRenderHost(null));
        Assertions.assertFalse(host.isOpen(B));
        Assertions.assertEquals(0, host.queued(A));
    }

    @Test
    void aKeyNeverOpenedCannotRunButInspectsEmpty() {
        FakeRenderHost host = new FakeRenderHost();
        Assertions.assertThrows(IllegalStateException.class, () -> host.run(A, 1));
        Assertions.assertThrows(IllegalStateException.class, () -> host.step(A));
        Assertions.assertThrows(IllegalStateException.class, () -> host.readback(A));
        Assertions.assertFalse(host.isOpen(A));
        Assertions.assertNull(host.surface(A));
        Assertions.assertTrue(
            host.offered(A)
                .isEmpty());
        Assertions.assertTrue(
            host.ran(A)
                .isEmpty());
        Assertions.assertTrue(
            host.readbacks(A)
                .isEmpty());
        Assertions.assertEquals(0, host.queued(A));
        Assertions.assertEquals(0, host.step());
        Assertions.assertTrue(
            host.log()
                .isEmpty());
    }

    @Test
    void bindDisplayMapsAndUnmapsADisplay() {
        FakeRenderHost host = new FakeRenderHost();
        Assertions.assertNull(host.boundDevice("d1"));
        host.bindDisplay("d1", A);
        Assertions.assertEquals(A, host.boundDevice("d1"));
        host.bindDisplay("d2", A);
        host.bindDisplay("d1", B);
        Assertions.assertEquals(B, host.boundDevice("d1"));
        Assertions.assertEquals(A, host.boundDevice("d2"));
        host.bindDisplay("d1", null);
        Assertions.assertNull(host.boundDevice("d1"));
        Assertions.assertEquals(A, host.boundDevice("d2"));
        Assertions.assertNull(host.boundDevice("d3"));
        Assertions.assertEquals(
            Arrays.asList(
                "bindDisplay d1 DeviceKey[1:1]",
                "bindDisplay d2 DeviceKey[1:1]",
                "bindDisplay d1 DeviceKey[1:2]",
                "bindDisplay d1 null"),
            host.log());
        Assertions.assertThrows(NullPointerException.class, () -> host.bindDisplay(null, A));
    }

    @Test
    void releaseSessionReleasesThatSessionsOpenKeysInOpenOrder() {
        FakeRenderHost host = new FakeRenderHost();
        DeviceKey other = new DeviceKey(2, 1);
        host.open(A, SURFACE, new LogSink());
        host.open(other, SURFACE, new LogSink());
        host.open(B, SURFACE, new LogSink());
        host.releaseSession(1);
        Assertions.assertFalse(host.isOpen(A));
        Assertions.assertFalse(host.isOpen(B));
        Assertions.assertTrue(host.isOpen(other));
        host.releaseSession(3);
        host.releaseSession(1);
        Assertions.assertEquals(
            Arrays.asList(
                "open DeviceKey[1:1]",
                "open DeviceKey[2:1]",
                "open DeviceKey[1:2]",
                "releaseSession 1",
                "release DeviceKey[1:1]",
                "release DeviceKey[1:2]",
                "releaseSession 3",
                "releaseSession 1"),
            host.log());
    }

    @Test
    void aKeyOpenedAgainMovesToTheEndOfTheOpenOrder() {
        LogRenderer renderer = new LogRenderer();
        FakeRenderHost host = new FakeRenderHost(renderer);
        host.open(A, SURFACE, new LogSink());
        host.open(B, SURFACE, new LogSink());
        host.release(A);
        host.open(A, SURFACE, new LogSink());
        host.offer(A, frame(1, 1));
        host.offer(B, frame(1, 1));
        renderer.clear();
        host.step();
        Assertions.assertEquals(
            Arrays.asList(
                "accept DeviceKey[1:2] FRAME e1 #1",
                "pixels DeviceKey[1:2]",
                "accept DeviceKey[1:1] FRAME e1 #1",
                "pixels DeviceKey[1:1]"),
            renderer.log());
    }

    @Test
    @Timeout(30)
    void onReadbackRunsOutsideTheHostsLock() throws InterruptedException {
        // a sink that calls back into the host from another thread must not deadlock (plan §3.10; B11)
        final FakeRenderHost host = new FakeRenderHost(new LogRenderer());
        final AtomicBoolean offered = new AtomicBoolean();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean joined = new AtomicBoolean();
        ReadbackSink sink = (session, deviceId, epoch, frameId, pixels) -> {
            Thread other = new Thread(() -> {
                try {
                    host.offer(A, new TexFreeRecord(1, 0x08000000));
                    offered.set(true);
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "offer-from-sink");
            other.setDaemon(true);
            other.start();
            try {
                other.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            }
            joined.set(!other.isAlive());
        };
        host.open(A, SURFACE, sink);
        host.offer(A, frame(1, 1));
        Assertions.assertEquals(1, host.step());
        Assertions.assertNull(failure.get());
        Assertions.assertTrue(joined.get(), "the sink's thread was still blocked after 5 s: the host lock was held");
        Assertions.assertTrue(offered.get());
        Assertions.assertEquals(1, host.queued(A));
    }

    @Test
    @Timeout(30)
    void offersFromTwoThreadsAreAllQueuedEachInItsThreadsOrder() throws InterruptedException {
        final FakeRenderHost host = new FakeRenderHost(new LogRenderer());
        host.open(A, SURFACE, new LogSink());
        final int n = 2000;
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[2];
        for (int t = 0; t < threads.length; t++) {
            final long epoch = t + 1;
            threads[t] = new Thread(() -> {
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start gate not opened");
                    }
                    for (int i = 1; i <= n; i++) {
                        host.offer(A, frame(epoch, i));
                    }
                } catch (Throwable e) {
                    failure.set(e);
                }
            }, "offerer-" + epoch);
            threads[t].setDaemon(true);
            threads[t].start();
        }
        try {
            start.countDown();
            for (Thread thread : threads) {
                thread.join(20000);
                Assertions.assertFalse(thread.isAlive(), thread.getName());
            }
        } finally {
            start.countDown();
        }
        Assertions.assertNull(failure.get());
        Assertions.assertEquals(2 * n, host.queued(A));
        List<Record> offered = host.offered(A);
        Assertions.assertEquals(2 * n, offered.size());
        long[] next = { 1, 1, 1 };
        for (Record r : offered) {
            FrameRecord f = (FrameRecord) r;
            Assertions.assertEquals(next[(int) f.epoch], f.frameId, "thread " + f.epoch);
            next[(int) f.epoch]++;
        }
        Assertions.assertEquals(n + 1, next[1]);
        Assertions.assertEquals(n + 1, next[2]);
        Assertions.assertEquals(2 * n, host.step());
        Assertions.assertEquals(offered, host.ran(A));
    }

    @Test
    void inspectionReturnsCopiesAndKeepHistoryFalseKeepsNone() {
        LogRenderer renderer = new LogRenderer();
        FakeRenderHost host = new FakeRenderHost(renderer);
        LogSink sink = new LogSink();
        host.open(A, SURFACE, sink);
        host.offer(A, frame(1, 1));
        host.step();
        host.offered(A)
            .clear();
        host.ran(A)
            .clear();
        host.readbacks(A)
            .clear();
        host.log()
            .clear();
        Assertions.assertEquals(
            1,
            host.offered(A)
                .size());
        Assertions.assertEquals(
            1,
            host.ran(A)
                .size());
        Assertions.assertEquals(
            1,
            host.readbacks(A)
                .size());
        Assertions.assertEquals(
            1,
            host.log()
                .size());
        // B15: from now on no history is kept, but records still run and readbacks are still delivered
        host.setKeepHistory(false);
        host.offer(A, frame(1, 2));
        Assertions.assertEquals(1, host.queued(A));
        host.step();
        Assertions.assertEquals(
            1,
            host.offered(A)
                .size());
        Assertions.assertEquals(
            1,
            host.ran(A)
                .size());
        Assertions.assertEquals(
            1,
            host.readbacks(A)
                .size());
        Assertions.assertEquals(Arrays.asList("1:1 e1 #1 1", "1:1 e1 #2 2"), sink.log());
        Assertions.assertTrue(
            renderer.log()
                .contains("accept DeviceKey[1:1] FRAME e1 #2"));
        host.setKeepHistory(true);
        host.offer(A, frame(1, 3));
        Assertions.assertEquals(
            2,
            host.offered(A)
                .size());
    }

    @Test
    void theReadbackIsRecordedBeforeItIsDelivered() {
        final FakeRenderHost host = new FakeRenderHost(new LogRenderer());
        final List<Integer> seen = new ArrayList<>();
        final List<Boolean> same = new ArrayList<>();
        host.open(A, SURFACE, (session, deviceId, epoch, frameId, pixels) -> {
            List<FakeRenderHost.Readback> readbacks = host.readbacks(A);
            seen.add(readbacks.size());
            same.add(readbacks.get(readbacks.size() - 1).pixels == pixels);
        });
        host.offer(A, frame(1, 1));
        host.step();
        Assertions.assertEquals(Collections.singletonList(1), seen);
        Assertions.assertEquals(Collections.singletonList(true), same);
    }

    @Test
    void availabilityAndStallAreWhatTheTestSets() {
        FakeRenderHost host = new FakeRenderHost();
        Assertions.assertTrue(host.available());
        Assertions.assertFalse(host.stalled());
        host.setAvailable(false);
        host.setStalled(true);
        Assertions.assertFalse(host.available());
        Assertions.assertTrue(host.stalled());
        host.setAvailable(true);
        host.setStalled(false);
        Assertions.assertTrue(host.available());
        Assertions.assertFalse(host.stalled());
        LogRenderer renderer = new LogRenderer();
        Assertions.assertSame(renderer, new FakeRenderHost(renderer).renderer());
        Assertions.assertEquals("FakeRenderHost[LogRenderer]", new FakeRenderHost(renderer).toString());
    }

    private static FrameRecord frame(long epoch, long id, byte[]... segments) {
        return new FrameRecord(epoch, id, segments, FrameTotals.EMPTY);
    }

    /** "FRAME e1 #2", "TEX_CREATE e1", ...: a short, stable name of a record for the logs. */
    private static String name(Record r) {
        String[] kinds = { "?", "FRAME", "TEX_CREATE", "TEX_UPDATE", "TEX_FREE", "?", "?", "?", "?", "RESET" };
        String s = kinds[r.kind()] + " e" + r.epoch;
        return r instanceof FrameRecord ? s + " #" + ((FrameRecord) r).frameId : s;
    }

    /** Logs every call; its pixels are filled with the number of records it accepted for the key. */
    private static final class LogRenderer implements RecordRenderer {

        private final List<String> log = new ArrayList<>();
        private final Map<DeviceKey, SurfaceDesc> surfaces = new HashMap<>();
        private final Map<DeviceKey, Integer> accepted = new HashMap<>();

        @Override
        public synchronized void open(DeviceKey key, SurfaceDesc surface) {
            log.add("open " + key);
            surfaces.put(key, surface);
            accepted.put(key, 0);
        }

        @Override
        public synchronized void accept(DeviceKey key, Record record) {
            log.add("accept " + key + " " + name(record));
            accepted.put(key, accepted.get(key) + 1);
        }

        @Override
        public synchronized byte[] pixels(DeviceKey key) {
            log.add("pixels " + key);
            byte[] p = new byte[surfaces.get(key)
                .byteSize()];
            Arrays.fill(
                p,
                (byte) accepted.get(key)
                    .intValue());
            return p;
        }

        @Override
        public synchronized void release(DeviceKey key) {
            log.add("release " + key);
            surfaces.remove(key);
            accepted.remove(key);
        }

        synchronized List<String> log() {
            return new ArrayList<>(log);
        }

        synchronized void clear() {
            log.clear();
        }

        @Override
        public String toString() {
            return "LogRenderer";
        }
    }

    /** Logs "s:d e# #id firstByte" per readback and keeps the delivered arrays. */
    private static final class LogSink implements ReadbackSink {

        private final List<String> log = new ArrayList<>();
        final List<byte[]> arrays = new ArrayList<>();

        @Override
        public synchronized void onReadback(long session, int deviceId, long epoch, long frameId, byte[] pixels) {
            log.add(
                String.format(
                    Locale.ROOT,
                    "%d:%d e%d #%d %d",
                    session,
                    deviceId,
                    epoch,
                    frameId,
                    pixels.length == 0 ? -1 : pixels[0]));
            arrays.add(pixels);
        }

        synchronized List<String> log() {
            return new ArrayList<>(log);
        }
    }
}
