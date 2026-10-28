package io.github.mindbound.opengpu.spike.load;

/** A class file the scan refuses before {@code defineClass}, with the first reason found (S14 plan P6.2). */
public final class KernelRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    KernelRefusedException(String className, String reason) {
        super("kernel class " + className + " refused: " + reason);
    }
}
