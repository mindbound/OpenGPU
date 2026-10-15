package io.github.mindbound.opengpu.core.fixtures.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.text;

import java.nio.charset.StandardCharsets;
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
import io.github.mindbound.opengpu.core.naive.NaiveDevice;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.ResetRecord;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexFreeRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;

/**
 * The record descriptors (S7 plan §2.3), pinned as literal strings. The CRC32s were computed outside Java, with
 * Python's {@code zlib.crc32}: {@code "123456789"} is the CRC-32 check value CBF43926, {@code "567891234"} 5276309D,
 * two zero bytes 41D912FF, 64 000 zero bytes 7DC291AB, 28:833-838's 64-byte frame 81B9385E and the sprite
 * {@code i % 5} (256 B) 1E8F899B. The
 * worked frame (28 §9.4) is also run through the device and the reference model, whose lines must agree.
 */
class RecordTextTest {

    private static final byte[] CHECK = ascii("123456789");

    @Test
    void aFrameNamesItsEpochIdSegmentsTotalsAndTheCrcOfItsSegmentsInSubmitOrder() {
        FrameTotals totals = new FrameTotals(9, 4, 64000, 5000, 256, 156);
        FrameRecord frame = new FrameRecord(1, 3, new byte[][] { ascii("1234"), ascii("56789") }, totals);
        Assertions.assertEquals(
            "FRAME e=1 id=3 segs=2 bytes=9 records=4 clear=64000 fill=5000 copy=256 set=156 crc=CBF43926",
            RecordText.of(frame));
        // the CRC runs over the segments in order, so the other order is another line
        FrameRecord reversed = new FrameRecord(2, 4, new byte[][] { ascii("56789"), ascii("1234") }, totals);
        Assertions.assertEquals(
            "FRAME e=2 id=4 segs=2 bytes=9 records=4 clear=64000 fill=5000 copy=256 set=156 crc=5276309D",
            RecordText.of(reversed));
    }

    @Test
    void anEmptyFrameHasTheEmptyCrc() {
        // a present without a submit since the last one (28:108)
        Assertions.assertEquals(
            "FRAME e=2 id=1 segs=0 bytes=0 records=0 clear=0 fill=0 copy=0 set=0 crc=00000000",
            RecordText.of(new FrameRecord(2, 1, new byte[0][], FrameTotals.EMPTY)));
        // one segment of zero length is a segment, and the CRC of nothing
        Assertions.assertEquals(
            "FRAME e=1 id=7 segs=1 bytes=0 records=0 clear=0 fill=0 copy=0 set=0 crc=00000000",
            RecordText.of(new FrameRecord(1, 7, new byte[][] { new byte[0] }, FrameTotals.EMPTY)));
    }

    @Test
    void textureRecordsNameTheFullHandleInHex() {
        // O7: the full handle, generation included; format codes as numbers (28 §6.2)
        Assertions.assertEquals(
            "TEX_CREATE e=1 tex=0x08000000 size=16x16 format=1",
            RecordText.of(new TexCreateRecord(1, 0x08000000, 16, 16, Formats.INDEX8)));
        Assertions.assertEquals(
            "TEX_CREATE e=4 tex=0x0FFFFFFF size=1024x3 format=2",
            RecordText.of(new TexCreateRecord(4, 0x0FFFFFFF, 1024, 3, Formats.RGB565)));
        Assertions.assertEquals(
            "TEX_UPDATE e=1 tex=0x08000000 at=3,4 size=3x3 len=9 crc=CBF43926",
            RecordText.of(new TexUpdateRecord(1, 0x08000000, 3, 4, 3, 3, CHECK)));
        Assertions.assertEquals(
            "TEX_UPDATE e=12 tex=0x0801FFFF at=0,1024 size=1x1 len=2 crc=41D912FF",
            RecordText.of(new TexUpdateRecord(12, 0x0801FFFF, 0, 1024, 1, 1, new byte[2])));
        Assertions.assertEquals("TEX_FREE e=7 tex=0x08000001", RecordText.of(new TexFreeRecord(7, 0x08000001)));
        Assertions.assertEquals("TEX_FREE e=0 tex=0x087F0000", RecordText.of(new TexFreeRecord(0, 0x087F0000)));
    }

    @Test
    void aResetNamesItsEpochAndTheCrcOfItsPixels() {
        Assertions.assertEquals("RESET e=2 len=9 crc=CBF43926", RecordText.of(new ResetRecord(2, CHECK)));
        // T2's cleared image, 320·200 zero bytes
        Assertions.assertEquals("RESET e=3 len=64000 crc=7DC291AB", RecordText.of(new ResetRecord(3, new byte[64000])));
    }

    @Test
    void aListMapsInOrderIntoANewList() {
        List<Record> records = Arrays.<Record>asList(
            new TexCreateRecord(1, 0x08000000, 1, 1, Formats.INDEX8),
            new TexFreeRecord(1, 0x08000000),
            new ResetRecord(2, CHECK));
        List<String> lines = RecordText.of(records);
        Assertions.assertEquals(
            Arrays.asList(
                "TEX_CREATE e=1 tex=0x08000000 size=1x1 format=1",
                "TEX_FREE e=1 tex=0x08000000",
                "RESET e=2 len=9 crc=CBF43926"),
            lines);
        lines.add("mutable");
        Assertions.assertEquals(Collections.emptyList(), RecordText.of(new ArrayList<Record>()));
        Assertions.assertThrows(NullPointerException.class, () -> RecordText.of((Record) null));
        // the helper the lines print, over parts in order
        Assertions.assertEquals(0xCBF43926L, RecordText.crc32(CHECK));
        Assertions.assertEquals(0xCBF43926L, RecordText.crc32(ascii("1234"), ascii("56789")));
        Assertions.assertEquals(0L, RecordText.crc32());
    }

    @Test
    void theWorkedFrameGivesTheSameLinesOnTheDeviceAndTheReferenceModel() {
        // 28 §9.4 on T2: create, update, submit, present, then a free behind FRAME 1 and a reset with K = 0
        byte[] s = segment(
            clear(0),
            fill(10, 20, 100, 50, 196),
            blit(0x08000000, 0, 0, 16, 16, 150, 40, 1),
            text(8, 180, 255, 0, 1, 0, "Hi"));
        Assertions.assertEquals(64, s.length);
        byte[] sprite = new byte[256];
        for (int i = 0; i < sprite.length; i++) {
            sprite[i] = (byte) (i % 5);
        }
        // the totals: CLEAR 320·200, FILL_RECT 100·50, BLIT 16·16 (KEY0-skipped pixels included) and TEXT 78 px per
        // codepoint (28:284), four records with END not counted (28 §3.5)
        List<String> expected = Arrays.asList(
            "TEX_CREATE e=1 tex=0x08000000 size=16x16 format=1",
            "TEX_UPDATE e=1 tex=0x08000000 at=0,0 size=16x16 len=256 crc=1E8F899B",
            "FRAME e=1 id=1 segs=1 bytes=64 records=4 clear=64000 fill=5000 copy=256 set=156 crc=81B9385E",
            "TEX_FREE e=1 tex=0x08000000",
            "RESET e=2 len=64000 crc=7DC291AB");

        DeviceRig rig = DeviceRig.tier(2);
        Assertions.assertEquals(0x08000000, rig.create(16, 16));
        rig.update(0x08000000, 0, 0, 16, 16, sprite);
        rig.submit(s);
        Assertions.assertEquals(1L, rig.present());
        rig.free(0x08000000);
        rig.device.reset();
        Assertions.assertEquals(expected, rig.offeredText());

        NaiveDevice model = new NaiveDevice(
            SurfaceDesc.forTier(2),
            FrameLimits.forTier(2),
            1048576,
            256,
            1024,
            32768,
            2,
            () -> 0L);
        Assertions.assertArrayEquals(new Object[] { 0x08000000 }, model.createTexture(16, 16, null));
        Assertions.assertArrayEquals(new Object[] { true }, model.updateTexture(0x08000000, 0, 0, 16, 16, sprite));
        Assertions.assertArrayEquals(new Object[] { true }, model.submit(s));
        Assertions.assertArrayEquals(new Object[] { 1L }, model.present());
        Assertions.assertArrayEquals(new Object[] { true }, model.free(0x08000000));
        model.reset();
        Assertions.assertEquals(expected, model.offered());
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
