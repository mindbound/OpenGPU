package io.github.mindbound.opengpu.ocelot

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileVisitResult, Files, Path, Paths, SimpleFileVisitor}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.{Order, Tag, Test, TestReporter, Timeout}
import totoro.ocelot.brain.Ocelot

/**
  * The last class of every ocelot Test task (junit-platform.properties orders classes by `@Order`; S10 plan P14.9).
  * After every rig is closed it shuts Ocelot down and checks 05's rules on what the harness leaves behind:
  *  - every rig and every event buffer closed (05:115);
  *  - no thread the harness started still alive, by name and daemon or not, and no non-daemon thread outside the
  *    snapshot Brain.init took (05:117 as S10 applies it; amendments F6, I9). `opengpu-gl`, which `:gl-testkit` keeps
  *    for the JVM, and brain's `Ocelot-*` pools, which outlive `Ocelot.shutdown()`, are allowed and reported;
  *  - no native in the project directory outside build/, nor in the working directory (05:128; plan P9.4).
  */
@Tag("ocelot-every")
@Order(Integer.MAX_VALUE)
class ShutdownTest {
  @Test
  @Timeout(60)
  def nothingIsLeftBehind(reporter: TestReporter): Unit = {
    val session = Brain.init()
    reporter.publishEntry("brainCommit", session.brainCommit)
    assertEquals(Seq.empty, Rig.open, "rigs not closed")
    assertEquals(Seq.empty, EventBuffer.open, "event buffers not closed")

    Ocelot.shutdown()
    val harness = Threads.harnessAlive(ShutdownTest.GraceMillis)
    val leaked = Threads.leakedNonDaemon(session.threadsBefore)
    val allowed = Threads.live().filter(Threads.allowed)
    Report.line(s"after Ocelot.shutdown(): harness threads ${Threads.describe(harness)}; non-daemon threads since " +
      s"Brain.init ${Threads.describe(leaked)}; allowed ${Threads.describe(allowed)}")
    assertEquals("none", Threads.describe(harness), "threads the harness started, still alive")
    assertEquals("none", Threads.describe(leaked), "non-daemon threads started since Brain.init, still alive")

    val project = Paths.get(Brain.property("opengpu.ocelot.projectDir"))
    // brain's default librariesPath is "./" (Ocelot.scala:45), the working directory itself
    val cwd = Files.list(Paths.get("").toAbsolutePath)
    val strays = try {
      natives(project) ++ cwd.iterator().asScala.filter(f => Files.isRegularFile(f) && isNative(f))
        .filterNot(_.startsWith(project))
    } finally cwd.close()
    Report.line(s"natives outside build/ in $project and the working directory: ${strays.size}")
    assertEquals(Seq.empty, strays.map(_.toString), "natives outside the build directory")
  }

  /** Every Ocelot-*, libjnlua* or libjnluajit* file under `dir`, skipping build/ and .gradle/. */
  private def natives(dir: Path): Seq[Path] = {
    val found = mutable.ArrayBuffer.empty[Path]
    Files.walkFileTree(dir, new SimpleFileVisitor[Path] {
      override def preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult = {
        val name = Option(d.getFileName).map(_.toString).getOrElse("")
        if (d != dir && (name == "build" || name == ".gradle")) FileVisitResult.SKIP_SUBTREE
        else FileVisitResult.CONTINUE
      }

      override def visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult = {
        if (isNative(f)) found += f
        FileVisitResult.CONTINUE
      }

      override def visitFileFailed(f: Path, e: IOException): FileVisitResult = FileVisitResult.CONTINUE
    })
    found.toList
  }

  /** Brain's copies are Ocelot-<version>-5x-libjnlua5x-<platform> (LuaStateFactory), OC-LuaJIT's libjnluajit52-*. */
  private def isNative(f: Path): Boolean = {
    val name = f.getFileName.toString
    name.startsWith("Ocelot-") || name.startsWith("libjnlua")
  }
}

object ShutdownTest {
  /** How long a harness thread may take to end after its executor reported termination. */
  val GraceMillis: Long = 2000L
}
