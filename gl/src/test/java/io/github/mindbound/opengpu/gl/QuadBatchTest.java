package io.github.mindbound.opengpu.gl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.HandleTypes;
import io.github.mindbound.opengpu.core.colour.Colours;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.font.GlyphTable;

/**
 * {@link OpQuads} and {@link QuadBatch} without GL (S8 plan §3.6, §4.1): each op's quads against 28:494-506's ranges
 * written out here from 28 §5.3-§5.6 (min and max of the operands, not {@code core.cmd.Clip}), on 28 §11.1's edge
 * values on every axis and both formats; the offsets that map each quad's pixels onto the right texels; offsets inside
 * i16 over 10 000 seeded blits and cells; {@code TEXT}'s cells, its stop at the right edge, its skip of records off the
 * surface and the missing glyph's last cell; and the batch's records, written six times in EXPANDED and once in
 * INSTANCED, flushed at a program change, a texture change and a full buffer.
 */
class QuadBatchTest {

    private static final long SEED = 808;
    /** 28 §11.1's edge values, with W - 1 and W filled in per surface. */
    private static final int[] EDGES = { -32768, -1, 0, 1, -2, -3, 32767 };
    private static final SurfaceDesc INDEX8 = new SurfaceDesc(0, 17, 13, Formats.INDEX8);
    private static final SurfaceDesc RGB565 = new SurfaceDesc(0, 9, 7, Formats.RGB565);
    private static final int TEX = Handles.pack(HandleTypes.TEXTURE, 5, 9);

    private final Recorder recorder = new Recorder();

    @Test
    void clearIsOneQuadOverTheSurface() {
        for (SurfaceDesc surface : new SurfaceDesc[] { INDEX8, RGB565 }) {
            recorder.quads.clear();
            int colour = surface.format == Formats.INDEX8 ? 200 : 0xFF123456;
            ops(surface, 1, 1).clear(colour);
            Assertions.assertEquals(1, recorder.quads.size());
            int[] q = recorder.quads.get(0);
            Assertions.assertArrayEquals(
                new int[] { Shaders.FILL, OpQuads.NO_TEXTURE, 0, 0, surface.width, surface.height, 0, 0,
                    Colours.sv(surface.format, colour), 0, 0, 0 },
                q);
        }
    }

    @Test
    void fillRectsAreTheirClippedRangesOnEveryEdge() {
        for (SurfaceDesc surface : new SurfaceDesc[] { INDEX8, RGB565 }) {
            int[] edges = edges(surface.width);
            int[] edgesY = edges(surface.height);
            int colour = surface.format == Formats.INDEX8 ? 255 : 0xFFFFFFFF;
            OpQuads ops = ops(surface, 1, 1);
            int drawn = 0;
            for (int x : edges) {
                for (int y : edgesY) {
                    for (int w : edges) {
                        for (int h : edgesY) {
                            recorder.quads.clear();
                            ops.fillRect(x, y, w, h, colour);
                            // 28:499: max(x,0) <= px < min(x+w,W), in 32-bit arithmetic
                            int x0 = Math.max(x, 0);
                            int x1 = Math.min(x + w, surface.width);
                            int y0 = Math.max(y, 0);
                            int y1 = Math.min(y + h, surface.height);
                            String what = surface + " fill " + x + "," + y + " " + w + "x" + h;
                            if (x1 <= x0 || y1 <= y0) {
                                Assertions.assertEquals(0, recorder.quads.size(), what);
                            } else {
                                drawn++;
                                Assertions.assertEquals(1, recorder.quads.size(), what);
                                Assertions.assertArrayEquals(
                                    new int[] { Shaders.FILL, OpQuads.NO_TEXTURE, x0, y0, x1, y1, 0, 0,
                                        Colours.sv(surface.format, colour), 0, 0, 0 },
                                    recorder.quads.get(0),
                                    what);
                            }
                        }
                    }
                }
            }
            Assertions.assertTrue(drawn > 100, "only " + drawn + " fills drew");
        }
    }

    @Test
    void blitsAreTheirClippedRangesAndFetchTheRightTexels() {
        int[][] textures = { { 1, 1 }, { 3, 2 }, { 5, 7 }, { 1024, 1 } };
        for (SurfaceDesc surface : new SurfaceDesc[] { INDEX8, RGB565 }) {
            int expectedProgram = surface.format == Formats.INDEX8 ? Shaders.BLIT_INDEX8 : Shaders.BLIT_RGB565;
            int[] edges = edges(surface.width);
            for (int[] t : textures) {
                OpQuads ops = ops(surface, t[0], t[1]);
                int drawn = 0;
                for (int sx : edges) {
                    for (int sy : edges) {
                        for (int w : edges) {
                            for (int h : new int[] { -1, 0, 1, 2, 32767 }) {
                                for (int dx : edges) {
                                    for (int dy : new int[] { -32768, -1, 0, 1, surface.height - 1, surface.height }) {
                                        recorder.quads.clear();
                                        ops.blit(TEX, sx, sy, w, h, dx, dy, 0xFF);
                                        // 28:500: i0 = max(0,-sx,-dx), i1 = min(w, TW-sx, W-dx); likewise j
                                        int i0 = Math.max(0, Math.max(-sx, -dx));
                                        int i1 = Math.min(w, Math.min(t[0] - sx, surface.width - dx));
                                        int j0 = Math.max(0, Math.max(-sy, -dy));
                                        int j1 = Math.min(h, Math.min(t[1] - sy, surface.height - dy));
                                        if (i1 <= i0 || j1 <= j0) {
                                            Assertions.assertEquals(0, recorder.quads.size());
                                            continue;
                                        }
                                        drawn++;
                                        Assertions.assertEquals(1, recorder.quads.size());
                                        int[] q = recorder.quads.get(0);
                                        Assertions.assertArrayEquals(
                                            new int[] { expectedProgram, TEX, dx + i0, dy + j0, dx + i1, dy + j1,
                                                sx - dx, sy - dy, 1, 0, 0, 0 },
                                            q);
                                        // Every corner pixel fetches a texel inside the texture (GL33:4324-4349)
                                        assertInside(q[2] + q[6], q[3] + q[7], t);
                                        assertInside(q[4] - 1 + q[6], q[5] - 1 + q[7], t);
                                        Assertions.assertTrue(q[2] >= 0 && q[4] <= surface.width);
                                        Assertions.assertTrue(q[3] >= 0 && q[5] <= surface.height);
                                    }
                                }
                            }
                        }
                    }
                }
                Assertions.assertTrue(drawn > 50, "only " + drawn + " blits drew");
            }
        }
    }

    @Test
    void blitFlagsPassKey0Only() {
        OpQuads ops = ops(INDEX8, 4, 4);
        ops.blit(TEX, 0, 0, 4, 4, 0, 0, 0);
        ops.blit(TEX, 0, 0, 4, 4, 0, 0, 1);
        Assertions.assertEquals(0, recorder.quads.get(0)[8]);
        Assertions.assertEquals(1, recorder.quads.get(1)[8]);
    }

    @Test
    void textCellsAreClippedAndMappedOntoTheirAtlasCells() {
        for (SurfaceDesc surface : new SurfaceDesc[] { INDEX8, RGB565 }) {
            GlyphAtlas atlas = new GlyphAtlas(SyntheticGlyphs.TABLE);
            OpQuads ops = ops(surface, 1, 1);
            int fg = surface.format == Formats.INDEX8 ? 15 : 0xFFFFFF00;
            int bg = surface.format == Formats.INDEX8 ? 3 : 0xFF000080;
            String s = "Abé─�x";
            int[] codepoints = s.codePoints()
                .toArray();
            for (int x : edges(surface.width)) {
                for (int y : edges(surface.height)) {
                    for (int flags : new int[] { 0, 1 }) {
                        recorder.quads.clear();
                        text(ops, x, y, fg, bg, flags, s);
                        List<int[]> expected = new ArrayList<>();
                        if (y < surface.height && y + 13 > 0) {
                            for (int k = 0; k < codepoints.length; k++) {
                                int x0 = x + 6 * k;
                                if (x0 >= surface.width) {
                                    break;
                                }
                                if (x0 + 6 <= 0) {
                                    continue;
                                }
                                int cell = atlas.cell(SyntheticGlyphs.TABLE.indexOf(codepoints[k]));
                                expected.add(
                                    new int[] { Shaders.TEXT, OpQuads.ATLAS, Math.max(x0, 0), Math.max(y, 0),
                                        Math.min(x0 + 6, surface.width), Math.min(y + 13, surface.height),
                                        atlas.cellX(cell) - x0, atlas.cellY(cell) - y, Colours.sv(surface.format, fg),
                                        Colours.sv(surface.format, bg), flags, 0 });
                            }
                        }
                        Assertions.assertEquals(
                            toString(expected),
                            toString(recorder.quads),
                            surface + " TEXT at " + x + "," + y);
                    }
                }
            }
        }
    }

    @Test
    void textStopsAtTheRightEdgeAndSkipsRecordsOffTheSurface() {
        OpQuads ops = ops(INDEX8, 1, 1);
        // 1 024 codepoints from x 0 on a 17-wide surface: cells at 0, 6 and 12 only
        StringBuilder long1024 = new StringBuilder();
        for (int i = 0; i < 1024; i++) {
            long1024.append('W');
        }
        text(ops, 0, 0, 1, 2, 0, long1024.toString());
        Assertions.assertEquals(3, recorder.quads.size());
        Assertions.assertEquals(17, recorder.quads.get(2)[4]);
        recorder.quads.clear();
        text(ops, 0, 13, 1, 2, 0, "below");
        text(ops, 0, -13, 1, 2, 0, "above");
        text(ops, 17, 0, 1, 2, 0, "right");
        text(ops, -32768, 0, 1, 2, 0, "far left");
        Assertions.assertEquals(0, recorder.quads.size());
        text(ops, 0, -12, 1, 2, 0, "a");
        text(ops, 0, 12, 1, 2, 0, "b");
        Assertions.assertEquals(2, recorder.quads.size());
        Assertions.assertArrayEquals(new int[] { 0, 0, 6, 1 }, Arrays.copyOfRange(recorder.quads.get(0), 2, 6));
        Assertions.assertArrayEquals(new int[] { 0, 12, 6, 13 }, Arrays.copyOfRange(recorder.quads.get(1), 2, 6));
    }

    @Test
    void theMissingGlyphUsesTheLastCell() {
        GlyphAtlas atlas = new GlyphAtlas(SyntheticGlyphs.TABLE);
        OpQuads ops = ops(INDEX8, 1, 1);
        // U+2500 and U+10FFFF have no glyph in the synthetic table (28:633); U+0041 has one
        text(ops, 0, 0, 1, 2, 0, "─A􏿿");
        Assertions.assertEquals(3, recorder.quads.size());
        int last = atlas.cell(GlyphTable.MISSING);
        Assertions.assertEquals(191, last);
        Assertions.assertEquals(atlas.cellX(last) - 0, recorder.quads.get(0)[6]);
        Assertions.assertEquals(atlas.cellY(last), recorder.quads.get(0)[7]);
        int a = atlas.cell(SyntheticGlyphs.TABLE.indexOf('A'));
        Assertions.assertEquals(atlas.cellX(a) - 6, recorder.quads.get(1)[6]);
        Assertions.assertEquals(atlas.cellX(last) - 12, recorder.quads.get(2)[6]);
    }

    @Test
    void offsetsStayInsideI16OverRandomBlitsAndCells() {
        SplittableRandom random = new SplittableRandom(SEED);
        SurfaceDesc big = new SurfaceDesc(0, 640, 400, Formats.RGB565);
        for (int n = 0; n < 10_000; n++) {
            int tw = 1 + random.nextInt(1024);
            int th = 1 + random.nextInt(1024);
            OpQuads ops = ops(big, tw, th);
            recorder.quads.clear();
            ops.blit(TEX, i16(random), i16(random), i16(random), i16(random), i16(random), i16(random), 1);
            int x = random.nextInt(-700, 700);
            int y = random.nextInt(-20, 420);
            text(ops, x, y, 0xFFFFFFFF, 0xFF000000, 0, "ÿ ~ x─");
            for (int[] q : recorder.quads) {
                for (int i = 2; i < 8; i++) {
                    Assertions.assertTrue(q[i] >= Short.MIN_VALUE && q[i] <= Short.MAX_VALUE, Arrays.toString(q));
                }
                Assertions.assertTrue(q[2] < q[4] && q[3] < q[5], Arrays.toString(q));
            }
        }
    }

    @Test
    void expandedWritesEachRecordSixTimesAndInstancedOnce() {
        byte[][] records = new byte[2][];
        int[] bytesPerQuad = new int[2];
        for (QuadMode mode : QuadMode.values()) {
            QuadBatch batch = new QuadBatch(mode);
            Drawn drawn = new Drawn();
            batch.begin(drawn);
            batch.quad(Shaders.TEXT, OpQuads.ATLAS, -5, 1, 300, 32767, -32768, 32767, 65535, 0x1234, 1, 0);
            batch.quad(Shaders.TEXT, OpQuads.ATLAS, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0);
            batch.finish();
            Assertions.assertEquals(1, drawn.draws.size());
            Assertions.assertEquals(2, drawn.draws.get(0).quads);
            Assertions.assertEquals(QuadBatch.BUFFER_BYTES, drawn.draws.get(0).limit, "the whole 64 KiB (A12)");
            records[mode.ordinal()] = drawn.draws.get(0).bytes;
            bytesPerQuad[mode.ordinal()] = batch.bytesPerQuad();
        }
        Assertions.assertEquals(120, bytesPerQuad[QuadMode.EXPANDED.ordinal()]);
        Assertions.assertEquals(20, bytesPerQuad[QuadMode.INSTANCED.ordinal()]);
        byte[] instanced = records[QuadMode.INSTANCED.ordinal()];
        byte[] expanded = records[QuadMode.EXPANDED.ordinal()];
        // The first record, field by field, in native order: four i16, two i16, four u16
        ByteBuffer first = ByteBuffer.wrap(instanced)
            .order(ByteOrder.nativeOrder());
        short[] fields = { -5, 1, 300, 32767, -32768, 32767, (short) 65535, 0x1234, 1, 0 };
        for (int f = 0; f < fields.length; f++) {
            Assertions.assertEquals(fields[f], first.getShort(2 * f), "field " + f);
        }
        for (int quad = 0; quad < 2; quad++) {
            byte[] record = Arrays.copyOfRange(instanced, 20 * quad, 20 * quad + 20);
            for (int vertex = 0; vertex < 6; vertex++) {
                int at = 120 * quad + 20 * vertex;
                Assertions.assertArrayEquals(record, Arrays.copyOfRange(expanded, at, at + 20), quad + "/" + vertex);
            }
        }
    }

    @Test
    void aProgramOrTextureChangeFlushes() {
        QuadBatch batch = new QuadBatch(QuadMode.INSTANCED);
        Drawn drawn = new Drawn();
        batch.begin(drawn);
        batch.quad(Shaders.FILL, OpQuads.NO_TEXTURE, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0);
        batch.quad(Shaders.FILL, OpQuads.NO_TEXTURE, 0, 0, 1, 1, 0, 0, 2, 0, 0, 0);
        batch.quad(Shaders.BLIT_INDEX8, TEX, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0);
        batch.quad(Shaders.BLIT_INDEX8, TEX + 1, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0);
        batch.quad(Shaders.BLIT_INDEX8, TEX + 1, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0);
        batch.quad(Shaders.TEXT, OpQuads.ATLAS, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0);
        Assertions.assertEquals(3, drawn.draws.size(), "three batches drawn before the last");
        batch.finish();
        Assertions.assertEquals(4, drawn.draws.size());
        int[][] expected = { { Shaders.FILL, OpQuads.NO_TEXTURE, 2 }, { Shaders.BLIT_INDEX8, TEX, 1 },
            { Shaders.BLIT_INDEX8, TEX + 1, 2 }, { Shaders.TEXT, OpQuads.ATLAS, 1 } };
        for (int i = 0; i < expected.length; i++) {
            Draw d = drawn.draws.get(i);
            Assertions.assertArrayEquals(expected[i], new int[] { d.program, d.texture, d.quads }, "draw " + i);
        }
        Assertions.assertEquals(0, batch.quads());
        // finish ended the run
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> batch.quad(Shaders.FILL, OpQuads.NO_TEXTURE, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void aFullBufferFlushes() {
        for (QuadMode mode : QuadMode.values()) {
            QuadBatch batch = new QuadBatch(mode);
            int capacity = mode == QuadMode.EXPANDED ? 546 : 3276;
            Assertions.assertEquals(capacity, batch.capacity());
            Drawn drawn = new Drawn();
            batch.begin(drawn);
            for (int i = 0; i < 2 * capacity + 1; i++) {
                batch.quad(Shaders.FILL, OpQuads.NO_TEXTURE, i % 100, 0, i % 100 + 1, 1, 0, 0, i & 0xFFFF, 0, 0, 0);
            }
            Assertions.assertEquals(2, drawn.draws.size(), mode + ": two full buffers drawn");
            batch.finish();
            Assertions.assertEquals(3, drawn.draws.size());
            Assertions.assertEquals(capacity, drawn.draws.get(0).quads);
            Assertions.assertEquals(capacity, drawn.draws.get(1).quads);
            Assertions.assertEquals(1, drawn.draws.get(2).quads);
            // The third batch starts at the buffer's start: quad 2·capacity's record
            ByteBuffer last = ByteBuffer.wrap(drawn.draws.get(2).bytes)
                .order(ByteOrder.nativeOrder());
            Assertions.assertEquals((2 * capacity) % 100, last.getShort(0));
            Assertions.assertEquals(batch.draws(), 3);
        }
    }

    @Test
    void aRunNeedsBeginAndFinishDrawsNothingWhenEmpty() {
        QuadBatch batch = new QuadBatch(QuadMode.EXPANDED);
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> batch.quad(Shaders.FILL, OpQuads.NO_TEXTURE, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0));
        Drawn drawn = new Drawn();
        batch.begin(drawn);
        Assertions.assertThrows(IllegalStateException.class, () -> batch.begin(drawn));
        batch.finish();
        Assertions.assertEquals(0, drawn.draws.size());
        batch.finish();
    }

    @Test
    void opQuadsRefusesACellOtherThanFontZeros() {
        GlyphTable wide = new GlyphTable(8, 13, new int[0], new byte[0], new byte[13]);
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new OpQuads(INDEX8, new GlyphAtlas(wide), recorder, recorder));
    }

    // Helpers

    private OpQuads ops(SurfaceDesc surface, int textureWidth, int textureHeight) {
        recorder.width = textureWidth;
        recorder.height = textureHeight;
        return new OpQuads(surface, new GlyphAtlas(SyntheticGlyphs.TABLE), recorder, recorder);
    }

    private static void text(OpQuads ops, int x, int y, int fg, int bg, int flags, String s) {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        byte[] segment = new byte[utf8.length + 9];
        System.arraycopy(utf8, 0, segment, 9, utf8.length);
        ops.text(x, y, fg, bg, flags, 0, segment, 9, utf8.length);
    }

    /** EDGES with the side's W - 1 and W. */
    private static int[] edges(int side) {
        int[] out = Arrays.copyOf(EDGES, EDGES.length);
        out[4] = side - 1;
        out[5] = side;
        return out;
    }

    private static int i16(SplittableRandom random) {
        return random.nextInt(-32768, 32768);
    }

    private static void assertInside(int tx, int ty, int[] texture) {
        Assertions.assertTrue(
            tx >= 0 && tx < texture[0] && ty >= 0 && ty < texture[1],
            "texel (" + tx + ", " + ty + ") outside " + texture[0] + "x" + texture[1]);
    }

    private static String toString(List<int[]> quads) {
        StringBuilder out = new StringBuilder();
        for (int[] q : quads) {
            out.append(Arrays.toString(q))
                .append('\n');
        }
        return out.toString();
    }

    /** Records each quad and answers one texture's size. */
    private static final class Recorder implements OpQuads.QuadSink, OpQuads.TextureSizes {

        final List<int[]> quads = new ArrayList<>();
        final Map<Integer, int[]> sizes = new HashMap<>();
        int width;
        int height;

        @Override
        public void quad(int program, int texture, int x0, int y0, int x1, int y1, int ox, int oy, int v0, int v1,
            int v2, int v3) {
            quads.add(new int[] { program, texture, x0, y0, x1, y1, ox, oy, v0, v1, v2, v3 });
        }

        @Override
        public int textureWidth(int handle) {
            Assertions.assertEquals(TEX, handle);
            return width;
        }

        @Override
        public int textureHeight(int handle) {
            Assertions.assertEquals(TEX, handle);
            return height;
        }
    }

    private static final class Draw {

        int program;
        int texture;
        int quads;
        int limit;
        byte[] bytes;
    }

    /** Records each batch handed to the drawer, with a copy of its records. */
    private static final class Drawn implements QuadBatch.Drawer {

        final List<Draw> draws = new ArrayList<>();

        @Override
        public void draw(int program, int texture, ByteBuffer data, int quads) {
            Assertions.assertEquals(0, data.position());
            Draw d = new Draw();
            d.program = program;
            d.texture = texture;
            d.quads = quads;
            d.limit = data.limit();
            d.bytes = new byte[QuadBatch.BUFFER_BYTES];
            data.duplicate()
                .get(d.bytes);
            draws.add(d);
        }
    }
}
