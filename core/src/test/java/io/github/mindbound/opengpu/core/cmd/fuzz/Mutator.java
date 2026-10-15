package io.github.mindbound.opengpu.core.cmd.fuzz;

import java.util.Arrays;
import java.util.SplittableRandom;

import io.github.mindbound.opengpu.core.fixtures.gen.GeneratedCase;
import io.github.mindbound.opengpu.core.fixtures.gen.SegmentGenerator;

/**
 * The fuzzer's mutations (28 §11.3): bit flips (a tenth of them in the header), byte insertion and deletion,
 * truncation, splicing with a second generated segment, corruption of a {@code TEXT} count, and opcode bytes
 * overwritten with unassigned or framing values. A clean case is never mutated; a rough one is left as generated a
 * tenth of the time and otherwise takes 1-3 mutations. The mutations draw from their own stream, seeded from the
 * case's seed, so the mutated segment is a function of the case's seed alone and both fuzz tests see the same one.
 */
final class Mutator {

    /** Unassigned opcodes and END (28 §4.4), for insertion and opcode overwrites. */
    private static final int[] OPCODE_BYTES = { 0x00, 0x02, 0x32, 0x40, 0xFE, 0xFF };
    private static final int HEADER = 8;

    private Mutator() {}

    /** The bytes the fuzzer submits for the case. */
    static byte[] submitted(GeneratedCase c) {
        byte[] s = c.segment();
        if (c.isClean()) {
            return s;
        }
        SplittableRandom r = new SplittableRandom(c.seed() * 0x9E3779B97F4A7C15L + 0x2B);
        if (r.nextInt(10) == 0) {
            return s;
        }
        int mutations = r.nextInt(1, 4);
        int[] records = c.recordOffsets();
        int[] counts = c.countOffsets();
        for (int k = 0; k < mutations; k++) {
            s = mutate(s, records, counts, r);
        }
        return s;
    }

    /**
     * One mutation of s; the offsets are the generated segment's, so after a shift they are hints only, and each one
     * is used only if it is still inside s.
     */
    static byte[] mutate(byte[] s, int[] recordOffsets, int[] countOffsets, SplittableRandom r) {
        switch (r.nextInt(7)) {
            case 0:
                return flipBit(s, r);
            case 1:
                return insert(s, r);
            case 2:
                return delete(s, r);
            case 3:
                return truncate(s, r);
            case 4:
                return splice(s, r);
            case 5:
                return corruptCount(s, countOffsets, r);
            default:
                return overwriteOpcode(s, recordOffsets, r);
        }
    }

    private static byte[] flipBit(byte[] s, SplittableRandom r) {
        if (s.length == 0) {
            return s;
        }
        int at = r.nextInt(10) == 0 ? r.nextInt(Math.min(HEADER, s.length)) : r.nextInt(s.length);
        byte[] out = s.clone();
        out[at] ^= (byte) (1 << r.nextInt(8));
        return out;
    }

    private static byte[] insert(byte[] s, SplittableRandom r) {
        int at = r.nextInt(s.length + 1);
        int value = r.nextBoolean() ? r.nextInt(256) : OPCODE_BYTES[r.nextInt(OPCODE_BYTES.length)];
        byte[] out = new byte[s.length + 1];
        System.arraycopy(s, 0, out, 0, at);
        out[at] = (byte) value;
        System.arraycopy(s, at, out, at + 1, s.length - at);
        return out;
    }

    private static byte[] delete(byte[] s, SplittableRandom r) {
        if (s.length == 0) {
            return s;
        }
        int at = r.nextInt(s.length);
        byte[] out = new byte[s.length - 1];
        System.arraycopy(s, 0, out, 0, at);
        System.arraycopy(s, at + 1, out, at, s.length - at - 1);
        return out;
    }

    /** A prefix: a fifth of the time inside the header. */
    private static byte[] truncate(byte[] s, SplittableRandom r) {
        if (s.length == 0) {
            return s;
        }
        int length = r.nextInt(5) == 0 ? r.nextInt(Math.min(HEADER, s.length)) : r.nextInt(s.length);
        return Arrays.copyOf(s, length);
    }

    /** A prefix of s and a suffix of a second generated segment, drawn from this mutation's stream. */
    private static byte[] splice(byte[] s, SplittableRandom r) {
        byte[] donor = SegmentGenerator.generate(r.nextLong())
            .segment();
        int keep = r.nextInt(s.length + 1);
        int from = r.nextInt(donor.length + 1);
        byte[] out = new byte[keep + donor.length - from];
        System.arraycopy(s, 0, out, 0, keep);
        System.arraycopy(donor, from, out, keep, donor.length - from);
        return out;
    }

    /** A TEXT count set to n - 1, n + 1, 0, 1 024, 1 025, 65 535 or a random value. */
    private static byte[] corruptCount(byte[] s, int[] countOffsets, SplittableRandom r) {
        if (countOffsets.length == 0) {
            return flipBit(s, r);
        }
        int at = countOffsets[r.nextInt(countOffsets.length)];
        if (at + 1 >= s.length) {
            return flipBit(s, r);
        }
        int n = (s[at] & 0xFF) | (s[at + 1] & 0xFF) << 8;
        int[] values = { n - 1, n + 1, 0, 1024, 1025, 65535, r.nextInt(65536) };
        int value = values[r.nextInt(values.length)] & 0xFFFF;
        byte[] out = s.clone();
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >>> 8);
        return out;
    }

    private static byte[] overwriteOpcode(byte[] s, int[] recordOffsets, SplittableRandom r) {
        int at;
        if (recordOffsets.length > 0) {
            at = recordOffsets[r.nextInt(recordOffsets.length)];
        } else {
            // The END of an empty segment
            at = s.length - 1;
        }
        if (at < 0 || at >= s.length) {
            return flipBit(s, r);
        }
        byte[] out = s.clone();
        out[at] = (byte) OPCODE_BYTES[r.nextInt(OPCODE_BYTES.length)];
        return out;
    }
}
