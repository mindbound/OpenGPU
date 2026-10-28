package io.github.mindbound.opengpu.spike.ir;

/**
 * A comparison (S14 plan P3.3): signed on {@code I32}, IEEE on {@code F32}, where an ordered comparison with a NaN is
 * false and {@code NE} is true.
 */
public enum Cmp {
    LT,
    LE,
    GT,
    GE,
    EQ,
    NE
}
