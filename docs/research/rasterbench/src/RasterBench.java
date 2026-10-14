import java.util.Random;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

/**
 * Minimal edge-function (Pineda) triangle rasterizer microbenchmark.
 * 28.4 fixed-point vertex coords, top-left fill rule, incremental edge stepping,
 * float barycentrics, depth test, three shading modes:
 *   0 = flat colour, 1 = perspective-correct nearest-sampled 256x256 texture, 2 = Gouraud RGB.
 * Reports pixels/sec and triangles/sec. Single file, no dependencies, Java 8 source level.
 */
public class RasterBench {
    static int W = 320, H = 200;
    static final int SUB = 4, ONE = 1 << SUB;

    // SoA vertex data: 3 vertices per triangle
    static float[] vx, vy, vz, viw, vuw, vvw, vr, vg, vb;
    static int[] vc;
    static final int[] tex = new int[256 * 256];

    static int orient2d(int ax, int ay, int bx, int by, int px, int py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    static boolean isTopLeft(int ax, int ay, int bx, int by) {
        // y-down coordinates: "top" = horizontal edge going right, "left" = edge going up (ryg convention mirrored)
        return (ay == by && bx > ax) || (by < ay);
    }

    /** Rasterize triangle t into fb/zb restricted to the tile [tx0,tx1)x[ty0,ty1). Returns shaded pixel count. */
    static int rasterTri(int t, int mode, int[] fb, float[] zb, int tx0, int ty0, int tx1, int ty1) {
        int i0 = t * 3, i1 = i0 + 1, i2 = i0 + 2;
        int X0 = Math.round(vx[i0] * ONE), Y0 = Math.round(vy[i0] * ONE);
        int X1 = Math.round(vx[i1] * ONE), Y1 = Math.round(vy[i1] * ONE);
        int X2 = Math.round(vx[i2] * ONE), Y2 = Math.round(vy[i2] * ONE);
        int area = orient2d(X0, Y0, X1, Y1, X2, Y2);
        if (area == 0) return 0;
        if (area < 0) { int tmp = X1; X1 = X2; X2 = tmp; tmp = Y1; Y1 = Y2; Y2 = tmp; tmp = i1; i1 = i2; i2 = tmp; area = -area; }

        int minx = Math.max(tx0, Math.min(X0, Math.min(X1, X2)) >> SUB);
        int maxx = Math.min(tx1 - 1, (Math.max(X0, Math.max(X1, X2)) + ONE - 1) >> SUB);
        int miny = Math.max(ty0, Math.min(Y0, Math.min(Y1, Y2)) >> SUB);
        int maxy = Math.min(ty1 - 1, (Math.max(Y0, Math.max(Y1, Y2)) + ONE - 1) >> SUB);
        if (minx > maxx || miny > maxy) return 0;

        int bias0 = isTopLeft(X1, Y1, X2, Y2) ? 0 : -1;
        int bias1 = isTopLeft(X2, Y2, X0, Y0) ? 0 : -1;
        int bias2 = isTopLeft(X0, Y0, X1, Y1) ? 0 : -1;
        int px = (minx << SUB) + ONE / 2, py = (miny << SUB) + ONE / 2;
        int w0row = orient2d(X1, Y1, X2, Y2, px, py) + bias0;
        int w1row = orient2d(X2, Y2, X0, Y0, px, py) + bias1;
        int w2row = orient2d(X0, Y0, X1, Y1, px, py) + bias2;
        int w0dx = -(Y2 - Y1) * ONE, w0dy = (X2 - X1) * ONE;
        int w1dx = -(Y0 - Y2) * ONE, w1dy = (X0 - X2) * ONE;
        int w2dx = -(Y1 - Y0) * ONE, w2dy = (X1 - X0) * ONE;
        float invArea = 1f / area;

        float z0 = vz[i0], z1 = vz[i1], z2 = vz[i2];
        float iw0 = viw[i0], iw1 = viw[i1], iw2 = viw[i2];
        float uw0 = vuw[i0], uw1 = vuw[i1], uw2 = vuw[i2];
        float vw0 = vvw[i0], vw1 = vvw[i1], vw2 = vvw[i2];
        float r0 = vr[i0], r1 = vr[i1], r2 = vr[i2];
        float g0 = vg[i0], g1 = vg[i1], g2 = vg[i2];
        float b0c = vb[i0], b1c = vb[i1], b2c = vb[i2];
        int color = vc[i0];
        int count = 0;
        for (int y = miny; y <= maxy; y++) {
            int w0 = w0row, w1 = w1row, w2 = w2row;
            int rowBase = y * W;
            for (int x = minx; x <= maxx; x++) {
                if ((w0 | w1 | w2) >= 0) {
                    float l0 = (w0 - bias0) * invArea, l1 = (w1 - bias1) * invArea, l2 = 1f - l0 - l1;
                    float z = l0 * z0 + l1 * z1 + l2 * z2;
                    int idx = rowBase + x;
                    if (z < zb[idx]) {
                        zb[idx] = z;
                        if (mode == 0) {
                            fb[idx] = color;
                        } else if (mode == 1) {
                            float iw = l0 * iw0 + l1 * iw1 + l2 * iw2;
                            float rw = 1f / iw;
                            float u = (l0 * uw0 + l1 * uw1 + l2 * uw2) * rw;
                            float v = (l0 * vw0 + l1 * vw1 + l2 * vw2) * rw;
                            int tu = ((int) (u * 256f)) & 255, tv = ((int) (v * 256f)) & 255;
                            fb[idx] = tex[(tv << 8) | tu];
                        } else {
                            int r = (int) (l0 * r0 + l1 * r1 + l2 * r2);
                            int g = (int) (l0 * g0 + l1 * g1 + l2 * g2);
                            int b = (int) (l0 * b0c + l1 * b1c + l2 * b2c);
                            fb[idx] = 0xFF000000 | (r << 16) | (g << 8) | b;
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

    static void alloc(int n) {
        vx = new float[n * 3]; vy = new float[n * 3]; vz = new float[n * 3]; viw = new float[n * 3];
        vuw = new float[n * 3]; vvw = new float[n * 3]; vr = new float[n * 3]; vg = new float[n * 3]; vb = new float[n * 3];
        vc = new int[n * 3];
    }

    static void setVert(int i, float x, float y, float z, float w, float u, float v, int c, Random rnd) {
        vx[i] = x; vy[i] = y; vz[i] = z; viw[i] = 1f / w; vuw[i] = u / w; vvw[i] = v / w; vc[i] = c;
        vr[i] = rnd.nextInt(256); vg[i] = rnd.nextInt(256); vb[i] = rnd.nextInt(256);
    }

    /** Two triangles covering the whole framebuffer, mild perspective. */
    static void sceneFullscreen(Random rnd) {
        alloc(2);
        setVert(0, 0, 0, 0.2f, 1f, 0, 0, 0xFF00FF00, rnd); setVert(1, W, 0, 0.3f, 2f, 1, 0, 0xFF00FF00, rnd); setVert(2, 0, H, 0.4f, 3f, 0, 1, 0xFF00FF00, rnd);
        setVert(3, W, 0, 0.3f, 2f, 1, 0, 0xFFFF0000, rnd); setVert(4, W, H, 0.5f, 4f, 1, 1, 0xFFFF0000, rnd); setVert(5, 0, H, 0.4f, 3f, 0, 1, 0xFFFF0000, rnd);
    }

    /** n small random triangles of ~side px. */
    static void sceneSmall(Random rnd, int n, float side) {
        alloc(n);
        for (int t = 0; t < n; t++) {
            float cx = rnd.nextFloat() * (W - side) , cy = rnd.nextFloat() * (H - side);
            for (int k = 0; k < 3; k++) {
                float x = cx + rnd.nextFloat() * side, y = cy + rnd.nextFloat() * side;
                setVert(t * 3 + k, x, y, rnd.nextFloat(), 1f + rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), 0xFF000000 | rnd.nextInt(0xFFFFFF), rnd);
            }
        }
    }

    static long frameST(int mode, int[] fb, float[] zb) {
        java.util.Arrays.fill(zb, Float.MAX_VALUE);
        long px = 0;
        int n = vx.length / 3;
        for (int t = 0; t < n; t++) px += rasterTri(t, mode, fb, zb, 0, 0, W, H);
        return px;
    }

    static long frameMT(int mode, int[] fb, float[] zb, ForkJoinPool pool, int tile) {
        java.util.Arrays.fill(zb, Float.MAX_VALUE);
        final int tilesX = (W + tile - 1) / tile, tilesY = (H + tile - 1) / tile;
        final int n = vx.length / 3;
        final AtomicLong total = new AtomicLong();
        try {
            pool.submit(() -> IntStream.range(0, tilesX * tilesY).parallel().forEach(ti -> {
                int tx0 = (ti % tilesX) * tile, ty0 = (ti / tilesX) * tile;
                int tx1 = Math.min(W, tx0 + tile), ty1 = Math.min(H, ty0 + tile);
                long c = 0;
                for (int t = 0; t < n; t++) c += rasterTri(t, mode, fb, zb, tx0, ty0, tx1, ty1);
                total.addAndGet(c);
            })).get();
        } catch (Exception e) { throw new RuntimeException(e); }
        return total.get();
    }

    interface Frame { long run(); }

    static void bench(String name, Frame f, double warmSec, double measSec) {
        long end = System.nanoTime() + (long) (warmSec * 1e9);
        while (System.nanoTime() < end) f.run();
        long px = 0, frames = 0, t0 = System.nanoTime();
        end = t0 + (long) (measSec * 1e9);
        while (System.nanoTime() < end) { px += f.run(); frames++; }
        double sec = (System.nanoTime() - t0) / 1e9;
        int tris = vx.length / 3;
        System.out.printf("%-34s %8.1f Mpix/s %8.2f Mtri/s %7.1f frames/s%n", name, px / sec / 1e6, frames * (double) tris / sec / 1e6, frames / sec);
    }

    public static void main(String[] args) {
        if (args.length >= 2) { W = Integer.parseInt(args[0]); H = Integer.parseInt(args[1]); }
        int maxThreads = args.length >= 3 ? Integer.parseInt(args[2]) : 8;
        Random rnd = new Random(42);
        for (int i = 0; i < tex.length; i++) tex[i] = rnd.nextInt();
        final int[] fb = new int[W * H];
        final float[] zb = new float[W * H];
        System.out.println("JVM " + System.getProperty("java.version") + " " + System.getProperty("java.vm.name") + "  fb=" + W + "x" + H + "  cores=" + Runtime.getRuntime().availableProcessors());

        sceneFullscreen(rnd);
        bench("fullscreen flat+z ST", () -> frameST(0, fb, zb), 2, 3);
        bench("fullscreen gouraud+z ST", () -> frameST(2, fb, zb), 2, 3);
        bench("fullscreen tex(persp,nearest)+z ST", () -> frameST(1, fb, zb), 2, 3);
        long check = 0; for (int v : fb) check += v; System.out.println("  (checksum " + check + ")");

        sceneSmall(rnd, 20000, 8f);
        bench("20k small tris (~8px) flat ST", () -> frameST(0, fb, zb), 2, 3);
        bench("20k small tris (~8px) tex ST", () -> frameST(1, fb, zb), 2, 3);
        sceneSmall(rnd, 2000, 32f);
        bench("2k tris (~32px) tex ST", () -> frameST(1, fb, zb), 2, 3);

        sceneFullscreen(rnd);
        for (int th = 1; th <= maxThreads; th *= 2) {
            final ForkJoinPool pool = new ForkJoinPool(th);
            final int threads = th;
            bench("fullscreen tex MT x" + threads + " tiles32", () -> frameMT(1, fb, zb, pool, 32), 2, 3);
            pool.shutdown();
        }
    }
}
