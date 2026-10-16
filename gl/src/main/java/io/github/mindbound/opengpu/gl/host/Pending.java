package io.github.mindbound.opengpu.gl.host;

import java.util.Locale;

/**
 * What the GPU lacks of one texture against its mirror (S8 plan §3.10, I-H3), by full handle (O7): a delete of the
 * texture the GPU holds, a create of the whole mirror, or an upload of the dirty box. A handle created again while its
 * delete is still pending, after a generation wrap, has both: delete, then create. Guarded by its
 * {@link HostDevice}'s lock.
 */
final class Pending {

    final int handle;
    /** The GPU holds a texture of this handle that must go first ({@code glDeleteTextures}, 28:604). */
    boolean deleteGpu;
    /** The GPU must create the texture from the whole mirror (28:550, :587). */
    boolean create;
    /** The dirty box, [x0, x1) × [y0, y1); empty when x1 ≤ x0. Only without {@link #create}. */
    int x0, y0, x1, y1;

    Pending(int handle) {
        this.handle = handle;
    }

    boolean dirty() {
        return x1 > x0 && y1 > y0;
    }

    /** Widens the dirty box to cover (x, y, w, h): the union's bounding box, so a second update widens it. */
    void addDirty(int x, int y, int w, int h) {
        if (!dirty()) {
            x0 = x;
            y0 = y;
            x1 = x + w;
            y1 = y + h;
            return;
        }
        x0 = Math.min(x0, x);
        y0 = Math.min(y0, y);
        x1 = Math.max(x1, x + w);
        y1 = Math.max(y1, y + h);
    }

    void clean() {
        x0 = y0 = x1 = y1 = 0;
    }

    /** Whether nothing is owed: an entry in this state is dropped. */
    boolean empty() {
        return !deleteGpu && !create && !dirty();
    }

    @Override
    public String toString() {
        return String.format(
            Locale.ROOT,
            "Pending[0x%08X%s%s%s]",
            handle,
            deleteGpu ? " delete" : "",
            create ? " create" : "",
            dirty() ? String.format(Locale.ROOT, " dirty (%d, %d)-(%d, %d)", x0, y0, x1, y1) : "");
    }
}
