package io.github.mindbound.opengpu.client.render;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.font.GlyphTables;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.ReadbackSink;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.RenderHost;
import io.github.mindbound.opengpu.gl.GlBackend;
import io.github.mindbound.opengpu.gl.GlLedger;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.UploadProbe;
import io.github.mindbound.opengpu.gl.host.DeviceBackend;
import io.github.mindbound.opengpu.gl.host.GlHostCore;
import io.github.mindbound.opengpu.gl.host.HostBackend;
import io.github.mindbound.opengpu.gl.host.HostOptions;
import io.github.mindbound.opengpu.gl.host.PassBudget;

/**
 * The client's render host (21:57-63; 24:83, :173; RM:35-36; S12 plan P5 with amendment I4): one {@link GlHostCore}
 * over one {@link GlBackend}, the shape of {@code :gl-testkit}'s {@code GlTestHost}, pumped by the game.
 * {@link RenderTickListener} runs {@link #pass()} at each {@code RenderTickEvent} START on the client main thread,
 * which becomes the core's GL thread at its first pass with work (H57; GLSM's main thread under Angelica). It names
 * only {@code :gl}, {@code :core} and JDK types, so a GL test drives it with no Minecraft, and it lives under
 * {@code client/} because it names {@code :gl} ({@code jarTest} check 1; 24:90).
 * <p>
 * Threads. The {@link RenderHost} methods go straight to the core from any thread, the integrated server's and OC's
 * workers among them, and never block (RenderHost.java:5-11; 28:184-199). {@link #pass()}, {@link #presentTexture}
 * and the test accessors run on the GL thread. Nothing here does GL work at construction: the backend makes its
 * objects at the first pass with work, on the game's own context, not the splash's shared one (GlBackend.java:226-228).
 * <p>
 * The quit (RM:68; S12 plan P7.4). {@link #releaseSession} takes locks only, so the server thread's stop never waits
 * for GL; the next pass with work deletes the session's GL objects and discards its readbacks in flight (step (d),
 * {@code GlHostCore.destroyReleased}), and {@link #pass()} then logs once that the ledger holds nothing of the session,
 * or, if it still does {@value #LEAK_PASSES} passes later, a warning with its rows.
 */
public final class GlRenderHost implements RenderHost {

    /**
     * The START pass's budget in milliseconds: 24:173's {@code client.renderBudgetMs} default, an estimate there, and a
     * constant in M0; the key comes with M1's host safety (RM:83; 24:214; S12 amendments F4).
     */
    public static final double PASS_MILLIS = 4;

    /**
     * Passes after a session's release before its remaining GL objects are reported, about 7 s at the title screen's
     * 30 fps [proposal; S12 plan P7.4].
     */
    static final int LEAK_PASSES = 200;

    /** The backend when it is a {@link GlBackend}, else null: a test's backend has no present texture. */
    private final GlBackend gl;
    private final GlLedger ledger;
    private final GlHostCore core;
    private final Consumer<String> info;
    private final Consumer<String> warn;
    private final String probedRenderer;
    /** Sessions released on any thread, which the GL thread takes at its next pass (S12 plan P5.3, P7.4). */
    private final ConcurrentLinkedQueue<Long> released = new ConcurrentLinkedQueue<>();

    // The GL thread's
    /** Released sessions whose ledger rows are awaited: {session, passes since it was taken}. */
    private final List<long[]> awaiting = new ArrayList<>();
    private boolean rendererChecked;

    /**
     * A host on any backend, for tests (a failing backend, a query policy that keeps readbacks in flight). Warnings,
     * the core's own included, go to {@code options.log()}; the release line goes to {@code info}. No GL work.
     *
     * @param ledger         the ledger the backend records into, which the release line reads
     * @param probedRenderer {@code GlCaps}' {@code GL_RENDERER}, which the first pass with work checks (S12 plan P5.7)
     * @throws NullPointerException for a null backend, ledger, options or info
     */
    GlRenderHost(HostBackend backend, GlLedger ledger, HostOptions options, Consumer<String> info,
        String probedRenderer) {
        Objects.requireNonNull(backend, "backend");
        this.gl = backend instanceof GlBackend ? (GlBackend) backend : null;
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.core = new GlHostCore(backend, Objects.requireNonNull(options, "options"));
        this.info = Objects.requireNonNull(info, "info");
        this.warn = options.log();
        this.probedRenderer = probedRenderer;
    }

    /**
     * {@link #production(Consumer, Consumer, String, LongSupplier)} on {@code System::nanoTime}: the host
     * {@code ClientProxy.init} installs, with {@code OpenGPU.LOG}'s warn and info.
     *
     * @throws NullPointerException for a null log or info
     */
    public static GlRenderHost production(Consumer<String> log, Consumer<String> info, String probedRenderer) {
        return production(log, info, probedRenderer, System::nanoTime);
    }

    /**
     * The production host on a given clock (S12 amendments I4): font 0, {@link QuadMode#EXPANDED} until G16's
     * measurement (QuadMode.java:3-7), a ledger of its own, no upload probe, and through {@link GlBackend}'s production
     * constructor {@code QueryPolicy.DRIVER} and {@code ReadbackFinish.AUTO}; {@link HostOptions#defaults()}, that is
     * mirrors on (Q-S8-1), a 2 s stall (24:126), {@code Expand.BOUND_ONLY} (28:1095's H68, a proposal adopted for
     * S12) and a forced fetch after 4 passes (24:197), with {@code log} for its warnings and {@code clock} for its
     * heartbeat. The tests make it on a {@code ManualClock}, so a slow first pass on llvmpipe never reads as a stall.
     * No GL work; the 2 MiB direct staging buffer is allocated on every client (S12 plan P5.2).
     *
     * @throws NullPointerException for a null log, info or clock
     */
    static GlRenderHost production(Consumer<String> log, Consumer<String> info, String probedRenderer,
        LongSupplier clock) {
        GlLedger ledger = new GlLedger();
        GlBackend backend = new GlBackend(GlyphTables.font0(), QuadMode.EXPANDED, ledger, UploadProbe.NONE);
        HostOptions options = HostOptions.defaults()
            .log(log)
            .clock(clock);
        return new GlRenderHost(backend, ledger, options, info, probedRenderer);
    }

    // ---- RenderHost: the core's, from any thread

    @Override
    public boolean available() {
        return core.available();
    }

    @Override
    public boolean stalled() {
        return core.stalled();
    }

    @Override
    public void open(DeviceKey key, SurfaceDesc surface, ReadbackSink sink) {
        core.open(key, surface, sink);
    }

    @Override
    public void offer(DeviceKey key, Record record) {
        core.offer(key, record);
    }

    @Override
    public void release(DeviceKey key) {
        core.release(key);
    }

    @Override
    public void bindDisplay(String displayId, DeviceKey key) {
        core.bindDisplay(displayId, key);
    }

    /**
     * Queues the session for {@link #pass()}'s release line, then releases it in the core: locks only, never GL, so
     * {@code ServerSession.stop} on the server thread returns while the client waits for that thread (21:111; RM:68).
     */
    @Override
    public void releaseSession(long session) {
        released.add(session);
        core.releaseSession(session);
    }

    // ---- The GL thread: the client main thread in game

    /**
     * One START pass within {@link #PASS_MILLIS} (21:85-88; 24:173). A pass with no work returns before the core's
     * bracket, so a client with no device pays one heartbeat write a frame (GlHostCore.java:281-283). A backend failure
     * fails the host for good (H63): it is logged once and not rethrown, and {@link #available()} answers false.
     *
     * @throws IllegalStateException on a thread other than the core's GL thread (H57)
     */
    public void pass() {
        pass(PassBudget.millis(PASS_MILLIS));
    }

    /** {@link #pass()} within any budget; tests run {@code PassBudget.unlimited()}. */
    void pass(PassBudget budget) {
        core.pass(budget);
        checkRenderer();
        reportReleases();
    }

    /**
     * The RGBA8 present texture of the device bound to the display, row 0 the surface's top row (GlBackend.java:364),
     * for the TESR and the GUI; 0 for a null id, an unbound display, a device with no GL objects yet or released, and a
     * host that is not available (S12 plan P5.5). A released device's texture is deleted only by a later START pass,
     * which runs before that frame's TESRs, so a name given here stays valid for the frame.
     *
     * @throws IllegalStateException on a thread other than the core's GL thread (H57)
     */
    public int presentTexture(String displayId) {
        if (displayId == null || gl == null || !core.available()) {
            return 0;
        }
        DeviceBackend device = core.presentDevice(displayId);
        return device == null ? 0 : gl.presentTexture(device);
    }

    /**
     * After the first pass with work, the backend's {@code GL_RENDERER} against the one {@code GlCaps} read at init:
     * they should agree, and a difference means the splash's shared context misreported (S12 plan P5.7) [proposal].
     */
    private void checkRenderer() {
        if (rendererChecked || gl == null) {
            return;
        }
        String passRenderer = gl.renderer();
        if (passRenderer == null) {
            return;
        }
        rendererChecked = true;
        if (!passRenderer.equals(probedRenderer)) {
            warn.accept(
                "OpenGPU host: the first pass's GL_RENDERER \"" + passRenderer
                    + "\" differs from GlCaps' \""
                    + probedRenderer
                    + "\", read at client init on the splash's shared context");
        }
    }

    /** P7.4's evidence line, once per released session, after the core's pass. */
    private void reportReleases() {
        for (Long session = released.poll(); session != null; session = released.poll()) {
            awaiting.add(new long[] { session, 0 });
        }
        for (Iterator<long[]> it = awaiting.iterator(); it.hasNext();) {
            long[] entry = it.next();
            long session = entry[0];
            if (ledger.isEmpty(session)) {
                info.accept("OpenGPU host: session " + session + " released, GL objects freed (ledger empty)");
                it.remove();
            } else if (++entry[1] >= LEAK_PASSES) {
                List<GlLedger.Row> rows = new ArrayList<>();
                for (GlLedger.Row row : ledger.rows()) {
                    if (row.key.session == session) {
                        rows.add(row);
                    }
                }
                warn.accept(
                    "OpenGPU host: session " + session
                        + " was released "
                        + LEAK_PASSES
                        + " passes ago and still holds GL objects: "
                        + rows);
                it.remove();
            }
        }
    }

    // ---- For tests

    /** Releases everything and deletes every GL object; the GL thread. The game never calls it (21:114). */
    void close() {
        core.close();
    }

    GlHostCore core() {
        return core;
    }

    GlLedger ledger() {
        return ledger;
    }

    @Override
    public String toString() {
        return "GlRenderHost[" + core + "]";
    }
}
