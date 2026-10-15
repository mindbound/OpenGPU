package io.github.mindbound.opengpu.core.render;

import java.util.Locale;

/**
 * Kind 9, {@code RESET} (28:171, §2.2): a computer stop or start. The host deletes every texture of the device and
 * overwrites its surface with the pixels, the confirmed frame K's, keeping the surface object (21:107).
 */
public final class ResetRecord extends Record {

    private final byte[] pixels;
    /** The pixels' CRC32 in dev builds. */
    private final long crc;

    /**
     * The pixels are K's in the 28 §5.7 layout: a copy the caller made (28:150), which is not copied again. Takes its
     * CRC32 in dev builds.
     *
     * @throws NullPointerException     for null pixels
     * @throws IllegalArgumentException for a negative epoch
     */
    public ResetRecord(long epoch, byte[] pixels) {
        super(epoch);
        if (pixels == null) {
            throw new NullPointerException("pixels");
        }
        this.pixels = pixels;
        this.crc = devChecks() ? crc(pixels) : 0;
    }

    /** That array; never write it. */
    public byte[] pixels() {
        return pixels;
    }

    @Override
    public int kind() {
        return RESET;
    }

    @Override
    public void accept(RecordVisitor visitor) {
        visitor.reset(this);
    }

    @Override
    public long queuedBytes() {
        return 0;
    }

    @Override
    public boolean intact() {
        return !devChecks() || crc(pixels) == crc;
    }

    /** For example "RESET e2 (64000 B)". */
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "RESET e%d (%d B)", epoch, pixels.length);
    }
}
