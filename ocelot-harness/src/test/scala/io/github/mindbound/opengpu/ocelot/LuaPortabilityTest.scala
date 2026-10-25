package io.github.mindbound.opengpu.ocelot

import java.nio.file.{Files, Path, Paths}

import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}
import org.junit.jupiter.api.{Tag, Test, Timeout}

import scala.jdk.CollectionConverters._

/**
  * 28 §9.1's portability rules on the harness's Lua (S10 plan P8.4, P14.1): the scanner flags each construct it is
  * for in synthetic Lua, and nothing in comments, strings or the allowed look-alikes (N6); and every Lua file the
  * harness loads passes it.
  */
@Tag("ocelot-unit")
class LuaPortabilityTest {
  @Test
  @Timeout(30)
  def theScannerFlagsEachRuleAndNothingElse(): Unit = {
    val flagged = Seq(
      "local a = 7 // 2" -> "floor division",
      "local a = x & 1" -> "bitwise operator",
      "local a = x | 1" -> "bitwise operator",
      "local a = x << 1" -> "bitwise operator",
      "local a = x >> 1" -> "bitwise operator",
      "local a = ~x" -> "bitwise operator",
      "local a = x ~ y" -> "bitwise operator",
      "local a = bit32.band(x, 1)" -> "bit32",
      "local t = computer.realTime()" -> "computer.realTime",
      "local i = s:find('a', 1, true)" -> "pattern function",
      "local m = string.match(s, '%d+')" -> "pattern function",
      "local r = string.gsub(s, 'a', 'b')" -> "pattern function",
      "for w in s:gmatch('.') do end" -> "pattern function",
      "local s = string.format(\"%d\", x)" -> "string.format with %d",
      "local s = string.format('%5i', x)" -> "string.format with %d",
      "local s = (\"%-3d\"):format(x)" -> "string.format with %d",
      "local s = string.char(table.unpack(t))" -> "variadic string.char",
      "local s = char(unpack(t))" -> "variadic string.char",
      "local function f(...) return string.char(...) end" -> "variadic string.char",
      "local x = 1LL" -> "LL literal")
    flagged.foreach { case (lua, rule) =>
      assertEquals(Seq(rule), LuaScanner.scan(lua).map(_.rule), s"scan of: $lua")
    }
    val clean = Seq(
      "if a ~= b then return a .. b end",
      "-- x // y, bit32, s:find(p) and computer.realTime()\nlocal a = 1",
      "--[[ x & y\n | z ]] local s = \"a // b | c ~ d\"",
      "--[==[ ]] still a comment << ]==] local n = 0",
      "local s = [[x << y]] .. [==[ ~x ]==]",
      "local s = string.format(\"%s and %5.1f\", x, y)",
      "local s = string.char(1, 2, 3) .. char(0x30, x % 256, floor(x / 256))",
      "local t = { realTime = 1, find = 2 }",
      "local function f(...) return select(\"#\", ...) end",
      "local e = 1e-3 + 0x1F + 2.5E+2")
    clean.foreach(lua => assertEquals(Seq.empty, LuaScanner.scan(lua), s"scan of: $lua"))
  }

  @Test
  @Timeout(30)
  def everyHarnessLuaFilePasses(): Unit = {
    val dir = Paths.get(classOf[LuaPortabilityTest].getResource("/lua/bios.lua").toURI).getParent
    val files = list(dir).filter(_.getFileName.toString.endsWith(".lua"))
    val names = files.map(_.getFileName.toString)
    assertTrue(Seq("bios.lua", "harness.lua", "ogpu_builders.lua").forall(names.contains),
      s"the harness's Lua files: ${names.mkString(", ")}")
    val findings = files.flatMap { f =>
      LuaScanner.scan(new String(Files.readAllBytes(f), "UTF-8")).map(finding => s"${f.getFileName} $finding")
    }
    assertEquals(Seq.empty, findings, "28 §9.1 constructs in the harness's Lua")
    Report.line(s"LuaPortabilityTest: ${files.size} Lua files pass: ${files.map(_.getFileName).mkString(", ")}")
  }

  private def list(dir: Path): Seq[Path] = {
    val entries = Files.list(dir)
    try entries.iterator().asScala.toList.sortBy(_.getFileName.toString) finally entries.close()
  }
}
