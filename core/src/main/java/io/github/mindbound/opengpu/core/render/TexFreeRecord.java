package io.github.mindbound.opengpu.core.render;

import java.util.Locale;

/** Kind 4, {@code TEX_FREE} (28:166, §6.4): the host deletes the texture with exactly this handle (O7). */
public final class TexFreeRecord extends Record {

    public final int handle;

    /** @throws IllegalArgumentException for a negative epoch or a handle that is not a well-formed type-1 handle */
    public TexFreeRecord(long epoch, int handle) {
        super(epoch);
        checkTexture(handle);
        this.handle = handle;
    }

    @Override
    public int kind() {
        return TEX_FREE;
    }

    @Override
    public void accept(RecordVisitor visitor) {
        visitor.texFree(this);
    }

    @Override
    public long queuedBytes() {
        return RESOURCE_OVERHEAD;
    }

    @Override
    public boolean intact() {
        return true;
    }

    /** For example "TEX_FREE e1 0x08000000". */
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "TEX_FREE e%d 0x%08X", epoch, handle);
    }
}
