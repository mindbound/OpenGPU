package io.github.mindbound.opengpu.core;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class HandlesTest {

    @Test
    void fieldsRoundTrip() {
        int handle = Handles.pack(5, 1234, 54321);
        Assertions.assertEquals(5, Handles.type(handle));
        Assertions.assertEquals(1234, Handles.slot(handle));
        Assertions.assertEquals(54321, Handles.generation(handle));
    }

    @Test
    void theLargestHandleIsBelow2To31() {
        // OC marshals handles as Integer: all 31 bits set is Integer.MAX_VALUE, never a negative number
        Assertions
            .assertEquals(Integer.MAX_VALUE, Handles.pack(Handles.MAX_TYPE, Handles.MAX_SLOT, Handles.MAX_GENERATION));
    }

    @Test
    void theSameSlotOfTwoTypesIsTwoHandles() {
        // The aliasing G1 removes: the first texture and the first buffer were the same integer
        Assertions.assertNotEquals(Handles.pack(1, 0, 0), Handles.pack(2, 0, 0));
    }

    @Test
    void typeZeroIsNeverAHandle() {
        Assertions.assertFalse(Handles.isWellFormed(Handles.NONE));
        // type 0 with every other bit set
        Assertions.assertFalse(Handles.isWellFormed((1 << (Handles.SLOT_BITS + Handles.GENERATION_BITS)) - 1));
        Assertions.assertFalse(Handles.isWellFormed(-1));
        Assertions.assertTrue(Handles.isWellFormed(Handles.pack(1, 0, 0)));
    }

    @Test
    void outOfRangeFieldsAreRejected() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> Handles.pack(0, 0, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Handles.pack(Handles.MAX_TYPE + 1, 0, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Handles.pack(1, Handles.MAX_SLOT + 1, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Handles.pack(1, 0, Handles.MAX_GENERATION + 1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Handles.pack(1, -1, 0));
        Assertions.assertThrows(IllegalArgumentException.class, () -> Handles.pack(1, 0, -1));
    }
}
