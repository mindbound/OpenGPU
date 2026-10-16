package io.github.mindbound.opengpu.gl.host;

import java.nio.ByteBuffer;
import java.util.Locale;

import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;

/**
 * The host's copy of one texture (S8 plan §3.10; Q-S8-1's option A with amendment A's copy-on-write), w·h·bpp bytes in
 * the 28 §5.7 layout:
 * <ul>
 * <li>{@code data == null} means all zeros, so a {@code TEX_CREATE} allocates nothing (28:550);</li>
 * <li>a {@code TEX_UPDATE} that covers the whole texture adopts its payload array by reference and marks it shared. A
 * record's payload is exactly the region's bytes (28:561-562) and is never written after the call (C14; 21 §3.1
 * rule 1), which {@code Record.intact()} checks in dev builds at the offer, when a queued record is applied, and, for
 * an adopted payload, whose record the mirror keeps while it is shared, before it is cloned or handed out for an
 * upload (28:202; 21:128);</li>
 * <li>a partial update first makes the array its own: zeros allocated when null, a clone when shared; then it copies
 * the payload's rows in (28:497). The offering thread therefore copies at most one texture plus one payload.</li>
 * </ul>
 * A null or shared array never changes again, so the render thread copies from it outside the device's lock; an owned
 * one it copies under the lock (amendment D). Guarded by its {@link HostDevice}'s lock.
 */
final class Mirror {

    /** What {@link #update} did, for {@link HostDevice}'s counters. */
    static final int ADOPTED = 0, ALLOCATED = 1, CLONED = 2, COPIED = 3;

    private static final byte[] ZEROS = new byte[8192];

    final int handle;
    final int width;
    final int height;
    final int format;
    final int bpp;

    private byte[] data;
    private boolean shared;
    /** The record whose payload {@code data} is while shared, for {@link #intact()}; null otherwise. */
    private TexUpdateRecord adopted;

    Mirror(int handle, int width, int height, int format) {
        this.handle = handle;
        this.width = width;
        this.height = height;
        this.format = format;
        this.bpp = Formats.bytesPerPixel(format);
    }

    /** w·h·bpp, whether or not an array backs it. */
    int byteSize() {
        return width * height * bpp;
    }

    /** Whether the region (x, y, w, h) lies inside and the payload has its w·h·bpp bytes (28 §6.3). */
    boolean fits(TexUpdateRecord u) {
        return u.x <= width - u.width && u.y <= height - u.height && u.payload().length == u.width * u.height * bpp;
    }

    /**
     * Applies the update, which {@link #fits} accepted: adopts a whole-texture payload, else copies the rows into an
     * array of its own.
     *
     * @return {@link #ADOPTED}, {@link #ALLOCATED}, {@link #CLONED} or {@link #COPIED}: what happened to the array
     */
    int update(TexUpdateRecord u) {
        byte[] payload = u.payload();
        if (u.x == 0 && u.y == 0 && u.width == width && u.height == height) {
            data = payload;
            shared = true;
            adopted = u;
            return ADOPTED;
        }
        int result = COPIED;
        if (data == null) {
            data = new byte[byteSize()];
            result = ALLOCATED;
        } else if (shared) {
            // 28:202, A11: the adopted payload is checked again before its bytes are copied
            assert intact() : this + "'s adopted payload changed after it was offered";
            data = data.clone();
            result = CLONED;
        }
        shared = false;
        adopted = null;
        int row = u.width * bpp;
        for (int j = 0; j < u.height; j++) {
            System.arraycopy(payload, j * row, data, ((u.y + j) * width + u.x) * bpp, row);
        }
        return result;
    }

    /** Whether the array is the mirror's own and may still change: copy it under the device's lock. */
    boolean owned() {
        return data != null && !shared;
    }

    /** Whether it adopted a payload it has not cloned since (tests). */
    boolean shared() {
        return shared;
    }

    /**
     * Whether an adopted payload still holds the bytes its record was made with: {@code Record.intact()}, true outside
     * dev builds and for an array of the mirror's own. Called inside {@code assert} only.
     */
    boolean intact() {
        return adopted == null || adopted.intact();
    }

    /** The backing array itself, or null for zeros; never written while shared, so safe outside the lock then. */
    byte[] array() {
        return data;
    }

    /** A fresh copy of the texels (tests). */
    byte[] texels() {
        return data == null ? new byte[byteSize()] : data.clone();
    }

    /**
     * Writes the box (x, y, w, h) of a w·h texture backed by {@code source} (null: zeros) into {@code out} from its
     * position, tightly packed, rows top first (28 §5.7).
     */
    static void copyBox(byte[] source, int textureWidth, int bpp, int x, int y, int w, int h, ByteBuffer out) {
        int row = w * bpp;
        if (source == null) {
            zeros(out, row * h);
            return;
        }
        for (int j = 0; j < h; j++) {
            out.put(source, ((y + j) * textureWidth + x) * bpp, row);
        }
    }

    /** Puts n zero bytes. */
    static void zeros(ByteBuffer out, int n) {
        while (n > 0) {
            int k = Math.min(n, ZEROS.length);
            out.put(ZEROS, 0, k);
            n -= k;
        }
    }

    @Override
    public String toString() {
        return String.format(
            Locale.ROOT,
            "Mirror[0x%08X %dx%d %s%s]",
            handle,
            width,
            height,
            Formats.name(format),
            data == null ? " zeros" : shared ? " shared" : " owned");
    }
}
