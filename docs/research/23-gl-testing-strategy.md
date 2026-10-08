# 23 — Test strategy for a GL-first OpenGPU (tolerance-based image comparison)

Researcher: GL-testing track, 2026-10-08. Scope: the test strategy after the owner's decision that the host client GPU is authoritative for both 2D and 3D ("Option 2"), with no software/reference renderer. Builds on 05 §4 and 19 and corrects them where Option 2 changes the premise. Primary leg: Java 8 + LWJGL 2.9.4 (`2.9.4-nightly-20150209`), Angelica 2.2.21 present or absent; lwjgl3ify is out of scope (owner decision 9). Nothing was built or run; all claims are from source, published artifacts and CI metadata.

Path shorthand (all under the session scratchpad `C:\Users\astro\AppData\Local\Temp\claude\C--Users-astro-Downloads-OpenGPU\170a14cc-5cc1-40e0-a7ec-6130b748eabf\scratchpad\`): `ANG/` = `Angelica-2.2.21` (tag 2.2.21, commit a8c29fa, 2026-09-26); `LW2/` = `lwjgl2-src` (github.com/LWJGL/lwjgl master 2df01dd, `Sys.VERSION = "2.9.4"`); `GHW/` = `gtnh-actions` (GTNewHorizons/GTNH-Actions-Workflows master be3d291, 2026-10-04); `HQA/` = `horizon-qa` (GTNewHorizons/Horizon-QA e05abc0). `MCSRC/` = `C:\Users\astro\Downloads\OC-LuaJIT\build\rfg\minecraft-src\java`.

## Summary

- Headless GL already works in the GTNH shared workflow: it runs on `ubuntu-24.04`, apt-installs `mesa-utils xvfb x11-xserver-utils`, and runs `./gradlew build` under `xvfb-run -screen 0 1366x768x24` (PR #36 "Support OpenGL in gradle tests", 2024-02-09). Angelica uses exactly this to run real LWJGL 2.9.4 GL tests on Java 8 (compat and 3.3-core contexts) on every push. OpenGPU needs no separate workflow for GL unit, harness or ocelot end-to-end tests.
- What CI renders on: Mesa llvmpipe, OpenGL 4.5 core and 4.5 compatibility profile (not 4.6). Ubuntu 24.04 `noble-updates` currently ships Mesa 25.2.8. LWJGL 2 on Linux needs the `xrandr` binary. Use `Display` + FBO, as Angelica does; Pbuffer is possible but unproven.
- Angelica's GLSM fixtures create a real context. They are published (`glsm-2.2.21-test-fixtures.jar`) but compiled for Java 21 (class major 65), GLSM-specific and LGPL-3.0. Don't depend on them. Write a ~60-line OpenGPU extension of the same shape, and use Angelica's dev jar, which ships the GLSM classes and `GLSMRedirector` as Java 8 bytecode (checked here in `Angelica-2.2.21-dev.jar`), for the "under GLSM" leg, pinned to the floor 2.2.8 and the pack's 2.2.28 (25 §6); those classes call JvmDowngrader stubs, so the test classpath also needs `xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:1.3.5:downgraded-8`.
- Image tiers: E (exact), for 2D with integer-aligned quads, nearest sampling, `GL_DITHER` off and no `GL_LINES`; the GL point-sampling rule makes this vendor-independent. Q (≤ 1 LSB) for fractional blending. G (±1 px neighbourhood match) for scaled or rotated 2D. S (shaded 3D): per-channel ≤ 2 within one implementation; cross-implementation ≤ 4 plus a Sobel edge mask, a failing-pixel budget and mean SSIM ≥ 0.98. The same-implementation thresholds sit within piglit's 3-LSB rule; the cross-implementation S limit (≤ 4, edges handled separately) is one LSB looser than piglit and follows the dEQP/Skia Gold pattern of a small per-channel delta plus neighbourhood or Sobel edge handling (dEQP's 0.02-0.05 is an aggregate fuzzy-error metric, not a per-channel bound).
- The ocelot harness gets a `RenderHost` SPI. `NullRenderHost` covers the dedicated-server and no-renderer path. `GlTestHost` runs the production client renderer on an LWJGL 2 GL thread, so Lua → pixels tests run in `check` on llvmpipe. Run that JVM on Java 8: ocelot-brain compiles and runs on JDK 8 (OC-LuaJIT precedent). This corrects 05's "JDK 17".
- In-game: a dev-only self-test (a client command plus an OC Lua suite that asserts readback) reuses the same comparator. runClient under Xvfb is feasible (mc-runtime-test supports 1.7.10 Forge) but belongs in a separate, non-gating workflow. runServer smoke plus Horizon-QA GameTests (a shared-workflow input) cover "graphics unavailable, compute works".

## Findings

### 1. Headless GL in CI

**1.1 The GTNH shared workflow already provides an X server and Mesa.** `GHW/.github/workflows/build-and-test.yml:64` `runs-on: ubuntu-24.04`. `:66-69` runs `sudo apt-get install -y mesa-utils xvfb x11-xserver-utils`. `:114-133` installs Zulu JDK 8, 17 and 21. `:154` runs `xvfb-run --server-args="-screen 0 1366x768x24" ./gradlew --build-cache --info --stacktrace build`. History: `git log -S"xvfb-run"` and `-S"x11-xserver-utils"` both resolve to commit 87bfdc7 (2024-02-09, Raven Szewczyk), "Support OpenGL in gradle tests (#36)", whose body lists "Wrap test running in xvfb-run to provide a virtual framebuffer" and "Add xrandr". Because `build` depends on `check`, any Gradle `Test` task wired into `check` runs with `DISPLAY` set and Mesa installed. OpenGPU's caller is unchanged (`OpenGPU/.github/workflows/build-and-test.yml:10-13` → `...build-and-test.yml@master`). Release builds use `assemble publish -x test` (`GHW/.github/workflows/release-tags.yml:120-135`), so GL tests never gate a release job.

**1.2 Precedent: Angelica runs real GL tests in that workflow.** Angelica's caller is the same one-liner (`ANG/.github/workflows/build-and-test.yml:12`). `ANG/glsm/build.gradle.kts:218-223` defines `glCompatTest`, `glCoreTest` and `glSharedTest`, each "in their own JVM, so they get their own Display and GL profile" (`:271`). `:224-253` sets `javaLauncher` to Java 8 and `-Djava.library.path` from RFG's `extractNatives2`. `:261` excludes the GL tags from plain `test`. `:286-303` adds `verify*Ran` tasks that fail when a GL task "produced no TEST-*.xml … likely went NO-SOURCE", and `check` depends on them. The root project runs its `gl-core` tests the same way (own `glCoreTest` task, Java 8 launcher, `extractNatives2`), but attaches it only as `tasks.test { finalizedBy(glCoreTest) }`, with no NO-SOURCE guard (`ANG/build.gradle.kts:124-178`). The LWJGL version is `2.9.4-nightly-20150209` (`ANG/gradle/libs.versions.toml:3`). 74 test files carry `@GLCompatTest`/`@GLCoreTest` (grep count). Angelica's master build on 2026-10-08 (run 37723381134 for commit 78abc05) concluded `success` (GitHub REST `actions/runs`), and at that commit `glsm/build.gradle.kts:218-303` still wires the GL tasks into `check`. So a green run means Java 8 + LWJGL 2.9.4 created both a compatibility context and a 3.3 core forward-compatible context on the runner [high confidence; the job log needs admin rights and was not read]. `ANG/glsm/src/test/java/com/gtnewhorizons/angelica/glsm/GLSM_PushPop_UnitTest.java:189-190` ("This fails on the RESET test in xvfb", skipped when `vendorIsMesa()`) shows the tests really execute on Mesa under Xvfb.

**1.3 What llvmpipe exposes.** Mesa 25.2.8 `docs/features.txt:213` lists llvmpipe under "GL 4.5, GLSL 4.50 -- all DONE"; it is absent from `:228` (GL 4.6). Mesa 21.3.0's release notes list "GL 4.5 compatibility on llvmpipe" (`docs/relnotes/21.3.0.rst:38`). Khronos certified llvmpipe as OpenGL 4.5 conformant on 2020-11-02 (khronos.org news). mesa-dist-win states "llvmpipe is certified for OpenGL 4.5 in all OpenGL profiles" (`readme.md:176`). Ubuntu 24.04 `noble-updates` currently ships `libgl1-mesa-dri 25.2.8-0ubuntu0.24.04.4` (packages.ubuntu.com), and Launchpad shows the noble series moving 24.0.5 → 24.0.9 → 24.2.8 → 25.x. **The reference implementation therefore drifts** whenever the runner image or `apt-get update` picks up a new HWE Mesa.

With LWJGL 2's plain `Display.create(PixelFormat)` (a legacy `glXCreateContext`), Mesa returns the highest compatibility version, 4.5 compat. `ContextAttribs(3,3).withProfileCore(true)` returns a 4.5 core context [inference from Mesa's version selection; consistent with Angelica's profile-mask assertions passing]. softpipe, Mesa's second software rasterizer, is complete only to GL 3.3 (`features.txt:100` vs `:114`). It is selectable with `GALLIUM_DRIVER=softpipe` (`docs/envvars.rst:1078-1081`), which gives an independent second rasterizer on the same runner. Ubuntu's noble-updates Mesa builds it (`debian/rules:46` `GALLIUM_DRIVERS = softpipe`, `:118` adds `llvmpipe`; git.launchpad.net `ubuntu/+source/mesa`, branch `ubuntu/noble-updates`). Set `LIBGL_ALWAYS_SOFTWARE=true` alongside it, as `envvars.rst:1078-1081` describes the pairing.

Useful Mesa knobs, all settable per Gradle `Test` task with `environment(...)` inside the shared workflow: `LP_NUM_THREADS` (`envvars.rst:1294-1298`, default = cores), `MESA_GL_VERSION_OVERRIDE=2.1` ("select a compatibility (non-Core) profile with GL version 2.1", `:106-125`) and `MESA_EXTENSION_OVERRIDE="-GL_ARB_framebuffer_object"` (`:89-94`). The last two let CI exercise OpenGPU's no-Angelica minimum-GL code paths through LWJGL's `ContextCapabilities`.

**1.4 LWJGL 2 on Linux: requirements and pitfalls.**
- Natives: `lwjgl-platform-2.9.4-nightly-20150209-natives-linux.jar` contains `liblwjgl64.so` and `libopenal64.so` (listing of the Gradle-cache copy). RFG's `extractNatives2` task (a `MinecraftTasks` member, `retrofuturagradle-2.0.2.jar` strings `taskExtractNatives2`) extracts them; Angelica points `java.library.path` at it (`ANG/glsm/build.gradle.kts:251-253`).
- **`xrandr` is mandatory.** `XRandR.populate()` runs `Runtime.exec({"xrandr","-q"})` (`LW2/src/java/org/lwjgl/opengl/XRandR.java:72`) and swallows any `Throwable` into an empty screen list (`:145-149`). The installed `lwjgl-2.9.4-nightly-20150209.jar` has the same `java/lang/Throwable` handler (`javap -c`). Xvfb advertises RANDR, so `LinuxDisplay` takes the XRANDR path (`LinuxDisplay.java:200-216`) and then indexes `XRandR.getScreenNames()[0]` (`:950`). Without the binary that index throws `ArrayIndexOutOfBoundsException`, or init fails with "No modes available" (`:736-740`). This is why GTNH added `x11-xserver-utils` and why mc-runtime-test installs it too (§1.5). The one escape hatch is the system property `LWJGL_DISABLE_XRANDR=true` (`LinuxDisplay.java:213-215`, also present in the installed jar), which makes LWJGL fall back to XF86VidMode; whether Xvfb offers that extension was not checked (unverified), so keep `xrandr` installed and treat the property as a last resort.
- Use a 24-bit screen (GTNH passes `1366x768x24`; Debian's current `xvfb-run` default is also `x24`, `xvfb-run:16`) and request `PixelFormat().withDepthBits(24).withStencilBits(8)`, as Angelica does.
- **Display vs Pbuffer.** LWJGL 2 has no EGL/surfaceless desktop path, so both need an X connection. `Pbuffer(w, h, PixelFormat, RenderTexture, Drawable, ContextAttribs)` exists (`LW2/src/java/org/lwjgl/opengl/Pbuffer.java:214`), and its capability probe needs only the X display (`LinuxDisplay.java:1323-1339`), but it is not proven on Mesa/Xvfb in any precedent found. `Display.create` + FBO is proven by Angelica. Use Display; a Pbuffer is an optional fallback for local Windows runs, where a Display briefly opens a window.
- Run GL test JVMs on Java 8. Angelica deliberately launches all LWJGL 2 GL tests on a Java 8 toolchain (`configureGlsmJava8`, `configureAngelicaJava8`).

**1.5 Other precedents.** headlesshq/mc-runtime-test runs the Minecraft client in GitHub Actions, using HeadlessMC "for headless Minecraft launches" and Xvfb (`README.md:26-27`). It lists 1.7.10 Forge as supported (`:47`), and its CI matrix really runs it: `ci-data.json` builds `1_7_10` against Forge `10.13.4.1614-1.7.10` on Java 8 and runs `"mc": "1.7.10"` (`lexforge`, Java 8), with `xvfb` set for every run (`.github/workflows/lifecycle.yml:130-133`), and its `xvfb` path apt-installs `x11-xserver-utils` before `xvfb-run java … launch` (`action.yml:153-157`). Its 1.7.10 module creates and joins a single-player world and then quits (`1_7_10/src/main/java/me/earth/mc_runtime_test/WorldCreator.java:10-17`). pyvista/setup-headless-display-action does the same job for VTK on Linux and Windows (§1.6).

**1.6 What the shared workflow cannot host, and the Windows alternatives.** The shared workflow is a single fixed job. Callers cannot add apt packages (e.g. `glslang-tools`), add a matrix, run `runClient`, or use Windows. Its runServer JVM arguments are injectable only through the Horizon-QA inputs (`:34-48`, `:211-237`). Anything beyond `check` therefore goes into a second, OpenGPU-owned workflow file.

GitHub's Windows runners have no GPU, and the stock `opengl32.dll` is the GDI Generic 1.1 renderer, too old for FBO/GLSL [general knowledge, not re-verified here]. Two Mesa routes exist:
- **mesa-dist-win llvmpipe.** pyvista's script downloads `mesa3d-<ver>-release-msvc.7z` from pal1000/mesa-dist-win and runs `systemwidedeploy.cmd 1` (install the OpenGL drivers system-wide), then `7` (`windows/install_opengl.sh:11-23`); the latest release is 26.2.4 (2026-10-04). Per-app deployment puts Mesa's `opengl32.dll` + `libgallium_wgl.dll` next to the executable (`docs/drivers/llvmpipe.rst`, "Windows"). For a JVM that means copying them into `%JAVA_HOME%\bin` [inference: `java.exe` is the application].
- **GLonD3D12 on WARP.** "you can test it with Direct3D WARP software renderer built into Windows by setting `GALLIUM_DRIVER=d3d12` and `LIBGL_ALWAYS_SOFTWARE=1`" (mesa-dist-win `readme.md:80`); it is "certified for OpenGL 3.3 in core profile / forward compatible context and 3.1 in compatibility profile" (`:176`). WARP is Microsoft's rasterizer, independent of Mesa's, which makes it a good **second implementation for calibrating tolerances**, not a product target.

Verdict: the Linux shared workflow is the gating GL environment. A Windows (llvmpipe + WARP) leg is an optional calibration job.

### 2. A standalone GL harness outside Minecraft

**2.1 What the renderer code must look like to be reusable.** The harness can only run the production client renderer if that code (the "render core": command execution, FBO management, shader translation and upload, PBO readback, quantize/present) imports no `net.minecraft.*`. That rules out `Tessellator`, `TextureUtil` and `OpenGlHelper`, which pull in MC classes that need a game instance. The render core must call only `org.lwjgl.opengl.GL11..GL33/ARB*/EXT*` and run on one thread through an executor. A thin adapter in the mod drives it from `RenderTickEvent(START)` (09 M5 checklist) and draws the result in the TESR. Under Angelica, these LWJGL calls are redirected to GLSM, which maps GL11-GL45 plus `EXTFramebufferObject`/`ARBFramebufferObject` (02 §4, `GLSMRedirector.java:186-760`). This **amends 09 §3.7**, which allowed `OpenGlHelper` calls: in the render core use the LWJGL classes directly, and keep `OpenGlHelper` in the adapter.

**2.2 Harness design (recommended).** A `:gl-testkit` Gradle subproject (`java-test-fixtures`) provides:
- **`GlContextExtension`** (JUnit 5), modelled on Angelica's. It calls `Display.setDisplayMode(…)` and then `Display.create(PixelFormat(24 depth, 8 stencil)[, ContextAttribs])` on a dedicated GL thread. It logs `GL_VENDOR/RENDERER/VERSION`, asserts the profile mask, drains `glGetError` before each test and asserts `GL_NO_ERROR` after it. Two context flavours run in separate `Test` tasks (Angelica's one-JVM-per-profile rule, `glsm/build.gradle.kts:271`):
  - **compat**: no Angelica, plain `Display.create`, as vanilla Forge does;
  - **core-3.3-FC + GLSM** (§2.3).
- **A state-hygiene assertion** around every OpenGPU pass. Bound FBO (draw and read), program, VAO, active texture and the binding on each unit used, viewport, scissor, blend/depth/cull/dither enables, `GL_PACK_*`/`GL_UNPACK_*` alignment and the bound PBO must equal their pre-pass values. Pre-set the harness state to a non-default "MC-like" state first, e.g. an FBO bound as `framebufferMc` would be (`MCSRC/net/minecraft/client/Minecraft.java:1100-1103`). This class of bug otherwise only shows in-game under Angelica/Iris; the harness catches it deterministically.
- **Readback through the production PBO path**, returning `int[]` ARGB plus the output-format plane (index8 or RGB565). The comparator (§3) is plain Java with no GL, so the in-game self-test (§5) reuses it.

**2.3 Angelica's fixtures: what they do and whether to reuse them.**

What they do:
- `ANG/glsm/src/testFixtures/java/com/gtnewhorizons/angelica/glsm/GLSMCoreExtension.java:34-39` creates a **real** 800×600 LWJGL 2 `Display` with `ContextAttribs(3, 3).withProfileCore(true).withForwardCompatible(true)`. It then sets GLSM's main thread and calls `GLStateManager.initialize(GLSMInitConfig.builder().displaySize(800,600).directDrawer(t -> {}).enableDSA(false).build())` (`:41-48`). It asserts `GL_CONTEXT_CORE_PROFILE_BIT` (`:57-60`) and deactivates FFP emulation before each test (`:70-76`).
- `GLSMExtension.java` (the compat flavour):
  - latches `RenderSystem` on a throw-away core context, destroys it and recreates a compat `Display` (`:77-82`, `:108-120`);
  - sets the private static `GLStateManager.MainThread` through `sun.misc.Unsafe` (`:122-131`);
  - resets the VAO, matrix stacks and FFP state before each test (`:139-168`);
  - fails on GL errors, leaked display-list recorders or unbalanced matrix stacks after each test (`:171-197`);
  - skips on macOS (`:29-38`).

Reuse verdict:
1. They are published: `https://nexus.gtnewhorizons.com/repository/public/com/gtnewhorizons/angelica/glsm/2.2.21/` contains `glsm-2.2.21-test-fixtures.jar` and a Gradle `.module` with a `testFixturesApiElements` variant.
2. They are **Java 21 bytecode**. `GLSMCoreExtension.class` and the `glsm-2.2.21.jar` classes are `major version: 65` (`javap -v`). The `.module` declares `org.gradle.jvm.version: 21`, and the subproject toolchain is 21 (`glsm/build.gradle.kts:16`). Angelica's own tests JvmDowngrade them first (`:192-215`). A Java 8 OpenGPU test JVM cannot load them.
3. They test **GLSM itself** (FFP-emulation resets, display-list leak checks). They are JVM singletons (`started` statics) and LGPL-3.0 (`ANG/LICENSE:8-9`).

So: **do not depend on them; copy the shape, not the code.** For the GLSM leg, use `com.github.GTNewHorizons:Angelica:<v>:dev` with `<v>` = 2.2.8 (the floor) and 2.2.28 (the pack) (25 §6). The 2.2.21 dev jar examined here is a multi-release jar (`Multi-Release: true`, `JvmDowngrader-Version: 1.3.5`) with about 286 base `glsm/` entries plus Java 17 copies under `META-INF/versions/17` (437 `glsm/` entries in all), which a Java 8 JVM ignores; the base `GLStateManager`, `glsm.hooks.GLSMInitConfig` and `GLSMRedirector` are `major version: 52` (`javap -v` of the downloaded `Angelica-2.2.21-dev.jar`, 10.7 MB). `GLSMRedirector.transformClassNode(String, ClassNode)` is public (`ANG/glsm/src/main/java/com/gtnewhorizons/angelica/glsm/redirect/GLSMRedirector.java:854-858`); Angelica's `GLSMRedirectorTest.java:120` calls it directly. `GLSMRedirector` is major 52 in 2.2.8 and 2.2.28 as well (25 §4), and tag 2.2.8 has the same public `transformClassNode` (`GLSMRedirector.java:825`) and `glsm.hooks.GLSMInitConfig` (editor check in 25's `angelica-git` clone). A test `ClassLoader` that runs OpenGPU's render-core classes through it after `GLStateManager.initialize(...)` reproduces the in-game redirection without FML. This is feasible but was not executed. Two classpath requirements follow from the jar itself. (a) The downgraded classes call JvmDowngrader stubs (`xyz/wagyourtail/jvmdg/j9/stub/java_base/J_N_Buffer`, `j21/.../J_L_Math.clamp` and others in `GLStateManager` and `GLSMRedirector`, `javap -c`) that neither the dev jar nor the release `angelica-2.2.21.jar` contains. In game, GTNHLib's dependency loader injects `xyz.wagyourtail.jvmdowngrader:jvmdowngrader-java-api:1.3.5:downgraded-8` (`fml-client-latest.log`: "Adding library … requested by … gtnhlib-0.11.52.jar!/META-INF/gtnhlib_deps8.json"). The GLSM test task must add that artifact explicitly, or it fails with `NoClassDefFoundError`. (b) GLSM's own `api`/`implementation` dependencies (GTNHLib dev, eventbus, glsl-transformation-lib, antlr4-runtime, jcpp; `ANG/glsm/build.gradle.kts:122-128`) must be on the classpath too. The alternative is to run JvmDowngrader over `glsm-2.2.21-test-fixtures.jar` as Angelica does, but that still leaves the LGPL and GLSM-specific objections. The cost is coupling to Angelica internals (`GLSMInitConfig`, the `MainThread` field), so pin the Angelica versions in the test configuration and treat a break as an Angelica-upgrade task.

**2.4 Angelica's assertion style is a model worth copying.** Angelica mostly avoids stored goldens; it renders the same thing two ways on the same context and compares.
- `FfpFixture.assertPixelParity(... channelTolerance)` requires zero mismatching pixels within a per-channel tolerance (`ANG/glsm/src/testFixtures/.../ffp/FfpFixture.java:200-229`).
- `CubeParityFixture` calls it with tolerance 1 (`CubeParityFixture.java:281-282`).
- `FFPVertexLightingGLTest` checks shader output against a CPU solver within `2.0f / 255.0f` (`ANG/glsm/src/test/java/.../ffp/FFPVertexLightingGLTest.java:163-170`).

Same-context differential tests are immune to driver drift. OpenGPU should prefer them wherever two paths exist: GLSM leg vs compat leg, user shader vs built-in path, `COPY` vs re-draw, frame N vs a replay.

### 3. Tolerance-based golden images

**3.1 What GL guarantees.**
- **Repeatability**: "the resulting GL and framebuffer state must be identical whenever the command is executed on that initial GL and framebuffer state" (GL 2.1 spec, Appendix A.1). The same driver build on the same machine is therefore exact.
- **Polygons use point sampling**: "Fragment centers that lie inside of this polygon are produced… if two polygons lie on either side of a common edge… then exactly one of the polygons results in the production of the fragment" (§3.5.1, p. 109). For axis-aligned quads with integer window coordinates, no fragment centre (at x + 0.5) lies on an edge, so coverage is identical on every conformant implementation.
- **Lines are not exact**: algorithms other than diamond-exit are allowed if fragments "may not deviate by more than one unit in either x or y" and the fragment count differs "by no more than one" (§3.4.1, p. 103).
- **Dithering** chooses per pixel between c and c − 1, and "Initially, dithering is enabled" (§4.1.9, p. 212).

Consequences for the exact tier:
- Draw 2D primitives (lines, circles, glyph runs) as integer-aligned quads or spans, never `GL_LINES`/`GL_POINTS`.
- Disable `GL_DITHER` explicitly.
- Use `GL_NEAREST` with texel-centre coordinates.
- Avoid fractional blend factors.
- Index8 palette lookups through a 256×1 `GL_NEAREST` texture at (i + 0.5)/256 are exact [inference from the sampling rules].

**3.2 How the conformance and engine suites handle vendor differences.**
- **piglit**: default `piglit_tolerance = {0.01,…}`; `piglit_set_tolerance_for_bits` sets `3.0 / (1 << bits)` per channel, i.e. 3 LSB (`tests/util/piglit-util-gl.c:284-307`, piglit main c3aa5b9).
- **dEQP / VK-GL-CTS**:
  - `fuzzyCompare` blurs lightly and compares each pixel to a 3×3 bilinear surface of its neighbours, "to compensate for both 1-pixel deviations in geometry and aliasing"; "good threshold values are in range 0.02 to 0.05" (`framework/common/tcuImageCompare.cpp:201-229`).
  - `intThresholdPositionDeviationCompare` passes a pixel if any pixel in a search volume matches within threshold (`:1353-1444`).
  - The rasterization verifier classifies pixels as full, partial or no coverage from the implementation's sub-pixel bits and tolerates the partial ones (`tcuRasterizationVerifier.cpp:742-820`, `:2947`).
- **WebGL CTS**: `checkCanvasRect(…, opt_errorRange)` with `errorRange = opt_errorRange || 0`, i.e. exact unless a test widens it (`sdk/tests/js/webgl-test-utils.js:1296-1327`).
- **ANGLE**: per-pixel `EXPECT_PIXEL_NEAR`/`EXPECT_PIXEL_COLOR_NEAR(x, y, color, abs_error)` (`src/tests/test_utils/ANGLETest.h:318-352`). Vendor/backend failures are recorded as `SKIP`/`FAIL` lines in `angle_end2end_tests_expectations.txt` (2,670 such entries), not by loosening thresholds.
- **wgpu**: NVIDIA FLIP error maps with `ComparisonType::Mean` or `Percentile{percentile, threshold}`; "good initial values … in the [0.01, 0.1] range" (`tests/src/image.rs:100-140`).
- **Chromium Skia Gold**: `fuzzy` (max differing pixels, max per-channel or summed delta, ignored border) and `sobel` (black out pixels above an edge threshold, then fuzzy) (`content/test/gpu/gpu_tests/skia_gold_matching_algorithms.py:9-54`, `:176-191`).

The pattern is consistent across all of them:
- Exact where the spec makes it exact.
- For shaded content, a small per-channel delta plus either a pixel budget or a geometric search window.
- Special treatment for edges (neighbourhood search or Sobel masking).
- An expectations file for genuinely vendor-specific failures rather than a looser global threshold.

**3.3 Metrics OpenGPU should implement.** One `ImageCompare` class in plain Java 8, about 400 lines with no dependencies:
1. Per-channel max |Δ| and a histogram of |Δ|.
2. Count and fraction of pixels whose max-channel |Δ| exceeds t.
3. ±1 px neighbourhood match: a pixel passes if any reference pixel in its 3×3 window is within t (dEQP position deviation).
4. Sobel edge mask on the reference luma (Y = 0.299R + 0.587G + 0.114B), threshold 64, dilated by 1 px. Edge pixels are judged only by metric 3 with a looser t; non-edge pixels by metrics 1-2 (Skia Gold `sobel`).
5. Simple SSIM on luma: 8×8 windows, stride 4, C1 = (0.01·255)², C2 = (0.03·255)² (Wang et al. 2004), reported as the mean and the minimum window.
6. Output-format awareness: compare index8 planes as indices and RGB565 planes in 565 units. For shaded content compare the de-indexed RGB, because one LSB before quantization can flip a palette index.

On failure, write `expected.png`, `actual.png` and a magnified diff heatmap. The shared workflow uploads `build/reports/` on failure (`:156-163`), so write them under `build/reports/opengpu-images/`.

**3.4 Tiers and recommended starting thresholds.** Values are in 8-bit units. These are recommendations to calibrate (§3.5), not measurements.

| Tier | Content | Same implementation (goldens from CI llvmpipe vs a later llvmpipe) | Cross-implementation (owner's Intel, softpipe, WARP) |
|---|---|---|---|
| E exact | `CLEAR`, `FILL_RECT`, 1:1 and integer-scale `BLIT`/`COPY`, scissor, nearest-sampled sprites and glyphs at integer offsets, palette/index ops, 2D lines/circles generated as integer quads | 0 / 0 px | 0 / 0 px; any failure is a bug or a driver nonconformance, triaged into an expectations file |
| Q blend | fractional alpha, `MODULATE`/`ADD` with non-0/1 factors, colour conversion before quantize | ≤ 1 per channel, any count | ≤ 1 per channel; after 565/index quantize, ≤ 1 quant step on ≤ 0.5 % of pixels |
| G geometry | non-integer scale, rotation, sub-pixel offsets, any `GL_LINES` | 0 | ±1 px neighbourhood within 1; ≤ 0.5 % failing |
| S shaded 3D | Gouraud, texture-mapped, depth-tested, lit, user GLSL-ES shaders | non-edge ≤ 2, ≤ 0.1 % non-edge over; edges: neighbourhood within 8 | non-edge ≤ 4, ≤ 0.5 % non-edge over; edges: neighbourhood within 16; no pixel > 64 outside the edge mask; mean SSIM(Y) ≥ 0.98 |
| T transcendental-heavy | `pow` specular, fog, procedural noise, `LINEAR` minification | as S | SSIM ≥ 0.97 plus a coverage check only |

At T1 (160×100 = 16,000 px), 0.5 % is 80 px; at T2 it is 320 px. Store absolute per-scene pixel budgets with each golden, as Skia Gold does, rather than one global percentage.

**3.5 Golden management.**
- Generate goldens in CI on llvmpipe. A `-Dopengpu.golden.update=true` run uploads them as an artifact for review.
- Store PNGs plus a sidecar recording `GL_RENDERER`, `GL_VERSION`, the Mesa package version, the tier and the thresholds.
- Keep tier-E goldens implementation-independent, and also generate them from a tiny test-only Java oracle (rect fill, blit, palette). The oracle is a few dozen lines inside the tests, not a software renderer, so it does not reopen owner decision 3.
- Before freezing S/T thresholds, render each scene on llvmpipe, softpipe (`GALLIUM_DRIVER=softpipe`, same runner), WARP (Windows leg) and the owner's Intel GPU. Set each threshold to about 2× the worst observed delta and record the four results.
- Re-run the calibration when Ubuntu's Mesa changes major version.
- **Integer conformance corpus** (27 §5; M2): integer results of the graphics language read back exactly, not compared as images. It gates on llvmpipe against the Java reference (the SIR interpreter from M3) and runs on the owner's Intel, NVIDIA and AMD GPUs per milestone and driver update, with Angelica absent and present; deviations go into the expectations file of implication 7.

### 4. The ocelot-brain harness under Option 2

**4.1 What is testable without GL.** These tests need no X server and are the bulk of the suite.
- Lua API surface and argument validation on 5.3/5.2/OC-LuaJIT (+5.4 on the user's instance; LuaJ correctness-only).
- Command encoding/decoding, handle and resource lifecycles, and limit enforcement: sizes, counts, RAM accounting against one 192 KB stick for 2D and ≥ 1 MB for 3D.
- Budgets and stalls, `LimitReached` retry idempotence, signal coalescing at queue sizes 256 and 1024, `computer.freeMemory()` flat over 10 k frames.
- The ES 3.00-subset frontend (27): parsing, Appendix-A and `switch` rules, integer guards, error messages, and translation to `#version 330 core` (the `#version 120` dialect was dropped, 24 §7 answer 1). Use golden-text tests on the output. In the optional workflow, Khronos `glslangValidator` (it needs `apt install glslang-tools`, which the shared workflow cannot add) validates inputs as `#version 300 es` with a test prelude that declares the `og_` built-ins, strips S7 initializers and supplies a default `precision highp float;` (27 §1.2 accepts fragment sources without one, which ES 3.00 §4.5.4 does not) [inference], and validates outputs as `330 core`.
- **raylib corpus** (26 S12): convert, do not drop. `glsl100` files go through 27 §1.3's converter, which tests the converter; `glsl330` files get `#version 330` → `#version 300 es` plus `precision highp float;`. Files that rely on GLSL 3.30's implicit conversions (e.g. `glsl330/palette_switch.fs`, which divides an `ivec3` by a float) stay as expected rejections. The per-file licence check stays.
- **Compute**: kernels compiled to JVM bytecode. The determinism tests 09 aimed at frames now apply here: identical output bits under `-Xint`, `-XX:TieredStopAtLevel=1` and default C2, on JDK 8, 17 and 21 (all installed by the shared workflow, `:114-133`), across 1 vs N worker threads and multi-card dispatch. Keep the `CheckClassAdapter` and verifier oracles from 05 §4.
- Persistence: `ws.save/load` of resources, kernels and a suspended frame loop (Eris only; 19 §6's limits stay).
- **The no-renderer path.** With `NullRenderHost`, graphics calls must return the documented error (or no-op, per the API spec), readback must return `nil, "<reason>"` promptly, and compute must work unchanged. This is exactly what a dedicated server will do, so make it the default host in the harness.
- The readback **protocol**, against a `FakeRenderHost` that returns synthetic pixels after a configurable delay: request/response matching, timeouts, the epoch check on card removal (09 §3.2) and back-pressure.

19's trust list and harness rules apply unchanged: 50 ms `Workspace.update()`, a complete `brain.conf` with asserted values, the `NativeLuaArchitecture` assertion, and `Long`/`String`/`Double` signal arguments.

**4.2 Plugging a GL test renderer in as the host.** Define the boundary in `:core` as an SPI:

```java
interface RenderHost {
  HostCaps caps();                                        // graphics available? formats, max sizes
  void submit(DisplayId d, CommandBuffer cb, long seq);   // server/worker thread -> host
  void readback(DisplayId d, Rect r, ReadbackSink sink);  // completes on host thread
}
```

The implementations:
- **In-game, single player and LAN**: hands command buffers from the integrated server to the client render thread.
- **Dedicated server**: `NullRenderHost`.
- **Tests**: `GlTestHost` (in `:gl-testkit`). It owns the LWJGL 2 context on its GL thread, pumps "frames" at a configurable rate (default 60 Hz, or step-wise via `pumpFrame()`), runs the **same render-core classes** as the mod (§2.1), and completes readbacks through the production PBO path.

The ocelot `OpenGpuEntity` takes its host by constructor or from a static test registry. Each end-to-end test:
1. Boots a machine per runtime and runs a Lua script that draws and calls readback.
2. Asserts that the bytes Lua received equal the bytes `GlTestHost` read from the FBO. **Exact, no tolerance:** this is Option 2's core contract.
3. Asserts the host framebuffer against the golden for the scene's tier.
4. Asserts timing in ticks: the readback arrives within N `Workspace.update()` ticks at the chosen frame rate.

The same scripts run with `NullRenderHost` for §4.1's no-renderer assertions. Tag these tests `gl` and give them their own `Test` task under the shared workflow's `xvfb-run`. Locally on Windows they run on the owner's Intel GPU, which gives the cross-implementation check for free.

**4.3 Run that JVM on Java 8 (correction to 05 §4).** 05 says the harness tests "run on JDK 17 (brain's Java classes are major 61)". OC-LuaJIT found that ocelot-brain's Scala classes are major 52 and only its 19 Java sources are 61, "because build.sbt sets no javacOptions release" (`OC-LuaJIT/docs/research/ocelot-brain.md:34`). Its `test/native/build-brain.sh:1-20` compiles ocelot-brain with scalac 2.13.11 on JDK 8 precisely so the harness runs on JDK 8. So:
- build the vendored jar with `--release 8` (or with that script);
- run the ocelot `Test` tasks on a Java 8 launcher.

One JVM then hosts ocelot, the JNLua natives and LWJGL 2.9.4, matching both the user's runtime and Angelica's practice. OC-LuaJIT's Linux native is available for the LuaJIT leg (`OC-LuaJIT/bin/main/assets/opencomputers/lib/libjnluajit52-linux-x86_64.so`).

**4.4 Other corrections.**
- **05 §4, golden-image bullet.** "Tolerance 0 for integer rasterisation paths and ≤ 1 LSB for shaded paths … bit-identical across JDK 8 vs 17/21 and x86-64 vs aarch64" assumed a software renderer. Under Option 2:
  - pixels do not depend on the JDK at all;
  - tolerance 0 holds for tier E only;
  - ≤ 1 LSB is too tight for shaded content across vendors (piglit allows 3 LSB);
  - aarch64 is out of scope (owner decision 12);
  - JDK/thread bit-identity now applies to compute only.
- **05 §4, "Framework".** It asks for "a CI job that also runs `:core` tests on a Java 8 launcher". No separate job is needed: set `javaLauncher` to Java 8 on the `Test` tasks inside the shared workflow (`ANG/glsm/build.gradle.kts:224-233`).
- **05 §2.** "Angelica's maintainers build and test under xvfb-run (Angelica/Justfile), a hint for CI" is confirmed as standard GTNH CI practice (§1.1).
- **09.** These parts are superseded by §§2-4:
  - §4 test rigs: "`:core` golden PNGs (tolerance 0)", "server-vs-mirror CRC… at 1 vs 8 threads", and the aarch64 conformance leg;
  - M2: "golden images bit-identical across JDKs";
  - M5: "headless GL tests via Angelica's `GLSMCoreExtension` if reusable".
- **02 open question 6** is answered in §2.3.

### 5. In-game tests

**5.1 runClient smoke checklist.** Manual, per milestone, on the owner's Java 8 / LWJGL 2.9.4 box (RFG `runClient`). Cases 2 and 3 run on Angelica 2.2.8, the floor, through `runClient` with its dev jar pinned, and in the owner's instance on 2.2.21; before each release also on the pack's 2.2.28, and on the latest release as a non-gating run (25 §6):
1. **Angelica absent** (dev classpath without `Angelica:dev`): the display renders, `/opengpu selftest` passes, and no GL errors are logged.
2. **Angelica present**, first with `glProfile=AUTO` (core 4.6 on the Intel box), then with `pinnedGLVersion=33` (02 §5).
   - Self-test passes.
   - Its readbacks are **byte-identical to case 1 on the same GPU**. This is a differential check: any difference is state leakage or GLSM mapping drift.
   - Run with `-Dangelica.unmappedGL=STRICT`, and grep OpenGPU's own sources for `GLSync` (02 §4).
3. **An Iris shader pack** loaded through Angelica.
   - Self-test readbacks are still byte-identical, because the offscreen pass runs before the world pass.
   - Visually check the screen face (lighting and tonemapping by the pack are expected): no flicker, no shadow-pass duplication.
4. **LAN with a second client.** RFG's `RunMinecraftTask` has `--username`/`--uuid` options, default user `Developer` (`retrofuturagradle-2.0.2.jar` strings); use a second run directory or the owner's MultiMC instance. A debug command prints each display's framebuffer hash on both clients, and the guest's must equal the host's (exact).
5. **Lifecycle.** Readback while paused (Esc), while a GUI is open, while minimized, and during world load (`skipRenderWorld` gates `onRenderTickStart`, `MCSRC/net/minecraft/client/Minecraft.java:1063-1069`). Each must complete or fail with the documented timeout error, never hang a Lua program.
6. **Input.**
   - OpenGPU's own GUI opens on right-click.
   - Touch coordinates are precise: a test program draws a crosshair at each touch and prints its pixel.
   - Keyboard and clipboard work through the GUI, with no dependence on OC keyboard blocks.

**5.2 What can be automated.** A dev-only module (`src/selftest`, excluded from the release jar) with three parts:
- **A client command** (Forge 1.7.10 `ClientCommandHandler.instance`, `MCSRC/net/minecraftforge/client/ClientCommandHandler.java:27-29`). `/opengpu selftest` renders the tier-E/Q/G/S scenes through the production renderer on the live game context (Angelica, Iris and all), compares them with the same `ImageCompare` and goldens, and writes JUnit XML to `run/client/opengpu-selftest/`.
- **An OC Lua suite** (BIOS or `autorun.lua`). It draws, reads back, compares CRCs or pixels for tier E (statistics for S), and writes `PASS/FAIL` lines to its filesystem (`saves/<world>/opencomputers/<uuid>/`). Set `ignorePower=true` in the dev `run/client/config/OpenComputers.cfg` so energy does not interfere; energy itself stays an in-game manual check (19 §12).
- **An auto-start hook for unattended runs.** It creates or loads a world (`Minecraft.launchIntegratedServer(folder, name, settings)`, `MCSRC/net/minecraft/client/Minecraft.java:2164`; mc-runtime-test's `WorldCreator` does it through `GuiCreateWorld`), places the rig, runs the suite and quits.

This can run as `xvfb-run ./gradlew runClient` in OpenGPU's own workflow; mc-runtime-test is the precedent that a 1.7.10 Forge client runs under Xvfb on GitHub runners. Keep it **non-gating, or nightly**:
- a full client start downloads assets and is slow;
- OpenAL may fail without an audio device [inference];
- beyond the `check`-level tests, llvmpipe adds only the MC/Angelica integration.

**5.3 runServer: graphics unavailable, compute works.** The shared workflow already runs `runServer` for `inputs.timeout` seconds (default 90, `:9-13`) with `stop` on stdin; when `horizonqa: true` it does not write `stop` (`:232-236`), so the GameTest run must finish and shut the server down within that timeout (raise `timeout` in OpenGPU's caller if needed). It fails on crash reports, on "Fatal errors were detected", or when the "Done (…)! For help" line is missing (`:194-237`, `GHW/scripts/test_no_error_reports`). That catches client-only classes leaking into common code (e.g. LWJGL referenced from the card driver).

Add Horizon-QA on top:
- The workflow inputs `horizonqa`, `horizonqa-tests` and `horizonqa-allow-no-tests` (`:34-48`) run the server with `-Dhorizonqa.mode=ci` and check `build/horizonqa/horizonqa-result.json` (`GHW/scripts/test_horizonqa_result`).
- Horizon-QA is "an end-to-end testing framework for GTNH. It implements the modern Minecraft GameTest API on 1.7.10" (`HQA/README.md`). It is MIT-licensed and added as `devOnlyNonPublishable('com.github.GTNewHorizons:Horizon-QA:<version>:dev')` (`HQA/docs/getting-started/mod-setup.md:8-18`); test classes are discovered through `@GameTestHolder`/`@GameTest`.

OpenGPU GameTests:
- **Compute through the real server class loader.** Compile and dispatch a compute kernel through the real FML server class loader on Java 8. This exercises relocated ASM 9 and the private `ClassLoader` under `LaunchClassLoader` (05 §1), which ocelot cannot prove. Assert the results bit for bit.
- **Graphics unavailable.** Assert that graphics report unavailable on the dedicated server, and that a placed card plus display neither crash nor lag the tick.

Horizon-QA's batch execution is server-side only (`mod-setup.md`, "Runtime mode"). GameTests therefore cover only the server path; client checks stay in §5.1-5.2.

## Design implications for OpenGPU

1. **Context-bound tests in `check`.** Give every GL test that needs a context its own `Test` task (`glCompatTest`, `glsmTest`, `ocelotGlTest`) on a Java 8 launcher, with `java.library.path` from RFG `extractNatives2`. Wire them into `check` with Angelica-style "tests actually ran" guards. They then run in the unchanged GTNH shared workflow under `xvfb-run` on llvmpipe.
2. **An MC-free render core.** Keep the render core free of `net.minecraft.*` and `OpenGlHelper`: LWJGL `GLxx`/`ARB`/`EXT` only, a single-thread executor, explicit save/restore of all touched state. The harness, the ocelot GL host and the in-game self-test then run identical code. This amends 09 §3.7.
3. **Vendor-exact 2D by construction.** Use integer-aligned quads or spans for all 2D primitives (no `GL_LINES`/`GL_POINTS`), `GL_NEAREST`, texel-centre coordinates, `glDisable(GL_DITHER)`, no MSAA or sRGB, and palette lookup via a nearest 256×1 texture. Document "2D readback is exact on conformant drivers" as an API property.
4. **Document host-dependent 3D readback.** Under Option 2, readback of shaded 3D depends on the host. Lua programs must not hash shaded output; compute output is deterministic (CPU).
5. **A `RenderHost` SPI** with `NullRenderHost`, the client host, `GlTestHost` and `FakeRenderHost`. The dedicated-server behaviour is the `NullRenderHost` contract, tested in ocelot by default.
6. **One `ImageCompare`** (max delta, failing-pixel count, ±1 px neighbourhood, Sobel edge mask, SSIM, output-format-aware comparison), shared by JUnit, the ocelot end-to-end tests and the in-game self-test. Goldens are PNG plus a sidecar (renderer, Mesa version, tier, thresholds); diffs are written as CI artifacts.
7. **Calibrate before freezing.** Calibrate S/T thresholds on four implementations (llvmpipe, softpipe, WARP, the owner's Intel) before freezing them. Track vendor-specific failures in an ANGLE-style expectations file rather than loosening global thresholds.
8. **GLSM leg from the dev jar.** Take the GLSM leg from `Angelica:2.2.8:dev` (the floor) and `Angelica:2.2.28:dev` (the pack) (Java 8 classes; 25 §6) through a `GLSMRedirector`-transforming test class loader, with `jvmdowngrader-java-api:1.3.5:downgraded-8` and GLSM's transitive dependencies added to that test task's classpath. Write OpenGPU's own JUnit extension; do not depend on Angelica's Java-21, LGPL fixtures. At 2.2.8 the hygiene case uses a non-default scissor box (25 §5.1 R-1).
9. **Minimum-GL paths in CI.** Once the owner fixes the no-Angelica minimum, exercise those paths with `MESA_GL_VERSION_OVERRIDE`/`MESA_EXTENSION_OVERRIDE` on a dedicated test task.
10. **ocelot on Java 8.** Build ocelot-brain for Java 8 and run the ocelot suites on Java 8, so Lua → pixels end-to-end tests share one JVM with LWJGL 2.9.4.
11. **Compute determinism replaces frame bit-identity.** JDK 8/17/21 × interpreter/C1/C2 × thread counts × multi-card tests replace 09's frame bit-identity rigs.
12. **Server and optional workflows.** Add Horizon-QA GameTests (shared-workflow input `horizonqa: true`) for server-side compute and "graphics unavailable". Put runClient under Xvfb, the Windows Mesa/WARP calibration leg and `glslangValidator` in a separate, non-gating OpenGPU workflow; `glslangValidator` checks `#version 300 es` inputs and `330 core` outputs (27 §11).

## Open questions for the owner

1. Is CI llvmpipe acceptable as the golden reference, with your Intel GPU as the local cross-check? It is Ubuntu 24.04 `noble-updates` Mesa, currently 25.2.8, and it drifts. The alternative is pinning Mesa in a container in a separate workflow.
2. What is the minimum GL for the no-Angelica path: 2.1 + `EXT_framebuffer_object`, 3.0, or 3.3 compat? It decides the `MESA_GL_VERSION_OVERRIDE` leg and the shader translation targets.
3. Should 2D lines, circles and text be generated as integer quads or spans (exact tier; recommended), or may they use GL primitives (G tier)?
4. Where does quantization to index8/RGB565 happen: in a shader on the host, or in Java after PBO readback? This decides which plane the tests compare.
5. Is a separate, non-gating (or nightly) workflow acceptable for runClient under Xvfb, the Windows Mesa/WARP calibration and `glslangValidator`?
6. Is adding Horizon-QA as a dev-only dependency acceptable for server-side compute GameTests?
7. GTNH pack servers run Java 17-21 through lwjgl3ify/RFB. Compute must work there, but lwjgl3ify gets no CI gate; is a manual pre-release `runServer21` compute check enough?
8. Should the Lua API expose a renderer identity (e.g. `caps().renderer`), so programs and bug reports can tell which host produced a readback?

## Sources

Local (read-only):
- `ANG/.github/workflows/build-and-test.yml:12`
- `ANG/Justfile:3-9`
- `ANG/gradle/libs.versions.toml:3`
- `ANG/LICENSE:8-9`
- `ANG/build.gradle.kts:124-178`
- `ANG/glsm/build.gradle.kts:16,160,192-303`
- `ANG/glsm/src/testFixtures/java/com/gtnewhorizons/angelica/glsm/{GLSMCoreExtension.java:34-82, GLSMExtension.java:29-197, GLCoreTest.java, GLCompatTest.java, GLTestTags.java, ffp/FfpFixture.java:200-229, ffp/CubeParityFixture.java:281-282}`
- `ANG/glsm/src/test/java/com/gtnewhorizons/angelica/glsm/{GLSM_PushPop_UnitTest.java:189-190, ffp/FFPVertexLightingGLTest.java:147-170, redirect/GLSMRedirectorTest.java:120}`
- `ANG/glsm/src/main/java/com/gtnewhorizons/angelica/glsm/redirect/GLSMRedirector.java:30-60,854-858`
- `LW2/src/java/org/lwjgl/{Sys.java:57, opengl/XRandR.java:64-149,247-249, opengl/LinuxDisplay.java:200-216,730-760,938-965,1323-1339, opengl/Pbuffer.java:165-258}`
- `C:\Games\Minecraft\libraries\org\lwjgl\lwjgl\lwjgl\2.9.4-nightly-20150209\lwjgl-2.9.4-nightly-20150209.jar` (`javap -c` of `XRandR`)
- `~\.gradle\caches\...\lwjgl-platform-2.9.4-nightly-20150209-natives-linux.jar` (listing)
- `~\.gradle\caches\...\retrofuturagradle-2.0.2.jar` (`MinecraftTasks`, `RunMinecraftTask` strings)
- `GHW/.github/workflows/build-and-test.yml:1-298`
- `GHW/.github/workflows/release-tags.yml:30-135`
- `GHW/scripts/{test_no_error_reports, test_horizonqa_result}`
- `git show 87bfdc7`
- `HQA/README.md`
- `HQA/docs/getting-started/mod-setup.md`
- `HQA/docs/reference/jvm-flags.md:27`
- `MCSRC/net/minecraft/client/Minecraft.java:1008-1103,2164`
- `MCSRC/net/minecraftforge/client/ClientCommandHandler.java:27-29`
- `C:\Users\astro\Downloads\OC-LuaJIT\{docs\research\ocelot-brain.md:34, test\native\build-brain.sh:1-20,136-146, bin\main\assets\opencomputers\lib\libjnluajit52-linux-x86_64.so}`
- `C:\Users\astro\Downloads\OpenGPU\.github\workflows\build-and-test.yml:10-13`
- `OpenGPU\docs\research\{02,05,09,19}` (statements corrected above)

Downloaded artifacts (inspected with javap and jar listings):
- `https://nexus.gtnewhorizons.com/repository/public/com/gtnewhorizons/angelica/glsm/2.2.21/` (`glsm-2.2.21.jar`, `glsm-2.2.21-test-fixtures.jar`, `glsm-2.2.21.module`)
- `https://nexus.gtnewhorizons.com/repository/public/com/github/GTNewHorizons/Angelica/2.2.21/Angelica-2.2.21-dev.jar`
- the instance's `mods\angelica-2.2.21.jar` (GLSM at major 52)

Web:
- GitHub REST `https://api.github.com/repos/GTNewHorizons/Angelica/actions/runs/37723381134` (+ `/jobs`)
- `https://api.github.com/repos/GTNewHorizons/GTNH-Actions-Workflows/commits?path=.github/workflows/build-and-test.yml`
- `https://raw.githubusercontent.com/GTNewHorizons/Angelica/78abc057112dcdc0cf46003d50564ae143b7c1fe/glsm/build.gradle.kts`
- Mesa: `https://gitlab.freedesktop.org/mesa/mesa/-/raw/mesa-25.2.8/docs/{features.txt, envvars.rst, drivers/llvmpipe.rst}` and `https://gitlab.freedesktop.org/mesa/mesa/-/raw/main/docs/relnotes/21.3.0.rst`
- `https://www.khronos.org/news/permalink/mesas-llvmpipe-is-opengl-4.5-conformant`
- `https://packages.ubuntu.com/noble-updates/libgl1-mesa-dri`
- `https://launchpad.net/ubuntu/noble/+source/mesa`
- `https://salsa.debian.org/xorg-team/xserver/xorg-server/-/raw/debian-unstable/debian/local/xvfb-run`
- `https://raw.githubusercontent.com/pal1000/mesa-dist-win/HEAD/readme.md` (`:78,80,176`)
- `https://api.github.com/repos/pal1000/mesa-dist-win/releases/latest`
- `https://raw.githubusercontent.com/pyvista/setup-headless-display-action/main/{action.yml, windows/install_opengl.sh}`
- `https://raw.githubusercontent.com/headlesshq/mc-runtime-test/main/{README.md, action.yml, 1_7_10/src/main/java/me/earth/mc_runtime_test/WorldCreator.java}`
- `https://registry.khronos.org/OpenGL/specs/gl/glspec21.pdf` (§3.4.1 p. 103, §3.5.1 p. 109, §4.1.9 p. 212, App. A.1)
- `https://gitlab.freedesktop.org/mesa/piglit/-/raw/main/tests/util/piglit-util-gl.c` (`:284-307`)
- `https://raw.githubusercontent.com/KhronosGroup/VK-GL-CTS/main/framework/common/{tcuImageCompare.hpp, tcuImageCompare.cpp, tcuRasterizationVerifier.cpp}`
- `https://raw.githubusercontent.com/KhronosGroup/WebGL/main/sdk/tests/js/webgl-test-utils.js` (`:1296-1327`)
- `https://chromium.googlesource.com/angle/angle/+/main/src/tests/test_utils/ANGLETest.h` (`:265-352`) and `.../src/tests/angle_end2end_tests_expectations.txt`
- `https://raw.githubusercontent.com/gfx-rs/wgpu/trunk/tests/src/image.rs` (`:100-140`)
- `https://chromium.googlesource.com/chromium/src/+/main/content/test/gpu/gpu_tests/skia_gold_matching_algorithms.py` (`:9-54,176-191`)
- `https://github.com/GTNewHorizons/Horizon-QA`
- Z. Wang, A. Bovik, H. Sheikh, E. Simoncelli, "Image quality assessment: from error visibility to structural similarity", IEEE TIP 13(4), 2004, `https://ece.uwaterloo.ca/~z70wang/publications/ssim.pdf`

## Verification notes

Adversarial check, 2026-10-08. Each key claim was re-checked against primary sources: the shared-workflow clone `GHW/` (be3d291), `ANG/` (tag 2.2.21), `LW2/` (2df01dd), the installed LWJGL jar, artifacts freshly re-downloaded from nexus.gtnewhorizons.com, Mesa 25.2.8 docs, the GL 2.1 PDF (`pdftotext -layout`), the GitHub REST API and the upstream test-suite sources. Changes:

1. **§1.2, root `gl-core` wiring (modified).** The text said the root project "does the same" as `glsm`. `ANG/build.gradle.kts:124-178` does register `glCoreTest` with a Java 8 launcher and `extractNatives2`, but attaches it only through `tasks.test { finalizedBy(glCoreTest) }` and has no `verify*Ran` NO-SOURCE guard (grep for `verify`/`NO-SOURCE`/`tasks.check` in that file finds nothing). The `glsm` wiring (`:218-303`) and run 37723381134 (`conclusion: success`, head 78abc05, job steps "Run post-build checks" and "Run server for up to 90 seconds" both `success`) were confirmed.
2. **§1.3, softpipe availability (now verified).** Ubuntu noble-updates `debian/rules:46` sets `GALLIUM_DRIVERS = softpipe` and `:118` adds `llvmpipe`, so the CI runner's Mesa includes softpipe. Added the `LIBGL_ALWAYS_SOFTWARE` pairing from `envvars.rst:1078-1081`. Mesa `features.txt:100/114/213/228`, `relnotes/21.3.0.rst:38` and `libgl1-mesa-dri 25.2.8-0ubuntu0.24.04.4` (packages.ubuntu.com and the Launchpad API, pocket Updates) were confirmed.
3. **§1.4, `xrandr` (modified).** The mechanism is confirmed in both the source and the installed jar (`javap -c`: `ldc "xrandr"`, `Runtime.exec`, a `Throwable` handler in `XRandR`; `getScreenNames()` followed by `iconst_0; aaload` in `LinuxDisplay`). "Mandatory" holds only in the default configuration: `LinuxDisplay.isXrandrSupported()` returns false when the privileged boolean `LWJGL_DISABLE_XRANDR` is set (`LinuxDisplay.java:213-215`; the string is also in the installed jar), and LWJGL then tries XF86VidMode. Xvfb's XF86VidMode support was not checked (unverified).
4. **§1.5, mc-runtime-test (strengthened).** `ci-data.json` builds `1_7_10` against Forge `10.13.4.1614-1.7.10` on Java 8 and runs it (`lexforge`), and `lifecycle.yml:130-133` sets `xvfb` for every run. This is the owner's exact Forge build.
5. **Summary, §2.3 and Design implication 8: JvmDowngrader runtime for the GLSM leg (omission, added).** The classes in `Angelica-2.2.21-dev.jar` are major 52 as stated, but `javap -c` shows `GLStateManager` and `GLSMRedirector` calling `xyz/wagyourtail/jvmdg/...` stubs (`J_N_Buffer.flip/position/...`, `J_L_Math.clamp`, `J_L_System.getProperty`). Neither the dev jar nor the instance's `angelica-2.2.21.jar` ships them. In game they come from `falsepattern/xyz.wagyourtail.jvmdowngrader-jvmdowngrader-java-api-1.3.5-downgraded-8.jar`, which FalsePatternLib's DepLoader injects at GTNHLib's request (`logs/fml-client-latest.log`, `gtnhlib_deps8.json`). A plain Java 8 test JVM therefore needs that artifact plus GLSM's transitive deps (`ANG/glsm/build.gradle.kts:122-128`). Also corrected the entry count: the jar is `Multi-Release: true`, with about 286 base `glsm/` entries and 437 in all including `META-INF/versions/17`, and `GLSMInitConfig` lives in `glsm.hooks`. Confirmed: test fixtures major 65 (`GLSMCoreExtension`), `glsm-2.2.21.jar` `GLStateManager` major 65, `.module` `org.gradle.jvm.version: 21` on all four variants, `transformClassNode(String, ClassNode)` public (`GLSMRedirector.java:854-856`), `MainThread` `private static final` (`GLStateManager.java:268`), and LGPL-3.0 (`ANG/LICENSE`).
6. **§3.1 and Sources, spec page numbers (modified).** In glspec21.pdf the exactly-one shared-edge sentence is on p. 109 (§3.5.1 begins on p. 108), not p. 110, and "Initially, dithering is enabled" is on p. 212, not p. 213. The quotations themselves and §3.4.1 p. 103 are correct. §2.14.9 additionally specifies round-to-nearest conversion of [0, 1] colours to fixed point, which supports exact pass-through of k/255 values in tier E.
7. **Summary, threshold wording (modified).** The summary said all thresholds "sit within piglit's 3-LSB rule, dEQP's 0.02-0.05 fuzzy metric". The cross-implementation S limit (≤ 4) is one LSB looser than piglit's `3.0 / (1 << bits)` (`piglit-util-gl.c:284-307`, confirmed), and dEQP's 0.02-0.05 is an aggregate error metric after blurring and bilinear-neighbour comparison (`tcuImageCompare.cpp:201-229`, confirmed), not a per-channel bound. Reworded. WebGL `errorRange = opt_errorRange || 0` (`webgl-test-utils.js:1311`), ANGLE `EXPECT_PIXEL_NEAR` (`ANGLETest.h:318`, `:352`) with 2,670 `SKIP`/`FAIL` expectation lines, wgpu `Mean`/`Percentile` with the `[0.01, 0.1]` guidance (`image.rs:106-117`) and Skia Gold fuzzy/Sobel (`skia_gold_matching_algorithms.py:9-54`, `:120-191`) were confirmed.
8. **§5.3, runServer duration (modified).** 90 s is the default of the `timeout` input (`GHW/.github/workflows/build-and-test.yml:9-13`), not a constant. With `horizonqa: true` the workflow does not pipe `stop` (`:232-236`), so GameTests must complete and shut the server down within that timeout. Horizon-QA (MIT, `HQA/LICENSE`; README GameTest-on-1.7.10 wording; `mod-setup.md:14,18,37-49`; `enableModernJavaSyntax = jabel`, i.e. Java 8 bytecode) and `scripts/test_horizonqa_result` (reads `exitCode` from `horizonqa-result.json`) were confirmed.

Confirmed without change: §1.1 (workflow `:64`, `:66-69`, `:154`, `:156-163` report upload, commit 87bfdc7 body; release `assemble publish -x test`), §1.6 (mesa-dist-win `readme.md:78,80,176`; pyvista `install_opengl.sh:11-23`; latest mesa-dist-win release 26.2.4, 2026-10-04), §4.3 (`OC-LuaJIT/docs/research/ocelot-brain.md` "CORRECTED" paragraph; `build-brain.sh:1-20` compiles with scalac 2.13.11 on JDK 8; the Linux LuaJIT native exists). For §4.3, note also that ocelot-brain pins `sbt.version = 2.0.7` (`project/build.properties`), which needs JDK 11+ (`build-brain.sh:9-10`). "Build with `--release 8`" therefore means sbt on JDK 11+ with `javacOptions ++= Seq("--release", "8")`, or a Gradle Scala subproject on a Java 8 toolchain, as owner decision 12 implies. The claim that ocelot runs on JDK 8 rests on that script's precedent, not on a run observed here (medium confidence, unchanged).

## Amendments after the Angelica floor study (2026-10-09)

Corrections from 25 §9 (`25-angelica-version-floor.md`), applied in place:

- **Summary, §2.3 and Design implication 8.** The GLSM leg moves from `Angelica:2.2.21:dev` to the floor and the pack, `2.2.8:dev` and `2.2.28:dev` (25 §6); 2.2.21 stays covered in game. §2.3 notes that `GLSMRedirector` is Java 8 bytecode in both (25 §4) and that tag 2.2.8 has the same `transformClassNode` and `GLSMInitConfig`.
- **Design implication 8.** The 2.2.8 hygiene case sets a non-default scissor box, because `glPopAttrib` does not restore the box before 2.2.21 (25 §5.1 R-1, §6).
- **§5.1.** The in-game cases name their Angelica versions: 2.2.8 through `runClient` with the dev jar pinned, the owner's 2.2.21 instance, 2.2.28 before each release, the latest release non-gating (25 §6).

## Amendments after the language decision (2026-10-09)

The owner changed decision 5 (24 §1) to an ES 3.00-based graphics language, specified in 27 (`27-graphics-language-es300.md`). Applied in place:

- **§4.1, frontend bullet.** The ES 3.00-subset frontend (27) with `switch` rules and integer guards, translated to `#version 330 core` only; `glslangValidator` validates inputs as `#version 300 es` with a test prelude (`og_` built-ins, S7 initializers stripped, a default float precision) and outputs as `330 core`.
- **§4.1, raylib corpus (new).** Converted rather than dropped: `glsl100` files through 27 §1.3's converter, `glsl330` files with the version line changed, implicit-conversion files as expected rejections, licence check per file.
- **§3.5, integer conformance corpus (new).** Gating on llvmpipe against the Java reference, run on the owner's three GPUs per milestone and driver update, deviations in the expectations file (27 §5).
- **Design implication 12.** `glslangValidator` checks `#version 300 es` inputs and `330 core` outputs.
