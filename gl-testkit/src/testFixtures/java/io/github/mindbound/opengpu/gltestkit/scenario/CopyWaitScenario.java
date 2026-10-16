package io.github.mindbound.opengpu.gltestkit.scenario;

import java.util.Locale;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.device.TeeRenderHost;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.gl.GlBackend;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.QueryPolicy;
import io.github.mindbound.opengpu.gl.ReadbackFinish;
import io.github.mindbound.opengpu.gl.UploadProbe;
import io.github.mindbound.opengpu.gl.host.DeviceStats;
import io.github.mindbound.opengpu.gl.host.HostOptions;
import io.github.mindbound.opengpu.gl.host.PassBudget;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * The readback's copy while the frame before it may still be drawn (S8's fix loop on llvmpipe; 20:138, 24:197): two
 * devices on one host in the shape of ConcurrencyScenario's failing passes there, A's frame, then B's, then A's expand
 * and A's copy. A, 320×200 index8, is bound to a display, so under {@code Expand.BOUND_ONLY} its expand is each pass's
 * last draw that samples; its frames are two fills over the whole surface. B, 16×16 and bound to none, draws a 4×4
 * fill: the other framebuffer between A's frame and A's expand. Every frame is offered before the first pump, so the
 * oracle's work stays out of the gaps between passes, and each pump runs one unit of each device, A's first
 * ({@code PassBudget.units(2)}). Under {@code QueryPolicy.NEVER_READY}, each fetch forced after {@link #FETCH_AFTER}
 * passes, a device copies in two passes of three. In a pass that copies, B's copy finds B's frame and waits for every
 * scene; in the one that copies nothing, nothing waits for A's expand, which lists A's surface for reading only, so in
 * the next pass llvmpipe's checks stop at it and A's copy takes the surface from before the frame, unless
 * {@code GlBackend.finishBeforeCopy} waits. Without that wait, on Mesa 26.0.8 in S8's runs, 35 to 39 of a leg's 40 such
 * copies (both modes) were stale on all threads, and 18 to 55 of its readbacks on two cores. Where the copy waits (a
 * renderer {@code GlBackend.finishesBeforeCopy} names), the host's fetch takes no {@code glFinish} (ReadbackFinish's
 * NEVER), which would drain the scenes before the frames of each pass that fetches and hide the race; elsewhere it
 * takes {@code -Popengpu.gl.readbackFinish}'s (Q-S8-3). Every readback is checked by a ShadowOracle through a tee, byte
 * for byte (28:938; 23:137), in both quad modes; then each device's last readback is its last frame, at least
 * {@link #PASSES} / 2 of A's were checked, and the backend resolved the copy's wait from the leg's {@code GL_RENDERER}
 * as the rule says, its {@code copyFinishes()} above 0 where it is on and 0 elsewhere.
 * <p>
 * A second phase, on the same host and devices, copies in passes that draw nothing, for the rule that only a real
 * {@code glFinish} ends the copy's wait (S8's fix loop after the final verification: a mutant that ended it at a fetch
 * that ran none survived the first phase, where only the last copy follows such a fetch). Two passes that draw and copy
 * put both rings in step, then {@link #CYCLES} cycles of three passes: X, in which A and B draw a frame each and
 * neither copies, two readbacks of each being in flight; Y, with no frame, so nothing draws into a surface, in which
 * the fetch frees a slot and the copies take X's frames; and Z, in which both draw and copy. The oracle checks every
 * readback; each device's last readback is its last frame; each X and Y kept that shape (A's stats); all 2 +
 * 2·{@link #CYCLES} frames of each device were read back; and where the copy waits, each Y's copy ran its
 * {@code glFinish}, Y's fetch having run none, so the phase's {@code copyFinishes()} is 2 + 2·{@link #CYCLES}, one per
 * pass that copies A after a frame, and 0 elsewhere. That count is what pins the rule: on Mesa 26.0.8, A's expand in X
 * binds A for sampling while the first scene that names A is the one drawing A's frame, and llvmpipe then finishes
 * every scene ({@code lp_state_sampler.c:164}, {@code lp_flush.c:134-145}), so Y's copies mostly come out right
 * without the wait. In S8's runs the mutant failed the count on every leg in every run, while the oracle alone caught
 * it in 5 of 30 leg runs on all threads and 11 of 18 on two cores (0.7 % and 9 % of Y's copies stale). Not one of
 * plan §4.3's rows, so not in {@link Scenarios#ALL}: GlCopyWaitTest and GlsmCopyWaitTest run it.
 */
public final class CopyWaitScenario extends Scenario {

    static final int PASSES = 60;
    /** {@code HostOptions.forceFetchAfterPasses}: with two slots, two passes of three copy. */
    static final int FETCH_AFTER = 3;
    /** The second phase's cycles of three passes. */
    static final int CYCLES = 10;
    private static final SurfaceDesc A = new SurfaceDesc(0, 320, 200, Formats.INDEX8);
    private static final SurfaceDesc B = new SurfaceDesc(0, 16, 16, Formats.INDEX8);
    private static final String DISPLAY = "copy-wait";

    @Override
    protected void run(ScenarioRun run) {
        for (QuadMode mode : QuadMode.values()) {
            passes(run, mode);
        }
    }

    private static void passes(ScenarioRun run, QuadMode mode) {
        String renderer = run.gl()
            .info().renderer;
        boolean waits = GlBackend.finishesBeforeCopy(renderer);
        ReadbackFinish finish = waits ? ReadbackFinish.NEVER : GlTestHost.readbackFinish();
        HostOptions options = GlTestHost.defaultOptions()
            .expand(HostOptions.Expand.BOUND_ONLY)
            .forceFetchAfterPasses(FETCH_AFTER);
        GlTestHost host = run
            .host(SyntheticGlyphs.TABLE, mode, options, QueryPolicy.NEVER_READY, UploadProbe.NONE, finish);
        TeeRenderHost tee = run.tee(host);
        String where = mode + ", glFinish " + finish;
        int before = run.failures()
            .size();
        DeviceKey a = run.key();
        DeviceKey b = run.key();
        Deliveries toA = new Deliveries();
        Deliveries toB = new Deliveries();
        tee.open(a, A, toA);
        tee.open(b, B, toB);
        tee.bindDisplay(DISPLAY, a);
        host.pumpFrame();
        for (int k = 1; k <= PASSES; k++) {
            tee.offer(a, frame(k, A.width, A.height, 2));
            tee.offer(b, frame(k, 4, 4, 1));
        }
        for (int k = 1; k <= PASSES; k++) {
            host.pumpFrame(PassBudget.units(2));
        }
        host.pumpUntilIdle(a, b);
        checkLast(run, where, a, toA, PASSES);
        checkLast(run, where, b, toB, PASSES);
        long checked = tee.shadow()
            .readbacksChecked(a);
        long checkedB = tee.shadow()
            .readbacksChecked(b);
        // Not blind: A copies in two passes of three
        run.check(
            checked >= PASSES / 2,
            where + ": only " + checked + " readbacks of " + a + " checked, at least " + PASSES / 2 + " expected");
        GlBackend backend = host.backend();
        long copyFinishes = backend.copyFinishes();
        run.check(
            renderer != null && renderer.equals(backend.renderer()),
            where + ": the backend read GL_RENDERER " + backend.renderer() + ", the leg's is " + renderer);
        run.check(
            backend.copyFinish() == waits && (waits ? copyFinishes > 0 : copyFinishes == 0),
            where + ": the copy's glFinish is "
                + (backend.copyFinish() ? "on" : "off")
                + " with "
                + copyFinishes
                + " call(s) on "
                + renderer
                + ", expected "
                + (waits ? "on, with some" : "off, with none"));
        int failures = run.failures()
            .size() - before;

        // The second phase: two passes that draw and copy, so both rings copy in Y and Z, then the cycles
        long frame = PASSES;
        int phaseStart = run.failures()
            .size();
        for (int i = 0; i < 2; i++) {
            frame++;
            tee.offer(a, frame(frame, A.width, A.height, 2));
            tee.offer(b, frame(frame, 4, 4, 1));
            host.pumpFrame(PassBudget.units(2));
        }
        int shaped = 0;
        int yWaits = 0;
        for (int n = 0; n < CYCLES; n++) {
            frame++;
            tee.offer(a, frame(frame, A.width, A.height, 2));
            tee.offer(b, frame(frame, 4, 4, 1));
            DeviceStats start = host.stats(a);
            // X: both draw, and neither copies
            host.pumpFrame(PassBudget.units(2));
            DeviceStats x = host.stats(a);
            long finishes = backend.copyFinishes();
            // Y: no frame; the fetch frees a slot and the copies take X's frames
            host.pumpFrame(PassBudget.units(2));
            DeviceStats y = host.stats(a);
            if (x.readbacksIssued == start.readbacksIssued && x.readbackOwed
                && y.readbacksFetched == x.readbacksFetched + 1
                && y.readbacksIssued == x.readbacksIssued + 1) {
                shaped++;
            }
            // Where the copy waits, Y's fetch ran no glFinish (NEVER), so X's frames still count: A's copy waits
            if (backend.copyFinishes() - finishes == (waits ? 1 : 0)) {
                yWaits++;
            }
            // Z: both draw and copy
            frame++;
            tee.offer(a, frame(frame, A.width, A.height, 2));
            tee.offer(b, frame(frame, 4, 4, 1));
            host.pumpFrame(PassBudget.units(2));
        }
        host.pumpUntilIdle(a, b);
        // So far the tee's (readbacks unlike their frames) and the hygiene check's
        int passFailures = run.failures()
            .size() - phaseStart;
        String phase = where + ", second phase";
        checkLast(run, phase, a, toA, frame);
        checkLast(run, phase, b, toB, frame);
        run.check(
            shaped == CYCLES,
            phase + ": X copied nothing and Y copied X's frame in only " + shaped + " of " + CYCLES + " cycles");
        long frames = 2 + 2L * CYCLES;
        long checked2 = tee.shadow()
            .readbacksChecked(a) - checked;
        long checked2B = tee.shadow()
            .readbacksChecked(b) - checkedB;
        run.check(
            checked2 == frames && checked2B == frames,
            phase + ": " + checked2 + " and " + checked2B + " readbacks checked, " + frames + " of each expected");
        long copyFinishes2 = backend.copyFinishes() - copyFinishes;
        run.check(
            yWaits == CYCLES && copyFinishes2 == (waits ? frames : 0),
            phase + ": the copy's glFinish ran as the rule says in "
                + yWaits
                + " of "
                + CYCLES
                + " Y passes and "
                + copyFinishes2
                + " time(s) in all on "
                + renderer
                + ", expected "
                + (waits ? "once in each Y and " + frames + " in all" : "never"));
        tee.release(a);
        tee.release(b);
        host.pumpFrame();
        run.close(host);
        run.log(
            String.format(
                Locale.ROOT,
                "%s: %d and %d readbacks checked, copy glFinish %s (%d calls), %d failure(s); second phase: %d and %d"
                    + " readbacks checked, copy glFinish %d calls (%d of %d Y passes as the rule says), %d failure(s)"
                    + " in its passes and %d in all; fetch glFinish %d calls in all",
                where,
                checked,
                checkedB,
                backend.copyFinish() ? "on" : "off",
                copyFinishes,
                failures,
                checked2,
                checked2B,
                copyFinishes2,
                yWaits,
                CYCLES,
                passFailures,
                run.failures()
                    .size() - phaseStart,
                backend.readbackFinishes()));
    }

    /** The device's last readback is e1 #frameId. */
    private static void checkLast(ScenarioRun run, String where, DeviceKey key, Deliveries d, long frameId) {
        Deliveries.Delivery last = d.last();
        run.check(
            last != null && last.epoch == 1 && last.frameId == frameId,
            where + ": " + key + "'s last readback is " + last + ", expected e1 #" + frameId);
    }

    /** Fills of (0, 0, w, h), each in a colour of the frame's own, so a readback of the frame before it differs. */
    private static FrameRecord frame(long k, int w, int h, int fills) {
        byte[][] records = new byte[fills][];
        for (int i = 0; i < fills; i++) {
            records[i] = TestEncoder.fill(0, 0, w, h, (int) (k * 7 + i * 13) & 0xFF);
        }
        return new FrameRecord(1, k, new byte[][] { TestEncoder.segment(records) }, FrameTotals.EMPTY);
    }
}
