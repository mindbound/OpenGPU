package io.github.mindbound.opengpu.spike.rt;

/**
 * The one interface every emitted kernel class implements (S14 plan P4.4): four buffers of fixed arity and the
 * uniforms as parameters, so the generated code needs no {@code AALOAD} and no field; 22:179's per-thread context and
 * the full binding layout are M3's. A buffer or the uniforms are {@code int[]}s of 32-bit words, a float as its bits.
 * The type is public, and so is its package, because each kernel class lives in a child loader in a package of its own
 * ({@code io.github.mindbound.opengpu.spike.gen}) and resolves this interface through the loader's parent.
 */
public interface Kernel {

    /**
     * Runs the program once per invocation of the grid range {@code [x0, x1) x [y0, y1)}, y outer and x inner, inside
     * this one call, so the call site inside stays monomorphic (22:179); a 1D program's range is {@code [x0, x1)}, and
     * it ignores {@code y0} and {@code y1}. A buffer the program does not use may be null.
     */
    void run(int[] b0, int[] b1, int[] b2, int[] b3, int[] u, int x0, int x1, int y0, int y1);
}
