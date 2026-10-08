# 08 — Proposal B: client-side hardware rendering from a replicated command stream

Architect B · 2026-10-08 · Starting constraint: the server validates, stores and replicates resources and per-frame command streams; every viewing client renders them with real OpenGL under Angelica/Iris; readback and compute still work. Citations are to the research reports in this directory (`NN §x`) and, where they add a line number not in the reports, to the local checkouts.

## 1. Overview

**Thesis.** Pixels are the wrong unit to replicate. A 320×200/16-bit 3D frame costs 50–85 KB after deflate, 1.0–1.7 MB/s per viewer at 20 FPS (07 §1, Architecture A), 10–50× what OC's own screens ever burst and "not network-feasible beyond LAN" (07 Summary 1, 5). The same frame as *commands* against resident resources is 0.2–2 KB, 4–40 KB/s per viewer (07 §1, Architecture B): the traffic class of an OC text screen. Proposal B therefore makes the **command stream the product**: Lua encodes one binary command buffer per frame, the server validates it, charges OC's call budget and replicates it to subscribed viewers, and each client executes it on its GPU through Angelica's GLSM (02 §1) into an FBO that a TESR draws as one textured quad. The server never rasterizes for display.

**What the server still computes.** Compute kernels and any pixels a Lua program reads back cannot be delegated to clients. Both run on a server-side **software reference renderer** (the engine core of 06 §3, §5), on demand only: compute always, readback only for targets explicitly marked readable. The reference renderer has a third job unique to this architecture, **log compaction** for incrementally drawn canvases, so late joiners can be synchronized without replaying every command ever issued (§5.2).

**What this buys and costs (numbers from 07 §1 and 03 §4):**

| Metric, 640×400 3D scene (1–2k textured triangles, 20 FPS), per display | A: server raster + pixel stream | **B: command replication** |
|---|---|---|
| Server CPU per frame | 6–11 ms raster + 5 ms deflate | 0.05–0.2 ms validation |
| Bandwidth per viewer | 4–7 MB/s | 4–40 KB/s (resident resources) |
| Client cost | 0.1–0.8 ms inflate + 1 MB upload | 1–2k triangles (free), 2 MB VRAM FBO |
| Readback | exact, free (it is the frame) | software re-render on demand, 6–11 ms, ≤1 LSB from the viewer's picture |
| Angelica exposure | one `glTexSubImage2D` + quad (lowest) | GLSL 330 core, FBO, VAO, program restore (highest; §5.5) |
| Late joiner | one keyframe (≤ 340 KB) | resource set (≤ VRAM cap) + last frame or compacted canvas |

### 1.1 Component and data-flow diagram

```
 Lua program (OC Lua 5.3 / 5.4 / OC-LuaJIT 5.2-compat / LuaJ)
   | component.invoke("opengpu", "submit"|"present"|"createBuffer"|...)   -- direct callbacks, OC worker thread
   v
 [opengpu-oc]  GpuCard (ManagedEnvironment, Visibility.Neighbors)  --  validate, consumeCallBudget BEFORE mutate,
   |            copy byte[], enqueue; <= 0.2 ms per call; never touches node network or world
   |-----------------------------------------------------------------.
   v                                                                  v
 [opengpu-core]  ResourceStore (pools, 32-bit slot|gen handles,     [OpenGPU pool, max(1,cores/2)]
   |             VRAM accounting), CommandBuffer (validated),        compute dispatch (JVM bytecode),
   |             ShaderCompiler (GLSL ES 1.00 subset -> IR)          readback renders (software raster),
   |                                                                  canvas log compaction, deflate
   v
 [opengpu-stream]  Replicator  -- server tick: per display, one packet = resource deltas + frame(s);
   |               per-viewer token bucket; keyframe on subscribe; chunk-watcher AND distance filter
   v  FML event channel "OpenGPU", Deflater(BEST_SPEED), heap ByteBuf, <= 2,097,050 B per packet
 Client packet handler (main thread: readBytes + release only) -> decode worker -> ClientDisplayState
   |                                                                 (resources, pending frames, GL object cache)
   v
 Render thread, RenderTickEvent(START) [FMLCommonHandler.java:335]: GlBackend executes pending frames
   |   into one FBO per visible display (VAO/VBO, GLSL "#version 330 core", exact program/FBO restore)
   |   -- or SoftwareClientBackend (same :core rasterizer) + glTexSubImage2D when GL < 3.0 / no FBO
   v
 DisplayRenderer (TESR, origin only): one textured quad of the FBO colour texture; GUI draws 1:1
   v
 Angelica GLSM -> GL 3.3+ core context (LWJGL2 on Java 8 / lwjgl3ify on Java 17-21 / SDL-GPU opt-in)

 Test path (no Minecraft, no GL):
 Lua program -> ocelot-brain Machine -> [opengpu-ocelot] OpenGpuEntity -> same :core + :stream
   -> recorded packet stream -> :stream decoder + :core SoftwareBackend ("virtual client")
   -> golden PNG compare against the server-side reference render of the same stream
```

## 2. Module layout and interfaces

Gradle subprojects on the GTNHGradle root (05 §4 layout; Angelica's `:glsm` is the precedent for `shadowImplementation(project(...))` keeping project packages unrelocated). All Java 8 bytecode via `enableModernJavaSyntax = jabel` (`gradle.properties:47`), which also enforces the Java 8 API surface (05 §1). Change `modId` to `opengpu`, `modGroup` to `io.github.mindbound.opengpu`, and use a config file name that cannot collide with the stale OCLights2-shaped `config/OpenGPU.cfg` (07 Risk 11).

| Module | Depends on | Packages / contents |
|---|---|---|
| `:core` | nothing | `opengpu.core.handle` (slot/generation pools), `.resource` (buffers, textures, programs, pipelines, targets, VRAM accounting), `.cmd` (binary command codec, validator, `CommandBuffer`), `.raster` (tile-binned edge-function software rasterizer, 06 §3), `.shader` (lexer, Pratt parser, type checker, `ProgramIR`, tree-walking interpreter), `.shader.glsl` (IR → GLSL 330 core emitter), `.compute` (work-item dispatcher), `.budget` (cost tables), `.canvas` (command log + compaction). `strictfp` math, `StrictMath` only (01 impl. 6, 06 §6). |
| `:jit` | `:core`, ASM 9 (shaded **and relocated** at the root, 05 §1: `org.objectweb.asm.` is parent-first in `LaunchClassLoader` and differs between Java 8 and lwjgl3ify) | IR → `V1_8` bytecode, one class per (program × pipeline state) so per-pixel call sites stay monomorphic (06 §4), per-program child `ClassLoader` as OC-Wasm does, variant cache by hash, generated methods < 8000 bytes. Optional at runtime; core falls back to the interpreter. |
| `:stream` | `:core` | `Replicator` (server: per-display outbox, resource deltas, frame coalescing, keyframes), `Subscriber` + `TokenBucket`, `FramePacket` codec; `StreamDecoder` + `ClientResourceStore` (client, also used by the virtual client in tests). |
| root (`opengpu` mod) | `:core`, `:jit`, `:stream`; `compileOnly` OC `1.12.64-GTNH:api` (≥ 1.12.58 because of `TextBuffer.dropFile`, 00 §3), Angelica `2.2.21:api` (optional) | `opengpu.server.component.{GpuCard, DisplayComponent}`, `opengpu.server.persist` (side-file store), `opengpu.common.{block, tile, item, net}`, `opengpu.client.render.{GlBackend, SoftwareClientBackend, DisplayRenderer, GlCaps}`, `opengpu.client.{gui, input}`. Only `opengpu.client.render` touches GL. |
| `:ocelot-harness` | `:core`, `:stream`, vendored ocelot-brain jar (Scala 2.13, JDK 17) | `OpenGpuEntity extends Entity with Environment` delegating to the same `GpuDevice` (05 §3). |
| `:viewer` | `:core`, `:stream` (Swing; later LWJGL3) | Plays recorded streams through the software backend; shader playground. |

**Public interfaces (the contracts the judges should score):**

```java
// :core — host-neutral. The OC adapter and the ocelot adapter are the only callers.
public final class GpuDevice {                       // one per card; all methods thread-safe (one monitor, as Hologram does)
  Handle create(ResourceDesc d, byte[] init);        // buffer/texture/program/pipeline/target; throws CapExceeded
  void   update(Handle h, int offset, byte[] data);  // sub-range / dirty-rect upload
  void   free(Handle h); void freeAll();
  Submission submit(int display, byte[] cmd);        // validate against ResourceStore + Limits; returns byte cost
  long   present(int display, int flags);            // frame id; coalesces to <= 1 flushed frame per tick
  long   dispatch(Handle kernel, int nx, int ny, int nz, byte[] bindings);   // job id, runs on the pool
  byte[] readBuffer(Handle h, int off, int len);     // null while pending
  byte[] readPixels(Handle target, int x, int y, int w, int h, int fmt);
}
public interface Backend { void execute(CommandBuffer cb, ResourceView rs, RenderTarget rt); }   // SoftwareBackend (core), GlBackend (client)
public interface FrameSink { void onFrame(int display, long frameId, CommandBuffer cb, ResourceDelta delta); }  // Replicator or local present
public interface IrEmitter<T> { T emit(ProgramIR ir, PipelineState ps, Limits l); }   // GlslEmitter -> String, JvmEmitter -> Class<?>
public interface Budget { boolean tryConsume(double cost); void pause(double seconds); }   // OC: context.consumeCallBudget / context.pause
public interface Clock { long tick(); }                                               // server tick for coalescing; test clock in ocelot
```

The command stream format (versioned header, little-endian, `u8 opcode` + fixed-size operands, handles only) is the stable contract between `:core`, `:stream`, both backends, the viewer and the tests (06 §7).

## 3. Lua-facing API

Two components. The **card** (`opengpu`, `Slot.Card`, `Visibility.Neighbors`, `createEnvironment` returns `null` client-side, registered in `init` — 00 Design implication 1) owns resources and frames. The **display** block (`opengpu_display`, `Visibility.Network`) owns resolution, mode, viewers and input; a card binds to it like `gpu.bind` (non-direct). Every drawing/resource call is `direct = true`, calls `consumeCallBudget` **before** mutating, and is idempotent across the `LimitReachedException` retry (00 §1, 04 §2).

### 3.1 Resources (handles are `Integer`, < 2^31, zero invalid; 32-bit `slot | generation`)

| Callback | Notes |
|---|---|
| `createBuffer(bytes: string \| size: number [, usage]) -> h` | vertex/index/storage; resident on server and every viewer |
| `updateBuffer(h, offset, bytes)` | sub-range; cost ∝ bytes |
| `createTexture(w, h, format [, bytes]) -> h` | formats `rgba8`, `r8` (palette index), `rgb565`; power-of-two only in v1 |
| `updateTexture(h, x, y, w, h, bytes)` | dirty-rect; clients receive only the rect |
| `createProgram(vertexSrc, fragmentSrc) -> h \| nil, err` | compiled by the server frontend; GLSL 330 emitted for clients, IR kept for readback |
| `createKernel(src) -> h \| nil, err` | compute entry point `kernel`, compiled to JVM bytecode on the server only |
| `createPipeline(desc: table) -> h` | immutable: program, vertex layout, blend, depth, cull, target format (06 §1 WebGPU/sokol shape); small table, copied out with key normalisation (04 impl. 2) |
| `createTarget(w, h, flags) -> h` | offscreen render target; flag `readable` makes it server-rendered |
| `free(h)`, `freeAll()`, `handles() -> table`, `vramTotal()`, `vramFree()` | explicit lifetime; all handles reset on `computer.stopped/started` like `GraphicsCard.onMessage` (04 §6) |

### 3.2 Frames, display, input

| Callback / signal | Notes |
|---|---|
| `submit(bytes) -> ok \| nil, "queue full"` | appends validated commands to the current frame; cost = `bytes / 256 KiB` budget units |
| `present([flags]) -> frameId` | closes the frame; cost 0.5 (= `limit 2`), so T1 default budget allows 1/tick, T3 installed 8/tick, each overrun stalls one tick (07 §2) |
| `bind(displayAddress)`, `getResolution()`, `setResolution(w,h)`, `maxResolution()` | non-direct where they touch the node network |
| `setMode("frame" \| "canvas")` | frame: each present is self-contained (auto-clear); canvas: FBO persists, server keeps a bounded command log (§5.2) |
| `setReadback(target \| 0, enabled)` | opt-in server-side software rendering of that target |
| `readPixels(target, x, y, w, h, fmt) -> bytes \| nil, "pending" \| nil, "not readable"` | chunked by caller; bounded by `computer.freeMemory()` (04 §3) |
| `dispatch(kernel, nx, ny, nz, bindings) -> jobId`, `readBuffer(h, off, len) -> bytes \| nil, "pending"` | compute; results via non-blocking poll |
| signal `gpu_frame(address, frameId)` | emitted when the Replicator has flushed the frame (server tick); coalesced to one pending per display (07 §3) |
| signal `gpu_reset(address)` | all handles invalid (load without side file, LuaJ, cap reset) |
| signals `touch/drag/drop/scroll(address, x, y, button [, player])` | **pixel** coordinates (0-based floats when `setPrecise(true)`), same names/order as OC so `event` code keeps working (03 §2); keyboard goes through OC keyboards adjacent to the display, forwarded as `keyboard.keyDown` node messages exactly as `TextBuffer.ServerProxy` does (00 §3) |

### 3.3 Command buffer encoding and call counts

Header `"OGPU" u8 version`; then opcodes with fixed operands (`u8 op`, little-endian):

```
00 END            10 CLEAR rgba:u32 depth:u16 flags:u8     11 VIEWPORT x,y,w,h:u16   12 SCISSOR x,y,w,h:u16
13 TARGET h:u32   20 PIPELINE h:u32   21 VBUF slot:u8 h:u32 off:u32   22 IBUF h:u32   23 TEX unit:u8 h:u32
24 UNIFORM_FX loc:u8 n:u8 (i32 16.16)*n     25 UNIFORM_F32 loc:u8 n:u8 (f32)*n
30 DRAW first:u32 count:u32   31 DRAW_INDEXED first:u32 count:u32 base:i32
40 RECT x,y,w,h:i16 rgba:u32   41 BLIT tex:u32 sx,sy,sw,sh:u16 dx,dy,dw,dh:i16 flags:u8   42 TEXT font:u32 x,y:i16 rgba:u32 len:u16 bytes
43 LINE x0,y0,x1,y1:i16 rgba:u32   44 COPY x,y,w,h,dx,dy:i16
```

`UNIFORM_FX` (16.16 fixed point) exists so that programs on Lua 5.2/LuaJIT/LuaJ, which have no `string.pack`, never need float encoding (04 §6 recommends fixed point as the default); the decoder converts to `float` on both backends. A pure-Lua helper library (`opengpu/cmd.lua`) selects `string.pack` on 5.3/5.4 and arithmetic `string.char` elsewhere, uses fixed-arity `string.char(b1..b4)` and `table.concat` per 1–4 KB (JIT-friendly; the naive `char(unpack(t))` pattern is 4× slower on LuaJIT, 04 §5), and contains no `//`, bitwise operators or `bit32` in hot loops. **Per frame: 2 direct calls** (`submit`, `present`), or 1 if `present(bytes)` is used; a 1,000-triangle scene with resident buffers is ~100 bytes of uniforms (07 §2). Against the installed `callBudgets=[1,2,4]` and the jar default `[0.5,1,1.5]`, a frame costs 0.5 + bytes/256 KiB, so even a T1 machine on defaults sustains one frame per tick, and the 20 Hz tick, not the budget, is the frame cap.

### 3.4 Example program (runs on 5.3, 5.4, OC-LuaJIT and LuaJ: no `string.pack`, no `//`, no bitwise ops, integer handles)

```lua
local component, computer = require("component"), require("computer")
local gpu = component.opengpu
local char, floor, concat = string.char, math.floor, table.concat

local function u8(v) return char(v % 256) end
local function u16(v) v = floor(v) % 65536; return char(v % 256, floor(v / 256)) end
local function i16(v) if v < 0 then v = v + 65536 end; return u16(v) end
local function u32(v) v = floor(v) % 4294967296
  local lo = v % 65536; return u16(lo) .. u16(floor(v / 65536)) end
local function fx(f)  -- 16.16 fixed point, two's complement
  local v = floor(f * 65536 + 0.5); if v < 0 then v = v + 4294967296 end; return u32(v) end

local display = component.list("opengpu_display")()
gpu.bind(display)
gpu.setResolution(320, 200)

-- vertex = i16 x, i16 y, i16 z, u16 pad, u32 rgba  (12 bytes); one triangle
local tri = concat({ i16(-100), i16(-80), i16(0), u16(0), u32(0xFF4040FF),
                     i16( 100), i16(-80), i16(0), u16(0), u32(0x40FF40FF),
                     i16(   0), i16( 90), i16(0), u16(0), u32(0x4040FFFF) })
local vbuf = gpu.createBuffer(tri, "vertex")
local prog = assert(gpu.createProgram([[
  attribute vec3 aPos; attribute vec4 aColor; uniform float uAngle; varying vec4 vColor;
  void main() { float c = cos(uAngle), s = sin(uAngle);
    vec2 p = vec2(aPos.x * c - aPos.y * s, aPos.x * s + aPos.y * c) / vec2(160.0, 100.0);
    gl_Position = vec4(p, 0.0, 1.0); vColor = aColor; }]], [[
  varying vec4 vColor; void main() { gl_FragColor = vColor; }]]))
local pipe = gpu.createPipeline({ program = prog, layout = "i16x3,pad16,rgba8", blend = "none", depth = false })

gpu.setMode("frame")
local angle, touched = 0, 0
while true do
  local cmd = concat({ "OGPU", u8(1),
    u8(0x10), u32(0x101820FF), u16(65535), u8(1),             -- CLEAR colour+depth
    u8(0x20), u32(pipe), u8(0x21), u8(0), u32(vbuf), u32(0),  -- PIPELINE, VBUF slot 0
    u8(0x24), u8(0), u8(1), fx(angle),                        -- UNIFORM_FX uAngle
    u8(0x30), u32(0), u32(3),                                 -- DRAW 3 vertices
    u8(0x40), i16(4), i16(4), i16(60), i16(10), u32(0xFFFFFF80),   -- RECT overlay
    u8(0x00) })
  gpu.submit(cmd)
  local id = gpu.present()
  -- wait for the flush (never spin: the 5 s "too long without yielding" hook, 04 §3)
  local ev, addr, px, py = computer.pullSignal(0.25)   -- "gpu_frame", "touch" (pixel coords) or timeout
  if ev == "touch" and addr == display then touched = touched + 1; angle = angle + 0.5 end
  angle = angle + 0.05
end
```

Handles come back as `Integer` (exact on every runtime); `pullSignal` returns `gpu_frame`, `touch` with pixel coordinates, or times out. The `assert` on `createProgram` fails with the server frontend's error message, which is identical on every client because clients receive GLSL *emitted* by that frontend, not the user's source.

## 4. Shader language and compiler

**Language: GLSL ES 1.00 subset** (06 §4): types `void bool int float vec2..4 bvec ivec mat2..4 sampler2D`; `attribute/uniform/varying/const`; built-ins `gl_Position`, `gl_FragCoord` (top-left origin, documented), `gl_FragColor`, `discard`; Appendix-A restrictions enforced as hard errors — `for` loops with constant bounds only, no `while`, no recursion, constant-index array/vector access, no dynamic indexing of uniforms. Precision qualifiers are parsed and ignored. Compute kernels use the same grammar with `kernel` as entry point, `global_id()` built-ins, read-only input buffers and exactly one write-only output indexed by the global id (no `local` memory, barriers or atomics in v1 — deterministic regardless of scheduling, 06 §5).

**Frontend (server, `:core`):** hand-written lexer + Pratt parser + type checker → typed AST → `ProgramIR` (SSA-ish, scalarized `vecN`). Limits at compile time: source ≤ 16 KiB; ≤ 4,096 scalar ops per fragment and 16,384 per vertex invocation counted statically (loop bounds are constants); ≤ 16 uniforms (64 floats), 8 varyings, 4 samplers; compile rate ≤ 4 programs/s per computer with a source-hash cache so repeated `createProgram` of the same text is free. Because the frontend runs on the server, every client receives already-validated IR output; a program that the server accepts is, by construction, within the emitted-GLSL subset that GLSM and the SDL-GPU cross-compiler accept.

**Backend 1 — GLSL 330 core (client):** `IrEmitter<String>` prints `#version 330 core` (the literal `core` token makes GLSM skip its compat rewrite, 02 §1), `in/out`, `out vec4 fragColor`, `texture()`, explicit `uniform mat4` inputs, attribute locations 0–7 via `glBindAttribLocation`, and mangled identifiers (`u_`, `v_`, `a_`) so user names like `sample`, `new`, `sampler` never reach `renameReservedWords` (02 §1). No geometry/tessellation stages (SDL-GPU lacks geometry shaders, 02 §4). Transcendentals are the GPU's; this is where the backends legitimately differ.

**Backend 2 — JVM bytecode (server, `:jit`):** compute and readback renders. Shaded, relocated ASM 9; `V1_8` classes; `COMPUTE_FRAMES` with a non-loading `getCommonSuperClass`; one class per (program × pipeline state); `strictfp`; built-ins through `StrictMath`; no `invokedynamic` or reflection; defined through a per-program child `ClassLoader` whose parent is the mod's loader (05 §1); re-derived from persisted source after reload (05 impl. 2). The interpreter stays the reference and the fallback. Budget: ≤ 10 ns per element compiled vs 100–500 ns interpreted (07 §1, M0.3).

**Safety bounds:** static op caps; emitted methods ≤ 6 KB bytecode (HotSpot never compiles above 8,000 bytes, 06 §4); closed IR (arithmetic, array, texture fetch, select) calling nothing outside a tiny `opengpu.core.rt` package; `CheckClassAdapter` in tests; NaN/Inf vertex positions rejected at decode time.

**Determinism, honestly.** Only `+ - * / sqrt` and conversions are bit-reproducible between the JVM reference and a GPU; `sin/cos/pow` differ by ULPs, and GL blending rounding is implementation-defined even with identical sample positions and fill rules. The contract: opaque 2D operations (`RECT`, nearest `BLIT`, `COPY`, `TEXT`) are bit-exact across the reference and every client; blended and shaded pixels match within ±1 LSB per channel plus edge pixels at triangle boundaries. Readback returns the *reference* render, never a viewer's. The conformance test (§7) renders every golden scene through both backends and asserts those tolerances.

## 5. Server/client responsibilities, networking, persistence, threading, platform support

### 5.1 Responsibilities

**Server (authoritative):** resource store and VRAM accounting; command validation (handle liveness, ranges, draw counts, pipeline/layout compatibility), 0.05–0.2 ms per 30–60 KB buffer (07 §1); budget charging; frame coalescing; viewer subscription and per-viewer bandwidth; compute; readback renders; canvas-log compaction; persistence; input validation (reach check, `computer.checked_signal` so `canInteract` applies, 00 §3).

**Client (per viewer):** mirror of the resource store for watched displays; GL object cache per handle (lazy creation, deletion on `free`/unload, Guava expiry as `HologramRenderer` does); frame execution; presentation; input capture in pixel coordinates (override `handleMouseInput`, use physical `Mouse.getEventX/Y`, 03 §2).

### 5.2 Networking format

One event-driven FML channel `"OpenGPU"`, OC's raw-`DataOutputStream` style with a compression flag byte, heap `ByteBuf`s released by hand (03 §3). **One packet per display per tick**: `RES_CREATE/UPDATE/FREE` deltas (texture rects, buffer ranges, program GLSL + IR hash, pipeline descriptors) coalesced per tick, then zero or more `FRAME(frameId, mode, cmdBytes)` records, Deflate `BEST_SPEED`. Recipients: `isPlayerWatchingChunk` ∧ distance ≤ render distance (64 m default) ∧ subscribed, not OC's effectively unbounded chunk-watcher broadcast (03 §3, 01 impl. 4).

*Frame mode:* each `FRAME` is self-contained; a viewer with an empty token bucket skips frames. *Canvas mode:* the FBO persists; the server appends each frame's commands to a per-display **canvas log** capped at 256 KiB or 200 frames, truncated by a full `CLEAR`. When the cap is exceeded, the OpenGPU pool renders the log once through the software reference backend (bounded by the readback clamp, §6) and replaces it with one `BLIT_PIXELS` keyframe (deflated, ≤ 340 KB at 640×400, 07 §5). Existing viewers never see the compaction; only late joiners replay the compacted log. Compaction is rate-limited to one per display per 5 s; a program that defeats it is charged `context.pause` à la `bitblt` (00 §3).

*Keyframe on subscribe:* resource set (≤ VRAM cap at ≤ 32 KiB/tick per viewer, so a full 8 MB T3 store takes ~12 s, §8) followed by the last frame or the canvas log. Client→server traffic is input only (≤ 32,766 bytes, unpatched anywhere, 03 §3). Large server→client payloads are split by rows/ranges below the portable 2,097,050-byte cap; Hodgepodge's 256 MiB is never assumed (00 §5).

### 5.3 Persistence

Nothing large in NBT. The card's item NBT holds handle tables, generation counters, tier and the display binding (a few hundred bytes); the display tile NBT holds resolution/mode. Resource contents (buffers, textures, program *source*, pipeline descriptors) and the canvas log or last frame go to an OpenGPU-owned side-file store `<world>/opengpu/state/<dim>/<cx>.<cz>/<address>` with async writes on a 1-thread pool, modelled on `SaveHandler` (00 §5; the class is internal, so OpenGPU implements its own). Heavy work stays out of `writeToNBTForClient`, which OC's `TextBuffer.save` gets wrong (00 §5). Compiled JVM classes and GL objects are never persisted.

**OC-LuaJIT full-state persistence** (04 §6): Lua holds only integer handles and byte strings, so OC-LuaJIT's serializer never sees an OpenGPU object. After load the server restores pools with the same slot/generation values, so a coroutine suspended between `submit` and `present` resumes holding valid handles. A missing or corrupt side file resets all pools and signals `gpu_reset`; LuaJ (no persistence, machine reboots) takes the same path. In-flight compute jobs and unflushed frames are discarded on save, which is why `readBuffer` is a poll, not a promise.

### 5.4 Threading

| Thread | Does | Never does |
|---|---|---|
| OC worker (direct callbacks, 4 shared threads) | validate, `consumeCallBudget`, copy `byte[]`, enqueue; ≤ 0.2 ms; one coarse monitor per `GpuDevice` (OC's own model, 00 §5) | rasterize, deflate, touch node network/world |
| Server tick | `Replicator.flush()`: per display, build and send one packet, emit coalesced `gpu_frame`; 5–20 µs per display (07 §3); run queued world-side effects | encode frames, render |
| OpenGPU pool (`ThreadPoolFactory.createSafePool`-style, `max(1, cores/2)`, bounded queue) | compute dispatch, readback renders, canvas compaction, deflate of keyframes | hold the machine monitor |
| Client main thread (packet handler) | `readBytes` into `byte[]`, release `ByteBuf`, enqueue | inflate, parse |
| Client decode worker | inflate, parse, update `ClientDisplayState` | GL |
| Client render thread | `RenderTickEvent(START)`: execute pending frames into FBOs (budgeted: ≤ 4 ms/frame total across displays, remaining displays wait); TESR draws quads | block on network |

Frame-done semantics: `present` returns immediately with a frame id; `gpu_frame` fires when the server has flushed the frame to the network (not when a client has drawn it — the server cannot know that). At most 2 frames in flight per display; a third `present` returns `nil, "busy"` and the program must yield (07 §3).

### 5.5 Angelica / Iris compatibility measures

All GL lives in `opengpu.client.render` and is never excluded from transformation (02 §1). Concretely:

1. **Offscreen pass outside the world phase.** Frames execute in `RenderTickEvent(Phase.START)` (`FMLCommonHandler.java:335`, before `EntityRenderer.updateCameraAndRender`), where Iris's `shouldOverrideShaders()` is false, so binding our programs never detaches an Iris pass (02 §2); `RenderWorldLastEvent` is the alternative. Save and restore `GL_DRAW/READ_FRAMEBUFFER_BINDING`, `GL_VIEWPORT`, `GL_CURRENT_PROGRAM` (the *exact* id or 0), buffer/VAO bindings, texture units 0–3 and the active unit, all answered cheaply from GLSM's cache (02 §1).
2. **Presentation is the baseline path:** `glPushAttrib` with specific bits, lightmap 240/240, bind the FBO colour texture, one `Tessellator` quad, `glPopAttrib`: OC's `ScreenRenderer` pattern that 02 §3 calls production-safe. Idempotent and upload-free, because Iris may call it twice.
3. **Chassis via a stateless `@ThreadSafeISBRH(perThread = false)` ISBRH** with no `Tessellator.draw()`; this makes `getRenderType()` ≠ −1, so the TESR is skipped in the Iris shadow pass under the default `shadowSkipInMeshTileEntities=true` (02 §2).
4. **Stable render bounds:** the origin tile returns a constant maximum-extent AABB from its first `getRenderBoundingBox()` call and a constant `getMaxRenderDistanceSquared`, because Celeritas classifies per class on first sight and caches per instance (02 §3, 03 §1).
5. **Only GLSM-mapped calls:** GL11/15/20/30/33 subset; sized formats (`GL_RGBA8`, `GL_DEPTH24_STENCIL8`); `glClearColor`+`glClear`, never `GL30.glClearBuffer*` (unmapped); **no `GLSync`** (unmapped *and* unreported; orphan with `glBufferData(null)` instead); no display lists or `glBegin`; `glBindVertexArray(0)` on exit. Dev runs use `-Dangelica.unmappedGL=STRICT`, `pinnedGLVersion=33`, one `glProfile=ES` run, and a CI grep for `GLSync` (02 §4–5).
6. **Shaders:** `#version 330 core`, mangled identifiers, explicit uniforms (02 §5). SDL-GPU (lwjgl3ify-only, opt-in since 2.2.29) lacks geometry shaders and needs indirect draws; the emitted subset is compatible and is tested on the Java 21 leg only.
7. **Optional Angelica-only features** behind `Loader.isModLoaded("angelica")`: `TesrMeshProvider`/`TesrShaders` for the presentation quad. Default build is `compileOnly(...:2.2.21:api) { transitive = false }`.
8. **Fallback:** if the context reports GL < 3.0, no FBO, or a program fails to link, that client switches the display to `SoftwareClientBackend`, the same `:core` rasterizer running on the client, presented through `glTexSubImage2D`. The server is not involved, so its cost model never changes.

### 5.6 Java 8 + LWJGL2 and Java 17–21 + lwjgl3ify

Java 8 bytecode only (jabel, `--release 8`); LWJGL2 API surface (`org.lwjgl.opengl.GL11..GL33`), which lwjgl3ify rewrites to `org.lwjglx` and Angelica redirects to GLSM *before* that (02 §4), so one code path serves both stacks. Capability probing is one final `GlCaps` class that reads LWJGL2's `GLContext.getCapabilities()` plus `OpenGlHelper` flags lazily on the render thread and caches primitives; the same code serves LWJGL 2.9.4 and lwjgl3ify, whose `org.lwjglx` shim returns a process-wide snapshot that Angelica's own GLSM reads too (14 §2). No per-LWJGL-major implementations and no `glGetString`/`GL_MAJOR_VERSION` branching (the strings are logged only); OpenGPU classes are never `@Lwjgl3Aware` and the jar never sets `Lwjgl3ify-Aware`; only fields present in LWJGL 2.9.4, the shim and LWJGL 3.4.3 are read (14 §2.3, §2.7). No `Unsafe`, `Lookup.defineClass` or `sun.*`; ASM relocated. Without Angelica on Java 8 the context is Forge's compat context; `GlCaps` then picks `GlBackend` (GL ≥ 3.0, emitting `#version 130`) or the software fallback. CI matrix: `runClient` (Java 8/LWJGL 2.9.4, Angelica on/off) and `runClient21` (lwjgl3ify 3.0.33, Angelica, Iris pack, SDL-GPU opt-in) (05 §2).

## 6. Tiers, caps, abuse protection and expected performance

Because display cost moved to the client GPU, resolution is cheap and the binding constraints become command bytes, resource bytes and client-side work. Tiers (integer fractions of OC's 1280×800 T3 text raster, 07 §5):

| Tier | Display max | Colour on wire | VRAM (resident resources) | Command bytes / frame | Draw calls / tris per frame | Readback/compaction clamp | Presents / tick (cost 0.5) |
|---|---|---|---|---|---|---|---|
| T1 | 320×200 | RGBA8 (palette via `r8` + 2D lib) | 1 MB | 32 KiB | 256 / 50 k | 320×200, 10 FPS, 0.5 M fragments | 2 (installed) / 1 (default) |
| T2 | 640×400 | RGBA8 | 4 MB | 128 KiB | 1,024 / 200 k | 640×400, 10 FPS, 2 M fragments | 4 / 2 |
| T3 | 1280×800 | RGBA8 | 8 MB | 256 KiB | 4,096 / 1 M | 640×400, 20 FPS, 6 M fragments | 8 / 3 |

Readable targets count 4× against VRAM and are clamped to 640×400 because the software path costs 6–11 ms per 640×400 3D frame (07 §1) and would exceed a tick at 1280×800. Other caps: shader limits (§4); compute ≤ 2^22 work-items per dispatch, ≤ 4 jobs in flight, cost `work_items × ops / 2^24` budget units; upload cost `bytes / 64 KiB`; frames in flight ≤ 2; per-viewer token bucket 16 KiB/tick steady (320 KB/s), 32 KiB/tick during initial sync; server ceilings on active displays (default 64) and pool queue depth; `context.pause` for repeat offenders (`bitblt` precedent). Clients self-limit (≤ 4 ms of frame execution per render frame, further displays deferred) because the server cannot see client cost. Every cap is tiered config, tested against both `[1,2,4]` and `[0.5,1,1.5]` budgets.

**Expected numbers (from the reports' models; measured in M0):**

- *Server CPU, 2D display:* validation of a 2–8 KB draw list 10–50 µs, packet build < 50 µs: **< 0.1 ms per display per tick**; 100 displays ≈ 1 ms of a 50 ms tick (07 §3).
- *Server CPU, 3D display:* 0.05–0.2 ms validation, **no raster**; ten 640×400 3D displays ≈ 2 ms/tick instead of 3–4 cores under Architecture A (07 §1).
- *Server CPU, compute:* 64 k-element kernel of ~30 ops, 0.15–0.65 ms compiled (07 §1), on the pool. *Readback:* 1.5–3 ms (320×200) / 6–11 ms (640×400) per rendered frame (07 §1); compaction the same, ≤ 1 per 5 s per display.
- *Bandwidth per viewer:* canvas-mode 2D draw lists 0.1–0.5 KB/frame, **2–10 KB/s**; 3D with resident meshes 0.2–2 KB/frame, **4–40 KB/s** (07 §1); streaming 1 k triangles per frame ≈ 400 KB/s, which the command-bytes cap and token bucket turn into dropped frames rather than server load.
- *Client:* 1–2 k triangles are free; one FBO per visible display (2 MB at 640×400, 8 MB at 1280×800); shader compile once per program per client, cached by IR hash; inflate 0.1 ms.
- *Latency:* validate 0.1 ms + ≤ 50 ms tick wait (avg 25) + network + ≤ 16 ms client frame ≈ **20–40 ms LAN** (07 §1).

## 7. Success criteria, headless testing, milestones

**(a) Lua 5.3/5.4/LuaJIT:** the boundary uses only `byte[]` top-level strings (`checkByteArray`), `Integer` handles, `Double`-tolerant integer arguments, small tables copied out with key normalisation, and signals with `Long/Double/String` args: the rules of 04 Design implications 1–7. The helper library is 5.2-syntax and feature-probed; LuaJ works at "correctness only" (no persistence, hence `gpu_reset`).

**(b) Modularity:** `:core` has zero Minecraft/OC/LWJGL imports, two consumers from day one (OC and ocelot adapters) and two implementations of one `Backend` interface (software, GL). The command stream is versioned; opcodes, stages and resource types are additive. An Angelica-native path (`TesrMeshProvider`, compute via `glDispatchCompute` where `RenderSystem.supportsCompute()`) is just another `Backend`.

**(c) Angelica:** §5.5. The only GL requirements are calls 02 §4 lists as mapped on both backends, and the software client fallback means a GLSM regression degrades to Architecture A's picture on that client, never to a black screen.

**Headless testing under ocelot-brain** (05 §3–4): `:ocelot-harness` boots a T3 `Case` with the `OpenGpuEntity`, pins `NativeLua53/52/54Architecture` and OC-LuaJIT explicitly per test, runs the Lua program from a 4 KiB EEPROM BIOS, and asserts on three oracles: (1) the server-side reference render of readable targets (golden PNGs, tolerance 0 for opaque 2D, ≤ 1 LSB shaded); (2) the **recorded packet stream** replayed through `:stream`'s decoder into the `:core` software backend, the "virtual client", which must reproduce the reference image, proving replication without any GL; (3) budget semantics (`LimitReached` stall, `nil,"busy"`, `gpu_frame` coalescing) and persistence round-trips (`ws.save` → `Workspace.load` with a program suspended between `submit` and `present`, on OC-LuaJIT too). `:core` unit tests (JUnit 5 on JDK 8 and 17): codec fuzzing, frontend/interpreter/JIT differential tests, `CheckClassAdapter`, JMH fill rate. The GL backend is validated in-game on the §5.6 matrix and, if Angelica's `GLSMCoreExtension` fixture proves reusable (02 open question 6), headless under a 3.3 core context against the same golden images.

**Milestones (effort in focused person-weeks, solo):**

| M | Scope | Exit criteria | Effort |
|---|---|---|---|
| **M0 vertical slice** | `:core` codec + pools + flat/Gouraud triangles + `RECT/BLIT`; `GpuCard` with `createBuffer/submit/present`; `:stream` replication; `GlBackend` with two fixed built-in programs (no user shaders); TESR quad; ocelot harness + virtual client; measurements from 07 §6 M0.1–M0.2 | triangle + rect visible in-game on Java 8/LWJGL2 and Java 21/lwjgl3ify with Angelica (AUTO and CORE, Iris pack on); ≤ 2 KB/frame; ≤ 1 ms server tick for 10 displays; Lua encode ≤ 5 ms/1 k tris on 5.3, ≤ 2.5 ms on LuaJIT; per-call overhead measured | 4 |
| M1 | GLSL ES frontend, interpreter, GLSL 330 emitter, uniforms, textures, pipelines, `TEXT/LINE/COPY`, Lua helper library, pixel input events, GUI 1:1 view | user shaders run on clients; conformance suite passes interpreter vs GL within tolerance | 4 |
| M2 | `:jit` ASM backend, compute (`dispatch/readBuffer`), readable targets + `readPixels`, canvas mode + log compaction | ≤ 10 ns/element; identical output JDK 8 vs 21; late joiner sees a compacted canvas | 4 |
| M3 | Side-file persistence, OC-LuaJIT save/load tests, token buckets, all caps and abuse tests, software client fallback, SDL-GPU run, `-Dangelica.unmappedGL=STRICT` clean | persistence round-trip green on 5.3/5.4/LuaJIT; abuse programs cannot exceed a tick budget; no unmapped GL calls | 3 |
| M4 | Multi-block displays, fonts/palette 2D library, docs, viewer polish, release | public API frozen at v1 | 2 |

≈ 17 person-weeks to a releasable v1; M0 alone decides whether the client GL path survives Angelica, which is why it is first.

## 8. Risks and honest weaknesses

1. **Two renderers that must agree.** The GL backend and the software reference will drift; every opcode and built-in needs a conformance case, and driver differences mean "what Lua reads back" is never exactly "what the viewer sees" (±1 LSB, edge pixels, transcendentals). Programs that read back their own display pay a software render they would not pay under Architecture A.
2. **Readback is expensive and opt-in**, clamped to 640×400. `getPixel`-style 2D programs (MineOS-like diffing, 01 §8) are poorly served; the mitigation is that canvas mode makes Lua-side diffing unnecessary, which is an API-education problem.
3. **Angelica is the dominant risk and it moves fast** (2.2.8 → 2.2.30 in two months; 07 Risk 10). Core-profile GLSL, FBOs, program restore under Iris, SDL-GPU's cross-compiler and the per-class TESR bounds cache are all surfaces a release can break. Mitigations are discipline (§5.5), the CI matrix and the software client fallback, but that fallback is slower (software at 1280×800 on a client is 2–3× a 640×400 server render) and is a second client code path to maintain.
4. **Late joiners wait.** A viewer subscribing to a display with an 8 MB resource set needs ~12 s at 32 KiB/tick before it sees anything; progressive sync (reduced-LOD textures first) is future work. OC's text screens have no such phase.
5. **Canvas compaction uses the software path**, so a late joiner's canvas can differ from an existing viewer's by the tolerances above, and a hostile program can force compaction cost onto the server (rate-limited and paused, not free).
6. **Client cost is invisible to the server.** Caps are conservative and clients self-limit, but an operator cannot see a display making players lag; a client cost report would need a client→server channel and trust.
7. **Dedicated servers have no picture.** Screenshots, moderation tools or server-side bots see nothing unless a target is readable; Architecture A gives this for free.
8. **Determinism between viewers is cosmetic.** Two players can see ULP-different shading; 2D opaque content is identical. This matches what OpenGlasses and WebDisplays promise today (01 §2, §7) and is weaker than OC's text screens.
9. **Complexity.** This is the largest of the three designs: GLSL emitter *and* JVM emitter *and* interpreter, GL *and* software backends on the client, frame *and* canvas modes, compaction, side files. M0 is scoped to prove the riskiest half (GL under Angelica, replication, Lua encoding cost) in four weeks; if the Angelica leg fails on either JVM, the software client path becomes the only client path and the design degenerates into "Architecture A rasterized on the client", still viable and still bandwidth-cheap: the command stream survives either outcome.
10. **Assumptions to retire in M0:** ~~`GlCaps` under lwjgl3ify (03 §5)~~ (resolved by code reading in 14; a `runClient21` log line confirms it at zero cost); `NetworkManager.scheduleOutboundPacket` from pool threads (verified by code reading, 13 §1.5; the M0 run on dedicated and integrated servers is confirmation, with a `sendFromPool=false` switch for diagnosis); GLSM behaviour for off-thread GL; the 1.5–2.5× deflate ratio behind the Architecture A comparison (07 §1); ~~the 5–20 µs per-call overhead estimate (07 §2)~~ (measured by 11 §2: 4.2–4.5 µs empty, 5–9 µs with scalar args on PUC Lua, 1.5–2.4 µs on OC-LuaJIT).

## Verification notes

### Amendments after gap-fill (2026-10-08)

- **Capability probing under lwjgl3ify (from 14 §2, §3, "superseded upstream" list).** §5.6's `GlCaps` interface with `glGetString(GL_VERSION)`/`GL_MAJOR_VERSION` probing "because `ContextCapabilities` and `GLCapabilities` differ (unverified under lwjgl3ify, an M0 item)" and risk 10's "`GlCaps` under lwjgl3ify" M0 assumption are superseded: `GLContext.getCapabilities()` plus `OpenGlHelper` flags work on both legs through lwjgl3ify's `org.lwjglx` shim (a process-wide snapshot that Angelica's GLSM itself reads); one final class, no per-LWJGL implementations, no `glGetString` branching, never `@Lwjgl3Aware`, read only fields present in LWJGL 2.9.4, the shim and LWJGL 3.4.3. Both places corrected.
- **Pool-thread sends (from 13 §1.5).** Risk 10's "sends stay on the tick thread until tested" is relaxed: `NetworkManager.scheduleOutboundPacket` from pool threads is verified by code reading; the M0 run is confirmation.
- **Per-call overhead (from 11 §2).** Risk 10's "5–20 µs per-call overhead estimate" is now measured (4.2–4.5 µs empty, 5–9 µs scalar on PUC Lua; 1.5–2.4 µs on OC-LuaJIT).
