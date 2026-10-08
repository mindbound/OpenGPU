# 08 — Proposal A: server-authoritative software renderer with streamed framebuffers

Architect A, 2026-10-08. Constraint: *all* rendering (2D, 3D with shaders compiled to JVM bytecode, compute) runs on the server JVM in software; clients receive compressed framebuffer deltas and only blit a texture. Citations are to the research reports (`[NN §x]` = `docs/research/NN-*.md`, section x) and, where the report cites it, to the underlying source path. Numbers marked **[est.]** are the reports' estimates, **[measured]** are their measurements.

## 1. Overview

The thesis of this design is that *one* authoritative pixel buffer per display, produced by a deterministic Java renderer and shipped as dirty-tile deltas, is the simplest system that satisfies all three success criteria at once: it needs no client GL beyond a textured quad (the one path every report calls Angelica-safe, [02 §5], [03 §1]), it makes `readPixels`/compute readback and persistence trivially consistent (the server copy *is* the truth, [01 impl. 6], [07 §1 C]), and it runs unchanged under ocelot-brain because the engine never touches Minecraft ([05 §3]). Its known wall is bandwidth for full-motion 3D ([07 §1 A], [03 §4]); the whole of §6 is about pushing that wall as far as software allows and being explicit about where it stays.

Hardware model, as Lua sees it: a tiered **card** (`opengpu`, `Slot.Card`, `Visibility.Neighbors`, mirroring `DriverGraphicsCard`, [00 §2]) owns resources (buffers, textures, programs, kernels, pipelines, fonts) addressed by integer handles, and is bound to a **pixel screen** block (`opengpu_screen`, tile entity, `Visibility.Network`, multi-block mergeable like OC screens, [00 §3]). A frame is one `submit(cmdbuf)` byte string plus one `present()`; the card renders on an OpenGPU-owned pool, the display tile ships the result once per tick.

```
SERVER JVM                                                          CLIENT JVM
+--------------------- OC worker thread (1 of computer.threads=4) --------------------+
| Lua program --component.invoke--> card @Callback(direct)                            |
|   enc.lua builds byte string        consumeCallBudget -> decode+validate -> enqueue |
|   present()                         seal frame, FrameJob -> pool, return frameId    |
+-------------------------------------------|-----------------------------------------+
                                            v
+--------------------- OpenGPU ForkJoinPool (max(1, cores-1), server-wide) ----------+
| :core   execute commands: 2D fast paths | tile-parallel rasterizer | compute       |
| :stream hash 16x16 tiles vs last encoded -> delta ops (RAW/FILL/COPY) -> Deflate    |
|         publish EncodedFrame to the display's queue; context.signal("gpu_frame")    |
+-------------------------------------------|-----------------------------------------+
                                            v
+--------------------- server thread (tick) -----------------------------------------+
| display tile update(): viewers = chunk watchers ^ distance ^ token bucket          |
|   send KEYFRAME / DELTA / CATCHUP on FML channel "OpenGPU" (1 packet/display/tick)  |
|   drain input packets -> reach check -> computer.checked_signal("touch", x, y, ..)  |
|   persistence: metadata in NBT, resources+last frame in side file (async, hashed)   |
+-------------------------------------------|-----------------------------------------+
                                            | TCP (<= 256 KB typical, chunked > 1 MB)
+--------------------- client main thread ---v---------------------------------------+
| packet handler: payload -> byte[] -> per-display decode queue (release ByteBuf)     |
| decode worker: inflate, apply tiles into back int[] ARGB -> AtomicReference swap    |
| render thread: once per frame glTexSubImage2D(dirty rows) -> TESR quad / GUI quad   |
| GUI/in-world click -> C2S INPUT(u16 px) ; keys -> C2S KEY -> keyboard.keyDown msgs  |
+-------------------------------------------------------------------------------------+

TEST / EMULATOR PATH (no Minecraft, no GL)
Lua test program -> ocelot-brain Machine -> :ocelot-harness OpenGpuEntity (Scala 2.13)
      |                                            | same :core + :stream jars
      v                                            v
 JUnit asserts <---- FrameSink(framebuffer, frameId, signals) ----> :viewer (Swing) / golden PNGs
```

## 2. Module layout and interfaces

Gradle subprojects (GTNHGradle on the root only, Angelica's `:glsm` precedent; project classes are bundled unrelocated, external libraries relocated, [05 §4]). Package root `io.github.mindbound.opengpu` (`modGroup`, `modId = opengpu`, config file named `opengpu-engine.cfg` to dodge the stale `OpenGPU.cfg` on case-insensitive NTFS, [07 risk 11]). All modules emit Java 8 bytecode via jabel (`--release 8` enforced, [05 §1]).

| Module | Depends on | Contents |
|---|---|---|
| `:core` | nothing | `strictfp` math; `Framebuffer{int[] argb; float[] depth}`; resource pools (16-bit slot + 16-bit generation handles, zero invalid, VRAM accounting, [06 §1]); command codec + validator; 2D draw-list builder with blit/fill fast paths; tile binner + edge-function rasterizer; sampler/blend; shader lexer/parser/typer/IR + tree-walking interpreter; compute dispatcher; `WorkBudget` |
| `:jit` | `:core`, shaded+relocated ASM 9 | GLSL-ES-subset IR -> JVM bytecode, variant cache, per-card `GeneratedClassLoader`; optional at runtime (core falls back to the interpreter) |
| `:stream` | `:core` | tile hashing, delta/keyframe/catch-up encoders, palette quantizer with ordered dither, lossy 4x4 block codec, Deflate wrapper, decoder (used by client and viewer) |
| root (the mod) | `:core`, `:jit`, `:stream`, OC `:api` 1.12.64 (compileOnly), Angelica `:api` 2.2.21 (compileOnly, optional) | `common` (blocks/items/registration in `init`), `server` (driver, card component, display tile, viewer manager, side-file store), `network` (channel, packets), `client` (packet handler, decode worker, texture cache, TESR, GUI, input) |
| `:ocelot-harness` | `:core`, `:stream`, vendored ocelot-brain | `OpenGpuEntity extends Entity with Environment` + Lua fixtures, 5.2/5.3/5.4/LuaJIT matrix |
| `:viewer` | `:core`, `:stream` | Swing frame player, golden-image runner, shader playground |

Public interfaces (the contracts the adapters program against):

```java
// :core
public final class Device {                                   // one per card, host-neutral
  public Device(DeviceLimits limits, ForkJoinPool pool, ShaderBackend jitOrNull);
  public int create(ResourceKind kind, ResourceDesc desc) throws LimitException; // slot|gen handle
  public void upload(int handle, int offset, byte[] data, int off, int len);
  public ValidatedBatch validate(byte[] cmd) throws CommandException;   // cheap, worker thread
  public FrameJob present(List<ValidatedBatch> batches, PresentFlags flags, FrameSink sink);
  public Framebuffer lastPresented();                         // immutable snapshot (readback/persist)
  public void free(int handle);  public void reset(ResetReason r);
  public void save(DataOutput out) / load(DataInput in);      // resources + last frame, deflated
}
public interface FrameSink   { void frameReady(FrameJob job, Framebuffer fb); void frameFailed(FrameJob job, String why); }
public interface ShaderBackend { Executable compile(ProgramIR ir, PipelineKey key) throws CompileException; }
public interface WorkBudget  { int triangleCap(); long fragmentCap(); long computeOpCap(); int timeSliceMs(); }
// :stream
public final class Lineage   { Encoded encodeNext(Framebuffer fb); Encoded catchUp(long sinceSeq); Encoded keyframe(); }  // one per (display, LOD, lossy)
public final class FrameDecoder { void apply(Encoded e, int[] argbBack, int[] dirtyRows); }
// mod-side
interface DisplayHost { int width(); int height(); DisplayMode mode(); Collection<Viewer> viewers(); void queue(Encoded e); }
```

The versioned little-endian command stream is the stable contract between Lua, the core, the viewer and the tests ([06 §7]); every other module boundary is a Java interface with no MC/OC/ocelot types in `:core`, `:jit`, `:stream` ([05 impl. 1]).

## 3. Lua-facing API

Design rules, all taken from the boundary analysis: bulk data only as top-level byte strings (`checkByteArray`, zero decoding, [00 §5], [04 §1]); tables only for small descriptors (<100 entries, copied out with key normalisation, never retained, [04 impl. 2]); integer handles (`Integer` results, exact on every runtime, no `Value` userdata required, [04 §6]); every direct callback charges `consumeCallBudget` *before* mutating so the `LimitReached` retry is idempotent ([00 §1]); direct callbacks never block ([07 §3]).

**Card component `opengpu`** (tiers 1-3; `HostAware` for Case/Server/Robot/Tablet):

| Group | Callbacks | Notes |
|---|---|---|
| Binding | `bind(address[, reset])` (non-direct), `getScreen()`, `getResolution()`, `setResolution(w,h)`, `maxResolution()`, `getMode()/setMode("index8"|"rgb565")`, `setPalette(i, rgb)`, `setPaletteRaw(bytes768)` | resolution <= min(card tier, screen tier, multi-block size) |
| Resources | `createBuffer(bytes)`, `createTexture(w,h,fmt)`, `createProgram(vsSrc, fsSrc)`, `createKernel(src)`, `createPipeline(program, state)`, `createFont(texture, cellW, cellH, firstCodepoint)`, `upload(h, offset, bytes)`, `free(h)`, `freeAll()`, `handles()`, `getInfo(h)`, `totalMemory()`, `freeMemory()` | all return `handle` or `nil, err`; `upload` cost scales with bytes (bitblt pattern) |
| Frame | `submit(bytes)`, `present([flags])` -> `frameId` or `nil,"busy"`, `readPixels(x,y,w,h[,fmt])` -> bytes (last *presented* frame), `stats()` | 2 calls per frame |
| Compute | `dispatch(kernel, nx, ny, nz, bindings)` -> `jobId`, `readBuffer(h, offset, len)` -> bytes or `nil,"pending"` | readback capped at 64 KB per call, documented against `computer.freeMemory()` ([04 §3]) |
| Misc | `getLimits()`, `library(name)` -> Lua source of the shipped helper (`enc`, `draw`, `term`) | library delivered as a byte string; a loot floppy is optional sugar |

**Screen component `opengpu_screen`**: `getResolution()`, `getAspectRatio()`, `setPrecise(b)/isPrecise()`, `turnOn()/turnOff()/isOn()`, `getViewers()`. Keyboards attach on any face, the front included; the front is keyboard-only (cables do not connect there), exactly OC's `Screen.scala:63-67` rule (15 §1); our server packet handler forwards `keyboard.keyDown/keyUp/clipboard` node messages to the display's neighbours exactly as `TextBuffer.ServerProxy.sendToKeyboards` does, so OC's own `Keyboard` component performs the 8-block reach check and raises `key_down` ([03 §2]).

**Signals**: `gpu_frame(card, frameId)` when the frame is encoded and queued for the next tick's send (the vsync analogue: at most one wire frame per tick); `gpu_reset(card, reason)` on load without a side file, on `computer.started/stopped`, or on card removal; `gpu_error(card, frameId, msg)` when a frame exceeds its work cap; `touch/drag/drop/scroll(screen, x, y, button|delta, player)` with **0-based integer pixel** coordinates (doubles when precise), names kept so OpenOS `event` code works unchanged.

**Command buffer format** (`"OGPU"` + u8 version + u8 flags + u16 reserved, then opcodes, `0xFF` END). Opcodes are u8 with fixed-size little-endian operands: `CLEAR(u32 argb, u8 flags)`, `VIEWPORT/SCISSOR(i16 x4)`, `FILL_RECT(i16 x4, u32)`, `LINE(i16 x4, u32)`, `BLIT(u16 tex, i16 sx,sy,sw,sh,dx,dy, u8 flags)` (fast path when unscaled), `BLIT_EX(... dw,dh, u16 angle)`, `COPY(i16 x4, i16 dx,dy)` (also emitted on the wire as a scroll op), `TEXT(u16 font, i16 x,y, u32 argb, u16 len, bytes)`, `BIND_PIPELINE(u16)`, `BIND_VERTEX(u16 buf, u8 stride, u8 layout)`, `BIND_INDEX(u16)`, `BIND_TEXTURE(u8 slot, u16 tex)`, `UNIFORMS_F32(u8 n, f32 x n)`, `UNIFORMS_FX16(u8 n, i32 x n)` (16.16 fixed, for runtimes without `string.pack`), `DRAW(u32 first, u32 count)`, `DRAW_INDEXED(u32, u32)`, `DISPATCH(u16 kernel, u16 nx,ny,nz)`. Vertex layouts are fixed-point by default (`i16` position/uv, `u8x4` colour, [04 §6]); `f32` layouts exist for 5.3/5.4 programs.

**Budget/encoding strategy.** A frame is 2 direct calls, so even a T1 machine at the jar-default budget of 0.5 is nowhere near the call wall ([07 §2]). Costs, modelled on `GraphicsCard.setCosts`/`bitblt` ([00 §3], [01 §1]): `submit` = `1/64,1/128,1/256` + `bytes / (64,128,256 KB)` per tier; `present` = `1/2, 1/4, 1/8` (2/8/32 presents per tick on the installed `[1,2,4]`, 1/4/12 on defaults — the real cap is "frames in flight <= 2 -> busy"); `upload` = `bytes / (64,128,256 KB)` with the two-phase throw-then-`context.pause(overrun/budget/20)` trick from `GraphicsCard.scala:221-253`, so a 1 MB texture on T3 costs one tick of stall, never a crash; `dispatch` = `items x staticOps / (2,8,32 M)`; `readPixels/readBuffer` = `bytes / (16,32,64 KB)`. The Lua side encodes with `string.pack` when present and arithmetic + fixed-arity `string.char` otherwise, pieces concatenated per 1-4 KB — the pattern OC-LuaJIT measured as JIT-friendly (3.7x faster than PUC 5.3) versus the `char(unpack(t))` pattern that is 4x slower ([04 §5], [07 §2]).

**Example program** (OpenOS; runs on Lua 5.3/5.4, OC Lua 5.2 and OC-LuaJIT: no `string.pack`, no `//`, no bitwise operators, handles are small integers):

```lua
local component, computer = require("component"), require("computer")
local gpu    = component.opengpu
local screen = component.list("opengpu_screen")()
local char, floor, concat = string.char, math.floor, table.concat

-- little-endian encoders, arithmetic only (string.pack fast path lives in gpu.library("enc"))
local function u8(v)  return char(floor(v) % 256) end
local function i16(v) v = floor(v) % 65536; return char(v % 256, floor(v / 256)) end
local function u32(v) v = floor(v) % 4294967296
  local b0 = v % 256; v = (v - b0) / 256; local b1 = v % 256; v = (v - b1) / 256
  return char(b0, b1, v % 256, floor(v / 256)) end
local OP = { CLEAR = 1, FILL = 16, BLIT = 17, TEXT = 19, END = 255 }
local function frame(ops) return "OGPU" .. char(1, 0, 0, 0) .. concat(ops) .. u8(OP.END) end

assert(gpu.bind(screen))
assert(gpu.setResolution(320, 200))
gpu.setMode("index8")                                   -- T2 default; palette 0..255 programmable

-- 16x16 RGBA8 sprite built once and uploaded once (stays resident on the server)
local px = {}
for y = 0, 15 do for x = 0, 15 do
  local c = ((x + y) % 2 == 0) and 0xFF or 0x40
  px[#px + 1] = char(c, 0x80, 0xFF - c, 0xFF)           -- r g b a
end end
local sprite = assert(gpu.createTexture(16, 16, "rgba8"))
assert(gpu.upload(sprite, 0, concat(px)))
local font = assert(gpu.createFont(0, 8, 16, 0))       -- 0 = built-in font texture

local function waitFrame(id)                            -- yield, never spin (computer.timeout = 5 s)
  local deadline = computer.uptime() + 0.5
  while true do
    local e, a, b, c = computer.pullSignal(deadline - computer.uptime())
    if e == "gpu_frame" and b == id then return true end
    if e == "gpu_reset" then return false end
    if e == "touch" then print("touch at pixel", b, c) end
    if computer.uptime() >= deadline then return false end
  end
end

local t = 0
while true do
  local x, y = 152 + floor(120 * math.cos(t)), 92 + floor(70 * math.sin(t))
  local cmd = frame {
    u8(OP.CLEAR), u32(0xFF102030), u8(0),
    u8(OP.FILL),  i16(8), i16(8), i16(304), i16(24), u32(0xFF204060),
    u8(OP.TEXT),  i16(font), i16(12), i16(12), u32(0xFFFFFFFF), i16(13), "OpenGPU  demo",
    u8(OP.BLIT),  i16(sprite), i16(0), i16(0), i16(16), i16(16), i16(x), i16(y), u8(0),
  }
  assert(gpu.submit(cmd))
  local id, err = gpu.present()
  if id then waitFrame(id) else os.sleep(0) end         -- "busy": two frames already in flight
  t = t + 0.08
end
```

One `submit` (~90 bytes) and one `present` per frame; the loop paces itself on `gpu_frame`, which arrives once per tick at most, so the program runs at 20 fps on every runtime without ever hitting the budget. A 3D frame looks the same with `BIND_*`, `UNIFORMS_*` and `DRAW_INDEXED` in place of the 2D ops and ~100 bytes of uniforms per frame, because meshes and textures are resident ([07 §2]).

## 4. Shader language and compiler

**Language**: a GLSL ES 1.00 subset with Appendix-A restrictions, exactly as [06 §4] recommends, because it is small, well documented, familiar to the OC audience, and keeps the door open to passing the same source to a GL backend later. Types `void bool int float vec2..4 bvec2..4 ivec2..4 mat2..4 sampler2D`; qualifiers `attribute uniform varying const`; precision qualifiers parsed and ignored (everything is `float`); functions allowed but inlined at IR level and recursion rejected; `for` loops only, with constant init/bound/step and the index not assigned in the body; constant-index-only on arrays/vectors/matrices; built-ins `gl_Position`, `gl_FragCoord` (top-left origin, documented), `gl_FrontFacing`, `gl_FragColor`, `discard`; `texture2D` on bound slots. Compute kernels use the same grammar with a `kernel` entry point, `global_id()` and typed `in`/`out` buffers: read-only inputs and exactly one write-only output indexed by the global id, no `local` memory, no barriers, no atomics — deterministic regardless of scheduling, reductions as two dispatches ([06 §5]).

**Pipeline**: hand-written lexer + Pratt parser + type checker -> typed AST -> scalarised SSA-like IR (every `vecN` becomes N scalar temporaries, so generated code allocates nothing per pixel, [06 §3]). Two executors share the IR:

1. `:core` tree-walking interpreter — the reference semantics, the ocelot/test executor, and the runtime fallback when `:jit` is absent or a compile fails. Cost 100-500 ns per work-item for a ~30-op kernel **[est.]** ([07 §1]), acceptable for M2 and for small 2D shaders.
2. `:jit` ASM backend — shaded **and relocated** ASM 9 (mandatory: `LaunchClassLoader` loads `org.objectweb.asm.` parent-first and the launcher's ASM is 5.0.3 on Java 8 but 9.x under lwjgl3ify, [05 §1]); emits `V1_8` classes with `COMPUTE_FRAMES` and a `getCommonSuperClass` override that loads nothing; defines them through a private `ClassLoader` subclass whose parent is the mod loader (OC-Wasm `Compiler.java:190-204` precedent; no `Unsafe`, no `Lookup.defineClass`, [05 §1]); one loader per card so programs unload with the card. One generated class per (program x pipeline state) so every per-pixel call site stays monomorphic — the property the benchmark actually demands ([06 §4 verification C4]): static uniform fields + `setUniforms(float[])`, `vertex(float[] in, int base, float[] out)`, and a `rasterizeTile(...)` whose loop inlines the fragment body with blend/depth/texture-format decisions baked in at generation time. Classes are `strictfp`; every transcendental built-in routes to `StrictMath` or own polynomials, never `Math` intrinsics ([01 impl. 6], [06 §6]). Variant cache keyed by `hash(source, state)`.

**Safety bounds** (all static, so the inner loop carries no counters): loop trip counts are compile-time constants and their product per invocation is capped; static instruction count per invocation <= 4 k (fragment), 16 k (vertex), 64 k (kernel item); <= 16 texture fetches per fragment; <= 64 uniform floats; source <= 32 KB; generated method <= 6 KB bytecode (split the fragment body into a `private static` helper above that, reject above `HugeMethodLimit`, [06 §4]); emitted opcodes restricted to arithmetic, locals, `float[]/int[]` access and `StrictMath` calls — no `invokedynamic`, reflection or object allocation ([07 risk 9]); `CheckClassAdapter` + JVM verification in tests, a grammar-driven fuzzer with interpreter-vs-JIT differential oracle ([05 §4]); compile rate limited to 4 programs/s per card with `context.pause` on abuse and a per-card class cap. Because per-fragment and per-item cost is static, `dispatch` and `DRAW` work estimates are known at validate time; the only runtime guard is a per-tile abort flag checked once per tile row when a frame exceeds its fragment cap or time slice, which yields `gpu_error` rather than a wedged worker (the 5 s Lua watchdog cannot interrupt Java, [00 §1]).

A GLSL 330 emitter from the same IR is an explicit extension point (the IR is already scalarised and typed) but is *not* part of this design: Proposal A ships no client shaders.

## 5. Server/client responsibilities, networking, persistence, threading, compatibility

**Responsibilities.** Server: everything that produces or reads pixels (render, compute, readback), resource ownership, caps, viewer selection, encoding, persistence. Client: decode, texture upload, draw one quad (world and GUI), collect input. The client holds *no* authoritative state; a desync is always repaired by requesting a keyframe.

**Networking.** One event-driven FML channel `"OpenGPU"` (<= 20 chars) with raw `DataInput/Output` payloads and OC's compression-flag byte, heap `ByteBuf`s released by hand ([03 §3]). Server->client, one packet per display per tick: `KEYFRAME(display, seq, w, h, mode, palette, tiles[])`, `DELTA(display, seq, prevSeq, ops[])` where ops are `TILE_RAW(idx, 256 B index8 | 512 B rgb565)`, `TILE_RLE`, `TILE_FILL(idx, colour)`, `COPY_RECT(x,y,w,h,dx,dy)` (scroll, harvested from the command stream so a terminal scroll costs ~12 bytes plus one row of tiles, [03 §4]), `PALETTE`, `TILE_BLOCK(idx, 8 x 4x4 DXT1-style blocks)` for lossy lineages; `CATCHUP` = delta over the union of tiles changed since the viewer's last acknowledged seq; `RESET`. Whole payload deflated at `BEST_SPEED` like `CompressedPacketBuilder` ([00 §3]). Client->server (<= 1 KB each, always under the 32,766-byte cap, [03 §3]): `SUBSCRIBE(display)` on TE load, retried every 100 ticks until a keyframe arrives (the `TextBufferInit` pattern), `ACK(display, seq)` every 20 ticks, `INPUT(display, kind, u16 x, u16 y, button/delta)`, `KEY(display, char, code, down)`. The server validates every C2S packet: `isPlayerWatchingChunk`, reach <= 8 blocks for input, `isFinite`, size ([00 §3], [03 §2]). Payloads are kept under a 256 KB soft limit; a keyframe over 1 MB is split by tile rows into several packets so the design holds on Hodgepodge-free servers with the 2,097,050-byte Forge cap, not only with Hodgepodge's 256 MiB ([00 §5], [03 §3]).

**Viewer model.** The display tile keeps a viewer set recomputed each tick: players watching the chunk (the only effective OC filter today, [00 §3]) within `displayRange` (default 64 blocks, hysteresis 8), optionally in the front hemisphere. Each viewer has a token bucket (default 16 KB/tick, burst 512 KB, server-wide aggregate 2 MB/s; the OC2 `StreamingLoadBalancer` shape, [01 §7]) and a `(lod, lossy)` lineage: full-res lossless within 24 blocks, half-res beyond, lossy block codec whenever the bucket runs dry two ticks in a row, back to lossless after 40 ticks of headroom. Lineages are capped at four per display (2 LOD x lossy flag); encoding happens once per lineage, not per viewer. A viewer that was skipped receives a `CATCHUP` computed from a ring of the last 8 per-frame tile-hash arrays (260 x 8 bytes per frame at 320x200, trivial); older than the ring -> keyframe.

**Persistence.** Card item NBT holds only metadata: handle table (slot, generation, kind, size, hash), bound screen address, resolution, mode, palette (<= 1 KB). Resource contents, shader *source* (bytecode is re-derived) and the last presented framebuffer go deflated into an OpenGPU side-file store at `<world>/opengpu/state/<dim>/<cx>.<cz>/<address>` written asynchronously on a single writer thread — our own implementation of the `SaveHandler` idea rather than a `MethodHandle` into `li.cil.oc.common.SaveHandler`, which is not API ([00 §5], [07 risk 5]). `save()` runs under the device monitor (consistent snapshot) and schedules a write only when the content hash changed, so description packets and Waila saves cost a hash compare instead of the 0.1 s machine pause OC's `TextBuffer.save` imposes ([00 §5]). On `load()`, a missing or corrupt side file invalidates every handle and queues `gpu_reset(card, "load")` from the card's first `update()`; in-flight frames at save time are dropped and the program's `waitFrame` timeout handles the missing `gpu_frame`. OC-LuaJIT interaction: the Lua heap holds only integers and byte strings, so its full-state serializer (which restores suspended coroutines exactly, [04 §6]) never sees an OpenGPU object; a render loop paused between `submit` and `present` resumes holding valid handles because handle generations are persisted. LuaJ (no persistence, no `__gc`) gets `gpu_reset` on every reboot and works at "correctness only" level ([04 §4]).

**Threading.** Four actors, one coarse monitor per device (OC's own model for `Hologram` and `TextBuffer.ServerProxy`, [00 §5]):
- *OC worker threads* (shared pool of 4): `submit` = `consumeCallBudget` -> `Device.validate` (0.05-0.2 ms for 60 KB **[est.]**, [07 §1 B]) -> append under `device.synchronized`; `present` seals the pending batches into a `FrameJob` and submits it to the pool; returns in <= 0.2 ms. No rendering ever runs here, so a 10 ms 3D frame never steals one of the four threads every computer shares ([07 §3]).
- *OpenGPU pool*: a daemon `ForkJoinPool` of `max(1, min(cores-1, config.renderThreads))`, server-wide, separate from OC's `Computer` pool (`InternetCard` precedent, [07 §3]). A `FrameJob` executes 2D fast paths serially, bins triangles into 32x32 tiles and renders tiles as subtasks, runs compute as 4096-item subtasks, then encodes every active lineage and publishes to the display queue. Frames in flight per display <= 2; per-display time slice (default 20 ms) and fragment cap abort via the tile flag. Completion signal: the card keeps the `Context` captured by the last callback and calls `context.signal("gpu_frame", id)` from the pool thread (`Machine.signal` is thread-safe and simply returns `false` when the queue is full, [07 §3]); only `Node` operations are forbidden off-thread ([00 §1]), and we do none there.
- *Server thread*: the display tile's `update()` drains the encoded queue, picks recipients, sends through the FML channel (channel `send*` is not thread-safe, [03 §6]), processes input packets, and runs persistence bookkeeping: 5-20 µs per display per tick **[est.]**, 1-2 ms for 100 displays ([07 §3]).
- *Client*: handlers run on the main thread in 1.7.10 ([03 §6]), so the handler only copies the payload and hands it to a single daemon decode thread; the decoded `int[]` is double-buffered and swapped atomically; the render thread uploads dirty rows once per frame.

**Angelica/Iris measures** — the baseline recipe from [02 §5] verbatim: CPU-side `int[]` ARGB, `glTexImage2D(GL_RGBA8)` once, `glTexSubImage2D(GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV)` of dirty rows gated by a frame counter (the Iris shadow pass may invoke a TESR twice), `GL_NEAREST`, `GL_TEXTURE_MAX_LEVEL=0`, `GL_CLAMP_TO_EDGE`; TESR modelled on `ScreenRenderer` (origin only, bezel inset, aspect fit, yaw/pitch, back-face reject, distance fade) with `glPushAttrib` of specific bits, `setLightmapTextureCoords(240,240)`, `glDisable(GL_LIGHTING)`, one Tessellator quad, `glPopAttrib`; no display lists, no `glBegin`, no GLSL, no FBO, no `GLSync`; chassis via an ISBRH annotated `@ThreadSafeISBRH(perThread=false)` with no state and no `Tessellator.draw()` (so the display is skipped in the shadow pass under the default `shadowSkipInMeshTileEntities`); `getRenderBoundingBox()` returns the **maximum multi-block extent (8x6) from the very first call** and `getMaxRenderDistanceSquared()` is constant per class, because Celeritas classifies per class on first sight and caches the AABB per instance ([02 §3], [03 §1]); never exclude `opengpu.client.*` from transformers; dev runs with `-Dangelica.unmappedGL=STRICT`, `pinnedGLVersion=33` once, Iris pack on. This is byte-for-byte the pattern OC-GTNH's `DynamicFontRenderer` already ships under Angelica ([02 §3]).

**Java 8 + LWJGL2 and Java 17-21 + lwjgl3ify.** Java 8 bytecode everywhere (jabel); only `GL11`/`GL12`/`OpenGlHelper` calls in the client (redirected by GLSM on both paths, renamed to `org.lwjglx` by lwjgl3ify, [02 §4]); capability probing through one final `GlCaps` class reading LWJGL2's `GLContext.getCapabilities()` plus `OpenGlHelper` flags on the render thread — the same code serves Java 8 and lwjgl3ify, whose `org.lwjglx` shim returns a process-wide snapshot that Angelica's GLSM reads too; no `glGetString` branching, never `@Lwjgl3Aware`, only fields present in LWJGL 2.9.4, the shim and LWJGL 3.4.3 ([03 §5], 14 §3); generated classes defined by a child `ClassLoader` whose parent is `LaunchClassLoader` or `RfbSystemClassLoader` as appropriate ([05 §1]); test matrix `runClient` (Java 8, LWJGL 2.9.4, Angelica 2.2.21) and `runClient21` (lwjgl3ify 3.0.33) ([05 §2]).

## 6. Tiers, caps, abuse protection, expected performance

Tiers follow [07 §5] (integer fractions of OC's 1280x800 T3 text raster, 16:10); the internal render target is always `int[]` ARGB + `float[]` depth, and the display mode is applied at present time by a 15-bit LUT quantizer (rebuilt on palette change, 0.3-1 ns/px **[est.]**) with ordered dithering for shaded content.

| Tier | Resolution | Wire mode | VRAM | Frame caps (tris / fragments / compute ops) | FPS cap 2D / 3D | `submit` max | Time slice |
|---|---|---|---|---|---|---|---|
| T1 | 160x100 | index8 | 256 KB | 20 k / 0.5 M / 2 M | 10 / 10 | 64 KB, 4 per present | 8 ms |
| T2 | 320x200 | index8 (rgb565 opt.) | 1 MB | 100 k / 2 M / 8 M | 20 / 10 | 128 KB, 4 per present | 15 ms |
| T3 | 640x400 | rgb565 (index8 opt.) | 4 MB | 300 k / 6 M / 32 M | 20 / 10 | 256 KB, 8 per present | 20 ms |

Further caps, all config keys: per-card shader source/compile rate/class count (§4); textures power-of-two <= 1024; `readPixels/readBuffer` <= 64 KB per call; frames in flight <= 2; active displays per server (default 64, beyond which new `bind`s fail with `"too many active displays"`); pool threads; per-viewer bucket and server aggregate; `displayRange`. Every cap failure is a Lua error or `nil, reason`, never a server stall; repeat offenders (over-cap uploads, compile storms) get `context.pause` exactly like `bitblt` ([00 §3]). NaN/Inf vertices are rejected at validate time ([06 §6]).

**Server CPU** (JDK 8 single core numbers from [06 §3], **[measured ±20%]**: ~340-420 Mpix/s flat, ~200-250 Gouraud, ~110-130 textured; 7 M small tris/s; Deflate ~100 MB/s; delta detect 0.5-1 ns/px, [07 §1]):

| Workload per frame | 320x200 | 640x400 |
|---|---|---|
| 2D UI (5 % tiles dirty): render + hash + deflate dirty tiles | 0.3 + 0.06 + 0.06 ≈ **0.4 ms** | 1 + 0.25 + 0.25 ≈ **1.5 ms** |
| 3D, 1-2 k textured tris, 2x overdraw: raster + hash + deflate full 16-bit frame | 1.5-3 + 0.06 + 1.3 ≈ **3-4.5 ms** | 6-11 + 0.25 + 5.1 ≈ **12-16 ms** |
| compute, 64 k items x 30 ops, JIT / interpreter | 0.15-0.65 ms / 6-30 ms | same |

So a 2D display at 20 fps costs ~1-3 % of one core; a 320x200 3D display at 10 fps ~4 %; a 640x400 3D display at 10 fps ~15 %. Tile parallelism gives ~5.5x at 320x200 and ~8.6x at 640x400 with 16 threads ([06 §3]) — it cuts latency, not total CPU, and the pool is shared, so the honest capacity statement is: **~30 concurrent 320x200 3D displays or ~8 at 640x400 per dedicated core**, and essentially unlimited 2D displays. Encoding is per lineage, so viewers add only `5-20 µs` of server-thread send cost each.

**Bandwidth per viewer** (delta + Deflate, [03 §4], [07 §1]; wire caps are policy, not a measurement):

| Content | 320x200 | 640x400 |
|---|---|---|
| 2D UI, ~5 % dirty tiles, 20 fps | **~20 KB/s**; full repaint 15-25 KB one-off | **~80 KB/s**; repaint 60-100 KB |
| scrolling terminal via `COPY_RECT`, 20 fps | ~30 KB/s | ~120 KB/s |
| 3D, rgb565 lossless, 10 fps | 0.5-0.85 MB/s | 2-3.5 MB/s |
| 3D, index8 dithered, 10 fps | 0.25-0.4 MB/s | 1-1.6 MB/s |
| 3D, lossy 4x4 block lineage (0.5 B/px raw -> ~26 KB/frame), 10 fps | **~260 KB/s**; half-res LOD ~65 KB/s | ~1 MB/s; half-res ~260 KB/s |

Against the 10-50 KB/s per viewer envelope that keeps OpenGPU from dominating a 1.7.10 connection ([03 §3]), 2D is comfortable and 3D is not: the default governor delivers 320x200 3D lossless at 10 fps to the one or two nearest viewers on a LAN, lossy/half-res to everyone else, and 640x400 3D is a LAN-only or single-viewer feature on this path. That is the wall; §8 states it plainly. Latency Lua-call-to-pixels is ~35-60 ms on a LAN (render + encode + 0-50 ms tick wait + decode + client frame), +20-80 ms over the internet ([07 §1]).

## 7. Success criteria, headless testing, milestones

**(a) Lua 5.3/5.4 and LuaJIT 5.2-compat.** Nothing in the protocol needs anything above Lua 5.2: byte strings in, byte strings and small integers out; `checkInteger` accepts the `Double`s that 5.2/LuaJIT/LuaJ deliver ([04 §1]); handles are `Integer`s < 2^31; the shipped `enc` library selects `string.pack` by feature probe and otherwise uses the arithmetic encoder; no `__gc`, no `Value` userdata, no bytecode; `gpu_reset` semantics cover the no-persistence runtimes. OC-LuaJIT's `machine.lua` keeps the zero-results retry and inherits budget/pause handling unchanged ([00 open q. 1], [01 open q.]), so budget-stall pacing behaves identically.

**(b) Modular architecture.** Six modules with Java-interface seams (§2); the command stream is the versioned contract, so a future client-GL `Backend` (Architecture B/C's path) can consume the same bytes without touching `:core`; the stream module is independent of both the renderer and Minecraft; the shader IR is backend-neutral.

**(c) Angelica.** The client uses only the one path the Angelica analysis certifies as production-safe and that OC-GTNH itself ships (§5); no GLSL, no FBO, no display lists, no sync objects; thread-safe ISBRH; stable render bounds; frame-keyed uploads.

**Headless testing under ocelot-brain** ([05 §3-4]): `:core` JUnit 5 golden-image tests (PNG, tolerance 0 for integer paths, <= 1 LSB for shaded), JMH on fill rate/shader dispatch/encode, compiler fuzzing with the interpreter-vs-JIT oracle, stream round-trip tests (encode -> decode -> pixel-equal, including catch-up and keyframe splitting); `:ocelot-harness` boots a `Case` with CPU/RAM/EEPROM and an `OpenGpuEntity` + `OpenGpuScreenEntity`, pins `NativeLua53Architecture` (the GTNH default), 5.2, 5.4 and OC-LuaJIT's architecture explicitly, runs Lua fixtures from an EEPROM BIOS, and asserts on the core framebuffer, `gpu_frame` ordering, budget stalls (`callBudgets` set to both `[0.5,1,1.5]` and `[1,2,4]`), `machine.lastError == nil`, and a `Workspace.save/load` round trip with live handles. CI runs `:core` tests on Java 8 and 17; `runClient` and `runClient21` with Angelica are the final gate.

**Milestones** (effort is focused solo-developer weeks, honest ranges):

| Milestone | Scope | Exit criteria | Effort |
|---|---|---|---|
| **M0 vertical slice** | `:core` framebuffer + `CLEAR/FILL/BLIT`; command codec + `enc.lua`; OC card + screen tile + `bind/submit/present`; `:stream` delta/keyframe; client decode + TESR; `:ocelot-harness` skeleton; viewer | 320x200 moving sprite in-game on Java 8 + Angelica and Java 21/lwjgl3ify; measured per-call cost, Lua encode cost on 5.3/5.4/LuaJIT, bytes/frame, server-thread µs/tick ([07 §6 M0]) | 3-4 weeks |
| M1 2D complete | text/fonts (licence check on OC's unscii font or ship Unifont, [06 §2]), palettes + quantizer, blend/scissor/copy ops, input with pixel coords, GUI, multi-block merge, persistence side files, caps/config, token buckets + catch-up | OpenOS-style terminal library running on the pixel screen; persistence round trip; 2D bandwidth within table | 3-4 weeks |
| M2 3D fixed pipeline + shader frontend | tile-parallel rasterizer, vertex/index buffers, textures, depth, perspective-correct, GLSL-ES-subset frontend + interpreter, pipelines | textured spinning mesh at 320x200/10 fps using < 5 % of a core; golden images bit-identical on JDK 8/17 | 4-6 weeks |
| M3 JIT + compute | ASM backend, variant cache, loader isolation, static bounds, fuzzer; `dispatch/readBuffer` | >= 5x over interpreter; <= 10 ns/item on the 64 k kernel; verifier clean on 10 k fuzzed programs | 4-6 weeks |
| M4 governor + polish | lossy lineage, LOD, server-wide budget, `stats()`, docs, example programs, release | 3D viewer tiers behave as §6; release on Java 8 and 21 | 3-4 weeks |

Total ~17-24 weeks; M0 alone answers whether the numbers in §6 hold on the user's hardware.

## 8. Risks and honest weaknesses

1. **3D bandwidth is the structural limit.** 0.5-0.85 MB/s per viewer for lossless 320x200 at 10 fps and 2-3.5 MB/s at 640x400 ([07 §1]) exceed the per-viewer envelope by 10-70x; the governor degrades gracefully (lossy, half-res, frame skipping) but cannot make server-rendered full-motion 3D an internet-multiplayer feature. The deflate ratios behind these numbers are themselves estimates ([07 verification C6]); M0 must measure them before any tier is finalised.
2. **Server CPU scales with displays, not viewers** — good — but a public GTNH server with dozens of 3D displays spends real cores (§6); the active-display ceiling and time slices are the only defence, and they are visible to players as `nil, "too many active displays"` and `gpu_error`.
3. **Latency** of 35-60 ms LAN / up to 140 ms internet makes this a UI/visualisation GPU, not a twitch-game GPU; the one-tick wire cadence is inherent to the design.
4. **Async readback** (`nil, "pending"`, `readPixels` of the *last presented* frame) is a programming-model cost that an in-process emulator would not have; it is the price of never blocking an OC worker.
5. **Own side-file store** duplicates OC's `SaveHandler` logic because that class is not API ([00 §5]); world-backup tools that only copy `opencomputers/` will miss `opengpu/`, so the mod must document it and tolerate a missing directory (`gpu_reset`).
6. **Celeritas' per-class bounds classification** forces a fixed 8x6-block AABB from the first instance ([02 §3]); if a larger multi-block size is ever wanted, it is a config constant read before the first TE spawns, not a runtime change.
7. **Keyboard path** needs our own GUI and key packets because the display is not an `api.internal.TextBuffer`; OpenOS `term` will not drive the pixel screen until the M1 terminal library exists, and stock GPUs cannot `bind` to it.
8. **Determinism promises** cost performance: `StrictMath` for transcendental built-ins is slower than `Math` intrinsics; the design accepts that for golden-image reproducibility across JDK 8/17/21.
9. **Signal delivery** can drop under a full queue (installed `maxSignalQueueSize=256`, [07 §3]); programs must treat a missing `gpu_frame` as a timeout, which the shipped library does but hand-written loops may not.
10. **No hardware acceleration path** is included; the IR and command stream are designed to admit one, but adding it later is a second renderer with a conformance suite (Architecture C's cost, [07 §1]), and judges should weigh this design as the floor every alternative must still ship as its fallback.

## Verification notes

### Amendments after gap-fill (2026-10-08)

- **Keyboard attachment (from 15 §1.10).** §3's "Keyboards attach to any face but the front" was the inverse half of `Screen.scala:63-67`: a screen offers its node on its five non-front faces to anything and on the front only to an adjacent OC keyboard, so keyboards attach on any face and the front is keyboard-only. Corrected in place.
- **Capability probing (from 14 §3).** §5's "`glGetString(GL_VERSION)` behind a `GlCaps` interface because the capability APIs differ by LWJGL major" is superseded: `GLContext.getCapabilities()` plus `OpenGlHelper` flags work on both legs through lwjgl3ify's `org.lwjglx` shim; no per-LWJGL implementations, no `glGetString` branching, never `@Lwjgl3Aware`, read only fields present in LWJGL 2.9.4, the shim and LWJGL 3.4.3. Corrected in place.
