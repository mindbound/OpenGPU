package io.github.mindbound.opengpu.ocelot

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest

import scala.collection.mutable

/**
  * This JVM's record of its leg (05:116, "test reports record the brain commit"; S10 plan P9.5): every line goes to
  * stdout with the `[opengpu-ocelot]` prefix CI greps for, and the whole record is rewritten after each line to
  * `<opengpu.ocelot.reportDir>/ocelot.txt`, so it is complete even when the JVM dies. Brain.init writes the brain pin,
  * the profile with its asserted values, the architectures, the natives with their sha256, `java.version` and
  * `os.name`; the GL and OC-LuaJIT legs add their own lines.
  */
object Report {
  private val lines = mutable.ArrayBuffer.empty[String]

  def line(text: String): Unit = synchronized {
    println(s"[opengpu-ocelot] $text")
    lines += text
    val dir = Paths.get(Brain.property("opengpu.ocelot.reportDir"))
    Files.createDirectories(dir)
    Files.write(dir.resolve("ocelot.txt"), (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8))
  }

  /** The lines so far. */
  def snapshot: Seq[String] = synchronized(lines.toList)

  /** The file's sha256, lower-case hex. */
  def sha256(file: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
    digest.map(b => f"${b & 0xff}%02x").mkString
  }
}
