package io.github.mindbound.opengpu.core.fixtures;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.cmd.HandleTypes;

/**
 * The test view's allocator (28 §6.1): a FIFO free queue of slots 0-2 047, a generation bump per free, and a reset
 * that frees the live handles in ascending slot order, after the slots already queued, as S7's device does (28 §6.1
 * leaves that order open). The expected handles are worked out here from those rules.
 */
class MapLiveViewTest {

    private static final int SLOTS = 2048;

    @Test
    void createTakesTheQueuesHeadAndFreeGoesToItsTail() {
        MapLiveView view = view();
        Assertions.assertEquals(0x08000000, view.create(1, 1));
        Assertions.assertEquals(0x08010000, view.create(1, 1));
        // slot 0 freed: generation 1, behind slots 2-2 047
        view.free(0x08000000);
        Assertions.assertFalse(view.isLive(0x08000000));
        Assertions.assertEquals(texture(2, 0), view.create(1, 1));
        Assertions.assertTrue(view.isLive(0x08010000));
        Assertions.assertEquals(2, view.liveCount());
    }

    @Test
    void aResetFreesInAscendingSlotOrderAfterTheQueuedSlots() {
        MapLiveView view = view();
        for (int slot = 0; slot < SLOTS; slot++) {
            Assertions.assertEquals(texture(slot, 0), view.create(1, 1));
        }
        // the queue is empty; slots 5, 3 and 1 freed in that order come back from FIFO in that order at generation 1,
        // so the live order ends 2 047, 5, 3, 1 and is not ascending
        for (int slot : new int[] { 5, 3, 1 }) {
            view.free(texture(slot, 0));
        }
        for (int slot : new int[] { 5, 3, 1 }) {
            Assertions.assertEquals(texture(slot, 1), view.create(1, 1));
        }
        // slot 10 freed before the reset: it is queued already, generation 1
        view.free(texture(10, 0));
        List<Integer> liveOrder = new ArrayList<>(view.handles());
        Assertions.assertEquals(SLOTS - 1, liveOrder.size());
        Assertions.assertEquals(
            texture(SLOTS - 1, 0),
            liveOrder.get(SLOTS - 5)
                .intValue());
        Assertions.assertEquals(
            texture(1, 1),
            liveOrder.get(SLOTS - 2)
                .intValue());
        // a put() entry is dropped and touches no slot
        view.put(Handles.pack(HandleTypes.TEXTURE, 3, 7), 1, 1, Formats.INDEX8);
        view.clear();
        Assertions.assertEquals(0, view.liveCount());
        Assertions.assertTrue(
            view.handles()
                .isEmpty());
        // the queue is 10, then 0-9 and 11-2 047 in ascending order, each a generation on: 2 for slots 1, 3 and 5,
        // freed twice, and 1 for the rest; freeing in live order would have queued 0, 2, 4, 6-9, 11-2 047, 5, 3, 1
        List<Integer> expected = new ArrayList<>();
        expected.add(texture(10, 1));
        for (int slot = 0; slot < SLOTS; slot++) {
            if (slot != 10) {
                expected.add(texture(slot, slot == 1 || slot == 3 || slot == 5 ? 2 : 1));
            }
        }
        List<Integer> created = new ArrayList<>();
        for (int k = 0; k < SLOTS; k++) {
            created.add(view.create(1, 1));
        }
        Assertions.assertEquals(expected, created);
        Assertions.assertThrows(IllegalStateException.class, () -> view.create(1, 1));
    }

    @Test
    void aResetKeepsTheGenerations() {
        MapLiveView view = view();
        int a = view.create(2, 2);
        int b = view.create(3, 3);
        view.clear();
        Assertions.assertFalse(view.isLive(a));
        Assertions.assertFalse(view.isLive(b));
        Assertions.assertEquals(0, view.textureFormat(a));
        // slots 2-2 047 come first, then 0 and 1 at generation 1
        for (int slot = 2; slot < SLOTS; slot++) {
            Assertions.assertEquals(texture(slot, 0), view.create(1, 1));
        }
        Assertions.assertEquals(texture(0, 1), view.create(1, 1));
        Assertions.assertEquals(texture(1, 1), view.create(1, 1));
    }

    private static MapLiveView view() {
        return new MapLiveView(new SurfaceDesc(0, 4, 3, Formats.INDEX8), FrameLimits.forTier(2));
    }

    private static int texture(int slot, int generation) {
        return Handles.pack(HandleTypes.TEXTURE, slot, generation);
    }
}
