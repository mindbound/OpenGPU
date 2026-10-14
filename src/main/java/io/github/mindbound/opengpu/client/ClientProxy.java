package io.github.mindbound.opengpu.client;

import io.github.mindbound.opengpu.CommonProxy;

// Client-only code lives under this package: the bytecode test keeps LWJGL and net.minecraft.client
// references out of every other package (docs/research/24-architecture-gl-first.md §3.1).
public class ClientProxy extends CommonProxy {
}
