package io.github.mindbound.opengpu.core.cmd.fuzz;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.cmd.ErrorCode;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.cmd.NaiveBridge;
import io.github.mindbound.opengpu.core.cmd.StreamError;
import io.github.mindbound.opengpu.core.cmd.Validation;
import io.github.mindbound.opengpu.core.cmd.Validator;
import io.github.mindbound.opengpu.core.fixtures.MapLiveView;
import io.github.mindbound.opengpu.core.fixtures.ReEncoder;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.gen.GeneratedCase;
import io.github.mindbound.opengpu.core.fixtures.gen.SegmentGenerator;
import io.github.mindbound.opengpu.core.naive.NaiveValidator;

/**
 * 28 §11.3's seeded mutation fuzzer over the validator: P1 (it never throws and returns only 28 §3.6's codes, at
 * offsets that fit them), P2 (it agrees with the naive validator in acceptance, code, offset and {@code <OP>}, in row
 * 21's string index, and in an accepted segment's records and class totals) and P3 (every accepted segment is
 * canonical), with the per-code tallies, in one pass over each case (S6 plan amendment E). Every clean case must be
 * accepted. A run takes base seed 28 and 100 000 cases unless the {@code opengpu.fuzz.seed} and
 * {@code opengpu.fuzz.cases} system properties say otherwise (the build forwards the Gradle properties of the same
 * names); case i is {@code SegmentGenerator.generate(SegmentGenerator.caseSeed(seed, i))} as {@link Mutator} submits
 * it, and a failure prints its index and seed. Coverage of every code is asserted on runs of 100 000 cases or more.
 * Linearity holds by construction (the validator's one pass) and shows in the bench's per-size figures; nothing here
 * is timed.
 */
class CodecFuzzTest {

    static final long SEED = Long.getLong("opengpu.fuzz.seed", SegmentGenerator.DEFAULT_SEED);
    static final int CASES = Integer.getInteger("opengpu.fuzz.cases", 100000);
    /** The run size at which every code must occur (S6 plan amendment E). */
    static final int FULL_RUN = 100000;

    @TestFactory
    @Timeout(120)
    List<DynamicTest> theValidatorUnderMutation() {
        Failures p1 = new Failures("P1, no exception and 28 §3.6's codes at offsets that fit");
        Failures p2 = new Failures("P2, agreement with the naive validator");
        Failures p3 = new Failures("P3, accepted segments are canonical");
        Failures clean = new Failures("clean cases are accepted");
        final Map<ErrorCode, Integer> codes = new EnumMap<>(ErrorCode.class);
        int accepted = 0;
        int acceptedWith5 = 0;
        int cleanCases = 0;
        long started = System.nanoTime();
        for (int i = 0; i < CASES; i++) {
            GeneratedCase c = SegmentGenerator.generate(SegmentGenerator.caseSeed(SEED, i));
            byte[] s = Mutator.submitted(c);
            byte[] copy = s.clone();
            MapLiveView view = c.view();
            FrameTotals open = c.open();
            Validation v;
            try {
                v = Validator.validate(s, view, open);
            } catch (Throwable e) {
                p1.add(i, c, s, "threw " + e);
                continue;
            }
            String bad = p1Violation(s, v);
            if (bad == null && !Arrays.equals(copy, s)) {
                bad = "the validator wrote the segment";
            }
            if (bad != null) {
                p1.add(i, c, s, bad + ": " + v);
                continue;
            }
            // P2
            try {
                NaiveValidator.Result naive = NaiveBridge.naive(s, view, open);
                String disagreement = NaiveBridge.disagreement(v, naive);
                if (disagreement != null) {
                    p2.add(i, c, s, disagreement);
                }
            } catch (Throwable e) {
                p2.add(i, c, s, "the naive validator threw " + e);
            }
            if (v.isAccepted()) {
                accepted++;
                if (v.totals().records >= 5) {
                    acceptedWith5++;
                }
                // P3
                try {
                    byte[] again = ReEncoder.reencode(s);
                    if (!Arrays.equals(again, s)) {
                        p3.add(i, c, s, "re-encoded as " + TestEncoder.toHex(again));
                    }
                } catch (Throwable e) {
                    p3.add(i, c, s, "re-encoding threw " + e);
                }
            } else {
                ErrorCode code = v.error().code;
                Integer n = codes.get(code);
                codes.put(code, n == null ? 1 : n + 1);
            }
            if (c.isClean()) {
                cleanCases++;
                if (!v.isAccepted()) {
                    clean.add(
                        i,
                        c,
                        s,
                        "rejected: " + v.error()
                            .message());
                }
            }
        }
        long millis = (System.nanoTime() - started) / 1000000;
        final int acceptedCases = accepted;
        final int acceptedLong = acceptedWith5;
        System.out.printf(
            Locale.ROOT,
            "CodecFuzzTest: %d cases from seed %d in %d ms: %d clean, %d accepted (%.1f %%), %d of them with 5 or more"
                + " records; rejections by code %s%n",
            CASES,
            SEED,
            millis,
            cleanCases,
            accepted,
            100.0 * accepted / Math.max(1, CASES),
            acceptedWith5,
            codes);
        List<DynamicTest> tests = new ArrayList<>();
        tests.add(property(p1));
        tests.add(property(p2));
        tests.add(property(p3));
        tests.add(property(clean));
        tests.add(DynamicTest.dynamicTest("at least 15 % of the cases are accepted", () -> {
            Assumptions.assumeTrue(CASES >= 1000, "a run of fewer than 1 000 cases");
            Assertions
                .assertTrue(acceptedCases * 100L >= 15L * CASES, acceptedCases + " of " + CASES + " cases accepted");
        }));
        tests.add(DynamicTest.dynamicTest("at least 1 000 accepted cases have 5 or more records", () -> {
            Assumptions.assumeTrue(CASES >= FULL_RUN, "a run of fewer than " + FULL_RUN + " cases");
            Assertions.assertTrue(acceptedLong >= 1000, acceptedLong + " accepted cases with 5 or more records");
        }));
        tests.add(DynamicTest.dynamicTest("every code of 28 §3.6 occurs", () -> {
            Assumptions.assumeTrue(CASES >= FULL_RUN, "a run of fewer than " + FULL_RUN + " cases");
            List<ErrorCode> missing = new ArrayList<>();
            for (ErrorCode code : ErrorCode.values()) {
                if (!codes.containsKey(code)) {
                    missing.add(code);
                }
            }
            Assertions.assertTrue(missing.isEmpty(), "never reached: " + missing + "; reached " + codes);
        }));
        return tests;
    }

    /** P1's checks of one result, or null when it holds. */
    static String p1Violation(byte[] s, Validation v) {
        if (v == null) {
            return "null result";
        }
        int n = s.length;
        if (v.isAccepted()) {
            if (v.error() != null || v.totals() == null) {
                return "accepted with an error";
            }
            if (v.totals().bytes != n) {
                return "accepted with " + v.totals().bytes + " bytes for " + n;
            }
            return null;
        }
        StreamError e = v.error();
        if (e == null || e.code == null || e.reason == null || e.reason.isEmpty()) {
            return "rejected without a code and a reason";
        }
        if (!e.message()
            .equals(e.offset + ": " + e.op() + ": " + e.reason)) {
            return "message " + e.message();
        }
        int offset = e.offset;
        switch (e.code) {
            case OVER_SUBMIT_BYTES:
            case OVER_FRAME_BYTES:
            case TRUNCATED_HEADER:
            case BAD_MAGIC:
                return offset == 0 && e.opcode == StreamError.NO_OPCODE ? null : "offset " + offset;
            case BAD_VERSION:
                return offset == 4 && e.opcode == StreamError.NO_OPCODE ? null : "offset " + offset;
            case BAD_FLAGS:
                return offset == 5 && e.opcode == StreamError.NO_OPCODE ? null : "offset " + offset;
            case BAD_RESERVED:
                return offset == 6 && e.opcode == StreamError.NO_OPCODE ? null : "offset " + offset;
            case MISSING_END:
                return offset == n && e.opcode == 0xFF ? null : "offset " + offset + " of " + n;
            case TRAILING_BYTES:
                return offset >= 8 && offset < n - 1 && (s[offset] & 0xFF) == 0xFF && e.opcode == 0xFF ? null
                    : "offset " + offset + " of " + n;
            default:
                if (offset < 8 || offset >= n || e.opcode != (s[offset] & 0xFF)) {
                    return "offset " + offset + " of " + n + " with opcode " + e.opcode;
                }
                if (e.code == ErrorCode.UNKNOWN_OPCODE && !e.op()
                    .startsWith("0x")) {
                    return "unknown opcode named " + e.op();
                }
                return null;
        }
    }

    private static DynamicTest property(final Failures failures) {
        return DynamicTest
            .dynamicTest(failures.property, () -> Assertions.assertTrue(failures.isEmpty(), failures::report));
    }

    /** One property's failures over a run; the first few are kept with what reproduces them. */
    static final class Failures {

        private static final int KEPT = 5;
        private static final int HEX_BYTES = 512;

        final String property;
        private final List<String> kept = new ArrayList<>();
        private int count;

        Failures(String property) {
            this.property = property;
        }

        void add(int index, GeneratedCase c, byte[] submitted, String what) {
            count++;
            if (kept.size() < KEPT) {
                kept.add(
                    String.format(
                        Locale.ROOT,
                        "case %d (seed %d, %s): %s%n  %s, %s, open %s, textures %s%n  submitted %d B: %s",
                        index,
                        c.seed(),
                        c.isClean() ? "clean" : "rough",
                        what,
                        c.surface(),
                        c.limits(),
                        c.open(),
                        c.textures(),
                        submitted.length,
                        hex(submitted)));
            }
        }

        boolean isEmpty() {
            return count == 0;
        }

        int count() {
            return count;
        }

        String report() {
            return property + ": "
                + count
                + " failing cases, the first "
                + kept.size()
                + ":\n"
                + String.join("\n", kept);
        }

        private static String hex(byte[] s) {
            if (s.length <= HEX_BYTES) {
                return TestEncoder.toHex(s);
            }
            return TestEncoder.toHex(Arrays.copyOf(s, HEX_BYTES)) + " ... (" + (s.length - HEX_BYTES) + " more)";
        }
    }
}
