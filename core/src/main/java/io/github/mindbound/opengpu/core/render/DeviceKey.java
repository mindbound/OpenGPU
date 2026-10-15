package io.github.mindbound.opengpu.core.render;

/**
 * A device's identity towards a host (28:173; 21:63): the server session and the device's id in it. It is the
 * argument of every {@link RenderHost} call; records do not carry it. Immutable.
 */
public final class DeviceKey {

    public final long session;
    public final int deviceId;

    /** Any values. */
    public DeviceKey(long session, int deviceId) {
        this.session = session;
        this.deviceId = deviceId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DeviceKey)) {
            return false;
        }
        DeviceKey that = (DeviceKey) other;
        return session == that.session && deviceId == that.deviceId;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(session) * 31 + deviceId;
    }

    /** For example "DeviceKey[1:2]". */
    @Override
    public String toString() {
        return "DeviceKey[" + session + ":" + deviceId + "]";
    }
}
