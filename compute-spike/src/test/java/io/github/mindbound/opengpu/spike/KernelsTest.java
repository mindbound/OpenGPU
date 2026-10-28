package io.github.mindbound.opengpu.spike;

import static io.github.mindbound.opengpu.spike.ir.Ir.cmp;
import static io.github.mindbound.opengpu.spike.ir.Ir.f32;
import static io.github.mindbound.opengpu.spike.ir.Ir.gid;
import static io.github.mindbound.opengpu.spike.ir.Ir.i32;
import static io.github.mindbound.opengpu.spike.ir.Ir.op;

import java.util.Arrays;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.mindbound.opengpu.spike.ir.Access;
import io.github.mindbound.opengpu.spike.ir.Cmp;
import io.github.mindbound.opengpu.spike.ir.Op;
import io.github.mindbound.opengpu.spike.ir.Program;
import io.github.mindbound.opengpu.spike.ir.ProgramBuilder;
import io.github.mindbound.opengpu.spike.ir.Type;
import io.github.mindbound.opengpu.spike.load.KernelLoader;
import io.github.mindbound.opengpu.spike.rt.Kernel;

/**
 * K1 and K2 against their Java references, bit for bit (S14 amendments F1), and the digests the build compares between
 * the Java 8 and the Java 21 leg (S14 plan P8.2: K1's 16 steps here, K3 in OpTableTest), each taken from a fresh
 * class's output; the first dispatch of a fresh class, which starts in the JVM's interpreter, against a warm one; what
 * the first dispatch and 1 000 warm ones allocate (22:179), measured as {@code :core}'s ValidatorAllocationTest
 * measures it, for K1, K2 and K3', which leaves out {@code FSIN}: {@code StrictMath.sin} allocates on JDK 21+ (S14
 * amendments I3); and locals zeroed per invocation, not per dispatch (amendments I7). 22:179's "no allocation" in the
 * emitted code rests on the scan's refusal of NEW, NEWARRAY, ANEWARRAY and MULTIANEWARRAY (ScanTest), since C2 can
 * remove an array the code makes and drops: the warm measure sees what survives compilation, such as an allocation in
 * a library call, and the first dispatch's, mostly interpreted, sees one per invocation, but a dead one per dispatch
 * passes both (S14 review tests-5; S14 plan P13's mutant M146).
 */
class KernelsTest {

    /** Dispatches before the measured ones, so these run compiled code. */
    private static final int WARM_UP = 100;
    private static final int MEASURED = 1000;
    /**
     * The first dispatch's bound: what the JVM allocates on this thread while it links a fresh class and runs its first
     * dispatch read 0 to 25 136 bytes in S14's runs, and an object per invocation is 65 536 objects, a mebibyte or
     * more.
     */
    private static final long FIRST_DISPATCH_BYTES = 256 * 1024;

    @Test
    void theHeatKernelMatchesItsJavaReferenceBitForBit() {
        int[][] emitted = SpikeKernels.heatSteps(KernelLoader.load(SpikeKernels.heat()));
        int[][] reference = SpikeKernels.heatSteps(JavaKernels.HEAT);
        for (int step = 0; step < SpikeKernels.HEAT_STEPS; step++) {
            Assertions.assertArrayEquals(reference[step], emitted[step], "step " + (step + 1));
        }
        Digests.record("k1", Digests.sha256(emitted));
    }

    @Test
    void heatInvocationsPastTheGridReturnAtItsGuard() {
        // 22:109's guard, for a range rounded up past W and H as a workgroup size would round it
        Kernel heat = KernelLoader.load(SpikeKernels.heat());
        int[] field = SpikeKernels.heatField();
        int[] exact = new int[SpikeKernels.INVOCATIONS];
        int[] over = new int[SpikeKernels.INVOCATIONS];
        int size = SpikeKernels.SIZE;
        heat.run(field, exact, null, null, SpikeKernels.heatUniforms(), 0, size, 0, size);
        heat.run(field, over, null, null, SpikeKernels.heatUniforms(), 0, size + 16, 0, size + 16);
        Assertions.assertArrayEquals(exact, over);
    }

    @Test
    void theIntegerMixMatchesItsJavaReferenceBitForBit() {
        int[] emitted = new int[SpikeKernels.INVOCATIONS];
        int[] reference = new int[SpikeKernels.INVOCATIONS];
        int[] in = SpikeKernels.mixInput();
        KernelLoader.load(SpikeKernels.mix())
            .run(in, emitted, null, null, SpikeKernels.mixUniforms(), 0, in.length, 0, 1);
        JavaKernels.MIX.run(in, reference, null, null, SpikeKernels.mixUniforms(), 0, in.length, 0, 1);
        Assertions.assertArrayEquals(reference, emitted);
        Digests.record("k2", Digests.sha256(emitted));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "K1", "K2", "K3'" })
    void dispatchesAllocateNothingAndWarmOnesMatchTheFirst(String name) throws ReflectiveOperationException {
        final Program program;
        final int[] b0;
        final int[] b1;
        final int[] b2;
        final int[] uniforms;
        final int x1;
        final int y1;
        if (name.equals("K1")) {
            program = SpikeKernels.heat();
            b0 = SpikeKernels.heatField();
            b1 = new int[SpikeKernels.INVOCATIONS];
            b2 = null;
            uniforms = SpikeKernels.heatUniforms();
            x1 = SpikeKernels.SIZE;
            y1 = SpikeKernels.SIZE;
        } else if (name.equals("K2")) {
            program = SpikeKernels.mix();
            b0 = SpikeKernels.mixInput();
            b1 = new int[SpikeKernels.INVOCATIONS];
            b2 = null;
            uniforms = SpikeKernels.mixUniforms();
            x1 = SpikeKernels.INVOCATIONS;
            y1 = 1;
        } else {
            program = SpikeKernels.opTable(false);
            int[][] pairs = SpikeKernels.opTablePairs();
            b0 = pairs[0];
            b1 = pairs[1];
            b2 = new int[SpikeKernels.INVOCATIONS * SpikeKernels.columns(false)
                .size()];
            uniforms = null;
            x1 = SpikeKernels.INVOCATIONS;
            y1 = 1;
        }
        // Each kernel reads b0 (and K3' b1) and writes its own output, so every dispatch computes the same words
        int[] out = b2 != null ? b2 : b1;
        Kernel kernel = KernelLoader.load(program);
        ThreadAllocation counter = new ThreadAllocation();
        long firstBytes = counter.during(() -> kernel.run(b0, b1, b2, null, uniforms, 0, x1, 0, y1));
        int[] first = out.clone();
        for (int i = 0; i < WARM_UP; i++) {
            kernel.run(b0, b1, b2, null, uniforms, 0, x1, 0, y1);
        }
        Arrays.fill(out, 0);
        long bytes = counter.during(() -> {
            for (int i = 0; i < MEASURED; i++) {
                kernel.run(b0, b1, b2, null, uniforms, 0, x1, 0, y1);
            }
        });
        Assertions.assertArrayEquals(first, out, name + "'s warm dispatch differs from its first");
        System.out.println("KernelsTest: the first dispatch of " + name + " allocates " + firstBytes + " B");
        System.out.println(
            "KernelsTest: " + MEASURED
                + " warm dispatches of "
                + name
                + " allocate "
                + bytes
                + " B (the counter's own subtracted)");
        Assertions.assertTrue(
            firstBytes < FIRST_DISPATCH_BYTES,
            () -> "the first dispatch of " + name + " allocated " + firstBytes + " B, not under 256 KiB");
        // An object a dispatch that C2 keeps would be 16 000 bytes or more, as a dead new int[x0] is on JDK 8
        Assertions.assertTrue(
            bytes < 1024,
            () -> MEASURED + " warm dispatches of " + name + " allocated " + bytes + " B, over 1 023");
    }

    @Test
    void aLocalSetOnOneArmIsZeroAgainInTheNextInvocation() {
        // Zero initialization is per invocation (22:44; 27:126): x = 1 must not see x = 0's 5 and 2.5
        ProgramBuilder k = new ProgramBuilder("OneArm", 1);
        int out = k.binding(Access.WRITEONLY);
        int t = k.local(Type.I32);
        int f = k.local(Type.F32);
        k.ifThen(cmp(Cmp.EQ, gid(0), i32(0)), () -> {
            k.assign(t, i32(5));
            k.assign(f, f32(2.5f));
        });
        k.store(out, op(Op.IMUL, gid(0), i32(2)), k.get(t));
        k.store(out, op(Op.IADD, op(Op.IMUL, gid(0), i32(2)), i32(1)), k.get(f));
        int[] words = new int[4];
        KernelLoader.load(k.build())
            .run(words, null, null, null, null, 0, 2, 0, 1);
        Assertions.assertArrayEquals(new int[] { 5, Float.floatToIntBits(2.5f), 0, 0 }, words);
    }
}
