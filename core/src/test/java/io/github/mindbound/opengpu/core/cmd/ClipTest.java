package io.github.mindbound.opengpu.core.cmd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * {@link Clip}'s ranges and areas, which S8's executor draws (28 §5.1, host notes) and the validator's fill counts
 * (28 §3.5), against a per-pixel brute force written here from 28 §5.1, §5.5 and the oracle of §5.7 (28:497-501): a
 * rect covers the pixels with x ≤ px &lt; x + w and y ≤ py &lt; y + h, the sums exact, clipped to [0, W) × [0, H); a
 * {@code BLIT} writes pixel (dx + i, dy + j) for 0 ≤ i &lt; w and 0 ≤ j &lt; h when that pixel is on the surface and
 * texel (sx + i, sy + j) is inside the texture, KEY0 aside. For every case: a non-empty result's ranges are the brute
 * force's bounding box, inside the surface (and for a {@code BLIT} the texture) and filled by it; an empty result's
 * ranges are empty, x1 ≤ x0 or y1 ≤ y0; and the area is the brute force's count. The cases are seeded operands on
 * T1's, T2's and T3's surfaces (160x100, 320x200, 640x400) and small ones, with textures from 1x1 to 1024x1024, i16
 * extremes and negative or partial sources and destinations; every small operand on a 5x4 surface with a 3x2
 * texture; and pinned hand cases.
 */
class ClipTest {

    private static final long SEED = 501;
    private static final int[][] SURFACES = { { 160, 100 }, { 320, 200 }, { 640, 400 }, { 1, 1 }, { 2, 1 }, { 1, 3 },
        { 5, 4 }, { 7, 3 }, { 16, 16 } };
    private static final int[][] TEXTURES = { { 1, 1 }, { 3, 2 }, { 2, 3 }, { 7, 3 }, { 16, 16 }, { 33, 33 },
        { 1024, 1 }, { 1, 1024 }, { 1024, 1024 } };

    private final int[] out = new int[4];
    private final List<String> failures = new ArrayList<>();
    private int failed;
    private int nonEmpty;
    private int partial;

    @Test
    void pinnedCases() {
        // T1: i in [max(0, 1, -157), min(162, 34, 3)) = [1, 3), j in [max(0, 0, 7), min(62, 33, 107)) = [7, 33)
        assertBlit(-1, 0, 162, 62, 157, -7, 33, 33, 160, 100, 1, 7, 3, 33);
        // x in [0, 7), y in [5, 100): the rect runs past the bottom
        assertRect(-3, 5, 10, 300, 160, 100, 0, 5, 7, 100);
        // The texture's height bounds j, not its width; the surface's width bounds x1 exactly
        assertBlit(0, 0, 16, 16, 0, 0, 7, 3, 320, 200, 0, 0, 7, 3);
        assertRect(0, 0, 320, 10, 320, 200, 0, 0, 320, 10);
        // ValidatorAccountingTest's: source and destination clipped at once, i in [5, 16), j in [0, 5)
        assertBlit(-2, 3, 16, 16, -5, 195, 16, 16, 320, 200, 5, 0, 16, 5);
        // A rect larger than the texture: i and j in [100, 116)
        assertBlit(-100, -100, 1000, 1000, -100, -100, 16, 16, 320, 200, 100, 100, 116, 116);
        // T3: a 1024x1 texture from x 1000 at (630, 399), the surface's edge cutting i at 10
        assertBlit(1000, 0, 24, 1, 630, 399, 1024, 1, 640, 400, 0, 0, 10, 1);
        assertRect(630, 390, 20, 20, 640, 400, 630, 390, 640, 400);
        // i16 extremes, with x + w in 32-bit arithmetic (28 §5.1)
        assertRect(-100, -100, 32767, 32767, 320, 200, 0, 0, 320, 200);
        assertRect(319, 199, 32767, 32767, 320, 200, 319, 199, 320, 200);
        assertRect(-1, -1, 32767, 32767, 640, 400, 0, 0, 640, 400);
        // Source and destination at -32 700: i and j in [32 700, 32 767), texels and pixels 0-66
        assertBlit(-32700, -32700, 32767, 32767, -32700, -32700, 1024, 1024, 640, 400, 32700, 32700, 32767, 32767);
        // The destination at (-1, -1): i in [1, 641), j in [1, 401), the whole of T3
        assertBlit(0, 0, 32767, 32767, -1, -1, 1024, 1024, 640, 400, 1, 1, 641, 401);
        // Empty: x + w = -1, w < 0, w = 0, off the surface, i0 = 32 768 past i1, the source past the texture
        assertEmptyRect(-32768, -32768, 32767, 32767, 320, 200);
        assertEmptyRect(32767, 32767, -32768, -32768, 320, 200);
        assertEmptyRect(0, 0, 0, 10, 320, 200);
        assertEmptyRect(320, 0, 10, 10, 320, 200);
        assertEmptyRect(-10, 0, 10, 10, 320, 200);
        assertEmptyBlit(-32768, 32767, 32767, 32767, -32768, -32768, 16, 16, 320, 200);
        assertEmptyBlit(0, 0, 32767, 32767, -32768, -32768, 16, 16, 320, 200);
        assertEmptyBlit(16, 0, 16, 16, 0, 0, 16, 16, 320, 200);
        assertEmptyBlit(0, -16, 16, 16, 0, 0, 16, 16, 320, 200);
        assertEmptyBlit(0, 0, 16, -1, 0, 0, 16, 16, 320, 200);
        assertEmptyBlit(0, 0, 16, 16, 320, 0, 16, 16, 320, 200);
        assertNoFailures();
    }

    @Test
    void seededOperandsAgreeWithTheBruteForce() {
        SplittableRandom r = new SplittableRandom(SEED);
        int cases = 0;
        for (int[] surface : SURFACES) {
            int sw = surface[0];
            int sh = surface[1];
            int count = sw * sh >= 16000 ? 1000 : 5000;
            for (int k = 0; k < count; k++) {
                checkRect(position(r, sw), position(r, sh), length(r, sw), length(r, sh), sw, sh);
                int[] texture = TEXTURES[r.nextInt(TEXTURES.length)];
                int tw = texture[0];
                int th = texture[1];
                checkBlit(
                    position(r, tw),
                    position(r, th),
                    length(r, Math.max(sw, tw)),
                    length(r, Math.max(sh, th)),
                    position(r, sw),
                    position(r, sh),
                    tw,
                    th,
                    sw,
                    sh);
                cases += 2;
            }
        }
        System.out.printf(
            Locale.ROOT,
            "ClipTest: %d seeded cases from seed %d, %d non-empty, %d of them clipped%n",
            cases,
            SEED,
            nonEmpty,
            partial);
        assertNoFailures();
        // The cases reach the surface, and clip, often enough to mean something
        Assertions.assertTrue(nonEmpty >= cases / 20, nonEmpty + " non-empty of " + cases);
        Assertions.assertTrue(partial >= cases / 40, partial + " clipped of " + cases);
    }

    @Test
    void everySmallOperandOnA5x4SurfaceWithA3x2Texture() {
        int[] values = { -32768, -3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 32767 };
        for (int x : values) {
            for (int w : values) {
                for (int y : values) {
                    for (int h : values) {
                        checkRect(x, y, w, h, 5, 4);
                    }
                }
            }
        }
        for (int sx : values) {
            for (int dx : values) {
                for (int w : values) {
                    for (int sy : values) {
                        for (int dy : values) {
                            for (int h : values) {
                                checkBlit(sx, sy, w, h, dx, dy, 3, 2, 5, 4);
                            }
                        }
                    }
                }
            }
        }
        assertNoFailures();
    }

    // The brute force

    /** Compares Clip.rect and Clip.rectArea with the per-pixel membership of 28 §5.1; mismatches are collected. */
    private void checkRect(int x, int y, int w, int h, int sw, int sh) {
        long count = 0;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int py = 0; py < sh; py++) {
            if (py < y || py >= (long) y + h) {
                continue;
            }
            for (int px = 0; px < sw; px++) {
                if (px >= x && px < (long) x + w) {
                    count++;
                    minX = Math.min(minX, px);
                    maxX = Math.max(maxX, px);
                    minY = Math.min(minY, py);
                    maxY = Math.max(maxY, py);
                }
            }
        }
        Arrays.fill(out, 0x5A5A5A5A);
        Clip.rect(x, y, w, h, sw, sh, out);
        long area = Clip.rectArea(x, y, w, h, sw, sh);
        String problem = compare(count, area, minX, minY, maxX, maxY, sw, sh, null);
        if (problem != null) {
            fail(String.format(Locale.ROOT, "rect(%d, %d, %d, %d) on %dx%d: %s", x, y, w, h, sw, sh, problem));
        }
        tally(count, (long) w * h);
    }

    /**
     * Compares Clip.blit and Clip.blitArea with the per-pixel rule of 28 §5.5, walked over the surface's pixels;
     * mismatches are collected.
     */
    private void checkBlit(int sx, int sy, int w, int h, int dx, int dy, int tw, int th, int sw, int sh) {
        long count = 0;
        int minI = Integer.MAX_VALUE;
        int minJ = Integer.MAX_VALUE;
        int maxI = Integer.MIN_VALUE;
        int maxJ = Integer.MIN_VALUE;
        for (int py = 0; py < sh; py++) {
            long j = (long) py - dy;
            if (j < 0 || j >= h || sy + j < 0 || sy + j >= th) {
                continue;
            }
            for (int px = 0; px < sw; px++) {
                long i = (long) px - dx;
                if (i >= 0 && i < w && sx + i >= 0 && sx + i < tw) {
                    count++;
                    minI = (int) Math.min(minI, i);
                    maxI = (int) Math.max(maxI, i);
                    minJ = (int) Math.min(minJ, j);
                    maxJ = (int) Math.max(maxJ, j);
                }
            }
        }
        Arrays.fill(out, 0x5A5A5A5A);
        Clip.blit(sx, sy, w, h, dx, dy, tw, th, sw, sh, out);
        long area = Clip.blitArea(sx, sy, w, h, dx, dy, tw, th, sw, sh);
        String problem = compare(count, area, minI, minJ, maxI, maxJ, sw, sh, new int[] { sx, sy, dx, dy, tw, th });
        if (problem != null) {
            fail(
                String.format(
                    Locale.ROOT,
                    "blit(src (%d, %d) %dx%d to (%d, %d)) of %dx%d on %dx%d: %s",
                    sx,
                    sy,
                    w,
                    h,
                    dx,
                    dy,
                    tw,
                    th,
                    sw,
                    sh,
                    problem));
        }
        tally(count, (long) w * h);
    }

    /**
     * Null if out[] and the area match the brute force's count and bounding box, otherwise what differs. A blit's
     * ranges are in i and j, and blit holds its {sx, sy, dx, dy, TW, TH}; null for a rect.
     */
    private String compare(long count, long area, int minA, int minB, int maxA, int maxB, int sw, int sh, int[] blit) {
        String ranges = Arrays.toString(out);
        if (area != count) {
            return "area " + area + ", brute force " + count + ", ranges " + ranges;
        }
        if (count == 0) {
            return out[2] <= out[0] || out[3] <= out[1] ? null : "brute force empty, ranges " + ranges;
        }
        int[] box = { minA, minB, maxA + 1, maxB + 1 };
        if (!Arrays.equals(box, out)) {
            return "ranges " + ranges + ", bounding box " + Arrays.toString(box);
        }
        if ((long) (out[2] - out[0]) * (out[3] - out[1]) != count) {
            return "ranges " + ranges + " hold more than the " + count + " pixels";
        }
        // Inside the surface, and a blit's inside the texture too, as S8's draw needs (28 §5.1, host notes)
        int x = blit == null ? 0 : blit[2];
        int y = blit == null ? 0 : blit[3];
        if (x + out[0] < 0 || x + out[2] > sw || y + out[1] < 0 || y + out[3] > sh) {
            return "ranges " + ranges + " leave the surface";
        }
        if (blit != null && (blit[0] + out[0] < 0 || blit[0] + out[2] > blit[4]
            || blit[1] + out[1] < 0
            || blit[1] + out[3] > blit[5])) {
            return "ranges " + ranges + " leave the texture";
        }
        return null;
    }

    private void tally(long count, long unclipped) {
        if (count > 0) {
            nonEmpty++;
            if (count < unclipped) {
                partial++;
            }
        }
    }

    // The pinned cases

    private void assertRect(int x, int y, int w, int h, int sw, int sh, int x0, int y0, int x1, int y1) {
        Clip.rect(x, y, w, h, sw, sh, out);
        Assertions
            .assertArrayEquals(new int[] { x0, y0, x1, y1 }, out.clone(), "rect " + x + " " + y + " " + w + " " + h);
        Assertions.assertEquals((long) (x1 - x0) * (y1 - y0), Clip.rectArea(x, y, w, h, sw, sh));
        checkRect(x, y, w, h, sw, sh);
    }

    private void assertEmptyRect(int x, int y, int w, int h, int sw, int sh) {
        Clip.rect(x, y, w, h, sw, sh, out);
        Assertions.assertTrue(out[2] <= out[0] || out[3] <= out[1], Arrays.toString(out));
        Assertions.assertEquals(0, Clip.rectArea(x, y, w, h, sw, sh));
        checkRect(x, y, w, h, sw, sh);
    }

    private void assertBlit(int sx, int sy, int w, int h, int dx, int dy, int tw, int th, int sw, int sh, int i0,
        int j0, int i1, int j1) {
        Clip.blit(sx, sy, w, h, dx, dy, tw, th, sw, sh, out);
        Assertions
            .assertArrayEquals(new int[] { i0, j0, i1, j1 }, out.clone(), "blit " + sx + " " + sy + " " + w + " " + h);
        Assertions.assertEquals((long) (i1 - i0) * (j1 - j0), Clip.blitArea(sx, sy, w, h, dx, dy, tw, th, sw, sh));
        checkBlit(sx, sy, w, h, dx, dy, tw, th, sw, sh);
    }

    private void assertEmptyBlit(int sx, int sy, int w, int h, int dx, int dy, int tw, int th, int sw, int sh) {
        Clip.blit(sx, sy, w, h, dx, dy, tw, th, sw, sh, out);
        Assertions.assertTrue(out[2] <= out[0] || out[3] <= out[1], Arrays.toString(out));
        Assertions.assertEquals(0, Clip.blitArea(sx, sy, w, h, dx, dy, tw, th, sw, sh));
        checkBlit(sx, sy, w, h, dx, dy, tw, th, sw, sh);
    }

    /**
     * A seeded i16 position on an axis of this side (the surface's for x, y, dx and dy, the texture's for sx and sy):
     * an edge of the side or of i16 (25 %), any i16 (5 %), or a value from -side - 2 to side + 2, so that the op
     * starts before, inside or past the axis (70 %).
     */
    private static int position(SplittableRandom r, int side) {
        int roll = r.nextInt(100);
        if (roll < 25) {
            int[] edges = { -32768, -32767, -side - 1, -side, -2, -1, 0, 1, 2, side - 1, side, side + 1, 32766, 32767 };
            return edges[r.nextInt(edges.length)];
        }
        if (roll < 30) {
            return r.nextInt(-32768, 32768);
        }
        return r.nextInt(-side - 2, side + 3);
    }

    /**
     * A seeded i16 width or height for an axis of this side: an edge or an i16 extreme, zero and negatives included
     * (20 %), any i16 (5 %), or 1 to 2·side + 2 (75 %).
     */
    private static int length(SplittableRandom r, int side) {
        int roll = r.nextInt(100);
        if (roll < 20) {
            int[] edges = { -32768, -2, -1, 0, 1, 2, side - 1, side, side + 1, 32767 };
            return edges[r.nextInt(edges.length)];
        }
        if (roll < 25) {
            return r.nextInt(-32768, 32768);
        }
        return r.nextInt(1, 2 * side + 3);
    }

    private void fail(String problem) {
        failed++;
        if (failures.size() < 20) {
            failures.add(problem);
        }
    }

    private void assertNoFailures() {
        Assertions.assertTrue(failures.isEmpty(), () -> failed + " mismatches (seed " + SEED + "): " + failures);
    }
}
