package io.github.mindbound.opengpu.ocelot

import totoro.ocelot.brain.entity.machine.Architecture
import totoro.ocelot.brain.entity.machine.luac.{
  NativeLua52Architecture,
  NativeLua53Architecture,
  NativeLua54Architecture
}
import totoro.ocelot.brain.entity.machine.luaj.LuaJLuaArchitecture

import scala.jdk.CollectionConverters._

/**
  * One architecture a leg runs (S10 plan P12): its `opengpu.ocelot.archs` id, the name brain registers it under, which
  * `computer.getArchitecture()` returns, its class, the sandbox's `_VERSION` (brain's machine.lua normalises it to
  * "Lua 5.2", "Lua 5.3", "Lua 5.4" or "Luaj"), and whether it is a native one, which the rig asserts after boot so a
  * silent LuaJ fallback fails (05 §3; 19:202). `packPath` marks the runtimes 28 §9.3's selection names for the
  * `string.pack` path, 5.3 and 5.4, whose encoder must give the `char` path's bytes (28:813; S10 decisions D4).
  * `integers` marks the ones with an integer subtype, whose integral numbers reach Java as Long and come back from it
  * as Lua integers; 5.2's numbers are all doubles (28:796).
  */
final case class Arch(id: String, name: String, cls: Class[_ <: Architecture], version: String, native: Boolean,
    packPath: Boolean, integers: Boolean) {
  /** The id, so parameterised tests are named by it. */
  override def toString: String = id
}

/** Every architecture S10 knows, and the ones this Test task runs. */
object Archs {
  val Lua53: Arch = Arch("lua53", "Lua 5.3", classOf[NativeLua53Architecture], "Lua 5.3", native = true,
    packPath = true, integers = true)
  val Lua52: Arch = Arch("lua52", "Lua 5.2", classOf[NativeLua52Architecture], "Lua 5.2", native = true,
    packPath = false, integers = false)
  val Lua54: Arch = Arch("lua54", "Lua 5.4", classOf[NativeLua54Architecture], "Lua 5.4", native = true,
    packPath = true, integers = true)
  /** Non-gating, correctness only, and only on the instance profile, where brain registers it (S10 decisions D5). */
  val LuaJ: Arch = Arch("luaj", "LuaJ", classOf[LuaJLuaArchitecture], "Luaj", native = false, packPath = false,
    integers = false)

  /** Brain's own. */
  val all: Seq[Arch] = Seq(Lua53, Lua52, Lua54, LuaJ)

  val LuaJITId: String = "luajit"

  /**
    * OC-LuaJIT's, on the local leg only (S10 plan P13; decisions D6). Its adapter is compiled only when
    * -Popengpu.ocluajit.dir is set, so its class is looked up by name, without initialising it, the first time the leg
    * asks for it. brain's machine.lua gives LuaJIT's `_VERSION`, "Lua 5.1", as "Lua 5.2" (24:256); its numbers are
    * doubles, as on 5.2, and the leg leaves 28 §9.3's `string.pack` path out (28:907).
    */
  lazy val LuaJIT: Arch = Arch(LuaJITId, OcLuajit.Name,
    Class.forName(OcLuajit.ArchitectureClass, false, getClass.getClassLoader).asSubclass(classOf[Architecture]),
    "Lua 5.2", native = true, packPath = false, integers = false)

  def byId(id: String): Arch =
    if (id == LuaJITId) LuaJIT
    else all.find(_.id == id).getOrElse(throw new IllegalArgumentException(
      s"opengpu.ocelot.archs names $id; the architectures are ${(all.map(_.id) :+ LuaJITId).mkString(", ")}"))

  /** `opengpu.ocelot.archs`: the build's list for this task, narrowed by -Popengpu.ocelot.archs on a local run. */
  def running: Seq[Arch] = parse(Brain.property("opengpu.ocelot.archs"))

  /**
    * `opengpu.ocelot.fullSet`: the part of [[running]] that runs the whole gating set, every arch on the pack legs and
    * 5.4 alone on the instance leg (S10 decisions D4). Only a local -Popengpu.ocelot.archs can leave it empty.
    */
  def full: Seq[Arch] = {
    val list = System.getProperty("opengpu.ocelot.fullSet")
    require(list != null, "opengpu.ocelot.fullSet is not set: run the tests through Gradle's ocelot Test tasks")
    val set = parse(list)
    val outside = set.filterNot(running.contains)
    require(outside.isEmpty, s"opengpu.ocelot.fullSet names ${outside.mkString(", ")}, which the task does not run")
    set
  }

  /** `opengpu.ocelot.narrowed`: whether a local -Popengpu.ocelot.archs narrowed this task's lists. */
  def narrowed: Boolean = Brain.property("opengpu.ocelot.narrowed").toBoolean

  /**
    * A gating test's archs. Such a test allows zero invocations for a narrowed local run only: Gradle counts a method
    * with none as one passed test, which VerifyTestsRan cannot tell from a run, so on a task that is not narrowed an
    * empty list fails the test (S10 review TS1).
    */
  def gating(what: String, archs: Seq[Arch]): Seq[Arch] = {
    if (archs.isEmpty && !narrowed) {
      throw new IllegalStateException(s"$what is empty on ${Brain.property("opengpu.ocelot.task")}, which " +
        "-Popengpu.ocelot.archs did not narrow: its gating tests would pass without running")
    }
    archs
  }

  /** [[running]] for `@MethodSource("io.github.mindbound.opengpu.ocelot.Archs#task")`, one invocation per arch. */
  def task(): java.util.List[Arch] = running.asJava

  /** [[full]] for `@MethodSource("io.github.mindbound.opengpu.ocelot.Archs#fullSet")`. */
  def fullSet(): java.util.List[Arch] = gating("the full set", full).asJava

  /** The [[full]] archs with 28 §9.3's `string.pack` path, for `@MethodSource("...Archs#packPath")`. */
  def packPath(): java.util.List[Arch] = gating("the full set's string.pack archs", full.filter(_.packPath)).asJava

  private def parse(list: String): Seq[Arch] = list.split(',').toSeq.map(_.trim).filter(_.nonEmpty).map(byId)
}
