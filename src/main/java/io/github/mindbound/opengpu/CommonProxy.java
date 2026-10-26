package io.github.mindbound.opengpu;

import java.io.File;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import io.github.mindbound.opengpu.card.CardDriver;
import io.github.mindbound.opengpu.card.CardProvider;
import io.github.mindbound.opengpu.net.Channel;
import io.github.mindbound.opengpu.net.InputListener;
import io.github.mindbound.opengpu.server.TickListener;
import li.cil.oc.api.Driver;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        // config/opengpu/opengpu.cfg, not Forge's suggested config/opengpu.cfg: Windows file names ignore case,
        // so the suggested file would be the same file as any config/OpenGPU.cfg another mod has written.
        Config.synchronizeConfiguration(new File(event.getModConfigurationDirectory(), "opengpu/opengpu.cfg"));
        Content.register();
        // FML records the active mod container when a channel is made, so the channel belongs to OpenGPU's own preInit
        Channel.init();
        // Input's server half on the channel's own bus, as OC registers its handler (03:55; S11 plan U5.8); it runs on
        // both physical sides, since an integrated server takes packets too. EventBus.register needs Loader.instance()
        Channel.get()
            .register(InputListener.INSTANCE);
        // The session's tick queue runs at the start of each server tick, so every frame status is signalled on the
        // server thread (D16; Q-S11-17); FML's bus, where OC registers its own tick handler (ModOpenComputers.scala:94)
        FMLCommonHandler.instance()
            .bus()
            .register(TickListener.INSTANCE);
    }

    public void init(FMLInitializationEvent event) {
        // OC sets its driver registry in its preInit and locks it in its postInit, so drivers register in init, as OC's
        // own do (Driver.java:73-83; Proxy.scala:105, :112-115); before OC's preInit, Driver.add would do nothing
        Driver.add(new CardDriver(Content.card));
        Driver.add(CardProvider.INSTANCE);
    }
}
