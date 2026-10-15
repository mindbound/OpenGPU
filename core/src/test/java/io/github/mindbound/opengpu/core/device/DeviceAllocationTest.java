package io.github.mindbound.opengpu.core.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.cmd.LiveView;
import io.github.mindbound.opengpu.core.cmd.Validation;
import io.github.mindbound.opengpu.core.cmd.Validator;
import io.github.mindbound.opengpu.core.fixtures.MapLiveView;
import io.github.mindbound.opengpu.core.fixtures.device.ManualClock;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.NoRenderHost;
import io.github.mindbound.opengpu.core.render.ReadbackSink;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.RenderHost;

/**
 * The live view allocates nothing (28:287; S6 G20, 28:1056; plan §3.13, §4.4): validation reads the device's handle
 * table through {@code textureFormat}, {@code textureWidth} and {@code textureHeight}, which must neither allocate nor
 * box, so that validation allocates nothing per record with the device as its view. Measured as S6's
 * {@code ValidatorAllocationTest} measures, with HotSpot's per-thread allocation counter by reflection (skipped where
 * the JVM lacks it): after 500 warm-up runs, the least one run allocates over 20 runs, less the counter's own
 * allocation. Amendment B3 adds a positive control, the same harness on S6's {@code MapLiveView}, which boxes every
 * lookup and so must show at least 16 B per call (else the counter is blind, escape analysis having removed the
 * boxing, and the check is void), and a structural check that needs no JIT: the table holds no {@code java.util}
 * collection, and the lookups take and return {@code int}. The readback ring (amendment B; 21:85) allocates only when
 * no array is free: once warm, a present and the readback of that frame, folded on the caller's thread, allocate no
 * surface array, the control being a fresh device's first readback, which allocates its pending array.
 */
class DeviceAllocationTest {

    private static final int T = 0x08000000;
    private static final int STALE = 0x08000001;
    private static final int CALLS = 100_000;
    /** Present, readback and fold cycles per measured run of the ring. */
    private static final int CYCLES = 4;
    private static final CallBudget FREE = new CallBudget() {

        @Override
        public void consume(double cost) {}

        @Override
        public void pause(double seconds) {}
    };

    /** Keep the results reachable, so that no run is optimized away. */
    static volatile long sink;
    static volatile Validation validationSink;
    static volatile CallResult resultSink;

    @Test
    void theLookupsAllocateNothing() throws Exception {
        Counter counter = Counter.get();
        Device device = device(3);
        Assertions.assertEquals(
            T,
            device.createTexture(FREE, 16, 16, null)
                .handle());
        MapLiveView boxing = new MapLiveView(device.surface(), device.limits());
        Assertions.assertEquals(T, boxing.create(16, 16));
        // the validator's call sites see many views; so does this harness's, which keeps the lookups out of the
        // loop's compilation, where escape analysis would remove MapLiveView's boxing and blind the counter
        LiveView[] views = { device, boxing, new ConstantView(device, 1), new ConstantView(device, 2) };
        for (int round = 0; round < 20; round++) {
            for (LiveView view : views) {
                sink = lookups(view);
            }
        }
        long perRun = counter.least(() -> sink = lookups(device), null);
        System.out.println("DeviceAllocationTest: " + 3 * CALLS + " lookups on the device allocate " + perRun + " B");
        Assertions.assertTrue(perRun <= 1024, 3 * CALLS + " lookups allocated " + perRun + " B, over 1 024");
        // the control: the same harness on a view that boxes
        long control = counter.least(() -> sink = lookups(boxing), null);
        System.out.println("DeviceAllocationTest: the same on MapLiveView allocate " + control + " B");
        if (control < 16L * 3 * CALLS) {
            Assertions.fail(
                "allocation counter blind (EA); the check is void: MapLiveView's " + 3 * CALLS
                    + " boxing lookups allocated "
                    + control
                    + " B");
        }
    }

    @Test
    void validationWithTheDeviceAsItsViewAllocatesNothingPerRecord() throws Exception {
        Counter counter = Counter.get();
        Device device = device(3);
        Assertions.assertEquals(
            T,
            device.createTexture(FREE, 16, 16, null)
                .handle());
        byte[] s = blits(3600);
        Assertions.assertEquals(64809, s.length);
        Validation v = Validator.validate(s, device);
        Assertions.assertTrue(v.isAccepted(), v::toString);
        Assertions.assertEquals(3600, v.totals().records);
        Assertions.assertEquals(921600, v.totals().copyPixels);
        long perRun = counter.least(() -> validationSink = Validator.validate(s, device), null);
        System.out
            .println("DeviceAllocationTest: one validate of 3 600 BLITs on the device allocates " + perRun + " B");
        Assertions.assertTrue(perRun <= 1024, "one validate of 3 600 BLITs allocated " + perRun + " B, over 1 024");
        // one object per record would be at least 57 600 B: MapLiveView shows the counter would see it
        MapLiveView boxing = new MapLiveView(device.surface(), device.limits());
        boxing.create(16, 16);
        long control = counter.least(() -> validationSink = Validator.validate(s, boxing), null);
        System.out.println("DeviceAllocationTest: the same on MapLiveView allocates " + control + " B");
        if (control < 57600) {
            Assertions.fail(
                "allocation counter blind (EA); the check is void: validating on MapLiveView allocated " + control
                    + " B");
        }
    }

    @Test
    void anAcceptedSubmitAllocatesLittle() throws Exception {
        Counter counter = Counter.get();
        Device device = device(3);
        Assertions.assertEquals(
            T,
            device.createTexture(FREE, 16, 16, null)
                .handle());
        byte[] s = blits(3600);
        long perRun = counter.least(() -> resultSink = device.submit(FREE, s), () -> device.present(FREE));
        Assertions.assertEquals(CallResult.TRUE, resultSink);
        System.out.println("DeviceAllocationTest: one accepted submit of 64 809 B allocates " + perRun + " B");
        Assertions.assertTrue(perRun <= 2048, "one accepted submit allocated " + perRun + " B, over 2 048");
        Assertions.assertEquals(FrameTotals.EMPTY, device.snapshot().openTotals, "each submit's frame was discarded");
    }

    /**
     * Amendment B's "allocates only when none is free": on a T3 device (a 512 000-byte surface) with a direct fold
     * executor, each cycle presents a frame and delivers its readback, which is copied into the spare and folded,
     * K's old array becoming the spare. A run of four cycles allocates the records, the result and the status, never a
     * surface array. The control: a fresh device's first readback finds no array free and allocates one.
     */
    @Test
    void theReadbackRingAllocatesNoSurfaceArrayOnceWarm() throws Exception {
        Counter counter = Counter.get();
        Device device = Device.create(
            new DeviceKey(1, 1),
            DeviceCaps.forTier(3),
            new AvailableHost(),
            Runnable::run,
            FrameListener.NONE,
            new ManualClock());
        byte[] px = new byte[device.surface()
            .byteSize()];
        Assertions.assertEquals(512000, px.length);
        Assertions.assertEquals(
            1L,
            device.present(FREE)
                .frameId());
        long first = counter.once(() -> device.onReadback(1, 1, 1, 1, px));
        Assertions.assertEquals(1, device.snapshot().confirmedFrameId);
        System.out.println("DeviceAllocationTest: a fresh T3 device's first readback allocates " + first + " B");
        if (first < 512000) {
            Assertions.fail(
                "allocation counter blind; the check is void: the first readback, which allocates the pending array, "
                    + "allocated "
                    + first
                    + " B");
        }
        long perRun = counter.least(() -> {
            for (int i = 0; i < CYCLES; i++) {
                long frame = device.present(FREE)
                    .frameId();
                device.onReadback(1, 1, 1, frame, px);
            }
        }, null);
        System.out.println(
            "DeviceAllocationTest: " + CYCLES + " cycles of present, readback and fold allocate " + perRun + " B");
        Assertions.assertTrue(
            perRun <= CYCLES * 1024L,
            CYCLES + " cycles of present, readback and fold allocated " + perRun + " B, over " + CYCLES * 1024);
        DeviceSnapshot s = device.snapshot();
        Assertions.assertEquals(1 + (500 + 20) * CYCLES, s.lastFrameId);
        Assertions.assertEquals(s.lastFrameId, s.confirmedFrameId, "every readback folded");
        Assertions.assertEquals(0, s.staleReadbacks + s.rejectedReadbacks);
    }

    /** B3's structural check: int arrays only, int in and out. */
    @Test
    void theHandleTableIsIntArraysAndTheLookupsTakeAndReturnInt() throws Exception {
        for (Field f : TextureTable.class.getDeclaredFields()) {
            Class<?> type = f.getType();
            Assertions.assertFalse(
                Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)
                    || type.getName()
                        .startsWith("java.util."),
                "TextureTable." + f.getName() + " is a " + type.getName());
            Assertions.assertTrue(
                type.isPrimitive() || type == int[].class,
                "TextureTable." + f.getName() + " is a " + type.getName());
        }
        for (String name : new String[] { "textureFormat", "textureWidth", "textureHeight" }) {
            Method m = Device.class.getMethod(name, int.class);
            Assertions.assertEquals(int.class, m.getReturnType(), "Device." + name);
        }
        for (String name : new String[] { "format", "width", "height", "byteSize" }) {
            Method m = TextureTable.class.getDeclaredMethod(name, int.class);
            Assertions.assertEquals(int.class, m.getReturnType(), "TextureTable." + name);
        }
    }

    // ---- helpers

    private static Device device(int tier) {
        return Device.create(
            new DeviceKey(1, 1),
            DeviceCaps.forTier(tier),
            NoRenderHost.INSTANCE,
            Runnable::run,
            FrameListener.NONE,
            new ManualClock());
    }

    /** CALLS rounds of the three lookups, alternating the live handle and a stale one of its slot. */
    private static long lookups(LiveView view) {
        long acc = 0;
        for (int i = 0; i < CALLS; i++) {
            int h = (i & 1) == 0 ? T : STALE;
            acc += view.textureFormat(h) + view.textureWidth(h) + view.textureHeight(h);
        }
        return acc;
    }

    /** One segment of n 16×16 KEY0 blits of 0x08000000 (18 B each). */
    private static byte[] blits(int n) {
        byte[][] records = new byte[n][];
        for (int i = 0; i < n; i++) {
            records[i] = blit(T, 0, 0, 16, 16, (i * 16) % 640, (i / 40) % 400, 1);
        }
        return segment(records);
    }

    /** A host that is available and keeps nothing: present offers its frame, and the test delivers the readback. */
    private static final class AvailableHost implements RenderHost {

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public void open(DeviceKey key, SurfaceDesc surface, ReadbackSink sink) {}

        @Override
        public void offer(DeviceKey key, Record record) {}

        @Override
        public void release(DeviceKey key) {}

        @Override
        public void bindDisplay(String displayId, DeviceKey key) {}

        @Override
        public void releaseSession(long session) {}
    }

    /** A view whose lookups answer a constant: a third and fourth receiver type for the harness's call sites. */
    private static final class ConstantView implements LiveView {

        private final LiveView like;
        private final int value;

        ConstantView(LiveView like, int value) {
            this.like = like;
            this.value = value;
        }

        @Override
        public SurfaceDesc surface() {
            return like.surface();
        }

        @Override
        public FrameLimits limits() {
            return like.limits();
        }

        @Override
        public int textureFormat(int handle) {
            return value;
        }

        @Override
        public int textureWidth(int handle) {
            return value + 1;
        }

        @Override
        public int textureHeight(int handle) {
            return value + 2;
        }
    }

    /** HotSpot's per-thread allocation counter, by reflection. */
    private static final class Counter {

        private final Object bean;
        private final Method allocated;
        private final long thread = Thread.currentThread()
            .getId();

        private Counter(Object bean, Method allocated) {
            this.bean = bean;
            this.allocated = allocated;
        }

        /** The counter, or the test skipped where the JVM has none (as S6's allocation test does). */
        static Counter get() throws Exception {
            Object bean = ManagementFactory.getThreadMXBean();
            try {
                Class<?> type = Class.forName("com.sun.management.ThreadMXBean");
                Assumptions.assumeTrue(type.isInstance(bean), "no com.sun.management.ThreadMXBean");
                Method allocated = type.getMethod("getThreadAllocatedBytes", long.class);
                Method supported = type.getMethod("isThreadAllocatedMemorySupported");
                Assumptions.assumeTrue((Boolean) supported.invoke(bean), "thread allocation is not measured");
                Method enabled = type.getMethod("isThreadAllocatedMemoryEnabled");
                if (!(Boolean) enabled.invoke(bean)) {
                    type.getMethod("setThreadAllocatedMemoryEnabled", boolean.class)
                        .invoke(bean, true);
                }
                return new Counter(bean, allocated);
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                Assumptions.assumeTrue(false, "no thread allocation counter: " + e);
                return null;
            }
        }

        /** What one run allocates, cold, less the counter's own allocation. */
        long once(Runnable run) throws Exception {
            long a = now();
            long b = now();
            long before = now();
            run.run();
            long after = now();
            return after - before - (b - a);
        }

        /**
         * After 500 warm-up runs, the least that one run allocates over 20, less the counter's own allocation;
         * between runs, unmeasured, the optional reset.
         */
        long least(Runnable run, Runnable between) throws Exception {
            for (int i = 0; i < 500; i++) {
                run.run();
                if (between != null) {
                    between.run();
                }
            }
            long overhead = Long.MAX_VALUE;
            long least = Long.MAX_VALUE;
            for (int i = 0; i < 20; i++) {
                long a = now();
                long b = now();
                overhead = Math.min(overhead, b - a);
                long before = now();
                run.run();
                long after = now();
                least = Math.min(least, after - before);
                if (between != null) {
                    between.run();
                }
            }
            return least - overhead;
        }

        private long now() throws Exception {
            return (Long) allocated.invoke(bean, thread);
        }
    }
}
