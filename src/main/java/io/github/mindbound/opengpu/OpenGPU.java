package io.github.mindbound.opengpu;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import io.github.mindbound.opengpu.server.ServerSession;

// OpenComputers 1.12.64-GTNH is the floor (decision 14, docs/research/24-architecture-gl-first.md).
@Mod(
    modid = OpenGPU.MODID,
    version = Tags.VERSION,
    name = "OpenGPU",
    acceptedMinecraftVersions = "[1.7.10]",
    dependencies = "required-after:OpenComputers@[1.12.64-GTNH,);")
public class OpenGPU {

    public static final String MODID = "opengpu";
    public static final Logger LOG = LogManager.getLogger(MODID);

    @SidedProxy(
        clientSide = "io.github.mindbound.opengpu.client.ClientProxy",
        serverSide = "io.github.mindbound.opengpu.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    // A server posts both on its own thread, an integrated one too (21:111), so a session is found by its thread
    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        ServerSession.start(event.getServer());
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        ServerSession.stop();
    }
}
