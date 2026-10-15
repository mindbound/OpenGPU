package io.github.mindbound.opengpu.core.device.model;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.blit;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.bytes;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.concat;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.fill;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.head;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.text;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.with;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.HandleTypes;
import io.github.mindbound.opengpu.core.cmd.Opcodes;
import io.github.mindbound.opengpu.core.device.DeviceCaps;

/**
 * The op stream of the model test (S7 plan §4.5) and of {@code DeviceConcurrencyTest}'s worker: a seeded, edge-heavy
 * mix of the M0 callbacks (28 §2.1, §3.6), host actions, ticks, resets and host switches. Each op is drawn from the
 * device's state as the caller reports it (the reference model's live handles and VRAM use), plus what this generator
 * was told of every successful create, so a sequence is a function of its seed alone. Not thread-safe.
 * <p>
 * Weights, out of 103 (the plan's 100 plus amendment A's churn):
 * <ul>
 * <li>{@code createTexture} 14: 70 % sides 1-8; 10 % invalid sides, the ±10^10 saturations included (28:920; OC's
 * {@code checkInteger} gives ±2^31); 10 % near the caps (one side at {@code maxTextureSize} or one past it, both at
 * it, or a texture that fills what is left of the VRAM, or one row more); 10 % format strings (28 §6.2);</li>
 * <li>{@code updateTexture} 14: handles 60 % live, 15 % freed or of a wrong generation, 10 % never allocated, 15 %
 * garbage (0, {@code 0x7FFFFFFF}, {@code 0x80000000}, −1, type 0 with other bits, types 2-7, 8-15; 28:336); regions
 * 70 % valid, 30 % at an edge or outside, the saturations included; data 90 % of the right length, 10 % one byte
 * off (28 §6.3);</li>
 * <li>{@code free} 8, with handles drawn as above (28 §6.4);</li>
 * <li>{@code submit} 20: 1-6 records of {@code CLEAR}, {@code FILL_RECT}, {@code BLIT} of a handle drawn as above and
 * {@code TEXT} (font 0, ASCII, 2-, 3- and 4-byte UTF-8); 10 % a malformed segment (a header field, framing, an
 * unknown opcode, a colour, a font, UTF-8, a control character, flags, a cut record, one byte past the submit cap,
 * a short header, the bare header; 28 §3.6). The frame caps of 28 §8.1 bind across submits by themselves;</li>
 * <li>{@code present} 12; host actions 15 ({@code run} of 0-5 records, {@code readback}, {@code step}, 5 each); tick
 * 8 (the clock + 0-700 ms); {@code readPixels} 5 (60 % inside, 10 % the whole surface, 5 % the whole rows that
 * fit the readback cap or one row more, 25 % at an edge or outside); reset 2; host switches 2 ({@code available} or
 * {@code stalled} flipped, flipped back 1-5 ops later);
 * churn 3 (amendment A, critique B1: a 9-byte {@code submit}, then 1-40 create/free pairs of 1-4 px sides, which reach
 * H23's bound on the small caps).</li>
 * </ul>
 * {@link #nextCallback} draws only the callbacks ({@code createTexture} to {@code present} and {@code readPixels}, in
 * the same proportions), for a worker that runs no host.
 */
public final class OpGenerator {

    /** What an op does. */
    public enum Kind {
        CREATE,
        UPDATE,
        FREE,
        SUBMIT,
        PRESENT,
        READ_PIXELS,
        CHURN,
        RUN,
        READBACK,
        STEP,
        TICK,
        RESET,
        SET_AVAILABLE,
        SET_STALLED
    }

    /** One op. Immutable but for its arrays, which nobody writes after generation. */
    public static final class Op {

        public final Kind kind;
        /** The handle of {@code updateTexture} and {@code free}. */
        public final int tex;
        /** The region of {@code updateTexture} and {@code readPixels}; w and h are also createTexture's sides. */
        public final int x, y, w, h;
        /** createTexture's format, null for Lua nil. */
        public final String format;
        /** updateTexture's data, submit's and churn's segment. */
        public final byte[] data;
        /** run's record count, tick's milliseconds. */
        public final int count;
        /** The host switches' new value. */
        public final boolean flag;
        /** Churn's create sides, w and h per pair. */
        private final int[] pairs;
        /** What the generator meant, for failure messages: the records of a segment, or the malformation. */
        private final String note;

        private Op(Kind kind, int tex, int x, int y, int w, int h, String format, byte[] data, int count, boolean flag,
            int[] pairs, String note) {
            this.kind = kind;
            this.tex = tex;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.format = format;
            this.data = data;
            this.count = count;
            this.flag = flag;
            this.pairs = pairs;
            this.note = note;
        }

        static Op create(int w, int h, String format) {
            return new Op(Kind.CREATE, 0, 0, 0, w, h, format, null, 0, false, null, null);
        }

        static Op update(int tex, int x, int y, int w, int h, byte[] data) {
            return new Op(Kind.UPDATE, tex, x, y, w, h, null, data, 0, false, null, null);
        }

        static Op free(int tex) {
            return new Op(Kind.FREE, tex, 0, 0, 0, 0, null, null, 0, false, null, null);
        }

        static Op submit(byte[] segment, String note) {
            return new Op(Kind.SUBMIT, 0, 0, 0, 0, 0, null, segment, 0, false, null, note);
        }

        static Op readPixels(int x, int y, int w, int h) {
            return new Op(Kind.READ_PIXELS, 0, x, y, w, h, null, null, 0, false, null, null);
        }

        static Op churn(byte[] segment, int[] pairs) {
            return new Op(Kind.CHURN, 0, 0, 0, 0, 0, null, segment, 0, false, pairs, null);
        }

        static Op of(Kind kind) {
            return new Op(kind, 0, 0, 0, 0, 0, null, null, 0, false, null, null);
        }

        static Op count(Kind kind, int count) {
            return new Op(kind, 0, 0, 0, 0, 0, null, null, count, false, null, null);
        }

        static Op flag(Kind kind, boolean flag) {
            return new Op(kind, 0, 0, 0, 0, 0, null, null, 0, flag, null, null);
        }

        /** Churn's pairs. */
        public int pairCount() {
            return pairs == null ? 0 : pairs.length / 2;
        }

        public int pairWidth(int pair) {
            return pairs[2 * pair];
        }

        public int pairHeight(int pair) {
            return pairs[2 * pair + 1];
        }

        @Override
        public String toString() {
            switch (kind) {
                case CREATE:
                    return String.format(
                        Locale.ROOT,
                        "createTexture(%d, %d, %s)",
                        w,
                        h,
                        format == null ? "nil" : "\"" + format + "\"");
                case UPDATE:
                    return String.format(
                        Locale.ROOT,
                        "updateTexture(0x%08X, %d, %d, %d, %d, %d B)",
                        tex,
                        x,
                        y,
                        w,
                        h,
                        data.length);
                case FREE:
                    return String.format(Locale.ROOT, "free(0x%08X)", tex);
                case SUBMIT:
                    return "submit(" + data.length + " B: " + note + ")";
                case PRESENT:
                    return "present()";
                case READ_PIXELS:
                    return String.format(Locale.ROOT, "readPixels(%d, %d, %d, %d)", x, y, w, h);
                case CHURN:
                    return "churn(submit " + data.length + " B, then " + pairCount() + " create/free pairs)";
                case RUN:
                    return "host.run(" + count + ")";
                case READBACK:
                    return "host.readback()";
                case STEP:
                    return "host.step()";
                case TICK:
                    return "tick(+" + count + " ms)";
                case RESET:
                    return "reset()";
                case SET_AVAILABLE:
                    return "host.setAvailable(" + flag + ")";
                case SET_STALLED:
                    return "host.setStalled(" + flag + ")";
                default:
                    throw new AssertionError(kind);
            }
        }
    }

    /** Out of 103: the plan's 100 and churn's 3 (amendment A). */
    private static final int TOTAL = 103;
    private static final int CREATE_END = 14;
    private static final int UPDATE_END = 28;
    private static final int FREE_END = 36;
    private static final int SUBMIT_END = 56;
    private static final int PRESENT_END = 68;
    private static final int RUN_END = 73;
    private static final int READBACK_END = 78;
    private static final int STEP_END = 83;
    private static final int TICK_END = 91;
    private static final int READ_PIXELS_END = 96;
    private static final int RESET_END = 98;
    private static final int SWITCH_END = 100;
    /** nextCallback's draw: the callbacks, then readPixels in the same proportions. */
    private static final int CALLBACK_TOTAL = PRESENT_END + (READ_PIXELS_END - TICK_END);

    private static final String[] FORMAT_STRINGS = { "index8", "rgb565", "", "INDEX8", "Index8", "rgb565 ", "rgb" };

    private final SplittableRandom rnd;
    private final DeviceCaps caps;
    private final SurfaceDesc surface;
    private final int bpp;
    private final List<Integer> created = new ArrayList<>();
    private final Set<Integer> createdSet = new HashSet<>();
    private final Map<Integer, int[]> sizes = new HashMap<>();
    private int index = -1;
    private int availableBackAt = -1;
    private int stalledBackAt = -1;

    public OpGenerator(DeviceCaps caps, SplittableRandom rnd) {
        this.caps = caps;
        this.surface = caps.surface;
        this.bpp = surface.bytesPerPixel();
        this.rnd = rnd;
    }

    /** Tells the generator that createTexture(w, h) returned this handle, so later ops can name it. */
    public void created(int handle, int w, int h) {
        if (createdSet.add(handle)) {
            created.add(handle);
        }
        sizes.put(handle, new int[] { w, h });
    }

    /**
     * The next op of the model test's stream.
     *
     * @param live     the live handles now, ascending slot
     * @param vramUsed the VRAM in use now
     */
    public Op next(List<Integer> live, long vramUsed) {
        index++;
        if (availableBackAt >= 0 && index >= availableBackAt) {
            availableBackAt = -1;
            return Op.flag(Kind.SET_AVAILABLE, true);
        }
        if (stalledBackAt >= 0 && index >= stalledBackAt) {
            stalledBackAt = -1;
            return Op.flag(Kind.SET_STALLED, false);
        }
        int r = rnd.nextInt(TOTAL);
        if (r < PRESENT_END) {
            return callback(r, live, vramUsed);
        }
        if (r < RUN_END) {
            return Op.count(Kind.RUN, rnd.nextInt(6));
        }
        if (r < READBACK_END) {
            return Op.of(Kind.READBACK);
        }
        if (r < STEP_END) {
            return Op.of(Kind.STEP);
        }
        if (r < TICK_END) {
            return Op.count(Kind.TICK, rnd.nextInt(701));
        }
        if (r < READ_PIXELS_END) {
            return readPixels();
        }
        if (r < RESET_END) {
            return Op.of(Kind.RESET);
        }
        if (r < SWITCH_END) {
            boolean available = availableBackAt < 0 && (stalledBackAt >= 0 || rnd.nextBoolean());
            if (available) {
                availableBackAt = index + 1 + rnd.nextInt(5);
                return Op.flag(Kind.SET_AVAILABLE, false);
            }
            if (stalledBackAt < 0) {
                stalledBackAt = index + 1 + rnd.nextInt(5);
                return Op.flag(Kind.SET_STALLED, true);
            }
            return Op.of(Kind.PRESENT);
        }
        int pairs = 1 + rnd.nextInt(40);
        int[] sides = new int[2 * pairs];
        for (int i = 0; i < sides.length; i++) {
            sides[i] = 1 + rnd.nextInt(4);
        }
        return Op.churn(segment(), sides);
    }

    /**
     * The next callback only, for a worker thread (DeviceConcurrencyTest); live and vramUsed as the worker sees them.
     */
    public Op nextCallback(List<Integer> live, long vramUsed) {
        index++;
        int r = rnd.nextInt(CALLBACK_TOTAL);
        return r < PRESENT_END ? callback(r, live, vramUsed) : readPixels();
    }

    private Op callback(int r, List<Integer> live, long vramUsed) {
        if (r < CREATE_END) {
            return create(vramUsed);
        }
        if (r < UPDATE_END) {
            return update(live);
        }
        if (r < FREE_END) {
            return Op.free(handle(live));
        }
        if (r < SUBMIT_END) {
            return submit(live);
        }
        return Op.of(Kind.PRESENT);
    }

    // ---- createTexture (28 §6.2, :343)

    private Op create(long vramUsed) {
        int max = caps.maxTextureSize;
        int r = rnd.nextInt(100);
        if (r < 70) {
            return Op.create(1 + rnd.nextInt(8), 1 + rnd.nextInt(8), null);
        }
        if (r < 80) {
            int[] bad = { 0, -1, max + 1, Integer.MAX_VALUE, Integer.MIN_VALUE };
            int w = 1 + rnd.nextInt(8);
            int h = 1 + rnd.nextInt(8);
            switch (rnd.nextInt(3)) {
                case 0:
                    return Op.create(bad[rnd.nextInt(bad.length)], h, null);
                case 1:
                    return Op.create(w, bad[rnd.nextInt(bad.length)], null);
                default:
                    return Op.create(bad[rnd.nextInt(bad.length)], bad[rnd.nextInt(bad.length)], null);
            }
        }
        if (r < 90) {
            switch (rnd.nextInt(5)) {
                case 0:
                    return rnd.nextBoolean() ? Op.create(max, 1 + rnd.nextInt(4), null)
                        : Op.create(1 + rnd.nextInt(4), max, null);
                case 1:
                    return rnd.nextBoolean() ? Op.create(max + 1, 1, null) : Op.create(1, max + 1, null);
                case 2:
                    return Op.create(max, max, null);
                default: {
                    // What is left of the VRAM, exactly or one row more
                    long left = (caps.vramBytes - vramUsed) / bpp;
                    int w = (int) Math.max(1, Math.min(max, left));
                    int h = (int) Math.max(1, Math.min(max, left / w));
                    if (rnd.nextBoolean() && h < max) {
                        h++;
                    }
                    return Op.create(w, h, null);
                }
            }
        }
        return Op.create(1 + rnd.nextInt(8), 1 + rnd.nextInt(8), FORMAT_STRINGS[rnd.nextInt(FORMAT_STRINGS.length)]);
    }

    // ---- updateTexture (28 §6.3, :344)

    private Op update(List<Integer> live) {
        int tex = handle(live);
        int[] size = sizes.get(tex);
        int tw = size == null ? 1 + rnd.nextInt(8) : size[0];
        int th = size == null ? 1 + rnd.nextInt(8) : size[1];
        int x;
        int y;
        int w;
        int h;
        if (rnd.nextInt(10) == 0) {
            x = 0;
            y = 0;
            w = tw;
            h = th;
        } else {
            x = rnd.nextInt(tw);
            y = rnd.nextInt(th);
            w = 1 + rnd.nextInt(Math.min(8, tw - x));
            h = 1 + rnd.nextInt(Math.min(8, th - y));
        }
        if (rnd.nextInt(100) >= 70) {
            boolean alongX = rnd.nextBoolean();
            int[] axis = alongX ? new int[] { x, w } : new int[] { y, h };
            edge(axis, alongX ? tw : th);
            if (alongX) {
                x = axis[0];
                w = axis[1];
            } else {
                y = axis[0];
                h = axis[1];
            }
        }
        boolean valid = 0 <= x && x <= tw && 1 <= w && w <= tw - x && 0 <= y && y <= th && 1 <= h && h <= th - y;
        int n;
        if (valid) {
            n = w * h * bpp;
            if (rnd.nextInt(10) == 0) {
                n += rnd.nextBoolean() ? 1 : -1;
            }
        } else if (w >= 1 && w <= 64 && h >= 1 && h <= 64) {
            n = w * h * bpp;
        } else {
            n = rnd.nextInt(32);
        }
        return Op.update(tex, x, y, w, h, randomBytes(n));
    }

    /** One axis {pos, len} of a region of size side moved to an edge, mostly outside (28:344; 28:920's rows). */
    private void edge(int[] axis, int side) {
        switch (rnd.nextInt(10)) {
            case 0:
                axis[0] = side;
                axis[1] = 1;
                break;
            case 1:
                axis[0] = 0;
                axis[1] = side + 1;
                break;
            case 2:
                axis[0] = side - 1;
                axis[1] = 1;
                break;
            case 3:
                axis[0] = 0;
                axis[1] = side;
                break;
            case 4:
                axis[0] = -1;
                break;
            case 5:
                axis[1] = 0;
                break;
            case 6:
                axis[0] = Integer.MAX_VALUE;
                break;
            case 7:
                axis[0] = Integer.MIN_VALUE;
                break;
            case 8:
                axis[1] = Integer.MAX_VALUE;
                break;
            default:
                axis[1] = Integer.MIN_VALUE;
                break;
        }
    }

    // ---- handles (28 §6.1, :336)

    private int handle(List<Integer> live) {
        int r = rnd.nextInt(100);
        if (r < 60 && !live.isEmpty()) {
            return live.get(rnd.nextInt(live.size()));
        }
        if (r < 75) {
            if (rnd.nextBoolean() && !created.isEmpty()) {
                for (int i = 0; i < 4; i++) {
                    int h = created.get(rnd.nextInt(created.size()));
                    if (!live.contains(h)) {
                        return h;
                    }
                }
            }
            if (!live.isEmpty()) {
                int h = live.get(rnd.nextInt(live.size()));
                int generation = (Handles.generation(h) + 1 + rnd.nextInt(3)) & Handles.MAX_GENERATION;
                return Handles.pack(HandleTypes.TEXTURE, Handles.slot(h), generation);
            }
        }
        if (r < 85) {
            for (int i = 0; i < 8; i++) {
                int h = rnd.nextBoolean() ? Handles.pack(HandleTypes.TEXTURE, Handles.MAX_SLOT - rnd.nextInt(64), 0)
                    : Handles.pack(HandleTypes.TEXTURE, rnd.nextInt(Handles.MAX_SLOT + 1), 1000 + rnd.nextInt(60000));
                if (!createdSet.contains(h)) {
                    return h;
                }
            }
        }
        int slot = rnd.nextInt(Handles.MAX_SLOT + 1);
        int generation = rnd.nextInt(Handles.MAX_GENERATION + 1);
        switch (rnd.nextInt(9)) {
            case 0:
                return 0;
            case 1:
                return 0x7FFFFFFF;
            case 2:
                return 0x80000000;
            case 3:
                return -1;
            case 4:
                return 1 + rnd.nextInt(0x07FFFFFF);
            case 5:
                return Handles.pack(HandleTypes.BUFFER, slot, generation);
            case 6:
                return Handles.pack(HandleTypes.RENDER_TARGET + rnd.nextInt(5), slot, generation);
            case 7:
                return Handles.pack(9, slot, generation);
            default:
                return Handles.pack(HandleTypes.FIRST_UNASSIGNED + rnd.nextInt(8), slot, generation);
        }
    }

    // ---- submit (28 §3.5-§3.6, §5)

    private Op submit(List<Integer> live) {
        if (rnd.nextInt(10) == 0) {
            return badSubmit(live);
        }
        int n = 1 + rnd.nextInt(6);
        byte[][] records = new byte[n][];
        StringBuilder note = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                note.append(", ");
            }
            int r = rnd.nextInt(100);
            if (r < 12) {
                records[i] = clear(colour());
                note.append("CLEAR");
            } else if (r < 50) {
                records[i] = fillRecord();
                note.append("FILL_RECT");
            } else if (r < 80) {
                int tex = handle(live);
                records[i] = blitRecord(tex, rnd.nextBoolean() ? Opcodes.BLIT_KEY0 : 0);
                note.append(String.format(Locale.ROOT, "BLIT 0x%08X", tex));
            } else {
                records[i] = text(
                    rnd.nextInt(surface.width + 12) - 6,
                    rnd.nextInt(surface.height + 14) - 7,
                    colour(),
                    colour(),
                    rnd.nextBoolean() ? Opcodes.TEXT_TRANSPARENT_BG : 0,
                    0,
                    randomText());
                note.append("TEXT");
            }
        }
        return Op.submit(segment(records), note.toString());
    }

    private Op badSubmit(List<Integer> live) {
        byte[] valid = segment(fillRecord());
        switch (rnd.nextInt(14)) {
            case 0:
                return Op.submit(with(valid, rnd.nextInt(4), 0x4E), "bad magic");
            case 1:
                return Op.submit(with(valid, 4, 2), "version 2");
            case 2:
                return Op.submit(concat(valid, bytes(rnd.nextInt(256))), "a byte after END");
            case 3:
                return Op.submit(head(valid, valid.length - 1), "no END");
            case 4:
                return Op.submit(segment(fillRecord(), bytes(0x77)), "opcode 0x77");
            case 5:
                return Op.submit(
                    segment(clear(surface.format == Formats.INDEX8 ? 0x100 : 0x00FF0000)),
                    "CLEAR of a bad colour");
            case 6:
                return Op.submit(segment(text(0, 0, colour(), colour(), 0, 1 + rnd.nextInt(255), "A")), "font 1-255");
            case 7:
                return Op.submit(segment(text(0, 0, colour(), colour(), 0, 0, bytes(0xC3, 0x28))), "bad UTF-8");
            case 8:
                return Op.submit(segment(text(0, 0, colour(), colour(), 0, 0, bytes(0x41, 0x07))), "U+0007");
            case 9:
                return Op.submit(segment(blitRecord(handle(live), 0x02)), "BLIT flags 0x02");
            case 10:
                return Op.submit(segment(head(fillRecord(), 5 + rnd.nextInt(8))), "a cut FILL_RECT");
            case 11:
                return Op.submit(new byte[caps.limits.submitBytes + 1], "one byte past the submit cap");
            case 12:
                return Op.submit(new byte[rnd.nextInt(8)], "a short header");
            default:
                return Op.submit(head(valid, 8), "the header alone");
        }
    }

    private byte[] fillRecord() {
        int w = surface.width;
        int h = surface.height;
        if (rnd.nextInt(20) == 0) {
            return fill(0, 0, w, h, colour());
        }
        return fill(
            rnd.nextInt(w + 8) - 4,
            rnd.nextInt(h + 8) - 4,
            rnd.nextInt(Math.min(w, 40) + 4) - 2,
            rnd.nextInt(Math.min(h, 40) + 4) - 2,
            colour());
    }

    private byte[] blitRecord(int tex, int flags) {
        int[] size = sizes.get(tex);
        int tw = size == null ? 8 : size[0];
        int th = size == null ? 8 : size[1];
        return blit(
            tex,
            rnd.nextInt(tw + 4) - 2,
            rnd.nextInt(th + 4) - 2,
            rnd.nextInt(Math.min(tw, 24) + 3) - 1,
            rnd.nextInt(Math.min(th, 24) + 3) - 1,
            rnd.nextInt(surface.width + 8) - 4,
            rnd.nextInt(surface.height + 8) - 4,
            flags);
    }

    /** 0-8 codepoints: printable ASCII, and 2-, 3- and 4-byte UTF-8 (the synthetic table draws a box for those). */
    private String randomText() {
        int n = rnd.nextInt(9);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int r = rnd.nextInt(100);
            if (r < 80) {
                b.append((char) (0x20 + rnd.nextInt(0x7F - 0x20)));
            } else if (r < 88) {
                b.append('é');
            } else if (r < 94) {
                b.append('€');
            } else {
                b.appendCodePoint(0x1F600);
            }
        }
        return b.toString();
    }

    /** A valid colour of the surface's format (28 §5.3): an index, or opaque 0xFFRRGGBB. */
    private int colour() {
        return surface.format == Formats.INDEX8 ? rnd.nextInt(256) : 0xFF000000 | rnd.nextInt(0x1000000);
    }

    // ---- readPixels (28:99, :342)

    private Op readPixels() {
        int sw = surface.width;
        int sh = surface.height;
        int r = rnd.nextInt(100);
        if (r < 60) {
            int x = rnd.nextInt(sw);
            int y = rnd.nextInt(sh);
            return Op.readPixels(x, y, 1 + rnd.nextInt(Math.min(16, sw - x)), 1 + rnd.nextInt(Math.min(16, sh - y)));
        }
        if (r < 70) {
            return Op.readPixels(0, 0, sw, sh);
        }
        if (r < 75) {
            int rows = Math.max(1, Math.min(sh, caps.maxReadbackBytes / (sw * bpp)));
            return Op.readPixels(0, 0, sw, rows + (rnd.nextBoolean() ? 0 : 1));
        }
        int[] axis = { rnd.nextInt(sw), 1 };
        boolean alongX = rnd.nextBoolean();
        edge(axis, alongX ? sw : sh);
        return alongX ? Op.readPixels(axis[0], 0, axis[1], 1) : Op.readPixels(0, axis[0], 1, axis[1]);
    }

    private byte[] randomBytes(int n) {
        byte[] out = new byte[n];
        int i = 0;
        while (i < n) {
            long v = rnd.nextLong();
            for (int k = 0; k < 8 && i < n; k++, i++) {
                out[i] = (byte) v;
                v >>>= 8;
            }
        }
        return out;
    }
}
