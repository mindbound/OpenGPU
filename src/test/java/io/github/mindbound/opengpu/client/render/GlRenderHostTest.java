package io.github.mindbound.opengpu.client.render;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.colour.Colours;
import io.github.mindbound.opengpu.core.colour.Palette;
import io.github.mindbound.opengpu.core.device.CallResult;
import io.github.mindbound.opengpu.core.device.Device;
import io.github.mindbound.opengpu.core.device.DeviceCaps;
import io.github.mindbound.opengpu.core.device.DeviceSnapshot;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.device.DeviceRig;
import io.github.mindbound.opengpu.core.fixtures.device.ManualClock;
import io.github.mindbound.opengpu.core.fixtures.device.RecordingBudget;
import io.github.mindbound.opengpu.core.fixtures.device.RecordingListener;
import io.github.mindbound.opengpu.core.font.GlyphTables;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.gl.GlBackend;
import io.github.mindbound.opengpu.gl.GlLedger;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.QueryPolicy;
import io.github.mindbound.opengpu.gl.UploadProbe;
import io.github.mindbound.opengpu.gl.host.DeviceBackend;
import io.github.mindbound.opengpu.gl.host.DeviceStats;
import io.github.mindbound.opengpu.gl.host.GlHostCore;
import io.github.mindbound.opengpu.gl.host.HostBackend;
import io.github.mindbound.opengpu.gl.host.HostOptions;
import io.github.mindbound.opengpu.gl.host.PassBudget;
import io.github.mindbound.opengpu.gltestkit.GlTest;
import io.github.mindbound.opengpu.gltestkit.GlThread;
import io.github.mindbound.opengpu.gltestkit.host.GlReads;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * {@link GlRenderHost} on a real context with no Minecraft running (S12 plan P14.3 with amendments I4 and I5), in
 * {@code clientGlTest}'s JVM: every pass and present lookup runs on the {@link GlThread}, the core's GL thread (H57),
 * and every host runs on a {@link ManualClock}, so a slow first pass on llvmpipe never reads as a stall
 * (GlHostCore.java:121-123). Each case closes its host at the end and finds the ledger empty (24:218). No test loads
 * FML or Minecraft, and none installs anything in {@code RenderHosts} (S12 plan P14.1).
 */
@GlTest
class GlRenderHostTest {

    private static final String DISPLAY = "display-1";
    private static final int BACKGROUND = 0x0F;
    private static final int BOX = 0x01;
    private static final int BOX_W = 16, BOX_H = 8;
    /** Passes a frame may take to come back "done": its own, the readback's fetch, at most four more (24:197). */
    private static final int MAX_PUMPS = 64;

    private final ManualClock clock = new ManualClock();
    // The GL thread writes them, the test thread reads them once its GlThread.call has returned
    private final List<String> warn = new CopyOnWriteArrayList<>();
    private final List<String> info = new CopyOnWriteArrayList<>();

    /** The production host on this test's clock and logs, with the context's own renderer as the probed one. */
    private GlRenderHost production(GlThread gl) {
        return GlRenderHost.production(warn::add, info::add, gl.info().renderer, clock);
    }

    private static GlBackend backend(GlRenderHost host) {
        return (GlBackend) host.core()
            .backend();
    }

    /** One pass within no budget, on the GL thread. */
    private static Runnable unlimitedPass(GlThread gl, GlRenderHost host) {
        return () -> on(gl, () -> host.pass(PassBudget.unlimited()));
    }

    private static void pumpUntilAnEvent(DeviceRig rig) {
        for (int pumps = 0; pumps < MAX_PUMPS && rig.listener.events()
            .isEmpty(); pumps++) {
            rig.step();
        }
    }

    /** The surface the tests draw: a box of {@link #BOX} at the top left, {@link #BACKGROUND} elsewhere. */
    private static byte[] boxImage(SurfaceDesc surface) {
        byte[] image = new byte[surface.byteSize()];
        for (int y = 0; y < surface.height; y++) {
            for (int x = 0; x < surface.width; x++) {
                image[y * surface.width + x] = (byte) (x < BOX_W && y < BOX_H ? BOX : BACKGROUND);
            }
        }
        return image;
    }

    private static byte[] boxStream() {
        return TestEncoder.segment(TestEncoder.clear(BACKGROUND), TestEncoder.fill(0, 0, BOX_W, BOX_H, BOX));
    }

    /** Closes the host on the GL thread: the ledger is then empty and no contract was broken (24:218; H47). */
    private static void close(GlThread gl, GlRenderHost host) {
        on(gl, host::close);
        Assertions.assertTrue(
            host.ledger()
                .isEmpty(),
            () -> "ledger after close: " + host.ledger()
                .rows());
        Assertions.assertEquals(
            0,
            host.core()
                .contractViolations());
    }

    // P14.3's first case: one T2 frame through the production host and its 4 ms pass, its "done", readPixels and the
    // present texture, the expanded confirmed image with row 0 the surface's top row (GlBackend.java:364)
    @Test
    void oneT2FrameComesBackDoneAndIsThePresentTexture(GlThread gl) throws Exception {
        GlRenderHost host = production(gl);
        DeviceRig rig = DeviceRig.on(DeviceCaps.forTier(2), host, () -> on(gl, host::pass));
        host.bindDisplay(DISPLAY, rig.key);
        rig.submit(boxStream());
        long frame = rig.present();
        pumpUntilAnEvent(rig);
        Assertions.assertEquals(Collections.singletonList("done " + frame), rig.listener.events());
        SurfaceDesc surface = rig.device.surface();
        byte[] confirmed = rig.screen();
        Assertions.assertArrayEquals(boxImage(surface), confirmed, "readPixels' confirmed image");
        int texture = gl.call(() -> host.presentTexture(DISPLAY));
        Assertions.assertNotEquals(0, texture, "the bound display's present texture");
        byte[] present = gl.call(() -> GlReads.rgba(backend(host), texture, surface));
        Assertions.assertArrayEquals(Colours.expandToRgba(surface, confirmed, Palette.DEFAULT), present);
        Assertions.assertTrue(host.available());
        close(gl, host);
        Assertions.assertEquals(Collections.emptyList(), warn);
    }

    // I4: the factory's options are production's: BOUND_ONLY (H68), a 2 s stall on the given clock, mirrors (Q-S8-1),
    // a forced fetch after 4 passes (24:197); 2 s with no pass reads as stalled, and a pass with no work clears it
    @Test
    void theProductionOptionsAreBoundOnlyATwoSecondStallMirrorsAndAFetchAfterFourPasses(GlThread gl) throws Exception {
        GlRenderHost host = production(gl);
        HostOptions options = host.core()
            .options();
        Assertions.assertEquals(HostOptions.Expand.BOUND_ONLY, options.expand());
        Assertions.assertEquals(2_000_000_000L, options.stallAfterNanos());
        Assertions.assertEquals(GlHostCore.STALL_AFTER_NANOS, options.stallAfterNanos());
        Assertions.assertTrue(options.mirrors());
        Assertions.assertEquals(4, options.forceFetchAfterPasses());
        Assertions.assertSame(clock, options.clock());
        Assertions.assertEquals(QuadMode.EXPANDED, backend(host).mode());
        Assertions.assertEquals(QueryPolicy.DRIVER, backend(host).queryPolicy());
        options.log()
            .accept("the given log");
        Assertions.assertEquals(Collections.singletonList("the given log"), warn);
        warn.clear();
        Assertions.assertFalse(host.stalled(), "a new host");
        clock.advanceNanos(GlHostCore.STALL_AFTER_NANOS);
        Assertions.assertFalse(host.stalled(), "2 s exactly");
        clock.advanceNanos(1);
        Assertions.assertTrue(host.stalled(), "more than 2 s with no pass");
        on(gl, host::pass);
        Assertions.assertFalse(host.stalled(), "a pass with no work moves the heartbeat");
        Assertions.assertEquals(4.0, GlRenderHost.PASS_MILLIS, "the M0 constant pass budget, in ms (24:173)");
        close(gl, host);
        Assertions.assertEquals(Collections.emptyList(), warn);
    }

    // H68 as production uses it: an unbound device reads back, so its frame is done, but its present texture stays as
    // made, the palette's entry 0 everywhere (DeviceGl.java:70), until a bind lets the next pass expand the owed frame
    @Test
    void anUnboundDevicesPresentTextureStaysAsMadeUntilABindExpandsTheOwedFrame(GlThread gl) throws Exception {
        GlRenderHost host = production(gl);
        DeviceRig rig = DeviceRig.on(DeviceCaps.forTier(2), host, unlimitedPass(gl, host));
        rig.submit(boxStream());
        long frame = rig.present();
        pumpUntilAnEvent(rig);
        Assertions.assertEquals(Collections.singletonList("done " + frame), rig.listener.events());
        SurfaceDesc surface = rig.device.surface();
        Assertions.assertEquals(0, (int) gl.call(() -> host.presentTexture(DISPLAY)), "no display is bound");
        byte[] asMade = gl.call(() -> {
            DeviceBackend device = host.core()
                .deviceBackend(rig.key);
            return GlReads.rgba(backend(host), backend(host).presentTexture(device), surface);
        });
        Assertions.assertArrayEquals(
            Colours.expandToRgba(surface, new byte[surface.byteSize()], Palette.DEFAULT),
            asMade,
            "the unbound device's present texture, its expand owed");
        host.bindDisplay(DISPLAY, rig.key);
        rig.step();
        int texture = gl.call(() -> host.presentTexture(DISPLAY));
        Assertions.assertNotEquals(0, texture);
        byte[] present = gl.call(() -> GlReads.rgba(backend(host), texture, surface));
        Assertions.assertArrayEquals(
            Colours.expandToRgba(surface, rig.screen(), Palette.DEFAULT),
            present,
            "the pass after the bind expanded the owed frame");
        close(gl, host);
        Assertions.assertEquals(Collections.emptyList(), warn);
    }

    // P5.5: 0 for a null id, an id no device is bound to, and a released device, before and after step (d) deletes it
    @Test
    void presentTextureIsZeroForANullIdAnUnboundIdAndAReleasedDevice(GlThread gl) throws Exception {
        GlRenderHost host = production(gl);
        DeviceRig rig = DeviceRig.on(DeviceCaps.forTier(2), host, unlimitedPass(gl, host));
        host.bindDisplay(DISPLAY, rig.key);
        rig.step();
        Assertions.assertNotEquals(0, (int) gl.call(() -> host.presentTexture(DISPLAY)), "bound, with GL objects");
        Assertions.assertEquals(0, (int) gl.call(() -> host.presentTexture(null)), "a null id");
        Assertions.assertEquals(0, (int) gl.call(() -> host.presentTexture("display-2")), "an unbound id");
        host.release(rig.key);
        Assertions.assertEquals(0, (int) gl.call(() -> host.presentTexture(DISPLAY)), "released, still bound by id");
        rig.step();
        Assertions.assertEquals(0, (int) gl.call(() -> host.presentTexture(DISPLAY)), "after step (d)");
        close(gl, host);
        Assertions.assertEquals(Collections.emptyList(), warn);
    }

    /**
     * RM:68's quit as the host sees it (S12 plan P7.4; amendments I5, I13): two devices of session 1, made with
     * {@code Device.create} since {@code DeviceRig} always uses key (1, 1), each with two frames presented and their
     * readbacks in flight ({@code QueryPolicy.NEVER_READY} keeps them there), then {@code releaseSession} on another
     * thread, the server thread's stop while the client waits and runs no pass (21:111). It returns at once; the next
     * pass empties the session's ledger rows, its openings and display bindings, no sink call reaches either device,
     * and one release line is logged. {@code close()} comes only at the end, as the game never calls it.
     */
    @Test
    void releasingTheSessionWithTwoFramesInFlightNeitherHangsNorLeaks(GlThread gl) throws Exception {
        GlLedger ledger = new GlLedger();
        GlBackend backend = new GlBackend(
            GlyphTables.font0(),
            QuadMode.EXPANDED,
            ledger,
            UploadProbe.NONE,
            QueryPolicy.NEVER_READY,
            GlTestHost.readbackFinish());
        HostOptions options = HostOptions.defaults()
            .log(warn::add)
            .clock(clock);
        GlRenderHost host = new GlRenderHost(backend, ledger, options, info::add, gl.info().renderer);
        RecordingListener listener = new RecordingListener();
        RecordingBudget budget = new RecordingBudget();
        List<Device> devices = Arrays.asList(
            Device.create(new DeviceKey(1, 1), DeviceCaps.forTier(2), host, Runnable::run, listener, clock),
            Device.create(new DeviceKey(1, 2), DeviceCaps.forTier(2), host, Runnable::run, listener, clock));
        List<String> displays = Arrays.asList("display-1", "display-2");
        for (int i = 0; i < devices.size(); i++) {
            host.bindDisplay(
                displays.get(i),
                devices.get(i)
                    .key());
        }
        for (int frame = 1; frame <= 2; frame++) {
            for (Device device : devices) {
                ok(device.submit(budget, TestEncoder.segment(TestEncoder.clear(frame))));
                ok(device.present(budget));
            }
            on(gl, () -> host.pass(PassBudget.unlimited()));
        }
        GlHostCore core = host.core();
        for (Device device : devices) {
            DeviceStats stats = core.stats(device.key());
            Assertions.assertEquals(2, stats.readbacksInFlight, stats::toString);
            Assertions.assertTrue(ledger.deviceBytes(device.key().session, device.key().deviceId) > 0);
        }
        Assertions.assertEquals(Collections.emptyList(), listener.events(), "no frame confirmed before the quit");

        ExecutorService server = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "GlRenderHostTest server");
            thread.setDaemon(true);
            return thread;
        });
        long releaseNanos;
        try {
            releaseNanos = server.submit(() -> {
                long start = System.nanoTime();
                host.releaseSession(1);
                return System.nanoTime() - start;
            })
                .get(10, TimeUnit.SECONDS);
        } finally {
            server.shutdownNow();
        }
        System.out
            .println("[opengpu-gl] releaseSession(1) with 2 x 2 frames in flight took " + releaseNanos / 1000 + " us");
        Assertions.assertTrue(releaseNanos < TimeUnit.MILLISECONDS.toNanos(50), releaseNanos + " ns");
        Assertions.assertFalse(ledger.isEmpty(1), "no pass ran since the release, so step (d) has not either");
        Assertions.assertEquals(Collections.emptyList(), info, "no pass has seen the release");

        on(gl, () -> host.pass(PassBudget.unlimited()));
        Assertions.assertTrue(ledger.isEmpty(1), () -> "session 1's rows: " + ledger.rows());
        for (int i = 0; i < devices.size(); i++) {
            DeviceKey key = devices.get(i)
                .key();
            Assertions.assertNull(core.stats(key), "no opening of " + key);
            Assertions.assertTrue(core.idle(key), key::toString);
            Assertions.assertNull(core.boundDevice(displays.get(i)), "no binding of " + displays.get(i));
            DeviceSnapshot snapshot = devices.get(i)
                .snapshot();
            Assertions.assertEquals(0, snapshot.confirmedFrameId, "no readback was delivered to " + key);
            Assertions.assertEquals(0, snapshot.staleReadbacks, "no stale delivery to " + key);
        }
        Assertions.assertEquals(Collections.emptyList(), listener.events(), "no status after the release");
        Assertions.assertEquals(0, core.contractViolations());
        List<String> released = Collections
            .singletonList("OpenGPU host: session 1 released, GL objects freed (ledger empty)");
        Assertions.assertEquals(released, info);
        for (int i = 0; i < 5; i++) {
            on(gl, () -> host.pass(PassBudget.unlimited()));
        }
        Assertions.assertEquals(released, info, "one line per session");
        Assertions.assertEquals(Collections.emptyList(), warn);
        close(gl, host);
    }

    /**
     * P7.4's other branch (review strength-3): a released session whose ledger still holds a row gets no "freed" line,
     * and {@value GlRenderHost#LEAK_PASSES} passes after its release one warning that names the row. The row is the
     * test's own, added to the host's ledger as a backend that failed to free an object would leave it, and taken out
     * again before {@code close()}.
     */
    @Test
    void aReleasedSessionThatStillHoldsARowIsWarnedAboutOnceAfterTheLeakPasses(GlThread gl) throws Exception {
        GlRenderHost host = production(gl);
        GlLedger ledger = host.ledger();
        ledger.add(1, 9, GlLedger.TEXTURE, "orphan", 64);
        host.releaseSession(1);
        on(gl, () -> host.pass(PassBudget.unlimited()));
        Assertions.assertEquals(Collections.emptyList(), info, "no freed line while the ledger holds a row");
        Assertions.assertEquals(Collections.emptyList(), warn, "no warning before the leak passes");
        on(gl, () -> {
            for (int pass = 1; pass < GlRenderHost.LEAK_PASSES - 1; pass++) {
                host.pass(PassBudget.unlimited());
            }
        });
        Assertions.assertEquals(Collections.emptyList(), warn, "no warning one pass before the leak passes");
        on(gl, () -> host.pass(PassBudget.unlimited()));
        Assertions.assertEquals(
            Collections.singletonList(
                "OpenGPU host: session 1 was released " + GlRenderHost.LEAK_PASSES
                    + " passes ago and still holds GL objects: [1:9 texture orphan = 64 B in 1]"),
            warn);
        on(gl, () -> {
            for (int pass = 0; pass < 5; pass++) {
                host.pass(PassBudget.unlimited());
            }
        });
        Assertions.assertEquals(1, warn.size(), "one warning per session");
        Assertions.assertEquals(Collections.emptyList(), info, "and never a freed line");
        ledger.remove(1, 9, GlLedger.TEXTURE, "orphan", 64);
        close(gl, host);
    }

    // P5.8, H63: a backend that throws fails the host for good; it is unavailable, its present lookup gives 0, a
    // present answers "no renderer" (28:1103), and the core logs the failure once
    @Test
    void aFailingBackendMakesTheHostUnavailableAndIsLoggedOnce(GlThread gl) throws Exception {
        HostBackend failing = new HostBackend() {

            @Override
            public void beginPass() {
                throw new IllegalStateException("this test's backend fails every pass");
            }

            @Override
            public void endPass() {}

            @Override
            public int maxSurfaceSide() {
                return 0;
            }

            @Override
            public DeviceBackend open(DeviceKey key, SurfaceDesc surface) {
                throw new AssertionError("a failed bracket opens nothing");
            }

            @Override
            public ByteBuffer staging(int bytes) {
                throw new AssertionError("a failed bracket stages nothing");
            }

            @Override
            public void close() {}
        };
        HostOptions options = HostOptions.defaults()
            .log(warn::add)
            .clock(clock);
        GlRenderHost host = new GlRenderHost(failing, new GlLedger(), options, info::add, "Test GL");
        DeviceRig rig = DeviceRig.on(DeviceCaps.forTier(2), host, unlimitedPass(gl, host));
        host.bindDisplay(DISPLAY, rig.key);
        Assertions.assertTrue(host.available());
        rig.step();
        Assertions.assertFalse(host.available());
        Assertions.assertEquals(0, (int) gl.call(() -> host.presentTexture(DISPLAY)));
        rig.step();
        rig.step();
        Assertions.assertEquals(1, warn.size(), warn::toString);
        Assertions.assertTrue(
            warn.get(0)
                .startsWith("OpenGPU: the GL host failed"),
            warn::toString);
        rig.submit(boxStream());
        CallResult present = rig.call(budget -> rig.device.present(budget));
        Assertions.assertFalse(present.isOk());
        Assertions.assertEquals("no renderer", present.message());
        close(gl, host);
        Assertions.assertEquals(1, warn.size(), warn::toString);
    }

    // P5.7: the first pass with work compares the backend's GL_RENDERER with GlCaps' and warns once when they differ;
    // the other cases, whose probed renderer is the context's, log nothing
    @Test
    void theFirstPassWithWorkWarnsOnceWhenTheProbedRendererDiffers(GlThread gl) throws Exception {
        GlRenderHost host = GlRenderHost.production(warn::add, info::add, "Not This GPU", clock);
        on(gl, host::pass);
        Assertions.assertEquals(Collections.emptyList(), warn, "a pass with no work reads no renderer");
        DeviceRig rig = DeviceRig.on(DeviceCaps.forTier(2), host, unlimitedPass(gl, host));
        rig.step();
        rig.step();
        rig.step();
        Assertions.assertEquals(1, warn.size(), warn::toString);
        String renderer = gl.info().renderer;
        Assertions.assertTrue(
            warn.get(0)
                .contains("\"" + renderer + "\" differs from GlCaps' \"Not This GPU\""),
            warn::toString);
        close(gl, host);
    }

    private static void ok(CallResult result) {
        Assertions.assertTrue(result.isOk(), result::message);
    }

    /** Work with no result on the GL thread, checked exceptions wrapped, for DeviceRig's Runnable pump. */
    private static void on(GlThread gl, Runnable work) {
        try {
            gl.call((Callable<Void>) () -> {
                work.run();
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
