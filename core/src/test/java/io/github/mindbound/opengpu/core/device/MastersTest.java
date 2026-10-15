package io.github.mindbound.opengpu.core.device;

import java.util.Arrays;
import java.util.SortedMap;

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
 * The texture masters (28:123; O6, O7; plan §3.6): {@code TEX_CREATE} puts a zeroed master (28:550),
 * {@code TEX_UPDATE} copies its rows in at their offsets in the 28 §5.7 layout (28:561), {@code TEX_FREE} drops it;
 * entries are keyed by the full handle, never the slot (O7); a duplicate create, an absent handle or another kind
 * throws; and a payload is never adopted, so a later partial update cannot write into an array a host still queues
 * (amendment B12; C14).
 */
class MastersTest {

    private static final int T = 0x08000000;

    @Test
    void aCreateIsAZeroedMasterInItsFormat() {
        Masters m = new Masters();
        m.apply(new TexCreateRecord(1, T, 4, 3, Formats.INDEX8));
        m.apply(new TexCreateRecord(1, 0x08010000, 3, 2, Formats.RGB565));
        SortedMap<Integer, MasterTexture> copy = m.copy();
        Assertions.assertEquals(
            Arrays.asList(T, 0x08010000),
            Arrays.asList(
                copy.keySet()
                    .toArray()));
        MasterTexture a = copy.get(T);
        Assertions.assertEquals(T, a.handle);
        Assertions.assertEquals(4, a.width);
        Assertions.assertEquals(3, a.height);
        Assertions.assertEquals(Formats.INDEX8, a.format);
        Assertions.assertArrayEquals(new byte[12], a.texels());
        MasterTexture b = copy.get(0x08010000);
        Assertions.assertEquals(Formats.RGB565, b.format);
        Assertions.assertArrayEquals(new byte[12], b.texels());
    }

    @Test
    void anUpdateCopiesItsRowsAtTheirOffsets() {
        Masters m = new Masters();
        m.apply(new TexCreateRecord(1, T, 4, 3, Formats.INDEX8));
        m.apply(new TexUpdateRecord(1, T, 1, 1, 2, 2, new byte[] { 1, 2, 3, 4 }));
        Assertions.assertArrayEquals(new byte[] { 0, 0, 0, 0, 0, 1, 2, 0, 0, 3, 4, 0 }, texels(m, T));
        // a second update over part of the first
        m.apply(new TexUpdateRecord(1, T, 2, 0, 2, 3, new byte[] { 5, 6, 7, 8, 9, 10 }));
        Assertions.assertArrayEquals(new byte[] { 0, 0, 5, 6, 0, 1, 7, 8, 0, 3, 9, 10 }, texels(m, T));
    }

    @Test
    void anRgb565UpdateCopiesTwoBytesPerTexelUnchanged() {
        // 28:561: LE u16 rows, w·2 bytes each, no padding
        Masters m = new Masters();
        m.apply(new TexCreateRecord(1, T, 3, 2, Formats.RGB565));
        byte[] payload = { (byte) 0x00, (byte) 0xF8, (byte) 0xE0, (byte) 0x07, (byte) 0x1F, (byte) 0x00, (byte) 0xFF,
            (byte) 0xFF };
        m.apply(new TexUpdateRecord(1, T, 1, 0, 2, 2, payload));
        Assertions.assertArrayEquals(
            new byte[] { 0, 0, (byte) 0x00, (byte) 0xF8, (byte) 0xE0, (byte) 0x07, 0, 0, (byte) 0x1F, (byte) 0x00,
                (byte) 0xFF, (byte) 0xFF },
            texels(m, T));
    }

    @Test
    void aFreeDropsTheMaster() {
        Masters m = new Masters();
        m.apply(new TexCreateRecord(1, T, 4, 3, Formats.INDEX8));
        m.apply(new TexFreeRecord(1, T));
        Assertions.assertTrue(
            m.copy()
                .isEmpty());
        // and the handle may be created again (a new device's reset path never does, but the map allows it)
        m.apply(new TexCreateRecord(2, T, 1, 1, Formats.INDEX8));
        Assertions.assertEquals(
            1,
            m.copy()
                .size());
    }

    @Test
    void mastersKeyByTheFullHandleNeverTheSlot() {
        // O7: slot 0 at generations 0 and 1 are two textures
        Masters m = new Masters();
        int older = 0x08000000;
        int newer = 0x08000001;
        m.apply(new TexCreateRecord(1, older, 2, 1, Formats.INDEX8));
        m.apply(new TexUpdateRecord(1, older, 0, 0, 2, 1, new byte[] { 7, 7 }));
        m.apply(new TexCreateRecord(1, newer, 2, 1, Formats.INDEX8));
        m.apply(new TexUpdateRecord(1, newer, 0, 0, 2, 1, new byte[] { 9, 9 }));
        m.apply(new TexFreeRecord(1, older));
        SortedMap<Integer, MasterTexture> copy = m.copy();
        Assertions.assertEquals(1, copy.size());
        Assertions.assertArrayEquals(
            new byte[] { 9, 9 },
            copy.get(newer)
                .texels());
    }

    @Test
    void whatNoValidSequenceProducesThrows() {
        Masters m = new Masters();
        m.apply(new TexCreateRecord(1, T, 4, 3, Formats.INDEX8));
        // a duplicate create
        Assertions
            .assertThrows(IllegalStateException.class, () -> m.apply(new TexCreateRecord(1, T, 4, 3, Formats.INDEX8)));
        // an absent handle
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> m.apply(new TexUpdateRecord(1, 0x08010000, 0, 0, 1, 1, new byte[1])));
        Assertions.assertThrows(IllegalStateException.class, () -> m.apply(new TexFreeRecord(1, 0x08010000)));
        Assertions.assertThrows(IllegalStateException.class, () -> m.apply(new TexFreeRecord(1, 0x08000001)));
        // an update outside the master, or whose payload is the other format's length
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> m.apply(new TexUpdateRecord(1, T, 3, 0, 2, 1, new byte[2])));
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> m.apply(new TexUpdateRecord(1, T, 0, 2, 1, 2, new byte[2])));
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> m.apply(new TexUpdateRecord(1, T, 5, 0, 1, 1, new byte[1])));
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> m.apply(new TexUpdateRecord(1, T, 0, 0, 2, 2, new byte[8])));
        // another kind
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> m.apply(new FrameRecord(1, 1, new byte[0][], FrameTotals.EMPTY)));
        Assertions.assertThrows(IllegalStateException.class, () -> m.apply(new ResetRecord(1, new byte[4])));
        // nothing changed
        SortedMap<Integer, MasterTexture> copy = m.copy();
        Assertions.assertEquals(1, copy.size());
        Assertions.assertArrayEquals(
            new byte[12],
            copy.get(T)
                .texels());
    }

    @Test
    void aFullUpdateThenAPartialOneLeavesTheFirstPayloadUntouched() {
        // B12: the fold copies rows and never keeps a payload array as the master
        Masters m = new Masters();
        m.apply(new TexCreateRecord(1, T, 4, 3, Formats.INDEX8));
        byte[] p = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 };
        byte[] pCopy = p.clone();
        TexUpdateRecord full = new TexUpdateRecord(1, T, 0, 0, 4, 3, p);
        m.apply(full);
        Assertions.assertArrayEquals(pCopy, texels(m, T));
        TexUpdateRecord partial = new TexUpdateRecord(1, T, 1, 1, 2, 1, new byte[] { 99, 98 });
        m.apply(partial);
        Assertions.assertArrayEquals(pCopy, p);
        Assertions.assertTrue(Record.devChecks());
        Assertions.assertTrue(full.intact());
        Assertions.assertTrue(partial.intact());
        Assertions.assertArrayEquals(new byte[] { 1, 2, 3, 4, 5, 99, 98, 8, 9, 10, 11, 12 }, texels(m, T));
    }

    @Test
    void copyIsDeepSortedAndUnmodifiable() {
        Masters m = new Masters();
        m.apply(new TexCreateRecord(1, 0x08020000, 1, 1, Formats.INDEX8));
        m.apply(new TexCreateRecord(1, T, 2, 1, Formats.INDEX8));
        m.apply(new TexCreateRecord(1, 0x08010003, 1, 1, Formats.INDEX8));
        SortedMap<Integer, MasterTexture> first = m.copy();
        Assertions.assertEquals(
            Arrays.asList(T, 0x08010003, 0x08020000),
            Arrays.asList(
                first.keySet()
                    .toArray()));
        first.get(T)
            .texels()[0] = 42;
        Assertions.assertArrayEquals(new byte[2], texels(m, T));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> first.remove(T));
        Assertions.assertThrows(UnsupportedOperationException.class, () -> first.put(1, null));
        Assertions.assertEquals(
            "MasterTexture[0x08000000 2x1 index8]",
            first.get(T)
                .toString());
    }

    @Test
    void clearDropsEveryMaster() {
        Masters m = new Masters();
        m.apply(new TexCreateRecord(1, T, 4, 3, Formats.INDEX8));
        m.apply(new TexCreateRecord(1, 0x08010000, 4, 3, Formats.INDEX8));
        m.clear();
        Assertions.assertTrue(
            m.copy()
                .isEmpty());
        m.apply(new TexCreateRecord(2, T, 4, 3, Formats.INDEX8));
        Assertions.assertEquals(
            1,
            m.copy()
                .size());
    }

    private static byte[] texels(Masters m, int handle) {
        return m.copy()
            .get(handle)
            .texels();
    }
}
