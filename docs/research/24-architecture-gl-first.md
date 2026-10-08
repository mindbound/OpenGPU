# 24 — Architecture and plan, GL-first (Option 2)

Synthesizer, 2026-10-08. Supersedes 09 §3–§6 where they conflict; 09 §1–§2 stay as the record of the earlier decision. Inputs: the owner's decisions below, reports 20–23, and 09 with its sources (00–15, 19). Citations: `NN §x` for reports, `path:line` for code. OC paths are relative to `C:\Users\astro\Downloads\OpenComputers-GTNH\src\main\scala\li\cil\oc\`; the `Machine.scala` and `Keyboard.scala` lines cited are identical at the 1.12.64-GTNH tag (scratchpad `ocgtnh-master`, 1e4559f), where `InputBuffer.scala` is cited. Tags: **[est.]** estimate, **[I]** inference, **[proposal]** a default set here. Version scope: Java 8 + LWJGL 2.9.4 + Forge 10.13.4.1614, Angelica 2.2.8 or later present (2.1.14–2.2.7 best-effort; 25 §1.1) or absent; nothing depends on lwjgl3ify.

## 1. Decisions (2026-10-08)

1. **Goal:** rough parity with modern "retro" engines: low-to-mid-poly 3D with shader-based transform and lighting, Minecraft-scale. Compute serves graphics and stands alone for in-game work on one or many cards (e.g. racked OC servers evaluating HBM NTM reactor designs).
2. **Audience:** single player first, LAN optional; public and dedicated servers considered, low priority.
3. **Rendering is GL-authoritative on the host.** The host client's GPU renders 2D and 3D offscreen; Lua readback returns exactly what it produced (PBO readback); LAN guests receive the host's pixels; image tests are tolerance-based. No software renderer is planned (deferred, §5).
4. **Compute runs on the server CPU** wherever game logic runs, dedicated servers and racks included: kernels compiled to JVM bytecode with shaded, relocated ASM 9, deterministic, in GLSL compute-shader syntax with OpenGPU semantics.
5. **Graphics shaders** (changed 2026-10-09): a GLSL ES 3.00-based subset (`#version 300 es` required) with Appendix-A-style bounded `for` loops, simulated trip counts, static costing and caps; integer results equal the compute language's (22 §2.3) through emitter guards; output `#version 330 core`. Specified in 27, which supersedes the ES 1.00 subset of 22 §1.
6. **Hardware:** a new card and a new display block; M0's display is a cube with one screen face; more block designs later.
7. **Tiers:** T1 160×100 index8 @ 10 fps, T2 320×200 index8 @ 20, T3 640×400 RGB565 @ 20. 2D on one 192 KB stick; 3D assumes ≥ 1 MB.
8. **Stock OC GPUs as a text console on the pixel screen:** later.
9. **Angelica optional but first-class.** lwjgl3ify is not designed for; only its two footguns are avoided (`@Lwjgl3Aware`/`Lwjgl3ify-Aware`; `org.lwjgl` class lookup by string); no CI gate.
10. **OC-LuaJIT first-class**; the owner adds `string.pack/unpack` and eager JNLua table-proxy release. One API: 5.3 and 5.2 must pass, OC-LuaJIT first-class, 5.4 user-instance-only, LuaJ correctness-only.
11. **Input:** precise touch; no OC keyboard dependency; right-click opens OpenGPU's own GUI for keyboard, mouse and clipboard.
12. **Conventions:** `modGroup io.github.mindbound.opengpu`, published `-api` jar, Scala 2.13 test-only ocelot-brain harness, GTNH shared workflow plus a Java 8 leg, x86-64 only.
13. **Display block** (decided after this document was first written): screen walls follow OC's model, identical display blocks of one tier merging into a rectangle that any block can extend, with no extender block. The final shape follows Energy Control's Advanced Info Panel: six facings with a rotation on floors and ceilings, thickness 1–16 px, and horizontal and vertical tilt as one plane across the whole wall. Shape settings are edited in a settings tab of the OpenGPU GUI and apply to the whole wall. Energy Control (GPL-3.0) is a clean-room design reference only; OpenGPU is MIT.
14. **OpenComputers runtime floor** (2026-10-09): 1.12.64-GTNH, the version GTNH 2.9.0 ships; GTNH 2.8.x (OC 1.11.20, Angelica 1.x) is unsupported. The owner notes that GTNH's OC changes from version to version touch OpenGPU very little, so the floor can be lowered later if a reason appears.
15. **Showcase shaders are examples, not mod API** (2026-10-09): the retro shader set and other showcase shaders ship as Lua-level examples that user programs load, hosted in this repository's `examples/` directory for now (a separate repository later if they grow), not as a built-in library inside the mod. The mod keeps only the internal shaders its own passes need.

## 2. What changes relative to 09

### 2.1 Carried over unchanged

| 09 section | What stays | Why |
|---|---|---|
| §3.2 components | card `opengpu` (`DriverItem`, `Slot.Card`, `Visibility.Neighbors`, `HostAware` Case/Server, FML `init`) + display block | independent of where pixels are made; `Neighbors` keeps rack cards private (22 §4) |
| §3.2 boundary | integer handles; byte-string command stream v1; JIT-friendly `string.char` encoder; table arguments only at configuration time (`PipelineDesc`, and now the compute-list descriptor of §3.3), copied at once, never per frame or per dispatch. One amendment: OC-LuaJIT is detected by OpenGPU's exported marker or a behavioural probe, never by the absence of `string.pack` (09 §3.2's heuristic), because decision 10 adds `string.pack` to OC-LuaJIT. A second amendment (26 G1): handles are `type(4)\|slot(11)\|generation(16)` instead of `slot\|generation`, below 2^31 for OC's `Integer` marshalling, with type 0 reserved so 0 stays invalid; the validator, `free` and error messages decode the type ("handle 0x… is a buffer, expected a texture"), and the type bits persist with the handle table. 2 048 slots per type per device exceed what tier VRAM allows | 04, 10, 11 unchanged; decision 10; 26 §1.2 |
| §3.2 runtimes, budget charges | as written, tuned for `[0.5,1,1.5]` | decision 10 |
| §3.2 signals | coalesced; `AtomicReference<Context>` + epoch; `Long`/`String`/`Double` args (`Boolean` also persists, `server/machine/Machine.scala:866`) | OC unchanged |
| §3.4 transport | pool sends via `scheduleOutboundPacket`; ≤ 256 KB packets; C2S ≤ 32 766 B; `(displayId, generation, frameId)`; recipients; C2S validation | now for LAN guests and server viewers (21 §4.1) |
| §3.5 OC worker, server thread | ≤ 0.2 ms per call, never render, `busy` at the third unconfirmed frame, `waitFrame` yields | 21 §2.1 |
| §3.6 base | metadata-only NBT, side files, hash-gated async writes | 21 §6 |
| §3.7 display | `ScreenRenderer`-style TESR, ISBRH chassis, render bounds fixed from the first call, no `@Lwjgl3Aware` | re-checked, 20 §4 |
| §3.3 JIT rules | relocated ASM 9, `V1_8`, child loader per program, no indy/reflection/allocation, `CheckClassAdapter`, fuzzer | now the compute backend (22 §3) |
| §3.8 conventions | `modId opengpu`, `apiPackage = api`, `config/opengpu/opengpu.cfg`, jabel `--release 8`, relocated shadowed deps | decision 12 |

### 2.2 Superseded

| 09 | Replaced by | Reason |
|---|---|---|
| §1/§3 server software core renders | host GPU renders; server owns *confirmed* pixels after readback (§3.6) | decision 3 |
| §3.1 lazy rendering of unwatched displays | host executes every device; laziness only in readback coalescing and guest encoding | 21 §2.4 |
| §3.1, 12 §4 local-channel `TILES` to the host | in-JVM `RenderHost` SPI | packet drain is tick-bound and pause-gated (21 §1.4) |
| §3.2 `readPixels` may be `"pending"` | newest confirmed frame + `frameId`, never pending | 21 §6 |
| §3.3 one grammar; single write-only kernel output | two languages; rules D1–D5 | 22 §2 |
| §3.3 scalarised IR for all emitters | TIR → GLSL; SIR → bytecode, interpreter | 22 §3 |
| §3.3 graphics determinism contract | compute only; graphics uses tolerance tiers | decision 3, 23 §3 |
| §3.5 pool rasterizes, `cores/2` threads | pool folds, encodes, signals, persists, computes; `min(4, max(1, cores/4))` | 22 §4 |
| §3.6 in-flight jobs dropped at save | confirmed state + tail replayed on load | 21 §6 |
| §3.7 no GLSL/FBO; `OpenGlHelper`; orphaning instead of sync; 02 §5 / 08-B §5.5's `RenderWorldLastEvent` alternative | MC-free render core on LWJGL `GL11`–`GL33`; timestamp-query completion; `RenderTickEvent(START)` only | 20 §1–3, 23 §2.1 |
| 05 §4 harness on JDK 17 | Java 8 | 23 §4.3 |
| 22 §1 ES 1.00 subset | 27's ES 3.00-based subset | decision 5 changed (2026-10-09) |

### 2.3 Dropped or deferred

| Item | Status | Reason |
|---|---|---|
| Client mirror, CRC resync (12) | dropped | guests get host pixels |
| Lossy lineage, LOD governor, internet buckets, `TILES`/`CMDS` estimator | dropped | public servers low priority; LAN lossless under a per-guest cap (21 §4.3) |
| `CMDS`/`RESOURCE` to viewers | deferred, ids reserved | only display-only viewers need it (21 §5) |
| Bit-exact graphics; CPU rasterizer as performance path | deferred (§5) | decision 3 |
| aarch64 leg | dropped | decision 12 |
| OC keyboard topology (15 §2: front-face keyboards, forwarding, `hasKeyboard`) | dropped | decision 11 |
| `runClient21`/lwjgl3ify leg, `ContextCapabilities` field-set test | dropped | decision 9 |
| `:viewer` Swing app, virtual client | dropped | no `CMDS` consumer; `:gl-testkit` and self-test replace it |
| GL 3.0/`#version 130` backend, `SoftwareClientBackend` (08-B §5.5–5.6) | dropped | 20 impl. 10 |
| `TextBuffer` binding; item-hosted displays | deferred / not planned | decision 8; 15 §3. Multi-block walls are no longer deferred: M1.5, decision 13 |

## 3. Architecture

### 3.1 Modules

GTNHGradle on the root; project classes shaded unrelocated, external libraries relocated (09 §3.8). Render-core classes ship in the mod jar and load through `LaunchClassLoader`, so GLSM redirects them (02 §1).

| Project | Depends on | Contents |
|---|---|---|
| `:core` (release 8; no MC/OC/LWJGL) | — | typed handles (26 G1), resources and VRAM accounting, command codec and validator (backend-neutral byte records), `Device` (op tail, confirmed state, fold), `RenderHost`/`ReadbackSink` SPI with `NoRenderHost`/`FakeRenderHost`, compute scheduler |
| `:lang` | `:core` | one GLSL-family frontend with a graphics profile (ES 3.00 subset, 27) and a compute profile (GLSL 4.30 surface, 22 §2), sharing preprocessor, lexer, parser, typer, `BuiltinInfo`, constant evaluator, integer intrinsics, loop simulation and costing (27 §8.4); TIR/SIR, `#version 330 core` emitter, SIR interpreter; the shared preprocessor has `#define`/`#if` and `#line` (for error positions in composed sources), no built-in libraries and no `#include` (decision 15) |
| `:jit` | `:lang`, ASM 9 (relocated) | SIR → `V1_8` bytecode, whitelisting child loader |
| `:gl` (compileOnly LWJGL 2.9.4) | `:core`, `:lang` | render core: `Fbo`, `Program`, `Vao`, `PboRing`, `GpuTimer`, op executor, quantize/expand passes, state save/restore, `GlLedger` (every `glGen*`, `glTexImage*`, `glBufferData` and `glRenderbufferStorage`, keyed `(session, deviceId, kind, label)` → bytes and count; 26 G10); LWJGL `GL11`–`GL33` only, no `net.minecraft.*`/`OpenGlHelper` (23 §2.1) |
| `:stream` | `:core` | 16×16 tile diff, deflate, `KEY`/`TILES`/`CATCHUP`, per-guest caps |
| root `opengpu` | shades the above; compileOnly OC `1.12.64-GTNH:api`, Angelica `2.2.8:api` (the floor, 25 R-11) | driver, card, display, callbacks, channel, pool sender, side files, `ClientProxy` → `GlRenderHost`, TESR, GUI, `GlCaps`; `api` package → `-api` jar (component interfaces, caps/status constants, command-format version and opcodes); from M1 the build generates `api-dump.json` (callbacks and argument kinds, signals, opcodes and record layouts, command-format version, `getCaps` keys, status strings), diffed in CI against the last release, and side-file loading dispatches on the command-format version (26 G12) |
| `:gl-testkit` (Java 8) | `:gl` | `GlContextExtension` (compat; core 3.3 under GLSM from `Angelica:2.2.8:dev` and `2.2.28:dev` (25 §6) + `jvmdowngrader-java-api:1.3.5:downgraded-8` + GLSM's own transitive dependencies, 23 verif. 5), `GlTestHost`, hygiene assertion, `ImageCompare` (23 §2–3) |
| `:ocelot-harness` (Scala 2.13, Java 8) | `:core`, `:lang`, `:jit`, `:gl-testkit`, ocelot-brain built for Java 8 | `OpenGpuEntity`, `NoRenderHost` as the default host (23 calls it `NullRenderHost`; one class, one contract: 21 §5 (a), so `readPixels` returns the confirmed image rather than 23 §4.1's `nil, reason`), `GlTestHost` for `gl` tests, 19's rules (23 §4) |
| `src/selftest` (dev only) | root | `/opengpu selftest`, Lua self-test suite (23 §5.2) |

A bytecode test asserts that no class outside `opengpu.client.*` and `:gl` references `org.lwjgl.*`, `net.minecraft.client.*`, `opengpu.client.*` or `:gl`'s package, and that no class names an `org.lwjgl` class in a string constant (decision 9's second footgun), since the dev `runServer` classpath contains LWJGL and would hide a leak (21 §1.3; 23 risks) [proposal].

### 3.2 Data flow

Single player (one JVM; the client thread is Minecraft's main thread):

```
OC worker (direct call)         OpenGPU pool                      client thread, RenderTickEvent(START)
budget, validate, own byte[]
present() -> frameId --op--> Device tail (immutable) --offer--> GlRenderHost, round-robin within budget:
  ^                                                               execute -> R8UI/R16UI surface
  |                                                               expand  -> RGBA8 texture -> TESR, GUI
  |                                                               glGetTexImage -> PBO ring + GL_TIMESTAMP
  |                       fold tail up to N  <-- onReadback(N, byte[]), non-blocking
  +-- opengpu_frame(N,"done")  confirmed state -> side files (async)
                          compute (bytecode) -> UploadOp into the same tail
```

LAN host, additionally:

```
pool: diff vs last confirmed frame -> deflate (L1 3D, L6 UI) -> scheduleOutboundPacket -> each guest
      (host's own local channel excluded); join or lag: keyframe from confirmed pixels / CATCHUP
guest: copy -> decode worker -> CPU expand (host's function) -> glTexSubImage2D at START (GL 1.2)
```

Dedicated server:

```
OC worker -> Device + NoRenderHost: present -> nil,"no renderer"; readPixels -> persisted confirmed image
pool: compute, readBuffer, persistence; confirmed image -> keyframe -> viewers (LAN path)
```

### 3.3 Lua API deltas (to 09 §3.2)

- **`getCaps()`** gains `renderer` (`"host-gl"`/`"none"`; `"viewer-gl"`, `"software"` reserved), `readback`, `graphics` (nil or a reason: `"dedicated server"`, `"host GPU below GL 3.3"`, `"GLES profile untested"`, `"disabled by config"`, `"disabled after GPU timeout"`, `"host GPU on device list"`; the last from M1, §3.7) and `hostRenderer` (`GL_RENDERER`, for bug reports) (20 §7, 21 §5, 23 Q8). On LAN the host decides for everyone.
- **`present()`** → `frameId` or `nil` with `"busy"`, `"no renderer"`, `"host render stalled"` (heartbeat silent > 2 s); never blocks (20 §5).
- **`opengpu_frame(card, frameId, status)`**: `"done"` (confirmed server-side), `"superseded"` (frame mode only, completed with a newer frame's pixels, 21 §2.4), `"dropped"` (unconfirmed after 1 s or epoch change), `"no renderer"`. Canvas mode never skips.
- **`setFramePolicy("latest" | "every")`** (frame mode only; owner answer to §7 Q3). `"latest"`, the default, lets the host skip to the newest presented frame and report older ones `"superseded"`, which keeps latency lowest on a slow host. `"every"` executes every presented frame in order, as canvas mode does, so the program is throttled by the existing back-pressure instead: `present` returns `"busy"` at the third unconfirmed frame. It costs at most two frames of extra latency on a slow host and no new machinery, because canvas mode already queues every frame. A server config key sets the default policy for new devices.
- **`readPixels`** returns the newest confirmed frame and its `frameId`, never pending; new devices start cleared at `frameId = 0` (21 §6). The bytes equal the host FBO's; host and guests display a pure function of them (20 §3). 2D readback is exact on conformant drivers by construction; shaded 3D is host-dependent and must not be hashed (23 impl. 3–4). Offscreen render targets are GPU-only in v1 [proposal]. 09's per-call caps and `freeMemory` sizing stay.
- **`setPalette`** (26 R7, M1) is an ordered op in the device tail. A present preceded only by palette ops runs the expand pass only, without re-rendering; the palette is part of confirmed state and of the side file (§3.6) and reaches LAN guests in keyframes and palette records (§4). `readPixels` returns indices, so palette changes do not affect it.
- **`setDither(mode[, matrix])`** per device (26 R8, M1): `"none"` (default; §7 question 13), `"bayer4"`, `"bayer8"`, or `"custom"` with 16 or 64 threshold bytes, selecting the present-time ordered dither of §3.7's quantize functions. No noise mode: it would change static images every frame and defeat LAN tile diffs [I]. PS1-exact dither is per draw, in the library (`DITHER_PS1`).
- **Latency.** Confirmation ≈ 1.5 host frames after `present`; a sleeping Lua program wakes only at the next server tick plus the 12 ms `executionDelay` (12–62 ms). Waiting for `done` with ≤ 2 frames in flight sustains ≈ 2 / (1.5 frames + 37 ms): 32 fps at 60 host fps, 18 at 20, so hosts below ≈ 25 fps miss the 20 fps tiers (21 §2.3). Pipelined programs reach min(host fps, tier fps). Non-LAN single player pauses on alt-tab (`pauseOnLostFocus`).
- **API invariants** (26 G2; text from M0, linted by the API dump at the M4 freeze). (a) No callback waits on, or returns data from, the host GPU, except state the server already holds (confirmed pixels, the cached `hostRenderer`); any future host-derived value is a signal, never a return value. (b) No `barrier`, `sync` or `fence` in graphics or compute: ordering is per-card submission order plus binding access flags, and v2's `barrier()` is workgroup-internal only. (c) Compute reaches graphics only through ops in the same device tail.
- **Errors.** `opengpu_error` adds `"gpu budget"`; `opengpu_reset` reserves `"canvas"` for future renderers that cannot keep canvas content (21 §5) and adds `"suspended after host crash"` (§3.8 layer 5; 26 G11); new `opengpu_program(card, handle, status, msg)` reports the host link (a rejection is an OpenGPU bug, 22 §1.5; a frame that needs a pending link waits, §3.4).
- **Shaders.** `createProgram(vs, fs)` → handle or `nil,"fs:12:5: …"`, synchronously, from the pure-Java frontend (works on dedicated servers too); `programInfo` returns warnings, std140 reflection and uniform defaults (26 S7). Shader source arrives as Lua strings (decision 15 supersedes 26 S8's server-side library). `createProgram` copies it, so the program may drop the strings afterwards; the transient RAM cost is acceptable because 3D already assumes ≥ 1 MB. `opengpu.lua` composes sources from files on the computer (its own include resolution), emitting `#line` so errors point at the original file and line. Sources start with `#version 300 es`; `opengpu.lua` writes that line once, first, when composing (27 §1.1). `programInfo` warnings include flat outputs that may differ per vertex (27 §6). Lua's uniform view stays std140 offsets in one byte string; int, uint and bool rows travel in `og_i_I` via `glUniform4iv` (27 §2.5).
- **Transforms (26 S6; owner answer 10).** The command stream carries `SET_CAMERA` per pass, `SET_GLOBALS`, and a 3×4 model matrix per `DRAW`. The server derives ModelView, MVP and the normal matrix and exposes them as `og_` built-ins, so Lua never encodes float32 matrices through `math.frexp` on 5.2 and OC-LuaJIT and avoids 16.16 precision loss on projection terms. `og_TargetSize` becomes part of S6's `og_Resolution` row.
- **Pipelines** (26 S5, R11, R9; M2). `PipelineDesc` names vertex streams by semantic (§3.4), POSITION mandatory; each stream steps per vertex or per instance (`glVertexAttribDivisor`, GLSM-mapped); blend modes add PS1's `sub` (B − F), `avg` (B/2 + F/2) and `addq` (B + F/4), saturating, on the RGBA8 scene target only, since integer surfaces ignore blending (GL 3.3 §4.1.7). `layout(location = n)` on a user vertex `in` names semantic slot n (27 §7).
- **Compute.** `createKernel(src)`; buffers flagged `COMPUTE|VERTEX|INDEX|TEXTURE_SRC` with std430 reflection; `dispatch(k, gx, gy, gz, bindings, uniforms)`, where `bindings` is a byte string of up to 8 handles and `uniforms` a std140 byte string of ≤ 64 rows (22 §2.1), so no table crosses per dispatch [proposal], runs inline under the inline cap, else queues and signals `opengpu_dispatch(card, jobId, status)`; `readBuffer`; `createComputeList(desc)` (configuration-time table, copied at once) plus `runList(h, K)` with integer arguments (22 §4). `opengpu.lua` decodes `<f`/`<d`/`<i8` via `string.unpack` where present, else arithmetically; OC-LuaJIT's `string.unpack` should cover those formats. `int64_t` values are exact in Lua only on 5.3/5.4; 5.2 and OC-LuaJIT hold numbers as doubles, so `readBuffer` helpers return them exact only below 2^53 (22 §2.2).
- **Input** (§3.10): `touch/drag/drop/scroll(display, x, y, button|delta, player)` in 0-based pixels; `key_down/key_up(display, char, code, player)`, `clipboard(display, text, player)` raised by the display.

### 3.4 Shader and compute languages

**Graphics: GLSL ES 3.00 subset, host GPU** (27; 22 §1 where 27 does not supersede it). Own ES 3.00 preprocessor (`GL_ES`, `__VERSION__` 300; depth 32, 10 000 tokens), lexer, parser and exact-match typer, shared with the compute profile and driven by `BuiltinInfo` (26 S2). Appendix-A `for` loops over int, uint or float indices (float only with `<`, `<=`, `>`, `>=`) with simulated trip counts; whole-array operations costed per element and arrays capped at 64 vec4-sized elements; no `while`/`do`/recursion; `switch` under ES 3.00 §6.2. uint, `%`, bitwise operators and shifts with compute integer semantics, enforced by emitter guards (27 §4). Dynamic indexing of arrays, vectors and matrices, clamped. `sampler2D` with `texelFetch`/`textureSize`, out-of-range fetches returning 0. smooth, flat ('either' vertex, 27 §6) and noperspective varyings. Static costing; TIR; `#version 330 core` text with zero-initialized outputs and locals, default returns, `#line` mapping and 27 §5's unconditional workarounds. Names get disjoint prefixes `og_u_`/`og_a_`/`og_v_`/`og_s_`/`og_o_`/`og_f_`/`og_i_`; a leading `_` and any `__` are rejected. Float rows pack into `og_i_U[n]` (`glUniform4fv`), rows with int, uint or bool members into `og_i_I[m]` (`glUniform4iv`, 25 C68); one `glUniform*v` call per array per program change. Samplers use units 0–3; `gl_Position.y` is negated with `glFrontFace` swapped so row 0 is the top.

Caps reconcile 20 §6 and 22 §1.4 by taking the tighter graphics value, because the GPU is the attack surface [proposal]: 8 attributes and 8 varying rows, counted by ES 3.00 §11 with smooth, flat and noperspective rows separate, and float, int and uint rows separate (27 §8.2); 128 VS / 64 FS uniform rows, counting `og_i_U`, `og_i_I` and used built-in rows; 4 FS samplers, no vertex texturing; 16 KB source; VS ≤ 4 096 ops; FS ≤ 1 024 ops and ≤ 16 fetches; ≤ 256 iterations per loop, nested product ≤ 4 096; ≤ 64 `switch` labels; arrays ≤ 64 vec4-sized elements; expression/statement/call depth 64/16/8. From M2, VS and FS are also lowered to SIR (a few days, 22 §3), with the scalarizer checked structurally in CI; from M3, when the SIR interpreter and bytecode emitter exist, the graphics fuzz corpus is also interpreted in CI and the `gl_Position` slice feeds the GPU-work guard (§3.8). This is seam 2 of §5.

**Interface additions** (26 §2.2, §3.2; M2; the attribute, format, blend and step additions are protocol, so they land before the M4 freeze).

- **Semantic attributes (S5; 22 §1.3).** Built-ins `og_Position` (vec3), `og_Normal` (vec3), `og_TexCoord` (vec2), `og_Color` (vec4) and `og_Custom0..3` (vec4) sit at fixed locations POSITION 0, NORMAL 1, TEXCOORD 2, COLOR 3 and CUSTOM0–3 4–7, emitted as `og_i_*` names; a user-declared vertex `in` takes a free CUSTOM slot, or slot n under `layout(location = n)` (27 §7). This replaces declaration-order binding, so one mesh and pipeline serve any shader that reads a subset of their streams. POSITION is mandatory, because compatibility drivers alias attribute 0 (20 §2). A stream the shader reads but the mesh lacks gets a 1-element VBO whose divisor exceeds any instance count, or a constant in the emitted program, never `glVertexAttrib4f` (§3.7, V1).
- **Uniform initializers (S7).** `uniform vec4 tint = vec4(1.0);` is a documented departure from ES 3.00 §4.3: initializers are the initial contents of the program's std140 block, Lua row writes overwrite them, and `programInfo` returns them.
- **`noperspective` (R1)** on varyings, behind `#extension GL_NV_shader_noperspective_interpolation`, which ES 3.00 requires because it reserves the word (§3.8); ANGLE and glslang implement the extension (27 §1.5); SIR carries an interpolation flag, and seam 4 adds screen-linear interpolation (§5).
- **`og_Resolution` (R2's `og_TargetSize`, merged into S6's row, §3.3)**, a built-in uniform modelled on `gl_DepthRange`, written per pass by the host and taking one row of the stage's cap only when referenced. The library's `og_snap` snaps clip-space positions to the real target's pixel grid, leaves w ≤ 0 to clipping and defines .5 with `floor(x + 0.5)` (`round()`'s .5 is implementation-defined; 27 §3); it is part of the `gl_Position` slice M3's guard runs.
- **Unbound samplers (G5)** read a 1×1 transparent-black default (GL rule in §3.7).
- **Pending links (G4; 22 §1.5).** A device whose next op needs a program with a pending host link is not runnable; other devices proceed, and its frames back up into `busy`. Link status is first read on the START pass after the one that issued the link (`GL_LINK_STATUS`, or `COMPLETION_STATUS_KHR` where present). A slow link needs no new timeout, since §3.3 already reports frames unconfirmed after 1 s as `"dropped"`; a permanent link failure reports `opengpu_program(card, h, false, msg)` and skips only the draws that use that program, so canvas content is not lost wholesale. No program-binary disk cache in v1: `glProgramBinary` is not GLSM-mapped and fails under `unmappedGL=FAIL`/`STRICT`.
- **Conventions (S10)**, documented as part of seam 4: Y-down, top-left `gl_FragCoord`, the depth range, matrix conventions and `og_Resolution`; `dFdy` along +y (down), `gl_FrontFacing` for counter-clockwise in y-up NDC (27 §7).
- **Flat varyings** (26 R10b; 27 §6): first-or-last-vertex contract, deterministic only with per-primitive-equal values; `programInfo` warns when that is not provable.
- **Examples, not libraries (decision 15).** The retro `#define` variant family of 26 §3.5 (R3, R4) and other showcase shaders live in the repository's `examples/` directory as Lua programs plus shader sources, loaded by user code. They can also ship on an OC loot floppy, and later through OPPM or a separate repository. They are documentation and integration tests at once: the ocelot GL test host runs each example and compares its readback within the image tiers of 23. Examples are MIT-licensed and clean-room; third-party shader code enters only after a per-file licence check (26 S12). The mod's own internal shaders (2D ops, quantize, expand) are not user-visible.

**Compute: GLSL 4.30 compute surface, server CPU** (22 §2). `#version 430`, `local_size`, the §7.1 built-ins, std430 `buffer` blocks with unsized arrays, bindings 0..7, `readonly`/`writeonly`, `bool int uint float double` (+ `int64_t` under `GL_ARB_gpu_shader_int64`), bitwise ops, `if`/`for`/`break`/`continue`/`return` with loop bounds that may be uniform (costed at dispatch), workgroups ≤ 256. Arithmetic is defined: wrapping overflow, `x/0 = x`, `x%0 = 0`, constant zero divisor a compile error, shifts mod width, saturating float→int, IEEE ops without contraction, `StrictMath` transcendentals. From 27: `switch` (shared frontend, 27 §8.4); float→uint saturates to [0, 2^32−1] with NaN → 0; `clamp` with lo > hi is `min(max())`; a non-void function falling off its end returns zero; constant float expressions that overflow to Inf or NaN are compile errors; `round` is half-even. Determinism D1–D5: bindings readonly/writeonly/read-write; a writable handle in one slot only; ≤ 1 writer per element; read-write accesses use one index proven injective at dispatch (mixed-radix test with extents refined by early-return guards) or checked by a CAS ownership array; no `shared`, atomics or images in v1. A fault zero-fills writable bindings and re-runs serially for a deterministic message. v2: `shared` + `barrier()`, unused-result integer atomics.

**Backends.** TIR → GLSL 330. SIR → bytecode (compute, GPU-work guard) and → interpreter (fuzz oracle, ocelot, tiny dispatches, fallback, fault re-run). Bytecode per 09 §3.3 plus `LineNumberTable`, an opcode/owner whitelist before `defineClass`, one class per program × layout × local size, methods < 7 500 B, and chunks of ≈ 0.25–1 ms from static cost because JDK 8 C2 drops safepoint polls in counted loops (22 §3).

### 3.5 Threading

The server never waits for the render thread; the render thread never calls into OC (21 §2).

| Actor | Does | Never |
|---|---|---|
| OC worker (4 shared) | budget, validate, take the argument `byte[]` (fresh per call, 21 §3.1), append to tail, `submit`; inline dispatch on bytecode only | render, block |
| Server thread | viewer snapshots (host connection excluded), input, saves; captures `RenderHosts.forServer(server)` and `session` at card creation | send frames, wait for GL |
| Pool, `min(4, max(1, cores/4))` | fold readbacks, LAN encode (priority), signals, persistence, compute under DRR by tier weight within `compute.maxMsPerTick` 20 ms (22 §4) | rasterize |
| Client thread, `RenderTickEvent(START)` | map finished PBOs → `onReadback`; drain queues round-robin within `client.renderBudgetMs` 4 ms [est.], with uploads, links and host deletes (evicted or released devices included) counted against it and capped at 8 such objects per frame (26 G13a, OSG's default) [proposal]; read back the newest frame per device; heartbeat | wait for the server; it does not fire in loading screens (20 §5) |

Side tests: `isDedicatedServer()` and `world.isRemote` only; never `getEffectiveSide()`, `getSide()`, `@SideOnly(Side.SERVER)` or client-side `MinecraftServer.getServer()` (21 §1.1).

### 3.6 Resources and persistence

Per device the server holds **confirmed state** (pixels of the last read-back frame K, the palette as of K (26 R7), resource master copies as of K, handle table with type bits) and a **tail** of immutable ops after K (21 §6). Each readback of N folds uploads up to N into the masters, which lag so a replayed frame sees the resource versions it was drawn with. The tail is bounded by ≤ 2 unconfirmed frames and tier VRAM (then `busy` or the `bitblt`-style pause). Payloads are the Lua argument arrays themselves, never copied on the worker; LWJGL 2 forces one direct-buffer staging copy before upload (21 §3.2). Compute buffers are server-owned masters, exact by construction.

**Save** (autosave, pause, stop) snapshots confirmed state + tail under the device monitor and writes a deflated side file on a single-thread writer, hash-gated, never waiting for the GPU. The pending-write map is global like OC's `SaveHandler.saving`, because the quit busy-wait ends at `serverStopped`, before `FMLServerStoppedEvent` (21 §2.5). Side files carry the command-format version of their tail op bytes, and from the first release (M1) on a mod update must decode every earlier version on load (26 G12). **Load** checks the crash guard first (§3.8 layer 5, from M2; a device with a surviving entry starts suspended), then submits `Init(resources, pixels)`, spread over frames by the creation budget (§3.5), and replays the tail under a new epoch, so a program saved between `present` and `waitFrame` gets `done`; without a renderer the tail resolves `"no renderer"`.

**GPU memory:** ≈ 3.5–5 MB of targets and PBOs per T3 device plus resources [est.] (20 §1, 21 §3.5), keyed by `(session, deviceId)` and independent of client world state (other dimensions still execute). Idle devices are evicted under a 256 MB client cap and rebuilt from confirmed state [proposal]; eviction and `stats()` read `GlLedger`'s host totals, which complement `:core`'s server-side VRAM accounting (26 G10).

### 3.7 Rendering legs

One code path; the only branches are "GLES or GL < 3.3 → graphics unavailable" (20 §2), the Angelica version gate below and, from M1, the renderer device list (26 G6): `GlCaps` matches a client-config list in Godot's format (vendor substring, renderer substring or `*`) against `GL_VENDOR`/`GL_RENDERER`, and a match reports `"host GPU on device list"` with compute unaffected. The matcher ships with an empty list, filled only from OpenGPU's own beta and bug reports; Godot's list is not imported, because its `"Intel(R) HD Graphics"` entry matches every Gen9 iGPU.

| | Angelica 2.2.8+ | No Angelica |
|---|---|---|
| Context | core 3.3–4.6 (4.1 macOS); opt-in GLES 3.2 → unavailable until tested | Forge legacy context, usually the highest compatibility profile on Windows [I] |
| GL calls | rewritten to `GLStateManager` by name, descriptor kept: a missing overload throws `NoSuchMethodError` (≈ 70 in 2.2.21, e.g. `glReadPixels(…, long)`, `glGetQueryObjectui(II)I`, `IntBuffer` gen/delete); scalar overloads + CI linkage test (20 §3; call set and deny-list in 25 §4) | direct |
| State queries | GLSM cache reads | ≈ 12 driver round trips per frame, only when work is queued |
| Shaders | `#version 330 core` passes GLSM untouched | as is |
| Pacing | `FramePacer`; FPS reducer (default off) and `IconifyGuard` skip world rendering when minimized; START still fires | `Display.sync` |

Common rules (20 §1–3): work only at START; save/restore program, draw and read FBO separately (START's binding is `framebufferMc`, not 0), VAO, buffer bindings, textures and sampler objects on every unit the pass touches (units 0–3, since user programs get up to 4 sampler units, §3.4; 20 §1's table lists 0–1 for the internal passes [I]), and seven `glPushAttrib` bits plus the scissor box, saved and restored explicitly because `GL_SCISSOR_BIT` does not restore it before 2.2.21 (25 R-1); force blend, scissor, stencil, polygon offset, colour logic op, dither and alpha test off, colour and depth masks on, and assert rasterizer discard off (20 §1); own `Fbo`/`Program`/`Vao`, no vanilla `Framebuffer` or GTNHLib helpers. 2D primitives (lines, circles, glyph runs) are integer-aligned quads or spans, never `GL_LINES`/`GL_POINTS`; these rasterization rules are written down in GL terms (point sampling at pixel centres, `GL_NEAREST` at texel centres, top row first, the quantize and expand functions; 23 §3.1) and are seam 4 of §5. R8UI (index8) and R16UI (RGB565) surfaces are written by a quantize pass and cleared by a quad writing 0 (`glClear` on integer buffers is undefined; `glClearBuffer*` is unmapped in GLSM). Readback: `glGetTexImage(…, GL_RED_INTEGER, …, 0L)` into a 3-PBO ring, ≤ 2 in flight, `glFlush`, completion polled via `glQueryCounter`/`glGetQueryObjecti(GL_QUERY_RESULT_AVAILABLE)`, data via `glGetBufferSubData`, fetched after 4 frames regardless (implicit sync keeps it correct); no `GLSync`. 21 §2.2's packed-RGBA8 readback is an M0 alternative if faster and exact.

**Angelica versions** (25 §1.1, §6; §7 answer 8). 2.2.8 is the supported floor. 2.1.14–2.2.7 is best-effort: graphics stay on with one log warning, and only the CI linkage canary covers the band; it is never run in game. Anything older, all of 1.x included, reports graphics unavailable with compute unaffected, as in §4. The gate reads the `angelica` mod version once at client init and feeds `GlCaps`; OpenGPU declares no FML version range, because FML 1.7.10 enforces soft-dependency ranges and would stop the game from starting. Gating CI linkage test: 2.1.14, 2.2.8, 2.2.21, 2.2.28 and the latest release. GLSM render-core leg: 2.2.8 and 2.2.28. In game: 2.2.8 through `runClient` with the dev jar pinned, the owner's instance on 2.2.21, 2.2.28 before each release, and the latest release as a non-gating run.

**Code rules from M0** (25 §5.1; unconditional, so no version branches). R-1 is the scissor-box rule above. R-4: inside its one five-bit `glPushAttrib` level the host TESR changes only FFP lighting, the lightmap coordinates and the unit-0 texture binding, never blend, alpha test, depth or colour masks, or per-unit texture enables (below 2.2.8 such changes leak under Iris override locks); the expand pass writes alpha 255, so inherited blend or alpha-test state has no visible effect [I]. R-8: the display item is never drawn through its TESR on a world-less dummy tile entity; it uses the ISBRH chassis's inventory path. R-9: every display tile entity returns one constant box, the largest wall's extent, from its first `getRenderBoundingBox()` call, whatever its role in a wall, because Angelica classifies the class on that call and caches the box per instance. Also from M0: R-2 (glGet into direct buffers, absolute reads), R-3 (drain `glGetError` at the start of START), R-5 (no GL method references), R-6 (one push level per pass, no stack-depth queries), R-7 (reflective `isGLES()`), R-10 (detach or delete the FBO before its textures), R-11 (compile against `Angelica:2.2.8:api`), R-12 (only 25 §4.1's calls, never §4.2's) and R-13 (the gate above).

**State rules** (26 §1.4, §3.2; unconditional, from M0 unless marked). G8: around every upload, every `glGetTexImage` and the LAN-guest upload, set `GL_PACK_`/`GL_UNPACK_ALIGNMENT` to 1, `ROW_LENGTH` to 0 and `SKIP_PIXELS`/`SKIP_ROWS` to 0 (plus `SWAP_BYTES`/`LSB_FIRST` false on compatibility contexts), then restore. Vanilla F2 leaves both alignments at 1 (`ScreenShotHelper`), and a foreign row length or skip would corrupt a transfer; GLSM caches only unpack state, so pack state is saved and forced only when a readback is queued (20 §1 table). V1: never set constant generic vertex attributes (`glVertexAttrib*`); Angelica caches them by dirty flag and the call bypasses that cache, so the value would leak into later Minecraft or Iris draws (the Iris effect is [I]). Missing streams are fed as §3.4 says. R10a: never call `glProvokingVertex` and never depend on its state; it is unmapped in GLSM 2.2.21, so it throws under `unmappedGL=FAIL`/`STRICT`, and GLSM sets the mode itself. G5 (from M2): every unit a user program declares and a draw leaves unbound gets a 1×1 transparent-black RGBA8 default (or a cube default); otherwise the draw would read the previous device's texture, a cross-device leak into pixels Lua can read back.

**Quantize functions and index textures** (26 R8, R5, G5; T3 and the format rule in M1, index8 3D in M2). RGB565 quantize is round-to-nearest, `q = (c·m + 127) / 255` in integer division with m = 31 or 63, which round-trips every 5- and 6-bit level. When `setDither` is not `"none"` (§3.3), it dithers between the bracketing levels `lo ≤ c < hi`, choosing `hi` iff `(c − exp(lo)) / (exp(hi) − exp(lo)) > (k + 0.5)/n²` for Bayer index k, which keeps every representable colour fixed, so 2D stays exact either way. Index8 quantize takes the exact nearest palette entry, ties to the lowest index, by brute force over 256 entries or a LUT plus a verify step, never a plain 32³ LUT, which can mis-map exact palette colours (20 §3 as amended). User-visible `index8` textures are normalized `GL_R8`, counted as 1 B/texel and sampled `GL_NEAREST`; internal shaders recover the index as `floor(r * 255.0 + 0.5)`, and palettes are RGBA8 textures of 256 × N rows. `R8UI`/`R16UI` surfaces stay OpenGPU-private and are never bindable to user samplers.

### 3.8 GPU safety

1. **Static:** Appendix-A loops with simulated trip counts, §3.4 caps, clamped indices, zero-initialized outputs (20 §6, 22 §1.2); integer guards and clamping of every dynamic index (27 §4, §2.6).
2. **Submission:** per-draw vertex/instance caps, per-frame triangle caps by tier, index ranges validated at upload (no robust access), frames chunked with `glFlush` between chunks (WebGL guidance).
3. **Per-frame guard (M3):** the `gl_Position` slice runs as bytecode on the server; clipped bounding boxes × fragment cost above 5·10⁷/2·10⁸/8·10⁸ ops per frame (T1/T2/T3, ≈ 16 ms at a pessimistic 50 Gop/s [est.]) reject the frame deterministically (22 §1.4). Until M3, layers 1, 2 and 4–6 suffice, because only the host player authors shaders before LAN arrives in M4.
4. **Measurement:** timestamps per display frame, EWMA, 3 ms of GPU per client frame host-wide; over-budget displays run less often; one frame > 100 ms suspends the display, > 500 ms disables graphics for the session (20 §6).
5. **Crash guard (M2; 26 G11):** before the first `glUseProgram` of a new program set on a device in a session, append `(world, deviceId, program hashes)` to `opengpu-gpu-guard.txt` and flush; clear the entry when that frame's timestamp query becomes available, and the file at clean shutdown. At the next launch, surviving entries start those devices suspended (`opengpu_reset(card, "suspended after host crash")`), with a resume button in the OpenGPU GUI; two consecutive unclean exits with live entries disable graphics with `"disabled after GPU timeout"`. This breaks the loop in which OC's persisted machines (and autorun scripts) present the same hang-causing frame after every reload; the program LRU keeps writes rare.
6. **Kill switch** in client config. A TDR (2 s default) still ends the session on both legs (no robust context); documented in game.

### 3.9 Testing (23)

Gating, in the unchanged shared workflow (`xvfb-run`, llvmpipe, Java 8 launchers on GL tasks, which is decision 12's Java 8 leg): compiler fuzzing; compute bit-identity over interpreter/bytecode, JDK 8/17/21, `-Xint`/C1/C2, thread and card counts; render-core tests on compat and GLSM contexts with the hygiene assertion; ocelot Lua bytes == FBO bytes; the linkage test; `runServer` with Horizon-QA GameTests. From 26: the hygiene start state adds non-default pixel-store state and generic attribute values on locations 0–7, and the assertion compares both (G8, V1); `:gl-testkit` asserts that `GlLedger` is empty after `releaseSession`, that per-device totals match 20 §1's formula and that they agree with `:core`'s VRAM accounting (G10); the frontend corpus adds raylib's `glsl100` shaders converted by 27 §1.3's tool and its `glsl330` shaders with the version line changed (implicit-conversion files as expected rejections), licence-checked per file, with Shadertoy-derived files excluded or the corpus fetched in CI instead of vendored (S12); fog golden tests without `exp()` move from tier T to tier S (R4). 27 §5's integer conformance corpus gates on llvmpipe and runs on the owner's Intel, NVIDIA and AMD GPUs per milestone and driver update. Image tiers E (exact; 2D as integer quads, `GL_NEAREST`, dither off), Q, G, S, T with thresholds calibrated on llvmpipe, softpipe, WARP and the owner's Intel. Non-gating, in a second OpenGPU workflow set up in M2: `runClient` under Xvfb, Windows llvmpipe/WARP, `glslangValidator` (inputs validated as `#version 300 es` with a test prelude, outputs as `330 core`). Manual: 23 §5.1 per milestone; pre-release compute check on a Java 17–21 pack server.

### 3.10 Input and the OpenGPU GUI

Right-click opens a client-only GUI: no container, `doesGuiPauseGame = false`, as in OC (03 §2; `ocgtnh-master/…/client/gui/traits/InputBuffer.scala:38`). Cursor positions floor to 0-based pixels, clicks outside the picture are rejected, and `setPrecise(true)` adds fractional coordinates on every tier. Keys and clipboard follow `InputBuffer.scala:66-116` (repeat filter, key-ups on close, paste by binding or middle click). Events go C2S (clipboard chunked under 32 766 B) and are validated (chunk loaded, ≤ 8 blocks, `isFinite`, range). The display node then signals as OC's keyboard does, `node.sendToReachable("computer.checked_signal", player, name, args…)` (`server/component/Keyboard.scala:143`), so `Machine` applies its user check (`server/machine/Machine.scala:649-651`). `char` and `code` are boxed as `Long`, because OC turns `Character` into `Integer` (`Machine.scala:320`) and a save turns `Integer` into nil (`:865-879`). The display connects on its five non-front faces, and every block of a wall keeps its own node so cables may attach to any constituent (15 §2.2). A later "touch in world" option maps clicks through the shared screen-surface helper (§6, M0 and M1.5) by intersecting the look ray with the possibly tilted screen plane; 15 §4's projection is its flat special case.

## 4. Dedicated servers and LAN guests in v1

**LAN guests** get host pixels (21 §4): lossless 16×16 dirty tiles against the last confirmed frame, deflated (L1 for 3D and large deltas, L6 otherwise), encoded once per frame and shared by all guests at the same stream position; keyframes from confirmed pixels and the palette on join, and a 768 B palette record on each palette change, because a palette-only frame has no dirty tiles (26 R7, restoring 09 §3.4's palette rule); `CATCHUP` beyond a 4 MB/s per-guest cap [proposal]. Measured: T3 3D with camera motion 34–123 KB/frame (0.7–2.5 MB/s), UI ≈ 1–2 KB/s. Guests need only GL 1.2 (02 §5) and see exactly what Lua reads. A weak, stalled or minimized host limits everyone; LAN-open worlds never pause.

**Dedicated servers** run option (a) (21 §5): `renderer = "none"`; compute, `dispatch`, `readBuffer` and command lists work fully; `present` → `nil,"no renderer"`; `readPixels` and viewers get the persisted confirmed image (as a keyframe over the LAN path). Programs must check `getCaps().renderer`.

**A host without usable graphics** (GL below 3.3, Angelica's GLES profile, an Angelica older than 2.1.14 (§3.7), a GPU on the renderer device list (§3.7), kill switch, or disabled after a GPU timeout) keeps `NoRenderHost` (or, when graphics are disabled mid-session, its `GlRenderHost` answers as `NoRenderHost` does) and behaves exactly like a dedicated server for graphics (21 §1.3): same API results, and its LAN guests get the persisted confirmed image. Its own displays show 20 §7's fixed "no graphics" bitmap through the GL 1.2 upload path, because the host's local channel never carries display content (§3.2). This section is the single statement of dedicated-server and LAN-guest behaviour; §3.2–§3.3 and M4 refer to it.

**Carried now so later options are additive** (21 §5): versioned byte records across the SPI (command stream plus `RESOURCE` records with handle, generation, rect, content hash); an asynchronous SPI (`submit`/`onReadback`); the `getCaps()` fields and status strings; display packets with a body type (`TILES` live, `CMDS`/`RESOURCE` reserved); renderer-independent persistence; documented `opengpu_reset(card, "canvas")`. This keeps (b) display-only viewers and (d) a server software renderer open. (c), a donor client rendering for the server, is rejected: C2S < 32 767 B, residential uplinks, and the donor would control Lua-visible pixels.

## 5. Deferred work item: software reference renderer

**Estimate** (orchestrator, recorded as given): practical later, because compute forces the hard parts to exist (GLSL-family frontend, typed IR, bytecode backend with caps, CPU dispatch). New work is a rasterizer (edge functions, clipping, interpolation, depth, sampling, blending) and the 2D operations: ≈ 2 weeks for 2D plus a fixed-function path, 4–6 weeks for programmable shading at "correct, not fast", plus tolerance tests against GL output. 21 §5 (d) put the whole server-side option, integration and CPU budgeting included, at 8–12 w; 6–12 w is the plausible range [est.]. Reference costs: 06 §3; 12 §1 (T2 1.3–1.6 ms, T3 4.8–5.6 ms per bytecode-shaded frame on the bench CPU).

**Seams kept** (each from the milestone that introduces it; architecture locations in brackets): (1) from M0, a backend-neutral command stream and resource model, with no GL types or enums in the protocol [§3.1 `:core`, §4]; (2) from M2, VS/FS lowered to SIR, and from M3 interpreted in CI, although only GLSL is emitted (22 §3) [§3.4]; (3) from M0, a `RenderHost` interface with `NoRenderHost`, `FakeRenderHost` and `GlTestHost` exercising it beside the GL host [§3.1, §3.2]; (4) from M0 for 2D and M2 for 3D, rasterization rules documented in GL terms: integer quads/spans for 2D, point sampling at pixel centres, `GL_NEAREST` at texel centres, top row first, the quantize and expand functions (23 §3.1), and from M2 screen-linear (`noperspective`) interpolation, the documented `gl_FragCoord`, depth and matrix conventions (26 R1, S10) and the flat 'either' rule, with LAST in the CPU reference (27 §6) [§3.4, §3.7].

**Triggers:** graphics on dedicated servers; GL-free CI if llvmpipe becomes unusable; a reference for driver-bug triage; demand from hosts below GL 3.3.

## 6. Phased plan

Focused solo-developer weeks, ±50 % [est.]; M0–M4 ≈ 30.5–44 w including M1.5, which was added after review, the lessons adopted from 26 (≈ +4–7 w, about half of it correctness and safety) and the ES 3.00 graphics language (27 §9: ≈ +2.6–3.8 w net). Shippable single-player 2D after M1, screen walls with the final block shape after M1.5, 3D after M2, compute after M3, LAN and v1 after M4.

| Phase | Scope | Definition of done | Effort |
|---|---|---|---|
| **M0 vertical slice** | Subprojects and CI wiring; card + cube display with `bind/submit/present/readPixels/getCaps` (all six facings plus the floor/ceiling rotation; thickness and tilt saved in NBT at their flat defaults; one screen-surface helper computing the world-space screen quad, shared by the TESR and touch; one framebuffer per display, owned by the future wall origin; one constant render box sized for the largest wall, returned by every display tile entity from its first call, §3.7 R-9); `CLEAR/FILL_RECT/BLIT/TEXT` into R8UI (T2) and R16UI (T3); quantize/expand; PBO ring + timestamps; confirmed-state fold; `opengpu_frame`; all four `RenderHost`s (`GlRenderHost`, `NoRenderHost`, `FakeRenderHost`, `GlTestHost`); heartbeat/stall; TESR (§3.7 R-4) and a display item drawn through the chassis's inventory path (R-8); minimal GUI with touch; minimal `/opengpu selftest` (pattern + sprite readback, no goldens); GLSM linkage test over 25 §4's call set (2.1.14 canary, 2.2.8, 2.2.21, 2.2.28, latest) and a `unmappedGL=FAIL` redirector run (2.2.x jars); the Angelica version gate and the §3.7 code rules (25 §5.1); from 26: typed handles (G1), the §3.7 state rules G8 and V1, `GlLedger` (G10) and the §3.3 API invariants as text (G2); 1-week compute spike (IR → `V1_8` via relocated ASM in a child loader) | Ocelot on llvmpipe: Lua bytes == FBO bytes on 5.3/5.2/OC-LuaJIT; under `NoRenderHost`, `present` returns `nil,"no renderer"` and `readPixels` the cleared `frameId = 0` image (§4). `runClient` with Angelica absent, present (2.2.8 dev jar pinned; repeated in the owner's 2.2.21 instance) and with an Iris pack: pattern + sprite visible, self-test readbacks byte-identical, hygiene clean, panel correct next to other mods' TESRs under a pack that overrides block-entity blend (25 §6). The GLSM render-core leg (2.2.8, 2.2.28) passes the hygiene case with a non-default scissor box (R-1). T2 2D at 20 fps on a ≥ 30 fps host. `runServer` loads no client class. Quit with 2 frames in flight neither hangs nor leaks. Spike ≤ 10 ns/element, same output on J8 and J21. **Measured:** render-thread cost per job (1/4/16 displays); PBO readback throughput and query latency on Intel, NVIDIA and AMD (owner's hardware, §7 answers); vanilla `glGet` cost; OpenGPU fps vs host fps 60/30/20; 1 k-triangle encode on 5.3/5.2/OC-LuaJIT; OpenOS free memory at 192 KB; inputs to 26's considered items (§7): START-pass p50/p99 and frame-time variance at 1/4/16 displays with NVIDIA Threaded optimization Auto vs Off for `javaw.exe`, set by the owner, and one T3 scene capped at 30 fps vs uncapped on Intel and AMD (G7), upload cost per MB on the three vendors (G13b), instanced vs expanded 2D quads (G16) | 4.5–7 w |
| **M1 2D product (SP)** | All 2D ops (palette, scissor, `COPY`, T3 blending via RGBA8 + quantize at present, integer-span lines/circles, glyphs from a licence-checked font); simple-2D callbacks; `library()`/`opengpu.lua`; frame/canvas modes; all tiers; full GUI; persistence (state + tail, global write map); budgets, GPU timer, suspend/disable, kill switch; `ImageCompare` E/Q/G goldens; self-tests; from 26: `setPalette` as an ordered, persisted op with expand-only presents (R7), the 565 quantize and `setDither` (R8), the `index8`/`GL_R8` format rule as `BLIT` sources gain formats (G5, R5), `api-dump.json` with its CI diff and versioned side-file decoders (G12), the device-list matcher with an empty list (G6), the creation budget (G13a) | OpenOS-style terminal at 20 fps on T2; E-tier goldens exact on llvmpipe, softpipe and Intel, Angelica on and off; save/load with a suspended loop on 5.3/5.2/OC-LuaJIT (+5.4 on the owner's instance) with tail replay delivering `done`; 23 §5.1 lifecycle checklist passes; an abuse corpus (spinning `present`, oversize uploads, fill-heavy frames) ends in `busy`, budget stalls or suspension, with no server tick > 50 ms or host frame > 100 ms attributable to OpenGPU [proposal]; `freeMemory` flat over 10 k frames; a palette-only frame re-expands without re-rendering and survives save/load (26 R7). **First SP release** | 5–7 w |
| **M1.5 Display block** | Walls of identical same-tier blocks (OC model: any block extends; rectangular; same facing and shape; maximum extent configurable, default 8 × 6 like OC's `maxScreenWidth`/`maxScreenHeight`, `OpenComputers-GTNH/src/main/resources/application.conf:1245,1252`); split and re-form on add, remove and chunk load; one framebuffer and one quad per wall, drawn by the origin; shape options after Energy Control's Advanced Info Panel, clean-room: thickness 1–16 px, horizontal and vertical tilt as one plane across the wall; GUI settings tab, applied wall-wide; frame geometry cached per shape, never rebuilt per frame; touch by ray–plane intersection with the tilted surface; wall aspect ratio reported to Lua, picture letterboxed; selection box follows thickness | A 3 × 2 T2 wall forms and splits correctly when blocks are added or removed, across a chunk border and after save/load; one picture spans it; touch lands on the right pixel within ±0.5 px at the corners of flat and maximally tilted walls; shape changes made from any block persist and reach the whole wall; GL object count and heap stay flat over 10 k frames; a wall grown after its first render is not culled under Angelica, including when a block becomes the origin after its own first render (25 R-9) | 2–3 w |
| **M2 3D + shaders** | Design review first (§7 question 11); buffers, textures (with `index8` and palette rows, 26 R5), pipelines (semantic streams, per-instance step, `sub`/`avg`/`addq`; S5, R11, R9), render targets, depth/stencil, dirty rects; full ES 3.00-subset frontend (27) with trip counts, costing, `switch`, integer guards and range analysis; the integer conformance corpus on llvmpipe, Intel, NVIDIA and AMD (27 §5); TIR + 330 emitter, mangling, std140 packing; async link + `opengpu_program` with pending-link semantics (G4), program LRU; engine-derived transforms (S6); the retro example set in `examples/`, not mod API (decision 15; R3, R4: `TEXTURE`, `CLUT`, `VCOLOR`, `LIGHT_VERTEX` (Gouraud), `LIGHT_PIXEL` (Blinn-Phong), fog per vertex, per pixel or by table, `CUTOUT`, `SNAP`, `AFFINE` and `SPRITE`; `FLAT`, `STIPPLE` and `DITHER_PS1` may follow at any time, since examples are not API; variant counts documented against 4 compiles per second per card and the 256-program LRU); the §3.4 interface additions (S2, S5, S7, S10, R1, R2, R10b), unbound-sampler defaults (G5), the exact index8 quantizer (R8) and the raylib corpus (S12); safety layers 1, 2 and 5 (the crash guard, G11), with 4 and 6 from M1 extended to 3D draws; VS/FS → SIR lowering with structural checks in CI (interpretation from M3); the non-gating OpenGPU workflow (Windows llvmpipe/WARP calibration leg, `glslangValidator`, `runClient` under Xvfb; 23 §1.6, §5.2), needed for the calibration; S/T goldens calibrated on llvmpipe, softpipe, WARP and Intel | Textured, lit 1 k-triangle scene with user shaders at 20 fps on T2 and T3 on the owner's Intel within 3 ms GPU per frame; hostile-shader corpus rejected with `line:col`; the corpus includes unbound samplers, and no draw reads a foreign texture (G5); a PS1-style example (snap, affine, vertex light, fog, CLUT) at 20 fps on T2; 10⁴ fuzzed programs link on llvmpipe and Intel with zero driver rejections, GLSM leaves emitted source unchanged; the integer conformance corpus is bit-exact against the Java reference on llvmpipe and the three owner GPUs (NaN cases excepted; 27 §5); 1 k-triangle encode ≤ 5 ms on 5.3, ≤ 2.5 ms on OC-LuaJIT (09) | 11–16.5 w |
| **M3 compute** | Compute profile of the shared frontend (27 §8.4), with `switch`; SIR interpreter, bytecode emitter (whitelist loader, chunking); D1–D5; `double`/`int64_t`; dispatch, `readBuffer`, command lists, DRR, tier caps, inline dispatch; compute → graphics; safety layer 3; multi-card copies (Q5), energy (Q4); Horizon-QA GameTests; HBM RBMK design-evaluator example on the console API common to upstream and NTM: Space (22 §5) | 64 k kernel ≤ 10 ns/element; bit-identical across 23's matrix incl. 1 vs 4 cards; 10⁵ fuzzed programs clean; graphics fuzz corpus runs on the SIR interpreter in CI (seam 2); no OC worker blocked > 0.2 ms; maximal T3 dispatch spread within `compute.maxMsPerTick`, no JDK 8 safepoint stall > 5 ms [target]; dedicated-server GameTests pass; RBMK example runs K = 1 000 steps of 15×15 in one command list | 4.9–6.2 w |
| **M4 LAN + v1** | Guest path (13 pool send, tiles, keyframes carrying the palette and palette records (26 R7), `CATCHUP`, per-guest cap); dedicated option (a); `displayRange`, `maxNetworkClientPacketDistance`; `stats()`, docs, examples, API freeze, with the API dump linting §3.3's invariants (G2); cross-vendor beta | Two-client LAN: guest framebuffer hash == host at every tier, including after palette-only frames (R7); the beta includes a Gen9 Intel iGPU and, if obtainable, a GCN 1–3 AMD GPU (G6); 3 guests × 2 T3 3D displays on wired LAN within 21 §4.3; dedicated-server soak (≥ 8 h, compute on 4 cards, 2 viewers receiving the confirmed image [proposal]) with no crash, no tick overrun attributable to OpenGPU and a flat heap; `unmappedGL=STRICT` clean; **v1** on Java 8, compute checked on a Java 17–21 pack server | 3–4 w |
| M5+ | Software renderer if triggered (§5); further block designs; `TextBuffer` binding; compute v2; display-only viewers; GLES profile | — | — |

**Display block (decision 13).** The design is settled, so M1's checkpoint is gone. M0 already carries what the final shape needs (facings, saved shape fields, the screen-surface helper, one framebuffer per wall, maximal render bounds), so M1.5 adds walls and shape options without a data migration. Constraints kept from the reports: TESR bounds are classified at the first call per class and cached per instance (02 §3; 25 R-9); every wall constituent keeps its own node (15 §2.2); resolution stays bounded by tier, so a larger wall shows the same pixels larger. Energy Control pitfalls not to copy: its tilted renderer compiles a new display list every frame and never frees it, its render box grows with the wall, and it has no touch mapping on the tilted face.

## 7. Questions for the owner

Ordered by design impact, each with the assumed default.

1. **GL floor 3.3 on both legs, no `#version 120` dialect in v1?** Hosts below 3.3 and Angelica's GLES profile get "graphics unavailable"; compute still works. GL 3.1-class GPUs are excluded outright; macOS and capped Mesa need Angelica. *Default: yes.* (Merges 20 Q1/Q3, 22 Q1, 23 Q2; the 120 emitter is small, but its host path lacks integer targets, timer queries, explicit locations and samplers, i.e. a second renderer leg.)
2. **Host priority:** render-thread budget 4 ms per client frame (not fps-scaled), GPU 3 ms, suspend at 100 ms, disable at 500 ms, eager readback of each device's newest frame (≤ 10 MB/s per T3 device), 256 MB GL cap with eviction? *Default: yes; budgets client-configurable; the disable threshold can rise to 1 s at most.*
3. **May frame-mode devices skip frames the host could not run (`"superseded"`)?** *Default: yes; canvas mode never skips.* Related, and unspecified in 20 §5 and 21 §6: does a frame reported `"dropped"` after the 1 s deadline leave the tail? *Default [proposal]: in frame mode it is removed (a newer frame supersedes it); in canvas mode it stays queued, so its pixels still land, and the program learns of it from the next `"done"`; only an epoch change discards canvas frames.*
4. **Energy** (unresearched since 09)? *Default: charge per `present` (pixels × fragment cost) and per dispatch (static ops), configurable, calibrated in M3 against OC's GPU costs; `ignorePower` respected.*
5. **Server-side buffer copies between cards in one OC server (≤ 4)?** *Default: yes, a queued `copyBuffer` ordered after both cards' queues, M3, ≈ 1 w [est.]; across servers and racks, OC network messages.*
6. **T3 2D blending:** RGBA8 with one 565 quantize per present, or quantize per op? *Default: per present; restored canvas content may then differ by one 565 step from never-saved content [I].*
7. **Is an NVIDIA or AMD machine available for M0?** *Default: no; M0 measures Intel and llvmpipe, cross-vendor items move to the M4 beta.*
8. **Angelica floor:** keep 2.2.21 (your instance; forces `glGetTexImage` readback) or require 2.2.22+? *Default: 2.2.21, linkage-tested with 2.2.28 and the latest release.*

**Owner answers (2026-10-08).**

1. GL 3.3 floor on both legs, no `#version 120` dialect: accepted.
2. Host budgets as proposed: accepted.
3. Skipping frames: accepted, and made configurable per device with `setFramePolicy` (§3.3); the `"dropped"` default stands (removed from the tail in frame mode, kept in canvas mode).
4. Energy: charge OC energy the way OC's own GPU does, through the node's power buffer with `ignorePower` respected, so OpenGPU behaves like any OC component; per-present and per-dispatch costs calibrated in M3.
5. Buffer copies between cards in one OC server: accepted, M3.
6. T3 blending in RGBA8 with one RGB565 quantize per present: accepted.
7. All three vendors are available: an NVIDIA RTX 5060 and the Intel integrated GPU on the owner's main machine, and an AMD Radeon RX 7600M XT on a second machine. M0 measures on all three; the M4 beta keeps a wider cross-vendor pass. The main machine is a Minisforum MS-02 Ultra mini desktop with both GPUs active, so the GPU is chosen per run with the owner's `dgpu-run.ps1` wrapper (`C:/Users/astro/.local/bin/dgpu-run.ps1`; high performance by default, `-Gpu PowerSaving` for the Intel GPU), which sets Windows' per-executable GPU preference for the launched JVM. For Gradle dev runs, the preference must name the toolchain `java.exe` that `runClient` forks, via the wrapper's `-Also <path>` option. Every measurement records `getCaps().hostRenderer` to confirm which GPU actually rendered.
8. Angelica floor: support 2.2.21, or an earlier 2.x release if nothing important is lost; the owner left the choice to us, and `25-angelica-version-floor.md` set it (§3.7): 2.2.8 supported, 2.1.14–2.2.7 best-effort, older releases gated off at runtime. The owner cannot update their own instance to the latest stable because of an unrelated Angelica bug, so the latest release is covered by CI and the linkage test rather than by the owner's instance.

**For the M2 design review** (26 §6). Questions 9, 10, 12 and 13 are answered; question 11 stays open until the review. Question 12's language change is in §6's estimates (27 §9); question 11's index-output 3D (≈ 3–5 d) is not.

9. **Typed shader types** (26 S1, with S3 and S9). *Answered 2026-10-09: deferred to after v1. They are additive, so nothing in v1 depends on them.*
10. **Engine-derived transforms** (26 S6). *Answered 2026-10-09: yes, in M2 (§3.3 Transforms).*
11. **Index-output 3D on T1/T2** (26 R6). Doom/Quake-style colormap lighting and palette-cycled 3D through an `index` pipeline output, ≈ 3–5 d [est.]. *Open: decided at the M2 design review (owner, 2026-10-09); palette ops for 2D (R7) ship in M1 either way.*
12. **Graphics language level** (26 S13). Stay with ES 1.00, or accept an ES 3.00-syntax subset (adding `uint`, `%`, bitwise operators, `switch`, `flat`, `texelFetch`) with the same Appendix-A loops and caps? This reopens decision 5. *Answered 2026-10-09: ES 3.00-based subset; decision 5 changed; specified in 27.*
13. **Default present dither** (26 R8). *Answered 2026-10-09: `"none"`, with example programs opting in.*

**Report 27's open questions** (27, Open questions). *Answered 2026-10-09: the owner accepted all nine defaults.* No integer vertex stream formats (decode from unorm streams); `samplerCube` only if M2's texture API creates cube textures; no vertex-stage `texelFetch` in v1; the predeclared input types of §3.4 S5; `packHalf2x16` excluded; `round` is half-even on the GPU (`roundEven`) and in compute; a flat output that is not provably per-primitive is a `programInfo` warning; no `#version 100` profile (converter and error hint); no uniform-block grouping syntax in v1. None changes §6's estimates.

**Considered, not scheduled** (26's CONSIDER items; no work is planned before their decision point):

- From M0's measurements (§6 M0): threaded-driver and DVFS effects on the START pass and on layer 4's timestamps, decided in M1 (G7); slicing `UploadOp`s over 256 KB across passes (G13b); 2D ops as instanced quads (G16).
- At the M2 design review: questions 9–11 (S1 with S3 and S9; S6; R6); one source per program under `VERTEX`/`FRAGMENT` defines (S4); the lit built-ins' colour space and the index8 quantizer's distance metric, a documentation decision (G15). S13 (question 12) and R10b (`flat` with WGSL's "either" semantics) left this list: both are in M2 through 27.
- During M2, from measurement or the ledger: streaming slots indexed by the PBO ring slot (G9); per-tier pooling of transient scene targets, only if `GlLedger` shows counts near the 256 MB cap or eviction churn (G14); the N64 3-point filter and atlas wrap as library functions (R13).
- Later: versioning typed shader contracts, with S1 (S10); Shadertoy and LÖVE alias shims for the M4 examples (S12); typed compute kernels in M3 if the injectivity proof misclassifies common fuzz-corpus kernels (§8 risk 10) or users want particle systems (S14).

## 8. Risks

| # | Risk | L / I | Mitigation |
|---|---|---|---|
| 1 | GLSM descriptor trap: a missing overload crashes the client; `STRICT` is blind to it | H without test, L with / H | Gating linkage test per supported Angelica jar; scalar overloads; re-run per Angelica release (20 §3) |
| 2 | Angelica churn (name maps, shader handling, `GLSMInitConfig`/`MainThread` used by the test leg) | H / M | Pinned test versions; latest release in the linkage test and non-gating `runClient`; Iris checklist (23 §2.3) |
| 3 | GPU hang or TDR ends the host session and all guests; OC persists running machines (and autorun re-runs), so after a reload the program presents the same frame again (26 G11) | L in SP, M with LAN authors / H | §3.8 layers, including the crash guard (layer 5, M2); kill switch; documentation |
| 4 | Host fps clocks OpenGPU (< 25 fps misses 20 fps tiers); paused, minimized or loading hosts; one render thread for all devices | M / M | Budgets, round-robin, `busy`, `superseded`, heartbeat/stall state; M0 measurements; documented two clocks (21 §2.3–2.4) |
| 5 | PBO readback slow on some GPUs; "timestamp available ⇒ PBO written" is a spec reading | M / M | M0 measurement; fetch after 4 frames regardless; packed-RGBA8 alternative; cross-vendor beta |
| 6 | Vanilla-leg `glGet` round trips; foreign GL state at START | M / M | Queries only when work is queued; forced state, pixel-store state included (26 G8); no constant generic attributes (V1); dev assertions; hygiene tests from non-default state |
| 7 | Persistence fold/replay bug; quit ordering | M / H | Fold at readback; save/load with suspended loops on every runtime; quit-with-frames test; global write map |
| 8 | Unmeasured constants (render-thread job cost, Lua encode, OpenOS memory at 192 KB, readback throughput) | H / M | M0 measures before tiers and budgets freeze |
| 9 | Compute on JDK 8: counted loops stall safepoints; misestimated or dispatch-time costs | M / H | 0.25–1 ms chunks; re-costing tests; TTSP checked in GameTests; caps, `compute.maxMsPerTick` |
| 10 | Interpreter/bytecode drift; injectivity misclassification; bytecode security (09 risk 6) | M / H | Continuous fuzzer and bit-exact oracle; guard-refined extents; closed IR, whitelist loader, `CheckClassAdapter` |
| 11 | OC worker starvation, signal loss, table-argument RAM drain (09 risks 7, 10, 12) | M / M | Carried mitigations; `freeMemory` flatness test; OC-LuaJIT eager proxy release |
| 12 | Client classes leaking into common code, hidden by the dev `runServer` classpath | M / H on servers | SPI; `@SideOnly(Side.CLIENT)`; bytecode reference test; pre-release production-jar server |
| 13 | llvmpipe drift and non-representativeness | H / L | Tolerance tiers, sidecars, four-implementation calibration, owner-GPU runs, expectations file (23 §3.5) |
| 14 | LAN on 2.4 GHz Wi-Fi; real 3D compressing worse than synthetic scenes | M / L | Per-guest cap, `CATCHUP`, L1 for 3D; measure real content in M4 |
| 15 | SP programs failing on dedicated servers; HBM model not a gather, OC I/O dominating live use | M / L | `getCaps().renderer`, docs, examples under `NoRenderHost`; command lists, design-search framing (22 §5) |
| 16 | Licences (Angelica's LGPL fixtures; font) | M / L | Own JUnit extension; unscii/Unifont check in M1 |
| 17 | Driver integer, `switch` or flat-varying miscompiles on the owner's GPUs; ANGLE's GL workaround list is thin evidence for Windows drivers | M / M | Guards keep undefined cases away from drivers; unconditional cheap workarounds; conformance corpus per driver update; renderer device list (G6) (27 §4–5) |
| 18 | ES 1.00 sources (tutorials, raylib `glsl100`) no longer parse | H / L | Error with a conversion hint; converter tool; examples written in ES 3.00 (27 §1.3) |

## 9. Index of new reports

| File | One line |
|---|---|
| `20-gl-first-client-rendering.md` | Host GL path: START hook, state save/restore, one `330 core` dialect, GLSM descriptor trap, `glGetTexImage` PBO readback with timestamp fence, integer surfaces, GPU safety, GL 3.3 floor |
| `21-integrated-server-render-handoff.md` | `RenderHost` SPI, threading and lifetimes, confirmed state + tail persistence, array ownership transfer, measured LAN tile sizes, dedicated-server options |
| `22-shader-and-compute-languages.md` | ES 1.00 frontend, mangling, caps, GPU-work guard; GLSL 4.30 compute surface, defined arithmetic, D1–D5; TIR/SIR; rack scheduling; HBM check; §1 superseded by 27 where they conflict |
| `23-gl-testing-strategy.md` | Headless GL in the shared workflow, `:gl-testkit`, GLSM leg from the dev jar, tolerance tiers, ocelot `RenderHost`, self-test, Horizon-QA |
| `24-architecture-gl-first.md` | This document |
| `25-angelica-version-floor.md` | Angelica version policy (2.2.8 supported, 2.1.14–2.2.7 best-effort, older gated off at runtime), linkage matrix over all 118 2.x releases, M0's GL call set and deny-list, code rules R-1–R-13, test matrix, GTNH pack coverage |
| `26-lessons-from-general-engines.md` | Lessons from Godot, OpenSceneGraph, O3DE, small-program shader languages and PS1/N64/Saturn/Dreamcast/Quake/Doom techniques: 27 adopted (typed handles, GL state hygiene, `GlLedger`, pending links, default samplers, crash guard, API dump, semantic attributes, palette as an ordered op, quantize and dither, the `#include <og/…>` retro variant family), considered items, five owner questions |
| `27-graphics-language-es300.md` | Graphics shader language as an ES 3.00-based subset: input form, accepted subset, built-ins against GLSL 3.30, integer guards matching compute, ANGLE driver workarounds re-checked, flat and noperspective, interface conventions, shared frontend, effort, an index8 example |

## Review notes

Review of 2026-10-08 against the owner's decisions, 20–23 (including their Verification notes) and 09. Citations re-checked: `server/machine/Machine.scala` and `server/component/Keyboard.scala` are byte-identical between the local checkout and `ocgtnh-master` (`diff -q`); `InputBuffer.scala:38` (`doesGuiPauseGame = false`), `:66-116`, `Keyboard.scala:143`, `Machine.scala:320`, `:649-651`, `:865-879` hold as cited. Plan efforts still sum to 22–30 w. Fixes:

1. **§2.1 boundary row (09 leftover, decision 10).** 09 §3.2 detects LuaJIT partly by the absence of `string.pack`, which decision 10 invalidates; the row now requires OpenGPU's marker or a behavioural probe. "`PipelineDesc` the only table" contradicted §3.3's `createComputeList(desc)`; the rule is restated as "configuration-time tables only, never per frame or per dispatch".
2. **§3.3 compute.** `dispatch`'s `bindings`/`uniforms` had no type, leaving room for a per-dispatch table (11 §2.3's RAM drain). They are now byte strings (handles; std140 rows ≤ 64, 22 §2.1) [proposal]. Added 22 §2.2's caveat that `int64_t` is exact in Lua only on 5.3/5.4.
3. **§2.2 superseded row.** `RenderWorldLastEvent` was attributed to 09 §3.7; 09's M5 checklist already uses START. The alternative 20 §1 corrects is 02 §5 / 08-B §5.5's.
4. **§3.1 modules.** `:gl-testkit` also needs GLSM's transitive dependencies (23 verif. 5). The harness's `NullRenderHost` and `:core`'s `NoRenderHost` were two names for one contract; unified on `NoRenderHost`, with 21 §5 (a)'s readback (confirmed image) chosen over 23 §4.1's `nil, reason`, matching §3.3 and §4. The bytecode reference test now also forbids common references to `:gl` and `org.lwjgl` class names in string constants (decision 9's second footgun, otherwise stated only in §1).
5. **§3.4 / §6, phase dependency.** M2 required "VS/FS → SIR in CI" and §5 said VS/FS are "interpreted in CI" from M0, but the SIR interpreter is an M3 deliverable (22 impl. 11). M2 now does lowering with structural checks; interpretation of the graphics corpus starts in M3 (added to M3's done list).
6. **§3.7 state rules.** Save/restore covered units 0–1 (20 §1's internal passes), but user programs bind up to 4 sampler units (§3.4, 22 §1.3); now units 0–3 [I]. The forced-state list gained 20 §1's polygon offset, logic op, colour/depth masks and rasterizer-discard check.
7. **Seam 4 (check e).** Seams 1–3 appeared in §3, but seam 4 (rasterization rules in GL terms) did not; §3.7 now states it. §5's "Seams kept from M0" was wrong for seam 2 (M2/M3) and is now per-milestone with architecture locations.
8. **§3.8 layer 3.** "Until M3 … trusted LAN guests" implied LAN before M3; LAN arrives in M4. Reworded, and layer 5 (kill switch, M1) added to the pre-M3 set.
9. **§4 (check d).** Behaviour of a host *without* usable graphics was unstated (20 §7 bitmap vs 21 §1.3 "behaves like a dedicated server"). Added one paragraph: same API results as a dedicated server, guests get the confirmed image, the host's own displays show the "no graphics" bitmap. §4 is marked as the single statement of dedicated-server and LAN-guest behaviour.
10. **§6 plan.** M0's done list used "self-test readbacks" while the self-test was scheduled in M1; a minimal `/opengpu selftest` moved into M0, and the four `RenderHost`s are named. M0's `NullRenderHost` criterion was made concrete. M2's calibration needs WARP, but the non-gating Windows workflow was scheduled nowhere; it is now an M2 item (absorbed in M2's ±50 % estimate [est.]), and §3.9 says so. M2's "safety layers 1, 2, 4, 5" duplicated M1's layers 4–5 and was reworded. Vague criteria made testable: M1's abuse programs (named corpus, tick/frame bounds [proposal]), "(+5.4)" → owner's instance only (decision 10), M4's "dedicated soak" (duration, load, pass conditions [proposal]).
11. **§7 Q3.** 20 §5's 1 s `"dropped"` and §3.3's "canvas mode never skips" conflicted with no rule for whether a dropped frame leaves the tail; added the question with a default.

Checked without change: (a) all twelve decisions are honoured, and none is re-asked: Q1 (GL floor) and Q8 (Angelica floor) are open in 20/22/23, not owner-decided; (b) the readback path (`glGetTexImage` + PBO, not `glReadPixels(…, long)`), timestamp-query fence, latency figures (21 §2.3), LAN sizes (21 §4.2) and caps match 20–23 after their verification notes; (f) mirror, CRC resync, lossy/LOD governor, token buckets, OC keyboard topology and `hasKeyboard`, aarch64, `runClient21` and bit-exact graphics determinism appear only in §2.2–§2.3 as superseded or dropped.

## Amendments after review (2026-10-08)

- §1: added decision 13 (display block: OC-model walls, Energy Control-style shape options, settings tab, clean-room reference, MIT).
- §2.3: multi-block walls removed from the deferred row; they are scheduled in M1.5.
- §3.10: every wall constituent keeps its own node; a later in-world touch option uses the screen-surface helper on the tilted plane.
- §6: M0 scope gains the block seams (facings, shape fields, screen-surface helper, one framebuffer per wall, maximal render bounds); new milestone M1.5 Display block (2–3 w); M1's block-design checkpoint, M4's single-block variants and M5+'s multi-block item removed; total now 24–33 w.
- §6: the block-design paragraph now records the settled design and the Energy Control pitfalls.
- §3.3: added `setFramePolicy("latest" | "every")` after the owner asked for configurable frame skipping.
- §6: M0 measures readback on Intel, NVIDIA and AMD.
- §7: recorded the owner's answers to all eight questions; the Angelica floor is delegated to report 25.
- §7 answer 7: corrected the owner's main machine to a mini desktop and named the `dgpu-run.ps1` wrapper for per-run GPU selection.

## Amendments (2026-10-09)

After report 25 (`25-angelica-version-floor.md`):

- Header: version scope is Angelica 2.2.8 or later (2.1.14–2.2.7 best-effort) or absent (25 §1.1).
- §3.1 root row: compileOnly `Angelica:2.2.8:api` instead of 2.2.21 (25 R-11).
- §3.1 `:gl-testkit` row: the GLSM leg uses the 2.2.8 and 2.2.28 dev jars instead of 2.2.21 (25 §6).
- §3.7: the Angelica version gate added as the second branch; the table column is now "Angelica 2.2.8+".
- §3.7 GL-calls row: points to 25 §4's call set and deny-list.
- §3.7 common rules: explicit scissor-box save and restore (25 R-1).
- §3.7: "Angelica floor 2.2.21; linkage-tested with 2.2.28 and the latest release" replaced by the "Angelica versions" paragraph (policy, runtime gate, CI and in-game matrix; 25 §1.1, §6).
- §3.7: new "Code rules from M0" paragraph: R-4, R-8 and R-9 stated, the other rules of 25 §5.1 listed by id.
- §4: an Angelica older than 2.1.14 added to the hosts without usable graphics.
- §6 M0 scope: render bounds restated as one constant maximal box from the first call (R-9).
- §6 M0 scope: TESR tied to R-4; display item drawn through the chassis's inventory path (R-8).
- §6 M0 scope: linkage jars 2.1.14, 2.2.8, 2.2.21, 2.2.28 and latest over 25 §4's call set, `unmappedGL=FAIL` on the 2.2.x jars, the version gate and the code rules.
- §6 M0 done list: `runClient` on the pinned 2.2.8 dev jar and in the owner's 2.2.21 instance, the TESR-neighbour check under a blend-overriding pack, and the R-1 hygiene case on the GLSM leg (25 §6).
- §6 M1.5 done list: the not-culled check covers a block that becomes the origin after its first render (25 R-9).
- §6 display-block paragraph: TESR bounds are classified per class and cached per instance (25 R-9).
- §7 answer 8: records that the owner left the floor to us and that 25 set it.
- §9: row for 25 added.

## Amendments after the engine-lessons study (2026-10-09)

After report 26 (`26-lessons-from-general-engines.md`); lesson ids are 26's.

- §2.1 boundary row: handles are `type(4)|slot(11)|generation(16)`, type 0 reserved; the validator, `free` and errors decode the type, and the type bits persist (G1).
- §3.1 `:core` row: typed handles (G1).
- §3.1 `:lang` row: the built-in graphics and compute libraries as resources behind `#include <og/…>` (S8, R3).
- §3.1 `:gl` row: `GlLedger` (G10).
- §3.1 root row: `api-dump.json` with a CI diff from M1, and command-format-version dispatch for side files (G12).
- §3.3 `getCaps()`: graphics reason `"host GPU on device list"` (G6).
- §3.3: new `setPalette` bullet: an ordered op, expand-only presents, `readPixels` unaffected (R7).
- §3.3: new `setDither` bullet, default `"none"` (R8).
- §3.3: new "API invariants" bullet (G2).
- §3.3 Errors: `opengpu_reset` adds `"suspended after host crash"` (G11); pending links point to §3.4 (G4).
- §3.3 Shaders: `programInfo` returns uniform defaults (S7); the library comes through `#include <og/…>`, with no `programSource()` (S8).
- §3.3: new Pipelines bullet: semantic streams with POSITION mandatory (S5), per-instance step (R11), `sub`/`avg`/`addq` (R9).
- §3.4 graphics paragraph: the typer is driven by a `BuiltinInfo` table (S2).
- §3.4: new "Interface additions" list: semantic attributes without `glVertexAttrib4f` (S5, V1), uniform initializers (S7), `noperspective` behind `#extension` (R1), `og_TargetSize` and `og_snap` (R2), unbound-sampler defaults (G5), pending-link semantics (G4), documented conventions (S10), the variant family and separate graphics and compute libraries (R3, S8).
- §3.5 client-thread row: uploads, links and deletes count against `renderBudgetMs`, at most 8 objects per frame (G13a).
- §3.6: confirmed state includes the palette and the typed handle table (R7, G1).
- §3.6: side files carry the command-format version and are decoded by version from M1 (G12); Load checks the crash guard first and is spread by the creation budget (G11, G13a).
- §3.6 GPU memory: eviction and `stats()` read `GlLedger` (G10).
- §3.7: the renderer device list as a third branch, shipped empty in M1 (G6).
- §3.7: new "State rules" paragraph after the R-rules: pixel-store forcing (G8), no constant generic attributes (V1), no `glProvokingVertex` (R10a), default textures on unbound samplers (G5).
- §3.7: new "Quantize functions and index textures" paragraph: 565 round-to-nearest, bracketing ordered dither, exact index8 nearest with lowest-index ties; user `index8` textures as `GL_R8`; integer surfaces private (R8, R5, G5).
- §3.8: new layer 5, the crash guard (M2; G11); the kill switch is now layer 6, and layer 3's pre-M3 set reads 1, 2 and 4–6.
- §3.9: the hygiene start state adds pixel-store and generic-attribute state (G8, V1); ledger assertions (G10); the raylib corpus with a per-file licence check (S12); fog without `exp()` in tier S (R4).
- §4 LAN guests: keyframes carry the palette, and a 768 B palette record is sent per change (R7).
- §4: a GPU on the renderer device list added to the hosts without usable graphics (G6).
- §5 seam 4: `noperspective` interpolation and the documented `gl_FragCoord`, depth and matrix conventions from M2 (R1, S10).
- §6 intro: total 24–33 w → ≈ 28–40 w.
- §6 M0: scope adds G1, G8, V1, G10 and G2's text; measurements add G7 (a) and (b), upload cost per MB (G13b) and instanced vs expanded 2D quads (G16); effort 4–6 w → 4.5–7 w.
- §6 M1: scope adds R7, R8 (the 565 quantize and `setDither`), the index-texture format rule (G5, R5), G12, G6's matcher and G13a; the done list adds the palette-only frame check, without 26's "reaches a test guest stream", because the guest stream arrives in M4 (whose done list covers palette-only frames); effort 4–5 w → 5–7 w.
- §6 M2: design review first (§7 questions 9–13); "built-in shaders written in the subset (unlit, textured, Gouraud, Blinn-Phong)" replaced by the `#include <og/…>` variant family (R3, R4, S8); scope adds G4, G5, G11, S2, S5, S7, S10, S12, R1, R2, R5, R8 (index8), R9 and R11; safety layers renumbered; the done list adds the unbound-sampler corpus check and the PS1-style example at 20 fps on T2; effort 5–7 w → 7.5–11 w.
- §6 M4: the guest path carries palette keyframes and records (R7); the API dump lints §3.3's invariants at the freeze (G2); the done list adds the guest hash after palette-only frames (R7) and a Gen9 Intel iGPU in the beta (G6).
- §7: questions 9–13 for the M2 design review, from 26 §6 with 26's defaults, not yet answered; S6's proposed commands are described there rather than in §3.3, and question 13 notes that M1 ships the `"none"` default before the review.
- §7: a "Considered, not scheduled" list of 26's CONSIDER items with their decision points.
- §8 risk 3: OC machine persistence as the crash-loop vector, and the crash guard in the mitigation (G11); risk 6: pixel-store forcing and the generic-attribute rule (G8, V1).
- §9: row for 26 added.

## Amendments after the owner's answers (2026-10-09)

- §1: decision 14 (OpenComputers runtime floor 1.12.64-GTNH) and decision 15 (showcase shaders are examples, not mod API).
- §3.1 `:lang` row: no built-in shader libraries and no `#include`; the preprocessors keep `#line` for composed sources.
- §3.3: the Shaders bullet now has source arriving as Lua strings, composed by `opengpu.lua` with `#line`; new Transforms bullet (S6 accepted).
- §3.4: the Libraries bullet became Examples, not libraries.
- §6 M2: design review covers questions 11–12; engine-derived transforms added; the variant family moved to `examples/`.
- §7: questions 9, 10 and 13 answered; 11 stays open for the M2 review; 12 awaits the owner after an assessment.

## Amendments after the language decision (2026-10-09)

After report 27 (`27-graphics-language-es300.md`), which specifies the graphics language the owner chose when changing decision 5:

- §1 decision 5: graphics shaders are a GLSL ES 3.00-based subset (`#version 300 es` required) with the same hang-safety model, compute integer results through emitter guards and `#version 330 core` output; 27 supersedes 22 §1's ES 1.00 subset.
- §2.2: Superseded row for 22 §1's ES 1.00 subset.
- §3.1 `:lang` row: one GLSL-family frontend with a graphics and a compute profile sharing the components of 27 §8.4.
- §3.3 Shaders: sources start with `#version 300 es`, written once by `opengpu.lua`; `programInfo` warns about flat outputs that may differ per vertex; int, uint and bool uniform rows travel in `og_i_I` via `glUniform4iv`. Pipelines: `layout(location = n)` on a user vertex `in` names semantic slot n.
- §3.4 graphics paragraph: replaced by 27 §11 item 5's text (float loop indices only with relational operators, 27 verification note V3; array cost and size cap, V6; out-of-range `texelFetch` returns 0, V8), keeping the sentence on sampler units and the Y flip, which 27 does not change. Caps: varying rows counted by ES 3.00 §11 with interpolation classes separate, uniform rows counting `og_i_U`, `og_i_I` and used built-in rows, ≤ 64 `switch` labels, arrays ≤ 64 vec4-sized elements.
- §3.4 interface additions: user vertex `in` with `layout(location)` slots; the S7 departure cites ES 3.00 §4.3; `noperspective` behind `GL_NV_shader_noperspective_interpolation`; the `og_TargetSize` bullet is now `og_Resolution`, with `round()`'s implementation-defined .5; `dFdy` and `gl_FrontFacing` conventions; new Flat varyings bullet (R10b).
- §3.4 compute paragraph: `switch`, float→uint saturation with NaN → 0, `clamp` with lo > hi as `min(max())`, default returns, constant float overflow as a compile error, half-even `round`.
- §3.8 layer 1: integer guards and clamping of every dynamic index.
- §3.9: raylib's `glsl100` shaders converted and its `glsl330` shaders added, implicit-conversion files as expected rejections; the integer conformance corpus; `glslangValidator` checks `#version 300 es` inputs and `330 core` outputs.
- §5 seam 4: the flat 'either' rule, with LAST in the CPU reference.
- §6: intro total ≈ 28–40 w → ≈ 30.5–44 w; M2 scope (ES 3.00-subset frontend, `switch`, integer guards, range analysis, conformance corpus, R10b), done list (conformance corpus bit-exact on llvmpipe and the three owner GPUs) and effort 7.5–11 w → 11–16.5 w; M3 scope (compute profile of the shared frontend, with `switch`) and effort 6–8 w → 4.9–6.2 w (27 §9, as corrected by its verification note V9). The rows now sum to 30.4–43.7 w, or 30.6–43.8 w with 27 §9's unrounded M2 of 11.2–16.6 w.
- §7: question 12 answered (ES 3.00-based subset, decision 5 changed, specified in 27), and the review list notes that its effort is in §6; M2's design review covers question 11 only; S13 and R10b left the "Considered, not scheduled" list.
- §8: risks 17 (driver integer, `switch` and flat-varying miscompiles) and 18 (ES 1.00 sources no longer parse).
- §9: row for 27; 22's row notes that 27 supersedes its §1 where they conflict.
- Editor follow-up: §3.4's varying rows are also split by base type (27 §8.2, note E1), and §7's M2-review header now says which questions are answered. Reports 20, 22 and 26 carry pointers to 27 where they still describe ES 1.00 or GL 2.1.
- Owner follow-up (2026-10-09): the nine open questions of 27 were answered with their defaults (§7); §3.4 S5 now states every predeclared input type.
