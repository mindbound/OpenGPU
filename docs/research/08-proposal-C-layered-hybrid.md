# 08 — Proposal C: layered core, software reference backend, pluggable acceleration

Architect C · 2026-10-08 · Starting constraint: a pure-Java core (resources, command stream, software rasterizer, shader compiler, compute) with no Minecraft/OC dependency; adapters for OC, ocelot-brain and a standalone viewer; a client-side hardware backend consuming the same command stream as an optional later addition. Citations are `NN §x` = research report `docs/research/NN-*.md`, section x, plus the source line the report cites.

## 1. Overview

**Thesis.** The product is the engine, not the mod. `opengpu-core` is a Java 8 library that *defines the image*: a versioned little-endian command stream, handle-addressed resources and immutable pipelines, a deterministic tile-parallel software rasterizer, a GLSL-ES-subset shader compiler (interpreter + JVM bytecode backend) and a work-item compute dispatcher. Everything else is an adapter: the OC mod (server component + client display), the ocelot-brain entity (headless tests), a Swing viewer.

**Where rendering runs by default — decided.** The server always executes every presented frame on an OpenGPU-owned thread pool; its framebuffer is authoritative for readback, persistence and keyframes (01 implication 6; 07 §1). Each viewing client *also* holds the identical core jar and runs a **mirror `Device`**: per display, per tick, the server ships whichever is smaller — `TILES` (dirty 16×16 tiles, palette/RGB565, deflate) or `CMDS` (the validated command bytes plus the resource deltas that viewer lacks) — and the client applies tiles or replays commands on its mirror. Because both sides run the same `strictfp` code the mirror is bit-identical by construction; a CRC32 of the presented frame travels in every packet header and a mismatch triggers a keyframe resync. The research numbers force this split: a 2D UI costs 20–80 KB/s per viewer as tiles (07 §1; 03 §4) but full-motion 3D at 320×200/16-bit costs 1.0–1.7 MB/s as tiles versus 4–40 KB/s as commands with resident meshes (07 §1 Architecture B). A later **GL hardware backend** is a drop-in replacement for the mirror's `Backend`; the stream is specified so the software core is the reference and the GL backend passes a tolerance-based conformance suite (§4, §8).

```
 Lua program (OC Lua 5.3/5.4, 5.2, OC-LuaJIT, LuaJ)
   | component.invoke: opengpu.submit(bytes) | present() | create* | upload | read*
   | direct callbacks on an OC "Computer" worker thread (00 §1), microsecond-cheap
   v
 +---------------------- OC adapter, server side (mod jar) ------------------------+
 | OpenGpuCard (ManagedEnvironment) -> core.Device: consumeCallBudget FIRST,        |
 |   validate, copy bytes, enqueue job                                              |
 |   <- update() on server tick: node.sendToReachable("computer.signal",            |
 |        "opengpu_frame", id, status)                                              |
 +----------------------------------|---------------------------------------------+
                                    v  ordered job queue (uploads, frames, dispatches)
 +---------------------- opengpu-core (pure Java 8, strictfp) ---------------------+
 | ResourceStore (pools, generation handles, VRAM) | CommandDecoder                 |
 | SoftwareBackend: tile binner + edge-function rasterizer + ShaderExecutor         |
 |   (Interpreter in core, JitCompiler in :jit) | ComputeDispatcher                 |
 | FrameImage {int[] argb | byte[] idx, dirty tiles, crc32}                         |
 +----------------------------------|---------------------------------------------+
                                    v  FrameSink.onFrame (pool thread)
 +---------------------- opengpu-stream (pure Java) ------------------------------+
 | ViewerState{residency, lastSeq, token bucket}; FrameEncoder: KEY|TILES|CMDS      |
 +----------------------------------|---------------------------------------------+
                                    v  server tick: <=1 packet per display per viewer
 ================= FML channel "OpenGPU", S->C <=256 KB soft, C->S <=32 KB =========
                                    v  client main-thread handler -> decode worker
 +---------------------- OC adapter, client side (mod jar) ------------------------+
 | DisplayMirror: apply TILES | replay CMDS on a mirror core.Device                  |
 |   -> int[] ARGB -> render thread: glTexSubImage2D dirty rows -> TESR quad / GUI  |
 |   (later: GLBackend executes CMDS on the GPU into an FBO instead)                |
 +--------------------------------------------------------------------------------+

 Test path (no MC, no GL):
 Lua program -> ocelot-brain Machine (5.2/5.3/5.4, OC-LuaJIT) -> OpenGpuEntity (Scala)
   -> core.Device -> in-memory FrameSink -> JUnit golden PNG / CRC / budget / persistence
   -> optional Swing viewer
```

## 2. Module layout and public interfaces

Gradle subprojects, GTNHGradle on the root only (05 §4, Angelica's `:glsm` precedent); all Java 8 bytecode via jabel (`--release 8` is enforced, 05 §1).

| Project | Deps | Contents |
|---|---|---|
| `:core` (`java-library`, release 8) | none | `io.github.mindbound.opengpu.core.{math,resource,stream,raster,shade,compute,device}` |
| `:jit` | `:core`, ASM 9 (shaded+relocated at root) | GLSL-ES AST → JVM bytecode, variant cache, private `ClassLoader` per program |
| `:stream` | `:core` | tile diff, wire formats, `FrameEncoder/FrameDecoder`, `ViewerState`, `Mirror` |
| root `opengpu` (the mod) | shadow `:core`,`:jit`,`:stream`; `compileOnly` OC `1.12.64-GTNH:api`, Angelica `2.2.21:api` | driver/item/block/TE, callbacks, FML channel, persistence, TESR/GUI, `GLBackend` (M4) |
| `:ocelot-harness` (Scala 2.13, test-only) | `:core`,`:stream`, vendored `ocelot-brain-0.24.2.jar`, OC-LuaJIT | `OpenGpuEntity`, Lua test programs, architecture matrix |
| `:viewer` (Swing) | `:core`,`:stream` | frame player, golden-image runner, shader playground |
| `assets/opengpu/lua/` | — | `opengpu.lua` helper library (encoder, 2D immediate API, matrices), shipped as a loot disk |

The relocated ASM is mandatory, not cosmetic: `LaunchClassLoader` loads `org.objectweb.asm.` parent-first and the launcher's ASM is 5.0.3 on Java 8 but 9.x under lwjgl3ify (05 §1).

**Contracts between modules** (Java signatures; `Result<T>` = `{T value; String error; int code}` so adapters map errors to `nil, msg` without exceptions):

```java
// :core — the only API adapters use
public final class Device {
  public Device(Tier tier, DeviceLimits limits, Executor pool, FrameSink sink);
  public Result<Integer> createBuffer(int bytes, BufferUsage u);           // VERTEX|INDEX|STORAGE
  public Result<Integer> createTexture(int w, int h, TexFormat f);         // RGBA8|R8
  public Result<Integer> createRenderTarget(int w, int h, boolean depth);
  public Result<Integer> createProgram(byte[] vs, byte[] fs);              // frontend now, codegen lazily
  public Result<Integer> createKernel(byte[] src);
  public Result<Integer> createPipeline(PipelineDesc d);                   // immutable
  public Result<Void>    upload(int handle, int offset, byte[] data);      // enqueued, ordered with frames
  public Result<Void>    free(int handle);  public void reset();
  public Result<Void>    submit(byte[] cmds);                              // validates, appends to open frame
  public Result<Integer> present();                                        // seals + enqueues; frameId
  public Result<byte[]>  readPixels(int target, int x,int y,int w,int h, PixelFormat f, int maxBytes);
  public Result<byte[]>  readBuffer(int handle, int offset, int len);      // "pending" until done
  public void bindDisplay(DisplaySpec spec);  public DeviceStats stats();
  public void save(DataOutput out) throws IOException;  public void load(DataInput in) throws IOException;
}
public interface FrameSink  { void onFrame(FrameResult r); }     // pool thread
public interface Backend    { void execute(CommandRecord rec, ResourceStore rs, RenderTarget rt, Budget b); }
public interface ShaderExecutorFactory { ShaderExecutor compile(ProgramIR ir, PipelineState st); }
public interface Scheduler  { <T> Future<T> submit(Callable<T> job); int parallelism(); }
```

`:stream` exposes `FrameEncoder.encode(ViewerState v, FrameResult r, WireFormat f) -> EncodedFrame` (chooses `TILES` vs `CMDS` by byte count, emits `KEY` when `v.lastSeq` is stale) and `Mirror.apply(byte[] packet)` (holds `int[] argb` + a `Device` in mirror mode with budgets disabled). The OC adapter and the viewer both consume exactly these two classes, so the wire protocol is tested without Minecraft.

## 3. Lua-facing API

Two components, mirroring OC's gpu/screen split so OpenOS conventions (`component.proxy`, `event` names) carry over (00 implication 1):

- **`opengpu`** — the card. `DriverItem`, `Slot.Card`, tiers 1–3, `createEnvironment` returns `null` on `world.isRemote` (`DriverGraphicsCard.scala:22`); node `Visibility.Neighbors`; registered in FML `init` because OC locks the driver registry in its own `postInit` (00 §2, `Proxy.scala:112-115`).
- **`opengpu_screen`** — the display block (`TileEntityEnvironment`, `Visibility.Network`), `SidedEnvironment` offering its node on the five non-front faces to anything and on the front face only to an adjacent OC keyboard, like OC's screen (`Screen.scala:63-67`; 15 §2). Multi-block merging is deferred to M4 but the TE returns its *maximum* extent AABB from the first call (02 §3, 03 implication 3).

| Group | Callbacks (all `direct=true` unless noted) | Notes |
|---|---|---|
| display | `bind(addr)` (non-direct, like `gpu.bind`), `getResolution()`, `setResolution(w,h)`, `getTier()`, `getCaps()`, `setPalette(bytes)` (768 B RGB), `getPalette()` | `setResolution` ≤ tier max; cost 1, forces a keyframe |
| resources | `createBuffer(bytes, usage)`, `createTexture(w,h,fmt)`, `createRenderTarget(w,h,depth)`, `createProgram(vs,fs)`, `createKernel(src)`, `createPipeline(desc)`, `upload(h,off,bytes)`, `free(h)`, `freeAll()`, `getMemory()`, `handles()` | handles are `Integer` slot\|generation (06 §1), never `Value` userdata (04 §6, 07 risk 4) |
| programs | `getProgramInfo(h)` → `{uniforms={name={slot,type}}, attributes, log}` | compile errors come back synchronously as `nil, log` |
| frame | `submit(bytes)`, `present()` → frameId or `nil,"busy"`, `getFrameStatus(id)`, `abort()` | ≤ 2 frames in flight (07 §3) |
| readback | `readPixels(target,x,y,w,h,fmt,maxBytes)`, `readBuffer(h,off,len)` → bytes or `nil,"pending"` | `maxBytes` ≤ 64 KB; Lua sizes it against `computer.freeMemory()` (04 §3) |
| simple 2D | `fill(x,y,w,h,argb)`, `blit(tex,sx,sy,w,h,dx,dy,blend)`, `line(x0,y0,x1,y1,argb)`, `text(x,y,str,fg,bg)`, `copy(sx,sy,w,h,dx,dy)` | one opcode each into the open frame; charged like `gpu.set` (1/64,1/128,1/256) |
| screen | `turnOn/turnOff/isOn`, `getAspectRatio` | |

**Signals.** `opengpu_frame(cardAddr, frameId:Long, status:String)` is emitted from the card's `update()` on the server thread via `node.sendToReachable("computer.signal", ...)` (the `NetworkCard` pattern; the node network is not thread-safe from the pool, 00 implication 4), coalesced to one pending signal per device because `Machine.signal` drops when the 256-entry queue is full (07 §3). Arguments are `Long`/`String` only, because `Machine.save` turns `Integer` signal args into nil after a reload (04 §3). Screen input reuses OC's names and argument order so `event.listen("touch", …)` works unchanged: `touch/drag/drop(screenAddr, x, y, button, player)` and `scroll(screenAddr, x, y, delta, player)` with **0-based integer pixel coordinates**, sent as `computer.checked_signal` after the 8-block reach check (00 §3). Keyboard input is not reinvented: `keyboard.keyDown/keyUp/clipboard` node messages go to adjacent keyboards exactly as `TextBuffer.ServerProxy.sendToKeyboards` does (`TextBuffer.scala:909-916`), and OC's keyboard component applies its own reach check (03 §2).

**Command stream v1** (the stable contract; little-endian; every operand fixed-size so the decoder is a table-driven loop with a per-opcode length check):

```
header  'O','G','P','U'  u8 version=1  u8 flags  u16 reserved
0x01 CLEAR        u32 argb  i32 depth(16.16)  u8 mask(1=color,2=depth)
0x02 VIEWPORT     i16 x,y,w,h           0x03 SCISSOR  i16 x,y,w,h (w=0 disables)
0x10 BIND_PIPELINE u32 pipeline         0x11 BIND_VBUF u8 slot u32 buffer u32 offset
0x12 BIND_IBUF    u32 buffer u8 type(1=u16,2=u32)   0x13 BIND_TEX u8 unit u32 texture u8 sampler
0x14 UNIFORMS_FX  u8 firstSlot u8 count i32[count*4] (16.16)   0x15 UNIFORMS_F32 ... f32[count*4]
0x16 SET_TARGET   u32 renderTarget (0 = bound display)
0x20 DRAW         u32 first u32 count   0x21 DRAW_INDEXED u32 first u32 count
0x30 FILL_RECT    i16 x,y,w,h u32 argb  0x31 BLIT u32 tex i16 sx,sy,w,h,dx,dy u8 blend
0x32 LINE         i16 x0,y0,x1,y1 u32 argb
0x33 TEXT         i16 x,y u32 fg u32 bg u16 len bytes[len]      (built-in 8x16 font)
0x34 COPY         i16 sx,sy,w,h,dx,dy   (also a wire op, 03 §4)
0x40 DISPATCH     u32 kernel u32 nx,ny,nz u8 nbind {u8 slot u32 handle}[nbind]
0x41 WRITE_BUFFER u32 buffer u32 offset u32 len bytes[len]
0xFF END
```

2D ops are executed by the same rasterizer through a built-in unlit pipeline (Dear-ImGui style, 06 §2) with decode-time fast paths for axis-aligned fills and NONE/BLEND blits (~0.3 ns/px row copy vs 2.4–3 ns/px through the triangle path, 06 §3). Vertex attribute formats are declared in `PipelineDesc` (`fx16_16x{1..4}`, `i16x{2,4}`, `unorm8x4`, `f32x{1..4}`), so both backends convert identically. Uniforms are addressed by vec4 slot; the compiler assigns slots in declaration order and `getProgramInfo` reports them.

**Encoding strategy and call counts.** One frame = 1 `submit` + 1 `present` (+ occasional `upload`), i.e. 40–60 direct calls/s at 20 fps against 20,480 (installed) or 7,680 (default) `gpu.set`-equivalents/s on T3 (07 §2 table). Per-primitive calls exist only in the "simple 2D" group and are charged at OC's own `set` rates, so a naive program behaves like a naive `gpu.set` program (64/256/1024 ops per tick here). Oversized frames are streamed in several `submit` calls (each ≤ 64/128/256 KB by tier); `present` seals. Bulk data crosses as top-level Lua strings (`Arguments.checkByteArray`, zero decoding, 04 §1); tables are accepted only for `PipelineDesc` (< 100 entries, copied out with key normalization because keys are `byte[]` on LuaJ, 04 §4). Everything Lua must encode is an integer or 16.16 fixed point — no float32 packing, no `string.pack`, no bitwise operators — so one encoder works on 5.2, 5.3, 5.4, LuaJIT and LuaJ; `opengpu.lua` adds a `string.pack` fast path via `load()` only where `string.pack ~= nil` (04 §6). Handles are < 2^31 `Integer`s; the adapter uses `checkInteger` because LuaJIT and 5.2 deliver every number as `Double` (04 §1).

**Example (works unchanged on 5.3, 5.4 and OC-LuaJIT 5.2-compat):**

```lua
local component, computer = require("component"), require("computer")
local gpu, screen = component.opengpu, component.opengpu_screen
local char, floor, concat = string.char, math.floor, table.concat
local function u8(v) return char(v % 256) end
local function u16(v) v = floor(v) % 65536 return char(v % 256, floor(v / 256)) end
local function i16(v) return u16(v < 0 and v + 65536 or v) end
local function u32(v) v = floor(v) % 4294967296
  local b0 = v % 256; v = floor(v / 256); local b1 = v % 256; v = floor(v / 256)
  return char(b0, b1, v % 256, floor(v / 256)) end
local function fx(v) return u32(floor(v * 65536 + 0.5) % 4294967296) end  -- 16.16, wraps negatives

local VS = [[attribute vec3 a_pos; attribute vec4 a_col; uniform mat4 u_mvp; varying vec4 v_col;
void main() { v_col = a_col; gl_Position = u_mvp * vec4(a_pos, 1.0); }]]
local FS = [[precision mediump float; varying vec4 v_col; void main() { gl_FragColor = v_col; }]]

assert(gpu.bind(screen.address))
local W, H = gpu.getResolution()
local verts = concat{ fx(-0.6), fx(-0.5), fx(0), char(255, 0, 0, 255),
                      fx( 0.6), fx(-0.5), fx(0), char(0, 255, 0, 255),
                      fx( 0.0), fx( 0.7), fx(0), char(0, 0, 255, 255) }
local vbuf = assert(gpu.createBuffer(#verts, "vertex"));  assert(gpu.upload(vbuf, 0, verts))
local prog = assert(gpu.createProgram(VS, FS))
local pipe = assert(gpu.createPipeline{ program = prog, depth = "less", cull = "none", blend = "none",
  attributes = { {name = "a_pos", format = "fx16_16x3", buffer = 0, offset = 0,  stride = 16},
                 {name = "a_col", format = "unorm8x4",  buffer = 0, offset = 12, stride = 16} } })
local slot = gpu.getProgramInfo(prog).uniforms.u_mvp.slot
local function mvp(a) local c, s, ar = math.cos(a), math.sin(a), H / W     -- column-major rotation about Z
  return concat{ fx(c*ar), fx(s), fx(0), fx(0),  fx(-s*ar), fx(c), fx(0), fx(0),
                 fx(0), fx(0), fx(1), fx(0),      fx(0), fx(0), fx(0), fx(1) } end
local n, t = 0, 0
while true do
  local label = "frame " .. n
  assert(gpu.submit(concat{ "OGPU", u8(1), u8(0), u16(0),
    u8(0x01), u32(0xFF101020), fx(1), u8(3),          -- CLEAR colour + depth
    u8(0x10), u32(pipe),  u8(0x11), u8(0), u32(vbuf), u32(0),
    u8(0x14), u8(slot), u8(4), mvp(t),                 -- mat4 = 4 vec4 slots, 16.16
    u8(0x20), u32(0), u32(3),                          -- DRAW 3 vertices
    u8(0x33), i16(4), i16(4), u32(0xFFFFFFFF), u32(0), u16(#label), label,
    u8(0xFF) }))
  local id = gpu.present()
  if not id then computer.pullSignal(0.05)             -- "busy": yield, never spin (5 s watchdog, 04 §3)
  else
    repeat local name, _, fid = computer.pullSignal(1) until name == "opengpu_frame" and fid == id
    n, t = n + 1, t + 0.05
  end
end
```

Lua-side cost of this loop is dominated by `concat` of ~40 small strings (< 0.1 ms on 5.3, 04 §6); a 1,000-triangle resident mesh costs the same per frame because only uniforms change (07 §2).

## 4. Shader language and compiler

**Language: GLSL ES 1.00 subset** with Appendix-A restrictions made mandatory (06 §4): types `void bool int float vec2-4 bvec ivec mat2-4 sampler2D`; `attribute/uniform/varying/const`; precision qualifiers parsed and ignored; `for` loops only with constant bounds and an unassigned index; no `while`, recursion or dynamic indexing; built-ins `gl_Position gl_PointSize gl_FragCoord gl_FrontFacing gl_FragColor`, `discard`, `texture2D`; a `kernel` entry point with `global_id()` for compute. GLSL ES rather than a custom language is what makes the later GL backend cheap: the same source is re-emitted as `#version 330 core` (02 §5: literal `core` token, no identifiers named `sample`, `new`, `sampler`).

**Pipeline.** Hand-written lexer → Pratt parser → typed AST → semantic checks and static instruction count (loop bounds are constants, so the count is exact) → `ProgramIR` (scalarized: every `vecN` is N named float temporaries, 06 §3 "no allocation in the inner loop"). Executors:

1. **Interpreter** (`:core`, reference): tree-walking over the typed IR; always available (ocelot tests, viewer, fallback if class definition fails); ~100–500 ns per shaded element (07 §1).
2. **JIT** (`:jit`): ASM `ClassWriter(COMPUTE_FRAMES)` with a `getCommonSuperClass` that loads nothing, `V1_8` classes declared `strictfp`, one class per (program × pipeline state) so every per-pixel call site stays monomorphic (06 §4: megamorphic dispatch halves a flat pixel and deoptimizes the shared loop on JDK 25); generated methods < 6 KB bytecode (HotSpot's 8000-byte `HugeMethodLimit`), with the fragment body split out when larger. Classes are defined through a private `ClassLoader` subclass parented to the mod's loader (OC-Wasm's `Compiler.java:190-204`; no `Lookup.defineClass`, no `Unsafe`, so Java 8 and 17–25 both work, 05 §1), one loader per program, cached by source+state hash; never persisted, always re-derived from saved source (05 implication 2). Target ≤ 10 ns per element (07 M0.3) vs CCLights2's ~10 ns/op interpreted (01 §3).
3. **GLSL 330 core emitter** (M4) for the hardware backend.

**Limits and safety.** Source ≤ 16 KB; ≤ 4,096 static scalar ops per fragment, ≤ 16,384 per vertex, ≤ 65,536 per compute work-item; ≤ 4 samplers, ≤ 64 uniform vec4 slots, ≤ 16 varyings; loops ≤ 1,024 iterations, nested ≤ 3 deep; generated code may only contain arithmetic, locals, loads/stores on engine-owned arrays and `StrictMath` calls — no reflection, no `invokedynamic`, no field access outside a tiny `shade.rt` package. Every generated class passes `CheckClassAdapter` and the JVM verifier in tests, and a grammar-driven fuzzer compares interpreter vs JIT (05 §4). Because the instruction budget is static, no runtime counter is needed in the inner loop; a frame's total cost is bounded by the tier caps in §6.

**Determinism rules** (06 §6): `strictfp` on all core math (free on x86-64, mandatory semantics on Java 17+); transcendental built-ins through `StrictMath` (`Math.sin` is only 1-ulp and intrinsic-dependent, `atan2` 2 ulp); 28.4 fixed-point edge functions with the top-left rule and pixel centres at +0.5; `float` depth compared before shading; blending with the integer `(x + (x>>8)) >> 8` approximation; tiles own their pixels and bins are built serially, so results are independent of thread count. The conformance suite asserts byte-identical frames for the same stream on 1 vs N threads, JDK 8 vs 17/21, server vs mirror; the GL backend is held to a tolerance (≤ 2 LSB per channel on ≥ 99 % of pixels, differences allowed only on triangle edges).

## 5. Server/client responsibilities, networking, persistence, threading, Angelica, Java matrix

**Threading.** Direct callbacks never render: they call `context.consumeCallBudget` *before* any side effect (the `LimitReachedException` retry re-issues the same call on the server thread next tick, 00 §1 finding 3), validate, copy the `byte[]` (20–40 µs per 100 KB, 07 §2) and enqueue a job under the device monitor — microseconds, so OC's shared 4-thread "Computer" pool is never blocked by rendering (07 §3). An OpenGPU-owned `ForkJoinPool` sized `max(1, cores/2)` (precedent: the Internet card's pool, `InternetCard.scala:192`) executes the ordered queue per device: uploads mutate resources only on this pool (no copy-on-write needed), frames run the command stream with tile-parallel rasterization, then CRC32, dirty-tile diff and deflate. The server thread does only hand-offs in `update()`: pop finished frames, encode per viewer, send, emit the coalesced signal — 5–20 µs per display per tick (07 §3). Readback reads the last *completed* frame snapshot. Frames in flight ≤ 2; `present` returns `nil,"busy"` beyond that and repeat offenders get `context.pause()` like `bitblt` (`GraphicsCard.scala:241-253`). Lua-to-pixels latency is ≈ 35–60 ms on LAN (07 §1); sending from the pool via `scheduleOutboundPacket` is a later, measured optimization (03 open question 4).

**Networking.** One `FMLEventChannel` "OpenGPU"; handlers run on the main thread on both sides in 1.7.10 (03 §6), so the client handler only copies the payload, releases the `ByteBuf` and hands it to a decode worker. Packet layout:

```
S->C: u8 type {KEY, TILES, CMDS, RESOURCE, DISPLAY_INFO}  u32 displayId  u32 seq  u32 crc32  u8 flags(bit0 deflate)  payload
  TILES: u8 ncopy {i16 sx,sy,w,h,dx,dy}[ncopy]  bitset(dirty tiles)  tile data 16x16 in wire format (1 B idx / 2 B RGB565)
  CMDS : u16 nres {RESOURCE record: u32 handle u8 kind u32 generation u32 len bytes}[nres]  u32 cmdLen  cmd bytes
  KEY  : full frame in wire format (+ palette); split by rows into <=256 KB packets
C->S: u8 type {SUBSCRIBE, RESYNC(lastSeq), TOUCH, DRAG, DROP, SCROLL}  u32 displayId  ...  (always < 32 KB)
```

Recipients are chunk watchers within 64 m (03 implication 5), not OC's effectively unbounded screen broadcast (01 §1). The encoder keeps a `ViewerState` per (viewer, display): resource residency generations (`CMDS` frames ship only resources that viewer lacks, improving on OC's `TextBufferRamInit`, which re-ships whole pages, 01 implication 9), `lastSeq`, and a token bucket (16 KB/tick ≈ 320 KB/s per viewer, 07 risk 6). Over budget, frames are *dropped for that viewer*, never split into many packets; a `KEY` follows later. Keyframes on subscribe, CRC mismatch, every 30 s, and after `setResolution`/palette changes. Packets stay ≤ 256 KB (far under Forge's 2,097,050-byte server→client cap; Hodgepodge's 256 MiB is not relied on) and client→server never exceeds 32,766 bytes (03 §3).

**Persistence.** Card item NBT holds only tier, bound screen, palette and the handle table (type, dimensions, format, length) — a few KB. Resource bytes and the last presented frame go to an OpenGPU-owned side-file store `<world>/opengpu/state/<dim>/<cx>.<cz>/<address>`, written asynchronously from a snapshot taken under the device lock, mirroring `SaveHandler.scheduleSave` without depending on OC internals (00 §5). `writeToNBTForClient` writes only `displayId`, resolution and tier, and the saver checks `SaveHandler.savingForClients` itself, avoiding OC's bug where every screen description packet schedules a disk write and pauses computers (00 §5). In-flight jobs are dropped at save and surface as `opengpu_frame(..., "dropped")`; missing side files on load invalidate all handles and raise `opengpu_reset` (04 implication 9). Lua holds only integers, so OC-LuaJIT's whole-state Eris serializer never sees an OpenGPU object and a program suspended between `submit` and `present` resumes correctly (04 §6); on LuaJ the same handles plus `reset()` on `computer.stopped/started` (`GraphicsCard.scala:538-545`) keep it correct.

**Angelica/Iris measures** (02 §5 baseline): the client framebuffer is a CPU `int[]`; the GL texture is touched only on the render thread, once per frame (frame-counter keyed, so the Iris shadow pass cannot double-upload), with `glTexSubImage2D(GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV)` of dirty rows into a texture allocated once (`GL_RGBA8`, `GL_NEAREST`, `GL_TEXTURE_MAX_LEVEL=0`) — OC's `DynamicFontRenderer` path, proven under Angelica (02 §3). The TESR copies `ScreenRenderer`'s placement/bezel/fade logic, pushes only the five attrib bits it changes (stack depth is 32 in 2.2.21), sets fullbright lightmap coords, draws one Tessellator quad and restores everything; no display lists, no `glBegin`. The chassis is a stateless ISBRH annotated `@ThreadSafeISBRH(perThread=false)` (OC's `BlockRenderer.scala:16`), which also keeps the TESR out of Iris's shadow pass under the default config (02 §2). `getRenderBoundingBox` and `getMaxRenderDistanceSquared` are constant from the first call because Celeritas classifies per class and caches per instance (02 §3, 03 §1). Angelica is `compileOnly …:2.2.21:api`, optional at runtime. The M4 GL backend renders off-screen in `RenderWorldLastEvent` into its own FBO/VAO with `#version 330 core` shaders and exact `glUseProgram(prev)` restore, and falls back to the software mirror on link failure (02 §5; 07 risk 1).

**Java 8 + LWJGL2 and Java 17–21 + lwjgl3ify.** Java 8 bytecode everywhere (jabel); GL only through the LWJGL2 `GL11/GL12/GL15/GL20/GL30` surface, which lwjgl3ify redirects and GLSM maps (02 §4); capability probing via one final `GlCaps` class reading LWJGL2's `GLContext.getCapabilities()` plus `OpenGlHelper` flags on the render thread — the same code serves Java 8 and lwjgl3ify, whose `org.lwjglx` shim returns a process-wide snapshot that Angelica's GLSM reads too; no `glGetString` branching, never `@Lwjgl3Aware`, only fields present in LWJGL 2.9.4, the shim and LWJGL 3.4.3 (03 implication 8 as amended; 14 §3); generated classes defined by a child of the mod's loader (`LaunchClassLoader` or `RfbSystemClassLoader`). CI: `runClient` (J8/LWJGL 2.9.4, the user's instance) and `runClient21` (lwjgl3ify 3.0.33), each with Angelica on/off and once with a shader pack; SDL-GPU only on the J21 leg (02 §4).

## 6. Tiers, caps, abuse protection, expected performance

Tiers follow 07 §5 (all 16:10, integer fractions of OC's 1280×800 T3 text raster); the renderer is always ARGB8888 internally and quantizes at present time, so shaders and blending are tier-independent:

| Tier | Display | Wire format | FPS cap | VRAM | Frame caps (tris / fragments after early-Z / compute work-items) | submit ≤ | readback ≤ |
|---|---|---|---|---|---|---|---|
| T1 | 160×100 | 8-bit programmable palette | 10 | 256 KB | 20 k / 0.5 M / 1 M | 64 KB | 16 KB/call |
| T2 | 320×200 | 8-bit palette | 20 | 1 MB | 100 k / 2 M / 4 M | 128 KB | 32 KB/call |
| T3 | 640×400 | RGB565 | 20 | 4 MB | 300 k / 6 M / 16 M | 256 KB | 64 KB/call |

Palette quantization uses a 32 K-entry RGB555→index LUT rebuilt on `setPalette` (default 6×6×6 cube + 40 greys, optional ordered dither), ~1 ns/px. Call-budget charges (OpenGPU opts in via `consumeCallBudget`, as OC's GPU does, 06 §1): `submit` 1/32·2^-tier + len/(1 MB); `present` 1/8, 1/16, 1/32; simple-2D ops 1/64, 1/128, 1/256; readback 1/4 per 64 KB; `createProgram` 1/4 (frontend ≤ 1 ms for 16 KB of source); `upload` len/(256 KB). With installed budgets `[1,2,4]` (`OpenComputers.cfg:143-147`) or defaults `[0.5,1,1.5]` none of these limit a sane program; they make an abusive one stall itself one tick per overrun rather than the server (07 §2). Hard caps do the real work: the per-frame and per-dispatch limits above, a 10 ms per-device time slice per tick after which the frame aborts with `"error:budget"`, ≤ 2 frames in flight, per-viewer token buckets, a configurable active-display ceiling, NaN/Inf rejection at decode, a program-hash cache against recompile storms, and generation handles (stale handle → error, never a crash).

**Expected cost (06 §3 measurements ±20–30 %; 07 §1 model).** Server CPU per frame: 2D UI 0.2–0.5 ms at 320×200, 0.8–2 ms at 640×400; 1–2 k textured triangles 1.5–3 ms (T2) / 6–11 ms (T3) single-threaded, 2–4× less on the pool; a 64 k-element compute kernel 0.15–0.65 ms JIT / 6–30 ms interpreted; CRC32 ≈ 0.1 ms; tile diff 0.06–0.25 ms; deflate ≤ 1.3 ms (T2) / 5.1 ms (T3) only when most tiles changed. Ten T3 3D displays at 20 fps ≈ 2–3 cores of pool time, the worst case the FPS cap and time slice allow. Memory per display ≈ 0.6 MB (T2) / 2.5 MB (T3) plus resources. Bandwidth per viewer: 2D UI 5–10 / 20 / 80 KB/s by tier in `TILES` mode (repaint bursts 25–40 KB at T2, 200–340 KB at T3); 3D in `CMDS` mode 4–40 KB/s with resident meshes; the per-tick choice guarantees the viewer never pays more than the cheaper of the two. The client mirror costs the same raster numbers per *visible* display on a worker thread, plus one `glTexSubImage2D` of ≤ 1 MB (0.1–0.5 ms, 03 §1).

## 7. Success criteria, headless testing, milestones

**(a) Lua 5.3/5.4 and LuaJIT.** Byte strings in, byte strings/scalars out, integer handles < 2^31, no `string.pack`, no bitwise syntax, no `Value` userdata, no `__gc` reliance, `checkInteger` everywhere (04 implications 1–8); LuaJ works at "correct, not fast" level for free. The ocelot harness runs every Lua test under `NativeLua53Architecture` (the GTNH in-game default, 05 §3), 5.2, 5.4 and OC-LuaJIT, each pinned with `setArchitecture`.

**(b) Modularity.** The stream spec is the contract; `Backend`, `ShaderExecutorFactory`, `FrameSink`, `Scheduler` are the plugin points; the core has zero MC/OC/LWJGL imports and three adapters from day one.

**(c) Angelica.** §5 measures; CI legs with Angelica 2.2.21 on J8 and latest on J21, `-Dangelica.unmappedGL=STRICT` plus a grep for `GLSync` (the family STRICT ignores, 02 §4), `pinnedGLVersion=33` once.

**Headless testing under ocelot-brain** (05 §3): `OpenGpuEntity extends Entity with Environment with DeviceInfo` (Scala 2.13) exposes the same `@Callback`s, delegates to `core.Device`, registers a constructor with `NBTPersistence`, and publishes frames to an in-memory `FrameSink`. Tests boot a `Case` with CPU/RAM/EEPROM (a 4 KiB BIOS calling `component.proxy(component.list("opengpu")())`), poll `machine.isRunning/lastError` rather than tick counts, and assert golden-PNG equality of the presented frame; CRC equality between the server `Device` and a `Mirror` fed the encoded packets (`TILES` and `CMDS`, 1 vs 8 pool threads); budget behaviour under `callBudgets=[0.5,1,1.5]` and `[1,2,4]`; `ws.save/load` round trips with handles held by a suspended Lua loop (OC-LuaJIT included); and the M0 per-call and encoding measurements per runtime. `:core` adds golden images, JDK 8 vs 17 determinism, interpreter-vs-JIT shader fuzzing, `CheckClassAdapter` on generated classes and JMH; `:stream` has codec and token-bucket tests; the viewer replays any recorded stream.

**Milestones (single developer, engineer-weeks, ±50 %).**

| | Scope | Exit criteria | Effort |
|---|---|---|---|
| M0 vertical slice | `:core` skeleton (handles, decoder, `CLEAR/FILL_RECT/BLIT/TEXT`), `Device` + pool, `:stream` `TILES`+`KEY`, OC card/screen with `submit/present/bind`, TESR under Angelica on J8 and J21, ocelot entity + harness, `opengpu.lua` encoder | 320×200 pattern + sprite visible on both JVM legs; ≤ 2 KB/frame; ≤ 1 ms server-thread time per tick for 10 displays; 1,000-triangle encode ≤ 5 ms on 5.3 / ≤ 2.5 ms on LuaJIT; per-call overhead measured (07 M0) | 3–4 w |
| M1 2D product | palette, blend modes, scissor, `COPY`, built-in font (unscii-asie licence check or Unifont, 06 §2), simple-2D callbacks, input + GUI, side-file persistence, viewer, golden tests | OpenOS-style UI at 20 fps, 20 KB/s per viewer; save/load survives | 3–4 w |
| M2 3D + shaders | tile rasterizer, buffers/textures/pipelines, GLSL-ES frontend + interpreter, `CMDS` transport with residency + CRC resync, client mirror | textured 1 k-tri scene at 20 fps on T2 under 3 ms server CPU, ≤ 40 KB/s per viewer, identical CRC server vs client | 6–8 w |
| M3 JIT + compute + hardening | ASM backend, `DISPATCH/readBuffer`, all caps, token buckets, time slices, LuaJ pass, fuzzing | ≤ 10 ns/element, caps abort cleanly, fuzz clean for 10^5 programs | 4–6 w |
| M4 optional | GL backend (GLSL 330 emitter, FBO, conformance suite), multi-block screens, `api.internal.TextBuffer` bindability (compile against ≥ 1.12.58, 00 §3) | conformance ≤ 2 LSB; runs under an Iris pack and the SDL-GPU leg | 6–8 w |

M0–M3 ≈ 16–22 weeks; the first shippable 2D release is after M1.

## 8. Risks and honest weaknesses

1. **Double execution.** The server renders every frame even when nobody watches (readback, persistence and keyframes need the pixels), and in `CMDS` mode each client renders again. A player looking at five T3 3D displays spends 30–55 ms per tick on a client worker thread; FPS caps and distance-based unsubscribe are the only relief until the GL backend lands. This is the price of the bandwidth numbers in §6.
2. **Determinism is load-bearing.** Any divergence between server and mirror (a `Math` intrinsic, a non-`strictfp` path, a thread-order-dependent write) is visible corruption in `CMDS` mode. The CRC resync bounds the damage to one keyframe; the conformance suite must run on every supported JDK.
3. **Transport complexity.** Per-viewer residency, two encodings, keyframe scheduling and token buckets are more code than OC's single `TextBufferMulti` builder, and bugs are multiplayer-only. Mitigation: `:stream` is pure Java and testable with a simulated viewer set.
4. **Software 3D ceiling.** At T3 a textured scene costs 6–11 ms per frame per display on one core (06 §3); the pool helps 2–4×, but busy public servers need the config ceilings, and Java 8 gets no SIMD (06 §3).
5. **Compiler is a sub-project.** Lexer, parser, type checker, interpreter, ASM backend, fuzzing and later a GLSL emitter: M2+M3 are 10–14 weeks on their own, and a megamorphic-dispatch regression is easy to introduce when extending the rasterizer.
6. **Deferred GL risk.** Angelica/Iris exposure is minimal for the baseline (one upload, one quad, 07 risk 1), but the hardware backend inherits the full GLSM/Iris/SDL-GPU obligation list (02 §5) and may never be worth it if the mirror is fast enough; M4 is honestly optional.
7. **API breadth.** Resources + pipelines + command stream is a bigger surface than `gpu.set`; the simple-2D callbacks and `opengpu.lua` keep the first program short, but 3D users must learn the stream format.
8. **Unmeasured constants.** In-machine Lua encoding times and 3D deflate ratios are estimates (07 §1-§2); M0 measures them before the tier table is frozen. The per-call figure no longer is one: 11 §2 measured 4.2–4.5 µs empty / 5–9 µs with scalar args on PUC Lua and 1.5–2.4 µs on OC-LuaJIT, inside the old 5–20 µs range and well below its top end.
9. **Smaller items.** ocelot-brain tracks OC 1.8.9a upstream and needs a Scala adapter built from a vendored jar (05 §3); the side-file store duplicates `SaveHandler` logic rather than depending on an internal class (07 risk 10); the stale OCLights2-derived `config/OpenGPU.cfg` collides with modid `opengpu` on NTFS, so OpenGPU must use a distinct config file name (07 risk 11).

## Verification notes

### Amendments after gap-fill (2026-10-08)

- **Keyboard attachment (from 15 §1.10, §2).** §3's "`SidedEnvironment` accepting a keyboard on its front face" was half of `Screen.scala:63-67`: the display offers its node on the five non-front faces to anything and on the front only to an adjacent OC keyboard, so keyboards attach on any face and the front is keyboard-only. Corrected in place.
- **Capability probing (from 14 §3).** §5's "`glGetString` behind an interface because the capabilities object differs between LWJGL majors" is superseded: `GLContext.getCapabilities()` plus `OpenGlHelper` flags work on both legs through lwjgl3ify's `org.lwjglx` shim; one final class, no `glGetString` branching, never `@Lwjgl3Aware`, read only fields present in LWJGL 2.9.4, the shim and LWJGL 3.4.3. Corrected in place.
- **Per-call overhead (from 11 §2).** Risk 8's "5–20 µs per-call figure" is now measured (4.2–4.5 µs empty, 5–9 µs scalar on PUC Lua; 1.5–2.4 µs on OC-LuaJIT); the risk entry now lists only the constants that remain unmeasured.
