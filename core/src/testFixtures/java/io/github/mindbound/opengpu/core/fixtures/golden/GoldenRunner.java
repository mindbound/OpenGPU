package io.github.mindbound.opengpu.core.fixtures.golden;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.ErrorCode;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.cmd.TextureHandles;
import io.github.mindbound.opengpu.core.cmd.Validation;
import io.github.mindbound.opengpu.core.fixtures.MapLiveView;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TestFrame;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;

/**
 * Runs a golden case of {@link GoldenCases} (28 §11.4) and reports every mismatch. The device side is the commit
 * model: a {@link MapLiveView} whose handles follow the file, checked against 28 §6.1's FIFO allocator, with the
 * permissive caps {@link #LIMITS}, and a {@link TestFrame} that validates every segment with the main validator (every
 * golden is a positive case, so a rejection fails the case with its message). The records then go, in offered order,
 * to a {@link Target}: the tier-E oracle by default, or S8's GL host, which replays the same goldens. After each frame
 * the frame's validator totals are compared with the case's {@code totals}, and at each {@code expect} the target's
 * pixels are compared with the grid; a mismatch reports both grids with the differing pixels marked.
 * <p>
 * It checks what {@code docs/research/cmdstream-checks/oracle_goldens.py} checks: a texture's handle must be the one
 * the allocator gives next, fewer than 256 textures are live at a create (28 §6.2), sides are 1-1 024, an update or a
 * free names a live texture (28 §6.3, §6.4), an update's region is inside the texture, a frame has at least one
 * segment, totals follow a frame, and a case has at least one {@code expect}. A failed step ends the case; a totals or
 * grid mismatch is recorded and the case goes on, so one run shows every differing grid.
 */
public final class GoldenRunner {

    /**
     * Plan §6.4's caps: 28 §8.1's submit cap, and frame caps no golden reaches; the goldens test drawing, not
     * admission.
     */
    public static final FrameLimits LIMITS = new FrameLimits(FrameLimits.SUBMIT_BYTES, 1 << 20, 4096, Long.MAX_VALUE);

    /** {@code createTexture} needs fewer than 256 live textures (28 §6.2). */
    private static final int MAX_LIVE_TEXTURES = 256;
    /** The largest texture side (28 §6.2). */
    private static final int MAX_TEXTURE_SIDE = 1024;
    /** The differing pixels a grid mismatch lists one by one. */
    private static final int LISTED_PIXELS = 16;

    private GoldenRunner() {}

    /**
     * What a case's records run on, in offered order (28 §2.2 O5): the tier-E oracle, or a host under test. Textures
     * are always in the surface's format (28 §6.2), and payloads and pixels are in the 28 §5.7 layout.
     */
    public interface Target {

        /** {@code TEX_CREATE}: a zeroed texture (28 §6.2). */
        void createTexture(int handle, int width, int height, int format);

        /** {@code TEX_UPDATE}: rows top first (28 §6.3). */
        void updateTexture(int handle, int x, int y, int width, int height, byte[] payload);

        /** {@code TEX_FREE}. */
        void freeTexture(int handle);

        /** {@code RESET}: every texture dropped, the surface replaced by the pixels (28:496). */
        void reset(byte[] pixels);

        /** {@code FRAME}: the frame's segments in order, each accepted by the validator. */
        void runFrame(byte[][] segments);

        /** The surface now, in the 28 §5.7 layout. */
        byte[] pixels();
    }

    /** The tier-E oracle as a target. */
    public static Target target(final TierEOracle oracle) {
        if (oracle == null) {
            throw new NullPointerException("oracle");
        }
        return new Target() {

            @Override
            public void createTexture(int handle, int width, int height, int format) {
                oracle.createTexture(handle, width, height, format);
            }

            @Override
            public void updateTexture(int handle, int x, int y, int width, int height, byte[] payload) {
                oracle.updateTexture(handle, x, y, width, height, payload);
            }

            @Override
            public void freeTexture(int handle) {
                oracle.freeTexture(handle);
            }

            @Override
            public void reset(byte[] pixels) {
                oracle.reset(pixels);
            }

            @Override
            public void runFrame(byte[][] segments) {
                oracle.runFrame(segments);
            }

            @Override
            public byte[] pixels() {
                return oracle.pixels();
            }
        };
    }

    /**
     * Runs the case on a new tier-E oracle with the synthetic glyph table, the one the file's first directive names.
     */
    public static Result run(GoldenCases.Case c) {
        SurfaceDesc surface;
        try {
            surface = c.surface();
        } catch (IllegalArgumentException e) {
            return new Result(c, Collections.singletonList("line " + c.line() + ": " + e.getMessage()), null);
        }
        return run(c, target(new TierEOracle(surface, SyntheticGlyphs.TABLE)));
    }

    /**
     * Runs the case on the target, which must start as a new device on {@code c.surface()} with font 0 being
     * {@link SyntheticGlyphs#TABLE}.
     */
    public static Result run(GoldenCases.Case c, Target target) {
        List<String> failures = new ArrayList<>();
        if (!SyntheticGlyphs.ID.equals(c.glyphs())) {
            failures
                .add("the goldens assume glyph table " + c.glyphs() + ", and the oracle's is " + SyntheticGlyphs.ID);
            return new Result(c, failures, null);
        }
        SurfaceDesc surface;
        try {
            surface = c.surface();
        } catch (IllegalArgumentException e) {
            failures.add("line " + c.line() + ": " + e.getMessage());
            return new Result(c, failures, null);
        }
        MapLiveView view = new MapLiveView(surface, LIMITS);
        FrameTotals last = null;
        int expects = 0;
        GoldenCases.Step step = null;
        try {
            for (GoldenCases.Step s : c.steps()) {
                step = s;
                switch (s.kind()) {
                    case TEXTURE:
                        texture((GoldenCases.Texture) s, view, target, surface);
                        break;
                    case UPDATE:
                        update((GoldenCases.Update) s, view, target);
                        break;
                    case FREE: {
                        GoldenCases.Free f = (GoldenCases.Free) s;
                        requireLive(f.handle(), view, f.line(), "free");
                        view.free(f.handle());
                        target.freeTexture(f.handle());
                        break;
                    }
                    case RESET:
                        // 28 §2.2: every live handle is freed with a generation bump; then the RESET record
                        view.clear();
                        target.reset(((GoldenCases.Reset) s).pixels());
                        break;
                    case FRAME:
                        last = frame((GoldenCases.Frame) s, view, target);
                        break;
                    case TOTALS:
                        if (last == null) {
                            throw new Failure(s.line() + ": totals before any frame");
                        }
                        totals((GoldenCases.Totals) s, last, failures);
                        break;
                    case EXPECT:
                        expects++;
                        expect(c, (GoldenCases.Expect) s, target.pixels(), failures);
                        break;
                    default:
                        throw new IllegalStateException("step kind " + s.kind());
                }
            }
        } catch (Failure f) {
            failures.add("line " + f.getMessage());
            return new Result(c, failures, null);
        } catch (RuntimeException e) {
            // the target refused a record the validator accepted: a bug in one of them
            int line = step == null ? c.line() : step.line();
            failures.add("line " + line + ": the target threw " + e);
            return new Result(c, failures, e);
        }
        if (expects == 0) {
            failures.add("the case has no expect block");
        }
        return new Result(c, failures, null);
    }

    private static void texture(GoldenCases.Texture t, MapLiveView view, Target target, SurfaceDesc surface) {
        int w = t.width();
        int h = t.height();
        if (w < 1 || w > MAX_TEXTURE_SIDE || h < 1 || h > MAX_TEXTURE_SIDE) {
            throw new Failure(
                t.line() + ": createTexture: invalid texture size " + w + "x" + h + " (28 §6.2: sides 1-1024)");
        }
        if (view.liveCount() >= MAX_LIVE_TEXTURES) {
            throw new Failure(t.line() + ": createTexture: too many textures (28 §6.2)");
        }
        int given = view.create(w, h);
        if (given != t.handle()) {
            throw new Failure(
                String.format(
                    Locale.ROOT,
                    "%d: texture 0x%08X, but the allocator gives 0x%08X (28 §6.1)",
                    t.line(),
                    t.handle(),
                    given));
        }
        target.createTexture(given, w, h, surface.format);
        if (!t.zeros()) {
            target.updateTexture(given, 0, 0, w, h, t.payload());
        }
    }

    private static void update(GoldenCases.Update u, MapLiveView view, Target target) {
        requireLive(u.handle(), view, u.line(), "updateTexture");
        int tw = view.textureWidth(u.handle());
        int th = view.textureHeight(u.handle());
        int x = u.x();
        int y = u.y();
        // 28 §6.3: 0 <= x <= TW, 1 <= w <= TW - x, 0 <= y <= TH, 1 <= h <= TH - y
        if (x < 0 || x > tw
            || u.width() < 1
            || u.width() > tw - x
            || y < 0
            || y > th
            || u.height() < 1
            || u.height() > th - y) {
            throw new Failure(
                String.format(
                    Locale.ROOT,
                    "%d: updateTexture: invalid texture region (%d, %d) %dx%d of %dx%d (28 §6.3)",
                    u.line(),
                    x,
                    y,
                    u.width(),
                    u.height(),
                    tw,
                    th));
        }
        target.updateTexture(u.handle(), x, y, u.width(), u.height(), u.payload());
    }

    private static void requireLive(int handle, MapLiveView view, int line, String call) {
        ErrorCode error = TextureHandles.check(handle, view);
        if (error != null) {
            throw new Failure(line + ": " + call + ": " + TextureHandles.reason(error, handle) + " (" + error + ")");
        }
    }

    /** One FRAME: every segment validated against the open frame, then the sealed frame run on the target. */
    private static FrameTotals frame(GoldenCases.Frame f, MapLiveView view, Target target) {
        if (f.segments()
            .isEmpty()) {
            throw new Failure(f.line() + ": a frame needs at least one segment");
        }
        TestFrame open = new TestFrame(view);
        for (GoldenCases.Segment segment : f.segments()) {
            Validation v = open.submit(segment.bytes());
            if (!v.isAccepted()) {
                throw new Failure(
                    segment.line() + ": segment rejected: "
                        + v.error()
                            .message()
                        + " ("
                        + v.error().code
                        + ")");
            }
        }
        FrameTotals totals = open.totals();
        target.runFrame(open.seal());
        return totals;
    }

    private static void totals(GoldenCases.Totals want, FrameTotals got, List<String> failures) {
        if (want.records != got.records || want.clear != got.clearPixels
            || want.fill != got.fillPixels
            || want.copy != got.copyPixels
            || want.set != got.setPixels) {
            failures.add(
                String.format(
                    Locale.ROOT,
                    "line %d: totals differ%n      file:      %s%n      validator: totals records=%d clear=%d"
                        + " fill=%d copy=%d set=%d",
                    want.line(),
                    want,
                    got.records,
                    got.clearPixels,
                    got.fillPixels,
                    got.copyPixels,
                    got.setPixels));
        }
    }

    private static void expect(GoldenCases.Case c, GoldenCases.Expect e, byte[] pixels, List<String> failures) {
        int w = c.width();
        int h = c.height();
        int[] want = e.values();
        int[] got = values(c.format(), pixels, w * h);
        List<Integer> differing = new ArrayList<>();
        for (int i = 0; i < want.length; i++) {
            if (want[i] != got[i]) {
                differing.add(i);
            }
        }
        if (differing.isEmpty()) {
            return;
        }
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "line %d: expect differs at %d pixel(s)%n", e.line(), differing.size()));
        out.append(String.format(Locale.ROOT, "      expected:%n"));
        grid(c, want, out);
        out.append(String.format(Locale.ROOT, "      actual:%n"));
        grid(c, got, out);
        out.append(String.format(Locale.ROOT, "      diff (X = differs):%n"));
        for (int y = 0; y < h; y++) {
            out.append("        ");
            for (int x = 0; x < w; x++) {
                out.append(want[y * w + x] != got[y * w + x] ? 'X' : '.');
            }
            out.append(String.format(Locale.ROOT, "%n"));
        }
        String digits = c.format() == Formats.INDEX8 ? "%02X" : "%04X";
        for (int k = 0; k < Math.min(LISTED_PIXELS, differing.size()); k++) {
            int i = differing.get(k);
            out.append(
                String.format(
                    Locale.ROOT,
                    "      (%d,%d): expected " + digits + ", actual " + digits + "%n",
                    i % w,
                    i / w,
                    want[i],
                    got[i]));
        }
        if (differing.size() > LISTED_PIXELS) {
            out.append(String.format(Locale.ROOT, "      ... %d more%n", differing.size() - LISTED_PIXELS));
        }
        if (c.chars() != null) {
            TreeSet<Integer> unmapped = new TreeSet<>();
            for (int v : got) {
                if (!c.chars()
                    .containsValue(v)) {
                    unmapped.add(v);
                }
            }
            if (!unmapped.isEmpty()) {
                out.append("      actual values not in chars (shown as '?'):");
                for (int v : unmapped) {
                    out.append(' ')
                        .append(String.format(Locale.ROOT, digits, v));
                }
                out.append(String.format(Locale.ROOT, "%n"));
            }
        }
        failures.add(
            out.toString()
                .trim());
    }

    /** The grid in the case's row form: hex tokens, or with chars the first character mapped to each value. */
    private static void grid(GoldenCases.Case c, int[] values, StringBuilder out) {
        int w = c.width();
        Map<Integer, String> inverse = new LinkedHashMap<>();
        if (c.chars() != null) {
            for (Map.Entry<Integer, Integer> m : c.chars()
                .entrySet()) {
                if (!inverse.containsKey(m.getValue())) {
                    inverse.put(m.getValue(), new String(Character.toChars(m.getKey())));
                }
            }
        }
        String digits = c.format() == Formats.INDEX8 ? "%02X" : "%04X";
        for (int y = 0; y < c.height(); y++) {
            out.append("        ");
            for (int x = 0; x < w; x++) {
                int v = values[y * w + x];
                if (c.chars() != null) {
                    String ch = inverse.get(v);
                    out.append(ch == null ? "?" : ch);
                } else {
                    if (x > 0) {
                        out.append(' ');
                    }
                    out.append(String.format(Locale.ROOT, digits, v));
                }
            }
            out.append(String.format(Locale.ROOT, "%n"));
        }
    }

    /** Pixels in the 28 §5.7 layout as values: an index, or a LE u16 565 value. */
    private static int[] values(int format, byte[] pixels, int count) {
        int bpp = Formats.bytesPerPixel(format);
        if (pixels.length != count * bpp) {
            throw new IllegalStateException("the target's surface is " + pixels.length + " bytes, not " + count * bpp);
        }
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = bpp == 1 ? pixels[i] & 0xFF : (pixels[2 * i] & 0xFF) | (pixels[2 * i + 1] & 0xFF) << 8;
        }
        return values;
    }

    /** A step that cannot run: the case ends there. The message starts with the line number. */
    private static final class Failure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Failure(String message) {
            super(message);
        }
    }

    /** A case's outcome: passed, or the failures in file order. */
    public static final class Result {

        private final GoldenCases.Case c;
        private final List<String> failures;
        private final Throwable cause;

        Result(GoldenCases.Case c, List<String> failures, Throwable cause) {
            this.c = c;
            this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
            this.cause = cause;
        }

        public boolean passed() {
            return failures.isEmpty();
        }

        public List<String> failures() {
            return failures;
        }

        /** The exception the target threw, if that is what ended the case; else null. */
        public Throwable cause() {
            return cause;
        }

        /** "PASS name", or "FAIL name (line n)" and each failure, indented. */
        public String report() {
            if (passed()) {
                return "PASS " + c.name();
            }
            StringBuilder out = new StringBuilder();
            out.append("FAIL ")
                .append(c.name())
                .append(" (line ")
                .append(c.line())
                .append(')');
            for (String f : failures) {
                out.append(String.format(Locale.ROOT, "%n    "))
                    .append(f);
            }
            return out.toString();
        }

        @Override
        public String toString() {
            return report();
        }
    }
}
