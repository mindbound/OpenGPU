package io.github.mindbound.opengpu.core.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;

/**
 * Plan §3.10: the device never calls its budget while holding its monitor, since OC's {@code Context.pause} takes
 * {@code Machine.this} and {@code state} ({@code Machine.scala:255-276}) while {@code computer.stopped} reaches
 * {@code reset} from code that may hold machine locks. A budget run inline cannot tell, the monitor being reentrant, so
 * the probe's consume and pause ask another thread for a snapshot, which waits for the monitor, and join it for 5 s:
 * "free" if it came, "blocked" if not (as {@code DeviceFrameStatusTest} probes the listener). Every charge is probed:
 * submit, present and readPixels; createTexture and updateTexture on the plain path (T2: 512×512 costs 1.0, the credit,
 * and 512×400 costs 0.781 25); and the pause of the two-phase pattern's retry (T3: 1 024×1 024 costs 8 and pauses
 * 0.166 666 667 s for the create and for its whole update, 28 §8.2; {@link Charges}). The first attempt's
 * {@link LimitReached} calls no budget.
 */
class DeviceBudgetMonitorTest {

    private static final int T = 0x08000000;

    @Test
    @Timeout(30)
    void submitIsChargedOutsideTheMonitor() throws Exception {
        DeviceRig rig = DeviceRig.tier(2);
        SnapshotProbe probe = new SnapshotProbe(rig.device);
        byte[] s = segment(clear(1));
        try {
            Assertions.assertEquals(CallResult.TRUE, rig.device.submit(probe, s));
        } finally {
            probe.joinAll();
        }
        Assertions.assertEquals(Collections.singletonList(consume(Charges.submit(2, s.length))), probe.events);
    }

    @Test
    @Timeout(30)
    void presentIsChargedOutsideTheMonitor() throws Exception {
        DeviceRig rig = DeviceRig.tier(2);
        SnapshotProbe probe = new SnapshotProbe(rig.device);
        try {
            Assertions.assertEquals(
                1L,
                rig.device.present(probe)
                    .frameId());
        } finally {
            probe.joinAll();
        }
        Assertions.assertEquals(Collections.singletonList("consume 0.062500000 free"), probe.events);
    }

    @Test
    @Timeout(30)
    void readPixelsIsChargedOutsideTheMonitor() throws Exception {
        DeviceRig rig = DeviceRig.tier(2);
        rig.submit(segment(clear(9)));
        rig.present();
        rig.step();
        SnapshotProbe probe = new SnapshotProbe(rig.device);
        CallResult r;
        try {
            r = rig.device.readPixels(probe, 0, 0, 320, 100);
        } finally {
            probe.joinAll();
        }
        Assertions.assertEquals(1, r.frameId());
        Assertions.assertEquals(9, r.data()[0]);
        Assertions.assertEquals(Collections.singletonList(consume(Charges.bytes(32000))), probe.events);
    }

    @Test
    @Timeout(30)
    void aPlainCreateAndUpdateAreChargedOutsideTheMonitor() throws Exception {
        DeviceRig rig = DeviceRig.tier(2);
        SnapshotProbe probe = new SnapshotProbe(rig.device);
        byte[] data = DeviceOrderingTest.pattern(204800, 3);
        try {
            Assertions.assertEquals(
                T,
                rig.device.createTexture(probe, 512, 512, null)
                    .handle());
            Assertions.assertEquals(CallResult.TRUE, rig.device.updateTexture(probe, T, 0, 0, 512, 400, data));
        } finally {
            probe.joinAll();
        }
        Assertions.assertEquals(Arrays.asList("consume 1.000000000 free", "consume 0.781250000 free"), probe.events);
        Assertions.assertEquals(
            Arrays.asList(
                "TEX_CREATE e=1 tex=0x08000000 size=512x512 format=1",
                "TEX_UPDATE e=1 tex=0x08000000 at=0,0 size=512x400 len=204800 crc=" + DeviceOrderingTest.crc(data)),
            rig.offeredText());
    }

    @Test
    @Timeout(30)
    void aTwoPhaseRetrysPauseIsOutsideTheMonitor() throws Exception {
        DeviceRig rig = DeviceRig.tier(3);
        SnapshotProbe probe = new SnapshotProbe(rig.device);
        byte[] whole = new byte[2097152];
        try {
            Assertions.assertThrows(LimitReached.class, () -> rig.device.createTexture(probe, 1024, 1024, null));
            Assertions.assertEquals(
                T,
                rig.device.createTexture(probe, 1024, 1024, null)
                    .handle());
            Assertions
                .assertThrows(LimitReached.class, () -> rig.device.updateTexture(probe, T, 0, 0, 1024, 1024, whole));
            Assertions.assertEquals(CallResult.TRUE, rig.device.updateTexture(probe, T, 0, 0, 1024, 1024, whole));
        } finally {
            probe.joinAll();
        }
        Assertions.assertEquals(Arrays.asList("pause 0.166666667 free", "pause 0.166666667 free"), probe.events);
        Assertions.assertEquals(
            2,
            rig.offeredText()
                .size());
    }

    // ---- helpers

    private static String consume(double cost) {
        return String.format(Locale.ROOT, "consume %.9f free", cost);
    }

    /**
     * Records "consume %.9f" and "pause %.9f", each followed by "free" when a snapshot taken on another thread came
     * within 5 s, else "blocked": the device's monitor was held across the budget call. Called on the test's thread.
     */
    private static final class SnapshotProbe implements CallBudget {

        final List<String> events = new ArrayList<>();
        private final Device device;
        private final List<Thread> threads = new ArrayList<>();

        SnapshotProbe(Device device) {
            this.device = device;
        }

        @Override
        public void consume(double cost) {
            events.add(String.format(Locale.ROOT, "consume %.9f %s", cost, snapshotFromAnotherThread()));
        }

        @Override
        public void pause(double seconds) {
            events.add(String.format(Locale.ROOT, "pause %.9f %s", seconds, snapshotFromAnotherThread()));
        }

        /** Joins every snapshot thread, which ends once the call under test has left the monitor. */
        void joinAll() throws InterruptedException {
            for (Thread t : threads) {
                t.join(10000);
                Assertions.assertFalse(t.isAlive(), t.getName() + " still waits for the monitor");
            }
        }

        private String snapshotFromAnotherThread() {
            AtomicReference<DeviceSnapshot> s = new AtomicReference<>();
            Thread t = new Thread(() -> s.set(device.snapshot()), "snapshot " + threads.size());
            threads.add(t);
            t.start();
            try {
                t.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread()
                    .interrupt();
            }
            return s.get() == null ? "blocked" : "free";
        }
    }
}
