package io.github.mindbound.opengpu.core.cmd.fuzz;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;

import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.cmd.Validation;
import io.github.mindbound.opengpu.core.cmd.Validator;
import io.github.mindbound.opengpu.core.fixtures.MapLiveView;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.TestFrame;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
import io.github.mindbound.opengpu.core.fixtures.gen.GeneratedCase;
import io.github.mindbound.opengpu.core.fixtures.gen.SegmentGenerator;

/**
 * 28 §11.3's oracle properties over {@link CodecFuzzTest}'s case stream (same seed, same cases, same mutations):
 * <ul>
 * <li>P4: the tier-E oracle, with the case's textures created and filled, runs every accepted segment without an
 * exception, so it writes and reads in bounds only (its accessor throws otherwise). Every accepted case on a surface of
 * at most 4 096 pixels, and every 50th accepted one on T2 and T3.</li>
 * <li>P5: splitting a clean case's records over k = 2-5 segments at random record boundaries gives the same oracle
 * output as the one segment. Each extra segment adds 9 header and {@code END} bytes, and a clean case's caps leave
 * room for them, so {@code n + 9(k - 1) <= frameBytes} is asserted, not skipped (S6 plan amendment E); every piece is
 * accepted through {@link TestFrame}, and the pieces' records and class totals sum to the one segment's. Every clean
 * case with two records or more on a small surface, and every 10th on T2 and T3.</li>
 * </ul>
 */
class OracleFuzzTest {

    private static final int SMALL = 4096;

    @TestFactory
    @Timeout(120)
    List<DynamicTest> theOracleUnderMutation() {
        CodecFuzzTest.Failures p4 = new CodecFuzzTest.Failures("P4, the oracle runs every accepted segment in bounds");
        CodecFuzzTest.Failures p5 = new CodecFuzzTest.Failures("P5, a frame split over segments keeps its pixels");
        int p4Runs = 0;
        int p5Runs = 0;
        int largeAccepted = 0;
        int largeClean = 0;
        long started = System.nanoTime();
        for (int i = 0; i < CodecFuzzTest.CASES; i++) {
            GeneratedCase c = SegmentGenerator.generate(SegmentGenerator.caseSeed(CodecFuzzTest.SEED, i));
            byte[] s = Mutator.submitted(c);
            boolean small = c.surface()
                .pixelCount() <= SMALL;
            Validation v = Validator.validate(s, c.view(), c.open());
            if (v.isAccepted() && (small || largeAccepted++ % 50 == 0)) {
                p4Runs++;
                try {
                    c.oracle(SyntheticGlyphs.TABLE)
                        .runSegment(s);
                } catch (Throwable e) {
                    p4.add(i, c, s, "the oracle threw " + e);
                }
            }
            if (c.isClean() && c.recordCount() >= 2 && (small || largeClean++ % 10 == 0)) {
                p5Runs++;
                String failure;
                try {
                    failure = splitKeepsPixels(c);
                } catch (Throwable e) {
                    failure = "threw " + e;
                }
                if (failure != null) {
                    p5.add(i, c, c.segment(), failure);
                }
            }
        }
        long millis = (System.nanoTime() - started) / 1000000;
        System.out.printf(
            Locale.ROOT,
            "OracleFuzzTest: %d cases from seed %d in %d ms: P4 ran %d accepted segments, P5 split %d clean frames%n",
            CodecFuzzTest.CASES,
            CodecFuzzTest.SEED,
            millis,
            p4Runs,
            p5Runs);
        final int p4Ran = p4Runs;
        final int p5Ran = p5Runs;
        List<DynamicTest> tests = new ArrayList<>();
        tests.add(DynamicTest.dynamicTest(p4.property, () -> {
            Assertions.assertTrue(p4.isEmpty(), p4::report);
            Assumptions.assumeTrue(CodecFuzzTest.CASES >= 1000, "a run of fewer than 1 000 cases");
            Assertions.assertTrue(p4Ran >= CodecFuzzTest.CASES / 20, "P4 ran on " + p4Ran + " cases only");
        }));
        tests.add(DynamicTest.dynamicTest(p5.property, () -> {
            Assertions.assertTrue(p5.isEmpty(), p5::report);
            Assumptions.assumeTrue(CodecFuzzTest.CASES >= 1000, "a run of fewer than 1 000 cases");
            Assertions.assertTrue(p5Ran >= CodecFuzzTest.CASES / 50, "P5 ran on " + p5Ran + " cases only");
        }));
        return tests;
    }

    /** P5 for one clean case: null if it holds, otherwise what differs. */
    private static String splitKeepsPixels(GeneratedCase c) {
        byte[] whole = c.segment();
        int[] offsets = c.recordOffsets();
        int records = offsets.length;
        MapLiveView view = c.view();
        Validation single = Validator.validate(whole, view, FrameTotals.EMPTY);
        if (!single.isAccepted()) {
            return "the clean segment is rejected: " + single.error()
                .message();
        }
        // k - 1 distinct cut points among the record boundaries 1..records-1
        SplittableRandom r = new SplittableRandom(c.seed() ^ 0x5DEECE66DL);
        int k = Math.min(records, r.nextInt(2, 6));
        boolean[] cut = new boolean[records];
        for (int cuts = 0; cuts < k - 1;) {
            int at = r.nextInt(1, records);
            if (!cut[at]) {
                cut[at] = true;
                cuts++;
            }
        }
        List<byte[]> pieces = new ArrayList<>();
        int from = 0;
        for (int at = 1; at <= records; at++) {
            if (at == records || cut[at]) {
                int start = offsets[from];
                int stop = at == records ? whole.length - 1 : offsets[at];
                pieces.add(TestEncoder.segment(Arrays.copyOfRange(whole, start, stop)));
                from = at;
            }
        }
        if (pieces.size() != k) {
            return pieces.size() + " pieces for k = " + k;
        }
        long bytes = 0;
        for (byte[] piece : pieces) {
            bytes += piece.length;
        }
        if (bytes != whole.length + 9L * (k - 1)) {
            return "the pieces are " + bytes + " B for " + whole.length + " + 9 * " + (k - 1);
        }
        if (bytes > c.limits().frameBytes) {
            return "the pieces' " + bytes + " B exceed the clean case's frame cap " + c.limits().frameBytes;
        }
        TestFrame frame = new TestFrame(view);
        for (int p = 0; p < pieces.size(); p++) {
            Validation v = frame.submit(pieces.get(p));
            if (!v.isAccepted()) {
                return "piece " + p
                    + " of "
                    + k
                    + " is rejected: "
                    + v.error()
                        .message()
                    + " in "
                    + hex(pieces.get(p));
            }
        }
        FrameTotals sum = frame.totals();
        FrameTotals one = single.totals();
        if (sum.records != one.records || sum.clearPixels != one.clearPixels
            || sum.fillPixels != one.fillPixels
            || sum.copyPixels != one.copyPixels
            || sum.setPixels != one.setPixels
            || sum.bytes != bytes) {
            return "the pieces total " + sum + ", the segment " + one;
        }
        TierEOracle once = c.oracle(SyntheticGlyphs.TABLE);
        once.runSegment(whole);
        TierEOracle split = c.oracle(SyntheticGlyphs.TABLE);
        split.runFrame(frame.seal());
        byte[] expected = once.pixels();
        byte[] actual = split.pixels();
        if (!Arrays.equals(expected, actual)) {
            int at = 0;
            while (expected[at] == actual[at]) {
                at++;
            }
            return "split into " + k + " the pixels differ first at byte " + at;
        }
        return null;
    }

    private static String hex(byte[] s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(s, 0, Math.min(s.length, 256));
        return TestEncoder.toHex(out.toByteArray()) + (s.length > 256 ? " ..." : "");
    }
}
