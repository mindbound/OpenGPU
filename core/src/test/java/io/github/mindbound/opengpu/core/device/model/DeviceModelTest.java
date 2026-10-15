package io.github.mindbound.opengpu.core.device.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.device.DeviceCaps;

/**
 * The model test of S7 plan §4.5 with amendments A (churn) and B8: {@code Device} against {@code NaiveDevice}, the
 * reference model written separately from 28 alone (plan §2.4, §6.3), op by op over seeded sequences of the M0
 * callbacks (28 §2.1, §3.6), host runs and readbacks, ticks, resets and host switches ({@link OpGenerator}), compared
 * by {@link ModelBridge} after every op and checked deeply every 16 ops on the small caps, every 64 on T2, and at the
 * end of every sequence (amendment B8).
 * <p>
 * The default run is 500 sequences of 200 ops, 100 000 ops: 300 on the small index8 caps, 100 on their rgb565 twin
 * and 100 on T2 (plan §4.5). {@code -Popengpu.model.cases=N} scales the count, split 3:1:1;
 * {@code -Popengpu.model.seed=S} (default 7) changes the stream. Each sequence is its own dynamic test, named by its
 * seed, so a failure is reproduced from the seed; its message names the op index, the op, both sides' values and the
 * ops before it. Each tier's coverage test then checks that the run reached the outcomes the rules are about (H2,
 * H23, every status, the two-phase pattern on T2), when the run has at least the default size. The times, the T2
 * portion's apart (amendment B8, DoD step 3), are printed at the end.
 */
class DeviceModelTest {

    static final long SEED = Long.getLong("opengpu.model.seed", 7L);
    static final int CASES = Integer.getInteger("opengpu.model.cases", 500);
    static final int OPS = 200;
    private static final int DEFAULT_CASES = 500;

    /** Plan §4's small caps: 24x16 index8, 2 KB of VRAM, 6 textures of at most 32 px a side, 256 B per readback. */
    static final DeviceCaps SMALL_INDEX8 = new DeviceCaps(
        new SurfaceDesc(2, 24, 16, Formats.INDEX8),
        new FrameLimits(65536, 512, 24, 4000),
        2048,
        6,
        32,
        256,
        2);
    /** Their T3 twin: 16x12 rgb565, 4 KB of VRAM, 512 B per readback. */
    static final DeviceCaps SMALL_RGB565 = new DeviceCaps(
        new SurfaceDesc(3, 16, 12, Formats.RGB565),
        new FrameLimits(65536, 512, 24, 4000),
        4096,
        6,
        32,
        512,
        2);

    /** One part of the run. */
    private static final class Part {

        final String name;
        final int ordinal;
        final DeviceCaps caps;
        final int sequences;
        final int deepEvery;
        final List<String> required;
        final Set<String> coverage = new TreeSet<>();
        long nanos;
        int ran;

        Part(String name, int ordinal, DeviceCaps caps, int sequences, int deepEvery, List<String> required) {
            this.name = name;
            this.ordinal = ordinal;
            this.caps = caps;
            this.sequences = sequences;
            this.deepEvery = deepEvery;
            this.required = required;
        }
    }

    /**
     * What every part must reach: each callback's main outcomes (28 §3.6's callback table), H2's and H23's busy
     * (amendment
     * A), every status of present (28:98, H3), both frame statuses (28:110-112), a stale readback (28:155) and frees
     * deferred while frames are in flight (O4).
     */
    private static final List<String> COMMON = Arrays.asList(
        "createTexture: ok",
        "createTexture: invalid texture size",
        "createTexture: unsupported format",
        "createTexture: not enough video memory",
        "createTexture: busy (H23)",
        "updateTexture: ok",
        "updateTexture: invalid handle 0x#",
        "updateTexture: handle 0x# has an unknown type",
        "updateTexture: handle 0x# is a buffer, expected a texture",
        "updateTexture: handle 0x# is not live",
        "updateTexture: invalid texture region",
        "updateTexture: data must be # bytes",
        "updateTexture: busy",
        "free: ok",
        "free: invalid handle 0x#",
        "free: handle 0x# is not live",
        "submit: ok",
        "submit: #: BLIT: handle 0x# is not live",
        "submit: #: TEXT: invalid UTF-# at string byte #",
        "submit: #: header: segment of # bytes is shorter than the #-byte header",
        "submit: #: submit: # bytes exceed the #-byte cap",
        "present: ok",
        "present: busy",
        "present: no renderer",
        "present: host render stalled",
        "readPixels: ok",
        "readPixels: invalid region",
        "churn's free: ok",
        "listener: done",
        "listener: dropped",
        "budget: consume",
        "readback: stale",
        "state: frees deferred with frames in flight");

    /** The frame caps of 28 §8.1, which bind across submits on the small caps (28:921's "caps ±1"). */
    private static final String[] SMALL_CAPS = { "createTexture: too many textures",
        "submit: #: submit: # bytes with the open frame exceed the #-byte frame cap",
        "submit: #: TEXT: # records exceed the frame's #-record cap" };

    private static final List<Part> PARTS = new ArrayList<>();

    @TestFactory
    List<DynamicTest> theDeviceAgreesWithTheReferenceModel() {
        int index8 = CASES * 3 / 5;
        int rgb565 = CASES / 5;
        PARTS.clear();
        PARTS.add(
            new Part(
                "small index8",
                0,
                SMALL_INDEX8,
                index8,
                16,
                plus(
                    plus(COMMON, SMALL_CAPS),
                    "createTexture: busy (H2)",
                    "submit: #: FILL_RECT: # px of fill exceed the frame's #-px cap",
                    "readPixels: region exceeds # bytes per call")));
        PARTS.add(
            new Part(
                "small rgb565",
                1,
                SMALL_RGB565,
                rgb565,
                16,
                plus(
                    plus(COMMON, SMALL_CAPS),
                    "submit: #: CLEAR: colour 0x#: alpha must be #xFF before blending (M#)")));
        PARTS.add(
            new Part(
                "T2",
                2,
                DeviceCaps.forTier(2),
                CASES - index8 - rgb565,
                64,
                plus(
                    COMMON,
                    "createTexture: busy (H2)",
                    "budget: limit",
                    "budget: pause",
                    "readPixels: region exceeds # bytes per call")));
        List<DynamicTest> tests = new ArrayList<>();
        for (final Part part : PARTS) {
            for (int i = 0; i < part.sequences; i++) {
                final long seed = sequenceSeed(SEED, part.ordinal, i);
                final int sequence = i;
                tests.add(
                    DynamicTest.dynamicTest(
                        String.format(Locale.ROOT, "%s #%d, seed %016X", part.name, i, seed),
                        () -> run(part, sequence, seed)));
            }
            tests.add(DynamicTest.dynamicTest(part.name + ": coverage", () -> coverage(part)));
        }
        return tests;
    }

    /** One sequence: OPS ops, each compared, a deep check every part.deepEvery ops and at the end. */
    private static void run(Part part, int sequence, long seed) {
        long started = System.nanoTime();
        ModelBridge bridge = new ModelBridge(part.caps, seed, part.coverage);
        int op = 0;
        try {
            for (; op < OPS; op++) {
                bridge.step();
                if ((op + 1) % part.deepEvery == 0) {
                    bridge.deepCheck();
                }
            }
            bridge.deepCheck();
        } catch (Throwable e) {
            throw new AssertionError(
                String.format(
                    Locale.ROOT,
                    "%s sequence %d (seed %016X; -Popengpu.model.seed=%d), at op %d: %s%n  ops before it: %s",
                    part.name,
                    sequence,
                    seed,
                    SEED,
                    op,
                    e.getMessage(),
                    String.join("\n    ", bridge.recent())),
                e);
        } finally {
            part.nanos += System.nanoTime() - started;
            part.ran++;
        }
    }

    private static void coverage(Part part) {
        Assumptions.assumeTrue(CASES >= DEFAULT_CASES, "a run of fewer than " + DEFAULT_CASES + " sequences");
        List<String> missing = new ArrayList<>();
        for (String tag : part.required) {
            if (!part.coverage.contains(tag)) {
                missing.add(tag);
            }
        }
        Assertions.assertTrue(
            missing.isEmpty(),
            part.name + "'s sequences never reached " + missing + "; reached " + part.coverage);
    }

    @AfterAll
    static void report() {
        long total = 0;
        StringBuilder b = new StringBuilder("DeviceModelTest, seed " + SEED + ":");
        for (Part part : PARTS) {
            total += part.nanos;
            b.append(
                String.format(
                    Locale.ROOT,
                    "%n  %-12s %4d sequences, %6d ops, %6.2f s; %d outcomes",
                    part.name,
                    part.ran,
                    part.ran * OPS,
                    part.nanos / 1e9,
                    part.coverage.size()));
        }
        b.append(String.format(Locale.ROOT, "%n  total %.2f s", total / 1e9));
        System.out.println(b);
    }

    /** SplitMix64 of the run's seed, the part and the sequence: independent streams per sequence. */
    static long sequenceSeed(long seed, int part, int sequence) {
        long z = seed * 0x9E3779B97F4A7C15L + (long) part * 0xBF58476D1CE4E5B9L + sequence * 0x94D049BB133111EBL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static List<String> plus(List<String> base, String... more) {
        List<String> out = new ArrayList<>(base);
        out.addAll(Arrays.asList(more));
        return out;
    }
}
