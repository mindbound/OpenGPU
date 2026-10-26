package io.github.mindbound.opengpu.core.fixtures.device;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.mindbound.opengpu.core.device.CallResult;
import io.github.mindbound.opengpu.core.device.Device;

/**
 * The Java model of the callback layer (S11 plan P15.4; amendments F-1), moved from {@code :ocelot-harness}'s
 * {@code CallReplay} (S10 plan P14.4 (e)), so that the harness's replay and the root's parity tests share one model of
 * what a callback does with the arguments OC delivered: each read in the callbacks' order (M0Callbacks), integers
 * through {@link OcArgs}, the model of OC's {@code checkInteger}, strings through {@link #byteArray} and
 * {@link #optString}, then the Device call on a {@link DeviceRig}, the charged ones through {@link DeviceRig#call},
 * OC's direct call with its retry. An argument error is OC's {@code IllegalArgumentException} with OC's message, as
 * the card's and the entity's are (28:84, :291).
 * <p>
 * {@code getCaps} gives Device's 15 keys plus {@link M0Callbacks#RENDERER} by the shared rule; {@code bind} has no
 * model here, being the card's alone (M0Callbacks).
 */
public final class M0Replay {

    private M0Replay() {}

    /**
     * The values Lua gets from the callback {@code name} called with {@code delivered}: {@code {getCaps(rig)}} for
     * {@code getCaps}, else {@code call(rig, name, delivered).toLua()}.
     *
     * @throws IllegalArgumentException with OC's message for an argument error
     * @throws IllegalStateException    for {@code bind} or a name that is no M0 callback
     */
    public static Object[] run(DeviceRig rig, String name, Object[] delivered) {
        if ("getCaps".equals(name)) {
            return new Object[] { getCaps(rig) };
        }
        return call(rig, name, delivered).toLua();
    }

    /**
     * The Device result of one of the six callbacks that return one: {@code submit}, {@code present},
     * {@code readPixels}, {@code createTexture}, {@code updateTexture} and {@code free}, the last uncharged (28:707).
     *
     * @throws IllegalArgumentException with OC's message for an argument error
     * @throws IllegalStateException    for {@code getCaps}, {@code bind} or a name that is no M0 callback
     */
    public static CallResult call(DeviceRig rig, String name, Object[] delivered) {
        Device d = rig.device;
        switch (name) {
            case "submit": {
                byte[] stream = byteArray(delivered, 0);
                return rig.call(b -> d.submit(b, stream));
            }
            case "present":
                return rig.call(d::present);
            case "readPixels": {
                int x = OcArgs.checkInteger(delivered, 0);
                int y = OcArgs.checkInteger(delivered, 1);
                int w = OcArgs.checkInteger(delivered, 2);
                int h = OcArgs.checkInteger(delivered, 3);
                return rig.call(b -> d.readPixels(b, x, y, w, h));
            }
            case "createTexture": {
                int w = OcArgs.checkInteger(delivered, 0);
                int h = OcArgs.checkInteger(delivered, 1);
                String format = optString(delivered, 2);
                return rig.call(b -> d.createTexture(b, w, h, format));
            }
            case "updateTexture": {
                int tex = OcArgs.checkInteger(delivered, 0);
                int x = OcArgs.checkInteger(delivered, 1);
                int y = OcArgs.checkInteger(delivered, 2);
                int w = OcArgs.checkInteger(delivered, 3);
                int h = OcArgs.checkInteger(delivered, 4);
                byte[] data = byteArray(delivered, 5);
                return rig.call(b -> d.updateTexture(b, tex, x, y, w, h, data));
            }
            case "free":
                return d.free(OcArgs.checkInteger(delivered, 0));
            default:
                throw new IllegalStateException(
                    "M0Replay has no model of " + name
                        + ("bind".equals(name) ? ", the card's alone (M0Callbacks)"
                            : "getCaps".equals(name) ? ", which returns a table: use run" : ""));
        }
    }

    /**
     * {@code getCaps()}'s parity part (F-1): Device's 15 keys in its order, then {@link M0Callbacks#RENDERER},
     * {@link M0Callbacks#HOST_GL} while the rig's host is available and {@link M0Callbacks#NONE} otherwise. A fresh
     * map.
     */
    public static Map<String, Object> getCaps(DeviceRig rig) {
        Map<String, Object> caps = new LinkedHashMap<>(rig.device.getCaps());
        caps.put(M0Callbacks.RENDERER, rig.renderHost.available() ? M0Callbacks.HOST_GL : M0Callbacks.NONE);
        return caps;
    }

    /**
     * OC's {@code checkByteArray} (ArgumentsImpl.scala:179-186), which ocelot repeats (Arguments.scala:236-243): a
     * {@code byte[]} itself, a {@code String}'s UTF-8 bytes, else its argument errors, with OC's plural past the end.
     *
     * @throws IllegalArgumentException  with OC's message
     * @throws IndexOutOfBoundsException for a negative index, as OC's {@code checkIndex}
     */
    public static byte[] byteArray(Object[] args, int i) {
        if (i < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (i >= args.length) {
            throw new IllegalArgumentException("bad arguments #" + (i + 1) + " (string expected, got no value)");
        }
        Object v = args[i];
        if (v instanceof byte[]) {
            return (byte[]) v;
        }
        if (v instanceof String) {
            return ((String) v).getBytes(StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException(
            "bad argument #" + (i + 1) + " (string expected, got " + OcArgs.typeName(v) + ")");
    }

    /**
     * OC's {@code optString(i, null)} (ArgumentsImpl.scala:174-177, :165-172), as ocelot's (Arguments.scala:361-364):
     * null when absent or nil, else {@code checkString}, which decodes a {@code byte[]} as UTF-8.
     *
     * @throws IllegalArgumentException with OC's message for a value that is no string
     */
    public static String optString(Object[] args, int i) {
        if (i < 0 || i >= args.length || args[i] == null) {
            return null;
        }
        Object v = args[i];
        if (v instanceof byte[]) {
            return new String((byte[]) v, StandardCharsets.UTF_8);
        }
        if (v instanceof String) {
            return (String) v;
        }
        throw new IllegalArgumentException(
            "bad argument #" + (i + 1) + " (string expected, got " + OcArgs.typeName(v) + ")");
    }
}
