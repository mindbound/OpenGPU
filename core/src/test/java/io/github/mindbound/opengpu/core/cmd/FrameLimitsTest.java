package io.github.mindbound.opengpu.core.cmd;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** The admission caps of 28 §8.1 that the validator checks (28 §3.6 rows 1, 2, 22 and 23). */
class FrameLimitsTest {

    @Test
    void theTierCapsAre28sTable() {
        // 28 §8.1: maxSubmitBytes, maxFrameBytes, maxFrameRecords, maxFrameFill
        assertLimits(FrameLimits.forTier(2), 65536, 131072, 4096, 1024000);
        assertLimits(FrameLimits.forTier(3), 65536, 262144, 4096, 4096000);
        Assertions.assertEquals(65536, FrameLimits.SUBMIT_BYTES);
    }

    @Test
    void m0BuildsTiers2And3Only() {
        // T1 joins in M1 (28 §1.1)
        for (int tier : new int[] { -1, 0, 1, 4, 5 }) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> FrameLimits.forTier(tier), "tier " + tier);
        }
    }

    @Test
    void theConstructorTakesAFillCapOf0AndNoOtherCapBelow1() {
        // A fill cap of 0 admits only empty ops (S6 plan amendment C, finding 8)
        assertLimits(new FrameLimits(1, 1, 1, 0), 1, 1, 1, 0);
        Assertions.assertThrows(IllegalArgumentException.class, () -> new FrameLimits(0, 1, 1, 1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new FrameLimits(1, 0, 1, 1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new FrameLimits(1, 1, 0, 1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new FrameLimits(1, 1, 1, -1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new FrameLimits(-5, 1, 1, 1));
        assertLimits(
            new FrameLimits(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE),
            Integer.MAX_VALUE,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE,
            Long.MAX_VALUE);
    }

    @Test
    void equalityIsByValue() {
        FrameLimits a = new FrameLimits(65536, 131072, 4096, 1024000);
        Assertions.assertEquals(FrameLimits.forTier(2), a);
        Assertions.assertEquals(
            FrameLimits.forTier(2)
                .hashCode(),
            a.hashCode());
        Assertions.assertNotEquals(FrameLimits.forTier(3), a);
        Assertions.assertNotEquals(new FrameLimits(65536, 131072, 4096, 1024001), a);
        Assertions.assertNotEquals(new FrameLimits(65536, 131072, 4095, 1024000), a);
        Assertions.assertNotEquals(new FrameLimits(65536, 131073, 4096, 1024000), a);
        Assertions.assertNotEquals(new FrameLimits(65535, 131072, 4096, 1024000), a);
        Assertions.assertNotEquals(a, null);
        Assertions.assertEquals("FrameLimits[submit 65536 B, frame 131072 B, 4096 records, 1024000 px]", a.toString());
    }

    private static void assertLimits(FrameLimits limits, int submit, int frame, int records, long fill) {
        Assertions.assertEquals(submit, limits.submitBytes);
        Assertions.assertEquals(frame, limits.frameBytes);
        Assertions.assertEquals(records, limits.frameRecords);
        Assertions.assertEquals(fill, limits.frameFill);
    }
}
