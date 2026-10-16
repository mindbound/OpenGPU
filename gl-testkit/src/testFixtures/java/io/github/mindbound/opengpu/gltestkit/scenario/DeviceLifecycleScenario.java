package io.github.mindbound.opengpu.gltestkit.scenario;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;

import java.util.Collections;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.device.CallResult;
import io.github.mindbound.opengpu.core.device.Device;
import io.github.mindbound.opengpu.core.device.DeviceCaps;
import io.github.mindbound.opengpu.core.device.DeviceSnapshot;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.ManualClock;
import io.github.mindbound.opengpu.core.fixtures.device.RecordingBudget;
import io.github.mindbound.opengpu.core.fixtures.device.RecordingListener;
import io.github.mindbound.opengpu.core.fixtures.device.TeeRenderHost;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.QueryPolicy;
import io.github.mindbound.opengpu.gl.UploadProbe;
import io.github.mindbound.opengpu.gl.host.DeviceStats;
import io.github.mindbound.opengpu.gl.host.PassBudget;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * Scenario 14 (S8 plan §4.3; 28:937, 28:146-155, :199; H38), through {@code Device} on {@link GlTestHost} under
 * {@code QueryPolicy.NEVER_READY}, so each readback arrives exactly {@code forceFetchAfterPasses} passes after its
 * issue and two never arrive in one pass (amendment C); every readback checked by a ShadowOracle through a tee, in both
 * quad modes.
 * <ul>
 * <li>K = 1 (frame 1 blits t onto a clear), then frames 2 and 3 in flight with a texture u created between them, and an
 * open frame with t's deferred free; the host has run none of them, frame 2 only (one unit), or both (an unlimited
 * pass); then {@code reset()}. The host drops what it still queues (H38): four records, one, or none. Whatever it
 * ran, its surface is P1, K's pixels, with no texture on the GPU; the readback of an old epoch in flight, if any, is
 * stale; no status is reported for frames 2 and 3; and frame 4 of epoch 2 draws on P1 (28:150-155). Kills plan §4.7's
 * "{@code RESET} not dropping older queued records, or dropping newer ones".</li>
 * <li>{@code release()} with a texture live: one pump later the key has no ledger row; then a new {@code Device} on
 * the key with a higher epoch starts from the cleared surface, and its first readback, of that epoch, matches a fresh
 * oracle (28:151, :199; H46).</li>
 * </ul>
 */
public final class DeviceLifecycleScenario extends Scenario {

    /** The first texture handle a device gives (28 §6.1). */
    static final int T = 0x08000000;

    /** How far the host got with frames 2 and 3 when the reset came. */
    enum Progress {

        /** No pump: frame 2, u's create and update, and frame 3 still queued. */
        NONE(0, 4, 0),
        /** One unit: frame 2 ran, u's records applied to the mirrors, frame 3 queued; frame 2's readback in flight. */
        ONE(1, 1, 1),
        /** An unlimited pass: both ran, u reached the GPU; frame 3's readback in flight. */
        BOTH(2, 0, 1);

        final int framesRun;
        final int dropped;
        final int stale;

        Progress(int framesRun, int dropped, int stale) {
            this.framesRun = framesRun;
            this.dropped = dropped;
            this.stale = stale;
        }
    }

    @Override
    protected void run(ScenarioRun run) {
        for (QuadMode mode : QuadMode.values()) {
            GlTestHost host = run.host(
                SyntheticGlyphs.TABLE,
                mode,
                GlTestHost.defaultOptions(),
                QueryPolicy.NEVER_READY,
                UploadProbe.NONE);
            TeeRenderHost tee = run.tee(host);
            for (Progress progress : Progress.values()) {
                resetWithFramesInFlight(run, host, tee, progress, mode + " reset, host ran " + progress);
            }
            releaseAndOpenAgain(run, host, tee, mode + " release and a new device");
            run.close(host);
        }
    }

    private static void resetWithFramesInFlight(ScenarioRun run, final GlTestHost host, TeeRenderHost tee,
        Progress progress, String where) {
        DeviceRig rig = DeviceRig.on(DeviceCaps.forTier(2), tee, () -> host.pumpFrame());
        byte[] p1 = confirmFrame1(run, host, rig, where);
        long ran = host.stats(rig.key).framesExecuted;
        rig.submit(segment(fill(0, 0, 50, 50, 9)));
        run.check(rig.present() == 2, where + ": frame 2's id");
        int u = rig.create(8, 8);
        rig.update(u, 0, 0, 8, 8, DeviceSteps.pattern(64, 22));
        rig.submit(segment(fill(100, 100, 50, 50, 11), blit(u, 0, 0, 8, 8, 0, 0, 0)));
        run.check(rig.present() == 3, where + ": frame 3's id");
        rig.submit(segment(clear(1)));
        rig.free(T);
        if (progress == Progress.ONE) {
            host.pumpFrame(PassBudget.units(1));
        } else if (progress == Progress.BOTH) {
            host.pumpFrame();
        }
        DeviceStats before = host.stats(rig.key);
        run.check(
            before.framesExecuted - ran == progress.framesRun,
            where + ": the host ran " + (before.framesExecuted - ran) + " of frames 2 and 3");
        rig.listener.clear();
        rig.device.reset();
        DeviceStats after = host.stats(rig.key);
        run.check(
            after.recordsDropped - before.recordsDropped == progress.dropped && after.queuedRecords == 0,
            where + ": the RESET dropped "
                + (after.recordsDropped - before.recordsDropped)
                + " queued records, "
                + progress.dropped
                + " expected (H38): "
                + after);
        run.check(after.pendingReset, where + ": no RESET pending on the host: " + after);
        DeviceSnapshot s = rig.snapshot();
        run.check(
            s.epoch == 2 && s.confirmedFrameId == 1 && s.lastFrameId == 3 && s.tail.isEmpty(),
            where + ": after reset(): " + s);
        // Frame 4 of epoch 2 draws on K's pixels (28:150)
        byte[] s4 = segment(fill(30, 30, 10, 10, 77));
        rig.submit(s4);
        run.check(rig.present() == 4, where + ": frame 4's id");
        DeviceSteps.pumpUntilConfirmed(host, rig, 4, where);
        s = DeviceSteps.settle(host, rig, where + " frame 4");
        TierEOracle expected = new TierEOracle(rig.device.surface(), SyntheticGlyphs.TABLE);
        expected.reset(p1);
        expected.runSegment(s4);
        run.compare(where + ": frame 4 on P1", rig.device.surface(), expected.pixels(), rig.screen());
        run.check(s.confirmedFrameId == 4, where + ": K is " + s.confirmedFrameId);
        run.check(
            s.staleReadbacks == progress.stale && s.rejectedReadbacks == 0,
            where + ": "
                + s.staleReadbacks
                + " stale and "
                + s.rejectedReadbacks
                + " rejected readbacks, "
                + progress.stale
                + " stale expected (28:155)");
        run.check(
            rig.listener.events()
                .equals(Collections.singletonList("done 4")),
            where + ": the statuses after the reset are " + rig.listener.events() + ", none for frames 2 and 3");
        DeviceStats end = host.stats(rig.key);
        run.check(
            end.resetsRun == 1 && end.gpuTextures == 0,
            where + ": one RESET run and no texture left on the GPU: " + end);
        DeviceSteps.ledgerEquation(run, host, rig.key, s, where);
        DeviceOrderingScenario.release(run, host, rig, where);
    }

    /** 28:151, :199: a released key has no GL object after a pump; a new device on it starts cleared. */
    private static void releaseAndOpenAgain(ScenarioRun run, final GlTestHost host, TeeRenderHost tee, String where) {
        DeviceRig first = DeviceRig.on(DeviceCaps.forTier(2), tee, () -> host.pumpFrame());
        confirmFrame1(run, host, first, where);
        first.create(4, 4);
        first.device.release();
        long old = first.device.epoch();
        run.check(old == 2, where + ": the released device's epoch is " + old);
        host.pumpFrame();
        run.check(
            host.ledger()
                .rows(first.key.session, first.key.deviceId)
                .isEmpty(),
            where + ": ledger rows after the release and a pump: "
                + host.ledger()
                    .rows(first.key.session, first.key.deviceId));
        RecordingListener listener = new RecordingListener();
        Device second = Device
            .create(first.key, DeviceCaps.forTier(2), tee, Runnable::run, listener, new ManualClock(), old + 1);
        RecordingBudget budget = new RecordingBudget();
        SurfaceDesc surface = second.surface();
        CallResult created = budget.call(() -> second.createTexture(budget, 2, 2, null));
        run.check(created.isOk() && created.handle() == T, where + ": the new device's first handle: " + created);
        byte[] texels = { 5, 6, 7, 8 };
        run.check(
            budget.call(() -> second.updateTexture(budget, T, 0, 0, 2, 2, texels))
                .isOk(),
            where + ": update");
        byte[] seg = segment(fill(4, 4, 3, 3, 2), blit(T, 0, 0, 2, 2, 1, 1, 0));
        run.check(
            budget.call(() -> second.submit(budget, seg))
                .isOk(),
            where + ": submit");
        CallResult presented = budget.call(() -> second.present(budget));
        run.check(presented.isOk() && presented.frameId() == 1, where + ": the new device's first frame: " + presented);
        host.pumpUntil(
            () -> second.snapshot().confirmedFrameId == 1,
            DeviceSteps.MAX_PUMPS,
            where + ": the new device's K reaching 1");
        TierEOracle fresh = new TierEOracle(surface, SyntheticGlyphs.TABLE);
        fresh.createTexture(T, 2, 2, surface.format);
        fresh.updateTexture(T, 0, 0, 2, 2, texels);
        fresh.runSegment(seg);
        run.compare(
            where + ": the new device's K",
            surface,
            fresh.pixels(),
            second.snapshot()
                .confirmedPixels());
        DeviceStats stats = host.stats(first.key);
        run.check(
            stats.lastReadbackEpoch == old + 1 && stats.lastReadbackFrameId == 1,
            where + ": the new opening's readback is e" + stats.lastReadbackEpoch + " #" + stats.lastReadbackFrameId);
        run.check(
            listener.events()
                .equals(Collections.singletonList("done 1")),
            where + ": the new device's statuses " + listener.events());
        second.release();
        host.pumpFrame();
        run.check(
            host.ledger()
                .rows(first.key.session, first.key.deviceId)
                .isEmpty(),
            where + ": ledger rows after the new device's release: "
                + host.ledger()
                    .rows(first.key.session, first.key.deviceId));
    }

    /** t = 16×16 of a pattern; frame 1 clears to 5 and blits t at (10, 10); pumped until K = 1. K's pixels. */
    private static byte[] confirmFrame1(ScenarioRun run, GlTestHost host, DeviceRig rig, String where) {
        run.check(rig.create(16, 16) == T, where + ": the first handle is not 0x08000000");
        rig.update(T, 0, 0, 16, 16, DeviceSteps.pattern(256, 21));
        rig.submit(segment(clear(5), blit(T, 0, 0, 16, 16, 10, 10, 0)));
        run.check(rig.present() == 1, where + ": frame 1's id");
        DeviceSteps.pumpUntilConfirmed(host, rig, 1, where);
        DeviceSteps.settle(host, rig, where + " frame 1");
        byte[] p1 = rig.screen();
        run.check(p1[0] == 5, where + ": K's first pixel is " + p1[0]);
        return p1;
    }
}
