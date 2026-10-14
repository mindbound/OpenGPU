import java.util.Random;

/**
 * Measures the cost of invoking a per-pixel shader through an interface call site
 * (monomorphic vs megamorphic) versus the shader body inlined in the rasterizer loop.
 * Fullscreen two-triangle scene, perspective-correct nearest texturing, depth test.
 * Usage: java ShaderBench mono|mega
 */
public class ShaderBench {
    static final int W = 320, H = 200, SUB = 4, ONE = 1 << SUB;
    static final int[] tex = new int[256 * 256];
    static final int[] fb = new int[W * H];
    static final float[] zb = new float[W * H];
    // attributes per vertex: {z, 1/w, u/w, v/w, r, g, b}
    static final float[][] attr = new float[6][7];
    static final float[] X = new float[6], Y = new float[6];

    interface PixelShader { int shade(float l0, float l1, float l2, float[] a0, float[] a1, float[] a2); }

    static final class Flat implements PixelShader {
        public int shade(float l0, float l1, float l2, float[] a0, float[] a1, float[] a2) { return 0xFF00FF00; }
    }
    static final class Gouraud implements PixelShader {
        public int shade(float l0, float l1, float l2, float[] a0, float[] a1, float[] a2) {
            int r = (int) (l0 * a0[4] + l1 * a1[4] + l2 * a2[4]);
            int g = (int) (l0 * a0[5] + l1 * a1[5] + l2 * a2[5]);
            int b = (int) (l0 * a0[6] + l1 * a1[6] + l2 * a2[6]);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }
    static final class Tex implements PixelShader {
        public int shade(float l0, float l1, float l2, float[] a0, float[] a1, float[] a2) {
            float iw = l0 * a0[1] + l1 * a1[1] + l2 * a2[1];
            float rw = 1f / iw;
            float u = (l0 * a0[2] + l1 * a1[2] + l2 * a2[2]) * rw;
            float v = (l0 * a0[3] + l1 * a1[3] + l2 * a2[3]) * rw;
            int tu = ((int) (u * 256f)) & 255, tv = ((int) (v * 256f)) & 255;
            return tex[(tv << 8) | tu];
        }
    }

    static int orient2d(int ax, int ay, int bx, int by, int px, int py) { return (bx - ax) * (py - ay) - (by - ay) * (px - ax); }
    static boolean isTopLeft(int ax, int ay, int bx, int by) { return (ay == by && bx > ax) || (by < ay); }

    /** shader == null -> inlined Tex body. */
    static int rasterTri(int i0, int i1, int i2, PixelShader shader) {
        int X0 = Math.round(X[i0] * ONE), Y0 = Math.round(Y[i0] * ONE);
        int X1 = Math.round(X[i1] * ONE), Y1 = Math.round(Y[i1] * ONE);
        int X2 = Math.round(X[i2] * ONE), Y2 = Math.round(Y[i2] * ONE);
        int area = orient2d(X0, Y0, X1, Y1, X2, Y2);
        if (area == 0) return 0;
        if (area < 0) { int t = X1; X1 = X2; X2 = t; t = Y1; Y1 = Y2; Y2 = t; t = i1; i1 = i2; i2 = t; area = -area; }
        int minx = Math.max(0, Math.min(X0, Math.min(X1, X2)) >> SUB), maxx = Math.min(W - 1, (Math.max(X0, Math.max(X1, X2)) + ONE - 1) >> SUB);
        int miny = Math.max(0, Math.min(Y0, Math.min(Y1, Y2)) >> SUB), maxy = Math.min(H - 1, (Math.max(Y0, Math.max(Y1, Y2)) + ONE - 1) >> SUB);
        int bias0 = isTopLeft(X1, Y1, X2, Y2) ? 0 : -1, bias1 = isTopLeft(X2, Y2, X0, Y0) ? 0 : -1, bias2 = isTopLeft(X0, Y0, X1, Y1) ? 0 : -1;
        int px = (minx << SUB) + ONE / 2, py = (miny << SUB) + ONE / 2;
        int w0row = orient2d(X1, Y1, X2, Y2, px, py) + bias0, w1row = orient2d(X2, Y2, X0, Y0, px, py) + bias1, w2row = orient2d(X0, Y0, X1, Y1, px, py) + bias2;
        int w0dx = -(Y2 - Y1) * ONE, w0dy = (X2 - X1) * ONE, w1dx = -(Y0 - Y2) * ONE, w1dy = (X0 - X2) * ONE, w2dx = -(Y1 - Y0) * ONE, w2dy = (X1 - X0) * ONE;
        float invArea = 1f / area;
        float[] a0 = attr[i0], a1 = attr[i1], a2 = attr[i2];
        float z0 = a0[0], z1 = a1[0], z2 = a2[0];
        int count = 0;
        for (int y = miny; y <= maxy; y++) {
            int w0 = w0row, w1 = w1row, w2 = w2row, rowBase = y * W;
            for (int x = minx; x <= maxx; x++) {
                if ((w0 | w1 | w2) >= 0) {
                    float l0 = (w0 - bias0) * invArea, l1 = (w1 - bias1) * invArea, l2 = 1f - l0 - l1;
                    float z = l0 * z0 + l1 * z1 + l2 * z2;
                    int idx = rowBase + x;
                    if (z < zb[idx]) {
                        zb[idx] = z;
                        if (shader == null) {
                            float iw = l0 * a0[1] + l1 * a1[1] + l2 * a2[1];
                            float rw = 1f / iw;
                            float u = (l0 * a0[2] + l1 * a1[2] + l2 * a2[2]) * rw;
                            float v = (l0 * a0[3] + l1 * a1[3] + l2 * a2[3]) * rw;
                            int tu = ((int) (u * 256f)) & 255, tv = ((int) (v * 256f)) & 255;
                            fb[idx] = tex[(tv << 8) | tu];
                        } else {
                            fb[idx] = shader.shade(l0, l1, l2, a0, a1, a2);
                        }
                        count++;
                    }
                }
                w0 += w0dx; w1 += w1dx; w2 += w2dx;
            }
            w0row += w0dy; w1row += w1dy; w2row += w2dy;
        }
        return count;
    }

    static long frame(PixelShader s) {
        java.util.Arrays.fill(zb, Float.MAX_VALUE);
        return rasterTri(0, 1, 2, s) + rasterTri(3, 4, 5, s);
    }

    static void setVert(int i, float x, float y, float z, float w, float u, float v, Random rnd) {
        X[i] = x; Y[i] = y; attr[i][0] = z; attr[i][1] = 1f / w; attr[i][2] = u / w; attr[i][3] = v / w;
        attr[i][4] = rnd.nextInt(256); attr[i][5] = rnd.nextInt(256); attr[i][6] = rnd.nextInt(256);
    }

    static void measure(String name, PixelShader s, double sec) {
        long px = 0, t0 = System.nanoTime(), end = t0 + (long) (sec * 1e9);
        while (System.nanoTime() < end) px += frame(s);
        double dt = (System.nanoTime() - t0) / 1e9;
        System.out.printf("%-44s %8.1f Mpix/s%n", name, px / dt / 1e6);
    }

    public static void main(String[] args) {
        boolean mega = args.length > 0 && args[0].equals("mega");
        Random rnd = new Random(42);
        for (int i = 0; i < tex.length; i++) tex[i] = rnd.nextInt();
        setVert(0, 0, 0, 0.2f, 1f, 0, 0, rnd); setVert(1, W, 0, 0.3f, 2f, 1, 0, rnd); setVert(2, 0, H, 0.4f, 3f, 0, 1, rnd);
        setVert(3, W, 0, 0.3f, 2f, 1, 0, rnd); setVert(4, W, H, 0.5f, 4f, 1, 1, rnd); setVert(5, 0, H, 0.4f, 3f, 0, 1, rnd);
        System.out.println("JVM " + System.getProperty("java.version") + "  mode=" + (mega ? "megamorphic" : "monomorphic"));
        PixelShader tex = new Tex(), flat = new Flat(), gouraud = new Gouraud();
        // warm-up
        for (int k = 0; k < 3; k++) {
            measure("  warm inlined", null, 1.0);
            if (mega) { measure("  warm flat via interface", flat, 1.0); measure("  warm gouraud via interface", gouraud, 1.0); }
            measure("  warm tex via interface", tex, 1.0);
        }
        measure("inlined tex (baseline)", null, 3.0);
        measure("tex via interface call (" + (mega ? "megamorphic site)" : "monomorphic site)"), tex, 3.0);
        if (mega) measure("flat via interface call (megamorphic site)", flat, 3.0);
    }
}
