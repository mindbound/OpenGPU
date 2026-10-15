package io.github.mindbound.opengpu.core.cmd;

/**
 * An opcode's energy class (28 §5.2's last column). The validator totals the fill per class (28 §3.5), {@code FRAME}
 * carries the totals, and M1 charges energy by them (28 §8.3).
 */
public enum EnergyClass {
    /** {@code CLEAR} */
    CLEAR,
    /** {@code FILL_RECT} */
    FILL,
    /** {@code BLIT} */
    COPY,
    /** {@code TEXT} */
    SET
}
