package io.github.mindbound.opengpu.gltestkit.scenario;

import java.util.ArrayList;
import java.util.List;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.cmd.HandleTypes;
import io.github.mindbound.opengpu.core.colour.Colours;
import io.github.mindbound.opengpu.core.colour.Palette;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.device.TeeRenderHost;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.ResetRecord;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;
import io.github.mindbound.opengpu.gl.GlLedger;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.host.DeviceBackend;
import io.github.mindbound.opengpu.gl.host.DeviceStats;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * Scenario 5 (S8 plan §4.3; 28:600, 28:150, 21:107): on {@link GlTestHost}, in both quad modes and both formats, a
 * {@code RESET} replaces the surface with its pixels, deletes every texture (ledger texture rows 0, DeviceStats' GPU
 * textures 0), and keeps the surface objects (the same surface texture name, every other ledger row unchanged); the
 * present texture shows the new pixels (H27), and a later frame of the new epoch draws on them, a texture created
 * after the reset included, checked by a ShadowOracle. Then a {@code FRAME} still queued when a newer {@code RESET} is
 * offered is dropped (H38): it never runs and the surface is the reset's.
 */
public final class ResetScenario extends Scenario {

    private static final int T1 = Handles.pack(HandleTypes.TEXTURE, 1, 1);
    private static final int T1_AGAIN = Handles.pack(HandleTypes.TEXTURE, 1, 2);

    @Override
    protected void run(ScenarioRun run) {
        for (QuadMode mode : QuadMode.values()) {
            GlTestHost host = run.host(mode);
            TeeRenderHost tee = run.tee(host);
            for (int format : new int[] { Formats.INDEX8, Formats.RGB565 }) {
                device(run, host, tee, new SurfaceDesc(0, 37, 23, format), mode + " " + Formats.name(format));
            }
            run.close(host);
        }
    }

    private static void device(ScenarioRun run, GlTestHost host, TeeRenderHost tee, SurfaceDesc surface, String where) {
        int format = surface.format;
        boolean index8 = format == Formats.INDEX8;
        DeviceKey key = run.key();
        Deliveries deliveries = new Deliveries();
        tee.open(key, surface, deliveries);
        tee.offer(key, new TexCreateRecord(1, T1, 6, 4, format));
        tee.offer(key, new TexUpdateRecord(1, T1, 0, 0, 6, 4, UploadsScenario.payload(6, 4, format, 1)));
        tee.offer(
            key,
            new FrameRecord(
                1,
                1,
                new byte[][] { TestEncoder.segment(
                    TestEncoder.clear(index8 ? 3 : 0xFF203040),
                    TestEncoder.blit(T1, 0, 0, 6, 4, 2, 3, 0),
                    TestEncoder.fill(10, 10, 9, 5, index8 ? 250 : 0xFFF0E0D0)) },
                FrameTotals.EMPTY));
        host.pumpUntilIdle(key);
        run.check(deliveries.count() == 1, where + ": frame 1's readback, got " + deliveries);
        GlLedger ledger = host.ledger();
        run.check(ledger.count(key.session, key.deviceId, GlLedger.TEXTURE) == 1, where + ": one texture row");
        List<String> objects = objectRows(ledger, key);
        int surfaceTexture = surfaceTexture(host, key);

        byte[] reset = pattern(surface, 5);
        tee.offer(key, new ResetRecord(2, reset));
        host.pumpUntilIdle(key);
        DeviceStats stats = host.stats(key);
        run.check(
            ledger.count(key.session, key.deviceId, GlLedger.TEXTURE) == 0 && stats.gpuTextures == 0,
            where + ": RESET deleted every texture: " + ledger.rows(key.session, key.deviceId));
        run.check(stats.resetsRun == 1, where + ": one RESET run, got " + stats);
        run.check(
            objects.equals(objectRows(ledger, key)),
            where + ": the surface objects' rows changed: " + objects + " -> " + objectRows(ledger, key));
        run.check(surfaceTexture(host, key) == surfaceTexture, where + ": RESET replaced the surface texture (21:107)");
        run.compare(where + " the RESET surface", surface, reset, host.surfacePixels(key));
        run.compareRgba(
            where + " the RESET present texture",
            surface,
            Colours.expandToRgba(surface, reset, Palette.DEFAULT),
            host.presentRgba(key));

        // Frame 2 of epoch 2 draws on the reset pixels, with a texture created after the reset in the freed slot
        tee.offer(key, new TexCreateRecord(2, T1_AGAIN, 4, 5, format));
        tee.offer(key, new TexUpdateRecord(2, T1_AGAIN, 1, 1, 3, 4, UploadsScenario.payload(3, 4, format, 2)));
        tee.offer(
            key,
            new FrameRecord(
                2,
                2,
                new byte[][] { TestEncoder.segment(
                    TestEncoder.fill(1, 1, 5, 3, index8 ? 99 : 0xFF314159),
                    TestEncoder.blit(T1_AGAIN, 0, 0, 4, 5, 20, 2, 1),
                    TestEncoder.text(3, 8, index8 ? 250 : 0xFFFEDCBA, index8 ? 1 : 0xFF000001, 1, 0, "Réset")) },
                FrameTotals.EMPTY));
        host.pumpUntilIdle(key);
        Deliveries.Delivery last = deliveries.last();
        run.check(
            deliveries.count() == 2 && last.epoch == 2 && last.frameId == 2,
            where + ": frame 2's readback, labelled e2 #2, got " + deliveries);

        // H38: a FRAME still queued when a newer RESET is offered is dropped with it
        long dropped = host.stats(key).recordsDropped;
        long frames = host.stats(key).framesExecuted;
        tee.offer(
            key,
            new FrameRecord(
                2,
                3,
                new byte[][] { TestEncoder.segment(TestEncoder.clear(index8 ? 1 : 0xFFFFFFFF)) },
                FrameTotals.EMPTY));
        byte[] again = pattern(surface, 11);
        tee.offer(key, new ResetRecord(3, again));
        host.pumpUntilIdle(key);
        stats = host.stats(key);
        run.check(
            stats.recordsDropped > dropped && stats.framesExecuted == frames,
            where + ": the queued FRAME was not dropped by the RESET (H38): " + stats);
        run.compare(where + " the second RESET surface", surface, again, host.surfacePixels(key));
        run.check(deliveries.count() == 2, where + ": a readback of a dropped frame: " + deliveries);
        tee.release(key);
    }

    /** The device's rows of every kind but textures: the surface objects RESET keeps (28:150). */
    private static List<String> objectRows(GlLedger ledger, DeviceKey key) {
        List<String> rows = new ArrayList<>();
        for (GlLedger.Row row : ledger.rows(key.session, key.deviceId)) {
            if (!GlLedger.TEXTURE.equals(row.key.kind)) {
                rows.add(row.toString());
            }
        }
        return rows;
    }

    private static int surfaceTexture(final GlTestHost host, final DeviceKey key) {
        return host.onGl(() -> {
            DeviceBackend device = host.core()
                .deviceBackend(key);
            return device == null ? 0
                : host.backend()
                    .surfaceTexture(device);
        });
    }

    /** A surface's bytes of a seeded pattern (28 §5.7 layout). */
    static byte[] pattern(SurfaceDesc surface, int seed) {
        byte[] out = new byte[surface.byteSize()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (i * 37 + seed * 11 + (i >>> 3));
        }
        return out;
    }
}
