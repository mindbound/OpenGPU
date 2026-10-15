package io.github.mindbound.opengpu.core.device;

import java.util.Locale;

import io.github.mindbound.opengpu.core.cmd.Formats;

/**
 * A copy of one texture master (28:123; 24:179), taken by {@link Device#snapshot()}: w·h·bpp bytes in the 28 §5.7
 * layout. Immutable but for {@link #texels()}, the snapshot's own copy, which callers must not write.
 */
public final class MasterTexture {

    public final int handle;
    public final int width;
    public final int height;
    public final int format;

    private final byte[] texels;

    MasterTexture(int handle, int width, int height, int format, byte[] texels) {
        this.handle = handle;
        this.width = width;
        this.height = height;
        this.format = format;
        this.texels = texels;
    }

    /** The texels, rows top first; the object's own array (a copy made by the snapshot), never written. */
    public byte[] texels() {
        return texels;
    }

    /** For example "MasterTexture[0x08000000 16x16 index8]". */
    @Override
    public String toString() {
        return String
            .format(Locale.ROOT, "MasterTexture[0x%08X %dx%d %s]", handle, width, height, Formats.name(format));
    }
}
