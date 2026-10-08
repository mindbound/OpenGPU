# 20 — GL-first client rendering path (Option 2), with and without Angelica

Research date: 2026-10-08. Scope: the host client's OpenGL path under the owner's decisions of 2026-10-08 (GL-authoritative rendering of 2D and 3D into an offscreen target, PBO readback, no software renderer). Primary leg: Java 8 + LWJGL 2.9.4 + Forge 10.13.4.1614, with Angelica 2.2.21 present (GL 4.6 core context on the owner's Intel GPU) or absent (vanilla legacy context). It builds on 02 §1/§4/§5 and 03 §1/§5/§6; statements that change earlier reports are marked **Correction**.

Citation prefixes (all read-only):

- `MC/` = `C:\Users\astro\Downloads\OC-LuaJIT\build\rfg\minecraft-src\java\` (MCP-decompiled Minecraft 1.7.10 + Forge 10.13.4.1614).
- `A21/` = scratchpad clone `Angelica-2.2.21` (tag 2.2.21, a8c29fa). `GLSM21` = `A21/glsm/src/main/java/com/gtnewhorizons/angelica/glsm/GLStateManager.java`; `RED21` = `A21/glsm/.../glsm/redirect/GLSMRedirector.java`.
- `AM` = scratchpad shallow clone `Angelica-master` (78abc05, 2026-10-07) plus fetched tags 2.2.22, 2.2.25, 2.2.28, 2.2.30, read with `git show <rev>:<path>`.
- `jar21` = `javap` of `GLStateManager.class` from the installed `mods\angelica-2.2.21.jar`.
- `LW/` = `lwjgl-2.9.4-nightly-20150209-sources.jar` (Gradle cache; the same build as the instance's `libraries\org\lwjgl\lwjgl\lwjgl\2.9.4-nightly-20150209`).
- `GTL/` = scratchpad GTNHLib clone at tag 0.11.52 (343a327), `src/main/java/com/gtnewhorizon/gtnhlib/`.
- `LOG` = `C:\Games\Minecraft\instances\Main\minecraft\logs\fml-client-latest.log` (2026-10-06). `ES100` = scratchpad `glsles100.txt` (GLSL ES 1.00 rev. 17).

Tags: **[V]** verified at the cited line; **[S]** specification or vendor documentation; **[I]** inference; **[E]** estimate.

## Summary

1. Do all OpenGPU GL work in `TickEvent.RenderTickEvent` `Phase.START`. It fires every rendered frame on both legs (GUI open, paused, F1, unfocused, and minimized even when Angelica skips world rendering), before Iris starts level rendering. It does not fire during loading screens.
2. Use one code path on both legs: LWJGL2 `GL11`–`GL33` calls, a GL 3.3 floor and a single shader dialect, `#version 330 core`, with explicit `layout(location)` qualifiers and mangled identifiers. Write OpenGPU's own FBO/VAO/program classes rather than using vanilla `Framebuffer` or GTNHLib's helpers.
3. **New hazard.** GLSM rewrites calls by method *name* and keeps the descriptor, so an LWJGL2 overload that `GLStateManager` lacks fails with `NoSuchMethodError`. In 2.2.21 this includes `GL11.glReadPixels(..., long)` (the PBO overload, added in 2.2.22) and `GL15.glGetQueryObjectui(int,int)`, among about 70 such overloads of mapped GL11–GL33 names (§3). A CI linkage test against every supported Angelica jar is mandatory, because `-Dangelica.unmappedGL=STRICT` cannot see these.
4. Readback uses `glGetTexImage(..., long)` into a `GL_PIXEL_PACK_BUFFER`; this overload exists from 2.1.12 (52b0db25; 25 §2), so in 2.2.8, 2.2.21 and master. A `GL_TIMESTAMP` query, which GLSM maps, signals completion and also supplies GPU time; `glGetBufferSubData` fetches the data. The implicit synchronization on the fetch keeps it correct, so no `GLSync` is needed.
5. Tier formats are made exact by integer targets that a final pass writes: R8UI for index8 and R16UI for packed RGB565. The TESR draws an RGBA8 texture expanded from them, so the displayed picture equals the readback. Transfer is 16 KB to 512 KB per frame, a negligible cost [E].
6. Hostile shaders are bounded per invocation by Appendix A loops plus static cost caps. WebGL's own finding is that "even very strict structural limits are insufficient", so OpenGPU also needs chunked submission, a per-frame GPU budget measured with timestamps, and throttling. Neither leg creates a robust context, so a TDR (2 s) means losing the session.
7. Below GL 3.3, on Angelica's GLES profile, or with an Angelica older than 2.1.14 (25 §1.1), graphics are reported unavailable and compute is unaffected. This covers macOS without Angelica, GL 3.1-class Intel HD 2000/3000 and old Mesa.

## Findings

### 1. Offscreen rendering per display

**What vanilla uses [V].** `OpenGlHelper.initializeTextures` sets `framebufferSupported = openGL14 && (ARB_framebuffer_object || EXT_framebuffer_object || OpenGL30)` (`MC/net/minecraft/client/renderer/OpenGlHelper.java:91-92`), picks GL30, ARB or EXT entry points (`:98-138`) and dispatches every FBO call through a switch (e.g. `func_153171_g` = bind, `:480-494`). `isFramebufferEnabled()` is `framebufferSupported && gameSettings.fboEnable` (`:742-744`), and `fboEnable` is a user video option (`MC/.../client/settings/GameSettings.java:71`, toggled at `:491`). `net.minecraft.client.shader.Framebuffer` creates an RGBA8 colour texture plus a `GL_DEPTH_COMPONENT24` (33190) renderbuffer, or `GL_DEPTH24_STENCIL8` when Forge stencil bits are on (`MC/.../client/shader/Framebuffer.java:103-130`). It leaves FBO 0 and texture 0 bound rather than restoring the previous bindings (`:58`, `:134`, `:193`, `:215`), and it silently does nothing when `isFramebufferEnabled()` is false (`:42-46`, `:97-100`). Under Angelica, `OpenGlHelper`'s FBO helpers and `isFramebufferEnabled` are redirected (`RED21:589-599`), and GLSM's version returns `true` unconditionally (`GLSM21:7198-7200`). Vanilla's class therefore behaves differently on the two legs and depends on a user toggle on the vanilla leg. OpenGPU must not use it.

**Entry points.** With the GL 3.3 floor (§7), call `GL30` only. GLSM maps `GL30`, `ARBFramebufferObject` and `EXTFramebufferObject` alike (`RED21:497-522`, `:617-640`), so nothing is gained by the ARB/EXT fallbacks.

**Attachments per display [V/I].**

- *Present texture:* RGBA8, allocated with `glTexImage2D(..., (ByteBuffer) null)`, which is exactly what vanilla's `Framebuffer.java:113` does under GLSM today. Use `GL_NEAREST`, `GL_CLAMP_TO_EDGE` and `GL_TEXTURE_MAX_LEVEL = 0`; the last keeps GLSM's `maybeGenerateMipmap` inert (02 §5).
- *Authoritative surface:* R8UI (index8) or R16UI (RGB565) (§3), with the same `GL_NEAREST` / `GL_TEXTURE_MAX_LEVEL = 0` state, since integer textures with a linear filter are incomplete [S]. GLSM knows both formats (`A21/glsm/.../glsm/texture/InternalTextureFormat.java:52`, `:62`).
- *Scene target* (3D, and 2D at T3): RGBA8 plus a `GL_DEPTH24_STENCIL8` renderbuffer on `GL_DEPTH_STENCIL_ATTACHMENT`. Only 3D-capable displays (≥ 1 MB RAM per decision 7) allocate depth.
- *Size:* the viewport equals the tier resolution. VRAM at T3 is about 1 + 1 + 0.5 + 1 MB for the targets plus 3 × 0.5 MB PBOs ≈ 5 MB per display [E].

**Where in the frame [V].** `Minecraft.runGameLoop` runs the client ticks (`MC/net/minecraft/client/Minecraft.java:1039`) and then `framebufferMc.bindFramebuffer(true)` (`:1052`). Next comes `RenderTickEvent(START)` (`:1065`), which is gated only by `skipRenderWorld`; vanilla never sets that to true (its only assignment is `= false`, `:866`). Then follow `updateCameraAndRender` (`:1067`), `RenderTickEvent(END)` (`:1069`) and `Display.update` (`:1106`). Iris begins level rendering inside `renderWorld` (`A21/src/mixin/java/com/gtnewhorizons/angelica/mixins/early/shaders/MixinEntityRenderer.java:45-64`) and finalizes it in an injection *before* `ForgeHooksClient.dispatchRenderLast` (`:66-78`). Both START and `RenderWorldLastEvent` therefore lie outside `shouldOverrideShaders()` (`A21/src/main/java/net/coderbot/iris/pipeline/DeferredWorldRenderingPipeline.java:916-922`), whose `PROGRAM_CHANGE` listeners return early when it is false (`A21/src/main/java/com/gtnewhorizons/angelica/iris/IrisGLSMBridge.java:264-292`).

`RenderWorldLastEvent` is still the wrong hook for three reasons:

- It fires only when a world exists.
- It fires once per anaglyph pass (`MC/.../renderer/EntityRenderer.java:1212`, `:1430`).
- It does not fire when Angelica skips `updateCameraAndRender` (`A21/src/mixin/.../angelica/MixinMinecraft_IconifyGuard.java:16-28`, §5).

`ClientTickEvent` fires 0–n times per frame. **Correction** to 02 §5 and 08-B §5.5, which offered `RenderWorldLastEvent` as an equivalent alternative: START is the only correct hook. At START the bound draw framebuffer is `framebufferMc`, not 0, whenever FBOs are enabled (`Minecraft.java:1052`), so restoring "to 0" would be wrong.

**State save/restore (same code on both legs).** Saved on entry to the pass, restored on exit, in reverse order:

| State | Saved with | Angelica 2.2.21 answer | Restore |
|---|---|---|---|
| Program | `glGetInteger(GL_CURRENT_PROGRAM)` | cache, `GLSM21:1135` | `glUseProgram(prev)`, the exact id (Iris rule, 02 §2) |
| Draw / read FBO | `GL_DRAW_FRAMEBUFFER_BINDING`, `GL_READ_FRAMEBUFFER_BINDING` | cache, `:1139-1140`; tracked separately, `:6981-6997` | bind each target separately, never `GL_FRAMEBUFFER` |
| VAO (and with it the EBO) | `GL_VERTEX_ARRAY_BINDING` | cache, `:1137`; returns GLSM's default VAO id, not 0 (`:6921-6923`) | `glBindVertexArray(prev)` |
| Array buffer | `GL_ARRAY_BUFFER_BINDING` | cache, `:1130` | rebind |
| Pack / unpack PBO | `GL_PIXEL_PACK/UNPACK_BUFFER_BINDING` | cache, `:1132-1133`; tracked by `trackBufferBinding`, `:6466-6483` | rebind |
| Unpack pixel store: alignment, row length, skip pixels and rows (swap bytes and LSB-first on compatibility contexts) | `glGetInteger(GL_UNPACK_*)` | cache, `:1104-1107` | force alignment 1, row length 0, skips 0 (swap bytes and LSB-first false) around every upload, the LAN-guest upload included; restore after (26 G8) |
| Pack pixel store: the same fields | `glGetInteger(GL_PACK_*)`, only when a readback is queued | not cached; falls to the driver | force the same values around every `glGetTexImage`; restore after (26 G8) |
| Active unit, `GL_TEXTURE_BINDING_2D` on units 0–1 | `glGetInteger` | cache, `:1094-1095` | per unit, active unit last |
| Sampler object on units 0–1 | `GL_SAMPLER_BINDING` | cache, `:1142` (Angelica uses samplers: `LOG:5869` "Sampler Objects: true") | `glBindSampler(unit, prev)`; bind 0 during the pass |
| Enables, blend, colour mask, dither, depth, stencil, scissor test (and box from 2.2.21), viewport, cull and polygon state | `glPushAttrib(GL_ENABLE_BIT \| GL_COLOR_BUFFER_BIT \| GL_DEPTH_BUFFER_BIT \| GL_STENCIL_BUFFER_BIT \| GL_SCISSOR_BIT \| GL_VIEWPORT_BIT \| GL_POLYGON_BIT)` | GLSM emulates all seven bits (`A21/glsm/.../glsm/Feature.java:16-19`); this is depth 1 of 32 (`GLSM21:279`; 18 before 2.2.19, 25 §2) | `glPopAttrib()` |
| Scissor box | `glGetInteger(GL_SCISSOR_BOX, buf)` right after `glPushAttrib` | driver until 2.2.20, cache from 2.2.21; before 2.2.21 `GL_SCISSOR_BIT` restores only the scissor-test enable (44c930f4; 25 §5.1 R-1) | `glScissor(prev)` just before `glPopAttrib()` |
| Renderbuffer binding | `GL_RENDERBUFFER_BINDING` | not cached; falls to the driver (`:1149`) | only touched at creation, so save/restore around creation |

Inside the pass, force the following:

- Disable `GL_BLEND` (except where an op needs it), `GL_SCISSOR_TEST`, `GL_STENCIL_TEST`, `GL_POLYGON_OFFSET_FILL`, `GL_DITHER` and `GL_COLOR_LOGIC_OP`. Set `GL_CULL_FACE` per op.
- Disable `GL_ALPHA_TEST` too: on the compatibility profile the fixed-function alpha test still applies when a fragment shader is bound [S/I], while under GLSM it is emulated only for FFP programs.
- Use `glColorMask(true×4)` and `glDepthMask(true)`, and assert that `GL_RASTERIZER_DISCARD` is off (GLSM tracks it, `:955`).

On the Angelica leg every query is a field read once `isCachingEnabled()` is true after the splash (`GLSM21:398-402`). On the vanilla leg each `glGet*` is a driver round trip that can serialize a multithreaded driver [I]. It is therefore issued once per frame (about 12 queries), and only when work is queued.

**GTNHLib as building blocks [V].** The instance and the pack ship 0.11.52 (pack manifest `manifest-2.9.0-RC-2.json:219-221`), under LGPL-3.0 (`GTNHLib/LICENSE.txt`). It is always present, because OC-GTNH requires it.

- `CustomFramebuffer` offers RGBA8/RGB8/RGBA16F only (`GTL/client/renderer/postprocessing/CustomFramebuffer.java:85-99`). Its "unbind" rebinds Minecraft's framebuffer rather than the previous binding (`:426-432`). Its depth-only renderbuffer path passes `GL_FRAMEBUFFER` as the `glRenderbufferStorage` target (`:187-191`), which is an invalid enum and leaves the attachment unallocated [V code, S effect].
- `ShaderProgram` compiles only from `ResourceLocation` files and silently stores program 0 on failure (`GTL/client/renderer/shader/ShaderProgram.java:25-34`), so it cannot take emitted source.
- `VAOManager`'s old API is `@Deprecated` (`GTL/client/renderer/vao/VAOManager.java:32-35`), and `VertexBuffer` binds 0 after use (`GTL/client/renderer/vbo/VertexBuffer.java:30-44`).
- `GLCaps` reads `GLContext.getCapabilities()` in a static initializer (`GTL/client/opengl/GLCaps.java:31-44`).

Verdict: do not build on them. OpenGPU's own `Fbo`, `Program` and `Vao` classes are small and can restore exactly.

### 2. Shader dialects

**Contexts [V/S/I].**

- *Without Angelica:* `ForgeHooksClient.createDisplay` calls `Display.create(new PixelFormat().withDepthBits(24))` (`MC/net/minecraftforge/client/ForgeHooksClient.java:317-327`). LWJGL2 forwards `null` `ContextAttribs` (`LW/org/lwjgl/opengl/Display.java:755-759`), so `ContextGL` passes a `null` attribute list to the native creator (`LW/org/lwjgl/opengl/ContextGL.java:121-129`), i.e. a legacy context. Per WGL_ARB_create_context, legacy creation equals attribute-based creation with defaults (version 1.0), and a ≤ 3.0 request may return "the compatibility profile of 3.2 or later" [S]. In practice Windows drivers return their highest compatibility version (4.6 on current NVIDIA/AMD/Intel) [I]. macOS legacy contexts are GL 2.1 and older Mesa capped legacy contexts at 3.0/3.1 [I].
- *With Angelica:* a core, forward-compatible 3.3–4.6 context (4.1 on macOS), or GLES 3.2 under `glProfile=ES` (02 §1; `LOG:5809` "Created GL 4.6 core profile context", `:5870`). `RenderSystem` reads `GL_CONTEXT_PROFILE_MASK` and throws on a non-core desktop context (`A21/glsm/.../glsm/RenderSystem.java:170-182`).

**One dialect is enough.** `#version 330 core` compiles on any compatibility context ≥ 3.3, because compatibility implementations accept core-profile shaders [S, GLSL 3.30 §3.3]. It also compiles under GLSM:

- `CompatShaderTransformer.isCoreShader` requires version ≥ 330 and the literal `core` token (`A21/glsm/.../glsm/CompatShaderTransformer.java:167-174`), and `fixupVersion` then returns the source unchanged (`:652-668`).
- `glShaderSource` always runs `renameReservedWords` first (`GLSM21:5822-5831`). This renames `sample` and `new` unconditionally, and `sampler` when the backend's minimum GLSL is ≥ 400 (`A21/glsm/.../glsm/GlslTransformUtils.java:48-58`, `:74-84`). The LWJGL2 backend's minimum is 330 (`A21/glsm/.../glsm/backend/Lwjgl2GLRenderBackend.java:115`); SDL-GPU's is 460.
- The patterns are word-bounded, so prefixed identifiers such as `u_sample` survive. The emitter must never emit a bare `sample`, `new` or `sampler`.
- GLSM leaves pure core programs alone at draw time: `ShaderManager.preDraw` returns early when no compat uniforms are registered ("Don't emulate FFP on non-iris core shaders", `A21/glsm/.../glsm/ffp/ShaderManager.java:144-148`).

A second `#version 120/130` dialect would serve only pre-3.3 compatibility contexts, which §7 excludes. **Correction** to 08-B §5.6 ("`GlBackend` (GL ≥ 3.0, emitting `#version 130`)").

**Binding conventions (both legs).**

- Vertex attributes use `layout(location = n) in`, which is core in 3.3. Always feed location 0, which old compatibility drivers alias to the vertex position [I].
- Fragment outputs use `layout(location = 0) out vec4`, or `out uint` for the integer targets.
- Uniforms are looked up by name after link; explicit uniform locations would need 4.3. Sampler units are set once with `glUniform1i`.
- No UBOs in the early milestones: indexed binding points are not cached by GLSM, and the points Iris uses are unknown.
- GLSL ES 1.00 is lowered as follows: `attribute`→`in`; `varying`→`out`/`in`; `gl_FragColor`→a declared output; `texture2D`/`textureCube`→`texture`; `texture2DLod`→`textureLod`. Precision qualifiers stay; desktop GLSL ≥ 1.30 accepts and ignores them [S]. *Superseded (2026-10-09): inputs are now the ES 3.00-based subset of 27, lowered by 22 §1.3's translation table.*
- Every user identifier is mangled, which also neutralizes GLSL 3.30 builtins such as `texture` used as user names.
- Draw lines and points as triangles: the core context has native line width 1.0 only (`LOG`, GLSM line "native [1.0, 1.0], GS emulation active", which applies to FFP draws only).
- Never generate GL-calling bytecode at runtime. Redirection applies only to classes passing through `LaunchClassLoader` (02 §1); the GL backend is static code.

**Leg detection.** Use `Loader.isModLoaded("angelica")` (modid `angelica` in the jar's `mcmod.info`) and `core = OpenGL32 && (glGetInteger(GL_CONTEXT_PROFILE_MASK) & CORE_BIT) != 0`. Both are logged with 14 §3's `GlCaps` line. Since the code path is identical, the only behavioural branches are "Angelica GLES profile → graphics unavailable" (via `RenderSystem.isGLES()`, `A21/glsm/.../RenderSystem.java:397`, read reflectively), until that profile is tested, and the Angelica version gate (§7; 25 §1.1).

### 3. Readback

**How GLSM rewrites calls [V].** The redirector first checks a small descriptor-keyed table (only `GL32C`/`GL15C` sync, map and debug-label entries, `RED21:656-668`). Otherwise, for any owner under `org/lwjgl/opengl/GL*` it looks the call up by *name alone* in one map merged from GL11–GL46 (`RED21:572-586`, `:947-950`); other owners (`OpenGlHelper`, ARB/EXT classes) use per-owner name maps. A hit rewrites only owner and name: `mNode.owner = GLStateManager; mNode.name = glsmName` (`RED21:964-965`). The descriptor is untouched. `glReadPixels` is in the GL11 map (`RED21:356`), but `jar21` lists only the `ByteBuffer`, `FloatBuffer` and `IntBuffer` overloads (source `GLSM21:5146-5162`), and each of them *suspends* a bound pack PBO (`:6528-6541`) because LWJGL2's buffer variants require no PBO bound.

LWJGL2's PBO variant `GL11.glReadPixels(..., long pixels_buffer_offset)` (`LW/org/lwjgl/opengl/GL11.java:2486`) is therefore rewritten under 2.2.21 to a non-existent `GLStateManager.glReadPixels(IIIIIIJ)V`, and throws `NoSuchMethodError` on first execution [V absence; standard JVM linkage]. The overload appears in tag 2.2.22 (`AM` 2.2.22 `GLStateManager.java:8116`), in 2.2.28, the pack's version (`:8297`), and in master (`:8439`). The same trap affects `GL15.glGetQueryObjectui(int,int)` (`LW/.../GL15.java:478`; GL15 map `RED21:436`): GLSM has only `glGetQueryObjectui(int,int,IntBuffer)` (`jar21`; master `:7415`). Use `glGetQueryObjecti` instead.

`STRICT` mode cannot catch either trap, because both names *are* mapped. **Correction** to 02 §4/§5: "safe as long as the calls are in GLSM's map" must read "the name is mapped *and* `GLStateManager` declares the identical descriptor". 02's "PBO readback is tracked" is true only of the binding.

**The trap is wide [V].** Diffing every public static method of LWJGL 2.9.4's `GL11`–`GL33` whose name is in the 2.2.21 GL maps against `jar21` finds about 70 overloads with no matching `GLStateManager` descriptor. Those in or near OpenGPU's call set are: the `IntBuffer` forms of `glGenFramebuffers`, `glGenRenderbuffers`, `glGenVertexArrays`, `glDeleteVertexArrays` and `glDeleteQueries`; the `(int, IntBuffer, ByteBuffer)` forms of `glGetShaderInfoLog`/`glGetProgramInfoLog`; the client-array forms of `glVertexAttribPointer`; `glTexImage2D(..., ShortBuffer)` and `glTexSubImage2D(..., ShortBuffer | FloatBuffer)`; `glGetTexImage(..., ShortBuffer | FloatBuffer)`; `glReadPixels(..., ShortBuffer)`; `GL30.glGetInteger(int,int,IntBuffer)`; and `GL20.glGetActiveUniform(int,int,int)`. The scalar forms (`glGenFramebuffers()`, `glGetShaderInfoLog(int,int)`, `glVertexAttribPointer(..., long)`, `glTexSubImage2D(..., ByteBuffer | long)`, `glGetInteger(int)`) all link. Separately, `GL30.glClearBuffer*` is *unmapped* in 2.2.21, 2.2.28 and master (only `glClearBufferData/SubData` are mapped, `AM` 2.2.28 `GLSMRedirector.java:552-553`): it reaches the driver directly, is logged once as "Unmapped GL call" under the default `WARN` mode, and throws `IllegalStateException` at class transform under `-Dangelica.unmappedGL=FAIL` or `STRICT` (`RED21:971`, `A21/glsm/.../config/SystemProperties.java:100-117`).

**The path that works from 2.1.12, so on 2.2.8, 2.2.21 and later.** `glGetTexImage(int,int,int,int,long)` was added to `GLStateManager` in 2.1.12 (52b0db25, #1592; 25 §2) and exists in the 2.2.8 checkout (`C:\Users\astro\Downloads\Angelica\glsm\...\GLStateManager.java:7276`), in `GLSM21:8009-8011` and in master (`:8436`). It passes straight to the backend with the PBO still bound. LWJGL2's version requires a bound pack PBO (`ensurePackPBOenabled`, `LW/.../GL11.java:1687-1692`), and the backend's own `GL15.glBindBuffer` keeps LWJGL2's tracker consistent. Pack bindings are tracked by GLSM (`GLSM21:6466-6483`) and cached (`:1133`). Reading the whole texture level is exactly what is needed, since the surface is the display's size, and it needs no read framebuffer.

**Per-frame protocol** (each display, inside the START pass):

1. Run `glQueryCounter(qStart, GL_TIMESTAMP)`, then the render passes, then the quantize pass into the integer surface.
2. Bind `GL_PIXEL_PACK_BUFFER` to `pbo[i]` and the surface on unit 0, then call `glGetTexImage(GL_TEXTURE_2D, 0, GL_RED_INTEGER, GL_UNSIGNED_BYTE or GL_UNSIGNED_SHORT, 0L)`.
3. Call `glQueryCounter(qEnd[i], GL_TIMESTAMP)` and then `glFlush()`.
4. On later frames, poll `glGetQueryObjecti(qEnd[i], GL_QUERY_RESULT_AVAILABLE)`. When it is true, read both timestamps (`glGetQueryObjectui64`) and copy the data with `glGetBufferSubData(GL_PIXEL_PACK_BUFFER, 0, directBuf)`. Use a ring of three PBOs, with at most two frames in flight.

Row sizes (160/320/640 px × 1 or 2 B) are multiples of 4, so the default `GL_PACK_ALIGNMENT` is correct.

**Completion without `GLSync` [S/V].** ARB_timer_query (core in 3.3) records a timestamp "after all previous commands on the GL client and server state and the framebuffer have been fully realized", and `QUERY_RESULT_AVAILABLE` returns `FALSE` instead of blocking. GLSM maps the calls (`RED21:535-544`, `:647-650`; `GLSM21:7037`, `:7062-7063`). That this also covers the pack-buffer write is a spec reading to confirm in M0 [I].

Correctness never depends on the signal: fetching buffer data without `MAP_UNSYNCHRONIZED` waits for pending writes [S]. The rule is therefore: fetch when available, or after 4 frames regardless, accepting at most one GPU-frame stall.

The explicit `glFlush` is needed because Angelica can suppress presents while minimized (`GLStateManager.setPresentSuppressed`, `A21/src/main/java/com/gtnewhorizons/angelica/rendering/FpsReducer.java:88`) and it can remove Minecraft's end-of-frame `glFlush` (`A21/src/mixin/.../angelica/MixinMinecraft_SkipEndFrameFlush.java:11`). The latter is an experimental opt-in: the mixin applies only if `skipEndOfFrameFlush` is set (`A21/src/main/java/com/gtnewhorizons/angelica/mixins/Mixins.java:204-209`), which defaults to false (`AngelicaConfig.java:149-152`) and is false in the owner's `config/angelica-modules.cfg:338`.

Fences would also work on both Java 8 legs: LWJGL2 `GL32.glFenceSync` returns `GLSync`, is absent from GLSM's maps (only `GL32C` long descriptors are mapped) and is never reported (`RED21:67`, `:74-79`), so it passes straight to the driver. They are unnecessary, though, and crash on SDL-GPU. **Correction** to 09 §3.7's checklist ("`glBufferData(null)` orphaning instead of sync objects"): orphaning streams uploads but cannot tell a readback is done; timestamp queries can.

**Cost [E].**

| Tier | Pixels | Surface | Bytes/frame | fps | MB/s | DMA at 2–12 GB/s | CPU copy |
|---|---|---|---|---|---|---|---|
| T1 160×100 | 16,000 | R8UI | 16,000 | 10 | 0.16 | < 0.01 ms | < 0.01 ms |
| T2 320×200 | 64,000 | R8UI | 64,000 | 20 | 1.28 | 0.005–0.03 ms | ≈ 0.01 ms |
| T3 640×400 | 256,000 | R16UI | 512,000 | 20 | 10.2 | 0.04–0.25 ms | ≈ 0.1 ms |

RGBA8 readback would cost 4× (1 MB at T3). The real cost of a *synchronous* `glReadPixels` is draining the GPU pipeline of the frame's queued work (several ms) [I]. That drain is what the PBO path removes. Reading back every presented frame of every watched display (for LAN streaming, Lua `readPixels` and persistence) therefore costs at most about 10 MB/s per T3 display.

**Producing the tier formats on the GPU.** Three options were compared:

- *(A) Palette lookup in the fragment stage, then quantizing an RGBA readback on the CPU.* Duplicate palette colours make the reverse map ambiguous, and it costs 4 B/px.
- *(B) Normalized R8 holding `i/255`.* This depends on the float→fixed rounding of the GL implementation.
- *(C) Integer targets, recommended.* R8UI holds the index. R16UI holds `(r5<<11)|(g6<<5)|b5`, computed with integer ops in the quantize shader. Readback is `GL_RED_INTEGER` with no conversion, so it is exact by construction. Blending is ignored for integer attachments [S], so 2D index ops are overwrite-only, which matches palette semantics. `glClear` must not be used on them: "The result of clearing integer color buffers is undefined" (GL 3.3 core §4.2.3). Clear them by drawing a full-screen quad that writes 0, which stays inside GLSM, or with `GL30.glClearBufferu`, which is unmapped (see above) [I: preference].

With (C):

- 3D, and blended 2D at T3, render to the RGBA8 scene target. A quantize pass then picks the exact nearest palette entry, ties to the lowest index (256-entry brute force, or a LUT plus a verify step; 26 R8), for index8, or rounds to 565 for T3.
- An expand pass writes the RGBA8 present texture as `palette[index]`, or 565 with bit replication done in integer arithmetic.
- The pass writes row 0 = top, so readback bytes need no CPU flip.
- The displayed picture is a pure function of the read-back bytes, and LAN guests apply the same function on the CPU, so host and guests see identical pixels.

### 4. Display in the world (rules from 02/09 re-checked)

- **Host TESR.** It binds the RGBA8 present texture: no upload, idempotent, `glPushAttrib` of the five bits it changes, lightmap 240/240, one `Tessellator` quad, `glPopAttrib` (02 §5 steps 3–5, 09 §3.7). It binds no program of its own, so Iris's `gbuffers_block` pass stays attached and samples unit 0 (02 §2). It still applies.
- **Iris shadow pass.** Master keeps `shadowSkipInMeshTileEntities` ("Skip tile entities whose block already renders in the terrain mesh (getRenderType() != -1)", `AM` HEAD `src/main/java/com/gtnewhorizons/angelica/config/AngelicaConfig.java:368-370`; `ShadowRenderer.java:767-769`). The `@ThreadSafeISBRH(perThread=false)` chassis therefore keeps the TESR out of the shadow pass under default settings (02 §2, 02 §3). It still applies from 2.2.0, where the option appeared; below it every visible TESR, chassis or not, is drawn again in the shadow pass (25 §5.3), which costs one extra idempotent draw. Under the GL path the TESR does no uploads in any case.
- **Celeritas cached bounds.** Classification is per class, by the first instance, and AABBs are cached per instance (02 §3, 03 §1). The M0 single-block cube is unaffected; the "maximum extent from the first call" rule stands for later multi-block designs.
- **LAN guests.** Received surface bytes are expanded on the CPU and uploaded with `glTexSubImage2D` in the START pass (frame-keyed, never in the TESR). This is 02 §5's certified baseline and needs only GL 1.2, so guests have no GL 3.3 requirement.
- **OpenGPU GUI** (decision 11). It draws the same present texture at 1:1 and should return `doesGuiPauseGame() = false`, as OC's screen GUI does (03 §2).

### 5. When does the client actually render?

| Situation | `RenderTickEvent(START)` | World / TESRs | Frame rate | Server-side Lua |
|---|---|---|---|---|
| In world, focused | every frame (`Minecraft.java:1063-1065`) | yes | user cap: vanilla `Display.sync(limit)` when limit < 260 (`:1136-1139`; default 120, `GameSettings.java:72`); Angelica no-ops `Display.sync` and paces with `FramePacer` at the same cap (`A21/src/mixin/.../angelica/MixinMinecraft.java:76-90`, `:102-105`) | runs |
| GUI open (chat, inventory, OpenGPU GUI) | every frame | world still renders behind it (`EntityRenderer.java:1081-1091`) | same | runs (pauses only if the GUI pauses SP) |
| HUD hidden (F1) | every frame | yes; `hideGUI` gates only the overlay (`EntityRenderer.java:1111`) | same | runs |
| SP paused (pause menu, world not opened to LAN) | every frame | yes, timer frozen (`Minecraft.java:1017-1022`) | same | **stops**: `isGamePaused` (`:1117`) makes `IntegratedServer.tick` skip the world tick (`MC/net/minecraft/server/integrated/IntegratedServer.java:104-118`); LAN-open worlds never pause (`getPublic`) |
| Unfocused | every frame | yes | user cap; 10 fps if Angelica's FPS reducer is enabled (default off, `A21/src/main/java/me/jellysquid/mods/sodium/client/gui/SodiumGameOptions.java:254-255`; owner's `angelica-options.json:35-36` has it off) | after 500 ms the pause menu opens when `pauseOnLostFocus` (default true, `GameSettings.java:182`; `EntityRenderer.java:1023-1029`), so SP stops |
| Minimized | every frame | vanilla: yes, but `Display.update` skips the swap, so there is no vsync wait (`LW/.../Display.java:643-650`). Angelica: `updateCameraAndRender` is skipped when the reducer is in its minimized state, and also whenever Minecraft's framebuffer is under 16 px in either dimension (`MixinMinecraft_IconifyGuard.java:20-27`), which on Windows a minimized window's 0×0 client area produces even with the reducer off (vanilla clamps the size to 1, `Minecraft.java:1146-1157`) [I: Windows behaviour]. START, which precedes it (`Minecraft.java:1065-1067`), still fires | vanilla: up to the cap; reducer: 20 Hz loop (`A21/.../rendering/ReducerStateMachine.java:31`) | SP pauses as when unfocused |
| Loading / saving screens | **no**: `LoadingScreenRenderer` drives `Display.update` itself (`MC/net/minecraft/client/LoadingScreenRenderer.java:204`) | no | — | world (un)loading |

**Expected latency [E].** A request is enqueued from an OC worker thread and picked up at the next START (≤ 1 frame). Readback is available 1–2 frames later. The result then goes to the server, a signal is queued, and Lua resumes in its next machine slice (≤ 1 server tick). In total that is about 2–3 client frames plus up to 50 ms: 70–100 ms at 60 fps, or 0.25–0.35 s at 10 fps. Throughput with two frames in flight is min(client fps, tier fps).

**Never hang.** The rules:

- `present()` never blocks. It returns a frame id, and completion arrives as a signal.
- The render thread publishes a heartbeat (a volatile timestamp of the last START).
- A frame not completed within 1 s is reported `dropped`.
- If the heartbeat is silent for more than 2 s, the host is "stalled": new presents return `nil, "host render stalled"`, `readPixels` serves the last completed frame, and recovery is automatic when the heartbeat resumes.
- Hand-off queues are bounded, non-blocking concurrent queues drained at START. Nothing on the render thread waits for the server, and nothing on the server waits for the render thread.

### 6. GPU safety for sandboxed user shaders

**Facts [S].**

- Windows `TdrDelay` is "the number of seconds that the GPU can delay the preempt request from the GPU scheduler … The default value is 2 seconds". `TdrLimitCount` (5) TDRs within `TdrLimitTime` (60 s) crash the system.
- Since WDDM 1.2 the OS sends a preempt request before starting TDR. Hardware preempts at granularities from DMA buffer down to pixel or instruction, so a single long-running shader invocation, or a draw on coarse-grained hardware, trips TDR [S/I].
- Neither leg can recover. Angelica's context is `new ContextAttribs(3, 3).withProfileCore(true).withForwardCompatible(true).withDebug(...)`, with no robustness flags (`A21/src/mixin/.../angelica/MixinForgeHooksClient_CoreProfile.java:63`), and vanilla passes no attributes. A reset therefore leaves an unusable context, almost certainly ending the session [I].

**Precedents [S].**

- The WebGL 1.0 "Defense Against Denial of Service" section says it is "not possible to impose limits on the structure of incoming shaders" in general and that "even very strict structural limits are insufficient to prevent long rendering times". It recommends "Splitting up draw calls with large numbers of elements into smaller draw calls" and "Timing individual draw calls and forbidding further rendering … if a certain timeout is exceeded".
- WebGL's "Supported GLSL Constructs" section requires Appendix A `for` loops and disallows `while`/`do-while`.
- ANGLE enforces Appendix A §4/§5 in `ValidateLimitations`. It offers `limitExpressionComplexity`, `limitCallStackDepth` and `clampIndirectArrayBounds`, plus `initOutputVariables`, documented as "a workaround for drivers which get context lost if gl_FragColor is not written" (`include/GLSLANG/ShaderLang.h`).

**OpenGPU's layers.**

1. *Compiler (static).*
   - Appendix A §4 loops: one `int`/`float` index, a constant initializer, `index relop constant`, a constant step, and no writes to the index in the body (`ES100:4922-4964`). No `while`/`do-while`, no recursion (`ES100:2812`).
   - Trip counts are therefore known, giving a weighted op count per stage. Caps (proposal): VS ≤ 4,096 ops; FS ≤ 1,024 ops and ≤ 16 texture fetches; ≤ 256 iterations per loop and a nested product ≤ 4,096.
   - Clamp dynamic indices, zero-initialize outputs and locals, and use the ES 2.0 minimum resource limits.
2. *Submission.*
   - Cap vertices and instances per draw, and triangles per frame by tier.
   - Validate index ranges on upload, because there is no robust buffer access.
   - Split each display frame into chunks of N draws with `glFlush` between them (WebGL's first recommendation).
3. *Measurement.*
   - Take `qStart`/`qEnd` timestamps per display frame (§3) and keep an EWMA per display.
   - Enforce a host-wide budget (proposal: 3 ms of GPU time per client frame). Displays over budget are scheduled less often, so Lua sees fewer `frame` signals.
   - A single frame over 100 ms suspends that display's programs and signals Lua. Over 500 ms disables OpenGPU graphics for the session.
   - Measurement is one frame late. The static caps must therefore bound the first frame: at an assumed 200 G ALU op/s for a weak iGPU, one full-screen T3 layer at 1,024 ops/fragment costs ≈ 1.3 ms, so reaching 2 s needs ≈ 1,500 such layers in one frame [E]. The per-frame triangle cap combined with the static per-fragment cap keeps this out of reach for retro-scale scenes, and chunking keeps it preemptible on fine-grained hardware.
4. A config kill switch on the client disables OpenGPU graphics locally.

### 7. Minimum GL requirements and behaviour when unmet

**Required on both legs.** The context must be GL ≥ 3.3 and not GLES: core under Angelica (guaranteed), or compatibility ≥ 3.3 without it (`ContextCapabilities.OpenGL33`, present in LWJGL 2.9.4, the lwjglx shim and LWJGL3 per 14 §2.3). When Angelica is present, its version also decides (25 §1.1): 2.2.8 and later are supported; 2.1.14–2.2.7 are best-effort (graphics on, one log warning); anything older, all of 1.x included, gets graphics unavailable. The check reads the `angelica` mod version at client init, because an FML version range on an optional dependency would stop the game from starting. OpenGPU's call set must never hit a missing `GLStateManager` overload in any release from 2.1.14 on (25 §3, §4); the gating CI linkage test checks 2.1.14, 2.2.8, 2.2.21, 2.2.28 and the latest release (25 §6).

What is used, and the version that brings it:

- FBO and renderbuffers (3.0).
- R8UI/R16UI textures, integer fragment outputs, `usampler2D`/`texelFetch` (3.0 / GLSL 1.30).
- VAOs (3.0).
- Explicit attribute/output locations, sampler objects, timer queries (3.3).
- PBOs (2.1).

Runtime checks: internal programs compile and link, every FBO is complete, and `glGetError` is clean after the first pass.

**When unmet.** Set `GlCaps.graphics = false` with a reason, logged once. The card reports graphics unavailable (methods return `nil, reason`; a capability query says `false`). Displays show a fixed "no graphics" bitmap uploaded once through the GL 1.2 baseline. Compute is unaffected, since it runs on the server CPU.

- *Dedicated servers:* graphics are always unavailable (decision 3).
- *LAN:* the host's capability decides for everyone.
- *Who is excluded [I]:* macOS without Angelica (legacy 2.1), Windows GPUs limited to GL 3.1 (Intel HD 2000/3000), and Mesa versions whose legacy context stops below 3.3. Only the GL 3.1-class GPUs are excluded outright: Angelica requests a 4.1 core context on macOS (`A21/src/mixin/.../angelica/MixinForgeHooksClient_CoreProfile.java:77-78`), and Mesa drivers that cap legacy contexts generally still offer 3.3+ core, so those players regain graphics by installing Angelica.

## Design implications for OpenGPU

1. **One client GL path**, `opengpu.client.gl`, with no leg-specific code apart from "GLES, < 3.3 or Angelica < 2.1.14 → unavailable" (25 §1.1). Hook: `RenderTickEvent(START)` with bounded per-frame work. Exact save/restore per the §1 table, using `glPushAttrib` (7 bits, one level) plus explicit object bindings.
2. **Own classes:** `Fbo` (GL30, RGBA8 / R8UI / R16UI / D24S8), `Program` (from emitted source, logs on failure), `Vao`, `PboRing`, `GpuTimer`. No vanilla `Framebuffer`, no GTNHLib GL helpers, and no ARB/EXT fallbacks.
3. **GLSM linkage test in CI.** Run Angelica's own `GLSMRedirector` over OpenGPU's compiled client classes, then assert with ASM that every resulting `GLStateManager` call target exists in each jar of 25 §6: 2.1.14 (best-effort canary), 2.2.8 (floor), 2.2.21 (owner), 2.2.28 (pack) and the latest release. 25 §4 is the call set and deny-list the test asserts. Exposing the redirector's transform entry point to a test is an [I] to confirm against the `glsm` artifact. Add a hand-maintained deny-list (`GL11.glReadPixels(..., long)`, `GL15.glGetQueryObjectui(II)I`, the `IntBuffer` gen/delete forms, the `ShortBuffer`/`FloatBuffer` texture-transfer forms and the other §3 traps, any `GLSync`) as a second net, and flag unmapped calls such as `glClearBuffer*` by running the redirector with `-Dangelica.unmappedGL=FAIL`.
4. **Shader emitter:** `#version 330 core`, `layout(location)` for attributes and outputs, mangled identifiers (never `sample`/`new`/`sampler`), uniforms by name, lines and points as triangles.
5. **Integer authoritative surfaces** (R8UI / R16UI) written by a quantize pass. The RGBA8 present texture is expanded from them on the GPU, and LAN guests expand on the CPU with the same function, so host display, guest display and Lua readback all agree.
6. **Readback:** `glGetTexImage` into a 3-PBO ring, with completion from timestamp queries and an explicit `glFlush`. Read back every presented frame of displays that have a consumer.
7. **Non-blocking `present()`**, a heartbeat, a 1 s frame deadline and a 2 s stall state (§5).
8. **Layered GPU safety** (§6): static Appendix-A cost caps, chunked submission, a timestamp-measured budget, suspend/disable thresholds and a kill switch. Document in-game that a TDR cannot be recovered.
9. **M0 measurements:** `glGetTexImage`-to-PBO throughput and query-availability timing on the owner's Intel GPU, plus one NVIDIA/AMD machine; the vanilla-leg `glGet` cost per frame; and START-pass cost with 1, 4 and 16 displays.
10. **Corrections to earlier reports** (the files are not edited here):
    - 02 §4/§5: "mapped" means name *and* descriptor, and the PBO `glReadPixels` overload is missing in 2.2.21.
    - 02 §5 and 08-B §5.5: RenderWorldLastEvent is not an equivalent alternative to START.
    - 02 §5: GTNHLib's `CustomFramebuffer`/`ShaderProgram` are not "convenient" building blocks for this use.
    - 08-B §5.6: a GL 3.0 / `#version 130` backend is replaced by the 3.3 floor and a single dialect.
    - 08-B §5.5 item 8: the `SoftwareClientBackend` fallback is out (decision 3).
    - 09 §3.7 M5 checklist: completion comes from timestamp queries, not from avoiding sync.

## Open questions for the owner

1. Do you accept GL 3.3 as the floor for graphics on both legs? It excludes GL 3.1-class Intel GPUs outright, and macOS and capped-legacy Mesa setups unless Angelica is installed (Angelica gives them a 3.3+/4.1 core context).
2. Keep compatibility with Angelica 2.2.21, which forces the `glGetTexImage` readback path, or set a floor of 2.2.22+? 2.2.21 is your instance's version; the pack ships 2.2.28.
3. Should Angelica's `glProfile=ES` report graphics unavailable until it is tested?
4. Read back every presented frame of displays that have a consumer (proposal), or only on demand?
5. GPU budget defaults: 3 ms of GPU per client frame, suspend at 100 ms, disable at 500 ms? Should players be able to raise them?
6. Should 2D at T3 blend in 8-bit RGBA and quantize to 565 once per present (proposal), or quantize every draw op?
7. Is an NVIDIA or AMD machine available for the M0 GPU measurements? The instance has only Intel (`LOG:5881`).

## Sources

- Minecraft/Forge (decompiled): `MC/net/minecraft/client/Minecraft.java` (`runGameLoop` 1008-1139, `displayGuiScreen` 866), `MC/net/minecraft/client/renderer/{EntityRenderer,OpenGlHelper}.java`, `MC/net/minecraft/client/shader/Framebuffer.java`, `MC/net/minecraft/client/settings/GameSettings.java`, `MC/net/minecraft/client/LoadingScreenRenderer.java`, `MC/net/minecraft/server/integrated/IntegratedServer.java`, `MC/net/minecraftforge/client/ForgeHooksClient.java`, `MC/cpw/mods/fml/common/FMLCommonHandler.java:333-341`.
- LWJGL 2.9.4-nightly-20150209 sources: `org/lwjgl/opengl/{Display,ContextGL,ContextAttribs,GL11,GL15,GL30,GL32,GL33,ContextCapabilities}.java`.
- Angelica 2.2.21 clone and installed jar: every `A21/`, `GLSM21`, `RED21` and `jar21` path cited above, plus `mcmod.info`. Angelica 2.2.8 checkout `C:\Users\astro\Downloads\Angelica` (`GLStateManager.java:7276`). Angelica master 78abc05 and tags 2.2.22/2.2.25/2.2.28/2.2.30 (`GLStateManager.java`, `AngelicaConfig.java`, `ShadowRenderer.java`).
- GTNHLib 0.11.52: `LICENSE.txt`, `client/opengl/GLCaps.java`, `client/renderer/postprocessing/CustomFramebuffer.java`, `client/renderer/shader/ShaderProgram.java`, `client/renderer/vao/VAOManager.java`, `client/renderer/vbo/VertexBuffer.java`; GTNH pack manifest `manifest-2.9.0-RC-2.json` (GTNHLib 0.11.52, Angelica 2.2.28, lwjgl3ify 3.0.37).
- Instance: `logs/fml-client-latest.log` lines 5805-5881, `config/angelica-options.json`.
- Specifications and documentation: GLSL ES 1.00 rev. 17 Appendix A (`ES100:4922-4964`, `:2812`); WGL_ARB_create_context, https://registry.khronos.org/OpenGL/extensions/ARB/WGL_ARB_create_context.txt; ARB_timer_query, https://registry.khronos.org/OpenGL/extensions/ARB/ARB_timer_query.txt; WebGL 1.0, https://registry.khronos.org/webgl/specs/latest/1.0/ ("Supported GLSL Constructs", "Defense Against Denial of Service"); ANGLE `ShaderLang.h`, https://raw.githubusercontent.com/google/angle/main/include/GLSLANG/ShaderLang.h; ANGLE `ValidateLimitations`, https://chromium.googlesource.com/external/angle/+/master/src/compiler/ValidateLimitations.h and https://android.googlesource.com/platform/external/angle/+/17b3c2f3f8/src/compiler/translator/ValidateLimitations.cpp; Microsoft, "TDR registry keys", https://learn.microsoft.com/en-us/windows-hardware/drivers/display/tdr-registry-keys; Microsoft, "GPU preemption", https://learn.microsoft.com/en-us/windows-hardware/drivers/display/gpu-preemption.
- Earlier reports: 02, 03, 08-B, 09, 14 in this directory.

## Verification notes

Adversarial check on 2026-10-08 against the same sources (2.2.21 clone a8c29fa, `jar21`, `AM` tags, LWJGL 2.9.4 sources and binary jar, `MC/`, GTNHLib 0.11.52, the instance's logs and configs, the Khronos and Microsoft pages). Key claims C1–C11 were re-read at the cited lines. C2, C3, C4, C6, C7, C8, C9, C10 and C11 held as written. The following changes were made:

1. **§3, how GLSM rewrites calls (C1, precision).** The lookup is not by owner and name. For every owner under `org/lwjgl/opengl/GL*`, after a small descriptor-keyed table (`RED21:656-668`), it is by name alone in one map merged from GL11–GL46 (`RED21:572-586`, `:947-950`). Per-owner maps apply only to `OpenGlHelper` and the ARB/EXT classes. The rewrite is at `:964-965`. The conclusion (descriptor kept, so `NoSuchMethodError`; `STRICT` blind, `:971`, `SystemProperties.java:100-117`) stands.
2. **§3 new paragraph and Summary 3: the trap is wide.** A mechanical diff (public static methods of LWJGL 2.9.4 `GL11`–`GL33` from `lwjgl-2.9.4-nightly-20150209.jar` whose names are in the 2.2.21 GL maps, against `javap -s` of `jar21`) gives about 70 unlinkable overloads, not 2. Several sit in OpenGPU's likely call set (the `IntBuffer` gen/delete forms, `ShortBuffer` texture uploads, the 3-argument info-log forms, `GL30.glGetInteger(int,int,IntBuffer)`). The commonly used scalar forms link. Implication 3's deny-list was widened to match.
3. **§3 new paragraph and option (C): `glClearBuffer*` is unmapped and `glClear` on integer buffers is undefined.** GL 3.3 core §4.2.3: "The result of clearing integer color buffers is undefined" (https://registry.khronos.org/OpenGL/specs/gl/glspec33.core.pdf). `GL30.glClearBuffer`/`glClearBufferu`/`glClearBufferfi` are not in any 2.2.21 map, and 2.2.28 and master map only `glClearBufferData/SubData` (`AM` `GLSMRedirector.java:552-553`). They pass to the driver and fail class transform under `FAIL`/`STRICT`. The report had not said how the integer surfaces are cleared.
4. **§1, authoritative surface.** Added the `GL_NEAREST` / `MAX_LEVEL 0` requirement for the integer textures, which are incomplete with linear filtering [S]. The report had stated it only for the RGBA8 present texture.
5. **§3, explicit `glFlush` rationale.** `MixinMinecraft_SkipEndFrameFlush` is gated by `AngelicaConfig.skipEndOfFrameFlush` (`Mixins.java:204-209`), which is "[Experimental]" and defaults to false (`AngelicaConfig.java:149-152`). The owner's `angelica-modules.cfg:338` has it false. So by default Angelica does *not* remove the flush. The present-suppression reason (`FpsReducer.java:88`) still justifies the explicit `glFlush`.
6. **§5, Minimized row (C5, refinement).** `MixinMinecraft_IconifyGuard.java:23` also skips `updateCameraAndRender` when `framebufferWidth` or `framebufferHeight` is under 16, independent of the reducer. Vanilla clamps a 0-size client area to 1 (`Minecraft.java:1146-1157`), so a minimized window on Windows skips world rendering under Angelica even with the reducer off [I]. START still fires because it precedes the redirected call (`Minecraft.java:1065-1067`). C5's conclusion is unchanged.
7. **§7 and Open question 1, who is excluded.** "None of these can run Angelica either" was wrong for macOS and Mesa. Angelica probes up to 4.1 core on macOS (`MixinForgeHooksClient_CoreProfile.java:77-78`), and Mesa drivers that cap legacy contexts generally expose 3.3+ core [I]. Only GL 3.1-class GPUs are excluded outright.

Confirmed without change, with the evidence re-checked:

- **C2:** `jar21` has only the `ByteBuffer`/`FloatBuffer`/`IntBuffer` `glReadPixels` overloads. The `long` overload is present at `AM` 2.2.22 `:8116`, 2.2.28 `:8297` and HEAD `:8439`. LWJGL `GL11.java:2486`.
- **C3:** `glGetTexImage(IIIIJ)V` is in `jar21`, in the 2.2.8 checkout at `:7276` and at HEAD `:8436`. The backend calls `GL11.glGetTexImage`/`GL15.glBindBuffer` (`Lwjgl2GLRenderBackend.java:666-668`, `:961-963`).
- **C4:** `jar21` has `glGetQueryObjectui(IILjava/nio/IntBuffer;)V` only. `glGetQueryObjecti(II)I`, `glQueryCounter(II)V` and `glGetQueryObjectui64(II)J` are present. ARB_timer_query says "The time is recorded after all previous commands on the GL client and server state and the framebuffer have been fully realized".
- **C10:** the WebGL 1.0 text is quoted correctly. TdrDelay's default is 2 s, and the TdrLimitCount/Time defaults are 5 and 60 s. LWJGL 2.9.4 does offer `withRobustAccess`/`withLoseContextOnReset` (`ContextAttribs.java:321`, `:356`), but neither leg uses them.
- **C11:** `IntegratedServer.tick` skips the entire `super.tick()` while paused, not only the world tick.

## Amendments after the Angelica floor study (2026-10-09)

Corrections from 25 §9 (`25-angelica-version-floor.md`), applied in place:

- **Summary 4 and §3 "The path that works…".** `glGetTexImage(IIIIJ)V` exists from 2.1.12 (52b0db25, #1592; 25 §2).
- **§7, Summary 7, §2 "Leg detection" and Design implication 1.** "Must link against ≥ 2.2.21" is replaced by 25 §1.1's policy: 2.2.8 supported, 2.1.14–2.2.7 best-effort, older releases graphics-unavailable through a runtime version check rather than an FML version range. The gate is a second behavioural branch next to the GLES one.
- **§1 state table.** `GL_SCISSOR_BIT` restores the scissor box only from 2.2.21, so a "Scissor box" row with an explicit save and restore was added (25 §5.1 R-1); the stack-depth note gains "18 before 2.2.19" (53776684; 25 §2).
- **§4 "Iris shadow pass".** `shadowSkipInMeshTileEntities` exists only from 2.2.0 (25 §5.3).
- **Design implication 3.** The linkage jars are 2.1.14, 2.2.8, 2.2.21, 2.2.28 and the latest release, asserting 25 §4's call set and deny-list (25 §6).

## Amendments after the engine-lessons study (2026-10-09)

Changes from 26 (`26-lessons-from-general-engines.md`), applied in place:

- **§1 state table.** Two pixel-store rows (26 G8). Unpack and pack alignment, row length and skips (plus swap bytes and LSB-first on compatibility contexts) are forced around every upload, every `glGetTexImage` and the LAN-guest upload, then restored: vanilla `ScreenShotHelper` leaves both alignments at 1 after every F2 (`MC/net/minecraft/util/ScreenShotHelper.java:67-68`), and a foreign row length or skip would corrupt a transfer. GLSM caches only unpack state (`GLSM21:1104-1107`), so pack state, a driver query on both legs, is saved only when a readback is queued.
- **§3 option (C), quantize pass.** The index8 quantize is the exact nearest palette entry with ties to the lowest index, by 256-entry brute force or a LUT plus a verify step. The plain 32³ LUT is dropped: for one plausible cube-plus-greys palette it mis-maps 14 of 256 exact palette colours, black included (26 R8; the count is illustrative).
