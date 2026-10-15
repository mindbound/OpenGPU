package io.github.mindbound.opengpu.core.device;

/**
 * The strings programs branch on (28:347; 24:126-127), which the root's {@code api} package copies as compile-time
 * constants. Every other message is documentation.
 */
public final class Statuses {

    /**
     * {@code present} at the third unconfirmed frame; {@code createTexture}/{@code updateTexture} at the tail bound
     * (H2). In both a frame is in flight, and its status follows. Also {@code createTexture} while the open frame's
     * deferred frees would pass {@code maxTextures} or {@code vramBytes} (H23, amendment A): only {@code present}, the
     * {@code "no renderer"} discard or a reset clears that case, and no frame status follows it, so a program must
     * present before it retries rather than wait for {@code opengpu_frame}.
     */
    public static final String BUSY = "busy";
    /** {@code present} without a renderer (28:204). */
    public static final String NO_RENDERER = "no renderer";
    /** {@code present} while the host's render loop is silent (24:126). */
    public static final String HOST_RENDER_STALLED = "host render stalled";
    /** {@code opengpu_frame}: the frame is confirmed (28:110). */
    public static final String DONE = "done";
    /** {@code opengpu_frame}: still unconfirmed 1 s after present (28:112). */
    public static final String DROPPED = "dropped";

    private Statuses() {}
}
