package io.github.mindbound.opengpu.gltestkit.scenario;

import java.util.List;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.device.DeviceCaps;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.TeeRenderHost;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.ReadbackSink;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.QueryPolicy;
import io.github.mindbound.opengpu.gl.UploadProbe;
import io.github.mindbound.opengpu.gl.host.DeviceBackend;
import io.github.mindbound.opengpu.gl.host.DeviceStats;
import io.github.mindbound.opengpu.gltestkit.host.GlHygiene;
import io.github.mindbound.opengpu.gltestkit.host.GlReads;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * Scenario 9 (S8 plan §4.3; 24:197, 21:87, 28:1073; S8 amendments C), on {@link GlTestHost} through the production PBO
 * ring, every readback checked by a ShadowOracle, in both quad modes.
 * <ul>
 * <li>{@code QueryPolicy.DRIVER}, what holds on every driver: no readback before a pump; the first arrives within
 * {@code forceFetchAfterPasses + 1} pumps, labelled with the newest frame; three frames with no pump between give one
 * readback, of the newest; the host hands every readback of an opening in the same array (28:1073); the sink runs
 * after the pass with the start state restored (H67). Where the driver leaves two readbacks in flight, a
 * {@code glFinish} makes both fetchable in one pass, which must deliver only the newer (21:87).</li>
 * <li>{@code QueryPolicy.NEVER_READY}: one issue per pass up to two in flight, a third frame owed while two are (H36)
 * and replaced by a fourth, each fetch forced exactly {@code forceFetchAfterPasses} passes after its issue, and the
 * owed frame issued at the first free slot: frames 1, 2 and 4 delivered, 3 never.</li>
 * <li>A {@code Device} on the host (DeviceRig, T2): its confirmed image follows each delivery although the host reuses
 * its array.</li>
 * </ul>
 * Kills plan §4.7's "a third readback issued while two are in flight" and, on drivers that leave two in flight,
 * "labelled with the oldest".
 */
public final class ReadbackScenario extends Scenario {

    @Override
    protected void run(ScenarioRun run) {
        for (QuadMode mode : QuadMode.values()) {
            driver(run, mode);
            neverReady(run, mode);
            device(run, mode);
        }
    }

    private static void driver(final ScenarioRun run, QuadMode mode) {
        final GlTestHost host = run.host(mode);
        TeeRenderHost tee = run.tee(host);
        final SurfaceDesc surface = new SurfaceDesc(0, 21, 13, Formats.INDEX8);
        final DeviceKey key = run.key();
        final Deliveries d = new Deliveries(
            () -> host.core()
                .passes());
        final String where = mode + " DRIVER";
        tee.open(key, surface, new RestoredSink(run, run.hygiene(), d, where));
        int force = host.core()
            .options()
            .forceFetchAfterPasses();
        tee.offer(key, frame(surface, 1));
        run.check(d.count() == 0, where + ": a readback before any pump");
        int pumps = host.pumpUntil(() -> d.count() >= 1, force + 1, "frame 1's readback");
        run.check(pumps >= 1, where + ": frame 1's readback came without a pump");
        run.check(d.last().epoch == 1 && d.last().frameId == 1, where + ": the first readback is " + d.last());
        // Three frames, no pump between: one readback, of the newest (21:87)
        tee.offer(key, frame(surface, 2));
        tee.offer(key, frame(surface, 3));
        tee.offer(key, frame(surface, 4));
        host.pumpUntil(() -> d.count() >= 2, force + 1, "frames 2-4's readback");
        host.pumpUntilIdle(key);
        run.check(d.count() == 2 && d.last().frameId == 4, where + ": frames 2-4 gave " + d);
        run.check(d.get(0).array == d.get(1).array, where + ": the host did not reuse its readback array (28:1073)");
        // Two in flight and both fetchable in one pass: only the newer is delivered
        tee.offer(key, frame(surface, 5));
        host.pumpFrame();
        tee.offer(key, frame(surface, 6));
        host.pumpFrame();
        DeviceStats stats = host.stats(key);
        boolean twoInFlight = stats.readbacksInFlight == 2;
        if (twoInFlight) {
            run.onGl(() -> {
                GlReads.finish();
                return null;
            });
            int before = d.count();
            long fetched = stats.readbacksFetched;
            host.pumpFrame();
            DeviceStats after = host.stats(key);
            if (after.readbacksFetched - fetched == 2) {
                run.check(
                    d.count() == before + 1 && d.last().frameId == 6,
                    where + ": two fetched in one pass delivered " + d + " (21:87: only the newer)");
            }
            run.log(mode + ": two readbacks in flight; one pass fetched " + (after.readbacksFetched - fetched));
        } else {
            run.log(mode + ": the driver fetched frame 5's readback before frame 6's issue");
        }
        host.pumpUntilIdle(key);
        run.check(d.last().frameId == 6, where + ": the last readback is " + d.last());
        tee.release(key);
        run.close(host);
    }

    private static void neverReady(final ScenarioRun run, QuadMode mode) {
        final GlTestHost host = run
            .host(SyntheticGlyphs.TABLE, mode, GlTestHost.defaultOptions(), QueryPolicy.NEVER_READY, UploadProbe.NONE);
        TeeRenderHost tee = run.tee(host);
        SurfaceDesc surface = new SurfaceDesc(0, 17, 9, Formats.RGB565);
        DeviceKey key = run.key();
        Deliveries d = new Deliveries(
            () -> host.core()
                .passes());
        String where = mode + " NEVER_READY";
        tee.open(key, surface, d);
        int force = host.core()
            .options()
            .forceFetchAfterPasses();
        long[] issuedAt = new long[5];
        for (int f = 1; f <= 4; f++) {
            tee.offer(key, frame(surface, f));
            host.pumpFrame();
            DeviceStats s = host.stats(key);
            int inFlight = Math.min(f, 2);
            run.check(s.readbacksInFlight == inFlight, where + ": after frame " + f + ": " + s);
            run.check(s.readbackOwed == (f > 2), where + ": a readback owed after frame " + f + " (H36): " + s);
            if (f <= 2) {
                issuedAt[f] = host.core()
                    .passes();
            }
        }
        run.check(d.count() == 0, where + ": a readback before " + force + " passes: " + d);
        int pumps = 0;
        while (d.count() < 3 && pumps < 3 * force) {
            host.pumpFrame();
            pumps++;
            run.check(host.stats(key).readbacksInFlight <= 2, where + ": more than two in flight");
            if (d.count() == 1 && issuedAt[4] == 0) {
                // The owed frame 4 goes out in the pass that fetched frame 1
                issuedAt[4] = host.core()
                    .passes();
            }
        }
        long[] frames = { 1, 2, 4 };
        if (run.check(d.count() == 3, where + ": readbacks " + d + ", expected frames 1, 2 and 4")) {
            for (int i = 0; i < 3; i++) {
                Deliveries.Delivery delivery = d.get(i);
                long issue = issuedAt[(int) frames[i]];
                run.check(
                    delivery.frameId == frames[i] && delivery.pass - issue == force,
                    where + ": readback "
                        + i
                        + " is "
                        + delivery
                        + ", expected frame "
                        + frames[i]
                        + " at pass "
                        + (issue + force));
            }
        }
        host.pumpUntilIdle(key);
        DeviceStats stats = host.stats(key);
        run.check(stats.readbackLatencyMax == force, where + ": the latency is " + stats.readbackLatencyMax);
        DeviceBackend device = host.deviceBackend(key);
        if (run.check(device != null, where + ": no backend device")) {
            run.check(
                host.backend()
                    .fetchedForced(device) == 3
                    && host.backend()
                        .fetchedWithQueryAvailable(device) == 0,
                where + ": every fetch forced");
        }
        tee.release(key);
        run.close(host);
    }

    /** A Device on the host: the confirmed image after each delivery equals the oracle's surface (28:1073). */
    private static void device(final ScenarioRun run, QuadMode mode) {
        final GlTestHost host = run.host(mode);
        TeeRenderHost tee = run.tee(host);
        DeviceRig rig = DeviceRig.on(DeviceCaps.forTier(2), tee, () -> host.pumpFrame());
        String where = mode + " Device";
        for (int f = 1; f <= 3; f++) {
            rig.submit(
                TestEncoder.segment(
                    TestEncoder.clear(f),
                    TestEncoder.fill(10 * f, 5 * f, 40, 30, 100 + f),
                    TestEncoder.text(f, 2 * f, 15, 0, 0, 0, "frame " + f)));
            long id = rig.present();
            host.pumpUntilIdle(rig.key);
            run.check(
                rig.snapshot().confirmedFrameId == id,
                where + ": confirmed " + rig.snapshot().confirmedFrameId + ", presented " + id);
            run.compare(
                where + " frame " + id + "'s confirmed image",
                rig.device.surface(),
                tee.shadow()
                    .pixelsNow(rig.key),
                rig.screen());
        }
        run.close(host);
    }

    /** {@link #frame(SurfaceDesc, long, long)} of epoch 1. */
    static FrameRecord frame(SurfaceDesc s, long id) {
        return frame(s, 1, id);
    }

    /** A frame of three ops that differ by frame id. */
    static FrameRecord frame(SurfaceDesc s, long epoch, long id) {
        boolean index8 = s.format == Formats.INDEX8;
        int f = (int) id;
        return new FrameRecord(
            epoch,
            id,
            new byte[][] { TestEncoder.segment(
                TestEncoder.clear(index8 ? f : 0xFF000000 | f * 0x111111),
                TestEncoder.fill(f, 1, 5, 4, index8 ? 100 + f : 0xFFFF0000 | f << 3),
                TestEncoder.text(f - 3, 2, index8 ? 200 : 0xFFFFFFFF, index8 ? f : 0xFF000000, f & 1, 0, "#" + f)) },
            FrameTotals.EMPTY);
    }

    /**
     * Forwards to the deliveries, after checking that the sink runs with the start state restored: after the pass,
     * outside the bracket (H67). Called on the GL thread inside the pump.
     */
    static final class RestoredSink implements ReadbackSink {

        private final ScenarioRun run;
        private final GlHygiene start;
        private final ReadbackSink next;
        private final String where;

        RestoredSink(ScenarioRun run, GlHygiene start, ReadbackSink next, String where) {
            this.run = run;
            this.start = start;
            this.next = next;
            this.where = where;
        }

        @Override
        public void onReadback(long session, int deviceId, long epoch, long frameId, byte[] pixels) {
            if (start != null) {
                List<String> found = start.diff();
                for (String finding : found) {
                    run.fail(where + ": in the sink (H67): " + finding);
                }
            }
            next.onReadback(session, deviceId, epoch, frameId, pixels);
        }
    }
}
