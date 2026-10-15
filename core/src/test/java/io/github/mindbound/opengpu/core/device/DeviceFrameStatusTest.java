package io.github.mindbound.opengpu.core.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.ManualClock;
import io.github.mindbound.opengpu.core.fixtures.device.RecordingBudget;
import io.github.mindbound.opengpu.core.fixtures.device.RecordingListener;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FakeRenderHost;

/**
 * Frame statuses (28:110-115; 24:127; H12): {@code "done"} once per fold for its frame, covering older frames
 * (21:87); {@code "dropped"} from {@code tick()} for a frame still unconfirmed {@link Device#DROP_AFTER_NANOS} after
 * its present on the device's clock, once, the frame staying queued until a later {@code "done"} covers it (28:112);
 * no status for frames an epoch change discards (28:115); undelivered statuses dropped by {@code release}; and the
 * listener called in order, outside the device's monitor (plan §3.7, §3.10).
 */
class DeviceFrameStatusTest {

    @Test
    void droppedAtExactlyOneSecondOnceThenDone() {
        DeviceRig rig = DeviceRig.tier(2);
        Assertions.assertEquals(1L, rig.present());
        rig.clock.advanceMillis(999);
        rig.device.tick();
        Assertions.assertEquals(Collections.emptyList(), rig.listener.events(), "999 ms: nothing");
        rig.clock.advanceNanos(999_999);
        rig.device.tick();
        Assertions.assertEquals(Collections.emptyList(), rig.listener.events(), "1 ns short: nothing");
        rig.clock.advanceNanos(1);
        rig.device.tick();
        Assertions.assertEquals(Collections.singletonList("dropped 1"), rig.listener.events());
        rig.clock.advanceMillis(5000);
        rig.device.tick();
        rig.device.tick();
        Assertions.assertEquals(Collections.singletonList("dropped 1"), rig.listener.events(), "reported once");
        // the frame stays queued, and its pixels still land
        Assertions.assertEquals(1, rig.snapshot().unconfirmedFrames);
        rig.step();
        Assertions.assertEquals(Arrays.asList("dropped 1", "done 1"), rig.listener.events());
        rig.device.tick();
        Assertions.assertEquals(
            2,
            rig.listener.events()
                .size());
    }

    @Test
    void eachFrameIsTimedFromItsOwnPresent() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.present();
        rig.clock.advanceMillis(600);
        rig.present();
        rig.clock.advanceMillis(400);
        rig.device.tick();
        Assertions.assertEquals(Collections.singletonList("dropped 1"), rig.listener.events());
        rig.clock.advanceMillis(599);
        rig.device.tick();
        Assertions.assertEquals(Collections.singletonList("dropped 1"), rig.listener.events());
        rig.clock.advanceMillis(1);
        rig.device.tick();
        Assertions.assertEquals(Arrays.asList("dropped 1", "dropped 2"), rig.listener.events());
        // one done covers both
        rig.step();
        Assertions.assertEquals(Arrays.asList("dropped 1", "dropped 2", "done 2"), rig.listener.events());
    }

    @Test
    void doneOfALaterFrameCoversTheEarlierOnes() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.present();
        rig.present();
        rig.step();
        rig.present();
        rig.step();
        Assertions.assertEquals(Arrays.asList("done 2", "done 3"), rig.listener.events());
        rig.clock.advanceMillis(10_000);
        rig.device.tick();
        Assertions.assertEquals(
            2,
            rig.listener.events()
                .size(),
            "confirmed frames are never dropped");
    }

    @Test
    void framesDiscardedByAResetAreNeverReported() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.present();
        rig.present();
        rig.device.reset();
        rig.clock.advanceMillis(2000);
        rig.device.tick();
        rig.step();
        rig.device.tick();
        Assertions.assertEquals(Collections.emptyList(), rig.listener.events());
        Assertions.assertEquals(1, rig.snapshot().staleReadbacks, "the host's readback of frame 2 was of epoch 1");
        // the next frame is reported as usual
        Assertions.assertEquals(3L, rig.present());
        rig.clock.advanceMillis(1000);
        rig.device.tick();
        rig.step();
        Assertions.assertEquals(Arrays.asList("dropped 3", "done 3"), rig.listener.events());
    }

    @Test
    void framesDroppedButDiscardedByAResetGetNoDone() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.present();
        rig.clock.advanceMillis(1000);
        rig.device.tick();
        rig.device.reset();
        rig.step();
        Assertions.assertEquals(Collections.singletonList("dropped 1"), rig.listener.events());
    }

    /**
     * A status queued while another is being delivered is still undelivered when {@code release} runs, which drops
     * it (plan §3.9). The listener holds "done 1" until the test lets it go.
     */
    @Test
    @Timeout(30)
    void releaseDropsUndeliveredStatuses() throws Exception {
        FakeRenderHost host = new FakeRenderHost();
        ManualClock clock = new ManualClock();
        BlockingListener listener = new BlockingListener();
        DeviceKey key = new DeviceKey(1, 1);
        Device device = Device.create(key, DeviceCaps.forTier(2), host, Runnable::run, listener, clock);
        RecordingBudget budget = new RecordingBudget();
        device.present(budget);
        device.present(budget);
        // the tick's classes loaded now, so that the ticker below can block on nothing but the delivery
        device.tick();
        Assertions.assertEquals(Collections.emptyList(), listener.events());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread folder = new Thread(() -> {
            try {
                Assertions.assertEquals(1, host.run(key, 1));
                host.readback(key);
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "folder");
        Thread ticker = new Thread(device::tick, "ticker");
        // on a thread of its own, so that a lock-order cycle (plan §3.10) fails the test instead of hanging it
        Thread releaser = new Thread(() -> {
            try {
                device.release();
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "releaser");
        try {
            folder.start();
            Assertions.assertTrue(listener.entered.await(10, TimeUnit.SECONDS), "done 1 reached the listener");
            clock.advanceMillis(1000);
            ticker.start();
            // the tick queued "dropped 2" and waits to deliver it behind "done 1"
            while (ticker.getState() != Thread.State.BLOCKED) {
                Assertions.assertTrue(ticker.isAlive(), "the tick delivered while done 1 was being delivered");
                Thread.yield();
            }
            releaser.start();
            releaser.join(10000);
            Assertions.assertFalse(releaser.isAlive(), "release did not return: a lock-order cycle");
        } finally {
            // 5 s each: after a cycle these threads stay blocked, and the release's failure must come before @Timeout
            listener.release.countDown();
            folder.join(5000);
            ticker.join(5000);
            releaser.join(5000);
        }
        Assertions.assertNull(failure.get());
        Assertions.assertEquals(Collections.singletonList("done 1"), listener.events());
        device.tick();
        Assertions.assertEquals(Collections.singletonList("done 1"), listener.events());
    }

    /** The listener runs outside the monitor: a snapshot from another thread, joined with a timeout, completes. */
    @Test
    @Timeout(30)
    void theListenerRunsOutsideTheMonitor() throws Exception {
        FakeRenderHost host = new FakeRenderHost();
        ManualClock clock = new ManualClock();
        List<String> seen = Collections.synchronizedList(new ArrayList<String>());
        AtomicReference<Device> device = new AtomicReference<>();
        FrameListener listener = new FrameListener() {

            @Override
            public void frameDone(long frameId) {
                seen.add("done " + frameId + " " + snapshotFromAnotherThread());
            }

            @Override
            public void frameDropped(long frameId) {
                seen.add("dropped " + frameId + " " + snapshotFromAnotherThread());
            }

            private String snapshotFromAnotherThread() {
                AtomicReference<DeviceSnapshot> s = new AtomicReference<>();
                Thread t = new Thread(
                    () -> s.set(
                        device.get()
                            .snapshot()),
                    "snapshot");
                t.start();
                try {
                    t.join(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread()
                        .interrupt();
                }
                return s.get() == null ? "blocked" : "K=" + s.get().confirmedFrameId;
            }
        };
        DeviceKey key = new DeviceKey(1, 1);
        device.set(Device.create(key, DeviceCaps.forTier(2), host, Runnable::run, listener, clock));
        RecordingBudget budget = new RecordingBudget();
        device.get()
            .present(budget);
        clock.advanceMillis(1000);
        device.get()
            .tick();
        host.step();
        Assertions.assertEquals(Arrays.asList("dropped 1 K=0", "done 1 K=1"), seen);
    }

    /**
     * Statuses reach the listener in order under a two-thread fold pool: done ids strictly increase (plan §3.7). On
     * {@code "busy"} the loop waits for a done rather than spinning, which on a two-core runner kept the pool's folds
     * from the monitor; it ends after 2 000 frames, each wait bounded.
     */
    @Test
    @Timeout(60)
    void statusesArriveInOrderFromATwoThreadPool() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        RecordingListener listener = new RecordingListener();
        Semaphore dones = new Semaphore(0);
        FrameListener counting = new FrameListener() {

            @Override
            public void frameDone(long frameId) {
                listener.frameDone(frameId);
                dones.release();
            }

            @Override
            public void frameDropped(long frameId) {
                listener.frameDropped(frameId);
            }
        };
        FakeRenderHost host = new FakeRenderHost();
        host.setKeepHistory(false);
        DeviceKey key = new DeviceKey(1, 1);
        Device device = Device.create(key, DeviceCaps.forTier(2), host, pool, counting, new ManualClock());
        RecordingBudget budget = new RecordingBudget();
        AtomicBoolean ok = new AtomicBoolean(true);
        try {
            int presented = 0;
            for (int i = 0; presented < 2000; i++) {
                if (i % 3 != 2) {
                    device.submit(budget, segment(clear(i & 0xFF)));
                    if (device.present(budget)
                        .isOk()) {
                        presented++;
                    } else {
                        host.step();
                        Assertions.assertTrue(
                            dones.tryAcquire(10, TimeUnit.SECONDS),
                            "no done within 10 s after " + presented + " frames");
                    }
                }
                host.step();
                if (i % 64 == 0) {
                    budget.clearEvents();
                }
            }
            long last = device.snapshot().lastFrameId;
            for (int i = 0; i < 1000 && device.snapshot().confirmedFrameId != last; i++) {
                host.step();
                dones.tryAcquire(10, TimeUnit.MILLISECONDS);
            }
            Assertions.assertEquals(last, device.snapshot().confirmedFrameId);
        } finally {
            pool.shutdown();
            ok.set(pool.awaitTermination(20, TimeUnit.SECONDS));
        }
        Assertions.assertTrue(ok.get(), "the fold pool drained");
        List<String> events = listener.events();
        Assertions.assertFalse(events.isEmpty());
        long previous = 0;
        for (String e : events) {
            Assertions.assertTrue(e.startsWith("done "), e);
            long id = Long.parseLong(e.substring(5));
            Assertions.assertTrue(id > previous, events.toString());
            previous = id;
        }
        Assertions.assertEquals(device.snapshot().lastFrameId, previous, "the last done is the last frame");
        Invariants.check(device.snapshot(), device.caps());
    }

    /** A listener that blocks in its first frameDone until the test releases it. */
    private static final class BlockingListener implements FrameListener {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final List<String> events = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public void frameDone(long frameId) {
            events.add("done " + frameId);
            if (entered.getCount() > 0) {
                entered.countDown();
                await();
            }
        }

        @Override
        public void frameDropped(long frameId) {
            events.add("dropped " + frameId);
        }

        List<String> events() {
            synchronized (events) {
                return new ArrayList<>(events);
            }
        }

        private void await() {
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            }
        }
    }
}
