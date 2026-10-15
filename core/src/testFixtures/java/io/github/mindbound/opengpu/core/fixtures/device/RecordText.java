package io.github.mindbound.opengpu.core.fixtures.device;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

import io.github.mindbound.opengpu.core.render.FrameRecord;
import io.github.mindbound.opengpu.core.render.Record;
import io.github.mindbound.opengpu.core.render.ResetRecord;
import io.github.mindbound.opengpu.core.render.TexCreateRecord;
import io.github.mindbound.opengpu.core.render.TexFreeRecord;
import io.github.mindbound.opengpu.core.render.TexUpdateRecord;

/**
 * Stable one-line descriptors of the records of 28 §2.3, shared by the device tests and the reference model, which
 * writes the same lines for what it would offer (S7 plan §2.3-§2.4). The record classes' {@code toString} is for
 * humans and may change; these lines are a test contract. {@link Locale#ROOT}; handles as {@code 0x%08X}; format codes
 * as numbers (28 §6.2); every CRC is {@code java.util.zip.CRC32} as 8 upper-case hex digits:
 * <ul>
 * <li>{@code FRAME e=%d id=%d segs=%d bytes=%d records=%d clear=%d fill=%d copy=%d set=%d crc=%08X}: bytes is the
 * segments' total length, records and the four pixel classes are the frame's totals (28:163), and the CRC runs over
 * the segments concatenated in submit order;</li>
 * <li>{@code TEX_CREATE e=%d tex=0x%08X size=%dx%d format=%d};</li>
 * <li>{@code TEX_UPDATE e=%d tex=0x%08X at=%d,%d size=%dx%d len=%d crc=%08X}, the CRC of the payload;</li>
 * <li>{@code TEX_FREE e=%d tex=0x%08X};</li>
 * <li>{@code RESET e=%d len=%d crc=%08X}, the CRC of the pixels.</li>
 * </ul>
 */
public final class RecordText {

    private RecordText() {}

    /**
     * The record's descriptor.
     *
     * @throws NullPointerException     for a null record
     * @throws IllegalArgumentException for a kind M0 does not define
     */
    public static String of(Record r) {
        switch (r.kind()) {
            case Record.FRAME: {
                FrameRecord f = (FrameRecord) r;
                byte[][] segments = new byte[f.segmentCount()][];
                long bytes = 0;
                for (int i = 0; i < segments.length; i++) {
                    segments[i] = f.segment(i);
                    bytes += segments[i].length;
                }
                return String.format(
                    Locale.ROOT,
                    "FRAME e=%d id=%d segs=%d bytes=%d records=%d clear=%d fill=%d copy=%d set=%d crc=%08X",
                    f.epoch,
                    f.frameId,
                    segments.length,
                    bytes,
                    f.totals.records,
                    f.totals.clearPixels,
                    f.totals.fillPixels,
                    f.totals.copyPixels,
                    f.totals.setPixels,
                    crc32(segments));
            }
            case Record.TEX_CREATE: {
                TexCreateRecord c = (TexCreateRecord) r;
                return String.format(
                    Locale.ROOT,
                    "TEX_CREATE e=%d tex=0x%08X size=%dx%d format=%d",
                    c.epoch,
                    c.handle,
                    c.width,
                    c.height,
                    c.format);
            }
            case Record.TEX_UPDATE: {
                TexUpdateRecord u = (TexUpdateRecord) r;
                byte[] payload = u.payload();
                return String.format(
                    Locale.ROOT,
                    "TEX_UPDATE e=%d tex=0x%08X at=%d,%d size=%dx%d len=%d crc=%08X",
                    u.epoch,
                    u.handle,
                    u.x,
                    u.y,
                    u.width,
                    u.height,
                    payload.length,
                    crc32(payload));
            }
            case Record.TEX_FREE: {
                TexFreeRecord t = (TexFreeRecord) r;
                return String.format(Locale.ROOT, "TEX_FREE e=%d tex=0x%08X", t.epoch, t.handle);
            }
            case Record.RESET: {
                ResetRecord reset = (ResetRecord) r;
                byte[] pixels = reset.pixels();
                return String
                    .format(Locale.ROOT, "RESET e=%d len=%d crc=%08X", reset.epoch, pixels.length, crc32(pixels));
            }
            default:
                throw new IllegalArgumentException("kind " + r.kind() + " is not an M0 record (28 §2.3)");
        }
    }

    /** Each record's descriptor, in order, in a new list. */
    public static List<String> of(List<? extends Record> rs) {
        List<String> out = new ArrayList<>(rs.size());
        for (Record r : rs) {
            out.add(of(r));
        }
        return out;
    }

    /** The CRC32 of the parts concatenated in order, as an unsigned 32-bit value: what the descriptors print. */
    public static long crc32(byte[]... parts) {
        CRC32 crc = new CRC32();
        for (byte[] part : parts) {
            crc.update(part, 0, part.length);
        }
        return crc.getValue();
    }
}
