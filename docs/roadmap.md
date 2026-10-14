# OpenGPU roadmap

OpenGPU is a Minecraft 1.7.10 Forge addon for GTNH OpenComputers (1.12.64-GTNH or later) that adds a pixel graphics card and display block: a 2D API, 3D with a programmable pipeline in a GLSL ES 3.00-based shader subset, and deterministic compute in GLSL 4.30 syntax, compiled to JVM bytecode and run on the server. Rendering is GL-first: the host client's GPU draws, and Lua reads back exactly what it drew. Single player and LAN come first; Angelica is optional but first-class.

*Living document — extend as steps complete or plans change. Rationale lives in [docs/research](research/README.md); start with [24], the decision document. This roadmap links to it rather than repeating it.* Efforts are focused solo-developer weeks, ±50 % [est.] ([24 §6]). `NN §x` cites report NN in `research/`. **[proposal]** marks scope this roadmap adds beyond 24 and 20–27.

| Milestone | Ships | Effort |
|---|---|---|
| M0 Vertical slice | 2D on a cube display end to end; the measurements that freeze tiers and budgets | 4.5–7 w |
| M1 2D product | first single-player release | 5–7 w |
| M1.5 Display block | screen walls with the final block shape | 2–3 w |
| M2 3D and shaders | 3D with user shaders; retro examples | 11–16.5 w |
| M3 Compute | deterministic compute on one or many cards | 4.9–6.2 w |
| M4 LAN and v1 | LAN guests, server viewers, API freeze, **v1** | 3–4 w |
| | **Total** (30.6–43.8 w with 27 §9's unrounded M2) | **30.4–43.7 w** |

## Research *(done, 2026-10-08 to 2026-10-13)*

- [x] Research corpus: reports 00–15 and 19–27 with their verification passes; method and index in [research/README.md][README]
- [x] Architecture chosen: GL-first, single player plus LAN first ([24]; supersedes 09 §3–§6)
- [x] Owner decisions 1–15 recorded in [24 §1]; owner answers to [24 §7] questions 1–8 (2026-10-08), to 9, 10, 12 and 13 and report 27's nine open questions (2026-10-09), and to this roadmap's draft questions 2–6 (2026-10-13: Angelica band dropped, OC-LuaJIT leg local until an artifact, 4 MB/s guest cap, provisional energy from M1, `string.pack` scope), with a fourth OC-LuaJIT item, `string.buffer`, added the same day ([24 answers]). Only question 11 stays open ([Open questions](#open-questions))
- [x] Pre-roadmap pass (2026-10-10): consistency audit of 24 against 20–27 and OC-LuaJIT, and a sweep of every open question in 00–27; fixes recorded in [24's amendments][24 amendments]
- [x] Readiness check for M0 (2026-10-13): artifacts, reference sources, build and CI, the local environment and the plan, with every flagged item adversarially verified; no day-1 blockers. Its corrections and the owner's answers (all its defaults accepted; the owner's instance now on the latest Angelica) applied on 2026-10-14 ([24 readiness])
- [x] Licence: MIT under the owner's name (`LICENSE`); `NOTICE` lists bundled third-party code (ASM, BSD-3-Clause, relocated)
- [x] CI trimmed: the template's release-packages workflow and version script removed; `build-and-test` (GTNH shared workflow) and `release-tags` remain. Repository detached from the ExampleMod1.7.10 template fork

## M0 — Vertical slice *(in progress)*

**Goal:** one card and one cube display render 2D on the host GPU end to end, with Lua bytes equal to FBO bytes, and M0 measures the constants that later choices depend on.\
**Depends on:** the research (done). OC-LuaJIT: an ocelot-brain-loadable build on the owner's machine; CI needs it only once a pinned artifact exists ([Track L](#track-l--oc-luajit-coordination)).

- [x] Preparation [proposal], done 2026-10-14 (step S1): the reference clones, jars and research evidence copied from the session Temp scratchpad to `C:\Users\astro\Downloads\opengpu-refs\` (checksums matched), names kept, so a report's `scratchpad\X` is `opengpu-refs\X`; pins in [research/README.md's "References and pins"][README refs]; the 06 and 12 benchmark sources archived next to `callbench/` as `research/rasterbench/` and `research/inflatebench/` (09 §5 Q10)
- [ ] Build and CI: the subprojects of [24 §3.1], `:gl-testkit`, `:ocelot-harness` and the `-selftest` jar included, wired into the shared workflow; the bytecode reference test; a check on the first `-dev` jar that project classes stay unrelocated (05 OQ2) [proposal]. S4 (2026-10-14) delivered `:core`, `:gl-testkit`, the bytecode reference test and the `-dev`-jar check (the root `jarTest`), all in `check`; the item stays open for `:ocelot-harness` (S10), the `-selftest` jar and the later subprojects
- [ ] Card and cube display: six facings plus floor/ceiling rotation, thickness (16 px) and tilt (0) saved in NBT, the shared screen-surface helper, one framebuffer per display, one constant render box (R-9); TESR (R-4) and the inventory path (R-8); minimal GUI with touch ([24 §6] M0 scope)
- [ ] 2D render path: `bind/submit/present/readPixels/getCaps`; `CLEAR/FILL_RECT/BLIT/TEXT` into R8UI (T2) and R16UI (T3); a minimal 2D texture in the surface's own format (create, update a region, free) as `BLIT`'s source and the self-test's sprite, also exercising G1, G8 around uploads and G13b's upload cost (owner, 2026-10-14; ≈ 1–2 d [est.], within M0's range; designed in step S5's command-stream v1 note), with other formats, index8 palette rows and the rest of the texture work left in M2; the 565 quantize (26 R8) and expand; PBO ring with timestamps; confirmed-state fold and `opengpu_frame`; heartbeat and stall ([24 §3.7])
- [ ] All four `RenderHost`s: `GlRenderHost`, `NoRenderHost`, `FakeRenderHost`, `GlTestHost` ([24 §3.1])
- [ ] Angelica: the GLSM linkage test over 25 §4's call set (2.2.8, 2.2.21, 2.2.28 and the latest release, 2.2.32 as of 2026-10-13: 25's linkage check stops at 2.2.30, so this test is the first check of a later release) and an `unmappedGL=FAIL` run on the same jars; the version gate (`[2.2.8,)`) and code rules R-1–R-13 ([25 §5.1]); the 2.2.8 dev jar booting in `runClient` (05 §2's recipe, [25 §6]), done early because the exit criteria depend on it [proposal]; booted on 2026-10-14 (S3)
- [ ] Engine lessons: typed handles (G1; the handle layout, `Handles`, is in `:core` since S4), state rules G8 and V1, `GlLedger` (G10), the API invariants as text (G2) ([26 §4])
- [x] OC floor check (decision 14): an FML version range if FML orders the `-GTNH` qualifier correctly, else a clear error at preInit. It does: FML's own `VersionParser` accepts `OpenComputers@[1.12.64-GTNH,)` for 1.12.64-GTNH (the version the OC dev jar reports in `@Mod` and `mcmod.info`) and every later GTNH release tried, numerically (1.12.100-GTNH included), and rejects 1.12.63-GTNH and a plain 1.12.64 (checked 2026-10-14), so the range goes in `@Mod` (S2), and `OcFloorRangeTest` (root `test`, S4) pins it: it runs FML's own `Loader.computeDependencies` on `@Mod`'s dependency string and the `containsVersion` check `Loader.sortModList` makes; no preInit fallback
- [ ] Minimal `/opengpu selftest`: pattern and sprite readback, no goldens
- [ ] Test shader pack, at the end of M0 (owner, 2026-10-14): a small MIT pack written for OpenGPU and kept in the repo as a test fixture, `shaders.properties` with `blend.gbuffers_block` plus minimal `gbuffers` programs. It is both the Iris pack and the pack that overrides block-entity blending (25 §6, the exit criteria), since no common 1.7.10 pack does (Complementary Reimagined r5.9.3, BSL v10.1.8 and Sildur's Vibrant v2.02 set no `blend.gbuffers_block`) and packs are usually not redistributable [I]; each run records the pack's version
- [ ] Compute spike (1 w): IR → `V1_8` bytecode via relocated ASM 9 in a child loader
- [ ] Measurements: render-thread cost per job (1/4/16 displays); PBO readback throughput and query latency on Intel, NVIDIA and AMD; vanilla `glGet` cost; OpenGPU fps against host fps 60/30/20; 1 k-triangle encode on 5.3/5.2/OC-LuaJIT (OC-LuaJIT on the owner's machine); OpenOS free memory at 192 KB; the G7, G13b and G16 inputs. Every run records `getCaps().hostRenderer`

**Order of work** (the readiness check's proposal, accepted by the owner on 2026-10-14, [24 readiness]; S1–S10 are M0's steps, not 26's lesson ids):
- **S1** References: done 2026-10-14 (Preparation above).
- **S2** Skeleton: done 2026-10-14: the template's `com.myname.mymodid` renamed to `io.github.mindbound.opengpu`; the OC 1.12.64 dependencies; the OC floor as an FML range in `@Mod`; `gtnhShared/` removed.
- **S3** Angelica: done 2026-10-14: the 2.2.8 `:api`, plus its dev jar behind `-PwithAngelica` (`=<version>` picks another release); the owner booted `runClient` with and without it, into a world each time, with no `forceEnableMixins` needed ([05 S3]).
- **S4** The subprojects on one Java 8 convention: done 2026-10-14: `:core`, with G1's handle layout as its first class, and `:gl-testkit`, both on buildSrc's `opengpu.java8` convention (jabel, `options.release = 8`, JUnit 5.14.4, every Test task on a Java 8 launcher); Shadow on, with an identity relocation that keeps project classes unrelocated and minimize off (05 OQ2); `OcFloorRangeTest` in the root `test`; the bytecode reference test and the `-dev`-jar check as the root `jarTest`, over the `-dev` and release jars; `glCompatTest` and `glCoreTest`, the headless-GL smoke test, with the renderer pinned to llvmpipe on CI; guards that fail a test task that ran no tests; the caller job's permissions plus `disable-spotless-pr` ([Track T](#track-t--testing-and-ci)). Verified locally (Intel iGPU, Java 8u504); the llvmpipe check comes with the first CI run after the owner's push. `:lang`, `:jit`, `:gl` and `:stream` join with their steps ([05 S4]).
- **GLSM spike** (half a day, right after S4's smoke test): bring GLSM up outside FML, which 23 §2.3 found feasible but never ran: after `GLStateManager` initialization, load a trivial GL-calling class through `GLSMRedirector.transformClassNode` on CI's llvmpipe, starting from S4's `glCoreTest` context (3.3 core, forward-compatible) in a `glsmTest` task. The GL-calling class is the spike's own, not `GlSmokeTest`, which clears integer surfaces with `glClearBufferu`, unmapped in GLSM ([24 §3.7]). It comes early because it is M0's least-proven piece and the GLSM render-core criterion depends on it. Record the result before S8; if it fails, raise it at once, since the GLSM leg would need a different design.
- **S5** The command-stream v1 note, for owner review, including the minimal texture path.
- **S6** The `:core` codec, validator and tier-E Java oracle.
- **S7** `Device`, the SPI, `NoRenderHost` and `FakeRenderHost`.
- **S8** The first `:gl` pass on `:gl-testkit`.
- **S9** The per-jar linkage and `unmappedGL=FAIL` runs.
- **S10** `:ocelot-harness` ([Track T](#track-t--testing-and-ci)).

S1–S8 take about two focused weeks [est.]. After them come `BLIT`/`TEXT`/T3, `GlRenderHost`, the blocks, the self-test, the compute spike and the measurements, with the test shader pack at the end.

**Decision points.**
- `TEXT` glyphs: run the unscii/Unifont licence check ([24 §8] risk 16) in M0, or use a dev-only glyph source until M1 [proposal].
- End of M0: the measurements freeze tiers and budgets (risk 8; the 192 KB figure tests decision 7's one-stick 2D target) and feed M1's decisions on G7, G13b and G16.

**Done when** ([24 §6], condensed):
- Ocelot on llvmpipe: Lua bytes == FBO bytes on 5.3 and 5.2 in CI, and on OC-LuaJIT on the owner's machine (non-gating until its pinned artifact exists); under `NoRenderHost`, `present` returns `nil,"no renderer"` and `readPixels` the cleared `frameId = 0` image.
- `runClient` with Angelica absent, with the 2.2.8 dev jar (repeated in the owner's instance on the latest release, 2.2.32 as of 2026-10-13, with the `-selftest` jar), and with OpenGPU's test shader pack as the Iris pack: pattern and sprite visible, self-test readbacks byte-identical, hygiene clean, panel correct next to other mods' TESRs under that pack's block-entity blend override.
- The GLSM render-core leg (2.2.8, 2.2.28) passes the hygiene case with a non-default scissor box (R-1).
- T2 2D at 20 fps on a ≥ 30 fps host; `runServer` loads no client class; quitting with 2 frames in flight neither hangs nor leaks.
- Spike ≤ 10 ns/element, same output on Java 8 and 21; every measurement above recorded.

**Effort:** 4.5–7 w.

## M1 — 2D product, single player *(planned)*

**Goal:** complete, persistent, budgeted 2D on every tier, shipped as the first single-player release.\
**Depends on:** M0 and its measurements. OC-LuaJIT: architecture-name detection, for `opengpu.lua`'s encoder choice.

- [ ] Design input first [proposal]: how OC programs drive `gpu`, `term` and `event` (the local chug-library's `chugraph`, `chug3d`, `chugkey`; OpenOS `term`/`tty`), for the simple-2D callbacks and `opengpu.lua` (README gaps)
- [ ] All 2D ops: palette, scissor, `COPY`, T3 blending in RGBA8 with one quantize per present, integer-span lines and circles, glyphs from a licence-checked font; all tiers; frame and canvas modes with `setFramePolicy` ([24 §3.3])
- [ ] Lua surface: simple-2D callbacks; `library()`/`opengpu.lua`, detecting OC-LuaJIT by `computer.getArchitecture() == "LuaJIT"`, never by `string.pack` ([24 §2.1])
- [ ] Palette and dither: `setPalette` as an ordered, persisted op with expand-only presents (R7); `setDither`, default `"none"` (R8); the `index8`/`GL_R8` format rule (G5, R5)
- [ ] Persistence: confirmed state plus tail, global write map ([24 §3.6], [21 §6]); versioned side-file decoders (G12); a side-file spec for layout, orphan cleanup and write ordering (README gaps) [proposal]
- [ ] Host safety: budgets, GPU timer, suspend and disable, kill switch; the renderer device-list matcher, shipped empty (G6); the creation budget (G13a) ([24 §3.8])
- [ ] Energy: provisional, configurable costs modelled on OC's GPU (per-cell `power.cost.gpu*` over `basicScreenPixels`), charged per `present` and per 2D op class from the node's power buffer, `ignorePower` respected; the card's node gets a connector; `"not enough energy"` joins the status strings before the API dump ([24 §3.3], [24 §7] answer 4)
- [ ] Full OpenGPU GUI: keyboard, mouse and clipboard ([24 §3.10])
- [ ] Tests: `ImageCompare` E/Q/G goldens, Q/G thresholds calibrated on llvmpipe, softpipe and the owner's Intel; self-tests; `api-dump.json` with its CI diff (G12)
- [ ] Dedicated servers: option (a)'s graphics results ([24 §4]; compute follows in M3)
- [ ] Decisions on G7, G13b and G16 from M0's measurements ([24 §7], Considered)
- [ ] Pack basics [proposal]: lang, textures, creative tab, OC manual pages, `DeviceInfo` (README gaps); recipes follow by v1
- [ ] Publishing settings, decided before the release: `usesMavenPublishing`, a Maven target if any, and `jitpack.yml` (nothing publishes in M0)
- [ ] **First single-player release**

**Done when** ([24 §6], condensed):
- OpenOS-style terminal at 20 fps on T2.
- E-tier goldens exact on llvmpipe, softpipe and the owner's Intel, NVIDIA and AMD, Angelica on and off.
- Save/load with a suspended loop on 5.3, 5.2 and OC-LuaJIT (5.4 on the owner's instance), tail replay delivering `done`; a palette-only frame re-expands without re-rendering and survives save/load.
- 23 §5.1's lifecycle checklist passes; the abuse corpus ends in `busy`, budget stalls or suspension, with no server tick > 50 ms or host frame > 100 ms attributable to OpenGPU; `freeMemory` flat over 10 k frames.
- `runServer` smoke: `renderer = "none"`, `present` → `nil,"no renderer"`, `readPixels` → the confirmed image persisted before a restart.
- The ocelot Lua suite also passes on LuaJ (correctness only).

**Effort:** 5–7 w.

## M1.5 — Display block *(planned)*

**Goal:** screen walls of identical blocks with the final shape options of decision 13.\
**Depends on:** M1. M0 already carries the block seams, so walls need no data migration ([24 §6], display-block paragraph).

- [ ] Walls: identical same-tier blocks of one facing and shape on OC's model, rectangular, any block extends; maximum extent configurable, default 8 × 6; split and re-form on add, remove and chunk load
- [ ] One framebuffer and one quad per wall, drawn by the origin; frame geometry cached per shape, never rebuilt per frame
- [ ] Shape options after Energy Control's Advanced Info Panel, clean-room: thickness 1–16 px, horizontal and vertical tilt as one plane across the wall; a GUI settings tab, applied wall-wide
- [ ] Touch by ray–plane intersection on the tilted surface; wall aspect ratio reported to Lua, picture letterboxed; selection box follows thickness
- [ ] Every constituent keeps its own node (15 §2.2); render bounds per R-9

**Done when:**
- A 3 × 2 T2 wall forms and splits correctly on add and remove, across a chunk border and after save/load, with one picture spanning it.
- Touch lands within ±0.5 px at the corners of flat and maximally tilted walls; shape changes made from any block persist and reach the whole wall.
- GL object count and heap stay flat over 10 k frames.
- A wall grown after its first render is not culled under Angelica, including when a block becomes the origin after its own first render (25 R-9).

**Effort:** 2–3 w.

## M2 — 3D and shaders *(planned)*

**Goal:** programmable 3D in the ES 3.00-based subset ([27]), with the retro example set.\
**Depends on:** M1 (2D path, persistence, goldens); M1.5 precedes it in 24 §6's order. OC-LuaJIT: `string.pack` for uniform rows, eager proxy release for `PipelineDesc`.

- [ ] **Design review first (decision point):** [24 §7] question 11 (index-output 3D), S4 (one source per program) and G15 (the lighting examples' colour space, the index8 quantizer's distance metric)
- [ ] Resources: buffers, textures (`index8` with palette rows, R5), render targets, depth and stencil, dirty rects
- [ ] Pipelines: semantic streams, per-instance step, `sub`/`avg`/`addq` blends (S5, R11, R9); engine-derived transforms (S6) ([24 §3.3])
- [ ] Shader frontend: the full subset with trip counts, costing, `switch`, integer guards and range analysis; TIR and the `330 core` emitter, mangling, std140 packing; the interface additions of [24 §3.4] (S2, S5, S7, S10, R1, R2, R10b); unbound-sampler defaults (G5); the exact index8 quantizer (R8)
- [ ] Links: async link with `opengpu_program` and pending-link semantics (G4); program LRU
- [ ] Conformance: the integer conformance corpus on llvmpipe, Intel, NVIDIA and AMD against a Java reference ([27 §5]); the raylib corpus (S12); VS/FS lowered to SIR with structural checks
- [ ] GPU safety: layers 1, 2 and 5 (the crash guard, G11), with M1's layers 4 and 6 extended to 3D draws ([24 §3.8])
- [ ] The retro example set in `examples/` ([Track E](#track-e--examples))
- [ ] CI: the non-gating OpenGPU workflow (`runClient` under Xvfb, Windows llvmpipe/WARP, `glslangValidator`); S/T goldens calibrated on llvmpipe, softpipe, WARP and Intel; M1's Q/G thresholds re-checked on WARP
- [ ] Decide during M2, from measurement or the `GlLedger` (no work before then): streaming slots by PBO-ring slot (G9); per-tier pooling of transient targets, only on counts near the 256 MB cap or eviction churn (G14) ([24 §7], Considered)

**Done when** ([24 §6], condensed):
- A textured, lit 1 k-triangle scene with user shaders at 20 fps on T2 and T3 on the owner's Intel, within 3 ms GPU per frame.
- The hostile-shader corpus is rejected with `line:col`; it includes unbound samplers, and no draw reads a foreign texture (G5).
- A PS1-style example (snap, affine, vertex light, fog, CLUT) at 20 fps on T2.
- 10⁴ fuzzed programs link on llvmpipe and the three owner GPUs with zero driver rejections; GLSM leaves emitted source unchanged.
- The integer conformance corpus is bit-exact against the Java reference on llvmpipe and the three owner GPUs (NaN cases excepted).
- 1 k-triangle encode ≤ 5 ms on 5.3 and ≤ 2.5 ms on OC-LuaJIT.

**Effort:** 11–16.5 w ([27 §9]); index-output 3D adds ≈ 3–5 d if the review accepts it.

## M3 — Compute *(planned)*

**Goal:** deterministic compute on the server CPU, on one card or many, feeding graphics through the device tail.\
**Depends on:** M2 (shared frontend, SIR). OC-LuaJIT: `string.unpack` `<f`/`<d`/`<i8` for the `readBuffer` helpers, eager proxy release for compute lists.

- [ ] Compute profile of the shared frontend, with `switch` (27 §8.4); `double` and `int64_t` ([22 §2])
- [ ] SIR interpreter and bytecode emitter (whitelist loader, chunking); determinism rules D1–D5
- [ ] `dispatch`, `readBuffer`, command lists, DRR, tier caps, inline dispatch; compute → graphics through the tail ([24 §3.4])
- [ ] GPU safety layer 3: the `gl_Position` slice as a per-frame work guard; the graphics fuzz corpus interpreted in CI (seam 2); the conformance oracle switched to the SIR interpreter
- [ ] Buffer copies between cards in one OC server ([24 §7] answer 5)
- [ ] Energy: M1's provisional costs recalibrated against OC's GPU, plus per-dispatch compute costs ([24 §7] answer 4)
- [ ] Horizon-QA GameTests on `runServer` ([23 §5])
- [ ] The HBM RBMK design-evaluator example ([22 §5])

**Decision point.** Typed compute kernels (S14) only if the injectivity proof misclassifies common fuzz-corpus kernels (risk 10) or users ask for particle systems.

**Done when** ([24 §6], condensed):
- A 64 k kernel at ≤ 10 ns/element; bit-identical across 23's matrix, 1 against 4 cards included; 10⁵ fuzzed programs clean.
- The graphics fuzz corpus runs on the SIR interpreter in CI; the conformance oracle switch leaves results unchanged.
- No OC worker blocked > 0.2 ms; a maximal T3 dispatch spread within `compute.maxMsPerTick`, with no JDK 8 safepoint stall > 5 ms [target].
- Dedicated-server GameTests pass; the RBMK example runs K = 1 000 steps of 15×15 in one command list.

**Effort:** 4.9–6.2 w.

## M4 — LAN and v1 *(planned)*

**Goal:** LAN guests see the host's pixels, dedicated-server viewers get the confirmed image, and the API freezes for v1.\
**Depends on:** M3.

- [ ] Guest path: 13's pool send, 16×16 tiles, keyframes carrying the palette and palette records (R7), `CATCHUP`, the per-guest cap (4 MB/s by default, configurable) ([24 §4], [21 §4]); 13 §4.5's two-thread send stress test [proposal]
- [ ] Dedicated-server viewers: the persisted confirmed image as a keyframe over the LAN path
- [ ] Recipient rules: `displayRange` and `maxNetworkClientPacketDistance`
- [ ] `stats()`, documentation and examples ([Track D](#track-d--documentation-and-api), [Track E](#track-e--examples))
- [ ] API freeze, with the API dump linting [24 §3.3]'s invariants (G2)
- [ ] Bandwidth measured on real content ([24 §8] risk 14)
- [ ] Cross-vendor beta, whose reports fill the device list (G6)
- [ ] Recipes and pack integration (README gaps) [proposal]

**Done when** ([24 §6], condensed):
- Two-client LAN: guest framebuffer hash == host at every tier, including after palette-only frames.
- The beta includes a Gen9 Intel iGPU and, if obtainable, a GCN 1–3 AMD GPU.
- 3 guests × 2 T3 3D displays on wired LAN: typical content within the per-guest cap; the worst measured case (moving camera, ≈ 5 MB/s per guest, 21 §4.3) falls back to `CATCHUP` without desync, and the guest framebuffer hash equals the host's after recovery.
- A dedicated-server soak of ≥ 8 h, compute on 4 cards, 2 viewers receiving the confirmed image: no crash, no tick overrun attributable to OpenGPU, a flat heap.
- `unmappedGL=STRICT` clean.

**Effort:** 3–4 w.

### v1 release (end of M4)

- [ ] v1 on Java 8, with compute checked on a Java 17–21 pack server ([24 §3.9])
- [ ] Pre-release runs: Angelica 2.2.28 in game and the latest release in the owner's instance ([25 §6]), where only OpenGPU's own failures block the release and an Angelica regression newer than the pack's is reported upstream and worked around; a production-jar server (risk 12)
- [ ] The frozen API dump as the baseline for later CI diffs (G12)

## Track T — Testing and CI

Rationale: [23], [24 §3.9].

- [ ] Gating in the unchanged GTNH shared workflow (`xvfb-run` on llvmpipe; the workflow installs Zulu 8 and the build puts every Test task on a Java 8 launcher): compiler fuzzing, compute bit-identity, render-core tests on compat and GLSM contexts with the hygiene assertion, ocelot Lua bytes == FBO bytes on 5.3 and 5.2, the linkage test; `runServer` smoke from M0, Horizon-QA GameTests from M3. Since M0 step S4 the caller job grants `permissions: { contents: read, statuses: write, pull-requests: write }`, because since GTNH-Actions-Workflows 042346f (2026-10-12) the shared workflow's test-results step posts a commit status and, on a PR with a failing test, a comment, while a new personal-account repository's default `GITHUB_TOKEN` can only read contents and packages (without the grant the job stays green but logs a warning, and no Tests status appears); it also sets `disable-spotless-pr: true`, because the spotless-fix PR step pushes a branch and needs `contents: write`
- [ ] `:gl-testkit`: OpenGPU's own JUnit extension, not Angelica's LGPL fixtures (risk 16), started in S4 with `GlThread`, `GlContextExtension`, `@GlTest` and the smoke test; `GlLedger` assertions (G10) and `ImageCompare` tiers E/Q/G/S/T with sidecars and an expectations file (risk 13) remain
- [ ] Calibration: Q/G in M1 on llvmpipe, softpipe and Intel; the WARP re-check and S/T in M2 on all four
- [ ] Owner hardware: Intel iGPU and RTX 5060 on the main machine (picked per run with `dgpu-run.ps1`, `-Also` for the `java.exe` that `runClient` forks), RX 7600M XT on a second machine ([24 §7] answer 7); E-tier goldens and the conformance corpus per milestone and driver update
- [ ] Ocelot harness: ocelot-brain's sources at e98a5b2 as a git submodule or subtree (chosen in the harness commit; the shared CI checks out submodules recursively), compiled by the harness's Gradle Scala plugin with Scala 2.13.11 on a Java 8 toolchain, or on a Java 17 toolchain with scalac `-release:8` and javac `options.release = 8` if Zinc misbehaves on Java 8 ([23 §4]); ocelot-brain's three URL-only jars from asie.pl (`OC-LuaJ-20220907.1`, `OC-JNLua-20230530.0`, `OC-JNLua-Natives-20220928.1`) contain no LICENSE, NOTICE or COPYING entry, so they are vendored test-only with their sha256 sums and the upstream LuaJ, JNLua and Lua licence texts (MIT-style [I], checked against upstream before vendoring); `NOTICE` needs them only if they ever ship inside the mod jar; `NoRenderHost` by default, `GlTestHost` for `gl` tests; runtimes 5.3 and 5.2 (gating), OC-LuaJIT on the owner's machine, non-gating, until OC-LuaJIT publishes a pinned artifact (jar, linux-x64 native, ocelot adapter), then gating in CI ([24 §6]), LuaJ correctness-only from M1, 5.4 on the owner's instance; rules from the 2026-10-14 evaluation of Renno231's ocelot-harness, which is not adopted ([05 eval]): a generated `brain.conf` per profile with its values asserted, `Ocelot.librariesPath` in a per-run build directory, one Ocelot initialisation per forked test JVM, bounded waits (tick and wall-time caps, typed outcomes), and a build that fails when the brain submodule is off e98a5b2 or dirty
- [ ] Non-gating OpenGPU workflow from M2: `runClient` under Xvfb, Windows llvmpipe/WARP, `glslangValidator`
- [ ] Manual, per milestone: 23 §5.1's checklist and 25 §6's in-game matrix (2.2.8 via `runClient`, which exercises R-1 and R-8 in game, rules that matter before 2.2.21 and 2.2.23 respectively; the owner's instance on the latest release, 2.2.32 as of 2026-10-13, with the `-selftest` jar, as the "latest" leg; 2.2.28 before each release). 2.2.21 has no in-game leg: it stays in CI's linkage and `unmappedGL=FAIL` runs, and an in-game 2.2.21 run, if ever wanted, uses `runClient` on `Angelica-2.2.21-dev.jar`, not a second instance

## Track L — OC-LuaJIT coordination

The owner's four OpenGPU-motivated items (in progress; decision 10, [24 §6]'s OC-LuaJIT inputs, risk 19), each with the milestone that first needs it:

- [ ] `string.pack`/`string.unpack`/`string.packsize` with `<f`, `<d`, `<i4`, `<I4` and `<i8` and repetition counts: **M2** (uniform rows), then M3 (`readBuffer`). Widened 2026-10-13 by the two former suggestions: `packsize` because the kernel copies all three (OC's `machine.lua:901-903`; OC-LuaJIT's `build/native/kernel/machine.lua:946-948`); `<f`/`<d` byte-identical to 5.3, so "Lua bytes == FBO bytes" holds across runtimes; `<i8` on the doubles-only VM errors in `pack` for non-integral values or values outside [−2^63, 2^63), as 5.3 does, and `unpack` returns a double, exact below 2^53 and rounded to nearest above, like the 5.2 arithmetic fallback ([24 §3.3]). Until it lands, `opengpu.lua`'s arithmetic (`math.frexp`/`string.char`) encoder serves OC-LuaJIT; the `string.char` encoder stays the baseline everywhere ([24 §2.1])
- [ ] Eager release of JNLua table proxies: **M2** (`PipelineDesc`), then M3 (compute lists); mitigates risk 11 and helps M1's `freeMemory` criterion
- [ ] Detection via `computer.getArchitecture() == "LuaJIT"`: **M1** (`opengpu.lua`'s encoder choice). The name already reads `"LuaJIT"` in game (`OC-LuaJIT docs/roadmap.md:113`)
- [ ] `string.buffer` reachable from OC user code (owner, 2026-10-13): an optional fast path for `opengpu.lua`'s encoder on OC-LuaJIT; no milestone criterion depends on it. Caveats (a)–(d) below
- [ ] Also needed: a pinned artifact (jar, linux-x64 native, ocelot adapter). Until it exists, the OC-LuaJIT ocelot leg (Lua bytes == FBO bytes, the encode measurements) runs on the owner's machine, non-gating, while the shared CI gates on 5.3 and 5.2; then it joins CI as gating ([24 §6])

Suggestions for the OC-LuaJIT side, from the 2026-10-10 sweep and audit and a 2026-10-13 check of `string.buffer` in the OC-LuaJIT tree at aa12418 (LuaJIT paths under `prototype/watchdog/luajit/src/`, the tree `native/build-native.sh` builds by default, `:112`: a LuaJIT 1ee778a4 clone that OC-LuaJIT's git ignores, `.gitignore:43`, pinned per `native/build-native.sh:942-943`). Each is a suggestion for the owner, not an OpenGPU commitment; rows are in the [OC-LuaJIT roadmap]:

- [ ] *Suggestion (a), **must-fix before exposing `string.buffer`**:* the FFI is compiled in (`native/build-native.sh:242`: `LJ_FLAGS` has no `LUAJIT_DISABLE_FFI`), so the `LJ_HASFFI` buffer methods exist: `set(cdata, len)` (`lib_buffer.c:94-101`), `putcdata` (`:183-200`), `reserve` (`:202-214`), `commit` (`:216-224`) and `ref` (`:226-236`). `reserve()` and `ref()` run `ctype_loadffi`, hence `luaopen_ffi` (`lj_ctype.h:397-403`), which installs the cdata metatable with pointer indexing and arithmetic (`lib_ffi.c:846`; `__index` `:146`, `__newindex` `:163`, `__add` `:245`), and they return `uint8_t *` cdata (`lib_buffer.c:209`, `:231`). Pointer indexing is unbounded, so user code could read and write arbitrary process memory: a sandbox escape on any server. Build with `LUAJIT_DISABLE_FFI` (which also settles the `1LL` suggestion below) or remove those five methods from the buffer method table (for `set`, only its cdata branch, `:94-101`; the string form is safe)
- [ ] *Suggestion (b), persistence:* buffer objects are userdata, which eris-lj refuses by value (`serializer/eris_lj.c:1702-1711`; `docs/research/os-shape-census.md:18`), so a buffer live across a yield (an over-budget component call, `os.sleep`, `event.pull`) fails the save, and the machine comes back off with its RAM lost (`docs/ffi-decision.md:29-30`). It needs a persist route, e.g. buffers presented through a table proxy whose metatable carries the spkey/`__persist` function (persisting contents and encode/decode options), as OC's `machine.lua` does for other userdata
- [ ] *Suggestion (c), memory:* buffers grow by doubling (`lj_buf.c:19-25`), so a growing command buffer makes power-of-two single allocations near the RAM cap, where the allocator refuses instead of collecting and retrying (`roadmap.md:157`, the case behind the collect-and-retry suggestion below, whose Java-side retry does not reach growth inside Lua); `buf:reset()` keeps capacity (`lj_buf.h:136-144`), so one buffer reused per frame avoids regrowth; `buf:tostring()` copies once at submit (`lib_buffer.c:265-271`)
- [ ] *Suggestion (d), to benchmark, not a speed claim:* `buf:putf("%c%c%c%c", …)` appends bytes without intermediate strings (`lj_strfmt.c:463`, into `lj_strfmt_putfchar`, `:246-254`); compare it against `string.char` + `table.concat` on OpenGPU's callbench
- [ ] *Suggestion:* for item 2, invalidate the released proxy and cancel its PhantomReference (freed refs are reused LIFO, and a stale `check()` would unref twice); release at the end of `resume`/`call`; test with OpenGPU's callbench `sinkTable_1000` under `-Xmx1g`
- [ ] *Suggestion:* for item 3, freeze `"LuaJIT"` as a documented contract and close the MineOS row's rename option (`roadmap.md:147`); document that the architecture cannot be detected by syntax
- [ ] *Suggestion:* the pinned LuaJIT (1ee778a4, with upstream a2ce8114) already parses `& | ~ << >>` but not `//` (`roadmap.md:146`): decide whether that syntax is a contract and document its semantics, before M0's encode measurement; OpenGPU's 04 §5 still reads "parse error" for OC-LuaJIT
- [ ] *Suggestion:* a consumable test kit for CI (the pinned artifact above) and M1's release: the Linux native loaded in a JVM (`roadmap.md:209`), a versioned artifact with both natives, the patched kernels, `LuaStateLuaJIT` and the ocelot adapter, the release guard (`:204`), and T10 in game (`:210`)
- [ ] *Suggestion:* Java-side collect-and-retry in `pushByteArray`/`pushString`, because a 500 KB T3 readback on a 1 MB machine is the exposed case (M1, M3; the allocator refuses where PUC collects and retries, collector rows `:155`, `:157`)
- [ ] *Suggestion:* `jit.opt` sizing by installed RAM (`:220`) and the JIT-on `strings` re-run (`:222`), before M0's 192 KB figure is trusted on OC-LuaJIT
- [ ] *Suggestion:* persistence-safe loops (`ipairs_aux` in perms, pure-Lua `gmatch` at every length, `:206`) and cross-build blob compatibility with "reboot after updating" in release notes (`:196`), for M1's save/load criterion
- [ ] *Suggestion:* a documented stats contract (`_OCLJ_JITSTATS`, `_OCLJ_GCSTATS`, `_OCLJ_WALLSTATS`) so the harness can assert `freeMemory` plus resident trace bytes flat (M1)
- [ ] *Suggestion:* an OpenGPU-shaped frame-loop workload in OC-LuaJIT's harness (16–64 KB buffers, 20 fps, 192 KB/1 MB/2 MB machines) for M0/M1
- [ ] *Suggestion (low):* build with `LUAJIT_DISABLE_FFI`, so `1LL` literals fail at load rather than at autosave, before v1; it is also one of (a)'s two fixes, so before exposing `string.buffer` if that fix is chosen
- OpenGPU does not need: the Value `__gc` fix (integer handles), `table.new`/`table.clear`, `bit`, `jit`, `utf8`, or a `component.invoke` fast path.
- OpenGPU-side counterparts [proposal]: `opengpu.lua` chunks large submits and readbacks and retries after a yield; 04 §5's table row and Summary 10 updated for that bit-operator syntax.

## Track E — Examples

Rationale: decision 15, [24 §3.4] ("Examples, not libraries").

- [ ] `examples/`: Lua programs plus shader sources, MIT and clean-room; third-party shader code only after a per-file licence check (S12)
- [ ] Every example runs in the ocelot GL test host, compared within 23's image tiers
- [ ] 2D showcase programs for M1's release, starting with the OpenOS-style terminal of its exit criteria [proposal]
- [ ] M2 retro set (R3, R4): `TEXTURE`, `CLUT`, `VCOLOR`, `LIGHT_VERTEX`, `LIGHT_PIXEL`, fog per vertex, per pixel or by table, `CUTOUT`, `SNAP` (`snapToPixel`), `AFFINE`, `SPRITE`; variant counts documented against 4 compiles per second per card and the 256-program LRU; the PS1-style showcase of M2's exit criteria
- [ ] Any time, since examples are not API: `FLAT`, `STIPPLE`, `DITHER_PS1`; the N64 3-point filter and atlas wrap (R13)
- [ ] M3: the HBM RBMK design evaluator on the console API common to upstream and NTM: Space
- [ ] M4: examples that run under `NoRenderHost` (risk 15); optional Shadertoy and LÖVE alias shims (S12)
- [ ] Distribution: an OC loot floppy; later OPPM or a separate repository if the set grows

## Track D — Documentation and API

- [ ] API invariants as text from M0 (G2), linted by the API dump at the M4 freeze
- [ ] `api-dump.json` from M1 (callbacks, signals, opcodes, record layouts, command-format version, `getCaps` keys, status strings), diffed in CI against the last release; the published `-api` jar ([24 §3.1])
- [ ] Versioned op decoders: side files carry the command-format version, and from M1 every earlier version decodes on load (G12)
- [ ] Lua docs: `getCaps` and status strings, frame policy and the latency model (21 §2.3), "check `getCaps().renderer`" and dedicated-server behaviour ([24 §4]), no hashing of shaded 3D readback, `int64_t` exact only below 2^53 on 5.2 and OC-LuaJIT, TDR behaviour ([24 §3.8] layer 6)
- [ ] Shader language reference ([27]) with the conventions of S10 and the ES 1.00 conversion hint (risk 18); compute reference: defined arithmetic and D1–D5 ([22 §2])
- [ ] Rasterization rules in GL terms (seam 4): 2D from M0, 3D from M2 ([24 §5])
- [ ] Residual wording 24's 2026-10-10 pass left in other reports: 23's `NullRenderHost` and `nil, reason` readback, 20 §7's `nil, reason`, 25 §6's in-game self-test without the `-selftest` jar (fixed 2026-10-14), 26's library wording and `og_snap` ([24 amendments])

## Track C — Compatibility

- [ ] OpenComputers floor 1.12.64-GTNH (decision 14), checked at load from M0; GTNH 2.8.x (OC 1.11.20, Angelica 1.x) unsupported; the floor can be lowered later
- [ ] Angelica ([25]): 2.2.8 and later supported; anything older (2.x below 2.2.8, all of 1.x) gated off at runtime with compute unaffected, by one check of the `mcmod.info` version against `[2.2.8,)`; no best-effort band (dropped 2026-10-13), no FML version range; the per-version roles of [25 §6]. Revisit only if decision 14's floor drops below 1.12.61 ([25 §8] Q4)
- [ ] GTNH pack coverage ([25 §7]): 2.9.0-RC-2 (Angelica 2.2.28, OC 1.12.64) supported and tested; the daily as "latest"
- [ ] lwjgl3ify: don't design for it, don't break it. Avoid `@Lwjgl3Aware`/`Lwjgl3ify-Aware` and `org.lwjgl` lookups by string (the bytecode test); no CI gate; compute checked on a Java 17–21 pack server before v1
- [ ] Lua runtimes (decision 10): 5.3 and 5.2 must pass, OC-LuaJIT first-class, 5.4 on the owner's instance only, LuaJ correctness-only
- [ ] GL 3.3 floor on both legs; GLES and older GL report "graphics unavailable", compute unaffected ([24 §7] answer 1); x86-64 only

## Parked — not on the active list

*Rows here are not being worked on. Each says what would bring it back.*

- [ ] **Software reference renderer** ([24 §5], ≈ 6–12 w [est.]). Triggers: graphics on dedicated servers, GL-free CI if llvmpipe becomes unusable, a reference for driver-bug triage, demand from hosts below GL 3.3. Seams 1–4 are kept from the milestone that introduces each
- [ ] **Typed shader entry functions** (26 S1 with S3, S9) and versioned typed contracts (S10). After v1; additive ([24 §7] question 9)
- [ ] **`#version 100` profile.** v1 has a converter and an error hint instead; revisit if users ask, ≈ 3–5 d ([27 OQ] 8)
- [ ] **Public servers and display-only viewers** (option (b); `CMDS`/`RESOURCE` ids reserved). Low priority under decision 2; the lossy lineage and internet buckets are dropped ([24 §2.3]). Back on demand once single player and LAN ship
- [ ] **Stock OC GPUs as a text console** on the pixel screen (`TextBuffer` binding). Decision 8: later
- [ ] **Further block designs** (decision 6), the GLES profile, compute v2 (`shared` plus `barrier()`, unused-result atomics): M5+ in [24 §6]
- [ ] **Touch in world** through the screen-surface helper ([24 §3.10], "a later option")
- [ ] **Item-hosted displays** for robots and tablets: not planned (15 §3)
- [ ] **Left out of v1 by 27's answers:** integer vertex stream formats, vertex-stage `texelFetch` (reconsider after M3's guard), `packHalf2x16`, uniform-block grouping; also no program-binary cache (26 G4; GLSM-unmapped)
- [ ] **Upstream courtesy:** raise the inert `defaultLua53=false` with GTNH's OC fork (09 Q11, 10). Blocks nothing
- [ ] **In-game confirmation of the per-call cost** (11 §5). Not design-relevant; can ride along with M0's measurement session
- [ ] **An APU test rig** (19 OQ1). Only if a rig ever uses an APU

## Open questions

*Genuinely open as of 2026-10-13. Each default stands until the owner decides.*

1. **Index-output 3D on T1/T2** ([24 §7] question 11, [26 §3.6]): colormap lighting and palette-cycled 3D, ≈ 3–5 d, not in the estimates. *Default: not scheduled (outside §6's estimates) unless the review accepts it, judged by whether palette-animated or colormap-lit 3D is part of "retro"; 2D palette ops (R7) ship in M1 either way.* **Decide:** M2 design review.

The 2026-10-10 draft's questions 2–6 (Angelica's best-effort band, how CI obtains OC-LuaJIT, the LAN per-guest cap against M4's criterion, energy before M3, `string.pack` scope) were answered on 2026-10-13 and are folded into the milestones and tracks above ([24 answers]).

[24]: research/24-architecture-gl-first.md
[24 §1]: research/24-architecture-gl-first.md#1-decisions-2026-10-08-amended-2026-10-09
[24 §2.1]: research/24-architecture-gl-first.md#21-carried-over-unchanged
[24 §2.3]: research/24-architecture-gl-first.md#23-dropped-or-deferred
[24 §3.1]: research/24-architecture-gl-first.md#31-modules
[24 §3.3]: research/24-architecture-gl-first.md#33-lua-api-deltas-to-09-32
[24 §3.4]: research/24-architecture-gl-first.md#34-shader-and-compute-languages
[24 §3.6]: research/24-architecture-gl-first.md#36-resources-and-persistence
[24 §3.7]: research/24-architecture-gl-first.md#37-rendering-legs
[24 §3.8]: research/24-architecture-gl-first.md#38-gpu-safety
[24 §3.9]: research/24-architecture-gl-first.md#39-testing-23
[24 §3.10]: research/24-architecture-gl-first.md#310-input-and-the-opengpu-gui
[24 §4]: research/24-architecture-gl-first.md#4-dedicated-servers-and-lan-guests-in-v1
[24 §5]: research/24-architecture-gl-first.md#5-deferred-work-item-software-reference-renderer
[24 §6]: research/24-architecture-gl-first.md#6-phased-plan
[24 §7]: research/24-architecture-gl-first.md#7-questions-for-the-owner
[24 §8]: research/24-architecture-gl-first.md#8-risks
[24 amendments]: research/24-architecture-gl-first.md#amendments-before-the-roadmap-2026-10-10
[24 answers]: research/24-architecture-gl-first.md#amendments-after-the-owners-answers-2026-10-13
[24 readiness]: research/24-architecture-gl-first.md#amendments-after-the-readiness-check-2026-10-14
[05 eval]: research/05-dev-stack-and-testing.md#amendments-after-the-ocelot-harness-evaluation-2026-10-14
[05 S3]: research/05-dev-stack-and-testing.md#amendments-after-m0-step-s3-2026-10-14
[05 S4]: research/05-dev-stack-and-testing.md#amendments-after-m0-step-s4-2026-10-14
[21 §4]: research/21-integrated-server-render-handoff.md#4-lan-guests
[21 §6]: research/21-integrated-server-render-handoff.md#6-persistence-under-option-2
[22 §2]: research/22-shader-and-compute-languages.md#2-compute-language-glsl-compute-syntax-cpu-semantics
[22 §5]: research/22-shader-and-compute-languages.md#5-hbm-ntm-reactors-as-the-sanity-check
[23]: research/23-gl-testing-strategy.md
[23 §4]: research/23-gl-testing-strategy.md#4-the-ocelot-brain-harness-under-option-2
[23 §5]: research/23-gl-testing-strategy.md#5-in-game-tests
[25]: research/25-angelica-version-floor.md
[25 §5.1]: research/25-angelica-version-floor.md#51-code-rules-unconditional-on-every-leg
[25 §6]: research/25-angelica-version-floor.md#6-test-matrix
[25 §7]: research/25-angelica-version-floor.md#7-gtnh-pack-coverage
[25 §8]: research/25-angelica-version-floor.md#8-open-questions-for-the-owner
[26 §3.6]: research/26-lessons-from-general-engines.md#36-index-output-3d-on-t1t2-r6-consider-owner
[26 §4]: research/26-lessons-from-general-engines.md#4-consolidated-lessons
[27]: research/27-graphics-language-es300.md
[27 §5]: research/27-graphics-language-es300.md#5-driver-workarounds
[27 §9]: research/27-graphics-language-es300.md#9-effort
[27 OQ]: research/27-graphics-language-es300.md#open-questions
[README]: research/README.md
[README refs]: research/README.md#references-and-pins
[OC-LuaJIT roadmap]: https://github.com/mindbound/OC-LuaJIT/blob/master/docs/roadmap.md
