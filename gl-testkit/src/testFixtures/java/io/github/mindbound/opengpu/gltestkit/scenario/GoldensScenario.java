package io.github.mindbound.opengpu.gltestkit.scenario;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.fixtures.golden.GoldenCases;
import io.github.mindbound.opengpu.core.fixtures.golden.GoldenRunner;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gltestkit.host.GlRecordRenderer;

/**
 * Scenario 1 (S8 plan §4.3; 28:933, :938): all 46 goldens, {@code reset-then-frame} included, through
 * {@code GoldenRunner} on a {@link GlRecordRenderer} with records of epoch 1, in both quad modes (H39), from the
 * MC-like start. Each case gets a fresh key, and its ledger rows are gone after its release (24:218). Kills plan §4.7's
 * rows for KEY0, {@code TRANSPARENT_BG}, flipped glyph rows, the BLIT offset, a bypassed {@code Clip} and an
 * INSTANCED-only bug.
 */
public final class GoldensScenario extends Scenario {

    /** 28:933's count. */
    static final int GOLDENS = 46;

    @Override
    protected void run(ScenarioRun run) {
        GoldenCases goldens = GoldenCases.load();
        run.check(
            goldens.cases()
                .size() == GOLDENS,
            GOLDENS + " goldens, got "
                + goldens.cases()
                    .size());
        for (QuadMode mode : QuadMode.values()) {
            GlRecordRenderer renderer = run.renderer(mode);
            int passed = 0;
            for (GoldenCases.Case c : goldens.cases()) {
                SurfaceDesc surface;
                try {
                    surface = c.surface();
                } catch (IllegalArgumentException e) {
                    run.fail(mode + " " + c.name() + ": " + e.getMessage());
                    continue;
                }
                DeviceKey key = run.key();
                GlGoldenTarget target = GlGoldenTarget.open(renderer, key, surface);
                GoldenRunner.Result result = GoldenRunner.run(c, target);
                if (result.passed()) {
                    passed++;
                } else {
                    run.fail(mode + " " + result.report());
                }
                target.release();
                run.check(
                    renderer.ledger()
                        .rows(key.session, key.deviceId)
                        .isEmpty(),
                    mode + " "
                        + c.name()
                        + ": ledger rows after the release: "
                        + renderer.ledger()
                            .rows(key.session, key.deviceId));
            }
            run.close(renderer);
            run.log(
                mode + ": "
                    + passed
                    + "/"
                    + goldens.cases()
                        .size()
                    + " goldens passed");
        }
    }
}
