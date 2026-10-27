package io.github.mindbound.opengpu.ocelot

import scala.jdk.CollectionConverters._

import io.github.mindbound.opengpu.core.fixtures.device.{OracleRecordRenderer, RecordText}
import io.github.mindbound.opengpu.core.font.GlyphTables
import io.github.mindbound.opengpu.core.render.FakeRenderHost
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.{Tag, Timeout}
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
  * The self-test program against the tier-E oracle through real OC (S13 plan P4.4, P6.3), on every arch of the task:
  * 5.3 and 5.2 on the pack's leg, LuaJ in its own task for correctness only (S10 decisions D5), OC-LuaJIT on the local
  * leg. Its host is FakeRenderHost over the oracle with font 0's real table, stepped every tick, as SceneOracleTest's,
  * so the readback it delivers for the program's frame is the oracle's rendering of exactly the records the program
  * submitted. On that readback Java derives the expected digests and holds the program's EXPECT block and printed
  * digests to them, with SelfTest.check's other checks; a change of the frame, the oracle, font 0 or the caps fails
  * here first, and the failure message gives the block to copy into the program. The records offered begin RESET,
  * TEX_CREATE, TEX_UPDATE, FRAME, TEX_FREE: the sprite's update reached the frame, and its free, made while the frame
  * was open, followed the frame's FRAME (28 §6.4, O4).
  *
  * Those checks hold the digests before the verdict, so they cannot show that the program's own comparison, its only
  * check in game, ever FAILs (S13 review tests-1). Two runs on T2 do: the program with its block's line for T2 changed,
  * once with the first digest wrong and once with one digest too many, FAILs its pixels line as its comparison says,
  * still prints Java's digests and passes its sweep, and gives `opengpu-selftest FAIL 1`. The comparison does not
  * depend on the tier, so T2's two chunks are enough.
  */
@Tag("ocelot-pack")
class SelfTestOracleTest {
  @ParameterizedTest(name = "{0} T{1}")
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.SelfTest#cases"))
  @Tag("ocelot-luaj")
  @Timeout(120)
  def theProgramPassesOnTheOracle(arch: Arch, tier: Int): Unit = {
    val fake = new FakeRenderHost(new OracleRecordRenderer(GlyphTables.font0()))
    Rig.using(SelfTest.spec(s"SelfTestOracleTest-$arch-T$tier", arch, tier, fake, () => fake.step())) { rig =>
      rig.awaitFinish(maxTicks = 1200)
      val key = rig.gpu.device.key()
      val readbacks = fake.readbacks(key).asScala
      val pixels = SelfTest.check(rig, tier, f => readbacks.filter(_.frameId == f).lastOption.map(_.pixels), None)
      val kinds = fake.offered(key).asScala.map(r => RecordText.of(r).takeWhile(_ != ' ')).take(5)
      assertEquals(Seq("RESET", "TEX_CREATE", "TEX_UPDATE", "FRAME", "TEX_FREE"), kinds, "the records offered first")
      Report.line(s"SelfTestOracleTest $arch T$tier: frame ${pixels.frame}'s ${pixels.digests.size} chunks " +
        s"${pixels.digests.map(SelfTest.hex).mkString(" ")} are the oracle's, the program's EXPECT and what it " +
        s"printed (${pixels.dropped} dropped noted): ${SelfTest.printed(rig).mkString(" | ")}")
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Archs#task"))
  @Tag("ocelot-luaj")
  @Timeout(120)
  def aWrongDigestFailsTheProgram(arch: Arch): Unit = {
    val want = SelfTest.expected(SelfTest.programLines, 2)
    val wrong = (want.head ^ 1L) +: want.tail
    fails(arch, "wrong", wrong, computed => s"FAIL chunk 1 rows 0-${SelfTest.rows(2) - 1} got " +
      s"${SelfTest.hex(computed.head)} want ${SelfTest.hex(wrong.head)}")
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource(Array("io.github.mindbound.opengpu.ocelot.Archs#task"))
  @Tag("ocelot-luaj")
  @Timeout(120)
  def anExtraDigestFailsTheProgram(arch: Arch): Unit = {
    val want = SelfTest.expected(SelfTest.programLines, 2)
    fails(arch, "extra", want :+ want.last, computed => s"FAIL ${computed.size} chunks, want ${computed.size + 1}")
  }

  /** The program on T2 with `digests` in its block's T2 line, held to SelfTest.checkFails with this `verdict`. */
  private def fails(arch: Arch, what: String, digests: Seq[Long], verdict: Seq[Long] => String): Unit = {
    val fake = new FakeRenderHost(new OracleRecordRenderer(GlyphTables.font0()))
    val spec = SelfTest.spec(s"SelfTestOracleTest-$arch-$what", arch, 2, fake, () => fake.step(),
      program = SelfTest.withDigests(2, digests))
    Rig.using(spec) { rig =>
      rig.awaitFinish(maxTicks = 1200)
      val readbacks = fake.readbacks(rig.gpu.device.key()).asScala
      val run = SelfTest.checkFails(rig, 2, f => readbacks.filter(_.frameId == f).lastOption.map(_.pixels), verdict)
      Report.line(s"SelfTestOracleTest $arch $what: with ${SelfTest.digestLines(2, digests).mkString(" ")} the " +
        s"program printed: ${run.lines.mkString(" | ")}")
    }
  }
}
