package io.github.mindbound.opengpu.spike.load;

import io.github.mindbound.opengpu.spike.emit.Emitter;
import io.github.mindbound.opengpu.spike.ir.Program;

/**
 * A kernel fault as {@code kernel:LINE} (22:180; S14 amendments I8): built from the program's name, the fault's class
 * and the line of its stack element in the program's class, never from its message, which differs between Java 8
 * ({@code 5}) and Java 21 ({@code Index 5 out of bounds for length 3}). The line needs a stack trace, which a cold
 * dispatch always has; C2's {@code OmitStackTraceInFastThrow} may drop it once a site is hot, and then the line is
 * {@code ?}. 22:163's serial re-run, which gives the message in M3, is not the spike's.
 */
public final class KernelFault {

    private KernelFault() {}

    /** For example {@code Oob: a buffer index out of bounds at kernel:3}. */
    public static String report(Program program, Throwable fault) {
        String className = Emitter.className(program);
        int line = -1;
        for (StackTraceElement e : fault.getStackTrace()) {
            if (className.equals(e.getClassName()) && Emitter.SOURCE.equals(e.getFileName())) {
                line = e.getLineNumber();
                break;
            }
        }
        String what;
        if (fault instanceof ArrayIndexOutOfBoundsException) {
            what = "a buffer index out of bounds";
        } else if (fault instanceof NullPointerException) {
            what = "an unbound buffer";
        } else {
            what = fault.getClass()
                .getName();
        }
        return program.name + ": " + what + " at " + Emitter.SOURCE + ":" + (line > 0 ? Integer.toString(line) : "?");
    }
}
