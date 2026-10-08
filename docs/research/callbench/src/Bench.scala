package callbench

import li.cil.repack.com.naef.jnlua.LuaState
import totoro.ocelot.brain.{Ocelot, Settings}
import totoro.ocelot.brain.entity.{CPU, Case, EEPROM, Memory}
import totoro.ocelot.brain.entity.machine.{Arguments, Callback, Context, Machine}
import totoro.ocelot.brain.entity.machine.luac.{LuaStateFactory, NativeLua52Architecture, NativeLua53Architecture, NativeLua54Architecture, NativeLuaArchitecture}
import totoro.ocelot.brain.entity.traits.{Entity, Environment}
import totoro.ocelot.brain.network.{Component, Network, Visibility}
import totoro.ocelot.brain.util.ResultWrapper.result
import totoro.ocelot.brain.util.{ExtendedTier, Tier}
import totoro.ocelot.brain.workspace.Workspace

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.concurrent.{Executors, TimeUnit}

/**
 * The minimal @Callback entity, modelled on ocelot-brain's ColorfulLamp.scala.
 *
 * Every measured method is `direct`.  The *256 variants carry limit = 256 (the
 * budget cost OC's own text GPU charges per T3 `set`); the rest keep the
 * Integer.MAX_VALUE default (cost 4.66e-10 per call, effectively unlimited).
 * `sync` is the one NON-direct method: the control that costs a whole server
 * tick per call.
 *
 * TIMING IS DONE ON THE JAVA SIDE.  `hit()` stamps System.nanoTime() on every
 * measured call; `begin`/`finish` bracket a batch and `finish` returns the
 * interval between the FIRST and LAST stamp, so the per-call figure is the
 * full round trip Lua loop -> kernel wrapper -> JNI -> Machine.invoke ->
 * callback -> result push -> back in the Lua loop, averaged over n-1 intervals.
 * The Lua side cross-checks with os.clock() (Machine.cpuTime, nanoTime based).
 * `hit()` also records which server tick each call landed in, which is how
 * the budget stall of the limit=256 variants is observed directly.
 */
class Bench extends Entity with Environment {
  override val node: Component = Network.newNode(this, Visibility.Network).
    withComponent("bench", Visibility.Network).
    create()

  @volatile var tick: Int = 0                 // bumped by the ticker after every ws.update()
  @volatile var done = false
  @volatile var n = 0L
  @volatile var first = 0L
  @volatile var last = 0L
  @volatile var recordTicks = false
  private val perTick = scala.collection.mutable.TreeMap.empty[Int, Int]

  private def hit(): Unit = {
    val t = System.nanoTime()
    if (n == 0) first = t
    last = t
    n += 1
    if (recordTicks) perTick.synchronized { val k = tick; perTick(k) = perTick.getOrElse(k, 0) + 1 }
  }

  // ---- unlimited (limit = Integer.MAX_VALUE) -------------------------------
  @Callback(direct = true, doc = "function()")
  def noop(context: Context, args: Arguments): Array[AnyRef] = { hit(); null }

  @Callback(direct = true, doc = "function(a:number, b:number):number")
  def add(context: Context, args: Arguments): Array[AnyRef] = { hit(); result(args.checkInteger(0) + args.checkInteger(1)) }

  @Callback(direct = true, doc = "function(s:string):number -- string in, length out")
  def sink(context: Context, args: Arguments): Array[AnyRef] = { hit(); result(args.checkByteArray(0).length) }

  @Callback(direct = true, doc = "function(s:string):string -- string in, same string out")
  def echo(context: Context, args: Arguments): Array[AnyRef] = { hit(); result(args.checkByteArray(0)) }

  @Callback(direct = true, doc = "function(t:table):number -- table in, size out")
  def sinkTable(context: Context, args: Arguments): Array[AnyRef] = { hit(); result(args.checkTable(0).size) }

  @Callback(direct = true, doc = "function(t:table):table -- table in, same table out")
  def echoTable(context: Context, args: Arguments): Array[AnyRef] = { hit(); result(args.checkTable(0)) }

  // ---- limited (limit = 256, i.e. 1/256 budget per call) -------------------
  @Callback(direct = true, limit = 256, doc = "function()")
  def noop256(context: Context, args: Arguments): Array[AnyRef] = { hit(); null }

  @Callback(direct = true, limit = 256, doc = "function(a:number, b:number):number")
  def add256(context: Context, args: Arguments): Array[AnyRef] = { hit(); result(args.checkInteger(0) + args.checkInteger(1)) }

  @Callback(direct = true, limit = 256, doc = "function(s:string):string")
  def echo256(context: Context, args: Arguments): Array[AnyRef] = { hit(); result(args.checkByteArray(0)) }

  @Callback(direct = true, limit = 256, doc = "function(t:table):table")
  def echoTable256(context: Context, args: Arguments): Array[AnyRef] = { hit(); result(args.checkTable(0)) }

  // ---- the control: NOT direct, one synchronized call per server tick ------
  @Callback(doc = "function() -- NOT direct")
  def sync(context: Context, args: Arguments): Array[AnyRef] = { hit(); null }

  // ---- instrumentation (direct, not counted) --------------------------------
  @Callback(direct = true, doc = "function(recordTicks:boolean)")
  def begin(context: Context, args: Arguments): Array[AnyRef] = {
    recordTicks = args.optBoolean(0, false)
    perTick.synchronized { perTick.clear() }
    n = 0; first = 0; last = 0
    null
  }

  /** returns n, elapsed_ns (first stamp -> last stamp), per-tick summary string */
  @Callback(direct = true, doc = "function():number, number, string")
  def finish(context: Context, args: Arguments): Array[AnyRef] = {
    val elapsed = if (n >= 2) last - first else 0L
    val summary = perTick.synchronized {
      if (perTick.isEmpty) ""
      else {
        val counts = perTick.values.toIndexedSeq      // TreeMap: in tick order
        // full ticks = every tick but the first and the last (both partial)
        val full = if (counts.size > 2) counts.slice(1, counts.size - 1) else counts
        val sorted = full.sorted
        val med = if (sorted.isEmpty) 0 else sorted(sorted.size / 2)
        "ticks_spanned=" + counts.size + " full_ticks=" + full.size +
          " per_full_tick_min=" + (if (sorted.isEmpty) 0 else sorted.head) +
          " per_full_tick_med=" + med + " per_full_tick_max=" + (if (sorted.isEmpty) 0 else sorted.last) +
          " first_tick=" + counts.head + " last_tick=" + counts.last + " seq=" + counts.take(64).mkString(",")
      }
    }
    recordTicks = false
    result(n, elapsed.toDouble, summary)
  }

  @Callback(direct = true, doc = "function(line:string)")
  def report(context: Context, args: Arguments): Array[AnyRef] = {
    val s = args.checkString(0)
    Main.p(s)
    if (s == "DONE") done = true
    null
  }

  @Callback(direct = true, doc = "function():number, number")
  def budget(context: Context, args: Arguments): Array[AnyRef] =
    result(context.getRemainingCallBudget, context.getMaxCallBudget)

  /** Read off the RAW state from inside the callback (we are on the executor
    * thread, inside the state's own invoke, so no other thread can be using it). */
  @Callback(direct = true, doc = "function():string")
  def fingerprint(context: Context, args: Arguments): Array[AnyRef] = {
    val (arch, lua) = Main.rawState(context)
    val s = Main.evalStr(lua,
      "return _VERSION .. ' native=' .. tostring(rawget(_G,'_OCLJ_NATIVE') or '<stock>') .. " +
        "' kernel=' .. tostring(rawget(_G,'_OCLJ_KERNEL') or '<stock>') .. " +
        "' jit=' .. tostring(jit and jit.version or 'none') .. " +
        "' jitstatus=' .. tostring(jit and jit.status and jit.status() or 'n/a')")
    result(s + " luaStateClass=" + lua.getClass.getSimpleName + " arch=" + arch.getClass.getSimpleName)
  }

  @Callback(direct = true, doc = "function(on:boolean):string -- jit.on()/jit.off()+jit.flush() on the raw state")
  def jit(context: Context, args: Arguments): Array[AnyRef] = {
    val (_, lua) = Main.rawState(context)
    val on = args.checkBoolean(0)
    result(Main.evalStr(lua,
      "if not jit then return 'no jit table' end " +
        (if (on) "jit.on() " else "jit.off() jit.flush() ") +
        "return 'jit.status()=' .. tostring(jit.status())"))
  }
}

object Main {
  def p(s: String): Unit = { println("BENCH| " + s); System.out.flush() }

  def die(s: String): Nothing = {
    p("FATAL: " + s)
    try Ocelot.shutdown() catch { case _: Throwable => }
    System.exit(3)
    throw new RuntimeException()
  }

  def luaOf(arch: AnyRef): LuaState = {
    val f = classOf[NativeLuaArchitecture].getDeclaredField("lua")
    f.setAccessible(true)
    f.get(arch).asInstanceOf[LuaState]
  }

  def rawState(context: Context): (AnyRef, LuaState) = {
    val m = context.asInstanceOf[Machine]
    val arch = m.architecture
    (arch, luaOf(arch))
  }

  def evalStr(lua: LuaState, code: String): String = {
    val top = lua.getTop
    try {
      lua.load(new ByteArrayInputStream(code.getBytes("UTF-8")), "=probe", "t")
      lua.call(0, 1)
      String.valueOf(lua.toString(-1))
    } catch { case t: Throwable => "<error " + t + ">" }
    finally lua.setTop(top)
  }

  def main(args: Array[String]): Unit = {
    if (args.length < 5) die("usage: Main <ocelot.conf> <52|53|54|luajit> <bench.lua> <workloads:comma list|all> <jit:on|off> [tag] [ramSticks] [cpuTier] [total]")
    val conf = args(0)
    val archName = args(1)
    val luaPath = args(2)
    val workloadsArg = args(3)
    val jitMode = args(4)
    val tag = if (args.length > 5) args(5) else ""
    val ramSticks = if (args.length > 6) args(6).toInt else 2
    val cpuTier = if (args.length > 7) args(7).toInt else 3
    val total = if (args.length > 8) args(8).toInt else 100000

    p("tag=" + tag + " arch=" + archName + " jit=" + jitMode + " ramSticks=" + ramSticks + " cpuTier=" + cpuTier + " total=" + total)
    p("java.version=" + System.getProperty("java.version") + " vm=" + System.getProperty("java.vm.name") +
      " " + System.getProperty("java.vm.version") + " vendor=" + System.getProperty("java.vendor"))
    p("os=" + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch") +
      " cpus=" + Runtime.getRuntime.availableProcessors + " cwd=" + Paths.get("").toAbsolutePath)

    Ocelot.configPath = Some(Paths.get(conf))
    Ocelot.initialize()
    p("LuaStateFactory.isAvailable=" + LuaStateFactory.isAvailable + " includeLuaJ=" + LuaStateFactory.includeLuaJ +
      " include52=" + LuaStateFactory.include52 + " include53=" + LuaStateFactory.include53 + " include54=" + LuaStateFactory.include54)
    p("forceNativeLibPathFirst='" + Settings.get.forceNativeLibPathFirst + "'")
    p("callBudgets=" + Settings.get.callBudgets.mkString("[", ",", "]") + " executionDelay=" + Settings.get.executionDelay +
      "ms timeout=" + Settings.get.timeout + "s threads=" + Settings.get.threads + " ramScale=" + Settings.get.ramScaleFor64Bit +
      " allowBytecode=" + Settings.get.allowBytecode + " ramSizes=" + Settings.get.ramSizes.mkString("[", ",", "]"))
    if (LuaStateFactory.includeLuaJ) die("LuaJ is in play (a native failed to load); refusing to measure")

    val archClass: Class[_ <: totoro.ocelot.brain.entity.machine.Architecture] = archName match {
      case "52" => classOf[NativeLua52Architecture]
      case "53" => classOf[NativeLua53Architecture]
      case "54" => classOf[NativeLua54Architecture]
      case "luajit" =>
        ocljit.arch.OCLuaJITStateFactory.register()
        classOf[ocljit.arch.OCLuaJITArchitecture]
      case other => die("unknown arch " + other)
    }

    val template = new String(Files.readAllBytes(Paths.get(luaPath)), StandardCharsets.UTF_8)
    val allWorkloads = Seq("local_call", "invoke_noop", "proxy_noop", "invoke_add", "sink_100KB", "echo_100KB",
      "sinkTable_1000", "echoTable_1000", "lim_noop256", "lim_add256", "lim_echo256", "lim_echoTable256", "sync_noop")
    val workloads = if (workloadsArg == "all") allWorkloads else workloadsArg.split(",").map(_.trim).filter(_.nonEmpty).toSeq

    for (w <- workloads) {
      p("---- workload=" + w + " arch=" + archName + " jit=" + jitMode + " tag=" + tag)
      val luaSrc = template.replace("%%WORKLOAD%%", w).replace("%%TOTAL%%", total.toString).replace("%%JIT%%", jitMode)
      val wsDir = Files.createTempDirectory("callbench-ws")
      val ws = new Workspace(wsDir)
      val computer = ws.add(new Case(Tier.Three))
      val cpu = new CPU(if (cpuTier == 1) Tier.One else if (cpuTier == 2) Tier.Two else Tier.Three)
      computer.inventory(0) = cpu
      val ramTier = if (cpuTier == 1) ExtendedTier.One else if (cpuTier == 2) ExtendedTier.Two else ExtendedTier.ThreeHalf
      for (i <- 0 until ramSticks) computer.inventory(1 + i) = new Memory(ramTier)
      val eeprom = new EEPROM
      eeprom.codeBytes = Some(luaSrc.getBytes(StandardCharsets.UTF_8))
      eeprom.label = "callbench"
      computer.inventory(1 + ramSticks) = eeprom
      val bench = ws.add(new Bench)
      computer.connect(bench)
      cpu.setArchitecture(archClass)

      // A server-tick clock: ws.update() every 50 ms at a fixed rate, the
      // cadence that resets the call budget and performs synchronized calls.
      val ticker = Executors.newSingleThreadScheduledExecutor()
      @volatile var tickErr: Throwable = null
      @volatile var ticks = 0L
      @volatile var lastTickNs = 0L
      @volatile var maxGapNs = 0L
      var sumGapNs = 0L
      ticker.scheduleAtFixedRate(new Runnable {
        def run(): Unit = try {
          val now = System.nanoTime()
          if (lastTickNs != 0) { val g = now - lastTickNs; sumGapNs += g; if (g > maxGapNs) maxGapNs = g }
          lastTickNs = now
          ws.update()
          ticks += 1
          bench.tick = ticks.toInt
        } catch { case t: Throwable => tickErr = t }
      }, 0, 50, TimeUnit.MILLISECONDS)

      if (!computer.machine.start()) die("machine.start() returned false")
      val t0 = System.nanoTime()
      val deadlineS = 300
      while (!bench.done && computer.machine.isRunning && (System.nanoTime() - t0) < deadlineS * 1e9 && tickErr == null) {
        Thread.sleep(20)
      }
      val wall = (System.nanoTime() - t0) / 1e9
      if (tickErr != null) p("!! ticker threw: " + tickErr)
      if (!bench.done) p("!! FAILED workload=" + w + ": machine did not report DONE: running=" + computer.machine.isRunning +
        " lastError=" + computer.machine.lastError + " after " + "%.1f".format(wall) + " s")
      val arch = computer.machine.architecture
      val archOk = arch != null && archClass.isInstance(arch)
      if (!archOk) p("!! architecture is " + (if (arch == null) "null" else arch.getClass.getName) + ", not " + archClass.getName)
      p("END workload=" + w + " ok=" + (bench.done && archOk) + " maxCallBudget=" + computer.machine.getMaxCallBudget +
        " calls_seen=" + bench.n + " wall_s=" + "%.1f".format(wall) + " ticks=" + ticks +
        " tick_gap_mean_ms=" + (if (ticks > 1) "%.2f".format(sumGapNs / 1e6 / (ticks - 1)) else "n/a") +
        " tick_gap_max_ms=" + "%.2f".format(maxGapNs / 1e6) + " lastError=" + computer.machine.lastError)
      ticker.shutdownNow()
      try ticker.awaitTermination(2, TimeUnit.SECONDS) catch { case _: Throwable => }
      try computer.machine.stop() catch { case _: Throwable => }
      try { var q = 0; while (computer.machine.isRunning && q < 100) { ws.update(); Thread.sleep(10); q += 1 } } catch { case _: Throwable => }
    }
    p("ALLDONE arch=" + archName + " jit=" + jitMode + " tag=" + tag)
    try Ocelot.shutdown() catch { case _: Throwable => }
    System.exit(0)
  }
}
