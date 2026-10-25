import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.gradle.api.GradleException;

/**
 * One external process for the OC-LuaJIT leg's tasks (M0 step S10; S10 decisions D6): its git calls and its luajit run.
 * As PinnedGitCheckout's git calls (S10 amendments I8), each has a wall-time cap, both output streams are read on
 * threads of their own, so a full pipe never blocks the process, and a process that runs past its cap is stopped with
 * its children. PinnedGitCheckout keeps its own copy, which U1's negative cases verified.
 */
final class ProcessRun {

    /** A finished process: its exit value and both outputs, UTF-8. */
    static final class Result {

        final int exit;
        final String out;
        final String err;

        Result(int exit, String out, String err) {
            this.exit = exit;
            this.out = out;
            this.err = err;
        }
    }

    private ProcessRun() {}

    /**
     * Runs {@code command} in {@code directory} with the caller's environment less {@code removed} and plus
     * {@code set},
     * and waits at most {@code timeoutSeconds}. A process that cannot start, or runs past its cap, fails the build with
     * {@code what} in the message.
     */
    static Result run(String what, List<String> command, File directory, List<String> removed, Map<String, String> set,
        long timeoutSeconds) {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory);
        Map<String, String> environment = builder.environment();
        removed.forEach(environment::remove);
        environment.putAll(set);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new GradleException(what + ": " + command.get(0) + " did not start: " + e.getMessage(), e);
        }
        Drain out = new Drain(process.getInputStream());
        Drain err = new Drain(process.getErrorStream());
        try {
            process.getOutputStream()
                .close();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                stop(process);
                throw new GradleException(what + " ran past " + timeoutSeconds + " s and was stopped");
            }
            return new Result(process.exitValue(), out.text(), err.text());
        } catch (IOException e) {
            stop(process);
            throw new GradleException(what + ": " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            stop(process);
            throw new GradleException("interrupted while running " + what, e);
        }
    }

    /** The children first: they outlive a killed parent on Windows. */
    private static void stop(Process process) {
        process.descendants()
            .forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
        }
    }

    /** Reads one of a process's streams on a thread of its own. */
    private static final class Drain {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final Thread thread;

        Drain(InputStream in) {
            thread = new Thread(() -> {
                byte[] chunk = new byte[8192];
                try (InputStream stream = in) {
                    for (int n = stream.read(chunk); n >= 0; n = stream.read(chunk)) {
                        synchronized (bytes) {
                            bytes.write(chunk, 0, n);
                        }
                    }
                } catch (IOException e) {
                    // The process was stopped; what was read stays
                }
            }, "opengpu-process-drain");
            thread.setDaemon(true);
            thread.start();
        }

        String text() throws InterruptedException {
            thread.join(10_000);
            synchronized (bytes) {
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }
}
