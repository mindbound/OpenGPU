package io.github.mindbound.opengpu.gltestkit.bench;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.cmd.HandleTypes;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.gl.GlBackend;
import io.github.mindbound.opengpu.gl.GlLedger;
import io.github.mindbound.opengpu.gl.GpuTimer;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.QueryPolicy;
import io.github.mindbound.opengpu.gl.UploadProbe;
import io.github.mindbound.opengpu.gl.host.DeviceBackend;
import io.github.mindbound.opengpu.gl.host.HostOptions;
import io.github.mindbound.opengpu.gltestkit.GlInfo;
import io.github.mindbound.opengpu.gltestkit.GlThread;
import io.github.mindbound.opengpu.gltestkit.host.RenderCoreTest;

/**
 * G13b's upload bench (28 §6.7, 28:613; S8 plan §4.4) and RM:43's other GL hooks, on the compatibility context, through
 * {@code :gl}'s production calls on a {@link GlBackend} of its own; only the glBench task runs it, never check (S8
 * amendments G). Each measured unit runs in its own pass bracket, 20 warm-ups and the median of 100. It writes
 * {@code gl-testkit/build/reports/opengpu-bench/uploads.txt} (the task's {@code opengpu.bench.report}) with
 * {@code GL_RENDERER}, and prints the same lines. A smoke figure per GPU in S8; RM:43's measurements run it on Intel,
 * NVIDIA and AMD. The task runs it without Java assertions, so the dev builds' integrity CRCs and per-pass error checks
 * stay out of the figures (28:1082; S8 amendments B).
 * <ul>
 * <li><b>Uploads</b> (G13b): 4 KiB, 64 KiB, 256 KiB, 512 000 B (a full T3 surface) and 2 MiB (T3's largest texture) of
 * {@code GL_R16UI}, and the same of {@code GL_R8} up to 1 MiB, its largest (T2's VRAM; 2 MiB of R8 needs a side over
 * 1 024, 28 §6.2): {@code glTexSubImage2D} of the whole texture from the host's staging buffer. Per upload, the
 * staging copy's and the call's render-thread ns from the {@link UploadProbe} (28 §6.7) and the GPU ns of a
 * {@link GpuTimer} pair around the call.</li>
 * <li><b>Readbacks</b> (24:197; 20:127-138) at T2 and T3: a frame, its readback issued in the same pass, then one pass
 * after another until {@code fetchReadbacks} hands it over with {@code forceFetchAfterPasses} 4: the latency in passes,
 * the fetch's ns, and whether the query was available or the fetch forced (GlBackend's fetchedWithQueryAvailable and
 * fetchedForced). Once with the passes back to back, the worst case in passes, and once paced at 16 ms (60 Hz; a
 * Windows sleep lasts at least a timer tick).</li>
 * <li><b>Quad modes</b> (G16, 28:415; H65): the same T2 and T3 frames, 2 000 {@code FILL_RECT}s, 200 {@code BLIT}s
 * and a screen of {@code TEXT}, in {@code QuadMode.EXPANDED} and {@code INSTANCED}: {@code runFrame}'s CPU ns and the
 * GPU ns of a {@link GpuTimer} pair around it.</li>
 * </ul>
 */
@RenderCoreTest
@Tag("bench")
class GlUploadBench {

    static final int WARMUPS = 20;
    static final int RUNS = 100;
    private static final long SESSION = 43;

    @Test
    @Timeout(600)
    void measures(GlThread gl) throws Exception {
        List<String> report = new ArrayList<>();
        GlInfo info = gl.info();
        report.add("OpenGPU glBench (S8 plan §4.4; 28 §6.7; RM:43)");
        report.add("GL_RENDERER " + info.renderer);
        report.add("GL_VENDOR " + info.vendor);
        report.add("GL_VERSION " + info.version);
        report.add(
            "profile " + info.profile
                + ", LWJGL "
                + info.lwjglVersion
                + ", Java "
                + System.getProperty("java.version")
                + ", assertions "
                + (assertionsOn() ? "on" : "off"));
        report.add(String.format(Locale.ROOT, "%d warm-ups, median of %d", WARMUPS, RUNS));
        report.add("");
        uploads(gl, report);
        report.add("");
        readbacks(gl, report);
        report.add("");
        quadModes(gl, report);
        StringBuilder text = new StringBuilder();
        for (String line : report) {
            text.append(line)
                .append('\n');
        }
        System.out.print(text);
        String path = System.getProperty("opengpu.bench.report");
        if (path != null) {
            File file = new File(path);
            Files.createDirectories(
                file.getParentFile()
                    .toPath());
            try (Writer out = new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)) {
                out.write(text.toString());
            }
        }
    }

    // ---- Uploads (G13b)

    private static void uploads(final GlThread gl, List<String> report) throws Exception {
        report.add("Uploads (G13b): glTexSubImage2D of a whole texture from the staging buffer, ns, median");
        report.add(
            String.format(
                Locale.ROOT,
                "%-9s %-10s %9s %8s %10s %12s %10s",
                "format",
                "size",
                "bytes",
                "staging",
                "call",
                "GPU",
                "MB/s(GPU)"));
        int[][] r8 = { { 64, 64 }, { 256, 256 }, { 512, 512 }, { 800, 640 }, { 1024, 1024 } };
        int[][] r16 = { { 64, 32 }, { 256, 128 }, { 512, 256 }, { 640, 400 }, { 1024, 1024 } };
        for (int format : new int[] { Formats.INDEX8, Formats.RGB565 }) {
            int[][] shapes = format == Formats.INDEX8 ? r8 : r16;
            final Probe probe = new Probe();
            final GlBackend backend = new GlBackend(SyntheticGlyphs.TABLE, QuadMode.EXPANDED, new GlLedger(), probe);
            final DeviceBackend device = open(gl, backend, new SurfaceDesc(0, 64, 64, format));
            try {
                for (int s = 0; s < shapes.length; s++) {
                    final int w = shapes[s][0];
                    final int h = shapes[s][1];
                    final int handle = Handles.pack(HandleTypes.TEXTURE, s, 0);
                    final byte[] payload = pattern(w * h * Formats.bytesPerPixel(format), s + 1);
                    long[] stage = new long[RUNS];
                    long[] call = new long[RUNS];
                    long[] gpu = new long[RUNS];
                    final GpuTimer timer = new GpuTimer(backend.ledger(), SESSION, 0, "bench-upload");
                    pass(gl, backend, () -> {
                        ByteBuffer zeros = backend.staging(payload.length);
                        for (int i = 0; i < payload.length; i++) {
                            zeros.put(i, (byte) 0);
                        }
                        device.createTexture(handle, w, h, format, zeros);
                        return null;
                    });
                    for (int run = -WARMUPS; run < RUNS; run++) {
                        long[] sample = pass(gl, backend, () -> {
                            ByteBuffer staging = backend.staging(payload.length);
                            staging.put(payload)
                                .flip();
                            timer.begin();
                            device.updateTexture(handle, 0, 0, w, h, staging);
                            timer.end();
                            return new long[] { probe.stageNanos, probe.callNanos, timer.elapsedNanos() };
                        });
                        if (run >= 0) {
                            stage[run] = sample[0];
                            call[run] = sample[1];
                            gpu[run] = sample[2];
                        }
                    }
                    pass(gl, backend, () -> {
                        timer.delete();
                        device.deleteTexture(handle);
                        return null;
                    });
                    long g = median(gpu);
                    report.add(
                        String.format(
                            Locale.ROOT,
                            "%-9s %-10s %9d %8d %10d %12d %10.0f",
                            format == Formats.INDEX8 ? "GL_R8" : "GL_R16UI",
                            w + "x" + h,
                            payload.length,
                            median(stage),
                            median(call),
                            g,
                            g > 0 ? payload.length * 1e3 / g : 0.0));
                    Assertions.assertTrue(median(call) > 0, "no call time measured");
                    Assertions.assertEquals(RUNS + WARMUPS + 1, probe.uploads - s * (RUNS + WARMUPS + 1));
                }
            } finally {
                close(gl, backend, device);
            }
        }
    }

    // ---- Readbacks (24:197)

    private static void readbacks(GlThread gl, List<String> report) throws Exception {
        int force = HostOptions.FORCE_FETCH_AFTER_PASSES;
        report.add(
            "Readbacks (24:197): a frame and its issue in one pass, then passes until the fetch (force after " + force
                + ")");
        report.add(
            String.format(
                Locale.ROOT,
                "%-4s %-12s %8s %8s %8s %11s %9s %7s",
                "tier",
                "passes",
                "latency",
                "max",
                "min",
                "fetch ns",
                "available",
                "forced"));
        for (int tier = 2; tier <= 3; tier++) {
            for (int paceMillis : new int[] { 0, 16 }) {
                readback(gl, report, SurfaceDesc.forTier(tier), paceMillis, force);
            }
        }
    }

    private static void readback(GlThread gl, List<String> report, SurfaceDesc surface, int paceMillis, int force)
        throws Exception {
        final GlBackend backend = new GlBackend(
            SyntheticGlyphs.TABLE,
            QuadMode.EXPANDED,
            new GlLedger(),
            UploadProbe.NONE,
            QueryPolicy.DRIVER);
        final DeviceBackend device = open(gl, backend, surface);
        int runs = paceMillis == 0 ? RUNS : RUNS / 2;
        long[] latency = new long[runs];
        long[] fetch = new long[runs];
        final long[] pass = { 0 };
        final int[] delivered = { 0 };
        final DeviceBackend.ReadbackOut out = (epoch, frameId, pixels) -> delivered[0]++;
        try {
            for (int run = -WARMUPS; run < runs; run++) {
                final long id = run + WARMUPS + 1L;
                final FrameRecord frame = frame(surface, id, 200, 20, 5);
                final long issued = ++pass[0];
                pass(gl, backend, () -> {
                    device.runFrame(frame);
                    device.issueReadback(1, id, issued);
                    return null;
                });
                long took = 0;
                int before = delivered[0];
                for (int p = 0; p < 2 * force && delivered[0] == before; p++) {
                    if (paceMillis > 0) {
                        Thread.sleep(paceMillis);
                    }
                    final long now = ++pass[0];
                    took = pass(gl, backend, () -> {
                        long start = System.nanoTime();
                        device.fetchReadbacks(out, now, force);
                        return System.nanoTime() - start;
                    });
                }
                Assertions.assertEquals(before + 1, delivered[0], "the readback of frame " + id + " never came");
                if (run >= 0) {
                    latency[run] = pass[0] - issued;
                    fetch[run] = took;
                }
            }
            long available = gl.call(() -> backend.fetchedWithQueryAvailable(device));
            long forced = gl.call(() -> backend.fetchedForced(device));
            report.add(
                String.format(
                    Locale.ROOT,
                    "T%-3d %-12s %8d %8d %8d %11d %9d %7d",
                    surface.tier,
                    paceMillis == 0 ? "back to back" : "every " + paceMillis + " ms",
                    median(latency),
                    max(latency),
                    min(latency),
                    median(fetch),
                    available,
                    forced));
        } finally {
            close(gl, backend, device);
        }
    }

    // ---- Quad modes (G16, H65)

    private static void quadModes(GlThread gl, List<String> report) throws Exception {
        report.add("Quad modes (G16; H65): runFrame of the same frame, ns, median");
        report.add(String.format(Locale.ROOT, "%-4s %-10s %10s %10s %s", "tier", "mode", "CPU", "GPU", "frame"));
        for (int tier = 2; tier <= 3; tier++) {
            SurfaceDesc surface = SurfaceDesc.forTier(tier);
            for (QuadMode mode : QuadMode.values()) {
                final GlBackend backend = new GlBackend(SyntheticGlyphs.TABLE, mode, new GlLedger(), UploadProbe.NONE);
                final DeviceBackend device = open(gl, backend, surface);
                final int handle = Handles.pack(HandleTypes.TEXTURE, 0, 0);
                final byte[] texels = pattern(64 * 64 * surface.bytesPerPixel(), 7);
                try {
                    pass(gl, backend, () -> {
                        ByteBuffer staging = backend.staging(texels.length);
                        staging.put(texels)
                            .flip();
                        device.createTexture(handle, 64, 64, surface.format, staging);
                        return null;
                    });
                    final FrameRecord frame = frame(surface, 1, 2000, 200, surface.height / 13);
                    final GpuTimer timer = new GpuTimer(backend.ledger(), SESSION, 0, "bench-frame");
                    long[] cpu = new long[RUNS];
                    long[] gpu = new long[RUNS];
                    for (int run = -WARMUPS; run < RUNS; run++) {
                        long[] sample = pass(gl, backend, () -> {
                            timer.begin();
                            long start = System.nanoTime();
                            device.runFrame(frame);
                            long cpuNanos = System.nanoTime() - start;
                            timer.end();
                            return new long[] { cpuNanos, timer.elapsedNanos() };
                        });
                        if (run >= 0) {
                            cpu[run] = sample[0];
                            gpu[run] = sample[1];
                        }
                    }
                    pass(gl, backend, () -> {
                        timer.delete();
                        return null;
                    });
                    report.add(
                        String.format(
                            Locale.ROOT,
                            "T%-3d %-10s %10d %10d %s",
                            tier,
                            mode,
                            median(cpu),
                            median(gpu),
                            "CLEAR, 2 000 FILL_RECT, 200 BLIT 16x16, " + surface.height / 13
                                + " TEXT lines of "
                                + surface.width / 6
                                + " cells"));
                } finally {
                    close(gl, backend, device);
                }
            }
        }
    }

    // ---- Helpers

    /** A frame of a CLEAR, fills, 16×16 blits of texture 0x08000000 when blits > 0, and full lines of TEXT. */
    static FrameRecord frame(SurfaceDesc surface, long id, int fills, int blits, int lines) {
        boolean index8 = surface.format == Formats.INDEX8;
        List<byte[]> records = new ArrayList<>();
        records.add(TestEncoder.clear(index8 ? 3 : 0xFF203040));
        for (int i = 0; i < fills; i++) {
            records.add(
                TestEncoder.fill(
                    i * 37 % surface.width,
                    i * 53 % surface.height,
                    8 + i % 40,
                    6 + i % 30,
                    index8 ? i & 0xFF : 0xFF000000 | i * 0x10204));
        }
        if (blits > 0 && lines > 5) {
            for (int i = 0; i < blits; i++) {
                records.add(
                    TestEncoder.blit(
                        Handles.pack(HandleTypes.TEXTURE, 0, 0),
                        i % 48,
                        i % 48,
                        16,
                        16,
                        i * 29 % surface.width,
                        i * 31 % surface.height,
                        i & 1));
            }
        }
        StringBuilder line = new StringBuilder();
        for (int c = 0; c < surface.width / 6; c++) {
            line.append((char) (0x21 + c % 94));
        }
        for (int l = 0; l < lines; l++) {
            records.add(
                TestEncoder
                    .text(0, 13 * l, index8 ? 255 : 0xFFFFFFFF, index8 ? 1 : 0xFF000001, l & 1, 0, line.toString()));
        }
        return new FrameRecord(
            1,
            id,
            new byte[][] { TestEncoder.segment(records.toArray(new byte[0][])) },
            FrameTotals.EMPTY);
    }

    /** Opens a device in its own pass. */
    private static DeviceBackend open(GlThread gl, final GlBackend backend, final SurfaceDesc surface)
        throws Exception {
        return pass(gl, backend, () -> backend.open(new DeviceKey(SESSION, 1), surface));
    }

    /** Destroys the device and closes the backend; the ledger must then be empty (24:218). */
    private static void close(GlThread gl, final GlBackend backend, final DeviceBackend device) throws Exception {
        gl.call(() -> {
            backend.beginPass();
            try {
                device.destroy();
            } finally {
                backend.endPass();
            }
            backend.close();
            return null;
        });
        Assertions.assertTrue(
            backend.ledger()
                .isEmpty(),
            "the bench left ledger rows: " + backend.ledger()
                .rows());
    }

    /** The work in one beginPass/endPass bracket on the GL thread (S8 amendments B). */
    private static <T> T pass(GlThread gl, final GlBackend backend, final Callable<T> work) throws Exception {
        return gl.call(() -> {
            backend.beginPass();
            try {
                return work.call();
            } finally {
                backend.endPass();
            }
        });
    }

    static byte[] pattern(int n, int seed) {
        byte[] b = new byte[n];
        int state = seed * 0x9E3779B9;
        for (int i = 0; i < n; i++) {
            state = state * 1664525 + 1013904223;
            b[i] = (byte) (state >>> 24);
        }
        return b;
    }

    static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    static long max(long[] values) {
        long m = Long.MIN_VALUE;
        for (long v : values) {
            m = Math.max(m, v);
        }
        return m;
    }

    static long min(long[] values) {
        long m = Long.MAX_VALUE;
        for (long v : values) {
            m = Math.min(m, v);
        }
        return m;
    }

    private static boolean assertionsOn() {
        boolean on = false;
        assert on = true;
        return on;
    }

    /** G13b's hook: the last upload's figures, and the count. GL thread. */
    static final class Probe implements UploadProbe {

        long stageNanos;
        long callNanos;
        long uploads;

        @Override
        public void upload(long session, int deviceId, int handle, int bytes, long stageNanos, long callNanos) {
            this.stageNanos = stageNanos;
            this.callNanos = callNanos;
            uploads++;
        }
    }
}
