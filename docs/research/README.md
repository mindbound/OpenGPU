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
| `25-angelica-version-floor.md` | Angelica version floor: 2.2.8 supported, older gated off at runtime (the 2.1.14-2.2.7 best-effort band was dropped on 2026-10-13); linkage matrix over all 2.x releases, the M0 GL call set and deny-list, code rules, test matrix, GTNH pack coverage |
| `26-lessons-from-general-engines.md` | Lessons from Godot, OpenSceneGraph, O3DE, small-program shader languages and PS1/N64/Dreamcast/Quake/Doom techniques: typed handles, GL state hygiene, a GL allocation ledger, crash guard, API dump, semantic attributes, palette ops, quantize and dither, a retro shader variant family; considered items and owner questions |
| `27-graphics-language-es300.md` | Graphics shader language as an ES 3.00-based subset: input form, accepted subset, built-ins against GLSL 3.30, integer guards matching compute, ANGLE driver workarounds re-checked, flat and noperspective, interface conventions, shared frontend, effort, an index8 example |
| `callbench/` | The ocelot-brain call-overhead harness (`src/Bench.scala`, `src/bench.lua`, run scripts) and every log of the 2026-10-08 campaign |

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
- Font licence: an M1 check (24 section 8, risk 16), needed before M1's
  licence-checked glyphs. M0's `TEXT` op needs glyphs earlier, so either
  the check moves into M0 or M0 uses a dev-only glyph source.
- GTNH registration conventions: roadmap tasks, with lang, textures,
  creative tab, OC manual pages and `DeviceInfo` in M1, and recipes and
  pack integration by v1 (M4).
- OC Lua program conventions (chug-library): design input at the start of
  M1, for the simple-2D callbacks, `opengpu.lua` and the OpenOS-style
  terminal.
