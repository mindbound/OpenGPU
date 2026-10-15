package io.github.mindbound.opengpu.core.render;

import java.util.Locale;

/**
 * Kind 3, {@code TEX_UPDATE} (28:165, §6.3): a region of a texture, its payload tightly packed in the 28 §5.7 layout.
 * It carries no format; the texture's decides the payload's bpp.
 */
public final class TexUpdateRecord extends Record {

    public final int handle;
    public final int x;
    public final int y;
    public final int width;
    public final int height;

    private final byte[] payload;
    /** The payload's CRC32 in dev builds. */
    private final long crc;

    /**
     * The payload is the argument array itself (28:562), not copied. Takes its CRC32 in dev builds.
     *
     * @throws NullPointerException     for a null payload
     * @throws IllegalArgumentException for a negative epoch, a handle that is not a well-formed type-1 handle, x or y
     *                                  outside 0-1 024, w or h outside 1-1 024, or a payload whose length is neither
     *                                  w·h nor 2·w·h
     */
    public TexUpdateRecord(long epoch, int handle, int x, int y, int width, int height, byte[] payload) {
        super(epoch);
        if (payload == null) {
            throw new NullPointerException("payload");
        }
        checkTexture(handle);
        if (x < 0 || x > MAX_TEXTURE_SIDE || y < 0 || y > MAX_TEXTURE_SIDE) {
            throw new IllegalArgumentException("origin " + x + "," + y + " is outside 0.." + MAX_TEXTURE_SIDE);
        }
        if (width < 1 || width > MAX_TEXTURE_SIDE || height < 1 || height > MAX_TEXTURE_SIDE) {
            throw new IllegalArgumentException(
                "region " + width + "x" + height + " has a side outside 1.." + MAX_TEXTURE_SIDE);
        }
        long texels = (long) width * height;
        if (payload.length != texels && payload.length != 2 * texels) {
            throw new IllegalArgumentException(
                "payload of " + payload.length + " bytes fits no format of a " + width + "x" + height + " region");
        }
        this.handle = handle;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.payload = payload;
        this.crc = devChecks() ? crc(payload) : 0;
    }

    /** The argument array itself; never write it. */
    public byte[] payload() {
        return payload;
    }

    @Override
    public int kind() {
        return TEX_UPDATE;
    }

    @Override
    public void accept(RecordVisitor visitor) {
        visitor.texUpdate(this);
    }

    @Override
    public long queuedBytes() {
        return RESOURCE_OVERHEAD + (long) payload.length;
    }

    @Override
    public boolean intact() {
        return !devChecks() || crc(payload) == crc;
    }

    /** For example "TEX_UPDATE e1 0x08000000 at 0,0 16x16 (256 B)". */
    @Override
    public String toString() {
        return String.format(
            Locale.ROOT,
            "TEX_UPDATE e%d 0x%08X at %d,%d %dx%d (%d B)",
            epoch,
            handle,
            x,
            y,
            width,
            height,
            payload.length);
    }
}
