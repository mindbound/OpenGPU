package io.github.mindbound.opengpu.gltestkit.scenario;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.HandleTypes;
import io.github.mindbound.opengpu.core.colour.Colours;
import io.github.mindbound.opengpu.core.colour.Palette;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.device.TeeRenderHost;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;
import io.github.mindbound.opengpu.gl.GlBackend;
import io.github.mindbound.opengpu.gl.GlLedger;
import io.github.mindbound.opengpu.gl.GpuTimer;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.QueryPolicy;
import io.github.mindbound.opengpu.gl.UploadProbe;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * Scenario 10 (S8 plan §4.3; 24:218, 21:106, 21:111, H46; plan §3.11), on {@link GlTestHost} with
 * {@code QueryPolicy.NEVER_READY}, so a readback stays in flight as long as the scenario needs, in both quad modes.
 * The ledger's rows after the first pass: the host's (six programs, two VAOs, the 64 KiB stream VBO, the atlas, the
 * palette) and each device's by 20 §1's formula (surface, present, three PBOs, two FBOs, three queries) plus its
 * textures; a {@code GpuTimer}'s two query rows come and go. {@code release} with a readback in flight: the device's
 * rows are gone after one pump and the readback never reaches its sink. Reopened with a higher epoch: a cleared surface
 * and present texture. Released and reopened before a pass: only the new opening's rows. {@code releaseSession}: no
 * row of the session after one pump, its display unbound, the other session untouched. {@code close}: the ledger
 * empty (the run's close checks it). Kills plan §4.7's "ledger row kept after delete, or removed twice" with G10.
 */
public final class HostLifecycleScenario extends Scenario {

    private static final int T1 = Handles.pack(HandleTypes.TEXTURE, 1, 1);
    private static final String DISPLAY = "lifecycle-display";

    @Override
    protected void run(ScenarioRun run) {
        for (QuadMode mode : QuadMode.values()) {
            lifecycle(run, mode);
        }
    }

    private static void lifecycle(ScenarioRun run, QuadMode mode) {
        GlTestHost host = run
            .host(SyntheticGlyphs.TABLE, mode, GlTestHost.defaultOptions(), QueryPolicy.NEVER_READY, UploadProbe.NONE);
        TeeRenderHost tee = run.tee(host);
        GlLedger ledger = host.ledger();
        long s1 = 21 + 10 * mode.ordinal();
        long s2 = s1 + 1;
        DeviceKey a = run.key(s1);
        DeviceKey b = run.key(s1);
        DeviceKey c = run.key(s2);
        SurfaceDesc sa = new SurfaceDesc(0, 48, 30, Formats.INDEX8);
        SurfaceDesc sb = new SurfaceDesc(0, 32, 20, Formats.RGB565);
        SurfaceDesc sc = new SurfaceDesc(0, 16, 16, Formats.INDEX8);
        Deliveries da = new Deliveries();
        tee.open(a, sa, da);
        tee.open(b, sb, new Deliveries());
        tee.open(c, sc, new Deliveries());
        tee.offer(a, new TexCreateRecord(1, T1, 9, 7, Formats.INDEX8));
        tee.offer(a, new TexUpdateRecord(1, T1, 0, 0, 9, 7, UploadsScenario.payload(9, 7, Formats.INDEX8, 4)));
        tee.offer(a, ReadbackScenario.frame(sa, 1));
        tee.offer(b, ReadbackScenario.frame(sb, 1));
        tee.offer(c, ReadbackScenario.frame(sc, 1));
        host.pumpUntilIdle(a, b, c);
        String where = mode + "";
        hostRows(run, host, where);
        deviceRows(run, ledger, a, sa, 9 * 7, where + " " + a);
        deviceRows(run, ledger, b, sb, 0, where + " " + b);
        timer(run, host, a, where);

        // release with a readback in flight: its rows go at the next pump, the readback never arrives (21:106)
        tee.offer(a, ReadbackScenario.frame(sa, 2));
        host.pumpFrame();
        run.check(host.stats(a).readbacksInFlight == 1, where + ": a readback in flight before the release");
        int delivered = da.count();
        tee.release(a);
        host.pumpFrame();
        run.check(
            ledger.rows(a.session, a.deviceId)
                .isEmpty(),
            where + ": rows after release and one pump: " + ledger.rows(a.session, a.deviceId));
        run.check(host.stats(a).released, where + ": the opening is not marked released");
        for (int i = 0; i < 2 * host.core()
            .options()
            .forceFetchAfterPasses(); i++) {
            host.pumpFrame();
        }
        run.check(da.count() == delivered, where + ": a released device's readback was delivered: " + da);

        // Reopened with a higher epoch: a cleared surface (28:199)
        tee.open(a, sa, new Deliveries());
        byte[] zeros = new byte[sa.byteSize()];
        run.compare(where + " reopened: the surface", sa, zeros, host.surfacePixels(a));
        run.compareRgba(
            where + " reopened: the present texture",
            sa,
            Colours.expandToRgba(sa, zeros, Palette.DEFAULT),
            host.presentRgba(a));
        deviceRows(run, ledger, a, sa, 0, where + " reopened");
        // Released and reopened before a pass (H46): the old opening's objects go first
        tee.offer(a, ReadbackScenario.frame(sa, 2, 1));
        tee.release(a);
        tee.open(a, sa, new Deliveries());
        host.pumpUntilIdle(a);
        deviceRows(run, ledger, a, sa, 0, where + " released and reopened before a pass");
        run.compare(where + " released and reopened: the surface", sa, zeros, host.surfacePixels(a));

        // releaseSession (21:111)
        host.bindDisplay(DISPLAY, b);
        tee.releaseSession(s1);
        host.pumpFrame();
        run.check(ledger.isEmpty(s1), where + ": rows of the released session: " + ledger.rows());
        run.check(
            host.core()
                .boundDevice(DISPLAY) == null,
            where + ": the session's display is still bound");
        deviceRows(run, ledger, c, sc, 0, where + " the other session");
        run.check(!ledger.isEmpty(), where + ": the host rows are gone before close");
        tee.release(c);
        run.close(host);
    }

    /** S8 plan §3.11's host rows. */
    private static void hostRows(ScenarioRun run, GlTestHost host, String where) {
        GlLedger ledger = host.ledger();
        GlBackend backend = host.backend();
        long s = GlLedger.HOST_SESSION;
        int d = GlLedger.HOST_DEVICE;
        run.check(ledger.count(s, d, GlLedger.PROGRAM) == 6, where + ": six program rows: " + ledger.rows(s, d));
        run.check(ledger.count(s, d, GlLedger.VAO) == 2, where + ": two VAO rows");
        run.check(ledger.bytes(s, d, GlLedger.VBO) == 64 * 1024, where + ": the stream VBO's 64 KiB (H69)");
        run.check(
            ledger.bytes(s, d, GlLedger.GLYPH_ATLAS) == (long) backend.atlas()
                .width() * backend.atlas()
                    .height(),
            where + ": the atlas row");
        run.check(ledger.bytes(s, d, GlLedger.PALETTE) == 1024, where + ": the palette row");
        run.check(backend.maxSurfaceSide() >= 1024, where + ": GL_MAX_TEXTURE_SIZE " + backend.maxSurfaceSide());
    }

    /** 20 §1's formula for a device (H35): surface W·H·bpp, present 4·W·H, three PBOs of W·H·bpp; plus textures. */
    static void deviceRows(ScenarioRun run, GlLedger ledger, DeviceKey key, SurfaceDesc surface, long textures,
        String where) {
        long s = key.session;
        int d = key.deviceId;
        long bytes = surface.byteSize();
        run.check(
            ledger.bytes(s, d, GlLedger.SURFACE) == bytes && ledger.count(s, d, GlLedger.SURFACE) == 1,
            where + ": the surface row");
        run.check(
            ledger.bytes(s, d, GlLedger.PRESENT) == 4L * surface.pixelCount()
                && ledger.count(s, d, GlLedger.PRESENT) == 1,
            where + ": the present row");
        run.check(ledger.count(s, d, GlLedger.FBO) == 2, where + ": two FBO rows");
        run.check(
            ledger.count(s, d, GlLedger.PBO) == 3 && ledger.bytes(s, d, GlLedger.PBO) == 3 * bytes,
            where + ": three PBOs of W·H·bpp");
        run.check(ledger.count(s, d, GlLedger.QUERY) == 3, where + ": three query rows");
        run.check(ledger.bytes(s, d, GlLedger.TEXTURE) == textures, where + ": the texture rows");
        run.check(
            ledger.deviceBytes(s, d) == 4 * bytes + 4L * surface.pixelCount() + textures,
            where + ": 20 §1's formula plus textures: " + ledger.rows(s, d));
    }

    /** A GpuTimer pair in a bracket of the host's backend: two query rows while it lives, a GL_TIMESTAMP span. */
    private static void timer(ScenarioRun run, final GlTestHost host, final DeviceKey key, String where) {
        final GlLedger ledger = host.ledger();
        long[] result = host.onGl(() -> {
            GlBackend backend = host.backend();
            backend.beginPass();
            try {
                GpuTimer timer = new GpuTimer(ledger, key.session, key.deviceId, "lifecycle");
                timer.begin();
                timer.end();
                int during = ledger.count(key.session, key.deviceId, GlLedger.QUERY);
                long nanos = timer.elapsedNanos();
                long available = timer.available() ? 1 : 0;
                timer.delete();
                return new long[] { during, nanos, available, ledger.count(key.session, key.deviceId, GlLedger.QUERY) };
            } finally {
                backend.endPass();
            }
        });
        run.check(result[0] == 5 && result[3] == 3, where + ": the timer's query rows came and went: " + result[0]);
        run.check(result[1] >= 0 && result[2] == 1, where + ": a GL_TIMESTAMP pair in order: " + result[1] + " ns");
    }
}
