package io.github.mindbound.opengpu;

import java.io.File;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        // config/opengpu/opengpu.cfg, not Forge's suggested config/opengpu.cfg: Windows file names ignore case,
        // so the suggested file would be the same file as any config/OpenGPU.cfg another mod has written.
        Config.synchronizeConfiguration(new File(event.getModConfigurationDirectory(), "opengpu/opengpu.cfg"));
    }

    public void init(FMLInitializationEvent event) {}
}
