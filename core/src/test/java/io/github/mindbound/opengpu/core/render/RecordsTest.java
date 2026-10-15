package io.github.mindbound.opengpu.core.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;

/**
 * The M0 records of 28 §2.3 (28:161-171): their kinds and the reserved ones, {@code isResource}, the queued bytes of
 * 28:140 (64 + w·h·bpp, 64 + {@code #data}, 64, 0, 0), the constructor checks (amendment B15), the arrays held as given
 * (C14; 28:562; 28:150: {@code FRAME} keeps the submitted arrays but not the caller's outer array) and the dev-build
 * CRCs of 28:202 (H16), which every Gradle test task has on.
 */
class RecordsTest {

    private static final int T = 0x08000000;

    @Test
    void kindsAreTheirOwnNamespace() {
        // 28:161-171
        Assertions.assertEquals(1, Record.FRAME);
        Assertions.assertEquals(2, Record.TEX_CREATE);
        Assertions.assertEquals(3, Record.TEX_UPDATE);
        Assertions.assertEquals(4, Record.TEX_FREE);
        Assertions.assertEquals(5, Record.PALETTE);
        Assertions.assertEquals(6, Record.DITHER);
        Assertions.assertEquals(7, Record.INIT);
        Assertions.assertEquals(8, Record.UPLOAD);
        Assertions.assertEquals(9, Record.RESET);
        Assertions.assertEquals(64, Record.RESOURCE_OVERHEAD);
        Assertions.assertEquals(Record.FRAME, frame(1, 1, new byte[9]).kind());
        Assertions.assertEquals(Record.TEX_CREATE, new TexCreateRecord(1, T, 16, 16, Formats.INDEX8).kind());
        Assertions.assertEquals(Record.TEX_UPDATE, new TexUpdateRecord(1, T, 0, 0, 1, 1, new byte[1]).kind());
        Assertions.assertEquals(Record.TEX_FREE, new TexFreeRecord(1, T).kind());
        Assertions.assertEquals(Record.RESET, new ResetRecord(2, new byte[4]).kind());
    }

    @Test
    void onlyTheTextureRecordsAreResources() {
        Assertions.assertFalse(frame(1, 1).isResource());
        Assertions.assertTrue(new TexCreateRecord(1, T, 16, 16, Formats.INDEX8).isResource());
        Assertions.assertTrue(new TexUpdateRecord(1, T, 0, 0, 1, 1, new byte[1]).isResource());
        Assertions.assertTrue(new TexFreeRecord(1, T).isResource());
        Assertions.assertFalse(new ResetRecord(2, new byte[4]).isResource());
    }

    @Test
    void queuedBytesAreTheOverheadPlusThePayloadOfResourceRecords() {
        // 28:140
        TexCreateRecord small = new TexCreateRecord(1, T, 16, 16, Formats.INDEX8);
        Assertions.assertEquals(256, small.byteSize());
        Assertions.assertEquals(320, small.queuedBytes());
        TexCreateRecord wide = new TexCreateRecord(1, T, 16, 16, Formats.RGB565);
        Assertions.assertEquals(512, wide.byteSize());
        Assertions.assertEquals(576, wide.queuedBytes());
        TexCreateRecord largest = new TexCreateRecord(1, T, 1024, 1024, Formats.RGB565);
        Assertions.assertEquals(2097152, largest.byteSize());
        Assertions.assertEquals(2097216, largest.queuedBytes());
        Assertions.assertEquals(320, new TexUpdateRecord(1, T, 0, 0, 16, 16, new byte[256]).queuedBytes());
        Assertions.assertEquals(576, new TexUpdateRecord(1, T, 0, 0, 16, 16, new byte[512]).queuedBytes());
        Assertions.assertEquals(64, new TexFreeRecord(1, T).queuedBytes());
        Assertions.assertEquals(0, frame(1, 1, new byte[64]).queuedBytes());
        Assertions.assertEquals(0, new ResetRecord(2, new byte[64000]).queuedBytes());
    }

    @Test
    void everyRecordCarriesANonNegativeEpoch() {
        Assertions.assertEquals(0, new TexFreeRecord(0, T).epoch);
        Assertions.assertEquals(Long.MAX_VALUE, new TexFreeRecord(Long.MAX_VALUE, T).epoch);
        assertIllegal(() -> frame(-1, 1));
        assertIllegal(() -> new TexCreateRecord(-1, T, 1, 1, Formats.INDEX8));
        assertIllegal(() -> new TexUpdateRecord(-1, T, 0, 0, 1, 1, new byte[1]));
        assertIllegal(() -> new TexFreeRecord(-1, T));
        assertIllegal(() -> new ResetRecord(-1, new byte[1]));
        Assertions.assertEquals(7, frame(7, 3).epoch);
    }

    @Test
    void aFrameKeepsTheSubmittedArraysButNotTheOuterOne() {
        byte[] a = new byte[9];
        byte[] b = new byte[64];
        byte[][] outer = { a, b };
        FrameTotals totals = new FrameTotals(73, 4, 64000, 5000, 256, 156);
        FrameRecord f = new FrameRecord(1, 3, outer, totals);
        outer[0] = new byte[9];
        outer[1] = null;
        Assertions.assertEquals(2, f.segmentCount());
        Assertions.assertSame(a, f.segment(0));
        Assertions.assertSame(b, f.segment(1));
        Assertions.assertSame(
            a,
            f.segments()
                .get(0));
        Assertions.assertSame(
            b,
            f.segments()
                .get(1));
        Assertions.assertSame(totals, f.totals);
        Assertions.assertEquals(3, f.frameId);
        Assertions.assertEquals(73, f.byteCount());
        Assertions.assertEquals("FRAME e1 #3 (2 segments, 73 B)", f.toString());
    }

    @Test
    void aFrameMayHaveNoSegments() {
        // 28:108: a present without a submit is an empty FRAME
        FrameRecord f = new FrameRecord(1, 1, new byte[0][], FrameTotals.EMPTY);
        Assertions.assertEquals(0, f.segmentCount());
        Assertions.assertTrue(
            f.segments()
                .isEmpty());
        Assertions.assertEquals(0, f.byteCount());
        Assertions.assertSame(FrameTotals.EMPTY, f.totals);
        Assertions.assertTrue(f.intact());
        Assertions.assertEquals("FRAME e1 #1 (0 segments, 0 B)", f.toString());
        Assertions.assertEquals("FRAME e1 #1 (1 segment, 9 B)", frame(1, 1, new byte[9]).toString());
    }

    @Test
    void segmentsIsUnmodifiable() {
        FrameRecord f = frame(1, 1, new byte[9]);
        List<byte[]> segments = f.segments();
        Assertions.assertThrows(UnsupportedOperationException.class, () -> segments.set(0, new byte[1]));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> segments.add(new byte[1]));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> segments.remove(0));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> f.segment(1));
    }

    @Test
    void theFrameConstructorChecksItsArguments() {
        Assertions.assertThrows(NullPointerException.class, () -> new FrameRecord(1, 1, null, FrameTotals.EMPTY));
        Assertions.assertThrows(NullPointerException.class, () -> new FrameRecord(1, 1, new byte[0][], null));
        Assertions.assertThrows(
            NullPointerException.class,
            () -> new FrameRecord(1, 1, new byte[][] { new byte[9], null }, FrameTotals.EMPTY));
        // 0 is the cleared image (28:108)
        assertIllegal(() -> frame(1, 0));
        assertIllegal(() -> frame(1, -1));
        Assertions.assertEquals(Long.MAX_VALUE, frame(1, Long.MAX_VALUE).frameId);
    }

    @Test
    void aTexCreateIsAWellFormedTextureOfAllowedSidesAndADefinedFormat() {
        TexCreateRecord c = new TexCreateRecord(1, 0x0FFFFFFF, 1024, 1, Formats.RGB565);
        Assertions.assertEquals(0x0FFFFFFF, c.handle);
        Assertions.assertEquals(1024, c.width);
        Assertions.assertEquals(1, c.height);
        Assertions.assertEquals(Formats.RGB565, c.format);
        new TexCreateRecord(1, T, 1, 1024, Formats.INDEX8);
        for (int bad : new int[] { 0, -1, Integer.MIN_VALUE, 0x00000001, 0x10000000, 0x48000000, 0x7FFFFFFF }) {
            assertIllegal(() -> new TexCreateRecord(1, bad, 1, 1, Formats.INDEX8));
        }
        assertIllegal(() -> new TexCreateRecord(1, T, 0, 1, Formats.INDEX8));
        assertIllegal(() -> new TexCreateRecord(1, T, 1, 0, Formats.INDEX8));
        assertIllegal(() -> new TexCreateRecord(1, T, 1025, 1, Formats.INDEX8));
        assertIllegal(() -> new TexCreateRecord(1, T, 1, 1025, Formats.INDEX8));
        assertIllegal(() -> new TexCreateRecord(1, T, 1, 1, Formats.INVALID));
        assertIllegal(() -> new TexCreateRecord(1, T, 1, 1, 3));
        Assertions.assertTrue(c.intact());
        Assertions.assertEquals(
            "TEX_CREATE e1 0x08000000 16x16 index8",
            new TexCreateRecord(1, T, 16, 16, Formats.INDEX8).toString());
    }

    @Test
    void aTexUpdateHoldsTheArgumentArrayAndChecksItsRegion() {
        byte[] data = new byte[256];
        TexUpdateRecord u = new TexUpdateRecord(1, T, 3, 4, 16, 16, data);
        Assertions.assertSame(data, u.payload());
        Assertions.assertEquals(T, u.handle);
        Assertions.assertEquals(3, u.x);
        Assertions.assertEquals(4, u.y);
        Assertions.assertEquals(16, u.width);
        Assertions.assertEquals(16, u.height);
        Assertions.assertEquals("TEX_UPDATE e1 0x08000000 at 3,4 16x16 (256 B)", u.toString());
        // either format's length: the record carries no format (28:165)
        new TexUpdateRecord(1, T, 0, 0, 16, 16, new byte[512]);
        // B15: x, y in 0-1 024, w, h in 1-1 024
        new TexUpdateRecord(1, T, 1024, 1024, 1, 1, new byte[1]);
        new TexUpdateRecord(1, T, 0, 0, 1024, 1024, new byte[2097152]);
        assertIllegal(() -> new TexUpdateRecord(1, T, -1, 0, 1, 1, new byte[1]));
        assertIllegal(() -> new TexUpdateRecord(1, T, 0, -1, 1, 1, new byte[1]));
        assertIllegal(() -> new TexUpdateRecord(1, T, 1025, 0, 1, 1, new byte[1]));
        assertIllegal(() -> new TexUpdateRecord(1, T, 0, 1025, 1, 1, new byte[1]));
        assertIllegal(() -> new TexUpdateRecord(1, T, 0, 0, 0, 1, new byte[0]));
        assertIllegal(() -> new TexUpdateRecord(1, T, 0, 0, 1, 0, new byte[0]));
        assertIllegal(() -> new TexUpdateRecord(1, T, 0, 0, 1025, 1, new byte[1025]));
        assertIllegal(() -> new TexUpdateRecord(1, T, 0, 0, 1, 1025, new byte[1025]));
        // a length that is neither w·h nor 2·w·h
        assertIllegal(() -> new TexUpdateRecord(1, T, 0, 0, 16, 16, new byte[255]));
        assertIllegal(() -> new TexUpdateRecord(1, T, 0, 0, 16, 16, new byte[257]));
        assertIllegal(() -> new TexUpdateRecord(1, T, 0, 0, 16, 16, new byte[768]));
        assertIllegal(() -> new TexUpdateRecord(1, 0x10000000, 0, 0, 1, 1, new byte[1]));
        assertIllegal(() -> new TexUpdateRecord(1, 0, 0, 0, 1, 1, new byte[1]));
        Assertions.assertThrows(NullPointerException.class, () -> new TexUpdateRecord(1, T, 0, 0, 1, 1, null));
    }

    @Test
    void aTexFreeIsAWellFormedTexture() {
        TexFreeRecord f = new TexFreeRecord(3, 0x08010002);
        Assertions.assertEquals(0x08010002, f.handle);
        Assertions.assertEquals("TEX_FREE e3 0x08010002", f.toString());
        Assertions.assertTrue(f.intact());
        for (int bad : new int[] { 0, -1, 0x00010000, 0x18000000, 0x7FFFFFFF }) {
            assertIllegal(() -> new TexFreeRecord(1, bad));
        }
    }

    @Test
    void aResetHoldsThePixelsItIsGiven() {
        // 28:150: the caller passes a copy it made; the record does not copy again
        byte[] pixels = new byte[64000];
        ResetRecord r = new ResetRecord(2, pixels);
        Assertions.assertSame(pixels, r.pixels());
        Assertions.assertEquals(2, r.epoch);
        Assertions.assertEquals("RESET e2 (64000 B)", r.toString());
        Assertions.assertThrows(NullPointerException.class, () -> new ResetRecord(1, null));
        Assertions.assertEquals(0, new ResetRecord(1, new byte[0]).pixels().length);
    }

    @Test
    void devBuildsDetectAnArrayWrittenAfterConstruction() {
        // 28:202, H16: assertions are on in every Gradle test task (plan §3.12)
        Assertions.assertTrue(Record.devChecks());
        byte[] segment = new byte[9];
        FrameRecord f = frame(1, 1, new byte[4], segment);
        Assertions.assertTrue(f.intact());
        segment[8] = 1;
        Assertions.assertFalse(f.intact());
        segment[8] = 0;
        Assertions.assertTrue(f.intact());

        byte[] payload = new byte[256];
        TexUpdateRecord u = new TexUpdateRecord(1, T, 0, 0, 16, 16, payload);
        Assertions.assertTrue(u.intact());
        payload[255] ^= 0x40;
        Assertions.assertFalse(u.intact());

        byte[] pixels = new byte[64000];
        ResetRecord r = new ResetRecord(2, pixels);
        Assertions.assertTrue(r.intact());
        pixels[0] = 1;
        Assertions.assertFalse(r.intact());
    }

    @Test
    void acceptCallsTheVisitorOfItsKind() {
        final List<String> calls = new ArrayList<>();
        RecordVisitor v = new RecordVisitor() {

            @Override
            public void frame(FrameRecord r) {
                calls.add("frame " + r.frameId);
            }

            @Override
            public void texCreate(TexCreateRecord r) {
                calls.add("create");
            }

            @Override
            public void texUpdate(TexUpdateRecord r) {
                calls.add("update");
            }

            @Override
            public void texFree(TexFreeRecord r) {
                calls.add("free");
            }

            @Override
            public void reset(ResetRecord r) {
                calls.add("reset");
            }
        };
        Record[] records = { frame(1, 4), new TexCreateRecord(1, T, 1, 1, Formats.INDEX8),
            new TexUpdateRecord(1, T, 0, 0, 1, 1, new byte[1]), new TexFreeRecord(1, T),
            new ResetRecord(2, new byte[1]) };
        for (Record r : records) {
            r.accept(v);
        }
        Assertions.assertEquals(Arrays.asList("frame 4", "create", "update", "free", "reset"), calls);
    }

    private static FrameRecord frame(long epoch, long id, byte[]... segments) {
        return new FrameRecord(epoch, id, segments, FrameTotals.EMPTY);
    }

    private static void assertIllegal(Executable construction) {
        Assertions.assertThrows(IllegalArgumentException.class, construction);
    }
}
