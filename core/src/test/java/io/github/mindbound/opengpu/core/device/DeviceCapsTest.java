package io.github.mindbound.opengpu.core.device;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;

/**
 * The device caps of 28 §8.1 (28:675-690): both M0 tiers' values, {@code getCaps()}'s 15 {@code :core} keys in their
 * order with their values and Java types (H6: the root adds the other four of 28:692), a fresh table per call, the
 * test constructor's validation (H20) and M0's two tiers only.
 */
class DeviceCapsTest {

    /** getCaps()'s :core keys, in 28:675-690's order. */
    static final List<String> KEYS = Arrays.asList(
        "tier",
        "width",
        "height",
        "format",
        "commandFormat",
        "maxSubmitBytes",
        "maxFrameBytes",
        "maxFrameRecords",
        "maxFrameFill",
        "maxTextBytes",
        "maxUnconfirmedFrames",
        "maxTextureSize",
        "maxTextures",
        "vramBytes",
        "maxReadbackBytes");

    private static final SurfaceDesc SMALL = new SurfaceDesc(2, 24, 16, Formats.INDEX8);
    private static final FrameLimits SMALL_LIMITS = new FrameLimits(65536, 512, 24, 4000);

    @Test
    void tier2() {
        DeviceCaps caps = DeviceCaps.forTier(2);
        Assertions.assertEquals(SurfaceDesc.forTier(2), caps.surface);
        Assertions.assertEquals(new SurfaceDesc(2, 320, 200, Formats.INDEX8), caps.surface);
        Assertions.assertEquals(FrameLimits.forTier(2), caps.limits);
        Assertions.assertEquals(2, caps.tier);
        Assertions.assertEquals(1048576, caps.vramBytes);
        Assertions.assertEquals(256, caps.maxTextures);
        Assertions.assertEquals(1024, caps.maxTextureSize);
        Assertions.assertEquals(32768, caps.maxReadbackBytes);
        Assertions.assertEquals(2, caps.maxUnconfirmedFrames);
        Assertions.assertEquals(caps, DeviceCaps.forTier(2));
    }

    @Test
    void tier3() {
        DeviceCaps caps = DeviceCaps.forTier(3);
        Assertions.assertEquals(new SurfaceDesc(3, 640, 400, Formats.RGB565), caps.surface);
        Assertions.assertEquals(FrameLimits.forTier(3), caps.limits);
        Assertions.assertEquals(3, caps.tier);
        Assertions.assertEquals(4194304, caps.vramBytes);
        Assertions.assertEquals(256, caps.maxTextures);
        Assertions.assertEquals(1024, caps.maxTextureSize);
        Assertions.assertEquals(65536, caps.maxReadbackBytes);
        Assertions.assertEquals(2, caps.maxUnconfirmedFrames);
        Assertions.assertNotEquals(DeviceCaps.forTier(2), caps);
    }

    @Test
    void m0BuildsTiers2And3Only() {
        // T1 is M1 (28:675); 0 and 4 are no tier
        for (int tier : new int[] { -1, 0, 1, 4 }) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> DeviceCaps.forTier(tier), "tier " + tier);
        }
    }

    @Test
    void theLuaTableHasThe15CoreKeysInOrder() {
        assertTable(
            DeviceCaps.forTier(2),
            2,
            320,
            200,
            "index8",
            1,
            65536,
            131072,
            4096,
            1024000,
            1024,
            2,
            1024,
            256,
            1048576,
            32768);
        assertTable(
            DeviceCaps.forTier(3),
            3,
            640,
            400,
            "rgb565",
            1,
            65536,
            262144,
            4096,
            4096000,
            1024,
            2,
            1024,
            256,
            4194304,
            65536);
        // none of the root's four host keys (H6; 24:125)
        Map<String, Object> t = DeviceCaps.forTier(2)
            .luaTable();
        for (String hostKey : new String[] { "renderer", "readback", "graphics", "hostRenderer" }) {
            Assertions.assertFalse(t.containsKey(hostKey), hostKey);
        }
    }

    @Test
    void eachCallReturnsAFreshTable() {
        DeviceCaps caps = DeviceCaps.forTier(2);
        Map<String, Object> first = caps.luaTable();
        Map<String, Object> second = caps.luaTable();
        Assertions.assertNotSame(first, second);
        Assertions.assertEquals(first, second);
        // the root adds its four keys to the map it gets (H6), which must not leak into the next call
        first.put("renderer", "gl");
        first.remove("tier");
        Assertions.assertEquals(
            KEYS,
            new ArrayList<>(
                caps.luaTable()
                    .keySet()));
        Assertions.assertEquals(second, caps.luaTable());
    }

    @Test
    void maxFrameFillIsAnIntegerWhenItFitsElseALong() {
        DeviceCaps fits = new DeviceCaps(
            SMALL,
            new FrameLimits(65536, 512, 24, Integer.MAX_VALUE),
            2048,
            6,
            32,
            256,
            2);
        Assertions.assertEquals(
            Integer.valueOf(Integer.MAX_VALUE),
            fits.luaTable()
                .get("maxFrameFill"));
        DeviceCaps big = new DeviceCaps(SMALL, new FrameLimits(65536, 512, 24, 1L << 33), 2048, 6, 32, 256, 2);
        Object fill = big.luaTable()
            .get("maxFrameFill");
        Assertions.assertEquals(Long.class, fill.getClass());
        Assertions.assertEquals(Long.valueOf(1L << 33), fill);
    }

    @Test
    void theTestConstructorTakesSmallCapsOfAnyTierFrom1To3() {
        DeviceCaps caps = new DeviceCaps(SMALL, SMALL_LIMITS, 2048, 6, 32, 256, 2);
        Assertions.assertSame(SMALL, caps.surface);
        Assertions.assertSame(SMALL_LIMITS, caps.limits);
        Assertions.assertEquals(2, caps.tier);
        Assertions.assertEquals(2048, caps.vramBytes);
        Assertions.assertEquals(6, caps.maxTextures);
        Assertions.assertEquals(32, caps.maxTextureSize);
        Assertions.assertEquals(256, caps.maxReadbackBytes);
        Assertions.assertEquals(2, caps.maxUnconfirmedFrames);
        assertTable(caps, 2, 24, 16, "index8", 1, 65536, 512, 24, 4000, 1024, 2, 32, 6, 2048, 256);
        // the tier indexes the charges (28:698): T1 is allowed here for the formulas (H20)
        Assertions.assertEquals(
            1,
            new DeviceCaps(new SurfaceDesc(1, 16, 12, Formats.INDEX8), SMALL_LIMITS, 1, 1, 1, 1, 1).tier);
        Assertions.assertEquals(
            3,
            new DeviceCaps(new SurfaceDesc(3, 16, 12, Formats.RGB565), SMALL_LIMITS, 4096, 6, 32, 512, 2).tier);
        // the upper edges are allowed
        new DeviceCaps(SMALL, SMALL_LIMITS, Integer.MAX_VALUE, 2048, 1024, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Test
    void theTestConstructorRejectsWhatNoDeviceCouldHave() {
        Assertions.assertThrows(NullPointerException.class, () -> new DeviceCaps(null, SMALL_LIMITS, 1, 1, 1, 1, 1));
        Assertions.assertThrows(NullPointerException.class, () -> new DeviceCaps(SMALL, null, 1, 1, 1, 1, 1));
        // SurfaceDesc allows tier 0, but the charges index the tier (H20)
        SurfaceDesc tier0 = new SurfaceDesc(0, 24, 16, Formats.INDEX8);
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new DeviceCaps(tier0, SMALL_LIMITS, 2048, 6, 32, 256, 2));
        int[][] bad = { { 0, 6, 32, 256, 2 }, { -1, 6, 32, 256, 2 }, { 2048, 0, 32, 256, 2 },
            { 2048, 2049, 32, 256, 2 }, { 2048, 6, 0, 256, 2 }, { 2048, 6, 1025, 256, 2 }, { 2048, 6, 32, 0, 2 },
            { 2048, 6, 32, 256, 0 } };
        for (int[] b : bad) {
            Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new DeviceCaps(SMALL, SMALL_LIMITS, b[0], b[1], b[2], b[3], b[4]),
                Arrays.toString(b));
        }
    }

    @Test
    void equalityCoversEveryField() {
        DeviceCaps caps = new DeviceCaps(SMALL, SMALL_LIMITS, 2048, 6, 32, 256, 2);
        DeviceCaps same = new DeviceCaps(new SurfaceDesc(2, 24, 16, Formats.INDEX8), SMALL_LIMITS, 2048, 6, 32, 256, 2);
        Assertions.assertEquals(caps, same);
        Assertions.assertEquals(caps.hashCode(), same.hashCode());
        Assertions.assertEquals(caps.toString(), same.toString());
        Assertions.assertNotEquals(caps, null);
        Assertions.assertNotEquals(caps, "caps");
        DeviceCaps[] others = {
            new DeviceCaps(new SurfaceDesc(2, 24, 17, Formats.INDEX8), SMALL_LIMITS, 2048, 6, 32, 256, 2),
            new DeviceCaps(SMALL, new FrameLimits(65536, 513, 24, 4000), 2048, 6, 32, 256, 2),
            new DeviceCaps(SMALL, SMALL_LIMITS, 2047, 6, 32, 256, 2),
            new DeviceCaps(SMALL, SMALL_LIMITS, 2048, 7, 32, 256, 2),
            new DeviceCaps(SMALL, SMALL_LIMITS, 2048, 6, 31, 256, 2),
            new DeviceCaps(SMALL, SMALL_LIMITS, 2048, 6, 32, 255, 2),
            new DeviceCaps(SMALL, SMALL_LIMITS, 2048, 6, 32, 256, 3) };
        for (DeviceCaps other : others) {
            Assertions.assertNotEquals(caps, other, other.toString());
        }
        Assertions.assertTrue(
            caps.toString()
                .contains("2048"),
            caps.toString());
    }

    /** The table's keys in order and its values with their types: format a String, everything else an Integer. */
    static void assertTable(DeviceCaps caps, Object... values) {
        Map<String, Object> t = caps.luaTable();
        Assertions.assertEquals(KEYS, new ArrayList<>(t.keySet()));
        Assertions.assertEquals(KEYS.size(), values.length);
        for (int i = 0; i < values.length; i++) {
            String key = KEYS.get(i);
            Object expected = values[i];
            Object actual = t.get(key);
            Assertions.assertEquals(expected, actual, key);
            Assertions.assertEquals(expected.getClass(), actual.getClass(), key);
        }
    }
}
