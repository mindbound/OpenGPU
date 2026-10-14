package net.minecraft.launchwrapper;

import java.util.Map;

/**
 * OpenGPU's stand-in for launchwrapper's Launch on the GLSM legs' runtime classpath only (the GLSM spike, roadmap M0):
 * GTNHLib's memory allocator (MemoryManage.getInstance) and GLSM's SystemProperties.isDeobf read its blackboard while
 * GLSM starts. Outside FML there is none, so it stays null: GTNHLib then allocates through its own Unsafe allocator,
 * and GLSM, seeing no deobfuscated environment, writes no shader dumps.
 */
public class Launch {

    public static Map<String, Object> blackboard;
}
