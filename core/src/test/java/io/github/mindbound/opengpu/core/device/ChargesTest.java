package io.github.mindbound.opengpu.core.device;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 28 §8.2's charge amounts as pure functions, at T1-T3 (T1 for the formulas only; it is M1's tier): submit's
 * 1/32·2^−t + min(#s, 65 536)/1 MB (28:702), present's 1/8, 1/16, 1/32 (28:703), the bytes/256 KB of readPixels,
 * updateTexture and createTexture (28:704-706), the credit k = (t + 1)·0.5 and the two-phase pattern's threshold and
 * pause (28:710-713), with 28:715-717's and plan §3.3's reference values. Every amount below is a binary fraction, so
 * the comparisons are exact except the one third in T3's pause.
 */
class ChargesTest {

    @Test
    void theUnitsAre256KiBAnd1MiB() {
        Assertions.assertEquals(262144.0, Charges.BYTES_PER_UNIT);
        Assertions.assertEquals(1048576.0, Charges.SUBMIT_BYTES_PER_UNIT);
    }

    @Test
    void submitIsTheTierBasePlusTheChargedBytes() {
        // 28:702: 1/32·2^−t, t = tier − 1
        double[] base = { 1.0 / 32, 1.0 / 64, 1.0 / 128 };
        for (int tier = 1; tier <= 3; tier++) {
            double b = base[tier - 1];
            Assertions.assertEquals(b, Charges.submit(tier, 0), "tier " + tier);
            Assertions.assertEquals(b + 9 / 1048576.0, Charges.submit(tier, 9), "tier " + tier);
            Assertions.assertEquals(b + 64 / 1048576.0, Charges.submit(tier, 64), "tier " + tier);
            Assertions.assertEquals(b + 0.0625, Charges.submit(tier, 65536), "tier " + tier);
            // min(#s, 65 536): an oversized string pays no more than the cap (28:719)
            Assertions.assertEquals(b + 0.0625, Charges.submit(tier, 70000), "tier " + tier);
            Assertions.assertEquals(b + 0.0625, Charges.submit(tier, Integer.MAX_VALUE), "tier " + tier);
        }
        Assertions.assertEquals(0.015625, Charges.submit(2, 0));
        Assertions.assertEquals(0.0078125, Charges.submit(3, 0));
        Assertions.assertEquals(0.078125, Charges.submit(2, 70000));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Charges.submit(2, -1));
    }

    @Test
    void submitNeverReachesTheLowestCredit() {
        // 28:680, :702: at most 0.094, below the pack's lowest budget 0.5, so never two-phase
        for (int tier = 1; tier <= 3; tier++) {
            double most = Charges.submit(tier, Integer.MAX_VALUE);
            Assertions.assertTrue(most <= 0.094, "tier " + tier + ": " + most);
            Assertions.assertFalse(Charges.twoPhase(tier, most), "tier " + tier);
        }
        Assertions.assertEquals(0.09375, Charges.submit(1, 65536));
    }

    @Test
    void presentHalvesPerTier() {
        // 28:703
        Assertions.assertEquals(0.125, Charges.present(1));
        Assertions.assertEquals(0.0625, Charges.present(2));
        Assertions.assertEquals(0.03125, Charges.present(3));
    }

    @Test
    void bytesAreChargedPer256KiB() {
        // 28:704-706
        Assertions.assertEquals(0.0, Charges.bytes(0));
        Assertions.assertEquals(1 / 262144.0, Charges.bytes(1));
        Assertions.assertEquals(0.0009765625, Charges.bytes(256));
        Assertions.assertEquals(0.25, Charges.bytes(65536));
        Assertions.assertEquals(1.0, Charges.bytes(262144));
        // 28:716: a full T3 surface upload
        Assertions.assertEquals(1.953125, Charges.bytes(512000));
        // 28:706, :717: the largest textures, 1 MB at T2 and 2 MB at T3
        Assertions.assertEquals(4.0, Charges.bytes(1048576));
        Assertions.assertEquals(8.0, Charges.bytes(2097152));
        // a long argument: no int overflow
        Assertions.assertEquals(8192.0, Charges.bytes(1L << 31));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Charges.bytes(-1));
    }

    @Test
    void theCreditIsHalfTheOneBasedTier() {
        // GraphicsCard.scala:235, 28:710
        Assertions.assertEquals(0.5, Charges.credit(1));
        Assertions.assertEquals(1.0, Charges.credit(2));
        Assertions.assertEquals(1.5, Charges.credit(3));
    }

    @Test
    void twoPhaseStartsJustAboveTheCredit() {
        for (int tier = 1; tier <= 3; tier++) {
            double k = Charges.credit(tier);
            // c ≤ k is a plain consume (28:711)
            Assertions.assertFalse(Charges.twoPhase(tier, k), "tier " + tier);
            Assertions.assertFalse(Charges.twoPhase(tier, 0), "tier " + tier);
            Assertions.assertFalse(Charges.twoPhase(tier, Math.nextDown(k)), "tier " + tier);
            Assertions.assertTrue(Charges.twoPhase(tier, Math.nextUp(k)), "tier " + tier);
        }
        // plan §3.3: T2 262 144 B (c = 1.0 = k) and T3 393 216 B (c = 1.5 = k) are plain
        Assertions.assertFalse(Charges.twoPhase(2, Charges.bytes(262144)));
        Assertions.assertFalse(Charges.twoPhase(3, Charges.bytes(393216)));
        // 28:716: 512 000 B at T3 is two-phase
        Assertions.assertTrue(Charges.twoPhase(3, Charges.bytes(512000)));
        Assertions.assertTrue(Charges.twoPhase(2, Charges.bytes(1048576)));
        Assertions.assertTrue(Charges.twoPhase(3, Charges.bytes(2097152)));
        // every plain charge of plan §3.3 is ≤ k: readPixels at the readback cap
        Assertions.assertFalse(Charges.twoPhase(2, Charges.bytes(32768)));
        Assertions.assertFalse(Charges.twoPhase(3, Charges.bytes(65536)));
        Assertions.assertFalse(Charges.twoPhase(1, Charges.present(1)));
    }

    @Test
    void thePauseIsTheExcessOverTwiceTheCreditPerCreditOver20() {
        // GraphicsCard.scala:239-243, 28:713: ((c − k) − k)/k/20 when c − k > k
        Assertions.assertEquals(0.1, Charges.pauseSeconds(2, 4.0));
        Assertions.assertEquals(1.0 / 6, Charges.pauseSeconds(3, 8.0), 1e-12);
        Assertions.assertEquals(0.166666667, Charges.pauseSeconds(3, 8.0), 1e-9);
        // 28:716: c = 1.953 125 at T3 is a one-tick stall without a pause
        Assertions.assertEquals(0.0, Charges.pauseSeconds(3, 1.953125));
        // c − k = k exactly is no pause; just above it is a positive one
        Assertions.assertEquals(0.0, Charges.pauseSeconds(2, 2.0));
        Assertions.assertTrue(Charges.pauseSeconds(2, Math.nextUp(2.0)) > 0);
        Assertions.assertEquals(0.0, Charges.pauseSeconds(3, 3.0));
        Assertions.assertEquals(0.0, Charges.pauseSeconds(1, 1.0));
        Assertions.assertEquals(0.05, Charges.pauseSeconds(1, 1.5));
        // at or below the credit there is nothing to pause for
        Assertions.assertEquals(0.0, Charges.pauseSeconds(2, 1.0));
        Assertions.assertEquals(0.0, Charges.pauseSeconds(2, 0.0));
        // T2's 3.812 5 (a 1 024×976 create) pauses 0.090 625 s
        Assertions.assertEquals(0.090625, Charges.pauseSeconds(2, Charges.bytes(1024 * 976)));
    }

    @ParameterizedTest(name = "tier {0}")
    @ValueSource(ints = { Integer.MIN_VALUE, -1, 0, 4, 5, Integer.MAX_VALUE })
    void everyTierFunctionRejectsATierOutside1To3(int tier) {
        Assertions.assertThrows(IllegalArgumentException.class, () -> Charges.submit(tier, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Charges.present(tier));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Charges.credit(tier));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Charges.twoPhase(tier, 1.0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Charges.pauseSeconds(tier, 8.0));
    }
}
