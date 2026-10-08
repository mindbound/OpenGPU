# OpenGPU preliminary research (2026-10-08)

Research corpus produced before any OpenGPU code was written. Start with
`24-architecture-gl-first.md`: it records the owner's decisions and carries
the current architecture, phased plan, open questions and risk register.
`09-architecture-synthesis.md` is the earlier decision document, written for
public servers and a software-authoritative renderer; its sections 1-2 remain
the feasibility record, and 24 supersedes its sections 3-6.

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
| `25-angelica-version-floor.md` | Angelica version floor: 2.2.8 supported, 2.1.14-2.2.7 best-effort, older gated off at runtime; linkage matrix over all 2.x releases, the M0 GL call set and deny-list, code rules, test matrix, GTNH pack coverage |
| `26-lessons-from-general-engines.md` | Lessons from Godot, OpenSceneGraph, O3DE, small-program shader languages and PS1/N64/Dreamcast/Quake/Doom techniques: typed handles, GL state hygiene, a GL allocation ledger, crash guard, API dump, semantic attributes, palette ops, quantize and dither, a retro shader variant family; considered items and owner questions |
| `27-graphics-language-es300.md` | Graphics shader language as an ES 3.00-based subset: input form, accepted subset, built-ins against GLSL 3.30, integer guards matching compute, ANGLE driver workarounds re-checked, flat and noperspective, interface conventions, shared frontend, effort, an index8 example |
| `callbench/` | The ocelot-brain call-overhead harness (`src/Bench.scala`, `src/bench.lua`, run scripts) and every log of the 2026-10-08 campaign |

Gaps the critic rated medium or low that were not researched: energy model,
OC Lua program conventions (chug-library), Angelica dev-run setup,
bandwidth/compression measurements on real content, persistence hash-gating
cost, font licence, GTNH registration conventions, headless GL testing.
