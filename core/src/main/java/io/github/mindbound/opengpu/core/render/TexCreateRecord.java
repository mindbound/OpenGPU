package io.github.mindbound.opengpu.core.render;

import java.util.Locale;

import io.github.mindbound.opengpu.core.cmd.Formats;

/** Kind 2, {@code TEX_CREATE} (28:164, §6.2): a new texture, all zeros, in an OpenGPU format code (24:238). */
public final class TexCreateRecord extends Record {

    public final int handle;
    public final int width;
    public final int height;
    /** {@link Formats#INDEX8} or {@link Formats#RGB565}. */
    public final int format;

    /**
     * @throws IllegalArgumentException for a negative epoch, a handle that is not a well-formed type-1 handle, a side
     *                                  outside 1-1 024 or an undefined format (28 §6.1-§6.2)
     */
    public TexCreateRecord(long epoch, int handle, int width, int height, int format) {
        super(epoch);
        checkTexture(handle);
        if (width < 1 || width > MAX_TEXTURE_SIDE || height < 1 || height > MAX_TEXTURE_SIDE) {
            throw new IllegalArgumentException(
                "texture " + width + "x" + height + " has a side outside 1.." + MAX_TEXTURE_SIDE);
        }
        if (!Formats.isDefined(format)) {
            throw new IllegalArgumentException("format " + format + " is undefined");
        }
        this.handle = handle;
        this.width = width;
        this.height = height;
        this.format = format;
    }

    /** w·h·bpp: the texture's bytes, and its VRAM use. */
    public int byteSize() {
        return width * height * Formats.bytesPerPixel(format);
    }

    @Override
    public int kind() {
        return TEX_CREATE;
    }

    @Override
    public void accept(RecordVisitor visitor) {
        visitor.texCreate(this);
    }

    @Override
    public long queuedBytes() {
        return RESOURCE_OVERHEAD + (long) byteSize();
    }

    @Override
    public boolean intact() {
        return true;
    }

    /** For example "TEX_CREATE e1 0x08000000 16x16 index8". */
    @Override
    public String toString() {
        return String
            .format(Locale.ROOT, "TEX_CREATE e%d 0x%08X %dx%d %s", epoch, handle, width, height, Formats.name(format));
    }
}
