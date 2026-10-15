package io.github.mindbound.opengpu.core.fixtures.gen;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.cmd.HandleTypes;
import io.github.mindbound.opengpu.core.fixtures.MapLiveView;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;

/**
 * The fuzzer's grammar-based, edge-heavy segment generator (28 §11.3), in the test fixtures so that S8 can run the
 * same valid cases on {@code GlTestHost} (28 §11.4). It is seeded and deterministic: a case is a function of its seed
 * alone, and a run's case i has the seed {@link #caseSeed}{@code (baseSeed, i)}. It uses the JDK, {@code :core}'s main
 * constants and the fixtures' {@link MapLiveView} and {@link TestEncoder}; it computes no validation result.
 * <ul>
 * <li>Surfaces: index8 8x6 and 17x13, rgb565 9x7 and 16x16 (75 %), T2 320x200 index8 and T3 640x400 rgb565
 * (25 %).</li>
 * <li>Clean cases (25 %, {@link #clean}): every field valid, live handles of the surface's format, font 0, valid
 * strings, empty open totals and the caps of the surface's tier (T2's on a small index8 surface, T3's on a small
 * rgb565 one), with records dropped while they would pass the fill cap. They are accepted unmutated.</li>
 * <li>Rough cases (75 %): the tier's caps (80 %) or small random ones (20 %); open totals empty (70 %) or near and at
 * the caps; besides live textures, freed handles, a texture of the other format (2 %) and handles of every bad kind;
 * colours, flags, fonts and strings that are invalid now and then. The fuzzer then mutates them.</li>
 * </ul>
 * Records number 0-8 (70 %) or 9-40 (30 %), weighted {@code CLEAR} 1, {@code FILL_RECT} 3, {@code BLIT} 3 and
 * {@code TEXT} 3. i16 fields take 28 §11.1's edges for their axis, -32 768, -1, 0, 1, W − 1, W and 32 767 (40 %), or
 * values around the surface or texture (60 %). Strings are 0-40 bytes of ASCII, Latin-1, U+20AC, U+4E00, U+1F600 and
 * random scalar values, or exactly 1 024 bytes (1 %).
 */
public final class SegmentGenerator {

    /** The fuzzer's default base seed. */
    public static final long DEFAULT_SEED = 28;

    private static final SurfaceDesc[] SMALL = { new SurfaceDesc(0, 8, 6, Formats.INDEX8),
        new SurfaceDesc(0, 17, 13, Formats.INDEX8), new SurfaceDesc(0, 9, 7, Formats.RGB565),
        new SurfaceDesc(0, 16, 16, Formats.RGB565) };
    private static final int[] SIDES = { 1, 2, 3, 7, 16, 24 };
    /** Font 0's fill per codepoint, 6·13 (28 §3.5); written out here rather than taken from {@code Fonts}. */
    private static final int CELL_FILL = 78;
    private static final int TEXT_MAX_BYTES = 1024;

    private SegmentGenerator() {}

    /** The seed of a run's case i: {@code baseSeed * 1 000 003 + index}. */
    public static long caseSeed(long baseSeed, long index) {
        return baseSeed * 1_000_003L + index;
    }

    /** A clean case (25 %) or a rough one (75 %), drawn from {@code new SplittableRandom(seed)}. */
    public static GeneratedCase generate(long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        boolean clean = random.nextInt(4) == 0;
        return new Builder(seed, random, clean, null).build();
    }

    /** A clean case on a surface drawn as {@link #generate} draws it: accepted unmutated. */
    public static GeneratedCase clean(long seed) {
        return new Builder(seed, new SplittableRandom(seed), true, null).build();
    }

    /**
     * A clean case on the given surface, with the caps of its tier (2 or 3), or for tier 0 those of the tier whose
     * format it has: what S8 runs on {@code GlTestHost} (28 §11.4).
     */
    public static GeneratedCase clean(long seed, SurfaceDesc surface) {
        if (surface == null) {
            throw new NullPointerException("surface");
        }
        return new Builder(seed, new SplittableRandom(seed), true, surface).build();
    }

    /** One case under construction. */
    private static final class Builder {

        private final long seed;
        private final SplittableRandom r;
        private final boolean clean;
        private final SurfaceDesc surface;
        private final FrameLimits limits;
        private final int width;
        private final int height;
        /** Allocates handles as 28 §6.1 does: FIFO slots, a generation per slot. */
        private final MapLiveView allocator;
        private final List<GeneratedCase.Texture> textures = new ArrayList<>();
        /** Live handles of the surface's format, which a clean BLIT draws from. */
        private final List<GeneratedCase.Texture> sameFormat = new ArrayList<>();
        private final List<Integer> freed = new ArrayList<>();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final List<Integer> recordOffsets = new ArrayList<>();
        private final List<Integer> countOffsets = new ArrayList<>();
        private FrameTotals open = FrameTotals.EMPTY;
        /** The fill a clean case's records add up to, kept within the cap. */
        private long fill;

        Builder(long seed, SplittableRandom random, boolean clean, SurfaceDesc surface) {
            this.seed = seed;
            this.r = random;
            this.clean = clean;
            this.surface = surface != null ? surface : drawSurface();
            this.width = this.surface.width;
            this.height = this.surface.height;
            this.limits = clean || r.nextInt(5) != 0 ? tierLimits(this.surface) : smallLimits();
            this.allocator = new MapLiveView(this.surface, limits);
        }

        GeneratedCase build() {
            if (!clean && r.nextInt(10) >= 7) {
                open = drawOpen();
            }
            drawTextures();
            write(TestEncoder.header());
            int records = r.nextInt(10) < 7 ? r.nextInt(9) : r.nextInt(9, 41);
            for (int k = 0; k < records; k++) {
                int pick = r.nextInt(10);
                if (pick == 0) {
                    clear();
                } else if (pick < 4) {
                    fillRect();
                } else if (pick < 7) {
                    blit();
                } else {
                    text();
                }
            }
            write(TestEncoder.end());
            return new GeneratedCase(
                seed,
                surface,
                limits,
                open,
                textures,
                out.toByteArray(),
                toArray(recordOffsets),
                toArray(countOffsets),
                clean);
        }

        // The case's setting

        private SurfaceDesc drawSurface() {
            if (r.nextInt(4) != 0) {
                return SMALL[r.nextInt(SMALL.length)];
            }
            return SurfaceDesc.forTier(r.nextBoolean() ? 2 : 3);
        }

        private static FrameLimits tierLimits(SurfaceDesc surface) {
            if (surface.tier == 2 || surface.tier == 3) {
                return FrameLimits.forTier(surface.tier);
            }
            return FrameLimits.forTier(surface.format == Formats.INDEX8 ? 2 : 3);
        }

        /** Small random caps: submit 16-2 048 B, frame 9-4 096 B, 1-24 records, 0-5 000 px. */
        private FrameLimits smallLimits() {
            return new FrameLimits(r.nextInt(16, 2049), r.nextInt(9, 4097), r.nextInt(1, 25), r.nextInt(0, 5001));
        }

        /** Open totals below each cap, near it or exactly at it. */
        private FrameTotals drawOpen() {
            int bytes = (int) nearCap(limits.frameBytes, 200);
            int records = (int) nearCap(limits.frameRecords, 10);
            long pixels = nearCap(limits.frameFill, 2000);
            // The open frame's fill, spread over the four classes
            long clearPx = pixels == 0 ? 0 : r.nextLong(pixels + 1);
            long fillPx = pixels - clearPx == 0 ? 0 : r.nextLong(pixels - clearPx + 1);
            long copyPx = pixels - clearPx - fillPx == 0 ? 0 : r.nextLong(pixels - clearPx - fillPx + 1);
            long setPx = pixels - clearPx - fillPx - copyPx;
            return new FrameTotals(bytes, records, clearPx, fillPx, copyPx, setPx);
        }

        /** 0 (40 %), uniform up to the cap (30 %), within slack below it (20 %) or the cap itself (10 %). */
        private long nearCap(long cap, long slack) {
            int roll = r.nextInt(10);
            if (roll < 4) {
                return 0;
            }
            if (roll < 7) {
                return r.nextLong(cap + 1);
            }
            if (roll < 9) {
                return Math.max(0, cap - r.nextLong(slack + 1));
            }
            return cap;
        }

        private void drawTextures() {
            int count = clean ? r.nextInt(1, 5) : r.nextInt(5);
            for (int k = 0; k < count; k++) {
                int w;
                int h;
                if (r.nextInt(100) < 3) {
                    boolean row = r.nextBoolean();
                    w = row ? 1024 : 64;
                    h = row ? 1 : 64;
                } else {
                    w = SIDES[r.nextInt(SIDES.length)];
                    h = SIDES[r.nextInt(SIDES.length)];
                }
                int handle = allocator.create(w, h);
                GeneratedCase.Texture t = new GeneratedCase.Texture(
                    handle,
                    w,
                    h,
                    surface.format,
                    texels(w, h, surface.format));
                textures.add(t);
                sameFormat.add(t);
            }
            if (clean) {
                return;
            }
            if (r.nextInt(5) == 0) {
                // A freed handle, kept for STALE_HANDLE (28 §6.1: the slot's generation moves on)
                int handle = allocator.create(SIDES[r.nextInt(SIDES.length)], SIDES[r.nextInt(SIDES.length)]);
                allocator.free(handle);
                freed.add(handle);
            }
            if (r.nextInt(50) == 0) {
                // A live texture of the other format, for WRONG_FORMAT (28 §3.6 row 19), on a slot create never uses
                int other = surface.format == Formats.INDEX8 ? Formats.RGB565 : Formats.INDEX8;
                int handle = Handles.pack(HandleTypes.TEXTURE, r.nextInt(1000, 2000), r.nextInt(65536));
                int w = SIDES[r.nextInt(SIDES.length)];
                int h = SIDES[r.nextInt(SIDES.length)];
                textures.add(new GeneratedCase.Texture(handle, w, h, other, texels(w, h, other)));
            }
        }

        /** Random texels in the 28 §5.7 layout, about 30 % of them 0 (for KEY0). */
        private byte[] texels(int w, int h, int format) {
            int bpp = Formats.bytesPerPixel(format);
            byte[] texels = new byte[w * h * bpp];
            for (int i = 0; i < w * h; i++) {
                int value = r.nextInt(10) < 3 ? 0 : bpp == 1 ? r.nextInt(1, 256) : r.nextInt(1, 65536);
                texels[i * bpp] = (byte) value;
                if (bpp == 2) {
                    texels[i * bpp + 1] = (byte) (value >>> 8);
                }
            }
            return texels;
        }

        // Records

        private void clear() {
            emit(TestEncoder.clear(colour()), (long) width * height, false);
        }

        private void fillRect() {
            int x = coordinate(width);
            int y = coordinate(height);
            int w = extent(width);
            int h = extent(height);
            emit(TestEncoder.fill(x, y, w, h, colour()), rectArea(x, y, w, h), false);
        }

        private void blit() {
            GeneratedCase.Texture t = null;
            int handle;
            if (clean) {
                t = sameFormat.get(r.nextInt(sameFormat.size()));
                handle = t.handle;
            } else if (!textures.isEmpty() && r.nextInt(100) < 85) {
                t = textures.get(r.nextInt(textures.size()));
                handle = t.handle;
            } else {
                handle = badHandle();
            }
            int texW = t != null ? t.width : SIDES[r.nextInt(SIDES.length)];
            int texH = t != null ? t.height : SIDES[r.nextInt(SIDES.length)];
            int sx = sourceCoordinate(texW);
            int sy = sourceCoordinate(texH);
            int w = extent(texW);
            int h = extent(texH);
            int dx = coordinate(width);
            int dy = coordinate(height);
            int flags = flags();
            long area = t == null ? 0 : blitArea(sx, sy, w, h, dx, dy, t.width, t.height);
            emit(TestEncoder.blit(handle, sx, sy, w, h, dx, dy, flags), area, false);
        }

        private void text() {
            int x = coordinate(width);
            int y = coordinate(height);
            int fg = colour();
            int bg = colour();
            int flags = flags();
            int font = clean || r.nextInt(100) < 95 ? 0 : pick(1, 255, r.nextInt(1, 256));
            StringBuilder count = new StringBuilder();
            byte[] string = string(count);
            emit(TestEncoder.text(x, y, fg, bg, flags, font, string), (long) CELL_FILL * count.length(), true);
        }

        /**
         * Appends the record; a clean case drops it instead when it would take the segment's fill past the cap, and
         * so stays accepted.
         */
        private void emit(byte[] record, long recordFill, boolean text) {
            if (clean) {
                if (fill + recordFill > limits.frameFill) {
                    return;
                }
                fill += recordFill;
            }
            int offset = out.size();
            recordOffsets.add(offset);
            if (text) {
                countOffsets.add(offset + 15);
            }
            write(record);
        }

        // Fields

        /** An x-like field on an axis of the given size: an edge (40 %) or a value around the axis (60 %). */
        private int coordinate(int axis) {
            if (r.nextInt(10) < 4) {
                return edge(axis);
            }
            return r.nextInt(-axis / 2 - 2, axis + axis / 2 + 3);
        }

        /** A source coordinate inside or around a texture's side. */
        private int sourceCoordinate(int side) {
            if (r.nextInt(10) < 4) {
                return edge(side);
            }
            return r.nextInt(-side / 2 - 2, side + 3);
        }

        /** A w- or h-like field: an edge (40 %) or -2 to axis + 4 (60 %). */
        private int extent(int axis) {
            if (r.nextInt(10) < 4) {
                return edge(axis);
            }
            return r.nextInt(-2, axis + 5);
        }

        /** 28 §11.1's i16 edges for an axis: -32 768, -1, 0, 1, axis - 1, axis, 32 767. */
        private int edge(int axis) {
            switch (r.nextInt(7)) {
                case 0:
                    return -32768;
                case 1:
                    return -1;
                case 2:
                    return 0;
                case 3:
                    return 1;
                case 4:
                    return axis - 1;
                case 5:
                    return axis;
                default:
                    return 32767;
            }
        }

        /** A colour valid for the surface (28 §3.4), or (rough cases, 10 %) an invalid one. */
        private int colour() {
            boolean index8 = surface.format == Formats.INDEX8;
            if (!clean && r.nextInt(10) == 0) {
                if (index8) {
                    switch (r.nextInt(5)) {
                        case 0:
                            return 0x100;
                        case 1:
                            return 0xFF000000;
                        case 2:
                            return 0xFFFFFFFF;
                        case 3:
                            return 0x80000000;
                        default:
                            return r.nextInt() | 0x100;
                    }
                }
                switch (r.nextInt(5)) {
                    case 0:
                        return 0x00FF8000;
                    case 1:
                        return 0xFEFFFFFF;
                    case 2:
                        return 0;
                    case 3:
                        return 0x7FFFFFFF;
                    default:
                        return r.nextInt(255) << 24 | r.nextInt(1 << 24);
                }
            }
            if (index8) {
                return r.nextInt(10) < 3 ? pick(0, 1, 254, 255) : r.nextInt(256);
            }
            return r.nextInt(10) < 3 ? pick(0xFF000000, 0xFFFFFFFF, 0xFFFF8000, 0xFF000001)
                : 0xFF000000 | r.nextInt(1 << 24);
        }

        /** BLIT's and TEXT's flags: 0 or bit 0 (all of a clean case's, 90 % of a rough one's), else undefined bits. */
        private int flags() {
            if (clean || r.nextInt(10) != 0) {
                return r.nextInt(2);
            }
            return pick(0x02, 0x04, 0x80, r.nextInt(256));
        }

        /** A handle validation rejects (28 §3.6 rows 15-18), or a live one's wrong generation. */
        private int badHandle() {
            switch (r.nextInt(9)) {
                case 0:
                    if (!freed.isEmpty()) {
                        return freed.get(r.nextInt(freed.size()));
                    }
                    return Handles.pack(HandleTypes.TEXTURE, r.nextInt(2000, 2048), 0);
                case 1:
                    if (!textures.isEmpty()) {
                        // The same slot, another generation
                        return textures.get(r.nextInt(textures.size())).handle ^ 1 << r.nextInt(16);
                    }
                    return Handles.pack(HandleTypes.TEXTURE, 2047, 65535);
                case 2:
                    // Never allocated: create hands out low slots first
                    return Handles.pack(HandleTypes.TEXTURE, r.nextInt(16, 2048), r.nextInt(65536));
                case 3:
                    return 0;
                case 4:
                    return 0x80000000 | r.nextInt();
                case 5:
                    // Type 0 with other bits set
                    return r.nextInt(1, 1 << 27);
                case 6:
                    return Handles.pack(HandleTypes.BUFFER, r.nextInt(2048), r.nextInt(65536));
                case 7:
                    return Handles.pack(9, r.nextInt(2048), r.nextInt(65536));
                default:
                    return Handles.pack(r.nextInt(2, 16), r.nextInt(2048), r.nextInt(65536));
            }
        }

        /**
         * A string's bytes; one char per codepoint is appended to count. A rough case's string holds a control
         * character or an ill-formed sequence now and then (3 %).
         */
        private byte[] string(StringBuilder count) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            boolean full = r.nextInt(100) == 0;
            int target = full ? TEXT_MAX_BYTES : r.nextInt(41);
            int bad = !clean && r.nextInt(100) < 3 ? r.nextInt(target + 1) : -1;
            while (bytes.size() < target) {
                if (bytes.size() >= bad && bad >= 0) {
                    byte[] piece = badPiece();
                    bytes.write(piece, 0, piece.length);
                    bad = -1;
                    continue;
                }
                int cp = codepoint();
                byte[] piece = utf8(cp);
                if (bytes.size() + piece.length > target) {
                    // Pad with ASCII to hit the target exactly
                    cp = r.nextInt(0x20, 0x7F);
                    piece = utf8(cp);
                }
                bytes.write(piece, 0, piece.length);
                count.append('c');
            }
            return bytes.toByteArray();
        }

        private int codepoint() {
            int roll = r.nextInt(100);
            if (roll < 50) {
                return r.nextInt(0x20, 0x7F);
            }
            if (roll < 65) {
                return r.nextInt(0xA0, 0x100);
            }
            if (roll < 73) {
                return 0x20AC;
            }
            if (roll < 81) {
                return 0x4E00;
            }
            if (roll < 88) {
                return 0x1F600;
            }
            // Any scalar value from U+00A0: noncharacters, private use and unassigned ones included
            int cp;
            do {
                cp = r.nextInt(0xA0, 0x110000);
            } while (cp >= 0xD800 && cp <= 0xDFFF);
            return cp;
        }

        /** A control (28 §7.2) or an ill-formed sequence (Table 3-7). */
        private byte[] badPiece() {
            switch (r.nextInt(12)) {
                case 0:
                    return TestEncoder.bytes(0x00);
                case 1:
                    return TestEncoder.bytes(0x0A);
                case 2:
                    return TestEncoder.bytes(0x7F);
                case 3:
                    return TestEncoder.bytes(0xC2, 0x85);
                case 4:
                    return TestEncoder.bytes(0x80);
                case 5:
                    return TestEncoder.bytes(0xC0, 0x80);
                case 6:
                    return TestEncoder.bytes(0xED, 0xA0, 0x80);
                case 7:
                    return TestEncoder.bytes(0xF4, 0x90, 0x80, 0x80);
                case 8:
                    return TestEncoder.bytes(0xFF);
                case 9:
                    return TestEncoder.bytes(0xE2, 0x82);
                case 10:
                    return TestEncoder.bytes(0xE0, 0x9F, 0xBF);
                default:
                    return TestEncoder.bytes(0xC2, 0x41);
            }
        }

        private int pick(int... values) {
            return values[r.nextInt(values.length)];
        }

        // Fill, as 28 §3.5 and §5.7 count it, for a clean case's cap

        private long rectArea(int x, int y, int w, int h) {
            long cols = Math.max(0, Math.min(x + w, width) - Math.max(x, 0));
            long rows = Math.max(0, Math.min(y + h, height) - Math.max(y, 0));
            return cols * rows;
        }

        private long blitArea(int sx, int sy, int w, int h, int dx, int dy, int texW, int texH) {
            long cols = Math.max(0, Math.min(w, Math.min(texW - sx, width - dx)) - Math.max(0, Math.max(-sx, -dx)));
            long rows = Math.max(0, Math.min(h, Math.min(texH - sy, height - dy)) - Math.max(0, Math.max(-sy, -dy)));
            return cols * rows;
        }

        private void write(byte[] bytes) {
            out.write(bytes, 0, bytes.length);
        }
    }

    /** The codepoint's UTF-8 bytes. */
    private static byte[] utf8(int cp) {
        if (cp < 0x80) {
            return TestEncoder.bytes(cp);
        }
        if (cp < 0x800) {
            return TestEncoder.bytes(0xC0 | cp >> 6, 0x80 | cp & 0x3F);
        }
        if (cp < 0x10000) {
            return TestEncoder.bytes(0xE0 | cp >> 12, 0x80 | cp >> 6 & 0x3F, 0x80 | cp & 0x3F);
        }
        return TestEncoder.bytes(0xF0 | cp >> 18, 0x80 | cp >> 12 & 0x3F, 0x80 | cp >> 6 & 0x3F, 0x80 | cp & 0x3F);
    }

    private static int[] toArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }
}
