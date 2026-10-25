package io.github.mindbound.opengpu.ocelot

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import totoro.ocelot.brain.Settings

import scala.io.{Codec, Source}
import scala.jdk.CollectionConverters._

/**
  * A complete brain.conf (05 §3's rule): brain's own /application.conf from the brain jar, one profile's overrides
  * layered over it, resolved and rendered to a file, so the file stays complete when the brain pin moves. Brain reads
  * the file alone and falls back to its defaults on any error (Settings.scala:243-280; 19 §9), so the file is checked
  * before brain sees it.
  */
object BrainConf {
  /** Brain's defaults, parsed as Settings.load parses them (Settings.scala:270-276). */
  def defaults(): Config = {
    val in = classOf[Settings].getResourceAsStream("/application.conf")
    require(in != null, "brain's /application.conf is not on the classpath")
    try ConfigFactory.parseString(Source.fromInputStream(in)(Codec.UTF8).mkString) finally in.close()
  }

  /**
    * The profile's brain.conf as text, without comments. An override whose path brain's defaults lack is refused, since
    * brain would ignore a misspelt key without a trace [proposal; S10 plan P9.2].
    */
  def render(overrides: String): String = {
    val base = defaults()
    val layer = ConfigFactory.parseString(overrides)
    val unknown = layer.entrySet().asScala.map(_.getKey).filterNot(base.hasPath).toList.sorted
    require(unknown.isEmpty, s"brain.conf overrides name paths brain's defaults lack: ${unknown.mkString(", ")}")
    layer.withFallback(base).resolve().root().render(
      ConfigRenderOptions.defaults().setOriginComments(false).setComments(false).setJson(false))
  }

  /**
    * Writes the profile's brain.conf to `file` after checking that it constructs a Settings, read the way brain reads
    * it (Settings.scala:250-254).
    */
  def write(file: Path, overrides: String): Path = {
    val text = render(overrides)
    new Settings(ConfigFactory.parseString(text).getConfig("opencomputers"))
    Files.createDirectories(file.getParent)
    Files.write(file, text.getBytes(StandardCharsets.UTF_8))
    file
  }
}
