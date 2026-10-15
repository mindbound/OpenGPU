package io.github.mindbound.opengpu.core.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentest4j.AssertionFailedError;

import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.TexFreeRecord;

/**
 * Dev-build integrity (28:202; 21:128; H16; plan §3.12): with Java assertions on, as in every Gradle test task, a
 * segment written after its {@code submit} fails the {@code present} that would seal it, and after the present the
 * host's run; a payload written after {@code updateTexture} fails the host's run and the fold that would apply it to
 * the masters. The worked frame raises no false alarm. The last two tests show that {@link Invariants}' checks are not
 * vacuous: a snapshot that breaks I1, I3, I5 or I12 and a history the replay does not match each fail.
 */
class DeviceIntegrityTest {

    private static final int T = 0x08000000;

    @BeforeAll
    static void devBuild() {
        Assertions.assertTrue(Record.devChecks(), "the tests run with assertions on (28:202); these need them");
    }

    @Test
    void aSegmentWrittenAfterItsSubmitFailsThePresent() {
        DeviceRig rig = DeviceRig.tier(2);
        byte[] s = segment(clear(3));
        rig.submit(s);
        s[9] = 4;
        Assertions.assertThrows(AssertionError.class, () -> rig.device.present(rig.budget));
        Assertions.assertTrue(
            rig.host.offered(rig.key)
                .isEmpty(),
            "nothing offered");
        // written after the present instead: the host's run catches it
        DeviceRig late = DeviceRig.tier(2);
        byte[] s2 = segment(clear(3));
        late.submit(s2);
        late.present();
        FrameRecord frame = (FrameRecord) late.host.offered(late.key)
            .get(0);
        Assertions.assertTrue(frame.intact());
        s2[9] = 4;
        Assertions.assertFalse(frame.intact());
        Assertions.assertThrows(AssertionError.class, () -> late.host.run(late.key, Integer.MAX_VALUE));
    }

    @Test
    void aPayloadWrittenAfterUpdateTextureFailsTheHostsRun() {
        DeviceRig rig = DeviceRig.tier(2);
        Assertions.assertEquals(T, rig.create(4, 4));
        rig.present();
        byte[] p = DeviceOrderingTest.pattern(16, 1);
        rig.update(T, 0, 0, 4, 4, p);
        List<Record> offered = rig.host.offered(rig.key);
        Record update = offered.get(offered.size() - 1);
        Assertions.assertTrue(update.intact());
        p[0]++;
        Assertions.assertFalse(update.intact());
        Assertions.assertThrows(AssertionError.class, () -> rig.host.run(rig.key, Integer.MAX_VALUE));
    }

    @Test
    void aPayloadWrittenAfterUpdateTextureFailsTheFold() {
        DeviceRig rig = DeviceRig.tier(2);
        Assertions.assertEquals(T, rig.create(4, 4));
        rig.present();
        byte[] p = DeviceOrderingTest.pattern(16, 2);
        rig.update(T, 0, 0, 4, 4, p);
        p[15]++;
        // readback 1 folds FRAME 1, then the TEX_UPDATE at the head; with the direct executor the assertion propagates
        Assertions.assertThrows(AssertionError.class, () -> rig.device.onReadback(1, 1, 1, 1, new byte[320 * 200]));
    }

    @ParameterizedTest
    @ValueSource(ints = { 2, 3 })
    void theWorkedFrameRaisesNoFalseAlarm(int tier) {
        DeviceRig rig = DeviceRig.tier(tier);
        Invariants.History history = Invariants.History.of(rig.device);
        rig.create(16, 16);
        rig.update(T, 0, 0, 16, 16, DeviceOrderingTest.pattern(tier == 2 ? 256 : 512, 3));
        rig.submit(DeviceOrderingTest.workedSegment(tier));
        rig.present();
        rig.step();
        rig.submit(DeviceOrderingTest.workedSegment(tier));
        rig.free(T);
        rig.present();
        rig.step();
        DeviceSnapshot s = DeviceOrderingTest.check(rig, history);
        Assertions.assertEquals(2, s.confirmedFrameId);
        for (Record r : rig.host.offered(rig.key)) {
            Assertions.assertTrue(r.intact(), r::toString);
        }
        Assertions.assertEquals(
            rig.host.offered(rig.key)
                .size(),
            rig.host.ran(rig.key)
                .size());
    }

    /** Invariants' snapshot checks fail on a snapshot that breaks them. */
    @Test
    void theInvariantsCatchABrokenSnapshot() {
        DeviceRig rig = DeviceRig.tier(2);
        rig.create(16, 16);
        rig.present();
        rig.create(8, 8);
        rig.submit(segment(clear(1)));
        DeviceCaps caps = rig.device.caps();
        DeviceSnapshot s = Invariants.check(rig.snapshot(), caps);
        // I1: a tail that starts with a resource record
        List<Record> tail = new ArrayList<>(s.tail);
        tail.add(0, new TexFreeRecord(1, T));
        assertCaught("I1", () -> Invariants.check(copy(s, tail, s.queuedBytes + 64, s.live, s.deferredBytes), caps));
        // I3: queued bytes off by one
        assertCaught("I3", () -> Invariants.check(copy(s, s.tail, s.queuedBytes + 1, s.live, s.deferredBytes), caps));
        // I5: a live handle without its slot's generation
        List<DeviceSnapshot.LiveTexture> live = new ArrayList<>(s.live);
        DeviceSnapshot.LiveTexture first = live.get(0);
        live.set(0, new DeviceSnapshot.LiveTexture(first.handle + 1, first.width, first.height, first.format));
        assertCaught("I5", () -> Invariants.check(copy(s, s.tail, s.queuedBytes, live, s.deferredBytes), caps));
        // I7 and I12: deferred bytes with no open frame, and past the VRAM cap
        assertCaught("I12", () -> Invariants.check(copy(s, s.tail, s.queuedBytes, s.live, caps.vramBytes), caps));
    }

    /** I9 fails when the full history holds a record the device's state does not account for. */
    @Test
    void theReplayCatchesARecordTheDeviceDidNotOffer() {
        DeviceRig rig = DeviceRig.tier(2);
        Invariants.History history = Invariants.History.of(rig.device);
        rig.submit(segment(clear(5)));
        rig.present();
        rig.step();
        DeviceSnapshot s = DeviceOrderingTest.check(rig, history);
        List<Record> more = new ArrayList<>(rig.host.offered(rig.key));
        more.add(new FrameRecord(1, 2, new byte[][] { segment(clear(6)) }, FrameTotals.EMPTY));
        history.feedNew(more);
        AssertionFailedError e = Assertions
            .assertThrows(AssertionFailedError.class, () -> Invariants.checkReplay(s, history));
        Assertions.assertTrue(
            e.getMessage()
                .startsWith("I9: "),
            e.getMessage());
        // and I8 sees the frame the device never presented
        Assertions.assertThrows(AssertionFailedError.class, () -> Invariants.checkEpochs(s, history));
        // a history fed a record of another epoch fails at once
        Invariants.History other = Invariants.History.of(rig.device);
        AssertionFailedError epoch = Assertions.assertThrows(
            AssertionFailedError.class,
            () -> other.feedNew(Collections.singletonList(new TexFreeRecord(2, T))));
        Assertions.assertTrue(
            epoch.getMessage()
                .startsWith("I8: "),
            epoch.getMessage());
    }

    private static DeviceSnapshot copy(DeviceSnapshot s, List<Record> tail, long queuedBytes,
        List<DeviceSnapshot.LiveTexture> live, long deferredBytes) {
        return new DeviceSnapshot(
            s.key,
            s.epoch,
            s.released,
            s.lastFrameId,
            s.confirmedFrameId,
            s.confirmedPixels(),
            tail,
            s.unconfirmedFrames,
            queuedBytes,
            s.vramUsed,
            live,
            s.masters,
            s.freeSlots(),
            s.generations(),
            s.openSegments,
            s.openTotals,
            s.deferredFrees,
            deferredBytes,
            s.budgetExhausted,
            s.staleReadbacks,
            s.rejectedReadbacks);
    }

    private static void assertCaught(String invariant, Executable check) {
        AssertionFailedError e = Assertions.assertThrows(AssertionFailedError.class, check);
        Assertions.assertTrue(
            e.getMessage()
                .startsWith(invariant + ": "),
            "expected " + invariant + ", got " + e.getMessage());
    }
}
