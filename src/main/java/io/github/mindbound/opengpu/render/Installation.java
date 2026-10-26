package io.github.mindbound.opengpu.render;

import java.util.Objects;

import io.github.mindbound.opengpu.core.render.NoRenderHost;
import io.github.mindbound.opengpu.core.render.RenderHost;

/**
 * A render host as a server session captures it (21:51-65; 24:171): the host its devices offer their records to, the
 * reason {@code getCaps().graphics} gives while that host is not available, and the host's cached {@code GL_RENDERER}
 * for {@code getCaps().hostRenderer} (24:125). The card answers both keys from this server-side state, since the SPI
 * returns no host data (28:200; 24:133 (a)). Immutable.
 * <p>
 * The reason is fixed per installation, so a client's {@code GlRenderHost} carries the one Lua sees if that host later
 * fails closed (H63), and a client without graphics carries its GL reason with {@code NoRenderHost}; every client
 * installation carries the probed {@code GL_RENDERER}, {@code NoRenderHost}'s included, and none only when the probe
 * threw ({@link #none}; S12 plan P6.4; Q-S12-7 (a)).
 * S11's interim reason for an integrated server with nothing installed is gone (Q-S11-8; Q-S12-5 (a)): such a server
 * refuses to start ({@link RenderHosts#forServer(boolean)}).
 */
public final class Installation {

    /** {@code graphics} on a dedicated server (24:125). */
    public static final String DEDICATED_SERVER = "dedicated server";

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

    /**
     * {@link NoRenderHost} with the given reason and no {@code hostRenderer}: a client whose GL probe threw, so it has
     * no {@code GL_RENDERER} (S12 amendments I11).
     */
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

    /**
     * The installation as the session's log line gives it, with the reason labelled, so a healthy host's start does not
     * read as a failure (S12 amendments F14): on a client with graphics, for example, "Installation[GlRenderHost,
     * graphics when unavailable: host renderer failed, hostRenderer Intel(R) UHD Graphics 770]", and on a dedicated
     * server "Installation[NoRenderHost, graphics when unavailable: dedicated server, hostRenderer null]".
     */
    @Override
    public String toString() {
        return "Installation[" + host.getClass()
            .getSimpleName()
            + ", graphics when unavailable: "
            + unavailableReason
            + ", hostRenderer "
            + hostRenderer
            + "]";
    }
}
