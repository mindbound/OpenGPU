package io.github.mindbound.opengpu.spike;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import io.github.mindbound.opengpu.spike.emit.Emitter;
import io.github.mindbound.opengpu.spike.ir.Program;

/**
 * ASM's own verifier over the emitted classes (22:178; S14 plan P6.3): {@code CheckClassAdapter.verify} with data-flow
 * checking, through this test's loader, which sees {@link io.github.mindbound.opengpu.spike.rt.Kernel}. It runs on the
 * unrelocated asm-util 9.10.1 of testRuntimeClasspath, which stays test-only (09:77, 22:197).
 */
class CheckClassTest {

    @Test
    void everyEmittedClassPassesCheckClassAdapterWithDataFlow() {
        List<Program> programs = Arrays.asList(
            SpikeKernels.heat(),
            SpikeKernels.mix(),
            SpikeKernels.opTable(true),
            SpikeKernels.opTable(false),
            EmitterRulesTest.sized(600),
            EmitterRulesTest.outOfBounds(),
            EmitterRulesTest.loopReturns(),
            EmitterRulesTest.bothReturn(),
            EmitterRulesTest.afterReturn());
        List<String> problems = new ArrayList<>();
        for (Program program : programs) {
            StringWriter out = new StringWriter();
            CheckClassAdapter.verify(
                new ClassReader(Emitter.emit(program)),
                CheckClassTest.class.getClassLoader(),
                false,
                new PrintWriter(out));
            if (out.getBuffer()
                .length() != 0) {
                problems.add(program.name + ":\n" + out);
            }
        }
        Assertions.assertEquals(new ArrayList<String>(), problems);
    }
}
