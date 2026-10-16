package io.github.mindbound.opengpu.core.fontgen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A reader for BDF 2.1 bitmap fonts (Adobe's Glyph Bitmap Distribution Format 2.1; own code), the form misc-fixed's
 * faces come in (29 §7, §5.4): the global {@code FONTBOUNDINGBOX}, the properties, the {@code COMMENT} lines, and per
 * glyph its {@code ENCODING}, {@code DWIDTH}, {@code BBX} and {@code BITMAP}. It is build-time code of the
 * {@code fontgen} source set, which ships in no jar and imports nothing of {@code :core} outside this package (plan
 * amendments F), so it checks everything it relies on itself.
 * <p>
 * The reader is strict, since it reads pinned files (28:631): {@code STARTFONT 2.1} on the first line; the keywords of
 * the format only, each with its exact number of integers; {@code STARTPROPERTIES} with as many properties as it
 * announces, {@code FONT_ASCENT} and {@code FONT_DESCENT} among them; {@code CHARS} with as many glyphs; each glyph
 * with one {@code ENCODING}, {@code DWIDTH} and {@code BBX} before its {@code BITMAP}, exactly {@code BBX}'s height in
 * rows of exactly 2·max(1, ⌈w / 8⌉) hex digits, and {@code ENDCHAR}; then {@code ENDFONT} and nothing after it. Every
 * malformation is an {@link IllegalArgumentException} whose message starts with {@code "line <n>: "}, n counting from
 * 1.
 * Lines end in LF or CRLF; bytes are read as ISO-8859-1, so the reader never fails on a byte, and the converter checks
 * that what it copies into its header is ASCII. Blank lines outside a {@code BITMAP} are skipped. {@code COMMENT} may
 * stand anywhere between {@code STARTFONT} and {@code ENDFONT} outside a {@code BITMAP}.
 */
public final class Bdf {

    /** The widest glyph box a row's int holds: 4 bytes. M0's font 0 is 6 wide, M1's 12x13ja 12 (29 §5.3). */
    public static final int MAX_BBX_WIDTH = 32;

    private final int[] fontBoundingBox;
    private final int ascent;
    private final int descent;
    private final Map<String, String> properties;
    private final List<String> comments;
    private final List<Glyph> glyphs;

    private Bdf(int[] fontBoundingBox, int ascent, int descent, Map<String, String> properties, List<String> comments,
        List<Glyph> glyphs) {
        this.fontBoundingBox = fontBoundingBox;
        this.ascent = ascent;
        this.descent = descent;
        this.properties = Collections.unmodifiableMap(properties);
        this.comments = Collections.unmodifiableList(comments);
        this.glyphs = Collections.unmodifiableList(glyphs);
    }

    /**
     * Reads a whole BDF file.
     *
     * @throws IllegalArgumentException naming the line, on malformed input
     */
    public static Bdf parse(byte[] file) {
        return new Parser(file).parse();
    }

    /** {@code FONTBOUNDINGBOX}'s four integers: w, h, x offset, y offset; a copy. */
    public int[] fontBoundingBox() {
        return fontBoundingBox.clone();
    }

    /** The {@code FONT_ASCENT} property. */
    public int ascent() {
        return ascent;
    }

    /** The {@code FONT_DESCENT} property. */
    public int descent() {
        return descent;
    }

    /** {@code COPYRIGHT}'s value, unquoted, its spaces kept byte for byte; null if the font has none. */
    public String copyright() {
        return properties.get("COPYRIGHT");
    }

    /**
     * A property's value: a string unquoted (its {@code ""} read as one quote), anything else as written after the
     * name and its separating blanks; null if the font has no such property.
     */
    public String property(String name) {
        return properties.get(name);
    }

    /** The {@code COMMENT} lines in file order, each the text after {@code COMMENT} and one blank, verbatim. */
    public List<String> comments() {
        return comments;
    }

    /** Every glyph in file order, the unencoded ones ({@code ENCODING -1}) included. */
    public List<Glyph> glyphs() {
        return glyphs;
    }

    /** One {@code STARTCHAR} ... {@code ENDCHAR} block. */
    public static final class Glyph {

        /** {@code STARTCHAR}'s name, as written. */
        public final String name;
        /** {@code STARTCHAR}'s line, from 1, for the converter's messages. */
        public final int line;
        /**
         * {@code ENCODING}'s first integer: the codepoint, or -1 for a glyph outside the font's encoding. The optional
         * second integer of {@code ENCODING -1 n}, an index in a non-standard encoding, is read and not kept.
         */
        public final int encoding;
        /** {@code DWIDTH}'s two integers, the advance. */
        public final int dwidthX;
        public final int dwidthY;
        /** {@code BBX}'s w, h, x offset and y offset; w is 0 to {@link #MAX_BBX_WIDTH}, h at least 0. */
        public final int bbxW;
        public final int bbxH;
        public final int bbxX;
        public final int bbxY;
        /**
         * The {@code BITMAP}'s bbxH rows, top first, each the value of its hex digits: {@link #rowBytes} bytes, MSB
         * leftmost, so column b of a glyph at most 8 wide is bit 7 - b.
         */
        public final int[] rows;

        Glyph(String name, int line, int encoding, int dwidthX, int dwidthY, int bbxW, int bbxH, int bbxX, int bbxY,
            int[] rows) {
            this.name = name;
            this.line = line;
            this.encoding = encoding;
            this.dwidthX = dwidthX;
            this.dwidthY = dwidthY;
            this.bbxW = bbxW;
            this.bbxH = bbxH;
            this.bbxX = bbxX;
            this.bbxY = bbxY;
            this.rows = rows;
        }

        /** Bytes per row, max(1, ⌈bbxW / 8⌉): BDF pads each row to whole bytes. */
        public int rowBytes() {
            return rowBytes(bbxW);
        }

        static int rowBytes(int width) {
            return Math.max(1, (width + 7) / 8);
        }
    }

    /** One pass over the lines; {@link #lineNo} is the current line's number from 1. */
    private static final class Parser {

        private final byte[] file;
        private int pos;
        private int lineNo;

        private int[] fontBoundingBox;
        private final Map<String, String> properties = new LinkedHashMap<String, String>();
        private final List<String> comments = new ArrayList<String>();
        private final List<Glyph> glyphs = new ArrayList<Glyph>();

        Parser(byte[] file) {
            this.file = file;
        }

        Bdf parse() {
            String first = nextLine();
            if (!"STARTFONT 2.1".equals(first)) {
                throw fail("expected STARTFONT 2.1, found " + quote(first));
            }
            int chars = -1;
            boolean sawProperties = false;
            // The global section, up to CHARS
            while (chars < 0) {
                String line = nextContentLine("CHARS");
                String keyword = keyword(line);
                if ("COMMENT".equals(keyword)) {
                    comments.add(commentText(line));
                } else if ("FONTBOUNDINGBOX".equals(keyword)) {
                    if (fontBoundingBox != null) {
                        throw fail("a second FONTBOUNDINGBOX");
                    }
                    fontBoundingBox = ints(line, 4, 4);
                    if (fontBoundingBox[0] < 0 || fontBoundingBox[1] < 0) {
                        throw fail("FONTBOUNDINGBOX has a negative size");
                    }
                } else if ("STARTPROPERTIES".equals(keyword)) {
                    if (sawProperties) {
                        throw fail("a second STARTPROPERTIES");
                    }
                    sawProperties = true;
                    readProperties(nonNegative(ints(line, 1, 1)[0], "STARTPROPERTIES"));
                } else if ("CHARS".equals(keyword)) {
                    chars = nonNegative(ints(line, 1, 1)[0], "CHARS");
                } else if ("FONT".equals(keyword) || "SIZE".equals(keyword)
                    || "CONTENTVERSION".equals(keyword)
                    || "METRICSSET".equals(keyword)
                    || "SWIDTH".equals(keyword)
                    || "DWIDTH".equals(keyword)
                    || "SWIDTH1".equals(keyword)
                    || "DWIDTH1".equals(keyword)
                    || "VVECTOR".equals(keyword)) {
                        // Read and not needed: the name, the point size and the global metrics
                    } else {
                        throw fail("unexpected " + quote(keyword) + " before CHARS");
                    }
            }
            int charsLine = lineNo;
            if (fontBoundingBox == null) {
                throw fail("no FONTBOUNDINGBOX before CHARS");
            }
            if (!sawProperties) {
                throw fail("no STARTPROPERTIES before CHARS");
            }
            int ascent = intProperty("FONT_ASCENT");
            int descent = intProperty("FONT_DESCENT");
            // The glyphs, then ENDFONT
            while (true) {
                String line = nextContentLine("ENDFONT");
                String keyword = keyword(line);
                if ("COMMENT".equals(keyword)) {
                    comments.add(commentText(line));
                } else if ("STARTCHAR".equals(keyword)) {
                    glyphs.add(readGlyph(line));
                } else if ("ENDFONT".equals(keyword) && line.length() == keyword.length()) {
                    break;
                } else {
                    throw fail("expected STARTCHAR or ENDFONT, found " + quote(line));
                }
            }
            if (glyphs.size() != chars) {
                throw fail("CHARS " + chars + " at line " + charsLine + ", but " + glyphs.size() + " glyphs");
            }
            String after = nextLine();
            while (after != null && after.isEmpty()) {
                after = nextLine();
            }
            if (after != null) {
                throw fail(quote(after) + " after ENDFONT");
            }
            return new Bdf(fontBoundingBox, ascent, descent, properties, comments, glyphs);
        }

        private void readProperties(int count) {
            int read = 0;
            while (read < count) {
                String line = nextContentLine("ENDPROPERTIES");
                String name = keyword(line);
                if ("COMMENT".equals(name)) {
                    comments.add(commentText(line));
                    continue;
                }
                if ("ENDPROPERTIES".equals(name)) {
                    throw fail("STARTPROPERTIES " + count + ", but ENDPROPERTIES after " + read);
                }
                if (name.isEmpty()) {
                    throw fail("a property line starts with a blank");
                }
                if (properties.containsKey(name)) {
                    throw fail("a second property " + name);
                }
                String value = propertyValue(line, name);
                if (("FONT_ASCENT".equals(name) || "FONT_DESCENT".equals(name)) && parseInt(value) == null) {
                    throw fail("property " + name + " is " + quote(value) + ", not an integer");
                }
                properties.put(name, value);
                read++;
            }
            String end = nextContentLine("ENDPROPERTIES");
            while ("COMMENT".equals(keyword(end))) {
                comments.add(commentText(end));
                end = nextContentLine("ENDPROPERTIES");
            }
            if (!"ENDPROPERTIES".equals(end)) {
                throw fail("STARTPROPERTIES " + count + ", but " + quote(end) + " where ENDPROPERTIES belongs");
            }
        }

        /** The value after the name and its blanks: a quoted string unquoted, anything else as written. */
        private String propertyValue(String line, String name) {
            int start = name.length();
            while (start < line.length() && isBlank(line.charAt(start))) {
                start++;
            }
            String raw = line.substring(start);
            if (raw.isEmpty()) {
                throw fail("property " + name + " has no value");
            }
            if (raw.charAt(0) != '"') {
                return raw;
            }
            if (raw.length() < 2 || raw.charAt(raw.length() - 1) != '"') {
                throw fail("property " + name + "'s string has no closing quote");
            }
            StringBuilder out = new StringBuilder(raw.length());
            for (int i = 1; i < raw.length() - 1; i++) {
                char c = raw.charAt(i);
                if (c == '"') {
                    // BDF writes a quote inside a string as two
                    if (i + 1 < raw.length() - 1 && raw.charAt(i + 1) == '"') {
                        i++;
                    } else {
                        throw fail("property " + name + "'s string has a lone quote inside");
                    }
                }
                out.append(c);
            }
            return out.toString();
        }

        /** A property readProperties checked to be an integer; its absence fails at CHARS's line. */
        private int intProperty(String name) {
            String value = properties.get(name);
            if (value == null) {
                throw fail("no " + name + " property before CHARS");
            }
            return parseInt(value);
        }

        private Glyph readGlyph(String startLine) {
            int startNo = lineNo;
            String name = startLine.length() > "STARTCHAR".length() ? startLine.substring("STARTCHAR".length() + 1)
                : "";
            Integer encoding = null;
            int[] dwidth = null;
            int[] bbx = null;
            while (true) {
                String line = nextContentLine("ENDCHAR of " + quote(name) + " (line " + startNo + ")");
                String keyword = keyword(line);
                if ("COMMENT".equals(keyword)) {
                    comments.add(commentText(line));
                } else if ("ENCODING".equals(keyword)) {
                    if (encoding != null) {
                        throw fail("a second ENCODING");
                    }
                    int[] values = ints(line, 1, 2);
                    if (values[0] < -1) {
                        throw fail("ENCODING " + values[0] + " is below -1");
                    }
                    if (values.length == 2 && values[0] != -1) {
                        throw fail("a second ENCODING integer follows -1 only, not " + values[0]);
                    }
                    encoding = values[0];
                } else if ("DWIDTH".equals(keyword)) {
                    if (dwidth != null) {
                        throw fail("a second DWIDTH");
                    }
                    dwidth = ints(line, 2, 2);
                } else if ("BBX".equals(keyword)) {
                    if (bbx != null) {
                        throw fail("a second BBX");
                    }
                    bbx = ints(line, 4, 4);
                    if (bbx[0] < 0 || bbx[0] > MAX_BBX_WIDTH || bbx[1] < 0) {
                        throw fail(
                            "BBX " + bbx[0]
                                + " "
                                + bbx[1]
                                + " is outside 0.."
                                + MAX_BBX_WIDTH
                                + " wide, 0 or more high");
                    }
                } else if ("SWIDTH".equals(keyword) || "SWIDTH1".equals(keyword)
                    || "DWIDTH1".equals(keyword)
                    || "VVECTOR".equals(keyword)
                    || "ATTRIBUTES".equals(keyword)) {
                        // Read and not needed: scalable widths, the vertical metrics and attributes
                    } else if ("BITMAP".equals(keyword) && line.length() == keyword.length()) {
                        break;
                    } else {
                        throw fail(
                            "unexpected " + quote(line) + " in glyph " + quote(name) + " (line " + startNo + ")");
                    }
            }
            if (encoding == null || dwidth == null || bbx == null) {
                throw fail(
                    "glyph " + quote(name)
                        + " (line "
                        + startNo
                        + ") lacks "
                        + (encoding == null ? "ENCODING" : dwidth == null ? "DWIDTH" : "BBX")
                        + " before BITMAP");
            }
            int digits = 2 * Glyph.rowBytes(bbx[0]);
            int[] rows = new int[bbx[1]];
            for (int r = 0; r < rows.length; r++) {
                String line = nextLine();
                if (line == null) {
                    throw fail("the file ends in glyph " + quote(name) + "'s BITMAP");
                }
                if ("ENDCHAR".equals(line)) {
                    throw fail("glyph " + quote(name) + "'s BITMAP has " + r + " rows, BBX says " + bbx[1]);
                }
                rows[r] = hexRow(line, digits);
            }
            String end = nextLine();
            if (end == null) {
                throw fail("the file ends before ENDCHAR of " + quote(name) + " (line " + startNo + ")");
            }
            if (!"ENDCHAR".equals(end)) {
                String next = keyword(end);
                if ("STARTCHAR".equals(next) || "ENDFONT".equals(next)) {
                    throw fail("no ENDCHAR for glyph " + quote(name) + " (line " + startNo + ") before " + next);
                }
                throw fail(
                    "glyph " + quote(name)
                        + "'s BITMAP has more than the "
                        + bbx[1]
                        + " rows BBX says: "
                        + quote(end)
                        + " where ENDCHAR belongs");
            }
            return new Glyph(name, startNo, encoding, dwidth[0], dwidth[1], bbx[0], bbx[1], bbx[2], bbx[3], rows);
        }

        private int hexRow(String line, int digits) {
            if (line.length() != digits) {
                throw fail("bitmap row " + quote(line) + " has " + line.length() + " hex digits, not " + digits);
            }
            long value = 0;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                // ASCII hex only: Character.digit would also take other scripts' digits
                int d = c >= '0' && c <= '9' ? c - '0'
                    : c >= 'A' && c <= 'F' ? c - 'A' + 10 : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1;
                if (d < 0) {
                    throw fail("bitmap row " + quote(line) + " is not hex");
                }
                value = value << 4 | d;
            }
            return (int) value;
        }

        /** The keyword's integers, at least min and at most max of them, and nothing else. */
        private int[] ints(String line, int min, int max) {
            String[] words = words(line);
            int n = words.length - 1;
            if (n < min || n > max) {
                String counts = min == max ? "" + min : min + " or " + max;
                throw fail(words[0] + " takes " + counts + " integers: " + quote(line));
            }
            int[] out = new int[n];
            for (int i = 0; i < n; i++) {
                Integer value = parseInt(words[i + 1]);
                if (value == null) {
                    throw fail(words[0] + "'s " + quote(words[i + 1]) + " is not an integer");
                }
                out[i] = value;
            }
            return out;
        }

        private int nonNegative(int value, String keyword) {
            if (value < 0) {
                throw fail(keyword + " " + value + " is negative");
            }
            return value;
        }

        /** The next line that is not blank; the file's end is an error naming what was still expected. */
        private String nextContentLine(String expected) {
            while (true) {
                String line = nextLine();
                if (line == null) {
                    throw fail("the file ends before " + expected);
                }
                if (!line.isEmpty()) {
                    return line;
                }
            }
        }

        /** The next line without its LF or CRLF, as ISO-8859-1; null at the end of the file. */
        private String nextLine() {
            if (pos >= file.length) {
                // Past the end, so that a message names the line after the last one
                lineNo++;
                return null;
            }
            int end = pos;
            while (end < file.length && file[end] != '\n') {
                end++;
            }
            // One CR before the LF, or before the file's end, belongs to the line end
            int stop = end > pos && file[end - 1] == '\r' ? end - 1 : end;
            char[] chars = new char[stop - pos];
            for (int i = 0; i < chars.length; i++) {
                chars[i] = (char) (file[pos + i] & 0xFF);
            }
            pos = end + 1;
            lineNo++;
            return new String(chars);
        }

        private IllegalArgumentException fail(String reason) {
            return new IllegalArgumentException("line " + lineNo + ": " + reason);
        }
    }

    /** The line's first word, up to the first blank. */
    static String keyword(String line) {
        int i = 0;
        while (i < line.length() && !isBlank(line.charAt(i))) {
            i++;
        }
        return line.substring(0, i);
    }

    /** COMMENT's text: everything after the keyword and one blank, verbatim; "" for a bare COMMENT. */
    static String commentText(String line) {
        return line.length() > "COMMENT".length() ? line.substring("COMMENT".length() + 1) : "";
    }

    /** The words between runs of blanks; the first is the keyword, possibly "". */
    static String[] words(String line) {
        List<String> out = new ArrayList<String>();
        int i = 0;
        while (true) {
            int start = i;
            while (i < line.length() && !isBlank(line.charAt(i))) {
                i++;
            }
            out.add(line.substring(start, i));
            while (i < line.length() && isBlank(line.charAt(i))) {
                i++;
            }
            if (i >= line.length()) {
                return out.toArray(new String[0]);
            }
        }
    }

    static boolean isBlank(char c) {
        return c == ' ' || c == '\t';
    }

    /** A decimal integer, an optional '-' then 1-9 digits; null for anything else. */
    static Integer parseInt(String s) {
        int i = s.startsWith("-") ? 1 : 0;
        if (s.length() == i || s.length() - i > 9) {
            return null;
        }
        for (int k = i; k < s.length(); k++) {
            if (s.charAt(k) < '0' || s.charAt(k) > '9') {
                return null;
            }
        }
        return Integer.valueOf(Integer.parseInt(s));
    }

    static String quote(String s) {
        return s == null ? "the end of the file" : "\"" + s + "\"";
    }
}
