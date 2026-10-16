package io.github.mindbound.opengpu.gltestkit.scenario;

import java.util.concurrent.Callable;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.device.TeeRenderHost;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.QueryPolicy;
import io.github.mindbound.opengpu.gl.UploadProbe;
import io.github.mindbound.opengpu.gltestkit.host.GlForeignWork;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * The readback ring while another mod deletes textures (S8's fix loop after U6; 20:138, 24:197): four devices take a
 * new frame in every pass, and between passes four textures are deleted the way GLSM deletes them, their storage
 * re-specified first ({@link GlForeignWork}), so textures change storage between each readback's copy and its fetch.
 * Every readback is checked by a ShadowOracle through a tee, byte for byte (28:938; 23:137), under
 * {@code QueryPolicy.NEVER_READY} (each fetch forced) and {@code DRIVER}, on a 320×200 index8 surface and an 80×25
 * rgb565 one. Without {@code ReadbackRing.fetch}'s {@code glFinish}, Intel's Windows driver (32.0.101.8860) handed up
 * to half of a case's readbacks a PBO's earlier bytes, on the GLSM legs and on the compatibility leg alike (124 of 265
 * on the compatibility leg under DRIVER at 320×200). Each host's backend must have resolved its {@code ReadbackFinish}
 * as {@code -Popengpu.gl.readbackFinish} says for the leg's {@code GL_VENDOR}, logged once per host (Q-S8-3), so
 * {@code never} on a driver is its re-verification. Not one of plan §4.3's rows, so not in {@link Scenarios#ALL}:
 * GlForeignTexturesTest and GlsmForeignTexturesTest run it.
 */
public final class ForeignTexturesScenario extends Scenario {

    static final int DEVICES = 4;
    static final int PASSES = 100;
    /** Textures deleted after each pass. */
    static final int DELETES = 4;
    private static final SurfaceDesc[] SURFACES = { new SurfaceDesc(0, 320, 200, Formats.INDEX8),
        new SurfaceDesc(0, 80, 25, Formats.RGB565) };

    @Override
    protected void run(ScenarioRun run) {
        for (QueryPolicy policy : QueryPolicy.values()) {
            for (SurfaceDesc surface : SURFACES) {
                passes(run, policy, surface);
            }
        }
    }

    private static void passes(ScenarioRun run, QueryPolicy policy, SurfaceDesc surface) {
        String where = policy + " " + surface;
        GlTestHost host = run
            .host(SyntheticGlyphs.TABLE, QuadMode.INSTANCED, GlTestHost.defaultOptions(), policy, UploadProbe.NONE);
        TeeRenderHost tee = run.tee(host);
        int before = run.failures()
            .size();
        DeviceKey[] keys = new DeviceKey[DEVICES];
        Deliveries[] deliveries = new Deliveries[DEVICES];
        for (int d = 0; d < DEVICES; d++) {
            keys[d] = run.key();
            deliveries[d] = new Deliveries();
            tee.open(keys[d], surface, deliveries[d]);
        }
        host.pumpFrame();
        // Q-S8-3: the first pass resolved the glFinish from GL_VENDOR
        String finish = host.checkReadbackFinish(
            run.gl()
                .info().vendor);
        run.check(finish == null, where + ": " + finish);
        for (int pass = 1; pass <= PASSES; pass++) {
            for (int d = 0; d < DEVICES; d++) {
                tee.offer(keys[d], frame(surface, pass, d));
            }
            host.pumpFrame();
            run.onGl(new Callable<Integer>() {

                @Override
                public Integer call() {
                    return GlForeignWork.deleteTexturesAsGlsm(DELETES);
                }
            });
        }
        host.pumpUntilIdle(keys);
        int checked = 0;
        for (int d = 0; d < DEVICES; d++) {
            checked += deliveries[d].count();
            Deliveries.Delivery last = deliveries[d].last();
            run.check(
                last != null && last.epoch == 1 && last.frameId == PASSES,
                where + ": " + keys[d] + "'s last readback is " + last + ", expected e1 #" + PASSES);
            tee.release(keys[d]);
        }
        host.pumpFrame();
        run.close(host);
        // Not blind: about one readback in two passes per device under NEVER_READY, one a pass under DRIVER
        run.check(
            checked >= DEVICES * PASSES / 4,
            where + ": only " + checked + " readbacks checked, at least " + DEVICES * PASSES / 4 + " expected");
        run.log(
            where + ": "
                + checked
                + " readbacks checked, "
                + (run.failures()
                    .size() - before)
                + " failure(s)");
    }

    /** A clear to a colour of the pass and the device, so a readback of an earlier frame or another device differs. */
    private static FrameRecord frame(SurfaceDesc surface, int pass, int d) {
        int k = pass * 7 + d * 31;
        int colour = surface.format == Formats.INDEX8 ? k & 0xFF : 0xFF000000 | k * 0x050B11 & 0xFFFFFF;
        return new FrameRecord(
            1,
            pass,
            new byte[][] { TestEncoder.segment(TestEncoder.clear(colour)) },
            FrameTotals.EMPTY);
    }
}
