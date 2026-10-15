package io.github.mindbound.opengpu.core.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.RecordingBudget;

/**
 * The order "checks, charge, effect" (28:86-91) and {@code bitblt}'s two-phase pattern (28:710-713;
 * {@code GraphicsCard.scala:221-251}; plan §3.3-§3.4, H4, H14): a charge above the credit throws {@link LimitReached}
 * at the first attempt with nothing changed and the device's flag set, and OC's synchronized retry pauses when far
 * above
 * and proceeds uncharged, clearing the flag; a charge at the credit is a plain consume; the flag is shared by
 * createTexture and updateTexture and cleared by a plain charge, a failed check and a reset; a per-tick budget that
 * throws at consume leaves nothing appended until the retry; and the checks repeated under the monitor after the charge
 * (phase 3) return their error with the charge spent when the state changed in between. A release in between (B9's
 * second check; plan §3.10) makes phase 3 throw {@code IllegalStateException("device released")} with the charge spent,
 * nothing offered after the host's release and the state as release left it, for every charged callback: on the
 * consume of submit, present, readPixels and the plain createTexture and updateTexture, and on the pause of the
 * two-phase retry of both. The in-monitor checks that no charge separates from the first check (free's, and the
 * pre-charge checks of createTexture and updateTexture) are reachable only by a concurrent release, which
 * {@code DeviceLifecycleTest} races.
 */
class DeviceChargeOrderTest {

    private static final int T = 0x08000000;

    @Test
    void aT3TwoMegabyteCreateThrowsFirstThenPausesAndCreatesOnTheRetry() {
        DeviceRig rig = DeviceRig.tier(3);
        RecordingBudget budget = rig.budget;
        Assertions.assertThrows(LimitReached.class, () -> rig.device.createTexture(budget, 1024, 1024, null));
        DeviceSnapshot first = rig.snapshot();
        Assertions.assertTrue(first.budgetExhausted);
        Assertions.assertEquals(Collections.emptyList(), budget.events());
        Assertions.assertTrue(
            rig.offeredText()
                .isEmpty());
        Assertions.assertTrue(first.live.isEmpty());
        Assertions.assertEquals(0, first.vramUsed);
        // OC's retry next tick is a synchronized call (Machine.scala:286-294); it repeats the checks (28:91)
        budget.setSynchronized(true);
        CallResult r = rig.device.createTexture(budget, 1024, 1024, null);
        budget.setSynchronized(false);
        Assertions.assertEquals(T, r.handle());
        Assertions.assertEquals(Collections.singletonList("pause 0.166666667"), budget.events());
        Assertions.assertEquals(
            Collections.singletonList("TEX_CREATE e=1 tex=0x08000000 size=1024x1024 format=2"),
            rig.offeredText());
        DeviceSnapshot after = rig.snapshot();
        Assertions.assertFalse(after.budgetExhausted);
        Assertions.assertEquals(2097152, after.vramUsed);
    }

    @Test
    void throughOcsRetryTheEventsAreLimitThenThePause() {
        DeviceRig t3 = DeviceRig.tier(3);
        Assertions.assertEquals(T, t3.create(1024, 1024));
        Assertions.assertEquals(Arrays.asList("limit", "pause 0.166666667"), t3.budget.events());
        // T2's 1 MB create: c = 4, k = 1, a 0.1 s pause (28:706)
        DeviceRig t2 = DeviceRig.tier(2);
        Assertions.assertEquals(T, t2.create(1024, 1024));
        Assertions.assertEquals(Arrays.asList("limit", "pause 0.100000000"), t2.budget.events());
    }

    @Test
    void aFullT3SurfaceUpdateStallsOneTickWithoutAPause() {
        // 28:716: c = 1.953 125 > 1.5, and c − k = 0.453 125 ≤ k
        DeviceRig rig = DeviceRig.tier(3);
        int t = rig.create(640, 400);
        Assertions.assertEquals(Collections.singletonList("limit"), rig.budget.events());
        rig.budget.clearEvents();
        rig.update(t, 0, 0, 640, 400, new byte[512000]);
        Assertions.assertEquals(Collections.singletonList("limit"), rig.budget.events());
        Assertions.assertEquals(
            2,
            rig.host.offered(rig.key)
                .size());
        Assertions.assertFalse(rig.snapshot().budgetExhausted);
    }

    @Test
    void aChargeAtTheCreditIsAPlainConsume() {
        // plan §3.3: T2 262 144 B (c = 1.0 = k) and T3 393 216 B (c = 1.5 = k)
        DeviceRig t2 = DeviceRig.tier(2);
        int a = t2.create(512, 512);
        Assertions.assertEquals(Collections.singletonList("consume 1.000000000"), t2.budget.events());
        t2.budget.clearEvents();
        t2.update(a, 0, 0, 512, 512, new byte[262144]);
        Assertions.assertEquals(Collections.singletonList("consume 1.000000000"), t2.budget.events());
        DeviceRig t3 = DeviceRig.tier(3);
        int b = t3.create(384, 512);
        Assertions.assertEquals(Collections.singletonList("consume 1.500000000"), t3.budget.events());
        t3.budget.clearEvents();
        t3.update(b, 0, 0, 384, 512, new byte[393216]);
        Assertions.assertEquals(Collections.singletonList("consume 1.500000000"), t3.budget.events());
        Assertions.assertFalse(t3.snapshot().budgetExhausted);
    }

    @Test
    void aPlainConsumeThatThrowsIsRetriedSynchronizedAndAppliedOnce() {
        // c = k = 1.0 against a 0.5 budget: consume throws before any side effect (28:711), the retry consumes nothing
        DeviceRig rig = DeviceRig.tier(2);
        RecordingBudget half = new RecordingBudget(0.5);
        CallResult r = half.call(() -> rig.device.createTexture(half, 1024, 256, null));
        Assertions.assertEquals(T, r.handle());
        Assertions.assertEquals(Arrays.asList("consume 1.000000000", "limit", "consume 1.000000000"), half.events());
        Assertions.assertEquals(
            1,
            rig.offeredText()
                .size());
        Assertions.assertEquals(1, rig.snapshot().live.size());
    }

    @Test
    void anExhaustedTickBudgetStopsASubmitUntilTheSynchronizedRetry() {
        // a 64 KB submit at T2 costs 1/64 + 1/16 = 0.078 125 > 0.05
        DeviceRig rig = DeviceRig.tier(2);
        byte[] s = submitCapSegment();
        Assertions.assertEquals(65536, s.length);
        RecordingBudget tick = new RecordingBudget(0.05);
        String before = DeviceCallbackTableTest.state(rig.snapshot());
        Assertions.assertThrows(LimitReached.class, () -> rig.device.submit(tick, s));
        Assertions.assertEquals(before, DeviceCallbackTableTest.state(rig.snapshot()), "nothing appended");
        tick.clearEvents();
        CallResult r = tick.call(() -> rig.device.submit(tick, s));
        Assertions.assertEquals(CallResult.TRUE, r);
        Assertions.assertEquals(Arrays.asList("consume 0.078125000", "limit", "consume 0.078125000"), tick.events());
        DeviceSnapshot after = rig.snapshot();
        Assertions.assertEquals(1, after.openSegments.size());
        Assertions.assertSame(s, after.openSegments.get(0));
        Assertions.assertEquals(65536, after.openTotals.bytes);
    }

    @Test
    void presentIsChargedOnEveryStatusBeforeDecidingIt() {
        // 28:91: a loop that spins on "busy" pays for it
        DeviceRig rig = DeviceRig.tier(2);
        rig.present();
        rig.present();
        for (int i = 0; i < 3; i++) {
            Assertions.assertEquals(
                "busy",
                rig.call(b -> rig.device.present(b))
                    .message());
        }
        Assertions.assertEquals(Collections.nCopies(5, "consume 0.062500000"), rig.budget.events());
        // a budget that throws at present's consume stops it before the status
        RecordingBudget empty = new RecordingBudget(0);
        rig.step();
        Assertions.assertThrows(LimitReached.class, () -> rig.device.present(empty));
        Assertions.assertEquals(2, rig.snapshot().lastFrameId);
    }

    @Test
    void aFailedCheckClearsTheFlag() {
        // 28:713: "if one fails, it clears the flag"
        DeviceRig rig = DeviceRig.tier(3);
        Assertions.assertThrows(LimitReached.class, () -> rig.device.createTexture(rig.budget, 1024, 1024, null));
        Assertions.assertTrue(rig.snapshot().budgetExhausted);
        Assertions.assertEquals(
            "invalid texture size",
            rig.device.createTexture(rig.budget, 0, 1, null)
                .message());
        Assertions.assertFalse(rig.snapshot().budgetExhausted);
        // so the big create starts over: LimitReached again, not the retry's pause
        Assertions.assertThrows(LimitReached.class, () -> rig.device.createTexture(rig.budget, 1024, 1024, null));
        // an updateTexture's failed check clears it too
        Assertions.assertEquals(
            "handle 0x08000000 is not live",
            rig.device.updateTexture(rig.budget, T, 0, 0, 1, 1, new byte[2])
                .message());
        Assertions.assertFalse(rig.snapshot().budgetExhausted);
        Assertions.assertEquals(Collections.emptyList(), rig.budget.events());
    }

    @Test
    void aPlainChargeClearsTheFlag() {
        DeviceRig rig = DeviceRig.tier(3);
        Assertions.assertThrows(LimitReached.class, () -> rig.device.createTexture(rig.budget, 1024, 1024, null));
        Assertions.assertEquals(
            T,
            rig.device.createTexture(rig.budget, 16, 16, null)
                .handle());
        Assertions.assertFalse(rig.snapshot().budgetExhausted);
        Assertions.assertThrows(LimitReached.class, () -> rig.device.createTexture(rig.budget, 1024, 1024, null));
        Assertions.assertEquals(Collections.singletonList("consume 0.001953125"), rig.budget.events());
    }

    @Test
    void createAndUpdateShareTheFlag() {
        // as bitblt's per-card flag: an update's first attempt lets the next big create proceed with its pause
        DeviceRig rig = DeviceRig.tier(3);
        int t = rig.create(640, 400);
        rig.budget.clearEvents();
        Assertions.assertThrows(
            LimitReached.class,
            () -> rig.device.updateTexture(rig.budget, t, 0, 0, 640, 400, new byte[512000]));
        Assertions.assertTrue(rig.snapshot().budgetExhausted);
        Assertions.assertEquals(
            0x08010000,
            rig.device.createTexture(rig.budget, 1024, 1024, null)
                .handle());
        Assertions.assertEquals(Collections.singletonList("pause 0.166666667"), rig.budget.events());
        Assertions.assertFalse(rig.snapshot().budgetExhausted);
    }

    @Test
    void aResetClearsTheFlag() {
        // H14
        DeviceRig rig = DeviceRig.tier(3);
        Assertions.assertThrows(LimitReached.class, () -> rig.device.createTexture(rig.budget, 1024, 1024, null));
        Assertions.assertTrue(rig.snapshot().budgetExhausted);
        rig.device.reset();
        Assertions.assertFalse(rig.snapshot().budgetExhausted);
    }

    @Test
    void aResetBetweenAnUpdatesAttemptAndItsRetryMakesTheRetryFailUncharged() {
        // artificial: OC never retries across a reboot, but the retry must still repeat its checks (28:91)
        DeviceRig rig = DeviceRig.tier(3);
        int t = rig.create(640, 400);
        rig.budget.clearEvents();
        Assertions.assertThrows(
            LimitReached.class,
            () -> rig.device.updateTexture(rig.budget, t, 0, 0, 640, 400, new byte[512000]));
        rig.device.reset();
        rig.budget.setSynchronized(true);
        CallResult r = rig.device.updateTexture(rig.budget, t, 0, 0, 640, 400, new byte[512000]);
        rig.budget.setSynchronized(false);
        Assertions.assertEquals("handle 0x08000000 is not live", r.message());
        Assertions.assertEquals(Collections.emptyList(), rig.budget.events());
        List<String> offered = rig.offeredText();
        Assertions.assertTrue(
            offered.get(offered.size() - 1)
                .startsWith("RESET e=2 "),
            offered.toString());
        Assertions.assertFalse(rig.snapshot().budgetExhausted);
    }

    @Test
    void aResetBetweenACreatesAttemptAndItsRetryStartsThePatternOver() {
        // the reset cleared the flag, so OC's one retry throws LimitReached again; the next attempt pauses
        final DeviceRig rig = DeviceRig.tier(3);
        final int[] attempt = { 0 };
        Assertions.assertThrows(LimitReached.class, () -> rig.budget.call(() -> {
            if (attempt[0]++ == 1) {
                rig.device.reset();
            }
            return rig.device.createTexture(rig.budget, 1024, 1024, null);
        }));
        Assertions.assertEquals(2, attempt[0]);
        Assertions.assertEquals(Arrays.asList("limit", "limit"), rig.budget.events());
        Assertions.assertTrue(rig.snapshot().budgetExhausted);
        Assertions.assertEquals(
            1,
            rig.offeredText()
                .size(),
            "only the RESET");
        rig.budget.clearEvents();
        Assertions.assertEquals(
            T,
            rig.call(b -> rig.device.createTexture(b, 1024, 1024, null))
                .handle());
        Assertions.assertEquals(Collections.singletonList("pause 0.166666667"), rig.budget.events());
        Assertions.assertEquals(
            2,
            rig.host.offered(rig.key)
                .get(1).epoch);
    }

    @Test
    void anUpdateRecheckedAfterAPlainChargeFailsWithTheChargeSpent() {
        // phase 3 (plan §3.4): the budget call runs outside the monitor, and the state may change meanwhile (H4)
        DeviceRig rig = DeviceRig.tier(2);
        int t = rig.create(16, 16);
        ScriptedBudget budget = new ScriptedBudget(() -> rig.device.free(t), null);
        CallResult r = rig.device.updateTexture(budget, t, 0, 0, 16, 16, new byte[256]);
        Assertions.assertEquals("handle 0x08000000 is not live", r.message());
        Assertions.assertEquals(Collections.singletonList("consume 0.000976563"), budget.events);
        Assertions.assertEquals(
            Arrays.asList("TEX_CREATE e=1 tex=0x08000000 size=16x16 format=1", "TEX_FREE e=1 tex=0x08000000"),
            rig.offeredText());
        Assertions.assertFalse(rig.snapshot().budgetExhausted);
    }

    @Test
    void aCreateRecheckedAfterAPlainChargeFailsWithTheChargeSpent() {
        DeviceRig rig = DeviceRig.of(DeviceCreateTextureTest.small());
        for (int i = 0; i < 5; i++) {
            rig.create(1, 1);
        }
        // while the outer create is charged, another call takes the sixth slot
        ScriptedBudget budget = new ScriptedBudget(() -> rig.create(1, 1), null);
        CallResult r = rig.device.createTexture(budget, 1, 1, null);
        Assertions.assertEquals("too many textures", r.message());
        Assertions.assertEquals(Collections.singletonList("consume 0.000003815"), budget.events);
        Assertions.assertEquals(
            6,
            rig.offeredText()
                .size());
        Assertions.assertEquals(6, rig.snapshot().live.size());
    }

    @Test
    void aTwoPhaseRetryRecheckedAfterItsPauseFailsWithThePauseSpent() {
        // T3 1 024 × 400 rgb565: c = 3.125, c − k = 1.625 > k, so the retry pauses ((1.625 − 1.5)/1.5)/20 s
        DeviceRig rig = DeviceRig.tier(3);
        int t = rig.create(1024, 400);
        byte[] whole = new byte[819200];
        Assertions
            .assertThrows(LimitReached.class, () -> rig.device.updateTexture(rig.budget, t, 0, 0, 1024, 400, whole));
        ScriptedBudget retry = new ScriptedBudget(null, () -> rig.device.free(t));
        CallResult r = rig.device.updateTexture(retry, t, 0, 0, 1024, 400, whole);
        Assertions.assertEquals("handle 0x08000000 is not live", r.message());
        Assertions.assertEquals(Collections.singletonList("pause 0.004166667"), retry.events);
        Assertions.assertEquals(
            2,
            rig.offeredText()
                .size());
        Assertions.assertFalse(rig.snapshot().budgetExhausted);
    }

    @Test
    void aReleaseDuringSubmitsChargeThrowsInPhase3() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.submit(segment(clear(1)));
        byte[] s = segment(clear(2));
        Release release = new Release(rig);
        ScriptedBudget budget = new ScriptedBudget(release, null);
        assertReleasedInPhase3(rig, release, () -> rig.device.submit(budget, s));
        Assertions.assertEquals(Collections.singletonList(consume(Charges.submit(2, s.length))), budget.events);
    }

    @Test
    void aReleaseDuringPresentsChargeThrowsInPhase3() {
        // an open frame with a deferred free: release drops both, and nothing reaches the host
        DeviceRig rig = DeviceRig.tier(2);
        int t = rig.create(16, 16);
        rig.submit(segment(clear(3)));
        rig.free(t);
        Release release = new Release(rig);
        ScriptedBudget budget = new ScriptedBudget(release, null);
        assertReleasedInPhase3(rig, release, () -> rig.device.present(budget));
        Assertions.assertEquals(Collections.singletonList("consume 0.062500000"), budget.events);
        Assertions.assertEquals(0, release.snapshot.lastFrameId);
    }

    @Test
    void aReleaseDuringReadPixelsChargeThrowsInPhase3() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.submit(segment(clear(4)));
        rig.present();
        rig.step();
        Release release = new Release(rig);
        ScriptedBudget budget = new ScriptedBudget(release, null);
        assertReleasedInPhase3(rig, release, () -> rig.device.readPixels(budget, 0, 0, 320, 100));
        Assertions.assertEquals(Collections.singletonList(consume(Charges.bytes(32000))), budget.events);
    }

    @Test
    void aReleaseDuringAPlainCreatesChargeThrowsInPhase3() {
        // T2 512 × 512: c = 1.0 = k, a plain consume
        DeviceRig rig = DeviceRig.tier(2);
        Release release = new Release(rig);
        ScriptedBudget budget = new ScriptedBudget(release, null);
        assertReleasedInPhase3(rig, release, () -> rig.device.createTexture(budget, 512, 512, null));
        Assertions.assertEquals(Collections.singletonList("consume 1.000000000"), budget.events);
        Assertions.assertTrue(release.snapshot.live.isEmpty());
    }

    @Test
    void aReleaseDuringAPlainUpdatesChargeThrowsInPhase3() {
        // T2 512 × 400 of a 512 × 512 texture: c = 0.781 25 < k
        DeviceRig rig = DeviceRig.tier(2);
        int t = rig.create(512, 512);
        Release release = new Release(rig);
        ScriptedBudget budget = new ScriptedBudget(release, null);
        assertReleasedInPhase3(
            rig,
            release,
            () -> rig.device.updateTexture(budget, t, 0, 0, 512, 400, new byte[204800]));
        Assertions.assertEquals(Collections.singletonList("consume 0.781250000"), budget.events);
    }

    @Test
    void aReleaseDuringATwoPhaseCreatesPauseThrowsInPhase3() {
        DeviceRig rig = DeviceRig.tier(3);
        Assertions.assertThrows(LimitReached.class, () -> rig.device.createTexture(rig.budget, 1024, 1024, null));
        Release release = new Release(rig);
        ScriptedBudget retry = new ScriptedBudget(null, release);
        assertReleasedInPhase3(rig, release, () -> rig.device.createTexture(retry, 1024, 1024, null));
        Assertions.assertEquals(Collections.singletonList("pause 0.166666667"), retry.events);
        Assertions.assertTrue(release.snapshot.live.isEmpty());
        Assertions.assertEquals(0, release.snapshot.vramUsed);
    }

    @Test
    void aReleaseDuringATwoPhaseUpdatesPauseThrowsInPhase3() {
        // T3 1 024 × 400 rgb565: c = 3.125, c − k = 1.625 > k, so the retry pauses
        DeviceRig rig = DeviceRig.tier(3);
        int t = rig.create(1024, 400);
        byte[] whole = new byte[819200];
        Assertions
            .assertThrows(LimitReached.class, () -> rig.device.updateTexture(rig.budget, t, 0, 0, 1024, 400, whole));
        Release release = new Release(rig);
        ScriptedBudget retry = new ScriptedBudget(null, release);
        assertReleasedInPhase3(rig, release, () -> rig.device.updateTexture(retry, t, 0, 0, 1024, 400, whole));
        Assertions.assertEquals(Collections.singletonList("pause 0.004166667"), retry.events);
    }

    @Test
    void limitReachedCarriesNoStackTrace() {
        LimitReached e = new LimitReached();
        Assertions.assertEquals(0, e.getStackTrace().length);
        Assertions.assertNull(e.getCause());
        Assertions.assertTrue(e instanceof RuntimeException);
        // the device's own is thrown the same way
        DeviceRig rig = DeviceRig.tier(3);
        LimitReached thrown = Assertions
            .assertThrows(LimitReached.class, () -> rig.device.createTexture(rig.budget, 1024, 1024, null));
        Assertions.assertEquals(0, thrown.getStackTrace().length);
    }

    /**
     * A 65 536-byte T2 segment, valid and within the frame caps: 51 TEXT records of 256 four-byte codepoints
     * (1 041 B, 19 968 px each, 1 018 368 px in all), two empty TEXT records and 954 FILL_RECT of width 0, 1 007
     * records.
     */
    static byte[] submitCapSegment() {
        byte[] emoji = new byte[1024];
        for (int i = 0; i < emoji.length; i += 4) {
            emoji[i] = (byte) 0xF0;
            emoji[i + 1] = (byte) 0x9F;
            emoji[i + 2] = (byte) 0x98;
            emoji[i + 3] = (byte) 0x80;
        }
        List<byte[]> records = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            records.add(text(0, 0, 1, 0, 0, 0, emoji));
        }
        records.add(text(0, 0, 1, 0, 0, 0, new byte[0]));
        records.add(text(0, 0, 1, 0, 0, 0, new byte[0]));
        for (int i = 0; i < 954; i++) {
            records.add(fill(0, 0, 0, 1, 1));
        }
        return segment(records.toArray(new byte[0][]));
    }

    private static String consume(double cost) {
        return String.format(Locale.ROOT, "consume %.9f", cost);
    }

    /**
     * B9's second check: the call throws {@code "device released"}, the host's log ends with the release, nothing was
     * offered after it, and the state is the one release left, but for the two-phase retry's clearing of the exhausted
     * flag after its pause, which comes before phase 3 (28:712).
     */
    private static void assertReleasedInPhase3(DeviceRig rig, Release release, Executable call) {
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class, call);
        Assertions.assertEquals("device released", e.getMessage());
        Assertions.assertNotNull(release.snapshot, "the budget's hook did not run");
        Assertions.assertTrue(release.snapshot.released);
        List<String> log = rig.host.log();
        Assertions.assertEquals("release DeviceKey[1:1]", log.get(log.size() - 1));
        Assertions.assertEquals(release.offered, rig.offeredText(), "nothing offered after the host's release");
        DeviceSnapshot after = rig.snapshot();
        Assertions.assertFalse(after.budgetExhausted);
        Assertions.assertEquals(
            Invariants.describe(release.snapshot)
                .replace(" exhausted true ", " exhausted false "),
            Invariants.describe(after));
    }

    /** A budget hook that releases the device (28:151), then keeps its snapshot and the records offered so far. */
    private static final class Release implements Runnable {

        private final DeviceRig rig;
        DeviceSnapshot snapshot;
        List<String> offered;

        Release(DeviceRig rig) {
            this.rig = rig;
        }

        @Override
        public void run() {
            rig.device.release();
            snapshot = rig.snapshot();
            offered = rig.offeredText();
        }
    }

    /** Records "consume %.9f" and "pause %.9f" and runs a hook on the first consume or pause: a concurrent caller. */
    private static final class ScriptedBudget implements CallBudget {

        final List<String> events = new ArrayList<>();
        private Runnable onConsume;
        private Runnable onPause;

        ScriptedBudget(Runnable onConsume, Runnable onPause) {
            this.onConsume = onConsume;
            this.onPause = onPause;
        }

        @Override
        public void consume(double cost) {
            events.add(String.format(Locale.ROOT, "consume %.9f", cost));
            Runnable hook = onConsume;
            onConsume = null;
            if (hook != null) {
                hook.run();
            }
        }

        @Override
        public void pause(double seconds) {
            events.add(String.format(Locale.ROOT, "pause %.9f", seconds));
            Runnable hook = onPause;
            onPause = null;
            if (hook != null) {
                hook.run();
            }
        }
    }
}
