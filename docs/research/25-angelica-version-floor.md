# 25 — Angelica version floor

*Status, 2026-10-13: policy changed. The owner, who had delegated the floor (24 §7 answer 8), accepted §8 Q4's recommendation, option 1(b): the best-effort band is dropped, and 2.2.8 is the only threshold. Graphics need Angelica 2.2.8 or later; any older Angelica (2.x below 2.2.8, every 1.x) reports graphics unavailable, compute unaffected. The analysis below stays as the record; the policy statements that changed are marked in place, and [Amendments after the owner's answers (2026-10-13)](#amendments-after-the-owners-answers-2026-10-13) lists every change.*

*Status, 2026-10-14: corrections from the readiness check for M0 (2026-10-13; workflow run wf_42fc3d4f-f30) and the owner's answers of 2026-10-14. Two releases followed this report's evidence: 2.2.31 (2026-10-11, tag 69428c1) and 2.2.32 (2026-10-13, tag 0cae2be), and `MVN` now names `<release>2.2.32</release>` (lastUpdated 2026-10-13 05:05 UTC). The link evidence below stops at 2.2.30 (tag 159f7ff), so ranges such as "2.2.0 to 2.2.30" state what was checked; 2.2.31 and 2.2.32 are unchecked, and M0's linkage test, which takes "latest" from `<release>` (§6), is the first check of a release after 2.2.30. The owner reports that Angelica has fixed the long-standing bug that kept them off the latest stable release: the owner's instance ran a 2.2.31 preview from 2026-10-09 and has run 2.2.32 since 2026-10-13 (its jar's SHA-1, 4106bdbc…, matches Nexus 2.2.32), and it is now §6's in-game latest leg. 2.2.21 stays in the CI linkage test and the `-Dangelica.unmappedGL=FAIL` run, with no in-game leg. The owner also decided that only OpenGPU's own failures in the in-game legs block a release (§6 notes). Tag commits: 2.2.8 = 6d8cbf9, 2.2.21 = a8c29fa, 2.2.28 = 0a4d489, 2.2.30 = 159f7ff, 2.2.31 = 69428c1, 2.2.32 = 0cae2be. Changed in place on 2026-10-14: the scope paragraph, Summary 1 and 6, §2's 2.2.30 row, R-8's range, §6's matrix and notes (the test shader pack included), §7's closing sentence and §8 Q2; one note was appended to the 2026-10-13 amendments.* *Update 2026-10-24 (M0 step S9): "latest" is resolved at build time from `MVN`'s `<release>`, which as of 2026-10-24 names 2.2.36 (since 2026-10-22, tag e84c8c8); S9's linkage test checked 2.2.8, 2.2.21, 2.2.28 and 2.2.36 that day, and the owner's instance ran 2.2.36. 2.2.31 and 2.2.33–2.2.35 stay unchecked, and 2.2.32 passed when forced locally (the S9 amendments at the end).*

Writer, 2026-10-09 (inputs gathered 2026-10-08). This report answers the owner's question 8 (24 §7): which is the lowest Angelica 2.x release that OpenGPU can support as an optional but first-class dependency? It merges the findings of three agents with checks the writer re-ran:

- the changelog analyst (findings `CL F1`–`F16`);
- the linkage analyst (`LK L1`–`L11`);
- a verifier (`VF`).

Scope: Java 8 + LWJGL 2.9.4 + Forge 10.13.4.1614, and the client GL path of 20 §1–§4 and 24 §3.7. Only the owner's 2.2.21 has ever run in game. Every statement about any other version is static: it rests on source, `javap` and simulated redirector maps. *2026-10-14: this describes the research period; the owner's instance has since moved to the latest release (2.2.32 since 2026-10-13), on which nothing in this report rests.* *2026-10-24: the instance runs 2.2.36, and the S9 amendments at the end add OpenGPU's own linkage results on 2.2.8, 2.2.21, 2.2.28 and 2.2.36, also out of game: each jar's own redirector run over `:gl`'s bytecode, with no GL context.*

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

1. **Recommended floor: Angelica 2.2.8** (2026-08-19). It is supported, linkage-tested in CI and smoke-tested in game. It covers 2.2.21 (the owner's release during the research), the 2.2.28 shipped in the 2.9.0-RC-2 pack, the latest 2.2.30 (as of 2026-10-08; 2.2.32 since 2026-10-13, which the owner's instance ran before 2.2.36; *2026-10-24:* the latest release is resolved at build time from `MVN`'s `<release>`, 2.2.36 that day, released 2026-10-22, which S9's linkage test checked and the instance runs, §6), and every GTNH manifest that ships Angelica 2.2.x (§7).
2. **The lowest release OpenGPU's code could run on is 2.1.14.** 2.1.12 is the hard linkage minimum, and 2.1.5–2.1.11 crash on the readback call. *Policy since 2026-10-13: below 2.2.8 a runtime version gate reports graphics as unavailable (§1.1).* Until then this report proposed a best-effort band, 2.1.14–2.2.7, with graphics on, a logged warning and a CI linkage canary but no in-game tests, and gated off only releases below 2.1.14; the owner dropped the band (§8 Q4).
3. Why 2.2.8: it is the first release whose `glPopAttrib` undoes the blend, alpha-test, depth-mask and colour-mask changes a TESR makes while Iris holds an override lock (22c94799, verified in code). Making 2.1.14 first-class would add a second GLSM generation to every milestone's in-game tests, only to cover superseded 2.9.0 pre-releases (§1.2).
4. All 124 planned calls link (the descriptor is identical) in every release from 2.2.0 to 2.2.30. Nothing needs 2.2.22 or later. 53 calls link in all 118 releases (§3).
5. One workaround is mandatory inside the supported range: save and restore the scissor box explicitly. `glPopAttrib` does not restore it before 2.2.21, and RC-1's 2.2.19 is one of those releases. The other rules in §5.1 apply unconditionally; they are cheap and harmless on newer versions and without Angelica.
6. Test matrix (§6): the CI linkage test runs against 2.2.8, 2.2.21, 2.2.28 and the latest release (the 2.1.14 canary was dropped with the band on 2026-10-13). The in-game smoke test with an Iris pack (OpenGPU's own test pack) runs on 2.2.8, 2.2.28 and, in the owner's instance, the latest release (2026-10-14: 2.2.21 keeps only its CI runs).
7. No stable GTNH release ships Angelica 2.x yet. GTNH 2.8.4 ships 1.0.0-beta66b, which falls below the gate (§7, Q3).

## 1. Decision

### 1.1 Policy [proposal]

| Angelica present | Status | OpenGPU behaviour |
|---|---|---|
| none | vanilla leg | as in 20 (needs a GL ≥ 3.3 compatibility context) |
| ≥ 2.2.8 | **supported** | graphics on |
| 2.1.14 – 2.2.7 | best-effort, **dropped 2026-10-13** | now unsupported, as the next row; the band had graphics on and one log line, "Angelica <v> is below the tested floor 2.2.8" |
| < 2.2.8 (< 2.1.14 until 2026-10-13), every 1.x included | unsupported | graphics unavailable, with that reason; compute unaffected, exactly as for GL < 3.3 or the GLES profile (24 §4) |
| version string that does not parse | treated as supported | logged once; *2026-10-26 (S12):* "does not parse" means that the version, trimmed, does not start with digits and dots, since FML's `ComparableVersion` orders every string and would put `@VERSION@` below the floor; the `GlCaps` line then reads `angelica="<v>" (does not parse, treated as supported)` (Q-S12-5 (a)) |

**Gate mechanism.**

- Read the version with `Loader.instance().getIndexedModList().get("angelica").getVersion()` (`MC/cpw/mods/fml/common/Loader.java:728`).
  - Angelica declares `version = Tags.VERSION` (`AG@2.2.21:src/main/java/com/gtnewhorizons/angelica/AngelicaMod.java:16-19`).
  - Its `mcmod.info` carries the plain release number: `"version": "2.2.21"`, `"2.2.8"` and `"2.1.14"` in `JAR 2.2.21`, `JAR 2.2.8` and `JAR 2.1.14` [V].
- Compare it with `VersionParser.parseRange("[2.2.8,)")` (`MC/cpw/mods/fml/common/versioning/VersionParser.java:63`). Until 2026-10-13 a second range, `"[2.1.14,)"`, marked the band; one range is now the whole gate.
- Run the check once at client init and feed the result into the `GlCaps` line (20 §7).
- If OpenGPU declares `after:angelica` for load ordering, give it no version range. *2026-10-26 (S12):* it declares none: `@Mod` keeps only the OC range, and `GlCaps` runs the gate in `ClientProxy.init`, after every mod is constructed (the S12 amendments).
  - FML 1.7.10 also checks the versions of soft dependencies, and it throws `MissingModsException` when one is out of range (`MC/cpw/mods/fml/common/Loader.java:246-260`).
  - An optional dependency would then stop the game from starting.

**"Supported" and "best-effort".**

- *Supported* means three things: the version is in the gating CI linkage test, it gets an in-game smoke test with an Iris pack at each milestone, and its bugs get fixed.
- *Best-effort* (dropped 2026-10-13; kept here as the record) meant two things:
  - The code rules of §5.1 keep the band working by construction, and the 2.1.14 canary keeps it linking in CI.
  - Nothing in the band is run in game.

### 1.2 Candidates

| Candidate | What choosing it brings | Costs | Verdict |
|---|---|---|---|
| 2.1.12 | the hard linkage minimum: the readback overload `glGetTexImage(IIIIJ)V` exists (52b0db25, #1592, first tag 2.1.12; `AG@2.1.12:GLSM:5817-5819`) | (1) until 2.1.14 a TESR whose box crosses its 16³ section is culled with that section (951be04e, #1607), so M1.5 walls would vanish. (2) GL method references are not redirected before 2.1.13 (e65c01c2) | below the gate |
| 2.1.14 (the changelog analyst's floor) | adds 2.9.0-beta-1 (2.1.32), 2.9.0-beta-2 (2.1.50) and the "experimental" manifest (2.1.37), all of them superseded pre-releases (§7) | a second GLSM generation (§5.3): (1) the era-2 redirector (`LK L1`) with 11 planned calls bypassing GLSM (§3); (2) no unmapped-call detector; (3) DSA texture paths until 2.1.21; (4) the Iris leak fixed in 2.2.8; (5) the TESR redraw in the shadow pass; (6) Java 8 crash fixes in Angelica itself until 2.1.29 | best-effort band; below the gate since 2026-10-13 |
| 2.2.1 (both analysts' conservative alternative) | the first 2.2 release that is not a pre-release (2.2.0 has `prerelease: true`, `LK L3`); every planned name goes through GLSM | the Iris leak fixed in 2.2.8; no GTNH manifest ships any of 2.2.0–2.2.7 (`CL` Table D) | inside the band; below the gate since 2026-10-13 |
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

All three inputs agree on the facts; they differed only on the level to declare. The band and its canary meet the owner's wish for the lowest workable version. (2026-10-13: the owner dropped the band; §8 Q4.)

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
| 2.2.30 (2026-10-07) | the latest release as of 2026-10-08 (`<release>2.2.30`); 2.2.32 since 2026-10-13 | — | MVN; master has only a README commit after it (`CL`) | latest as of 2026-10-08 |

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
- **F01–F10:** `glEnable`/`glDisable` with a constant capability. The redirector folds these into no-argument methods. The fold list is the same at 2.1.12, 2.2.0, 2.2.21 and 2.2.30 (VF on `LK L1`), so the test must see the constant as well as the descriptor. *2026-10-24 (S9):* `glCapRedirects` holds the same nine folds at 2.2.8, 2.2.21, 2.2.28, 2.2.32 and 2.2.36, and `glsmLinkageTest` reads each jar's own set.
- **X01–X02:** the TESR extras, one of which has a non-GL owner.
- **R01–R14:** state queries and restores. The verifier checked them in six jars (`VCHK`), and the writer ran them over all 118 releases. R01 and R02 are mandatory (rule R-1).

**Columns.**

- *Owner:* `GL11` means `org/lwjgl/opengl/GL11`, and so on. The owner of X01 is `net/minecraft/client/renderer/OpenGlHelper`.
- *Descriptor:* every LWJGL descriptor exists in `javap -s` of `lwjgl-2.9.4-nightly-20150209.jar` (`LWK\lwjgl294.txt`; `LK`; the writer re-checked every row in `F25`).
- *Links from:* the first release from which the call links in every later release up to 2.2.30.

**What the test asserts** [proposal, refining 20 implication 3]:

1. **Allowlist.** Take every `invokestatic` in OpenGPU's client classes whose owner is under `org/lwjgl/opengl/` or is `OpenGlHelper`. Each must match a row of §4.1 by owner, name and descriptor. For `glEnable`/`glDisable`, the folded constant must match too. A new GL call needs a new row and a fresh run of the matrix. *2026-10-26 (M0 step S12):* steps 1-2 over the root's classes are built as `jarTest`'s check 10, over every OpenGPU class but `:gl`'s in both shipped jars, against the rows file `clientcalls.txt`; the S12 amendments at the end record it.
2. **Deny-list.**
   - No call matches §4.2.
   - No descriptor mentions `GLSync`.
   - No `invokedynamic` bootstrap argument is a method handle to `org/lwjgl/opengl/*` (R-5).
3. **Linkage.** Repeat this step for each jar in §6. *2026-10-24 (M0 step S9):* built the first way, as `:gl-testkit:glsmLinkageTest`, together with step 4; the S9 amendments at the end record it.
   - Apply that jar's own redirect rule, in one of two ways. Either load its `GLSMRedirector` in an isolated class loader and transform OpenGPU's classes with it, or evaluate its maps the way `LWK` does. `GLSMRedirector` is Java 8 bytecode (class major 52) in `JAR 2.1.14`, `2.2.8`, `2.2.28` and `2.2.30` [V, writer].
   - Then assert that every rewritten target is a public static method of that jar's `GLStateManager` with an identical descriptor.
4. **Expected pass-through.** On 2.1.14, exactly the 11 driver rows of §3 pass through to the driver; on 2.2.x, none do. Any other unmapped call fails the test. *(2026-10-13: 2.1.14 left §6's linkage jars with the band, so only the 2.2.x clause applies, and the era-2 question below no longer arises.)* *2026-10-26 (S12):* on 2.2.x the root's P01, `GLContext.getCapabilities()`, passes through by design, and L2 holds it to `GlCaps`; any other unmapped call still fails, but for X01's SRG name `func_77475_a` in the release jar, which GLSM's map lacks and the test leaves to vanilla's body, whose own GL call GLSM rewrites (the S12 amendments).

It is still [I] whether the redirector's transform entry point can be called the same way in era 2 and era 3. M0 settles this. The map-evaluation fallback works for both eras (`LK`). *2026-10-24 (S9):* settled for 2.2.x: `glsmLinkageTest` calls `new GLSMRedirector()`, its core exclusions, `shouldTransform` and `transformClassNode(String, ClassNode)` the same way on 2.2.8, 2.2.21, 2.2.28, 2.2.32 and 2.2.36, and era 2 no longer arises (25:259).

### 4.1 Planned calls (124)

| ID | Call: `org/lwjgl/opengl/<Owner>.name(descriptor)` | Tier | `GLStateManager` target | Links from | Notes, older versions, alternative | Use |
|---|---|---|---|---|---|---|
| C01 | `GL11.glGetInteger(I)I` | core | `glGetInteger(I)I` | all 2.x |  | state saves (20 §1 table) and leg detection GL_CONTEXT_PROFILE_MASK (20 §2) |
| C02 | `GL11.glGetString(I)Ljava/lang/String;` | infer | `glGetString(I)Ljava/lang/String;` | 2.1.5 |  | GlCaps log line (20 §2, 14 §3) |
| C03 | `GL11.glGetError()I` | core | `glGetError()I` | 2.1.5 | (from 2.2.16: `Util.checkGLError` too is redirected, to `GLStateManager.checkGLError`; M0 step S8 reads the driver through D01 instead, the S8 amendment) | glGetError clean after first pass (20 §7) |
| C04 | `GL11.glPushAttrib(I)V` | core | `glPushAttrib(I)V` | all 2.x |  | 7-bit save in START pass (20 §1); 5-bit save in TESR (20 §4, 02 §5); *2026-10-26 (S12):* a 4-bit save in the TESR, 0x6140 without `GL_TEXTURE_BIT`, and none in the GUI (R-4 as amended) |
| C05 | `GL11.glPopAttrib()V` | core | `glPopAttrib()V` | all 2.x |  | restore (20 §1, §4) |
| C06 | `GL20.glUseProgram(I)V` | core | `glUseProgram(I)V` | all 2.x |  | program save/restore (20 §1 table) |
| C07 | `GL30.glBindFramebuffer(II)V` | core | `glBindFramebuffer(II)V` | 2.0.0-alpha14 |  | draw/read FBO restore per target (20 §1 table) |
| C08 | `GL30.glBindVertexArray(I)V` | core | `glBindVertexArray(I)V` | 2.0.0-alpha19 |  | VAO restore (20 §1 table) |
| C09 | `GL15.glBindBuffer(II)V` | core | `glBindBuffer(II)V` | 2.0.0-alpha19 |  | array/element/pack buffer restore; PBO bind (20 §1, §3) |
| C10 | `GL13.glActiveTexture(I)V` | core | `glActiveTexture(I)V` | all 2.x |  | active unit restore (20 §1) |
| C11 | `GL11.glBindTexture(II)V` | core | `glBindTexture(II)V` | all 2.x |  | texture restore units 0–3 (20 §1, 24 §3.7); TESR/GUI bind (20 §4); *2026-10-26 (S12):* the TESR and the GUI bind their texture, then 0 (R-4 as amended) |
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
| C32 | `GL11.glTexSubImage2D(IIIIIIIILjava/nio/ByteBuffer;)V` | core | `glTexSubImage2D(IIIIIIIILjava/nio/ByteBuffer;)V` | all 2.x |  | LAN-guest upload, no-graphics bitmap (20 §4, §7); *2026-10-26 (S12):* not the bitmap in M0, which comes from the block atlas (S12 plan P8.6); C32 stays for M4's LAN-guest upload |
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
| C91 | `GL11.glGetTexImage(IIIIJ)V` | core | `glGetTexImage(IIIIJ)V` | 2.1.12 | NSME in 2.1.5–2.1.11 and no overload covers that range: A01 is NSME in 2.2.0–2.2.21, and A08 cannot run with a pack PBO bound in 2.1.5 (L7). Below 2.2.8 the version gate disables graphics (below 2.1.14 until 2026-10-13) | readback into GL_PIXEL_PACK_BUFFER (20 §3) |
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
| X02 | `GL11.glColor4f(FFFF)V` | opt | `glColor4f(FFFF)V` | all 2.x |  | TESR/GUI tint reset; *2026-10-26 (S12):* not called: the dispatcher sets white before each TESR, and the GUI's quad carries white vertices |
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

*2026-10-13: since the band was dropped (§1.1), some reasons below concern only releases that are now gated off. Every rule stays in the code, as hygiene where its reason lay below 2.2.8; the amendments at the end of this report say which rules lost their band rationale.*

- **R-1 Scissor box.**
  - *Rule:* save the box with `glGetInteger(GL_SCISSOR_BOX, buf)` (R01) right after the pass's `glPushAttrib`. Restore it with `glScissor` (R02) just before `glPopAttrib`.
  - *Why:* up to 2.2.20, `GL_SCISSOR_BIT` restores only the scissor-test enable. The box is only a comment there (`AG@2.2.8:FEAT:292-295`, `AG@2.2.20:FEAT:303-306`), and the fix 44c930f4 first appears in 2.2.21.
  - *Range:* the floor 2.2.8 and RC-1's 2.2.19 both need this.
  - *Consistency:* up to 2.2.20 the query reaches the driver (`AG@2.1.12:GLSM:1030-1046`, `AG@2.2.20:GLSM:1120-1138`) and `glScissor` is not cached (`AG@2.1.12:GLSM:4554-4563`). From 2.2.21 the box is cached (`AG@2.2.21:GLSM:1162`). Both behaviours are consistent with an explicit restore (`CL F5`, VF).
  - *Test:* a hygiene case with a non-default box on the floor's GLSM leg (§6). *2026-10-26 (S12, RM:67):* met: all 17 `GlsmScenarioTest` scenarios on `glsmFloorTest` (2.2.8) and `glsmPackTest` (2.2.28) run from `GlHygiene.mcLikeStart`'s scissor box {1, 2, 7, 9} at least once, and all passed in CI run 40249306573 of e4617d8 (RM:67's note; 23's S12 amendments).
- **R-2 glGet into buffers.**
  - *Rule:* use a direct buffer at position 0. Call `clear()` before the query and read the result with absolute `get(i)`.
  - *Why:* before 2.1.16, cached multi-value queries advance the buffer position (ec82280b). In 2.1.12–2.1.15, cached scalar queries write index 0 whatever the position is (`AG@2.1.12:GLSM:1040`, VF).
- **R-3 Drain `glGetError`.**
  - *Rule:* at the start of the START pass, loop until `GL_NO_ERROR`, with a bound. Do this before the "first pass is error-free" check of 20 §7.
  - *Why:* before 2.1.57, Angelica itself issues capabilities that core removed (6570b6a0). Any mod can leave an error pending [I].
- **R-4 TESR state.**
  - *Rule:* inside one `glPushAttrib(ENABLE | COLOR | DEPTH | LIGHTING | TEXTURE)` level (02 §5 step 3), the host TESR changes only three things: *2026-10-26 (S12 decisions D23, the owner's choice):* the level is now four bits, `ENABLE | COLOR | DEPTH | LIGHTING` (0x6140), without `TEXTURE`; the binding item below says why, and the S12 amendments give the rule in full.
    - FFP lighting (F09);
    - the lightmap coordinates (X01);
    - the unit-0 texture binding (C11). *2026-10-26 (S12 decisions D23):* to its texture before the draw and to 0 after it, inside the level, with no pop to restore it, since Angelica's pop restores a pushed binding in GLSM's cache and the driver only, without the `TEXTURE_BIND` event that Iris's bind-driven uniforms and PBR maps follow; the GUI's quad binds the same way and pushes no level.
  - It never changes blend enable or function, alpha test, depth mask, colour mask, or per-unit texture capabilities.
  - *Supporting choice:* the expand pass writes alpha 255 [proposal], so the present texture is opaque and inherited blend or alpha-test state has no visible effect [I].
  - *Why:* below 2.2.8, changes to those states leak under Iris locks (§1.3 b). Below 2.2.12, Iris misread per-unit texture capabilities (32d1c27b).
  - *Bits are emulated:* all five pushed bits are emulated in 2.1.14, 2.2.8 and 2.2.21 (supported list `AG@2.1.14:FEAT:21-24`, LIGHTING at `:216`, TEXTURE at `:325`; `AG@2.2.21:FEAT:16-19`) [V, writer]. *2026-10-26 (S12):* since D23 the TESR pushes four of them, without TEXTURE.
  - *START pass:* it lies outside Iris level rendering in every release (§5.4), so Iris locks are not held there [I].
- **R-5 No GL method references.**
  - *Rule:* no method references or lambdas bound to GL static methods, and no GL calls through reflection or `MethodHandle`.
  - *Why:* `invokedynamic` handles are redirected only from 2.1.13 (e65c01c2), and from then on by name with the descriptor kept (`AG@2.2.21:RED:985-1008`, `LK L11`). Plain `invokestatic` calls keep every GL call visible to the linkage test.
- **R-6 Attrib stack.**
  - *Rule:* exactly one `glPushAttrib` level in the START pass and one in the TESR. Never query `GL_ATTRIB_STACK_DEPTH` or `GL_MAX_ATTRIB_STACK_DEPTH`. *2026-10-26 (S12):* as built; the GUI pushes none.
  - *Depth:* the stack holds 18 levels up to 2.2.18 and 32 from 2.2.19 (`CL F6`). Overflow throws `IllegalStateException` (`AG@2.1.14:GLSM:3165-3167`; `AG@2.2.21:GLSM:3964`, `:3978`; VF).
  - *Queries:* GLSM answers the two stack queries only from 2.2.29 (6dfb0312, #2211). Before that they reach a core driver and raise an error (`CL` Table A).
- **R-7 GLES detection.**
  - *Rule:* read `RenderSystem.isGLES()` reflectively (20 §2) and treat a missing method as "not GLES".
  - *Why:* the method and the ES profile exist only from 2.2.0 (`AG@2.2.0:glsm/.../RenderSystem.java:387`; ES branch at `AG@2.2.0:src/mixin/java/com/gtnewhorizons/angelica/mixins/early/angelica/MixinForgeHooksClient_CoreProfile.java:52`). `JAR 2.1.61` lacks the method (`CL F14`, VF).
- **R-8 No world-less TESR rendering.**
  - *Rule:* never pass `TileEntityRendererDispatcher` a world-less tile entity whose `blockMetadata` is −1. One example is an item renderer that draws the display block through its TESR.
  - *Why:* before 2.2.23, Angelica's dispatcher mixin calls `te.getBlockMetadata()` at HEAD. 9814a96b replaces that call with a guarded version in `MixinTileEntityRendererDispatcher` and `TesrProviderDispatch` [V, writer]. Vanilla's getter dereferences `worldObj` when the field is −1 (`MC/net/minecraft/tileentity/TileEntity.java:153-161`). The commit title reports an attrib-stack overflow as the result. *2026-10-26 (S12's planning scout, read at the floor):* for OpenGPU's own tile entity this reason does not hold at 2.2.8: Angelica's always-applied rendering mixins already replace `TileEntity.getBlockType` and `getBlockMetadata` with versions that read the world only when it exists (`AG@2.2.8:src/mixin/java/com/gtnewhorizons/angelica/mixins/early/rendering/MixinTileEntity.java:60-82`), and 9814a96b's guard is for tile entities that override `getBlockMetadata`, which `DisplayTile` does not [V]. The rule stands for its other reasons: a world-less display has no id, no world lightmap and no host mapping.
  - *Range:* this includes the floor 2.2.8, whose `runClient` leg exercises the rule in game, and 2.2.21, which stays in CI (2026-10-14: the owner's instance, on 2.2.21 during the research, now runs 2.2.32; §6). *2026-10-24:* the instance runs 2.2.36.
  - *Fix:* draw the display item through the ISBRH chassis's inventory path instead [proposal]. *2026-10-26 (S12):* built as `DisplayChassis.renderInventoryBlock`, with its own `Tessellator` draw (02:78's note); on 2.2.8, IG6 saw the chassis cube in every item path.
- **R-9 Render bounds** (every 2.x).
  - *Rule:* every display tile entity returns one constant box from its very first `getRenderBoundingBox()` call: the maximal wall extent, whatever its role in a wall. Alternatively, the class returns `INFINITE_EXTENT_AABB` from its first instance onwards. *2026-10-26 (S12):* built the first way: the block grown by 7 on every side, the extent of M1.5's default 8 × 6 wall around any of its blocks (Q-S12-6 (a); the S12 amendments).
  - *Why:*
    - The box is cached per instance, keyed on position, block and metadata (`AG@2.1.14` and `AG@2.2.21:src/mixin/.../rendering/MixinTileEntity.java:72-99`, VF).
    - The class is classified on its first call (`CL F8`).
    - DYNAMIC is reachable only through the user's `dynamicBoundsTileEntities` list, with no API (`AG@2.2.21:.../ClientProxy.java:133`, VF).
  - *Consequence:* a block that becomes a wall origin after its first render keeps its old box [I]. M1.5's check that a wall grown after its first render is not culled must cover this case (24 §6).
- **R-10 Eviction order.**
  - *Rule:* detach or delete the FBO before deleting its textures.
  - *Why:* GLSM's `glDeleteTextures` shrinks the texture to 1×1 R8 in place and frees the name only at the next `glGenTextures` (`AG@2.1.14:GLSM:1941-1991`; the same mechanism exists in 2.2.21, VF). *2026-10-24 (S9's doc pass; Angelica 2.2.36's `GLStateManager.java`, read on GitHub at the tag):* from 2.2.36, `glDeleteTextures` no longer re-specifies the texture when it is called: it unbinds it, detaches it from the bound draw and read framebuffers and queues it (`deferDeleteTexture`, `:2733-2774`), and `onFrameEnd()` re-specifies a 2D texture's storage as 0 × 0 `GL_RGBA8`, at each level and not for immutable storage, at least `RELEASE_DELAY_FRAMES` = 3 frames later (`:444`, `:2793-2808`, `:2851-2860`). A texture still attached to an FBO stays `DEFERRED_ATTACHED` until it is detached (`:2771`, `:2984`), so on latest R-10 is also a memory rule: a texture deleted before its FBO keeps its storage until the FBO lets it go.
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

*2026-10-13: this section no longer has to hold. The band is gated off (§1.1), so no release below 2.2.8 runs OpenGPU graphics. The list stays as the record, and as the starting point if the band ever returns (§8 Q4).*

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
- **Attrib bits.** Every bit that OpenGPU pushes is emulated: the seven of the START pass and the five of the TESR (§5.1 R-4). *2026-10-26 (S12):* the TESR now pushes four of the five (R-4's note).
- **Binding caches.** The bindings that `glGetInteger(int)` answers from its cache are always set by calls that the same release rewrites (`LK L5`). OpenGPU's exact save and restore therefore never reads a stale cache. The caches also skip redundant binds (`AG@2.1.14:GLSM:5068-5075`, `:1670`), so this holds only while all code keeps them honest (VF).
- **Mod identity.** `@ThreadSafeISBRH` is unchanged, and the modid is always `angelica` (`CL F9`).
- **Java 17/21.** The multi-release Java 17/21 copies of `GLStateManager` declare the same public static methods as the base class in every jar (`LK L9`). The verifier did not re-check this, and decision 9 does not design for lwjgl3ify. *2026-10-24 (S9, critic A11):* re-checked by `glsmLinkageTest`'s L1 on every run, on 2.2.8, 2.2.21, 2.2.28 and 2.2.36, and on 2.2.32 when forced: the `META-INF/versions/17` and `/21` copies of `GLStateManager` declare the same public static methods as the base, read from those entries.

## 6. Test matrix

| Angelica | Role | CI linkage test (gating) | CI `-Dangelica.unmappedGL=FAIL` run | CI GLSM render-core leg (23 §2.3) | In-game smoke with an Iris pack | GPUs |
|---|---|---|---|---|---|---|
| 2.1.14 | best-effort canary, **dropped 2026-10-13** | no (yes until 2026-10-13) | — (no detector before 2.2.0) | — | — | — |
| **2.2.8** | **floor** | yes | yes | yes, including the R-1 hygiene case | yes, each milestone, `runClient` with the dev jar pinned | any one |
| 2.2.21 | CI only since 2026-10-14 (the owner's instance during the research) | yes | yes | — | no (yes until 2026-10-14, each milestone, in the owner's instance) | — |
| 2.2.28 | pack (2.9.0-RC-2) | yes | yes | yes | yes, before each release | any one |
| latest (`MVN` `<release>`, resolved at build time each run: 2.2.36 as of 2026-10-24, S9; 2.2.32 as of 2026-10-13) | latest; the owner's instance since 2026-10-14 | yes, version taken from `MVN` `<release>` | yes | — | yes, each milestone, in the owner's instance with the `-selftest` jar of 24 §3.1 (24 risk 2; until 2026-10-14 a non-gating `runClient`); blocks a release only on OpenGPU's own failures (notes) | Intel, NVIDIA and AMD for the M0 measurements (24 §7 answer 7) |
| none | vanilla leg | — | — | the compat leg (23) | yes, each milestone, without a shader pack (Iris needs Angelica) | as for the latest release |

Notes:

- **Running the floor in game.** The floor runs through `runClient` with the Angelica dev jar pinned by a Gradle property (`-PwithAngelica`, since M0 step S3 on 2026-10-14, when it first booted; 05 §2). `Angelica-2.2.8-dev.jar` and `Angelica-2.1.14-dev.jar` are published (Maven listings of `.../Angelica/2.2.8/` and `.../2.1.14/`) [V]. *2026-10-14:* the owner's instance runs the latest release (2.2.32 since 2026-10-13) and is the in-game latest leg, so the floor's `runClient` leg is the in-game run that exercises the version-dependent rules: R-1 matters up to 2.2.20, and R-8 before 2.2.23. 2.2.21 has no in-game leg; if one is ever wanted, it runs through `runClient` on `Angelica-2.2.21-dev.jar`, not in a second instance. *2026-10-24:* the instance runs 2.2.36 (released 2026-10-22, `MVN`'s `<release>` on 2026-10-24), which the owner keeps current by checking Angelica's releases by hand; the table's latest row now takes `<release>` at build time, 2.2.36 at S9's run that day.
- **GLSM CI leg.** 23 §8 pins this leg to 2.2.21. It moves to the floor and the pack version [proposal]; 2.2.21 stays covered by the CI linkage test and the `unmappedGL=FAIL` run (2026-10-14; until then also in game, in the owner's instance).
- **What each in-game smoke checks**, per version:
  - the pattern and the sprite are visible, and the self-test readbacks are byte-identical (24 §6 M0);
  - the hygiene check is clean;
  - the panel renders correctly next to other mods' TESRs, under a shader pack that overrides blend for block entities. *Decided 2026-10-14:* OpenGPU's own test pack, a small MIT pack written for OpenGPU and kept in the repo as a test fixture (`shaders.properties` with `blend.gbuffers_block`, plus minimal `gbuffers` programs), written at the end of M0. No common 1.7.10 pack overrides block blending: Iris reads `blend.<program>` from `shaders.properties` (`AG@2.2.8:src/main/java/net/coderbot/iris/shaderpack/ProgramDirectives.java:76-78`), `gbuffers_block` and its translucent variant have no default (`AG@2.2.8:src/main/java/net/coderbot/iris/shaderpack/loading/ProgramId.java:36-37`; both the same at 2.2.28 and 2.2.32), and Complementary Reimagined r5.9.3, BSL v10.1.8 and Sildur's Vibrant v2.02 set no `blend.gbuffers_block`; packs are also usually not redistributable [I]. The same pack is the Iris pack of the in-game column, and each run records the pack's version. *2026-10-24:* on the latest leg this meets two newer Angelica changes. From 2.2.36 every TESR dispatch runs inside a batched-TESR scope (`TesrBatchRenderer`'s `beginScope` and `endScope` around `MixinTileEntityRendererDispatcher`'s dispatch) and animated TESRs are drawn instanced (Angelica PR 2275, "Instance animated TESRs and let mods register shaders for batched TESRs", merged 2026-10-19, first in 2.2.36); from 2.2.34 entity, block-entity and item IDs are a per-vertex attribute for Iris (PR 2265, "Set entity, block entity and item IDs per vertex", merged 2026-10-17, 2.2.34's tag commit). The latest leg, with the test pack, is to confirm that OpenGPU's plain-GL TESR (R-4) still renders correctly under both [I], a check for the in-game session, not yet run. *2026-10-26 (S12):* the same leg now also checks R-4 as D23 amends it: one look, under a PBR pack, at a vanilla chest TESR beside a bound display, whose PBR maps must stay the chest's own. S12's in-game legs on 2.2.8, with no shader pack, saw a chest and a sign beside a bound display render with their own textures; the pack look and both checks above stay S15's.
  - From M1.5 on, a 3 × 2 wall that crosses a chunk-section boundary is also checked.
- **New Angelica releases.** The gating linkage test picks up each new release as "latest". A failure there is the signal to update §4 (24 risk 1). *2026-10-24 (S9):* `glsmLinkageTest` reads `MVN`'s `<release>` at build time and logs it on every run. A target that a later release renames fails its L3, and the fix is §4.1's target column together with that jar's name in `GlsmLinkageTest.linkage()` (S9 review F3).
- **What blocks a release** (*decided 2026-10-14*; the owner: "only a direct OpenGPU failure should block"). The latest leg runs at each milestone and before each release. A failure in an in-game leg blocks the release only when it is OpenGPU's own: either OpenGPU's code is at fault (a call outside §4's set, a broken §5.1 rule, leaked GL state), or the failure also reproduces on the floor or the pack version. A regression in an Angelica release newer than the pack's does not block. It is reported upstream with a reproduction, worked around where that is cheap, and recorded with the run. This concerns the in-game legs; the CI linkage test keeps its role (24 risk 1).

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

OC 1.12.x first appears together with Angelica 2.x, from 2.9.0-beta-1 onwards (`CL` Table D). A player on OC 1.12.64 therefore normally has Angelica 2.2.28 or later, unless they have mixed versions, as the owner had during the research (2026-10-14: the owner's instance now runs 2.2.32; 2026-10-24: 2.2.36).

*2026-10-13: under the changed §1.1 the three rows marked best-effort (2.9.0-beta-1, experimental, 2.9.0-beta-2) fall below the gate, so graphics are unavailable there. Their OC versions, 1.12.44–1.12.48, are below decision 14's floor of 1.12.64 anyway (24 §1).*

## 8. Open questions for the owner

1. **Policy.** Is this policy acceptable: 2.2.8 supported, 2.1.14–2.2.7 best-effort with a log warning, and everything older gated off? *Default: yes.* There are two alternatives:
   - (a) Make 2.1.14 first-class. The floor column of §6 becomes 2.1.14, 2.2.8 joins the linkage list, and every milestone gains an in-game Iris run on the 2.1 generation.
   - (b) Drop the band. Graphics become unavailable below 2.2.8, the canary goes away, and rules R-2, R-3 and R-5 remain only as hygiene.

   *Answered 2026-10-13: (b). The owner had delegated the floor (24 §7 answer 8) and accepted the recommendation of Q4 at decision 14's OC floor. Graphics need 2.2.8 or later; the band, its log warning and the 2.1.14 canary are gone. The amendments at the end refine (b)'s list of hygiene-only rules.*
2. **Floor in game.** Should the floor be tested in game through `runClient` with the 2.2.8 dev jar pinned, while your instance stays on 2.2.21? *Default: yes.* *2026-10-14: the instance no longer stays on 2.2.21: it runs the latest release (2.2.32 since 2026-10-13) and is §6's in-game latest leg. The floor's `runClient` leg stays, and it is now the in-game run that exercises R-1 and R-8.*
3. **Angelica 1.x (GTNH up to 2.8.4, the current stable line).** *Default: graphics unavailable.* Revisit this only if OpenGPU is to support OC 1.11, which 2.8.x ships (1.11.20).
4. **OC floor.** The packs in the band ship OC 1.12.44, 1.12.46 and 1.12.48. If OpenGPU's own OC floor ends up at 1.12.61 or later, the band covers no GTNH pack, and 1(b) becomes the simpler choice. *Default: decide together with the OC floor.* *Answered 2026-10-13: decision 14 set the OC floor at 1.12.64-GTNH (24 §1), so 1(b) was taken. Revisit only if that floor is ever lowered below 1.12.61, because only then could a supported GTNH pack carry an Angelica below 2.2.8 (§7).*

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

## Amendments after the owner's answers (2026-10-13)

The owner, who had delegated the Angelica floor (24 §7 answer 8), accepted this report's recommendation for §8 Q4: at decision 14's OC floor of 1.12.64-GTNH the band covers no GTNH pack, so option 1(b) applies. Policy statements were changed in place; the evidence in §2–§5 and §7 stays as the record.

- Status note under the title: the policy changed; 2.2.8 is the only threshold.
- Summary 2: below 2.2.8 the runtime gate reports graphics unavailable; the band is recorded as the earlier proposal. Summary 6: the linkage jars are 2.2.8, 2.2.21, 2.2.28 and the latest release.
- §1.1 policy table: the 2.1.14–2.2.7 row is marked dropped, and the unsupported row reads "< 2.2.8". Gate mechanism: one `VersionParser` range, `"[2.2.8,)"`, against the `mcmod.info` version; no log-warning band. The definition of best-effort is kept as the record.
- §1.2: the 2.1.14 and 2.2.1 verdicts note that both are below the gate. §1.3: the closing sentence notes the owner's answer.
- §5.1: a dated note; every rule stays in the code. §5.3: a dated note; the section no longer has to hold.
- §6: the 2.1.14 canary row is dropped from the gating linkage test. The `unmappedGL=FAIL` runs stay on the 2.2.x jars, which are now all the linkage jars.
- §7: a dated note; the three pre-release rows marked best-effort now fall below the gate.
- §8 Q1 and Q4: answered. Revisit only if decision 14's OC floor is ever lowered below 1.12.61, because only then could a supported GTNH pack carry an Angelica below 2.2.8.
- **Rules that lose their band rationale but stay as hygiene** (§5.1, read against §5.2–§5.3). R-2: both of its reasons (buffer positions before 2.1.16, index-0 writes in 2.1.12–2.1.15) lie below 2.2.8. R-3: its Angelica reason (removed capabilities issued before 2.1.57) lies below 2.2.8; the [I] that any mod can leave an error pending remains. R-4, in part: the clause that forbids changing blend, alpha test, depth mask and colour mask had one version reason, the Iris override leak fixed in 2.2.8 (§1.3 b); the per-unit texture-capability clause keeps a reason inside the supported range (Iris misread those capabilities until 2.2.12, 32d1c27b), and §5.2's row for other mods' TESR leaks (fixed in 2.2.23) still relies on R-4. R-5: its version reason (method handles redirected only from 2.1.13) lay below even the old gate; its second reason, that plain `invokestatic` calls keep every GL call visible to the linkage test, stands. R-7, in part: the fallback that treats a missing `isGLES()` as "not GLES" served 2.1.x (`JAR 2.1.61` lacks the method); it still covers a version string that does not parse, which §1.1 treats as supported [I]. This refines §8 Q1 (b)'s list of R-2, R-3 and R-5.
- **Rules that keep a reason inside 2.2.8 → latest:** R-1 (2.2.8–2.2.20), R-6 (stack depth 18 until 2.2.18; stack queries reach the driver until 2.2.28), R-8 (before 2.2.23, the owner's 2.2.21 included), R-9 (every 2.x), R-10 (the same mechanism in 2.1.14 and 2.2.21), R-11 (the floor's `-api` jar), R-12 (deny-listed calls that throw `NoSuchMethodError` in 2.2.x, §3) and R-13 (now one range). *(2026-10-14: the owner's instance now runs 2.2.32, outside R-8's range; the range still includes the floor 2.2.8 and the CI jar 2.2.21.)*
- Summary 2's heading reads "could run on": 2.1.14 remains the lowest release the evidence admits, but it is no longer linkage-tested. §4: step 4 of the test notes that only the 2.2.x clause applies; row C91 names 2.2.8 as the gate.
- Not changed: the floor 2.2.8 and its supported-range table (§5.2); §2–§3, and §4 apart from the two notes above; §5.4; R-11's compile target; the GLSM render-core leg (2.2.8, 2.2.28) and the in-game matrix of §6; the Verification notes. §9's correction for 20 and 24 still lists the 2.1.14 jar; this amendment supersedes it, and 20 and 24 were amended to match on 2026-10-13.

## Amendments after M0 step S8 (2026-10-16)

M0 step S8 built `:gl`, OpenGPU's first GL-calling code, against §4's call set; 28's section "S8 implemented" records the step. `:gl`'s call set as built is the resource `callset.txt` beside `CallSetTest` in `:gl`'s tests, one row per call with §4.1's ids. §4.1 and §5.1 are not edited, except a note on C03's row (25:269) that points here; recorded here:

- **Rows now used.** C02 `GL11.glGetString(I)Ljava/lang/String;` (25:268) reads `GL_VENDOR`, which picks the readback `glFinish` (Q-S8-3), and `GL_RENDERER`, which turns on llvmpipe's copy wait, each once per GL context at its first pass, and both are logged; GLSM maps it to `GLStateManager.glGetString`, which returns the driver's string whenever a context is current (`AG@2.2.8:RED:281`; the same at 2.2.28 by `javap`). C15 `GL11.glEnable(I)V` (25:281) takes a cap that `GLSMRedirector` does not fold at 2.2.8, `GL_RASTERIZER_DISCARD` (0x8C89; its `glCapRedirects` fold 3008, 3042, 2929, 2884, 2896, 3553, 2912, 32826 and 3089): the pass reads the cap with C14, turns it off for the pass and on again at exit if it found it on, in every build (H70).
- **New rows.** C99 `GL11.glPolygonMode(II)V`: `glPolygonMode(GL_FRONT_AND_BACK, GL_FILL)` inside the pass's push, since a foreign line or point mode changes what a quad covers (H41); `GLStateManager` declares it at 2.2.8, 2.2.21, 2.2.28 and 2.2.32. C100 `GL11.glFinish()V`: the readback waits, one before a pass's first fetch on every driver but those verified clean (Intel's Windows driver needs it; Q-S8-3) and, on llvmpipe, one before a readback's copy; `GLSMRedirector` maps it (`AG@2.2.8:RED:279`) to `GLStateManager.glFinish`, which calls the driver. §3's matrix was not re-run for either row: S9's per-jar linkage test covers them.
- **C03 is no longer called.** `GLStateManager.glGetError()` answers 0 while GLSM's `noErrorChecks` is set (`AG@2.2.8:GLSM:5216-5220`), and Angelica sets it in an obfuscated client by default: `noErrorChecks(AngelicaConfig.disableErrorChecks && GTNHLibCore.isObf() && !AngelicaMod.lwjglDebug)` (`AG@2.2.8:src/mixin/.../startup/MixinInitGLStateManager.java:47`), with `disableErrorChecks` `@Config.DefaultBoolean(true)` (`AG@2.2.8:CFG:433-436`). Through C03, R-3's drain and 20 §7's first-pass check (20:240) would never fire in a GTNH client, only in a dev `runClient` (S8's review, HY-1). `:gl` makes no `GL11.glGetError` call, and `CallSetTest` reports one.
- **New row class D, driver-direct.** D01 `GL11.glGetError()I`: `PassState` looks it up at run time, `MethodHandles.publicLookup().findStatic(GL11.class, "glGetError", methodType(int.class))`, and invokes the handle, never calling it as an instruction. `GLSMRedirector` rewrites call instructions, `invokedynamic` handles and `NEW` and `CHECKCAST` types, never a class literal or a run-time lookup, and its core exclusions leave LWJGL's own classes as they are (`AG@2.2.8:RED:177-182`), so the handle reaches the driver on every leg, GLSM's release configuration included. D01 is hidden from §4's linkage test by design. It is an exception to R-5's rule, "no GL calls through reflection or `MethodHandle`" (25:447), whose reason that plain `invokestatic` calls keep every GL call visible to the linkage test (25:448) gives way for this one row; `CallSetTest` and the GLSM legs' `GlsmRedirectionTest` allow no other LWJGL class literal. R-3 drains (at most 16 errors) and 20 §7 checks through D01 only on checking passes: a release build's first pass, a dev build's every pass. The main session chose D01, and it goes to the owner with its alternative: record that the `glGetError` check is inert under Angelica's release configuration and keep only the link and framebuffer-status checks. Its known limits: a GLSM render backend that is not LWJGL 2's GL would need D01 guarded, and lwjgl3ify, where `GL11.glGetError` is also public static, is not tried. *2026-10-24:* D01 is kept. The owner had no strong preference and asked for the main session's recommendation, which was to keep it: without it the check never fires in a GTNH client, and it costs one lookup, through a `GL11` class literal, and the driver's `glGetError` only on checking passes. The alternative is not taken, and lwjgl3ify stays untried (28:1103, :1110).
- **`Util.checkGLError` is no alternative.** From 2.2.16, absent from 2.2.8 to 2.2.15 and present from 2.2.16 to 2.2.30 and on master, `GLSMRedirector` maps `org.lwjgl.opengl.Util.checkGLError` to `GLStateManager.checkGLError`, which reads GLSM's own `glGetError` and reports through `GLErrorReporter` (`AG@2.2.28:RED:815`, from S8's fix round; only 2.2.8, which has no such mapping, was re-read for this amendment). S8's first fix used `Util`: `glsmPackTest`'s `GlsmRedirectionTest` (2.2.28) caught the rewrite, and as a mutant that route fails only the pack's release-configuration leg (it drains nothing and does not throw) while the floor's passes.
- **Counts as the code has them** (`CallSetTest`): 66 allowed calls (64 C rows, among them C99 and C100, plus R01 and R02), 5 folded (F01, F03, F04, F06, F08), the 13 deny-listed calls of §4.2, and 1 driver-direct (D01); no X row, which belongs to the root's TESR.
- **25:251-255's steps 1-2, checked over `:gl`.** `CallSetTest` runs the allowlist, with the folded constants, and the deny-list over `:gl`'s classes; on both GLSM legs `GlsmRedirectionTest` checks that every call is redirected and every target links: 11 GL-calling classes rewritten, 81 classes left unchanged, 15 enables and disables folded, 1 driver-direct lookup. Steps 3-4, the per-jar linkage over §6's jars, and the `unmappedGL=FAIL` runs are S9's; D01 stays outside them by design.
- **Clip distances, corrected.** GLSM handles `glEnable`/`glDisable` of 0x3000-0x3007 (`GL_CLIP_PLANEi`, the same values as `GL_CLIP_DISTANCEi`) in a cache of its own and also sends each change to the driver (`AG@2.2.8:GLSM:649-653`, `:730-734`, through `BooleanState.setEnabled`; the same at 2.2.28 by S8's review), so the pass's `glDisable(GL_CLIP_DISTANCE0..7)` (C16, H41) matters on every leg; S8's plan had taken GLSM to emulate them without the driver (H72, corrected).
- **R-10's mechanism and Intel.** GLSM's `glDeleteTextures` re-specifies the texture as 1×1 `GL_R8` and frees the name only at the next `glGenTextures` (`AG@2.2.8:GLSM:2201-2209`; `deferDeleteTexture`, `:2222-2255`, its `texImage2D` at `:2248`), and on Intel's Windows driver (32.0.101.8860) a re-specification of any texture between a readback's copy and its fetch can make the fetch return stale bytes. Under GLSM every mod's deletes take that path, so in game it would hit every Intel user; C100 fixes it (20's S8 amendment). *2026-10-24:* from 2.2.36 the re-specification moves to a frame end, at least three frames after the delete, as 0 × 0 `GL_RGBA8` (R-10's note, 25:470); in game it can still fall between a readback's copy and its fetch, so C100 keeps its reason on the latest release.

## Amendments after M0 step S9 (2026-10-24)

M0 step S9 built §4's steps 3 and 4 as `:gl-testkit:glsmLinkageTest` on 2026-10-24 (run wf_be3b82c7-8dc): §6's gating CI linkage test and its `-Dangelica.unmappedGL=FAIL` run (24:248), in `check` with a guard that allows no skip. It runs in one Java 8 JVM with no GL context, no natives and no Angelica jar on its classpath. Each leg's dev jar gets a `URLClassLoader` of its own, whose parent holds no Angelica class (L0 checks that); each loader initializes its own `SystemProperties` under FAIL, then its own `GLSMRedirector`. `GLStateManager` is never loaded: its public static methods are read from the jar's bytecode. 23's S9 amendments give the task, how the latest leg gets its release, the owner's decisions and the times. Applied in place, marked 2026-10-24: the status note and the scope (25:5, :13), Summary 1 (25:33), the F rows' fold list (25:239), step 3 and the closing [I] of §4's test (25:256, :261), R-8's range and R-10's reason (25:459, :470), the Java 17/21 bullet (25:524), §6's latest row and three of its notes (25:534, :539, :544, :546), §7's closing sentence (25:563) and the S8 amendments' R-10 bullet (25:700). Recorded here:

**The jars** (tags by `git ls-remote` on 2026-10-24; each `Angelica-<v>-dev.jar` from Nexus through Gradle's cache):

| Leg | Release | Tag | Source | Public static `GLStateManager` methods |
|---|---|---|---|---|
| floor | 2.2.8 | 6d8cbf9 | pinned, `OpenGpuVersions.ANGELICA_FLOOR` | 773 |
| ci-only | 2.2.21 | a8c29fa | pinned, `ANGELICA_CI_ONLY` (new in S9) | 798 |
| pack | 2.2.28 | 0a4d489 | pinned, `ANGELICA_PACK` | 819 |
| latest | 2.2.36 on 2026-10-24 (released 2026-10-22) | e84c8c8 | `MVN`'s `<release>`, read at build time | 870 |

- **Each jar.** Forced locally with `-Popengpu.angelica.latest=2.2.32` (0cae2be), the latest leg passed on 2.2.32 as well, with 837 methods and the same L2, L3, L4 and N6 results as 2.2.36. The methods are counted in the base entry; in all five jars the `META-INF/versions/17` and `/21` copies declare the same set, read from those entries (`LK L9`'s re-check: critic A11, and review TS2, which made the test assert what it read). `glCapRedirects` holds the same nine folds in all five: 0x0B44 Cull, 0x0B50 Lighting, 0x0B60 Fog, 0x0B71 DepthTest, 0x0BC0 AlphaTest, 0x0BE2 Blend, 0x0C11 ScissorTest, 0x0DE1 Texture and 0x803A RescaleNormal. 2.2.31 and 2.2.33–2.2.35 (2.2.33 = 49db8e1, 2.2.34 = ab3bf17, 2.2.35 = 32f8eef) stay unchecked, since only "latest" runs.
- **Each leg, the same on every run.** L1: the jar is the resolved release (`mcmod.info`'s `angelica` entry; the base entries major 52; `GLSMRedirector`'s `CodeSource` is the leg's jar; `UNMAPPED_GL` is `FAIL`). L2: each of `:gl`'s 44 shipped classes goes through the leg's redirector as in game without a throw; 8 are rewritten, exactly the GL-calling ones (`DeviceGl`, `GlBackend`, `GlTexture`, `GpuTimer`, `PassState`, `PixelStore`, `Program`, `ReadbackRing`, which `CallSetTest` pins), and 36 come out unchanged, with 5 folded sites and 1 driver-direct lookup. L3: 158 rewritten call sites reach 71 distinct `GLStateManager` methods, each public static with the call's descriptor under that jar's own folds. L4 (ci-only, pack, latest): `:gl`'s 18 constant capabilities are folded as on the floor. `GlFormats`, which holds GL constants only, passes GLSM's constant-pool filter in `:gl`'s jar, on javac's orphan LWJGL class entries, and comes out unchanged; the reobfuscated root jar that ships has no such entry, so in game the filter skips it (review F2). The GLSM legs' `GlsmRedirectionTest` still counts 11 classes rewritten, 81 unchanged and 15 calls folded (25:698), over `:gl`'s classes with `:gl-testkit`'s host fixtures and scenarios; L2 counts `:gl`'s shipped classes only.
- **C02, C99 and C100** (critic A9 (b)). `glGetString(I)Ljava/lang/String;`, `glPolygonMode(II)V` and `glFinish()V` are among L3's linked targets on 2.2.8, 2.2.21, 2.2.28 and 2.2.36. S8's `GlsmRedirectionTest` had already linked C99 and C100 on 2.2.8 and 2.2.28 (25:698; 28:1104), so what is new for them is 2.2.21 and 2.2.36. S9 checks four jars, not §3's matrix over every release, so §4.1 gets no "Links from" value for either row.
- **D01 is the only LWJGL reference left.** On every leg L2 finds no LWJGL name in any shipped class after the transform (owners and descriptors of calls and fields, type instructions, LDC types and handles, `invokedynamic` bootstraps and their arguments, declared field types and method descriptors, supertypes, and thrown and caught types, all read from the class tree, never from the constant pool, critic A7; string constants are left to `CallSetTest` and `GlsmRedirectionTest`) but D01's lookup in `PassState`. The rule takes D01's exact form, `PassState.lookUpDriverGetError`'s: `GL11`'s class literal, its method's only LWJGL one, right before the string "glGetError", the only GL entry-point name among the method's strings, in a method whose only reflective call is one `MethodHandles$Lookup.findStatic` (`javap`: `invokestatic publicLookup; ldc GL11; ldc_w "glGetError"; getstatic Integer.TYPE; invokestatic methodType; invokevirtual findStatic`). `GlBytecode.literalProblem` (L2 and `GlsmRedirectionTest`) applies this rule in `PassState` only; `CallSetTest.checkLookup` applies the same method-level form to each driver-direct row in any `:gl` class. S8's rule accepted any `GL11` literal in a `PassState` method that also named "glGetError", so a second lookup that reused the literal passed all three checks (reviews F1 and TS1, TS1 rated major; R1 below). *2026-10-26 (S12):* over the root's classes L2 leaves three more names by design: `GLContext` (P01's call) and `ContextCapabilities` in `GlCaps`, and `org/lwjgl/input/Mouse` in `client/gui/`'s `DisplayScreen`, which GLSM does not transform, since it is not GL (S12 plan, amendment F9; this report's S12 amendments).
- **What FAIL lets through, and A09.** FAIL throws for a call on an `org/lwjgl/opengl/` owner whose name starts with `gl` or `ngl` and which no map rewrites, but not when its descriptor names `GLSync`, and it reports debug-output calls (owners `ARBDebugOutput` and `AMDDebugOutput`, and the debug names `GLSMRedirector` lists) as a pass-through instead (2.2.36's `GLSMRedirector.java:42-44`, `:74-79` and `:1049-1052`, read on GitHub at the tag; the same at 2.2.8, critic A2). N6 shows that the scan reports both kinds: `GL32.glFenceSync(II)Lorg/lwjgl/opengl/GLSync;` (A12) and `ARBDebugOutput.glDebugMessageInsertARB(IIIILjava/nio/ByteBuffer;)V` pass the transform and stay on LWJGL on all four jars; `:gl` makes neither. N1 makes FAIL reject a name no map holds, and its separate A09 test shows `GL30.glClearBufferu(IILjava/nio/IntBuffer;)V` still unmapped on all four jars, 2.2.36 included (24:197).
- **The other negative cases** run on every build too, on synthetic classes that are transformed and scanned but never defined: N2, a rewritten call with no GLSM method, fails the link check; N3, a jar without `glPolygonMode`, fails it at the C99 sites; N4, a fold set without `GL_BLEND`, fails the expected calls; N5 reports a second LWJGL literal or a second lookup in D01's method.
- **The class set, and the TESR step's obligation** (S9 plan P8.2, critic A10). S9 scans `:gl`'s 44 shipped classes, `gl.host`'s included, which the root shades unrelocated. When the TESR step adds the root's client classes (X01, X02), the test reads them from the root's built jar, passed to the task as a file input and read with `ZipFile`, never through the root's classpath in the test JVM, and it follows the owners of that jar's `getNamedClassMethodRedirects`, X01's `OpenGlHelper` among them. `GlsmLinkageTest.SHIPPED`'s Javadoc holds this. *2026-10-26 (S12):* met: the test reads every root class outside `:gl`'s package, 129 a jar, from the root's -dev and release jars, as task inputs read with `ZipFile`; X02 is not called (the S12 amendments).
- **A renamed target turns CI red** (review F3). L3 expects each call's own name on `GLStateManager`, which is stricter than step 3 by design. If a later release maps a call to another name, L3 fails on the latest leg, and the fix is §4.1's target column and that jar's name in `GlsmLinkageTest.linkage()` (25:546).

**Mutants and negative-case checks.** Each code mutant ran in a WSL copy of the tree, never in the Windows tree, with exactly the one mutant file differing, and was reverted after each run; the control passed 38 of 38. M7's rows are the resolver's paths, run with flags in WSL's Gradle home, and on Windows where the table says so. M1–M7 and S1–S3 ran before the review fixes; M4, S1 and S2 ran again after them, with the same results in `glsmLinkageTest`. R1 and R3, the reviews' mutants, failed no test before the fixes and fail tests after them; S2b, TS3 and B1 ran only after them. The others were not re-run, since the fixer judged that they touch no logic it changed; NexusRelease's online path, which M7 (a) ran, did change, and it has not run since ((c) below). Failures are of `glsmLinkageTest`'s 38 tests:

| Id | Change | `glsmLinkageTest` | Elsewhere |
|---|---|---|---|
| M1 | the task's `angelica.unmappedGL` set to `WARN` | 12: L1, N1 and the A09 test on 4 legs; L2 passes | |
| M2 | a never-called `GL30.glClearBufferu` in `DeviceGl` | 13: L2 on 4 legs with GLSM's "Unmapped GL call …", L3 and N3 on 4, N4 | `CallSetTest` (4 problems); the floor leg's `GlsmRedirectionTest` |
| M3 | a never-called A11 `glTexImage2D(…ShortBuffer)` in `DeviceGl` | 9: L3 on 4 legs (no such public static method), N3 on 4, N4; L2 passes | `CallSetTest` (3 problems); the floor leg's `GlsmRedirectionTest` |
| M4 | a second `GL11` literal in `GlBackend` | 4: L2 on 4 legs | `CallSetTest`; `GlsmRedirectionTest` on both default GLSM legs |
| M5 | L3's expectation with an empty fold map | 4: L3 on 4 legs, at exactly the 5 folded sites | |
| M6 | the floor's dev jar on the task's classpath | 5: L0, and L1 on 4 legs (the floor's too, which the plan did not list) | |
| M7 (a) | online | "Nexus <release>, read online"; the release remembered | |
| M7 (b) | `--offline` with a remembered release | "remembered from an online read at <t>"; 38 pass; a configuration-cache entry reused | |
| M7 (c) | `--offline`, nothing remembered, no `-P` | fails before any task with NexusRelease's message | |
| M7 (d) | `-P…=2.2.32` | on Windows 38 pass; in WSL, whose home never fetched 2.2.32, Gradle's "No cached version …" | |
| M7 (d′) | `-P…=2.2.28` | "latest = pack 2.2.28: one jar, two legs"; 38 pass | |
| M7 (e) | `-P…=2.1.49` | 9, all on the latest leg (no `SystemProperties`, so no FAIL); the other legs pass | |
| M7 (f) | the remembered release edited, the configuration cache warm | Gradle drops the entry ("a build logic input of type 'NexusRelease' has changed") and runs the edited release; 38 pass | |
| S1 | the scan ignores call owners and descriptors | 4: N6 on 4 legs, nothing else | |
| S2 | the literal rule takes a `GL11` literal in any class | 1: N5, nothing else | after the fixes, the floor leg's literal-rule test |
| S3 | L3's link check never reports | 8: N2 and N3 on 4 legs; L3 passes | |
| R1 | `PassState`'s lookup keeps `GL11` in a local and also looks up `glFinish` | none before the fixes; after them 4: L2 on 4 legs | `CallSetTest`; the floor leg's `GlsmRedirectionTest` |
| R3 | the multi-release reader never reads a `META-INF/versions` copy | none before the fixes; after them 4: L1 on 4 legs | |
| S2b | S8's literal rule restored | 1: N5 (D01's form twice in one method) | the floor leg's literal-rule test |
| TS3 | `GITHUB_ACTIONS=true` with `-P…=2.2.28` | fails at configuration with the CI message; without `-P`, 38 pass | |
| B1 | an offline read reported as online, for an uncached 2.2.32 | fails at resolution and remembers nothing; for the cached 2.2.36, remembered at the task's start | |

- **Known and accepted** (23's S9 amendments give the remedies; the owner asked on 2026-10-24 to close the first two in this pass). (a) A remembered release whose jar Gradle's 30-day cache clean-up has deleted, or a `-P` release the Gradle home never fetched (M7 (d) in WSL), makes `--offline` fail with Gradle's "No cached version of com.github.GTNewHorizons:Angelica:<v> available for offline mode" instead of NexusRelease's hint. (b) A real `:gl` link or transform break also fails N3 and N4 beside the real check (M2: L2, L3, N3 on 4 legs and N4; M3: L3, N3 on 4 legs and N4), because their expected problem lists are exact over the shipped classes; every message names the cause, so it is noise in a red run, not a wrong verdict. (c) NexusRelease's online path changed in the review fixes and has not run online since, so CI's first run of the S9 commit is its first online run.
- **Pointers in the code.** `CallSetTest`'s class Javadoc, `GlsmSpikeTest`'s `FailModeLoader` Javadoc and `callset.txt`'s header now name `glsmLinkageTest` (S9 plan P16.5, critic A9 (c)).
- **CI.** That first run is still to come; its "Tests" status should show 2 136 passed, and the main session records it, with `glsmLinkageTest`'s time against its proposed budget (23's S9 amendments). *Update 2026-10-24:* run 40114221360 of 1d143c4 is green on its second attempt: "Tests" 2 136 passed, five jars with no `-dirty`, `<release>` read online as 2.2.36, and `glsmLinkageTest`'s tests 1.975 s against 10 s (23's S9 amendments). The owner cancelled the first attempt while GitHub showed neither its steps nor its log; that attempt had already posted the same "Tests" status, 18 s earlier.
- Not changed: §3's matrix and §4.1's "Links from" column; the GLSM render-core legs (2.2.8, 2.2.28) and the in-game matrix of §6; the S8 amendments' counts and their sentence on steps 3-4 (25:697-698), which are that step's record.

## Amendments after M0 step S12 (2026-10-26)

M0 step S12 built the root mod's client side on 2026-10-26, the root's first GL-calling code: `GlCaps`' probe with R-7's GLES read and R-13's gate, `ScreenDraw`'s two brackets, the TESR's and the GUI's, the chassis, the render box, and two static checks of the root's calls; 28's section "S12 implemented" records the step, 23's S12 amendments how its tests and in-game session ran, 24's S12 amendments the architecture, and the roadmap's S12 amendments the owner's answers. Applied in place, marked 2026-10-26: §1.1's unparsable row and its `after:angelica` bullet (25:51, :60), steps 1 and 4 of §4's test (25:251, whose note covers steps 1-2, and :259), the rows C04, C11, C32 and X02 (25:270, :277, :298, :376), R-1's test (25:429), R-4 (25:437, :440, :444), R-6 (25:450), R-8's reason and fix (25:458, :460), R-9 (25:462), §5.4's attrib bits (25:521), §6's note on the latest leg (25:544), and the S9 amendments' L2 and class-set bullets (25:718, :721). Recorded here:

**R-4, amended** (S12 decisions D23, the owner's choice; D24 and D25, the main session's):

- *Rule:* inside one `glPushAttrib(GL_ENABLE_BIT | GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT | GL_LIGHTING_BIT)` level, 0x6140, the host TESR changes only FFP lighting (F09) and the lightmap coordinates (X01), and it sets the unit-0 texture binding with C11, to its texture before its draw and to 0 after it, both inside the level; the pop restores no binding. The GUI's quad binds the same way and pushes no level, since the binding is the only state it changes. The rest of R-4 stands: no blend, alpha-test, depth-mask, colour-mask or per-unit texture change, and the expand pass writes alpha 255.
- *As built* (`client/render/ScreenDraw`): `world` builds its `Tessellator` quad first (`startDrawingQuads`, the picture's outward normal, four vertices), then makes C04 `glPushAttrib(0x6140)`, F09 `glDisable(GL_LIGHTING)`, X01 `setLightmapTextureCoords(lightmapTexUnit, 240, 240)`, C11 of the texture, the quad's `draw()`, C11 of the literal 0 and C05 `glPopAttrib`, with nothing after the pop; `gui` builds its quad, then makes C11 of the texture, `draw()` and C11 of 0. The TESR's two faces, the present texture and the "no graphics" icon of the block atlas, and the GUI's two pictures all go through these two methods.
- *Why:* Angelica's `glPopAttrib` restores a pushed `GL_TEXTURE_BIT` binding in GLSM's cache (`AG@2.2.8:GLSM:3631-3635`, `popState`'s non-boolean stacks) and in the driver (`:3791-3805`, the rebind at `:3800`) only, without the `TEXTURE_BIND` event and the `RenderSystem` update that `glBindTexture` makes on a cache miss (`:1986-2000`: the event at `:1997`, `RenderSystem.bindTextureToUnit` at `:1999`). Iris listens to that event (`AG@2.2.8:src/main/java/com/gtnewhorizons/angelica/iris/IrisGLSMBridge.java:238-243`), so its bind-driven uniforms (`atlasSize`, `gtextureSize`, `gtextureId`; `AG@2.2.8:src/main/java/net/coderbot/iris/uniforms/CommonUniforms.java:78-100`) and PBR maps would stay on the TESR's texture while the next renderer draws with the restored one, until a bind that misses GLSM's cache (S12 review gl-3; the same at 2.2.36, by `javap` of its dev jar). With every change made by `glBindTexture`, GLSM's cache, `RenderSystem` and Iris agree. Binding 0 rather than leaving the texture bound keeps O-16: a later START pass never saves, deletes and rebinds the name of a texture it has just deleted. The binding the TESR found is not put back: vanilla's TESRs leave their own textures bound, the chest's and the sign's among them, and the dispatcher sets only the lightmap and the colour before each TESR (`MC/net/minecraft/client/renderer/tileentity/TileEntityRendererDispatcher.java:121-125`), so no later renderer can rely on it; Iris treats id 0 as an untracked texture, with the default PBR maps and an `atlasSize` of 0, as it treats OpenGPU's. Since the floor is 2.2.8, an upstream fix could not reach every supported release.
- *How it was decided:* the review's gl-3 was first recorded as deferred (D20), with the main session's statement that R-4 leaves OpenGPU no fix, which held only for R-4 as then written. The owner asked: "if this is a serious concern that *can* be fixed on our end by changing the OpenGPU state rules, can and should we do it that way? That is to say, is it a genuine Angelica issue or rather a clash between Angelica's behaviour and our logic?" The main session proposed the bracket without `GL_TEXTURE_BIT` and the bind of 0, and the owner chose it: "Alright, that looks good! You're right, our version floor guarantees older versions wouldn't receive the fix, no matter if it's implemented upstream or not. Yes, let's do it." So gl-3 is fixed, not deferred. D23's own citations of `GLStateManager` (`:3792-3805`, `:1985-2000`) were off by one; the lines above are the ones verified at tag 2.2.8 = 6d8cbf9 (D25). An upstream report to GTNewHorizons/Angelica, asking that the pop post `TEXTURE_BIND` and update `RenderSystem` when it restores unit 0's binding, has not been filed; it is the owner's call.
- *Checks:* `jarTest`'s check 11 holds the form on both shipped jars: `world` makes C04 with the constant 0x6140, F09, X01, C11 of its texture parameter, `draw()`, C11 of the literal 0 and C05, and `gui` C11 of its texture, `draw()` and C11 of 0, with no C04 or C05; each C11 is named by its operand on `GL_TEXTURE_2D`; each row comes once but C11 twice; between the first and the last call there is nothing but those and the draw (no jump, switch, return, throw, `invokedynamic` or other call); after the last there is nothing but the return; there is no try block; and no other client method pushes or pops (D24). `ScreenDrawTest`'s hygiene case, `theWorldAndGuiBracketsLeaveTheTesrEntryAsTheyFoundItButUnit0BoundTo0`, starts each bracket from a TESR's entry state and compares `GlHygiene`'s snapshot after it, with unit 0 bound to 0. In game, V7's legs L2 and L4 (2.2.8 on the Intel iGPU and on the RTX 5060, no shader pack) saw a vanilla chest and a sign beside a bound display render with their own textures; the PBR-pack look is S15's (25:544's note).

**R-6.** One level in the START pass and one in the TESR, as written; the GUI's bracket, which pushed one level before D23, now pushes none.

**The bracket's two other review fixes** (S12 review gl-1 and gl-2; S12 decisions D19):

- The world quad carries the picture's outward normal, the cross product of its right and left edges, since with GLSM's `expandVertexFormats` a draw without normals takes GLSM's current normal, whatever another renderer set last (`AG@2.2.8:glsm/src/main/java/com/gtnewhorizons/angelica/glsm/streaming/TessellatorStreamingDrawer.java:116-120`) [proposal]. With lighting off, fixed-function GL ignores it; a shader pack's lighting reads it.
- Both brackets build the quad before their first GL call, so only the rows and the draw sit inside. Celeritas suppresses a `RuntimeException` from the TESR of an invalid tile entity (`AG@2.2.8:src/main/java/com/gtnewhorizons/angelica/rendering/celeritas/CeleritasWorldRenderer.java:571-590`), so a throw inside the bracket, such as `startDrawingQuads`' "Already tesselating!" (`MC/net/minecraft/client/renderer/Tessellator.java:253-259`), could leave the push unpopped without ending the game, an exception to the plan's premise that such a throw becomes a crash report (S12 plan, amendment I6). There is still no try/finally: javac's copy of a finally block would give the pop, or the bind of 0, two call sites.

**P01, a new row class: pass-through by design** (S12 decisions D17; S12 plan, amendment F9). §4.1 gains this row here, as D01 joined in the S8 amendments:

| ID | Call | Tier | `GLStateManager` target | Links from | Notes | Use |
|---|---|---|---|---|---|---|
| P01 | `GLContext.getCapabilities()Lorg/lwjgl/opengl/ContextCapabilities;` | core | none: not a `gl` name, so `GLSMRedirector` leaves it on LWJGL (14 §2.5) | passes through on 2.2.8, 2.2.21, 2.2.28 and 2.2.36 (`glsmLinkageTest`) | lwjgl3ify remaps it to its shim (14 §2.1); the class that calls it may read the `ContextCapabilities` fields it returns, from 14:56's all-three row (14:83's rule 2), and keeps none as an object (14:84's rule 3) | `GlCaps`' probe (14 §3) |

P names the row's kind, as `callset.txt`'s A, D and F do. It is §4's one expected pass-through on 2.2.x (25:259's note).

**The root's call set** (`src/jarTest/resources/io/github/mindbound/opengpu/jartest/clientcalls.txt`, read by `ClientCallSetTest`, `jarTest`'s checks 10 and 11). Rows by class under `client/`: `render/ScreenDraw` C04, C05, C11, F09 (the constant 2896) and X01 (the MCP name, `func_77475_a` in the release jar); `render/GlCaps` C01, C02 and P01; and §4.2's 13 deny-listed calls for every class, held equal to `:gl`'s `callset.txt`, which `jarTest` takes as a file input, ids and calls both ways, with every shared C row naming `callset.txt`'s call (S12 decisions D13). X02 is not called, and §4.1 gains no matrix row: the TESR and the GUI compute their vertices on the CPU, and the chassis centres its inventory cube with the `Tessellator`'s own translation. Check 10 runs steps 1-2 over every OpenGPU class of both shipped jars but `:gl`'s, `@SideOnly(CLIENT)` members of common code included (S12 review strength-6): every call on LWJGL or `OpenGlHelper` is a plain `invokestatic` that matches a row of its own class, each `glEnable`/`glDisable` by the constant right before it; every row is called in its class; no call is deny-listed, names `GLSync` or sets a generic attribute (V1); `Mouse` is called only under `client/gui/`; nothing calls vanilla's GL helpers, `TextureUtil`, `GLAllocation`, `RenderHelper` or any `bindTexture(ResourceLocation)` by its MCP or SRG names (strength-5); no `invokedynamic` or `ldc` handle to these, LWJGL class literal or GL entry-point name beside a reflective call reaches GL (R-5); and no other instruction names LWJGL but `GlCaps`' reads of P01's fields. `jarTest` went from 34 to 39 tests.

**`glsmLinkageTest` over the root** (25:721's obligation, met; widened by review strength-6). The task takes the root's `shadowJar` and `reobfJar` outputs as file inputs and reads each with `ZipFile`, never through its own classpath, which it asserts holds no `client/render/ScreenDraw.class`: every root class outside `:gl`'s package, 129 a jar, 17 client classes and 112 others, `:core`'s among them. On every leg (2.2.8, 2.2.21, 2.2.28, and 2.2.36 as remembered from 2026-10-24) two classes are rewritten in each jar, `GlCaps` and `ScreenDraw`: in the -dev jar 12 call sites reach 7 distinct `GLStateManager` methods (`glGetInteger(I)I`, `glGetString(I)Ljava/lang/String;`, `glPushAttrib(I)V`, `glPopAttrib()V`, `glBindTexture(II)V`, `disableLighting()V`, `setLightmapTextureCoords(IFF)V`), and in the release jar 11 reach 6, X01's `func_77475_a` being left to vanilla's body, whose own GL call GLSM rewrites; P01 passes through; and L4 folds the 19 capabilities of `:gl` and the root as the floor does. New tests: L2 and L3 for the root and N7, a root class calling A01, which fails the link check on 2.2.8 and 2.2.21 and links from 2.2.22, on four legs each, and N8, an LWJGL name left outside its class, on the floor; 38 tests became 51. On D26's tree the tests' sum was 0.784 s on Windows and 0.826 s in WSL, against S9's CI figure of 1.975 s; CI's figure for S12 comes with S12's first CI run (D17; 23's S12 amendments). *Update 2026-10-26 (S12's first CI run):* CI's figure is 1.028 s, in run 40357899553 of 3851e8a, whose latest leg ran on Angelica 2.2.39, Nexus's `<release>` that day (23's amendments after S12's first CI run).

**R-7, R-9, R-11 and R-13 as built:**

- R-7: `RenderSystem.isGLES()`, called by reflection through its class name and read only with Angelica loaded; a missing class or method, or any linkage error, means not GLES.
- R-9: `DisplayTile.getRenderBoundingBox` returns the block grown by 7 on every side, (x - 7, y - 7, z - 7) to (x + 8, y + 8, z + 8), from its first call, made under a private lock and published through a volatile field, since Celeritas' meshing threads call it too (`AG@2.2.8:src/main/java/com/gtnewhorizons/angelica/rendering/celeritas/AngelicaChunkBuilderMeshingTask.java:166-182`); `getMaxRenderDistanceSquared` stays vanilla's 64 blocks. Nearly every display is then a global block entity for Celeritas; at section-local 7 or 8 on all three axes, 8 of 4 096 positions, the box stays in its section, whose bounds hold all of it, which is harmless (S12 review spec-1; 03:102's note).
- R-11: `DisplayChassis` carries `@ThreadSafeISBRH(perThread = false)` from `Angelica:2.2.8:api`; without Angelica the JVM ignores the missing annotation.
- R-13: `GlCaps` reads `Loader.isModLoaded("angelica")` and, when it is loaded, the version from FML's indexed mod list, once in `ClientProxy.init`, and tests it against `VersionParser.parseRange("[2.2.8,)")`; a version that does not parse counts as supported (25:51's note). The reasons are taken in the order gate, GLES, GL 3.3, and an Angelica below the floor gives `"Angelica below 2.2.8"`. V7's leg L3, which the owner chose to run after all (S12 decisions D29), ran Angelica 2.1.49 on the Intel iGPU, Windows' GPU choice: its line read `angelica=2.1.49 gate=below ... graphics="Angelica below 2.2.8"`, the client installed `NoRenderHost` with that reason, and the bound display showed the "no graphics" bitmap through the block atlas, on its face and in its GUI.

**Mutants that touch R-4 and the linkage test** (S12 decisions D14, D21, D24), each in a WSL copy of the tree, never in the Windows tree, with a control that passed; the rows give the last run's result:

| Id | Change | Result |
|---|---|---|
| M76 | `world` pushes `GL_ALL_ATTRIB_BITS` | check 11 on both jars ("pushes 0xFFFFF") |
| M77 | `world` adds `glEnable(GL_BLEND)` | checks 10 and 11 on both jars |
| M78 | F09's constant through a local | checks 10 and 11 on both jars, and the root's L3 on 4 legs |
| M79 | a second push and pop in `world` | check 11 on both jars |
| M80 | `gui` calls `glVertexAttrib4f` | checks 10 (V1) and 11 on both jars; GLSM maps and links it |
| M81 | a `GL11::glFlush` handle in `GlCaps.probe` | check 10 on both jars (R-5) |
| M82 | `GlCaps.probe` calls A01 | check 10 on both jars; the root's L3 on 2.2.8 and 2.2.21 only |
| M83a | `DisplayRenderer` calls `GLContext.getCapabilities()` | check 10 on both jars; L2 on 4 legs |
| M83b | `DisplayRenderer` keeps a `GLContext` field | L2 on 4 legs; `jarTest` green |
| M103 | `GL_LIGHTING_BIT` dropped from 0x6140 | check 11 on both jars ("pushes 0x6100"); `ScreenDrawTest` green, an equivalent state, since `GL_ENABLE_BIT` also saves the lighting enable |
| M103b | the push without `GL_TEXTURE_BIT` | retired: since D23 that is the design (D24) |
| m109 | A17 dropped from `clientcalls.txt` | `ClientCallSetTest`'s D13 test |
| m110 | no `setNormal` in `world` | `ScreenDrawTest`'s three world image cases (the packed normals) |
| m111 | `world` pushes before it builds the quad | check 11 on both jars (the quad's `Tessellator` calls inside the bracket); `ScreenDrawTest` green |
| p3 | `GLAllocation.generateDisplayLists(1)` inside `world`'s bracket | checks 10 and 11 on both jars |
| p4 | `glFlush` in a `@SideOnly(CLIENT)` member of `DisplayTile` | check 10 on both jars (no rows outside `client/`); check 1 and the linkage legs green |
| p4b | A01 in such a member | check 10 on both jars; the root's L3 on 2.2.8 and 2.2.21 |
| p5 | `bindTexture(TextureMap.locationBlocksTexture)` in the TESR | check 10 on both jars |
| d1 | `world` without its bind of 0 | check 11 on both jars; `ScreenDrawTest` 5 of 7 (the world and TESR image cases, the hygiene case) |
| d2 | `world`'s bind of 0 after the pop | check 11 on both jars; `ScreenDrawTest` green (the same end state) |
| d3 | `world` pushes 0x46140 again, with its bind of 0 | check 11 on both jars; the hygiene case (the pop puts the entry's texture back) |
| d4 | `gui` without its bind of 0 | check 11 on both jars; the GUI image case and the hygiene case |
| d5 | `gui` pushes 0x6140 and pops again | check 11 on both jars; `ScreenDrawTest` green |
| d6 | `world` binds its texture in place of 0 | check 11 on both jars; `ScreenDrawTest` as d1 |
| d6b | `world` binds `texture - texture`, a 0 that is no literal | check 11 on both jars; `ScreenDrawTest` green |
| d7 | `world` calls `normal(corners)` after the pop | check 11 on both jars (nothing after the pop but the return) |
| d8 | `gui` calls `setColorOpaque_I` between its binds | check 11 on both jars |

- Not changed: §3's matrix and §4.1's table, whose new row is above, and its "Links from" column; R-4's band rationale in the 2026-10-13 amendments (25:683) and §5.2's row for other mods' TESR leaks (25:486), which still rely on R-4's untouched clauses; the S8 amendments' counts of `:gl`'s call set (25:697), which `callset.txt` keeps; the GLSM render-core legs and §6's in-game matrix, whose exit legs stay S15's: S12's in-game session ran Angelica absent, 2.2.8 through `runClient` and 2.1.49 below the floor, without the self-test or a shader pack.
