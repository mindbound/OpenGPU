package io.github.mindbound.opengpu.gl.host;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * {@link GlHostCore}'s options (S8 plan §2.2). Immutable: each with-er returns a changed copy, and each getter has the
 * with-er's name.
 */
public final class HostOptions {

    /** When a device's present texture is expanded after a turn that changed its surface (H27; 28:484-488). */
    public enum Expand {
        /** After every such turn: {@code GlTestHost}, which checks the present texture. */
        ALWAYS,
        /**
         * Only while a display is bound to the device: the root's default (H68). A device without a display keeps its
         * expand owed until one is bound, so nothing is lost.
         */
        BOUND_ONLY,
        /** Never: tests that count backend calls. */
        NEVER
    }

    /** 24:197: a readback is fetched, waiting for its copy, this many passes after its issue at the latest. */
    public static final int FORCE_FETCH_AFTER_PASSES = 4;
    /** The bounds of {@link #forceFetchAfterPasses(int)}. */
    public static final int MIN_FORCE_FETCH = 1, MAX_FORCE_FETCH = 16;

    private static final HostOptions DEFAULTS = new HostOptions(
        true,
        System::nanoTime,
        GlHostCore.STALL_AFTER_NANOS,
        Expand.BOUND_ONLY,
        FORCE_FETCH_AFTER_PASSES,
        new Consumer<String>() {

            @Override
            public void accept(String message) {
                System.err.println(message);
            }

            @Override
            public String toString() {
                return "System.err";
            }
        });

    private final boolean mirrors;
    private final LongSupplier clock;
    private final long stallAfterNanos;
    private final Expand expand;
    private final int forceFetchAfterPasses;
    private final Consumer<String> log;

    private HostOptions(boolean mirrors, LongSupplier clock, long stallAfterNanos, Expand expand,
        int forceFetchAfterPasses, Consumer<String> log) {
        this.mirrors = mirrors;
        this.clock = clock;
        this.stallAfterNanos = stallAfterNanos;
        this.expand = expand;
        this.forceFetchAfterPasses = forceFetchAfterPasses;
        this.log = log;
    }

    /**
     * Mirrors on (Q-S8-1's option A), {@code System::nanoTime}, stalled after {@link GlHostCore#STALL_AFTER_NANOS}
     * (20:194; 24:126), {@link Expand#BOUND_ONLY}, a forced fetch after {@link #FORCE_FETCH_AFTER_PASSES} passes
     * (24:197; at most two readbacks in flight is fixed), and log lines to {@code System.err}, which FML routes to the
     * game's log.
     */
    public static HostOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Whether resource records offered behind no queued {@code FRAME} fold into host-side texture mirrors at once
     * (Q-S8-1's option A, the owner's choice of 2026-10-16, with amendment A's copy-on-write), which bounds what the
     * host holds for a device whose records it does not run (H24). False queues every record until the render thread
     * runs it (option B: bounded only by time), for the tests that show the difference.
     */
    public boolean mirrors() {
        return mirrors;
    }

    public HostOptions mirrors(boolean on) {
        return new HostOptions(on, clock, stallAfterNanos, expand, forceFetchAfterPasses, log);
    }

    /** The heartbeat's nanosecond clock (H32). */
    public LongSupplier clock() {
        return clock;
    }

    /** @throws NullPointerException for a null clock */
    public HostOptions clock(LongSupplier nanos) {
        if (nanos == null) {
            throw new NullPointerException("clock");
        }
        return new HostOptions(mirrors, nanos, stallAfterNanos, expand, forceFetchAfterPasses, log);
    }

    /** How long the render loop may be silent before {@code stalled()} answers true; {@code Long.MAX_VALUE}: never. */
    public long stallAfterNanos() {
        return stallAfterNanos;
    }

    /** @throws IllegalArgumentException for a negative value */
    public HostOptions stallAfterNanos(long nanos) {
        if (nanos < 0) {
            throw new IllegalArgumentException("stall threshold " + nanos + " ns is negative");
        }
        return new HostOptions(mirrors, clock, nanos, expand, forceFetchAfterPasses, log);
    }

    public Expand expand() {
        return expand;
    }

    /** @throws NullPointerException for null */
    public HostOptions expand(Expand when) {
        if (when == null) {
            throw new NullPointerException("expand");
        }
        return new HostOptions(mirrors, clock, stallAfterNanos, when, forceFetchAfterPasses, log);
    }

    /** Passes after which a readback is fetched whatever its query says (24:197; S8 plan §3.9). */
    public int forceFetchAfterPasses() {
        return forceFetchAfterPasses;
    }

    /** @throws IllegalArgumentException outside {@value #MIN_FORCE_FETCH}-{@value #MAX_FORCE_FETCH} */
    public HostOptions forceFetchAfterPasses(int passes) {
        if (passes < MIN_FORCE_FETCH || passes > MAX_FORCE_FETCH) {
            throw new IllegalArgumentException(
                "force fetch after " + passes + " passes is outside " + MIN_FORCE_FETCH + ".." + MAX_FORCE_FETCH);
        }
        return new HostOptions(mirrors, clock, stallAfterNanos, expand, passes, log);
    }

    /** Where the host writes its few log lines: a failure's reason, once (20:242; H63), and a sink that threw. */
    public Consumer<String> log() {
        return log;
    }

    /** @throws NullPointerException for a null log */
    public HostOptions log(Consumer<String> log) {
        if (log == null) {
            throw new NullPointerException("log");
        }
        return new HostOptions(mirrors, clock, stallAfterNanos, expand, forceFetchAfterPasses, log);
    }

    @Override
    public String toString() {
        return "HostOptions[mirrors " + (mirrors ? "on" : "off")
            + ", stalled after "
            + (stallAfterNanos == Long.MAX_VALUE ? "never" : stallAfterNanos + " ns")
            + ", expand "
            + expand
            + ", force fetch after "
            + forceFetchAfterPasses
            + " passes]";
    }
}
