# OpenGPU preliminary research (2026-10-08)

Research corpus produced before any OpenGPU code was written. Start with
`24-architecture-gl-first.md`: it records the owner's decisions and carries
the current architecture, phased plan, open questions and risk register.
`09-architecture-synthesis.md` is the earlier decision document, written for
public servers and a software-authoritative renderer; its sections 1-2 remain
the feasibility record, and 24 supersedes its sections 3-6.

The plan of work built on this research is [`../roadmap.md`](../roadmap.md).

## How it was produced

Multi-agent workflow, all claims cited as `path:line` into the local checkouts
(OpenComputers-GTNH, Angelica, OC-LuaJIT, ocelot-brain, OC-JNLua) or as URLs:

1. Eight researchers wrote reports 00-07 in parallel.
2. Each report was checked by two adversarial verifiers (primary-source check,
   version/environment fit) and corrected by an editor; every report ends with a
   `Verification notes` section recording what changed.
3. Three architects wrote proposals 08-A/B/C from different starting
   constraints; two judges scored them; a synthesizer wrote 09.
4. A completeness critic listed gaps and contradictions; seven gap-fill
   researchers wrote reports 10-15 and 19; 09 and the affected base reports
   were amended (see each file's `Amendments after gap-fill` notes).
5. The owner chose GL-first rendering on the host and single player plus LAN
   as the priority. Four researchers wrote reports 20-23, each corrected in
   place by an adversarial verifier; a synthesizer wrote 24 and a reviewer
   fixed it in place. 24 was then amended with the owner's display-block
   decisions. Report 25 (two analysts, a verifier and a writer) then set the
   Angelica version floor the owner had delegated, and 02, 09, 20, 23 and 24
   were amended to match. Report 26 (three researchers, a verifier and a
   writer) then drew lessons from general-purpose engines and retro hardware,
   and 20, 22 and 24 were amended with the lessons it adopted. After the owner
   changed decision 5 to an ES 3.00-based graphics language, report 27
   specified it, and 22, 23 and 24 were amended to match.
6. 01 was re-checked against the GTNH OpenGlasses source (`GTNewHorizons/OCGlasses`).
   Before the roadmap (`../roadmap.md`, 2026-10-10), a consistency audit of 24,
   a sweep of every open question in 00-27 and a read of OC-LuaJIT were run, and
   24 and this index were amended. The owner's answers of 2026-10-13 (Angelica
   band dropped, energy from M1, OC-LuaJIT inputs) were then applied to 20-25
   and the roadmap.
7. The readiness check for M0 (2026-10-13, workflow run wf_42fc3d4f-f30)
   covered the artifacts, the reference sources, build and CI, the local
   environment and the plan, with every flagged item adversarially verified.
   It found no day-1 blockers, but stale and wrong statements in the docs. On
   2026-10-14 the owner accepted its defaults, the cited references were copied
   out of the Temp scratchpad or pinned (References and pins, below), the 06
   and 12 bench sources were archived next to `callbench/`, and 05, 11, 20,
   23, 24, 25, the roadmap and this index were amended.
8. M0 step S4 (2026-10-14) added the `:core` and `:gl-testkit` subprojects,
   the shadow-jar fix, the bytecode reference and `-dev`-jar tests, a
   headless-GL smoke test and the CI permissions, and 05, 23, 24 and the
   roadmap were amended to match.
9. M0 step S5 (2026-10-14, workflow run wf_3e3e37b3-af7) wrote 28: four
   investigators extracted what the corpus and the code say about the command
   stream, a drafter wrote the note, three critics (fidelity, implementability,
   Lua and forward compatibility) reviewed it, and a reviser checked every
   finding against the sources. On 2026-10-14 the owner accepted the defaults
   of its questions Q2-Q13; Q1 (the glyph source for `TEXT`) stays open while
   the owner's proposal, mcer12/PixelFonts, is evaluated. The edits 28 lists
   for other documents were then applied, and 08-C, 09, 21, 23, 24, 26, the
   roadmap and this index were amended.
10. 28's Q1 (2026-10-14) took two more runs. Workflow run wf_9a3bba48-eef
   evaluated the owner's proposal, mcer12/PixelFonts. Workflow run
   wf_d03273f8-867 surveyed bitmap fonts in four sweeps (terminal, small,
   display, wide coverage), with three measurers and a licence critic, and
   produced 29. The owner then pointed out that the draft judged fonts partly
   by OC text-screen criteria (grids, half-block cells, joined box drawing),
   which a pixel GPU that draws text at any pixel does not need. They chose
   X11 misc-fixed 6x13 as font 0, with 8x13 as the alternative. The main
   session checked font 0 (`cmdstream-checks/q1_font0.py`) and amended 28 in
   place, the roadmap, 24 and this index; a reviser rewrote 29 around the
   decision.
11. On 2026-10-15 the owner answered 29's F4 (`TEXT` gains a font byte now),
   deferred F5 (the advance of wide codepoints, with CJK, to M1) and accepted
   F8's defaults (integer scale ×1-×4 for `TEXT` and `BLIT` in M1). The main
   session amended 28 in place, updated and re-ran `cmdstream-checks/`, and
   recorded the answers in 29, the roadmap, 08-C, 24 and this index; workflow
   run wf_17aa6788-a0a checked the F4 and F5 edits, and its findings were
   applied.
12. Later on 2026-10-15 the owner answered 29's F3 (misc-fixed for M1), F6
   (proportional fonts in M1, faces later), F7 (misc-fixed's CJK companions;
   other CJK fonts later) and F9 (delete the ROM transcriptions). The main
   session deleted the 11 files and recorded the answers in 29, 28, 24, the
   roadmap and this index. Workflow run wf_e33bc51a-a12 checked those edits.
   Workflow run wf_ef4a27ce-c17 (three investigators, three adversarial
   verifiers) then traced the sources of misc-fixed's CJK faces (29 §10),
   and the owner narrowed F7 to 12x13ja. With the owner's agreement, the main
   session then deleted Daewoo's and Hanterm's fonts from those downloads
   (557 files), and workflow run wf_2355c476-81d checked the edits.
13. The owner then left 29's F5 to the main session, which measured OC's
   character widths (`font-survey-checks/report/f5_widths.py`) and decided it
   for M1 (29 §6.2): musl's `wcwidth` table without OC's `font.hex` overrides.
   Last, the owner took the writer's starting set for F6: Tiny5, Tiny5 Duo and
   ANAKRON 8x12, with Jersey left for later.
14. M0 step S6 (2026-10-15) built `:core`'s codec, validator and tier-E oracle
   from 28. Workflow run wf_5ac9daa7-07a planned it, with a critic whose 18
   findings were all taken. Run wf_b87d58b8-d23 wrote the main code, a naive
   validator from 28's tables alone and, without reading the Java side, an
   independent Python golden model (`cmdstream-checks/oracle_goldens.py`);
   run wf_7db59286-5da wrote the test fixtures and tests, one agent at a time.
   Run wf_e2e06e91-798 reviewed it in four dimensions, each finding checked by
   an adversarial verifier, and run wf_1a26d219-907 closed the confirmed gaps
   and re-ran the reviewers' mutants. The main session recorded the points 28
   left open in 28's section "S6 implemented", and amended 24, the roadmap and
   this index; workflow run wf_2699d104-7d7 checked those edits.
15. M0 step S7 (2026-10-15) built `:core`'s `Device`, the render SPI,
   `NoRenderHost` and `FakeRenderHost` from 28. Workflow run wf_8dadf76e-689
   planned it, with two critics; both found that frees deferred by an open
   frame could grow without bound, and the owner chose the fix ("Count pending
   frees", Q-S7-1). Run wf_e9b8f380-44b wrote the main code and, from 28 and a
   brief alone, a reference model (`NaiveDevice`); run wf_1a88084c-40a wrote the
   test fixtures and tests, one agent at a time, among them a model test that
   compares the two. Run wf_59a9bdf2-6d6 reviewed it in four dimensions, each
   finding checked by an adversarial verifier, and run wf_2bd6bb63-7da closed
   the confirmed gaps and re-ran the reviewers' mutants. The main session
   then let a fold that throws inside the executor's `execute` propagate
   rather than count as a refused hand-off, and made a status test wait for
   a fold instead of spinning (it had failed on two CPUs, which S7 took for
   CI's count; S8's first CI run reports 4 Gradle worker leases, Gradle's
   default of one per processor). It recorded the points 28 left open in
   28's section "S7 implemented", and amended 24, the roadmap and this
   index; workflow run wf_62516a40-f86 checked those edits.
16. M0 step S8 (2026-10-16) built `:gl` (the render core and the GL host
   core), `:gl-testkit`'s GL host, hygiene assertion, scenarios and legs,
   and font 0's build-time converter from 28. Workflow run wf_8005b365-3a6
   planned it, with two critics; the owner chose host-side texture mirrors
   for the bound that S7 had left open ("Eager mirrors", Q-S8-1) and an AMD
   run later (Q-S8-2). Run wf_57c75fcc-b8a wrote the GL code and the build
   in the repository and, in staging, the converter, the host core with its
   CPU tests and, from 28, 29 and the BDF format without reading the Java,
   an independent Python converter (`cmdstream-checks/font0_table.py`); run
   wf_c31e90be-f63 put the host core on GL and wrote the GL test host, the
   scenarios, the legs and the bench, one agent at a time, with fix loops
   that found two readback bugs in Intel's Windows driver. Asked which
   drivers get the readback `glFinish` that one of them needs (Q-S8-3), the
   owner answered on a condition, and the main session judged it met:
   every driver but those verified clean, NVIDIA so far. Run
   wf_8793515b-9ae reviewed it in five dimensions, each finding checked by
   an adversarial verifier (20 findings), run wf_8b7470ca-a80 fixed the
   confirmed ones and re-ran the mutants pinned to two real cores, and run
   wf_d17751f7-ef8 closed what that recheck found. On WSL2's llvmpipe (CI
   parity; the owner agreed to WSL for Linux runs), run wf_529cdd5a-538
   traced a failing scenario to a Mesa bug, with an independent check, and
   run wf_d0b5450b-085 added the copy wait and verified it adversarially
   over the whole test matrix. Run wf_c953affa-95a gave `CopyWaitScenario`
   a second phase whose exact count of copy finishes pins the copy wait's
   rule, stopped `DeviceConcurrencyTest`'s fold pool without an interrupt,
   recorded the points 28 left open in 28's section "S8 implemented",
   archived the llvmpipe reproducers in `driver-checks/`, and amended the
   roadmap, 24, 23, 25, 29, 20 and this index, each file checked by a
   refuting checker and the confirmed points fixed. The owner pushed the
   step as 08ca13b, and its first CI run, 38899286849, was green, with a
   "Tests" status of 2 096 passed, on llvmpipe from Mesa 25.2.8 with both
   readback waits on. 28's section "S8 implemented" checks its times against
   the plan's budgets: the legs, the model test and the font tests inside
   theirs, but `:gl:test`'s tests took 21.4 s against 20 s, 8.30 s of it in
   `GlLedgerTest`'s two-thread test, which a fix after 08ca13b then made
   linear without losing a mutant kill. The fix's own CI run, 38922626582
   of 2c4528e, was green with the same status; it restored `:core:test`
   from Gradle's build cache, put `:gl:test`'s tests at 8.6 s and that test
   at 0.42 s, and took the three main GL legs and the model test to 0.51
   to 0.89 of their first-run sums, for a cause not measured.
17. On 2026-10-24 a doc pass recorded that run in 28's section "S8
   implemented" (closing its open item on `:gl:test`'s time on CI) and
   §11.4, the roadmap, 23, 20 and this index. The same day the owner
   reported that OC-LuaJIT's `string.pack`, `string.unpack` and
   `string.packsize` have Lua 5.3.6's whole format language and that Lua
   5.3's bitwise operators with 64-bit results are in, both built on
   2026-10-17. The pass recorded that in 28 §9.3, §9.5, §11.1 and §13 (on
   OC-LuaJIT the 2D records keep the `char` path until M0 measures `pack`
   faster there), in 24, in the roadmap's measurements, M2, M3 and Track
   L, and in 04's runtime table, with amendments at the end of 24, 04 and
   the roadmap; 24 and Track L also record that
   OC-LuaJIT's LuaJIT has been built without the FFI since 2026-10-16,
   which closes two of Track L's suggestions; the operators' documented
   semantics close a third. Run wf_c9c0c5c9-2d3 did both, with a refuting
   checker and its confirmed points applied. It also found why every CI
   jar's version ends in "-dirty": GTNH's shared build-and-test workflow
   checks itself out into the workspace as `.gtnh-workflows/` and tees
   runServer's output to `server.log`, both untracked, so the version
   plugin's `git status --porcelain` is never empty. `.gitignore` now
   ignores both paths; a replay of CI's steps in a fresh clone kept the
   tree clean and the jars' names without "-dirty".

Target environment at the time: OpenComputers 1.12.64-GTNH, Angelica 2.2.21,
Forge 10.13.4.1614 on Java 8 + LWJGL 2.9.4 (the user's instance) and on
Java 17-21 + lwjgl3ify (GTNH packs), OC-LuaJIT commit a4994e6.

## Index

| File | Contents |
|---|---|
| `00-opencomputers-internals.md` | OC-GTNH component model, callbacks/budgets/retry, screen/VRAM/hologram sync, packet caps, persistence, Angelica touch points |
| `01-prior-art.md` | Pixel/framebuffer displays for OC, CC and other mods; data-transfer approaches; lessons |
| `02-angelica-compat.md` | GLSM redirector, core-profile context, Iris and Celeritas rules, the certified `glTexSubImage2D` + quad baseline |
| `03-minecraft-1710-rendering-and-networking.md` | Vanilla texture path, `ScreenRenderer` template, Forge 1.7.10 networking and caps, bandwidth envelope |
| `04-lua-boundary-and-runtimes.md` | Marshalling across Lua 5.2/5.3/5.4, LuaJ and OC-LuaJIT; limits; portable encoder and handle strategy |
| `05-dev-stack-and-testing.md` | GTNHGradle setup, dependency coordinates, runtime bytecode generation, dev runs, ocelot-brain adapter design, licensing |
| `06-graphics-engine-architecture-best-practices.md` | API shape, software rasterizer design with measured JVM throughput, shader language and compiler plan, compute model, determinism |
| `07-feasibility-and-performance-envelope.md` | Cost model, call-rate wall, threading policy, risk register, tier proposal, M0 definition |
| `08-proposal-A-server-software.md` | Server-authoritative software renderer with streamed framebuffers |
| `08-proposal-B-client-hardware.md` | Client-side GL rendering from a replicated command stream |
| `08-proposal-C-layered-hybrid.md` | Layered core, software reference backend, client mirror, optional GL; the adopted base |
| `09-architecture-synthesis.md` | Decision document: verdict, matrix, recommended design, phases, questions, risks, amendments |
| `10-gap-gtnh-pack-shipped-oc-config.md` | The GTNH pack's shipped OC config vs the user's instance; effective default Lua runtime; budget and RAM consequences |
| `11-gap-direct-call-overhead-measured.md` | Measured direct-call costs per runtime (harness in `callbench/`); budget stall protocol; table-argument RAM finding |
| `12-gap-client-mirror-feasibility.md` | Client-side mirror cost, memory, class loading, single-player bypass, cross-ISA determinism, Context lifecycle; mirror deferred to M3 |
| `13-gap-off-thread-packet-sending.md` | Forge 1.7.10 send-path thread safety; pool-thread sending via `scheduleOutboundPacket` |
| `14-gap-lwjgl3ify-glcaps-shim.md` | Capability probing under lwjgl3ify; the GlCaps question closed |
| `15-gap-keyboard-attachment-and-input-topology.md` | Keyboard attachment rule, display block input topology, host scope, touch projection |
| `19-gap-ocelot-brain-gtnh-parity.md` | ocelot-brain vs OC 1.12.64-GTNH machine model diff; which tests are trustworthy headless |
| `20-gl-first-client-rendering.md` | Client GL path with and without Angelica: GL 3.3 floor, offscreen integer surfaces, PBO readback, timestamp fences, the GLSM descriptor trap, GPU-hang safety |
| `21-integrated-server-render-handoff.md` | Server-to-host handoff in one JVM (RenderHost SPI), persistence with an in-flight tail, LAN guest streaming with measured bandwidth, dedicated-server options |
| `22-shader-and-compute-languages.md` | GLSL ES 1.00 graphics subset and its emitter, GLSL 4.30-syntax compute with CPU semantics, determinism rules, rack scheduling, the HBM RBMK check; section 1 superseded by 27 where they conflict |
| `23-gl-testing-strategy.md` | Headless GL in the GTNH shared CI, tolerance tiers for golden images, the ocelot GL test host, in-game and dedicated-server tests |
| `24-architecture-gl-first.md` | Current decision document: owner decisions, architecture, phased plan M0-M4 with M1.5 display block, questions, risks |
| `25-angelica-version-floor.md` | Angelica version floor: 2.2.8 supported, older gated off at runtime (the 2.1.14-2.2.7 best-effort band was dropped on 2026-10-13); linkage matrix over all 2.x releases to 2.2.30, the M0 GL call set and deny-list, code rules, test matrix, GTNH pack coverage |
| `26-lessons-from-general-engines.md` | Lessons from Godot, OpenSceneGraph, O3DE, small-program shader languages and PS1/N64/Dreamcast/Quake/Doom techniques: typed handles, GL state hygiene, a GL allocation ledger, crash guard, API dump, semantic attributes, palette ops, quantize and dither, a retro shader variant family; considered items and owner questions |
| `27-graphics-language-es300.md` | Graphics shader language as an ES 3.00-based subset: input form, accepted subset, built-ins against GLSL 3.30, integer guards matching compute, ANGLE driver workarounds re-checked, flat and noperspective, interface conventions, shared frontend, effort, an index8 example |
| `28-command-stream-v1.md` | Command stream v1 for M0, accepted by the owner on 2026-10-14, with its glyph-source question (Q1) answered the same day (font 0 is misc-fixed 6x13) and `TEXT`'s font byte added on 2026-10-15 (29 F4): framing, error codes, versioning and the opcode map; the four M0 ops with per-format pixel results and the tier-E oracle; the minimal texture path and its ordering; the lifecycle and the `RenderHost` SPI; caps, charges and the Lua encoder with a worked frame; S6's test plan; owner questions; what S6 and S7 (2026-10-15) and S8 (2026-10-16) settled, with S8's owner decisions Q-S8-1 to Q-S8-3 and the readback bugs it found in Intel's Windows driver and in Mesa's llvmpipe |
| `29-font-survey.md` | Font survey for `TEXT` (2026-10-14), answering the follow-up to 28 Q1, revised the same day after the owner decided F1 (font 0 is misc-fixed 6x13), and on 2026-10-15 after F3, F4, F7 and F8 (decided), F6 (proportional fonts in M1, starting with Tiny5, Tiny5 Duo and ANAKRON), F5 (deferred to M1, then decided for M1 at the owner's request) and F9 (done, the ROM files deleted), and after a CJK provenance check (§10) that narrowed F7 to 12x13ja: why one size cannot serve a pixel GPU's labels, body text and headings; bitmap candidates by role (body text, small, display, wide coverage) measured for coverage, licence and provenance; a recommended M1 family by use, mostly misc-fixed; the licence obligations and atlas and jar cost; a font operand, advance and fill rules for `TEXT`; F1's decision record; owner questions F1-F9; the provenance check of misc-fixed's CJK faces |
| `callbench/` | The ocelot-brain call-overhead harness (`src/Bench.scala`, `src/bench.lua`, run scripts) and every log of the 2026-10-08 campaign. The scripts hard-code the research-phase scratchpad in `S=` (`run.sh:6`, `matrix.sh:3`, `run-extra.sh:3`) and read `$S/lib/*.jar`, 17 public jars (35 MB) that nothing rebuilds; fetch them as OC-LuaJIT's `test/native/smoke-test.sh:353-378` does. No M0 item re-runs callbench; whoever does makes `S` script-relative first |
| `cmdstream-checks/` | 28's check scripts (2026-10-14; `example.py`, `luabuilders.py` and `rev_checks.py` updated on 2026-10-15 for `TEXT`'s font byte): `q565.py` (quantize, expand and the `GL_R8` index recovery), `example.py` (the worked frame's bytes, handles, charges and caps), `luabuilders.py` (the Lua record builders against the layouts) and `rev_checks.py` (the revision's checks, among them a naive validator over the negative cases; on 2026-10-15 its `TEXT` string check became one scan, as 28:648 says), plus Q1's `q1_font0.py` (font 0's cell, coverage with Greek and Cyrillic, and atlas, and the synthetic table at 6×13 and 8×13; it takes the BDFs under `opengpu-refs\font-survey\misc-fixed\x\` as arguments), and S6's `oracle_goldens.py` (2026-10-15: an independent model of 28 §3, §5, §6.1–§6.4 and §7, with §2.2's reset, that re-derives every grid and total of `:core`'s `oracle-goldens.txt`; `--emit <case>` prints a case's grid; it takes the goldens file as its argument), and S8's `font0_table.py` (2026-10-16: font 0's glyph table re-derived from `6x13.bdf` without the Java converter's code, through a strict BDF 2.1 reading with the converter's checks; it prints the body's SHA-256, which `Font0Test` pins, and its CRC32, the coverage counts and `q1_font0.py`'s checks, then ALL PASS; `--self-test` runs a hand-made two-glyph BDF through the same code and each rule against a file that breaks it; it takes `core/src/fonts/misc-fixed/6x13.bdf` as its argument). Own code, MIT with the repo; Python 3 standard library only: run each with `python -I X.py` |
| `driver-checks/` | GL driver checks S8 left (2026-10-16), for 28's section "S8 implemented": `lp_stale_gettex.c`, a standalone C program (EGL surfaceless, no GL headers, no OpenGPU code) that shows Mesa llvmpipe's `glGetTexImage` into a PBO returning a texture as it was before a draw into it issued earlier (Mesa 26.0.8; the same code in CI's 25.2.8 and on main), and `lp_order_check.c`, which runs the same two scenes in either slot order (`lowread`, `highread`) and, as a control, with the lower scene sampling another texture (`noread`). Build each on Linux with Mesa's EGL: `gcc -O1 -o <name> <name>.c /usr/lib/x86_64-linux-gnu/libEGL.so.1`. Run with `EGL_PLATFORM=surfaceless LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe ./lp_stale_gettex [c\|f] [iterations]` (no argument: the bug; `c`, the last draw samples another texture, and `f`, a `glFinish` before the copy: controls) or `./lp_order_check lowread\|highread\|noread [iterations]`, 200 iterations by default. Mesa 26.0.8 gives 185–190 of 200 readbacks wholly stale by default, the controls 0, and `lowread` 199 of 200, `highread` and `noread` 0. A Mesa without the bug prints 0 wholly and 0 partly stale in every mode, and `lp_stale_gettex` then exits 0 (1 when any readback was stale, 2 when setup fails); it must show that before `GlBackend.finishesBeforeCopy` may leave out a later Mesa. Not yet reported to Mesa. Own code, MIT with the repo; each file declares only the EGL and GL prototypes and enums it uses |
| `font-survey-checks/` | 29's scripts and small outputs (2026-10-14), by survey role: `shared/fontmeasure.py` (BDF, `.hex` and PSF coverage and previews), `terminal/`, `small/`, `measure-cd/` (display and wide, with `ttfgrid.py` for pixel-grid outlines), `critic/` (ink and glyph-identity checks; the ROM transcriptions they compared against were deleted on 2026-10-15, 29 F9), `report/` (atlas cost with `setcost.py`'s per-set sums, `.hex` and BDF packing (`hexpack.py`, `bdfpack.py`), glyph boxes against the line (`boxcheck.py`), characters per line (`linefit.py`), the CJK companions (`f7_cjk.py`), OC's character widths against musl's (`f5_widths.py`), inked coverage of the misc-fixed faces (`ink.py`), tier mock-ups and their specs, the revision's `*-f1.out` outputs, and `29-font-survey-files.tsv`, the sha256 of all 327 downloaded files), `pixelfonts/` (the PixelFonts coverage check), `cjk/` (29 §10's CJK provenance check: the investigators' and verifiers' scripts and outputs, the workflow's results in `results/`, and `cjk-provenance-files.tsv`, the sha256 and status of its 2 349 downloaded files, 557 of them deleted on 2026-10-15). Own code, MIT with the repo; Python 3 standard library only. The fonts are not here: they are untrusted downloads under `opengpu-refs\font-survey\`, passed as arguments |
| `inflatebench/` | 12's CRC32 and deflate benches, archived 2026-10-14: `src/CrcInflateBench.java` (12 section 5) and `src/InflateMid.java` (12 section 1). Own code, MIT with the repo; each is one default-package file at Java 8 source level with no dependencies: run it in `src/` with `javac --release 8 X.java && java X` |
| `rasterbench/` | 06's software-rasterizer benches, archived 2026-10-14: `src/RasterBench.java` and `src/ShaderBench.java` (06 section 3.6), and the verifiers' re-run logs `logs/verify-bench.log` and `logs/verify6.log` (normalized to LF). Own code, MIT with the repo; each source is one default-package file at Java 8 source level with no dependencies: run it in `src/` with `javac --release 8 X.java && java X`. `RasterBench` takes `[W H [maxThreads]]`, `ShaderBench` takes `mono` or `mega` |

The completeness critic's eight medium- and low-rated gaps got no gap-fill
report of their own. As of 2026-10-10, later reports, 24's plan or the
roadmap cover all of them:

- Headless GL testing: covered by 23 (sections 1-2, `:gl-testkit`).
- Energy model: the policy is 24 section 7 answer 4 (charge through the
  node's power buffer as OC's GPU does, `ignorePower` respected); since
  2026-10-13, provisional costs are charged from M1 and recalibrated in M3.
- Angelica dev-run setup: an M0 task; 05 section 2 has the recipe, and 25
  section 6 pins the 2.2.8 dev jar for `runClient`.
- Bandwidth and compression on real content: 21 section 4.2 measured
  synthetic LAN content; real content is measured in M4 (24 section 8,
  risk 14). The internet envelope is moot under decision 2.
- Persistence hash-gating cost: moot for description packets, because NBT
  is metadata-only and pixels live in side files (24 sections 2.1 and 3.6);
  the side-file spec (layout, orphan cleanup, write ordering) is part of
  M1's persistence work.
- Font licence: done in M0 for font 0 (24 section 8, risk 16). On
  2026-10-14, 29 checked X11 misc-fixed 6x13, which ships in M0 (28 Q1);
  M1's further fonts are misc-fixed's faces with 12x13ja for CJK (29 F3,
  F7 and §10, 2026-10-15); its proportional fonts start with Tiny5, Tiny5 Duo and ANAKRON (29 F6).
- GTNH registration conventions: roadmap tasks, with lang, textures,
  creative tab, OC manual pages and `DeviceInfo` in M1, and recipes and
  pack integration by v1 (M4).
- OC Lua program conventions (chug-library): design input at the start of
  M1, for the simple-2D callbacks, `opengpu.lua` and the OpenOS-style
  terminal.

## References and pins

Reports cite the research-phase scratchpad as `scratchpad\X`; since
2026-10-14 that is `C:\Users\astro\Downloads\opengpu-refs\X` (328 MB, about
10,700 files), copied with the names kept from
`C:\Users\astro\AppData\Local\Temp\claude\C--Users-astro-Downloads-OpenGPU\170a14cc-5cc1-40e0-a7ec-6130b748eabf\scratchpad`
(2.3 GB, still present but an orphaned Temp folder), and every copied file's
checksum matches the original.

Clones:

| Name | Repo | Pin |
|---|---|---|
| `ocgtnh-master` | https://github.com/GTNewHorizons/OpenComputers | tag 1.12.64-GTNH = 1e4559f (shallow) |
| `angelica-git` | https://github.com/GTNewHorizons/Angelica | full history; HEAD 78abc05 (master, 2026-10-07, one commit after 2.2.30); tags fetched to 2.2.32 on 2026-10-14. The working tree is not checked out: read it with `git show <rev>:<path>`, as 25 does |
| `Angelica-2.2.21` | same repo | tag 2.2.21 = a8c29fa (shallow); its index, cleared during the research, was restored in the copy |
| `GTNHLib` | https://github.com/GTNewHorizons/GTNHLib | tag 0.11.52 = 343a327 |
| `lwjgl3ify` | https://github.com/GTNewHorizons/lwjgl3ify | tag 3.0.37 = c87172b |
| `rfb` | https://github.com/GTNewHorizons/RetroFuturaBootstrap | tag 1.1.2 = 78b6871 |
| `lwjgl2-src` | https://github.com/LWJGL/lwjgl | master 2df01dd (2016-12-08) |
| `gtnh-actions` | https://github.com/GTNewHorizons/GTNH-Actions-Workflows | be3d291 (2026-10-04; master is now 042346f) |
| `horizon-qa` | https://github.com/GTNewHorizons/Horizon-QA | tag 0.15.0 = e05abc0 |
| `ocglasses-gtnh\src` | https://github.com/GTNewHorizons/OCGlasses | tag 1.6.9-GTNH = bb1a1bb (source tree only) |
| `ocelot-harness-renno231\repo` | https://github.com/Renno231/ocelot-harness | `main` HEAD e5f81e1 (2026-10-03, version 0.1.1), cloned straight into `opengpu-refs` on 2026-10-14; its `lib/ocelot-brain` submodule (bec1cc6) is not checked out. Evaluated, not adopted: [05's amendments](05-dev-stack-and-testing.md#amendments-after-the-ocelot-harness-evaluation-2026-10-14), cited there and in 23 as `RH:` |

Jars and extractions:

| Name | Contents | Cited by |
|---|---|---|
| `angelica-jars\{2.2.8, 2.2.21, 2.2.28, 2.2.30, 2.2.32}` | `Angelica-<v>.jar` and `-sources.jar` from `https://nexus.gtnewhorizons.com/repository/public/com/github/GTNewHorizons/Angelica/<v>/`; 2.2.32 was fetched on 2026-10-14 and its SHA-1 verified against Nexus, and the owner's installed jar is identical | 25 |
| `lwjgl3jar` | `lwjgl-opengl-3.3.6.jar` plus an extraction | 14 |
| `lwjgl2jar` | classes extracted from the instance's LWJGL 2.9.4 nightly jar | 14 |
| `oc-jar-1.12.64` | an extraction of `OpenComputers-1.12.64-GTNH.jar` | 19 |
| `glcaps-diff` | the capability lists `l2.txt`, `lx.txt`, `l3.txt` and `l3new.txt`, and `l343\lwjgl-opengl-3.4.3.jar` from Maven Central | 14 |

Research evidence, made or downloaded by the research agents; it exists only
in `opengpu-refs` and in the session transcripts:

| Name | What it holds | Cited by |
|---|---|---|
| `angelica-floor` | 52 MB: the changelog notes, the linkage-work scripts, the call-set and class-matrix TSVs, traps and web API dumps (25's prefixes CL, LWK and related) | 25 |
| `floor25` | F25: the writer's extended call set, evaluator, results and table generators | 25 |
| `vchk`, `vscripts` | VCHK: the verifier's independent check (`vscripts\chk.py` with `vchk\*.txt`) | 25 |
| `engine-lessons`, `retro-scripts` | the researchers' notes (`godot-server`, `shader-ergonomics`, `retro-techniques`) and the scripts `q565.py`, `bayer.py` and `lut.py` | 26 |
| `bench`, `verify-bench`, `verify6` | the rasterizer and shader benches and the verifiers' re-run logs, also archived here as `rasterbench/` | 06 |
| `gap12\bench` | the CRC32 and deflate benches, also archived here as `inflatebench/` | 12 |
| `gtnh-pack-cfg` | the GTNH pack's shipped `OpenComputers.cfg` per pack version and the 2.8.4 and 2.9.0-RC-2 manifests | 10, 14 |
| `web22` | ANGLE sources and the GL 3.3 core, GLSL 3.30 and WGSL specs | 22, 26, 27 |
| `web27` | the GLSL ES 3.00 spec, the WebGL 2.0 spec and glslang sources | 27 |
| `glsles100.txt` | the GLSL ES 1.00 spec text (revision 17) | 20, 26, 27 |

Not copied; fetch these again from their pins if needed:

- `Angelica-master` and `angelica-full.git`: both 78abc05, redundant with
  `angelica-git`.
- The other 116 versions in `angelica-jars` (the remaining 2.x releases to
  2.2.30, and 1.0.0-beta29 and 1.0.0-beta66b): all on Nexus.
- Extractions that can be rebuilt from the instance's jars and the Gradle
  caches: gap12's `ct-jar`, `gtnhlib-jar` and `unimixins-jar`;
  `hodgepodge-classes`; `netty-4.0.10-src`; `mc-classes`.
- `callbench`'s `lib`, `classes`, `stage`, `modjar` and `ocjar`; its sources,
  scripts and logs are in `callbench/` here (see its row in the Index).
- Workflow intermediates (`edit*`, `answers*`, `*-results.md` and similar).

Clones not copied, by citing report:

| Cited by | Name | Repo | Pin |
|---|---|---|---|
| 01, 05 | `oclights2` | https://github.com/BlueAmulet/OCLights2 | 5438053 |
| 01, 05 | `plethora` | https://github.com/SquidDev-CC/plethora | 3c9b415 |
| 01, 05 | `oc2` | https://github.com/fnuecke/oc2 | tag 1.21.1/0.9.1 = 480af73 |
| 01, 05 | `mcvm` | https://github.com/Delta2Force/MCVmComputers | 17f3890 |
| 01, 05 | `ccdirectgpu` | https://github.com/tiktop101/CC-DirectGPU-Mod | 453be7a |
| 01, 05 | `pine3d` | https://github.com/Xella37/Pine3D | 2fbbd2c |
| 01, 05 | `cctweaked` | https://github.com/cc-tweaked/CC-Tweaked | c8b3f6a |
| 01, 05 | `mcef` | https://github.com/montoyo/mcef | db8953e |
| 01, 05 | `webdisplays` | https://github.com/montoyo/webdisplays | cf16d2e |
| 01, 05 | `ocglasses` | https://github.com/Starchasers/OCGlasses | 9c0c8cc |
| 01, 05 | `bennybox` | https://github.com/BennyQBD/3DSoftwareRenderer | b883775 |
| 01, 05 | `ocelot-desktop` | https://gitlab.com/cc-ru/ocelot/ocelot-desktop | 4cd3cfa |
| 22 | `hbmntm` | https://github.com/HbmMods/Hbm-s-Nuclear-Tech-GIT | 427c1ea |
| 26 | `godot`, `el-src\godot` | https://github.com/godotengine/godot | 65e8d16 |
| 26 | `el-src\godot-docs` | https://github.com/godotengine/godot-docs | 8973ac3 |
| 26 | `el-src\love-11.5` | https://github.com/love2d/love | tag 11.5 = 6eb8d54 |
| 26 | `el-src\love-main` | same repo | b7daef0 |
| 26 | `el-src\raylib` | https://github.com/raysan5/raylib | a043255 |
| 26 | `el-src\bgfx` | https://github.com/bkaradzic/bgfx | 73183be |
| 26 | `el-src\threejs` | three files, not a clone | none |
| 26 | `osg` | https://github.com/openscenegraph/OpenSceneGraph | 2e4ae2e |
| 26 | `o3de` | https://github.com/o3de/o3de | e031590 |
| 26 | `retro\doom` | https://github.com/id-Software/DOOM | a77dfb9 |
| 26 | `retro\quake` | https://github.com/id-Software/Quake | bf4ac42 |
| 26 | `retro\kos` | https://github.com/KallistiOS/KallistiOS | 84ed47b |
| 26 | `retro\libdragon` | https://github.com/DragonMinded/libdragon | e356bf3 |
| 26 | `retro\psx-spx` | https://github.com/psx-spx/psx-spx.github.io | 6d7d1bc |
| 26 | `retro\shaders\URP-PSX` | https://github.com/Kodrin/URP-PSX | 8367000 |
| 26 | `retro\shaders\godot-psx` | https://github.com/AnalogFeelings/godot-psx | 7e51770 |
| 26 | `retro\shaders\godot-psx-style-demo` | https://github.com/MenacingMecha/godot-psx-style-demo | 91bd249 |
| 26 | `retro\shaders\psx_retroshader` | https://github.com/dsoft20/psx_retroshader | d675407 |
| 26 | `retro\shaders\ultimate-retro-shader-collection` | https://github.com/Zorochase/ultimate-retro-shader-collection | tag 1.5 = 8cd971d |
| 27 | `angle27` | https://github.com/google/angle | 16a0aa0 (sparse) |

The owner's own checkouts under `C:\Users\astro\Downloads\` are unchanged:
OpenComputers-GTNH is a shallow 1.12.55-GTNH (f83bd95), which is why
`ocgtnh-master` is the 1.12.64 reference; Angelica is a shallow 2.2.8
(6d8cbf9), the floor; and OC-LuaJIT (62970a0, 2026-10-13), ocelot-brain
(e98a5b2) and OC-JNLua (da3d4d4) are full clones.
