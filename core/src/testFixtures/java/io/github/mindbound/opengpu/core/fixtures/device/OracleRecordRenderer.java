package io.github.mindbound.opengpu.core.fixtures.device;

import java.util.HashMap;
import java.util.Map;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
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

/**
 * The tier-E oracle as {@code FakeRenderHost}'s renderer (28:205; S6 plan §1.8): one {@link TierEOracle} per open key,
 * on {@link SyntheticGlyphs#TABLE} or a given glyph table, which runs every record it is handed in offered order
 * (28 §2.4: "the oracle sees {@code TEX_*} and {@code RESET} as well as frames"):
 * <ul>
 * <li>{@code FRAME}: {@link TierEOracle#runSegment} of each segment, in submit order;</li>
 * <li>{@code TEX_CREATE}: {@link TierEOracle#createTexture}(handle, w, h, format);</li>
 * <li>{@code TEX_UPDATE}: {@link TierEOracle#updateTexture}(handle, x, y, w, h, payload);</li>
 * <li>{@code TEX_FREE}: {@link TierEOracle#freeTexture}(handle);</li>
 * <li>{@code RESET}: {@link TierEOracle#reset}(pixels).</li>
 * </ul>
 * {@link #open} makes a new oracle, replacing an older one of the key; {@link #release} drops it. Every method takes
 * this renderer's own lock, so a test thread may look up {@link #oracle} while a host runs records; the oracle itself
 * is not thread-safe, so a test reads it only while no host is running that key's records. Like every
 * {@link RecordRenderer}, it never calls a device or a {@code ReadbackSink}.
 */
public final class OracleRecordRenderer implements RecordRenderer {

    private final GlyphTable glyphs;
    private final Map<DeviceKey, TierEOracle> oracles = new HashMap<>();

    /** On the synthetic glyph table (28:633). */
    public OracleRecordRenderer() {
        this(SyntheticGlyphs.TABLE);
    }

    /** @throws NullPointerException for a null table */
    public OracleRecordRenderer(GlyphTable glyphs) {
        if (glyphs == null) {
            throw new NullPointerException("glyphs");
        }
        this.glyphs = glyphs;
    }

    /** The glyph table every oracle of this renderer draws {@code TEXT} with. */
    public GlyphTable glyphs() {
        return glyphs;
    }

    /** A new, zeroed oracle on the surface (24:197; 21:242), replacing any older one of the key. */
    @Override
    public synchronized void open(DeviceKey key, SurfaceDesc surface) {
        if (key == null) {
            throw new NullPointerException("key");
        }
        oracles.put(key, new TierEOracle(surface, glyphs));
    }

    /**
     * Runs the record on the key's oracle, which throws what it throws for a record validation never produces.
     *
     * @throws IllegalStateException for a key that is not open
     */
    @Override
    public synchronized void accept(DeviceKey key, Record record) {
        if (record == null) {
            throw new NullPointerException("record");
        }
        record.accept(new Runner(oracleLocked(key)));
    }

    /**
     * The key's oracle's {@link TierEOracle#pixels()}: a fresh array in the 28 §5.7 layout.
     *
     * @throws IllegalStateException for a key that is not open
     */
    @Override
    public synchronized byte[] pixels(DeviceKey key) {
        return oracleLocked(key).pixels();
    }

    /** Drops the key's oracle; a later {@link #open} starts afresh. Nothing for a key that is not open. */
    @Override
    public synchronized void release(DeviceKey key) {
        oracles.remove(key);
    }

    /** Whether the key has an oracle: opened and not released since. */
    public synchronized boolean isOpen(DeviceKey key) {
        return oracles.containsKey(key);
    }

    /**
     * The key's oracle itself, for a test to inspect: its surface, pixels and textures.
     *
     * @throws IllegalStateException for a key that is not open
     */
    public synchronized TierEOracle oracle(DeviceKey key) {
        return oracleLocked(key);
    }

    @Override
    public String toString() {
        return "OracleRecordRenderer";
    }

    private TierEOracle oracleLocked(DeviceKey key) {
        TierEOracle oracle = oracles.get(key);
        if (oracle == null) {
            throw new IllegalStateException(key + " is not open");
        }
        return oracle;
    }

    /** One record's mapping onto the oracle's record methods. */
    private static final class Runner implements RecordVisitor {

        private final TierEOracle oracle;

        Runner(TierEOracle oracle) {
            this.oracle = oracle;
        }

        @Override
        public void frame(FrameRecord r) {
            for (int i = 0; i < r.segmentCount(); i++) {
                oracle.runSegment(r.segment(i));
            }
        }

        @Override
        public void texCreate(TexCreateRecord r) {
            oracle.createTexture(r.handle, r.width, r.height, r.format);
        }

        @Override
        public void texUpdate(TexUpdateRecord r) {
            oracle.updateTexture(r.handle, r.x, r.y, r.width, r.height, r.payload());
        }

        @Override
        public void texFree(TexFreeRecord r) {
            oracle.freeTexture(r.handle);
        }

        @Override
        public void reset(ResetRecord r) {
            oracle.reset(r.pixels());
        }
    }
}
