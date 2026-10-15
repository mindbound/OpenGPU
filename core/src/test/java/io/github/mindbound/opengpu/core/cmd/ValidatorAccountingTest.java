package io.github.mindbound.opengpu.core.cmd;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.bytes;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.concat;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.header;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.text;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.fixtures.MapLiveView;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.TestFrame;

/**
 * 28 §11.2's caps ±1 (28 §3.6 rows 1, 2, 22 and 23; §8.1) and 28 §3.5's accounting: what each opcode's fill counts,
 * that {@code END} is no record and empty ops are, and that a rejected submit changes nothing. Through both
 * validators; the expected totals are worked out here from 28 §3.5 and §5.7.
 */
class ValidatorAccountingTest {

    // Whole-segment caps (rows 1, 2)

    @Test
    void theLargestSubmitIsAcceptedAndOneByteMoreIsNot() {
        // On T2's caps (S6 plan amendment F): 3 853 TEXT with n = 0 and one with n = 9 are
        // 8 + 65 501 + 26 + 1 = 65 536 B, 3 854 records and 702 px, inside the fill cap
        MapLiveView t2 = Expectations.t2();
        byte[] largest = textPadded(9);
        Assertions.assertEquals(65536, largest.length);
        Expectations.assertAccepted(largest, t2, new FrameTotals(65536, 3854, 0, 0, 0, 702));
        // n = 10: 65 537 B
        byte[] over = textPadded(10);
        Assertions.assertEquals(65537, over.length);
        StreamError e = Expectations.assertRejected(over, t2, ErrorCode.OVER_SUBMIT_BYTES, 0, "submit");
        Assertions.assertEquals(65537, e.detail);
    }

    @Test
    void theFrameByteCapCountsTheOpenFrame() {
        MapLiveView t2 = Expectations.t2();
        byte[] s = segment(clear(1));
        Assertions.assertEquals(14, s.length);
        // b + #s == 131 072: accepted; one byte more: rejected
        Expectations
            .assertAccepted(s, t2, new FrameTotals(131072 - 14, 0, 0, 0, 0, 0), new FrameTotals(14, 1, 64000, 0, 0, 0));
        StreamError e = Expectations.assertRejected(
            s,
            t2,
            new FrameTotals(131072 - 13, 0, 0, 0, 0, 0),
            ErrorCode.OVER_FRAME_BYTES,
            0,
            "submit");
        Assertions.assertEquals(131073, e.detail);
        // T3's frame cap is 262 144
        Expectations.assertAccepted(
            segment(),
            Expectations.t3(),
            new FrameTotals(262144 - 9, 0, 0, 0, 0, 0),
            new FrameTotals(9, 0, 0, 0, 0, 0));
        Expectations.assertRejected(
            segment(),
            Expectations.t3(),
            new FrameTotals(262144 - 8, 0, 0, 0, 0, 0),
            ErrorCode.OVER_FRAME_BYTES,
            0,
            "submit");
    }

    @Test
    void theWholeSegmentCapsComeFirst() {
        MapLiveView t2 = Expectations.t2();
        FrameTotals full = new FrameTotals(131072, 0, 0, 0, 0, 0);
        // 70 000 bytes with a full frame: the submit cap (row 1 before row 2)
        Expectations.assertRejected(new byte[70000], t2, full, ErrorCode.OVER_SUBMIT_BYTES, 0, "submit");
        // The frame cap before any header check: 2 bytes with 131 071 open (28 §11.2 [M])
        Expectations.assertRejected(
            bytes(0x4F, 0x47),
            t2,
            new FrameTotals(131071, 0, 0, 0, 0, 0),
            ErrorCode.OVER_FRAME_BYTES,
            0,
            "submit");
        // A full frame refuses even the shortest segment
        Expectations.assertRejected(segment(), t2, full, ErrorCode.OVER_FRAME_BYTES, 0, "submit");
        // Small caps are the view's: 22 bytes over a 16-byte submit cap, a CLEAR's 64 000 px over 5 000
        MapLiveView small = Expectations.withLimits(SurfaceDesc.forTier(2), new FrameLimits(16, 4096, 24, 5000));
        StreamError e = Expectations
            .assertRejected(segment(fill(0, 0, 2, 2, 1)), small, ErrorCode.OVER_SUBMIT_BYTES, 0, "submit");
        Assertions.assertEquals(22, e.detail);
        Expectations.assertRejected(segment(clear(1)), small, ErrorCode.OVER_FRAME_FILL, 8, "CLEAR");
        Expectations.assertAccepted(segment(), small, new FrameTotals(9, 0, 0, 0, 0, 0));
    }

    // Records and fill (rows 22, 23)

    @Test
    void theRecordCapCountsTheOpenFrameAndThisRecord() {
        MapLiveView t2 = Expectations.t2();
        FrameTotals open = new FrameTotals(0, 4095, 0, 0, 0, 0);
        Expectations.assertAccepted(segment(clear(1)), t2, open, new FrameTotals(14, 1, 64000, 0, 0, 0));
        // The second record is the 4 097th, at offset 13
        StreamError e = Expectations
            .assertRejected(segment(clear(1), clear(2)), t2, open, ErrorCode.OVER_FRAME_RECORDS, 13, "CLEAR");
        Assertions.assertEquals(4097, e.detail);
        // An empty record counts too
        Expectations.assertRejected(
            segment(clear(1), fill(0, 0, 0, 0, 1)),
            t2,
            open,
            ErrorCode.OVER_FRAME_RECORDS,
            13,
            "FILL_RECT");
        // Inside one segment, with a cap of 3: the fourth record
        MapLiveView three = Expectations
            .withLimits(SurfaceDesc.forTier(2), new FrameLimits(65536, 131072, 3, 1L << 40));
        Expectations.assertRejected(
            segment(clear(1), clear(1), clear(1), text(0, 0, 1, 0, 0, 0, "")),
            three,
            ErrorCode.OVER_FRAME_RECORDS,
            23,
            "TEXT");
        Expectations
            .assertAccepted(segment(clear(1), clear(1), clear(1)), three, new FrameTotals(24, 3, 192000, 0, 0, 0));
    }

    @Test
    void theFillCapCountsTheOpenFrameAndThisRecord() {
        MapLiveView t2 = Expectations.t2();
        // T2: 1 024 000 px; a CLEAR is 64 000
        Expectations.assertAccepted(
            segment(clear(1)),
            t2,
            new FrameTotals(0, 0, 0, 960000, 0, 0),
            new FrameTotals(14, 1, 64000, 0, 0, 0));
        StreamError e = Expectations.assertRejected(
            segment(clear(1)),
            t2,
            new FrameTotals(0, 0, 0, 960001, 0, 0),
            ErrorCode.OVER_FRAME_FILL,
            8,
            "CLEAR");
        Assertions.assertEquals(1024001, e.detail);
        // The open frame's fill is its sum over the classes
        Expectations.assertRejected(
            segment(clear(1)),
            t2,
            new FrameTotals(0, 0, 900000, 30000, 20000, 10001),
            ErrorCode.OVER_FRAME_FILL,
            8,
            "CLEAR");
        // Inside one segment: 16 CLEARs fill the cap exactly, the 17th at 8 + 16·5 = 88 does not fit
        byte[][] clears = new byte[17][];
        for (int i = 0; i < clears.length; i++) {
            clears[i] = clear(i);
        }
        StreamError seventeenth = Expectations
            .assertRejected(segment(clears), t2, ErrorCode.OVER_FRAME_FILL, 88, "CLEAR");
        Assertions.assertEquals(1088000, seventeenth.detail);
        byte[][] sixteen = new byte[16][];
        System.arraycopy(clears, 0, sixteen, 0, 16);
        Expectations.assertAccepted(segment(sixteen), t2, new FrameTotals(89, 16, 1024000, 0, 0, 0));
    }

    @Test
    void theReportedFillTotalSaturates() {
        // Open totals near Long.MAX_VALUE under a fill cap of Long.MAX_VALUE: the CLEAR's 64 000 px are over the cap,
        // and the total, which would pass Long.MAX_VALUE, is reported as Long.MAX_VALUE, never wrapped. The naive
        // validator must agree on the code and offset
        MapLiveView t2 = Expectations
            .withLimits(SurfaceDesc.forTier(2), new FrameLimits(65536, 131072, 4096, Long.MAX_VALUE));
        String reason = Long.MAX_VALUE + " px of fill exceed the frame's " + Long.MAX_VALUE + "-px cap";
        Validation first = Validator
            .validate(segment(clear(5)), t2, new FrameTotals(0, 0, Long.MAX_VALUE - 20, 0, 0, 0));
        assertOverFill(first, 8, "CLEAR", Long.MAX_VALUE);
        Expectations.assertRejected(
            segment(clear(5)),
            t2,
            new FrameTotals(0, 0, Long.MAX_VALUE - 20, 0, 0, 0),
            ErrorCode.OVER_FRAME_FILL,
            8,
            "CLEAR");
        Assertions.assertEquals(
            "8: CLEAR: " + reason,
            first.error()
                .message());
        // Spread over the classes, with this segment's fill before the record: 64 009 px are left, so the CLEAR fits
        // and the 64 000 px FILL_RECT at 13 does not
        FrameTotals open = new FrameTotals(0, 0, 1L << 61, 1L << 61, 1L << 61, (1L << 61) - 64010);
        Validation second = Validator.validate(segment(clear(5), fill(0, 0, 320, 200, 6)), t2, open);
        assertOverFill(second, 13, "FILL_RECT", Long.MAX_VALUE);
        Expectations.assertRejected(
            segment(clear(5), fill(0, 0, 320, 200, 6)),
            t2,
            open,
            ErrorCode.OVER_FRAME_FILL,
            13,
            "FILL_RECT");
        Assertions.assertEquals(
            "13: FILL_RECT: " + reason,
            second.error()
                .message());
    }

    @Test
    void recordsAreCheckedBeforeFill() {
        Expectations.assertRejected(
            segment(clear(1)),
            Expectations.t2(),
            new FrameTotals(0, 4096, 1024000, 0, 0, 0),
            ErrorCode.OVER_FRAME_RECORDS,
            8,
            "CLEAR");
    }

    @Test
    void aFillCapOf0AdmitsOnlyEmptyOps() {
        MapLiveView view = Expectations.withLimits(SurfaceDesc.forTier(2), new FrameLimits(65536, 131072, 4096, 0));
        int tex = view.create(4, 4);
        Expectations.assertAccepted(
            segment(
                fill(0, 0, 0, 5, 1),
                fill(320, 0, 5, 5, 1),
                blit(tex, 0, 0, 0, 4, 0, 0, 0),
                text(0, 0, 1, 0, 0, 0, "")),
            view,
            new FrameTotals(8 + 13 + 13 + 18 + 17 + 1, 4, 0, 0, 0, 0));
        StreamError e = Expectations.assertRejected(segment(clear(1)), view, ErrorCode.OVER_FRAME_FILL, 8, "CLEAR");
        Assertions.assertEquals(64000, e.detail);
        Expectations.assertRejected(
            segment(fill(0, 0, 0, 5, 1), fill(319, 199, 5, 5, 1)),
            view,
            ErrorCode.OVER_FRAME_FILL,
            21,
            "FILL_RECT");
    }

    @Test
    void fillIsALongBeyond2To31() {
        // 4 096 CLEARs of a 4 096 x 4 096 surface: 68 719 476 736 px
        MapLiveView view = Expectations.withLimits(
            new SurfaceDesc(0, 4096, 4096, Formats.INDEX8),
            new FrameLimits(65536, 131072, 4096, Long.MAX_VALUE));
        byte[][] clears = new byte[4096][];
        for (int i = 0; i < clears.length; i++) {
            clears[i] = clear(i & 0xFF);
        }
        byte[] s = segment(clears);
        long pixels = 4096L * 4096 * 4096;
        Expectations.assertAccepted(s, view, new FrameTotals(s.length, 4096, pixels, 0, 0, 0));
    }

    // What each opcode's fill counts (28 §3.5)

    @Test
    void clearCountsTheWholeSurface() {
        Expectations.assertAccepted(segment(clear(9)), Expectations.t2(), new FrameTotals(14, 1, 64000, 0, 0, 0));
        Expectations
            .assertAccepted(segment(clear(0xFF123456)), Expectations.t3(), new FrameTotals(14, 1, 256000, 0, 0, 0));
        Expectations.assertAccepted(
            segment(clear(9)),
            Expectations.withLimits(new SurfaceDesc(0, 17, 13, Formats.INDEX8), FrameLimits.forTier(2)),
            new FrameTotals(14, 1, 221, 0, 0, 0));
    }

    @Test
    void fillRectCountsItsRectClippedToTheSurface() {
        MapLiveView t2 = Expectations.t2();
        // Inside, then clipped at each edge of 320 x 200
        assertFill(t2, 10, 20, 100, 50, 5000);
        assertFill(t2, -5, 0, 10, 10, 50);
        assertFill(t2, 315, 0, 10, 10, 50);
        assertFill(t2, 0, -3, 10, 10, 70);
        assertFill(t2, 0, 195, 10, 10, 50);
        assertFill(t2, 318, 198, 10, 10, 4);
        // Larger than the surface on every side
        assertFill(t2, -10, -10, 400, 300, 64000);
        // Empty: w = 0, h < 0, entirely off the surface (x = W, y = H, x + w = 0)
        assertFill(t2, 0, 0, 0, 10, 0);
        assertFill(t2, 0, 0, 10, -5, 0);
        assertFill(t2, 320, 0, 10, 10, 0);
        assertFill(t2, 0, 200, 10, 10, 0);
        assertFill(t2, -10, 0, 10, 10, 0);
        assertFill(t2, 0, 0, -32768, -32768, 0);
        // i16 extremes, with x + w in 32-bit arithmetic (28 §5.1)
        assertFill(t2, -32768, -32768, 32767, 32767, 0);
        assertFill(t2, -100, -100, 32767, 32767, 64000);
        assertFill(t2, 32767, 32767, 32767, 32767, 0);
        assertFill(t2, -32768, 0, 32767, 10, 0);
        assertFill(t2, 319, 199, 32767, 32767, 1);
        // T3: 640 x 400
        assertFill(Expectations.t3(), 630, 390, 20, 20, 100);
    }

    @Test
    void blitCountsItsClippedAreaKey0Included() {
        MapLiveView t2 = Expectations.t2();
        int t = Expectations.TEXTURE;
        // A 16 x 16 texture: inside
        assertBlit(t2, t, 0, 0, 16, 16, 150, 40, 256);
        // Source clipped: sx < 0, sx + w > TW, sy + h > TH
        assertBlit(t2, t, -4, 0, 16, 16, 150, 40, 192);
        assertBlit(t2, t, 10, 0, 16, 16, 150, 40, 96);
        assertBlit(t2, t, 0, 12, 16, 16, 150, 40, 64);
        // Destination clipped at each edge
        assertBlit(t2, t, 0, 0, 16, 16, 310, 40, 160);
        assertBlit(t2, t, 0, 0, 16, 16, -3, 40, 208);
        assertBlit(t2, t, 0, 0, 16, 16, 150, -5, 176);
        assertBlit(t2, t, 0, 0, 16, 16, 150, 190, 160);
        // Both at once: i in [5, 16), j in [0, 5)
        assertBlit(t2, t, -2, 3, 16, 16, -5, 195, 55);
        // A rect larger than the texture
        assertBlit(t2, t, -100, -100, 1000, 1000, -100, -100, 256);
        // Empty: w = 0, h < 0, the source entirely outside
        assertBlit(t2, t, 0, 0, 0, 16, 0, 0, 0);
        assertBlit(t2, t, 0, 0, 16, -1, 0, 0, 0);
        assertBlit(t2, t, 16, 0, 16, 16, 0, 0, 0);
        assertBlit(t2, t, 0, -16, 16, 16, 0, 0, 0);
        assertBlit(t2, t, 0, 0, 16, 16, 320, 0, 0);
        // i16 extremes
        assertBlit(t2, t, -32768, -32768, 32767, 32767, 32767, 32767, 0);
        assertBlit(t2, t, 0, 0, 32767, 32767, -32768, -32768, 0);
        // KEY0 counts the skipped texels too (28 §3.5)
        byte[] keyed = segment(blit(t, 0, 0, 16, 16, 0, 0, 1));
        Expectations.assertAccepted(keyed, t2, new FrameTotals(27, 1, 0, 0, 256, 0));
        // The texture's own size: 7 x 3
        MapLiveView small = Expectations.t2();
        int narrow = small.create(7, 3);
        assertBlit(small, narrow, 0, 0, 16, 16, 0, 0, 21);
        assertBlit(small, narrow, 5, 1, 16, 16, 0, 0, 4);
    }

    @Test
    void textCounts78PxPerCodepointUnclipped() {
        MapLiveView t2 = Expectations.t2();
        // "é€😀" is 2 + 3 + 4 bytes and 3 codepoints: 234 px
        byte[] three = segment(text(0, 0, 1, 0, 0, 0, "é€😀"));
        Assertions.assertEquals(8 + 17 + 9 + 1, three.length);
        Expectations.assertAccepted(three, t2, new FrameTotals(three.length, 1, 0, 0, 0, 234));
        // Off the surface, the count is the same (28 §7.3)
        byte[] off = segment(text(-1000, -1000, 1, 0, 0, 0, "Hi"));
        Expectations.assertAccepted(off, t2, new FrameTotals(off.length, 1, 0, 0, 0, 156));
        byte[] far = segment(text(32767, 32767, 1, 0, 1, 0, "Hi"));
        Expectations.assertAccepted(far, t2, new FrameTotals(far.length, 1, 0, 0, 0, 156));
        // n = 0: one record, 0 px
        byte[] empty = segment(text(0, 0, 1, 0, 0, 0, ""));
        Expectations.assertAccepted(empty, t2, new FrameTotals(26, 1, 0, 0, 0, 0));
    }

    @Test
    void theClassesAreTotalledApartAndEndIsNoRecord() {
        MapLiveView t2 = Expectations.t2();
        byte[] s = segment(
            clear(1),
            fill(0, 0, 10, 10, 2),
            blit(Expectations.TEXTURE, 0, 0, 4, 4, 0, 0, 0),
            text(0, 0, 3, 4, 0, 0, "ab"),
            fill(0, 0, 1, 1, 5));
        Expectations.assertAccepted(s, t2, new FrameTotals(s.length, 5, 64000, 101, 16, 156));
        Expectations.assertAccepted(segment(), t2, new FrameTotals(9, 0, 0, 0, 0, 0));
    }

    // Commit (28 §3.5: a rejected submit changes nothing)

    @Test
    void aRejectedSubmitChangesNothing() {
        MapLiveView t2 = Expectations.t2();
        FrameTotals start = new FrameTotals(100, 4093, 0, 0, 0, 0);
        TestFrame frame = new TestFrame(t2, start);
        byte[] a = segment(clear(1), fill(0, 0, 10, 10, 2));
        FrameTotals aTotals = new FrameTotals(27, 2, 64000, 100, 0, 0);
        Expectations.assertAccepted(a, t2, frame.totals(), aTotals);
        Assertions.assertTrue(
            frame.submit(a)
                .isAccepted());
        FrameTotals afterA = start.plus(aTotals);
        Assertions.assertEquals(afterA, frame.totals());
        // B: two records more than the cap allows
        byte[] b = segment(clear(3), clear(4));
        Expectations.assertRejected(b, t2, frame.totals(), ErrorCode.OVER_FRAME_RECORDS, 13, "CLEAR");
        Validation vb = frame.submit(b);
        Assertions.assertFalse(vb.isAccepted());
        Assertions.assertEquals(afterA, frame.totals());
        Assertions.assertEquals(
            1,
            frame.segments()
                .size());
        Assertions.assertSame(
            a,
            frame.segments()
                .get(0));
        // B': a bad colour
        byte[] bad = segment(clear(0x100));
        Expectations.assertRejected(bad, t2, frame.totals(), ErrorCode.BAD_COLOUR, 8, "CLEAR");
        Assertions.assertFalse(
            frame.submit(bad)
                .isAccepted());
        Assertions.assertEquals(afterA, frame.totals());
        Assertions.assertEquals(Collections.singletonList(a), frame.segments());
        // C: the last record the cap allows
        byte[] c = segment(text(0, 0, 1, 0, 0, 0, "c"));
        FrameTotals cTotals = new FrameTotals(27, 1, 0, 0, 0, 78);
        Expectations.assertAccepted(c, t2, frame.totals(), cTotals);
        Assertions.assertTrue(
            frame.submit(c)
                .isAccepted());
        Assertions.assertEquals(afterA.plus(cTotals), frame.totals());
        Assertions.assertEquals(4096, frame.totals().records);
        byte[][] sealed = frame.seal();
        Assertions.assertEquals(2, sealed.length);
        Assertions.assertSame(a, sealed[0]);
        Assertions.assertSame(c, sealed[1]);
        Assertions.assertEquals(FrameTotals.EMPTY, frame.totals());
    }

    @Test
    void validationNeverWritesTheSegment() {
        // C14: the validator reads the argument array in place and never writes it
        MapLiveView t2 = Expectations.t2();
        byte[][] cases = { segment(clear(1), text(0, 0, 1, 0, 0, 0, "ok")), segment(clear(0x100)),
            concat(header(), bytes(0x02)), new byte[70000], textPadded(9) };
        for (byte[] s : cases) {
            byte[] copy = s.clone();
            Validator.validate(s, t2);
            Validator.validate(s, t2, new FrameTotals(5, 5, 5, 5, 5, 5));
            Assertions.assertArrayEquals(copy, s);
        }
    }

    /** 3 853 empty TEXT records and one TEXT of n ASCII bytes: 65 527 + n bytes on T2's caps. */
    private static byte[] textPadded(int n) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] empty = text(0, 0, 1, 0, 0, 0, "");
        for (int i = 0; i < 3853; i++) {
            out.write(empty, 0, empty.length);
        }
        byte[] string = new byte[n];
        Arrays.fill(string, (byte) 'x');
        byte[] last = text(0, 0, 1, 0, 0, 0, string);
        out.write(last, 0, last.length);
        return segment(out.toByteArray());
    }

    private static void assertOverFill(Validation v, int offset, String op, long detail) {
        Assertions.assertFalse(v.isAccepted(), v::toString);
        StreamError e = v.error();
        Assertions.assertEquals(ErrorCode.OVER_FRAME_FILL, e.code, e::toString);
        Assertions.assertEquals(offset, e.offset, e::toString);
        Assertions.assertEquals(op, e.op(), e::toString);
        Assertions.assertTrue(e.detail >= 0, e::toString);
        Assertions.assertEquals(detail, e.detail, e::toString);
    }

    private static void assertFill(MapLiveView view, int x, int y, int w, int h, long pixels) {
        int c = view.surface().format == Formats.INDEX8 ? 7 : 0xFF00FF00;
        byte[] s = segment(fill(x, y, w, h, c));
        Expectations.assertAccepted(s, view, new FrameTotals(s.length, 1, 0, pixels, 0, 0));
    }

    private static void assertBlit(MapLiveView view, int tex, int sx, int sy, int w, int h, int dx, int dy,
        long pixels) {
        byte[] s = segment(blit(tex, sx, sy, w, h, dx, dy, 0));
        Expectations.assertAccepted(s, view, new FrameTotals(s.length, 1, 0, 0, pixels, 0));
        Assertions.assertEquals(27, s.length, TestEncoder.toHex(s));
    }
}
