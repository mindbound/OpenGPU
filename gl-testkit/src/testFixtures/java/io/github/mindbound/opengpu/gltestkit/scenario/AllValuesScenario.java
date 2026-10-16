package io.github.mindbound.opengpu.gltestkit.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.cmd.HandleTypes;
import io.github.mindbound.opengpu.core.colour.Colours;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.device.TeeRenderHost;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * Scenario 3 (S8 plan §4.3; 28:583's index recovery, 28:265's levels, the LE order of 28 §5.7): every pixel value of
 * both formats through {@code BLIT} and {@code FILL_RECT} on {@link GlTestHost}, in both quad modes. index8: a 256×1
 * {@code GL_R8} texture of 0-255 blitted without and with {@code KEY0} over a fill, and a {@code FILL_RECT} of each
 * index. rgb565: a 256×256 texture holding all 65 536 values blitted without and with {@code KEY0}, and fills of every
 * 8-bit red, green and blue input, so every 5- and 6-bit level of {@code q} appears. Each delivered readback is
 * checked by a ShadowOracle and against values written out here independently of the oracle. Kills plan §4.7's
 * {@code floor(r * 255.0)} row and {@code KEY0} ignored or applied to 0x0001.
 */
public final class AllValuesScenario extends Scenario {

    private static final int TEXTURE = Handles.pack(HandleTypes.TEXTURE, 1, 1);
    /** The fill under the keyed blit; not a value the texture's first texel has. */
    private static final int UNDER_INDEX = 77;
    private static final int UNDER_RGB = 0xFF8040C0;

    @Override
    protected void run(ScenarioRun run) {
        for (QuadMode mode : QuadMode.values()) {
            GlTestHost host = run.host(mode);
            TeeRenderHost tee = run.tee(host);
            index8(run, host, tee, mode);
            rgb565(run, host, tee, mode);
            run.close(host);
        }
    }

    private static void index8(ScenarioRun run, GlTestHost host, TeeRenderHost tee, QuadMode mode) {
        SurfaceDesc surface = new SurfaceDesc(0, 256, 3, Formats.INDEX8);
        DeviceKey key = run.key();
        Deliveries deliveries = new Deliveries();
        tee.open(key, surface, deliveries);
        byte[] ramp = new byte[256];
        for (int i = 0; i < 256; i++) {
            ramp[i] = (byte) i;
        }
        tee.offer(key, new TexCreateRecord(1, TEXTURE, 256, 1, Formats.INDEX8));
        tee.offer(key, new TexUpdateRecord(1, TEXTURE, 0, 0, 256, 1, ramp));
        List<byte[]> fills = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            fills.add(TestEncoder.fill(i, 2, 1, 1, i));
        }
        byte[][] segments = { TestEncoder.segment(
            TestEncoder.clear(UNDER_INDEX),
            TestEncoder.blit(TEXTURE, 0, 0, 256, 1, 0, 0, 0),
            TestEncoder.blit(TEXTURE, 0, 0, 256, 1, 0, 1, 1)), TestEncoder.segment(fills.toArray(new byte[0][])) };
        byte[] pixels = frame(run, tee, host, key, deliveries, 1, segments, mode + " index8");
        if (pixels == null) {
            return;
        }
        for (int x = 0; x < 256; x++) {
            expect(run, mode + " index8 blit", x, 0, x, pixels[x] & 0xFF);
            expect(run, mode + " index8 blit KEY0", x, 1, x == 0 ? UNDER_INDEX : x, pixels[256 + x] & 0xFF);
            expect(run, mode + " index8 fill", x, 2, x, pixels[512 + x] & 0xFF);
        }
        tee.release(key);
    }

    private static void rgb565(ScenarioRun run, GlTestHost host, TeeRenderHost tee, QuadMode mode) {
        SurfaceDesc surface = new SurfaceDesc(0, 256, 256, Formats.RGB565);
        DeviceKey key = run.key();
        Deliveries deliveries = new Deliveries();
        tee.open(key, surface, deliveries);
        byte[] all = new byte[2 * 65536];
        for (int v = 0; v < 65536; v++) {
            all[2 * v] = (byte) v;
            all[2 * v + 1] = (byte) (v >>> 8);
        }
        tee.offer(key, new TexCreateRecord(1, TEXTURE, 256, 256, Formats.RGB565));
        tee.offer(key, new TexUpdateRecord(1, TEXTURE, 0, 0, 256, 256, all));
        String where = mode + " rgb565";
        byte[] plain = frame(
            run,
            tee,
            host,
            key,
            deliveries,
            1,
            new byte[][] {
                TestEncoder.segment(TestEncoder.clear(UNDER_RGB), TestEncoder.blit(TEXTURE, 0, 0, 256, 256, 0, 0, 0)) },
            where);
        if (plain != null) {
            for (int v = 0; v < 65536; v++) {
                expect(run, where + " blit", v % 256, v / 256, v, value(plain, v));
            }
        }
        int under = Colours.sv(Formats.RGB565, UNDER_RGB);
        byte[] keyed = frame(
            run,
            tee,
            host,
            key,
            deliveries,
            2,
            new byte[][] {
                TestEncoder.segment(TestEncoder.clear(UNDER_RGB), TestEncoder.blit(TEXTURE, 0, 0, 256, 256, 0, 0, 1)) },
            where);
        if (keyed != null) {
            for (int v = 0; v < 65536; v++) {
                expect(run, where + " blit KEY0", v % 256, v / 256, v == 0 ? under : v, value(keyed, v));
            }
        }
        // Every 8-bit input of each channel on its own row: q covers every 5- and 6-bit level (28:265)
        List<byte[]> fills = new ArrayList<>();
        fills.add(TestEncoder.clear(0xFF000000));
        for (int c = 0; c < 256; c++) {
            fills.add(TestEncoder.fill(c, 0, 1, 1, 0xFF000000 | c << 16));
            fills.add(TestEncoder.fill(c, 1, 1, 1, 0xFF000000 | c << 8));
            fills.add(TestEncoder.fill(c, 2, 1, 1, 0xFF000000 | c));
        }
        byte[] levels = frame(
            run,
            tee,
            host,
            key,
            deliveries,
            3,
            new byte[][] { TestEncoder.segment(fills.toArray(new byte[0][])) },
            where);
        if (levels != null) {
            boolean[][] seen = new boolean[3][64];
            for (int c = 0; c < 256; c++) {
                int r5 = (c * 31 + 127) / 255;
                int g6 = (c * 63 + 127) / 255;
                expect(run, where + " red level", c, 0, r5 << 11, value(levels, c));
                expect(run, where + " green level", c, 1, g6 << 5, value(levels, 256 + c));
                expect(run, where + " blue level", c, 2, r5, value(levels, 512 + c));
                seen[0][value(levels, c) >>> 11] = true;
                seen[1][value(levels, 256 + c) >>> 5 & 63] = true;
                seen[2][value(levels, 512 + c) & 31] = true;
            }
            run.check(count(seen[0]) == 32 && count(seen[1]) == 64 && count(seen[2]) == 32, where + ": levels seen");
        }
        tee.release(key);
    }

    /** Offers the frame, pumps until it is delivered, and returns its pixels (the tee checked them), or null. */
    private static byte[] frame(ScenarioRun run, TeeRenderHost tee, GlTestHost host, DeviceKey key,
        Deliveries deliveries, long frameId, byte[][] segments, String where) {
        int before = deliveries.count();
        tee.offer(key, new FrameRecord(1, frameId, segments, FrameTotals.EMPTY));
        host.pumpUntilIdle(key);
        Deliveries.Delivery last = deliveries.last();
        if (!run.check(
            deliveries.count() == before + 1 && last.frameId == frameId,
            where + ": one readback of frame " + frameId + ", got " + deliveries)) {
            return null;
        }
        return last.pixels;
    }

    /** The LE u16 of pixel i (28 §5.7: low byte first on x86-64, 24:18). */
    private static int value(byte[] pixels, int i) {
        return (pixels[2 * i] & 0xFF) | (pixels[2 * i + 1] & 0xFF) << 8;
    }

    private static int count(boolean[] seen) {
        int n = 0;
        for (boolean b : seen) {
            n += b ? 1 : 0;
        }
        return n;
    }

    private static void expect(ScenarioRun run, String what, int x, int y, int expected, int actual) {
        if (expected != actual) {
            run.fail(
                String.format(
                    Locale.ROOT,
                    "%s: pixel (%d, %d) is 0x%04X, expected 0x%04X",
                    what,
                    x,
                    y,
                    actual,
                    expected));
        }
    }
}
