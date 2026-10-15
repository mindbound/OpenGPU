package io.github.mindbound.opengpu.core.fixtures.device;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Function;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.device.CallBudget;
import io.github.mindbound.opengpu.core.device.CallResult;
import io.github.mindbound.opengpu.core.device.Device;
import io.github.mindbound.opengpu.core.device.DeviceCaps;
import io.github.mindbound.opengpu.core.device.DeviceSnapshot;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FakeRenderHost;
import io.github.mindbound.opengpu.core.render.NoRenderHost;
import io.github.mindbound.opengpu.core.render.RecordRenderer;
import io.github.mindbound.opengpu.core.render.RenderHost;

/**
 * One device on a host, with the pieces a test needs around it (S7 plan §2.3; amendment B14): key (1, 1), a direct
 * fold executor unless one is given, a {@link ManualClock}, a {@link RecordingListener} and an unlimited
 * {@link RecordingBudget} through which the helpers call (OC's retry included). For S7's tests, S8 and S10.
 * <ul>
 * <li>{@link #tier} and {@link #of}: a {@link FakeRenderHost} running an {@link OracleRecordRenderer};
 * {@link #step()} is {@code host.step()};</li>
 * <li>{@link #noRenderer}: {@link NoRenderHost} (28:204); {@link #host} and {@link #renderer} are null and
 * {@link #step()} does nothing;</li>
 * <li>{@link #on}: any host with the test's own pump, such as S8's {@code GlTestHost} and its {@code pumpFrame()};
 * {@link #host} is set when the host is a {@code FakeRenderHost}, {@link #renderer} when that host's renderer is an
 * {@code OracleRecordRenderer}.</li>
 * </ul>
 * The helpers {@link #create}, {@link #update}, {@link #free}, {@link #submit} and {@link #present} expect success
 * and throw IllegalStateException with the call and its message otherwise. {@link #screen()} reads the confirmed
 * image whole without touching {@link #budget}'s events. Not thread-safe beyond what the device is.
 */
public final class DeviceRig {

    /** Every rig's key: session 1, device 1. */
    public static final DeviceKey KEY = new DeviceKey(1, 1);

    /** {@link #screen()}'s budget: charges nothing and records nothing. */
    private static final CallBudget UNRECORDED = new CallBudget() {

        @Override
        public void consume(double cost) {}

        @Override
        public void pause(double seconds) {}
    };

    public final Device device;
    /** The host the device was created on; never null. */
    public final RenderHost renderHost;
    /** {@link #renderHost} when it is a {@link FakeRenderHost}, else null. */
    public final FakeRenderHost host;
    /** {@link #host}'s renderer when it is an {@link OracleRecordRenderer}, else null. */
    public final OracleRecordRenderer renderer;
    /** The device's fold executor: {@code Runnable::run} unless {@link #on} was given another. */
    public final Executor folds;
    public final ManualClock clock;
    public final RecordingListener listener;
    public final RecordingBudget budget;
    public final DeviceKey key;

    private final Runnable pump;

    private DeviceRig(DeviceCaps caps, RenderHost renderHost, Runnable pump, Executor folds) {
        if (caps == null || renderHost == null || pump == null || folds == null) {
            throw new NullPointerException("caps, host, pump and folds");
        }
        this.renderHost = renderHost;
        this.host = renderHost instanceof FakeRenderHost ? (FakeRenderHost) renderHost : null;
        RecordRenderer r = host == null ? null : host.renderer();
        this.renderer = r instanceof OracleRecordRenderer ? (OracleRecordRenderer) r : null;
        this.pump = pump;
        this.folds = folds;
        this.clock = new ManualClock();
        this.listener = new RecordingListener();
        this.budget = new RecordingBudget();
        this.key = KEY;
        this.device = Device.create(KEY, caps, renderHost, folds, listener, clock);
    }

    /** {@link #of} on {@code DeviceCaps.forTier(tier)}: 2 or 3 in M0. */
    public static DeviceRig tier(int tier) {
        return of(DeviceCaps.forTier(tier));
    }

    /** A device on a new {@link FakeRenderHost} with a new {@link OracleRecordRenderer}; step runs the host. */
    public static DeviceRig of(DeviceCaps caps) {
        final FakeRenderHost fake = new FakeRenderHost(new OracleRecordRenderer());
        return new DeviceRig(caps, fake, new Runnable() {

            @Override
            public void run() {
                fake.step();
            }
        }, Runnable::run);
    }

    /** {@link #noRenderer(DeviceCaps)} on {@code DeviceCaps.forTier(tier)}. */
    public static DeviceRig noRenderer(int tier) {
        return noRenderer(DeviceCaps.forTier(tier));
    }

    /** A device on {@link NoRenderHost} (28:204): no host to inspect, and {@link #step()} does nothing. */
    public static DeviceRig noRenderer(DeviceCaps caps) {
        return new DeviceRig(caps, NoRenderHost.INSTANCE, () -> {}, Runnable::run);
    }

    /** A device on any host, with a direct fold executor; {@link #step()} runs pump (B14). */
    public static DeviceRig on(DeviceCaps caps, RenderHost host, Runnable pump) {
        return new DeviceRig(caps, host, pump, Runnable::run);
    }

    /** The same with the device's fold executor, such as a {@link ManualExecutor}. */
    public static DeviceRig on(DeviceCaps caps, RenderHost host, Runnable pump, Executor folds) {
        return new DeviceRig(caps, host, pump, folds);
    }

    /** One callback through {@link #budget}'s {@link RecordingBudget#call}, OC's direct call and its retry. */
    public CallResult call(Function<CallBudget, CallResult> call) {
        return budget.call(() -> call.apply(budget));
    }

    /** {@code createTexture(w, h)} with format nil; the handle. */
    public int create(int w, int h) {
        return ok(call(b -> device.createTexture(b, w, h, null)), "createTexture(" + w + ", " + h + ")").handle();
    }

    /** {@code updateTexture(tex, x, y, w, h, data)}, the array handed over as OC's {@code checkByteArray} does. */
    public void update(int tex, int x, int y, int w, int h, byte[] data) {
        ok(
            call(b -> device.updateTexture(b, tex, x, y, w, h, data)),
            String.format(Locale.ROOT, "updateTexture(0x%08X, %d, %d, %d, %d, %d B)", tex, x, y, w, h, data.length));
    }

    /** {@code free(tex)}, uncharged (28:707). */
    public void free(int tex) {
        ok(device.free(tex), String.format(Locale.ROOT, "free(0x%08X)", tex));
    }

    /** {@code submit(stream)}. */
    public void submit(byte[] stream) {
        ok(call(b -> device.submit(b, stream)), "submit(" + stream.length + " B)");
    }

    /** {@code present()}; the frame id. */
    public long present() {
        return ok(call(b -> device.present(b)), "present()").frameId();
    }

    /** The pump: {@code host.step()}, nothing for {@link #noRenderer}, or {@link #on}'s pump. */
    public void step() {
        pump.run();
    }

    /** {@link #device}'s snapshot. */
    public DeviceSnapshot snapshot() {
        return device.snapshot();
    }

    /**
     * {@link RecordText} of everything offered to {@link #host} for the key, in order.
     *
     * @throws IllegalStateException unless the rig's host is a {@code FakeRenderHost}
     */
    public List<String> offeredText() {
        if (host == null) {
            throw new IllegalStateException(renderHost + " keeps no offered records");
        }
        return RecordText.of(host.offered(key));
    }

    /** {@link RecordText} of the device's server tail, head first. */
    public List<String> tailText() {
        return RecordText.of(device.snapshot().tail);
    }

    /**
     * The host's oracle for the key: {@code renderer.oracle(key)}.
     *
     * @throws IllegalStateException unless the rig's host runs an {@code OracleRecordRenderer}
     */
    public TierEOracle hostOracle() {
        if (renderer == null) {
            throw new IllegalStateException(renderHost + " runs no OracleRecordRenderer");
        }
        return renderer.oracle(key);
    }

    /**
     * The confirmed image whole (28:99), in the 28 §5.7 layout: {@code readPixels} in bands of whole rows that fit
     * {@code maxReadbackBytes}, or, when one row does not fit, in pieces of a row, concatenated. Charged to a budget
     * of its own, so {@link #budget}'s events show only the test's calls.
     *
     * @throws IllegalStateException if a read fails, if one pixel does not fit the readback cap, or if the confirmed
     *                               frame changed between two reads
     */
    public byte[] screen() {
        SurfaceDesc s = device.surface();
        int width = s.width;
        int height = s.height;
        int bpp = s.bytesPerPixel();
        int cap = device.caps().maxReadbackBytes;
        int row = width * bpp;
        byte[] out = new byte[s.byteSize()];
        long[] frame = { -1 };
        if (row <= cap) {
            int rows = cap / row;
            for (int y = 0; y < height; y += rows) {
                int h = Math.min(rows, height - y);
                byte[] band = read(0, y, width, h, frame);
                System.arraycopy(band, 0, out, y * row, band.length);
            }
        } else {
            int columns = cap / bpp;
            if (columns < 1) {
                throw new IllegalStateException(
                    "a readback of " + cap + " B holds no pixel of " + bpp + " B (" + s + ")");
            }
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x += columns) {
                    int w = Math.min(columns, width - x);
                    byte[] piece = read(x, y, w, 1, frame);
                    System.arraycopy(piece, 0, out, (y * width + x) * bpp, piece.length);
                }
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "DeviceRig[" + device + " on " + renderHost + "]";
    }

    private byte[] read(int x, int y, int w, int h, long[] frame) {
        CallResult r = ok(
            device.readPixels(UNRECORDED, x, y, w, h),
            String.format(Locale.ROOT, "readPixels(%d, %d, %d, %d)", x, y, w, h));
        if (frame[0] >= 0 && r.frameId() != frame[0]) {
            throw new IllegalStateException(
                "the confirmed frame moved from " + frame[0] + " to " + r.frameId() + " during screen()");
        }
        frame[0] = r.frameId();
        return r.data();
    }

    private static CallResult ok(CallResult r, String what) {
        if (!r.isOk()) {
            throw new IllegalStateException(what + ": " + r.message());
        }
        return r;
    }
}
