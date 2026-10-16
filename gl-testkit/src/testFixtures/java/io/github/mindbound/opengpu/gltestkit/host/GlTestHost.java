package io.github.mindbound.opengpu.gltestkit.host;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.font.GlyphTable;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.ReadbackSink;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.RenderHost;
import io.github.mindbound.opengpu.gl.GlBackend;
import io.github.mindbound.opengpu.gl.GlLedger;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.QueryPolicy;
import io.github.mindbound.opengpu.gl.ReadbackFinish;
import io.github.mindbound.opengpu.gl.UploadProbe;
import io.github.mindbound.opengpu.gl.host.DeviceBackend;
import io.github.mindbound.opengpu.gl.host.DeviceStats;
import io.github.mindbound.opengpu.gl.host.GlHostCore;
import io.github.mindbound.opengpu.gl.host.HostOptions;
import io.github.mindbound.opengpu.gl.host.PassBudget;
import io.github.mindbound.opengpu.gltestkit.GlThread;

/**
 * 23:182's test host (H29): {@link GlHostCore} on a {@link GlBackend} of its own, on a {@link GlThread}, pumped step
 * by step. The {@link RenderHost} methods go straight to the core from the caller's thread, as a {@code Device}'s do
 * from the server thread; each pump runs one START pass on the GL thread (S8 plan §3.2). A pass that fails the host
 * (H63) fails the pump with the reason. With {@link #checkHygiene}, every GL entry point (pump, read, close) is
 * followed by {@link GlHygiene#diff()} on the GL thread, its findings added to the given list (S8 amendments E). Its
 * backend takes {@link #readbackFinish()}'s policy. It makes no GL call of its own: {@link GlReads} reads the present
 * texture. Not thread-safe beyond {@code GlHostCore}: one thread pumps.
 */
public final class GlTestHost implements RenderHost {

    /** {@link #pumpUntil}'s bound for {@link #pumpUntilIdle}, {@link #surfacePixels} and {@link #presentRgba}. */
    public static final int MAX_PUMPS = 64;
    /** {@code -Popengpu.gl.readbackFinish=auto|always|never}, which gl-testkit's build passes to every test task. */
    public static final String READBACK_FINISH_PROPERTY = "opengpu.gl.readbackFinish";
    /** Hygiene findings kept per host, so one leaked item does not flood the report. */
    private static final int MAX_FINDINGS = 20;

    private final GlThread gl;
    private final GlLedger ledger;
    private final GlBackend backend;
    private final GlHostCore core;
    private GlHygiene hygiene;
    private List<String> findings;
    private String name = "GlTestHost";
    private int findingsKept;
    private long pumps;
    private boolean closed;

    /** {@link QuadMode#EXPANDED} with {@link #defaultOptions()} (S8 plan §2.4). No GL work. */
    public GlTestHost(GlThread gl, GlyphTable glyphs) {
        this(gl, glyphs, QuadMode.EXPANDED, defaultOptions());
    }

    /** {@link QueryPolicy#DRIVER} and no upload probe. No GL work. */
    public GlTestHost(GlThread gl, GlyphTable glyphs, QuadMode mode, HostOptions options) {
        this(gl, glyphs, mode, options, QueryPolicy.DRIVER, UploadProbe.NONE);
    }

    /**
     * No GL work: the backend makes its objects at the first pump (H57). Its readbacks' {@code glFinish} follows
     * {@link #readbackFinish()}.
     */
    public GlTestHost(GlThread gl, GlyphTable glyphs, QuadMode mode, HostOptions options, QueryPolicy policy,
        UploadProbe probe) {
        this(gl, glyphs, mode, options, policy, probe, readbackFinish());
    }

    /** No GL work, with the readbacks' {@code glFinish} as given, whatever {@link #readbackFinish()} says. */
    public GlTestHost(GlThread gl, GlyphTable glyphs, QuadMode mode, HostOptions options, QueryPolicy policy,
        UploadProbe probe, ReadbackFinish finish) {
        if (gl == null) {
            throw new NullPointerException("gl");
        }
        this.gl = gl;
        this.ledger = new GlLedger();
        this.backend = new GlBackend(glyphs, mode, ledger, probe, policy, finish);
        this.core = new GlHostCore(backend, options);
    }

    /**
     * The test JVM's {@link ReadbackFinish} for every GlBackend the fixtures make: {@value #READBACK_FINISH_PROPERTY},
     * which gl-testkit's build gives every test JVM, {@code auto} (production's) without {@code -P}. {@code never} on a
     * driver is Q-S8-3's re-verification run. A JVM without it was not set up by that build, or the build dropped it,
     * which would run a {@code never} re-verification at {@code auto} unseen: it fails here, loudly.
     *
     * @throws IllegalStateException    when the property is not set
     * @throws IllegalArgumentException for another value
     */
    public static ReadbackFinish readbackFinish() {
        String raw = System.getProperty(READBACK_FINISH_PROPERTY);
        if (raw == null) {
            throw new IllegalStateException(
                READBACK_FINISH_PROPERTY + " is not set in this test JVM: gl-testkit's build passes it to every Test"
                    + " task (auto without -P"
                    + READBACK_FINISH_PROPERTY
                    + "), so this JVM was not started by that build or the build dropped it (Q-S8-3)");
        }
        String value = raw.trim();
        for (ReadbackFinish policy : ReadbackFinish.values()) {
            if (policy.name()
                .equals(value.toUpperCase(Locale.ROOT))) {
                return policy;
            }
        }
        throw new IllegalArgumentException(
            READBACK_FINISH_PROPERTY + " is \"" + value + "\", not one of auto, always and never");
    }

    /**
     * One "[opengpu-gl]" line naming the policy this host's backend resolved for the driver, after its first pump.
     *
     * @return null when it resolved as {@link #readbackFinish()} says for {@code vendor}, the leg's
     *         {@code GL_VENDOR}, else what differs
     */
    public String checkReadbackFinish(String vendor) {
        boolean expected = readbackFinish().finishes(vendor);
        String line = name + ": readback glFinish "
            + (backend.readbackFinish() ? "on" : "off")
            + " ("
            + backend.readbackFinishPolicy()
            + ", the backend's GL_VENDOR "
            + backend.vendor()
            + ", the leg's "
            + vendor
            + ")";
        System.out.println("[opengpu-gl] " + line);
        if (backend.readbackFinishPolicy() != readbackFinish() || backend.readbackFinish() != expected
            || vendor == null
            || !vendor.equals(backend.vendor())) {
            return line + ", expected " + readbackFinish() + " resolving " + (expected ? "on" : "off");
        }
        return null;
    }

    /** {@code HostOptions.defaults()}, never stalled and always expanding (S8 plan §2.4, §3.8; H32). */
    public static HostOptions defaultOptions() {
        return HostOptions.defaults()
            .stallAfterNanos(Long.MAX_VALUE)
            .expand(HostOptions.Expand.ALWAYS);
    }

    /** After every GL entry point, {@code hygiene.diff()}'s findings go to {@code findings}, prefixed by name. */
    public GlTestHost checkHygiene(GlHygiene hygiene, List<String> findings, String name) {
        this.hygiene = hygiene;
        this.findings = findings;
        this.name = name;
        return this;
    }

    // RenderHost: the core's, from the caller's thread

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

    @Override
    public void releaseSession(long session) {
        core.releaseSession(session);
    }

    // Pumps

    /** One pass with {@code PassBudget.unlimited()} on the GL thread. */
    public void pumpFrame() {
        pumpFrame(PassBudget.unlimited());
    }

    /**
     * One pass with the budget on the GL thread, then the hygiene check there.
     *
     * @throws AssertionError when the pass failed the host (H63)
     */
    public void pumpFrame(final PassBudget budget) {
        onGl(() -> {
            core.pass(budget);
            pumps++;
            checkHygiene("pump " + pumps);
            return null;
        });
        String failure = core.failure();
        if (failure != null) {
            throw new AssertionError(name + ": the GL host failed at pump " + pumps + ": " + failure);
        }
    }

    /**
     * Pumps until {@code done} holds, asked before each pump, at most maxPumps times.
     *
     * @return the pumps taken
     * @throws AssertionError "not done after max pumps: what" when it still does not hold
     */
    public int pumpUntil(BooleanSupplier done, int maxPumps, String what) {
        for (int taken = 0; taken < maxPumps; taken++) {
            if (done.getAsBoolean()) {
                return taken;
            }
            pumpFrame();
        }
        if (done.getAsBoolean()) {
            return maxPumps;
        }
        throw new AssertionError(name + ": not done after " + maxPumps + " pumps: " + what);
    }

    /** Pumps until {@link GlHostCore#idle} holds for every key, at most {@link #MAX_PUMPS} times; the pumps taken. */
    public int pumpUntilIdle(final DeviceKey... keys) {
        return pumpUntil(() -> {
            for (DeviceKey key : keys) {
                if (!core.idle(key)) {
                    return false;
                }
            }
            return true;
        }, MAX_PUMPS, "idle " + java.util.Arrays.toString(keys));
    }

    /** Pumps so far. */
    public long pumps() {
        return pumps;
    }

    // Reads

    /**
     * The surface now, through the production calls (amendment C): pumped until the key is idle, then
     * {@code GlHostCore.readNow}, whose {@code glGetBufferSubData} waits for the copy.
     */
    public byte[] surfacePixels(final DeviceKey key) {
        pumpUntilIdle(key);
        return onGl(() -> {
            byte[] pixels = core.readNow(key);
            checkHygiene("surfacePixels " + key);
            return pixels;
        });
    }

    /**
     * The present texture, RGBA8, row 0 the surface's top row (H27): pumped until the key is idle, then a test-only
     * direct read ({@link GlReads}, A08) inside the backend's bracket.
     *
     * @throws IllegalStateException for a key with no GL objects
     */
    public byte[] presentRgba(final DeviceKey key) {
        pumpUntilIdle(key);
        return onGl(() -> {
            DeviceBackend device = core.deviceBackend(key);
            if (device == null) {
                throw new IllegalStateException(key + " has no GL objects on " + name);
            }
            byte[] rgba = GlReads.rgba(backend, backend.presentTexture(device), core.stats(key).surface);
            checkHygiene("presentRgba " + key);
            return rgba;
        });
    }

    /** The key's {@link DeviceBackend} (GL thread), or null: for GlBackend's per-device figures and texture names. */
    public DeviceBackend deviceBackend(final DeviceKey key) {
        return onGl(() -> core.deviceBackend(key));
    }

    /** {@link GlHostCore#stats}. */
    public DeviceStats stats(DeviceKey key) {
        return core.stats(key);
    }

    /** {@link GlHostCore#idle}. */
    public boolean idle(DeviceKey key) {
        return core.idle(key);
    }

    /** Runs work on the GL thread; a checked exception comes back wrapped in an IllegalStateException. */
    public <T> T onGl(Callable<T> work) {
        return call(gl, work);
    }

    public GlHostCore core() {
        return core;
    }

    public GlBackend backend() {
        return backend;
    }

    public GlLedger ledger() {
        return ledger;
    }

    public GlThread gl() {
        return gl;
    }

    public String name() {
        return name;
    }

    /**
     * {@code core().close()} on the GL thread and the hygiene check after it. Idempotent.
     *
     * @throws AssertionError unless the ledger is then empty (24:218) and no contract violation occurred (H47)
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        onGl(() -> {
            core.close();
            checkHygiene("close");
            return null;
        });
        if (!ledger.isEmpty()) {
            throw new AssertionError(name + ": the ledger is not empty after close: " + ledger.rows());
        }
        if (core.contractViolations() != 0) {
            throw new AssertionError(name + ": " + core.contractViolations() + " contract violation(s) (H47)");
        }
    }

    public boolean closed() {
        return closed;
    }

    @Override
    public String toString() {
        return name + "["
            + backend.mode()
            + ", "
            + backend.queryPolicy()
            + ", glFinish "
            + backend.readbackFinishPolicy()
            + ", "
            + core
            + "]";
    }

    /** GL thread: the hygiene findings after an entry point. */
    private void checkHygiene(String where) {
        if (hygiene == null) {
            return;
        }
        for (String finding : hygiene.diff()) {
            if (findingsKept < MAX_FINDINGS) {
                findings.add(name + " after " + where + ": " + finding);
            } else if (findingsKept == MAX_FINDINGS) {
                findings.add(name + ": more hygiene findings dropped");
            }
            findingsKept++;
        }
    }

    /** {@link GlThread#call} with a checked exception wrapped, so pumps fit a Runnable (DeviceRig.on's pump). */
    static <T> T call(GlThread gl, Callable<T> work) {
        try {
            return gl.call(work);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
