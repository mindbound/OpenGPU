package io.github.mindbound.opengpu.gltestkit.scenario;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
import io.github.mindbound.opengpu.core.fixtures.golden.GoldenRunner;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.ResetRecord;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexFreeRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;
import io.github.mindbound.opengpu.gltestkit.host.GlRecordRenderer;

/**
 * A golden case's target (28:933, :938) on a {@link GlRecordRenderer}: each step becomes the record a device would
 * offer, of epoch 1, with frame ids from 1 (28 §2.3), accepted by the renderer, and {@code pixels()} reads back through
 * the production calls. A tier-E oracle on the synthetic glyphs runs the same steps, so the present texture can be
 * compared with {@code Colours.expandToRgba} of the oracle's surface (H27).
 */
public final class GlGoldenTarget implements GoldenRunner.Target {

    /** Every record's epoch. */
    public static final long EPOCH = 1;

    private final GlRecordRenderer renderer;
    private final DeviceKey key;
    private final SurfaceDesc surface;
    private final TierEOracle oracle;
    private long frameId;

    private GlGoldenTarget(GlRecordRenderer renderer, DeviceKey key, SurfaceDesc surface) {
        this.renderer = renderer;
        this.key = key;
        this.surface = surface;
        this.oracle = new TierEOracle(surface, SyntheticGlyphs.TABLE);
    }

    /** Opens the key on the renderer (a cleared surface, 28:199) and returns its target. */
    public static GlGoldenTarget open(GlRecordRenderer renderer, DeviceKey key, SurfaceDesc surface) {
        GlGoldenTarget target = new GlGoldenTarget(renderer, key, surface);
        renderer.open(key, surface);
        return target;
    }

    @Override
    public void createTexture(int handle, int width, int height, int format) {
        oracle.createTexture(handle, width, height, format);
        renderer.accept(key, new TexCreateRecord(EPOCH, handle, width, height, format));
    }

    @Override
    public void updateTexture(int handle, int x, int y, int width, int height, byte[] payload) {
        oracle.updateTexture(handle, x, y, width, height, payload);
        renderer.accept(key, new TexUpdateRecord(EPOCH, handle, x, y, width, height, payload));
    }

    @Override
    public void freeTexture(int handle) {
        oracle.freeTexture(handle);
        renderer.accept(key, new TexFreeRecord(EPOCH, handle));
    }

    @Override
    public void reset(byte[] pixels) {
        oracle.reset(pixels);
        renderer.accept(key, new ResetRecord(EPOCH, pixels));
    }

    @Override
    public void runFrame(byte[][] segments) {
        oracle.runFrame(segments);
        renderer.accept(key, new FrameRecord(EPOCH, ++frameId, segments, FrameTotals.EMPTY));
    }

    @Override
    public byte[] pixels() {
        return renderer.pixels(key);
    }

    /** The oracle that ran the same steps. */
    public TierEOracle oracle() {
        return oracle;
    }

    public DeviceKey key() {
        return key;
    }

    public SurfaceDesc surface() {
        return surface;
    }

    /** Releases the key on the renderer: its GL objects and ledger rows go. */
    public void release() {
        renderer.release(key);
    }
}
