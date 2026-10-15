package io.github.mindbound.opengpu.core.cmd;

import java.util.Collection;
import java.util.Locale;

import io.github.mindbound.opengpu.core.SurfaceDesc;
import io.github.mindbound.opengpu.core.fixtures.MapLiveView;
import io.github.mindbound.opengpu.core.naive.NaiveValidator;

/**
 * Runs the naive validator (28 §11.3, P2's second validator) on what the main validator sees: the surface, the caps,
 * the live textures and the open frame's totals become a naive {@code Config}, and {@link #disagreement} compares the
 * two outcomes as P2 does: acceptance, the code, the offset and {@code <OP>}; the string byte index for
 * {@code BAD_UTF8} and {@code CONTROL_CHAR}; and, when accepted, the records and the four class totals.
 */
public final class NaiveBridge {

    private NaiveBridge() {}

    /** The naive config of a map view: every handle it holds is a live texture. */
    public static NaiveValidator.Config config(MapLiveView view, FrameTotals open) {
        return config(view, view.handles(), open);
    }

    /** The naive config of any view, whose live textures are the given handles. */
    public static NaiveValidator.Config config(LiveView view, Collection<Integer> liveHandles, FrameTotals open) {
        SurfaceDesc surface = view.surface();
        FrameLimits limits = view.limits();
        NaiveValidator.Config c = new NaiveValidator.Config();
        c.format = surface.format;
        c.width = surface.width;
        c.height = surface.height;
        c.submitCap = limits.submitBytes;
        c.frameByteCap = limits.frameBytes;
        c.frameRecordCap = limits.frameRecords;
        c.frameFillCap = limits.frameFill;
        c.openBytes = open.bytes;
        c.openRecords = open.records;
        // Summed here from the four class fields, not by FrameTotals.totalPixels(), which is main code under test
        c.openFill = open.clearPixels + open.fillPixels + open.copyPixels + open.setPixels;
        for (int handle : liveHandles) {
            c.textures.put(
                Integer.toUnsignedLong(handle),
                new int[] { view.textureWidth(handle), view.textureHeight(handle), view.textureFormat(handle) });
        }
        return c;
    }

    public static NaiveValidator.Result naive(byte[] s, MapLiveView view, FrameTotals open) {
        return NaiveValidator.validate(s, config(view, open));
    }

    /** Null if the two outcomes agree as P2 requires, otherwise what differs. */
    public static String disagreement(Validation main, NaiveValidator.Result naive) {
        if (main.isAccepted() != naive.ok) {
            return "main " + main + ", naive " + naive;
        }
        if (main.isAccepted()) {
            FrameTotals t = main.totals();
            if (t.records != naive.records || t.clearPixels != naive.fill[0]
                || t.fillPixels != naive.fill[1]
                || t.copyPixels != naive.fill[2]
                || t.setPixels != naive.fill[3]) {
                return "both accept, but main " + t + ", naive " + naive;
            }
            return null;
        }
        StreamError e = main.error();
        if (!e.code.name()
            .equals(naive.code) || e.offset != naive.offset
            || !e.op()
                .equals(naive.op)) {
            return String.format(
                Locale.ROOT,
                "main %s at %d (%s), naive %s at %d (%s)",
                e.code,
                e.offset,
                e.op(),
                naive.code,
                naive.offset,
                naive.op);
        }
        if ((e.code == ErrorCode.BAD_UTF8 || e.code == ErrorCode.CONTROL_CHAR) && e.detail != naive.detail) {
            return "main " + e.code + " detail " + e.detail + ", naive detail " + naive.detail;
        }
        return null;
    }
}
