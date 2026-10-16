package io.github.mindbound.opengpu.core.fixtures.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.device.DeviceCaps;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FakeRenderHost;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.ReadbackSink;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.RecordRenderer;
import io.github.mindbound.opengpu.core.render.RenderHost;
import io.github.mindbound.opengpu.core.render.ResetRecord;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;

/**
 * The shadow oracle and the tee (S8 plan §2.4 with amendment E, B5): each record runs on a per-opening oracle when it
 * is offered and each FRAME's surface is kept by (epoch, frame id); a {@code RESET} replaces the surface and drops the
 * textures in its new epoch; the checking sink reports the first differing pixel and drops every frame its readback
 * confirms (21:87); the tee offers to the shadow first and checks the primary's readbacks before the device sees them;
 * release and a later open start afresh; and the test-aid strictness. Expected surfaces are worked out by hand.
 */
class ShadowOracleTest {

    private static final DeviceKey KEY = new DeviceKey(1, 1);
    private static final DeviceKey OTHER = new DeviceKey(1, 2);
    private static final SurfaceDesc S = new SurfaceDesc(0, 4, 2, Formats.INDEX8);
    private static final int TEX = 0x08000000;
    private static final ReadbackSink NONE = (session, deviceId, epoch, frameId, pixels) -> {};

    @Test
    void eachRecordRunsAtItsOfferAndEachFramesSurfaceIsKept() {
        ShadowOracle shadow = new ShadowOracle(SyntheticGlyphs.TABLE);
        shadow.open(KEY, S, NONE);
        shadow.offer(KEY, new TexCreateRecord(1, TEX, 2, 1, Formats.INDEX8));
        shadow.offer(KEY, new TexUpdateRecord(1, TEX, 0, 0, 2, 1, bytes(5, 6)));
        shadow.offer(KEY, frame(1, 1, segment(blit(TEX, 0, 0, 2, 1, 0, 0, 0))));
        // The update after frame 1 changes only what frame 2 sees (28:144)
        shadow.offer(KEY, new TexUpdateRecord(1, TEX, 0, 0, 2, 1, bytes(7, 8)));
        shadow.offer(KEY, frame(1, 2, segment(blit(TEX, 0, 0, 2, 1, 2, 1, 0))));
        // Rows top first: frame 1 is 5 6 0 0 / 0 0 0 0; frame 2 adds 7 8 at (2, 1)
        Assertions.assertArrayEquals(bytes(5, 6, 0, 0, 0, 0, 0, 0), shadow.pixelsAfter(KEY, 1, 1));
        Assertions.assertArrayEquals(bytes(5, 6, 0, 0, 0, 0, 7, 8), shadow.pixelsAfter(KEY, 1, 2));
        Assertions.assertArrayEquals(bytes(5, 6, 0, 0, 0, 0, 7, 8), shadow.pixelsNow(KEY));
        Assertions.assertEquals(2, shadow.framesKept(KEY));
        byte[] copy = shadow.pixelsAfter(KEY, 1, 1);
        copy[0] = 99;
        Assertions.assertEquals(5, shadow.pixelsAfter(KEY, 1, 1)[0], "a fresh copy each time");
        Assertions.assertThrows(IllegalArgumentException.class, () -> shadow.pixelsAfter(KEY, 1, 3));
        Assertions.assertThrows(IllegalArgumentException.class, () -> shadow.pixelsAfter(KEY, 2, 1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> shadow.pixelsAfter(OTHER, 1, 1));
        Assertions.assertEquals(
            7,
            shadow.oracle(KEY)
                .texel(TEX, 0, 0));
    }

    @Test
    void aResetReplacesTheSurfaceAndDropsTheTexturesInItsEpoch() {
        ShadowOracle shadow = new ShadowOracle(SyntheticGlyphs.TABLE);
        shadow.open(KEY, S, NONE);
        shadow.offer(KEY, new TexCreateRecord(1, TEX, 1, 1, Formats.INDEX8));
        shadow.offer(KEY, frame(1, 1, segment(clear(3))));
        // K's pixels in the 28 §5.7 layout
        byte[] k = bytes(1, 2, 3, 4, 5, 6, 7, 8);
        shadow.offer(KEY, new ResetRecord(2, k));
        Assertions.assertEquals(
            0,
            shadow.oracle(KEY)
                .textureCount());
        shadow.offer(KEY, frame(2, 2, segment(fill(1, 0, 2, 2, 9))));
        Assertions.assertArrayEquals(bytes(1, 9, 9, 4, 5, 9, 9, 8), shadow.pixelsAfter(KEY, 2, 2));
        // Epoch 1's frame stays until a readback confirms past it
        Assertions.assertArrayEquals(bytes(3, 3, 3, 3, 3, 3, 3, 3), shadow.pixelsAfter(KEY, 1, 1));
        List<String> failures = new ArrayList<>();
        shadow.checking(KEY, NONE, failures)
            .onReadback(1, 1, 2, 2, bytes(1, 9, 9, 4, 5, 9, 9, 8));
        Assertions.assertEquals(Collections.emptyList(), failures);
        Assertions.assertEquals(0, shadow.framesKept(KEY), "a newer epoch's readback drops the older epochs");
    }

    @Test
    void theCheckingSinkReportsTheFirstDifferenceAndDropsTheFramesItConfirms() {
        ShadowOracle shadow = new ShadowOracle(SyntheticGlyphs.TABLE);
        shadow.open(KEY, S, NONE);
        for (int id = 1; id <= 3; id++) {
            shadow.offer(KEY, frame(1, id, segment(fill(0, 0, id, 1, id))));
        }
        List<String> failures = new ArrayList<>();
        final List<String> forwarded = new ArrayList<>();
        ReadbackSink next = (session, deviceId, epoch, frameId, pixels) -> forwarded
            .add(session + ":" + deviceId + " e" + epoch + " #" + frameId);
        ReadbackSink sink = shadow.checking(KEY, next, failures);
        // Frame 2: 2 2 0 0 / 0 0 0 0, right; it confirms frame 1 as well (21:87)
        sink.onReadback(1, 1, 1, 2, bytes(2, 2, 0, 0, 0, 0, 0, 0));
        Assertions.assertEquals(Collections.emptyList(), failures);
        Assertions.assertEquals(1, shadow.framesKept(KEY), "frames 1 and 2 dropped, 3 kept");
        Assertions.assertEquals(1, shadow.readbacksChecked(KEY));
        // Frame 1 after frame 2: no longer kept
        sink.onReadback(1, 1, 1, 1, bytes(1, 0, 0, 0, 0, 0, 0, 0));
        Assertions.assertEquals(1, failures.size());
        Assertions.assertTrue(
            failures.get(0)
                .contains("e1 #1: no such frame"),
            failures.get(0));
        // Frame 3, wrong at (1, 1): 3 3 3 0 / 0 0 0 0 expected
        sink.onReadback(1, 1, 1, 3, bytes(3, 3, 3, 0, 0, 0x42, 0, 0));
        Assertions.assertEquals(2, failures.size());
        Assertions.assertEquals(
            "DeviceKey[1:1] e1 #3: pixel (1, 1) of index8 is 0x42, expected 0x0; row 1 from x 0: actual [0 42 0 0],"
                + " expected [0 0 0 0]",
            failures.get(1));
        Assertions.assertEquals(0, shadow.framesKept(KEY));
        // Another key's readback and a wrong length are failures too
        shadow.offer(KEY, frame(1, 4, segment(clear(1))));
        sink.onReadback(1, 2, 1, 4, new byte[8]);
        Assertions.assertTrue(
            failures.get(2)
                .contains("for 1:2 reached this key's sink"),
            failures.get(2));
        sink.onReadback(1, 1, 1, 4, new byte[7]);
        Assertions.assertTrue(
            failures.get(3)
                .contains("7 bytes, expected 8"),
            failures.get(3));
        // Every call is forwarded, failing or not
        Assertions
            .assertEquals(Arrays.asList("1:1 e1 #2", "1:1 e1 #1", "1:1 e1 #3", "1:2 e1 #4", "1:1 e1 #4"), forwarded);
        // rgb565 compares LE u16 values
        SurfaceDesc rgb = new SurfaceDesc(0, 2, 1, Formats.RGB565);
        Assertions.assertNull(ShadowOracle.firstDifference("x", rgb, bytes(0x34, 0x12, 0, 0), bytes(0x34, 0x12, 0, 0)));
        Assertions.assertEquals(
            "x: pixel (0, 0) of rgb565 is 0x1235, expected 0x1234; row 0 from x 0: actual [1235 0], expected [1234 0]",
            ShadowOracle.firstDifference("x", rgb, bytes(0x34, 0x12, 0, 0), bytes(0x35, 0x12, 0, 0)));
    }

    @Test
    void theTeeOffersToTheShadowFirstAndChecksThePrimarysReadbacksBeforeTheDevice() {
        final ShadowOracle shadow = new ShadowOracle(SyntheticGlyphs.TABLE);
        final List<String> order = new ArrayList<>();
        // A primary that runs on the oracle renderer and looks into the shadow at each FRAME it is offered
        final FakeRenderHost fake = new FakeRenderHost(new OracleRecordRenderer());
        RenderHost primary = new RenderHost() {

            @Override
            public boolean available() {
                return fake.available();
            }

            @Override
            public boolean stalled() {
                return fake.stalled();
            }

            @Override
            public void open(DeviceKey key, SurfaceDesc surface, ReadbackSink sink) {
                // Throws unless the shadow has the key open already
                shadow.pixelsNow(key);
                order.add("primary open after the shadow's");
                fake.open(key, surface, sink);
            }

            @Override
            public void offer(DeviceKey key, Record record) {
                if (record.kind() == Record.FRAME) {
                    FrameRecord f = (FrameRecord) record;
                    shadow.pixelsAfter(key, f.epoch, f.frameId);
                    order.add("primary offer #" + f.frameId + " after the shadow's");
                }
                fake.offer(key, record);
            }

            @Override
            public void release(DeviceKey key) {
                order.add("primary release");
                fake.release(key);
            }

            @Override
            public void bindDisplay(String displayId, DeviceKey key) {
                fake.bindDisplay(displayId, key);
            }

            @Override
            public void releaseSession(long session) {
                fake.releaseSession(session);
            }
        };
        List<String> failures = Collections.synchronizedList(new ArrayList<String>());
        TeeRenderHost tee = new TeeRenderHost(primary, shadow, failures);
        Assertions.assertSame(primary, tee.primary());
        Assertions.assertSame(shadow, tee.shadow());
        Assertions.assertSame(failures, tee.failures());
        DeviceRig rig = DeviceRig.on(caps(), tee, fake::step);
        int t = rig.create(2, 2);
        rig.update(t, 0, 0, 2, 2, bytes(1, 2, 3, 4));
        rig.submit(segment(clear(7), blit(t, 0, 0, 2, 2, 1, 0, 1)));
        long id = rig.present();
        rig.step();
        Assertions.assertEquals(Collections.emptyList(), failures);
        Assertions.assertEquals(id, rig.snapshot().confirmedFrameId, "the checked readback reached the device");
        Assertions.assertArrayEquals(shadow.pixelsNow(DeviceRig.KEY), rig.screen());
        Assertions.assertEquals(1, shadow.readbacksChecked(DeviceRig.KEY));
        Assertions.assertEquals(
            Arrays.asList("primary open after the shadow's", "primary offer #1 after the shadow's"),
            order);
        // available and stalled are the primary's
        fake.setAvailable(false);
        fake.setStalled(true);
        Assertions.assertFalse(tee.available());
        Assertions.assertTrue(tee.stalled());
        rig.device.release();
        Assertions.assertEquals("primary release", order.get(order.size() - 1));
        Assertions.assertThrows(IllegalStateException.class, () -> shadow.checking(DeviceRig.KEY, NONE, failures));

        // A primary whose surfaces stay zero: the tee reports its readback
        ShadowOracle second = new ShadowOracle(SyntheticGlyphs.TABLE);
        FakeRenderHost zeros = new FakeRenderHost(RecordRenderer.zeros());
        DeviceRig wrong = DeviceRig.on(caps(), new TeeRenderHost(zeros, second, failures), zeros::step);
        wrong.submit(segment(fill(0, 0, 1, 1, 5)));
        wrong.present();
        wrong.step();
        Assertions.assertEquals(1, failures.size(), failures.toString());
        Assertions.assertTrue(
            failures.get(0)
                .startsWith("DeviceKey[1:1] e1 #1: pixel (0, 0) of index8 is 0x0, expected 0x5"),
            failures.get(0));
    }

    @Test
    void releaseAndALaterOpenStartAfreshAndLateReadbacksAreForwardedUnchecked() {
        ShadowOracle shadow = new ShadowOracle(SyntheticGlyphs.TABLE);
        shadow.open(KEY, S, NONE);
        shadow.offer(KEY, frame(1, 1, segment(clear(4))));
        List<String> failures = new ArrayList<>();
        final int[] forwarded = { 0 };
        ReadbackSink old = shadow.checking(KEY, (s, d, e, f, p) -> forwarded[0]++, failures);
        shadow.release(KEY);
        Assertions.assertEquals(0, shadow.framesKept(KEY));
        Assertions.assertThrows(IllegalStateException.class, () -> shadow.offer(KEY, frame(1, 2)));
        shadow.release(KEY);
        shadow.open(KEY, S, NONE);
        Assertions.assertArrayEquals(new byte[8], shadow.pixelsNow(KEY), "a new opening starts cleared (28:199)");
        old.onReadback(1, 1, 1, 1, new byte[8]);
        Assertions.assertEquals(Collections.emptyList(), failures, "the old opening's late readback is not checked");
        Assertions.assertEquals(1, forwarded[0]);
        Assertions.assertEquals(1, shadow.lateReadbacks());
        // releaseSession releases the session's keys only
        shadow.open(OTHER, S, NONE);
        shadow.open(new DeviceKey(2, 1), S, NONE);
        shadow.releaseSession(1);
        Assertions.assertThrows(IllegalStateException.class, () -> shadow.offer(OTHER, frame(1, 1)));
        Assertions.assertDoesNotThrow(() -> shadow.offer(new DeviceKey(2, 1), frame(1, 1)));
    }

    @Test
    void itIsStrictAsATestAid() {
        ShadowOracle shadow = new ShadowOracle(SyntheticGlyphs.TABLE);
        Assertions.assertTrue(shadow.available());
        Assertions.assertFalse(shadow.stalled());
        Assertions.assertSame(SyntheticGlyphs.TABLE, shadow.glyphs());
        Assertions.assertThrows(NullPointerException.class, () -> new ShadowOracle(null));
        Assertions.assertThrows(IllegalStateException.class, () -> shadow.offer(KEY, frame(1, 1)));
        Assertions.assertThrows(IllegalStateException.class, () -> shadow.pixelsNow(KEY));
        Assertions.assertThrows(IllegalStateException.class, () -> shadow.checking(KEY, NONE, new ArrayList<>()));
        shadow.open(KEY, S, NONE);
        Assertions.assertThrows(IllegalStateException.class, () -> shadow.open(KEY, S, NONE));
        Assertions.assertThrows(NullPointerException.class, () -> shadow.offer(KEY, null));
        Assertions.assertThrows(NullPointerException.class, () -> shadow.checking(KEY, null, new ArrayList<>()));
        Assertions.assertThrows(NullPointerException.class, () -> shadow.checking(KEY, NONE, null));
        // A record validation never produces (a BLIT of an absent texture) throws from the oracle
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> shadow.offer(KEY, frame(1, 1, segment(blit(TEX, 0, 0, 1, 1, 0, 0, 0)))));
        Assertions
            .assertThrows(NullPointerException.class, () -> new TeeRenderHost(shadow, null, new ArrayList<String>()));
        Assertions.assertDoesNotThrow(() -> shadow.bindDisplay("d", KEY));
    }

    private static FrameRecord frame(long epoch, long id, byte[]... segments) {
        return new FrameRecord(epoch, id, segments, FrameTotals.EMPTY);
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    /** 4×2 index8 caps for a device on a tee. */
    private static DeviceCaps caps() {
        return new DeviceCaps(
            new SurfaceDesc(2, 4, 2, Formats.INDEX8),
            new FrameLimits(65536, 4096, 64, 100000),
            4096,
            8,
            32,
            4096,
            2);
    }
}
