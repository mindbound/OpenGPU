package io.github.mindbound.opengpu.core.device;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.cmd.Header;
import io.github.mindbound.opengpu.core.cmd.Opcodes;

/**
 * A device's caps (28 §8.1): its surface, the admission caps the validator checks, and the caps the device enforces.
 * They are tier caps, not host limits (28 §10.5), and provisional until M0's measurements (Q5). Immutable.
 */
public final class DeviceCaps {

    /** The texture side cap of 28 §6.2 (GL33:14799-14808). */
    private static final int MAX_TEXTURE_SIZE = 1024;

    private static final int MAX_TEXTURES = 256;
    private static final int UNCONFIRMED_FRAMES = 2;
    private static final DeviceCaps T2 = new DeviceCaps(
        SurfaceDesc.forTier(2),
        FrameLimits.forTier(2),
        1048576,
        MAX_TEXTURES,
        MAX_TEXTURE_SIZE,
        32768,
        UNCONFIRMED_FRAMES);
    private static final DeviceCaps T3 = new DeviceCaps(
        SurfaceDesc.forTier(3),
        FrameLimits.forTier(3),
        4194304,
        MAX_TEXTURES,
        MAX_TEXTURE_SIZE,
        65536,
        UNCONFIRMED_FRAMES);

    public final SurfaceDesc surface;
    public final FrameLimits limits;
    /** {@code surface.tier}, 1-3: the charges index it (28:698). */
    public final int tier;
    /** {@code vramBytes}: the live textures' Σ w·h·bpp, and the queued-bytes cap (28:688-689). */
    public final int vramBytes;
    /** {@code maxTextures}: live textures (256). */
    public final int maxTextures;
    /** {@code maxTextureSize}: either side (1 024). */
    public final int maxTextureSize;
    /** {@code maxReadbackBytes}: one {@code readPixels} call's bytes. */
    public final int maxReadbackBytes;
    /** {@code maxUnconfirmedFrames}: {@code "busy"} at the next present (2). */
    public final int maxUnconfirmedFrames;

    /**
     * Any caps, for tests (small surfaces; H20).
     *
     * @throws NullPointerException     for a null surface or null limits
     * @throws IllegalArgumentException unless surface.tier is 1-3, vramBytes >= 1, maxTextures 1-2 048,
     *                                  maxTextureSize 1-1 024, maxReadbackBytes >= 1 and maxUnconfirmedFrames >= 1
     */
    public DeviceCaps(SurfaceDesc surface, FrameLimits limits, int vramBytes, int maxTextures, int maxTextureSize,
        int maxReadbackBytes, int maxUnconfirmedFrames) {
        if (surface == null || limits == null) {
            throw new NullPointerException("surface and limits");
        }
        if (surface.tier < 1 || surface.tier > 3) {
            throw new IllegalArgumentException("surface tier " + surface.tier + " is outside 1..3");
        }
        if (vramBytes < 1 || maxTextures < 1
            || maxTextures > TextureTable.SLOTS
            || maxTextureSize < 1
            || maxTextureSize > MAX_TEXTURE_SIZE
            || maxReadbackBytes < 1
            || maxUnconfirmedFrames < 1) {
            throw new IllegalArgumentException(
                "invalid caps: "
                    + describe(vramBytes, maxTextures, maxTextureSize, maxReadbackBytes, maxUnconfirmedFrames));
        }
        this.surface = surface;
        this.limits = limits;
        this.tier = surface.tier;
        this.vramBytes = vramBytes;
        this.maxTextures = maxTextures;
        this.maxTextureSize = maxTextureSize;
        this.maxReadbackBytes = maxReadbackBytes;
        this.maxUnconfirmedFrames = maxUnconfirmedFrames;
    }

    /**
     * The tier's caps (28 §8.1). T2: 320×200 index8, {@code FrameLimits.forTier(2)}, 1 048 576 B VRAM, 256 textures,
     * 1 024, 32 768 B per readback, 2 frames. T3: 640×400 rgb565, {@code FrameLimits.forTier(3)}, 4 194 304, 256,
     * 1 024, 65 536, 2. M1 adds T1: 160×100 index8, 262 144 B VRAM, 16 384 B per readback.
     *
     * @throws IllegalArgumentException for any tier but 2 and 3
     */
    public static DeviceCaps forTier(int tier) {
        switch (tier) {
            case 2:
                return T2;
            case 3:
                return T3;
            default:
                throw new IllegalArgumentException("M0 builds tiers 2 and 3 only, not " + tier);
        }
    }

    /**
     * {@code getCaps()}'s 15 {@code :core} keys (28:675-690), in this order, in a fresh map: tier, width, height,
     * format ("index8" or "rgb565", a String), commandFormat, maxSubmitBytes, maxFrameBytes, maxFrameRecords,
     * maxFrameFill (an Integer when it fits, else a Long: only test caps exceed an int), maxTextBytes,
     * maxUnconfirmedFrames, maxTextureSize, maxTextures, vramBytes and maxReadbackBytes, every other value an Integer.
     * The root adds renderer, readback, graphics and hostRenderer (24:125; H6).
     */
    public Map<String, Object> luaTable() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("tier", tier);
        t.put("width", surface.width);
        t.put("height", surface.height);
        t.put("format", Formats.name(surface.format));
        t.put("commandFormat", Header.VERSION);
        t.put("maxSubmitBytes", limits.submitBytes);
        t.put("maxFrameBytes", limits.frameBytes);
        t.put("maxFrameRecords", limits.frameRecords);
        long fill = limits.frameFill;
        t.put("maxFrameFill", fill <= Integer.MAX_VALUE ? (Object) Integer.valueOf((int) fill) : Long.valueOf(fill));
        t.put("maxTextBytes", Opcodes.TEXT_MAX_BYTES);
        t.put("maxUnconfirmedFrames", maxUnconfirmedFrames);
        t.put("maxTextureSize", maxTextureSize);
        t.put("maxTextures", maxTextures);
        t.put("vramBytes", vramBytes);
        t.put("maxReadbackBytes", maxReadbackBytes);
        return t;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DeviceCaps)) {
            return false;
        }
        DeviceCaps that = (DeviceCaps) other;
        return surface.equals(that.surface) && limits.equals(that.limits)
            && vramBytes == that.vramBytes
            && maxTextures == that.maxTextures
            && maxTextureSize == that.maxTextureSize
            && maxReadbackBytes == that.maxReadbackBytes
            && maxUnconfirmedFrames == that.maxUnconfirmedFrames;
    }

    @Override
    public int hashCode() {
        int hash = surface.hashCode();
        hash = hash * 31 + limits.hashCode();
        hash = hash * 31 + vramBytes;
        hash = hash * 31 + maxTextures;
        hash = hash * 31 + maxTextureSize;
        hash = hash * 31 + maxReadbackBytes;
        return hash * 31 + maxUnconfirmedFrames;
    }

    @Override
    public String toString() {
        return "DeviceCaps[" + surface
            + ", "
            + limits
            + ", "
            + describe(vramBytes, maxTextures, maxTextureSize, maxReadbackBytes, maxUnconfirmedFrames)
            + "]";
    }

    private static String describe(int vramBytes, int maxTextures, int maxTextureSize, int maxReadbackBytes,
        int maxUnconfirmedFrames) {
        return String.format(
            Locale.ROOT,
            "VRAM %d B, %d textures of at most %d, readback %d B, %d unconfirmed frames",
            vramBytes,
            maxTextures,
            maxTextureSize,
            maxReadbackBytes,
            maxUnconfirmedFrames);
    }
}
