package io.github.mindbound.opengpu.core.fixtures.device;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.device.CallResult;
import io.github.mindbound.opengpu.core.device.LimitReached;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;

/**
 * The recording budget against OC's {@code Machine} (28 §8.2): a per-tick budget that {@code consume} throws beyond
 * and subtracts from ({@code Machine.scala:286-294}), the synchronized call in which it does nothing, the refill per
 * tick, and {@code call()}, OC's direct call that retries once as a synchronized call after {@code LimitReached}
 * ({@code NativeLuaArchitecture.scala:72-77}). Costs are binary fractions, so the arithmetic is exact.
 */
class RecordingBudgetTest {

    @Test
    void anUnlimitedBudgetRecordsEveryCallAndNeverThrows() {
        RecordingBudget budget = new RecordingBudget();
        budget.consume(0.015625);
        budget.consume(1e9);
        budget.pause(0.1);
        budget.consume(-1);
        budget.pause(1.0 / 6);
        Assertions.assertEquals(
            Arrays.asList(
                "consume 0.015625000",
                "consume 1000000000.000000000",
                "pause 0.100000000",
                "consume -1.000000000",
                "pause 0.166666667"),
            budget.events());
        Assertions.assertEquals(Double.POSITIVE_INFINITY, budget.remaining());
        // events() is a copy
        budget.events()
            .clear();
        Assertions.assertEquals(
            5,
            budget.events()
                .size());
        budget.clearEvents();
        Assertions.assertEquals(Collections.emptyList(), budget.events());
    }

    @Test
    void aPerTickBudgetThrowsBeyondWhatIsLeftAsOcsMachineDoes() {
        RecordingBudget budget = new RecordingBudget(0.25);
        Assertions.assertEquals(0.25, budget.remaining());
        budget.consume(0.125);
        // equal to what is left does not exceed it (clampedCost > callBudget, Machine.scala:289)
        budget.consume(0.125);
        Assertions.assertEquals(0.0, budget.remaining());
        Assertions.assertThrows(LimitReached.class, () -> budget.consume(0.0078125));
        Assertions.assertEquals(0.0, budget.remaining());
        // a negative cost is clamped to 0: it neither throws nor refills
        budget.consume(-1);
        budget.consume(0);
        Assertions.assertEquals(0.0, budget.remaining());
        // the next tick refills it (Machine.scala:532)
        budget.newTick();
        Assertions.assertEquals(0.25, budget.remaining());
        budget.consume(0.25);
        Assertions.assertThrows(LimitReached.class, () -> budget.consume(0.0078125));
        // every consume is recorded, also those that throw
        Assertions.assertEquals(
            Arrays.asList(
                "consume 0.125000000",
                "consume 0.125000000",
                "consume 0.007812500",
                "consume -1.000000000",
                "consume 0.000000000",
                "consume 0.250000000",
                "consume 0.007812500"),
            budget.events());
    }

    @Test
    void aSynchronizedCallConsumesNothing() {
        RecordingBudget budget = new RecordingBudget(0.25);
        Assertions.assertFalse(budget.isSynchronized());
        budget.setSynchronized(true);
        Assertions.assertTrue(budget.isSynchronized());
        budget.consume(5);
        Assertions.assertEquals(0.25, budget.remaining());
        budget.setSynchronized(false);
        Assertions.assertThrows(LimitReached.class, () -> budget.consume(0.5));
        Assertions.assertEquals(Arrays.asList("consume 5.000000000", "consume 0.500000000"), budget.events());
    }

    @Test
    void callRunsOnceWithoutALimit() {
        RecordingBudget budget = new RecordingBudget(0.25);
        int[] runs = { 0 };
        CallResult r = budget.call(() -> {
            runs[0]++;
            budget.consume(0.125);
            return CallResult.TRUE;
        });
        Assertions.assertSame(CallResult.TRUE, r);
        Assertions.assertEquals(1, runs[0]);
        Assertions.assertEquals(Arrays.asList("consume 0.125000000"), budget.events());
    }

    @Test
    void callRetriesOnceAsASynchronizedCallAfterALimit() {
        RecordingBudget budget = new RecordingBudget(0.25);
        List<Boolean> modes = new ArrayList<>();
        CallResult r = budget.call(() -> {
            modes.add(budget.isSynchronized());
            budget.consume(0.5);
            return CallResult.handle(0x08000000);
        });
        Assertions.assertEquals(CallResult.handle(0x08000000), r);
        // first direct, then synchronized; the synchronized consume charged nothing
        Assertions.assertEquals(Arrays.asList(false, true), modes);
        Assertions.assertEquals(Arrays.asList("consume 0.500000000", "limit", "consume 0.500000000"), budget.events());
        Assertions.assertFalse(budget.isSynchronized());
        Assertions.assertEquals(0.25, budget.remaining());

        // the mode it had before is restored
        budget.clearEvents();
        budget.setSynchronized(true);
        int[] runs = { 0 };
        budget.call(() -> {
            if (runs[0]++ == 0) {
                throw new LimitReached();
            }
            return CallResult.TRUE;
        });
        Assertions.assertEquals(2, runs[0]);
        Assertions.assertTrue(budget.isSynchronized());
        Assertions.assertEquals(Arrays.asList("limit"), budget.events());
    }

    @Test
    void aLimitFromTheRetryIsRecordedAndPropagates() {
        RecordingBudget budget = new RecordingBudget();
        int[] runs = { 0 };
        Assertions.assertThrows(LimitReached.class, () -> budget.call(() -> {
            runs[0]++;
            throw new LimitReached();
        }));
        Assertions.assertEquals(2, runs[0]);
        Assertions.assertEquals(Arrays.asList("limit", "limit"), budget.events());
        Assertions.assertFalse(budget.isSynchronized());
        // other exceptions pass through without a retry
        runs[0] = 0;
        Assertions.assertThrows(IllegalStateException.class, () -> budget.call(() -> {
            runs[0]++;
            throw new IllegalStateException("device released");
        }));
        Assertions.assertEquals(1, runs[0]);
    }

    @Test
    void onADeviceTheTwoPhaseCreateAndAnExhaustedSubmitEachRetryOnce() {
        // 28:715-716: T2 1 024×1 024 create, c = 4 > k = 1: LimitReached first, the retry pauses ((4 - 1) - 1)/1/20
        DeviceRig rig = DeviceRig.tier(2);
        CallResult created = rig.call(b -> rig.device.createTexture(b, 1024, 1024, null));
        Assertions.assertEquals(CallResult.handle(0x08000000), created);
        Assertions.assertEquals(Arrays.asList("limit", "pause 0.100000000"), rig.budget.events());

        // a plain charge beyond the tick's budget: submit's 1/64 + 9/1 MB = 0.015633583 > 0.01 throws at consume
        // before validation, so nothing is appended, and the synchronized retry appends the segment once (28:86-91)
        RecordingBudget tight = new RecordingBudget(0.01);
        byte[] noOp = TestEncoder.segment();
        Assertions.assertEquals(9, noOp.length);
        Assertions.assertEquals(CallResult.TRUE, tight.call(() -> rig.device.submit(tight, noOp)));
        Assertions.assertEquals(Arrays.asList("consume 0.015633583", "limit", "consume 0.015633583"), tight.events());
        Assertions.assertEquals(1, rig.snapshot().openSegments.size());
        Assertions.assertSame(noOp, rig.snapshot().openSegments.get(0));
    }

    @Test
    void aBudgetIsNeverNegativeOrNaN() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new RecordingBudget(-0.5));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new RecordingBudget(Double.NaN));
        RecordingBudget empty = new RecordingBudget(0);
        empty.consume(0);
        Assertions.assertThrows(LimitReached.class, () -> empty.consume(Double.MIN_VALUE));
    }
}
