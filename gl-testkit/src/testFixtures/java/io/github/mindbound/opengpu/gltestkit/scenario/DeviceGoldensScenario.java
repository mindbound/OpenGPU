package io.github.mindbound.opengpu.gltestkit.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.device.DeviceCaps;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.TeeRenderHost;
import io.github.mindbound.opengpu.core.fixtures.golden.GoldenCases;
import io.github.mindbound.opengpu.core.fixtures.golden.GoldenRunner;
import io.github.mindbound.opengpu.core.render.FakeRenderHost;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gltestkit.host.GlRecordRenderer;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * Scenario 12 (S8 plan §4.3; 28:1086, :936): the 45 goldens without a {@code RESET} step through the whole server path
 * on GL, as S7's {@code DeviceGoldenTest} runs them on the oracle: each case's textures as {@code createTexture} calls,
 * whose handle must be the case's (28 §6.1's FIFO), then {@code updateTexture}; {@code free}; a frame as a
 * {@code submit} per segment and {@code present}; and the case's grids compared with {@code readPixels} of the whole
 * surface (28:99) once K is the frame. Two hosts, in both quad modes:
 * <ul>
 * <li>{@link GlTestHost}, pumped until K is the frame id, every readback also checked by a ShadowOracle through a tee
 * (S8 amendments E);</li>
 * <li>{@code FakeRenderHost(GlRecordRenderer)} (28:205; H54), stepped once a frame.</li>
 * </ul>
 * A device runs with the goldens' permissive frame caps on a surface of the case's size, T2's charges for index8 and
 * T3's for rgb565, on DeviceRig's key; after each case the device is released, and one pump (or the release itself, on
 * the renderer) leaves no ledger row of the key (24:218). {@code reset-then-frame} is left out: a device's
 * {@code RESET} only carries K's pixels (28:150), so no call sequence produces its arbitrary ones. The count is the
 * cases per host and mode, in file order: all 45 on every leg by default ({@code -Popengpu.gl.cases} caps it).
 */
public final class DeviceGoldensScenario extends Scenario {

    /** 46 goldens (28:933) less {@code reset-then-frame}. */
    static final int CASES = 45;

    @Override
    protected void run(ScenarioRun run) {
        List<GoldenCases.Case> cases = casesWithoutReset();
        run.check(cases.size() == CASES, CASES + " goldens without a RESET step, got " + cases.size());
        int count = Math.min(cases.size(), run.cases(CASES, CASES));
        List<GoldenCases.Case> chosen = cases.subList(0, count);
        for (QuadMode mode : QuadMode.values()) {
            int passed = onGlTestHost(run, mode, chosen);
            run.log(mode + ": " + passed + "/" + count + " goldens through Device on GlTestHost");
            passed = onRecordRenderer(run, mode, chosen);
            run.log(mode + ": " + passed + "/" + count + " goldens through Device on FakeRenderHost(GlRecordRenderer)");
        }
    }

    private static int onGlTestHost(ScenarioRun run, QuadMode mode, List<GoldenCases.Case> cases) {
        final GlTestHost host = run.host(mode);
        TeeRenderHost tee = run.tee(host);
        int passed = 0;
        for (GoldenCases.Case c : cases) {
            String where = mode + " GlTestHost " + c.name();
            DeviceRig rig = DeviceRig.on(caps(c), tee, () -> host.pumpFrame());
            GoldenRunner.Result result = GoldenRunner.run(c, new DeviceTarget(rig, host));
            if (result.passed()) {
                passed++;
            } else {
                run.fail(mode + " GlTestHost " + result.report() + describeCause(result));
            }
            rig.device.release();
            host.pumpFrame();
            run.check(
                host.ledger()
                    .rows(rig.key.session, rig.key.deviceId)
                    .isEmpty(),
                where + ": ledger rows after the release and a pump: "
                    + host.ledger()
                        .rows(rig.key.session, rig.key.deviceId));
        }
        run.close(host);
        return passed;
    }

    private static int onRecordRenderer(ScenarioRun run, QuadMode mode, List<GoldenCases.Case> cases) {
        GlRecordRenderer renderer = run.renderer(mode);
        final FakeRenderHost fake = new FakeRenderHost(renderer);
        fake.setKeepHistory(false);
        int passed = 0;
        for (GoldenCases.Case c : cases) {
            String where = mode + " FakeRenderHost(GlRecordRenderer) " + c.name();
            DeviceRig rig = DeviceRig.on(caps(c), fake, () -> fake.step());
            GoldenRunner.Result result = GoldenRunner.run(c, new DeviceTarget(rig, null));
            if (result.passed()) {
                passed++;
            } else {
                run.fail(mode + " FakeRenderHost(GlRecordRenderer) " + result.report() + describeCause(result));
            }
            rig.device.release();
            run.check(
                renderer.ledger()
                    .rows(rig.key.session, rig.key.deviceId)
                    .isEmpty(),
                where + ": ledger rows after the release: "
                    + renderer.ledger()
                        .rows(rig.key.session, rig.key.deviceId));
        }
        run.close(renderer);
        return passed;
    }

    /** DeviceGoldenTest's caps: the case's surface and the goldens' frame caps, roomy device caps. */
    static DeviceCaps caps(GoldenCases.Case c) {
        SurfaceDesc surface = new SurfaceDesc(c.format() == Formats.INDEX8 ? 2 : 3, c.width(), c.height(), c.format());
        return new DeviceCaps(surface, GoldenRunner.LIMITS, 4194304, 256, 1024, 1 << 20, 2);
    }

    /** The goldens but {@code reset-then-frame}, in file order. */
    static List<GoldenCases.Case> casesWithoutReset() {
        List<GoldenCases.Case> out = new ArrayList<>();
        for (GoldenCases.Case c : GoldenCases.load()
            .cases()) {
            boolean reset = false;
            for (GoldenCases.Step s : c.steps()) {
                reset |= s.kind() == GoldenCases.Kind.RESET;
            }
            if (!reset) {
                out.add(c);
            }
        }
        return out;
    }

    private static String describeCause(GoldenRunner.Result result) {
        return result.cause() == null ? "" : "\n    caused by " + Scenarios.describe(result.cause());
    }

    /**
     * A golden case's records as device calls through the rig's helpers, each of which expects success. A frame is
     * followed by the host's pumps until K is its id, or by one step of the FakeRenderHost (host null).
     */
    private static final class DeviceTarget implements GoldenRunner.Target {

        private final DeviceRig rig;
        private final GlTestHost host;

        DeviceTarget(DeviceRig rig, GlTestHost host) {
            this.rig = rig;
            this.host = host;
        }

        @Override
        public void createTexture(int handle, int width, int height, int format) {
            if (format != rig.device.surface().format) {
                throw new IllegalStateException("a " + Formats.name(format) + " texture on " + rig.device.surface());
            }
            int got = rig.create(width, height);
            if (got != handle) {
                throw new IllegalStateException(
                    String.format(Locale.ROOT, "createTexture gave 0x%08X, the case says 0x%08X", got, handle));
            }
        }

        @Override
        public void updateTexture(int handle, int x, int y, int width, int height, byte[] payload) {
            rig.update(handle, x, y, width, height, payload);
        }

        @Override
        public void freeTexture(int handle) {
            rig.free(handle);
        }

        @Override
        public void reset(byte[] pixels) {
            throw new IllegalStateException("a device offers RESET with K's pixels only (28:150)");
        }

        @Override
        public void runFrame(byte[][] segments) {
            for (byte[] segment : segments) {
                rig.submit(segment);
            }
            long id = rig.present();
            if (host != null) {
                DeviceSteps.pumpUntilConfirmed(host, rig, id, rig.toString());
            } else {
                rig.step();
            }
            long k = rig.snapshot().confirmedFrameId;
            if (k != id) {
                throw new IllegalStateException("K is " + k + " after frame " + id + "'s readback");
            }
        }

        @Override
        public byte[] pixels() {
            return rig.screen();
        }
    }
}
