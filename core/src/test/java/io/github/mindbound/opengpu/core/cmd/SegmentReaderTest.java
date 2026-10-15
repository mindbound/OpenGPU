package io.github.mindbound.opengpu.core.cmd;

import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.bytes;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.clear;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.concat;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.header;
import static io.github.mindbound.opengpu.core.fixtures.TestEncoder.segment;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The reader's own refusals (28 §2.4: it trusts its input and asserts its invariants in dev builds, which every Gradle
 * Test task runs with {@code -ea}): a segment whose header is not v1, or whose {@code END} is not its last byte, fails
 * its assertions before any record is visited, and an unassigned opcode throws {@link IllegalStateException} with or
 * without them. The assertion cases are skipped if assertions are off.
 */
class SegmentReaderTest {

    @Test
    void aSegmentThatIsNotV1FailsItsAssertionsBeforeAnyRecord() {
        Assumptions.assumeTrue(SegmentReader.class.desiredAssertionStatus(), "assertions are off");
        // Each but the first holds a CLEAR, which a reader that did not check the header first would visit
        byte[] records = concat(clear(1), bytes(0xFF));
        assertAssertionError(header(), "header only");
        assertAssertionError(concat(bytes(0x4F, 0x47, 0x50, 0x56, 0x01, 0x00, 0x00, 0x00), records), "magic");
        assertAssertionError(concat(header(2, 0, 0), records), "version 2");
        assertAssertionError(concat(header(1, 1, 0), records), "flags");
        assertAssertionError(concat(header(1, 0, 0x0001), records), "reserved byte 6");
        assertAssertionError(concat(header(1, 0, 0x0100), records), "reserved byte 7");
    }

    @Test
    void endMustBeTheLastByte() {
        Assumptions.assumeTrue(SegmentReader.class.desiredAssertionStatus(), "assertions are off");
        assertAssertionError(concat(header(), bytes(0xFF, 0x00)), "END followed by a byte");
        // A well-formed segment is read to its END
        Recorder recorder = new Recorder();
        SegmentReader.read(segment(clear(1)), recorder);
        Assertions.assertEquals(1, recorder.calls.size(), recorder.calls::toString);
    }

    @Test
    void anUnassignedOpcodeThrowsWithOrWithoutAssertions() {
        Recorder recorder = new Recorder();
        IllegalStateException e = Assertions.assertThrows(
            IllegalStateException.class,
            () -> SegmentReader.read(concat(header(), bytes(0x02)), recorder));
        Assertions.assertEquals("opcode 0x02 at 8 in an unvalidated segment", e.getMessage());
        Assertions.assertTrue(recorder.calls.isEmpty(), recorder.calls::toString);
    }

    private static void assertAssertionError(byte[] s, String what) {
        Recorder recorder = new Recorder();
        Assertions.assertThrows(AssertionError.class, () -> SegmentReader.read(s, recorder), what);
        Assertions.assertTrue(recorder.calls.isEmpty(), () -> what + ": visited " + recorder.calls);
    }

    /** A visitor that records every call. */
    private static final class Recorder implements OpVisitor {

        final List<String> calls = new ArrayList<>();

        @Override
        public void clear(int colour) {
            calls.add("clear");
        }

        @Override
        public void fillRect(int x, int y, int w, int h, int colour) {
            calls.add("fillRect");
        }

        @Override
        public void blit(int texture, int sx, int sy, int w, int h, int dx, int dy, int flags) {
            calls.add("blit");
        }

        @Override
        public void text(int x, int y, int fg, int bg, int flags, int font, byte[] segment, int stringOffset,
            int stringLength) {
            calls.add("text");
        }
    }
}
