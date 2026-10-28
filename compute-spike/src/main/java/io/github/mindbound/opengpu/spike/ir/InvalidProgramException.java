package io.github.mindbound.opengpu.spike.ir;

/** A program {@link Validator#check} refuses, with the program's name and, for a statement, its kernel line. */
public final class InvalidProgramException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    InvalidProgramException(String message) {
        super(message);
    }
}
