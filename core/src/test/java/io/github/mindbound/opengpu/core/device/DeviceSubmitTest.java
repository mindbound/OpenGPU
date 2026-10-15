package io.github.mindbound.opengpu.core.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.bytes;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.concat;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.header;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.text;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.cmd.ErrorCode;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.render.FrameRecord;

/**
 * {@code submit(stream)} through the device (28:97, §3.5, §3.6; plan §3.4): charged before validation and whatever the
 * outcome (28:91, :287); 28's six example messages (28:327-332) with the stale handle on a device whose
 * {@code 0x08000000} is live, as S6's {@code StreamErrorMessageTest} builds them; a rejected submit leaving the open
 * frame as it was and the next valid one accepted (28:919); frame caps cumulative over the open frame's submits;
 * nothing offered (O2); and the {@code FRAME} holding the submitted arrays themselves (C14) with the open frame's
 * totals.
 */
class DeviceSubmitTest {

    private static final int T = 0x08000000;
    private static final byte[] HI = { 'H', 'i' };

    /** 28 §9.4's T2 frame: records at 8, 13, 26 and 44, END at 63. */
    private static byte[] workedFrame(int handle, byte[] string) {
        return segment(
            clear(0),
            fill(10, 20, 100, 50, 196),
            blit(handle, 0, 0, 16, 16, 150, 40, 1),
            text(8, 180, 255, 0, 1, 0, string));
    }

    @Test
    void theChargeComesBeforeValidationAndIsSpentOnARejection() {
        DeviceRig t2 = DeviceRig.tier(2);
        assertRejected(t2, new byte[0], "0: header: segment of 0 bytes is shorter than the 8-byte header");
        Assertions.assertEquals(Collections.singletonList("consume 0.015625000"), t2.budget.events());
        DeviceRig t3 = DeviceRig.tier(3);
        assertRejected(t3, bytes(0x4F), "0: header: segment of 1 bytes is shorter than the 8-byte header");
        // 1/128 + 1/1 MB = 0.007 813 453 674...
        Assertions.assertEquals(Collections.singletonList("consume 0.007813454"), t3.budget.events());
        // an accepted worked frame: 1/64 + 64/1 MB
        t2.create(16, 16);
        t2.budget.clearEvents();
        t2.submit(workedFrame(T, HI));
        Assertions.assertEquals(Collections.singletonList("consume 0.015686035"), t2.budget.events());
    }

    @Test
    void aBudgetThatThrowsStopsTheSubmitBeforeValidation() {
        // whatever budget.consume throws passes through, with nothing validated or appended (plan §2.2)
        DeviceRig rig = DeviceRig.tier(2);
        final IllegalStateException boom = new IllegalStateException("out of budget");
        CallBudget refusing = new CallBudget() {

            @Override
            public void consume(double cost) {
                throw boom;
            }

            @Override
            public void pause(double seconds) {
                throw new AssertionError("submit never pauses");
            }
        };
        String before = DeviceCallbackTableTest.state(rig.snapshot());
        IllegalStateException e = Assertions
            .assertThrows(IllegalStateException.class, () -> rig.device.submit(refusing, segment()));
        Assertions.assertSame(boom, e);
        Assertions.assertEquals(before, DeviceCallbackTableTest.state(rig.snapshot()));
    }

    @Test
    void the28ExampleMessagesThroughTheDevice() {
        DeviceRig rig = DeviceRig.tier(2);
        Assertions.assertEquals(T, rig.create(16, 16));
        assertRejected(
            rig,
            new byte[70000],
            "0: submit: 70000 bytes exceed the 65536-byte cap",
            ErrorCode.OVER_SUBMIT_BYTES,
            0);
        assertRejected(
            rig,
            concat(header(2, 0, 0), bytes(0xFF)),
            "4: header: unsupported command format 2 (this mod decodes 1)",
            ErrorCode.BAD_VERSION,
            4);
        // the stale generation of the live 0x08000000
        assertRejected(
            rig,
            workedFrame(0x08000001, HI),
            "26: BLIT: handle 0x08000001 is not live",
            ErrorCode.STALE_HANDLE,
            26);
        assertRejected(
            rig,
            workedFrame(0x10000000, HI),
            "26: BLIT: handle 0x10000000 is a buffer, expected a texture",
            ErrorCode.WRONG_TYPE,
            26);
        // "Hi!" and a stray continuation byte: n = 4, the bad byte at string index 3
        assertRejected(
            rig,
            workedFrame(T, bytes('H', 'i', '!', 0x80)),
            "44: TEXT: invalid UTF-8 at string byte 3",
            ErrorCode.BAD_UTF8,
            44);
        // the header, three FILL_RECT and two CLEAR put 0x02 at 8 + 39 + 10 = 57
        byte[] f = fill(10, 20, 100, 50, 196);
        assertRejected(
            rig,
            concat(header(), f, f, f, clear(0), clear(0), bytes(0x02, 0xFF)),
            "57: 0x02: unknown opcode",
            ErrorCode.UNKNOWN_OPCODE,
            57);
        // and the worked frame itself is accepted
        rig.submit(workedFrame(T, HI));
    }

    @Test
    void aRejectedSubmitLeavesTheOpenFrameAndTheNextValidOneIsAccepted() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(16, 16);
        byte[] a = workedFrame(T, HI);
        rig.submit(a);
        DeviceSnapshot open = rig.snapshot();
        Assertions.assertEquals(new FrameTotals(64, 4, 64000, 5000, 256, 156), open.openTotals);
        assertRejected(rig, workedFrame(0x08000001, HI), "26: BLIT: handle 0x08000001 is not live");
        DeviceSnapshot after = rig.snapshot();
        Assertions.assertEquals(open.openTotals, after.openTotals);
        Assertions.assertEquals(1, after.openSegments.size());
        Assertions.assertSame(a, after.openSegments.get(0));
        byte[] b = segment(clear(7));
        rig.submit(b);
        DeviceSnapshot two = rig.snapshot();
        Assertions.assertEquals(2, two.openSegments.size());
        Assertions.assertSame(a, two.openSegments.get(0));
        Assertions.assertSame(b, two.openSegments.get(1));
        Assertions.assertEquals(new FrameTotals(78, 5, 128000, 5000, 256, 156), two.openTotals);
    }

    @Test
    void theFrameByteCapIsCumulativeOverTheOpenFrame() {
        // small caps: 512 B per frame; 56 header-and-END segments are 504 B, a 57th would be 513
        DeviceRig rig = DeviceRig.of(DeviceCreateTextureTest.small());
        for (int i = 0; i < 56; i++) {
            rig.submit(segment());
        }
        assertRejected(
            rig,
            segment(),
            "0: submit: 513 bytes with the open frame exceed the 512-byte frame cap",
            ErrorCode.OVER_FRAME_BYTES,
            0);
        Assertions.assertEquals(56, rig.snapshot().openSegments.size());
        Assertions.assertEquals(504, rig.snapshot().openTotals.bytes);
        // a present starts a new frame with its own budget
        rig.present();
        rig.submit(segment());
        Assertions.assertEquals(9, rig.snapshot().openTotals.bytes);
    }

    @Test
    void theRecordAndFillCapsAreCumulativeToo() {
        // small caps: 24 records and 4 000 px per frame; FILL_RECT of width 0 counts a record and no fill
        DeviceRig rig = DeviceRig.of(DeviceCreateTextureTest.small());
        byte[][] twenty = new byte[20][];
        Arrays.fill(twenty, fill(0, 0, 0, 1, 1));
        rig.submit(segment(twenty));
        byte[][] five = new byte[5][];
        Arrays.fill(five, fill(0, 0, 0, 1, 1));
        // the fifth record of the second segment is the 25th: at 8 + 4 × 13 = 60
        assertRejected(
            rig,
            segment(five),
            "60: FILL_RECT: 25 records exceed the frame's 24-record cap",
            ErrorCode.OVER_FRAME_RECORDS,
            60);
        rig.submit(segment(fill(0, 0, 0, 1, 1), fill(0, 0, 0, 1, 1), fill(0, 0, 0, 1, 1), fill(0, 0, 0, 1, 1)));
        Assertions.assertEquals(24, rig.snapshot().openTotals.records);

        // 24 × 16 = 384 px per CLEAR: ten fit in 4 000 px, the eleventh in the next segment does not
        DeviceRig fillRig = DeviceRig.of(DeviceCreateTextureTest.small());
        byte[][] ten = new byte[10][];
        Arrays.fill(ten, clear(0));
        fillRig.submit(segment(ten));
        assertRejected(
            fillRig,
            segment(clear(0)),
            "8: CLEAR: 4224 px of fill exceed the frame's 4000-px cap",
            ErrorCode.OVER_FRAME_FILL,
            8);
        Assertions.assertEquals(3840, fillRig.snapshot().openTotals.clearPixels);
    }

    @Test
    void submitsOfferNothingAndLeaveTheTailAlone() {
        // O2: a frame enters the tail only at present, whole
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(16, 16);
        rig.present();
        List<String> offered = rig.offeredText();
        List<String> tail = rig.tailText();
        rig.submit(workedFrame(T, HI));
        assertRejected(rig, new byte[3], "0: header: segment of 3 bytes is shorter than the 8-byte header");
        rig.submit(segment(clear(1)));
        Assertions.assertEquals(offered, rig.offeredText());
        Assertions.assertEquals(tail, rig.tailText());
        Assertions.assertEquals(1, rig.snapshot().lastFrameId);
    }

    @Test
    void theFrameHoldsTheSubmittedArraysAndTheOpenFramesTotals() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(16, 16);
        byte[] a = workedFrame(T, HI);
        byte[] b = segment(clear(7));
        rig.submit(a);
        rig.submit(b);
        FrameTotals totals = rig.snapshot().openTotals;
        Assertions.assertEquals(1, rig.present());
        FrameRecord f = (FrameRecord) rig.host.offered(rig.key)
            .get(1);
        Assertions.assertEquals(2, f.segmentCount());
        Assertions.assertSame(a, f.segment(0));
        Assertions.assertSame(b, f.segment(1));
        Assertions.assertEquals(totals, f.totals);
        Assertions.assertEquals(new FrameTotals(78, 5, 128000, 5000, 256, 156), f.totals);
        // the open frame starts again empty
        Assertions.assertEquals(FrameTotals.EMPTY, rig.snapshot().openTotals);
        Assertions.assertTrue(rig.snapshot().openSegments.isEmpty());
    }

    @Test
    void validationSeesTheLiveViewAtTheTimeOfTheSubmit() {
        DeviceRig rig = DeviceRig.tier(2);
        byte[] s = workedFrame(T, HI);
        assertRejected(rig, s, "26: BLIT: handle 0x08000000 is not live");
        rig.create(16, 16);
        rig.submit(s);
        // a 4×4 texture: BLIT's 16×16 source rect is clipped to it, so the copy class counts 4·4
        DeviceRig small = DeviceRig.tier(2);
        small.create(4, 4);
        small.submit(s);
        Assertions.assertEquals(16, small.snapshot().openTotals.copyPixels);
    }

    @Test
    void nullArgumentsAreRefused() {
        DeviceRig rig = DeviceRig.tier(2);
        Assertions.assertThrows(NullPointerException.class, () -> rig.device.submit(rig.budget, null));
        Assertions.assertThrows(NullPointerException.class, () -> rig.device.submit(null, segment()));
        Assertions.assertEquals(Collections.emptyList(), rig.budget.events());
        Assertions.assertTrue(rig.snapshot().openSegments.isEmpty());
    }

    private static void assertRejected(DeviceRig rig, byte[] s, String message) {
        assertRejected(rig, s, message, null, -1);
    }

    /** The submit is rejected with the message (and code and offset), and the device is as it was. */
    private static void assertRejected(DeviceRig rig, byte[] s, String message, ErrorCode code, int offset) {
        String before = DeviceCallbackTableTest.state(rig.snapshot());
        List<String> offered = rig.offeredText();
        CallResult r = rig.call(b -> rig.device.submit(b, s));
        Assertions.assertArrayEquals(new Object[] { null, message }, r.toLua());
        Assertions.assertNotNull(r.streamError());
        Assertions.assertEquals(
            message,
            r.streamError()
                .message());
        if (code != null) {
            Assertions.assertEquals(code, r.streamError().code);
            Assertions.assertEquals(offset, r.streamError().offset);
        }
        Assertions.assertEquals(offered, rig.offeredText());
        Assertions.assertEquals(before, DeviceCallbackTableTest.state(rig.snapshot()));
    }
}
