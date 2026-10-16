package io.github.mindbound.opengpu.gltestkit.scenario;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
import io.github.mindbound.opengpu.core.fixtures.gen.GeneratedCase;
import io.github.mindbound.opengpu.core.fixtures.gen.SegmentGenerator;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * Scenario 2 (S8 plan §4.3), the test that discharges 28:938 (S8 amendments E): {@code SegmentGenerator.clean(seed,
 * surface)} cases on 8×6 and 17×13 index8, 9×7 and 16×16 rgb565, T2 and T3, in both quad modes, offered through
 * {@link GlTestHost}: a fresh key opened with a capturing sink, the case's textures as {@code TEX_CREATE} and a whole
 * {@code TEX_UPDATE}, its segment as {@code FRAME} 1, pumped until idle, and the delivered readback compared with
 * {@code GeneratedCase.oracle(SyntheticGlyphs.TABLE)} after the segment. Then 28:929's P5 on GL: {@code FRAME} 2 holds
 * a {@code CLEAR} to 0 and the same records split over 2-4 segments at random record boundaries, and must deliver
 * the same pixels. Per surface and mode: 48 cases on the compatibility leg, 16 on each GLSM leg,
 * {@code -Popengpu.gl.cases=N} on every leg. Seeds are {@code SegmentGenerator.caseSeed(938 + surface index, case)}.
 */
public final class GeneratedScenario extends Scenario {

    static final long BASE_SEED = 938;
    private static final SurfaceDesc[] SURFACES = { new SurfaceDesc(0, 8, 6, Formats.INDEX8),
        new SurfaceDesc(0, 17, 13, Formats.INDEX8), new SurfaceDesc(0, 9, 7, Formats.RGB565),
        new SurfaceDesc(0, 16, 16, Formats.RGB565), SurfaceDesc.forTier(2), SurfaceDesc.forTier(3) };

    @Override
    protected void run(ScenarioRun run) {
        int cases = run.cases(48, 16);
        int splits = 0;
        for (QuadMode mode : QuadMode.values()) {
            // The readback comes at the next pass: two pumps a frame (amendment C's forced fetch)
            GlTestHost host = run.host(
                mode,
                GlTestHost.defaultOptions()
                    .forceFetchAfterPasses(1));
            DeviceKey previous = null;
            for (int s = 0; s < SURFACES.length; s++) {
                for (int i = 0; i < cases; i++) {
                    long seed = SegmentGenerator.caseSeed(BASE_SEED + s, i);
                    GeneratedCase c = SegmentGenerator.clean(seed, SURFACES[s]);
                    String where = mode + " seed " + seed + " " + c.surface();
                    if (previous != null) {
                        host.release(previous);
                    }
                    DeviceKey key = run.key();
                    previous = key;
                    if (!runCase(run, host, key, c, where)) {
                        continue;
                    }
                    splits += c.recordCount() >= 2 ? 1 : 0;
                }
            }
            if (previous != null) {
                host.release(previous);
                host.pumpFrame();
            }
            run.check(
                host.ledger()
                    .isEmpty(ScenarioRun.SESSION),
                mode + ": ledger rows of released keys: "
                    + host.ledger()
                        .rows());
            run.close(host);
        }
        run.log(cases + " cases per surface and mode on " + SURFACES.length + " surfaces, " + splits + " split");
    }

    /** One case: the textures, FRAME 1 with the segment, FRAME 2 with P5's split. False when it stopped early. */
    private static boolean runCase(ScenarioRun run, GlTestHost host, DeviceKey key, GeneratedCase c, String where) {
        SurfaceDesc surface = c.surface();
        Deliveries deliveries = new Deliveries();
        host.open(key, surface, deliveries);
        for (GeneratedCase.Texture t : c.textures()) {
            host.offer(key, new TexCreateRecord(1, t.handle, t.width, t.height, t.format));
            host.offer(key, new TexUpdateRecord(1, t.handle, 0, 0, t.width, t.height, t.texels()));
        }
        byte[] segment = c.segment();
        TierEOracle oracle = c.oracle(SyntheticGlyphs.TABLE);
        oracle.runSegment(segment);
        byte[] expected = oracle.pixels();
        host.offer(key, new FrameRecord(1, 1, new byte[][] { segment }, FrameTotals.EMPTY));
        host.pumpUntilIdle(key);
        if (!run.check(deliveries.count() == 1, where + ": one readback of FRAME 1, got " + deliveries)) {
            return false;
        }
        Deliveries.Delivery first = deliveries.last();
        run.check(first.epoch == 1 && first.frameId == 1, where + ": the readback labelled e1 #1, got " + first);
        if (!run.compare(where + " FRAME 1", surface, expected, first.pixels)) {
            return false;
        }
        host.offer(key, new FrameRecord(1, 2, split(c, surface), FrameTotals.EMPTY));
        host.pumpUntilIdle(key);
        Deliveries.Delivery second = deliveries.last();
        if (run.check(
            deliveries.count() == 2 && second.frameId == 2,
            where + ": one readback of FRAME 2, got " + deliveries)) {
            run.compare(where + " FRAME 2 (P5's split)", surface, expected, second.pixels);
        }
        return true;
    }

    /**
     * A {@code CLEAR} to 0, then the case's records over k = 2-4 segments cut at random record boundaries, as
     * OracleFuzzTest's P5 cuts them (28:929); a case of one record or none stays whole after the clear.
     */
    static byte[][] split(GeneratedCase c, SurfaceDesc surface) {
        byte[] whole = c.segment();
        int[] offsets = c.recordOffsets();
        int records = offsets.length;
        List<byte[]> segments = new ArrayList<>();
        segments.add(TestEncoder.segment(TestEncoder.clear(surface.format == Formats.INDEX8 ? 0 : 0xFF000000)));
        if (records < 2) {
            segments.add(whole);
            return segments.toArray(new byte[0][]);
        }
        SplittableRandom r = new SplittableRandom(c.seed() ^ 0x5DEECE66DL);
        int k = Math.min(records, r.nextInt(2, 5));
        boolean[] cut = new boolean[records];
        for (int cuts = 0; cuts < k - 1;) {
            int at = r.nextInt(1, records);
            if (!cut[at]) {
                cut[at] = true;
                cuts++;
            }
        }
        int from = 0;
        for (int at = 1; at <= records; at++) {
            if (at == records || cut[at]) {
                int start = offsets[from];
                // The last piece ends before END, which TestEncoder.segment adds again
                int stop = at == records ? whole.length - 1 : offsets[at];
                segments.add(TestEncoder.segment(Arrays.copyOfRange(whole, start, stop)));
                from = at;
            }
        }
        return segments.toArray(new byte[0][]);
    }
}
