package io.github.mindbound.opengpu.client;

import net.minecraft.client.Minecraft;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.client.registry.RenderingRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import io.github.mindbound.opengpu.CommonProxy;
import io.github.mindbound.opengpu.Content;
import io.github.mindbound.opengpu.OpenGPU;
import io.github.mindbound.opengpu.client.gui.DisplayScreen;
import io.github.mindbound.opengpu.client.render.DisplayChassis;
import io.github.mindbound.opengpu.client.render.DisplayRenderer;
import io.github.mindbound.opengpu.client.render.GlCaps;
import io.github.mindbound.opengpu.client.render.GlRenderHost;
import io.github.mindbound.opengpu.client.render.HostInstaller;
import io.github.mindbound.opengpu.client.render.RenderTickListener;
import io.github.mindbound.opengpu.core.render.RenderHost;
import io.github.mindbound.opengpu.display.DisplayBlock;
import io.github.mindbound.opengpu.display.DisplayOpener;
import io.github.mindbound.opengpu.display.DisplayTile;
import io.github.mindbound.opengpu.render.Installation;
import io.github.mindbound.opengpu.render.RenderHosts;

// Client-only code lives under this package: the bytecode test keeps LWJGL and net.minecraft.client
// references out of every other package (docs/research/24-architecture-gl-first.md §3.1).
/**
 * The client proxy (21:59; S12 plan P7.1, P8.1, P9.2, P12.1). Its init runs OC's drivers through {@code super.init}
 * first, then probes the client's GL once ({@link GlCaps}), installs the host an integrated server's session
 * captures at {@code FMLServerStartingEvent} ({@link HostInstaller}, {@link RenderHosts#install}), and, for a
 * {@link GlRenderHost}, puts its START pass on FML's bus ({@link RenderTickListener}). It then binds the display's
 * TESR ({@link DisplayRenderer}) for whatever host it installed, with the block atlas's "no graphics" icon, and gives
 * the display block its chassis ({@link DisplayChassis}) under a new render id, both before any world loads, so every
 * chunk compiled later lists the display and draws its chassis (24:36; 20:169-170). Last it installs the opener that a
 * display's right-click calls, which shows the display's GUI ({@link DisplayScreen}) with a sender for that display
 * ({@link InputSender}) (S12 plan P12.1 with amendment F16). FML's init runs on the client main thread under Forge's
 * splash, with the splash's shared context current (SplashProgress.java:190-196); the probe only reads it, and the
 * host makes no GL object before its first render tick, on the game's own context. Client init always comes before
 * any integrated server starts (S12 plan P2.3, K3).
 */
public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        Installation installation = HostInstaller.choose(
            GlCaps::probe,
            renderer -> GlRenderHost.production(OpenGPU.LOG::warn, OpenGPU.LOG::info, renderer),
            OpenGPU.LOG::info,
            OpenGPU.LOG::warn);
        RenderHosts.install(installation);
        if (installation.host() instanceof GlRenderHost) {
            FMLCommonHandler.instance()
                .bus()
                .register(new RenderTickListener((GlRenderHost) installation.host()));
        }
        // The block registered its icons at the atlas's first stitch, before init, and gets new ones at each later
        // stitch, so the TESR asks for the "no graphics" icon at each draw (S12 amendments I9)
        DisplayBlock screen = Content.screen;
        ClientRegistry.bindTileEntitySpecialRenderer(
            DisplayTile.class,
            new DisplayRenderer(installation.host(), screen::noGraphicsIcon));
        // The render type first, then the handler under it, as OC does (Proxy.scala:50-51; S12 plan P9.2)
        int chassis = RenderingRegistry.getNextAvailableRenderId();
        DisplayBlock.useRenderType(chassis);
        RenderingRegistry.registerBlockHandler(new DisplayChassis(chassis));
        // A right-click on a display opens its GUI through the block's common hook (S12 plan P12.1; amendments F16)
        RenderHost host = installation.host();
        DisplayOpener.install(tile -> open(tile, host, screen));
    }

    /**
     * Shows the GUI of {@code tile} with the installed host, the block atlas's "no graphics" icon and a sender for the
     * tile's block (S12 plan P12.1, P12.6), on the client main thread, from the block's {@code onBlockActivated}.
     */
    private static void open(DisplayTile tile, RenderHost host, DisplayBlock screen) {
        InputSender sender = InputSender.forBlock(tile.xCoord, tile.yCoord, tile.zCoord);
        Minecraft.getMinecraft()
            .displayGuiScreen(new DisplayScreen(tile, host, screen::noGraphicsIcon, sender::send));
    }
}
