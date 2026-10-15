package io.github.mindbound.opengpu.core.cmd;

import java.util.Locale;

import io.github.mindbound.opengpu.core.Handles;

/**
 * The texture-handle checks of 28 §3.6 rows 15-18, in that order, shared by {@code BLIT}'s validation and S7's
 * {@code updateTexture} and {@code free}, whose handle messages are the same (28 §3.6, callback results).
 */
public final class TextureHandles {

    private TextureHandles() {}

    /**
     * Null if the handle names a live texture of the view; otherwise the first of {@code BAD_HANDLE} (0, at least 2^31
     * or type 0), {@code UNKNOWN_TYPE} (type 8-15), {@code WRONG_TYPE} (type 2-7) and {@code STALE_HANDLE} (type 1 but
     * not live) that applies. Allocates nothing. The surface-format check of row 19 is the caller's.
     */
    public static ErrorCode check(int handle, LiveView view) {
        if (!Handles.isWellFormed(handle)) {
            return ErrorCode.BAD_HANDLE;
        }
        int type = Handles.type(handle);
        if (!HandleTypes.isAssigned(type)) {
            return ErrorCode.UNKNOWN_TYPE;
        }
        if (type != HandleTypes.TEXTURE) {
            return ErrorCode.WRONG_TYPE;
        }
        return view.textureFormat(handle) == 0 ? ErrorCode.STALE_HANDLE : null;
    }

    /**
     * The reason of one of {@link #check}'s codes (28 §3.6): "invalid handle 0x%08X", "handle 0x%08X has an unknown
     * type", "handle 0x%08X is a &lt;kind&gt;, expected a texture" with the kind of 28 §6.1, and "handle 0x%08X is not
     * live".
     *
     * @throws IllegalArgumentException for any other code, or {@code WRONG_TYPE} with a handle whose type is not 2-7
     */
    public static String reason(ErrorCode code, int handle) {
        switch (code) {
            case BAD_HANDLE:
                return String.format(Locale.ROOT, "invalid handle 0x%08X", handle);
            case UNKNOWN_TYPE:
                return String.format(Locale.ROOT, "handle 0x%08X has an unknown type", handle);
            case WRONG_TYPE: {
                int type = Handles.type(handle);
                if (type == HandleTypes.TEXTURE || HandleTypes.kindName(type) == null) {
                    throw new IllegalArgumentException(
                        String.format(Locale.ROOT, "handle 0x%08X is not of a wrong type", handle));
                }
                return String.format(
                    Locale.ROOT,
                    "handle 0x%08X is a %s, expected a texture",
                    handle,
                    HandleTypes.kindName(type));
            }
            case STALE_HANDLE:
                return String.format(Locale.ROOT, "handle 0x%08X is not live", handle);
            default:
                throw new IllegalArgumentException(code + " is not a handle code");
        }
    }
}
