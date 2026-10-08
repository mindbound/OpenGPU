# 19 — Gap: ocelot-brain 0.24.2 vs OpenComputers 1.12.64-GTNH machine-model parity

Gap-fill researcher, 2026-10-08. Assignment (completeness critic, severity medium): 05 and 09 make ocelot-brain the primary test harness on the claim that it "carries the same `Machine`/budget code", but nobody had diffed its machine model against the GTNH fork. This report is that diff, file by file, for everything OpenGPU's tests rely on.

Path shorthand used below (all citations are `path:line`, read in full on 2026-10-08):

- `GTNH/` = `C:\Users\astro\AppData\Local\Temp\claude\C--Users-astro-Downloads-OpenGPU\170a14cc-5cc1-40e0-a7ec-6130b748eabf\scratchpad\ocgtnh-master\src\main` — a clone of GTNewHorizons/OpenComputers at tag **1.12.64-GTNH** (commit `1e4559ff5`, 2026-09-30; `git describe --tags` = `1.12.64-GTNH`). I deepened it to the full history (4 314 commits, back to 2014) so `git log -S` attribution below is from the real history, not inference. Scala paths are under `scala/li/cil/oc/`, resources under `resources/`.
- `OB/` = `C:\Users\astro\Downloads\ocelot-brain\src\main` — ocelot-brain at commit `e98a5b2`, `version := "0.24.2"` (`build.sbt:4`). Scala paths are under `scala/totoro/ocelot/brain/`.
- `CFG` = `C:\Games\Minecraft\instances\Main\minecraft\config\OpenComputers.cfg` (the user's hand-tuned instance config).
- `OLD/` = `C:\Users\astro\Downloads\OpenComputers-GTNH` (tag 1.12.55-GTNH, read-only; used only to confirm 1.12.55 → 1.12.64 deltas).

Method: `diff --strip-trailing-cr -u` of each pair (CRLF/LF differ between the trees and would otherwise report every line changed, as OC-LuaJIT already warned, `OC-LuaJIT/docs/research/shipping-model.md:422-424`), then reading every hunk. Nothing was built or run; this is a source audit. Where a difference is one of naming/packaging only (`OpenComputers.log` → `Ocelot.log`, `api.internal.TextBuffer` → `TextBufferProxy`, `Unit` → `()`), it is not listed.

## Summary

1. **The budget machine is the same code.** `consumeCallBudget`, `maxCallBudget` averaging, the per-tick reset, the `inSynchronizedCall` bypass, `invoke` charging `1/limit`, `NativeLuaArchitecture.invoke` mapping `LimitReachedException` to zero results, the `machine.lua` zero-results → `coroutine.yield(function() … end)` retry, `runThreaded`/`runSynchronized`, `switchTo` scheduling after `executionDelay`, and the GPU's cost tables and two-phase `bitblt` are identical between `OB/` and `GTNH/` (sections 1, 2, 7). Both trees descend from upstream OC 1.8.9 (section 0), and the same JNLua/Eris binaries are used on both sides (`GTNH/../dependencies.gradle:4-6` vs `OB/../build.sbt:18-20`: OC-LuaJ 20220907.1, OC-JNLua 20230530.0, OC-JNLua-Natives 20220928.1).
2. **What differs is around the machine, not in it**, and every difference is enumerable: (a) energy — ocelot has no connector, no power drain, no `not enough energy` results, `computer.energy()` is a constant (section 7, 10); (b) persistence transport — GTNH writes Eris blobs through `SaveHandler.scheduleSave` to external files on the save thread and skips `savingForClients`, ocelot writes them inline into NBT (section 6); (c) tick cadence — in-game `Machine.update()` runs on the 50 ms server tick, in ocelot it runs whenever the harness calls `Workspace.update()` (section 2); (d) `maxSignalQueueSize` default 1024 (GTNH, since 1.12.64) vs 256 (ocelot), while the instance pins 256 explicitly (section 3, 9); (e) `machine.lua` differs by exactly one line, GTNH's fork-only `realTime = computer.realTime` in the sandbox (section 8); (f) ocelot's `Machine.signal` lacks the upstream Scala-`Map` argument cases (section 3); (g) ocelot's `brain.conf` replaces the defaults and falls back to the defaults silently on any missing key (section 9).
3. **Verdict for the test strategy:** budget/stall, retry idempotence, `bitblt`-style throttling, scheduling delay, queue overflow, Integer-arg loss, argument/result marshalling, memory accounting and Eris persistence of OpenGPU's `Value`s are **trustworthy in ocelot** provided the harness (i) ticks at 50 ms, (ii) loads a complete copy of the instance config and asserts the loaded values, (iii) pins the native architecture class. **Energy, SaveHandler/chunk-lifecycle persistence, player/op reach checks, the clipboard/drop-file input path, `computer.realTime`, and anything touching the item/driver layer must be confirmed in-game** (section 12).
4. Two statements in 09 are too strong and several test-rig lines need qualifiers; 05 open question 4 can be closed (section 13).

## 0. Provenance: what each tree is

- ocelot-brain's README states "Current `master` branch of Ocelot Brain corresponds to OC 1.8.9a (June 29, 2025, 52da41b)" (`OB/../README.md:22`). Upstream `52da41b` is a README-only commit ("document Weblate", 1 file, `https://github.com/MightyPirates/OpenComputers/commit/52da41b`), whose parent `851300d` is "feat(robot.names): add multiple robot names (#3784)" (upstream `master-MC1.7.10` commit list for June 2025). 
- GTNH's last upstream merge is `e9e52144a 2025-06-02 Merge remote-tracking branch 'src/master-MC1.7.10' into gtnh-oc-1.8.9` (`git log` in `GTNH/`), and that merge already contains `a430047` ("different fix for #3764", Rack GUI labels only) and `d1ca1ce` ("OpenComputers 1.8.9") (`git cat-file -t` in `GTNH/`). So the only upstream commits in ocelot's baseline that GTNH lacks are the robot-names list and the README; neither touches machine code. **Common ancestor of the machine model: upstream OC 1.8.9.**
- GTNH fork-only changes to the compared files since that merge (`git diff --stat e9e52144a HEAD` in `GTNH/`): `application.conf` (+35 lines), `machine.lua` (+1), `Settings.scala` (+14), `GpuTextBuffer.scala` (+1, signature), `common/component/TextBuffer.scala` (+36), `server/machine/Machine.scala` (+27). The commits: `537e12509` drop_file event (#178, first tag 1.12.26-GTNH), `40629a38b` expose `computer.realTime()` (#196, first tag 1.12.43-GTNH), `534a77aec` batch clipboard paste signals (#212, 1.12.53-GTNH), `f83bd95ad` clipboard batching opt-in (#215, 1.12.55-GTNH), `c3a147af7` drop-file limits (#216, 1.12.58-GTNH), `1e4559ff5` default `maxSignalQueueSize` 1024 (#226, 1.12.64-GTNH). `git tag --contains` was used for the first-tag attributions.
- 1.12.55 → 1.12.64: `Machine.scala`, `NativeLuaArchitecture.scala`, `ComputerAPI.scala`, `ArgumentsImpl.scala`, `ExtendedLuaState.scala`, `machine.lua` and `GraphicsCard.scala` are byte-identical between `OLD/` and `GTNH/` (empty diffs); `Settings.scala` gained the drop-file keys (`GTNH/scala/li/cil/oc/Settings.scala:346-348`) and `GpuTextBuffer.scala` only the `dropFile(…, Array[Byte], …)` signature (`:63`). This confirms 00 §C12 and 04's version note.

## 1. Direct-call budget charging and the LimitReached retry — identical

| Item | GTNH 1.12.64 | ocelot 0.24.2 | Difference |
|---|---|---|---|
| `consumeCallBudget`: no-op unless `architecture.isInitialized && !inSynchronizedCall`; clamp ≥ 0; throw `LimitReachedException` if `cost > callBudget`; subtract | `server/machine/Machine.scala:286-294` | `entity/machine/Machine.scala:253-261` | none |
| `invoke(address, method, args)`: visibility check, `if (annotation.direct) consumeCallBudget(1.0 / annotation.limit)`, then `component.invoke` | `Machine.scala:385-402` (charge at `:391`) | `Machine.scala:344-361` (charge at `:349-351`) | none; both throw `LimitReachedException` when the node is gone (`:400`, `:359`) |
| `invoke(value: Value, …)` for userdata callbacks | `Machine.scala:404-415` | `Machine.scala:363-374` | none |
| `maxCallBudget` = mean of all `CallBudget` parts, `1.0` if none | `Machine.scala:123-126`; sources `integration/opencomputers/DriverCPU.scala:69` and `DriverMemory.scala:40`, both `Settings.get.callBudgets(tier max One min Three)` | `Machine.scala:94-97`; sources `entity/traits/GenericCPU.scala:29` and `entity/Memory.scala:39`, same expression | none for CPU + RAM. (ocelot's APU budgets at `cpuTier = tier + 1`, `entity/APU.scala:36`; GTNH's APU driver was not compared — APUs are not in the planned rigs.) |
| Budget reset to `maxCallBudget` at the top of every `update()` | `Machine.scala:532` | `Machine.scala:498` | none in code; **who calls `update()` differs** (section 2) |
| `inSynchronizedCall = true` around `architecture.runSynchronized()`; cleared in `finally` | `Machine.scala:597-629` | `Machine.scala:530-562` | none |
| `NativeLuaArchitecture.invoke`: `LimitReachedException` → return 0 results; all other exception → result mappings | `server/machine/luac/NativeLuaArchitecture.scala:59-131` (`:76-77`) | `entity/machine/luac/NativeLuaArchitecture.scala:46-118` (`:63-64`) | logger name only |
| `machine.lua` `invoke(target, direct, …)`: `if result.n == 0 then … result = nil` → `coroutine.yield(function() … target.invoke(…) end)` | `resources/assets/opencomputers/lua/machine.lua:1080-1103` (`:1087`, `:1094`) | same path, same line numbers `:1080-1103` | none |
| `runThreaded` (signal pop, resume, result classification) and `runSynchronized` | `NativeLuaArchitecture.scala:171-300` | `:158-298` | `recomputeMemory(machine.host.internalComponents)` vs `(machine.host.inventory.entities)` (`:222` vs `:220`) — the input source, not the logic |
| `Callbacks`/`CallbackWrapper` (annotation scan, `limit`/`direct` extraction, ASM-generated call wrappers) | `server/machine/Callbacks.scala`, `CallbackWrapper.scala` | `entity/machine/Callbacks.scala`, `CallbackWrapper.scala` | ocelot removes the `ManagedPeripheral`/`MethodWhitelist`/`FilteredEnvironment`/`CompoundBlockEnvironment` branches (`GTNH …/Callbacks.scala:21-26, 48-79` vs `OB …/Callbacks.scala:10-18, 24-36`); for a plain `Environment` such as OpenGPU's card the static-analysis path is identical |

Harness-only conveniences (absent in GTNH, so not portable to in-game assertions): ocelot adds `getRemainingCallBudget`/`getMaxCallBudget` (`OB Machine.scala:403-405`, declared as "Ocelot extensions" in `entity/machine/Context.scala:184-188`) and `latestCallBudget`/`latestMemoryUsage`/`latestExecutionInfo` (`:81-84`, `:150-163`, filled at `:992-998`). They are the right instrument for budget assertions in the harness; they are not evidence about in-game behaviour beyond what the identical code above already guarantees.

Conclusion: a budget/stall/retry test that passes in ocelot exercises the exact code that runs in-game. The retry's idempotence rule in 09 §3 ("`consumeCallBudget` before any side effect") is verified against both trees.

## 2. `executionDelay`, scheduling, and tick cadence

- `switchTo(Yielded | SynchronizedReturn)` → `threadPool.schedule(this, Settings.get.executionDelay, MILLISECONDS)`: `GTNH Machine.scala:960-975` (`:968`) vs `OB Machine.scala:891-902` (`:899`). Pool: `ThreadPoolFactory.create("Computer", Settings.get.threads)` at `GTNH Machine.scala:1136` vs `OB entity/machine/MachineAPI.scala:110`. Both are wall-clock scheduled executors, so the 12 ms floor from 00 §1/04 holds on both sides. Defaults identical: `executionDelay: 12` (`GTNH/resources/application.conf:207`, `OB/resources/application.conf:207`), `threads: 4` (`:125` both); instance `CFG:191` = 12, `CFG:295` = 4.
- `Sleep`/`Yielded`/`Sleeping`/`remainIdle` handling in `run()`: `GTNH :983-1069` vs `OB :907-1002` — identical.
- **Divergence: the caller of `update()`.** In-game, `common/tileentity/traits/Computer.scala:129` calls `machine.update()` from the server tick (every 50 ms while the chunk ticks). In ocelot, `entity/traits/Computer.scala:36-44` calls it from `Workspace.update()` (`workspace/Workspace.scala:94-103`), which the harness drives at its own cadence (OC-LuaJIT's loop is `ws.update(); Thread.sleep(25)`, 05 §3). Everything keyed to ticks — the budget reset (`OB :498`), the synchronized-call execution (`:530-562`), `remainIdle` countdown (`:493-495`), `remainingPause` (`:520-528`), `uptime` (`:491`) — therefore scales with how fast the harness ticks. A harness ticking every 25 ms gives a computer twice the budget per wall second and halves the "rest of tick + 12 ms" synchronized-call round trip. **Rule: tick `Workspace.update()` every 50 ms (or express stall assertions in ticks, not seconds).** Measured calls-per-second figures from a 25 ms loop would overstate in-game throughput by up to 2×.
- `isGamePaused`: GTNH pauses the executor when the integrated server's client is paused (`Machine.scala:977-980, 989-993`); ocelot's is a constant `false` (`:904`). Singleplayer-only; irrelevant to dedicated servers.
- Energy drain every `tickFrequency` ticks and the `NoEnergy` crash in `update()`/`start()` (`GTNH :534-553`, `:213-217`) have no counterpart in ocelot (section 10).

## 3. Signal queue limits and argument conversion

- Queue cap: `if (signals.size >= maxSignalQueueSize) return false` — `GTNH Machine.scala:339` vs `OB :307`. Setting parsed identically with a floor of 256: `GTNH Settings.scala:489` and `OB Settings.scala:186-187` are the same expression (`(if (hasPath) getInt else 256) max 256`).
- **Default differs**: `maxSignalQueueSize: 1024` at `GTNH/resources/application.conf:283` (fork #226, 1.12.64 only; the installed jar's `application.conf:283` in `scratchpad/oc-jar-1.12.64` also says 1024) vs `maxSignalQueueSize: 256` at `OB/resources/application.conf:280`. The instance sets it explicitly to 256 (`CFG:268`), so **the user's game runs at 256 and ocelot's default happens to match it; a GTNH server with a fresh 1.12.64 config runs at 1024.** Tests of frame-signal coalescing should run under both values (ocelot accepts 1024 via `brain.conf`; section 9 explains how to make that stick).
- `signal()` returns `false` when `Stopped`/`Stopping`: `GTNH :337` vs `OB :305` — same. Locking (`state.synchronized` + `signals.synchronized`, never the machine monitor): same (`GTNH :336-365`, `OB :304-330`), so raising `opengpu_frame` from a pool thread (09 §3) is exercised faithfully.
- `convertArg` (Boolean, Character→Integer, Byte/Short/Integer/Long kept, other Number→Double, String, `byte[]`, `NBTTagCompound`, otherwise warn + null): `GTNH :317-333` vs `OB :285-301` — identical.
- **ocelot drops the Scala-`Map` argument cases.** GTNH `signal()` accepts an immutable `Map[_, _]` or `mutable.Map[_, _]` whose first entry is String→String as-is (`Machine.scala:346-347`) before the `java.util.Map` case (`:348-360`); ocelot has only the `java.util.Map` case (`OB :314-325`), so a Scala map falls through to `convertArg` → "unsupported argument" warning → `nil`. The Scala cases are upstream, not GTNH (`git log -S`: `0148ccff2 2016-12-17 Florian Nücke "Make Machine.signal less picky…"`, `fac5ecbe8 2019-06-11 payonel "allow signals of tables of simple key value types"`; upstream `master-MC1.12` `Machine.scala` still has them per `raw.githubusercontent.com`), so this is an ocelot port omission. Also `signal(name, args: Any*)` (`OB :303`) vs `AnyRef*` (`GTNH :335`). Impact on OpenGPU: none as long as signal arguments stay `Long`/`String`/`Double` (09 §3 rule); a `java.util.Map` argument behaves the same on both sides.
- **`Integer` signal arguments are lost on save in both trees**: `save` writes byte `-1` for anything not Boolean/Long/Double/String/`byte[]`/Map/NBT (`GTNH :864-879`, `OB :799-814`) and `load` maps `-1` to `null` (`GTNH :782`, `OB :719`). OC-LuaJIT's `ocelot-brain.md:36, :52` lists this as an ocelot defect; it is upstream behaviour present in GTNH as well, so an ocelot test that shows it is representative, and 09 §3's "Long/String/Double only" rule is correctly motivated.
- `popSignal`: GTNH's fork code sets `processingClipboardSignal` (`Machine.scala:108, :371-378`, from #212) which only feeds the energy exemption `isClipboardPowerFree` (`:664-666`, consumed at `:541-543`); ocelot's is the plain dequeue (`OB :336`). No effect on queue semantics.
- `onMessage("computer.checked_signal")` and `canInteract`: GTNH takes an `EntityPlayer` and `canInteract` additionally allows single-player and server operators (`:196-202`, `:649-651`); ocelot takes a `User` and checks only the user list (`:174-175`, `:582-584`). Reach/ownership behaviour for touch signals (09 §3) is therefore in-game-only.

## 4. String/table marshalling — identical

- Java → Lua (`ExtendedLuaState.pushValue`): `GTNH util/ExtendedLuaState.scala:27-66` vs `OB entity/machine/ExtendedLuaState.scala:20-59`. Same cases in the same order: `String` → `pushString`, `Array[Byte]` → `pushByteArray` (`:50` / `:43`), `Value` → raw userdata gated by `allowUserdata` (`:52` / `:45`), `Array`/`Product`/`Seq` → list tables, `java.util.Map`/`Map`/`mutable.Map` → tables, else warn + nil. Memoisation of cyclic structures identical.
- Lua → Java (`toSimpleJavaObject`): `GTNH :106-113` vs `OB :99-106`: boolean; NUMBER → `Long` if `lua.isInteger` else `Double`; STRING → `byte[]`; TABLE → `java.util.Map`; USERDATA → raw object. Identical, so Lua 5.3/5.4 integers reach `Arguments` as `java.lang.Long` on both sides, and strings always arrive as `byte[]` (zero-copy `checkByteArray`, 09 §3).
- `Arguments`: `GTNH server/machine/ArgumentsImpl.scala` vs `OB entity/machine/Arguments.scala` (a class, not an interface). `checkInteger` (`:60` / `:84`), `checkLong` (`:119` / `:147`), `checkString` (`:165` / `:217`), `checkByteArray` (`:179` / `:236`), `checkTable` (`:193` / `:255`), `toArray` (`:303` / `:547`) have identical bodies including the lenient number conversion and NaN rejection (GTNH-era upstream fixes `66bd036ed`, `ec4e40867`, `fe89946fd` of 2023-06 are in both). Only MC helpers differ: ocelot has no `checkItemStack`/`optItemStack` (keeps `isItemStack`), and its `checkTable` returns Scala maps `.asJava` (`:258-259`) where GTNH returns them unchanged (`:196-197`) — unreachable from Lua, which only ever yields `java.util.Map`.
- Result conversion (`Registry.convert`/`convertRecursively`/`convertList`/`convertMap`): `GTNH server/driver/Registry.scala:167-266` vs `OB entity/machine/Registry.scala:36-140` — same rules (`oc:flatten`, memo, converters, `toString` fallback). GTNH registers Minecraft converters (ItemStack, fluids, NBT); ocelot registers fewer. OpenGPU returns only Integer/Long/Double/Boolean/String/`byte[]`/Map/array, which never reach a converter.
- `Component.invoke` → `Registry.convert(callback(env, context, new Arguments(args)))`: `GTNH server/network/Component.scala:110-113` vs `OB network/Component.scala:153-155` — same.

## 5. Memory accounting — identical code, config-dependent values

- `recomputeMemory`/`memoryInBytes`: `GTNH NativeLuaArchitecture.scala:152-168` vs `OB :139-156`. Same formula: Σ(`amount × 1024`) `max 0 min maxTotalRam`, then `setTotalMemory(kernelMemory + ceil(bytes × ramScale))` when `limitMemory`. `kernelMemory` measured after the init run with a full GC (`:221` / `:219`). `ramScale = if (lua.getPointerWidth >= 8) ramScaleFor64Bit else 1.0` (`:317` / `:315`).
- Where `amount` comes from: GTNH `DriverMemory.amount` = `Settings.ramSizes(memory.tier)` over six levels, item tier = level/2 (`integration/opencomputers/DriverMemory.scala:14-20, :35-38`); ocelot `Memory.amount` = `Settings.ramSizes(memoryTier.id)` with `ExtendedTier` (`entity/Memory.scala:37`). Same table, same mapping. (OC-LuaJIT's `ocelot-brain.md:46` is right that `recomputeMemory`'s input path is the one structurally different piece — the item/driver resolution — but the arithmetic after it is the same.)
- `computer.freeMemory()`/`totalMemory()`: GTNH computes `(freeMemory min (totalMemory − kernelMemory)) / ramScale` inline (`luac/ComputerAPI.scala:42-54`); ocelot moved the same expressions to `NativeLuaArchitecture.freeMemory/totalMemory` (`:158-166`) and calls them (`luac/ComputerAPI.scala:38-49`). Same values.
- Defaults identical: `ramSizes [192,256,384,512,768,1024]` (`application.conf:242-249` both), `ramScaleFor64Bit 1.8` (`:265` both), `maxTotalRam 67108864` (`:273` both). **Instance values differ from both defaults**: `ramSizes [256,512,1024,2048,4096,8192]` (`CFG:251-258`), `maxTotalRam 134217728` (`CFG:229`), `ramScaleFor64Bit 1.8` (`CFG:245`). Readback sizing tests against `computer.freeMemory()` (09 §3) must run with the instance table copied into `brain.conf`.
- `limitMemory = !disableMemoryLimit`: `GTNH Settings.scala:458` / `OB :173`; instance `disableMemoryLimit=false` (`CFG:335`).

## 6. Persistence of userdata (`Value`) and machine state

- `UserdataAPI` (`save`/`load`/`apply`/`unapply`/`call`/`dispose`/`methods`/`invoke`/`doc`): `GTNH luac/UserdataAPI.scala` vs `OB luac/UserdataAPI.scala` identical except `persistable.load(nbt)` (`:45`) vs `persistable.load(nbt, null)` (`:38`) — ocelot's `Persistable.load` takes a `Workspace`, passed as `null` from the userdata path. OpenGPU's ocelot `Value` adapter must tolerate a null workspace. 09 §3 already forbids `Value` handles in the API ("never `Value` userdata"), so this path is exercised only if that decision changes.
- `PersistenceAPI` (perms/uperms flattening, deterministic sorted DFS, Eris settings): identical apart from a comment line (`GTNH luac/PersistenceAPI.scala` vs `OB luac/PersistenceAPI.scala`).
- Blob transport differs: GTNH `NativeLuaArchitecture.save` hands `persistence.persist(1)`/`persist(2)` to `SaveHandler.scheduleSave(host, nbt, address + "_kernel"/"_stack", …)` (`:407`, `:412`) and `load` reads them back with `SaveHandler.load` (`:362`, `:369`); the machine's `tmp` filesystem likewise goes through `SaveHandler.scheduleSave` (`Machine.scala:853`) with a `SaveHandler.loadNBT` fallback (`:771`), and `Machine.save` returns early when `SaveHandler.savingForClients` (`:828-830`). ocelot writes the same byte arrays straight into the NBT (`:409`, `:417`; read at `:361`, `:370`; tmp inline at `Machine.scala:786-788`). Consequence: a `ws.save/load` round trip in ocelot proves the Eris encode/decode of the suspended coroutine (what Lua and OpenGPU's `Value`s see); it cannot prove the external-file write ordering, chunk-unload timing, or the client-save short-circuit — exactly OC-LuaJIT's open question 1 (`shipping-model.md:432-434`).
- `allowUserdata = !disableUserdata`: `GTNH Settings.scala:456` / `OB :171`; instance false (`CFG:346`).
- Lua kernel save/load dance (`Machine.load` state stack, `pause(startupDelay)`, signal re-queue): `GTNH :753-819` vs `OB :691-756` identical except GTNH's `SaveHandler` fallback and ocelot calling `onHostChanged()` in the "weird state" branch (`:753`).

## 7. GPU budget and bitblt cost model — identical maths, no energy

GTNH keeps this in `server/component/GraphicsCard.scala`; ocelot in `entity/traits/GenericGPU.scala` (the `entity/GraphicsCard.scala` class is a 32-line shell with device info only).

| Item | GTNH | ocelot | Difference |
|---|---|---|---|
| Cost tables `setBackground/Foreground 1/32..1/128`, `setPaletteColor 1/2..1/16`, `set 1/64..1/256`, `copy 1/16..1/64`, `fill 1/32..1/128` | `GraphicsCard.scala:64-69` | `GenericGPU.scala:57-62` | none |
| `bitbltCost = Settings.bitbltCost × 2^tier`; `totalVRAM = maxW×maxH × vramSizes(tier)` | `:73-74` | `:66-67` | none (`tier` 0-based vs `tier.id` 0-based) |
| `budgetExhausted` flag | `:76` | `:69` | none |
| `determineBitbltBudgetCost`: VRAM→VRAM 0; dirty page → `bitbltCost × area/maxArea`; clean page 0.001; from screen 0 | `:196-209` | `:196-207` | none |
| `bitblt` two-phase: `tierCredit = (tier+1)×0.5`; first overrun sets `budgetExhausted` and throws `LimitReachedException`; the retry converts the excess into `context.pause((excess/tierCredit)/20)` and zeroes the charge | `:221-267` (`:235`, `:239-251`) | `:212-257` (`:225` `tier.num × 0.5`, `Tier.num` is 1-based per `util/Tier.scala:26-30`; `:229-241`) | none |
| `setPaletteColor` charges and `context.pause(0.1)`; `bind(…, reset=false)` does `context.pause(0)` | `:363-364`, `:292` | `:353-354`, `:282` | none |
| `resolveInvokeCosts(idx, ctx, budgetCost, units, factor)`: charge budget **and** `consumePower(units, factor)` = `node.tryChangeBuffer(−units×factor)` | `:100-107`, `:534` | `resolveInvokeCosts(idx, ctx, budgetCost)` charges budget and returns `true` (`:102-108`) | **energy absent**: ocelot never returns `nil, "not enough energy"` from `set`/`copy`/`fill`/`bitblt` (GTNH `:482-531`) |
| `bitblt` energy `determineBitbltEnergyCost` (`gpuCopyCost/15` to screen) | `:210-217` | removed | energy absent |
| `GpuTextBuffer.bitblt` / page hand-off to the screen | `common/component/GpuTextBuffer.scala:140-181` | `entity/GpuTextBuffer.scala:70-145` | same algorithm; GTNH adds the client `ClientGpuTextBufferHandler` and the `dropFile(byte[])` stub (1.12.58) |
| Screen `TextBuffer` save-time `machine.pause(0.1)` and `setResolution` `context.pause(0.25)` | `common/component/TextBuffer.scala:486`, `:199` | `entity/TextBuffer.scala:348`, `:93` | same; GTNH additionally has per-tick screen power (`:151-152`, `:236`) |

Defaults: `gpu.bitbltCost 0.5` (`application.conf:1681` GTNH / `:1663` ocelot; `CFG:515`), `vramSizes [1,2,3]` (`:1674` / `:1656`; instance `[1,2,4]`, `CFG:521-525`). ocelot adds `forceBind(node, reset)` (`GenericGPU.scala:75-96`), a harness convenience with no in-game analogue.

Conclusion: 09 §3's "uploads … with `bitblt`'s two-phase throw-then-`context.pause` pattern" can be developed and regression-tested in ocelot against the real pattern; only its energy half (and OpenGPU's own `node.tryChangeBuffer` charges, if any) needs the game.

## 8. The Lua kernel (`machine.lua`)

`diff --strip-trailing-cr` of `GTNH/resources/assets/opencomputers/lua/machine.lua` (1 548 lines) against `OB/resources/assets/opencomputers/lua/machine.lua` (1 547 lines) is **exactly one line**: GTNH adds `realTime = computer.realTime,` to the sandbox `libcomputer` table (`:1391`). It is fork-only: `git log -S "realTime = computer.realTime"` → `40629a38b 2026-05-26 Eldrinn-Elantey "expose computer.realTime() to user programs (#196)"`, first shipped in 1.12.43-GTNH; the Java-side `computer.realTime` function exists in both trees (`GTNH luac/ComputerAPI.scala:23`, `OB luac/ComputerAPI.scala:19`, and the LuaJ twins) but only GTNH's sandbox exposes it. This confirms and extends OC-LuaJIT's `shipping-model.md:418-421` (stated there for 1.12.58; it holds for 1.12.64 and for the instance jar).

Consequences: everything 00 and 04 rely on — the `invoke` retry (`:1080-1103`), the deadline hook (`:42-54`, `hookInterval` calibrated per boot `:1-40`, which means the 5 s `computer.timeout` triggers after a host-speed-dependent instruction count on both sides), `string.*` replacements, `pullSignal` — is the same text. **But OpenGPU's shipped Lua helper (`opengpu.lua`, `library()`) must not call `computer.realTime()`**: it exists in-game (GTNH ≥ 1.12.43) and is `nil` in ocelot and in upstream OC. Use `computer.uptime()` for pacing, or feature-detect.

## 9. Settings and configuration

Parsing of every `computer.*`/`gpu.*` key used here is the same code (`GTNH Settings.scala:59-98, 454-458, 489-499` vs `OB Settings.scala:22-61, 169-173, 186-197`, including the `callBudgets` three-entry and `ramSizes` six-entry validation). Default values (both `application.conf`s, line numbers identical up to `:273`):

| Key | GTNH 1.12.64 default | ocelot 0.24.2 default | Instance `CFG` | Note |
|---|---|---|---|---|
| `computer.callBudgets` | `[0.5, 1.0, 1.5]` (`:162-166`) | same | `[1, 2, 4]` (`:143-147`) | test both |
| `computer.executionDelay` | 12 (`:207`) | 12 | 12 (`:191`) | |
| `computer.timeout` | 5.0 (`:132`) | 5.0 | 5 (`:302`) | Lua hook only, both |
| `computer.threads` | 4 (`:125`) | 4 | 4 (`:295`) | |
| `computer.startupDelay` | 0.25 (`:139`) | 0.25 | 0.25 (`:287`) | |
| `computer.maxSignalQueueSize` | **1024** (`:283`) | **256** (`:280`) | 256 (`:268`) | floor 256 both |
| `computer.lua.ramSizes` | `[192…1024]` (`:242-249`) | same | `[256…8192]` (`:251-258`) | copy |
| `computer.lua.ramScaleFor64Bit` | 1.8 (`:265`) | 1.8 | 1.8 (`:245`) | |
| `computer.lua.maxTotalRam` | 64 MiB (`:273`) | 64 MiB | 128 MiB (`:229`) | copy |
| `computer.lua.allowBytecode` | false (`:218`) | false | **true** (`:203`) | copy |
| `computer.lua.allowGC` | false (`:224`) | false | **true** (`:209`) | copy |
| `computer.lua.enableLua54` | false (`:236`) | **true** (`:236`) | true (`:221`) | |
| `computer.lua.defaultLua53` | true | true | true (`:213`) | 5.3 first on both (`GTNH Proxy`/`OB Ocelot.scala:48-50`) |
| `computer.eraseTmpOnReboot` | false (`:200`) | false | false (`:184`) | |
| `filesystem.tmpSize` | 64 (`:918`) | 64 | 64 (`:504`) | |
| `gpu.bitbltCost` / `gpu.vramSizes` | 0.5 / `[1,2,3]` | same | 0.5 / `[1,2,4]` | |
| `debug.disableUserdata` / `disableMemoryLimit` / `logCallbackErrors` / `nativeInTmpDir` | false | false | false (`:346`, `:335`, `:386`, `:412`) | |
| `debug.registerLuaJArchitecture` | false | false | **true** (`:427`) | LuaJ is a selectable architecture in the instance |
| `power.ignorePower` | false (`:147` Settings) | n/a | false (`:1375`) | energy is live in-game |

GTNH-only keys: `misc.enableClipboardBatching`, `maxClipboardSize`, `clipboardBatchSize`, `maxDropFileCount`, `maxDropFileSize` (`GTNH application.conf:1260-1282`, `Settings.scala:346-357`; instance `CFG:761, 814, 720, 817, 821`). ocelot-only: `misc.maxClipboard` (`:1260-1263`), `soundCard`, `tapedrive` (`:1666-1687`); other value differences (`filesystem.bufferChanges` true/false `:895`, `mfuRange` 16/3, `transposerFluidTransferRate` 16000/4000) are outside OpenGPU's scope.

**The `brain.conf` trap.** `OB Settings.scala:243-280`: when a config file is given, ocelot parses *that file alone* and constructs `new Settings(config.getConfig("opencomputers"))`; every `val` in `Settings` reads its key eagerly, so **any missing key throws inside the `try`, which is caught (`:264-266`, "Failed to parse …"), after which the embedded defaults are loaded (`:269-280`) without failing the run**. A partial override file therefore silently yields defaults (budgets `[0.5,1,1.5]`, queue 256, RAM `[192…]`). OC-LuaJIT's harness already does the right thing — copies `application.conf` whole and appends HOCON path assignments (`OC-LuaJIT/test/native/smoke-test.sh:405-409`). OpenGPU's harness must do the same and **assert `Settings.get.callBudgets`, `maxSignalQueueSize`, `ramSizes`, `allowBytecode` after `Ocelot.initialize()`**. (GTNH does the opposite: an invalid config crashes the game, upstream `e652fd8df` 2022-09-06.)

## 10. Other `Machine`/architecture differences (for completeness)

- Energy model: GTNH's node is a `Connector` with `bufferComputer` (`Machine.scala:52-55`), `cost = computerCost × tickFrequency` (`:103`), `setCostPerTick` (`:168-170`), `start()` refuses without energy (`:213-217`), `update()` drains per `tickFrequency` with `sleepCostFactor` and the clipboard exemption (`:534-553`); `computer.energy()`/`maxEnergy()` read the connector (`luac/ComputerAPI.scala:101-112`). ocelot: no connector; `computer.energy()` and `maxEnergy()` both return the constant `Settings.bufferComputer` (`OB luac/ComputerAPI.scala:95-105`).
- `stop()`: GTNH defers the close to the server tick via `EventHandler.scheduleClose` (`:282`, undone at `:249`); ocelot calls `tryClose()` immediately (`:249`), which still defers while executing (`:861-871`). `computer.stopped` reaches neighbours a tick earlier in ocelot.
- `beep` callback orders `pause` then `beep` (GTNH `:467-468`) vs `beep` then `pause` (ocelot `:432-433`); events vs packets.
- `LuaStateFactory`: line-for-line port (as OC-LuaJIT found, `shipping-model.md:427-428`) with three edits: `init(librariesPath)` and the `Ocelot-` file prefix (`OB luac/LuaStateFactory.scala:172, :206-225`), the `create().close()` probe wrapped in `try/catch` (`:321-326`) so a probe failure still marks the native "available" (GTNH `:318-333` would mark it unavailable and fall back to LuaJ), and `UnsatisfiedLinkError` logged with the throwable. Same natives jar on both sides; the untracked `Ocelot-0.24.2-5x-libjnlua5x-windows-x86_64.dll` files in the OpenGPU repo root show the harness has already been run with CWD = the repo (05 §4's warning stands).
- `Architecture` trait: `recomputeMemory(Iterable[Entity])` plus abstract `freeMemory`/`totalMemory` (`OB entity/machine/Architecture.scala`), as OC-LuaJIT reported (`ocelot-brain.md:36`).

## 11. What OC-LuaJIT's research already established, and what changes

- `shipping-model.md:418-424`: one-line `machine.lua` difference (`realTime`), confirmed against 1.12.55 and 1.12.58 — **confirmed here against 1.12.64 and attributed** (#196, 1.12.43+). `:427-428` LuaStateFactory line-for-line — confirmed with the three edits above. It did not compare budgets, retry, queue, energy or persistence transport; sections 1-7 close that.
- `ocelot-brain.md:36, :52` "queued-signal Integer loss is a genuine [ocelot] defect" — **correction**: the same code is in GTNH (`Machine.scala:864-879`); it is an upstream behaviour, so the harness is representative and 09's `Long`-only signal rule is the right mitigation on both hosts.
- `ocelot-brain.md:44` "GTNH's [default] is 5.2" — wrong, already corrected in 05 §3 (5.3 first on both, `OB Ocelot.scala:48-50`).
- `ocelot-brain.md:46` "recomputeMemory is the piece the harness structurally cannot exercise" — true for the item/driver *input*; the arithmetic is identical (section 5).
- `ocelot-brain.md:50` tick non-determinism — reinforced: not only does execution progress vary, the harness's tick cadence sets the budget rate (section 2).
- `ocelot-brain.md:52` "brain.conf replacing rather than overriding config" — confirmed with the silent-fallback mechanism (section 9).

## 12. Which OpenGPU test categories are trustworthy in ocelot

Trustworthy in ocelot (same code; config and cadence controlled by the harness):

1. Direct-call budget charging, `LimitReached` → synchronized retry, idempotence of callbacks under retry, `consumeCallBudget`-before-side-effect (sections 1, 8). Run under `callBudgets` `[0.5,1,1.5]` and `[1,2,4]`.
2. The `bitblt`-style two-phase throttle (`throw` then `context.pause`) for uploads/dispatches, `context.pause` semantics in direct and non-direct callbacks (section 7).
3. `executionDelay`/`Sleep`/`Yielded` scheduling and the ~12-62 ms synchronized-call round trip, **only with `Workspace.update()` ticked every 50 ms** (section 2).
4. Signal queue overflow and frame-signal coalescing at 256 and at 1024 (set in a complete `brain.conf`), thread-safe `signal()` from a pool thread, `false` while stopping (section 3).
5. Argument/result marshalling: byte strings, `Long` integers on 5.3/5.4, table arguments as `java.util.Map`, `Registry.convert` of OpenGPU's return types (section 4).
6. Memory accounting: `computer.freeMemory()` headroom for readback sizing and OOM behaviour, with the instance's `ramSizes`/`maxTotalRam`/`ramScaleFor64Bit` copied (section 5).
7. Eris persistence of a suspended OpenGPU frame loop and of any `Value` (if ever used) at the Lua level: `ws.save/load` exercises the same `PersistenceAPI`/`UserdataAPI`/perms code (section 6).
8. Per-call overhead and in-machine Lua encoding cost (M0 measurements): same JNLua binding and kernel wrapper; report the tick cadence and `threads` alongside the numbers.
9. The 5.2/5.3/5.4 matrix and the LuaJIT architecture via OC-LuaJIT (05 §3), with the architecture class asserted at boot.

Must be confirmed in-game (code absent or different in ocelot):

1. Energy: `nil, "not enough energy"` from drawing calls, `NoEnergy` crashes, sleep-cost behaviour, `computer.energy()`; any `node.tryChangeBuffer` OpenGPU adds (sections 7, 10; instance `ignorePower=false`).
2. Persistence transport: `SaveHandler.scheduleSave` external blobs, `savingForClients`, world-save ordering, chunk unload/reload, `markChanged` client sync (section 6) — OC-LuaJIT's open question 1 remains in-game-only.
3. Server-tick cadence under real load, integrated-server pause, `EventHandler.scheduleClose` timing (sections 2, 10).
4. Player reach/ownership: `canInteract` op/single-player bypass and `computer.checked_signal` with real `EntityPlayer`s (section 3); the 8-block touch reach in 00 §3 lives in `TextBuffer`, which ocelot replaces with its own.
5. Input path: clipboard batching (#212/#215), drop-file chunking (#216), keyboard forwarding — fork-only code in `common/component/TextBuffer.scala` and `Keyboard`, not in ocelot.
6. `computer.realTime()` availability in the sandbox (section 8), and any Scala-`Map` signal argument (section 3).
7. The item/driver layer feeding `maxCallBudget`, `maxComponents` and `recomputeMemory` (`Driver.driverFor`, slots, tiers), `Settings` loading from the Forge config, and the `registerLuaJArchitecture=true` instance setting.
8. Everything Minecraft-side that 09 already routes to `runClient`/`runClient21` (rendering, packets, Angelica).

## 13. Implications for 09-architecture-synthesis.md

Statements that must change (quoted, then replacement):

1. §1, line 9: "headless testing, because ocelot-brain carries the same `Machine`/budget code and OC-LuaJIT's harness already boots OpenOS in it (05 §3)" → "headless testing, because ocelot-brain carries the same upstream-1.8.9 `Machine`/budget/retry/`bitblt` code as OC 1.12.64-GTNH (diffed in 19: the divergences are energy, `SaveHandler` persistence transport, tick cadence, the 1024-vs-256 queue default and GTNH's `computer.realTime` sandbox line) and OC-LuaJIT's harness already boots OpenOS in it (05 §3)".
2. §3 Signals, line 62: "coalesced to one pending per device because the installed queue is 256 (07 §3)" → "coalesced to one pending per device because the effective queue is 256 in this instance (`CFG:268`), while the 1.12.64-GTNH default is 1024 and ocelot's is 256 (19 §3, §9); the harness tests both sizes".
3. §4 project layout, line 113, `:ocelot-harness` row: append "ocelot pinned at e98a5b2 (0.24.2 = upstream OC 1.8.9a), which ships the same OC-JNLua 20230530.0 / natives 20220928.1 as OC-GTNH; `brain.conf` is a complete copy of the instance `OpenComputers.cfg` values (budgets, queue size, RAM table, `allowBytecode`/`allowGC`), asserted after `Ocelot.initialize()` (19 §9)".
4. §4 test rigs, line 117: "`:ocelot-harness` asserting golden frames, server-vs-mirror CRC in both modes at 1 vs 8 threads, budget stalls under both budget tables, `ws.save/load` with a suspended loop, and the M0 measurements per runtime" → same list plus: "with `Workspace.update()` ticked every 50 ms so budget-per-tick and the synchronized-call round trip match the server tick (19 §2); `ws.save/load` certifies the Eris path only — `SaveHandler`/chunk-lifecycle persistence and all energy behaviour (`not enough energy`, `NoEnergy`) are in-game-only tests (19 §6, §7, §12)".
5. §3 Command stream / `opengpu.lua` paragraph (line 59): add the rule "never calls `computer.realTime()` (GTNH-only since 1.12.43, absent in ocelot and upstream; 19 §8) — pace with `computer.uptime()` or feature-detect".
6. §5 risk 9, line 159: "ocelot-brain = upstream OC 1.8.9a | H / M | … in-game confirmation of ocelot results" → "ocelot-brain = upstream OC 1.8.9a; machine model identical to 1.12.64-GTNH except energy, persistence transport, tick cadence, queue default, `realTime` (19) | M / L | in-game confirmation limited to 19 §12's second list".
7. §3 Budget charges, line 66 ("Tested against both `[0.5,1,1.5]` and `[1,2,4]`"): no change needed; add "in ocelot, which runs the identical `consumeCallBudget`/retry code (19 §1)".
8. 05 open question 4 (line 144) can be closed: computer/gpu `Settings` keys and parsing are identical; the enumerated differences are 19 §9's table.

Harness rules that follow (for 05 §3/§4 and the M0 plan): assert `machine.architecture.isInstanceOf[NativeLuaArchitecture]` (OC-LuaJIT's guard); tick at 50 ms; use a complete `brain.conf` and assert loaded values; do not use ocelot's `getRemainingCallBudget`/`forceBind`/`latestCallBudget` in assertions meant to be portable to in-game (fine for diagnostics); run the queue test at 256 and 1024; keep signal arguments to `Long`/`String`/`Double`.

## Open questions

1. GTNH's APU budget source (`DriverAPU`) was not compared with ocelot's `APU.cpuTier = tier + 1` (`OB entity/APU.scala:36`); only CPU + RAM were. Irrelevant unless the rigs use an APU.
2. Whether the GTNH pack's shipped config (as opposed to the user's hand-tuned `CFG`) sets `maxSignalQueueSize` — the 1.12.64 default of 1024 applies only when the key is absent (floor 256). Both values are covered by the rule above.

## Sources

- `GTNH/scala/li/cil/oc/server/machine/{Machine,ArgumentsImpl,Callbacks,CallbackWrapper}.scala`, `server/machine/luac/{NativeLuaArchitecture,LuaStateFactory,ComponentAPI,ComputerAPI,UserdataAPI,PersistenceAPI,OSAPI,SystemAPI,UnicodeAPI}.scala`, `util/ExtendedLuaState.scala`, `server/driver/Registry.scala`, `server/network/Component.scala`, `server/component/GraphicsCard.scala`, `common/component/{GpuTextBuffer,TextBuffer}.scala`, `common/tileentity/traits/Computer.scala`, `integration/opencomputers/{DriverCPU,DriverMemory}.scala`, `Settings.scala`; `GTNH/resources/application.conf`, `GTNH/resources/assets/opencomputers/lua/machine.lua`; `GTNH/../dependencies.gradle`; `git log`/`git log -S`/`git diff --stat`/`git tag --contains` in the deepened clone (origin `https://github.com/GTNewHorizons/OpenComputers.git`).
- `OB/scala/totoro/ocelot/brain/entity/machine/{Machine,Context,Arguments,Callbacks,CallbackWrapper,Registry,ExtendedLuaState,Architecture,MachineAPI}.scala`, `entity/machine/luac/{NativeLuaArchitecture,LuaStateFactory,ComponentAPI,ComputerAPI,UserdataAPI,PersistenceAPI,OSAPI,SystemAPI,UnicodeAPI}.scala`, `entity/{GraphicsCard,GpuTextBuffer,TextBuffer,CPU,APU,Memory}.scala`, `entity/traits/{GenericGPU,GenericCPU,CallBudget,Computer}.scala`, `network/Component.scala`, `util/Tier.scala`, `workspace/Workspace.scala`, `Ocelot.scala`, `Settings.scala`; `OB/resources/application.conf`, `OB/resources/assets/opencomputers/lua/machine.lua`; `OB/../{build.sbt,README.md}`.
- `OLD/` (1.12.55-GTNH) for the 1.12.55 → 1.12.64 file comparison; `scratchpad/oc-jar-1.12.64/application.conf` (installed jar).
- `CFG` = `C:\Games\Minecraft\instances\Main\minecraft\config\OpenComputers.cfg` (lines cited inline).
- `C:\Users\astro\Downloads\OC-LuaJIT\docs\research\{shipping-model.md,ocelot-brain.md}`, `test\native\smoke-test.sh:405-409`.
- `C:\Users\astro\Downloads\OpenGPU\docs\research\{00-opencomputers-internals,04-lua-boundary-and-runtimes,05-dev-stack-and-testing,07-feasibility-and-performance-envelope,09-architecture-synthesis}.md` (statements cross-checked).
- Web: `https://raw.githubusercontent.com/MightyPirates/OpenComputers/master-MC1.12/src/main/scala/li/cil/oc/server/machine/Machine.scala` (upstream `signal` cases); `https://github.com/MightyPirates/OpenComputers/commit/52da41b`, `/commit/a430047`, `/commits/master-MC1.7.10/?since=2025-06-02&until=2025-07-01` (baseline attribution).
