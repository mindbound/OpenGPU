package io.github.mindbound.opengpu.gltestkit.scenario;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.ErrorCode;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.cmd.Opcodes;
import io.github.mindbound.opengpu.core.cmd.Utf8;
import io.github.mindbound.opengpu.core.cmd.Validation;
import io.github.mindbound.opengpu.core.cmd.Validator;
import io.github.mindbound.opengpu.core.colour.Colours;
import io.github.mindbound.opengpu.core.fixtures.MapLiveView;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
import io.github.mindbound.opengpu.core.font.GlyphTable;
import io.github.mindbound.opengpu.core.font.GlyphTables;
import io.github.mindbound.opengpu.core.render.DeviceKey;
import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.gl.QuadMode;
import io.github.mindbound.opengpu.gl.QueryPolicy;
import io.github.mindbound.opengpu.gl.UploadProbe;
import io.github.mindbound.opengpu.gltestkit.host.GlTestHost;

/**
 * Scenario 6 (S8 plan §4.3; 28:633-635, :655; S8 amendments E, A10; H71): the real font 0 (misc-fixed 6x13,
 * {@code GlyphTables.font0()}) through {@code TEXT} on {@link GlTestHost}, against
 * {@code TierEOracle(surface, GlyphTables.font0())} byte for byte, on T2 and T3, in both quad modes. Every codepoint
 * {@code TEXT} accepts is drawn: the 4 120 glyphs of the table's 4 121 that are no control character (U+0000 is one,
 * 28:647, so H71 leaves its cell to GlyphAtlasLayoutTest), then 20 codepoints without a glyph, of 2, 3 and 4 UTF-8
 * bytes, which draw U+FFFD's rows (28:632, :650). Each frame is a screen of one-line {@code TEXT} records, 53 cells a
 * line on T2 and 106 on T3 (28 §7.3), with each line's own fg and bg; the codepoints run over as many frames as they
 * need, once opaque over a {@code CLEAR} and once {@code TRANSPARENT_BG} over striped fills, so every clear bit keeps a
 * pixel that differs from bg: the stripes' colour seeds are even and the lines' bg seeds odd, which on index8
 * {@link #colour} keeps apart, and the scenario checks every stripe under a line's text against its bg as surface
 * values, on rgb565 too (S8 review TE-4). A last frame holds the 1 024-byte record (28:465, n = 1 024 accepted, 1 025
 * {@code TEXT_TOO_LONG}) twice, at x -2 and at x -1 800, clipped at both edges. Every segment goes through the real
 * {@link Validator} on a {@link MapLiveView} with the tier's frame caps and must be accepted. The count is the number
 * of placements per surface and mode: 2 on the compatibility leg, 1 on each GLSM leg ({@code -Popengpu.gl.cases}, at
 * most {@link #PLACEMENTS}); placement p puts the first cell at (-(p mod 6), 7p mod 13), so from placement 1 on the
 * left column and the bottom line clip. Kills plan §4.7's "glyph rows flipped" with the goldens.
 */
public final class TextFont0Scenario extends Scenario {

    /** 28:632: the table's glyphs. */
    static final int GLYPHS = 4121;
    /** H71: the glyphs TEXT can draw, all but U+0000. */
    static final int DRAWABLE = 4120;
    /** Codepoints font 0 has no glyph for, none a control character: 2-, 3- and 4-byte UTF-8 (28 §7.2). */
    static final int[] WITHOUT_GLYPH = { 0x0378, 0x0530, 0x07C0, 0x0800, 0x0F00, 0x1000, 0x3042, 0x4E00, 0xA000, 0xAC00,
        0xF8FF, 0xFFFE, 0xFFFF, 0x10000, 0x1D11E, 0x1F4A9, 0x1F600, 0x20000, 0xE0001, 0x10FFFF };
    /** Distinct placements: 6 cell columns by 13 cell rows. */
    static final int PLACEMENTS = 78;
    /** The background stripes' width. */
    static final int STRIPE = 7;

    @Override
    protected void run(ScenarioRun run) {
        GlyphTable font0 = GlyphTables.font0();
        List<Integer> codepoints = codepoints(run, font0);
        int placements = Math.max(1, Math.min(PLACEMENTS, run.cases(2, 1)));
        int frames = 0;
        int[] stripes = { 0 };
        for (QuadMode mode : QuadMode.values()) {
            // The readback comes at the next pass: two pumps a frame (amendment C's forced fetch)
            GlTestHost host = run.host(
                font0,
                mode,
                GlTestHost.defaultOptions()
                    .forceFetchAfterPasses(1),
                QueryPolicy.DRIVER,
                UploadProbe.NONE);
            for (int tier = 2; tier <= 3; tier++) {
                frames += surface(run, host, font0, codepoints, SurfaceDesc.forTier(tier), placements, mode, stripes);
            }
            run.close(host);
        }
        run.log(
            codepoints.size() + " codepoints at "
                + placements
                + " placement(s), opaque and TRANSPARENT_BG, in "
                + frames
                + " frames, "
                + stripes[0]
                + " stripes under TRANSPARENT_BG text checked against the line's bg");
        run.check(stripes[0] > 0, "no stripe under TRANSPARENT_BG text was checked");
    }

    /** The 4 120 drawable glyphs ascending, then the 20 without one; each list checked against 28:632 and H71. */
    static List<Integer> codepoints(ScenarioRun run, GlyphTable font0) {
        run.check(font0.size() == GLYPHS, "font 0 has " + font0.size() + " glyphs, 28:632 says " + GLYPHS);
        run.check(
            font0.cellWidth() == 6 && font0.cellHeight() == 13,
            "font 0's cell is " + font0.cellWidth() + "x" + font0.cellHeight() + ", 28 §7.3 says 6x13");
        List<Integer> out = new ArrayList<>();
        List<String> controls = new ArrayList<>();
        for (int i = 0; i < font0.size(); i++) {
            int cp = font0.codepointAt(i);
            if (Utf8.isControl(cp)) {
                controls.add(String.format(Locale.ROOT, "U+%04X", cp));
            } else {
                out.add(cp);
            }
        }
        run.check(
            out.size() == DRAWABLE && controls.size() == 1 && "U+0000".equals(controls.get(0)),
            "font 0's control characters are " + controls + ", H71 says U+0000 only");
        for (int cp : WITHOUT_GLYPH) {
            boolean ok = font0.indexOf(cp) == GlyphTable.MISSING && !Utf8.isControl(cp)
                && Character.isValidCodePoint(cp)
                && (cp < 0xD800 || cp > 0xDFFF);
            run.check(ok, String.format(Locale.ROOT, "U+%04X is no codepoint without a glyph that TEXT accepts", cp));
            out.add(cp);
        }
        return out;
    }

    /**
     * One surface on the host: every placement opaque and transparent, then the 1 024-byte record. The frames run;
     * stripes[0] counts the stripes checked under TRANSPARENT_BG text.
     */
    private static int surface(ScenarioRun run, GlTestHost host, GlyphTable font0, List<Integer> codepoints,
        SurfaceDesc surface, int placements, QuadMode mode, int[] stripes) {
        FrameLimits limits = FrameLimits.forTier(surface.tier);
        MapLiveView view = new MapLiveView(surface, limits);
        TierEOracle oracle = new TierEOracle(surface, font0);
        DeviceKey key = run.key();
        Deliveries deliveries = new Deliveries();
        host.open(key, surface, deliveries);
        String where = mode + " " + surface;
        int cells = surface.width / font0.cellWidth();
        int lines = surface.height / font0.cellHeight();
        long frameId = 0;
        for (int p = 0; p < placements; p++) {
            int x0 = -(p % 6);
            int y0 = 7 * p % 13;
            for (int transparent = 0; transparent <= 1; transparent++) {
                int next = 0;
                for (int screen = 0; next < codepoints.size(); screen++) {
                    List<byte[]> records = new ArrayList<>();
                    records.add(background(surface, transparent == 1, screen));
                    for (int line = 0; line < lines && next < codepoints.size(); line++) {
                        int n = Math.min(cells, codepoints.size() - next);
                        byte[] utf8 = utf8(codepoints, next, n);
                        int y = y0 + font0.cellHeight() * line;
                        // An odd seed, the stripes' are even (TE-4)
                        int bg = colour(surface, 2 * (7 * line + 2 * screen + 101) + 1);
                        if (transparent == 1) {
                            stripes[0] += stripesUnder(
                                run,
                                surface,
                                screen,
                                x0,
                                y,
                                n * font0.cellWidth(),
                                font0.cellHeight(),
                                bg,
                                where + " placement " + p + " screen " + screen + " line " + line);
                        }
                        records.add(
                            TestEncoder.text(
                                x0,
                                y,
                                colour(surface, 3 * line + screen + 1),
                                bg,
                                transparent == 1 ? Opcodes.TEXT_TRANSPARENT_BG : 0,
                                0,
                                utf8));
                        next += n;
                    }
                    String what = String.format(
                        Locale.ROOT,
                        "%s placement %d %s screen %d (to codepoint %d of %d)",
                        where,
                        p,
                        transparent == 1 ? "TRANSPARENT_BG" : "opaque",
                        screen,
                        next,
                        codepoints.size());
                    if (!frame(run, host, key, view, oracle, deliveries, ++frameId, records, what)) {
                        host.release(key);
                        return (int) frameId;
                    }
                }
            }
        }
        // 28:465: n = 1 024 is TEXT's longest string, clipped on both sides; 1 025 is TEXT_TOO_LONG
        byte[] longest = longest();
        run.check(longest.length == Opcodes.TEXT_MAX_BYTES, "the longest record holds " + longest.length + " bytes");
        byte[] tooLong = new byte[Opcodes.TEXT_MAX_BYTES + 1];
        System.arraycopy(longest, 0, tooLong, 0, longest.length);
        tooLong[Opcodes.TEXT_MAX_BYTES] = 'x';
        Validation rejected = Validator.validate(
            TestEncoder.segment(TestEncoder.text(0, 0, colour(surface, 1), colour(surface, 2), 0, 0, tooLong)),
            view);
        run.check(
            !rejected.isAccepted() && rejected.error().code == ErrorCode.TEXT_TOO_LONG,
            where + ": a 1 025-byte TEXT gave " + rejected + ", 28:465 says TEXT_TOO_LONG");
        List<byte[]> records = new ArrayList<>();
        records.add(background(surface, true, 7));
        // Odd bg seeds, as the lines' (TE-4)
        records.add(TestEncoder.text(-2, 2, colour(surface, 5), colour(surface, 7), 0, 0, longest));
        String longestText = new String(longest, StandardCharsets.UTF_8);
        int longestCells = longestText.codePointCount(0, longestText.length());
        stripes[0] += stripesUnder(
            run,
            surface,
            7,
            -1800,
            40,
            longestCells * font0.cellWidth(),
            font0.cellHeight(),
            colour(surface, 11),
            where + " the 1 024-byte record");
        records.add(
            TestEncoder
                .text(-1800, 40, colour(surface, 9), colour(surface, 11), Opcodes.TEXT_TRANSPARENT_BG, 0, longest));
        frame(run, host, key, view, oracle, deliveries, ++frameId, records, where + " the 1 024-byte record");
        host.release(key);
        return (int) frameId;
    }

    /**
     * One frame of one segment: validated by the real Validator, offered, pumped until idle, and its one readback
     * compared with the oracle after the same segment. False when the scenario should stop on this surface.
     */
    private static boolean frame(ScenarioRun run, GlTestHost host, DeviceKey key, MapLiveView view, TierEOracle oracle,
        Deliveries deliveries, long frameId, List<byte[]> records, String what) {
        byte[] segment = TestEncoder.segment(records.toArray(new byte[0][]));
        Validation v = Validator.validate(segment, view);
        if (!run.check(v.isAccepted(), what + ": the validator rejected the segment: " + v)) {
            return false;
        }
        oracle.runSegment(segment);
        int before = deliveries.count();
        host.offer(key, new FrameRecord(1, frameId, new byte[][] { segment }, v.totals()));
        host.pumpUntilIdle(key);
        Deliveries.Delivery last = deliveries.last();
        if (!run.check(
            deliveries.count() == before + 1 && last.epoch == 1 && last.frameId == frameId,
            what + ": one readback of e1 #"
                + frameId
                + ", got "
                + deliveries.all()
                    .subList(before, deliveries.count()))) {
            return false;
        }
        return run.compare(what, oracle.surface(), oracle.pixels(), last.pixels);
    }

    /** Opaque: a CLEAR; transparent: vertical stripes 7 px wide in colours of their own, under the text. */
    private static byte[] background(SurfaceDesc surface, boolean transparent, int screen) {
        if (!transparent) {
            return TestEncoder.clear(colour(surface, 200 + screen));
        }
        List<byte[]> stripes = new ArrayList<>();
        for (int x = 0, i = 0; x < surface.width; x += STRIPE, i++) {
            stripes.add(TestEncoder.fill(x, 0, STRIPE, surface.height, stripe(surface, i, screen)));
        }
        return TestEncoder.concat(stripes.toArray(new byte[0][]));
    }

    /** Stripe i's colour on a screen: an even seed, the lines' bg seeds are odd (S8 review TE-4). */
    static int stripe(SurfaceDesc surface, int i, int screen) {
        return colour(surface, 2 * (11 * i + 13 * screen + 17));
    }

    /**
     * S8 review TE-4: each stripe under a TRANSPARENT_BG text's cells, from x for width pixels and from y for height
     * rows, clipped to the surface, has a surface value (28 §3.4) other than the text's bg, so a clear bit drawn as bg
     * would show; one that has it is a failure. Returns the stripes compared.
     */
    static int stripesUnder(ScenarioRun run, SurfaceDesc surface, int screen, int x, int y, int width, int height,
        int bg, String what) {
        if (y >= surface.height || y + height <= 0) {
            return 0;
        }
        int from = Math.max(0, x);
        int to = Math.min(surface.width, x + width);
        int compared = 0;
        for (int i = from / STRIPE; i * STRIPE < to; i++) {
            compared++;
            int value = Colours.sv(surface.format, stripe(surface, i, screen));
            run.check(
                value != Colours.sv(surface.format, bg),
                String.format(
                    Locale.ROOT,
                    "%s: stripe %d (x %d-%d) has the bg's surface value 0x%X, so a clear bit drawn as bg would not show",
                    what,
                    i,
                    i * STRIPE,
                    i * STRIPE + STRIPE - 1,
                    value));
        }
        return compared;
    }

    /** A valid colour of the surface's format (28 §3.4) for a seed: an index, or opaque 0xFFRRGGBB. */
    static int colour(SurfaceDesc surface, int seed) {
        if (surface.format == Formats.INDEX8) {
            return seed * 37 + 11 & 0xFF;
        }
        int v = seed * 0x9E3779B1;
        return 0xFF000000 | v >>> 8 & 0xFFFFFF;
    }

    /** The UTF-8 bytes of n codepoints from index from. */
    private static byte[] utf8(List<Integer> codepoints, int from, int n) {
        StringBuilder s = new StringBuilder();
        for (int i = from; i < from + n; i++) {
            s.appendCodePoint(codepoints.get(i));
        }
        return s.toString()
            .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Exactly {@code TEXT_MAX_BYTES} bytes of UTF-8: 341 box-drawing and block codepoints of 3 bytes (U+2500-259F,
     * which font 0 inks, 28:632) and one 'Z'.
     */
    static byte[] longest() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < 341; i++) {
            byte[] b = new String(Character.toChars(0x2500 + i % 0xA0)).getBytes(StandardCharsets.UTF_8);
            out.write(b, 0, b.length);
        }
        out.write('Z');
        return out.toByteArray();
    }
}
