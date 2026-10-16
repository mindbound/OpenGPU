package io.github.mindbound.opengpu.gltestkit.scenario;

import java.util.Arrays;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.fixtures.device.TeeRenderHost;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.host.PassBudget;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * Scenario 11 (S8 plan §4.3; 24:173, S8 plan §3.2): three devices with five frames queued each on {@link GlTestHost},
 * in both quad modes. Under {@code PassBudget.units(1)} each pump runs exactly one frame and the devices take turns
 * in a fixed rotation; under {@code PassBudget.millis(4)} each pump runs at least one unit, so every device
 * progresses however slow a unit is. Every readback is checked by a ShadowOracle, and each device's last readback is
 * its newest frame.
 */
public final class RoundRobinScenario extends Scenario {

    private static final int FRAMES = 5;

    @Override
    protected void run(ScenarioRun run) {
        for (QuadMode mode : QuadMode.values()) {
            GlTestHost host = run.host(mode);
            TeeRenderHost tee = run.tee(host);
            DeviceKey[] keys = { run.key(), run.key(), run.key() };
            SurfaceDesc[] surfaces = { new SurfaceDesc(0, 20, 10, Formats.INDEX8),
                new SurfaceDesc(0, 15, 9, Formats.RGB565), new SurfaceDesc(0, 11, 7, Formats.INDEX8) };
            Deliveries[] deliveries = new Deliveries[keys.length];
            for (int k = 0; k < keys.length; k++) {
                deliveries[k] = new Deliveries();
                tee.open(keys[k], surfaces[k], deliveries[k]);
                for (int f = 1; f <= FRAMES; f++) {
                    tee.offer(keys[k], ReadbackScenario.frame(surfaces[k], f));
                }
            }
            String where = mode + " units(1)";
            int[] advanced = new int[keys.length * FRAMES];
            long[] before = executed(host, keys);
            for (int pump = 0; pump < advanced.length; pump++) {
                host.pumpFrame(PassBudget.units(1));
                long[] now = executed(host, keys);
                advanced[pump] = -1;
                long total = 0;
                for (int k = 0; k < keys.length; k++) {
                    total += now[k] - before[k];
                    if (now[k] != before[k]) {
                        advanced[pump] = k;
                    }
                }
                run.check(total == 1, where + ": pump " + pump + " ran " + total + " frames");
                before = now;
            }
            boolean rotation = advanced[0] != advanced[1] && advanced[1] != advanced[2] && advanced[0] != advanced[2];
            for (int pump = keys.length; pump < advanced.length; pump++) {
                rotation &= advanced[pump] == advanced[pump - keys.length];
            }
            run.check(rotation, where + ": the devices did not take turns: " + Arrays.toString(advanced));
            host.pumpUntilIdle(keys);
            lastFrame(run, deliveries, FRAMES, where);

            where = mode + " millis(4)";
            for (int k = 0; k < keys.length; k++) {
                for (int f = FRAMES + 1; f <= 2 * FRAMES; f++) {
                    tee.offer(keys[k], ReadbackScenario.frame(surfaces[k], f));
                }
            }
            long[] start = executed(host, keys);
            long left = keys.length * FRAMES;
            int pumps = 0;
            while (left > 0 && pumps <= keys.length * FRAMES) {
                host.pumpFrame(PassBudget.millis(4));
                pumps++;
                long[] now = executed(host, keys);
                long ran = 0;
                for (int k = 0; k < keys.length; k++) {
                    ran += now[k] - start[k];
                }
                run.check(keys.length * FRAMES - ran < left, where + ": pump " + pumps + " ran no unit");
                left = keys.length * FRAMES - ran;
            }
            run.check(left == 0, where + ": " + left + " frames left after " + pumps + " pumps");
            host.pumpUntilIdle(keys);
            lastFrame(run, deliveries, 2 * FRAMES, where);
            for (DeviceKey key : keys) {
                tee.release(key);
            }
            run.close(host);
        }
    }

    private static long[] executed(GlTestHost host, DeviceKey[] keys) {
        long[] out = new long[keys.length];
        for (int k = 0; k < keys.length; k++) {
            out[k] = host.stats(keys[k]).framesExecuted;
        }
        return out;
    }

    private static void lastFrame(ScenarioRun run, Deliveries[] deliveries, long frameId, String where) {
        for (int k = 0; k < deliveries.length; k++) {
            Deliveries.Delivery last = deliveries[k].last();
            run.check(
                last != null && last.frameId == frameId,
                where + ": device " + k + "'s last readback is " + last + ", expected frame " + frameId);
        }
    }
}
