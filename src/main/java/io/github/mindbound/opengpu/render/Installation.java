package io.github.mindbound.opengpu.render;

import java.util.Objects;

import io.github.mindbound.opengpu.core.render.NoRenderHost;
import io.github.mindbound.opengpu.core.render.RenderHost;

/**
 * A render host as a server session captures it (21:51-65; 24:171): the host its devices offer their records to, the
 * reason {@code getCaps().graphics} gives while that host is not available, and the host's cached {@code GL_RENDERER}
 * for {@code getCaps().hostRenderer} (24:125). The card answers both keys from this server-side state, since the SPI
 * returns no host data (28:200; 24:133 (a)). Immutable.
 */
public final class Installation {

    /** {@code graphics} on a dedicated server (24:125). */
    public static final String DEDICATED_SERVER = "dedicated server";

    /**
     * {@code graphics} on an integrated server while no client host is installed: every integrated server until S12's
     * {@code ClientProxy.init} installs {@code GlRenderHost} or one of 24:125's GL reasons. S12 removes it (Q-S11-8).
     */
    public static final String NOT_INSTALLED = "host renderer not installed";

    private static final Installation DEDICATED = new Installation(NoRenderHost.INSTANCE, DEDICATED_SERVER, null);

    private final RenderHost host;
    private final String unavailableReason;
    private final String hostRenderer;

    private Installation(RenderHost host, String unavailableReason, String hostRenderer) {
        this.host = host;
        this.unavailableReason = unavailableReason;
        this.hostRenderer = hostRenderer;
    }

    /**
     * @param unavailableReason {@code graphics} whenever {@code host.available()} is false
     * @param hostRenderer      the host's cached {@code GL_RENDERER}, or null when it has none
     * @throws NullPointerException for a null host or reason
     */
    public static Installation of(RenderHost host, String unavailableReason, String hostRenderer) {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(unavailableReason, "unavailableReason");
        return new Installation(host, unavailableReason, hostRenderer);
    }

    /** {@link NoRenderHost} with the given reason and no {@code hostRenderer}. */
    public static Installation none(String reason) {
        return of(NoRenderHost.INSTANCE, reason, null);
    }

    /** A dedicated server's: {@link NoRenderHost} with {@link #DEDICATED_SERVER} (21:63; 24:125). */
    public static Installation dedicated() {
        return DEDICATED;
    }

    /** The host every device of the session offers its records to. */
    public RenderHost host() {
        return host;
    }

    /** {@code graphics} while {@link #host()} is not available. */
    public String unavailableReason() {
        return unavailableReason;
    }

    /** The host's cached {@code GL_RENDERER}, or null. */
    public String hostRenderer() {
        return hostRenderer;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Installation)) {
            return false;
        }
        Installation that = (Installation) other;
        return host == that.host && unavailableReason.equals(that.unavailableReason)
            && Objects.equals(hostRenderer, that.hostRenderer);
    }

    @Override
    public int hashCode() {
        return (System.identityHashCode(host) * 31 + unavailableReason.hashCode()) * 31
            + Objects.hashCode(hostRenderer);
    }

    /** For example "Installation[NoRenderHost, dedicated server, hostRenderer null]". */
    @Override
    public String toString() {
        return "Installation[" + host.getClass()
            .getSimpleName() + ", " + unavailableReason + ", hostRenderer " + hostRenderer + "]";
    }
}
