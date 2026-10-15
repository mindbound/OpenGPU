package io.github.mindbound.opengpu.core.cmd.bench;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.github.mindbound.opengpu.core.cmd.ErrorCode;
import io.github.mindbound.opengpu.core.cmd.Expectations;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.cmd.StreamError;
import io.github.mindbound.opengpu.core.cmd.bench.ValidatorWorkloads.Workload;

/**
 * Keeps the bench's loads, and the allocation test's, valid in CI: each has its stated length and is accepted with the
 * totals it was built for, or for {@code reject-last} rejected at its last record, through both validators.
 */
class ValidatorWorkloadsTest {

    @Test
    void eachLoadHasItsSizeAndOutcome() {
        List<Workload> all = ValidatorWorkloads.all();
        Assertions.assertEquals(12, all.size());
        ValidatorWorkloads.OneTextureView view = new ValidatorWorkloads.OneTextureView();
        for (int i = 0; i < all.size(); i++) {
            Workload w = all.get(i);
            Assertions.assertEquals(ValidatorWorkloads.SIZES[i / 4], w.segment.length, w.name);
            if (w.expected != null) {
                Expectations
                    .assertAccepted(w.segment, view, ValidatorWorkloads.liveHandles(), FrameTotals.EMPTY, w.expected);
            } else {
                StreamError e = Expectations.assertRejected(
                    w.segment,
                    view,
                    ValidatorWorkloads.liveHandles(),
                    FrameTotals.EMPTY,
                    ErrorCode.BAD_COLOUR,
                    w.rejectedAt,
                    "TEXT");
                Assertions.assertEquals(
                    w.rejectedAt + ": TEXT: fg 0x00336699: alpha must be 0xFF before blending (M1)",
                    e.message());
            }
        }
    }

    @Test
    void theLoadsAtTheSubmitCapAre28sFigures() {
        // The S6 plan's section 6.6: records is 2 047 FILL_RECT, 2 047 BLIT and two TEXT of n = 1 018, 4 096 records
        Workload records = ValidatorWorkloads.records(65536, false);
        Assertions.assertEquals(new FrameTotals(65536, 4096, 0, 2047 * 16, 2047 * 256, 2036 * 78), records.expected);
        // ascii-text: 62 TEXT of n = 1 024 and one of n = 968
        Assertions.assertEquals(
            new FrameTotals(65536, 63, 0, 0, 0, (62 * 1024 + 968) * 78),
            ValidatorWorkloads.asciiText(65536).expected);
        // cjk-text: 62 TEXT of 341 codepoints and two of 507 and 506 ASCII bytes
        Assertions.assertEquals(
            new FrameTotals(65536, 64, 0, 0, 0, (62 * 341 + 507 + 506) * 78),
            ValidatorWorkloads.cjkText(65536).expected);
        Workload reject = ValidatorWorkloads.records(65536, true);
        Assertions.assertEquals(65536 - 1 - 17 - 1018, reject.rejectedAt);
    }

    @Test
    void theAllocationTestsLoadsAreAccepted() {
        // clears: 4 096 CLEARs of the 640 x 400 surface; short-text: 93 rounds of 32 TEXTs, 160 string bytes and 80
        // codepoints a round
        Workload clears = ValidatorWorkloads.clears(4096);
        Assertions.assertEquals(new FrameTotals(20489, 4096, 4096L * 256000, 0, 0, 0), clears.expected);
        Workload text = ValidatorWorkloads.shortText();
        Assertions.assertEquals(new FrameTotals(65481, 2976, 0, 0, 0, 93 * 80 * 78), text.expected);
        ValidatorWorkloads.OneTextureView view = new ValidatorWorkloads.OneTextureView();
        for (Workload w : new Workload[] { clears, text }) {
            Expectations
                .assertAccepted(w.segment, view, ValidatorWorkloads.liveHandles(), FrameTotals.EMPTY, w.expected);
        }
    }
}
