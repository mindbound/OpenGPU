package io.github.mindbound.opengpu.gltestkit.scenario;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.device.DeviceCaps;
import io.github.mindbound.opengpu.core.device.DeviceSnapshot;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.TeeRenderHost;
import io.github.mindbound.opengpu.gl.GlLedger;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * Scenario 15 (S8 plan §4.3, §3.11; 28:1083, 28:568, :602-604; 26:114-117): G10, {@code GlLedger} against
 * {@code :core}, through {@code Device} on {@link GlTestHost} with T2's and T3's caps, in both quad modes, every
 * readback checked by a ShadowOracle through a tee. After each step the host is pumped until it owes the key nothing
 * and the server tail is empty, and then the device's texture rows hold {@code vramUsed} plus the open frame's
 * deferred bytes, and as many rows as live textures plus deferred frees, which the host's own count repeats; the
 * device's other rows follow 20 §1's formula (512 000 B at T2, 3 072 000 B at T3, two FBOs, three queries). The steps:
 * textures of 1×1, 16×16 and, on T3, 1 024² (2 MiB); whole and partial updates; a free with no frame open; a free
 * during an open frame, whose row stays until the present appends its {@code TEX_FREE} (28:568's deferred bytes); a
 * texture created, updated and freed before the host ran any of it, which never reaches the GPU; a create while a
 * frame is in flight; and after the release and one pump, no row of the device (24:218). Kills plan §4.7's "free of a
 * created-and-pending texture issuing a GL delete" and "ledger row kept after delete".
 */
public final class G10Scenario extends Scenario {

    @Override
    protected void run(ScenarioRun run) {
        for (QuadMode mode : QuadMode.values()) {
            GlTestHost host = run.host(mode);
            TeeRenderHost tee = run.tee(host);
            for (int tier = 2; tier <= 3; tier++) {
                device(run, host, tee, tier, mode + " T" + tier);
            }
            run.close(host);
        }
    }

    private static void device(ScenarioRun run, final GlTestHost host, TeeRenderHost tee, int tier, String where) {
        DeviceRig rig = DeviceRig.on(DeviceCaps.forTier(tier), tee, () -> host.pumpFrame());
        SurfaceDesc surface = rig.device.surface();
        int bpp = surface.bytesPerPixel();
        step(run, host, rig, where + " opened");
        DeviceSteps.formula(run, host, rig.key, surface, 0, where + " opened");
        run.check(
            host.ledger()
                .deviceBytes(rig.key.session, rig.key.deviceId) == (tier == 2 ? 512_000L : 3_072_000L),
            where + ": 20 §1's formula gives "
                + host.ledger()
                    .deviceBytes(rig.key.session, rig.key.deviceId)
                + " B (S8 plan §3.11)");

        int t1 = rig.create(1, 1);
        int t16 = rig.create(16, 16);
        int big = tier == 3 ? rig.create(1024, 1024) : 0;
        long textures = (1L + 256 + (tier == 3 ? 1024L * 1024 : 0)) * bpp;
        step(run, host, rig, where + " created");
        DeviceSteps.formula(run, host, rig.key, surface, textures, where + " created");

        rig.update(t1, 0, 0, 1, 1, DeviceSteps.pattern(bpp, 1));
        rig.update(t16, 0, 0, 16, 16, DeviceSteps.pattern(256 * bpp, 2));
        rig.update(t16, 3, 4, 5, 6, DeviceSteps.pattern(30 * bpp, 3));
        if (big != 0) {
            rig.update(big, 0, 0, 1024, 1024, DeviceSteps.pattern(1024 * 1024 * bpp, 4));
            rig.update(big, 1000, 1000, 24, 24, DeviceSteps.pattern(24 * 24 * bpp, 5));
        }
        step(run, host, rig, where + " updated");

        // A free with no frame open: TEX_FREE at once, the row goes at the next pump (28:604)
        rig.free(t16);
        step(run, host, rig, where + " t16 freed");

        // A free during an open frame: deferred, so the host still holds it (28:568)
        rig.submit(segment(blit(t1, 0, 0, 1, 1, 0, 0, 0)));
        rig.free(t1);
        if (big != 0) {
            rig.submit(segment(blit(big, 990, 990, 34, 34, 5, 5, 0)));
            rig.free(big);
        }
        DeviceSnapshot open = step(run, host, rig, where + " freed during an open frame");
        run.check(
            open.deferredCount == (big != 0 ? 2 : 1) && open.deferredBytes == bpp + (big != 0 ? 2L * 1024 * 1024 : 0),
            where + ": the open frame's deferred frees: " + open.deferredCount + " of " + open.deferredBytes + " B");
        long id = rig.present();
        DeviceSteps.pumpUntilConfirmed(host, rig, id, where + " the open frame presented");
        step(run, host, rig, where + " the deferred frees appended");

        // Created, updated and freed before the host ran any of it: it never reaches the GPU (S8 plan §3.10)
        int c = rig.create(4, 4);
        rig.update(c, 0, 0, 4, 4, DeviceSteps.pattern(16 * bpp, 6));
        rig.free(c);
        step(run, host, rig, where + " created and freed before a pump");

        // A create while a frame is in flight joins the server tail behind it
        rig.submit(segment(clear(tier == 2 ? 3 : 0xFF123456)));
        long inFlight = rig.present();
        int t8 = rig.create(8, 8);
        rig.update(t8, 1, 1, 6, 6, DeviceSteps.pattern(36 * bpp, 7));
        run.check(
            rig.snapshot().tail.size() == 3,
            where + ": the server tail holds "
                + rig.snapshot().tail.size()
                + " records, FRAME, TEX_CREATE, TEX_UPDATE");
        DeviceSteps.pumpUntilConfirmed(host, rig, inFlight, where + " a create while a frame is in flight");
        step(run, host, rig, where + " created while a frame was in flight");
        rig.submit(segment(blit(t8, 0, 0, 8, 8, 2, 2, 0)));
        DeviceSteps.pumpUntilConfirmed(host, rig, rig.present(), where + " t8 drawn");
        DeviceSnapshot last = step(run, host, rig, where + " t8 drawn");
        DeviceSteps.formula(run, host, rig.key, surface, last.vramUsed, where + " t8 drawn");

        rig.device.release();
        host.pumpFrame();
        run.check(
            host.ledger()
                .isEmpty(rig.key.session)
                && host.ledger()
                    .bytes(rig.key.session, rig.key.deviceId, GlLedger.TEXTURE) == 0,
            where + ": rows after the release and a pump: "
                + host.ledger()
                    .rows(rig.key.session, rig.key.deviceId));
    }

    /** Pumped until the host is idle and the tail empty, then G10's equation. The snapshot. */
    private static DeviceSnapshot step(ScenarioRun run, GlTestHost host, DeviceRig rig, String where) {
        DeviceSnapshot s = DeviceSteps.settle(host, rig, where);
        DeviceSteps.ledgerEquation(run, host, rig.key, s, where);
        return s;
    }
}
