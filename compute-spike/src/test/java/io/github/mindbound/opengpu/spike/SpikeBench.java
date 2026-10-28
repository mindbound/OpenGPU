package io.github.mindbound.opengpu.spike;

import static io.github.mindbound.opengpu.spike.ir.Ir.gid;
import static io.github.mindbound.opengpu.spike.ir.Ir.load;
import static io.github.mindbound.opengpu.spike.ir.Ir.op;

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

import io.github.mindbound.opengpu.spike.emit.Emitter;
import io.github.mindbound.opengpu.spike.ir.Access;
import io.github.mindbound.opengpu.spike.ir.Compare;
import io.github.mindbound.opengpu.spike.ir.Expr;
import io.github.mindbound.opengpu.spike.ir.Op;
import io.github.mindbound.opengpu.spike.ir.Program;
import io.github.mindbound.opengpu.spike.ir.ProgramBuilder;
import io.github.mindbound.opengpu.spike.ir.Stmt;
import io.github.mindbound.opengpu.spike.ir.Type;
import io.github.mindbound.opengpu.spike.load.KernelLoader;
import io.github.mindbound.opengpu.spike.load.KernelScan;
import io.github.mindbound.opengpu.spike.rt.Kernel;

/**
 * The spike's speed (RM:69, 24:248: K1 at most 10 ns/element; S14 plan P9.2, without the interpreter's figure), run by
 * {@code spikeBench8} and {@code spikeBench21} and never by {@code check}; each run is one fresh JVM, and the main
 * session judges the gate on the worst of five runs per JDK on the owner's CPU (P9.3). Per run: the cold time to the
 * first result (emit, scan and define, first dispatch) of the JVM's first program, K1, and of a later one, K2; then
 * 3 000 warm-up and 2 000 timed dispatches of K1 and K2 and of their Java references under the same harness, as the
 * median, p10 and p90 in ns/element (a dispatch's time over its 65 536 invocations); each kernel's {@code run} bytecode
 * size and IR op count. The buffers are the sink: each kernel's timed buffers after its 5 000 dispatches must equal its
 * Java reference's, and P8.2's digests from the warm kernels the references' in this JVM (and the legs' digests.txt).
 * Then, recorded only and after K1's and K2's figures, so that neither FSIN's garbage on JDK 21+ nor these kernels'
 * compilation touches them (P9.1, P9.3; P4.5): K3 and K3' per pair, with FSIN's cost as their difference, and FSQRT
 * through {@code Math.sqrt} against an {@code FMUL} and against javac's {@code Math.sqrt} and {@code StrictMath.sqrt}
 * forms, each with the bytes a timed dispatch allocates per element (S14 amendments I3). Nothing is asserted on time.
 * The report goes to stdout and to the file that {@code opengpu.bench.report} names.
 */
@Tag("bench")
class SpikeBench {

    private static final int WARM_UP = 3000;
    private static final int TIMED = 2000;
    /** K3's and K3''s: a dispatch of theirs costs about a hundred of K1's, and OpTableTest's 30th is compiled. */
    private static final int TABLE_WARM_UP = 50;
    private static final int TABLE_TIMED = 50;
    static final String REPORT_PROPERTY = "opengpu.bench.report";

    /** FSQRT in javac's form, through {@code Math.sqrt} as the emitter lowers it. */
    private static final Kernel MATH_SQRT = new Kernel() {

        @Override
        public void run(int[] b0, int[] b1, int[] b2, int[] b3, int[] u, int x0, int x1, int y0, int y1) {
            for (int x = x0; x < x1; x++) {
                b1[x] = Float.floatToIntBits((float) Math.sqrt(Float.intBitsToFloat(b0[x])));
            }
        }
    };

    /** The same through {@code StrictMath.sqrt}, which the emitter does not use: it is native on JDK 8 (P4.5). */
    private static final Kernel STRICT_SQRT = new Kernel() {

        @Override
        public void run(int[] b0, int[] b1, int[] b2, int[] b3, int[] u, int x0, int x1, int y0, int y1) {
            for (int x = x0; x < x1; x++) {
                b1[x] = Float.floatToIntBits((float) StrictMath.sqrt(Float.intBitsToFloat(b0[x])));
            }
        }
    };

    /** A kernel's dispatches over buffers of their own, which are the sink its digest is read from. */
    private interface Sink {

        /** The {@code n}th dispatch. */
        void dispatch(Kernel kernel, int n);

        String digest();
    }

    /** A recorded kernel's report line, with its median and the bytes a timed dispatch allocates, per element. */
    private static final class Timing {

        final String line;
        final double median;
        final double bytes;

        Timing(String line, double median, double bytes) {
            this.line = line;
            this.median = median;
            this.bytes = bytes;
        }
    }

    @Test
    void kernelsNanosecondsPerElement() throws IOException, ReflectiveOperationException {
        List<String> lines = new ArrayList<>();
        lines.add("OpenGPU compute spike bench (S14 plan P9.2; RM:69: K1 <= 10 ns/element), one JVM");
        lines.add(
            String.format(
                Locale.ROOT,
                "JVM: java.version %s, java.vendor %s, java.home %s, %s %s",
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                System.getProperty("java.home"),
                System.getProperty("java.vm.name"),
                System.getProperty("java.vm.version")));
        lines.add(
            String.format(
                Locale.ROOT,
                "OS: %s %s %s, %d processors",
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                System.getProperty("os.arch"),
                Runtime.getRuntime()
                    .availableProcessors()));
        lines.add(
            String.format(
                Locale.ROOT,
                "dispatches: %d warm-up, %d timed, %d invocations each",
                WARM_UP,
                TIMED,
                SpikeKernels.INVOCATIONS));

        Program heat = SpikeKernels.heat();
        Program mix = SpikeKernels.mix();
        Sink heatSink = heatSink();
        Sink heatJavaSink = heatSink();
        Sink mixSink = mixSink();
        Sink mixJavaSink = mixSink();
        Kernel heatKernel = cold(lines, "K1 Heat, the JVM's first program", heat, heatSink());
        String heatTimes = time("K1 Heat", heatKernel, heatSink, heat);
        String heatJava = time("K1 Java", JavaKernels.HEAT, heatJavaSink, null);
        Kernel mixKernel = cold(lines, "K2 Mix, a later program", mix, mixSink());
        String mixTimes = time("K2 Mix", mixKernel, mixSink, mix);
        String mixJava = time("K2 Java", JavaKernels.MIX, mixJavaSink, null);
        lines.add(
            String.format(
                Locale.ROOT,
                "%-8s %10s %7s %12s %12s %12s",
                "kernel",
                "run bytes",
                "IR ops",
                "median ns/el",
                "p10 ns/el",
                "p90 ns/el"));
        lines.add(heatTimes);
        lines.add(heatJava);
        lines.add(mixTimes);
        lines.add(mixJava);

        // The timed buffers are the sink: each kernel's after its 5 000 dispatches, against its Java reference's
        lines.add(
            "sinks after timing: K1 " + heatSink.digest()
                + " (Java "
                + heatJavaSink.digest()
                + "), K2 "
                + mixSink.digest()
                + " (Java "
                + mixJavaSink.digest()
                + ")");
        // P8.2's digests from the warm kernels, which the legs' digests.txt holds from fresh ones
        String k1 = Digests.sha256(SpikeKernels.heatSteps(heatKernel));
        String k1Java = Digests.sha256(SpikeKernels.heatSteps(JavaKernels.HEAT));
        Sink mixOnce = mixSink();
        Sink mixJavaOnce = mixSink();
        mixOnce.dispatch(mixKernel, 0);
        mixJavaOnce.dispatch(JavaKernels.MIX, 0);
        lines.add(
            "P8.2's digests after timing: k1=" + k1
                + " (Java "
                + k1Java
                + "), k2="
                + mixOnce.digest()
                + " (Java "
                + mixJavaOnce.digest()
                + ")");

        // Recorded only, from here on: K3 and K3', and FSQRT's one-column programs and Java forms
        ThreadAllocation allocation = new ThreadAllocation();
        Program table = SpikeKernels.opTable(true);
        Program tableNoSin = SpikeKernels.opTable(false);
        Sink tableSink = tableSink(true);
        Sink tableNoSinSink = tableSink(false);
        Timing k3 = timeRecorded(
            "K3",
            KernelLoader.load(table),
            tableSink,
            table,
            TABLE_WARM_UP,
            TABLE_TIMED,
            allocation);
        Timing k3NoSin = timeRecorded(
            "K3'",
            KernelLoader.load(tableNoSin),
            tableNoSinSink,
            tableNoSin,
            TABLE_WARM_UP,
            TABLE_TIMED,
            allocation);
        Program sqrt = oneColumn("Sqrt", Op.FSQRT);
        Program square = oneColumn("Square", Op.FMUL);
        Sink sqrtSink = columnSink();
        Sink squareSink = columnSink();
        Sink mathSink = columnSink();
        Sink strictSink = columnSink();
        Timing sqrtTimes = timeRecorded("Sqrt", KernelLoader.load(sqrt), sqrtSink, sqrt, WARM_UP, TIMED, allocation);
        Timing squareTimes = timeRecorded(
            "Square",
            KernelLoader.load(square),
            squareSink,
            square,
            WARM_UP,
            TIMED,
            allocation);
        Timing mathTimes = timeRecorded("Sqrt Math", MATH_SQRT, mathSink, null, WARM_UP, TIMED, allocation);
        Timing strictTimes = timeRecorded("Sqrt Strict", STRICT_SQRT, strictSink, null, WARM_UP, TIMED, allocation);
        lines.add(
            String.format(
                Locale.ROOT,
                "recorded only: K3 and K3' %d warm-up and %d timed dispatches, the rest as K1's; Sqrt Math and"
                    + " Sqrt Strict are Java",
                TABLE_WARM_UP,
                TABLE_TIMED));
        lines.add(
            String.format(
                Locale.ROOT,
                "%-11s %10s %7s %12s %12s %12s %9s",
                "kernel",
                "run bytes",
                "IR ops",
                "median ns/el",
                "p10 ns/el",
                "p90 ns/el",
                "B/el"));
        for (Timing t : Arrays.asList(k3, k3NoSin, sqrtTimes, squareTimes, mathTimes, strictTimes)) {
            lines.add(t.line);
        }
        lines.add(
            String.format(
                Locale.ROOT,
                "FSIN, K3 - K3': %.3f ns/pair (medians), %.2f B/pair",
                k3.median - k3NoSin.median,
                k3.bytes - k3NoSin.bytes));
        lines.add(
            String.format(
                Locale.ROOT,
                "FSQRT, Sqrt - Square: %.3f ns/element; StrictMath.sqrt - Math.sqrt in Java: %.3f ns/element (medians)",
                sqrtTimes.median - squareTimes.median,
                strictTimes.median - mathTimes.median));
        // K3's and K3''s timed buffers against a fresh class's first dispatch, which for K3 is the legs' k3; Sqrt's
        // against both Java forms, since Math.sqrt and StrictMath.sqrt are both correctly rounded
        String k3Fresh = Digests.sha256(SpikeKernels.opTableRun(KernelLoader.load(table), true));
        String k3NoSinFresh = Digests.sha256(SpikeKernels.opTableRun(KernelLoader.load(tableNoSin), false));
        lines.add(
            "K3 after timing: " + tableSink.digest()
                + " (a fresh class's first dispatch, the legs' k3: "
                + k3Fresh
                + "), K3' "
                + tableNoSinSink.digest()
                + " (fresh "
                + k3NoSinFresh
                + ")");
        lines.add(
            "Sqrt after timing: " + sqrtSink.digest()
                + " (Math.sqrt in Java "
                + mathSink.digest()
                + ", StrictMath.sqrt in Java "
                + strictSink.digest()
                + ")");
        report(lines);
        Assertions.assertEquals(heatJavaSink.digest(), heatSink.digest(), "K1's sink after timing");
        Assertions.assertEquals(mixJavaSink.digest(), mixSink.digest(), "K2's sink after timing");
        Assertions.assertEquals(k1Java, k1, "K1's warm digest");
        Assertions.assertEquals(mixJavaOnce.digest(), mixOnce.digest(), "K2's warm digest");
        Assertions.assertEquals(k3Fresh, tableSink.digest(), "K3's sink after timing");
        Assertions.assertEquals(k3NoSinFresh, tableNoSinSink.digest(), "K3''s sink after timing");
        Assertions.assertEquals(mathSink.digest(), sqrtSink.digest(), "Sqrt's sink after timing");
        Assertions.assertEquals(strictSink.digest(), sqrtSink.digest(), "Sqrt's sink against StrictMath.sqrt");
    }

    /** K1's dispatches ping-pong over two buffers of its own, from the fixed field. */
    private static Sink heatSink() {
        int[] a = SpikeKernels.heatField();
        int[] b = new int[SpikeKernels.INVOCATIONS];
        int[] uniforms = SpikeKernels.heatUniforms();
        int size = SpikeKernels.SIZE;
        return new Sink() {

            @Override
            public void dispatch(Kernel kernel, int n) {
                kernel.run(n % 2 == 0 ? a : b, n % 2 == 0 ? b : a, null, null, uniforms, 0, size, 0, size);
            }

            @Override
            public String digest() {
                return Digests.sha256(a, b);
            }
        };
    }

    /** K2's dispatches, each over the fixed input into an output of its own. */
    private static Sink mixSink() {
        int[] in = SpikeKernels.mixInput();
        int[] out = new int[SpikeKernels.INVOCATIONS];
        int[] uniforms = SpikeKernels.mixUniforms();
        return new Sink() {

            @Override
            public void dispatch(Kernel kernel, int n) {
                kernel.run(in, out, null, null, uniforms, 0, in.length, 0, 1);
            }

            @Override
            public String digest() {
                return Digests.sha256(out);
            }
        };
    }

    /** K3's ({@code sine}) or K3''s dispatches, each over the fixed pairs into an output of its own. */
    private static Sink tableSink(boolean sine) {
        int[][] pairs = SpikeKernels.opTablePairs();
        int[] out = new int[SpikeKernels.INVOCATIONS * SpikeKernels.columns(sine)
            .size()];
        return new Sink() {

            @Override
            public void dispatch(Kernel kernel, int n) {
                kernel.run(pairs[0], pairs[1], out, null, null, 0, SpikeKernels.INVOCATIONS, 0, 1);
            }

            @Override
            public String digest() {
                return Digests.sha256(out);
            }
        };
    }

    /** A one-column program's dispatches, each over K1's field, finite floats, into an output of its own. */
    private static Sink columnSink() {
        int[] in = SpikeKernels.heatField();
        int[] out = new int[SpikeKernels.INVOCATIONS];
        return new Sink() {

            @Override
            public void dispatch(Kernel kernel, int n) {
                kernel.run(in, out, null, null, null, 0, in.length, 0, 1);
            }

            @Override
            public String digest() {
                return Digests.sha256(out);
            }
        };
    }

    /** {@code b1[x] = sqrt(b0[x])} for {@code FSQRT}, or {@code b0[x] * b0[x]} for {@code FMUL}, over floats. */
    private static Program oneColumn(String name, Op column) {
        ProgramBuilder k = new ProgramBuilder(name, 1);
        int in = k.binding(Access.READONLY);
        int out = k.binding(Access.WRITEONLY);
        int v = k.local(Type.F32);
        k.assign(v, load(in, gid(0), Type.F32));
        k.store(out, gid(0), column == Op.FSQRT ? op(column, k.get(v)) : op(column, k.get(v), k.get(v)));
        return k.build();
    }

    /** Emits, scans, defines and runs the program once, and records each step's time. */
    private static Kernel cold(List<String> lines, String what, Program program, Sink first) {
        long t0 = System.nanoTime();
        byte[] bytes = Emitter.emit(program);
        long t1 = System.nanoTime();
        Kernel kernel = KernelLoader.instantiate(new KernelLoader(Emitter.className(program)).define(bytes));
        long t2 = System.nanoTime();
        first.dispatch(kernel, 0);
        long t3 = System.nanoTime();
        lines.add(
            String.format(
                Locale.ROOT,
                "cold, %s: %.3f ms to the first result (emit %.3f, scan and define %.3f, first dispatch %.3f)",
                what,
                (t3 - t0) / 1e6,
                (t1 - t0) / 1e6,
                (t2 - t1) / 1e6,
                (t3 - t2) / 1e6));
        return kernel;
    }

    /** Times the kernel's dispatches; {@code program} is null for a Java reference, which has no class of ours. */
    private static String time(String name, Kernel kernel, Sink sink, Program program) {
        long[] times = new long[TIMED];
        for (int n = 0; n < WARM_UP + TIMED; n++) {
            long t0 = System.nanoTime();
            sink.dispatch(kernel, n);
            long t = System.nanoTime() - t0;
            if (n >= WARM_UP) {
                times[n - WARM_UP] = t;
            }
        }
        Arrays.sort(times);
        double invocations = SpikeKernels.INVOCATIONS;
        return String.format(
            Locale.ROOT,
            "%-8s %10s %7s %12.3f %12.3f %12.3f",
            name,
            program == null ? "-" : Integer.toString(KernelScan.codeLength(Emitter.emit(program), "run")),
            program == null ? "-" : Integer.toString(ops(program.body)),
            times[TIMED / 2] / invocations,
            times[TIMED / 10] / invocations,
            times[TIMED * 9 / 10] / invocations);
    }

    /**
     * Times a recorded kernel as {@link #time} does, apart from it so that K1's and K2's figures keep the harness the
     * gate's runs measured, and counts what the timed dispatches allocate on this thread.
     */
    private static Timing timeRecorded(String name, Kernel kernel, Sink sink, Program program, int warmUp, int timed,
        ThreadAllocation allocation) throws ReflectiveOperationException {
        for (int n = 0; n < warmUp; n++) {
            sink.dispatch(kernel, n);
        }
        long[] times = new long[timed];
        long bytes = allocation.during(() -> {
            for (int n = 0; n < timed; n++) {
                long t0 = System.nanoTime();
                sink.dispatch(kernel, warmUp + n);
                times[n] = System.nanoTime() - t0;
            }
        });
        Arrays.sort(times);
        double invocations = SpikeKernels.INVOCATIONS;
        double median = times[timed / 2] / invocations;
        double perElement = bytes / (invocations * timed);
        String line = String.format(
            Locale.ROOT,
            "%-11s %10s %7s %12.3f %12.3f %12.3f %9.2f",
            name,
            program == null ? "-" : Integer.toString(KernelScan.codeLength(Emitter.emit(program), "run")),
            program == null ? "-" : Integer.toString(ops(program.body)),
            median,
            times[timed / 10] / invocations,
            times[timed * 9 / 10] / invocations,
            perElement);
        return new Timing(line, median, perElement);
    }

    /** The program's operations as written, once per node: loads, stores, unary and binary ops, comparisons. */
    private static int ops(List<Stmt> block) {
        int ops = 0;
        for (Stmt s : block) {
            if (s instanceof Stmt.Assign) {
                ops += ops(((Stmt.Assign) s).value);
            } else if (s instanceof Stmt.Store) {
                Stmt.Store st = (Stmt.Store) s;
                ops += 1 + ops(st.index) + ops(st.value);
            } else if (s instanceof Stmt.If) {
                Stmt.If i = (Stmt.If) s;
                Compare c = i.condition;
                ops += 1 + ops(c.left) + ops(c.right) + ops(i.then) + ops(i.orElse);
            } else if (s instanceof Stmt.For) {
                ops += ops(((Stmt.For) s).body);
            }
        }
        return ops;
    }

    private static int ops(Expr e) {
        if (e instanceof Expr.Load) {
            return 1 + ops(((Expr.Load) e).index);
        }
        if (e instanceof Expr.Unary) {
            return 1 + ops(((Expr.Unary) e).operand);
        }
        if (e instanceof Expr.Binary) {
            Expr.Binary b = (Expr.Binary) e;
            return 1 + ops(b.left) + ops(b.right);
        }
        return 0;
    }

    private static void report(List<String> lines) throws IOException {
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
