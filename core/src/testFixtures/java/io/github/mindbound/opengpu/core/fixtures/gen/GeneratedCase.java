package io.github.mindbound.opengpu.core.fixtures.gen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.fixtures.MapLiveView;
import io.github.mindbound.opengpu.core.fixtures.TestEncoder;
import io.github.mindbound.opengpu.core.fixtures.TierEOracle;
import io.github.mindbound.opengpu.core.font.GlyphTable;

/**
 * One case of {@link SegmentGenerator}: a device's surface, its caps, the open frame's totals, the live textures with
 * their contents, and one generated segment with its record offsets (28 §11.3). A clean case has every field valid,
 * live handles only, font 0, valid strings, empty open totals and its tier's caps, so validation accepts it; S8 runs
 * the clean cases on {@code GlTestHost} against the oracle (28 §11.4). Immutable: every array is copied in and out.
 */
public final class GeneratedCase {

    /** A live texture: its handle, size, format code and texels in the 28 §5.7 layout (rows top first). */
    public static final class Texture {

        public final int handle;
        public final int width;
        public final int height;
        public final int format;
        private final byte[] texels;

        Texture(int handle, int width, int height, int format, byte[] texels) {
            this.handle = handle;
            this.width = width;
            this.height = height;
            this.format = format;
            this.texels = texels.clone();
        }

        /** The texels in the 28 §5.7 layout, w·h·bpp bytes: a fresh copy. */
        public byte[] texels() {
            return texels.clone();
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "0x%08X %dx%d format %d", handle, width, height, format);
        }
    }

    private final long seed;
    private final SurfaceDesc surface;
    private final FrameLimits limits;
    private final FrameTotals open;
    private final List<Texture> textures;
    private final byte[] segment;
    private final int[] recordOffsets;
    private final int[] countOffsets;
    private final boolean clean;

    GeneratedCase(long seed, SurfaceDesc surface, FrameLimits limits, FrameTotals open, List<Texture> textures,
        byte[] segment, int[] recordOffsets, int[] countOffsets, boolean clean) {
        this.seed = seed;
        this.surface = surface;
        this.limits = limits;
        this.open = open;
        this.textures = Collections.unmodifiableList(new ArrayList<>(textures));
        this.segment = segment.clone();
        this.recordOffsets = recordOffsets.clone();
        this.countOffsets = countOffsets.clone();
        this.clean = clean;
    }

    /** The seed of the {@code SplittableRandom} the case was drawn from, for failure messages. */
    public long seed() {
        return seed;
    }

    public SurfaceDesc surface() {
        return surface;
    }

    public FrameLimits limits() {
        return limits;
    }

    /** The open frame's totals before the segment: {@link FrameTotals#EMPTY} for a clean case. */
    public FrameTotals open() {
        return open;
    }

    /** The live textures, in the order they became live; a texture of the other format may be among them. */
    public List<Texture> textures() {
        return textures;
    }

    /** The generated segment, unmutated: a fresh copy. */
    public byte[] segment() {
        return segment.clone();
    }

    /** The offset of each record's opcode byte in {@link #segment()}, in order; {@code END} is not a record. */
    public int[] recordOffsets() {
        return recordOffsets.clone();
    }

    public int recordCount() {
        return recordOffsets.length;
    }

    /** The offset of each {@code TEXT} record's u16 count (its opcode's offset + 15, 28 §5.2), in order. */
    public int[] countOffsets() {
        return countOffsets.clone();
    }

    /** Whether the case is clean, and so accepted unmutated. */
    public boolean isClean() {
        return clean;
    }

    /** A live view of the surface and caps with exactly {@link #textures()} live (28 §2.2). */
    public MapLiveView view() {
        MapLiveView view = new MapLiveView(surface, limits);
        for (Texture t : textures) {
            view.put(t.handle, t.width, t.height, t.format);
        }
        return view;
    }

    /** A tier-E oracle on the surface with {@link #textures()} created and filled (28 §5.7), as the host has them. */
    public TierEOracle oracle(GlyphTable glyphs) {
        TierEOracle oracle = new TierEOracle(surface, glyphs);
        for (Texture t : textures) {
            oracle.createTexture(t.handle, t.width, t.height, t.format);
            oracle.updateTexture(t.handle, 0, 0, t.width, t.height, t.texels);
        }
        return oracle;
    }

    /** The case in one line, the segment in hex: what a fuzz failure prints. */
    public String describe() {
        return String.format(
            Locale.ROOT,
            "seed %d, %s, %s, %s, %s, textures %s, %d records, segment %s",
            seed,
            clean ? "clean" : "rough",
            surface,
            limits,
            open,
            textures,
            recordOffsets.length,
            TestEncoder.toHex(segment));
    }

    @Override
    public String toString() {
        return describe();
    }
}
