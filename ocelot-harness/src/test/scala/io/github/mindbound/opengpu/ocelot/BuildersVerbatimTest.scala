package io.github.mindbound.opengpu.ocelot

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import org.junit.jupiter.api.Assertions.{assertEquals, fail}
import org.junit.jupiter.api.{Tag, Test, Timeout}

import scala.jdk.CollectionConverters._

/**
  * The harness's encoder is 28 §9.2's [proposal; S10 plan P14.1]: ogpu_builders.lua, after its leading comment lines,
  * equals the Lua code block under 28's "9.2 Reference record builders" heading, found by the heading and the block's
  * fences rather than by line numbers. The build passes 28 as `opengpu.ocelot.docs28` and declares it an input of the
  * task, so an edit to 28 runs this again rather than restoring an earlier pass from the build cache (S10 amendments
  * I10). The self-test program carries its own copy, since the game has no harness library to load, and the lines
  * between its two marker comments equal the same block (S13 plan P3.13, P6.4).
  */
@Tag("ocelot-unit")
class BuildersVerbatimTest {
  @Test
  @Timeout(30)
  def theBuildersAre28sBlock(): Unit = {
    val (block, open, close) = block28()
    // Line by line, as readAllLines reads 28, so a checkout's line endings do not matter
    val resource = Rig.resource("ogpu_builders.lua").split("\r?\n", -1).toList
    val builders = resource.dropWhile(l => l.startsWith("--") || l.isEmpty).reverse.dropWhile(_.isEmpty).reverse
    same("ogpu_builders.lua", block, builders, open, close)
    Report.line(s"BuildersVerbatimTest: ogpu_builders.lua is 28's block of ${block.size} lines (28:${open + 2}-$close)")
  }

  @Test
  @Timeout(30)
  def theSelfTestsBuildersAre28sBlock(): Unit = {
    val (block, open, close) = block28()
    val program = SelfTest.programLines
    val begin = program.indexOf(SelfTest.BuildersBegin)
    val end = program.indexOf(SelfTest.BuildersEnd, begin + 1)
    if (begin < 0 || end < 0) fail(s"${SelfTest.Program} has no builders between '${SelfTest.BuildersBegin}' and " +
      s"'${SelfTest.BuildersEnd}'")
    same(SelfTest.Program, block, program.slice(begin + 1, end), open, close)
    Report.line(s"BuildersVerbatimTest: ${SelfTest.Program}'s lines ${begin + 2}-$end are 28's block of " +
      s"${block.size} lines (28:${open + 2}-$close)")
  }

  /** 28's block under its 9.2 heading, with the 0-based lines of its opening and closing fences. */
  private def block28(): (List[String], Int, Int) = {
    val doc = Files.readAllLines(Paths.get(Brain.property("opengpu.ocelot.docs28")), StandardCharsets.UTF_8).asScala
    val heading = doc.indexWhere(_.startsWith("### 9.2 Reference record builders"))
    if (heading < 0) fail("28 has no \"### 9.2 Reference record builders\" heading")
    val open = doc.indexWhere(_ == "```lua", heading)
    val close = doc.indexWhere(_ == "```", open + 1)
    if (open < 0 || close < 0) fail(s"no ```lua block after 28's 9.2 heading (line ${heading + 1})")
    val next = doc.indexWhere(_.startsWith("#"), heading + 1)
    if (next >= 0 && next < open) fail(s"28's first ```lua block after 9.2 (line ${open + 1}) is past the next heading")
    (doc.slice(open + 1, close).toList, open, close)
  }

  private def same(name: String, block: List[String], builders: Seq[String], open: Int, close: Int): Unit = {
    val first = block.zipAll(builders, "<none>", "<none>").indexWhere { case (a, b) => a != b }
    if (first >= 0) {
      fail(s"$name differs from 28's block (28:${open + 2}-${close}) at its line ${first + 1}: 28 has " +
        s"'${block.lift(first).getOrElse("<none>")}', the resource '${builders.lift(first).getOrElse("<none>")}'")
    }
    assertEquals(block, builders.toList)
  }
}
