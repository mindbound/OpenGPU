package io.github.mindbound.opengpu.gltestkit.scenario;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.bytes;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.head;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.text;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.with;

import java.util.ArrayList;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.cmd.HandleTypes;
import io.github.mindbound.opengpu.core.cmd.Opcodes;
import io.github.mindbound.opengpu.core.device.CallBudget;
import io.github.mindbound.opengpu.core.device.CallResult;
import io.github.mindbound.opengpu.core.device.Device;
import io.github.mindbound.opengpu.core.device.DeviceCaps;
import io.github.mindbound.opengpu.core.device.DeviceSnapshot;
import io.github.mindbound.opengpu.core.device.LimitReached;
import io.github.mindbound.opengpu.core.device.Statuses;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.device.RecordingBudget;
import io.github.mindbound.opengpu.core.fixtures.device.RecordingListener;
import io.github.mindbound.opengpu.core.fixtures.device.TeeRenderHost;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.QueryPolicy;
import io.github.mindbound.opengpu.gl.UploadProbe;
import io.github.mindbound.opengpu.gl.host.PassBudget;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * Scenario 17 (S8 plan §4.3, §3.12; 24 §3.5; 28:1072-1074; S7's {@code DeviceConcurrencyTest} on GL): the threads of
 * the game on real {@code Device}s and one {@link GlTestHost}, at once, with {@code System::nanoTime}, in both quad
 * modes:
 * <ul>
 * <li>two devices, T2 and a small rgb565 one of tier 3 with tight caps, each driven by its own <b>worker</b>, an OC
 * worker running seeded random callbacks ({@code createTexture}, {@code updateTexture}, {@code free}, {@code submit},
 * {@code present}, {@code readPixels}; valid and invalid alike) through {@link RecordingBudget#call}, OC's direct call
 * and its synchronized retry (28:710-713), repeated as S7's B2 says while a reset lands between the attempts;</li>
 * <li>the <b>GL thread</b> pumped by a render loop, bounded by a count and a stop flag, with a budget of one unit every
 * third pass and an unlimited one otherwise, delivering every readback after its pass (H67);</li>
 * <li>a <b>server thread</b> calling {@code tick()} (H12) and resetting each device about every 500 callbacks of its
 * worker, or a quarter of the worker's count when that is smaller (250 on the GLSM legs) (28:150);</li>
 * <li>a two-thread <b>fold pool</b> (21:91) as both devices' fold executor.</li>
 * </ul>
 * Every readback the devices receive passes a ShadowOracle's checking sink through a tee (S8 amendments E, B5), and
 * the GlTestHost checks the MC-like start after every pump. Then the quiesce, each step bounded: the workers finish,
 * the server and render threads stop, the host is pumped and the pool drained until both devices are idle with an
 * empty tail; a final {@code present} each, pumped until K is that frame. Then: no thread threw; K's pixels and a
 * whole {@code readPixels} equal the oracle's surface; S8 plan §3.11's equation holds; no readback was rejected;
 * {@code done} ids rise and no frame is dropped twice (28:112); at least one reset raced each worker; and the host
 * counted no contract violation (checked at its close). The count is the callbacks per worker: 2 000 on the
 * compatibility leg, 1 000 on each GLSM leg ({@code -Popengpu.gl.cases}). Every wait is bounded, and the whole run
 * ends within {@link #BUDGET_NANOS}, inside the 60 s of its JUnit wrappers (plan §0).
 */
public final class ConcurrencyScenario extends Scenario {

    /** The whole scenario's bound, inside its wrappers' {@code @Timeout(60)}. */
    static final long BUDGET_NANOS = TimeUnit.SECONDS.toNanos(50);
    /** The workers' share of it; the rest is the quiesce and the checks. */
    static final long WORK_NANOS = TimeUnit.SECONDS.toNanos(30);
    /**
     * Plan §4.3: a reset of each device about every 500 callbacks, or a quarter of the worker's count when that is
     * smaller (250 on the GLSM legs; {@code Lane.resetEvery()}).
     */
    static final int RESET_EVERY = 500;
    /** S7's small rgb565 caps (DeviceConcurrencyTest): 16×12, 512-byte frames of 24 records, six textures of 32². */
    static final DeviceCaps SMALL_RGB565 = new DeviceCaps(
        new SurfaceDesc(3, 16, 12, Formats.RGB565),
        new FrameLimits(65536, 512, 24, 4000),
        4096,
        6,
        32,
        512,
        2);
    /** Safety bounds behind the stop flags. */
    private static final int MAX_PASSES = 1_000_000;
    private static final int MAX_TICKS = 1_000_000;
    /** A worker waits this long at most, after a present, for the host to start one more pass. */
    private static final long PRESENT_PACE_NANOS = TimeUnit.MILLISECONDS.toNanos(20);
    private static final long JOIN_MILLIS = 10_000;

    @Override
    protected void run(ScenarioRun run) throws Exception {
        long start = System.nanoTime();
        int callbacks = run.cases(2000, 1000);
        for (QuadMode mode : QuadMode.values()) {
            GlTestHost host = run
                .host(SyntheticGlyphs.TABLE, mode, GlTestHost.defaultOptions(), QueryPolicy.DRIVER, UploadProbe.NONE);
            Harness h = new Harness(run, host, mode, start);
            h.lane(run.key(), DeviceCaps.forTier(2), callbacks, 0x5EED0017L + mode.ordinal());
            h.lane(run.key(), SMALL_RGB565, callbacks, 0x5EED0117L + mode.ordinal());
            h.run();
            run.close(host);
        }
    }

    /** One device, its worker and what it counted. */
    private static final class Lane {

        final DeviceKey key;
        final DeviceCaps caps;
        final int callbacks;
        final long seed;
        final RecordingListener listener = new RecordingListener();
        final RecordingBudget budget = new RecordingBudget();
        final CountDownLatch firstReset = new CountDownLatch(1);
        final AtomicInteger done = new AtomicInteger();
        final Map<String, Integer> outcomes = new TreeMap<>();
        Device device;
        volatile int resets;
        int nextReset;
        int extraAttempts;

        Lane(DeviceKey key, DeviceCaps caps, int callbacks, long seed) {
            this.key = key;
            this.caps = caps;
            this.callbacks = callbacks;
            this.seed = seed;
            this.nextReset = resetEvery();
        }

        int resetEvery() {
            return Math.max(1, Math.min(RESET_EVERY, callbacks / 4));
        }
    }

    /** The host, the tee, the pool, the lanes and the three kinds of thread. */
    private static final class Harness {

        final ScenarioRun run;
        final GlTestHost host;
        final TeeRenderHost tee;
        final String where;
        final long start;
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
        final ConcurrentLinkedQueue<String> thrown = new ConcurrentLinkedQueue<>();
        /** The pool as the devices see it: a task that throws is recorded, not lost on a pool thread. */
        final Executor folds = task -> pool.execute(() -> {
            try {
                task.run();
            } catch (Throwable e) {
                thrown.add("a fold threw " + Scenarios.describe(e));
            }
        });
        final List<Lane> lanes = new ArrayList<>();
        volatile boolean stopServer;
        volatile boolean stopRender;
        volatile boolean stopWorkers;

        Harness(ScenarioRun run, GlTestHost host, QuadMode mode, long start) {
            this.run = run;
            this.host = host;
            this.tee = run.tee(host);
            this.where = mode.toString();
            this.start = start;
        }

        void lane(DeviceKey key, DeviceCaps caps, int callbacks, long seed) {
            Lane lane = new Lane(key, caps, callbacks, seed);
            lane.device = Device.create(key, caps, tee, folds, lane.listener, System::nanoTime);
            lanes.add(lane);
        }

        void run() throws InterruptedException {
            long began = System.nanoTime();
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
                    w.join(Math.max(1, remainingMillis(WORK_NANOS + TimeUnit.SECONDS.toNanos(5))));
                    run.check(!w.isAlive(), where + ": " + w.getName() + " did not finish within the work budget");
                }
            } finally {
                stopWorkers = true;
                for (Lane lane : lanes) {
                    lane.firstReset.countDown();
                }
                stopServer = true;
                server.join(JOIN_MILLIS);
                stopRender = true;
                render.join(JOIN_MILLIS);
                for (Thread w : workers) {
                    w.join(JOIN_MILLIS);
                }
            }
            try {
                boolean stopped = run.check(
                    !server.isAlive() && !render.isAlive(),
                    where + ": the server or render thread did not stop within " + JOIN_MILLIS + " ms");
                for (Thread w : workers) {
                    stopped &= run.check(!w.isAlive(), where + ": " + w.getName() + " did not stop");
                }
                if (stopped) {
                    long passes = host.pumps();
                    quiesce();
                    for (Lane lane : lanes) {
                        check(lane);
                    }
                    log(began, passes);
                }
            } finally {
                // Not shutdownNow: its interrupt can reach a drain task the release has already let go (the latch sees
                // the interrupt after the unpark), which then throws on its pool thread. A pool that does not stop is a
                // failure, and only then interrupted
                pool.shutdown();
                if (!pool.awaitTermination(JOIN_MILLIS, TimeUnit.MILLISECONDS)) {
                    run.fail(where + ": the fold pool did not stop within " + JOIN_MILLIS + " ms");
                    pool.shutdownNow();
                }
                for (String e : thrown) {
                    run.fail(where + ": " + e);
                }
            }
        }

        private Thread thread(String name, Runnable body) {
            Thread t = new Thread(() -> {
                try {
                    body.run();
                } catch (Throwable e) {
                    thrown.add(
                        Thread.currentThread()
                            .getName() + " threw "
                            + Scenarios.describe(e));
                }
            }, "opengpu-concurrency-" + name);
            t.setDaemon(true);
            return t;
        }

        /** Milliseconds left of the given share of the run, from the scenario's start. */
        private long remainingMillis(long share) {
            return TimeUnit.NANOSECONDS.toMillis(start + share - System.nanoTime());
        }

        private boolean pastWork() {
            return System.nanoTime() - start > WORK_NANOS;
        }

        /** Whether the whole run's bound has passed: the quiesce and the final pumps stop then. */
        private boolean pastBudget() {
            return System.nanoTime() - start > BUDGET_NANOS;
        }

        /** The OC worker: random callbacks through OC's call and retry, paced by the host's passes after a present. */
        private void work(Lane lane) {
            Callbacks generator = new Callbacks(lane.caps, new SplittableRandom(lane.seed));
            List<Integer> live = new ArrayList<>();
            Map<Integer, Integer> sizes = new HashMap<>();
            long vram = 0;
            long epoch = lane.device.epoch();
            int bpp = lane.caps.surface.bytesPerPixel();
            for (int i = 0; i < lane.callbacks && !stopWorkers; i++) {
                if (pastWork()) {
                    run.fail(
                        where + ": "
                            + lane.key
                            + "'s worker ran "
                            + i
                            + " of "
                            + lane.callbacks
                            + " callbacks in "
                            + TimeUnit.NANOSECONDS.toSeconds(WORK_NANOS)
                            + " s");
                    return;
                }
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
                Op op = generator.next(live, vram);
                long passes = host.core()
                    .passes();
                CallResult r = callWithRetries(lane, op);
                String outcome = r.isOk() ? "ok" : Statuses.BUSY.equals(r.message()) ? "busy" : "nil";
                lane.outcomes.merge(op.name() + " " + outcome, 1, Integer::sum);
                if (op.kind == Op.CREATE && r.isOk()) {
                    live.add(r.handle());
                    sizes.put(r.handle(), op.w * op.h * bpp);
                    vram += op.w * op.h * bpp;
                    generator.created(r.handle(), op.w, op.h);
                } else if (op.kind == Op.FREE && r.isOk()) {
                    live.remove(Integer.valueOf(op.tex));
                    Integer size = sizes.get(op.tex);
                    vram -= size == null ? 0 : size;
                } else if (op.kind == Op.PRESENT) {
                    pace(passes);
                }
                lane.done.incrementAndGet();
                if (i % 500 == 499) {
                    lane.budget.clearEvents();
                }
            }
        }

        /**
         * Waits, bounded, until the host has begun two passes since the given count, so one whole pass started after
         * the present: the worker keeps pace with the render loop. It yields rather than parks, since a park on
         * Windows lasts a timer tick, up to 15.6 ms.
         */
        private void pace(long passes) {
            long until = System.nanoTime() + PRESENT_PACE_NANOS;
            while (host.core()
                .passes() < passes + 2 && System.nanoTime() < until
                && !stopWorkers) {
                Thread.yield();
            }
        }

        /**
         * S7's B2: OC's call and retry, repeated while LimitReached escapes it, four attempts at most; each extra
         * attempt must follow a reset that landed during the call.
         */
        private CallResult callWithRetries(Lane lane, Op op) {
            for (int attempt = 1;; attempt++) {
                long before = lane.device.epoch();
                try {
                    return lane.budget.call(() -> op.invoke(lane.device, lane.budget));
                } catch (LimitReached e) {
                    long after = lane.device.epoch();
                    if (before == after || attempt >= 4) {
                        throw new AssertionError(
                            op + " threw LimitReached at OC's retry, attempt "
                                + attempt
                                + ", epoch "
                                + before
                                + " to "
                                + after
                                + " (B2, 28:712)");
                    }
                    lane.extraAttempts++;
                }
            }
        }

        /** The render loop: the GL thread's passes, one unit every third pass, unlimited otherwise. */
        private void render() {
            for (int i = 0; !stopRender && i < MAX_PASSES; i++) {
                host.pumpFrame(i % 3 == 0 ? PassBudget.units(1) : PassBudget.unlimited());
            }
        }

        /**
         * The server thread: tick() each device about every millisecond, and a reset about every 500 callbacks, or a
         * quarter of the worker's count when that is smaller (250 on the GLSM legs; {@code Lane.resetEvery()}).
         */
        private void server() {
            for (int i = 0; !stopServer && i < MAX_TICKS; i++) {
                for (Lane lane : lanes) {
                    lane.device.tick();
                    if (lane.done.get() >= lane.nextReset) {
                        lane.device.reset();
                        lane.resets++;
                        lane.nextReset += lane.resetEvery();
                        lane.firstReset.countDown();
                    }
                }
                LockSupport.parkNanos(1_000_000);
            }
        }

        /** The test thread, the others stopped: pumps and drains until both devices are idle with an empty tail. */
        private void quiesce() throws InterruptedException {
            for (int round = 0; round < GlTestHost.MAX_PUMPS && !pastBudget(); round++) {
                host.pumpFrame();
                drain();
                boolean quiet = true;
                for (Lane lane : lanes) {
                    quiet &= host.idle(lane.key) && lane.device.snapshot().tail.isEmpty();
                }
                if (quiet) {
                    return;
                }
            }
            run.fail(
                where + ": the devices were not quiet after "
                    + GlTestHost.MAX_PUMPS
                    + " pumps and drains, or within "
                    + TimeUnit.NANOSECONDS.toSeconds(BUDGET_NANOS)
                    + " s");
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
                if (!inside.await(JOIN_MILLIS, TimeUnit.MILLISECONDS)) {
                    throw new AssertionError("the fold pool did not drain within " + JOIN_MILLIS + " ms");
                }
            } finally {
                release.countDown();
            }
        }

        /** The checks of one quiet device, then its final present and the comparison with the oracle. */
        private void check(Lane lane) throws InterruptedException {
            Device device = lane.device;
            String at = where + " " + lane.key + " " + lane.caps.surface;
            DeviceSnapshot s = device.snapshot();
            run.check(s.tail.isEmpty(), at + ": the tail of a quiet device holds " + s.tail.size() + " records");
            run.check(s.rejectedReadbacks == 0, at + ": " + s.rejectedReadbacks + " readbacks rejected (H8)");
            run.check(lane.resets >= 1, at + ": no reset raced the worker");
            statuses(lane.listener.events(), at);
            // The final present: K becomes that frame, whose pixels are the oracle's surface after every record
            CallResult r = lane.budget.call(() -> device.present(lane.budget));
            if (!run.check(r.isOk(), at + ": the final present returned " + r)) {
                return;
            }
            long id = r.frameId();
            for (int pumps = 0; pumps < GlTestHost.MAX_PUMPS && device.snapshot().confirmedFrameId != id
                && !pastBudget(); pumps++) {
                host.pumpFrame();
                drain();
            }
            s = device.snapshot();
            if (!run.check(
                s.confirmedFrameId == id && s.lastFrameId == id,
                at + ": K is "
                    + s.confirmedFrameId
                    + " after the final frame "
                    + id
                    + " and "
                    + GlTestHost.MAX_PUMPS
                    + " pumps")) {
                return;
            }
            byte[] oracle = tee.shadow()
                .pixelsNow(lane.key);
            run.compare(at + ": K's pixels", lane.caps.surface, oracle, s.confirmedPixels());
            run.compare(at + ": readPixels", lane.caps.surface, oracle, screen(device));
            for (int pumps = 0; pumps < GlTestHost.MAX_PUMPS && !host.idle(lane.key) && !pastBudget(); pumps++) {
                host.pumpFrame();
            }
            DeviceSteps.ledgerEquation(run, host, lane.key, device.snapshot(), at);
            run.check(
                tee.shadow()
                    .readbacksChecked(lane.key) > 0,
                at + ": the ShadowOracle checked no readback");
            run.check(
                lane.listener.events()
                    .contains("done " + id),
                at + ": no done for the final frame " + id);
        }

        private void log(long began, long passes) {
            StringBuilder b = new StringBuilder(
                String.format(
                    Locale.ROOT,
                    "%s: %.2f s, %d passes while the workers ran",
                    where,
                    (System.nanoTime() - began) / 1e9,
                    passes));
            for (Lane lane : lanes) {
                DeviceSnapshot s = lane.device.snapshot();
                b.append(
                    String.format(
                        Locale.ROOT,
                        "%n  %s %s: %d callbacks, %d resets, %d extra attempts, last frame %d, %d readbacks checked,"
                            + " %d stale, %d statuses; %s",
                        lane.key,
                        lane.caps.surface,
                        lane.done.get(),
                        lane.resets,
                        lane.extraAttempts,
                        s.lastFrameId,
                        tee.shadow()
                            .readbacksChecked(lane.key),
                        s.staleReadbacks,
                        lane.listener.events()
                            .size(),
                        lane.outcomes));
            }
            run.log(b.toString());
        }

        /** done ids rise strictly; no frame is reported dropped twice (28:110-112). */
        private void statuses(List<String> events, String at) {
            long lastDone = 0;
            Set<Long> dropped = new HashSet<>();
            for (String e : events) {
                String[] parts = e.split(" ");
                long id = Long.parseLong(parts[1]);
                if (Statuses.DONE.equals(parts[0])) {
                    run.check(id > lastDone, at + ": done " + id + " after done " + lastDone);
                    lastDone = id;
                } else {
                    run.check(
                        Statuses.DROPPED.equals(parts[0]) && dropped.add(id),
                        at + ": " + e + " (a frame dropped twice, or an unknown status)");
                }
            }
        }

        /** The confirmed image whole, through readPixels in bands that fit maxReadbackBytes (as DeviceRig.screen). */
        private byte[] screen(Device device) {
            SurfaceDesc s = device.surface();
            int row = s.width * s.bytesPerPixel();
            int rows = Math.max(1, device.caps().maxReadbackBytes / row);
            CallBudget free = new CallBudget() {

                @Override
                public void consume(double cost) {}

                @Override
                public void pause(double seconds) {}
            };
            byte[] out = new byte[s.byteSize()];
            for (int y = 0; y < s.height; y += rows) {
                CallResult r = device.readPixels(free, 0, y, s.width, Math.min(rows, s.height - y));
                if (!r.isOk()) {
                    throw new IllegalStateException("readPixels returned " + r);
                }
                System.arraycopy(r.data(), 0, out, y * row, r.data().length);
            }
            return out;
        }
    }

    private static void await(CountDownLatch latch, String what) {
        try {
            if (!latch.await(JOIN_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new AssertionError(what + " did not come within " + JOIN_MILLIS + " ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            throw new AssertionError("interrupted waiting for " + what, e);
        }
    }

    /** One callback with its arguments. Immutable but for its array, which nobody writes after it is made. */
    static final class Op {

        static final int CREATE = 0, UPDATE = 1, FREE = 2, SUBMIT = 3, PRESENT = 4, READ_PIXELS = 5;
        private static final String[] NAMES = { "createTexture", "updateTexture", "free", "submit", "present",
            "readPixels" };

        final int kind;
        final int tex, x, y, w, h;
        final String format;
        final byte[] data;

        Op(int kind, int tex, int x, int y, int w, int h, String format, byte[] data) {
            this.kind = kind;
            this.tex = tex;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.format = format;
            this.data = data;
        }

        String name() {
            return NAMES[kind];
        }

        CallResult invoke(Device device, CallBudget budget) {
            switch (kind) {
                case CREATE:
                    return device.createTexture(budget, w, h, format);
                case UPDATE:
                    return device.updateTexture(budget, tex, x, y, w, h, data);
                case FREE:
                    return device.free(tex);
                case SUBMIT:
                    return device.submit(budget, data);
                case PRESENT:
                    return device.present(budget);
                default:
                    return device.readPixels(budget, x, y, w, h);
            }
        }

        @Override
        public String toString() {
            return String.format(
                Locale.ROOT,
                "%s(tex 0x%08X, %d, %d, %d, %d, %s, %d B)",
                name(),
                tex,
                x,
                y,
                w,
                h,
                format,
                data == null ? 0 : data.length);
        }
    }

    /**
     * A seeded mix of the M0 callbacks, valid and invalid (28 §2.1, §3.6; S7's OpGenerator in brief, which lives in
     * {@code :core}'s tests): out of 73, {@code createTexture} 14 (sides 1-8 mostly, then invalid sides, sides at the
     * caps or what is left of the VRAM, and format strings), {@code updateTexture} 14 and {@code free} 8 (handles live,
     * stale or garbage; regions inside, at an edge or outside; data of the right length or one byte off),
     * {@code submit} 20 (1-6 records of {@code CLEAR}, {@code FILL_RECT}, {@code BLIT} with and without {@code KEY0}
     * and {@code TEXT} opaque and transparent, or one malformed segment in ten), {@code present} 12 and
     * {@code readPixels} 5. Not thread-safe: one per worker.
     */
    static final class Callbacks {

        private static final String[] FORMATS = { "index8", "rgb565", "", "INDEX8", "rgb" };

        private final SplittableRandom rnd;
        private final DeviceCaps caps;
        private final SurfaceDesc surface;
        private final int bpp;
        private final List<Integer> created = new ArrayList<>();
        private final Map<Integer, int[]> sizes = new HashMap<>();

        Callbacks(DeviceCaps caps, SplittableRandom rnd) {
            this.caps = caps;
            this.surface = caps.surface;
            this.bpp = surface.bytesPerPixel();
            this.rnd = rnd;
        }

        /** createTexture(w, h) returned this handle. */
        void created(int handle, int w, int h) {
            if (!sizes.containsKey(handle)) {
                created.add(handle);
            }
            sizes.put(handle, new int[] { w, h });
        }

        Op next(List<Integer> live, long vramUsed) {
            int r = rnd.nextInt(73);
            if (r < 14) {
                return create(vramUsed);
            }
            if (r < 28) {
                return update(live);
            }
            if (r < 36) {
                return new Op(Op.FREE, handle(live), 0, 0, 0, 0, null, null);
            }
            if (r < 56) {
                return new Op(Op.SUBMIT, 0, 0, 0, 0, 0, null, submit(live));
            }
            if (r < 68) {
                return new Op(Op.PRESENT, 0, 0, 0, 0, 0, null, null);
            }
            return readPixels();
        }

        private Op create(long vramUsed) {
            int max = caps.maxTextureSize;
            int r = rnd.nextInt(100);
            int w = 1 + rnd.nextInt(8);
            int h = 1 + rnd.nextInt(8);
            if (r < 75) {
                return new Op(Op.CREATE, 0, 0, 0, w, h, null, null);
            }
            if (r < 82) {
                int[] bad = { 0, -1, max + 1, Integer.MIN_VALUE };
                return new Op(Op.CREATE, 0, 0, 0, bad[rnd.nextInt(bad.length)], h, null, null);
            }
            if (r < 90) {
                if (rnd.nextBoolean()) {
                    return new Op(Op.CREATE, 0, 0, 0, max, 1 + rnd.nextInt(4), null, null);
                }
                // What is left of the VRAM, exactly or one row more
                long left = Math.max(1, (caps.vramBytes - vramUsed) / bpp);
                int lw = (int) Math.min(max, left);
                int lh = (int) Math.max(1, Math.min(max, left / lw)) + (rnd.nextBoolean() ? 1 : 0);
                return new Op(Op.CREATE, 0, 0, 0, lw, lh, null, null);
            }
            return new Op(Op.CREATE, 0, 0, 0, w, h, FORMATS[rnd.nextInt(FORMATS.length)], null);
        }

        private Op update(List<Integer> live) {
            int tex = handle(live);
            int[] size = sizes.get(tex);
            int tw = size == null ? 1 + rnd.nextInt(8) : size[0];
            int th = size == null ? 1 + rnd.nextInt(8) : size[1];
            int x;
            int y;
            int w;
            int h;
            if (rnd.nextInt(10) == 0 && tw * th <= 4096) {
                x = 0;
                y = 0;
                w = tw;
                h = th;
            } else {
                x = rnd.nextInt(tw);
                y = rnd.nextInt(th);
                w = 1 + rnd.nextInt(Math.min(8, tw - x));
                h = 1 + rnd.nextInt(Math.min(8, th - y));
            }
            int n = w * h * bpp;
            switch (rnd.nextInt(12)) {
                case 0:
                    // At the right edge, outside
                    x = tw;
                    w = 1;
                    n = h * bpp;
                    break;
                case 1:
                    y = -1;
                    break;
                case 2:
                    n += rnd.nextBoolean() ? 1 : -1;
                    break;
                default:
                    break;
            }
            return new Op(Op.UPDATE, tex, x, y, w, h, null, randomBytes(Math.max(0, n)));
        }

        private int handle(List<Integer> live) {
            int r = rnd.nextInt(100);
            if (r < 70 && !live.isEmpty()) {
                return live.get(rnd.nextInt(live.size()));
            }
            if (r < 85 && !created.isEmpty()) {
                // Freed, or of an older epoch: "not live"
                return created.get(rnd.nextInt(created.size()));
            }
            switch (rnd.nextInt(5)) {
                case 0:
                    return 0;
                case 1:
                    return -1;
                case 2:
                    return Handles.pack(
                        HandleTypes.TEXTURE,
                        rnd.nextInt(Handles.MAX_SLOT + 1),
                        rnd.nextInt(Handles.MAX_GENERATION + 1));
                case 3:
                    return 0x7FFFFFFF;
                default:
                    return Handles.pack(HandleTypes.BUFFER, rnd.nextInt(Handles.MAX_SLOT + 1), 0);
            }
        }

        private byte[] submit(List<Integer> live) {
            if (rnd.nextInt(10) == 0) {
                byte[] valid = segment(fillRecord());
                switch (rnd.nextInt(7)) {
                    case 0:
                        return with(valid, 0, 0x4E);
                    case 1:
                        return segment(fillRecord(), bytes(0x77));
                    case 2:
                        return segment(clear(surface.format == Formats.INDEX8 ? 0x100 : 0x00FF0000));
                    case 3:
                        return segment(text(0, 0, colour(), colour(), 0, 0, bytes(0x41, 0x07)));
                    case 4:
                        return segment(blitRecord(handle(live), 0x02));
                    case 5:
                        return segment(head(fillRecord(), 5 + rnd.nextInt(8)));
                    default:
                        return new byte[caps.limits.submitBytes + 1];
                }
            }
            int n = 1 + rnd.nextInt(6);
            byte[][] records = new byte[n][];
            for (int i = 0; i < n; i++) {
                int r = rnd.nextInt(100);
                if (r < 12) {
                    records[i] = clear(colour());
                } else if (r < 50) {
                    records[i] = fillRecord();
                } else if (r < 80) {
                    records[i] = blitRecord(handle(live), rnd.nextBoolean() ? Opcodes.BLIT_KEY0 : 0);
                } else {
                    records[i] = text(
                        rnd.nextInt(surface.width + 12) - 6,
                        rnd.nextInt(surface.height + 14) - 7,
                        colour(),
                        colour(),
                        rnd.nextBoolean() ? Opcodes.TEXT_TRANSPARENT_BG : 0,
                        0,
                        randomText());
                }
            }
            return segment(records);
        }

        private byte[] fillRecord() {
            int w = surface.width;
            int h = surface.height;
            return fill(
                rnd.nextInt(w + 8) - 4,
                rnd.nextInt(h + 8) - 4,
                rnd.nextInt(Math.min(w, 40) + 4) - 2,
                rnd.nextInt(Math.min(h, 40) + 4) - 2,
                colour());
        }

        private byte[] blitRecord(int tex, int flags) {
            int[] size = sizes.get(tex);
            int tw = size == null ? 8 : size[0];
            int th = size == null ? 8 : size[1];
            return blit(
                tex,
                rnd.nextInt(tw + 4) - 2,
                rnd.nextInt(th + 4) - 2,
                rnd.nextInt(Math.min(tw, 24) + 3) - 1,
                rnd.nextInt(Math.min(th, 24) + 3) - 1,
                rnd.nextInt(surface.width + 8) - 4,
                rnd.nextInt(surface.height + 8) - 4,
                flags);
        }

        /** 0-8 codepoints: printable ASCII, and 2-, 3- and 4-byte UTF-8 (the synthetic table draws a box for those). */
        private String randomText() {
            int n = rnd.nextInt(9);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < n; i++) {
                int r = rnd.nextInt(100);
                if (r < 80) {
                    b.append((char) (0x20 + rnd.nextInt(0x7F - 0x20)));
                } else if (r < 88) {
                    b.append('é');
                } else if (r < 94) {
                    b.append('€');
                } else {
                    b.appendCodePoint(0x1F600);
                }
            }
            return b.toString();
        }

        /** A valid colour of the surface's format (28 §3.4): an index, or opaque 0xFFRRGGBB. */
        private int colour() {
            return surface.format == Formats.INDEX8 ? rnd.nextInt(256) : 0xFF000000 | rnd.nextInt(0x1000000);
        }

        private Op readPixels() {
            int sw = surface.width;
            int sh = surface.height;
            int r = rnd.nextInt(100);
            if (r < 70) {
                int x = rnd.nextInt(sw);
                int y = rnd.nextInt(sh);
                return new Op(
                    Op.READ_PIXELS,
                    0,
                    x,
                    y,
                    1 + rnd.nextInt(Math.min(16, sw - x)),
                    1 + rnd.nextInt(Math.min(16, sh - y)),
                    null,
                    null);
            }
            if (r < 80) {
                return new Op(Op.READ_PIXELS, 0, 0, 0, sw, sh, null, null);
            }
            return new Op(Op.READ_PIXELS, 0, sw - rnd.nextInt(2), rnd.nextInt(sh), 1 + rnd.nextInt(2), 1, null, null);
        }

        private byte[] randomBytes(int n) {
            byte[] out = new byte[n];
            int i = 0;
            while (i < n) {
                long v = rnd.nextLong();
                for (int k = 0; k < 8 && i < n; k++, i++) {
                    out[i] = (byte) v;
                    v >>>= 8;
                }
            }
            return out;
        }
    }
}
