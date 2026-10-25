package io.github.mindbound.opengpu.ocelot

import java.nio.file.{Files, Paths}

import com.typesafe.config.{ConfigException, ConfigFactory}
import org.junit.jupiter.api.Assertions.{assertEquals, assertThrows, assertTrue}
import org.junit.jupiter.api.{Tag, Test, Timeout}
import totoro.ocelot.brain.Settings

import scala.jdk.CollectionConverters._

/**
  * BrainConf without Ocelot (S10 plan P9.2, P14.1): the rendered file is complete and carries each profile's overrides,
  * a misspelt path is refused (N1), and an override block on its own is 19 §9's trap, a file brain could not load and
  * would replace with its defaults without failing (N2).
  */
@Tag("ocelot-unit")
class BrainConfTest {
  private val profiles = Seq(Profiles.Pack, Profiles.Instance)

  @Test
  @Timeout(30)
  def theRenderedFileHoldsEveryPathOfTheDefaults(): Unit = {
    val defaults = BrainConf.defaults().entrySet().asScala.map(_.getKey).toSet
    assertTrue(defaults.size > 100, s"brain's application.conf has ${defaults.size} paths")
    profiles.foreach { p =>
      val rendered = ConfigFactory.parseString(BrainConf.render(p.overrides))
      val missing = defaults.filterNot(rendered.hasPath).toList.sorted
      assertEquals(Nil, missing.take(10), s"${p.name}: ${missing.size} of ${defaults.size} default paths missing")
    }
  }

  @Test
  @Timeout(30)
  def theOverridesApply(): Unit = profiles.foreach { p =>
    val layer = ConfigFactory.parseString(p.overrides)
    val rendered = ConfigFactory.parseString(BrainConf.render(p.overrides))
    val paths = layer.entrySet().asScala.map(_.getKey).toList.sorted
    assertTrue(paths.size >= 10, s"${p.name}: ${paths.size} overrides")
    paths.foreach(path => assertEquals(layer.getValue(path).unwrapped(), rendered.getValue(path).unwrapped(),
      s"${p.name}: $path"))
  }

  @Test
  @Timeout(30)
  def anUnknownPathIsRefused(): Unit = {
    val file = Paths.get(Brain.property("opengpu.ocelot.runDir")).resolve("unit").resolve("misspelt.conf")
    val e = assertThrows(classOf[IllegalArgumentException],
      () => BrainConf.write(file, "opencomputers.computer.maxSignalQueueSise = 1024\n"))
    assertTrue(e.getMessage.contains("opencomputers.computer.maxSignalQueueSise"), e.getMessage)
    assertTrue(!Files.exists(file), s"$file was written")
  }

  @Test
  @Timeout(30)
  def theOverrideBlockAloneIsNoSettings(): Unit = profiles.foreach { p =>
    assertThrows(classOf[ConfigException.Missing],
      () => { new Settings(ConfigFactory.parseString(p.overrides).getConfig("opencomputers")); () }, p.name)
  }

  @Test
  @Timeout(30)
  def bothProfilesConstructTheirValues(): Unit = profiles.foreach { p =>
    val s = new Settings(ConfigFactory.parseString(BrainConf.render(p.overrides)).getConfig("opencomputers"))
    assertEquals(p.callBudgets, s.callBudgets.toSeq, s"${p.name}: callBudgets")
    assertEquals(p.maxSignalQueueSize, s.maxSignalQueueSize, s"${p.name}: maxSignalQueueSize")
    assertEquals(p.ramSizes, s.ramSizes.toSeq, s"${p.name}: ramSizes")
    assertEquals(p.allowBytecode, s.allowBytecode, s"${p.name}: allowBytecode")
    assertEquals(p.threads, s.threads, s"${p.name}: threads")
    assertEquals(p.timeout, s.timeout, s"${p.name}: timeout")
    assertEquals(p.enableLua54, s.enableLua54, s"${p.name}: enableLua54")
    assertEquals(p.registerLuaJArchitecture, s.registerLuaJArchitecture, s"${p.name}: registerLuaJArchitecture")
  }
}
