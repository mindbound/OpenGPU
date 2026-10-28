package io.github.mindbound.opengpu.spike;

import io.github.mindbound.opengpu.spike.rt.Kernel;

/**
 * K1 and K2 written by hand in Java (S14 amendments F1): the references the emitted kernels must equal bit for bit,
 * and the bench's baselines under the same harness, as {@link Kernel}s with the emitted classes' loop shape, so a gap
 * between the two is the emitter's, not the harness's. Each is javac's form of {@link SpikeKernels}' IR, in its order
 * of operations; every float store goes through {@code Float.floatToIntBits}, as the emitter's does.
 */
public final class JavaKernels {

    /** K1, 22 §2.1's heat stencil; u is {W, H, the rate's bits}. */
    public static final Kernel HEAT = new Kernel() {

        @Override
        public void run(int[] b0, int[] b1, int[] b2, int[] b3, int[] u, int x0, int x1, int y0, int y1) {
            int w = u[0];
            int h = u[1];
            float rate = Float.intBitsToFloat(u[2]);
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    if (x >= w || y >= h) {
                        continue;
                    }
                    int i = y * w + x;
                    float c = Float.intBitsToFloat(b0[i]);
                    float s = c;
                    int n = 1;
                    if (x > 0) {
                        s = s + Float.intBitsToFloat(b0[i - 1]);
                        n = n + 1;
                    }
                    if (x + 1 < w) {
                        s = s + Float.intBitsToFloat(b0[i + 1]);
                        n = n + 1;
                    }
                    if (y > 0) {
                        s = s + Float.intBitsToFloat(b0[i - w]);
                        n = n + 1;
                    }
                    if (y + 1 < h) {
                        s = s + Float.intBitsToFloat(b0[i + w]);
                        n = n + 1;
                    }
                    b1[i] = Float.floatToIntBits(c + rate * (s / (float) n - c));
                }
            }
        }
    };

    /** K2, the integer mix; u is {the seed}. */
    public static final Kernel MIX = new Kernel() {

        @Override
        public void run(int[] b0, int[] b1, int[] b2, int[] b3, int[] u, int x0, int x1, int y0, int y1) {
            int seed = u[0];
            for (int x = x0; x < x1; x++) {
                int h = b0[x];
                int acc = x ^ seed;
                for (int j = 0; j < 4; j++) {
                    int d = (h >>> (j * 8)) & 15;
                    acc = acc * 31 + idiv(h, d);
                    int e = d - 7;
                    acc = acc ^ irem(h, e);
                    h = (h << 5) | (h >>> 27);
                    h = h + (acc >> 3) + (acc << (j * 11 + 1));
                    h = ~h ^ -acc;
                }
                b1[x] = acc ^ h;
            }
        }
    };

    private JavaKernels() {}

    /** 22:134-135: {@code x / 0 = x}; {@code INT_MIN / -1 = INT_MIN} is Java's own. */
    static int idiv(int x, int y) {
        return y == 0 ? x : x / y;
    }

    /** 22:134-135: {@code x % 0 = 0}; {@code INT_MIN % -1 = 0} is Java's own. */
    static int irem(int x, int y) {
        return y == 0 ? 0 : x % y;
    }
}
