package io.github.mindbound.opengpu.spike.emit;

import org.objectweb.asm.ClassWriter;

/**
 * {@code COMPUTE_FRAMES} with a {@code getCommonSuperClass} that loads nothing (22:177; S14 plan P4.3). ASM calls it
 * only where a frame merges two different reference types, and the emitter gives every reference local one type for
 * the whole method (the four {@code int[]} buffers, the uniforms' {@code int[]} and {@code this}), so a call is an
 * emitter bug. ASM's own would load both classes through {@code Class.forName}, in a mod through LaunchClassLoader.
 */
final class NonLoadingWriter extends ClassWriter {

    NonLoadingWriter() {
        super(ClassWriter.COMPUTE_FRAMES);
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        throw new IllegalStateException(
            "emitter bug: a frame merges " + type1 + " and " + type2 + ", and every reference local has one type");
    }
}
