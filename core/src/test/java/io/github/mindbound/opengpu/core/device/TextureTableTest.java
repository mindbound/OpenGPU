package io.github.mindbound.opengpu.core.device;

import java.util.Arrays;
import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.cmd.Formats;

/**
 * The texture type's handle table (28 §6.1; plan §3.8, §3.13; amendment B4): handles {@code type(4) | slot(11) |
 * generation(16)} of type 1 from a FIFO free queue 0-2 047, a generation bump on free wrapping 65 535 → 0 (28:533),
 * frees requeued at the tail, a reset freeing live slots in ascending order (28:1061), a first handle of
 * {@code 0x08000000} (28:538), never-allocated slots not live (28:537), and lookups that answer 0 for any int that is
 * not exactly a live handle, on full and one-slot tables alike.
 */
class TextureTableTest {

    private static final int I8 = Formats.INDEX8;

    /** Handles that are never live: 0, −1, the int extremes, a type-2 handle (B4). */
    private static final int[] NEVER_LIVE = { 0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x10000000 };

    @Test
    void theQueueStartsAt0To2047AndHandsOutType1Handles() {
        TextureTable t = new TextureTable();
        Assertions.assertEquals(2048, TextureTable.SLOTS);
        int[] free = t.freeSlots();
        Assertions.assertEquals(2048, free.length);
        for (int i = 0; i < free.length; i++) {
            Assertions.assertEquals(i, free[i]);
        }
        Assertions.assertEquals(2048, t.generations().length);
        Assertions.assertEquals(0, t.liveCount());
        // 28:538: a new device's first texture; then slot 1
        Assertions.assertEquals(0x08000000, t.allocate(16, 16, I8));
        Assertions.assertEquals(0x08010000, t.allocate(8, 4, Formats.RGB565));
        Assertions.assertEquals(0x08020000, t.allocate(1, 1, I8));
        Assertions.assertEquals(3, t.liveCount());
        Assertions.assertArrayEquals(new int[] { 0x08000000, 0x08010000, 0x08020000 }, t.liveHandles());
        Assertions.assertEquals(2045, t.freeSlots().length);
        Assertions.assertEquals(3, t.freeSlots()[0]);
    }

    @Test
    void lookupsAnswerTheLiveTexturesSizeAndFormat() {
        TextureTable t = new TextureTable();
        int a = t.allocate(16, 16, I8);
        int b = t.allocate(8, 4, Formats.RGB565);
        Assertions.assertEquals(I8, t.format(a));
        Assertions.assertEquals(16, t.width(a));
        Assertions.assertEquals(16, t.height(a));
        Assertions.assertEquals(256, t.byteSize(a));
        Assertions.assertEquals(Formats.RGB565, t.format(b));
        Assertions.assertEquals(8, t.width(b));
        Assertions.assertEquals(4, t.height(b));
        Assertions.assertEquals(64, t.byteSize(b));
        // the largest texture: 1 024 × 1 024 × 2 B fits an int
        int c = t.allocate(1024, 1024, Formats.RGB565);
        Assertions.assertEquals(2097152, t.byteSize(c));
    }

    @Test
    void freeBumpsTheGenerationAndRequeuesTheSlotAtTheTail() {
        TextureTable t = new TextureTable();
        int a = t.allocate(4, 4, I8);
        t.free(a);
        Assertions.assertEquals(1, t.generations()[0]);
        int[] free = t.freeSlots();
        Assertions.assertEquals(2048, free.length);
        Assertions.assertEquals(1, free[0]);
        Assertions.assertEquals(2047, free[2046]);
        Assertions.assertEquals(0, free[2047]);
        Assertions.assertEquals(0, t.liveCount());
        // the slot comes back only after the 2 047 slots ahead of it
        for (int slot = 1; slot < 2048; slot++) {
            Assertions.assertEquals(0x08000000 | slot << 16, t.allocate(1, 1, I8));
        }
        Assertions.assertEquals(0x08000001, t.allocate(1, 1, I8));
        Assertions.assertEquals(0, t.freeSlots().length);
        // an empty queue is a state the caps never reach (plan §3.8)
        Assertions.assertThrows(IllegalStateException.class, () -> t.allocate(1, 1, I8));
    }

    @Test
    void theGenerationWraps65535To0AndTheFirstHandleIsLiveAgain() {
        TextureTable t = new TextureTable(1);
        Assertions.assertArrayEquals(new int[] { 0 }, t.freeSlots());
        int h = t.allocate(1, 1, I8);
        Assertions.assertEquals(0x08000000, h);
        for (int cycle = 1; cycle < 65536; cycle++) {
            t.free(h);
            h = t.allocate(1, 1, I8);
            Assertions.assertEquals(0x08000000 | cycle, h);
        }
        Assertions.assertEquals(0x0800FFFF, h);
        t.free(h);
        Assertions.assertEquals(0, t.generations()[0]);
        // 28:534-536: after 65 536 frees of the slot the first handle is live again
        Assertions.assertEquals(0x08000000, t.allocate(2, 3, I8));
        Assertions.assertEquals(I8, t.format(0x08000000));
        Assertions.assertEquals(2, t.width(0x08000000));
        Assertions.assertEquals(3, t.height(0x08000000));
        Assertions.assertEquals(0, t.format(0x0800FFFF));
    }

    @Test
    void lookupsAre0ForAWrongGenerationANeverAllocatedSlotAndAFreedHandle() {
        TextureTable t = new TextureTable();
        int a = t.allocate(16, 16, I8);
        // a wrong generation of a live slot
        assertNotLive(t, 0x08000001);
        assertNotLive(t, 0x0800FFFF);
        // a never-allocated slot, whose generation 0 matches (28:537)
        assertNotLive(t, 0x08010000);
        assertNotLive(t, 0x0FFF0000);
        // the same slot and generation with another type
        assertNotLive(t, 0x10000000);
        t.free(a);
        assertNotLive(t, a);
        assertNotLive(t, 0x08000001);
    }

    @Test
    void lookupsOfNonHandlesAre0OnAFullAndAOneSlotTable() {
        // B4: every per-slot array is 2 048 long whatever the slot count; 0 marks a free slot, so free zeroes it
        TextureTable full = new TextureTable();
        TextureTable one = new TextureTable(1);
        for (TextureTable t : new TextureTable[] { full, one }) {
            for (int h : NEVER_LIVE) {
                assertNotLive(t, h);
            }
            int a = t.allocate(16, 16, I8);
            for (int h : NEVER_LIVE) {
                assertNotLive(t, h);
            }
            t.free(a);
            // slot 0 is free again: handle 0 must not read its old entries
            for (int h : NEVER_LIVE) {
                assertNotLive(t, h);
            }
            assertNotLive(t, a);
        }
        // a one-slot table's other slots are never live, and looking them up does not throw
        for (int slot = 1; slot < 2048; slot += 89) {
            assertNotLive(one, 0x08000000 | slot << 16);
        }
        assertNotLive(one, 0x0FFF0000);
    }

    @Test
    void freeAllAscendingQueuesTheLiveSlotsInSlotOrderAfterTheFreeOnes() {
        // 28:1061, plan §3.8: create 5, free slot 2, reset
        TextureTable t = new TextureTable();
        int[] h = new int[5];
        for (int i = 0; i < 5; i++) {
            h[i] = t.allocate(1 + i, 1, I8);
        }
        t.free(h[2]);
        t.freeAllAscending();
        Assertions.assertEquals(0, t.liveCount());
        Assertions.assertEquals(0, t.liveHandles().length);
        int[] free = t.freeSlots();
        Assertions.assertEquals(2048, free.length);
        for (int i = 0; i < 2043; i++) {
            Assertions.assertEquals(5 + i, free[i]);
        }
        Assertions.assertArrayEquals(new int[] { 2, 0, 1, 3, 4 }, Arrays.copyOfRange(free, 2043, 2048));
        int[] generations = t.generations();
        for (int slot = 0; slot < 5; slot++) {
            Assertions.assertEquals(1, generations[slot], "slot " + slot);
            assertNotLive(t, h[slot]);
        }
        Assertions.assertEquals(0, generations[5]);
        // a reset of an empty table changes nothing
        t.freeAllAscending();
        Assertions.assertArrayEquals(free, t.freeSlots());
        Assertions.assertArrayEquals(generations, t.generations());
    }

    @Test
    void liveHandlesAreInSlotOrderWhateverTheAllocationOrder() {
        TextureTable t = new TextureTable(3);
        int s0 = t.allocate(1, 1, I8);
        int s1 = t.allocate(1, 1, I8);
        int s2 = t.allocate(1, 1, I8);
        t.free(s1);
        t.free(s0);
        // the queue is now 1, 0: slot 1 comes back first
        Assertions.assertArrayEquals(new int[] { 1, 0 }, t.freeSlots());
        Assertions.assertEquals(0x08010001, t.allocate(1, 1, I8));
        Assertions.assertEquals(0x08000001, t.allocate(1, 1, I8));
        Assertions.assertArrayEquals(new int[] { 0x08000001, 0x08010001, s2 }, t.liveHandles());
        Assertions.assertEquals(3, t.liveCount());
    }

    @Test
    void freeTakesExactlyALiveHandle() {
        TextureTable t = new TextureTable();
        int a = t.allocate(1, 1, I8);
        assertFreeFails(t, 0x08000001, "0x08000001 is not live");
        assertFreeFails(t, 0x08010000, "0x08010000 is not live");
        assertFreeFails(t, 0, "0x00000000 is not live");
        assertFreeFails(t, -1, "0xFFFFFFFF is not live");
        assertFreeFails(t, 0x10000000, "0x10000000 is not live");
        t.free(a);
        assertFreeFails(t, a, "0x08000000 is not live");
        Assertions.assertEquals(1, t.generations()[0]);
        Assertions.assertEquals(0, t.liveCount());
    }

    @Test
    void theConstructorAndAllocateCheckTheirArguments() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new TextureTable(0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new TextureTable(2049));
        Assertions.assertEquals(2048, new TextureTable(2048).freeSlots().length);
        TextureTable t = new TextureTable();
        Assertions.assertThrows(IllegalArgumentException.class, () -> t.allocate(0, 1, I8));
        Assertions.assertThrows(IllegalArgumentException.class, () -> t.allocate(1, 0, I8));
        Assertions.assertThrows(IllegalArgumentException.class, () -> t.allocate(4097, 1, I8));
        Assertions.assertThrows(IllegalArgumentException.class, () -> t.allocate(1, 1, Formats.INVALID));
        Assertions.assertThrows(IllegalArgumentException.class, () -> t.allocate(1, 1, 3));
        // nothing was taken by the failed calls
        Assertions.assertEquals(0, t.liveCount());
        Assertions.assertEquals(0x08000000, t.allocate(1, 1, I8));
    }

    @Test
    void theArrayViewsAreCopies() {
        TextureTable t = new TextureTable();
        int a = t.allocate(1, 1, I8);
        t.generations()[0] = 99;
        t.freeSlots()[0] = 99;
        t.liveHandles()[0] = 99;
        Assertions.assertEquals(0, t.generations()[0]);
        Assertions.assertEquals(1, t.freeSlots()[0]);
        Assertions.assertArrayEquals(new int[] { a }, t.liveHandles());
    }

    @Test
    void the256TexturesOfTheCapsLeaveAtLeast1792SlotsQueued() {
        // plan §3.8: allocate never finds the queue empty under the caps
        TextureTable t = new TextureTable();
        for (int i = 0; i < 256; i++) {
            t.allocate(1, 1, I8);
        }
        Assertions.assertEquals(1792, t.freeSlots().length);
    }

    private static void assertNotLive(TextureTable t, int handle) {
        String what = String.format(Locale.ROOT, "0x%08X", handle);
        Assertions.assertEquals(0, t.format(handle), what);
        Assertions.assertEquals(0, t.width(handle), what);
        Assertions.assertEquals(0, t.height(handle), what);
        Assertions.assertEquals(0, t.byteSize(handle), what);
    }

    private static void assertFreeFails(TextureTable t, int handle, String message) {
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class, () -> t.free(handle));
        Assertions.assertEquals(message, e.getMessage());
    }
}
