package io.github.mindbound.opengpu.core.device;

import java.util.Arrays;
import java.util.Locale;

import io.github.mindbound.opengpu.core.cmd.StreamError;

/**
 * A callback's result (28 §2.1, §3.6), which the {@code @Callback} layer returns as {@link #toLua()}: Lua true, a
 * handle, a frame id, pixels with their frame id, or {@code nil, msg}. Immutable; a pixels result's data is the array
 * the device made, not copied.
 */
public final class CallResult {

    private enum Kind {
        TRUE,
        HANDLE,
        FRAME_ID,
        PIXELS,
        ERROR
    }

    /** Lua true. */
    public static final CallResult TRUE = new CallResult(Kind.TRUE, 0, 0, null, null, null);

    private final Kind kind;
    private final int handle;
    private final long frameId;
    private final byte[] data;
    private final String message;
    private final StreamError streamError;

    private CallResult(Kind kind, int handle, long frameId, byte[] data, String message, StreamError streamError) {
        this.kind = kind;
        this.handle = handle;
        this.frameId = frameId;
        this.data = data;
        this.message = message;
        this.streamError = streamError;
    }

    /** A handle, a Lua integer. */
    public static CallResult handle(int handle) {
        return new CallResult(Kind.HANDLE, handle, 0, null, null, null);
    }

    /** A frame id, a Lua number from a Long (28:108). */
    public static CallResult frameId(long frameId) {
        return new CallResult(Kind.FRAME_ID, 0, frameId, null, null, null);
    }

    /**
     * {@code data, frameId} (28:99); data is not copied.
     *
     * @throws NullPointerException for null data
     */
    public static CallResult pixels(byte[] data, long frameId) {
        if (data == null) {
            throw new NullPointerException("data");
        }
        return new CallResult(Kind.PIXELS, 0, frameId, data, null, null);
    }

    /**
     * {@code nil, message}.
     *
     * @throws NullPointerException for a null message
     */
    public static CallResult error(String message) {
        if (message == null) {
            throw new NullPointerException("message");
        }
        return new CallResult(Kind.ERROR, 0, 0, null, message, null);
    }

    /**
     * {@code nil, error.message()} for a rejected submit, keeping the structured error.
     *
     * @throws NullPointerException for a null error
     */
    public static CallResult rejected(StreamError error) {
        if (error == null) {
            throw new NullPointerException("error");
        }
        return new CallResult(Kind.ERROR, 0, 0, null, error.message(), error);
    }

    /** Whether it is anything but {@code nil, msg}. */
    public boolean isOk() {
        return kind != Kind.ERROR;
    }

    /** The message of {@code nil, msg}, or null when ok. */
    public String message() {
        return message;
    }

    /** The submit's structured error, or null unless {@link #rejected}. */
    public StreamError streamError() {
        return streamError;
    }

    /** @throws IllegalStateException unless a {@link #handle(int)} result */
    public int handle() {
        if (kind != Kind.HANDLE) {
            throw new IllegalStateException(this + " is no handle");
        }
        return handle;
    }

    /** @throws IllegalStateException unless a {@link #frameId(long)} or {@link #pixels} result */
    public long frameId() {
        if (kind != Kind.FRAME_ID && kind != Kind.PIXELS) {
            throw new IllegalStateException(this + " has no frame id");
        }
        return frameId;
    }

    /** The pixels result's array itself. @throws IllegalStateException unless a {@link #pixels} result */
    public byte[] data() {
        if (kind != Kind.PIXELS) {
            throw new IllegalStateException(this + " has no data");
        }
        return data;
    }

    /**
     * A fresh array in OC's callback shape: {@code {Boolean.TRUE}}, {@code {Integer}}, {@code {Long}},
     * {@code {byte[], Long}} or {@code {null, String}}.
     */
    public Object[] toLua() {
        switch (kind) {
            case TRUE:
                return new Object[] { Boolean.TRUE };
            case HANDLE:
                return new Object[] { Integer.valueOf(handle) };
            case FRAME_ID:
                return new Object[] { Long.valueOf(frameId) };
            case PIXELS:
                return new Object[] { data, Long.valueOf(frameId) };
            default:
                return new Object[] { null, message };
        }
    }

    /**
     * Equal kind and values: data compared by content, and the stream error by {@link StreamError#equals}, so a
     * {@link #rejected} result never equals an {@link #error} with the same message (their {@link #toLua()} do).
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CallResult)) {
            return false;
        }
        CallResult that = (CallResult) other;
        return kind == that.kind && handle == that.handle
            && frameId == that.frameId
            && Arrays.equals(data, that.data)
            && (message == null ? that.message == null : message.equals(that.message))
            && (streamError == null ? that.streamError == null : streamError.equals(that.streamError));
    }

    @Override
    public int hashCode() {
        int hash = kind.hashCode();
        hash = hash * 31 + handle;
        hash = hash * 31 + Long.hashCode(frameId);
        hash = hash * 31 + Arrays.hashCode(data);
        return hash * 31 + (message == null ? 0 : message.hashCode());
    }

    /** "true", "0x08000000", "frame 3", "256 B of frame 0" or "nil, \"busy\"". */
    @Override
    public String toString() {
        switch (kind) {
            case TRUE:
                return "true";
            case HANDLE:
                return String.format(Locale.ROOT, "0x%08X", handle);
            case FRAME_ID:
                return "frame " + frameId;
            case PIXELS:
                return data.length + " B of frame " + frameId;
            default:
                return "nil, \"" + message + "\"";
        }
    }
}
