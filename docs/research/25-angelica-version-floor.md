# 25 — Angelica version floor

Writer, 2026-10-09 (inputs gathered 2026-10-08). This report answers the owner's question 8 (24 §7): which is the lowest Angelica 2.x release that OpenGPU can support as an optional but first-class dependency? It merges the findings of three agents with checks the writer re-ran:

- the changelog analyst (findings `CL F1`–`F16`);
- the linkage analyst (`LK L1`–`L11`);
- a verifier (`VF`).

Scope: Java 8 + LWJGL 2.9.4 + Forge 10.13.4.1614, and the client GL path of 20 §1–§4 and 24 §3.7. Only the owner's 2.2.21 has ever run in game. Every statement about any other version is static: it rests on source, `javap` and simulated redirector maps.

Citation prefixes. All scratchpad paths are under `C:\Users\astro\AppData\Local\Temp\claude\C--Users-astro-Downloads-OpenGPU\170a14cc-5cc1-40e0-a7ec-6130b748eabf\scratchpad\` and are read-only data.

- `AG@<tag>:<path>:<line>` is the full Angelica clone `angelica-git` (all tags, HEAD 78abc057, 2026-10-07), read with `git show <tag>:<path>`.
  - Short paths: `GLSM` = `glsm/src/main/java/com/gtnewhorizons/angelica/glsm/GLStateManager.java`; `RED` = `glsm/.../glsm/redirect/GLSMRedirector.java`; `FEAT` = `glsm/.../glsm/Feature.java`; `BSS` = `glsm/.../glsm/stacks/BooleanStateStack.java`; `CFG` = `src/main/java/com/gtnewhorizons/angelica/config/AngelicaConfig.java`.
  - Commits are cited by hash. "First tag" is the earliest tag that `git tag --contains` returns.
- `JAR <v>` is `Angelica-<v>.jar` from `https://nexus.gtnewhorizons.com/repository/public/com/github/GTNewHorizons/Angelica/<v>/`, stored in `angelica-jars\<v>\`. All 118 releases of 2.x that `.../Angelica/maven-metadata.xml` lists (`<release>2.2.30`) were downloaded. That file is cited as `MVN`.
- `REL <v>` is `https://github.com/GTNewHorizons/Angelica/releases/tag/<v>`. Publication dates and pre-release flags come from the GitHub releases API.
- `DAX <name>` is `https://github.com/GTNewHorizons/DreamAssemblerXXL/blob/master/releases/manifests/<name>.json`.
- `MC/` is `C:\Users\astro\Downloads\OC-LuaJIT\build\rfg\minecraft-src\java\`, the decompiled Minecraft 1.7.10 + Forge 10.13.4.1614 source, as in 20.
- `CL` is `angelica-floor\changelog.md`, the changelog analyst's notes and tables.
- `LWK` is `angelica-floor\linkage-work\`, the linkage analyst's data: simulated maps `red\<v>.json`, `javap` lists `glsm\<v>.base.txt`, `class-matrix.tsv` and the scripts.
- `F25` is `floor25\`, the writer's work: the extended call set `callset.tsv`, the evaluator `evaluate25.py`, the results `out.json` and the table generators `gen.py` and `gen2.py`.
- `VCHK` is `vscripts\chk.py` with `vchk\*.txt`, the verifier's independent check.

Tags: **[V]** verified at the cited place; **[I]** inference; **[proposal]** a default set here for the owner to accept or change.

## Summary

1. **Recommended floor: Angelica 2.2.8** (2026-08-19). It is supported, linkage-tested in CI and smoke-tested in game. It covers the owner's 2.2.21, the 2.2.28 shipped in the 2.9.0-RC-2 pack, the latest 2.2.30, and every GTNH manifest that ships Angelica 2.2.x (§7).
2. **The lowest release OpenGPU's code runs on is 2.1.14.** Releases 2.1.14–2.2.7 form a best-effort band: graphics stay on and a warning is logged. A CI linkage canary covers the band; in-game tests do not. 2.1.12 is the hard linkage minimum, and 2.1.5–2.1.11 crash on the readback call. Below 2.1.14 a runtime version gate reports graphics as unavailable (§1.1).
3. Why 2.2.8: it is the first release whose `glPopAttrib` undoes the blend, alpha-test, depth-mask and colour-mask changes a TESR makes while Iris holds an override lock (22c94799, verified in code). Making 2.1.14 first-class would add a second GLSM generation to every milestone's in-game tests, only to cover superseded 2.9.0 pre-releases (§1.2).
4. All 124 planned calls link (the descriptor is identical) in every release from 2.2.0 to 2.2.30. Nothing needs 2.2.22 or later. 53 calls link in all 118 releases (§3).
5. One workaround is mandatory inside the supported range: save and restore the scissor box explicitly. `glPopAttrib` does not restore it before 2.2.21, and RC-1's 2.2.19 is one of those releases. The other rules in §5.1 apply unconditionally; they are cheap and harmless on newer versions and without Angelica.
6. Test matrix (§6): the CI linkage test runs against 2.1.14 (the canary), 2.2.8, 2.2.21, 2.2.28 and the latest release. The in-game smoke test with an Iris pack runs on 2.2.8, 2.2.21, 2.2.28 and the latest release.
7. No stable GTNH release ships Angelica 2.x yet. GTNH 2.8.4 ships 1.0.0-beta66b, which falls below the gate (§7, Q3).

## 1. Decision

### 1.1 Policy [proposal]

| Angelica present | Status | OpenGPU behaviour |
|---|---|---|
| none | vanilla leg | as in 20 (needs a GL ≥ 3.3 compatibility context) |
| ≥ 2.2.8 | **supported** | graphics on |
| 2.1.14 – 2.2.7 | best-effort | graphics on; one log line, "Angelica <v> is below the tested floor 2.2.8" |
| < 2.1.14, every 1.x included | unsupported | graphics unavailable, with that reason; compute unaffected, exactly as for GL < 3.3 or the GLES profile (24 §4) |
| version string that does not parse | treated as supported | logged once |

**Gate mechanism.**

- Read the version with `Loader.instance().getIndexedModList().get("angelica").getVersion()` (`MC/cpw/mods/fml/common/Loader.java:728`).
  - Angelica declares `version = Tags.VERSION` (`AG@2.2.21:src/main/java/com/gtnewhorizons/angelica/AngelicaMod.java:16-19`).
  - Its `mcmod.info` carries the plain release number: `"version": "2.2.21"`, `"2.2.8"` and `"2.1.14"` in `JAR 2.2.21`, `JAR 2.2.8` and `JAR 2.1.14` [V].
- Compare it with `VersionParser.parseRange("[2.1.14,)")` and `"[2.2.8,)"` (`MC/cpw/mods/fml/common/versioning/VersionParser.java:63`).
- Run the check once at client init and feed the result into the `GlCaps` line (20 §7).
- If OpenGPU declares `after:angelica` for load ordering, give it no version range.
  - FML 1.7.10 also checks the versions of soft dependencies, and it throws `MissingModsException` when one is out of range (`MC/cpw/mods/fml/common/Loader.java:246-260`).
  - An optional dependency would then stop the game from starting.

**"Supported" and "best-effort".**

- *Supported* means three things: the version is in the gating CI linkage test, it gets an in-game smoke test with an Iris pack at each milestone, and its bugs get fixed.
- *Best-effort* means two things:
  - The code rules of §5.1 keep the band working by construction, and the 2.1.14 canary keeps it linking in CI.
  - Nothing in the band is run in game.

### 1.2 Candidates

| Candidate | What choosing it brings | Costs | Verdict |
|---|---|---|---|
| 2.1.12 | the hard linkage minimum: the readback overload `glGetTexImage(IIIIJ)V` exists (52b0db25, #1592, first tag 2.1.12; `AG@2.1.12:GLSM:5817-5819`) | (1) until 2.1.14 a TESR whose box crosses its 16³ section is culled with that section (951be04e, #1607), so M1.5 walls would vanish. (2) GL method references are not redirected before 2.1.13 (e65c01c2) | below the gate |
| 2.1.14 (the changelog analyst's floor) | adds 2.9.0-beta-1 (2.1.32), 2.9.0-beta-2 (2.1.50) and the "experimental" manifest (2.1.37), all of them superseded pre-releases (§7) | a second GLSM generation (§5.3): (1) the era-2 redirector (`LK L1`) with 11 planned calls bypassing GLSM (§3); (2) no unmapped-call detector; (3) DSA texture paths until 2.1.21; (4) the Iris leak fixed in 2.2.8; (5) the TESR redraw in the shadow pass; (6) Java 8 crash fixes in Angelica itself until 2.1.29 | best-effort band |
| 2.2.1 (both analysts' conservative alternative) | the first 2.2 release that is not a pre-release (2.2.0 has `prerelease: true`, `LK L3`); every planned name goes through GLSM | the Iris leak fixed in 2.2.8; no GTNH manifest ships any of 2.2.0–2.2.7 (`CL` Table D) | inside the band |
| **2.2.8** (the verifier's floor) | while an Iris override is held, `glPopAttrib` writes into the vanilla state layer (22c94799, #2017, first tag 2.2.8; `AG@2.2.8:BSS:82-88`) | one workaround, for the scissor box (§5.1 R-1) | **supported floor** |
| 2.2.21 (the earlier default in 24 §3.7) | `glPopAttrib(GL_SCISSOR_BIT)` restores the scissor box (44c930f4, #2138) | drops 2.9.0-beta-3 (2.2.10) and 2.9.0-RC-1 (2.2.19), which cost only one cheap workaround | too high |
| 2.2.22+ | `glReadPixels(IIIIIIJ)V` links (7bb5bfd4, #2157) | OpenGPU reads back with `glGetTexImage` and does not need it | — |

### 1.3 Where the inputs disagree, and the decision

**The declared floor.** The three inputs recommend different floors:

- The changelog analyst recommends 2.1.14, with 2.2.1 as the conservative alternative.
- The linkage analyst gives 2.1.12 on linkage alone, or 2.2.1 if every call has to go through GLSM.
- The verifier recommends 2.2.8 if Iris correctness of the host TESR counts as "first-class", and otherwise 2.2.1. The verifier keeps 2.1.14 as best-effort.

**Decision: 2.2.8**, for four reasons.

- (a) Iris correctness is in scope. M0's definition of done requires a `runClient` run "with an Iris pack" (24 §6).
- (b) The leak below 2.2.8 is real in the code.
  - In 2.1.14, while Iris holds a lock, GLSM hands six calls to Iris's handlers as deferred requests: `glEnable/glDisable(GL_BLEND)`, `glBlendFunc`, `glBlendFuncSeparate`, `glDepthMask` and `glColorMask` (`AG@2.1.14:GLSM:1176`, `:1192`, `:1230`, `:1305`, `:1409`, `:1558`).
  - `popDepth` then restores the cached value directly (`AG@2.1.14:BSS:92-99`). The deferred request is applied anyway when Iris releases the lock.
  - 2.2.8 routes the restore through the vanilla layer (`AG@2.2.8:BSS:82-88`). The layers are wired for blend, alpha, depth and colour mask at `AG@2.2.21:src/main/java/com/gtnewhorizons/angelica/iris/IrisGLSMBridge.java:127-132`.
  - `AG@2.2.7:BSS` has no vanilla layer [V, writer]. The effect in game is [I].
- (c) 2.2.8 costs no pack coverage compared with 2.2.1, because no manifest ships any of 2.2.0–2.2.7.
- (d) The coverage that 2.1.14 adds is small, and the cost is large.
  - The coverage is two superseded pre-releases (plus an old experimental manifest), whose OC versions, 1.12.44 and 1.12.48, are older than the OC 1.12.64 that OpenGPU's research targets (README; §7).
  - The cost is that every milestone would have to test a second redirector era, a second Iris integration and a second texture path. Every Angelica bug specific to 2.1.x would become OpenGPU's to work around.

All three inputs agree on the facts; they differed only on the level to declare. The band and its canary meet the owner's wish for the lowest workable version.

**Where the pack-PBO binding cache starts.** `CL` Table C says from 2.1.12, and `LK L5` says from 2.1.6.

- `LK` is right. `AG@2.1.6:GLSM:1012` has `case GL21.GL_PIXEL_PACK_BUFFER_BINDING -> boundPixelPackBuffer`, and `AG@2.1.5:GLSM` has no such case [V, writer].
- `CL`'s table column starts at its 2.1.12 sample.
- Both versions are below the gate, so the floor is unaffected.

**"Four rules needed below 2.2.21"** (`CL`). The verifier points out that three of the four apply only below 2.1.57:

- the glGet buffer position, below 2.1.16;
- stray GL errors, below 2.1.57;
- method references, below 2.1.13.

The verifier is right. Only the scissor rule is needed across 2.2.1–2.2.20. All the rules stay unconditional anyway (§5.1), so this changes no code.

## 2. Version history: the changes that matter

| Version (published) | Change | Effect on OpenGPU | Evidence | Impact |
|---|---|---|---|---|
| 1.0.0-alpha19 | `@ThreadSafeISBRH(perThread)` | the chassis ISBRH annotation; its source is unchanged from 2.0.0-alpha1 to 2.2.30 | 6a7c2520 (#254), first tag 1.0.0-alpha19; `git diff 2.0.0-alpha1 2.2.30 -- .../api/ThreadSafeISBRH.java` is empty (`CL F9`) | none |
| 2.0.0-alpha1 (2026-01-07) | the first 2.x (Celeritas); a **compatibility context**; a per-owner redirector that maps only GL11/12/13/14/20 names | 67–71 of the 124 planned calls bypass GLSM in 2.0.0-alpha1–alpha25 (§3) | REL 2.0.0-alpha1; `AG@2.0.0-alpha1:src/main/java/.../loading/shared/transformers/AngelicaRedirector.java:73-226` (`CL F1`) | below the gate |
| 2.0.0-alpha13 (2026-01-26) | TE render AABB cached per instance; finite/infinite classification per class | "stable maximal bounds from the first call" applies from here | 72b13543 (#1309) (`CL F8`) | design rule |
| 2.1.0 (2026-03-03) | **3.3+ core context**, FFP emulation, emulated `glPushAttrib` with depth 16+2 | this is the leg that 20 and 24 design for | REL 2.1.0 "#1412"; d7d2228a, first tag 2.1.0; `AG@2.1.0:GLSM:133` (`CL F1`, `F6`) | below the gate |
| 2.1.5 (2026-03-21) | GLSM extracted into its own module; one merged GL11–GL44 name map for any `org/lwjgl/opengl/GL*` owner | FBO, VAO, sampler, buffer and shader-query calls now go through GLSM; `glGetTexImage(IIIIJ)V` becomes a trap | REL #1527; `AG@2.1.5:RED:154` (`CL F2`, `LK L1`, `L2`) | NSME in 2.1.5–2.1.11 |
| 2.1.6 (2026-03-22) | pack and unpack PBO bindings cached | — | `AG@2.1.6:GLSM:1012` | none |
| 2.1.12 (2026-03-28) | `GLStateManager.glGetTexImage(IIIIJ)V` added | the readback links | 52b0db25 (#1592); `AG@2.1.12:GLSM:5817-5819` | hard linkage minimum |
| 2.1.13 (2026-03-29) | method handles inside `invokedynamic` redirected | GL method references are redirected from here on | e65c01c2 (`LK L11`) | rule R-5 |
| 2.1.14 (2026-03-31) | cross-section TE render bounds handled | walls that cross a section boundary stay visible | 951be04e (#1607) (`CL F7`) | **the band starts** |
| 2.1.16 (2026-04-03) | cached `glGet*` into a buffer no longer advances its position | — | ec82280b (#1637) (`CL F13`) | rule R-2 |
| 2.1.21 (2026-05-04) | DSA removed from GLSM | before this release `glTexImage2D` may take a DSA path (the `shouldUseDSA` branch of `glTexImage2D(..., ByteBuffer)` in `AG@2.1.14:GLSM`) | REL 2.1.21 "#1723" | untested in the band [I] |
| 2.1.24 (2026-05-19) | `MixinMinecraft_IconifyGuard` skips `updateCameraAndRender` while minimized | `RenderTickEvent(START)` still fires | 9c1b6c75 (#1766) (`CL F11`) | none |
| 2.1.40 (2026-06-18) | `glDrawArraysInstanced` mapped; STATIC/INFINITE/DYNAMIC bound classes and the `dynamicBoundsTileEntities` option | — | REL #1874; `AG@2.1.40:CFG:274-277` (`CL F8`) | none |
| 2.1.57 (2026-07-27) | capabilities removed from core are no longer sent to the driver | before this release a stray `GL_INVALID_ENUM` can be pending | 6570b6a0 (#1965) (`CL F13`) | rule R-3 |
| 2.2.0 (2026-08-08, **pre-release**) | 1. era-3 redirector, which maps every planned name. 2. unmapped-call detector (default WARN). 3. `glProfile=ES` and `RenderSystem.isGLES()`. 4. `shadowSkipInMeshTileEntities`. 5. STATIC bounds baked into section data. 6. TESR batching API v1. 7. `glReadPixels` mapped without its `long` overload | all 124 planned calls link from here on | REL #1993; `AG@2.2.0:RED:349`, `:420`, `:530`; `AG@2.2.0:glsm/.../config/SystemProperties.java:24`; `AG@2.2.0:CFG:337-348` (`CL F4`, `F12`, `F14`, `LK L3`) | inside the band |
| 2.2.1 (2026-08-09) | the first 2.2 release that is not a pre-release | — | GitHub API `prerelease=false` (`LK L3`) | inside the band |
| **2.2.8** (2026-08-19) | Iris vanilla state layer: while an override is held, `glPopAttrib` restores into that layer | TESR state changes no longer leak under Iris locks | 22c94799 (#2017), first tag 2.2.8; `AG@2.2.8:BSS:82-88` (VF) | **the floor** |
| 2.2.12 (2026-09-08) | fix: Iris treated every texture-unit capability as `GL_TEXTURE_2D` | matters only if the TESR toggles per-unit texture capabilities, which R-4 forbids | 32d1c27b (#2072) [V, writer] | none |
| 2.2.14 (2026-09-15) | sampler bindings cached per unit | before this release they pass through, which is still consistent | a07cecb8 (#2099) (`CL F13`) | none |
| 2.2.15 (2026-09-17) | TESR API v1 → v2 (additive) | OpenGPU uses a plain TESR | 3876f7e6 (#2104) (`CL F10`) | none |
| 2.2.18 (2026-09-22) | push/pop optimisations; display lists that use push/pop broken until 2.2.19 | OpenGPU uses no display lists | REL 2.2.18 "#2124", REL 2.2.19 "#2126" | none [I] |
| 2.2.19 (2026-09-22) | attrib stack depth 18 → 32 | OpenGPU pushes one level either way | 53776684 (#2126); `AG@2.2.8:GLSM:216` (18) vs `AG@2.2.19:GLSM:236` (32) | none |
| 2.2.21 (2026-09-27) | `glPopAttrib(GL_SCISSOR_BIT)` restores the scissor box | earlier releases need an explicit save and restore | 44c930f4 (#2138); in `AG@2.2.8:FEAT:292-295` and `AG@2.2.20:FEAT:303-306` the box is only a comment (`CL F5`) | rule R-1 |
| 2.2.22 (2026-09-28) | `glReadPixels(IIIIIIJ)V` added | not used | 7bb5bfd4 (#2157) | none |
| 2.2.23 (2026-09-29) | TESR state and batching leaks fixed; TESR item renderers without a world no longer overflow the attrib stack | protects against other mods' TESRs; see R-8 | 1f274a64 (#2162), 9814a96b (#2159) [V, writer: diff of 9814a96b] | rule R-8 |
| 2.2.29 (2026-10-05) | legacy stack queries answered by GLSM; SDL-GPU selectable in the video settings | before this release `GL_ATTRIB_STACK_DEPTH` reaches the core driver | 6dfb0312 (#2211); REL #2215 | rule R-6 |
| 2.2.30 (2026-10-07) | the latest release (`<release>2.2.30`) | — | MVN; master has only a README commit after it (`CL`) | latest |

## 3. Linkage matrix

**Method.** The linkage analyst built the matrix, and the writer extended it.

- *Releases:* all 118 Angelica 2.x releases on the GTNH Maven.
- *Redirect maps:* simulated from the bytecode of each jar's redirector `<clinit>` (`LWK\simclinit.py`).
- *Target methods:* the public static methods of each jar's `GLStateManager`, from `javap -s -p` (`LWK\glsm\<v>.base.txt`).
- *Redirect rule:* each redirector era has its own (`LK L1`). In every era only the owner and the name are rewritten, and the descriptor is kept (`AG@2.2.21:RED:947-965`, VF).
- *Validation:*
  - The simulated 2.2.21 map equals report 20's map, which was derived from source.
  - Report 20 counted "about 70" trapped overloads in 2.2.21. Both analysts independently count exactly 70.
  - The verifier re-checked every planned target in `JAR 2.1.12`, `2.1.14`, `2.2.1`, `2.2.21`, `2.2.28` and `2.2.30` (`VCHK`).
- *Writer's extension:* the writer added 18 rows to `LK`'s 123 (F10, R01–R14, A15–A17) and re-ran the whole set over all 118 releases (`F25\out.json`). `LK`'s 11 version classes are unchanged.

**Legend.**

- *links*: the call is rewritten to `GLStateManager`, and the descriptor is identical.
- *driver*: the name is unmapped, so LWJGL calls the driver directly and GLSM's cache never sees the call.
- ***NSME***: the name is mapped but the descriptor is missing, so the call throws `NoSuchMethodError` the first time it runs.

**Counts for the 124 planned calls** (the C, F, X and R rows of §4):

| Releases | Number of releases | Link | Driver | NSME |
|---|---|---|---|---|
| 2.0.0-alpha1 – 2.1.4 (era 1, six classes) | 30 | 53–88 | 36–71 | 0 |
| 2.1.5 – 2.1.11 | 7 | 112 | 11 | **1 (C91)** |
| 2.1.12 – 2.1.39 | 28 | 113 | 11 | 0 |
| 2.1.40 – 2.1.61 | 22 | 114 | 10 | 0 |
| 2.2.0 – 2.2.21 | 22 | **124** | 0 | 0 |
| 2.2.22 – 2.2.30 | 9 | **124** | 0 | 0 |

**53 of the 124 planned calls link in all 118 releases, and all 124 link in every release from 2.2.0 to 2.2.30.** No planned call goes from linking back to unmapped or failing in a later release (`LK`, `F25`).

**Calls that throw `NoSuchMethodError` in some release** (the planned call C91 plus rejected alternatives):

| ID | Call | alpha1–2.1.4 | 2.1.5–2.1.11 | 2.1.12–2.1.39 | 2.1.40–2.1.61 | 2.2.0–2.2.21 | 2.2.22–2.2.30 |
|---|---|---|---|---|---|---|---|
| C91 | `GL11.glGetTexImage(IIIIJ)V` | driver | **NSME** | links | links | links | links |
| A01 | `GL11.glReadPixels(IIIIIIJ)V` | driver | driver | driver | driver | **NSME** | links |
| A02 | `GL15.glGetQueryObjectui(II)I` | driver | driver | driver | driver | **NSME** | **NSME** |
| A04 | `GL30.glGenFramebuffers(Ljava/nio/IntBuffer;)V` | driver | **NSME** | **NSME** | **NSME** | **NSME** | **NSME** |
| A05 | `GL30.glGenVertexArrays(Ljava/nio/IntBuffer;)V` | driver | **NSME** | **NSME** | **NSME** | **NSME** | **NSME** |
| A06 | `GL15.glDeleteQueries(Ljava/nio/IntBuffer;)V` | driver | driver | driver | driver | **NSME** | **NSME** |
| A07 | `GL20.glGetShaderInfoLog(ILjava/nio/IntBuffer;Ljava/nio/ByteBuffer;)V` | driver | **NSME** | **NSME** | **NSME** | **NSME** | **NSME** |
| A11 | `GL11.glTexImage2D(IIIIIIIILjava/nio/ShortBuffer;)V` | **NSME** | **NSME** | **NSME** | **NSME** | **NSME** | **NSME** |
| A15 | `GL30.glGetInteger(II)I` | driver | **NSME** | **NSME** | **NSME** | links | links |

**Planned calls that bypass GLSM in some release from 2.1.5 onwards.** Where this happens inside the best-effort band (2.1.14–2.1.61), the bypass is harmless (§5.3); 2.1.5–2.1.13 are below the gate.

| ID | Call | alpha1–2.1.4 | 2.1.5–2.1.11 | 2.1.12–2.1.39 | 2.1.40–2.1.61 | 2.2.0–2.2.21 | 2.2.22–2.2.30 |
|---|---|---|---|---|---|---|---|
| C13 | `GL30.glBindRenderbuffer(II)V` | driver | driver | driver | driver | links | links |
| C39 | `GL30.glGenRenderbuffers()I` | driver | driver | driver | driver | links | links |
| C40 | `GL30.glRenderbufferStorage(IIII)V` | driver | driver | driver | driver | links | links |
| C41 | `GL30.glFramebufferRenderbuffer(IIII)V` | driver | driver | driver | driver | links | links |
| C42 | `GL30.glDeleteRenderbuffers(I)V` | driver | driver | driver | driver | links | links |
| C85 | `GL31.glDrawArraysInstanced(IIII)V` | driver | driver | driver | links | links | links |
| C94 | `GL15.glGenQueries()I` | driver | driver | driver | driver | links | links |
| C95 | `GL15.glDeleteQueries(I)V` | driver | driver | driver | driver | links | links |
| C96 | `GL33.glQueryCounter(II)V` | driver | driver | driver | driver | links | links |
| C97 | `GL15.glGetQueryObjecti(II)I` | driver | driver | driver | driver | links | links |
| C98 | `GL33.glGetQueryObjectui64(II)J` | driver | driver | driver | driver | links | links |

**Descriptor-trap totals.** These count the LWJGL 2.9.4 `GL11`–`GL33` overloads, 775 in all, whose name is mapped but whose descriptor is missing (`LWK\traps.py` over all 118 releases, results in `LWK\traps-all.json`; `CL`'s `angelica-floor\scripts\trapcount.py` agrees at every sample the two share). Most of these overloads lie outside OpenGPU's call set: the table measures how wide the trap is, not OpenGPU's exposure to it.

| Releases | Trapped overloads |
|---|---|
| 2.0.0-alpha1 – alpha6 | 32 |
| 2.0.0-alpha7 – alpha25 | 31 |
| 2.1.0 – 2.1.4 | 35–39 |
| 2.1.5 | 71 |
| 2.1.6 – 2.1.11 | 58–70 |
| 2.1.12 – 2.1.13 | 57 |
| 2.1.14 – 2.1.61 | 54 |
| 2.2.0 – 2.2.13 (the floor 2.2.8 included) | 72 |
| 2.2.14 – 2.2.21 | 70 |
| 2.2.22 – 2.2.28 | 69 |
| 2.2.29 – 2.2.30 | 67 |

The jump at 2.2.0 comes from names that were mapped there without all of their overloads. The verifier did not recompute these totals.

## 4. OpenGPU GL call set (input to M0's gating linkage test)

This section is the authoritative input for the linkage test of 20 implication 3 and 24 §6 M0.

**Row groups.**

- **C01–C98:** the planned calls of 20 §1–§4, §6, §7 and 24 §3.7–§3.8 (`LWK\callset.tsv`; the Use column gives the source). Tier *core* means named in 20 or 24, *infer* means implied by a planned feature, and *opt* means optional.
- **F01–F10:** `glEnable`/`glDisable` with a constant capability. The redirector folds these into no-argument methods. The fold list is the same at 2.1.12, 2.2.0, 2.2.21 and 2.2.30 (VF on `LK L1`), so the test must see the constant as well as the descriptor.
- **X01–X02:** the TESR extras, one of which has a non-GL owner.
- **R01–R14:** state queries and restores. The verifier checked them in six jars (`VCHK`), and the writer ran them over all 118 releases. R01 and R02 are mandatory (rule R-1).

**Columns.**

- *Owner:* `GL11` means `org/lwjgl/opengl/GL11`, and so on. The owner of X01 is `net/minecraft/client/renderer/OpenGlHelper`.
- *Descriptor:* every LWJGL descriptor exists in `javap -s` of `lwjgl-2.9.4-nightly-20150209.jar` (`LWK\lwjgl294.txt`; `LK`; the writer re-checked every row in `F25`).
- *Links from:* the first release from which the call links in every later release up to 2.2.30.

**What the test asserts** [proposal, refining 20 implication 3]:

1. **Allowlist.** Take every `invokestatic` in OpenGPU's client classes whose owner is under `org/lwjgl/opengl/` or is `OpenGlHelper`. Each must match a row of §4.1 by owner, name and descriptor. For `glEnable`/`glDisable`, the folded constant must match too. A new GL call needs a new row and a fresh run of the matrix.
2. **Deny-list.**
   - No call matches §4.2.
   - No descriptor mentions `GLSync`.
   - No `invokedynamic` bootstrap argument is a method handle to `org/lwjgl/opengl/*` (R-5).
3. **Linkage.** Repeat this step for each jar in §6.
   - Apply that jar's own redirect rule, in one of two ways. Either load its `GLSMRedirector` in an isolated class loader and transform OpenGPU's classes with it, or evaluate its maps the way `LWK` does. `GLSMRedirector` is Java 8 bytecode (class major 52) in `JAR 2.1.14`, `2.2.8`, `2.2.28` and `2.2.30` [V, writer].
   - Then assert that every rewritten target is a public static method of that jar's `GLStateManager` with an identical descriptor.
4. **Expected pass-through.** On 2.1.14, exactly the 11 driver rows of §3 pass through to the driver; on 2.2.x, none do. Any other unmapped call fails the test.

It is still [I] whether the redirector's transform entry point can be called the same way in era 2 and era 3. M0 settles this. The map-evaluation fallback works for both eras (`LK`).

### 4.1 Planned calls (124)

| ID | Call: `org/lwjgl/opengl/<Owner>.name(descriptor)` | Tier | `GLStateManager` target | Links from | Notes, older versions, alternative | Use |
|---|---|---|---|---|---|---|
| C01 | `GL11.glGetInteger(I)I` | core | `glGetInteger(I)I` | all 2.x |  | state saves (20 §1 table) and leg detection GL_CONTEXT_PROFILE_MASK (20 §2) |
| C02 | `GL11.glGetString(I)Ljava/lang/String;` | infer | `glGetString(I)Ljava/lang/String;` | 2.1.5 |  | GlCaps log line (20 §2, 14 §3) |
| C03 | `GL11.glGetError()I` | core | `glGetError()I` | 2.1.5 |  | glGetError clean after first pass (20 §7) |
| C04 | `GL11.glPushAttrib(I)V` | core | `glPushAttrib(I)V` | all 2.x |  | 7-bit save in START pass (20 §1); 5-bit save in TESR (20 §4, 02 §5) |
| C05 | `GL11.glPopAttrib()V` | core | `glPopAttrib()V` | all 2.x |  | restore (20 §1, §4) |
| C06 | `GL20.glUseProgram(I)V` | core | `glUseProgram(I)V` | all 2.x |  | program save/restore (20 §1 table) |
| C07 | `GL30.glBindFramebuffer(II)V` | core | `glBindFramebuffer(II)V` | 2.0.0-alpha14 |  | draw/read FBO restore per target (20 §1 table) |
| C08 | `GL30.glBindVertexArray(I)V` | core | `glBindVertexArray(I)V` | 2.0.0-alpha19 |  | VAO restore (20 §1 table) |
| C09 | `GL15.glBindBuffer(II)V` | core | `glBindBuffer(II)V` | 2.0.0-alpha19 |  | array/element/pack buffer restore; PBO bind (20 §1, §3) |
| C10 | `GL13.glActiveTexture(I)V` | core | `glActiveTexture(I)V` | all 2.x |  | active unit restore (20 §1) |
| C11 | `GL11.glBindTexture(II)V` | core | `glBindTexture(II)V` | all 2.x |  | texture restore units 0–3 (20 §1, 24 §3.7); TESR/GUI bind (20 §4) |
| C12 | `GL33.glBindSampler(II)V` | core | `glBindSampler(II)V` | 2.1.5 |  | sampler restore; bind 0 during pass (20 §1 table) |
| C13 | `GL30.glBindRenderbuffer(II)V` | core | `glBindRenderbuffer(II)V` | 2.2.0 | Unmapped (straight to the driver) in 2.1.14–2.1.61; harmless (§5.3) | RBO bind at creation (20 §1 table) |
| C14 | `GL11.glIsEnabled(I)Z` | core | `glIsEnabled(I)Z` | all 2.x |  | assert GL_RASTERIZER_DISCARD off (20 §1) |
| C15 | `GL11.glEnable(I)V` | core | `glEnable(I)V` | all 2.x |  | non-folded caps or variable cap argument (20 §1) |
| C16 | `GL11.glDisable(I)V` | core | `glDisable(I)V` | all 2.x |  | GL_STENCIL_TEST, GL_POLYGON_OFFSET_FILL, GL_DITHER, GL_COLOR_LOGIC_OP (20 §1) |
| C17 | `GL11.glColorMask(ZZZZ)V` | core | `glColorMask(ZZZZ)V` | all 2.x |  | glColorMask(true ×4) (20 §1) |
| C18 | `GL11.glDepthMask(Z)V` | core | `glDepthMask(Z)V` | all 2.x |  | glDepthMask(true) (20 §1) |
| C19 | `GL11.glViewport(IIII)V` | core | `glViewport(IIII)V` | all 2.x |  | viewport = tier resolution (20 §1) |
| C20 | `GL11.glDepthFunc(I)V` | infer | `glDepthFunc(I)V` | all 2.x |  | 3D depth test |
| C21 | `GL11.glCullFace(I)V` | infer | `glCullFace(I)V` | all 2.x |  | per-op culling (20 §1) |
| C22 | `GL11.glFrontFace(I)V` | infer | `glFrontFace(I)V` | all 2.x |  | per-op culling |
| C23 | `GL11.glBlendFunc(II)V` | infer | `glBlendFunc(II)V` | all 2.x |  | blended 2D at T3 into RGBA8 (20 §3 option C) |
| C24 | `GL14.glBlendFuncSeparate(IIII)V` | opt | `tryBlendFuncSeparate(IIII)V` | all 2.x |  | alpha-preserving blend (alternative to C23) |
| C25 | `GL14.glBlendEquation(I)V` | opt | `glBlendEquation(I)V` | all 2.x |  | blend equation reset |
| C26 | `GL11.glClearColor(FFFF)V` | core | `glClearColor(FFFF)V` | all 2.x |  | clear RGBA8 scene target, never integer targets (20 §3) |
| C27 | `GL11.glClearDepth(D)V` | infer | `glClearDepth(D)V` | all 2.x |  | clear depth of scene target |
| C28 | `GL11.glClear(I)V` | core | `glClear(I)V` | all 2.x |  | RGBA8 colour + depth only (20 §3) |
| C29 | `GL11.glGenTextures()I` | core | `glGenTextures()I` | 2.1.0 |  | present/surface textures (20 §1) |
| C30 | `GL11.glTexParameteri(III)V` | core | `glTexParameteri(III)V` | all 2.x |  | NEAREST, CLAMP_TO_EDGE, MAX_LEVEL 0 (20 §1) |
| C31 | `GL11.glTexImage2D(IIIIIIIILjava/nio/ByteBuffer;)V` | core | `glTexImage2D(IIIIIIIILjava/nio/ByteBuffer;)V` | all 2.x | `ByteBuffer` form, `null` to allocate (A11 traps) | allocate with (ByteBuffer) null (20 §1) |
| C32 | `GL11.glTexSubImage2D(IIIIIIIILjava/nio/ByteBuffer;)V` | core | `glTexSubImage2D(IIIIIIIILjava/nio/ByteBuffer;)V` | all 2.x |  | LAN-guest upload, no-graphics bitmap (20 §4, §7) |
| C33 | `GL11.glDeleteTextures(I)V` | core | `glDeleteTextures(I)V` | all 2.x |  | eviction (24 §3.6) |
| C34 | `GL11.glPixelStorei(II)V` | opt | `glPixelStorei(II)V` | all 2.x |  | UNPACK_ALIGNMENT/ROW_LENGTH for guest uploads (02 §5 step 2) |
| C35 | `GL30.glGenFramebuffers()I` | core | `glGenFramebuffers()I` | 2.1.5 | Scalar form only (A04 traps) | Fbo, scalar form (20 §1, §3) |
| C36 | `GL30.glFramebufferTexture2D(IIIII)V` | core | `glFramebufferTexture2D(IIIII)V` | 2.1.5 |  | attach RGBA8/R8UI/R16UI (20 §1) |
| C37 | `GL30.glCheckFramebufferStatus(I)I` | core | `glCheckFramebufferStatus(I)I` | 2.1.5 |  | every FBO is complete (20 §7) |
| C38 | `GL30.glDeleteFramebuffers(I)V` | core | `glDeleteFramebuffers(I)V` | 2.1.5 |  | eviction |
| C39 | `GL30.glGenRenderbuffers()I` | core | `glGenRenderbuffers()I` | 2.2.0 | Unmapped (straight to the driver) in 2.1.14–2.1.61; harmless (§5.3) | D24S8 RBO, scalar form (20 §1, §3) |
| C40 | `GL30.glRenderbufferStorage(IIII)V` | core | `glRenderbufferStorage(IIII)V` | 2.2.0 | Unmapped (straight to the driver) in 2.1.14–2.1.61; harmless (§5.3) | D24S8 RBO (20 §1) |
| C41 | `GL30.glFramebufferRenderbuffer(IIII)V` | core | `glFramebufferRenderbuffer(IIII)V` | 2.2.0 | Unmapped (straight to the driver) in 2.1.14–2.1.61; harmless (§5.3) | DEPTH_STENCIL_ATTACHMENT (20 §1) |
| C42 | `GL30.glDeleteRenderbuffers(I)V` | core | `glDeleteRenderbuffers(I)V` | 2.2.0 | Unmapped (straight to the driver) in 2.1.14–2.1.61; harmless (§5.3) | eviction |
| C43 | `GL20.glCreateShader(I)I` | core | `glCreateShader(I)I` | 2.1.0 |  | Program from emitted source (20 §2, impl. 2) |
| C44 | `GL20.glShaderSource(ILjava/lang/CharSequence;)V` | core | `glShaderSource(ILjava/lang/CharSequence;)V` | 2.1.0 |  | emitted #version 330 core source |
| C45 | `GL20.glCompileShader(I)V` | core | `glCompileShader(I)V` | 2.1.0 |  | Program |
| C46 | `GL20.glGetShaderi(II)I` | core | `glGetShaderi(II)I` | 2.1.5 |  | compile status |
| C47 | `GL20.glGetShaderInfoLog(II)Ljava/lang/String;` | core | `glGetShaderInfoLog(II)Ljava/lang/String;` | 2.1.5 | 2-argument form only (A07 traps) | scalar form (20 §3); logs on failure |
| C48 | `GL20.glCreateProgram()I` | core | `glCreateProgram()I` | 2.1.0 |  | Program |
| C49 | `GL20.glAttachShader(II)V` | core | `glAttachShader(II)V` | 2.1.0 |  | Program |
| C50 | `GL20.glLinkProgram(I)V` | core | `glLinkProgram(I)V` | 2.1.0 |  | Program |
| C51 | `GL20.glGetProgrami(II)I` | core | `glGetProgrami(II)I` | 2.1.5 |  | link status |
| C52 | `GL20.glGetProgramInfoLog(II)Ljava/lang/String;` | core | `glGetProgramInfoLog(II)Ljava/lang/String;` | 2.1.5 |  | scalar form (20 §3) |
| C53 | `GL20.glDetachShader(II)V` | infer | `glDetachShader(II)V` | 2.1.0 |  | after link |
| C54 | `GL20.glDeleteShader(I)V` | infer | `glDeleteShader(I)V` | 2.1.5 |  | after link |
| C55 | `GL20.glDeleteProgram(I)V` | core | `glDeleteProgram(I)V` | 2.1.0 |  | eviction |
| C56 | `GL20.glGetUniformLocation(ILjava/lang/CharSequence;)I` | core | `glGetUniformLocation(ILjava/lang/CharSequence;)I` | 2.1.0 |  | uniforms looked up by name after link (20 §2) |
| C57 | `GL20.glUniform1i(II)V` | core | `glUniform1i(II)V` | 2.1.0 |  | sampler units set once with glUniform1i (20 §2) |
| C58 | `GL20.glUniform1f(IF)V` | infer | `glUniform1f(IF)V` | 2.1.0 |  | internal pass scalars |
| C59 | `GL20.glUniform2f(IFF)V` | infer | `glUniform2f(IFF)V` | 2.1.0 |  | internal pass scalars |
| C60 | `GL20.glUniform4f(IFFFF)V` | infer | `glUniform4f(IFFFF)V` | 2.1.0 |  | internal pass scalars |
| C61 | `GL20.glUniform1(ILjava/nio/FloatBuffer;)V` | infer | `glUniform1(ILjava/nio/FloatBuffer;)V` | 2.1.0 |  | user uniform rows (24 §3.3 byte-string uniforms) |
| C62 | `GL20.glUniform2(ILjava/nio/FloatBuffer;)V` | infer | `glUniform2(ILjava/nio/FloatBuffer;)V` | 2.1.0 |  | user vec2 rows |
| C63 | `GL20.glUniform3(ILjava/nio/FloatBuffer;)V` | infer | `glUniform3(ILjava/nio/FloatBuffer;)V` | 2.1.0 |  | user vec3 rows |
| C64 | `GL20.glUniform4(ILjava/nio/FloatBuffer;)V` | infer | `glUniform4(ILjava/nio/FloatBuffer;)V` | 2.1.0 |  | user vec4 rows / palette |
| C65 | `GL20.glUniform1(ILjava/nio/IntBuffer;)V` | infer | `glUniform1(ILjava/nio/IntBuffer;)V` | 2.1.0 |  | user int/bool rows |
| C66 | `GL20.glUniform2(ILjava/nio/IntBuffer;)V` | infer | `glUniform2(ILjava/nio/IntBuffer;)V` | 2.1.0 |  | user ivec2 rows |
| C67 | `GL20.glUniform3(ILjava/nio/IntBuffer;)V` | infer | `glUniform3(ILjava/nio/IntBuffer;)V` | 2.1.0 |  | user ivec3 rows |
| C68 | `GL20.glUniform4(ILjava/nio/IntBuffer;)V` | infer | `glUniform4(ILjava/nio/IntBuffer;)V` | 2.1.0 |  | user ivec4 rows |
| C69 | `GL20.glUniformMatrix2(IZLjava/nio/FloatBuffer;)V` | infer | `glUniformMatrix2(IZLjava/nio/FloatBuffer;)V` | 2.1.0 |  | user mat2 |
| C70 | `GL20.glUniformMatrix3(IZLjava/nio/FloatBuffer;)V` | infer | `glUniformMatrix3(IZLjava/nio/FloatBuffer;)V` | 2.1.0 |  | user mat3 |
| C71 | `GL20.glUniformMatrix4(IZLjava/nio/FloatBuffer;)V` | infer | `glUniformMatrix4(IZLjava/nio/FloatBuffer;)V` | 2.1.0 |  | 3D transforms, user mat4 |
| C72 | `GL30.glGenVertexArrays()I` | core | `glGenVertexArrays()I` | 2.1.5 | Scalar form only (A05 traps) | Vao, scalar form (20 §1 impl. 2, §3) |
| C73 | `GL30.glDeleteVertexArrays(I)V` | core | `glDeleteVertexArrays(I)V` | 2.1.0 |  | Vao, scalar form (20 §3) |
| C74 | `GL15.glGenBuffers()I` | core | `glGenBuffers()I` | 2.1.5 |  | VBO/EBO/PBO ring |
| C75 | `GL15.glBufferData(IJI)V` | core | `glBufferData(IJI)V` | 2.1.5 |  | allocate PBOs and streaming VBOs |
| C76 | `GL15.glBufferData(ILjava/nio/ByteBuffer;I)V` | core | `glBufferData(ILjava/nio/ByteBuffer;I)V` | 2.1.5 |  | upload vertex/index data |
| C77 | `GL15.glBufferSubData(IJLjava/nio/ByteBuffer;)V` | core | `glBufferSubData(IJLjava/nio/ByteBuffer;)V` | 2.1.5 |  | streaming updates |
| C78 | `GL15.glDeleteBuffers(I)V` | core | `glDeleteBuffers(I)V` | 2.1.2 |  | eviction |
| C79 | `GL20.glEnableVertexAttribArray(I)V` | core | `glEnableVertexAttribArray(I)V` | 2.1.2 |  | Vao setup (layout(location)) |
| C80 | `GL20.glDisableVertexAttribArray(I)V` | infer | `glDisableVertexAttribArray(I)V` | 2.1.2 |  | Vao setup |
| C81 | `GL20.glVertexAttribPointer(IIIZIJ)V` | core | `glVertexAttribPointer(IIIZIJ)V` | 2.1.2 |  | long-offset form (20 §3) |
| C82 | `GL30.glVertexAttribIPointer(IIIIJ)V` | opt | `glVertexAttribIPointer(IIIIJ)V` | 2.1.2 |  | integer attributes for internal passes |
| C83 | `GL11.glDrawArrays(III)V` | core | `glDrawArrays(III)V` | all 2.x |  | full-screen and integer-aligned quads (24 §3.7) |
| C84 | `GL11.glDrawElements(IIIJ)V` | core | `glDrawElements(IIIJ)V` | all 2.x |  | indexed 3D, index ranges validated (20 §6) |
| C85 | `GL31.glDrawArraysInstanced(IIII)V` | infer | `glDrawArraysInstanced(IIII)V` | 2.1.40 | Unmapped (straight to the driver) in 2.1.14–2.1.39; harmless (§5.3) | instances-per-draw cap implies instancing (20 §6) |
| C86 | `GL31.glDrawElementsInstanced(IIIJI)V` | infer | `glDrawElementsInstanced(IIIJI)V` | 2.1.5 |  | instanced indexed 3D |
| C87 | `GL33.glVertexAttribDivisor(II)V` | infer | `glVertexAttribDivisor(II)V` | 2.1.5 |  | instanced attributes |
| C88 | `GL33.glGenSamplers()I` | infer | `glGenSamplers()I` | 2.1.5 |  | user sampler state |
| C89 | `GL33.glSamplerParameteri(III)V` | infer | `glSamplerParameteri(III)V` | 2.1.5 |  | user sampler state |
| C90 | `GL33.glDeleteSamplers(I)V` | infer | `glDeleteSamplers(I)V` | 2.1.5 |  | eviction |
| C91 | `GL11.glGetTexImage(IIIIJ)V` | core | `glGetTexImage(IIIIJ)V` | 2.1.12 | NSME in 2.1.5–2.1.11 and no overload covers that range: A01 is NSME in 2.2.0–2.2.21, and A08 cannot run with a pack PBO bound in 2.1.5 (L7). Below 2.1.14 the version gate disables graphics | readback into GL_PIXEL_PACK_BUFFER (20 §3) |
| C92 | `GL15.glGetBufferSubData(IJLjava/nio/ByteBuffer;)V` | core | `glGetBufferSubData(IJLjava/nio/ByteBuffer;)V` | 2.1.5 |  | fetch PBO data (20 §3) |
| C93 | `GL11.glFlush()V` | core | `glFlush()V` | 2.1.5 |  | after qEnd and between chunks (20 §3, §6) |
| C94 | `GL15.glGenQueries()I` | core | `glGenQueries()I` | 2.2.0 | Unmapped (straight to the driver) in 2.1.14–2.1.61; harmless (§5.3) | GpuTimer (20 §3, §6) |
| C95 | `GL15.glDeleteQueries(I)V` | core | `glDeleteQueries(I)V` | 2.2.0 | Unmapped (straight to the driver) in 2.1.14–2.1.61; harmless (§5.3). Scalar form only (A06 traps) | GpuTimer, scalar form (20 §3) |
| C96 | `GL33.glQueryCounter(II)V` | core | `glQueryCounter(II)V` | 2.2.0 | Unmapped (straight to the driver) in 2.1.14–2.1.61; harmless (§5.3) | GL_TIMESTAMP (20 §3) |
| C97 | `GL15.glGetQueryObjecti(II)I` | core | `glGetQueryObjecti(II)I` | 2.2.0 | Unmapped (straight to the driver) in 2.1.14–2.1.61; harmless (§5.3). Use this, not A02 | GL_QUERY_RESULT_AVAILABLE (20 §3) |
| C98 | `GL33.glGetQueryObjectui64(II)J` | core | `glGetQueryObjectui64(II)J` | 2.2.0 | Unmapped (straight to the driver) in 2.1.14–2.1.61; harmless (§5.3). Use this, not A03 | 64-bit timestamp (20 §3) |
| F01 | `GL11.glDisable(I)V` with constant 3042 | core | `disableBlend()V` | all 2.x |  | GL_BLEND constant, folded to disableBlend()V (20 §1) |
| F02 | `GL11.glEnable(I)V` with constant 3042 | infer | `enableBlend()V` | all 2.x |  | GL_BLEND constant, folded to enableBlend()V (blended 2D at T3, 20 §3) |
| F03 | `GL11.glDisable(I)V` with constant 3089 | core | `disableScissorTest()V` | all 2.x |  | GL_SCISSOR_TEST constant, folded to disableScissorTest()V (20 §1) |
| F04 | `GL11.glDisable(I)V` with constant 3008 | core | `disableAlphaTest()V` | all 2.x |  | GL_ALPHA_TEST constant, folded to disableAlphaTest()V (20 §1) |
| F05 | `GL11.glEnable(I)V` with constant 2884 | core | `enableCull()V` | all 2.x |  | GL_CULL_FACE constant, folded to enableCull()V (20 §1) |
| F06 | `GL11.glDisable(I)V` with constant 2884 | core | `disableCull()V` | all 2.x |  | GL_CULL_FACE constant, folded to disableCull()V (20 §1) |
| F07 | `GL11.glEnable(I)V` with constant 2929 | infer | `enableDepthTest()V` | all 2.x |  | GL_DEPTH_TEST constant, folded to enableDepthTest()V (3D, 20 §1) |
| F08 | `GL11.glDisable(I)V` with constant 2929 | infer | `disableDepthTest()V` | all 2.x |  | GL_DEPTH_TEST constant, folded to disableDepthTest()V (2D passes) |
| F09 | `GL11.glDisable(I)V` with constant 2896 | core | `disableLighting()V` | all 2.x |  | GL_LIGHTING constant, folded to disableLighting()V (TESR, 02 §5 step 3) |
| F10 | `GL11.glEnable(I)V` with constant 3089 | infer | `enableScissorTest()V` | all 2.x |  | GL_SCISSOR_TEST constant, folded to enableScissorTest()V (M1 2D scissor op) |
| X01 | `OpenGlHelper.setLightmapTextureCoords(IFF)V` | core | `setLightmapTextureCoords(IFF)V` | 2.0.0-alpha11 | Owner `net/minecraft/client/renderer/OpenGlHelper`. The redirect key is the MCP name; a production (SRG) call is `func_77475_a`, which is absent from the `OpenGlHelper` map in all six releases checked (Verification notes), so vanilla code runs [I] | TESR lightmap 240/240 (20 §4, 02 §5 step 3) |
| X02 | `GL11.glColor4f(FFFF)V` | opt | `glColor4f(FFFF)V` | all 2.x |  | TESR/GUI tint reset |
| R01 | `GL11.glGetInteger(ILjava/nio/IntBuffer;)V` | core | `glGetInteger(ILjava/nio/IntBuffer;)V` | all 2.x | Needed below 2.2.21: `glPopAttrib(GL_SCISSOR_BIT)` does not restore the box there. Use a direct buffer at position 0 and read it with absolute `get(i)` | GL_SCISSOR_BOX save (scissor box not restored by glPopAttrib before 2.2.21) |
| R02 | `GL11.glScissor(IIII)V` | core | `glScissor(IIII)V` | all 2.x | Restores the box saved by R01 before `glPopAttrib` | scissor box restore; M1 2D scissor op |
| R03 | `GL11.glGetBoolean(I)Z` | opt | `glGetBoolean(I)Z` | all 2.x |  | explicit state asserts |
| R04 | `GL11.glGetBoolean(ILjava/nio/ByteBuffer;)V` | opt | `glGetBoolean(ILjava/nio/ByteBuffer;)V` | all 2.x |  | colour mask save |
| R05 | `GL11.glGetFloat(I)F` | opt | `glGetFloat(I)F` | all 2.x |  | explicit state asserts |
| R06 | `GL11.glPolygonOffset(FF)V` | opt | `glPolygonOffset(FF)V` | all 2.x |  | explicit restore |
| R07 | `GL11.glLogicOp(I)V` | opt | `glLogicOp(I)V` | all 2.x |  | explicit restore |
| R08 | `GL20.glBlendEquationSeparate(II)V` | opt | `glBlendEquationSeparate(II)V` | all 2.x |  | explicit restore |
| R09 | `GL11.glStencilFunc(III)V` | opt | `glStencilFunc(III)V` | all 2.x |  | explicit restore / 3D stencil |
| R10 | `GL11.glStencilOp(III)V` | opt | `glStencilOp(III)V` | all 2.x |  | explicit restore / 3D stencil |
| R11 | `GL11.glStencilMask(I)V` | opt | `glStencilMask(I)V` | all 2.x |  | explicit restore / 3D stencil |
| R12 | `GL11.glDepthRange(DD)V` | opt | `glDepthRange(DD)V` | all 2.x |  | explicit restore |
| R13 | `GL11.glDrawBuffer(I)V` | opt | `glDrawBuffer(I)V` | all 2.x |  | FBO draw buffer |
| R14 | `GL11.glReadBuffer(I)V` | opt | `glReadBuffer(I)V` | all 2.x |  | FBO read buffer |

### 4.2 Deny-list (never call)

| ID | Call | alpha1–2.1.4 | 2.1.5–2.1.11 | 2.1.12–2.1.39 | 2.1.40–2.1.61 | 2.2.0–2.2.21 | 2.2.22–2.2.30 | Use instead |
|---|---|---|---|---|---|---|---|---|
| A01 | `GL11.glReadPixels(IIIIIIJ)V` | driver | driver | driver | driver | **NSME** | links | C91 `glGetTexImage(..., long)` |
| A02 | `GL15.glGetQueryObjectui(II)I` | driver | driver | driver | driver | **NSME** | **NSME** | C97 `glGetQueryObjecti(II)I` |
| A03 | `GL33.glGetQueryObjecti64(II)J` | driver | driver | driver | driver | driver | driver | C98 `glGetQueryObjectui64(II)J` |
| A04 | `GL30.glGenFramebuffers(Ljava/nio/IntBuffer;)V` | driver | **NSME** | **NSME** | **NSME** | **NSME** | **NSME** | C35 `glGenFramebuffers()I` |
| A05 | `GL30.glGenVertexArrays(Ljava/nio/IntBuffer;)V` | driver | **NSME** | **NSME** | **NSME** | **NSME** | **NSME** | C72 `glGenVertexArrays()I` |
| A06 | `GL15.glDeleteQueries(Ljava/nio/IntBuffer;)V` | driver | driver | driver | driver | **NSME** | **NSME** | C95 `glDeleteQueries(I)V` |
| A07 | `GL20.glGetShaderInfoLog(ILjava/nio/IntBuffer;Ljava/nio/ByteBuffer;)V` | driver | **NSME** | **NSME** | **NSME** | **NSME** | **NSME** | C47 `glGetShaderInfoLog(II)` |
| A09 | `GL30.glClearBufferu(IILjava/nio/IntBuffer;)V` | driver | driver | driver | driver | driver | driver | a full-screen quad writing 0 (20 §3) |
| A11 | `GL11.glTexImage2D(IIIIIIIILjava/nio/ShortBuffer;)V` | **NSME** | **NSME** | **NSME** | **NSME** | **NSME** | **NSME** | `ByteBuffer` uploads (C31, C32) |
| A12 | `GL32.glFenceSync(II)Lorg/lwjgl/opengl/GLSync;` | driver | driver | driver | driver | driver | driver | timestamp queries (C96, C98); `GLSync` is invisible to the unmapped-call detector (02 §4) |
| A15 | `GL30.glGetInteger(II)I` | driver | **NSME** | **NSME** | **NSME** | links | links | C01 or R01 (`GL11` forms) |
| A16 | `GL30.glUniform1ui(II)V` | driver | driver | driver | driver | driver | driver | `glUniform1i` (C57) or the vector forms |
| A17 | `GL32.glGetInteger64(I)J` | driver | driver | driver | driver | driver | driver | timestamps from queries (C96, C98) |

Three alternatives are acceptable but unused:

- A08, `GL11.glGetTexImage(IIIILjava/nio/ByteBuffer;)V`, links from 2.1.5. It is synchronous, and in 2.1.5 it runs with a pack PBO still bound (`LK L7`).
- A13, `GL20.glGetUniformLocation(ILjava/nio/ByteBuffer;)I`, links from 2.1.0.
- A14, `GL20.glShaderSource(I[Ljava/lang/CharSequence;)V`, links from 2.1.0.

## 5. Behaviour differences that need code or tests

### 5.1 Code rules (unconditional, on every leg)

Each rule is cheap and harmless on newer versions and on vanilla. None of them needs a version branch.

- **R-1 Scissor box.**
  - *Rule:* save the box with `glGetInteger(GL_SCISSOR_BOX, buf)` (R01) right after the pass's `glPushAttrib`. Restore it with `glScissor` (R02) just before `glPopAttrib`.
  - *Why:* up to 2.2.20, `GL_SCISSOR_BIT` restores only the scissor-test enable. The box is only a comment there (`AG@2.2.8:FEAT:292-295`, `AG@2.2.20:FEAT:303-306`), and the fix 44c930f4 first appears in 2.2.21.
  - *Range:* the floor 2.2.8 and RC-1's 2.2.19 both need this.
  - *Consistency:* up to 2.2.20 the query reaches the driver (`AG@2.1.12:GLSM:1030-1046`, `AG@2.2.20:GLSM:1120-1138`) and `glScissor` is not cached (`AG@2.1.12:GLSM:4554-4563`). From 2.2.21 the box is cached (`AG@2.2.21:GLSM:1162`). Both behaviours are consistent with an explicit restore (`CL F5`, VF).
  - *Test:* a hygiene case with a non-default box on the floor's GLSM leg (§6).
- **R-2 glGet into buffers.**
  - *Rule:* use a direct buffer at position 0. Call `clear()` before the query and read the result with absolute `get(i)`.
  - *Why:* before 2.1.16, cached multi-value queries advance the buffer position (ec82280b). In 2.1.12–2.1.15, cached scalar queries write index 0 whatever the position is (`AG@2.1.12:GLSM:1040`, VF).
- **R-3 Drain `glGetError`.**
  - *Rule:* at the start of the START pass, loop until `GL_NO_ERROR`, with a bound. Do this before the "first pass is error-free" check of 20 §7.
  - *Why:* before 2.1.57, Angelica itself issues capabilities that core removed (6570b6a0). Any mod can leave an error pending [I].
- **R-4 TESR state.**
  - *Rule:* inside one `glPushAttrib(ENABLE | COLOR | DEPTH | LIGHTING | TEXTURE)` level (02 §5 step 3), the host TESR changes only three things:
    - FFP lighting (F09);
    - the lightmap coordinates (X01);
    - the unit-0 texture binding (C11).
  - It never changes blend enable or function, alpha test, depth mask, colour mask, or per-unit texture capabilities.
  - *Supporting choice:* the expand pass writes alpha 255 [proposal], so the present texture is opaque and inherited blend or alpha-test state has no visible effect [I].
  - *Why:* below 2.2.8, changes to those states leak under Iris locks (§1.3 b). Below 2.2.12, Iris misread per-unit texture capabilities (32d1c27b).
  - *Bits are emulated:* all five pushed bits are emulated in 2.1.14, 2.2.8 and 2.2.21 (supported list `AG@2.1.14:FEAT:21-24`, LIGHTING at `:216`, TEXTURE at `:325`; `AG@2.2.21:FEAT:16-19`) [V, writer].
  - *START pass:* it lies outside Iris level rendering in every release (§5.4), so Iris locks are not held there [I].
- **R-5 No GL method references.**
  - *Rule:* no method references or lambdas bound to GL static methods, and no GL calls through reflection or `MethodHandle`.
  - *Why:* `invokedynamic` handles are redirected only from 2.1.13 (e65c01c2), and from then on by name with the descriptor kept (`AG@2.2.21:RED:985-1008`, `LK L11`). Plain `invokestatic` calls keep every GL call visible to the linkage test.
- **R-6 Attrib stack.**
  - *Rule:* exactly one `glPushAttrib` level in the START pass and one in the TESR. Never query `GL_ATTRIB_STACK_DEPTH` or `GL_MAX_ATTRIB_STACK_DEPTH`.
  - *Depth:* the stack holds 18 levels up to 2.2.18 and 32 from 2.2.19 (`CL F6`). Overflow throws `IllegalStateException` (`AG@2.1.14:GLSM:3165-3167`; `AG@2.2.21:GLSM:3964`, `:3978`; VF).
  - *Queries:* GLSM answers the two stack queries only from 2.2.29 (6dfb0312, #2211). Before that they reach a core driver and raise an error (`CL` Table A).
- **R-7 GLES detection.**
  - *Rule:* read `RenderSystem.isGLES()` reflectively (20 §2) and treat a missing method as "not GLES".
  - *Why:* the method and the ES profile exist only from 2.2.0 (`AG@2.2.0:glsm/.../RenderSystem.java:387`; ES branch at `AG@2.2.0:src/mixin/java/com/gtnewhorizons/angelica/mixins/early/angelica/MixinForgeHooksClient_CoreProfile.java:52`). `JAR 2.1.61` lacks the method (`CL F14`, VF).
- **R-8 No world-less TESR rendering.**
  - *Rule:* never pass `TileEntityRendererDispatcher` a world-less tile entity whose `blockMetadata` is −1. One example is an item renderer that draws the display block through its TESR.
  - *Why:* before 2.2.23, Angelica's dispatcher mixin calls `te.getBlockMetadata()` at HEAD. 9814a96b replaces that call with a guarded version in `MixinTileEntityRendererDispatcher` and `TesrProviderDispatch` [V, writer]. Vanilla's getter dereferences `worldObj` when the field is −1 (`MC/net/minecraft/tileentity/TileEntity.java:153-161`). The commit title reports an attrib-stack overflow as the result.
  - *Range:* this includes the owner's 2.2.21.
  - *Fix:* draw the display item through the ISBRH chassis's inventory path instead [proposal].
- **R-9 Render bounds** (every 2.x).
  - *Rule:* every display tile entity returns one constant box from its very first `getRenderBoundingBox()` call: the maximal wall extent, whatever its role in a wall. Alternatively, the class returns `INFINITE_EXTENT_AABB` from its first instance onwards.
  - *Why:*
    - The box is cached per instance, keyed on position, block and metadata (`AG@2.1.14` and `AG@2.2.21:src/mixin/.../rendering/MixinTileEntity.java:72-99`, VF).
    - The class is classified on its first call (`CL F8`).
    - DYNAMIC is reachable only through the user's `dynamicBoundsTileEntities` list, with no API (`AG@2.2.21:.../ClientProxy.java:133`, VF).
  - *Consequence:* a block that becomes a wall origin after its first render keeps its old box [I]. M1.5's check that a wall grown after its first render is not culled must cover this case (24 §6).
- **R-10 Eviction order.**
  - *Rule:* detach or delete the FBO before deleting its textures.
  - *Why:* GLSM's `glDeleteTextures` shrinks the texture to 1×1 R8 in place and frees the name only at the next `glGenTextures` (`AG@2.1.14:GLSM:1941-1991`; the same mechanism exists in 2.2.21, VF).
- **R-11 Compile against the floor.**
  - *Rule:* `compileOnly("com.github.GTNewHorizons:Angelica:2.2.8:api")`, so that no newer API is used by accident [proposal]. `Angelica-2.2.8-api.jar` is published (Maven listing of `.../Angelica/2.2.8/`).
  - `@ThreadSafeISBRH` is the same in every 2.x release (`CL F9`).
- **R-12 Overload discipline.** Only the calls in §4.1, never those in §4.2.
- **R-13 Version gate** as described in §1.1.

### 5.2 Inside the supported range (2.2.8 → latest)

| Behaviour | How it changes | Handling | Test |
|---|---|---|---|
| `glPopAttrib(GL_SCISSOR_BIT)` restores the box | no in 2.2.8–2.2.20; yes from 2.2.21 | R-1 | hygiene case at the floor (GLSM leg) |
| Attrib stack depth | 18 up to 2.2.18; 32 from 2.2.19 | R-6 | — |
| Sampler bindings | passed through until 2.2.13; cached per unit from 2.2.14 (a07cecb8) | exact save and restore works with both (`LK L5`) | hygiene case at the floor |
| `GL_SCISSOR_BOX` query | driver until 2.2.20; cache from 2.2.21 | none | — |
| `glReadPixels(IIIIIIJ)V` | NSME until 2.2.21 | deny-listed (A01) | linkage test |
| Other mods' batched TESRs leak state; world-less TESR item renderers | fixed in 2.2.23 (#2162, #2159) | R-4 (the TESR sets what it needs explicitly), R-8 | in-game smoke next to other TESRs [I] |
| Legacy stack queries | reach the driver until 2.2.28 | R-6 | — |
| SDL-GPU backend | opt-in (`-Dangelica.sdlgpu.enable`, default false, `AG@2.2.21:glsm/.../config/SystemProperties.java:19-20`, VF); selectable in video settings from 2.2.29 | out of scope: it needs lwjgl3ify, which decision 9 does not design for. Unmapped calls crash there; the never-mapped rows A03, A09, A12, A16 and A17 are already deny-listed | — |
| Display lists with push/pop | broken only in 2.2.18 | OpenGPU uses none | — |

### 5.3 Only in the best-effort band (2.1.14–2.2.7)

- **Iris override leak below 2.2.8:** handled by R-4.
- **Release status:** 2.2.0 is a pre-release, and no manifest ships any of 2.2.1–2.2.7.
- **Pass-through calls below 2.2.0** (§3). Eleven planned calls reach the driver directly in 2.1.14–2.1.39, and ten in 2.1.40–2.1.61:
  - the five renderbuffer calls;
  - the five timer-query calls;
  - `glDrawArraysInstanced`, which passes through only before 2.1.40.

  This is harmless. GLSM in those releases has no query or renderbuffer methods and never caches `GL_RENDERBUFFER_BINDING` (`javap` of `JAR 2.1.14` and `JAR 2.1.61`; `AG@2.1.12:GLSM:982-1019`; `LK L4`, `CL F4`, VF). Nothing is logged, because the unmapped-call detector arrives only in 2.2.0.
- **Instanced draws skip `preDraw` below 2.2.0** (`AG@2.1.12:GLSM:2261`). `preDraw` does three things:
  - it flushes generic attribute defaults, which matter only for disabled arrays;
  - it flushes Iris's deferred blend;
  - it uploads compat uniforms for FFP-transformed programs (`AG@2.1.12:glsm/.../ffp/ShaderManager.java:93-119`, VF).

  Skipping it is therefore harmless for OpenGPU's `#version 330 core` programs with enabled arrays, drawn outside Iris passes.
- **Iris shadow pass below 2.2.0.** It re-renders every visible TESR, including those of blocks with an ISBRH chassis (`AG@2.1.61:src/main/java/net/coderbot/iris/pipeline/ShadowRenderer.java:569-615`). `shadowSkipInMeshTileEntities` exists only from 2.2.0 (`AG@2.2.0:CFG:337-348`, `CL F12`). The TESR is idempotent (20 §4), so the cost is one extra draw, and the panel casts a shadow.
- **Other gaps below 2.2.0:** there is no GLES profile (R-7). The TESR batching API is absent, and OpenGPU does not use it. STATIC tile-entity boxes are not baked into section data; no action is needed.
- **DSA texture path below 2.1.21.** `glTexImage2D(..., ByteBuffer)` may take GLSM's DSA path (`AG@2.1.14:GLSM:1841`, branch at `:1852`; no `shouldUseDSA` remains in `AG@2.1.21:GLSM`) [V, writer]. `changeFormatIfDeprecated` remaps only ALPHA formats, so R8UI and R16UI pass through unchanged (`AG@2.1.14:GLSM:1763-1771`) [V, writer]. Allocating integer textures through the DSA path is untested [I].
- **Below 2.1.16 and below 2.1.57:** handled by R-2 and R-3.
- **Angelica's own Java 8 stability in 2.1.x.** Fixes for Java 8 / LWJGL 2 landed in 2.1.13 ("FINE, we'll work with java8/forge", #1606), 2.1.14 (#1618), 2.1.15 (#1630), 2.1.17 (#1670) and 2.1.29 (#1804) (REL). Whether 2.1.14 runs at all on the Java 8 leg is untested [I].

### 5.4 The same in every 2.x release (no branch needed)

- **The START pass lies outside Iris.**
  - No Angelica release issues GL calls at `RenderTickEvent(START)`. Angelica's only START subscriber is `Zoom.onRenderTick`, which updates the zoom factor arithmetically (`AG@2.2.21:src/main/java/com/gtnewhorizons/angelica/zoom/Zoom.java:115-119`). That subscriber exists at 2.2.1 and is absent at 2.1.14 (VF correction to `CL F11`).
  - Iris begins level rendering after START and finalizes it before `dispatchRenderLast`. This holds in every sampled release from 2.1.0 to 2.2.30 (`CL F11`).
- **Shaders.** The FFP transformer leaves `#version 330 core` sources untouched, and `glUseProgram(non-zero)` deactivates FFP (`AG@2.1.14:glsm/.../CompatShaderTransformer.java:117-126`, `:444-445`; `AG@2.2.21:...:167-174`; `CL F13`, VF).
- **Attrib bits.** Every bit that OpenGPU pushes is emulated: the seven of the START pass and the five of the TESR (§5.1 R-4).
- **Binding caches.** The bindings that `glGetInteger(int)` answers from its cache are always set by calls that the same release rewrites (`LK L5`). OpenGPU's exact save and restore therefore never reads a stale cache. The caches also skip redundant binds (`AG@2.1.14:GLSM:5068-5075`, `:1670`), so this holds only while all code keeps them honest (VF).
- **Mod identity.** `@ThreadSafeISBRH` is unchanged, and the modid is always `angelica` (`CL F9`).
- **Java 17/21.** The multi-release Java 17/21 copies of `GLStateManager` declare the same public static methods as the base class in every jar (`LK L9`). The verifier did not re-check this, and decision 9 does not design for lwjgl3ify.

## 6. Test matrix

| Angelica | Role | CI linkage test (gating) | CI `-Dangelica.unmappedGL=FAIL` run | CI GLSM render-core leg (23 §2.3) | In-game smoke with an Iris pack | GPUs |
|---|---|---|---|---|---|---|
| 2.1.14 | best-effort canary | yes | — (no detector before 2.2.0) | — | — | — |
| **2.2.8** | **floor** | yes | yes | yes, including the R-1 hygiene case | yes, each milestone, `runClient` with the dev jar pinned | any one |
| 2.2.21 | owner's instance | yes | yes | — | yes, each milestone, in the owner's instance | Intel, NVIDIA and AMD for the M0 measurements (24 §7 answer 7) |
| 2.2.28 | pack (2.9.0-RC-2) | yes | yes | yes | yes, before each release | any one |
| latest (2.2.30 today) | latest | yes, version taken from `MVN` `<release>` | yes | — | non-gating `runClient` (24 risk 2) | any one |
| none | vanilla leg | — | — | the compat leg (23) | yes, each milestone, without a shader pack (Iris needs Angelica) | as for 2.2.21 |

Notes:

- **Running the floor in game.** The floor runs through `runClient` with the Angelica dev jar pinned by a Gradle property. `Angelica-2.2.8-dev.jar` and `Angelica-2.1.14-dev.jar` are published (Maven listings of `.../Angelica/2.2.8/` and `.../2.1.14/`) [V]. The owner's instance stays on 2.2.21.
- **GLSM CI leg.** 23 §8 pins this leg to 2.2.21. It moves to the floor and the pack version [proposal]; the owner's 2.2.21 stays covered in game.
- **What each in-game smoke checks**, per version:
  - the pattern and the sprite are visible, and the self-test readbacks are byte-identical (24 §6 M0);
  - the hygiene check is clean;
  - the panel renders correctly next to other mods' TESRs, under a shader pack that overrides blend for block entities. Which pack to use is decided in M0 [I].
  - From M1.5 on, a 3 × 2 wall that crosses a chunk-section boundary is also checked.
- **New Angelica releases.** The gating linkage test picks up each new release as "latest". A failure there is the signal to update §4 (24 risk 1).

## 7. GTNH pack coverage

| GTNH release (manifest date) | Angelica | OpenComputers | Under §1.1 | Evidence |
|---|---|---|---|---|
| 2.6.0 – 2.8.4 (the stable line today) | 1.0.0-alpha40 … 1.0.0-beta66b | 1.10.11 – 1.11.20 | graphics unavailable (Angelica 1.x is below the gate); OC 1.10/1.11 support not studied | DAX 2.6.0 … 2.8.4 (`CL` Table D) |
| 2.9.0-beta-1 (2026-06-07) | 2.1.32 | 1.12.44 | best-effort | DAX 2.9.0-beta-1 |
| experimental (2026-06-14) | 2.1.37 | 1.12.46 | best-effort | DAX experimental |
| 2.9.0-beta-2 (2026-07-05) | 2.1.50 | 1.12.48 | best-effort | DAX 2.9.0-beta-2 |
| 2.9.0-beta-3 (2026-09-06) | 2.2.10 | 1.12.61 | **supported** | DAX 2.9.0-beta-3 |
| 2.9.0-RC-1 (2026-09-24) | 2.2.19 | 1.12.62 | **supported** (needs R-1) | DAX 2.9.0-RC-1 |
| 2.9.0-RC-2 (2026-10-04) | 2.2.28 | 1.12.64 | **supported, tested** | DAX 2.9.0-RC-2 (manifest commit b62320b074) |
| daily (2026-10-08) | 2.2.30 | 1.12.64 | **supported, tested as latest** | DAX daily |
| 2.9.0 final | not released as of 2026-10-08 | — | expected ≥ 2.2.28 [I] | GT-New-Horizons-Modpack releases API: RC-2 followed only by nightlies (`CL F15`, VF) |

OC 1.12.x first appears together with Angelica 2.x, from 2.9.0-beta-1 onwards (`CL` Table D). A player on OC 1.12.64 therefore normally has Angelica 2.2.28 or later, unless they have mixed versions as the owner has.

## 8. Open questions for the owner

1. **Policy.** Is this policy acceptable: 2.2.8 supported, 2.1.14–2.2.7 best-effort with a log warning, and everything older gated off? *Default: yes.* There are two alternatives:
   - (a) Make 2.1.14 first-class. The floor column of §6 becomes 2.1.14, 2.2.8 joins the linkage list, and every milestone gains an in-game Iris run on the 2.1 generation.
   - (b) Drop the band. Graphics become unavailable below 2.2.8, the canary goes away, and rules R-2, R-3 and R-5 remain only as hygiene.
2. **Floor in game.** Should the floor be tested in game through `runClient` with the 2.2.8 dev jar pinned, while your instance stays on 2.2.21? *Default: yes.*
3. **Angelica 1.x (GTNH up to 2.8.4, the current stable line).** *Default: graphics unavailable.* Revisit this only if OpenGPU is to support OC 1.11, which 2.8.x ships (1.11.20).
4. **OC floor.** The packs in the band ship OC 1.12.44, 1.12.46 and 1.12.48. If OpenGPU's own OC floor ends up at 1.12.61 or later, the band covers no GTNH pack, and 1(b) becomes the simpler choice. *Default: decide together with the OC floor.*

## 9. Corrections to earlier reports

These reports are not edited here; apply the corrections when each is next amended.

- **02 Summary 1 (line 7).** The core context dates "since 2.2.x"; it actually dates from 2.1.0 (d7d2228a, #1412; VF).
- **02 §2 "Blend state" (line 45).** `glPushAttrib(GL_COLOR_BUFFER_BIT)` restores blend safely under Iris only from 2.2.8 (§1.3).
- **02 §1, §5 and 20 §4.**
  - The attrib-stack depth values "18 in 2.2.8, 32 in 2.2.21" are correct; the change itself happened in 2.2.19 (53776684).
  - The shadow-pass skip `shadowSkipInMeshTileEntities` exists only from 2.2.0.
- **20 §1, state table.** `GL_SCISSOR_BIT` restores the scissor box only from 2.2.21. Save and restore the box explicitly (R-1).
- **20 §3, "The path that works on 2.2.8, 2.2.21 and later".** `glGetTexImage(IIIIJ)V` exists from 2.1.12 (52b0db25).
- **20 §7 and implication 3; 24 §3.7 and §6 M0.** These say the call set must link against "≥ 2.2.21", set the Angelica floor at 2.2.21 "linkage-tested with 2.2.28 and the latest release", and head the table column "Angelica 2.2.21+". Replace all three with §1.1 and §6: the floor is 2.2.8, and the linkage jars are 2.1.14, 2.2.8, 2.2.21, 2.2.28 and the latest release.
- **23 §8.** The GLSM leg comes from `Angelica:2.2.21:dev`; change it to the floor plus the pack version (§6).
- **09 risk 4.**
  - The TESR API went from v1 to v2 in 2.2.15 (3876f7e6, #2104), and the change is additive.
  - The 2.2.23 leak fixes are #2162 and #2159 (`CL`).
- **README index.** Add this report.

## Sources

- **Angelica source.**
  - The full clone `angelica-git` with all tags (HEAD 78abc057), for every `AG@` citation.
  - Commits: 6a7c2520, 72b13543, d7d2228a, 52b0db25, e65c01c2, 951be04e, ec82280b, 9c1b6c75, 6570b6a0, 8da561d3, 22c94799, 32d1c27b, a07cecb8, 3876f7e6, 53776684, 44c930f4, 7bb5bfd4, 1f274a64, 9814a96b and 6dfb0312.
  - Clones of 2.2.21 (`Angelica-2.2.21`, a8c29fa) and master (`Angelica-master`), which 20 used.
- **Angelica binaries.**
  - Release jars `JAR <v>` for all 118 2.x releases, plus 1.0.0-beta29 and 1.0.0-beta66b.
  - `MVN`: `https://nexus.gtnewhorizons.com/repository/public/com/github/GTNewHorizons/Angelica/maven-metadata.xml`.
  - Maven directory listings for 2.1.14 and 2.2.8, which show the `-api` and `-dev` jars: `https://nexus.gtnewhorizons.com/service/rest/repository/browse/public/com/github/GTNewHorizons/Angelica/<v>/`.
  - The installed `C:\Games\Minecraft\instances\Main\minecraft\mods\angelica-2.2.21.jar`, whose SHA-1 is identical to `JAR 2.2.21` (`LK`).
- **Angelica releases.** The GitHub release pages `REL <v>` for every 2.x release, and the releases API dump in `angelica-floor\web\rel{1,2,3}.json` (pre-release flags, dates and bodies).
- **GTNH packs.** The `DAX` manifests from 2.6.0 to daily and experimental, and `https://api.github.com/repos/GTNewHorizons/GT-New-Horizons-Modpack/releases`.
- **LWJGL.** `C:\Games\Minecraft\libraries\org\lwjgl\lwjgl\lwjgl\2.9.4-nightly-20150209\lwjgl-2.9.4-nightly-20150209.jar` (`javap -s`, in `LWK\lwjgl294.txt` and `angelica-floor\lw\`).
- **Forge and Minecraft.** `MC/cpw/mods/fml/common/Loader.java:246-260`, `:728`; `MC/cpw/mods/fml/common/versioning/VersionParser.java:63`; `MC/net/minecraft/tileentity/TileEntity.java:153-161`.
- **Agent artifacts.**
  - `CL` (changelog notes, Tables A–E).
  - `LWK` (`simclinit.py`, `evaluate.py`, `class-matrix.tsv`, `matrix-full.tsv`, `pnames.tsv`, `traps-all.json`).
  - `angelica-floor\scripts\` (`linkage.py`, `trapcount.py`, `reltable.py`).
  - `VCHK`.
  - `F25` (`callset.tsv`, `evaluate25.py`, `out.json`, `gen.py`, `gen2.py`).
- **Earlier reports.** 02, 09, 20, 23 and 24 in this directory.

## Verification notes

A verifier checked all 27 findings from the two analysts, and the writer then re-ran the checks listed at the end of this section. The changes are listed here.

**Verifier verdicts.**

- **Confirmed** (24 findings): `CL` F1–F10 and F12–F16, and `LK` L1–L8 and L11. Corrections came attached to F5, F8, L4 and L5 (listed below).
  - For `CL F3` and `LK L8` the verifier did not recompute the descriptor-trap totals; it checked sample rows only.
  - For `LK L6`, the interaction of `glEnableVertexAttribArray`/`glVertexAttribPointer` with the VAO in 2.1.0–2.1.1 stays [I]. It lies below the gate either way.
- **Modified:** `CL F11`.
  - Angelica does subscribe to `RenderTickEvent` START: `Zoom.onRenderTick` calls `updateZoomLerp()` in 2.2.1 and 2.2.21.
  - That handler does arithmetic only, so the conclusion stands: no GL state is touched at START (§5.4).
- **Corrections applied:**
  - **`CL F5`.** The scissor-box workaround is needed from 2.2.1 to 2.2.20, so a 2.2.x floor does not remove it. Applied as R-1, which is required at the floor 2.2.8.
  - **`CL F8`.**
    - DYNAMIC bounds can be set only through the user's config list; there is no API.
    - The per-instance cache is keyed on position, block and metadata.
    - Applied as R-9.
  - **`LK L4`.** The [I] about instanced draws skipping `preDraw` was narrowed to "harmless for core programs with enabled arrays outside Iris passes". Applied in §5.3.
  - **`LK L5`.** The cache guarantee depends on all code keeping the caches honest, because GLSM skips redundant binds. Applied in §5.4.
- **Unverifiable:**
  - **`LK L9`** (Java 17/21 multi-release classes) is kept and marked as not re-verified.
  - **`LK L10`** (`setLightmapTextureCoords`). The writer checked the map half: the simulated `OpenGlHelper` maps of 2.0.0-alpha1, -alpha11, 2.1.14, 2.2.8, 2.2.21 and 2.2.30 contain `setLightmapTextureCoords` but not `func_77475_a` (`LWK\red\<v>.json`) [V]. That a production call therefore reaches vanilla code stays [I] (X01 note).
- **New findings from the verifier**, all applied:
  - **The Iris override leak before 2.2.8.** This moved the floor from the analysts' 2.1.14 or 2.2.1 to 2.2.8 (§1.3) and led to R-4.
  - **The restore calls outside `LK`'s set.** These became R01–R14, which the writer re-ran over all 118 releases. All link in every release.
  - **Two glGet buffer details.**
    - In 2.1.12–2.1.15, cached scalar glGet into a buffer writes index 0.
    - Multi-value cached glGets advance the buffer position before 2.1.16.
    - Both are covered by R-2.
  - **Deferred `glDeleteTextures`.** Covered by R-10.
  - **SDL-GPU is opt-in** (§5.2).
  - **`glEnable`/`glDisable` routing in 2.1.14.** The generic calls for dither, logic op, polygon offset and stencil go to GLSM's state stacks, and `GL_RASTERIZER_DISCARD` goes to the backend. This is consistent with only asserting that rasterizer discard is off.
  - **The date in 02 line 7** (§9).

**Writer's own checks** [V, all in the files cited above]:

- **Commit 22c94799** first appears in 2.2.8. `AG@2.2.7:BSS` has no vanilla layer, and `AG@2.2.8:BSS:82-88` restores through it.
- **The 2.1.14 deferral sites** under Iris locks are at `AG@2.1.14:GLSM:1176`, `:1192`, `:1230`, `:1305`, `:1409` and `:1558`.
- **The pack-PBO cache** starts in 2.1.6, which resolves the disagreement between `CL` and `LK` (§1.3).
- **Attrib bits.** `FEAT` supports every attrib bit, LIGHTING and TEXTURE included, in 2.1.14, 2.2.8 and 2.2.21. In 2.2.8 the scissor box is still only a comment, and `MAX_ATTRIB_STACK_DEPTH` is 16 + 2 (`AG@2.2.8:GLSM:216`).
- **Mod version strings.** The `mcmod.info` version strings of `JAR 2.1.14`, `2.2.8` and `2.2.21` match their releases. `AngelicaMod` declares `version = Tags.VERSION`.
- **FML version checks.** FML 1.7.10 enforces the versions of soft dependencies (`Loader.java:246-260`), which is why the gate is a runtime check.
- **Class files.** `GLStateManager` and `GLSMRedirector` are class major 52 in `JAR 2.1.14`, `2.2.8`, `2.2.28` and `2.2.30`.
- **Maven artifacts.** The `-dev` and `-api` jars exist for 2.1.14 and 2.2.8.
- **Commit diffs.**
  - 32d1c27b (#2072) touches only Iris's texture-unit listener.
  - 9814a96b (#2159) guards `getBlockMetadata()` for world-less tile entities.
  - Vanilla `TileEntity.getBlockMetadata()` dereferences the world when the field is −1.
- **DSA path in 2.1.x.** It sits in `AG@2.1.14:GLSM:1852` and is gone from 2.1.21. `changeFormatIfDeprecated` (`:1763-1771`) leaves R8UI and R16UI alone.
- **Extended call set.** All 141 rows, 124 planned and 17 alternatives, have descriptors present in LWJGL 2.9.4. The extended evaluation over 118 releases reproduces `LK`'s 11 version classes, with 53 calls linking everywhere and 124 linking from 2.2.0 (`F25\out.json`).
