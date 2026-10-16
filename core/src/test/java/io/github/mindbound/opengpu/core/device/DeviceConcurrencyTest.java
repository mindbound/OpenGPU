package io.github.mindbound.opengpu.core.device;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.device.model.OpGenerator;
import io.github.mindbound.opengpu.core.device.model.OpGenerator.Op;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
import io.github.mindbound.opengpu.core.fixtures.device.OracleRecordRenderer;
import io.github.mindbound.opengpu.core.fixtures.device.RecordingBudget;
import io.github.mindbound.opengpu.core.fixtures.device.RecordingListener;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FakeRenderHost;

/**
 * S7 plan §4.5's concurrency test with amendments B2 and B15: the threads of 24 §3.5 (plan §3.10) on real
 * {@code Device}s at once, with {@code System::nanoTime}, the tier-E oracle host and a two-thread fold pool (21:91):
 * <ul>
 * <li>a <b>worker</b> per device, an OC worker running seeded random callbacks ({@link OpGenerator#nextCallback})
 * through {@link RecordingBudget#call}, OC's direct call and its synchronized retry (28:710-713). As B2 says, a
 * {@code LimitReached} that escapes the retry is repeated, at most four attempts, and each extra attempt must coincide
 * with a reset: a reset between the attempt and the retry clears the device's exhausted flag (H14), which OC never
 * does, since it never retries across a reboot;</li>
 * <li>a <b>render thread</b> stepping the host (28:205), which hands readbacks to the devices on its own thread
 * (28:194), and checks I1-I7 and I10-I12 on snapshots taken while everything runs, which hold at every release of
 * the monitor, not only at quiescence;</li>
 * <li>a <b>server thread</b> calling {@code tick()} (H12) and resetting a device every 2 000 iterations
 * (28:150).</li>
 * </ul>
 * Every loop is bounded by a count or a volatile stop flag; the host keeps no history (B15). Quiescing stops the
 * server thread, then the render thread, steps the host until nothing is queued and drains the pool. Then: no thread
 * threw (the oracle host throws for a record out of order, such as a {@code BLIT} of a texture already freed, O3-O5);
 * I1-I12 hold; the tail is empty; no readback was rejected (a faithful host's readbacks are stale at worst, H8);
 * {@code done} ids rise strictly (28:112: one coalesced {@code done} per fold, K never moving back) and no frame is
 * reported dropped twice; and after a final {@code present} and step K is that frame, whose pixels equal the host's
 * surface and {@code readPixels}, and the masters equal the host's textures (I9 at quiescence: the tail being empty,
 * the replay is K's pixels and the masters).
 */
class DeviceConcurrencyTest {

    /** Plan §4's small rgb565 caps, the T3 twin of the small caps. */
    private static final DeviceCaps SMALL_RGB565 = new DeviceCaps(
        new SurfaceDesc(3, 16, 12, Formats.RGB565),
        new FrameLimits(65536, 512, 24, 4000),
        4096,
        6,
        32,
        512,
        2);

    private static final int RESET_EVERY = 2000;
    private static final long JOIN_MILLIS = 20_000;
    /** Safety bounds behind the stop flags. */
    private static final int MAX_STEPS = 50_000_000;
    private static final int MAX_TICKS = 200_000_000;

    /** Plan §4.5: one T2 device, a worker of 20 000 callbacks, the render thread and the server thread. */
    @Test
    @Timeout(30)
    void oneDeviceRacingTheRenderThreadAndTheServerThread() throws Exception {
        Harness h = new Harness();
        h.lane(new DeviceKey(1, 1), DeviceCaps.forTier(2), 20_000, 0x5EED0001L);
        h.run();
    }

    /**
     * Two devices of different tiers and formats on one host and one pool (O1: one order per device, none across
     * devices, 28:132), a worker each, one render thread stepping both and one server thread resetting them in turn:
     * the shared host lock and fold threads keep each device's records and folds its own.
     */
    @Test
    @Timeout(30)
    void twoDevicesSharingTheHostAndThePool() throws Exception {
        Harness h = new Harness();
        h.lane(new DeviceKey(7, 1), DeviceCaps.forTier(2), 8_000, 0x5EED0002L);
        h.lane(new DeviceKey(7, 2), SMALL_RGB565, 8_000, 0x5EED0003L);
        h.run();
    }

    /** One device and its worker's bookkeeping. */
    private static final class Lane {

        final DeviceKey key;
        final DeviceCaps caps;
        final int callbacks;
        final long seed;
        final RecordingListener listener = new RecordingListener();
        final RecordingBudget budget = new RecordingBudget();
        final CountDownLatch firstReset = new CountDownLatch(1);
        final Map<String, Integer> outcomes = new TreeMap<>();
        Device device;
        volatile int resets;
        int extraAttempts;

        Lane(DeviceKey key, DeviceCaps caps, int callbacks, long seed) {
            this.key = key;
            this.caps = caps;
            this.callbacks = callbacks;
            this.seed = seed;
        }
    }

    /** The host, the pool, the lanes and the three kinds of thread. */
    private static final class Harness {

        final OracleRecordRenderer renderer = new OracleRecordRenderer();
        final FakeRenderHost host = new FakeRenderHost(renderer);
        final ThreadPoolExecutor pool = new ThreadPoolExecutor(
            2,
            2,
            0,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>(),
            r -> {
                Thread t = new Thread(r, "opengpu-fold");
                t.setDaemon(true);
                return t;
            });
        final ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        /** The pool as the devices see it: a task that throws is recorded, not lost on a pool thread. */
        final Executor folds = task -> pool.execute(() -> {
            try {
                task.run();
            } catch (Throwable e) {
                failures.add(e);
            }
        });
        final List<Lane> lanes = new ArrayList<>();
        volatile boolean stopServer;
        volatile boolean stopRender;

        Harness() {
            host.setKeepHistory(false);
        }

        void lane(DeviceKey key, DeviceCaps caps, int callbacks, long seed) {
            Lane lane = new Lane(key, caps, callbacks, seed);
            lane.device = Device.create(key, caps, host, folds, lane.listener, System::nanoTime);
            lanes.add(lane);
        }

        void run() throws Exception {
            long started = System.nanoTime();
            List<Thread> workers = new ArrayList<>();
            Thread render = thread("render", this::render);
            Thread server = thread("server", this::server);
            try {
                for (final Lane lane : lanes) {
                    workers.add(thread("worker " + lane.key, () -> work(lane)));
                }
                render.start();
                server.start();
                for (Thread w : workers) {
                    w.start();
                }
                for (Thread w : workers) {
                    w.join(JOIN_MILLIS);
                    Assertions.assertFalse(w.isAlive(), w.getName() + " did not finish");
                }
            } finally {
                stopServer = true;
                server.join(JOIN_MILLIS);
                stopRender = true;
                render.join(JOIN_MILLIS);
                for (Lane lane : lanes) {
                    lane.firstReset.countDown();
                }
            }
            try {
                Assertions
                    .assertFalse(server.isAlive() || render.isAlive(), "the server or render thread did not stop");
                quiesce();
                assertNoFailures();
                for (Lane lane : lanes) {
                    check(lane);
                }
                assertNoFailures();
            } finally {
                // Not shutdownNow: its interrupt can reach a drain task the release has already let go (the latch sees
                // the interrupt after the unpark), which then throws on its pool thread, a trace that fails no test. A
                // pool that does not stop is a failure, and only then interrupted
                pool.shutdown();
                if (!pool.awaitTermination(JOIN_MILLIS, TimeUnit.MILLISECONDS)) {
                    failures.add(new AssertionError("the fold pool did not stop within " + JOIN_MILLIS + " ms"));
                    pool.shutdownNow();
                }
            }
            // Reached only when the checks passed: a failure above stays the test's own
            assertNoFailures();
            StringBuilder b = new StringBuilder(
                String.format(Locale.ROOT, "DeviceConcurrencyTest: %.2f s", (System.nanoTime() - started) / 1e9));
            for (Lane lane : lanes) {
                DeviceSnapshot s = lane.device.snapshot();
                b.append(
                    String.format(
                        Locale.ROOT,
                        "%n  %s %s: %d resets, %d extra attempts, last frame %d, stale readbacks %d, %d statuses; %s",
                        lane.key,
                        lane.caps.surface,
                        lane.resets,
                        lane.extraAttempts,
                        s.lastFrameId,
                        s.staleReadbacks,
                        lane.listener.events()
                            .size(),
                        lane.outcomes));
            }
            System.out.println(b);
        }

        private Thread thread(String name, Runnable body) {
            Thread t = new Thread(() -> {
                try {
                    body.run();
                } catch (Throwable e) {
                    failures.add(e);
                }
            }, name);
            t.setDaemon(true);
            return t;
        }

        /** The OC worker: random callbacks through OC's direct call and its retry, with B2's repeat. */
        private void work(Lane lane) {
            OpGenerator generator = new OpGenerator(lane.caps, new SplittableRandom(lane.seed));
            List<Integer> live = new ArrayList<>();
            Map<Integer, Integer> sizes = new HashMap<>();
            long vram = 0;
            long epoch = lane.device.epoch();
            int bpp = lane.caps.surface.bytesPerPixel();
            for (int i = 0; i < lane.callbacks; i++) {
                if (i == lane.callbacks / 2) {
                    // Let at least one reset race the second half (the server thread counts it down)
                    await(lane.firstReset, "the first reset of " + lane.key);
                }
                long now = lane.device.epoch();
                if (now != epoch) {
                    // A reset freed every handle (28:150)
                    live.clear();
                    vram = 0;
                    epoch = now;
                }
                Op op = generator.nextCallback(live, vram);
                CallResult r = callWithRetries(lane, op);
                String outcome = r.isOk() ? "ok" : "busy".equals(r.message()) ? "busy" : "nil";
                lane.outcomes.merge(op.kind + " " + outcome, 1, Integer::sum);
                if (op.kind == OpGenerator.Kind.CREATE && r.isOk()) {
                    live.add(r.handle());
                    sizes.put(r.handle(), op.w * op.h * bpp);
                    vram += op.w * op.h * bpp;
                    generator.created(r.handle(), op.w, op.h);
                } else if (op.kind == OpGenerator.Kind.FREE && r.isOk()) {
                    live.remove(Integer.valueOf(op.tex));
                    vram -= sizes.get(op.tex);
                }
                if (i % 1000 == 999) {
                    lane.budget.clearEvents();
                }
            }
        }

        /**
         * B2: OC's call and retry, repeated while LimitReached escapes it, at most four attempts; every extra attempt
         * must follow a reset that landed during the call.
         */
        private CallResult callWithRetries(Lane lane, Op op) {
            for (int attempt = 1;; attempt++) {
                long before = lane.device.epoch();
                try {
                    return lane.budget.call(() -> invoke(lane.device, lane.budget, op));
                } catch (LimitReached e) {
                    long after = lane.device.epoch();
                    Assertions.assertNotEquals(
                        before,
                        after,
                        op + " threw LimitReached at OC's retry with no reset during the call (B2, 28:712)");
                    Assertions.assertTrue(attempt < 4, op + " threw LimitReached at four attempts in a row");
                    lane.extraAttempts++;
                }
            }
        }

        /** The render thread: steps the host and checks the invariants on snapshots taken meanwhile. */
        private void render() {
            for (int i = 0; !stopRender && i < MAX_STEPS; i++) {
                host.step();
                if (i % 64 == 63) {
                    for (Lane lane : lanes) {
                        Invariants.check(lane.device.snapshot(), lane.caps);
                    }
                }
            }
        }

        /** The server thread: tick() each device every iteration, a reset of one device every RESET_EVERY. */
        private void server() {
            int resets = 0;
            for (int i = 1; !stopServer && i < MAX_TICKS; i++) {
                for (Lane lane : lanes) {
                    lane.device.tick();
                }
                if (i % RESET_EVERY == 0) {
                    Lane lane = lanes.get(resets++ % lanes.size());
                    lane.device.reset();
                    lane.resets++;
                    lane.firstReset.countDown();
                }
                Thread.yield();
            }
        }

        /** On the test thread, the others stopped: step until nothing is queued, then drain the pool. */
        private void quiesce() throws InterruptedException {
            for (int round = 0; round < 1000; round++) {
                host.step();
                drain();
                boolean queued = false;
                for (Lane lane : lanes) {
                    queued |= host.queued(lane.key) > 0;
                }
                if (!queued) {
                    return;
                }
            }
            Assertions.fail("the host still had records queued after 1 000 steps");
        }

        /**
         * Waits until both pool threads run a task of this method's, so every task queued before them has finished
         * (the pool takes tasks in queue order), then lets them go.
         */
        private void drain() throws InterruptedException {
            final CountDownLatch inside = new CountDownLatch(2);
            final CountDownLatch release = new CountDownLatch(1);
            try {
                for (int i = 0; i < 2; i++) {
                    pool.execute(() -> {
                        inside.countDown();
                        await(release, "the drain's release");
                    });
                }
                Assertions.assertTrue(inside.await(10, TimeUnit.SECONDS), "the fold pool did not drain");
            } finally {
                release.countDown();
            }
        }

        private void check(Lane lane) throws InterruptedException {
            Device device = lane.device;
            String where = " on " + lane.key + " " + lane.caps.surface;
            DeviceSnapshot s = Invariants.check(device.snapshot(), lane.caps);
            Assertions.assertTrue(s.tail.isEmpty(), "the tail of a quiescent device is not empty" + where);
            Assertions.assertEquals(0, s.rejectedReadbacks, "readbacks of a faithful host were rejected (H8)" + where);
            Assertions.assertTrue(lane.resets >= 1, "no reset raced the worker" + where);
            assertStatuses(lane.listener.events(), where);

            // A final present and step: K is that frame, and the host's surface is K's pixels
            CallResult r = lane.budget.call(() -> device.present(lane.budget));
            Assertions.assertTrue(r.isOk(), "the final present returned " + r + where);
            host.step();
            drain();
            assertNoFailures();
            s = Invariants.check(device.snapshot(), lane.caps);
            Assertions.assertEquals(r.frameId(), s.lastFrameId, "the final frame's id" + where);
            Assertions.assertEquals(s.lastFrameId, s.confirmedFrameId, "K after the final present and step" + where);
            Assertions.assertTrue(s.tail.isEmpty(), "the tail after the final fold" + where);
            TierEOracle oracle = renderer.oracle(lane.key);
            byte[] hostPixels = oracle.pixels();
            if (!Arrays.equals(hostPixels, s.confirmedPixels())) {
                Assertions.fail("K's pixels differ from the host's surface" + where);
            }
            if (!Arrays.equals(hostPixels, screen(device))) {
                Assertions.fail("readPixels differs from the host's surface" + where);
            }
            // I9 at quiescence: with the tail empty, the replay is K's pixels and the masters
            Assertions.assertEquals(s.masters.size(), oracle.textureCount(), "the host's textures" + where);
            for (MasterTexture m : s.masters.values()) {
                Assertions.assertTrue(oracle.hasTexture(m.handle), Invariants.hex(m.handle) + " is not on the host");
                byte[] t = m.texels();
                boolean index8 = m.format == Formats.INDEX8;
                for (int y = 0; y < m.height; y++) {
                    for (int x = 0; x < m.width; x++) {
                        int i = y * m.width + x;
                        int want = index8 ? t[i] & 0xFF : (t[2 * i] & 0xFF) | (t[2 * i + 1] & 0xFF) << 8;
                        if (oracle.texel(m.handle, x, y) != want) {
                            Assertions.fail(
                                Invariants.hex(
                                    m.handle) + "'s master differs from the host at (" + x + ", " + y + ")" + where);
                        }
                    }
                }
            }
            Assertions.assertTrue(
                lane.listener.events()
                    .contains("done " + r.frameId()),
                "the final frame's done" + where);
        }

        private void assertNoFailures() {
            Throwable first = failures.peek();
            if (first != null) {
                AssertionError e = new AssertionError(failures.size() + " thread(s) threw; the first: " + first, first);
                for (Throwable t : failures) {
                    if (t != first) {
                        e.addSuppressed(t);
                    }
                }
                throw e;
            }
        }
    }

    /** One callback of the generator's op on the device. */
    private static CallResult invoke(Device device, CallBudget budget, Op op) {
        switch (op.kind) {
            case CREATE:
                return device.createTexture(budget, op.w, op.h, op.format);
            case UPDATE:
                return device.updateTexture(budget, op.tex, op.x, op.y, op.w, op.h, op.data);
            case FREE:
                return device.free(op.tex);
            case SUBMIT:
                return device.submit(budget, op.data);
            case PRESENT:
                return device.present(budget);
            case READ_PIXELS:
                return device.readPixels(budget, op.x, op.y, op.w, op.h);
            default:
                throw new AssertionError(op + " is no callback");
        }
    }

    /** done ids rise strictly; no frame is dropped twice (28:110-112; plan §3.7). */
    private static void assertStatuses(List<String> events, String where) {
        long lastDone = 0;
        Set<Long> dropped = new HashSet<>();
        for (String e : events) {
            String[] parts = e.split(" ");
            long id = Long.parseLong(parts[1]);
            if ("done".equals(parts[0])) {
                Assertions.assertTrue(id > lastDone, "done " + id + " after done " + lastDone + where);
                lastDone = id;
            } else {
                Assertions.assertEquals("dropped", parts[0], e);
                Assertions.assertTrue(dropped.add(id), "frame " + id + " reported dropped twice" + where);
            }
        }
    }

    /** The confirmed image whole, through readPixels in bands that fit maxReadbackBytes (as DeviceRig.screen()). */
    private static byte[] screen(Device device) {
        SurfaceDesc s = device.surface();
        int row = s.width * s.bytesPerPixel();
        int rows = device.caps().maxReadbackBytes / row;
        Assertions.assertTrue(rows >= 1, "a row of " + s + " does not fit one readback");
        CallBudget free = new CallBudget() {

            @Override
            public void consume(double cost) {}

            @Override
            public void pause(double seconds) {}
        };
        byte[] out = new byte[s.byteSize()];
        long frame = -1;
        for (int y = 0; y < s.height; y += rows) {
            CallResult r = device.readPixels(free, 0, y, s.width, Math.min(rows, s.height - y));
            Assertions.assertTrue(r.isOk(), "readPixels returned " + r);
            Assertions.assertTrue(frame < 0 || frame == r.frameId(), "K moved during the read");
            frame = r.frameId();
            System.arraycopy(r.data(), 0, out, y * row, r.data().length);
        }
        return out;
    }

    private static void await(CountDownLatch latch, String what) {
        try {
            Assertions.assertTrue(latch.await(10, TimeUnit.SECONDS), what + " did not come within 10 s");
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            throw new AssertionError("interrupted waiting for " + what, e);
        }
    }
}
