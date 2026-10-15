package io.github.mindbound.opengpu.core.device;

/**
 * Thrown by {@link Device#createTexture} and {@link Device#updateTexture} at the two-phase pattern's first attempt
 * (28:712), before any side effect, so that OC retries the call as a synchronized one next tick. OC's
 * {@code LimitReachedException} is checked ({@code OCT/java/li/cil/oc/api/machine/LimitReachedException.java:7}), so
 * the {@code @Callback} layer catches this and throws OC's. It has no stack trace.
 */
public final class LimitReached extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The call budget's limit is reached; retry synchronized. */
    public LimitReached() {
        super("call budget limit reached", null, false, false);
    }
}
