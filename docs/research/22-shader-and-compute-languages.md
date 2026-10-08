# 22 — Shader and compute languages and the compiler pipeline under Option 2

Research date: 2026-10-08. Scope: the GLSL ES 1.00 graphics subset that runs on the host GPU, the GLSL-compute-syntax kernel language that runs on the server CPU, the shared IR with its GLSL and JVM-bytecode emitters, compute scheduling across cards and racks, and the HBM NTM sanity check. §1 is superseded by 27 (ES 3.00-based graphics language, 2026-10-09) where they conflict; the frontend pipeline, std140 reflection, caps method, GPU-work guard and §1.5 stay. This report builds on 06 §4-5, 05 §1 and 09 §3.3 and corrects them where Option 2 changes the premises (the owner's decisions of 2026-10-08 override 09). Primary leg: Java 8 + LWJGL 2.9.4, with Angelica 2.2.21 present or absent. Path prefixes are defined under Sources. **[est.]** marks estimates, **[inf.]** inferences.

## Summary

1. Validation stays mandatory although the GPU executes. Code written by any LAN player runs on the host GPU, and a Windows GPU hang resets the device after 2 s, which LWJGL2 cannot survive **[inf.]**. ANGLE carries driver-specific rewrites, and Angelica's GLSM rewrites every shader source it is handed. ANGLE's Intel rewrites for integer unary minus and float `isnan` are D3D11-backend workarounds for 2014-2016 drivers, and its GL backend's Intel rewrites (integer `abs`, loop conditions, `texelFetchOffset`) apply on macOS only (`renderergl_utils.cpp:2294, 2296, 2451`); NVIDIA gets `atan(y, x)` emulation, `gl_FragDepth` clamping, repeated-assignment rewriting and constructor scalarization (27 §5).
2. One output dialect, `#version 330 core` (24 §7 answer 1 dropped `#version 120`): Angelica's default profile gives a 3.3-4.6 core context, its opt-in ES profile cross-compiles the shader to ES 3.20, and any vanilla context of GL 3.3 or newer accepts it. Every user name is mangled to `og_*`.
3. OpenGPU packs uniforms itself, in std140 layout, into one `vec4` array per stage, uploaded with one `glUniform4fv`; rows with int, uint or bool members go to an `ivec4` array uploaded with `glUniform4iv` (27 §2.5). The caps are OpenGPU's own, chosen within what GL 3.3 guarantees, not queried from the host, so programs stay portable. GPU work per frame is bounded by running the `gl_Position` slice of each vertex shader on the CPU.
4. Compute uses GLSL 4.30 compute syntax (`#version 430`, `local_size`, std430 `buffer` blocks, unsized arrays), executed on the CPU. Its semantics are fully defined (WGSL-style division by zero, Java overflow and shifts, `StrictMath`), and `double` and `int64_t` are in v1.
5. 09's rule of one write-only output indexed by the global id is too strict. It is replaced by: any number of readonly and writeonly bindings, no aliasing of writable bindings, and at most one writer per element. Single writers are proven at dispatch time for affine indices and checked with a compare-and-swap ownership array otherwise.
6. One structured IR in two forms: the vector-typed form feeds GLSL; the scalarized form feeds bytecode and an interpreter, which is worth keeping.
7. A rack hosts at most 16 cards (T3 server: 4 slots, at most 2 of them T3; only creative servers take 4 T3 cards). A 15×15 RBMK step costs about 0.1 ms **[est.]**. OC I/O, not the kernel, is the bottleneck, so simulations need compute command lists (K ping-pong steps per Lua call).

## Findings

### 1. Graphics language: GLSL ES 1.00 subset executed by the host GPU (superseded by 27 where they conflict)

**Status after the language decision (2026-10-09).** The owner changed decision 5 (24 §1): graphics shaders are written in the ES 3.00-based subset that 27 specifies, and 27 governs where it conflicts with this section. Still valid here: the reasons for validation (§1.1, with the ANGLE attribution corrected by 27 §5); the frontend pipeline with its trip-count simulation, costing and zero initialization (§1.2); the mangling scheme, std140 reflection, semantic attribute binding and Y orientation (§1.3, extended by 27 §2.5 and §7); the caps method and the per-frame GPU-work guard (§1.4); and caching, threading, errors and pending links (§1.5). Replaced by 27: the ES 1.00 keyword set and reserved operators, the Appendix A §5 index classes and §7 varying packing, the ES 1.00 → 330 translation table and the `#version 120` dialect.

#### 1.1 Why validation stays mandatory

| Reason | Evidence |
|---|---|
| **GPU hangs crash the host.** Windows resets the adapter when a GPU task cannot finish or be pre-empted within the timeout, and the application must re-create its device. LWJGL2 and Minecraft have no context-loss path, so the host crashes and every LAN guest is dropped **[inf.]**. | Microsoft TDR: "The default timeout period in Windows is two seconds"; "An application must release and then re-create its ... device" |
| **Terminating is not the same as short, and Appendix A does not even guarantee termination.** `for (int i = 0; i < 2147483647; i++)` is valid under Appendix A. So are `i += 0` (the increment is any `constant_expression`) and a `float` index stepping by 1.0 past 2²⁴, where `f + 1.0 == f` in fp32; both never terminate **[inf.]**. ANGLE additionally rejects "easy-to-detect infinite loops". | GLSL ES 1.00 App. A §4; ANGLE `ShaderLang.h:446-448` |
| **Drivers diverge.** ANGLE ships per-vendor rewrites, but its Intel rewrites for integer unary minus and float `isnan` (`rewriteIntegerUnaryMinusOperator`, `emulateIsnanFloatFunction`) are D3D11-backend workarounds for 2014-2016 drivers, and its GL backend enables `emulateAbsIntFunction`, `addAndTrueToLoopCondition` and `preAddTexelFetchOffsets` (`rewriteTexelFetchOffsetToTexelFetch`) for Intel on macOS only; it has no Intel-specific shader rewrite on Windows. NVIDIA gets `emulateAtan2FloatFunction`, `clampFragDepth`, `rewriteRepeatedAssignToSwizzled` and `scalarizeVecAndMatConstructorArgs`, all Apple GPUs `unfoldShortCircuit`, and drivers that "get context lost if gl_FragColor is not written" `initOutputVariables`. The owner's GPUs are Intel, NVIDIA and AMD (24 §7 answer 7); 27 §5 keeps undefined integer cases away from drivers and tests all three. | ANGLE `ShaderLang.h:203, 228-237, 258-282, 332`; `renderergl_utils.cpp:2294, 2296, 2451` (27 §5) |
| **Driver compilers are fragile.** ANGLE caps expression, statement and call depth (256 each), parameters (255) and macro tokens (10 000). GraphicsFuzz exists to find driver shader-compiler bugs. | ANGLE `ShaderLang.cpp:273-277`, `MacroExpander.cpp:25`; github.com/google/graphicsfuzz |
| **Undefined behaviour.** WebGL zero-initializes variables and forbids out-of-range access; ANGLE implements both. | WebGL 1.0 "Initial values for GLSL local and global variables", #OUT_OF_RANGE_ARRAY_ACCESSES; ANGLE `ShaderLang.h:211, 286` |
| **GLSM rewrites sources.** `glShaderSource` always renames `sample` and `new`; `sampler` is renamed only when the backend's minimum GLSL version is ≥ 400, which the LWJGL2 backend never reports (it returns 330). When GLSM's FFP `ShaderManager` is enabled, a source whose first `#version` is not ≥ 330 with a literal `core` is version-rewritten to `#version N core`, and AST-rewritten when it uses compat built-ins or `attribute`/`varying`; in the ES profile every source is also cross-compiled to ES 3.20. | ANG `GLStateManager.java:5822-5831`, `GlslTransformUtils.java:49-56, 74-84`, `CompatShaderTransformer.java:53, 67-69, 117-160, 167-178, 652-668`; `backend/Lwjgl2GLRenderBackend.java:115`; 02 §1 |
| **Errors come back synchronously.** The pure-Java frontend runs inside the Lua call, so errors carry `line:col`, and it works on dedicated servers too. | design |

#### 1.2 Frontend

The frontend is hand-written, as 06 §4 proposed: preprocessor → lexer → Pratt parser (expressions) plus recursive descent → type checker → restriction checker → cost analyser → lowering. Points that 06 and 09 left open or got wrong:

- **Preprocessor.** OpenGPU implements the ES 3.00 preprocessor (27 §1.4) itself and emits fully preprocessed text, so the driver's preprocessor never sees user input. `GL_ES` is defined to 1, so WebGL-style `#ifdef GL_ES` precision blocks work. Expansion is capped at depth 32 and 10 000 tokens, after ANGLE `MacroExpander.cpp:25, 433-440`.
- **Lexing and types.** ASCII source after comment stripping, tokens ≤ 256 characters (WebGL, both rules). Identifiers are capped at 64 to leave room for mangling, and any identifier containing `__` is rejected (27 §7). There are **no implicit conversions**; overload resolution is exact-match. Built-ins can be neither overloaded nor redefined (ES 3.00 §4.2.3, §6.1; ES 1.00 allowed overloads, 27 §1.3). Struct nesting is capped at 4 (WebGL #MAX_STRUCT_NESTING).
- **Appendix A, checked as ANGLE does.** Loop-index form follows `ParseContext.cpp:4219-4445`. `while` and `do-while` are rejected (`:4587`; WebGL #SUPPORTED_GLSL_CONSTRUCTS). Recursion is detected on the call graph (`:10919-11007`). The reserved prefix is `og_` instead of `webgl_` (`:1576-1587`). `for` is extended to uint indices, float indices take only `<`, `<=`, `>`, `>=`, and `switch` is allowed (27 §2.3).
- **Correction to 09 ("no dynamic indexing").** Appendix A §5 *mandates* indexing vertex-shader uniforms with any integer expression, and ANGLE exempts that case (`ParseContext.cpp:7747-7753`). Allow it, because skinning needs `bones[i]`, and clamp the index (ANGLE `clampIndirectArrayBounds`). Everything else stays limited to constant-index-expressions. *Superseded: all dynamic indexing of arrays, vectors and matrices is allowed and clamped (27 §2.6).*
- **Trip counts are simulated** at compile time with the loop's own int or float arithmetic. Non-terminating loops and loops over 1 024 iterations are rejected; float indices get a +1 margin **[inf.]**. Static cost is the sum of scalar ops weighted by trip counts.
- **Precision qualifiers** are parsed and dropped. A missing fragment default precision is accepted, which departs from ES 3.00 §4.5.4 (ES 1.00 §4.5.3). The minimum guarantee is highp; actual results are whatever the host GPU produces.
- **Constant folding** must evaluate every built-in except texture lookups (GLSL ES 1.00 §5.10), so the frontend contains a typed evaluator anyway (§3.3).
- **Zero initialization** of locals, `gl_Position` and the fragment output (ANGLE `initializeUninitializedLocals`, `initGLPosition`, `initOutputVariables`), plus default returns and `gl_FragDepth` initialization and clamp (27 §2.3, §2.8). `invariant` is accepted and, per 27 §2.4, emitted on both sides of a varying, where ANGLE strips it (`removeInvariantAndCentroidForESSL3`, `ShaderLang.h:190-200`). Mip-mapped lookups in non-uniform flow are undefined (App. A §6); v1 textures are non-mipmapped (09 §3.7).

#### 1.3 Emitter, dialects and binding model

**Which contexts occur.** With Angelica in its default `glProfile=AUTO` (or `CORE`) the context is GL 3.3-4.6 core, probed from the highest version down, 4.1 at most on macOS (02 §1; `MixinForgeHooksClient_CoreProfile.java:63-129`; the owner's log says "Created GL 4.6 core profile context"). With the opt-in `glProfile=ES` (`-Dangelica.glProfile=es`) it is a GL ES 3.2 context instead (`:52-61, 141-151`; `AngelicaConfig.java:452-457, 479-484`). Without Angelica, Forge calls `Display.create(format)` with no `ContextAttribs` (MC `net/minecraftforge/client/ForgeHooksClient.java:318-330`), which yields a legacy context at the driver's compatibility version. That is 4.x on Windows NVIDIA, AMD and Intel drivers; macOS legacy contexts and old Mesa drivers may give only 2.1-3.2 **[inf.]**. A `#version 330 core` shader is valid in a compatibility context ("everything specified for the core profile is also available in the compatibility profile", GLSL 3.30 §3.3). Dialect A therefore covers Angelica and every vanilla context of 3.3 or newer. Dialect B (`#version 120`) would cover only vanilla contexts below 3.3; GLSL 1.20 reserves `lowp mediump highp precision` (§3.6) and guarantees only 16-bit integers (§4.1.3). 24 §7 answer 1 dropped it, and the ES 3.00 subset's 32-bit integers (27 §4) rule it out anyway, so A is the only output.

| ES 3.00 input (27) | `#version 330 core` |
|---|---|
| identifier `x` | `og_u_x`; vertex inputs `og_a_x`, varyings `og_v_x`, samplers `og_s_x`, the fragment output `og_o_x`, struct members `og_f_x`; OpenGPU's own names `og_i_*` (e.g. `og_i_U`, `og_i_I`, `og_i_Position`) |
| vertex `in vec3 p;`, optionally with `layout(location = n)` | `layout(location = k) in vec3 og_a_p;`, k the semantic slot (GLSL 3.30 §4.3.8.1; 27 §7) |
| VS `out` / FS `in`, with `smooth`, `flat` or `noperspective` | as written; integer varyings `flat` on both sides; `centroid` dropped (27 §2.4, §6) |
| the one fragment output, `out vec4 color;` | `layout(location = 0) out vec4 og_o_color;` |
| texture functions (`texture`, `textureProj`, `textureLod`, ...) | unchanged; `texelFetch` and `texelFetchOffset` through a bounds-checked helper (27 §3) |
| integer `/`, `%`, shifts, float→int and float→uint | guard helpers, or native operators where ranges are proven (27 §4) |
| precision qualifiers and statements | removed |
| uniforms | `uniform vec4 og_i_U[n];` and `uniform ivec4 og_i_I[m];`, accesses rewritten (27 §2.5) |
| `gl_Max*` constants | OpenGPU cap literals |
| line mapping | `#line N` before each statement |

- **Mangling** follows ANGLE's `_u` prefixing (`HashNames.cpp:57-70`). It prevents collisions with 3.30 keywords and built-ins that are free names in ES 1.00 (`texture`, `round`, `sample`, `smooth`, `layout`, ...). It also means GLSM's `\bsample\b`-style regexes cannot match: `og_u_sample` has no word boundary before `sample`. Each name class needs its own prefix that no other class can produce: with a single `og_` prefix for user names, a user global `a_p` would become `og_a_p` and collide with attribute `p`, and a user `U` would collide with the packed uniform array. Identifiers beginning with `_` are rejected so that no emitted name contains `__`, which GLSL 3.30 reserves **[inf.]**. 27 §7 adds `og_o_` for the fragment output and `og_f_` for struct members, which keeps GLSM's word-boundary `new` renaming off a member access such as `s.new`, and rejects any identifier containing `__`, which a prefix would otherwise carry into the emitted text.
- **GLSM is a no-op on this output.** With a literal `core`, `CompatShaderTransformer` skips the source, and FFP emulation applies only to programs with compat uniforms (02 §1). Every call needed is GLSM-mapped: `glBindAttribLocation`, `glGetUniformLocation`, `glUniform4`, timer queries (ANG `GLStateManager.java:7034, 7062-7063, 7712, 7815, 7958`). Angelica's `glProfile=ES` cross-compiles even core shaders to ES 3.20 (02 §1), which is reachable but untested. SDL-GPU (lwjgl3ify only) is out of scope.
- **Uniform model.** Lua sees one program block with **std140** offsets (GL 4.3 §7.6.2.2, rules 1-10), which map directly onto `vec4` rows. Each stage gets an `og_i_U[n]` holding only the rows it reads (`mat4` → `mat4(og_i_U[k], …, og_i_U[k+3])`). The host uploads it with one `glUniform4fv` per program change. Rows with int, uint or bool members go to `ivec4 og_i_I[m]` via `glUniform4iv` (25 C68), because float rows cannot carry them bit-exactly (27 §2.5). Rows used are exactly what OpenGPU counts, so driver packing cannot turn an accepted program into a link failure. That risk is the reason behind Appendix A §7 and ANGLE's `CheckVariablesWithinPackingLimits` (`ShaderLang.h:895-902`).
- **Samplers, attributes, inputs.** Samplers get units 0..3 in declaration order via `glUniform1i` after link (`layout(binding)` needs 4.20). Attributes bind by semantic, not by declaration order (26 S5): the built-ins `og_Position` (vec3), `og_Normal` (vec3), `og_TexCoord` (vec2), `og_Color` (vec4) and `og_Custom0..3` (vec4) have fixed locations POSITION 0, NORMAL 1, TEXCOORD 2, COLOR 3 and CUSTOM0-3 4-7 and are emitted as `og_i_*` names, and a user-declared vertex `in` takes a free CUSTOM slot, or slot n under `layout(location = n)` (27 §7), so one mesh serves any shader that reads a subset of its streams. POSITION is mandatory, so location 0 is always used. A stream the shader reads but the mesh lacks is fed from a 1-element VBO whose divisor exceeds any instance count, or replaced by a constant in the emitted program, never with `glVertexAttrib4f`, because Angelica's constant-attribute cache does not see that call (26 V1). Vertex formats (float32×1-4, unorm8×4, snorm16) are checked against attribute types when the pipeline is created.
- **Y orientation.** Negating `gl_Position.y` in an epilogue and swapping `glFrontFace` makes FBO row 0 the top row. Readback then needs no flip, and `gl_FragCoord.y` counts from the top **[inf.]**.

#### 1.4 Caps and the per-frame GPU-work bound

| Resource | ES 1.00 / WebGL1 | ES 3.00 | GL 2.1 | GL 3.3 core | **OpenGPU v1** |
|---|---|---|---|---|---|
| vertex attributes | 8 | 16 | 16 | 16 | **8** |
| VS uniform vec4 | 128 | 256 | 128 (512 comps) | 256 | **128** physical rows: `og_i_U` + `og_i_I` + used built-in rows (27 §8.2) |
| FS uniform vec4 | 16 | 224 | 16 (64 comps) | 256 | **64**, counted the same way (GL 2.1 host must report ≥ 256 comps) |
| varyings (vec4) | 8 (App. A §7 packing) | 16 out / 15 in (§11 packing) | 8 (32 floats) | 15 (60 comps) | **8** rows packed as in ES 3.00 §11, smooth, flat and noperspective (and float, int and uint) never sharing a row, emitted as at most 8 vec4-sized outputs (27 §8.2) |
| FS / VS samplers | 8 / 0 | 16 / 16 | 2 / 0 | 16 / 16 | **4 / 0** |
| draw buffers / max texture | 1 / — | 4 / — | 1 / 64 | 8 / 1024 | **1 / 1024** |
| texel offsets | — | -8 / 7 | — | -8 / 7 | **-8 / 7** |
| `switch` labels | — | — | — | — | **64** (27 §2.3) |
| array size (vec4-sized elements) | — | — | — | — | **64** per local or global array (27 §2.6) |
| source / identifier / struct nesting | — / 256 / 4 | — / 1024 / — | | | **16 KB / 64 / 4** |
| expr / stmt / call depth / params | ANGLE 256/256/256/255 | | | | **64 / 16 / 8 / 16** |
| loops; static ops | App. A | unbounded (§6.3) | | | ≤ 1 024 iterations, nesting ≤ 3; ≤ 4 096 per fragment, ≤ 16 384 per vertex (09) |

Sources: GLSL ES 1.00 §7.4; GLSL ES 3.00 §7.3, §11 and 27 §8.2; the GL 2.1 `glGet` reference page ("must be at least 512 / 64 / 32 / 16 / 2"; vertex texture units "may be 0"); GL 3.3 core Tables 6.42, 6.44 and 6.45. A GL 3.3 host meets every cap. A GL 2.1 host is accepted after a one-time query through `GlCaps` (14 §3).

**Per-frame GPU-work bound.** Per-invocation caps do not bound a frame, and assuming full-screen coverage per triangle would reject ordinary scenes (T3, 1 000 triangles, a 100-op fragment shader: 2.6·10¹⁰ ops). Positions, however, are a pure function of uniforms and attributes: v1 has no vertex texturing, and both inputs live on the card. The validator therefore runs only the **`gl_Position` slice** of the vertex shader as bytecode on the server (typically about 30 ops). It clips each primitive's bounding box to the viewport, sums area × fragment cost plus vertices × vertex cost, and rejects the frame with a deterministic Lua error above a tier cap. Suggested caps are 5·10⁷ / 2·10⁸ / 8·10⁸ ops, about 16 ms at a pessimistic 50 Gop/s **[est.]**, far below the 2 s timeout. The pre-pass costs about 0.05 ms for 1 500 vertices **[est.]**. `GL_TIME_ELAPSED` queries (core in GL 3.3 §5.1) then throttle cards whose measured GPU time is over budget.

#### 1.5 Caching, threading, errors

- **Frontend.** Runs in `createProgram` on the OC worker thread: about 0.1-1 ms for 16 KB **[est.]**, charged at 1/4 and limited to 4 compiles per second per card (09 §3.2). Cached by `SHA-256(source ‖ stage ‖ compiler version)`.
- **Errors.** Lua receives `nil, "fs:12:5: 'foo': undeclared identifier"`, up to 8 messages; `programInfo(h)` returns warnings.
- **GL side.** An LRU of about 256 programs, keyed by the emitted text and dialect. Compile and link run on the render thread, outside world rendering, at most one link per frame. Where `KHR_parallel_shader_compile` exists, `COMPLETION_STATUS_KHR` (0x91B1) is polled with plain `glGetShaderi`, so LWJGL 2.9.4 needs no binding. A driver rejection is an OpenGPU bug. It is logged with the source and reported as `opengpu_program(card, h, false, msg)`, with lines mapped back through `#line`. A device whose next op needs a program with a pending link is not runnable: other devices proceed, and its frames back up into `busy`. Link status is first read on the START pass after the one that issued the link (`GL_LINK_STATUS`, or `COMPLETION_STATUS_KHR` where present). A slow link needs no timeout of its own, because 24 §3.3 already reports frames unconfirmed after 1 s as `"dropped"`. After a permanent failure the frame skips only the draws that use that program and keeps the rest, so canvas content is not lost wholesale. There is no program-binary cache in v1: `glProgramBinary` is not GLSM-mapped, so it fails under `unmappedGL=FAIL`/`STRICT` (26 G4).

### 2. Compute language: GLSL compute syntax, CPU semantics

#### 2.1 Accepted v1 subset

```glsl
#version 430
#extension GL_ARB_gpu_shader_int64 : enable          // optional
layout(local_size_x = 16, local_size_y = 16) in;
layout(std430, binding = 0) readonly  buffer Src { float heat[]; } src;
layout(std430, binding = 1) writeonly buffer Dst { float heat[]; } dst;
uniform int W; uniform int H; uniform float k;
void main() {
  uvec3 g = gl_GlobalInvocationID;
  if (g.x >= uint(W) || g.y >= uint(H)) return;
  int i = int(g.y) * W + int(g.x);
  float c = src.heat[i], s = c; int n = 1;
  if (g.x > 0u)           { s += src.heat[i - 1]; n++; }
  if (g.x + 1u < uint(W)) { s += src.heat[i + 1]; n++; }   // y neighbours alike
  dst.heat[i] = c + k * (s / float(n) - c);
}
```

- **Built-ins.** `gl_GlobalInvocationID`, `gl_LocalInvocationID`, `gl_WorkGroupID`, `gl_NumWorkGroups`, `gl_WorkGroupSize`, `gl_LocalInvocationIndex`, with the formulas of GLSL 4.30 §7.1.
- **Types.** `bool int uint float double`, their vectors, `mat2-4`, `dmat2-4`, structs and local arrays, plus `int64_t`/`uint64_t` under the ARB extension. Literal suffixes are `u l ul f lf`. Implicit conversions follow GLSL 4.30 §4.1.10. `% & | ^ ~ << >>` are available.
- **Buffer blocks** use std430 offsets (GL 4.3 §7.6.2.2: std140 without rounding arrays and structs up to `vec4`). An unsized trailing array has `.length()` = (bound bytes − offset) / stride. Qualifiers are `readonly`, `writeonly` or none (§2.4); `restrict`, `coherent` and `volatile` are ignored. Bindings 0..7 take whole OpenGPU buffers. Reflection returns offsets and strides to Lua, so padding such as `vec3` stride 16 never has to be guessed. Buffers are backed by `int[]` words, so views can be reinterpreted and `float` costs one `intBitsToFloat` intrinsic.
- **Uniforms** are std140-packed as in graphics, from a per-dispatch string of at most 64 rows.
- **Control flow.** `if`, `for`, `break`, `continue`, `return`; no `while` or `do`; `switch` as in 27 §2.3 (shared frontend). One relaxation of Appendix A: a loop bound may be a **uniform expression**, costed at dispatch time with the actual values. Loops are capped at 65 536 iterations and 65 536 static ops per invocation (09). Indexing of buffers and locals may be dynamic (bounds-checked). No recursion.
- **Workgroups.** Local size x, y ≤ 256, z ≤ 64, product ≤ 256; ≤ 65 535 workgroups per dimension (WebGPU defaults, 06 §5).

#### 2.2 `double` and 64-bit integers: in v1

On the JVM, `double` and `long` cost the same as `float` and `int`, `StrictMath` is natively `double`, and determinism is unchanged (12 §5). HBM stores RBMK column heat as `double` (HBM `tileentity/machine/rbmk/TileEntityRBMKBase.java:55`) and PWR heat as `long` with `double flux` (`TileEntityPWRController.java:59-67`). Iterated `float` loses precision on values around 10⁴-10⁶ with small deltas **[inf.]**. The syntax is standard: `double` has been core since GLSL 4.00 (4.30 §4.1.4), and `int64_t` comes from `GL_ARB_gpu_shader_int64`. The only cost is at the Lua boundary. Lua 5.2 and OC-LuaJIT have only doubles and lack `string.unpack` (06 §5), so `int64` values are exact only on 5.3/5.4, and `readBuffer` should offer typed decode helpers.

#### 2.3 Defined arithmetic

| Operation | OpenGPU semantics | Basis |
|---|---|---|
| add, sub, mul, shift overflow | wrap to low 32/64 bits | GLSL 4.30 §4.1.3 (mul undefined there); JLS §15.17-15.18 |
| `x / 0`, `x % 0` | `x`, `0` (divisor tested; Java would throw); a constant zero divisor is a compile error, as in WGSL | WGSL §8.8 |
| `INT_MIN / -1`, `% -1` | `INT_MIN`, `0` | WGSL §8.8; JLS §15.17.2 |
| shift count | taken mod the bit width | WGSL; JLS §15.19 |
| `%` with a negative operand | truncating, sign of the dividend | JLS §15.17.3 |
| uint `/ % <`, uint→float | `Integer.divideUnsigned/remainderUnsigned/compareUnsigned`, `(float)(x & 0xFFFFFFFFL)` | Java 8 `Integer` |
| float→int | toward zero, saturating, NaN → 0 | JLS §5.1.3 |
| float→uint | toward zero, saturating to [0, 4 294 967 295], NaN → 0 | JLS §5.1.3's style; 27 §4 I10 |
| `+ - * / sqrt` | IEEE, subnormals kept, no FMA contraction | JEP 306; 12 §5 |
| transcendentals | `(float)StrictMath.f((double)x)` or documented polynomials | 06 §6 |
| `mix length dot ...` | GLSL definitional formula, left-to-right | GLSL 4.30 §8 |
| `clamp(x, lo, hi)` with lo > hi | `min(max(x, lo), hi)`, the definitional formula | GLSL 4.30 §8.3; 27 §4 I11 |
| `round` | half-even, as `roundEven` | GLSL 4.30 §8.3 leaves .5 to the implementation; 27 §3 |
| non-void function whose end is reached | returns the zero value of its type | 27 §2.3 (ANGLE `AddDefaultReturnStatements`) |
| constant float expression folding to ±Inf or NaN | compile error | after WGSL's const-expression rule; 27 §2.1 |

#### 2.4 Determinism rule (replaces 09 §3.3)

09's rule forbids, among other things, a stencil that updates heat and flux per column, a kernel that writes a 2×2 block per invocation (`out[4*g+j]`), and in-place `buf[g] = f(buf[g])`. All of these are deterministic. Proposed rule:

- **D1** Each binding is `readonly`, `writeonly` or read-write.
- **D2** A handle bound to a writable slot appears in no other slot of the dispatch (checked at dispatch).
- **D3** Each element of a writable buffer has at most one writing invocation; repeated writes by one invocation are sequential, so the last wins.
- **D4** Every access to a read-write binding uses one syntactically identical index that passes the injectivity test below; anything else is a compile error that suggests ping-pong.
- **D5** Nothing else is shared: no `shared`, atomics or images in v1.

Under D1-D5, reads see either pre-dispatch data or the invocation's own writes, and no two invocations write one location. The result is therefore independent of execution order and thread count.

- **Static proof.** For an affine write index `e = Σ cᵢ·tᵢ + c₀` over the global-id components and enclosing loop indices, with coefficients built from constants and uniforms, the dispatch sorts terms by `|cᵢ|`. If each term with extent > 1 satisfies `|cᵢ| > Σ_{j<i} |cⱼ|·(Xⱼ−1)`, the mapping is injective; this mixed-radix condition is sufficient **[inf.]**. It covers `y*W+x` with W ≥ X, and `4*g+j`. Zero runtime cost. Extents must be refined by early-exit guards on the global id: in the §2.1 kernel with W = 15 and `local_size_x = 16`, the dispatch extent is X = 16 > W, and only the guard `g.x >= uint(W)` → `return` shrinks it to 15. Without that refinement the commonest grid kernel falls back to the CAS check **[inf.]**.
- **Dynamic check** for other indices (`out[idx[g]]`, nonlinear): an `AtomicIntegerArray` owner per binding, cleared per dispatch. A write stores when `owner[i] == me`, else tries `compareAndSet(i, 0, me)`, else reports a conflict. With two distinct writers one CAS fails in every interleaving, so failure itself is schedule-independent. The cost is one uncontended CAS per such write **[est.]**.
- **On failure** (out of bounds, conflict, cap), every writable binding is zero-filled, and the dispatch is re-run serially up to its first fault so the message (invocation, buffer, line) is deterministic. That costs time only when something fails.

#### 2.5 Excluded from v1, and the path to add them

- **`shared` + `barrier()` (v2).** Each workgroup runs on one thread. The kernel is split at barriers into regions, each executed as a loop over local invocations, with locals that live across a barrier moved to per-invocation scratch. This is pocl's work-group-function technique (portablecl.org kernel compiler docs). The fixed order makes shared-memory races deterministic for free. Barriers would be allowed only in statically uniform flow; GLSL 4.30 §8.16 requires uniform flow but leaves it to the author. Shared memory would be capped at 16 KiB. This lets a 15×15 grid (225 ≤ 256) step many ticks in one dispatch.
- **Atomics (v2).** On shared memory they are deterministic for the same reason. On buffers, integer `atomicAdd/Min/Max/And/Or/Xor` are deterministic when the result is unused, which the compiler can check. Float atomics stay excluded because float addition is not associative.
- **Images** are excluded. Textures live on the host GPU and compute runs on the server.

### 3. Backend: one IR, two emitters

**IR levels (correction to 09).** 09 sends every emitter a "scalarised ProgramIR". Emitting GLSL from scalarized IR turns `m*v` into 16 multiply-adds of text for no gain **[inf.]**. Instead use one *structured* IR (blocks, `if`, counted `for`, `break`/`continue`/`return`/`discard`, typed temporaries, intrinsics, loop bounds kept symbolic for dispatch-time costing) in two forms. **TIR** is vector-typed, produced after checking, mangling and folding, and feeds the GLSL emitter. **SIR** is the scalarized form and feeds the bytecode emitter and the interpreter. Structured, reducible control flow suits both GLSL text and JVM bytecode. TIR and SIR gain a structured `switch` node and integer-semantics intrinsics (`idiv`, `irem`, `udiv`, `urem`, `shl`, `shr`, `f2i`, `f2u`, `clampIndex`), which the GLSL emitter lowers to 27 §4's helpers and the bytecode emitter to Java operators, so both backends share one definition (27 §8.3).

**Bytecode emitter.** It confirms 05 §1 and 09 §3.3 and supersedes 06 §4's "use ASM 5.0.3".
- **ASM.** Shaded and relocated ASM 9, because `LaunchClassLoader` loads `org.objectweb.asm.` parent-first.
- **Class files.** `V1_8`, `COMPUTE_FRAMES` with a non-loading `getCommonSuperClass`, no `invokedynamic`.
- **Loading.** A child `ClassLoader` per program (OC-Wasm `Compiler.java:190-204`) that admits only `java.lang.*` and OpenGPU's kernel runtime. An opcode and owner whitelist scan runs before `defineClass`, and `CheckClassAdapter` runs in tests.
- **Code shape.** One class per (program × binding layout × local size), so call sites stay monomorphic (06 verif. C4). Buffers are hoisted into locals for range-check elimination, vectors are scalarized into locals, and scratch for local arrays and returns comes from a per-thread context. **No allocation.** Functions are inlined where size allows; any method at or above 7 500 bytes that cannot be split is a compile error (`HugeMethodLimit` 8000, 06 §4).
- **Line numbers.** A `LineNumberTable` of kernel lines makes an `ArrayIndexOutOfBoundsException` map to `kernel:LINE` for free.
- **Safepoints.** On JDK 8, C2 drops safepoint polls in counted loops; loop strip mining arrived in JDK 10 (JDK-8186027; JDK-8154302). Chunks are therefore sized from the static cost to about 0.25-1 ms **[est.]**, which bounds the time-to-safepoint stall.

**Interpreter: keep it.** GLSL ES 1.00 §5.10 already forces a typed evaluator for almost every built-in. A SIR interpreter adds about 1.5-2.5 kLOC **[est.]** and serves as:
1. a bit-exact fuzz oracle for the bytecode emitter;
2. the executor for tiny dispatches and for the ocelot harness, where JIT warm-up dominates (an interpreted inline dispatch needs a lower op cap than §4's 65 536, see there);
3. the fallback when bytecode generation hits an internal limit;
4. the serial re-run that produces deterministic fault messages.

At 100-500 ns per element (07 §1) it is never the production path for large dispatches.

**Vertex and fragment stages in SIR now.** This costs a few days **[est.]**: `discard` and texture sampling become IR intrinsics, and CI runs the scalarizer and interpreter on the graphics fuzz corpus. It pays off at once, because §1.4's position slice is SIR compiled to bytecode. A later software renderer would add sampler semantics, interpolation and a raster loop, but no second compiler. ES 1.00 core had no derivatives; the ES 3.00 subset has `dFdx`, `dFdy` and `fwidth` (27 §3), which a software renderer would add as quad-level intrinsics **[inf.]**. v1 has no mipmaps.

**Testing.**
- A type-directed grammar fuzzer for both languages, plus mutation of real sources: no crash, bounded time, every error has `line:col`.
- Compute oracles: interpreter = bytecode, 1 thread = N threads, JDK 8 = 17 = 21, all bit-exact.
- `glslangValidator`, which needs no GPU, validates inputs as `#version 300 es` (with a test prelude) and the emitted `330 core` text in the non-gating workflow (23 §4.1; 27 §11). Tolerance image tests run on real drivers and optionally on Mesa llvmpipe, a gap 09 lists as unresearched.
- 10⁵ fuzz programs per release, `CheckClassAdapter` clean (09 M3).

### 4. Compute scheduling across cards and racks

**Rack capacity.** A rack has 4 mountable slots (OC `common/tileentity/Rack.scala:342`). A slot accepts a card at or below its own tier (OC `common/inventory/ServerInventory.scala:21-24`). Server card slots (OC `common/InventorySlots.scala:53-121`; both files byte-identical to the 1.12.64-GTNH tag):

| Server tier | Card slots (slot tier) | Max OpenGPU T3 | Max OpenGPU cards |
|---|---|---|---|
| T1 | T2, T2 | 0 | 2 |
| T2 | T3, T2, T2 | 1 | 3 |
| T3 | T3, T3, T2, T2 | 2 | 4 |
| Creative | T3 ×4 | 4 | 4 |

A rack therefore hosts **at most 16 cards** (at most 8 T3 in survival; 16 T3 only with creative servers), realistically **12 plus 4 network cards**. A card's node is connected only to its server's machine node (OC `server/component/Server.scala:135-140`). That machine node is also the server's rack-mountable node, which the rack connects to a side bus when the server's primary connection is assigned (OC `server/component/Server.scala:40`; `common/tileentity/Rack.scala:56-90, 103-113`). The card stays private to its server only if its component uses `Visibility.Neighbors`, as stock OC cards do. OpenGPU should do the same, so one program drives at most 4 cards and servers cooperate by network messages. A `Visibility.Network` card would instead be callable by every machine on the rack's side network **[inf.]**. Component limits do not bind (`application.conf:149-154`).

**Per-JVM pool.** One pool per JVM, shared in single player by the integrated server and the client. LAN pixel encoding runs there too, with priority over compute.
- **Threads.** `min(4, max(1, cores/4))`. 09's `cores/2` no longer fits, because the pool no longer rasterizes **[inf.]**.
- **Fairness.** Deficit round-robin over cards in nanoseconds of pool time, with tier weights 1/2/4, per-tick credit of 1/2/4 ms and a burst of 4×.
- **Server-wide cap.** `compute.maxMsPerTick`, default 20 ms of pool time per 50 ms tick **[est.]**.
- **Per-dispatch caps by tier.** Invocations 2¹⁶ / 2¹⁸ / 2²⁰; static op totals 2²⁴ / 2²⁶ / 2²⁸, so a maximal T3 dispatch is about 0.2-0.5 s of CPU spread over ticks **[est.]**. At most 4 queued dispatches per card, else `nil,"busy"`. Buffers count against the card's memory.
- **Inline execution.** A dispatch of at most 65 536 static ops runs inside the direct call, charged to `callBudget`. That respects 09's rule that no OC worker blocks for more than 0.2 ms only on the bytecode path: at an assumed 5-10 ns per interpreted scalar op, 65 536 ops take 0.3-0.7 ms, so a dispatch that would run on the interpreter (bytecode not yet generated) needs a cap of about 16 384 ops or should be queued **[est.]**.
- **Compute command lists.** `computeList{ {p, gx, gy, gz, bind, uniforms}, {swap = {a, b}}, …, repeat = K }` runs K steps with an implicit barrier between dispatches and is capped as a whole. This is the key API for simulations.

| Path | Latency until Lua sees the result |
|---|---|
| inline | call overhead (4.2-4.5 µs stock Lua, 1.5 µs OC-LuaJIT; 11 §2) + compute |
| queued, waiting in `pullSignal(timeout)` | compute + wait for the next `Machine.update` (0-50 ms; a sleeping machine wakes only there, OC `server/machine/Machine.scala:584-585`) + 12 ms `executionDelay` (`:966-968`) |
| queued, polling `pullSignal(0)` | compute + up to about 15.8 ms (3.2 resumes per tick, 11 §2.2) |

**Compute feeding rendering in single player.**
1. Buffers carry usage flags (`COMPUTE | VERTEX | INDEX | TEXTURE_SRC`), and the per-card queue is ordered (09 §3.5), so a frame that references buffer B seals only after the dispatch writing B completes.
2. On completion the pool copies B into a pooled native-order direct `ByteBuffer`, about 6 µs per 64 KB **[est.]**, and publishes it through an `AtomicReference`.
3. The render thread uploads it with orphaned `glBufferSubData` (B §5.5) or `glTexSubImage2D`. RGBA8 always works; `R32F` needs GL 3.0 or `ARB_texture_float` **[inf.]**.

Latency is 1-2 frames. A LAN host is the same JVM. On a dedicated server, compute runs and graphics does not (owner decision 3).

### 5. HBM NTM reactors as the sanity check

HBM exposes reactors through `SimpleComponent`: 66 files implement it in upstream master `427c1ea` (build 5808). The owner runs the fork "NTM: Space" `1.0.27_X5778_H261_DBS1`, and `javap` shows its jar exposes the same `TileEntityRBMKConsole` and `TileEntityPWRController` callbacks, `direct=true`.

| Component | Reads | Writes |
|---|---|---|
| `rbmk_console` (`TileEntityRBMKConsole.java:567-808`) | `getColumnData(x, y)`: a map of 13 common entries (`type`, `hullTemp`, `level`, `enrichment`, `xenon`, `coreTemp`, …) plus 1-5 type-specific ones (`fluxQuantity` for rods, `water`/`steam` for boilers, …), and up to 73 for a fully loaded storage column (5 keys × 12 slots), on a 15×15 grid (`:53`, `:596`, `:601-689`; the same key set in the installed NTM: Space jar, by `javap -c`) | `setLevel`, `setColumnLevel`, `setColor(Level)`, `pressAZ5` |
| `rbmk_fuel_rod`, `rbmk_control_rod`, `rbmk_boiler` | heat, flux, depletion, xenon, levels, water/steam | `setLevel`, `setSteamType` |
| `ntm_pwr_control` (`TileEntityPWRController.java:596-640`) | `long` core/hull heat, `double` flux, rods, coolant, fuel | `setLevel` (limit = 4) |
| `zirnox_reactor`, `research_reactor`, `watz_reactor`, `ntm_icf_reactor`, `breeding_reactor`, `ntm_fusion_torus`, `dfc_emitter`, `ntm_pile_control` | temperatures, pressure, flux, fluids, power | on/off, levels, inputs |

**Model.** RBMK heat moves to the 4 orthogonal neighbours by stepped averaging (`TileEntityRBMKBase.java:159-215`). Fuel rods emit flux in 4 directions (`TileEntityRBMKRod.java:213-218`) along streams `fluxRange` nodes long (`RBMKNeutronHandler.java:214-235`), a game-rule dial that defaults to 5 and is clamped to 1..100 (`RBMKDials.java:31, 103, 273-275`).

**Kernel sizing [est.].** One invocation per column: a neighbour average (about 20 ops), a gather over 4 directions × R nodes with a running attenuation product (about 12 ops per node), and burn-up and xenon (about 30 ops). That is about 300 ops at R = 5 and about 5 000 at R = 100. Per step: 225 × 300 ≈ 6.8·10⁴ ops (≈ 0.07-0.1 ms) or 1.1·10⁶ (≈ 1-2 ms). At 20 steps per second that is under 0.5 % or 4 % of one core. Every cap holds: 65 536 ops per invocation, 2¹⁶ invocations at T1, and a K = 1 000 command list of 6.8·10⁷ ≤ 2²⁸ at T3. Required features: **float or double, int, arrays, 2D grids (`y*15+x`), neighbour reads, uniform-bounded loops with `break`, ping-pong**. Not required: shared memory, barriers, atomics. The extra demands are `double` for fidelity with HBM's own state and command lists for multi-step runs. That assumes HBM's stream interaction is expressible as a gather.

**I/O dominates.** Reading the live grid takes 225 `getColumnData` calls, each building a Java map that OC converts to a Lua table: about 5-14 ms per sweep on stock Lua **[est.]**. Scalar per-column getters cost about 2.9 ms (3 × 225 × 4.3 µs, 11 §2). The kernel is about 100× cheaper. Many cards pay off for design searches, not for mirroring one reactor.

## Design implications for OpenGPU

1. The ES 3.00-subset frontend (27) always runs, with ANGLE's WebGL checks as the baseline. Add clamped dynamic indexing (all of it, 27 §2.6), integer guards (27 §4), simulated trip counts, zero initialization and depth caps.
2. Ship one emitter, 330 core, with `og_*` mangling, fully preprocessed output and `#line` mapping.
3. Uniforms are one std140 block per program, emitted as per-stage `og_i_U[n]` and uploaded with one `glUniform4fv`, with rows holding int, uint or bool members in `og_i_I[m]` via `glUniform4iv` (27 §2.5); attributes bind by semantic slot (26 S5) and samplers in declaration order; Lua gets a reflection table.
4. Use the fixed portable caps of §1.4 (the GL 2.1 check is moot under 24 §7 answer 1's GL 3.3 floor), and add the CPU position-slice per-frame GPU-work guard plus timer-query throttling.
5. Compute follows the GLSL 4.30 surface with `double` and `int64_t`, std430 with reflection, uniform-bounded loops and the arithmetic of §2.3.
6. Replace 09's single-output rule with D1-D5: dispatch-time injectivity proof, a CAS fallback, zero-fill and a serial re-run on failure.
7. One structured IR in two forms (TIR → GLSL; SIR → bytecode and interpreter). Keep the interpreter, and lower vertex and fragment stages to SIR now.
8. Bytecode rules as in 09, plus a `LineNumberTable`, a whitelisting loader, an opcode scan, and chunks of about 1 ms for JDK 8 safepoints.
9. Scheduling: per-JVM pool, DRR by tier weight, `compute.maxMsPerTick`, tiered dispatch caps, inline tiny dispatches, **compute command lists**.
10. v2 adds `shared` + `barrier()` (region loops, one thread per workgroup) and integer buffer atomics whose result is unused. Images stay out.
11. Phasing: the frontend, TIR and the GLSL 330 emitter move into the first 3D milestone; the SIR interpreter and the compute language come with the dispatch API, then the bytecode emitter, with 09's M0 spike unchanged.

## Open questions for the owner

1. Should the `#version 120` dialect ship (best effort, checked only with glslang), or should OpenGPU require GL 3.3? *Default: ship it; it is about 150 lines.* *Answered: 24 §7 answer 1 set a GL 3.3 floor, so there is no `#version 120` dialect; the GL 2.1 column of §1.4 is kept for reference only.*
2. Fragment uniform cap: 64 rows, or 16 for WebGL-1 parity? *Default: 64.*
3. Should the CPU position-slice GPU-work guard be a deliverable of the first 3D milestone, or are static caps plus timer-query throttling enough while LAN is trusted? *Default: build the guard.*
4. Should compute v1 include `double` and `int64_t` (*default: yes*), and should OC-LuaJIT's planned `string.unpack` cover `<d`/`<i8`?
5. Should cards in one server be able to copy buffers peer-to-peer on the server, without going through Lua?
6. Should OpenGPU ship an HBM RBMK design-evaluator example, and should it target upstream HBM or the NTM: Space fork you run?
7. Should dispatches cost OC energy per op? The energy model is an unresearched gap in 09.

## Sources

Local (read-only; prefixes):
- **OC** = `C:\Users\astro\Downloads\OpenComputers-GTNH\src\main\scala\li\cil\oc` (1.12.55 checkout; `InventorySlots.scala` and `Rack.scala` byte-identical to scratchpad `ocgtnh-master`, tag 1.12.64-GTNH, commit 1e4559f, by `diff`): `common/InventorySlots.scala:53-121`; `common/tileentity/Rack.scala:56-113, 342-346`; `common/inventory/ServerInventory.scala:10-25`; `server/component/Server.scala:40, 135-149`; `server/machine/Machine.scala:335-368, 506, 584-585, 960-969`; `src/main/resources/application.conf:149-166`.
- **ANG** = scratchpad `Angelica-2.2.21\glsm\src\main\java\com\gtnewhorizons\angelica\glsm` (tag 2.2.21, a8c29fa): `GLStateManager.java:5822-5831, 7034, 7062-7063, 7712, 7815, 7958`; `GlslTransformUtils.java:49-56, 74-84`; `CompatShaderTransformer.java:53, 167-174`.
- **MC** = `C:\Users\astro\Downloads\OC-LuaJIT\build\rfg\minecraft-src\java`: `net/minecraftforge/client/ForgeHooksClient.java:318-330`; `net/minecraft/client/Minecraft.java:474`; `net/minecraft/client/renderer/OpenGlHelper.java:165-198`.
- **HBM** = scratchpad `hbmntm\src\main\java\com\hbm` (github.com/HbmMods/Hbm-s-Nuclear-Tech-GIT master 427c1ea, 2026-10-08, build 5808, MC 1.7.10): `tileentity/machine/rbmk/TileEntityRBMKConsole.java:53, 567-808`; `…/rbmk/TileEntityRBMKBase.java:55, 108-215`; `…/rbmk/TileEntityRBMKRod.java:213-246, 433-501`; `…/rbmk/TileEntityRBMKControl.java:205-240`; `…/rbmk/RBMKDials.java:31, 103, 273-275`; `handler/neutron/RBMKNeutronHandler.java:110-113, 214-235`; `tileentity/machine/TileEntityPWRController.java:59-74, 596-640`; `TileEntityReactorZirnox.java:491-512`; `TileEntityReactorResearch.java:407-472`; `TileEntityWatz.java:580-615`. Installed: `C:\Games\Minecraft\instances\Main\minecraft\mods\HBM-NTM-[1.0.27_X5778_H261_DBS1].jar` (`mcmod.info` "NTM: Space"; `javap` of the console and PWR classes).
- Earlier reports: 02 §1; 05 §1; 06 §4-6; 07 §1; 09 §3.2-3.8, §4; 11 §2; 12 §5; 14 §3.
- Downloaded copies (scratchpad `web22\`, data only): ANGLE sources and GL/GLSL spec PDFs.

Web:
- ANGLE main (fetched 2026-10-08; `ShaderLang.h` last changed 2026-09-08): https://github.com/google/angle/blob/main/include/GLSLANG/ShaderLang.h (190-332, 446-468, 600-619, 895-902); `src/compiler/translator/ParseContext.cpp` (1576-1587, 2084, 4219-4445, 4587, 6488, 7747-7753, 10919-11007); `src/compiler/translator/Compiler.cpp` (441-446); `src/compiler/translator/ShaderLang.cpp` (188-193, 273-277); `src/compiler/translator/HashNames.cpp` (57-70); `src/compiler/preprocessor/MacroExpander.cpp` (25, 433-440).
- WebGL 1.0: https://registry.khronos.org/webgl/specs/latest/1.0/ (#SUPPORTED_GLSL_CONSTRUCTS, #OUT_OF_RANGE_ARRAY_ACCESSES, #MAX_GLSL_TOKEN_SIZE, #MAX_STRUCT_NESTING, #PACKING_RESTRICTIONS, "Initial values for GLSL local and global variables").
- GLSL ES 1.00 rev 17: https://registry.khronos.org/OpenGL/specs/es/2.0/GLSL_ES_Specification_1.00.pdf (§4.5.3, §5.10, §6.1, §7.4, App. A §4-7). GLSL 1.20 (§3.6, §4.1.3): https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.1.20.pdf. GLSL 3.30 (§3.3, §4.3.8.1-2): https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.3.30.pdf. GLSL 4.30 (§4.1.3-4.1.10, §4.3.8, §7.1, §8.16): https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.4.30.pdf.
- OpenGL 2.1 `glGet`: https://registry.khronos.org/OpenGL-Refpages/gl2.1/xhtml/glGet.xml; OpenGL 3.3 core (§5.1, Tables 6.42-6.45): https://registry.khronos.org/OpenGL/specs/gl/glspec33.core.pdf; OpenGL 4.3 core (§7.6.2.2): https://registry.khronos.org/OpenGL/specs/gl/glspec43.core.pdf.
- https://registry.khronos.org/OpenGL/extensions/ARB/ARB_gpu_shader_int64.txt; https://registry.khronos.org/OpenGL/extensions/KHR/KHR_parallel_shader_compile.txt.
- WGSL §8.8 and the shift rules: https://www.w3.org/TR/WGSL/.
- Microsoft TDR: https://learn.microsoft.com/en-us/windows-hardware/drivers/display/timeout-detection-and-recovery.
- pocl: https://portablecl.org/docs/html/kernel_compiler.html; GraphicsFuzz: https://github.com/google/graphicsfuzz.
- JDK-8186027: https://bugs.openjdk.org/browse/JDK-8186027; JLS 8 §5.1.3, §15.17-15.19: https://docs.oracle.com/javase/specs/jls/se8/html/; Java 8 `Integer`: https://docs.oracle.com/javase/8/docs/api/java/lang/Integer.html.

## Verification notes

Adversarial check of 2026-10-08 against the local checkouts, the scratchpad clones (Angelica 2.2.21 `a8c29fa`, `ocgtnh-master` `1e4559f`, HBM `427c1ea`, the installed NTM: Space jar) and the downloaded spec texts. Claims not listed here were confirmed as written: the Forge `createDisplay` without `ContextAttribs`, GLSL 3.30 §3.3, the GLSL 1.20 reservations, ES 1.00 App. A §5 and ANGLE `ParseContext.cpp:7746-7753`, the GL 2.1, 3.3 and ES 1.00 minimums, the TDR quotes, the `Machine.scala` wake path (`onSignal` is a no-op in `NativeLuaArchitecture.scala:302`, which OC-LuaJIT's `LuaJITArchitecture` inherits), WGSL §8.8, GLSL 4.30 §4.1.3 and §8.16, JDK-8186027, ES 1.00 §5.10, and the HBM heat, flux and dial values.

1. **Angelica context (Summary 2, §1.3).** "Always GL 3.3+ core" holds only for `glProfile=AUTO/CORE`. `glProfile=ES` creates a GL ES 3.2 context (`MixinForgeHooksClient_CoreProfile.java:52-61, 141-151`; `AngelicaConfig.java:452-457, 479-484`). AUTO probes 4.6 down to 3.3, with 4.1 at most on macOS (`:77-129`).
2. **GLSM rewriting (§1.1).** `renameReservedWords` is keyed on `RENDER_BACKEND.getMinGLSLVersion()`, not on the shader's `#version` (`GLStateManager.java:5825`), and the LWJGL2 backend returns 330 (`Lwjgl2GLRenderBackend.java:115`), so `sampler` is never renamed there. `CompatShaderTransformer` runs only when the FFP `ShaderManager` is enabled (`GLStateManager.java:5826`). A non-core source is only version-fixed (`fixupVersion`, `:652-668`) unless `NEEDS_TRANSFORM_PATTERN` (compat built-ins, `attribute`, `varying`; `:67-69`) matches. The conclusion that GLSM leaves `#version 330 core` output untouched is unchanged, except under the ES profile (`:121-160`).
3. **Loop termination (§1.1).** Appendix A §4 allows `loop_index += constant_expression` with any constant, including 0, and float indices, so conforming loops can be infinite. The trip-count simulation of §1.2 already rejects them. The row now says so.
4. **Precision citation (§1.2, Sources).** The fragment "no default precision" rule is in ES 1.00 §4.5.3, not §4.5.4 (ES 1.00 rev 17 TOC and text, "The fragment language has no default precision qualifier for floating point types").
5. **Mangling collisions (§1.3, implication 3), a new finding.** With one `og_` prefix for user names, a user `a_p` maps to `og_a_p`, the name of attribute `p`. A user `U` or `FragColor` likewise maps onto OpenGPU's `og_U` or `og_FragColor`. The fix gives user names, attributes, varyings, samplers and internal names disjoint prefixes (`og_u_`, `og_a_`, `og_v_`, `og_s_`, `og_i_`) and rejects a leading `_`, which would otherwise emit a `__` name.
6. **Rack capacity and card visibility (Summary 7, §4).** Creative servers have 4 T3 card slots (`InventorySlots.scala:102-120`), so "at most 8 T3 per rack" holds only in survival. "Cards are internal" is not structural: `Server.node` is `machine.node` (`Server.scala:40`), and the rack connects that node to a side bus (`Rack.scala:56-90, 103-113`). Privacy therefore comes from the card's `Visibility.Neighbors`.
7. **HBM `getColumnData` size (§5).** The map has 13 common keys plus 1-5 type-specific ones, and up to 73 for a fully loaded storage column (`TileEntityRBMKConsole.java:601-689`). It is not "13-25". The installed NTM: Space jar has the same key set (`javap -c`, `ldc` strings including `slot`).
8. **Injectivity proof (§2.4), a new finding.** I checked that the mixed-radix condition is sufficient: take the highest differing term and the remainder cannot cancel it. Without guard-refined extents, however, it rejects the report's own 15-wide kernel at `local_size_x = 16`. Text added.
9. **Inline dispatch budget (§3, §4), a new finding.** The 65 536-op inline cap meets 09's 0.2 ms rule only with bytecode. On the interpreter it takes about 0.3-0.7 ms **[est.]**. Also, "never the production path" contradicted item 2 of the same list. Both are now qualified.
10. **WGSL division (§2.3).** WGSL makes a constant zero divisor a shader-creation error (`wgsl.txt` §8.8, "It is a shader-creation error if e2 is a const-expression"). The table now adopts that rule.
11. **Summary 1.** "LWJGL2 cannot survive" a TDR is an inference, so it is now marked **[inf.]** as it already was in §1.1.

## Amendments after the engine-lessons study (2026-10-09)

Changes from 26 (`26-lessons-from-general-engines.md`), applied in place:

- **§1.3 Samplers, attributes, inputs.** Attributes bind by semantic instead of declaration order: fixed locations 0-7 for POSITION, NORMAL, TEXCOORD, COLOR and CUSTOM0-3, user attributes in free CUSTOM slots, POSITION mandatory, and missing streams fed from a 1-element VBO or a constant, never with `glVertexAttrib4f` (26 S5, V1). Design implication 3 changed to match.
- **§1.5 GL side.** Pending-link semantics: a device waits for a pending link while other devices proceed; status is first read on the next START pass; a slow link relies on 24 §3.3's 1 s `"dropped"`; a permanent failure skips only the draws that use the program; no program-binary cache in v1 (26 G4).

## Amendments after the language decision (2026-10-09)

The owner changed decision 5 (24 §1) to an ES 3.00-based graphics language, specified in 27 (`27-graphics-language-es300.md`). Applied in place:

- **Header and §1.** §1 is marked superseded by 27 where they conflict, with a status note on what still holds (frontend pipeline, mangling, std140 reflection, caps method, GPU-work guard, §1.5).
- **Summary 1 and §1.1 "Drivers diverge".** ANGLE's Intel rewrites for integer unary minus and float `isnan` are D3D11-backend workarounds for 2014-2016 drivers; its GL backend's Intel rewrites (`emulateAbsIntFunction`, `addAndTrueToLoopCondition`, `preAddTexelFetchOffsets`) are macOS-only; `scalarizeVecAndMatConstructorArgs` is NVIDIA's (and Mali's), not Apple's; NVIDIA also gets `clampFragDepth` (27 §5).
- **Summary 2.** One output dialect, `#version 330 core`; 24 §7 answer 1 dropped `#version 120`.
- **Summary 3, §1.3 uniform model, implication 3.** Rows with int, uint or bool members go to `ivec4 og_i_I[m]` via `glUniform4iv` (27 §2.5).
- **§1.2.** ES 3.00 preprocessor; the `f`-suffix and reserved-operator sentences removed; any `__` rejected; built-ins can be neither overloaded nor redefined (ES 3.00 §4.2.3, §6.1); `for` over uint indices, float indices only with relational operators, `switch` allowed (27 §2.3); the VS-uniform-only dynamic indexing marked superseded by 27 §2.6; precision cites ES 3.00 §4.5.4; default returns and `gl_FragDepth` initialization and clamp; `invariant` emitted on both sides instead of dropped (27 §2.4).
- **§1.3.** Dialect B marked dropped; the translation table now maps 27's ES 3.00 forms to `330 core` only; vertex inputs take semantic slots by `layout(location)` as well; mangling adds `og_o_` and `og_f_` and the `__` rule; "both dialects" removed from the Y-orientation bullet.
- **§1.4.** The caps table gains an ES 3.00 column and rows for texel offsets, `switch` labels and array size; uniform caps count physical rows of `og_i_U`, `og_i_I` and used built-ins; varyings are counted by ES 3.00 §11 and emitted as at most 8 vec4-sized outputs (27 §8.2).
- **§2.1.** `switch` is allowed in compute through the shared frontend.
- **§2.3.** Rows for float→uint saturation with NaN → 0, `clamp` with lo > hi, half-even `round`, default returns and constant float overflow as a compile error.
- **§3.** TIR and SIR gain a structured `switch` node and shared integer-semantics intrinsics (27 §8.3); derivatives exist in the ES 3.00 subset; `glslangValidator` validates `#version 300 es` inputs and `330 core` outputs.
- **Design implications 1-3.** The ES 3.00-subset frontend (27) with clamped indexing and integer guards; one emitter, 330 core; integer uniform rows in `og_i_I`.
