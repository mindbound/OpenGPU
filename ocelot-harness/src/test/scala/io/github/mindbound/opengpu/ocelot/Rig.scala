package io.github.mindbound.opengpu.ocelot

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ExecutorService, LinkedBlockingQueue, ThreadFactory, ThreadPoolExecutor, TimeUnit}
import java.util.function.LongSupplier

import scala.collection.mutable
import scala.io.{Codec, Source}

import io.github.mindbound.opengpu.core.render.{DeviceKey, NoRenderHost, RenderHost}
import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue, fail}
import totoro.ocelot.brain.entity.machine.Machine
import totoro.ocelot.brain.entity.machine.luac.NativeLuaArchitecture
import totoro.ocelot.brain.entity.{CPU, Case, EEPROM, Memory}
import totoro.ocelot.brain.event.MachineCrashEvent
import totoro.ocelot.brain.util.{ExtendedTier, Tier}
import totoro.ocelot.brain.workspace.Workspace

/**
  * What one rig is built from (S10 plan P3): the test's name, the architecture, the test's own Lua file under
  * `src/test/resources/lua/`, and the opengpu device's tier and host, `NoRenderHost` by default (24:87), with the
  * host's per-frame pump and its teardown.
  *
  * @param libraries more Lua files, loaded after harness.lua and before the test's own, such as ogpu_pack.lua
  * @param pump      one host frame, run [[Cadence.pumpsPerTick]] times per tick (S10 amendments F10), unless the
  *                  script holds the pumps (`ogpu_harness.hold`)
  * @param closeHost runs after the device is released, before the workspace is disposed (a GL host's idle wait and
  *                  close), and when the entity over the host cannot be made
  * @param nanoClock the device's clock, for its 1 s `"dropped"` (28:112); a ManualClock moves it without sleeping
  * @param deadline  shared by every wait of the rig; a fresh [[Deadline.forTest]] when None (S10 amendments I18)
  */
final case class RigSpec(
    name: String,
    arch: Arch,
    script: String,
    libraries: Seq[String] = Seq.empty,
    tier: Int = 3,
    host: RenderHost = NoRenderHost.INSTANCE,
    pump: () => Unit = () => (),
    closeHost: () => Unit = () => (),
    hostRenderer: Option[String] = None,
    params: Map[String, Any] = Map.empty,
    blobs: Map[String, Array[Byte]] = Map.empty,
    cases: Int => Option[String] = _ => None,
    nanoClock: LongSupplier = () => System.nanoTime(),
    deadline: Option[Deadline] = None,
    bootTicks: Int = Rig.BootTicks)

/**
  * One booted OC computer in its own workspace (S10 plan P3): a T3 case with a T3 CPU pinned to the architecture,
  * `Memory(ExtendedTier.ThreeHalf)` and the harness BIOS on its EEPROM; the `opengpu` entity in the case as its card,
  * over a device with its own fold executor; the `ogpu_harness` component beside it; and the [[Ticker]] that runs
  * `workspace.update()` and the host's pumps every 50 ms. The workspace lives in `<runDir>/ws/<name>-<n>`.
  *
  * Boot asserts what booted (S10 plan P12): the machine's architecture is the arch's class, a NativeLuaArchitecture for
  * a native one, which catches a silent LuaJ fallback, and Lua's `computer.getArchitecture()` and `_VERSION` are the
  * arch's. [[close]] stops the machine and waits for its `computer.stopped`, releases the device, closes the host,
  * disposes the workspace, then shuts the fold executor down and waits for it, so no harness thread outlives its test
  * (05:117; S10 amendments I9). A test closes its rig on its own thread, after anything it ticked.
  */
final class Rig private (
    val spec: RigSpec,
    val workspace: Workspace,
    val computer: Case,
    val gpu: OpenGpuEntity,
    val sink: HarnessSink,
    val ticker: Ticker,
    val crashes: EventBuffer[MachineCrashEvent],
    folds: ExecutorService,
    gate: Boolean => Unit,
    val label: String)
  extends AutoCloseable {

  private var closed = false

  def machine: Machine = computer.machine

  /**
    * Ticks until `condition` holds (05:115), and fails at once, with the rig's state, when the script failed, the
    * machine crashed or the machine stopped before the script finished.
    */
  def until(what: String, maxTicks: Int = Rig.SceneTicks)(condition: => Boolean): Satisfied = {
    var held = false
    val outcome = ticker.until(s"$label: $what", maxTicks) {
      held = condition
      held || failure.isDefined
    }.orFail(context)
    if (!held) fail(s"$label: $what: ${failure.getOrElse("")}; $context")
    outcome
  }

  /**
    * One tick by hand, with no condition (S10 amendments I2): `workspace.update()`, which refills the machine's call
    * budget and runs a synchronized call that is waiting (Machine.scala:497-498, :530-537), then the pumps.
    */
  def step(): Unit = ticker.step()

  /**
    * Waits without ticking until `condition` holds, for a state the Lua executor reaches between two ticks, and fails
    * at once when the rig cannot go on (S10 amendments I2).
    */
  def hold(what: String)(condition: => Boolean): Satisfied = {
    var held = false
    val outcome = ticker.hold(s"$label: $what") {
      held = condition
      held || failure.isDefined
    }.orFail(context)
    if (!held) fail(s"$label: $what: ${failure.getOrElse("")}; $context")
    outcome
  }

  /**
    * Ticks until the script's `park(n)` has begun, then waits without ticking until the script waits in that park's
    * `sync()` for the next tick (S10 amendments I2): the device stays as the script left it, and after [[release]] the
    * next tick is the one that lets it go, on the call budget that tick refilled. `machine.isExecuting` alone cannot
    * tell a parked script from one whose executor run is due, so every `sync()` that ran must also have had its `go()`.
    */
  def parked(n: Int): Unit = {
    until(s"park($n)")(sink.all("park").exists(_.long(0) == n))
    hold(s"the script waiting in park($n)")(sink.syncsAnswered && !machine.isExecuting)
  }

  /** Lets the parked script go at the next tick. */
  def release(): Unit = sink.release()

  /**
    * Holds the host's pumps, or lets them run, as the script's `ogpu_harness.hold` does: LuaFboTest's script holds
    * them at each present, and Java lets them run when its bounded wait for that frame's readback begins (P10.2).
    */
  def holdPumps(on: Boolean): Unit = gate(on)

  /** Waits for the script's `finish` and the machine's stop, and fails unless the script finished ok. */
  def awaitFinish(maxTicks: Int = Rig.SceneTicks): Finish = {
    ticker.until(s"$label: the script finished", maxTicks) {
      sink.result.isDefined && stopped || crashes.size > 0
    }.orFail(context)
    val finish = sink.result.getOrElse(fail(s"$label: the machine crashed before the script finished; $context"))
    if (!finish.ok) fail(s"$label: the script failed: ${finish.message}; $context")
    finish
  }

  /** The machine is closed: its `computer.stopped` has caught up with its `computer.started`. */
  def stopped: Boolean = gpu.machineStops >= gpu.machineStarts

  /** Why the rig cannot go on, if it cannot. */
  def failure: Option[String] =
    sink.result.filterNot(_.ok).map(f => s"the script failed: ${f.message}")
      .orElse(crashes.snapshot.headOption.map(c => s"the machine crashed: ${c.message}"))
      .orElse(if (gpu.machineStarts > 0 && stopped && sink.result.isEmpty) {
        Some(s"the machine stopped before the script finished, lastError ${machine.lastError}")
      } else None)

  /** The rig's state for a failure message (S10 plan P10.1). */
  def context: String =
    s"arch ${spec.arch}, machine running ${machine.isRunning}, lastError ${machine.lastError}, " +
      s"finish ${sink.result}, crashes ${crashes.snapshot.map(_.message).mkString("[", "; ", "]")}, " +
      s"${sink.records.size} sink records " +
      s"(${sink.records.dropped} dropped), the last: ${sink.last(6)}; frame signals ${gpu.frameSignals}"

  /**
    * Tears the rig down in order; every step runs, and the first failure is thrown with the later ones suppressed.
    * An interrupted thread is cleared for the teardown's own ticks, then interrupted again.
    */
  override def close(): Unit = if (!closed) {
    closed = true
    val interrupted = Thread.interrupted()
    val errors = mutable.ArrayBuffer.empty[Throwable]

    def step(body: => Unit): Unit = try body catch { case e: Throwable => errors += e }

    step(if (!ticker.release()) throw new IllegalStateException(s"$label: another thread kept ticking"))
    step(if (!stopped) {
      machine.stop()
      ticker.until(s"$label: the machine stopped", Rig.BootTicks, within = Deadline.afterMillis(Rig.TeardownMillis))(
        stopped).orFail(context)
    })
    step(gpu.dispose())
    step(spec.closeHost())
    step(workspace.remove(sink))
    step(workspace.remove(computer))
    step {
      folds.shutdown()
      if (!folds.awaitTermination(Rig.TeardownMillis, TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException(s"$label: the fold executor did not end in ${Rig.TeardownMillis} ms")
      }
    }
    step(crashes.close())
    step(ticker.close())
    Rig.opened.synchronized(Rig.opened -= label)
    if (interrupted) Thread.currentThread().interrupt()
    errors.headOption.foreach { first =>
      errors.tail.foreach(first.addSuppressed)
      throw first
    }
  }
}

object Rig {
  /** Tick caps (S10 plan P10.1). */
  val BootTicks: Int = 100
  val SceneTicks: Int = 400

  val TeardownMillis: Long = 10000L

  private val workspaces = new AtomicInteger()
  /** Device ids from a counter, so every entity has a fresh key (28:1080). */
  private val deviceIds = new AtomicInteger()
  private val opened = mutable.LinkedHashSet.empty[String]

  /** Rigs not yet closed, for ShutdownTest (S10 plan P14.9). */
  def open: Seq[String] = opened.synchronized(opened.toList)

  /** Boots, runs `body` and closes; a failure of the close is suppressed into one of the body. */
  def using[T](spec: RigSpec)(body: Rig => T): T = {
    val rig = boot(spec)
    val result = try body(rig) catch {
      case e: Throwable =>
        try rig.close() catch { case c: Throwable => e.addSuppressed(c) }
        throw e
    }
    rig.close()
    result
  }

  def boot(spec: RigSpec): Rig = {
    Brain.init()
    val n = workspaces.incrementAndGet()
    val label = s"${spec.name.replaceAll("[^A-Za-z0-9._-]", "_")}-$n"
    val dir = Files.createDirectories(Paths.get(Brain.property("opengpu.ocelot.runDir")).resolve("ws").resolve(label))
    val deviceId = deviceIds.incrementAndGet()
    val folds = foldExecutor(deviceId)
    val crashes = EventBuffer.subscribe[MachineCrashEvent](s"crashes of $label", 1024) {
      case e: MachineCrashEvent => e
    }
    val workspace = new Workspace(dir)
    val pumps = Cadence.pumpsPerTick
    val deadline = spec.deadline.getOrElse(Deadline.forTest())
    val gate = new PumpGate
    val ticker = new Ticker(() => {
      workspace.update()
      var i = 0
      while (i < pumps) {
        gate.pump(spec.pump())
        i += 1
      }
    }, deadline)
    val gpu = try {
      new OpenGpuEntity(spec.tier, spec.host, new DeviceKey(1, deviceId), folds, spec.nanoClock, spec.hostRenderer)
    } catch {
      case e: Throwable =>
        folds.shutdownNow()
        crashes.close()
        // The host may have taken GL state of its own before the device opened on it
        try spec.closeHost() catch { case c: Throwable => e.addSuppressed(c) }
        throw e
    }
    val sink = new HarnessSink(source(spec.libraries :+ spec.script), spec.params, spec.blobs, spec.cases,
      () => ticker.ticks, gate.hold)
    val computer = new Case(Tier.Three)
    val rig = new Rig(spec, workspace, computer, gpu, sink, ticker, crashes, folds, gate.hold, label)
    opened.synchronized(opened += label)
    try {
      workspace.add(computer)
      val cpu = new CPU(Tier.Three)
      // An architecture brain did not register throws "Unsupported processor type." (19:202; 05:106)
      cpu.setArchitecture(spec.arch.cls)
      computer.inventory(0) = cpu
      computer.inventory(1) = new Memory(ExtendedTier.ThreeHalf)
      val eeprom = new EEPROM
      eeprom.codeBytes = Some(resource("bios.lua").getBytes(StandardCharsets.UTF_8))
      eeprom.label = "opengpu harness"
      computer.inventory(2) = eeprom
      computer.inventory(3) = gpu
      workspace.add(sink)
      computer.connect(sink)
      assertTrue(computer.machine.start(), s"$label: machine.start(); lastError ${computer.machine.lastError}")
      val boot = rig.until("boot", spec.bootTicks)(sink.count("arch") > 0)
      val arch = sink.first("arch")
      val running = computer.machine.architecture
      assertTrue(spec.arch.cls.isInstance(running), s"$label: the machine runs $running, not ${spec.arch.cls}")
      if (spec.arch.native) {
        assertTrue(running.isInstanceOf[NativeLuaArchitecture], s"$label: $running is not a native Lua")
      }
      assertEquals(spec.arch.name, arch.str(0), s"$label: computer.getArchitecture()")
      assertEquals(spec.arch.version, arch.str(1), s"$label: _VERSION")
      println(s"[opengpu-ocelot] $label: ${spec.arch.name} booted in ${boot.ticks} ticks, ${boot.millis} ms, on " +
        s"${spec.host}")
      rig
    } catch {
      case e: Throwable =>
        try rig.close() catch { case c: Throwable => e.addSuppressed(c) }
        throw e
    }
  }

  /** builders, harness and the test's own files, in that order, as one chunk (S10 plan P8.2). */
  def source(files: Seq[String]): Array[Byte] =
    (Seq("ogpu_builders.lua", "harness.lua") ++ files).map(resource).mkString("\n").getBytes(StandardCharsets.UTF_8)

  /** A Lua file under `src/test/resources/lua/`. */
  def resource(name: String): String = {
    val in = classOf[Rig].getResourceAsStream(s"/lua/$name")
    require(in != null, s"lua/$name is not on the test classpath")
    try Source.fromInputStream(in)(Codec.UTF8).mkString finally in.close()
  }

  /**
    * The script's hold on the host's pumps (`ogpu_harness.hold`): a pump runs under the gate's lock, so a hold, taken
    * on brain's executor thread, returns only after a running pump has ended, and no pump starts until the release.
    * The lock is a leaf: a pump never waits for a machine lock, which the executor holds while it runs the script.
    */
  private final class PumpGate {
    private var held = false

    def pump(body: => Unit): Unit = synchronized(if (!held) body)

    def hold(on: Boolean): Unit = synchronized {
      held = on
    }
  }

  /**
    * The device's folds on one daemon thread, `opengpu-ocelot-fold-<deviceId>`, started at once so a leak shows by
    * name even when no readback came [proposal; S10 plan P7.5]. AbortPolicy runs every task it accepts and refuses
    * by throwing, which Device handles (28:1073); delivery crosses threads, as with the root's pool (21:91).
    */
  private def foldExecutor(deviceId: Int): ExecutorService = {
    val factory: ThreadFactory = (r: Runnable) => {
      val t = new Thread(r, s"${Threads.HarnessPrefix}fold-$deviceId")
      t.setDaemon(true)
      t
    }
    val executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue[Runnable](), factory,
      new ThreadPoolExecutor.AbortPolicy())
    executor.prestartAllCoreThreads()
    executor
  }
}
