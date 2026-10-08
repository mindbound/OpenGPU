# 21 — Server-to-host-client render handoff under Option 2: single player, LAN host, LAN guests, dedicated servers

Researcher, 2026-10-08. Assignment: specify how the server-side authoritative `Device` and the host client's GL renderer cooperate in one JVM under the owner's Option 2 (GL-authoritative on the host, PBO readback, guests receive the host's pixels), with lifetimes, resources, LAN streaming, dedicated-server options and persistence. Builds on 12 §4 and §6, 13, and 09 §3.5–3.6, and corrects them where Option 2 changes the answer.

Abbreviations: `MC/` = `C:\Users\astro\Downloads\OC-LuaJIT\build\rfg\minecraft-src\java` (RFG-decompiled Minecraft 1.7.10 + Forge 10.13.4.1614); `OC/` = `C:\Users\astro\Downloads\OpenComputers-GTNH\src\main\scala\li\cil\oc` (1.12.55 checkout; the files cited are byte-identical at 1.12.64-GTNH per 13's `diff`); `SP/` = the session scratchpad `C:\Users\astro\AppData\Local\Temp\claude\C--Users-astro-Downloads-OpenGPU\170a14cc-5cc1-40e0-a7ec-6130b748eabf\scratchpad`. Tags: **[V]** verified in source or bytecode, **[I]** inference from verified code, **[est.]** estimate, **[measured]** produced for this report, **[derived]** arithmetic on cited figures. Version applicability: everything below holds for Java 8 + LWJGL 2.9.4 with and without Angelica 2.2.21 (the only Angelica-dependent fact, that `RenderTickEvent` still fires, is checked in §2.1); nothing here depends on lwjgl3ify.

## Summary

1. **Hand frames to the host GPU through an in-JVM SPI, not through packets.** A common-code `RenderHost` interface holds a `NoRenderHost` by default; only the client proxy installs `GlRenderHost`, so dedicated servers never load a client class. Side checks: `server.isDedicatedServer()` and `world.isRemote`, never `getEffectiveSide()` (thread-name check) or `getSide()` (physical side, CLIENT on the integrated server) (§1).
2. **The local packet channel is the wrong carrier for the host.** The client drains in-world packets only in `runTick` and only while `!isGamePaused && theWorld != null` (`Minecraft.java:1691-1694`), whereas `RenderTickEvent` fires on every rendered frame, with or without a world, paused or not (`:1063-1070`). The host must render every device on the integrated server, not just those whose chunks it watches (§1.4, §2).
3. **The server side must never wait for the render thread.** On quit, the render thread busy-waits for the integrated server to stop (`Minecraft.java:2264-2279`) while the server does its final save. Pausing single player also triggers a save (`IntegratedServer.java:109-114`) (§2.5).
4. **Persist confirmed state plus an in-flight tail.** Confirmed state is the last read-back pixels plus the resource master copies as of that frame. The tail holds the immutable upload and frame records after it. The tail is replayed on load, which replaces 09 §3.6's "drop in-flight frames" (§6).
5. **Read back the newest frame of every device once per client frame, eagerly**, through two PBOs filled with `glGetTexImage(..., long)`, because Angelica 2.2.21's GLSM lacks the `glReadPixels` PBO overload (20 §3). A polled `GL_TIMESTAMP` query is the non-blocking completion fence; `GLSync` stays avoided per 08-B §5.5 (§2.2).
6. **Lua upload arrays are fresh per call.** JNLua allocates them with `NewByteArray`, and OC-LuaJIT uses the same native code. The device can therefore take ownership without copying; master copies stay server-private, and LWJGL 2 forces one direct-buffer staging copy on the render thread (§3).
7. **LAN guests get host pixels as deflated 16×16 dirty tiles [measured].** 3D camera motion costs 2.5–4.3 KB/frame at T1, 6.5–15 KB at T2 and 34–123 KB at T3. That is 0.03 / 0.13–0.31 / 0.7–2.5 MB/s at 10/20/20 fps. UI traffic is two orders of magnitude lower. Wired LAN and 5 GHz Wi-Fi carry this easily. Pixels win over guest-side replay for v1 (§4).
8. **Dedicated servers in v1: renderer `none`.** Compute works fully, `present` returns `nil,"no renderer"`, and the persisted last image is still shown. The v1 protocol keeps the record format, capability fields and async `RenderHost` SPI so that display-only viewers (b) and a software renderer (d) can be added later. Delegating rendering to a client (c) is rejected (§5).

## Findings

### 1. Object model and side separation

**1.1 What each side API actually answers [V].**

| API | Returns | Evidence | Use in OpenGPU |
|---|---|---|---|
| `FMLCommonHandler.getSide()` | physical side: CLIENT in the client JVM, *including* code running on the integrated server | `MC/cpw/mods/fml/common/FMLCommonHandler.java:142-145` → `FMLClientHandler.java:458-461`, `FMLServerHandler.java:142-145` | Only "is this JVM a client". Never "am I the logical server". |
| `getEffectiveSide()` | `"Server thread"` name → SERVER, any other thread → CLIENT | `FMLCommonHandler.java:152-160`; thread named at `MinecraftServer.java:751` | Never. It says CLIENT on OC workers, pool threads and Netty threads, also on dedicated servers (12 §4). |
| `@SideOnly` | `SideTransformer` strips members, or refuses whole classes, by **physical** side (`FMLLaunchHandler.side()`) | `SideTransformer.java:33, 44-51, 66-78, 85-115` | `@SideOnly(Side.CLIENT)` on client-only classes makes a stray server-side load fail loudly. **Never** use `@SideOnly(Side.SERVER)`: it is stripped in the client JVM and would break the integrated server. |
| `@SidedProxy` | instantiates `clientSide` or `serverSide` by physical side via `Class.forName` | `ProxyInjector.java:58-59`, called with `getSide()` at `FMLModContainer.java:512` | The only entry point to client classes. |
| `MinecraftServer.isDedicatedServer()` | false for `IntegratedServer`, true for `DedicatedServer` | abstract `MinecraftServer.java:1168`; `IntegratedServer.java:161-164` | The server-side test for whether a host GPU can exist. |
| `Minecraft.isIntegratedServerRunning()`, `isSingleplayer()` | both true while an integrated server runs, **including after "Open to LAN"** (1.7.10's `isSingleplayer` does not exclude public worlds) | `Minecraft.java:2769-2780` | Client code only. |
| `IntegratedServer.getPublic()` | true after `shareToLAN`; **never reset**, since 1.7.10 has no "close LAN" | `isPublic = true` only at `IntegratedServer.java:248`; getter `:292-295` | LAN ends only when the server stops. |
| `MinecraftServer.getServer()` | static, assigned in the constructor and never cleared | `MinecraftServer.java:88, 164, 938-941` | Stale after quitting to the title and joining a remote server. Never use it in client code to detect single player. |

**1.2 OC's precedent [V].** OC keeps every piece of component *state* on separate objects per side and moves it only through packets, even in single player:

- A `TextBuffer` chooses `ClientProxy` or `ServerProxy` at construction (`OC/common/component/TextBuffer.scala:94-96`). The choice uses `SideTracker.isClient`, which is `!getEffectiveSide().isServer()` (`OpenComputers-GTNH/src/main/java/li/cil/oc/util/SideTracker.java:7-13`), so it is correct only because buffers are constructed on the server or client main thread. The server proxy appends commands under the buffer's monitor (`:739-760`), and the client copy re-requests a full sync on a cooldown (`:163-169`).
- Client and server keep separate `ComponentTracker` objects (`OC/client/ComponentTracker.scala:6`, `OC/server/ComponentTracker.scala:6`), keyed by dimension and cleared on `WorldEvent.Unload` (`OC/common/ComponentTracker.scala:19-58`).

The only direct cross-side touches are pause handling:

- `Machine.isGamePaused` reads `Minecraft.getMinecraft.isGamePaused` from an OC worker thread, guarded by `!isDedicatedServer` and a pattern match on `IntegratedServer` (`OC/server/machine/Machine.scala:977-980`, used at `:990-992`).
- `Tablet`'s client tick handler calls the *server-side* cache's `Server.keepAlive()` while the integrated game is paused (`OC/common/item/Tablet.scala:476-484`). It has no `isDedicatedServer` check: it is a `ClientTickEvent` handler (never fired on a dedicated server) guarded by a pattern match on `IntegratedServer`.
- `OC/client/Sound.scala:47-50` reads the pause state the same way from client code, through the never-cleared `MinecraftServer.getServer`.

All three rely on HotSpot resolving class references lazily, so `IntegratedServer`/`Minecraft` are never loaded on a dedicated server **[I]**. The precedent is therefore: OC never hands render data across sides in memory, and it does read client-global flags from server code. OpenGPU must break with the first half deliberately, because under Option 2 the pixels exist only on the host GPU. It should not copy the second half's style: no client class should be named in common bytecode.

**1.3 Design: the `RenderHost` SPI.**

```
common (no client imports; loaded on both sides)
  render.RenderHost            interface: boolean available(); void submit(DeviceKey, Op); void release(DeviceKey);
                               void bindDisplay(String displayId, DeviceKey); void releaseSession(long session)
  render.RenderHosts           static volatile RenderHost installed = NoRenderHost.INSTANCE;
                               static RenderHost forServer(MinecraftServer s) { return s.isDedicatedServer() ? NoRenderHost.INSTANCE : installed; }
  render.ReadbackSink          implemented by the server-side Device: onReadback(session, deviceId, epoch, frameId, byte[] px) (non-blocking)
  CommonProxy                  @SidedProxy serverSide
client (@SideOnly(Side.CLIENT) at class level; LWJGL imports live only here)
  ClientProxy extends CommonProxy   init(): if GL caps suffice -> RenderHosts.installed = new GlRenderHost()
  render.GlRenderHost          MPSC op queue, per-device GL targets keyed by (session, deviceId), PBO ring, RenderTickEvent hook
```

A `Device` (in `:core`, on the integrated server) captures `RenderHosts.forServer(server)` once, when the card environment is created on the server thread, together with a `session` id drawn at `FMLServerStartingEvent`. It never asks again from a pool or worker thread. The host keys everything by `(session, deviceId)` and maps `displayId → DeviceKey` itself. The display's client TE, which learns `displayId` from its description packet (09 §3.6), asks `GlRenderHost` for the texture to draw. In single player, no packet ever carries OpenGPU display content to the host.

Dedicated server: `ClientProxy` is never instantiated (`ProxyInjector.java:58-59`), `installed` stays `NoRenderHost`, and no common class names an `opengpu.client.*` or `net.minecraft.client.*` type. A host whose GL is inadequate (capability probe fails) also keeps `NoRenderHost` and behaves exactly like a dedicated server for graphics (§5).

**1.4 Why not the host's local packet channel.** 12 §4 routed the host through `isLocalChannel()` with undeflated `TILES`. Under Option 2 the host receives *commands*, and the local-channel route fails on four counts:

- **(i) Latency and stalls [V].** Client-side, in-world packets are queued (`NetworkManager.java:120-130`) and drained by `PlayerControllerMP.updateController` → `processReceivedPackets` (`PlayerControllerMP.java:311-318`). That runs from `runTick` only when `!isGamePaused && theWorld != null` (`Minecraft.java:1691-1694`): 20 Hz, so 0–50 ms of extra wait, and nothing while paused. Before the client world exists, `runTick`'s `pendingConnection` branch drains the same queue (`Minecraft.java:2149-2153`), also at 20 Hz, but only once the local connection exists (iv).
- **(ii) Recipients [I].** Packets go to chunk watchers (13 §4.2), but the host GPU must execute every device on the integrated server, including displays in another dimension or outside view distance. Otherwise canvas state, Lua readback and guests' pictures stall.
- **(iii) Ordering [V].** The first frame can overtake the TE description packet (13 §4.4).
- **(iv) Lifetime [I].** The local connection exists only between login and quit, while the integrated server ticks before the client connects (`Minecraft.java:2200-2233`: the client waits for `serverIsInRunLoop`, then opens the local connection).

A minor correction to 12 §4: across the local channel the payload **array**, not the packet object, crosses by reference. `FMLProxyPacket.toS3FPacket` builds a new `S3FPacketCustomPayload` around `payload.array()` (`FMLProxyPacket.java:132-135`, `S3FPacketCustomPayload.java:25-29`), and the client side wraps the same array in a new `FMLProxyPacket` (`NetworkDispatcher.java:291-293`, `FMLProxyPacket.java:39-42`).

### 2. Threading, ordering and lifetimes

**2.1 Actors and flow.**

1. **OC worker** (direct callbacks). `consumeCallBudget`, then validate, then take ownership of the argument `byte[]` (§3). It appends an `UploadOp` or `FrameOp` to the device's ordered tail under the device monitor (≤ 0.2 ms, 09 §3.5) and calls `renderHost.submit`, which is one `ConcurrentLinkedQueue.offer` plus marking the device runnable. A third un-confirmed frame returns `nil,"busy"` (09 §3.2).
2. **Client render thread**, in a `TickEvent.RenderTickEvent` `START` handler. It fires from `runGameLoop` on every frame whenever `skipRenderWorld` is false (`Minecraft.java:1063-1070`; `FMLCommonHandler.java:333-341`), and vanilla only ever sets that flag to false (`Minecraft.java:866`, the only writer). So it fires at the title screen, in loading GUIs, with the host in any dimension, and while paused. Angelica 2.2.21 subscribes to the same event for its zoom (`SP/Angelica-2.2.21/.../zoom/Zoom.java:115-119`). It does inject into `runGameLoop` (`MixinMinecraft`, `MixinMinecraft_FrameHook`, `MixinMinecraft_FPSCap`, `MixinMinecraft_IconifyGuard` and `MixinMinecraft_SkipEndFrameFlush` in `SP/Angelica-2.2.21/src/mixin/java/com/gtnewhorizons/angelica/mixins/early/angelica/`, all present in the installed `angelica-2.2.21.jar`), but none touches `onRenderTickStart/End` or `skipRenderWorld`. `IconifyGuard` only redirects `updateCameraAndRender`, which sits between the two events, so the hook survives Angelica, including while minimized. Angelica's optional FPS reducer (`ReducerSettings.enabled = false` by default; when enabled, 10 fps unfocused and 30 fps idle, `SP/Angelica-2.2.21/src/main/java/me/jellysquid/mods/sodium/client/gui/SodiumGameOptions.java:253-262`) lowers the host frame rate, and with it OpenGPU's throughput (§2.3).

   The handler runs four steps:

   - (a) Map last frame's PBOs. For each, copy into a server-owned `byte[]` taken from a two-array ring per device, then call `sink.onReadback(...)`.
   - (b) Drain the op queue round-robin across devices under a per-frame budget (`client.renderBudgetMs`, default 4 ms **[est.]**, measured in M0). Execute uploads and frames into the device's offscreen target, saving and restoring the FBO and viewport, because `framebufferMc` is bound at this point (`Minecraft.java:1052`).
   - (c) Issue the readback into a PBO for the **newest** frame each device executed this render frame, with `glGetTexImage(..., long)` on the packed target. Do not use `glReadPixels(..., long)`: Angelica 2.2.21's `GLStateManager` has no `glReadPixels(IIIIIIJ)V` (javap of the installed jar; 20 §3), so the redirected call throws `NoSuchMethodError` on the owner's instance. Follow it with a `GL_TIMESTAMP` query (§2.2). Older frames of the same device complete with it (coalescing).
   - (d) Execute `release` and `releaseSession` ops.

   The TESR for the host's own view samples the device's display texture, which must be the *quantized* image so that the host sees what Lua and the guests see.
3. **OpenGPU pool.** `onReadback` stores the result and schedules one pool task per device. The task checks session and epoch, folds the tail up to that frame into confirmed state (§6), diffs tiles, encodes for guests, and raises one coalesced `opengpu_frame(card, frameId, "done")` through the card's `AtomicReference<Context>` (12 §6). `Machine.signal` locks only `state` and `signals` and accepts signals while the machine is `Paused` (`OC/server/machine/Machine.scala:335-369`). The render thread never calls into OC, so it can never contend for those monitors. Compute dispatches also run here (decision 4). Their output buffers become server-produced `UploadOp`s in the same device queue, which is how compute feeds graphics "in tandem".
4. **Server thread.** Unchanged from 09 §3.5 (viewer snapshots, input, saves), except that the host's own connection (`isLocalChannel()`, `NetworkManager.java:274-277`) is excluded from every snapshot.

**2.2 Ordering and completion.** Each device has one FIFO from the OC worker to the render thread, and every op carries `(session, deviceId, epoch, generation, frameId)`. The render host executes ops of one device strictly in order, with no ordering across devices. "Done" means the pixels are confirmed server-side. `GLSync` is neither redirected nor reported by Angelica's GLSM (02 C3), and 08-B §5.5 bans it. On the GL backends it would still pass through to the driver and work (20 §3), so it is avoidable rather than unusable. The blocking-free way to learn the GPU finished frame N is a `GL_TIMESTAMP` query issued after N's readback and polled with `GL_QUERY_RESULT_AVAILABLE` through `glGetQueryObjecti`, which GLSM maps (20 §3, "Completion without `GLSync`"; `glGetQueryObjectui(int,int)` is missing from 2.2.21). Only then is the PBO mapped or read with `glGetBufferSubData`. Mapping blindly one render frame later is usually stall-free but not guaranteed, because the driver queue and Angelica's render-ahead limit (`cpuRenderAheadLimit`, `MixinMinecraft.java`) can let the GPU lag by more than one frame **[I]**. Eager readback plus the polled query is the fence.

Cost per readback: 16 KB (T1), 64 KB (T2) or 512 KB (T3) in wire format. At 20 fps that is ≤ 10 MB/s per T3 device across the bus and a 0.05–0.2 ms copy on the render thread **[est.]**. To stay on drivers' fast path, pack the quantized image into an `RGBA8` target (4 index8 or 2 RGB565 pixels per texel) and read it with `glGetTexImage(GL_TEXTURE_2D, 0, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0L)` into the pack PBO, not as `GL_UNSIGNED_SHORT_5_6_5` **[I]**. PBO readback is tracked by GLSM (02 §4, line 89).

**2.3 Latency and throughput [derived].** `present` at time t is executed at the next `RenderTickEvent` (average ½ client frame), read back one frame later, folded and signalled within ≈ 1 ms on the pool. That is ≈ 1.5 client frames until confirmation: ≈ 25 ms at 60 fps, 50 ms at 30 fps and 150 ms at 10 fps. A Lua program that sleeps in `pullSignal` waiting for that signal resumes only at the next server tick's `Machine.update` (`OC/server/machine/Machine.scala:584-585`), plus OC's `executionDelay` of 12 ms before the executor runs (`Machine.scala:960-968`; 00, 04 §3): another 12–62 ms, ≈ 37 ms on average. A signal already queued when the program pulls is taken without sleeping (`Machine.scala:1008-1016`), still after the 12 ms reschedule. With ≤ 2 unconfirmed frames and a program that waits for `done`, the sustained rate is ≈ 2 / (1.5 client frames + 37 ms): about 32 fps at 60 client fps, 23 at 30, 18 at 20 and 11 at 10. So **a host running below ~25 fps cannot sustain T2/T3's 20 fps** for such a program, and Lua sees `busy`, which is the intended back-pressure. In single player OpenGPU therefore has two clocks: the host's client fps (execution and confirmation) and the server tick (Lua wake-ups). Vanilla's `pauseOnLostFocus` (default on) opens the pause menu when the window loses focus (`MC/net/minecraft/client/renderer/EntityRenderer.java:1023-1029`), so alt-tabbing pauses every OpenGPU program in non-LAN single player.

**2.4 The render thread is the shared bottleneck in single player [I].** Every device on the integrated server executes there, including unwatched ones (canvas state needs it). Fifty T3 devices presenting 2D UIs at 20 fps is 1 000 jobs/s on one thread. The per-frame budget plus round-robin bounds the damage to the host's frame rate, and the overflow surfaces as `busy` to Lua. Frame-mode devices (self-contained frames, 09 §3.1) may skip superseded frames, which complete as `"superseded"` with the newer frame's pixels. Canvas devices may not skip. The real per-job cost is an M0 measurement.

**2.5 Lifetimes.**

| Event | Thread, evidence | Server-side action | Render-host action |
|---|---|---|---|
| Card removed or moved; computer chunk unloads | server thread; `onDisconnect(node)` (12 §6), `traits/Computer.dispose` → `machine.stop()` | epoch++, `ctx = null`, tail persisted with the card's side file, `renderHost.release(key)` | delete the device's GL objects next frame; drop late readbacks (epoch mismatch) |
| `computer.stopped/started` | `GraphicsCard.onMessage` pattern (12 §6) | epoch++, handles reset (09 §3.6); confirmed pixels kept, so the display keeps its last picture | free resources and keep the target |
| Display TE chunk unload | server thread | unbind display; the device lives on with the card | drop the `displayId` mapping |
| Autosave every 900 ticks | `MinecraftServer.java:636-641` | snapshot confirmed state + tail under the device monitor; write asynchronously (§6) | none |
| Single-player pause | server stops ticking and saves on entry (`IntegratedServer.java:104-118`); OC machines push `Paused` (`Machine.scala:977-992`) | none (no ticks) | keeps executing and reading back in-flight frames; folds and signals queue into the paused `Machine` |
| Quit to title, frames in flight | render thread: `WorldEvent.Unload` (client), `initiateShutdown()`, then **busy-wait** `while (!isServerStopped()) sleep(10)` (`Minecraft.java:2251-2279`); server thread: `handleServerStopping`, `stopServer()`, which saves all worlds then posts `WorldEvent.Unload` (`MinecraftServer.java:388-418, 496, 541-551`) | final save from confirmed state + tail, **never** waiting for a readback (it could not arrive); at `FMLServerStoppedEvent`, wait for the side-file writer as OC does (`OC/OpenComputers.scala:93-95`, `util/ThreadPoolFactory.scala:89-96`), then `releaseSession(session)` | queue drained once `loadWorld` returns; deletes everything of the dead session |
| LAN open | `shareToLAN` (`IntegratedServer.java:226-256`); public worlds never pause (`Minecraft.java:1117`) | guests start appearing in snapshots | none |
| LAN "closes" | no such action in 1.7.10 (§1.1); a guest leaving drops out of the next snapshot, and a dead `NetworkManager` is skipped (13 §4.3) | — | — |
| Game window closed | `Minecraft.shutdown` path | as quit | GL context dies with the window; no deletes needed |

Hodgepodge 2.7.216 hooks `loadWorld` only at `handleClientWorldClosing` (`MixinMinecraft_ShutdownHook`, `@Inject` at that `INVOKE`, read with `javap`), so the busy-wait is present on the owner's instance **[V]**. The busy-wait ends when `serverStopped` is set right after `stopServer()` (`MinecraftServer.java:541-543`), *before* `handleServerStopped` posts `FMLServerStoppedEvent` (`:551`). The writer wait at `FMLServerStoppedEvent` therefore runs while the client is already back at the title screen, and the player could already be opening another world. OC's `saving` map lives in a global `object` (`OC/common/SaveHandler.scala:34, 74`), so a quick reopen still waits for the last write (`:148-156`); OpenGPU's pending-write map must likewise outlive the server instance. The same wait-free rule covers start-up: the render thread sleeps in 200 ms steps until `serverIsInRunLoop()` (`Minecraft.java:2200-2215`), during which no `RenderTickEvent` fires, so ops submitted by early server ticks simply queue.

### 3. Resources

**3.1 Ownership transfer, not copies [V].** `ArgumentsImpl.checkByteArray` returns the argument array itself (`OC/server/machine/ArgumentsImpl.scala:179-185`). That array is created per call:

- native Lua: `ExtendedLuaState.toSimpleJavaObject` → `lua.toByteArray` (`OC/util/ExtendedLuaState.scala:106-109`) → JNLua's native `lua_tobytearray`, which allocates with `NewByteArray` on every call (`C:\Users\astro\Downloads\OC-JNLua\native\src\jnlua.c:930-953`);
- OC-LuaJIT: the same native entry (`OC-LuaJIT/src/main/java/li/cil/repack/com/naef/jnlua/LuaStateLuaJIT.java:195`), built from OC-JNLua's `jnlua.c` "unmodified except for ONE" diagnostic patch (`OC-LuaJIT/native/build-native.sh:10`);
- LuaJ: a `String` argument goes through `getBytes` (`ArgumentsImpl.scala:182`), which is also fresh.

The device therefore takes the array as its own immutable `UploadOp` payload: zero copies on the worker. Rules:

- (1) Payload and command arrays are never written after `submit`. Dev builds hash them at submit and at GL upload and assert equality.
- (2) Master copies (VRAM accounting, persistence) are separate server-private arrays, mutated only by the pool's fold (§6), and never referenced by the render host.
- (3) Ops reference no `Machine`, TE, `World` or `Context`, only ids and byte arrays (12 §6's leak rule extended to the render host).
- (4) Readback arrays flow the other way. They are server-owned once handed over and recycled through the two-array ring per device.

The `LimitReached` retry is safe because budget charges precede side effects (09 §3.2), so a retried call never submits twice.

**3.2 The one unavoidable copy [V].** LWJGL 2.9.4's `GL11.glTexSubImage2D(..., ByteBuffer)` calls `BufferChecks.checkBuffer`, which calls `checkDirect` (`javap -c` of `lwjgl-2.9.4-nightly-20150209.jar`: `GL11` → `BufferChecks.checkBuffer(ByteBuffer,int)` → `checkDirect`). Heap arrays must be copied into a direct staging buffer or into a mapped `GL_PIXEL_UNPACK_BUFFER` (`glTexSubImage2D(..., long)` exists in GLSM 2.2.21, javap). `checkDirect` runs only while `LWJGLUtil.CHECKS` is on, but the native call always receives `MemoryUtil.getAddress(pixels)`, which is no valid pointer for a heap buffer, so the requirement holds either way. This design makes the copy on the render thread; filling a pooled direct buffer on the OpenGPU pool would move it off that thread, at the cost of direct memory held per queued op **[I]**. Budget it with the ops (≈ 0.1 ms per 1 MB **[est.]**), with at most ≈ 4 MB of uploads per render frame **[est.]**.

**3.3 Dirty rects.** `updateTexture(h,x,y,w,h,bytes)` becomes `UploadOp(rect, payload)`. On GL that is `GL_UNPACK_ROW_LENGTH`/`ALIGNMENT` plus `glTexSubImage2D` of the rect. On the master copy it is a row-wise `arraycopy` at fold time. Sharing, not copying, the payload means a 1 MB texture updated 4 KB at a time costs 4 KB per update on every path.

**3.4 Independence from client world state [V].** The render hook needs no `WorldClient` (§2.1), and GL targets are keyed by `(session, deviceId)`, not by client TEs. A host in another dimension, or a display chunk the client never loaded, still renders, reads back and streams to guests; only the host's TESR needs the client TE. On loading a world, the server restores confirmed state and submits `Init(resources, pixels)` plus the replayed tail (§6), so the host sees the right picture as soon as the client TE appears.

**3.5 GL memory [derived].** Per T3 device the target is colour `RGBA8` 1 MB + depth 1 MB + packed readback target 0.5 MB + two PBOs 1 MB ≈ 3.5 MB, plus resources up to the tier VRAM cap. Because confirmed state can rebuild any target, the host may evict idle devices (no ops for N seconds) under a client-side cap and rebuild them on demand through the same `Init` path persistence uses **[I]**.

### 4. LAN guests

**4.1 Path.** The guest path is 13 §4.2 unchanged. Frames leave from the pool via `NetworkManager.scheduleOutboundPacket(FMLProxyPacket)` to an immutable viewer snapshot, size-checked before queuing. The host's local channel is excluded from the snapshot. The encoding is lossless 16×16 dirty tiles against the previous *confirmed* frame. Each encoded packet is shared by all guests at the same position in the stream. A guest that subscribes or falls behind gets a keyframe from confirmed pixels: always available, with no GPU round trip. Over-budget guests get 09's `CATCHUP`. Minecraft 1.7.10 has no protocol compression, so OpenGPU deflates itself. Guests need only the certified `glTexSubImage2D` path (02 §5): no FBO and no GLSL.

**4.2 Measured frame and delta sizes [measured].** `SP/gap21/bench/FrameBench.java`, results in `SP/gap21/bench/results-jre8.txt`. The content:

- UI frames are drawn with vanilla `ascii.png` glyphs and OpenOS `term.lua` text: a title bar (a gradient at T3), a side panel, a terminal and a status bar with a progress bar.
- 3D is a 32×32 low-poly heightmap with a sky gradient, either flat-shaded, textured with noisy 16×16 pixel-art, or textured with 4×4 Bayer dither, quantized to a 252-colour cube (index8) or RGB565.
- Deltas are a u16 count, u16 tile indices and raw tile bytes, deflated with `java.util.zip.Deflater`, timed on JRE 1.8.0_492 on the bench CPU (Core Ultra 9 285HX; ×1.8–2.4 on typical CPUs, 12 §1).

| Tier, content | Raw frame | Keyframe L6 | Delta (dirty tiles) | Delta bytes | Deflate time L1 (delta / key) |
|---|---|---|---|---|---|
| T1 UI clock + progress / typed char | 16 000 | 1 321 (12×) | 1–2 / 70 | 61–78 (L6) | < 0.01 / 0.04 ms |
| T1 UI scroll one line | 16 000 | 1 331 | 29 / 70 | 801 (L6) | 0.02 ms |
| T1 3D camera pan 1°, flat → tex + dither | 16 000 | 1 761–3 888 | 44–45 / 70 | 2 455–4 331 (L1) | 0.05–0.09 ms |
| T1 3D spinning cube, static camera | 16 000 | — | 3–4 / 70 | 147–329 (L1) | < 0.01 ms |
| T2 UI clock / typed char | 64 000 | 4 064 (16×) | 2 / 260 | 53–59 (L6) | < 0.01 / 0.2 ms |
| T2 UI scroll one line | 64 000 | 3 935 | 149 / 260 | 3 366 (L6), 4 917 (L1) | 0.14 ms |
| T2 3D camera pan, flat → tex + dither | 64 000 | 4 285–13 407 | 149–154 / 260 | 6 507–15 342 (L1) | 0.16–0.33 ms |
| T2 3D spinning cube | 64 000 | — | 7–8 / 260 | 258–466 (L1) | 0.01 ms |
| T3 UI clock / typed char | 512 000 | 13 752 (37×) | 2–4 / 1000 | 73–111 (L6) | < 0.01 / 0.9 ms |
| T3 UI scroll one line | 512 000 | 13 593 | 438 / 1000 | 10 330 (L6), 16 334 (L1) | 0.8 ms |
| T3 3D camera pan, flat → tex + dither | 512 000 | 21 921–108 280 | 546–561 / 1000 | 33 977–123 213 (L1) | 1.1–3.2 ms; key L1 1.3–3.8 ms, L6 5–19 ms |
| T3 3D spinning cube | 512 000 | — | 24 / 1000 | 847–2 096 (L1) | 0.02–0.04 ms |

The synthetic scenes compress better than 12 §1's gradient-plus-noise proxy (255 KB per T3 frame), which remains the pessimistic bound. A pan without a static sky is keyframe-sized: T3 L1 35–129 KB.

**4.3 Bandwidth per guest per watched display [derived from 4.2].** Rates are T1 @ 10 fps, T2 and T3 @ 20 fps.

| Content | T1 | T2 | T3 |
|---|---|---|---|
| UI, typical (clock, typing) | < 1 KB/s | ≈ 1.2 KB/s | 1.5–2.2 KB/s |
| UI, continuous scrolling | 8 KB/s | 67–98 KB/s (0.5–0.8 Mbit/s) | 207–327 KB/s (1.7–2.6 Mbit/s) |
| 3D, object moving, static camera | 1.5–3.3 KB/s | 5–9 KB/s | 17–42 KB/s |
| 3D, camera moving | 25–43 KB/s | 130–307 KB/s (1.0–2.5 Mbit/s) | 0.68–2.46 MB/s (5.4–19.7 Mbit/s); 12's noise bound 5.1 MB/s (41 Mbit/s) |

LAN capacities **[est.]**:

- Gigabit Ethernet carries ≈ 940 Mbit/s of TCP payload [derived: 1460/1538 framing].
- Fast Ethernet carries ≈ 94 Mbit/s.
- A 2×2 802.11ac link at 80 MHz (867 Mbit/s PHY) gives ≈ 300–450 Mbit/s of TCP on a good link. Practitioners quote 40–60 % of PHY, with one measured 330 Mbit/s iperf3 (HPE Airheads thread).
- 2.4 GHz 802.11n at 20 MHz (144 Mbit/s PHY) gives ≈ 50–80 Mbit/s near the access point and far less at range. All Wi-Fi guests share the airtime (arXiv 1702.03257 survey).

The worst realistic load is three guests each watching two T3 displays with a moving camera: 6 × 20 Mbit/s = 120 Mbit/s. That is trivial on wired LAN, comfortable on 5 GHz, and saturating on 2.4 GHz Wi-Fi. LAN therefore needs a per-guest cap far above 09's internet bucket (16 KB/tick): suggested default 4 MB/s per guest, configurable, with skip-and-`CATCHUP` beyond it.

**4.4 Host CPU [measured/derived].** Encoding happens once per frame per display, whatever the guest count. A T3 3D stream at L1 costs 1.1–4.4 ms per frame across two runs of the bench (sizes reproduce exactly, times vary by up to ≈ 25 %), i.e. 2–9 % of a bench-CPU core at 20 fps (≈ 4–21 % typical). At L6 it costs 5–19 ms (10–38 %). Rule: L1 for deltas above ≈ 8 KB raw and for keyframes of 3D content; L6 elsewhere. UI is negligible at every tier.

**4.5 Guests replaying the command stream instead.**

| | Host pixels (recommended) | Guest renders the commands on its own GPU |
|---|---|---|
| What the guest sees | exactly what Lua reads back (owner decision 3) | the guest driver's result; ≤ 2 LSB per draw (09 §3.3 GL tolerance), accumulating in canvas mode |
| Steady bandwidth | §4.3 | ≈ 100 B uniforms/frame; 4–40 KB/s for 3D with resident meshes (09 §3.4 [est.]) |
| Join / late joiner | one keyframe: 1–129 KB | every live resource (≥ 1 MB for 3D, decision 7) **plus** a pixel keyframe for canvas content, which only the host has |
| Guest requirements | 02 §5 baseline texture path | full OpenGPU GL backend, shader compilation on every guest, every guest's Angelica/driver mix |
| Implementation | 13's path + tile codec, both needed anyway | per-guest residency and generations, resync, divergence handling (≈ 3 w per 09 Q1) |

Command replay saves bandwidth that LAN does not need, and it breaks decision 3's "guests receive the host's pixels". **v1: pixels.** Command replay is kept only as the dedicated-server option (b).

### 5. Dedicated servers

| Option | Graphics behaviour | Readback | Effort **[est.]** | Complexity, risks |
|---|---|---|---|---|
| **(a) Unavailable** | `present` → `nil,"no renderer"`; `getCaps().renderer = "none"`; compute, `dispatch` and `readBuffer` fully work; viewers receive the persisted confirmed image as a `TILES` keyframe through the same pool path as LAN guests | `readPixels` returns the static confirmed image with its `frameId` | ≈ 0.5 w beyond the LAN path | none new |
| **(b) Display-only** | each viewer runs the GL backend on the replicated `CMDS`/`RESOURCE` stream | none (`readback = false`) | 5–8 w: residency per viewer, join replay, shader compiles on clients, per-viewer budgets | canvas mode has no keyframe source: needs a bounded canvas log since the last full clear, or a frame-mode-only rule; viewers diverge within tolerance; programs that read pixels break |
| **(c) One client renders and uploads** | a donor client executes and uploads pixels | yes, from the donor | 4–6 w plus open-ended operations work | C2S payloads must stay < 32 767 B (`C17PacketCustomPayload.java:37-39`), so a T3 3D frame is 2–4 packets and 0.7–2.5 MB/s **upstream** from a residential link; the donor controls Lua-visible pixels (trust); donor hand-over must move all state; latency adds RTT plus the donor's client fps |
| **(d) Software renderer** | a server-side `RenderHost` on the pool; the T2 3D frame ≈ 1.3–1.6 ms, T3 ≈ 4.8–5.6 ms on the bench CPU (12 §1) | yes | 8–12 w; the GLSL front end and JVM-bytecode back end are shared with compute (decision 4), the rasterizer and sampler are new | pixels differ from the host GL within tolerance; costs server CPU per watched frame |

**Recommendation: (a) for v1.** (d) is the later path if graphics on dedicated servers is ever wanted, because it preserves readback and reuses the guest pixel path unchanged. (b) is only for a display-only "kiosk" use. (c) is rejected on bandwidth and trust grounds.

**What the v1 protocol must already carry** so that (b) or (d) is an addition, not a redesign:

1. Every op crossing the `RenderHost` SPI is a self-contained, versioned **byte record**: the 09 §3.2 command stream plus `RESOURCE` upload records with `(handle, generation, rect, content hash)`. In single player these records travel by reference, but they are exactly what (b) would send and (d) would execute.
2. The SPI is **asynchronous** (`submit`, then `onReadback`), never call-and-return, so a pool-thread software host fits.
3. `getCaps()` carries `renderer` (`"host-gl"` / `"none"`, later `"viewer-gl"` / `"software"`) and `readback` (bool); frame statuses include `"no renderer"`, `"superseded"` and `"dropped"`.
4. Display packets carry a body type with `TILES` live and `CMDS`/`RESOURCE` ids reserved, plus `(displayId, generation, frameId)` (09 §3.4).
5. Persistence stores renderer-independent confirmed pixels plus the tail (§6), so a world moved from single player to a dedicated server keeps its last images, and (d) can resume canvas content from them.
6. The API documents that canvas content may be reset with `opengpu_reset(card, "canvas")` where a renderer cannot sustain it, which is (b)'s escape hatch.

### 6. Persistence under Option 2

**State model.** Per device the server holds:

- **confirmed state**: pixels of frame K (the last read-back), resource master copies *as of K*, and the handle table;
- **tail**: the ordered immutable ops after K.

On each readback of frame N the pool folds the tail's uploads up to N into the master copies and replaces the pixels. An upload arriving when the tail contains no frame folds immediately. The tail is bounded by ≤ 2 unconfirmed frames (09 §3.2) and by tier VRAM for queued uploads; beyond that, uploads return `busy` or take the `bitblt`-style pause (09 §3.2).

Masters must lag. If they were updated at submit time, replaying a tail frame that drew with version 1 of a texture later overwritten in the same tail would use version 2. Folding at readback keeps every replay exact up to GL tolerance.

**Save** (autosave, pause or stop) snapshots the confirmed state and the tail under the device monitor. The tail's arrays are shared, and masters changed since the last save are copied (≤ tier VRAM, ≈ 0.4–1 ms at 4 MB **[est.]**). The snapshot is then deflated and written off-thread to 09 §3.6's side file, hash-gated. OC's precedent applies twice:

- side files are written on a single-thread pool (`OC/common/SaveHandler.scala:71, 163-171`) and a load waits for a pending write of the same name (`:148-156`);
- the server waits for all such pools at `FMLServerStoppedEvent` (`OC/OpenComputers.scala:93-95`).

A save never waits for the GPU (§2.5).

**Load** restores the confirmed state. With a GL host it submits `Init(resources, pixels)` (an upload into the FBO) and then replays the tail under the new epoch, so a Lua program persisted between `present` and `waitFrame` receives `opengpu_frame(..., "done")` for the frames it was waiting on. This replaces 09 §3.6's `"dropped"`. Without a renderer the tail frames resolve as `"no renderer"` and the pixels stay as persisted. OC persists queued signals itself (`OC/server/machine/Machine.scala:884` save, `:777` load), so a frame confirmed before the save is never in the tail and is never signalled twice **[I]**.

**When the host never rendered a frame.** A new device's confirmed state is defined server-side as the cleared target (palette index 0 or black) with `frameId = 0`. A loaded device's is the persisted image. Readback-dependent calls therefore always have an exact answer without the GPU. `readPixels` returns the newest *confirmed* frame and its `frameId`, never blocks, and is never `pending`. A program that needs frame N waits for its signal. A render thread stalled in a loading screen or a resource reload only delays confirmation: Lua sees `busy`, never stale data mislabelled as new.

## Design implications for OpenGPU

1. Add a `RenderHost` SPI in common code (`NoRenderHost` default, `GlRenderHost` installed only by `ClientProxy` after a capability probe). Devices capture `RenderHosts.forServer(server)` and a per-server `session` once, on the server thread. No common class names a `net.minecraft.client.*`, `org.lwjgl.*` or `opengpu.client.*` type. Never use `@SideOnly(Side.SERVER)`, `getEffectiveSide()`, `getSide()` for logical side, or `MinecraftServer.getServer()` in client code.
2. Replace 12 §4's and 09 §3.1's "local-channel `TILES`" policy: the host receives nothing over the network for OpenGPU displays and is excluded from viewer snapshots. 09 §3.1's "lazy rendering" becomes "the host executes every device; laziness applies to readback coalescing and to guest encoding".
3. 09 §3.5 gains a fifth actor, the client render thread, with a `RenderTickEvent(START)` drain under `client.renderBudgetMs` and round-robin. The OpenGPU pool no longer rasterizes graphics: it folds readbacks, encodes for guests, signals, persists and runs compute.
4. Read back the newest frame per device per client frame through a two-PBO ring into a recycled server-owned `byte[]`, using `glGetTexImage(..., long)` (never `glReadPixels(..., long)`, absent from Angelica 2.2.21) and a polled `GL_TIMESTAMP` query as the fence. "Done" means confirmed. Use the `RGBA8`-packed fast readback path.
5. Treat Lua byte strings as transferred ownership. Keep master copies server-private and fold them at readback. Add a dev-build mutation check.
6. Persist confirmed state plus tail and replay on load (replaces 09 §3.6's `"dropped"`). Never wait on the GPU from the server thread. Wait for the side-file writer at `FMLServerStoppedEvent`.
7. LAN: per-guest cap ≈ 4 MB/s, L1 deflate for 3D, keyframes from confirmed pixels, 13's pool send path unchanged. Single-player pause and LAN need no special casing beyond §2.5.
8. Dedicated servers ship option (a) in v1, including streaming the static persisted image. Freeze the record format, caps fields, status strings and async SPI now (§5 list).
9. M0 must measure: per-job render-thread cost for UI and 3D frames, PBO map stalls on the owner's Intel GPU with and without Angelica, sustained OpenGPU fps against host fps, and a quit-to-title with two frames in flight per device (no hang, no leak, correct replay on reopen).

## Open questions for the owner

1. **Host frame-rate priority.** Is a default render-thread budget of ≈ 4 ms per client frame (≈ 24 % of a 60 fps frame) the right trade, so that many OpenGPU devices slow themselves (`busy`) rather than the game? Or should it scale with the host's fps?
2. **Superseded frames.** May frame-mode devices skip frames the host could not execute in time (status `"superseded"`), or must every presented frame be executed?
3. **Canvas mode on renderer-less hosts.** Accept that canvas content can be reset (`opengpu_reset(card,"canvas")`) on a future display-only dedicated mode, so v1 programs are written to tolerate it?
4. **Static images on dedicated servers.** Should option (a) stream the persisted last image to viewers (cheap, recommended), or show a fixed "no renderer" card?
5. **GPU memory cap.** Should the host evict idle devices' GL targets and rebuild them from confirmed state, and under what default cap (e.g., 256 MB)?
6. **LAN per-guest cap.** Is 4 MB/s a sensible default, or should it be unlimited on LAN and capped only for non-LAN remote players?

## Sources

Local, cited as `path:line` (read-only):

- Minecraft/Forge 1.7.10 (`MC/`):
  - `net/minecraft/client/Minecraft.java:866, 1008-1140, 1117, 1170-1177, 1658, 1691-1694, 2149-2153, 2164-2233, 2249-2306, 2769-2788`
  - `net/minecraft/client/renderer/EntityRenderer.java:1023-1029`
  - `net/minecraft/server/MinecraftServer.java:88, 164, 388-443, 445-556, 604, 636-641, 751, 938-941, 1168, 1272, 1643-1646`
  - `net/minecraft/server/integrated/IntegratedServer.java:104-125, 161-164, 226-256, 264-295`
  - `net/minecraft/client/multiplayer/PlayerControllerMP.java:311-318`
  - `net/minecraft/network/NetworkManager.java:120-130, 221-245, 274-277`
  - `net/minecraft/network/NetworkSystem.java:110-125`
  - `net/minecraft/network/play/server/S3FPacketCustomPayload.java:20-35`
  - `net/minecraft/network/play/client/C17PacketCustomPayload.java:37-39, 50-52`
  - `cpw/mods/fml/common/FMLCommonHandler.java:142-160, 333-341`
  - `cpw/mods/fml/client/FMLClientHandler.java:458-461, 648-656`
  - `cpw/mods/fml/server/FMLServerHandler.java:142-145`
  - `cpw/mods/fml/relauncher/FMLLaunchHandler.java:100-103`
  - `cpw/mods/fml/common/asm/transformers/SideTransformer.java:33-116`
  - `cpw/mods/fml/common/ProxyInjector.java:32-81`
  - `cpw/mods/fml/common/FMLModContainer.java:512`
  - `cpw/mods/fml/common/network/internal/FMLProxyPacket.java:25-56, 125-135`
  - `cpw/mods/fml/common/network/handshake/NetworkDispatcher.java:190-210, 265-297, 385-407, 436`
- OpenComputers-GTNH (`OC/`):
  - `common/component/TextBuffer.scala:94-96, 155-169, 425-445, 615-640, 735-760`
  - `common/ComponentTracker.scala:19-58`; `client/ComponentTracker.scala:6`; `server/ComponentTracker.scala:6`
  - `server/machine/Machine.scala:335-369, 584-585, 960-993, 1008-1016`
  - `server/machine/ArgumentsImpl.scala:179-190`
  - `util/ExtendedLuaState.scala:106-109`
  - `common/item/Tablet.scala:476-484`
  - `client/Sound.scala:47-50`; `src/main/java/li/cil/oc/util/SideTracker.java:7-13` (outside `OC/`)
  - `common/SaveHandler.scala:40-75, 140-171, 241-246`
  - `util/ThreadPoolFactory.scala:49-58, 82-96`
  - `OpenComputers.scala:60-66, 92-96`
- `C:\Users\astro\Downloads\OC-JNLua\native\src\jnlua.c:918-953`; `src\main\java\li\cil\repack\com\naef\jnlua\LuaState.java:1233-1236`.
- `C:\Users\astro\Downloads\OC-LuaJIT\src\main\java\li\cil\repack\com\naef\jnlua\LuaStateLuaJIT.java:195`; `native\build-native.sh:10`.
- `SP/Angelica-2.2.21/src/main/java/com/gtnewhorizons/angelica/zoom/Zoom.java:115-119`; grep of the same tree for `runGameLoop`/`RenderTickEvent`; `src/mixin/java/com/gtnewhorizons/angelica/mixins/early/angelica/MixinMinecraft.java`, `MixinMinecraft_FrameHook.java`, `MixinMinecraft_IconifyGuard.java`, `MixinMinecraft_SkipEndFrameFlush.java`, `debug/MixinMinecraft_FPSCap.java`; `src/main/java/com/gtnewhorizons/angelica/rendering/FpsReducer.java`; `src/main/java/me/jellysquid/mods/sodium/client/gui/SodiumGameOptions.java:253-262`; `glsm/.../redirect/GLSMRedirector.java:431-437, 543-544`.
- `C:\Games\Minecraft\instances\Main\minecraft\mods\angelica-2.2.21.jar` → `com/gtnewhorizons/angelica/glsm/GLStateManager.class` (`javap -p`: `glReadPixels` only with `ByteBuffer`/`FloatBuffer`/`IntBuffer`; `glGetTexImage(IIIIJ)V`, `glTexSubImage2D(...J)V`, `glMapBuffer`, `glQueryCounter`, `glGetQueryObjecti` present).
- `C:\Games\Minecraft\instances\Main\minecraft\mods\hodgepodge-2.7.216.jar` → `com/mitchej123/hodgepodge/mixins/early/memory/MixinMinecraft_ShutdownHook.class` (`javap -v`; extracted to `SP/gap21/hp`).
- `C:\Games\Minecraft\libraries\org\lwjgl\lwjgl\lwjgl\2.9.4-nightly-20150209\lwjgl-2.9.4-nightly-20150209.jar` → `org.lwjgl.opengl.GL11.glTexSubImage2D`, `org.lwjgl.BufferChecks.checkBuffer` (`javap -c`).
- Bench written for this report: `SP/gap21/bench/FrameBench.java` (compiled with `javac --release 8`, run on `C:\Games\Minecraft\java\eclipse_temurin_jre8.0.492+9`), output `SP/gap21/bench/results-jre8.txt`. Inputs: `assets/minecraft/textures/font/ascii.png` from `C:\Games\Minecraft\libraries\com\mojang\minecraft\1.7.10\minecraft-1.7.10-client.jar` and OpenOS `term.lua` from the OC checkout.
- Research reports: 02 (C3, §4, §5), 08-B §5.5, 09 §3.1–3.6 and Q1, 12 §1, §4, §6, 13 §1.3–1.6, §4.

Web (accessed 2026-10-08), for Wi-Fi throughput estimates only:

- Impact of IEEE 802.11n/ac PHY/MAC High Throughput Enhancements over Transport/Application Layer Protocols — A Survey, https://arxiv.org/pdf/1702.03257
- HPE Airheads, "PHY rate vs throughput" and "802.11ac theoretical TCP throughput of a layer-2 bridge" threads, https://airheads.hpe.com/discussion/phy-rate-vs-throughput-1 and https://airheads.hpe.com/discussion/80211ac-theoretical-tcp-thoughput-of-a-layer-2-bridge-configuration

## Verification notes

Adversarial verification, 2026-10-08. Each key claim was re-checked against the primary source; the bench was rebuilt (`javac --release 8`) and re-run on `eclipse_temurin_jre8.0.492+9`. Changes made to this report:

1. **§1.4 (i), packets before the world exists (K1, modified).** The old text said nothing is drained "before the client world exists". In fact, while `theWorld == null`, `runTick` drains `myNetworkManager` through its `pendingConnection` branch (`MC/net/minecraft/client/Minecraft.java:2149-2153`). The pause and `theWorld != null` guard on `updateController` (`:1691-1694`), the 20 Hz cadence, `skipRenderWorld` (`:866` is its only writer) and the `RenderTickEvent` position (`:1063-1070`) were confirmed.
2. **§2.1, Angelica and `runGameLoop` (new finding).** "Has no `runGameLoop` mixin (grep)" was wrong. Angelica 2.2.21 (tag checkout and installed jar) injects into `runGameLoop` in `MixinMinecraft` (`onRenderTickEnd` AFTER, `updateCameraAndRender`, `Thread.yield`, `Display.sync`, RETURN), `MixinMinecraft_FrameHook` (HEAD), `MixinMinecraft_FPSCap`, `MixinMinecraft_IconifyGuard` (redirects `updateCameraAndRender` and skips it when minimized) and `MixinMinecraft_SkipEndFrameFlush`. None of them removes `onRenderTickStart/End`, so the conclusion stands. Added the FPS reducer (`FpsReducer.java`; defaults `enabled = false`, `unfocusedFpsLimit = 10`, `idleFpsLimit = 30`, `SodiumGameOptions.java:253-262`).
3. **§2.1 (c), §2.2, Summary 5, implication 4, the readback call (new finding, contradicted 20).** `glReadPixels` into a PBO fails on the owner's instance. `javap -p` of `GLStateManager` in `mods/angelica-2.2.21.jar` lists only the `ByteBuffer`/`FloatBuffer`/`IntBuffer` overloads, so the redirected LWJGL2 `glReadPixels(..., long)` throws `NoSuchMethodError` (20 §3). `glGetTexImage(IIIIJ)V` exists, so the text now uses it. The RGBA8/BGRA packing advice is kept.
4. **§2.2, Summary 5, the completion fence (K9, modified).** "Mapping the PBO one frame later is the only non-blocking fence" was wrong. GLSM 2.2.21 maps `glQueryCounter` and `glGetQueryObjecti` (`GLSMRedirector.java:431-437, 543-544`), so a `GL_TIMESTAMP` query polled with `GL_QUERY_RESULT_AVAILABLE` is a non-blocking completion test. 20 §3 already adopts it. `GLSync` would pass through on the GL backends (02 line 69: unmapped calls pass straight to the driver), so it is banned by 08-B §5.5 rather than unusable. A blind map one frame later can stall when the GPU lags more than a frame **[I]**.
5. **§2.3, latency and throughput (K9, modified; new finding).** The old text ignored OC's wake-up path. A machine sleeping in `pullSignal` is resumed only by `Machine.update` on the next server tick (`OC/server/machine/Machine.scala:584-585`), then rescheduled after `executionDelay` (`:960-968`; 12 ms, 00 and 04 §3). The previous figures (2 / 1.5 client frames, "below ~15 fps", "the client fps, not the server tick, is the clock") are replaced by a derived 2 / (1.5 client frames + ≈ 37 ms): about 32 / 23 / 18 / 11 fps at 60 / 30 / 20 / 10 client fps, a threshold near 25 client fps, and two clocks. Also added: vanilla `pauseOnLostFocus` (`EntityRenderer.java:1023-1029`) pauses single player on alt-tab.
6. **§2.5, quit ordering (K2/K10 nuance).** The busy-wait exits when `serverStopped = true` is set at `MinecraftServer.java:543`. That is before `handleServerStopped` (`:551`), so the OC-style writer wait at `FMLServerStoppedEvent` runs after the client has resumed. OC's `saving` map lives in a global `object` (`SaveHandler.scala:34, 74`), which keeps a quick reopen safe; OpenGPU must keep its pending-write map the same way. K2 itself was confirmed: `loadWorld` busy-waits (`Minecraft.java:2266-2279`), `stopServer` saves and then posts `WorldEvent.Unload` (`MinecraftServer.java:388-418`), and Hodgepodge's only `loadWorld` injection is at the `handleClientWorldClosing` INVOKE (javap of the jar's `MixinMinecraft_ShutdownHook`; refmap has no other `loadWorld` target).
7. **§1.2, OC cross-side touches (K5, modified).** `Tablet`'s handler is not guarded by `!isDedicatedServer`. It is a `ClientTickEvent` handler with an `IntegratedServer` pattern match (`Tablet.scala:476-484`). A third pause-related touch was added (`client/Sound.scala:47-50`). The note also records that OC's `TextBuffer` proxy choice uses `getEffectiveSide()` through `SideTracker` (`SideTracker.java:7-13`).
8. **§3.2, the staging copy (K6, modified).** Direct buffers are confirmed required: `GL11.glTexSubImage2D(...ByteBuffer)` → `BufferChecks.checkBuffer` → `checkDirect` (gated by `LWJGLUtil.CHECKS`), then `MemoryUtil.getAddress` → `nglTexSubImage2D`, all from `javap -c` of the installed LWJGL 2.9.4 jar. The copy is not forced onto the render thread, though; it could run on the pool. The `NewByteArray`-per-call chain was confirmed (`jnlua.c:930-953`, `ExtendedLuaState.scala:106-109`, `ArgumentsImpl.scala:179-185`). `LuaJITArchitecture` extends `NativeLuaArchitecture`, so OC-LuaJIT takes the same path.
9. **§4.4, deflate time (K8).** Sizes reproduced byte for byte on the re-run. L1 times were 1.30–4.39 ms against 1.11–3.75 ms in the original run, so the range and CPU shares were widened.

Confirmed without change: K3 (`IntegratedServer.java:104-118, 248, 292-295`; `Minecraft.java:1117`; `Machine.scala:335-337, 977-992`), K4 (`FMLCommonHandler.java:142-160`, `FMLClientHandler.java:458-461`, `SideTransformer.java:33-116`, `ProxyInjector.java:58-59`, `MinecraftServer.java:88, 164, 938-941`, `Minecraft.java:2777-2780`), K7 (`FMLProxyPacket.java:38-42, 132-135`, `S3FPacketCustomPayload.java:25-29`, `NetworkDispatcher.java:291-293, 430-436`; the local pipeline has no codec, `NetworkSystem.java:110-125`, `NetworkManager.java:321-333`), K10 (`SaveHandler.scala:71, 148-156, 163-171`, `OpenComputers.scala:93-95`, `ThreadPoolFactory.scala:89-96`) and K11 (`C17PacketCustomPayload.java:37-39`, which throws at ≥ 32 767).
