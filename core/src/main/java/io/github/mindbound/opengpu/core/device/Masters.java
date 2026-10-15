package io.github.mindbound.opengpu.core.device;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexFreeRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;

/**
 * The texture masters (28:123; 24:179): every texture's contents as of the records ahead of the server tail, keyed by
 * the full handle, never by slot (O7). They lag the live view exactly as 21:231 requires: they hold the state the
 * first unconfirmed frame starts from. A master is the device's own array; payloads are copied in row by row, never
 * adopted. Not thread-safe: the device's monitor guards it.
 */
final class Masters {

    private static final class Master {

        final int width;
        final int height;
        final int format;
        final byte[] texels;

        Master(int width, int height, int format) {
            this.width = width;
            this.height = height;
            this.format = format;
            this.texels = new byte[width * height * Formats.bytesPerPixel(format)];
        }
    }

    private final TreeMap<Integer, Master> entries = new TreeMap<>();

    /**
     * Folds a resource record (O6), after {@code assert r.intact()}: {@code TEX_CREATE} puts a zeroed master,
     * {@code TEX_UPDATE} copies its rows in, {@code TEX_FREE} removes the master.
     *
     * @throws IllegalStateException for another kind, a create of a handle that has a master, an update or free of one
     *                               that has none, or an update that does not fit its master
     */
    void apply(Record r) {
        assert r.intact() : r + " changed after it was offered";
        switch (r.kind()) {
            case Record.TEX_CREATE: {
                TexCreateRecord c = (TexCreateRecord) r;
                if (entries.containsKey(c.handle)) {
                    throw new IllegalStateException(hex(c.handle) + " has a master already");
                }
                entries.put(c.handle, new Master(c.width, c.height, c.format));
                return;
            }
            case Record.TEX_UPDATE: {
                TexUpdateRecord u = (TexUpdateRecord) r;
                Master e = entry(u.handle);
                int bpp = Formats.bytesPerPixel(e.format);
                byte[] payload = u.payload();
                if (u.width > e.width - u.x || u.height > e.height - u.y
                    || payload.length != u.width * u.height * bpp) {
                    throw new IllegalStateException(r + " does not fit its " + e.width + "x" + e.height + " master");
                }
                int row = u.width * bpp;
                for (int j = 0; j < u.height; j++) {
                    System.arraycopy(payload, j * row, e.texels, ((u.y + j) * e.width + u.x) * bpp, row);
                }
                return;
            }
            case Record.TEX_FREE: {
                int handle = ((TexFreeRecord) r).handle;
                entry(handle);
                entries.remove(handle);
                return;
            }
            default:
                throw new IllegalStateException(r + " is no resource record");
        }
    }

    /** Drops every master. */
    void clear() {
        entries.clear();
    }

    /** Copies of every master, by full handle (unmodifiable). */
    SortedMap<Integer, MasterTexture> copy() {
        TreeMap<Integer, MasterTexture> copy = new TreeMap<>();
        for (Map.Entry<Integer, Master> m : entries.entrySet()) {
            Master e = m.getValue();
            copy.put(m.getKey(), new MasterTexture(m.getKey(), e.width, e.height, e.format, e.texels.clone()));
        }
        return Collections.unmodifiableSortedMap(copy);
    }

    private Master entry(int handle) {
        Master e = entries.get(handle);
        if (e == null) {
            throw new IllegalStateException(hex(handle) + " has no master");
        }
        return e;
    }

    private static String hex(int handle) {
        return String.format(Locale.ROOT, "0x%08X", handle);
    }
}
