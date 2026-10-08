# 26 — Lessons from general-purpose engines and retro hardware

**Status after the language decision (2026-10-09).** The owner adopted S13: graphics shaders are now the ES 3.00-based subset of 27 (24 §1 decision 5), and 27 governs where this report assumes ES 1.00. In particular, the variant family's ES 1.00 limits (no `round()`, no constant arrays, constant-only FS indexing, no `textureSize`) no longer apply, and 27 §5 corrects the ANGLE hazard in S13: the Intel integer rewrites are D3D11-backend or macOS-only. R2's `floor(x + 0.5)` snap stays valid, because ES 3.00's `round()` leaves .5 implementation-defined (27 §3).

Writer, 2026-10-09 (research and verification of 2026-10-08). This report answers the owner's question whether engines outside Minecraft and Java (Godot, O3DE, OpenSceneGraph and others) hold useful lessons for OpenGPU, in architecture, methodology or otherwise. Inputs: three researcher notes and one adversarial verifier, whose corrections are applied here and recorded in Verification notes.

- **Godot's server architecture and GL 3.3 renderer**, with OpenSceneGraph and O3DE Atom for comparison (§1).
- **Shader-language ergonomics** for users who write small programs: Godot, LÖVE, Shadertoy, raylib, bgfx, three.js, GameMaker (§2).
- **Retro techniques**: PS1, N64, Saturn, Dreamcast, Quake and Doom, plus five MIT retro shader packs, mapped onto OpenGPU's built-in shader library and formats (§3).

Baseline: 24 (plan M0–M4 with M1.5), 22, 21 §1–2 and 20. Report 06 already took the API-shape lessons from WebGPU, bgfx, sokol, fantasy consoles, LÖVE, raylib, ImGui and software rasterizers; nothing below repeats them. Nothing here touches the GL 3.3 floor, the `#version 330 core` dialect, LWJGL 2 or the threading model of 24 §3.5.

Citations: `NN §x` for reports; `PREFIX/path:line` for read-only clones, never built or executed (prefixes under Sources). Tags: **[V]** verified at the cited line, **[S]** spec or official documentation, **[I]** inference, **[est.]** estimate, **[P]** proposal made here, **[script]** checked with a throw-away script. Lesson IDs: **G** Godot/OSG/O3DE, **S** shader ergonomics, **R** retro techniques (numbers follow the researcher notes), **V** found by the verifier.

## Summary

1. **Worth looking at, but the architecture holds.** 24's design (queue from any thread to one render thread, immutable ops, confirmed-state readback, coalesced frame signals, polled timestamps) is where Godot arrived after several releases of fixes; Godot's separate render thread, OSG's draw/update overlap and O3DE's frame graph solve problems OpenGPU does not have.
2. **The plan should still change: 27 lessons are adopted, almost all small.** No owner decision is overturned, and no lesson was refuted; the verifier modified 17.
3. **Before M1 (correctness):** palette changes are not ordered, persisted or streamed, so LAN guests miss palette-only frames (R7); the present-time quantize and dither are unspecified (R8); side files persist op bytes, so versioned decoders and an API dump are needed from the first release (G12).
4. **M0 (state hygiene):** handles of different types alias (G1); pixel-store state, which vanilla F2 changes, and Angelica's cached constant vertex attributes are missing from the save/restore rules (G8, V1); a GL allocation ledger should back the 256 MB cap and the leak tests (G10).
5. **M2 (user programs):** an unbound declared sampler would read the previous device's texture, a cross-device leak (G5); a frame that needs a still-linking program must wait, never draw with a substitute (G4); a crash guard stops OC's persisted machines from re-running a TDR-causing program on every load (G11).
6. **M2 (language and library):** semantic attribute slots replace declaration-order binding before the freeze (S5); uniform defaults, `noperspective`, `og_TargetSize`, `index8` textures, PS1 blend modes and a per-instance step; the four planned built-ins become one ES 1.00 `#define` variant family included server-side (`#include <og/…>`) that covers PS1/N64/Dreamcast looks (R3, S8).
7. **Cost:** ≈ +4–7 w on 24–33 w [est.], about half of it correctness and safety; the retro breadth can be phased within or after M2.
8. **Owner decisions (§6):** typed `canvas`/`mesh` shader types (default: not in v1), engine-derived camera and model transforms (default: yes, M2), index-output 3D on T1/T2, an ES 3.00-syntax language (default: no), the default dither (default: none).

## 1. Godot's rendering architecture and GL 3.3 renderer, with OpenSceneGraph and O3DE

Sources: Godot master 65e8d16 (4.8-dev), OpenSceneGraph 2e4ae2e, O3DE e031590, and the GitHub texts of the Godot PRs cited.

### 1.1 Where 24 already matches what Godot learned

Godot's Compatibility renderer (`drivers/gles3`) uses GL 3.3 core on desktop and sits at the feature line OpenGPU aims for: forward single-pass lighting with several shadowless lights, sRGB-space blending, no HDR, no compute ("The OpenGL driver does not use the RenderingDevice abstraction") [S: Godot docs, "Internal rendering architecture"]. Its history confirms several of 24's choices, which need no change:

| 24 choice | Godot's path to the same place | Evidence |
|---|---|---|
| `readPixels` returns confirmed state and never waits; completion arrives as a coalesced signal | Synchronous getters stalled callers (`buffer_get_data` calls `_flush_and_stall_for_all_frames`); users moved them to background threads, which silently flushed the device; thread guards (PR #90400) broke that (issue #99750); `buffer_get_data_async`/`texture_get_data_async` arrived in 4.4 (PR #100110, merged 2024-12-11) | `GD/servers/rendering/rendering_device.cpp:1356-1401`, stall at `:1383` [V]; `GD/servers/server_wrap_mt_common.h:44-56, 138-165`; `GD/core/config/engine.h:105` (sync warning after 5 consecutive syncing frames) |
| Queue from any thread to one render thread; creation never blocks | RIDs are allocated on the caller's thread and initialized later on the render thread | `GD/core/templates/rid_owner.h:162-163, 186-189, 245-249`; `GD/servers/rendering/rendering_server_default.h:136-145` |
| Polled `GL_TIMESTAMP` availability | GLES3 keeps a 3-frame timestamp ring but reads `GL_QUERY_RESULT` blocking | `GD/drivers/gles3/storage/utilities.h:184, 200`; `utilities.cpp:333-375` |
| One `vec4` array per stage, one `glUniform4fv` | GLES3 uses UBOs for scene and material data; at OpenGPU's 128/64 rows nothing is shared across draws | `GD/drivers/gles3/rasterizer_scene_gles3.cpp:1557-1560, 2152-2177` |
| Deterministic rejection above caps | Godot's canvas ignores commands beyond `item_buffer_size` ("If more render commands are issued they will be ignored") | `GD/doc/classes/ProjectSettings.xml:3088-3090` [V] |

The rest of this section is what 24 lacks.

### 1.2 Handles must not alias across types (G1, ADOPT, M0)

Godot draws every RID validator from one counter shared by all allocators (`static inline SafeNumeric<uint64_t> base_id`, `GD/core/templates/rid_owner.h:68`; `validator = 1 + (gen % 0x7FFFFFFF)`, `:157-160`) [V], so a buffer RID fails validation as a texture although `free_rid` is untyped (`GD/servers/rendering/rendering_server_default.h:1201-1208`). OpenGPU's handles are a 16-bit slot plus a 16-bit generation per resource type (06 §1, 09 §3.2), and its `free(h)` is untyped. The first texture and the first buffer of a device are therefore the same integer, a buffer passed where a texture is expected is accepted silently, and `free(h)` cannot tell which pool `h` belongs to [I, arithmetic].

**Change:**
- Handles become 4 type bits + 11 slot bits + 16 generation bits, below 2^31 for OC's `Integer` marshalling. Type 0 is reserved, so 0 stays invalid.
- The validator, `free` and error messages decode the type ("handle 0x… is a buffer, expected a texture").
- The type bits are persisted with the handle table.

2 048 slots per type per device is far above what tier VRAM allows [I]. ≤ 0.5 d [est.].

### 1.3 Written API invariants (G2, ADOPT, M0 text)

Godot shipped `barrier()` and `post_barrier` masks in 4.0–4.2. After the acyclic command graph (PR #84976, 4.3) they do nothing but must stay bound as compatibility methods (`GD/servers/rendering/rendering_device.compat.inc:48-50`, "Does nothing.") [V]. 24 already avoids both of Godot's traps (21 §6, 24 §3.3); this lesson only writes the rule down, so later additions cannot break it:

- (a) No callback waits on, or returns data from, the host GPU, except state the server already holds (confirmed pixels, the cached `hostRenderer`). Any future host-derived value is a signal, never a return value.
- (b) No `barrier`, `sync` or `fence` in graphics or compute. Ordering is per-card submission order plus binding access flags; v2's `barrier()` is workgroup-internal only.
- (c) Compute reaches graphics only through ops in the same device tail.

The verifier rated the marginal value low. It is a short bullet in 24 §3.3, linted by G12's API dump at the M4 freeze.

### 1.4 Driver and state lessons from the Compatibility renderer

**Renderer device list (G6, ADOPT the mechanism, M1).** Godot forces ANGLE on GPUs whose advertised GL 3.3 is unusable, including Intel Gen7–9.5 ("Intel(R) HD Graphics", Iris 5100–650) and older ATI/AMD up to R9 (`GD/main/main.cpp:2404-2472`, Intel entry at `:2456` [V]; `GD/platform/windows/display_server_windows.cpp:8283-8319`; PR #82364: such devices "still have OpenGL 3.3 listed as supported so automatic fallback won't work"). OpenGPU renders inside Minecraft's context and has no ANGLE escape, and 24 §3.7's only gate is "GLES or GL < 3.3".

- **Change:** `GlCaps` matches a client config list in Godot's format (vendor substring, name substring or `*`) against `GL_VENDOR`/`GL_RENDERER`. A match sets `getCaps().graphics = "host GPU on device list"`; compute is unaffected.
- **Do not import Godot's list** (verifier). `"Intel(R) HD Graphics"` matches every Gen9 iGPU (HD 520/620/630), a large share of GTNH players, and Godot's breakage was in its own, larger GLES3 renderer.
- Ship the matcher with an empty list alongside the M1 kill switch, and populate it only from OpenGPU's own beta and bug reports. The M4 beta should include one Gen9 Intel iGPU and, if obtainable, one GCN 1–3 AMD. ≈ 0.5 d [est.].

**Pixel-store state (G8, ADOPT, M0).** Three sources show this state is foreign and unstable at START:
- Vanilla `ScreenShotHelper` sets `GL_PACK_ALIGNMENT` and `GL_UNPACK_ALIGNMENT` to 1 on every F2 and never restores them (`MC/net/minecraft/util/ScreenShotHelper.java:67-68`) [V].
- Iris resets unpack row length and skips, because otherwise "the uploaded texture data will be quite incorrect" (`ANGSRC/net/coderbot/iris/gl/texture/TextureUploadHelper.java:11-21`) [V].
- Godot unbinds the pack buffer and sets `GL_PACK_ALIGNMENT` before each `glGetTexImage` (`GD/drivers/gles3/storage/texture_storage.cpp:1575-1585`).

23 §2's hygiene assertion already compares pack/unpack alignment, and 21 §3.3 sets unpack row length and alignment for dirty rects. What is missing is *forcing* the whole state. Readback is not alignment-sensitive (rows of 160–1 280 B are multiples of 8, 20 §3), but a foreign `PACK_ROW_LENGTH` or `PACK_SKIP_*` would corrupt it [I: no source shows anyone setting them], and an upload whose rows are not multiples of 4 behaves differently before and after the player's first screenshot.

- **Change:** around every upload, every `glGetTexImage` and the LAN-guest upload, set `PACK_`/`UNPACK_ALIGNMENT = 1`, `ROW_LENGTH = 0` and `SKIP_PIXELS`/`SKIP_ROWS = 0` (plus `SWAP_BYTES`/`LSB_FIRST = false` on compatibility contexts), then restore.
- Add pixel-store rows to 20 §1's table, and non-default pixel-store state to the hygiene test's start state (24 §3.9).
- GLSM caches unpack state only (`ANG/GLStateManager.java:1104-1107`), so saving pack state costs driver round trips on both legs: do it only when a readback is queued. ≈ 0.5 d [est.].

**Constant vertex attributes (V1, ADOPT, M0; found by the verifier).** Angelica 2.2.21 caches constant generic attributes (colour at location 1, normal, UV, lightmap) and re-sends them only when its own dirty flags are set (`ANG/GLStateManager.java:323-346`; colour at location 1, `:690`). `glVertexAttrib4f` passes straight through without updating that cache (`:3135-3137`) [V, re-read by the writer]. An OpenGPU `glVertexAttrib4f` would therefore leak into later Minecraft or Iris draws that lack those streams [I: the effect on Iris draws was not reproduced].

- **Change:** OpenGPU never sets constant generic attributes (S5 in §2.2 shows how missing streams are fed instead), or restores them if it must.
- The hygiene assertion also compares the current generic attribute values on locations 0–7. ≤ 0.5 d [est.].

**A defined texture on every declared sampler (G5, ADOPT; format rule M1, binding rule M2).** Godot binds a typed default texture to every sampler uniform without a value, including an `RGBA8UI` default for integer samplers (`GD/drivers/gles3/storage/material_storage.cpp:1010-1093`; `texture_storage.cpp:198-211`) [V]. 24 §3.7 saves and restores units 0–3 but does not say what a user program's declared-but-unbound sampler reads during the pass. It reads whatever is left on the unit, and inside OpenGPU's pass that is often the *previous device's* texture, possibly another player's display. The verifier pointed out that this is a cross-device leak into pixels Lua can read back, not only nondeterminism. A leftover integer texture under a float sampler is undefined [S: GLSL 3.30 §8.7].

- **Change:** bind a 1×1 transparent-black RGBA8 default (or a cube default) to every unit a program declares and a draw leaves unbound. A validator that rejects such draws would also be deterministic, but is less friendly.
- `R8UI`/`R16UI` surfaces stay OpenGPU-private and are never bindable to user samplers.
- User-visible `index8` textures are normalized `GL_R8`; internal shaders recover the index with `floor(r * 255.0 + 0.5)`. This is decided in M1, when `BLIT` sources gain formats, and is the same rule as R5. ≈ 0.5–1 d [est.].

**Driver threading and GPU power states (G7, CONSIDER; measure in M0, decide in M1).** Godot writes an NvAPI profile that disables NVIDIA "Threaded optimization" to avoid stutter (`GD/platform/windows/gl_manager_windows_native.cpp:105-110, 243-251`). It also documents that GPU timestamps grow when a frame cap lets the GPU downclock (`GD/doc/classes/RenderingServer.xml:4176-4178`) [S]. OpenGPU's START pass polls queries, calls `glGetBufferSubData` and, on the vanilla leg, makes ≈ 12 `glGet*` calls that a threaded driver must serialize [I]; layer 4's EWMA throttle (24 §3.8) reads timestamps. The verifier notes that only the 3 ms throttle is exposed; the 100/500 ms thresholds are far above any DVFS inflation. Two added M0 measurements decide it:

- (a) RTX 5060 with Threaded optimization Auto vs Off for `javaw.exe`, set by the owner in the NVIDIA Control Panel (OpenGPU never writes driver profiles); START-pass p50/p99 and frame-time variance at 1/4/16 displays. If Auto is worse, poll only the oldest query per device per frame and document the setting.
- (b) Intel iGPU and 7600M XT, one T3 scene uncapped vs capped at 30 fps. If capped timestamps exceed ≈ 1.5× the uncapped ones, layer 4 needs hysteresis or a calibration pass.

### 1.5 Frames that need a program still linking (G4, ADOPT, M2)

Godot's Compatibility renderer compiles a missing variant "on the spot" at bind time and reads `GL_COMPILE_STATUS`/`GL_LINK_STATUS` immediately; its "use defaults in the meantime" branch is literally `if (false)` with a TODO (`GD/drivers/gles3/shader_gles3.h:195-206`; `shader_gles3.cpp:311-333, 431-433`) [V, re-read by the writer]. Forward+ draws with an ubershader while the specialized pipeline compiles (PR #90400). OpenGPU can do neither: a stall breaks the render budget, and a substitute would confirm pixels the program never produced. 22 §1.5 defines the asynchronous link but not what a frame using a pending program does.

**Change:**
- (a) A device whose next op needs a program with a pending host link is not runnable. Other devices proceed, and its frames back up into `busy`.
- (b) Link status is first read on the START pass *after* the one that issued the link (`GL_LINK_STATUS`, or `COMPLETION_STATUS_KHR` where present).
- (c) A slow link needs no new timeout: 24 §3.3 already reports any frame unconfirmed after 1 s as `"dropped"`. A permanent link failure, which is an OpenGPU bug (22 §1.5), needs its own rule, because canvas mode keeps dropped frames queued (24 §7 answer 3). Report `opengpu_program(card, h, false, msg)`, skip the draws that use that program and keep the rest of the frame, so canvas content is not lost wholesale (verifier).
- (d) No program-binary disk cache in v1. `glProgramBinary` is not in GLSM's maps, so it fails under `unmappedGL=FAIL/STRICT` (`ANG/redirect/GLSMRedirector.java:74-79, 946-971`). If one is ever added, key it on emitted text plus `GL_VENDOR`, `GL_RENDERER` and `GL_VERSION`, as Godot does (`GD/drivers/gles3/shader_gles3.cpp:133-148`).

≈ 1 d [est.].

### 1.6 Memory, streaming and creation budgets

**GL allocation ledger (G10, ADOPT, M0).** Godot GLES3 routes every buffer, texture and renderbuffer allocation through a named ledger with per-category totals and prints named leaks at teardown (`GD/drivers/gles3/storage/utilities.h:71-73, 85-153`; `utilities.cpp:70-100`). OpenGPU's 256 MB host cap with eviction (24 §3.6), `releaseSession` (21 §2.5), M1.5's "GL object count flat" criterion and `stats()` all need exact host accounting.

- `:gl` gets `GlLedger`. Every `glGen*`, `glTexImage*`, `glBufferData` and `glRenderbufferStorage` goes through it, keyed `(session, deviceId, kind, label)` → bytes and count. Eviction and `stats()` read it.
- `:gl-testkit` asserts that the ledger is empty after `releaseSession`, that per-device totals match 20 §1's formula, and that it agrees with `:core`'s server-side VRAM accounting, which it complements rather than replaces.

1–2 d [est.].

**Creation budget (G13a ADOPT in M1; G13b CONSIDER).** OSG's `IncrementalCompileOperation` drains texture uploads, buffer creation, compiles and deletes from a separate queue, at least 1 ms and at most 8 objects per frame, checked against elapsed time; its per-object time estimates are compiled out (`OSG/include/osgUtil/IncrementalCompileOperation:66-119, 164-169`; `src/osgUtil/IncrementalCompileOperation.cpp:340-370`) [V]. OSG also notes that drivers defer the real transfer to first use. An OpenGPU world load submits `Init(resources, pixels)` for every device and relinks programs (24 §3.6), which is the paged-database case exactly.

- **G13a:** uploads, links and deletes count against `renderBudgetMs`, with a cap of 8 objects per frame [P: OSG's default]. Host deletes of evicted or released devices are spread the same way. ≈ 0.5–1 d [est.].
- **G13b:** slicing `UploadOp`s over 256 KB across START passes, holding the device's draws until done, adds state-machine work. Decide it from an added M0 measurement of upload cost per MB on the three vendors; a 1 024² RGBA8 texture (4 MB) may fit the budget anyway [I].

**Streaming rings (G9, CONSIDER, M2).** Godot's canvas streams per-frame data through 3 buffer sets guarded by fences and allocates a new set rather than wait (`GD/drivers/gles3/rasterizer_canvas_gles3.cpp:118-142, 2783-2806`). RenderingDevice staging grows to a cap and then stalls (`GD/servers/rendering/rendering_device.cpp:1030-1100`). The verifier simplified the researcher's proposal for OpenGPU:

- Index per-device stream slots by the PBO ring slot that 24 §3.7 already limits to ≤ 2 frames in flight. A slot retires when its PBO is fetched; no new fence and no "skip the device" rule are needed.
- At M0's 2D volumes (tens of KB), `glBufferData(…, null)` orphaning is acceptable (GLSM maps the `(int, long, int)` overload, `ANG/GLStateManager.java:6651`).
- Decide in M2 from measurement, including one in-place `updateTexture` of a texture the previous frame sampled, on all three vendors.

**Transient scene targets (G14, CONSIDER, M2).** O3DE distinguishes imported attachments from transient ones, whose "lifetime is only valid for the scopes that use them", and aliases transients through a pool (`O3DE/Gems/Atom/RHI/Code/Include/Atom/RHI/FrameGraphInterface.h:36-38`; `DeviceTransientAttachmentPool.h:56-63`). Godot's `DRAW_IGNORE_*` and `texture_set_discardable` hints serve the same purpose. A frame-mode device whose frame starts with a full colour and depth clear leaves its RGBA8 scene target and D24S8 depth dead after the quantize pass, which the server validator can see at decode time. Devices execute one after another, so one pair per tier could serve all of them, saving ≈ 2 MB of ≈ 5 MB per T3 3D device. Canvas mode and T3 2D blending (RGBA8 persists between presents) are excluded. Trigger: 24 §3.6's 256 MB cap at ≈ 5 MB per device is ≈ 50 T3 3D devices; adopt only if G10's ledger shows realistic counts near the cap or eviction churn.

### 1.7 Crash guard against replaying a GPU hang (G11, ADOPT, M2)

A TDR almost certainly ends the session, because neither leg has a robust context (20 §6). Two precedents:
- Godot attaches breadcrumbs to draw lists so that it can dump the shaders that were running at a GPU crash (`GD/doc/classes/RenderingDevice.xml:287-293`).
- Firefox's `DriverCrashGuard` records a guard before risky driver calls; if the guard survives to the next launch, the feature is disabled (searchfox `gfx/src/DriverCrashGuard.h`).

The researcher expected 24 §3.6's tail replay to re-run the offending frame on every load. The verifier corrected the mechanism: an autosave would have to fall in the ≈ 1.5-frame window between that present and the crash, so the tail rarely holds it. The real loop is that OC persists running Lua machines (and autorun scripts re-run on boot; 09 §3.6, 04 §6), so after a reload the program presents the same hostile frame again. A guard keyed on program hashes catches both cases.

**Change** (a new 24 §3.8 layer):
- Before the first `glUseProgram` of a new program set on a device in a session, append `(world, deviceId, program hashes)` to `opengpu-gpu-guard.txt` and flush. Writes are rare, because the program LRU keeps sets resident.
- Clear the entry when that frame's timestamp query becomes available, and clear the file at clean shutdown.
- At the next launch, surviving entries start those devices suspended (`opengpu_reset(card, "suspended after host crash")` [P]), with a resume button in the OpenGPU GUI.
- Two consecutive unclean exits with live entries disable graphics with the existing reason `"disabled after GPU timeout"`.

M2, because user shaders, the realistic TDR source, arrive there; M1 has only bounded built-in 2D programs. 24 §8 risk 3 should name machine persistence as the loop. 1–2 d [est.].

### 1.8 API evolution (G12, ADOPT, M1)

Godot keeps old signatures callable through shims named after the PR that broke them (`GD/servers/rendering/rendering_device.compat.inc:174-199`). Its CI checks the generated API JSON against the previous stable release and requires a justified entry for every break (`misc/extension_api_validation/`, folders `4.0-stable_4.1-stable` … `4.7-stable`). OpenGPU's first release is M1 and its API freeze is M4.

**Change**, as scoped by the verifier:
- From M1, generate `api-dump.json` (callbacks and argument kinds, signals and arguments, opcodes and record layouts, command-format version, `getCaps` keys, status strings) and diff it in CI against the last release.
- The hard requirement applies to **persisted formats** only. Side files hold confirmed state plus tail op bytes (24 §3.6), so a mod update must decode every earlier command-format version on load, from the first release on.
- Lua-facing callback and signal changes before the freeze need only a justification and a changelog entry. The dump also lints G2.

1–2 d [est.].

### 1.9 What does not transfer (G3, IGNORE)

- **Godot's separate render thread.** `thread_model = Separate` is still experimental in 4.8-dev ("several known bugs which can lead to crashing"), and the editor is forced to the Safe model (`GD/doc/classes/ProjectSettings.xml:2949-2951`; `GD/main/main.cpp:2777-2780, 3543-3547`). GLSM, Angelica and LWJGL 2 assume Minecraft's main thread, so START on the client thread stays.
- **OSG's DrawThreadPerContext and DataVariance.** Overlapping update with draw requires counting `DYNAMIC` objects and an end-of-dynamic-draw block (`OSG/include/osgViewer/ViewerBase:77-86`; `src/osgViewer/ViewerBase.cpp:476-481, 928-932`; `include/osg/Object:224-242`), because update mutates data the draw thread reads. OpenGPU's ops are immutable and owned (21 §3.1), so nothing is shared mutable.
- **O3DE's frame-graph compilation, cross-queue synchronization and pass templates** (`O3DE/Gems/Atom/RHI/Code/Include/Atom/RHI/FrameScheduler.h:62-83`; `O3DE/Gems/Atom/RPI/Code/Include/Atom/RPI.Public/Pass/Pass.h:85-99`). OpenGPU has one GL queue with per-device submission order (21 §2.2), and the Lua program is the pipeline.
- **OSG's lazy state cache** (`OSG/include/osg/State:1200-1215`) is an implementation detail. On the vanilla leg, a shadow cache in OpenGPU's executor is valid only inside one START pass and must be dirtied at entry (20 §1); never keep one across passes.

### 1.10 Smaller notes (researcher only; not separately verified)

- **Colour space (G15, CONSIDER, M2).** Godot GLES3 lights in sRGB and blends additive passes there (`GD/drivers/gles3/shaders/scene.glsl:2463-2465, 2999-3004`; `rasterizer_gles3.cpp:388-391`). 20–24 do not say whether the M2 lit built-ins linearize, or which metric the index8 quantizer uses for "nearest". OpenGPU uses no sRGB formats, so no GL state rule follows; it is a documentation decision for M2.
- **2D as instanced quads (G16, CONSIDER, M0).** Godot's canvas draws items as one static quad plus per-instance data with `glDrawElementsInstanced` (`GD/drivers/gles3/rasterizer_canvas_gles3.cpp:1310-1318, 1490-1497`). For `FILL_RECT`, `BLIT` and glyph runs this cuts host-side vertex building about fourfold [est.]. Decide from M0's render-thread cost per job.

## 2. Shader-language ergonomics

Sources: Godot 65e8d16 and godot-docs 8973ac30e64f, LÖVE 11.5 and 12-dev (b7daef0f), raylib a043255a, bgfx 73183bea, three.js b3cbe43d, the GameMaker manual. Shadertoy facts come from an archived page and Godot's porting table, because shadertoy.com and the LÖVE wiki returned HTTP 403. Picotron's manual (v0.3.0d) confirms that fantasy consoles have no user shader language.

### 2.1 What engines for small programs do

| | Who writes `main` | Varyings | Attribute binding | Transform matrices | Errors |
|---|---|---|---|---|---|
| Godot | engine template | declared once; both sides generated | engine built-ins (`VERTEX`, `UV`, …) | engine | own parser, own wording |
| LÖVE 11/12 | engine prelude | 2 built in plus user; one string for both stages | fixed names, locations 0–2 | engine (`love_UniformsPerDraw[12]`) | vendor regexes (11), glslang (12) |
| Shadertoy | site | none | none | none | driver |
| raylib | user | user | fixed names → fixed locations, −1 if absent | engine-set by name | driver |
| bgfx | user | `varying.def.sc`, declared once | closed semantic set | engine-computed per draw, only if used | shaderc and driver |
| three.js `ShaderMaterial`, GameMaker | user | user | fixed names prepended | engine-set | driver |
| **OpenGPU (22)** | **user** | **user, in both stages** | **declaration order** | **user (Lua)** | **own frontend, `line:col`** |

Evidence: `GD/servers/rendering/shader_types.cpp:91-237`; `GDOC/tutorials/shaders/converting_glsl_to_godot_shaders.rst:23-26` ("If you only choose to write one, Godot will supply the other"); `LOVE11/src/modules/graphics/wrap_GraphicsShader.lua:220-236, 290-299, 317-350`; `LOVE12/src/modules/graphics/Shader.cpp:65-82, 238-262`; `RL/src/rlgl.h:62-82, 332-359`; `RL/src/rcore.c:1258-1292`; `BGFX/examples/01-cubes/varying.def.sc:1-4`; `BGFX/tools/shaderc/shaderc.cpp:197-240, 1876-1928`; `BGFX/src/renderer.h:302-310, 477-489`; `THREE/src/renderers/webgl/WebGLProgram.js:557-585`.

22's plan is the least ergonomic point in this sample. Most of the gain on the Lua side, however, comes from data the engine owns (attribute semantics, transforms), not from shader syntax.

### 2.2 Adopt regardless of the typed-layer decision

**A built-in table drives validation (S2, ADOPT, M2).** Godot validates against one declarative table per shader type and processor function. The table records each built-in's type and writability, whether `discard` is allowed, and stage-only functions. Errors are worded in the language's own terms, such as "Constants cannot be modified." and "Use of 'discard' is not supported for the '%s' shader type." (`GD/servers/rendering/shader_types.cpp:59-66, 111-112, 196-197`; `GD/servers/rendering/shader_language.cpp:5590-5610, 5759, 6495, 9083-9086`). The raw ES 1.00 frontend needs exactly these rules: `gl_FragCoord` and `discard` only in the FS, `gl_Position` only in the VS, read-only built-ins. A `BuiltinInfo{type, writable, stages}` table is therefore the right way to build 22 §1.2's typer. ≈ 0–1 d over the planned frontend [est.].

**Semantic attribute slots (S5, ADOPT with two constraints, M2, protocol).** bgfx (a closed semantic set), raylib (fixed locations, −1 when absent), LÖVE (locations 0–2) and GameMaker (`in_Position`, `in_Colour`, …) bind attributes by name. One mesh and pipeline then work with any shader that reads a subset of their streams. 22 §1.3 binds by declaration order, which ties every `PipelineDesc` to one shader's declaration order.

- **Change:** built-in attributes `og_Position` (vec3), `og_Normal`, `og_TexCoord`, `og_Color` (vec4) and `og_Custom0..3`, at fixed locations POSITION 0, NORMAL 1, TEXCOORD 2, COLOR 3 and CUSTOM0–3 4–7, emitted as `og_i_*` names. A user-declared `attribute` takes a free CUSTOM slot. `PipelineDesc` names streams by semantic.
- **Constraint 1 (verifier):** POSITION is mandatory. Compatibility drivers alias attribute 0, and a disabled array 0 may draw nothing on the vanilla leg (20 §2: "Always feed location 0").
- **Constraint 2 (verifier):** a stream the shader reads but the mesh lacks must **not** be fed with `glVertexAttrib4f` (V1). Use a 1-element VBO whose divisor exceeds any instance count (`glVertexAttribDivisor` is GLSM-mapped, `ANG/redirect/GLSMRedirector.java:542`), or substitute a constant in the emitted program.

This replaces declaration-order binding, so it must land before the M4 freeze. 1–2 d [est.].

**Uniform default values (S7, ADOPT, M2).** Godot accepts `uniform float x = 0.5;` (`GDOC/tutorials/shaders/shader_reference/shading_language.rst:854-870`). GLSL ES 1.00 §4.3 forbids it ("Uniforms, attributes and varyings may not have initializers", `glsles100.txt:1463`), but OpenGPU packs `og_i_U` itself (22 §1.3), so it can allow initializers as a documented departure. The verifier fixed the semantics: defaults are the initial contents of the per-program std140 block, and Lua row writes overwrite them; `programInfo` returns them. Library and example shaders then draw sensibly with no uniform upload. ≈ 0.5 d [est.].

**Documented conventions (S10: ADOPT the documentation in M2; versioning CONSIDER with S1).** Godot 4.3's switch to reverse Z changed what writing `POSITION` means, broke user shaders and needed a dedicated warning (`GD/servers/rendering/shader_warnings.cpp:69-70`; godotengine.org, "Introducing Reverse Z"). Built-in semantics are a compatibility contract. 22 §1.3 marks the `gl_FragCoord` orientation only as [inf.]. M2 must document Y-down, top-left `gl_FragCoord`, the depth range, matrix conventions and `og_TargetSize` (R2) as part of seam 4. A version field on typed shader types belongs with S1. ≈ 0.5 d [est.].

**raylib's ES 1.00 shaders as a frontend corpus (S12: ADOPT the corpus with a licence check in M2; CONSIDER shims in M4).** raylib ships 62 `#version 100` example shaders with Appendix-A-clean loops: no `while`, constant bounds (`RL/examples/shaders/resources/shaders/glsl100/`). They make a ready accept/reject corpus. Julia (≈ 3 000 ops), mandelbrot (20 000 iterations), raymarching and bloom (≈ 25 fetches) exceed OpenGPU's caps; grayscale, posterization, scanlines, pixelizer, palette_switch and sobel fit [est., by inspection].

- **Licence check per file before committing to an MIT repository (verifier).** `ascii.fs` takes its character set from a Shadertoy shader (Shadertoy's default licence is CC BY-NC-SA 3.0), and several files carry their own MIT notices. Exclude Shadertoy-derived files, or fetch the corpus in CI instead of vendoring it.
- Shadertoy and LÖVE alias shims (≈ 30 lines each) stay CONSIDER for the M4 examples. Shadertoy's `fragCoord` origin is bottom-left and OpenGPU's is top-left, so ports come out flipped without a shim.

0.5–1 d [est.].

### 2.3 Owner decisions

**Engine-derived transforms (S6, CONSIDER and recommended; decide at the M2 design review).** bgfx computes `u_modelViewProj` per draw from the view and the draw's model matrix, only for uniforms the program uses (`BGFX/src/renderer.h:302-310, 477-489`); LÖVE packs `love_UniformsPerDraw[12]`; raylib sets `mvp` and `matModel`. In 24 as planned, each Lua draw computes MVP and the normal matrix (≈ 300 float ops) and encodes ≈ 28 floats. The verifier restated why that matters. The float ops are modest (≈ 0.5 ms for 50 draws on 5.3 [est.]). The real costs are that float32 uniforms must be encoded through `math.frexp` on 5.2 and OC-LuaJIT (04 §5), and that 16.16 matrices (09 §3.2) lose precision on projection terms.

- **Proposal:** `SET_CAMERA` (view from eye/target/up or a matrix; projection from fovy/near/far or an orthographic box; built in float on the server), `SET_GLOBALS` (lights, ambient, fog, time), and `DRAW` with a 3×4 model matrix.
- `:core` derives ModelView, MVP and the normal matrix per draw, exposed as `og_` built-in uniforms in three frequency blocks (per pass `og_i_G`, per draw `og_i_D`, material `og_i_U`), counted against the 128 VS / 64 FS rows only when used. M3's GPU-work guard reads the same derived MVP.
- ≈ 3–5 d [est.]. It couples to S1 and S7, so decide them together (§6 Q2).

**Typed shader types (S1 with S3 and S9; CONSIDER, owner, M2 design review).** Godot, LÖVE and Shadertoy let users write processor functions (`vertex()`, `fragment()`, `light()`; `position()`/`effect()`; `mainImage`) and supply `main`, the plumbing and defaults for missing stages. OpenGPU could add `shader_type canvas;` and `shader_type mesh;` in Godot spelling. The verifier downgraded this from ADOPT for two reasons. It is new language scope (+0.5–1.5 w on top of the built-in interface [est.]) beyond decision 5. And it is *additive*: new shader types can ship after v1, so only the protocol pieces (S5, S6) are bound to the freeze.

- **If adopted, compose in IR, not text (S3).** Write the templates in the subset and parse them with the same frontend in a privileged mode; built-ins become stage globals; the template's `main()` calls the processor functions; usage analysis gates template code; emission is deterministic (Godot sorts varyings "to ensure order is deterministic", `GD/servers/rendering/shader_compiler.cpp:700`); the composed program is costed and lowered to SIR like a raw one.
- **Never copy LÖVE's mechanism:** regex detection of entry points and per-vendor regexes that rewrite driver errors (`LOVE11/src/modules/graphics/wrap_GraphicsShader.lua:337-350, 414-447`). Its changelog records repeated fixes (`LOVE12/changes.txt:99, 821, 1037`).
- **`light()` (S9)** inside an engine-owned per-light loop is decided with the `mesh` template: it fits if 4 lights × `light()`, plus the template and a typical `fragment()`, stay within FS ≤ 1 024 ops (Blinn-Phong ≈ 30–40 ops per light [est.]).

**One source per program (S4, CONSIDER, M2).** LÖVE compiles one string twice with the stage name defined, and Godot declares each varying once. OpenGPU's frontend links both stages in `createProgram` and already reports mismatched varying types with `line:col`, so `createProgram(src)` under `VERTEX`/`FRAGMENT` defines is a convenience only. It is cheap in OpenGPU's own preprocessor (≈ 0.5 d [est.]).

**Language level (S13, CONSIDER, low priority).** Godot ("similar to GLSL ES 3.0"), LÖVE 12 (only `glsl3`/`glsl4`) and Shadertoy (WebGL 2) are ES 3.00-class. With `#version 330 core` as the only output, ES 1.00's exclusions of `uint`, `%`, bitwise operators, `switch`, `flat` and `texelFetch` protect no host; the Appendix-A loops and caps, which bound GPU work, could stay. One real hazard remains (verifier): ANGLE's Intel integer workarounds (`rewriteIntegerUnaryMinusOperator`, `emulateAbsIntFunction`; 22 §1.1), and the owner's main iGPU is Intel. Changing this would reopen decision 5, made the same day.

**Typed compute kernels (S14, CONSIDER, M3).** Godot's `particles` type has engine-owned per-particle state (`GD/servers/rendering/shader_types.cpp:376-425`); LÖVE 12's `computemain` shares the graphics prelude. A `kernel grid2d` type whose engine generates the bounds guard and the `y*W+x` index makes D4 true by construction. Trigger: the guard-refined injectivity proof (22 §2.4, verif. 8) misclassifies common kernels in the fuzz corpus (24 §8 risk 10), or users want particle systems.

### 2.4 Recommended M2 design, reconciled with §3

The researchers proposed two different built-in libraries: typed `canvas`/`mesh` templates with a `programSource()` API (S1, S3, S8), and one readable ES 1.00 source with `#define` switches prepended by `opengpu.lua` (R3). This report adopts the verifier's reconciliation:

1. **Raw ES 1.00 stays the only language in v1**, extended by S2's table, S5's semantic attributes, S7's defaults, R1's `noperspective` extension and R2's `og_TargetSize`.
2. **The built-in library is one variant family** (§3.5), stored as Java resources on the server and pulled in by `#include <og/…>`, an extension handled by OpenGPU's own preprocessor (22 §1.2). No shader source travels as Lua strings, which matters on 192 KB machines (decision 7; M0 measures OpenOS free memory), and no `programSource()` API is needed. The sources ship readable in the documentation, so users can copy and edit them [P]. Graphics and compute keep separate libraries, because they are different languages (verifier; S8 had proposed a subset both frontends accept).
3. **Typed shader types are an optional later frontend over the same sources** (§6 Q1).

Examples (illustrative; the extension name, library path and encoder calls are [P]):

```glsl
// A PS1-style textured, vertex-lit, fogged material: the whole user program.
#define TEXTURE
#define LIGHT_VERTEX
#define SNAP
#define AFFINE
#define FOG_VERTEX
#include <og/retro>
```

```glsl
// A custom raw program using only adopted pieces (S5, S7, G5).
// vs
uniform mat4 mvp;                       // computed by Lua unless S6 is accepted
varying vec2 v_uv;
void main() { v_uv = og_TexCoord; gl_Position = mvp * vec4(og_Position, 1.0); }
// fs
uniform sampler2D tex;                  // left unbound: reads a 1x1 transparent-black default (G5)
uniform vec4 tint = vec4(1.0);          // default value (S7)
varying vec2 v_uv;
void main() { gl_FragColor = texture2D(tex, v_uv) * tint; }
```

```lua
local p = assert(gpu.createProgram(VS, FS))
local pipe = gpu.createPipeline{program = p, vertex = {POSITION = "float3", TEXCOORD = "float2"}}  -- S5
-- With S6 accepted, the VS writes og_ModelViewProj * vec4(og_Position, 1.0), and Lua sends
-- enc:camera(eye, target, up, fovy, near, far) once per pass and enc:draw(pipe, mesh, m3x4) per draw.
```

If S1 is accepted later, the same material becomes `shader_type mesh; uniform sampler2D albedo_tex; void fragment() { ALBEDO = texture2D(albedo_tex, UV).rgb; }`, with the variant family supplying the template's defaults.

**Not adopted (S11, IGNORE).** Godot puts blend, cull and depth state (`render_mode`), inspector hints and project-wide uniforms into shader source, because it has no pipeline objects and does have an editor (`GD/servers/rendering/shader_types.cpp:240-268`; `GD/servers/rendering/shader_language.cpp:380-420`). OpenGPU has immutable pipeline descriptions (06 §1) and no inspector.

## 3. Retro techniques and the built-in shader library

Sources: psx-spx (6d7d1bc), libdragon (e356bf3), KallistiOS (84ed47b), Quake and Doom (GPL-2.0, cited for behaviour only; no code may be copied into MIT OpenGPU), five MIT retro shader packs (reference only), the GL 3.3, GLSL 3.30, GLSL ES 1.00 and WGSL texts, and Angelica 2.2.21's GLSM. Saturn and Dreamcast facts come partly from Copetti's write-ups and were not checked against Sega documents. The researcher's scripts (`retro-scripts/`, own code, run with `python -I`) checked the arithmetic marked [script].

### 3.1 What "retro" means technically

"Retro" in the PS1/N64/Saturn/Dreamcast sense is a short list of features the hardware lacked or did cheaply:

| Technique | PS1 | N64 | Saturn | Dreamcast | PC 8-bit |
|---|---|---|---|---|---|
| Integer vertex coordinates (snap) | yes (`PSX/geometrytransformationenginegte.md:417-421`) | sub-pixel | yes | no | varies |
| Affine interpolation | yes (`PSX/graphicsprocessingunitgpu.md:1368-1400`) | no | forward-mapped quads | no | Quake: 16-px perspective steps |
| 15/16-bit framebuffer + ordered dither | 4×4, shaded polygons only (`PSX/…gpu.md:1401-1415`) | square/Bayer/noise (`LD/include/rdpq_mode.h:141-200`) | none | on output (`KOS/hardware/video.c:302-307`) | — |
| Palette (CLUT) textures, palette per draw | 4/8-bit (`PSX/…gpu.md:365-375`) | CI4/CI8 + TLUT | yes | PAL4/PAL8 + selector (`KOS/include/dc/pvr.h:311-334`) | everything indexed |
| Per-vertex lighting | GTE NCDS, 3 lights (`PSX/…gte.md:517-534`) | microcode | CPU | CPU | colormaps |
| Fog | per vertex, linear in 1/z (`PSX/…gte.md:145-162`) | per vertex, in the blender (`LD/include/rdpq_mode.h:609-622`) | — | 128-entry table over 1/w (`KOS/hardware/pvr/pvr_fog.c:55-80`) | colormap rows (`DOOM/r_main.c:620-640`) |
| Depth | ordering tables | Z-buffer | Z-sort | per tile; translucency auto-sorted | span sorting |
| Filtering | none | 3-point "bilinear" | none | bilinear | none |

OpenGPU's tiers already supply the low resolution, the 8-bit indexed and 16-bit 565 output and nearest sampling (20 §3, 24 §3.7). Almost everything else fits a shader library, plus a few format and API additions. The modern packs (dsoft20/psx_retroshader, godot-psx-style-demo, ultimate-retro-shader-collection, godot-psx, URP-PSX) share one shape: compile-time variants with per-vertex light and fog. They also share two mistakes: snapping to a hard-coded grid, and `round()` at exactly .5, which GLSL 3.30 §8.3 leaves to the implementation and ES 1.00 does not have.

### 3.2 Language and format additions

**Affine mapping as `noperspective` (R1, ADOPT, M2).** PS1 hardware interpolates "only linear" in screen space (`PSX/graphicsprocessingunitgpu.md:1368-1376`). GLSL 3.30 §4.3.9 has `noperspective`, and WGSL §12.9 calls it `linear`. Godot lacks the qualifier, so its packs fake it:
- forcing `w = 1` makes every varying affine and breaks near-plane clipping (`SH/godot-psx-style-demo/shaders/psx_base.gdshaderinc:54-55`);
- multiplying by w costs an extra varying and a divide per fragment (`SH/ultimate-retro-shader-collection/shaders/ursc/spatial/common.gdshaderinc:254-258, 295-297`).

OpenGPU emits `330 core`, which GLSM leaves untouched, so the keyword can pass straight through. **Change:** accept `noperspective` on varyings behind an OpenGPU `#extension` directive; SIR carries an interpolation flag; seam 4's rasterization rules add screen-linear interpolation. The extension gate is required, not optional: ES 1.00 reserves `flat` but not `noperspective`, so without the gate it is a legal user identifier (`glsles100.txt:847`; verifier). 1–2 d [est.].

**Snapping to the real target (R2, ADOPT, M2).** PS1 vertices are integer screen coordinates (`PSX/geometrytransformationenginegte.md:417-421`; `PSX/graphicsprocessingunitgpu.md:324-331`). The packs snap to fixed 160×120 or 320×240 grids (`SH/psx_retroshader/Assets/Shaders/psx-vertexlit.shader:35-40`; `SH/godot-psx-style-demo/shaders/psx_base.gdshaderinc:22-33`), which is wrong whenever the target differs. **Change:** a built-in uniform `og_TargetSize`, modelled on ES 1.00's built-in uniform `gl_DepthRange` (§7.5), using one reserved row per stage only when referenced and written per pass by the host; plus a library function:

```glsl
vec4 og_snap(vec4 p) {                         // library (R2); p in clip space
  if (p.w <= 0.0) return p;                    // behind the eye: leave it to clipping (verifier)
  vec2 h = og_TargetSize * 0.5;
  vec2 ndc = floor(p.xy / p.w * h + 0.5) / h;  // ES 1.00 has no round(); this defines .5
  return vec4(ndc * p.w, p.z, p.w);
}
```

All tier sizes are even, so the grid lands on pixel corners, and 22 §1.3's y negation preserves it [I]. The snap is part of the `gl_Position` slice that M3's GPU-work guard runs (`floor` exists in SIR). `og_TargetSize` also serves pixel-size sprites and texel math. If S6 is accepted, it is the same row as S6's proposed `og_Resolution`; keep one name. ≤ 1 d [est.].

**`index8` textures and palette textures (R5, ADOPT; format rule M1, library M2).** PS1 (4/8-bit CLUT, palette chosen per polygon, colour `0000h` transparent; `PSX/graphicsprocessingunitgpu.md:1146-1197`), N64 (CI4/CI8 + TLUT; `LD/include/surface.h:111-112`) and Dreamcast (PAL4/PAL8 with a per-polygon selector, filtered after lookup; `KOS/include/dc/pvr/pvr_pal.h:59-70`) all draw palette textures. **Change:** a texture format `index8`, stored as normalized `GL_R8`, counted as 1 B/texel in VRAM accounting, with `GL_NEAREST` forced and unpack alignment forced per G8. Palettes are ordinary RGBA8 textures of 256 × N rows. The lookup is exact, because unorm→float is `c/255` (GL 3.3 core §2.1.5 eq. 2.1) and palette fetches at texel centres are exact (23 §3.1):

```glsl
float i = floor(texture2D(idx, uv).r * 255.0 + 0.5);
vec4 c = texture2D(pal, vec2((i + 0.5) / 256.0, (row + 0.5) / rows));  // row: per-draw uniform
if (c.a == 0.0) discard;                                               // CUTOUT, like PS1 0000h
```

This is the same format rule as G5 and should be written once in 24 §3.7: user-visible index textures are `GL_R8`, and `R8UI`/`R16UI` surfaces are private. Default sampling is nearest, with linear opt-in per binding; filtered CLUT (4 index plus 4 palette fetches) is an opt-in variant. 1–2 d [est.].

**PS1 blend modes (R9, ADOPT, M2).** PS1 semi-transparency is B/2+F/2, B+F, B−F or B+F/4, saturating (`PSX/graphicsprocessingunitgpu.md:1425-1446`). 06 §2 took SDL's set, which lacks subtraction and the constant-factor modes. All four map onto GL 3.3 blending on the RGBA8 scene target (`GL_FUNC_REVERSE_SUBTRACT`; `glBlendColor` with 0.5 or 0.25). GLSM maps both calls and saves them under `GL_COLOR_BUFFER_BIT` (`ANG/GLStateManager.java:1377, 1618`; `ANG/Feature.java:81-85`; `ANG/states/BlendState.java:10-20`) [V], a bit 24 §3.7 already pushes. **Change:** add `sub`, `avg` and `addq` to the pipeline description. Integer surfaces ignore blending (GL 3.3 §4.1.7), so these modes apply to the scene target only. ≤ 1 d [est.].

**Per-instance attributes (R11, ADOPT, M2).** Camera-facing sprites (Doom-style Y-locked or spherical) are quads expanded in the vertex shader from corner offsets and drawn with cutout or stipple, so they need no sorting. Batching them needs a per-instance step in the vertex layout. `glVertexAttribDivisor` is GLSM-mapped (`ANG/redirect/GLSMRedirector.java:542`; `ANG/GLStateManager.java:3187`) [V]. **Change:** confirm the step mode in M2's `PipelineDesc`; the `SPRITE` variant is library code (≈ 30 VS ops [est.]). ≈ 1 d [est.].

**Provoking vertex (R10a ADOPT now; R10b CONSIDER, M2).** `glProvokingVertex` is not in GLSM 2.2.21's redirect maps (the GL32 map holds three names, `ANG/redirect/GLSMRedirector.java:531-534`), so it is reported as unmapped and throws under `unmappedGL=FAIL/STRICT` (`:74-79, 111-117`), which M0 and M4 run. GLSM also sets the mode itself for line-stipple emulation (`ANG/GLStateManager.java:711, 5750`) [V].
- **R10a:** add "never call `glProvokingVertex`, and never depend on its state" to 24 §3.7.
- **R10b:** offering `flat` varyings is a later decision. If offered, they need WGSL's `either` semantics: the value must be equal on all vertices of a primitive. Default: not in M2; the `FLAT` variant duplicates vertices.

### 3.3 Palette changes as ordered, persisted, streamed ops (R7, ADOPT, M1)

The verifier rated this the most consequential finding. Quake implements damage, powerup and liquid tints as palette shifts (`Q1/view.c:523-611`). On index8 tiers OpenGPU gets the same effect for free for any content stored as indices, because expand computes `palette[index]` on the host GPU and on LAN guests' CPUs (20 §3; 24 §3.2). But 24 leaves palette changes unspecified:

- `setPalette` is not an ordered tail op;
- the confirmed state and the side files (24 §3.6) do not include the palette;
- the LAN stream (21 §4) has no palette record.

A palette-only frame produces zero dirty tiles, so **LAN guests would keep showing old colours**, and a reload could pair confirmed pixels with the wrong palette. 09 handled this with "keyframes … after resolution/palette changes" (09 §3.4); 24 dropped that rule without replacing it.

**Change:**
- `setPalette` is an ordered op in the device tail, and a present preceded only by palette ops runs the expand pass only.
- The palette is part of confirmed state and of the side file.
- Keyframes carry the palette, and a 768 B palette record is streamed to guests on each change, cheaper than 09's keyframe.
- Document that `readPixels` returns indices and is unaffected by palette changes.
- M4's two-client test adds palette-only frames to the guest-hash check.

2–3 d [est.].

### 3.4 Quantize and dither (R8, ADOPT; T3 rule in M1, index8 3D in M2)

24 answer 6 fixes one 565 quantize per present, and 20 §3 says T3 "rounds to 565", but no dither rule survives from 08/09. 23's "dither off" means `GL_DITHER`, a different thing. Facts [script; the verifier re-ran the arithmetic]:

- Round-to-nearest, `q = (c·m + 127) / 255` with integer division and m = 31 or 63, round-trips every 5- and 6-bit level under bit-replication expansion.
- PS1's offset-then-truncate rule, `q = clamp(c + d, 0, 255) >> 3`, changes 26 of 32 representable 5-bit values under some offset. A PS1-style dither at present would therefore alter exact 2D colours and break 23's E tier. The PS1 table itself is the 4×4 Bayer matrix halved (`PS1 + 4 == floor(Bayer4/2)`; `PSX/graphicsprocessingunitgpu.md:1401-1415`).
- Ordered dither between the bracketing levels `lo ≤ c < hi`, choosing `hi` iff `(c − exp(lo)) / (exp(hi) − exp(lo)) > (k + 0.5)/n²` for Bayer index k, keeps every representable colour fixed. Its mean error over a 4×4 tile is ≤ 0.25 (R, B) and ≤ 0.125 (G) in 8-bit units.

**Change:**
- 24 §3.7 states the quantize functions: 565 round-to-nearest; bracketing ordered dither; index8 exact nearest palette entry, ties to the lowest index.
- `setDither(mode[, matrix])` per device: `"none"` (default), `"bayer4"`, `"bayer8"`, or `"custom"` with 16 or 64 threshold bytes. No noise mode: it changes static images every frame and defeats LAN tile diffs [I].
- PS1-exact dither belongs in the library, per draw (`DITHER_PS1`), which is also how the PS1 applied it (shaded polygons only, never rectangles).
- For index8, the verifier notes that 2D ops write R8UI directly (20 §3), so exactness matters for 3D only, which is tolerance-tested anyway. An exact quantizer is still preferable to the 32³ LUT that 20 §3 allows: for one plausible cube-plus-greys palette the LUT mis-maps 14 of 256 exact palette colours, black to a grey included [script; the count is illustrative, because 08-C never fixed the grey levels]. Use brute force over 256 entries (≈ 16 M distance evaluations per T2 frame, ≈ 0.1–0.5 ms on the GPU [est.]) or the LUT plus a verify step.

≈ 2 d in M1 + 1 d in M2 [est.].

### 3.5 The built-in library as one variant family (R3, R4, R13; ADOPT, M2)

24 §6 M2 lists four built-ins: unlit, textured, Gouraud and Blinn-Phong. Retro parity needs feature switches, and they should be **preprocessor variants of one ES 1.00 source per stage**, not uniform branches. 22 §1.2's static costing sums every branch against FS ≤ 1 024 ops and 16 fetches and feeds the GPU-work guard, so a uniform-switched uber-shader is costed for every feature at once, while each variant is costed exactly. The packs are organized the same way (`SH/godot-psx-style-demo/shaders/psx_base.gdshaderinc:6-20`).

| Define | Effect | Cost [est.] | Source |
|---|---|---|---|
| `TEXTURE` / `CLUT` / `CLUT_FILTERED` | nearest sample / index plus palette row (R5) / filtered after lookup | 1 / 2 / 8 fetches | all / PS1, N64, DC |
| `VCOLOR` (`MODULATE2X`) | vertex colour; 0x80 is neutral, as in PS1 `texel × vcol / 128` | 1–3 ops | `PSX/…gpu.md:1447-1460` |
| `LIGHT_VERTEX` | ≤ 3 directional lights + ambient + light-colour matrix in the VS (GTE NCDS form); the "Gouraud" entry | VS ≈ 40, FS 0 | `PSX/…gte.md:517-534` |
| `LIGHT_PIXEL` | Blinn-Phong (the existing entry) | FS ≈ 40 incl. `pow` | — |
| `FLAT` | face-constant lighting from duplicated vertices (R10b) | 0 | PS1 IIP bit |
| `SNAP`, `AFFINE` | R2, R1 | VS ≈ 8; 0 | PS1 |
| `FOG_VERTEX` / `FOG_PIXEL` / `FOG_TABLE` | linear per vertex / per pixel / 256×1 LUT over normalized depth (any curve, `exp` precomputed in Lua) | 1 mix / ≈ 4 ops / 1 fetch and 1 sampler | PS1, N64 / DC table fog (`KOS/hardware/pvr/pvr_fog.c:55-80`) |
| `CUTOUT` | discard at alpha 0 | 1 | PS1 `0000h` |
| `STIPPLE` | discard if alpha ≤ bayer4/16: screen-door transparency without sorting | ≈ 16 | Saturn "mesh" |
| `DITHER_PS1` | offset-then-truncate per draw (R8) | ≈ 20 | `PSX/…gpu.md:1401-1415` |
| `SPRITE` | VS-expanded quad, spherical or Y-locked, world or pixel size (R11) | VS ≈ 30 | Doom, PS1 |
| `og_filter3pt`, `og_atlasWrap` (R13, CONSIDER) | N64 3-texel filter; PS1 texture-window repeat inside an atlas rectangle | 3–4 fetches; ≈ 4 ops | N64 manual §12.5.1; `PSX/…gpu.md:405-417` |

ES 1.00 shapes the library. It has no `round()` and no constant arrays, and the FS may index only with constant expressions, so a Bayer table is either arithmetic (an index-free formula of ≈ 15 ops, checked against the reference matrix [script]) or a 4×4 `GL_NEAREST` texture costing one sampler and one fetch. It has no `textureSize`, so texel math takes a size uniform.

- **Delivery** (verifier): the sources are server-side resources, included with `#include <og/retro>` after the user's `#define`s (§2.4), not Lua strings prepended by `opengpu.lua`.
- **Variant counts:** each `#define` set is a separate program against 22 §1.5's limit of 4 compiles per second per card and its 256-program LRU, so the documentation lists the variant count of the shipped examples.
- **Tests:** fog without `exp()` (vertex, linear per pixel, or table) can move its golden tests from tolerance tier T to tier S (23 §3) [I].
- **Cost:** the family replaces the four planned built-ins, ≈ +2–4 d over them [est.]. Breadth can be phased: `TEXTURE`, `CLUT`, `VCOLOR`, both light models, fog, `CUTOUT`, `SNAP` and `AFFINE` in M2, the rest later, with no API impact.

### 3.6 Index-output 3D on T1/T2 (R6, CONSIDER, owner)

On index8 tiers 3D renders in RGB and is then quantized to the nearest palette entry, which loses the index: 3D cannot follow palette animation, and quantization can be ambiguous. Quake and Doom lit and fogged *indices* through colormap tables (Quake: 256 × 64 grades plus fullbrights, `Q1/vid.h:22-23, 37-39`, `Q1/r_surf.c:369-372`; Doom: distance colormaps, `DOOM/r_main.c:620-640`). A pipeline output mode `index` would write `floor(gl_FragColor.r·255 + 0.5)` straight into an R8UI + depth target. That gives exact indices, colormap lighting and fog through a 256×N texture, and palette-cycled 3D.

- Integer targets ignore blending (GL 3.3 §4.1.7), so transparency must be cutout or stipple.
- `rgb` passes must quantize into the surface at pass end before `index` passes compose.
- ≈ 3–5 d [est.]. Test: palette-cycled water in a T2 3D scene changes on screen with no re-render.

Decide at the M2 design review, by whether palette-animated or colormap-lit 3D is part of the owner's "retro" (§6 Q3).

### 3.7 Not adopted (R12, R14, IGNORE)

- **Ordering tables, Saturn Z-sort, Dreamcast auto-sorted translucency.** OpenGPU has `DEPTH24_STENCIL8` (20 §1). Translucent order is Lua's job, since command order is execution order, and users who want PS1 sorting glitches can disable the depth test. Order-independent transparency needs per-pixel lists (GL 4.2), and w-buffers or reverse Z need `glClipControl` (GL 4.5), both above the floor.
- **Saturn forward-mapped quads** cannot be reproduced cheaply with triangles.
- **N64 VI de-dither and CRT/composite filters at expand.** The displayed picture must be a pure function of the read-back bytes (20 §3), so such filters belong in user post-process passes.
- **Dreamcast modifier volumes** are not a defining retro feature.
- **PS1 per-texel STP blending:** GL cannot vary the blend equation per texel in one pass.
- **Noise dither** (§3.4).

## 4. Consolidated lessons

Verdicts are after the verifier's corrections. Cost is added solo-developer effort [est.]; "text" means a rule in 24 only.

| ID | Lesson | Verdict | 24 section | Milestone | Cost |
|---|---|---|---|---|---|
| G1 | Type bits in handles; typed `free` and errors | ADOPT | §2.1 boundary, §3.1 `:core` | M0 | ≤ 0.5 d |
| G2 | Written invariants: no host-synchronous call, no barrier, compute reaches graphics via the tail | ADOPT | §3.3 | M0 text, M4 lint | text |
| G3 | Separate GL thread, OSG threading and DataVariance, O3DE frame graph and pass templates | IGNORE | — | — | — |
| G4 | Device waits for a pending link; status read next pass; link failure skips its draws; no binary cache | ADOPT | §3.4, 22 §1.5 | M2 | ≈ 1 d |
| G5 | Default texture on every declared-but-unbound sampler; integer surfaces private; user index textures `GL_R8` | ADOPT | §3.7, §3.4 | M1 format, M2 | 0.5–1 d |
| G6 | Renderer device list (matcher, empty list) | ADOPT | §3.7, §3.3 | M1; data from the M4 beta | ≈ 0.5 d |
| G7 | Threaded-driver and DVFS effects on sync points and timers | CONSIDER | §3.8 layer 4, §6 M0 | measure M0, decide M1 | measurement |
| G8 | Force pixel-store state around uploads and readbacks | ADOPT | §3.7, 20 §1, §3.9 | M0 | ≈ 0.5 d |
| G9 | Streaming slots indexed by the PBO ring slot; orphaning in M0 | CONSIDER | §3.7 | M2 | — |
| G10 | `GlLedger` for every GL allocation | ADOPT | §3.1 `:gl`, §3.6 | M0 | 1–2 d |
| G11 | Crash guard keyed on program hashes | ADOPT | §3.8, §3.6 Load, §8 risk 3 | M2 | 1–2 d |
| G12 | API dump with CI diff; versioned decoders for persisted formats | ADOPT | §3.1 `api`, §3.6, §6 | M1 | 1–2 d |
| G13a | Creation budget: uploads, links, deletes within `renderBudgetMs`, ≤ 8 objects per frame | ADOPT | §3.5, §3.6 Load | M1 | 0.5–1 d |
| G13b | Slice `UploadOp`s over 256 KB across passes | CONSIDER | §3.5 | measure M0 | — |
| G14 | Pool transient scene targets per tier | CONSIDER | §3.6 GPU memory | M2 | — |
| G15 | State the lit built-ins' colour space and the quantizer's metric | CONSIDER | §3.4, §3.7 | M2 | text |
| G16 | 2D ops as instanced quads | CONSIDER | §3.7 | M0 measurement | — |
| V1 | Never set constant generic attributes (GLSM cache leak); hygiene checks locations 0–7 | ADOPT | §3.7, §3.9 | M0 | ≤ 0.5 d |
| S1 | Typed `canvas`/`mesh` shader types | CONSIDER | §3.3, §3.4 | owner, M2 review; additive | 0.5–1.5 w |
| S2 | A declarative built-in table drives validation and messages | ADOPT | §3.4 `:lang` | M2 | 0–1 d |
| S3 | If S1: compose in IR, never by regex or text prelude | CONSIDER | §3.4 | with S1 | in S1 |
| S4 | One source per program under `VERTEX`/`FRAGMENT` | CONSIDER | §3.3 | M2 | ≈ 0.5 d |
| S5 | Semantic attribute slots; POSITION mandatory; no `glVertexAttrib4f` for missing streams | ADOPT | §3.4, §3.3 `PipelineDesc` | M2 (protocol) | 1–2 d |
| S6 | Engine-derived transforms: `SET_CAMERA`, `SET_GLOBALS`, model per `DRAW`, `og_` blocks | CONSIDER (recommended) | §3.3, §3.4, §3.8 | owner, M2 review | 3–5 d |
| S7 | Uniform default values | ADOPT | §3.4 | M2 | ≈ 0.5 d |
| S8 | Library as server-side `#include <og/…>` resources; no `programSource`; separate compute library | ADOPT | §3.3, §3.4, §6 M2 | M2 | in R3 |
| S9 | Optional `light()` inside an engine light loop | CONSIDER | §3.4 | with S1 | — |
| S10 | Document coordinate, depth and matrix conventions; version typed contracts | ADOPT (docs) / CONSIDER (versioning) | §3.4, seam 4 | M2; freeze at M4 | ≈ 0.5 d |
| S11 | Pipeline state, editor hints and global uniforms in shader source | IGNORE | — | — | — |
| S12 | raylib `glsl100` corpus, licence-checked per file; Shadertoy/LÖVE shims | ADOPT (corpus) / CONSIDER (shims) | §3.9 | M2; shims M4 | 0.5–1 d |
| S13 | ES 3.00-syntax subset with the same loop rules and caps | CONSIDER | §1 decision 5, §3.4 | owner, before the M2 frontend | — |
| S14 | Typed compute kernels (`grid2d`, `particles`) | CONSIDER | §3.4 compute | M3 | — |
| R1 | `noperspective` behind `#extension` | ADOPT | §3.4, seam 4 | M2 | 1–2 d |
| R2 | `og_TargetSize` and target-grid snapping with a w ≤ 0 guard | ADOPT | §3.4 | M2 | ≤ 1 d |
| R3 | Built-ins as one `#define` variant family; variant counts documented | ADOPT | §6 M2 | M2 | +2–4 d |
| R4 | Fog per vertex, per pixel or by table; non-`exp` fog in tier S | ADOPT | §6 M2, §3.9 | M2 | in R3 |
| R5 | `index8` textures with palette rows and cutout | ADOPT | §3.7, §6 M2 | M1 format, M2 | 1–2 d |
| R6 | Index-output 3D for T1/T2 | CONSIDER | §3.7, §6 | owner, M2 review | 3–5 d |
| R7 | Palette as an ordered, persisted, streamed op | ADOPT | §3.3, §3.6, §4 | M1 | 2–3 d |
| R8 | Idempotent quantize; selectable ordered dither; exact index8 quantizer | ADOPT | §3.3, §3.7 | M1 (T3), M2 (index8) | 2 d + 1 d |
| R9 | PS1 blend modes `sub`, `avg`, `addq` | ADOPT | §3.3 `PipelineDesc` | M2 | ≤ 1 d |
| R10a | Never call `glProvokingVertex` | ADOPT | §3.7 | now (text) | text |
| R10b | `flat` varyings with "either" semantics | CONSIDER | §3.4 | M2 | — |
| R11 | Sprites as a VS variant; per-instance step | ADOPT | §3.3, §6 M2 | M2 | ≈ 1 d |
| R12 | Ordering tables, OIT, w-buffers | IGNORE | — | — | — |
| R13 | N64 3-point filter and atlas wrap as library functions | CONSIDER | §6 M2 library | M2 or later | — |
| R14 | Saturn quads, scan-out filters, per-texel STP blending | IGNORE | — | — | — |

**Effort [est.].** The ADOPT items add ≈ 3–4.5 d to M0 (including G7's measurements), ≈ 6–9 d to M1 and ≈ 12–21 d to M2, about +4–7 w on 24–33 w. Roughly half (≈ 2–3.5 w) is correctness and safety: G1, G4–G6, G8, G10–G12, G13a, R7, R8 and V1. The rest is retro and language breadth (R1–R5, R9, R11, S2, S5, S7, S10, S12), which can be phased within M2 or into an M2.x without API changes beyond S5 and the format, blend and step additions. CONSIDER items, if all were accepted: S1 with S3 and S9 ≈ 0.5–1.5 w, S6 ≈ 3–5 d, R6 ≈ 3–5 d.

## 5. Proposed edits to 24 (not applied)

Listed by 24 section, with the lessons that motivate them. Consequential edits to 20 and 22 are noted where they arise.

- **§1 Decisions:** none, unless the owner answers §6 Q4 against the default (decision 5).
- **§2.1 boundary row:** handles are `type(4) | slot(11) | generation(16)`, below 2^31, with type 0 reserved (G1).
- **§3.1 Modules:**
  - `:core` decodes handle types (G1).
  - `:gl` adds `GlLedger` (G10).
  - The root `api` package generates `api-dump.json`, diffed in CI from M1, and the command-format version dispatch for persisted tails (G12).
  - The built-in library ships as resources resolved by `:lang`'s preprocessor for `#include <og/…>` (S8, R3).
- **§3.3 Lua API deltas:**
  - New "API invariants" bullet (G2).
  - `getCaps().graphics` gains `"host GPU on device list"` (G6).
  - `setPalette` is an ordered op; a palette-only present runs expand only; `readPixels` returns indices, unaffected by the palette (R7).
  - `setDither(mode[, matrix])`, default `"none"` (R8).
  - `opengpu_reset` gains `"suspended after host crash"` (G11).
  - `PipelineDesc`: streams named by semantic with POSITION mandatory (S5); a per-instance step (R11); blend modes `sub`, `avg` and `addq` (R9).
  - Shaders bullet: the `#include <og/…>` library; uniform initializers, returned by `programInfo` (S7, S8).
  - If S6 is accepted: `SET_CAMERA`, `SET_GLOBALS` and a 3×4 model per `DRAW`.
- **§3.4 Shader and compute languages:**
  - Graphics paragraph: the built-in table (S2); semantic attribute locations 0–7 replace declaration order, which also edits 22 §1.3 (S5); uniform initializers (S7); `noperspective` behind `#extension` (R1); the built-in `og_TargetSize` (R2); `og_` built-ins emitted as `og_i_*`; documented conventions (S10).
  - Pending-link semantics, mirrored in 22 §1.5 "GL side" (G4).
  - Graphics and compute keep separate include libraries (S8).
- **§3.5 Threading, client-thread row:** a creation budget within `renderBudgetMs`, at most 8 objects per frame, with deletes spread the same way (G13a).
- **§3.6 Resources and persistence:**
  - Confirmed state and the side file include the palette (R7).
  - Persisted tails carry the command-format version and are decoded by version on load (G12).
  - Load checks the crash guard before replay (G11).
  - GPU memory: eviction and `stats()` read `GlLedger` (G10).
- **§3.7 Rendering legs:**
  - "The only branch is GLES or GL < 3.3" gains the device list (G6).
  - Common rules add: pixel-store forcing (G8; also rows in 20 §1's table); a default texture on every declared-but-unbound sampler, integer surfaces never bindable by users, and user index textures as `GL_R8` (G5, R5); never set constant generic attributes (V1); never call `glProvokingVertex` (R10a); the quantize functions, namely 565 round-to-nearest, bracketing ordered dither, and index8 exact nearest with lowest-index ties (R8).
  - Replace 20 §3's option "a 32³ LUT rebuilt on palette change" with "brute force, or LUT plus verify" (R8).
- **§3.8 GPU safety:** a new layer between 4 and 5, the crash guard, from M2 (G11).
- **§3.9 Testing:**
  - The hygiene start state adds non-default pixel-store state and generic attribute values on locations 0–7 (G8, V1).
  - `:gl-testkit` adds the ledger assertions (G10).
  - The raylib `glsl100` corpus joins the frontend corpus, with a per-file licence check (S12).
  - Fog golden tests without `exp()` move to tier S (R4).
- **§4 LAN:** keyframes carry the palette, and a 768 B palette record is sent per palette change (R7).
- **§5 seam 4:** the rasterization rules add screen-linear (`noperspective`) interpolation and the documented `gl_FragCoord` and depth conventions (R1, S10).
- **§6 Phased plan:**
  - **M0** scope adds G1, G8, G10, V1 and G2's text. Measurements add G7 (a) and (b), upload cost per MB (G13b), and instanced vs expanded 2D quads (G16).
  - **M1** scope adds R7, R8 (T3 rule and `setDither`), G12 (dump, CI diff, versioned decoders), G6's matcher, G13a, and the index-texture format rule (G5, R5). The done list adds "a palette-only frame re-expands without re-rendering, reaches a test guest stream and survives save/load".
  - **M2:** "built-in shaders written in the subset (unlit, textured, Gouraud, Blinn-Phong)" becomes "the built-in variant family via `#include <og/…>`". Scope adds G4, G5, G11, S2, S5, S7, S10, S12, R1, R2, R5, R8 (index8), R9 and R11; textures add `index8`. The done list adds "the hostile-shader corpus includes unbound samplers, and no draw reads a foreign texture" and "a PS1-style example (snap, affine, vertex light, fog, CLUT) at 20 fps on T2".
  - **M4** done list: the guest hash equals the host's after palette-only frames (R7); the beta includes a Gen9 Intel iGPU (G6).
  - **Effort:** M0 4–6 w → 4.5–7 w; M1 4–5 w → 5–7 w; M2 5–7 w → 7.5–11 w; total 24–33 w → ≈ 28–40 w [est.].
- **§7 Questions:** add §6 of this report.
- **§8 Risks:** risk 3 names OC machine persistence as the crash-loop vector, and its mitigation adds the crash guard (G11); risk 6's mitigation adds pixel-store forcing and the generic-attribute rule (G8, V1).
- **§9 Index:** add this report.

## 6. Questions for the owner

1. **Typed shader types.** The M2 built-ins become one ES 1.00 variant family pulled in by `#include <og/…>` (R3, S8). Should typed `canvas`/`mesh` shader types, with `vertex()`/`fragment()`/`light()` in Godot spelling, come as well, and when? *Default: the variant family in M2; typed shader types not in v1, revisited at the M2 design review. They are additive and can ship after the freeze.*
2. **Engine-derived transforms (S6).** Should the command stream carry a camera per pass and a 3×4 model per draw, with MVP and the normal matrix derived on the server and exposed as `og_` built-ins? *Default: yes, in M2, settled at the M2 design review together with Q1, because it is protocol.*
3. **Index-output 3D on T1/T2 (R6).** Doom/Quake-style colormap lighting and palette-cycled 3D, ≈ 3–5 d. *Default: decide at the M2 design review; palette ops for 2D (R7) ship in M1 either way.*
4. **Graphics language level (S13).** Stay with ES 1.00, or accept an ES 3.00-syntax subset (adding `uint`, `%`, bitwise operators, `switch`, `flat`, `texelFetch`) with the same Appendix-A loops and caps? This reopens decision 5. *Default: keep ES 1.00.* *Answered 2026-10-09: ES 3.00-based subset; specified in 27.*
5. **Default present dither (R8).** *Default: `"none"`, with sample programs opting in. Idempotence keeps 2D exact either way, and dithered 3D compresses worse for LAN guests [I].*

## Sources

Local clones, read only and never built or executed. Scratchpad = `C:\Users\astro\AppData\Local\Temp\claude\C--Users-astro-Downloads-OpenGPU\170a14cc-5cc1-40e0-a7ec-6130b748eabf\scratchpad`.

- `GD/` = godotengine/godot master 65e8d16 (2026-10-08, 4.8-dev); sparse clones of the same commit at scratchpad `godot/` and `el-src/godot/`. Files: `core/templates/{rid.h, rid_owner.h, command_queue_mt.h}`, `core/config/engine.{h,cpp}`, `servers/server_wrap_mt_common.h`, `servers/rendering/{rendering_server_default.{h,cpp}, rendering_device.{h,cpp}, rendering_device.compat.inc, rendering_device_graph.h, shader_types.cpp, shader_compiler.{h,cpp}, shader_language.{h,cpp}, shader_warnings.cpp, shader_include_db.cpp}`, `main/main.cpp`, `platform/windows/{display_server_windows.cpp, gl_manager_windows_native.cpp}`, `drivers/gles3/{rasterizer_gles3.cpp, rasterizer_canvas_gles3.cpp, rasterizer_scene_gles3.cpp, shader_gles3.{h,cpp}, storage/{config, texture_storage, material_storage, utilities}.{h,cpp}, shaders/{scene,canvas}.glsl}`, `scene/resources/{canvas_item_material.cpp, material.cpp}`, `doc/classes/{RenderingServer, RenderingDevice, ProjectSettings}.xml`.
- `GDOC/` = godot-docs 8973ac30e64f, scratchpad `el-src/godot-docs/`: `tutorials/shaders/converting_glsl_to_godot_shaders.rst`, `shader_reference/{shading_language, spatial_shader, canvas_item_shader, shader_preprocessor}.rst`, `compute_shaders.rst`.
- `OSG/` = openscenegraph/OpenSceneGraph master 2e4ae2e (2022-12-01), scratchpad `osg/`: `include/osg/{State, Object}`, `include/osgViewer/ViewerBase`, `src/osgViewer/{ViewerBase.cpp, Renderer.cpp}`, `include/osgUtil/IncrementalCompileOperation`, `src/osgUtil/IncrementalCompileOperation.cpp`.
- `O3DE/` = o3de/o3de development e031590 (2026-10-08), scratchpad `o3de/`: `Gems/Atom/RHI/Code/Include/Atom/RHI/{FrameScheduler.h, FrameGraphInterface.h, FrameGraphAttachmentInterface.h, DeviceTransientAttachmentPool.h}`, `Gems/Atom/RPI/Code/Include/Atom/RPI.Public/Pass/{Pass.h, AttachmentReadback.h}`.
- `LOVE11/` = love2d/love tag 11.5 (6eb8d546) and `LOVE12/` = love main b7daef0f (2026-09-20), scratchpad `el-src/love-11.5`, `el-src/love-main`: `src/modules/graphics/wrap_GraphicsShader.lua`, `src/modules/graphics/Shader.{h,cpp}`, `changes.txt`.
- `RL/` = raysan5/raylib a043255a: `src/rlgl.h`, `src/rcore.c`, `src/raylib.h`, `examples/shaders/resources/shaders/glsl100/*`. `BGFX/` = bkaradzic/bgfx 73183bea: `examples/01-cubes/varying.def.sc`, `tools/shaderc/shaderc.cpp`, `src/bgfx_shader.sh`, `src/renderer.h`. `THREE/` = three files from mrdoob/three.js `dev` b3cbe43d: `src/renderers/webgl/WebGLProgram.js`, `src/materials/RawShaderMaterial.js`.
- `PSX/` = psx-spx docs 6d7d1bc (`graphicsprocessingunitgpu.md`, `geometrytransformationenginegte.md`); `LD/` = libdragon e356bf3 (`include/rdpq_mode.h`, `include/surface.h`); `KOS/` = KallistiOS `kernel/arch/dreamcast` 84ed47b (`include/dc/pvr.h`, `include/dc/pvr/{pvr_fog.h, pvr_header.h, pvr_pal.h}`, `hardware/pvr/pvr_fog.c`, `hardware/video.c`); `Q1/` = id-Software/Quake WinQuake bf4ac42 (`vid.h`, `r_surf.c`, `view.c`); `DOOM/` = id-Software/DOOM linuxdoom-1.10 a77dfb9 (`r_main.{h,c}`); all under scratchpad `retro/`.
- `SH/` = MIT retro shader packs under scratchpad `retro/shaders/`: dsoft20/psx_retroshader, MenacingMecha/godot-psx-style-demo, Zorochase/ultimate-retro-shader-collection, AnalogFeelings/godot-psx, Kodrin/URP-PSX.
- `ANG/` = scratchpad `Angelica-2.2.21/glsm/src/main/java/com/gtnewhorizons/angelica/glsm` (tag 2.2.21, a8c29fa): `GLStateManager.java`, `redirect/GLSMRedirector.java`, `Feature.java`, `states/BlendState.java`, `backend/Lwjgl2GLRenderBackend.java`. `ANGSRC/` = scratchpad `Angelica-2.2.21/src/main/java`: `net/coderbot/iris/gl/texture/TextureUploadHelper.java`, `com/gtnewhorizons/angelica/rendering/PlayerReflectionCapture.java`.
- `MC/` = `C:\Users\astro\Downloads\OC-LuaJIT\build\rfg\minecraft-src\java` (Minecraft 1.7.10 + Forge 10.13.4.1614, decompiled): `net/minecraft/util/ScreenShotHelper.java`.
- Spec texts: scratchpad `glsles100.txt` (GLSL ES 1.00 rev 17); scratchpad `web22/` (GL 3.3 core, GLSL 3.30, WGSL).
- Researcher notes and scripts: scratchpad `engine-lessons/{godot-server, shader-ergonomics, retro-techniques}.md`; scratchpad `retro-scripts/` (`q565.py`, `bayer.py`, `lut.py`).
- Earlier reports: 04 §5–6, 06, 09 §3.2–3.6, 20, 21, 22, 23, 24.

Web:
- Godot PRs and issues: #100110 https://github.com/godotengine/godot/pull/100110 ; #99750 https://github.com/godotengine/godot/issues/99750 ; #90400 https://github.com/godotengine/godot/pull/90400 ; #84976 https://github.com/godotengine/godot/pull/84976 ; #81356 https://github.com/godotengine/godot/pull/81356 ; #82364 https://github.com/godotengine/godot/pull/82364 ; API validation https://github.com/godotengine/godot/tree/master/misc/extension_api_validation
- Godot documentation: https://docs.godotengine.org/en/latest/engine_details/architecture/internal_rendering_architecture.html ; https://docs.godotengine.org/en/latest/tutorials/performance/thread_safe_apis.html ; "Introducing Reverse Z" https://godotengine.org/article/introducing-reverse-z/
- Firefox `DriverCrashGuard`: https://searchfox.org/mozilla-central/source/gfx/src/DriverCrashGuard.h
- Shadertoy (archived page; shadertoy.com/howto returned HTTP 403): https://archive.hackclub.com/archive/AUjnb/mhtml/html ; GameMaker, "Guide To Using Shaders": https://manual.gamemaker.io/monthly/en/Additional_Information/Guide_To_Using_Shaders.htm ; Picotron manual: https://www.lexaloffle.com/dl/docs/picotron_manual.html
- N64 Programming Manual §12.5: https://ultra64.ca/files/documentation/online-manuals/man-v5-1/pro-man/pro12/12-05.htm ; Copetti, Sega Saturn and Dreamcast architecture: https://www.copetti.org/writings/consoles/sega-saturn/ , https://www.copetti.org/writings/consoles/dreamcast/ ; A. Gavin, "Making Crash Bandicoot": https://all-things-andy-gavin.com/making-crash
- Repositories: https://github.com/godotengine/godot , https://github.com/godotengine/godot-docs , https://github.com/openscenegraph/OpenSceneGraph , https://github.com/o3de/o3de , https://github.com/love2d/love , https://github.com/raysan5/raylib , https://github.com/bkaradzic/bgfx , https://github.com/mrdoob/three.js , https://github.com/psx-spx/psx-spx.github.io , https://github.com/DragonMinded/libdragon , https://github.com/KallistiOS/KallistiOS , https://github.com/id-Software/Quake , https://github.com/id-Software/DOOM , https://github.com/dsoft20/psx_retroshader , https://github.com/MenacingMecha/godot-psx-style-demo , https://github.com/Zorochase/ultimate-retro-shader-collection , https://github.com/AnalogFeelings/godot-psx , https://github.com/Kodrin/URP-PSX
- Specifications: GLSL ES 1.00 rev 17 https://registry.khronos.org/OpenGL/specs/es/2.0/GLSL_ES_Specification_1.00.pdf ; GLSL 3.30 https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.3.30.pdf ; OpenGL 3.3 core https://registry.khronos.org/OpenGL/specs/gl/glspec33.core.pdf ; WGSL https://www.w3.org/TR/WGSL/

## Verification notes

Adversarial check of 2026-10-08. The verifier checked 38 lessons individually and spot-checked the 4 IGNORE verdicts (G3, S11, R12, R14), which were consistent with 21 §2.2/§3.1 and 20 §3. It confirmed 21 lessons (several with notes), modified 17, and refuted none. All corrections are applied in the text above. No lesson contradicts reports 20–23 outright.

**Verdict or scope changes.**

1. **G4 (pending link).** Parts (a), (c) and (d) stand. The researcher's 1 s timeout rule added nothing, because 24 §3.3 already drops frames after 1 s. Canvas mode keeps dropped frames queued, so a permanent link failure got its own rule: skip the draws that use that program, report `opengpu_program(…, false)`, keep the rest of the frame.
2. **G6 (device list).** The mechanism moved from M0 to M1, beside the kill switch, with an empty list. Godot's list must not be imported: "Intel(R) HD Graphics" matches every Gen9 iGPU, and Godot's breakage was in its own renderer. The owner's Arrow Lake iGPU is not on Godot's list.
3. **G8 (pixel store).** Partly covered already: 23 §2's hygiene assertion lists pack/unpack alignment, and 21 §3.3 sets unpack row length and alignment. The delta is forcing the state and adding rows to 20 §1. The researcher overstated the readback exposure: readback rows are multiples of 8, so a foreign pack alignment is harmless; only foreign row length or skips would matter. Save pack state only when a readback is queued.
4. **G9 (streaming rings).** ADOPT → CONSIDER. The "skip the device when the ring is full" rule duplicated 24 §3.7's ≤ 2-in-flight limit; slots are indexed by the PBO ring slot instead. Orphaning is acceptable in M0.
5. **G11 (crash guard).** The crash loop comes mainly from OC persisting running machines (and autorun), not from tail replay. The guard still works because it keys on program hashes. Moved from M1 to M2, when user shaders arrive. The guard write must precede the first `glUseProgram` of a new program set.
6. **G12 (API evolution).** The dump and CI diff from M1 stand. Mandatory decoder shims are limited to persisted formats (side files hold op bytes); Lua-facing changes before the freeze need only a justification and a changelog entry.
7. **G13 (creation budget).** Split: the budget within `renderBudgetMs` is ADOPT in M1 (G13a); slicing large uploads is CONSIDER, decided by M0's upload-cost measurement (G13b).
8. **S1 (typed shader types).** ADOPT → CONSIDER, an owner decision at the M2 design review. It is new language scope beyond decision 5, and additive, so "must land before the freeze" was wrong; only S5 and S6 touch the protocol. It overlapped R3, which needed one decision (§2.4).
9. **S2 (built-in table).** Useful without S1, because the raw frontend needs per-stage built-in rules anyway; the typed-shader parts depend on S1.
10. **S3 (IR composition).** Conditional on S1; the 136-program CI matrix is speculative until a template set exists.
11. **S4 (one source).** ADOPT → CONSIDER: OpenGPU's frontend already reports mismatched varyings with `line:col`, so this is convenience only.
12. **S5 (semantic attributes).** Two constraints added: POSITION mandatory (attribute 0 aliasing, 20 §2), and no `glVertexAttrib4f` for missing streams because of GLSM's attribute cache (V1).
13. **S6 (engine-derived transforms).** Kept as an M2 protocol proposal, but the rationale was restated: Lua float ops are modest; the costs are float32 encoding through `math.frexp` on 5.2 and OC-LuaJIT (04 §5) and 16.16 precision on projection terms (09 §3.2). To be decided with S1 and S7, not as a standalone ADOPT; recorded as CONSIDER (recommended).
14. **S8 (library delivery).** The library lives on the server as Java resources resolved by `#include <og/…>`; no `programSource()` API and no Lua strings (OpenOS memory at 192 KB). The graphics and compute libraries stay separate.
15. **S10 (contract versioning).** Versioning depends on S1. Documenting the conventions is needed in M2 regardless, because 22 §1.3 marks the `gl_FragCoord` orientation only as [inf.].
16. **S12 (raylib corpus).** A per-file licence check is needed: `ascii.fs` is Shadertoy-derived (CC BY-NC-SA 3.0 by default), and several files carry MIT notices. Exclude those files or fetch the corpus in CI.
17. **R3 (variant family).** Sound and consistent with 22's sum-of-branches costing. It conflicted with S1/S8 and was reconciled (§2.4). Variant counts must be documented against 4 compiles per second per card and the 256-program LRU, and the sources are delivered server-side through `#include`.

**Confirmed with notes.**
- G2: low marginal value, since 21 §6 and 24 §3.3 already behave this way; a short M0 bullet. G5: the risk is larger than stated (a cross-device leak); validator rejection is a deterministic alternative. G7: only the 3 ms throttle is exposed to DVFS. G10: complements `:core`'s VRAM accounting; cross-check both in `:gl-testkit`. G14: the ≈ 2 MB saving per T3 device is right; the trigger is ≈ 50 T3 3D devices.
- S7: defaults are the initial block contents that Lua row writes overwrite. S9: depends on S1. S13: still a valid question, but it reopens decision 5, and ANGLE's Intel integer workarounds remain a real hazard; low priority.
- R1: the `#extension` gate is required, because `noperspective` is not reserved in ES 1.00. R2: guard w ≤ 0 before the divide. R5: merged with G5 into one format rule. R7: a real LAN bug in 24, not only an optimization; 09's keyframe-on-palette-change rule was dropped by 24. R8: index8 exactness matters for 3D only, since 2D writes R8UI directly; still preferable to the 32³ LUT. The verifier re-ran the dither arithmetic.
- G1, S14, R4, R6, R9, R10, R11, R13: confirmed as written.

**New finding.** V1: Angelica 2.2.21 caches constant generic vertex attributes by dirty flag, and `glVertexAttrib4f` bypasses that cache, so OpenGPU must not set constant generic attributes (or must restore them), and the hygiene assertion should cover locations 0–7. The effect on Iris draws was not reproduced [I].

**Writer's checks and choices.**
- Re-read at the cited lines, all as cited: `GD/core/templates/rid_owner.h:66-70, 155-161`; `GD/drivers/gles3/shader_gles3.h:193-208` (`if (false)` at `:196`); `GD/main/main.cpp:2404-2412, 2456`; `GD/servers/rendering/rendering_device.compat.inc:46-51`; `GD/servers/rendering/rendering_device.cpp:1382-1386`; `ANG/GLStateManager.java:320-348, 686-692, 3130-3140`; 09 §3.4 (keyframes after palette changes, line 85); 20 §3 (readback alignment); 23 §2 hygiene (line 55); 21 §3.3.
- The researcher's citation for Godot's silent drop of excess canvas items (`rasterizer_canvas_gles3.cpp:1315, 1494`) shows instanced drawing, not the drop; the drop is documented at `GD/doc/classes/ProjectSettings.xml:3088-3090`, which §1.1 now cites.
- `misc/extension_api_validation/` is not in the sparse clone; its folder list comes from the GitHub listing.
- Writer's choices: G13, S10, S12 and R10 are split into separately judged parts; G15 and G16 come from the researcher's "checked, no lesson entry" notes and were not separately verified; the effort sums in §4 and §5 are the writer's arithmetic on [est.] figures.
