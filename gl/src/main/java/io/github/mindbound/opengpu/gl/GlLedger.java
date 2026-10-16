package io.github.mindbound.opengpu.gl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * G10 (26:114-117; 24:83): every GL allocation OpenGPU makes, keyed (session, deviceId, kind, label) to bytes and a
 * count. A row appears at its first {@link #add} and goes when its count returns to 0. Deletes leave it at call time,
 * although GLSM defers the real delete (28:604; 25:470). Host-wide objects (programs, VAOs, the stream VBO, the atlas,
 * the palette) use {@link #HOST_SESSION} and {@link #HOST_DEVICE}, never a server session (H34). Thread-safe through
 * one private lock: written on the GL thread, read from any thread.
 */
public final class GlLedger {

    /** The session of host-wide objects (H34). */
    public static final long HOST_SESSION = -1L;
    /** The device of host-wide objects (H34). */
    public static final int HOST_DEVICE = -1;

    // Kinds (S8 plan §3.11)
    public static final String SURFACE = "surface";
    public static final String PRESENT = "present";
    public static final String TEXTURE = "texture";
    public static final String PBO = "pbo";
    public static final String FBO = "fbo";
    public static final String QUERY = "query";
    public static final String GLYPH_ATLAS = "glyph-atlas";
    public static final String PALETTE = "palette";
    public static final String PROGRAM = "program";
    public static final String VAO = "vao";
    public static final String VBO = "vbo";

    private final Object lock = new Object();
    /** Guarded by lock; sorted, so {@link #rows()} needs no sort. */
    private final Map<Key, long[]> rows = new TreeMap<>();

    /**
     * Count + 1 and bytes + bytes on the row, which this creates if absent.
     *
     * @throws NullPointerException     for a null kind or label
     * @throws IllegalArgumentException for negative bytes
     */
    public void add(long session, int deviceId, String kind, String label, long bytes) {
        Key key = new Key(session, deviceId, kind, label);
        if (bytes < 0) {
            throw new IllegalArgumentException(key + ": negative bytes " + bytes);
        }
        synchronized (lock) {
            long[] row = rows.get(key);
            if (row == null) {
                row = new long[2];
                rows.put(key, row);
            }
            row[0] += bytes;
            row[1]++;
        }
    }

    /**
     * Count - 1 and bytes - bytes on the row, which goes at count 0.
     *
     * @throws NullPointerException     for a null kind or label
     * @throws IllegalStateException    if the row is absent (a double delete) or if either value would go negative, or
     *                                  the bytes would not reach 0 with the count (a size that does not match the add)
     * @throws IllegalArgumentException for negative bytes
     */
    public void remove(long session, int deviceId, String kind, String label, long bytes) {
        Key key = new Key(session, deviceId, kind, label);
        if (bytes < 0) {
            throw new IllegalArgumentException(key + ": negative bytes " + bytes);
        }
        synchronized (lock) {
            long[] row = rows.get(key);
            if (row == null) {
                throw new IllegalStateException(key + " is not in the ledger: a double delete, or no add");
            }
            long left = row[0] - bytes;
            long count = row[1] - 1;
            if (left < 0 || count == 0 && left != 0) {
                throw new IllegalStateException(
                    String.format(
                        Locale.ROOT,
                        "%s holds %d B in %d, and removing %d B does not match an add",
                        key,
                        row[0],
                        row[1],
                        bytes));
            }
            if (count == 0) {
                rows.remove(key);
            } else {
                row[0] = left;
                row[1] = count;
            }
        }
    }

    /** The bytes of every row of the kind for the device, summed over labels. */
    public long bytes(long session, int deviceId, String kind) {
        long sum = 0;
        synchronized (lock) {
            for (Map.Entry<Key, long[]> entry : rows.entrySet()) {
                Key key = entry.getKey();
                if (key.session == session && key.deviceId == deviceId && key.kind.equals(kind)) {
                    sum += entry.getValue()[0];
                }
            }
        }
        return sum;
    }

    /** The objects of the kind for the device, summed over labels. */
    public int count(long session, int deviceId, String kind) {
        long sum = 0;
        synchronized (lock) {
            for (Map.Entry<Key, long[]> entry : rows.entrySet()) {
                Key key = entry.getKey();
                if (key.session == session && key.deviceId == deviceId && key.kind.equals(kind)) {
                    sum += entry.getValue()[1];
                }
            }
        }
        return (int) sum;
    }

    /** The device's bytes over every kind. */
    public long deviceBytes(long session, int deviceId) {
        long sum = 0;
        synchronized (lock) {
            for (Map.Entry<Key, long[]> entry : rows.entrySet()) {
                Key key = entry.getKey();
                if (key.session == session && key.deviceId == deviceId) {
                    sum += entry.getValue()[0];
                }
            }
        }
        return sum;
    }

    /** Every row, a sorted copy. */
    public List<Row> rows() {
        List<Row> out = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<Key, long[]> entry : rows.entrySet()) {
                out.add(new Row(entry.getKey(), entry.getValue()[0], (int) entry.getValue()[1]));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** The device's rows, a sorted copy. */
    public List<Row> rows(long session, int deviceId) {
        List<Row> out = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<Key, long[]> entry : rows.entrySet()) {
                Key key = entry.getKey();
                if (key.session == session && key.deviceId == deviceId) {
                    out.add(new Row(key, entry.getValue()[0], (int) entry.getValue()[1]));
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Whether no row has the session: after {@code releaseSession(s)} and one pass (24:218). */
    public boolean isEmpty(long session) {
        synchronized (lock) {
            for (Key key : rows.keySet()) {
                if (key.session == session) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether the ledger holds no row at all: after the host's {@code close()}. */
    public boolean isEmpty() {
        synchronized (lock) {
            return rows.isEmpty();
        }
    }

    /** A texture's label, its full handle in hex (28:602): "0x%08X". */
    public static String textureLabel(int handle) {
        return String.format(Locale.ROOT, "0x%08X", handle);
    }

    /** A row's key. Immutable; ordered by session, device, kind, then label. */
    public static final class Key implements Comparable<Key> {

        public final long session;
        public final int deviceId;
        public final String kind;
        public final String label;

        /** @throws NullPointerException for a null kind or label */
        public Key(long session, int deviceId, String kind, String label) {
            if (kind == null || label == null) {
                throw new NullPointerException("kind and label");
            }
            this.session = session;
            this.deviceId = deviceId;
            this.kind = kind;
            this.label = label;
        }

        @Override
        public int compareTo(Key other) {
            int c = Long.compare(session, other.session);
            if (c == 0) {
                c = Integer.compare(deviceId, other.deviceId);
            }
            if (c == 0) {
                c = kind.compareTo(other.kind);
            }
            if (c == 0) {
                c = label.compareTo(other.label);
            }
            return c;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key)) {
                return false;
            }
            Key that = (Key) other;
            return session == that.session && deviceId == that.deviceId
                && kind.equals(that.kind)
                && label.equals(that.label);
        }

        @Override
        public int hashCode() {
            return ((Long.hashCode(session) * 31 + deviceId) * 31 + kind.hashCode()) * 31 + label.hashCode();
        }

        /** For example "1:2 texture 0x08000000", or "host program fill" for a host-wide row. */
        @Override
        public String toString() {
            String owner = session == HOST_SESSION && deviceId == HOST_DEVICE ? "host" : session + ":" + deviceId;
            return owner + " " + kind + " " + label;
        }
    }

    /** One row: its key, bytes and object count. Immutable. */
    public static final class Row {

        public final Key key;
        public final long bytes;
        public final int count;

        Row(Key key, long bytes, int count) {
            this.key = key;
            this.bytes = bytes;
            this.count = count;
        }

        /** For example "1:2 texture 0x08000000 = 256 B in 1". */
        @Override
        public String toString() {
            return key + " = " + bytes + " B in " + count;
        }
    }
}
