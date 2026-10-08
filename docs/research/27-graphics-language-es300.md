# 27 — Graphics shader language: an ES 3.00-based subset

Specifier, 2026-10-09. The owner changed decision 5 (24 §1) on 2026-10-09: graphics shaders are written in a GLSL ES 3.00-based subset instead of 22 §1's ES 1.00 subset. The GPU-hang safety model stays (Appendix-A-style bounded `for` loops, simulated trip counts, static costing, caps), integer results must equal the compute language's (22 §2.3), and the only output dialect stays `#version 330 core`. This report is the specification delta. Where it conflicts with 22 §1 it governs; the rest of 22 §1 (frontend pipeline, mangling scheme, std140 reflection, caps method, GPU-work guard, caching, pending links) stays. Showcase shaders are examples, not mod API (24 decision 15), so nothing here depends on a shader library. Inputs: 20 §2 and §6, 22 §1–§3, 24 §1 and §3.3–§8, 25 §4, and 26 S2, S5, S6, S7, S10, S12, S13, R1, R2, R10, G4 and G5. Tags: **[I]** inference, **[est.]** estimate, **[proposal]** a default set here. Citation prefixes (ES300, GLSL330, GL33, ANGLE, ANG, GLSLANG, RL, …) are defined under Sources; ANGLE paths are relative to the repository root, ANG paths to `glsm/src/main/java/com/gtnewhorizons/angelica/glsm/`.

## Summary

1. Input starts with `#version 300 es` (required). Precision qualifiers are accepted and ignored, and all arithmetic is 32-bit highp. ES 1.00 sources are rejected with a conversion hint, and a converter ships as a dev tool. The preprocessor follows ES 3.00 with `#line` for composed sources and no `#include`; the one extension is `GL_NV_shader_noperspective_interpolation`.
2. The subset accepts bool, int, uint and float scalars and vectors, square and non-square matrices, 1-D arrays as values, structs, and every ES 3.00 operator. Control flow is Appendix-A `for` loops (int, uint or float index) with simulated trip counts, plus `switch` under ES rules. Varyings are smooth, flat and noperspective. Textures are `sampler2D` with `texelFetch`/`textureSize`; `gl_FragDepth`, `gl_VertexID` and `gl_InstanceID` are available. Dynamic indexing is clamped everywhere.
3. Excluded: `while`/`do`, uniform blocks, vertex samplers, integer, 3D, array and shadow samplers, points, more than one fragment output, integer and matrix vertex inputs, and `packHalf2x16`.
4. GLSL 3.30 has every ES 3.00 built-in except the six packing functions. The four norm functions and `unpackHalf2x16` are polyfilled: the pack forms and `unpackHalf2x16` match compute bit for bit on hardware with IEEE-rounded multiplies [I], and the two norm unpacks are within the GPU's division precision (verification note V1). `round` is emitted as `roundEven`.
5. Emitter guards give the compute rules on the GPU: x/0 = x, x%0 = 0, INT_MIN/−1 = INT_MIN, truncating `/`, a dividend-signed `%`, shift counts mod 32, and saturating float→int/uint. They cost 1 op for unsigned division, about 10–12 for signed division and 4–6 per conversion, and are elided where ranges are proven. The documented exceptions are NaN conversions and out-of-range indexing (GPU clamps, compute faults).
6. Correction to 22 §1.1 and 26 S13: ANGLE's integer unary-minus and `isnan` rewrites are D3D11 workarounds for 2014–2016 Intel drivers, and its abs, loop and `texelFetchOffset` rewrites are enabled for Intel on macOS only. ANGLE's GL backend has no Intel-specific shader rewrite on Windows (its one Intel-on-Windows condition hides `GL_KHR_blend_equation_advanced`; the vendor-independent Windows rewrite `removeDynamicIndexingOfSwizzledVector` applies to Intel too). Cheap rewrites are applied unconditionally, and a conformance corpus runs on the owner's three GPUs.
7. `flat` means "first or last vertex" (WGSL's `either`), because OpenGPU never sets the provoking vertex and GLSM changes it. Results are deterministic only when all vertices of a primitive agree, and a warning flags flat outputs that cannot be proven so.
8. New finding: 22 §1.3's single `vec4` uniform array cannot carry int, uint or bool members bit-exactly. Integer rows go to an `ivec4 og_i_I[]` array uploaded with `glUniform4iv` (25 C68).
9. Graphics and compute become two profiles of one frontend: preprocessor, lexer, parser, typer, `BuiltinInfo`, constant evaluator, integer intrinsics, loop simulation and costing are shared, and compute gains `switch`.
10. Effort [est.]: M2 +3.7–5.6 w, M3 −1.1–1.8 w, net +2.6–3.8 w; 24's ≈ 28–40 w becomes ≈ 30.5–44 w.

## 1. Input form

### 1.1 The version line

**`#version 300 es` is required as the first line** [proposal]. ES300 §3.4 requires the directive "in any shader that uses version 3.00 of the language", only on the first line, processed "before all other preprocessing". A missing line is an error here ("OpenGPU graphics shaders start with `#version 300 es`"), not ES's fallback to version 1.00. Any other number (`100`, `310 es`, `330`) is also an error. Reasons:
- the source names its own dialect, so a later profile (an ES 3.10-based one, or a legacy 1.00 one, §1.3) can be added without guessing;
- inputs validate unchanged with `glslangValidator` in ES 3.00 mode (§11, 23);
- ES300 §1.5 already forbids linking a 1.00 stage with a 3.00 stage, so both stages of a program declare `300 es`.

**Composed sources.** `opengpu.lua` composes sources from files (24 §3.3). It writes `#version 300 es` once as line 1, strips the directive from the fragments it includes, and emits `#line N S` before each fragment, with S the file's index. In ES 3.00 and in GLSL 3.30, `#line N` gives the *next* line the number N (glslang applies next-line semantics "for ES and version ≥ 330", GLSLANG `glslang/MachineIndependent/ParseHelper.cpp:6424-6427`), so the input and the emitted text use one rule. Errors read `fs:S:N:col`, and `opengpu.lua` maps S back to a file name.

**Predefined macros:** `__VERSION__` = 300, `GL_ES` = 1 (ES300 §3.5), `GL_NV_shader_noperspective_interpolation` = 1 (§1.5), and `__OPENGPU__` = 1 [proposal], so ported code can test for OpenGPU. ES reserves `__` names for "underlying software layers" (ES300 §3.5), which is OpenGPU's role here.

### 1.2 Precision qualifiers: accepted and ignored

Every float is evaluated as fp32 and every int or uint as 32-bit two's complement. The emitted `330 core` text has no precision statements, and in desktop GLSL precision qualifiers "have no semantic meaning" (GLSL330 §4.5). A fragment shader without a default float precision is accepted. This departs from ES300 §4.5.4 ("The fragment language has no default precision qualifier for floating point types"), and `programInfo` notes it. Requiring the line would catch no error on OpenGPU, where every precision is highp, and Shadertoy-style sources omit it. Consequence: `mediump int`, the ES fragment default (ES300 §4.5.4), is 32-bit here. ES allows mediump integers of 16–32 bits (§4.5.1), so a conforming program cannot depend on a narrower wrap. Precision on samplers and in `precision` statements is accepted the same way.

### 1.3 ES 1.00 sources

**No legacy profile in v1** [proposal]. A source declaring `#version 100`, or lacking `#version` while using `attribute`, `varying`, `gl_FragColor`, `gl_FragData` or `texture2D`, is rejected with one error that names the conversion recipe. A second profile would need a second keyword table, ES 1.00's Appendix A §5 index classes and its overloadable built-ins (22 §1.2), all for sources that a mechanical pass converts. Showcase shaders are examples (decision 15) and will be written in 3.00. If users ask for it later, an ES 1.00 profile is a frontend mode lowering to the same TIR, ≈ 3–5 d [est.].

**Mechanical conversion.** The converter is a `:lang` dev tool built on OpenGPU's own lexer, so renames respect scopes. CI runs it on the raylib corpus (§11), and the steps are documented for users:

| # | ES 1.00 | ES 3.00 | Basis |
|---|---|---|---|
| 1 | `#version 100` or no line | `#version 300 es` on line 1 | ES300 §3.4 |
| 2 | VS `attribute`; VS `varying`; FS `varying` | `in`; `out`; `in` | ES300 §3.8 reserves `attribute` and `varying` |
| 3 | `gl_FragColor`, `gl_FragData[0]` | a declared `out vec4 fragColor;` | ES300 §7.2 lists no colour output |
| 4 | `texture2D`, `textureCube`; `texture2DProj`; `texture2DLod`, `textureCubeLod`, `texture2DLodEXT`; `texture2DProjLod`; `texture2DGradEXT` | `texture`; `textureProj`; `textureLod`; `textureProjLod`; `textureGrad` | ES300 §8.8 |
| 5 | `#extension GL_OES_standard_derivatives`, `GL_EXT_shader_texture_lod`, `GL_EXT_frag_depth`; `gl_FragDepthEXT` | removed (core); `gl_FragDepth` | ES300 §7.2, §8.9 |
| 6 | user identifiers that ES 3.00 makes keywords or reserved words (`layout centroid smooth case uint uvec2-4 mat2x3`…, `sample patch noperspective resource readonly writeonly coherent restrict active common partition filter subroutine`, new sampler and image names) | renamed | ES100 §3.7 vs ES300 §3.8 |
| 7 | user functions named like 3.00 built-ins (`round trunc roundEven modf isnan isinf sinh … atanh determinant inverse transpose outerProduct texture textureSize texelFetch`…), or overloads of built-ins | renamed | ES300 §4.2.3 and §6.1 forbid redefining or overloading built-ins; ES 1.00 allowed overloads |

Loops in Appendix-A form, precision statements, uniforms and expressions need no change. ES 1.00 has no integer `%` or bitwise operators, so no integer semantics change either.

### 1.4 Preprocessor

OpenGPU runs its own preprocessor (22 §1.2) under ES300 §3.5:
- **Macros:** object-like and function-like `#define`, plus `#undef`. ES 3.00 has no `##` token pasting (ES300 §1.1.6 lists "CPP token pasting" as removed), and defining a `GL_`-prefixed macro or redefining a predefined one is an error.
- **Conditionals:** `#if`, `#ifdef`, `#ifndef`, `#elif`, `#else`, `#endif` with `defined`. An undefined identifier in `#if` is an error, not 0.
- **Other directives:** `#error` is a compile error and `#extension` follows §1.5. `#pragma` is ignored, except that `#pragma STDGL invariant(all)` (vertex stage) is flattened into explicit `invariant` declarations, as ANGLE does with `flattenPragmaSTDGLInvariantAll` (ANGLE `include/GLSLANG/ShaderLang.h:246-249`).
- **Line handling:** `#line` as in §1.1, and line continuation with `\` (ES300 §3.1).
- **Limits:** 22's caps stay: expansion depth 32, 10 000 tokens, identifiers ≤ 64 characters (ES allows 1024).
- **Not supported:** `#include` (decision 15).

The driver sees only fully preprocessed text plus OpenGPU's own `#line` directives.

### 1.5 Extensions

- **`GL_NV_shader_noperspective_interpolation`** carries 26 R1's `noperspective`. It is Khronos OpenGL ES extension #201 (revision 2, 2014-10-24): it requires OpenGL ES 3.0 and GLSL ES 3.00, and moves `noperspective` from the reserved words to the keywords. R1 planned an OpenGPU-specific directive. The existing name is better: ES300 §3.8 reserves `noperspective` (using it is an error), and ANGLE (`src/compiler/translator/ExtensionBehavior.cpp:71`, `glslang.l:168`, `glslang.y:808-811`) and glslang (GLSLANG `Scan.cpp:1109-1110`, `Versions.cpp:310, 515`) already implement it. Ported code and CI validation therefore need nothing OpenGPU-specific. Behaviours `enable`, `require` and `warn` all turn it on.
- **ES 1.00-era extensions** (`GL_OES_standard_derivatives`, `GL_EXT_shader_texture_lod`, `GL_EXT_frag_depth`) produce a warning ("core in ES 3.00") and have no effect.
- **Any other name** follows ES300 §3.5: `require` is an error, `enable` and `warn` give a warning with no effect, and `#extension all` with `require` or `enable` is an error.
- **No other extension in v1** [proposal]. Later candidates are an integer `mix` (a trivial polyfill) and the ES 3.10 bit functions, which compute's GLSL 4.30 surface already has.

## 2. The accepted subset

### 2.1 Types

| Type | ES 3.00 | OpenGPU v1 | Notes |
|---|---|---|---|
| `bool`, `bvec2-4` | yes | yes | not allowed in interfaces (ES rule) |
| `int`, `ivec2-4` | yes | yes | 32-bit. Literals follow ES300 §4.1.3: decimal, octal or hex that fit 32 bits (`3000000000` is −1294967296); the emitter prints canonical literals (`(-2147483647 - 1)` for INT_MIN) |
| `uint`, `uvec2-4` | yes | yes | `u`/`U` suffix |
| `float`, `vec2-4` | yes | yes | `f`/`F` suffix accepted (ES300 §4.1.4; 22 §1.2's "no suffix" was ES 1.00). Emitted with 9 significant digits, which round-trips binary32 [I]. A constant expression that folds to ±Inf or NaN is a compile error [proposal], after WGSL's const-expression rule |
| `mat2-4`, `matCxR` | yes | yes | non-square included |
| structs | yes | yes | ES rules (named, at least one member, no embedded definitions); nesting ≤ 4 (22); no samplers inside structs [proposal] |
| 1-D arrays | yes | yes | §2.6 |
| `sampler2D` | yes | yes | §2.7 |
| `samplerCube` | yes | open question 2 | |
| `sampler3D`, `sampler2DArray`, shadow samplers, `isampler*`, `usampler*` | yes | no | §2.7 |

There are no implicit conversions (ES300 §1.1.6 lists "Implicit type conversion" as removed), and overload resolution is exact-match, as in 22.

### 2.2 Operators and expressions

All ES300 §5.9 operators are accepted:
- **Arithmetic and bitwise:** `+ - * /` on int, uint and float scalars, vectors and matrices; `%` and `~ & | ^ << >>` on integers, with their compound assignments. The operands of a shift may mix int and uint, and the result takes the left operand's type.
- **Logical:** `&&`, `||`, `^^` and `!`, with the specified short-circuit evaluation.
- **Comparison:** relational operators on scalars only; equality on all types, arrays and structs included.
- **Selection and sequence:** the ternary and sequence operators, but not on arrays or `void`. ES300 §5.7 makes array ternaries optional, and WebGL 2.0 rejects them ("Disallowed variants of GLSL ES 3.00 operators").

Integer semantics are §4's. Float results are tolerance-tier (24 decision 3).

### 2.3 Control flow

- **`for`, Appendix-A form, extended to `uint`.** The header is `for (T i = c0; i op c1; step)` with T ∈ {int, uint, float}, op ∈ {`<`, `<=`, `>`, `>=`, `==`, `!=`} (only `<`, `<=`, `>`, `>=` when T is float, see below), step ∈ {`i++`, `++i`, `i--`, `--i`, `i += c`, `i -= c`}, and c, c0, c1 constant expressions. The body never assigns `i` or passes it as `out`/`inout` (ES100 App. A §4, as 22 §1.2). ES 3.00 itself has no such rule: "Non-terminating loops are allowed. The consequences of very long or non-terminating loops are platform dependent" (ES300 §6.3). The restriction is therefore OpenGPU's, as it was WebGL 1's, and WebGL 2 dropped it. Trip counts are simulated with the compute semantics, so `for (uint i = 9u; i >= 0u; i--)` wraps, never ends and is rejected. 24 §3.4's caps stay: ≤ 256 iterations per loop, nested product ≤ 4 096; nesting ≤ 3 comes from 22 §1.4's table. Unlike in ES 1.00, the index may appear in any expression, dynamic indexing included.
- **Float indices with `==` or `!=` are rejected** [proposal; verification note V3]. The simulation computes the index in IEEE fp32, but the GPU need not: GLSL330 §4.1.4 does not require "the precision of internal processing" to match IEEE 754, ES300 §4.5.1 leaves the rounding mode undefined within 1 ULP, and compilers may rewrite the index as c0 + k·c. A float loop that the simulation sees reach `i == c1` exactly can therefore miss it on the GPU and never end, which is the GPU-hang case the model exists to exclude. With `<`, `<=`, `>`, `>=` and a monotone step, a 1-ULP drift moves the exit by at most one iteration, which 22's +1 margin already covers. ES 1.00 Appendix A allowed `==`/`!=` on float indices, so this also tightens 22 §1.2.
- **`while`, `do-while`: rejected**, because they have no static bound. WebGL 1 rejected them too, and compute rejects them (22 §2.1). The error message suggests the bounded `for`.
- **`switch`: accepted with ES300 §6.2's rules.**
  - The selector is a scalar int or uint, and every label is a constant integral expression of the same type, with no conversion.
  - Labels must not repeat, and there is at most one `default`, which may appear anywhere.
  - No statement may come before the first label, no label may sit inside other control flow, and a label must be followed by a statement before the end of the switch.
  - Fallthrough is allowed. `break` leaves the switch, and `continue` applies to the enclosing loop.

  OpenGPU caps a switch at 64 labels [proposal], and a switch counts toward statement depth 16. The emitter passes it through unchanged, since GLSL330 §6.2 has the same semantics. One ANGLE pass concerns `switch` on every output, GLSL included: `PruneEmptyCases` (`src/compiler/translator/Compiler.cpp:1037-1046`, unconditional) removes trailing cases followed only by no-ops, because "In case the last case inside a switch statement is a certain type of no-op, GLSL compilers in drivers may not accept it". ES300 §6.2 accepts such input (`case 3: ;` satisfies its "statement between a label and the end" rule), so the emitter does the same: trailing labels whose statements are all no-ops are dropped, and a switch left with no label is emitted as its selector expression alone [proposal; verification note V4]. The other two passes do not apply: `wrapSwitchInIfTrue` is SPIR-V-only (ANGLE `src/compiler/translator/spirv/OutputSPIRV.cpp:5607, 5719`) and `RemoveSwitchFallThrough` is HLSL-only (`src/compiler/translator/tree_ops/hlsl/`).
- **Jumps.** `break`, `continue`, `return` and `discard` (fragment stage only) follow ES. Recursion is rejected on the call graph (ES300 §6.1; 22). A non-void function whose end is reachable returns the zero value of its type, where ES300 §12.34 leaves the value undefined. ANGLE does the same (`src/compiler/translator/tree_ops/AddDefaultReturnStatements.cpp:52-64`, applied in `glsl/TranslatorGLSL.cpp:99-104`), and the same rule is proposed for compute (§11).

### 2.4 Interface qualifiers and declarations

| Construct | ES 3.00 | OpenGPU v1 |
|---|---|---|
| vertex `in` | float, int and uint scalars, vectors and matrices; `layout(location)` (ES300 §4.3.4, §4.3.8.1) | float scalars and vectors only. Without a layout a user input takes the next free CUSTOM slot (26 S5); `layout(location = n)` with n ∈ 0..7 names semantic slot n (§7). Integer and matrix inputs are excluded until integer stream formats exist (open question 1) |
| vertex `out` / fragment `in` | no bool or opaque types; arrays; structs without arrays or nested structs (ES300 §4.3.4, §4.3.6) | as ES. Struct varyings are flattened by the emitter into one varying per member; ≤ 8 rows counted as in ES300 §11 (§8.2) |
| interpolation | `smooth` (default), `flat`, `centroid` (ES300 §4.3.9) | `smooth`, `flat`, `noperspective` (§1.5, §6). `centroid` is accepted and dropped: OpenGPU targets are single-sampled, and §4.3.9 ignores centroid when single-sampling. Qualifiers must match across stages (ES300 §4.3.9), checked by `createProgram` with both positions |
| integer varyings | must be `flat` (ES300 §4.3.4, §4.3.6) | same. GLSL330 §4.3.4 requires `flat` on the fragment input; the emitter writes it on both sides |
| fragment `out` | several, `layout(location)` (ES300 §4.3.8.2) | exactly one `vec4`, any name, at location 0; `layout(location = 0)` optional. Integer outputs are reserved for 24 §7 question 11 (index-output 3D) |
| `invariant` | outputs only (ES300 §1.1.1) | accepted on vertex outputs and `gl_Position`. The emitter also marks the matching fragment input `invariant`, because GLSL330 §4.6.1 says "the invariant keyword has to be used in both shaders, or a link error will result". ANGLE instead strips the qualifier (`ShaderLang.h:186-200`), for GL ≤ 4.1 or desktop AMD (`src/libANGLE/renderer/gl/renderergl_utils.cpp:2350-2352`) |
| `uniform` | initialized to 0 at link (ES300 §4.3.5) | as ES, plus 26 S7's initializers, a departure from ES300 §4.3 ("Initializers may only be used in declarations of globals with no storage qualifier or with a const qualifier") |
| `const`, unqualified globals | constant initializers; uninitialized globals undefined | as ES; uninitialized globals and locals are zero-initialized (22 §1.2) |
| `layout` on uniforms or blocks | yes | no (§2.5) |

### 2.5 Uniforms and uniform blocks

**Uniform blocks** (ES300 §4.3.7) are excluded in v1 [proposal]:
- OpenGPU already packs each program's uniforms into one std140 block that Lua addresses by offset (22 §1.3).
- A GL uniform-buffer path would need indexed buffer bindings, which GLSM does not cache (20 §2: "No UBOs in the early milestones").
- None of the target corpora (raylib, Godot, Shadertoy, LÖVE) depends on blocks.

A block declaration or `layout(std140) uniform;` yields an error that names the alternative.

**Integer uniforms: a correction to 22 §1.3.** 22 uploads every row as floats (`uniform vec4 og_i_U[n]`, one `glUniform4fv`). An int, uint or bool member stored there is a float bit pattern:
- integers 1..0x7FFFFF are subnormal floats, which "can be flushed to 0" on input (ES300 §4.5.1);
- −1 (0xFFFFFFFF) is a NaN pattern, and GL33 §2.1.1 says that "providing a NaN or an infinity" to a GL command "yields unspecified results"; NaN payloads may also be lost on the JVM's float paths [I].

The opposite scheme, storing everything as ints and reading floats through `intBitsToFloat`, gains nothing for floats: GLSL330 §8.3 leaves `intBitsToFloat` of Inf or NaN unspecified, and float rows uploaded with `glUniform4fv` are equally unspecified for Inf and NaN (GL33 §2.1.1). Both schemes therefore guarantee only finite floats, and float rows avoid a bit cast on every read. Lua writes of Inf or NaN into a float member are documented as unspecified on the GPU.

Rule [proposal]:
- Rows holding only float members stay in `uniform vec4 og_i_U[n]`, uploaded with `glUniform4fv` (25 C64).
- Rows holding any int, uint or bool member go to `uniform ivec4 og_i_I[m]`, uploaded with `GL20.glUniform4(int, IntBuffer)`, i.e. `glUniform4iv`. This call is 25 C68, GLSM-mapped since 2.1.0 (ANG `GLStateManager.java:7962-7964`; redirect `redirect/GLSMRedirector.java:470`).
- A row that mixes both kinds, such as std140 `struct { float a; int b; }`, is uploaded to both arrays from the same 16 bytes, and each member is read from the array of its kind.
- A bool reads as `og_i_I[k].x != 0` and a uint as `uint(og_i_I[k].x)`.

Lua's view, std140 offsets in one byte string, is unchanged. Every physical row counts against the stage's row cap. The S6 built-in rows (§7) are float-only.

### 2.6 Arrays and indexing

**Array values.** Arrays are 1-D, of any non-opaque type (ES300 §4.1.9 forbids arrays of arrays), and sized by constant integral expressions. They are full values:
- constructors such as `float[3](…)`, and unsized declarations with an initializer;
- assignment, `==` and `!=`;
- parameters and return values;
- `.length()`, a constant int (ES300 §4.1.9).

**Whole-array cost and size cap** [proposal; verification note V6]. These value operations are new relative to ES 1.00, and each costs O(N): assignment, `==`/`!=`, constructors, passing and returning, and zero-initialization (emitted as constructors, not loops, see below). §8.1 weights them at N × the element's weight. A local or global array holds at most 64 vec4-sized elements per stage, so the zero-initializing constructor text and the driver's register allocation stay bounded; uniform arrays are already bounded by the row caps.

**Dynamic indexing.** It is allowed wherever ES300 §12.30 mandates it: arrays, vectors and matrices, but not sampler arrays, fragment-output arrays or uniform-block arrays. Every dynamic index is clamped: an int index i becomes `clamp(i, 0, N-1)` and a uint index u becomes `min(u, uint(N-1))`. ANGLE does the same with a float clamp (`src/compiler/translator/tree_ops/ClampIndirectIndices.cpp:66-130`), kept for a Qualcomm integer-clamp bug (`:78-84`) that desktop targets do not need. The clamp is elided where the index range is proven inside the array [proposal]; the common case is an Appendix-A loop index, whose range the trip simulation already knows.

**Swizzles and local arrays.** An l-value that dynamically indexes a swizzle (`v.zyx[i] = e`) is rewritten into a select chain. ANGLE does this for every vendor on Windows, Apple and Android (`removeDynamicIndexingOfSwizzledVector`, `renderergl_utils.cpp:2447-2448`; crbug 709351). Local arrays are zero-initialized with constructors, not loops (ANGLE `dontUseLoopsToInitializeVariables`, `:2374-2375`).

### 2.7 Samplers

`sampler2D` is available in the fragment stage, at most 4 units assigned in declaration order (22 §1.3). Sampler arrays need constant indices (ES300 §4.1.7.1), and samplers may be function parameters. Excluded:
- **vertex-stage samplers** (24 §3.4, "no vertex texturing"): the GPU-work guard needs `gl_Position` to be a function of uniforms and attributes (22 §1.4); open question 3;
- **`sampler3D`, `sampler2DArray` and shadow samplers**: no v1 API creates those textures;
- **`isampler2D` and `usampler2D`**: user-visible index textures are normalized `GL_R8`, and the `R8UI`/`R16UI` surfaces are private (24 §3.7), so no integer texture exists to bind. A mismatched sampler and texture kind is undefined (26 G5; GLSL330 §8.7).

An index is read exactly with `uint(texelFetch(t, p, 0).r * 255.0 + 0.5)`, because unorm8 converts to c/255 (GL33 §2.1.5; 26 R5). Whether `samplerCube` joins v1 is open question 2.

### 2.8 Built-in variables (the `BuiltinInfo` table, 26 S2)

| Variable | Stage, access | OpenGPU v1 |
|---|---|---|
| `gl_VertexID` | VS, read | the fetched index for `glDrawElements*`, first + i for `glDrawArrays*`. OpenGPU's draws are 25 C83–C86 and none uses a base vertex, so ANGLE's Apple-AMD base-vertex problem (`renderergl_utils.cpp:2311`) cannot arise |
| `gl_InstanceID` | VS, read | 0..n−1 |
| `gl_Position` | VS, write | zero-initialized; Y negated in the epilogue (22 §1.3) |
| `gl_PointSize` | VS, write | excluded: no point primitives (20 §2) |
| `gl_FragCoord` | FS, read | top-left origin (§7) |
| `gl_FrontFacing` | FS, read | yes (§7) |
| `gl_FragDepth` | FS, write | set to `gl_FragCoord.z` on entry when statically written (ES300 §7.2 leaves paths that skip the write undefined) and clamped to [0, 1] on exit (ANGLE `clampFragDepth`, NVIDIA, `renderergl_utils.cpp:2361-2363`) |
| `gl_PointCoord` | FS, read | excluded |
| `gl_DepthRange` | both, read | emitted as the constants {0, 1, 1} |
| `gl_Max*` constants | both | OpenGPU's caps (§8.2), not ES300 §7.3's minima. A documented departure: `gl_MaxVertexAttribs` is 8, below ES's 16 |
| `og_` inputs and uniforms | | §7 |

### 2.9 Excluded, with reasons

| ES 3.00 feature | Reason |
|---|---|
| `while`, `do-while` | no static bound; the hang-safety model needs simulated trip counts |
| uniform blocks, `layout(std140/shared/packed, row_major)` | OpenGPU packs uniforms itself; GLSM does not cache indexed UBO bindings |
| vertex-stage samplers | the CPU position slice of the GPU-work guard (22 §1.4) |
| `sampler3D`, `sampler2DArray`, shadow and integer samplers | no such user texture in v1; integer surfaces are private (24 §3.7) |
| `gl_PointSize`, `gl_PointCoord` | no point primitives |
| more than one fragment output, outputs at location > 0 | one draw buffer (22 §1.4) |
| integer and matrix vertex inputs | stream formats are float-only today (22 §1.3); open question 1 |
| ternary or sequence on arrays, `void` operands | optional in ES300 §5.7 and §5.9; rejected by WebGL 2 |
| samplers inside structs | keeps unit assignment in declaration order [proposal] |
| `packHalf2x16` | §3 |
| `gl_FragColor`, `gl_FragData`, `attribute`, `varying`, `texture2D` | removed by ES 3.00 itself |

## 3. Built-in functions against GLSL 3.30

**Method.** Every function name in ES300 §8 was searched in the local GLSL330 text. All are present except the six packing functions of ES300 §8.4, which arrived in GLSL 4.00/4.20 (or `ARB_shading_language_packing`). ANGLE emulates exactly these six when it emits GLSL below 4.10 or 4.20 (`src/compiler/translator/glsl/BuiltInFunctionEmulatorGLSL.cpp:61-232`).

| ES 3.00 group | Functions | In GLSL 3.30 | Semantics vs ES 3.00 | OpenGPU v1 |
|---|---|---|---|---|
| Angle and trigonometry (§8.1) | `radians degrees sin cos tan asin acos atan`(1 and 2 args)` sinh cosh tanh asinh acosh atanh` | all | same definitions; ES300 §4.5.1's precision table has no 3.30 counterpart | pass-through. `atan(y, x)` is emitted through ANGLE's emulation (NVIDIA, `renderergl_utils.cpp:2354-2356`; ≈ 5 ops) [proposal: unconditional] |
| Exponential (§8.2) | `pow exp log exp2 log2 sqrt inversesqrt` | all | same | pass-through |
| Common (§8.3) | `abs sign` (float, int), `floor trunc round roundEven ceil fract mod modf`, `min max clamp` (float, int, uint), `mix` (float and bvec selector), `step smoothstep isnan isinf` | all | same, including `round`'s implementation-chosen .5 | `round` is emitted as `roundEven`, which both specs permit ("This includes the possibility that round(x) returns the same value as roundEven(x)", ES300 §8.3; GLSL330 §8.3) and which matches compute's proposed half-even rounding [proposal]. `clamp` with non-constant bounds is emitted as `min(max())` (§4, I11). Integer `abs` becomes a select (ANGLE `emulateAbsIntFunction`, §5). `isnan`/`isinf` are best-effort, because GPU compilers may assume no NaNs (ES300 §4.5.1) |
| Bit casts (§8.3) | `floatBitsToInt floatBitsToUint intBitsToFloat uintBitsToFloat` | all | same; Inf or NaN into `*BitsToFloat` is unspecified in both | pass-through |
| Packing (§8.4) | `packSnorm2x16 unpackSnorm2x16 packUnorm2x16 unpackUnorm2x16 packHalf2x16 unpackHalf2x16` | none | — | the four norm functions and `unpackHalf2x16` are polyfilled (below, with their exactness); `packHalf2x16` is excluded in v1 |
| Geometric (§8.5) | `length distance dot cross normalize faceforward reflect refract` | all | same | pass-through |
| Matrix (§8.6) | `matrixCompMult outerProduct transpose determinant inverse` | all (`inverse` since 1.40, `determinant` since 1.50) | same | pass-through. Vector and matrix constructor arguments are scalarized (ANGLE `scalarizeVecAndMatConstructorArgs`, NVIDIA and Mali, `renderergl_utils.cpp:2707-2709`), at no runtime cost |
| Vector relational (§8.7) | `lessThan lessThanEqual greaterThan greaterThanEqual equal notEqual any all not` | all | same | pass-through |
| Texture (§8.8), `sampler2D` forms | `textureSize texture textureProj textureLod textureOffset texelFetch texelFetchOffset textureProjOffset textureLodOffset textureProjLod textureProjLodOffset textureGrad textureGradOffset textureProjGrad textureProjGradOffset` | all | same; offsets are constant expressions in both, with the range given by `MIN/MAX_PROGRAM_TEXEL_OFFSET` (−8/7 minimum in GL 3.3 and ES 3.00) | below |
| Fragment processing (§8.9) | `dFdx dFdy fwidth` | all | same; fragment stage only | pass-through; `dFdy`'s sign follows OpenGPU's Y-down convention (§7) |

**Texture functions.**
- **Filtered lookups.** `texture`, `textureProj`, `textureLod`, `textureGrad` and their offset forms pass through. v1 textures have one level (22 §1.2; 24 §3.7), so explicit LODs and gradients cannot reach an undefined level. Bias in the vertex stage is moot, since that stage has no samplers.
- **`texelFetch`, `texelFetchOffset`.** The emitter calls a helper that adds the offset first, clamps the coordinate so the fetch stays inside the texture, and returns `vec4(0)` when the requested coordinate lies outside. The early offset addition subsumes ANGLE's Intel-macOS `rewriteTexelFetchOffsetToTexelFetch`. The zero result is WebGL 2.0's rule: "Texel fetches that have undefined results in the OpenGL ES 3.0 API must return zero" (WebGL 2.0, "Differences Between WebGL and OpenGL ES 3.0", "Texel Fetches"); GL33 §2.11.7 leaves such fetches undefined. The lod argument is still evaluated. v1 textures have one level, so a lod other than 0 also returns `vec4(0)`: GL33 §2.11.7 lists a lod outside [levelbase, levelmax] as undefined, and WebGL 2's rule then gives zero. A constant lod of 0 needs no test. Cost ≈ 7 ops plus one size query, +1 for a non-constant lod. WebGL 2.0's other case, (0, 0, 0, 1) from an incomplete texture, cannot occur: every declared unit is bound to a complete texture or to 26 G5's default (verification note V8).
- **`textureSize(s, lod)`.** The lod is clamped the same way. The query returns the bound texture's size, or (1, 1) for the unbound default (26 G5).
- **Offsets.** They must be constant expressions in [−8, 7] (ES300 §8.8: "The offset value must be a constant expression"); an out-of-range offset is a compile error, as in WebGL 2.
- **Derivatives in non-uniform control flow.** Implicit derivatives there are harmless for v1's single-level nearest textures (22 §1.2). Explicit `dFdx`/`dFdy` inside a non-uniform branch is undefined (ES300 §8.9), so the frontend warns.

**Packing polyfills.** ES300 §8.4's formulas use `round`, whose .5 case is implementation-chosen (ES300 §8.3); the polyfills use `roundEven`, matching compute's proposed half-even rounding (open question 6). The pack forms are then bit-exact against compute where the GPU's fp32 multiply is IEEE-rounded, which ES300 §4.5.1 requires ("Correctly rounded") but GLSL330 §4.1.4 does not [I]. The two norm unpacks divide (`f / 65535.0`, `f / 32767.0`), and division is only required to be within 2.5 ULP (ES300 §4.5.1; unstated in GLSL 3.30), so their results are tolerance-tier floats, not bit-exact. `og_i_halfToFloat` uses only integer operations, bit casts and a power-of-two scale given as a bit pattern, so it is exact for finite halves (verification note V1):

```glsl
uint og_i_packUnorm2x16(vec2 v) {                 // ES300 §8.4: round(clamp(c, 0, 1) * 65535.0)
  uvec2 q = uvec2(roundEven(clamp(v, 0.0, 1.0) * 65535.0));
  return q.x | (q.y << 16);
}
vec2 og_i_unpackUnorm2x16(uint p) { return vec2(p & 0xFFFFu, p >> 16) / 65535.0; }
uint og_i_packSnorm2x16(vec2 v) {                 // round(clamp(c, -1, 1) * 32767.0)
  uvec2 q = uvec2(ivec2(roundEven(clamp(v, -1.0, 1.0) * 32767.0))) & 0xFFFFu;
  return q.x | (q.y << 16);
}
vec2 og_i_unpackSnorm2x16(uint p) {               // clamp(f / 32767.0, -1, 1), halves sign-extended
  ivec2 s = ivec2(uvec2(p << 16, p)) >> 16;
  return clamp(vec2(s) / 32767.0, -1.0, 1.0);
}
float og_i_halfToFloat(uint h) {                  // used by unpackHalf2x16 for each half
  uint e = (h >> 10) & 0x1Fu, m = h & 0x3FFu;
  float v = e == 0u  ? float(m) * uintBitsToFloat(0x33800000u)        // 2^-24; zero, subnormals: exact
          : e == 31u ? uintBitsToFloat(0x7F800000u | (m << 13))       // Inf/NaN: unspecified (GLSL330 §8.3)
          :            uintBitsToFloat(((e + 112u) << 23) | (m << 13)); // normals: exact
  return (h & 0x8000u) != 0u ? -v : v;
}
```

Costs are ≈ 6–14 ops each [est.]. ANGLE's own polyfill truncates the mantissa and flushes half subnormals in `packHalf2x16` (`BuiltInFunctionEmulatorGLSL.cpp:125-170`), and it uses `round()` with an implementation-defined .5. An exact `packHalf2x16` with round-to-nearest-even and subnormals costs about 20 ops [est.]. v1 graphics has no consumer for half bit patterns (no float targets, no user float textures), so `packHalf2x16` is excluded until one exists.

## 4. Integer semantics on the GPU

Each case lists what GLSL 3.30 (and ES 3.00) says, the compute language's rule (22 §2.3), and the emitted form that makes the GPU match.

| # | Case | GLSL 3.30 / ES 3.00 | Compute rule | Emitted form | Cost [est.] | Agreement |
|---|---|---|---|---|---|---|
| I1 | `+ - *`, `++ --`, unary `−` overflow (`−INT_MIN`, `abs(INT_MIN)`) | defined: "Operations resulting in overflow or underflow ... will 'wrap' to yield the low-order 32 bits" (GLSL330 §4.1.3; ES300 §4.1.3 for all precisions) | wrap (JLS §15.17–15.18) | native | 0 | exact by both specs; covered by the conformance corpus (§5) |
| I2 | uint `x / 0`, `x % 0` | "Dividing by zero ... result[s] in an unspecified value"; `%` by zero undefined (GLSL330 §5.9) | `x`, `0` | `a / max(b, 1u)`, `a % max(b, 1u)` | 1 | exact |
| I3 | int `x / 0` | unspecified (§5.9) | `x` | `og_i_idiv` | ≈ 12 + udiv | exact |
| I4 | `INT_MIN / −1` | GLSL330 §4.1.3's wrap rule gives INT_MIN [I]; ES300 §4.1.3 allows "either the minimum representable value or the maximum" | INT_MIN | `og_i_idiv` | included | exact |
| I5 | int division with a negative operand | rounding undefined: "The rounding mode is undefined for this version of the specification" (ES300 §12.33); GLSL330 says nothing | truncation (JLS §15.17.2) | `og_i_idiv` | included | exact |
| I6 | int `x % 0`; `%` with a negative operand; `INT_MIN % −1` | undefined: "Results are undefined if one or both operands are negative" (GLSL330 §5.9; ES300 §5.9) | `0`; sign of the dividend (JLS §15.17.3); `0` | `og_i_irem` | ≈ 10 + urem | exact |
| I7 | shift count < 0 or ≥ 32 | "The result is undefined if the right operand is negative, or greater than or equal to the number of bits" (GLSL330 §5.9) | count mod 32 (JLS §15.19) | `a << (b & 31)`, `& 31u` for uint; constant counts folded | 1 | exact |
| I8 | float → int out of range, ±Inf | GLSL330 §5.4.1 only says the fraction is dropped; the range is unstated | saturate to INT_MIN/INT_MAX (JLS §5.1.3) | `og_i_f2i` | ≈ 6 | exact for every non-NaN input |
| I9 | NaN → int or uint | undefined | 0 | the same helpers give 0 where the driver keeps IEEE comparisons | included | **not guaranteed**: GPU compilers may assume NaN-free code ("NaNs are not required to be generated", ES300 §4.5.1) |
| I10 | float → uint, negative or ≥ 2^32 | "It is undefined to convert a negative floating point value to an uint" (GLSL330 §5.4.1; ES300 §5.4.1) | not in 22 §2.3. Proposed: saturate to [0, 4 294 967 295], NaN → 0, in JLS §5.1.3's style. WGSL §15.7.6 also clamps, but to 4 294 967 040, the largest float-representable value | `og_i_f2u` | ≈ 4 | exact for non-NaN |
| I11 | `clamp(x, lo, hi)` with lo > hi (int, uint, float) | undefined (GLSL330 §8.3; ES300 §8.3) | the definitional `min(max(x, lo), hi)` (22 §2.3, "GLSL definitional formula") | emitted as `min(max(x, lo), hi)` unless constant bounds prove lo ≤ hi | 0–1 | exact. A median-of-three lowering (AMD hardware has `med3` instructions [I]) would differ for lo > hi |
| I12 | dynamic index out of range | undefined (GLSL330 §4.1.9, §5.5, §5.6) | fault, zero-fill, deterministic message (22 §2.1, §2.4) | clamp (§2.6) | 1–2, 0 when elided | **divergent by design**: a fragment cannot report a fault, so graphics clamps |
| I13 | int ↔ uint; int, uint → float | bit-preserving (ES300 §5.4.1); conversions "correctly rounded" (ES300 §4.5.1), unstated in GLSL 3.30 | bit-preserving; Java rounds to nearest | native | 0 | exact on IEEE hardware [I] |
| I14 | constant expressions | — | folded with these rules; a constant integer zero divisor is a compile error (22 §2.3) | folded by the shared evaluator | 0 | exact by construction |

**Guard helpers.** These are emitted only when used; vector forms work component-wise.

```glsl
uint og_i_mag(int x)  { return x < 0 ? 0u - uint(x) : uint(x); }       // |x| as uint; INT_MIN -> 2147483648u
int  og_i_idiv(int a, int b) {
  uint q = og_i_mag(a) / max(og_i_mag(b), 1u);                         // b == 0 -> |a|
  return ((a < 0) != (b < 0)) ? int(0u - q) : int(q);                 // truncation; b == 0 -> a
}
int  og_i_irem(int a, int b) {
  uint r = og_i_mag(a) % max(og_i_mag(b), 1u);                         // b == 0 -> 0
  return (a < 0) ? int(0u - r) : int(r);                               // sign of the dividend
}
int  og_i_f2i(float x) {
  return (x >= 2147483648.0) ? 2147483647
       : (x <= -2147483648.0) ? (-2147483647 - 1)
       : (x > -2147483648.0) ? int(x) : 0;                             // last arm: NaN
}
uint og_i_f2u(float x) { return (x >= 4294967296.0) ? 4294967295u : ((x > 0.0) ? uint(x) : 0u); }
```

Every operation inside the helpers is defined in GLSL 3.30: uint subtraction wraps, `int(uint)` and `uint(int)` preserve bits, and the divisor reaching hardware is never zero. Checked values:
- `og_i_idiv`: (−7, 2) → −3, (7, −2) → −3, (INT_MIN, −1) → INT_MIN, (INT_MIN, 1) → INT_MIN, (5, 0) → 5, (INT_MIN, 0) → INT_MIN.
- `og_i_irem`: (−7, 2) → −1, (7, −2) → 1, (INT_MIN, −1) → 0, (x, 0) → 0.

These match Java's `/` and `%` wherever Java defines a result, and the 22 §2.3 rules where it throws.

**Why the magnitude form, not the hardware's signed division.**
- The rounding is explicitly undefined (ES300 §12.33), and `%` with negative operands is undefined in both specs.
- The extra ≈ 10 ops are small next to the GPU's own integer divide, which is a multi-instruction sequence on all three vendors [I].
- No zero divisor ever reaches a driver, which takes a vendor-specific trap or hang path out of the safety argument [I].

**Elision by value ranges** [proposal; ≈ 1–1.5 d, optional]. A forward interval analysis over TIR elides guards and clamps.
- *Seeds:*
  - constants;
  - Appendix-A loop indices, whose range the simulation knows;
  - `.length()`;
  - `gl_VertexID` and `gl_InstanceID` (≥ 0, below the per-draw caps);
  - `gl_FragCoord.xy` ∈ [0, 1024], the maximum target size (24 §3.4);
  - lookups on float samplers ∈ [0, 1]: every v1 user texture is unorm, and the fetch helper returns 0 outside;
  - `textureSize` ∈ [1, 1024].
- *Propagation:* through `+ − ×` by constants, `min`, `max`, `clamp`, `floor`, `fract` and `abs`.
- *Rules:*
  - int `/` and `%` with dividend ≥ 0 and divisor ≥ 1, or uint with divisor ≥ 1, use the native operator. For int this relies on drivers truncating non-negative quotients [I]: GLSL330 §5.9 states only that the remainder is then non-negative, and ES300 §12.33 leaves the rounding mode undefined without limiting that to negative operands; T1 checks it;
  - a float → int or uint conversion whose operand lies inside the target range is native;
  - an index proven in range keeps no clamp.

Guards stay correct without this analysis; it only removes ops.

**Contract.** For the same integer operands, every integer operation on the GPU gives the compute language's result, with three exceptions:
- (a) NaN converted to an integer, whose GPU value is unspecified;
- (b) an out-of-range dynamic index, which the GPU clamps and compute reports as a fault;
- (c) integers computed from float arithmetic, which inherit the float's tolerance (decision 3): `int(x * 255.0)` differs wherever x differs.

Exact agreement in (a) would need NaN tests that drivers may legally fold away. In (b), a fragment invocation cannot report an error. Constant folding and the M3 GPU-work guard use the compute semantics, so both agree with the GPU on the integer part of every expression (§8.3).

## 5. Driver workarounds

ANGLE main (16a0aa0, 2026-10-08) enables its GLSL-output workarounds per vendor in `src/libANGLE/renderer/gl/renderergl_utils.cpp` and maps them to compiler options in `src/libANGLE/renderer/gl/ShaderGL.cpp:129-276`. The options are declared in `include/GLSLANG/ShaderLang.h` (lines below).

| ANGLE workaround | Where ANGLE enables it | Relevance to OpenGPU | OpenGPU emitter |
|---|---|---|---|
| `rewriteIntegerUnaryMinusOperator` (`ShaderLang.h:264-266`: "-(int) into ~(int) + 1") | **D3D11 backend only**: Intel Haswell or Broadwell with driver 15.0–15.4623, vertex shaders (`src/libANGLE/renderer/d3d/d3d11/renderer11_utils.cpp:2181-2184`; `src/libANGLE/renderer/d3d/ShaderD3D.cpp:310-313`; `src/compiler/translator/hlsl/TranslatorHLSL.cpp:255-257`) | none: not a GL path, and a driver generation older than Arrow Lake | not applied; the corpus tests `−x` at INT_MIN in both stages anyway |
| `emulateIsnanFloatFunction` (`:268-270`) | **D3D11 only**: Intel Skylake, driver 16.0–16.4541 (`renderer11_utils.cpp:2178-2180`) | none | not applied; `isnan` is best-effort (§3) |
| `emulateAbsIntFunction` (`:202-203`; vertex stage, `BuiltInFunctionEmulatorGLSL.cpp:17-24`) | GL, **Intel on macOS** (`renderergl_utils.cpp:2293-2294`, crbug 642227) | macOS hosts with Angelica get 4.1 core contexts (20 §7), so Intel Macs can run OpenGPU | applied always: integer `abs` becomes a select (+1 op) |
| `addAndTrueToLoopCondition` (`:260-262`) | GL, Intel on macOS (`:2296`) | same | applied always (free) |
| `rewriteTexelFetchOffsetToTexelFetch` (`:256-258`; feature `preAddTexelFetchOffsets`) | GL, Intel on macOS (`:2451`, crbug 642605) | same | subsumed by the fetch helper (§3) |
| `dontUseLoopsToInitializeVariables` (`:318-320`) | Qualcomm; Intel on macOS (`:2374-2375`) | same | applied always: constructors, not loops |
| `unfoldShortCircuit` (`:224-228`) | all Apple (`:2437`) | macOS | applied only where the right operand has side effects [proposal] |
| `emulateAtan2FloatFunction` (`:280-282`) | all NVIDIA; drivers 364–375 known (`:2354-2356`) | RTX 5060 (driver far newer [I]) | applied always (≈ 5 ops; a rare function) |
| `clampFragDepth` (`:326-328`) | all NVIDIA; ≤ 390 known (`:2361-2363`) | RTX 5060 | applied always (1 op) |
| `rewriteRepeatedAssignToSwizzled` (`:330-332`) | all NVIDIA; fixed in 397.31 (`:2365-2367`) | none today | free by construction: the emitter never chains assignments |
| `scalarizeVecAndMatConstructorArgs` (`:235-237`) | NVIDIA and Mali (`:2707-2709`, crbug 328015191) | RTX 5060 | applied always (no runtime cost) |
| `removeInvariantAndCentroidForESSL3` (`:186-200`) | GL ≤ 4.1, or desktop AMD (`:2350-2352`) | 330 output; RX 7600M XT | `centroid` dropped; `invariant` emitted on both sides (§2.4) |
| `clampIndirectArrayBounds` (`:205-211`) | AMD, Android, or no `KHR_robust_buffer_access_behavior` (`:2410-2413`) | RX 7600M XT | applied always (§2.6) |
| `removeDynamicIndexingOfSwizzledVector` (`:358-359`) | Windows, Apple, Android, all vendors (`:2447-2448`, crbug 709351) | all three owner GPUs | applied always |
| `initGLPosition`, `initOutputVariables`, `initializeUninitializedLocals` (`:219-233, 284-286`) | GL backend: always; Adreno; all but Qualcomm (`ShaderGL.cpp:129-171`) | all | already in 22 §1.2 |
| `explicitFragmentLocations` (`:398-399`) | Qualcomm (`:2641`) | none | outputs always carry `layout(location = 0)` |
| `addBaseVertexToVertexID` (`:351-356`) | Apple AMD (`:2311`) | none: no base-vertex draws | — |
| `wrapSwitchInIfTrue` (`:348-349`) | SPIR-V output only | none for GL | — |
| `PruneEmptyCases` (pass, not an option; `src/compiler/translator/Compiler.cpp:1037-1046`) | every output, unconditional | all | applied always (§2.3) |
| `clampPointSize` (`ShaderGL.cpp:173-176`) | NVIDIA or Android (`renderergl_utils.cpp:2371`) | none: no points in v1 | — |
| `preTransformTextureCubeGradDerivatives` (`ShaderGL.cpp:193-196`) | all Apple (`renderergl_utils.cpp:2715`) | only if `samplerCube` joins v1 (open question 2) | — |

**Correction to 22 §1.1 and 26 S13.** Both say that ANGLE ships integer workarounds for Intel and that this is a hazard on the owner's Intel GPU. In ANGLE main, the two Intel rewrites 22 lists for integer unary minus and float `isnan` (`rewriteIntegerUnaryMinusOperator`, `emulateIsnanFloatFunction`) are D3D11-backend workarounds for 2014–2016 driver ranges. The D3D11 backend also enables `preAddTexelFetchOffsets` for every Intel GPU (`renderer11_utils.cpp:2173`), which OpenGPU's fetch helper subsumes anyway. The three Intel rewrites of the GL backend (`emulateAbsIntFunction`, `addAndTrueToLoopCondition`, `preAddTexelFetchOffsets`) apply only on macOS. The GL backend has no Intel-specific shader rewrite on Windows. Its only Intel-on-Windows condition, `disableBlendEquationAdvanced` (`renderergl_utils.cpp:2754-2758`, "Intel desktop GL drivers fail many Skia blend tests"), hides `GL_KHR_blend_equation_advanced` (`:2147-2152`), which OpenGPU does not use, and the vendor-independent Windows rewrite `removeDynamicIndexingOfSwizzledVector` applies to Intel as to everyone. That is weak evidence of good drivers: Chrome on Windows uses ANGLE's D3D11 backend, so ANGLE's GL backend rarely meets Windows GL drivers [I]. OpenGPU therefore keeps undefined integer cases away from drivers (§4), applies the cheap rewrites unconditionally, avoiding vendor branches as 25 §5.1 does for state rules, and tests the owner's hardware directly.

**Conformance corpus** (`:gl-testkit`, M2). Results are encoded into an integer test target and read back with `glGetTexImage`; `:gl-testkit` may use formats beyond the runtime's set. The oracle is a Java reference, replaced by the SIR interpreter from M3. llvmpipe runs gate CI. The owner's Intel Arrow Lake iGPU, NVIDIA RTX 5060 and AMD RX 7600M XT run per milestone and per driver update, with the GPU chosen through `dgpu-run.ps1` and recorded through `getCaps().hostRenderer` (24 §7 answer 7), each with Angelica absent and present. Vendor deviations go into 23's expectations file.

| Group | Checks | Gating |
|---|---|---|
| T1 integer grid | a, b ∈ {0, ±1, ±2, ±7, INT_MAX, INT_MIN, 0x80000001, random} through `+ − * / % << >> & \| ^ ~`, unary `−`, `abs`, `sign`, `min/max/clamp`, in VS (through a flat varying) and FS. Native unguarded results are recorded as well, which documents why the guards exist | guarded results bit-exact |
| T2 conversions | f2i and f2u at ±2^31, ±2^32, ±Inf, ±0.5; i2f and u2f at 2^24 + 1 and 2^31; NaN recorded only | non-NaN exact |
| T3 `switch` | fallthrough; `default` first, middle and last; a nested switch in a loop with `break` and `continue`; uint selector; 64 labels | exact |
| T4 loops | Appendix-A loops at the 256 cap with int, uint and float indices; `break`, `continue` | exact |
| T5 flat | distinct per-vertex int, uint and ivec4 values on independent triangles, strips and instanced draws: record which vertex supplies the value; equal per-primitive values must come out exact | equal-value case exact |
| T6 interpolation | `noperspective` against `smooth` across a perspective quad | tolerance tier |
| T7 texel access | `texelFetch` inside and outside, `texelFetchOffset`, `textureSize` on bound and default textures, `textureOffset` at −8 and 7 | exact |
| T8 indexing | dynamic indexing of locals, float and integer uniform rows, vectors, matrices, swizzle l-values | exact |
| T9 integer uniforms | rows −1, 1, 0x7F800001, 0x00000001 through `og_i_I`; the same values through float rows (diagnostic) | `og_i_I` exact |
| T10 misc | packing polyfills against Java; `atan(y, x)` in four quadrants and on the axes; `gl_FragDepth` clamp; invariant pairs link; `dFdy` sign under the Y flip | pack forms and `unpackHalf2x16` exact; norm unpacks, `atan` and `dFdy` tolerance; the rest exact |

## 6. Interpolation

- **`smooth`** (default): perspective-correct interpolation (ES300 §4.3.9).
- **`noperspective`**: enabled by `GL_NV_shader_noperspective_interpolation` (§1.5), on float varyings only, since integer varyings must be flat. It passes through to `330 core`, where the keyword is native (GLSL330 §4.3.9). Interpolation is linear in screen space. OpenGPU's Y negation is affine, so linearity is preserved [I]. SIR carries the interpolation class (24 §3.4, R1), and seam 4 documents the formula for the CPU reference.
- **`flat`**: GL33 §2.18 assigns "those [values] of the provoking vertex of the primitive", selected by `glProvokingVertex` between `FIRST_VERTEX_CONVENTION` and `LAST_VERTEX_CONVENTION`, with LAST initially (Table 2.14). OpenGPU never calls `glProvokingVertex`, which GLSM leaves unmapped (24 §3.7 R10a; 26 R10). GLSM sets the mode itself:
  - LAST at context initialization (ANG `GLStateManager.java:710-712`);
  - FIRST while line-stipple emulation is active, re-evaluated in `preDraw` on every redirected draw (`:2647-2650`, `:5741-5751`);
  - `preDraw` is skipped by instanced draws before 2.2.0 (25 §5.3, `AG@2.1.12:GLSM:2261`).

  Under GLSM 2.2.21, every redirected non-line draw that runs `preDraw` calls `setLineStippleActive(false)`, which restores LAST if stipple had set FIRST (`:5741-5751`); the initial call at `:710-712` runs only when a default VAO exists. OpenGPU's triangle draws therefore see LAST except on paths that skip `preDraw` [I], and without Angelica GL's default LAST holds unless another mod changes it. Neither is a contract.

**The flat contract ("either"; WGSL §13.3.1.4, sampling name listed in §3.8.7: "The value is provided by the first vertex or last vertex of the primitive. Whether the value comes from the first or last vertex is implementation dependent").** A flat input receives the value of the first or the last vertex of its primitive, in GL33 Table 2.14's vertex order. Which one may differ between hosts, Angelica versions and frames. A program is deterministic if and only if every vertex of each primitive writes the same value to each flat output. Ways to achieve that:
1. derive the value only from uniforms, constants, `gl_InstanceID` or per-instance streams (24 §3.3, per-instance step);
2. in non-indexed `GL_TRIANGLES` draws, use `gl_VertexID / 3`, which the three vertices of a triangle share; indexed draws do not have this property;
3. duplicate vertices so that each triangle owns its corners (26 R10b's `FLAT` variant).

**Check** [proposal]. At `createPipeline`, when stream step modes are known, a data-flow pass marks each flat output as per-primitive-constant if it depends only on uniforms, constants, `gl_InstanceID` and per-instance streams. Otherwise `programInfo` carries the warning "flat output 'x' may differ between the vertices of a primitive; its value then depends on the provoking vertex". It is not an error, because meshes with duplicated vertices are legitimate and cannot be proven statically. The CPU reference (SIR interpreter, M3's guard, a later software renderer) uses LAST, GL's default.

**`centroid`** is accepted and dropped (§2.4).

## 7. Interface conventions that change from ES 1.00

**Vertex inputs.** 26 S5's predeclared names stay the primary interface. ES 3.00 adds a second spelling: `layout(location = n)` on a user `in` names semantic slot n [proposal], so ports keep their own variable names:

| Slot (24 §3.4) | Predeclared input | Type [proposal] | User spelling |
|---|---|---|---|
| 0 POSITION | `og_Position` | vec3 | `layout(location = 0) in vec3 pos;` |
| 1 NORMAL | `og_Normal` | vec3 | `layout(location = 1) in …` |
| 2 TEXCOORD | `og_TexCoord` | vec2 | `layout(location = 2) in …` |
| 3 COLOR | `og_Color` | vec4 | `layout(location = 3) in …` |
| 4–7 CUSTOM0–3 | `og_Custom0`–`og_Custom3` | vec4 | `layout(location = 4..7) in …`, or a user `in` with no layout (next free CUSTOM slot) |

- **Conflicts and types.** Two declarations of one slot are an error, including a predeclared name used together with a user input at its location (ES300 §4.3.8.1 forbids aliasing). A user input may declare a different float type than the table's. The stream format is checked against the declared type when the pipeline is created (22 §1.3), and missing components are filled with (0, 0, 0, 1).
- **POSITION.** It stays a pipeline rule: compatibility drivers alias attribute 0 (24 §3.4). A shader that never reads a position, such as a full-screen triangle built from `gl_VertexID`, is still valid.
- **Missing streams** follow 24 §3.4: a 1-element VBO or a constant, never `glVertexAttrib4f`.
- **Types.** The table's types are confirmed (owner, 2026-10-09; open question 4); 22 and 24 previously fixed names and slots but not every type.

**Fragment output.** ES 3.00 removes `gl_FragColor`, so the program declares one `out vec4` of any name. The emitter writes it as `layout(location = 0) out vec4 og_o_<name>;`.

**`og_` built-in uniforms** (26 S6 accepted, 24 §3.3; 26 R2). The transforms (ModelView, MVP, normal matrix; 26 names `og_ModelViewProj`), `og_Resolution` (which 24 §3.3 merges with R2's `og_TargetSize`) and the `SET_GLOBALS` values are predeclared, read-only `uniform`s. The exact list belongs to M2's S6 design. The language fixes their rules:
- the `og_` prefix is reserved;
- they cannot be redeclared, initialized or written;
- they are float rows emitted into `og_i_G` (per pass) and `og_i_D` (per draw);
- they count against the stage's row cap only when statically used.

**Coordinates** (26 S10; documented as seam 4).
- **`gl_FragCoord`** has its origin at the top left with y pointing down and pixel centres at .5. ES 3.00 removed `origin_upper_left`, and OpenGPU obtains the orientation with 22 §1.3's `gl_Position.y` negation and swapped `glFrontFace`. z lies in [0, 1] (`gl_DepthRange` is fixed), and w is 1/w_clip.
- **NDC stays y-up.** User NDC +y appears at the top of the image.
- **`gl_FrontFacing`** is true for primitives that are counter-clockwise in user NDC.
- **`dFdy` is new to the language** (ES 1.00 had derivatives only behind `OES_standard_derivatives`). It is the derivative along +`gl_FragCoord.y`, i.e. downward, so it has the opposite sign to the same program on stock GL. In particular `cross(dFdx(p), dFdy(p))` yields the opposite face normal, and ports must negate it. The Shadertoy shim (26 S12) flips `fragCoord`.

**Name mangling** (22 §1.3, extended). Each name class has a prefix that no other class can produce:

| Prefix | Class | Status |
|---|---|---|
| `og_u_` | user globals, locals, functions, struct type names | 22 |
| `og_a_` | user vertex `in` | 22 |
| `og_v_` | varyings | 22 |
| `og_s_` | samplers | 22 |
| `og_o_` | the fragment output | new |
| `og_f_` | struct members | new |
| `og_i_` | OpenGPU internals: predeclared inputs (`og_i_Position` …), uniform arrays `og_i_U`, `og_i_I`, `og_i_G`, `og_i_D`, guard helpers | 22 |

Rejected user identifiers:
- the `gl_` prefix (ES300 §3.9) and the `og_` prefix;
- a leading `_`;
- any `__`. ES300 §3.8 reserves such names for software layers, and with a prefix prepended a user `a__b` would put `__` into the emitted text. 22 rejected only a leading `_`.

GLSM renames `sample` and `new` on word boundaries in every source it receives, and `sampler` only when the backend's minimum GLSL is ≥ 400, which never holds on LWJGL 2 (22 §1.1; ANG `GlslTransformUtils.java:49-56, 74-84`). `sample` is reserved in ES 3.00 and cannot occur. `new` is a legal ES 3.00 identifier, and a struct member called `new` would appear as `s.new`, with word boundaries on both sides. The new `og_f_` class closes that gap, which 22 left open [new finding].

## 8. Effect on 22's machinery

### 8.1 Costing and trip counts

Float weights stay as in 22. Integer and control-flow weights [est.]:

| Operation | Weight |
|---|---|
| int/uint add, sub, compare, bitwise, shift, select, `min`/`max`, int↔uint↔float conversions | 1 (bit casts 0) |
| int/uint multiply | 2 |
| uint `/`, `%` (guard included) | 21 |
| int `/`, `%` (guard included) | 32 / 30 |
| f2i, f2u guard | 6 / 4 |
| dynamic-index clamp | 1–2, 0 when elided |
| `texelFetch` helper | 1 fetch (against the cap of 16) + 7 ops + 1 size query |
| packing polyfills | 6–14 |
| whole-array assignment, `==`/`!=`, constructor, argument, return, zero-initialization | N × the element's weight (§2.6) |
| `switch` | selector + 1 per label (drivers may lower to compare chains) + the costliest entry path, where a path runs from a label through fallthrough to the first `break`, `return`, `continue` or `discard` |

Trip counts are simulated with the shared evaluator, so integer indices wrap and uint indices count unsigned. A loop that does not reach its exit within the cap is rejected with the reason (e.g. "unsigned index wraps"). Float indices keep 22's fp32 simulation and +1 margin, and take only `<`, `<=`, `>`, `>=` (§2.3).

### 8.2 Caps against GL 3.3 and ES 3.00 minima

| Resource | ES 3.00 minimum (ES300 §7.3) | GL 3.3 core minimum | OpenGPU v1 | Holds on every GL 3.3 host |
|---|---|---|---|---|
| vertex attributes | 16 | 16 | 8 (slots 0–7) | yes |
| VS uniform rows | 256 | 256 vec4 | 128 physical rows: `og_i_U` + `og_i_I` + used built-in rows | yes |
| FS uniform rows | 224 | 256 vec4 | 64, counted the same way | yes |
| VS outputs / FS inputs | 16 / 15 vec4 | 64 / 128 components; 60 varying components | 8 rows packed as in ES300 §11, with smooth, flat and noperspective never sharing a row (ES300 §11 already separates smooth and flat), and float, int and uint never sharing a row, because one emitted row is one GLSL variable with one base type [proposal; note E1]; built-ins not counted | yes: at most 32 components ≤ 60, as GL33 §2.11.6 counts components. A driver that rounded each declared varying up to a vec4 could see up to 32 scalar declarations (128 components), so the emitter declares ES300 §11's packed rows as at most 8 `vec4`-sized outputs, one interpolation class and one base type per row, and reads members by swizzle [proposal; verification note V7] |
| integer varyings | inside the above; flat | no separate limit; components count against the same totals (GL33 §2.11.6) | inside the 8 rows | yes |
| FS / VS samplers | 16 / 16 | 16 / 16 | 4 / 0 | yes |
| draw buffers | 4 | 8 | 1 | yes |
| texel offsets | −8 / 7 | −8 / 7 | −8 / 7 | yes |
| texture size | — | 1024 | 1024 | yes |
| `switch` labels | — | — | 64 [proposal] | new |
| loops, ops, depths, source | — | — | 24 §3.4 unchanged | — |

### 8.3 TIR, SIR and the GPU-work guard

- **TIR** gains a structured `switch` node (ordered case blocks with fallthrough flags and `break` targets), integer-semantics intrinsics (`idiv`, `irem`, `udiv`, `urem`, `shl`, `shr`, `f2i`, `f2u`, `clampIndex`) and a `texelFetchChecked` intrinsic. Control flow stays structured and reducible, as 22 §3 requires.
- **The GLSL emitter** lowers the intrinsics to §4's helpers or to native operators where elided.
- **SIR** keeps the `switch` node; the bytecode emitter maps it to `tableswitch`/`lookupswitch`, and the interpreter walks it. The integer intrinsics map to Java operators with 22 §2.3's tests, so both backends share one definition.
- **The GPU-work guard** (24 §3.8 layer 3, M3) runs the `gl_Position` slice as bytecode with compute semantics. The slice may now contain integer operations, `switch`, `gl_VertexID`, `gl_InstanceID` and integer uniform rows. Because the emitted guards give the GPU the same integer results, the slice's integer part is exact; float differences stay within the tolerance the guard already assumes. Without the guards a negative `%` or an out-of-range shift could make the CPU's bounding boxes disagree with what the GPU draws.

### 8.4 One frontend, two profiles

| Component | Shared with compute | Profile differences |
|---|---|---|
| preprocessor | yes | `GL_ES`; `__VERSION__` 300 vs 430; `##` in compute only |
| lexer | yes | keyword and reserved-word tables; literal suffixes (ES: `u`, `f`; compute adds `l`, `lf`, `ul`) |
| parser (Pratt expressions, recursive descent) | yes | graphics: `in`/`out`/`flat`/`smooth`/`noperspective`, `layout(location)`, precision statements. Compute: `buffer` blocks, `layout(local_size, std430, binding)`, `readonly`/`writeonly` |
| symbol tables, scoping, call graph, recursion check | yes | — |
| type system and typer core | yes | implicit conversions off (ES300 §1.1.6) vs GLSL 4.30 §4.1.10; `double` and `int64_t` in compute only |
| `BuiltinInfo` table (26 S2) | yes | entries tagged by profile and stage |
| constant evaluator | yes | identical integer semantics (22 §2.3 and §4 here) |
| integer intrinsics in TIR/SIR | yes | GLSL helpers vs bytecode |
| loop recognition and trip simulation | yes | graphics: constant bounds; compute: uniform bounds costed at dispatch (22 §2.1) |
| costing | yes | per-target weight tables |
| `switch` | yes | new in compute (22 §2.1 excluded it) |
| diagnostics, `#line` mapping | yes | — |
| std140 layout | yes | graphics packs rows into `og_i_U`/`og_i_I`; compute uniforms are std140 (22 §2.1) |
| TIR, SIR, scalarizer, interpreter, bytecode emitter | yes (22 §3) | graphics uses bytecode only for the position slice |
| graphics only | — | interface rules, semantic slots, `og_` built-ins, samplers and texture functions, derivatives, `discard`, flat analysis, mangling, robustness lowering, `330 core` emitter |
| compute only | — | std430, buffer blocks, D1–D5 analysis and injectivity proof, workgroup built-ins, `double`/`int64_t` |

## 9. Effort

Focused solo-developer days, ±50 % as in 24 §6 [est.].

| # | M2 work item | M2 days | Built once, then reused by compute (M3 saving) |
|---|---|---|---|
| 1 | lexer, grammar and preprocessor delta (keywords, uint literals, `layout`, interface qualifiers, `switch`, array syntax, `.length()`, line continuation, the version rule) | 2–3 | 1.5–2 |
| 2 | types and operators (uint vectors, non-square matrices, arrays as values, `% & \| ^ ~ << >>`, compound assignments) | 2.5–4 | 2–3 |
| 3 | `switch` end to end (validation, costing, TIR/SIR node, emission) | 2–3 | 1–1.5 |
| 4 | `BuiltinInfo` and constant-folder delta (≈ 35 new names, `sampler2D` texture family) | 2.5–4 | 1.5–2.5 |
| 5 | integer guards, packing polyfills, range analysis (1–1.5 d of it optional) | 3–4 | 0.5–1 |
| 6 | robustness lowering delta (all dynamic indices, swizzle l-values, checked `texelFetch`, `gl_FragDepth`, default returns, invariant pairs, constructor scalarization) | 1.5–2.5 | — |
| 7 | interface delta (`layout` ↔ semantic slots, interpolation classes, ES300 §11 counting, struct-varying flattening, `og_i_I` rows, flat check) | 2–3 | — |
| 8 | conformance corpus on four implementations (§5) | 2–3 | — |
| 9 | converter, raylib corpora, glslang ES 3.00 leg (§11) | 1.5–2 | — |
| 10 | language reference delta | 1–1.5 | — |
| — | ES 1.00-only work no longer built (Appendix A §5 index classes, Appendix A §7 packing, the 1.00 lowering table) | −(1.5–2) | — |
| | **Total** | **18.5–28 d ≈ +3.7–5.6 w** | **6.5–10 d** |

- **M2:** 7.5–11 w → ≈ 11–16.5 w.
- **M3:** saves the shared items (6.5–10 d) less the compute side of `switch` (bytecode, interpreter, ≈ 1 d), i.e. −5.5 to −9 d ≈ −1.1 to −1.8 w; 6–8 w → ≈ 4.9–6.2 w. Under 24 §3.1 the ES 1.00 and compute frontends were separate (`:lang` "GLSL ES 1.00 and compute frontends"), and the ES 1.00 one had no uint, bitwise operators or `switch`, so the saving is real rather than moved.
- **Net:** +13 to +19 d ≈ **+2.6–3.8 w**. 24's ≈ 28–40 w becomes **≈ 30.5–44 w** (M0 4.5–7, M1 5–7, M1.5 2–3, M2 11.2–16.6, M3 4.9–6.2, M4 3–4).
- **Cuts if needed:** range analysis (−1–1.5 d; guards then always on); struct-varying flattening (−0.5 d; exclude struct varyings instead); `unpackHalf2x16` (−0.5 d).

## 10. Example: index8 sprites with a palette

A batch of sprites: each instance is one quad whose texels are 8-bit palette indices in a `GL_R8` sheet. The palette is an RGBA8 texture of 256 × 16 rows, and each instance picks a row.

**Vertex shader (input):**

```glsl
#version 300 es
// One quad per instance. og_Position: corner offset in pixels; og_TexCoord: texel coordinates;
// og_Custom0: per-instance stream (24 §3.3 per-instance step), xy = sprite position in pixels.
uniform vec2 origin = vec2(0.0);      // S7 initializer (a departure from ES 3.00 §4.3)
out vec2 texel;                       // smooth
flat out uint row;                    // integer varying: flat is mandatory (ES 3.00 §4.3.6)

void main() {
  texel = og_TexCoord;
  row = uint(gl_InstanceID) & 15u;    // palette row: equal on every vertex of the quad (§6)
  vec2 p = og_Custom0.xy + og_Position.xy + origin;
  gl_Position = vec4(p.x / og_Resolution.x * 2.0 - 1.0,
                     1.0 - p.y / og_Resolution.y * 2.0, 0.0, 1.0);   // pixels, y down (§7)
}
```

**Fragment shader (input):**

```glsl
#version 300 es
precision highp float;                // accepted and ignored (§1.2)
uniform sampler2D sheet;              // index8 texture: GL_R8, GL_NEAREST (24 §3.7)
uniform sampler2D palette;            // RGBA8, 256 x 16 palette rows
in vec2 texel;
flat in uint row;
out vec4 color;                       // the single fragment output, location 0

void main() {
  ivec2 t = ivec2(floor(texel));                            // range unknown: guarded (§4)
  uint i = uint(texelFetch(sheet, t, 0).r * 255.0 + 0.5);   // exact; range [0.5, 255.5]: no guard
  vec4 c = texelFetch(palette, ivec2(int(i), int(row)), 0);
  if (c.a == 0.0) discard;                                  // alpha-0 palette entries are transparent
  color = c;
}
```

**Emitted vertex shader** (`#line` maps back to the input; `og_i_U` row 0 holds `origin`, initially (0, 0); `og_i_G` row 0 holds `og_Resolution`):

```glsl
#version 330 core
layout(location = 0) in vec3 og_i_Position;
layout(location = 2) in vec2 og_i_TexCoord;
layout(location = 4) in vec4 og_i_Custom0;
uniform vec4 og_i_U[1];
uniform vec4 og_i_G[1];
out vec4 og_v_r0;                     // smooth float row: .xy = texel (§8.2 packing)
flat out uvec4 og_v_r1;               // flat uint row: .x = row
void og_u_main() {
#line 9
  og_v_r0.xy = og_i_TexCoord;
#line 10
  og_v_r1.x = uint(gl_InstanceID) & 15u;
#line 11
  vec2 og_u_p = og_i_Custom0.xy + og_i_Position.xy + og_i_U[0].xy;
#line 12
  gl_Position = vec4(og_u_p.x / og_i_G[0].x * 2.0 - 1.0, 1.0 - og_u_p.y / og_i_G[0].y * 2.0, 0.0, 1.0);
}
void main() {
  og_v_r0 = vec4(0.0);                // zero-initialized outputs (22 §1.2)
  og_v_r1 = uvec4(0u);
  gl_Position = vec4(0.0);
  og_u_main();
  gl_Position.y = -gl_Position.y;     // Y-down epilogue (22 §1.3); runs after any early return
}
```

**Emitted fragment shader** (samplers get units 0 and 1 by `glUniform1i` after link):

```glsl
#version 330 core
uniform sampler2D og_s_sheet;
uniform sampler2D og_s_palette;
in vec4 og_v_r0;
flat in uvec4 og_v_r1;
layout(location = 0) out vec4 og_o_color;
int og_i_f2i(float x) {
  return (x >= 2147483648.0) ? 2147483647
       : (x <= -2147483648.0) ? (-2147483647 - 1)
       : (x > -2147483648.0) ? int(x) : 0;
}
ivec2 og_i_f2i(vec2 x) { return ivec2(og_i_f2i(x.x), og_i_f2i(x.y)); }
vec4 og_i_fetch(sampler2D s, ivec2 p) {             // texelFetch, lod 0; outside -> 0 (WebGL 2 rule)
  ivec2 sz = textureSize(s, 0);
  vec4 v = texelFetch(s, clamp(p, ivec2(0), sz - 1), 0);
  return all(lessThan(uvec2(p), uvec2(sz))) ? v : vec4(0.0);
}
void og_u_main() {
#line 10
  ivec2 og_u_t = og_i_f2i(floor(og_v_r0.xy));
#line 11
  uint og_u_i = uint(og_i_fetch(og_s_sheet, og_u_t).r * 255.0 + 0.5);
#line 12
  vec4 og_u_c = og_i_fetch(og_s_palette, ivec2(int(og_u_i), int(og_v_r1.x)));
#line 13
  if (og_u_c.a == 0.0) discard;
#line 14
  og_o_color = og_u_c;
}
void main() {
  og_o_color = vec4(0.0);
  og_u_main();
}
```

What the example exercises:
- **Packed varying rows.** `texel` and `row` become two rows: a smooth `vec4` and a flat `uvec4`, read by swizzle (§8.2). They cannot share a row, because they differ in interpolation class and base type.
- **Flat integer varying.** `row` is a flat uint derived from `gl_InstanceID`, which the §6 check proves per-primitive-constant, so no warning appears.
- **Guards and elision.** `floor(texel)` has an unknown range and gets `og_i_f2i`. The palette index has the proven range [0.5, 255.5] (fetch results lie in [0, 1]), so it converts natively. `int(uint)` preserves bits and needs nothing.
- **Fetches.** Both fetches are bounds-checked: the clamp keeps the access legal, and the select returns 0 outside, so a sprite drawn past its sheet reads index 0 deterministically.
- **Cost:** ≈ 40 ALU ops and 2 fetches [est.], far inside FS ≤ 1 024 ops and ≤ 16 fetches.
- **Avoiding the guard.** A program that writes `ivec2(clamp(floor(texel), 0.0, 1023.0))` proves the range, and the guard disappears.

## 11. Integration

### 24 (architecture)

1. **§1 decision 5**, replace the text with: "**Graphics shaders** (changed 2026-10-09): a GLSL ES 3.00-based subset (`#version 300 es` required) with Appendix-A-style bounded `for` loops, simulated trip counts, static costing and caps; integer results equal the compute language's (22 §2.3) through emitter guards; output `#version 330 core`. Specified in 27, which supersedes the ES 1.00 subset of 22 §1."
2. **§2.2 Superseded**, add the row: "22 §1 ES 1.00 subset | 27's ES 3.00-based subset | decision 5 changed (2026-10-09)".
3. **§3.1 `:lang` row**: "GLSL ES 1.00 and compute frontends" → "one GLSL-family frontend with a graphics profile (ES 3.00 subset, 27) and a compute profile (GLSL 4.30 surface, 22 §2), sharing preprocessor, lexer, parser, typer, `BuiltinInfo`, constant evaluator, integer intrinsics, loop simulation and costing (27 §8.4)".
4. **§3.3**:
   - Shaders bullet, append: "Sources start with `#version 300 es`; `opengpu.lua` writes that line once, first, when composing (27 §1.1). `programInfo` warnings include flat outputs that may differ per vertex (27 §6)."
   - Pipelines bullet, append: "`layout(location = n)` on a user vertex `in` names semantic slot n (27 §7)."
   - Uniform model, add: "int, uint and bool rows travel in `og_i_I` via `glUniform4iv` (27 §2.5)."
5. **§3.4 graphics paragraph**, replace it with:

   > "**Graphics: GLSL ES 3.00 subset, host GPU** (27; 22 §1 where 27 does not supersede it). Own ES 3.00 preprocessor (`GL_ES`, `__VERSION__` 300; depth 32, 10 000 tokens), lexer, parser and exact-match typer, shared with the compute profile and driven by `BuiltinInfo` (26 S2). Appendix-A `for` loops over int, uint or float indices (float only with `<`, `<=`, `>`, `>=`) with simulated trip counts; whole-array operations costed per element and arrays capped at 64 vec4-sized elements; no `while`/`do`/recursion; `switch` under ES 3.00 §6.2. uint, `%`, bitwise operators and shifts with compute integer semantics, enforced by emitter guards (27 §4). Dynamic indexing of arrays, vectors and matrices, clamped. `sampler2D` with `texelFetch`/`textureSize`, out-of-range fetches returning 0. smooth, flat ('either' vertex, 27 §6) and noperspective varyings. Static costing; TIR; `#version 330 core` text with zero-initialized outputs and locals, default returns, `#line` mapping and 27 §5's unconditional workarounds. Names get disjoint prefixes `og_u_`/`og_a_`/`og_v_`/`og_s_`/`og_o_`/`og_f_`/`og_i_`; a leading `_` and any `__` are rejected. Float rows pack into `og_i_U[n]` (`glUniform4fv`), rows with int, uint or bool members into `og_i_I[m]` (`glUniform4iv`, 25 C68); one `glUniform*v` call per array per program change."

   Caps paragraph, add: "8 varying rows counted by ES 3.00 §11 with smooth, flat and noperspective rows separate; ≤ 64 `switch` labels; uniform row caps count `og_i_U`, `og_i_I` and used built-in rows."
6. **§3.4 interface additions**:
   - Semantic attributes: append "or `layout(location = n)` on a user `in`".
   - Uniform initializers: "departure from ES 1.00 §4.3" → "departure from ES 3.00 §4.3".
   - `noperspective`: "behind an OpenGPU `#extension` directive, which is required because ES 1.00 does not reserve the word" → "behind `#extension GL_NV_shader_noperspective_interpolation`, which ES 3.00 requires because it reserves the word (§3.8); ANGLE and glslang implement the extension".
   - `og_TargetSize`: use the name `og_Resolution`; replace "(ES 1.00 has no `round()`)" with "(`round()`'s .5 is implementation-defined; 27 §3)".
   - Conventions: add "`dFdy` along +y (down), `gl_FrontFacing` for counter-clockwise in y-up NDC (27 §7)".
   - New bullet: "**Flat varyings** (26 R10b; 27 §6): first-or-last-vertex contract, deterministic only with per-primitive-equal values; `programInfo` warns when that is not provable."
7. **§3.4 compute paragraph**, add: "`switch` (shared frontend, 27 §8.4); float→uint saturates to [0, 2^32−1] with NaN → 0; `clamp` with lo > hi is `min(max())`; a non-void function falling off its end returns zero; constant float expressions that overflow to Inf or NaN are compile errors; `round` is half-even."
8. **§3.8 layer 1**, append: "integer guards and clamped indices (27 §4)".
9. **§3.9**:
   - Replace "the frontend corpus adds raylib's `glsl100` example shaders" with "the frontend corpus adds raylib's `glsl100` shaders converted by 27 §1.3's tool and its `glsl330` shaders with the version line changed (implicit-conversion files as expected rejections)".
   - Add "27 §5's integer conformance corpus, gating on llvmpipe and run on the owner's Intel, NVIDIA and AMD GPUs".
   - Non-gating `glslangValidator` validates inputs as `#version 300 es` with a test prelude, and outputs as `330 core`.
10. **§5 seam 4**: add "the flat 'either' rule, with LAST in the CPU reference (27 §6)".
11. **§6**:
    - Intro: "≈ 28–40 w" → "≈ 30.5–44 w (27 §9: the ES 3.00 graphics language adds ≈ 2.6–3.8 w net)".
    - M2 scope: "full ES 1.00 frontend with trip counts and costing" → "full ES 3.00-subset frontend (27) with trip counts, costing, `switch`, integer guards and range analysis; the integer conformance corpus on llvmpipe, Intel, NVIDIA and AMD (27 §5)". M2 done list: add "the conformance corpus is bit-exact against the Java reference on llvmpipe and the three owner GPUs (NaN cases excepted)". M2 effort: "7.5–11 w" → "11–16.5 w".
    - M3 scope: "Compute frontend" → "compute profile of the shared frontend (27 §8.4), with `switch`"; effort "6–8 w" → "4.9–6.2 w".
12. **§7**:
    - Question 12: replace the "Open: …" sentence with "*Answered 2026-10-09: ES 3.00-based subset; decision 5 changed; specified in 27.*"
    - The M2 design-review list ("questions 11–12") → "question 11".
    - "Considered, not scheduled": remove S13 and R10b (`flat`) from "At the M2 design review"; both are now in M2 through 27.
13. **§8 risks**, add two rows:
    - "17 | Driver integer, `switch` or flat-varying miscompiles on the owner's GPUs; ANGLE's GL workaround list is thin evidence for Windows drivers | M / M | guards keep undefined cases away from drivers; unconditional cheap workarounds; conformance corpus per driver update; renderer device list (G6) (27 §4–5)"
    - "18 | ES 1.00 sources (tutorials, raylib `glsl100`) no longer parse | H / L | error with a conversion hint; converter tool; examples written in ES 3.00 (27 §1.3)"
14. **§9 index**: add the row for 27 (as in the README below); 22's row: append "§1 superseded by 27 where they conflict".
15. **New amendments section**, "Amendments after the language decision (2026-10-09)", listing items 1–14.

### 22 (shader and compute languages)

1. **Header**, after the scope sentence: "§1 is superseded by 27 (ES 3.00-based graphics language, 2026-10-09) where they conflict; the frontend pipeline, std140 reflection, caps method, GPU-work guard and §1.5 stay."
2. **Summary 1 and §1.1 "Drivers diverge" row**: replace the Intel list with "ANGLE's Intel integer rewrites (`rewriteIntegerUnaryMinusOperator`, `emulateIsnanFloatFunction`) are D3D11-backend workarounds for 2014–2016 drivers; its GL backend enables `emulateAbsIntFunction`, `addAndTrueToLoopCondition` and `preAddTexelFetchOffsets` for Intel on macOS only (`renderergl_utils.cpp:2294, 2296, 2451`); NVIDIA gets `emulateAtan2Float`, `clampFragDepth`, `rewriteRepeatedAssignToSwizzled` and `scalarizeVecAndMatConstructorArgs` (27 §5)".
3. **Summary 2**: "Two output dialects suffice: … `#version 120` …" → "One output dialect, `#version 330 core` (24 §7 answer 1 dropped `#version 120`)".
4. **§1 heading**: append "(superseded by 27 where they conflict)".
5. **§1.2**:
   - Preprocessor bullet: "the ES 1.00 preprocessor" → "the ES 3.00 preprocessor (27 §1.4)".
   - Lexing bullet: delete "Floating literals take no `f` suffix" and "`%`, bitwise operators, `switch` and `uint` stay reserved"; add "any `__` is rejected".
   - Appendix A bullet: keep, with "`for` extended to uint indices; float indices only with `<`, `<=`, `>`, `>=`; `switch` allowed (27 §2.3)".
   - "Correction to 09" bullet: "superseded: all dynamic indexing allowed and clamped (27 §2.6)".
   - Precision bullet: "§4.5.3" → "ES 3.00 §4.5.4".
   - Zero-initialization bullet: add "default returns, `gl_FragDepth` initialization and clamp (27 §2.3, §2.8)".
6. **§1.3**:
   - Translation table: replace the ES 1.00 rows with 27's forms (`in`/`out` as written, the user fragment output → `og_o_<name>` at location 0, texture functions unchanged) and drop column B.
   - Uniform-model bullet: add "rows with int, uint or bool members go to `ivec4 og_i_I[m]` via `glUniform4iv` (25 C68), because float rows cannot carry them bit-exactly (27 §2.5)".
   - Mangling bullet: add `og_o_` and `og_f_`, and the `__` rule.
7. **§1.4 caps table**: add an ES 3.00 column and the rows from 27 §8.2 (varyings by ES 3.00 §11, physical uniform rows, `switch` labels).
8. **§2.1 Control flow**: "no `while`, `do` or `switch` in v1" → "no `while` or `do`; `switch` as in 27 §2.3 (shared frontend)".
9. **§2.3 table**, add rows:
   - float→uint saturates to [0, 2^32−1], NaN → 0;
   - `clamp(lo > hi)` = `min(max())`;
   - a non-void function's end returns zero;
   - constant float overflow is a compile error;
   - `round` = half-even (27 §4, §3).
10. **§3**: "One IR" paragraph, add "TIR/SIR gain a structured `switch` node and integer-semantics intrinsics shared by the GLSL helpers and the bytecode (27 §8.3)".
11. **Design implications 1–2**: "The GLSL ES 1.00 frontend" → "The ES 3.00-subset frontend (27)"; "Ship two emitters, 330 core and 120" → "Ship one emitter, 330 core".
12. **New amendments section**, "Amendments after the language decision (2026-10-09)", listing items 1–11.

### 23 (testing)

1. **§4.1, the frontend bullet**, replace with: "The ES 3.00-subset frontend (27): parsing, Appendix-A and `switch` rules, integer guards, error messages, and translation to `#version 330 core` (the `#version 120` dialect was dropped, 24 §7 answer 1). Golden-text tests on the output. In the optional workflow, `glslangValidator` validates inputs as `#version 300 es` with a test prelude that declares the `og_` built-ins and strips S7 initializers, and validates outputs as `330 core`."
2. **§4.1, new bullet:** "**raylib corpus** (26 S12): convert, do not drop. `glsl100` files go through 27 §1.3's converter, which tests the converter; `glsl330` files get `#version 330` → `#version 300 es` plus `precision highp float;`. Files that rely on GLSL 3.30's implicit conversions (e.g. `glsl330/palette_switch.fs`, which divides an `ivec3` by a float) stay as expected rejections. The per-file licence check stays."
3. **§4.1 or §3, new bullet:** "**Integer conformance corpus** (27 §5): gating on llvmpipe against the Java reference; run on the owner's Intel, NVIDIA and AMD GPUs per milestone and driver update; deviations go into the expectations file of implication 7."
4. **Implication 12**: append "`glslangValidator` checks `#version 300 es` inputs and `330 core` outputs (27 §11)."
5. **New amendments section** listing items 1–4.

### README

1. **Index**, add the row: "| `27-graphics-language-es300.md` | Graphics shader language as an ES 3.00-based subset: input form, accepted subset, built-ins against GLSL 3.30, integer guards matching compute, ANGLE driver workarounds re-checked, flat and noperspective, interface conventions, shared frontend, effort, an index8 example |".
2. **22's row**: append "; §1 superseded by 27 where they conflict".
3. **"How it was produced", step 5**: append "After the owner changed decision 5 to an ES 3.00-based graphics language, report 27 specified it, and 22, 23 and 24 were amended to match."

One edit outside these four documents follows from §5: 26 S13's hazard sentence and its verifier note (26 §8, "ANGLE's Intel integer workarounds remain a real hazard") should point to 27 §5's correction.

## Open questions

*Answered 2026-10-09: the owner accepted all nine defaults below. They are now part of this specification.*

1. **Integer vertex inputs.** Should M2 add integer stream formats (`uint8x4`, `uint16x2`) with `in uint`/`uvec*` vertex inputs (`glVertexAttribIPointer`, 25 C82)? It is protocol, so it would have to land before the M4 freeze. *Default: no; decode from unorm streams with `uint(x * 255.0 + 0.5)`.*
2. **`samplerCube` in v1.** *Default: only if M2's texture API creates cube textures; 24 §3.7's G5 already names a cube default.*
3. **Vertex-stage `texelFetch`.** The server holds texture masters (24 §3.6), so the CPU position slice could evaluate exact fetches. *Default: not in v1 (24 §3.4); reconsider after M3's guard.*
4. **Types of the predeclared inputs** (§7: `og_Normal` vec3, `og_TexCoord` vec2, `og_Color` and `og_Custom*` vec4). *Default: as proposed; confirm against 24 §3.4's S5 list.*
5. **`packHalf2x16`.** *Default: excluded in v1; an exact polyfill costs ≈ 20 ops [est.] and needs a consumer.*
6. **`round` → `roundEven`** on the GPU, and half-even `round` in compute. *Default: yes.*
7. **Flat outputs that are not provably per-primitive**: warning or error? *Default: warning in `programInfo`.*
8. **A legacy `#version 100` profile.** *Default: no; converter and error hint. Revisit if users ask (≈ 3–5 d [est.]).*
9. **Uniform blocks as a grouping syntax** mapped into OpenGPU's std140 block, without GL UBOs. *Default: not in v1.*

## Sources

Specifications (local copies in the scratchpad, data only):
- **ES300** = The OpenGL ES Shading Language 3.00, document revision 6 (29 January 2016), `https://registry.khronos.org/OpenGL/specs/es/3.0/GLSL_ES_Specification_3.00.pdf` (scratchpad `web27/glsles300.pdf`, text via `pdftotext`): §1.1.6, §1.5, §3.1, §3.4, §3.5, §3.8, §3.9, §4.1.3, §4.1.4, §4.1.7.1, §4.1.9, §4.2.3, §4.3, §4.3.4–4.3.9, §4.5.1, §4.5.4, §4.6.1, §5.4.1, §5.7, §5.9, §6.1–6.4, §7.1–7.4, §8.1–8.9, §11, §12.30, §12.33, §12.34.
- **ES100** = GLSL ES 1.00 rev. 17 (scratchpad `glsles100.txt`, as 22): §3.7, Appendix A §4.
- **GLSL330** = The OpenGL Shading Language 3.30, revision 6 (11 March 2010), `https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.3.30.pdf` (scratchpad `web22/glsl330.txt`): §4.1.3, §4.1.9, §4.3.4, §4.3.6, §4.3.9, §4.5, §4.6.1, §5.4.1, §5.5, §5.6, §5.9, §6.2, §8 (every ES 3.00 function name searched; none of §8.4's packing functions present).
- **GL33** = OpenGL 3.3 core, `https://registry.khronos.org/OpenGL/specs/gl/glspec33.core.pdf` (scratchpad `web22/gl33core.txt`): §2.1.5, §2.11.6, §2.11.7 ("Texel Fetches", undefined cases), §2.18 (flatshading, Table 2.14, initial `LAST_VERTEX_CONVENTION`), Tables 6.42–6.45 (minima, as 22 §1.4).
- WebGL 2.0, `https://registry.khronos.org/webgl/specs/latest/2.0/` (fetched 2026-10-09; scratchpad `web27/webgl2.html`), section "Differences Between WebGL and OpenGL ES 3.0": "Texel Fetches" ("must return zero"), "Disallowed variants of GLSL ES 3.00 operators", "Only std140 layout supported in uniform blocks".
- WGSL (scratchpad `web22/wgsl.txt`, as 22): §3.8.7 and §13.3.1.4 (interpolation sampling `either`), §15.7.6 (floating-point conversion), §8.8.
- `GL_NV_shader_noperspective_interpolation`, OpenGL ES extension #201, revision 2 (2014-10-24), `https://registry.khronos.org/OpenGL/extensions/NV/NV_shader_noperspective_interpolation.txt`.
- JLS 8 §5.1.3, §15.17–15.19 (as 22).

Code (read only, never executed):
- **ANGLE** = `https://github.com/google/angle` main at `16a0aa002f4995861cafaa7ad3c684e934e15bd1` (2026-10-08), sparse shallow clone in scratchpad `angle27`:
  - `include/GLSLANG/ShaderLang.h:186-200, 202-203, 205-211, 219-237, 246-249, 256-270, 280-286, 318-332, 348-359, 398-399`;
  - `include/platform/gl_features.json` (descriptions and issue links), `include/platform/d3d_features.json:80-92`;
  - `src/libANGLE/renderer/gl/ShaderGL.cpp:129-276`;
  - `src/libANGLE/renderer/gl/renderergl_utils.cpp:2293-2296, 2311, 2350-2352, 2354-2356, 2361-2367, 2374-2375, 2410-2413, 2437, 2447-2448, 2451, 2641, 2707-2709`;
  - `src/libANGLE/renderer/d3d/d3d11/renderer11_utils.cpp:2178-2184`;
  - `src/libANGLE/renderer/d3d/ShaderD3D.cpp:310-317`;
  - `src/compiler/translator/hlsl/TranslatorHLSL.cpp:255-257`;
  - `src/compiler/translator/glsl/BuiltInFunctionEmulatorGLSL.cpp:17-24, 61-232`;
  - `src/compiler/translator/glsl/TranslatorGLSL.cpp:99-104, 117-147`;
  - `src/compiler/translator/tree_ops/ClampIndirectIndices.cpp:66-130`;
  - `src/compiler/translator/tree_ops/AddDefaultReturnStatements.cpp:52-64`;
  - `src/compiler/translator/Compiler.cpp:1043, 1069-1071`;
  - `src/compiler/translator/ExtensionBehavior.cpp:71`; `glslang.l:168`; `glslang.y:808-811`;
  - `src/compiler/translator/spirv/OutputSPIRV.cpp:5607, 5719`.
- **GLSLANG** = `https://github.com/KhronosGroup/glslang` main at `9a1dc7519823c28fe46a4e7092ed512c040937fd` (2026-10-08), files fetched to scratchpad `web27/glslang`: `glslang/MachineIndependent/Scan.cpp:408, 1109-1110`; `Versions.cpp:310, 515`; `ParseHelper.cpp:6424-6427`.
- **ANG** = Angelica 2.2.21 GLSM (scratchpad `Angelica-2.2.21`, a8c29fa, as 22): `GLStateManager.java:710-712, 2647-2650, 3169-3183, 5741-5751, 7962-7964`; `redirect/GLSMRedirector.java:470, 508`; `GlslTransformUtils.java:49-56, 74-84` (as 22).
- **RL** = `https://github.com/raysan5/raylib` at `a043255aa786088e4137b6f0260cf8c8dee03f57` (2026-10-08): `examples/shaders/resources/shaders/` holds `glsl100` (62 files), `glsl120`, `glsl330` (63 files) and `glsl430`; `glsl100/palette_switch.fs:27-36` (the if-ladder ES 1.00 needs for fragment-stage uniform indexing), `glsl330/palette_switch.fs:25-32` (dynamic `palette[index]`, and `color/255.0` with `color` an `ivec3`).

Earlier reports: 20 §2, §6, §7; 22 §1–§3 and its verification notes; 23 §4.1 and implications 7 and 12; 24 §1, §3.1–§3.9, §5–§8; 25 §4 (C64, C68, C82–C86) and §5.3's `preDraw` note; 26 S2, S5, S6, S7, S10, S12, S13, R1, R2, R5, R10, G5 and §8's verifier notes.

## Verification notes

Adversarial verifier, 2026-10-09. Each key claim was re-checked against the primary sources named in Sources (ES 3.00 rev 6 text, GLSL 3.30 rev 6 text, GL 3.3 core text, WebGL 2.0, WGSL, the NV extension text fetched from the Khronos registry, ANGLE 16a0aa0 in scratchpad `angle27`, glslang 9a1dc75 files, Angelica 2.2.21 GLSM, raylib a043255a via the GitHub contents API). The integer guards were re-modelled independently (scratchpad `v27/gc.py`, 98 596 operand pairs, 0 mismatches against Java semantics with 22 §2.3's zero-divisor rules).

**Verdicts on the key claims.**
- **Confirmed:** C1 (all ES300 §8 call names except the six packing functions occur in GLSL 3.30; ANGLE emulates unorm forms below 4.10 and snorm/half forms for 3.30 ≤ v < 4.20, `BuiltInFunctionEmulatorGLSL.cpp:66-90`), C2 (`renderer11_utils.cpp:2179-2185`, `ShaderD3D.cpp:310-317`, `TranslatorHLSL.cpp:255-257`), C4, C5, C6 (extension #201, revision 2, October 24, 2014, "OpenGL ES 3.0 and GLSL ES 3.00 are required", moves `noperspective` from the reserved words to the keywords), C7 (GLSL 3.30 §4.6.1 and §4.3.9's matching rule; ES300 §1.1.1 "The invariant qualifier is only allowed on outputs"), C10, C11, C12 (62 and 63 files; `palette_switch.fs:26` and `:32`), C14.
- **Modified:** C3 (wording; V2), C8 (WGSL section; GLSM detail), C9 (stronger evidence; V5), C13 (two omitted NVIDIA/Apple options; V2), C15 (M3 rounding; V9).

**Changes made.**
- **V1 Packing exactness** (Summary 4, §3 table and paragraph, `og_i_halfToFloat`, T10). The text said `round` in ES300 §8.4's formula "is defined as `roundEven`"; the formula uses `round`, whose .5 is implementation-chosen (ES300 §8.3). Using `roundEven` is a permitted choice that matches compute's proposed half-even rounding. Bit-exactness of the pack forms needs an IEEE-rounded multiply, which ES300 §4.5.1 requires but GLSL330 §4.1.4 ("it is not required that the precision of internal processing match the IEEE 754 floating-point specification") does not, so it is [I]. The norm unpacks divide, and division is 2.5 ULP in ES300 §4.5.1 and unspecified in GLSL 3.30, so they are tolerance-tier. `exp2(-24.0)` was replaced by `uintBitsToFloat(0x33800000u)`, because `exp2` has (3 + 2·|x|) ULP in ES300 §4.5.1 and the subnormal path was claimed exact.
- **V2 ANGLE lists** (Summary 6, §5 paragraph and table). "No Intel-on-Windows shader workaround" became "no Intel-specific shader rewrite on Windows": `disableBlendEquationAdvanced` is enabled for `isIntel && IsWindows()` (`renderergl_utils.cpp:2754-2758`) and hides the advanced-blend extension (`:2147-2155`), and `removeDynamicIndexingOfSwizzledVector` is enabled on Windows for every vendor (`:2447-2448`). `emulateIsnanFloatFunction` concerns float `isnan`, not integers. D3D11 also enables `preAddTexelFetchOffsets` for all Intel (`renderer11_utils.cpp:2173`). The NVIDIA list omitted `clampPointSize` (`isNvidia || IsAndroid()`, `:2371`; mapped at `ShaderGL.cpp:173-176`), irrelevant without points, and the Apple-only `preTransformTextureCubeGradDerivatives` (`:2715`; `ShaderGL.cpp:193-196`) matters if `samplerCube` joins v1. Rows added.
- **V3 Float loop indices with `==`/`!=`** (§2.3, §8.1) [new finding]. Simulation in IEEE fp32 can prove that a float index reaches `c1` exactly while the GPU, which GLSL330 §4.1.4 does not bind to IEEE rounding and ES300 §4.5.1 lets round in an undefined mode, misses it and loops forever. That is a GPU-hang path through the safety model, inherited from ES 1.00 Appendix A. Float indices now take only relational operators [proposal].
- **V4 `switch` and ANGLE** (§2.3, §5 table). "No ANGLE GL-backend workaround concerns `switch`" was wrong: `PruneEmptyCases` runs for every output (`Compiler.cpp:1037-1046`) because drivers may reject a final case that holds only a no-op, which ES300 §6.2 allows. The emitter now prunes such cases [proposal]. The report already cited `Compiler.cpp:1043` without using it.
- **V5 Integer uniforms** (§2.5). The NaN hazard is stated by GL33 §2.1.1 ("providing a NaN or an infinity yields unspecified results"), not only inferred. The same sentence makes Inf in float rows unspecified, so "would lose float infinities" was not a distinguishing drawback of the all-int scheme; the reason now given is the per-read bit cast. The split-array rule is unchanged.
- **V6 Whole-array operations** (§2.6, §8.1) [new finding]. ES 3.00 adds array assignment, comparison, constructors, array arguments and returns, none of which ES 1.00 had, and §8.1 gave them no weight. Zero-initialization by constructor (no loops) makes emitted text grow with N, and no array-size cap existed in 22 or 24 within the 16 KB source cap. Added N × element weight and a 64-vec4-element cap per array [proposal].
- **E1 Example and row rule** (editor, after integration). The §10 example declared unpacked varyings (`og_v_texel`, `og_v_row`), contradicting V7's packed rows. It now emits a smooth `vec4` row and a flat `uvec4` row. §8.2 also states what V7 implied: rows are split by base type as well as interpolation class, since one row is one GLSL variable. A program that ES300 §11 fits in 8 rows can need more once base types are split (for example 7 smooth `vec4`, one flat `float` and one flat `uint`), so the cap counts rows the way the emitter packs them, and an overflow is a compile error that names the varyings [proposal].
- **V7 Varying cap** (§8.2). "8 × 4 = 32 ≤ 60 even if a driver gives each varying a full row" holds only for ≤ 15 declarations: ES300 §11 packs up to 32 scalar varyings into 8 rows, and a driver rounding each declaration to a vec4 would need 128 components. GL33 §2.11.6 counts components, so a conforming driver is fine; the emitter now declares the packed rows [proposal, ≈ 0.5 d [est.] inside item 7's range of §9].
- **V8 `texelFetch` lod** (§3). The text claimed WebGL 2's zero rule but clamped a non-zero lod to level 0; GL33 §2.11.7 makes that fetch undefined, so WebGL 2 returns 0. Now a lod ≠ 0 returns 0. WebGL 2's (0, 0, 0, 1) incomplete-texture case is excluded by 26 G5's defaults.
- **V9 Effort** (§9, §11 24 item 11). The arithmetic checks: items sum to 20–30 d, less 1.5–2 d gives 18.5–28 d with endpoints paired low-with-low; reuse 6.5–10 d; 24 §6 rows sum to 28–40 w; new total 30.6–43.8 w. One rounding error: M3 becomes 4.9–6.2 w, not "≈ 5–6.5 w". V3–V8 add under 1.5 d [est.], inside the ±50 % band, so the totals stand.
- **Smaller corrections.** Nesting ≤ 3 comes from 22 §1.4, not 24 §3.4 (§2.3). WGSL's "either" text is in §13.3.1.4; §3.8.7 only lists the name (§6, Sources). GLSM's initial `LAST` runs only when a default VAO exists, and every redirected non-line draw through `preDraw` restores LAST (`GLStateManager.java:710-712, 5741-5751`), so "most likely LAST" was made precise (§6). The native int `/` elision for non-negative operands is marked [I], because neither spec defines the rounding of non-negative quotients explicitly (§4).

**Checked, no change.** The guard helpers reproduce 22 §2.3 in 32-bit arithmetic and use only operations GLSL 3.30 defines (uint wrap, bit-preserving `int(uint)`/`uint(int)` at GLSL330 §5.4.1, non-zero divisors). `og_i_f2i`/`og_i_f2u` saturate and send NaN to 0 by comparison, as stated. Shift guards use `& 31u` for uint counts, as GLSL330 §5.9 requires matching signedness for `&`. The noperspective, invariant, `#line` and raylib statements match their sources. The §10 example's `#line` numbers match its input lines.
