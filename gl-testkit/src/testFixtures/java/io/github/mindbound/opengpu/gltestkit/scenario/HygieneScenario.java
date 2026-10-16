package io.github.mindbound.opengpu.gltestkit.scenario;

import java.util.List;
import java.util.concurrent.Callable;

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
import io.github.mindbound.opengpu.core.render.TexFreeRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gltestkit.host.GlHygiene;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * Scenario 8 (S8 plan §4.3; 23:59, RM:67, S8 amendments E), the worst case of the hygiene every scenario checks. From
 * the MC-like start (its non-default scissor box included, which only R-1's explicit restore keeps on 2.2.8), a
 * brand-new backend's first pass makes its shared objects and opens two devices, index8 and rgb565 (B1, B2), and the
 * passes that follow do every op, a create, whole and partial uploads, a delete, a {@code RESET} and readbacks, in both
 * quad modes. After every pump {@code diff()} must be empty (the run's hosts check it), every readback must equal the
 * ShadowOracle's, and the surface and the present texture of both formats must equal the reference. Then from the
 * "GLSM defaults" start (program 0, GLSM's default VAO, nothing else changed; the context's defaults on the
 * compatibility leg) one device's passes, after which a {@code glClear} through the leg's GL into a framebuffer of its
 * own and one fixed-function quad through the client-array path must draw exactly (GlHygiene.clearProbe). Kills plan
 * §4.7's rows for {@code PACK_*}, the scissor box, polygon mode, clip distances, unit 1 and the samplers.
 */
public final class HygieneScenario extends Scenario {

    private static final int T1 = Handles.pack(HandleTypes.TEXTURE, 1, 1);
    private static final int T2 = Handles.pack(HandleTypes.TEXTURE, 2, 1);

    @Override
    protected boolean hostileStart() {
        return false;
    }

    @Override
    protected void run(ScenarioRun run) {
        for (QuadMode mode : QuadMode.values()) {
            run.start(true);
            GlTestHost host = run.host(mode);
            TeeRenderHost tee = run.tee(host);
            DeviceKey a = run.key();
            DeviceKey b = run.key();
            SurfaceDesc sa = new SurfaceDesc(0, 40, 20, Formats.INDEX8);
            SurfaceDesc sb = new SurfaceDesc(0, 30, 18, Formats.RGB565);
            Deliveries da = new Deliveries();
            Deliveries db = new Deliveries();
            // Both open before the first pass: the backend's creation and both opens run inside the hostile start
            tee.open(a, sa, da);
            tee.open(b, sb, db);
            everyOp(tee, a, sa, 1);
            everyOp(tee, b, sb, 1);
            host.pumpUntilIdle(a, b);
            String where = mode + " from the MC-like start";
            check(run, host, tee, a, sa, where);
            check(run, host, tee, b, sb, where);
            afterReset(tee, a, sa);
            afterReset(tee, b, sb);
            host.pumpUntilIdle(a, b);
            check(run, host, tee, a, sa, where + ", after the RESET");
            check(run, host, tee, b, sb, where + ", after the RESET");
            run.check(da.count() >= 2 && db.count() >= 2, where + ": readbacks " + da + " and " + db);
            run.check(
                host.backend()
                    .passState()
                    .rasterizerDiscardSeen() > 0,
                where + ": no pass saw the start's GL_RASTERIZER_DISCARD (H70)");
            tee.release(a);
            tee.release(b);
            run.close(host);
            run.undoStart();

            run.start(false);
            host = run.host(mode);
            tee = run.tee(host);
            DeviceKey c = run.key();
            tee.open(c, sa, new Deliveries());
            everyOp(tee, c, sa, 1);
            host.pumpUntilIdle(c);
            String defaults = mode + (run.glsm() ? " from GLSM's defaults" : " from the context's defaults");
            check(run, host, tee, c, sa, defaults);
            tee.release(c);
            run.close(host);
            List<String> probe = run.onGl(new Callable<List<String>>() {

                @Override
                public List<String> call() {
                    return GlHygiene.clearProbe(true);
                }
            });
            for (String finding : probe) {
                run.fail(defaults + ", the clear probe: " + finding);
            }
            run.log(
                mode + ": the clear probe's glClear and client-array quad (glEnableClientState, glVertexPointer of a"
                    + " FloatBuffer, glDrawArrays) "
                    + (probe.isEmpty() ? "drew exactly" : "failed"));
            run.undoStart();
        }
    }

    /** A create, whole and partial uploads, and a frame of every op (both TEXT forms, KEY0, clipped blits). */
    private static void everyOp(TeeRenderHost tee, DeviceKey key, SurfaceDesc s, long epoch) {
        int format = s.format;
        boolean index8 = format == Formats.INDEX8;
        tee.offer(key, new TexCreateRecord(epoch, T1, 7, 5, format));
        tee.offer(key, new TexUpdateRecord(epoch, T1, 0, 0, 7, 5, UploadsScenario.payload(7, 5, format, 21)));
        tee.offer(key, new TexUpdateRecord(epoch, T1, 2, 1, 3, 3, UploadsScenario.payload(3, 3, format, 22)));
        tee.offer(key, new TexCreateRecord(epoch, T2, 3, 3, format));
        tee.offer(
            key,
            new FrameRecord(
                epoch,
                1,
                new byte[][] {
                    TestEncoder.segment(
                        TestEncoder.clear(index8 ? 5 : 0xFF0A0B0C),
                        TestEncoder.fill(2, 1, 9, 6, index8 ? 200 : 0xFFFF0000),
                        TestEncoder.fill(-3, -2, 6, 5, index8 ? 255 : 0xFF00FF00),
                        TestEncoder.blit(T1, 0, 0, 7, 5, 4, 3, 0),
                        TestEncoder.blit(T1, 0, 0, 7, 5, s.width - 4, s.height - 3, 1),
                        TestEncoder.blit(T2, -1, -1, 5, 5, 12, 2, 0)),
                    TestEncoder.segment(
                        TestEncoder.text(-2, 2, index8 ? 15 : 0xFFFFFF00, index8 ? 0 : 0xFF000000, 0, 0, "Abé~x"),
                        TestEncoder
                            .text(5, s.height - 8, index8 ? 196 : 0xFF00FFFF, index8 ? 3 : 0xFF203040, 1, 0, "Hi!"),
                        TestEncoder
                            .text(s.width - 4, -9, index8 ? 77 : 0xFF808000, index8 ? 66 : 0xFF000080, 0, 0, "QZ")) },
                FrameTotals.EMPTY));
        tee.offer(key, new TexFreeRecord(epoch, T2));
    }

    /** A RESET of epoch 2, then a frame on it with a texture created after it. */
    private static void afterReset(TeeRenderHost tee, DeviceKey key, SurfaceDesc s) {
        boolean index8 = s.format == Formats.INDEX8;
        tee.offer(key, new ResetRecord(2, ResetScenario.pattern(s, 7)));
        int t3 = Handles.pack(HandleTypes.TEXTURE, 1, 2);
        tee.offer(key, new TexCreateRecord(2, t3, 2, 2, s.format));
        tee.offer(key, new TexUpdateRecord(2, t3, 0, 0, 2, 2, UploadsScenario.payload(2, 2, s.format, 23)));
        tee.offer(
            key,
            new FrameRecord(
                2,
                2,
                new byte[][] { TestEncoder.segment(
                    TestEncoder.blit(t3, 0, 0, 2, 2, 1, 1, 0),
                    TestEncoder.text(0, 4, index8 ? 1 : 0xFFFFFFFF, index8 ? 2 : 0xFF000000, 0, 0, "ok")) },
                FrameTotals.EMPTY));
    }

    /** The surface and the present texture against the ShadowOracle's surface now. */
    private static void check(ScenarioRun run, GlTestHost host, TeeRenderHost tee, DeviceKey key, SurfaceDesc s,
        String where) {
        byte[] expected = tee.shadow()
            .pixelsNow(key);
        String what = where + " " + Formats.name(s.format);
        run.compare(what + ", the surface", s, expected, host.surfacePixels(key));
        run.compareRgba(
            what + ", the present texture",
            s,
            Colours.expandToRgba(s, expected, Palette.DEFAULT),
            host.presentRgba(key));
    }
}
