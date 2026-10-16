package io.github.mindbound.opengpu.gltestkit;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.gl.GlBackend;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.QueryPolicy;
import io.github.mindbound.opengpu.gl.ReadbackFinish;
import io.github.mindbound.opengpu.gl.UploadProbe;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;
import io.github.mindbound.opengpu.gltestkit.host.RenderCoreTest;
import io.github.mindbound.opengpu.gltestkit.scenario.Deliveries;

/**
 * The owner's Q-S8-3 (2026-10-16) on a real context: each {@link ReadbackFinish} resolves from this driver's
 * {@code GL_VENDOR} at the first pass, and a pass that fetches a readback runs {@code glFinish} once (C100) exactly
 * when it resolved on: never under {@code NEVER}, and under {@code AUTO} as on the vendor, Intel's and Mesa's included,
 * but not NVIDIA's. The frames are plain clears, so no readback needs the wait; their pixels are not compared.
 */
@RenderCoreTest
class GlReadbackFinishTest {

    private static final int FRAMES = 6;

    @Test
    @Timeout(60)
    void aFetchingPassRunsGlFinishExactlyWhenThePolicyResolvedOn(GlThread gl) {
        String vendor = gl.info().vendor;
        List<String> summary = new ArrayList<>();
        for (ReadbackFinish policy : ReadbackFinish.values()) {
            GlTestHost host = new GlTestHost(
                gl,
                SyntheticGlyphs.TABLE,
                QuadMode.EXPANDED,
                GlTestHost.defaultOptions(),
                QueryPolicy.DRIVER,
                UploadProbe.NONE,
                policy);
            GlBackend backend = host.backend();
            try {
                SurfaceDesc surface = new SurfaceDesc(0, 32, 16, Formats.INDEX8);
                DeviceKey key = new DeviceKey(200 + policy.ordinal(), 1);
                Deliveries deliveries = new Deliveries();
                host.open(key, surface, deliveries);
                host.pumpFrame();
                for (int frame = 1; frame <= FRAMES; frame++) {
                    host.offer(
                        key,
                        new FrameRecord(
                            1,
                            frame,
                            new byte[][] { TestEncoder.segment(TestEncoder.clear(frame)) },
                            FrameTotals.EMPTY));
                    host.pumpUntilIdle(key);
                }
                boolean on = policy.finishes(vendor);
                summary.add(policy + " " + (on ? "on" : "off") + ": " + backend.readbackFinishes() + " glFinish");
                Assertions.assertEquals(on, backend.readbackFinish(), policy + " on GL_VENDOR " + vendor);
                Assertions.assertEquals(vendor, backend.vendor(), policy + ": the backend's GL_VENDOR");
                Assertions.assertTrue(deliveries.count() > 0, policy + ": no readback was fetched");
                if (on) {
                    Assertions.assertTrue(
                        backend.readbackFinishes() > 0 && backend.readbackFinishes() <= host.pumps(),
                        policy + ": " + backend.readbackFinishes() + " glFinish in " + host.pumps() + " passes");
                } else {
                    Assertions.assertEquals(0, backend.readbackFinishes(), policy + ": glFinish calls");
                }
                host.release(key);
                host.pumpFrame();
            } finally {
                host.close();
            }
        }
        System.out.println("[opengpu-gl] readback glFinish on GL_VENDOR " + vendor + ": " + summary);
        Assertions.assertTrue(ReadbackFinish.ALWAYS.finishes(vendor) && !ReadbackFinish.NEVER.finishes(vendor));
    }
}
