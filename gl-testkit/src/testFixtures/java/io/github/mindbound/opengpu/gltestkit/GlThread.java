package io.github.mindbound.opengpu.gltestkit;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.lwjgl.opengl.ContextAttribs;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.PixelFormat;

/**
 * The one thread that owns this JVM's GL context (docs/research/23-gl-testing-strategy.md §2.2). LWJGL 2 keeps the
 * context per thread, and a GL call on any other thread fails with "No OpenGL context found in the current thread"
 * (GLContext.java:121-124), so tests, and later GlTestHost (23 §4.2), hand their GL work to this thread. Every call is
 * bounded: a hung driver fails the test instead of hanging the build. The executor is a scheduled one so that
 * GlTestHost's frame pump can run on it.
 */
public final class GlThread implements AutoCloseable {

    /** The two context flavours of 23 §2.2. */
    public enum Profile {
        /** Display.create(PixelFormat) with no attributes, as vanilla Forge creates its context. */
        COMPAT,
        /** 3.3 core, forward-compatible: the context GLSM runs on under Angelica. */
        CORE_33
    }

    /** GL work with no result. */
    public interface GlAction {

        void run() throws Exception;
    }

    /** The window's size. The tests render into framebuffers, so the window only has to exist. */
    public static final int WIDTH = 64;
    public static final int HEIGHT = 64;

    private static final long TIMEOUT_SECONDS = Long.getLong("opengpu.gl.callTimeoutSeconds", 60L);

    private final ScheduledExecutorService executor;
    private volatile Thread thread;
    private volatile Throwable stuck;
    private GlInfo info;

    private GlThread() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "opengpu-gl");
            t.setDaemon(true); // a driver call that never returns must not keep the test JVM alive
            thread = t;
            return t;
        });
    }

    /** Opens the Display with a context of the given profile on a new GL thread. */
    static GlThread start(final Profile profile) throws Exception {
        GlThread gl = new GlThread();
        try {
            gl.info = gl.call(() -> {
                Display.setDisplayMode(new DisplayMode(WIDTH, HEIGHT));
                PixelFormat format = new PixelFormat().withDepthBits(24)
                    .withStencilBits(8); // 23 §1.4
                if (profile == Profile.COMPAT) {
                    Display.create(format);
                } else {
                    Display.create(
                        format,
                        new ContextAttribs(3, 3).withProfileCore(true)
                            .withForwardCompatible(true));
                }
                return GlInfo.query(profile);
            });
            return gl;
        } catch (Exception | Error e) {
            gl.abandon(e);
            throw e;
        }
    }

    /** Runs {@code work} on the GL thread and returns its result; on the GL thread itself, runs it directly. */
    public <T> T call(Callable<T> work) throws Exception {
        if (Thread.currentThread() == thread) {
            return work.call();
        }
        if (stuck != null) {
            throw new IllegalStateException("the GL thread is still stuck in an earlier call", stuck);
        }
        Future<T> result = executor.submit(work);
        try {
            return result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            stuck = e;
            throw new AssertionError(
                "a GL call took longer than " + TIMEOUT_SECONDS + " s (opengpu.gl.callTimeoutSeconds)",
                e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    /** {@link #call} for work with no result. */
    public void run(final GlAction action) throws Exception {
        call(() -> {
            action.run();
            return null;
        });
    }

    /** The context this thread owns. */
    public GlInfo info() {
        return info;
    }

    /** Destroys the Display on the GL thread, unless that thread is stuck, and stops the thread. */
    @Override
    public void close() throws Exception {
        try {
            if (stuck == null) {
                run(() -> {
                    Display.destroy();
                    if (Display.isCreated()) {
                        throw new IllegalStateException("the Display is still created after Display.destroy()");
                    }
                });
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void abandon(Throwable cause) {
        if (stuck == null) {
            try {
                run(() -> {
                    if (Display.isCreated()) {
                        Display.destroy();
                    }
                });
            } catch (Exception | Error e) {
                cause.addSuppressed(e); // e.g. Display's static initializer failed
            }
        }
        executor.shutdownNow();
    }
}
