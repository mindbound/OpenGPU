package io.github.mindbound.opengpu.ocelot

import java.nio.file.{Files, Path, Paths}
import java.util.regex.Pattern

import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue, fail}
import org.junit.jupiter.api.{Tag, Test, TestReporter, Timeout}
import totoro.ocelot.brain.Ocelot

import scala.jdk.CollectionConverters._

/**
  * Every ocelot Test task's own profile (05:110; S10 plan P14.2): Brain.init's assertions as test results, the profile
  * the task must have by [[ProfileTest.TaskProfiles]], the architectures the task runs registered, and brain's natives
  * in the run directory only. The brain commit goes into the class's report entries (05:116).
  */
@Tag("ocelot-every")
class ProfileTest {
  @Test
  @Timeout(60)
  def settingsAndArchitecturesAreTheTasksProfile(reporter: TestReporter): Unit = {
    val session = Brain.init()
    reporter.publishEntry("brainCommit", session.brainCommit)
    val task = Brain.property("opengpu.ocelot.task")
    val profile = ProfileTest.TaskProfiles.getOrElse(task,
      fail(s"$task is not in ProfileTest.TaskProfiles, the ocelot tasks and the profile each must have"))
    assertEquals(profile, session.profile.name, s"$task's profile")
    Archs.running.foreach { arch =>
      assertTrue(session.architectures.contains(arch.name), s"${session.profile.name} runs ${arch.id}, but " +
        s"${arch.name} is not among ${session.architectures.mkString(", ")}")
    }
    val full = if (Archs.full.isEmpty) "none" else Archs.full.mkString(", ")
    Report.line(s"profile ${session.profile.name} on $task: runs ${Archs.running.mkString(", ")}, the full set $full" +
      (if (Archs.narrowed) ", narrowed by -Popengpu.ocelot.archs" else ""))
  }

  @Test
  @Timeout(60)
  def nativesAreInTheRunDirectoryOnly(): Unit = {
    val session = Brain.init()
    val natives = names(session.runDir.resolve("natives"))
    // Brain copies each of the three JNLua natives as Ocelot-<version>-5x-libjnlua5x-<platform> (LuaStateFactory)
    val native = ("Ocelot-" + Pattern.quote(Ocelot.Version) + "-(5[234])-libjnlua\\1-.+").r
    assertEquals(Seq("52", "53", "54"), natives.collect { case native(v) => v }, s"natives: $natives")
    assertEquals(3, natives.size, s"natives: $natives")
    // Never in the working directory, which is this project's directory under Gradle (05 §4)
    val strays = names(Paths.get("").toAbsolutePath).filter(n => n.startsWith("Ocelot-") || n.startsWith("libjnlua"))
    assertEquals(Seq.empty, strays, "natives in the working directory")
  }

  private def names(dir: Path): Seq[String] = {
    val entries = Files.list(dir)
    try entries.iterator().asScala.map(_.getFileName.toString).toList.sorted finally entries.close()
  }
}

object ProfileTest {
  /**
    * Each ocelot Test task and its profile, a second source beside the build's `opengpu.ocelot.profile`, which
    * Brain.init loads and checks the settings against: a leg wired to another task's profile would pass those checks,
    * since 5.3 and 5.2 are registered under both, so it fails here (05 §3; S10 decisions D4-D6; review TS2).
    */
  val TaskProfiles: Map[String, String] = Map(
    "test" -> Profiles.Pack.name,
    "ocelotGlTest" -> Profiles.Pack.name,
    "ocelotInstanceTest" -> Profiles.Instance.name,
    "ocelotLuaJCorrectnessTest" -> Profiles.Instance.name,
    "ocelotLuajitTest" -> Profiles.LuajitName)
}
