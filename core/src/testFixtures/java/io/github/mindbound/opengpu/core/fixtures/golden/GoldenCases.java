package io.github.mindbound.opengpu.core.fixtures.golden;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.fixtures.SyntheticGlyphs;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;

/**
 * The tier-E oracle's hand-computed goldens (28 §11.4) as a file model, and the parser of their text format. The file
 * is {@value #RESOURCE} in {@code :core}'s test fixtures, so S8 can replay it on GL through {@link GoldenRunner}; its
 * grids are derived from 28's text and re-derived by {@code docs/research/cmdstream-checks/oracle_goldens.py}, never
 * taken from the Java oracle (23:148).
 * <p>
 * The format is line-based, UTF-8, with free indentation:
 *
 * <pre>
 * glyphs synthetic-6x13 28:633        first, mandatory: the glyph table every case assumes (28:376)
 * case NAME                           [a-z0-9-]+, unique; a new device, handles from 0x08000000 (28 §6.1)
 * surface index8|rgb565 WxH           a tier-0 surface, all zero (28 §5.7)
 * chars C=HEX ...                     optional: rows become W-character strings, one character per value
 * texture HANDLE WxH [zeros]          TEX_CREATE in the surface format; unless zeros, h rows follow (TEX_UPDATE)
 * update HANDLE X Y WxH               TEX_UPDATE of a region (28 §6.3); h rows of w values follow
 * free HANDLE                         TEX_FREE
 * reset                               RESET (28:496); H rows follow
 * frame                               one FRAME: the segments below it, until the next step
 * segment                             header and END are added (28 §3.1); op lines follow
 *   clear C
 *   fill X Y W H C
 *   blit TEX SX SY W H DX DY FLAGS
 *   text X Y FG BG FLAGS FONT "S"     escapes \" \\ &#92;uXXXX &#92;U00XXXXXX
 *   hex BYTE ...                      a raw record: two hex digits or 0x-prefixed per byte
 * totals records=N clear=N fill=N copy=N set=N   the last frame's validator totals (28 §3.5)
 * expect                              H rows follow: the surface now
 * end
 * </pre>
 *
 * Numbers are decimal or {@code 0x} hex, with an optional leading '-'; handles and rgb565 colours are written in hex by
 * convention. A row is W tokens of two (index8) or four (rgb565: the 565 value, not its bytes) hex digits, or with
 * {@code chars} a string of W mapped characters, also for texture and update rows.
 * <p>
 * The parser reads the file exactly as {@code oracle_goldens.py} does, including where plan §6.4 leaves a choice:
 * <ul>
 * <li>lines split as Python's {@code str.splitlines} splits them, and blanks are Python's whitespace;</li>
 * <li>a row block is the next N non-blank lines, read before any comment handling, since a {@code chars} row may begin
 * with '#';</li>
 * <li>'#' starts a comment only as a line's first non-blank character outside a row block: there are no trailing
 * comments, so {@code clear 3 # why} is an error;</li>
 * <li>a <code>&#92;uXXXX</code> escape is one codepoint, so an escaped surrogate is encoded alone (Python's
 * {@code surrogatepass}) and is then BAD_UTF8 for the validator (28 §7.2).</li>
 * </ul>
 * Immutable.
 */
public final class GoldenCases {

    /** The classpath resource of the goldens. */
    public static final String RESOURCE = "/io/github/mindbound/opengpu/core/fixtures/oracle-goldens.txt";

    /** The source the first directive cites after the table's name: 28:633's synthetic rule. */
    public static final String GLYPHS_SOURCE = "28:633";

    /** The largest texel or pixel value per format: an index, or a 565 value. */
    private static final int MAX_INDEX8 = 0xFF;
    private static final int MAX_RGB565 = 0xFFFF;

    private final String glyphs;
    private final List<Case> cases;

    private GoldenCases(String glyphs, List<Case> cases) {
        this.glyphs = glyphs;
        this.cases = Collections.unmodifiableList(new ArrayList<>(cases));
    }

    /**
     * The goldens from {@value #RESOURCE}.
     *
     * @throws IllegalStateException if the resource is not on the classpath
     * @throws FormatException       if the file breaks the format
     */
    public static GoldenCases load() {
        InputStream in = GoldenCases.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException(RESOURCE + " is not on the classpath");
        }
        try {
            return read(in);
        } finally {
            try {
                in.close();
            } catch (IOException e) {
                // a read-only resource stream: nothing was lost
            }
        }
    }

    /**
     * The goldens from a stream of strict UTF-8 (a malformed byte is an error, as for Python's {@code open}).
     *
     * @throws FormatException if the bytes are not UTF-8 or the text breaks the format
     */
    public static GoldenCases read(InputStream in) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            for (int n; (n = in.read(buffer)) > 0;) {
                bytes.write(buffer, 0, n);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString();
        } catch (CharacterCodingException e) {
            throw new FormatException(0, "the file is not UTF-8: " + e);
        }
        return parse(text);
    }

    /**
     * The goldens from text.
     *
     * @throws FormatException if the text breaks the format
     */
    public static GoldenCases parse(String text) {
        return new Parser(text).parse();
    }

    /** The glyph table the first directive names, {@link SyntheticGlyphs#ID}. */
    public String glyphs() {
        return glyphs;
    }

    /** The cases in file order, unmodifiable. */
    public List<Case> cases() {
        return cases;
    }

    /**
     * The case of that name.
     *
     * @throws IllegalArgumentException if there is none
     */
    public Case byName(String name) {
        for (Case c : cases) {
            if (c.name.equals(name)) {
                return c;
            }
        }
        throw new IllegalArgumentException("no golden case " + name);
    }

    /** A malformed goldens file, with the 1-based line it was found on (0 for the file as a whole). */
    public static final class FormatException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        private final int line;

        public FormatException(int line, String message) {
            super("line " + line + ": " + message);
            this.line = line;
        }

        public int line() {
            return line;
        }
    }

    /** One case: a new device on a tier-0 surface, and the steps run on it in file order. */
    public static final class Case {

        private final String glyphs;
        private final String name;
        private final int line;
        private final int format;
        private final int width;
        private final int height;
        private final Map<Integer, Integer> chars;
        private final List<Step> steps;
        private final int comments;

        Case(String glyphs, String name, int line, int format, int width, int height, Map<Integer, Integer> chars,
            List<Step> steps, int comments) {
            this.glyphs = glyphs;
            this.name = name;
            this.line = line;
            this.format = format;
            this.width = width;
            this.height = height;
            this.chars = chars == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(chars));
            this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
            this.comments = comments;
        }

        /** The glyph table the file's first directive names. */
        public String glyphs() {
            return glyphs;
        }

        public String name() {
            return name;
        }

        /** The line of the case's {@code case} directive. */
        public int line() {
            return line;
        }

        /** {@link Formats#INDEX8} or {@link Formats#RGB565}. */
        public int format() {
            return format;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        /**
         * The case's tier-0 surface.
         *
         * @throws IllegalArgumentException for a side above {@link SurfaceDesc#MAX_SIDE}
         */
        public SurfaceDesc surface() {
            return new SurfaceDesc(0, width, height, format);
        }

        /** The {@code chars} map from a character's codepoint to its value, in file order; null without one. */
        public Map<Integer, Integer> chars() {
            return chars;
        }

        public List<Step> steps() {
            return steps;
        }

        /** The '#' lines inside the case: each case says why its grids are what they are (plan §6.4). */
        public int comments() {
            return comments;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** The kinds of step, as the directives name them. */
    public enum Kind {
        TEXTURE,
        UPDATE,
        FREE,
        RESET,
        FRAME,
        TOTALS,
        EXPECT
    }

    /** One step of a case, run in file order. */
    public abstract static class Step {

        private final int line;

        Step(int line) {
            this.line = line;
        }

        /** The line of the step's directive. */
        public int line() {
            return line;
        }

        public abstract Kind kind();
    }

    /** {@code texture}: a {@code TEX_CREATE} in the surface format, then, unless zeros, a full {@code TEX_UPDATE}. */
    public static final class Texture extends Step {

        private final int handle;
        private final int width;
        private final int height;
        private final int format;
        /** Row-major texels, filled by the row block; null for zeros. */
        private final int[] texels;

        Texture(int line, int handle, int width, int height, int format, int[] texels) {
            super(line);
            this.handle = handle;
            this.width = width;
            this.height = height;
            this.format = format;
            this.texels = texels;
        }

        @Override
        public Kind kind() {
            return Kind.TEXTURE;
        }

        public int handle() {
            return handle;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        /** Whether the texture keeps its zeros (28 §6.2), with no update. */
        public boolean zeros() {
            return texels == null;
        }

        /** The texels row-major, a copy; null for zeros. */
        public int[] texels() {
            return texels == null ? null : texels.clone();
        }

        /** The full update's payload in the 28 §5.7 layout; null for zeros. */
        public byte[] payload() {
            return texels == null ? null : toBytes(format, texels);
        }
    }

    /** {@code update}: a {@code TEX_UPDATE} of a region (28 §6.3). */
    public static final class Update extends Step {

        private final int handle;
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final int format;
        private final int[] values;

        Update(int line, int handle, int x, int y, int width, int height, int format, int[] values) {
            super(line);
            this.handle = handle;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.format = format;
            this.values = values;
        }

        @Override
        public Kind kind() {
            return Kind.UPDATE;
        }

        public int handle() {
            return handle;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        /** The region's texels row-major, a copy. */
        public int[] values() {
            return values.clone();
        }

        /** The payload in the 28 §5.7 layout: rows top first, w·bpp bytes each, rgb565 as LE u16. */
        public byte[] payload() {
            return toBytes(format, values);
        }
    }

    /** {@code free}: a {@code TEX_FREE}. */
    public static final class Free extends Step {

        private final int handle;

        Free(int line, int handle) {
            super(line);
            this.handle = handle;
        }

        @Override
        public Kind kind() {
            return Kind.FREE;
        }

        public int handle() {
            return handle;
        }
    }

    /** {@code reset}: a {@code RESET} with the given pixels (28:496). */
    public static final class Reset extends Step {

        private final int format;
        private final int[] values;

        Reset(int line, int format, int[] values) {
            super(line);
            this.format = format;
            this.values = values;
        }

        @Override
        public Kind kind() {
            return Kind.RESET;
        }

        /** The pixels row-major, a copy. */
        public int[] values() {
            return values.clone();
        }

        /** The pixels in the 28 §5.7 layout. */
        public byte[] pixels() {
            return toBytes(format, values);
        }
    }

    /** {@code frame}: one {@code FRAME} of segments. */
    public static final class Frame extends Step {

        private final List<Segment> segments;

        Frame(int line, List<Segment> segments) {
            super(line);
            this.segments = Collections.unmodifiableList(new ArrayList<>(segments));
        }

        @Override
        public Kind kind() {
            return Kind.FRAME;
        }

        public List<Segment> segments() {
            return segments;
        }
    }

    /** One {@code segment} of a frame: the op lines' records between the v1 header and {@code END} (28 §3.1). */
    public static final class Segment {

        private final int line;
        private final byte[] bytes;

        Segment(int line, byte[] bytes) {
            this.line = line;
            this.bytes = bytes;
        }

        /** The line of the {@code segment} directive. */
        public int line() {
            return line;
        }

        /** The whole segment, a copy. */
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /** {@code totals}: the last frame's validator totals (28 §3.5), END not counted as a record. */
    public static final class Totals extends Step {

        public final long records;
        public final long clear;
        public final long fill;
        public final long copy;
        public final long set;

        Totals(int line, long records, long clear, long fill, long copy, long set) {
            super(line);
            this.records = records;
            this.clear = clear;
            this.fill = fill;
            this.copy = copy;
            this.set = set;
        }

        @Override
        public Kind kind() {
            return Kind.TOTALS;
        }

        @Override
        public String toString() {
            return String.format(
                Locale.ROOT,
                "totals records=%d clear=%d fill=%d copy=%d set=%d",
                records,
                clear,
                fill,
                copy,
                set);
        }
    }

    /** {@code expect}: the surface after the steps before it. */
    public static final class Expect extends Step {

        private final int[] values;

        Expect(int line, int[] values) {
            super(line);
            this.values = values;
        }

        @Override
        public Kind kind() {
            return Kind.EXPECT;
        }

        /** The pixels row-major, a copy: an index, or a 565 value. */
        public int[] values() {
            return values.clone();
        }
    }

    /** Values in the 28 §5.7 byte layout: index8 1 B each, rgb565 a LE u16 each. */
    static byte[] toBytes(int format, int[] values) {
        boolean index8 = format == Formats.INDEX8;
        byte[] out = new byte[values.length * (index8 ? 1 : 2)];
        for (int i = 0; i < values.length; i++) {
            if (index8) {
                out[i] = (byte) values[i];
            } else {
                out[2 * i] = (byte) values[i];
                out[2 * i + 1] = (byte) (values[i] >>> 8);
            }
        }
        return out;
    }

    // Python's text model, which oracle_goldens.py reads the file with

    /** {@code str.isspace}: Unicode bidi classes WS, B and S and category Zs. Every one is in the BMP. */
    static boolean isSpace(int c) {
        return c >= 0x09 && c <= 0x0D || c >= 0x1C && c <= 0x20
            || c == 0x85
            || c == 0xA0
            || c == 0x1680
            || c >= 0x2000 && c <= 0x200A
            || c == 0x2028
            || c == 0x2029
            || c == 0x202F
            || c == 0x205F
            || c == 0x3000;
    }

    /** {@code str.splitlines}'s boundaries (after universal newlines, which change nothing more). */
    private static boolean isLineBreak(char c) {
        return c == '\n' || c == '\r'
            || c == 0x0B
            || c == 0x0C
            || c == 0x1C
            || c == 0x1D
            || c == 0x1E
            || c == 0x85
            || c == 0x2028
            || c == 0x2029;
    }

    /** {@code text.splitlines()}: "\r\n" is one break, and a final break opens no empty line. */
    static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isLineBreak(c)) {
                lines.add(text.substring(start, i));
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    /** {@code s.strip()}. */
    static String strip(String s) {
        int from = 0;
        int to = s.length();
        while (from < to && isSpace(s.charAt(from))) {
            from++;
        }
        while (to > from && isSpace(s.charAt(to - 1))) {
            to--;
        }
        return s.substring(from, to);
    }

    /** {@code s.split()}: the runs of non-blank characters. */
    static List<String> split(String s) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            while (i < s.length() && isSpace(s.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < s.length() && !isSpace(s.charAt(i))) {
                i++;
            }
            if (i > start) {
                tokens.add(s.substring(start, i));
            }
        }
        return tokens;
    }

    private static boolean isHexDigit(int c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
    }

    private static boolean allHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!isHexDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean allDecimal(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** Each codepoint as UTF-8 on its own, surrogates included (Python's {@code surrogatepass}). */
    private static byte[] utf8(List<Integer> codepoints) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int cp : codepoints) {
            if (cp < 0x80) {
                out.write(cp);
            } else if (cp < 0x800) {
                out.write(0xC0 | cp >>> 6);
                out.write(0x80 | cp & 0x3F);
            } else if (cp < 0x10000) {
                out.write(0xE0 | cp >>> 12);
                out.write(0x80 | cp >>> 6 & 0x3F);
                out.write(0x80 | cp & 0x3F);
            } else {
                out.write(0xF0 | cp >>> 18);
                out.write(0x80 | cp >>> 12 & 0x3F);
                out.write(0x80 | cp >>> 6 & 0x3F);
                out.write(0x80 | cp & 0x3F);
            }
        }
        return out.toByteArray();
    }

    /**
     * A pending row block: count rows of the given width. Its step is made when the last row has been read, since only
     * rows can come in between, so memory follows the file's length and never a declared size.
     */
    private static final class Rows {

        final int count;
        final int width;
        final Function<int[], Step> step;
        final List<int[]> rows = new ArrayList<>();

        Rows(int count, int width, Function<int[], Step> step) {
            this.count = count;
            this.width = width;
            this.step = step;
        }
    }

    /** A case while it is being read. */
    private static final class CaseBuilder {

        final String name;
        final int line;
        int format = Formats.INVALID;
        int width;
        int height;
        Map<Integer, Integer> chars;
        final List<Step> steps = new ArrayList<>();
        int comments;

        CaseBuilder(String name, int line) {
            this.name = name;
            this.line = line;
        }

        int maxValue() {
            return format == Formats.INDEX8 ? MAX_INDEX8 : MAX_RGB565;
        }
    }

    /** oracle_goldens.py's {@code parse}, transcribed. */
    private static final class Parser {

        private static final List<String> STEP_WORDS = Arrays
            .asList("texture", "update", "free", "reset", "frame", "totals", "expect", "end");
        private static final List<String> OP_WORDS = Arrays.asList("clear", "fill", "blit", "text", "hex");
        private static final List<String> TOTALS_KEYS = Arrays.asList("records", "clear", "fill", "copy", "set");

        private final List<String> lines;
        private final List<Case> cases = new ArrayList<>();
        private final Set<String> names = new HashSet<>();
        private String glyphs;
        private CaseBuilder current;
        private Rows pending;
        /** The open frame's segments and its line, or null outside a frame. */
        private List<Segment> frame;
        private int frameLine;
        /** The open segment's records, or null outside a segment. */
        private List<byte[]> segment;
        private int segmentLine;

        Parser(String text) {
            this.lines = splitLines(text);
        }

        GoldenCases parse() {
            for (int index = 0; index < lines.size(); index++) {
                line(index + 1, strip(lines.get(index)));
            }
            if (pending != null) {
                throw new FormatException(lines.size(), "the file ends inside a row block");
            }
            if (current != null) {
                throw new FormatException(current.line, "case " + current.name + " has no end");
            }
            if (glyphs == null) {
                throw new FormatException(1, "no 'glyphs " + SyntheticGlyphs.ID + " " + GLYPHS_SOURCE + "' directive");
            }
            return new GoldenCases(glyphs, cases);
        }

        private void line(int ln, String s) {
            if (s.isEmpty()) {
                return;
            }
            if (pending != null) {
                // rows first: a chars row may begin with '#'
                pending.rows.add(row(s, pending.width, ln));
                if (pending.rows.size() == pending.count) {
                    int[] values = new int[pending.count * pending.width];
                    for (int r = 0; r < pending.count; r++) {
                        System.arraycopy(pending.rows.get(r), 0, values, r * pending.width, pending.width);
                    }
                    current.steps.add(pending.step.apply(values));
                    pending = null;
                }
                return;
            }
            if (s.startsWith("#")) {
                if (current != null) {
                    current.comments++;
                }
                return;
            }
            List<String> toks = split(s);
            String kw = toks.get(0);
            if (glyphs == null) {
                glyphsDirective(ln, toks);
                return;
            }
            if (current == null) {
                caseDirective(ln, toks);
                return;
            }
            if (current.format == Formats.INVALID) {
                surface(ln, toks);
                return;
            }
            if (kw.equals("chars")) {
                chars(ln, toks);
                return;
            }
            if (OP_WORDS.contains(kw)) {
                if (segment == null) {
                    throw new FormatException(ln, kw + " outside a segment");
                }
                segment.add(op(toks, s, ln));
                return;
            }
            if (kw.equals("segment")) {
                if (frame == null || toks.size() != 1) {
                    throw new FormatException(ln, "segment outside a frame");
                }
                closeSegment();
                segment = new ArrayList<>();
                segmentLine = ln;
                return;
            }
            if (!STEP_WORDS.contains(kw)) {
                throw new FormatException(ln, "unknown directive '" + kw + "'");
            }
            closeFrame();
            step(ln, kw, toks);
        }

        private void glyphsDirective(int ln, List<String> toks) {
            // amendment C (finding 17): the table every TEXT golden assumes, asserted against SyntheticGlyphs.ID
            if (!toks.equals(Arrays.asList("glyphs", SyntheticGlyphs.ID, GLYPHS_SOURCE))) {
                throw new FormatException(
                    ln,
                    "the first directive must be 'glyphs " + SyntheticGlyphs.ID + " " + GLYPHS_SOURCE + "'");
            }
            glyphs = toks.get(1);
        }

        private void caseDirective(int ln, List<String> toks) {
            if (!toks.get(0)
                .equals("case")) {
                throw new FormatException(ln, "unknown or misplaced directive '" + toks.get(0) + "' outside a case");
            }
            if (toks.size() != 2 || !isCaseName(toks.get(1))) {
                throw new FormatException(ln, "case <name> with name [a-z0-9-]+");
            }
            if (!names.add(toks.get(1))) {
                throw new FormatException(ln, "duplicate case name " + toks.get(1));
            }
            current = new CaseBuilder(toks.get(1), ln);
        }

        private void surface(int ln, List<String> toks) {
            int format = toks.size() == 3 ? Formats.byName(toks.get(1)) : Formats.INVALID;
            if (!toks.get(0)
                .equals("surface") || format == Formats.INVALID) {
                throw new FormatException(ln, "a case starts with surface <index8|rgb565> <W>x<H>");
            }
            int[] size = size(toks.get(2), ln);
            if (size[0] < 1 || size[1] < 1) {
                throw new FormatException(ln, "empty surface");
            }
            current.format = format;
            current.width = size[0];
            current.height = size[1];
        }

        private void chars(int ln, List<String> toks) {
            // an open frame is a step already: oracle_goldens.py appends it at its directive
            if (!current.steps.isEmpty() || frame != null || current.chars != null || toks.size() < 2) {
                throw new FormatException(ln, "chars comes once, right after surface, with at least one mapping");
            }
            Map<Integer, Integer> map = new LinkedHashMap<>();
            for (String t : toks.subList(1, toks.size())) {
                int[] cps = t.codePoints()
                    .toArray();
                if (cps.length < 3 || cps[1] != '=') {
                    throw new FormatException(ln, "chars mapping " + t + " is not <c>=<hex>");
                }
                String hex = new String(cps, 2, cps.length - 2);
                BigInteger v = number(hex.startsWith("0x") || hex.startsWith("0X") ? hex : "0x" + hex, ln);
                if (map.containsKey(cps[0]) || v.compareTo(BigInteger.valueOf(current.maxValue())) > 0) {
                    throw new FormatException(ln, "chars mapping " + t + " repeats a character or exceeds the format");
                }
                map.put(cps[0], v.intValue());
            }
            current.chars = map;
        }

        private void step(final int ln, String kw, List<String> toks) {
            int w = current.width;
            int h = current.height;
            switch (kw) {
                case "end":
                    if (toks.size() != 1) {
                        throw new FormatException(ln, "end takes nothing");
                    }
                    cases.add(
                        new Case(
                            glyphs,
                            current.name,
                            current.line,
                            current.format,
                            current.width,
                            current.height,
                            current.chars,
                            current.steps,
                            current.comments));
                    current = null;
                    break;
                case "frame":
                    if (toks.size() != 1) {
                        throw new FormatException(ln, "frame takes nothing");
                    }
                    frame = new ArrayList<>();
                    frameLine = ln;
                    break;
                case "texture": {
                    if (toks.size() != 3 && toks.size() != 4 || toks.size() == 4 && !toks.get(3)
                        .equals("zeros")) {
                        throw new FormatException(ln, "texture <handle> <w>x<h> [zeros]");
                    }
                    final int[] size = size(toks.get(2), ln);
                    final int handle = u32(toks.get(1), ln, "handle");
                    final int format = current.format;
                    if (toks.size() == 3) {
                        // with h = 0 the block never ends, as in oracle_goldens.py: a row or end-of-file error follows
                        pending = new Rows(
                            size[1],
                            size[0],
                            texels -> new Texture(ln, handle, size[0], size[1], format, texels));
                    } else {
                        current.steps.add(new Texture(ln, handle, size[0], size[1], format, null));
                    }
                    break;
                }
                case "update": {
                    if (toks.size() != 5) {
                        throw new FormatException(ln, "update <handle> <x> <y> <w>x<h>");
                    }
                    final int[] size = size(toks.get(4), ln);
                    final int handle = u32(toks.get(1), ln, "handle");
                    final int x = (int) ranged(number(toks.get(2), ln), Integer.MIN_VALUE, Integer.MAX_VALUE, "x", ln);
                    final int y = (int) ranged(number(toks.get(3), ln), Integer.MIN_VALUE, Integer.MAX_VALUE, "y", ln);
                    final int format = current.format;
                    if (size[1] > 0) {
                        pending = new Rows(
                            size[1],
                            size[0],
                            values -> new Update(ln, handle, x, y, size[0], size[1], format, values));
                    } else {
                        // no rows: the runner rejects the empty region (28 §6.3)
                        current.steps.add(new Update(ln, handle, x, y, size[0], 0, format, new int[0]));
                    }
                    break;
                }
                case "free":
                    if (toks.size() != 2) {
                        throw new FormatException(ln, "free <handle>");
                    }
                    current.steps.add(new Free(ln, u32(toks.get(1), ln, "handle")));
                    break;
                case "reset":
                case "expect": {
                    if (toks.size() != 1) {
                        throw new FormatException(ln, kw + " takes nothing; its " + h + " rows follow");
                    }
                    final int format = current.format;
                    pending = kw.equals("reset") ? new Rows(h, w, values -> new Reset(ln, format, values))
                        : new Rows(h, w, values -> new Expect(ln, values));
                    break;
                }
                default: // totals
                    current.steps.add(totals(ln, toks));
                    break;
            }
        }

        private Totals totals(int ln, List<String> toks) {
            Map<String, Long> want = new LinkedHashMap<>();
            for (String t : toks.subList(1, toks.size())) {
                int eq = t.indexOf('=');
                String k = eq < 0 ? t : t.substring(0, eq);
                String v = eq < 0 ? "" : t.substring(eq + 1);
                if (!TOTALS_KEYS.contains(k) || want.containsKey(k) || v.isEmpty()) {
                    throw new FormatException(ln, "totals records=<n> clear=<n> fill=<n> copy=<n> set=<n>");
                }
                want.put(k, ranged(number(v, ln), Long.MIN_VALUE, Long.MAX_VALUE, k, ln));
            }
            if (want.size() != TOTALS_KEYS.size()) {
                throw new FormatException(ln, "totals needs all of records, clear, fill, copy and set");
            }
            return new Totals(
                ln,
                want.get("records"),
                want.get("clear"),
                want.get("fill"),
                want.get("copy"),
                want.get("set"));
        }

        /** A step directive ends the open frame: its segments become one {@code frame} step. */
        private void closeFrame() {
            if (frame != null) {
                closeSegment();
                current.steps.add(new Frame(frameLine, frame));
            }
            frame = null;
            segment = null;
        }

        private void closeSegment() {
            if (segment != null) {
                frame.add(new Segment(segmentLine, TestEncoder.segment(segment.toArray(new byte[0][]))));
            }
            segment = null;
        }

        /** One op line's record bytes (28 §5.2), through the fixtures' test encoder. */
        private byte[] op(List<String> toks, String s, int ln) {
            switch (toks.get(0)) {
                case "clear":
                    if (toks.size() != 2) {
                        throw new FormatException(ln, "clear <c>");
                    }
                    return TestEncoder.clear(u32(toks.get(1), ln, "c"));
                case "fill":
                    if (toks.size() != 6) {
                        throw new FormatException(ln, "fill <x> <y> <w> <h> <c>");
                    }
                    return TestEncoder.fill(
                        i16(toks.get(1), ln, "x"),
                        i16(toks.get(2), ln, "y"),
                        i16(toks.get(3), ln, "w"),
                        i16(toks.get(4), ln, "h"),
                        u32(toks.get(5), ln, "c"));
                case "blit":
                    if (toks.size() != 9) {
                        throw new FormatException(ln, "blit <tex> <sx> <sy> <w> <h> <dx> <dy> <flags>");
                    }
                    return TestEncoder.blit(
                        u32(toks.get(1), ln, "tex"),
                        i16(toks.get(2), ln, "sx"),
                        i16(toks.get(3), ln, "sy"),
                        i16(toks.get(4), ln, "w"),
                        i16(toks.get(5), ln, "h"),
                        i16(toks.get(6), ln, "dx"),
                        i16(toks.get(7), ln, "dy"),
                        u8(toks.get(8), ln, "flags"));
                case "text": {
                    int quote = s.indexOf('"');
                    List<String> head = quote < 0 ? null : split(s.substring(0, quote));
                    if (head == null || head.size() != 7) {
                        throw new FormatException(ln, "text <x> <y> <fg> <bg> <flags> <font> \"<string>\"");
                    }
                    byte[] data = string(s.substring(quote), ln);
                    if (data.length > 0xFFFF) {
                        throw new FormatException(ln, "string longer than a u16 count");
                    }
                    return TestEncoder.text(
                        i16(head.get(1), ln, "x"),
                        i16(head.get(2), ln, "y"),
                        u32(head.get(3), ln, "fg"),
                        u32(head.get(4), ln, "bg"),
                        u8(head.get(5), ln, "flags"),
                        u8(head.get(6), ln, "font"),
                        data);
                }
                default: { // hex
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    for (String t : toks.subList(1, toks.size())) {
                        if (t.startsWith("0x") || t.startsWith("0X")) {
                            out.write(u8(t, ln, "byte"));
                        } else if (t.length() == 2 && allHex(t)) {
                            out.write(Integer.parseInt(t, 16));
                        } else {
                            throw new FormatException(ln, "hex bytes are two hex digits or 0x-prefixed: " + t);
                        }
                    }
                    if (out.size() == 0) {
                        throw new FormatException(ln, "hex needs bytes");
                    }
                    return out.toByteArray();
                }
            }
        }

        /** A quoted string from its opening quote, as UTF-8 (oracle_goldens.py's {@code parse_string}). */
        private static byte[] string(String quoted, int ln) {
            int[] rest = quoted.codePoints()
                .toArray();
            List<Integer> out = new ArrayList<>();
            int i = 1;
            while (true) {
                if (i >= rest.length) {
                    throw new FormatException(ln, "unterminated string");
                }
                int ch = rest[i];
                if (ch == '"') {
                    break;
                }
                if (ch != '\\') {
                    out.add(ch);
                    i++;
                    continue;
                }
                int e = i + 1 < rest.length ? rest[i + 1] : -1;
                if (e == '"' || e == '\\') {
                    out.add(e);
                    i += 2;
                } else if (e == 'u' && rest.length >= i + 6 && hexRun(rest, i + 2, i + 6)) {
                    out.add(hexValue(rest, i + 2, i + 6));
                    i += 6;
                } else if (e == 'U' && rest.length >= i + 10
                    && rest[i + 2] == '0'
                    && rest[i + 3] == '0'
                    && hexRun(rest, i + 4, i + 10)) {
                        int v = hexValue(rest, i + 2, i + 10);
                        if (v > 0x10FFFF) {
                            throw new FormatException(ln, "escape above U+10FFFF");
                        }
                        out.add(v);
                        i += 10;
                    } else {
                        throw new FormatException(ln, "bad escape in string");
                    }
            }
            for (int k = i + 1; k < rest.length; k++) {
                if (!isSpace(rest[k])) {
                    throw new FormatException(ln, "text after the string");
                }
            }
            return utf8(out);
        }

        private static boolean hexRun(int[] cps, int from, int to) {
            for (int k = from; k < to; k++) {
                if (!isHexDigit(cps[k])) {
                    return false;
                }
            }
            return true;
        }

        private static int hexValue(int[] cps, int from, int to) {
            return Integer.parseInt(new String(cps, from, to - from), 16);
        }

        /** One row of a block: W tokens of the format's hex digits, or W characters with {@code chars}. */
        private int[] row(String text, int width, int ln) {
            int[] out = new int[width];
            if (current.chars != null) {
                int[] cps = text.codePoints()
                    .toArray();
                if (cps.length != width) {
                    throw new FormatException(ln, "row has " + cps.length + " characters, expected " + width);
                }
                for (int i = 0; i < width; i++) {
                    Integer v = current.chars.get(cps[i]);
                    if (v == null) {
                        throw new FormatException(ln, "character '" + new String(cps, i, 1) + "' is not in chars");
                    }
                    out[i] = v;
                }
                return out;
            }
            List<String> toks = split(text);
            if (toks.size() != width) {
                throw new FormatException(ln, "row has " + toks.size() + " tokens, expected " + width);
            }
            int digits = current.format == Formats.INDEX8 ? 2 : 4;
            for (int i = 0; i < width; i++) {
                String t = toks.get(i);
                if (t.length() != digits || !allHex(t)) {
                    throw new FormatException(ln, "row token " + t + " is not " + digits + " hex digits");
                }
                out[i] = Integer.parseInt(t, 16);
            }
            return out;
        }

        private static boolean isCaseName(String name) {
            if (name.isEmpty()) {
                return false;
            }
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-')) {
                    return false;
                }
            }
            return true;
        }

        /** {@code <w>x<h>} in decimal digits. */
        private static int[] size(String tok, int ln) {
            String[] parts = tok.split("x", -1);
            if (parts.length != 2 || parts[0].isEmpty()
                || parts[1].isEmpty()
                || !allDecimal(parts[0])
                || !allDecimal(parts[1])) {
                throw new FormatException(ln, "not a <w>x<h> size: " + tok);
            }
            int w = (int) ranged(new BigInteger(parts[0]), 0, Integer.MAX_VALUE, "width", ln);
            int h = (int) ranged(new BigInteger(parts[1]), 0, Integer.MAX_VALUE, "height", ln);
            return new int[] { w, h };
        }

        /** Decimal, or 0x hex, with an optional leading '-' (oracle_goldens.py's {@code parse_int}). */
        private static BigInteger number(String tok, int ln) {
            String s = tok;
            boolean negative = s.startsWith("-");
            if (negative) {
                s = s.substring(1);
            }
            BigInteger v;
            if (s.startsWith("0x") || s.startsWith("0X")) {
                String digits = s.substring(2);
                if (digits.isEmpty() || !allHex(digits)) {
                    throw new FormatException(ln, "not a number: " + tok);
                }
                v = new BigInteger(digits, 16);
            } else {
                if (s.isEmpty() || !allDecimal(s)) {
                    throw new FormatException(ln, "not a number: " + tok);
                }
                v = new BigInteger(s, 10);
            }
            return negative ? v.negate() : v;
        }

        private static long ranged(BigInteger v, long lo, long hi, String what, int ln) {
            if (v.compareTo(BigInteger.valueOf(lo)) < 0 || v.compareTo(BigInteger.valueOf(hi)) > 0) {
                throw new FormatException(ln, what + " " + v + " is outside " + lo + ".." + hi);
            }
            return v.longValue();
        }

        private static int i16(String tok, int ln, String what) {
            return (int) ranged(number(tok, ln), -32768, 32767, what + " (i16)", ln);
        }

        private static int u8(String tok, int ln, String what) {
            return (int) ranged(number(tok, ln), 0, 255, what + " (u8)", ln);
        }

        /** A u32 as the int with its 32 bits. */
        private static int u32(String tok, int ln, String what) {
            return (int) ranged(number(tok, ln), 0, 0xFFFFFFFFL, what + " (u32)", ln);
        }
    }
}
