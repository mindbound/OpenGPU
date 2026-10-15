package io.github.mindbound.opengpu.core.render;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** The device key (session, deviceId) of 28:173 and 21:63: any values, equality on both fields, "DeviceKey[s:d]". */
class DeviceKeyTest {

    @Test
    void equalityIsOnBothFields() {
        DeviceKey key = new DeviceKey(1, 2);
        Assertions.assertEquals(1L, key.session);
        Assertions.assertEquals(2, key.deviceId);
        Assertions.assertEquals(key, key);
        Assertions.assertEquals(new DeviceKey(1, 2), key);
        Assertions.assertEquals(new DeviceKey(1, 2).hashCode(), key.hashCode());
        Assertions.assertNotEquals(new DeviceKey(1, 3), key);
        Assertions.assertNotEquals(new DeviceKey(2, 2), key);
        // the fields are not interchangeable
        Assertions.assertNotEquals(new DeviceKey(2, 1), key);
        Assertions.assertNotEquals(key, null);
        Assertions.assertNotEquals(key, "DeviceKey[1:2]");
    }

    @Test
    void anyValuesAreAKey() {
        DeviceKey[] keys = { new DeviceKey(0, 0), new DeviceKey(-1, -1),
            new DeviceKey(Long.MAX_VALUE, Integer.MAX_VALUE), new DeviceKey(Long.MIN_VALUE, Integer.MIN_VALUE),
            new DeviceKey(1L << 32, 0), new DeviceKey(0, 1) };
        Set<DeviceKey> set = new HashSet<>();
        for (DeviceKey k : keys) {
            Assertions.assertTrue(set.add(k), k.toString());
            Assertions.assertEquals(new DeviceKey(k.session, k.deviceId), k);
        }
        Assertions.assertEquals(keys.length, set.size());
        Assertions.assertTrue(set.contains(new DeviceKey(1L << 32, 0)));
    }

    @Test
    void toStringNamesSessionAndDevice() {
        Assertions.assertEquals("DeviceKey[1:2]", new DeviceKey(1, 2).toString());
        Assertions.assertEquals("DeviceKey[-1:0]", new DeviceKey(-1, 0).toString());
        Assertions.assertEquals(
            "DeviceKey[9223372036854775807:-2147483648]",
            new DeviceKey(Long.MAX_VALUE, Integer.MIN_VALUE).toString());
    }
}
