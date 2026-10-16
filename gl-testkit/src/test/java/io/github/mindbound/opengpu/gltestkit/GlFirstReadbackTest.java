package io.github.mindbound.opengpu.gltestkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.device.ShadowOracle;
import io.github.mindbound.opengpu.core.fixtures.device.TeeRenderHost;
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
 * A fresh device's first readback through the PBO ring is its frame (S8 plan §3.9; 24:197; 20:138), byte for byte
 * against a ShadowOracle (28:938; 23:137). Sixteen devices open in one pass, each takes one frame of its own colour and
 * is pumped until idle, eight rounds per query policy and surface. Without {@code ReadbackRing.create}'s read of each
 * new PBO, Intel's Windows driver (32.0.101.8860) handed a few percent of these readbacks the PBO memory's earlier
 * bytes, zeros or another device's surface, on every policy and format (S8's fix loop after U5). Each host's backend
 * must also have resolved its readbacks' {@code glFinish} as {@code -Popengpu.gl.readbackFinish} says for the leg's
 * {@code GL_VENDOR}, logged once per host (Q-S8-3; {@code never} is a driver's re-verification run). Every case also
 * runs under {@link ReadbackFinish#NEVER}, which must run no {@code glFinish}: the wait before a pass's first PBO read
 * (the fix loop after U6; {@code AUTO} on Intel) hides most stale first readbacks too, leaving 0-2 of 1 024 without the
 * create-time read, so only {@code NEVER} shows the read is what holds, as it is under {@code AUTO} on NVIDIA.
 * Compatibility leg only; the scenarios cover the GLSM legs.
 */
@RenderCoreTest
class GlFirstReadbackTest {

    private static final int PER_PASS = 16;
    private static final int ROUNDS = 8;
    private static final SurfaceDesc[] SURFACES = { new SurfaceDesc(0, 37, 23, Formats.INDEX8),
        new SurfaceDesc(0, 160, 50, Formats.INDEX8), new SurfaceDesc(0, 320, 100, Formats.INDEX8),
        new SurfaceDesc(0, 80, 25, Formats.RGB565) };

    @Test
    @Timeout(120)
    void everyFreshDevicesFirstReadbackIsItsFrame(GlThread gl) {
        List<String> failures = Collections.synchronizedList(new ArrayList<String>());
        List<String> summary = new ArrayList<>();
        long session = 100;
        // The property's policy, then NEVER, under which ReadbackRing.create's read is the only guard
        ReadbackFinish property = GlTestHost.readbackFinish();
        ReadbackFinish[] finishes = property == ReadbackFinish.NEVER ? new ReadbackFinish[] { ReadbackFinish.NEVER }
            : new ReadbackFinish[] { property, ReadbackFinish.NEVER };
        for (ReadbackFinish finish : finishes) {
            for (QueryPolicy policy : QueryPolicy.values()) {
                for (SurfaceDesc surface : SURFACES) {
                    GlTestHost host = new GlTestHost(
                        gl,
                        SyntheticGlyphs.TABLE,
                        QuadMode.EXPANDED,
                        GlTestHost.defaultOptions(),
                        policy,
                        UploadProbe.NONE,
                        finish);
                    List<String> found = Collections.synchronizedList(new ArrayList<String>());
                    TeeRenderHost tee = new TeeRenderHost(host, new ShadowOracle(SyntheticGlyphs.TABLE), found);
                    GlBackend backend = host.backend();
                    try {
                        for (int round = 0; round < ROUNDS; round++) {
                            session++;
                            DeviceKey[] keys = new DeviceKey[PER_PASS];
                            Deliveries[] deliveries = new Deliveries[PER_PASS];
                            for (int k = 0; k < PER_PASS; k++) {
                                keys[k] = new DeviceKey(session, k + 1);
                                deliveries[k] = new Deliveries();
                                tee.open(keys[k], surface, deliveries[k]);
                            }
                            // The openings' pass: every ring's PBOs are made here
                            host.pumpFrame();
                            if (round == 0 && finish == property) {
                                // Q-S8-3: the glFinish as -Popengpu.gl.readbackFinish says for this GL_VENDOR
                                String resolved = host.checkReadbackFinish(gl.info().vendor);
                                if (resolved != null) {
                                    found.add(resolved);
                                }
                            }
                            for (int k = 0; k < PER_PASS; k++) {
                                tee.offer(keys[k], frame(surface, k + 1));
                            }
                            host.pumpUntilIdle(keys);
                            for (int k = 0; k < PER_PASS; k++) {
                                Deliveries.Delivery last = deliveries[k].last();
                                if (deliveries[k].count() != 1 || last.epoch != 1 || last.frameId != 1) {
                                    found.add(keys[k] + ": " + deliveries[k].all() + " delivered, expected e1 #1 once");
                                }
                            }
                            tee.releaseSession(session);
                            host.pumpFrame();
                        }
                        if (finish == ReadbackFinish.NEVER
                            && (backend.readbackFinish() || backend.readbackFinishes() != 0)) {
                            found.add(
                                "NEVER ran " + backend.readbackFinishes()
                                    + " glFinish, so the create-time read was not alone");
                        }
                    } finally {
                        host.close();
                    }
                    summary
                        .add(finish + " " + policy + " " + surface + ": " + found.size() + " of " + ROUNDS * PER_PASS);
                    failures.addAll(found);
                }
            }
        }
        Assertions.assertEquals(
            Collections.emptyList(),
            failures,
            failures.size() + " first readback(s) differ from their frame; per case " + summary);
    }

    /** A clear to a colour of the device's own, so a readback of another device's surface shows too. */
    private static FrameRecord frame(SurfaceDesc surface, int k) {
        int colour = surface.format == Formats.INDEX8 ? k : 0xFF000000 | k * 0x0F0F0F;
        return new FrameRecord(
            1,
            1,
            new byte[][] { TestEncoder.segment(TestEncoder.clear(colour)) },
            FrameTotals.EMPTY);
    }
}
