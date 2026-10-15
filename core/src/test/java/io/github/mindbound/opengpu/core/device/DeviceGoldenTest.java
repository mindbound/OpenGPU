package io.github.mindbound.opengpu.core.device;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.golden.GoldenCases;
import io.github.mindbound.opengpu.core.fixtures.golden.GoldenRunner;

/**
 * 28 §11.4's hand-computed vectors through the whole server path: every case of {@code oracle-goldens.txt} runs as
 * calls on a {@code Device} on {@code FakeRenderHost} with the tier-E oracle, and the case's grids are compared with
 * {@code readPixels} of the whole surface, the confirmed frame K after the host's readback (28:99). A case's textures
 * are {@code createTexture} calls, whose handle must be the file's (28 §6.1's FIFO), then {@code updateTexture};
 * {@code free} is {@code free}; a frame is a {@code submit} per segment, {@code present} and a step. The device runs
 * with the goldens' permissive frame caps ({@link GoldenRunner#LIMITS}) on a surface of the case's size, T2's charges
 * for index8 and T3's for rgb565. The one case with a {@code reset} step, {@code reset-then-frame}, is not run here:
 * its {@code RESET} carries arbitrary pixels, while a device only ever offers K's (28:150), so no call sequence
 * produces it; {@link #onlyResetThenFrameHasAResetStep} pins that exclusion.
 */
class DeviceGoldenTest {

    private static final GoldenCases GOLDENS = GoldenCases.load();
    private static final String RESET_CASE = "reset-then-frame";

    static Stream<GoldenCases.Case> casesWithoutReset() {
        List<GoldenCases.Case> cases = new ArrayList<>();
        for (GoldenCases.Case c : GOLDENS.cases()) {
            if (!hasReset(c)) {
                cases.add(c);
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("casesWithoutReset")
    void theCaseHoldsThroughTheDevice(GoldenCases.Case c) {
        SurfaceDesc surface = new SurfaceDesc(c.format() == Formats.INDEX8 ? 2 : 3, c.width(), c.height(), c.format());
        DeviceCaps caps = new DeviceCaps(surface, GoldenRunner.LIMITS, 4194304, 256, 1024, 1 << 20, 2);
        DeviceRig rig = DeviceRig.of(caps);
        Invariants.History history = Invariants.History.of(rig.device);
        GoldenRunner.Result result = GoldenRunner.run(c, new DeviceTarget(rig, history));
        if (!result.passed()) {
            System.out.println(result.report());
            Assertions.fail(result.report(), result.cause());
        }
        DeviceOrderingTest.check(rig, history);
    }

    @Test
    void onlyResetThenFrameHasAResetStep() {
        List<String> withReset = new ArrayList<>();
        for (GoldenCases.Case c : GOLDENS.cases()) {
            if (hasReset(c)) {
                withReset.add(c.name());
            }
        }
        Assertions.assertEquals(Collections.singletonList(RESET_CASE), withReset);
        Assertions.assertEquals(45, casesWithoutReset().count(), "46 goldens (OracleGoldenTest) less the reset case");
    }

    private static boolean hasReset(GoldenCases.Case c) {
        for (GoldenCases.Step s : c.steps()) {
            if (s.kind() == GoldenCases.Kind.RESET) {
                return true;
            }
        }
        return false;
    }

    /** A golden case's records as device calls, through the rig's helpers (each asserts success). */
    private static final class DeviceTarget implements GoldenRunner.Target {

        private final DeviceRig rig;
        private final Invariants.History history;

        DeviceTarget(DeviceRig rig, Invariants.History history) {
            this.rig = rig;
            this.history = history;
        }

        @Override
        public void createTexture(int handle, int width, int height, int format) {
            Assertions.assertEquals(rig.device.surface().format, format);
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
            rig.step();
            DeviceSnapshot s = DeviceOrderingTest.check(rig, history);
            Assertions.assertEquals(id, s.confirmedFrameId);
        }

        @Override
        public byte[] pixels() {
            return rig.screen();
        }
    }
}
