package io.github.mindbound.opengpu.core.device;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import io.github.mindbound.opengpu.core.cmd.ErrorCode;
import io.github.mindbound.opengpu.core.cmd.StreamError;

/**
 * A callback's result (28 §2.1's "Returns" column, §3.6): each factory's Lua shape as a fresh {@code Object[]} (Lua
 * true, a handle as an Integer, a frame id as a Long (28:108), {@code data, frameId} (28:99) and {@code nil, msg}),
 * the accessors and their IllegalStateExceptions, and equality with data compared by content.
 */
class CallResultTest {

    private static final StreamError UNKNOWN = new StreamError(
        ErrorCode.UNKNOWN_OPCODE,
        57,
        0x02,
        0x02,
        "unknown opcode");

    @Test
    void trueIsLuaTrue() {
        CallResult r = CallResult.TRUE;
        Assertions.assertTrue(r.isOk());
        Assertions.assertNull(r.message());
        Assertions.assertNull(r.streamError());
        Assertions.assertArrayEquals(new Object[] { Boolean.TRUE }, r.toLua());
        Assertions.assertEquals("true", r.toString());
        assertIllegalState(r::handle);
        assertIllegalState(r::frameId);
        assertIllegalState(r::data);
    }

    @Test
    void aHandleIsALuaInteger() {
        CallResult r = CallResult.handle(0x08000000);
        Assertions.assertTrue(r.isOk());
        Assertions.assertEquals(0x08000000, r.handle());
        Object[] lua = r.toLua();
        Assertions.assertEquals(1, lua.length);
        Assertions.assertEquals(Integer.class, lua[0].getClass());
        Assertions.assertEquals(0x08000000, lua[0]);
        Assertions.assertEquals("0x08000000", r.toString());
        Assertions.assertEquals(
            "0x7FFFFFFF",
            CallResult.handle(0x7FFFFFFF)
                .toString());
        Assertions.assertNull(r.message());
        assertIllegalState(r::frameId);
        assertIllegalState(r::data);
    }

    @Test
    void aFrameIdIsALuaNumberFromALong() {
        CallResult r = CallResult.frameId(3);
        Assertions.assertTrue(r.isOk());
        Assertions.assertEquals(3L, r.frameId());
        Object[] lua = r.toLua();
        Assertions.assertEquals(1, lua.length);
        Assertions.assertEquals(Long.class, lua[0].getClass());
        Assertions.assertEquals(3L, lua[0]);
        Assertions.assertEquals("frame 3", r.toString());
        // ids beyond the int range stay exact (28:108: a Long)
        Assertions.assertEquals(
            Long.valueOf(1L << 40),
            CallResult.frameId(1L << 40)
                .toLua()[0]);
        assertIllegalState(r::handle);
        assertIllegalState(r::data);
    }

    @Test
    void pixelsAreTheDataAndTheirFrameIdUncopied() {
        byte[] data = new byte[256];
        data[7] = 9;
        CallResult r = CallResult.pixels(data, 0);
        Assertions.assertTrue(r.isOk());
        Assertions.assertSame(data, r.data());
        Assertions.assertEquals(0L, r.frameId());
        Object[] lua = r.toLua();
        Assertions.assertEquals(2, lua.length);
        Assertions.assertSame(data, lua[0]);
        Assertions.assertEquals(Long.class, lua[1].getClass());
        Assertions.assertEquals(0L, lua[1]);
        Assertions.assertEquals("256 B of frame 0", r.toString());
        Assertions.assertNull(r.message());
        assertIllegalState(r::handle);
        Assertions.assertThrows(NullPointerException.class, () -> CallResult.pixels(null, 0));
    }

    @Test
    void anErrorIsNilAndTheMessage() {
        CallResult r = CallResult.error("busy");
        Assertions.assertFalse(r.isOk());
        Assertions.assertEquals("busy", r.message());
        Assertions.assertNull(r.streamError());
        Assertions.assertArrayEquals(new Object[] { null, "busy" }, r.toLua());
        Assertions.assertEquals("nil, \"busy\"", r.toString());
        assertIllegalState(r::handle);
        assertIllegalState(r::frameId);
        assertIllegalState(r::data);
        Assertions.assertThrows(NullPointerException.class, () -> CallResult.error(null));
    }

    @Test
    void aRejectedSubmitKeepsTheStreamError() {
        CallResult r = CallResult.rejected(UNKNOWN);
        Assertions.assertFalse(r.isOk());
        Assertions.assertEquals("57: 0x02: unknown opcode", r.message());
        Assertions.assertSame(UNKNOWN, r.streamError());
        Assertions.assertArrayEquals(new Object[] { null, "57: 0x02: unknown opcode" }, r.toLua());
        Assertions.assertEquals("nil, \"57: 0x02: unknown opcode\"", r.toString());
        // the same Lua values as a plain error with the message, but not equal to it: the structure is kept
        CallResult plain = CallResult.error(UNKNOWN.message());
        Assertions.assertArrayEquals(plain.toLua(), r.toLua());
        Assertions.assertNotEquals(plain, r);
        Assertions.assertEquals(CallResult.rejected(UNKNOWN), r);
        Assertions.assertEquals(
            CallResult.rejected(new StreamError(ErrorCode.UNKNOWN_OPCODE, 57, 0x02, 0x02, "unknown opcode")),
            r);
        Assertions.assertThrows(NullPointerException.class, () -> CallResult.rejected(null));
    }

    @Test
    void toLuaIsAFreshArrayEveryCall() {
        CallResult[] results = { CallResult.TRUE, CallResult.handle(1 << 27), CallResult.frameId(1),
            CallResult.pixels(new byte[1], 0), CallResult.error("busy"), CallResult.rejected(UNKNOWN) };
        for (CallResult r : results) {
            Object[] first = r.toLua();
            Assertions.assertNotSame(first, r.toLua(), r.toString());
            Object kept = first[first.length - 1];
            first[first.length - 1] = "changed";
            Assertions.assertEquals(kept, r.toLua()[first.length - 1], r.toString());
        }
        // the shared TRUE never changes
        CallResult.TRUE.toLua()[0] = Boolean.FALSE;
        Assertions.assertArrayEquals(new Object[] { Boolean.TRUE }, CallResult.TRUE.toLua());
    }

    @Test
    void equalityComparesKindsValuesAndDataByContent() {
        CallResult a = CallResult.pixels(new byte[] { 1, 2, 3 }, 5);
        CallResult b = CallResult.pixels(new byte[] { 1, 2, 3 }, 5);
        Assertions.assertEquals(a, b);
        Assertions.assertEquals(a.hashCode(), b.hashCode());
        Assertions.assertNotEquals(a, CallResult.pixels(new byte[] { 1, 2, 4 }, 5));
        Assertions.assertNotEquals(a, CallResult.pixels(new byte[] { 1, 2, 3 }, 6));
        Assertions.assertNotEquals(a, CallResult.pixels(new byte[] { 1, 2 }, 5));
        Assertions.assertEquals(CallResult.handle(7), CallResult.handle(7));
        Assertions.assertEquals(
            CallResult.handle(7)
                .hashCode(),
            CallResult.handle(7)
                .hashCode());
        Assertions.assertNotEquals(CallResult.handle(7), CallResult.handle(8));
        Assertions.assertEquals(CallResult.frameId(7), CallResult.frameId(7));
        Assertions.assertNotEquals(CallResult.frameId(7), CallResult.handle(7));
        Assertions.assertNotEquals(CallResult.frameId(0), CallResult.TRUE);
        Assertions.assertEquals(CallResult.error("busy"), CallResult.error("busy"));
        Assertions.assertEquals(
            CallResult.error("busy")
                .hashCode(),
            CallResult.error("busy")
                .hashCode());
        Assertions.assertNotEquals(CallResult.error("busy"), CallResult.error("no renderer"));
        Assertions.assertNotEquals(CallResult.error("busy"), null);
        Assertions.assertNotEquals(CallResult.TRUE, Boolean.TRUE);
    }

    private static void assertIllegalState(Executable accessor) {
        Assertions.assertThrows(IllegalStateException.class, accessor);
    }
}
