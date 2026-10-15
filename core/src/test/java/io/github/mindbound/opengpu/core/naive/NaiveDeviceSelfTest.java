package io.github.mindbound.opengpu.core.naive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.cmd.Validator;
import io.github.mindbound.opengpu.core.fixtures.MapLiveView;

/**
 * Hand-computed expectations for {@link NaiveDevice}: CRCs and charges were computed outside Java (Python's zlib and
 * the formulas of 28 §8.2), and the segments are written byte by byte here, without :core's test encoder.
 */
class NaiveDeviceSelfTest {

    private static final SurfaceDesc T2 = new SurfaceDesc(2, 320, 200, Formats.INDEX8);
    private static final SurfaceDesc T3 = new SurfaceDesc(3, 640, 400, Formats.RGB565);

    /** 28 §9.4's 64-byte segment, from its byte table. */
    private static final String S64 = "4F 47 50 55 01 00 00 00" + " 01 00 00 00 00"
        + " 30 0A 00 14 00 64 00 32 00 C4 00 00 00"
        + " 31 00 00 00 08 00 00 00 00 10 00 10 00 96 00 28 00 01"
        + " 33 08 00 B4 00 FF 00 00 00 00 00 00 00 01 00 02 00 48 69"
        + " FF";

    /** Header plus END, 9 bytes, crc AB8EA542. */
    private static final byte[] MIN = hex("4F 47 50 55 01 00 00 00 FF");

    private static final String EMPTY_FRAME = " segs=0 bytes=0 records=0 clear=0 fill=0 copy=0 set=0 crc=00000000";

    private static final String C_PRESENT_T2 = "consume 0.062500000";
    private static final String C_MIN_T2 = "consume 0.015633583"; // 1/64 + 9/2^20
    private static final String C_27_T2 = "consume 0.015650749"; // 1/64 + 27/2^20
    private static final String C_1X1 = "consume 0.000003815"; // 1/2^18
    private static final String C_4X4 = "consume 0.000061035"; // 16/2^18

    private static NaiveDevice t2(LongSupplier clock) {
        return new NaiveDevice(T2, FrameLimits.forTier(2), 1048576, 256, 1024, 32768, 2, clock);
    }

    private static NaiveDevice t2() {
        return t2(() -> 0L);
    }

    private static NaiveDevice t3() {
        return new NaiveDevice(T3, FrameLimits.forTier(3), 4194304, 256, 1024, 65536, 2, () -> 0L);
    }

    // --------------------------------------------------------------------------------------------- §9.4 at T2

    @Test
    void workedFrameT2() {
        NaiveDevice d = t2();
        assertArrayEquals(new Object[] { 0x08000000 }, d.createTexture(16, 16, null));
        byte[] sprite = new byte[256];
        for (int i = 0; i < 256; i++) {
            sprite[i] = (byte) i;
        }
        assertArrayEquals(ok(), d.updateTexture(0x08000000, 0, 0, 16, 16, sprite));
        byte[] s = hex(S64);
        assertEquals(64, s.length);
        assertArrayEquals(ok(), d.submit(s));
        assertEquals(
            2,
            d.offered()
                .size(),
            "submit offers nothing (O2)");
        assertArrayEquals(new Object[] { 1L }, d.present());

        String frame = "FRAME e=1 id=1 segs=1 bytes=64 records=4 clear=64000 fill=5000 copy=256 set=156 crc=81B9385E";
        assertEquals(
            Arrays.asList(
                "TEX_CREATE e=1 tex=0x08000000 size=16x16 format=1",
                "TEX_UPDATE e=1 tex=0x08000000 at=0,0 size=16x16 len=256 crc=29058C73",
                frame),
            d.offered());
        assertEquals(Collections.singletonList(frame), d.tail(), "both resource records folded at once (O6)");
        assertEquals(
            Arrays.asList("consume 0.000976563", "consume 0.000976563", "consume 0.015686035", C_PRESENT_T2),
            d.budgetEvents());
        assertEquals(
            1,
            d.masters()
                .size());
        assertArrayEquals(
            sprite,
            d.masters()
                .get(0x08000000));
        assertEquals(256, d.vramUsed());
        assertEquals(0, d.queuedBytes());
        assertEquals(1, d.unconfirmedFrames());
        assertEquals(1, d.lastFrameId());
        assertEquals(0, d.confirmedFrameId());
        assertEquals(1, d.epoch());
        assertEquals(Collections.singletonList(0x08000000), d.liveHandles());

        // Before the readback, readPixels answers frame 0's cleared image
        Object[] r = d.readPixels(0, 0, 4, 1);
        assertArrayEquals(new byte[4], (byte[]) r[0]);
        assertEquals(0L, r[1]);

        byte[] px = pattern();
        d.onReadback(1, 1, px);
        px[650] = 99; // the device keeps a copy
        assertEquals(Collections.singletonList("done 1"), d.listenerEvents());
        assertEquals(Collections.<String>emptyList(), d.tail());
        assertEquals(1, d.confirmedFrameId());
        assertEquals(0, d.unconfirmedFrames());

        r = d.readPixels(10, 2, 16, 2);
        byte[] expected = new byte[32];
        for (int row = 0; row < 2; row++) {
            for (int c = 0; c < 16; c++) {
                expected[row * 16 + c] = (byte) (((2 + row) * 320 + 10 + c) % 251);
            }
        }
        assertArrayEquals(expected, (byte[]) r[0]);
        assertEquals(1L, r[1]);
        assertEquals("consume 0.000122070", last(d.budgetEvents()));
        assertArrayEquals(pattern(), d.confirmedPixels());
    }

    // ------------------------------------------------------------------------------------ 28:143-144's examples

    @Test
    void orderingExampleOne() {
        NaiveDevice d = t2();
        assertArrayEquals(new Object[] { 0x08000000 }, d.createTexture(4, 4, null));
        byte[] a = segment(blit(0x08000000, 0, 0, 4, 4, 10, 10, 0));
        byte[] b = segment(blit(0x08000000, 0, 0, 4, 4, 20, 10, 0));
        assertEquals(27, a.length);
        assertArrayEquals(ok(), d.submit(a));
        assertArrayEquals(ok(), d.updateTexture(0x08000000, 0, 0, 4, 4, data16()));
        assertArrayEquals(ok(), d.submit(b));
        assertArrayEquals(ok(), d.free(0x08000000));
        // O4: a validated frame never references a freed resource
        assertArrayEquals(err("8: BLIT: handle 0x08000000 is not live"), d.submit(a));
        assertArrayEquals(new Object[] { 1L }, d.present());

        String frame = "FRAME e=1 id=1 segs=2 bytes=54 records=2 clear=0 fill=0 copy=32 set=0 crc=9615C310";
        String free = "TEX_FREE e=1 tex=0x08000000";
        assertEquals(
            Arrays.asList(
                "TEX_CREATE e=1 tex=0x08000000 size=4x4 format=1",
                "TEX_UPDATE e=1 tex=0x08000000 at=0,0 size=4x4 len=16 crc=094C80F1",
                frame,
                free),
            d.offered());
        assertEquals(Arrays.asList(frame, free), d.tail());
        assertEquals(64, d.queuedBytes());
        assertArrayEquals(
            data16(),
            d.masters()
                .get(0x08000000),
            "the update folded at once");
        assertEquals(0, d.vramUsed());
        assertEquals(Collections.<Integer>emptyList(), d.liveHandles());
        assertEquals(
            Arrays.asList(C_4X4, C_27_T2, C_4X4, C_27_T2, C_27_T2, C_PRESENT_T2),
            d.budgetEvents(),
            "the rejected submit is charged");

        d.onReadback(1, 1, new byte[64000]);
        assertEquals(Collections.<String>emptyList(), d.tail());
        assertTrue(
            d.masters()
                .isEmpty(),
            "the TEX_FREE at the head folded after the frame");
        assertEquals(0, d.queuedBytes());
    }

    @Test
    void orderingExampleTwo() {
        NaiveDevice d = t2();
        assertArrayEquals(new Object[] { 0x08000000 }, d.createTexture(4, 4, null));
        assertArrayEquals(new Object[] { 1L }, d.present());
        assertArrayEquals(new Object[] { 2L }, d.present());
        d.onReadback(1, 2, new byte[64000]);
        assertArrayEquals(new Object[] { 3L }, d.present());
        assertArrayEquals(new Object[] { 4L }, d.present());
        d.onReadback(1, 4, new byte[64000]);
        assertArrayEquals(ok(), d.submit(segment(blit(0x08000000, 0, 0, 4, 4, 10, 10, 0))));
        assertArrayEquals(new Object[] { 5L }, d.present());
        assertArrayEquals(ok(), d.updateTexture(0x08000000, 0, 0, 4, 4, data16()));
        assertArrayEquals(new Object[] { 6L }, d.present());

        List<String> three = Arrays.asList(
            "FRAME e=1 id=5 segs=1 bytes=27 records=1 clear=0 fill=0 copy=16 set=0 crc=3E62DED2",
            "TEX_UPDATE e=1 tex=0x08000000 at=0,0 size=4x4 len=16 crc=094C80F1",
            "FRAME e=1 id=6" + EMPTY_FRAME);
        List<String> offered = d.offered();
        assertEquals(8, offered.size());
        assertEquals("TEX_CREATE e=1 tex=0x08000000 size=4x4 format=1", offered.get(0));
        for (int i = 1; i <= 4; i++) {
            assertEquals("FRAME e=1 id=" + i + EMPTY_FRAME, offered.get(i));
        }
        assertEquals(three, offered.subList(5, 8));
        assertEquals(three, d.tail());
        assertEquals(80, d.queuedBytes());
        assertArrayEquals(
            new byte[16],
            d.masters()
                .get(0x08000000),
            "frame 5 starts from the old contents");

        d.onReadback(1, 5, new byte[64000]);
        assertEquals(Collections.singletonList("FRAME e=1 id=6" + EMPTY_FRAME), d.tail());
        assertArrayEquals(
            data16(),
            d.masters()
                .get(0x08000000),
            "the TEX_UPDATE at the head folded");
        assertEquals(0, d.queuedBytes());
        assertEquals(Arrays.asList("done 2", "done 4", "done 5"), d.listenerEvents());
        assertEquals(5, d.confirmedFrameId());
    }

    // ------------------------------------------------------------------------------------------- reset, 28:150

    @Test
    void resetPer28_150() {
        NaiveDevice d = t2();
        assertArrayEquals(new Object[] { 1L }, d.present());
        d.onReadback(1, 1, pattern());
        assertArrayEquals(new Object[] { 0x08000000 }, d.createTexture(1, 1, null)); // P, slot 0
        assertArrayEquals(new Object[] { 0x08010000 }, d.createTexture(1, 1, null)); // Q, slot 1
        assertArrayEquals(ok(), d.free(0x08000000));
        cycle(d, 2, 2047, 0);
        assertArrayEquals(new Object[] { 0x08000001 }, d.createTexture(1, 1, null)); // R: slot 0 again, after Q
        assertEquals(Arrays.asList(0x08000001, 0x08010000), d.liveHandles(), "ascending slot");
        assertArrayEquals(ok(), d.submit(MIN));
        assertArrayEquals(new Object[] { 0x08020001 }, d.createTexture(1, 1, null)); // S
        assertArrayEquals(ok(), d.free(0x08020001));
        assertArrayEquals(new Object[] { 2L }, d.present());
        assertEquals(1, d.unconfirmedFrames());
        assertArrayEquals(ok(), d.submit(MIN));
        assertArrayEquals(new Object[] { 0x08030001 }, d.createTexture(1, 1, null)); // T, joins the tail
        assertArrayEquals(ok(), d.free(0x08030001)); // deferred
        assertEquals(Arrays.asList(0x08000001, 0x08010000), d.liveHandles());
        int before = d.offered()
            .size();
        assertEquals("TEX_CREATE e=1 tex=0x08030001 size=1x1 format=1", last(d.offered()));
        assertEquals(
            3,
            d.tail()
                .size());

        d.reset();
        assertEquals(2, d.epoch());
        List<String> offered = d.offered();
        assertEquals(before + 1, offered.size(), "the open frame and its deferred frees are not offered");
        assertEquals("RESET e=2 len=64000 crc=545CAB2F", last(offered));
        assertEquals(Collections.<String>emptyList(), d.tail());
        assertEquals(0, d.queuedBytes());
        assertEquals(0, d.vramUsed());
        assertTrue(
            d.masters()
                .isEmpty());
        assertEquals(Collections.<Integer>emptyList(), d.liveHandles());
        assertEquals(1, d.confirmedFrameId());
        assertEquals(2, d.lastFrameId());
        assertEquals(0, d.unconfirmedFrames());
        assertArrayEquals(pattern(), d.confirmedPixels());

        d.onReadback(1, 2, new byte[64000]); // the old epoch's frame: dropped
        d.tick();
        assertEquals(Collections.singletonList("done 1"), d.listenerEvents(), "discarded frames get no event");
        Object[] r = d.readPixels(0, 0, 4, 1);
        assertArrayEquals(Arrays.copyOf(pattern(), 4), (byte[]) r[0]);
        assertEquals(1L, r[1]);

        // Before the reset the queue was 4'..2047', 2'' (S), 3'' (T, freed at once though its TEX_FREE waited).
        // The reset freed R (slot 0, live after Q) and Q (slot 1) in ascending slot order, after the queued
        // slots: 4'..2047', 2'', 3'', 0'', 1'. Freeing in the order they became live would put 1' before 0''.
        cycle(d, 4, 2047, 1);
        assertArrayEquals(new Object[] { 0x08020002 }, d.createTexture(1, 1, null));
        assertArrayEquals(new Object[] { 0x08030002 }, d.createTexture(1, 1, null));
        assertArrayEquals(new Object[] { 0x08000002 }, d.createTexture(1, 1, null));
        assertArrayEquals(new Object[] { 0x08010001 }, d.createTexture(1, 1, null));
        assertArrayEquals(new Object[] { 3L }, d.present(), "frame ids are never reused");
        assertEquals("FRAME e=2 id=3" + EMPTY_FRAME, last(d.offered()));
        assertEquals(
            "TEX_CREATE e=2 tex=0x08010001 size=1x1 format=1",
            d.offered()
                .get(
                    d.offered()
                        .size() - 2));
    }

    // ---------------------------------------------------------------------------------------- brief rules 5, 6

    @Test
    void pendingFreesMakeCreateBusy() {
        NaiveDevice d = t2();
        assertArrayEquals(ok(), d.submit(MIN));
        for (int i = 0; i < 256; i++) {
            int h = 0x08000000 | (i << 16);
            assertArrayEquals(new Object[] { h }, d.createTexture(1, 1, null));
            assertArrayEquals(ok(), d.free(h));
        }
        int charges = d.budgetEvents()
            .size();
        assertArrayEquals(err("busy"), d.createTexture(1, 1, null));
        assertEquals(
            charges,
            d.budgetEvents()
                .size(),
            "busy is uncharged");
        assertEquals(0, d.vramUsed());
        assertEquals(
            256,
            d.offered()
                .size());
        assertArrayEquals(new Object[] { 1L }, d.present());
        List<String> offered = d.offered();
        assertEquals(256 + 1 + 256, offered.size());
        assertEquals(
            "FRAME e=1 id=1 segs=1 bytes=9 records=0 clear=0 fill=0 copy=0 set=0 crc=AB8EA542",
            offered.get(256));
        for (int i = 0; i < 256; i++) {
            assertEquals(
                String.format("TEX_CREATE e=1 tex=0x%08X size=1x1 format=1", 0x08000000 | (i << 16)),
                offered.get(i));
            assertEquals(String.format("TEX_FREE e=1 tex=0x%08X", 0x08000000 | (i << 16)), offered.get(257 + i));
        }
        assertEquals(
            257,
            d.tail()
                .size());
        assertEquals(256 * 64, d.queuedBytes());
        assertArrayEquals(new Object[] { 0x09000000 }, d.createTexture(1, 1, null));
        assertEquals(256 * 64 + 65, d.queuedBytes());

        // 256 live: "too many textures" comes first
        NaiveDevice full = t2();
        for (int i = 0; i < 256; i++) {
            full.createTexture(1, 1, null);
        }
        assertArrayEquals(err("too many textures"), full.createTexture(1, 1, null));

        // The VRAM half of rule 6, after "not enough video memory"
        NaiveDevice v = t2();
        assertArrayEquals(ok(), v.submit(MIN));
        assertThrows(NaiveDevice.LimitReached.class, () -> v.createTexture(1024, 512, null));
        assertArrayEquals(new Object[] { 0x08000000 }, v.createTexture(1024, 512, null));
        assertArrayEquals(ok(), v.free(0x08000000));
        assertThrows(NaiveDevice.LimitReached.class, () -> v.createTexture(1024, 512, null));
        assertArrayEquals(new Object[] { 0x08010000 }, v.createTexture(1024, 512, null));
        assertEquals(524288, v.vramUsed());
        assertArrayEquals(err("not enough video memory"), v.createTexture(1024, 513, null));
        assertArrayEquals(err("busy"), v.createTexture(1, 1, null));
        assertEquals(Arrays.asList(C_MIN_T2, "limit", "limit"), v.budgetEvents(), "c = 2, k = 1: no pause");
        assertArrayEquals(new Object[] { 1L }, v.present());
        assertArrayEquals(new Object[] { 0x08020000 }, v.createTexture(1, 1, null));
    }

    @Test
    void queuedBytesCheckOnlyBehindATail() {
        NaiveDevice d = t2();
        assertThrows(NaiveDevice.LimitReached.class, () -> d.createTexture(1024, 1024, null));
        assertEquals(Collections.<String>emptyList(), d.offered(), "no effect before the retry");
        assertArrayEquals(new Object[] { 0x08000000 }, d.createTexture(1024, 1024, null));
        assertEquals(Arrays.asList("limit", "pause 0.100000000"), d.budgetEvents());
        assertEquals(Collections.singletonList("TEX_CREATE e=1 tex=0x08000000 size=1024x1024 format=1"), d.offered());
        assertEquals(Collections.<String>emptyList(), d.tail());
        assertEquals(0, d.queuedBytes());
        assertEquals(1048576, d.vramUsed());
        assertArrayEquals(
            new byte[1048576],
            d.masters()
                .get(0x08000000));

        // Behind an unconfirmed frame the same update is busy; a smaller one queues
        assertArrayEquals(new Object[] { 1L }, d.present());
        assertArrayEquals(err("busy"), d.updateTexture(0x08000000, 0, 0, 1024, 1024, new byte[1048576]));
        assertThrows(
            NaiveDevice.LimitReached.class,
            () -> d.updateTexture(0x08000000, 0, 0, 1024, 1000, new byte[1024000]));
        assertArrayEquals(ok(), d.updateTexture(0x08000000, 0, 0, 1024, 1000, new byte[1024000]));
        assertEquals("pause 0.095312500", last(d.budgetEvents()));
        assertEquals(1024064, d.queuedBytes());
        assertArrayEquals(ok(), d.free(0x08000000));
        assertEquals(1024128, d.queuedBytes());
        assertEquals(
            3,
            d.tail()
                .size());

        NaiveDevice e = t2();
        assertArrayEquals(new Object[] { 1L }, e.present());
        assertArrayEquals(err("busy"), e.createTexture(1024, 1024, null));
        assertEquals(Collections.singletonList(C_PRESENT_T2), e.budgetEvents());
        assertEquals(0, e.vramUsed());
        e.onReadback(1, 1, new byte[64000]);
        assertThrows(NaiveDevice.LimitReached.class, () -> e.createTexture(1024, 1024, null));
        assertArrayEquals(new Object[] { 0x08000000 }, e.createTexture(1024, 1024, null));
    }

    // ------------------------------------------------------------------------------------------- two-phase

    @Test
    void t3LargeCreateTwoPhase() {
        NaiveDevice d = t3();
        assertThrows(NaiveDevice.LimitReached.class, () -> d.createTexture(1024, 1024, "rgb565"));
        assertEquals(Collections.singletonList("limit"), d.budgetEvents());
        assertArrayEquals(new Object[] { 0x08000000 }, d.createTexture(1024, 1024, "rgb565"));
        assertEquals(Arrays.asList("limit", "pause 0.166666667"), d.budgetEvents());
        assertEquals(2097152, d.vramUsed());
        assertArrayEquals(err("unsupported format"), d.createTexture(1, 1, "index8"));
        assertArrayEquals(ok(), d.submit(MIN));
        assertArrayEquals(new Object[] { 1L }, d.present());
        assertEquals(
            Arrays.asList("limit", "pause 0.166666667", "consume 0.007821083", "consume 0.031250000"),
            d.budgetEvents());
    }

    @Test
    void twoPhaseFlagIsSharedAndCleared() {
        // A failed check clears the flag, so the next attempt is a first attempt again
        NaiveDevice d = t2();
        assertThrows(NaiveDevice.LimitReached.class, () -> d.createTexture(1024, 1024, null));
        assertArrayEquals(err("invalid texture size"), d.createTexture(2000, 1, null));
        assertThrows(NaiveDevice.LimitReached.class, () -> d.createTexture(1024, 1024, null));
        assertArrayEquals(new Object[] { 0x08000000 }, d.createTexture(1024, 1024, null));
        assertEquals(Arrays.asList("limit", "limit", "pause 0.100000000"), d.budgetEvents());

        // The plain-consume path (c <= k) clears it too
        NaiveDevice e = t2();
        assertArrayEquals(new Object[] { 0x08000000 }, e.createTexture(512, 512, null));
        assertThrows(NaiveDevice.LimitReached.class, () -> e.createTexture(1024, 768, null));
        assertArrayEquals(ok(), e.updateTexture(0x08000000, 0, 0, 512, 512, new byte[262144]));
        assertThrows(NaiveDevice.LimitReached.class, () -> e.createTexture(1024, 768, null));
        assertArrayEquals(new Object[] { 0x08010000 }, e.createTexture(1024, 768, null));
        assertEquals(
            Arrays.asList("consume 1.000000000", "limit", "consume 1.000000000", "limit", "pause 0.050000000"),
            e.budgetEvents());

        // One flag per device: a create's "limit" lets the next large update through as the retry
        NaiveDevice f = t2();
        assertThrows(NaiveDevice.LimitReached.class, () -> f.createTexture(1024, 512, null));
        assertArrayEquals(new Object[] { 0x08000000 }, f.createTexture(1024, 512, null));
        assertThrows(NaiveDevice.LimitReached.class, () -> f.createTexture(1024, 512, null));
        assertArrayEquals(ok(), f.updateTexture(0x08000000, 0, 0, 1024, 512, new byte[524288]));
        assertThrows(NaiveDevice.LimitReached.class, () -> f.createTexture(1024, 512, null));
        assertEquals(Arrays.asList("limit", "limit", "limit"), f.budgetEvents());
        assertEquals(Collections.singletonList(0x08000000), f.liveHandles());
    }

    // ------------------------------------------------------------------------------------ dropped and done

    @Test
    void droppedAndDoneOnManualClock() {
        long[] now = { 0 };
        NaiveDevice d = t2(() -> now[0]);
        assertArrayEquals(new Object[] { 1L }, d.present());
        now[0] = 999999999L;
        d.tick();
        assertEquals(Collections.<String>emptyList(), d.listenerEvents());
        now[0] = 1000000000L;
        d.tick();
        d.tick();
        assertEquals(Collections.singletonList("dropped 1"), d.listenerEvents());
        now[0] = 1500000000L;
        assertArrayEquals(new Object[] { 2L }, d.present());
        now[0] = 2400000000L;
        d.tick();
        assertEquals(Collections.singletonList("dropped 1"), d.listenerEvents());
        now[0] = 2500000000L;
        d.tick();
        assertEquals(Arrays.asList("dropped 1", "dropped 2"), d.listenerEvents());
        d.onReadback(1, 2, new byte[64000]);
        assertEquals(
            Arrays.asList("dropped 1", "dropped 2", "done 2"),
            d.listenerEvents(),
            "frame 1 is confirmed by the same fold without an event");
        d.onReadback(1, 1, pattern()); // <= K
        d.onReadback(1, 3, pattern()); // not in the tail
        d.onReadback(2, 3, pattern()); // another epoch
        assertArrayEquals(new byte[64000], d.confirmedPixels());

        now[0] = 3000000000L;
        assertArrayEquals(new Object[] { 3L }, d.present());
        now[0] = 3200000000L;
        assertArrayEquals(new Object[] { 4L }, d.present());
        now[0] = 4100000000L;
        d.tick();
        d.onReadback(0, 3, pattern());
        d.onReadback(1, 3, pattern());
        now[0] = 4200000000L;
        d.tick();
        d.onReadback(1, 4, new byte[64000]);
        d.tick();
        assertEquals(
            Arrays.asList("dropped 1", "dropped 2", "done 2", "dropped 3", "done 3", "dropped 4", "done 4"),
            d.listenerEvents());
        assertEquals(4, d.confirmedFrameId());
    }

    // ------------------------------------------------------------------------------------- present statuses

    @Test
    void presentStatusOrder() {
        NaiveDevice d = t2();
        assertArrayEquals(new Object[] { 1L }, d.present());
        assertArrayEquals(new Object[] { 2L }, d.present());
        assertArrayEquals(ok(), d.submit(MIN));
        assertArrayEquals(new Object[] { 0x08000000 }, d.createTexture(1, 1, null));
        assertArrayEquals(ok(), d.free(0x08000000));
        assertArrayEquals(err("busy"), d.present());
        d.setHostStalled(true);
        assertArrayEquals(err("host render stalled"), d.present());
        d.setHostAvailable(false);
        assertArrayEquals(err("no renderer"), d.present());
        String create = "TEX_CREATE e=1 tex=0x08000000 size=1x1 format=1";
        String free = "TEX_FREE e=1 tex=0x08000000";
        List<String> expected = Arrays
            .asList("FRAME e=1 id=1" + EMPTY_FRAME, "FRAME e=1 id=2" + EMPTY_FRAME, create, free);
        assertEquals(expected, d.offered());
        assertEquals(expected, d.tail());
        assertEquals(65 + 64, d.queuedBytes());
        d.setHostAvailable(true);
        assertArrayEquals(err("host render stalled"), d.present());
        d.setHostStalled(false);
        assertArrayEquals(err("busy"), d.present());
        d.onReadback(1, 2, new byte[64000]);
        assertEquals(Collections.<String>emptyList(), d.tail());
        assertTrue(
            d.masters()
                .isEmpty());
        assertEquals(0, d.queuedBytes());
        assertArrayEquals(new Object[] { 3L }, d.present());
        assertEquals("FRAME e=1 id=3" + EMPTY_FRAME, last(d.offered()), "the discarded frame is gone");
        assertEquals(
            Arrays.asList(
                C_PRESENT_T2,
                C_PRESENT_T2,
                C_MIN_T2,
                C_1X1,
                C_PRESENT_T2,
                C_PRESENT_T2,
                C_PRESENT_T2,
                C_PRESENT_T2,
                C_PRESENT_T2,
                C_PRESENT_T2),
            d.budgetEvents());

        // On an empty tail the discarded frame's deferred frees fold at once
        NaiveDevice e = t2();
        e.setHostAvailable(false);
        assertArrayEquals(ok(), e.submit(MIN));
        assertArrayEquals(new Object[] { 0x08000000 }, e.createTexture(1, 1, null));
        assertArrayEquals(ok(), e.free(0x08000000));
        assertArrayEquals(err("no renderer"), e.present());
        assertEquals(Arrays.asList(create, free), e.offered());
        assertEquals(Collections.<String>emptyList(), e.tail());
        assertTrue(
            e.masters()
                .isEmpty());
        assertEquals(0, e.lastFrameId());
        Object[] r = e.readPixels(0, 0, 1, 1);
        assertEquals(0L, r[1]);
    }

    // -------------------------------------------------------------------------------------- handle messages

    @Test
    void handleMessagesMatchTheValidator() {
        assertArrayEquals(err("invalid handle 0x00000000"), t2().free(0));
        assertArrayEquals(err("invalid handle 0xFFFFFFFF"), t2().free(-1));
        assertArrayEquals(err("handle 0x10000000 is a buffer, expected a texture"), t2().free(0x10000000));
        assertArrayEquals(err("handle 0x40000000 has an unknown type"), t2().free(0x40000000));
        assertArrayEquals(err("handle 0x08000001 is not live"), t2().free(0x08000001));
        int[] handles = { 0, -1, Integer.MIN_VALUE, 0x00001234, 0x07FFFFFF, 0x08000000, 0x08000001, 0x0FFFFFFF,
            0x10000000, 0x18000000, 0x20000000, 0x28000000, 0x30000000, 0x38000000, 0x40000000, 0x7FFFFFFF };
        MapLiveView view = new MapLiveView(T2, FrameLimits.forTier(2));
        for (int h : handles) {
            NaiveDevice d = t2();
            Object[] free = d.free(h);
            assertNull(free[0]);
            String stream = Validator.validate(segment(blit(h, 0, 0, 1, 1, 0, 0, 0)), view)
                .error()
                .message();
            assertEquals(stream, "8: BLIT: " + free[1], String.format("handle 0x%08X", h));
            assertArrayEquals(free, d.updateTexture(h, 0, 0, 1, 1, new byte[1]));
            assertEquals(Collections.<String>emptyList(), d.budgetEvents());
        }
        NaiveDevice d = t2();
        assertArrayEquals(new Object[] { 0x08000000 }, d.createTexture(1, 1, null));
        assertArrayEquals(ok(), d.free(0x08000000));
        assertArrayEquals(err("handle 0x08000000 is not live"), d.free(0x08000000));
        assertArrayEquals(new Object[] { 0x08010000 }, d.createTexture(1, 1, null), "a freed slot goes to the tail");
    }

    // ---------------------------------------------------------------------------------------------- getCaps

    @Test
    void getCapsKeysAndValues() {
        NaiveDevice d = t2();
        Map<String, Object> caps = d.getCaps();
        List<String> keys = Arrays.asList(
            "tier",
            "width",
            "height",
            "format",
            "commandFormat",
            "maxSubmitBytes",
            "maxFrameBytes",
            "maxFrameRecords",
            "maxFrameFill",
            "maxTextBytes",
            "maxUnconfirmedFrames",
            "maxTextureSize",
            "maxTextures",
            "vramBytes",
            "maxReadbackBytes");
        assertEquals(keys, new ArrayList<>(caps.keySet()));
        assertEquals(
            Arrays.<Object>asList(
                2,
                320,
                200,
                "index8",
                1,
                65536,
                131072,
                4096,
                1024000,
                1024,
                2,
                1024,
                256,
                1048576,
                32768),
            new ArrayList<>(caps.values()));
        Map<String, Object> caps3 = t3().getCaps();
        assertEquals(keys, new ArrayList<>(caps3.keySet()));
        assertEquals(
            Arrays.<Object>asList(
                3,
                640,
                400,
                "rgb565",
                1,
                65536,
                262144,
                4096,
                4096000,
                1024,
                2,
                1024,
                256,
                4194304,
                65536),
            new ArrayList<>(caps3.values()));
        assertEquals(Collections.<String>emptyList(), d.budgetEvents());
    }

    // ------------------------------------------------------------------------------------- callback checks

    @Test
    void callbackChecksAndRowWiseUpdates() {
        NaiveDevice d = t2();
        int max = Integer.MAX_VALUE, min = Integer.MIN_VALUE;
        int[][] bad = { { -1, 0, 1, 1 }, { 0, 0, 0, 1 }, { 320, 0, 1, 1 }, { 0, 200, 1, 1 }, { 0, 0, 321, 1 },
            { 0, 0, 1, 201 }, { max, 0, max, 1 }, { 0, max, 1, max }, { 0, 0, max, max }, { min, 0, 1, 1 },
            { 1, 0, 320, 1 } };
        for (int[] a : bad) {
            assertArrayEquals(err("invalid region"), d.readPixels(a[0], a[1], a[2], a[3]), Arrays.toString(a));
        }
        assertArrayEquals(err("region exceeds 32768 bytes per call"), d.readPixels(0, 0, 320, 103));
        assertEquals(Collections.<String>emptyList(), d.budgetEvents());
        assertEquals(1, ((byte[]) d.readPixels(319, 199, 1, 1)[0]).length);
        assertEquals(32640, ((byte[]) d.readPixels(0, 0, 320, 102)[0]).length);
        assertEquals(32768, ((byte[]) d.readPixels(64, 72, 256, 128)[0]).length, "exactly the cap");
        assertArrayEquals(err("invalid region"), d.readPixels(65, 72, 256, 128));
        assertArrayEquals(err("region exceeds 32768 bytes per call"), d.readPixels(63, 71, 257, 128));
        assertEquals(Arrays.asList(C_1X1, "consume 0.124511719", "consume 0.125000000"), d.budgetEvents());

        int[][] badSizes = { { 0, 1 }, { 1, 0 }, { 1025, 1 }, { 1, 1025 }, { max, max }, { min, 1 }, { -1, -1 } };
        for (int[] a : badSizes) {
            assertArrayEquals(err("invalid texture size"), d.createTexture(a[0], a[1], null), Arrays.toString(a));
        }
        assertArrayEquals(err("invalid texture size"), d.createTexture(0, 1, "bogus"), "size before format");
        assertArrayEquals(err("unsupported format"), d.createTexture(1, 1, "rgb565"));
        assertArrayEquals(err("unsupported format"), d.createTexture(1, 1, ""));
        assertArrayEquals(err("unsupported format"), d.createTexture(1, 1, "INDEX8"));
        assertEquals(
            3,
            d.budgetEvents()
                .size(),
            "failed checks charge nothing");
        assertArrayEquals(new Object[] { 0x08000000 }, d.createTexture(1, 1, "index8"));
        assertArrayEquals(new Object[] { 0x08010000 }, d.createTexture(16, 16, null));

        int[][] badRegions = { { 1, 0, 1, 1 }, { 0, 1, 1, 1 }, { 0, 0, 2, 1 }, { -1, 0, 1, 1 }, { max, 0, max, 1 },
            { 0, 0, max, max }, { min, 0, 1, 1 } };
        for (int[] a : badRegions) {
            assertArrayEquals(
                err("invalid texture region"),
                d.updateTexture(0x08000000, a[0], a[1], a[2], a[3], new byte[1]),
                Arrays.toString(a));
        }
        assertArrayEquals(err("data must be 1 bytes"), d.updateTexture(0x08000000, 0, 0, 1, 1, new byte[2]));
        assertArrayEquals(err("data must be 1 bytes"), d.updateTexture(0x08000000, 0, 0, 1, 1, new byte[0]));
        assertArrayEquals(err("data must be 144 bytes"), d.updateTexture(0x08010000, 4, 4, 12, 12, new byte[143]));
        assertArrayEquals(err("invalid texture region"), d.updateTexture(0x08010000, 4, 4, 13, 1, new byte[13]));
        assertArrayEquals(err("invalid texture region"), d.updateTexture(0x08010000, 16, 0, 1, 1, new byte[1]));
        assertArrayEquals(err("invalid texture region"), d.updateTexture(0x08010000, 0, 16, 1, 1, new byte[1]));
        int charges = d.budgetEvents()
            .size();
        assertArrayEquals(ok(), d.updateTexture(0x08010000, 2, 3, 2, 2, new byte[] { 1, 2, 3, 4 }));
        assertArrayEquals(ok(), d.updateTexture(0x08010000, 15, 15, 1, 1, new byte[] { 9 }));
        assertEquals(
            charges + 2,
            d.budgetEvents()
                .size());
        byte[] m = d.masters()
            .get(0x08010000);
        byte[] expected = new byte[256];
        expected[50] = 1;
        expected[51] = 2;
        expected[66] = 3;
        expected[67] = 4;
        expected[255] = 9;
        assertArrayEquals(expected, m);
        assertEquals(1 + 256, d.vramUsed());

        // rgb565: 2 bytes per texel, rows of w·2 bytes
        NaiveDevice e = t3();
        assertArrayEquals(new Object[] { 0x08000000 }, e.createTexture(4, 2, null));
        assertArrayEquals(err("data must be 4 bytes"), e.updateTexture(0x08000000, 1, 1, 2, 1, new byte[2]));
        assertArrayEquals(ok(), e.updateTexture(0x08000000, 1, 1, 2, 1, new byte[] { 5, 6, 7, 8 }));
        byte[] m3 = new byte[16];
        m3[10] = 5;
        m3[11] = 6;
        m3[12] = 7;
        m3[13] = 8;
        assertArrayEquals(
            m3,
            e.masters()
                .get(0x08000000));
        assertEquals(16, e.vramUsed());
        assertEquals(
            "TEX_CREATE e=1 tex=0x08000000 size=4x2 format=2",
            e.offered()
                .get(0));
        assertArrayEquals(err("region exceeds 65536 bytes per call"), e.readPixels(0, 0, 640, 52));
        assertEquals(65280, ((byte[]) e.readPixels(0, 0, 640, 51)[0]).length);
    }

    // ===================================================================================================== helpers

    /** Creates and frees one 1×1 texture per slot from..to, each expected at the given generation. */
    private static void cycle(NaiveDevice d, int from, int to, int generation) {
        for (int slot = from; slot <= to; slot++) {
            int h = 0x08000000 | (slot << 16) | generation;
            assertArrayEquals(new Object[] { h }, d.createTexture(1, 1, null), String.format("0x%08X", h));
            assertArrayEquals(ok(), d.free(h));
        }
    }

    private static byte[] pattern() {
        byte[] px = new byte[64000];
        for (int i = 0; i < px.length; i++) {
            px[i] = (byte) (i % 251);
        }
        return px;
    }

    private static byte[] data16() {
        byte[] b = new byte[16];
        for (int i = 0; i < 16; i++) {
            b[i] = (byte) (i + 1);
        }
        return b;
    }

    private static byte[] segment(byte[]... records) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x4F);
        out.write(0x47);
        out.write(0x50);
        out.write(0x55);
        out.write(1);
        out.write(0);
        out.write(0);
        out.write(0);
        for (byte[] r : records) {
            out.write(r, 0, r.length);
        }
        out.write(0xFF);
        return out.toByteArray();
    }

    /** 28 §5.2: 0x31, tex u32, sx sy w h dx dy i16, flags u8; little-endian. */
    private static byte[] blit(int tex, int sx, int sy, int w, int h, int dx, int dy, int flags) {
        return new byte[] { 0x31, (byte) tex, (byte) (tex >>> 8), (byte) (tex >>> 16), (byte) (tex >>> 24), (byte) sx,
            (byte) (sx >> 8), (byte) sy, (byte) (sy >> 8), (byte) w, (byte) (w >> 8), (byte) h, (byte) (h >> 8),
            (byte) dx, (byte) (dx >> 8), (byte) dy, (byte) (dy >> 8), (byte) flags };
    }

    private static byte[] hex(String s) {
        String[] parts = s.trim()
            .split("\\s+");
        byte[] b = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            b[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return b;
    }

    private static Object[] ok() {
        return new Object[] { Boolean.TRUE };
    }

    private static Object[] err(String message) {
        return new Object[] { null, message };
    }

    private static <T> T last(List<T> list) {
        return list.get(list.size() - 1);
    }
}
