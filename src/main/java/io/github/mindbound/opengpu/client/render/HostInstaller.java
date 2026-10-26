package io.github.mindbound.opengpu.client.render;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.github.mindbound.opengpu.core.render.NoRenderHost;
import io.github.mindbound.opengpu.render.Installation;

/**
 * The installation the client proxy hands {@code RenderHosts} (21:59, :63; 24:125, :230; S12 plan P7.1 with
 * amendments I11 and F6): the probe's caps decide, and only graphics that are on make a {@link GlRenderHost}, so no
 * {@code :gl} class loads on a client without graphics, where an Angelica below the floor could fail to link it
 * (25 §3). It names no Minecraft, FML or LWJGL type, so the root's plain tests run it with their own probe and host
 * factory.
 */
public final class HostInstaller {

    /**
     * {@code graphics} for a {@link GlRenderHost} that is no longer available, and for a client whose probe threw
     * (Q-S12-5 (a)). An installation's reason is fixed for its sessions, so a host that fails closed later (H63)
     * answers this. M1's "disabled after GPU timeout" and "disabled by config" read the reason from the host instead
     * (S12 plan P6.4, P20).
     */
    public static final String HOST_RENDERER_FAILED = "host renderer failed";

    private HostInstaller() {}

    /**
     * Runs the probe once and logs its caps line at info; a null reason installs {@code hosts.apply(renderer)} with
     * {@link #HOST_RENDERER_FAILED}, a reason installs {@code NoRenderHost} with it, and {@code hosts} is not called.
     * Both carry the probed {@code GL_RENDERER} (Q-S12-7 (a)). A probe that throws, anything at all, a
     * {@code LinkageError} included, installs {@code NoRenderHost} with {@link #HOST_RENDERER_FAILED} and no
     * {@code hostRenderer}, logged once at warn with its stack trace, so the client still installs before any
     * integrated server starts (S12 plan P2.3, K3). A host factory that throws, such as a {@code :gl} class that fails
     * to link or font 0's tables that fail to load, is caught the same way, and installs {@code NoRenderHost} with
     * {@link #HOST_RENDERER_FAILED} and the probed {@code GL_RENDERER}, so a broken host never ends the client at FML
     * init (S12 decisions D11).
     *
     * @param probe the client's GL probe, {@link GlCaps#probe} in game
     * @param hosts makes the host from the probed {@code GL_RENDERER}, for {@link GlRenderHost}'s cross-check
     * @throws NullPointerException for a null argument
     */
    public static Installation choose(Supplier<GlCaps> probe, Function<String, GlRenderHost> hosts,
        Consumer<String> info, Consumer<String> warn) {
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(hosts, "hosts");
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(warn, "warn");
        GlCaps caps;
        try {
            caps = Objects.requireNonNull(probe.get(), "the probe's caps");
        } catch (Throwable e) {
            warn.accept(
                "OpenGPU GlCaps: the probe failed, so graphics are unavailable (\"" + HOST_RENDERER_FAILED
                    + "\"): "
                    + stackTrace(e));
            return Installation.none(HOST_RENDERER_FAILED);
        }
        info.accept(caps.logLine());
        String reason = caps.reason();
        if (reason != null) {
            return Installation.of(NoRenderHost.INSTANCE, reason, caps.renderer());
        }
        GlRenderHost host;
        try {
            host = Objects.requireNonNull(hosts.apply(caps.renderer()), "the factory's host");
        } catch (Throwable e) {
            warn.accept(
                "OpenGPU host: the host renderer could not be made, so graphics are unavailable (\""
                    + HOST_RENDERER_FAILED
                    + "\"): "
                    + stackTrace(e));
            return Installation.of(NoRenderHost.INSTANCE, HOST_RENDERER_FAILED, caps.renderer());
        }
        return Installation.of(host, HOST_RENDERER_FAILED, caps.renderer());
    }

    private static String stackTrace(Throwable e) {
        StringWriter text = new StringWriter();
        e.printStackTrace(new PrintWriter(text));
        return text.toString();
    }
}
