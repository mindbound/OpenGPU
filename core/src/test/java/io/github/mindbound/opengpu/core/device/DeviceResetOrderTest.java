package io.github.mindbound.opengpu.core.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.cmd.HandleTypes;
import io.github.mindbound.opengpu.core.fixtures.MapLiveView;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;

/**
 * The free queue across a reset (28 §6.1, 28:533-535; 28:1061, S6's settlement): a reset frees every live handle in
 * ascending slot order, each going to the queue's tail after the slots already queued, with its generation bumped,
 * exactly as S6's {@link MapLiveView#clear} does; generations are never reset. The handles creates return afterwards
 * follow that queue, which a differential against {@code MapLiveView} over seeded create/free/reset sequences checks.
 */
class DeviceResetOrderTest {

    @Test
    void aResetQueuesTheLiveSlotsAscendingBehindTheFreeOnes() {
        DeviceRig rig = DeviceRig.tier(2);
        for (int slot = 0; slot < 5; slot++) {
            Assertions.assertEquals(handle(slot, 0), rig.create(1, 1));
        }
        rig.free(handle(2, 0));
        rig.device.reset();
        DeviceSnapshot s = Invariants.check(rig.snapshot(), rig.device.caps());
        List<Integer> expected = new ArrayList<>();
        for (int slot = 5; slot <= Handles.MAX_SLOT; slot++) {
            expected.add(slot);
        }
        expected.add(2);
        expected.add(0);
        expected.add(1);
        expected.add(3);
        expected.add(4);
        Assertions.assertEquals(expected, list(s.freeSlots()));
        int[] generations = s.generations();
        for (int slot = 0; slot < 5; slot++) {
            Assertions.assertEquals(1, generations[slot], "slot " + slot);
        }
        for (int slot = 5; slot <= Handles.MAX_SLOT; slot++) {
            Assertions.assertEquals(0, generations[slot], "slot " + slot);
        }
        Assertions.assertTrue(s.live.isEmpty());
    }

    @Test
    void theCreatesAfterAResetFollowThatQueue() {
        DeviceRig rig = DeviceRig.tier(2);
        for (int slot = 0; slot < 5; slot++) {
            rig.create(1, 1);
        }
        rig.free(handle(2, 0));
        rig.device.reset();
        // 2 043 create/free pairs walk slots 5..2 047, each requeued behind slot 4
        for (int slot = 5; slot <= Handles.MAX_SLOT; slot++) {
            int h = rig.create(1, 1);
            Assertions.assertEquals(handle(slot, 0), h);
            rig.free(h);
        }
        Assertions.assertEquals(0x08020001, rig.create(1, 1));
        Assertions.assertEquals(0x08000001, rig.create(1, 1));
        Assertions.assertEquals(0x08010001, rig.create(1, 1));
        Assertions.assertEquals(0x08030001, rig.create(1, 1));
        Assertions.assertEquals(0x08040001, rig.create(1, 1));
        Assertions.assertEquals(handle(5, 1), rig.create(1, 1));
        Invariants.check(rig.snapshot(), rig.device.caps());
    }

    @Test
    void generationsSurviveResetsAndHandlesOfEarlierEpochsStayStale() {
        DeviceRig rig = DeviceRig.tier(2);
        int t = rig.create(4, 4);
        rig.device.reset();
        Assertions.assertEquals(
            "handle 0x08000000 is not live",
            rig.device.free(t)
                .message());
        for (int i = 0; i < 3; i++) {
            rig.free(rig.create(1, 1));
            rig.device.reset();
        }
        byte[] blitT = segment(blit(t, 0, 0, 4, 4, 0, 0, 0));
        Assertions.assertEquals(
            "8: BLIT: handle 0x08000000 is not live",
            rig.call(b -> rig.device.submit(b, blitT))
                .message());
        Assertions.assertEquals(
            1,
            rig.snapshot()
                .generations()[0]);
        Assertions.assertEquals(
            1,
            rig.snapshot()
                .generations()[1]);
    }

    /** 200 seeded sequences of creates, frees, submits, presents and resets: the same handles as MapLiveView. */
    @Test
    void theHandlesMatchMapLiveViewOverSeededSequences() {
        byte[] noOp = segment();
        for (int seed = 0; seed < 200; seed++) {
            Random random = new Random(seed);
            DeviceRig rig = seed % 2 == 0 ? DeviceRig.noRenderer(2) : DeviceRig.tier(2);
            MapLiveView view = new MapLiveView(rig.device.surface(), rig.device.limits());
            List<Integer> live = new ArrayList<>();
            StringBuilder ops = new StringBuilder();
            for (int op = 0; op < 100; op++) {
                int r = random.nextInt(100);
                String where = String.format(Locale.ROOT, "seed %d op %d after%s", seed, op, ops);
                if (r < 45) {
                    int w = 1 + random.nextInt(8);
                    int h = 1 + random.nextInt(8);
                    int expected = view.create(w, h);
                    int got = rig.create(w, h);
                    Assertions.assertEquals(Invariants.hex(expected), Invariants.hex(got), where);
                    live.add(got);
                    ops.append(" c");
                } else if (r < 75) {
                    if (live.isEmpty()) {
                        continue;
                    }
                    int h = live.remove(random.nextInt(live.size()));
                    view.free(h);
                    rig.free(h);
                    ops.append(" f");
                } else if (r < 85) {
                    rig.submit(noOp);
                    ops.append(" s");
                } else if (r < 95) {
                    rig.call(b -> rig.device.present(b));
                    rig.step();
                    ops.append(" p");
                } else {
                    view.clear();
                    rig.device.reset();
                    rig.step();
                    live.clear();
                    ops.append(" r");
                }
                List<Integer> deviceLive = new ArrayList<>();
                for (DeviceSnapshot.LiveTexture t : rig.snapshot().live) {
                    deviceLive.add(t.handle);
                }
                List<Integer> viewLive = new ArrayList<>(view.handles());
                Collections.sort(viewLive, (a, b) -> Integer.compare(Handles.slot(a), Handles.slot(b)));
                Assertions.assertEquals(viewLive, deviceLive, where);
            }
            Invariants.check(rig.snapshot(), rig.device.caps());
        }
    }

    private static int handle(int slot, int generation) {
        return Handles.pack(HandleTypes.TEXTURE, slot, generation);
    }

    private static List<Integer> list(int[] values) {
        List<Integer> out = new ArrayList<>(values.length);
        for (int v : values) {
            out.add(v);
        }
        return out;
    }
}
