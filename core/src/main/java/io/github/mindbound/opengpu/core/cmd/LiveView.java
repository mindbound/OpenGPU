package io.github.mindbound.opengpu.core.cmd;

import io.github.mindbound.opengpu.core.SurfaceDesc;

/**
 * What validation sees of a device (28 §2.2): its surface, its caps and its live handle table.
 * {@link io.github.mindbound.opengpu.core.device.Device} implements it with an allocation-free lookup by
 * {@link io.github.mindbound.opengpu.core.Handles#slot}. The validator reads {@link #surface()} and {@link #limits()}
 * once per segment. Per {@code BLIT} it calls {@link #textureFormat} at most twice, for rows 18 and 19 (28 §3.6), and
 * {@link #textureWidth} and {@link #textureHeight} once each, only after rows 15-20 pass (the handle, the format and
 * the flags). None of them may allocate or box: validation allocates nothing per record (28 §3.5).
 */
public interface LiveView {

    SurfaceDesc surface();

    FrameLimits limits();

    /**
     * The format code of the live texture with exactly this handle, slot and generation, or 0 if there is none: freed,
     * never created, or another generation (28 §3.6 row 18). Called only with a well-formed handle of type 1.
     */
    int textureFormat(int handle);

    /**
     * The live texture's width: 1 to 1 024 for {@code createTexture}'s textures (28 §6.2), and at most
     * {@link SurfaceDesc#MAX_SIDE}, up to which {@link Clip}'s arithmetic is exact. Called only after
     * {@link #textureFormat} returned non-zero for the handle; {@code BLIT}'s fill needs it (28 §3.5, the i and j
     * ranges of §5.7).
     */
    int textureWidth(int handle);

    /** The live texture's height, as {@link #textureWidth}. */
    int textureHeight(int handle);
}
