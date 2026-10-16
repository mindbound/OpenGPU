package io.github.mindbound.opengpu.gltestkit.scenario;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.text;

import java.util.Arrays;
import java.util.Locale;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.device.CallResult;
import io.github.mindbound.opengpu.core.device.DeviceCaps;
import io.github.mindbound.opengpu.core.device.DeviceSnapshot;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.TeeRenderHost;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.QueryPolicy;
import io.github.mindbound.opengpu.gl.UploadProbe;
import io.github.mindbound.opengpu.gl.host.DeviceStats;
import io.github.mindbound.opengpu.gl.host.PassBudget;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * Scenario 13 (S8 plan §4.3; 28:936, 28:143-144, 28 §9.4): S7's ordering examples through {@code Device} on
 * {@link GlTestHost}, every readback checked by a ShadowOracle through a tee (S8 amendments E), in both quad modes.
 * <ul>
 * <li>28:143, T2: with t created and folded, {@code submit(A)} (A blits t), {@code updateTexture(t)}, {@code submit(B)}
 * (B blits t), {@code free(t)}, {@code present()}: both blits show the update, and t leaves the GPU.</li>
 * <li>28:144, T2, under {@code QueryPolicy.NEVER_READY} and one unit a pass, so frames 5 and 6 run in different passes
 * and their readbacks come apart (amendment C): with frames 1-4 confirmed, {@code FRAME 5} blits t, an update of t
 * lands between {@code FRAME 5} and {@code FRAME 6}, and nothing runs until both are offered. Frame 5 must show the old
 * contents and frame 6 the new ones: the host runs the update's upload between them (S8 plan §3.10). Kills plan §4.7's
 * "a {@code FRAME} run before pending is synced".</li>
 * <li>28 §9.4's worked frame on the full T2 and T3 surfaces: {@code TEX_CREATE}, {@code TEX_UPDATE} and
 * {@code FRAME(1)}, the readback equal to an oracle that ran the same records (28:840, :842).</li>
 * </ul>
 */
public final class DeviceOrderingScenario extends Scenario {

    /** The first texture handle a device gives (28 §6.1): slot 0, generation 0. */
    static final int T = 0x08000000;

    @Override
    protected void run(ScenarioRun run) {
        for (QuadMode mode : QuadMode.values()) {
            final GlTestHost host = run.host(mode);
            TeeRenderHost tee = run.tee(host);
            example1(run, host, tee, mode + " 28:143");
            for (int tier = 2; tier <= 3; tier++) {
                workedFrame(run, host, tee, tier, mode + " 28 §9.4 T" + tier);
            }
            run.close(host);
            final GlTestHost neverReady = run.host(
                SyntheticGlyphs.TABLE,
                mode,
                GlTestHost.defaultOptions(),
                QueryPolicy.NEVER_READY,
                UploadProbe.NONE);
            example2(run, neverReady, run.tee(neverReady), mode + " 28:144");
            run.close(neverReady);
        }
    }

    /** 28:143: an update made while a frame is open, with an empty tail, folds at once and both blits show it. */
    private static void example1(ScenarioRun run, final GlTestHost host, TeeRenderHost tee, String where) {
        DeviceRig rig = DeviceRig.on(DeviceCaps.forTier(2), tee, () -> host.pumpFrame());
        run.check(rig.create(16, 16) == T, where + ": the first handle is not 0x08000000");
        DeviceSteps.settle(host, rig, where + " t created");
        byte[] a = segment(blit(T, 0, 0, 16, 16, 0, 0, 0));
        byte[] b = segment(blit(T, 0, 0, 16, 16, 20, 0, 0));
        byte[] p = DeviceSteps.pattern(256, 1);
        rig.submit(a);
        rig.update(T, 0, 0, 16, 16, p);
        rig.submit(b);
        rig.free(T);
        long id = rig.present();
        run.check(id == 1, where + ": the frame id is " + id);
        DeviceStats queued = host.stats(rig.key);
        run.check(
            queued.queuedFrames == 1 && queued.queuedRecords == 2,
            where + ": the host holds FRAME and TEX_FREE behind it before a pump, the update applied at offer: "
                + queued);
        DeviceSteps.pumpUntilConfirmed(host, rig, id, where);
        DeviceSnapshot s = DeviceSteps.settle(host, rig, where);
        assertRegion(run, rig, 0, 0, 16, 16, p, where + " blit A");
        assertRegion(run, rig, 20, 0, 16, 16, p, where + " blit B");
        TierEOracle expected = new TierEOracle(rig.device.surface(), SyntheticGlyphs.TABLE);
        expected.createTexture(T, 16, 16, rig.device.surface().format);
        expected.updateTexture(T, 0, 0, 16, 16, p);
        expected.runFrame(a, b);
        run.compare(where + ": K's image", rig.device.surface(), expected.pixels(), rig.screen());
        run.check(s.masters.isEmpty() && s.live.isEmpty(), where + ": t has a master or a handle after its TEX_FREE");
        run.check(host.stats(rig.key).gpuTextures == 0, where + ": t is still on the GPU: " + host.stats(rig.key));
        DeviceSteps.ledgerEquation(run, host, rig.key, s, where);
        release(run, host, rig, where);
    }

    /**
     * 28:144: frame 5 shows t's old contents and frame 6 the new ones. NEVER_READY fetches each readback exactly
     * forceFetchAfterPasses passes after its issue, and units(1) runs FRAME 5, the update's upload and FRAME 6 in three
     * passes, so frame 5's readback is delivered alone and K passes 5 before 6.
     */
    private static void example2(ScenarioRun run, final GlTestHost host, TeeRenderHost tee, String where) {
        DeviceRig rig = DeviceRig.on(DeviceCaps.forTier(2), tee, () -> host.pumpFrame());
        run.check(rig.create(16, 16) == T, where + ": the first handle is not 0x08000000");
        byte[] old = DeviceSteps.pattern(256, 3);
        rig.update(T, 0, 0, 16, 16, old);
        for (int i = 1; i <= 4; i++) {
            rig.submit(segment(clear(i)));
            run.check(rig.present() == i, where + ": frame " + i + "'s id");
            DeviceSteps.pumpUntilConfirmed(host, rig, i, where);
        }
        DeviceSteps.settle(host, rig, where + " frames 1-4");
        rig.listener.clear();
        byte[] blitT = segment(blit(T, 0, 0, 16, 16, 100, 50, 0));
        rig.submit(blitT);
        run.check(rig.present() == 5, where + ": frame 5's id");
        byte[] fresh = DeviceSteps.pattern(256, 4);
        rig.update(T, 0, 0, 16, 16, fresh);
        rig.submit(blitT);
        run.check(rig.present() == 6, where + ": frame 6's id");
        DeviceStats queued = host.stats(rig.key);
        run.check(
            queued.queuedFrames == 2 && queued.queuedRecords == 3,
            where + ": the host holds FRAME 5, TEX_UPDATE and FRAME 6 before a pump: " + queued);
        long k = 0;
        for (int pumps = 0; pumps < DeviceSteps.MAX_PUMPS && k < 5; pumps++) {
            host.pumpFrame(PassBudget.units(1));
            k = rig.snapshot().confirmedFrameId;
        }
        if (run.check(k == 5, where + ": K went from 4 to " + k + ", not to 5 alone")) {
            assertRead(run, rig, 100, 50, 16, 16, 5, old, where + " frame 5 shows the old contents");
        }
        for (int pumps = 0; pumps < DeviceSteps.MAX_PUMPS && k < 6; pumps++) {
            host.pumpFrame(PassBudget.units(1));
            k = rig.snapshot().confirmedFrameId;
        }
        if (run.check(k == 6, where + ": K is " + k + ", not 6")) {
            assertRead(run, rig, 100, 50, 16, 16, 6, fresh, where + " frame 6 shows the new contents");
        }
        run.check(
            rig.listener.events()
                .equals(Arrays.asList("done 5", "done 6")),
            where + ": the statuses are " + rig.listener.events());
        DeviceSteps.ledgerEquation(run, host, rig.key, DeviceSteps.settle(host, rig, where), where);
        release(run, host, rig, where);
    }

    /** 28 §9.4's worked frame (28:823-842): created, updated and drawn in one frame on the tier's full surface. */
    private static void workedFrame(ScenarioRun run, final GlTestHost host, TeeRenderHost tee, int tier, String where) {
        DeviceRig rig = DeviceRig.on(DeviceCaps.forTier(tier), tee, () -> host.pumpFrame());
        SurfaceDesc surface = rig.device.surface();
        byte[] sprite = DeviceSteps.pattern(16 * 16 * surface.bytesPerPixel(), 9);
        byte[] s = workedSegment(tier);
        run.check(s.length == 64, where + ": the worked segment is " + s.length + " bytes, 28:842 says 64");
        run.check(rig.create(16, 16) == T, where + ": the first handle is not 0x08000000");
        rig.update(T, 0, 0, 16, 16, sprite);
        rig.submit(s);
        long id = rig.present();
        run.check(id == 1, where + ": the frame id is " + id);
        run.check(
            rig.snapshot().tail.size() == 1,
            where + ": the server tail holds " + rig.snapshot().tail.size() + " records, FRAME(1) only (O6)");
        DeviceSteps.pumpUntilConfirmed(host, rig, id, where);
        TierEOracle expected = new TierEOracle(surface, SyntheticGlyphs.TABLE);
        expected.createTexture(T, 16, 16, surface.format);
        expected.updateTexture(T, 0, 0, 16, 16, sprite);
        expected.runFrame(s);
        run.compare(where + ": K's image", surface, expected.pixels(), rig.screen());
        run.compare(
            where + ": the snapshot's K",
            surface,
            expected.pixels(),
            rig.snapshot()
                .confirmedPixels());
        DeviceSteps.ledgerEquation(run, host, rig.key, DeviceSteps.settle(host, rig, where), where);
        DeviceSteps.formula(run, host, rig.key, surface, 16L * 16 * surface.bytesPerPixel(), where);
        release(run, host, rig, where);
    }

    /** The 64-byte worked segment of 28 §9.4 for the tier (28:823-824, :842). */
    static byte[] workedSegment(int tier) {
        if (tier == 2) {
            return segment(
                clear(0),
                fill(10, 20, 100, 50, 196),
                blit(T, 0, 0, 16, 16, 150, 40, 1),
                text(8, 180, 255, 0, 1, 0, "Hi"));
        }
        return segment(
            clear(0xFF000000),
            fill(10, 20, 100, 50, 0xFFFF8000),
            blit(T, 0, 0, 16, 16, 150, 40, 1),
            text(8, 180, 0xFFFFFFFF, 0xFF000000, 1, 0, "Hi"));
    }

    /** The device released; one pump later the key has no ledger row (24:218). */
    static void release(ScenarioRun run, GlTestHost host, DeviceRig rig, String where) {
        rig.device.release();
        host.pumpFrame();
        run.check(
            host.ledger()
                .rows(rig.key.session, rig.key.deviceId)
                .isEmpty(),
            where + ": ledger rows after the release and a pump: "
                + host.ledger()
                    .rows(rig.key.session, rig.key.deviceId));
    }

    /** K's region, rows top first, equals the texels (28 §5.7 layout). */
    static void assertRegion(ScenarioRun run, DeviceRig rig, int x, int y, int w, int h, byte[] texels, String where) {
        CallResult r = rig.call(b -> rig.device.readPixels(b, x, y, w, h));
        if (run.check(r.isOk(), where + ": readPixels returned " + r)) {
            run.check(Arrays.equals(texels, r.data()), where + ": the region differs from the texels");
        }
    }

    /** readPixels of the region returns these bytes and this frame id. */
    static void assertRead(ScenarioRun run, DeviceRig rig, int x, int y, int w, int h, long frameId, byte[] expected,
        String where) {
        CallResult r = rig.call(b -> rig.device.readPixels(b, x, y, w, h));
        if (run.check(r.isOk() && r.frameId() == frameId, where + ": readPixels returned " + r)) {
            run.check(
                Arrays.equals(expected, r.data()),
                String.format(Locale.ROOT, "%s: the region (%d, %d, %d, %d) differs", where, x, y, w, h));
        }
    }
}
