package io.github.mindbound.opengpu.core.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.ManualClock;
import io.github.mindbound.opengpu.core.fixtures.device.ManualExecutor;
import io.github.mindbound.opengpu.core.fixtures.device.OracleRecordRenderer;
import io.github.mindbound.opengpu.core.fixtures.device.RecordText;
import io.github.mindbound.opengpu.core.fixtures.device.RecordingBudget;
import io.github.mindbound.opengpu.core.fixtures.device.RecordingListener;
import io.github.mindbound.opengpu.core.render.FakeRenderHost;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.ResetRecord;

/**
 * The lifecycle of 28:146-155 (28 §11.4, 28:937; plan §3.9; amendment B9). A reset ({@code computer.stopped} or
 * {@code started}, 28:150) bumps the epoch, discards the open frame with its deferred frees and the whole tail without
 * a status, clears the masters, the live handles (ascending slot, 28:1061), the VRAM use and the queued bytes, keeps K,
 * its pixels and the frame counter, and offers {@code RESET} with the new epoch and a copy of K's pixels; so a step
 * leaves K's pixels and no texture on the oracle however far the host got (28:155), and the host's readback of an old
 * epoch is stale. A release (28:151) logs {@code release} on the host, makes every callback throw
 * {@code IllegalStateException("device released")} before any check or charge, makes reset and a second release
 * no-ops and pending folds stale, and lets a new device open the key again with a higher epoch (28:199). B9's check
 * under the monitor catches a release that lands while a call waits for it: raced here for the calls whose check no
 * charge precedes (free, and the pre-charge checks of createTexture and updateTexture), which throw uncharged;
 * {@code DeviceChargeOrderTest} releases between a charge and phase 3.
 */
class DeviceLifecycleTest {

    private static final int T = 0x08000000;

    /** How far the host got with frames 2 and 3 when the reset came. */
    enum HostProgress {
        /** It ran nothing of them. */
        RAN_NOTHING,
        /** It ran both, without a readback yet. */
        RAN_BOTH_UNREAD,
        /** It skips records of an older epoch than the newest it was offered (28:155's optional skip). */
        SKIPS_STALE_EPOCHS
    }

    /** A call that passes the first released check and then waits for the monitor. */
    enum WaitingCall {

        FREE {

            @Override
            CallResult invoke(Device device, CallBudget budget, int tex) {
                return device.free(tex);
            }
        },
        CREATE_TEXTURE {

            @Override
            CallResult invoke(Device device, CallBudget budget, int tex) {
                return device.createTexture(budget, 16, 16, null);
            }
        },
        UPDATE_TEXTURE {

            @Override
            CallResult invoke(Device device, CallBudget budget, int tex) {
                return device.updateTexture(budget, tex, 0, 0, 16, 16, new byte[256]);
            }
        };

        abstract CallResult invoke(Device device, CallBudget budget, int tex);
    }

    @ParameterizedTest
    @EnumSource(HostProgress.class)
    void aResetWithFramesInFlightLeavesKsPixelsOnTheHost(HostProgress progress) {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        byte[] p1 = confirmFrame1(rig);
        // frames 2 and 3 in flight, a texture queued between them, and an open frame with a deferred free
        rig.submit(segment(fill(0, 0, 50, 50, 9)));
        Assertions.assertEquals(2L, rig.present());
        int u = rig.create(8, 8);
        rig.submit(segment(fill(100, 100, 50, 50, 11), blit(u, 0, 0, 8, 8, 0, 0, 0)));
        Assertions.assertEquals(3L, rig.present());
        rig.submit(segment(clear(1)));
        rig.free(T);
        DeviceOrderingTest.check(rig, history);
        if (progress == HostProgress.RAN_BOTH_UNREAD) {
            Assertions.assertEquals(3, rig.host.run(rig.key, Integer.MAX_VALUE));
        } else if (progress == HostProgress.SKIPS_STALE_EPOCHS) {
            rig.host.setSkipStaleEpochs(true);
        }
        int offeredBefore = DeviceOrderingTest.offered(rig)
            .size();
        rig.device.reset();
        List<Record> offered = DeviceOrderingTest.offered(rig);
        Assertions.assertEquals(offeredBefore + 1, offered.size(), "the reset offers RESET only");
        ResetRecord reset = (ResetRecord) offered.get(offered.size() - 1);
        Assertions.assertEquals(2, reset.epoch);
        Assertions.assertArrayEquals(p1, reset.pixels());
        for (String d : RecordText.of(offered)) {
            Assertions.assertNotEquals("TEX_FREE e=1 tex=0x08000000", d, "the deferred free is dropped unoffered");
        }
        DeviceSnapshot s = DeviceOrderingTest.check(rig, history);
        assertCleared(s, 2);
        Assertions.assertEquals(1, s.confirmedFrameId, "K is kept");
        Assertions.assertEquals(3, s.lastFrameId, "the frame counter is kept");
        Assertions.assertArrayEquals(p1, s.confirmedPixels());
        rig.listener.clear();
        rig.step();
        s = DeviceOrderingTest.check(rig, history);
        TierEOracle host = rig.hostOracle();
        Assertions.assertArrayEquals(p1, host.pixels(), "K's pixels on the host");
        Assertions.assertEquals(0, host.textureCount());
        Assertions.assertEquals(progress == HostProgress.SKIPS_STALE_EPOCHS ? 0 : 1, s.staleReadbacks);
        List<FakeRenderHost.Readback> readbacks = rig.host.readbacks(rig.key);
        if (progress != HostProgress.SKIPS_STALE_EPOCHS) {
            Assertions.assertEquals(1, readbacks.get(readbacks.size() - 1).epoch);
            Assertions.assertEquals(3, readbacks.get(readbacks.size() - 1).frameId);
        } else {
            Assertions.assertEquals(1, readbacks.size(), "frames 2 and 3 were skipped, so nothing to read back");
        }
        Assertions.assertEquals(Collections.emptyList(), rig.listener.events(), "no status for discarded frames");
        Assertions.assertEquals(1, s.confirmedFrameId);
        DeviceOrderingTest.assertRead(rig, 0, 0, 320, 100, 1, Arrays.copyOf(p1, 32000));
        // the next frame, 4 of epoch 2, draws on K's pixels
        byte[] s4 = segment(fill(30, 30, 10, 10, 77));
        rig.submit(s4);
        Assertions.assertEquals(4L, rig.present());
        Assertions.assertEquals(
            2,
            DeviceOrderingTest.offered(rig)
                .get(offered.size()).epoch);
        rig.step();
        s = DeviceOrderingTest.check(rig, history);
        TierEOracle expected = new TierEOracle(rig.device.surface(), SyntheticGlyphs.TABLE);
        expected.reset(p1);
        expected.runSegment(s4);
        Assertions.assertEquals(4, s.confirmedFrameId);
        Assertions.assertArrayEquals(expected.pixels(), rig.screen());
        Assertions.assertEquals(Collections.singletonList("done 4"), rig.listener.events());
    }

    /** The handle table restarts from the free queue: the reset frees u (slot 1) after t's slot 0, freed earlier. */
    @Test
    void theNextCreateFollowsTheFreeQueue() {
        DeviceRig rig = DeviceRig.tier(2);
        Assertions.assertEquals(T, rig.create(16, 16));
        Assertions.assertEquals(0x08010000, rig.create(8, 8));
        rig.free(T);
        rig.device.reset();
        DeviceSnapshot s = Invariants.check(rig.snapshot(), rig.device.caps());
        int[] free = s.freeSlots();
        Assertions.assertEquals(2048, free.length);
        Assertions.assertEquals(2, free[0]);
        Assertions.assertEquals(0, free[2046]);
        Assertions.assertEquals(1, free[2047]);
        Assertions.assertEquals(1, s.generations()[0]);
        Assertions.assertEquals(1, s.generations()[1]);
        Assertions.assertEquals(0x08020000, rig.create(1, 1));
        Assertions.assertEquals(
            "handle 0x08010000 is not live",
            rig.device.free(0x08010000)
                .message());
    }

    /** Each reset bumps the epoch by one and offers its own copy of K's pixels, which no later fold overwrites. */
    @Test
    void everyResetBumpsTheEpochAndCarriesItsOwnCopy() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        byte[] p1 = confirmFrame1(rig);
        rig.device.reset();
        rig.device.reset();
        Assertions.assertEquals(3, rig.device.epoch());
        List<Record> offered = DeviceOrderingTest.offered(rig);
        ResetRecord r2 = (ResetRecord) offered.get(offered.size() - 2);
        ResetRecord r3 = (ResetRecord) offered.get(offered.size() - 1);
        Assertions.assertEquals(2, r2.epoch);
        Assertions.assertEquals(3, r3.epoch);
        Assertions.assertArrayEquals(p1, r2.pixels());
        Assertions.assertArrayEquals(p1, r3.pixels());
        Assertions.assertNotSame(r2.pixels(), r3.pixels(), "a copy each");
        Assertions.assertNotSame(
            rig.host.readbacks(rig.key)
                .get(0).pixels,
            r2.pixels());
        DeviceOrderingTest.check(rig, history);
        // later folds recycle K's old arrays through the ring (amendment B); the RESETs keep their pixels
        for (int i = 2; i <= 5; i++) {
            rig.submit(segment(clear(20 + i)));
            rig.present();
            rig.step();
            DeviceOrderingTest.check(rig, history);
        }
        Assertions.assertArrayEquals(p1, r2.pixels());
        Assertions.assertArrayEquals(p1, r3.pixels());
        Assertions.assertTrue(r2.intact());
        Assertions.assertTrue(r3.intact());
        Assertions.assertEquals(25, rig.screen()[0]);
    }

    @Test
    void releaseClosesTheKeyAndDropsTheState() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        rig.create(16, 16);
        rig.present();
        rig.submit(segment(clear(3)));
        rig.device.release();
        Assertions.assertTrue(rig.device.isReleased());
        Assertions.assertEquals(Arrays.asList("open DeviceKey[1:1]", "release DeviceKey[1:1]"), rig.host.log());
        Assertions.assertFalse(rig.host.isOpen(rig.key));
        Assertions.assertEquals(0, rig.host.queued(rig.key), "the host dropped the key's queue (21:106)");
        DeviceSnapshot s = DeviceOrderingTest.check(rig, history);
        Assertions.assertTrue(s.released);
        Assertions.assertEquals(2, s.epoch);
        Assertions.assertTrue(s.tail.isEmpty());
        Assertions.assertTrue(s.masters.isEmpty());
        Assertions.assertTrue(s.openSegments.isEmpty());
        // a second release, and a reset, do nothing
        String before = Invariants.describe(s);
        rig.device.release();
        rig.device.reset();
        Assertions.assertEquals(
            2,
            rig.host.log()
                .size());
        Assertions.assertEquals(before, Invariants.describe(rig.snapshot()));
        Assertions.assertEquals(
            history.fed(),
            DeviceOrderingTest.offered(rig)
                .size(),
            "nothing offered after release");
        // getCaps, tick and snapshot still work; tick reports nothing
        Assertions.assertEquals(
            rig.device.caps()
                .luaTable(),
            rig.device.getCaps());
        rig.clock.advanceMillis(5000);
        rig.device.tick();
        Assertions.assertEquals(Collections.emptyList(), rig.listener.events());
    }

    /** B9: every callback throws before any check or charge, with valid or invalid arguments alike. */
    @Test
    void everyCallbackThrowsOnAReleasedDeviceUncharged() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(16, 16);
        rig.device.release();
        RecordingBudget budget = rig.budget;
        budget.clearEvents();
        byte[] seg = segment(clear(1));
        assertReleased(() -> rig.device.submit(budget, seg));
        assertReleased(() -> rig.device.submit(budget, new byte[0]));
        assertReleased(() -> rig.device.present(budget));
        assertReleased(() -> rig.device.readPixels(budget, 0, 0, 1, 1));
        assertReleased(() -> rig.device.readPixels(budget, -1, 0, 1, 1));
        assertReleased(() -> rig.device.readPixels(budget, 0, 0, 320, 200));
        assertReleased(() -> rig.device.createTexture(budget, 16, 16, null));
        assertReleased(() -> rig.device.createTexture(budget, 1024, 1024, null));
        assertReleased(() -> rig.device.createTexture(budget, 0, 0, "INDEX8"));
        assertReleased(() -> rig.device.updateTexture(budget, T, 0, 0, 16, 16, new byte[256]));
        assertReleased(() -> rig.device.updateTexture(budget, 0, -1, 0, 0, 0, new byte[1]));
        assertReleased(() -> rig.device.free(T));
        assertReleased(() -> rig.device.free(0));
        Assertions.assertEquals(Collections.emptyList(), budget.events(), "no charge");
        Assertions.assertEquals(
            1,
            DeviceOrderingTest.offered(rig)
                .size());
    }

    /**
     * B9, plan §3.10: a release that lands while a worker's call, past its first check, waits for the monitor makes
     * that call throw once it enters, before any check or charge. Made deterministic without sleeping: the server
     * thread's tick holds the monitor inside the clock until the worker is BLOCKED on it, then releases the device,
     * reentrantly, which stands for a release that wins the monitor ahead of the worker. The texture is live, so that
     * a missing check would free or update it and offer its record after the host's release.
     */
    @ParameterizedTest
    @EnumSource(WaitingCall.class)
    @Timeout(30)
    void aCallWaitingForTheMonitorWhenReleaseLandsThrowsUncharged(WaitingCall call) throws Exception {
        GateClock clock = new GateClock();
        FakeRenderHost host = new FakeRenderHost(new OracleRecordRenderer());
        Device device = Device
            .create(DeviceRig.KEY, DeviceCaps.forTier(2), host, Runnable::run, new RecordingListener(), clock);
        RecordingBudget budget = new RecordingBudget();
        Assertions.assertEquals(
            T,
            DeviceOrderingTest.ok(device.createTexture(budget, 16, 16, null))
                .handle());
        budget.clearEvents();
        List<String> offered = RecordText.of(host.offered(DeviceRig.KEY));
        AtomicReference<DeviceSnapshot> released = new AtomicReference<>();
        AtomicReference<CallResult> result = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread server = new Thread(device::tick, "server");
        Thread worker = new Thread(() -> {
            try {
                result.set(call.invoke(device, budget, T));
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, "worker");
        clock.arm(() -> {
            device.release();
            released.set(device.snapshot());
        });
        try {
            server.start();
            Assertions.assertTrue(clock.entered.await(10, TimeUnit.SECONDS), "the tick reached the clock");
            worker.start();
            while (worker.getState() != Thread.State.BLOCKED) {
                Assertions.assertTrue(worker.isAlive(), "the worker's call did not wait: the monitor is not held");
                Thread.yield();
            }
        } finally {
            clock.go.countDown();
            server.join(10000);
            worker.join(10000);
        }
        Assertions.assertFalse(server.isAlive() || worker.isAlive(), "the tick or the worker did not return");
        Throwable e = thrown.get();
        Assertions.assertTrue(
            e instanceof IllegalStateException,
            call + " returned " + result.get() + " or threw " + e + " on a released device");
        Assertions.assertEquals("device released", e.getMessage());
        Assertions.assertEquals(Collections.emptyList(), budget.events(), "no charge");
        Assertions.assertEquals(Arrays.asList("open DeviceKey[1:1]", "release DeviceKey[1:1]"), host.log());
        Assertions.assertEquals(offered, RecordText.of(host.offered(DeviceRig.KEY)), "nothing offered after release");
        Assertions.assertEquals(Invariants.describe(released.get()), Invariants.describe(device.snapshot()));
    }

    /** A fold still queued at release is stale when it runs, and a late readback too (28:151, 21:106). */
    @Test
    void aFoldPendingAtReleaseIsStale() {
        ManualExecutor folds = new ManualExecutor();
        FakeRenderHost host = new FakeRenderHost(new OracleRecordRenderer());
        DeviceRig rig = DeviceRig.on(DeviceCaps.forTier(2), host, host::step, folds);
        rig.submit(segment(clear(8)));
        rig.present();
        rig.step();
        Assertions.assertEquals(1, folds.pending());
        rig.device.release();
        folds.runAll();
        DeviceSnapshot s = rig.snapshot();
        Assertions.assertEquals(1, s.staleReadbacks);
        Assertions.assertEquals(0, s.confirmedFrameId);
        Assertions.assertEquals(Collections.emptyList(), rig.listener.events());
        rig.device.onReadback(1, 1, 1, 1, new byte[320 * 200]);
        folds.runAll();
        Assertions.assertEquals(2, rig.snapshot().staleReadbacks);
        Assertions.assertEquals(0, rig.snapshot().confirmedFrameId);
    }

    /**
     * 28:151, :199 (H13): after a release the key opens again for a new device with a higher epoch, which starts at
     * frame 0 with the cleared image and a fresh handle table, and whose first readback matches a fresh oracle.
     */
    @Test
    void theKeyOpensAgainForANewDeviceWithAHigherEpoch() {
        FakeRenderHost host = new FakeRenderHost(new OracleRecordRenderer());
        DeviceRig first = DeviceRig.on(DeviceCaps.forTier(2), host, host::step);
        byte[] p1 = confirmFrame1(first);
        first.create(4, 4);
        first.device.release();
        long old = first.device.epoch();
        Assertions.assertEquals(2, old);
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> Device
                .create(first.key, DeviceCaps.forTier(2), host, Runnable::run, FrameListener.NONE, first.clock, 0));
        RecordingListener listener = new RecordingListener();
        Device second = Device
            .create(first.key, DeviceCaps.forTier(2), host, Runnable::run, listener, new ManualClock(), old + 1);
        Assertions.assertEquals(
            Arrays.asList("open DeviceKey[1:1]", "release DeviceKey[1:1]", "open DeviceKey[1:1]"),
            host.log());
        Assertions.assertTrue(host.isOpen(first.key));
        Assertions.assertTrue(
            host.offered(first.key)
                .isEmpty(),
            "a fresh opening");
        Invariants.History history = Invariants.History.of(second);
        DeviceSnapshot s = Invariants.checkAll(second, host, history);
        Assertions.assertEquals(old + 1, s.epoch);
        Assertions.assertEquals(0, s.lastFrameId);
        Assertions.assertEquals(0, s.confirmedFrameId);
        Assertions.assertArrayEquals(new byte[320 * 200], s.confirmedPixels());
        Assertions.assertTrue(s.live.isEmpty());
        Assertions.assertArrayEquals(new int[2048], s.generations());
        RecordingBudget budget = new RecordingBudget();
        Assertions.assertEquals(
            T,
            DeviceOrderingTest.ok(second.createTexture(budget, 2, 2, null))
                .handle());
        byte[] texels = { 5, 6, 7, 8 };
        DeviceOrderingTest.ok(second.updateTexture(budget, T, 0, 0, 2, 2, texels));
        byte[] seg = segment(clear(2), blit(T, 0, 0, 2, 2, 1, 1, 0));
        DeviceOrderingTest.ok(second.submit(budget, seg));
        Assertions.assertEquals(
            1L,
            DeviceOrderingTest.ok(second.present(budget))
                .frameId());
        Assertions.assertEquals(
            old + 1,
            host.offered(first.key)
                .get(0).epoch);
        host.step();
        s = Invariants.checkAll(second, host, history);
        Assertions.assertEquals(1, s.confirmedFrameId);
        Assertions.assertEquals(
            old + 1,
            host.readbacks(first.key)
                .get(0).epoch);
        TierEOracle fresh = new TierEOracle(second.surface(), SyntheticGlyphs.TABLE);
        fresh.createTexture(T, 2, 2, second.surface().format);
        fresh.updateTexture(T, 0, 0, 2, 2, texels);
        fresh.runSegment(seg);
        Assertions.assertArrayEquals(fresh.pixels(), s.confirmedPixels());
        Assertions.assertEquals(Collections.singletonList("done 1"), listener.events());
        // the released device takes late readbacks as stale
        first.device.onReadback(1, 1, old, 1, p1);
        Assertions.assertEquals(1, first.snapshot().staleReadbacks);
        Assertions.assertEquals(1, s.confirmedFrameId);
    }

    // ---- helpers

    /** t = 16×16 with a pattern; frame 1 clears to 5 and blits t at (10, 10); stepped: K = 1. Its pixels. */
    private static byte[] confirmFrame1(DeviceRig rig) {
        Assertions.assertEquals(T, rig.create(16, 16));
        rig.update(T, 0, 0, 16, 16, DeviceOrderingTest.pattern(256, 21));
        rig.submit(segment(clear(5), blit(T, 0, 0, 16, 16, 10, 10, 0)));
        Assertions.assertEquals(1L, rig.present());
        rig.step();
        Assertions.assertEquals(1, rig.snapshot().confirmedFrameId);
        byte[] p1 = rig.screen();
        Assertions.assertEquals(5, p1[0]);
        return p1;
    }

    private static void assertCleared(DeviceSnapshot s, long epoch) {
        Assertions.assertEquals(epoch, s.epoch);
        Assertions.assertTrue(s.tail.isEmpty());
        Assertions.assertEquals(0, s.unconfirmedFrames);
        Assertions.assertEquals(0, s.queuedBytes);
        Assertions.assertTrue(s.masters.isEmpty());
        Assertions.assertTrue(s.live.isEmpty());
        Assertions.assertEquals(0, s.vramUsed);
        Assertions.assertTrue(s.openSegments.isEmpty());
        Assertions.assertTrue(s.deferredFrees.isEmpty());
        Assertions.assertEquals(0, s.deferredBytes);
        Assertions.assertFalse(s.budgetExhausted);
    }

    private static void assertReleased(Executable call) {
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class, call);
        Assertions.assertEquals("device released", e.getMessage());
    }

    /**
     * A device clock at 0 that, once armed, signals {@link #entered} at its next read, waits for {@link #go} and runs
     * the hook on the calling thread: inside the device's monitor when {@code tick} reads it.
     */
    private static final class GateClock implements LongSupplier {

        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch go = new CountDownLatch(1);
        private volatile Runnable hook;

        void arm(Runnable hook) {
            this.hook = hook;
        }

        @Override
        public long getAsLong() {
            Runnable h = hook;
            if (h != null) {
                hook = null;
                entered.countDown();
                try {
                    go.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread()
                        .interrupt();
                }
                h.run();
            }
            return 0;
        }
    }
}
