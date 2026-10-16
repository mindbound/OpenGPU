package io.github.mindbound.opengpu.gltestkit.host;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.font.GlyphTable;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.RecordRenderer;
import io.github.mindbound.opengpu.core.render.RecordVisitor;
import io.github.mindbound.opengpu.core.render.ResetRecord;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexFreeRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;
import io.github.mindbound.opengpu.gl.GlBackend;
import io.github.mindbound.opengpu.gl.GlLedger;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.QueryPolicy;
import io.github.mindbound.opengpu.gl.UploadProbe;
import io.github.mindbound.opengpu.gl.host.DeviceBackend;
import io.github.mindbound.opengpu.gltestkit.GlThread;

/**
 * {@code FakeRenderHost}'s GL renderer (28:205; H54): each call runs on the GL thread through {@code gl.call}, inside
 * one {@code beginPass}/{@code endPass} bracket of a {@link GlBackend} of its own (S8 amendments B). A {@code FRAME}
 * or a {@code RESET} is followed by the expand (H27), so {@link #presentRgba} shows the newest surface. {@link #pixels}
 * reads back through the production PBO path with an immediate fetch ({@code DeviceBackend.readNow}, whose
 * {@code glGetBufferSubData} waits), so FakeRenderHost's explicit steps hold. Textures are uploaded from the record's
 * own bytes through the backend's staging buffer, zeros for a create (28:550). Called by one thread at a time, as
 * {@link RecordRenderer} says. It makes no GL call of its own.
 */
public final class GlRecordRenderer implements RecordRenderer {

    private final GlThread gl;
    private final GlLedger ledger;
    private final GlBackend backend;
    private final Map<DeviceKey, Opening> openings = new HashMap<>();
    private GlHygiene hygiene;
    private List<String> findings;
    private String name = "GlRecordRenderer";
    private int findingsKept;
    private boolean closed;

    /** QueryPolicy.DRIVER, no upload probe, {@link GlTestHost#readbackFinish()}'s policy. No GL work. */
    public GlRecordRenderer(GlThread gl, GlyphTable glyphs, QuadMode mode) {
        if (gl == null) {
            throw new NullPointerException("gl");
        }
        this.gl = gl;
        this.ledger = new GlLedger();
        this.backend = new GlBackend(
            glyphs,
            mode,
            ledger,
            UploadProbe.NONE,
            QueryPolicy.DRIVER,
            GlTestHost.readbackFinish());
    }

    /** As {@link GlTestHost#checkHygiene}: after every call, {@code hygiene.diff()}'s findings go to the list. */
    public GlRecordRenderer checkHygiene(GlHygiene hygiene, List<String> findings, String name) {
        this.hygiene = hygiene;
        this.findings = findings;
        this.name = name;
        return this;
    }

    /** A fresh surface and present texture, both cleared (28:199), replacing an earlier opening of the key. */
    @Override
    public void open(final DeviceKey key, final SurfaceDesc surface) {
        bracket("open " + key, () -> {
            Opening old = openings.remove(key);
            if (old != null) {
                old.device.destroy();
            }
            openings.put(key, new Opening(surface, backend.open(key, surface)));
            return null;
        });
    }

    /**
     * Runs one record in the device's bracket (O5).
     *
     * @throws AssertionError        if a dev-build check finds the record's arrays changed (28:202, :1082)
     * @throws IllegalStateException for a key that is not open
     */
    @Override
    public void accept(final DeviceKey key, final Record record) {
        if (!record.intact()) {
            throw new AssertionError(record + " changed after it was made (28:1082)");
        }
        bracket(record + " for " + key, () -> {
            record.accept(new Runner(opening(key)));
            return null;
        });
    }

    /** The surface now, in the 28 §5.7 layout, through the production readback calls (amendment C). */
    @Override
    public byte[] pixels(final DeviceKey key) {
        return bracket("pixels " + key, () -> {
            Opening o = opening(key);
            int n = o.surface.byteSize();
            ByteBuffer out = backend.staging(n);
            o.device.readNow(out);
            byte[] pixels = new byte[n];
            for (int i = 0; i < n; i++) {
                pixels[i] = out.get(i);
            }
            return pixels;
        });
    }

    /** The device's GL objects deleted (FBOs first, R-10), its ledger rows with them. Nothing for a key not open. */
    @Override
    public void release(final DeviceKey key) {
        bracket("release " + key, () -> {
            Opening o = openings.remove(key);
            if (o != null) {
                o.device.destroy();
            }
            return null;
        });
    }

    /** The present texture, RGBA8, row 0 the surface's top row: a test-only direct read ({@link GlReads}, A08). */
    public byte[] presentRgba(final DeviceKey key) {
        return bracket("presentRgba " + key, () -> {
            Opening o = opening(key);
            return GlReads.rgba(backend, backend.presentTexture(o.device), o.surface);
        });
    }

    /** The surface texture through A08 ({@link GlReads}), not the PBO ring: GlReadbackCrossCheckTest's other side. */
    public byte[] surfaceDirect(final DeviceKey key) {
        return bracket("surfaceDirect " + key, () -> {
            Opening o = opening(key);
            return GlReads.surface(backend, backend.surfaceTexture(o.device), o.surface);
        });
    }

    public GlLedger ledger() {
        return ledger;
    }

    public GlBackend backend() {
        return backend;
    }

    /**
     * Closes the backend (every device destroyed, every shared object deleted) on the GL thread. Idempotent.
     *
     * @throws AssertionError unless the ledger is then empty (24:218)
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        GlTestHost.call(gl, () -> {
            openings.clear();
            backend.close();
            checkHygiene("close");
            return null;
        });
        if (!ledger.isEmpty()) {
            throw new AssertionError(name + ": the ledger is not empty after close: " + ledger.rows());
        }
    }

    @Override
    public String toString() {
        return name + "[" + backend.mode() + ", " + openings.size() + " open]";
    }

    /** Work in one bracket on the GL thread, then the hygiene check. */
    private <T> T bracket(final String what, final Callable<T> work) {
        if (closed) {
            throw new IllegalStateException(name + " is closed");
        }
        return GlTestHost.call(gl, () -> {
            T result;
            backend.beginPass();
            try {
                result = work.call();
            } finally {
                backend.endPass();
            }
            checkHygiene(what);
            return result;
        });
    }

    private Opening opening(DeviceKey key) {
        Opening o = openings.get(key);
        if (o == null) {
            throw new IllegalStateException(key + " is not open on " + name);
        }
        return o;
    }

    private void checkHygiene(String where) {
        if (hygiene == null) {
            return;
        }
        for (String finding : hygiene.diff()) {
            if (findingsKept < 20) {
                findings.add(name + " after " + where + ": " + finding);
            }
            findingsKept++;
        }
    }

    /** One opening: its surface and its backend device. */
    private static final class Opening {

        final SurfaceDesc surface;
        final DeviceBackend device;

        Opening(SurfaceDesc surface, DeviceBackend device) {
            this.surface = surface;
            this.device = device;
        }
    }

    /** One record onto the device, in the bracket; the bytes go through the backend's staging buffer. */
    private final class Runner implements RecordVisitor {

        private final Opening o;

        Runner(Opening o) {
            this.o = o;
        }

        @Override
        public void frame(FrameRecord r) {
            o.device.runFrame(r);
            o.device.expand();
        }

        @Override
        public void texCreate(TexCreateRecord r) {
            int n = r.byteSize();
            ByteBuffer zeros = backend.staging(n);
            for (int i = 0; i < n; i++) {
                zeros.put(i, (byte) 0);
            }
            o.device.createTexture(r.handle, r.width, r.height, r.format, zeros);
        }

        @Override
        public void texUpdate(TexUpdateRecord r) {
            o.device.updateTexture(r.handle, r.x, r.y, r.width, r.height, staged(r.payload()));
        }

        @Override
        public void texFree(TexFreeRecord r) {
            o.device.deleteTexture(r.handle);
        }

        @Override
        public void reset(ResetRecord r) {
            o.device.resetSurface(staged(r.pixels()));
            o.device.expand();
        }

        private ByteBuffer staged(byte[] bytes) {
            ByteBuffer staging = backend.staging(bytes.length);
            staging.put(bytes)
                .flip();
            return staging;
        }
    }
}
