package io.github.mindbound.opengpu.gltestkit.scenario;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.device.DeviceCaps;
import io.github.mindbound.opengpu.core.device.DeviceSnapshot;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.TeeRenderHost;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.QueryPolicy;
import io.github.mindbound.opengpu.gl.host.DeviceStats;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * Scenario 16 (S8 plan §4.3, §3.10; Q-S8-1 with amendment A's copy-on-write; H24, 28:1084): what the host holds while
 * its render loop does not run. Through {@code Device} on {@link GlTestHost} with T2's caps, before the host's first
 * pump: a 1 024² texture (the whole 1 MiB of T2's VRAM) and 300 whole-texture updates, with the server tail empty, the
 * first 299 of one immutable payload and the last of another (amendment E: the device offers the arrays uncopied).
 * With the mirrors on (the owner's option A), the host then holds no queued record and one mirror, the last payload
 * itself (300 adoptions, no array allocated or cloned); one pump makes the device's GL objects and uploads the
 * texture once (G13b's probe), and the frame that blits it reads back equal to the oracle, every readback also checked
 * by a ShadowOracle. With the mirrors off (option B) the host queues all 301 records and uploads 301 times, and the
 * readback is the same, byte for byte. In both quad modes. Kills plan §4.7's "H24 one upload" rows: an update applied
 * as its own upload, or a record queued while the queue is empty.
 */
public final class H24BoundScenario extends Scenario {

    /** Plan §4.3's count of whole-texture updates. */
    static final int UPDATES = 300;
    /** T2's largest texture: all of its VRAM (28 §8.1). */
    static final int SIDE = 1024;

    @Override
    protected void run(ScenarioRun run) {
        byte[] first = DeviceSteps.pattern(SIDE * SIDE, 16);
        byte[] last = DeviceSteps.pattern(SIDE * SIDE, 17);
        for (QuadMode mode : QuadMode.values()) {
            byte[] withMirrors = bound(run, mode, true, first, last);
            byte[] without = bound(run, mode, false, first, last);
            if (withMirrors != null && without != null) {
                run.compare(
                    mode + ": the readback without mirrors against the one with them",
                    DeviceCaps.forTier(2).surface,
                    withMirrors,
                    without);
            }
        }
    }

    /** One host with the mirrors on or off: K's image after the frame, or null when it got no readback. */
    private static byte[] bound(ScenarioRun run, QuadMode mode, boolean mirrors, byte[] first, byte[] last) {
        String where = mode + (mirrors ? " mirrors on" : " mirrors off");
        UploadsScenario.Probe probe = new UploadsScenario.Probe();
        final GlTestHost host = run.host(
            SyntheticGlyphs.TABLE,
            mode,
            GlTestHost.defaultOptions()
                .mirrors(mirrors),
            QueryPolicy.DRIVER,
            probe);
        TeeRenderHost tee = run.tee(host);
        DeviceRig rig = DeviceRig.on(DeviceCaps.forTier(2), tee, () -> host.pumpFrame());
        SurfaceDesc surface = rig.device.surface();
        int t = rig.create(SIDE, SIDE);
        for (int i = 0; i < UPDATES; i++) {
            rig.update(t, 0, 0, SIDE, SIDE, i < UPDATES - 1 ? first : last);
        }
        DeviceSnapshot s = rig.snapshot();
        run.check(
            s.tail.isEmpty() && s.vramUsed == SIDE * SIDE,
            where + ": the server tail holds " + s.tail.size() + " records, VRAM " + s.vramUsed);
        DeviceStats held = host.stats(rig.key);
        run.check(
            held.gpuTextures == 0 && held.uploads == 0,
            where + ": the host ran something before a pump: " + held);
        if (mirrors) {
            run.check(
                held.queuedRecords == 0 && held.queuedBytes == 0
                    && held.mirrorTextures == 1
                    && held.mirrorBytes == SIDE * SIDE
                    && held.pendingTextures == 1,
                where + ": the host holds more than one mirror and its pending create (S8 plan §3.10): " + held);
            run.check(
                held.mirrorAdoptions == UPDATES && held.mirrorAllocations == 0
                    && held.mirrorClones == 0
                    && held.mirrorCopyBytes == 0,
                where + ": copy-on-write adopted "
                    + held.mirrorAdoptions
                    + ", allocated "
                    + held.mirrorAllocations
                    + ", cloned "
                    + held.mirrorClones
                    + " (amendment A)");
        } else {
            run.check(
                held.queuedRecords == UPDATES + 1 && held.mirrorTextures == 0,
                where + ": option B queues every record: " + held);
        }
        rig.submit(segment(blit(t, 100, 200, 320, 200, 0, 0, 0)));
        long id = rig.present();
        // One pump: the device's GL objects, the texture's upload(s) and the frame
        host.pumpFrame();
        long[] uploads = probe.of(rig.key);
        long expected = mirrors ? 1 : UPDATES + 1;
        run.check(
            uploads[0] == expected && uploads[1] == expected * SIDE * SIDE,
            where + ": "
                + uploads[0]
                + " uploads of "
                + uploads[1]
                + " B after one pump, "
                + expected
                + " expected (H24)");
        run.check(host.stats(rig.key).framesExecuted == 1, where + ": the frame did not run in the pump");
        DeviceSteps.pumpUntilConfirmed(host, rig, id, where);
        DeviceSteps.settle(host, rig, where);
        run.check(probe.of(rig.key)[0] == expected, where + ": uploads after the readback: " + probe.of(rig.key)[0]);
        TierEOracle reference = new TierEOracle(surface, SyntheticGlyphs.TABLE);
        reference.createTexture(t, SIDE, SIDE, surface.format);
        reference.updateTexture(t, 0, 0, SIDE, SIDE, last);
        reference.runSegment(segment(blit(t, 100, 200, 320, 200, 0, 0, 0)));
        byte[] screen = rig.screen();
        boolean ok = run.compare(where + ": K's image", surface, reference.pixels(), screen);
        DeviceOrderingScenario.release(run, host, rig, where);
        run.close(host);
        return ok ? screen : null;
    }
}
