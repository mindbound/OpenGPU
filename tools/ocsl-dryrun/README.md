# tools/ocsl-dryrun

Reproduces the **OCSL GLSL-120 codegen dry run** — the gate DESIGN required before the IR format
freezes. The written report is [docs/dev/OCSL-GLSL120-DRYRUN.md](../../docs/dev/OCSL-GLSL120-DRYRUN.md)
(verdict GO-WITH-AMENDMENTS, 15 amendments folded into DESIGN); this directory is the part of it a
machine can re-run.

```bash
ANGELICA=/c/Users/you/Downloads/Angelica ./run.sh              # 330 core — the GL backends
ANGELICA=... TARGET=460 ./run.sh                               # 460 core — 2.2.x SDL GPU
```

Last run 2026-08-12: **4/4 at 330 and 4/4 at 460**, GLSL and SPIR-V, against Angelica at 2.2.3.

## What it actually proves

`CompatShaderTransformer.transform()` **never throws** — on AST failure it catches, logs, and falls
back to `fixupVersion()`, which only rewrites the `#version` line. So "it ran" proves nothing. The
discriminating signal is that the fixed-function built-ins are **gone**: `gl_TexCoord`, `gl_Color`
and `gl_FragColor` surviving under a core `#version` would not compile, and that is the silent
failure worth detecting. `RunTransform` prints the before→after for each and shouts if one survives.

Then glslang compiles the output twice: as GLSL, and to SPIR-V with `--auto-map-locations`, which
matches `SpirvCompiler.Options.vulkanForced460Core()`'s `autoMapLocations=true`. **That flag is
load-bearing, not a convenience** — without it all four fail, on Angelica's *own* injected
`angelica_currentAlphaTest` uniform rather than on anything of ours.

## Why there is a stub backend

The transformer takes its target from `RENDER_BACKEND.getMinGLSLVersion()`, and every real backend
wants a GL context. `stub/HeadlessStubBackend.java` is ~240 empty overrides plus a
`META-INF/services` registration, reporting whatever `-Dstub.glsl` says. That is also how the same
four programs get checked against 2.2.x's 460 target with no SDL device present — and it is a
genuine improvement on the original session, which approximated the 460 case by rewriting the
version line on 330-transformed text. Here the transformer really targets 460.

## Requirements, and the two traps

- **A built Angelica clone** — `(cd $ANGELICA && ./gradlew :glsm:classes)`. Not vendored here; see
  below.
- **JDK 21+**, which `run.sh` locates itself. **Trap 1:** this machine's default `javac` is Java 8,
  and against Angelica's class-file-65 classes it fails with `wrong version 65.0, should be 52.0` —
  which reads like a target-level problem and is not. There is nothing to lower. `run.sh` searches
  `$JDK`, then `PATH`, then `~/.gradle/jdks/*`. *(This is a fact about the dev clone only. The game
  runs Java 8, and Angelica ships Java 8 bytecode via Jabel.)*
- **Trap 2:** on Windows, `find` yields POSIX paths (`/c/Users/...`) that `java.exe` cannot resolve,
  and the failure arrives as a runtime `NoClassDefFoundError` long after `javac` was perfectly
  happy. `run.sh` converts with `cygpath -m`.
- **glslang** — optional; without it the transform still runs and validation is skipped. The pinned
  copy is `C:\glslang\bin\glslang.exe` (16.5.0); override with `GLSLANG=`.

## What is deliberately NOT here

- **Angelica's source.** The scratch work extracted copies of `GLStateManager`, `CompatShaderTransformer`
  and `SDLGPURenderBackend` for reading. Those are third-party sources and vendoring them into an
  MMPL-1.0 repo is exactly the shape of the problem the 2026-08-08 publish gate was filed for. The
  harness references Angelica through a classpath; nothing is copied.
- **`angelica_*.frag`, the transformed outputs.** Regenerable in one command, and they carry
  Angelica-injected boilerplate — so they are build output (`build/`), not sources. The four
  `shaders/dryrun_*.frag` inputs are ours and *are* tracked.
- **`cp.txt`.** The original run used a hardcoded classpath; `run.sh` derives it by globbing the
  gradle cache, because a checked-in absolute path is one machine's truth and nobody else's.
- The ad-hoc probe fragments (`t1_input.frag`, `z_s_460.frag`, …) from the original session. They
  answered one question each and the answers are in the report.
