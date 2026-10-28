package io.github.mindbound.opengpu.spike.ir;

/** How a program may use a buffer binding: 22 §2.4's D1 (22:153), which the validator holds every load and store to. */
public enum Access {
    READONLY,
    WRITEONLY,
    READ_WRITE
}
