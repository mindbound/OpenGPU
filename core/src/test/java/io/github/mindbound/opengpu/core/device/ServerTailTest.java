package io.github.mindbound.opengpu.core.device;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.ResetRecord;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexFreeRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;

/**
 * The server tail (28:122; plan §3.5-§3.7): the records after K, head first, never starting with a resource record
 * (O6, C27); its frame count (28:140's "at most 2 unconfirmed frames") and queued bytes (64 B per resource record plus
 * w·h·bpp or {@code #data}, 28:140) kept in step through add and remove; and the frames due for {@code "dropped"} at
 * exactly 1 s after present, each once (28:112; H12).
 */
class ServerTailTest {

    private static final long MS = 1_000_000L;

    @Test
    void anEmptyTailHasNothing() {
        ServerTail tail = new ServerTail();
        Assertions.assertTrue(tail.isEmpty());
        Assertions.assertEquals(0, tail.unconfirmedFrames());
        Assertions.assertEquals(0, tail.queuedBytes());
        Assertions.assertTrue(
            tail.records()
                .isEmpty());
        Assertions.assertFalse(tail.containsFrame(1));
        Assertions.assertEquals(0, tail.dueForDrop(Long.MAX_VALUE).length);
        Assertions.assertThrows(NoSuchElementException.class, tail::first);
        Assertions.assertThrows(NoSuchElementException.class, tail::removeFirst);
    }

    @Test
    void aResourceRecordNeverStartsTheTail() {
        // O6: with no FRAME ahead it folds at once instead
        ServerTail tail = new ServerTail();
        Assertions.assertThrows(IllegalStateException.class, () -> tail.addResource(create(0x08000000, 16, 16)));
        Assertions.assertThrows(IllegalStateException.class, () -> tail.addResource(new TexFreeRecord(1, 0x08000000)));
        Assertions.assertTrue(tail.isEmpty());
        Assertions.assertEquals(0, tail.queuedBytes());
        // a FRAME or a RESET is no resource record, empty tail or not
        Assertions.assertThrows(IllegalArgumentException.class, () -> tail.addResource(frame(1)));
        tail.addFrame(frame(1), 0);
        Assertions.assertThrows(IllegalArgumentException.class, () -> tail.addResource(frame(2)));
        Assertions
            .assertThrows(IllegalArgumentException.class, () -> tail.addResource(new ResetRecord(2, new byte[4])));
        Assertions.assertEquals(
            1,
            tail.records()
                .size());
        Assertions.assertEquals(1, tail.unconfirmedFrames());
    }

    @Test
    void theCountersFollowEveryAddAndRemove() {
        ServerTail tail = new ServerTail();
        FrameRecord f1 = frame(1);
        FrameRecord f2 = frame(2);
        TexCreateRecord c = create(0x08000000, 16, 16);
        TexUpdateRecord u = new TexUpdateRecord(1, 0x08000000, 0, 0, 16, 16, new byte[256]);
        TexFreeRecord fr = new TexFreeRecord(1, 0x08000000);
        tail.addFrame(f1, 0);
        Assertions.assertEquals(1, tail.unconfirmedFrames());
        Assertions.assertEquals(0, tail.queuedBytes());
        tail.addResource(c);
        Assertions.assertEquals(320, tail.queuedBytes());
        tail.addResource(u);
        Assertions.assertEquals(640, tail.queuedBytes());
        tail.addFrame(f2, 10 * MS);
        tail.addResource(fr);
        Assertions.assertEquals(704, tail.queuedBytes());
        Assertions.assertEquals(2, tail.unconfirmedFrames());
        Assertions.assertEquals(Arrays.<Record>asList(f1, c, u, f2, fr), tail.records());
        Assertions.assertTrue(tail.containsFrame(1));
        Assertions.assertTrue(tail.containsFrame(2));
        Assertions.assertFalse(tail.containsFrame(0));
        Assertions.assertFalse(tail.containsFrame(3));

        Assertions.assertSame(f1, tail.first());
        Assertions.assertSame(f1, tail.first());
        Assertions.assertSame(f1, tail.removeFirst());
        Assertions.assertEquals(1, tail.unconfirmedFrames());
        Assertions.assertEquals(704, tail.queuedBytes());
        Assertions.assertFalse(tail.containsFrame(1));
        Assertions.assertSame(c, tail.removeFirst());
        Assertions.assertEquals(384, tail.queuedBytes());
        Assertions.assertSame(u, tail.removeFirst());
        Assertions.assertEquals(64, tail.queuedBytes());
        Assertions.assertSame(f2, tail.removeFirst());
        Assertions.assertEquals(0, tail.unconfirmedFrames());
        // a resource record may be the head once the frame ahead of it left; the device folds it at once (O6)
        Assertions.assertSame(fr, tail.first());
        Assertions.assertSame(fr, tail.removeFirst());
        Assertions.assertEquals(0, tail.queuedBytes());
        Assertions.assertTrue(tail.isEmpty());
    }

    @Test
    void queuedBytesCountTheOverheadAndThePayloadPerKind() {
        ServerTail tail = new ServerTail();
        tail.addFrame(frame(1), 0);
        // TEX_CREATE: 64 + w·h·bpp, the host uploads zeros (28:140)
        tail.addResource(new TexCreateRecord(1, 0x08000000, 1024, 1024, Formats.RGB565));
        Assertions.assertEquals(64 + 2097152, tail.queuedBytes());
        // TEX_UPDATE: 64 + #data; TEX_FREE: 64
        tail.addResource(new TexUpdateRecord(1, 0x08000000, 3, 4, 5, 6, new byte[60]));
        tail.addResource(new TexFreeRecord(1, 0x08000000));
        Assertions.assertEquals(64 + 2097152 + 124 + 64, tail.queuedBytes());
        // a FRAME counts nothing
        tail.addFrame(frame(2), 0);
        Assertions.assertEquals(64 + 2097152 + 124 + 64, tail.queuedBytes());
    }

    @Test
    void recordsIsACopyHeadFirst() {
        ServerTail tail = new ServerTail();
        FrameRecord f1 = frame(1);
        tail.addFrame(f1, 0);
        List<Record> copy = tail.records();
        copy.clear();
        Assertions.assertEquals(Arrays.<Record>asList(f1), tail.records());
        Assertions.assertNotSame(tail.records(), tail.records());
    }

    @Test
    void framesAreDueForDropAtExactly1SecondAndOnlyOnce() {
        Assertions.assertEquals(1_000_000_000L, Device.DROP_AFTER_NANOS);
        ServerTail tail = new ServerTail();
        long t0 = 5 * MS;
        tail.addFrame(frame(1), t0);
        tail.addFrame(frame(2), t0 + 500 * MS);
        Assertions.assertEquals(0, tail.dueForDrop(t0).length);
        Assertions.assertEquals(0, tail.dueForDrop(t0 + 999 * MS).length);
        Assertions.assertEquals(0, tail.dueForDrop(t0 + Device.DROP_AFTER_NANOS - 1).length);
        Assertions.assertArrayEquals(new long[] { 1 }, tail.dueForDrop(t0 + 1000 * MS));
        // each frame is reported once (28:112)
        Assertions.assertEquals(0, tail.dueForDrop(t0 + 1000 * MS).length);
        Assertions.assertEquals(0, tail.dueForDrop(t0 + 1499 * MS).length);
        Assertions.assertArrayEquals(new long[] { 2 }, tail.dueForDrop(t0 + 1500 * MS));
        Assertions.assertEquals(0, tail.dueForDrop(t0 + 60_000 * MS).length);
        // a dropped frame stays queued (28:112)
        Assertions.assertEquals(2, tail.unconfirmedFrames());
        Assertions.assertTrue(tail.containsFrame(1));
    }

    @Test
    void framesDueTogetherComeInIdOrderAndRemovedFramesAreNeverDue() {
        ServerTail tail = new ServerTail();
        tail.addFrame(frame(1), 0);
        tail.addFrame(frame(2), 100 * MS);
        tail.addFrame(frame(3), 200 * MS);
        tail.removeFirst();
        Assertions.assertArrayEquals(new long[] { 2, 3 }, tail.dueForDrop(5000 * MS));
        ServerTail other = new ServerTail();
        other.addFrame(frame(7), 0);
        other.removeFirst();
        Assertions.assertEquals(0, other.dueForDrop(5000 * MS).length);
    }

    @Test
    void clearEmptiesTheTailAndBothCounters() {
        ServerTail tail = new ServerTail();
        tail.addFrame(frame(1), 0);
        tail.addResource(create(0x08000000, 16, 16));
        tail.addFrame(frame(2), 0);
        tail.clear();
        Assertions.assertTrue(tail.isEmpty());
        Assertions.assertEquals(0, tail.unconfirmedFrames());
        Assertions.assertEquals(0, tail.queuedBytes());
        Assertions.assertFalse(tail.containsFrame(1));
        Assertions.assertEquals(0, tail.dueForDrop(Long.MAX_VALUE).length);
        // usable again
        tail.addFrame(frame(3), 0);
        Assertions.assertEquals(1, tail.unconfirmedFrames());
    }

    private static FrameRecord frame(long id) {
        return new FrameRecord(1, id, new byte[0][], FrameTotals.EMPTY);
    }

    private static TexCreateRecord create(int handle, int w, int h) {
        return new TexCreateRecord(1, handle, w, h, Formats.INDEX8);
    }
}
