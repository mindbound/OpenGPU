package io.github.mindbound.opengpu.core.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.render.Record;

/**
 * The tail bound (28:140; 28:689; 24:179) with H2 and amendment A's I10: the queued bytes count 64 B per resource
 * record in the server tail plus w·h·bpp for a {@code TEX_CREATE} and {@code #data} for a {@code TEX_UPDATE}; nothing
 * counts while the tail is empty, since a record that no {@code FRAME} precedes folds at once (O6; H2); behind a frame
 * {@code createTexture} and {@code updateTexture} are {@code "busy"} one byte past the cap (28:343-344), uncharged and
 * with nothing offered; {@code free} is never refused, so its {@code TEX_FREE}s can pass the cap by at most
 * 64 · {@code maxTextures} (I10); and at most two frames are unconfirmed (28:140). Boundary numbers at T2, where the
 * cap
 * is 1 048 576 B.
 */
class DeviceTailBoundTest {

    private static final int CAP = 1048576;

    @Test
    void eachResourceRecordCountsItsQueuedBytes() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        Assertions.assertEquals(1L, rig.present());
        int t = rig.create(16, 16);
        assertLastQueued(rig, 64 + 256);
        Assertions.assertEquals(320, rig.snapshot().queuedBytes);
        rig.update(t, 2, 3, 4, 5, new byte[20]);
        assertLastQueued(rig, 64 + 20);
        Assertions.assertEquals(404, rig.snapshot().queuedBytes);
        rig.free(t);
        assertLastQueued(rig, 64);
        Assertions.assertEquals(468, rig.snapshot().queuedBytes);
        DeviceOrderingTest.check(rig, history);
        Assertions.assertEquals(
            0,
            rig.snapshot().tail.get(0)
                .queuedBytes(),
            "a FRAME counts nothing");
        rig.step();
        Assertions.assertEquals(0, DeviceOrderingTest.check(rig, history).queuedBytes);
        rig.device.reset();
        assertLastQueued(rig, 0);
        // T3: two bytes per texel
        DeviceRig t3 = DeviceRig.tier(3);
        t3.present();
        int u = t3.create(16, 16);
        Assertions.assertEquals(64 + 512, t3.snapshot().queuedBytes);
        t3.update(u, 0, 0, 4, 5, new byte[40]);
        Assertions.assertEquals(64 + 512 + 64 + 40, t3.snapshot().queuedBytes);
    }

    @Test
    void nothingCountsWhileTheTailIsEmpty() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        int t = rig.create(16, 16);
        rig.update(t, 0, 0, 16, 16, new byte[256]);
        rig.free(t);
        Assertions.assertEquals(0, DeviceOrderingTest.check(rig, history).queuedBytes);
        // an open frame is no FRAME in the tail
        rig.submit(segment());
        // H2: the one create of more than cap - 64 bytes succeeds on an empty tail, and so does a whole update of it
        int big = rig.create(1024, 1024);
        rig.update(big, 0, 0, 1024, 1024, new byte[CAP]);
        DeviceSnapshot s = DeviceOrderingTest.check(rig, history);
        Assertions.assertEquals(0, s.queuedBytes);
        Assertions.assertTrue(s.tail.isEmpty());
        Assertions.assertEquals(CAP, s.vramUsed);
    }

    @Test
    void createsFillTheCapExactlyBehindAFrameAndThenAreBusy() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        rig.present();
        rig.create(1024, 976);
        Assertions.assertEquals(999424 + 64, rig.snapshot().queuedBytes);
        rig.create(128, 383);
        DeviceSnapshot s = DeviceOrderingTest.check(rig, history);
        Assertions.assertEquals(CAP, s.queuedBytes);
        Assertions.assertEquals(999424 + 49024, s.vramUsed);
        // 1 B fits the VRAM (128 B free) and the H23 check, but not the tail
        assertBusyCreate(rig, 1, 1);
        assertBusyCreate(rig, 2, 64);
        rig.step();
        Assertions.assertEquals(0, DeviceOrderingTest.check(rig, history).queuedBytes);
        rig.create(1, 1);
    }

    @Test
    void oneByteMoreOfCreateIsBusyThoughTheVramFits() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        rig.present();
        rig.create(1024, 976);
        // 185·265 = 49 025: 999 424 + 49 025 fits the VRAM; 999 488 + 49 025 + 64 = 1 048 577 does not fit the tail
        assertBusyCreate(rig, 185, 265);
        DeviceOrderingTest.check(rig, history);
        rig.step();
        rig.create(185, 265);
        Assertions.assertEquals(999424 + 49025, DeviceOrderingTest.check(rig, history).vramUsed);
    }

    @Test
    void updatesFillTheCapExactlyBehindAFrameAndThenAreBusy() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        int t = rig.create(1024, 1024);
        rig.present();
        rig.update(t, 0, 0, 1024, 1023, new byte[1024 * 1023]);
        Assertions.assertEquals(1047552 + 64, rig.snapshot().queuedBytes);
        // 28·32 = 896 B: 1 047 616 + 896 + 64 = the cap
        rig.update(t, 0, 992, 28, 32, new byte[896]);
        Assertions.assertEquals(CAP, DeviceOrderingTest.check(rig, history).queuedBytes);
        assertBusyUpdate(rig, t, 0, 0, 1, 1);
        rig.step();
        rig.update(t, 0, 0, 1, 1, new byte[1]);
        Assertions.assertEquals(0, DeviceOrderingTest.check(rig, history).queuedBytes);
    }

    @Test
    void oneByteMoreOfUpdateIsBusy() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        int t = rig.create(1024, 1024);
        rig.present();
        rig.update(t, 0, 0, 1024, 1023, new byte[1024 * 1023]);
        // 13·69 = 897 B: one byte past the cap
        assertBusyUpdate(rig, t, 0, 0, 13, 69);
        rig.update(t, 0, 0, 13, 68, new byte[884]);
        DeviceOrderingTest.check(rig, history);
    }

    /**
     * I10 (amendment A): 256 textures of 63×64 behind {@code FRAME 1} fill the cap exactly (256 · (64 + 4 032)); their
     * 256 {@code TEX_FREE}s, never refused, take the queued bytes to vramBytes + 64 · maxTextures and no further.
     */
    @Test
    void freesPassTheCapByAtMost64BytesPerTexture() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        rig.present();
        int[] handles = new int[256];
        for (int i = 0; i < 256; i++) {
            handles[i] = rig.create(63, 64);
        }
        DeviceSnapshot s = DeviceOrderingTest.check(rig, history);
        Assertions.assertEquals(CAP, s.queuedBytes);
        Assertions.assertEquals(256 * 4032, s.vramUsed);
        assertCreateFails(rig, 1, 1, "too many textures");
        rig.budget.clearEvents();
        for (int h : handles) {
            Assertions.assertEquals(CallResult.TRUE, rig.device.free(h));
        }
        Assertions.assertEquals(Collections.emptyList(), rig.budget.events(), "free is uncharged (28:707)");
        s = DeviceOrderingTest.check(rig, history);
        Assertions.assertEquals(CAP + 64 * 256, s.queuedBytes);
        Assertions.assertEquals(
            (long) rig.device.caps().vramBytes + 64L * rig.device.caps().maxTextures,
            s.queuedBytes,
            "exactly I10's bound");
        Assertions.assertEquals(0, s.vramUsed);
        assertBusyCreate(rig, 1, 1);
        rig.step();
        s = DeviceOrderingTest.check(rig, history);
        Assertions.assertEquals(0, s.queuedBytes);
        Assertions.assertTrue(s.masters.isEmpty());
    }

    @Test
    void atMostTwoFramesAreUnconfirmed() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        Assertions.assertEquals(1L, rig.present());
        Assertions.assertEquals(2L, rig.present());
        Assertions.assertArrayEquals(
            new Object[] { null, "busy" },
            rig.call(b -> rig.device.present(b))
                .toLua());
        DeviceSnapshot s = DeviceOrderingTest.check(rig, history);
        Assertions.assertEquals(2, s.unconfirmedFrames);
        Assertions.assertEquals(2, s.lastFrameId);
        Assertions.assertEquals(2, s.tail.size());
        // a readback of frame 1 alone makes room for one more
        Assertions.assertEquals(1, rig.host.run(rig.key, 1));
        Assertions.assertTrue(rig.host.readback(rig.key));
        Assertions.assertEquals(1, DeviceOrderingTest.check(rig, history).unconfirmedFrames);
        Assertions.assertEquals(3L, rig.present());
        Assertions.assertArrayEquals(
            new Object[] { null, "busy" },
            rig.call(b -> rig.device.present(b))
                .toLua());
        rig.step();
        Assertions.assertEquals(0, DeviceOrderingTest.check(rig, history).unconfirmedFrames);
    }

    // ---- helpers

    private static void assertLastQueued(DeviceRig rig, long bytes) {
        List<Record> offered = rig.host.offered(rig.key);
        Assertions.assertEquals(
            bytes,
            offered.get(offered.size() - 1)
                .queuedBytes());
    }

    /** {@code "busy"}, uncharged, nothing offered, nothing changed. */
    static void assertBusyCreate(DeviceRig rig, int w, int h) {
        assertCreateFails(rig, w, h, "busy");
    }

    /** {@code "busy"} for an update with a payload of the right size, uncharged, nothing offered, nothing changed. */
    static void assertBusyUpdate(DeviceRig rig, int tex, int x, int y, int w, int h) {
        rig.budget.clearEvents();
        String before = Invariants.describe(rig.snapshot());
        int offered = rig.host.offered(rig.key)
            .size();
        byte[] data = new byte[w * h
            * rig.device.surface()
                .bytesPerPixel()];
        Assertions.assertArrayEquals(
            new Object[] { null, "busy" },
            rig.call(b -> rig.device.updateTexture(b, tex, x, y, w, h, data))
                .toLua());
        Assertions.assertEquals(Collections.emptyList(), rig.budget.events());
        Assertions.assertEquals(
            offered,
            rig.host.offered(rig.key)
                .size());
        Assertions.assertEquals(before, Invariants.describe(rig.snapshot()));
    }

    /** createTexture's failure: the message, no charge, nothing offered, nothing changed. */
    static void assertCreateFails(DeviceRig rig, int w, int h, String message) {
        rig.budget.clearEvents();
        String before = Invariants.describe(rig.snapshot());
        int offered = rig.host == null ? 0
            : rig.host.offered(rig.key)
                .size();
        Assertions.assertArrayEquals(
            new Object[] { null, message },
            rig.call(b -> rig.device.createTexture(b, w, h, null))
                .toLua(),
            w + "x" + h);
        Assertions.assertEquals(Collections.emptyList(), rig.budget.events(), "no charge");
        if (rig.host != null) {
            Assertions.assertEquals(
                offered,
                rig.host.offered(rig.key)
                    .size(),
                "nothing offered");
        }
        Assertions.assertEquals(before, Invariants.describe(rig.snapshot()), "nothing changed");
    }
}
