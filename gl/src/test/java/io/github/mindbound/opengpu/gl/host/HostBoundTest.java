package io.github.mindbound.opengpu.gl.host;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.device.CallResult;
import io.github.mindbound.opengpu.core.device.DeviceCaps;
import io.github.mindbound.opengpu.core.device.DeviceSnapshot;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.ShadowOracle;
import io.github.mindbound.opengpu.core.fixtures.device.TeeRenderHost;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;

/**
 * H24's bound with Q-S8-1's option A (S8 plan §3.10, §4.1; amendment A): on T3 caps, while the host does not pass, what
 * it holds per device stays within the mirrors' VRAM cap and the queue's tail bound, whatever is offered; option B
 * lacks that bound. Copy-on-write is observed directly: whole-texture updates adopt their payloads (no mirror array
 * allocated, the payload itself held), a partial update after a whole one clones exactly once, and a shared mirror is
 * staged outside the device's lock while an owned one is staged under it (amendment D). Repeated payloads are one
 * immutable array, as {@code Device} offers it uncopied (B5).
 */
class HostBoundTest {

    private static final DeviceCaps T3 = DeviceCaps.forTier(3);
    private static final int SIDE = 1024;
    /** A whole 1 024² rgb565 texture: 2 MiB (28 §6.2). */
    private static final int WHOLE = SIDE * SIDE * 2;

    @Test
    void wholeTextureUpdatesWithTheServerTailEmptyHoldOneTextureAndAllocateNoMirrorArray() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = new GlHostCore(
            backend,
            HostOptions.defaults()
                .stallAfterNanos(Long.MAX_VALUE)
                .expand(HostOptions.Expand.ALWAYS));
        DeviceRig rig = DeviceRig.on(T3, core, () -> {});
        int t = rig.create(SIDE, SIDE);
        byte[] payload = pattern(WHOLE, 3);
        byte[] original = payload.clone();
        long started = System.nanoTime();
        for (int i = 0; i < 2000; i++) {
            rig.update(t, 0, 0, SIDE, SIDE, payload);
        }
        long nanos = System.nanoTime() - started;
        DeviceStats s = core.stats(rig.key);
        // The host never passed: one texture's bytes, nothing queued (Q-S8-1's bound with the tail empty)
        Assertions.assertEquals(1, s.mirrorTextures);
        Assertions.assertEquals(WHOLE, s.mirrorBytes);
        Assertions.assertEquals(0, s.queuedRecords);
        Assertions.assertEquals(0, s.queuedBytes);
        Assertions.assertEquals(2001, s.appliedAtOffer);
        // Copy-on-write (amendment A): every update adopted by reference, no array allocated, cloned or copied
        Assertions.assertEquals(2000, s.mirrorAdoptions);
        Assertions.assertEquals(0, s.mirrorAllocations);
        Assertions.assertEquals(0, s.mirrorClones);
        Assertions.assertEquals(0, s.mirrorCopyBytes);
        Mirror m = core.device(rig.key)
            .mirror(t);
        Assertions.assertTrue(m.shared());
        Assertions.assertSame(payload, m.array(), "the mirror holds the payload itself");
        Assertions.assertEquals(1, s.pendingTextures, "one create, of the whole mirror");
        DeviceSnapshot snap = rig.snapshot();
        Assertions.assertTrue(snap.tail.isEmpty());
        Assertions.assertEquals(WHOLE, snap.vramUsed);

        // One pass: one upload for the texture, staged outside the device's lock (the array is shared); then the
        // readback equals the reference
        List<Boolean> lockedStaging = new ArrayList<>();
        backend.stagingProbe(bytes -> lockedStaging.add(Thread.holdsLock(core.device(rig.key))));
        rig.submit(segment(blit(t, 0, 0, SIDE, SIDE, -100, -50, 0)));
        long frame = rig.present();
        core.pass(PassBudget.unlimited());
        Assertions.assertEquals(1, backend.device(rig.key).creates);
        Assertions.assertEquals(0, backend.device(rig.key).updates);
        Assertions.assertEquals(Collections.singletonList(false), lockedStaging);
        core.pass(PassBudget.unlimited());
        Assertions.assertEquals(frame, rig.snapshot().confirmedFrameId);
        byte[] reference = GlHostCoreTest.replay(
            T3.surface,
            Arrays.<Record>asList(
                new TexCreateRecord(1, t, SIDE, SIDE, Formats.RGB565),
                new TexUpdateRecord(1, t, 0, 0, SIDE, SIDE, original),
                new FrameRecord(
                    1,
                    frame,
                    new byte[][] { segment(blit(t, 0, 0, SIDE, SIDE, -100, -50, 0)) },
                    FrameTotals.EMPTY)));
        GlHostCoreTest.assertPixels(T3.surface, reference, rig.screen());
        Assertions.assertArrayEquals(original, payload, "the payload was never written");
        System.out.println("HostBoundTest: 2 000 whole 2 MiB updates through Device in " + nanos / 1_000_000 + " ms");
    }

    @Test
    void withMirrorsOffTheSameUpdatesQueueEveryRecord() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = new GlHostCore(
            backend,
            HostOptions.defaults()
                .mirrors(false)
                .stallAfterNanos(Long.MAX_VALUE));
        DeviceRig rig = DeviceRig.on(T3, core, () -> {});
        int t = rig.create(SIDE, SIDE);
        byte[] payload = pattern(WHOLE, 4);
        for (int i = 0; i < 2000; i++) {
            rig.update(t, 0, 0, SIDE, SIDE, payload);
        }
        // Option B holds every record until the render thread runs: bounded only by time (Q-S8-1)
        DeviceStats s = core.stats(rig.key);
        Assertions.assertEquals(2001, s.queuedRecords);
        Assertions.assertEquals(2001L * (Record.RESOURCE_OVERHEAD + WHOLE), s.queuedBytes);
        Assertions.assertEquals(0, s.mirrorTextures);
        Assertions.assertTrue(rig.snapshot().tail.isEmpty(), "the device holds none of it: its tail is empty");
    }

    @Test
    void withTwoFramesInFlightTheQueueStaysWithinTheTailBound() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = new GlHostCore(
            backend,
            HostOptions.defaults()
                .stallAfterNanos(Long.MAX_VALUE)
                .expand(HostOptions.Expand.ALWAYS));
        List<String> failures = Collections.synchronizedList(new ArrayList<String>());
        ShadowOracle shadow = new ShadowOracle(SyntheticGlyphs.TABLE);
        DeviceRig rig = DeviceRig.on(T3, new TeeRenderHost(core, shadow, failures), () -> {});
        DeviceKey key = rig.key;
        rig.submit(segment(fill(0, 0, 64, 64, 0xFF102030)));
        rig.present();
        rig.submit(segment(fill(32, 32, 64, 64, 0xFF405060)));
        rig.present();
        long bound = T3.vramBytes + 64L * T3.maxTextures + T3.maxUnconfirmedFrames * (long) T3.limits.frameBytes;
        // Creates, updates and frees behind the two frames, until the device answers busy (28:140; H23)
        byte[] payload = pattern(64 * 64 * 2, 5);
        List<Integer> textures = new ArrayList<>();
        for (int i = 0; i < 128; i++) {
            textures.add(rig.create(64, 64));
            assertWithin(core, rig, key, bound);
        }
        int updates = 0;
        boolean busy = false;
        for (int i = 0; i < 10_000 && !busy; i++) {
            final int tex = textures.get(i % textures.size());
            CallResult r = rig.call(b -> rig.device.updateTexture(b, tex, 0, 0, 64, 64, payload));
            if (r.isOk()) {
                updates++;
            } else {
                Assertions.assertEquals("busy", r.message());
                busy = true;
            }
            assertWithin(core, rig, key, bound);
        }
        Assertions.assertTrue(busy, "the tail bound was reached");
        for (int tex : textures) {
            rig.free(tex);
            assertWithin(core, rig, key, bound);
        }
        DeviceStats s = core.stats(key);
        Assertions.assertEquals(2, s.queuedFrames);
        Assertions.assertEquals(2 + 128 + updates + 128, s.queuedRecords);
        Assertions.assertEquals(0, s.mirrorTextures, "everything waits behind the frames");
        Assertions.assertTrue(s.queuedBytes > T3.vramBytes - 64 * 64 * 2 - 64, "near the bound: " + s.queuedBytes);
        System.out.println("HostBoundTest: " + s.queuedBytes + " B queued behind two frames, bound " + bound);

        // One pass runs both frames and everything behind them; the readback and the device agree with the oracle
        core.pass(PassBudget.unlimited());
        core.pass(PassBudget.unlimited());
        Assertions.assertEquals(Collections.emptyList(), failures);
        Assertions.assertEquals(2, rig.snapshot().confirmedFrameId);
        GlHostCoreTest.pumpUntilIdle(core, key);
        Assertions.assertTrue(rig.snapshot().tail.isEmpty());
        Assertions.assertArrayEquals(shadow.pixelsNow(key), rig.screen());
        DeviceStats after = core.stats(key);
        // G10 (28:1083): the host's GPU textures equal the device's VRAM, none, once idle with the tail empty
        Assertions.assertEquals(0, after.gpuTextures);
        Assertions.assertEquals(0, after.gpuTextureBytes);
        Assertions.assertEquals(0, after.mirrorTextures);
        Assertions.assertEquals(0, core.contractViolations());
    }

    @Test
    void churnWithAnOpenFrameKeepsTheMirrorsWithinTheTextureCap() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = new GlHostCore(
            backend,
            HostOptions.defaults()
                .stallAfterNanos(Long.MAX_VALUE));
        DeviceRig rig = DeviceRig.on(T3, core, () -> {});
        DeviceKey key = rig.key;
        // 28:1074: one open frame, then 300 × (create 1×1, free); its frees are deferred and still count (H23)
        rig.submit(segment(fill(0, 0, 1, 1, 0xFF000000)));
        int created = 0;
        int busy = 0;
        for (int i = 0; i < 300; i++) {
            CallResult r = rig.call(b -> rig.device.createTexture(b, 1, 1, null));
            if (r.isOk()) {
                created++;
                rig.free(r.handle());
            } else {
                Assertions.assertEquals("busy", r.message());
                busy++;
            }
            DeviceStats s = core.stats(key);
            Assertions.assertTrue(s.mirrorTextures <= T3.maxTextures, "mirrors " + s.mirrorTextures);
            Assertions.assertTrue(s.pendingTextures <= 2 * T3.maxTextures, "pending " + s.pendingTextures);
        }
        Assertions.assertEquals(256, created);
        Assertions.assertEquals(44, busy);
        Assertions.assertEquals(256, core.stats(key).mirrorTextures, "created, freed in the open frame, kept");
        rig.present();
        GlHostCoreTest.pumpUntilIdle(core, key);
        DeviceStats s = core.stats(key);
        Assertions.assertEquals(0, s.mirrorTextures);
        Assertions.assertEquals(0, s.gpuTextures);
        Assertions.assertEquals(1, rig.snapshot().confirmedFrameId);
    }

    @Test
    void aPartialUpdateAfterAWholeOneClonesExactlyOnce() {
        OracleBackend backend = new OracleBackend();
        GlHostCore core = new GlHostCore(
            backend,
            HostOptions.defaults()
                .stallAfterNanos(Long.MAX_VALUE));
        DeviceKey key = new DeviceKey(3, 3);
        core.open(key, T3.surface, new GlHostCoreTest.Sink());
        int tex = 0x08000000;
        int other = 0x08010000;
        core.offer(key, new TexCreateRecord(1, tex, SIDE, SIDE, Formats.RGB565));
        HostDevice hd = core.device(key);
        Mirror m = hd.mirror(tex);
        Assertions.assertNull(m.array(), "TEX_CREATE allocates nothing (28:550)");
        byte[] w1 = pattern(WHOLE, 7);
        byte[] w1Copy = w1.clone();
        core.offer(key, new TexUpdateRecord(1, tex, 0, 0, SIDE, SIDE, w1));
        Assertions.assertSame(w1, m.array());
        DeviceStats s = core.stats(key);
        Assertions.assertEquals(1, s.mirrorAdoptions);
        Assertions.assertEquals(0, s.mirrorClones);

        byte[] p1 = pattern(3 * 2 * 2, 8);
        core.offer(key, new TexUpdateRecord(1, tex, 1020, 5, 3, 2, p1));
        s = core.stats(key);
        Assertions.assertEquals(1, s.mirrorClones, "the first partial update after a whole one clones");
        Assertions.assertEquals(0, s.mirrorAllocations);
        Assertions.assertNotSame(w1, m.array());
        Assertions.assertFalse(m.shared());
        Assertions.assertArrayEquals(w1Copy, w1, "the adopted payload is never written");
        byte[] p2 = pattern(2 * 2 * 2, 9);
        core.offer(key, new TexUpdateRecord(1, tex, 0, 1022, 2, 2, p2));
        Assertions.assertEquals(1, core.stats(key).mirrorClones, "an owned array is written in place");
        // The mirror is W1 with P1's and P2's rows
        byte[] expected = w1Copy.clone();
        rows(expected, SIDE, 1020, 5, 3, 2, p1);
        rows(expected, SIDE, 0, 1022, 2, 2, p2);
        Assertions.assertArrayEquals(expected, m.texels());

        // A whole update makes it shared again; the next partial one clones again
        byte[] w2 = pattern(WHOLE, 10);
        core.offer(key, new TexUpdateRecord(1, tex, 0, 0, SIDE, SIDE, w2));
        Assertions.assertSame(w2, m.array());
        core.offer(key, new TexUpdateRecord(1, tex, 7, 7, 1, 1, new byte[2]));
        s = core.stats(key);
        Assertions.assertEquals(2, s.mirrorAdoptions);
        Assertions.assertEquals(2, s.mirrorClones);
        // A partial update of a texture never written allocates its zeros instead
        core.offer(key, new TexCreateRecord(1, other, 4, 4, Formats.RGB565));
        core.offer(key, new TexUpdateRecord(1, other, 1, 1, 1, 1, new byte[] { 1, 2 }));
        s = core.stats(key);
        Assertions.assertEquals(1, s.mirrorAllocations);
        Assertions.assertEquals(2, s.mirrorClones);
        Assertions.assertEquals(2L * WHOLE + 3 * 2 * 2 + 2 * 2 * 2 + 2 + 2, s.mirrorCopyBytes);

        // On the render thread: the owned mirrors are staged under the device's lock, a shared one outside it
        List<Boolean> lockedStaging = new ArrayList<>();
        backend.stagingProbe(bytes -> lockedStaging.add(Thread.holdsLock(hd)));
        core.pass(PassBudget.unlimited());
        Assertions.assertEquals(Arrays.asList(true, true), lockedStaging, "both creates are of owned mirrors");
        GlHostCoreTest.assertGpuTexture(core, backend, key, tex);
        GlHostCoreTest.assertGpuTexture(core, backend, key, other);
        lockedStaging.clear();
        byte[] w3 = pattern(WHOLE, 11);
        core.offer(key, new TexUpdateRecord(1, tex, 0, 0, SIDE, SIDE, w3));
        core.offer(key, new TexUpdateRecord(1, other, 0, 0, 2, 1, new byte[] { 5, 6, 7, 8 }));
        core.pass(PassBudget.unlimited());
        Assertions.assertEquals(Arrays.asList(false, true), lockedStaging, "shared W3 outside, owned under the lock");
        GlHostCoreTest.assertGpuTexture(core, backend, key, tex);
        GlHostCoreTest.assertGpuTexture(core, backend, key, other);
        Assertions.assertEquals(0, core.contractViolations());
    }

    // ---- Helpers

    /** The host's queue within the bound, and its resource bytes exactly the device's tail's (28:140). */
    private static void assertWithin(GlHostCore core, DeviceRig rig, DeviceKey key, long bound) {
        DeviceStats s = core.stats(key);
        Assertions.assertTrue(s.queuedBytes <= bound, s.queuedBytes + " B queued, over " + bound);
        long frameBytes = 0;
        for (Record r : core.device(key)
            .queued()) {
            if (r.kind() == Record.FRAME) {
                frameBytes += ((FrameRecord) r).byteCount();
            }
        }
        Assertions.assertEquals(rig.snapshot().queuedBytes, s.queuedBytes - frameBytes);
    }

    private static byte[] pattern(int n, int seed) {
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) (i * 13 + seed * 7 + (i >>> 11));
        }
        return out;
    }

    /** The 28 §5.7 rows of a w×h rgb565 region written into a texture's bytes. */
    private static void rows(byte[] texture, int width, int x, int y, int w, int h, byte[] payload) {
        for (int j = 0; j < h; j++) {
            System.arraycopy(payload, j * w * 2, texture, ((y + j) * width + x) * 2, w * 2);
        }
    }
}
