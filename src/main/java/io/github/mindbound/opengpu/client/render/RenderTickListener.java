package io.github.mindbound.opengpu.client.render;

import java.util.Objects;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The host's START pass (21:85-88; 24:173, :197 "work only at START"; S12 plan P5.4): on FML's bus, where FML posts
 * the render tick (FMLCommonHandler.java:333-341), it runs {@link GlRenderHost#pass()} at each
 * {@code RenderTickEvent} START on the client main thread and does nothing at END. START fires on the title screen,
 * while a GUI is open, while paused and while minimized, and not on loading screens (20 §5). {@code ClientProxy.init}
 * registers it only when it installs a {@link GlRenderHost}. It is the only class of the pump that names FML, and no
 * test loads it (S12 plan P14.1).
 */
public final class RenderTickListener {

    private final GlRenderHost host;

    /** @throws NullPointerException for a null host */
    public RenderTickListener(GlRenderHost host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    /** A render tick: the host's pass at START, nothing at END; see the class comment. */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            host.pass();
        }
    }
}
