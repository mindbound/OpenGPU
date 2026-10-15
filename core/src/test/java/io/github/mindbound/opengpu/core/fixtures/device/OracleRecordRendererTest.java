package io.github.mindbound.opengpu.core.fixtures.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
import io.github.mindbound.opengpu.core.font.GlyphTable;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FakeRenderHost;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.ReadbackSink;
import io.github.mindbound.opengpu.core.render.ResetRecord;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexFreeRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;

/**
 * The oracle as {@code FakeRenderHost}'s renderer (28:205; 28 §2.4): open makes a zeroed oracle on the surface and the
 * renderer's glyph table; each record kind maps onto the oracle's record method (28:496-497), a {@code FRAME}'s
 * segments in submit order; release drops the oracle and a later open starts afresh; a key that is not open throws.
 * The expected surfaces are worked out by hand in the comments, never read from an oracle.
 */
class OracleRecordRendererTest {

    private static final DeviceKey KEY = new DeviceKey(1, 1);
    private static final DeviceKey OTHER = new DeviceKey(1, 2);
    private static final int TEX = 0x08000000;
    private static final int TEX2 = 0x08010000;

    @Test
    void openGivesAZeroedOracleOnTheSurfaceAndTheRenderersTable() {
        OracleRecordRenderer renderer = new OracleRecordRenderer();
        Assertions.assertSame(SyntheticGlyphs.TABLE, renderer.glyphs());
        Assertions.assertFalse(renderer.isOpen(KEY));
        SurfaceDesc s = index8(6, 4);
        renderer.open(KEY, s);
        Assertions.assertTrue(renderer.isOpen(KEY));
        TierEOracle oracle = renderer.oracle(KEY);
        Assertions.assertSame(s, oracle.surface());
        Assertions.assertSame(SyntheticGlyphs.TABLE, oracle.glyphs());
        Assertions.assertEquals(0, oracle.textureCount());
        // 24:197, 21:242: the cleared frame 0 image; pixels() is a fresh array each time (RecordRenderer's contract)
        byte[] first = renderer.pixels(KEY);
        Assertions.assertArrayEquals(new byte[24], first);
        Assertions.assertNotSame(first, renderer.pixels(KEY));
        Assertions.assertThrows(NullPointerException.class, () -> new OracleRecordRenderer(null));
    }

    @Test
    void textDrawsWithTheGivenGlyphTable() {
        // A table without glyphs whose missing glyph fills all six columns (rows 0xFC): every codepoint is a solid
        // cell, where the synthetic table's 'A' (0x41) has row 0 = (7·65) mod 256 AND 0xFC = 0xC4 (28:633)
        byte[] solid = new byte[13];
        Arrays.fill(solid, (byte) 0xFC);
        GlyphTable table = new GlyphTable(6, 13, new int[0], new byte[0], solid);
        OracleRecordRenderer custom = new OracleRecordRenderer(table);
        OracleRecordRenderer synthetic = new OracleRecordRenderer();
        FrameRecord frame = frame(1, segment(text(0, 0, 5, 0, 0, 0, "A")));
        for (OracleRecordRenderer r : Arrays.asList(custom, synthetic)) {
            r.open(KEY, index8(6, 13));
            r.accept(KEY, frame);
        }
        Assertions.assertSame(
            table,
            custom.oracle(KEY)
                .glyphs());
        byte[] all5 = new byte[78];
        Arrays.fill(all5, (byte) 5);
        Assertions.assertArrayEquals(all5, custom.pixels(KEY));
        // 0xC4 = 1100 0100: columns 0, 1 and 5 of row 0 are set
        byte[] row0 = Arrays.copyOf(synthetic.pixels(KEY), 6);
        Assertions.assertArrayEquals(new byte[] { 5, 5, 0, 0, 0, 5 }, row0);
    }

    @Test
    void eachRecordKindMapsOntoTheOracle() {
        OracleRecordRenderer renderer = new OracleRecordRenderer();
        renderer.open(KEY, index8(6, 4));
        TierEOracle oracle = renderer.oracle(KEY);

        // TEX_CREATE: T[h] = (w, h, zeros) (28:497)
        renderer.accept(KEY, new TexCreateRecord(1, TEX, 3, 2, Formats.INDEX8));
        Assertions.assertTrue(oracle.hasTexture(TEX));
        Assertions.assertEquals(0, oracle.texel(TEX, 2, 1));

        // TEX_UPDATE: rows top first (28 §6.3)
        renderer.accept(KEY, new TexUpdateRecord(1, TEX, 0, 0, 3, 2, new byte[] { 1, 2, 3, 4, 5, 6 }));
        Assertions.assertEquals(3, oracle.texel(TEX, 2, 0));
        Assertions.assertEquals(4, oracle.texel(TEX, 0, 1));
        renderer.accept(KEY, new TexUpdateRecord(1, TEX, 1, 1, 2, 1, new byte[] { 8, 9 }));
        Assertions.assertEquals(8, oracle.texel(TEX, 1, 1));
        Assertions.assertEquals(9, oracle.texel(TEX, 2, 1));

        // FRAME: its segments in submit order; fill the surface with 7, then blit the texture to (2, 1)
        renderer.accept(KEY, frame(1, segment(fill(0, 0, 6, 4, 7)), segment(blit(TEX, 0, 0, 3, 2, 2, 1, 0))));
        Assertions.assertArrayEquals(rows("777777", "771237", "774897", "777777"), renderer.pixels(KEY));

        // TEX_FREE: T[h] removed
        renderer.accept(KEY, new TexCreateRecord(1, TEX2, 1, 1, Formats.INDEX8));
        renderer.accept(KEY, new TexFreeRecord(1, TEX));
        Assertions.assertFalse(oracle.hasTexture(TEX));
        Assertions.assertTrue(oracle.hasTexture(TEX2));

        // RESET: T = {}, S = the pixels (28:496)
        byte[] k = { 9, 8, 7, 6, 5, 4, 3, 2, 1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 8, 7, 6, 5, 4 };
        renderer.accept(KEY, new ResetRecord(2, k));
        Assertions.assertEquals(0, oracle.textureCount());
        Assertions.assertArrayEquals(k, renderer.pixels(KEY));
        Assertions.assertSame(oracle, renderer.oracle(KEY));
    }

    @Test
    void aFramesSegmentsRunInSubmitOrder() {
        byte[] seven = segment(fill(0, 0, 2, 1, 7));
        byte[] corner = segment(fill(0, 0, 1, 1, 3));
        OracleRecordRenderer renderer = new OracleRecordRenderer();
        renderer.open(KEY, index8(2, 1));
        renderer.accept(KEY, frame(1, seven, corner));
        Assertions.assertArrayEquals(new byte[] { 3, 7 }, renderer.pixels(KEY));
        renderer.accept(KEY, frame(2, corner, seven));
        Assertions.assertArrayEquals(new byte[] { 7, 7 }, renderer.pixels(KEY));
        // an empty FRAME (a present without a submit, 28:108) changes nothing
        renderer.accept(KEY, new FrameRecord(1, 3, new byte[0][], FrameTotals.EMPTY));
        Assertions.assertArrayEquals(new byte[] { 7, 7 }, renderer.pixels(KEY));
    }

    @Test
    void rgb565PayloadsAndPixelsAreLittleEndian() {
        // 28 §5.7: rgb565 as a LE u16; the texture is copied raw by BLIT (28 §5.5)
        OracleRecordRenderer renderer = new OracleRecordRenderer();
        renderer.open(KEY, new SurfaceDesc(0, 2, 1, Formats.RGB565));
        renderer.accept(KEY, new TexCreateRecord(1, TEX, 2, 1, Formats.RGB565));
        renderer.accept(KEY, new TexUpdateRecord(1, TEX, 0, 0, 2, 1, bytes(0x34, 0x12, 0xCD, 0xAB)));
        TierEOracle oracle = renderer.oracle(KEY);
        Assertions.assertEquals(0x1234, oracle.texel(TEX, 0, 0));
        Assertions.assertEquals(0xABCD, oracle.texel(TEX, 1, 0));
        renderer.accept(KEY, frame(1, segment(blit(TEX, 0, 0, 2, 1, 0, 0, 0))));
        Assertions.assertArrayEquals(bytes(0x34, 0x12, 0xCD, 0xAB), renderer.pixels(KEY));
    }

    @Test
    void releaseDropsTheOracleAndALaterOpenStartsAfresh() {
        OracleRecordRenderer renderer = new OracleRecordRenderer();
        renderer.open(KEY, index8(2, 1));
        TierEOracle first = renderer.oracle(KEY);
        renderer.accept(KEY, new TexCreateRecord(1, TEX, 1, 1, Formats.INDEX8));
        renderer.accept(KEY, frame(1, segment(fill(0, 0, 2, 1, 7))));

        renderer.release(KEY);
        Assertions.assertFalse(renderer.isOpen(KEY));
        assertNotOpen(renderer, KEY);
        // a second release is nothing
        renderer.release(KEY);

        renderer.open(KEY, index8(3, 1));
        TierEOracle second = renderer.oracle(KEY);
        Assertions.assertNotSame(first, second);
        Assertions.assertEquals(0, second.textureCount());
        Assertions.assertArrayEquals(new byte[3], renderer.pixels(KEY));
    }

    @Test
    void aKeyThatIsNotOpenThrows() {
        OracleRecordRenderer renderer = new OracleRecordRenderer();
        assertNotOpen(renderer, KEY);
        renderer.open(KEY, index8(2, 1));
        assertNotOpen(renderer, OTHER);
        // releasing a key never opened is nothing, and leaves the open one alone
        renderer.release(OTHER);
        Assertions.assertTrue(renderer.isOpen(KEY));
        Assertions.assertThrows(NullPointerException.class, () -> renderer.accept(KEY, null));
        Assertions.assertThrows(NullPointerException.class, () -> renderer.open(null, index8(1, 1)));
    }

    @Test
    void aRecordValidationNeverProducesFailsInTheOracle() {
        // The oracle refuses what validation never accepts (TierEOracle's contract); the renderer passes that on
        OracleRecordRenderer renderer = new OracleRecordRenderer();
        renderer.open(KEY, index8(2, 1));
        IllegalStateException absent = Assertions.assertThrows(
            IllegalStateException.class,
            () -> renderer.accept(KEY, new TexUpdateRecord(1, TEX, 0, 0, 1, 1, new byte[1])));
        Assertions.assertEquals("texture 0x08000000 is not present", absent.getMessage());
        Assertions.assertThrows(IllegalStateException.class, () -> renderer.accept(KEY, new TexFreeRecord(1, TEX)));
        Assertions
            .assertThrows(IllegalArgumentException.class, () -> renderer.accept(KEY, new ResetRecord(2, new byte[3])));
    }

    @Test
    void keysAreIndependentAndOpenAgainReplacesOnlyItsOwn() {
        OracleRecordRenderer renderer = new OracleRecordRenderer();
        renderer.open(KEY, index8(2, 1));
        renderer.open(OTHER, new SurfaceDesc(0, 1, 1, Formats.RGB565));
        renderer.accept(KEY, frame(1, segment(fill(0, 0, 2, 1, 7))));
        Assertions.assertArrayEquals(new byte[] { 7, 7 }, renderer.pixels(KEY));
        Assertions.assertArrayEquals(new byte[2], renderer.pixels(OTHER));
        TierEOracle other = renderer.oracle(OTHER);
        renderer.open(KEY, index8(2, 1));
        Assertions.assertArrayEquals(new byte[2], renderer.pixels(KEY));
        Assertions.assertSame(other, renderer.oracle(OTHER));
    }

    @Test
    void asFakeRenderHostsRendererItRunsWhatTheHostOffers() {
        // 28:205: step hands every queued record to the renderer, then reads back the newest FRAME it ran
        OracleRecordRenderer renderer = new OracleRecordRenderer();
        FakeRenderHost host = new FakeRenderHost(renderer);
        List<String> readbacks = new ArrayList<>();
        List<byte[]> pixels = new ArrayList<>();
        ReadbackSink sink = (session, deviceId, epoch, frameId, px) -> {
            readbacks.add(session + ":" + deviceId + " e" + epoch + " #" + frameId);
            pixels.add(px);
        };
        host.open(KEY, index8(3, 1), sink);
        Assertions.assertTrue(renderer.isOpen(KEY));
        host.offer(KEY, new TexCreateRecord(1, TEX, 1, 1, Formats.INDEX8));
        host.offer(KEY, new TexUpdateRecord(1, TEX, 0, 0, 1, 1, new byte[] { 4 }));
        host.offer(KEY, frame(1, segment(fill(0, 0, 3, 1, 2), blit(TEX, 0, 0, 1, 1, 1, 0, 0))));
        Assertions.assertEquals(3, host.step());
        Assertions.assertEquals(Arrays.asList("1:1 e1 #1"), readbacks);
        Assertions.assertArrayEquals(new byte[] { 2, 4, 2 }, pixels.get(0));
        Assertions.assertTrue(
            renderer.oracle(KEY)
                .hasTexture(TEX));
        // the host's release calls the renderer's (H17)
        host.release(KEY);
        Assertions.assertFalse(renderer.isOpen(KEY));
    }

    private static void assertNotOpen(OracleRecordRenderer renderer, DeviceKey key) {
        String expected = key + " is not open";
        Assertions.assertEquals(
            expected,
            Assertions.assertThrows(IllegalStateException.class, () -> renderer.oracle(key))
                .getMessage());
        Assertions.assertEquals(
            expected,
            Assertions.assertThrows(IllegalStateException.class, () -> renderer.pixels(key))
                .getMessage());
        Assertions.assertEquals(
            expected,
            Assertions.assertThrows(IllegalStateException.class, () -> renderer.accept(key, new TexFreeRecord(1, TEX)))
                .getMessage());
    }

    private static FrameRecord frame(long frameId, byte[]... segments) {
        return new FrameRecord(1, frameId, segments, FrameTotals.EMPTY);
    }

    private static SurfaceDesc index8(int w, int h) {
        return new SurfaceDesc(0, w, h, Formats.INDEX8);
    }

    /** An index8 surface from rows of decimal digits, one digit per pixel, top row first. */
    private static byte[] rows(String... rows) {
        StringBuilder all = new StringBuilder();
        for (String row : rows) {
            all.append(row);
        }
        byte[] out = new byte[all.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (all.charAt(i) - '0');
        }
        return out;
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
