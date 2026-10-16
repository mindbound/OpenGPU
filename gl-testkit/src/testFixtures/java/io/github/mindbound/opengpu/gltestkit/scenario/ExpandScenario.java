package io.github.mindbound.opengpu.gltestkit.scenario;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.colour.Colours;
import io.github.mindbound.opengpu.core.colour.Palette;
import io.github.mindbound.opengpu.core.fixtures.golden.GoldenCases;
import io.github.mindbound.opengpu.core.fixtures.golden.GoldenRunner;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.ResetRecord;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.host.DeviceStats;
import io.github.mindbound.opengpu.gl.host.HostOptions;
import io.github.mindbound.opengpu.gltestkit.host.GlRecordRenderer;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * Scenario 7 (S8 plan §4.3; H27, H42; 28:484-488): the present texture, read back as RGBA8, against
 * {@code Colours.expandToRgba} with the default palette (alpha 255, 24:201) for all 256 indices (a 16×16 index8
 * surface set by {@code RESET}), all 65 536 rgb565 values (a 256×256 surface) and each golden's last frame (on a
 * {@link GlRecordRenderer}), in both quad modes. Exactness needs GL's float-to-unorm conversion to give k for k/255
 * (H42). Then H68 under {@code Expand.BOUND_ONLY}: an unbound device's present texture stays the black of its open
 * until a display is bound, and a pass then expands it. Kills plan §4.7's "GL's 5_6_5 unorm expand" row.
 */
public final class ExpandScenario extends Scenario {

    private static final String DISPLAY = "expand-display";

    @Override
    protected void run(ScenarioRun run) {
        for (QuadMode mode : QuadMode.values()) {
            GlTestHost host = run.host(mode);
            byte[] indices = new byte[256];
            for (int i = 0; i < 256; i++) {
                indices[i] = (byte) i;
            }
            reset(run, host, new SurfaceDesc(0, 16, 16, Formats.INDEX8), indices, mode + " all 256 indices");
            byte[] values = new byte[2 * 65536];
            for (int v = 0; v < 65536; v++) {
                values[2 * v] = (byte) v;
                values[2 * v + 1] = (byte) (v >>> 8);
            }
            reset(run, host, new SurfaceDesc(0, 256, 256, Formats.RGB565), values, mode + " all 65 536 values");
            run.close(host);
            goldens(run, mode);
            boundOnly(run, mode);
        }
    }

    /** A RESET with the pixels, then the present texture (expanded at the RESET's turn) and the surface. */
    private static void reset(ScenarioRun run, GlTestHost host, SurfaceDesc surface, byte[] pixels, String where) {
        DeviceKey key = run.key();
        host.open(key, surface, new Deliveries());
        host.offer(key, new ResetRecord(1, pixels));
        run.compareRgba(where, surface, Colours.expandToRgba(surface, pixels, Palette.DEFAULT), host.presentRgba(key));
        run.compare(where + ", the surface", surface, pixels, host.surfacePixels(key));
        host.release(key);
    }

    /** Each golden's last frame: the present texture against the expanded oracle surface. */
    private static void goldens(ScenarioRun run, QuadMode mode) {
        GlRecordRenderer renderer = run.renderer(mode);
        int checked = 0;
        for (GoldenCases.Case c : GoldenCases.load()
            .cases()) {
            SurfaceDesc surface;
            try {
                surface = c.surface();
            } catch (IllegalArgumentException e) {
                continue; // GoldensScenario reports it
            }
            GlGoldenTarget target = GlGoldenTarget.open(renderer, run.key(), surface);
            GoldenRunner.Result result = GoldenRunner.run(c, target);
            if (result.passed()) {
                run.compareRgba(
                    mode + " golden " + c.name() + "'s present texture",
                    surface,
                    Colours.expandToRgba(
                        surface,
                        target.oracle()
                            .pixels(),
                        Palette.DEFAULT),
                    renderer.presentRgba(target.key()));
                checked++;
            } else {
                run.fail(mode + " golden " + c.name() + " failed before its present texture: " + result.failures());
            }
            target.release();
        }
        run.check(checked == GoldensScenario.GOLDENS, mode + ": " + checked + " goldens' present textures checked");
        run.close(renderer);
    }

    /** H68: under BOUND_ONLY the expand waits for a bound display, and nothing is lost meanwhile. */
    private static void boundOnly(ScenarioRun run, QuadMode mode) {
        GlTestHost host = run.host(
            mode,
            GlTestHost.defaultOptions()
                .expand(HostOptions.Expand.BOUND_ONLY));
        SurfaceDesc surface = new SurfaceDesc(0, 19, 11, Formats.RGB565);
        DeviceKey key = run.key();
        host.open(key, surface, new Deliveries());
        byte[] pixels = ResetScenario.pattern(surface, 3);
        host.offer(key, new ResetRecord(1, pixels));
        String where = mode + " BOUND_ONLY";
        byte[] black = Colours.expandToRgba(surface, new byte[surface.byteSize()], Palette.DEFAULT);
        run.compareRgba(where + ", unbound: the present texture of the open", surface, black, host.presentRgba(key));
        DeviceStats stats = host.stats(key);
        run.check(stats.expandOwed && stats.expands == 0, where + ": the RESET's expand owed and not run: " + stats);
        host.bindDisplay(DISPLAY, key);
        run.check(!host.idle(key), where + ": a bound display with an expand owed is work");
        run.compareRgba(
            where + ", bound",
            surface,
            Colours.expandToRgba(surface, pixels, Palette.DEFAULT),
            host.presentRgba(key));
        host.bindDisplay(DISPLAY, null);
        run.check(
            host.core()
                .boundDevice(DISPLAY) == null,
            where + ": the display stays bound after unbinding");
        run.close(host);
    }
}
