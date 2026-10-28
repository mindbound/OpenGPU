package io.github.mindbound.opengpu.spike.ir;

/**
 * A scalar's type (S14 plan P3.3): a 32-bit word read as a signed int or as an IEEE float. Booleans exist only as an
 * {@code If}'s condition; {@code uint}, {@code double} and {@code int64_t} are compute v1's (22:125-127) and M3's.
 */
public enum Type {
    I32,
    F32
}
