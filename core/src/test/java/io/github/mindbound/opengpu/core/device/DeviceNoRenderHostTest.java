package io.github.mindbound.opengpu.core.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.mindbound.opengpu.core.cmd.ErrorCode;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;

/**
 * A device on {@code NoRenderHost} (28:204; 24:87, :228; RM:65), the dedicated server's host: {@code present} returns
 * {@code "no renderer"}, charged, and discards the open frame with its deferred frees, which fold at once (28:106), so
 * a submit/present loop never grows the frame; {@code readPixels} returns the cleared frame 0; and submits and texture
 * calls validate as with a renderer. Amendment A8's comparison with a {@code FakeRenderHost}: the same calls without
 * {@code present} give the same results, VRAM and live handles; {@code present} differs ({@code "no renderer"} here,
 * a frame id there), and with the fake host stepped after each present the later submit and texture results agree.
 */
class DeviceNoRenderHostTest {

    /** Two 4-byte codepoints (U+1F600 twice): 8 string bytes, 156 px of fill. */
    private static final String TWO_WIDE = "😀😀";

    @ParameterizedTest
    @ValueSource(ints = { 2, 3 })
    void presentIsNoRendererCharged(int tier) {
        DeviceRig rig = DeviceRig.noRenderer(tier);
        rig.submit(segment(tier == 2 ? clear(1) : clear(0xFF00FF00)));
        rig.budget.clearEvents();
        Assertions.assertArrayEquals(
            new Object[] { null, "no renderer" },
            rig.call(b -> rig.device.present(b))
                .toLua());
        Assertions.assertEquals(
            Collections.singletonList(tier == 2 ? "consume 0.062500000" : "consume 0.031250000"),
            rig.budget.events());
        DeviceSnapshot s = Invariants.check(rig.snapshot(), rig.device.caps());
        Assertions.assertEquals(0, s.lastFrameId);
        Assertions.assertTrue(s.openSegments.isEmpty(), "the open frame is discarded");
        Assertions.assertTrue(s.tail.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = { 2, 3 })
    void readPixelsIsTheClearedFrame0(int tier) {
        DeviceRig rig = DeviceRig.noRenderer(tier);
        for (int i = 0; i < 3; i++) {
            rig.submit(segment(tier == 2 ? clear(9) : clear(0xFFFFFFFF)));
            rig.call(b -> rig.device.present(b));
        }
        Assertions.assertArrayEquals(
            new byte[rig.device.surface()
                .byteSize()],
            rig.screen());
        CallResult r = rig.call(b -> rig.device.readPixels(b, 0, 0, 1, 1));
        Assertions.assertEquals(0L, r.frameId());
        Assertions.assertArrayEquals(
            new byte[rig.device.surface()
                .bytesPerPixel()],
            r.data());
    }

    /** A8: without present, the same calls give the same results and the same state as on a FakeRenderHost. */
    @Test
    void withoutPresentTheResultsAndTheStateAreAFakeHosts() {
        DeviceRig none = DeviceRig.noRenderer(2);
        DeviceRig fake = DeviceRig.tier(2);
        List<Function<DeviceRig, CallResult>> calls = script();
        List<Boolean> accepted = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            CallResult n = calls.get(i)
                .apply(none);
            CallResult f = calls.get(i)
                .apply(fake);
            Assertions.assertArrayEquals(f.toLua(), n.toLua(), "call " + i);
            Assertions.assertEquals(fake.budget.events(), none.budget.events(), "call " + i);
            Assertions.assertEquals(handleState(fake.snapshot()), handleState(none.snapshot()), "call " + i);
            accepted.add(n.isOk());
        }
        Invariants.check(none.snapshot(), none.device.caps());
        Assertions.assertEquals(
            Arrays.asList(true, true, true, true, false, false, true, false, true, true, false, true),
            accepted,
            "the script mixes accepted and refused calls");
    }

    /** A8: present differs, and with the fake host stepped after each present the later results agree. */
    @Test
    void presentDiffersButLaterResultsAgreeWithAFakeHostSteppedAfterEachPresent() {
        DeviceRig none = DeviceRig.noRenderer(2);
        DeviceRig fake = DeviceRig.tier(2);
        List<Function<DeviceRig, CallResult>> calls = script();
        int read = calls.size() - 1;
        for (int round = 1; round <= 3; round++) {
            for (int i = 0; i < calls.size(); i++) {
                CallResult n = calls.get(i)
                    .apply(none);
                CallResult f = calls.get(i)
                    .apply(fake);
                if (i == read) {
                    // readPixels is no submit or texture call: frame 0 here, the confirmed frame there
                    Assertions.assertEquals(0L, n.frameId());
                    Assertions.assertEquals(round - 1, f.frameId());
                    continue;
                }
                Assertions.assertArrayEquals(f.toLua(), n.toLua(), "round " + round + ", call " + i);
            }
            Assertions.assertArrayEquals(
                new Object[] { null, "no renderer" },
                none.call(b -> none.device.present(b))
                    .toLua());
            Assertions.assertArrayEquals(
                new Object[] { Long.valueOf(round) },
                fake.call(b -> fake.device.present(b))
                    .toLua());
            fake.step();
            Assertions.assertEquals(handleState(fake.snapshot()), handleState(none.snapshot()), "round " + round);
            Assertions
                .assertEquals(fake.snapshot().masters.keySet(), none.snapshot().masters.keySet(), "round " + round);
        }
    }

    /** 28:106: a loop on a dedicated server never grows the open frame, so the frame byte cap is never reached. */
    @Test
    void submitPresentLoopsNeverReachTheFrameByteCap() {
        byte[] big = textRecords(1800);
        Assertions.assertEquals(45009, big.length);
        // control: three such submits into one frame pass T2's 131 072-byte frame cap
        DeviceRig fake = DeviceRig.tier(2);
        fake.submit(big);
        fake.submit(big);
        CallResult third = fake.call(b -> fake.device.submit(b, big));
        Assertions.assertEquals(ErrorCode.OVER_FRAME_BYTES, third.streamError().code);
        Assertions.assertEquals(
            "0: submit: 135027 bytes with the open frame exceed the 131072-byte frame cap",
            third.message());
        DeviceRig none = DeviceRig.noRenderer(2);
        for (int i = 0; i < 100; i++) {
            Assertions.assertEquals(CallResult.TRUE, none.call(b -> none.device.submit(b, big)), "loop " + i);
            Assertions.assertEquals(CallResult.TRUE, none.call(b -> none.device.submit(b, big)), "loop " + i);
            Assertions.assertArrayEquals(
                new Object[] { null, "no renderer" },
                none.call(b -> none.device.present(b))
                    .toLua());
        }
        DeviceSnapshot s = Invariants.check(none.snapshot(), none.device.caps());
        Assertions.assertTrue(s.openSegments.isEmpty());
        Assertions.assertEquals(0, s.lastFrameId);
    }

    @Test
    void deferredFreesFoldAtOnceOnTheDiscard() {
        DeviceRig rig = DeviceRig.noRenderer(2);
        int t = rig.create(16, 16);
        int u = rig.create(8, 8);
        rig.submit(segment(blit(t, 0, 0, 16, 16, 0, 0, 0)));
        rig.free(t);
        rig.free(u);
        DeviceSnapshot s = Invariants.check(rig.snapshot(), rig.device.caps());
        Assertions.assertEquals(Arrays.asList(t, u), s.deferredFrees);
        Assertions.assertEquals(2, s.masters.size());
        rig.call(b -> rig.device.present(b));
        s = Invariants.check(rig.snapshot(), rig.device.caps());
        Assertions.assertTrue(s.deferredFrees.isEmpty());
        Assertions.assertTrue(s.masters.isEmpty());
        Assertions.assertTrue(s.tail.isEmpty());
        Assertions.assertEquals(0, s.queuedBytes);
    }

    @ParameterizedTest
    @ValueSource(ints = { 2, 3 })
    void getCapsIsTheSame(int tier) {
        Assertions.assertEquals(DeviceRig.tier(tier).device.getCaps(), DeviceRig.noRenderer(tier).device.getCaps());
    }

    // ---- helpers

    /**
     * A call sequence with accepted and refused submits and texture calls, every one of them through the rig's
     * budget, and no present.
     */
    private static List<Function<DeviceRig, CallResult>> script() {
        final int t = 0x08000000;
        final int u = 0x08010000;
        List<Function<DeviceRig, CallResult>> calls = new ArrayList<>();
        calls.add(r -> r.call(b -> r.device.createTexture(b, 16, 16, null)));
        calls.add(r -> r.call(b -> r.device.createTexture(b, 8, 4, "index8")));
        calls.add(r -> r.call(b -> r.device.updateTexture(b, t, 0, 0, 16, 16, DeviceOrderingTest.pattern(256, 1))));
        calls.add(r -> r.call(b -> r.device.submit(b, segment(clear(3), blit(t, 0, 0, 16, 16, 5, 5, 0)))));
        calls.add(r -> r.call(b -> r.device.submit(b, segment(fill(0, 0, 1, 1, 256)))));
        calls.add(r -> r.call(b -> r.device.updateTexture(b, u, 4, 0, 8, 4, new byte[32])));
        calls.add(r -> r.device.free(u));
        calls.add(r -> r.call(b -> r.device.submit(b, segment(blit(u, 0, 0, 1, 1, 0, 0, 0)))));
        calls.add(r -> r.call(b -> r.device.submit(b, segment(text(0, 0, 255, 0, 0, 0, "dedicated")))));
        calls.add(r -> r.call(b -> r.device.createTexture(b, 1024, 1023, null)));
        calls.add(r -> r.call(b -> r.device.createTexture(b, 1024, 1, null)));
        calls.add(r -> r.call(b -> r.device.readPixels(b, 0, 0, 16, 16)));
        return calls;
    }

    /** The live view's state: VRAM, live handles, the free queue, generations, the open frame and deferred frees. */
    private static String handleState(DeviceSnapshot s) {
        return "vram " + s.vramUsed
            + " live "
            + s.live
            + " free "
            + Arrays.hashCode(s.freeSlots())
            + " generations "
            + Arrays.hashCode(s.generations())
            + " open "
            + s.openSegments.size()
            + " "
            + s.openTotals
            + " deferred "
            + s.deferredFrees
            + " "
            + s.deferredBytes
            + " exhausted "
            + s.budgetExhausted;
    }

    /**
     * One segment of n TEXT records of two 4-byte codepoints each: 25 B and 156 px per record, so two segments stay
     * under T2's record and fill caps (4 096 records, 1 024 000 px) while three pass its frame byte cap.
     */
    private static byte[] textRecords(int n) {
        byte[][] records = new byte[n][];
        for (int i = 0; i < n; i++) {
            records[i] = text(i % 300, 0, 1, 0, 0, 0, TWO_WIDE);
        }
        return segment(records);
    }
}
