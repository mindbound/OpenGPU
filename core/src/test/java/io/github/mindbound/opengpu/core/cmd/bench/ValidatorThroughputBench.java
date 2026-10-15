package io.github.mindbound.opengpu.core.cmd.bench;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.cmd.LiveView;
import io.github.mindbound.opengpu.core.cmd.Validation;
import io.github.mindbound.opengpu.core.cmd.Validator;
import io.github.mindbound.opengpu.core.cmd.bench.ValidatorWorkloads.Workload;

/**
 * The validator's time per submit (28 §8.1: one call's validation must fit the OC worker's 0.2 ms, 24:34, at the
 * 65 536-byte cap; RM:43), run by {@code :core:benchTest} and never by {@code check}. Per load: the first call's time,
 * then 5 000 warm-up runs and 1 000 timed ones with {@code System.nanoTime}, reported as the median and the 99th
 * percentile in µs, MiB/s at the median, and whether the median is within 0.2 ms. The first calls run in load order, so
 * only the first load's first call (records at 65 536 B) is a truly cold JVM; the rest have the validator partly
 * compiled. Nothing is asserted on time, so the bench cannot make a build flaky. Validation is linear in {@code #s}
 * (28:287), with each record's cost bounded by {@code TEXT}'s 1 024-byte string cap (28 §3.6 row 12); every size's
 * loads hold strings of one length, so the per-size figures show the slope in {@code #s}, not how a record's cost
 * grows with its string. The report goes to stdout and, only when the {@code opengpu.bench.report} system property is
 * set, to the file it names: {@code :core:benchTest} passes the absolute path of
 * {@code core/build/reports/opengpu-bench/validator.txt}.
 */
@Tag("bench")
class ValidatorThroughputBench {

    private static final int WARM_UP = 5000;
    private static final int TIMED = 1000;
    private static final long BUDGET_NS = 200000;
    /** The report file's path, which {@code :core:benchTest} sets; without it the report goes to stdout only. */
    static final String REPORT_PROPERTY = "opengpu.bench.report";

    /** Keeps the results reachable, so that no run is optimized away. */
    static volatile Validation sink;

    @Test
    void validationTimePerSubmit() throws IOException {
        List<Workload> loads = ValidatorWorkloads.all();
        LiveView view = new ValidatorWorkloads.OneTextureView();
        long[] first = new long[loads.size()];
        for (int i = 0; i < loads.size(); i++) {
            long t0 = System.nanoTime();
            sink = Validator.validate(loads.get(i).segment, view);
            first[i] = System.nanoTime() - t0;
            Assertions.assertEquals(loads.get(i).expected != null, sink.isAccepted(), loads.get(i).name);
        }
        List<String> lines = new ArrayList<>();
        lines.add(
            "OpenGPU validator throughput (28 §8.1; RM:43), T3 surface, caps (65536, 65536, 4096, unbounded fill)");
        lines.add(
            String.format(
                Locale.ROOT,
                "JVM: %s %s (%s), java.version %s",
                System.getProperty("java.vm.name"),
                System.getProperty("java.vm.version"),
                System.getProperty("java.vm.vendor"),
                System.getProperty("java.version")));
        lines.add(
            String.format(
                Locale.ROOT,
                "OS: %s %s %s, %d processors",
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch"),
                Runtime.getRuntime()
                    .availableProcessors()));
        lines.add(String.format(Locale.ROOT, "warm-up %d runs, timed %d runs per load", WARM_UP, TIMED));
        lines.add(
            String.format(
                Locale.ROOT,
                "%-12s %7s %10s %10s %10s %9s  %s",
                "load",
                "bytes",
                "first us",
                "median us",
                "p99 us",
                "MiB/s",
                "median <= 0.2 ms"));
        for (int i = 0; i < loads.size(); i++) {
            Workload w = loads.get(i);
            for (int k = 0; k < WARM_UP; k++) {
                sink = Validator.validate(w.segment, view);
            }
            long[] times = new long[TIMED];
            for (int k = 0; k < TIMED; k++) {
                long t0 = System.nanoTime();
                sink = Validator.validate(w.segment, view);
                times[k] = System.nanoTime() - t0;
            }
            Arrays.sort(times);
            long median = times[TIMED / 2];
            long p99 = times[TIMED * 99 / 100];
            double mibPerSecond = w.segment.length / (median / 1e9) / (1024.0 * 1024.0);
            lines.add(
                String.format(
                    Locale.ROOT,
                    "%-12s %7d %10.1f %10.1f %10.1f %9.0f  %s",
                    w.name,
                    w.segment.length,
                    first[i] / 1000.0,
                    median / 1000.0,
                    p99 / 1000.0,
                    mibPerSecond,
                    median <= BUDGET_NS ? "yes" : "no"));
        }
        StringBuilder report = new StringBuilder();
        for (String line : lines) {
            System.out.println(line);
            report.append(line)
                .append(System.lineSeparator());
        }
        String path = System.getProperty(REPORT_PROPERTY);
        if (path == null) {
            System.out.println("no " + REPORT_PROPERTY + " property: the report is not written to a file");
            return;
        }
        File file = new File(path);
        File dir = file.getAbsoluteFile()
            .getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        try (Writer out = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            out.write(report.toString());
        }
        System.out.println("written to " + file.getAbsolutePath());
    }
}
