package io.github.mindbound.opengpu.gltestkit.scenario;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.device.ShadowOracle;
import io.github.mindbound.opengpu.core.fixtures.device.TeeRenderHost;
import io.github.mindbound.opengpu.core.font.GlyphTable;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.QueryPolicy;
import io.github.mindbound.opengpu.gl.ReadbackFinish;
import io.github.mindbound.opengpu.gl.UploadProbe;
import io.github.mindbound.opengpu.gl.host.HostOptions;
import io.github.mindbound.opengpu.gltestkit.GlThread;
import io.github.mindbound.opengpu.gltestkit.host.GlHygiene;
import io.github.mindbound.opengpu.gltestkit.host.GlRecordRenderer;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * One run of one {@link Scenario}, which {@link Scenarios#run} makes and finishes: the GL thread, the leg, the
 * failures, fresh device keys, and the hosts and renderers the body makes, each checked against the run's MC-like
 * start after every GL entry point and closed by {@link Scenarios#run} in finally (S8 amendments E). The failures list
 * is synchronized, so workers and sinks on other threads may add to it.
 */
public final class ScenarioRun {

    /** The session of {@link #key()}'s keys. */
    public static final long SESSION = 8;
    /** Kept per run; the rest is counted. */
    static final int MAX_FAILURES = 60;
    /** Kept in the list at most. */
    static final int MAX_KEPT = 500;

    private final GlThread gl;
    private final String name;
    private final List<String> failures = Collections.synchronizedList(new ArrayList<String>());
    private final List<GlTestHost> hosts = new ArrayList<>();
    private final List<GlRecordRenderer> renderers = new ArrayList<>();
    private GlHygiene hygiene;
    private Map<String, String> initial;
    private int nextDevice;
    private int hostsMade;
    private int dropped;

    ScenarioRun(GlThread gl, String name) {
        this.gl = gl;
        this.name = name;
    }

    public GlThread gl() {
        return gl;
    }

    public String name() {
        return name;
    }

    /**
     * Whether this is a GLSM leg: the scenarios run on a core context only under GLSM, since glCoreTest excludes the
     * render core (H28).
     */
    public boolean glsm() {
        return gl.info().profile == GlThread.Profile.CORE_33;
    }

    /** "compat", or "glsm" and the Angelica release (opengpu.glsm.angelica). */
    public String leg() {
        return glsm() ? "glsm " + System.getProperty("opengpu.glsm.angelica", "?") : "compat";
    }

    /**
     * A count-driven scenario's count on this leg (S8 amendments E, CI time): {@code -Popengpu.gl.cases=N} on every
     * leg when set, else {@code compat} on the compatibility leg and {@code glsm} on the GLSM legs.
     */
    public int cases(int compat, int glsm) {
        String set = System.getProperty("opengpu.gl.cases");
        if (set != null && !set.trim()
            .isEmpty()) {
            return Integer.parseInt(set.trim());
        }
        return glsm() ? glsm : compat;
    }

    /** The start {@link Scenarios#run} set, or the one the body set with {@link #start}; null without one. */
    public GlHygiene hygiene() {
        return hygiene;
    }

    /** A fresh key of {@link #SESSION}: one per case (S8 amendments E, CI time). */
    public DeviceKey key() {
        return key(SESSION);
    }

    /** A fresh key of the session. */
    public DeviceKey key(long session) {
        return new DeviceKey(session, ++nextDevice);
    }

    /** {@link #host(GlyphTable, QuadMode, HostOptions, QueryPolicy, UploadProbe)} with the synthetic glyphs. */
    public GlTestHost host(QuadMode mode) {
        return host(mode, GlTestHost.defaultOptions());
    }

    public GlTestHost host(QuadMode mode, HostOptions options) {
        return host(SyntheticGlyphs.TABLE, mode, options, QueryPolicy.DRIVER, UploadProbe.NONE);
    }

    /**
     * A GlTestHost on a GlBackend of its own: one per (scenario, mode) is the rule (S8 amendments E, CI time), with a
     * fresh key per case, its readbacks' {@code glFinish} as {@code -Popengpu.gl.readbackFinish} says
     * ({@link GlTestHost#readbackFinish()}; Q-S8-3). Checked against the run's start after every GL entry point and
     * closed by Scenarios.run.
     */
    public GlTestHost host(GlyphTable glyphs, QuadMode mode, HostOptions options, QueryPolicy policy,
        UploadProbe probe) {
        return host(glyphs, mode, options, policy, probe, GlTestHost.readbackFinish());
    }

    /**
     * {@link #host(GlyphTable, QuadMode, HostOptions, QueryPolicy, UploadProbe)} with the readbacks' {@code glFinish}
     * as given, whatever {@code -Popengpu.gl.readbackFinish} says: for a scenario that needs the fetch's wait off to
     * reach what it tests (CopyWaitScenario).
     */
    public GlTestHost host(GlyphTable glyphs, QuadMode mode, HostOptions options, QueryPolicy policy, UploadProbe probe,
        ReadbackFinish finish) {
        GlTestHost host = new GlTestHost(gl, glyphs, mode, options, policy, probe, finish);
        String label = name + " host " + ++hostsMade + " (" + mode + ", " + policy + ", glFinish " + finish + ")";
        host.checkHygiene(hygiene, failures, label);
        hosts.add(host);
        return host;
    }

    /** A GlRecordRenderer with the synthetic glyphs, checked and closed as {@link #host} is. */
    public GlRecordRenderer renderer(QuadMode mode) {
        return renderer(SyntheticGlyphs.TABLE, mode);
    }

    public GlRecordRenderer renderer(GlyphTable glyphs, QuadMode mode) {
        GlRecordRenderer renderer = new GlRecordRenderer(gl, glyphs, mode);
        renderer.checkHygiene(hygiene, failures, name + " renderer " + ++hostsMade + " (" + mode + ")");
        renderers.add(renderer);
        return renderer;
    }

    /** Closes the host now, a failure of its close (a ledger row left, a contract violation) recorded. */
    public void close(GlTestHost host) {
        hosts.remove(host);
        try {
            host.close();
        } catch (RuntimeException | AssertionError e) {
            fail(host.name() + " close: " + Scenarios.describe(e));
        }
    }

    public void close(GlRecordRenderer renderer) {
        renderers.remove(renderer);
        try {
            renderer.close();
        } catch (RuntimeException | AssertionError e) {
            fail(renderer + " close: " + Scenarios.describe(e));
        }
    }

    /**
     * A {@link TeeRenderHost} over the host and a new {@link ShadowOracle} of the glyphs: every readback the host
     * delivers to a key opened through it is compared with the oracle's surface after that frame, a mismatch added to
     * the failures (S8 amendments E, B5).
     */
    public TeeRenderHost tee(GlTestHost host, GlyphTable glyphs) {
        return new TeeRenderHost(host, new ShadowOracle(glyphs), failures);
    }

    /** {@link #tee(GlTestHost, GlyphTable)} with the synthetic glyphs. */
    public TeeRenderHost tee(GlTestHost host) {
        return tee(host, SyntheticGlyphs.TABLE);
    }

    /** Records what when not ok; returns ok. */
    public boolean check(boolean ok, String what) {
        if (!ok) {
            fail(what);
        }
        return ok;
    }

    /** Records a failure; past {@link #MAX_KEPT} only the count grows, so a broken loop cannot flood the heap. */
    public void fail(String what) {
        synchronized (failures) {
            if (failures.size() < MAX_KEPT) {
                failures.add(name + ": " + what);
            } else {
                dropped++;
            }
        }
    }

    /** Failures past {@link #MAX_KEPT}, counted only. */
    public int dropped() {
        synchronized (failures) {
            return dropped;
        }
    }

    /** Byte equality of two surfaces in the 28 §5.7 layout, the first differing pixel reported (tier E, 23:137). */
    public boolean compare(String what, SurfaceDesc surface, byte[] expected, byte[] actual) {
        String difference = ShadowOracle.firstDifference(what, surface, expected, actual);
        if (difference != null) {
            fail(difference);
            return false;
        }
        return true;
    }

    /** Byte equality of two RGBA8 images of the surface's size, the first differing pixel and channel reported. */
    public boolean compareRgba(String what, SurfaceDesc surface, byte[] expected, byte[] actual) {
        if (expected.length != actual.length) {
            fail(what + ": " + actual.length + " RGBA bytes, expected " + expected.length);
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                int pixel = i / 4;
                fail(
                    String.format(
                        Locale.ROOT,
                        "%s: RGBA pixel (%d, %d) of %s, channel %d, is %d, expected %d",
                        what,
                        pixel % surface.width,
                        pixel / surface.width,
                        surface,
                        i % 4,
                        actual[i] & 0xFF,
                        expected[i] & 0xFF));
                return false;
            }
        }
        return true;
    }

    /** The failures so far, a synchronized list. */
    public List<String> failures() {
        return failures;
    }

    /** One "[opengpu-scenario]" line in the test log. */
    public void log(String line) {
        System.out.println("[opengpu-scenario] " + name + " on " + leg() + ": " + line);
    }

    /** Runs work on the GL thread; a checked exception comes back wrapped in an IllegalStateException. */
    public <T> T onGl(Callable<T> work) {
        try {
            return gl.call(work);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Sets a start for the hosts made from now on: {@code GlHygiene.mcLikeStart()} when hostile, else
     * {@code GlHygiene.current()} (S8 amendments E's "GLSM defaults"). An earlier start is undone first.
     */
    public GlHygiene start(final boolean hostile) {
        undoStart();
        if (initial == null) {
            initial = onGl(new Callable<Map<String, String>>() {

                @Override
                public Map<String, String> call() {
                    return GlHygiene.snapshot();
                }
            });
        }
        hygiene = onGl(new Callable<GlHygiene>() {

            @Override
            public GlHygiene call() {
                return hostile ? GlHygiene.mcLikeStart() : GlHygiene.current();
            }
        });
        return hygiene;
    }

    /** Checks the start once more and undoes it; the state must then be what it was before the first start. */
    public void undoStart() {
        final GlHygiene start = hygiene;
        if (start == null) {
            return;
        }
        hygiene = null;
        List<String> left = onGl(new Callable<List<String>>() {

            @Override
            public List<String> call() {
                List<String> found = new ArrayList<>();
                for (String finding : start.diff()) {
                    found.add("at the end of the start: " + finding);
                }
                start.undo();
                for (String finding : GlHygiene.compare(initial)) {
                    found.add("after the start's undo: " + finding);
                }
                return found;
            }
        });
        for (String finding : left) {
            fail(finding);
        }
    }

    /** Scenarios.run's finally: every host and renderer closed, then the start checked and undone. */
    void finish() {
        for (GlTestHost host : new ArrayList<>(hosts)) {
            close(host);
        }
        for (GlRecordRenderer renderer : new ArrayList<>(renderers)) {
            close(renderer);
        }
        try {
            undoStart();
        } catch (RuntimeException | AssertionError e) {
            fail("undoing the start: " + Scenarios.describe(e));
        }
    }
}
