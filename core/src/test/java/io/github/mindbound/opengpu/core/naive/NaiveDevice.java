package io.github.mindbound.opengpu.core.naive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.zip.CRC32;

import io.github.mindbound.opengpu.core.Handles;
import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.cmd.Formats;
import io.github.mindbound.opengpu.core.cmd.FrameLimits;
import io.github.mindbound.opengpu.core.cmd.FrameTotals;
import io.github.mindbound.opengpu.core.cmd.Header;
import io.github.mindbound.opengpu.core.cmd.Opcodes;
import io.github.mindbound.opengpu.core.cmd.Validation;
import io.github.mindbound.opengpu.core.cmd.Validator;
import io.github.mindbound.opengpu.core.fixtures.MapLiveView;

/**
 * A reference model of 28's server-side device (§2.1-§2.4, §3.5-§3.6, §6.1-§6.4, §8.1-§8.2, "S6 implemented"), written
 * from the note and the S7 U2 brief only, for a differential test against {@code Device}. It is deliberately plain:
 * lists, a map-backed live view, linear scans, a descriptor string per record. Not thread-safe; nothing here is fast.
 *
 * <p>
 * Results come in the Lua shape as {@code Object[]}: {@code {TRUE}}, {@code {Integer}}, {@code {Long}},
 * {@code {byte[], Long}} or {@code {null, String}}.
 */
public final class NaiveDevice {

    /** OC's {@code LimitReached}: the caller repeats the same call, as OC's synchronized retry does. */
    public static final class LimitReached extends RuntimeException {
    }

    // ---- record kinds (28 §2.3)
    private static final int FRAME = 1, TEX_CREATE = 2, TEX_UPDATE = 3, TEX_FREE = 4;

    /** 28 §2.2: 64 B per resource record in the server tail. */
    private static final long RECORD_OVERHEAD = 64;
    /** 28 §8.2: "/256 KB" and "/1 MB". */
    private static final double KB256 = 262144.0, MB1 = 1048576.0;
    /** 28 §8.2: submit's charge counts min(#s, 65 536). */
    private static final int SUBMIT_CHARGE_CAP = 65536;
    /** 28 §2.1: "dropped" 1 s after present. */
    private static final long DROP_NANOS = 1000000000L;

    /** 28 §6.1's kinds, by handle type 1-7 (written here, not taken from HandleTypes). */
    private static final String[] KIND = { null, "texture", "buffer", "render target", "program", "pipeline", "kernel",
        "compute list" };

    /** One record, kept with its descriptor (records are immutable once appended). */
    private static final class Rec {

        final int kind;
        final long epoch;
        final String descriptor;
        // FRAME
        long frameId;
        long presentNanos;
        boolean droppedReported;
        // TEX_*
        int handle, x, y, w, h;
        byte[] data;
        /** What the record counts in the queued resource bytes while it is in the server tail (0 for FRAME). */
        long queued;

        Rec(int kind, long epoch, String descriptor) {
            this.kind = kind;
            this.epoch = epoch;
            this.descriptor = descriptor;
        }
    }

    /** A texture master: its size and texels in 28 §5.7's layout (rows top first, w·bpp bytes, no padding). */
    private static final class Master {

        final int w, h;
        final byte[] texels;

        Master(int w, int h, int bpp) {
            this.w = w;
            this.h = h;
            this.texels = new byte[w * h * bpp];
        }
    }

    // ---- configuration
    private final SurfaceDesc surface;
    private final FrameLimits limits;
    private final int vramBytes, maxTextures, maxTextureSize, maxReadbackBytes, maxUnconfirmedFrames;
    private final LongSupplier clock;
    private final int bpp;
    /** OC's 0-based tier t (28 §8.2). */
    private final int t;
    /** The two-phase credit k = (t + 1)·0.5 (28 §8.2). */
    private final double k;

    private boolean hostAvailable = true;
    private boolean hostStalled = false;

    private long epoch = 1;

    // ---- the live view (28 §2.2): handle table, VRAM in use
    private final MapLiveView live;
    private long vramUsed;

    // ---- the open frame
    private boolean frameOpen;
    private final List<byte[]> openSegments = new ArrayList<>();
    private FrameTotals openTotals = FrameTotals.EMPTY;
    private final List<Integer> deferredFrees = new ArrayList<>();
    private long deferredBytes;

    // ---- the server tail
    private final LinkedList<Rec> tail = new LinkedList<>();
    private long queuedBytes;

    // ---- the confirmed state
    private long confirmed;
    private byte[] confirmedPixels;
    private final TreeMap<Integer, Master> masters = new TreeMap<>();

    private long lastFrameId;
    /** The device's two-phase ("exhausted") flag, shared by createTexture and updateTexture (brief rule 3). */
    private boolean exhausted;

    // ---- observations
    private final List<String> offered = new ArrayList<>();
    private final List<String> listenerEvents = new ArrayList<>();
    private final List<String> budgetEvents = new ArrayList<>();

    public NaiveDevice(SurfaceDesc surface, FrameLimits limits, int vramBytes, int maxTextures, int maxTextureSize,
        int maxReadbackBytes, int maxUnconfirmedFrames, LongSupplier nanoClock) {
        if (surface == null || limits == null || nanoClock == null) {
            throw new NullPointerException("surface, limits and clock");
        }
        if (surface.tier < 1 || surface.tier > 3) {
            throw new IllegalArgumentException("tier " + surface.tier + " is outside 1..3");
        }
        if (maxTextureSize < 1 || maxTextureSize > MapLiveView.MAX_TEXTURE_SIDE) {
            throw new IllegalArgumentException("maxTextureSize " + maxTextureSize);
        }
        if (vramBytes < 0 || maxTextures < 0 || maxReadbackBytes < 0 || maxUnconfirmedFrames < 0) {
            throw new IllegalArgumentException("negative cap");
        }
        this.surface = surface;
        this.limits = limits;
        this.vramBytes = vramBytes;
        this.maxTextures = maxTextures;
        this.maxTextureSize = maxTextureSize;
        this.maxReadbackBytes = maxReadbackBytes;
        this.maxUnconfirmedFrames = maxUnconfirmedFrames;
        this.clock = nanoClock;
        this.bpp = surface.format == Formats.RGB565 ? 2 : 1;
        this.t = surface.tier - 1;
        this.k = (t + 1) * 0.5;
        this.live = new MapLiveView(surface, limits);
        // Brief rule 1: confirmed frame 0 is the cleared image
        this.confirmedPixels = new byte[surface.width * surface.height * bpp];
    }

    public void setHostAvailable(boolean available) {
        hostAvailable = available;
    }

    public void setHostStalled(boolean stalled) {
        hostStalled = stalled;
    }

    // =================================================================================================== callbacks

    /** 28 §2.1, §3.5: charged before it validates; an accepted segment joins the open frame, nothing is offered. */
    public Object[] submit(byte[] s) {
        if (s == null) {
            throw new NullPointerException("stream");
        }
        consume(1.0 / 32 * Math.pow(2, -t) + Math.min(s.length, SUBMIT_CHARGE_CAP) / MB1);
        Validation v = Validator.validate(s, live, openTotals);
        if (!v.isAccepted()) {
            return fail(
                v.error()
                    .message());
        }
        openSegments.add(s);
        openTotals = openTotals.plus(v.totals());
        frameOpen = true;
        return ok();
    }

    /** 28 §2.1 and brief rule 4: charged first; then "no renderer", "host render stalled", "busy", or a frame id. */
    public Object[] present() {
        consume(0.125 * Math.pow(2, -t));
        if (!hostAvailable) {
            // Discard the open frame; its deferred frees are appended (after any frames still in the tail)
            List<Integer> frees = new ArrayList<>(deferredFrees);
            clearOpenFrame();
            for (Integer handle : frees) {
                append(freeRecord(handle));
            }
            return fail("no renderer");
        }
        if (hostStalled) {
            return fail("host render stalled");
        }
        if (unconfirmedFrames() >= maxUnconfirmedFrames) {
            return fail("busy");
        }
        long id = lastFrameId + 1;
        lastFrameId = id;
        Rec frame = frameRecord(id, new ArrayList<>(openSegments), openTotals);
        frame.presentNanos = clock.getAsLong();
        List<Integer> frees = new ArrayList<>(deferredFrees);
        clearOpenFrame();
        append(frame);
        for (Integer handle : frees) {
            append(freeRecord(handle));
        }
        return new Object[] { Long.valueOf(id) };
    }

    /** 28 §3.6: the region check, then the readback cap, then the charge; K's pixels and K. */
    public Object[] readPixels(int x, int y, int w, int h) {
        int sw = surface.width, sh = surface.height;
        if (!(0 <= x && x <= sw && 1 <= w && w <= sw - x && 0 <= y && y <= sh && 1 <= h && h <= sh - y)) {
            return fail("invalid region");
        }
        long bytes = (long) w * h * bpp;
        if (bytes > maxReadbackBytes) {
            return fail(fmt("region exceeds %d bytes per call", maxReadbackBytes));
        }
        consume(bytes / KB256);
        byte[] out = new byte[(int) bytes];
        int row = w * bpp;
        for (int r = 0; r < h; r++) {
            System.arraycopy(confirmedPixels, ((y + r) * sw + x) * bpp, out, r * row, row);
        }
        return new Object[] { out, Long.valueOf(confirmed) };
    }

    /** 28 §6.2, §3.6 and brief rules 5-6; two-phase charge after every check. */
    public Object[] createTexture(int w, int h, String format) {
        String error = createError(w, h, format);
        if (error != null) {
            exhausted = false;
            return fail(error);
        }
        long bytes = (long) w * h * bpp;
        twoPhase(bytes / KB256);
        int handle = live.create(w, h);
        vramUsed += bytes;
        Rec r = new Rec(
            TEX_CREATE,
            epoch,
            fmt("TEX_CREATE e=%d tex=0x%08X size=%dx%d format=%d", epoch, handle, w, h, surface.format));
        r.handle = handle;
        r.w = w;
        r.h = h;
        r.queued = RECORD_OVERHEAD + bytes;
        append(r);
        return new Object[] { Integer.valueOf(handle) };
    }

    private String createError(int w, int h, String format) {
        if (!(1 <= w && w <= maxTextureSize && 1 <= h && h <= maxTextureSize)) {
            return "invalid texture size";
        }
        if (format != null && !format.equals(formatName(surface.format))) {
            return "unsupported format";
        }
        int liveCount = live.liveCount();
        if (liveCount >= maxTextures) {
            return "too many textures";
        }
        long bytes = (long) w * h * bpp;
        if (bytes > vramBytes - vramUsed) {
            return "not enough video memory";
        }
        // Brief rule 6: the open frame's deferred frees still count
        if ((long) liveCount + deferredFrees.size() >= maxTextures || vramUsed + deferredBytes + bytes > vramBytes) {
            return "busy";
        }
        // Brief rule 5: only a record that joins a non-empty tail is queued
        if (!tail.isEmpty() && queuedBytes + bytes + RECORD_OVERHEAD > vramBytes) {
            return "busy";
        }
        return null;
    }

    /** 28 §6.3, §3.6 and brief rule 5; two-phase charge after every check. */
    public Object[] updateTexture(int tex, int x, int y, int w, int h, byte[] data) {
        if (data == null) {
            throw new NullPointerException("data");
        }
        String error = updateError(tex, x, y, w, h, data);
        if (error != null) {
            exhausted = false;
            return fail(error);
        }
        twoPhase(data.length / KB256);
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        Rec r = new Rec(
            TEX_UPDATE,
            epoch,
            fmt(
                "TEX_UPDATE e=%d tex=0x%08X at=%d,%d size=%dx%d len=%d crc=%08X",
                epoch,
                tex,
                x,
                y,
                w,
                h,
                data.length,
                crc.getValue()));
        r.handle = tex;
        r.x = x;
        r.y = y;
        r.w = w;
        r.h = h;
        r.data = data;
        r.queued = RECORD_OVERHEAD + data.length;
        append(r);
        return ok();
    }

    private String updateError(int tex, int x, int y, int w, int h, byte[] data) {
        String handleError = handleError(tex);
        if (handleError != null) {
            return handleError;
        }
        int tw = live.textureWidth(tex), th = live.textureHeight(tex);
        if (!(0 <= x && x <= tw && 1 <= w && w <= tw - x && 0 <= y && y <= th && 1 <= h && h <= th - y)) {
            return "invalid texture region";
        }
        long need = (long) w * h * bpp;
        if (data.length != need) {
            return fmt("data must be %d bytes", need);
        }
        if (!tail.isEmpty() && queuedBytes + data.length + RECORD_OVERHEAD > vramBytes) {
            return "busy";
        }
        return null;
    }

    /** 28 §6.4, O4 and brief rules 6 and 10: never charged, never busy. */
    public Object[] free(int tex) {
        String error = handleError(tex);
        if (error != null) {
            return fail(error);
        }
        long bytes = (long) live.textureWidth(tex) * live.textureHeight(tex) * bpp;
        live.free(tex);
        vramUsed -= bytes;
        if (frameOpen) {
            deferredFrees.add(Integer.valueOf(tex));
            deferredBytes += bytes;
        } else {
            append(freeRecord(tex));
        }
        return ok();
    }

    /** 28 §8.1's keys in the table's order (brief rule 13). */
    public Map<String, Object> getCaps() {
        Map<String, Object> caps = new LinkedHashMap<>();
        caps.put("tier", Integer.valueOf(surface.tier));
        caps.put("width", Integer.valueOf(surface.width));
        caps.put("height", Integer.valueOf(surface.height));
        caps.put("format", formatName(surface.format));
        caps.put("commandFormat", Integer.valueOf(Header.VERSION));
        caps.put("maxSubmitBytes", Integer.valueOf(limits.submitBytes));
        caps.put("maxFrameBytes", Integer.valueOf(limits.frameBytes));
        caps.put("maxFrameRecords", Integer.valueOf(limits.frameRecords));
        long fill = limits.frameFill;
        caps.put("maxFrameFill", fill <= Integer.MAX_VALUE ? (Object) Integer.valueOf((int) fill) : Long.valueOf(fill));
        caps.put("maxTextBytes", Integer.valueOf(Opcodes.TEXT_MAX_BYTES));
        caps.put("maxUnconfirmedFrames", Integer.valueOf(maxUnconfirmedFrames));
        caps.put("maxTextureSize", Integer.valueOf(maxTextureSize));
        caps.put("maxTextures", Integer.valueOf(maxTextures));
        caps.put("vramBytes", Integer.valueOf(vramBytes));
        caps.put("maxReadbackBytes", Integer.valueOf(maxReadbackBytes));
        return caps;
    }

    /** 28:150 and brief rule 11: computer.stopped or started. */
    public void reset() {
        epoch++;
        clearOpenFrame();
        tail.clear();
        queuedBytes = 0;
        masters.clear();
        vramUsed = 0;
        live.clear();
        exhausted = false;
        byte[] pixels = confirmedPixels.clone();
        offered.add(fmt("RESET e=%d len=%d crc=%08X", epoch, pixels.length, crc(pixels)));
    }

    /** Brief rule 7 and O6: the host read back that frame. */
    public void onReadback(long readEpoch, long frameId, byte[] pixels) {
        if (readEpoch != epoch || frameId <= confirmed) {
            return;
        }
        Rec target = null;
        for (Rec r : tail) {
            if (r.kind == FRAME && r.frameId == frameId) {
                target = r;
                break;
            }
        }
        if (target == null) {
            return;
        }
        if (pixels == null || pixels.length != confirmedPixels.length) {
            throw new IllegalArgumentException("readback pixels must be " + confirmedPixels.length + " bytes");
        }
        while (true) {
            Rec r = tail.removeFirst();
            if (r == target) {
                break;
            }
            if (r.kind != FRAME) {
                queuedBytes -= r.queued;
                fold(r);
            }
        }
        confirmed = frameId;
        confirmedPixels = pixels.clone();
        while (!tail.isEmpty() && tail.getFirst().kind != FRAME) {
            Rec r = tail.removeFirst();
            queuedBytes -= r.queued;
            fold(r);
        }
        listenerEvents.add("done " + frameId);
    }

    /** Brief rule 8: "dropped" once per frame still in the tail 1 s after its present. */
    public void tick() {
        long now = clock.getAsLong();
        for (Rec r : tail) {
            if (r.kind == FRAME && !r.droppedReported && now - r.presentNanos >= DROP_NANOS) {
                r.droppedReported = true;
                listenerEvents.add("dropped " + r.frameId);
            }
        }
    }

    // ================================================================================================ observations

    public List<String> offered() {
        return Collections.unmodifiableList(new ArrayList<>(offered));
    }

    public List<String> tail() {
        List<String> out = new ArrayList<>();
        for (Rec r : tail) {
            out.add(r.descriptor);
        }
        return Collections.unmodifiableList(out);
    }

    public List<String> listenerEvents() {
        return Collections.unmodifiableList(new ArrayList<>(listenerEvents));
    }

    public List<String> budgetEvents() {
        return Collections.unmodifiableList(new ArrayList<>(budgetEvents));
    }

    public long epoch() {
        return epoch;
    }

    public long lastFrameId() {
        return lastFrameId;
    }

    public long confirmedFrameId() {
        return confirmed;
    }

    public long vramUsed() {
        return vramUsed;
    }

    public long queuedBytes() {
        return queuedBytes;
    }

    public int unconfirmedFrames() {
        int n = 0;
        for (Rec r : tail) {
            if (r.kind == FRAME) {
                n++;
            }
        }
        return n;
    }

    public byte[] confirmedPixels() {
        return confirmedPixels.clone();
    }

    public SortedMap<Integer, byte[]> masters() {
        SortedMap<Integer, byte[]> out = new TreeMap<>();
        for (Map.Entry<Integer, Master> e : masters.entrySet()) {
            out.put(e.getKey(), e.getValue().texels.clone());
        }
        return Collections.unmodifiableSortedMap(out);
    }

    public List<Integer> liveHandles() {
        List<Integer> out = new ArrayList<>(live.handles());
        Collections.sort(out, new Comparator<Integer>() {

            @Override
            public int compare(Integer a, Integer b) {
                return Integer.compare(Handles.slot(a), Handles.slot(b));
            }
        });
        return Collections.unmodifiableList(out);
    }

    // ===================================================================================================== helpers

    /** 28:336's handle messages (rows 15-18); row 19 cannot arise, every texture having the surface's format. */
    private String handleError(int tex) {
        if (tex <= 0 || Handles.type(tex) == 0) {
            return fmt("invalid handle 0x%08X", tex);
        }
        int type = Handles.type(tex);
        if (type >= 8) {
            return fmt("handle 0x%08X has an unknown type", tex);
        }
        if (type != 1) {
            return fmt("handle 0x%08X is a %s, expected a texture", tex, KIND[type]);
        }
        if (live.textureFormat(tex) == 0) {
            return fmt("handle 0x%08X is not live", tex);
        }
        return null;
    }

    /** Appends a record and offers it (O3, O4); a resource record reaching an empty tail folds at once (O6). */
    private void append(Rec r) {
        offered.add(r.descriptor);
        if (r.kind != FRAME && tail.isEmpty()) {
            fold(r);
            return;
        }
        tail.addLast(r);
        if (r.kind != FRAME) {
            queuedBytes += r.queued;
        }
    }

    /** O6: create zeroed, update row-wise, free drop; a FRAME changes no master. */
    private void fold(Rec r) {
        switch (r.kind) {
            case TEX_CREATE:
                if (masters.containsKey(r.handle)) {
                    throw new IllegalStateException(fmt("master 0x%08X created twice", r.handle));
                }
                masters.put(r.handle, new Master(r.w, r.h, bpp));
                break;
            case TEX_UPDATE: {
                Master m = masters.get(r.handle);
                if (m == null) {
                    throw new IllegalStateException(fmt("update of absent master 0x%08X", r.handle));
                }
                int row = r.w * bpp;
                for (int j = 0; j < r.h; j++) {
                    System.arraycopy(r.data, j * row, m.texels, ((r.y + j) * m.w + r.x) * bpp, row);
                }
                break;
            }
            case TEX_FREE:
                if (masters.remove(r.handle) == null) {
                    throw new IllegalStateException(fmt("free of absent master 0x%08X", r.handle));
                }
                break;
            default:
                break;
        }
    }

    private Rec frameRecord(long id, List<byte[]> segments, FrameTotals totals) {
        long bytes = 0;
        CRC32 crc = new CRC32();
        for (byte[] s : segments) {
            bytes += s.length;
            crc.update(s, 0, s.length);
        }
        Rec r = new Rec(
            FRAME,
            epoch,
            fmt(
                "FRAME e=%d id=%d segs=%d bytes=%d records=%d clear=%d fill=%d copy=%d " + "set=%d crc=%08X",
                epoch,
                id,
                segments.size(),
                bytes,
                totals.records,
                totals.clearPixels,
                totals.fillPixels,
                totals.copyPixels,
                totals.setPixels,
                crc.getValue()));
        r.frameId = id;
        return r;
    }

    private Rec freeRecord(int handle) {
        Rec r = new Rec(TEX_FREE, epoch, fmt("TEX_FREE e=%d tex=0x%08X", epoch, handle));
        r.handle = handle;
        r.queued = RECORD_OVERHEAD;
        return r;
    }

    private void clearOpenFrame() {
        frameOpen = false;
        openSegments.clear();
        openTotals = FrameTotals.EMPTY;
        deferredFrees.clear();
        deferredBytes = 0;
    }

    /** A plain charge (submit, present, readPixels). */
    private void consume(double c) {
        budgetEvents.add(fmt("consume %.9f", c));
    }

    /** 28 §8.2's two-phase pattern; returns when the effect may proceed. */
    private void twoPhase(double c) {
        if (c <= k) {
            consume(c);
            exhausted = false;
            return;
        }
        if (!exhausted) {
            exhausted = true;
            budgetEvents.add("limit");
            throw new LimitReached();
        }
        double over = c - k;
        if (over > k) {
            budgetEvents.add(fmt("pause %.9f", (over - k) / k / 20));
        }
        exhausted = false;
    }

    private static String formatName(int format) {
        return format == Formats.RGB565 ? "rgb565" : "index8";
    }

    private static long crc(byte[] b) {
        CRC32 crc = new CRC32();
        crc.update(b, 0, b.length);
        return crc.getValue();
    }

    private static Object[] ok() {
        return new Object[] { Boolean.TRUE };
    }

    private static Object[] fail(String message) {
        return new Object[] { null, message };
    }

    private static String fmt(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }
}
