package io.github.mindbound.opengpu.core.render;

import java.util.Locale;
import java.util.zip.CRC32;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.cmd.HandleTypes;

/**
 * A record of 28 §2.3: what a device appends and offers to its host. Records are immutable values holding only ids,
 * numbers and byte arrays (21:130); the arrays they hold are never written by anyone after construction (C14; 24:179),
 * and they are not copied except where a constructor says so. Their byte form is M1's side-file spec (28:159; RM:82).
 * M0 has kinds 1-4 and 9. The name follows 28:188; {@code --release 8} has no {@code java.lang.Record}, and every file
 * outside this package imports this one by its single-type name.
 */
public abstract class Record {

    /** Kinds, a namespace separate from opcodes (28:159, :161-171). */
    public static final int FRAME = 1, TEX_CREATE = 2, TEX_UPDATE = 3, TEX_FREE = 4, RESET = 9;
    /** Reserved for later milestones; no class in M0 (28:166-169). */
    public static final int PALETTE = 5, DITHER = 6, INIT = 7, UPLOAD = 8;
    /** 64 B per resource record in the queued-bytes bound (28:140). */
    public static final int RESOURCE_OVERHEAD = 64;

    /** The texture side cap of 28 §6.2, which bounds the texture records' fields. */
    static final int MAX_TEXTURE_SIDE = 1024;

    private static final boolean DEV_CHECKS = Record.class.desiredAssertionStatus();

    /** The device's epoch when the record was appended (28:173), at least 0. */
    public final long epoch;

    /** @throws IllegalArgumentException for a negative epoch */
    Record(long epoch) {
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch " + epoch + " is negative");
        }
        this.epoch = epoch;
    }

    /** {@link #FRAME}, {@link #TEX_CREATE}, {@link #TEX_UPDATE}, {@link #TEX_FREE} or {@link #RESET}. */
    public abstract int kind();

    /** Calls the visitor's method for this record's kind. */
    public abstract void accept(RecordVisitor visitor);

    /** Whether it is a {@code TEX_CREATE}, {@code TEX_UPDATE} or {@code TEX_FREE}: it folds into the masters (O6). */
    public final boolean isResource() {
        int kind = kind();
        return kind == TEX_CREATE || kind == TEX_UPDATE || kind == TEX_FREE;
    }

    /**
     * Its bytes in the server tail's queued-bytes bound (28:140): 64 + w·h·bpp for {@code TEX_CREATE}, 64 + the
     * payload's length for {@code TEX_UPDATE}, 64 for {@code TEX_FREE}, 0 for the others.
     */
    public abstract long queuedBytes();

    /**
     * In dev builds (Java assertions on for this package, 28:202): whether every array still has the CRC32 taken at
     * construction. Always true when assertions are off, when nothing was taken.
     */
    public abstract boolean intact();

    /** Whether the dev-build checks run: {@code Record.class.desiredAssertionStatus()}, read once. */
    public static boolean devChecks() {
        return DEV_CHECKS;
    }

    /** The CRC32 of the whole array. */
    static long crc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length);
        return crc.getValue();
    }

    /** A well-formed type-1 handle, else IllegalArgumentException. */
    static void checkTexture(int handle) {
        if (!Handles.isWellFormed(handle) || Handles.type(handle) != HandleTypes.TEXTURE) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, "0x%08X is no texture handle", handle));
        }
    }
}
