package io.github.mindbound.opengpu.gltestkit.scenario;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.cmd.HandleTypes;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.device.TeeRenderHost;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexFreeRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.QueryPolicy;
import io.github.mindbound.opengpu.gl.UploadProbe;
import io.github.mindbound.opengpu.gl.host.DeviceStats;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * Scenario 4 (S8 plan §4.3; G8, 28:593-597): uploads under the MC-like start's pixel store (alignment 8, row length
 * 7, skips, swap bytes and LSB first) with an unpack PBO bound, on both formats, in both quad modes, with the host's
 * mirrors on (Q-S8-1) and off. Texture widths 1, 2, 3, 5, 7, 1 023 and 1 024 by heights 1 and 1 024: each created,
 * updated whole, then at each corner and the middle with a pump between (so each reaches GL as its own
 * {@code glTexSubImage2D}), then the middle twice before one pump; each payload's first texel is the rgb565 value
 * 0x1234 from bytes 34 12 (0x34 on index8). A frame then blits the texture's four corners and middle onto the surface,
 * and the delivered readback is checked by a ShadowOracle and for that texel. Then the same full handle is freed and
 * created again, with another size, while its GPU delete is pending (O7; S8 plan §3.10). G13b's probe counts every
 * upload per key and must agree with DeviceStats. Kills plan §4.7's {@code UNPACK_ALIGNMENT} and
 * {@code UNPACK_SWAP_BYTES} rows.
 */
public final class UploadsScenario extends Scenario {

    static final int[] WIDTHS = { 1, 2, 3, 5, 7, 1023, 1024 };
    static final int[] HEIGHTS = { 1, 1024 };
    private static final int SURFACE_W = 64;
    private static final int SURFACE_H = 48;
    /** The blit windows' size and pitch on the surface: five side by side. */
    private static final int WINDOW_W = 12;
    private static final int WINDOW_H = 9;
    private static final int PITCH = 13;

    @Override
    protected void run(ScenarioRun run) {
        for (QuadMode mode : QuadMode.values()) {
            for (boolean mirrors : new boolean[] { true, false }) {
                Probe probe = new Probe();
                GlTestHost host = run.host(
                    SyntheticGlyphs.TABLE,
                    mode,
                    GlTestHost.defaultOptions()
                        .mirrors(mirrors)
                        .forceFetchAfterPasses(1),
                    QueryPolicy.DRIVER,
                    probe);
                TeeRenderHost tee = run.tee(host);
                for (int format : new int[] { Formats.INDEX8, Formats.RGB565 }) {
                    String where = mode + (mirrors ? " mirrors " : " no mirrors ") + Formats.name(format);
                    DeviceKey key = run.key();
                    device(
                        run,
                        host,
                        tee,
                        probe,
                        key,
                        new SurfaceDesc(0, SURFACE_W, SURFACE_H, format),
                        mirrors,
                        where);
                }
                run.close(host);
            }
        }
    }

    private static void device(ScenarioRun run, GlTestHost host, TeeRenderHost tee, Probe probe, DeviceKey key,
        SurfaceDesc surface, boolean mirrors, String where) {
        Deliveries deliveries = new Deliveries();
        tee.open(key, surface, deliveries);
        long[] frameId = { 0 };
        int slot = 0;
        for (int w : WIDTHS) {
            for (int h : HEIGHTS) {
                int handle = Handles.pack(HandleTypes.TEXTURE, ++slot, 1);
                texture(run, host, tee, key, surface, deliveries, frameId, handle, w, h, where + " " + w + "x" + h);
            }
        }
        generationWrap(run, host, tee, key, surface, deliveries, frameId, where + " generation wrap");
        DeviceStats stats = host.stats(key);
        long[] probed = probe.of(key);
        run.check(
            stats.uploads == probed[0] && stats.uploadBytes == probed[1],
            where + ": the upload probe saw "
                + probed[0]
                + " uploads of "
                + probed[1]
                + " B, DeviceStats "
                + stats.uploads
                + " of "
                + stats.uploadBytes
                + " B (G13b)");
        run.check(stats.uploads > 0, where + ": no upload counted");
        if (mirrors) {
            run.check(
                stats.mirrorAdoptions > 0 && stats.mirrorClones > 0,
                where + ": copy-on-write adopted "
                    + stats.mirrorAdoptions
                    + " and cloned "
                    + stats.mirrorClones
                    + " (Q-S8-1)");
        }
        tee.release(key);
    }

    /** One texture's uploads, then the frame that blits its corners and middle; the texture is freed after. */
    private static void texture(ScenarioRun run, GlTestHost host, TeeRenderHost tee, DeviceKey key, SurfaceDesc surface,
        Deliveries deliveries, long[] frameId, int handle, int w, int h, String where) {
        int format = surface.format;
        tee.offer(key, new TexCreateRecord(1, handle, w, h, format));
        tee.offer(key, new TexUpdateRecord(1, handle, 0, 0, w, h, payload(w, h, format, handle)));
        host.pumpFrame();
        int sw = Math.min(w, 3);
        int sh = Math.min(h, 2);
        int[][] corners = { { 0, 0 }, { w - sw, 0 }, { 0, h - sh }, { w - sw, h - sh },
            { (w - sw) / 2, (h - sh) / 2 } };
        for (int k = 0; k < corners.length; k++) {
            tee.offer(
                key,
                new TexUpdateRecord(1, handle, corners[k][0], corners[k][1], sw, sh, payload(sw, sh, format, k + 7)));
            host.pumpFrame();
        }
        // Repeated updates of one region before a pump: the second wins
        tee.offer(
            key,
            new TexUpdateRecord(1, handle, corners[4][0], corners[4][1], sw, sh, payload(sw, sh, format, 41)));
        tee.offer(
            key,
            new TexUpdateRecord(1, handle, corners[4][0], corners[4][1], sw, sh, payload(sw, sh, format, 43)));
        host.pumpFrame();
        int bw = Math.min(w, WINDOW_W);
        int bh = Math.min(h, WINDOW_H);
        int[][] windows = { { 0, 0 }, { w - bw, 0 }, { 0, h - bh }, { w - bw, h - bh },
            { (w - bw) / 2, (h - bh) / 2 } };
        byte[][] records = new byte[windows.length + 1][];
        records[0] = TestEncoder.clear(format == Formats.INDEX8 ? 9 : 0xFF102030);
        for (int i = 0; i < windows.length; i++) {
            records[i + 1] = TestEncoder.blit(handle, windows[i][0], windows[i][1], bw, bh, PITCH * i, 0, 0);
        }
        int before = deliveries.count();
        long id = ++frameId[0];
        tee.offer(key, new FrameRecord(1, id, new byte[][] { TestEncoder.segment(records) }, FrameTotals.EMPTY));
        host.pumpUntilIdle(key);
        Deliveries.Delivery last = deliveries.last();
        if (run.check(
            deliveries.count() == before + 1 && last.frameId == id,
            where + ": one readback of frame " + id + ", got " + deliveries)) {
            // The top-left window's first pixel is the top-left corner update's first texel: 0x1234 from bytes 34 12
            boolean index8 = format == Formats.INDEX8;
            int value = index8 ? last.pixels[0] & 0xFF : (last.pixels[0] & 0xFF) | (last.pixels[1] & 0xFF) << 8;
            run.check(
                value == (index8 ? 0x34 : 0x1234),
                where + String
                    .format(Locale.ROOT, ": pixel (0, 0) is 0x%04X, expected the texel from bytes 34 12", value));
        }
        tee.offer(key, new TexFreeRecord(1, handle));
    }

    /**
     * A full handle freed and created again, with another size, while its GPU delete is pending: the host deletes, then
     * creates (O7; S8 plan §3.10), and the frame draws the new texture.
     */
    private static void generationWrap(ScenarioRun run, GlTestHost host, TeeRenderHost tee, DeviceKey key,
        SurfaceDesc surface, Deliveries deliveries, long[] frameId, String where) {
        int format = surface.format;
        int handle = Handles.pack(HandleTypes.TEXTURE, 300, 65535);
        tee.offer(key, new TexCreateRecord(1, handle, 5, 3, format));
        tee.offer(key, new TexUpdateRecord(1, handle, 0, 0, 5, 3, payload(5, 3, format, 3)));
        host.pumpUntilIdle(key);
        tee.offer(key, new TexFreeRecord(1, handle));
        tee.offer(key, new TexCreateRecord(1, handle, 3, 7, format));
        tee.offer(key, new TexUpdateRecord(1, handle, 1, 2, 2, 4, payload(2, 4, format, 5)));
        int before = deliveries.count();
        long id = ++frameId[0];
        tee.offer(
            key,
            new FrameRecord(
                1,
                id,
                new byte[][] { TestEncoder.segment(
                    TestEncoder.clear(format == Formats.INDEX8 ? 200 : 0xFFFFFFFF),
                    TestEncoder.blit(handle, 0, 0, 5, 7, 1, 1, 0)) },
                FrameTotals.EMPTY));
        host.pumpUntilIdle(key);
        run.check(deliveries.count() == before + 1, where + ": one readback, got " + deliveries);
        tee.offer(key, new TexFreeRecord(1, handle));
        host.pumpUntilIdle(key);
        run.check(host.stats(key).gpuTextures == 0, where + ": GPU textures left: " + host.stats(key));
    }

    /** w·h texels of a seeded pattern with zeros among them, the first texel 0x1234 as bytes 34 12 (0x34 on index8). */
    static byte[] payload(int w, int h, int format, int seed) {
        int bpp = Formats.bytesPerPixel(format);
        byte[] out = new byte[w * h * bpp];
        int state = seed * 0x9E3779B9 + 0x7F4A7C15;
        for (int i = 0; i < w * h; i++) {
            state = state * 1664525 + 1013904223;
            int v = i == 0 ? 0x1234 : (state >>> 9) % 7 == 0 ? 0 : state >>> 13;
            out[i * bpp] = (byte) v;
            if (bpp == 2) {
                out[i * bpp + 1] = (byte) (v >>> 8);
            }
        }
        return out;
    }

    /** G13b's hook (28 §6.7): uploads and bytes per key. Called on the GL thread, read after the pump. */
    static final class Probe implements UploadProbe {

        private final Map<DeviceKey, long[]> counts = new HashMap<>();

        @Override
        public synchronized void upload(long session, int deviceId, int handle, int bytes, long stageNanos,
            long callNanos) {
            DeviceKey key = new DeviceKey(session, deviceId);
            long[] c = counts.get(key);
            if (c == null) {
                c = new long[2];
                counts.put(key, c);
            }
            c[0]++;
            c[1] += bytes;
        }

        /** {uploads, bytes} of the key. */
        synchronized long[] of(DeviceKey key) {
            long[] c = counts.get(key);
            return c == null ? new long[2] : c.clone();
        }
    }
}
