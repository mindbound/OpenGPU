package io.github.mindbound.opengpu.ocelot

import java.io.{IOException, Reader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.{Comparator, Properties}

import com.typesafe.config.ConfigFactory
import org.junit.jupiter.api.Assertions.{assertEquals, fail}
import totoro.ocelot.brain.entity.machine.MachineAPI
import totoro.ocelot.brain.{Ocelot, Settings}

import scala.jdk.CollectionConverters._

/**
  * What one JVM's Ocelot initialisation gave: its profile, run directory, brain.conf and architectures, the brain pin
  * it ran on, and the threads alive before it, for ShutdownTest's non-daemon rule (05:117).
  */
final class Session(
    val profile: Profile,
    val runDir: Path,
    val conf: Path,
    val initMillis: Long,
    val architectures: Seq[String],
    val brainCommit: String,
    val threadsBefore: Set[Thread])

/**
  * One Ocelot initialisation per test JVM (05 §3), in the run directory the build passes (`opengpu.ocelot.runDir`),
  * with brain's natives in its natives/ (05 §4: never in the working directory). Its first attempt is kept, success
  * or failure, so Ocelot.initialize() runs at most once per JVM and every later caller sees the first error unchanged
  * (S10 amendments I12); a second profile in one JVM is refused.
  */
object Brain {
  private var first: Option[(String, Either[Throwable, Session])] = None

  /** The Test task's profile, `opengpu.ocelot.profile`. */
  def init(): Session = init(Profiles.named(property("opengpu.ocelot.profile")))

  def init(profile: Profile): Session = synchronized {
    first match {
      case Some((name, _)) if name != profile.name =>
        throw new IllegalStateException(s"Brain.init(${profile.name}) after Brain.init($name): one profile per JVM")
      case Some((_, outcome)) => outcome.fold(e => throw e, identity)
      case None =>
        val outcome = try Right(start(profile)) catch { case e: Throwable => Left(e) }
        first = Some((profile.name, outcome))
        outcome.fold(e => throw e, identity)
    }
  }

  def property(name: String): String = {
    val value = System.getProperty(name)
    require(value != null && value.nonEmpty, s"$name is not set: run the tests through Gradle's ocelot Test tasks")
    value
  }

  private def start(profile: Profile): Session = {
    val runDir = Paths.get(property("opengpu.ocelot.runDir"))
    empty(runDir)
    val threadsBefore = Threads.live()
    stageNative(runDir)
    val conf = BrainConf.write(runDir.resolve("brain.conf"), profile.overrides)
    Ocelot.configPath = Some(conf)
    Ocelot.librariesPath = Some(runDir.resolve("natives"))
    val start = System.nanoTime()
    Ocelot.initialize()
    val ms = (System.nanoTime() - start) / 1000000
    val pin = readPin(Paths.get(property("opengpu.ocelot.pinFile")))
    val p = profile.name
    Report.line(s"profile $p: Ocelot ${Ocelot.Version} initialised in $ms ms from $conf (${Files.size(conf)} B)")
    val from = Option(pin.getProperty("fetchedFrom")).filter(_.nonEmpty).getOrElse("a source the store did not record")
    Report.line(s"brain ${pin.getProperty("commit")}, tree ${pin.getProperty("tree")}, ${pin.getProperty("files")} " +
      s"files, from $from")
    Report.line(s"java ${System.getProperty("java.version")} (${System.getProperty("java.vendor")}) on " +
      s"${System.getProperty("os.name")} ${System.getProperty("os.arch")}; ${Cadence.pumpsPerTick} host frames per " +
      s"${Cadence.TickMillis} ms tick, readback cap ${Cadence.readbackCapTicks} ticks")
    // OC-LuaJIT's architecture, which brain does not know, after brain's own (S10 plan P13.3)
    if (profile.ocluajit) OcLuajit.start(runDir)
    val architectures = MachineAPI.architectures.map(a => MachineAPI.getArchitectureName(a)).toSeq
    val s = Settings.get
    Report.line(s"profile $p: callBudgets ${s.callBudgets.mkString("[", ", ", "]")}, maxSignalQueueSize " +
      s"${s.maxSignalQueueSize}, ramSizes ${s.ramSizes.mkString("[", ", ", "]")}, allowBytecode ${s.allowBytecode}, " +
      s"threads ${s.threads}, timeout ${s.timeout}, enableLua54 ${s.enableLua54}, registerLuaJArchitecture " +
      s"${s.registerLuaJArchitecture}; architectures ${architectures.mkString(", ")}")
    check(profile, s, architectures)
    val natives = runDir.resolve("natives")
    if (Files.isDirectory(natives)) {
      list(natives).foreach(f => Report.line(s"native ${f.getFileName} ${Files.size(f)} B sha256 ${Report.sha256(f)}"))
    }
    new Session(profile, runDir, conf, ms, architectures, pin.getProperty("commit"), threadsBefore)
  }

  /**
    * Settings.get against the profile (05:110; S10 plan P9.3): its typed values, every override's own value in the
    * configuration brain loaded, and the architectures exactly, in their order.
    */
  private def check(profile: Profile, s: Settings, architectures: Seq[String]): Unit = {
    val p = profile.name
    assertEquals(profile.callBudgets, s.callBudgets.toSeq, s"$p: callBudgets")
    assertEquals(profile.maxSignalQueueSize, s.maxSignalQueueSize, s"$p: maxSignalQueueSize")
    assertEquals(profile.ramSizes, s.ramSizes.toSeq, s"$p: ramSizes")
    assertEquals(profile.allowBytecode, s.allowBytecode, s"$p: allowBytecode")
    assertEquals(profile.threads, s.threads, s"$p: threads")
    assertEquals(profile.timeout, s.timeout, s"$p: timeout")
    assertEquals(profile.enableLua54, s.enableLua54, s"$p: enableLua54")
    assertEquals(profile.registerLuaJArchitecture, s.registerLuaJArchitecture, s"$p: registerLuaJArchitecture")
    val layer = ConfigFactory.parseString(profile.overrides)
    layer.entrySet().asScala.map(_.getKey).toList.sorted.foreach { path =>
      val key = path.stripPrefix("opencomputers.")
      if (!s.config.hasPath(key)) fail(s"$p: the loaded configuration has no $key")
      assertEquals(layer.getValue(path).unwrapped(), s.config.getValue(key).unwrapped(), s"$p: $key")
    }
    assertEquals(profile.architectures, architectures, s"$p: registered architectures")
  }

  /**
    * `opengpu.ocelot.stageNative=<file>` (the OC-LuaJIT leg's native): copied into `<runDir>/oclj-natives/` after the
    * run directory is emptied, so the emptying cannot delete it and brain, which deletes a forceNativeLibPathFirst
    * file that fails to load, only ever sees a copy (S10 amendments I11).
    */
  private def stageNative(runDir: Path): Unit = Option(System.getProperty("opengpu.ocelot.stageNative"))
    .filter(_.nonEmpty).map(Paths.get(_)).foreach { source =>
      val dir = Files.createDirectories(runDir.resolve(OcLuajit.NativeDir))
      val copy = Files.copy(source, dir.resolve(source.getFileName), StandardCopyOption.COPY_ATTRIBUTES)
      Report.line(s"staged native $source as $copy (${Files.size(copy)} B, sha256 ${Report.sha256(copy)})")
    }

  private def readPin(file: Path): Properties = {
    val pin = new Properties()
    val in: Reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)
    try pin.load(in) finally in.close()
    pin
  }

  private def list(dir: Path): Seq[Path] = {
    val entries = Files.list(dir)
    try entries.iterator().asScala.toList.sortBy(_.getFileName.toString) finally entries.close()
  }

  /**
    * Deletes everything under `dir` and makes it again, so each run starts from nothing; a file an earlier JVM still
    * holds (a loaded DLL, on Windows) is named (S10 amendments I11).
    */
  private def empty(dir: Path): Unit = {
    if (Files.exists(dir)) {
      val paths = Files.walk(dir)
      try paths.sorted(Comparator.reverseOrder[Path]()).iterator().asScala.foreach { path =>
        try Files.delete(path)
        catch {
          case e: IOException =>
            throw new IllegalStateException(s"cannot empty the run directory $dir: $path is held (${e.getMessage}); " +
              "a JVM of an earlier run may still have it open", e)
        }
      } finally paths.close()
    }
    Files.createDirectories(dir)
  }
}
