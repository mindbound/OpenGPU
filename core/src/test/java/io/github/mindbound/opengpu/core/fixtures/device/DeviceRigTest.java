package io.github.mindbound.opengpu.core.fixtures.device;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.device.DeviceCaps;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FakeRenderHost;
import io.github.mindbound.opengpu.core.render.NoRenderHost;
import io.github.mindbound.opengpu.core.render.ReadbackSink;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.RenderHost;

/**
 * The test rig (S7 plan §2.3; B14) and the pieces it wires: the worked frame (28 §9.4) through the rig's helpers onto
 * the oracle host, with its charges (28 §8.2) and its {@code done} status; the helpers' failures; the
 * {@link NoRenderHost} rig (28:204) and any host with its own pump and executor; {@code screen()}'s bands under small
 * readback caps; and the {@link ManualClock}, {@link ManualExecutor} and {@link RecordingListener} on their own and
 * against a device's {@code dropped} status (28:112).
 */
class DeviceRigTest {

    private static final int TEX = 0x08000000;

    @Test
    void tierTwoRunsTheWorkedFrameOnTheOracleHost() {
        DeviceRig rig = DeviceRig.tier(2);
        Assertions.assertEquals(new DeviceKey(1, 1), rig.key);
        Assertions.assertSame(rig.host, rig.renderHost);
        Assertions.assertSame(rig.renderer, rig.host.renderer());
        Assertions.assertEquals(DeviceCaps.forTier(2), rig.device.caps());
        // 28:199: the device opens its key on the host at creation
        Assertions.assertTrue(rig.host.isOpen(rig.key));
        Assertions.assertEquals(Arrays.asList("open DeviceKey[1:1]"), rig.host.log());
        Assertions.assertTrue(rig.renderer.isOpen(rig.key));

        byte[] sprite = new byte[256];
        for (int i = 0; i < sprite.length; i++) {
            sprite[i] = (byte) (i % 5);
        }
        byte[] s = segment(
            clear(0),
            fill(10, 20, 100, 50, 196),
            blit(TEX, 0, 0, 16, 16, 150, 40, 1),
            text(8, 180, 255, 0, 1, 0, "Hi"));
        Assertions.assertEquals(TEX, rig.create(16, 16));
        rig.update(TEX, 0, 0, 16, 16, sprite);
        rig.submit(s);
        Assertions.assertEquals(1L, rig.present());

        // 28:840: offered TEX_CREATE, TEX_UPDATE, FRAME(1); the tail holds FRAME(1) only
        Assertions.assertEquals(
            Arrays.asList(
                "TEX_CREATE e=1 tex=0x08000000 size=16x16 format=1",
                "TEX_UPDATE e=1 tex=0x08000000 at=0,0 size=16x16 len=256 crc=1E8F899B",
                "FRAME e=1 id=1 segs=1 bytes=64 records=4 clear=64000 fill=5000 copy=256 set=156 crc=81B9385E"),
            rig.offeredText());
        Assertions.assertEquals(
            Arrays
                .asList("FRAME e=1 id=1 segs=1 bytes=64 records=4 clear=64000 fill=5000 copy=256 set=156 crc=81B9385E"),
            rig.tailText());
        // 28 §8.2 at T2: 256 B / 256 KB twice (plain: c <= k = 1), 1/64 + 64/1 MB, 1/16
        Assertions.assertEquals(
            Arrays.asList("consume 0.000976563", "consume 0.000976563", "consume 0.015686035", "consume 0.062500000"),
            rig.budget.events());
        Assertions.assertEquals(Collections.emptyList(), rig.listener.events());

        rig.step();
        Assertions.assertEquals(Arrays.asList("done 1"), rig.listener.events());
        Assertions.assertEquals(1L, rig.snapshot().confirmedFrameId);
        Assertions.assertTrue(rig.snapshot().tail.isEmpty());
        Assertions.assertEquals(Collections.emptyList(), rig.tailText());

        byte[] screen = rig.screen();
        Assertions.assertArrayEquals(
            rig.hostOracle()
                .pixels(),
            screen);
        // by hand: the clear; FILL_RECT's 196 at (10, 20); the sprite's texel (2, 1) = 18 mod 5 = 3 at (152, 41) and
        // its texel (0, 0) = 0 skipped by KEY0, leaving the clear at (150, 40); 'H' (0x48) row 0 is
        // (7·72) mod 256 AND 0xFC = 0xF8, so columns 0-4 of its cell take fg 255 and column 5 keeps the clear
        Assertions.assertEquals(0, screen[0]);
        Assertions.assertEquals(196, screen[20 * 320 + 10] & 0xFF);
        Assertions.assertEquals(3, screen[41 * 320 + 152]);
        Assertions.assertEquals(0, screen[40 * 320 + 150]);
        Assertions.assertEquals(255, screen[180 * 320 + 8] & 0xFF);
        Assertions.assertEquals(255, screen[180 * 320 + 12] & 0xFF);
        Assertions.assertEquals(0, screen[180 * 320 + 13]);
        // screen() charges a budget of its own
        Assertions.assertEquals(
            4,
            rig.budget.events()
                .size());
    }

    @Test
    void theHelpersThrowTheCallAndItsMessage() {
        DeviceRig rig = DeviceRig.tier(2);
        assertFails("createTexture(0, 1): invalid texture size", () -> rig.create(0, 1));
        Assertions.assertEquals(TEX, rig.create(16, 16));
        assertFails(
            "updateTexture(0x08000000, 0, 0, 16, 16, 255 B): data must be 256 bytes",
            () -> rig.update(TEX, 0, 0, 16, 16, new byte[255]));
        assertFails("free(0x08010000): handle 0x08010000 is not live", () -> rig.free(0x08010000));
        assertFails(
            "submit(0 B): 0: header: segment of 0 bytes is shorter than the 8-byte header",
            () -> rig.submit(new byte[0]));
        // 28:98: "busy" at the third unconfirmed frame
        Assertions.assertEquals(1L, rig.present());
        Assertions.assertEquals(2L, rig.present());
        assertFails("present(): busy", rig::present);
        rig.free(TEX);
        Assertions.assertEquals(
            Arrays.asList("TEX_FREE e=1 tex=0x08000000"),
            rig.tailText()
                .subList(2, 3));
    }

    @Test
    void callGoesThroughOcsRetry() {
        // 28:715-716: a T2 1 024×1 024 create costs 4 > k = 1: LimitReached, then the synchronized retry pauses 0.1 s
        DeviceRig rig = DeviceRig.tier(2);
        Assertions.assertEquals(TEX, rig.create(1024, 1024));
        Assertions.assertEquals(Arrays.asList("limit", "pause 0.100000000"), rig.budget.events());
        Assertions.assertFalse(rig.budget.isSynchronized());
    }

    @Test
    void aNoRendererRigHasNoHostToInspectAndItsStepDoesNothing() {
        DeviceRig rig = DeviceRig.noRenderer(2);
        Assertions.assertSame(NoRenderHost.INSTANCE, rig.renderHost);
        Assertions.assertNull(rig.host);
        Assertions.assertNull(rig.renderer);
        rig.step();
        Assertions.assertEquals(TEX, rig.create(16, 16));
        rig.submit(segment(clear(7)));
        // 28:204: present returns "no renderer", and readPixels the cleared frame 0
        assertFails("present(): no renderer", rig::present);
        rig.step();
        Assertions.assertArrayEquals(new byte[64000], rig.screen());
        Assertions.assertEquals(0L, rig.snapshot().confirmedFrameId);
        Assertions.assertEquals(Collections.emptyList(), rig.tailText());
        assertFails("NoRenderHost keeps no offered records", rig::offeredText);
        assertFails("NoRenderHost runs no OracleRecordRenderer", rig::hostOracle);

        DeviceRig small = DeviceRig.noRenderer(smallT3(512));
        Assertions.assertEquals(smallT3(512), small.device.caps());
        Assertions.assertArrayEquals(new byte[384], small.screen());
    }

    @Test
    void onTakesAnyHostWithItsOwnPumpAndExecutor() {
        // a FakeRenderHost on the zeros renderer: the host is inspectable, the renderer is no oracle
        FakeRenderHost zeros = new FakeRenderHost();
        int[] pumped = { 0 };
        DeviceRig rig = DeviceRig.on(DeviceCaps.forTier(3), zeros, () -> {
            pumped[0]++;
            zeros.step();
        });
        Assertions.assertSame(zeros, rig.host);
        Assertions.assertNull(rig.renderer);
        rig.submit(segment(clear(0xFFFFFFFF)));
        Assertions.assertEquals(1L, rig.present());
        rig.step();
        Assertions.assertEquals(1, pumped[0]);
        Assertions.assertEquals(1L, rig.snapshot().confirmedFrameId);
        // 23:165: the zeros renderer keeps the surface zeroed whatever ran
        Assertions.assertArrayEquals(new byte[512000], rig.screen());
        Assertions.assertEquals(
            1,
            rig.offeredText()
                .size());
        assertFails("FakeRenderHost[RecordRenderer.zeros()] runs no OracleRecordRenderer", rig::hostOracle);

        // a fold executor of the test's: the readback queues the fold until the test runs it
        ManualExecutor folds = new ManualExecutor();
        FakeRenderHost oracleHost = new FakeRenderHost(new OracleRecordRenderer());
        DeviceRig manual = DeviceRig.on(DeviceCaps.forTier(2), oracleHost, oracleHost::step, folds);
        Assertions.assertSame(folds, manual.folds);
        Assertions.assertNotNull(manual.renderer);
        Assertions.assertEquals(1L, manual.present());
        manual.step();
        Assertions.assertEquals(1, folds.pending());
        Assertions.assertEquals(0L, manual.snapshot().confirmedFrameId);
        Assertions.assertEquals(Collections.emptyList(), manual.listener.events());
        Assertions.assertEquals(1, folds.runAll());
        Assertions.assertEquals(1L, manual.snapshot().confirmedFrameId);
        Assertions.assertEquals(Arrays.asList("done 1"), manual.listener.events());

        // any other host: nothing to inspect
        List<Record> offers = new ArrayList<>();
        DeviceRig plain = DeviceRig.on(DeviceCaps.forTier(2), new ListHost(offers), () -> {});
        Assertions.assertNull(plain.host);
        Assertions.assertNull(plain.renderer);
        Assertions.assertEquals(TEX, plain.create(1, 1));
        Assertions.assertEquals(1, offers.size());
        assertFails("ListHost keeps no offered records", plain::offeredText);

        Assertions.assertThrows(NullPointerException.class, () -> DeviceRig.on(DeviceCaps.forTier(2), null, () -> {}));
        Assertions.assertThrows(
            NullPointerException.class,
            () -> DeviceRig.on(DeviceCaps.forTier(2), new FakeRenderHost(), null));
    }

    /**
     * {@code screen()} on T3-format caps of 16×12 (a row is 32 B): pieces of 3 px at a 6-byte cap and of 1 px at 3
     * bytes, one row per read at 32, three rows per read at 100 (four bands), the whole surface at 512.
     */
    @ParameterizedTest(name = "maxReadbackBytes {0}")
    @ValueSource(ints = { 2, 3, 6, 32, 33, 100, 384, 512 })
    void screenReadsInBandsThatFitTheReadbackCap(int maxReadbackBytes) {
        DeviceRig rig = DeviceRig.of(smallT3(maxReadbackBytes));
        // red 0xF800 in [0, 5) × [0, 7), then blue 0x001F in [5, 16) × [3, 12) on black (28:495's sv)
        rig.submit(segment(clear(0xFF000000), fill(0, 0, 5, 7, 0xFFFF0000), fill(5, 3, 11, 9, 0xFF0000FF)));
        Assertions.assertEquals(1L, rig.present());
        rig.step();
        rig.budget.clearEvents();
        byte[] screen = rig.screen();
        byte[] expected = new byte[384];
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 16; x++) {
                int v = x >= 5 && y >= 3 ? 0x001F : x < 5 && y < 7 ? 0xF800 : 0;
                expected[2 * (y * 16 + x)] = (byte) v;
                expected[2 * (y * 16 + x) + 1] = (byte) (v >>> 8);
            }
        }
        Assertions.assertArrayEquals(expected, screen);
        Assertions.assertArrayEquals(
            rig.hostOracle()
                .pixels(),
            screen);
        Assertions.assertEquals(Collections.emptyList(), rig.budget.events());
    }

    @Test
    void screenNeedsOnePixelToFitTheReadbackCap() {
        DeviceRig rig = DeviceRig.of(smallT3(1));
        assertFails("a readback of 1 B holds no pixel of 2 B (T3 16x12 rgb565)", rig::screen);
    }

    @Test
    void theManualClockDrivesTheDroppedStatus() {
        // 28:112: "dropped" when still unconfirmed 1 s after present, once; the next "done" covers the frame
        DeviceRig rig = DeviceRig.tier(2);
        Assertions.assertEquals(0L, rig.clock.getAsLong());
        Assertions.assertEquals(1L, rig.present());
        rig.clock.advanceMillis(999);
        Assertions.assertEquals(999_000_000L, rig.clock.getAsLong());
        rig.device.tick();
        Assertions.assertEquals(Collections.emptyList(), rig.listener.events());
        rig.clock.advanceNanos(1_000_000);
        rig.device.tick();
        rig.device.tick();
        Assertions.assertEquals(Arrays.asList("dropped 1"), rig.listener.events());
        rig.step();
        Assertions.assertEquals(Arrays.asList("dropped 1", "done 1"), rig.listener.events());
        rig.listener.clear();
        Assertions.assertEquals(Collections.emptyList(), rig.listener.events());

        Assertions.assertThrows(IllegalArgumentException.class, () -> rig.clock.advanceNanos(-1));
        Assertions.assertThrows(IllegalArgumentException.class, () -> rig.clock.advanceMillis(-1));
        Assertions.assertEquals(1_000_000_000L, rig.clock.getAsLong());
    }

    @Test
    void theManualExecutorRunsWhatIsAskedInTheOrderAsked() {
        ManualExecutor executor = new ManualExecutor();
        List<String> ran = new ArrayList<>();
        executor.execute(() -> ran.add("a"));
        executor.execute(() -> ran.add("b"));
        executor.execute(() -> ran.add("c"));
        Assertions.assertEquals(3, executor.pending());
        executor.runLast();
        executor.runNext();
        Assertions.assertEquals(Arrays.asList("c", "a"), ran);
        Assertions.assertEquals(1, executor.runAll());
        Assertions.assertEquals(Arrays.asList("c", "a", "b"), ran);
        Assertions.assertEquals(0, executor.pending());
        Assertions.assertEquals(
            "no task is queued",
            Assertions.assertThrows(IllegalStateException.class, executor::runNext)
                .getMessage());
        Assertions.assertThrows(IllegalStateException.class, executor::runLast);

        // runAll also runs what a task queues
        executor.execute(() -> executor.execute(() -> ran.add("e")));
        Assertions.assertEquals(2, executor.runAll());
        Assertions.assertEquals("e", ran.get(3));

        // a pool that shuts down: execute throws and queues nothing; queued tasks stay
        executor.execute(() -> ran.add("f"));
        executor.setRejecting(true);
        Assertions.assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> ran.add("g")));
        Assertions.assertEquals(1, executor.pending());
        executor.setRejecting(false);
        executor.runAll();
        Assertions.assertEquals(Arrays.asList("c", "a", "b", "e", "f"), ran);
        Assertions.assertThrows(NullPointerException.class, () -> executor.execute(null));
    }

    @Test
    void theRecordingListenerRecordsInCallOrder() {
        RecordingListener listener = new RecordingListener();
        listener.frameDone(3);
        listener.frameDropped(4);
        listener.frameDone(4);
        List<String> events = listener.events();
        Assertions.assertEquals(Arrays.asList("done 3", "dropped 4", "done 4"), events);
        events.clear();
        Assertions.assertEquals(
            3,
            listener.events()
                .size());
        listener.clear();
        Assertions.assertEquals(Collections.emptyList(), listener.events());
    }

    /** The small T3 twin of the plan's §4 caps, with the given readback cap. */
    private static DeviceCaps smallT3(int maxReadbackBytes) {
        return new DeviceCaps(
            new SurfaceDesc(3, 16, 12, Formats.RGB565),
            new FrameLimits(65536, 512, 24, 4000),
            4096,
            6,
            32,
            maxReadbackBytes,
            2);
    }

    private static void assertFails(String message, Runnable call) {
        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class, call::run);
        Assertions.assertEquals(message, e.getMessage());
    }

    /** A host that only lists what it is offered. */
    private static final class ListHost implements RenderHost {

        private final List<Record> offers;

        ListHost(List<Record> offers) {
            this.offers = offers;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public void open(DeviceKey key, SurfaceDesc surface, ReadbackSink sink) {}

        @Override
        public void offer(DeviceKey key, Record record) {
            offers.add(record);
        }

        @Override
        public void release(DeviceKey key) {}

        @Override
        public void bindDisplay(String displayId, DeviceKey key) {}

        @Override
        public void releaseSession(long session) {}

        @Override
        public String toString() {
            return "ListHost";
        }
    }
}
