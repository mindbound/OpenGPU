package io.github.mindbound.opengpu.core.fixtures;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import io.github.mindbound.opengpu.core.cmd.OpVisitor;
import io.github.mindbound.opengpu.core.cmd.SegmentReader;

/**
 * Re-encodes the records {@link SegmentReader} visits with {@link TestEncoder}, for 28 §3.2's canonical form: every
 * accepted segment decodes to exactly one record list, and re-encoding that list gives the same bytes (28 §11.1;
 * the fuzzer's P3, 28 §11.3).
 */
public final class ReEncoder implements OpVisitor {

    private final ByteArrayOutputStream records = new ByteArrayOutputStream();

    public ReEncoder() {}

    /** An accepted segment decoded with the main reader and re-encoded: equal to it if the encoding is canonical. */
    public static byte[] reencode(byte[] segment) {
        ReEncoder encoder = new ReEncoder();
        SegmentReader.read(segment, encoder);
        return encoder.toSegment();
    }

    /** The v1 header, the records visited so far in order, and {@code END}. */
    public byte[] toSegment() {
        return TestEncoder.segment(records.toByteArray());
    }

    @Override
    public void clear(int colour) {
        add(TestEncoder.clear(colour));
    }

    @Override
    public void fillRect(int x, int y, int w, int h, int colour) {
        add(TestEncoder.fill(x, y, w, h, colour));
    }

    @Override
    public void blit(int texture, int sx, int sy, int w, int h, int dx, int dy, int flags) {
        add(TestEncoder.blit(texture, sx, sy, w, h, dx, dy, flags));
    }

    @Override
    public void text(int x, int y, int fg, int bg, int flags, int font, byte[] segment, int stringOffset,
        int stringLength) {
        byte[] string = Arrays.copyOfRange(segment, stringOffset, stringOffset + stringLength);
        add(TestEncoder.text(x, y, fg, bg, flags, font, string));
    }

    private void add(byte[] record) {
        records.write(record, 0, record.length);
    }
}
