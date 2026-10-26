package io.github.mindbound.opengpu;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

public class Config {

    private static volatile int poolThreads;

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);
        // S11 plan P5.4 [proposal]; 0 keeps 24:172's size
        poolThreads = configuration.getInt(
            "threads",
            "pool",
            0,
            0,
            64,
            "Threads of OpenGPU's pool, which confirms the frames a host has read back. "
                + "0 picks min(4, max(1, processors / 4)).");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    /**
     * {@code pool.threads}, 0 for automatic: the OpenGPU pool's thread count. Only {@code ServerSession.start} reads it
     * and passes it on (S11 amendments E6).
     */
    public static int poolThreads() {
        return poolThreads;
    }
}
