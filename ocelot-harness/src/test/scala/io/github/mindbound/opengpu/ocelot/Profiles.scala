package io.github.mindbound.opengpu.ocelot

import java.nio.file.{Path, Paths}

import com.typesafe.config.ConfigUtil

/**
  * One brain.conf profile (05 §3): an override block over brain's own defaults, in HOCON paths under `opencomputers`,
  * and the values Brain.init asserts through Settings.get after Ocelot.initialize(), with the architectures that must
  * then be registered, in their order. `ocluajit` marks the OC-LuaJIT leg's, whose architecture Brain.init registers.
  */
final case class Profile(
    name: String,
    overrides: String,
    callBudgets: Seq[Double],
    maxSignalQueueSize: Int,
    ramSizes: Seq[Int],
    allowBytecode: Boolean,
    threads: Int,
    timeout: Double,
    enableLua54: Boolean,
    registerLuaJArchitecture: Boolean,
    architectures: Seq[String],
    ocluajit: Boolean = false)

/**
  * The pack's and the owner's instance's settings (10's summary items 1-2, 10 F3; 19 §9's table). A partial or
  * unreadable brain.conf makes brain fall back to its defaults without failing (19 §9), so the asserted values include
  * ones that differ from those defaults in each profile (S10 amendments I6): on pack, maxSignalQueueSize (1024 against
  * 256) and enableLua54 (false against true), so 5.4 is not registered; on instance, callBudgets, ramSizes,
  * allowBytecode and registerLuaJArchitecture, so LuaJ is. Brain.init also asserts every override's own value.
  */
object Profiles {
  /** Both profiles: no network for the tests, and a native that fails to load says why [proposal; S10 plan P9.1]. */
  private val Common =
    """opencomputers.internet.enableHttp = false
      |opencomputers.internet.enableTcp = false
      |opencomputers.debug.logFullNativeLibLoadErrors = true
      |""".stripMargin

  /** GTNH 2.9.0-RC-2's OpenComputers.cfg: OC's defaults, with the queue of PR #27079 (10 item 1), and Common's keys. */
  val Pack: Profile = Profile(
    name = "pack",
    overrides = Common +
      """opencomputers.computer.callBudgets = [0.5, 1.0, 1.5]
        |opencomputers.computer.maxSignalQueueSize = 1024
        |opencomputers.computer.threads = 4
        |opencomputers.computer.timeout = 5
        |opencomputers.computer.lua.ramSizes = [192, 256, 384, 512, 768, 1024]
        |opencomputers.computer.lua.ramScaleFor64Bit = 1.8
        |opencomputers.computer.lua.allowBytecode = false
        |opencomputers.computer.lua.enableLua53 = true
        |opencomputers.computer.lua.enableLua54 = false
        |opencomputers.computer.lua.defaultLua53 = false
        |opencomputers.debug.registerLuaJArchitecture = false
        |opencomputers.debug.forceLuaJ = false
        |""".stripMargin,
    callBudgets = Seq(0.5, 1.0, 1.5),
    maxSignalQueueSize = 1024,
    ramSizes = Seq(192, 256, 384, 512, 768, 1024),
    allowBytecode = false,
    threads = 4,
    timeout = 5.0,
    enableLua54 = false,
    registerLuaJArchitecture = false,
    architectures = Seq("Lua 5.3", "Lua 5.2"))

  /** The owner's instance: the eleven keys of 10 item 2, its maxSignalQueueSize = 256 (10 F3) and Common's keys. */
  val Instance: Profile = Profile(
    name = "instance",
    overrides = Common +
      """opencomputers.computer.callBudgets = [1, 2, 4]
        |opencomputers.gpu.vramSizes = [1, 2, 4]
        |opencomputers.computer.lua.ramSizes = [256, 512, 1024, 2048, 4096, 8192]
        |opencomputers.computer.lua.maxTotalRam = 134217728
        |opencomputers.computer.lua.defaultLua53 = true
        |opencomputers.computer.lua.enableLua54 = true
        |opencomputers.computer.lua.allowBytecode = true
        |opencomputers.computer.lua.allowGC = true
        |opencomputers.debug.registerLuaJArchitecture = true
        |opencomputers.misc.maxWirelessRange = [16, 512]
        |opencomputers.filesystem.maxHandles = 64
        |opencomputers.computer.maxSignalQueueSize = 256
        |""".stripMargin,
    callBudgets = Seq(1.0, 2.0, 4.0),
    maxSignalQueueSize = 256,
    ramSizes = Seq(256, 512, 1024, 2048, 4096, 8192),
    allowBytecode = true,
    threads = 4,
    timeout = 5.0,
    enableLua54 = true,
    registerLuaJArchitecture = true,
    architectures = Seq("Lua 5.3", "Lua 5.4", "Lua 5.2", "LuaJ"))

  val LuajitName: String = "luajit"

  /**
    * The local OC-LuaJIT leg's (S10 plan P13; decisions D6): the pack's settings, with debug.forceNativeLibPathFirst at
    * `nativeDir`, where Brain.init stages the one OC-LuaJIT native (amendments I11). Brain's own factories find no
    * libjnlua5x there and take their natives from the jar as on the pack; OcljArch's finds its libjnluajit52, as in
    * OC-LuaJIT's smoke-test.sh:533-542. OC-LuaJIT's architecture is registered after Ocelot.initialize(), so it comes
    * last.
    */
  def luajit(nativeDir: Path): Profile = Pack.copy(
    name = LuajitName,
    overrides = Pack.overrides +
      s"opencomputers.debug.forceNativeLibPathFirst = ${ConfigUtil.quoteString(nativeDir.toString)}\n",
    architectures = Pack.architectures :+ OcLuajit.Name,
    ocluajit = true)

  /** The Test task's profile, `opengpu.ocelot.profile`. */
  def named(name: String): Profile = name match {
    case Pack.name => Pack
    case Instance.name => Instance
    case LuajitName => luajit(Paths.get(Brain.property("opengpu.ocelot.runDir")).resolve(OcLuajit.NativeDir))
    case other =>
      throw new IllegalArgumentException(s"opengpu.ocelot.profile is '$other', not pack, instance or $LuajitName")
  }
}
